package com.tv.live;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import java.util.HashMap;

/**
 * 【2026-10-27 切台卡顿专项 · 方案1+3】
 * 可运行时动态修改缓冲参数的 LoadControl（media3 1.7.1 适配版）。
 *
 * <p>逻辑逐字复刻 {@code androidx.media3.exoplayer.DefaultLoadControl}，仅把 4 个缓冲时长
 * 抽成可热替换的 {@link Config}，因此默认参数 = TVLive 既有调优值（12000/60000/1200/2000）时，
 * 行为与原 {@code DefaultLoadControl} 完全一致（零回归）。
 *
 * <p>切台时按源类型调用 {@link #setParams(BufferParams)} 切换缓冲配置（虎牙 / HLS 沿用默认厚缓冲；
 * 非虎牙的渐进式低延迟流收紧缓冲），播放器实例不重建（方案3：复用实例），仅替换缓冲参数。
 *
 * <p>注意：豆包原版 {@code DynamicLoadControl} 是针对 ExoPlayer 2.x（com.google.android.exoplayer2）
 * 写的，其 {@code LoadControl} 接口与 media3 完全不同且使用了 media3 不存在的
 * {@code Renderer.getFormat()}，无法直接编译，故此处按 media3 1.7.1 重写。
 */
public final class DynamicLoadControl implements LoadControl {

    /** 缓冲参数包（4 个核心时长，单位 ms，语义与 DefaultLoadControl.Builder.setBufferDurationsMs 一致）。 */
    public static final class BufferParams {
        public final int minBufferMs;
        public final int maxBufferMs;
        public final int bufferForPlaybackMs;
        public final int bufferForPlaybackAfterRebufferMs;

        public BufferParams(int minBufferMs, int maxBufferMs, int bufferForPlaybackMs,
                            int bufferForPlaybackAfterRebufferMs) {
            this.minBufferMs = minBufferMs;
            this.maxBufferMs = maxBufferMs;
            this.bufferForPlaybackMs = bufferForPlaybackMs;
            this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs;
        }

        @Override
        public String toString() {
            return minBufferMs + "," + maxBufferMs + "," + bufferForPlaybackMs + ","
                    + bufferForPlaybackAfterRebufferMs;
        }
    }

    /** 既有的全量调优值（虎牙 / HLS / 未知源沿用，零回归）。 */
    public static final BufferParams PROFILE_DEFAULT = new BufferParams(12000, 60000, 1200, 2000);
    /**
     * 低延迟渐进式流（非虎牙、非 HLS 的 FLV/TS/MP4 直连）收紧缓冲，切台更快：
     *   起播门槛 1200→800（首帧快约 0.4s）、最小缓冲 12000→6000、最大 60000→30000、
     *   二次缓冲后门槛 2000→1500（卡后恢复更快）。
     * 仅作用于「非虎牙、非 HLS」的直连流，虎牙/HLS 既有的厚缓冲调优完全保留。
     */
    public static final BufferParams PROFILE_LOW_LATENCY = new BufferParams(6000, 30000, 800, 1500);

    // 以下常量与 DefaultLoadControl 保持一致（仅用于 allocator 目标字节数，不影响缓冲时长决策）
    private static final int DEFAULT_VIDEO_BUFFER_SIZE = 2000 * C.DEFAULT_BUFFER_SEGMENT_SIZE;
    private static final int DEFAULT_AUDIO_BUFFER_SIZE = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE;
    private static final int DEFAULT_TEXT_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE;
    private static final int DEFAULT_METADATA_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE;
    private static final int DEFAULT_CAMERA_MOTION_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE;
    private static final int DEFAULT_IMAGE_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE;
    private static final int DEFAULT_MUXED_BUFFER_SIZE =
            DEFAULT_VIDEO_BUFFER_SIZE + DEFAULT_AUDIO_BUFFER_SIZE + DEFAULT_TEXT_BUFFER_SIZE;
    private static final int DEFAULT_MIN_BUFFER_SIZE = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

    private static final boolean PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS = false;

    /**
     * 【2026-10-27 预取不足修复】运行时可切换「时间优先」：
     * true 时 shouldContinueLoading / shouldStartPlayback 忽略 allocator 字节上限，纯按时间
     * （&lt; minBuffer 继续加载、≥ maxBuffer 停止）。
     *
     * <p>病根（CCTV4 实测）：liveOffset 已 33s（offset 生效），但 bufBehind 仅 8.3s——
     * allocator 字节上限（DEFAULT_MIN_BUFFER_SIZE ≈12.8MB）在 buffered 才 8-12s 时就让
     * {@code targetBufferSizeReached=true}，把 isLoading 置 false，预取被掐死在 ~8s，
     * 覆盖不了「新片发布→清单可见」10-11s 空窗 → 分片交界卡 3s。
     *
     * <p>仅对大分片低频 HLS（td ≥ 8s）由 TVPlayerManager 开启；其余源保持 false（零回归）。
     */
    private volatile boolean timePrioritized = false;

    /** 供 TVPlayerManager 按源类型（td ≥ 8s 的大分片 HLS）开关时间优先预取；切台时重置 false。 */
    public void setTimePrioritized(boolean v) {
        this.timePrioritized = v;
    }

    public boolean isTimePrioritized() {
        return timePrioritized;
    }

    /** 可变缓冲配置（us）。volatile 保证切台换参后播放线程立刻可见。 */
    private volatile Config config;
    private final DefaultAllocator allocator;
    private final HashMap<PlayerId, PlayerLoadingState> loadingStates;
    private long threadId;

    private static final class Config {
        final long minBufferUs;
        final long maxBufferUs;
        final long bufferForPlaybackUs;
        final long bufferForPlaybackAfterRebufferUs;

        Config(int minMs, int maxMs, int playbackMs, int afterRebufferMs) {
            this.minBufferUs = Util.msToUs(minMs);
            this.maxBufferUs = Util.msToUs(maxMs);
            this.bufferForPlaybackUs = Util.msToUs(playbackMs);
            this.bufferForPlaybackAfterRebufferUs = Util.msToUs(afterRebufferMs);
        }
    }

    private static final class PlayerLoadingState {
        public boolean isLoading;
        public int targetBufferBytes;
    }

    public DynamicLoadControl(BufferParams initial) {
        this.allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        this.loadingStates = new HashMap<>();
        this.threadId = C.INDEX_UNSET;
        this.config = new Config(initial.minBufferMs, initial.maxBufferMs,
                initial.bufferForPlaybackMs, initial.bufferForPlaybackAfterRebufferMs);
    }

    /** 切台时调用：按源类型切换缓冲参数（播放器实例不重建）。 */
    public void setParams(BufferParams p) {
        if (p == null) return;
        checkArgument(p.minBufferMs >= p.bufferForPlaybackMs,
                "minBufferMs cannot be less than bufferForPlaybackMs");
        checkArgument(p.minBufferMs >= p.bufferForPlaybackAfterRebufferMs,
                "minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs");
        checkArgument(p.maxBufferMs >= p.minBufferMs,
                "maxBufferMs cannot be less than minBufferMs");
        this.config = new Config(p.minBufferMs, p.maxBufferMs,
                p.bufferForPlaybackMs, p.bufferForPlaybackAfterRebufferMs);
    }

    public BufferParams getParams() {
        Config c = config;
        return new BufferParams(
                (int) Util.usToMs(c.minBufferUs),
                (int) Util.usToMs(c.maxBufferUs),
                (int) Util.usToMs(c.bufferForPlaybackUs),
                (int) Util.usToMs(c.bufferForPlaybackAfterRebufferUs));
    }

    @Override
    public Allocator getAllocator() {
        return allocator;
    }

    @Override
    public void onPrepared(PlayerId playerId) {
        long currentThreadId = Thread.currentThread().getId();
        checkState(
                threadId == C.INDEX_UNSET || threadId == currentThreadId,
                "Players that share the same LoadControl must share the same playback thread.");
        threadId = currentThreadId;
        if (!loadingStates.containsKey(playerId)) {
            loadingStates.put(playerId, new PlayerLoadingState());
        }
        resetPlayerLoadingState(playerId);
    }

    @Override
    public void onStopped(PlayerId playerId) {
        removePlayer(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        removePlayer(playerId);
        if (loadingStates.isEmpty()) {
            threadId = C.INDEX_UNSET;
        }
    }

    @Override
    public void onTracksSelected(
            LoadControl.Parameters parameters,
            TrackGroupArray trackGroups,
            @NullableType ExoTrackSelection[] trackSelections) {
        checkNotNull(loadingStates.get(parameters.playerId)).targetBufferBytes =
                calculateTargetBufferBytes(trackSelections);
        updateAllocator();
    }

    @Override
    public boolean shouldContinueLoading(LoadControl.Parameters parameters) {
        PlayerLoadingState playerLoadingState = checkNotNull(loadingStates.get(parameters.playerId));
        Config c = config;
        boolean prioritizeTime = PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS || timePrioritized;
        boolean targetBufferSizeReached =
                !prioritizeTime
                        && allocator.getTotalBytesAllocated() >= calculateTotalTargetBufferBytes();
        long minBufferUs = c.minBufferUs;
        if (parameters.playbackSpeed > 1) {
            // 倍速播放：按倍速放大最小所需媒体时长，保证播放时长仍够 minBufferUs
            long mediaDurationMinBufferUs =
                    Util.getMediaDurationForPlayoutDuration(minBufferUs, parameters.playbackSpeed);
            minBufferUs = min(mediaDurationMinBufferUs, c.maxBufferUs);
        }
        // 防止 minBufferUs 过小导致卡死
        minBufferUs = max(minBufferUs, 500_000);
            if (parameters.bufferedDurationUs < minBufferUs) {
                playerLoadingState.isLoading = prioritizeTime || !targetBufferSizeReached;
            } else if (parameters.bufferedDurationUs >= c.maxBufferUs || targetBufferSizeReached) {
            playerLoadingState.isLoading = false;
        } // 否则不改变加载状态
        return playerLoadingState.isLoading;
    }

    @Override
    public boolean shouldStartPlayback(LoadControl.Parameters parameters) {
        Config c = config;
        long bufferedDurationUs =
                Util.getPlayoutDurationForMediaDuration(
                        parameters.bufferedDurationUs, parameters.playbackSpeed);
        long minBufferDurationUs =
                parameters.rebuffering ? c.bufferForPlaybackAfterRebufferUs : c.bufferForPlaybackUs;
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            minBufferDurationUs = min(parameters.targetLiveOffsetUs / 2, minBufferDurationUs);
        }
        return minBufferDurationUs <= 0
                || bufferedDurationUs >= minBufferDurationUs
                || (!(PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS || timePrioritized)
                    && allocator.getTotalBytesAllocated() >= calculateTotalTargetBufferBytes());
    }

    @Override
    public long getBackBufferDurationUs(PlayerId playerId) {
        return 0;
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return false;
    }

    @Override
    public boolean shouldContinuePreloading(
            Timeline timeline, MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        for (PlayerLoadingState playerLoadingState : loadingStates.values()) {
            if (playerLoadingState.isLoading) {
                return false;
            }
        }
        return true;
    }

    private void resetPlayerLoadingState(PlayerId playerId) {
        PlayerLoadingState playerLoadingState = checkNotNull(loadingStates.get(playerId));
        playerLoadingState.targetBufferBytes = DEFAULT_MIN_BUFFER_SIZE;
        playerLoadingState.isLoading = false;
    }

    private void removePlayer(PlayerId playerId) {
        if (loadingStates.remove(playerId) != null) {
            updateAllocator();
        }
    }

    private void updateAllocator() {
        if (loadingStates.isEmpty()) {
            allocator.reset();
        } else {
            allocator.setTargetBufferSize(calculateTotalTargetBufferBytes());
        }
    }

    private int calculateTargetBufferBytes(@NullableType ExoTrackSelection[] trackSelectionArray) {
        int targetBufferSize = 0;
        if (trackSelectionArray != null) {
            for (ExoTrackSelection exoTrackSelection : trackSelectionArray) {
                if (exoTrackSelection != null) {
                    targetBufferSize += getDefaultBufferSize(exoTrackSelection.getTrackGroup().type);
                }
            }
        }
        return max(DEFAULT_MIN_BUFFER_SIZE, targetBufferSize);
    }

    private int calculateTotalTargetBufferBytes() {
        int totalTargetBufferBytes = 0;
        for (PlayerLoadingState state : loadingStates.values()) {
            totalTargetBufferBytes += state.targetBufferBytes;
        }
        return totalTargetBufferBytes;
    }

    private static int getDefaultBufferSize(@C.TrackType int trackType) {
        switch (trackType) {
            case C.TRACK_TYPE_DEFAULT:
                return DEFAULT_MUXED_BUFFER_SIZE;
            case C.TRACK_TYPE_AUDIO:
                return DEFAULT_AUDIO_BUFFER_SIZE;
            case C.TRACK_TYPE_VIDEO:
                return DEFAULT_VIDEO_BUFFER_SIZE;
            case C.TRACK_TYPE_TEXT:
                return DEFAULT_TEXT_BUFFER_SIZE;
            case C.TRACK_TYPE_METADATA:
                return DEFAULT_METADATA_BUFFER_SIZE;
            case C.TRACK_TYPE_CAMERA_MOTION:
                return DEFAULT_CAMERA_MOTION_BUFFER_SIZE;
            case C.TRACK_TYPE_IMAGE:
                return DEFAULT_IMAGE_BUFFER_SIZE;
            case C.TRACK_TYPE_NONE:
                return 0;
            case C.TRACK_TYPE_UNKNOWN:
                return DEFAULT_MIN_BUFFER_SIZE;
            default:
                throw new IllegalArgumentException();
        }
    }
}
