package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.tv.live.Channel;
import com.tv.live.RedirectLoggingHttpDataSource;
import com.tv.live.exception.RedirectFailedException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.net.Uri;
import android.util.Base64;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// 【2026-10-10 Lint】本类刻意使用 media3 的 @UnstableApi 内部 API（自研虎牙播放链）。
@OptIn(markerClass = UnstableApi.class)
public class HuyaStreamPlayer {
    private static final String TAG = "HuyaStreamPlayer";

    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    public interface StreamPlaybackCallback {
        void onPlayError(String msg);
        void onSourceFailed();
        void dLog(String msg);
        void onPlaySuccess();
        void onSeekTo(long positionMs);
        void onPlay();
        void onPrepare();
        void onStuckDetectionStart();
        Context getContext();
        ExoPlayer getPlayer();
        PlayerView getPlayerView();
        FrameLayout getSdkPlayerContainer();
        android.os.Handler getHandler();
        ExecutorService getPlaylistExecutor();
        SharedPreferences getSharedPrefs();
        String getCurrentChannelName();
        Channel getCurrentChannel();
        void setCurrentUrl(String url);
        void setHuyaRoomId(int roomId);
        int getHuyaRoomId();
        void setPendingHeaders(Map<String, String> headers);
        Map<String, String> getPendingHeaders();
        void setReusableHeaderMap(Map<String, String> map);
        Map<String, String> getReusableHeaderMap();
        void setCurrentResolutionLabel(String label);
        void ensurePlayerBoundToView();
        void setMediaSourceAndPrepare(MediaSource source, long seekPosition);
    }

    private StreamPlaybackCallback callback;
    private VariantManager variantManager;

    /**
     * 【2026-10-03 修复「手动切线路被轮询覆盖」】记录**用户手动选择的线路**（按房间隔离）。
     * 与 {@code TVPlayerManager} 中同名 helper 读写同一 key，保证两条解析路径共享一份偏好。
     * 只在用户手动切线路时写入；解析路径只读，故用户选择能跨换台稳定保留。
     */
    private void setUserChosenLine(int roomId, int lineIndex) {
        if (roomId <= 0 || lineIndex < 0) return;
        SharedPreferences p = callback.getSharedPrefs();
        if (p == null) return;
        p.edit().putInt("huya_user_line_" + roomId, lineIndex).apply();
    }

    /** 读取用户手动选择的线路；未选择过或 prefs 不可用时返回 -1。 */
    private int getUserChosenLine(int roomId) {
        if (roomId <= 0) return -1;
        SharedPreferences p = callback.getSharedPrefs();
        if (p == null) return -1;
        return p.getInt("huya_user_line_" + roomId, -1);
    }

    public HuyaStreamPlayer(StreamPlaybackCallback callback, VariantManager variantManager) {
        this.callback = callback;
        this.variantManager = variantManager;
    }

    public boolean isHuyaRoomUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            if (!host.contains("huya.com") && !host.contains("huya.cn")) return false;
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return false;
            String roomIdStr = path.replace("/", "").trim();
            return roomIdStr.matches("\\d+");
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isHuyaProtocolUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.startsWith("huya://room/");
    }

    public boolean isHuyaSource(String url) {
        if (url != null && (url.contains(".huya.com/") || url.contains("huya.com/src"))) {
            return true;
        }
        Channel currentChannel = callback.getCurrentChannel();
        if (currentChannel != null) {
            String cid = currentChannel.getChannelId();
            if (cid != null && (cid.startsWith("huya_") || cid.startsWith("hy_"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 虎牙自有 CDN 主机（hls/flv.huya.com 等）。
     * 【2026-09-29 实测】这些主机对带 Referer 的请求常返回 403；
     * 设备侧 26 组对照中能 200 的组合全部是「只带浏览器 UA」。故判定为 true 时不发 Referer。
     */
    public static boolean isHuyaCdnUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains(".hls.huya.com") || url.contains(".flv.huya.com")
                || url.contains(".hls.huya.cn") || url.contains(".flv.huya.cn");
    }

    private static boolean isHlsUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return false;
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) return true;
            String host = uri.getHost();
            if (host != null && host.contains(".hls.huya.com")) return true;
            return false;
        } catch (Exception e) {
            String lower = url.toLowerCase(Locale.ROOT);
            int q = lower.indexOf('?');
            String beforeQuery = q >= 0 ? lower.substring(0, q) : lower;
            if (beforeQuery.contains(".m3u8") || beforeQuery.contains(".m3u")) return true;
            if (lower.contains(".hls.huya.com")) return true;
            return false;
        }
    }

    public void playUrlInternal(String url) {
        playUrlInternal(url, 0);
    }

    public void playUrlInternal(String url, long initialSeekPosition) {
        if (isHuyaProtocolUrl(url)) {
            String roomIdStr = url.replace("huya://room/", "").trim();
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                callback.onPlayError("虎牙房间号格式错误: " + url);
                return;
            }
            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        if (isHuyaRoomUrl(url)) {
            String roomIdStr = url.replaceAll(".*/(\\d+).*", "$1");
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                callback.onPlayError("虎牙房间号格式错误: " + url);
                return;
            }
            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        doPlay(url, initialSeekPosition);
    }

    /**
     * 取流一律走自研解析器（{@link HuyaParser}），与 {@code TVPlayerManager.playHuyaStreamDual}
     * 同一口径。
     * 注：本方法目前仅在 {@code isHuyaProtocolUrl/isHuyaRoomUrl} 分支内部自调用，属遗留入口。
     */
    public void playHuyaStream(final int roomId, final long initialSeekPosition) {
        Context context = callback.getContext();
        final android.os.Handler mHandler = callback.getHandler();
        PlayerView playerView = callback.getPlayerView();
        FrameLayout sdkPlayerContainer = callback.getSdkPlayerContainer();

        callback.setHuyaRoomId(roomId);

        if (sdkPlayerContainer != null) {
            sdkPlayerContainer.setVisibility(View.GONE);
        }
        if (playerView != null) {
            playerView.setVisibility(View.VISIBLE);
        }

        variantManager.clearVariantList();
        final Channel currentChannel = callback.getCurrentChannel();
        if (currentChannel != null) {
            try {
                currentChannel.clearBackupUrls();
            } catch (Exception ignored) {}
        }

        LogBridge.i(TAG, "【虎牙】走自研解析器（不依赖 SDK）, roomId=" + roomId);
        HuyaParser.parse(roomId, new HuyaParser.OnParseResultListener() {
            @Override
            public void onSuccess(StreamMatrix matrix, String hlsUrl, String flvUrl) {
                onSuccess(hlsUrl, flvUrl, false);
            }

            @Override
            public void onSuccess(final String hlsUrl, final String flvUrl, boolean isTogetherWatch) {
                final String url = !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
                if (TextUtils.isEmpty(url)) {
                    LogBridge.w(TAG, "【虎牙】自研解析返回空地址, roomId=" + roomId);
                    mHandler.post(() -> {
                        Toast.makeText(context, "虎牙解析失败（空地址）", Toast.LENGTH_SHORT).show();
                        callback.onSourceFailed();
                    });
                    return;
                }
                LogBridge.d(TAG, "⚡【虎牙自研解析】拿到已探活播放地址, roomId=" + roomId
                        + " url=" + url.substring(0, Math.min(70, url.length())));
                callback.setPendingHeaders(null);
                mHandler.post(() -> doPlay(url, initialSeekPosition));
            }

            @Override
            public void onFailed(String errorMsg) {
                LogBridge.w(TAG, "【虎牙】自研解析失败: " + errorMsg + ", roomId=" + roomId);
                mHandler.post(() -> {
                    Toast.makeText(context, "虎牙解析失败: " + errorMsg, Toast.LENGTH_SHORT).show();
                    callback.onSourceFailed();
                });
            }
        });
    }

    private void doPlay(String url, long initialSeekPosition) {
        Context context = callback.getContext();
        try {
            ExoPlayer player = callback.getPlayer();
            if (player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();
            String lowerUrl = playUrl.toLowerCase(Locale.ROOT);
            boolean isRealStream = isHlsUrl(playUrl)
                    || lowerUrl.endsWith(".flv")
                    || lowerUrl.contains(".flv.huya.com/")
                    || lowerUrl.contains(".hls.huya.com/")
                    || (lowerUrl.startsWith("http") && (lowerUrl.contains("/src?ws") || lowerUrl.contains("&wssecret=")));
            LogBridge.d(TAG, "doPlay: url=" + playUrl.substring(0, Math.min(100, playUrl.length())) + " isHls=" + isHlsUrl(playUrl));

            String finalUrl;
            Channel currentChannel = callback.getCurrentChannel();
            if (currentChannel != null && !isRealStream) {
                SharedPreferences sp = callback.getSharedPrefs();
                String channelKey = currentChannel.getChannelId();
                if (TextUtils.isEmpty(channelKey)) {
                    channelKey = currentChannel.getName();
                }
                String prefKey = "channel_line_index_" + channelKey;
                int lineIndex = sp.getInt(prefKey, 0);
                if (lineIndex == 0 && sp.contains(KEY_CHANNEL_LINE_INDEX)) {
                    lineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
                }

                if (lineIndex == 0) {
                    finalUrl = currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = lineIndex - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        finalUrl = backups.get(backupIndex);
                    } else {
                        finalUrl = currentChannel.getMainPlayUrl();
                        LogBridge.w(TAG, "线路索引越界，已自动切回主源");
                    }
                }
                callback.dLog("切换线路后播放：" + finalUrl);
            } else {
                finalUrl = playUrl;
                callback.dLog("直接播放地址：" + finalUrl);
            }
            callback.setCurrentUrl(finalUrl);

            if (isHuyaSource(finalUrl)) {
                doPlayHuya(finalUrl, initialSeekPosition);
            } else {
                doPlayNormal(finalUrl, initialSeekPosition);
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                callback.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            callback.onPlayError("播放异常：" + e.getMessage());
        }
    }

    private void doPlayNormal(String url, long initialSeekPosition) {
        Context context = callback.getContext();
        ExoPlayer player = callback.getPlayer();
        String currentChannelName = callback.getCurrentChannelName();


        if (isHlsUrl(url)) {
            fetchAndParseMasterPlaylistNormal(url);
        } else {
            variantManager.clearVariantList();
        }

        SharedPreferences sp = callback.getSharedPrefs();

        RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();

        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        Map<String, String> reusableHeaderMap = callback.getReusableHeaderMap();
        reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        callback.dLog("【普通源】使用默认 headers " + reusableHeaderMap.size() + " 项");

        httpFactory.setDefaultRequestProperties(reusableHeaderMap);
        httpFactory.setChannelName(currentChannelName);
        httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(sp.getBoolean("redirect_cross_domain", true))
                .setAllowCrossProtocolRedirects(sp.getBoolean("redirect_cross_protocol", true))
                .setFollowRedirectsWithHeaders(sp.getBoolean("redirect_follow_headers", true))
                .setIgnoreSslErrorRedirect(sp.getBoolean("redirect_ignore_ssl", false))
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(httpFactory, TvExtractorsFactory.getInstance()).createMediaSource(mediaItem);
        }

        callback.ensurePlayerBoundToView();
        callback.setMediaSourceAndPrepare(mediaSource, initialSeekPosition);
        callback.onStuckDetectionStart();
    }

    private void doPlayHuya(String url, long initialSeekPosition) {
        Context context = callback.getContext();
        ExoPlayer player = callback.getPlayer();
        String currentChannelName = callback.getCurrentChannelName();


        SharedPreferences sp = callback.getSharedPrefs();

        RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();

        Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
        Map<String, String> reusableHeaderMap = callback.getReusableHeaderMap();
        reusableHeaderMap.clear();
        for (String name : globalHeaders.names()) {
            reusableHeaderMap.put(name, globalHeaders.get(name));
        }
        reusableHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        int huyaRoomId = callback.getHuyaRoomId();
        String huyaReferer = huyaRoomId > 0 ? "https://www.huya.com/" + huyaRoomId : "https://www.huya.com/";
        reusableHeaderMap.put("Referer", huyaReferer);
        reusableHeaderMap.put("Accept", "*/*");
        reusableHeaderMap.put("Accept-Language", "zh-CN,zh;q=0.9");
        reusableHeaderMap.put("Accept-Encoding", "identity");
        reusableHeaderMap.put("Connection", "keep-alive");
        callback.dLog("【虎牙源】已启用浏览器UA+Referer(" + huyaReferer + ") 防盗链头");

        Map<String, String> mPendingPlaybackHeaders = callback.getPendingHeaders();
        if (mPendingPlaybackHeaders != null && !mPendingPlaybackHeaders.isEmpty()) {
            int cnt = 0;
            for (Map.Entry<String, String> e : mPendingPlaybackHeaders.entrySet()) {
                if ("Origin".equalsIgnoreCase(e.getKey())) continue;
                if ("Referer".equalsIgnoreCase(e.getKey())) continue;
                reusableHeaderMap.put(e.getKey(), e.getValue());
                cnt++;
            }
            LogBridge.d(TAG, "【虎牙源】解析器专用Headers注入 " + cnt + " 项(含Cookie="
                    + (mPendingPlaybackHeaders.containsKey("Cookie") ? "是" : "否") + ")");
        } else {
            LogBridge.d(TAG, "【虎牙源】mPendingPlaybackHeaders 为空，走默认虎牙 headers");
        }
        callback.setPendingHeaders(null);

        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        if (sendCookie && !reusableHeaderMap.containsKey("Cookie")) {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) reusableHeaderMap.put("Cookie", cookies);
        }

        httpFactory.setDefaultRequestProperties(reusableHeaderMap);
        httpFactory.setChannelName(currentChannelName);
        httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                .setAllowCrossDomainRedirects(true)
                .setAllowCrossProtocolRedirects(true)
                .setFollowRedirectsWithHeaders(true)
                .setIgnoreSslErrorRedirect(sp.getBoolean("redirect_ignore_ssl", false))
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);

        MediaItem mediaItem = MediaItem.fromUri(url);
        MediaSource mediaSource;
        if (isHlsUrl(url)) {
            mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(httpFactory, TvExtractorsFactory.getInstance()).createMediaSource(mediaItem);
        }

        callback.ensurePlayerBoundToView();
        callback.setMediaSourceAndPrepare(mediaSource, initialSeekPosition);
        callback.onStuckDetectionStart();
    }

    private void fetchAndParseMasterPlaylistNormal(String masterUrl) {
        if (variantManager.isParsingMasterPlaylist()) return;
        variantManager.setParsingMasterPlaylist(true);
        ExecutorService sPlaylistExecutor = callback.getPlaylistExecutor();
        sPlaylistExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {

                URL url = new URL(masterUrl);
                // 【2026-10-25 代理坑修复】绕过系统代理（失效代理会让预取超时，拖垮清晰度菜单）
                connection = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                connection.setRequestProperty("Accept", "*/*");

                int code = connection.getResponseCode();

                if (code == HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (InputStream is = connection.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    variantManager.parseMasterPlaylist(playlist, masterUrl);
                } else if (code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    LogBridge.d(TAG, "【普通源】重定向到: " + newUrl);
                    variantManager.setParsingMasterPlaylist(false);
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistNormal(newUrl);
                        return;
                    }
                } else {
                    LogBridge.w(TAG, "【普通源】主播放列表请求失败: code=" + code);
                    variantManager.clearVariantList();
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "【普通源】解析主播放列表失败: ", e);
                variantManager.clearVariantList();
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                variantManager.setParsingMasterPlaylist(false);
            }
        });
    }

    private void fetchAndParseMasterPlaylistHuya(String masterUrl) {
        if (variantManager.isParsingMasterPlaylist()) return;
        variantManager.setParsingMasterPlaylist(true);
        ExecutorService sPlaylistExecutor = callback.getPlaylistExecutor();
        sPlaylistExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {

                URL url = new URL(masterUrl);
                // 【2026-10-25 代理坑修复】绕过系统代理（失效代理会让虎牙主清单预取超时）
                connection = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
                connection.setRequestProperty("Referer", "https://www.huya.com/");
                connection.setRequestProperty("Origin", "https://www.huya.com");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Connection", "keep-alive");

                Map<String, String> mPendingPlaybackHeaders = callback.getPendingHeaders();
                if (mPendingPlaybackHeaders != null && !mPendingPlaybackHeaders.isEmpty()) {
                    for (Map.Entry<String, String> e : mPendingPlaybackHeaders.entrySet()) {
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                String cookies = CookieManager.getInstance().getCookie(masterUrl);
                if (cookies != null && !cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookies);
                }

                int code = connection.getResponseCode();

                if (code == HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (InputStream is = connection.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    variantManager.parseMasterPlaylist(playlist, masterUrl);
                } else if (code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    LogBridge.d(TAG, "【虎牙源】手动重定向到: " + newUrl);
                    variantManager.setParsingMasterPlaylist(false);
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistHuya(newUrl);
                        return;
                    }
                } else {
                    LogBridge.w(TAG, "【虎牙源】主播放列表请求失败: code=" + code);
                    variantManager.clearVariantList();
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "【虎牙源】解析主播放列表失败: ", e);
                variantManager.clearVariantList();
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                variantManager.setParsingMasterPlaylist(false);
            }
        });
    }

    public void release() {
        callback = null;
        variantManager = null;
    }

    // ==================================================================
    // 以下为合并的内嵌类（虎牙播放/网络辅助工具）
    // ==================================================================

    public interface IntCallback {
        void accept(int value);
    }

    /**
     * 【2026-09-29 重写】虎牙自研解析器 —— 模仿 SDK 的解析方法，零 SDK 依赖。
     *
     * SDK 的解析链路（{@code SdkLiveService.getLivingInfo → parseGetLivingInfoRsp}）：
     *   1) 取 {@code StreamInfo}：sHlsUrl / sHlsUrlSuffix / sStreamName / sHlsAntiCode；
     *   2) antiCode 里的 fm 是 base64("xxxx_$0_$1_$2_$3")，取前半段作 fm0；
     *   3) wsSecret = md5(fm0_uid_streamName_seqid_wsTime)，seqid = 当前毫秒 + 随机数；
     *   4) 拼成 sHlsUrl + "/" + sStreamName + "." + suffix + "?" + antiCode。
     *
     * 相比 SDK 多做的一步（也是能"真正播放"的关键）：
     *   对**每个候选地址先做真实 HTTP 探活**（等价于播放器起播的那次请求），
     *   只把「能连续两次拉到真实流」的地址交给 ExoPlayer。
     *   否则会把 403 / 「无上游流」占位 playlist 交给播放器 → 解析"成功"却永远不出画面。
     *
     * ═══════════ 2026-09-29 · 线路选择 & 清晰度选择：完全按 SDK 的模型来 ═══════════
     *
     * ▍SDK 的模型（两个正交维度，本类一一对应）
     *
     *   ┌ 线路（CDN）维度 ─────────────────────────────────────────────┐
     *   │ SDK: {@code StreamInfo.iLineIndex} 作主键，URL 存在            │
     *   │      {@code PlayerHelper.HlsUrl/FlvUrl} 这两个以 lineIndex     │
     *   │      为 key 的 Map 里（SdkLiveService 第 462/466 行）。        │
     *   │      优先级 = {@code StreamInfo.iWebPriorityRate}。            │
     *   │ 本类: mp API 的 {@code baseSteamInfoList[]}，字段同名同义。     │
     *   └──────────────────────────────────────────────────────────────┘
     *   ┌ 清晰度维度 ─────────────────────────────────────────────────┐
     *   │ SDK: {@code MultiStreamInfo{sDisplayName,iBitRate,            │
     *   │      iHEVCBitRate,iCodecType}} → {@code SMObject.BitRateInfo} │
     *   │      取值口径 bitRate = H264BitRate>0 ? H264BitRate : H265。   │
     *   │      默认档 = DEFAULT_BITRATE(2500)/「超清」，没命中则取        │
     *   │      第一个 < 2500 的档（LineListFragment 第 209-219 行）。     │
     *   │ 本类: mp API 的 {@code stream.hls.rateArray}，字段完全一致。    │
     *   └──────────────────────────────────────────────────────────────┘
     *
     *   ※ 服务端下发的 {@code iWebPriorityRate} 逐房间不同（11342412: TX=60>HS=40；
     *     11279247: HS=100>TX=0；11602058: HS=50>TX=30>AL=20）。本类把它作为**同主机内的次序**。
     *     ⚠️ 但它**不能当主排序键**：hs 索引只有 20~50% 成功，若让 prio=100 的 hs 排在 tx 之前
     *     并被采用，播放器每 2~6 秒重拉索引就会撞 403 → 画面"一卡一卡"。
     *     故主排序键改为**主机**（tx 优先，见 {@link #rankHost}），依据见下方 ctype 专段。
     *
     * ▍★ 根治点：**所有** HLS 地址的 ctype 都要换成 SDK 的 {@code huya_adr_game_sdk}
     *
     *   这是"HTTP 线路一卡一卡、SDK 内部线路却正常"的**根本原因**，也是"其余线路全灭"的原因。
     *
     *   mp.huya.com 下发的 antiCode 带 {@code ctype=tars_mp}（PC 网页端标识）。虎牙边缘据此把
     *   请求按**非 SDK 来源**处理 → AL 直接 403；TX 只给 master 且把子地址标成
     *   {@code TxLiveCode=cold_stream}（冷流）→ 子播放列表恒 403；HS 大幅抖动。
     *   而 SDK 用的是 {@code ctype=huya_adr_game_sdk}，所以 SDK 线路能播。
     *
     *   设备侧实测（脚本见下方"脚本"段）—— **换 ctype 后三条线路全部打通**：
     *     AL  原样 0/6（238B 403 错误页）→ SDK ctype **6/6**，且直出 media playlist（比 TX 少一跳）
     *     TX  原样 6/6 但只给 master、子地址恒 403 → SDK ctype 后 sub **48/48 可播**
     *     HS  原样 3/6（随机）→ SDK ctype **6/6**
     *   端到端（4 房间 × 3 线路 × 10 索引 + 20 分片）**全 200/206 全绿**。
     *
     *   对照变量全部无效（仍 403）：显式 {@code Host: tx.hls.huya.com}、删 TxLiveCode、
     *   删 txyp、删 fs、只留 wsSecret+wsTime+fm、路径 /src→/huyalive、去掉子地址 IP 前缀。
     *   wsSecret 的签名公式（md5(fm0_uid_streamName_seqid_wsTime)）不含 ctype，故替换安全。
     *
     *   ⚠️ **Referer / Origin 与本次故障无关**（曾误判）：SDK ctype 下"裸请求 / 带 Referer /
     *   带 Origin / 两者都带"四种组合各 6 连拉全部 200，无差异。故不改动请求头策略
     *   （{@code NetUtil.createCommonHeaders} 对 huya.com 注入的 Referer/Origin 保持原样）。
     *
     *   为什么 TX 还必须走 master 展开：TX 的 URL 一路是 master（{@code #EXT-X-STREAM-INF}），
     *   ExoPlayer 会自己去拉子地址 —— 而**子地址的 ctype 写在 master 的下一跳里**，
     *   播放器不会替我们改写。所以必须由本类先解一层 master、换好 ctype，
     *   **直接把 media playlist 地址交给播放器**。索引本身 6 房间全 8/8 稳定，多这一次请求完全值得。
     *
     *   副作用（已处理）：旧实现把"master 子地址 403"判为**确定性失败**并跳过整条线路，
     *   于是所有 HLS 候选落选、退到 FLV（短分片，播 1~3 秒即 EOF）→ 就是用户看到的
     *   "播几秒卡一下再自动续播"。现在 master 不再判死，而是展开后重探。
     *
     * ▍通道策略：HLS 优先，FLV 仅兜底（＝ SDK {@code HuyaStreamInfo#getPlayUrl()} 语义）
     *   虎牙 FLV **不是长连接，而是"短分片"**。设备侧裁决实测（{@code _probe_flv_lifetime.py}，
     *   对 hs.flv.huya.com 长读 20s）：无 Referer → 200/403 抖动、0.24MB、**0.28s 即 EOF**；
     *   带 Referer → 200、0.75MB、**0.14s 即 EOF**。即一次 open 只吐 0.24~0.75MB（≈1~3 秒内容）
     *   就 {@code Connection: close}。{@code ProgressiveMediaSource} 读到 EOF 即 STATE_ENDED
     *   → 必须反复重连 → **比 HLS 更卡**。要真用 FLV，唯一出路是自实现
     *   「EOF 后带 Range 续传的伪长连接 DataSource」。
     *
     * ▍指标陷阱（易误判，务必注意）
     *   「hs.flv 成功率 92% vs hs.hls 21%」**不能**证明 FLV 更好：FLV 的"成功"只代表
     *   HTTP 200 且读到数据，它 0.1~0.3s 就断；HLS 的"失败"则是**索引** 403。
     *   两者不同量纲 —— **要看连接寿命（time_total），不是成功率**。
     *   另注：HLS 的分片（.ts）本身是稳的（设备日志 62 次全 206），
     *   403 只出现在 .m3u8 **索引**上，这正是 ExoPlayer 每 2~6s 重拉索引时会撞到的问题
     *   → 靠 {@link #probeRetry} 的重试抹平，而不是换通道。
     *
     * ▍SDK 线路 URL 形态，以及我们三条**有意偏离**（均有实测支撑）
     *   ① **不重签 antiCode**：SDK 的 {@code getSAntiCode} 会丢弃 fm、补 u/seqid 重算 wsSecret
     *      （SdkLiveService 第 369-388 行）。实测重签**更差**：hs 原样 2/6 → 重签 0/6
     *      （uid=0 与 mp 下发的签名配对不上）。故原样透传。参考实现保留在
     *      {@link #sdkSignAntiCode}，**当前不参与候选生成**。
     *   ② **不无条件加 {@code &ratio=}**：SDK 播放时无条件追加（StreamInfoHelper 第 36 行、
     *      LiveInfo 第 69 行）。早期在 **tars_mp** 下实测 hs 加 ratio 反而 0/8（不加 1~3/8）；
     *      换成 SDK ctype 后未再复测，故仍把带 ratio 的候选排到**最后**（variant=2），
     *      既不会污染主路径，又保留"按清晰度取档"的能力。
     *      （注：tx 曾经的 403 与 ratio 无关，是 ctype 问题 —— 见上方 ctype 专段。）
     *   ③ **不用 HLS 的 {@code <stream>_<码率>.m3u8} 后缀形式**（LiveInfo 第 62-64 行）：
     *      在 mp 的 /src 路由上不被接受（hs 各档 0/5）。
     */
    /** 一档清晰度（对齐 SDK {@code BitRateInfo}），并带上该线路该档的可播地址 */
    public static final class QualityOption {
        /** 清晰度名（蓝光6M / 蓝光4M / 超清 / 流畅 …）；可能为「默认档」 */
        public final String displayName;
        /** 码率（kbps）；0 = 未知（如 master 未声明 BANDWIDTH 时的兜底档） */
        public final int bitRate;
        /** 该档的 HLS 地址（**已换 SDK ctype**，可直接播）；null = 该线路无 HLS */
        public final String hlsUrl;
        /** 该档的 FLV 地址；一般 null */
        public final String flvUrl;

        public QualityOption(String displayName, int bitRate, String hlsUrl, String flvUrl) {
            this.displayName = displayName;
            this.bitRate = bitRate;
            this.hlsUrl = hlsUrl;
            this.flvUrl = flvUrl;
        }

        @Override
        public String toString() {
            return displayName + "(" + bitRate + "k)";
        }
    }

    /** 一条线路（对齐 SDK {@code StreamInfo}）：线路 + 该线路下所有清晰度档 */
    public static final class LineOption {
        /** SDK {@code iLineIndex} */
        public final int lineIndex;
        /** 服务端 {@code sCdnType}：TX / AL / HS；null = 未知 */
        public final String cdnType;
        /** 该线路的清晰度档（已按码率降序） */
        public final List<QualityOption> qualities;

        public LineOption(int lineIndex, String cdnType, List<QualityOption> qualities) {
            this.lineIndex = lineIndex;
            this.cdnType = cdnType;
            this.qualities = qualities;
        }

        public String displayLabel() {
            if (!TextUtils.isEmpty(cdnType)) return cdnType.toUpperCase(Locale.US) + " 线路";
            return "线路" + (lineIndex + 1);
        }

        @Override
        public String toString() {
            return displayLabel() + qualities;
        }
    }

    /**
     * 【2026-09-29 新增】一条「子线路」= mp 接口 {@code stream.hls.multiLine[]} 的一个条目。
     *
     * <p>与 {@link LineOption}（总线路）的关键区别：multiLine 的维度是「一条 URL 一个条目」，
     * 天然**不含清晰度**——清晰度由 {@code &ratio=} 派生，属于另一个独立的轴
     * （对应设置里的「清晰度选择」按钮）。因此「频道线路选择」要展示的就是这一层。
     *
     * <p>⚠️ 只取 {@code hls.multiLine}，**绝不取 {@code flv.multiLine}**：
     * 虎牙 FLV 是「短分片」（一次 open 只吐 0.24~0.75MB 就 EOF，约 0.28s），不可直接播放。
     */
    public static final class MultiLineOption {
        /** SDK {@code iLineIndex}（与 {@link LineOption#lineIndex} 同源） */
        public final int lineIndex;
        /** 服务端 {@code sCdnType}：TX / AL / HS；null = 未知 */
        public final String cdnType;
        /** 该子线路的地址（**已换 SDK ctype**，可直接播） */
        public final String url;
        /** 服务端下发的优先级 {@code webPriorityRate}；-1 = 未评级 */
        public final int priority;

        public MultiLineOption(int lineIndex, String cdnType, String url, int priority) {
            this.lineIndex = lineIndex;
            this.cdnType = cdnType;
            this.url = url;
            this.priority = priority;
        }

        @Override
        public String toString() {
            return (cdnType == null ? "?" : cdnType) + "#" + lineIndex;
        }
    }

    /**
     * 【2026-09-29 新增】虎牙自研解析的完整结果 = 采用地址 + 「线路 × 清晰度」矩阵。
     *
     * 背景：自研解析器此前只回传**一个**已探活地址，导致播放器把 variantList 清空、
     * 频道也没有备份源 → 设置里的「频道线路选择」只剩「主源」、「清晰度选择」直接弹
     * 「当前直播源不支持清晰度切换」。这里把解析时本来就已经算出来的线路表（
     * {@code baseSteamInfoList} 的 iLineIndex/sCdnType）与清晰度表（{@code rateArray}，
     * TX 线路则取 master 的 STREAM-INF 子条目）一并带出来，供 UI 展示与切换。
     *
     * 注意：只有 {@link #adoptedUrl} 是**实测探活通过**的地址（可信度最高），
     * 其余档位是服务端下发的等价地址 —— 切换时由播放器自己试，失败会走既有重试/兜底链。
     */
    public static final class StreamMatrix {
        /** 线路列表（**采用线路排第一**）；可能为空（如候选不来自 baseSteamInfoList） */
        public final List<LineOption> lines;
        /** 已探活采用的播放地址 */
        public final String adoptedUrl;
        /** 采用的线路下标（iLineIndex）；-1 = 未知 */
        public final int adoptedLineIndex;
        /** 采用的清晰度名 */
        public final String adoptedQuality;
        /** 采用的 FLV 地址（仅 FLV 通道时有值） */
        public final String adoptedFlv;
        /**
         * 【2026-09-29 新增】子线路表（mp 的 {@code stream.hls.multiLine}）：
         * 一条 URL 一个条目，**不含清晰度**；顺序 = 服务端下发顺序。
         * 该房间没有 multiLine 时为空列表（永不为 null）。
         */
        public final List<MultiLineOption> multiLines;

        StreamMatrix(List<LineOption> lines, String adoptedUrl, int adoptedLineIndex,
                     String adoptedQuality, String adoptedFlv, List<MultiLineOption> multiLines) {
            this.lines = lines;
            this.adoptedUrl = adoptedUrl;
            this.adoptedLineIndex = adoptedLineIndex;
            this.adoptedQuality = adoptedQuality;
            this.adoptedFlv = adoptedFlv;
            this.multiLines = (multiLines == null)
                    ? new ArrayList<MultiLineOption>() : new ArrayList<MultiLineOption>(multiLines);
        }
    }

    public static class HuyaParser {

        private static final String TAGP = "HuyaParser";
        /** 与 doPlayHuya 播放时使用的 UA 保持一致 */
        private static final String HUYA_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        /**
         * 自签用的 uid（SDK 用自己的登录 uid；未登录即 0）。
         *
         * 【2026-09-29】仅服务于 {@link #sdkSignAntiCode} 这份**保留的参考实现**，
         * 当前**不参与候选生成** —— 实测重签比服务端原样 antiCode 更差（hs 原样 2/6 → 重签 0/6）。
         */
        private static final long SDK_UID = 0L;
        /** 整体解析预算：超时不再继续探活，避免起播被拖太久 */
        private static final long TOTAL_BUDGET_MS = 12_000L;
        /** okhttp 最多读多少字节用于识别 playlist / 判断是否 HTML 错误页 */
        private static final int PEEK_BYTES = 64 * 1024;
        /** 探活失败重试次数（虎牙边缘的 403 是逐请求随机的，重试 1 次即可抹平） */
        private static final int PROBE_ATTEMPTS = 3;
        /** 重试间隔：403 由边缘直接拒绝，~100ms 就返回，180ms 后换一次握手大概率命中正常节点 */
        private static final int PROBE_RETRY_GAP_MS = 180;

        /**
         * 【2026-09-29 关键发现】SDK 取流使用的 ctype —— 这是"HTTP 线路播不了、SDK 线路能播"的根因。
         *
         * mp.huya.com 下发的 antiCode 带 {@code ctype=tars_mp}（PC 网页端标识），虎牙边缘会因此
         * 按**非 SDK 来源**处理：AL 直接 403、TX 只给 master 且子地址被标成
         * {@code TxLiveCode=cold_stream} → 恒 403、HS 大幅抖动。
         * 实测（设备侧 curl）只有换成 SDK 的 {@code huya_adr_game_sdk} 才三条线路全通：
         *
         *   线路  原样(tars_mp)              → 换成 SDK ctype 后
         *   AL   0/6（238B 403 错误页）      → 6/6，直出 media playlist（3 片）
         *   TX   6/6 但只给 master，子地址恒 403 → 子地址 48/48 可播
         *   HS   3/6（逐请求随机）           → 6/6
         *
         * 其它 ctype 取值（tars_mp / huya_live / huya / web / tx）一律 403、0 字节。
         * 对照变量均无效：显式 Host 头、删 TxLiveCode / txyp / fs、只留 wsSecret+wsTime+fm、
         * 路径 /src → /huyalive、去掉子地址的 IP 前缀 —— 全部仍 403。
         * 而 wsSecret 的签名公式（md5(fm0_uid_streamName_seqid_wsTime)）不含 ctype，
         * 因此替换 ctype 不会破坏签名。
         *
         * 用法：**候选生成时就要换**（见 {@link #addBuiltCandidates} ①与 multiLine 分支），
         * 因为播放器会在整个播放周期里反复重拉这个索引 URL，索引自带的 ctype 必须是对的。
         */
        private static final String SDK_CTYPE = "huya_adr_game_sdk";

        public interface OnParseResultListener {
            void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch);

            /**
             * 【2026-09-29 新增】带「线路 × 清晰度」矩阵的结果。
             * 默认实现转调旧方法 —— 只实现 3 参版本的老调用方行为完全不变。
             */
            default void onSuccess(StreamMatrix matrix, String hlsUrl, String flvUrl) {
                onSuccess(hlsUrl, flvUrl, true);
            }

            void onFailed(String errorMsg);
        }

        private static class StreamCache {
            String hls;
            String flv;
            long timestamp;
            /** 【2026-09-29 新增】该房间的「线路 × 清晰度」矩阵，命中缓存时一并回传
             *  （否则 60s 内重复解析会拿不到矩阵 → 设置里的线路/清晰度列表又变空） */
            StreamMatrix matrix;

            StreamCache(String hls, String flv) {
                this(hls, flv, null);
            }

            StreamCache(String hls, String flv, StreamMatrix matrix) {
                this.hls = hls;
                this.flv = flv;
                this.matrix = matrix;
                this.timestamp = System.currentTimeMillis();
            }

            boolean isValid() {
                return System.currentTimeMillis() - timestamp < 60_000L;
            }
        }

        private static final ConcurrentHashMap<Long, StreamCache> sCache = new ConcurrentHashMap<>();

        /** 【2026-09-29】403/404 重试前清掉指定房间的解析缓存，确保拿到新签名地址而非 60s 内的旧地址 */
        public static void clearCache(long roomId) {
            if (roomId > 0) sCache.remove(roomId);
        }

        private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
        private static final OkHttpClient sClient = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                // 【2026-10-25 代理坑修复】显式绕过系统代理：设备挂着失效代理时（实测案例：按模拟器
                //   抓包教程设的 10.0.2.2:8899，真机上不可达），OkHttp 默认走系统代理 → mp API 与
                //   PC 网页全部 6s 超时 → 虎牙解析全失败（且异常被 collectFromMpApi 的 .d 日志吞掉，
                //   release 包看不到，极难排查）。对齐 NetUtil/RybHttp/NbMovieHttp/JzApi 的既有惯例。
                .proxy(java.net.Proxy.NO_PROXY)
                .build();

        /**
         * 一条线路 —— 对应 SDK {@code com.duowan.HUYA.StreamInfo}。
         *
         * SDK 里线路由 {@code StreamInfo.iLineIndex} 标识，URL 存在
         * {@code PlayerHelper.HlsUrl} / {@code FlvUrl} 两个以 lineIndex 为 key 的 Map 中
         * （见 {@code SdkLiveService.parseGetLivingInfoRsp} 第 462/466 行）。
         * 本类用同一口径描述 mp API `baseSteamInfoList` 里的一条线路。
         *
         * 【2026-09-29 实测】{@link #priority} 来自服务端 {@code iWebPriorityRate}，
         * **逐房间动态变化**（房间A: TX=60>HS=40>AL=-1；房间B: HS=100>TX=0>AL=-1；
         * 房间C: HS=50>TX=30>AL=20）—— 这正是 SDK {@code LineInfo.priorityRate}
         * 想表达但该版本未赋值的量。因此**不能写死主机名排序**，必须按服务端下发的值排。
         */
        private static final class LineMeta {
            /** StreamInfo.iLineIndex —— SDK 的线路主键 */
            final int lineIndex;
            /** StreamInfo.sCdnType（AL / TX / HS） */
            final String cdnType;
            /** StreamInfo.iWebPriorityRate —— 服务端下发的线路优先级，越大越优先；-1 视为未评级 */
            final int priority;
            /** sHlsUrl 的路径形态（/src 或 /huyalive），仅用于日志 */
            final String hlsPath;

            LineMeta(int lineIndex, String cdnType, int priority, String hlsPath) {
                this.lineIndex = lineIndex;
                this.cdnType = cdnType;
                this.priority = priority;
                this.hlsPath = hlsPath;
            }

            @Override
            public String toString() {
                return "线路" + lineIndex + "(" + cdnType + ",prio=" + priority + "," + hlsPath + ")";
            }
        }

        /**
         * 一档清晰度 —— 对应 SDK {@code SMObject.BitRateInfo}。
         * 源数据是 mp API {@code stream.hls.rateArray} / {@code stream.flv.rateArray}
         * 里的 {@code MultiStreamInfo}（字段名与 SDK {@code com.duowan.HUYA.MultiStreamInfo} 完全一致）。
         */
        private static final class RateMeta {
            /** MultiStreamInfo.sDisplayName，如「蓝光6M / 蓝光4M / 超清 / 流畅」 */
            final String displayName;
            /** 有效码率 = H264BitRate>0 ? H264BitRate : H265BitRate —— 与 SDK 取值口径一致 */
            final int bitRate;
            final int hevcBitRate;
            final int codecType;

            RateMeta(String displayName, int bitRate, int hevcBitRate, int codecType) {
                this.displayName = displayName;
                this.bitRate = bitRate;
                this.hevcBitRate = hevcBitRate;
                this.codecType = codecType;
            }

            @Override
            public String toString() {
                return displayName + "(" + bitRate + "kbps" + (codecType == 1 ? ",H265" : "") + ")";
            }
        }

        /** 候选播放地址 */
        private static final class Candidate {
            final String url;
            final String label;
            final boolean hls;
            final int hostRank;
            /** 所属线路（SDK iLineIndex）；-1 = 非 baseSteamInfoList 派生（multiLine / 网页源） */
            final int lineIndex;
            /** 服务端下发的线路优先级；Integer.MIN_VALUE = 未知 */
            final int priority;
            /** 该候选携带的清晰度标签；null = 服务端默认档 */
            final String quality;
            /** 变体次序：0 = 原样（服务端 antiCode 直接透传）；1 = 指定清晰度档。越小越优先 */
            final int variant;
            /** 服务端 sCdnType（TX / AL / HS）；null = 未知（非线路派生候选） */
            final String cdnType;

            Candidate(String url, String label, boolean hls) {
                this(url, label, hls, -1, Integer.MIN_VALUE, null, 0);
            }

            Candidate(String url, String label, boolean hls,
                      int lineIndex, int priority, String quality, int variant) {
                this(url, label, hls, lineIndex, priority, quality, variant, null);
            }

            Candidate(String url, String label, boolean hls,
                      int lineIndex, int priority, String quality, int variant, String cdnType) {
                this.url = url;
                this.label = label;
                this.hls = hls;
                this.hostRank = rankHost(url);
                this.lineIndex = lineIndex;
                this.priority = priority;
                this.quality = quality;
                this.variant = variant;
                this.cdnType = cdnType;
            }
        }

        /** 探活结果 */
        private static final class Probe {
            final int code;
            final String ctype;
            final boolean playable;
            final String note;
            /**
             * 该失败是否**值得重试**。
             *
             * 【2026-09-29 新增】区分两类失败，避免把探活预算浪费在确定失败的线路上：
             *   retryable=true  —— 索引本身 403/404/空 playlist：边缘逐请求随机拒绝，
             *                      隔 PROBE_RETRY_GAP_MS 再拉基本能命中（实测 ~25% → 3 次 ~58%）。
             *   retryable=false —— HTML 错误页 / 根本不是 playlist 之类的**确定性**失败，
             *                      重试纯属浪费（单条线路白花 ~1.5s，会把整轮解析拖过预算）。
             *                      ⚠️ master playlist **不在**此列：它由上层展开子地址 + 换 SDK ctype
             *                      后重新探活（见 {@link #applySdkCtype}）—— 那是 TX 线路唯一的可用入口。
             */
            final boolean retryable;

            /**
             * 【2026-09-29 新增】响应体是否是 **master playlist**（含 #EXT-X-STREAM-INF 且无 #EXTINF）。
             *
             * 虎牙 TX 线路的索引恒返回 master，其子播放列表地址带 {@code TxLiveCode=cold_stream}；
             * 不能据此判死 —— 需展开子地址并把 ctype 换成 SDK 值后重新探活（见 {@link #applySdkCtype}）。
             */
            final boolean master;

            /** master playlist 的第一个子播放列表地址（已解析为绝对 URL）；非 master 时为 null。 */
            final String masterUri;

            /**
             * 【2026-09-29 新增】master playlist 的**全部**子播放列表（＝该线路的清晰度阶梯）。
             *
             * 虎牙 TX 线路只返回 master（{@code #EXT-X-STREAM-INF} 多档），此前只取第一个子地址 → 
             * 设置里的「清晰度选择」拿不到任何档位（variantList 被清空）。这里把 STREAM-INF 全部收下来，
             * 上层即可把「带宽 → 清晰度名」建成真正的档位表。非 master 时为 null。
             */
            final List<MasterChild> masterChildren;

            Probe(int code, String ctype, boolean playable, String note) {
                this(code, ctype, playable, note, true);
            }

            Probe(int code, String ctype, boolean playable, String note, boolean retryable) {
                this(code, ctype, playable, note, retryable, false, null);
            }

            Probe(int code, String ctype, boolean playable, String note,
                  boolean retryable, boolean master, String masterUri) {
                this(code, ctype, playable, note, retryable, master, masterUri, null);
            }

            Probe(int code, String ctype, boolean playable, String note, boolean retryable,
                  boolean master, String masterUri, List<MasterChild> masterChildren) {
                this.code = code;
                this.ctype = ctype;
                this.playable = playable;
                this.note = note;
                this.retryable = retryable;
                this.master = master;
                this.masterUri = masterUri;
                this.masterChildren = masterChildren;
            }

            @Override
            public String toString() {
                return "HTTP " + code + " " + ctype + " playable=" + playable
                        + (note == null || note.isEmpty() ? "" : " (" + note + ")");
            }
        }

        /**
         * 主机次序（数值越小越优先）—— **主排序键**（排在服务端 priority 之前）。
         *
         * 【2026-09-29 实测定案 · 三条线路全绿】此前按服务端 {@code iWebPriorityRate} 降序排（"对齐 SDK"），
         * 会让抖动最大的 HS 排到最前；实测后改回按**实测稳定性**排主机：
         *
         *   tx.hls.huya.com  索引 6 房间 × 8 连拉 = **全 200**（100% 稳定）；
         *                    返回 master，子地址换 SDK ctype 后 **48/48 可播**。
         *                    → 首选。
         *   al.hls.huya.com  换成 SDK ctype 后索引 **4 房间 × 10 连拉全 200**，且**直出 media
         *                    playlist**（比 tx 少一跳），分片 206 稳定。→ 次选。
         *                    （注：AL 的 web_prio 恒为 -1，服务端不建议给网页端用；
         *                      但换成 SDK ctype 后它对 SDK 来源完全可用。）
         *   hs.hls.huya.com  索引抖动最大（单次实测 20%~100% 波动），换 SDK ctype 后仍偶发
         *                    403，但成功时是可直接播的 media playlist。→ 兜底。
         *
         * ⚠️ 排序键顺序很关键：若把服务端 priority 放在主机之前，prio=100 的 hs 会排在
         * prio=0 的 tx 前面；hs 只要探活偶中一次就会被采用，随后索引抖动 → 又回到"一卡一卡"。
         *
         * 说明：三条线路现在**都能播**（都是 ctype 修复的功劳，见 {@link #SDK_CTYPE}），
         * 这里排的是"优先用谁"，不是"谁能用"——所以坏线路已无需删除，探活会自然过滤。
         *
         * 【2026-09-29 App 内实测】把本函数临时改成各线路优先，逐条跑真实播放：
         *   AL 优先：采用 al.hls.huya.com（**直出 media，无 master 展开**）→ 探活 958ms，
         *            96 次请求 **0 失败 / 0 异常事件**
         *   HS 优先：采用 hs.hls.huya.com（直出 media）→ 探活 766~838ms，
         *            72 次请求 **0 失败 / 0 异常事件**
         *   TX 优先：master 展开后采用 → 探活 1655~2637ms（多一次索引往返），
         *            68 次请求 **0 失败 / 0 异常事件**
         * 选 TX 为第一优先的理由：TX 是虎牙自带的腾讯 CDN（边缘为 *.smtcdns.net），
         * 容量与水印最新度最有保障；AL 的 web_prio 恒为 -1（服务端不建议给网页端），
         * 更像备用线路——高峰期容量存疑，故放在 TX 之后、但仍优于抖动最大的 HS。
         */
        private static int rankHost(String url) {
            if (url == null) return 9;
            if (url.contains("//tx.")) return 0;
            if (url.contains("//al.")) return 1;
            if (url.contains("//hs.")) return 2;
            return 3;
        }

        /** FLV 通道判定：路径以 .flv 结尾，或主机是 *.flv.huya.com / *.flv.huya.cn */
        private static boolean isFlvUrl(String url) {
            if (TextUtils.isEmpty(url)) return false;
            int q = url.indexOf('?');
            String head = (q >= 0 ? url.substring(0, q) : url).toLowerCase(Locale.US);
            return head.endsWith(".flv") || head.contains(".flv.huya.com/")
                    || head.contains(".flv.huya.cn/");
        }

        public static void parse(final int roomId, final OnParseResultListener listener) {
            if (listener == null) return;
            final long roomLong = roomId;

            StreamCache cached = sCache.get(roomLong);
            if (cached != null && cached.isValid()) {
                String hit = cached.hls != null ? cached.hls : cached.flv;
                // 【2026-09-29】缓存里带着上次的线路/清晰度矩阵就一并回传
                postSuccess(listener, cached.hls, cached.flv, cached.matrix);
                return;
            }

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    long t0 = System.currentTimeMillis();
                    try {
                        List<Candidate> cands = new ArrayList<>();
                        List<RateMeta> rates = new ArrayList<>();
                        // 【2026-09-29 新增】子线路表（mp hls.multiLine）：不含清晰度，供「频道线路选择」直接列/切
                        List<MultiLineOption> multiLines = new ArrayList<>();
                        collectFromMpApi(roomLong, cands, rates, multiLines);
                        int mpCount = cands.size();
                        if (cands.isEmpty()) {
                            LogBridge.i(TAGP, "mp API 未取到候选，改用 PC 网页源");
                            collectFromWebPage(roomLong, cands, rates);
                        }
                        if (cands.isEmpty()) {
                            postFailed(listener, "无候选地址（mp API 与网页均未取到流信息）");
                            return;
                        }

                        // 【2026-09-29 对齐 SDK】HLS 优先、FLV 仅兜底 ——
                        // 与 HuyaSDKParser.HuyaStreamInfo#getPlayUrl() 的语义完全一致。
                        // FLV 曾试作主通道并被否决：它是"短分片"（一次 open 只给 0.24~0.75MB 就 EOF），
                        // 会被 ProgressiveMediaSource 反复 STATE_ENDED 重连，比 HLS 更卡（见类注释）。
                        // 排序：通道(HLS优先) → 主机(tx优先) → 线路(服务端降序) → 变体(raw优先)
                        Collections.sort(cands, new java.util.Comparator<Candidate>() {
                            @Override
                            public int compare(Candidate a, Candidate b) {
                                // ① 通道：HLS 优先（＝ SDK HuyaStreamInfo#getPlayUrl 的语义）
                                if (a.hls != b.hls) return a.hls ? -1 : 1;
                                // ② 主机：tx 优先 —— **主排序键**，必须排在服务端 priority 之前。
                                //    实测 tx 索引 100% 稳定、展开后 48/48 可播；hs 索引仅 20~50%，
                                //    若让它凭 prio=100 排在 tx 前并被采用，索引抖动会让画面"一卡一卡"。
                                //    详见 rankHost 的实测说明。
                                if (a.hostRank != b.hostRank) {
                                    return Integer.compare(a.hostRank, b.hostRank);
                                }
                                // ③ 线路：服务端下发的 iWebPriorityRate 降序（同主机内偏好服务端推荐档）。
                                //    未评级（-1 / Integer.MIN_VALUE / multiLine 派生）一律垫底。
                                int ra = a.priority > 0 ? a.priority : -1;
                                int rb = b.priority > 0 ? b.priority : -1;
                                if (ra != rb) return Integer.compare(rb, ra);
                                // ④ 变体：raw 优先于 ratio（实测 ratio 对 hs 无益且会让索引 0/8）
                                if (a.variant != b.variant) {
                                    return Integer.compare(a.variant, b.variant);
                                }
                                return a.label.compareTo(b.label);
                            }
                        });
                        List<Candidate> uniq = new ArrayList<>();
                        Set<String> seen = new HashSet<>();
                        for (Candidate c : cands) {
                            if (seen.add(c.url)) uniq.add(c);
                        }

                        LogBridge.i(TAGP, "候选 " + uniq.size() + " 个（mp源" + mpCount + "）："
                                + describe(uniq));

                        Candidate bestAny = null;
                        for (Candidate c : uniq) {
                            if (System.currentTimeMillis() - t0 > TOTAL_BUDGET_MS) {
                                LogBridge.d(TAGP, "探活超预算（" + TOTAL_BUDGET_MS + "ms），停止后续候选");
                                break;
                            }
                            // 探活：403 是逐请求随机的（边缘节点直接拒），隔 ~180ms 重试一次即可抹平抖动
                            Probe r = probeRetry(c.url, PROBE_ATTEMPTS);


                            // 【2026-09-29 关键修复】master playlist → 展开子播放列表并换成 SDK ctype 再探活。
                            //
                            // 这是"HTTP 线路一卡一卡"的根治点：虎牙 TX 线路的索引 6 房间全 8/8 稳定，
                            // 但它只返回 master，子地址原文带 TxLiveCode=cold_stream 恒 403；
                            // 旧实现据此把整条 TX 判死 → 所有 HLS 候选落选 → 退到 FLV（短分片，
                            // 播 1~3 秒就 Connection: close）→ 表现为"播几秒卡一下再自动续播"。
                            // 展开子地址 + 换 ctype=huya_adr_game_sdk 后 6 房间 48/48 全 200 可播。
                            if (r.master && !TextUtils.isEmpty(r.masterUri)) {
                                String fixed = applySdkCtype(r.masterUri);
                                Probe r2 = probeRetry(fixed, PROBE_ATTEMPTS);


                                if (r2.playable && probeStable(fixed, true)) {



                                    StreamMatrix mx = buildMatrix(uniq, c, r.masterChildren, rates,
                                            multiLines, fixed, null);

                                    putCache(roomLong, fixed, null, mx);
                                    postSuccess(listener, fixed, null, mx);
                                    return;
                                }
                            }

                            if (r.playable && probeStable(c.url, c.hls)) {



                                String hls = c.hls ? c.url : null;
                                String flv = c.hls ? null : c.url;
                                StreamMatrix mx = buildMatrix(uniq, c, null, rates, multiLines, c.url, flv);

                                putCache(roomLong, hls, flv, mx);
                                postSuccess(listener, hls, flv, mx);
                                return;
                            }
                            if (bestAny == null && r.playable) bestAny = c;
                        }

                        if (bestAny != null) {
                            // 探活过了但复拉确认没过：仍交给播放器试一次，比直接判失败强
                            LogBridge.w(TAGP, "⚠️ 无候选通过复拉确认，退而使用 " + bestAny.label);
                            String bh = bestAny.hls ? bestAny.url : null;
                            String bf = bestAny.hls ? null : bestAny.url;
                            StreamMatrix mx = buildMatrix(uniq, bestAny, null, rates, multiLines, bestAny.url, bf);
                            putCache(roomLong, bh, bf, mx);
                            postSuccess(listener, bh, bf, mx);
                            return;
                        }
                        postFailed(listener, "全部 " + uniq.size() + " 个候选探活失败");
                    } catch (Throwable t2) {
                        LogBridge.e(TAGP, "解析异常", t2);
                        postFailed(listener, "解析异常：" + t2);
                    }
                }
            }, "HuyaParser-" + roomId);
            t.start();
        }

        // ============================== 候选采集 ==============================

        /** 源 1：mp.huya.com 的 profileRoom 接口（无需登录，JSON 结构稳定） */
        private static void collectFromMpApi(long roomId, List<Candidate> out, List<RateMeta> ratesOut,
                                             List<MultiLineOption> multiOut) {
            try {
                String url = "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=" + roomId
                        + "&showSecret=1&ver=1&_=" + System.currentTimeMillis();
                String json = httpGetString(url, "https://mp.huya.com/");
                if (TextUtils.isEmpty(json)) {
                    LogBridge.w(TAGP, "mp API 返回空");
                    return;
                }
                JSONObject root = new JSONObject(json);
                JSONObject data = root.optJSONObject("data");
                if (data == null) {
                    LogBridge.w(TAGP, "mp API 无 data 字段");
                    return;
                }
                LogBridge.d(TAGP, "mp API liveStatus=" + data.optString("liveStatus"));
                JSONObject stream = data.optJSONObject("stream");
                if (stream == null) {
                    LogBridge.w(TAGP, "mp API 无 stream 字段（可能未开播）");
                    return;
                }

                // 1) 成品 HLS（multiLine）：服务端已拼好，但 **antiCode 里的 ctype=tars_mp 必须换成
                //    SDK 的 huya_adr_game_sdk**（见 addBuiltCandidates ①的实测说明），否则：
                //      AL 恒 403、TX 只给 master 且子地址冷、HS 大幅抖动。
                JSONObject hlsObj = stream.optJSONObject("hls");
                JSONArray ml = hlsObj == null ? null : hlsObj.optJSONArray("multiLine");
                if (ml != null) {
                    for (int i = 0; i < ml.length(); i++) {
                        JSONObject e = ml.optJSONObject(i);
                        String u = httpsUp(e == null ? null : e.optString("url"));
                        if (isHlsUrl(u) && u.contains("wsSecret=")) {
                            out.add(new Candidate(applySdkCtype(u),
                                    "mp-multiLine" + i + "-sdkctype", true));
                            out.add(new Candidate(u, "mp-multiLine" + i + "-raw", true));

                            // 【2026-09-29 新增】同时把这条登记为「子线路」，供 UI 直接列/切。
                            // 维度是「一条 URL 一个条目」，不含清晰度 —— 清晰度是独立的另一个轴。
                            // ⚠️ URL 必须换成 SDK ctype（tars_mp 会被边缘按非 SDK 来源拒绝 → 403）。
                            if (multiOut != null) {
                                String cdn = e == null ? null : e.optString("cdnType");
                                multiOut.add(new MultiLineOption(
                                        e == null ? -1 : e.optInt("lineIndex", -1),
                                        TextUtils.isEmpty(cdn) ? null : cdn,
                                        applySdkCtype(u),
                                        e == null ? -1 : e.optInt("webPriorityRate", -1)));
                            }
                        }
                    }
                    if (multiOut != null) {
                        LogBridge.i(TAGP, "子线路（hls.multiLine）" + multiOut.size() + " 条：" + multiOut);
                    }
                }

                // 1b) 成品 FLV（multiLine）—— **仅作兜底**（HLS 全部候选都不可用时才轮到它）。
                //     注意：FLV 的"高成功率"是假象 —— 它一次 open 只吐 0.24~0.75MB 就 EOF，
                //     交给 ProgressiveMediaSource 会反复 STATE_ENDED 重连，比 HLS 更卡（见类注释）。
                JSONObject flvObj = stream.optJSONObject("flv");
                JSONArray mlF = flvObj == null ? null : flvObj.optJSONArray("multiLine");
                if (mlF != null) {
                    for (int i = 0; i < mlF.length(); i++) {
                        JSONObject e = mlF.optJSONObject(i);
                        String u = e == null ? null : e.optString("url");
                        if (isFlvUrl(u) && u.contains("wsSecret=")) {
                            out.add(new Candidate(u, "mp-flvLine" + i + "-raw", false));
                        }
                    }
                }

                // 2) 清晰度表 —— 对齐 SDK SMObject.BitRateInfo（源数据 = mp 的 MultiStreamInfo）
                List<RateMeta> rates = parseRateArray(stream);
                RateMeta defRate = pickDefaultRate(rates);
                if (ratesOut != null) {
                    ratesOut.clear();
                    ratesOut.addAll(rates);
                }
                LogBridge.i(TAGP, "清晰度 " + rates.size() + " 档：" + rates
                        + "；SDK 口径默认档 = " + (defRate == null ? "服务端默认" : defRate.toString()));

                // 3) 线路表 —— 对齐 SDK StreamInfo.iLineIndex（+ PlayerHelper.HlsUrl/FlvUrl 的 key）
                JSONArray base = stream.optJSONArray("baseSteamInfoList");
                if (base != null) {
                    List<JSONObject> lines = new ArrayList<>();
                    for (int i = 0; i < base.length(); i++) {
                        JSONObject o = base.optJSONObject(i);
                        if (o != null) lines.add(o);
                    }
                    // SDK LineListFragment#lineIndexsSort 就是按 lineIndex 冒泡升序展开展示
                    Collections.sort(lines, new java.util.Comparator<JSONObject>() {
                        @Override
                        public int compare(JSONObject a, JSONObject b) {
                            return Integer.compare(a.optInt("iLineIndex"), b.optInt("iLineIndex"));
                        }
                    });
                    StringBuilder sb = new StringBuilder();
                    for (JSONObject o : lines) {
                        sb.append(' ').append(new LineMeta(o.optInt("iLineIndex"), o.optString("sCdnType"),
                                o.optInt("iWebPriorityRate", -1), pathOf(o.optString("sHlsUrl"))));
                    }
                    LogBridge.i(TAGP, "线路 " + lines.size() + " 条（lineIndex 升序）：" + sb);
                    for (JSONObject o : lines) {
                        addBuiltCandidates(o, "mp-line" + o.optInt("iLineIndex"), defRate, rates, out);
                    }
                }
            } catch (Throwable t) {
                LogBridge.d(TAGP, "mp API 异常：" + t);
            }
        }

        /** 源 2：PC 网页 www.huya.com/<roomId> 里的 hyPlayerConfig.stream（ctype=huya_live） */
        private static void collectFromWebPage(long roomId, List<Candidate> out, List<RateMeta> ratesOut) {
            try {
                String html = httpGetString("https://www.huya.com/" + roomId, "https://www.huya.com/");
                if (TextUtils.isEmpty(html)) {
                    LogBridge.w(TAGP, "PC 网页返回空");
                    return;
                }
                // 页面里是 JS 字面量：stream: {"data":[{...,"gameStreamInfoList":[...]}]}
                int k = html.indexOf("stream:");
                if (k < 0) k = html.indexOf("\"stream\":");
                if (k < 0) {
                    LogBridge.i(TAGP, "PC 网页未找到 stream 字段");
                    return;
                }
                int brace = html.indexOf('{', k);
                String js = extractBalancedJson(html, brace);
                if (TextUtils.isEmpty(js)) {
                    LogBridge.w(TAGP, "PC 网页 stream 括号匹配失败");
                    return;
                }
                JSONObject stream = new JSONObject(js);
                JSONArray data = stream.optJSONArray("data");
                JSONObject d0 = data == null ? null : data.optJSONObject(0);
                JSONArray gl = d0 == null ? null : d0.optJSONArray("gameStreamInfoList");
                if (gl == null) {
                    LogBridge.w(TAGP, "PC 网页无 gameStreamInfoList");
                    return;
                }
                LogBridge.d(TAGP, "PC 网页 gameStreamInfoList=" + gl.length() + " 条");
                // 【2026-09-29 对齐 SDK】网页源没有独立的 rateArray 时，只生成「原样」候选
                // （网页的 gameStreamInfoList 各条目本身就是服务端按当前用户档位拼好的地址）。
                List<RateMeta> webRates = new ArrayList<>();
                JSONArray webRa = d0 == null ? null : d0.optJSONArray("vMultiStreamInfo");
                if (webRa != null) {
                    for (int i = 0; i < webRa.length(); i++) {
                        JSONObject e = webRa.optJSONObject(i);
                        if (e == null) continue;
                        int h264 = e.optInt("iBitRate", 0);
                        int h265 = e.optInt("iHEVCBitRate", -1);
                        webRates.add(new RateMeta(e.optString("sDisplayName"),
                                h264 > 0 ? h264 : h265, h265, e.optInt("iCodecType", 0)));
                    }
                }
                RateMeta webDef = pickDefaultRate(webRates);
                if (!webRates.isEmpty()) {
                    LogBridge.i(TAGP, "PC 网页清晰度 " + webRates.size() + " 档：" + webRates
                            + "；默认档 = " + (webDef == null ? "服务端默认" : webDef.toString()));
                }
                if (ratesOut != null && ratesOut.isEmpty()) {
                    ratesOut.addAll(webRates);
                }
                for (int i = 0; i < gl.length(); i++) {
                    addBuiltCandidates(gl.optJSONObject(i), "web-line" + i, webDef, webRates, out);
                }
            } catch (Throwable t) {
                LogBridge.d(TAGP, "PC 网页异常：" + t);
            }
        }

        /** 清晰度档位上限常量：与 SDK {@code PlayerHelper.DEFAULT_BITRATE} 保持一致 */
        private static final int SDK_DEFAULT_BITRATE = 2500;

        /**
         * 解析清晰度表 —— 对齐 SDK 的 {@code MultiStreamInfo[]} → {@code SMObject.BitRateInfo}。
         *
         * SDK 在 {@code SdkLiveService.parseGetLivingInfoRsp}（第 420-450 行）逐条做三件事，
         * 本方法逐字复刻（顺序也一致，因为 SDK 的填充依赖遍历顺序）：
         *
         *   ① 去重：{@code sDisplayName} 已出现过就跳过。mp 的 rateArray 已经去重，这里再挡一次。
         *   ② 取值：{@code H264BitRate = iBitRate}、{@code H265BitRate = iHEVCBitRate}。
         *   ③ **占位档填充**（第 434-447 行，最容易漏的一条）：
         *      若某档是 {@code iBitRate=0 且 iHEVCBitRate=-1}（服务端用它表示"当前档位占位"），
         *      则在遍历时把它填成 **当时已知的最大码率 {@code i}**。
         *      {@code i} 的初值是 {@code StreamSettingNotice.iBitRate}（服务端下发的默认码率）。
         *      ⇒ HTTP 侧对应物 = mp `hls.multiLine[].url` 里那个 {@code ratio=2000} 参数，
         *        见 {@link #serverDefaultBitrate}；取不到才退回 {@link #SDK_DEFAULT_BITRATE}。
         *
         * 实测（房间 11342412）：
         *   rateArray = 蓝光6M(0,-1) 蓝光6M(6000,-1) 蓝光4M(4000,4000) 超清(2000,2000) 流畅(500,500)
         *   mp multiLine ratio = 2000  ⇒ i 初值 2000
         *   → 蓝光6M 占位档填成 2000；随后 蓝光6M(6000) 把 i 抬到 6000；蓝光4M 把 i 再抬到 4000…（不抬）
         *   最终有效档：2000 / 6000 / 4000 / 2000 / 500
         * 默认档仍走 {@link #pickDefaultRate} 的「超清」规则 → 2000kbps，与 mp 自带的 ratio 一致。
         */
        private static List<RateMeta> parseRateArray(JSONObject stream) {
            List<RateMeta> out = new ArrayList<>();
            if (stream == null) return out;
            JSONObject hlsObj = stream.optJSONObject("hls");
            JSONArray arr = hlsObj == null ? null : hlsObj.optJSONArray("rateArray");
            if (arr == null) {
                JSONObject flvObj = stream.optJSONObject("flv");
                arr = flvObj == null ? null : flvObj.optJSONArray("rateArray");
            }
            if (arr == null) return out;

            // ① 去重（SDK 第 421-429 行）—— 但**同一名字保留码率更大的那条**。
            //
            // 【2026-09-29 修复「清晰度少一档」】roomId 11342412 的 rateArray 里「蓝光6M」出现两次：
            //   {sDisplayName:蓝光6M, iBitRate:0,   iCodecType:1}  ← H265 占位档（码率取不到）
            //   {sDisplayName:蓝光6M, iBitRate:6000, iCodecType:0}
            // 旧实现「先到先得」留下了占位档，稍后被 SDK 回填成 serverDefaultBitrate(2000kbps) →
            //   ① 「蓝光6M」名不符实：建出来的地址是 ratio=2000 的流；
            //   ② 它与「超清」(2000kbps) 的 ratio 地址**完全相同**，在候选表按 URL 去重时被合并，
            //      结果「超清」整档凭空消失（AL/HS 只剩 蓝光4M/蓝光6M/流畅 三档）。
            Map<String, JSONObject> dedup = new LinkedHashMap<>();
            for (int k = 0; k < arr.length(); k++) {
                JSONObject e = arr.optJSONObject(k);
                if (e == null) continue;
                String dn = e.optString("sDisplayName");
                if (TextUtils.isEmpty(dn)) continue;
                JSONObject prev = dedup.get(dn);
                if (prev == null || knownBitrate(e) > knownBitrate(prev)) dedup.put(dn, e);
            }

            int i = serverDefaultBitrate(stream);   // ≙ StreamSettingNotice.iBitRate
            String placeholderName = null;
            for (JSONObject e : dedup.values()) {
                String dn = e.optString("sDisplayName");
                int h264 = e.optInt("iBitRate", 0);
                int h265 = e.optInt("iHEVCBitRate", -1);
                // 占位档：SDK 先记名字，等 i 抬升后再回填
                if (h264 == 0 && h265 == -1) placeholderName = dn;
                if (h264 > i) {
                    i = h264;
                }
                if (h265 > i) {
                    i = h265;
                }
                if (h265 == -1 && h264 == 0) {
                    h264 = i;   // ← SDK 第 445-447 行的回填
                }
                int eff = h264 > 0 ? h264 : h265;
                out.add(new RateMeta(dn, eff, h265, e.optInt("iCodecType", 0)));
            }
            if (placeholderName != null) {
                LogBridge.d(TAGP, "清晰度占位档「" + placeholderName + "」按 SDK 规则回填为 " + i + "kbps");
            }
            return out;
        }

        /** 该清晰度条目已知的码率（H264 优先，其次 H265）；两个都取不到返回 -1（＝"未知档"） */
        private static int knownBitrate(JSONObject e) {
            if (e == null) return -1;
            int m = Math.max(e.optInt("iBitRate", 0), e.optInt("iHEVCBitRate", -1));
            return m > 0 ? m : -1;
        }

        /**
         * HTTP 侧的 {@code StreamSettingNotice.iBitRate} 等价物 ——
         * mp 的 {@code hls.multiLine[].url} 里服务端自己带的 {@code ratio=<n>}。
         * 实测 mp 下发的就是客户端应使用的默认档（如 2000），故直接采信。
         * 取不到时退回 {@link #SDK_DEFAULT_BITRATE}（＝ SDK 的 {@code PlayerHelper.DEFAULT_BITRATE}）。
         */
        private static int serverDefaultBitrate(JSONObject stream) {
            try {
                JSONObject hlsObj = stream.optJSONObject("hls");
                JSONArray ml = hlsObj == null ? null : hlsObj.optJSONArray("multiLine");
                if (ml != null) {
                    for (int k = 0; k < ml.length(); k++) {
                        JSONObject e = ml.optJSONObject(k);
                        String u = e == null ? null : e.optString("url");
                        if (TextUtils.isEmpty(u)) continue;
                        int k2 = u.indexOf("ratio=");
                        if (k2 < 0) continue;
                        int s = k2 + 6;
                        int p = s;
                        while (p < u.length() && Character.isDigit(u.charAt(p))) p++;
                        if (p > s) {
                            int v = Integer.parseInt(u.substring(s, p));
                            if (v > 0) return v;
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            return SDK_DEFAULT_BITRATE;
        }

        /**
         * 选默认清晰度档 —— 复刻 SDK 的两段规则，顺序一致：
         *
         * ① {@code SdkLiveService} 第 489-496 行：bitRate 初值为 {@code DEFAULT_BITRATE}=2500、
         *    disPlayName「超清」；若列表里有「超清」就直接用它（第 505 行把 {@code z4} 置真）。
         * ② {@code LineListFragment#setBitRateData} 第 209-219 行：没匹配上时，
         *    取**第一个** {@code value < PlayerHelper.bitRate} 的档（列表按服务端顺序，
         *    通常由高到低，所以等价于"≤2500 的最高档"）。
         *
         * 实测 rateArray 例（房间 11342412）：
         *   蓝光6M(0/H265) 蓝光6M(6000) 蓝光4M(4000) 超清(2000) 流畅(500)  → 命中「超清」= 2000kbps
         */
        private static RateMeta pickDefaultRate(List<RateMeta> rates) {
            if (rates == null || rates.isEmpty()) return null;
            for (RateMeta r : rates) {
                if ("超清".equals(r.displayName) && r.bitRate > 0) return r;
            }
            for (RateMeta r : rates) {
                if (r.bitRate > 0 && r.bitRate < SDK_DEFAULT_BITRATE) return r;
            }
            for (RateMeta r : rates) {
                if (r.bitRate > 0) return r;
            }
            return null;
        }

        /** 取 URL 的路径首段（/src 或 /huyalive），仅用于日志比对 */
        private static String pathOf(String base) {
            if (TextUtils.isEmpty(base)) return "-";
            int i = base.indexOf("://");
            int j = i < 0 ? 0 : i + 3;
            int k = base.indexOf('/', j);
            if (k < 0 || k + 1 >= base.length()) return "-";
            String rest = base.substring(k + 1);
            int m = rest.indexOf('/');
            return "/" + (m < 0 ? rest : rest.substring(0, m));
        }

        /**
         * 从一条线路（＝ SDK {@code com.duowan.HUYA.StreamInfo}）派生候选。
         * **线路与清晰度的选择口径完全对齐 SDK**：
         *   线路主键 = {@code iLineIndex}；优先级 = {@code iWebPriorityRate}；
         *   清晰度 = {@code rateArray}（SDK 的 {@code SMObject.BitRateInfo}）。
         *
         * 生成两种形态：
         *   ① {@code variant=0} 原样：服务端 antiCode 直接透传（mp 的 multiLine 已自带 ratio=默认档）；
         *   ② {@code variant=1} 指定档：追加 {@code &ratio=<默认档码率>} —— SDK 的取档方式。
         *
         * 【2026-09-29 实测校正 · 与 SDK 的一处有意偏离】
         *   SDK 构造播放地址时**无条件**追加 {@code &ratio=<bitRate>}
         *   （{@code StreamInfoHelper#getPlaybackUrl} 第 36 行、{@code LiveInfo#getPlayUrlByLineAndBitrate} 第 69 行）。
         *   但设备侧逐线路对照（房间 11342412 / 11279247 / 11602058，各 8 连拉）显示 ratio 会**打挂 hs**：
         *     hs.hls.huya.com  无 ratio  1~3/8 通过，且 200 时是**可直接播的 media playlist**
         *                      +ratio=500/2000/4000/6000 → 0/8
         *     tx.hls.huya.com  带不带 ratio 索引都 8/8，返回 master；其子地址换 SDK ctype 后 48/48 可播
         *                      （原文的 403 是 ctype 问题，与 ratio 无关 —— 见类注释 ctype 专段）
         *     al.hls.huya.com  全 0/8（238B 403 错误页）
         *   另有两条负结论：
         *     · **SDK 式重签 antiCode 更差**（丢弃 fm、补 u/seqid）：hs 原样 2/6 → 重签 0/6，
         *       因为 uid=0 与 mp 下发的 wsSecret 配对不上；故这里**不重签**，原样透传。
         *     · HLS 的 {@code <stream>_<码率>.m3u8} 后缀形式（{@code LiveInfo} 第 62-64 行）在 mp 的
         *       /src 路由上不被接受（hs 各档 0/5），故不采用。
         *   因此本类把**原样地址排在前**，带 ratio 的版本作为后备 —— 保留"按清晰度取档"的能力，
         *   又不让主路径自伤。真正切换清晰度时，ratio 仍是唯一可用手段。
         */
        private static void addBuiltCandidates(JSONObject line, String tag, RateMeta defRate,
                                               List<RateMeta> rates, List<Candidate> out) {
            if (line == null) return;
            String name = line.optString("sStreamName");
            if (TextUtils.isEmpty(name)) return;
            int lineIndex = line.optInt("iLineIndex", -1);
            int priority = line.optInt("iWebPriorityRate", -1);
            String cdnType = line.optString("sCdnType");
            if (TextUtils.isEmpty(cdnType)) cdnType = null;

            String hlsBase = line.optString("sHlsUrl");
            String hlsSuf = line.optString("sHlsUrlSuffix");
            if (TextUtils.isEmpty(hlsSuf)) hlsSuf = "m3u8";
            String hlsAc = line.optString("sHlsAntiCode");
            if (!TextUtils.isEmpty(hlsBase) && !TextUtils.isEmpty(hlsAc)) {
                // ① 【主】SDK ctype —— 把 antiCode 里的 ctype 换成 huya_adr_game_sdk。
                //
                // 【2026-09-29 ★ 根治点】"其余线路全灭"的唯一原因是 mp 下发的 ctype=tars_mp
                // （PC 网页端标识），虎牙边缘对非 SDK 来源一律按冷流拒绝。三条线路实测：
                //     AL  原样 0/6（238B 403 错误页）→ SDK ctype **6/6**，且直出 media playlist
                //     TX  原样 6/6 但只给 master、子地址恒 403 → SDK ctype 后 48/48 可播
                //     HS  原样 3/6 → SDK ctype 6/6
                // 4 房间 × 3 线路 × (10 索引 + 20 分片) 全绿（脚本 _probe_other_lines3/4.py）。
                // 签名安全：wsSecret 的公式 md5(fm0_uid_streamName_seqid_wsTime) 不含 ctype。
                String u = buildUrl(hlsBase, name, hlsSuf, hlsAc);
                if (u != null) {
                    out.add(new Candidate(applySdkCtype(u), tag + "-hls-sdkctype", true,
                            lineIndex, priority, null, 0, cdnType));
                    // ①b 兜底：原样 antiCode。实测各线路都不优于 SDK ctype，仅作后备
                    //     （万一某个边缘对 ctype 做了额外校验，仍有退路）。
                    out.add(new Candidate(u, tag + "-hls-raw", true, lineIndex, priority, null, 1, cdnType));
                }
                // ② SDK 取档方式：&ratio=<bitRate>（同样换 SDK ctype）
                //
                // 【2026-09-29 扩展】**每一档都建**，不再只建默认档 —— 设置里的「清晰度选择」
                // 就靠这些地址（variant=2 排在原样之后，主路径不受影响）。
                // ⚠️ 已知局限：ratio 在 TX（master）上无效、在 HS 上会打挂（见下方实测说明），
                // 因此 TX 线路的清晰度以 master 子播放列表（{@link MasterChild}）为准。
                if (rates != null) {
                    for (RateMeta r : rates) {
                        if (r == null || r.bitRate <= 0) continue;
                        String u2 = buildUrl(hlsBase, name, hlsSuf, hlsAc + "&ratio=" + r.bitRate);
                        if (u2 == null) continue;
                        out.add(new Candidate(applySdkCtype(u2), tag + "-hls-ratio" + r.bitRate,
                                true, lineIndex, priority, r.displayName, 2, cdnType));
                    }
                }
            }

            String flvBase = line.optString("sFlvUrl");
            String flvSuf = line.optString("sFlvUrlSuffix");
            if (TextUtils.isEmpty(flvSuf)) flvSuf = "flv";
            String flvAc = line.optString("sFlvAntiCode");
            if (!TextUtils.isEmpty(flvBase) && !TextUtils.isEmpty(flvAc)) {
                // FLV 保持服务端给的明文 http（不做无谓的 https 升级，少一次重定向/握手开销）。
                // FLV 仅作兜底 —— 它能建连 ≠ 能持续播放（短分片，一次 open 只吐 0.24~0.75MB 就 EOF，见类注释）。
                String u = buildUrlRaw(flvBase, name, flvSuf, flvAc);
                if (u != null) {
                    out.add(new Candidate(u, tag + "-flv-raw", false, lineIndex, priority, null, 0, cdnType));
                }
            }
        }

        // ============================== 探活 ==============================

        /**
         * 真实拉一次地址，判断"是不是真的能播"：
         * HLS：200/206 且返回体含 #EXTM3U + (#EXTINF 或 #EXT-X-STREAM-INF)，并再拉一次末条分片；
         * FLV：200/206 且有实际数据（不是 HTML 错误页）。
         */
        private static Probe probe(String url) {
            Response resp = null;
            try {
                resp = sClient.newCall(buildProbeRequest(url)).execute();
                int code = resp.code();
                String ctype = resp.header("Content-Type");
                if (ctype == null) ctype = "";
                String lower = ctype.toLowerCase(Locale.US);

                if (code != 200 && code != 206) {
                    return new Probe(code, ctype, false, "");
                }

                boolean looksPlaylist = lower.contains("mpegurl") || lower.contains("m3u");
                String text = resp.peekBody(PEEK_BYTES).string();

                // 【2026-09-29】这里只是**响应体类型判定**的顺序（先排 FLV 再排 playlist），
                // 与通道优先级无关。虎牙 FLV 通道返回 `video/x-flv` 且**不是** m3u8，
                // 故判据 = ctype/路径任一命中 .flv，避免把 flv 流误送进 playlist 解析分支。
                boolean looksFlv = isFlvUrl(url) || lower.contains("flv") || lower.contains("octet-stream");
                if (looksFlv && !looksPlaylist && !text.contains("#EXTM3U")) {
                    if (text.contains("<html") || text.contains("<!DOCTYPE")) {
                        return new Probe(code, ctype, false, "HTML错误页", false);
                    }
                    return new Probe(code, ctype, text.length() > 0, text.length() + "B");
                }

                if (looksPlaylist || text.startsWith("#EXTM3U") || text.contains("#EXTM3U")) {
                    if (!text.contains("#EXTM3U")) {
                        return new Probe(code, ctype, false, "无 #EXTM3U", false);
                    }
                    boolean hasSegments = text.contains("#EXTINF");
                    boolean isMaster = text.contains("#EXT-X-STREAM-INF");
                    if (!hasSegments && !isMaster) {
                        return new Probe(code, ctype, false, "空playlist(无#EXTINF/无STREAM-INF)");
                    }
                    // 【2026-09-29 关键修复】master playlist 不在此处判死，而是把第一个子播放列表
                    // 地址交给上层「展开 + 换 SDK ctype」后再探活。
                    // 依据：虎牙 TX 线路的索引 6 房间全 8/8 稳定，但只返回 master，子地址原文带
                    // TxLiveCode=cold_stream 恒 403；仅把 ctype 换成 huya_adr_game_sdk 后
                    // 48/48 全 200，返回含 #EXTINF 的 media playlist 且分片 200（300~980KB）。
                    if (isMaster) {
                        // 【2026-09-29 新增】收下**全部**子播放列表（＝清晰度阶梯），
                        // 不只取第一个：设置里的「清晰度选择」就靠这张表。
                        List<MasterChild> children = parseMasterChildren(text, url);
                        String first = children.isEmpty() ? firstUriLine(text) : children.get(0).url;
                        String sub = (first == null) ? null : resolveUrl(url, first);
                        if (sub == null) {
                            return new Probe(code, ctype, false, "master无子地址", false);
                        }
                        return new Probe(code, ctype, false,
                                "master待展开 [" + hostOf(sub) + "] x" + children.size(), false, true, sub, children);
                    }
                    String last = lastUriLine(text);
                    if (last == null) {
                        return new Probe(code, ctype, true, "无分片行但含#EXTINF");
                    }
                    String segUrl = resolveUrl(url, last);
                    // media playlist：末条分片能下才算真能播
                    int sc = probeStatus(segUrl);
                    if (sc == 200 || sc == 206) {
                        return new Probe(code, ctype, true, "分片 " + sc);
                    }
                    return new Probe(code, ctype, false, "分片 " + sc);
                }

                if (text.contains("<html") || text.contains("<!DOCTYPE")) {
                    return new Probe(code, ctype, false, "HTML错误页", false);
                }
                return new Probe(code, ctype, text.length() > 0, ctype.isEmpty() ? text.length() + "B" : "");
            } catch (Throwable t) {
                return new Probe(-1, "", false, t.getClass().getSimpleName());
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }

        /**
         * 【2026-09-29 新增】带重试的探活。
         *
         * 虎牙边缘节点对同一 URL 的 403 是**逐请求随机**的（不是票据失效、也不是限流：
         * 同一 token 间隔 4s 连拉照样随机 403，403 响应仅 ~100ms 由边缘直接拒绝）。
         * 因此单次失败不代表地址不可用，隔 PROBE_RETRY_GAP_MS 再拉一次即可显著提高命中率
         * （**HLS 索引尤其需要** —— m3u8 每 2~6s 就要重拉一次，这正是"播几秒就卡"的来源）。
         * 若第一次就返回 200/206 则不重试（省预算）；
         * 若失败被判定为**确定性**失败（见 {@link Probe#retryable}，如 master→冷边缘 403），
         * 也立即返回不再重试。
         */
        private static Probe probeRetry(String url, int attempts) {
            Probe last = null;
            for (int i = 0; i < attempts; i++) {
                last = probe(url);
                if (last.playable || !last.retryable) return last;
                if (i + 1 < attempts) {
                    try {
                        Thread.sleep(PROBE_RETRY_GAP_MS);
                    } catch (InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                        return last;
                    }
                }
            }
            return last;
        }

        /** 只取状态码（用于校验分片是否可下） */
        private static int probeStatus(String url) {
            Response resp = null;
            try {
                resp = sClient.newCall(buildProbeRequest(url)).execute();
                return resp.code();
            } catch (Throwable t) {
                return -1;
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }

        /**
         * 【2026-09-29 新增】"复拉确认" —— 判断这个地址是「可持续」还是「撞上了随机 403」。
         *
         * 背景：ExoPlayer 播 HLS 直播时会**反复**拉同一个 playlist URL（每 2~6s 一次刷新，
         * 换台/卡顿时还会更多次）。而虎牙的 m3u8 索引是逐请求随机的 403
         * （实测 hs.hls ≈21% 成功），于是"探活成功一次"根本不够 ——
         * 索引第二次刷新就 403 → Source error 2001 → 正是用户看到的"播几秒就卡"。
         * 所以必须确认"同一地址能连续拉到两次"。
         *
         * 注意：这里统计的"成功率"只用于衡量**索引可达性**，**不代表通道优劣** ——
         * FLV 成功率虽高（≈92%），但它是短分片，一次只给 1~3 秒就断（见类注释）。
         *
         * @param isHls true 时要求复拉体仍是含 #EXTINF 的 m3u8；false（FLV）只要求有真实数据。
         */
        private static boolean probeStable(String url, boolean isHls) {
            Response resp = null;
            try {
                resp = sClient.newCall(buildProbeRequest(url)).execute();
                int code = resp.code();
                if (code != 200 && code != 206) {
                    LogBridge.d(TAGP, "复拉确认不通过：HTTP " + code + " [" + hostOf(url) + "]");
                    return false;
                }
                String text = resp.peekBody(PEEK_BYTES).string();
                if (isHls) {
                    if (!text.contains("#EXTM3U") || !text.contains("#EXTINF")) {
                        LogBridge.d(TAGP, "复拉确认不通过：无 #EXTINF [" + hostOf(url) + "]");
                        return false;
                    }
                } else {
                    if (text.length() <= 0 || text.contains("<html") || text.contains("<!DOCTYPE")) {
                        LogBridge.d(TAGP, "复拉确认不通过：FLV 无有效数据 [" + hostOf(url) + "]");
                        return false;
                    }
                }
                LogBridge.d(TAGP, "复拉确认通过 [" + hostOf(url) + "]" + (isHls ? " HLS" : " FLV"));
                return true;
            } catch (Throwable t) {
                LogBridge.d(TAGP, "复拉确认异常：" + t.getClass().getSimpleName());
                return false;
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }

        /** 探活请求：与播放时一致的请求头（浏览器 UA、不发 Referer），只读前 1MB 就断开 */
        private static Request buildProbeRequest(String url) {
            return new Request.Builder()
                    .url(url)
                    .header("User-Agent", HUYA_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "close")
                    .header("Range", "bytes=0-1048575")
                    .build();
        }

        // ============================== 工具 ==============================

        private static String httpGetString(String url, String referer) throws IOException {
            Request.Builder b = new Request.Builder()
                    .url(url)
                    .header("User-Agent", HUYA_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive");
            if (!TextUtils.isEmpty(referer)) b.header("Referer", referer);
            Response resp = null;
            try {
                resp = sClient.newCall(b.build()).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    LogBridge.w(TAGP, "GET " + hostOf(url) + " 失败，状态码 " + resp.code());
                    return null;
                }
                return resp.body().string();
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }

        /**
         * ⚠️【保留的参考实现，当前不参与候选生成】复刻 SDK
         * {@code com.huya.berry.module.live.SdkLiveService#getSAntiCode}（第 369-388 行）：
         * wsSecret = md5(fm0 + "_" + uid + "_" + streamName + "_" + seqid + "_" + wsTime)
         * wsSecret/wsTime 由服务端下发，客户端只重算 wsSecret 并补 u/seqid。
         *
         * 【2026-09-29 实测否决】设备侧对照（房间 11342412，各 6 连拉）：
         *   服务端原样 antiCode  → 2/6 通过（200 时是可直接播的 media playlist）
         *   SDK 式重签（丢 fm）  → 0/6
         *   重签但保留 fm        → 0/6
         * 原因是 uid=0 与 mp 下发的 wsSecret 不是同一套配对关系。故主路径改为**原样透传**。
         * 该方法保留用于将来需要自签的场景（如 FLV 伪长连接 DataSource），勿直接接回候选生成。
         */
        private static String sdkSignAntiCode(String antiCode, String streamName, long uid) {
            if (TextUtils.isEmpty(antiCode) || TextUtils.isEmpty(streamName)) return null;
            try {
                String fm = null;
                String wsTime = null;
                StringBuilder rest = new StringBuilder();
                for (String seg : antiCode.split("&")) {
                    if (seg.isEmpty()) continue;
                    int eq = seg.indexOf('=');
                    String k = eq < 0 ? seg : seg.substring(0, eq);
                    String v = eq < 0 ? "" : seg.substring(eq + 1);
                    if ("fm".equals(k)) {
                        fm = v;
                    } else if ("wsSecret".equals(k)) {
                        // 服务端签名丢弃，下面重算
                    } else if ("wsTime".equals(k)) {
                        wsTime = v;
                    } else {
                        rest.append('&').append(seg);
                    }
                }
                if (fm == null || wsTime == null) return null;
                String fmPlain = new String(Base64.decode(URLDecoder.decode(fm, "UTF-8"), Base64.DEFAULT),
                        "UTF-8");
                String fm0 = fmPlain.split("_")[0];
                String seqid = System.currentTimeMillis() + ""
                        + ((int) (Math.random() * 10000));
                String wsSecret = md5Hex(fm0 + "_" + uid + "_" + streamName + "_" + seqid + "_" + wsTime);
                if (wsSecret == null) return null;
                return "wsSecret=" + wsSecret
                        + "&wsTime=" + wsTime
                        + "&u=" + uid
                        + "&seqid=" + seqid
                        + "&fm=" + fm
                        + rest;
            } catch (Throwable t) {
                LogBridge.d(TAGP, "自签 antiCode 失败：" + t);
                return null;
            }
        }

        private static String md5Hex(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] d = md.digest(s.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder(d.length * 2);
                for (byte x : d) {
                    sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(x & 0xF, 16));
                }
                return sb.toString();
            } catch (Throwable t) {
                return null;
            }
        }

        private static String buildUrl(String base, String name, String suffix, String antiCode) {
            if (TextUtils.isEmpty(base) || TextUtils.isEmpty(name)) return null;
            String b = httpsUp(base);
            if (TextUtils.isEmpty(b)) return null;
            return assembleUrl(b, name, suffix, antiCode);
        }

        /** 【2026-09-29】保持服务端下发的 scheme（FLV 用 http 原样，不强行升级 https） */
        private static String buildUrlRaw(String base, String name, String suffix, String antiCode) {
            if (TextUtils.isEmpty(base) || TextUtils.isEmpty(name)) return null;
            return assembleUrl(base.trim(), name, suffix, antiCode);
        }

        private static String assembleUrl(String b, String name, String suffix, String antiCode) {
            StringBuilder sb = new StringBuilder(b);
            if (!sb.toString().endsWith("/")) sb.append('/');
            sb.append(name);
            if (!TextUtils.isEmpty(suffix)) sb.append('.').append(suffix);
            if (!TextUtils.isEmpty(antiCode)) {
                sb.append(antiCode.contains("?") ? '&' : '?').append(antiCode);
            }
            return sb.toString();
        }

        /** 虎牙 CDN 对明文 http 一律 403，统一升级 https */
        private static String httpsUp(String u) {
            if (TextUtils.isEmpty(u)) return null;
            if (u.startsWith("http://")) return "https://" + u.substring(7);
            return u;
        }

        /** 从 m3u8 文本里取最后一条非注释行（分片/子 playlist 地址） */
        private static String lastUriLine(String text) {
            String[] lines = text.split("\r?\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (!l.isEmpty() && !l.startsWith("#")) return l;
            }
            return null;
        }

        /** 从 m3u8 文本里取**第一条**非注释行（master playlist 的子播放列表地址） */
        /**
         * master playlist 的一个子播放列表 ＝ **一档清晰度**。
         * 【2026-09-29 新增】虎牙 TX 线路返回的就是 master，其子条目即清晰度阶梯
         * （{@code #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080} + 下一行 URI）。
         */
        private static final class MasterChild {
            /** 绝对地址（**未**换 ctype，取用前需 applySdkCtype） */
            final String url;
            /** STREAM-INF BANDWIDTH（bps）；-1 = 未声明 */
            final int bandwidth;
            /** STREAM-INF RESOLUTION，如 {@code 1920x1080}；null = 未声明 */
            final String resolution;

            MasterChild(String url, int bandwidth, String resolution) {
                this.url = url;
                this.bandwidth = bandwidth;
                this.resolution = resolution;
            }

            @Override
            public String toString() {
                return (bandwidth > 0 ? (bandwidth / 1000) + "k" : "?k")
                        + "/" + (resolution == null ? "?" : resolution);
            }
        }

        /** 解析 master playlist 的全部 STREAM-INF 子条目（保持服务端下发顺序） */
        private static List<MasterChild> parseMasterChildren(String text, String baseUrl) {
            List<MasterChild> out = new ArrayList<>();
            if (TextUtils.isEmpty(text)) return out;
            int pendingBandwidth = -1;
            String pendingResolution = null;
            for (String raw : text.split("\r?\n")) {
                String t = raw.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("#EXT-X-STREAM-INF")) {
                    pendingBandwidth = -1;
                    pendingResolution = null;
                    int b = t.indexOf("BANDWIDTH=");
                    if (b >= 0) {
                        int s = b + "BANDWIDTH=".length();
                        int e = s;
                        while (e < t.length() && Character.isDigit(t.charAt(e))) e++;
                        if (e > s) {
                            try { pendingBandwidth = Integer.parseInt(t.substring(s, e)); } catch (Exception ignored) {}
                        }
                    }
                    int r = t.indexOf("RESOLUTION=");
                    if (r >= 0) {
                        int s = r + "RESOLUTION=".length();
                        int e = s;
                        while (e < t.length() && t.charAt(e) != ',' && t.charAt(e) != '"') e++;
                        if (e > s) pendingResolution = t.substring(s, e).trim();
                    }
                } else if (!t.startsWith("#")) {
                    String abs = resolveUrl(baseUrl, t);
                    if (!TextUtils.isEmpty(abs)) out.add(new MasterChild(abs, pendingBandwidth, pendingResolution));
                    pendingBandwidth = -1;
                    pendingResolution = null;
                }
            }
            return out;
        }

        private static String firstUriLine(String text) {
            for (String l : text.split("\r?\n")) {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#")) return t;
            }
            return null;
        }

        /**
         * 【2026-09-29 关键修复】把 URL 的 {@code ctype} 参数替换为 SDK 的 {@link #SDK_CTYPE}；
         * 没有该参数则追加。其余参数（wsSecret / wsTime / fm / txyp / fs / …）原样保留 ——
         * 见 {@link #SDK_CTYPE} 的实测说明：签名公式不含 ctype，替换不会破坏签名。
         */
        private static String applySdkCtype(String url) {
            if (TextUtils.isEmpty(url)) return url;
            if (url.contains("ctype=")) {
                return url.replaceAll("([?&]ctype=)[^&]*", "$1" + SDK_CTYPE);
            }
            return url + (url.contains("?") ? "&" : "?") + "ctype=" + SDK_CTYPE;
        }

        private static String resolveUrl(String base, String relative) {
            try {
                return new URL(new URL(base), relative).toString();
            } catch (Throwable t) {
                return relative;
            }
        }

        /** 从 text[startIdx]（应为 '{'）做括号匹配，返回内部对应的合法 JSON 子串 */
        private static String extractBalancedJson(String text, int startIdx) {
            if (startIdx < 0 || startIdx >= text.length() || text.charAt(startIdx) != '{') return null;
            int depth = 0;
            char inStr = 0;
            boolean esc = false;
            for (int i = startIdx; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inStr != 0) {
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == inStr) {
                        inStr = 0;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    inStr = c;
                    continue;
                }
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) return text.substring(startIdx, i + 1);
                }
            }
            return null;
        }

        private static String hostOf(String url) {
            try {
                return new URL(url).getHost();
            } catch (Throwable t) {
                return "?";
            }
        }

        private static String describe(List<Candidate> list) {
            StringBuilder sb = new StringBuilder();
            for (Candidate c : list) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(c.label).append('@').append(hostOf(c.url));
            }
            return sb.toString();
        }

        private static void putCache(long roomId, String hls, String flv) {
            sCache.put(roomId, new StreamCache(hls, flv));
        }

        private static void putCache(long roomId, String hls, String flv, StreamMatrix matrix) {
            sCache.put(roomId, new StreamCache(hls, flv, matrix));
        }

        // ==================== 【2026-09-29】「线路 × 清晰度」矩阵 ====================

        /**
         * 由候选表构造矩阵。线路来自 {@code baseSteamInfoList}（候选带 iLineIndex/sCdnType），
         * 清晰度取值优先级：
         *   ① 采用线路 + master 展开 → 用 master 的 STREAM-INF 子条目（**TX 线路的真实清晰度阶梯**）；
         *   ② 其余线路 → 用 {@code rateArray} 派生的 ratio 候选（variant=2，按码率降序）；
         *   ③ 都没有 → 该线路的「主地址」作为单档「默认档」。
         *
         * 说明：只有 {@code adoptedUrl} 是实测探活通过的；其余档位是服务端下发的等价地址。
         */
        private static StreamMatrix buildMatrix(List<Candidate> uniq, Candidate adopted,
                                                List<MasterChild> masterChildren,
                                                List<RateMeta> rates,
                                                List<MultiLineOption> multiLines,
                                                String adoptedUrl, String adoptedFlv) {
            List<LineOption> lines = new ArrayList<>();
            if (uniq == null || uniq.isEmpty()) {
                return new StreamMatrix(lines, adoptedUrl,
                        adopted == null ? -1 : adopted.lineIndex,
                        adopted == null ? null : adopted.quality, adoptedFlv, multiLines);
            }

            // 按 lineIndex 聚合，保持候选表里的先后顺序（＝ 实际线路优先级）
            List<Integer> order = new ArrayList<>();
            java.util.Map<Integer, List<Candidate>> byLine = new java.util.LinkedHashMap<>();
            for (Candidate c : uniq) {
                if (c.lineIndex < 0) continue;   // multiLine / 网页源派生，无线路语义
                List<Candidate> bucket = byLine.get(c.lineIndex);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    byLine.put(c.lineIndex, bucket);
                    order.add(c.lineIndex);
                }
                bucket.add(c);
            }

            // 采用线路排第一（下拉里第一条 = 当前线路）
            if (adopted != null && adopted.lineIndex >= 0 && order.contains(adopted.lineIndex)) {
                order.remove(Integer.valueOf(adopted.lineIndex));
                order.add(0, adopted.lineIndex);
            }

            boolean masterUsed = false;
            for (int li : order) {
                List<Candidate> bucket = byLine.get(li);
                String cdnType = null;
                for (Candidate c : bucket) {
                    if (!TextUtils.isEmpty(c.cdnType)) { cdnType = c.cdnType; break; }
                }
                List<QualityOption> quals = new ArrayList<>();

                // ① 采用线路 + master → 直接用 master 的清晰度阶梯
                boolean isAdoptedLine = (adopted != null && adopted.lineIndex == li);
                if (isAdoptedLine && masterChildren != null && !masterChildren.isEmpty()) {
                    for (MasterChild mc : masterChildren) {
                        int kbps = mc.bandwidth > 0 ? mc.bandwidth / 1000 : 0;
                        quals.add(new QualityOption(labelForBitRate(rates, kbps, mc.resolution),
                                kbps, applySdkCtype(mc.url), null));
                    }
                    masterUsed = true;
                } else {
                    // ② ratio 候选（每个清晰度一档），按码率降序
                    List<Candidate> ratio = new ArrayList<>();
                    for (Candidate c : bucket) {
                        if (c.hls && !TextUtils.isEmpty(c.quality)) ratio.add(c);
                    }
                    Collections.sort(ratio, new java.util.Comparator<Candidate>() {
                        @Override public int compare(Candidate a, Candidate b) {
                            return Integer.compare(bitRateOfQuality(b.quality, rates),
                                    bitRateOfQuality(a.quality, rates));
                        }
                    });
                    Set<String> seenQ = new HashSet<>();
                    for (Candidate c : ratio) {
                        if (!seenQ.add(c.quality)) continue;
                        quals.add(new QualityOption(c.quality, bitRateOfQuality(c.quality, rates), c.url, null));
                    }
                    // ③ 兜底：该线路的第一条地址（排序后最优）当「默认档」
                    if (quals.isEmpty()) {
                        Candidate rep = null;
                        for (Candidate c : bucket) {
                            if (c.hls) { rep = c; break; }
                        }
                        if (rep == null) rep = bucket.get(0);
                        quals.add(new QualityOption("默认档", 0, rep.hls ? rep.url : null,
                                rep.hls ? null : rep.url));
                    }
                }
                lines.add(new LineOption(li, cdnType, quals));
            }

            // 采用的清晰度名：master 情况下取第一个子条目，否则取候选自带 quality
            String adoptedQuality = adopted == null ? null : adopted.quality;
            if (masterUsed && !lines.isEmpty() && !lines.get(0).qualities.isEmpty()) {
                adoptedQuality = lines.get(0).qualities.get(0).displayName;
            }
            if (TextUtils.isEmpty(adoptedQuality)) adoptedQuality = "默认档";

            return new StreamMatrix(lines, adoptedUrl,
                    adopted == null ? -1 : adopted.lineIndex, adoptedQuality, adoptedFlv, multiLines);
        }

        /** 把 BANDWIDTH(kbps)/RESOLUTION 翻译成人类可读的清晰度名（优先用 rateArray 里的官方名） */
        private static String labelForBitRate(List<RateMeta> rates, int kbps, String resolution) {
            if (rates != null) {
                for (RateMeta r : rates) {
                    if (r != null && r.bitRate == kbps && !TextUtils.isEmpty(r.displayName)) {
                        return r.displayName;
                    }
                }
            }
            if (!TextUtils.isEmpty(resolution)) {
                if (resolution.contains("2160")) return "4K";
                if (resolution.contains("1080")) return "1080p";
                if (resolution.contains("720")) return "720p";
                return resolution;
            }
            return kbps > 0 ? (kbps / 1000.0) + "M" : "默认档";
        }

        private static int bitRateOfQuality(String quality, List<RateMeta> rates) {
            if (TextUtils.isEmpty(quality) || rates == null) return 0;
            for (RateMeta r : rates) {
                if (r != null && quality.equals(r.displayName)) return r.bitRate;
            }
            return 0;
        }

        private static String describeMatrix(StreamMatrix mx) {
            if (mx == null || mx.lines.isEmpty()) return "(无线路信息)";
            StringBuilder sb = new StringBuilder("采用线路=").append(mx.adoptedLineIndex)
                    .append(" 清晰度=").append(mx.adoptedQuality).append("；");
            for (LineOption l : mx.lines) {
                sb.append(' ').append(l.displayLabel()).append(": ");
                for (QualityOption q : l.qualities) sb.append(q.displayName).append(' ');
                sb.append('|');
            }
            if (mx.multiLines != null && !mx.multiLines.isEmpty()) {
                sb.append("；子线路 ").append(mx.multiLines.size()).append(" 条：");
                for (MultiLineOption m : mx.multiLines) sb.append(' ').append(m.toString());
            }
            return sb.toString();
        }

        private static String cacheSafe(String s) {
            if (s == null) return "null";
            return s.length() < 80 ? s : s.substring(0, 80) + "...";
        }

        private static void postSuccess(final OnParseResultListener listener, final String hls, final String flv) {
            postSuccess(listener, hls, flv, null);
        }

        /** 【2026-09-29】带「线路 × 清晰度」矩阵的成功回调；matrix=null 时等价于旧行为 */
        private static void postSuccess(final OnParseResultListener listener, final String hls,
                                        final String flv, final StreamMatrix matrix) {
            sMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (matrix != null) {
                            listener.onSuccess(matrix, hls, flv);
                        } else {
                            listener.onSuccess(hls, flv, true);
                        }
                    } catch (Throwable ignore) {
                    }
                }
            });
        }

        private static void postFailed(final OnParseResultListener listener, final String errorMsg) {
            sMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.onFailed(errorMsg);
                    } catch (Throwable ignore) {
                    }
                }
            });
        }
    }

    public static final class HuyaHttpThrottle {
        private static final String TAG = "HuyaHttpThrottle";
        // 【2026-10-03 提速】4 → 6。实证：第一路 34+ 个子分类请求挤在 4 个槽里排队，
        // 是「第一路 4.9s vs 第二路 0.97s」的主因（第二路走 HttpURLConnection，不受此限流）。
        // 近 3 轮冷启动实测：真 503 = 0 次、并发繁忙放弃 = 0 次 → 4 并发有富余，提到 6 安全。
        private static final int HUYA_CONCURRENCY = 6;
        private static final long ACQUIRE_TIMEOUT_SEC = 8;
        private static final Semaphore HUYA_SEM = new Semaphore(HUYA_CONCURRENCY);
        private static volatile boolean sHookInstalled = false;

        private HuyaHttpThrottle() {}

        public static boolean isHuyaUrl(String url) {
            if (url == null) return false;
            return url.contains("huya.com") || url.contains("huya.cn") || url.contains("wup.huya");
        }

        public static OkHttpClient.Builder attach(OkHttpClient.Builder builder) {
            if (builder == null) return builder;
            builder.addInterceptor(new ThrottleInterceptor());
            sHookInstalled = true;
            return builder;
        }

        public static boolean isInstalled() { return sHookInstalled; }

        private static final class ThrottleInterceptor implements Interceptor {
            @Override
            public Response intercept(Chain chain) throws IOException {
                String url = chain.request().url().toString();
                if (!isHuyaUrl(url)) return chain.proceed(chain.request());
                boolean acquired = false;
                try {
                    acquired = HUYA_SEM.tryAcquire(ACQUIRE_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("HuyaHttpThrottle: 等待虎牙请求许可被中断 url=" + url);
                }
                if (!acquired) {
                    LogBridge.w(TAG, "⏳ 虎牙并发繁忙（" + HUYA_CONCURRENCY + "），放弃请求: " + url);
                    throw new IOException("HuyaHttpThrottle: 虎牙请求繁忙超时 url=" + url);
                }
                try {
                    return chain.proceed(chain.request());
                } finally {
                    HUYA_SEM.release();
                }
            }
        }
    }

}
