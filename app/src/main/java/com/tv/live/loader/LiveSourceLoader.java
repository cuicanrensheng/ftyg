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
            LogBridge.i(TAG, "LOAD: source count=" + sources.size() + " LIVE_URL='" + UrlConfig.LIVE_URL + "' LIVE_URL_2='" + UrlConfig.LIVE_URL_2 + "'");
            for (int i = 0; i < sources.size(); i++) {
                String url = sources.get(i);
                if (url == null || url.trim().isEmpty()) {
                    LogBridge.w(TAG, "LOAD: source #" + (i+1) + " is null/empty, skipping");
                    continue;
                }

                if (i == 0 && accelerateEnabled && isGitHubUrl(url)) {
                    String racedContent = downloadSource1Racing(url);
                    if (racedContent != null) {
                        try {
                            List<Channel> channels = PlaylistParser.parseContent(racedContent);
                            if (channels != null && !channels.isEmpty()) {
                                cacheManager.saveFileCache("live_source", racedContent);
                                if (channels.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                                    saveFallback(racedContent);
                                }
                                LogBridge.d(TAG, "【镜像竞速】源1 加载成功，共 " + channels.size() + " 个频道");
                                postSuccess(callback, "【镜像竞速】源1 加载成功", channels);
                                return;
                            }
                        } catch (Exception pe) {
                            LogBridge.w(TAG, "【镜像竞速】胜出内容解析失败：" + pe.getMessage());
                        }
                    }
                    LogBridge.w(TAG, "【镜像竞速】源1 全部镜像未在限时内返回有效数据，转常规链路");

                    continue;
                }
                final boolean isChanxiang = url.startsWith("chanxiang://");
                final boolean isAsset = url.startsWith("asset://");
                final boolean isRenyibo = url.startsWith("renyibo://");
                final boolean isMigu = url.startsWith("migu://");
                final boolean isAfhgrj = url.startsWith("afhgrj://");
                final boolean isKvm4 = url.startsWith("kvm4://");
                String acceleratedUrl = (isChanxiang || isAsset || isRenyibo || isMigu || isAfhgrj || isKvm4) ? url : getAcceleratedUrl(url);
                LogBridge.d(TAG, "【网络】直播源 #" + (i + 1) + " 开始加载：" + acceleratedUrl);
                LogBridge.d(TAG, "LOAD: source #" + (i+1) + " downloading: " + acceleratedUrl);
                try {
                    String rawContent;
                    long tDlStart = System.currentTimeMillis();
                    if (isChanxiang) {
                        LogBridge.i(TAG, "【畅享】走内置畅享源分支，本地实时生成 M3U");
                        rawContent = com.tv.live.chanxiang.ChanxiangSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isAsset) {
                        LogBridge.i(TAG, "【内置】走 assets 内置源分支：" + url);
                        rawContent = readAssetSource(url.substring("asset://".length()));
                    } else if (isRenyibo) {
                        LogBridge.i(TAG, "【爱其意】走内置源5分支，本地实时拉取频道并生成 M3U");
                        rawContent = com.tv.live.renyibo.RenYiBoSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isMigu) {
                        LogBridge.i(TAG, "【咪咕】走内置源6分支，取官方频道目录生成 M3U（地址为 miguhd:// 惰性解析）");
                        rawContent = com.tv.live.migu.MiguSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isAfhgrj) {
                        LogBridge.i(TAG, "【影视源7】走内置源7分支，抓 5 个分组生成 M3U（地址为 afhgrj:// 惰性解析）");
                        rawContent = com.tv.live.afhgrj.AfhgrjSourceLoader.loadAllLiveAsM3u(context);
                    } else if (isKvm4) {
                        LogBridge.i(TAG, "【4kvm源8】走内置源8分支，抓 5 个片单分组生成 M3U（地址为 kvm4:// 惰性解析）");
                        rawContent = com.tv.live.kvm4.Kvm4SourceLoader.loadAllLiveAsM3u(context);
                    } else {
                        rawContent = downloadRawContent(acceleratedUrl);
                    }
                    long dlMs = System.currentTimeMillis() - tDlStart;
                    if (rawContent == null || rawContent.isEmpty()) {
                        LogBridge.e(TAG, "LOAD: source #" + (i+1) + " download returned empty/null");
                        throw new IOException("下载为空");
                    }
                    long tParseStart = System.currentTimeMillis();
                    // 源8（4kvm）片单分组包含大量重复视频，用户要求不去重，是多少显示多少
                    List<Channel> channels;
                    if (isKvm4) {
                        LogBridge.i(TAG, "【4kvm源8】使用不去重解析 parseContentNoDedup，rawContent长度=" + rawContent.length());
                        channels = PlaylistParser.parseContentNoDedup(rawContent);
                    } else {
                        channels = PlaylistParser.parseContent(rawContent);
                    }
                    // 【2026-10-18 修日志误导】这里原来无条件写「【4kvm源8】解析完成」，
                    // 连源1 的成功日志也被贴上源8 前缀，排查时极易误判。
                    LogBridge.i(TAG, (isKvm4 ? "【4kvm源8】" : "【网络】")
                            + "解析完成，频道数=" + (channels == null ? 0 : channels.size()));
                    if (channels == null || channels.isEmpty()) {
                        LogBridge.e(TAG, "LOAD: source #" + (i+1) + " parsed 0 channels");
                        throw new IOException("解析后频道数=0");
                    }
                    // 【2026-10-11 海报】源7：Channel 直接绑定海报，Channel 直达 OTT，不依赖 M3U tvg-logo
                    if (isAfhgrj) {
                        applyAfhgrjPosters(channels);
                    }

                    cacheManager.saveFileCache("live_source", rawContent);
                    if (channels.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                        saveFallback(rawContent);
                        LogBridge.d(TAG, "【网络】永久兜底已更新，频道数 " + channels.size());
                    } else {
                        LogBridge.w(TAG, "【网络】返回频道数过少（" + channels.size() + " < " + MIN_CHANNELS_FOR_FALLBACK + "），不覆盖永久兜底");
                    }
                    LogBridge.i(TAG, "⏱️【链路-主源】源#" + (i + 1) + " HTTP下载 " + dlMs
                            + "ms + 解析 " + (System.currentTimeMillis() - tParseStart)
                            + "ms，频道=" + channels.size());
                    LogBridge.d(TAG, "【网络】直播源 #" + (i + 1) + " 加载成功，共 " + channels.size() + " 个频道");
                    postSuccess(callback, "LOAD: source #" + (i + 1) + " SUCCESS", channels);
                    return;
                } catch (Exception e) {
                    lastErrorMsg = "源#" + (i + 1) + "(" + url + "): " + e.getMessage();
                    LogBridge.e(TAG, "【网络】直播源 #" + (i + 1) + " 失败：" + e.getMessage());
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
        LogBridge.i(TAG, "【影视源7】海报绑定完成：共 " + bound + " 部绑定成功"
                + (noPoster > 0 ? "，" + noPoster + " 部无海报数据" : ""));
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
            e.printStackTrace();
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
            e.printStackTrace();
            return githubUrl;
        }
    }

    private String convertToGitmirror(String githubUrl) {
        try {

            return githubUrl.replace("raw.githubusercontent.com", "raw.gitmirror.com")
                    .replace("raw.github.com", "raw.gitmirror.com");
        } catch (Exception e) {
            e.printStackTrace();
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
            e.printStackTrace();
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
                    LogBridge.d(TAG, "下载成功 attempt=" + attempt + " size=" + content.length()
                            + " url=" + urlStr.substring(0, Math.min(80, urlStr.length())));
                    return content;
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                LogBridge.w(TAG, "下载异常 attempt=" + attempt + "/" + DOWNLOAD_MAX_RETRIES
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
                            cacheManager.saveFileCache("live_source", content);
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
                    LogBridge.d(TAG, "【镜像竞速】胜出镜像 elapsed="
                            + (System.currentTimeMillis() - startMs) + "ms（其余请求作废）");
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
                LogBridge.i(TAG, "【内置】assets 命中：" + candidate);
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
