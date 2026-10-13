package com.tv.live.renyibo;

import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import okhttp3.Response;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 内置源5：爱其意(任意播)直播源加载器。
 *
 * <p>机制（逆向自爱其意 App 运行时抓包）：
 * <pre>
 *   GET http://110.42.52.180:6688/live/live.php?token=<TOKEN>&type=json&id=<频道ID>
 *     → {"code":200,"msg":"获取成功","name":"CCTV1综合","url":"&lt;实时生成的m3u8直链&gt;","epg":"..."}
 * </pre>
 * 频道 ID 固定 97 个（zb-cctv1..zb-cctv8k / zb-*ws / ...），分组由 ID 前缀推导。
 * 逐个频道取回 url 后拼成 M3U，交给 PlaylistParser.parseContent 复用既有解析链路。
 *
 * <p><b>【2026-10-07 惰性解析】</b>参照源6（咪咕）模式：{@link #loadAllLiveAsM3u} 只发
 * live.php 取链请求（列表必须的 97 次），M3U 里存<b>跳转地址原样</b>（110.42.52.180 的
 * php 302 跳转器），不再为 97 个频道各做一次全量 CDN GET 预解析 —— 实测该项把源5
 * 加载拖到 30.5s（换台/启动全程黑等）。真实落地地址延迟到「起播这一刻」解析：
 * {@code TVPlayerManager.doPlay} 识别待解析地址（{@link #isPendingResolveUrl}）→
 * {@link #resolveUrl}（单频道 1 次 GET，带 5 分钟缓存）→ 播落地地址。
 *
 * <p><b>⚠️ token 说明</b>：token 由服务端下发并会轮换（曾为 76AYudAHan，现为 0oujsAM7），
 * 失效时接口返回 {@code msg="token异常请检查"}。重新获取方法：
 * 在装有爱其意的设备（雷电模拟器）上对 host {@code 110.42.52.180} 抓包
 * （设备自带 tcpdump，{@code tcpdump -i any -s0 -w /data/local/tmp/cap.pcap}），
 * 过滤 {@code GET /live/live.php?token=xxx} 取出新 token 替换下面常量即可。
 *
 * <p><b>【2026-10-12 影视点播移植】</b>本类只负责直播部分（5 组）。
 * 影视点播（电影/电视剧/综艺/动漫/纪录/少儿 6 分类 + 首页推荐）由
 * {@link RenYiBoVodSourceLoader} 提供，并在 {@link #loadAllLiveAsM3u} 末尾
 * <b>追加</b>到同一份 M3U —— 源5 由此组织为「直播 5 组 + 点播 7 组」两大类。
 * 点播部分当前为「明文+骨架落地」，不发起任何 VOD 网络请求。
 */
public final class RenYiBoSourceLoader {

    private static final String TAG = "RenYiBoSourceLoader";

    /**
     * 频道 channelId 前缀：M3U 里写成 {@code tvg-id="renyibo:zb-cctv1"}，
     * 解析后 {@link com.tv.live.Channel#getChannelId()} 即为 {@code renyibo:<API id>}。
     * 播放失败时据此识别「本频道属于源5」并实时重拉最新直链（见 TVPlayerManager#tryRefreshRenyiboSource）。
     */
    public static final String TVG_PREFIX = "renyibo:";

    /**
     * 【2026-10-07 惰性解析】M3U 行地址协议串前缀：{@code renyibo://<apiId>}。
     * 参照咪咕 {@code nowtv://<tvId>} 模式 —— 列表存永不过期的协议串，
     * 起播时才实时解析（fresh live.php → 302 → 真 CDN 落地地址）。
     * ⚠️ 与订阅源地址 {@code renyibo://all} 同前缀，但 {@code all} 不会作为频道地址进入播放链。
     */
    public static final String SCHEME = "renyibo://";

    /** 任意播直播源 API token（随服务端轮换，失效时需重新抓包获取） */
    private static final String TOKEN = "0oujsAM7";
    private static final String BASE =
            "http://110.42.52.180:6688/live/live.php?token=" + TOKEN + "&type=json&id=";

    private RenYiBoSourceLoader() {}

    /** 97 个频道 ID（数组顺序即展示顺序） */
    private static final String[] IDS = {
            // ── 央视 (20) ──
            "zb-cctv1", "zb-cctv2", "zb-cctv3", "zb-cctv4", "zb-cctv5", "zb-cctv5+",
            "zb-cctv6", "zb-cctv7", "zb-cctv8", "zb-cctv9", "zb-cctv10", "zb-cctv11",
            "zb-cctv12", "zb-cctv13", "zb-cctv14", "zb-cctv15", "zb-cctv16", "zb-cctv17",
            "zb-cctv4k", "zb-cctv8k",
            // ── 省级卫视 (25) ──
            "zb-zjws", "zb-jsws", "zb-dfws", "zb-gdws", "zb-sdws", "zb-henws", "zb-scws",
            "zb-cqws", "zb-sxws", "zb-lnws", "zb-jlws", "zb-hljws", "zb-gzws", "zb-gxws",
            "zb-gsws", "zb-qhws", "zb-xzws", "zb-xjws", "zb-nmgws", "zb-szws", "zb-tjws",
            "zb-ynws", "zb-hebws", "zb-jxws", "zb-ahws",
            // ── 教育 (3) ──
            "zb-cetv1", "zb-cetv2", "zb-cetv4",
            // ★【2026-10-13 分组合并】「地方」分组已整体删除（22 个频道随分组移除），
            // 卫视/教育/特色 频道并入央视（见 groupOf）。
            // ── 特色 (27) ──
            "zb-cgtn", "zb-jykt", "zb-hnly", "zb-hnds", "zb-hndy",
            "zb-zjxw", "zb-zjse", "zb-jsxw", "zb-zjgj", "zb-jsgj",
            "zb-cqxw", "zb-hnxw", "zb-hndsj", "zb-fjxw", "zb-fjly", "zb-fjse", "zb-fjdsj",
            "zb-shdy", "zb-jsxx", "zb-jszy",
            "zb-xsj", "zb-shss", "zb-yxfy", "zb-fztd", "zb-hjjd", "zb-wspd", "zb-wssj",
    };

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 【2026-10-07 性能修复】M3U 列表缓存：TTL 内重复切换到源5直接复用，零网络请求。
     * TTL 只需覆盖「用户来回切源」的短窗口；过期待解析 php 地址由起播时的
     * {@link #resolveUrl} 重新解析，失效兜底由 {@code tryRefreshRenyiboSource} 负责。
     */
    private static volatile String sCachedM3u = null;
    private static volatile long sCachedM3uAtMs = 0L;
    private static final long M3U_CACHE_TTL_MS = 3L * 60L * 1000L;

    /** 拉取全部频道并拼成 M3U 文本（供 PlaylistParser.parseContent 复用） */
    public static String loadAllLiveAsM3u() {
        // 【2026-10-07】短 TTL 缓存：来回切源不再重新发 97 个请求
        String cached = sCachedM3u;
        if (cached != null && System.currentTimeMillis() - sCachedM3uAtMs < M3U_CACHE_TTL_MS) {
            LogBridge.i(TAG, "任意播(爱其意)源5：命中列表缓存（" + (System.currentTimeMillis() - sCachedM3uAtMs) + "ms 前生成），零请求复用");
            return cached;
        }

        List<String[]> items = new ArrayList<>();   // 每项 = {id, name, group, url(服务端原始)}
        int threads = Math.min(12, IDS.length);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String[]>> futures = new ArrayList<>(IDS.length);
            for (final String id : IDS) {
                futures.add(pool.submit(() -> fetchOne(id, false)));
            }
            for (Future<String[]> f : futures) {
                try {
                    String[] r = f.get();
                    if (r != null) items.add(r);
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdown();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        HashSet<String> seen = new HashSet<>();
        int written = 0, dup = 0;
        for (String[] it : items) {
            // 【2026-10-13 分组合并】groupOf 返回 null = 已删除分组（地方），防御性跳过
            if (it[2] == null) continue;
            // ★【同名同流去重】接口对 zb-cctv5+ 会返回与 zb-cctv5 完全相同的 name+url
            //   （该 ID 在服务端无数据，带 "+" 被当空格→回退到 zb-cctv5）。
            //   若不去重，列表里会出现两条「CCTV5体育」且地址相同：切台序号错位、观感重复。
            //   判重用服务端原始 url（cctv5+ 与 cctv5 返回同一地址），行地址写协议串。
            if (!seen.add(it[1] + "|" + it[3])) {
                dup++;
                LogBridge.w(TAG, "跳过同名同流重复项：" + it[0] + " (" + it[1] + ")");
                continue;
            }
            sb.append("#EXTINF:-1 tvg-id=\"").append(TVG_PREFIX).append(esc(it[0]))
              .append("\" tvg-name=\"").append(esc(it[1]))
              // 【2026-10-13 台标】给源5 直播频道补 tvg-logo（对齐内置源1 的 logo.catvod.com 图床）。
              // live.php 域名本身不下发 logo 字段，故按频道名推导 logo.catvod.com 的台标文件名。
              .append("\" tvg-logo=\"").append(esc(logoTvgUrl(it[1]))).append("\"")
              .append(" group-title=\"").append(esc(it[2]))
              .append("\",").append(esc(it[1])).append("\n");
            // ★【2026-10-07 惰性解析·协议串】行地址 = renyibo://<apiId>（参照咪咕 nowtv://<tvId>）。
            //   live.php 下发的 php 跳转地址里 ZY- token 存活期极短（实测 2 分钟即失效），
            //   写进 M3U 必然在起播时过期；协议串永不过期，起播时经
            //   {@link #resolveByApiId} 走 fresh live.php + 302（实测 ~0.2s）。
            sb.append(SCHEME).append(it[0]).append("\n");
            written++;
        }
        LogBridge.i(TAG, "任意播(爱其意)源5：取回 " + written + " / " + IDS.length + " 个频道"
                + (dup > 0 ? "（同名同流去重 " + dup + " 条）" : ""));

        // 【2026-10-13 分组合并】卫视/教育/特色 已并入央视；「地方」分组及频道已删除（groupOf 返回 null 的防御性兜底）
        int mergedCctv = 0;
        for (String[] it : items) {
            if ("央视".equals(it[2])) mergedCctv++;
        }
        LogBridge.i(TAG, "源5：卫视/教育/特色 已合并入央视分组，共 " + mergedCctv
                + " 个频道（地方分组已删除）");

        // ── 【2026-10-12 VOD 移植】直播之后追加影视点播分组 ──
        //   源5 组织为「两大类」：直播 1 组（央视，原央视/卫视/教育/特色合并）+
        //   点播 8 组（首页推荐 + 电影/电视剧/综艺/动漫/纪录/少儿/短剧）。
        //   点播部分见 RenYiBoVodSourceLoader：本期为「明文+骨架落地」，
        //   不发任何网络请求，条目来自抓包明文；加密调用留桩待补齐。
        //   ⚠️ 直播部分逻辑保持完全不变（12 线程并发仅用于既有 live.php 取链，
        //   不新增任何对 naiqiyi.php 的请求）。
        try {
            String vod = RenYiBoVodSourceLoader.buildVodM3uFragment();
            if (vod != null && !vod.isEmpty()) {
                sb.append(vod);
                LogBridge.i(TAG, "任意播(爱其意)源5：已追加影视点播分组（"
                        + RenYiBoVodSourceLoader.categorySummary().size() + " 个分类 + 首页推荐）");
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "源5点播分组生成失败（不影响直播）：" + t.getMessage());
        }

        if (written > 0) {
            sCachedM3u = sb.toString();
            sCachedM3uAtMs = System.currentTimeMillis();
        }
        return sb.toString();
    }

    /**
     * 实时重拉单个频道的最新播放直链（API 每次都返回带新签名的 m3u8）。
     *
     * @param apiId 频道 API id（不带 {@link #TVG_PREFIX} 前缀），如 {@code zb-cctv1}
     * @return 最新直链（已解析 302 落地地址）；失败返回 {@code null}
     */
    public static String fetchUrlById(String apiId) {
        if (apiId == null || apiId.isEmpty()) return null;
        String[] r = fetchOne(apiId, true);
        return r == null ? null : r[3];
    }

    // ------------------------------------------------------------------
    // 【2026-10-07 惰性解析】参照咪咕模式：列表存待解析地址，起播时才解析 302
    // ------------------------------------------------------------------

    /**
     * 是否为「待解析」的源5跳转地址（110.42.52.180 的 php 302 跳转器，
     * 命中它说明还没解析到真实 CDN 地址）。真实 CDN 地址（各台域名各异）不命中。
     */
    public static boolean isPendingResolveUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (!lower.contains("110.42.52.180")) return false;
        int q = lower.indexOf('?');
        String path = q > 0 ? lower.substring(0, q) : lower;
        return path.endsWith(".php");
    }

    /**
     * 把跳转地址解析成真实可播的 CDN 落地地址（302 跟随）。
     *
     * <p>带 5 分钟进程内缓存：同一频道快速重播 / 换台回来不再重复发 GET；
     * 失效（auth 过期播放报错）由 {@code tryRefreshRenyiboSource} 实时重拉兜底。
     *
     * @return 落地地址；解析失败返回原地址（沿用旧行为，交给播放器/兜底链）
     */
    public static String resolveUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return rawUrl;
        synchronized (RESOLVE_CACHE_LOCK) {
            long now = System.currentTimeMillis();
            String hit = RESOLVE_CACHE_URLS.get(rawUrl);
            if (hit != null && now - RESOLVE_CACHE_AT.getOrDefault(rawUrl, 0L) < RESOLVE_TTL_MS) {
                return hit;
            }
        }
        String finalUrl = resolveFinalUrl(rawUrl);
        synchronized (RESOLVE_CACHE_LOCK) {
            RESOLVE_CACHE_URLS.put(rawUrl, finalUrl);
            RESOLVE_CACHE_AT.put(rawUrl, System.currentTimeMillis());
        }
        return finalUrl;
    }

    private static final Object RESOLVE_CACHE_LOCK = new Object();
    private static final java.util.Map<String, String> RESOLVE_CACHE_URLS = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> RESOLVE_CACHE_AT = new java.util.HashMap<>();
    private static final long RESOLVE_TTL_MS = 5L * 60L * 1000L;

    /** 是否为源5协议串频道地址（renyibo://<apiId>；订阅源 renyibo://all 除外） */
    public static boolean isRenyiboSchemeUrl(String url) {
        return url != null && url.startsWith(SCHEME) && !url.equalsIgnoreCase("renyibo://all");
    }

    /** 从协议串地址取 apiId（renyibo://zb-cctv1 → zb-cctv1）；非协议串返回 null */
    public static String apiIdOfScheme(String url) {
        if (!isRenyiboSchemeUrl(url)) return null;
        return url.substring(SCHEME.length()).trim();
    }

    /**
     * 按频道 apiId 实时解析最新落地地址（fresh live.php → 302 → 真 CDN），带 5 分钟缓存。
     *
     * <p>php 跳转地址里的 ZY- token 存活期极短，缓存旧 php URL 再解析必然失败
     * （2026-10-07 实测），故协议串一律走 fresh 解析；缓存只对「5 分钟内同频道重播」生效。
     *
     * @return 落地地址；失败返回 {@code null}（调用方走实时重拉兜底）
     */
    public static String resolveByApiId(String apiId) {
        if (apiId == null || apiId.isEmpty()) return null;
        synchronized (SCHEME_LOCK) {
            String hit = SCHEME_FINAL.get(apiId);
            if (hit != null
                    && System.currentTimeMillis() - SCHEME_AT.getOrDefault(apiId, 0L) < RESOLVE_TTL_MS) {
                return hit;
            }
        }
        String finalUrl = fetchUrlById(apiId);
        if (finalUrl != null && !finalUrl.isEmpty()) {
            synchronized (SCHEME_LOCK) {
                SCHEME_FINAL.put(apiId, finalUrl);
                SCHEME_AT.put(apiId, System.currentTimeMillis());
            }
        }
        return finalUrl;
    }

    private static final Object SCHEME_LOCK = new Object();
    private static final java.util.Map<String, String> SCHEME_FINAL = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> SCHEME_AT = new java.util.HashMap<>();

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * @param resolve302 false=直接返回 live.php 下发的跳转地址（列表生成用，零 CDN 请求）；
     *                   true=跟随 302 解析到真实 CDN 落地地址（单频道起播/重拉用）
     */
    private static String[] fetchOne(String id, boolean resolve302) {
        try {
            String text = NetUtil.getInstance().syncGetText(BASE + id);
            if (text == null || text.isEmpty()) return null;
            JSONObject o = new JSONObject(text);
            int code = o.optInt("code", 0);
            String url = o.optString("url", "");
            if (code != 200 || url.isEmpty()) {
                LogBridge.w(TAG, id + " 取流失败: " + o.optString("msg", "code=" + code));
                return null;
            }
            String name = o.optString("name", id);
            if (name.isEmpty()) name = id;
            String playUrl = url;
            if (resolve302) {
                // ★关键：live.php 下发的直链会 302 跳到真实 CDN（如 live-play-hls.cctvnews.cctv.com/…m3u8?auth_key=…），
                //   而 m3u8 里的分片地址是**相对路径**。ExoPlayer 以「原始请求 URI」为基准解析相对地址
                //   → 会拼成 http://110.42.52.180:6688/zb/<分片>.ts → 404（实测）。
                //   故这里先把跳转解析掉，直接给出最终 CDN 地址作为播放地址。
                playUrl = resolveFinalUrl(url);
            }
            // 【2026-10-07】resolve302=false 时返回跳转地址原样入 M3U（咪咕惰性解析模式）：
            //   列表生成不再为 97 个频道各发一次全量 CDN GET（实测省 25s+），
            //   真实地址延迟到「起播这一刻」由 TVPlayerManager 调 {@link #resolveUrl} 解析。
            return new String[]{id, name, groupOf(id), playUrl};
        } catch (Throwable t) {
            LogBridge.w(TAG, id + " 异常: " + t.getMessage());
            return null;
        }
    }

    /**
     * 跟随重定向拿到最终地址（相对分片必须以此基准解析，否则 404）。
     * 无跳转或解析失败时返回原地址。
     */
    private static String resolveFinalUrl(String url) {
        try (Response resp = NetUtil.getInstance().syncGet(url)) {
            if (resp != null && resp.request() != null && resp.request().url() != null) {
                String finalUrl = resp.request().url().toString();
                if (finalUrl != null && !finalUrl.isEmpty()) {
                    if (!finalUrl.equals(url)) {
                        LogBridge.d(TAG, "302 解析: " + url + " -> " + finalUrl);
                    }
                    return finalUrl;
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "重定向解析失败，沿用原地址：" + t.getMessage());
        }
        return url;
    }

    /** 由频道 ID 推导分组（返回 null 表示该频道已被移除，不进入列表）。 */
    private static String groupOf(String id) {
        // 【2026-10-13 分组合并】「卫视」「教育」「特色」并入「央视」；「地方」分组整个删除。
        if ("zb-fztd".equals(id)) return "央视";   // 法治天地是中央/专题频道，例外先判（含 "zb-fz" 前缀）
        if (hasAnyPrefix(id, "zb-xm", "zb-qz", "zb-zz", "zb-pt", "zb-sm", "zb-nd", "zb-np",
                "zb-klg", "zb-klcd", "zb-fz", "zb-ly", "zb-csxw", "zb-ydds")) return null; // 原「地方」→ 移除
        return "央视";                              // 央视/卫视/教育/特色 全部并入央视
    }

    private static boolean hasAnyPrefix(String s, String... prefixes) {
        for (String p : prefixes) if (s.startsWith(p)) return true;
        return false;
    }

    /**
     * 【2026-10-13 台标】按频道名推导 logo.catvod.com 台标 URL（对齐内置源1 playlist.m3u 的命名：
     * {@code https://logo.catvod.com/<链路名>.png}）。
     *
     * <p>规则：取频道名前导 ASCII 段（如 {@code CCTV1综合} → {@code CCTV1}、{@code CETV1综合教育}
     * → {@code CETV1}）；无前导 ASCII 的中文台名（如 {@code 浙江卫视}）则整体作为文件名。
     * 图床上不存在该文件时 ChannelLogoLoader 会回退至本地 {@code ic_channel_logo_placeholder} 兜底，不影响展示。
     */
    private static String logoTvgUrl(String name) {
        if (name == null || name.isEmpty()) return "";
        String ascii = leadingAsciiTag(name);
        String tag = ascii.isEmpty() ? name : ascii;
        return "https://logo.catvod.com/" + tag + ".png";
    }

    /** 取频道名前导 ASCII 段（字母/数字/符号）；首个中文即截断返回前缀（无前导则返回空串）。 */
    private static String leadingAsciiTag(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x80 && c != ' ' && c != '\t') {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }
}
