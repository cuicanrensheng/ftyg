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
 * 跟踪 MEDIA-SEQUENCE：直播清单（无 ENDLIST）连续多次刷新序号不前进，
 * 判定为死流并通过 {@link Listener} 通知上层（TVPlayerManager 走既有 autoRetry 兜底链）。
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
     * 连续多少次刷新 MEDIA-SEQUENCE 无前进即判定死流。
     * 直播清单刷新间隔约为 targetDuration 量级（该源 9–10s），
     * 4 次 ≈ 40s：短于 20s 的偶发停顿不误杀，长于此基本可判定源失效。
     */
    private static final int DEAD_THRESHOLD = 4;

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
                if (s.sameCount >= DEAD_THRESHOLD) {
                    s.sameCount = 0;
                    s.cooldownUntil = now + COOLDOWN_MS;
                    s.triggerCount++;
                    LogBridge.w(TAG, "死流检测：清单连续 " + DEAD_THRESHOLD
                            + " 次刷新 MEDIA-SEQUENCE 未前进（seq=" + seq
                            + ", hasEndTag=false），判定固定窗口伪直播 -> " + url);
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
            }
        }
    }

    /** 单个 URL 的跟踪状态。 */
    private static final class State {
        long lastSeq = -1L;
        int sameCount = 0;
        long cooldownUntil = 0L;
        /** 累计判死流次数（不随冷却清零），供上层决定是否走上限兜底。 */
        int triggerCount = 0;
    }
}
