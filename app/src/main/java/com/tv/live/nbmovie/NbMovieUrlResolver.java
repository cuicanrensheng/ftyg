package com.tv.live.nbmovie;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.util.LogBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【2026-10-21 内置源2 接入 4K影视（NbMovie）】播放地址解析器 —— 照源8（renyibo）的架构做。
 *
 * <p>三个约定与源8 一致：
 * <ul>
 *   <li><b>纯 HTTP</b>：不起 WebView；</li>
 *   <li><b>惰性解析</b>：M3U 里只放 {@code nbm://<vodId>}，真正取流在起播那一刻；</li>
 *   <li><b>线路 / 集数原生支持</b>：详情接口直接给结构化 {@code play_urls}，
 *       每条线路下每个 {@code episodes[]} 都带 {@code pid/name/num}。</li>
 * </ul>
 *
 * <p>地址协议：
 * <pre>
 *   nbm://&lt;vodId&gt;               整片（当前选中线路第 1 集）
 *   nbm://&lt;vodId&gt;@&lt;line&gt;-&lt;ep&gt;     指定线路 + 指定集（line/ep 从 1 开始）
 * </pre>
 *
 * <p>解析链（两次串行请求）：{@code detail(vodId)} → {@code play_urls} 取
 * {@code pid/num} → {@code play(pid, vodId, num, "1080")} → {@code quality_urls} 取流。
 * 清晰度择优（对照原端 {@code PlayUrlData.currentUrl()}）：{@code current_quality}
 * 是 {@code quality_urls} 的**下标**，命中且未锁直接用；否则首个未锁非 VIP；再否则首个。
 */
public final class NbMovieUrlResolver {

    private static final String TAG = "NbMovieUrlResolver";

    /** 自定义协议：M3U 播放地址前缀 */
    public static final String SCHEME = "nbm://";
    /** 频道 id 前缀（{@code tvg-id="nbm:<group>:<vodId>"}） */
    public static final String TVG_PREFIX = "nbm:";

    /** 详情（线路/集数）缓存有效期：30 分钟 */
    private static final long INFO_TTL_MS = 30L * 60L * 1000L;
    /** 解析结果（m3u8）缓存：10 分钟 */
    private static final long URL_TTL_MS = 10L * 60L * 1000L;
    /** 抓取失败冷却：5 分钟。避免同一部片被反复重试（网格会多次调 getEpg）。 */
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;

    /** 一条线路 */
    public static final class Line {
        public final int line;
        public final String lineName;
        public final List<Ep> eps = new ArrayList<>();

        Line(int line, String lineName) {
            this.line = line;
            this.lineName = lineName;
        }
    }

    /** 一集 */
    public static final class Ep {
        public final int pid;
        public final String name;
        public final int num;

        Ep(int pid, String name, int num) {
            this.pid = pid;
            this.name = name;
            this.num = num;
        }
    }

    /** 一部影片的播放信息 */
    public static final class Info {
        public final int vodId;
        public final String title;
        public final List<Line> lines = new ArrayList<>();
        long ts;

        Info(int vodId, String title) {
            this.vodId = vodId;
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
    /** 用户手选线路：vodId → 线路下标（0 基）；缺省 0 */
    private static final Map<String, Integer> sSelectedLine = new ConcurrentHashMap<>();
    /** 当前播放集下标（0 基）：vodId → ep */
    private static final Map<String, Integer> sCurEp = new ConcurrentHashMap<>();
    private static final Map<String, Object> sLocks = new ConcurrentHashMap<>();
    /** 抓取中占位（EPG 单飞）：同一部片同时只允许一个预取任务 */
    private static final Map<String, Object> sFetching = new ConcurrentHashMap<>();
    /** 抓取失败时刻：vodId → 时刻 */
    private static final Map<String, Long> sFailAt = new ConcurrentHashMap<>();

    /**
     * 【集数提速】用户「正在查看」的影片 vodId（打开详情页的那一部）。
     *
     * <p>EPG 联网预取闸门额外放行「正在查看」的这一部：用户在读简介 / 挑线路时
     * 集数已抓好，按下播放即刻有列表。仍只有用户主动打开的那一部会联网。
     */
    private static volatile String sForegroundVodId;

    /** 【遮罩用】最近一次解析出的真实 m3u8 地址（供 UI 查广告时间窗） */
    public static volatile String sLastResolvedUrl = null;

    private NbMovieUrlResolver() {
    }

    /** 注入 Application 上下文（TVPlayerManager / EpgManager 首次调用时传入一次即可）。 */
    public static void attach(Context c) {
        if (c != null) {
            NbMovieApi.attach(c);
        }
    }

    // ==================== URL 解析 ====================

    /** 是否本源的播放地址 */
    public static boolean isNbmUrl(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /**
     * 从频道取 vodId（{@code tvg-id="nbm:<group>:<vodId>"}，取最后一个 {@code :} 后的 id）；
     * 非本源返回 null。
     */
    public static String vodIdOf(Channel ch) {
        if (ch == null) return null;
        String cid = ch.getChannelId();
        if (TextUtils.isEmpty(cid) || !cid.startsWith(TVG_PREFIX)) return null;
        int idx = cid.lastIndexOf(':');
        String v = (idx >= 0 ? cid.substring(idx + 1) : cid.substring(TVG_PREFIX.length())).trim();
        return v.isEmpty() ? null : v;
    }

    /** 从 {@code nbm://<vodId>[@<line>-<ep>]} 取 vodId；非法返回 null */
    public static String vodIdOfUrl(String nbmUrl) {
        if (!isNbmUrl(nbmUrl)) return null;
        String rest = nbmUrl.substring(SCHEME.length()).trim();
        int at = rest.indexOf('@');
        if (at >= 0) rest = rest.substring(0, at);
        rest = rest.trim();
        return rest.isEmpty() ? null : rest;
    }

    // ==================== 详情解析（detail → play_urls） ====================

    /**
     * 取（或抓）播放信息。带 30 分钟缓存、失败冷却与单飞锁：同一部片并发只抓一次。
     *
     * @return 解析失败返回 null
     */
    public static Info info(String vodId) {
        if (TextUtils.isEmpty(vodId)) return null;
        Info cached = sInfoCache.get(vodId);
        if (cached != null && System.currentTimeMillis() - cached.ts < INFO_TTL_MS) {
            return cached;
        }
        Long failAt = sFailAt.get(vodId);
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_COOLDOWN_MS) {
            return null;
        }

        Object lock = sLocks.get(vodId);
        if (lock == null) {
            synchronized (sLocks) {
                lock = sLocks.get(vodId);
                if (lock == null) {
                    lock = new Object();
                    sLocks.put(vodId, lock);
                }
            }
        }
        synchronized (lock) {
            cached = sInfoCache.get(vodId);
            if (cached != null && System.currentTimeMillis() - cached.ts < INFO_TTL_MS) {
                return cached;
            }
            int id;
            try {
                id = Integer.parseInt(vodId.trim());
            } catch (NumberFormatException e) {
                LogBridge.w(TAG, "vodId 非数字: " + vodId);
                return null;
            }
            JSONObject vodInfo = NbMovieApi.detail(id);
            if (vodInfo == null) {
                LogBridge.w(TAG, "详情抓取失败 vodId=" + vodId);
                sFailAt.put(vodId, System.currentTimeMillis());
                return null;
            }
            Info info = parseInfo(id, vodInfo);
            if (info != null && info.lineCount() > 0) {
                info.ts = System.currentTimeMillis();
                sInfoCache.put(vodId, info);
                LogBridge.i(TAG, "详情解析成功 vodId=" + vodId + " 标题=" + info.title
                        + " 线路=" + info.lineCount()
                        + " 首线集数=" + info.lines.get(0).eps.size());
                return info;
            }
            LogBridge.w(TAG, "详情解析：无 play_urls vodId=" + vodId);
            sFailAt.put(vodId, System.currentTimeMillis());
            return null;
        }
    }

    /** 从 {@code vod_info} 的 {@code play_urls} 构建 Info */
    private static Info parseInfo(int vodId, JSONObject vodInfo) {
        JSONArray playUrls = vodInfo.optJSONArray("play_urls");
        if (playUrls == null || playUrls.length() == 0) return null;
        Info info = new Info(vodId, vodInfo.optString("title", ""));
        try {
            for (int i = 0; i < playUrls.length(); i++) {
                JSONObject pu = playUrls.optJSONObject(i);
                if (pu == null) continue;
                String name = pu.optString("line_name", "");
                Line line = new Line(pu.optInt("line", i + 1),
                        TextUtils.isEmpty(name) ? ("线路" + (i + 1)) : name);
                JSONArray eps = pu.optJSONArray("episodes");
                if (eps != null) {
                    for (int j = 0; j < eps.length(); j++) {
                        JSONObject e = eps.optJSONObject(j);
                        if (e == null) continue;
                        int pid = e.optInt("pid", 0);
                        if (pid <= 0) continue;
                        line.eps.add(new Ep(pid, e.optString("name", ""), e.optInt("num", 0)));
                    }
                }
                if (!line.eps.isEmpty()) info.lines.add(line);
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "play_urls 解析失败: " + t.getMessage());
            return null;
        }
        return info.lineCount() > 0 ? info : null;
    }

    // ==================== 线路选择 ====================

    public static int getSelectedLine(String vodId) {
        Integer v = sSelectedLine.get(vodId);
        return v == null ? 0 : v;
    }

    /** 当前播放集下标（0 基）；未知返回 -1。 */
    public static int getCurrentEpisode(String vodId) {
        if (TextUtils.isEmpty(vodId)) return -1;
        Integer v = sCurEp.get(vodId);
        return v == null ? -1 : v;
    }

    /**
     * 【选集面板】直接置当前集下标。
     *
     * <p>{@link #resolve} 起播时才会写 sCurEp；用户在选集面板点某一集时解析尚未开始，
     * 若不在此同步置位，面板高亮与「正在播放 · 第N集」会停在上一集的数字上。
     */
    public static void setCurrentEpisode(String vodId, int idx) {
        if (TextUtils.isEmpty(vodId) || idx < 0) return;
        sCurEp.put(vodId, idx);
    }

    /** 指定线路的集数（供设置/线路面板展示）。 */
    public static int episodeCount(String vodId, int lineIdx) {
        Info info = sInfoCache.get(vodId);
        if (info == null) return 0;
        Line l = info.lineAt(lineIdx);
        return l == null ? 0 : l.eps.size();
    }

    public static void setSelectedLine(String vodId, int idx) {
        if (TextUtils.isEmpty(vodId) || idx < 0) return;
        sSelectedLine.put(vodId, idx);
        // 线路变了，已解析地址不再适用
        for (String key : new ArrayList<>(sUrlCache.keySet())) {
            if (key.startsWith(vodId + "@")) sUrlCache.remove(key);
        }
        LogBridge.i(TAG, "线路切换 vodId=" + vodId + " → 第" + (idx + 1) + "条");
    }

    /** 线路名列表（供线路面板；未联网抓到详情时返回空列表） */
    public static List<String> lineNames(String vodId) {
        List<String> out = new ArrayList<>();
        Info info = info(vodId);
        if (info == null) return out;
        for (int i = 0; i < info.lines.size(); i++) {
            Line l = info.lines.get(i);
            out.add("线路" + (i + 1) + " · " + l.lineName + "（" + l.eps.size() + "）");
        }
        return out;
    }

    // ==================== 集数（EPG 用） ====================

    /**
     * 取选集列表：按**当前选中线路**生成，{@code customPlayUrl=nbm://<vodId>@<line>-<ep>}。
     * 只读内存缓存，不联网（未就绪返回空列表，由 {@link #fetchEpisodes} 预热）。
     */
    public static List<Channel.EpgItem> getEpisodes(String vodId) {
        Info info = sInfoCache.get(vodId);
        if (info == null) return new ArrayList<>();
        return buildItems(info, getSelectedLine(vodId));
    }

    /** 联网抓一次并返回选集（供 EpgManager 后台预热） */
    public static List<Channel.EpgItem> fetchEpisodes(String vodId) {
        Info info = info(vodId);
        if (info == null) return new ArrayList<>();
        return buildItems(info, getSelectedLine(vodId));
    }

    private static List<Channel.EpgItem> buildItems(Info info, int lineIdx) {
        List<Channel.EpgItem> out = new ArrayList<>();
        Line line = info.lineAt(lineIdx);
        if (line == null) return out;
        for (int i = 0; i < line.eps.size(); i++) {
            Ep ep = line.eps.get(i);
            String label = !TextUtils.isEmpty(ep.name) ? ep.name : ("第" + ep.num + "集");
            Channel.EpgItem it = new Channel.EpgItem("今天", "", label, false);
            it.customPlayUrl = SCHEME + info.vodId + "@" + (lineIdx + 1) + "-" + (i + 1);
            out.add(it);
        }
        return out;
    }

    // ==================== 预取闸门（EPG 单飞 + 前景放行） ====================

    /** 占位成功返回 true（调用方才发起抓取）；已在抓取中返回 false。 */
    public static boolean beginFetch(String vodId) {
        if (TextUtils.isEmpty(vodId)) return false;
        return sFetching.putIfAbsent(vodId, new Object()) == null;
    }

    /** 释放占位。 */
    public static void endFetch(String vodId) {
        if (!TextUtils.isEmpty(vodId)) sFetching.remove(vodId);
    }

    public static void setForegroundSlug(String vodId) {
        sForegroundVodId = TextUtils.isEmpty(vodId) ? null : vodId;
    }

    /** 是否是「用户正在查看」的那部片（EPG 预取闸门放行条件之一） */
    public static boolean isForeground(String vodId) {
        return !TextUtils.isEmpty(vodId) && vodId.equals(sForegroundVodId);
    }

    // ==================== 起播解析 ====================

    /**
     * 解析 {@code nbm://...} 为真实 m3u8 直链。
     *
     * @param nbmUrl {@code nbm://<vodId>} 或 {@code nbm://<vodId>@<line>-<ep>}
     * @return m3u8；失败返回 null
     */
    public static String resolve(String nbmUrl) {
        String vodId = vodIdOfUrl(nbmUrl);
        if (TextUtils.isEmpty(vodId)) {
            LogBridge.w(TAG, "解析失败：无法提取 vodId，url=" + nbmUrl);
            return null;
        }

        int lineIdx = -1;
        int epIdx = -1;
        String rest = nbmUrl.substring(SCHEME.length());
        int at = rest.indexOf('@');
        if (at >= 0) {
            String[] p = rest.substring(at + 1).split("-");
            if (p.length >= 2) {
                try {
                    lineIdx = Integer.parseInt(p[0].trim()) - 1;
                    epIdx = Integer.parseInt(p[1].trim()) - 1;
                } catch (Throwable ignored) {
                }
            }
        }
        if (lineIdx < 0) lineIdx = getSelectedLine(vodId);
        if (epIdx < 0) epIdx = 0;

        String cacheKey = vodId + "@" + lineIdx + "-" + epIdx;
        String[] hit = sUrlCache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - parseLong(hit[1]) < URL_TTL_MS) {
            LogBridge.i(TAG, "命中解析缓存 " + cacheKey);
            sLastResolvedUrl = hit[0];
            return hit[0];
        }

        Info info = info(vodId);
        if (info == null) {
            LogBridge.w(TAG, "解析失败：详情无播放数据 vodId=" + vodId);
            return null;
        }
        Line line = info.lineAt(lineIdx);
        if (line == null && info.lineCount() > 0) line = info.lines.get(0);
        if (line == null || line.eps.isEmpty()) {
            LogBridge.w(TAG, "解析失败：线路为空 vodId=" + vodId);
            return null;
        }
        if (epIdx >= line.eps.size()) epIdx = line.eps.size() - 1;
        Ep ep = line.eps.get(epIdx);
        if (ep.pid <= 0) {
            LogBridge.w(TAG, "解析失败：pid 非法 vodId=" + vodId);
            return null;
        }
        int num = ep.num > 0 ? ep.num : (epIdx + 1);

        JSONObject play = NbMovieApi.play(ep.pid, info.vodId, num, "1080");
        if (play == null) {
            LogBridge.w(TAG, "解析失败：play 无数据 vodId=" + vodId + " pid=" + ep.pid);
            return null;
        }
        String url = pickQualityUrl(play);
        if (TextUtils.isEmpty(url)) {
            LogBridge.w(TAG, "解析失败：quality_urls 为空 vodId=" + vodId + " pid=" + ep.pid);
            return null;
        }

        // 记录当前集下标，供控制栏「上一集/下一集」定位
        sCurEp.put(vodId, epIdx);
        sUrlCache.put(cacheKey, new String[]{url, String.valueOf(System.currentTimeMillis())});
        sLastResolvedUrl = url;
        LogBridge.i(TAG, "解析成功 " + cacheKey + " -> " + url);
        return url;
    }

    /**
     * 清晰度择优（对照原端 {@code PlayUrlData.currentUrl()}）：
     * {@code current_quality} 是 {@code quality_urls} 下标，命中且未锁直接用；
     * 否则首个未锁非 VIP；再否则首个。
     */
    private static String pickQualityUrl(JSONObject play) {
        JSONArray qurls = play.optJSONArray("quality_urls");
        if (qurls == null || qurls.length() == 0) return null;
        int cur = play.optInt("current_quality", -1);
        if (cur >= 0 && cur < qurls.length()) {
            JSONObject q = qurls.optJSONObject(cur);
            if (q != null) {
                String u = q.optString("url", "");
                boolean locked = q.optBoolean("locked", false) || q.optBoolean("isvip", false);
                if (!u.isEmpty() && !locked) return u;
            }
        }
        for (int i = 0; i < qurls.length(); i++) {
            JSONObject q = qurls.optJSONObject(i);
            if (q == null) continue;
            if (q.optBoolean("locked", false) || q.optBoolean("isvip", false)) continue;
            String u = q.optString("url", "");
            if (!u.isEmpty()) return u;
        }
        JSONObject first = qurls.optJSONObject(0);
        return first == null ? null : first.optString("url", "");
    }

    /**
     * 【2026-10-22 源2·失效自动换线】当前线路播放彻底失败后调用：
     * 从当前线路轮转遍历该片所有线路，逐条 resolve 探活，返回第一个可用的
     * <b>待解析地址</b>（保持当前集；集数超出新线路范围时由 {@link #resolve} 夹取末集）。
     *
     * <p>与手动切线路（{@link #setSelectedLine}）的区别：手动切只改选区不探活；
     * 本方法在后台逐条试解析，成功才切换并返回，避免盲跳到另一条死线。
     * 调用方（TVPlayerManager.autoRetry 兜底链）已在入口作废本片缓存，
     * 这里的 {@link #info(String)} 必然联网重抓最新线路表。
     *
     * @param nbmUrl 当前播放的 {@code nbm://<vodId>} 或 {@code nbm://<vodId>@<line>-<ep>}
     * @return 可播的 {@code nbm://<vodId>@<新线路>-<当前集>}；无可换线路/全部失败返回 null
     */
    public static String tryNextLineAuto(String nbmUrl) {
        if (TextUtils.isEmpty(nbmUrl)) return null;
        String vodId = vodIdOfUrl(nbmUrl);
        if (TextUtils.isEmpty(vodId)) return null;

        // 当前线路/当前集：优先取 URL 显式携带值（EPG 切集时带 @line-ep），否则回落选区/记忆集
        int cur = getSelectedLine(vodId);
        int ep = getCurrentEpisode(vodId);
        if (ep < 0) ep = 0;
        String rest = nbmUrl.substring(SCHEME.length());
        int at = rest.indexOf('@');
        if (at >= 0) {
            String[] p = rest.substring(at + 1).split("-");
            if (p.length >= 2) {
                try {
                    int line = Integer.parseInt(p[0].trim()) - 1;
                    int epv = Integer.parseInt(p[1].trim()) - 1;
                    if (line >= 0) cur = line;
                    if (epv >= 0) ep = epv;
                } catch (Throwable ignored) {
                }
            }
        }

        Info info = info(vodId);
        if (info == null || info.lineCount() <= 1) {
            LogBridge.w(TAG, "失效换线：无其他线路可换 vodId=" + vodId
                    + (info == null ? "（详情获取失败）" : "（仅 " + info.lineCount() + " 条）"));
            return null;
        }
        int size = info.lineCount();
        for (int step = 1; step < size; step++) {
            int idx = (cur + step) % size;
            String raw = SCHEME + vodId + "@" + (idx + 1) + "-" + (ep + 1);
            LogBridge.i(TAG, "失效换线：试第" + (idx + 1) + "条线路 vodId=" + vodId + " → " + raw);
            String url = resolve(raw);
            if (!TextUtils.isEmpty(url)) {
                // 直接写选区（不清缓存：刚解析出的新 key 要保留，供同片后续集复用）
                sSelectedLine.put(vodId, idx);
                LogBridge.i(TAG, "失效换线成功 vodId=" + vodId + " 第" + (cur + 1)
                        + "条 → 第" + (idx + 1) + "条");
                return raw;
            }
            LogBridge.w(TAG, "失效换线：第" + (idx + 1) + "条线路不可用 vodId=" + vodId);
        }
        LogBridge.w(TAG, "失效换线失败：所有线路均不可用 vodId=" + vodId);
        return null;
    }

    /** 播放失败时作废本片的地址与详情缓存 */
    public static void invalidateByUrl(String nbmUrl) {
        String vodId = vodIdOfUrl(nbmUrl);
        if (TextUtils.isEmpty(vodId)) return;
        for (String key : new ArrayList<>(sUrlCache.keySet())) {
            if (key.startsWith(vodId + "@")) sUrlCache.remove(key);
        }
        sInfoCache.remove(vodId);
        sFailAt.remove(vodId);
        LogBridge.i(TAG, "已作废缓存 vodId=" + vodId);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
