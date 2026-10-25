package com.tv.live.juzhi;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.M3uDiskCache;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 橘汁点播 M3U 加载器。
 *
 * <p>接口：{@code POST /api/proto/v5/drama/search}，参数
 * {@code searchKeys & page & pagesize}（pagesize 为小写，来自原版
 * {@code ExtendUtilsKt.getRequestVideoListParams}）。
 *
 * <p>响应 {@code DramaBeanPage}：{@code repeated DramaBean dramaBean = 1}；
 * DramaBean：area=1, cover_image=2(path=1, thumbnail_path=2), id=3, name=5,
 * update_time=10, remark=13, year=14, clazz=15。
 *
 * <p>M3U 里地址写作 {@code juzhi://<vodId>}，起播时由 {@link JzUrlResolver} 换成 m3u8。
 */
public final class JzSourceLoader {

    private static final String TAG = "JzSourceLoader";

    private static final String CACHE_FILE = "juzhi9_source_v1.m3u";
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;
    private static final int PAGE_SIZE = 20;

    /** 直播分类 typeId（tag/list 里「直播」的 typeId） */
    private static final int LIVE_TYPE_ID = 25;

    /** 直播大分组前缀（频道分组会拼在后面，如「橘汁·直播 · CCTV直播」） */
    private static final String LIVE_GROUP_PREFIX = "橘汁·直播 · ";

    /** 关键词 → 分组名（顺序即 M3U 分组顺序） */
    private static final String[][] GROUPS = {
            {"电影", "橘汁·电影"},
            {"电视剧", "橘汁·电视剧"},
            {"综艺", "橘汁·综艺"},
            {"动漫", "橘汁·动漫"},
            {"纪录片", "橘汁·纪录片"},
    };

    private static final M3uDiskCache sCache = new M3uDiskCache(
            TAG, "源9(橘汁)", CACHE_FILE, CACHE_TTL_MS, FAIL_COOLDOWN_MS,
            new M3uDiskCache.Fetcher() {
                @Override
                public String fetch() {
                    return buildM3u();
                }
            });

    private JzSourceLoader() {
    }

    /** 取 M3U 文本（磁盘缓存 + SWR）。 */
    public static String loadM3u(Context context) {
        return sCache.load(context);
    }

    // ------------------------------------------------------------------

    private static String buildM3u() {
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        Set<Integer> seen = new HashSet<>();
        int groups = 0;
        int count = 0;

        for (String[] g : GROUPS) {
            String keyword = g[0];
            String groupName = g[1];
            List<Drama> list = search(keyword);
            if (list == null || list.isEmpty()) {
                LogBridge.w(TAG, "关键词[" + keyword + "] 无结果");
                continue;
            }
            groups++;
            int added = 0;
            for (Drama d : list) {
                if (d.id <= 0 || TextUtils.isEmpty(d.name)) continue;
                if (!seen.add(d.id)) continue;
                appendItem(m3u, String.valueOf(d.id), d.name, d.poster, groupName, d.remark);
                added++;
            }
            count += added;
            LogBridge.i(TAG, "[" + groupName + "] 关键词=" + keyword
                    + " 返回=" + list.size() + " 新增=" + added);
        }

        // 直播频道（每个 list 条目 = 一张频道卡）
        int liveCount = appendLive(m3u);

        if (count == 0 && liveCount == 0) {
            // 不返回空壳：交给 M3uDiskCache 按失败处理（5 分钟冷却、不写盘），
            // 避免一次瞬时失败把空列表固化 6 小时。
            LogBridge.w(TAG, "点播与直播均无结果，本次按失败处理（不写缓存）");
            return null;
        }
        LogBridge.i(TAG, "M3U 完成：" + groups + " 个点播组 / " + count
                + " 条，直播 " + liveCount + " 条");
        return m3u.toString();
    }

    // ------------------------------------------------------------------
    // 直播（typeId1=25）
    // ------------------------------------------------------------------

    /**
     * 追加橘汁直播频道。
     *
     * <p>接口：{@code GET /api/ex/v3/security/drama/list?query=<双重加密>}，
     * 内层参数 {@code page&pagesize&typeId1=25}。返回 JSON：
     * <pre>
     *   {"list":[{"name":"CCTV直播","id":223354,
     *             "videos":[{"titleOld":"CCTV1综合","path":"zb-cctv1","source":"cctv"}]}]}
     * </pre>
     * 每个 {@code list} 条目在 App 里就是一张频道卡；{@code titleOld} 才是真实台名，
     * {@code name} 是页面上的分组名（CCTV直播 / 卫视频道 / 体育频道 …）。
     *
     * <p>播放地址仍需 POST {@code videoUsableUrl}（vodPlayFrom=source, playUrl=path）换取。
     *
     * @return 追加的频道条数
     */
    private static int appendLive(StringBuilder m3u) {
        int added = 0;
        try {
            // 【2026-10-23 修复 · 直播分组】
            //   原 drama/list?typeId1=25 已失效（返回 null）→ 直播 0 条。
            //   改用 category?typeId1=25（protobuf，DramaBeanPage）：
            //   每个 dramaBean = 一个直播分组（id=3 / name=5，如 CCTV直播/卫视频道/体育频道…），
            //   再对每个分组 getDetail(id) 拿该分组的频道列表（DramaDetailBean.videos）。
            Map<String, String> cp = new LinkedHashMap<>();
            cp.put("page", "1");
            cp.put("pagesize", "50");
            cp.put("typeId1", String.valueOf(LIVE_TYPE_ID));
            byte[] catData = JzApi.post(JzProtocol.pathCategory(), cp);
            if (catData == null || catData.length == 0) {
                LogBridge.w(TAG, "直播分组列表为空（category 无数据）");
                return 0;
            }
            List<byte[]> groupBeans = JzProtocol.messages(catData, 1);   // DramaBeanPage.dramaBean
            LogBridge.i(TAG, "直播分组数=" + groupBeans.size());
            Set<String> seenLive = new HashSet<>();
            for (byte[] gb : groupBeans) {
                int gid = (int) JzProtocol.num(gb, 3, 0);     // DramaBean.id
                String gname = JzProtocol.str(gb, 5);          // DramaBean.name
                if (gid <= 0 || TextUtils.isEmpty(gname)) continue;

                Map<String, String> dp = new LinkedHashMap<>();
                dp.put("id", String.valueOf(gid));
                byte[] dd = JzApi.post(JzProtocol.pathDetail(), dp);
                List<byte[]> chans = (dd == null) ? new ArrayList<byte[]>()
                        : JzProtocol.messages(dd, 29);         // DramaDetailBean.videos
                LogBridge.i(TAG, "  分组[" + gname + "] 频道=" + chans.size());
                int chIdx = 0;
                for (byte[] ch : chans) {
                    if (chIdx < 2) {
                        LogBridge.i(TAG, "【诊断·频道】g=" + gname
                                + " f2=" + JzProtocol.str(ch, 2)
                                + " f3=" + JzProtocol.str(ch, 3)
                                + " f4=" + JzProtocol.str(ch, 4)
                                + " f7=" + JzProtocol.str(ch, 7)
                                + " f8=" + JzProtocol.str(ch, 8)
                                + " f10=" + JzProtocol.str(ch, 10)
                                + " f11=" + JzProtocol.str(ch, 11)
                                + " f13=" + JzProtocol.num(ch, 13, -1));
                    }
                    chIdx++;
                    String titleOld = JzProtocol.str(ch, 3);   // 真实频道名（titleOld）
                    String title = JzProtocol.str(ch, 2);      // 分组内序号
                    String path = JzProtocol.str(ch, 4);
                    String source = JzProtocol.str(ch, 9);
                    if (TextUtils.isEmpty(path) || TextUtils.isEmpty(source)) continue;
                    String key = source + "/" + path;
                    if (!seenLive.add(key)) continue;
                    // 频道名优先真实名（titleOld），其次序号，最后分组名
                    String titleDisp = !TextUtils.isEmpty(titleOld) ? titleOld
                            : (!TextUtils.isEmpty(title) ? title : gname);
                    appendItem(m3u, "live/" + source + "/" + path, titleDisp,
                            catvodLogo(titleDisp), LIVE_GROUP_PREFIX + gname, "");
                    added++;
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "直播解析异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        LogBridge.i(TAG, "直播频道 " + added + " 条（前缀：" + LIVE_GROUP_PREFIX + "）");
        return added;
    }

    /**
     * 【2026-10-23 诊断】探测"直播分组 + 完整频道列表"的正确接口。
     *
     * <p>背景：现用 {@code drama/list?typeId1=25} 每个分组的 videos 只有 1 个，
     * 怀疑它返回的是"推荐剧集"而非"分组+频道"。橘汁原版的分组在
     * {@code NormalTagsBean.sections}（SectionBean: sectionName + vodList）。
     * 本方法把候选接口的响应结构 dump 到日志，供确认后定实现（不影响现有行为）。
     */
    private static void probeLiveApis() {
        // ① 现用接口 drama/list：看每个分组的 videos 数
        try {
            String j1 = JzApi.getJson(JzProtocol.pathLiveList(),
                    "page=1&pagesize=200&typeId1=" + LIVE_TYPE_ID);
            LogBridge.i(TAG, "【诊断·drama/list】len=" + (j1 == null ? "null" : j1.length()));
            if (j1 != null) {
                org.json.JSONArray arr = new org.json.JSONObject(j1).optJSONArray("list");
                LogBridge.i(TAG, "【诊断·drama/list】list.size=" + (arr == null ? -1 : arr.length()));
                if (arr != null) {
                    for (int i = 0; i < Math.min(3, arr.length()); i++) {
                        org.json.JSONObject g = arr.optJSONObject(i);
                        if (g == null) continue;
                        org.json.JSONArray vs = g.optJSONArray("videos");
                        LogBridge.i(TAG, "【诊断·drama/list】[" + g.optString("name")
                                + "] videos=" + (vs == null ? 0 : vs.length()));
                    }
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "【诊断·drama/list】异常 " + t.getMessage());
        }

        // ② tag/list：解析出每个 tag 的 sections（分组）与 vodList（频道）
        try {
            String j2 = JzApi.getJson(JzProtocol.pathTagList(), "page=1&pagesize=100");
            LogBridge.i(TAG, "【诊断·tag/list】len=" + (j2 == null ? "null" : j2.length()));
            if (j2 != null && j2.length() > 0) {
                org.json.JSONArray tags = null;
                String t = j2.trim();
                if (t.startsWith("[")) {
                    tags = new org.json.JSONArray(t);
                } else {
                    org.json.JSONObject root = new org.json.JSONObject(t);
                    tags = root.optJSONArray("list");
                    if (tags == null) tags = root.optJSONArray("data");
                }
                LogBridge.i(TAG, "【诊断·tag/list】tag数=" + (tags == null ? -1 : tags.length()));
                if (tags != null) {
                    for (int i = 0; i < tags.length(); i++) {
                        org.json.JSONObject tag = tags.optJSONObject(i);
                        if (tag == null) continue;
                        String tagName = tag.optString("name", "");
                        if (tagName.contains("直播") || i < 2) {   // 只详列"直播"类与前 2 个
                            org.json.JSONArray secs = tag.optJSONArray("sections");
                            LogBridge.i(TAG, "【诊断·tag/list】tag[" + tagName + "] typeId="
                                    + tag.optInt("typeId", -1) + " sections="
                                    + (secs == null ? 0 : secs.length()));
                            if (secs != null) {
                                for (int s = 0; s < Math.min(5, secs.length()); s++) {
                                    org.json.JSONObject sec = secs.optJSONObject(s);
                                    if (sec == null) continue;
                                    org.json.JSONArray vl = sec.optJSONArray("vodList");
                                    LogBridge.i(TAG, "【诊断·tag/list】  section["
                                            + sec.optString("sectionName", "") + "] vodList="
                                            + (vl == null ? 0 : vl.length()));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "【诊断·tag/list】异常 " + t.getMessage());
        }

        // ③ category（protobuf）：data 解析 DramaBeanPage
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("page", "1");
            p.put("pagesize", "50");
            p.put("typeId1", String.valueOf(LIVE_TYPE_ID));
            byte[] data = JzApi.post(JzProtocol.pathCategory(), p);
            LogBridge.i(TAG, "【诊断·category】data.len=" + (data == null ? "null" : data.length));
            if (data != null && data.length > 0) {
                List<byte[]> pages = JzProtocol.messages(data, 1);   // DramaBeanPage.dramaBean
                LogBridge.i(TAG, "【诊断·category】dramaBean数=" + pages.size());
                for (int i = 0; i < Math.min(5, pages.size()); i++) {
                    byte[] db = pages.get(i);
                    LogBridge.i(TAG, "【诊断·category】[" + JzProtocol.str(db, 5)   // name = 5
                            + "] id=" + JzProtocol.num(db, 3, 0));               // id = 3
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "【诊断·category】异常 " + t.getMessage());
        }
    }

    /**
     * 【2026-10-23 台标】catvod 图床台标 URL，与内置源1 同一口径
     * （{@code https://logo.catvod.com/<频道名>.png}）。
     * 无对应图时返回 404，前端 {@code ChannelLogoLoader} 会回退占位图。
     */
    private static String catvodLogo(String channelName) {
        if (TextUtils.isEmpty(channelName)) return "";
        return "https://logo.catvod.com/" + channelName.trim() + ".png";
    }

    private static List<Drama> search(String keyword) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("searchKeys", keyword);
        params.put("page", "1");
        params.put("pagesize", String.valueOf(PAGE_SIZE));

        byte[] data = JzApi.post(JzProtocol.pathSearch(), params);
        if (data == null || data.length == 0) {
            LogBridge.w(TAG, "search 响应为空 keyword=" + keyword);
            return null;
        }
        List<Drama> out = new ArrayList<>();
        for (byte[] raw : JzProtocol.messages(data, 1)) {
            Drama d = new Drama();
            d.id = (int) JzProtocol.num(raw, 3, 0);
            d.name = JzProtocol.str(raw, 5);
            d.remark = JzProtocol.str(raw, 13);
            byte[] cover = JzProtocol.firstMessage(raw, 2);
            if (cover != null) {
                String thumb = JzProtocol.str(cover, 2);
                d.poster = TextUtils.isEmpty(thumb) ? JzProtocol.str(cover, 1) : thumb;
            }
            out.add(d);
        }
        LogBridge.i(TAG, "search[" + keyword + "] data=" + data.length + "B 解析=" + out.size() + " 条");
        return out;
    }

    private static void appendItem(StringBuilder m3u, String vodId, String name,
                                   String poster, String group, String remark) {
        String display = TextUtils.isEmpty(remark) ? name : name + " · " + remark;
        m3u.append("#EXTINF:-1 tvg-id=\"juzhi:").append(esc(vodId)).append('"');
        if (!TextUtils.isEmpty(poster)) {
            m3u.append(" tvg-logo=\"").append(esc(poster)).append('"');
        }
        m3u.append(" group-title=\"").append(esc(group)).append("\",")
                .append(esc(display)).append('\n');
        m3u.append(JzProtocol.SCHEME).append(esc(vodId)).append('\n');
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }

    private static final class Drama {
        int id;
        String name = "";
        String poster = "";
        String remark = "";
    }
}
