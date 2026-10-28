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

    /**
     * 【2026-10-26 网页线路】WebView 解析网页播放页需要 Application Context。
     * 由 MyApplication.onCreate 注入；未注入时网页线路只走轻量正则提取。
     */
    private static volatile android.content.Context sAppContext;

    public static void initContext(android.content.Context ctx) {
        if (ctx != null && sAppContext == null) {
            sAppContext = ctx.getApplicationContext();
        }
    }

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
     * 【2026-10-27 线路面板】测速速率低于此值（KB/s）视为「线路过慢不可用」。
     * 背景：QQ 等线路服务端防盗链对纯直连限速到 ~1KB/s（能连但卡死黑屏），
     *   measureSpeed 在 3s 窗口内仍能测到 speed>0，原逻辑只在 speed<=0 才判死，
     *   于是「限速黑屏线」被当成「慢但可用」永远显示可用。
     *   引入阈值后：测速 speed 低于此值的线路同样记入失效黑名单，
     *   在 OTT/设置面板标记「已失效」并置灰，且自动选线不再选它。
     *   经验值：正常 HLS 首段探测在该参数下约 15~21KB/s，10 阈值只剔除明显不可用的慢线。
     */
    private static final long MIN_USABLE_KBPS = 10L;

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

    /**
     * 与 {@link #lineNames} 一一对应：每条线路是否已失效（判死且未过期）。
     * 供 OTT 线路面板 / 设置面板「频道线路选择」标记「已失效」并置灰禁用，
     * 避免用户选到测速失败 / 播放失败过的失效线路。
     */
    public static java.util.List<Boolean> lineDeadFlags(int vodId, int wantEp) {
        java.util.List<Boolean> flags = new java.util.ArrayList<>();
        try {
            List<Video> all = videosOf(vodId);
            if (all.isEmpty()) return flags;
            List<Video> epVideos = videosOfEpisode(all, wantEp);
            for (Video v : epVideos) {
                flags.add(isLineDead(vodId, v.source));
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "lineDeadFlags 异常: " + t.getMessage());
        }
        return flags;
    }

    /** 获取用户手动选择的线路索引（未选返回 -1） */
    public static int getSelectedLine(int vodId, int wantEp) {
        String sel = getManualLine(vodId, wantEp);
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
        String key = vodId + "@" + wantEp;
        String sel = sManualLineSel.get(key);
        if (sel != null && !sel.isEmpty()) return sel;
        // 【2026-10-27 修复】线路面板的 ep 取自**频道 M3U 地址**（juzhi://<vodId>，无 @ep → ep=0），
        //   而取流用的是 EPG 切集后的 juzhi://<vodId>@<ep>（ep=1/2/…）—— 两者 key 永不相交，
        //   导致源9 的「手动选线」被静默忽略（用户选了 qq 也照样走测速选线）。
        //   线路选择本质是「按 source 选线路」，与集数无关 → 回退到该 vodId 下任意 ep 的手动选择。
        String prefix = vodId + "@";
        for (java.util.Map.Entry<String, String> e : sManualLineSel.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith(prefix)
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue();
            }
        }
        return null;
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
        // 【2026-10-26 直链媒体】非清单类直链（B 站 durl 的 mp4、m4s 等，扩展名正常）没有 #EXTM3U 头，
        //   做清单校验只会白下载一次并必然判负 → 直接视为可播。
        if (!looksLikePlaylist(url) && looksLikeDirectMedia(url)) {
            return true;
        }
        try {
            // 【2026-10-27】改用字节级探针：除了 m3u8 文本，还按「真内容」识别二进制容器。
            //   源9 存在伪装扩展名（mp4 伪装成 .image、m3u8 分片伪装成 .png），按扩展名会漏判。
            byte[] sample = M3u8AdStripper.downloadBytes(
                    url, FORMAT_CHECK_BYTES, "https://www.shenhua7.vip/", null);
            if (sample == null || sample.length == 0) return false;
            // ① 二进制容器魔数（mp4/TS/FLV）→ 直链媒体，可播
            if (isMediaContainer(sample)) return true;
            // ② 文本清单（含十进制混淆解码）
            String text = new String(sample, "UTF-8");
            text = M3u8AdStripper.decodeDecimalM3u8(text);
            return text.contains("#EXTM3U");
        } catch (Throwable t) {
            LogBridge.w(TAG, "格式校验异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * 【2026-10-27】按真内容（魔数）识别二进制媒体容器，不依赖扩展名。
     * 覆盖：mp4(isom/ftyp)、MPEG-TS(0x47 同步)、FLV。
     */
    private static boolean isMediaContainer(byte[] b) {
        if (b == null || b.length < 4) return false;
        // mp4 / mov / m4s：第 4~8 字节为 "ftyp"（前 4 字节是 box size）
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return true;
        }
        // TS：首字节 0x47（同步字节），且（若足够长）第 188 字节也是 0x47
        if (b[0] == 0x47) {
            if (b.length < 188) return true;
            if (b[188] == 0x47) return true;
        }
        // FLV：前 3 字节 "FLV"
        if (b[0] == 'F' && b[1] == 'L' && b[2] == 'V') return true;
        return false;
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
        /** 【2026-10-26】该直链对应的线路 source 名（失败时可据此判死线路并换线） */
        final String source;

        PlayResult(String url, java.util.Map<String, String> headers) {
            this(url, headers, "");
        }

        PlayResult(String url, java.util.Map<String, String> headers, String source) {
            this.url = url;
            this.headers = (headers == null || headers.isEmpty())
                    ? java.util.Collections.emptyMap() : headers;
            this.source = source;
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
        return new PlayResult(JzProtocol.str(data, 1), JzProtocol.mapStr(data, 6), v.source);
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
                // 【2026-10-27 修复】手动选线同样要处理「网页播放页」线路。
                //   之前只取服务端 videoUsableUrl 的原样返回值：qq 线路服务端回显的是
                //   v.qq.com/x/cover/...html 网页 → isPlayableM3u8 判 false → 明明手动选了 qq
                //   也被静默回退到测速选线（用户体感=「手动选线没用」）。
                //   现在与 probeLine 同口径：网页型先走 JS 插件解析出真实 m3u8 再校验。
                if (m3u8 != null && looksLikeHtmlPage(m3u8)) {
                    LogBridge.d(TAG, "手动选线[" + manualSource + "] 是网页播放页，先 web 解析: " + brief(m3u8));
                    PlayResult wr = resolveWebLineResult(m3u8, null);
                    if (wr != null && !TextUtils.isEmpty(wr.url)) {
                        m3u8 = wr.url;
                        if (!wr.headers.isEmpty()) {
                            sPlayHeaders = wr.headers;
                        }
                    } else {

                        break;
                    }
                }
                if (m3u8 != null && isPlayableM3u8(m3u8)) {
                    LogBridge.d(TAG, "使用手动选线[" + manualSource + "(" + v.sourceCn
                            + ")] vodId=" + vodId + " ep=" + wantEp);
                    return m3u8;
                }

                break;
            }
        }

        // ② 【2026-10-23 晚 用户口径】测速选线：并发探测全部候选线路，取最快
        //   【2026-10-26】跳过已判死的线路，避免反复选到同一条失效地址
        List<Video> alive = new java.util.ArrayList<>();
        for (Video v : epVideos) {
            if (isLineDead(vodId, v.source)) {

                continue;
            }
            alive.add(v);
        }
        if (alive.isEmpty()) {
            // 全部线路都判死 → 清空黑名单重来一次（可能只是源临时抽风）
            LogBridge.w(TAG, "全部线路均已判死，清空黑名单重新选线 vodId=" + vodId);
            clearDeadLines(vodId);
            alive = epVideos;
        }
        PlayResult picked = pickFastestLine(vodId, wantEp, alive);
        if (picked != null && !TextUtils.isEmpty(picked.url)) {
            if (!picked.headers.isEmpty()) {
                sPlayHeaders = picked.headers;
            }
            rememberPickedLine(vodId, picked.source);
            LogBridge.i(TAG, "使用测速最快线路 vodId=" + vodId + " ep=" + wantEp
                    + " → " + picked.url.substring(0, Math.min(80, picked.url.length())));
            return picked.url;
        }

        // ③ 测速全失败 → 服务端第一条顺序降级（跳过不可播/刚判死的过慢线路，避免选到黑屏线）
        for (int i = 0; i < alive.size(); i++) {
            Video v = alive.get(i);
            if (isLineDead(vodId, v.source)) continue;  // 跳过本轮刚判死的限速线
            String m3u8 = fetchPlayUrl(v);
            if (TextUtils.isEmpty(m3u8)) {
                LogBridge.d(TAG, "线路[" + v.source + "] 直链获取失败，试下一条");
                continue;
            }
            // 【2026-10-26 网页线路】下发的是 HTML 播放页 → 走 web 解析拿真实 m3u8
            //   【2026-10-26 收窄】改用 looksLikeHtmlPage：只有真正的网页（.html 或已知视频站）
            //   才进 web 解析；图片 URL / 裸 vid 等非网页直接判不可达，不再浪费 30s WebView 兜底。
            if (looksLikeHtmlPage(m3u8)) {

                PlayResult webPr = resolveWebLineResult(m3u8, null);
                if (webPr == null || TextUtils.isEmpty(webPr.url)) {

                    continue;
                }
                if (!webPr.headers.isEmpty()) sPlayHeaders = webPr.headers;
                m3u8 = webPr.url;
            }
            if (!isPlayableM3u8(m3u8)) {

                continue;
            }


            rememberPickedLine(vodId, v.source);
            return m3u8;
        }
        LogBridge.w(TAG, "全部线路不可播 vodId=" + vodId + " ep=" + wantEp);
        return null;
    }

    /** 网页播放页里内嵌 m3u8 的匹配（很多播放页把直链写在 HTML/JS 变量里）。 */
    private static final java.util.regex.Pattern M3U8_IN_WEB_PAGE =
            java.util.regex.Pattern.compile(
                    "https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*");

    /**
     * 网页线路走咪咕 JS 插件解析的整体超时。
     * 【2026-10-26 下调 15→10】插件依赖的上游服务大量失效（api.okjx.cc:666 实测不可达），
     * 死线路要快速失败才好换下一条；JS 桥内部单次调用已降到 6s。
     */
    private static final long JS_PARSE_TIMEOUT_MS = 10000L;

    /** 网页轻量探测的下载上限（网页动辄几百 KB，全量下载会吃掉整个解析预算）。 */
    private static final int WEB_PAGE_PROBE_BYTES = 64 * 1024;

    /** 限长小文件下载（超量即截断），带防盗链表与十进制解码。 */
    private static String downloadSmallLimited(String url, java.util.Map<String, String> headers, int maxBytes) {
        String c = M3u8AdStripper.downloadContent(url, maxBytes,
                headerValue(headers, "referer"), headerValue(headers, "user-agent"));
        if (c == null || c.isEmpty()) return null;
        return M3u8AdStripper.decodeDecimalM3u8(c);
    }

    /**
     * 【2026-10-26 网页线路解析】网页播放页 → 真实 m3u8，三级兜底：
     * <ol>
     *   <li>轻量正则：页面 HTML 里直接内嵌 m3u8（毫秒级，多数情况命中）；</li>
     *   <li>咪咕 JS 插件：{@code UrlSourceParser.parseSync} 按站点路由到 assets/js 里的
     *       24 个插件（含 mgtv_vod.js / iqy / qq / letv / sohu / bili…），覆盖主流视频站；</li>
     *   <li>WebView 解析：{@code WebVideoResolver} 加载页面等播放器起流取 video.currentSrc
     *       （最慢，30s 级，仅前两级都失败时用）。</li>
     * </ol>
     * 背景：源9 的 videoUsableUrl 对部分源下发的是 HTML 播放页
     * （实测 https://www.mgtv.com/b/825667/24500844.html），这类地址直接交给播放器必然播不出。
     */
    private static String resolveWebLineUrl(String pageUrl, java.util.Map<String, String> headers) {
        PlayResult pr = resolveWebLineResult(pageUrl, headers);
        return (pr == null) ? null : pr.url;
    }

    /**
     * 【2026-10-26】网页线路解析（带回播放头）。
     *
     * <p>播放头（Referer / User-Agent）必须与地址一起传下去：
     * JS 插件回传的 {@code url@@origin=..@@referer=..@@user-agent=..} 里，
     * B 站 durl 直链缺 Referer 会被判 403 —— 既测不出速度，播放也起不来。
     */
    private static PlayResult resolveWebLineResult(String pageUrl, java.util.Map<String, String> headers) {
        if (TextUtils.isEmpty(pageUrl)) return null;
        final java.util.Map<String, String> outHeaders = new java.util.LinkedHashMap<>();
        if (headers != null) outHeaders.putAll(headers);
        // 【2026-10-27 方案A】QQ 线路：解析阶段预取游客 Cookie + 强制 Referer=v.qq.com，
        //   注入播放头，使后续测速/播放请求带齐浏览器会话身份（解除盗链层限速的关键）。
        if (pageUrl.contains("v.qq.com")) {
            prefetchQqCookie(pageUrl);
            String qqCookie = getQqCookie();
            outHeaders.put("Referer", "https://v.qq.com/");
            if (qqCookie != null && !qqCookie.isEmpty()) {
                outHeaders.put("Cookie", qqCookie);
                LogBridge.i(TAG, "QQ 线路注入游客 Cookie 到播放头");
            } else {
                LogBridge.w(TAG, "QQ 线路未取到游客 Cookie，仅注入 Referer");
            }
        }
        // ① 轻量正则（限长下载：网页动辄几百 KB，全量下载会吃掉整个解析预算）
        try {
            String page = downloadSmallLimited(pageUrl, outHeaders, WEB_PAGE_PROBE_BYTES);
            if (page != null && !page.isEmpty()) {
                java.util.regex.Matcher m = M3U8_IN_WEB_PAGE.matcher(page);
                while (m.find()) {
                    String cand = m.group(0);
                    if (isPlayableM3u8(cand)) {

                        return new PlayResult(cand, outHeaders, null);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        android.content.Context ctx = sAppContext;
        if (ctx == null) {
            LogBridge.d(TAG, "网页线路：未注入 Context，跳过 JS 插件/WebView 解析: " + brief(pageUrl));
            return null;
        }
        // ② 咪咕 JS 插件
        try {
            String raw = com.tv.live.migu.js.UrlSourceParser.parseSync(ctx, pageUrl, JS_PARSE_TIMEOUT_MS);
            if (!TextUtils.isEmpty(raw)) {
                // 【2026-10-26】插件回传格式为「地址@@origin=…@@referer=…@@user-agent=…」，
                //   必须拆出播放头，否则带着 @@尾巴去请求必然失败。
                Object[] ph = splitPlayHeaders(raw);
                String parsed = (String) ph[0];
                @SuppressWarnings("unchecked")
                java.util.Map<String, String> hdrs = (java.util.Map<String, String>) ph[1];
                if (!TextUtils.isEmpty(parsed) && looksLikeM3u8(parsed)) {
                    if (!hdrs.isEmpty()) {
                        sPlayHeaders = hdrs;
                        outHeaders.putAll(hdrs);
                    } else {
                    }
                    return new PlayResult(parsed, outHeaders, null);
                }
                LogBridge.i(TAG, "网页线路：JS 插件返回不可播地址: " + brief(raw));
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "网页线路：JS 插件解析异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
        // ③ WebView 解析（最慢兜底）
        try {
            String parsed = com.tv.live.renyibo.WebVideoResolver.get(ctx).resolve(pageUrl);
            if (!TextUtils.isEmpty(parsed) && looksLikeM3u8(parsed)) {
                LogBridge.d(TAG, "网页线路：WebView 解析成功: " + brief(parsed));
                return new PlayResult(parsed, outHeaders, null);
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "网页线路：WebView 解析异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * 地址是否像 m3u8 清单（路径或 query 里带 .m3u8/.m3u 扩展名）。
     *
     * <p><b>【2026-10-26 扩展】</b>网页线路（爱奇艺/B站/腾讯/芒果）的 JS 插件解析出的
     * 未必是 HLS —— B 站 {@code bili_vod.js} 给的是 <b>mp4 直链</b>（含鉴权参数）。
     * 因此这里放宽为「可播媒体地址」：m3u8/m3u 或 mp4/m4s/mov/flv 都认。
     * mp4 直链带 Range 请求即可边下边播，ExoPlayer 的渐进式源能处理。
     */
    private static boolean looksLikeM3u8(String url) {
        if (url == null || url.isEmpty()) return false;
        String p = url.toLowerCase(java.util.Locale.ROOT);
        return p.contains(".m3u8") || p.contains(".m3u")
                || p.contains(".mp4") || p.contains(".m4s")
                || p.contains(".mov") || p.contains(".flv");
    }

    /**
     * 【2026-10-26 收窄】地址是否像「需要解析的网页播放页」。
     *
     * <p>之前用 {@code !looksLikeM3u8(url)} 判定网页，会把 <b>任何非媒体地址</b>
     * （图片 CDN 的 {@code *.image}、源下发的裸 vid {@code v025aag...}/{@code JD-...} 等）
     * 都当成网页送去 JS 插件 / WebView（30s 级）解析，结果一律 {@code no js parser}，
     * 既浪费 30s 兜底解析，又污染「web 解析失败」日志，掩盖了真正能播的网页线路。
     *
     * <p>正判：必须是 http(s) 页面，且要么以 {@code .html/.htm/.shtml} 结尾，
     * 要么落在已知视频站 host（与 {@code yt_source_root.js} 路由表对齐）。
     * 其余（图片、裸 vid、异常串）一律不当网页，直接判不可达，不再做无意义解析。
     */
    private static boolean looksLikeHtmlPage(String url) {
        if (url == null || url.isEmpty()) return false;
        String p = url.toLowerCase(java.util.Locale.ROOT);
        if (!p.startsWith("http://") && !p.startsWith("https://")) return false;
        if (p.contains(".html") || p.contains(".htm") || p.contains(".shtml")) return true;
        return p.contains(".iqiyi.com") || p.contains(".mgtv.com")
                || p.contains("v.qq.com") || p.contains("youku.com")
                || p.contains(".le.com") || p.contains("tv.sohu.com")
                || p.contains(".pptv.com") || p.contains(".bilibili.com")
                || p.contains(".fun.tv") || p.contains(".hanju.cc")
                || p.contains("weibo.com") || p.contains("miaopai.com")
                || p.contains("baofeng.com") || p.contains("krcom.cn")
                || p.contains("onln.cn") || p.contains("cntv.cn")
                || p.contains("cctv.com");
    }

    /**
     * 【2026-10-26】地址是否像 HLS 清单（只看路径/参数里的 .m3u8/.m3u）。
     *
     * <p>与 {@link #looksLikeM3u8} 的区别：后者是「可播地址」判定（含 mp4 等直链），
     * 这里用于区分「要不要去清单里找分片」。
     */
    private static boolean looksLikePlaylist(String url) {
        if (url == null || url.isEmpty()) return false;
        String p = url.toLowerCase(java.util.Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.endsWith(".m3u8") || p.endsWith(".m3u");
    }

    /**
     * 【2026-10-26】地址是否像直链媒体文件（mp4/m4s/flv/mov）。
     *
     * <p>这类地址（B 站 durl、腾讯直链等）没有清单结构，不能用「找分片」的方式测速，
     * 直接对自身做 Range 探测即可。
     */
    private static boolean looksLikeDirectMedia(String url) {
        if (url == null || url.isEmpty()) return false;
        String p = url.toLowerCase(java.util.Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.endsWith(".mp4") || p.endsWith(".m4s")
                || p.endsWith(".mov") || p.endsWith(".flv");
    }

    /**
     * 【2026-10-26】剥掉 JS 插件附在地址尾部的清晰度标记（原版约定 {@code url#流畅}）。
     *
     * <p>原版 JS 插件统一用 {@code url#清晰度名} 表达档位（爱奇艺/优酷/芒果都有）。
     * 注意 URL 本身也可能有 fragment（{@code #}），所以只在「{@code #} 之前是 http(s) 开头」
     * 且「{@code #} 之后不含 {@code /}、{@code ?}」时才当作清晰度标记剥离。
     */
    private static String stripQualityTag(String url) {
        if (url == null) return "";
        String u = url.trim();
        int h = u.indexOf('#');
        if (h <= 0) return u;
        String head = u.substring(0, h);
        String tail = u.substring(h + 1);
        if (!head.startsWith("http://") && !head.startsWith("https://")) return u;
        if (tail.isEmpty() || tail.contains("/") || tail.contains("?") || tail.contains("&")) return u;
        return head;
    }

    /**
     * 【2026-10-26】解析 JS 插件返回的「地址@@播放头」格式。
     *
     * <p>原始畅享 App 的 JS 插件统一用这个格式回传播放所需的请求头：
     * <pre>
     *   https://.../x.mp4@@origin=https://www.bilibili.com@@referer=https://...@@user-agent=Mozilla/5.0
     * </pre>
     * 本移植版此前没有实现该解析 → 插件返回的地址被当成普通 URL 直接下发，
     * 带着 {@code @@origin=...} 尾巴请求，必然失败（B 站/芒果/腾讯线路全挂）。
     *
     * @param raw 插件原始返回值
     * @return [真实地址, 播放头 map]；无 {@code @@} 时第二个元素为空 map
     */
    private static Object[] splitPlayHeaders(String raw) {
        java.util.Map<String, String> hdrs = new java.util.LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return new Object[]{"", hdrs};
        int idx = raw.indexOf("@@");
        if (idx < 0) return new Object[]{stripQualityTag(raw), hdrs};
        String url = stripQualityTag(raw.substring(0, idx).trim());
        String tail = raw.substring(idx + 2);
        for (String part : tail.split("@@")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            String v = part.substring(eq + 1).trim();
            if (v.isEmpty()) continue;
            if ("referer".equals(k) || "origin".equals(k)) {
                // referer/origin 两者都写进 Referer 头（CDN 防盗链通常看 Referer）
                hdrs.put("Referer", v);
            } else if ("user-agent".equals(k)) {
                hdrs.put("User-Agent", v);
            } else if ("cookie".equals(k)) {
                hdrs.put("Cookie", v);
            }
        }
        return new Object[]{url, hdrs};
    }

    /** 日志用短地址 */
    private static String brief(String url) {
        if (url == null) return "(null)";
        return url.length() <= 80 ? url : url.substring(0, 80) + "…";
    }

    // ------------------------------------------------------------------
    // 【2026-10-23 晚】测速选线
    // ------------------------------------------------------------------

    /** 测速选线缓存：key="vodId" → [选中线路 source 名, 时间戳]（按片不按集，换集不重复测速） */
    private static final java.util.Map<String, Object[]> sSpeedPickCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【2026-10-26 失效线路黑名单】key = vodId + "|" + source → 判死时间戳。
     *
     * <p><b>背景（修"一直重复播放"）</b>：源9 同一部片有 5~13 条线路，服务端把失效线路
     * 原样下发（如实测的 {@code PAN1(NS蓝光-1)} 指向已挂的 {@code 43.248.3.46:7788}）。
     * 旧逻辑只做「重新解析取新直链」，而重新解析会命中测速缓存的<b>同一条</b>线路
     * （{@code sSpeedPickCache} 按 vodId 缓存 source 名），于是拿到的还是那个死地址，
     * 反复起播-失败-重解析，形成无限循环（实测 40 秒内起播 4 次仍在原地打转）。
     *
     * <p><b>修法</b>：某条线路被判死（非视频内容 / 重定向到占位图）时把它记入黑名单，
     * 后续选线直接跳过它 → 自动换到该片的其他线路。这是与源5/源8/源2「失效自动换线」
     * 同一思路，只是判死信息由播放层回传给解析层。
     *
     * <p>黑名单 10 分钟过期，避免源临时抽风后永久失去该线路。
     */
    private static final java.util.Map<String, Long> sDeadLines =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 失效线路黑名单有效期（毫秒） */
    private static final long DEAD_LINE_TTL_MS = 10 * 60 * 1000L;

    /** 记录某条线路已判死（播放层回调用） */
    public static void markLineDead(int vodId, String source) {
        if (TextUtils.isEmpty(source)) return;
        String key = vodId + "|" + source;
        sDeadLines.put(key, System.currentTimeMillis());
        // 同时作废该片的测速缓存，否则下次选线还会命中这条死线路
        sSpeedPickCache.remove(String.valueOf(vodId));

    }

    /** 该线路是否已判死（且未过期）。供 OTT/设置线路面板标记并置灰失效线路。 */
    public static boolean isLineDead(int vodId, String source) {
        if (TextUtils.isEmpty(source)) return false;
        Long ts = sDeadLines.get(vodId + "|" + source);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > DEAD_LINE_TTL_MS) {
            sDeadLines.remove(vodId + "|" + source);
            return false;
        }
        return true;
    }

    /** 清理某片全部判死记录（播放就绪时调用，新一轮稳定） */
    public static void clearDeadLines(int vodId) {
        String prefix = vodId + "|";
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = sDeadLines.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) it.remove();
        }
    }

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
            // 【2026-10-26】缓存线路若已判死（播放失败过）→ 不可复用，
            //   否则会一次又一次拿回同一条死地址 → 起播失败 → 再来一次（一直重复播放）。
            if (isLineDead(vodId, src)) {
                LogBridge.d(TAG, "测速选线缓存命中但线路[" + src + "]已判死，重新选线 vodId=" + vodId);
                sSpeedPickCache.remove(key);
            } else {
                for (Video v : epVideos) {
                    if (src != null && src.equals(v.source)) {

                        PlayResult r = fetchPlayUrlEx(v);
                        if (r != null && !TextUtils.isEmpty(r.url)) return r;

                        break;
                    }
                }
                sSpeedPickCache.remove(key);
            }
        }

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

                } else {

                }
            }));
        }
        // 总时限：超时按已完成结果择优（未完成的探针继续在后台跑完，不影响本次选择）
        long deadline = System.currentTimeMillis() + SPEED_PICK_MAX_WAIT_MS;
        for (java.util.concurrent.Future<?> f : futures) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {

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
            // 只从「速率达标」的线路里挑最快：否则会把限速黑屏线当成"最快"选去播放
            if (outcomes[i] != null && outcomes[i].speed >= MIN_USABLE_KBPS
                    && outcomes[i].speed > bestSpeed) {
                bestSpeed = outcomes[i].speed;
                best = i;
            }
        }
        // 【2026-10-27 线路面板】测速不可达 / 过慢的线路记入失效黑名单，
        // 使「测速失败 / 限速黑屏」也能在 OTT/设置面板被标记「已失效」并置灰。
        // 必须放在 sSpeedPickCache.put 之前：否则下面 markLineDead 的缓存失效会清掉刚选的最快线路。
        for (int i = 0; i < outcomes.length; i++) {
            if (i < epVideos.size() && outcomes[i] != null
                    && outcomes[i].speed < MIN_USABLE_KBPS) {



                markLineDead(vodId, epVideos.get(i).source);
            }
        }

        if (best < 0 || outcomes[best].play == null) {
            LogBridge.i(TAG, "测速选线：全部线路不可达，回退服务端顺序");
            return null;
        }
        if (bestSpeed < SLOW_LINE_WARN_KBPS) {
            LogBridge.i(TAG, "测速选线：最快线路仅 " + bestSpeed + "KB/s（低于 "
                    + SLOW_LINE_WARN_KBPS + "KB/s 告警线），全网偏慢");
        }
        sSpeedPickCache.put(key, new Object[]{epVideos.get(best).source, System.currentTimeMillis()});
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
        // 【2026-10-26 网页线路】下发的是 HTML 播放页 → 先 web 解析出真实 m3u8，再拿分片测速。
        //   否则会把网页里随便一行 URL 当分片（下载又快）→ 网页被选成"最快线路"，落地必播不出。
        //   【2026-10-26 收窄】改用 looksLikeHtmlPage：非网页（图片/裸 vid）直接判不可达。
        if (looksLikeHtmlPage(r.url)) {

            PlayResult wr = resolveWebLineResult(r.url, r.headers);
            if (wr == null || TextUtils.isEmpty(wr.url)) {

                return new ProbeOutcome(-1, null);
            }
            // 【2026-10-26】用带播放头的结果（B 站直链缺 Referer 会 403）
            r = new PlayResult(wr.url, wr.headers, v.source);
        }
        // 【2026-10-26 直链媒体】B 站 durl 这类直链没有清单结构，
        //   去「找分片」必然拿到空 → 会被误判成不可达，白丢一条能播的线路。
        //   直接对直链自身做 Range 探测测速。
        String seg;
        if (looksLikePlaylist(r.url)) {
            seg = firstSegmentUrl(r.url, r.headers);
            if (TextUtils.isEmpty(seg)) return new ProbeOutcome(-1, null);
        } else {
            seg = r.url;
        }
        return new ProbeOutcome(measureSpeed(seg, r.headers), r);
    }

    // ===================== 【2026-10-27 方案A】QQ 线路游客会话身份 =====================
    // QQ 分片直连限速本质是「缺浏览器会话身份（Cookie/Referer）被当盗链/异常流量」。
    // 解析阶段预取 v.qq.com 游客 Cookie 并强制 Referer，使测速/播放请求带齐身份，
    // 验证限速是否仅为盗链识别层。若有效 → QQ 线路复活；若仍限速 → 确属签名层，再上 WebView。
    private static final long QQ_COOKIE_TTL_MS = 5 * 60 * 1000L;
    private static volatile long sQqCookieTs = 0L;
    private static volatile String sQqCookie = null;

    private static String getQqCookie() {
        if (sQqCookie != null
                && System.currentTimeMillis() - sQqCookieTs < QQ_COOKIE_TTL_MS) {
            return sQqCookie;
        }
        return null;
    }

    /** 预取 v.qq.com 游客 Cookie（只取响应头 Set-Cookie，不下 body），缓存 5 分钟复用 */
    private static void prefetchQqCookie(String pageUrl) {
        if (getQqCookie() != null) return;  // 缓存有效，跳过
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(pageUrl).openConnection(java.net.Proxy.NO_PROXY);
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://v.qq.com/");
            java.util.Map<String, java.util.List<String>> hf = conn.getHeaderFields();
            StringBuilder sb = new StringBuilder();
            if (hf != null) {
                java.util.List<String> sc = hf.get("Set-Cookie");
                if (sc == null) sc = hf.get("set-cookie");
                if (sc != null) {
                    for (String c : sc) {
                        if (c == null) continue;
                        String pair = c.split(";", 2)[0];
                        if (pair != null && !pair.trim().isEmpty()) {
                            if (sb.length() > 0) sb.append("; ");
                            sb.append(pair.trim());
                        }
                    }
                }
            }
            conn.disconnect();
            if (sb.length() > 0) {
                sQqCookie = sb.toString();
                sQqCookieTs = System.currentTimeMillis();
            } else {
                LogBridge.i(TAG, "QQ 游客 Cookie 预取为空（播放页未下发 Set-Cookie）");
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "QQ 游客 Cookie 预取异常: " + t.getMessage());
        }
    }

    /**
     * 限长测速：Range 取前 {@link #SPEED_PROBE_MAX_BYTES} 字节，{@link #SPEED_PROBE_DEADLINE_MS}
     * 总时限内按已到达字节结算速度（慢线也能得到可比较的速度值，不会被一刀切成不可达）。
     */
    private static long measureSpeed(String segUrl, java.util.Map<String, String> headers) {
        java.net.HttpURLConnection conn = null;
        try {
            // 【2026-10-25 NO_PROXY】全工程统一口径：凡播放/清单类请求一律绕开系统代理。
            //   设备若配了 WiFi 代理，openConnection() 默认走 ProxySelector → 测速全部超时，
            //   表现为「源9 线路全被判定不可用」。与虎牙 6 处保持同一口径。
            conn = (java.net.HttpURLConnection) new java.net.URL(segUrl)
                    .openConnection(java.net.Proxy.NO_PROXY);
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
            // 【2026-10-27 方案A】QQ 线路：带上游客 Cookie（来自播放页预取），解除盗链层限速
            String cookie = headerValue(headers, "cookie");
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
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
        // 【2026-10-26 网页伪装成线路】videoUsableUrl 对部分源下发的是 **HTML 播放页**
        //   （实测 https://www.mgtv.com/b/825667/24500844.html）。旧逻辑会走
        //   firstMediaLine 把页面里任意一行 URL 当成分片，测速下载这种小网页又快 →
        //   网页被选成"最快线路"，落地后播放器拿到的是网页，自然一直播不出来。
        //   这里先校验是不是真清单（含 #EXTM3U），不是就判该线路不可用。
        if (!content.contains("#EXTM3U")) {
            LogBridge.i(TAG, "线路内容不是 m3u8 清单（疑似网页播放页），判该线路不可用: "
                    + m3u8Url.substring(0, Math.min(80, m3u8Url.length())));
            return null;
        }
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
            LogBridge.i(TAG, "resolve: vodId 为空 url=" + url);
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
            LogBridge.i(TAG, "resolve: 未找到匹配集 vodId=" + vodId + " ep=" + wantEp);
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
            LogBridge.i(TAG, "resolveLive: play_url 为空 msg=" + JzProtocol.str(data, 9));
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

    /**
     * 【2026-10-26】本次解析选中的线路 source 名（vodId → source）。
     * 播放失败时由播放层取回并 {@link #markLineDead}，实现"失败即换线"。
     */
    private static final java.util.Map<String, String> sPickedLine =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录本次选中线路 */
    private static void rememberPickedLine(int vodId, String source) {
        if (TextUtils.isEmpty(source)) return;
        sPickedLine.put(String.valueOf(vodId), source);
    }

    /** 取回某片当前选中的线路 source（无记录返回空串） */
    public static String pickedLineOf(int vodId) {
        String s = sPickedLine.get(String.valueOf(vodId));
        return s == null ? "" : s;
    }

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
