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
import com.tv.live.util.HuyaSDKParser.HuyaLiveProbe;
import com.tv.live.util.Variant;
import com.tv.live.util.DecoderModeManager;
import com.tv.live.util.VariantManager;
import com.tv.live.util.HuyaStreamPlayer;
import com.tv.live.util.TvExtractorsFactory;
import com.tv.live.util.CacheManager.AppCacheInspector;
import com.tv.live.exception.RedirectFailedException;
import com.tv.live.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final int MAX_CHANXIANG_REFRESH = 1;
    private static final int MAX_RENYIBO_REFRESH = 3;
    /** 源5 直链有效期实测仅约 120s，故设冷却间隔：距上次重拉不足此值则不再重拉（防抖/防循环） */
    private static final long RENYIBO_REFRESH_MIN_INTERVAL_MS = 15000;
    /** 源6(咪咕)直链签名同样短时有效，重解析次数与冷却间隔 */
    private static final int MAX_MIGU_REFRESH = 3;
    private static final long MIGU_REFRESH_MIN_INTERVAL_MS = 15000;
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

    /**
     * 【虎牙】SDK 解析出的流地址 -> 播放器实测的「最终落地地址」（CDN 调度跳之后）缓存。
     * 目的：SDK 已经解析过一次，播放器不该每次起播/重连/切分片都再解析一遍重定向；
     * 首次播放时由数据源回传最终地址并缓存，之后直接用最终地址起播（零跳）。
     */
    private static final long HUYA_FINAL_URL_TTL_MS = 20 * 60 * 1000L;
    private static final Map<String, HuyaFinalUrl> sHuyaFinalUrlCache = new ConcurrentHashMap<>();
    /** 标记本次 doPlay 走「虎牙 SDK 已解析好的直连流」路径：禁止被频道主/备源覆盖、强制走虎牙播放链路 */
    private volatile boolean mHuyaDirectPlay = false;
    private volatile String mLastHuyaFinalUrlKey = null;

    private static final class HuyaFinalUrl {
        final String url;
        final long ts = System.currentTimeMillis();
        HuyaFinalUrl(String url) { this.url = url; }
        boolean expired() { return System.currentTimeMillis() - ts > HUYA_FINAL_URL_TTL_MS; }
    }

    private static String peekHuyaFinalUrl(String originalUrl) {
        if (originalUrl == null) return null;
        HuyaFinalUrl f = sHuyaFinalUrlCache.get(originalUrl);
        if (f == null) return null;
        if (f.expired()) { sHuyaFinalUrlCache.remove(originalUrl); return null; }
        return f.url;
    }

    private static void dropHuyaFinalUrl(String originalUrl) {
        if (originalUrl != null) sHuyaFinalUrlCache.remove(originalUrl);
    }

    private static volatile TVPlayerManager instance;
    private Context context;
    /** 播放器实例：由后台线程构建（冷启动优化），volatile 保证主线程可觑 */
    private volatile ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
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
    private int chanxiangRefreshCount = 0;
    /** 源5(爱其意)直链重拉计数，每频道最多触发 {@link #MAX_RENYIBO_REFRESH} 次，防循环 */
    private int renyiboRefreshCount = 0;
    /** 上次源5 直链重拉时间戳（冷却用） */
    private long lastRenyiboRefreshAtMs = 0;
    /** 源6(咪咕)重解析计数与时间戳（冷却用） */
    private int miguRefreshCount = 0;
    private long lastMiguRefreshAtMs = 0;
    /** 源6(咪咕) JS 引擎解析的整体超时（毫秒）；超时即降级到纯 Java 实现 */
    private static final long MIGU_JS_TIMEOUT_MS = 25_000L;
    /**
     * 源6(咪咕) 正在后台解析的<b>待解析地址</b>（{@code miguhd://<pID>} 或 {@code nowtv://<tvId>}）。
     * 同一地址解析期间忽略重复请求，避免 doPlay 的多次触发（起播 + 重试）并发打同一接口。
     */
    private volatile String miguResolvingPid = null;

    // ===================== ★【2026-10-08】咪咕直链缓存回放（冷启动提速） =====================
    // 背景：咪咕源冷启动 = 取源(1.3s) → JS引擎(1.2s) → 302探测(0.1s) → 缓冲(0.6s) ≈ 3.3s；
    //       直链源只有缓冲 ≈ 1s。把上次解析成功的最终直链按 tvId 持久化，冷启动先直接播
    //       缓存（与直链源同速），后台解析链照常执行刷新缓存；缓存直链若失效，
    //       autoRetry 统一入口里立即作废缓存，解析完成时无缝切换到新直链。
    /** 缓存回放中的直链（当前实际在播的地址）；null = 无回放 */
    private volatile String miguCachedReplayUrl = null;
    /** 缓存回放对应的频道 tvId */
    private volatile int miguCachedReplayTvId = 0;
    /** 缓存回放直链是否已失效（onError/autoRetry 时置位） */
    private volatile boolean miguCachedReplayFailed = false;
    /** 直链缓存有效期：咪咕直播 token 实际有效期更长，取保守值；失效由错误回退兜底 */
    private static final long MIGU_DIRECT_TTL_MS = 2L * 60 * 60 * 1000;

    private static android.content.SharedPreferences miguDirectPrefs() {
        return MyApplication.getInstance()
                .getSharedPreferences("migu_direct_cache", android.content.Context.MODE_PRIVATE);
    }

    /** 读某频道的缓存直链；过期/缺失返回 null（纯本机操作，零网络请求） */
    private static String loadCachedDirectUrl(int tvId) {
        if (tvId <= 0) return null;
        try {
            String url = miguDirectPrefs().getString("u" + tvId, null);
            long ts = miguDirectPrefs().getLong("t" + tvId, 0L);
            if (TextUtils.isEmpty(url) || ts <= 0L) return null;
            if (System.currentTimeMillis() - ts > MIGU_DIRECT_TTL_MS) return null;
            return url;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 写某频道的缓存直链 */
    private static void saveCachedDirectUrl(int tvId, String url) {
        if (tvId <= 0 || TextUtils.isEmpty(url)) return;
        try {
            miguDirectPrefs().edit()
                    .putString("u" + tvId, url)
                    .putLong("t" + tvId, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 删除某频道的缓存直链（直链确认失效时调用） */
    private static void deleteCachedDirectUrl(int tvId) {
        if (tvId <= 0) return;
        try {
            miguDirectPrefs().edit().remove("u" + tvId).remove("t" + tvId).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * autoRetry 统一入口的缓存失效钩子：本次报错如果发生在「缓存回放直链」上，
     * 立即标记失效并删除缓存 —— 保证重试不会再回放同一条死链。
     */
    private void invalidateMiguCacheIfReplayFailed() {
        String replay = miguCachedReplayUrl;
        if (replay != null && TextUtils.equals(currentUrl, replay)) {
            miguCachedReplayFailed = true;
            // 只在「缓存里存的还是这条死链」时才删 —— 若后台解析链已抢先刷新成新直链，
            // 必须保留（否则刚写入的好缓存被误删，重试又得走全链路）
            boolean deleted = false;
            try {
                String stored = miguDirectPrefs().getString("u" + miguCachedReplayTvId, null);
                if (TextUtils.isEmpty(stored) || TextUtils.equals(stored, replay)) {
                    deleteCachedDirectUrl(miguCachedReplayTvId);
                    deleted = true;
                }
            } catch (Throwable ignored) {
            }
            LogBridge.w(TAG, "【咪咕】缓存直链失效 → "
                    + (deleted ? "已删除缓存" : "缓存已被后台刷新为新直链，保留")
                    + "，等待后台解析链结果 tvId=" + miguCachedReplayTvId);
        }
    }

    /**
     * 源6(咪咕) 单次解析的产物：真实可播地址 + 起播定位秒数。
     *
     * <p>定位值<b>必须随结果一起返回</b>，不能用「成员字段」传递 —— 连点换台时两次解析会并发，
     * 共享字段会串台（实测：湖南卫视被误按上一个轮播频道的 278s 定位）。
     */
    private static final class MiguResolution {
        final String url;
        final int offsetSec;

        MiguResolution(String url, int offsetSec) {
            this.url = url;
            this.offsetSec = Math.max(0, offsetSec);
        }

        boolean isEmpty() {
            return TextUtils.isEmpty(url);
        }
    }

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

    /**
     * 【2026-09-29】虎牙总线路的**展示顺序**（lineIndex 列表），必须与 {@code Channel.huyaLineLabels} 严格同序 ——
     * 「频道线路选择」列表第 N 项 ⇄ 本列表第 N 个 lineIndex ⇄ 该线路的清晰度档列表第 N 项。
     *
     * ⚠️ 不能从 {@code variantList} 反推：切换线路后 {@link #rebuildVariantListForLine(int)} 会把目标线路
     * 挪到最前，反推出来的顺序会变，导致"线路名 ↔ 打勾位置"错位。
     */
    private final List<Integer> mHuyaLineOrder = new ArrayList<>();

    /**
     * 【2026-09-29 新增】虎牙「子线路」表 = mp 接口 {@code stream.hls.multiLine} 的条目。
     *
     * <p>与总线路（{@link #mHuyaLineOrder}）的分工：总线路是「线路 × 清晰度」矩阵的上层概念，
     * 而子线路的维度是「一条 URL 一个条目」，**天然不含清晰度** —— 清晰度由
     * 「清晰度选择」按钮独立负责，两个轴不混。设置里的「频道线路选择」展示的就是这一层。
     *
     * <p>条数**随房间变化**（服务端下发几条就是几条），UI 必须容纳 N 条，不能写死。
     */
    private final Object huyaMultiLineLock = new Object();
    private final List<HuyaStreamPlayer.MultiLineOption> mHuyaMultiLines = new ArrayList<>();

    /**
     * 【2026-09-29】「线路/清晰度矩阵就绪」回调。虎牙的线路表/清晰度表要等解析完成（1.7~2.2s）才有值，
     * 而设置面板常常在那之前就被打开 —— 挂上这个回调后，矩阵一就绪就主动刷新面板那两行状态，
     * 不会停在「自适应 / 主源」这种初值上。
     */
    private Runnable mStreamOptionsReadyListener;

    /* ==================== 【2026-10-07 新增】咪咕「线路 × 清晰度」轴 ==================== */

    /**
     * 咪咕频道的「线路 × 清晰度」轴。
     *
     * <p>解析「① 服务端下发」拿到完整 {@code sourceLabels} 后构建（见
     * {@link com.tv.live.migu.MiguStreamAxis}）；非咪咕频道恒为 {@link
     * com.tv.live.migu.MiguStreamAxis#empty()}。设置里的「频道线路选择 / 清晰度选择」
     * 对咪咕频道即读这张表。
     */
    private volatile com.tv.live.migu.MiguStreamAxis mMiguAxis =
            com.tv.live.migu.MiguStreamAxis.empty();
    /** 咪咕当前生效线路名（空 = 未选定，取轴首选） */
    private volatile String mMiguCurLine = "";
    /** 咪咕当前生效清晰度名（空 = 未选定） */
    private volatile String mMiguCurQuality = "";

    /**
     * 咪咕「直接起播」标志：切换咪咕线路 / 清晰度时置位。
     *
     * <p>为什么需要它：{@link #doPlay} 对「非真实流地址」会用频道线路表
     * （{@code channel_line_index_<key>} + {@code backupUrls}）<b>覆盖</b>调用方传入的地址。
     * 咪咕要播的源串（{@code miguhd://…}）恰好不是可识别流地址，会被覆盖掉、导致切线路/切清晰度
     * 失效。置位后本次起播直接采用传入地址。跨换台由 {@link #playUrl} 复位。
     */
    private volatile boolean mMiguDirectPlay = false;

    /* ==================== 【2026-10-07 新增】畅享（源3）「线路 × 清晰度」轴 ====================
     *  完全参照咪咕轴的模式：源3 与咪咕同出一服务端（get_channel_source 的 sourceLabels），
     *  咪咕把整表拆成线路/清晰度两轴，源3 的 http 子集同样拆（见 ChanxiangStreamAxis）。
     *  与咪咕的差异：源3 起播走 M3U 直链、不需要额外请求 → 轴改为「面板打开时惰性构建」
     *  （单请求 + 10min TTL 缓存），不在起播链路加任何请求。 */

    /** 畅享频道的「线路 × 清晰度」轴；非畅享频道 / 未构建时恒为 empty() */
    private volatile com.tv.live.chanxiang.ChanxiangStreamAxis mChanxiangAxis =
            com.tv.live.chanxiang.ChanxiangStreamAxis.empty();
    /** 畅享当前生效线路名（空 = 未选定） */
    private volatile String mChanxiangCurLine = "";
    /** 畅享当前生效清晰度名（空 = 未选定） */
    private volatile String mChanxiangCurQuality = "";
    /** 轴缓存归属的 tvId 与构建时刻（换台失效，TTL 内复用，避免重复请求） */
    private volatile int mChanxiangAxisTvId = 0;
    private volatile long mChanxiangAxisAtMs = 0L;
    private static final long CHANXIANG_AXIS_TTL_MS = 10L * 60L * 1000L;

    /**
     * 【2026-09-19 404 升级】连续 404 计数：达到阈值后不再死磕同一线路重新解析，
     * 而是切换到探活通过的其它线路（对照原始版 PlayerActivity.onBufferStart 高频缓冲切源）。
     * 阈值=2：第一次 404 重解析签名；若新签名仍 404（线路/CDN 节点持续失效），切线路。
     */
    private static final int HUYA_404_SWITCH_LINE_THRESHOLD = 2;
    private int mHuya404Streak = 0;
    /** 【2026-09-29 自研解析】本房间自研 HTTP 解析失败过则本次直接判失败，避免循环重试 */
    private int mHttpParseFailedRoomId = 0;

    /** 最近一次探活通过的存活流列表（供 404 切线路时选目标），播放就绪后清空 */
    private java.util.List<HuyaSDKParser.HuyaStreamInfo> mLastAliveStreams = null;

    /**
     * 【2026-09-27 换台卡顿专项·重试去重】最近一次虎牙签名失效重试的发起时刻与房间号。
     * 403 触发的 retryHuyaImmediately 会先清 isRetrying 再重解析，这个窗口内
     * stuckCheckRunnable 看到 STATE_IDLE 会再排一次 autoRetry("播放卡住")，
     * 导致同一房间被重解析两次（实测 16:32:34 与 16:32:35 各一次）。
     */
    private long mLastHuyaSigRetryAt = 0L;
    private int mLastHuyaSigRetryRoomId = 0;
    private static final long HUYA_SIG_RETRY_DEDUP_MS = 3000L;

    // 【2026-09-27 换台卡顿专项】起播后主动巡检当前播放地址
    private static final long PROBE_WATCH_INTERVAL_MS = 1000L;
    private static final int PROBE_WATCH_FAIL_THRESHOLD = 2;
    private static final int PROBE_WATCH_MAX_ROUNDS = 20;
    /** 巡检代数：每次起播自增，旧线程据此自动退出（避免同房间换地址时线程叠加） */
    private volatile long mProbeWatchGen = 0L;

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

    /** 播放器就绪门闩：initPlayer 在后台线程执行，主线程需要 player 实例时可短暂等待 */
    private final java.util.concurrent.CountDownLatch playerReadyLatch = new java.util.concurrent.CountDownLatch(1);
    private volatile boolean playerInitStarted = false;

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
        mHandler = new Handler(Looper.getMainLooper());

        String savedMode = sp.getString("decoder_mode", "auto");
        switch (savedMode) {
            case "hard":   mDecoderMode = DECODER_MODE_HARD;   break;
            case "soft":   mDecoderMode = DECODER_MODE_SOFT;   break;
            default:       mDecoderMode = DECODER_MODE_AUTO;   break;
        }
        LogBridge.i(TAG, "📺 启动时读取解码器设置: decoder_mode=" + savedMode + " -> mDecoderMode=" + mDecoderMode);

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

                        if (isHuyaSigRetryJustFired()) {
                            // 【换台卡顿专项】403 已触发立即重解析，这里再排一次会让同一房间重解析两遍
                            LogBridge.d(TAG, "【重试去重】虎牙签名重试刚发起，跳过卡住检测的重复重试");
                            mHandler.postDelayed(this, 2000);
                            return;
                        }
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
                            if (isHuyaSigRetryJustFired()) {
                                LogBridge.d(TAG, "【重试去重】虎牙签名重试刚发起，跳过长时间缓冲的重复重试");
                                // 重置计时让位给已排队的重试，避免下一轮立刻又触发
                                lastPositionUpdateTime = now;
                            } else {
                                LogBridge.w(TAG, "检测到长时间缓冲(" + STUCK_TIMEOUT + "ms)，自动重试...");
                                autoRetry("播放卡住");
                                return;
                            }
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
                awaitPlayerReady(5000);
                if (player != null) {
                    player.setMediaSource(source, true);
                    player.prepare();
                    if (seekPosition > 0) player.seekTo(seekPosition);
                    player.play();
                }
            }
        }, variantManager);

        // 冷启动优化：ExoPlayer 构建移出主线程（详见 initPlayerAsync 注释）
        initPlayerAsync();

        // 方案 G ̽针：MediaCodec 真实预创建（默认关闭，改 CodecWarmupProbe.ENABLED=true 开启）
        // 详见 开发记录_2026-09-10.md 13.4：伪预热(getDecoderInfos)只枚举不创建，真预热必须 createByCodecName+configure+start
        com.tv.live.util.CodecWarmupProbe.runIfEnabled(context);
    }

    private void dLog(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            LogBridge.d(TAG, msg);
            com.tv.live.util.LogBridge.LogCollector.getInstance().addLog(TAG, msg);
        }
    }

    private void logPlayback(String msg) {
        LogBridge.i(TAG, "[PLAY] " + msg);
        com.tv.live.util.LogBridge.LogCollector.getInstance().playback(TAG, msg);
    }

    private void logError(String msg) {
        LogBridge.e(TAG, msg);

        if (shouldReportPlaybackError(msg)) {
            com.tv.live.util.LogBridge.LogCollector.getInstance().error(TAG, msg);
        } else {

            com.tv.live.util.LogBridge.LogCollector.getInstance().warn(TAG, "[播放过滤] " + msg);
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
        com.tv.live.util.LogBridge.LogCollector.getInstance().warn(TAG, msg);
    }

    private void logNetwork(String msg) {
        if (BuildConfig.IS_DEBUG) {
            LogBridge.i(TAG, "[NET] " + msg);
            com.tv.live.util.LogBridge.LogCollector.getInstance().network(TAG, msg);
        }
    }

    /**
     * 冷启动优化：ExoPlayer 构建移出主线程。
     * 原实现里构造器同步调 initPlayer()（含 ExoPlayer.build + MediaCodecUtil 编解码器扫描 +
     * media3 大量类加载，低端盒子 100~300ms），导致主线程 getInstance 与预热线程在类锁上竞争，
     * 拖慢首帧。现在：
     *   - 构造器立即返回（只做轻量初始化），首帧不再被播放器构建阻塞；
     *   - ExoPlayer 在无 Looper 的后台线程 build 时会自动绑定主 Looper，之后主线程访问行为与原来完全一致；
     *   - 就绪后通过 CountDownLatch + 主线程回调补绑视图（ensurePlayerBoundToView）。
     */
    private void initPlayerAsync() {
        if (player != null) return;
        synchronized (TVPlayerManager.class) {
            if (playerInitStarted || player != null) return;
            playerInitStarted = true;
        }
        Thread t = new Thread(() -> {
            try {
                long t0 = android.os.SystemClock.elapsedRealtime();
                initPlayer();
                LogBridge.i(TAG, "🎬 播放器后台构建完成: "
                        + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
            } catch (Throwable e) {
                LogBridge.e(TAG, "后台构建播放器失败: " + e.getMessage(), e);
            } finally {
                playerReadyLatch.countDown();
                // 播放器就绪后主线程补绑视图（attachPlayerView 可能先于就绪被调用）
                mHandler.post(() -> {
                    try {
                        ensurePlayerBoundToView();
                    } catch (Exception e) {
                        LogBridge.e(TAG, "补绑播放视图异常", e);
                    }
                });
            }
        }, "TVPlayer-Init");
        t.setDaemon(true);
        t.start();
    }

    /** 仅在真正需要 player 实例（起播）时调用；预热线程通常早已完成，等待趋近 0 */
    private void awaitPlayerReady(long timeoutMs) {
        if (player != null) return;
        try {
            playerReadyLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
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
                dLog("【解码器】软解优先模式");
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

        // 🎯 缓冲策略（直播流折中）：
        //   2026-09-10 版（5000/12000/500/2500）实测：首帧约 0.53s 出现，
        //   但仅存活约 140ms 后 500ms 缓冲耗尽，立刻二次缓冲 0.62-0.68s——
        //   主观感受就是"切台后画面卡顿一下"（2026-09-15 局域网9527实测3轮一致）。
        //   2026-09-15 调整：bufferForPlayback 500->1200（实测下载约4倍实时，
        //   首帧只晚约0.15-0.2s，但首帧时缓冲更厚，不再立即耗尽）；
        //   afterRebuffer 2500->2000（与起播门槛更接近，万一再卡也恢复更快）。
        //   即 minBuffer 5000 / maxBuffer 12000 / 起播 1200 / 再缓冲 2000。
        // 【2026-09-19 回退】曾为了配合 master m3u8 ABR 把水位抬到 15s/30s/2.5s/3.0s
        //   （外加 LiveConfiguration targetOffset=10s 制造约 10s 直播延迟）。
        //   现已回退到「固定画质 + URL 自带 &ratio=<码率>」的原版方案，
        //   播放器不再做 ABR 选档，厚缓冲只会白白拉长起播与延迟，故恢复旧值。
        // 【2026-09-19 虎牙轮播卡顿专项】对照原始 Fungobox（xiufu gongcheng）实现：
        //   原版 ExoMediaPlayer 用默认 LoadControl（min 50s/max 50s/2.5s/5s）+ 起播前
        //   SimpleLiveChecker 并行 HEAD 探活过滤坏线路。AH-main 之前把缓冲砍到
        //   5s/12s/1.2s/2s，虎牙公网 CDN TTFB 179ms/尖刺数秒的串行分片很快耗尽水位
        //   → 周期性 STATE_BUFFERING。本次：
        //   ① 起播门槛 bufferForPlayback 保持 1200（首帧速度不变，2026-09-15 已验证）；
        //   ② minBuffer 5000→12000、maxBuffer 12000→60000、afterRebuffer 2000→5000，
        //      让播放中缓冲水位厚起来，扛住 TTFB 空窗与尖刺（不配 ABR，无 live offset
        //      副作用；固定画质下厚缓冲仅增加 CDN 波动容忍度，不拉长首帧——首帧由
        //      bufferForPlayback 单独控制）。
        DefaultLoadControl optimizedLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    12000,   // minBufferMs: 播放中水位（5000→12000，虎牙串行分片空窗下更抗耗）
                    60000,   // maxBufferMs（12000→60000，给足重建空间，参考原版默认 50s）
                    1200,    // bufferForPlaybackMs: 起播门槛（保持 1200，首帧不变）
                    2000     // bufferForPlaybackAfterRebufferMs（5000→2000，卡后快速恢复，避免 5s+ 黑帧）
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
                    + " -> 选中模式: " + (preferSoftware ? "软解优先" : "硬解优先"));
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

            // 【源5 爱其意】部分直播源把 m3u8 写在查询串里（形如 …&zy.m3u8），
            //   路径无扩展名 → 会被误判为普通流 → ExoPlayer 抛 UnrecognizedInputFormatException。
            //   只要整条 URL 出现 m3u8/m3u 一律按 HLS 处理。
            String full = url.toLowerCase(Locale.ROOT);
            if (full.contains(".m3u8") || full.contains(".m3u")) return true;
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

    /**
     * 🎯【2026-09-08 卡顿诊断】把播放错误的完整 cause 链 + 当前 playUrl/频道
     * 拼成一段诊断字符串，便于打进 9527 日志精准定位
     * （是 403/404/超时/连接拒绝，还是 CDN 垮了）。
     * 用反射提取 HttpDataSourceException 的 responseCode / uri，避免依赖具体 API（编译安全）。
     */
    private String buildErrorDiagnostic(PlaybackException error) {
        StringBuilder sb = new StringBuilder();
        sb.append("播放异常: ").append(error.getClass().getSimpleName())
          .append(": ").append(error.getMessage())
          .append(" | errorCode=").append(error.errorCode);
        sb.append(" | 频道=").append(TextUtils.isEmpty(currentChannelName) ? "(空)" : currentChannelName);
        sb.append(" | playUrl=").append(TextUtils.isEmpty(currentUrl) ? "(空)" : currentUrl);

        sb.append(" | cause链=[");
        Throwable t = error.getCause();
        int depth = 0;
        while (t != null && depth < 12) {
            sb.append(" {").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            try {
                java.lang.reflect.Field rf = t.getClass().getDeclaredField("responseCode");
                rf.setAccessible(true);
                Object rc = rf.get(t);
                if (rc != null) sb.append(" responseCode=").append(rc);
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field df = t.getClass().getDeclaredField("dataSpec");
                df.setAccessible(true);
                Object ds = df.get(t);
                if (ds != null) {
                    java.lang.reflect.Field uf = ds.getClass().getDeclaredField("uri");
                    uf.setAccessible(true);
                    Object uri = uf.get(ds);
                    if (uri != null) sb.append(" uri=").append(uri);
                }
            } catch (Throwable ignored) {}
            sb.append("}");
            t = t.getCause();
            depth++;
        }
        sb.append(" ]");
        return sb.toString();
    }

    private void initPlayerListener() {
        if (playerListener != null) return;
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String errDetail = buildErrorDiagnostic(error);
                LogBridge.e(TAG, errDetail);
                com.tv.live.util.LogBridge.LogCollector.getInstance().error(TAG, "[ERR_DIAG] " + errDetail);
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
                    String surfDetail = buildErrorDiagnostic(error);
                    logWarn("检测到 Surface 失效（MediaCodec/Surface 相关），触发重建流程");
                    LogBridge.e(TAG, surfDetail);
                    com.tv.live.util.LogBridge.LogCollector.getInstance().error(TAG, "[ERR_DIAG] " + surfDetail);
                    handleSurfaceLost();
                    if (listener != null) {
                        listener.onPlayError("Surface 失效，正在重启 | playUrl=" + (currentUrl == null ? "(空)" : currentUrl));
                    }
                    return;
                }

                if (isRedirectError) {
                    String redDetail = buildErrorDiagnostic(error);
                    logError("重定向错启: " + error.getMessage());
                    LogBridge.e(TAG, redDetail);
                    com.tv.live.util.LogBridge.LogCollector.getInstance().error(TAG, "[ERR_DIAG] " + redDetail);
                    if (listener != null) {
                        listener.onPlayError(error.getMessage() + " | playUrl=" + (currentUrl == null ? "(空)" : currentUrl));
                    }
                    return;
                }

                boolean isNetworkError = isNetworkError(error);
                if (isNetworkError && isNetworkUnavailable()) {
                    logWarn("网络不可用，暂不切台，等待网络恢复后重试...");
                    autoRetry("网络不可用", error);
                } else if (isHuyaSignatureExpiredError(errDetail)) {
                    // 【2026-09-19 404 专项】【2026-09-24 403 专项】
                    // 虎牙 m3u8 地址签名时效失效（主播切流/防盗链过期）→ 旧地址 404 或 403。
                    // 处理：① 清掉该房间流缓存（强制重新实时解析拿新签名）；
                    //   ② 立即重试（不等 3s 退避，404/403 是确定性失效不是网络抖动）。
                    logWarn("【虎牙签名失效】m3u8 地址失效（404/403，签名过期或主播切流），清缓存并立即重解析签名");
                    dropHuyaFinalUrl(mLastHuyaFinalUrlKey);
                    if (mHuyaRoomId > 0) {
                        HuyaSDKParser.invalidateCachedStreams(mHuyaRoomId);
                    }
                    retryHuyaImmediately(error);
                } else if (isHuyaCdnTempError(errDetail)) {
                    // 【2026-09-24 5xx 专项】虎牙 CDN 临时错误（503 WAF限流/504 网关超时）。
                    // 签名仍有效，无需清缓存；快速重试当前线路（不等 3s 退避），
                    // 持续 5xx 达上限则切换备用线路。
                    logWarn("【虎牙 CDN 临时错误(503/504)】快速重试当前线路（不清缓存，签名仍有效）");
                    retryHuya5xxImmediately(error);
                } else {
                    autoRetry(isNetworkError ? "网络异常" : "播放异常", error);
                }

                if (listener != null) {
                    listener.onPlayError(errDetail);
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    logPlayback("播放就绪 (STATE_READY)");
                    updateWakeLock(true);

                    // 【P0-B】首帧已出，此刻再初始化虎牙 SDK，避免与首帧抢 CPU/IO
                    try {
                        MyApplication app = MyApplication.getInstance();
                        if (app != null) {
                            app.onFirstFrameShown();
                        }
                    } catch (Throwable ignored) {
                    }

                    HuyaSDKParser.notifyPlaybackSettled();
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    isSwitching = false;
                    if (listener != null) listener.onPlayReady();
                    // 【2026-09-27 换台卡顿专项】播放就绪 = 本轮已恢复，必须撤掉排队中的重试，
                    //   否则那个 autoRetry 会在几秒后再次 playHuyaStream → 重新 prepare，
                    //   画面刚出来又被黑一下。实测 15:27:20 排了 4000ms 延迟重试 → 15:27:24
                    //   触发，而此时画面早已恢复（表现为「短暂缓冲恢复 487ms」）。
                    cancelRetry();
                    retryCount = 0;
                    // 【源5 兜底】播放恢复 = 新一轮稳定，清零连续重拉计数与冷却（2 分钟短时效下需可反复重拉）
                    renyiboRefreshCount = 0;
                    lastRenyiboRefreshAtMs = 0;
                    // 【2026-09-19 404 升级】播放就绪 = 当前线路已稳定，清零连续404计数
                    mHuya404Streak = 0;
                    startStuckDetection();
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

    /**
     * 畅享源失效兜底：播放彻底失败、备线也切换失败时，若当前频道是畅享源
     * （channelId 即 tvId），异步实时重新拉取该频道最新签名 m3u8 并重播，
     * 避免签名过期（约48h）导致整组频道黑屏。每频道最多触发 MAX_CHANXIANG_REFRESH 次，防循环。
     */
    private boolean tryRefreshChanxiangSource() {
        Channel ch = currentChannel;
        if (ch == null) return false;
        String id = ch.getChannelId();
        if (id == null || !id.matches("\\d+")) return false;
        if (chanxiangRefreshCount >= MAX_CHANXIANG_REFRESH) return false;
        chanxiangRefreshCount++;
        final int tvId = Integer.parseInt(id);
        final String chName = currentChannelName;
        try {
            new Thread(() -> {
                String newUrl = null;
                try {
                    newUrl = com.tv.live.chanxiang.ChanxiangSourceLoader.getLatestMainUrl(tvId);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "【畅享重拉】异帧: " + t.getMessage());
                }
                final String url = newUrl;
                mHandler.post(() -> {
                    if (url != null && !url.isEmpty()) {
                        LogBridge.i(TAG, "【畅享重拉】已拿到最新签名源，重播 " + chName);
                        playUrlInternal(url);
                    } else {
                        LogBridge.w(TAG, "【畅享重拉】未取到新源，转自动切台");
                        if (sourceFailedListener != null) sourceFailedListener.onSourceFailed();
                    }
                });
            }, "Chanxiang-Refresh").start();
            return true;
        } catch (Throwable t) {
            LogBridge.w(TAG, "【畅享重拉】发起失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 【2026-10-07 新增】源5（爱其意）跳转地址解析后起播 —— 参照咪咕 {@link #resolveMiguAndPlay} 模式。
     *
     * <p>列表生成期不再为 97 个频道预解析 302（原实现实测拖到 30.5s），改在起播这一刻
     * 后台解析该频道的落地地址（单频道 1 次 GET，带 5 分钟缓存），完成后回主线程播放。
     * 解析失败走 {@link #tryRefreshRenyiboSource} 实时重拉兜底。
     */
    private void resolveRenyiboAndPlay(String rawUrl, long initialSeekPosition) {
        // 同一地址解析中 → 忽略重复请求（起播 + 重试可能同时触发）
        synchronized (this) {
            if (rawUrl.equals(renyiboResolvingUrl)) {
                LogBridge.d(TAG, "【爱其意】解析进行中，忽略重复请求");
                return;
            }
            renyiboResolvingUrl = rawUrl;
        }
        LogBridge.i(TAG, "【爱其意】起播解析待解析地址 → " + rawUrl);
        new Thread(() -> {
            String finalUrl = null;
            try {
                if (com.tv.live.renyibo.RenYiBoSourceLoader.isRenyiboSchemeUrl(rawUrl)) {
                    // 协议串 → fresh live.php + 302（php 地址里 ZY- token 存活期极短，
                    // 不缓存 php URL 本身，只缓存解析结果）
                    finalUrl = com.tv.live.renyibo.RenYiBoSourceLoader.resolveByApiId(
                            com.tv.live.renyibo.RenYiBoSourceLoader.apiIdOfScheme(rawUrl));
                } else {
                    // 旧缓存遗留的 php 跳转地址 → 直接尝试 302 解析
                    finalUrl = com.tv.live.renyibo.RenYiBoSourceLoader.resolveUrl(rawUrl);
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "【爱其意】跳转解析异常: " + t.getMessage());
            }
            final String resolved = finalUrl;
            mHandler.post(() -> {
                renyiboResolvingUrl = "";
                // 频道已被切换（currentUrl 不再是这条待解析地址）→ 丢弃本次结果
                if (!TextUtils.equals(currentUrl, rawUrl)) {
                    LogBridge.d(TAG, "【爱其意】解析完成但频道已切换，丢弃");
                    return;
                }
                if (TextUtils.isEmpty(resolved) || resolved.equals(rawUrl)) {
                    // 解析失败 → 实时重拉兜底：fetchUrlById 会重新取 live.php 签名并解析
                    // 落地；再失败才转自动切台
                    LogBridge.w(TAG, "【爱其意】跳转解析失败，转实时重拉兜底");
                    if (!tryRefreshRenyiboSource()) {
                        autoRetry("源5跳转解析失败");
                    }
                    return;
                }
                LogBridge.i(TAG, "【爱其意】落地地址就绪 → " + resolved);
                doPlay(resolved, initialSeekPosition);
            });
        }, "Renyibo-Resolve").start();
    }

    /** 源5（爱其意）当前正在解析的待解析地址（去重用；空 = 无解析进行中） */
    private volatile String renyiboResolvingUrl = "";

    /**
     * 源5(爱其意)失效兜底：直链由 live.php 实时下发、带短时签名，缓存久了会失效。
     * 播放彻底失败且备线用尽时，若当前频道是源5（channelId 形如 {@code renyibo:<apiId>}），
     * 异步实时重拉该频道最新直链并重播。每频道最多触发 {@link #MAX_RENYIBO_REFRESH} 次，防循环。
     */
    private boolean tryRefreshRenyiboSource() {
        Channel ch = currentChannel;
        if (ch == null) return false;
        String cid = ch.getChannelId();
        if (cid == null || !cid.startsWith(com.tv.live.renyibo.RenYiBoSourceLoader.TVG_PREFIX)) return false;
        if (renyiboRefreshCount >= MAX_RENYIBO_REFRESH) return false;
        long now = System.currentTimeMillis();
        if (now - lastRenyiboRefreshAtMs < RENYIBO_REFRESH_MIN_INTERVAL_MS) return false;
        renyiboRefreshCount++;
        lastRenyiboRefreshAtMs = now;
        final String apiId = cid.substring(com.tv.live.renyibo.RenYiBoSourceLoader.TVG_PREFIX.length());
        final String chName = currentChannelName;
        try {
            new Thread(() -> {
                String newUrl = null;
                try {
                    newUrl = com.tv.live.renyibo.RenYiBoSourceLoader.fetchUrlById(apiId);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "【爱其意重拉】异常: " + t.getMessage());
                }
                final String url = newUrl;
                mHandler.post(() -> {
                    if (url != null && !url.isEmpty()) {
                        // 同步更新频道的当前线路地址，保证后续 doPlay 用的是新鲜直链
                        if (currentChannel != null) {
                            currentChannel.setMainPlayUrl(url);
                            currentChannel.setCurrentLineIndex(0);
                        }
                        LogBridge.i(TAG, "【爱其意重拉】已拿到最新直链，重播 " + chName);
                        playUrlInternal(url);
                    } else {
                        LogBridge.w(TAG, "【爱其意重拉】未取到新源，转自动切台");
                        if (sourceFailedListener != null) sourceFailedListener.onSourceFailed();
                    }
                });
            }, "Renyibo-Refresh").start();
            return true;
        } catch (Throwable t) {
            LogBridge.w(TAG, "【爱其意重拉】发起失败: " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内置源6（咪咕）：惰性解析 + 失效重解析
    // ------------------------------------------------------------------

    /**
     * 把 M3U 里的待解析地址换成真实可播的 m3u8 后再起播。
     *
     * <p>支持两种待解析地址（对应原版畅享TV 链路）：
     * <ul>
     *   <li>{@code nowtv://<tvId>} —— 第 ① 步：先向 nowtv-new.xiaoyouzb.cn 换取真实源串；</li>
     *   <li>{@code miguhd://<pID>} —— 直接进解析。</li>
     * </ul>
     *
     * <p>为什么不在加载频道表时预解析：咪咕直链带 {@code SecurityKey}/{@code puData}
     * 短时签名，预解析会大片过期；且会形成「每频道一次」的批量请求。这里改成
     * <b>起播那一刻才解析</b>（与畅享TV 运行时行为一致）。
     *
     * @param miguUrl             待解析地址
     * @param initialSeekPosition 起播定位（通常 0）
     */
    private void resolveMiguAndPlay(String miguUrl, long initialSeekPosition) {
        final String request = miguUrl;
        final boolean viaServer = com.tv.live.migu.MiguSourceLoader.isNowTvUrl(request);
        final String pid = com.tv.live.migu.MiguUrlResolver.pidOf(request);
        // 合法性：任一即可 —— 服务端下发 / migu* 协议(有 pid) / JS 路由表可解析
        //           / tvnow:// 轮播 / lunbolbk:// 回看 / lunboParser_ 回看串
        //           / 网页类源(http 且不像流，如 http://www.le.com → letv_vod.js)
        final boolean routable = com.tv.live.migu.MiguUrlResolver.isJsRoutableUrl(request)
                || com.tv.live.migu.source.CarouselApi.isCarouselUrl(request)
                || com.tv.live.migu.source.CarouselApi.isLookBackUrl(request)
                || com.tv.live.migu.source.CarouselApi.isLunboParserUrl(request)
                || com.tv.live.migu.source.CarouselApi.isYunTuLookBackUrl(request)
                || com.tv.live.migu.source.ZgyjVodApi.isZgyjVodUrl(request)
                || com.tv.live.migu.MiguUrlResolver.isWebPageUrl(request);

        if (!viaServer && pid == null && !routable) {
            LogBridge.w(TAG, "【咪咕】非法地址：" + request);
            autoRetry("咪咕地址非法");
            return;
        }
        // 同一地址解析中 → 忽略重复请求（起播 + 重试可能同时触发）
        synchronized (this) {
            if (request.equals(miguResolvingPid)) {
                LogBridge.d(TAG, "【咪咕】解析进行中，忽略重复请求 " + request);
                return;
            }
            miguResolvingPid = request;
        }

        final String chName = currentChannelName;
        LogBridge.i(TAG, "【咪咕】开始解析 " + request + " 频道=" + chName);

        // ★【2026-10-08】直链缓存回放：频道主地址（nowtv://<tvId>）若命中上次解析成功的
        //   直链 → 先直接起播（与直链源同速，省掉 取源+JS引擎+探测 ≈ 2.6s），后台解析链
        //   照常执行刷新缓存；缓存直链若失效（autoRetry 钩子置位），解析完成时无缝切换。
        //   仅限直播主地址且无定位需求（initialSeekPosition==0）；轮播/回看结果带定位，
        //   不缓存也不回放。回放本身零额外网络请求。
        final int reqTvId;
        if (viaServer) {
            int t = -1;
            try {
                t = Integer.parseInt(request.substring(
                        com.tv.live.migu.MiguSourceLoader.SCHEME_NOWTV.length()).trim());
            } catch (Throwable ignored) {
            }
            reqTvId = t;
        } else {
            reqTvId = -1;
        }
        if (viaServer && reqTvId > 0 && initialSeekPosition == 0) {
            String cached = loadCachedDirectUrl(reqTvId);
            if (!TextUtils.isEmpty(cached)
                    && !com.tv.live.migu.source.CarouselApi.isKnownDeadHost(cached)) {
                LogBridge.i(TAG, "【咪咕】命中直链缓存 → 先起播，后台并行刷新解析链 tvId=" + reqTvId);
                miguCachedReplayUrl = cached;
                miguCachedReplayTvId = reqTvId;
                miguCachedReplayFailed = false;
                try {
                    doPlay(cached, 0);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "【咪咕】缓存直链起播失败: " + t.getMessage());
                    miguCachedReplayUrl = null;
                    miguCachedReplayTvId = 0;
                }
            }
        }

        try {
            new Thread(() -> {
                Resolved real = null;
                try {
                    real = resolveMiguChain(request, chName);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "【咪咕】解析异常: " + t.getMessage());
                } finally {
                    synchronized (TVPlayerManager.this) {
                        if (request.equals(miguResolvingPid)) miguResolvingPid = null;
                    }
                }
                final Resolved r = real;
                mHandler.post(() -> {
                    // ★【2026-10-08】缓存回放直链仍在正常播放 → 不打断用户画面，
                    //   仅用本次解析结果刷新缓存（本次解析是「刷新」性质，非「救急」）。
                    //   健康判定 = 播放器已 STATE_READY（回放后 ~1s 即 READY，解析需 ~3s；
                    //   若解析完成时仍未 READY，说明缓存直链有问题 —— 如死域名 DNS 迟迟
                    //   不报错 —— 此时必须切换到本次解析结果，不能傻等播放器报错）。
                    if (reqTvId > 0 && !miguCachedReplayFailed
                            && miguCachedReplayTvId == reqTvId
                            && TextUtils.equals(currentUrl, miguCachedReplayUrl)) {
                        boolean healthy = player != null
                                && player.getPlaybackState()
                                        == androidx.media3.common.Player.STATE_READY;
                        if (r != null && !TextUtils.isEmpty(r.url)) {
                            if (healthy) {
                                if (r.seekSec == 0) {
                                    saveCachedDirectUrl(reqTvId, r.url);
                                    LogBridge.i(TAG, "【咪咕】缓存直链播放正常 → 保持画面，已刷新缓存 tvId="
                                            + reqTvId);
                                }
                                notifyStreamOptionsReady();
                                return;
                            }
                            LogBridge.w(TAG, "【咪咕】缓存直链未就绪 → 切换后台解析结果（不等播放器报错）");
                            // 落到下方正常流程（含黑洞预判 + doPlay），无缝接管
                        } else if (healthy) {
                            LogBridge.i(TAG, "【咪咕】本次解析未取到直链，但缓存直链播放正常 → 保持");
                            return;
                        }
                        // 缓存不健康且无新结果 → 落回原失败/重试逻辑
                    }
                    // 防御：结果若仍是 migu/nowtv 协议（异常数据）直接判失败，避免解析自循环
                    if (r != null && !TextUtils.isEmpty(r.url)
                            && !com.tv.live.migu.MiguUrlResolver.isMiguUrl(r.url)
                            && !com.tv.live.migu.MiguSourceLoader.isNowTvUrl(r.url)) {
                        // ★【2026-10-07】黑洞预判 + 快速失败：解析结果落在已知黑洞主机
                        //   （hlsbkmgspvod.miguvideo.com —— 咪咕点播 VOD 的 CNAME 末端
                        //    heidong.cdn.cmvideo.cn，DNS 解析到 1.1.1.2）时直接判「版权受限」：
                        //   <b>不 doPlay、不 autoRetry</b>。否则播放器会干等 8~45 秒才报错黑屏，
                        //   期间还会触发重试，对本就不可用的地址重复请求。
                        if (com.tv.live.migu.source.CarouselApi.isKnownDeadHost(r.url)) {
                            LogBridge.w(TAG, "【咪咕】命中已知黑洞主机 → 快速失败（版权受限）："
                                    + r.url);
                            notifyMiguCopyrightLimited();
                            return;
                        }
                        // 轮播频道：按「已播时长」定位（复刻原版 UrlParser$1 的 offSet 回调）
                        final long seek = Math.max(initialSeekPosition, r.seekSec * 1000L);
                        LogBridge.i(TAG, "【咪咕】已拿到直链，起播 " + chName
                                + (seek > 0 ? "，定位 " + (seek / 1000) + "s" : ""));
                        // 【2026-10-07】轴已就绪 → 通知设置面板刷新「线路 / 清晰度」两行
                        //   （面板可能在本频道解析完成前就被打开，否则会停在初值上）。
                        notifyStreamOptionsReady();
                        doPlay(r.url, seek);
                        // ★【2026-10-08】解析成功且无定位（直播主链路）→ 持久化直链，
                        //   供下次冷启动/换台回放（轮播/回看结果带定位，不缓存）
                        if (reqTvId > 0 && r.seekSec == 0 && seek == 0) {
                            saveCachedDirectUrl(reqTvId, r.url);
                        }
                    } else {
                        LogBridge.w(TAG, "【咪咕】未取到有效直链，转重试/切台");
                        autoRetry("咪咕取流失败(" + request + ")");
                    }
                });
            }, "Migu-Resolve").start();
        } catch (Throwable t) {
            synchronized (this) {
                if (request.equals(miguResolvingPid)) miguResolvingPid = null;
            }
            LogBridge.w(TAG, "【咪咕】发起解析失败: " + t.getMessage());
            autoRetry("咪咕解析发起失败");
        }
    }

    /**
     * 完整解析链路（后台线程）—— 对应原版畅享TV 的 ① → ②③④：
     * <pre>
     *   nowtv://&lt;tvId&gt;
     *     → ① NowTvSourceApi.getFirstPlayableSource()  服务端下发 → migu* / tvnow:// / http直链 / ysp*(末位)
     *     │  ★ 若下发源非 migu*：按频道名 MiguSourceLoader.lookupPidByName() 补 miguhd://&lt;pID&gt;
     *     │     优先解析；失败原样回退下发源（「全部优先咪咕线路」）
     *     ├ migu* / ysp* → ②③④ UrlSourceParser.parseSync()   JS 引擎（路由表 + 插件）→ 真实 m3u8
     *     │                ↳ JS 失败 → MiguUrlResolver.resolvePid() 纯 Java 降级（仅 migu* 有 pid）
     *     ├ tvnow://     → CarouselApi.resolve()         轮播接口 → 当前节目地址 + 起播定位
     *     ├ zgyj_vod     → ZgyjVodApi.resolve()          GITV 银河 VOD 鉴权 → playUrl（央视大剧/剧场组）
     *     └ http(s)://   → 直接起播（HLS 才做 302 落地探测）
     * </pre>
     * 没有任何一步会重试或并发 —— 失败一律交给上层 autoRetry / 切台。
     *
     * @return 真实可播地址；全链路失败返回 null
     */
    private Resolved resolveMiguChain(String input, String channelName) {
        return resolveMiguChain(input, 0, channelName);
    }

    private Resolved resolveMiguChain(String input, int depth, String channelName) {
        if (depth > 2 || TextUtils.isEmpty(input)) {
            LogBridge.w(TAG, "【咪咕】解析层级过深或地址为空：" + input);
            return null;
        }
        String source = input;
        // 轮播频道的「已播定位秒数」——用局部变量随返回值带回，避免并发解析时共享字段串台
        int carouselSeekSec = 0;
        // 【2026-10-07】回看链（A）与网页兜底（B）的上下文 —— 只在 nowtv:// 步骤采集
        boolean yuntuLookBack = false;   // 服务端声明了 backSourceLabels=yuntulunbo
        String webPageSource = "";       // sourceLabels 里的「网页类」源（如 http://www.le.com）
        int nowTvId = 0;                 // 本频道的 tvId（回看链接口要用）
        // ★【2026-10-07】backSourceLabels 里「非云图关键字」的**具体回看源**
        //   （典型：lunbolbk://<pid> —— 原版 UrlParser 直解，走回看链）。
        //   原实现只认 yuntulunbo 关键字，把这些具体源整条丢掉 → 剧场组丢唯一可播路径。
        java.util.List<String> backCand = new java.util.ArrayList<>();
        // ---- 第 ① 步：服务端下发（仅 nowtv:// 需要） ----
        if (com.tv.live.migu.MiguSourceLoader.isNowTvUrl(input)) {
            String tvIdStr = input.substring(
                    com.tv.live.migu.MiguSourceLoader.SCHEME_NOWTV.length()).trim();
            int tvId;
            try {
                tvId = Integer.parseInt(tvIdStr);
            } catch (Throwable t) {
                LogBridge.w(TAG, "【咪咕】非法 tvId：" + tvIdStr);
                return null;
            }
            nowTvId = tvId;
            // ★★【2026-10-07】只请求一次 get_channel_source —— 完整源信息（直播源 +
            //   backUrl + backSourceLabels）同时供：①「挑首选源」②「线路/清晰度两个 UI 轴」
            //   ③「回看链 A」④「网页兜底 B」使用。
            //   ⚠️ 修 bug：此前只读 sourceLabels，把服务端单独下发的回看源整段丢掉了 ——
            //   剧场组（电影/少儿/综艺…）因此丢了唯一可播路径（云图回看）。
            com.tv.live.migu.source.NowTvSourceApi.ChannelSourceInfo info =
                    com.tv.live.migu.source.NowTvSourceApi.getChannelSourceInfo(context, tvId);
            List<com.tv.live.migu.source.NowTvSourceApi.SourceLabel> allSources = info.sources;
            yuntuLookBack = info.isYunTuLookBack();
            webPageSource = info.firstWebPageSource();
            // 采集「具体回看源」：backSourceLabels 里排除 yuntulunbo 关键字项
            //  （关键字已由上面的合成线路覆盖），剩余的具体串（典型 lunbolbk://<pid>）
            //  在直播轮播全黑洞时逐条尝试（原版 UrlParser 直解，走旁路①/②）。
            for (com.tv.live.migu.source.NowTvSourceApi.SourceLabel bl : info.backSources) {
                if (bl == null || TextUtils.isEmpty(bl.source)) continue;
                if (bl.source.toLowerCase().contains("yuntulunbo")) continue;
                if (!backCand.contains(bl.source)) backCand.add(bl.source);
            }
            LogBridge.i(TAG, "【咪咕】源信息 tvId=" + tvId + " → " + info
                    + (yuntuLookBack ? " ·★云图回看型" : "")
                    + (backCand.isEmpty() ? "" : " ·具体回看源" + backCand.size() + "条=" + backCand)
                    + (TextUtils.isEmpty(webPageSource) ? "" : " ·网页源=" + webPageSource));
            if (yuntuLookBack) {
                // 把服务端的回看关键字 yuntulunbo 合成为一条可选线路（「回看·云图」）——
                // 用户可手动切到它；自动链路也复用同一解析入口（见 resolveMiguChain 旁路④）。
                allSources.add(com.tv.live.migu.source.NowTvSourceApi.synthesized(
                        com.tv.live.migu.source.CarouselApi.SCHEME_YUNTU_LB + tvId,
                        "云图回看", "回看"));
            }
            List<com.tv.live.migu.source.NowTvSourceApi.SourceLabel> playable =
                    com.tv.live.migu.source.NowTvSourceApi.filterForAxis(allSources);
            com.tv.live.migu.MiguStreamAxis axis =
                    com.tv.live.migu.MiguStreamAxis.build(playable);
            mMiguAxis = axis;
            LogBridge.i(TAG, "【咪咕】服务端下发 tvId=" + tvId + " → 可播源 " + playable.size()
                    + " 条，线路=" + axis.lines + "，清晰度=" + axis.qualities);

            // 恢复用户上次手动选择的 (线路, 清晰度)；没选过则用轴首选
            String memLine = miguPrefGet(tvId, "line");
            String memQuality = miguPrefGet(tvId, "quality");
            com.tv.live.migu.MiguStreamAxis.Entry chosen =
                    axis.isEmpty() ? null : axis.find(memLine, memQuality);
            boolean userChosen = chosen != null
                    && (!TextUtils.isEmpty(memLine) || !TextUtils.isEmpty(memQuality));

            String served;
            if (userChosen) {
                served = chosen.source;
                LogBridge.i(TAG, "【咪咕】按记忆的线路/清晰度选源 → " + chosen);
            } else {
                served = com.tv.live.migu.source.NowTvSourceApi.pickFirstPlayable(allSources);
            }
            if (chosen != null) {
                mMiguCurLine = chosen.lineName;
                mMiguCurQuality = chosen.qualityLabel;
            }
            if (TextUtils.isEmpty(served)) {
                LogBridge.w(TAG, "【咪咕】服务端未下发源 tvId=" + tvId);
                return null;
            }
            LogBridge.i(TAG, "【咪咕】采用源 → " + served);

            // ★「全部优先咪咕线路」：下发源不是 migu* 时（典型：tvId=43 湖南卫视只有
            //   ysp:// + CDN 直链，无咪咕源），按频道名从本地咪咕表补一条 miguhd://<pID>
            //   优先解析；解析失败则原样回退服务端下发源（不重试、不再多发请求）。
            //   ⚠️ 用户手动选过线路时**不补** —— 尊重用户选择。
            if (!userChosen && !served.startsWith("migu") && !TextUtils.isEmpty(channelName)) {
                String pidByName = com.tv.live.migu.MiguSourceLoader
                        .lookupPidByName(context, channelName);
                if (!TextUtils.isEmpty(pidByName)) {
                    Resolved viaMigu = resolveMiguChain(
                            com.tv.live.migu.MiguUrlResolver.SCHEME + pidByName,
                            depth + 1, channelName);
                    if (viaMigu != null && !TextUtils.isEmpty(viaMigu.url)) {
                        LogBridge.i(TAG, "【咪咕】按频道名补咪咕源命中：" + channelName
                                + " → miguhd://" + pidByName + "（下发源 " + served + " 未采用）");
                        return viaMigu;
                    }
                    LogBridge.w(TAG, "【咪咕】按频道名补的咪咕源未取到直链，回退下发源：频道="
                            + channelName + " pID=" + pidByName + " 下发源=" + served);
                }
            }
            source = served;
        }

        // ---- 旁路①：回看协议 lunbolbk://<pid> → /p/get_carousel_video ----
        //   （2026-10-07 按原版 UrlParser 的 lunbolbk:// 分支补齐，之前完全没实现）
        if (com.tv.live.migu.source.CarouselApi.isLookBackUrl(source)) {
            com.tv.live.migu.source.CarouselApi.Resolution r =
                    dnsPickAlive(com.tv.live.migu.source.CarouselApi.resolveLookBack(source));
            if (r == null || TextUtils.isEmpty(r.url)) {
                LogBridge.w(TAG, "【咪咕】回看解析失败：" + source);
                return null;
            }
            LogBridge.i(TAG, "【咪咕】回看拿到直链 → " + r.url
                    + (r.candidates.size() > 1 ? "（共 " + r.candidates.size() + " 条候选）" : ""));
            source = r.url;
        }

        // ---- 旁路②：回看解析串 lunboParser_<tvId>_<offset>_&?start=..&end=.. ----
        //   （原版 lunbo.js::yuntuLbParsePlay → /p/get_carousel_video_for_lb）
        if (com.tv.live.migu.source.CarouselApi.isLunboParserUrl(source)) {
            com.tv.live.migu.source.CarouselApi.Resolution r =
                    dnsPickAlive(com.tv.live.migu.source.CarouselApi.resolveLunboParser(source));
            if (r == null || TextUtils.isEmpty(r.url)) {
                LogBridge.w(TAG, "【咪咕】回看串解析失败（该时段可能无回看缓存）：" + source);
                return null;
            }
            source = r.url;
        }

        // ---- 旁路④：云图回看合成源 yuntulunbo://<tvId> ----
        //   （2026-10-07 新增）服务端 backSourceLabels 的关键字式回看通道；
        //   解析时补 EPG 时间窗 → lunboParser → /p/get_carousel_video_for_lb。
        //   用户手动选「回看·云图」线路、或自动链路黑洞兜底时都会走到这里。
        if (com.tv.live.migu.source.CarouselApi.isYunTuLookBackUrl(source)) {
            int ytTvId = com.tv.live.migu.source.CarouselApi.yunTuTvIdOf(source);
            com.tv.live.migu.source.CarouselApi.Resolution r =
                    dnsPickAlive(resolveYunTuLookBack(ytTvId));
            if (r == null || TextUtils.isEmpty(r.url) || r.allCandidatesDead()) {
                LogBridge.w(TAG, "【咪咕】云图回看未取到可用地址：tvId=" + ytTvId);
                return null;
            }
            LogBridge.i(TAG, "【咪咕】云图回看命中 → " + r.url);
            source = r.url;
        }

        // ---- 轮播协议 tvnow://（剧场类频道：电影/少儿/综艺/热播剧集/央视大剧…） ----
        if (com.tv.live.migu.source.CarouselApi.isCarouselUrl(source)) {
            com.tv.live.migu.source.CarouselApi.Resolution r =
                    com.tv.live.migu.source.CarouselApi.resolve(source);
            if (r == null || TextUtils.isEmpty(r.url)) {
                LogBridge.w(TAG, "【咪咕】轮播解析失败：" + source);
                // B【2026-10-07】轮播整体失败 → 试网页类源兜底（JS letv_vod.js）
                Resolved web = tryWebPageFallback(webPageSource, depth, channelName);
                if (web != null) return web;
                return null;
            }
            carouselSeekSec = r.offsetSec;
            LogBridge.i(TAG, "【咪咕】轮播拿到当前节目《" + r.title + "》已播 "
                    + r.offsetSec + "s → " + r.url
                    + (r.candidates.size() > 1 ? "（共 " + r.candidates.size() + " 条候选）" : ""));

            // ★【2026-10-08】把轮播节目单接入 EPG —— 数据来自本次请求的响应 data[]
            //   （已由 CarouselApi 顺带缓存），<b>不产生任何新请求</b>；
            //   EPG 面板 / 信息栏随后自动重渲染。
            // ★【2026-10-07】全天节目单（原版 program_v3.php）：仅当轮播缓存不足 5 条
            //   时拉 1 次/频道/天 —— 成功后 EPG 面板与原版一致（全天节目带「回看」pid），
            //   也是下面回看兜底的候选池。
            com.tv.live.migu.source.CarouselApi.ensureDaySchedule(r.carouselTvId);
            pushMiguCarouselEpg(r.carouselTvId);

            // ★【2026-10-08】候选 DNS 探活换线 —— 热播剧集组修复的核心。
            //   轮播 videos[] 是第三方资源站 CDN（wlcdn99/cdnlz12/gsuus/xluuss/jisuzyv/…），
            //   服务端排序第一条可能域名已死（实测 cdn.wlcdn99.com 无 A 记录）：
            //   旧行为固定播第一条 → ExoPlayer UnknownHostException → 自动重试 5 次
            //   全打同一条死域名 → 必失败（而候选[1..5] 域名均活着）。
            //   这里按序换成第一条「域名可解析」的候选（纯本机 DNS 查询，零 HTTP 请求）；
            //   全部失效则保持第一条，原失败语义不变。
            r = dnsPickAlive(r);

            // ★ 全部候选都命中已知黑洞主机 → 依次试回看链 / 回看旁路
            //   A【2026-10-07 新增】云图回看链（服务端声明 backSourceLabels=yuntulunbo 时）：
            //      get_channel_current_epg?tvId= → st/et
            //      → lunboParser_<tvId>_1_&?start=<st>&end=<et>&
            //      → GET /p/get_carousel_video_for_lb?tvId=&offsert=1&start=&end=（原版 lookBack）
            //   ① /p/get_carousel_video?pid=<节目id>（原 tryBypass 第 1 条）
            //   ② /p/get_carousel_video_for_lb?tvId=&start=<节目时段起>&end=<节目时段止>
            //   串行、每步最多 1 次、不重试；任一步取到非黑洞地址即停止。
            //   都无果则保持原候选（不改变「先试第一条」的失败语义）。
            if (r.allCandidatesDead()) {
                LogBridge.w(TAG, "【咪咕】轮播候选全命中黑洞 → 试回看链（yuntu=" + yuntuLookBack
                        + " pid=" + r.programId + " tvId=" + r.carouselTvId + "）");
                com.tv.live.migu.source.CarouselApi.Resolution alt = null;
                // A：云图回看链（时间窗来自 get_channel_current_epg）
                if (yuntuLookBack && nowTvId > 0) {
                    alt = resolveYunTuLookBack(nowTvId);
                    if (alt != null && !TextUtils.isEmpty(alt.url)
                            && !alt.allCandidatesDead()) {
                        LogBridge.i(TAG, "【咪咕】云图回看链命中 → " + alt.url);
                    } else {
                        LogBridge.w(TAG, "【咪咕】云图回看链未取到可用地址，继续 pid 旁路");
                        alt = null;
                    }
                }
                // ② 原有回看旁路
                //    ⚠️ 若 A 已经跑过（yuntuLookBack），`for_lb`（用轮播时间窗）与本链
                //    A 的 `for_lb`（用 EPG 时间窗，两者实测同值）是**同一个请求**，
                //    为避免重复请求，这里只补 A 没覆盖的「pid 旁路」。
                if (alt == null) {
                    if (yuntuLookBack) {
                        if (!TextUtils.isEmpty(r.programId)) {
                            alt = com.tv.live.migu.source.CarouselApi.getCarouselVideo(
                                    r.programId, "轮播兜底·节目id=" + r.programId);
                            if (alt != null && alt.allCandidatesDead()) alt = null;
                        }
                    } else {
                        alt = com.tv.live.migu.source.CarouselApi.tryBypass(r);
                    }
                }
                // ★【2026-10-07】服务端 backSourceLabels 里的「具体回看源」
                //   （典型 lunbolbk://<pid> —— 原版 UrlParser 直解，即旁路①/②）。
                //   原实现只认 yuntulunbo 关键字，把这些具体串整条丢弃 ——
                //   剧场组因此丢了唯一可播路径。逐条串行尝试（每条 1 次，不重试）。
                if (alt == null && !backCand.isEmpty()) {
                    for (String bc : backCand) {
                        Resolved via = resolveMiguChain(bc, depth + 1, channelName);
                        if (via != null && !TextUtils.isEmpty(via.url)
                                && !com.tv.live.migu.source.CarouselApi
                                        .isKnownDeadHost(via.url)) {
                            LogBridge.i(TAG, "【咪咕】具体回看源命中 → " + bc
                                    + " → " + via.url);
                            if (via.seekSec == 0 && r.offsetSec > 0) {
                                via.seekSec = r.offsetSec;
                            }
                            return via;
                        }
                        LogBridge.w(TAG, "【咪咕】具体回看源未取到可用地址：" + bc);
                    }
                }
                // ★【2026-10-07】最后兜底：同频道「回看候选」（节目单已缓存，零列表请求）。
                //   候选序 = 下一条未播 → 最近已结束（原版 EPG「回看」同数据源），
                //   至多试 2 条（每条 1 次 get_carousel_video）——某条节目若已获授权
                //   （videoHtml=zgyj_vod）即可起播；全部黑洞则放弃并快速失败。
                if (alt == null) {
                    java.util.List<String> lbPids = com.tv.live.migu.source.CarouselApi
                            .lookBackPidCandidates(r.carouselTvId, r.programId);
                    int tried = 0;
                    for (String lbPid : lbPids) {
                        if (tried >= 2) break;
                        tried++;
                        alt = com.tv.live.migu.source.CarouselApi.getCarouselVideo(
                                lbPid, "节目单回看 pid=" + lbPid);
                        if (alt != null && alt.allCandidatesDead()) alt = null;
                        if (alt != null) {
                            LogBridge.i(TAG, "【咪咕】节目单回看命中 pid=" + lbPid
                                    + " → " + alt.url + "（当前节目版权受限，已切可播节目）");
                            break;
                        }
                    }
                }
                if (alt != null && !TextUtils.isEmpty(alt.url)) {
                    alt = dnsPickAlive(alt);
                    LogBridge.i(TAG, "【咪咕】旁路取到地址 → " + alt.url);
                    r = alt;
                } else {
                    LogBridge.w(TAG, "【咪咕】回看链与旁路均无可用地址（该节目全部源均在黑洞主机）");
                }
            }

            if (com.tv.live.migu.source.CarouselApi.isCarouselUrl(r.url)) {
                // 内层又是轮播 → 递归（有深度上限，防环）
                Resolved inner = resolveMiguChain(r.url, depth + 1, channelName);
                if (inner != null && inner.seekSec == 0) inner.seekSec = r.offsetSec;
                return inner;
            }

            // B【2026-10-07】轮播/回看链全部落黑洞 → 试「网页类」源兜底
            //   （服务端 sourceLabels 里的 http://www.le.com → JS letv_vod.js::letvVodParsePlay）
            if (com.tv.live.migu.source.CarouselApi.isKnownDeadHost(r.url)) {
                Resolved web = tryWebPageFallback(webPageSource, depth, channelName);
                if (web != null) {
                    LogBridge.i(TAG, "【咪咕】轮播全黑洞 → 网页源兜底命中 → " + web.url);
                    return new Resolved(web.url, carouselSeekSec);
                }
            }
            source = r.url;
        }

        // ---- 旁路③：追光影界 VOD 鉴权链 http://zgyj_vod/<base64> → GITV playUrl ----
        //   （2026-10-07 按原版 other_live.js::zgyjvodParsePlay 1:1 Java 移植）
        //   央视大剧 / 剧场组轮播节目的 get_current_carousel 往往没有 videos[]，
        //   只给 videoHtml = "http://zgyj_vod/<base64>"（base64 = {"cdnName":"yinhe",
        //   "partnerType":"VOD","contentId":"clear/…/index.m3u8","vids":[]}）。
        //   原版交 JS → GET hecmcc-gvp-zy-vod.playauth.gitv.tv/gitv_vod_auth/<base64>
        //   ?playAuthToken=…&gMac=… → 返回 data.playUrl
        //   （hegd-vod.ali-cdn.gitv.tv/…/index.m3u8，阿里 CDN 可播）。
        //   该地址也可能直接出现在 sourceLabels 里，故此处统一兜住。
        if (com.tv.live.migu.source.ZgyjVodApi.isZgyjVodUrl(source)) {
            com.tv.live.migu.source.ZgyjVodApi.Result zr =
                    com.tv.live.migu.source.ZgyjVodApi.resolve(source);
            if (zr != null && !TextUtils.isEmpty(zr.url)) {
                LogBridge.i(TAG, "【咪咕】GITV VOD 鉴权命中 → " + zr.url);
                source = zr.url;
            } else {
                // Java 侧没取到 → 不中断，继续往下交 JS 路由表
                // （yt_source_root.js 有 `http://zgyj_vod/` → zgyjvodParsePlay 兜底）
                LogBridge.w(TAG, "【咪咕】GITV VOD 鉴权未取到 playUrl，转 JS 路由兜底：" + source);
            }
        }

        if (com.tv.live.migu.MiguUrlResolver.isHttpUrl(source)
                && !com.tv.live.migu.MiguUrlResolver.isWebPageUrl(source)) {
            LogBridge.i(TAG, "【咪咕】直链，跳过 JS 直接起播");
            return new Resolved(resolveMiguRedirect(source), carouselSeekSec);
        }
        if (com.tv.live.migu.MiguUrlResolver.isWebPageUrl(source)) {
            // ★【2026-10-07】网页类源（原版 videoHtml 语义，典型 http://www.le.com）：
            //   yt_source_root.js 里 `.le.com` 命中 letv_vod.js::letvVodParsePlay。
            //   这里不 return，继续往下走 JS 引擎分支；JS 不可用时无 pid 可降级 → 返回 null。
            LogBridge.i(TAG, "【咪咕】网页类源，交 JS 路由表解析：" + source);
        }

        String pid = com.tv.live.migu.MiguUrlResolver.pidOf(source);

        // ---- 第 ②③④ 步：JS 引擎完整解析（WebView 跑 yt_source_root.js 路由 + 插件） ----
        if (com.tv.live.migu.js.JsEngine.get().isAvailable()) {
            try {
                String viaJs = com.tv.live.migu.js.UrlSourceParser
                        .parseSync(context, source, MIGU_JS_TIMEOUT_MS);
                if (!TextUtils.isEmpty(viaJs)) {
                    LogBridge.i(TAG, "【咪咕】JS 引擎解析成功 ← " + source);
                    return new Resolved(resolveMiguRedirect(viaJs), carouselSeekSec);
                }
                LogBridge.w(TAG, "【咪咕】JS 引擎未取到直链，降级 Java ← " + source);
            } catch (Throwable t) {
                LogBridge.w(TAG, "【咪咕】JS 引擎异常，降级 Java：" + t.getMessage());
            }
        } else {
            LogBridge.i(TAG, "【咪咕】JS 引擎不可用，走 Java 解析 ← " + source);
        }

        // ---- 降级：纯 Java 直连咪咕官方接口（只有 migu* 协议能拿到 pid） ----
        if (pid == null) {
            LogBridge.w(TAG, "【咪咕】JS 未取到直链且无可提取 pID，无法降级：" + source);
            return null;
        }
        String viaJava = com.tv.live.migu.MiguUrlResolver.resolvePid(pid);
        return TextUtils.isEmpty(viaJava) ? null
                : new Resolved(resolveMiguRedirect(viaJava), carouselSeekSec);
    }

    /**
     * ★【2026-10-08】候选 DNS 探活换线（热播剧集组修复核心）。
     *
     * <p>候选里排序靠前的域名可能已死（实测 {@code cdn.wlcdn99.com} 无 A 记录），
     * 旧行为固定播第一条 → ExoPlayer {@code UnknownHostException} 且自动重试 5 次
     * 全打同一条死域名。本方法按序换成第一条「域名可解析」的候选 ——
     * <b>纯本机 DNS 查询，零 HTTP 请求</b>（结果进程级缓存）；全部失效保持原样。
     */
    private static com.tv.live.migu.source.CarouselApi.Resolution dnsPickAlive(
            com.tv.live.migu.source.CarouselApi.Resolution r) {
        if (r == null || r.candidates.size() <= 1) return r;
        String pick = com.tv.live.migu.source.CarouselApi.pickResolvableCandidate(r.candidates);
        if (!TextUtils.isEmpty(pick) && !pick.equals(r.url)) {
            LogBridge.i(TAG, "【咪咕】候选 DNS 探活换线：" + r.url + " → " + pick);
            return r.withUrl(pick);
        }
        return r;
    }

    /**
     * ★【2026-10-08】普通源（内置源4 影视等）起播前 DNS 探活换线 —— 候选构建。
     *
     * <p>背景：内置源4（{@code assets/live_source_4.txt}，7799 行影视源）同名多行
     * 即同一频道的多条线路（解析进 {@code backupUrls}），全部是采集站 CDN，
     * 存在与热播剧集组相同的「域名已死」问题。
     *
     * <p>候选顺序：用户所选/传入地址优先（活着则原样播放，尊重手动选线）→ 主源 →
     * 备源（保序、去重；只收 http/https 候选，migu/虎牙等协议串过滤掉）。
     *
     * <p><b>线程模型</b>：本方法只做候选构建（零阻塞）。DNS 判定由调用方分两步完成 ——
     * ① {@link com.tv.live.migu.source.CarouselApi#pickResolvableCandidateCached}
     * （纯内存，主线程安全）秒判已知结果；② 命中未知域名时
     * {@link com.tv.live.migu.source.CarouselApi#warmHostAliveAsync} 后台预热。
     * （初版直接在 doPlay 主线程调阻塞版 {@code pickResolvableCandidate}，抛
     * NetworkOnMainThreadException 被吞 → 永不换线，已改。）
     */
    private static java.util.List<String> buildLineCandidatesForChannel(String wanted, Channel ch) {
        java.util.LinkedHashSet<String> cand = new java.util.LinkedHashSet<>();
        if (!TextUtils.isEmpty(wanted)) cand.add(wanted);
        if (ch != null) {
            try {
                String main = ch.getMainPlayUrl();
                if (!TextUtils.isEmpty(main)
                        && (main.startsWith("http://") || main.startsWith("https://"))) {
                    cand.add(main);
                }
                for (String b : ch.getBackupUrls()) {
                    if (!TextUtils.isEmpty(b)
                            && (b.startsWith("http://") || b.startsWith("https://"))) {
                        cand.add(b);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return new java.util.ArrayList<>(cand);
    }

    /**
     * A【2026-10-07】云图回看链（原版 {@code type=lookBack}）—— 剧场组唯一可播路径。
     *
     * <p>链路（1:1 复刻原版，用户已确证）：
     * <pre>
     *   ① GET nowtv-new.xiaoyouzb.cn/channel/get_channel_current_epg?tvId=  → st / et
     *   ② 拼 lunboParser_&lt;tvId&gt;_1_&amp;?start=&lt;st&gt;&amp;end=&lt;et&gt;&amp;
     *      （原版 JS {@code lunbo.js::yuntuLbParsePlay} 的入参格式）
     *   ③ GET user.xiaoyouzb.net/p/get_carousel_video_for_lb?tvId=&amp;offsert=1&amp;start=&amp;end=
     *      → 真实播放地址（GITV 银河 VOD CDN，可播）
     * </pre>
     * 仅当频道声明 {@code backSourceLabels=yuntulunbo} 且<b>轮播候选全黑洞</b>时才调用。
     * 串行：前一条无果不发下一条；全程单次、不重试、不并发。
     *
     * @param tvId 频道 tvId
     * @return 解析结果；无果返回 {@code null}
     */
    private com.tv.live.migu.source.CarouselApi.Resolution resolveYunTuLookBack(int tvId) {
        if (tvId <= 0) return null;
        com.tv.live.migu.source.NowTvSourceApi.CurrentEpg epg =
                com.tv.live.migu.source.NowTvSourceApi.getChannelCurrentEpg(context, tvId);
        if (epg == null || !epg.valid()) {
            LogBridge.w(TAG, "【咪咕】云图回看链：未取到当前节目时间窗 tvId=" + tvId);
            return null;
        }
        String raw = com.tv.live.migu.source.CarouselApi.buildLunboParserUrl(
                String.valueOf(tvId), "1", epg.st, epg.et);
        if (TextUtils.isEmpty(raw)) return null;
        LogBridge.i(TAG, "【咪咕】云图回看链 → " + raw + "（当前节目《" + epg.title + "》）");
        return com.tv.live.migu.source.CarouselApi.resolveLunboParser(raw);
    }

    /**
     * B【2026-10-07】「网页类」源兜底 —— 直播轮播与回看链全部落黑洞时启用。
     *
     * <p>服务端 {@code sourceLabels} 里常带一条 {@code http://www.le.com}（网页，非流），
     * 原版把它保留为一条可选线路，选中后交 JS 路由
     * （{@code yt_source_root.js} 的 {@code .le.com} → {@code letv_vod.js::letvVodParsePlay}）。
     * 这里在自动选源全失败时自动试它一次。
     *
     * <p>红线：单次、不重试、不并发；取到的地址若仍是黑洞主机则视为失败。
     *
     * @return 网页源解析结果；无网页源 / 解析失败返回 {@code null}
     */
    private Resolved tryWebPageFallback(String webPageSource, int depth, String channelName) {
        if (TextUtils.isEmpty(webPageSource)) return null;
        if (depth >= 2) {
            LogBridge.w(TAG, "【咪咕】网页兜底跳过（解析层级过深）");
            return null;
        }
        LogBridge.i(TAG, "【咪咕】网页源兜底 → " + webPageSource);
        Resolved web;
        try {
            web = resolveMiguChain(webPageSource, depth + 1, channelName);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【咪咕】网页源兜底异常：" + t.getMessage());
            return null;
        }
        if (web == null || TextUtils.isEmpty(web.url)
                || com.tv.live.migu.source.CarouselApi.isKnownDeadHost(web.url)) {
            LogBridge.w(TAG, "【咪咕】网页源兜底未取到可用地址");
            return null;
        }
        return web;
    }

    /** 咪咕链路的解析结果：真实可播地址 + 轮播频道起播定位秒数 */
    private static final class Resolved {
        final String url;
        int seekSec;

        Resolved(String url, int seekSec) {
            this.url = url;
            this.seekSec = seekSec;
        }
    }

    /**
     * 咪咕 CDN「跨主机 302」修正。
     *
     * <p>实测（2026-10-06）：主播放列表
     * {@code http://gslbmgsplive.miguvideo.com/.../index.m3u8} 返回 <b>302</b>，落地到
     * {@code http://hlsztemgsplive.miguvideo.com:8080/.../index.m3u8?...&encrypt=<令牌>}。
     * 播放器解析 HLS 时以「请求 URL」为基准拼相对路径（并非 302 落地 URL），于是子列表
     * {@code 01.m3u8} 被拼回跳转<b>前</b>的主机，而 {@code mtv_session} 是跳转<b>后</b>主机
     * 签发的 → 子列表返回 HTTP 661，起播失败。
     *
     * <p>修法：起播前把主列表的 302 落地点探出来，直接用落地 URL 起播，相对路径基准就落在
     * 正确主机上。仅探测 1 次、不重试、不并发；探测失败原样返回（回退到修复前行为，不会更差）。
     */
    private String resolveMiguRedirect(String m3u8) {
        if (TextUtils.isEmpty(m3u8) || !m3u8.startsWith("http")) return m3u8;
        // ★【2026-10-07】已知黑洞主机：HEAD 探测也必然超时 4s，直接返回交上层快速失败
        if (com.tv.live.migu.source.CarouselApi.isKnownDeadHost(m3u8)) return m3u8;
        // 只有 HLS 才有「相对子列表基准」问题；mp4/flv 等整段流直接交播放器，省一次 HEAD
        String lower = m3u8.toLowerCase();
        int q = lower.indexOf('?');
        String pathOnly = q > 0 ? lower.substring(0, q) : lower;
        if (!pathOnly.endsWith(".m3u8") && !pathOnly.endsWith(".m3u")) {
            return m3u8;
        }
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL base = new java.net.URL(m3u8);
            conn = (java.net.HttpURLConnection) base.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            if (loc != null && code >= 300 && code < 400) {
                // 相对 Location 需按原地址解析
                String absolute = loc.startsWith("http") ? loc : new java.net.URL(base, loc).toString();
                LogBridge.i(TAG, "【咪咕】主列表跨主机跳转 " + code + " → 改用落地地址起播");
                return absolute;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "【咪咕】跳转探测失败（沿用原地址）：" + t.getMessage());
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
        return m3u8;
    }

    // ------------------------------------------------------------------
    // 【2026-10-07 新增】黑洞预判：版权受限快速失败
    // ------------------------------------------------------------------

    /** 上一次「版权受限」提示时刻（5s 去重，避免连续切台时刷屏） */
    private volatile long mLastCopyrightToastAt = 0L;

    /**
     * 解析结果命中已知黑洞主机时的统一出口。
     *
     * <p>行为：Toast 提示「该节目当前版权受限」+ 通知监听者；<b>不重试、不再发起任何请求</b>。
     * 用户仍可手动在「频道线路选择」里切到别的线路（例如网页线路）。
     */
    private void notifyMiguCopyrightLimited() {
        long now = System.currentTimeMillis();
        if (now - mLastCopyrightToastAt > 5000L) {
            mLastCopyrightToastAt = now;
            try {
                Toast.makeText(context, "该节目当前版权受限，暂无法播放", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
        if (listener != null) {
            try {
                listener.onPlayError("该节目当前版权受限（源在已知黑洞主机）");
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================================================================
    // 【2026-10-07 新增】咪咕「线路 × 清晰度」选择 —— 供设置面板两个弹窗使用
    // ==================================================================

    /** 咪咕线路名列表（空 = 当前不是咪咕频道，或尚未解析出多源） */
    public List<String> getMiguLineNames() {
        com.tv.live.migu.MiguStreamAxis axis = mMiguAxis;
        return axis == null ? new ArrayList<>() : new ArrayList<>(axis.lines);
    }

    /** 咪咕清晰度名列表（降序，高清在前） */
    public List<String> getMiguQualityNames() {
        com.tv.live.migu.MiguStreamAxis axis = mMiguAxis;
        return axis == null ? new ArrayList<>() : new ArrayList<>(axis.qualities);
    }

    /** 当前频道是否已解析出咪咕线路轴 */
    public boolean hasMiguAxis() {
        com.tv.live.migu.MiguStreamAxis axis = mMiguAxis;
        return axis != null && !axis.isEmpty();
    }

    /** 「频道线路选择」里咪咕当前线路的打勾下标（0 基）；无法确定返回 -1 */
    public int getCurrentMiguLineUiIndex() {
        com.tv.live.migu.MiguStreamAxis axis = mMiguAxis;
        if (axis == null || axis.isEmpty()) return -1;
        return axis.indexOfLine(mMiguCurLine);
    }

    /** 咪咕当前清晰度展示名（未解析时为「自适应」） */
    public String getCurrentMiguQualityLabel() {
        return TextUtils.isEmpty(mMiguCurQuality) ? "自适应" : mMiguCurQuality;
    }

    /**
     * 切换咪咕线路（下标口径 = {@link #getMiguLineNames()}）。
     * 保持当前清晰度不变；该线路没有这档时用它的首选档。
     */
    public void switchMiguLine(int index) {
        com.tv.live.migu.MiguStreamAxis axis = mMiguAxis;
        if (axis == null || axis.isEmpty() || index < 0 || index >= axis.lines.size()) {
            LogBridge.w(TAG, "【咪咕】线路表为空或下标越界，无法切换：" + index);
            return;
        }
        String line = axis.lines.get(index);
        com.tv.live.migu.MiguStreamAxis.Entry e = axis.find(line, mMiguCurQuality);
        if (e == null) {
            LogBridge.w(TAG, "【咪咕】该线路无可播源：" + line);
            return;
        }
        applyMiguSelection(e.lineName, e.qualityLabel, e.source, "切换线路");
    }

    /**
     * 切换咪咕清晰度（口径 = {@link #getMiguQualityNames()}）。
     * 保持当前线路不变；该线路没有这档时退化为任意线路的该档。
     */
    public void switchMiguQuality(String qualityLabel) {
        com.tv.live.migu.MiguStreamAxis axis = mMiguAxis;
        if (axis == null || axis.isEmpty() || TextUtils.isEmpty(qualityLabel)) {
            LogBridge.w(TAG, "【咪咕】清晰度表为空或档位为空，无法切换");
            return;
        }
        com.tv.live.migu.MiguStreamAxis.Entry e = axis.find(mMiguCurLine, qualityLabel);
        if (e == null) {
            LogBridge.w(TAG, "【咪咕】无可播源对应清晰度：" + qualityLabel);
            return;
        }
        applyMiguSelection(e.lineName, e.qualityLabel, e.source, "切换清晰度");
    }

    /** 落地一次咪咕选择：记忆 → 置直接起播标志 → 播该源串（走完整解析链，不重试不并发） */
    private void applyMiguSelection(String line, String quality, String source, String what) {
        if (TextUtils.isEmpty(source)) return;
        mMiguCurLine = line;
        mMiguCurQuality = quality;
        currentResolutionLabel = quality;
        int tvId = miguTvIdOf(currentChannel);
        if (tvId > 0) {
            miguPrefPut(tvId, "line", line);
            miguPrefPut(tvId, "quality", quality);
        }
        LogBridge.i(TAG, "【咪咕】" + what + " → " + line + " / " + quality + "：" + source);
        // ★直接进入咪咕解析链，而不是 playUrlInternal()：
        //   源串里除 migu* 外还有 ysp:// / cctv:// 等「JS 路由表可解析」协议，
        //   而 doPlay 只把 isMiguUrl / nowtv 分流进咪咕链，ysp:// 会被误当普通源丢给 ExoPlayer。
        //   resolveMiguAndPlay 的合法性判定 = 服务端下发 ∪ migu*(有 pid) ∪ JS可路由 ∪ 轮播，
        //   正好覆盖全部轴内源；它内部同样只解析一次（不重试、不并发）。
        mMiguDirectPlay = true;
        resolveMiguAndPlay(source, 0);
    }

    /**
     * 【2026-10-08】把 {@link com.tv.live.migu.source.CarouselApi} 刚缓存的轮播节目单
     * 接入 EPG。
     *
     * <p>数据来源是本次起播 {@code /p/get_current_carousel} 响应的 {@code data[]}
     * （{@code CarouselApi#cachePrograms} 顺带缓存），<b>不产生任何新请求</b>；
     * 只把已经在路上的数据接到 EPG 面板 / 信息栏。
     *
     * @param tvId 轮播频道 tvId（来自内层 URL 的 {@code ?tvId=}）
     */
    private void pushMiguCarouselEpg(String tvId) {
        if (TextUtils.isEmpty(tvId) || context == null) return;
        int id;
        try {
            id = Integer.parseInt(tvId.trim());
        } catch (Throwable t) {
            return;
        }
        if (id <= 0) return;
        try {
            java.util.List<com.tv.live.migu.source.CarouselApi.Program> ps =
                    com.tv.live.migu.source.CarouselApi.getCachedPrograms(tvId);
            if (ps == null || ps.isEmpty()) return;
            com.tv.live.EpgManager.getInstance(context).putMiguEpgFromPrograms(id, ps);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【咪咕】轮播节目单接入 EPG 失败：" + t.getMessage());
        }
    }

    /** 从频道 id（{@code nowtv:<tvId>}）还原 tvId；非服务端下发通道返回 0 */
    private static int miguTvIdOf(Channel ch) {
        if (ch == null) return 0;
        String cid = ch.getChannelId();
        if (TextUtils.isEmpty(cid)) return 0;
        String prefix = com.tv.live.migu.MiguSourceLoader.TVG_PREFIX_NOWTV;
        if (cid.startsWith(prefix)) {
            try {
                return Integer.parseInt(cid.substring(prefix.length()).trim());
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private String miguPrefGet(int tvId, String what) {
        try {
            SharedPreferences p = sp;
            return p == null ? "" : p.getString("migu_" + what + "_" + tvId, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void miguPrefPut(int tvId, String what, String val) {
        try {
            SharedPreferences p = sp;
            if (p != null) {
                p.edit().putString("migu_" + what + "_" + tvId, val == null ? "" : val).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 源6(咪咕)失效兜底：直链带短时签名，缓存久了会失效。
     * 播放彻底失败且备线用尽时，若当前频道属于源6（channelId 形如
     * {@code migu:<pID>} 或 {@code nowtv:<tvId>}），异步重新解析最新直链并重播。
     * 每频道最多触发 {@link #MAX_MIGU_REFRESH} 次，防循环。
     */
    private boolean tryRefreshMiguSource() {
        Channel ch = currentChannel;
        if (ch == null) return false;
        String cid = ch.getChannelId();
        if (TextUtils.isEmpty(cid)) return false;

        // 还原出「待解析地址」：migu:<pID> → miguhd://<pID>；nowtv:<tvId> → nowtv://<tvId>
        String retryUrl = null;
        if (cid.startsWith(com.tv.live.migu.MiguUrlResolver.TVG_PREFIX)) {
            String pid = cid.substring(com.tv.live.migu.MiguUrlResolver.TVG_PREFIX.length()).trim();
            if (!pid.isEmpty()) retryUrl = com.tv.live.migu.MiguUrlResolver.SCHEME + pid;
        } else if (cid.startsWith(com.tv.live.migu.MiguSourceLoader.TVG_PREFIX_NOWTV)) {
            String tvId = cid.substring(
                    com.tv.live.migu.MiguSourceLoader.TVG_PREFIX_NOWTV.length()).trim();
            if (!tvId.isEmpty()) retryUrl = com.tv.live.migu.MiguSourceLoader.SCHEME_NOWTV + tvId;
        }
        if (retryUrl == null) return false;

        if (miguRefreshCount >= MAX_MIGU_REFRESH) return false;
        long now = System.currentTimeMillis();
        if (now - lastMiguRefreshAtMs < MIGU_REFRESH_MIN_INTERVAL_MS) return false;
        miguRefreshCount++;
        lastMiguRefreshAtMs = now;

        // 直接复用「起播解析」通道：它会重走完整链路并 doPlay(url)
        resolveMiguAndPlay(retryUrl, 0);
        return true;
    }

    private void startStuckDetection() {
        mHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        mHandler.postDelayed(stuckCheckRunnable, 2000);
    }

    /**
     * 【2026-09-27 换台卡顿专项·重试去重】虎牙签名失效重试刚发起（同一房间
     * {@link #HUYA_SIG_RETRY_DEDUP_MS} 内）时为 true，卡住检测据此让位，不再重复排重试。
     */
    private boolean isHuyaSigRetryJustFired() {
        return mHuyaRoomId > 0
                && mHuyaRoomId == mLastHuyaSigRetryRoomId
                && System.currentTimeMillis() - mLastHuyaSigRetryAt < HUYA_SIG_RETRY_DEDUP_MS;
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
        // 虎牙直连地址失效（防盗链过期 / CDN 换节点）时清掉缓存，下次重新从 SDK 地址解析
        dropHuyaFinalUrl(mLastHuyaFinalUrlKey);
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
        // ★【2026-10-08】缓存回放直链一旦触发重试 → 立即作废该缓存，
        //   避免重试再次回放同一条死链（后台解析链结果会无缝接管）
        invalidateMiguCacheIfReplayFailed();
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
                    // 畅享源失效兜底：备线用尽前，先尝试实时重拉最新签名源重播
                    if (tryRefreshChanxiangSource()) {
                        return;
                    }
                    // 源5(爱其意)失效兜底：直链签名会短时过期，实时重拉最新直链再重播
                    if (tryRefreshRenyiboSource()) {
                        return;
                    }
                    // 源6(咪咕)失效兜底：直链带短时签名，重新解析一次再重播
                    if (tryRefreshMiguSource()) {
                        return;
                    }
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
        // 【2026-09-27 换台卡顿专项】虎牙签名失效（403/404）是确定性失效，退避等待纯属浪费：
        //   实测换台命中 403 后，"播放卡住" 被当作网络错误走 2s<<1 指数退避，
        //   排队 4000ms 才重试。这类错误直接 0ms 重试（重试必然重新解析拿新签名）。
        boolean huyaSigExpired = isHuyaSignatureExpiredError(reason);
        long delayMs = huyaSigExpired ? 0L
                : (isNetworkError ? (2000L * (1L << (retryCount - 1))) : 3000L);
        logWarn("自动重试（第" + retryCount + "/" + maxRetry + "次）"
                + (huyaSigExpired ? "【虎牙签名失效，立即重试】" : "，延迟" + delayMs + "ms")
                + "，原因：" + reason);
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

    /**
     * 【2026-09-19 404 专项】判断播放错误是否由虎牙 m3u8 地址 404 引起。
     * 判定依据：RedirectLoggingHttpDataSource 在抛 HTTP 404 异常前打的静态时间戳
     * （LAST_HTTP_404_TS），5s 内新鲜即认为是本次播放错误的 404。
     * 为什么不用异常 cause 链：R8 混淆后类名/message 被裁剪（实测 cause链=[ {ea: null} ]），
     * 反射 responseCode 也不可靠。静态打标是唯一稳定通道。
     * 同时限定为虎牙源（currentUrl 含 .huya.com / huyalive），避免误伤普通源。
     */
    /**
     * 判断是否虎牙 m3u8 签名失效（404 或 403）。
     * 404 = 资源不存在（主播切流/房间刷新），403 = 防盗链签名过期，
     * 二者都需要清缓存 + 立即重解析签名，不应走 3s 退避重试。
     */
    private boolean isHuyaSignatureExpiredError(String errDetail) {
        String url = currentUrl;
        if (url == null || !(url.contains(".huya.com") || url.contains("huyalive"))) {
            return false;
        }
        long now = System.currentTimeMillis();
        long ts404 = RedirectLoggingHttpDataSource.LAST_HTTP_404_TS;
        if (ts404 > 0 && now - ts404 < 5000) {
            return true;
        }
        // 【2026-09-24 403 专项】403 Forbidden 同属签名失效
        long ts403 = RedirectLoggingHttpDataSource.LAST_HTTP_403_TS;
        if (ts403 > 0 && now - ts403 < 5000) {
            return true;
        }
        // 兜底：诊断串文本含 404/403（未混淆的 debug 包 / 其他来源）
        if (errDetail != null) {
            String lower = errDetail.toLowerCase();
            if (lower.contains("404") || lower.contains("not found")
                    || lower.contains("403") || lower.contains("forbidden")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否虎牙 CDN 临时错误（503/504）。
     * 503 = WAF 限流/服务不可用，504 = 网关超时，
     * 二者均为 CDN 临时错误，签名仍有效，无需清缓存，
     * 但应快速重试当前线路（不等 3s 退避），持续 5xx 则切线路。
     */
    private boolean isHuyaCdnTempError(String errDetail) {
        String url = currentUrl;
        if (url == null || !(url.contains(".huya.com") || url.contains("huyalive"))) {
            return false;
        }
        long now = System.currentTimeMillis();
        long ts5xx = RedirectLoggingHttpDataSource.LAST_HTTP_5XX_TS;
        if (ts5xx > 0 && now - ts5xx < 5000) {
            return true;
        }
        if (errDetail != null) {
            String lower = errDetail.toLowerCase();
            if (lower.contains("503") || lower.contains("service unavailable")
                    || lower.contains("504") || lower.contains("gateway timeout")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【2026-09-24 5xx 专项】虎牙 CDN 临时错误（503/504）快速重试（不清缓存，签名仍有效）。
     * 503/504 是 CDN 临时错误，快速重试当前线路即可恢复；
     * 连续 5xx 达到阈值时切换到备用线路。
     */
    private void retryHuya5xxImmediately(Throwable error) {
        if (isRetrying) return;
        isRetrying = true;
        int maxRetry = MAX_RETRY_COUNT;
        if (retryCount >= maxRetry) {
            isRetrying = false;
            logWarn("虎牙 5xx(503/504) 重试已达上限（" + maxRetry + "），尝试切换备用线路");
            trySwitchBackup();
            return;
        }
        retryCount++;
        logWarn("【虎牙 CDN 临时错误(503/504)】快速重试当前线路（第" + retryCount + "/" + maxRetry + "次），不清缓存");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            isRetrying = false;
            if (player != null) {
                player.prepare();
                player.setPlayWhenReady(true);
            }
        }, 300);
    }

    /**
     * 【2026-09-19 404 专项】虎牙 404 立即重试（不等 3s 退避）。
     * 404 是确定性失效（签名过期/主播切流），重试越快恢复越快；
     * 且前面已清缓存，重试必然走 SDK 实时解析拿新签名。
     *
     * <p>【2026-09-19 404 升级】连续 404 达到阈值（HUYA_404_SWITCH_LINE_THRESHOLD=2）时，
     * 不再死磕同一线路重新解析，而是从探活通过的存活流列表里选一条
     * <b>不同线路</b>（lineIndex 不同）的流直接播放——对照原始版
     * PlayerActivity.onBufferStart 的"高频缓冲自动切源"（frequentBufferingCountToAutoNextSource=5）。
     * 适用于"某条线路/CDN 节点持续失效"（如 roomId=11601960 的 _4000.m3u8 反复 404）。
     */
    private void retryHuyaImmediately(Throwable error) {
        if (isRetrying) return;
        // 【2026-09-27 换台卡顿专项】无备用线路时的重复重解析是徒劳的：存活流只有当前这一条，
        //   重新解析签名后仍然只拿到同一条线路，必然再次 403。实测这类房间会走
        //   「403 立即重试 + 卡住自动重试 + 自动跳台」三重复解析，每次 prepare 1~3s，累加成 7s 黑屏。
        //   首次失效（streak==0）仍放行重解析一次——签名确实过期时需要拿新签名；
        //   从第 2 次起若无其它存活线路，直接交给跳台，不再空耗一次 prepare。
        if (mHuya404Streak > 0 && mHuyaRoomId > 0) {
            List<HuyaSDKParser.HuyaStreamInfo> aliveNow;
            synchronized (this) {
                aliveNow = mLastAliveStreams;
            }
            if (pickDifferentLineStream(aliveNow) == null) {
                logWarn("【虎牙签名失效】无备用线路（存活流=" + (aliveNow == null ? 0 : aliveNow.size())
                        + "），重解析必然再 403，直接交给跳台");
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
                return;
            }
        }
        isRetrying = true;
        mLastHuyaSigRetryAt = System.currentTimeMillis();
        mLastHuyaSigRetryRoomId = mHuyaRoomId;
        // 重试上限同样受 retryCount 约束，避免 404 时无限循环
        int maxRetry = MAX_RETRY_COUNT;
        if (retryCount >= maxRetry) {
            isRetrying = false;
            logWarn("虎牙签名失效(404/403)重试已达上限（" + maxRetry + "），判定为失效源");
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
            return;
        }
        retryCount++;
        mHuya404Streak++;
        logWarn("【虎牙签名失效(404/403)】立即重试（第" + retryCount + "/" + maxRetry + "次），连续失效计数=" + mHuya404Streak);

        // 连续 404 达到阈值：切换线路（从探活存活流里选不同 lineIndex 的流）
        if (mHuya404Streak >= HUYA_404_SWITCH_LINE_THRESHOLD && mHuyaRoomId > 0) {
            List<HuyaSDKParser.HuyaStreamInfo> alive;
            synchronized (this) {
                alive = mLastAliveStreams;
            }
            HuyaSDKParser.HuyaStreamInfo switchTarget = pickDifferentLineStream(alive);
            if (switchTarget != null) {
                logWarn("【虎牙签名失效·切线路】第 " + mHuya404Streak + " 次失效，切换到线路 "
                        + switchTarget.lineIndex + " " + switchTarget.bitRateDisplayName
                        + "（原线路 " + mCurrentHuyaLineIndex + "）");
                final String switchUrl = switchTarget.getPlayUrl();
                final int switchLine = switchTarget.lineIndex;
                mHuya404Streak = 0;
                mHandler.post(() -> {
                    isRetrying = false;
                    mCurrentHuyaLineIndex = switchLine;
                    // 直接播放新线路地址；同时清缓存防止后续重新解析又回到旧线路
                    HuyaSDKParser.invalidateCachedStreams(mHuyaRoomId);
                    if (!TextUtils.isEmpty(switchUrl)) {
                        playUrlInternal(switchUrl);
                    } else {
                        playHuyaStream(mHuyaRoomId, 0);
                    }
                });
                return;
            }
            logWarn("【虎牙签名失效】无其它存活线路可切换（存活流="
                    + (alive == null ? 0 : alive.size()) + "），退回重新解析签名");
        }

        // 默认路径：重新实时解析签名（清缓存已由调用方完成）
        mHandler.post(() -> {
            isRetrying = false;
            if (mHuyaRoomId > 0) {
                // 【2026-09-29 自研解析】清自研解析器 60s 缓存，确保 403/404 重试拿到新签名地址
                HuyaStreamPlayer.HuyaParser.clearCache(mHuyaRoomId);
                playHuyaStream(mHuyaRoomId, 0);
            } else if (!TextUtils.isEmpty(currentUrl)) {
                playUrlInternal(currentUrl);
            }
        });
    }

    /**
     * 【2026-10-03 BUG C 修复】按线路身份合并存活流，而不是整体覆盖。
     *
     * <p>背景：{@code mLastAliveStreams} 若被整体覆盖为「本次探活结果」，一旦 HS/AL 因抖动
     * 未通过（或探活尚未跑完），列表就退化成 TX-only，{@link #pickDifferentLineStream} 便
     * 永远切不到 HS/AL —— 表现为「AL/HS 不能用」。
     *
     * <p>规则：同一 {@code lineIndex} 视为同一条线路；{@code newList} 的探测结果优先，
     * {@code oldList} 中未被新结果覆盖的线路追加在后；按 lineIndex 去重并上限 24 条防增长。
     */
    private static List<HuyaSDKParser.HuyaStreamInfo> mergeAliveStreams(
            List<HuyaSDKParser.HuyaStreamInfo> oldList,
            List<HuyaSDKParser.HuyaStreamInfo> newList) {
        final int MAX_MERGED = 24;
        java.util.LinkedHashMap<Integer, HuyaSDKParser.HuyaStreamInfo> byLine =
                new java.util.LinkedHashMap<>();
        if (newList != null) {
            for (HuyaSDKParser.HuyaStreamInfo s : newList) {
                if (s == null) continue;
                if (!byLine.containsKey(Integer.valueOf(s.lineIndex))) {
                    byLine.put(Integer.valueOf(s.lineIndex), s);
                }
            }
        }
        if (oldList != null) {
            for (HuyaSDKParser.HuyaStreamInfo s : oldList) {
                if (s == null) continue;
                Integer key = Integer.valueOf(s.lineIndex);
                if (!byLine.containsKey(key)) byLine.put(key, s);
            }
        }
        List<HuyaSDKParser.HuyaStreamInfo> merged = new ArrayList<>(byLine.values());
        if (merged.size() > MAX_MERGED) {
            merged = new ArrayList<>(merged.subList(0, MAX_MERGED));
        }
        return merged;
    }

    /**
     * 【2026-09-27 换台卡顿优化】后台异步补验候选线路。
     * 换台关键路径上只快速探默认流一条，其余线路的探活放这里做，
     * 结果非空时才刷新 mLastAliveStreams（供 403/404 切线路用），
     * 因此探活耗时不再计入换台黑屏时间。
     */
    private void probeAliveStreamsAsync(final List<HuyaSDKParser.HuyaStreamInfo> candidates,
                                        final HuyaSDKParser.HuyaStreamInfo defaultStream,
                                        final int roomIdAtProbe) {
        if (candidates == null || candidates.size() <= 1) return;
        final List<HuyaSDKParser.HuyaStreamInfo> snapshot = new ArrayList<>(candidates);
        new Thread(() -> {
            try {
                List<HuyaSDKParser.HuyaStreamInfo> alive = HuyaLiveProbe.filterAvailable(snapshot);
                if (alive.isEmpty() && defaultStream != null
                        && !TextUtils.isEmpty(defaultStream.getPlayUrl())
                        && HuyaLiveProbe.probeSingleWithGrace(defaultStream.getPlayUrl())) {
                    LogBridge.i(TAG, "【虎牙】后台宽限补验通过，默认流作为唯一存活线路");
                    alive = new ArrayList<>();
                    alive.add(defaultStream);
                }
                if (alive.isEmpty()) {
                    LogBridge.w(TAG, "【虎牙】后台补验全部失败（" + snapshot.size()
                            + " 条），保留全量候选交由播放器兜底");
                    return;
                }
                synchronized (TVPlayerManager.this) {
                    // 补验期间用户可能已切台，旧房间的结果不能覆盖新房间的存活列表
                    if (mHuyaRoomId != roomIdAtProbe) return;
                    // 【2026-10-03 BUG C】按线路合并，避免一次抖动把 HS/AL 挤出存活表
                    mLastAliveStreams = mergeAliveStreams(mLastAliveStreams, alive);
                }
                LogBridge.i(TAG, "【虎牙】后台补验完成，存活 " + alive.size() + "/" + snapshot.size() + " 条");
            } catch (Throwable t) {
                LogBridge.d(TAG, "【虎牙】后台补验异常: " + t.getMessage());
            }
        }, "huya-probe").start();
    }

    /**
     * 【2026-09-27 换台卡顿专项】起播后在后台主动巡检当前播放地址。
     *
     * <p>背景：播放器要把坏 CDN 节点暴露出来平均要 4~5s（等超时/403），
     * 而一次探活 800ms 内就能拿到结果。实测换台命中坏节点时黑屏 7.7s / 9.6s，
     * 其中 4~5s 纯属"等播放器自己报错"。
     *
     * <p>做法：起播后每秒探一次当前地址，连续 {@link #PROBE_WATCH_FAIL_THRESHOLD} 次失败
     * 且播放器尚未 READY（避免误杀正常播放）时，立即清缓存 + 重新解析签名。
     * 线程随换台自动退出（代数比对），最多跑 {@link #PROBE_WATCH_MAX_ROUNDS} 轮。
     */
    private void startProbeWatch(String url, int roomId) {
        if (TextUtils.isEmpty(url)) return;
        // 【2026-09-29 mp 源 403 抖动专项】自研解析(mp/tars_mp)地址不做主动探活：
        // 实测该源对同一条 m3u8 的 403 是**随机抖动**（同一 URL 前后都能 200，与签名无关），
        // 每秒探一次必然频繁命中 403 → 误判"线路失效"→ 清缓存重解析 → 反而把正常播放打断；
        // 同时探活请求本身也挤占 CDN 侧配额。抖动已由数据源内部重试（RedirectLoggingHttpDataSource）
        // 消化，这里直接不探，交给播放器自身错误兜底。
        if (mHuyaDirectPlay) {
            LogBridge.i(TAG, "【主动探活】自研解析地址：跳过主动探活（mp 源 403 为随机抖动，探活会误判）roomId=" + roomId);
            return;
        }
        final long gen = ++mProbeWatchGen;
        Thread t = new Thread(() -> {
            int failStreak = 0;
            int rounds = 0;
            int fails = 0;
            // release 下 d 级不进 LogCollector（/api/logs 取不到），这里只在
            // 启停各打一条 i 级：足以判定"线程有没有跑""探了多少次""失败几次"
            LogBridge.i(TAG, "【主动探活】巡检启动 roomId=" + roomId
                    + " url尾=" + url.substring(Math.max(0, url.length() - 40)));
            try {
                for (int i = 0; i < PROBE_WATCH_MAX_ROUNDS; i++) {
                    Thread.sleep(PROBE_WATCH_INTERVAL_MS);
                    if (gen != mProbeWatchGen || mHuyaRoomId != roomId) break;
                    if (player == null) break;
                    rounds++;
                    if (HuyaLiveProbe.probeOnce(url)) {
                        failStreak = 0;
                        continue;
                    }
                    fails++;
                    failStreak++;
                    if (failStreak < PROBE_WATCH_FAIL_THRESHOLD) continue;
                    if (!isPlayerBufferingOrIdle()) {
                        // 播放器已就绪 = 画面正常，探测失败判定为 CDN 节点差异造成的假阳性
                        LogBridge.d(TAG, "【主动探活】探测失败但播放器已就绪，忽略（假阳性）");
                        failStreak = 0;
                        continue;
                    }
                    LogBridge.w(TAG, "【主动探活】当前地址连续 " + failStreak + " 次探测失败，"
                            + "提前清缓存重解析（不等播放器报错，roomId=" + roomId + "）");
                    mHandler.post(() -> probeWatchTriggerReload(roomId));
                    break;
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable err) {
                LogBridge.d(TAG, "【主动探活】异常: " + err.getMessage());
            }
            LogBridge.i(TAG, "【主动探活】巡检结束 roomId=" + roomId
                    + " 轮数=" + rounds + " 探测失败=" + fails);
        }, "huya-probe-watch");
        t.setDaemon(true);
        t.start();
    }

    private boolean isPlayerBufferingOrIdle() {
        final Player p = player;
        if (p == null) return true;
        // 【2026-09-29 修复】getPlaybackState() 必须在主线程调用：
        // 探活线程直接调用会抛 "Player is accessed on the wrong thread"，
        // 被外层 catch 吞掉后整个巡检线程静默退出（实测日志只剩一条异常）。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            int st = p.getPlaybackState();
            return st == Player.STATE_BUFFERING || st == Player.STATE_IDLE;
        }
        final java.util.concurrent.atomic.AtomicInteger holder =
                new java.util.concurrent.atomic.AtomicInteger(Player.STATE_IDLE);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mHandler.post(() -> {
            try {
                Player cur = player;
                holder.set(cur == null ? Player.STATE_IDLE : cur.getPlaybackState());
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int st = holder.get();
        return st == Player.STATE_BUFFERING || st == Player.STATE_IDLE;
    }

    private void probeWatchTriggerReload(int roomId) {
        if (mHuyaRoomId != roomId) return;
        if (isRetrying) {
            LogBridge.d(TAG, "【主动探活】已有重试在处理，跳过本次重解析");
            return;
        }
        dropHuyaFinalUrl(mLastHuyaFinalUrlKey);
        if (roomId > 0) {
            HuyaSDKParser.invalidateCachedStreams(roomId);
            playHuyaStream(roomId, 0);
        } else if (!TextUtils.isEmpty(currentUrl)) {
            playUrlInternal(currentUrl);
        }
    }

    /**
     * 【2026-09-19 404 升级】从探活存活流里挑一条与当前线路不同的流（优先其它线路的最高码率）。
     * 返回 null 表示没有可切换的目标。
     */
    private HuyaSDKParser.HuyaStreamInfo pickDifferentLineStream(
            List<HuyaSDKParser.HuyaStreamInfo> alive) {
        if (alive == null || alive.isEmpty()) {
            return null;
        }
        HuyaSDKParser.HuyaStreamInfo best = null;
        for (HuyaSDKParser.HuyaStreamInfo s : alive) {
            if (s == null || TextUtils.isEmpty(s.getPlayUrl())) {
                continue;
            }
            if (s.lineIndex == mCurrentHuyaLineIndex) {
                continue; // 跳过当前线路
            }
            if (best == null || s.bitRate > best.bitRate) {
                best = s;
            }
        }
        return best;
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
                        LogBridge.e(TAG, "Surface 重建后恢复播放异帧: " + e.getMessage(), e);
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
        // 卡顿排查结论：原黑名单（omx.ms./c2.mstar./amlogic/hisi）会误伤本机唯一硬解
        // OMX.MS.AVC.Decoder，触发 enableDecoderFallback 运行期换核重建 MediaCodec，
        // 造成起播慢与出画后周期性卡顿。已按要求移除全部黑名单，
        // 仅保留「软解优先」模式下软/硬解的排序偏好（非过滤）。
        return new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(
                    String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                    throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {

                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

                if (preferSoftware) {
                    List<MediaCodecInfo> soft = new ArrayList<>();
                    List<MediaCodecInfo> hard = new ArrayList<>();
                    for (MediaCodecInfo info : all) {
                        if (isSoftwareDecoder(info)) soft.add(info);
                        else hard.add(info);
                    }
                    soft.addAll(hard);
                    return soft;
                }
                return all;
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
            LogBridge.e(TAG, "注册解码器广播失败", e);
        }
    }

    private void switchRenderer(boolean useTexture) {
        if (player == null || playerView == null || context == null) return;
        if (mCurrentUseTexture != null && mCurrentUseTexture == useTexture) {
            // 【2026-09-29 黑屏根因修复·第二轮】埋点：确认是否命中早退分支（用 i 级，release 版可见）
            LogBridge.i(TAG, "switchRenderer 早退分支: useTexture=" + useTexture
                    + ", mCurrentUseTexture=" + mCurrentUseTexture
                    + ", view=" + System.identityHashCode(playerView));
            if (playerView.getPlayer() != player) playerView.setPlayer(player);
            // 【2026-09-29 黑屏根因修复】即使命中早退，也要保证当前 Surface 的回调已绑定：
            //   退出重进时 playerView 是新实例，旧回调绑不上新 Surface，surfaceCreated 永不触发。
            if (!useTexture) {
                surfaceCallbackBound = false;      // 允许重绑到当前视图
                bindSurfaceCallback(playerView);
            }
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
        // 【2026-09-29 黑屏根因修复·第二轮】埋点：重建分支使用的 view 身份
        LogBridge.i(TAG, "switchRenderer 重建分支: useTexture=" + useTexture
                + ", mCurrentUseTexture=" + mCurrentUseTexture
                + ", oldView=" + System.identityHashCode(playerView)
                + ", newView=" + System.identityHashCode(newPlayerView));
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
            // 【2026-09-29 黑屏根因修复·第二轮】埋点：旧视图移除
            LogBridge.i(TAG, "移除旧 PlayerView, identityHashCode=" + System.identityHashCode(oldPlayerView));
            oldPlayerView.setPlayer(null);
            parent.removeView(oldPlayerView);
            // 【2026-09-29 黑屏根因修复·第二轮】旧视图内部 SurfaceView 在移除时会触发 surfaceDestroyed，
            //   ExoPlayer 会因此清空「共享的」player 的视频输出 Surface；而此时 playerView 已是新视图，
            //   且 getPlayer()==player（Java 层引用相等，不代表 ExoPlayer 内部 surface 仍有效），
            //   旧的「相等即跳过」判断无法察觉，导致新视图再也拿不到 Surface → 永久黑屏。
            //   此处旧视图已彻底移除（确定性时序），对新视图强制 setPlayer(null)→setPlayer(player)，
            //   让 ExoPlayer 重新走 setVideoSurface 流程拿回可用 Surface。
            try {
                if (playerView != null && player != null) {
                    playerView.setPlayer(null);
                    playerView.setPlayer(player);
                    LogBridge.i(TAG, "旧视图移除后强制重绑新视图, newView="
                            + System.identityHashCode(playerView));
                }
            } catch (Exception e) {
                LogBridge.w(TAG, "旧视图移除后强制重绑失败: " + e.getMessage());
            }
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

    // 【2026-09-29 黑屏根因修复·第二轮】当前正在排队的 Surface 恢复重试轮询；
    //   重复启动前先取消旧的，避免多个轮询并发跑（实测 attachPlayerView 被调用两次时会并发两个轮询）。
    private Runnable pendingRetryRunnable;

    public void onForeground() {

        try {
            if (player != null && playerView != null) {
                // 【2026-09-29 黑屏修复】不再盲信 surfaceReady 布尔量：
                //   退出重进（返回键 finishAffinity → release → 重建）时，Surface 往往尚未就绪，
                //   但 surfaceReady 可能残留为 true，导致 play() 到失效 Surface，状态机 STATE_READY
                //   却永远不出画面。这里以「实际 Surface 是否有效」为准。
                boolean surfaceActuallyReady = isSurfaceActuallyReady();
                // 【2026-09-29 黑屏根因修复·第二轮】埋点改 i 级，release 版可见；暴露引用相等与实际 Surface 有效性
                LogBridge.i(TAG, "onForeground: surfaceReady=" + surfaceReady
                        + ", surfaceActuallyReady=" + surfaceActuallyReady
                        + ", getPlayer()==player=" + (playerView.getPlayer() == player));

                if (surfaceActuallyReady) {
                    surfaceReady = true;
                    pendingBindPlayer = false;
                    // 【2026-09-29 黑屏根因修复·第二轮】引用相等不代表 ExoPlayer 内部 surface 仍有效，
                    //   回前台就绪分支同样无条件强制「先摘再挂」，确保 ExoPlayer 重新走 setVideoSurface 流程。
                    try {
                        playerView.setPlayer(null);
                        playerView.setPlayer(player);
                    } catch (Exception e) {
                        LogBridge.w(TAG, "onForeground 强制重绑 Surface 失败: " + e.getMessage());
                    }
                    player.play();
                } else {
                    // Surface 未就绪：先绑定播放器并挂起重试，等 Surface 真正可用再恢复播放
                    surfaceReady = false;
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

    /**
     * 【2026-09-29 黑屏修复】校验 playerView 当前持有的 Surface 是否真的可用。
     * TextureView 模式无 SurfaceView，按 surfaceReady 处理；
     * SurfaceView 模式则以 SurfaceHolder 的 Surface.isValid() 为唯一事实来源。
     */
    private boolean isSurfaceActuallyReady() {
        try {
            if (playerView == null) return false;
            View videoSurfaceView = playerView.getVideoSurfaceView();
            // 【2026-09-29 黑屏根因修复】新建的 PlayerView 在布局完成前 getVideoSurfaceView() 可能为
            //   null，原实现会落到下方 `return true` 被当成「已就绪」，导致 bindSurfaceCallback 早退、
            //   回调绑不上新 Surface。这里必须先判 null 返回 false。
            if (videoSurfaceView == null) return false;
            if (!(videoSurfaceView instanceof android.view.SurfaceView)) {
                // TextureView（或其它）：只要已 attach 且确实拿到视图，就认为可用
                return true;
            }
            android.view.SurfaceView sv = (android.view.SurfaceView) videoSurfaceView;
            android.view.Surface surface = sv.getHolder().getSurface();
            return surface != null && surface.isValid();
        } catch (Exception e) {
            LogBridge.w(TAG, "Surface 有效性校验异常，按未就绪处理: " + e.getMessage());
            return false;
        }
    }

    private void retryResumeAfterSurface() {
        try {
            if (mHandler == null) return;
            // 【2026-09-29 黑屏根因修复·第二轮】先取消上一次未结束的重试轮询，避免多个轮询并发跑。
            if (pendingRetryRunnable != null) {
                mHandler.removeCallbacks(pendingRetryRunnable);
                pendingRetryRunnable = null;
            }
            final int[] retryCount = {0};
            // 【2026-09-29 黑屏修复】原 10 次 × 300ms = 3s，对「退出重进」这条
            //   释放播放器 + 重建渲染器 + 重建 Surface 的重路径明显不够（实测 3s 后放弃 → 永久黑屏）。
            //   放宽到 40 次（≈12s），并且判定依据改为「实际 Surface 是否有效」，而非布尔量。
            final int maxRetries = 40;
            Runnable retryRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        retryCount[0]++;
                        if (player != null && playerView != null) {
                            boolean surfaceOk = isSurfaceActuallyReady();
                            // 【2026-09-29 黑屏根因修复·第二轮】埋点：暴露「getPlayer() 引用相等」与「实际 Surface 有效」两个信号
                            LogBridge.i(TAG, "恢复分支: getPlayer()==player="
                                    + (playerView.getPlayer() == player)
                                    + ", isSurfaceActuallyReady=" + surfaceOk
                                    + ", 重试第" + retryCount[0] + "次");
                            if (surfaceOk) {
                                surfaceReady = true;
                                // 【2026-09-29 黑屏根因修复·第二轮】黑屏直接原因：PlayerView.getPlayer() 返回的是
                                //   Java 层引用，相等并不代表 ExoPlayer 内部 videoOutput surface 仍有效。旧视图被
                                //   移除时会把「共享 player」的视频输出 surface 清空，而新视图 getPlayer() 依旧
                                //   == player，旧的「相等即跳过」判断导致永远不再重挂 → 永久黑屏。
                                //   故此处无条件强制「先摘再挂」，让 ExoPlayer 重新走 setVideoSurface 流程。
                                try {
                                    playerView.setPlayer(null);
                                    playerView.setPlayer(player);
                                } catch (Exception e) {
                                    LogBridge.w(TAG, "强制重绑 Surface 失败: " + e.getMessage());
                                }
                                if (!player.isPlaying()) {
                                    player.play();
                                }
                                pendingBindPlayer = false;
                                pendingRetryRunnable = null;   // 【第二轮】轮询正常结束
                                LogBridge.d(TAG, "Surface已重建，播放已恢复（重试第" + retryCount[0] + "次）");
                                return;
                            }

                            try {
                                // 【2026-09-29 黑屏根因修复·第二轮】Surface 尚未就绪，此处保持「引用不等才重挂」，
                                //   避免未就绪期间每 300ms 反复强挂造成抖动；最终成功重挂由上方 surfaceOk 分支负责。
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
                                LogBridge.w(TAG, "Surface恢复重试已达上限(" + maxRetries + "次)，停止重启");
                                pendingBindPlayer = false;
                                pendingRetryRunnable = null;   // 【第二轮】轮询结束
                            }
                        }
                    } catch (Exception e) {
                        LogBridge.e(TAG, "恢复播放重试异常", e);
                    }
                }
            };
            pendingRetryRunnable = retryRunnable;
            mHandler.postDelayed(retryRunnable, 300);
        } catch (Exception e) {
            LogBridge.e(TAG, "启动恢复重试异常", e);
        }
    }

    public void onBackground() {

        try {
            // 【2026-09-29 黑屏修复】原实现只有 `if (player != null) { }` 空块 + 置 pendingBindPlayer，
            //   完全依赖异步的 surfaceDestroyed 去改 surfaceReady。但系统在退后台时并不保证回调
            //   在 onPause 之前送达，导致回前台时 surfaceReady 残留 true，onForeground 走错分支
            //   把画面播到已失效的 Surface 上（实测表现为 STATE_READY 但永久黑屏）。
            //   现在改为主动失效：退后台即认为当前 Surface 不再可信，回前台必然走重建分支。
            surfaceReady = false;
            pendingBindPlayer = true;
            LogBridge.d(TAG, "onBackground: 已主动失效 Surface 状态（surfaceReady=false, pendingBindPlayer=true）");
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
        // 【2026-09-29 黑屏修复】原为「绑定过就直接 return」，导致同一 PlayerView 回前台时
        //   回调不再重绑、surfaceCreated 不会再来纠正状态。改为：先前置于标志，再校验当前
        //   Surface 是否真的有效——有效才复用既有回调，无效则重新绑定。
        if (surfaceCallbackBound && isSurfaceActuallyReady()) return;
        surfaceCallbackBound = false;
        View videoSurfaceView = view.getVideoSurfaceView();
        if (videoSurfaceView instanceof android.view.SurfaceView) {
            android.view.SurfaceView surfaceView = (android.view.SurfaceView) videoSurfaceView;
            surfaceView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    // 【2026-09-29 黑屏根因修复·第二轮】归属判断：只认「当前 playerView」的回调。
                    //   旧视图被移除时也会回调，若不判断会把「新 Surface 已就绪」的状态改错。
                    boolean isCurrent = (view == TVPlayerManager.this.playerView);
                    LogBridge.i(TAG, "surfaceCreated: view=" + System.identityHashCode(view)
                            + ", 是否当前视图=" + isCurrent);
                    if (!isCurrent) return;
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
                    // 【2026-09-29 黑屏根因修复·第二轮】归属判断：忽略旧视图的回调
                    if (view != TVPlayerManager.this.playerView) {
                        LogBridge.i(TAG, "surfaceChanged: 来自旧视图，已忽略");
                        return;
                    }
                    surfaceReady = true;
                    LogBridge.d(TAG, "Surface变化: " + width + "x" + height);
                }

                @Override
                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    // 【2026-09-29 黑屏根因修复·第二轮】归属判断：旧视图被移除时也会触发本回调，
                    //   若无条件置 surfaceReady=false，会污染「新 Surface 已就绪」的全局状态 → 黑屏。
                    if (view != TVPlayerManager.this.playerView) {
                        LogBridge.i(TAG, "surfaceDestroyed: 来自旧视图，已忽略（不污染全局状态）");
                        return;
                    }
                    surfaceReady = false;
                    pendingBindPlayer = true;
                    LogBridge.d(TAG, "Surface销毁，播放器保持运行不解绑");
                }
            });
            surfaceCallbackBound = true;
        }
    }

    public void attachPlayerView(PlayerView view) {
        // 【2026-09-29 黑屏根因修复·第二轮】幂等守卫：实测 attachPlayerView 会被连续调用两次
        //   （854487 / 854543），每次都启动一个 retryResumeAfterSurface() 轮询，导致两个轮询并发
        //   跑、互相覆盖状态。若传入的是同一个 PlayerView 实例且已完成初始化（mCurrentUseTexture
        //   非 null，或 Surface 实际已就绪），直接 return：不重复 switchRenderer、不重复启动轮询。
        if (view != null && view == playerView
                && (mCurrentUseTexture != null || isSurfaceActuallyReady())) {
            LogBridge.i(TAG, "attachPlayerView: 同一视图且已初始化，跳过重复调用, view="
                    + System.identityHashCode(view));
            return;
        }
        playerView = view;

        // 2026-09-10 修复个别频道切台闪黑屏:
        //   默认情况下, ExoPlayer 的 PlayerView 在 setMediaSource(resetPosition=true) 时
        //   会清空 Surface, 切台瞬间出现 200-800ms 黑屏闪烁.
        //   setKeepContentOnPlayerReset(true) 让 Surface 保留上一频道最后一帧,
        //   新频道首帧渲染时再无缝覆盖, 视觉上"无感切换".
        //   此设置与渲染器无共, 必须在 attachPlayerView 一次性应用, 切渲染器走的
        //   switchRenderer -> newPlayerView 路径在 1138 行也会重复设置, 互不冲突.
        view.setKeepContentOnPlayerReset(true);

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
        // 【2026-09-29 黑屏根因修复】playerView 已换为全新实例，渲染器状态必须作废：
        //   mCurrentUseTexture 语义上属于「当前这个 playerView」，surfaceCallbackBound 也属于旧视图。
        //   若不作废，switchRenderer 会命中早退分支跳过重建，Surface 回调也不会重绑到新视图。
        mCurrentUseTexture = null;
        surfaceCallbackBound = false;
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
                    // 【2026-09-29 黑屏修复】attach 时 Surface 尚未就绪（退出重进时的常态）：
                    //   原先只置标志、无人推进，若 surfaceCreated 因回调早退而不触发，
                    //   播放器就永远绑不上新 Surface（STATE_READY 却黑屏）。
                    //   这里主动启动重试轮询，等 Surface 真正可用后自动恢复画面。
                    surfaceReady = false;
                    pendingBindPlayer = true;
                    LogBridge.d(TAG, "attachPlayerView: Surface 尚未就绪，启动恢复重试轮询");
                    retryResumeAfterSurface();
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
        // 起播前确保播放器构建完成（后台构建通常早已完成，此处等待趋近 0）
        awaitPlayerReady(5000);
        if (!TextUtils.isEmpty(channelName)) this.currentChannelName = channelName;
        this.currentChannel = channel;
        // 【2026-09-14 冷启动优化】记录「上次播放流地址」——
        // 供下次启动时 MyApplication.prewarmLastPlayUrl() 做 DNS+TCP+TLS 预热。
        // 注意：此前 CacheManager.saveLastPlay() 只有定义、全项目无调用点，
        // 导致预热永远拿不到地址而静默跳过，这里补上写入。
        try {
            if (!TextUtils.isEmpty(url) && url.startsWith("http")) {
                com.tv.live.util.CacheManager.getInstance(context)
                        .saveLastPlay(url, this.currentChannelName, 0);
            }
        } catch (Throwable ignored) {
        }
        this.backupRetryIndex = -1;
        this.mHuyaDirectPlay = false;
        // 【2026-10-07】新起播（换台 / 重播）复位咪咕「直接起播」标志与旧线路记忆 ——
        // 否则切到非咪咕频道后，doPlay 仍会误绕过频道线路覆盖。
        // 轴本身会在解析第 ① 步重新构建（用户手动选择另由 SP 记忆恢复）。
        this.mMiguDirectPlay = false;
        this.mMiguAxis = com.tv.live.migu.MiguStreamAxis.empty();
        this.mMiguCurLine = "";
        this.mMiguCurQuality = "";
        // 【2026-10-07】换台/重播同样作废畅享轴（新频道若是畅享源，面板打开时会惰性重建）；
        //  手动切线路/清晰度走 playUrlInternal，不经此处，轴与当前选择得以保留。
        this.mChanxiangAxis = com.tv.live.chanxiang.ChanxiangStreamAxis.empty();
        this.mChanxiangCurLine = "";
        this.mChanxiangCurQuality = "";
        this.mChanxiangAxisTvId = 0;
        this.mChanxiangAxisAtMs = 0L;
        if (channel != null && TextUtils.isEmpty(this.currentChannelName)) {
            this.currentChannelName = channel.getName();
        }
        cancelRetry();
        retryCount = 0;
        chanxiangRefreshCount = 0;
        renyiboRefreshCount = 0;
        lastRenyiboRefreshAtMs = 0;
        miguRefreshCount = 0;
        lastMiguRefreshAtMs = 0;
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

        // 起播前重校验（幂等、静默）

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
                autoRetry("虎牙房间号格式错启: " + url);
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
                autoRetry("虎牙房间号格式错启: " + url);
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
        // 【2026-09-29 修复 · 自研解析器在 uid 通道上 100% 失败的根因】
        //   原实现直接 playHuyaStreamDual(uid, 0)，于是 effectiveRoomId 走了 "(int) uid" 分支。
        //   uid 是 64 位（如 1099531785141），(int) 截断后得到完全不相关的房间号
        //   （1099531785141 → 20157365），mp profileRoom 查询返回 422「该主播不存在」，
        //   自研解析器拿不到任何候选地址 —— 日志里
        //   「走自研解析器, roomId=20157365」+「自研解析失败: 无候选地址」正是如此。
        //   正确做法：房间号一律用频道自带的 profileRoom。
        int roomId = 0;
        if (currentChannel != null) {
            long chRoom = currentChannel.getHuyaRoomId();
            if (chRoom > 0 && chRoom <= Integer.MAX_VALUE) roomId = (int) chRoom;
        }
        if (roomId > 0) {
            LogBridge.i(TAG, "【虎牙】uid 通道改用频道 profileRoom 解析, uid=" + uid + " profileRoom=" + roomId);
        }
        playHuyaStreamDual(uid, roomId, initialSeekPosition);
    }

    private void playHuyaStreamDual(final long uid, final int roomId, long initialSeekPosition) {
        // 解析一律走自研 HuyaParser（纯 HTTP）。
        // 说明：effectiveRoomId 兼作"换台身份键"（第 2301 行用它决定是否丢弃上一房间的存活流列表），
        //   必须保持对同一 uid 稳定；uid 超出 int 时 (int) 截断值虽然是假房间号，但作为身份键是稳定的，
        //   因此这里保持原样。真正的"房间号"(用来查 mp) 由下面的 selfParseRoom 单独把关。
        final int effectiveRoomId = (roomId > 0 ? roomId : (int) uid);

        final int cacheKey = (uid > 0 ? (int) -uid : roomId);
        // 【2026-09-27 换台卡顿专项】换到别的房间时必须丢掉上一个房间的存活流列表：
        //   probeAliveStreamsAsync 只在"探活完成时房间未变"才写入，但写入后列表会一直留着。
        //   若不清理，新房间探活还没回来时 403，pickDifferentLineStream 可能挑中
        //   上一房间的线路地址（播成别的频道），新的"无备用线路"判定也会基于过期数据。
        //   同一房间的重试（roomId 未变）不清，切线路仍需要它。
        if (mHuyaRoomId != effectiveRoomId) {
            synchronized (this) {
                mLastAliveStreams = null;
                // 【2026-09-29 自研解析】换房间时清 HTTP 解析失败标记，新房间重新走自研解析器
                mHttpParseFailedRoomId = 0;
            }
        }
        mHuyaRoomId = effectiveRoomId;
        // 【2026-09-19 404 升级】注意：这里【不】重置 mHuya404Streak——
        // 404 重试也会走到本方法（playHuyaStream），若在此清零则 streak 永远达不到阈值。
        // streak 只在：① 404 切线路成功时；② STATE_READY（播放稳定）时清零。
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

        // 【2026-09-29 改 · 自研解析器优先】虎牙播放的解析一律先走自研 HuyaParser
        // （多源取流信息 → 复刻 SDK getSAntiCode 签名 → 真实探活），全程不依赖 SDK。
        // HuyaParser 会保证返回的地址「真的能播」：200 + 含 #EXTINF 的 m3u8 + 分片可下，
        // 因此可规避 tx 节点的「无上游流」占位 playlist 与 al 节点的 403。
        // 用 mHttpParseFailedRoomId 去重，避免重复解析成环。
        final boolean selfParseAlreadyTried = (mHttpParseFailedRoomId == effectiveRoomId);
        // 【2026-09-29 修复】自研解析器只能按"真实 profileRoom"查 mp 接口。
        //   绝不能拿 uid 当房间号：uid 是 64 位，(int) 截断后得到的是毫不相干的假房间号
        //   （1099531785141 → 20157365），查询必返回 422「该主播不存在」，
        //   于是自研解析器每次都以"无候选地址"收场 —— 这正是
        //   日志里「走自研解析器, roomId=20157365」+「自研解析失败: 无候选地址」的成因。
        //   优先级：显式传入的 roomId > 频道自带的 profileRoom；都没有就放弃自研解析。
        int selfParseRoom = roomId > 0 ? roomId : 0;
        if (selfParseRoom <= 0 && currentChannel != null) {
            long chRoom = currentChannel.getHuyaRoomId();
            if (chRoom > 0 && chRoom <= Integer.MAX_VALUE) selfParseRoom = (int) chRoom;
        }
        if (selfParseAlreadyTried) {
            selfParseRoom = 0;
        }
        if (!selfParseAlreadyTried && selfParseRoom > 0) {
            final int selfRoomId = selfParseRoom;
            final long selfParseTs = System.currentTimeMillis();
            LogBridge.i(TAG, "【虎牙】走自研解析器（不依赖 SDK）, roomId=" + selfRoomId);
            HuyaStreamPlayer.HuyaParser.parse(selfRoomId,
                    new HuyaStreamPlayer.HuyaParser.OnParseResultListener() {
                        /** 【2026-09-29】自研解析带出的「线路 × 清晰度」矩阵（在房间校验通过后填充 UI 可选项） */
                        private HuyaStreamPlayer.StreamMatrix pendingMatrix;

                        @Override
                        public void onSuccess(HuyaStreamPlayer.StreamMatrix matrix,
                                              String hlsUrl, String flvUrl) {
                            pendingMatrix = matrix;
                            onSuccess(hlsUrl, flvUrl, true);
                        }

                        @Override
                        public void onSuccess(final String hlsUrl, final String flvUrl, boolean isTogetherWatch) {
                            mHandler.post(() -> {
                                if (mHuyaRoomId != effectiveRoomId) {
                                    LogBridge.d(TAG, "【虎牙】自研解析返回时房间已切换，丢弃 roomId=" + effectiveRoomId);
                                    return;
                                }
                                final String httpUrl = !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
                                if (TextUtils.isEmpty(httpUrl)) {
                                    LogBridge.w(TAG, "【虎牙】自研解析返回空地址, roomId=" + effectiveRoomId);
                                    mHttpParseFailedRoomId = effectiveRoomId;
                                    Toast.makeText(context, "虎牙解析失败（空地址）", Toast.LENGTH_SHORT).show();
                                    if (sourceFailedListener != null) sourceFailedListener.onSourceFailed();
                                    return;
                                }
                                long costMs = System.currentTimeMillis() - selfParseTs;
                                LogBridge.i(TAG, "⚡【虎牙自研解析】" + costMs + "ms 拿到已探活播放地址, roomId="
                                        + effectiveRoomId + " url=" + httpUrl.substring(0, Math.min(70, httpUrl.length())));
                                mPendingPlaybackHeaders = null;
                                mHuyaDirectPlay = true;
                                // 【2026-09-29 修复「线路/清晰度选择不显示」】
                                // 自研解析器此前只给一个地址，这里把 variantList/备份源清空 →
                                // 设置里的「频道线路选择」只剩「主源」、「清晰度选择」直接弹
                                // 「当前直播源不支持清晰度切换」。现在解析器已回传完整矩阵，
                                // 用它填充线路表 + 清晰度档位（TX 线路取 master 的 STREAM-INF 子条目）。
                                applyHuyaMatrix(pendingMatrix, httpUrl);
                                doPlay(httpUrl, initialSeekPosition);
                                startProbeWatch(httpUrl, effectiveRoomId);
                            });
                        }

                        @Override
                        public void onFailed(final String errorMsg) {
                            LogBridge.w(TAG, "【虎牙】自研解析失败: " + errorMsg);
                            mHandler.post(() -> {
                                if (mHuyaRoomId != effectiveRoomId) return;
                                mHttpParseFailedRoomId = effectiveRoomId;
                                Toast.makeText(context, "虎牙解析失败: " + errorMsg, Toast.LENGTH_SHORT).show();
                                if (sourceFailedListener != null) sourceFailedListener.onSourceFailed();
                            });
                        }
                    });
            return;
        }
        // 自研解析已失败过（或无可用房间号）→ 直接判失败。
        LogBridge.w(TAG, "【虎牙】自研解析不可用（已失败过或无房间号）, roomId=" + effectiveRoomId);
        mHandler.post(() -> {
            if (mHuyaRoomId != effectiveRoomId) return;
            Toast.makeText(context, "虎牙解析失败，请稍后重试", Toast.LENGTH_SHORT).show();
            if (sourceFailedListener != null) {
                sourceFailedListener.onSourceFailed();
            }
        });
    }

    // =====【2026-10-08 恢复】以下成员被 2026-10-07 task57 误删（SDK 块删除波及），从 10.8 备份原样恢复 =====

    private void doPlay(String url, long initialSeekPosition) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();

            String lowerUrl = playUrl.toLowerCase(Locale.ROOT);
            boolean isRealStream = isHlsUrl(playUrl) || looksLikeProgressiveMedia(lowerUrl);
            // 【2026-10-07】追光影界 VOD 鉴权链地址（http://zgyj_vod/…）既不是媒体流、
            //   也不是「频道线路表」里的地址 —— 必须原样交给咪咕解析链，不能被下面的
            //   「频道主地址覆盖」逻辑替换掉（否则回看/直选该地址时会被换成本频道主源）。
            boolean isZgyjVod = com.tv.live.migu.source.ZgyjVodApi.isZgyjVodUrl(playUrl);
            // 【2026-10-07】云图回看合成源（yuntulunbo://<tvId>）同理：它只由本类内部生成
            //   （M3U/线路表里不存在），必须原样交给咪咕解析链。
            boolean isYunTuLb = com.tv.live.migu.source.CarouselApi.isYunTuLookBackUrl(playUrl);
            // 【2026-10-07】源5（爱其意）待解析地址，两种形态：
            //   ① renyibo://<apiId> 协议串（新 M3U 行地址，参照咪咕 nowtv:// 模式）；
            //   ② 110.42.52.180 的 php 302 跳转器（旧缓存 M3U 遗留，兼容处理）。
            //   都不是真实流地址，不能被「频道线路表」覆盖，须先解析落地再播。
            boolean isRenyiboScheme =
                    com.tv.live.renyibo.RenYiBoSourceLoader.isRenyiboSchemeUrl(playUrl);
            boolean isRenyiboPending = isRenyiboScheme
                    || com.tv.live.renyibo.RenYiBoSourceLoader.isPendingResolveUrl(playUrl);
            LogBridge.d(TAG, "doPlay: url=" + playUrl.substring(0, Math.min(100, playUrl.length())) + " isHls=" + isHlsUrl(playUrl) + " isRealStream=" + isRealStream);

            String finalUrl;
            // 【2026-10-07】mMiguDirectPlay：咪咕切线路/切清晰度时置位 —— 本次起播必须
            // 直接采用传入的源串，不能被「频道线路表」覆盖（咪咕频道 backupUrls 不含这些源）。
            if (currentChannel != null && !isRealStream && !isZgyjVod && !isYunTuLb
                    && !isRenyiboPending && !mHuyaDirectPlay && !mMiguDirectPlay) {
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
            // ★【2026-10-08】普通源（内置源4 影视等）起播前 DNS 探活换线：
            //   所选线路域名解析失败（采集站 CDN 死域名）时自动换到可解析线路。
            //   主线程只读 DNS 缓存（零阻塞）：已知失效 → 立即换线；已知活着 → 原样；
            //   无记录 → 照常起播所选线路，同时后台预热 DNS 缓存 —— 预热判死且播放器
            //   尚未就绪时立即接管（远快于 ExoPlayer 自身 UnknownHostException 报错），
            //   且下次起播同一频道直接命中缓存秒判。
            if (currentChannel != null && !mHuyaDirectPlay && !isHuyaSource(finalUrl)
                    && (finalUrl.startsWith("http://") || finalUrl.startsWith("https://"))) {
                final java.util.List<String> lineCands =
                        buildLineCandidatesForChannel(finalUrl, currentChannel);
                String picked = com.tv.live.migu.source.CarouselApi
                        .pickResolvableCandidateCached(lineCands);
                if (picked != null && !picked.equals(finalUrl)) {
                    LogBridge.w(TAG, "【DNS 探活】线路域名已知失效 → 换线："
                            + finalUrl.substring(0, Math.min(70, finalUrl.length()))
                            + " → " + picked.substring(0, Math.min(70, picked.length())));
                    finalUrl = picked;
                } else if (picked == null) {
                    final String wantedUrl = finalUrl;
                    com.tv.live.migu.source.CarouselApi.warmHostAliveAsync(lineCands, () -> {
                        String p = com.tv.live.migu.source.CarouselApi
                                .pickResolvableCandidateCached(lineCands);
                        if (p == null || p.equals(wantedUrl)) return;
                        mHandler.post(() -> {
                            // 接管条件：用户没换台、仍停在这条死链上、播放器还没就绪
                            if (!TextUtils.equals(currentUrl, wantedUrl)) return;
                            boolean notReady = player == null
                                    || player.getPlaybackState()
                                            != androidx.media3.common.Player.STATE_READY;
                            if (!notReady) return;
                            LogBridge.w(TAG, "【DNS 探活】预热判定线路域名失效 → 接管换线："
                                    + wantedUrl.substring(0, Math.min(70, wantedUrl.length()))
                                    + " → " + p.substring(0, Math.min(70, p.length())));
                            doPlay(p, 0);
                        });
                    });
                }
            }
            currentUrl = finalUrl;

            // ★内置源5（爱其意）：M3U 里存的是 renyibo://<apiId> 协议串（或旧缓存的
            //   php 跳转地址），起播这一刻才解析真实落地地址（参照咪咕惰性解析模式），
            //   列表生成期零 CDN 请求。
            if (isRenyiboPending) {
                resolveRenyiboAndPlay(finalUrl, initialSeekPosition);
                return;
            }

            // ★内置源6（咪咕）：M3U 里存的是待解析地址 miguhd://<pID> 或 nowtv://<tvId>，
            //   在起播这一刻才换成带签名的真实 m3u8，避免预解析导致签名过期 / 批量请求。
            //   轮播 / 回看（tvnow://、lunbolbk://、lunboParser_…）同属咪咕解析链，一并分流。
            if (com.tv.live.migu.MiguUrlResolver.isMiguUrl(finalUrl)
                    || com.tv.live.migu.MiguSourceLoader.isNowTvUrl(finalUrl)
                    || com.tv.live.migu.source.CarouselApi.isCarouselUrl(finalUrl)
                    || com.tv.live.migu.source.CarouselApi.isLookBackUrl(finalUrl)
                    || com.tv.live.migu.source.CarouselApi.isLunboParserUrl(finalUrl)
                    || com.tv.live.migu.source.CarouselApi.isYunTuLookBackUrl(finalUrl)
                    || com.tv.live.migu.source.ZgyjVodApi.isZgyjVodUrl(finalUrl)) {
                resolveMiguAndPlay(finalUrl, initialSeekPosition);
                return;
            }

            if (mHuyaDirectPlay || isHuyaSource(finalUrl)) {
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

    /**
     * 是否为「整段式」可直接播放的媒体地址（非 HLS）。
     *
     * <p>用于 {@link #doPlay} 判定 {@code isRealStream}：为 true 时说明调用方给的
     * <b>已是真实流地址</b>，不能再用「频道线路表」里的待解析地址覆盖它 ——
     * 否则会形成
     * {@code doPlay(真实地址) → 取频道主地址(nowtv://…) → resolveMiguAndPlay → doPlay(真实地址) → …}
     * 的死循环。
     *
     * <p>背景：源6(咪咕) 剧场类频道走轮播接口，拿到的直接是
     * {@code https://hlsbkmgspvod.miguvideo.com/…/xxx_56.mp4} 这种 mp4 点播文件。
     */
    private static boolean looksLikeProgressiveMedia(String lowerUrl) {
        if (TextUtils.isEmpty(lowerUrl)) return false;
        if (lowerUrl.contains(".flv.huya.com/") || lowerUrl.contains(".hls.huya.com/")) return true;
        if (lowerUrl.startsWith("http")
                && (lowerUrl.contains("/src?ws") || lowerUrl.contains("&wssecret="))) return true;
        int q = lowerUrl.indexOf('?');
        String path = q > 0 ? lowerUrl.substring(0, q) : lowerUrl;
        return path.endsWith(".mp4") || path.endsWith(".m4s")
                || path.endsWith(".ts") || path.endsWith(".mpd")
                || path.endsWith(".mkv") || path.endsWith(".avi")
                || path.endsWith(".flv") || path.endsWith(".mov");
    }

    public boolean isHuyaSource(String url) {        if (url != null && (url.startsWith("huya://")
                || url.contains(".huya.com/") || url.contains("huya.com/src"))) {
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
        final long __tPrep = System.currentTimeMillis();
        new Thread(() -> {
            try {
                LogBridge.i(TAG, "⏱️[起播准备] +" + (System.currentTimeMillis() - __tPrep) + "ms 线程体开始");
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                LogBridge.i(TAG, "⏱️[起播准备] +" + (System.currentTimeMillis() - __tPrep) + "ms SharedPreferences 读取完成");

                RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();

                Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
                LogBridge.i(TAG, "⏱️[起播准备] +" + (System.currentTimeMillis() - __tPrep) + "ms NetUtil.getInstance()+createCommonHeaders 完成");
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
                        // 🎯【2026-09-19 深挖】readTimeout 8000→4000ms：
                        //   实测 12:54:54 播放中卡顿 4.3s，分片 TTFB 全程 126-275ms 稳定，
                        //   缓冲见底后播放器空等 ~4s 才恢复拉取——readTimeout 过大导致
                        //   缓冲中播放器等待当前请求直到超时判定才重建。虎牙分片实测
                        //   TTFB <300ms，4000ms 余量充足，把"缓冲后恢复"从 ~4s 压到 ~2s。
                        .setConnectTimeoutMs(5000)
                        .setReadTimeoutMs(4000);

                MediaItem mediaItem = MediaItem.fromUri(url);
                MediaSource mediaSource;
                if (isHlsUrl(url)) {
                    // 🎯 HLS 直播流优化：setAllowChunklessPreparation(true) 让 m3u8
                    //   解析完即发 prepare，不必等分片下载，节省 1-2s 起播延迟
                    // 链路：畅享EXTINF归一化 → HTTP
                    // （2026-09-15 去掉并行预取，切台后直接单连接播放）
                    HlsMediaSource.Factory hlsFactory = new HlsMediaSource.Factory(
                            new ChanxiangM3u8DataSource.Factory(httpFactory))
                            .setAllowChunklessPreparation(true);
                    mediaSource = hlsFactory.createMediaSource(mediaItem);
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(httpFactory, TvExtractorsFactory.getInstance()).createMediaSource(mediaItem);
                }

                synchronized (reusableHeaderMap) {
                    reusableHeaderMap.clear();
                    reusableHeaderMap.putAll(localHeaders);
                }

                LogBridge.i(TAG, "⏱️[起播准备] +" + (System.currentTimeMillis() - __tPrep) + "ms MediaSource/Player 配置完成，即将 prepare");
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

                RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();

                Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(url);
                Map<String, String> localHeaders = new HashMap<>();
                for (String name : globalHeaders.names()) {
                    localHeaders.put(name, globalHeaders.get(name));
                }
                localHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");

                // 【2026-09-29 更正】此前这里写"虎牙 CDN 不发 Referer（实测带 Referer 会被 403）"，
                // **结论与事实不符**：
                //   ① createCommonHeaders 只对非 huya 域名赋值 huyaReferer；huya.com/douyu/bdstatic
                //      的 Referer/Origin 由 NetUtil.createCommonHeaders 自己注入（huya.com →
                //      Referer=https://www.huya.com/ + Origin=https://www.huya.com），本分支**并未移除**它。
                //      也就是说虎牙 CDN 请求一直带着 Referer，且播放正常。
                //   ② 设备侧请求头矩阵复测（脚本 _probe_hdr_matrix.py，SDK ctype 下 3 房间 × 6 连拉）：
                //      裸请求 / 带 Referer / 带 Origin / 两者都带 —— **四种组合全部 200，无差异**。
                //   ③ 真正的病根是 antiCode 里的 ctype（见 HuyaStreamPlayer.SDK_CTYPE），与请求头无关。
                // 故此处保持现有行为（虎牙 CDN 不额外覆盖 Referer，沿用 createCommonHeaders 注入值）。
                String huyaReferer = roomId > 0 ? "https://www.huya.com/" + roomId : "https://www.huya.com/";
                final boolean huyaCdn = HuyaStreamPlayer.isHuyaCdnUrl(url);
                if (huyaCdn) {
                    LogBridge.d(TAG, "【虎牙源】虎牙CDN：沿用 createCommonHeaders 注入的 Referer/Origin"
                            + "（实测请求头与 403 无关，病根是 antiCode 的 ctype）");
                } else {
                    localHeaders.put("Referer", huyaReferer);
                }

                localHeaders.put("Accept", "*/*");
                localHeaders.put("Accept-Language", "zh-CN,zh;q=0.9");
                localHeaders.put("Accept-Encoding", "identity");
                localHeaders.put("Connection", "keep-alive");
                dLog("【虎牙源】已启用浏览器UA" + (huyaCdn ? "" : "+Referer(" + huyaReferer + ")") + " 防盗链头");

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

                // 【虎牙】SDK 已经解析出直连流地址，播放器不再自己解析重定向：
                // 交给 HttpURLConnection 在系统层跟随 3xx（省掉手写循环里每跳
                // disconnect+重建+重设头），并把真实落地地址回传缓存，下次起播零跳。
                httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                        .setAllowCrossDomainRedirects(true)
                        .setAllowCrossProtocolRedirects(true)
                        .setFollowRedirectsWithHeaders(true)
                        .setIgnoreSslErrorRedirect(sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false))
                        // 🎯【2026-09-19 深挖】readTimeout 8000→4000ms（同 doPlayNormal 说明）：
                        //   缓冲见底后播放器空等 readTimeout 才恢复拉取，8s 会把卡顿拉长到 4s+；
                        //   虎牙分片 TTFB 实测 <300ms，4000ms 足够，恢复更快。
                        .setConnectTimeoutMs(5000)
                        .setReadTimeoutMs(4000)
                        .setNativeRedirectFollow(true)
                        .setFinalUrlListener((originalUrl, finalUrl) -> {
                            if (originalUrl == null || finalUrl == null) return;
                            if (finalUrl.equals(originalUrl)) return;
                            // 只缓存「主地址」(m3u8/flv 清单)的落地地址，分片地址不入缓存，避免撑爆 Map
                            if (!originalUrl.equals(mLastHuyaFinalUrlKey)) return;
                            if (sHuyaFinalUrlCache.size() > 64) sHuyaFinalUrlCache.clear();
                            sHuyaFinalUrlCache.put(originalUrl, new HuyaFinalUrl(finalUrl));
                            LogBridge.d(TAG, "【虎牙】记录最终直连地址(下次起播零跳): "
                                    + finalUrl.substring(0, Math.min(80, finalUrl.length())));
                        });

                MediaItem mediaItem;
                MediaSource mediaSource;
                if (isHlsUrl(url)) {
                    // 🎯 HLS 直播流优化：setAllowChunklessPreparation(true) 节省 1-2s 起播延迟
                    //
                    // 【2026-09-19 虎牙轮播卡顿专项·live edge 加厚】
                    // 背景：ExoPlayer 直播默认 targetOffset = 3×分片时长（约 5-6s），
                    // 且 LoadControl 的 minBuffer 对 live 流被 live edge 压住，缓冲根本攒不满
                    // → 虎牙公网 CDN 的 TTFB 空窗（median 179ms/尖刺数秒）很快见底 → 周期性卡顿。
                    // 解决：对虎牙 HLS 显式设置 targetOffset=20s（落后 live edge 20s 播放），
                    // 播放器有足够空间把缓冲水位拉满（配合 minBuffer 12s / maxBuffer 60s），
                    // 吸收 TTFB 波动。代价是直播延迟 +20s——电视点播/轮播场景可接受。
                    // 注意：不设 min/maxPlaybackSpeed（保持 UNSET），media3 会禁用变速追帧，
                    // 不会出现 0.95x 慢放拉长音画时间感（09-19 早期方案的教训）。
                    mediaItem = new MediaItem.Builder()
                            .setUri(url)
                            .setLiveConfiguration(
                                    new MediaItem.LiveConfiguration.Builder()
                                            .setTargetOffsetMs(20000)
                                            .build())
                            .build();
                    HlsMediaSource.Factory hlsFactory = new HlsMediaSource.Factory(
                            new ChanxiangM3u8DataSource.Factory(httpFactory))
                            .setAllowChunklessPreparation(true);
                    mediaSource = hlsFactory.createMediaSource(mediaItem);
                } else {
                    mediaItem = MediaItem.fromUri(url);
                    mediaSource = new ProgressiveMediaSource.Factory(httpFactory, TvExtractorsFactory.getInstance()).createMediaSource(mediaItem);
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
                    LogBridge.w(TAG, "【普通源】主播放列表请求失败: code=" + code);
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
                    LogBridge.d(TAG, "【虎牙源】手动重定向住: " + newUrl);
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
                    LogBridge.w(TAG, "【虎牙源】主播放列表请求失败: code=" + code);

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
            LogBridge.w(TAG, "未解析到任何清晰度流，可能是直播源本身不支持多码率或网络被墙");
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

        // 【2026-10-07】咪咕频道优先：返回「线路 × 清晰度」轴的清晰度名（降序，高清在前）。
        //   咪咕源是待解析协议串，不能进 variantList（会被 doPlayNormal 的 master 解析清空），
        //   故单独用咪咕轴承载。
        List<String> miguQualities = getMiguQualityNames();
        if (!miguQualities.isEmpty()) return miguQualities;

        // 【2026-10-07】畅享频道：同样返回轴的清晰度名（降序，高清在前），口径与咪咕一致
        List<String> cxQualities = getChanxiangQualityNames();
        if (!cxQualities.isEmpty()) return cxQualities;

        Channel ch = getCurrentChannel();
        boolean scoped = isScopedHuyaLines(ch);
        int curLine = currentLineIndexForUi(ch);
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                // 【2026-09-29 修复「线路/清晰度显示错位」】虎牙频道原先把三条总线路的档位**混装**成一张表，
                // 于是「选 蓝光4M」实际切到的是 AL/HS 的地址，而线路那行还写着 TX —— 显示与实际不符。
                // 现在按当前生效总线路收敛：清晰度列表 = 该线路自己的档位。非虎牙频道行为不变。
                if (scoped && v.huyaLineIndex >= 0 && v.huyaLineIndex != curLine) continue;
                String label = v.getDisplayLabel();
                if (!resolutions.contains(label)) {
                    resolutions.add(label);
                }
            }
        }
        return resolutions;
    }

    /** 虎牙频道且已知总线路表 → 线路 / 清晰度两个列表都要按"当前总线路"收敛 */
    private boolean isScopedHuyaLines(Channel ch) {
        if (ch == null) return false;
        if (!isHuyaSource(ch.getMainPlayUrl())) return false;
        List<String> labels = ch.getHuyaLineLabels();
        return labels != null && !labels.isEmpty();
    }

    /** 当前生效的虎牙总线路（返回 lineIndex，不是列表下标） */
    private int currentLineIndexForUi(Channel ch) {
        // ① 首选 mCurrentHuyaLineIndex —— 自研路径 = 采用线路；SDK 路径 = streamHolder[0] 的线路，
        //    这才是"实际在播哪条线路"的唯一权威来源。
        List<Integer> order = huyaLineOrder();
        if (order.contains(Integer.valueOf(mCurrentHuyaLineIndex))) return mCurrentHuyaLineIndex;
        // ② 兜底：variantList 的首条（自研路径下恒为当前线路）
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                if (v.huyaLineIndex >= 0) return v.huyaLineIndex;
            }
        }
        return mCurrentHuyaLineIndex;
    }

    /**
     * 虎牙总线路顺序 = {@code variantList} 里 {@code huyaLineIndex} 首次出现的顺序。
     * 该顺序与 {@link #getAvailableLines()} / {@link Channel#getHuyaLineLabels()} 严格同序，
     * 因此「频道线路选择」里的第 which 项 ⇄ 本列表第 which 个 lineIndex。
     */
    private List<Integer> huyaLineOrder() {
        synchronized (variantListLock) {
            if (!mHuyaLineOrder.isEmpty()) return new ArrayList<>(mHuyaLineOrder);
            // 兜底：解析器没写顺序表时按 variantList 首次出现顺序推（仅用于老数据/异常兜底）
            List<Integer> order = new ArrayList<>();
            for (Variant v : variantList) {
                if (v.huyaLineIndex >= 0 && !order.contains(v.huyaLineIndex)) order.add(v.huyaLineIndex);
            }
            return order;
        }
    }

    /** 与 {@link #getAvailableLines()} 同序：每条线路各自的清晰度档列表（仅日志/排查用） */
    public List<List<String>> getHuyaSubLineLists() {
        List<List<String>> out = new ArrayList<>();
        Channel ch = getCurrentChannel();
        if (!isScopedHuyaLines(ch)) return out;
        List<Integer> order = huyaLineOrder();
        if (order.isEmpty()) return out;
        synchronized (variantListLock) {
            for (Integer li : order) {
                List<String> quals = new ArrayList<>();
                for (Variant v : variantList) {
                    if (v.huyaLineIndex != li) continue;
                    String label = v.getDisplayLabel();
                    if (!quals.contains(label)) quals.add(label);
                }
                out.add(quals);
            }
        }
        return out;
    }



    /**
     * 「频道线路选择」弹窗的当前选中项下标（0 基）。
     * 虎牙频道按真实生效线路算，避免 SP 记忆值与实际播放线路不一致时打错勾；其余情况返回 -1（由调用方回退 SP）。
     */
    public int getCurrentHuyaLineUiIndex() {
        Channel ch = getCurrentChannel();
        if (!isScopedHuyaLines(ch)) return -1;
        List<Integer> order = huyaLineOrder();
        if (order.isEmpty()) return -1;
        int idx = order.indexOf(Integer.valueOf(mCurrentHuyaLineIndex));
        return idx < 0 ? 0 : idx;
    }

    /** 注册「线路/清晰度矩阵就绪」回调（设置面板用；传 null 解除） */
    public void setOnStreamOptionsReadyListener(Runnable r) {
        mStreamOptionsReadyListener = r;
    }

    private void notifyStreamOptionsReady() {
        final Runnable r = mStreamOptionsReadyListener;
        if (r == null) return;
        if (mHandler != null) {
            mHandler.post(r);
        } else {
            r.run();
        }
    }

    /** 码率 → 估算高度（仅用于 Variant.height / 排序兜底；展示名以服务端 displayName 为准） */
    private static int heightForBitRate(int kbps) {
        if (kbps >= 6000) return 1080;
        if (kbps >= 4000) return 1080;
        if (kbps >= 2000) return 720;
        if (kbps >= 1000) return 480;
        return 360;
    }

    /**
     * 【2026-09-29 新增】用自研解析回传的「线路 × 清晰度」矩阵填充 UI 可选项。
     *
     * 修复的问题：自研解析器此前只回传一个已探活地址，本方法原来那两行
     * （{@code currentResolutionLabel=""; variantList.clear();}）会把可选项清空 →
     * 设置里的「频道线路选择」只剩「主源」、「清晰度选择」直接弹「当前直播源不支持清晰度切换」。
     *
     * 填充三处（与 SDK 解析路径的口径一致）：
     *  ① {@code variantList}：每个「线路×清晰度」一个 Variant，带 huyaLineIndex 供换线路时重排；
     *  ② 频道线路标签（{@code setHuyaLineLabels}）+ 备份源：供「频道线路选择」展示与切换；
     *  ③ {@code currentResolutionLabel}：当前实际在播的档位（设置里「清晰度选择」的默认选中项）。
     */
    private void applyHuyaMatrix(HuyaStreamPlayer.StreamMatrix mx, String adoptedUrl) {
        if (mx == null || mx.lines == null || mx.lines.isEmpty()) {
            // 解析器没给出矩阵（理论上只在极端兜底路径）→ 保持旧行为，至少别留脏数据
            currentResolutionLabel = "";
            synchronized (variantListLock) { variantList.clear(); mHuyaLineOrder.clear(); }
            return;
        }

        List<Variant> variants = new ArrayList<>();
        List<String> lineLabels = new ArrayList<>();
        List<String> lineUrls = new ArrayList<>();
        List<Integer> lineOrderTmp = new ArrayList<>();

        // 【2026-09-29 修正】线路列表的展示口径对齐「用户预期」的 线路1 / 线路2 / 线路3：
        //   顺序 = 按 lineIndex 升序（对齐 SDK LineListFragment#lineIndexsSort 的升序）；
        //   标签 =「线路」+ 升序**序号**（1..N），即在升序列表里的位次 +1。
        //   为什么**不能**照抄 SDK 的 String.format("线路%s", lineIndex) 填原值：
        //   SDK 自己的 lineIndex 是连续序号，而 mp 接口下发给我们的 lo.lineIndex 是
        //   **CDN 编码**（实测 AL=3 / TX=5 / HS=14），直接当 %s 填进去会显示成
        //   「线路3 / 线路5 / 线路14」，与 UI 上"第几条线路"的语义完全不符。
        //   列表里**只有线路**（一个纯 Text），不含 CDN/清晰度信息
        //   —— 清晰度在 SDK 里是另一个列表（mItemRateList），对应我们的「清晰度选择」按钮。
        List<HuyaStreamPlayer.LineOption> sortedLines = new ArrayList<>(mx.lines);
        java.util.Collections.sort(sortedLines, new java.util.Comparator<HuyaStreamPlayer.LineOption>() {
            @Override public int compare(HuyaStreamPlayer.LineOption a, HuyaStreamPlayer.LineOption b) {
                return Integer.compare(a.lineIndex, b.lineIndex);
            }
        });

        int lineSeq = 0;
        for (HuyaStreamPlayer.LineOption lo : sortedLines) {
            if (lo == null) continue;
            lineSeq++;
            // 与 mHuyaLineOrder 严格同序，保证「列表第 N 项 ⇄ 第 N 条线路 ⇄ 打勾位置」三处对齐。
            lineLabels.add("线路" + lineSeq);
            lineOrderTmp.add(lo.lineIndex);
            String lineRepresentative = null;
            for (HuyaStreamPlayer.QualityOption q : lo.qualities) {
                if (q == null || TextUtils.isEmpty(q.hlsUrl)) continue;
                Variant v = new Variant(q.hlsUrl, q.bitRate * 1000, 0, 0);
                v.huyaBitRateDisplayName = q.displayName;
                v.resolutionLabel = q.displayName;          // 展示名以服务端为准（蓝光4M 等）
                v.height = heightForBitRate(q.bitRate);
                v.huyaLineIndex = lo.lineIndex;
                v.huyaBitRate = q.bitRate;
                variants.add(v);
                if (lineRepresentative == null) lineRepresentative = q.hlsUrl;
            }
            lineUrls.add(lineRepresentative != null ? lineRepresentative : adoptedUrl);
        }

        // 【2026-09-29】登记线路展示顺序（与 lineLabels 严格同序），供「线路名 ⇄ 打勾位置」对齐
        synchronized (variantListLock) {
            mHuyaLineOrder.clear();
            mHuyaLineOrder.addAll(lineOrderTmp);
        }

        // 【2026-09-29 新增】登记子线路表（hls.multiLine，不含清晰度），供「频道线路选择」直接列/切。
        // 放在 variants.isEmpty() 的早退之前 —— 即使清晰度矩阵为空，子线路仍然是可播的独立条目。
        synchronized (huyaMultiLineLock) {
            mHuyaMultiLines.clear();
            if (mx.multiLines != null) mHuyaMultiLines.addAll(mx.multiLines);
            // 【2026-09-30】子线路顺序**稳定化**：服务端下发顺序会抖（同一房间几次请求条数/成员都变），
            // 不排序的话「源1」这次是 AL、下次是 TX，用户凭序号记忆会选错，URL 全等匹配也会命中错项。
            // 排序口径沿用已验证的可靠性顺序：TX(5) > HS(14) > AL(3) > 其它
            //   ⚠️ 这个顺序只是**稳定性偏好**，不代表 AL/HS 不能用。旧结论「al 实测 0/6」是在
            //   **原始 ctype=tars_mp 探活口径**下得到的：该口径 CDN 对子播放列表直接 403（AL 全挂、
            //   HS 抖动 20~50%）。经 applySdkCtype（ctype=huya_adr_game_sdk，播放路径与 2026-10-03
            //   修复后的探活路径同口径）实测三条线路全部可用：AL 6/6、TX 48/48、HS 6/6。
            java.util.Collections.sort(mHuyaMultiLines,
                    new java.util.Comparator<HuyaStreamPlayer.MultiLineOption>() {
                        @Override
                        public int compare(HuyaStreamPlayer.MultiLineOption a,
                                           HuyaStreamPlayer.MultiLineOption b) {
                            int ra = multiLineRank(a), rb = multiLineRank(b);
                            if (ra != rb) return Integer.compare(ra, rb);
                            return Integer.compare(a == null ? 0 : a.lineIndex,
                                    b == null ? 0 : b.lineIndex);
                        }
                    });

            // 【2026-09-30 第 4 次修正】补齐**总线路里 multiLine 缺席的 CDN**：
            //   mp 的 hls.multiLine 只覆盖部分总线路（如本频道 11342412：AL/TX/HS 三条总线路，
            //   multiLine 只给了 TX/HS，AL 缺席）。用户期望「频道线路选择」**列全所有总线路**
            //   —— 把每个 mx.lines 里没被 multiLine 覆盖的总线路，用其首个 quality 的成品 URL 补成
            //   一项 subLine。这样弹窗里 AL/TX/HS 都会出现，标签照旧是「源N · CDN」。
            if (mx.lines != null) {
                Set<Integer> have = new HashSet<>();
                for (HuyaStreamPlayer.MultiLineOption m : mHuyaMultiLines) {
                    if (m != null) have.add(m.lineIndex);
                }
                int appended = 0;
                for (HuyaStreamPlayer.LineOption lo : mx.lines) {
                    if (lo == null || have.contains(lo.lineIndex)) continue;
                    String url = null;
                    for (HuyaStreamPlayer.QualityOption q : lo.qualities) {
                        if (q != null && !TextUtils.isEmpty(q.hlsUrl)) {
                            url = q.hlsUrl;
                            break;
                        }
                    }
                    if (TextUtils.isEmpty(url)) {
                        LogBridge.w(TAG, "【虎牙】补齐跳过: 总线路 lineIndex=" + lo.lineIndex
                                + " cdnType=" + lo.cdnType + " 无可用的 quality URL");
                        continue;
                    }
                    mHuyaMultiLines.add(new HuyaStreamPlayer.MultiLineOption(
                            lo.lineIndex, lo.cdnType, url, /*priority*/ -1));
                    appended++;
                    LogBridge.i(TAG, "【虎牙】补齐子线路: lineIndex=" + lo.lineIndex
                            + " cdnType=" + lo.cdnType + " (multiLine 缺席)");
                }
                if (appended > 0) {
                    // 新增后重排一次（TX>HS>AL>其它，同 CDN 按 lineIndex 升序）
                    java.util.Collections.sort(mHuyaMultiLines,
                            new java.util.Comparator<HuyaStreamPlayer.MultiLineOption>() {
                                @Override
                                public int compare(HuyaStreamPlayer.MultiLineOption a,
                                                   HuyaStreamPlayer.MultiLineOption b) {
                                    int ra = multiLineRank(a), rb = multiLineRank(b);
                                    if (ra != rb) return Integer.compare(ra, rb);
                                    return Integer.compare(a == null ? 0 : a.lineIndex,
                                            b == null ? 0 : b.lineIndex);
                                }
                            });
                    LogBridge.i(TAG, "【虎牙】子线路表补齐 +" + appended + " 条，"
                            + "排序后共 " + mHuyaMultiLines.size() + " 条");
                }
            }
        }

        if (variants.isEmpty()) {
            currentResolutionLabel = "";
            synchronized (variantListLock) { variantList.clear(); mHuyaLineOrder.clear(); }
            return;
        }

        // 【2026-10-03 修复 Site 3】原实现无条件 `mCurrentHuyaLineIndex = mx.adoptedLineIndex`，
        //   把用户手动选择的线路每次解析都覆盖回解析器最优。这里改为：用户选过且该线路在矩阵
        //   可用线路中 → 用用户线路（并在该线路内选档），否则维持原行为。
        int effLine = mx.adoptedLineIndex;
        String effQuality = mx.adoptedQuality;
        int userLineD = getUserChosenLine(mHuyaRoomId);
        if (userLineD >= 0 && lineOrderTmp.contains(userLineD)) {
            effLine = userLineD;
            // 该线路内选档：优先沿用当前清晰度，否则取最高码率
            Variant best = null;
            for (Variant v : variants) {
                if (v.huyaLineIndex != userLineD) continue;
                if (!TextUtils.isEmpty(currentResolutionLabel)
                        && currentResolutionLabel.equals(v.getDisplayLabel())) {
                    best = v;
                    break;
                }
                if (best == null || v.huyaBitRate > best.huyaBitRate) best = v;
            }
            if (best != null) effQuality = best.getDisplayLabel();
            LogBridge.i(TAG, "【虎牙】沿用用户上次选择的线路 lineIndex=" + userLineD);
        } else if (userLineD >= 0) {
            LogBridge.i(TAG, "【虎牙】用户线路 lineIndex=" + userLineD + " 本次缺席，回落默认线路");
        }

        // 采用线路的采用档排最前：保证 getAvailableResolutions() 的首项 = 实际在播的档
        List<Variant> ordered = new ArrayList<>();
        for (Variant v : variants) {
            if (v.huyaLineIndex == effLine
                    && effQuality != null && effQuality.equals(v.resolutionLabel)) {
                ordered.add(v);
                break;
            }
        }
        for (Variant v : variants) {
            if (!ordered.contains(v)) ordered.add(v);
        }
        synchronized (variantListLock) {
            variantList.clear();
            variantList.addAll(ordered);
        }

        currentResolutionLabel = !TextUtils.isEmpty(effQuality)
                ? effQuality : ordered.get(0).getDisplayLabel();
        mCurrentHuyaLineIndex = effLine;

        if (currentChannel != null) {
            try {
                // ⚠️ 顺序不能反：Channel.clearBackupUrls() 会**连同 huyaLineLabels 一起清空**，
                // 所以必须先清备份源、再写线路标签。
                currentChannel.clearBackupUrls();
                // 【2026-09-30 定稿】备份源改填**子线路成品地址**（hls.multiLine，已过 applySdkCtype），
                // 不再填各清晰度档的 &ratio= 地址 —— 清晰度是独立轴，由「清晰度选择」负责，
                // 把清晰度档混进「频道线路选择」就是重复。
                // 该房间没有 multiLine 时（罕见）才回落到旧的代表地址。
                List<String> backupSrc = new ArrayList<>();
                if (mx.multiLines != null) {
                    for (HuyaStreamPlayer.MultiLineOption mo : mx.multiLines) {
                        if (mo != null && !TextUtils.isEmpty(mo.url)) backupSrc.add(mo.url);
                    }
                }
                if (backupSrc.isEmpty()) backupSrc = lineUrls;
                for (String u : backupSrc) {
                    if (!TextUtils.isEmpty(u) && !u.equals(adoptedUrl)) currentChannel.addBackupUrl(u);
                }
                currentChannel.setHuyaLineLabels(lineLabels);
            } catch (Throwable t) {
                LogBridge.w(TAG, "【虎牙】填充线路标签/备份源失败: " + t.getMessage());
            }
        }

        LogBridge.i(TAG, "【虎牙】线路/清晰度选项已就绪：采用线路=" + effLine
                + " 清晰度=" + currentResolutionLabel + "，线路数=" + lineLabels.size()
                + " " + lineLabels + "，当前线路清晰度档=" + getAvailableResolutions()
                + "，各线路清晰度档=" + getHuyaSubLineLists());

        // 【2026-10-03 BUG D 新增】子线路表就绪后，后台补探「非当前采用线路」的子线路，
        //   存活者合并进 mLastAliveStreams，供后续 403/404 切线路（pickDifferentLineStream）使用。
        //   背景：起播只快速探过默认流一条，HS/AL 一旦抖动未记录，就永远切不过去。
        startMultiLineProbeAsync(effLine, mHuyaRoomId);

        notifyStreamOptionsReady();
    }

    /**
     * 【2026-10-03 BUG D 新增】后台补探子线路表里「非当前采用线路」的项（2000ms 宽限探活），
     * 存活者按线路合并进 {@code mLastAliveStreams}。全程在独立线程，不阻塞 applyHuyaMatrix。
     */
    private void startMultiLineProbeAsync(final int adoptedLineIndex, final int roomIdAtProbe) {
        final List<HuyaStreamPlayer.MultiLineOption> snapshot = huyaMultiLinesSnapshot();
        if (snapshot.size() <= 1) return;
        new Thread(() -> {
            try {
                List<HuyaSDKParser.HuyaStreamInfo> survivors = new ArrayList<>();
                for (HuyaStreamPlayer.MultiLineOption mo : snapshot) {
                    if (mo == null || TextUtils.isEmpty(mo.url)) continue;
                    if (mo.lineIndex == adoptedLineIndex) continue; // 跳过当前在播线路
                    if (HuyaLiveProbe.probeSingleWithGrace(mo.url)) {
                        HuyaSDKParser.HuyaStreamInfo s = new HuyaSDKParser.HuyaStreamInfo();
                        s.lineIndex = mo.lineIndex;
                        s.lineValue = mo.lineIndex;
                        s.lineLabel = mo.cdnType;
                        s.hlsUrl = mo.url;
                        s.bitRateDisplayName = (mo.cdnType == null ? "?" : mo.cdnType) + "#" + mo.lineIndex;
                        survivors.add(s);
                    }
                }
                if (survivors.isEmpty()) {
                    LogBridge.i(TAG, "【虎牙·子线路补探】非当前线路无存活，候选=" + snapshot.size());
                    return;
                }
                synchronized (TVPlayerManager.this) {
                    if (mHuyaRoomId != roomIdAtProbe) return;
                    mLastAliveStreams = mergeAliveStreams(mLastAliveStreams, survivors);
                }
                LogBridge.i(TAG, "【虎牙·子线路补探】新增存活 " + survivors.size()
                        + " 条，已合并进 mLastAliveStreams");
            } catch (Throwable t) {
                LogBridge.d(TAG, "【虎牙·子线路补探】异常: " + t.getMessage());
            }
        }, "huya-multiline-probe").start();
    }

    // ==================================================================
    // 【2026-10-07 新增】畅享（源3）「线路 × 清晰度」选择 —— 完全参照咪咕轴
    // ==================================================================

    /**
     * 当前频道是否为畅享（源3）http 直链频道。
     *
     * <p>判据取「双特征」降低误判：channelId 为纯数字（= 服务端 tvId，与
     * {@link #tryRefreshChanxiangSource} 同口径）<b>且</b>主地址是带畅享签名
     * （{@code _et=…&_nt=…}）或 ichuanghi CDN 主机的 http(s) 直链。
     * 咪咕频道（mainUrl = nowtv:// 等私有协议）天然不命中。
     */
    public boolean isChanxiangHttpChannel(Channel ch) {
        if (ch == null) return false;
        String id = ch.getChannelId();
        if (id == null || !id.matches("\\d+")) return false;
        String url = ch.getMainPlayUrl();
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase();
        if (!lower.startsWith("http")) return false;
        return lower.contains("ichuanghi.com") || (lower.contains("_et=") && lower.contains("_nt="));
    }

    /** 畅享轴是否已就绪（非空） */
    public boolean hasChanxiangAxis() {
        com.tv.live.chanxiang.ChanxiangStreamAxis axis = mChanxiangAxis;
        return axis != null && !axis.isEmpty();
    }

    /** 畅享线路名列表（空 = 当前不是畅享频道，或轴尚未构建） */
    public List<String> getChanxiangLineNames() {
        com.tv.live.chanxiang.ChanxiangStreamAxis axis = mChanxiangAxis;
        return axis == null ? new ArrayList<>() : new ArrayList<>(axis.lines);
    }

    /** 畅享清晰度名列表（降序，高清在前） */
    public List<String> getChanxiangQualityNames() {
        com.tv.live.chanxiang.ChanxiangStreamAxis axis = mChanxiangAxis;
        return axis == null ? new ArrayList<>() : new ArrayList<>(axis.qualities);
    }

    /** 「频道线路选择」里畅享当前线路的打勾下标（0 基）；无法确定返回 -1 */
    public int getCurrentChanxiangLineUiIndex() {
        com.tv.live.chanxiang.ChanxiangStreamAxis axis = mChanxiangAxis;
        if (axis == null || axis.isEmpty()) return -1;
        return axis.indexOfLine(mChanxiangCurLine);
    }

    /** 畅享当前清晰度展示名（未确定时为「自适应」） */
    public String getCurrentChanxiangQualityLabel() {
        return TextUtils.isEmpty(mChanxiangCurQuality) ? "自适应" : mChanxiangCurQuality;
    }

    /**
     * 惰性构建畅享轴（设置面板打开时调用）。
     *
     * <p>缓存有效（同频道 + TTL 内）直接回调；否则后台发<b>一次</b>
     * {@code get_channel_source}（与咪咕轴同数据源，非批量、用户触发），
     * 完成后切回主线程回调。频道已切换时丢弃结果。
     *
     * @param onReady 主线程回调（轴就绪或缓存命中），供面板刷新两行状态；可为 null
     */
    public void ensureChanxiangAxisAsync(Runnable onReady) {
        Channel ch = currentChannel;
        if (!isChanxiangHttpChannel(ch)) return;
        final int tvId;
        try {
            tvId = Integer.parseInt(ch.getChannelId());
        } catch (Throwable t) {
            return;
        }
        com.tv.live.chanxiang.ChanxiangStreamAxis cached = mChanxiangAxis;
        if (cached != null && !cached.isEmpty()
                && mChanxiangAxisTvId == tvId
                && System.currentTimeMillis() - mChanxiangAxisAtMs < CHANXIANG_AXIS_TTL_MS) {
            if (onReady != null) onReady.run();
            return;
        }
        final String curUrl = ch.getMainPlayUrl();
        new Thread(() -> {
            com.tv.live.chanxiang.ChanxiangStreamAxis built =
                    com.tv.live.chanxiang.ChanxiangStreamAxis.empty();
            try {
                built = com.tv.live.chanxiang.ChanxiangStreamAxis.build(
                        com.tv.live.chanxiang.ChanxiangSourceLoader.fetchHttpSources(tvId));
            } catch (Throwable t) {
                LogBridge.w(TAG, "【畅享轴】构建失败: " + t.getMessage());
            }
            final com.tv.live.chanxiang.ChanxiangStreamAxis axis = built;
            mHandler.post(() -> {
                Channel cur = currentChannel;
                if (cur == null || !isChanxiangHttpChannel(cur)
                        || !String.valueOf(tvId).equals(cur.getChannelId())) {
                    LogBridge.d(TAG, "【畅享轴】构建完成但频道已切换，丢弃 tvId=" + tvId);
                    return;
                }
                mChanxiangAxis = axis;
                mChanxiangAxisTvId = tvId;
                mChanxiangAxisAtMs = System.currentTimeMillis();
                initChanxiangCurFromUrl(curUrl);
                LogBridge.i(TAG, "【畅享轴】tvId=" + tvId + " http源 " + axis.entries.size()
                        + " 条，线路=" + axis.lines + "，清晰度=" + axis.qualities);
                if (onReady != null) onReady.run();
            });
        }, "Chanxiang-Axis").start();
    }

    /** 由「正在播的地址」初始化当前线路/清晰度显示；URL 匹配不上则取最高清晰度档 */
    private void initChanxiangCurFromUrl(String playingUrl) {
        com.tv.live.chanxiang.ChanxiangStreamAxis axis = mChanxiangAxis;
        if (axis == null || axis.isEmpty()) return;
        com.tv.live.chanxiang.ChanxiangStreamAxis.Entry e = axis.findBySource(playingUrl);
        if (e == null) e = axis.bestEntry();
        if (e != null) {
            mChanxiangCurLine = e.lineName;
            mChanxiangCurQuality = e.qualityLabel;
        }
    }

    /**
     * 切换畅享线路（下标口径 = {@link #getChanxiangLineNames()}）。
     * 保持当前清晰度不变；该线路没有这档时用它的首选档。
     */
    public void switchChanxiangLine(int index) {
        com.tv.live.chanxiang.ChanxiangStreamAxis axis = mChanxiangAxis;
        if (axis == null || axis.isEmpty() || index < 0 || index >= axis.lines.size()) {
            LogBridge.w(TAG, "【畅享】线路表为空或下标越界，无法切换：" + index);
            return;
        }
        String line = axis.lines.get(index);
        com.tv.live.chanxiang.ChanxiangStreamAxis.Entry e = axis.find(line, mChanxiangCurQuality);
        if (e == null) {
            LogBridge.w(TAG, "【畅享】该线路无可播源：" + line);
            return;
        }
        applyChanxiangSelection(e.lineName, e.qualityLabel, e.source, "切换线路");
    }

    /**
     * 切换畅享清晰度（口径 = {@link #getChanxiangQualityNames()}）。
     * 保持当前线路不变；该线路没有这档时退化为任意线路的该档。
     */
    public void switchChanxiangQuality(String qualityLabel) {
        com.tv.live.chanxiang.ChanxiangStreamAxis axis = mChanxiangAxis;
        if (axis == null || axis.isEmpty() || TextUtils.isEmpty(qualityLabel)) {
            LogBridge.w(TAG, "【畅享】清晰度表为空或档位为空，无法切换");
            return;
        }
        com.tv.live.chanxiang.ChanxiangStreamAxis.Entry e =
                axis.find(mChanxiangCurLine, qualityLabel);
        if (e == null) {
            LogBridge.w(TAG, "【畅享】无可播源对应清晰度：" + qualityLabel);
            return;
        }
        applyChanxiangSelection(e.lineName, e.qualityLabel, e.source, "切换清晰度");
    }

    /**
     * 落地一次畅享选择：记忆 → 直接播该 http 直链。
     *
     * <p>与咪咕不同<b>无需</b>直接起播标志：畅享源是真实流地址，doPlay 不会用
     * 频道线路表覆盖它；走 {@link #playUrlInternal(String)}（不重置轴与计数，
     * 手动切换不打断当前播放状态机）。播错自动走既有失败链路。
     */
    private void applyChanxiangSelection(String line, String quality, String url, String what) {
        if (TextUtils.isEmpty(url)) return;
        mChanxiangCurLine = line;
        mChanxiangCurQuality = quality;
        currentResolutionLabel = quality;
        LogBridge.i(TAG, "【畅享】" + what + " → " + line + " / " + quality + "：" + url);
        playUrlInternal(url);
    }

    public List<String> getAvailableLines() {
        List<String> lines = new ArrayList<>();
        Channel ch = getCurrentChannel();
        if (ch == null) return lines;

        // 【2026-10-07】咪咕频道优先：直接返回「线路 × 清晰度」轴的线路名
        //   （形态如「咪咕」「央视频」「央视」「CDN直链」），不再退化成「主源 / 源N」。
        List<String> miguLines = getMiguLineNames();
        if (!miguLines.isEmpty()) return miguLines;

        // 【2026-10-07】畅享频道：同样返回「线路 × 清晰度」轴的线路名
        //   （形态如「节点zj500」「节点js1000」= CDN 节点），轴未就绪时回落「主源 / 源N」。
        List<String> cxLines = getChanxiangLineNames();
        if (!cxLines.isEmpty()) return cxLines;

        // 【2026-09-30 定稿·用户最终口径】虎牙频道：「频道线路选择」**只列子线路**，
        //   每项形如「源1 · TX」「源2 · HS」（N 为子线路序号，后缀是它所属的 CDN / 总线路）。
        //   ⚠️ 严格排除两类东西：
        //     ① **总线路本身**（baseSteamInfoList 的 AL/TX/HS）—— 不再有「主源」项，也不列「线路1/2/3」；
        //     ② **清晰度** —— 它是独立轴，由「清晰度选择」按钮负责，混进来就是重复。
        //   数据源直接取子线路表（hls.multiLine）而不是备份源，保证「列表第 N 项 ⇄ 第 N 条子线路」严格对齐
        //   （备份源会剔掉当前在播地址，条数/下标会错位）。
        List<HuyaStreamPlayer.MultiLineOption> mls = huyaMultiLinesSnapshot();
        if (isHuyaSource(ch.getMainPlayUrl()) && !mls.isEmpty()) {
            lines.addAll(getHuyaMultiLineLabels());
            return lines;
        }

        // 该房间没有子线路数据 → 老语义：线路标签（总线路）/ 主源 + 备份源
        List<String> huyaLabels = ch.getHuyaLineLabels();
        if (isHuyaSource(ch.getMainPlayUrl()) && huyaLabels != null && !huyaLabels.isEmpty()) {
            lines.addAll(huyaLabels);
            return lines;
        }

        lines.add("主源");
        List<String> backups = ch.getBackupUrls();
        if (backups != null) {
            for (int i = 0; i < backups.size(); i++) {
                lines.add("源" + (i + 1));
            }
        }
        return lines;
    }

    /* ==================== 【2026-09-29 新增】子线路（hls.multiLine） ==================== */

    /** 取一份子线路表的快照（避免持有锁期间做耗时操作） */
    private List<HuyaStreamPlayer.MultiLineOption> huyaMultiLinesSnapshot() {
        synchronized (huyaMultiLineLock) {
            return new ArrayList<>(mHuyaMultiLines);
        }
    }

    /**
     * 【2026-10-03 修复「手动切线路被轮询覆盖」】记录**用户手动选择的线路**（按房间隔离）。
     *
     * <p>与旧的 {@code huya_line_poll_} 游标彻底分离：游标只是"上次解析落到哪条"的副产品，
     * 每次解析旋转一次，导致用户手选 AL 后切台再回来就被改成 TX。本 key 只在用户手动
     * 切线路时写入（{@link #switchToHuyaMultiLine} / {@link #switchToHuyaLine}），
     * 解析路径只读不写，故用户选择能跨换台稳定保留。
     *
     * @param roomId 虎牙房间号，&gt; 0
     * @param lineIndex 线路编码（AL=3 / TX=5 / HS=14）
     */
    private void setUserChosenLine(int roomId, int lineIndex) {
        if (roomId <= 0 || lineIndex < 0) return;
        SharedPreferences p = sp;
        if (p == null) return;
        p.edit().putInt("huya_user_line_" + roomId, lineIndex).apply();
    }

    /** 读取用户手动选择的线路；未选择过或 prefs 不可用时返回 -1。 */
    private int getUserChosenLine(int roomId) {
        if (roomId <= 0) return -1;
        SharedPreferences p = sp;
        if (p == null) return -1;
        return p.getInt("huya_user_line_" + roomId, -1);
    }

    /**
     * 子线路稳定排序用的优先级：TX(0) > HS(1) > AL(2) > 其它(3)。
     * 先认服务端 {@code sCdnType} 文本，缺失时退回 CDN 编码（AL=3 / TX=5 / HS=14）。
     * 顺序依据：TX &gt; HS &gt; AL 只是**稳定性偏好**，不是可用性判定 —— 2026-09-29 的
     * 「tx.hls 索引 100% 稳定，hs 仅 20~50%，al 实测 0/6」是在**原始 ctype=tars_mp 探活口径**下
     * 得到的（该口径下 CDN 对子播放列表 403，AL 全挂、HS 抖动）。经 {@code applySdkCtype}
     * （ctype=huya_adr_game_sdk，播放路径与 2026-10-03 修复后的探活路径同口径）实测三条线路
     * 均可用：AL 6/6、TX 48/48、HS 6/6。故此处不再暗示 AL/HS 永久失效。
     */
    private static int multiLineRank(HuyaStreamPlayer.MultiLineOption o) {
        if (o == null) return 99;
        String cdn = (o.cdnType == null) ? "" : o.cdnType.trim().toUpperCase(Locale.US);
        if (cdn.contains("TX")) return 0;
        if (cdn.contains("HS")) return 1;
        if (cdn.contains("AL")) return 2;
        if (o.lineIndex == 5) return 0;
        if (o.lineIndex == 14) return 1;
        if (o.lineIndex == 3) return 2;
        return 3;
    }

    /**
     * 「频道线路选择」用的**子线路列表**，每项形如「源1 · TX」「源2 · HS」。
     *
     * <p>数据源 = mp 接口 {@code stream.hls.multiLine}（一条 URL 一个条目），**不含清晰度**
     * —— 清晰度是独立的另一个轴，由「清晰度选择」按钮负责，两个轴不混。
     * 顺序 = {@link #multiLineRank} 稳定排序后的顺序（TX &gt; HS &gt; AL &gt; 其它）；
     * N 为列表序号（1..N），后缀是它所属的 CDN（即那条总线路的 {@code sCdnType}）。
     * 列表为空时调用方应回落到「线路标签 / 主源 + 源N」的老逻辑。
     */
    public List<String> getHuyaMultiLineLabels() {
        List<String> out = new ArrayList<>();
        List<HuyaStreamPlayer.MultiLineOption> src = huyaMultiLinesSnapshot();
        for (int i = 0; i < src.size(); i++) {
            HuyaStreamPlayer.MultiLineOption o = src.get(i);
            if (o == null) continue;
            String cdn = (o.cdnType == null || o.cdnType.trim().isEmpty())
                    ? ("L" + (i + 1))                       // CDN 名缺失时退化成 L1/L2…
                    : o.cdnType.trim().toUpperCase(Locale.US);
            out.add("源" + (i + 1) + " · " + cdn);
        }
        return out;
    }

    /**
     * 按子线路下标直接播放该条地址（下标口径 = {@link #getHuyaMultiLineLabels()}）。
     * 子线路表为空时直接返回，由调用方回落兜底。
     */
    public void switchToHuyaMultiLine(int index) {
        List<HuyaStreamPlayer.MultiLineOption> src = huyaMultiLinesSnapshot();
        if (src.isEmpty()) {
            LogBridge.w(TAG, "【虎牙】子线路表为空，无法切换");
            return;
        }
        if (index < 0 || index >= src.size()) index = 0;
        HuyaStreamPlayer.MultiLineOption o = src.get(index);
        if (o == null || TextUtils.isEmpty(o.url)) {
            LogBridge.w(TAG, "【虎牙】子线路#" + index + " 无有效地址，放弃切换");
            return;
        }
        Channel ch = getCurrentChannel();
        if (ch == null) return;

        mCurrentHuyaLineIndex = o.lineIndex;
        if (mHuyaRoomId > 0) setUserChosenLine(mHuyaRoomId, o.lineIndex);
        try {
            ch.setCurrentLineIndex(o.lineIndex);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【虎牙】写回频道线路号失败: " + t.getMessage());
        }
        LogBridge.i(TAG, "【虎牙】切换子线路 -> 子线路" + (index + 1)
                + " · " + (o.cdnType == null ? "?" : o.cdnType)
                + "（lineIndex=" + o.lineIndex + "）");
        playUrlInternal(o.url);
    }

    /**
     * 【2026-09-30 新增】URL 头部归一化，用于判断「是不是同一路地址」。
     *
     * <p>去掉 query 并**抹平 http/https 协议差异** —— 服务端下发的子线路是 {@code http://hs.hls...}，
     * 而播放器落地地址是 {@code https://hs.hls...}，不归一化会判定为不同地址。
     */
    private static String normalizeUrlHead(String url) {
        if (url == null) return "";
        String h = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (h.startsWith("https://")) h = h.substring(8);
        else if (h.startsWith("http://")) h = h.substring(7);
        return h;
    }

    /**
     * 「频道线路选择」弹窗的当前打勾项下标（0 基，口径 = {@link #getHuyaMultiLineLabels()}）。
     * 优先用**当前实际在播的 URL** 匹配（先全等、再去 query 比较，因为播放会经 CDN 重定向）；
     * 退而按当前生效总线路的 lineIndex 匹配。
     *
     * <p>⚠️ 匹配不到**必须返回 -1**，禁止回退 0 —— 回退 0 会在实际播着第 2 条时把勾打在第 1 条上。
     * 子线路表为空时同样返回 -1。
     */
    public int getCurrentHuyaMultiLineUiIndex() {
        List<HuyaStreamPlayer.MultiLineOption> src = huyaMultiLinesSnapshot();
        if (src.isEmpty()) return -1;
        // 【2026-09-30】所有匹配都走归一化后的 URL 头（去 query + 抹平 http/https），见 normalizeUrlHead。

        // ① 当前实际在播的 URL —— 全等匹配
        String cur = currentUrl;
        if (!TextUtils.isEmpty(cur)) {
            for (int i = 0; i < src.size(); i++) {
                HuyaStreamPlayer.MultiLineOption o = src.get(i);
                if (o != null && !TextUtils.isEmpty(o.url) && o.url.equals(cur)) return i;
            }
            // ② 播放会经 CDN 调度/重定向，参数可能变化 → 退一步只比「去 query 」部分。
            // ⚠️ 必须**同时归一化协议头**：服务端下发的子线路是 http://hs.hls...，而播放器落地的是
            // https://hs.hls...，只去 query 仍然不相等 → 会掉到 ③ 用可能过期的 lineIndex 匹配，
            // 实测表现为「在播 hs（源2）却把勾打在 源1（TX）」。
            String curHead = normalizeUrlHead(cur);
            for (int i = 0; i < src.size(); i++) {
                HuyaStreamPlayer.MultiLineOption o = src.get(i);
                if (o == null || TextUtils.isEmpty(o.url)) continue;
                if (normalizeUrlHead(o.url).equals(curHead)) return i;
            }
        }

        // ③ 退回按当前生效总线路的 lineIndex 匹配
        for (int i = 0; i < src.size(); i++) {
            HuyaStreamPlayer.MultiLineOption o = src.get(i);
            if (o != null && o.lineIndex >= 0 && o.lineIndex == mCurrentHuyaLineIndex) return i;
        }
        return -1;
    }

    /**
     * 【2026-09-30 新增】「频道线路选择」列表口径为「只列子线路（源N · CDN）」时的当前打勾下标（0 基）。
     *
     * <p>因为列表里已经没有「主源」项了，这里的下标就是 {@link #getCurrentHuyaMultiLineUiIndex()} 的
     * 结果本身（子线路 0 基下标）。
     *
     * <p>⚠️ 子线路表为空、或匹配不到任何一条子线路时返回 <b>-1</b>（调用方应回落旧逻辑或干脆不打勾）——
     * **禁止回退 0**，那会把勾打在「源1」上（打勾铁律）。
     */
    public int getCurrentHuyaSourceUiIndex() {
        List<HuyaStreamPlayer.MultiLineOption> src = huyaMultiLinesSnapshot();
        if (src.isEmpty()) return -1;
        int k = getCurrentHuyaMultiLineUiIndex();
        return (k >= 0 && k < src.size()) ? k : -1;
    }

    /**
     * 切换虎牙线路。
     *
     * 【2026-09-29 修复】参数语义＝**「频道线路选择」列表里的下标**（0 基）。
     * 虎牙频道该列表直接来自解析矩阵的线路标签（{@link #applyHuyaMatrix}），
     * 与 {@link #mHuyaLineOrder} 一一对应；因此这里按该登记表取目标线路，
     * 再在该线路内**优先保持当前清晰度**，否则取最高码率。
     * ⚠️ 不能再从 variantList 反推线路顺序：切线路会把目标线路挪到最前，反推顺序会漂移。
     * 没有线路标签时（老数据/异常）回落到旧的「主源 + 备份源」语义。
     */
    public void switchToHuyaLine(int lineIndex) {
        Channel ch = getCurrentChannel();
        if (ch == null) return;
        if (lineIndex < 0) lineIndex = 0;

        // 【2026-09-30 定稿·用户最终口径】虎牙频道且有子线路：列表**只列子线路**（源1 · TX / 源2 · HS …），
        //   lineIndex 就是子线路的 0 基下标，**没有「主源」项、也没有总线路项**。
        //   ⚠️ 必须放在下面「总线路（huyaLabels / mHuyaLineOrder）」分支**之前** ——
        //      否则只要 huyaLabels 非空就会被它抢走，点了「源N」实际切的是总线路。
        List<HuyaStreamPlayer.MultiLineOption> mls = huyaMultiLinesSnapshot();
        if (isHuyaSource(ch.getMainPlayUrl()) && !mls.isEmpty()) {
            if (lineIndex < 0 || lineIndex >= mls.size()) {
                LogBridge.w(TAG, "【虎牙】子线路下标越界: " + lineIndex + "（共 " + mls.size() + " 条），忽略");
                return;
            }
            HuyaStreamPlayer.MultiLineOption o = mls.get(lineIndex);
            String target = (o != null) ? o.url : null;
            if (TextUtils.isEmpty(target)) {
                LogBridge.w(TAG, "【虎牙】子线路#" + lineIndex + " 无有效地址，放弃切换");
                return;
            }
            mCurrentHuyaLineIndex = o.lineIndex;
            if (mHuyaRoomId > 0) setUserChosenLine(mHuyaRoomId, o.lineIndex);
            try {
                ch.setCurrentLineIndex(lineIndex);
            } catch (Throwable t) {
                LogBridge.w(TAG, "【虎牙】写回频道线路号失败: " + t.getMessage());
            }
            LogBridge.i(TAG, "【虎牙】切换线路 -> 源" + (lineIndex + 1)
                    + " · " + (o.cdnType == null ? "?" : o.cdnType)
                    + "（lineIndex=" + o.lineIndex + "）");
            // 【2026-09-30】子线路是服务端拼好的**成品地址**（自带固定 ratio，不再是可选档列表），
            // 所以切过去之后必须把清晰度轴对齐，否则「清晰度选择」那行/打勾还停在切换前的旧档
            // （实测：源1 原档次 / 源2 蓝光4M → 切源1 后仍显示「蓝光4M」，与实际不符）。
            syncResolutionLabelForDirectUrl(target, o);
            playUrlInternal(target);
            return;
        }

        List<String> huyaLabels = ch.getHuyaLineLabels();
        List<Integer> lineOrder = huyaLineOrder();
        if (huyaLabels != null && !huyaLabels.isEmpty() && !lineOrder.isEmpty() && !variantList.isEmpty()) {
            Variant target = null;
            if (lineIndex >= lineOrder.size()) lineIndex = 0;
            int targetLine = lineOrder.get(lineIndex);

            for (Variant v : variantList) {
                if (v.huyaLineIndex != targetLine) continue;
                // 同线路内：优先保持当前清晰度，否则比码率取最高
                if (currentResolutionLabel != null && currentResolutionLabel.equals(v.getDisplayLabel())) {
                    target = v;
                    break;
                }
                if (target == null || v.huyaBitRate > target.huyaBitRate) target = v;
            }
            if (target != null) {
                ch.setCurrentLineIndex(targetLine);
                mCurrentHuyaLineIndex = targetLine;
                if (mHuyaRoomId > 0) setUserChosenLine(mHuyaRoomId, targetLine);
                currentResolutionLabel = target.getDisplayLabel();
                rebuildVariantListForLine(targetLine);
                LogBridge.i(TAG, "【虎牙】切换线路 -> " + huyaLabels.get(lineIndex)
                        + "（lineIndex=" + targetLine + "，清晰度=" + currentResolutionLabel + "）");
                playUrlInternal(target.url);
                return;
            }
        }

        // 兜底：老语义（主源 + 备份源）
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

    /**
     * 【2026-09-30 新增】按「成品播放地址」反推并同步清晰度标签。
     *
     * <p>子线路 / 主源用的都是服务端拼好的成品 URL（自带固定 {@code ratio=}），它**不是**一个可选档列表，
     * 所以切换后不能直接沿用切换前的 {@link #currentResolutionLabel}。
     * 反推顺序：① URL 里 {@code ratio=} 精确匹配该线路的码率档 → ② 该线路的第一档 → ③「默认档」。
     * 反推不出来就写「默认档」（它不在清晰度列表里 → 打勾自然落空，不会打错勾）。
     */
    private void syncResolutionLabelForDirectUrl(String url, HuyaStreamPlayer.MultiLineOption o) {
        if (TextUtils.isEmpty(url)) return;
        String ratio = null;
        int q = url.indexOf("ratio=");
        if (q >= 0) {
            int amp = url.indexOf('&', q);
            ratio = (amp > 0) ? url.substring(q + 6, amp) : url.substring(q + 6);
        }
        String best = null;
        synchronized (variantListLock) {
            if (ratio != null) {
                for (Variant v : variantList) {
                    if (v == null) continue;
                    if (o != null && o.lineIndex >= 0 && v.huyaLineIndex != o.lineIndex) continue;
                    if (v.huyaBitRate > 0 && String.valueOf(v.huyaBitRate).equals(ratio)) {
                        best = v.getDisplayLabel();
                        break;
                    }
                }
            }
            if (best == null && o != null && o.lineIndex >= 0) {
                for (Variant v : variantList) {
                    if (v != null && v.huyaLineIndex == o.lineIndex) {
                        best = v.getDisplayLabel();
                        break;
                    }
                }
            }
        }
        currentResolutionLabel = TextUtils.isEmpty(best) ? "默认档" : best;
        LogBridge.i(TAG, "【虎牙】清晰度轴已对齐到: " + currentResolutionLabel
                + (ratio == null ? "" : "（ratio=" + ratio + "）"));
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

            // 【2026-09-29】只有"当前清晰度"不属于这条新线路时才回落到该线路最高档；
            // 否则会把调用方刚选定的档位覆盖掉（表现为切线路后清晰度被悄悄重置）。
            if (!currentLine.isEmpty() && !containsLabel(currentLine, currentResolutionLabel)) {
                currentResolutionLabel = currentLine.get(0).getDisplayLabel();
            }
        }
        LogBridge.i(TAG, "【虎牙】切换到线路 " + lineIndex + "（该线路清晰度档: "
                + getAvailableResolutions() + "）");
    }

    private static boolean containsLabel(List<Variant> list, String label) {
        if (label == null) return false;
        for (Variant v : list) {
            if (label.equals(v.getDisplayLabel()) || label.equals(v.resolutionLabel)) return true;
        }
        return false;
    }

    public void switchToResolution(int targetHeight, String... matchLabelOpt) {
        // 【2026-10-07】咪咕频道：走咪咕清晰度轴（按展示名匹配 → 重新解析对应源串）
        if (hasMiguAxis()) {
            String miguLabel = (matchLabelOpt != null && matchLabelOpt.length > 0)
                    ? matchLabelOpt[0] : null;
            if (!TextUtils.isEmpty(miguLabel)) {
                switchMiguQuality(miguLabel);
                return;
            }
        }
        // 【2026-10-07】畅享频道：走畅享清晰度轴（按展示名匹配 → 直播该档地址）
        if (hasChanxiangAxis()) {
            String cxLabel = (matchLabelOpt != null && matchLabelOpt.length > 0)
                    ? matchLabelOpt[0] : null;
            if (!TextUtils.isEmpty(cxLabel)) {
                switchChanxiangQuality(cxLabel);
                return;
            }
        }
        List<Variant> snapshot;
        // 【2026-09-29】虎牙频道只在「当前总线路」内匹配档位 —— 否则会跳到别条线路的地址上，
        // 造成「线路那行还是 TX，实际播的是 AL」这种显示与实际不一致。
        Channel ch0 = getCurrentChannel();
        boolean scoped = isScopedHuyaLines(ch0);
        int curLine = currentLineIndexForUi(ch0);
        synchronized (variantListLock) {
            snapshot = new ArrayList<>();
            for (Variant v : variantList) {
                if (scoped && v.huyaLineIndex >= 0 && v.huyaLineIndex != curLine) continue;
                snapshot.add(v);
            }
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
        dLog("切换清晰度到）" + selected.getDisplayLabel() + "，URL=" + (selected.url != null ? selected.url.substring(0, Math.min(60, selected.url.length())) : "(空)"));
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
        // 【2026-10-07】咪咕频道：当前档位以咪咕轴为准（切档 / 解析选源都会更新它），
        // 避免与虎牙专用的 currentResolutionLabel 串味。
        if (hasMiguAxis()) return getCurrentMiguQualityLabel();
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

    public void release() {

        try {
            stopStuckDetection();
            cancelRetry();
            if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
            updateWakeLock(false);
            try { unregisterDecoderModeReceiver(); } catch (Exception e) {
                LogBridge.w(TAG, "注销解码器广播异帧: " + e.getMessage());
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

            // 【2026-09-29 黑屏根因修复】TVPlayerManager 是单例，实例字段会跨 Activity 生命周期残留。
            //   release() 释放了 player / playerView，但若不重置下列渲染器状态，重进时新 PlayerView
            //   会被误判为「渲染器类型未变」「Surface 回调已绑定」，从而跳过 switchRenderer 重建与
            //   bindSurfaceCallback 重绑，导致新 Surface 的 surfaceCreated 永不触发 → STATE_READY 却黑屏。
            mCurrentUseTexture = null;
            surfaceCallbackBound = false;
            surfaceReady = false;
            pendingBindPlayer = false;
            pendingRetryRunnable = null;   // 【2026-09-29 黑屏根因修复·第二轮】清理待执行的重试轮询引用

            if (trackSelector != null) {
                try {
                    trackSelector.release();
                } catch (Exception e) {

                    LogBridge.w(TAG, "trackSelector.release 异常（已吞掉，不影响清理）: " + e.getMessage());
                }
                trackSelector = null;
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
