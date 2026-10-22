package com.tv.live.chanxiang;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.tv.live.Channel;
import com.tv.live.util.LogBridge;
import com.tv.live.util.M3uDiskCache;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.tv.live.security.SecurityCore;
import okhttp3.Response;

/**
 * 畅享TV 源加载器（核心）。
 *
 * 流程：
 *   1) 遍历有效 tagId 取频道清单（字段：tvName / contentId / tvLogo）
 *   2) 固定 8 线程并行调 /channel/get_channel_source 取 sourceLabels
 *      （第三方站点允许有上限并发；「请求必须串行」红线仅约束自有服务器，见 project_memory）
 *   3) 只保留 http(s) 源（实测约 84% 频道有），按清晰度降序：首个为主源，其余为备源
 *   4) 输出 M3U 文本，交给 PlaylistParser 复用既有解析链路
 *
 * 说明：miguhd:// migu:// cctvh5:// 等私有协议源不在本加载器解析范围内，
 *      直接丢弃（避免把不可播地址塞进播放器）。
 */
public final class ChanxiangSourceLoader {
    private static final String TAG = "ChanxiangSourceLoader";

    /** 实测有效的分组 tagId → 分组名 */
    public static final Map<Integer, String> TAG_GROUPS = new LinkedHashMap<>();

    static {
        TAG_GROUPS.put(9, "央视");
        TAG_GROUPS.put(10, "卫视");
        TAG_GROUPS.put(12, "体育");
        TAG_GROUPS.put(15, "轮播");
        TAG_GROUPS.put(21, "央视精选");
        TAG_GROUPS.put(22, "纪实");
        TAG_GROUPS.put(23, "教育");
    }

    /** 解析后的频道条目 */
    public static class ChannelItem {
        public String name;
        public String group;
        public String tvgId;
        public int tvId;
        public String logoUrl;
        public String mainUrl = "";
        public List<String> backupUrls = new ArrayList<>();
        public List<String> lineLabels = new ArrayList<>();
        /** 是否拿到可直接播放的 http(s) 源 */
        public boolean playable;

        public ChannelItem(String name, String group, String tvgId) {
            this.name = name;
            this.group = group;
            this.tvgId = tvgId;
            try {
                this.tvId = Integer.parseInt(tvgId);
            } catch (Exception ignored) {
            }
        }
    }

    private ChanxiangSourceLoader() {}

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 拉取全部频道并解析播放源 */
    public static List<ChannelItem> loadAllLive() {
        List<ChannelItem> all = new ArrayList<>();
        Map<String, ChannelItem> seen = new LinkedHashMap<>();

        for (Map.Entry<Integer, String> e : TAG_GROUPS.entrySet()) {
            int tagId = e.getKey();
            String groupName = e.getValue();
            try {
                String json = ChanxiangApi.getTagList(tagId, groupName);
                if (json == null || json.isEmpty()) continue;
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    // 实测字段：tvName / contentId / tvLogo
                    String name = o.optString("tvName", o.optString("name", ""));
                    String id = o.optString("contentId", o.optString("tvId", ""));
                    if (name.isEmpty() || id.isEmpty()) continue;
                    if (seen.containsKey(id)) continue;
                    ChannelItem it = new ChannelItem(name, groupName, id);
                    it.logoUrl = o.optString("tvLogo", o.optString("logo", ""));
                    seen.put(id, it);
                    all.add(it);
                }
                LogBridge.d(TAG, "分组 " + groupName + "(" + tagId + ") 取到 " + arr.length() + " 条");
            } catch (Exception ex) {
                LogBridge.w(TAG, "分组 " + groupName + "(" + tagId + ") 拉取失败: " + ex.getMessage());
            }
        }

        LogBridge.i(TAG, "共 " + all.size() + " 个频道，开始解析播放源…");
        resolveAll(all);

        int ok = 0;
        for (ChannelItem it : all) if (it.playable) ok++;
        LogBridge.i(TAG, "解析完成：可播 " + ok + " / " + all.size());
        return all;
    }

    // ------------------------------------------------------------------
    // 【2026-10-13 磁盘缓存】照源7（AfhgrjSourceLoader）模板：缓存优先 + TTL +
    //   失败冷却 + 原子写 + 后台刷新单飞。
    //   原实现是纯同步网络（清单 + 逐频道解析源），冷启动必须现场跑完才有分组。
    //
    //   TTL 取 6h：本源 M3U 行地址是「已解析的直链」，签名有效期约 48h
    //   （见 TVPlayerManager#tryRefreshChanxiangSource 注释），6h 远小于 48h；
    //   即便个别直链失效，播放路径还有「每频道最多重取 1 次」的自动兜底。
    // ------------------------------------------------------------------
    private static final long M3U_CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;

    private static final M3uDiskCache sM3uCache = new M3uDiskCache(
            TAG, "源3(畅享)", "chanxiang_source.m3u", M3U_CACHE_TTL_MS, FAIL_COOLDOWN_MS,
            new M3uDiskCache.Fetcher() {
                @Override
                public String fetch() {
                    List<ChannelItem> items = loadAllLive();
                    if (items == null || items.isEmpty()) {
                        return null;
                    }
                    String m3u = toM3u(items);
                    return TextUtils.isEmpty(m3u) ? null : m3u;
                }
            });

    /** 拉取全部频道并转成 M3U（供 PlaylistParser.parseContent 复用）；缓存优先 */
    public static String loadAllLiveAsM3u(Context context) {
        return sM3uCache.load(context);
    }

    /** 关键字搜索（在已拉取的清单里本地过滤） */
    public static List<ChannelItem> search(String keyword) {
        List<ChannelItem> out = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return out;
        for (ChannelItem it : loadAllLive()) {
            if (it.name != null && it.name.contains(keyword)) out.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 播放源解析
    // ------------------------------------------------------------------

    /** 并行解析所有频道的播放源 */
    public static void resolveAll(List<ChannelItem> items) {
        if (items == null || items.isEmpty()) return;
        int threads = Math.min(8, items.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (final ChannelItem it : items) {
                futures.add(pool.submit(() -> {
                    try {
                        resolveSource(it);
                    } catch (Throwable t) {
                        LogBridge.w(TAG, it.name + " 源解析异常: " + t.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    /** 解析单个频道的播放源，只保留 http(s)，按清晰度降序 */
    public static void resolveSource(ChannelItem item) {
        if (item == null || item.tvId <= 0) return;
        try {
            String json = ChanxiangApi.getChannelSource(item.tvId);
            if (json == null || json.isEmpty()) return;
            JSONObject root = new JSONObject(json);
            JSONArray labels = root.optJSONArray("sourceLabels");
            if (labels == null) return;

            List<Object[]> httpSources = new ArrayList<>();
            for (int i = 0; i < labels.length(); i++) {
                JSONObject s = labels.optJSONObject(i);
                if (s == null) continue;
                String src = s.optString("source", "");
                if (src.isEmpty()) continue;
                if (!src.startsWith("http://") && !src.startsWith("https://")) continue; // 丢弃私有协议
                int quality = parseIntSafe(s.optString("quality", "0"));
                String label = s.optString("qualityName", "");
                String tag = s.optString("tag", "");
                if (!tag.isEmpty()) label = (label.isEmpty() ? tag : label + " " + tag);
                httpSources.add(new Object[]{src, quality, label});
            }
            if (httpSources.isEmpty()) {
                item.playable = false;
                return;
            }
            httpSources.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));

            item.mainUrl = (String) httpSources.get(0)[0];
            item.backupUrls.clear();
            item.lineLabels.clear();
            for (int i = 1; i < httpSources.size(); i++) {
                item.backupUrls.add((String) httpSources.get(i)[0]);
                item.lineLabels.add((String) httpSources.get(i)[2]);
            }
            item.playable = true;
        } catch (Exception e) {
            LogBridge.w(TAG, item.name + " 源解析失败: " + e.getMessage());
        }
    }

    /** 给定 tvId，实时重新拉取并返回最新的 http(s) 主源 URL（已解密、已过滤私有协议）；失败返回 null */
    public static String getLatestMainUrl(int tvId) {
        try {
            String json = ChanxiangApi.getChannelSource(tvId);
            if (json == null || json.isEmpty()) return null;
            JSONObject root = new JSONObject(json);
            JSONArray labels = root.optJSONArray("sourceLabels");
            if (labels == null) return null;
            for (int i = 0; i < labels.length(); i++) {
                JSONObject s = labels.optJSONObject(i);
                if (s == null) continue;
                String src = s.optString("source", "");
                if (src.isEmpty()) continue;
                if (!src.startsWith("http://") && !src.startsWith("https://")) continue;
                return src; // 第一个 http(s) 即最高清晰度主源
            }
        } catch (Exception e) {
            LogBridge.w(TAG, "getLatestMainUrl(" + tvId + ") 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 【2026-10-07 新增】拉取某频道全部 http(s) 源（含清晰度信息），
     * 供「线路 × 清晰度」轴（{@link ChanxiangStreamAxis}）构建。
     *
     * <p>与 {@link #getLatestMainUrl} 同一接口（get_channel_source），但保留全部 http 源
     * 及其 quality/qualityName/tag，不做「主源/备源」裁剪 —— 轴需要整张源表才能拆出两轴。
     * 失败返回空表（调用方按「轴未就绪」处理）。
     */
    public static List<ChanxiangStreamAxis.HttpSource> fetchHttpSources(int tvId) {
        List<ChanxiangStreamAxis.HttpSource> out = new ArrayList<>();
        try {
            String json = ChanxiangApi.getChannelSource(tvId);
            if (json == null || json.isEmpty()) return out;
            JSONObject root = new JSONObject(json);
            JSONArray labels = root.optJSONArray("sourceLabels");
            if (labels == null) return out;
            for (int i = 0; i < labels.length(); i++) {
                JSONObject s = labels.optJSONObject(i);
                if (s == null) continue;
                String src = s.optString("source", "");
                if (src.isEmpty()) continue;
                if (!src.startsWith("http://") && !src.startsWith("https://")) continue;
                out.add(new ChanxiangStreamAxis.HttpSource(src,
                        parseIntSafe(s.optString("quality", "0")),
                        s.optString("tag", ""),
                        s.optString("qualityName", "")));
            }
        } catch (Exception e) {
            LogBridge.w(TAG, "fetchHttpSources(" + tvId + ") 失败: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // M3U 输出
    // ------------------------------------------------------------------

    public static String toM3u(List<ChannelItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        if (items == null) return sb.toString();
        for (ChannelItem c : items) {
            if (!c.playable || c.mainUrl.isEmpty()) continue;
            sb.append("#EXTINF:-1");
            sb.append(" tvg-id=\"").append(esc(c.tvgId)).append("\"");
            sb.append(" tvg-name=\"").append(esc(c.name)).append("\"");
            if (c.logoUrl != null && !c.logoUrl.isEmpty()) {
                sb.append(" tvg-logo=\"").append(esc(c.logoUrl)).append("\"");
            }
            sb.append(" group-title=\"").append(esc(c.group)).append("\"");
            sb.append(",").append(esc(c.name)).append("\n");
            sb.append(c.mainUrl).append("\n");
        }
        return sb.toString();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    // ==================================================================
    // 以下为合并的内嵌类
    // ==================================================================

    /** MD5 工具（移植自 org.fungo.common.util.MD5Utils） */
    public static final class MD5Utils {
        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private MD5Utils() {}

        public static String getStringMD5(String s) {
            if (s == null) return null;
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] b = md.digest(s.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder(b.length * 2);
                for (byte x : b) {
                    sb.append(HEX[(x >> 4) & 0x0F]).append(HEX[x & 0x0F]);
                }
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 畅享TV 签名工具（移植自 org.fungo.common.util.SignUtils）
     * sign = MD5("fungolive" + nwtime)
     */
    public static final class SignUtils {
        public static final String TAG_SIGN_LIVE = "fungolive";
        public static final String TAG_SIGN_A8 = "fungoa8sport";
        public static final String TAG_SIGN_FUNGOWS = "fungows";

        public static int networkTimeOffset = 0;

        private SignUtils() {}

        public static int getCurrentTime() {
            return (int) (System.currentTimeMillis() / 1000L) - networkTimeOffset;
        }

        public static String getTimeSign(String tag) {
            return getTimeSign(tag, getCurrentTime());
        }

        public static String getTimeSign(String tag, int time) {
            if (tag == null) tag = TAG_SIGN_LIVE;
            return MD5Utils.getStringMD5(tag + time);
        }

        public static String[] buildSignPair() {
            int t = getCurrentTime();
            return new String[]{String.valueOf(t), getTimeSign(TAG_SIGN_LIVE, t)};
        }
    }

    /**
     * 畅享TV 公共请求参数生成器（移植自 HttpUtils.getQueryMap / interceptArguments）
     *
     * 服务集群：
     *   NOWTV_NEW : http://nowtv-new.xiaoyouzb.cn   （主用）
     *   NOWTV     : http://nowtv.xiaoyouzb.cn       （旧版，已废弃）
     *   USER / PROBE / A8
     */
    public static final class ChanxiangConfig {

        // 【2026-10-15 密文化】下列 host 原为 public static final String。编译期常量会被
        // **内联到每个使用点**，dex 里处处可见；改为惰性 getter 后，明文只存在于 SO
        // （app/src/main/cpp/secstore.cpp），Java 按 id 取。
        // ⚠️ 惰性解密铁律：绝不可写回 static final / <clinit> —— SecurityCore.init() 要
        //    loadLibrary（可达数秒），放进类初始化会卡住首访线程（可能是主线程）。
        private static volatile String sNowtvNew, sNowtv, sUser, sProbe, sA8;

        private static String so(int id) {
            String d = SecurityCore.secureString(id);
            return d == null ? "" : d;
        }

        /** 主直播 host（原 NOWTV_NEW）。解密失败返回空串，调用方会得到一次失败请求而非 NPE。 */
        public static String nowtvNew() {
            String v = sNowtvNew;
            if (v == null) v = sNowtvNew = so(SecurityCore.C_API_NOWTV_NEW);
            return v;
        }

        /** 旧版直播 host（原 NOWTV，已废弃；保留以便日后兼容） */
        public static String nowtv() {
            String v = sNowtv;
            if (v == null) v = sNowtv = so(SecurityCore.C_API_NOWTV);
            return v;
        }

        /** 用户服务 host（原 USER） */
        public static String user() {
            String v = sUser;
            if (v == null) v = sUser = so(SecurityCore.C_API_USER_CN);
            return v;
        }

        /** 探测 host（原 PROBE） */
        public static String probe() {
            String v = sProbe;
            if (v == null) v = sProbe = so(SecurityCore.C_API_PROBE);
            return v;
        }

        /** A8 游戏接口 host（原 A8） */
        public static String a8() {
            String v = sA8;
            if (v == null) v = sA8 = so(SecurityCore.C_API_A8);
            return v;
        }

        public static final String USER_AGENT = "yuntutv/5.0.4 (Android 11)";
        public static final String APP_PN = "com.fungo.fungobox";
        public static final String APP_X = "300";
        public static final String VERSION = "5.0.4";

        private ChanxiangConfig() {}

        /** 生成公共查询参数（自动注入 nwtime + sign） */
        public static String buildQuery(Map<String, String> extra) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("version", VERSION);
            p.put("platform", "2");
            p.put("appx", APP_X);
            p.put("apppn", APP_PN);
            p.put("enterprise", "0");
            p.put("channel", "yingyonghui");
            p.put("market", "1024");
            p.put("os_version", "11");
            p.put("device_model", "MiBox");
            p.put("device_code", "");
            p.put("imei", "");
            p.put("udid", "");
            p.put("android_id", "");
            p.put("source", "1");
            p.put("IsVip", "N");
            p.put("_u", "");
            p.put("oaid", "");
            if (extra != null) p.putAll(extra);
            String[] sg = SignUtils.buildSignPair();
            p.put("nwtime", sg[0]);
            p.put("sign", sg[1]);
            return encode(p);
        }

        public static String buildQuery() {
            return buildQuery(null);
        }

        public static String url(String host, String path, Map<String, String> extra) {
            return host + path + "?" + buildQuery(extra);
        }

        private static String encode(Map<String, String> p) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> e : p.entrySet()) {
                if (!first) sb.append('&');
                first = false;
                sb.append(encode(e.getKey())).append('=')
                        .append(encode(e.getValue() == null ? "" : e.getValue()));
            }
            return sb.toString();
        }

        private static String encode(String s) {
            try {
                return URLEncoder.encode(s, "UTF-8");
            } catch (Exception e) {
                return s;
            }
        }
    }

    /**
     * 畅享TV 响应解码（移植自 RequestHandler.requestSuccess + DecodeUtils）
     *
     * 服务端返回体形如：
     *   {"code":0,"data":"<混淆串 或 明文>","hasEncryption":true}
     *
     * 关键：只有 hasEncryption=true 时才需要反混淆；为 false（如
     * /channel/get_channel_id_by_name）时 data 是明文，直接使用。
     *
     * 混淆规则（DecodeUtils.resoveEPGBase64String / resoveSourceBase64String）：
     *   1) 去掉前 2 个字符
     *   2) 再删掉下标 20 处的 1 个垃圾字符
     *   3) 剩余才是标准 Base64
     */
    public static final class ChanxiangDecode {
        private static final String TAG = "ChanxiangDecode";

        private ChanxiangDecode() {}

        /** 取出并按需反混淆后的 data 字符串；失败返回 null */
        public static String dataField(String bodyJson) {
            if (bodyJson == null || bodyJson.isEmpty()) return null;
            try {
                JSONObject root = new JSONObject(bodyJson);
                String data = root.optString("data", null);
                if (data == null || data.isEmpty()) return null;

                if (root.has("hasEncryption")) {
                    boolean enc = root.optBoolean("hasEncryption", false);
                    if (!enc) return data;
                    String d = deobfuscate(data);
                    return d != null ? d : data;
                }
                // 无标记时：先尝试反混淆，失败则按明文返回
                String d = deobfuscate(data);
                return d != null ? d : data;
            } catch (Exception e) {
                LogBridge.w(TAG, "dataField 解析失败: " + e.getMessage());
                return null;
            }
        }

        /**
         * 反混淆 + Base64 解码，移植自 DecodeUtils.resoveSourceBase64String / resoveEPGBase64String
         * 与 RequestHandler.decodeEPG：
         *   去掉前 2 个字符 → 再删掉下标 20 处的 1 个垃圾字符 → 剩余标准 Base64 → UTF-8。
         *
         * 畅享服务端返回的 data 解码后必定是 JSON（对象或数组），因此只在"解码结果以 { 或 [
         * 开头"时才采纳，避免 Base64 部分误命中返回乱码、导致上层 new JSONArray/JSONObject 抛错、
         * 最终"永远解析为空"。
         */
        public static String deobfuscate(String s) {
            if (s == null || s.length() < 24) return null;
            int[] heads = {2, 3};
            int[] cuts = {20, 21, 19};
            for (int head : heads) {
                for (int cut : cuts) {
                    try {
                        StringBuilder b = new StringBuilder(s);
                        if (b.length() <= head + cut + 1) continue;
                        b.delete(0, head);
                        b.deleteCharAt(cut);
                        byte[] raw = Base64.decode(b.toString(), Base64.DEFAULT);
                        String decoded = new String(raw, "UTF-8");
                        if (isJsonLike(decoded)) return decoded;
                    } catch (Exception ignored) {
                    }
                }
            }
            return null;
        }

        private static boolean isJsonLike(String s) {
            if (s == null || s.isEmpty()) return false;
            int i = 0;
            int n = s.length();
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
            if (i >= n) return false;
            char c = s.charAt(i);
            return c == '{' || c == '[';
        }
    }

    /**
     * 畅享TV HTTP API 客户端。
     *
     * 端点均以实测为准（旧版 /channel/get_channel_group、/channel/search_channel
     * 在新集群返回 404，已移除）：
     *   /channel/get_tag_list           按分组取频道列表（tagId 9/10/12/15/21/22/23 有效）
     *   /channel/get_channel_source     取频道播放源
     *   /channel/get_channel_id_by_name 按频道名查 tvId（hasEncryption=false，明文）
     */
    public static final class ChanxiangApi {
        private static final String TAG = "ChanxiangApi";

        private ChanxiangApi() {}

        public static String get(String fullUrl) throws IOException {
            try (Response r = NetUtil.getInstance().syncGet(fullUrl)) {
                if (!r.isSuccessful() || r.body() == null) {
                    throw new IOException("HTTP " + r.code() + " url=" + fullUrl);
                }
                return r.body().string();
            }
        }

        private static String url(String host, String path, Map<String, String> extra) {
            return ChanxiangConfig.url(host, path, extra);
        }

        /**
         * 取某个分组下的频道列表（已解码）。
         * 实测有效 tagId：9=央视 10=卫视 12=体育 15=轮播电影 21/22/23=其他央视/教育
         */
        public static String getTagList(int tagId, String tagName) throws IOException {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("tagId", String.valueOf(tagId));
            q.put("tagName", tagName == null ? "" : tagName);
            String body = get(url(ChanxiangConfig.nowtvNew(), "/channel/get_tag_list", q));
            return ChanxiangDecode.dataField(body);
        }

        /** 取频道播放源（已解码），形如 {"path":..,"sourceLabels":[{"source":..,"quality":..}]} */
        public static String getChannelSource(int tvId) throws IOException {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("tvId", String.valueOf(tvId));
            q.put("uid", "");
            q.put("s_ver", ChanxiangConfig.VERSION);
            String body = get(url(ChanxiangConfig.nowtvNew(), "/channel/get_channel_source", q));
            return ChanxiangDecode.dataField(body);
        }

        /** 按频道名查 tvId（明文返回，例如 "50661"） */
        public static String getChannelIdByName(String name) throws IOException {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("name", name);
            String body = get(url(ChanxiangConfig.nowtvNew(), "/channel/get_channel_id_by_name", q));
            return ChanxiangDecode.dataField(body);
        }
    }

    /**
     * 桥接器：把畅享TV 的频道条目映射成项目内的 com.tv.live.Channel。
     * 主源 = 清晰度最高的 http 源；其余 http 源作为备线（带清晰度标签）。
     */
    public static final class ChanxiangSourceBridge {
        private ChanxiangSourceBridge() {}

        public static List<Channel> toChannels(List<ChanxiangSourceLoader.ChannelItem> items) {
            List<Channel> out = new ArrayList<>();
            if (items == null) return out;
            for (ChanxiangSourceLoader.ChannelItem it : items) {
                if (!it.playable || it.mainUrl == null || it.mainUrl.isEmpty()) continue;
                Channel c = new Channel(it.name, it.mainUrl, it.group, it.tvgId);
                // 【2026-09-29 频道台标】把畅享条目已解析的 tvLogo 传给主 Channel 模型
                if (it.logoUrl != null && !it.logoUrl.isEmpty()) {
                    c.setLogoUrl(it.logoUrl);
                }
                for (int i = 0; i < it.backupUrls.size(); i++) {
                    String label = i < it.lineLabels.size() ? it.lineLabels.get(i) : "";
                    c.addBackupUrl(it.backupUrls.get(i), label);
                }
                out.add(c);
            }
            return out;
        }

        /** 一次性取全部可播频道 */
        public static List<Channel> loadAllLive() {
            return toChannels(ChanxiangSourceLoader.loadAllLive());
        }
    }
}
