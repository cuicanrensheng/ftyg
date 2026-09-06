package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.tv.live.util.LogBridge;

import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import com.tv.live.util.AppExecutors;
import com.tv.live.util.CacheManager;
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

            String lastErrorMsg = null;
            List<String> sources = Arrays.asList(UrlConfig.LIVE_URL, UrlConfig.LIVE_URL_2);
            LogBridge.e(TAG, "LOAD: source count=" + sources.size() + " LIVE_URL='" + UrlConfig.LIVE_URL + "' LIVE_URL_2='" + UrlConfig.LIVE_URL_2 + "'");
            for (int i = 0; i < sources.size(); i++) {
                String url = sources.get(i);
                if (url == null || url.trim().isEmpty()) {
                    LogBridge.e(TAG, "LOAD: source #" + (i+1) + " is null/empty, skipping");
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
                                final List<Channel> racedChannels = channels;
                                LogBridge.d(TAG, "【镜像竞速】源1 加载成功，共 " + racedChannels.size() + " 个频道");
                                mainHandler.post(() -> callback.onSuccess(racedChannels));
                                return;
                            }
                        } catch (Exception pe) {
                            LogBridge.w(TAG, "【镜像竞速】胜出内容解析失败：" + pe.getMessage());
                        }
                    }
                    LogBridge.w(TAG, "【镜像竞速】源1 全部镜像未在限时内返回有效数据，转常规链路");

                    continue;
                }
                String acceleratedUrl = getAcceleratedUrl(url);
                LogBridge.d(TAG, "【网络】直播源 #" + (i + 1) + " 开始加载：" + acceleratedUrl);
                LogBridge.e(TAG, "LOAD: source #" + (i+1) + " downloading: " + acceleratedUrl);
                try {
                    String rawContent = downloadRawContent(acceleratedUrl);
                    if (rawContent == null || rawContent.isEmpty()) {
                        LogBridge.e(TAG, "LOAD: source #" + (i+1) + " download returned empty/null");
                        throw new IOException("下载为空");
                    }
                    List<Channel> channels = PlaylistParser.parseContent(rawContent);
                    if (channels == null || channels.isEmpty()) {
                        LogBridge.e(TAG, "LOAD: source #" + (i+1) + " parsed 0 channels");
                        throw new IOException("解析后频道数=0");
                    }

                    cacheManager.saveFileCache("live_source", rawContent);
                    if (channels.size() >= MIN_CHANNELS_FOR_FALLBACK) {
                        saveFallback(rawContent);
                        LogBridge.d(TAG, "【网络】永久兜底已更新，频道数 " + channels.size());
                    } else {
                        LogBridge.w(TAG, "【网络】返回频道数过少（" + channels.size() + " < " + MIN_CHANNELS_FOR_FALLBACK + "），不覆盖永久兜底");
                    }
                    final List<Channel> finalChannels = channels;
                    LogBridge.d(TAG, "【网络】直播源 #" + (i + 1) + " 加载成功，共 " + finalChannels.size() + " 个频道");
                    LogBridge.e(TAG, "LOAD: source #" + (i+1) + " SUCCESS, channels=" + finalChannels.size());
                    mainHandler.post(() -> callback.onSuccess(finalChannels));
                    return;
                } catch (Exception e) {
                    lastErrorMsg = "源#" + (i + 1) + "(" + url + "): " + e.getMessage();
                    LogBridge.w(TAG, "【网络】直播源 #" + (i + 1) + " 失败：" + e.getMessage());
                }
            }

            LogBridge.w(TAG, "【兜底】网络全部失败，尝试读取本地永久兜底 " + FALLBACK_FILE);
            String fallback = readFallback();
            if (fallback != null && !fallback.isEmpty()) {
                try {
                    List<Channel> fallbackChannels = PlaylistParser.parseContent(fallback);
                    if (fallbackChannels != null && !fallbackChannels.isEmpty()) {
                        LogBridge.i(TAG, "【兜底】启用本地永久兜底，共 " + fallbackChannels.size() + " 个频道，继续可看电视");
                        final List<Channel> finalFb = fallbackChannels;
                        mainHandler.post(() -> callback.onSuccess(finalFb));
                        return;
                    }
                } catch (Exception ex) {
                    LogBridge.e(TAG, "【兜底】解析失败：" + ex.getMessage());
                }
            }

            final String err = (lastErrorMsg != null ? lastErrorMsg : "未知错误") + "（且无本地兜底数据）";
            mainHandler.post(() -> callback.onError(err));
        });
    }

    public String getAcceleratedUrl(String originalUrl) {

        if (!accelerateEnabled) {
            return originalUrl;
        }

        if (originalUrl == null || originalUrl.trim().isEmpty()) {
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
                    content = f.resultNow();
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
}
