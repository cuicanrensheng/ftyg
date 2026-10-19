package com.tv.live.ryb;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.M3uDiskCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【2026-10-19 内置源8（renyibo）】频道表加载器 —— <b>结构照源7（Afhgrj）做</b>。
 *
 * <p>与源7 一致的三个约定：
 * <ol>
 *   <li>M3U 里地址写自定义协议 {@code ryb://<slug>}，**惰性解析**（起播那一刻才取流）；</li>
 *   <li>列表页是服务端直出的 HTML，正则提卡片即可，**不起 WebView**；</li>
 *   <li>磁盘缓存走公共 {@link M3uDiskCache}（files/ + SWR + 失败冷却 + 原子写）。</li>
 * </ol>
 *
 * <p>分组（用户 2026-10-19 指定 8 组，type id 取自站点导航）：
 * <pre>
 *   电影科幻片 13 · 电影恐怖片 14 · 电视剧国产剧 20 · 电视剧欧美剧 26
 *   综艺大陆综艺 30 · 动漫国产动漫 40 · 动漫日韩动漫 42 · 短剧漫剧 50
 * </pre>
 */
public final class RybSourceLoader {

    private static final String TAG = "RybSourceLoader";

    private static final String BASE = "https://www.renyibo.com";
    private static final String CACHE_FILE = "ryb8_source.m3u";
    private static final long CACHE_TTL_MS = 12L * 60L * 60L * 1000L;
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;

    /** 每个分组抓几页（1 页 20 部） */
    private static final int PAGES_PER_GROUP = 2;
    /** 并行度上限：同一站点同时最多 3 个请求 */
    private static final int FETCH_PARALLEL = 3;

    /** {分组名, type id} */
    private static final String[][] GROUPS = {
            {"电影科幻片", "13"},
            {"电影恐怖片", "14"},
            {"电视剧国产剧", "20"},
            {"电视剧欧美剧", "26"},
            {"综艺大陆综艺", "30"},
            {"动漫国产动漫", "40"},
            {"动漫日韩动漫", "42"},
            {"短剧漫剧", "50"},
    };

    /** 卡片块分隔 */
    private static final String CARD_MARK = "class=\"video-card\"";
    private static final Pattern P_HREF = Pattern.compile("href=\"(/video/[^\"]+)\"");
    private static final Pattern P_POSTER = Pattern.compile("<img[^>]+src=\"([^\"]+)\"");
    private static final Pattern P_TITLE =
            Pattern.compile("<div class=\"card-title\">([\\s\\S]*?)</div>");

    private static final M3uDiskCache sCache = new M3uDiskCache(
            TAG, "源8(renyibo)", CACHE_FILE, CACHE_TTL_MS, FAIL_COOLDOWN_MS,
            new M3uDiskCache.Fetcher() {
                @Override
                public String fetch() {
                    return buildM3u();
                }
            });

    private RybSourceLoader() {
    }

    /**
     * 加载 8 个分组，返回 M3U 文本（内部走磁盘缓存 + SWR）。
     */
    public static String loadAllLiveAsM3u(Context context) {
        return sCache.load(context);
    }

    /** 列表页地址：{@code /type/<id>/pg/<page>/} */
    private static String listUrl(String typeId, int page) {
        return BASE + "/type/" + typeId + "/pg/" + page + "/";
    }

    /**
     * 抓 8 个分组并组装 M3U。
     *
     * <p>并行但有上限（3 个在飞），**结果按 GROUPS 原顺序落盘**，展示顺序与配置一致。
     */
    private static String buildM3u() {
        final StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        final int totalTasks = GROUPS.length * PAGES_PER_GROUP;

        ExecutorService pool = Executors.newFixedThreadPool(FETCH_PARALLEL, r -> {
            Thread t = new Thread(r, "ryb8-fetch");
            t.setDaemon(true);
            return t;
        });

        try {
            // 提交所有 (分组 × 页) 任务
            List<List<Future<List<Card>>>> futuresByGroup = new ArrayList<>();
            for (String[] g : GROUPS) {
                List<Future<List<Card>>> fs = new ArrayList<>();
                for (int p = 1; p <= PAGES_PER_GROUP; p++) {
                    final String url = listUrl(g[1], p);
                    fs.add(pool.submit(() -> parseCards(url)));
                }
                futuresByGroup.add(fs);
            }

            int total = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int gi = 0; gi < GROUPS.length; gi++) {
                String gname = GROUPS[gi][0];
                List<Card> cards = new ArrayList<>();
                for (Future<List<Card>> f : futuresByGroup.get(gi)) {
                    try {
                        List<Card> part = f.get(30, TimeUnit.SECONDS);
                        if (part != null && !part.isEmpty()) cards.addAll(part);
                    } catch (Throwable t) {
                        LogBridge.w(TAG, "分组[" + gname + "]抓取异常 " + t.getClass().getSimpleName());
                    }
                }
                int before = total;
                for (Card c : cards) {
                    if (c.slug == null || seen.contains(c.slug)) continue;
                    seen.add(c.slug);
                    int vid = RybUrlResolver.videoIdOf(c.slug);
                    String tvgId = RybUrlResolver.TVG_PREFIX + c.slug;
                    m3u.append("#EXTINF:-1 tvg-id=\"").append(tvgId).append("\"");
                    if (!TextUtils.isEmpty(c.poster)) {
                        m3u.append(" tvg-logo=\"").append(c.poster).append("\"");
                    }
                    m3u.append(" group-title=\"").append(gname).append("\"");
                    m3u.append(",").append(TextUtils.isEmpty(c.title) ? c.slug : c.title);
                    m3u.append("\n");
                    m3u.append(RybUrlResolver.SCHEME).append(c.slug).append("\n");
                    total++;
                }
                LogBridge.i(TAG, "分组[" + gname + "]抓到 " + (total - before) + " 部");
            }

            if (total == 0) {
                LogBridge.w(TAG, "源8(renyibo)：无任何可用频道");
                return null;
            }
            LogBridge.i(TAG, "源8(renyibo)：共抓到 " + total + " 部（任务 " + totalTasks + " 个）");
            return m3u.toString();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 列表页里的条目 */
    private static final class Card {
        String slug;
        String title;
        String poster;
    }

    /**
     * 解析一个列表页。卡片形如：
     * <pre>{@code
     * <div class="video-card">
     *   <a class="card-link" href="/video/%E8%A7%A3%E7%A0%81%E5%99%A8-12803/">
     *     <div class="card-thumb"><img src="https://img...jpg" alt="解码器 科幻片 在线观看">
     *       ...
     *     <div class="card-body"><div class="card-title">解码器</div>
     * }</pre>
     */
    private static List<Card> parseCards(String url) {
        List<Card> out = new ArrayList<>();
        String html = RybHttp.get(url);
        if (TextUtils.isEmpty(html)) return out;

        String[] blocks = html.split(CARD_MARK);
        for (int i = 1; i < blocks.length; i++) {
            String b = blocks[i];
            Matcher mh = P_HREF.matcher(b);
            if (!mh.find()) continue;
            String href = mh.group(1);          // /video/xxx-123/
            String slug = href.substring("/video/".length());
            if (slug.endsWith("/")) slug = slug.substring(0, slug.length() - 1);
            if (TextUtils.isEmpty(slug)) continue;

            Card c = new Card();
            c.slug = slug;

            Matcher mt = P_TITLE.matcher(b);
            if (mt.find()) c.title = htmlUnescape(mt.group(1)).trim();
            if (TextUtils.isEmpty(c.title)) c.title = RybUrlResolver.titleOf(slug);

            Matcher mp = P_POSTER.matcher(b);
            if (mp.find()) c.poster = applyHostMirror(mp.group(1).trim());

            out.add(c);
        }
        LogBridge.d(TAG, "列表页 " + url + " → " + out.size() + " 部");
        return out;
    }

    // ==================== 【2026-10-19 海报镜像】图床域名重写 ====================

    /**
     * 【2026-10-19 海报镜像】图床域名重写表：不可达主机 → 可达镜像主机。
     *
     * <p>与源7 {@code AfhgrjSourceLoader.POSTER_HOST_MIRROR}、源5
     * {@code RenYiBoVodSourceLoader} 的实测结论一致（已在本项目验证过）：
     * <ul>
     *   <li>{@code img.picbf.com} 的 TLS 握手被 RST（HTTP 000），而 {@code p.bfvp26.com}
     *       与它<b>同属 CDN、路径完全通用</b> → 同路径请求返回 200；</li>
     *   <li>feifeiimg 系（{@code img.feifeiimg.vip / tupian.ffeiimg.com / pps.vodfeiss.com}）
     *       全部 RST，而 {@code fffgood.com} 用<b>完全相同的路径</b>返回 200。</li>
     * </ul>
     *
     * <p>源8 列表页实测 20 张海报里有 8 张挂 {@code img.picbf.com}、2 张挂
     * {@code img.feifeiimg.vip} —— 不做重写就会整片卡片空白（用户报障：源8 有些频道没有图片）。
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

    /** 对海报 URL 做「不可达主机 → 可达镜像」重写；无匹配则原样返回 */
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

    private static String htmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }
}
