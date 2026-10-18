package com.tv.live.kvm4;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.renyibo.WebVideoResolver;
import com.tv.live.util.LogBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置源8（4kvm.top）播放地址解析器。
 *
 * <p>M3U 里存的是 {@code kvm4://<playId>}（如 {@code kvm4://ch15tbl03}），
 * 起播时解析为真实播放页面 {@code https://4kvm.top/play/<playId>}，
 * 再通过 {@link WebVideoResolver} 用 WebView 加载页面，从 performance 网络请求提取真实 m3u8 直链。
 *
 * <p><b>4kvm 技术特点</b>：使用 MSE（Media Source Extensions）+ WASM 播放器，
 * video.currentSrc 是 blob: URL，真实 m3u8 需从 performance.getEntriesByType('resource') 提取。
 * m3u8 地址有时效性（约几分钟后 404），故缓存 TTL 设为 2 分钟。
 *
 * <p><b>线程</b>：{@link #resolve(String, Context)} 内部自动切主线程创建 WebView，
 * 调用方可在后台线程直接调用，会阻塞等待结果（超时30秒）。
 *
 * <p><b>缓存</b>（参照源7 AfhgrjUrlResolver）：解析结果按 playId 缓存，带时间戳；
 * 命中且未过期直接复用；播放失败时调 {@link #invalidate(String)} 清除，下次重解析。
 */
public final class Kvm4UrlResolver {

    private static final String TAG = "Kvm4UrlResolver";

    /** 自定义协议前缀 */
    public static final String SCHEME = "kvm4://";

    /**
     * 【2026-10-18 EPG 选集】集数地址分隔符。
     * <ul>
     *   <li>{@code kvm4://<playId>} —— 整片（第 1 线路第 1 集，兼容旧 M3U）</li>
     *   <li>{@code kvm4://<playId>@<line>-<ep>-<dataid>} —— 指定集（EPG 面板点击用）</li>
     * </ul>
     */
    private static final char EP_SEP = '@';

    /** 集数锚点：{@code <a ... data-line="1" data-episode="1" dataid="13772" ...>} */
    private static final java.util.regex.Pattern EP_ANCHOR =
            java.util.regex.Pattern.compile(
                    "<a\\b[^>]*?data-line=\"(\\d+)\"[^>]*?data-episode=\"(\\d+)\"[^>]*?dataid=\"(\\d+)\"[^>]*>");

    /** 集数列表内存缓存：playId → EpgItem 列表 */
    private static final Map<String, java.util.List<com.tv.live.Channel.EpgItem>> sEpisodeCache =
            new ConcurrentHashMap<>();
    /** 集数缓存时间戳 */
    private static final Map<String, Long> sEpisodeCacheTs = new ConcurrentHashMap<>();
    /** 集数缓存有效期：30 分钟（集数变动极少） */
    private static final long EPISODE_TTL_MS = 30 * 60 * 1000L;
    /** 同一 playId 的集数抓取并发守卫 */
    private static final Map<String, Object> sEpisodeLocks = new ConcurrentHashMap<>();

    /** 4kvm 基础 URL */
    private static final String BASE_URL = "https://4kvm.top";

    /** 解析结果缓存：playId -> [真实 m3u8 URL, 缓存时间戳ms] */
    private static final Map<String, String[]> sResolveCache = new ConcurrentHashMap<>();

    /** 缓存有效期（毫秒）—— 4kvm 的 m3u8 地址有时效性，超过有效期需重新解析 */
    private static final long CACHE_TTL_MS = 2 * 60 * 1000L; // 2分钟

    /** 缓存「免检信任期」：1 分钟内直接复用，不做存活校验（起播最快路径） */
    private static final long CACHE_TRUST_MS = 60 * 1000L; // 1分钟

    /** 存活校验：连接/读取超时（短超时——校验必须"失败得快"） */
    private static final int VERIFY_CONNECT_TIMEOUT_MS = 1500;
    private static final int VERIFY_READ_TIMEOUT_MS = 1500;
    /** 存活校验：只读头 2KB 判断有无 #EXTM3U */
    private static final int VERIFY_MAX_BYTES = 2048;

    private Kvm4UrlResolver() {}

    /**
     * 判断是否为 kvm4 协议地址。
     */
    public static boolean isKvm4Url(String url) {
        return !TextUtils.isEmpty(url) && url.startsWith(SCHEME);
    }

    /**
     * 从 kvm4:// 地址中提取 playId（自动剥掉 {@code @line-ep-dataid} 集数后缀）。
     * @return playId，非 kvm4 地址返回 null
     */
    public static String playIdOf(String url) {
        if (!isKvm4Url(url)) return null;
        String rest = url.substring(SCHEME.length()).trim();
        int q = rest.indexOf('?');
        if (q > 0) rest = rest.substring(0, q);
        int at = rest.indexOf(EP_SEP);
        if (at > 0) rest = rest.substring(0, at);
        return rest.isEmpty() ? null : rest;
    }

    /**
     * 【2026-10-18 EPG 选集】从集数地址解析 {@code line/ep/dataid}。
     *
     * @return 长度 3 的数组 {@code [line, ep, dataid]}；非集数地址（整片）返回 null
     */
    public static int[] episodeOf(String url) {
        if (!isKvm4Url(url)) return null;
        String rest = url.substring(SCHEME.length()).trim();
        int q = rest.indexOf('?');
        if (q > 0) rest = rest.substring(0, q);
        int at = rest.indexOf(EP_SEP);
        if (at <= 0) return null;
        String body = rest.substring(at + 1);
        String[] p = body.split("-");
        if (p.length < 3) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 【2026-10-18 EPG 选集】拼集数地址。 */
    public static String buildEpisodeUrl(String playId, int line, int ep, int dataId) {
        return SCHEME + playId + EP_SEP + line + "-" + ep + "-" + dataId;
    }

    /**
     * 【2026-10-18 EPG 选集】从频道取出 playId（仅当频道播放地址是 kvm4:// 协议）。
     * 与 {@code AfhgrjUrlResolver.vodIdOf(Channel)} 同构，供 {@code EpgManager} 分流。
     */
    public static String playIdOfChannel(com.tv.live.Channel channel) {
        if (channel == null) return null;
        // 片单分组的 tvg-id 带 _seq 后缀（chXXX_0），不能当 playId 用；以播放地址为准
        return playIdOf(channel.getPlayUrl());
    }

    /**
     * 【2026-10-18 EPG 选集】读内存缓存（不联网）。未命中返回空列表。
     */
    public static java.util.List<com.tv.live.Channel.EpgItem> getEpisodes(String playId) {
        if (TextUtils.isEmpty(playId)) return new java.util.ArrayList<>();
        java.util.List<com.tv.live.Channel.EpgItem> list = sEpisodeCache.get(playId);
        if (list == null) return new java.util.ArrayList<>();
        Long ts = sEpisodeCacheTs.get(playId);
        if (ts != null && System.currentTimeMillis() - ts > EPISODE_TTL_MS) {
            sEpisodeCache.remove(playId);
            sEpisodeCacheTs.remove(playId);
            return new java.util.ArrayList<>();
        }
        return list;
    }

    /**
     * 【2026-10-18 EPG 选集】抓取集数列表（HTTP 直取播放页 HTML）。
     *
     * <p>4kvm 播放页是<b>服务端直出</b>，集数以
     * {@code <a href="/play/chXXX" data-line="1" data-episode="1" dataid="13772">}
     * 形式内联在 HTML 中，可直接正则提取，无需 WebView。
     *
     * <p>多线路时取「集数最多」的那一条线路（其余线路在起播失败时才考虑，保持简单）。
     *
     * @return 集数项列表（title=第N集，customPlayUrl=kvm4://&lt;playId&gt;@line-ep-dataid）
     */
    public static java.util.List<com.tv.live.Channel.EpgItem> fetchEpisodes(String playId) {
        if (TextUtils.isEmpty(playId)) return new java.util.ArrayList<>();

        java.util.List<com.tv.live.Channel.EpgItem> cached = getEpisodes(playId);
        if (!cached.isEmpty()) return cached;

        Object lock = sEpisodeLocks.get(playId);
        if (lock == null) {
            lock = new Object();
            Object prev = sEpisodeLocks.putIfAbsent(playId, lock);
            if (prev != null) lock = prev;
        }
        synchronized (lock) {
            // 双检：等锁期间可能已被别的线程抓完
            cached = getEpisodes(playId);
            if (!cached.isEmpty()) return cached;

            String html = com.tv.live.kvm4.Kvm4SourceLoader.fetchHtml(buildPlayPageUrl(playId));
            if (TextUtils.isEmpty(html)) {
                LogBridge.w(TAG, "集数抓取失败（页面为空）playId=" + playId);
                return new java.util.ArrayList<>();
            }

            // 按线路聚合：line → (ep → dataid)，取集数最多的线路
            java.util.LinkedHashMap<Integer, java.util.TreeMap<Integer, Integer>> byLine =
                    new java.util.LinkedHashMap<>();
            java.util.regex.Matcher m = EP_ANCHOR.matcher(html);
            while (m.find()) {
                int line, ep, dataId;
                try {
                    line = Integer.parseInt(m.group(1));
                    ep = Integer.parseInt(m.group(2));
                    dataId = Integer.parseInt(m.group(3));
                } catch (Throwable t) {
                    continue;
                }
                if (line <= 0 || ep <= 0) continue;
                java.util.TreeMap<Integer, Integer> eps = byLine.get(line);
                if (eps == null) {
                    eps = new java.util.TreeMap<>();
                    byLine.put(line, eps);
                }
                eps.put(ep, dataId);
            }

            if (byLine.isEmpty()) {
                LogBridge.w(TAG, "集数抓取：页面未解析到集数锚点 playId=" + playId
                        + " htmlLen=" + html.length());
                return new java.util.ArrayList<>();
            }

            // 【2026-10-18 线路】保存全部线路表，供线路面板 / 设置面板使用
            java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines =
                    new java.util.TreeMap<>();
            lines.putAll(byLine);
            sLinesCache.put(playId, lines);
            sLinesCacheTs.put(playId, System.currentTimeMillis());

            // 默认线路 = 集数最多者；用户已手动选过则沿用
            java.util.List<com.tv.live.Channel.EpgItem> items =
                    buildItemsForLine(playId, resolveSelectedLine(playId, lines));
            sEpisodeCache.put(playId, items);
            sEpisodeCacheTs.put(playId, System.currentTimeMillis());
            LogBridge.i(TAG, "集数就绪 playId=" + playId + " 共 " + items.size()
                    + " 集（当前线路 " + getSelectedLine(playId)
                    + "，页面共 " + lines.size() + " 条线路）");
            return items;
        }
    }

    // ==================== 【2026-10-18 线路】====================

    /** 全部线路表：playId → (line → (ep → dataid)) */
    private static final Map<String, java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>>>
            sLinesCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> sLinesCacheTs = new ConcurrentHashMap<>();
    /** 用户手选的线路：playId → line（0/缺省 = 自动取集数最多的线路） */
    private static final Map<String, Integer> sSelectedLine = new ConcurrentHashMap<>();
    /** 当前正在播的集号：playId → ep（切线路时用来续集） */
    private static final Map<String, Integer> sCurEp = new ConcurrentHashMap<>();
    /** 切线路后「下一次整片解析要续的集号」：playId → ep，消费一次即清 */
    private static final Map<String, Integer> sPendingResume = new ConcurrentHashMap<>();

    /**
     * 【2026-10-18 线路】解析本次要播的集：URL 自带集号优先；否则消费切线路留下的续集。
     */
    private static int[] resolveEpisode(String rawUrl, String playId) {
        int[] ep = episodeOf(rawUrl);
        if (ep != null) {
            sCurEp.put(playId, ep[1]);
            return ep;
        }
        Integer resume = sPendingResume.remove(playId);
        if (resume == null || resume <= 0) return null;
        int line = getSelectedLine(playId);
        if (line <= 0) return null;
        int dataId = dataIdOfEpisode(playId, line, resume);
        if (dataId <= 0) return null;
        LogBridge.i(TAG, "切线路后续播 playId=" + playId + " 线路" + line + " 第" + resume + "集");
        return new int[]{line, resume, dataId};
    }

    /**
     * 【2026-10-18 线路】线路号列表（升序）。未抓过集数时返回空列表。
     */
    public static java.util.List<Integer> getLineNumbers(String playId) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (TextUtils.isEmpty(playId)) return out;
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines = linesOf(playId);
        if (lines == null) return out;
        out.addAll(lines.keySet());
        return out;
    }

    /** 线路集数（用于面板上显示「N 集」）。 */
    public static int episodeCountOfLine(String playId, int line) {
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines = linesOf(playId);
        if (lines == null) return 0;
        java.util.TreeMap<Integer, Integer> eps = lines.get(line);
        return eps == null ? 0 : eps.size();
    }

    /** 当前线路号；未选择时返回集数最多的那条。 */
    public static int getSelectedLine(String playId) {
        if (TextUtils.isEmpty(playId)) return 0;
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines = linesOf(playId);
        Integer sel = sSelectedLine.get(playId);
        if (lines == null) return sel == null ? 0 : sel;
        return resolveSelectedLine(playId, lines);
    }

    /** 线路显示名：{@code 线路N}。 */
    public static String lineLabel(int line) {
        return "线路" + line;
    }

    /**
     * 【2026-10-18 线路】切换线路：记住集号并重建选集列表，调用方随后重播即可续当前集。
     *
     * @param playId 影片 playId
     * @param line   目标线路号
     * @param keepEp 需要保留的集号（0 = 不保留，从头）
     */
    public static void switchLine(String playId, int line, int keepEp) {
        if (TextUtils.isEmpty(playId) || line <= 0) return;
        sSelectedLine.put(playId, line);
        if (keepEp > 0) sPendingResume.put(playId, keepEp);
        // 重建选集项：EPG 面板下次读取即为新线路
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines = linesOf(playId);
        if (lines != null) {
            java.util.List<com.tv.live.Channel.EpgItem> items = buildItemsForLine(playId, line);
            sEpisodeCache.put(playId, items);
            sEpisodeCacheTs.put(playId, System.currentTimeMillis());
        }
        // 解析缓存按「playId@line-ep」分键，清掉本片全部旧缓存
        for (String key : new java.util.ArrayList<>(sResolveCache.keySet())) {
            if (key.startsWith(playId)) sResolveCache.remove(key);
        }
        LogBridge.i(TAG, "线路切换 playId=" + playId + " → 线路" + line
                + "，续集=" + keepEp);
    }

    /** 当前正在播的集号（由最近一次带集号的解析记录）。 */
    public static int currentEpisode(String playId) {
        Integer ep = sCurEp.get(playId);
        return ep == null ? 0 : ep;
    }

    /** 取线路表（带 TTL 校验）。 */
    private static java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> linesOf(String playId) {
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines = sLinesCache.get(playId);
        if (lines == null) return null;
        Long ts = sLinesCacheTs.get(playId);
        if (ts != null && System.currentTimeMillis() - ts > EPISODE_TTL_MS) {
            sLinesCache.remove(playId);
            sLinesCacheTs.remove(playId);
            return null;
        }
        return lines;
    }

    /** 选定线路：手选优先，否则集数最多者。 */
    private static int resolveSelectedLine(String playId,
                                           java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines) {
        Integer sel = sSelectedLine.get(playId);
        if (sel != null && lines.containsKey(sel)) return sel;
        int best = 0, bestCount = -1;
        for (java.util.Map.Entry<Integer, java.util.TreeMap<Integer, Integer>> e : lines.entrySet()) {
            if (e.getValue().size() > bestCount) {
                bestCount = e.getValue().size();
                best = e.getKey();
            }
        }
        return best;
    }

    /** 按指定线路生成选集项。 */
    private static java.util.List<com.tv.live.Channel.EpgItem> buildItemsForLine(String playId, int line) {
        java.util.List<com.tv.live.Channel.EpgItem> items = new java.util.ArrayList<>();
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines = linesOf(playId);
        if (lines == null || !lines.containsKey(line)) return items;
        java.util.TreeMap<Integer, Integer> eps = lines.get(line);
        if (eps == null) return items;
        for (java.util.Map.Entry<Integer, Integer> e : eps.entrySet()) {
            com.tv.live.Channel.EpgItem it =
                    new com.tv.live.Channel.EpgItem("今天", "", "第" + e.getKey() + "集", false);
            it.customPlayUrl = buildEpisodeUrl(playId, line, e.getKey(), e.getValue());
            items.add(it);
        }
        return items;
    }

    /** 指定线路的某集 dataid（找不到返回 0）。 */
    public static int dataIdOfEpisode(String playId, int line, int ep) {
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> lines = linesOf(playId);
        if (lines == null) return 0;
        java.util.TreeMap<Integer, Integer> eps = lines.get(line);
        if (eps == null) return 0;
        Integer id = eps.get(ep);
        return id == null ? 0 : id;
    }

    /**
     * 构建播放页面 URL。
     */
    public static String buildPlayPageUrl(String playId) {
        return BASE_URL + "/play/" + playId;
    }

    /**
     * 解析 kvm4:// 地址为真实 m3u8 直链。
     *
     * <p>缓存策略（参照源7）：
     * <ul>
     *   <li>1 分钟「信任期」内 → 直接复用，0 次网络（最快）；</li>
     *   <li>1~2 分钟「保留期」内 → 先花 1 次 HTTP 校验 m3u8 可达，通过才复用；</li>
     *   <li>超过 2 分钟 → 丢弃，走完整 WebView 解析。</li>
     * </ul>
     *
     * @param rawUrl kvm4://<playId>
     * @param context  上下文（用于 WebView 创建）
     * @return 真实 m3u8 URL；解析失败返回 null
     */
    public static String resolve(String rawUrl, Context context) {
        String playId = playIdOf(rawUrl);
        if (TextUtils.isEmpty(playId)) {
            LogBridge.w(TAG, "解析失败：无法提取 playId，url=" + rawUrl);
            return null;
        }

        final int[] ep = resolveEpisode(rawUrl, playId);
        final String qualityKey = qualityKeyOf(context);
        final String cacheKey = ((ep == null)
                ? playId : (playId + EP_SEP + ep[0] + "-" + ep[1]))
                + (qualityKey == null ? "" : ("|q" + qualityKey));

        // 命中缓存
        String[] cached = sResolveCache.get(cacheKey);
        if (cached != null && cached.length >= 2 && !TextUtils.isEmpty(cached[0])) {
            long cachedTs = parseLongSafe(cached[1]);
            long age = System.currentTimeMillis() - cachedTs;
            if (age < CACHE_TTL_MS) {
                // 信任期内直接复用
                if (age < CACHE_TRUST_MS) {
                    LogBridge.i(TAG, "命中解析缓存（信任期）key=" + cacheKey
                            + " age=" + (age / 1000) + "s");
                    return cached[0];
                }
                // 保留期：校验 m3u8 是否仍可达
                if (isM3u8Alive(cached[0])) {
                    LogBridge.i(TAG, "命中解析缓存（校验通过）key=" + cacheKey
                            + " age=" + (age / 1000) + "s");
                    return cached[0];
                }
                LogBridge.i(TAG, "缓存校验不通，重新解析 key=" + cacheKey);
                sResolveCache.remove(cacheKey);
            } else {
                // 过期
                sResolveCache.remove(cacheKey);
            }
        }

        String pageUrl = buildPlayPageUrl(playId);
        LogBridge.i(TAG, "开始解析 key=" + cacheKey + " page=" + pageUrl);

        try {
            // 【2026-10-18 EPG 选集】指定集时，先让页面点到该集再取流；
            // 只认点击后「新增」的 m3u8，避免拿到上一集残留的地址。
            String clickSelector = null;
            if (ep != null) {
                clickSelector = "a[data-line=\"" + ep[0] + "\"][data-episode=\"" + ep[1] + "\"]";
            }
            String realUrl = WebVideoResolver.get(context).resolve(pageUrl, clickSelector, qualityKey);
            if (!TextUtils.isEmpty(realUrl)) {
                sResolveCache.put(cacheKey, new String[]{realUrl, String.valueOf(System.currentTimeMillis())});
                LogBridge.i(TAG, "解析成功 key=" + cacheKey + " -> " + realUrl.length() + "字符");
                return realUrl;
            } else {
                LogBridge.w(TAG, "解析失败：WebVideoResolver 返回空 key=" + cacheKey);
                return null;
            }
        } catch (Exception e) {
            LogBridge.w(TAG, "解析异常 key=" + cacheKey + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /** 解析缓存键：整片用 playId，指定集额外带 {@code @line-ep}。 */
    private static String cacheKeyOf(String url) {
        String playId = playIdOf(url);
        if (TextUtils.isEmpty(playId)) return "";
        int[] ep = episodeOf(url);
        return (ep == null) ? playId : (playId + EP_SEP + ep[0] + "-" + ep[1]);
    }

    /**
     * 【2026-10-18 清晰度】源8 的清晰度档位 → 站点播放器认的 key。
     *
     * <p>站点用 localStorage 的 {@code artplayer_settings.quality} 决定请求哪一档
     * （{@code 4k / 1080 / 720 / 480}）。AppConfig 按源持久化，0 = 自动（不干预）。
     *
     * @return 站点 key；null = 自动
     */
    private static String qualityKeyOf(Context context) {
        try {
            int h = com.tv.live.config.AppConfig.getInstance(context).getQualityForSource(8);
            if (h <= 0) return null;
            if (h >= 2160) return "4k";
            if (h >= 1080) return "1080";
            if (h >= 720) return "720";
            return "480";
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供 UI 判断源8 当前清晰度档（0=自动）。 */
    public static int qualityHeightOf(Context context) {
        try {
            return com.tv.live.config.AppConfig.getInstance(context).getQualityForSource(8);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 供 UI 写入源8 清晰度档（0=自动）。 */
    public static void setQualityHeight(Context context, int height) {
        try {
            com.tv.live.config.AppConfig.getInstance(context).setQualityForSource(8, height);
            // 清晰度变了，旧解析结果不再适用
            sResolveCache.clear();
            LogBridge.i(TAG, "清晰度设置 源8 → " + (height <= 0 ? "自动" : height + "P"));
        } catch (Throwable t) {
            LogBridge.w(TAG, "清晰度设置失败: " + t.getMessage());
        }
    }

    /**
     * 轻量校验：缓存的 m3u8 是否仍可达。
     *
     * <p>只拉一次 m3u8 内容判断有无 {@code #EXTM3U}。
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
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Referer", BASE_URL + "/");
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
        return ok;
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

    /**
     * 清除指定 playId 的解析缓存（播放失败时调用，下次重解析）。
     */
    public static void invalidate(String playId) {
        if (!TextUtils.isEmpty(playId)) {
            sResolveCache.remove(playId);
            LogBridge.i(TAG, "已失效解析缓存 playId=" + playId);
        }
    }

    /**
     * 从 kvm4:// URL 中提取 playId 并清除其缓存。
     */
    public static void invalidateByUrl(String kvm4Url) {
        String key = cacheKeyOf(kvm4Url);
        if (!TextUtils.isEmpty(key)) invalidate(key);
    }

    /**
     * 清除所有解析缓存。
     */
    public static void invalidateAll() {
        sResolveCache.clear();
        LogBridge.i(TAG, "已清除全部解析缓存");
    }
}
