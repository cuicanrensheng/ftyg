package com.tv.live;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播分片下载耗时采集器（2026-10-27 快轮询自适应）。
 *
 * <p><b>为什么需要它</b>：{@link FastLivePlaylistTrackerFactory} 要把 HLS 清单的
 * {@code targetDuration} 从 10s 改写为 2s（快轮询），必须只在「分片下载耗时占发布间隔比例低」
 * 的线上开启——否则对带宽被分片占满的线（如 39.135：11.5MB/片、下载占满 10s 间隔的 75%），
 * 额外的清单请求会和分片抢带宽，反而引入微冻回归（2.0.251 电视真机 A/B 实证）。
 *
 * <p>而清单本身只给分片时长、不给字节数，静态判据只能按「线」白名单；运行时判据需要
 * 真实的分片传输耗时。本类按 host 维护一份 EWMA（指数加权移动平均）的「分片下载秒数」，
 * 由 {@code TVPlayerManager} 的媒体源监听在每次媒体分片加载完成时回填，供工厂在清单解析时查询。
 *
 * <p><b>判定口径</b>：{@code ratio = avgDownloadSec / targetDurationSec}。
 * {@code ratio ≤ 0.5} → 该线分片占用带宽低，可安全开快轮询；{@code > 0.5} → 保持原生 10s 轮询；
 * 样本不足（{@code < 2} 次）或无数据 → 保守保持原生（绝不对未知线莽撞开启）。
 *
 * <p><b>线程安全</b>：进程级单例，{@code ConcurrentHashMap} + 每 host 内部 {@code synchronized} 标量数组，
 * 采集（播放线程）与查询（清单解析线程）并发安全。
 */
public final class LiveSegmentSpeedMeter {

    private static final LiveSegmentSpeedMeter INSTANCE = new LiveSegmentSpeedMeter();

    /** 进程级单例。 */
    public static LiveSegmentSpeedMeter getInstance() {
        return INSTANCE;
    }

    /**
     * 每 host 维护 {@code [avgSec, count]}：avgSec = EWMA 分片下载秒数，count = 有效样本数。
     * 用定长 double[2] + synchronized 而非 Double，避免装箱与并发写竞争。
     */
    private final Map<String, double[]> ewma = new ConcurrentHashMap<>();

    /** EWMA 平滑系数：新样本占 30%，旧均值占 70%。分片耗时本身方差不大，0.3 足够跟手又不抖。 */
    private static final double ALPHA = 0.3;

    private LiveSegmentSpeedMeter() {}

    /**
     * 记录一次媒体分片下载耗时。
     *
     * @param host          分片请求 host（{@code uri.getHost()}），为 null 直接忽略
     * @param loadDurationMs 本次加载耗时（毫秒），由 {@code LoadEventInfo.loadDurationMs} 提供
     */
    public void record(String host, long loadDurationMs) {
        if (host == null || loadDurationMs <= 0) return;
        double[] v = ewma.computeIfAbsent(host, k -> new double[2]);
        synchronized (v) {
            double sec = loadDurationMs / 1000.0;
            if (v[1] == 0) {
                v[0] = sec;
            } else {
                v[0] = v[0] * (1 - ALPHA) + sec * ALPHA;
            }
            v[1]++;
        }
    }

    /**
     * 取该 host 的「下载耗时 / 发布间隔」比值。
     *
     * @param host                分片 host
     * @param targetDurationSec   清单 {@code targetDuration} 换算的秒数（= 发布间隔）
     * @return {@code ≤0.5} 表示可安全快轮询；{@code >0.5} 表示保持原生；{@code null} 表示
     *         样本不足或无数据（调用方应保守保持原生）
     */
    public Double getRatio(String host, double targetDurationSec) {
        if (host == null || targetDurationSec <= 0) return null;
        double[] v = ewma.get(host);
        if (v == null || v[1] < 2) return null;
        synchronized (v) {
            return v[0] / targetDurationSec;
        }
    }

    /** 仅供调试：某 host 是否已积累到可决策的样本。 */
    public boolean hasEnoughSamples(String host) {
        double[] v = ewma.get(host);
        return v != null && v[1] >= 2;
    }
}
