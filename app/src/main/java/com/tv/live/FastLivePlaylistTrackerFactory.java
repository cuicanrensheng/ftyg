package com.tv.live;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
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
import com.tv.live.TVPlayerManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 * <b>播放器不再按自己的 10s 节奏拉，改由 App 指定 targetDuration/2 节奏（10s→5s）</b>：
 * 发现延迟 ≤2s → 下载 4s 在播放头到边界前 ~5s 完成 → 振荡消失。
 *
 * <p><b>实现</b>（纯公开 API，零反射；media3 1.7.1 源码级实证）：
 * <ul>
 *   <li>{@link DefaultHlsPlaylistTracker} 重载周期 = 快照的 {@code targetDurationUs}
 *       （变化时）/{@code /2}（无变化时）——见 {@code processLoadedPlaylist} L854-855；</li>
 *   <li>用本工厂包住上层传入的 {@link HlsPlaylistParserFactory}（项目里是
 *       {@link LoggingHlsPlaylistParserFactory}），在每次媒体清单解析成功后把
 *       {@link HlsMediaPlaylist#targetDurationUs} 从 10s 改写为 targetDuration/2（21 参全参构造器
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

    /**
     * 【2026-10-27 跨 tracker 去重】快轮询决策日志原本是「每个 tracker 打一次」，
     * 但切台会重建 tracker → 实测 10 次切台打了 20 条（同一 host 重复 10 遍）。
     * 改为进程内按「类型|host」全局只记一次：换台不再刷，换到不同 host 仍各留一条。
     */
    private static final java.util.Set<String> FASTPOLL_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 【2026-10-27 二次收紧】sanitizeMediaPlaylist 里的「伪不连续净化 / EXTINF 校准」原先只做了
     * 「每 parser 实例一次」——但直播快轮询下**每次解析清单都会新建 parser**，等于每条清单刷一次
     * （2-5s 一条，一小时上千条）。改为同样按 host 全局一次。
     */

    /** 同一 key 是否首次（true=该记，false=已记过） */
    private static boolean firstTimeForHost(String kind, @Nullable Uri uri) {
        String host = (uri == null || uri.getHost() == null) ? "-" : uri.getHost();
        return FASTPOLL_LOGGED.add(kind + "|" + host);
    }

    /**
     * 改写后目标分片时长下限：2s。实际改写值 = max(原 targetDuration / 2, 本值)。
     * 例如 10s 片 → 5s 改写 → 重载节奏 ~5s（原为 1-2s），显著减少清单重载次数，缓解 live-edge 微缓冲抖动。
     */
    private static final long MIN_FAST_TARGET_DURATION_US = 2_000_000L;

    /**
     * 仅当原 targetDuration ≥ 5s 才改写。
     * 短分片源（≤4s）自身重载周期已足够短，改写只会徒增请求；
     * 5-10s 档（PLTV 直播 10s、常规 6s 源）才是锁相卡顿的受害区间。
     */
    private static final long MIN_REWRITABLE_TARGET_DURATION_US = 5_000_000L;

    /**
     * 【2026-10-27 EXTINF 校准阈值】媒体清单 EXTINF 与 {@code #EXT-X-PROGRAM-DATE-TIME} 推算的
     * 真实时长平均偏差 ≥ 本值（秒/片）才认定「EXTINF 撒谎」并按 PDT 改写。
     *
     * <p>实测：江西 ts2hls 线同一路流 250 帧（真实 9.96s）的分片 EXTINF 在 9/10 间抖动，
     * 平均偏差 0.46~0.96s/片 → 6~8 片后累积 4~8s 窗口漂移（设备日志 posAdvance=-7981ms）；
     * 正常源（CCTV1 原生 PLTV）偏差仅 0.04s/片，不会误触发。
     */
    private static final double EXTINF_MISMATCH_TOLERANCE_SEC = 0.25;

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
     * <p>【2026-10-27 运行时自适应】静态白名单门槛过高（未验证的线一律原生，仍有大量直播线在吃
     * 「每 10s 卡一下」）。现把判据升级为「静态白名单 ∪ 运行时自适应」：由
     * {@link LiveSegmentSpeedMeter} 按 host 维护分片下载耗时 EWMA，清单解析时算
     * 「下载耗时 / 发布间隔」比值，{@code ≤ 0.3} 即开快轮询（分片占用带宽低，开快轮询无回归），
     * {@code > 0.3} 保持原生（避免与分片抢带宽回归），样本不足保守原生。至此白名单仅作已知-safe 兜底，
     * 任意线都能在播放数秒后自动获得微抖根治，无需逐线人工验证。
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

        /** 进程内只打一次「静态白名单命中」日志。 */
        private final AtomicBoolean allowlistedLogged = new AtomicBoolean(false);

        /** 进程内只打一次「自适应样本不足」日志。 */
        private final AtomicBoolean adaptivePendingLogged = new AtomicBoolean(false);

        /** 进程内只打一次「自适应拒绝（比值>0.5）」日志。 */
        private final AtomicBoolean adaptiveRejectedLogged = new AtomicBoolean(false);

        /** 进程内只打一次「自适应生效（比值≤0.3）」日志。 */
        private final AtomicBoolean adaptiveEnabledLogged = new AtomicBoolean(false);

        /** 进程内只打一次「伪不连续净化」日志。 */
        private final AtomicBoolean strippedLogged = new AtomicBoolean(false);

        /** 进程内只打一次「EXTINF 按 PDT 校准」日志。 */
        private final AtomicBoolean extinfCalibLogged = new AtomicBoolean(false);

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
                    byte[] raw = Util.toByteArray(inputStream);
                    // 【2026-10-27 音频 1 秒冻结专项】净化伪不连续 + 用 PDT 校准 EXTINF，
                    // 详见 sanitizeMediaPlaylist 注释。返回原数组时零拷贝语义不变。
                    byte[] sanitized = sanitizeMediaPlaylist(uri, raw);
                    return maybeRewriteTargetDuration(
                            delegateParser.parse(uri, new ByteArrayInputStream(sanitized)), uri);
                }
            };
        }

        /**
         * 净化媒体清单：<b>剔除伪 {@code #EXT-X-DISCONTINUITY}</b> + <b>用 {@code #EXT-X-PROGRAM-DATE-TIME}
         * 校准 {@code #EXTINF}</b>（2026-10-27 江西2套「仍卡顿」专项）。
         *
         * <p><b>病根（本轮实测，188 源分片拉取 + 设备 9527 日志双证）</b>：源站 ts2hls 转换器产出的清单
         * 有两个独立缺陷，且<b>旧净化只治了其中一个</b>：
         * <ol>
         *   <li><b>EXTINF 撒谎约 1s</b>：同一路流 250 帧（真实 9.96s）的分片，EXTINF 在
         *       {@code 9} / {@code 10} 之间抖动（`#EXTINF:9` 对应真实 9.96s）。媒体清单同时给出
         *       {@code #EXT-X-PROGRAM-DATE-TIME}，其相邻差值恒为 <b>整 10s</b>——PDT 才是真值。
         *       media3 用 EXTINF 铺时间轴、用 PDT 做锚定，两者每片差 0~1s，累计到 6~8 片即
         *       <b>约 8s 的窗口漂移</b>；设备日志实测 {@code posAdvance=-7981ms/-8004ms}（位置回跳
         *       近一整片）每隔 40~60s 一次，紧跟 {@code dropped+=23}（1033ms 冻帧）。</li>
         *   <li><b>伪 DISCONTINUITY 只部分被剔</b>：旧安全阀要求「标签数 ≥ 分片数」，而江西都市实测
         *       <b>6 个 / 9 片</b>、江西少儿 8/9、江西新闻 7/9、江西公共农业 2/9 —— 全部落在阀外，
         *       6 次重锚定/窗口照旧发生，这就是「江西3套好了、江西2套还卡」的原因。</li>
         * </ol>
         *
         * <p><b>本版判据（PDT 为准，可自证）</b>：
         * <ul>
         *   <li>PDT 相邻差值合理（0.5s~30s）且每片都有 → {@code realSec[i]} 即该片真实时长；</li>
         *   <li>EXTINF 与 realSec 平均偏差 ≥ {@value #EXTINF_MISMATCH_TOLERANCE_SEC}s → <b>全部改写为 realSec</b>
         *       （收敛到 ±0.04s/片，与 CCTV1 原生线同级）；</li>
         *   <li>某个 DISCONTINUITY 边界「PDT 差 ≈ 前片真实时长（±0.2s）」→ <b>时间轴连续，标记是伪的</b>，剔除；
         *       窗口首片前的标记无边界语义，一并剔除。PDT 不可用时回退旧阀（标签数 ≥ 分片数 ≥ 3）。</li>
         * </ul>
         *
         * <p>实测依据：188 源分片拉取中，所有 ts2hls 线跨片 PTS 间隙恒为 +0.021s（音频）/+0.04s（视频），
         * 源流时间轴本身完全连续，因此「PDT 连续 → 无真实断点」的判定对这批源成立。
         *
         * <p>安全：任何解析异常/非媒体清单/分片数 &lt; 3 一律原样返回；未命中判据时返回原数组
         * （{@code Util.toByteArray} 的零拷贝语义不变）。
         */
        private byte[] sanitizeMediaPlaylist(@Nullable Uri uri, byte[] raw) {
            if (raw == null || raw.length == 0) return raw;
            final String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            final String[] lines = text.split("\n", -1);
            final int n = lines.length;

            // ---- 1) 扫描：分片 ↔ EXTINF / PDT / 前置 DISCONTINUITY
            final java.util.ArrayList<Double> extinfOf = new java.util.ArrayList<>();
            final java.util.ArrayList<Integer> extinfLineOf = new java.util.ArrayList<>();
            final java.util.ArrayList<Long> pdtOf = new java.util.ArrayList<>();
            final java.util.ArrayList<java.util.List<Integer>> discLinesOf = new java.util.ArrayList<>();
            java.util.List<Integer> pendingDisc = new java.util.ArrayList<>();
            double pendExtinf = Double.NaN;
            int pendExtinfLine = -1;
            long pendPdt = -1;
            int ndisc = 0;
            for (int i = 0; i < n; i++) {
                String s = lines[i].trim();
                if (s.isEmpty()) continue;
                if (s.charAt(0) == '#') {
                    if (isDiscontinuityTag(s)) {
                        pendingDisc.add(i);
                        ndisc++;
                    } else if (s.startsWith("#EXTINF:")) {
                        pendExtinf = parseExtinfSeconds(s);
                        pendExtinfLine = i;
                    } else if (s.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                        pendPdt = parseIsoTimeMs(
                                s.substring("#EXT-X-PROGRAM-DATE-TIME:".length()).trim());
                    }
                } else {
                    extinfOf.add(pendExtinf);
                    extinfLineOf.add(pendExtinfLine);
                    pdtOf.add(pendPdt);
                    discLinesOf.add(pendingDisc);
                    pendingDisc = new java.util.ArrayList<>();
                    pendExtinf = Double.NaN;
                    pendExtinfLine = -1;
                    pendPdt = -1;
                }
            }
            final int nseg = extinfOf.size();
            if (nseg < 3) return raw;

            // ---- 2) PDT = 时间真值 → 每片真实时长
            boolean pdtOk = true;
            for (int i = 0; i < nseg; i++) {
                if (pdtOf.get(i) < 0) {
                    pdtOk = false;
                    break;
                }
            }
            final double[] realSec = new double[nseg];
            if (pdtOk) {
                for (int i = 0; i < nseg - 1; i++) {
                    double d = (pdtOf.get(i + 1) - pdtOf.get(i)) / 1000.0;
                    if (d <= 0.5 || d > 30.0) {
                        pdtOk = false;
                        break;
                    }
                    realSec[i] = d;
                }
                if (pdtOk) realSec[nseg - 1] = realSec[nseg - 2];
            }

            // ---- 3) EXTINF 是否撒谎
            boolean fixExtinf = false;
            double avgErr = 0;
            if (pdtOk) {
                double sum = 0;
                int cnt = 0;
                for (int i = 0; i < nseg; i++) {
                    double e = extinfOf.get(i);
                    if (!Double.isNaN(e)) {
                        sum += Math.abs(realSec[i] - e);
                        cnt++;
                    }
                }
                if (cnt >= 3) {
                    avgErr = sum / cnt;
                    fixExtinf = avgErr >= EXTINF_MISMATCH_TOLERANCE_SEC;
                }
            }

            // ---- 4) 伪不连续判定
            final boolean discAll = ndisc >= nseg && ndisc >= 3;
            final java.util.HashSet<Integer> dropLines = new java.util.HashSet<>();
            for (int i = 0; i < nseg; i++) {
                java.util.List<Integer> dl = discLinesOf.get(i);
                if (dl.isEmpty()) continue;
                boolean fake;
                if (i == 0) {
                    fake = pdtOk || discAll;   // 窗口首片前的标记没有「边界」语义
                } else if (pdtOk) {
                    double gap = (pdtOf.get(i) - pdtOf.get(i - 1)) / 1000.0;
                    fake = Math.abs(gap - realSec[i - 1]) <= 0.2;   // PDT 连续 → 无真实断点
                } else {
                    fake = discAll;
                }
                if (fake) dropLines.addAll(dl);
            }

            if (dropLines.isEmpty() && !fixExtinf) return raw;

            // ---- 5) 重建清单
            final double[] newExtinfByLine = new double[n];
            java.util.Arrays.fill(newExtinfByLine, Double.NaN);
            if (fixExtinf) {
                for (int i = 0; i < nseg; i++) {
                    int ln = extinfLineOf.get(i);
                    if (ln >= 0) newExtinfByLine[ln] = realSec[i];
                }
            }
            final StringBuilder out = new StringBuilder(text.length() + 32);
            for (int i = 0; i < n; i++) {
                if (dropLines.contains(i)) continue;
                if (!Double.isNaN(newExtinfByLine[i])) {
                    out.append("#EXTINF:")
                            .append(String.format(java.util.Locale.US, "%.3f", newExtinfByLine[i]))
                            .append(",\n");
                    continue;
                }
                out.append(lines[i]);
                if (i < n - 1) out.append('\n');
            }
            if (!dropLines.isEmpty() && strippedLogged.compareAndSet(false, true)
                    && firstTimeForHost("disc", uri)) {
                LogBridge.i(TAG, "【伪不连续净化】按 PDT 连续性剔除 DISCONTINUITY " + dropLines.size()
                        + " 个（原 " + ndisc + " 个 / " + nseg + " 片，pdt=" + (pdtOk ? "ok" : "none")
                        + "）→ 消除每个边界的时间轴重锚定，uri=" + uri);
            }
            if (fixExtinf && extinfCalibLogged.compareAndSet(false, true)
                    && firstTimeForHost("extinf", uri)) {
                LogBridge.i(TAG, String.format(java.util.Locale.US,
                        "【EXTINF 校准】PDT 显示每片真实 %.3fs，清单 EXTINF 平均偏差 %.3fs/片 → 已按 PDT 改写（消除累计 %.1fs 的窗口漂移），uri=%s",
                        realSec[0], avgErr, avgErr * nseg, String.valueOf(uri)));
            }
            return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        /** 精确匹配 {@code #EXT-X-DISCONTINUITY}（排除 -SEQUENCE 等同类前缀标签）。 */
        private static boolean isDiscontinuityTag(String line) {
            final String tag = "#EXT-X-DISCONTINUITY";
            if (!line.startsWith(tag)) return false;
            final int next = tag.length();
            return line.length() <= next || line.charAt(next) != '-';
        }

        /** 解析 {@code #EXTINF:<秒>,[标题]} → 秒；失败返回 NaN。 */
        private static double parseExtinfSeconds(String line) {
            int start = "#EXTINF:".length();
            int end = line.length();
            for (int i = start; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ',' || c == ' ') {
                    end = i;
                    break;
                }
            }
            try {
                return Double.parseDouble(line.substring(start, end));
            } catch (Throwable t) {
                return Double.NaN;
            }
        }

        /**
         * 解析 ISO-8601 时间（如 {@code 2026-10-27T12:07:21Z} / {@code .123Z} / {@code +08:00}）→ epoch ms。
         * 纯整数历法（Howard Hinnant civil→days），不依赖 {@code java.time}（minSdk 21）。失败返回 -1。
         */
        private static long parseIsoTimeMs(String s) {
            if (s == null || s.length() < 19) return -1;
            int y = atoi(s, 0, 4);
            int mo = atoi(s, 5, 7);
            int d = atoi(s, 8, 10);
            int hh = atoi(s, 11, 13);
            int mi = atoi(s, 14, 16);
            int ss = atoi(s, 17, 19);
            if (y < 2000 || mo < 1 || mo > 12 || d < 1 || d > 31) return -1;
            long sec = daysFromCivil(y, mo, d) * 86400L + hh * 3600L + mi * 60L + ss;
            long ms = 0;
            int idx = 19;
            if (idx < s.length() && s.charAt(idx) == '.') {
                int st = idx + 1;
                idx = st;
                while (idx < s.length() && Character.isDigit(s.charAt(idx)) && idx - st < 3) idx++;
                int frac = atoi(s, st, idx);
                int digits = idx - st;
                for (int k = digits; k < 3; k++) frac *= 10;
                ms = frac;
                while (idx < s.length() && Character.isDigit(s.charAt(idx))) idx++;
            }
            if (idx < s.length()) {
                char c = s.charAt(idx);
                if (c == '+' || c == '-') {
                    int oh = atoi(s, idx + 1, idx + 3);
                    int om = atoi(s, idx + 4, idx + 6);
                    long off = oh * 3600L + om * 60L;
                    sec += (c == '+' ? -off : off);
                }
            }
            return sec * 1000L + ms;
        }

        /** Howard Hinnant civil_from_days 的逆运算（1970-01-01 为 0）。 */
        private static long daysFromCivil(int y, int m, int d) {
            y -= (m <= 2) ? 1 : 0;
            long era = (y >= 0 ? y : y - 399) / 400;
            long yoe = y - era * 400;
            long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
            long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
            return era * 146097 + doe - 719468;
        }

        private static int atoi(String s, int from, int to) {
            int v = 0;
            for (int i = from; i < to && i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') return -1;
                v = v * 10 + (c - '0');
            }
            return v;
        }

        /**
         * 把直播媒体清单的 targetDurationUs 改写为 targetDuration/2（10s→5s，驱动 tracker ~5s/2.5s 重载，减少重载次数）。
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
            // 【2026-10-27 方案1·live offset 落后补偿】直播媒体清单解析成功 → 回传原始 targetDuration，
            // 供 TVPlayerManager 按 td 自适应 live offset（td≥8s 落后约 2 片；<8s 回落默认 30s）。
            // 每次清单刷新都会回传，TVPlayerManager 内部按 td 去重（切台时重置标记）。
            try {
                TVPlayerManager.reportHlsTargetDuration(
                        media.targetDurationUs, uri != null ? uri.getHost() : null);
            } catch (Throwable ignored) {
            }
            if (media.targetDurationUs < MIN_REWRITABLE_TARGET_DURATION_US) {
                return playlist; // 短分片源天然快轮询
            }
            if (media.durationUs > MAX_FAST_POLL_WINDOW_US) {
                logGatedOutOnce(media, uri);
                return playlist; // 超大窗口：异常清单，保持原生轮询周期
            }
            // 【2026-10-27 自适应】三路判定，任一命中即开快轮询（改写值 = 原 td/2）：
            //   ① 静态白名单（已验证的 hwrr.jx，实测安全，零回归）；
            //   ② 运行时自适应：本 host「分片下载耗时 / 发布间隔」≤ 0.3 → 分片占用带宽低，
            //      开快轮询把「发布→清单可见」从 ≤10s 压到 ≤1s，根除每 10s 微抖，无回归；
            //   ③ 比值 > 0.3（分片占满发布间隔，如 39.135 类）→ 开快轮询会与分片抢带宽，回归，保持原生；
            //   ④ 样本不足/无数据 → 保守保持原生（首播前几秒无测量值，安全默认）。
            String host = uri != null ? uri.getHost() : null;
            boolean allowlisted = host != null && FAST_POLL_HOST_ALLOWLIST.contains(host);
            if (allowlisted) {
                // 白名单命中：沿用 2026-10-26 验证结论，维持原日志语义（"已纳入快轮询名单"）。
                logAllowlistedOnce(media, uri, host);
            } else {
                Double ratio = (host != null)
                        ? com.tv.live.LiveSegmentSpeedMeter.getInstance()
                            .getRatio(host, media.targetDurationUs / 1_000_000.0)
                        : null;
                if (ratio == null) {
                    logAdaptivePendingOnce(media, uri, host);
                    return playlist; // 样本不足：保持原生（首次播放前几秒，安全默认）
                }
                if (ratio > 0.3) {
                    logAdaptiveRejectedOnce(media, uri, host, ratio);
                    return playlist; // 分片占用带宽过高：开快轮询会回归，保持原生
                }
                logAdaptiveEnabledOnce(media, uri, host, ratio);
            }
            // 【2026-10-27 移除「缓冲余量守卫」】原逻辑：live 缓冲 < 4s 时 return playlist
            // （跳过快轮询改写，回退原生 5s/10s 轮询周期）。
            // 移除理由（10 台 × 30s 实测证据，CCTV7军事 18:32）：
            //   - 该守卫的出发点是「缓冲薄 = 贴着 live-edge，频繁重载会挤压边缘」；
            //     但 2.0.268 起直播 offset 已抬到 40s，播放头常驻离 live-edge 30-50s，
            //     前提不成立，快轮询不再有挤压边缘的风险；
            //   - 反效果显著：切台刚起播那几秒缓冲必然很薄（2s 级），守卫恰在此刻把
            //     快轮询关掉 → 清单回到 5s 刷新 → 新分片「发布→可见」多等最多 5s；
            //     CCTV7 正是因此把 14s 发布空窗拖成 3137ms 严重卡顿（缓冲 11.3s→1.3s）；
            //   - 结论：缓冲薄时比任何时候都更需要「尽早发现新分片」，守卫逻辑方向相反。
            // 其它自适应闸门（样本不足 pending / ratio > 0.3 带宽过高）保持不变。
            try {
                long fastTdUs = Math.max(media.targetDurationUs / 2, MIN_FAST_TARGET_DURATION_US);
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
                        fastTdUs, // ← 唯一改写：原 td/2（10s→5s），减少重载、缓解 live-edge 抖动
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
                if (rewriteLogged.compareAndSet(false, true) && firstTimeForHost("rewrite", uri)) {
                    LogBridge.i(TAG, "直播清单快轮询生效：targetDuration "
                            + (media.targetDurationUs / 1_000_000) + "s → "
                            + (fastTdUs / 1_000_000) + "s"
                            + "（重载周期 ~" + (fastTdUs / 1_000_000) + "s/其半，卡死阈值 30s），窗口 "
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
         * 静态白名单命中的线：沿用 2026-10-26 验证结论，记一次「已纳入快轮询名单」。
         */
        private void logAllowlistedOnce(HlsMediaPlaylist media, @Nullable Uri uri, @Nullable String host) {
            if (allowlistedLogged.compareAndSet(false, true) && firstTimeForHost("allow", uri)) {
            }
        }

        /**
         * 自适应：样本不足，保持原生轮询（安全默认，首播前几秒）。每个 tracker 只打一次。
         */
        private void logAdaptivePendingOnce(HlsMediaPlaylist media, @Nullable Uri uri, @Nullable String host) {
            if (adaptivePendingLogged.compareAndSet(false, true)) {
                LogBridge.i(TAG, "快轮询（自适应）样本不足，暂保持原生轮询（host=" + host
                        + "），待分片耗时采集 ≥2 次再决策，uri=" + uri);
            }
        }

        /**
         * 自适应：比值 > 0.3（分片占用带宽过高），开快轮询会回归，保持原生。每个 tracker 只打一次。
         */
        private void logAdaptiveRejectedOnce(HlsMediaPlaylist media, @Nullable Uri uri,
                                            @Nullable String host, double ratio) {
            if (adaptiveRejectedLogged.compareAndSet(false, true)) {
                LogBridge.i(TAG, "快轮询（自适应）拒绝（host=" + host + "）：下载耗时/发布间隔="
                        + String.format("%.2f", ratio) + " > 0.3，分片占用带宽过高，快轮询会回归，保持原生轮询，uri=" + uri);
            }
        }

        /**
         * 自适应：比值 ≤ 0.3（分片占用带宽低），开快轮询根治每 10s 微抖。每个 tracker 只打一次。
         */
        private void logAdaptiveEnabledOnce(HlsMediaPlaylist media, @Nullable Uri uri,
                                           @Nullable String host, double ratio) {
            if (adaptiveEnabledLogged.compareAndSet(false, true)) {
                LogBridge.d(TAG, "快轮询（自适应）生效（host=" + host + "）：下载耗时/发布间隔="
                        + String.format("%.2f", ratio) + " ≤ 0.3，targetDuration "
                        + (media.targetDurationUs / 1_000_000) + "s → "
                        + (media.targetDurationUs / 2_000_000) + "s，uri=" + uri);
            }
        }

    }
}
