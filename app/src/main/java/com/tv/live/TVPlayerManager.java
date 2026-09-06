package com.tv.live;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Surface;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.CookieManager;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import androidx.core.content.ContextCompat;

import com.tv.live.util.NetUtil;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.Variant;
import com.tv.live.util.DecoderModeManager;
import com.tv.live.util.VariantManager;
import com.tv.live.util.HuyaStreamPlayer;
import com.tv.live.util.AppCacheInspector;
import com.tv.live.exception.RedirectFailedException;
import com.tv.live.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.Headers;

@SuppressLint({"UnsafeOptInUsageError", "StaticFieldLeak"})
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;

    private static final int MAX_RETRY_COUNT = 2;
    private static final int MAX_RETRY_COUNT_NETWORK = 5;
    private static final long STUCK_TIMEOUT = 20000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long SOURCE_FAILED_COOLDOWN_MS = 30000;
    private static final long MIN_STALL_WARN_THRESHOLD = 2000;

    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";

    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    private static volatile TVPlayerManager instance;
    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
    private com.tv.live.util.SourceHealthChecker healthChecker;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;
    private String currentChannelName = "";
    private int mDecoderMode = DECODER_MODE_AUTO;

    private boolean isSwitching = false;

    private Channel currentChannel;
    private int backupRetryIndex = -1;

    private long initialPlayStartTime = 0;
    private int bufferCount = 0;
    private long totalStallTime = 0;
    private boolean isStalled = false;
    private long lastStallStartTime = 0;
    private int retryCount = 0;
    private boolean isRetrying = false;
    private Runnable retryRunnable;
    private long lastSourceFailedTime = 0;

    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private Runnable stuckCheckRunnable;

    private Handler mHandler;
    private Runnable hideChannelRunnable;

    private FrameLayout mSdkPlayerContainer;
    private Activity mActivity;
    private int mHuyaRoomId = -1;
    private int mCurrentHuyaLineIndex = 0;

    private OnPlayStateListener listener;
    private OnSourceFailedListener sourceFailedListener;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;
    private boolean isPlaying = false;

    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;
    private BroadcastReceiver rendererModeReceiver;
    private boolean rendererReceiverRegistered = false;

    private OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    private boolean isRenderingSwitching = false;

    private final Map<String, String> reusableHeaderMap = Collections.synchronizedMap(new HashMap<>());

    private Map<String, String> mPendingPlaybackHeaders = null;

    private DefaultTrackSelector trackSelector;

    private ScaleMode mCurrentScaleMode = ScaleMode.FILL;

    private Boolean mCurrentUseTexture = null;

    private final Object variantListLock = new Object();
    private volatile List<Variant> variantList = new ArrayList<>();
    private volatile boolean isParsingMasterPlaylist = false;

    private SharedPreferences sp;
    private String currentResolutionLabel = "自适应";

    private static ExecutorService sPlaylistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TVPlayer-PlaylistParser");
        t.setDaemon(true);
        return t;
    });

    private DecoderModeManager decoderModeManager;
    private VariantManager variantManager;
    private HuyaStreamPlayer huyaStreamPlayer;

    public interface OnPlayerViewRecreatedListener {
        void onPlayerViewRecreated(PlayerView newPlayerView);
    }

    public void setOnPlayerViewRecreatedListener(OnPlayerViewRecreatedListener listener) {
        this.onPlayerViewRecreatedListener = listener;
    }

    public static TVPlayerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) {
                    instance = new TVPlayerManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private TVPlayerManager(Context context) {
        this.context = context;
        this.sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        this.healthChecker = new com.tv.live.util.SourceHealthChecker(context);
        mHandler = new Handler(Looper.getMainLooper());

        String savedMode = sp.getString("decoder_mode", "auto");
        switch (savedMode) {
            case "hard":   mDecoderMode = DECODER_MODE_HARD;   break;
            case "soft":   mDecoderMode = DECODER_MODE_SOFT;   break;
            default:       mDecoderMode = DECODER_MODE_AUTO;   break;
        }
        LogBridge.i(TAG, "📺 启动时读取解码器设置: decoder_mode=" + savedMode + " → mDecoderMode=" + mDecoderMode);

        hideChannelRunnable = () -> hideChannelNum();

        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {

                if (player == null) {
                    lastPosition = 0;
                    lastPositionUpdateTime = System.currentTimeMillis();
                    mHandler.postDelayed(this, 2000);
                    return;
                }
                try {
                    int state = player.getPlaybackState();
                    if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {

                        LogBridge.w(TAG, "检测到播放器异常状态(state=" + state + ")，自动重试...");
                        autoRetry("播放卡住");
                        return;
                    }
                    if (state == Player.STATE_BUFFERING) {

                        long now = System.currentTimeMillis();
                        if (lastPositionUpdateTime == 0) {
                            lastPositionUpdateTime = now;
                        }
                        if (now - lastPositionUpdateTime > STUCK_TIMEOUT) {
                            LogBridge.w(TAG, "检测到长时间缓冲(" + STUCK_TIMEOUT + "ms)，自动重试...");
                            autoRetry("播放卡住");
                            return;
                        }
                    } else {

                        lastPositionUpdateTime = 0;
                    }
                } catch (Exception e) {
                    LogBridge.e(TAG, "卡住检测异常", e);
                }
                mHandler.postDelayed(this, 2000);
            }
        };

        decoderModeManager = new DecoderModeManager(context, mHandler);
        variantManager = new VariantManager(new VariantManager.PlaybackCallback() {
            @Override
            public void playUrl(String url) {
                TVPlayerManager.this.playUrlInternal(url);
            }
            @Override
            public Channel getCurrentChannel() {
                return TVPlayerManager.this.getCurrentChannel();
            }
            @Override
            public void dLog(String msg) {
                TVPlayerManager.this.dLog(msg);
            }
        });
        huyaStreamPlayer = new HuyaStreamPlayer(new HuyaStreamPlayer.StreamPlaybackCallback() {
            @Override
            public void onPlayError(String msg) {
                if (listener != null) listener.onPlayError(msg);
            }
            @Override
            public void onSourceFailed() {
                if (sourceFailedListener != null) sourceFailedListener.onSourceFailed();
            }
            @Override
            public void dLog(String msg) {
                TVPlayerManager.this.dLog(msg);
            }
            @Override public void onPlaySuccess() {}
            @Override public void onSeekTo(long positionMs) { if (player != null) player.seekTo(positionMs); }
            @Override public void onPlay() { if (player != null) player.play(); }
            @Override public void onPrepare() { if (player != null) player.prepare(); }
            @Override public void onStuckDetectionStart() { startStuckDetection(); }
            @Override public Context getContext() { return context; }
            @Override public ExoPlayer getPlayer() { return player; }
            @Override public PlayerView getPlayerView() { return playerView; }
            @Override public FrameLayout getSdkPlayerContainer() { return mSdkPlayerContainer; }
            @Override public android.os.Handler getHandler() { return mHandler; }
            @Override public ExecutorService getPlaylistExecutor() { return ensurePlaylistExecutor(); }
            @Override public SharedPreferences getSharedPrefs() { return sp; }
            @Override public String getCurrentChannelName() { return currentChannelName; }
            @Override public Channel getCurrentChannel() { return TVPlayerManager.this.getCurrentChannel(); }
            @Override public void setCurrentUrl(String url) { currentUrl = url; }
            @Override public void setHuyaRoomId(int roomId) { mHuyaRoomId = roomId; }
            @Override public int getHuyaRoomId() { return mHuyaRoomId; }
            @Override public void setPendingHeaders(Map<String, String> headers) { mPendingPlaybackHeaders = headers; }
            @Override public Map<String, String> getPendingHeaders() { return mPendingPlaybackHeaders; }
            @Override public void setReusableHeaderMap(Map<String, String> map) {  }
            @Override public Map<String, String> getReusableHeaderMap() { return reusableHeaderMap; }
            @Override public void setCurrentResolutionLabel(String label) { currentResolutionLabel = label; }
            @Override public void ensurePlayerBoundToView() { TVPlayerManager.this.ensurePlayerBoundToView(); }
            @Override public void setMediaSourceAndPrepare(MediaSource source, long seekPosition) {
                if (player != null) {
                    player.setMediaSource(source, true);
                    player.prepare();
                    if (seekPosition > 0) player.seekTo(seekPosition);
                    player.play();
                }
            }
        }, variantManager);

        initPlayer();
    }

    private void dLog(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            LogBridge.d(TAG, msg);
            com.tv.live.util.LogCollector.getInstance().addLog(TAG, msg);
        }
    }

    private void logPlayback(String msg) {
        LogBridge.i(TAG, "[PLAY] " + msg);
        com.tv.live.util.LogCollector.getInstance().playback(TAG, msg);
    }

    private void logError(String msg) {
        LogBridge.e(TAG, msg);

        if (shouldReportPlaybackError(msg)) {
            com.tv.live.util.LogCollector.getInstance().error(TAG, msg);
        } else {

            com.tv.live.util.LogCollector.getInstance().warn(TAG, "[播放过滤] " + msg);
        }
    }

    private boolean shouldReportPlaybackError(String msg) {
        if (msg == null || msg.isEmpty()) return false;

        String lowerMsg = msg.toLowerCase();

        String[] externalResourceErrors = {
            "404 not found",
            "失败: http 404",
            "失败: http",
            "打开连接失败: timeout",
            "播放异常: unexpected runtime error"
        };

        for (String error : externalResourceErrors) {
            if (lowerMsg.contains(error)) {
                return false;
            }
        }

        return true;
    }

    private void logWarn(String msg) {
        LogBridge.w(TAG, msg);
        com.tv.live.util.LogCollector.getInstance().warn(TAG, msg);
    }

    private void logNetwork(String msg) {
        if (BuildConfig.IS_DEBUG) {
            LogBridge.i(TAG, "[NET] " + msg);
            com.tv.live.util.LogCollector.getInstance().network(TAG, msg);
        }
    }

    private void initPlayer() {

        boolean preferSoftware;
        switch (mDecoderMode) {
            case DECODER_MODE_SOFT:
                dLog("【解码器】软解优先模式");
                preferSoftware = true;
                break;
            case DECODER_MODE_HARD:
                dLog("【解码器】硬解优先模式");
                preferSoftware = false;
                break;
            case DECODER_MODE_AUTO:
            default:

                preferSoftware = false;
                break;
        }

        DefaultRenderersFactory factory = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(createSmartSelector(preferSoftware))
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        dLog("【解码器】RenderersFactory = DefaultRenderersFactory"
                + ", preferSoftware=" + preferSoftware
                + ", fallback=true");

        DefaultLoadControl optimizedLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    3000,
                    50000,
                    1000,
                    2000
                )
                .build();

        trackSelector = new DefaultTrackSelector(context);

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(factory)
                .setLoadControl(optimizedLoadControl)
                .setTrackSelector(trackSelector)
                .build();

        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos("video/avc", false, false);
            int softCount = 0, hardCount = 0;
            for (MediaCodecInfo codec : h264Codecs) {
                if (isSoftwareDecoder(codec)) softCount++;
                else hardCount++;
            }
            dLog("【解码器】软解 " + softCount + " 个，硬解 " + hardCount + " 个"
                    + " → 选中模式: " + (preferSoftware ? "软解优先" : "硬解优先"));
        } catch (Exception ignored) {
        }

        initPlayerListener();
        CookieManager.getInstance().setAcceptCookie(true);
    }

    static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
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

    private void initPlayerListener() {
        if (playerListener != null) return;
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                logError("播放异常: " + error.getMessage());
                isSwitching = false;

                Throwable rootCause = error.getCause();
                boolean isRedirectError = false;
                int depth = 0;
                while (rootCause != null && depth < 20) {
                    if (rootCause instanceof RedirectFailedException) {
                        isRedirectError = true;
                        break;
                    }
                    rootCause = rootCause.getCause();
                    depth++;
                }

                if (isSurfaceLostError(error)) {
                    logWarn("检测到 Surface 失效（MediaCodec/Surface 相关），触发重建流程");
                    handleSurfaceLost();
                    if (listener != null) {
                        listener.onPlayError("Surface 失效，正在重建");
                    }
                    return;
                }

                if (isRedirectError) {
                    logError("重定向错误: " + error.getMessage());
                    if (listener != null) {
                        listener.onPlayError(error.getMessage());
                    }
                    return;
                }

                boolean isNetworkError = isNetworkError(error);
                if (isNetworkError && isNetworkUnavailable()) {
                    logWarn("网络不可用，暂不切台，等待网络恢复后重试...");
                    autoRetry("网络不可用", error);
                } else {
                    autoRetry(isNetworkError ? "网络异常" : "播放异常", error);
                }

                if (!isNetworkError && !isRedirectError) {
                    if (healthChecker != null && currentChannel != null) {
                        healthChecker.markFailed(currentUrl, currentChannel);
                    }
                }

                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    logPlayback("播放就绪 (STATE_READY)");
                    updateWakeLock(true);

                    HuyaSDKParser.notifyPlaybackSettled();
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    isSwitching = false;
                    if (listener != null) listener.onPlayReady();
                    retryCount = 0;
                    isRetrying = false;
                    startStuckDetection();
                    if (healthChecker != null && !TextUtils.isEmpty(currentUrl)) {
                        healthChecker.markSuccess(currentUrl);
                    }
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    logPlayback("缓冲中 (STATE_BUFFERING)");
                    if (listener != null) listener.onBuffering();
                    lastPositionUpdateTime = System.currentTimeMillis();
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                    }
                } else if (state == Player.STATE_ENDED) {
                    logPlayback("播放结束 (STATE_ENDED)");
                    if (listener != null) listener.onPlayEnd();
                    autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    logPlayback("空闲状态 (STATE_IDLE)");
                    isSwitching = false;
                    if (listener != null) listener.onIdle();
                    updateWakeLock(false);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    logPlayback("开始播放");
                    lastPositionUpdateTime = System.currentTimeMillis();
                    if (isStalled) {
                        isStalled = false;
                        long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                        totalStallTime += stallDuration;
                        if (stallDuration >= MIN_STALL_WARN_THRESHOLD) {
                            logWarn("严重卡顿结束，时长：" + stallDuration + "ms");
                        } else {
                            logPlayback("短暂缓冲恢复，时长：" + stallDuration + "ms");
                        }
                    }
                } else {
                    logPlayback("暂停播放");
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                logPlayback("视频分辨率变化：" + videoSize.width + "×" + videoSize.height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }

    private boolean trySwitchBackup() {
        if (currentChannel == null || currentChannel.getBackupUrls().isEmpty()) {
            return false;
        }
        if (backupRetryIndex < 0) {
            backupRetryIndex = 0;
        } else {
            backupRetryIndex++;
        }
        List<String> backups = currentChannel.getBackupUrls();
        if (backupRetryIndex >= backups.size()) {
            backupRetryIndex = -1;
            return false;
        }
        String backupUrl = backups.get(backupRetryIndex);

        if (isHuyaRoomUrl(backupUrl)) {
            LogBridge.w(TAG, "备用源是虎牙房间号，跳过！尝试下一个...");
            return trySwitchBackup();
        }

        dLog("尝试切换到备用源：" + backupUrl);
        playUrlInternal(backupUrl);
        return true;
    }

    private void startStuckDetection() {
        mHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        mHandler.postDelayed(stuckCheckRunnable, 2000);
    }

    private void stopStuckDetection() {
        mHandler.removeCallbacks(stuckCheckRunnable);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }

    private void handleAsyncPlaybackError(Throwable e) {
        LogBridge.e(TAG, "播放异常", e);
        if (e instanceof RedirectFailedException) {
            if (listener != null) listener.onPlayError("源跳转失败：" + e.getMessage());
            return;
        }
        autoRetry("播放异常：" + e.getMessage(), e);
    }

    private void autoRetry(String reason) {
        autoRetry(reason, null);
    }

    private void autoRetry(String reason, Throwable cause) {
        if (cause != null) {
            Throwable t = cause;
            int depth = 0;
            while (t != null && depth < 20) {
                if (t instanceof RedirectFailedException) return;
                t = t.getCause();
                depth++;
            }
        }
        if (cause == null && reason != null && reason.contains("RedirectFailedException")) {
            return;
        }
        if (isRetrying) return;

        boolean isNetworkError = isNetworkError(cause) || (reason != null && (reason.contains("网络") || reason.contains("卡住")));
        int maxRetry = isNetworkError ? MAX_RETRY_COUNT_NETWORK : MAX_RETRY_COUNT;

        if (retryCount >= maxRetry) {
            logWarn("重试次数已达上限：" + maxRetry + "（" + (isNetworkError ? "网络错误" : "源错误") + "），判定为失效源");

            if (!isNetworkError || !isNetworkUnavailable()) {
                boolean backupSwitched = trySwitchBackup();
                if (!backupSwitched) {
                    long now = System.currentTimeMillis();
                    if (now - lastSourceFailedTime < SOURCE_FAILED_COOLDOWN_MS) {
                        logWarn("切台冷却中（" + (SOURCE_FAILED_COOLDOWN_MS / 1000) + "s），跳过本次自动切台");
                        return;
                    }
                    lastSourceFailedTime = now;
                    if (sourceFailedListener != null) {
                        mHandler.post(() -> sourceFailedListener.onSourceFailed());
                    }
                }
            } else {
                logWarn("网络不可用，不切台不切源，保持当前频道等待网络恢复");
            }
            return;
        }
        isRetrying = true;
        retryCount++;
        long delayMs = isNetworkError ? (2000L * (1L << (retryCount - 1))) : 3000L;
        logWarn("自动重试（第" + retryCount + "/" + maxRetry + "次），延迟" + delayMs + "ms，原因：" + reason);
        retryRunnable = () -> {
            isRetrying = false;
            if (!TextUtils.isEmpty(currentUrl)) {

                if (currentUrl.contains(".huya.com") && mHuyaRoomId > 0) {
                    LogBridge.d(TAG, "【虎牙】重试：重新触发解析获取新签名, roomId=" + mHuyaRoomId);
                    playHuyaStream(mHuyaRoomId, 0);
                } else {
                    playUrlInternal(currentUrl);
                }
            }
            retryRunnable = null;
        };
        mHandler.postDelayed(retryRunnable, delayMs);
    }

    private boolean isSurfaceLostError(Throwable throwable) {
        if (throwable == null) return false;
        Throwable t = throwable;
        int depth = 0;
        while (t != null && depth < 20) {
            if (t instanceof IllegalArgumentException) {

                for (StackTraceElement elem : t.getStackTrace()) {
                    String cls = elem.getClassName();
                    String method = elem.getMethodName();
                    if ((cls.contains("MediaCodec") && method.contains("Surface"))
                            || (cls.contains("SynchronousMediaCodecAdapter"))
                            || (cls.contains("MediaCodecVideoRenderer"))) {
                        return true;
                    }
                }
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }

    private void handleSurfaceLost() {
        try {
            LogBridge.w(TAG, "handleSurfaceLost: 开始处理 Surface 失效");

            if (playerView != null) {
                try {
                    playerView.setPlayer(null);
                } catch (Exception e) {
                    LogBridge.w(TAG, "setPlayer(null) 异常: " + e.getMessage());
                }
            }

            surfaceReady = false;
            surfaceCallbackBound = false;
            pendingBindPlayer = true;

            if (playerView != null) {
                try {
                    playerView.post(() -> {
                        try {
                            playerView.setVisibility(View.VISIBLE);
                            playerView.requestLayout();
                            playerView.invalidate();
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            }

            if (mHandler != null) {
                mHandler.postDelayed(() -> {
                    try {
                        if (player == null || playerView == null) return;

                        bindSurfaceCallback(playerView);

                        if (playerView.getPlayer() != player) {
                            playerView.setPlayer(player);
                        }

                        if (!TextUtils.isEmpty(currentUrl)) {
                            LogBridge.d(TAG, "Surface 重建完成，重新播放: " + currentUrl);

                            playUrlInternal(currentUrl);
                        }
                        pendingBindPlayer = false;
                    } catch (Exception e) {
                        LogBridge.e(TAG, "Surface 重建后恢复播放异常: " + e.getMessage(), e);
                    }
                }, 500);
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "handleSurfaceLost 异常", e);
        }
    }

    private boolean isNetworkError(Throwable throwable) {
        if (throwable == null) return false;
        Throwable t = throwable;
        int depth = 0;
        while (t != null && depth < 20) {
            String className = t.getClass().getName();
            String msg = t.getMessage();
            if (msg == null) msg = "";
            if (className.contains("SocketTimeoutException")
                    || className.contains("ConnectException")
                    || className.contains("UnknownHostException")
                    || className.contains("NetworkOnMainThreadException")
                    || msg.contains("timeout")
                    || msg.contains("timed out")
                    || msg.contains("Connection")
                    || msg.contains("connection")
                    || msg.contains("unreachable")
                    || msg.contains("ECONNRESET")
                    || msg.contains("ECONNREFUSED")) {
                return true;
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }

    private boolean isNetworkUnavailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info == null || !info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        dLog("手动切换解码器模式：" + mode);
        if (player != null) performDecoderSwitch();
    }

    private void performDecoderSwitch() {
        if (isSwitching) {
            LogBridge.w(TAG, "正在解码器切换中，忽略当前请求");
            return;
        }
        isSwitching = true;
        long currentPosition = player != null ? player.getCurrentPosition() : 0;

        try {
            mHandler.removeCallbacks(stuckCheckRunnable);
            mHandler.removeCallbacks(retryRunnable);
            mHandler.removeCallbacks(hideChannelRunnable);
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                    playerListener = null;
                }
                player.release();
                player = null;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "释放旧播放器异常", e);
        }

        initPlayer();
        final boolean hasUrl = !TextUtils.isEmpty(currentUrl);
        if (playerView != null) {
            mHandler.post(() -> {
                try {
                    if (playerView != null && player != null) {
                        playerView.setPlayer(player);
                    }
                } finally {
                    if (!hasUrl) isSwitching = false;
                }
            });
        }
        if (hasUrl) {
            retryCount = 0;
            isRetrying = false;

            if (mDecoderMode == DECODER_MODE_SOFT) {
                Toast.makeText(context, "已切换至 软解模式", Toast.LENGTH_SHORT).show();
            } else if (mDecoderMode == DECODER_MODE_HARD) {
                Toast.makeText(context, "已切换至 硬解模式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "已切换至 自动模式", Toast.LENGTH_SHORT).show();
            }

            playUrlInternal(currentUrl, currentPosition);
            mHandler.postDelayed(() -> { isSwitching = false; }, 30000);
        } else if (playerView == null) {
            isSwitching = false;
        }
    }

    public int getDecoderMode() {
        return mDecoderMode;
    }

    private MediaCodecSelector createSmartSelector(boolean preferSoftware) {
        return new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(
                    String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                    throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {

                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

                List<String> blacklist = new ArrayList<>();
                blacklist.add("omx.ms.");
                blacklist.add("c2.mstar.");
                blacklist.add("c2.amlogic.avc.decoder.awesome");
                blacklist.add("omx.hisi.video.decoder");

                List<MediaCodecInfo> filtered = new ArrayList<>();
                for (MediaCodecInfo info : all) {
                    String name = info.name.toLowerCase(Locale.ROOT);
                    boolean blocked = false;
                    for (String prefix : blacklist) {
                        if (name.startsWith(prefix)) { blocked = true; break; }
                    }
                    if (!blocked) filtered.add(info);
                }

                if (filtered.isEmpty()) filtered.addAll(all);

                if (preferSoftware) {
                    List<MediaCodecInfo> soft = new ArrayList<>();
                    List<MediaCodecInfo> hard = new ArrayList<>();
                    for (MediaCodecInfo info : filtered) {
                        if (isSoftwareDecoder(info)) soft.add(info);
                        else hard.add(info);
                    }
                    soft.addAll(hard);
                    return soft;
                }
                return filtered;
            }
        };
    }

    public void registerDecoderModeReceiver() {
        if (decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = DECODER_MODE_AUTO;
                        if ("hard".equals(modeStr)) mode = DECODER_MODE_HARD;
                        else if ("soft".equals(modeStr)) mode = DECODER_MODE_SOFT;
                        setDecoderMode(mode);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            ContextCompat.registerReceiver(context, decoderModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            decoderReceiverRegistered = true;
        } catch (Exception e) {
            LogBridge.e(TAG, "注册解码器广播失败", e);
        }
    }

    public void unregisterDecoderModeReceiver() {
        if (!decoderReceiverRegistered) return;
        try {
            if (decoderModeReceiver != null) {
                context.unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
            }
            decoderReceiverRegistered = false;
        } catch (Exception e) {
            LogBridge.e(TAG, "注销解码器广播失败", e);
        }
    }

    private void switchRenderer(boolean useTexture) {
        if (player == null || playerView == null || context == null) return;
        if (mCurrentUseTexture != null && mCurrentUseTexture == useTexture) {
            if (playerView.getPlayer() != player) playerView.setPlayer(player);
            return;
        }
        ViewParent rawParent = playerView.getParent();
        if (!(rawParent instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) rawParent;

        isRenderingSwitching = true;
        bufferCount = 0;
        boolean wasPlaying = player.isPlaying();
        boolean useController = playerView.getUseController();
        ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();

        int index = parent.indexOfChild(playerView);
        int styleRes = useTexture ? R.style.PlayerView_Texture : R.style.PlayerView_Surface;
        ContextThemeWrapper themedContext = new ContextThemeWrapper(context, styleRes);
        PlayerView newPlayerView = new PlayerView(themedContext);
        newPlayerView.setLayoutParams(layoutParams);
        newPlayerView.setUseController(useController);
        newPlayerView.setKeepContentOnPlayerReset(true);

        int resizeMode;
        switch (mCurrentScaleMode) {
            case FILL:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                break;
            case ZOOM:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                break;
            case FIT:
            default:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                break;
        }
        newPlayerView.setResizeMode(resizeMode);

        newPlayerView.setPlayer(player);
        parent.addView(newPlayerView, index + 1, layoutParams);

        if (!useTexture) {
            surfaceCallbackBound = false;
            bindSurfaceCallback(newPlayerView);
        }

        if (onPlayerViewRecreatedListener != null) {
            onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
        }

        PlayerView oldPlayerView = playerView;
        playerView = newPlayerView;
        playerView.requestFocus();

        mHandler.postDelayed(() -> {
            oldPlayerView.setPlayer(null);
            parent.removeView(oldPlayerView);
            if (wasPlaying && player != null && !player.isPlaying()) {
                player.play();
            }
        }, 300);

        mCurrentUseTexture = useTexture;
        isRenderingSwitching = false;
    }

    public void registerRendererModeReceiver() {
        if (rendererReceiverRegistered) return;
        try {
            rendererModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.RENDERER_TYPE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String mode = sp.getString("renderer_type", "surface");
                        if (playerView != null) switchRenderer("texture".equals(mode));
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            ContextCompat.registerReceiver(context, rendererModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            rendererReceiverRegistered = true;
        } catch (Exception e) {
            LogBridge.e(TAG, "注册渲染方式广播失败", e);
        }
    }

    public void unregisterRendererModeReceiver() {
        if (!rendererReceiverRegistered) return;
        try {
            if (rendererModeReceiver != null) {
                context.unregisterReceiver(rendererModeReceiver);
                rendererModeReceiver = null;
            }
            rendererReceiverRegistered = false;
        } catch (Exception e) {
            LogBridge.e(TAG, "注销渲染方式广播失败", e);
        }
    }

    private boolean surfaceReady = false;
    private boolean pendingBindPlayer = false;

    private boolean surfaceCallbackBound = false;

    public void onForeground() {

        try {
            if (player != null && playerView != null) {
                if (surfaceReady) {
                    if (playerView.getPlayer() != player) {
                        playerView.setPlayer(player);
                    }
                    player.play();
                } else {
                    pendingBindPlayer = true;

                    try {
                        playerView.post(() -> {
                            playerView.setVisibility(View.VISIBLE);
                            playerView.requestLayout();
                            playerView.invalidate();
                        });
                    } catch (Exception ignored) {}

                    try {
                        if (playerView.getPlayer() != player) {
                            playerView.setPlayer(player);
                        }
                        player.play();
                    } catch (Exception ignored) {}

                    retryResumeAfterSurface();
                }
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "切前台异常", e);
        }
    }

    private void retryResumeAfterSurface() {
        try {
            if (mHandler == null) return;
            final int[] retryCount = {0};
            final int maxRetries = 10;
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        retryCount[0]++;
                        if (player != null && playerView != null) {
                            if (surfaceReady) {
                                if (playerView.getPlayer() != player) {
                                    playerView.setPlayer(player);
                                }
                                if (!player.isPlaying()) {
                                    player.play();
                                }
                                pendingBindPlayer = false;
                                LogBridge.d(TAG, "Surface已重建，播放已恢复（重试第" + retryCount[0] + "次）");
                                return;
                            }

                            try {
                                if (playerView.getPlayer() != player) {
                                    playerView.setPlayer(player);
                                }
                                if (!player.isPlaying()) {
                                    player.play();
                                }
                                playerView.setVisibility(View.VISIBLE);
                                playerView.requestLayout();
                            } catch (Exception ignored) {}

                            if (pendingBindPlayer && retryCount[0] < maxRetries) {
                                mHandler.postDelayed(this, 300);
                            } else if (retryCount[0] >= maxRetries) {
                                LogBridge.w(TAG, "Surface恢复重试已达上限(" + maxRetries + "次)，停止重试");
                                pendingBindPlayer = false;
                            }
                        }
                    } catch (Exception e) {
                        LogBridge.e(TAG, "恢复播放重试异常", e);
                    }
                }
            }, 300);
        } catch (Exception e) {
            LogBridge.e(TAG, "启动恢复重试异常", e);
        }
    }

    public void onBackground() {

        try {
            if (player != null) {

            }
            if (playerView != null && surfaceReady) {
                pendingBindPlayer = true;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "切后台异常", e);
        }
    }

    private void ensurePlayerBoundToView() {
        try {
            if (player != null && playerView != null) {
                if (playerView.getPlayer() != player) {
                    playerView.setPlayer(player);
                    LogBridge.d(TAG, "播放器已绑定到视图");
                }
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "绑定播放器到视图失败", e);
        }
    }

    public void detachPlayerView() {
        try {
            if (playerView != null) {
                playerView.setPlayer(null);
                surfaceReady = false;
                pendingBindPlayer = true;
                surfaceCallbackBound = false;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "解绑PlayerView异常", e);
        }
    }

    private void bindSurfaceCallback(final PlayerView view) {
        if (view == null) return;
        if (surfaceCallbackBound) return;
        View videoSurfaceView = view.getVideoSurfaceView();
        if (videoSurfaceView instanceof android.view.SurfaceView) {
            android.view.SurfaceView surfaceView = (android.view.SurfaceView) videoSurfaceView;
            surfaceView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    surfaceReady = true;
                    if (player != null) {
                        if (view.getPlayer() != player) {
                            view.setPlayer(player);
                        }
                        if (!player.isPlaying()) {
                            player.play();
                        }
                    }
                    pendingBindPlayer = false;
                    LogBridge.d(TAG, "Surface创建成功，播放器已绑定视图并持续播放");
                }

                @Override
                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
                    LogBridge.d(TAG, "Surface变化: " + width + "x" + height);
                }

                @Override
                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    surfaceReady = false;
                    pendingBindPlayer = true;
                    LogBridge.d(TAG, "Surface销毁，播放器保持运行不解绑");
                }
            });
            surfaceCallbackBound = true;
        }
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;

        if (view.getContext() instanceof Activity) {
            mActivity = (Activity) view.getContext();
        }

        if (mActivity != null) {
            FrameLayout container = mActivity.findViewById(R.id.sdk_player_container);
            if (container != null) {
                mSdkPlayerContainer = container;
            }
        }

        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String rendererMode = sp.getString("renderer_type", "surface");
        boolean useTexture = "texture".equals(rendererMode);
        switchRenderer(useTexture);

        if (useTexture) {
            playerView.setPlayer(player);
            surfaceReady = true;
            pendingBindPlayer = false;
        } else {

            bindSurfaceCallback(playerView);
            View videoSurfaceView = playerView.getVideoSurfaceView();
            if (videoSurfaceView instanceof android.view.SurfaceView) {
                android.view.SurfaceView surfaceView = (android.view.SurfaceView) videoSurfaceView;
                android.view.Surface surface = surfaceView.getHolder().getSurface();
                if (surface != null && surface.isValid()) {
                    surfaceReady = true;
                    if (player != null && playerView.getPlayer() != player) {
                        playerView.setPlayer(player);
                    }
                    pendingBindPlayer = false;
                } else {
                    surfaceReady = false;
                    pendingBindPlayer = true;
                }
            } else {
                playerView.setPlayer(player);
                surfaceReady = true;
                pendingBindPlayer = false;
            }
        }

        playerView.setUseController(false);
    }

    public FrameLayout getSdkPlayerContainer() {
        return mSdkPlayerContainer;
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) playerView.setKeepScreenOn(enable);
    }

    public void playUrl(String url) {
        playUrl(url, null, null);
    }

    public void playUrl(String url, String channelName) {
        playUrl(url, channelName, null);
    }

    public void playUrl(String url, String channelName, Channel channel) {
        if (!TextUtils.isEmpty(channelName)) this.currentChannelName = channelName;
        this.currentChannel = channel;
        this.backupRetryIndex = -1;
        if (channel != null && TextUtils.isEmpty(this.currentChannelName)) {
            this.currentChannelName = channel.getName();
        }
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();
        playUrlInternal(url, 0);
    }

    public Channel getCurrentChannel() {
        return currentChannel;
    }

    public interface OnSourceFailedListener {
        void onSourceFailed();
    }

    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        this.sourceFailedListener = listener;
    }

    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }

    private boolean isHuyaRoomUrl(String url) {
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

    private boolean isHuyaProtocolUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.startsWith("huya://room/");
    }

    private boolean isHuyaUidProtocolUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.startsWith("huya://uid/");
    }

    private void playUrlInternal(String url) {
        playUrlInternal(url, 0);
    }

    private void playUrlInternal(String url, long initialSeekPosition) {

        try { AppCacheInspector.onBeforePlayback(context); } catch (Throwable ignored) {}

        if (isHuyaUidProtocolUrl(url)) {
            String uidStr = url.replace("huya://uid/", "").trim();
            long uid;
            try {
                uid = Long.parseLong(uidStr);
            } catch (NumberFormatException e) {
                autoRetry("虎牙UID格式错误: " + url);
                return;
            }
            playHuyaStreamByUid(uid, initialSeekPosition);
            return;
        }
        if (isHuyaProtocolUrl(url)) {
            String roomIdStr = url.replace("huya://room/", "").trim();
            int roomId;
            try {
                roomId = Integer.parseInt(roomIdStr);
            } catch (NumberFormatException e) {
                autoRetry("虎牙房间号格式错误: " + url);
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
                autoRetry("虎牙房间号格式错误: " + url);
                return;
            }

            playHuyaStream(roomId, initialSeekPosition);
            return;
        }
        doPlay(url, initialSeekPosition);
    }

    private void playHuyaStream(int roomId, long initialSeekPosition) {
        playHuyaStreamDual(0, roomId, initialSeekPosition);
    }

    private void playHuyaStreamByUid(long uid, long initialSeekPosition) {
        playHuyaStreamDual(uid, 0, initialSeekPosition);
    }

    private void playHuyaStreamDual(final long uid, final int roomId, long initialSeekPosition) {
        final int effectiveRoomId = (roomId > 0 ? roomId : (int) uid);

        final int cacheKey = (uid > 0 ? (int) -uid : roomId);
        mHuyaRoomId = effectiveRoomId;
        final long parseStartTs = System.currentTimeMillis();

        if (mSdkPlayerContainer != null) {
            mSdkPlayerContainer.setVisibility(View.GONE);
        }
        if (playerView != null) {
            playerView.setVisibility(View.VISIBLE);
        }

        synchronized (variantListLock) {
            variantList.clear();
        }
        if (currentChannel != null) {
            try {
                currentChannel.clearBackupUrls();
            } catch (Exception ignored) {}
        }

        if (!HuyaSDKParser.isSDKAvailable()) {

            LogBridge.w(TAG, "【虎牙】SDK 尚未就绪, roomId=" + effectiveRoomId + " → 等待 SDK 初始化完成后自动重试");
            final long startTime = System.currentTimeMillis();
            HuyaSDKParser.addInitReadyListener(new Runnable() {
                @Override public void run() {
                    long waitTime = System.currentTimeMillis() - startTime;
                    LogBridge.i(TAG, "【虎牙】SDK 就绪(等待" + waitTime + "ms), 开始解析 roomId=" + effectiveRoomId);

                    mHandler.post(new Runnable() {
                        @Override public void run() {
                            playHuyaStreamDual(uid, roomId, initialSeekPosition);
                        }
                    });
                }
            });

            mHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!HuyaSDKParser.isSDKAvailable()) {
                        LogBridge.e(TAG, "【虎牙】SDK 等待超时(15s), roomId=" + effectiveRoomId);
                        Toast.makeText(context, "虎牙 SDK 初始化超时，请重试", Toast.LENGTH_SHORT).show();
                        if (sourceFailedListener != null) {
                            sourceFailedListener.onSourceFailed();
                        }
                    }
                }
            }, 15000);
            return;
        }

        HuyaSDKParser.CachedStreams cached = HuyaSDKParser.getCachedStreams(cacheKey);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            long ageSec = (System.currentTimeMillis() - cached.timestamp) / 1000;
            LogBridge.i(TAG, "🚀【虎牙并行加载】命中预解析缓存！房间=" + effectiveRoomId
                    + "，缓存年龄=" + ageSec + "s，流数=" + cached.streams.size()
                    + "，即将瞬时启动播放（跳过实时SDK等待）");
        } else {
            LogBridge.w(TAG, "⚠️【虎牙并行加载】未命中预解析缓存（房间=" + effectiveRoomId
                    + "），退化为实时解析，等待时间约 3~30s。"
                    + " 通常是：该房间在预解析前30名之外 / 直播源刚加载完用户就立刻点击 / 缓存已过期（>60s）");
        }

        LogBridge.d(TAG, "【虎牙】使用 SDK 全量解析, roomId=" + effectiveRoomId
                + (uid > 0 ? " (uid通道 uid=" + uid + ")" : ""));
        HuyaSDKParser.OnSDKFullResultListener sdkFullListener = new HuyaSDKParser.OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaSDKParser.HuyaStreamInfo defaultStream,
                                  List<HuyaSDKParser.HuyaStreamInfo> allStreams,
                                  List<String> lines) {
                long costMs = System.currentTimeMillis() - parseStartTs;
                HuyaSDKParser.CachedStreams cs = HuyaSDKParser.getCachedStreams(cacheKey);
                boolean fromPreload = (cs != null && cs.streams == allStreams)
                        || (costMs < 300);
                LogBridge.i(TAG, "⚡【虎牙解析耗时】" + costMs + "ms, roomId=" + effectiveRoomId
                        + "，缓存命中=" + fromPreload
                        + "，流数=" + (allStreams != null ? allStreams.size() : 0));

                final HuyaSDKParser.HuyaStreamInfo[] streamHolder = {defaultStream};

                if (defaultStream != null && allStreams != null && !allStreams.isEmpty()) {

                    List<HuyaSDKParser.HuyaStreamInfo> sameLineStreams = new ArrayList<>();
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (s != null && s.lineIndex == defaultStream.lineIndex
                                && !TextUtils.isEmpty(s.getPlayUrl())) {
                            sameLineStreams.add(s);
                        }
                    }
                    HuyaSDKParser.HuyaStreamInfo deviceBest =
                            HuyaSDKParser.selectBestStreamForDevice(sameLineStreams);
                    if (deviceBest != null && deviceBest != defaultStream) {
                        LogBridge.i(TAG, "【画质选择】默认流已调整: " + defaultStream.bitRateDisplayName
                                + " → " + deviceBest.bitRateDisplayName
                                + " (统一最高画质)");
                        streamHolder[0] = deviceBest;
                    }
                }

                if (streamHolder[0] == null || TextUtils.isEmpty(streamHolder[0].getPlayUrl())) {
                    LogBridge.e(TAG, "【虎牙】SDK 解析返回空默认地址");
                    mHandler.post(() -> {
                        Toast.makeText(context, "虎牙 SDK 解析失败：返回空地址", Toast.LENGTH_SHORT).show();
                        if (sourceFailedListener != null) {
                            sourceFailedListener.onSourceFailed();
                        }
                    });
                    return;
                }

                if (allStreams != null && allStreams.size() > 1) {
                    Set<Integer> lineIndexSet = new TreeSet<>();
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (!TextUtils.isEmpty(s.getPlayUrl())) {
                            lineIndexSet.add(s.lineIndex);
                        }
                    }
                    List<Integer> uniqueLineIndices = new ArrayList<>(lineIndexSet);

                    if (uniqueLineIndices.size() > 1) {
                        String linePrefKey = "huya_line_poll_" + effectiveRoomId;
                        int lastLineIdx = sp.getInt(linePrefKey, uniqueLineIndices.get(0));

                        int currentPos = -1;
                        for (int i = 0; i < uniqueLineIndices.size(); i++) {
                            if (uniqueLineIndices.get(i) == lastLineIdx) {
                                currentPos = i;
                                break;
                            }
                        }
                        if (currentPos == -1) currentPos = 0;

                        int nextPos = (currentPos + 1) % uniqueLineIndices.size();
                        int targetLineIndex = uniqueLineIndices.get(nextPos);

                        if (targetLineIndex != streamHolder[0].lineIndex) {
                            LogBridge.d(TAG, "【虎牙】线路轮询：切换到线路 " + targetLineIndex);
                            HuyaSDKParser.HuyaStreamInfo targetStream = null;
                            for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                                if (s.lineIndex == targetLineIndex && !TextUtils.isEmpty(s.getPlayUrl())) {
                                    if (s.isDefaultBitrate) {
                                        targetStream = s;
                                        break;
                                    }
                                    if (targetStream == null || s.bitRate > targetStream.bitRate) {
                                        targetStream = s;
                                    }
                                }
                            }
                            if (targetStream != null) {
                                streamHolder[0] = targetStream;
                            }
                        }
                        sp.edit().putInt(linePrefKey, streamHolder[0].lineIndex).apply();
                    }
                }

                List<Variant> allVariants = new ArrayList<>();
                if (allStreams != null) {
                    for (HuyaSDKParser.HuyaStreamInfo s : allStreams) {
                        if (!TextUtils.isEmpty(s.getPlayUrl())) {
                            allVariants.add(Variant.fromHuyaStreamInfo(s));
                        }
                    }
                }
                final String defaultUrl = streamHolder[0].getPlayUrl();
                mCurrentHuyaLineIndex = streamHolder[0].lineIndex;

                Map<Integer, List<Variant>> lineGroups = new TreeMap<>();
                for (Variant v : allVariants) {
                    List<Variant> group = lineGroups.get(v.huyaLineIndex);
                    if (group == null) {
                        group = new ArrayList<>();
                        lineGroups.put(v.huyaLineIndex, group);
                    }
                    group.add(v);
                }
                for (List<Variant> group : lineGroups.values()) {
                    Collections.sort(group, (a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
                }

                List<Variant> currentLineVariants = lineGroups.get(mCurrentHuyaLineIndex);
                if (currentLineVariants != null) {
                    int defIdx = -1;
                    for (int i = 0; i < currentLineVariants.size(); i++) {
                        if (defaultUrl.equals(currentLineVariants.get(i).url)) { defIdx = i; break; }
                    }
                    if (defIdx > 0) {
                        Variant defV = currentLineVariants.remove(defIdx);
                        currentLineVariants.add(0, defV);
                    }
                }

                synchronized (variantListLock) {
                    variantList.clear();
                    variantList.addAll(allVariants);
                }
                currentResolutionLabel = currentLineVariants != null && !currentLineVariants.isEmpty()
                        ? currentLineVariants.get(0).getDisplayLabel() : "";
                int totalVariantCount = 0;
                for (List<Variant> g : lineGroups.values()) totalVariantCount += g.size();
                LogBridge.d(TAG, "【虎牙】variantList 填充: 共 " + totalVariantCount + " 个清晰度，分布在 " + lineGroups.size() + " 条线路");
                for (java.util.Map.Entry<Integer, List<Variant>> entry : lineGroups.entrySet()) {
                    StringBuilder sb = new StringBuilder("  线路").append(entry.getKey()).append(": ");
                    for (Variant v : entry.getValue()) {
                        sb.append(v.getDisplayLabel()).append(" ");
                    }
                    LogBridge.d(TAG, sb.toString());
                }

                if (currentChannel != null) {
                    List<String> backups = currentChannel.getBackupUrls();
                    if (backups == null) { backups = new ArrayList<>(); }
                    else backups.clear();

                    Set<String> seenUrls = new HashSet<>();
                    if (allVariants != null) {
                        for (Variant v : allVariants) {
                            if (v.url != null && !seenUrls.contains(v.url)) {
                                seenUrls.add(v.url);
                                if (!v.url.equals(defaultUrl) && !backups.contains(v.url)) {
                                    backups.add(v.url);
                                }
                            }
                        }
                    }

                    LogBridge.d(TAG, "【虎牙】扁平化线路: 主源 + " + backups.size() + " 个备源 (总变体数=" + allVariants.size() + ")");
                }

                LogBridge.d(TAG, "【虎牙】SDK 全量解析成功, 默认流=" + defaultUrl.substring(0, Math.min(80, defaultUrl.length())));

                mPendingPlaybackHeaders = null;
                mHandler.post(() -> doPlay(defaultUrl, initialSeekPosition));
            }

            @Override
            public void onError(String error) {
                LogBridge.e(TAG, "【虎牙】SDK 全量解析失败: " + error);
                mHandler.post(() -> {
                    Toast.makeText(context, "虎牙 SDK 解析失败: " + error, Toast.LENGTH_SHORT).show();
                    if (sourceFailedListener != null) {
                        sourceFailedListener.onSourceFailed();
                    }
                });
            }
        };

        if (uid > 0) {
            HuyaSDKParser.parseFullByUid(uid, sdkFullListener);
        } else {
            HuyaSDKParser.parseFull(roomId, sdkFullListener);
        }
    }

    private void doPlay(String url, long initialSeekPosition) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();

            String lowerUrl = playUrl.toLowerCase(Locale.ROOT);
            boolean isRealStream = isHlsUrl(playUrl)
                    || lowerUrl.endsWith(".flv")
                    || lowerUrl.contains(".flv.huya.com/")
                    || lowerUrl.contains(".hls.huya.com/")
                    || (lowerUrl.startsWith("http") && (lowerUrl.contains("/src?ws") || lowerUrl.contains("&wssecret=")));
            LogBridge.d(TAG, "doPlay: url=" + playUrl.substring(0, Math.min(100, playUrl.length())) + " isHls=" + isHlsUrl(playUrl) + " isRealStream=" + isRealStream);

            String finalUrl;
            if (currentChannel != null && !isRealStream) {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
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
                dLog("切换线路后播放：" + finalUrl);
            } else {
                finalUrl = playUrl;
                dLog("直接播放地址：" + finalUrl);
            }
            currentUrl = finalUrl;

            if (isHuyaSource(finalUrl)) {
                doPlayHuya(finalUrl, initialSeekPosition);
            } else {
                doPlayNormal(finalUrl, initialSeekPosition);
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                if (listener != null) listener.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            autoRetry("播放异常：" + e.getMessage(), e);
        }
    }

    public boolean isHuyaSource(String url) {
        if (url != null && (url.contains(".huya.com/") || url.contains("huya.com/src"))) {
            return true;
        }
        if (currentChannel != null) {
            String cid = currentChannel.getChannelId();
            if (cid != null && (cid.startsWith("huya_") || cid.startsWith("hy_"))) {
                return true;
            }
        }
        return false;
    }

    private void doPlayNormal(String url, long initialSeekPosition) {
        LogBridge.d(TAG, "【普通源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        if (isHlsUrl(url)) {
            fetchAndParseMasterPlaylistNormal(url);
        } else {
            synchronized (variantListLock) { variantList.clear(); }
        }

        final String channelName = currentChannelName;
        new Thread(() -> {
            try {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                boolean debugEnabled = sp.getBoolean("debug_log_enable", false);

                RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
                httpFactory.setDebugLogEnabled(debugEnabled);

                Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
                Map<String, String> localHeaders = new HashMap<>();
                for (String name : globalHeaders.names()) {
                    localHeaders.put(name, globalHeaders.get(name));
                }
                dLog("【普通源】使用默认 headers " + localHeaders.size() + " 项");

                httpFactory.setDefaultRequestProperties(localHeaders);
                httpFactory.setChannelName(channelName);
                httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                        .setAllowCrossDomainRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true))
                        .setAllowCrossProtocolRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true))
                        .setFollowRedirectsWithHeaders(sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true))
                        .setIgnoreSslErrorRedirect(sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false))
                        .setConnectTimeoutMs(5000)
                        .setReadTimeoutMs(8000);

                MediaItem mediaItem = MediaItem.fromUri(url);
                MediaSource mediaSource;
                if (isHlsUrl(url)) {
                    mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
                }

                synchronized (reusableHeaderMap) {
                    reusableHeaderMap.clear();
                    reusableHeaderMap.putAll(localHeaders);
                }

                mHandler.post(() -> {
                    try {
                        ensurePlayerBoundToView();
                        player.setMediaSource(mediaSource, true);
                        player.prepare();
                        if (initialSeekPosition > 0) player.seekTo(initialSeekPosition);
                        player.play();
                        startStuckDetection();
                    } catch (Exception e) {
                        handleAsyncPlaybackError(e);
                    }
                });
            } catch (Exception e) {
                LogBridge.e(TAG, "播放准备(后台)异常", e);
                mHandler.post(() -> handleAsyncPlaybackError(e));
            }
        }, "TVPlayer-Prepare-Normal").start();
    }

    private void doPlayHuya(String url, long initialSeekPosition) {
        LogBridge.d(TAG, "【虎牙源】开始播放: " + url.substring(0, Math.min(80, url.length())));

        final String channelName = currentChannelName;
        final int roomId = mHuyaRoomId;
        final Map<String, String> pendingHeaders = mPendingPlaybackHeaders;
        mPendingPlaybackHeaders = null;

        new Thread(() -> {
            try {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                boolean debugEnabled = sp.getBoolean("debug_log_enable", false);

                RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
                httpFactory.setDebugLogEnabled(debugEnabled);

                Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
                Map<String, String> localHeaders = new HashMap<>();
                for (String name : globalHeaders.names()) {
                    localHeaders.put(name, globalHeaders.get(name));
                }
                localHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");

                String huyaReferer = roomId > 0 ? "https://www.huya.com/" + roomId : "https://www.huya.com/";
                localHeaders.put("Referer", huyaReferer);

                localHeaders.put("Accept", "*/*");
                localHeaders.put("Accept-Language", "zh-CN,zh;q=0.9");
                localHeaders.put("Accept-Encoding", "identity");
                localHeaders.put("Connection", "keep-alive");
                dLog("【虎牙源】已启用浏览器UA+Referer(" + huyaReferer + ") 防盗链头");

                if (pendingHeaders != null && !pendingHeaders.isEmpty()) {
                    int cnt = 0;
                    for (Map.Entry<String, String> e : pendingHeaders.entrySet()) {

                        if ("Origin".equalsIgnoreCase(e.getKey())) continue;
                        if ("Referer".equalsIgnoreCase(e.getKey())) continue;
                        localHeaders.put(e.getKey(), e.getValue());
                        cnt++;

                        LogBridge.d(TAG, "  Header[" + e.getKey() + "] = " + e.getValue().substring(0, Math.min(50, e.getValue().length())));
                    }
                    LogBridge.d(TAG, "【虎牙源】解析器专用Headers注入 " + cnt + " 项(含Cookie="
                            + (pendingHeaders.containsKey("Cookie") ? "是" : "否") + ")");
                } else {
                    LogBridge.d(TAG, "【虎牙源】mPendingPlaybackHeaders 为空，走默认虎牙 headers");
                }

                boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
                if (sendCookie && !localHeaders.containsKey("Cookie")) {
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) localHeaders.put("Cookie", cookies);
                }

                synchronized (reusableHeaderMap) {
                    reusableHeaderMap.clear();
                    reusableHeaderMap.putAll(localHeaders);
                }

                httpFactory.setDefaultRequestProperties(localHeaders);
                httpFactory.setChannelName(channelName);

                httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                        .setAllowCrossDomainRedirects(true)
                        .setAllowCrossProtocolRedirects(true)
                        .setFollowRedirectsWithHeaders(true)
                        .setIgnoreSslErrorRedirect(sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false))
                        .setConnectTimeoutMs(5000)
                        .setReadTimeoutMs(8000);

                MediaItem mediaItem = MediaItem.fromUri(url);
                MediaSource mediaSource;
                if (isHlsUrl(url)) {
                    mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
                }

                mHandler.post(() -> {
                    try {
                        ensurePlayerBoundToView();
                        player.setMediaSource(mediaSource, true);
                        player.prepare();
                        if (initialSeekPosition > 0) player.seekTo(initialSeekPosition);
                        player.play();
                        startStuckDetection();
                    } catch (Exception e) {
                        handleAsyncPlaybackError(e);
                    }
                });
            } catch (Exception e) {
                LogBridge.e(TAG, "播放准备(后台)异常", e);
                mHandler.post(() -> handleAsyncPlaybackError(e));
            }
        }, "TVPlayer-Prepare-Huya").start();
    }

    private void fetchAndParseMasterPlaylistNormal(String masterUrl) {
        if (isParsingMasterPlaylist) return;
        isParsingMasterPlaylist = true;
        ensurePlaylistExecutor().execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                LogBridge.d(TAG, "【普通源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                java.net.URL url = new java.net.URL(masterUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                connection.setRequestProperty("Accept", "*/*");

                int code = connection.getResponseCode();
                LogBridge.d(TAG, "【普通源】主播放列表响应码: " + code);

                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (java.io.InputStream is = connection.getInputStream();
                         java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    LogBridge.d(TAG, "【普通源】主播放列表长度: " + playlist.length());
                    parseMasterPlaylist(playlist, masterUrl);
                } else if (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                    String newUrl = connection.getHeaderField("Location");
                    LogBridge.d(TAG, "【普通源】重定向到: " + newUrl);
                    isParsingMasterPlaylist = false;
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistNormal(newUrl);
                        return;
                    }
                } else {
                    LogBridge.e(TAG, "【普通源】主播放列表请求失败: code=" + code);
                    synchronized (variantListLock) { variantList.clear(); }
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "【普通源】解析主播放列表失败: ", e);
                synchronized (variantListLock) { variantList.clear(); }
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                isParsingMasterPlaylist = false;
            }
        });
    }

    private void fetchAndParseMasterPlaylistHuya(String masterUrl) {
        if (isParsingMasterPlaylist) return;
        isParsingMasterPlaylist = true;
        ensurePlaylistExecutor().execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                LogBridge.d(TAG, "【虎牙源】解析主播放列表: " + masterUrl.substring(0, Math.min(100, masterUrl.length())));

                java.net.URL url = new java.net.URL(masterUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
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

                if (mPendingPlaybackHeaders != null && !mPendingPlaybackHeaders.isEmpty()) {
                    for (Map.Entry<String, String> e : mPendingPlaybackHeaders.entrySet()) {
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }

                String cookies = android.webkit.CookieManager.getInstance().getCookie(masterUrl);
                if (cookies != null && !cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookies);
                    LogBridge.d(TAG, "【虎牙源】发送 Cookie: " + cookies.substring(0, Math.min(80, cookies.length())));
                }

                int code = connection.getResponseCode();
                LogBridge.d(TAG, "【虎牙源】主播放列表响应码: " + code);

                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder content = new StringBuilder();
                    try (java.io.InputStream is = connection.getInputStream();
                         java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line).append("\n");
                        }
                    }
                    String playlist = content.toString();
                    LogBridge.d(TAG, "【虎牙源】主播放列表长度: " + playlist.length());

                } else if (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || code == java.net.HttpURLConnection.HTTP_MOVED_PERM) {

                    String newUrl = connection.getHeaderField("Location");
                    LogBridge.d(TAG, "【虎牙源】手动重定向到: " + newUrl);
                    isParsingMasterPlaylist = false;
                    if (newUrl != null) {
                        fetchAndParseMasterPlaylistHuya(newUrl);
                        return;
                    }
                } else {

                    try (java.io.InputStream es = connection.getErrorStream()) {
                        if (es != null) {
                            StringBuilder err = new StringBuilder();
                            byte[] buf = new byte[1024];
                            int len;
                            while ((len = es.read(buf)) != -1) {
                                err.append(new String(buf, 0, len));
                            }
                            if (err.length() > 0) {
                                LogBridge.e(TAG, "【虎牙源】错误响应体: " + err.substring(0, Math.min(200, err.length())));
                            }
                        }
                    }
                    LogBridge.e(TAG, "【虎牙源】主播放列表请求失败: code=" + code);

                }
            } catch (Exception e) {
                LogBridge.e(TAG, "【虎牙源】解析主播放列表失败: ", e);

            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                isParsingMasterPlaylist = false;
            }
        });
    }

    private void parseMasterPlaylist(String playlist, String baseUrl) {
        List<Variant> list = new ArrayList<>();
        Pattern streamInfPattern = Pattern.compile("^#EXT-X-STREAM-INF:", Pattern.CASE_INSENSITIVE);
        Pattern bandwidthPattern = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern resolutionPattern = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
        dLog("播放列表内容（截取前500字符）：\n" + playlist.substring(0, Math.min(playlist.length(), 500)));

        String[] lines = playlist.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!streamInfPattern.matcher(line).find()) continue;

            Matcher bwMatcher = bandwidthPattern.matcher(line);
            if (!bwMatcher.find()) continue;
            int bandwidth = Integer.parseInt(bwMatcher.group(1));

            int width = 0, height = 0;
            String resolutionStr = null;
            Matcher resMatcher = resolutionPattern.matcher(line);
            if (resMatcher.find()) {
                width = Integer.parseInt(resMatcher.group(1));
                height = Integer.parseInt(resMatcher.group(2));
                resolutionStr = width + "x" + height;
            }

            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (!next.isEmpty() && !next.startsWith("#")) {
                    uri = next;
                    break;
                }
            }
            if (uri != null) {
                if (!uri.startsWith("http")) {
                    uri = resolveUrl(baseUrl, uri);
                }
                list.add(new Variant(uri, bandwidth, width, height));
                dLog("解析到清晰度: " + (height > 0 ? resolutionStr : "自适应") + " -> " + uri);
            }
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.height, b.height));
        synchronized (variantListLock) { this.variantList = list; }
        if (!list.isEmpty()) {
            dLog("解析到 " + list.size() + " 个清晰度");
        } else {
            LogBridge.w(TAG, "未解析到任何清晰度流，可能是直播源本身不支持多码率或网络被拦截");
        }
    }

    private String resolveUrl(String base, String relative) {
        try {
            URL baseUrl = new URL(base);
            URL resolved = new URL(baseUrl, relative);
            return resolved.toString();
        } catch (Exception e) {
            return relative;
        }
    }

    public List<String> getAvailableResolutions() {
        List<String> resolutions = new ArrayList<>();
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                String label = v.getDisplayLabel();
                if (!resolutions.contains(label)) {
                    resolutions.add(label);
                }
            }
        }
        return resolutions;
    }

    public List<String> getAvailableLines() {
        List<String> lines = new ArrayList<>();
        Channel ch = getCurrentChannel();
        if (ch == null) return lines;

        lines.add("主源");
        List<String> backups = ch.getBackupUrls();
        if (backups != null) {
            for (int i = 0; i < backups.size(); i++) {
                lines.add("源" + (i + 1));
            }
        }
        return lines;
    }

    public void switchToHuyaLine(int lineIndex) {
        Channel ch = getCurrentChannel();
        if (ch == null) return;

        if (lineIndex < 0) lineIndex = 0;

        if (lineIndex == 0) {
            String url = ch.getMainPlayUrl();
            if (!TextUtils.isEmpty(url)) {
                ch.setCurrentLineIndex(0);
                playUrlInternal(url);
            }
        } else {
            List<String> backups = ch.getBackupUrls();
            int backupIdx = lineIndex - 1;
            if (backups != null && backupIdx >= 0 && backupIdx < backups.size()) {
                ch.setCurrentLineIndex(lineIndex);
                playUrlInternal(backups.get(backupIdx));
            }
        }
    }

    private int findLineIndexByUrl(String url) {
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                if (url.equals(v.url)) return v.huyaLineIndex;
            }
        }
        return -1;
    }

    private void rebuildVariantListForLine(int lineIndex) {
        synchronized (variantListLock) {
            List<Variant> currentLine = new ArrayList<>();
            List<Variant> otherLines = new ArrayList<>();
            for (Variant v : variantList) {
                if (v.huyaLineIndex == lineIndex) {
                    currentLine.add(v);
                } else {
                    otherLines.add(v);
                }
            }
            Collections.sort(currentLine, (a, b) -> Integer.compare(b.bandwidth, a.bandwidth));
            variantList.clear();
            variantList.addAll(currentLine);
            variantList.addAll(otherLines);

            if (!currentLine.isEmpty()) {
                currentResolutionLabel = currentLine.get(0).getDisplayLabel();
            }
        }
        LogBridge.d(TAG, "【虎牙】切换到线路 " + lineIndex + ", 当前线路清晰度: " + getAvailableResolutions());
    }

    public void switchToResolution(int targetHeight, String... matchLabelOpt) {
        List<Variant> snapshot;
        synchronized (variantListLock) {
            snapshot = new ArrayList<>(variantList);
        }
        if (snapshot.isEmpty()) {
            LogBridge.w(TAG, "无多码率信息，无法切换清晰度");
            return;
        }
        String matchLabel = (matchLabelOpt != null && matchLabelOpt.length > 0) ? matchLabelOpt[0] : null;
        Variant selected = null;

        if (!TextUtils.isEmpty(matchLabel)) {
            for (Variant v : snapshot) {
                if (matchLabel.equals(v.getDisplayLabel())
                        || matchLabel.equals(v.resolutionLabel)) {
                    selected = v;
                    break;
                }
            }
        }

        if (selected == null && targetHeight > 0) {
            for (Variant v : snapshot) {
                if (v.height >= targetHeight) {
                    selected = v;
                    break;
                }
            }
        }

        if (selected == null) {
            selected = snapshot.get(0);
        }

        currentResolutionLabel = selected.getDisplayLabel();
        dLog("切换清晰度到：" + selected.getDisplayLabel() + "，URL=" + (selected.url != null ? selected.url.substring(0, Math.min(60, selected.url.length())) : "(空)"));
        playUrlInternal(selected.url);
    }

    public enum ScaleMode {FIT, FILL, ZOOM}

    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            this.mCurrentScaleMode = mode;
            switch (mode) {
                case FIT:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
                case FILL:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case ZOOM:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    break;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "设置缩放模式异常", e);
        }
    }

    public void setSurface(Surface surface) {
        try {
            if (player != null) {
                player.setVideoSurface(surface);
                LogBridge.d(TAG, "播放器已绑定 Surface");
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "绑定 Surface 失败", e);
        }
    }

    public String getCurrentResolutionLabel() {
        return currentResolutionLabel;
    }

    public void setCurrentChannelNumber(int num) {
        currentChannelNumber = num;
    }

    public void bindChannelText(TextView textView) {
        channelNumberTextView = textView;
    }

    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
            mHandler.removeCallbacks(hideChannelRunnable);
            mHandler.postDelayed(hideChannelRunnable, CHANNEL_NUM_HIDE_DELAY);
        }
    }

    private void hideChannelNum() {
        if (channelNumberTextView != null) channelNumberTextView.setVisibility(View.GONE);
    }

    public static class LiveInfo {
        public String resolution = "未知";
        public String bitrate = "0";
        public String audio = "未知";
        public String format = "未知";
    }

    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        try {
            if (player != null) {
                Format videoFormat = player.getVideoFormat();
                if (videoFormat != null) {
                    int width = videoFormat.width, height = videoFormat.height;
                    if (width > 0 && height > 0) info.resolution = width + "×" + height;
                    info.format = friendlyMime(videoFormat.sampleMimeType);
                    if (videoFormat.bitrate > 0)
                        info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", videoFormat.bitrate / 1000000f);
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = friendlyMime(audioFormat.sampleMimeType);
                    if (audioFormat.sampleRate > 0) info.audio += " " + (audioFormat.sampleRate / 1000) + "kHz";
                }
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "获取直播信息异常", e);
        }
        return info;
    }

    private static String friendlyMime(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) return "未知";
        String m = mimeType.toLowerCase(Locale.ROOT);
        if (m.contains("avc") || m.contains("h264") || m.endsWith("/264")) return "H.264";
        if (m.contains("hevc") || m.contains("h265")) return "H.265 (HEVC)";
        if (m.contains("av1")) return "AV1";
        if (m.contains("vp9")) return "VP9";
        if (m.contains("vp8")) return "VP8";
        if (m.contains("mpeg2") || m.contains("mp2v")) return "MPEG-2";
        if (m.contains("mpeg4") || m.contains("mp4v")) return "MPEG-4";
        if (m.contains("wmv")) return "WMV";
        if (m.contains("mp4a") || m.contains("aac") || m.contains("mpeg4-generic")) return "AAC";
        if (m.contains("ac3")) return "AC-3";
        if (m.contains("eac3") || m.contains("ec3")) return "E-AC-3 (Dolby Digital Plus)";
        if (m.contains("ac4")) return "AC-4";
        if (m.contains("opus")) return "Opus";
        if (m.contains("vorbis")) return "Vorbis";
        if (m.contains("flac")) return "FLAC";
        if (m.contains("g711") || m.contains("alaw") || m.contains("ulaw")) return "G.711";
        if (m.contains("pcm")) return "PCM";
        if (m.contains("wma")) return "WMA";
        if (m.contains("mp3") || m.endsWith("/mpeg") && m.startsWith("audio/")) return "MP3";
        return mimeType;
    }

    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        liveInfoUpdateListener = listener;
    }

    public void pause() {
        try {
            if (player != null) player.pause();
        } catch (Exception ignored) {}
    }

    public void resume() {
        try {
            ensurePlayerBoundToView();
            if (player != null) player.play();
        } catch (Exception ignored) {}
    }

    public void togglePlayWhenReady() {
        try {
            if (player == null) return;
            if (player.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE
                    || player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) {
                return;
            }
            player.setPlayWhenReady(!player.getPlayWhenReady());
        } catch (Exception ignored) {}
    }

    public boolean isPlaying() {
        try {
            return player != null && player.getPlayWhenReady()
                    && player.getPlaybackState() != androidx.media3.common.Player.STATE_IDLE
                    && player.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED;
        } catch (Exception e) {
            return false;
        }
    }

    public long getCurrentPosition() {
        try {
            return player != null ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public long getDuration() {
        try {
            return player != null ? player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void seekTo(long positionMs) {
        try {
            if (player != null) player.seekTo(positionMs);
        } catch (Exception ignored) {}
    }

    public com.tv.live.util.SourceHealthChecker getHealthChecker() {
        return healthChecker;
    }

    public void release() {

        try {
            stopStuckDetection();
            cancelRetry();
            if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
            updateWakeLock(false);
            try { unregisterDecoderModeReceiver(); } catch (Exception e) {
                LogBridge.w(TAG, "注销解码器广播异常: " + e.getMessage());
            }
            try { unregisterRendererModeReceiver(); } catch (Exception e) {
                LogBridge.w(TAG, "注销渲染方式广播异常: " + e.getMessage());
            }

            if (decoderModeManager != null) {
                try { decoderModeManager.release(); } catch (Exception e) {
                    LogBridge.w(TAG, "decoderModeManager.release 异常: " + e.getMessage());
                }
                decoderModeManager = null;
            }
            if (variantManager != null) {
                try { variantManager.release(); } catch (Exception e) {
                    LogBridge.w(TAG, "variantManager.release 异常: " + e.getMessage());
                }
                variantManager = null;
            }
            if (huyaStreamPlayer != null) {
                try { huyaStreamPlayer.release(); } catch (Exception e) {
                    LogBridge.w(TAG, "huyaStreamPlayer.release 异常: " + e.getMessage());
                }
                huyaStreamPlayer = null;
            }

            onPlayerViewRecreatedListener = null;
            sourceFailedListener = null;
            liveInfoUpdateListener = null;
            listener = null;

            mActivity = null;
            if (mSdkPlayerContainer != null) {
                try {
                    mSdkPlayerContainer.removeAllViews();
                    mSdkPlayerContainer.setVisibility(View.GONE);
                } catch (Exception ignored) {}
                mSdkPlayerContainer = null;
            }

            if (player != null) {
                try {
                    if (playerListener != null) {
                        try { player.removeListener(playerListener); }
                        catch (Exception e) { LogBridge.w(TAG, "removeListener 异常: " + e.getMessage()); }
                        playerListener = null;
                    }
                    player.release();
                } catch (Exception e) {
                    LogBridge.w(TAG, "player.release 异常: " + e.getMessage());
                }
                player = null;
            }
            if (playerView != null) {
                try {
                    playerView.setPlayer(null);
                    playerView.setVisibility(View.VISIBLE);
                } catch (Exception ignored) {}
                playerView = null;
            }

            if (trackSelector != null) {
                try {
                    trackSelector.release();
                } catch (Exception e) {

                    LogBridge.w(TAG, "trackSelector.release 异常（已吞掉，不影响清理）: " + e.getMessage());
                }
                trackSelector = null;
            }

            if (healthChecker != null) {
                try { healthChecker.release(); }
                catch (Exception e) { LogBridge.w(TAG, "healthChecker.release 异常: " + e.getMessage()); }
                healthChecker = null;
            }

            synchronized (variantListLock) {
                variantList.clear();
            }
            reusableHeaderMap.clear();

            channelNumberTextView = null;
            currentChannel = null;
            currentChannelName = "";
            currentUrl = null;
            mHuyaRoomId = -1;

            context = null;
            sp = null;
        } catch (Exception e) {

            LogBridge.e(TAG, "释放异常（顶层兜底）", e);
        } finally {

            instance = null;
        }
    }

    public static void shutdownThreadPool() {
        if (sPlaylistExecutor != null && !sPlaylistExecutor.isShutdown()) {
            sPlaylistExecutor.shutdownNow();
        }
    }

    private static ExecutorService ensurePlaylistExecutor() {
        if (sPlaylistExecutor == null || sPlaylistExecutor.isShutdown()) {
            synchronized (TVPlayerManager.class) {
                if (sPlaylistExecutor == null || sPlaylistExecutor.isShutdown()) {
                    sPlaylistExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "TVPlayer-PlaylistParser");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return sPlaylistExecutor;
    }
}
