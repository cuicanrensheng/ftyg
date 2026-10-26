package com.tv.live;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.HlsDataSourceFactory;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import com.tv.live.util.LogBridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HLS「直播清单快轮询」Tracker 工厂（2026-10-25 卡顿专项）。
 *
 * <p><b>病根</b>（2.0.250 四版本同机同网 A/B 实证，详见 TVPlayerManager 直播分支注释）：
 * PLTV 侧每 10s 发布一个新分片，而 ExoPlayer 自家的清单重载周期 =
 * {@code targetDuration}（快照变化时）/{@code targetDuration/2}（无变化时）——
 * 本线 {@code TARGETDURATION:10} → 播放器也是 10s 拉一次清单。
 * <b>发布节奏与轮询节奏同频锁相</b>，「新片发布 → 清单可见」最多迟 10s；
 * 2 片窗口（20s 内容）频道的播放头贴到清单尾缘时新片尚未进视野，
 * 只能现场下载 4s → 缓冲在 2s 阈值附近振荡（12ms 冻/40ms 播 ~30 次），每 10s 一次微抖。
 * targetOffset 任何值都无解（≥10s 被 2 片窗口 clamp 成贴缘，&lt;10s 提前量被清单可见延迟吃光）。
 *
 * <p><b>方案定型</b>：「播放器不拉清单」彻底停拉不可行——片段表冻结后播放头会冲出
 * 2 片窗口抛 {@code BehindLiveWindowException}（10-17 黑屏老坑）。正解是
 * <b>播放器不再按自己的 10s 节奏拉，改由 App 指定 2s 节奏</b>：
 * 发现延迟 ≤2s → 下载 4s 在播放头到边界前 ~5s 完成 → 振荡消失。
 *
 * <p><b>实现</b>（纯公开 API，零反射；media3 1.7.1 源码级实证）：
 * <ul>
 *   <li>{@link DefaultHlsPlaylistTracker} 重载周期 = 快照的 {@code targetDurationUs}
 *       （变化时）/{@code /2}（无变化时）——见 {@code processLoadedPlaylist} L854-855；</li>
 *   <li>用本工厂包住上层传入的 {@link HlsPlaylistParserFactory}（项目里是
 *       {@link LoggingHlsPlaylistParserFactory}），在每次媒体清单解析成功后把
 *       {@link HlsMediaPlaylist#targetDurationUs} 从 10s 改写为 2s（21 参全参构造器
 *       类型安全克隆，只改这一个字段；其余字段原样透传）；</li>
 *   <li>仅当原 td ≥ 5s 且清单无 ENDLIST（直播）才改写：短分片源天然就是快轮询无需改，
 *       点播清单不刷新、改写无意义；</li>
 *   <li><b>仅当清单窗口总时长 ≤ {@value #MAX_FAST_POLL_WINDOW_US} µs 才改写</b>——
 *       见 {@link #MAX_FAST_POLL_WINDOW_US} 的 A/B 数据依据：快轮询只救小窗口频道，
 *       大窗口频道不需要且会被引入微冻回归；</li>
 *   <li>卡死系数 3.5 → 15.0：卡死阈值 = td × 系数 = 2s × 15 = <b>30s</b>，
 *       必须大于实测最大发布尖刺 21s（约 30% 间隔超 11s）留 9s 余量，
 *       否则健康源会被误判 {@code PlaylistStuckException}；</li>
 *   <li>显式 {@code targetOffsetMs=30000} 不受影响：{@code HlsMediaSource}
 *       仅在 LiveConfiguration 未显式设置时才用 {@code 3 × targetDuration} 兜底（L753）；</li>
 *   <li>任何异常都回退原清单，绝不阻断播放链路；生效/被拒日志各只打一次，避免 2s 一条刷屏。</li>
 * </ul>
 *
 * <p><b>配套改动</b>：{@link DeadStreamMonitor} 的死流判定已从「连续 4 次序号不前进」
 * （按 9-10s 轮询标定）改为「40s 时间窗口」——2s 轮询下健康源每 10s 发布周期内有 8-9 次
 * 未前进，旧计数制 4-5s 即误判死流。
 */
@OptIn(markerClass = UnstableApi.class)
public final class FastLivePlaylistTrackerFactory implements HlsPlaylistTracker.Factory {

    private static final String TAG = "FastLiveMnt";

    /** 改写后的目标分片时长：2s（对应重载周期：快照变化 2s / 无变化 1s）。 */
    private static final long FAST_TARGET_DURATION_US = 2_000_000L;

    /**
     * 仅当原 targetDuration ≥ 5s 才改写。
     * 短分片源（≤4s）自身重载周期已足够短，改写只会徒增请求；
     * 5-10s 档（PLTV 直播 10s、常规 6s 源）才是锁相卡顿的受害区间。
     */
    private static final long MIN_REWRITABLE_TARGET_DURATION_US = 5_000_000L;

    /**
     * 仅当清单窗口总时长（{@link HlsMediaPlaylist#durationUs}，构造时按
     * 「末分片相对起点 + 末分片时长」计算）≤ 40s 才改写。
     *
     * <p><b>为什么必须门控</b>（2.0.251 同机同网 A/B 实证，TV 192.168.5.60）：
     * <ul>
     *   <li>hwrr.jx 投诉频道（CCTV15音乐）：2 片 × 10s = <b>20s 窗口</b>——
     *       10s 轮询稳态每 10s 一轮微抖（~30 次/10s，基线 187 次/2128ms/60s），
     *       2s 轮询稳态 60s <b>0 振荡</b>，根治；</li>
     *   <li>CCTV4欧洲（39.135.140.104:6610）：10 片 ≈ <b>90s 窗口</b>——
     *       10s 轮询 0 振荡，2s 轮询反而每 10s ~50 次 9-19ms 微冻 + 1 次 1292ms
     *       大冻，<b>快轮询引入的回归</b>（nofast 对照版同窗口 0 振荡，已排除
     *       吞吐/时钟/下载时长假说）。</li>
     * </ul>
     * 机理：窗口总时长 ≤ 播放器缓冲能力时，播放头会贴到清单尾缘，「新片发布→清单
     * 可见」的轮询延迟直接变成缓冲抖动，需要快轮询把延迟从 ≤10s 压到 ≤2s；窗口远大于
     * 缓冲的频道（90s runway）播放头永远追不上尾缘，本无边界问题，快轮询只会平添
     * 干扰（实测为清单加载与分片下载交错时的缓冲振荡）。
     *
     * <p><b>2026-10-26 门限前提被电视真机 A/B 推翻，门限值上调</b>：
     * 本常量原为 40s，依据是「hwrr.jx 投诉频道 = 2 片 × 10s = 20s 小窗口」。
     * 电视真机（创维 8N05_Q840，debug 包 + 9527 全量日志，2026-10-26 07:09 冷启动 A/B）实测：
     * <ul>
     *   <li>hwrr.jx 投诉频道（PLTV 224/3221225618）清单实为 <b>9 片 × td 10s = 90s 窗口</b>
     *       （模拟器 FastLiveMnt 日志「窗口 90s（9 片 × td 10s）」+ 电视 2.0.251 清单
     *       恒为 1059 字节=9 片，双证），<b>不是 2 片小窗口</b> —— 原前提是误判；</li>
     *   <li>2.0.252（门限 40s）把该频道挡在快轮询之外 → 清单回到原生 10s 节奏，
     *       与 PLTV 的 10s 发布节奏<b>同频锁相</b>：冷启动后 +13.7s 起
     *       <b>109 段微缓冲翻转（累计 ≈5.6s 停顿）</b>，release 包汇总为
     *       「严重卡顿 5563ms」→ <b>冷启动即卡，用户可感知</b>；</li>
     *   <li>同机同源量纲：分片 5233168 字节 / 下载 3.9s ≈ <b>10.8Mbps</b>（电视侧真实带宽），
     *       清单加载节律实测严格 10s（4.74/9.97/14.93/24.92…），新片「发布→清单可见」
     *       最迟 10s，播放头贴缘时新片尚未进视野 → 现场下 3.9s → 缓冲抖动；</li>
     *   <li>2.0.251（同机同网 A/B）该频道走快轮询时稳态 60s <b>0 振荡</b>，即根治。</li>
     * </ul>
     * 结论：<b>90s 大窗口的 hwrr.jx 频道同样必须快轮询</b>，「大窗口不需要快轮询」
     * 的原假设不成立。门限上调到 {@value #MAX_FAST_POLL_WINDOW_US} µs（覆盖 90s 窗口），
     * 门限机制保留（仅拦更大窗口/异常清单），取值放宽。
     * ⚠ 遗留：CCTV4欧洲（39.135.140.104:6610，同为 10 片≈90s 窗口）在 2.0.251 走快轮询
     * 时出现过微冻回归，需另找区分信号（该线分片 11.5MB/片，下载占满 10s 发布间隔的
     * 比例远高于本线 5.2MB/片）——详见 {@link TVPlayerManager} 直播分支注释。
     */
    private static final long MAX_FAST_POLL_WINDOW_US = 300_000_000L;

    /**
     * 卡死系数（{@link DefaultHlsPlaylistTracker} 默认 3.5）。
     * 卡死阈值 = 改写后 td(2s) × 系数 = 30s &gt; 实测最大发布尖刺 21s（留 9s 余量）。
     * 真死流仍会被 30s 的 PlaylistStuckException / 40s 的 DeadStreamMonitor 双层覆盖。
     */
    private static final double PLAYLIST_STUCK_COEFFICIENT = 15.0;

    /**
     * <b>【2026-10-26 电视真机 A/B 定案】快轮询按「线」开启，不按窗口大小。</b>
     *
     * <p>创维 8N05_Q840 + debug 包 + 9527 全量日志，2×2 矩阵（冷启动场景，2.0.252 源码）：
     * <pre>
     *   频道/线                     分片/片   下载耗时   占 10s 发布间隔   原生 10s 轮询          快轮询 1s
     *   hwrr.jx.chinamobile.com(90)  5.2MB     3.9s      39%             109 段缓冲(启动 2.9s)   1 段，稳态 0 ✅
     *   39.135.140.104(18)         11.5MB     7.5s      75%             2 次 1.2s 短暂缓冲 ✅   104 段缓冲 ❌
     * </pre>
     * 机理：PLTV 每 10s 发布一片，原生 10s 轮询与发布节奏<b>同频锁相</b>，播放头贴到清单
     * 尾缘时新片还没进视野 → 现场下载 → 每 10s 一次微抖（这就是用户投诉的「每 10s 卡一下」）。
     * 快轮询把「发布→清单可见」从 ≤10s 压到 ≤1s，下载有时间在播放头到边界前完成 → 根治；
     * 但分片本身要占满发布间隔的线路（75%），额外的清单请求会与分片抢带宽 → 反而饿死。
     *
     * <p>解析期拿不到码率（m3u8 只给时长不给字节数），静态判据只能用「线」：
     * <b>只对已验证的 hwrr.jx（移动 PLTV）线开快轮询，其余线一律保持原生 10s 轮询</b>
     * （= 2.0.250 行为，零回归）。未验证的新线默认原生，等实测后再决定是否纳入。
     *
     * <p>TODO：把静态名单换成运行时自适应判据「分片下载耗时 ≤ 0.5 × 发布间隔」
     * （需把数据源的传输耗时回喂给本工厂），即可摆脱白名单、覆盖任意线。
     */
    private static final java.util.Set<String> FAST_POLL_HOST_ALLOWLIST =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(
                    java.util.Arrays.asList("hwrr.jx.chinamobile.com")));

    @Override
    public HlsPlaylistTracker createTracker(
            HlsDataSourceFactory dataSourceFactory,
            LoadErrorHandlingPolicy loadErrorHandlingPolicy,
            HlsPlaylistParserFactory playlistParserFactory,
            @Nullable CmcdConfiguration cmcdConfiguration) {
        // 内嵌 td 改写工厂：上层工厂（Logging）照常回填多码率/喂死流检测，
        // 只是它返回的媒体清单在出栈前被改写 targetDurationUs。
        return new DefaultHlsPlaylistTracker(
                dataSourceFactory,
                loadErrorHandlingPolicy,
                new FastTargetDurationParserFactory(playlistParserFactory),
                cmcdConfiguration,
                PLAYLIST_STUCK_COEFFICIENT);
    }

    /**
     * 包装上层解析器工厂：媒体清单解析成功后把 targetDurationUs 10s → 2s。
     * 多码率主清单（{@link HlsMultivariantPlaylist}）无此字段，原样透传。
     */
    private static final class FastTargetDurationParserFactory implements HlsPlaylistParserFactory {

        private final HlsPlaylistParserFactory delegate;

        /** 进程内只打一次「快轮询生效」日志（改写每次解析都发生，2s 一条会刷屏）。 */
        private final AtomicBoolean rewriteLogged = new AtomicBoolean(false);

        /** 进程内只打一次「窗口过大不改写」日志（每频道一条，供现场统计窗口分布）。 */
        private final AtomicBoolean gatedLogged = new AtomicBoolean(false);

        /** 进程内只打一次「非白名单线 → 保持原生轮询」日志（每频道一条）。 */
        private final AtomicBoolean notAllowlistedLogged = new AtomicBoolean(false);

        FastTargetDurationParserFactory(HlsPlaylistParserFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
            return wrap(delegate.createPlaylistParser());
        }

        @Override
        public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
                HlsMultivariantPlaylist multivariantPlaylist,
                @Nullable HlsMediaPlaylist previousMediaPlaylist) {
            return wrap(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist));
        }

        private ParsingLoadable.Parser<HlsPlaylist> wrap(
                final ParsingLoadable.Parser<HlsPlaylist> delegateParser) {
            return new ParsingLoadable.Parser<HlsPlaylist>() {
                @Override
                public HlsPlaylist parse(Uri uri, InputStream inputStream) throws IOException {
                    return maybeRewriteTargetDuration(delegateParser.parse(uri, inputStream), uri);
                }
            };
        }

        /**
         * 把直播媒体清单的 targetDurationUs 改写为 2s（驱动 tracker 2s/1s 重载）。
         *
         * <p>四重门限全部满足才改写：① 媒体清单（非多码率主清单）② 无 ENDLIST（直播）
         * ③ 原 td ≥ 5s ④ <b>窗口总时长 ≤ {@value #MAX_FAST_POLL_WINDOW_US} µs</b>。
         * 任一不满足原样返回；克隆或字段访问出任何异常都回退原清单——
         * 改写只是优化，绝不能阻断播放。
         */
        private HlsPlaylist maybeRewriteTargetDuration(HlsPlaylist playlist, @Nullable Uri uri) {
            if (!(playlist instanceof HlsMediaPlaylist)) {
                return playlist;
            }
            final HlsMediaPlaylist media = (HlsMediaPlaylist) playlist;
            if (media.hasEndTag) {
                return playlist; // 点播：不刷新，改写无意义
            }
            if (media.targetDurationUs < MIN_REWRITABLE_TARGET_DURATION_US) {
                return playlist; // 短分片源天然快轮询
            }
            if (media.durationUs > MAX_FAST_POLL_WINDOW_US) {
                logGatedOutOnce(media, uri);
                return playlist; // 超大窗口：异常清单，保持原生轮询周期
            }
            // 【2026-10-26】按线判定：只有已验证的 hwrr.jx（移动 PLTV）线开快轮询。
            // 39.135 线（分片 11.5MB/片、下载占满 10s 发布间隔的 75%）实测快轮询会回归。
            String host = uri != null ? uri.getHost() : null;
            if (host == null || !FAST_POLL_HOST_ALLOWLIST.contains(host)) {
                logNotAllowlistedOnce(media, uri, host);
                return playlist; // 未验证的线：保持原生轮询（= 2.0.250 行为，零回归）
            }
            try {
                HlsMediaPlaylist fast = new HlsMediaPlaylist(
                        media.playlistType,
                        media.baseUri,
                        media.tags,
                        media.startOffsetUs,
                        media.preciseStart,
                        media.startTimeUs,
                        media.hasDiscontinuitySequence,
                        media.discontinuitySequence,
                        media.mediaSequence,
                        media.version,
                        FAST_TARGET_DURATION_US, // ← 唯一改写的字段
                        media.partTargetDurationUs,
                        media.hasIndependentSegments,
                        media.hasEndTag,
                        media.hasProgramDateTime,
                        media.protectionSchemes,
                        media.segments,
                        media.trailingParts,
                        media.serverControl,
                        media.renditionReports,
                        media.interstitials);
                if (rewriteLogged.compareAndSet(false, true)) {
                    LogBridge.i(TAG, "直播清单快轮询生效：targetDuration "
                            + (media.targetDurationUs / 1_000_000) + "s → 2s"
                            + "（重载周期 2s/1s，卡死阈值 30s），窗口 "
                            + (media.durationUs / 1_000_000) + "s/" + media.segments.size() + " 片，uri=" + uri);
                }
                return fast;
            } catch (Throwable t) {
                LogBridge.w(TAG, "targetDuration 改写失败，回退原清单（不影响播放）: " + t);
                return playlist;
            }
        }

        /**
         * 非白名单线：保持媒体3原生轮询周期（td / td÷2），每个 tracker 只打一次。
         * 【2026-10-26】文案同步订正：原「窗口大 → 播放头追不上尾缘 → 无需快轮询」的前提
         * 已被电视真机 A/B 推翻（90s 窗口的投诉频道同样需要快轮询），现在真正的理由是
         * 「本线未做快轮询收益验证（分片占用带宽过高时快轮询反而回归）」。
         */
        private void logGatedOutOnce(HlsMediaPlaylist media, @Nullable Uri uri) {
            if (gatedLogged.compareAndSet(false, true)) {
                LogBridge.i(TAG, "直播清单窗口 " + (media.durationUs / 1_000_000) + "s（"
                        + media.segments.size() + " 片 × td "
                        + (media.targetDurationUs / 1_000_000) + "s）> "
                        + (MAX_FAST_POLL_WINDOW_US / 1_000_000) + "s，保持原生轮询周期 "
                        + (media.targetDurationUs / 1_000_000) + "s（超大窗口，异常清单），uri=" + uri);
            }
        }

        /**
         * 未在快轮询白名单内的线：保持原生轮询周期（= 2.0.250 行为，零回归）。
         * 每个 tracker 只打一次，便于现场统计各线的归属。
         */
        private void logNotAllowlistedOnce(HlsMediaPlaylist media, @Nullable Uri uri, @Nullable String host) {
            if (notAllowlistedLogged.compareAndSet(false, true)) {
                LogBridge.i(TAG, "该线未开启快轮询（host=" + host + "），保持原生轮询周期 "
                        + (media.targetDurationUs / 1_000_000) + "s：窗口 "
                        + (media.durationUs / 1_000_000) + "s/" + media.segments.size() + " 片，"
                        + "本线分片占用带宽较高，快轮询实测会回归（2026-10-26 电视真机 A/B），uri=" + uri);
            }
        }
    }
}
