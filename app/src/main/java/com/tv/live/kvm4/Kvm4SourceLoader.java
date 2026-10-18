package com.tv.live.kvm4;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.M3uDiskCache;
import com.tv.live.util.NetUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

/**
 * 内置源8（4kvm.top 影视分类）频道表加载器。
 *
 * <p>抓取 4kvm.top 的分类页面，把每部影片拼成一个「频道」：
 * M3U 里地址写作自定义协议 {@code kvm4://<playId>}，真正取流在起播那一刻由
 * {@link Kvm4UrlResolver} 惰性完成（WebView 解析播放页取真实 m3u8）。
 *
 * <p>分组与入口：
 * <pre>
 *   首页   /          (约60部)
 *   电影   /movie     (约47部)
 *   电视剧 /tv        (约61部)
 *   动漫   /anime     (约26部)
 *   片单   /playlists (精选片单)
 * </pre>
 *
 * <p>抓取方式（2026-10-18 起改为纯 HTTP，与内置源7 一致）：
 * <pre>
 *   首页   /          (服务端直出 HTML，正则提卡片)
 *   电影   /movie     (同上)
 *   电视剧 /tv        (同上)
 *   动漫   /anime     (同上)
 *   片单   /api/playlists/list → /api/playlists/detail?id=（JSON 接口）
 * </pre>
 *
 * <p>早期版本用 WebView 加载 SPA 再抓链接，会被页面骨架里的全站「最新更新」区块
 * 抢先命中，导致 24 个片单全部返回同一份 7 部（168 条只有 7 个唯一 tvg-id）。
 * 现分类页走 SSR HTML、片单走官方 JSON 接口，不再依赖 WebView 渲染时序。
 *
 * <p><b>起播解析仍用 WebView</b>：播放地址由站点前端 WASM（{@code build_play_url}）
 * 现算签名，没有可复现的明文 HTTP 接口，见 {@link Kvm4UrlResolver}。
 *
 * <p>失败返回 {@code null}，由 {@code LiveSourceLoader} 走既有 fallback 链。
 */
public final class Kvm4SourceLoader {

    private static final String TAG = "Kvm4SourceLoader";

    private static final String BASE = "https://4kvm.top";

    /** 移动端 UA */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * 5个分类分组：分组名 + 分类页面URL
     */
    private static final String[][] GROUPS = {
            {"首页",   BASE + "/"},
            {"电影",   BASE + "/movie"},
            {"电视剧", BASE + "/tv"},
            {"动漫",   BASE + "/anime"},
            {"片单",   BASE + "/playlists"},
    };

    /** 分组抓取之间的最小间隔（毫秒），串行限速 */
    private static final long GROUP_INTERVAL_MS = 200L;

    /**
     * 【2026-10-18 提速】并行度上限：同时最多 3 个请求在飞。
     * 冷启 4 分类页 + 24 片单详情由 ~17s 降到 ~6s；不要再往上加，
     * 同一站点短时间打太多并发容易被限速/502。
     */
    private static final int FETCH_PARALLEL = 3;

    /** 磁盘缓存文件 */
    private static final String CACHE_FILE = "kvm4_source.m3u";
    /** 缓存有效期：12 小时（与源5/源6 对齐） */
    private static final long CACHE_TTL_MS = 12L * 60L * 60L * 1000L;

    /** 缓存落在 files/（与源3/5/6/7 一致），带 SWR + 失败冷却 + 原子写 */
    private static final M3uDiskCache sCache = new M3uDiskCache(
            TAG, "源8(4kvm)", CACHE_FILE, CACHE_TTL_MS, 5L * 60L * 1000L,
            new M3uDiskCache.Fetcher() {
                @Override
                public String fetch() {
                    return fetchAllGroups();
                }
            });

    /** 整体抓取截止时间（毫秒） */
    private static final long FETCH_DEADLINE_MS = 120000L; // 2分钟，24个片单逐个加载需要较长时间

    /** 单次请求超时：连接 3s / 读取 8s（分类页 HTML 约 350KB，读超时给足） */
    private static final long FETCH_CONNECT_MS = 3000L;
    private static final long FETCH_READ_MS = 8000L;
    /** 单次调用总超时：超过即放弃该页，避免「慢吐字节」拖死整组 */
    private static final long FETCH_CALL_MS = 12000L;

    /** 4kvm 专用 OkHttp 客户端（共享连接池 + gzip），懒构造见 {@link #http()} */
    private static volatile okhttp3.OkHttpClient sHttp;

    /** 抓取失败冷却期：5 分钟 */
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;
    private static volatile long sLastFailAtMs = 0;

    // ==================== 【2026-10-18】纯 HTTP 抓取用到的常量 ====================

    /** 片单目录接口（站点前端同款参数：按创建时间倒序，一页 24 个） */
    private static final String PLAYLIST_LIST_PATH =
            "/api/playlists/list?page=1&per_page=24&order_by=create_time&order=desc";

    /** 片单详情每页条数（多数片单条目数 < 30，一次取完；站点默认 24） */
    private static final int PLAYLIST_PER_PAGE = 48;

    /**
     * 影片卡片：{@code <a href="/play/chXXXX" data-vod="..." class="block"> ... </a>}
     * 分类页为服务端直出，故可直接正则提取（无需 WebView）。
     */
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "<a href=\"/play/(ch[0-9a-zA-Z]+)\"[^>]*class=\"block\">([\\s\\S]*?)</a>");

    /** 卡片封面 alt 即片名，最可靠 */
    private static final Pattern CARD_TITLE_PATTERN = Pattern.compile("alt=\"([^\"]*)\"");
    /** 兜底片名：{@code <h3>片名</h3>} */
    private static final Pattern CARD_H3_PATTERN =
            Pattern.compile("<h3[^>]*>([\\s\\S]*?)</h3>");
    /** 卡片海报（懒加载真实地址） */
    private static final Pattern CARD_POSTER_PATTERN = Pattern.compile("data-src=\"([^\"]*)\"");

    /** 从 {@code /play/chXXXX} 里取 playId */
    private static final Pattern PLAY_ID_IN_URL = Pattern.compile("/play/(ch[0-9a-zA-Z]+)");

    /** 后台刷新 in-flight 守卫 */
    private static final AtomicBoolean sRefreshing = new AtomicBoolean(false);

    /** 海报缓存：playId -> posterUrl */
    private static final Map<String, String> sPosterCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Kvm4SourceLoader() {}

    /**
     * 加载所有分组，返回 M3U 格式字符串。
     *
     * <p><b>【2026-10-18 对齐其他源】</b>改用公共 {@link M3uDiskCache}：
     * 缓存落在 {@code files/kvm4_source.m3u}（原来错放在 {@code cache/}，会被系统
     * 与 App 自身缓存巡检回收 → 频繁冷启重抓 17s）；语义与源3/5/6/7 完全一致
     * （缓存优先 + stale-while-revalidate + TTL + 失败冷却 + 原子写 + 后台刷新单飞）。
     */
    public static String loadAllLiveAsM3u(Context context) {
        return sCache.load(context);
    }

    /**
     * 串行抓取所有分组，生成 M3U。
     *
     * <p><b>【2026-10-18 改纯 HTTP】</b>原先用 WebView（{@code WebVideoResolver}）加载页面再抓
     * {@code a[href*="/play/"]}，有三处硬伤：
     * <ol>
     *   <li>SPA 渲染时序不可控 —— 首次轮询就命中页面骨架里的全站「最新更新」7 部，
     *       24 个片单全部返回同一份，168 条只有 7 个唯一 tvg-id；</li>
     *   <li>24 个片单逐个 WebView 加载，耗时约 90 秒，还常撞 120 秒截止时间；</li>
     *   <li>依赖 WebView 实例，冷启动/低内存机型上偶发解析失败。</li>
     * </ol>
     *
     * <p>现在与内置源7 一致，走纯 HTTP：
     * <ul>
     *   <li><b>分类页</b>（首页/电影/电视剧/动漫）：站点是<b>服务端直出</b>，
     *       直接 GET HTML 后用正则提 {@code <a href="/play/chXXX" ... class="block">} 卡片，
     *       标题取 {@code alt}、海报取 {@code data-src}；</li>
     *   <li><b>片单</b>：走站点自己的 JSON 接口
     *       {@code /api/playlists/list} → {@code /api/playlists/detail?id=<id>}，
     *       只取接口真实内容，天然不含「最新更新」推荐块。</li>
     * </ul>
     *
     * <p>注意：<b>起播解析仍走 WebView</b>（见 {@link Kvm4UrlResolver}）——站点的播放地址由
     * 前端 WASM（{@code nbmovie_wasm.js} 的 {@code build_play_url}）现算签名，不属于可用 HTTP 复现的明文接口。
     */
    private static String fetchAllGroups() {
        long deadline = System.currentTimeMillis() + FETCH_DEADLINE_MS;
        final StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        int total = 0;

        // 【2026-10-18 提速】原来 4 个分类页 + 24 个片单详情全部串行，冷启一次要 17s。
        // 改为**有上限的并行**：最多 3 个请求同时在飞（同一客户端、顺序落 M3U，
        // 结果顺序仍按 GROUPS / 片单顺序，不影响展示）。24 片单 10s → 约 3s。
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                FETCH_PARALLEL, r -> {
                    Thread t = new Thread(r, "kvm4-fetch");
                    t.setDaemon(true);
                    return t;
                });

        try {
            // ---------- 分类页：并行抓取 ----------
            final java.util.List<String[]> catGroups = new java.util.ArrayList<>();
            java.util.List<java.util.concurrent.Future<java.util.LinkedHashMap<String, String>>> catFutures =
                    new java.util.ArrayList<>();
            for (String[] g : GROUPS) {
                if ("片单".equals(g[0])) continue;
                catGroups.add(g);
                final String catUrl = g[1];
                catFutures.add(pool.submit(() -> readCategoryCards(catUrl)));
            }

            for (int i = 0; i < catGroups.size(); i++) {
                String groupName = catGroups.get(i)[0];
                java.util.LinkedHashMap<String, String> cards = null;
                try {
                    long remain = Math.max(1500L, deadline - System.currentTimeMillis());
                    cards = catFutures.get(i).get(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "源8(4kvm)：分组[" + groupName + "]抓取异常 "
                            + t.getClass().getSimpleName());
                }
                if (cards == null || cards.isEmpty()) {
                    LogBridge.w(TAG, "源8(4kvm)：分组[" + groupName + "]未抓到视频");
                    continue;
                }
                int count = appendVideosToM3u(cardsToVideos(cards), groupName, m3u, true, new int[1]);
                total += count;
                LogBridge.i(TAG, "源8(4kvm)：分组[" + groupName + "]抓到 " + count + " 部");
            }

            // ---------- 片单：先取目录，再并行取每个片单 ----------
            java.util.List<Integer> ids = readPlaylistIds();
            LogBridge.i(TAG, "源8(4kvm)：片单列表接口返回 " + ids.size() + " 个片单");

            java.util.List<java.util.concurrent.Future<java.util.List<java.util.Map<String, String>>>>
                    plFutures = new java.util.ArrayList<>();
            for (int id : ids) {
                final int pid = id;
                plFutures.add(pool.submit(() -> readPlaylistVideos(pid)));
            }

            java.util.List<java.util.Map<String, String>> all = new java.util.ArrayList<>();
            for (int i = 0; i < plFutures.size(); i++) {
                if (System.currentTimeMillis() > deadline) {
                    LogBridge.w(TAG, "源8(4kvm)：片单抓取已达截止时间，已处理 "
                            + all.size() + " 部，停止");
                    break;
                }
                try {
                    long remain = Math.max(1500L, deadline - System.currentTimeMillis());
                    java.util.List<java.util.Map<String, String>> v =
                            plFutures.get(i).get(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (v != null && !v.isEmpty()) all.addAll(v);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "源8(4kvm)：片单[" + ids.get(i) + "]抓取异常 "
                            + t.getClass().getSimpleName());
                }
            }

            if (!all.isEmpty()) {
                int count = appendVideosToM3u(all, "片单", m3u, false, new int[]{0});
                total += count;
                LogBridge.i(TAG, "源8(4kvm)：分组[片单]抓到 " + count + " 部");
            }
        } finally {
            pool.shutdownNow();
        }

        if (total == 0) {
            LogBridge.w(TAG, "源8(4kvm)：无任何可用频道");
            return null;
        }

        LogBridge.i(TAG, "源8(4kvm)：共抓到 " + total + " 部影片");
        return m3u.toString();
    }

    /** 卡片表 → 追加用的视频列表（带海报）。 */
    private static java.util.List<java.util.Map<String, String>> cardsToVideos(
            java.util.LinkedHashMap<String, String> cards) {
        java.util.List<java.util.Map<String, String>> videos = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : cards.entrySet()) {
            java.util.Map<String, String> v = new java.util.HashMap<>();
            v.put("playId", e.getKey());
            v.put("title", e.getValue());
            videos.add(v);
        }
        return videos;
    }

    /**
     * 分类页抓取（纯 HTTP）：站点 HTML 为服务端直出，卡片形如
     * <pre>{@code
     * <a href="/play/ch4pq4efv" data-vod="..." class="block">
     *   <img data-src="https://....jpg" alt="玛克辛">
     *   <h3>玛克辛</h3>
     * </a>
     * }</pre>
     * 按 playId 去重（同页可能出现于多个栏目），标题优先取 alt，其次取 h3。
     *
     * @return playId → 标题（保持页面顺序）；失败返回空表
     */
    private static java.util.LinkedHashMap<String, String> readCategoryCards(String pageUrl) {
        java.util.LinkedHashMap<String, String> idToTitle = new java.util.LinkedHashMap<>();
        String html = fetchPage(pageUrl);
        if (TextUtils.isEmpty(html)) return idToTitle;

        Matcher m = CARD_PATTERN.matcher(html);
        while (m.find()) {
            String playId = m.group(1);
            String body = m.group(2);
            if (idToTitle.containsKey(playId)) continue;

            String title = null;
            Matcher mt = CARD_TITLE_PATTERN.matcher(body);
            if (mt.find()) title = htmlUnescape(mt.group(1)).trim();
            if (TextUtils.isEmpty(title)) {
                Matcher mh = CARD_H3_PATTERN.matcher(body);
                if (mh.find()) title = htmlUnescape(mh.group(1)).trim();
            }
            if (TextUtils.isEmpty(title)) title = playId;
            idToTitle.put(playId, title);
        }
        return idToTitle;
    }

    /**
     * 片单目录：{@code /api/playlists/list} → 片单 id 列表（保持站点顺序）。
     */
    private static java.util.List<Integer> readPlaylistIds() {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        String listJson = fetchPage(BASE + PLAYLIST_LIST_PATH);
        if (TextUtils.isEmpty(listJson)) {
            LogBridge.w(TAG, "源8(4kvm)：片单列表接口无响应");
            return ids;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(listJson);
            if (root.optInt("code", 0) != 200) {
                LogBridge.w(TAG, "源8(4kvm)：片单列表接口返回异常 code=" + root.optInt("code", -1));
                return ids;
            }
            org.json.JSONArray list = root.getJSONObject("data").optJSONArray("list");
            if (list == null) return ids;
            for (int i = 0; i < list.length(); i++) {
                int id = list.getJSONObject(i).optInt("id", 0);
                if (id > 0) ids.add(id);
            }
        } catch (Exception e) {
            LogBridge.w(TAG, "源8(4kvm)：片单列表 JSON 解析失败 " + e.getMessage());
        }
        return ids;
    }

    /**
     * 单个片单条目：{@code /api/playlists/detail?id=<id>}。
     *
     * <p>接口条目里的 {@code movie.play_url} 形如 {@code /play/ch15obsbb}，取其末段作为 playId。
     */
    private static java.util.List<java.util.Map<String, String>> readPlaylistVideos(int playlistId) {
        java.util.List<java.util.Map<String, String>> out = new java.util.ArrayList<>();
        String detailJson = fetchPage(BASE + "/api/playlists/detail?id=" + playlistId
                + "&page=1&per_page=" + PLAYLIST_PER_PAGE);
        if (TextUtils.isEmpty(detailJson)) return out;

        try {
            org.json.JSONObject root = new org.json.JSONObject(detailJson);
            if (root.optInt("code", 0) != 200) return out;
            org.json.JSONObject data = root.optJSONObject("data");
            if (data == null) return out;
            org.json.JSONArray items = data.optJSONArray("items");
            if (items == null) return out;

            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject movie = items.getJSONObject(i).optJSONObject("movie");
                if (movie == null) continue;
                String playUrl = movie.optString("play_url", "");
                Matcher pm = PLAY_ID_IN_URL.matcher(playUrl);
                if (!pm.find()) continue;
                String playId = pm.group(1);
                if (TextUtils.isEmpty(playId)) continue;

                String title = movie.optString("title", "");
                if (TextUtils.isEmpty(title)) title = playId;

                java.util.Map<String, String> v = new java.util.HashMap<>();
                v.put("playId", playId);
                v.put("title", title);
                String poster = movie.optString("poster", "");
                if (TextUtils.isEmpty(poster)) poster = movie.optString("cover", "");
                if (!TextUtils.isEmpty(poster)) v.put("poster", poster);
                out.add(v);
            }
            LogBridge.i(TAG, "源8(4kvm)：片单[" + playlistId + "]抓到 " + out.size() + " 部");
        } catch (Exception e) {
            LogBridge.w(TAG, "源8(4kvm)：片单[" + playlistId + "]解析失败 " + e.getMessage());
        }
        return out;
    }


    /**
     * 将视频列表追加到 M3U。
     *
     * @param dedup     是否按 playId 去重（片单分组传 false，保留重复视频）
     * @param seqHolder 跨片单共享的序号容器（长度 1）。
     *                  <p><b>【2026-10-18 修复】</b>原实现把序号声明为方法内局部变量，
     *                  每个片单调用都从 0 重来，导致 24 个片单产出完全相同的 tvg-id，
     *                  下游按 key 合并时 168 条塌成 7 条。改为由调用方传入共享容器。
     */
    private static int appendVideosToM3u(java.util.List<java.util.Map<String, String>> videos,
                                          String groupName, StringBuilder m3u, boolean dedup,
                                          final int[] seqHolder) {
        int count = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (java.util.Map<String, String> video : videos) {
            String playId = video.get("playId");
            String title = video.get("title");

            if (TextUtils.isEmpty(playId)) continue;
            if (dedup && seen.contains(playId)) continue;
            seen.add(playId);

            if (TextUtils.isEmpty(title)) title = playId;
            // 清理标题中的评分、年份等
            title = title.replaceAll("\\d+\\.\\d+", "").trim();
            title = title.replaceAll("\\b(19|20)\\d{2}\\b", "").trim();
            title = title.replace("VIP", "").trim();
            title = title.replaceAll("\\s+", " ").trim();
            if (TextUtils.isEmpty(title)) title = playId;

            // 不去重时，tvg-id 加全局唯一序号，避免下游按相同 key 合并成一个频道
            String tvgId = dedup ? playId : (playId + "_" + (seqHolder[0]++));

            m3u.append("#EXTINF:-1");
            m3u.append(" tvg-id=\"").append(tvgId).append("\"");
            m3u.append(" group-title=\"").append(groupName).append("\"");
            m3u.append(",").append(title).append("\n");
            m3u.append("kvm4://").append(playId).append("\n");
            count++;
        }

        return count;
    }

    /**
     * HTML 实体反转义（海报 URL 里的 {@code &amp;} 等）。
     */
    private static String htmlUnescape(String s) {
        if (TextUtils.isEmpty(s)) return "";
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    /**
     * 清理标题：去除HTML标签、多余空白
     */
    private static String cleanTitle(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        // 去除HTML标签
        String t = raw.replaceAll("<[^>]+>", " ").trim();
        // 去除评分（如 7.8 VIP）
        t = t.replaceAll("\\d+\\.\\d+", "").trim();
        // 去除年份（如 2024）
        t = t.replaceAll("\\b(19|20)\\d{2}\\b", "").trim();
        // 去除VIP标记
        t = t.replace("VIP", "").trim();
        // 合并空白
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /**
     * 从页面HTML中提取指定视频的海报URL
     */
    private static String extractPoster(String html, String playId) {
        // 尝试匹配 playId 附近的图片
        Pattern p = Pattern.compile(
                "/play/" + Pattern.quote(playId) + "[\\s\\S]{0,500}?src=\"([^\"]+\\.(jpg|png|webp))\"",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            String url = m.group(1);
            if (url.startsWith("//")) url = "https:" + url;
            else if (url.startsWith("/")) url = BASE + url;
            return url;
        }
        return null;
    }

    /**
     * 供 {@link Kvm4UrlResolver} 取集数用的 HTML 抓取入口（同一专用客户端，共享连接池）。
     *
     * @param url 绝对地址
     * @return HTML 文本；失败返回 null
     */
    public static String fetchHtml(String url) {
        return fetchPage(url);
    }

    /**
     * 抓取页面HTML。
     *
     * <p><b>【2026-10-18 性能修复】</b>两个瓶颈都在 {@code NetUtil} 通用链路上，这里改用专用客户端：
     * <ol>
     *   <li>{@code NetUtil.SecurityRequestInterceptor} 无条件加 {@code Accept-Encoding: identity}，
     *       导致 4kvm 的 HTML 无法走 gzip（分类页未压缩约 350KB，压缩后约 1/5）；</li>
     *   <li>{@code NetUtil.syncGetWithHeaders(url, headers, connectMs, readMs)} 每次
     *       {@code newBuilder().build()} 新建 OkHttpClient，<b>连接池不共享</b>，
     *       一次加载 29 个请求就要 29 次 TCP+TLS 握手。</li>
     * </ol>
     * 本客户端只服务 4kvm：固定超时、复用连接池、允许 gzip（OkHttp 自动解压），
     * 并保留 {@link SecurityCertificatePinner} 的证书固定。
     */
    private static String fetchPage(String url) {
        try {
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", BASE + "/")
                    .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8");
            try (Response resp = http().newCall(rb.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    LogBridge.w(TAG, "源8(4kvm)：GET 失败 code=" + resp.code() + " url=" + url);
                    return null;
                }
                return resp.body().string();
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "源8(4kvm)：抓取异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** 4kvm 专用 HTTP 客户端（懒构造；共享连接池，支持 gzip） */
    private static okhttp3.OkHttpClient http() {        okhttp3.OkHttpClient c = sHttp;
        if (c != null) return c;
        synchronized (Kvm4SourceLoader.class) {
            if (sHttp == null) {
                java.util.concurrent.TimeUnit ms = java.util.concurrent.TimeUnit.MILLISECONDS;
                okhttp3.OkHttpClient.Builder b = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(FETCH_CONNECT_MS, ms)
                        .readTimeout(FETCH_READ_MS, ms)
                        // 整次调用上限：兜住「服务器挂起慢慢吐字节」的病态情况
                        .callTimeout(FETCH_CALL_MS, ms)
                        .retryOnConnectionFailure(true)
                        .connectionPool(new okhttp3.ConnectionPool(4, 5, java.util.concurrent.TimeUnit.MINUTES))
                        .proxy(java.net.Proxy.NO_PROXY);
                b = com.tv.live.util.SecurityCertificatePinner.getInstance().configureOkHttpClient(b);
                sHttp = b.build();
            }
            return sHttp;
        }
    }


    /**
     * 获取指定 playId 的海报 URL
     */
    public static String posterOf(String playId) {
        return sPosterCache.get(playId);
    }
}
