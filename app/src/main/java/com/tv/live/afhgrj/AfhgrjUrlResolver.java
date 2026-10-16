package com.tv.live.afhgrj;

import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import okhttp3.Response;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置源7（afhgrj 影视点播）取流解析器。
 *
 * <p>站点为 MacCMS 架构，三级链路：
 * <pre>
 *   分类页 /y/{catId}/  →  详情页 /yd/{vodId}/  →  播放页 /yplay/{vodId}-{sid}-{nid}/
 * </pre>
 * 播放页内嵌 {@code player_aaaa} JSON，其中的 {@code url} 字段即明文 m3u8（{@code encrypt:0}，
 * 无 native 依赖；JSON 转义 {@code \/} 需还原为 {@code /}）。
 *
 * <p>协议（照搬内置源6 咪咕「M3U 存协议串、起播惰性解析」）：
 * <ul>
 *   <li>{@code afhgrj://{vodId}} —— 该片第 1 集（最小 sid、最小 nid）</li>
 *   <li>{@code afhgrj://{vodId}-{sid}-{nid}} —— 单集（由 EPG 集数项携带）</li>
 * </ul>
 *
 * <p>频道 {@code channelId} 写作 {@code afhgrj:{vodId}}（见 {@link #TVG_PREFIX}）。
 *
 * <p>【2026-10-10 线路兜底】站点每部片有 1~3 个播放源（sid），各走不同 CDN，且线路会各自
 * 挂掉（实测同一部片 sid=1 的 {@code vod.feifei-video.com} 与 sid=2 的
 * {@code v10.baofeng10.com} 整条不可达，sid=3 的 {@code play.ly166.com} 正常）。
 * 旧实现固定取最小 sid，一旦该线路挂掉这部片就永远打不开（换集也没用，还是同一条线路），
 * 冷启动续播到它即黑屏。现在 {@link #resolve} 会按 sid 逐条探活换线，并把可用 sid 记入
 * {@link #sWorkingSid} 供同片后续集数直接复用。
 */
public final class AfhgrjUrlResolver {

    private static final String TAG = "AfhgrjUrlResolver";

    /** 自定义协议前缀：M3U 中每个影片地址写作 {@code afhgrj://<vodId>} */
    public static final String SCHEME = "afhgrj://";

    /** M3U 中写成 {@code tvg-id="afhgrj:<vodId>"}，解析后 channelId 即为 {@code afhgrj:<vodId>} */
    public static final String TVG_PREFIX = "afhgrj:";

    private static final String BASE = "https://www.afhgrj.com";

    /** 移动端 UA（站点对桌面 UA 的排版不同，用移动端更稳定） */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 集数列表内存缓存（vodId → 集数 EpgItem） */
    private static final Map<String, List<Channel.EpgItem>> sEpisodeCache = new ConcurrentHashMap<>();

    /**
     * 【2026-10-10 线路兜底】vodId → 该片全部播放源 sid（升序）。
     *
     * <p>站点每部片配 1~3 个播放源，各走不同 CDN；线路会各自挂掉（实测
     * {@code vod.feifei-video.com}、{@code v10.baofeng10.com} 整条不可达，
     * 而同一部片的 sid=3 {@code play.ly166.com} 正常 200），固定用最小 sid
     * 会导致「这部片永远打不开、换集也没用」。
     */
    private static final Map<String, int[]> sSidCache = new ConcurrentHashMap<>();

    /** vodId → 最近一次探活通过的 sid（同片后续集数优先复用它，省去重复探活） */
    private static final Map<String, Integer> sWorkingSid = new ConcurrentHashMap<>();

    /** 线路探活超时（只做建连+取状态码，故给得短；失败即换下一条 sid） */
    private static final int PROBE_CONNECT_TIMEOUT_MS = 2500;
    private static final int PROBE_READ_TIMEOUT_MS = 2500;

    // ------------------------------------------------------------------
    // 【2026-10-12 线路测速择优】把「只判 200」的探活升级为「下载取样 + 计时」
    //   背景（2026-10-10 实测）：源7 的线路只判 HTTP 2xx 会选中「能连上但极慢」的死慢线。
    //   当场抓到：v.fengbao11.com 返回 200 但只有 ~137 KB/s，播放表现为「播 2 秒卡 8 秒」；
    //   而同片的 play.ly166.com 高达 ~315 KB/s。旧逻辑根本不会去试后者。
    // ------------------------------------------------------------------

    /** 测速取样字节数（约 128KB）：足以区分 100KB/s 与 300KB/s，又不至于拖慢起播 */
    private static final int SPEED_PROBE_BYTES = 128 * 1024;
    /** 测速最长观测时间；到点就用已读到的字节数折算速率（死慢线不至于卡住起播） */
    private static final long SPEED_PROBE_MAX_MS = 3000L;
    /** 「够快」门槛（KB/s）：达到即采用该线路，不再继续比别人。实测 ly166=315、慢线=137，取中间值 */
    private static final int SPEED_ACCEPT_KBPS = 250;
    /** 有效测速的最少采样字节（分片 EOF 提前结束时可放宽到 8KB） */
    private static final int MIN_SPEED_PROBE_BYTES = 32 * 1024;
    /** 有效测速的最短观测时间（ms）：太短说明首包被缓冲，折算值不可信 */
    private static final long MIN_SPEED_PROBE_MS = 60L;

    /**
     * 优先主机白名单（按优先级从高到低）。命中即直接采用，跳过测速。
     *
     * <p>2026-10-10 实测 10 个 CDN：{@code play.ly166.com:65} = 315 KB/s 断层第一，
     * 且是抽样 6 部片里 5 部都有的<b>唯一跨片通用 CDN</b>。这是「白名单兜底」：
     * 站点换域名后白名单会失效，届时由上面的测速逻辑自动接管。
     */
    private static final String[] PREFERRED_HOSTS = {
            // 【2026-10-13 水印规避】原白名单 host "play.ly166.com" 已移除：
            //   实测该 CDN 的正片分片内**烧录**了「249331.com 注册即送888！主营：棋牌 电子
            //   真人 捕鱼 体育 电竞」横幅水印（压制在视频帧内，m3u8 层无法剥离），
            //   故不再作为首选，改由 WATERMARK_HOST_MARKERS 统一降级为「兜底」。
            //
            // 【2026-10-13 线路体检·抽帧核验】把实测「干净且可达」的源7 CDN 提为优选，
            //   让默认线路直接落在这类干净线上（命中后仍会过一次测速做可达性校验，
            //   线路挂掉/被封时自动继续比别的线，不会因白名单而强选死线）。
            //   样本：《吞噬星空》第01集三线路同帧对比（同一秒画面）——
            //     sid=1 p.bvvvvvvvvv1f.com → 干净；sid=2 vod.feifei-kan.com → 干净；
            //     sid=3 play.ly166.com:65 → 顶部博彩横幅（已降级）。
            //   ⚠ 站点换域名后白名单会失效 → 自动退回测速选线，但「水印黑名单」始终生效。
            "p.bvvvvvvvvv1f.com",
            "vod.feifei-kan.com",
    };

    /**
     * 【2026-10-13 水印规避】已知带「博彩烧录水印」的 CDN 主机特征（host 子串匹配，小写）。
     *
     * <p>背景：源7《吞噬星空》第 210 集三线路同日对比实测（抽帧核验）——
     * <ul>
     *   <li>sid=1 {@code v.baofeng9.com}（暴风系）：正片帧干净；</li>
     *   <li>sid=2 {@code vip.ffzy-plays.com}（非凡系）：正片帧干净；</li>
     *   <li>sid=3 {@code play.ly166.com}：正片帧<b>烧录博彩横幅水印</b>（片头/正片全程可见）。</li>
     * </ul>
     * 由于该水印压制在视频编码帧内（不是独立广告分片），广告剥离器（只作用于 m3u8 清单）
     * 无法去除 → 唯一可行做法是<b>选线时避开这些 CDN</b>：命中即跳过并降级为兜底，
     * 优先采用干净线路；只有全部干净线路不可达时才回退带水印线路（保证仍有画面）。</p>
     */
    private static final String[] WATERMARK_HOST_MARKERS = { "ly166.com" };

    /**
     * 线路测速结果短期缓存：{@code vodId + "-" + sid} → [kbps, 时间戳]。
     * 避免同一部片每次切集都重新测速（一部 48 集的剧会白白多测 48 次）。
     */
    private static final Map<String, long[]> sSidSpeed = new ConcurrentHashMap<>();
    private static final long SID_SPEED_TTL_MS = 10L * 60L * 1000L;

    // ------------------------------------------------------------------
    // 方案1：最近落地 m3u8 缓存（减少源7起播的串行联网）
    // ------------------------------------------------------------------

    /** 落地缓存「免检」信任期：10 分钟内直接复用，0 次网络（起播最快路径） */
    private static final long RESOLVED_TRUST_MS = 10L * 60L * 1000L;
    /**
     * 落地缓存「需校验」保留期：6 小时内仍复用，但复用前先拉一次 m3u8 校验可达性。
     *
     * <p>【2026-10-11】由原来的 10 分钟延长到 6 小时：源7 冷启动的主要耗时就是「播放页
     * /yplay/ + sid 测速取样」（实测 2.6~4.9s，站点慢时更久），而播放页解析出的 m3u8 路径
     * 对同一部片/同一集是稳定的（实测多次重启 URL 完全一致）。延长保留期后，10 分钟~6 小时
     * 内重启只需 1 次 m3u8 校验请求（~0.2~0.3s）即可复用，省掉播放页+探活。
     * 校验不通过（线路轮换/过期/被墙）则丢弃走完整链路；即便误判，播放失败时
     * {@code TVPlayerManager} 会调 {@link #invalidate} 兜底重解析，不会一坏到底。
     */
    private static final long RESOLVED_TTL_MS = 6L * 60L * 60L * 1000L;

    /** 落地缓存校验：连接/读取超时（短超时——校验必须"失败得快"，否则不如直接走完整链路） */
    private static final int VERIFY_CONNECT_TIMEOUT_MS = 1500;
    private static final int VERIFY_READ_TIMEOUT_MS = 1500;
    /** 落地缓存校验：只读头 2KB 判断有无 #EXTM3U */
    private static final int VERIFY_MAX_BYTES = 2048;
    /** 落地缓存磁盘文件（跨进程冷启动复用最近一次成功解析的 m3u8） */
    private static final String RESOLVED_CACHE_FILE = "afhgrj_resolved.cache";
    /** 落地缓存条目：v{afhgrjUrl} → m3u8 */
    private static final Map<String, String> sResolvedCache = new ConcurrentHashMap<>();
    /** 落地缓存落盘时间（v{afhgrjUrl} → tsMs），命中且未过期才可免联网 */
    private static final Map<String, Long> sResolvedCacheTs = new ConcurrentHashMap<>();

    /** 解析所用 application context（init 时注入；用于读写磁盘缓存） */
    private static volatile android.content.Context sAppContext = null;

    // ------------------------------------------------------------------
    // 【2026-10-11 新增】集数/sid「详情缓存」磁盘持久化（跨进程冷启动复用）
    //
    // 背景：集数列表（详情页）与全量 sid 此前只存内存，于是每次「软件重启」都要重抓一次
    // 详情页 —— 站点慢时实测这一步要 7~8s，在 OTT 上就表现为起播长时间黑屏。
    // 做法完全对齐本类既有的落地缓存（afhgrj_resolved.cache）：TSV 落 filesDir、
    // init() 时加载、解析成功后异步覆盖写。
    // ------------------------------------------------------------------

    /** 详情缓存磁盘文件；每行：{@code vodId \t minSid \t allSidsCSV \t nidsCSV \t tsMs} */
    private static final String EPISODE_CACHE_FILE = "afhgrj_episodes.cache";
    /** 详情缓存有效期：6 小时（剧集数/线路变化很慢；到期自动重抓，兼顾在播剧补集） */
    private static final long EPISODE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    /** 详情缓存落盘时间（vodId → tsMs） */
    private static final Map<String, Long> sEpisodeCacheTs = new ConcurrentHashMap<>();

    private AfhgrjUrlResolver() {}

    /**
     * 注入 application context（供落地缓存落盘）。应在应用启动早期调用一次。
     */
    public static void init(android.content.Context appContext) {
        if (appContext != null) sAppContext = appContext.getApplicationContext();
        loadResolvedCache();
        loadEpisodeCache();
    }

    /**
     * 方案1b：某部影片落地地址失效（起播后播放失败）时，清除其落地缓存，
     * 下次解析从完整链路重试（含探活换线），避免缓存一坏到底。
     *
     * @param vodId 该部影片 id
     */
    public static void invalidate(String vodId) {
        if (TextUtils.isEmpty(vodId)) return;
        String prefix = "v" + SCHEME + vodId;
        sResolvedCache.keySet().removeIf(k -> k.startsWith(prefix));
        sResolvedCacheTs.keySet().removeIf(k -> k.startsWith(prefix));
        // 线路测速结果一并作废：既然播放已经失败，之前测出的速率不再可信，
        // 下次解析重新取样（否则会一直沿用一条已恶化线路的旧速率）。
        String speedPrefix = vodId + "-";
        sSidSpeed.keySet().removeIf(k -> k.startsWith(speedPrefix));
        LogBridge.i(TAG, "源7(影视) 已失效落地缓存 vodId=" + vodId);
    }

   // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 落地缓存 key：{@code v}<afhgrjUrl>（带 sid-nid，按「单集」粒度缓存，
     * 切集不相互污染；v0 前缀避免与任何天然 m3u8 串混淆）。
     */
    private static String resolvedKey(String afhgrjUrl) {
        return "v" + afhgrjUrl;
    }

    /** 命中且未过期则取出落地缓存（并刷新其时间戳语义：解析成功后重置计时）。 */
    private static String popResolvedCache(String key) {
        Long ts = sResolvedCacheTs.get(key);
        if (ts == null) return null;
        if (System.currentTimeMillis() - ts > RESOLVED_TTL_MS) {
            sResolvedCache.remove(key);
            sResolvedCacheTs.remove(key);
            return null;
        }
        String v = sResolvedCache.get(key);
        // 【2026-10-13 水印规避】缓存地址若落在带烧录水印的 CDN → 立即作废，强制重走选线。
        // （否则改了选线策略也会被旧缓存短路，用户依旧看到 ly166 的水印）
        if (v != null && isWatermarkHost(v)) {
            LogBridge.w(TAG, "源7(影视) 落地缓存命中带水印线路，作废并重解析："
                    + v.substring(0, Math.min(110, v.length())));
            sResolvedCache.remove(key);
            sResolvedCacheTs.remove(key);
            return null;
        }
        return v;
    }

    /** 写入落地缓存（内存 + 异步落盘）。 */
    private static void putResolvedCache(String key, String m3u8) {
        if (key == null || TextUtils.isEmpty(m3u8)) return;
        long now = System.currentTimeMillis();
        sResolvedCache.put(key, m3u8);
        sResolvedCacheTs.put(key, now);
        persistResolvedCache();
    }

    /** 落地缓存是否处于「免检信任期」内（10 分钟）：是则直接复用，不发任何请求。 */
    private static boolean isResolvedFresh(String key) {
        Long ts = sResolvedCacheTs.get(key);
        if (ts == null || (System.currentTimeMillis() - ts) > RESOLVED_TRUST_MS) return false;
        // 【2026-10-13 水印规避】带水印线路的缓存不算「免检新鲜」（应改走完整选线）
        String v = sResolvedCache.get(key);
        return v == null || !isWatermarkHost(v);
    }

    /**
     * 轻量校验：缓存的 m3u8 是否仍可达。
     *
     * <p>只拉一次 m3u8 内容判断有无 {@code #EXTM3U}（同一目录下的分片随该目录一起轮换，
     * 故 m3u8 可达即认为该集地址仍有效）。
     *
     * <p>【2026-10-11 实测】不能用 {@link #getText}（NetUtil 栈）——同一 URL 实测 ~3s，
     * 比完整链路还慢；必须走与播放/测速同源的直连栈，实测 ~0.23s。且用短超时，
     * 「失败要失败得快」，否则不如直接走完整链路。
     */
    private static boolean isM3u8Alive(String m3u8) {
        if (TextUtils.isEmpty(m3u8)) return false;
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        boolean ok = false;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(m3u8).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(VERIFY_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(VERIFY_READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", BASE + "/");
            conn.setRequestProperty("Range", "bytes=0-" + (VERIFY_MAX_BYTES - 1));
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                in = conn.getInputStream();
                byte[] buf = new byte[VERIFY_MAX_BYTES];
                int r = in.read(buf);
                if (r > 0) {
                    ok = new String(buf, 0, r, "UTF-8").contains("#EXTM3U");
                }
            }
        } catch (Throwable t) {
            ok = false;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
        LogBridge.i(TAG, "源7(影视) 落地缓存校验" + (ok ? "通过" : "不通")
                + " → " + m3u8.substring(0, Math.min(110, m3u8.length())));
        return ok;
    }

    // ------------------------------------------------------------------
    // 落地缓存：磁盘读写（跨进程冷启动复用最近一次成功解析）
    // ------------------------------------------------------------------

    private static void loadResolvedCache() {
        android.content.Context ctx = sAppContext;
        if (ctx == null) return;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), RESOLVED_CACHE_FILE);
            if (!f.exists() || f.length() <= 0) return;
            long mtime = f.lastModified();
            long now = System.currentTimeMillis();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    int i1 = line.indexOf('\t');
                    if (i1 <= 0) continue;
                    int i2 = line.indexOf('\t', i1 + 1);
                    String k = line.substring(0, i1);
                    String v = (i2 > 0) ? line.substring(i1 + 1, i2) : line.substring(i1 + 1);
                    // 【2026-10-11】新增每行时间戳（老格式无第 3 段时回退用文件 mtime），
                    // 保证「10 分钟免检信任期」在重启后依然准确。
                    long ts = (i2 > 0) ? parseLongSafe(line.substring(i2 + 1)) : mtime;
                    if (ts <= 0) ts = mtime;
                    if (now - ts > RESOLVED_TTL_MS) continue;   // 超过保留期，丢弃
                    if (v.isEmpty()) continue;
                    sResolvedCache.put(k, v);
                    sResolvedCacheTs.put(k, ts);
                }
            } finally {
                br.close();
            }
            if (sResolvedCache.isEmpty()) {
                f.delete();
                return;
            }
            LogBridge.i(TAG, "源7(影视) 落地缓存加载完成，共 " + sResolvedCache.size() + " 条");
        } catch (Throwable t) {
            LogBridge.w(TAG, "源7(影视) 落地缓存加载失败：" + t.getMessage());
        }
    }

    /** 异步把落地缓存整体写盘（文件不大，覆盖写一次即可）。 */
    private static void persistResolvedCache() {
        final android.content.Context ctx = sAppContext;
        if (ctx == null) return;
        new Thread(() -> {
            try {
                java.io.File f = new java.io.File(ctx.getFilesDir(), RESOLVED_CACHE_FILE);
                StringBuilder sb = new StringBuilder();
                long now = System.currentTimeMillis();
                for (Map.Entry<String, String> e : sResolvedCache.entrySet()) {
                    Long ts = sResolvedCacheTs.get(e.getKey());
                    sb.append(e.getKey()).append('\t').append(e.getValue()).append('\t')
                            .append(ts == null ? now : ts).append('\n');
                }
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                try {
                    fos.write(sb.toString().getBytes("UTF-8"));
                    fos.flush();
                } finally {
                    fos.close();
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "源7(影视) 落地缓存落盘失败：" + t.getMessage());
            }
        }, "Afhgrj-CacheWrite").start();
    }

    // ------------------------------------------------------------------
    // 详情缓存（集数 + 全量 sid）：磁盘读写
    // ------------------------------------------------------------------

    /** 异步把「集数 + 全量 sid」详情缓存整体写盘（覆盖写一次；文件约几十 KB）。 */
    private static void persistEpisodeCache() {
        final android.content.Context ctx = sAppContext;
        if (ctx == null) return;
        new Thread(() -> {
            try {
                java.io.File f = new java.io.File(ctx.getFilesDir(), EPISODE_CACHE_FILE);
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, List<Channel.EpgItem>> e : sEpisodeCache.entrySet()) {
                    String vodId = e.getKey();
                    List<Channel.EpgItem> eps = e.getValue();
                    if (TextUtils.isEmpty(vodId) || eps == null || eps.isEmpty()) continue;
                    int[] sids = sSidCache.get(vodId);
                    if (sids == null || sids.length == 0) continue;

                    // nid 列表从集数项的 customPlayUrl（afhgrj://vodId-sid-nid）里解出来，
                    // 保证「落盘内容」与「内存里正在显示的集数」严格一致。
                    StringBuilder nids = new StringBuilder();
                    for (Channel.EpgItem it : eps) {
                        if (it == null) continue;
                        int nid = nidOfCustomUrl(it.customPlayUrl);
                        if (nid <= 0) continue;
                        if (nids.length() > 0) nids.append(',');
                        nids.append(nid);
                    }
                    if (nids.length() == 0) continue;

                    long ts = System.currentTimeMillis();
                    sb.append(vodId).append('\t').append(sids[0]).append('\t');
                    for (int i = 0; i < sids.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(sids[i]);
                    }
                    sb.append('\t').append(nids).append('\t').append(ts).append('\n');
                    sEpisodeCacheTs.put(vodId, ts);
                }
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                try {
                    fos.write(sb.toString().getBytes("UTF-8"));
                    fos.flush();
                } finally {
                    fos.close();
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "源7(影视) 详情缓存落盘失败：" + t.getMessage());
            }
        }, "Afhgrj-EpgCacheWrite").start();
    }

    /** 冷启动加载详情缓存：期内条目直接重建「集数列表 + 全量 sid」，省掉一次详情页抓取。 */
    private static void loadEpisodeCache() {
        android.content.Context ctx = sAppContext;
        if (ctx == null) return;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), EPISODE_CACHE_FILE);
            if (!f.exists() || f.length() <= 0) return;
            long now = System.currentTimeMillis();
            int ymd = todayYmd();
            int loaded = 0;
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\t");
                    if (parts.length < 5) continue;
                    String vodId = parts[0];
                    int minSid = parseIntSafe(parts[1]);
                    long ts = parseLongSafe(parts[4]);
                    if (TextUtils.isEmpty(vodId) || minSid <= 0) continue;
                    if (now - ts > EPISODE_CACHE_TTL_MS) continue;   // 过期条目丢弃，走联网重抓
                    int[] sids = parseIntArray(parts[2]);
                    int[] nids = parseIntArray(parts[3]);
                    if (sids.length == 0 || nids.length == 0) continue;

                    List<Channel.EpgItem> items = new ArrayList<>(nids.length);
                    int ordinal = 0;
                    for (int nid : nids) {
                        ordinal++;
                        Channel.EpgItem it = new Channel.EpgItem("今天", "", "第" + ordinal + "集", false, ymd);
                        it.customPlayUrl = SCHEME + vodId + "-" + minSid + "-" + nid;
                        items.add(it);
                    }
                    sEpisodeCache.put(vodId, items);
                    sSidCache.put(vodId, sids);
                    sEpisodeCacheTs.put(vodId, ts);
                    loaded++;
                }
            } finally {
                br.close();
            }
            if (loaded > 0) {
                LogBridge.i(TAG, "源7(影视) 集数/sid 详情缓存加载完成，共 " + loaded + " 部（省详情页联网）");
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "源7(影视) 详情缓存加载失败：" + t.getMessage());
        }
    }

    /** 从 {@code afhgrj://<vodId>-<sid>-<nid>} 取 nid；解析失败返回 0 */
    private static int nidOfCustomUrl(String url) {
        if (TextUtils.isEmpty(url)) return 0;
        int dash = url.lastIndexOf('-');
        if (dash < 0 || dash + 1 >= url.length()) return 0;
        return parseIntSafe(url.substring(dash + 1));
    }

    /**
     * 【2026-10-11】集数项对应的站点 nid（从 {@code customPlayUrl} 解析）。
     * 供联播时「用当前播放的 nid 在列表里定位、再取下一集」使用。
     */
    public static int nidOfEpisodeItem(Channel.EpgItem it) {
        return (it == null) ? 0 : nidOfCustomUrl(it.customPlayUrl);
    }

    /** 宽松解析 long（失败返回 0） */
    private static long parseLongSafe(String s) {
        if (s == null) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 解析 "1,2,3" → [1,2,3]（非法项直接跳过） */
    private static int[] parseIntArray(String csv) {
        if (TextUtils.isEmpty(csv)) return new int[0];
        String[] parts = csv.split(",");
        int[] tmp = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            int v = parseIntSafe(p);
            if (v > 0) tmp[n++] = v;
        }
        int[] res = new int[n];
        System.arraycopy(tmp, 0, res, 0, n);
        return res;
    }

    /**
     * 是否为源7 待解析地址（{@code afhgrj://…}） */
    public static boolean isAfhgrjUrl(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /**
     * 从频道 id（{@code afhgrj:<vodId>}）取出 vodId；非源7 频道返回 {@code null}。
     */
    public static String vodIdOf(Channel ch) {
        if (ch == null) return null;
        String cid = ch.getChannelId();
        if (TextUtils.isEmpty(cid) || !cid.startsWith(TVG_PREFIX)) return null;
        String v = cid.substring(TVG_PREFIX.length()).trim();
        return v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------
    // 【2026-10-11 新增】线路（播放源 sid）对外只读接口 —— 供设置面板「频道线路选择」使用
    // ------------------------------------------------------------------

    /**
     * 该片所有播放源 sid（升序）；<b>仅读内存缓存</b>，未缓存返回空数组（不联网）。
     *
     * <p>播放该片时 {@link #resolve} / {@link #fetchEpisodes} 必然已把 sSidCache 填上，
     * 故设置面板打开时此处恒有缓存；空数组意味着尚未起播该片。
     */
    public static int[] sidsOf(String vodId) {
        if (TextUtils.isEmpty(vodId)) return new int[0];
        int[] cached = sSidCache.get(vodId);
        return (cached == null || cached.length == 0) ? new int[0] : cached;
    }

    /**
     * 该片线路名列表（形如 {@code 线路1}/{@code 线路2}/…，按 sid 升序）；仅读内存缓存。
     *
     * <p>每个 sid 即一个播放源/CDN，映射为一条可选「线路」，与设置里「频道线路选择」口径一致。
     */
    public static List<String> lineNamesOf(String vodId) {
        int[] sids = sidsOf(vodId);
        List<String> out = new ArrayList<>(sids.length);
        for (int s : sids) out.add("线路" + s);
        return out;
    }

    /**
     * 该片最近探活/手选采用的 sid（线路）；无记录返回 -1。
     *
     * <p>来源：{@link #sWorkingSid}（起播换线/手动切线路都会写）—— 正是「实际在播哪条线路」
     * 的权威依据，供设置面板打勾定位。
     */
    public static int workingSidOf(String vodId) {
        if (TextUtils.isEmpty(vodId)) return -1;
        Integer w = sWorkingSid.get(vodId);
        return w == null ? -1 : w;
    }

    /**
     * 记录用户手选的线路 sid：写入 {@link #sWorkingSid}，下次 {@link #resolve} 会优先用它，
     * 使切线路在同片后续集数（含 EPG 切集）保持一致。
     */
    public static void rememberSid(String vodId, int sid) {
        if (TextUtils.isEmpty(vodId) || sid <= 0) return;
        sWorkingSid.put(vodId, sid);
        LogBridge.i(TAG, "源7(影视) 记住用户手选线路 vodId=" + vodId + " sid=" + sid);
    }

    /**
     * 解析源7 协议地址为可直接播放的 m3u8。
     *
     * @param afhgrjUrl {@code afhgrj://<vodId>} 或 {@code afhgrj://<vodId>-<sid>-<nid>}
     * @return 最终 m3u8；失败返回 {@code null}
     */
    public static String resolve(String afhgrjUrl) {
        if (!isAfhgrjUrl(afhgrjUrl)) {
            LogBridge.w(TAG, "非法源7地址：" + afhgrjUrl);
            return null;
        }
        String rest = afhgrjUrl.substring(SCHEME.length()).trim();
        if (rest.isEmpty()) return null;

        // 【方案1】命中「最近落地 m3u8」缓存则起播，把源7冷启动/重复起播从
        // 「详情页+播放页+探活」多次串行缩到 0~1 次网络：
        //   ① 10 分钟「信任期」内 → 直接复用，0 次网络（最快）；
        //   ② 10 分钟~6 小时「保留期」内 → 先花 1 次 HTTP 校验 m3u8 可达，通过才复用。
        String cacheKey = resolvedKey(afhgrjUrl);
        String cachedUrl = popResolvedCache(cacheKey);
        if (cachedUrl != null) {
            if (isResolvedFresh(cacheKey) || isM3u8Alive(cachedUrl)) {
                LogBridge.i(TAG, "源7(影视) 命中落地缓存：" + afhgrjUrl
                        + " → " + cachedUrl.substring(0, Math.min(110, cachedUrl.length())));
                return cachedUrl;
            }
            // 校验不通：丢弃该条，走下面的完整链路（详情页已由磁盘缓存短路，只剩播放页+探活）
            LogBridge.i(TAG, "源7(影视) 落地缓存校验不通，改走完整解析：" + afhgrjUrl);
            sResolvedCache.remove(cacheKey);
            sResolvedCacheTs.remove(cacheKey);
            persistResolvedCache();
        }

        String[] parts = rest.split("-");
        String vodId = parts[0].trim();
        String sid = parts.length >= 2 ? parts[1].trim() : "";
        String nid = parts.length >= 3 ? parts[2].trim() : "";
        if (vodId.isEmpty()) return null;

        // 缺省（只有 vodId）：取最小 sid 的第一集（需读一次详情页）
        if (sid.isEmpty() || nid.isEmpty()) {
            List<Channel.EpgItem> eps = fetchEpisodes(vodId);
            if (eps != null && !eps.isEmpty()) {
                String first = eps.get(0).customPlayUrl;
                if (!TextUtils.isEmpty(first) && first.startsWith(SCHEME)) {
                    String[] p2 = first.substring(SCHEME.length()).split("-");
                    if (p2.length >= 3) {
                        sid = p2[1].trim();
                        nid = p2[2].trim();
                    }
                }
            }
            if (sid.isEmpty()) sid = "1";
            if (nid.isEmpty()) nid = "1";
        }

        // 【2026-10-10 线路兜底 + 2026-10-12 测速择优】逐条 sid 试：优先上次生效的 sid，
        // 其次地址里带的 sid，最后按 sid 升序补全。对每条拿到的 m3u8：
        //   ① 主机命中 PREFERRED_HOSTS → 直接采用（实测最快的 CDN，省掉测速）；
        //   ② 否则测速：达到 SPEED_ACCEPT_KBPS 立即采用；都不达标则用测得最快的那条；
        //   ③ 全部不可达 → 退回第一个解析出的地址（等价旧行为，不因探测误判而彻底播不了）。
        final int requestedSid = parseIntSafe(sid);
        final List<Integer> candidates = candidateSids(vodId, requestedSid);
        // 只有一条播放源时测速没有意义（没有可换的目标），直接采用省一次取样
        final boolean canFallback = candidates.size() > 1;
        String firstM3u8 = null;
        int firstSid = requestedSid;
        String bestM3u8 = null;
        int bestSid = requestedSid;
        int bestKbps = -1;
        // 【2026-10-13 水印规避】带烧录水印线路的兜底候选（仅当无任何干净线路可用时才采用）
        String wmFallbackM3u8 = null;
        int wmFallbackSid = requestedSid;
        for (int cand : candidates) {
            String pageUrl = BASE + "/yplay/" + vodId + "-" + cand + "-" + nid + "/";
            String html = getText(pageUrl);
            if (TextUtils.isEmpty(html)) {
                LogBridge.w(TAG, "源7(影视) 播放页取空：" + pageUrl);
                continue;
            }
            String m3u8 = extractPlayerUrl(html);
            if (TextUtils.isEmpty(m3u8)) {
                LogBridge.w(TAG, "源7(影视) 未解析到 player_aaaa url：" + pageUrl);
                continue;
            }
            // 【2026-10-13 线路体检】逐条 sid 打印 host + 直链，供 PC 逐线抽帧判定广告/水印。
            LogBridge.i(TAG, "源7(影视) 线路体检 vodId=" + vodId + " sid=" + cand
                    + " host=" + hostOf(m3u8) + " url=" + m3u8);
            if (firstM3u8 == null) {
                firstM3u8 = m3u8;
                firstSid = cand;
            }
            if (!canFallback) {
                bestM3u8 = m3u8;
                bestSid = cand;
                bestKbps = 0;
                break;
            }

            // ⓪ 【2026-10-13 水印规避】该线路正片带博彩烧录水印（如 ly166 系）→
            //    不作为首选，仅记录为兜底；继续比较其它线路，避免整段正片画面带水印。
            if (isWatermarkHost(m3u8)) {
                if (wmFallbackM3u8 == null) {
                    wmFallbackM3u8 = m3u8;
                    wmFallbackSid = cand;
                }
                LogBridge.w(TAG, "源7(影视) 线路带烧录水印，降级为兜底 sid=" + cand
                        + " host=" + hostOf(m3u8));
                continue;
            }

            // ① 白名单命中：固定主线路，禁止自动切换到其他线路
            if (isPreferredHost(m3u8)) {
                int kbps = cachedSpeedKbps(vodId, cand);
                if (kbps < 0) {
                    kbps = measureKbps(m3u8);
                    if (kbps > 0) putSpeedKbps(vodId, cand, kbps);
                }
                if (kbps > 0) {
                    bestM3u8 = m3u8;
                    bestSid = cand;
                    bestKbps = kbps;
                    LogBridge.i(TAG, "源7(影视) 固定主线路（白名单 host=" + hostOf(m3u8)
                            + "）sid=" + cand + " 实测 " + kbps + " KB/s");
                    break;
                }
                LogBridge.w(TAG, "源7(影视) 主线路不可达 sid=" + cand
                        + " host=" + hostOf(m3u8));
                // 【2026-10-14 固定线路】主线路不可达时，不再自动切换到其他线路
                // 直接返回 null，让上层重试/重拉
                continue;
            }

            // ② 测速：先查短期缓存，未命中才真正取样
            int kbps = cachedSpeedKbps(vodId, cand);
            if (kbps < 0) {
                kbps = measureKbps(m3u8);
                if (kbps > 0) putSpeedKbps(vodId, cand, kbps);
                LogBridge.i(TAG, "源7(影视) 线路测速 sid=" + cand + "（" + hostOf(m3u8)
                        + "）= " + kbps + " KB/s");
            } else {
                LogBridge.i(TAG, "源7(影视) 线路测速命中缓存 sid=" + cand + " = " + kbps + " KB/s");
            }
            if (kbps <= 0) {
                LogBridge.w(TAG, "源7(影视) 线路不可达 sid=" + cand + " → "
                        + m3u8.substring(0, Math.min(110, m3u8.length())));
                continue;
            }
            if (kbps > bestKbps) {
                bestKbps = kbps;
                bestM3u8 = m3u8;
                bestSid = cand;
            }
            if (kbps >= SPEED_ACCEPT_KBPS) break;
        }

        // 【2026-10-13 水印规避】没有任何干净线路可用 → 回退到带水印线路（保证仍有画面）
        if (bestM3u8 == null && wmFallbackM3u8 != null) {
            LogBridge.w(TAG, "源7(影视) 全部线路带水印或不可达，回退带水印线路 sid=" + wmFallbackSid);
            bestM3u8 = wmFallbackM3u8;
            bestSid = wmFallbackSid;
            bestKbps = 0;
        }

        if (bestM3u8 != null) {
            sWorkingSid.put(vodId, bestSid);
            if (bestSid != requestedSid) {
                LogBridge.i(TAG, "源7(影视) 线路择优换线 sid " + requestedSid + " → " + bestSid
                        + "（" + (bestKbps == Integer.MAX_VALUE ? "白名单" : bestKbps + " KB/s")
                        + "）：" + vodId + "-" + nid);
            }
            LogBridge.i(TAG, "源7(影视) 解析成功 " + vodId + "-" + bestSid + "-" + nid
                    + " → " + bestM3u8.substring(0, Math.min(110, bestM3u8.length())));
            putResolvedCache(resolvedKey(afhgrjUrl), bestM3u8);
            return bestM3u8;
        }

        if (firstM3u8 != null) {
            LogBridge.w(TAG, "源7(影视) 全部线路不可达，退回 sid=" + firstSid + " 的地址");
            sWorkingSid.put(vodId, firstSid);
        }
        return firstM3u8;
    }

    /**
     * 读内存缓存的集数列表（<b>不联网</b>）。
     *
     * @return 缓存命中返回列表；未命中返回 {@code null}
     */
    public static List<Channel.EpgItem> getEpisodes(String vodId) {
        if (TextUtils.isEmpty(vodId)) return null;
        return sEpisodeCache.get(vodId);
    }

    /**
     * 抓详情页并解析集数列表（联网；结果写内存缓存）。
     *
     * <p>取<b>最小 sid</b> 的 nid 升序列表（站点每部片有 1~3 个播放源，取第一个播放源即可）。
     * 集数项 {@code title = 第N集}、{@code customPlayUrl = afhgrj://vodId-sid-nid}。
     *
     * @return 集数列表；失败返回 {@code null}
     */
    public static List<Channel.EpgItem> fetchEpisodes(String vodId) {
        if (TextUtils.isEmpty(vodId)) return null;
        List<Channel.EpgItem> cached = sEpisodeCache.get(vodId);
        if (cached != null && !cached.isEmpty()) return cached;
        // 【2026-10-11 单飞去重】同一部片的详情页会被多处几乎同时请求：
        //   ① TVPlayerManager 起播解析 resolve()；② OTT EPG 面板；③ refreshNowPlayingProgram。
        //   实测一次起播出现 3~5 次「集数解析成功」= 同一页面被抓多遍（并发重复，既浪费流量
        //   也不符合本项目「串行、不并发」红线）。这里按 vodId 加锁做 single-flight：
        //   等锁期间若已被填充则直接复用结果，绝不再发第二次请求。
        synchronized (episodeFetchLock(vodId)) {
            cached = sEpisodeCache.get(vodId);
            if (cached != null && !cached.isEmpty()) return cached;
            return doFetchEpisodes(vodId);
        }
    }

    /** vodId 级单飞锁（仅内部使用；条目数 = 频道数级，可忽略） */
    private static final Map<String, Object> sEpisodeFetchLocks = new ConcurrentHashMap<>();

    private static Object episodeFetchLock(String vodId) {
        Object lock = sEpisodeFetchLocks.get(vodId);
        if (lock == null) {
            Object created = new Object();
            Object prev = sEpisodeFetchLocks.putIfAbsent(vodId, created);
            lock = (prev != null) ? prev : created;
        }
        return lock;
    }

    /**
     * 详情页抓取实际实现。调用方 {@link #fetchEpisodes} 负责缓存短路与 vodId 级单飞。
     */
    private static List<Channel.EpgItem> doFetchEpisodes(String vodId) {
        String html = getText(BASE + "/yd/" + vodId + "/");
        if (TextUtils.isEmpty(html)) {
            LogBridge.w(TAG, "源7(影视) 详情页取空 vodId=" + vodId);
            return null;
        }

        Pattern p = Pattern.compile("/yplay/" + Pattern.quote(vodId) + "-(\\d+)-(\\d+)/");

        // 【2026-10-10 线路兜底】一次遍历同时收集「全部 sid」与「最小 sid」：
        // 集数列表仍按最小 sid 编排（编号口径不变），全部 sid 交给 resolve() 做换线兜底。
        TreeSet<Integer> allSids = new TreeSet<>();
        int minSid = Integer.MAX_VALUE;
        Matcher m = p.matcher(html);
        while (m.find()) {
            int sid = parseIntSafe(m.group(1));
            if (sid > 0) {
                allSids.add(sid);
                if (sid < minSid) minSid = sid;
            }
        }
        if (minSid == Integer.MAX_VALUE) {
            LogBridge.w(TAG, "源7(影视) 未找到播放源 vodId=" + vodId);
            return null;
        }

        TreeSet<Integer> nids = new TreeSet<>();
        m = p.matcher(html);
        while (m.find()) {
            if (parseIntSafe(m.group(1)) == minSid) {
                int nid = parseIntSafe(m.group(2));
                if (nid > 0) nids.add(nid);
            }
        }
        if (nids.isEmpty()) return null;

        int ymd = todayYmd();
        List<Channel.EpgItem> items = new ArrayList<>(nids.size());
        int ordinal = 0;
        for (Integer nid : nids) {
            ordinal++;
            Channel.EpgItem it = new Channel.EpgItem("今天", "", "第" + ordinal + "集", false, ymd);
            it.customPlayUrl = SCHEME + vodId + "-" + minSid + "-" + nid;
            items.add(it);
        }
        sEpisodeCache.put(vodId, items);
        sSidCache.put(vodId, toIntArray(allSids));
        sEpisodeCacheTs.put(vodId, System.currentTimeMillis());
        persistEpisodeCache();
        LogBridge.i(TAG, "源7(影视) 集数解析成功 vodId=" + vodId + " sid=" + minSid
                + " 共 " + items.size() + " 集（播放源 " + allSids.size() + " 条："
                + allSids + "）");
        return items;
    }

    // ------------------------------------------------------------------
    // 解析辅助
    // ------------------------------------------------------------------

    /** 该片的候选 sid 顺序：上次探活通过的 → 地址里带的 → 其余升序补全（去重保序） */
    private static List<Integer> candidateSids(String vodId, int requestedSid) {
        List<Integer> out = new ArrayList<>();
        Integer working = sWorkingSid.get(vodId);
        if (working != null && working > 0) out.add(working);
        if (requestedSid > 0) out.add(requestedSid);
        for (int s : allSids(vodId)) out.add(s);
        if (out.isEmpty()) out.add(1);

        List<Integer> uniq = new ArrayList<>(out.size());
        for (Integer s : out) {
            if (!uniq.contains(s)) uniq.add(s);
        }
        return uniq;
    }

    /** 该片全部 sid；未缓存则借 {@link #fetchEpisodes} 拉一次详情页（顺带写入缓存） */
    private static int[] allSids(String vodId) {
        int[] cached = sSidCache.get(vodId);
        if (cached != null && cached.length > 0) return cached;
        fetchEpisodes(vodId);
        cached = sSidCache.get(vodId);
        return cached != null ? cached : new int[0];
    }

    private static int[] toIntArray(TreeSet<Integer> set) {
        int[] arr = new int[set.size()];
        int i = 0;
        for (Integer v : set) arr[i++] = v;
        return arr;
    }

    /**
     * 线路测速：**抓真实媒体分片**抽样计时，返回折算速率（KB/s）。拿不到分片 → 0。
     *
     * <p>取代旧的 {@code probePlayable}（只判 2xx）。旧逻辑的致命缺陷已实测确认：
     * {@code v.fengbao11.com} 返回 200 但只有 ~137 KB/s，会被判为"可用"并优先选中，
     * 播放表现为「播 2 秒卡 8 秒」；而真正快的同片线路（{@code play.ly166.com} ~315 KB/s）
     * 根本不会被尝试。
     *
     * <p>⚠️ 必须测「分片」而不是「m3u8 播放列表」：播放列表只有几百字节到几 KB，
     * 取样循环会立刻 EOF，折算出来的只是「几 KB ÷ 首包耗时」，与视频带宽无关，
     * 还可能给出离谱的巨大值把慢线误判成最快。
     *
     * <p>链路：m3u8 → 若是 master（含 {@code EXT-X-STREAM-INF}）先取第一个变体的播放列表 →
     * 取第一个分片 URI → 下载最多 {@link #SPEED_PROBE_BYTES} 字节并计时。
     */
    private static int measureKbps(String m3u8Url) {
        try {
            String playlist = fetchTextShort(m3u8Url);
            if (TextUtils.isEmpty(playlist)) return 0;
            String segUrl;
            if (playlist.contains("EXT-X-STREAM-INF")) {
                // master 播放列表：第一个非注释行是「变体播放列表」，再下一层才是分片
                String variantUrl = firstUri(playlist, m3u8Url);
                if (variantUrl == null) return 0;
                String variant = fetchTextShort(variantUrl);
                if (TextUtils.isEmpty(variant)) return 0;
                segUrl = firstUri(variant, variantUrl);
            } else {
                segUrl = firstUri(playlist, m3u8Url);
            }
            if (segUrl == null) return 0;
            return measureDownloadKbps(segUrl);
        } catch (Throwable t) {
            LogBridge.w(TAG, "线路测速异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return 0;
        }
    }

    /** 短超时取文本（探活用）。避免 NetUtil 全局 10s/15s 超时把一条死线拖住整个解析。 */
    private static String fetchTextShort(String url) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", BASE + "/");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
                if (bos.size() > 512 * 1024) break;   // 播放列表不可能这么大，防御
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /** 播放列表里第一个非注释行（相对地址按 base 解析为绝对地址）；没有返回 null */
    private static String firstUri(String playlist, String base) {
        if (playlist == null) return null;
        for (String raw : playlist.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("http://") || l.startsWith("https://")) return l;
            try {
                return java.net.URI.create(base).resolve(l).toString();
            } catch (Throwable t) {
                return l;
            }
        }
        return null;
    }

    /** 下载最多 {@link #SPEED_PROBE_BYTES} 字节并计时，返回 KB/s；采样不足/过快均判为 0 */
    private static int measureDownloadKbps(String url) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", BASE + "/");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return 0;
            in = conn.getInputStream();
            byte[] buf = new byte[16 * 1024];
            long start = android.os.SystemClock.elapsedRealtime();
            long total = 0;
            boolean eof = false;
            while (total < SPEED_PROBE_BYTES) {
                if (android.os.SystemClock.elapsedRealtime() - start > SPEED_PROBE_MAX_MS) break;
                int r = in.read(buf, 0, (int) Math.min(buf.length, SPEED_PROBE_BYTES - total));
                if (r <= 0) { eof = true; break; }
                total += r;
            }
            long dt = android.os.SystemClock.elapsedRealtime() - start;
            if (total <= 0 || dt <= 0) return 0;
            if (total < MIN_SPEED_PROBE_BYTES && (!eof || total < 8 * 1024)) {
                // 既不是「分片本身比取样量小」的 EOF，也没在观测窗口内读够 → 吞吐太低
                return 0;
            }
            if (dt < MIN_SPEED_PROBE_MS) return 0;   // 观测过短，折算值不可信
            return (int) (total * 1000L / 1024L / dt);
        } catch (Throwable t) {
            LogBridge.w(TAG, "分片测速异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return 0;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /** m3u8 主机名；解析失败返回空串 */
    private static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            String h = java.net.URI.create(url.trim()).getHost();
            return h == null ? "" : h;
        } catch (Throwable t) {
            return "";
        }
    }

    /** m3u8 是否落在 {@link #PREFERRED_HOSTS} 白名单里 */
    private static boolean isPreferredHost(String url) {
        String h = hostOf(url);
        if (h.isEmpty()) return false;
        for (String p : PREFERRED_HOSTS) {
            if (h.equals(p) || h.endsWith("." + p)) return true;
        }
        return false;
    }

    /**
     * 【2026-10-13 水印规避】m3u8 是否落在「带博彩烧录水印」的 CDN 上。
     *
     * <p>命中 {@link #WATERMARK_HOST_MARKERS} 任一子串即视为带水印线路，选线时降级为兜底。
     * 大小写不敏感（hostOf 一般已小写，这里再兜一层）。</p>
     */
    private static boolean isWatermarkHost(String url) {
        String h = hostOf(url).toLowerCase(java.util.Locale.ROOT);
        if (h.isEmpty()) return false;
        for (String m : WATERMARK_HOST_MARKERS) {
            if (h.contains(m)) return true;
        }
        return false;
    }

    /** 读线路测速缓存；未命中或已过期返回 -1（0 是合法的"测过且不可达"） */
    private static int cachedSpeedKbps(String vodId, int sid) {
        String k = vodId + "-" + sid;
        long[] v = sSidSpeed.get(k);
        if (v == null) return -1;
        if (System.currentTimeMillis() - v[1] > SID_SPEED_TTL_MS) {
            sSidSpeed.remove(k);
            return -1;
        }
        return (int) v[0];
    }

    /** 写线路测速缓存（同时清理过期项，防止 Map 无界增长） */
    private static void putSpeedKbps(String vodId, int sid, int kbps) {
        long now = System.currentTimeMillis();
        if (sSidSpeed.size() > 64) {
            // 先删过期项；若全是新鲜项（removeIf 什么都没删）则整体清空 —— 保证内存有界，
            // 代价只是下次重新测速，不影响正确性。
            sSidSpeed.entrySet().removeIf(e -> now - e.getValue()[1] > SID_SPEED_TTL_MS);
            if (sSidSpeed.size() > 128) sSidSpeed.clear();
        }
        sSidSpeed.put(vodId + "-" + sid, new long[]{kbps, now});
    }

    /** 从播放页 HTML 的 {@code player_aaaa} JSON 中取 {@code url} 字段，并还原 {@code \/} */
    private static String extractPlayerUrl(String html) {
        int idx = html.indexOf("player_aaaa");
        if (idx < 0) return null;
        String marker = "\"url\":\"";
        int u = html.indexOf(marker, idx);
        if (u < 0) return null;
        int start = u + marker.length();
        int end = html.indexOf('"', start);
        if (end <= start) return null;
        String url = html.substring(start, end).replace("\\/", "/");
        return url.isEmpty() ? null : url;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int todayYmd() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private static String getText(String url) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", BASE + "/");
            try (Response resp = NetUtil.getInstance().syncGetWithHeaders(url, headers)) {
                if (resp == null || !resp.isSuccessful() || resp.body() == null) {
                    LogBridge.w(TAG, "GET 失败 code=" + (resp == null ? -1 : resp.code())
                            + " url=" + url);
                    return null;
                }
                return resp.body().string();
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET 异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }
}
