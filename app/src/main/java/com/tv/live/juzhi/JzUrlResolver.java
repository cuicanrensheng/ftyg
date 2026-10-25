package com.tv.live.juzhi;

import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.M3u8AdStripper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.tv.live.Channel;

/**
 * 橘汁 {@code juzhi://} 待解析地址 → m3u8 直链。
 *
 * <p>两步（对照原版 VideoParser.doParseVideoPlayUrl 与 DetailActivity）：
 * <ol>
 *   <li>{@code POST /api/proto/v5/drama/getDetail}（{@code id=<vodId>}）
 *       → DramaDetailBean，其中 {@code repeated DramaVideoBean videos = 29}；</li>
 *   <li>{@code POST /api/proto/v5/videoUsableUrl}
 *       （{@code vodPlayFrom=<video.source>&playUrl=<URLEncoder(video.path)>}）
 *       → ParsePlayUrlBean，{@code play_url = 1} 即真实 m3u8。</li>
 * </ol>
 *
 * <p>地址格式：
 * <ul>
 *   <li>{@code juzhi://<vodId>} —— 首集；</li>
 *   <li>{@code juzhi://<vodId>@<ep>} —— 指定集数（按 DramaVideoBean.episode 匹配）。</li>
 * </ul>
 */
public final class JzUrlResolver {

    private static final String TAG = "JzUrlResolver";

    /** 直播地址前缀（{@code juzhi://live/<source>/<path>}） */
    private static final String LIVE_PREFIX = "live/";
    /** tvg-id 前缀（M3U 里写作 juzhi:<vodId>，与 SCHEME 不同） */
    private static final String TVG_PREFIX = "juzhi:";

    /** 详情内存缓存：同一部剧连播多集时不必反复拉几百 KB */
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    private static final int CACHE_MAX = 8;

    private static final Object LOCK = new Object();
    private static final Map<Integer, CachedVideos> CACHE =
            new LinkedHashMap<Integer, CachedVideos>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, CachedVideos> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private JzUrlResolver() {
    }

    // ------------------------------------------------------------------
    // 【2026-10-23 选路口径】用户手动选线 > 测速选线 > 服务端第一条
    //   （晚场用户要求改回按测速选择：源9 同集 13 条线路实测速度差异巨大——
    //    神话蓝光(shenhua7) ~35~60KB/s、自建4K-3 ~154KB/s、自建4K-2 ~1.6MB/s，
    //    按服务端顺序取第一条会稳定选中慢线，大分片必卡，故恢复测速选线）
    // ------------------------------------------------------------------

    /** 格式校验建连超时 */
    private static final int PROBE_CONNECT_TIMEOUT_MS = 2500;
    /** 格式校验读取超时 */
    private static final int PROBE_READ_TIMEOUT_MS = 2500;
    /** 格式校验读取字节数（仅判定 #EXTM3U，不做速度测速） */
    private static final int FORMAT_CHECK_BYTES = 8192;

    /** 测速单条线路总时限（含建连；超过按已到达字节结算，防止慢线拖死整体） */
    private static final long SPEED_PROBE_DEADLINE_MS = 3000L;
    /** 测速选线总时限（所有候选并发探测的上限；超时按已完成结果择优） */
    private static final long SPEED_PICK_MAX_WAIT_MS = 12000L;
    /** 测速下载字节上限（前 64KB 足以区分快慢线） */
    private static final int SPEED_PROBE_MAX_BYTES = 64 * 1024;
    /** 测速并发上限（第三方站点：有上限并发；自有 110.42.x 不在此列） */
    private static final int SPEED_PROBE_CONCURRENCY = 6;
    /** 测速结果缓存 TTL（同一集 10 分钟内不重复测速，换集/重播秒开） */
    private static final long SPEED_PICK_TTL_MS = 10 * 60 * 1000L;
    /** 慢线路告警阈值（KB/s）：低于仅告警仍选最快的一条（全网都慢时别无选择） */
    private static final long SLOW_LINE_WARN_KBPS = 200L;

    /**
     * 【2026-10-23 手动线路选择】用户在线路面板手动选的线路，
     * 优先级高于服务端默认第一条线路。
     * key="vodId@ep" → source名称
     */
    private static final java.util.Map<String, String> sManualLineSel =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 获取某集可用线路名称列表（供线路面板显示）。
     * 返回 source 名称列表，如 ["PAN1", "qsvip", "shvip", ...]
     */
    public static java.util.List<String> lineNames(int vodId, int wantEp) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            List<Video> all = videosOf(vodId);
            if (all.isEmpty()) return names;
            List<Video> epVideos = videosOfEpisode(all, wantEp);
            for (Video v : epVideos) {
                String display = TextUtils.isEmpty(v.sourceCn)
                        ? v.source : v.source + "(" + v.sourceCn + ")";
                names.add(display);
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "lineNames 异常: " + t.getMessage());
        }
        return names;
    }

    /** 获取用户手动选择的线路索引（未选返回 -1） */
    public static int getSelectedLine(int vodId, int wantEp) {
        String key = vodId + "@" + wantEp;
        String sel = sManualLineSel.get(key);
        if (sel == null || sel.isEmpty()) return -1;
        java.util.List<String> names = lineNames(vodId, wantEp);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).startsWith(sel + "(")) return i;
            if (names.get(i).equals(sel)) return i;
        }
        return -1;
    }

    /** 用户手动选择线路（线路面板调用） */
    public static void setSelectedLine(int vodId, int wantEp, int idx) {
        java.util.List<String> names = lineNames(vodId, wantEp);
        if (idx >= 0 && idx < names.size()) {
            String source = names.get(idx).contains("(")
                    ? names.get(idx).substring(0, names.get(idx).indexOf('('))
                    : names.get(idx);
            sManualLineSel.put(vodId + "@" + wantEp, source);
            LogBridge.i(TAG, "用户手动选线路 [" + source + "] vodId=" + vodId + " ep=" + wantEp);
        }
    }

    /** 获取用户手动选择的线路 source 名称（无则 null） */
    public static String getManualLine(int vodId, int wantEp) {
        return sManualLineSel.get(vodId + "@" + wantEp);
    }

    /** 清除手动线路选择（回到服务端第一条） */
    public static void clearManualLine(int vodId, int wantEp) {
        sManualLineSel.remove(vodId + "@" + wantEp);
    }

    /**
     * 校验 m3u8 是否可直接播放：下载少量头部字节，判断是否含 #EXTM3U（兼容十进制编码）。
     *
     * <p>只做可用性校验（测速在 {@link #measureSpeed}）。用于手动选线 / 测速全失败后的
     * 服务端顺序降级路径，跳过非标准 m3u8（如 iqiyi 网页 URL），避免黑屏。
     */
    private static boolean isPlayableM3u8(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String sample = M3u8AdStripper.downloadContent(
                    url, FORMAT_CHECK_BYTES, "https://www.shenhua7.vip/");
            if (sample == null || sample.isEmpty()) return false;
            sample = M3u8AdStripper.decodeDecimalM3u8(sample);
            return sample.contains("#EXTM3U");
        } catch (Throwable t) {
            LogBridge.w(TAG, "格式校验异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * 取某集全部线路（同一 episode 的所有 Video，按 source 去重）。
     * wantEp>0：取指定集数；wantEp==0：取首集（episode 最小或 0 的线路）。
     */
    private static List<Video> videosOfEpisode(List<Video> all, int wantEp) {
        List<Video> out = new ArrayList<>();
        java.util.Set<String> seenSources = new java.util.HashSet<>();

        if (wantEp > 0) {
            // 指定集数：取 episode==wantEp 的所有线路
            for (Video v : all) {
                if (v.episode != wantEp) continue;
                if (seenSources.add(v.source)) out.add(v);
            }
        } else {
            // 首集：优先取 episode==0 的线路；若无则取 episode 最小的那一集
            List<Video> firstEp = new ArrayList<>();
            for (Video v : all) {
                if (v.episode == 0) {
                    if (seenSources.add(v.source)) firstEp.add(v);
                }
            }
            if (firstEp.isEmpty()) {
                // 找 episode 最小的
                int minEp = Integer.MAX_VALUE;
                for (Video v : all) {
                    if (v.episode > 0 && v.episode < minEp) minEp = v.episode;
                }
                if (minEp == Integer.MAX_VALUE) minEp = 0;
                for (Video v : all) {
                    if (v.episode != minEp) continue;
                    if (seenSources.add(v.source)) out.add(v);
                }
            } else {
                out = firstEp;
            }
        }
        return out;
    }

    /** 调用 videoUsableUrl 取 m3u8（从 Video 的 source+path 换直链），并暂存播放头 */
    private static String fetchPlayUrl(Video v) {
        PlayResult r = fetchPlayUrlEx(v);
        if (r == null) return null;
        if (!r.headers.isEmpty()) {
            sPlayHeaders = r.headers;
            LogBridge.i(TAG, "videoUsableUrl 下发播放头 " + r.headers.size() + " 项: " + r.headers.keySet());
        }
        return r.url;
    }

    /** videoUsableUrl 结果：m3u8 直链 + 服务端下发的播放请求头 */
    private static final class PlayResult {
        final String url;
        final java.util.Map<String, String> headers;
        PlayResult(String url, java.util.Map<String, String> headers) {
            this.url = url;
            this.headers = (headers == null || headers.isEmpty())
                    ? java.util.Collections.emptyMap() : headers;
        }
    }

    /**
     * 调用 videoUsableUrl 取 m3u8 + 播放头。<b>不写 sPlayHeaders</b>——测速探针要批量
     * 探测全部线路，只有最终选中的那条才允许把播放头写进 holder（见 selectBestPlayUrl ②）。
     *
     * @param fast true 走短超时客户端（测速探针用，死线路 3s 内失败）
     */
    private static PlayResult fetchPlayUrlEx(Video v, boolean fast) {
        if (TextUtils.isEmpty(v.path) || TextUtils.isEmpty(v.source)) return null;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vodPlayFrom", v.source);
        params.put("playUrl", urlEncode(v.path));
        byte[] data = fast
                ? JzApi.postFast(JzProtocol.pathPlayUrl(), params)
                : JzApi.post(JzProtocol.pathPlayUrl(), params);
        if (data == null || data.length == 0) return null;
        return new PlayResult(JzProtocol.str(data, 1), JzProtocol.mapStr(data, 6));
    }

    private static PlayResult fetchPlayUrlEx(Video v) {
        return fetchPlayUrlEx(v, false);
    }

    /**
     * 线路选择：【用户口径】手动选线 > 测速选线 > 服务端第一条。
     *
     * <p>优先级：
     * <ol>
     *   <li>用户手动选线（线路面板选的）优先；</li>
     *   <li>【2026-10-23 晚】测速选线：并发探测全部候选线路（videoUsableUrl → m3u8 →
     *       首个分片限长下载），取实测最快的一条。源9 同集 13 条线路速度差 40 倍
     *       （35KB/s ~ 1.6MB/s），不测速会稳定选中慢线导致大分片卡死；</li>
     *   <li>测速全失败时按服务端顺序降级（跳过不可播的，避免选到 iqiyi 网页 URL 导致黑屏）。</li>
     * </ol>
     *
     * @return m3u8 直链；全部不可播返回 null
     */
    private static String selectBestPlayUrl(int vodId, int wantEp, List<Video> epVideos) {
        if (epVideos == null || epVideos.isEmpty()) return null;

        // ① 用户手动选线优先
        String manualSource = getManualLine(vodId, wantEp);
        if (manualSource != null && !manualSource.isEmpty()) {
            for (Video v : epVideos) {
                if (!v.source.equals(manualSource)) continue;
                String m3u8 = fetchPlayUrl(v);
                if (m3u8 != null && isPlayableM3u8(m3u8)) {
                    LogBridge.i(TAG, "使用手动选线[" + manualSource + "(" + v.sourceCn
                            + ")] vodId=" + vodId + " ep=" + wantEp);
                    return m3u8;
                }
                LogBridge.w(TAG, "手动选线[" + manualSource + "] 不可播，回退测速选线");
                break;
            }
        }

        // ② 【2026-10-23 晚 用户口径】测速选线：并发探测全部候选线路，取最快
        PlayResult picked = pickFastestLine(vodId, wantEp, epVideos);
        if (picked != null && !TextUtils.isEmpty(picked.url)) {
            if (!picked.headers.isEmpty()) {
                sPlayHeaders = picked.headers;
            }
            LogBridge.i(TAG, "使用测速最快线路 vodId=" + vodId + " ep=" + wantEp
                    + " → " + picked.url.substring(0, Math.min(80, picked.url.length())));
            return picked.url;
        }

        // ③ 测速全失败 → 服务端第一条顺序降级（跳过不可播的，避免选到 iqiyi 网页 URL 导致黑屏）
        for (int i = 0; i < epVideos.size(); i++) {
            Video v = epVideos.get(i);
            String m3u8 = fetchPlayUrl(v);
            if (TextUtils.isEmpty(m3u8)) {
                LogBridge.w(TAG, "线路[" + v.source + "] 直链获取失败，试下一条");
                continue;
            }
            if (!isPlayableM3u8(m3u8)) {
                LogBridge.w(TAG, "线路[" + v.source + "] 非标准 m3u8，试下一条");
                continue;
            }
            LogBridge.i(TAG, "使用服务端第" + (i + 1) + "条线路[" + v.source + "("
                    + v.sourceCn + ")] vodId=" + vodId + " ep=" + wantEp);
            return m3u8;
        }
        LogBridge.w(TAG, "全部线路不可播 vodId=" + vodId + " ep=" + wantEp);
        return null;
    }

    // ------------------------------------------------------------------
    // 【2026-10-23 晚】测速选线
    // ------------------------------------------------------------------

    /** 测速选线缓存：key="vodId" → [选中线路 source 名, 时间戳]（按片不按集，换集不重复测速） */
    private static final java.util.Map<String, Object[]> sSpeedPickCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 单条线路测速结果：速度（KB/s，-1 不可达）+ 可达时的 m3u8/播放头 */
    private static final class ProbeOutcome {
        final long speed;
        final PlayResult play;
        ProbeOutcome(long speed, PlayResult play) {
            this.speed = speed;
            this.play = play;
        }
    }

    /**
     * 并发测速全部候选线路，返回最快线路的 {@link PlayResult}（含 m3u8 与播放头）；
     * 只有一条候选或全部不可达时返回 null（调用方回退服务端顺序）。
     *
     * <p>缓存按 <b>vodId</b>（不按集）：同一部片换集/重播直接用已选线路名匹配当前集线路表，
     * 只发一次 videoUsableUrl，连续看剧不重复测速；服务端线路表变化（名字不存在）时自动作废重测。
     * 总时限 {@link #SPEED_PICK_MAX_WAIT_MS}：超时按已完成结果择优，保证起播延迟有上界。
     */
    private static PlayResult pickFastestLine(int vodId, int wantEp, List<Video> epVideos) {
        if (epVideos == null || epVideos.size() <= 1) return null;
        String key = String.valueOf(vodId);

        Object[] cached = sSpeedPickCache.get(key);
        if (cached != null && System.currentTimeMillis() - (Long) cached[1] < SPEED_PICK_TTL_MS) {
            String src = (String) cached[0];
            for (Video v : epVideos) {
                if (src != null && src.equals(v.source)) {
                    LogBridge.i(TAG, "测速选线命中缓存 → [" + src + "] vodId=" + vodId + " ep=" + wantEp);
                    PlayResult r = fetchPlayUrlEx(v);
                    if (r != null && !TextUtils.isEmpty(r.url)) return r;
                    LogBridge.w(TAG, "缓存线路[" + src + "]当前集不可用，重新测速");
                    break;
                }
            }
            sSpeedPickCache.remove(key);
        }

        LogBridge.i(TAG, "测速选线开始：候选 " + epVideos.size() + " 条 vodId=" + vodId + " ep=" + wantEp);
        final ProbeOutcome[] outcomes = new ProbeOutcome[epVideos.size()];
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(SPEED_PROBE_CONCURRENCY, epVideos.size()), r -> {
            Thread t = new Thread(r, "Jz-SpeedProbe");
            t.setDaemon(true);
            return t;
        });
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < epVideos.size(); i++) {
            final int idx = i;
            final Video v = epVideos.get(i);
            futures.add(pool.submit(() -> {
                ProbeOutcome o = probeLine(v);
                outcomes[idx] = o;
                String name = TextUtils.isEmpty(v.sourceCn) ? v.source : v.source + "(" + v.sourceCn + ")";
                if (o.speed > 0) {
                    LogBridge.i(TAG, "测速线路#" + (idx + 1) + " [" + name + "] " + o.speed + "KB/s");
                } else {
                    LogBridge.w(TAG, "测速线路#" + (idx + 1) + " [" + name + "] 不可达");
                }
            }));
        }
        // 总时限：超时按已完成结果择优（未完成的探针继续在后台跑完，不影响本次选择）
        long deadline = System.currentTimeMillis() + SPEED_PICK_MAX_WAIT_MS;
        for (java.util.concurrent.Future<?> f : futures) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                LogBridge.w(TAG, "测速选线超过总时限 " + SPEED_PICK_MAX_WAIT_MS + "ms，按已完成结果择优");
                break;
            }
            try {
                f.get(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdown();

        int best = -1;
        long bestSpeed = -1;
        for (int i = 0; i < outcomes.length; i++) {
            if (outcomes[i] != null && outcomes[i].speed > bestSpeed) {
                bestSpeed = outcomes[i].speed;
                best = i;
            }
        }
        if (best < 0 || outcomes[best].play == null) {
            LogBridge.w(TAG, "测速选线：全部线路不可达，回退服务端顺序");
            return null;
        }
        if (bestSpeed < SLOW_LINE_WARN_KBPS) {
            LogBridge.w(TAG, "测速选线：最快线路仅 " + bestSpeed + "KB/s（低于 "
                    + SLOW_LINE_WARN_KBPS + "KB/s 告警线），全网偏慢");
        }
        sSpeedPickCache.put(key, new Object[]{epVideos.get(best).source, System.currentTimeMillis()});
        LogBridge.i(TAG, "测速选线完成 → 第" + (best + 1) + "条 " + bestSpeed + "KB/s");
        return outcomes[best].play;
    }

    /**
     * 测速单条线路：videoUsableUrl 换 m3u8 → 取首个分片 → Range 限长下载计时。
     *
     * @return 速度 KB/s；任一步失败返回 speed=-1
     */
    private static ProbeOutcome probeLine(Video v) {
        PlayResult r = fetchPlayUrlEx(v, true);   // 短超时客户端：死线路快速失败
        if (r == null || TextUtils.isEmpty(r.url)) return new ProbeOutcome(-1, null);
        String seg = firstSegmentUrl(r.url, r.headers);
        if (TextUtils.isEmpty(seg)) return new ProbeOutcome(-1, null);
        return new ProbeOutcome(measureSpeed(seg, r.headers), r);
    }

    /**
     * 限长测速：Range 取前 {@link #SPEED_PROBE_MAX_BYTES} 字节，{@link #SPEED_PROBE_DEADLINE_MS}
     * 总时限内按已到达字节结算速度（慢线也能得到可比较的速度值，不会被一刀切成不可达）。
     */
    private static long measureSpeed(String segUrl, java.util.Map<String, String> headers) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(segUrl).openConnection();
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            String ua = headerValue(headers, "user-agent");
            conn.setRequestProperty("User-Agent",
                    ua != null && !ua.isEmpty() ? ua : "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
            String referer = headerValue(headers, "referer");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            conn.setRequestProperty("Range", "bytes=0-" + (SPEED_PROBE_MAX_BYTES - 1));
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return -1;
            java.io.InputStream is = conn.getInputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            long start = System.currentTimeMillis();
            int read;
            while (total < SPEED_PROBE_MAX_BYTES) {
                if (System.currentTimeMillis() - start > SPEED_PROBE_DEADLINE_MS) break;
                read = is.read(buf);
                if (read < 0) break;
                total += read;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed <= 0) elapsed = 1;
            if (total <= 0) return -1;
            return (total / 1024) * 1000 / elapsed;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 取 m3u8 的首个分片直链（测速用）；master 清单先取第一个子流。失败返回 null。 */
    private static String firstSegmentUrl(String m3u8Url, java.util.Map<String, String> headers) {
        String content = downloadSmall(m3u8Url, headers);
        if (content == null || content.isEmpty()) return null;
        if (content.contains("#EXT-X-STREAM-INF")) {
            for (String line : content.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String variant = resolveUrl(m3u8Url, t);
                if (variant == null) return null;
                String sub = downloadSmall(variant, headers);
                if (sub != null && !sub.isEmpty()) {
                    return firstMediaLine(sub, variant);
                }
                return null;   // 只试第一个子流
            }
            return null;
        }
        return firstMediaLine(content, m3u8Url);
    }

    /** 取清单里第一个非注释行（分片地址），相对地址基于 baseUrl 绝对化 */
    private static String firstMediaLine(String content, String baseUrl) {
        for (String line : content.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("http://") || t.startsWith("https://")) return t;
            return resolveUrl(baseUrl, t);
        }
        return null;
    }

    /** 小文件下载（m3u8/子流），带防盗链表与十进制解码；失败返回 null */
    private static String downloadSmall(String url, java.util.Map<String, String> headers) {
        String c = M3u8AdStripper.downloadContent(url, -1,
                headerValue(headers, "referer"), headerValue(headers, "user-agent"));
        if (c == null || c.isEmpty()) return null;
        return M3u8AdStripper.decodeDecimalM3u8(c);
    }

    /** 大小写不敏感取头值 */
    private static String headerValue(java.util.Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) return null;
        for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /** 相对地址基于 base 绝对化 */
    private static String resolveUrl(String base, String rel) {
        try {
            return new java.net.URL(new java.net.URL(base), rel).toString();
        } catch (Exception e) {
            return rel.startsWith("http") ? rel : null;
        }
    }

    public static boolean isJzUrl(String url) {
        return url != null && url.startsWith(JzProtocol.SCHEME);
    }

    /** 是否为直播频道地址（{@code juzhi://live/<source>/<path>}） */
    public static boolean isLiveUrl(String url) {
        return url != null && url.startsWith(JzProtocol.SCHEME + LIVE_PREFIX);
    }

    /** {@code juzhi://<vodId>[@<ep>]} → vodId；直播地址与非本协议返回 null */
    public static String vodIdOf(String url) {
        if (!isJzUrl(url)) return null;
        if (isLiveUrl(url)) return null;   // 直播不是点播，不能进点播板块/点播控制栏
        String rest = url.substring(JzProtocol.SCHEME.length()).trim();
        int cut = rest.indexOf('@');
        if (cut >= 0) rest = rest.substring(0, cut);
        cut = rest.indexOf('?');
        if (cut >= 0) rest = rest.substring(0, cut);
        return rest.isEmpty() ? null : rest;
    }

    /** 集数；无 {@code @<ep>} 返回 0（表示首集） */
    public static int episodeOf(String url) {
        if (!isJzUrl(url)) return 0;
        String rest = url.substring(JzProtocol.SCHEME.length()).trim();
        int at = rest.indexOf('@');
        if (at < 0) return 0;
        String ep = rest.substring(at + 1).trim();
        int q = ep.indexOf('?');
        if (q >= 0) ep = ep.substring(0, q);
        try {
            return Integer.parseInt(ep);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 解析为 m3u8 直链。
     *
     * @param url {@code juzhi://<vodId>[@<ep>]}
     * @return m3u8；失败返回 null（调用方切线路/重试）
     */
    public static String resolve(String url) {
        if (isLiveUrl(url)) return resolveLive(url);

        // 【2026-10-23 修复5】清空上条线路的播放头，防止换线/换片后旧 UA/Referer 污染新请求
        sPlayHeaders = java.util.Collections.emptyMap();

        String idStr = vodIdOf(url);
        if (TextUtils.isEmpty(idStr)) {
            LogBridge.w(TAG, "resolve: vodId 为空 url=" + url);
            return null;
        }
        int vodId;
        try {
            vodId = Integer.parseInt(idStr);
        } catch (Throwable t) {
            LogBridge.w(TAG, "resolve: vodId 非数字 url=" + url);
            return null;
        }
        int wantEp = episodeOf(url);

        List<Video> videos = videosOf(vodId);
        if (videos.isEmpty()) {
            LogBridge.w(TAG, "resolve: 无可用播放源 vodId=" + vodId);
            return null;
        }

        // 【2026-10-23 源9】同一集取全部线路，按服务端顺序取第一条可用线路
        List<Video> epVideos = videosOfEpisode(videos, wantEp);
        if (epVideos.isEmpty()) {
            LogBridge.w(TAG, "resolve: 未找到匹配集 vodId=" + vodId + " ep=" + wantEp);
            return null;
        }
        String playUrl = selectBestPlayUrl(vodId, wantEp, epVideos);
        if (TextUtils.isEmpty(playUrl)) {
            LogBridge.w(TAG, "resolve: 无可用 m3u8 vodId=" + vodId + " ep=" + wantEp);
            return null;
        }
        LogBridge.i(TAG, "resolve: m3u8 → " + playUrl);
        return playUrl;
    }

    /** 取该片全部集数（10 分钟内存缓存） */
    public static List<Video> videosOf(int vodId) {
        synchronized (LOCK) {
            CachedVideos c = CACHE.get(vodId);
            if (c != null && System.currentTimeMillis() - c.at < CACHE_TTL_MS) {
                return c.videos;
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", String.valueOf(vodId));
        byte[] data = JzApi.post(JzProtocol.pathDetail(), params);
        if (data == null || data.length == 0) {
            LogBridge.w(TAG, "videosOf: getDetail 无数据 vodId=" + vodId);
            return new ArrayList<>();
        }

        List<Video> videos = new ArrayList<>();
        for (byte[] raw : JzProtocol.messages(data, 29)) {   // DramaDetailBean.videos
            Video v = new Video();
            v.id = (int) JzProtocol.num(raw, 1, 0);
            v.title = JzProtocol.str(raw, 2);
            v.path = JzProtocol.str(raw, 4);
            v.source = JzProtocol.str(raw, 9);
            v.sourceCn = JzProtocol.str(raw, 10);
            v.episode = (int) JzProtocol.num(raw, 13, 0);
            if (!TextUtils.isEmpty(v.path) && !TextUtils.isEmpty(v.source)) {
                videos.add(v);
            }
        }
        synchronized (LOCK) {
            CACHE.put(vodId, new CachedVideos(videos, System.currentTimeMillis()));
        }
        LogBridge.i(TAG, "videosOf: vodId=" + vodId + " data=" + data.length
                + "B 可播集=" + videos.size());
        return videos;
    }

    /**
     * 直播频道解析：{@code juzhi://live/<source>/<path>} → m3u8。
     *
     * <p>直播频道信息在列表接口里已自带 {@code source} 与 {@code path}，
     * 不需要 getDetail，直接走 videoUsableUrl 换取真实地址。
     */
    private static String resolveLive(String url) {
        String rest = url.substring((JzProtocol.SCHEME + LIVE_PREFIX).length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            LogBridge.w(TAG, "resolveLive: 地址格式错误 " + url);
            return null;
        }
        String source = rest.substring(0, slash);
        String path = rest.substring(slash + 1);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vodPlayFrom", source);
        params.put("playUrl", urlEncode(path));
        byte[] data = JzApi.post(JzProtocol.pathPlayUrl(), params);
        if (data == null || data.length == 0) {
            LogBridge.w(TAG, "resolveLive: 无数据 source=" + source + " path=" + path);
            return null;
        }
        String playUrl = JzProtocol.str(data, 1);
        if (TextUtils.isEmpty(playUrl)) {
            LogBridge.w(TAG, "resolveLive: play_url 为空 msg=" + JzProtocol.str(data, 9));
            return null;
        }
        // 【2026-10-23 修复5】直播线路同样下发防盗链播放头（ParsePlayUrlBean.headers=6）
        Map<String, String> liveHdrs = JzProtocol.mapStr(data, 6);
        if (!liveHdrs.isEmpty()) {
            sPlayHeaders = liveHdrs;
            LogBridge.i(TAG, "resolveLive 下发播放头 " + liveHdrs.size() + " 项: " + liveHdrs.keySet());
        }
        LogBridge.i(TAG, "resolveLive: " + source + "/" + path + " → " + playUrl);
        return playUrl;
    }

    private static Video pick(List<Video> videos, int wantEp) {
        if (wantEp > 0) {
            for (Video v : videos) {
                if (v.episode == wantEp) return v;
            }
            for (Video v : videos) {
                if (String.valueOf(wantEp).equals(v.title)) return v;
            }
        }
        return videos.get(0);
    }

    // ==================== EPG 集数（供 EpgManager 接入） ====================

    /** EPG 单飞：同一部片同时只允许一个预取任务 */
    private static final Map<String, Object> sFetching = new ConcurrentHashMap<>();
    /** 抓取失败冷却：vodIdStr → 失败时刻，避免网格反复重试 */
    private static final Map<String, Long> sFailAt = new ConcurrentHashMap<>();
    private static final long EPG_FAIL_COOLDOWN_MS = 5L * 60L * 1000L;
    /** 用户正在查看的影片 vodIdStr（频道详情页已打开），EPG 预取闸门放行条件之一 */
    private static volatile String sForegroundVodId;

    /**
     * 【2026-10-23 修复5】最近一次 videoUsableUrl 下发的播放请求头（防盗链 UA/Referer）。
     * 每次 {@link #resolve(String)} 入口清空、成功后由 fetchPlayUrl/resolveLive 覆写，
     * 避免上一条线路的头污染下一条。由 TVPlayerManager 合并进播放器请求头。
     */
    private static volatile Map<String, String> sPlayHeaders = java.util.Collections.emptyMap();

    /** 取当前线路的播放请求头（服务端未下发返回空 Map，调用方按空处理即可） */
    public static Map<String, String> playHeaders() {
        return sPlayHeaders;
    }

    /**
     * 【2026-10-23 修复5】清空播放请求头。非源9 起播时由 TVPlayerManager 调用，
     * 防止上一条源9 线路的 UA/Referer 污染其它源的 CDN 请求。
     */
    public static void clearPlayHeaders() {
        sPlayHeaders = java.util.Collections.emptyMap();
    }

    /** 从频道取点播 vodId（{@code tvg-id="juzhi:<vodId>"}）；非本源/直播频道返回 null */
    public static String vodIdOf(Channel ch) {
        if (ch == null) return null;
        String cid = ch.getChannelId();
        if (TextUtils.isEmpty(cid) || !cid.startsWith(TVG_PREFIX)) return null;
        // tvg-id="juzhi:<vodId>" 前缀只有 6 字符（juzhi:），
        // 不能用 SCHEME（juzhi://，8 字符）的长度截取，否则 vodId 被砍掉前 2 位
        String rest = cid.substring(TVG_PREFIX.length()).trim();
        if (rest.startsWith(LIVE_PREFIX)) return null;  // 直播不进 EPG 集数
        return rest.isEmpty() ? null : rest;
    }

    public static void setForegroundVodId(String vodId) {
        sForegroundVodId = TextUtils.isEmpty(vodId) ? null : vodId;
    }

    public static boolean isForeground(String vodId) {
        return !TextUtils.isEmpty(vodId) && vodId.equals(sForegroundVodId);
    }

    /** 占位成功返回 true（调用方才发起抓取）；已在抓取中返回 false */
    public static boolean beginFetch(String vodId) {
        if (TextUtils.isEmpty(vodId)) return false;
        return sFetching.putIfAbsent(vodId, new Object()) == null;
    }

    /** 释放占位 */
    public static void endFetch(String vodId) {
        if (!TextUtils.isEmpty(vodId)) sFetching.remove(vodId);
    }

    /**
     * 取集数列表：只读 10 分钟内存缓存，不联网。
     * 未就绪返回空，由 {@link #fetchEpisodes} 预热。
     */
    public static List<Channel.EpgItem> getEpisodes(String vodIdStr) {
        if (TextUtils.isEmpty(vodIdStr)) return new ArrayList<>();
        int vodId;
        try { vodId = Integer.parseInt(vodIdStr); } catch (Throwable t) { return new ArrayList<>(); }
        synchronized (LOCK) {
            CachedVideos c = CACHE.get(vodId);
            if (c == null) return new ArrayList<>();
            if (System.currentTimeMillis() - c.at >= CACHE_TTL_MS) return new ArrayList<>();
            return buildItems(vodIdStr, c.videos);
        }
    }

    /** 联网抓一次并返回（供 EpgManager 后台预热） */
    public static List<Channel.EpgItem> fetchEpisodes(String vodIdStr) {
        if (TextUtils.isEmpty(vodIdStr)) return new ArrayList<>();
        int vodId;
        try { vodId = Integer.parseInt(vodIdStr); } catch (Throwable t) { return new ArrayList<>(); }
        // 清除内存缓存强制重抓
        synchronized (LOCK) { CACHE.remove(vodId); }
        List<Video> videos = videosOf(vodId);
        if (videos.isEmpty()) {
            sFailAt.put(vodIdStr, System.currentTimeMillis());
            return new ArrayList<>();
        }
        return buildItems(vodIdStr, videos);
    }

    private static List<Channel.EpgItem> buildItems(String vodIdStr, List<Video> videos) {
        // 按 episode 去重 + 排序：同一集可能有多条线路，只保留首次出现的
        java.util.Set<Integer> seen = new TreeSet<>();
        List<Channel.EpgItem> out = new ArrayList<>();
        for (Video v : videos) {
            int ep = v.episode > 0 ? v.episode : 0;
            if (!seen.add(ep)) continue;
            String label = TextUtils.isEmpty(v.title)
                    ? ("第" + ep + "集") : v.title;
            Channel.EpgItem it = new Channel.EpgItem("今天", "", label, false);
            it.customPlayUrl = JzProtocol.SCHEME + vodIdStr + "@" + ep;
            out.add(it);
        }
        // 无 episode 字段的条目（网站可能只给标题不给集号）靠后追加，按原始顺序
        for (int i = 0; i < videos.size(); i++) {
            Video v = videos.get(i);
            if (v.episode > 0) continue;
            String label = TextUtils.isEmpty(v.title)
                    ? ("第" + (i + 1) + "集") : v.title;
            Channel.EpgItem it = new Channel.EpgItem("今天", "", label, false);
            it.customPlayUrl = JzProtocol.SCHEME + vodIdStr + "@" + (i + 1);
            out.add(it);
        }
        return out;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    /** 单集（DramaVideoBean 精简视图） */
    public static final class Video {
        public int id;
        public String title = "";
        public String path = "";
        public String source = "";
        public String sourceCn = "";
        public int episode;
    }

    private static final class CachedVideos {
        final List<Video> videos;
        final long at;

        CachedVideos(List<Video> videos, long at) {
            this.videos = videos;
            this.at = at;
        }
    }
}
