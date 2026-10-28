package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import com.tv.live.util.AppExecutors;
import com.tv.live.util.CacheManager;
import com.tv.live.util.ChannelBlocklist;
import com.tv.live.util.NetUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class LiveSourceLoader {
    private static final String TAG = "LiveSourceLoader";

    private static final String FALLBACK_FILE = "live_source_fallback.m3u";

    // ⚠️【2026-09-10 维持回退】保持: 启动时网络下载直播源 (无 assets 首发)
    // 暂不启用 assets 首发, 等待用户确认 CDN 加速方向

    private static final int MIN_CHANNELS_FOR_FALLBACK = 10;

    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    private final CacheManager cacheManager;

    public enum AccelerateType {

        JSDELIVR,

        GHPROXY,

        GITMIRROR,

        NONE
    }

    private AccelerateType accelerateType = AccelerateType.JSDELIVR;

    private boolean accelerateEnabled = true;

    public interface LoadCallback {
        void onSuccess(List<Channel> channels);
        void onError(String errorMsg);
    }
    private LiveSourceLoader(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.cacheManager = CacheManager.getInstance(context);
    }
    public static LiveSourceLoader getInstance(Context context) {
        if (instance == null) {
            instance = new LiveSourceLoader(context.getApplicationContext());
        }
        return instance;
    }

    public void setAccelerateEnabled(boolean enabled) {
        this.accelerateEnabled = enabled;

    }

    public void setAccelerateType(AccelerateType type) {
        this.accelerateType = type;

    }

    private String getAccelerateTypeName(AccelerateType type) {
        switch (type) {
            case JSDELIVR: return "jsDelivr CDN";
            case GHPROXY: return "ghproxy";
            case GITMIRROR: return "gitmirror";
            case NONE: return "不加速（直连）";
            default: return "未知";
        }
    }

    public void load(final LoadCallback callback) {
        AppExecutors.io(() -> {

            // 启动竞态兜底：fillPublicFields 在 init-security 后台线程执行，全新安装（无缓存源）
            // 时本方法可能早于它被调用，读到空 URL 导致「加载失败」。此处同步补填（纯常量赋值）。
            if (TextUtils.isEmpty(UrlConfig.LIVE_URL) || TextUtils.isEmpty(UrlConfig.LIVE_URL_2)) {
                try { UrlConfig.fillPublicFields(); } catch (Throwable ignored) {}
            }

            // ⚠️【2026-09-10 维持回退】保持: 启动时网络下载直播源 (无 assets 首发)
            // 暂不启用 assets 首发, 等待用户确认 CDN 加速方向
            String lastErrorMsg = null;
            List<String> sources = Arrays.asList(
                    UrlConfig.LIVE_URL, UrlConfig.LIVE_URL_2, UrlConfig.LIVE_URL_CHANXIANG,
                    UrlConfig.LIVE_URL_4, UrlConfig.LIVE_URL_5, UrlConfig.LIVE_URL_6,
                    UrlConfig.LIVE_URL_7, UrlConfig.LIVE_URL_8);
            // 【2026-10-11 切源修复】用户显式选择的源不得被静默替换成另一个源。
            // 仅当生效源是「内置源1」（默认源，地址多变才需要镜像/多源兜底）时才启用依次兜底链；
            // 用户显式选择了源2~源7 或自定义源时只加载该源，失败即走本地永久兜底/报错。
            // 背景：源7(afhgrj) 站点故障返回 520 时，旧逻辑继续尝试 LIVE_URL_2（内置源2）并成功，
            //       表现为「切到源7 结果自动变成内置源2」，且会把源2 内容写进 live_source 缓存。
            String effectiveUrl = UrlConfig.LIVE_URL;
            boolean isDefaultSource1 = effectiveUrl != null && effectiveUrl.equals(UrlConfig.getLiveUrl());
            if (!isDefaultSource1) {
                sources = java.util.Collections.singletonList(effectiveUrl == null ? "" : effectiveUrl);
                LogBridge.i(TAG, "【切源】用户已显式选择源：" + effectiveUrl + "，仅加载该源（不做跨源兜底）");
            }
            LogBridge.d(TAG, "LOAD: source count=" + sources.size() + " LIVE_URL='" + UrlConfig.LIVE_URL + "' LIVE_URL_2='" + UrlConfig.LIVE_URL_2 + "'");
            for (int i = 0; i < sources.size(); i++) {
                String url = sources.get(i);
                if (url == null || url.trim().isEmpty()) {
                    LogBridge.d(TAG, "LOAD: source #" + (i+1) + " is null/empty, skipping");
                    continue;
                }

                // 【2026-10-22 S1-04】镜像竞速仅对 GitHub raw 地址生效（isGitHubUrl 只认 GitHub）。
                // 当前源1 为 Gitee 地址，此条件恒为 false → 竞速分支不触发，源1 直接走下方常规下载。
                // Gitee 不需要 CDN 加速，直连即可；保留此分支是为未来源1 切回 GitHub 时自动复用。
                if (i == 0 && accelerateEnabled && isGitHubUrl(url)) {
                    String racedContent = downloadSource1Racing(url);
                    if (racedContent != null) {
                        try {
                            List<Channel> channels = PlaylistParser.parseContent(racedContent);
                            if (channels != null && !channels.isEmpty()) {
                                saveLiveSourceCache(racedContent);
                                if (channels.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                                    saveFallback(racedContent);
                                }
                                postSuccess(callback, "【镜像竞速】源1 加载成功", channels);
                                return;
                            }
                        } catch (Exception pe) {
                            LogBridge.w(TAG, "【镜像竞速】胜出内容解析失败：" + pe.getMessage());
                        }
                    }
                    LogBridge.d(TAG, "【镜像竞速】源1 全部镜像未在限时内返回有效数据，转常规链路");

                    continue;
                }
                final boolean isChanxiang = url.startsWith("chanxiang://");
                final boolean isAsset = url.startsWith("asset://");
                final boolean isRenyibo = url.startsWith("renyibo://");
                final boolean isMigu = url.startsWith("migu://");
                final boolean isAfhgrj = url.startsWith("afhgrj://");
                final boolean isRyb = url.startsWith("ryb://");
                // 【2026-10-23】源9：橘汁点播（加密 protobuf API，baseUrl 走 OSS 域名池）
                final boolean isJz = url.startsWith(com.tv.live.juzhi.JzProtocol.SCHEME);
                // 【2026-10-21】源2（内置源2，外链 M3U）：加载出口追加 4K影视 4 个点播分组
                final boolean isSource2 = isSource2Url(url);
                String acceleratedUrl = (isChanxiang || isAsset || isRenyibo || isMigu || isAfhgrj || isRyb || isJz) ? url : getAcceleratedUrl(url);


                try {
                    String rawContent;
                    long tDlStart = System.currentTimeMillis();
                    if (isChanxiang) {
                        LogBridge.d(TAG, "【畅享】走内置畅享源分支，本地实时生成 M3U");
                        rawContent = com.tv.live.chanxiang.ChanxiangSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isAsset) {
                        LogBridge.d(TAG, "【内置】走 assets 内置源分支：" + url);
                        rawContent = readAssetSource(url.substring("asset://".length()));
                    } else if (isRenyibo) {

                        rawContent = com.tv.live.renyibo.RenYiBoSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isMigu) {
                        LogBridge.d(TAG, "【咪咕】走内置源6分支，取官方频道目录生成 M3U（地址为 miguhd:// 惰性解析）");
                        rawContent = com.tv.live.migu.MiguSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isAfhgrj) {
                        LogBridge.d(TAG, "【影视源7】走内置源7分支，抓 5 个分组生成 M3U（地址为 afhgrj:// 惰性解析）");
                        rawContent = com.tv.live.afhgrj.AfhgrjSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isRyb) {
                        LogBridge.d(TAG, "【源8 renyibo】走内置源8分支，抓 8 个分组生成 M3U（地址为 ryb:// 惰性解析）");
                        rawContent = com.tv.live.ryb.RybSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isJz) {
                        LogBridge.d(TAG, "【源9 橘汁】走内置源9分支，按关键词搜片生成 M3U（地址为 juzhi:// 惰性解析）");
                        rawContent = com.tv.live.juzhi.JzSourceLoader.loadM3u(context);
                    } else {
                        rawContent = downloadRawContent(acceleratedUrl);
                    }
                    // 【2026-10-21】源2：在下载出口追加 4K影视（nbmovie）4 个点播分组
                    //   （首页/电影/电视剧/动漫），地址为 nbm://<vodId> 惰性解析。
                    //   幂等：已含 nbm:// 频道则跳过（缓存命中/重复加载时不重复注入）。
                    if (isSource2) {
                        rawContent = appendNbmovieGroups(context, rawContent);
                    }
                    // 【2026-10-19 用户口径】给「没有自家直播频道」的源补上「央视」分组：
                    //   源4（影视轮播）/源7/源8 切过去后「直播」板块是空的，补一份内置央视列表
                    //   （取自内置源1 的央视条目，31 个频道，带 catvod 台标，随包离线可用）。
                    if (isAsset) {
                        rawContent = appendCctvGroup(rawContent);
                    } else if (isAfhgrj || isRyb) {
                        // 【2026-10-21】源7/源8：央视分组里再并入卫视频道
                        rawContent = appendCctvAndWeishi(context, rawContent);
                    }
                    long dlMs = System.currentTimeMillis() - tDlStart;
                    if (rawContent == null || rawContent.isEmpty()) {

                        throw new IOException("下载为空");
                    }
                    long tParseStart = System.currentTimeMillis();
                    List<Channel> channels;
                    channels = PlaylistParser.parseContent(rawContent);


                    if (channels == null || channels.isEmpty()) {

                        throw new IOException("解析后频道数=0");
                    }
                    // 【2026-10-11 海报】源7：Channel 直接绑定海报，Channel 直达 OTT，不依赖 M3U tvg-logo
                    if (isAfhgrj) {
                        applyAfhgrjPosters(channels);
                    }
                    // 【2026-10-21】源2：为追加的 4K影视 nbm:// 频道绑定海报
                    if (isSource2) {
                        applyNbmoviePosters(channels);
                    }

                    saveLiveSourceCache(rawContent);
                    if (channels.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                        saveFallback(rawContent);

                    } else {
                        LogBridge.d(TAG, "【网络】返回频道数过少（" + channels.size() + " < " + MIN_CHANNELS_FOR_FALLBACK + "），不覆盖永久兜底");
                    }
                    LogBridge.d(TAG, "⏱️【链路-主源】源#" + (i + 1) + " HTTP下载 " + dlMs
                            + "ms + 解析 " + (System.currentTimeMillis() - tParseStart)
                            + "ms，频道=" + channels.size());

                    postSuccess(callback, "LOAD: source #" + (i + 1) + " SUCCESS", channels);
                    return;
                } catch (Exception e) {
                    lastErrorMsg = "源#" + (i + 1) + "(" + url + "): " + e.getMessage();

                }
            }

            // 【2026-10-11 切源修复】只有「内置源1（默认源）」才允许落到本地永久兜底。
            // 用户显式选择的源失败时若回退到 readFallback()，读的是 live_source_fallback.m3u
            // ——那是「上一次抓取成功的某个源」的内容，等于又一次跨源替换（就是本 bug 的根因）。
            if (isDefaultSource1) {
                LogBridge.w(TAG, "【兜底】网络全部失败，尝试读取本地永久兜底 " + FALLBACK_FILE);
                String fallback = readFallback();
                if (fallback != null && !fallback.isEmpty()) {
                    try {
                        List<Channel> fallbackChannels = PlaylistParser.parseContent(fallback);
                        if (fallbackChannels != null && !fallbackChannels.isEmpty()) {
                            postSuccess(callback, "【兜底】启用本地永久兜底，继续可看电视", fallbackChannels);
                            return;
                        }
                    } catch (Exception ex) {
                        LogBridge.e(TAG, "【兜底】解析失败：" + ex.getMessage());
                    }
                }

                final String err = (lastErrorMsg != null ? lastErrorMsg : "未知错误") + "（且无本地兜底数据）";
                mainHandler.post(() -> callback.onError(err));
            } else {
                // 显式选源失败：不得跨源替换（不读本地永久兜底），直接上报错误。
                // lastErrorMsg 已带出错源地址与原因，例如 "源#1(afhgrj://all): 下载为空"。
                final String err = lastErrorMsg != null
                        ? lastErrorMsg
                        : ("源[" + (effectiveUrl == null ? "未知源" : effectiveUrl) + "] 加载失败：未知错误");
                LogBridge.e(TAG, "【切源】显式选源加载失败，已阻止跨源/本地兜底替换：" + err);
                mainHandler.post(() -> callback.onError(err));
            }
        });
    }

    // ------------------------------------------------------------------
    // 【2026-10-12 屏蔽名单】频道表统一出口
    // ------------------------------------------------------------------

    /**
     * 频道表统一出口：先过 {@link ChannelBlocklist} 屏蔽名单，再回调。
     *
     * <p>所有成功路径（镜像竞速 / 常规下载 / 本地永久兜底）都必须走这里。屏蔽放在
     * 「解析结果出口」而不是各来源的生成端，是为了让<b>已有缓存</b>（live_source 缓存、
     * live_source_fallback.m3u）里的屏蔽项也一并失效，不必等缓存过期或手动清数据。
     *
     * @param label 日志前缀，形如 {@code LOAD: source #1 SUCCESS}
     */
    private void postSuccess(LoadCallback callback, String label, List<Channel> channels) {
        final List<Channel> filtered = ChannelBlocklist.filter(channels);
        final int n = filtered == null ? 0 : filtered.size();
        LogBridge.i(TAG, label + "，频道=" + n);
        mainHandler.post(() -> callback.onSuccess(filtered));
    }

    // ------------------------------------------------------------------
    // 【2026-10-11 海报】源7：为 afhgrj:// 频道直接绑定海报 URL（Channel 直达 OTT）
    // ------------------------------------------------------------------
    private static void applyAfhgrjPosters(List<Channel> channels) {
        int bound = 0, noPoster = 0;
        for (Channel c : channels) {
            // 仅处理源7 协议串频道（M3U 行地址为 afhgrj://<vodId>）
            String url = c.getMainPlayUrl();
            if (url == null || !url.startsWith("afhgrj://")) continue;
            // 用 Channel 版本提 vodId（从 channelId "afhgrj:<vodId>" 提取）
            String vodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(c);
            String poster = com.tv.live.afhgrj.AfhgrjSourceLoader.posterOf(vodId);
            if (poster == null || poster.isEmpty()) {
                noPoster++;
                continue;
            }
            c.setLogoUrl(poster);
            bound++;
        }
    }

    // ------------------------------------------------------------------
    // 【2026-10-21 源2 追加 4K影视】nbmovie 分组注入 + 海报绑定 + 源2 判定
    // ------------------------------------------------------------------

    /**
     * 判定某 URL 是否为「内置源2」。
     *
     * <p>源2 是外链 M3U（LIVE_URL_2），地址由 SecurityCore 解密得出，
     * LIVE_URL_2 / LIVE_URL_2_RAW / getLiveUrl2() 三者取值同源，
     * 用户显式选源时 effectiveUrl 可能取其中任意一种形态，故全部比对。
     */
    static boolean isSource2Url(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.equals(UrlConfig.LIVE_URL_2)) return true;
        if (url.equals(UrlConfig.LIVE_URL_2_RAW)) return true;
        try {
            return url.equals(UrlConfig.getLiveUrl2());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【2026-10-21】给源2 的 M3U 出口追加 4K影视（nbmovie）4 个点播分组。
     *
     * <p>幂等：内容中已含 {@code nbm://} 频道（重复加载/旧缓存）时直接返回。
     * 追加时剥掉片段的 {@code #EXTM3U} 头（PlaylistParser 只认第一行）。
     *
     * <p>【2026-10-21 用户批准】成人内容清洗：入口先过
     * {@link com.tv.live.nbmovie.NbMovieSourceLoader#filterAdult(String)}，
     * 再判幂等——升级前写入的 {@code live_source} 缓存含未过滤的 nbm 成人条目，
     * 直接判幂等会跳过导致残留。
     *
     * @return 追加后的 M3U；拉取失败时返回原内容并打 warn（不影响源2 原有直播频道）
     */
    private static String appendNbmovieGroups(Context ctx, String m3u) {
        if (m3u == null || m3u.isEmpty()) return m3u;
        String cleaned = com.tv.live.nbmovie.NbMovieSourceLoader.filterAdult(m3u);
        if (cleaned == null || cleaned.isEmpty()) return cleaned;
        m3u = cleaned;
        if (m3u.contains("nbm://")) {
            LogBridge.d(TAG, "【4K影视】源2 已含 nbm:// 频道，跳过追加");
            return m3u;
        }
        String nbmM3u;
        try {
            nbmM3u = com.tv.live.nbmovie.NbMovieSourceLoader.loadAllLiveAsM3u(ctx);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【4K影视】拉取分组 M3U 异常：" + t.getMessage());
            return m3u;
        }
        if (nbmM3u == null || nbmM3u.isEmpty()) {
            LogBridge.w(TAG, "【4K影视】分组 M3U 为空（站点不可达或解析失败），源2 仅保留原有直播频道");
            return m3u;
        }
        // 剥掉片段的 #EXTM3U 头行，避免文件中段出现第二个头
        String body = nbmM3u;
        if (body.startsWith("#EXTM3U")) {
            int nl = body.indexOf('\n');
            body = nl >= 0 ? body.substring(nl + 1) : "";
        }
        int added = 0;
        for (int i = 0; i >= 0 && i < body.length(); ) {
            int j = body.indexOf("#EXTINF", i);
            if (j < 0) break;
            added++;
            i = body.indexOf('\n', j);
            if (i < 0) break;
            i++;
        }
        StringBuilder sb = new StringBuilder(m3u);
        if (!m3u.endsWith("\n")) sb.append('\n');
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        LogBridge.d(TAG, "【4K影视】源2 已追加点播分组，共 " + added + " 个频道");
        return sb.toString();
    }

    /**
     * 【2026-10-21】源2：为追加的 4K影视 {@code nbm://} 频道直接绑定海报 URL。
     *
     * <p>对照 {@link #applyAfhgrjPosters}：仅处理 nbm:// 协议串频道，
     * 其余源2 原有直播频道不碰。
     */
    private static void applyNbmoviePosters(List<Channel> channels) {
        int bound = 0, noPoster = 0;
        for (Channel c : channels) {
            String url = c.getMainPlayUrl();
            if (url == null || !url.startsWith("nbm://")) continue;
            String vodId = com.tv.live.nbmovie.NbMovieUrlResolver.vodIdOf(c);
            if (vodId == null || vodId.isEmpty()) continue;
            String poster = com.tv.live.nbmovie.NbMovieSourceLoader.posterOf(vodId);
            if (poster == null || poster.isEmpty()) {
                noPoster++;
                continue;
            }
            c.setLogoUrl(poster);
            bound++;
        }
    }

    public String getAcceleratedUrl(String originalUrl) {

        if (!accelerateEnabled) {
            return originalUrl;
        }

        if (!isGitHubUrl(originalUrl)) {
            return originalUrl;
        }

        switch (accelerateType) {
            case JSDELIVR:
                return convertToJsdelivr(originalUrl);
            case GHPROXY:
                return convertToGhproxy(originalUrl);
            case GITMIRROR:
                return convertToGitmirror(originalUrl);
            case NONE:
            default:
                return originalUrl;
        }
    }

    /**
     * 判断是否为 GitHub 原始文件地址（仅这类地址需要 jsDelivr/ghproxy/gitmirror CDN 加速）。
     *
     * <p>【2026-10-22 S1-04 定案】Gitee 地址（源1/源2 当前使用的 gitee.com/.../raw/...）
     * <b>不</b>需要镜像加速，直连即可，故此处<b>故意不</b>匹配 gitee.com。
     * 源1 自 2026-08-29 起已从 GitHub 切换为 Gitee（见 SourceManager 注释），
     * 因此镜像竞速分支对当前源1 永不触发，属预期行为，非 Bug。
     * 若未来源1 切回 GitHub raw 地址，本方法与竞速/加速链路自动恢复生效。
     */
    private boolean isGitHubUrl(String url) {
        if (url == null) return false;
        return url.contains("raw.githubusercontent.com")
                || url.contains("github.com/") && url.contains("/raw/")
                || url.contains("raw.github.com");
    }

    private String convertToJsdelivr(String githubUrl) {
        try {

            GitHubUrlInfo info = parseGitHubUrl(githubUrl);
            if (info == null) {
                return githubUrl;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("https://cdn.jsdelivr.net/gh/");
            sb.append(info.user);
            sb.append("/");
            sb.append(info.repo);

            String refAndPath = (info.branch != null && !info.branch.isEmpty())
                    ? info.branch + "/" + info.path
                    : info.path;
            sb.append("@");
            sb.append(normalizeBranch(refAndPath));
            return sb.toString();
        } catch (Exception e) {
            return githubUrl;
        }
    }

    private String normalizeBranch(String branch) {
        if (branch == null) return "";
        String b = branch.trim();
        if (b.startsWith("refs/heads/")) {
            return b.substring("refs/heads/".length());
        }
        if (b.startsWith("refs/tags/")) {
            return b.substring("refs/tags/".length());
        }
        return b;
    }

    private String convertToGhproxy(String githubUrl) {
        try {
            return "https://ghproxy.com/" + githubUrl;
        } catch (Exception e) {
            return githubUrl;
        }
    }

    private String convertToGitmirror(String githubUrl) {
        try {

            return githubUrl.replace("raw.githubusercontent.com", "raw.gitmirror.com")
                    .replace("raw.github.com", "raw.gitmirror.com");
        } catch (Exception e) {
            return githubUrl;
        }
    }

    private static class GitHubUrlInfo {
        String user;
        String repo;
        String branch;
        String path;
    }

    private GitHubUrlInfo parseGitHubUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            GitHubUrlInfo info = new GitHubUrlInfo();

            String cleanUrl = url;
            if (cleanUrl.startsWith("https://")) {
                cleanUrl = cleanUrl.substring(8);
            } else if (cleanUrl.startsWith("http://")) {
                cleanUrl = cleanUrl.substring(7);
            }

            if (cleanUrl.startsWith("raw.githubusercontent.com/")) {
                String pathPart = cleanUrl.substring("raw.githubusercontent.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0];
                    info.repo = parts[1];
                    info.branch = parts[2];
                    info.path = parts[3];
                    return info;
                }
            }

            if (cleanUrl.startsWith("github.com/") && cleanUrl.contains("/raw/")) {

                Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)");
                Matcher matcher = pattern.matcher(cleanUrl);
                if (matcher.find()) {
                    info.user = matcher.group(1);
                    info.repo = matcher.group(2);
                    info.branch = matcher.group(3);
                    info.path = matcher.group(4);
                    return info;
                }
            }

            if (cleanUrl.startsWith("raw.github.com/")) {
                String pathPart = cleanUrl.substring("raw.github.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0];
                    info.repo = parts[1];
                    info.branch = parts[2];
                    info.path = parts[3];
                    return info;
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final int DOWNLOAD_MAX_RETRIES = 3;
    private static final long DOWNLOAD_RETRY_DELAY_MS = 500;

    private String downloadRawContent(String urlStr) {
        int lastCode = -1;
        String lastError = "";
        for (int attempt = 1; attempt <= DOWNLOAD_MAX_RETRIES; attempt++) {
            try {
                Response response = NetUtil.getInstance().syncGet(urlStr);
                try (Response resp = response) {
                    int responseCode = resp.code();
                    if (responseCode != 200 || resp.body() == null) {
                        lastCode = responseCode;
                        lastError = "HTTP " + responseCode;
                        LogBridge.w(TAG, "下载失败 attempt=" + attempt + "/" + DOWNLOAD_MAX_RETRIES
                                + " code=" + responseCode + " url=" + urlStr);
                        if (responseCode >= 400 && responseCode < 500) {
                            break;
                        }
                        continue;
                    }
                    String content = resp.body().string();


                    return content;
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                LogBridge.d(TAG, "下载异常 attempt=" + attempt + "/" + DOWNLOAD_MAX_RETRIES
                        + " url=" + urlStr + " err=" + lastError);
            }
            if (attempt < DOWNLOAD_MAX_RETRIES) {
                try { Thread.sleep(DOWNLOAD_RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }
        LogBridge.e(TAG, "下载最终失败 code=" + lastCode + " url=" + urlStr + " err=" + lastError);
        return null;
    }

    private static final long RACE_TIMEOUT_MS = 5000L;

    private String downloadSource1Racing(String originalUrl) {

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        AccelerateType preferred = accelerateType;
        if (preferred == AccelerateType.JSDELIVR) candidates.add(convertToJsdelivr(originalUrl));
        if (preferred == AccelerateType.GITMIRROR) candidates.add(convertToGitmirror(originalUrl));
        candidates.add(convertToJsdelivr(originalUrl));
        candidates.add(convertToGitmirror(originalUrl));
        candidates.add(originalUrl);

        LogBridge.d(TAG, "【镜像竞速】源1 启动，候选镜像 " + candidates.size() + " 个，时限 "
                + (RACE_TIMEOUT_MS / 1000) + "s");
        final long startMs = System.currentTimeMillis();

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(candidates.size(), r -> {
                    Thread t = new Thread(r, "LiveSource-Race");
                    t.setDaemon(true);
                    return t;
                });
        try {
            java.util.concurrent.CompletionService<String> cs =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
            int submitted = 0;
            for (final String candidate : candidates) {
                final String c = candidate;
                cs.submit(() -> {
                    try {
                        String content = downloadRawContent(c);
                        if (content == null || content.isEmpty()) return null;

                        List<Channel> parsed = PlaylistParser.parseContent(content);
                        if (parsed == null || parsed.isEmpty()) {
                            LogBridge.w(TAG, "【镜像竞速】镜像返回无法解析的内容: "
                                    + c.substring(0, Math.min(60, c.length())));
                            return null;
                        }

                        try {
                            saveLiveSourceCache(content);
                            if (parsed.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                                saveFallback(content);
                            }
                        } catch (Throwable t2) {  }
                        return content;
                    } catch (Throwable t1) {
                        return null;
                    }
                });
                submitted++;
            }

            long deadline = startMs + RACE_TIMEOUT_MS;
            boolean winner = false;
            for (int done = 0; done < submitted; done++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                java.util.concurrent.Future<String> f = cs.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) break;
                String content;
                try {
                    // 【2026-10-10 Lint/NewApi】原用 Future#resultNow()（Java 19 / Android API 36 才有）。
                    // minSdk=21 上该调用必抛 NoSuchMethodError，且被下方 catch(Throwable) 吞掉 →
                    // 表现为「镜像竞速」在 Android 15 以下永远无法胜出，静默退化成等满超时。
                    // ExecutorCompletionService#poll 返回的 Future 必定已完成，故 get() 不会阻塞。
                    content = f.get();
                } catch (Throwable t1) {
                    continue;
                }
                if (content != null) {


                    winner = true;
                    return content;
                }
            }
            if (!winner) {
                LogBridge.w(TAG, "【镜像竞速】无镜像在 " + (RACE_TIMEOUT_MS / 1000)
                        + "s 内胜出（elapsed=" + (System.currentTimeMillis() - startMs)
                        + "ms），转源2；未完成的镜像转入后台继续（成功会写缓存）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {

            pool.shutdown();
        }
        LogBridge.w(TAG, "【镜像竞速】无镜像胜出，elapsed=" + (System.currentTimeMillis() - startMs) + "ms");
        return null;
    }

    /**
     * 读取打包在 assets 里的内置直播源（asset://xxx）。
     * 支持省略扩展名（asset://live_source_4 → live_source_4.txt/.m3u/.m3u8）。
     * 内容格式与网络源一致：M3U 或「分类,#genre# + 频道名,URL」（DIYP 格式）。
     */
    private String readAssetSource(String assetName) throws IOException {
        if (assetName == null || assetName.trim().isEmpty()) {
            throw new IOException("asset 名为空");
        }
        String name = assetName.trim();
        while (name.startsWith("/")) name = name.substring(1);
        String[] candidates = new String[]{name, name + ".txt", name + ".m3u", name + ".m3u8"};

        InputStream in = null;
        for (String candidate : candidates) {
            try {
                in = context.getAssets().open(candidate);
                break;
            } catch (IOException notFound) {

            }
        }
        if (in == null) {
            throw new IOException("assets 中未找到内置源：" + name);
        }
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private File getFallbackFile() {
        return new File(context.getFilesDir(), FALLBACK_FILE);
    }

    // ==================== 【2026-10-19】央视分组补入（源4/源7/源8 缺直播频道） ====================

    /** 内置央视片段（assets/cctv_group.m3u）：取自内置源1 的央视条目，group-title="央视"、带 catvod 台标 */
    private static final String CCTV_GROUP_ASSET = "cctv_group.m3u";
    /** 【2026-10-21】内置卫视片段（assets/weishi_group.m3u）：39 个卫视频道，同挂 group-title="央视" */
    private static final String WEISHI_GROUP_ASSET = "weishi_group.m3u";
    private static final java.util.Map<String, String> sFragments = new java.util.HashMap<>();

    /**
     * 读取内置片段（按 asset 名只读一次并常驻；离线可用，不额外联网）。
     * 片段格式与网络源一致：{@code #EXTINF:-1 tvg-name=… tvg-logo=… group-title="央视",频道名} + 地址行。
     */
    private static String fragment(Context ctx, String asset) {
        synchronized (sFragments) {
            String cached = sFragments.get(asset);
            if (cached != null) return cached;
            String t = "";
            try (InputStream in = ctx.getAssets().open(asset)) {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                t = sb.toString().trim();
            } catch (Throwable e) {
                LogBridge.w(TAG, "【央视分组】内置片段读取失败(" + asset + "): " + e.getMessage());
            }
            sFragments.put(asset, t);
            return t;
        }
    }

    private static int countExtinf(String frag) {
        int n = 0;
        if (frag == null) return 0;
        for (String line : frag.split("\n")) {
            if (line.startsWith("#EXTINF")) n++;
        }
        return n;
    }

    /**
     * 【2026-10-19 用户口径】给「没有自家直播频道」的源补上「央视」分组。
     *
     * <p>源4（影视轮播）/源7/源8 切过去后「直播」板块是空的，用户要求把内置源1 的央视分组
     * 补进这三个源。片段随包内置（{@code assets/cctv_group.m3u}，31 个频道，央视系全覆盖，
     * 带 catvod 台标），不依赖源1 的实时抓取。
     *
     * <p><b>幂等</b>：M3U 里已含 {@code group-title="央视"} 时原样返回（补过的不再补；
     * 该组名在这三个源里本就不存在，不会误判）。
     */
    private String appendCctvGroup(String m3u) {
        if (m3u == null || m3u.isEmpty()) return m3u;
        if (m3u.contains("group-title=\"央视\"")) return m3u;
        String frag = fragment(context, CCTV_GROUP_ASSET);
        if (frag.isEmpty()) return m3u;
        int n = countExtinf(frag);
        LogBridge.d(TAG, "【央视分组】已补入直播频道 " + n + " 个（源4 补齐直播源）");
        return m3u + (m3u.endsWith("\n") ? "" : "\n") + frag + "\n";
    }

    /**
     * 【2026-10-21 用户口径】源7/源8 的「央视」分组里再并入卫视频道。
     *
     * <p>两个片段都挂 {@code group-title="央视"}，解析后同属一个分组：先 31 个央视系，
     * 后 39 个卫视系（{@code assets/weishi_group.m3u}，取自内置源1，离线可用）。
     * 幂等：已含央视分组直接返回。
     */
    private static String appendCctvAndWeishi(Context ctx, String m3u) {
        if (m3u == null || m3u.isEmpty()) return m3u;
        if (m3u.contains("group-title=\"央视\"")) return m3u;
        String cctv = fragment(ctx, CCTV_GROUP_ASSET);
        String weishi = fragment(ctx, WEISHI_GROUP_ASSET);
        int c = countExtinf(cctv), w = countExtinf(weishi);
        if (c + w == 0) return m3u;
        StringBuilder sb = new StringBuilder(m3u);
        if (!m3u.endsWith("\n")) sb.append('\n');
        if (!cctv.isEmpty()) sb.append(cctv).append('\n');
        if (!weishi.isEmpty()) sb.append(weishi).append('\n');
        LogBridge.d(TAG, "【央视分组】已补入央视 " + c + " + 卫视 " + w + " 个（源7/源8）");
        return sb.toString();
    }

    /**
     * 缓存命中时补组：源7/源8 的 {@code live_source} 缓存可能是升级前写入的（不含卫视），
     * 启动直接读缓存会绕过网络分支，这里按当前源补一次，保证新版首次启动就能看到卫视。
     *
     * <p>【2026-10-21】源2：同理，旧缓存是升级前写入的（不含 4K影视 4 个点播分组），
     * 缓存命中时补一次 {@code nbm://} 分组。
     */
    public static String appendExtraGroupsForCache(Context ctx, String m3u, String sourceUrl) {
        if (m3u == null || m3u.isEmpty() || sourceUrl == null) return m3u;
        if (isSource2Url(sourceUrl)) {
            return appendNbmovieGroups(ctx, m3u);
        }
        if (!sourceUrl.startsWith("afhgrj://") && !sourceUrl.startsWith("ryb://")) return m3u;
        return appendCctvAndWeishi(ctx, m3u);
    }

    /**
     * 【2026-10-26 缓存归属】写 live_source 缓存，并把「这份缓存属于哪个源」记到 SP。
     * <p>只写内容不打标时，AppCoreManager 的「7 天内跳过网络刷新」会拿旧源缓存当新源用
     * （实测 SP 切回默认源1，冷启动仍加载源9 的 343 个频道）。所有写入缓存的位置都必须走这里。
     */
    private void saveLiveSourceCache(String content) {
        cacheManager.saveFileCache("live_source", content);
        try {
            com.tv.live.config.AppConfig.getInstance(context).markLiveSourceCacheOwner();
        } catch (Throwable t) {
            LogBridge.w(TAG, "【缓存归属】写标记失败：" + t.getMessage());
        }
    }

    private void saveFallback(String content) {
        if (content == null || content.isEmpty()) return;
        File file = getFallbackFile();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } catch (IOException e) {
            LogBridge.e(TAG, "保存永久兜底失败：" + e.getMessage());
        } finally {
            if (fos != null) { try { fos.close(); } catch (IOException ignored) {} }
        }
    }

    private String readFallback() {
        File file = getFallbackFile();
        if (!file.exists() || file.length() <= 0) return null;
        FileInputStream fis = null;
        BufferedReader br = null;
        try {
            fis = new FileInputStream(file);
            br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            LogBridge.e(TAG, "读取永久兜底失败：" + e.getMessage());
            return null;
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
            if (fis != null) { try { fis.close(); } catch (IOException ignored) {} }
        }
    }

    // ⚠️【2026-09-10 回退】已恢复: 以下 3 个方法移除 (assets 首发功能回退)
    // readAssetSource(), hasCachedSource(), refreshFromNetworkInBackground()
}
