package com.tv.live.ryb;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.util.LogBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【2026-10-19 内置源8（renyibo）】播放地址解析器 —— <b>照源7（Afhgrj）的架构做</b>。
 *
 * <p>与旧源8（kvm4/WebView）的区别：
 * <ul>
 *   <li><b>纯 HTTP</b>：不起 WebView、不碰前端 WASM；</li>
 *   <li><b>惰性解析</b>：M3U 里只放 {@code ryb://<slug>}，真正取流在起播那一刻；</li>
 *   <li><b>线路 / 集数原生支持</b>：详情页里就有结构化 {@code PLAY_SOURCES}，
 *       每条线路下每个 {@code episodes[]} 都带**直链**，不需要二次请求。</li>
 * </ul>
 *
 * <p>地址协议：
 * <pre>
 *   ryb://&lt;slug&gt;                 整片（第 1 条线路第 1 集）
 *   ryb://&lt;slug&gt;@&lt;src&gt;-&lt;ep&gt;       指定线路 + 指定集（src/ep 从 1 开始）
 * </pre>
 * {@code slug} 是详情页路径末段（形如 {@code 深空法则-187347} 的 URL 编码形式），
 * 因为站点**只认 slug**，纯数字 id 访问会 404（实测）。
 *
 * <p>详情页关键数据（实测）：
 * <pre>
 * window.PLAY_SOURCES = [{"name":"快速线路","episodes":[
 *     {"label":"HD国语","url":"https:\/\/super.ffzy-online6.com\/.../index.m3u8"}]}];
 * </pre>
 */
public final class RybUrlResolver {

    private static final String TAG = "RybUrlResolver";

    /** 自定义协议：M3U 播放地址前缀 */
    public static final String SCHEME = "ryb://";
    /** 频道 id 前缀（{@code tvg-id="ryb:<slug>"}） */
    public static final String TVG_PREFIX = "ryb:";

    private static final String BASE = "https://www.renyibo.com";
    private static final String PATH_VIDEO = "/video/";

    /** 详情页缓存有效期：30 分钟（线路/集数极少变，地址本身是 CDN 直链不过期） */
    private static final long INFO_TTL_MS = 30L * 60L * 1000L;
    /**
     * 【2026-10-19 列表构建期带上集数】集数/线路**落盘**缓存文件。
     *
     * <p>为什么这么做：源6/源5 之所以 EPG 零请求，是因为<b>集数在列表构建期就已经拿到了</b>。
     * 源8 的列表页只有卡片（标题+海报），集数必须进详情页才有 —— 若照搬「构建期全抓」，
     * 320 个频道就是 320 次请求/次刷新，属于对站点的批量请求，不做。
     * 折中：抓到一次就<b>落盘</b>，之后 EPG 直接从磁盘读 → 第二次起零请求。
     */
    private static final String DISK_FILE = "ryb8_episodes.json";
    /** 磁盘条目有效期 24h（CDN 直链可能轮换，过期后重抓一次） */
    private static final long DISK_TTL_MS = 24L * 60L * 60L * 1000L;
    /** 解析结果（m3u8）缓存：10 分钟 */
    private static final long URL_TTL_MS = 10L * 60L * 1000L;

    /** 一条线路 */
    public static final class Line {
        public final String name;
        public final List<String> labels = new ArrayList<>();
        public final List<String> urls = new ArrayList<>();

        Line(String name) {
            this.name = name;
        }
    }

    /** 一部影片的播放信息 */
    public static final class Info {
        public final String slug;
        public final String title;
        public final List<Line> lines = new ArrayList<>();
        long ts;

        Info(String slug, String title) {
            this.slug = slug;
            this.title = title;
        }

        public int lineCount() {
            return lines.size();
        }

        public Line lineAt(int idx) {
            if (idx < 0 || idx >= lines.size()) return null;
            return lines.get(idx);
        }
    }

    private static final Map<String, Info> sInfoCache = new ConcurrentHashMap<>();
    private static final Map<String, String[]> sUrlCache = new ConcurrentHashMap<>();
    /** 用户手选线路：slug → 线路下标（0 基）；缺省 0 */
    private static final Map<String, Integer> sSelectedLine = new ConcurrentHashMap<>();
    /** 【2026-10-19 EPG 单飞】占位成功返回 true（调用方才发起抓取）；已在抓取中返回 false。 */
    public static boolean beginFetch(String slug) {
        if (TextUtils.isEmpty(slug)) return false;
        return sFetching.putIfAbsent(slug, new Object()) == null;
    }

    /** 【2026-10-19 EPG 单飞】释放占位。 */
    public static void endFetch(String slug) {
        if (!TextUtils.isEmpty(slug)) sFetching.remove(slug);
    }

    /**
     * 【2026-10-19 集数提速】用户「正在查看」的影片 slug（打开详情页的那一部）。
     *
     * <p>EPG 的联网预取闸门原本只放行「正在播放」的频道，于是用户点开某部片的详情页时，
     * 集数区会一直空着，直到按下播放才开始联网抓详情页（用户反馈「源8 集数加载太慢」）。
     * 额外放行「正在查看」的这一部：用户在读简介 / 挑线路的时候集数就已经抓好，
     * 按下播放即刻就有列表。仍然只有用户主动打开的那一部会联网（非批量预取）。
     */
    private static volatile String sForegroundSlug;

    public static void setForegroundSlug(String slug) {
        sForegroundSlug = TextUtils.isEmpty(slug) ? null : slug;
    }

    /** 是否是「用户正在查看」的那部片（EPG 预取闸门放行条件之一） */
    public static boolean isForeground(String slug) {
        return !TextUtils.isEmpty(slug) && slug.equals(sForegroundSlug);
    }

    /** 【2026-10-19 上下集】当前播放的集下标（0 基）：slug → ep。整片起播视为 0。 */
    private static final Map<String, Integer> sCurEp = new ConcurrentHashMap<>();
    private static final Map<String, Object> sLocks = new ConcurrentHashMap<>();
    /** 抓取中占位（EPG 单飞）：同一部片同时只允许一个预取任务 */
    private static final Map<String, Object> sFetching = new ConcurrentHashMap<>();
    /** 抓取失败冷却：slug → 失败时刻。避免同一部片被反复重试（网格会多次调 getEpg）。 */
    private static final Map<String, Long> sFailAt = new ConcurrentHashMap<>();
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;

    private static volatile Context sAppCtx = null;
    private static volatile boolean sDiskLoaded = false;
    private static final Object sDiskLock = new Object();

    private RybUrlResolver() {
    }

    /** 注入 Application 上下文（TVPlayerManager / EpgManager 首次调用时传入一次即可）。 */
    public static void attach(Context c) {
        if (c != null && sAppCtx == null) sAppCtx = c.getApplicationContext();
    }

    // ==================== URL 解析 ====================

    /** 是否本源的播放地址 */
    /** 【2026-10-19 遮罩用】最近一次解析出的真实 m3u8 地址（供 UI 查广告时间窗） */
    public static volatile String sLastResolvedUrl = null;

    public static boolean isRybUrl(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /** 从频道取 slug（{@code tvg-id="ryb:<slug>"}）；非本源返回 null */
    public static String vodIdOf(Channel ch) {
        if (ch == null) return null;
        String cid = ch.getChannelId();
        if (TextUtils.isEmpty(cid) || !cid.startsWith(TVG_PREFIX)) return null;
        String v = cid.substring(TVG_PREFIX.length()).trim();
        return v.isEmpty() ? null : v;
    }

    /** 从 {@code ryb://<slug>[@src-ep]} 取 slug */
    public static String slugOf(String rybUrl) {
        if (!isRybUrl(rybUrl)) return null;
        String rest = rybUrl.substring(SCHEME.length()).trim();
        int at = rest.indexOf('@');
        if (at >= 0) rest = rest.substring(0, at);
        return rest.isEmpty() ? null : rest;
    }

    /** 从 slug 取数字 id（{@code 深空法则-187347} → 187347）；取不到返回 0 */
    public static int videoIdOf(String slug) {
        if (TextUtils.isEmpty(slug)) return 0;
        int dash = slug.lastIndexOf('-');
        if (dash < 0 || dash + 1 >= slug.length()) return 0;
        try {
            return Integer.parseInt(slug.substring(dash + 1).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 频道展示名（slug 去掉编码与尾部 id，作为标题兜底） */
    public static String titleOf(String slug) {
        Info info = sInfoCache.get(slug);
        if (info != null && !TextUtils.isEmpty(info.title)) return info.title;
        int dash = slug == null ? -1 : slug.lastIndexOf('-');
        String t = dash > 0 ? slug.substring(0, dash) : slug;
        try {
            t = java.net.URLDecoder.decode(t, "UTF-8");
        } catch (Throwable ignored) {
        }
        return TextUtils.isEmpty(t) ? slug : t;
    }

    // ==================== 详情页解析（纯 HTTP） ====================

    /** 详情页地址：{@code https://www.renyibo.com/video/<slug>/} */
    public static String pageUrlOf(String slug) {
        return BASE + PATH_VIDEO + slug + "/";
    }

    /**
     * 取（或抓）播放信息。带 30 分钟缓存与单飞锁：同一部片并发只抓一次。
     *
     * @return 解析失败返回 null
     */
    public static Info info(String slug) {
        if (TextUtils.isEmpty(slug)) return null;
        Info cached = sInfoCache.get(slug);
        if (cached != null && System.currentTimeMillis() - cached.ts < INFO_TTL_MS) {
            return cached;
        }
        Long failAt = sFailAt.get(slug);
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_COOLDOWN_MS) {
            return null;
        }
        // ② 内存未命中 → 试磁盘（只加载一次）。命中则 EPG 全程零网络请求。
        if (!sDiskLoaded) loadDisk();
        cached = sInfoCache.get(slug);
        if (cached != null && System.currentTimeMillis() - cached.ts < DISK_TTL_MS) {
            return cached;
        }

        Object lock = sLocks.get(slug);
        if (lock == null) {
            synchronized (sLocks) {
                lock = sLocks.get(slug);
                if (lock == null) {
                    lock = new Object();
                    sLocks.put(slug, lock);
                }
            }
        }
        synchronized (lock) {
            cached = sInfoCache.get(slug);
            if (cached != null && System.currentTimeMillis() - cached.ts < INFO_TTL_MS) {
                return cached;
            }
            String html = RybHttp.get(pageUrlOf(slug));
            if (TextUtils.isEmpty(html)) {
                LogBridge.w(TAG, "详情页抓取失败 slug=" + slug);
                sFailAt.put(slug, System.currentTimeMillis());
                return null;
            }
            Info info = parseInfo(slug, html);
            if (info != null && info.lineCount() > 0) {
                info.ts = System.currentTimeMillis();
                sInfoCache.put(slug, info);
                // 【2026-10-19】抓到即落盘：下次启动/下次进 EPG 无需再联网
                saveDiskAsync();
                LogBridge.i(TAG, "详情解析成功 slug=" + slug + " 标题=" + info.title
                        + " 线路=" + info.lineCount()
                        + " 首线集数=" + info.lines.get(0).labels.size());
                return info;
            }
            LogBridge.w(TAG, "详情解析：未找到 PLAY_SOURCES slug=" + slug + " htmlLen=" + html.length());
            sFailAt.put(slug, System.currentTimeMillis());
            return null;
        }
    }

    // ==================== 集数落盘（列表构建期带上集数） ====================

    /** 一次性把磁盘里的集数读进内存；文件不存在/损坏时静默返回。 */
    private static void loadDisk() {
        synchronized (sDiskLock) {
            if (sDiskLoaded) return;
            Context ctx = sAppCtx;
            // ⚠️ 上下文还没注入时必须**直接返回而不置位**，否则本次会话会永久跳过磁盘缓存
            if (ctx == null) return;
            sDiskLoaded = true;
            try {
                java.io.File f = new java.io.File(ctx.getFilesDir(), DISK_FILE);
                if (!f.exists()) return;
                StringBuilder sb = new StringBuilder();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                br.close();
                org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                int n = 0;
                java.util.Iterator<String> keys = root.keys();
                long now = System.currentTimeMillis();
                while (keys.hasNext()) {
                    String slug = keys.next();
                    org.json.JSONObject o = root.optJSONObject(slug);
                    if (o == null) continue;
                    long ts = o.optLong("ts", 0L);
                    if (ts > 0 && now - ts > DISK_TTL_MS) continue;   // 过期条目丢弃
                    Info info = new Info(slug, o.optString("t", titleOf(slug)));
                    info.ts = ts;
                    org.json.JSONArray ls = o.optJSONArray("l");
                    if (ls != null) {
                        for (int i = 0; i < ls.length(); i++) {
                            org.json.JSONObject lo = ls.optJSONObject(i);
                            if (lo == null) continue;
                            Line ln = new Line(lo.optString("n", "线路" + (i + 1)));
                            org.json.JSONArray labs = lo.optJSONArray("e");
                            if (labs != null) {
                                for (int j = 0; j + 1 < labs.length(); j += 2) {
                                    ln.labels.add(labs.optString(j, ""));
                                    ln.urls.add(labs.optString(j + 1, ""));
                                }
                            }
                            if (!ln.urls.isEmpty()) info.lines.add(ln);
                        }
                    }
                    if (info.lineCount() > 0) {
                        sInfoCache.put(slug, info);
                        n++;
                    }
                }
                LogBridge.i(TAG, "集数磁盘缓存已加载 " + n + " 部（文件 " + f.length() + " 字节）");
            } catch (Throwable t) {
                LogBridge.w(TAG, "集数磁盘缓存读取失败 " + t.getMessage());
            }
        }
    }

    /** 异步写盘（限流：30 秒内只写一次，避免频繁 IO）。 */
    private static void saveDiskAsync() {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        new Thread(() -> {
            synchronized (sDiskLock) {
                try {
                    org.json.JSONObject root = new org.json.JSONObject();
                    for (java.util.Map.Entry<String, Info> e : sInfoCache.entrySet()) {
                        Info info = e.getValue();
                        if (info == null || info.lineCount() == 0) continue;
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("t", info.title);
                        o.put("ts", info.ts);
                        org.json.JSONArray ls = new org.json.JSONArray();
                        for (Line ln : info.lines) {
                            org.json.JSONObject lo = new org.json.JSONObject();
                            lo.put("n", ln.name);
                            org.json.JSONArray arr = new org.json.JSONArray();
                            for (int i = 0; i < ln.labels.size(); i++) {
                                arr.put(ln.labels.get(i));
                                arr.put(ln.urls.get(i));
                            }
                            lo.put("e", arr);
                            ls.put(lo);
                        }
                        o.put("l", ls);
                        root.put(e.getKey(), o);
                    }
                    java.io.File dir = ctx.getFilesDir();
                    java.io.File tmp = new java.io.File(dir, DISK_FILE + ".tmp");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                    fos.write(root.toString().getBytes("UTF-8"));
                    fos.close();
                    java.io.File dst = new java.io.File(dir, DISK_FILE);
                    if (dst.exists()) dst.delete();
                    tmp.renameTo(dst);   // 原子替换，避免读到半截文件
                    LogBridge.i(TAG, "集数磁盘缓存已写入 " + sInfoCache.size() + " 部");
                } catch (Throwable t) {
                    LogBridge.w(TAG, "集数磁盘缓存写入失败 " + t.getMessage());
                }
            }
        }, "Ryb-EpsSave").start();
    }

    /** 从详情页 HTML 里抠 {@code PLAY_SOURCES} 与 {@code VIDEO_DATA.vod_name} */
    private static Info parseInfo(String slug, String html) {
        String arr = extractArray(html, "PLAY_SOURCES");
        if (TextUtils.isEmpty(arr)) return null;

        String title = null;
        try {
            int nameIdx = html.indexOf("vod_name:");
            if (nameIdx > 0) {
                int q1 = html.indexOf('"', nameIdx);
                int q2 = q1 > 0 ? html.indexOf('"', q1 + 1) : -1;
                if (q1 > 0 && q2 > q1) title = html.substring(q1 + 1, q2);
            }
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(title)) title = titleOf(slug);

        Info info = new Info(slug, title);
        try {
            org.json.JSONArray srcs = new org.json.JSONArray(arr);
            for (int i = 0; i < srcs.length(); i++) {
                org.json.JSONObject s = srcs.optJSONObject(i);
                if (s == null) continue;
                String name = s.optString("name", "");
                Line line = new Line(TextUtils.isEmpty(name) ? ("线路" + (i + 1)) : name);
                org.json.JSONArray eps = s.optJSONArray("episodes");
                if (eps != null) {
                    for (int j = 0; j < eps.length(); j++) {
                        org.json.JSONObject ep = eps.optJSONObject(j);
                        if (ep == null) continue;
                        String u = ep.optString("url", "");
                        if (TextUtils.isEmpty(u)) continue;
                        String label = ep.optString("label", "");
                        if (TextUtils.isEmpty(label)) label = "第" + (j + 1) + "集";
                        line.labels.add(label);
                        line.urls.add(u.trim());
                    }
                }
                if (!line.urls.isEmpty()) info.lines.add(line);
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "PLAY_SOURCES 解析失败: " + t.getMessage());
            return null;
        }
        return info.lineCount() > 0 ? info : null;
    }

    /**
     * 从 JS 里抠出 {@code KEY = [ ... ]} 的平衡括号数组文本。
     */
    private static String extractArray(String text, String key) {
        int k = text.indexOf(key);
        if (k < 0) return null;
        int start = text.indexOf('[', k);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    // ==================== 线路选择 ====================

    public static int getSelectedLine(String slug) {
        Integer v = sSelectedLine.get(slug);
        return v == null ? 0 : v;
    }

    /** 【2026-10-19 上下集】当前播放集下标（0 基）；未知返回 -1。 */
    public static int getCurrentEpisode(String slug) {
        if (TextUtils.isEmpty(slug)) return -1;
        Integer v = sCurEp.get(slug);
        return v == null ? -1 : v;
    }

    /**
     * 【2026-10-19 选集面板】直接置当前集下标。
     *
     * <p>{@link #resolve} 起播时才会写 sCurEp；用户在选集面板点某一集时解析尚未开始，
     * 若不在此同步置位，面板高亮与「正在播放 · 第N集」会停在上一集的数字上。
     */
    public static void setCurrentEpisode(String slug, int idx) {
        if (TextUtils.isEmpty(slug) || idx < 0) return;
        sCurEp.put(slug, idx);
    }

    /** 指定线路的集数（供设置/线路面板展示）。 */
    public static int episodeCount(String slug, int lineIdx) {
        Info info = sInfoCache.get(slug);
        if (info == null) return 0;
        Line l = info.lineAt(lineIdx);
        return l == null ? 0 : l.labels.size();
    }

    public static void setSelectedLine(String slug, int idx) {
        if (TextUtils.isEmpty(slug) || idx < 0) return;
        sSelectedLine.put(slug, idx);
        // 线路变了，已解析地址不再适用
        for (String key : new ArrayList<>(sUrlCache.keySet())) {
            if (key.startsWith(slug)) sUrlCache.remove(key);
        }
        LogBridge.i(TAG, "线路切换 slug=" + slug + " → 第" + (idx + 1) + "条");
    }

    /** 线路名列表（供线路面板） */
    public static List<String> lineNames(String slug) {
        List<String> out = new ArrayList<>();
        Info info = info(slug);
        if (info == null) return out;
        for (int i = 0; i < info.lines.size(); i++) {
            Line l = info.lines.get(i);
            out.add("线路" + (i + 1) + " · " + l.name + "（" + l.labels.size() + "）");
        }
        return out;
    }

    // ==================== 集数（EPG 用） ====================

    /**
     * 取选集列表：按**当前选中线路**生成，{@code customPlayUrl=ryb://<slug>@<src>-<ep>}。
     * 只读内存缓存，不联网（未就绪返回空列表，由 {@link #fetchEpisodes} 预热）。
     */
    public static List<Channel.EpgItem> getEpisodes(String slug) {
        Info info = sInfoCache.get(slug);
        if (info == null) return new ArrayList<>();
        return buildItems(info, getSelectedLine(slug));
    }

    /** 联网抓一次并返回选集（供 EpgManager 后台预热） */
    public static List<Channel.EpgItem> fetchEpisodes(String slug) {
        Info info = info(slug);
        if (info == null) return new ArrayList<>();
        return buildItems(info, getSelectedLine(slug));
    }

    private static List<Channel.EpgItem> buildItems(Info info, int lineIdx) {
        List<Channel.EpgItem> out = new ArrayList<>();
        Line line = info.lineAt(lineIdx);
        if (line == null) return out;
        for (int i = 0; i < line.labels.size(); i++) {
            String label = line.labels.get(i);
            Channel.EpgItem it = new Channel.EpgItem("今天", "", label, false);
            it.customPlayUrl = SCHEME + info.slug + "@" + (lineIdx + 1) + "-" + (i + 1);
            out.add(it);
        }
        return out;
    }

    // ==================== 起播解析 ====================

    /**
     * 解析 {@code ryb://...} 为真实 m3u8 直链。
     *
     * @param rybUrl {@code ryb://<slug>} 或 {@code ryb://<slug>@<src>-<ep>}
     * @return m3u8；失败返回 null
     */
    public static String resolve(String rybUrl) {
        String slug = slugOf(rybUrl);
        if (TextUtils.isEmpty(slug)) {
            LogBridge.w(TAG, "解析失败：无法提取 slug，url=" + rybUrl);
            return null;
        }

        int src = -1;
        int ep = -1;
        String rest = rybUrl.substring(SCHEME.length());
        int at = rest.indexOf('@');
        if (at >= 0) {
            String[] p = rest.substring(at + 1).split("-");
            if (p.length >= 2) {
                try {
                    src = Integer.parseInt(p[0].trim()) - 1;
                    ep = Integer.parseInt(p[1].trim()) - 1;
                } catch (Throwable ignored) {
                }
            }
        }
        if (src < 0) src = getSelectedLine(slug);
        if (ep < 0) ep = 0;

        String cacheKey = slug + "@" + src + "-" + ep;
        String[] hit = sUrlCache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - parseLong(hit[1]) < URL_TTL_MS) {
            LogBridge.i(TAG, "命中解析缓存 " + cacheKey);
            return hit[0];
        }

        Info info = info(slug);
        if (info == null) {
            LogBridge.w(TAG, "解析失败：详情页无播放数据 slug=" + slug);
            return null;
        }
        Line line = info.lineAt(src);
        if (line == null && info.lineCount() > 0) line = info.lines.get(0);
        if (line == null || line.urls.isEmpty()) {
            LogBridge.w(TAG, "解析失败：线路为空 slug=" + slug);
            return null;
        }
        if (ep >= line.urls.size()) ep = line.urls.size() - 1;

        String url = line.urls.get(ep);
        // 【2026-10-19 上下集】记录当前集下标，供控制栏「上一集/下一集」定位
        sCurEp.put(slug, ep);
        if (TextUtils.isEmpty(url)) return null;
        sUrlCache.put(cacheKey, new String[]{url, String.valueOf(System.currentTimeMillis())});
        sLastResolvedUrl = url;
        LogBridge.i(TAG, "解析成功 " + cacheKey + " -> " + url);
        return url;
    }

    /** 播放失败时作废本片的地址缓存 */
    public static void invalidateByUrl(String rybUrl) {
        String slug = slugOf(rybUrl);
        if (TextUtils.isEmpty(slug)) return;
        for (String key : new ArrayList<>(sUrlCache.keySet())) {
            if (key.startsWith(slug)) sUrlCache.remove(key);
        }
        sInfoCache.remove(slug);
        LogBridge.i(TAG, "已作废缓存 slug=" + slug);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
