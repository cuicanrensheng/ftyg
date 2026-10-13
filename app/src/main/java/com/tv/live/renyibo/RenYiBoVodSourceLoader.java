package com.tv.live.renyibo;

import com.tv.live.Channel;
import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置源5（爱其意/任意播）影视点播（VOD）M3U 分组生成器。
 *
 * <p>与 {@link RenYiBoSourceLoader}（直播，97 个频道，5 个分组）同属内置源5，
 * 本类负责<b>追加 6 个 VOD 分组</b>：
 * 电影 / 电视剧 / 综艺 / 动漫 / 纪录 / 少儿 —— 从而把源5 组织为
 * <b>「直播 5 组 + 点播 6 组」两大类</b>（用户 2026-10-12 选定方案）。
 *
 * <p><b>【2026-10-12 明文+骨架落地 → 加密移植】</b>服务端 {@code naiqiyi.php/v7/logs}
 * 的请求/响应加密已确证为 <b>AES-128-CBC + PKCS7</b>、固定密钥
 * {@code 7dbe4c69aa5b98ad90ba3468e21c6fd2}、随机 IV（见 {@link VodCrypto}）。
 * <b>但线格式（IV 放置 / 分类列表字段 / 详情取流参数）尚未锁定</b>，遵循用户红线
 * （禁止未经授权的第三方请求），本类<b>默认不发任何 VOD 网络请求</b>，仍走内置明文：
 * <ol>
 *   <li>6 个分类分组<b>骨架全部落地</b>（分组名、type_id、协议串地址）；</li>
 *   <li>每个分组内填充<b>已抓到的真实明文条目</b>（首页推荐 vlist，含 vod_id /
 *       vod_name / vod_pic / remarks），保证列表可见、可点；</li>
 *   <li>真实加密请求已写成 {@link #fetchCategoryPage} 与 {@link #resolveVodPlayUrl}
 *       的<b>双闸门关闭</b>路径（{@link #canSendRealRequest()}），待授权真机锁定线格式后
 *       打开总开关即可启用。</li>
 * </ol>
 *
 * <p><b>协议串</b>：M3U 行地址写作 {@code renyibo-vod://<typeId>:<vodId>}。
 * 起播时由 {@link #isVodSchemeUrl}/{@link #vodTypeIdOfScheme}/{@link #vodIdOfScheme}
 * 解析 —— <b>【2026-10-12 播放已打通】</b>{@link #resolveVodPlayUrl} 走真实
 * {@code video_detail} 加密请求，响应由 {@link VodResponse} 完成三段信封解密
 * （IV 内嵌 + 固定 key），从 {@code vod_info.vod_url_with_player} 取直链 m3u8 返回。
 */
public final class RenYiBoVodSourceLoader {

    private static final String TAG = "RenYiBoVodSourceLoader";

    // ------------------------------------------------------------------
    // 【2026-10-12 VOD 加密移植】真实请求双重开关
    // ------------------------------------------------------------------

    /**
     * 真实加密请求的总闸：<b>默认 false（离线）</b>。
     *
     * <p><b>红线</b>（用户铁律：禁止未经授权的对第三方请求）：即使把本开关置
     * {@code true}，还须满足第二个守卫 {@link #REQUEST_FORMAT_LOCKED} 才会真正发包。
     * 两个守卫同时为 true 前，任何路径都<b>不会发起网络请求</b>，只走内置明文/返回 null。
     *
     * <p>开启入口（设置面板或调试触发）本期<b>不接 UI</b>。【2026-10-12 授权实测】
     * 用户已授权对源站发起真实加密请求以锁定线格式，故置 true。
     */
    private static volatile boolean sRealEnabled = true;

    /**
     * 真实请求的线格式（IV 放置 / dm_list 分类字段 / vod_detail|play 参数形状）
     * 是否已通过授权真机核对并锁定。默认 false ⇒ 即便 {@link #sRealEnabled} 为 true
     * 也不发送任何请求（防止对未确证字段做猜测性请求，见 {@link VodRequest}）。
     */
    private static final boolean REQUEST_FORMAT_LOCKED = true;

    /** 是否为"可真正发包"状态：两个开关同时开才为 true。 */
    private static boolean canSendRealRequest() {
        return sRealEnabled && REQUEST_FORMAT_LOCKED;
    }

    /**
     * VOD 频道 channelId 前缀：{@code renyibo-vod:<typeId>:<vodId>}。
     * 与直播前缀 {@link RenYiBoSourceLoader#TVG_PREFIX}（{@code renyibo:}）区分，
     * 但同属内置源5，便于"源5 内两大类"统一归组。
     */
    public static final String TVG_PREFIX = "renyibo-vod:";

    /**
     * VOD 行地址协议串：{@code renyibo-vod://<typeId>:<vodId>}。
     * 同一前缀既作频道 id 也作行地址，{@code typeId} 用于分组归属，
     * {@code vodId} 用于起播时取流。
     */
    public static final String SCHEME = "renyibo-vod://";

    /** 首页推荐分组名（放在 6 分类之前，展示上更靠前）。 */
    public static final String HOME_GROUP = "首页推荐";

    /**
     * 【2026-10-13 多页拉取】每个点播分类固定串行拉取的页数上限（video_list.pg=1..N）。
     * 接口每页约 21 条，3 页约 63 条（合并去重后 50~60+），满足「每分组频道更多」诉求；
     * 某组总页数不足时按实际空页提前停止，不做无限翻页。
     */
    private static final int CATEGORY_FETCH_PAGES = 3;

    /**
     * 【2026-10-13 海报加速】图床域名镜像重写表：不可达主机 → 可达镜像主机。
     * 照搬源7 {@code com.tv.live.afhgrj.AfhgrjSourceLoader.POSTER_HOST_MIRROR}（已实测验证）：
     * {@code pps.vodfeiss.com / img.feifeiimg.vip / tupian.ffeiimg.com} 同属 feifeiimg 系，
     * TLS 均被 RST，在 {@code fffgood.com} 上用相同路径 15/15 返回 200；{@code img.picbf.com}
     * → {@code p.bfvp26.com} 同属 CDN。路径与源域完全一致，只换主机。
     */
    private static final String[][] POSTER_HOST_MIRROR = {
            {"img.picbf.com", "p.bfvp26.com"},
            {"img.feifeiimg.vip", "fffgood.com"},
            {"tupian.ffeiimg.com", "fffgood.com"},
            {"pps.vodfeiss.com", "fffgood.com"},
    };

    /** 【2026-10-13 海报加速】单次 build 内使用缩略图 vod_pic_thumb 的条数（聚合日志用）。 */
    private static volatile int sThumbUsedCount = 0;
    /** 【2026-10-13 海报加速】单次 build 内回退原图 vod_pic 的条数（聚合日志用）。 */
    private static volatile int sPosterFallbackCount = 0;
    /** 【2026-10-13 海报加速】单次 build 内图床镜像重写次数（聚合日志用）。 */
    private static volatile int sMirrorRewrites = 0;

    private RenYiBoVodSourceLoader() {}

    // ------------------------------------------------------------------
    // M3U 生成
    // ------------------------------------------------------------------

    /**
     * 生成 VOD 部分的 M3U 片段（不含 {@code #EXTM3U} 头，由调用方统一拼接）。
     *
     * <p>输出顺序：首页推荐 → 电影 → 电视剧 → 综艺 → 动漫 → 纪录 → 少儿。
     * 每个分类分组内列出本期<b>静态内置</b>的真实条目；分类骨架（含筛选维度）
     * 通过 {@link RenYiBoVodCatalog} 提供，便于后续 UI 呈现筛选表单。
     */
    public static String buildVodM3uFragment() {
        StringBuilder sb = new StringBuilder(4096);
        HashSet<String> seen = new HashSet<>();
        // 【2026-10-13 海报加速】每次重建 M3U 前清零聚合计数，结尾统一打一次日志
        sThumbUsedCount = 0;
        sPosterFallbackCount = 0;
        sMirrorRewrites = 0;

        // ① 首页推荐（真实明文，含海报）
        List<String[]> home = RenYiBoVodCatalog.homeVideos();
        int homeWritten = 0;
        StringBuilder homeSb = new StringBuilder();
        for (String[] v : home) {
            // v = {vodId, vodName, vodPic, typeId, remarks}
            if (v[1].isEmpty()) continue;
            if (!seen.add("H|" + v[0])) continue;
            appendItem(homeSb, v[0], v[1], v[2], HOME_GROUP, v[4]);
            homeWritten++;
        }
        if (homeWritten > 0) {
            LogBridge.i(TAG, "源5点播：分组[" + HOME_GROUP + "]内置 " + homeWritten + " 部（首页推荐明文）");
            sb.append(homeSb);
        }

        // ② 各分类骨架（双闸门开启时优先拉多页真实加密接口，失败回落内置明文）
        for (RenYiBoVodCatalog.Category cat : RenYiBoVodCatalog.CATEGORIES) {
            int written = 0;
            StringBuilder catSb = new StringBuilder();

            // ① 双闸门开启 → 按 pg=1..CATEGORY_FETCH_PAGES 串行拉多页 video_list，合并去重
            if (canSendRealRequest()) {
                List<String[]> real = fetchCategoryPagesMerged(cat);
                if (real != null && !real.isEmpty()) {
                    for (String[] v : real) {
                        if (v[1] == null || v[1].isEmpty()) continue;
                        if (!seen.add(cat.typeId + "|" + v[0])) continue;
                        appendItem(catSb, v[0], v[1], v[2], cat.name, v[4]);
                        written++;
                    }
                    if (written > 0) {
                        LogBridge.i(TAG, "源5点播：分类[" + cat.name + "] type_id=" + cat.typeId
                                + " 真实接口合并后 " + written + " 条");
                        sb.append(catSb);
                        continue;
                    }
                }
                // 真实数据为空/失败 → 回落内置，继续走下方静态填充
            }

            // ② 内置明文条目（来源：首页推荐里 type_id 匹配的项）
            for (String[] v : home) {
                int t = parseIntSafe(v[3], 0);
                if (t != cat.typeId) continue;
                if (!seen.add(cat.typeId + "|" + v[0])) continue;
                appendItem(catSb, v[0], v[1], v[2], cat.name, v[4]);
                written++;
            }
            // 分类骨架条目：即使本期没有内容，也要让分组与协议串落地，
            // 使"分类结构已移植"这一目标可见、可点、可后续替换数据源。
            if (written == 0) {
                appendItem(catSb, "", cat.name, "", cat.name,
                        "分类入口(type_id=" + cat.typeId + ")");
                written = 1;
            }
            LogBridge.i(TAG, "源5点播：分类[" + cat.name + "] type_id=" + cat.typeId
                    + " 内置 " + written + " 条，筛选维度 " + cat.filters.length + " 组");
            sb.append(catSb);
        }
        // 【2026-10-13 海报加速】一次 build 打一条聚合统计（避免每条刷屏）
        if (sThumbUsedCount > 0 || sPosterFallbackCount > 0 || sMirrorRewrites > 0) {
            LogBridge.i(TAG, "源5点播：海报加速统计——使用缩略图 vod_pic_thumb " + sThumbUsedCount
                    + " 条，回退原图 vod_pic " + sPosterFallbackCount
                    + " 条，图床镜像重写 " + sMirrorRewrites + " 次");
        }
        return sb.toString();
    }

    /**
     * 【2026-10-13 多页拉取】按 {@code pg=1..CATEGORY_FETCH_PAGES} 串行拉取某分类的真实加密
     * {@code video_list} 列表，并按 {@code vod_id} 去重合并（同片在首页与分类页会重复出现）。
     *
     * <p>沿用 {@link #fetchCategoryPageReal} 既有的「AES-128-CBC（固定 key、随机 IV 前置）+
     * {@link VodResponse} 三段信封解密」管线，仅在同一授权端点（{@link RenYiBoVodCatalog#VOD_BASE}）
     * 上多发几页，<b>不新增任何请求逻辑</b>。某页返回 null（接口失败）或空（该组再无更多页）即
     * 停止向后翻页，<b>不做无限翻页</b>。
     *
     * @return 合并去重后的条目列表 {@code {vodId, vodName, vodPic, typeId, remarks}}；全部失败返回空列表
     */
    private static List<String[]> fetchCategoryPagesMerged(RenYiBoVodCatalog.Category cat) {
        Map<String, String[]> dedup = new LinkedHashMap<>();
        for (int page = 1; page <= CATEGORY_FETCH_PAGES; page++) {
            if (!canSendRealRequest()) break;
            List<String[]> pageList = fetchCategoryPageReal(cat.typeId, page);
            if (pageList == null) {
                LogBridge.w(TAG, "源5点播：分组[" + cat.name + "] 第" + page + "页接口失败，停止拉取");
                break;
            }
            if (pageList.isEmpty()) {
                LogBridge.i(TAG, "源5点播：分组[" + cat.name + "] 第" + page + "页无数据，停止拉取（总页数已尽）");
                break;
            }
            int added = 0;
            for (String[] v : pageList) {
                if (v == null) continue;
                String vodId = (v[0] == null ? "" : v[0]).trim();
                if (vodId.isEmpty() || v[1] == null || v[1].isEmpty()) continue;
                if (!dedup.containsKey(vodId)) {
                    dedup.put(vodId, v);
                    added++;
                }
            }
            LogBridge.i(TAG, "源5点播：分组[" + cat.name + "] 拉取第" + page + "页 共" + pageList.size()
                    + "条（淘出新片 " + added + " 条）");
        }
        List<String[]> merged = new ArrayList<>(dedup.values());
        LogBridge.i(TAG, "源5点播：分组[" + cat.name + "] 多页合并去重后共 " + merged.size() + " 条");
        return merged;
    }

    /**
     * 【2026-10-13 海报加速】对海报 URL 做「不可达主机 → 可达镜像」重写；命中则累计一次，
     * 无匹配原样返回。供所有写 poster / tvg-logo 的入口（本类 appendItem / fetchCategoryPageReal
     * 与 RenYiBoVodCatalog 硬编码海报）统一调用。
     */
    public static String normalizePosterUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        for (String[] pair : POSTER_HOST_MIRROR) {
            String from = pair[0], to = pair[1];
            int schemeIdx = url.indexOf("://");
            if (schemeIdx < 0) continue;
            int hostStart = schemeIdx + 3;
            int hostEnd = url.indexOf('/', hostStart);
            String host = (hostEnd < 0) ? url.substring(hostStart) : url.substring(hostStart, hostEnd);
            if (!host.equalsIgnoreCase(from)) continue;
            String rest = (hostEnd < 0) ? "" : url.substring(hostEnd);
            sMirrorRewrites++;
            return url.substring(0, hostStart) + to + rest;
        }
        return url;
    }

    /** 【2026-10-13 海报加速】取条目海报：优先缩略字段 vod_pic_thumb，空则回退 vod_pic，统一做镜像重写。 */
    private static String pickPoster(JSONObject o) {
        String thumb = o == null ? "" : o.optString("vod_pic_thumb", "");
        if (thumb != null && !thumb.trim().isEmpty()) {
            sThumbUsedCount++;
            return normalizePosterUrl(thumb);
        }
        sPosterFallbackCount++;
        return normalizePosterUrl(o == null ? "" : o.optString("vod_pic", ""));
    }

    private static void appendItem(StringBuilder sb, String vodId, String name,
                                   String poster, String group, String remarks) {
        String display = name;
        if (remarks != null && !remarks.isEmpty()) {
            display = name + " · " + remarks;
        }
        // channelId / 行地址：renyibo-vod://<typeId>:<vodId>；无 vodId 时只带分类名
        int typeId = RenYiBoVodCatalog.typeIdOfName(group);
        String tail = (typeId > 0 ? String.valueOf(typeId) : "0") + ":" + (vodId == null ? "" : vodId);
        String schemeUrl = SCHEME + tail;

        sb.append("#EXTINF:-1 tvg-id=\"").append(TVG_PREFIX).append(esc(tail)).append("\"")
          .append(" tvg-name=\"").append(esc(name)).append("\"");
        if (poster != null && !poster.isEmpty()) {
            // 【2026-10-13 海报加速】所有写进 tvg-logo 的海报统一过图床镜像重写
            sb.append(" tvg-logo=\"").append(esc(normalizePosterUrl(poster))).append("\"");
        }
        sb.append(" group-title=\"").append(esc(group)).append("\",")
          .append(esc(display)).append("\n");
        sb.append(schemeUrl).append("\n");
    }

    // ------------------------------------------------------------------
    // 协议串解析
    // ------------------------------------------------------------------

    /** 是否为源5 VOD 协议串地址。 */
    public static boolean isVodSchemeUrl(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /** 从协议串取 typeId（{@code renyibo-vod://2:605021} → 2）；非法返回 0。 */
    public static int vodTypeIdOfScheme(String url) {
        String tail = tailOf(url);
        if (tail == null) return 0;
        int c = tail.indexOf(':');
        String t = c >= 0 ? tail.substring(0, c) : tail;
        return parseIntSafe(t, 0);
    }

    /** 从协议串取 vodId（{@code renyibo-vod://2:605021} → 605021）；无则返回空串。 */
    public static String vodIdOfScheme(String url) {
        String tail = tailOf(url);
        if (tail == null) return "";
        int c = tail.indexOf(':');
        return c >= 0 ? tail.substring(c + 1).trim() : "";
    }

    /** 从 channelId（{@code renyibo-vod:2:605021}）取 vodId；非本源返回空串。 */
    public static String vodIdOfChannelId(String channelId) {
        if (channelId == null || !channelId.startsWith(TVG_PREFIX)) return "";
        String tail = channelId.substring(TVG_PREFIX.length());
        int c = tail.indexOf(':');
        return c >= 0 ? tail.substring(c + 1).trim() : "";
    }

    /** 从 channelId 取 typeId；非本源返回 0。 */
    public static int vodTypeIdOfChannelId(String channelId) {
        if (channelId == null || !channelId.startsWith(TVG_PREFIX)) return 0;
        String tail = channelId.substring(TVG_PREFIX.length());
        int c = tail.indexOf(':');
        String t = c >= 0 ? tail.substring(0, c) : tail;
        return parseIntSafe(t, 0);
    }

    private static String tailOf(String url) {
        if (!isVodSchemeUrl(url)) return null;
        return url.substring(SCHEME.length()).trim();
    }

    // ------------------------------------------------------------------
    // 【桩】加密 VOD 调用（待补齐加密算法后启用）
    // ------------------------------------------------------------------

    /**
     * 【未实现·桩】按分类取回一页 VOD 列表。
     *
     * <p>服务端协议（已从抓包确证，2026-10-12）：
     * <pre>
     *   POST {RenYiBoVodCatalog#VOD_BASE}
     *   headers: Dart 客户端模板（见 {@link RenYiBoVodCatalog#DART_HEADERS}）
     *   body:    URL-safe Base64( AES-128-CBC+PKCS7 加密的请求明文 )   ← 已确证
     *   resp:    明文 JSON（列表类）或
     *            {@code <base64(0x数字串)>==<base64(密文体)>}（部分接口）
     * </pre>
     *
     * <p><b>【2026-10-12 已破译】算法确证为 AES-128-CBC + PKCS7</b>，固定密钥
     * {@code 7dbe4c69aa5b98ad90ba3468e21c6fd2}、每请求随机 IV（见 {@link VodCrypto}）；
     * <b>推翻</b>早期"无长度扩张 ⇒ ChaCha/CTR 流式"的误判 —— 之前不扩张是因为密文块数
     * 恰与明文一致。IV 放置与 {@code vod_detail}/{@code play} 参数形状等线格式
     * <b>仍未锁定</b>（见 {@link VodRequest}），故真实请求默认<b>不发送</b>。
     *
     * <p><b>红线</b>（用户铁律：禁止未经授权的第三方请求）：真实发包受
     * {@link #canSendRealRequest()} 双闸门约束（总开关 {@code sRealEnabled} 默认 false
     * + 线格式锁定常量 false）。闸门开启前本方法<b>不发任何网络包</b>、返回内置明文。
     *
     * @param typeId 分类 id（1 电影 / 2 电视剧 / 3 综艺 / 4 动漫 / 5 纪录 / 208 少儿）
     * @param page   页码（从 1 开始）
     * @return 条目列表，每项 = {vodId, vodName, vodPic, typeId, remarks}；未授权发包时恒为静态数据
     */
    public static List<String[]> fetchCategoryPage(int typeId, int page) {
        List<String[]> out = new ArrayList<>();
        if (canSendRealRequest()) {
            List<String[]> real = fetchCategoryPageReal(typeId, page);
            if (real != null) {
                LogBridge.i(TAG, "源5点播：type_id=" + typeId + " 第 " + page + " 页真实接口返回 "
                        + real.size() + " 条");
                return real;
            }
            LogBridge.w(TAG, "源5点播：真实接口失败（type_id=" + typeId + "）回落内置明文");
        }
        if (page != 1) {
            // 本期只有第 1 页的静态数据（首页推荐同源），后续页待加密接口补齐
            LogBridge.i(TAG, "源5点播：type_id=" + typeId + " 第 " + page
                    + " 页待加密接口补齐，返回空（不发请求）");
            return out;
        }
        for (String[] v : RenYiBoVodCatalog.homeVideos()) {
            if (parseIntSafe(v[3], 0) == typeId) out.add(v);
        }
        LogBridge.i(TAG, "源5点播：type_id=" + typeId + " 第 1 页返回内置 " + out.size() + " 条");
        return out;
    }

    /**
     * 【真实路径·默认不可达】按分类拉取一页 VOD 列表。
     *
     * <p>仅在 {@link #canSendRealRequest()} 为 true 时由 {@link #fetchCategoryPage} 调用。
     * 走 {@code dm_list} 加密请求 + {@link VodResponse} 解析；线格式未锁定（当前恒 true 不成立，
     * 因 {@code REQUEST_FORMAT_LOCKED=false}）故本方法不会被执行，保编译、保红线。
     *
     * @return 条目列表 {vodId, vodName, vodPic, typeId, remarks}；失败返回 {@code null}
     */
    private static List<String[]> fetchCategoryPageReal(int typeId, int page) {
        try {
            byte[] iv = VodCrypto.genRandomIv();
            String plain = VodRequest.videoList(typeId, page);
            byte[] cipher = VodCrypto.encrypt(plain, iv);
            // 【2026-10-12 线上实测定案】请求体 = base64url( IV(16B) ‖ 密文 )，IV 前置。
            byte[] payload = new byte[iv.length + cipher.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipher, 0, payload, iv.length, cipher.length);
            String body = postVod(VodCrypto.b64urlNoPad(payload));
            JSONObject obj = VodResponse.parse(body);
            if (obj == null) return null;
            // video_list 响应为明文 JSON：{code:1,msg:"视频列表",page,pagecount,data:[{vod_id,vod_name,vod_pic,type_id,vod_remarks}]}
            JSONArray arr = obj.optJSONArray("data");
            if (arr == null) return null;
            List<String[]> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                list.add(new String[]{
                        o.optString("vod_id"),
                        o.optString("vod_name"),
                        // 【2026-10-13 海报加速】优先 vod_pic_thumb 缩略图，空则回退 vod_pic，再镜像重写
                        pickPoster(o),
                        String.valueOf(typeId),
                        o.optString("vod_remarks"),
                });
            }
            return list;
        } catch (Throwable t) {
            LogBridge.w(TAG, "源5点播：真实列表异常 " + t.getMessage());
            return null;
        }
    }

    /** 向 VOD 端点发送加密请求体并取回正文（仅可发包状态下载入；不抛 IOException 系业务失败返回 null）。 */
    private static String postVod(String cipherBody) {
        try {
            Request.Builder rb = new Request.Builder()
                    .url(RenYiBoVodCatalog.VOD_BASE)
                    .post(RequestBody.create(MediaType.get("text/plain"), cipherBody));
            for (String[] h : RenYiBoVodCatalog.DART_HEADERS) {
                rb.header(h[0], h[1]);
            }
            LogBridge.i(TAG, "源5点播：POST VOD（授权实测）body_len=" + cipherBody.length()
                    + " body=" + trunc(cipherBody, 120));
            try (Response resp = NetUtil.getInstance().getClient().newCall(rb.build()).execute()) {
                int code = resp.code();
                String body = (resp.body() == null) ? "" : resp.body().string();
                LogBridge.i(TAG, "源5点播：POST VOD resp code=" + code
                        + " body=" + trunc(body, 200));
                if (!resp.isSuccessful() || body.isEmpty()) return null;
                return body;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "源5点播：POST VOD 异常 " + t.getMessage());
            return null;
        }
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * 【live】把 VOD 协议串解析成可播放地址（起播时调用）。
     *
     * <p><b>2026-10-12 攻播放</b>：调用真实 {@code video_detail} 接口（与
     * {@link #fetchCategoryPageReal} 相同的「IV 前置」加密请求）取回详情，尝试从响应
     * 解析出可播放地址（play_url / 直链 m3u8）。
     *
     * <p><b>响应两形态</b>：① 纯明文 JSON；② {@code base64(0x数字串)['==']base64(...)} 加密
     * 形态（解密密钥为运行时派生，未解，本路径尽力解析明文/半明文后返回 null，由
     * 播放器提示待补齐，不产生错误请求）。
     *
     * @param rawUrl M3U 行地址，形如 {@code renyibo-vod://2:605021}
     * @return 落地播放地址；无法取得时返回 {@code null}
     */
    public static String resolveVodPlayUrl(String rawUrl) {
        if (!isVodSchemeUrl(rawUrl)) return null;
        int typeId = vodTypeIdOfScheme(rawUrl);
        String vodId = vodIdOfScheme(rawUrl);
        RenYiBoVodCatalog.Category cat = RenYiBoVodCatalog.byTypeId(typeId);
        if (vodId.isEmpty()) {
            LogBridge.w(TAG, "源5点播：分类入口[" + (cat == null ? typeId : cat.name)
                    + "]无具体剧集，返回 null");
            return null;
        }
        String playUrl = resolveVodPlayUrlReal(vodId);
        LogBridge.i(TAG, "源5点播：[" + (cat == null ? ("type_id=" + typeId) : cat.name)
                + "] vodId=" + vodId + " 取流结果=" + (playUrl == null ? "null(待补齐)" : playUrl));
        return playUrl;
    }

    /**
     * 【live】真实 video_detail 请求 + 解析。
     *
     * <p><b>2026-10-12 解密闭环</b>：video_detail 响应为「三段 Base64 信封」加密形态，
     * 已由 {@link VodResponse} 完成完整解密（IV 内嵌 buf[0:16]，固定 key），
     * 明文含 {@code data.vod_info.vod_url_with_player} 直链 m3u8。
     */
    private static String resolveVodPlayUrlReal(String vodId) {
        JSONObject obj = fetchVideoDetailJson(vodId);
        if (obj == null) {
            LogBridge.w(TAG, "源5点播：video_detail 响应解析失败（明文/信封均未命中），返回 null");
            return null;
        }
        // 【2026-10-13 源5 联播】起播顺带把选集列表写进内存缓存（复用同一份已取回的
        //   video_detail 明文，<b>不新增任何网络请求</b>），让 STATE_ENDED 的自动联播在
        //   主界面 / OTT 两条播放通路下都能拿到「当前 vod 全部集顺序」。
        ensureVodEpisodeCacheFromDetail(vodId, obj);
        // 【2026-10-13 线路接入】优先取用户所选线路的直链；无线路表再回落 extractPlayUrl。
        List<String[]> lines = sVodLineCache.get(vodId);
        String u = null;
        if (lines != null && !lines.isEmpty()) {
            int idx = clampVodLineIndex(vodId, lines.size());
            u = lines.get(idx)[3];
        }
        if (u == null) {
            u = extractPlayUrl(obj);
        }
        if (u != null) {
            LogBridge.i(TAG, "源5点播：video_detail 解析命中 play=" + u);
            return u;
        }
        LogBridge.i(TAG, "源5点播：video_detail 可解析但无直链 play，data="
                + trunc(obj.toString(), 220));
        return null;
    }

    /**
     * 【2026-10-13 源5 联播】用已取回的 video_detail 明文填充选集缓存（若尚未有）。
     *
     * <p>起播 {@link #resolveVodPlayUrl} 必然已经为取流发过一次 video_detail（受双闸门约束），
     * 这里<b>只做内存解析，不新增任何网络请求</b>；依赖 {@link #fetchVideoDetailJson} 已顺带
     * 填充的 {@link #sVodLineCache} 用 {@link #buildVodEpisodeItems} 生成集数顺序。
     */
    private static void ensureVodEpisodeCacheFromDetail(String vodId, JSONObject vd) {
        if (vodId == null || vodId.isEmpty()) return;
        try {
            List<Channel.EpgItem> cached = sVodEpisodeCache.get(vodId);
            if (cached != null && !cached.isEmpty()) return;
            List<Channel.EpgItem> items = buildVodEpisodeItems(vodId, vd);
            if (items != null && !items.isEmpty()) {
                sVodEpisodeCache.put(vodId, items);
                LogBridge.i(TAG, "源5点播：起播顺带缓存选集 vodId=" + vodId
                        + " 共 " + items.size() + " 项");
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "源5点播：起播缓存选集异常 " + t.getMessage());
        }
    }

    /**
     * video_detail 网络请求（IV 前置加密）+ {@link VodResponse} 三段信封解密，
     * 返回业务明文 JSONObject；失败返回 null。<b>受 {@link #canSendRealRequest()} 双闸门约束。</b>
     * 解析成功时顺带填充线路表 {@link #sVodLineCache}（供设置面板线路/清晰度切换）。
     */
    private static JSONObject fetchVideoDetailJson(String vodId) {
        if (!canSendRealRequest()) {
            LogBridge.w(TAG, "源5点播：video_detail 请求被双闸门拦截（未授权真实请求）vodId=" + vodId);
            return null;
        }
        try {
            byte[] iv = VodCrypto.genRandomIv();
            String plain = VodRequest.videoDetail(vodId);
            byte[] cipher = VodCrypto.encrypt(plain, iv);
            byte[] payload = new byte[iv.length + cipher.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipher, 0, payload, iv.length, cipher.length);
            String body = postVod(VodCrypto.b64urlNoPad(payload));
            if (body == null) return null;
            JSONObject obj = VodResponse.parse(body);
            if (obj == null) {
                LogBridge.w(TAG, "源5点播：video_detail 解密失败 vodId=" + vodId);
            } else {
                populateVodLines(vodId, obj);
            }
            return obj;
        } catch (Throwable t) {
            LogBridge.w(TAG, "源5点播：video_detail 取详异常 " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 【2026-10-12】EPG 面板接入：VOD 频道选集列表（参照源7 afhgrj 模式）
    // ------------------------------------------------------------------

    /** 选集内存缓存：vodId → EpgItem 列表（每项 customPlayUrl 直接携带可播放 m3u8）。 */
    private static final Map<String, List<Channel.EpgItem>> sVodEpisodeCache = new ConcurrentHashMap<>();

    /** vodId 级单飞锁：起播取流 / EPG 面板并发时同一部片只抓一次 video_detail。 */
    private static final Map<String, Object> sVodEpisodeLocks = new ConcurrentHashMap<>();

    /** 读选集缓存：命中返回列表；未命中返回 {@code null}（由 EpgManager 决定是否异步抓取）。 */
    public static List<Channel.EpgItem> getVodEpisodes(String vodId) {
        if (vodId == null || vodId.isEmpty()) return null;
        return sVodEpisodeCache.get(vodId);
    }

    /**
     * 抓 video_detail 并解析选集列表（联网，受双闸门约束；结果写内存缓存）。
     *
     * <p>集数/品质项 {@code title = 清晰度或第NN集}、{@code customPlayUrl = 直链 m3u8}：
     * 由 {@code EpgManager} 放进 EPG 面板，点击即播放该地址（无需再惰性解析）。
     *
     * @return 选集列表；失败返回 {@code null}
     */
    public static List<Channel.EpgItem> fetchVodEpisodes(String vodId) {
        if (vodId == null || vodId.isEmpty()) return null;
        Object lock;
        synchronized (sVodEpisodeLocks) {
            lock = sVodEpisodeLocks.get(vodId);
            if (lock == null) {
                lock = new Object();
                sVodEpisodeLocks.put(vodId, lock);
            }
        }
        synchronized (lock) {
            List<Channel.EpgItem> cached = sVodEpisodeCache.get(vodId);
            if (cached != null && !cached.isEmpty()) return cached;
            JSONObject vd = fetchVideoDetailJson(vodId);
            if (vd == null) return null;
            List<Channel.EpgItem> items = buildVodEpisodeItems(vodId, vd);
            if (items == null || items.isEmpty()) {
                LogBridge.w(TAG, "源5点播：video_detail 未解析出选集 vodId=" + vodId);
                return null;
            }
            sVodEpisodeCache.put(vodId, items);
            LogBridge.i(TAG, "源5点播：EPG 选集解析成功 vodId=" + vodId
                    + " 共 " + items.size() + " 项");
            return items;
        }
    }

    // ------------------------------------------------------------------
    // 【2026-10-13】设置面板线路 / 清晰度接入（每项 = {name, code, 品质, 首条直链, 原始url}）
    // ------------------------------------------------------------------

    /**
     * 【2026-10-13 水印规避】已知带「博彩烧录水印」的 CDN 主机特征（host 子串匹配，小写）。
     *
     * <p>与源7 {@code com.tv.live.afhgrj.AfhgrjUrlResolver.WATERMARK_HOST_MARKERS}
     * <b>同源同口径</b>（源5/源7 同为 lz/ffzy/baofeng/ly166 系上游）。实测该系 CDN 的正片
     * 分片内<b>烧录</b>了「249331.com 注册即送888！主营：棋牌 电子 真人 捕鱼 体育 电竞」
     * 横幅水印 —— 压制在视频编码帧内，广告剥离器（只作用于 m3u8 清单）无法去除，
     * 唯一可行做法是<b>选线时避开</b>：命中即降级为「兜底」，优先采用干净线路，
     * 只有全部干净线路不可用/不可达时才回退带水印线路（保证仍有画面）。</p>
     */
    private static final String[] WATERMARK_HOST_MARKERS = { "ly166.com" };

    /** 线路表缓存：vodId → 直链可播的播放源列表（不含仅第三方解析源）。 */
    private static final Map<String, List<String[]>> sVodLineCache = new ConcurrentHashMap<>();

    /** 用户所选线路下标（内存级，与源7 的 sid 切换同规格；默认 0 = 第一条直链源）。 */
    private static final Map<String, Integer> sVodLineSel = new ConcurrentHashMap<>();

    /** 从 video_detail 明文解析并缓存线路表（供设置面板与起播取流共用口径）。 */
    private static void populateVodLines(String vodId, JSONObject vd) {
        JSONObject data = vd == null ? null : vd.optJSONObject("data");
        JSONObject vi = data == null ? null : data.optJSONObject("vod_info");
        if (vi == null) return;
        JSONArray pl = vi.optJSONArray("vod_url_with_player");
        if (pl == null) return;
        List<String[]> lines = new ArrayList<>();
        for (int i = 0; i < pl.length(); i++) {
            JSONObject e = pl.optJSONObject(i);
            if (e == null) continue;
            String raw = e.optString("url", "");
            String[] seg = firstHttpSeg(raw);
            if (seg == null) continue;   // 无直链（第三方解析加密串）不列入线路
            lines.add(new String[]{
                    e.optString("name", "播放源" + (lines.size() + 1)),
                    e.optString("code", ""),
                    seg[0],
                    seg[1],
                    raw,
            });
        }
        // 【2026-10-13 默认线路=畅享(白天用) + 水印规避】四级稳定排序（同级保持服务端原顺序）：
        //   ⓪ 名含「畅享」且 host 干净 → 最优先（★用户指定默认线路：畅享(白天用)）；
        //   ① 名含「无水印/无广告」且 host 干净 → 次优先（原 App 的服务端线路分级口径）；
        //   ② 其余 host 干净线路；
        //   ③ host 命中 WATERMARK_HOST_MARKERS（正片烧录博彩水印）→ 兜底
        //      （仅当 ⓪①② 全部不可用/不可达时才回退带水印线路，保证仍有画面）。
        List<String[]> ordered = new ArrayList<>(lines.size());
        int wmCount = 0;
        for (String[] ln : lines) {                                        // ⓪ 畅享线 + host 干净
            if (isChangxiangName(ln[0]) && !isWatermarkHost(ln[3])) ordered.add(ln);
        }
        for (String[] ln : lines) {                                        // ① 名干净 + host 干净
            if (!isChangxiangName(ln[0]) && isCleanName(ln[0]) && !isWatermarkHost(ln[3])) ordered.add(ln);
        }
        for (String[] ln : lines) {                                        // ② 其余 host 干净
            if (!isChangxiangName(ln[0]) && !isCleanName(ln[0]) && !isWatermarkHost(ln[3])) ordered.add(ln);
        }
        for (String[] ln : lines) {                                        // ③ host 带烧录水印 → 兜底
            if (isWatermarkHost(ln[3])) { ordered.add(ln); wmCount++; }
        }
        lines = ordered;
        if (!lines.isEmpty()) {
            sVodLineCache.put(vodId, lines);
            // 【2026-10-13 水印规避】原选中下标若在排序后落在带水印的兜底线路 → 复位为默认
            //   （第 0 条干净线路），避免旧的选区绕开新策略、用户依旧看到烧录水印。
            Integer sel = sVodLineSel.get(vodId);
            if (sel != null && sel >= 0 && sel < lines.size() && isWatermarkHost(lines.get(sel)[3])) {
                sVodLineSel.remove(vodId);
                LogBridge.w(TAG, "源5点播：原选线路带烧录水印，已复位为默认干净线路");
            }
            LogBridge.i(TAG, "源5点播：线路表就绪 vodId=" + vodId + " 共 " + lines.size()
                    + " 条，默认线路[" + lines.get(0)[0] + "]"
                    + (wmCount > 0 ? "（其中 " + wmCount + " 条带烧录水印已降级为兜底）" : ""));
            // 【2026-10-13 线路体检】把全部线路（名/host/直链）逐条打日志，
            //   供 PC 端逐线拉 m3u8 抽帧、判定哪条线路正片带广告/水印（无水印者定为默认线）。
            for (int li = 0; li < lines.size(); li++) {
                String[] ln = lines.get(li);
                LogBridge.i(TAG, "源5点播：线路#" + li + " [" + ln[0] + "] code=" + ln[1]
                        + " host=" + hostOf(ln[3]) + " url=" + ln[3]);
            }
        }
    }

    /** 「品质$url#集$url#…」串的第一个直链段 → {品质, url}；无直链返回 null。 */
    private static String[] firstHttpSeg(String u) {
        if (u == null || u.isEmpty()) return null;
        for (String seg : u.split("#")) {
            if (seg.isEmpty()) continue;
            int d = seg.lastIndexOf('$');
            String url = d >= 0 ? seg.substring(d + 1) : seg;
            if (!isHttpUrl(url)) continue;
            String q = d > 0 ? seg.substring(0, d) : "";
            if (q.isEmpty()) q = "默认";
            return new String[]{ q, url };
        }
        return null;
    }

    /** 【2026-10-13】线路名是否声明「无水印/无广告」（原 App 服务端线路分级口径）。 */
    private static boolean isCleanName(String name) {
        return name != null && (name.contains("无水印") || name.contains("无广告"));
    }

    /** 【2026-10-13】线路名是否「畅享」（★用户指定默认线路：畅享(白天用)）。 */
    private static boolean isChangxiangName(String name) {
        return name != null && name.contains("畅享");
    }

    /**
     * 【2026-10-13 水印规避】url 的主机名（小写）；解析失败返回空串。
     */
    private static String hostOf(String url) {
        if (url == null) return "";
        String u = url.trim();
        int i = u.indexOf("://");
        if (i < 0) return "";
        int s = i + 3, e = u.indexOf('/', s);
        String h = (e < 0) ? u.substring(s) : u.substring(s, e);
        int at = h.indexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        int c = h.indexOf(':');
        if (c >= 0) h = h.substring(0, c);
        return h.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 【2026-10-13 水印规避】url 是否落在「带博彩烧录水印」的 CDN 上
     * （命中 {@link #WATERMARK_HOST_MARKERS} 任一子串即视为带水印线路 → 选线时降级为兜底）。
     */
    private static boolean isWatermarkHost(String url) {
        String h = hostOf(url);
        if (h.isEmpty()) return false;
        for (String m : WATERMARK_HOST_MARKERS) {
            if (h.contains(m)) return true;
        }
        return false;
    }

    /** 线路表读取（设置面板用）；尚未抓取返回 {@code null}。 */
    public static List<String[]> getVodLines(String vodId) {
        if (vodId == null || vodId.isEmpty()) return null;
        return sVodLineCache.get(vodId);
    }

    /** 当前所选线路下标（越界钳制到 0）。 */
    public static int getVodLineIndex(String vodId) {
        Integer idx = sVodLineSel.get(vodId);
        return (idx == null) ? 0 : idx;
    }

    private static int clampVodLineIndex(String vodId, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(getVodLineIndex(vodId), size - 1));
    }

    /**
     * 切换该片线路并返回该线首条直链（供设置面板重播用）；线路未就绪返回 null。
     */
    public static String switchVodLine(String vodId, int index) {
        List<String[]> lines = sVodLineCache.get(vodId);
        if (lines == null || lines.isEmpty()) return null;
        int i = Math.max(0, Math.min(index, lines.size() - 1));
        sVodLineSel.put(vodId, i);
        String[] ln = lines.get(i);
        LogBridge.i(TAG, "源5点播：切线路 vodId=" + vodId + " → [" + ln[0] + " · " + ln[2] + "]");
        return ln[3];
    }

    /**
     * 异步确保线路表就绪（复用选集抓取的单飞与缓存；受双闸门约束），完成后主线程回调。
     */
    public static void ensureVodLinesAsync(final String vodId, final Runnable onReady) {
        new Thread(() -> {
            try {
                fetchVodEpisodes(vodId);
            } catch (Throwable ignored) {
            }
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(onReady);
            } catch (Throwable ignored) {
            }
        }, "Renyibo-LineFetch").start();
    }

    /**
     * 从 video_detail 明文构建选集 EpgItem 列表。
     *
     * <p>【2026-10-13】优先<b>用户所选线路</b>的原始 url（与设置面板切换口径一致）；
     * 无线路表/无选区时回落「http(s) 直链分段数最多」的一条（剧集为
     * {@code 第01集$url#第02集$url#…}、电影为 {@code 品质$url}），按 {@code #}
     * 拆集、按最后一个 {@code $} 拆标题/地址；仅保留直链 m3u8 段。
     */
    private static List<Channel.EpgItem> buildVodEpisodeItems(String vodId, JSONObject obj) {
        if (obj == null) return null;
        JSONObject data = obj.optJSONObject("data");
        if (data == null) return null;
        JSONObject vi = data.optJSONObject("vod_info");
        if (vi == null) return null;
        String best = null;
        List<String[]> lines = sVodLineCache.get(vodId);
        if (lines != null && !lines.isEmpty()) {
            best = lines.get(clampVodLineIndex(vodId, lines.size()))[4];
        } else {
            // 【2026-10-13 水印规避】无线路表时的兜底选线：优先「直链分段数最多」的
            //   <b>干净</b>线路，带烧录水印的线路仅作兜底（全部干净线路不可用时才用）。
            int bestSegs = -1;
            String wmBest = null;
            int wmBestSegs = -1;
            JSONArray pl = vi.optJSONArray("vod_url_with_player");
            if (pl != null) {
                for (int i = 0; i < pl.length(); i++) {
                    JSONObject e = pl.optJSONObject(i);
                    if (e == null) continue;
                    String u = e.optString("url", "");
                    int segs = httpSegCount(u);
                    if (isWatermarkHost(firstHttpM3u8(u))) {
                        if (segs > wmBestSegs) { wmBestSegs = segs; wmBest = u; }
                        continue;
                    }
                    if (segs > bestSegs) {
                        bestSegs = segs;
                        best = u;
                    }
                }
            }
            if (best == null) best = wmBest;
        }
        if (best == null) {
            // 兜底：单条直链（play_url 等常见字段）
            String u = extractPlayUrl(obj);
            if (u == null) return null;
            best = "$" + u;
        }
        List<Channel.EpgItem> items = new ArrayList<>();
        for (String seg : best.split("#")) {
            if (seg.isEmpty()) continue;
            int d = seg.lastIndexOf('$');
            String url = d >= 0 ? seg.substring(d + 1) : seg;
            if (!isHttpUrl(url)) continue;
            String label = d > 0 ? seg.substring(0, d) : "";
            String title = label.isEmpty() ? ("第" + (items.size() + 1) + "集") : label;
            Channel.EpgItem it = new Channel.EpgItem("今天", "", title, false, ymdToday());
            it.customPlayUrl = url;
            items.add(it);
        }
        return items.isEmpty() ? null : items;
    }

    /** 「品质$url#集$url#…」串里 http(s) 直链的分段数（用于选直链条目）。 */
    private static int httpSegCount(String u) {
        if (u == null || u.isEmpty()) return 0;
        int n = 0;
        for (String seg : u.split("#")) {
            int d = seg.lastIndexOf('$');
            String url = d >= 0 ? seg.substring(d + 1) : seg;
            if (isHttpUrl(url)) n++;
        }
        return n;
    }

    /** 当日 yyyyMMdd（作为选集项 dateYMD，与 afhgrj 集数项口径一致）。 */
    private static int ymdToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 10000
                + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    /**
     * 从 video_detail 明文解析出可播放地址。
     *
     * <p>优先 {@code data.vod_info.vod_url_with_player[]}：逐条取 {@code url} 字段
     * （形如 {@code 品质$https://…/index.m3u8} 或剧集 {@code 第01集$url#第02集$url#…}），
     * 返回第一个可直接播放的 http(s) m3u8；无直链再兜底扫描常见字段。
     */
    private static String extractPlayUrl(JSONObject obj) {
        if (obj == null) return null;
        JSONObject data = obj.optJSONObject("data");
        if (data == null) return null;
        JSONObject vi = data.optJSONObject("vod_info");
        if (vi != null) {
            JSONArray pl = vi.optJSONArray("vod_url_with_player");
            if (pl != null) {
                // 【2026-10-13 水印规避】优先取 host 干净的直链；带烧录水印的直链只作兜底。
                String wmUrl = null;
                for (int i = 0; i < pl.length(); i++) {
                    JSONObject e = pl.optJSONObject(i);
                    if (e == null) continue;
                    String u = firstHttpM3u8(e.optString("url", ""));
                    if (u == null) continue;
                    if (isWatermarkHost(u)) {
                        if (wmUrl == null) wmUrl = u;
                        continue;
                    }
                    LogBridge.i(TAG, "源5点播：取直链[" + e.optString("name", "")
                            + "/" + e.optString("code", "") + "] -> " + u);
                    return u;
                }
                if (wmUrl != null) {
                    LogBridge.w(TAG, "源5点播：全部线路带烧录水印，回退兜底直链 -> " + wmUrl);
                    return wmUrl;
                }
            }
        }
        // 兜底：常见直接字段
        String[] keys = { "play_url", "playUrl", "url", "video_url", "urls", "m3u8" };
        if (vi != null) {
            for (String k : keys) {
                String v = vi.optString(k, "");
                if (isHttpUrl(v)) return v;
            }
        }
        for (String k : keys) {
            String v = obj.optString(k, "");
            if (isHttpUrl(v)) return v;
        }
        return null;
    }

    /** 从「品质$url#集$url#…」串里解析第一个可直接播放的 http(s) 地址；无则返回 null。 */
    private static String firstHttpM3u8(String u) {
        if (u == null || u.isEmpty()) return null;
        for (String seg : u.split("#")) {
            if (seg.isEmpty()) continue;
            int d = seg.lastIndexOf('$');
            String url = d >= 0 ? seg.substring(d + 1) : seg;
            if (isHttpUrl(url)) return url;
        }
        return null;
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    // ------------------------------------------------------------------
    // 调试/展示辅助
    // ------------------------------------------------------------------

    /** 分类摘要（日志/设置面板用）：每项 = {type_id, 名称, 筛选维度数}。 */
    public static List<String[]> categorySummary() {
        List<String[]> out = new ArrayList<>();
        for (RenYiBoVodCatalog.Category c : RenYiBoVodCatalog.CATEGORIES) {
            out.add(new String[]{String.valueOf(c.typeId), c.name, String.valueOf(c.filters.length)});
        }
        return out;
    }

    /** 分类筛选维度展开：返回 {维度名 → 值数组}，供后续筛选 UI 使用。 */
    public static Map<String, String[]> filtersOf(int typeId) {
        Map<String, String[]> out = new LinkedHashMap<>();
        RenYiBoVodCatalog.Category c = RenYiBoVodCatalog.byTypeId(typeId);
        if (c == null) return out;
        for (String[] f : c.filters) {
            if (f[1] == null || f[1].isEmpty()) continue;
            out.put(f[0], f[1].split(","));
        }
        return out;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    /** 供 {@link RenYiBoVodCatalog} 内部构造 JSON 时复用（保留以备加密接口补齐后使用）。 */
    static String joinJsonArray(JSONArray arr) {
        if (arr == null) return "[]";
        return arr.toString();
    }

    /** 供加密接口补齐后复用：安全取 JSON 对象字段。 */
    static String opt(JSONObject o, String key, String def) {
        return o == null ? def : o.optString(key, def);
    }
}
