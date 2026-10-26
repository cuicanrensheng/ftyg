package com.tv.live;

import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;

import com.tv.live.util.LogBridge;

import java.util.HashMap;
import java.util.Map;

/**
 * HLS「死流」检测器：识别固定窗口不滚动的伪直播清单。
 *
 * <p>背景（2026-10-22 新动漫源故障）：移动 IPTV 点播/回看切片接口
 * （如 <code>3221226322/01.m3u8?fmt=ts2hls</code>）返回的是<b>固定内容窗口</b>：
 * 无 {@code #EXT-X-ENDLIST}（播放器按直播处理）、MEDIA-SEQUENCE 永不前进，
 * 但 302 重定向与分片下载始终 200，传输层一切"正常"。ExoPlayer 无死流检测，
 * 只会无限刷新清单等待新分片，最终靠应用 20s 卡住看门狗循环重试。
 *
 * <p>本类在 HLS 媒体清单每次成功解析后（见 {@link LoggingHlsPlaylistParserFactory}）
 * 跟踪 MEDIA-SEQUENCE：直播清单（无 ENDLIST）刷新序号持续不前进，
 * 判定为死流并通过 {@link Listener} 通知上层（TVPlayerManager 走既有 autoRetry 兜底链）。
 *
 * <p>【2026-10-25 计数制 → 时间窗口制】原实现按「连续 4 次刷新序号不前进」判定，
 * 阈值是按 9-10s 轮询标定的（4 次 ≈ 40s）。当日上线
 * {@link FastLivePlaylistTrackerFactory} 直播清单 2s 快轮询后，健康源每 10s 发布周期内
 * 有 8-9 次未前进——旧计数制 4-5s 即触线，必然误杀。现改为<b>时间基准</b>：
 * 序号不前进持续 {@value #DEAD_WINDOW_MS} ms 才判死流，与轮询快慢彻底解耦
 * （2s 轮询 40s 触发 ≈ 原 10s 轮询 4 次触发的绝对时长，行为等价）。
 * 真死流还有 30s 的 {@code PlaylistStuckException}（快轮询工厂卡死系数 15.0 × 2s）
 * 与 20s 应用卡住看门狗兜底，三层互不依赖。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>只观察、不修改</b>：任何异常都不得阻断播放链路；</li>
 *   <li>按 URL 维度隔离状态：切台/换源自动建立新状态，互不干扰；</li>
 *   <li>点播（有 ENDLIST）不参与检测：播完自动 STATE_ENDED，属正常路径；</li>
 *   <li>触发后进入冷却，避免每次刷新重复触发风暴。</li>
 * </ul>
 */
@OptIn(markerClass = UnstableApi.class)
public final class DeadStreamMonitor {

    private static final String TAG = "DeadStreamMnt";

    /**
     * 直播清单 MEDIA-SEQUENCE 持续多久不前进即判定死流（时间基准，与轮询快慢解耦）。
     * 40s 与原计数制（10s 轮询 × 4 次）的绝对时长等价；
     * 须大于实测最大发布尖刺 21s（约 30% 分片间隔超 11s），给慢发布源留足余量。
     */
    private static final long DEAD_WINDOW_MS = 40_000L;

    /**
     * 触发死流判定的最少「未前进」观察次数（配合 {@link #DEAD_WINDOW_MS}）。
     * 防止 lastSeqChangeMs 陈旧的边角情形（如长时间无刷新后恢复）一击必杀：
     * 至少看到 3 次连续未前进（2s 轮询 = 6s，10s 轮询 = 30s）才认账。
     */
    private static final int DEAD_MIN_SAME_COUNT = 3;

    /** 判定触发后的冷却时长：冷却期内同 URL 不再重复判定（避免风暴）。 */
    private static final long COOLDOWN_MS = 60_000L;

    /** 死流回调。url 为当前播放的清单 URL（由解析器传入）。 */
    public interface Listener {
        void onDeadStreamDetected(String url);
    }

    private static final Map<String, State> STATES = new HashMap<>();
    private static volatile Listener listener;

    private DeadStreamMonitor() {
    }

    public static void setListener(@Nullable Listener l) {
        listener = l;
    }

    /**
     * 该 URL 累计判死流次数（不随冷却清零）。
     * 上层用它判断"重试后仍不前进"：同一 URL 累计 ≥2 次说明重播无效，
     * 应直接走失效源兜底（切备用源/上报），而不是继续无限重试。
     */
    public static int getTriggerCount(String url) {
        if (url == null || url.isEmpty()) {
            return 0;
        }
        synchronized (STATES) {
            State s = STATES.get(url);
            return s == null ? 0 : s.triggerCount;
        }
    }

    /** 媒体清单解析完成回调（由 LoggingHlsPlaylistParserFactory 在 parse 后调用）。 */
    public static void onMediaPlaylistParsed(String url, HlsMediaPlaylist playlist) {
        if (url == null || url.isEmpty() || playlist == null) {
            return;
        }
        // 仅检测直播（无 ENDLIST）：点播清单序号本来就不前进，不参与
        if (playlist.hasEndTag) {
            synchronized (STATES) {
                STATES.remove(url);
            }
            return;
        }
        final long seq = playlist.mediaSequence;
        synchronized (STATES) {
            State s = STATES.get(url);
            if (s == null) {
                s = new State();
                STATES.put(url, s);
            }
            final long now = SystemClock.elapsedRealtime();
            if (now < s.cooldownUntil) {
                return; // 冷却中
            }
            if (s.lastSeq == seq) {
                s.sameCount++;
                if (s.sameCount >= DEAD_MIN_SAME_COUNT
                        && now - s.lastSeqChangeMs >= DEAD_WINDOW_MS) {
                    final int sameCount = s.sameCount;
                    final long stalledMs = now - s.lastSeqChangeMs;
                    s.sameCount = 0;
                    s.cooldownUntil = now + COOLDOWN_MS;
                    s.triggerCount++;
                    LogBridge.w(TAG, "死流检测：直播清单 MEDIA-SEQUENCE 持续 " + (stalledMs / 1000)
                            + "s 未前进（seq=" + seq + ", 未前进刷新 " + sameCount
                            + " 次, hasEndTag=false），判定固定窗口伪直播 -> " + url);
                    final Listener l = listener;
                    if (l != null) {
                        try {
                            l.onDeadStreamDetected(url);
                        } catch (Throwable ignored) {
                            // 回调异常不扩散
                        }
                    }
                }
            } else {
                s.lastSeq = seq;
                s.sameCount = 0;
                s.lastSeqChangeMs = now;
            }
        }
    }

    /** 单个 URL 的跟踪状态。 */
    private static final class State {
        long lastSeq = -1L;
        int sameCount = 0;
        /** 最近一次序号前进（或首次观察）的时刻，死流判定以此算「持续未前进」时长。 */
        long lastSeqChangeMs = 0L;
        long cooldownUntil = 0L;
        /** 累计判死流次数（不随冷却清零），供上层决定是否走上限兜底。 */
        int triggerCount = 0;
    }
}
