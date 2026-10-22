package com.tv.live.afhgrj;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import okhttp3.Response;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置源7（afhgrj 影视点播）频道表加载器。
 *
 * <p>抓取 5 个导航分组（首页 1 页 + 4 个分类各 2 页，共 9 页），把每部影片拼成一个「频道」：
 * M3U 里地址写作自定义协议 {@code afhgrj://<vodId>}，真正取流在起播那一刻由
 * {@link AfhgrjUrlResolver} 惰性完成（见 TVPlayerManager#doPlay）。
 *
 * <p>分组与入口（MacCMS 站点）：
 * <pre>
 *   首页   /
 *   电影   /y/1/
 *   电视剧 /y/2/
 *   综艺   /y/3/
 *   动漫   /y/4/
 * </pre>
 *
 * <p>条目提取正则：{@code href="…/yd/(\d+)/…" title="…《标题》…"}（站点详情链接形如
 * {@code <a href="/yd/174008/" title="《火柴盒大电影》完整版">}）。
 *
 * <p>失败返回 {@code null}，由 {@code LiveSourceLoader} 走既有 fallback 链。
 */
public final class AfhgrjSourceLoader {

    private static final String TAG = "AfhgrjSourceLoader";

    private static final String BASE = "https://www.afhgrj.com";

    /** 移动端 UA（站点对桌面 UA 的排版不同，用移动端更稳定） */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * 分组：每一组待抓取的页面 URL 列表。
     * 【2026-10-11】应要求恢复为「分类各抓 2 页」，扩充频道量：首页 1 页 + 4 个分类各 2 页 = 共 9 个 GET。
     * 顺序即 M3U 中的展示顺序。MacCMS 分类页第 N 页形如 {@code /y/{catId}-{N}.html}。
     * 注：抓取按「页深轮转」（先各组第 1 页、再各组第 2 页），被 {@link #FETCH_DEADLINE_MS}
     * 截断时仍能覆盖全部 5 个分组；9 页串行实测约 19.2s，25s 截止有余量。
     */
    private static final String[][] GROUPS = {
            {"首页",   BASE + "/"},
            {"电影",   BASE + "/y/1/",  BASE + "/y/1-2.html"},
            {"电视剧", BASE + "/y/2/",  BASE + "/y/2-2.html"},
            {"综艺",   BASE + "/y/3/",  BASE + "/y/3-2.html"},
            {"动漫",   BASE + "/y/4/",  BASE + "/y/4-2.html"},
    };

    /** 分组抓取之间的最小间隔（毫秒），串行限速 */
    private static final long GROUP_INTERVAL_MS = 150L;

    /** 磁盘缓存文件（避免每次切源都重新抓 9 个页面） */
    private static final String CACHE_FILE = "afhgrj_source.m3u";
    /** 缓存有效期：6 小时 */
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;

    // ------------------------------------------------------------------
    // 【2026-10-11 源7降级加固】抓取超时上限 / 陈旧缓存快路径 / 失败冷却
    //   背景：站点降级（HTTPS 侧 520/挂起）时，旧实现串行等完所有页面才返回，
    //         最坏 2.5~5 分钟卡住频道表。以下四项把最坏耗时收敛到秒级。
    // ------------------------------------------------------------------

    /**
     * 单次同步抓取的整体截止时间（毫秒）：到点即用已抓到的部分结果返回，绝不空等。
     *
     * <p>【2026-10-10 源7 perf4 实测校准 / 2026-10-11 恢复 2 页】站点健康时单页耗时 1.0~3.4s，
     * 9 页串行（含组间 150ms 间隔）实测约 19.2s，25s 截止时间留出约 30% 余量。该截止时间只用于
     * 兜住「站点挂起」的病态情况，<b>不应截断一次完全健康的抓取</b>（原先 8000ms 会在第 4 页就把
     * 健康抓取掐断，导致综艺/动漫整组丢失）。配合「按页深轮转」抓取顺序，即便真被截断也覆盖全部 5 个分组。
     */
    private static final long FETCH_DEADLINE_MS = 25000L;

    /** afhgrj 专用短超时：连接 3s / 读取 4s（仅本次请求生效，不改全局 NetUtil 行为） */
    private static final long FETCH_CONNECT_MS = 3000L;
    private static final long FETCH_READ_MS = 4000L;

    /** 抓取失败冷却期：5 分钟。冷却期内：有缓存则直接用缓存不联网；无缓存则直接失败返回不阻塞。 */
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;

    /** 上次抓取失败（一次尝试结束且 total==0）的时刻（0 表示从未失败；无论当时有无磁盘缓存） */
    private static volatile long sLastFailAtMs = 0;

    /** 后台刷新 in-flight 守卫：同一时刻至多一个后台刷新在跑 */
    private static final AtomicBoolean sRefreshing = new AtomicBoolean(false);

    /** 后台刷新单线程执行器（守护线程；进程退出不阻塞） */
    private static final ExecutorService sRefreshExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "afhgrj-refresh");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** 详情链接 + 标题（标题取书名号内文本；首页/分类页两种排版均适用） */
    private static final Pattern VOD_PATTERN = Pattern.compile(
            "href=\"[^\"]*?/yd/(\\d+)/[^\"]*\"[^>]*title=\"[^\"]*?《([^》]+)》");

    /** 【2026-10-11 海报】封面缩略图：{@code <a ... href="/yd/<vodId>/" ... data-original="POSTER">} */
    private static final Pattern VOD_THUMB_PATTERN = Pattern.compile(
            "href=\"[^\"]*?/yd/(\\d+)/[^\"]*\"[^>]*?data-original=\"([^\"]+)\"");

    private AfhgrjSourceLoader() {}

    // ------------------------------------------------------------------
    // 【2026-10-11 海报】vodId → 海报URL 的静态映射
    //   构建 M3U 时填充；供 LiveSourceLoader 解析出 Channel 后直接 setLogoUrl
    //   （像虎牙一样 Channel 直达 OTT，不依赖缓存 M3U 文本里的 tvg-logo）。
    //   注意：会被后台刷新线程写（rememberPoster）、被解析线程读（posterOf），
    //   必须用 ConcurrentHashMap 保证并发 put/get 安全。
    // ------------------------------------------------------------------
    private static final Map<String, String> POSTER_BY_VOD = new ConcurrentHashMap<>();

    /** 记录/更新某影片的海报 URL */
    private static void rememberPoster(String vodId, String poster) {
        if (vodId == null || vodId.isEmpty()) return;
        if (poster != null && !poster.isEmpty()) POSTER_BY_VOD.put(vodId, poster);
    }

    /** 查询某影片的海报 URL；未知返回 null。
     *  【2026-10-11 海报镜像】出口统一做主机重写，保证 Channel 直达路径也用可达域名。 */
    public static String posterOf(String vodId) {
        return vodId == null ? null : applyHostMirror(POSTER_BY_VOD.get(vodId));
    }

    /**
     * 拉取 5 个分组的影片列表并拼成 M3U 文本（供 {@code PlaylistParser.parseContent} 复用）。
     * 该方法会阻塞网络，必须在 IO 线程调用。
     *
     * @return M3U 文本；全部来源都失败时返回 {@code null}
     */
    public static String loadAllLiveAsM3u(Context context) {
        String cached = readDiskCache(context);

        // ① 陈旧缓存快路径（stale-while-revalidate）：只要有磁盘缓存——即使是过期的——
        //    就立即返回旧内容，本次跳过联网抓取；过期时再异步触发一次后台刷新。
        if (!TextUtils.isEmpty(cached)) {
            if (isCacheExpired(context)) {
                if (isInFailCooldown()) {
                    // ④ 冷却期内且有缓存：直接用缓存，不联网、也不再触发后台刷新
                    LogBridge.i(TAG, "源7(影视)：抓取失败冷却期内，直接使用磁盘缓存，不联网");
                } else {
                    LogBridge.i(TAG, "源7(影视)：磁盘缓存已过期，先用缓存返回并异步刷新");
                    triggerBackgroundRefresh(context);
                }
            } else {
                LogBridge.i(TAG, "源7(影视)：命中磁盘缓存，跳过抓取");
            }
            // 【2026-10-11 海报镜像】缓存里可能残留不可达图床域名（如 img.picbf.com），
            // 该路径不经过 normalizePoster，故在这里对整段 M3U 文本做一次主机重写。
            return rewritePosterHosts(cached);
        }

        // ② 完全没有磁盘缓存且处于失败冷却期：不走同步（阻塞）抓取，直接失败返回，
        //    让上层快速报错，避免每次切源都空等 8~15 秒。
        if (isInFailCooldown()) {
            LogBridge.w(TAG, "源7(影视)：无磁盘缓存且处于抓取失败冷却期内，跳过同步抓取");
            return null;
        }

        // ③ 完全没有磁盘缓存且非冷却期，才走同步（阻塞）抓取路径
        return fetchAndCacheBlocking(context);
    }

    /**
     * 同步抓取全部分组页面并写缓存。
     *
     * <p>受 {@link #FETCH_DEADLINE_MS} 整体截止时间约束：到点即跳出分组循环，<b>用已经抓到的
     * 部分结果继续（部分成功）</b>，不返回 null。仅在完全没有磁盘缓存时被调用。
     */
    private static String fetchAndCacheBlocking(Context context) {
        // ③ 记录进入本次抓取前是否已有磁盘缓存（放在抓取之前，避免读到本次刚写的结果）。
        //    用于：被 deadline 截断时，若无旧缓存则仍写入部分结果（有总比没有强，下次能秒开）；
        //    有旧缓存则跳过写入，保住更完整的旧缓存。
        boolean hadExistingCache = !TextUtils.isEmpty(readDiskCache(context));

        // ② 整体截止时间起点
        long deadline = System.currentTimeMillis() + FETCH_DEADLINE_MS;
        // ② 本次抓取是否因截止时间被截断（部分结果）；被截断时不得覆盖磁盘缓存
        boolean hitDeadline = false;

        final int groupCount = GROUPS.length;
        final java.util.List<Map<String, String[]>> perGroup = new java.util.ArrayList<>(groupCount);
        final int[] pageOkByGroup = new int[groupCount];
        for (int i = 0; i < groupCount; i++) perGroup.add(new LinkedHashMap<String, String[]>());

        // ★ 抓取顺序 = 按页深轮转：先抓各组第 1 页，再抓各组第 2 页……
        //   保证被 FETCH_DEADLINE_MS 截断时，已抓到的内容也覆盖全部 5 个分组。
        int maxPages = 0;
        for (String[] g : GROUPS) maxPages = Math.max(maxPages, g.length - 1);

        boolean first = true;
        groupsLoop:
        for (int pi = 1; pi <= maxPages; pi++) {
            for (int gi = 0; gi < groupCount; gi++) {
                String[] group = GROUPS[gi];
                if (pi >= group.length) continue;
                String gname = group[0];
                String url = group[pi];

                if (System.currentTimeMillis() > deadline) {
                    hitDeadline = true;
                    LogBridge.w(TAG, "源7(影视)：已达整体截止时间 " + FETCH_DEADLINE_MS
                            + "ms，提前结束抓取，使用部分结果");
                    break groupsLoop;
                }
                if (!first) sleep(GROUP_INTERVAL_MS);
                first = false;

                String html = getText(url);
                if (TextUtils.isEmpty(html)) {
                    LogBridge.w(TAG, "源7(影视)：分组[" + gname + "]第" + pi + "页抓取失败 url=" + url);
                    continue;
                }
                Map<String, String[]> items = extractVods(html);
                if (items.isEmpty()) {
                    LogBridge.w(TAG, "源7(影视)：分组[" + gname + "]第" + pi + "页未解析到条目 url=" + url);
                    continue;
                }
                pageOkByGroup[gi]++;
                Map<String, String[]> dst = perGroup.get(gi);
                for (Map.Entry<String, String[]> e : items.entrySet()) {
                    // 组内跨页去重（同片出现在最新页与第2页时保留首次）、跨组天然分开
                    if (!dst.containsKey(e.getKey())) dst.put(e.getKey(), e.getValue());
                    // 【2026-10-11 海报】填充静态海报映射，供解析出 Channel 后直接 setLogoUrl
                    String[] v = e.getValue();
                    if (v != null && v.length > 1) rememberPoster(e.getKey(), v[1]);
                }
            }
        }

        // 按 GROUPS 原顺序组装 M3U（展示顺序不变）
        StringBuilder sb = new StringBuilder(2048);
        sb.append("#EXTM3U\n");
        int total = 0;
        int groupOk = 0;
        for (int gi = 0; gi < groupCount; gi++) {
            String gname = GROUPS[gi][0];
            Map<String, String[]> groupItems = perGroup.get(gi);
            if (groupItems.isEmpty()) continue;
            groupOk++;
            for (Map.Entry<String, String[]> e : groupItems.entrySet()) {
                String vodId = e.getKey();
                String name = e.getValue()[0];
                String poster = e.getValue()[1];
                sb.append("#EXTINF:-1 tvg-id=\"").append(AfhgrjUrlResolver.TVG_PREFIX).append(vodId)
                  .append("\" tvg-logo=\"").append(esc(poster)).append("\"")
                  .append(" group-title=\"").append(esc(gname))
                  .append("\",").append(esc(name)).append("\n");
                sb.append(AfhgrjUrlResolver.SCHEME).append(vodId).append("\n");
                total++;
            }
            LogBridge.i(TAG, "源7(影视)：分组[" + gname + "]抓到 " + groupItems.size()
                    + " 部（" + pageOkByGroup[gi] + "/" + (GROUPS[gi].length - 1) + " 页）");
        }

        if (total == 0) {
            // ④ 一次抓取尝试结束且 total==0：无条件记录失败冷却起点（无论是否有磁盘缓存可兜底），
            //    否则「有缓存 + 后台刷新失败」这一最常见情形永远不会触发冷却。
            sLastFailAtMs = System.currentTimeMillis();
            LogBridge.w(TAG, "源7(影视)：无任何可用频道表");
            return null;
        }

        String m3u = sb.toString();
        if (!hitDeadline) {
            // ③a 完整结果：照常写缓存
            writeDiskCache(context, m3u);
        } else if (!hadExistingCache) {
            // ③a 被截止时间截断，但本次抓取前本就没有任何旧缓存：写入部分结果，
            //     避免下次切回源7 又要从零重抓（有部分总比什么都没有强，下次能秒开）。
            LogBridge.w(TAG, "源7(影视)：抓取被截止时间截断（部分结果），无旧缓存兜底，写入部分结果");
            writeDiskCache(context, m3u);
        } else {
            // ③a 被截止时间截断且已有更完整的旧缓存：跳过写入，保住旧缓存
            LogBridge.w(TAG, "源7(影视)：抓取被截止时间截断（部分结果），跳过写磁盘缓存以免覆盖旧缓存");
        }
        LogBridge.i(TAG, "源7(影视)：构建完成，共 " + total + " 部，成功分组 "
                + groupOk + "/" + GROUPS.length
                + (hitDeadline ? "（部分结果，已被截止时间截断）" : ""));
        return m3u;
    }

    // ------------------------------------------------------------------
    // 【2026-10-11 源7降级加固】后台刷新 / 失败冷却
    // ------------------------------------------------------------------

    /**
     * 异步触发一次后台刷新（陈旧缓存快路径的 revalidate 半）。
     *
     * <p>{@link #sRefreshing} 做 in-flight 守卫，保证同一时刻只有一个后台刷新在跑；
     * 刷新线程为守护线程，抓取仍严格串行（单线程执行器），绝不并发。
     */
    private static void triggerBackgroundRefresh(final Context context) {
        if (context == null) return;
        if (!sRefreshing.compareAndSet(false, true)) {
            LogBridge.i(TAG, "源7(影视)：后台刷新已在进行，跳过重复触发");
            return;
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            sRefreshExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        LogBridge.i(TAG, "源7(影视)：后台刷新开始（陈旧缓存快路径触发）");
                        fetchAndCacheBlocking(appCtx);
                    } catch (Throwable t) {
                        LogBridge.w(TAG, "源7(影视)：后台刷新异常 " + t.getClass().getSimpleName()
                                + ": " + t.getMessage());
                    } finally {
                        sRefreshing.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            sRefreshing.set(false);
            LogBridge.w(TAG, "源7(影视)：后台刷新调度失败 " + t.getMessage());
        }
    }

    /** 是否处于抓取失败冷却期内（最近一次抓取失败至今 < {@link #FAIL_COOLDOWN_MS}） */
    private static boolean isInFailCooldown() {
        long last = sLastFailAtMs;
        return last > 0 && (System.currentTimeMillis() - last) < FAIL_COOLDOWN_MS;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 从分类页/首页 HTML 提取 {@code vodId → [标题, 海报URL]}（同页去重，保留首次出现的标题与海报） */
    private static Map<String, String[]> extractVods(String html) {
        // ① 标题：任一带 《》 标签的详情链接（首页/分类页两种排版均适用），vodId→标题
        Map<String, String> titles = new LinkedHashMap<>();
        Matcher m = VOD_PATTERN.matcher(html);
        while (m.find()) {
            String vodId = m.group(1);
            String title = m.group(2) == null ? "" : m.group(2).trim();
            if (vodId == null || vodId.isEmpty() || title.isEmpty()) continue;
            if (!titles.containsKey(vodId)) titles.put(vodId, title);
        }
        // ② 海报：封面缩略图 {@code <a href="/yd/<vodId>/" data-original="POSTER">}（stui-vodlist 模板）
        Map<String, String> posters = new LinkedHashMap<>();
        Matcher mt = VOD_THUMB_PATTERN.matcher(html);
        while (mt.find()) {
            String vodId = mt.group(1);
            String poster = normalizePoster(mt.group(2));
            if (vodId == null || vodId.isEmpty() || poster.isEmpty()) continue;
            if (!posters.containsKey(vodId)) posters.put(vodId, poster);
        }
        // ③ 合并：只保留有标题的影片；海报缺失则留空（空 tvg-logo → 卡片回退占位图）
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String vodId : titles.keySet()) {
            String poster = posters.containsKey(vodId) ? posters.get(vodId) : "";
            out.put(vodId, new String[]{titles.get(vodId), poster});
        }
        return out;
    }

    /**
     * 【2026-10-11 海报镜像】图床域名重写表：不可达主机 → 可达镜像主机。
     *
     * <p>实测（emulator-5554，单次串行 curl，共 30+ 次请求）：
     * <ol>
     *   <li>{@code img.picbf.com} 的 TLS 握手被 RST（HTTP 000），而 {@code p.bfvp26.com}
     *       与它<b>同属 CDN {@code bf1.abfedgecanme.com}</b>、<b>路径完全通用</b>——
     *       用 picbf 的原始路径请求 {@code p.bfvp26.com} 连续 4/4 返回 200。</li>
     *   <li>{@code feifeiimg} 系三台（img.feifeiimg.vip / tupian.ffeiimg.com / pps.vodfeiss.com）
     *       全部 RST，但在 {@code fffgood.com} 上用<b>完全相同的路径</b>请求
     *       <b>15/15 全部返回 200</b>（19KB~226KB 真实图片）→ {@code fffgood.com}
     *       是全系通用镜像。</li>
     * </ol>
     * 故重写如下表，可恢复本项目全部海报（约 86 张受影响的图）。
     *
     * <p>维护提示：镜像域名随时可能变化；若某主机再次大面积失败，用设备 curl 重新
     * 交叉验证「原路径 + 候选镜像域」是否 200，再更新本表。
     */
    private static final String[][] POSTER_HOST_MIRROR = {
            // {不可达主机, 可达镜像主机}   —— 镜像域上路径与源域完全一致
            {"img.picbf.com", "p.bfvp26.com"},
            {"img.feifeiimg.vip", "fffgood.com"},
            {"tupian.ffeiimg.com", "fffgood.com"},
            {"pps.vodfeiss.com", "fffgood.com"},
    };

    /** 【2026-10-11 海报镜像】对海报 URL 做「不可达主机 → 可达镜像」重写；无匹配则原样返回。 */
    private static String applyHostMirror(String url) {
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
            return url.substring(0, hostStart) + to + rest;
        }
        return url;
    }

    /**
     * 【2026-10-11 海报镜像】对整段 M3U 文本做主机重写。
     * 用于「磁盘缓存快路径」——该路径直接返回缓存的 M3U 原文，不经过 {@link #normalizePoster}，
     * 若不处理，缓存里残留的不可达图床域名（如 {@code img.picbf.com}）会继续导致卡片空白。
     */
    private static String rewritePosterHosts(String m3u) {
        if (m3u == null || m3u.isEmpty()) return m3u;
        String out = m3u;
        for (String[] pair : POSTER_HOST_MIRROR) {
            out = out.replace("://" + pair[0] + "/", "://" + pair[1] + "/");
        }
        return out;
    }

    /** 【2026-10-11 海报镜像】{@link #rewritePosterHosts} 的公开入口，供 AppCoreManager 缓存解析路径复用。 */
    public static String rewritePosterHostsPublic(String m3u) {
        return rewritePosterHosts(m3u);
    }

    /**
     * 海报 URL 归一化：
     * <ul>
     *   <li>站点图片代理相对路径 {@code /img.php?url=<真实图URL>} → 解出真实图 URL（如豆瓣图床，需 Referer）</li>
     *   <li>其他相对路径 → 补全站点域名</li>
     *   <li>已是绝对 URL → 按镜像表重写不可达主机，再原样返回</li>
     * </ul>
     */
    private static String normalizePoster(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        if (p.isEmpty()) return "";
        if (p.startsWith("/")) {
            int idx = p.indexOf("url=");
            if (p.startsWith("/img.php") && idx >= 0) {
                String inner = p.substring(idx + "url=".length());
                try {
                    inner = java.net.URLDecoder.decode(inner, "UTF-8");
                } catch (Exception ignored) {
                    // 解码失败则用原串
                }
                if (inner.startsWith("http")) return applyHostMirror(inner);
            }
            return BASE + p;
        }
        return applyHostMirror(p);
    }

    /** M3U 属性值转义；顺便把逗号替换掉，避免破坏 {@code #EXTINF:...,频道名} 的解析 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\"", "'")
                .replace(",", " ")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private static String getText(String url) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", BASE + "/");
            try (Response resp = NetUtil.getInstance()
                    .syncGetWithHeaders(url, headers, FETCH_CONNECT_MS, FETCH_READ_MS)) {
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

    // ------------------------------------------------------------------
    // 磁盘缓存
    // ------------------------------------------------------------------

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE);
    }

    private static boolean isCacheExpired(Context context) {
        File f = cacheFile(context);
        if (!f.exists()) return true;
        return System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS;
    }

    private static String readDiskCache(Context context) {
        if (context == null) return "";
        File f = cacheFile(context);
        if (!f.exists() || f.length() <= 0) return "";
        FileInputStream fis = null;
        BufferedReader br = null;
        try {
            fis = new FileInputStream(f);
            br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            LogBridge.w(TAG, "读缓存失败：" + t.getMessage());
            return "";
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
            if (fis != null) { try { fis.close(); } catch (IOException ignored) {} }
        }
    }

    private static void writeDiskCache(Context context, String content) {
        if (context == null || TextUtils.isEmpty(content)) return;
        // ③b 原子写：先写同目录临时文件，flush 后 renameTo 覆盖目标文件，
        //     避免另一线程 readDiskCache 读到半截文件而误判「无缓存」→ 触发并发抓取。
        File target = cacheFile(context);
        // ③c 唯一临时名：nanoTime + 线程ID，避免并发抓取（后台刷新与同步抓取重叠）写同一 tmp
        //     导致内容交错、rename 后落下损坏的 M3U。失败/异常分支删除的始终是本次自己的 tmp。
        File tmp = new File(target.getParentFile(),
                target.getName() + ".tmp." + System.nanoTime() + "." + Thread.currentThread().getId());
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            fos = null;
            if (!tmp.renameTo(target)) {
                LogBridge.w(TAG, "写缓存 rename 失败，删除临时文件");
                if (!tmp.delete()) {
                    LogBridge.w(TAG, "删除临时文件失败：" + tmp.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "写缓存失败：" + t.getMessage());
            if (tmp.exists() && !tmp.delete()) {
                LogBridge.w(TAG, "删除临时文件失败：" + tmp.getAbsolutePath());
            }
        } finally {
            if (fos != null) { try { fos.close(); } catch (IOException ignored) {} }
        }
    }
}
