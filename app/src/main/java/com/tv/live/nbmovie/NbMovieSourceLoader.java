package com.tv.live.nbmovie;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.M3uDiskCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【2026-10-21 内置源2 接入 4K影视（NbMovie）】频道表加载器 —— 结构照源8（renyibo）做。
 *
 * <p>与源8 一致的三个约定：
 * <ol>
 *   <li>M3U 里地址写自定义协议 {@code nbm://<vodId>}，<b>惰性解析</b>（起播那一刻才取流）；</li>
 *   <li>列表数据走 JSON API（{@link NbMovieApi}），不起 WebView；</li>
 *   <li>磁盘缓存走公共 {@link M3uDiskCache}（files/ + SWR + 失败冷却 + 原子写）。</li>
 * </ol>
 *
 * <p>分组（用户指定 4 组，对应 4K影视 的 4 个列表端点）：
 * <pre>
 *   首页 /app/v1/home → home_modules
 *   电影 /app/v1/movie-page → movie_modules
 *   电视剧 /app/v1/tv-page → tv_modules
 *   动漫 /app/v1/anime-page → anime_modules
 * </pre>
 *
 * <p>合规：4 个分组页<b>严格串行</b>逐个抓（for 循环，禁并行/禁批量）；
 * tvg-id 带分组前缀 {@code nbm:<group>:<id>}，同一部片跨组出现时不按 tvgId 合并。
 */
public final class NbMovieSourceLoader {

    private static final String TAG = "NbMovieSourceLoader";

    // 【2026-10-21】缓存文件名带 v2：旧版（含成人内容未过滤）缓存为 nbm_source.m3u，
    //   升级后天然冷落旧文件，重新抓取时即走新版过滤逻辑。
    private static final String CACHE_FILE = "nbm_source_v2.m3u";
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long FAIL_COOLDOWN_MS = 5L * 60L * 1000L;

    /**
     * 【2026-10-21 用户批准】成人/情色/三级片名黑名单（contains 匹配）。
     *
     * <p>4K影视 {@code movie_modules} 的「伦理/情色」模块与部分 R 级动漫
     * 会混入站点列表，用户要求剔除。已核对全量 215 部片名无误伤；
     * 站点内容更新后如出现新片，在此追加即可。
     */
    private static final String[] ADULT_TITLES = {
            // 电影分组：情色/三级
            "欲动",
            "挑情丑闻",
            "禁止的爱：善良的小姨子",
            "罗马帝国艳情史",
            "色，戒",
            "先性后爱",
            "人间中毒",
            "深情触摸",
            "军中乐园",
            "蜜桃成熟时",
            "不知不觉诱惑你",
            "交通费",
            "现在不是出轨的问题",
            "为了皇帝",
            // 动漫分组：含情色/伦理镜头（用户点名）
            "妖精的旋律",
            "寄生兽",
            "替身",
    };

    /** 片名是否命中成人黑名单（trim 后 contains 匹配） */
    public static boolean isAdultTitle(String title) {
        if (title == null || title.isEmpty()) return false;
        String t = title.trim();
        for (String k : ADULT_TITLES) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    /**
     * 【2026-10-21 用户批准】过滤 M3U 文本中的成人条目（EXTINF + 紧随的 nbm:// 地址行成对剔除）。
     *
     * <p>用于旧缓存清洗：升级前写入的 {@code live_source} 缓存可能含未过滤的
     * nbm 成人条目，而追加逻辑的幂等判断（{@code contains("nbm://")}）会直接跳过，
     * 导致成人片残留；故在追加入口先过本过滤器再判幂等。
     *
     * @return 过滤后的 M3U；无命中时原样返回
     */
    public static String filterAdult(String m3u) {
        if (m3u == null || m3u.isEmpty()) return m3u;
        String[] lines = m3u.split("\n", -1);
        StringBuilder sb = new StringBuilder(m3u.length() + 64);
        int removed = 0;
        boolean skipUrl = false;
        for (String ln : lines) {
            if (skipUrl) {
                skipUrl = false;
                if (ln.trim().startsWith("nbm://")) continue;   // 吞掉成人条目的地址行
            }
            if (ln.startsWith("#EXTINF")) {
                int comma = ln.lastIndexOf(',');
                String title = comma >= 0 ? ln.substring(comma + 1).trim() : "";
                if (isAdultTitle(title)) {
                    removed++;
                    skipUrl = true;
                    continue;
                }
            }
            sb.append(ln).append('\n');
        }
        if (removed > 0) {
            LogBridge.i(TAG, "【成人内容过滤】剔除 " + removed + " 部情色/三级影片");
        }
        return sb.toString();
    }

    /** {分组名, 分组键, modules 字段名} */
    private static final String[][] GROUPS = {
            {"首页", "home", "home_modules"},
            {"电影", "movie", "movie_modules"},
            {"电视剧", "tv", "tv_modules"},
            {"动漫", "anime", "anime_modules"},
    };

    /** vodId → 海报 URL（供 LiveSourceLoader 绑定 Channel.logoUrl 直达 OTT） */
    private static final Map<String, String> sPosters = new LinkedHashMap<>();

    private static final M3uDiskCache sCache = new M3uDiskCache(
            TAG, "源2(4K影视)", CACHE_FILE, CACHE_TTL_MS, FAIL_COOLDOWN_MS,
            new M3uDiskCache.Fetcher() {
                @Override
                public String fetch() {
                    return buildM3u();
                }
            });

    private NbMovieSourceLoader() {
    }

    /**
     * 抓 4 个分组，返回 M3U 文本（内部走磁盘缓存 + SWR）。
     *
     * @return M3U 全文；全部失败返回 null（调用方保持源2 原有内容不变）
     */
    public static String loadAllLiveAsM3u(Context context) {
        // 【2026-10-21 联调修复】加载入口先确保 attach：本方法可能早于 EpgManager /
        //   TVPlayerManager 被 LiveSourceLoader 调用（实测 IllegalStateException:
        //   NbMovieApi.attach 未调用），attach 幂等可重复调。
        if (context != null) {
            NbMovieUrlResolver.attach(context);
        }
        return sCache.load(context);
    }

    /** 取某 vodId 的海报 URL；未收录返回 null */
    public static String posterOf(String vodId) {
        if (vodId == null || vodId.isEmpty()) return null;
        synchronized (sPosters) {
            return sPosters.get(vodId);
        }
    }

    // ------------------------------------------------------------------ 抓取与组装

    /**
     * 抓 4 个分组并组装 M3U。<b>严格串行</b>（合规：逐次请求、禁并行）。
     */
    private static String buildM3u() {
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        int total = 0;
        int adultSkipped = 0;
        for (String[] g : GROUPS) {
            String gname = g[0];
            String gkey = g[1];
            String modulesKey = g[2];
            int before = total;

            JSONObject data = fetchPage(gkey);
            if (data == null) {
                LogBridge.w(TAG, "分组[" + gname + "]抓取失败，跳过");
                continue;
            }
            JSONArray modules = data.optJSONArray(modulesKey);
            if (modules == null || modules.length() == 0) {
                LogBridge.w(TAG, "分组[" + gname + "]无 " + modulesKey + "，跳过");
                continue;
            }

            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int mi = 0; mi < modules.length(); mi++) {
                JSONArray vods = collectVods(modules.optJSONObject(mi));
                if (vods == null) continue;
                for (int vi = 0; vi < vods.length(); vi++) {
                    JSONObject vod = vods.optJSONObject(vi);
                    if (vod == null) continue;
                    int id = vod.optInt("id", 0);
                    if (id <= 0) continue;
                    String idStr = String.valueOf(id);
                    if (seen.contains(idStr)) continue;   // 组内去重
                    seen.add(idStr);

                    String title = vod.optString("title", "");
                    if (TextUtils.isEmpty(title)) continue;
                    // 【2026-10-21 用户批准】成人/情色/三级片剔除（源头不过滤会写入缓存）
                    if (isAdultTitle(title)) {
                        adultSkipped++;
                        continue;
                    }

                    // 海报：cover 优先，缺失取 poster；再做归一化 + 镜像重写
                    String poster = normalizePoster(vod.optString("cover", ""));
                    if (TextUtils.isEmpty(poster)) {
                        poster = normalizePoster(vod.optString("poster", ""));
                    }
                    poster = applyHostMirror(poster);
                    if (!TextUtils.isEmpty(poster)) {
                        synchronized (sPosters) {
                            sPosters.put(idStr, poster);
                        }
                    }

                    String tvgId = "nbm:" + gkey + ":" + idStr;
                    m3u.append("#EXTINF:-1 tvg-id=\"").append(tvgId).append("\"");
                    if (!TextUtils.isEmpty(poster)) {
                        m3u.append(" tvg-logo=\"").append(poster).append("\"");
                    }
                    m3u.append(" group-title=\"").append(gname).append("\"");
                    m3u.append(",").append(escapeTitle(title)).append("\n");
                    m3u.append("nbm://").append(idStr).append("\n");
                    total++;
                }
            }
            LogBridge.i(TAG, "分组[" + gname + "]收录 " + (total - before) + " 部");
        }

        if (total == 0) {
            LogBridge.w(TAG, "源2(4K影视)：无任何可用频道");
            return null;
        }
        LogBridge.i(TAG, "源2(4K影视)：共收录 " + total + " 部"
                + (adultSkipped > 0 ? "（剔除成人内容 " + adultSkipped + " 部）" : ""));
        return m3u.toString();
    }

    /** 按分组键调对应列表端点 */
    private static JSONObject fetchPage(String gkey) {
        switch (gkey) {
            case "home":
                return NbMovieApi.home();
            case "movie":
                return NbMovieApi.moviePage();
            case "tv":
                return NbMovieApi.tvPage();
            case "anime":
                return NbMovieApi.animePage();
            default:
                return null;
        }
    }

    /**
     * 从一个 module 收集 vod 数组：优先 {@code vods}；
     * {@code playlists}（片单型模块）里若嵌 {@code vods} 也一并收下。
     */
    private static JSONArray collectVods(JSONObject module) {
        if (module == null) return null;
        JSONArray vods = module.optJSONArray("vods");
        JSONArray playlists = module.optJSONArray("playlists");
        if (playlists == null || playlists.length() == 0) {
            return vods;
        }
        org.json.JSONArray merged = new org.json.JSONArray();
        if (vods != null) {
            for (int i = 0; i < vods.length(); i++) {
                JSONObject v = vods.optJSONObject(i);
                if (v != null) merged.put(v);
            }
        }
        for (int i = 0; i < playlists.length(); i++) {
            JSONArray inner = playlists.optJSONObject(i).optJSONArray("vods");
            if (inner == null) continue;
            for (int j = 0; j < inner.length(); j++) {
                JSONObject v = inner.optJSONObject(j);
                if (v != null) merged.put(v);
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------ 海报处理

    /**
     * 海报 URL 归一化：补全协议头。
     * {@code //host/path} → {@code https://host/path}；单 {@code /} 开头的相对路径无法定位主机，直接丢弃。
     */
    private static String normalizePoster(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return "https://" + u;
        }
        return u;
    }

    /**
     * 【海报镜像】图床域名重写表：不可达主机 → 可达镜像主机（与源8 实测结论一致）。
     * <ul>
     *   <li>{@code img.picbf.com} → {@code p.bfvp26.com}</li>
     *   <li>{@code img.feifeiimg.vip} / {@code tupian.ffeiimg.com} / {@code pps.vodfeiss.com} → {@code fffgood.com}</li>
     * </ul>
     */
    private static final String[][] POSTER_HOST_MIRROR = {
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

    /** M3U 标题转义：双引号/换行会破坏 EXTINF 属性解析 */
    private static String escapeTitle(String title) {
        return title.replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }
}
