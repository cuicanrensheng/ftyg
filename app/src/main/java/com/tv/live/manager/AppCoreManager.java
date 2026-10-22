package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import android.content.BroadcastReceiver;
import com.tv.live.util.LogBridge;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.util.AppExecutors;
import com.tv.live.util.CacheManager;
import com.tv.live.SourceManager;
import com.tv.live.util.HuyaSDKParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppCoreManager {
    private static final long LOAD_TIMEOUT = 15000;

    // 【2026-09-29 启动卡顿修复】缓存已起播时，网络刷新延后执行。
    // 电视真机铁证（17:51 会话）：首帧后前 33s 连续 7 次严重卡顿（2~3.7s），
    // 根因 = 启动初期「直播源网络下载(7.8s)+解析(1.7s)」「虎牙 API 增量拉取(26.6s)」
    // 与视频 HLS 分片(5MB/7~10s 一个)并发抢带宽 → 分片在播放窗口内下不完。
    // 17:52 后台任务做完后零卡顿、分片秒级下完（带宽 >40Mbps 充足）。
    // 故把缓存命中后的网络刷新推迟到播放稳定窗口之后。
    private static final long NETWORK_REFRESH_DELAY_MS = 30000;
    // 距上次网络加载不足该值时，延后刷新直接跳过（避免手动切源后 30s 又多一次下载）
    private static final long NETWORK_REFRESH_SKIP_MS = 60000;
    /** 【2026-10-05】直播源缓存有效期：7 天。7 天内启动直接用本地缓存，不重新下载，
     *  避免直播源链接下载次数限制；满 7 天才重新拉一次。 */
    private static final long LIVE_SOURCE_CACHE_VALID_MS = 7L * 24 * 60 * 60 * 1000;
    private volatile long lastNetworkLoadAtMs = 0;
    private static final int MAX_CONSECUTIVE_SKIP = 10;

    private Context context;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private CacheManager cacheManager;

    private List<Channel> channelSourceList = new ArrayList<>();
    private final Object channelListLock = new Object();

    /** 【2026-10-22 切源跨实例同步】本实例 channelSourceList 对应的直播源地址。
     *  用于判断切源后本实例数据是否已过期（见 adoptSharedResultIfNewer）。 */
    private volatile String loadedSourceUrl;

    /** 【2026-10-22 切源跨实例同步】最近一次成功加载的直播源结果（静态共享）。
     *
     *  <p>问题现场：{@code channelSourceList} 是<b>实例</b>字段，而「加载在飞 / 同源去重」
     *  标记是<b>静态</b>共享 → 用户切源时由 A 实例（如 MainActivity）执行加载，B 实例
     *  （OTT 首页）收到广播后判定「已有加载在飞」只登记 pending；A 加载结束后 B 的重载
     *  又被「与刚完成的加载同源，折叠为一次」挡掉 —— B 自己又不是空列表（还是上一个源的
     *  频道），旧实现只在「空列表」时才补缓存回调 → B 永远拿不到新源列表。
     *  用户实测：切到源6(咪咕) 后 OTT 首页仍显示源5 的频道，重启应用才生效。
     *
     *  <p>修复：加载成功的实例把结果写进这两个静态字段，其他实例在被去重/折叠挡下时
     *  调用 {@link #adoptSharedResultIfNewer()} 主动同步（纯内存复制，不额外请求第三方）。 */
    private static volatile List<Channel> sLastLoadedChannels;
    private static volatile String sLastLoadedChannelsUrl;

    private boolean hasPlayedWithCache = false;
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;

    // 【P0-C】直播源加载去重（全局）：MainActivity 与 OttHomeActivity 各自 new 了一个
    // AppCoreManager，若去重字段是实例字段，则守卫变成「每实例一份」→ 一次切源并发跑 2~3 份
    // 完全相同的加载（日志里就是 3 条 "LOAD: source count="）并重复请求第三方站点。
    // 因此这两个字段必须 static，全局共用同一份闸门。
    private static final java.util.concurrent.atomic.AtomicBoolean liveSourceLoadInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // 【2026-10-11 切源单飞】切源刷新与在飞加载必须互斥（绝不并发请求第三方），但切源又
    // 不能被丢弃：若切源请求到达时已有加载在飞，只登记「待重载」标记，等本次加载结束后
    // 串行接续执行。这样「切源一定生效」与「绝不并发」两个诉求同时满足。
    private static final java.util.concurrent.atomic.AtomicBoolean liveSourceReloadPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long lastLiveSourceLoadAtMs = 0;
    /** 最近一次「已发起」的直播源 URL：用于把多个接收器收到的同源重复切源广播折叠成一次 */
    private static volatile String sLastLoadedSourceUrl = null;
    /** 最近一次加载「结束」时刻（开机基准），用于同源折叠的时间窗判定 */
    private static volatile long sLastLoadDoneAtMs = 0;
    /**
     * 加载代次号：每次真正发起加载时自增。回调与收口前先比对代次，<b>过期加载</b>
     * （已被新的切源取代的那一次）其结果一律丢弃 —— 否则旧源的频道会被 mergeChannels
     * 混进新源的频道列表（表现为「切到源7，分组/频道里混着别的源」）。
     */
    private static final java.util.concurrent.atomic.AtomicLong sLoadGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final long LOAD_DEDUPE_WINDOW_MS = 6000;
    private static final long LOAD_DEDUPE_RELEASE_MS = 30000;
    private final Handler dedupeHandler = new Handler(Looper.getMainLooper());
    /**
     * 闸门兜底释放专用 handler（static）：闸门本身是 static 的（多实例共用），
     * 释放任务也必须共用同一份，否则 A 实例的旧释放任务会在 B 实例的加载进行中提前清闸。
     */
    private static final Handler sReleaseHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver toggleControllerReceiver;
    private BroadcastReceiver refreshReceiver;
    private boolean receiversRegistered = false;

    private boolean isOpeningSettings = false;
    private boolean isControllerVisible = false;

    private int consecutiveFailedCount = 0;
    private OnSourceSkipListener sourceSkipListener;

    private OnDataLoadListener dataLoadListener;
    private OnRefreshListener refreshListener;

    public interface OnDataLoadListener {
        void onLiveSourceLoaded(List<Channel> channels, boolean fromCache);
        void onLiveSourceFailed(String errorMsg);
        void onEpgLoaded();
        void onLoadTimeout(boolean hasData);
    }
    public interface OnRefreshListener { void onRefreshNeeded(); }
    public interface OnSourceSkipListener {
        void onNeedSkipChannel();
        void onSkipLimitReached(int maxSkip);
        void onSourceFailed(String channelName, int failedCount);
    }

    public AppCoreManager(Context context, TVPlayerManager playerManager, AppConfig appConfig) {
        this.context = context.getApplicationContext();
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.cacheManager = CacheManager.getInstance(context);
    }

    static <T> List<T> sanitizeChannels(List<T> channels) {
        return (channels != null) ? channels : new ArrayList<>();
    }

    static <T> List<T> ensureChannelListNotNull(List<T> existing) {
        return (existing != null) ? existing : new ArrayList<>();
    }

    /**
     * 【P0-C】直播源加载入口（带去重闸门）。
     * 并发调用直接拦截；串行重复调用在 6s 窗口内也拦截。
     * 真正的加载逻辑在 loadLiveAndEpgInternal()。
     */
    public void loadLiveAndEpg() {
        requestLiveSourceLoad(false);
    }

    /**
     * 【2026-10-11 切源单飞】用户显式切换直播源后调用（由 REFRESH_LIVE_AND_EPG 广播触发）。
     *
     * <p>与 {@link #loadLiveAndEpg()} 的区别：切源是用户显式意图，优先级最高，既不能被
     * 「6s 去重窗口」挡掉，也不能被「另一个 AppCoreManager 实例正在飞的加载」挡掉
     * （否则表现为「切了源界面没反应」）。但为了遵守「绝不对第三方并发/批量请求」，它
     * <b>也不清闸硬闯</b>：若此刻已有加载在飞，只登记待重载标记，等本次加载结束后串行接续。
     *
     * <p>另外，切源广播会被 MainActivity 与 OttHomeActivity 各自的 AppCoreManager 各收一次，
     * 同源的重复广播由 {@link #sLastLoadedSourceUrl} 折叠成一次，避免重复请求第三方。
     */
    public void forceReloadLiveSource() {
        requestLiveSourceLoad(true);
    }

    private void requestLiveSourceLoad(boolean forced) {
        long now = android.os.SystemClock.elapsedRealtime();

        // ① 已有加载在飞：不并发。显式切源登记「待重载」，等它结束后串行接续（保证切源不被丢弃）。
        if (liveSourceLoadInFlight.get()) {
            if (forced) {
                liveSourceReloadPending.set(true);
                log("【直播源】已有加载在飞，已登记切源重载（本次加载结束后串行接续，不并发）");
            } else {
                log("【直播源】上一次加载仍在进行，跳过重复加载");
            }
            // 【修复】本实例尚无数据（如 Activity 重建产生的新 AppCoreManager 实例被全局闸门挡住）：
            // 不能直接 return 导致本实例 UI 永远空白。纯本地读磁盘缓存补一份数据回调本实例 listener。
            supplyCacheToListenerIfEmpty();
            // 【2026-10-22 切源跨实例同步】若是切源且别的实例已加载完新源，直接吸收其结果
            if (forced) adoptSharedResultIfNewer();
            return;
        }

        // ② 源未变化的重复刷新折叠：切源广播会被多个 AppCoreManager 实例各收一次，
        //    逐次都真发就会重复请求第三方；若刚发起/刚结束的加载就是同一个源，直接折叠。
        String cur = UrlConfig.LIVE_URL;
        if (forced) {
            if (cur != null && cur.equals(sLastLoadedSourceUrl)
                    && now - sLastLoadDoneAtMs < LOAD_DEDUPE_WINDOW_MS) {
                log("【直播源】切源刷新与刚完成的加载同源，折叠为一次，跳过重复刷新");
                // 【2026-10-22 切源跨实例同步】折叠只表示"不必再发网络请求"，
                // 但本实例若有可能是别的实例加载的 → 必须把共享结果同步过来，否则 UI 停在旧源。
                adoptSharedResultIfNewer();
                return;
            }
        } else if (now - lastLiveSourceLoadAtMs < LOAD_DEDUPE_WINDOW_MS) {
            log("【直播源】距上次加载不足 " + (LOAD_DEDUPE_WINDOW_MS / 1000) + "s，跳过重复加载");
            // 【修复】全局 6s 去重窗口是为防止重复请求第三方；但 Activity 重建产生的新实例
            // 自己 channelSourceList 为空，被挡后没有数据填充 UI（表现为退出再进 OTT 只剩播放器、
            // 分组与频道全空）。此处同样纯本地读缓存补回调，不请求第三方。
            supplyCacheToListenerIfEmpty();
            // 【2026-10-22 切源跨实例同步】非空但属于旧源时也要同步共享结果
            adoptSharedResultIfNewer();
            return;
        }

        if (!liveSourceLoadInFlight.compareAndSet(false, true)) {
            log("【直播源】并发加载被去重拦截");
            return;
        }
        final long gen = sLoadGeneration.incrementAndGet();
        lastLiveSourceLoadAtMs = now;
        sLastLoadedSourceUrl = cur;
        // 兜底释放：异常路径若没走到 onSuccess/onError，避免标志永久卡死。
        // 先清掉上一轮未触发的释放任务，否则旧任务会在新加载进行中提前清闸。
        sReleaseHandler.removeCallbacksAndMessages(null);
        sReleaseHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finishLiveSourceLoad(gen);
            }
        }, LOAD_DEDUPE_RELEASE_MS);
        try {
            loadLiveAndEpgInternal(gen);
        } catch (Throwable t) {
            finishLiveSourceLoad(gen);
            log("【直播源】加载异常：" + t.getMessage());
        }
    }

    /**
     * 一次直播源加载结束（成功 / 失败 / 异常 / 兜底超时释放）统一收口：
     * 记录结束时刻 → 释放闸门 → 若有登记过的「切源重载」则串行接续执行。
     *
     * <p>幂等：重复调用只会把闸门再置 false，无害。
     */
    /**
     * 【2026-10-14 修复】本实例频道列表为空时，从磁盘缓存补一份数据回调给本实例 listener。
     * 用途：Activity 重建产生新的 AppCoreManager 实例后，其 loadLiveAndEpg() 被全局去重闸门
     * （liveSourceLoadInFlight / 6s 窗口）挡住时，不能让本实例 UI 永远空白。
     * 纯本地读磁盘缓存文件（readLiveSourceCacheRaw），不请求第三方站点。
     * 回调走 OttHomeActivity 内部的 mainHandler.post，从任意线程调用均安全。
     */
    private void supplyCacheToListenerIfEmpty() {
        if (dataLoadListener == null) return;
        synchronized (channelListLock) {
            if (!channelSourceList.isEmpty()) return;
            try {
                String cacheContent = readLiveSourceCacheRaw();
                if (cacheContent == null || cacheContent.isEmpty()) return;
                List<Channel> cached = parseLiveSource(cacheContent);
                if (cached == null || cached.isEmpty()) return;
                channelSourceList.clear();
                channelSourceList.addAll(cached);
                // ⚠ 不要在这里把 loadedSourceUrl 标成当前 LIVE_URL：切源瞬间磁盘里可能还是
                //   上一个源的缓存，标错会让 adoptSharedResultIfNewer() 误判"已是最新"而跳过同步。
                dataLoadListener.onLiveSourceLoaded(cached, true);
                log("【直播源】被全局去重窗口拦截，已从磁盘缓存补回调本实例，频道数：" + cached.size());
            } catch (Throwable t) {
                log("【直播源】补缓存回调异常：" + t.getMessage());
            }
        }
    }

    /**
     * 【2026-10-22 切源跨实例同步】本实例被「加载在飞 / 同源折叠」挡下时，主动吸收
     * 静态共享位里别的实例刚加载成功的直播源结果。
     *
     * <p>典型时序（用户实测）：OTT 首页切源6 →
     * ① 本实例收到广播时 MainActivity 的实例正好在加载 → 登记 pending 返回；
     * ② 对方加载完成（共享位 = 源6，342 台）；
     * ③ pending 触发的重载被判「同源，折叠」返回。
     * 旧实现在 ①③ 只做 {@code supplyCacheToListenerIfEmpty()}（本实例非空 → 直接 return），
     * 结果 UI 一直停在旧源频道上，必须重启应用。此处补上共享结果同步。
     */
    private void adoptSharedResultIfNewer() {
        List<Channel> shared = sLastLoadedChannels;
        if (shared == null || shared.isEmpty()) return;
        final String sharedUrl = sLastLoadedChannelsUrl;
        final List<Channel> copy;
        synchronized (channelListLock) {
            // 已有数据且就是当前共享源 → 无需同步
            if (!channelSourceList.isEmpty() && TextUtils.equals(sharedUrl, loadedSourceUrl)) return;
            channelSourceList.clear();
            channelSourceList.addAll(shared);
            copy = new ArrayList<>(shared);
        }
        loadedSourceUrl = sharedUrl;
        if (dataLoadListener != null) {
            dataLoadListener.onLiveSourceLoaded(copy, false);
        }
        log("【直播源】已从共享加载结果同步本实例：源=" + sharedUrl + "，频道数=" + copy.size());
    }

    private void finishLiveSourceLoad(long gen) {
        if (gen != sLoadGeneration.get()) {
            log("【直播源】忽略过期加载的收口（gen=" + gen + "，当前=" + sLoadGeneration.get()
                    + "）：不改闸门、不触发重载");
            return;
        }
        sLastLoadDoneAtMs = android.os.SystemClock.elapsedRealtime();
        liveSourceLoadInFlight.set(false);
        if (liveSourceReloadPending.compareAndSet(true, false)) {
            log("【直播源】执行登记的切源重载（串行接续）");
            AppExecutors.io(() -> requestLiveSourceLoad(true));
        }
    }

    private void loadLiveAndEpgInternal(long gen) {
        log("【直播源】开始加载直播源...");
        isLoading = true;

        timeoutHandler.postDelayed(() -> {
            if (isLoading) {
                log("【加载】超时，自动隐藏加载动画");
                boolean hasData;
                synchronized (channelListLock) {
                    hasData = !channelSourceList.isEmpty();
                }
                if (dataLoadListener != null) {
                    dataLoadListener.onLoadTimeout(hasData);
                }

                isLoading = false;
            }
        }, LOAD_TIMEOUT);

        boolean cacheHit = false;
        // 【2026-10-05】改用直读文件（绕过 CacheManager 24h 有效期），配合 7 天策略：
        // 7 天内启动始终能用本地缓存快速显示，不再因 24h 过期而 cacheHit=false 触发网络下载。
        String cacheContent = readLiveSourceCacheRaw();
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                synchronized (channelListLock) {
                    channelSourceList.clear();
                    channelSourceList.addAll(cacheChannels);
                }
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(cacheChannels, true);
                }
                loadEpgCache();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
                collectAndPreloadHuyaRooms(cacheChannels, "缓存");
                cacheHit = true;
            }
        }

        if (!cacheHit && dataLoadListener != null) {
            dataLoadListener.onLiveSourceLoaded(new ArrayList<Channel>(), true);
            log("【缓存】无缓存，立即渲染空UI，后台继续网络加载");
        }

        if (cacheHit) {
            // 【2026-10-05】7 天策略：直播源缓存未满 7 天时跳过网络刷新，直接沿用本地缓存。
            // 【2026-10-05 修复】不能直接 return：切换 EPG 订阅源走同一广播进来，若在此拦掉，
            // 新 EPG 源永远没有下载机会（表现为切换后节目单一直空白/旧数据）。
            // loadEpg() 内部自带 7 天+缓存来源校验：EPG 缓存有效→只用缓存；换源/损坏/过期→下载。
            if (isLiveSourceCacheFresh()) {
                log("【直播源】缓存未满 7 天，跳过网络刷新，直接使用本地缓存");
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                loadEpg();
                // 【切源单飞】此路径不触发网络加载，必须立刻收口释放闸门，
                // 否则闸门要等 30s 兜底释放，期间的切源操作会退化成「等 30s 才生效」。
                finishLiveSourceLoad(gen);
                return;
            }
            // 【2026-09-29 启动卡顿修复】缓存已起播 → 网络刷新延后 30s 错峰，让分片独享带宽
            log("【网络】缓存已满 7 天需更新，网络刷新延后 " + (NETWORK_REFRESH_DELAY_MS / 1000)
                    + "s 错峰（避开起播带宽窗口）");
            dedupeHandler.postDelayed(() -> {
                try {
                    long since = android.os.SystemClock.elapsedRealtime() - lastNetworkLoadAtMs;
                    if (lastNetworkLoadAtMs > 0 && since < NETWORK_REFRESH_SKIP_MS) {
                        log("【网络】延后刷新跳过：距上次网络加载仅 " + (since / 1000) + "s");
                        return;
                    }
                    log("【网络】延后的直播源刷新开始（错峰）...");
                    startNetworkLiveSourceLoad(gen);
                } catch (Throwable t) {
                    log("【网络】延后刷新异常：" + t.getMessage());
                }
            }, NETWORK_REFRESH_DELAY_MS);
        } else {
            startNetworkLiveSourceLoad(gen);
        }
    }

    /** 【2026-09-29 启动卡顿修复】从 loadLiveAndEpgInternal 抽出的后台网络加载（原逻辑不动）。 */
    private void startNetworkLiveSourceLoad(long gen) {
        lastNetworkLoadAtMs = android.os.SystemClock.elapsedRealtime();
        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(context).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                // 【切源单飞】过期加载（已被新的切源取代）的结果一律丢弃，否则会把旧源的
                // 频道 mergeChannels 混进新源列表（表现为「切到源7，频道里混着别的源」）。
                if (gen != sLoadGeneration.get()) {
                    log("【网络】忽略过期加载的成功结果（gen=" + gen + "，当前="
                            + sLoadGeneration.get() + "），不合并进频道列表");
                    return;
                }
                finishLiveSourceLoad(gen);
                List<Channel> safeChannels = sanitizeChannels(channels);
                log("【网络】直播源加载成功，频道总数：" + safeChannels.size());
                synchronized (channelListLock) {
                    channelSourceList = ensureChannelListNotNull(channelSourceList);
                    if (safeChannels.isEmpty()) {

                        channelSourceList.clear();
                    } else if (channelSourceList.isEmpty()) {
                        channelSourceList.addAll(safeChannels);
                    } else {

                        mergeChannels(safeChannels);
                    }
                }
                timeoutHandler.removeCallbacksAndMessages(null);

                // 【2026-10-22 切源跨实例同步】把本次成功结果发布到静态共享位，
                // 供其他 AppCoreManager 实例（被去重/折叠挡下的那一侧）同步使用。
                if (!safeChannels.isEmpty()) {
                    loadedSourceUrl = UrlConfig.LIVE_URL;
                    sLastLoadedChannelsUrl = UrlConfig.LIVE_URL;
                    sLastLoadedChannels = new ArrayList<>(safeChannels);
                }

                boolean firstTime = isLoading;
                isLoading = false;
                if (dataLoadListener != null) {

                    dataLoadListener.onLiveSourceLoaded(safeChannels, false);

                    if (safeChannels.isEmpty()) {
                        dataLoadListener.onLiveSourceFailed("直播源返回 0 个频道，地址可能已失效或暂时不可达");
                    }
                }
                if (firstTime) {
                    log("【网络】直播源列表已更新");
                } else {
                    log("【网络】直播源列表已更新（超时后补到数据）");
                }
                if (!safeChannels.isEmpty()) {
                    collectAndPreloadHuyaRooms(safeChannels, "网络");
                }
                loadEpg();
            }

            @Override
            public void onError(String errorMsg) {
                if (gen != sLoadGeneration.get()) {
                    log("【网络】忽略过期加载的失败结果（gen=" + gen + "，当前="
                            + sLoadGeneration.get() + "）");
                    return;
                }
                finishLiveSourceLoad(gen);
                log("【网络】直播源加载失败：" + errorMsg);
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceFailed(errorMsg);
                }
                loadEpgCache();
            }
        });
    }

    private void collectAndPreloadHuyaRooms(List<Channel> channels, String source) {
        if (channels == null || channels.isEmpty()) return;
        try {
            java.util.ArrayList<Integer> roomIds = new java.util.ArrayList<>();
            java.util.regex.Pattern huyaProtocol = java.util.regex.Pattern.compile("huya://room/(\\d+)");

            java.util.regex.Pattern huyaHttpRoom = java.util.regex.Pattern.compile("(?:m\\.|www\\.)?huya\\.com/(\\d+)(?:[?#/].*)?$");
            java.util.regex.Pattern huyaProfileId = java.util.regex.Pattern.compile("profileRoom=(\\d+)");
            java.util.regex.Pattern huyaRoomIdInUrl = java.util.regex.Pattern.compile("[?&]roomId=(\\d+)");
            for (Channel ch : channels) {
                if (ch == null) continue;
                boolean added = false;

                if (ch.getHuyaRoomId() > 0) {
                    roomIds.add(ch.getHuyaRoomId());
                    continue;
                }

                java.util.List<String> urls = new java.util.ArrayList<>();
                if (!TextUtils.isEmpty(ch.getMainPlayUrl())) urls.add(ch.getMainPlayUrl());
                if (ch.getBackupUrls() != null) urls.addAll(ch.getBackupUrls());
                for (String u : urls) {
                    if (TextUtils.isEmpty(u)) continue;
                    String trimmed = u.trim();
                    java.util.regex.Matcher m1 = huyaProtocol.matcher(trimmed);
                    if (m1.find()) {
                        try { roomIds.add(Integer.parseInt(m1.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                    java.util.regex.Matcher m2 = huyaHttpRoom.matcher(trimmed);
                    if (m2.find()) {
                        try { roomIds.add(Integer.parseInt(m2.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                    java.util.regex.Matcher m3 = huyaProfileId.matcher(trimmed);
                    if (m3.find()) {
                        try { roomIds.add(Integer.parseInt(m3.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                    java.util.regex.Matcher m4 = huyaRoomIdInUrl.matcher(trimmed);
                    if (m4.find()) {
                        try { roomIds.add(Integer.parseInt(m4.group(1))); added = true; break; } catch (Exception ignored) {}
                    }
                }
            }
            if (!roomIds.isEmpty()) {
                log("🟢【虎牙预解析】(" + source + ") 收集到 " + roomIds.size() + " 个虎牙房间，开始后台并行预解析");
                HuyaSDKParser.preloadRooms(roomIds);
            } else {
                log("🟡【虎牙预解析】(" + source + ") 未识别到虎牙房间，跳过预解析（列表共 " + channels.size() + " 个频道）");
            }
        } catch (Throwable t) {
            log("【虎牙预解析】收集异常: " + t.getMessage());
        }
    }

    private void loadEpgCache() {
        log("【EPG】尝试从缓存加载...");
        // 【2026-10-05 提速】原实现是空壳（只回调 onEpgLoaded 刷信息栏，不加载 EPG 数据）。
        // 导致冷启动直播源缓存命中时，EPG 节目单数据要等到 30s 后网络错峰刷新 → loadEpg()
        // 才真正加载磁盘缓存 → 用户打开 EPG 面板长时间空白。
        // 现在冷启动即先行加载 EPG 磁盘缓存数据（毫秒级解析），节目单立即可用。
        EpgManager em = EpgManager.getInstance(context);
        em.setEpgUrl(UrlConfig.EPG_URL);
        // 【2026-10-05】EPG 操作全部走 serial 单线程：loadEpgCache 与 loadEpg 并行会在
        // 4 线程池上同时 clear/parseXml（channelEpgMap 竞态 → 节目单随机空白）。
        AppExecutors.serial(() -> {
            em.loadEpgFromCache();
        });
        if (dataLoadListener != null) {
            dataLoadListener.onEpgLoaded();
        }
    }

    /** 【2026-10-05】直读直播源缓存文件内容（绕过 CacheManager 24h 有效期），
     *  配合 7 天策略：7 天内启动始终能读本地缓存快速显示。 */
    private String readLiveSourceCacheRaw() {
        try {
            java.io.File f = cacheManager.getCacheFile("live_source");
            if (f == null || !f.exists() || f.length() <= 0) {
                log("【直播源】readLiveSourceCacheRaw 未命中：f=" + (f == null ? "null" : f.getAbsolutePath())
                        + "，exists=" + (f == null ? "null" : f.exists()) + "，len=" + (f == null ? "null" : f.length()));
                return null;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            // 【2026-10-21】源7/源8：缓存可能是升级前写入的（无央视/卫视分组），
            // 这里按当前源补一次，保证新版首次启动即生效（幂等，已补过直接返回）。
            return LiveSourceLoader.appendExtraGroupsForCache(context, sb.toString(), UrlConfig.LIVE_URL);
        } catch (Exception e) {
            return null;
        }
    }

    /** 【2026-10-05】直播源缓存是否仍在 7 天有效期内（文件存在且 lastModified < 7 天）。 */
    private boolean isLiveSourceCacheFresh() {
        try {
            java.io.File f = cacheManager.getCacheFile("live_source");
            if (f == null || !f.exists()) return false;
            long age = System.currentTimeMillis() - f.lastModified();
            return age < LIVE_SOURCE_CACHE_VALID_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadEpg() {
        log("【EPG】开始加载节目单...");
        EpgManager em = EpgManager.getInstance(context);
        em.setEpgUrl(UrlConfig.EPG_URL);
        // 【2026-10-05】EPG 操作串行化（同 loadEpgCache，全部走 serial 单线程）。
        // loadEpg 内部自带「7 天新鲜 + 缓存来源校验 + 坏缓存回退下载」：
        // 缓存有效 → 只用缓存不下载；换源/损坏/过期 → 自动下载兜底。
        AppExecutors.serial(() -> {
            em.loadEpg(() -> {
                new Handler(Looper.getMainLooper()).post(() -> {
                    log("【EPG】最新节目单加载完成");
                    if (dataLoadListener != null) {
                        dataLoadListener.onEpgLoaded();
                    }
                });
            });
        });
    }

    private List<Channel> parseLiveSource(String content) {
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        // 【2026-10-19】源8 已换为 renyibo，M3U 由构建端按 slug 去重，tvg-id 天然唯一，
        // 这里不再需要「按协议串分流」的特殊路径（原 4kvm 的 noDedup 分支已移除）。
        final boolean noDedup = false;
        List<Channel> plainList = noDedup ? new ArrayList<>() : null;
        if (TextUtils.isEmpty(content)) {
            return new ArrayList<>();
        }
        // 【2026-10-11 海报镜像】缓存里的 M3U 可能残留不可达图床域名（如 img.picbf.com，
        // 实测 TLS 被 RST），而本方法是「缓存快显路径」的解析入口，不经过 AfhgrjSourceLoader。
        // 在入口统一做一次主机重写，保证旧缓存里的海报也能换到可达镜像。
        content = com.tv.live.afhgrj.AfhgrjSourceLoader.rewritePosterHostsPublic(content);
        String[] lines = content.split("\n");
        String currentName = "";
        String currentGroup = "未分类";
        String currentTvgId = "";
        String currentLogo = "";   // 【2026-09-29 频道台标】
        boolean pendingM3uUri = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.endsWith(",#genre#") || line.endsWith("#genre#")) {
                String group = line;
                if (group.endsWith(",#genre#")) {
                    group = group.substring(0, group.length() - ",#genre#".length()).trim();
                } else if (group.endsWith("#genre#")) {
                    int idx = group.lastIndexOf("#genre#");
                    if (idx > 0) group = group.substring(0, idx).trim();
                    if (group.endsWith(",")) group = group.substring(0, group.length() - 1).trim();
                }
                if (!group.isEmpty()) currentGroup = group;
                pendingM3uUri = false;
                currentName = ""; currentTvgId = ""; currentLogo = "";
                continue;
            }

            int httpIdx = line.indexOf("http://");
            if (httpIdx < 0) httpIdx = line.indexOf("https://");

            if (httpIdx > 1) {
                int diypComma = line.lastIndexOf(',', httpIdx - 1);
                if (diypComma > 0) {
                    String diypName = line.substring(0, diypComma).trim();
                    String diypUri = line.substring(diypComma + 1).trim();
                    // 【2026-10-07】接受协议串（renyibo:// 等），否则源5缓存永远解析失败 → 首播被网络刷新卡死
                    if (!diypName.isEmpty() && com.tv.live.PlaylistParser.isSupportedUri(diypUri)) {
                        addParsedChannel(channelMap, plainList, noDedup,
                                diypName, diypUri, currentGroup, "", "");
                        pendingM3uUri = false;
                        currentName = ""; currentTvgId = ""; currentLogo = "";
                        continue;
                    }
                }
            }

            if (line.startsWith("#EXTINF:")) {
                currentName = "";
                currentTvgId = "";
                currentLogo = "";   // 【2026-09-29 频道台标】

                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
                // 【2026-09-29 频道台标】解析 tvg-logo
                int logoIndex = line.indexOf("tvg-logo=\"");
                if (logoIndex > 0) {
                    int logoEnd = line.indexOf("\"", logoIndex + 10);
                    if (logoEnd > logoIndex) {
                        currentLogo = line.substring(logoIndex + 10, logoEnd).trim();
                    }
                }
                pendingM3uUri = true;
                continue;
            }

            if (line.startsWith("#")) {

                continue;
            }

            // 【2026-10-07】接受协议串（renyibo:// / miguhd:// / nowtv://），
            //   否则源5(爱其意)的缓存 M3U 解析出 0 个频道，缓存快显形同虚设。
            if (pendingM3uUri && com.tv.live.PlaylistParser.isSupportedUri(line)) {
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName)) {
                    addParsedChannel(channelMap, plainList, noDedup,
                            currentName, playUrl, currentGroup, currentTvgId, currentLogo);
                }
                pendingM3uUri = false;
                currentName = ""; currentTvgId = ""; currentLogo = "";
                continue;
            }

            if (!pendingM3uUri && com.tv.live.PlaylistParser.isSupportedUri(line)) {
                addParsedChannel(channelMap, plainList, noDedup, line, line, currentGroup, "", "");
            }
        }
        // 【2026-10-12 屏蔽名单】本方法是「本地缓存快显」路径的解析出口
        // （见 loadLiveAndEpgInternal 里 readLiveSourceCacheRaw → parseLiveSource → onLiveSourceLoaded(..., true)），
        // 它<b>不经过</b> LiveSourceLoader.postSuccess，所以屏蔽必须在这里也过一遍，否则
        // 7 天缓存有效期内冷启动会重新看到被屏蔽的频道。
        // 注：网络路径已在 LiveSourceLoader.postSuccess 里过滤，此处重复过滤是幂等的。
        // 【2026-10-18 源8】noDedup 时返回直列表（保留重复条目），否则返回按 key 合并后的结果。
        return com.tv.live.util.ChannelBlocklist.filter(
                noDedup ? plainList : new ArrayList<>(channelMap.values()));
    }

    /**
     * 【2026-10-18 源8】缓存快显路径的入库分发。
     *
     * <p>源8（4kvm）片单分组要求「M3U 有多少条就显示多少条」，不能按 tvgId/name 合并；
     * 其余来源保持原有语义：相同 key 追加为备用线路（addBackupUrl）。
     */
    private static void addParsedChannel(Map<String, Channel> channelMap, List<Channel> plainList,
                                         boolean noDedup, String name, String uri,
                                         String group, String tvgId, String logo) {
        if (!noDedup) {
            mergeChannelInto(channelMap, name, uri, group, tvgId, logo);
            return;
        }
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(uri)
                || !com.tv.live.PlaylistParser.isSupportedUri(uri)) return;
        Channel ch = new Channel(name, uri, group, tvgId);
        if (!TextUtils.isEmpty(logo)) {
            ch.setLogoUrl(logo);
        }
        plainList.add(ch);
    }

    private static void mergeChannelInto(Map<String, Channel> channelMap,
                                         String name, String uri,
                                         String group, String tvgId, String logo) {
        // 【2026-10-07】改用统一白名单（含 renyibo:// 等协议串）
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(uri)
                || !com.tv.live.PlaylistParser.isSupportedUri(uri)) return;
        String key = !TextUtils.isEmpty(tvgId) ? tvgId : name;
        if (TextUtils.isEmpty(key)) return;

        Channel existing = channelMap.get(key);
        if (existing != null) {
            existing.addBackupUrl(uri);
            if (!TextUtils.isEmpty(group)) {
                existing.setGroup(group);
            }
            // 【2026-09-29 频道台标】命中既有频道时，若其 logo 为空则补上
            if (!TextUtils.isEmpty(logo)
                    && TextUtils.isEmpty(existing.getLogoUrl())) {
                existing.setLogoUrl(logo);
            }
        } else {
            Channel newChannel = new Channel(name, uri, group, tvgId);
            // 【2026-09-29 频道台标】
            if (!TextUtils.isEmpty(logo)) {
                newChannel.setLogoUrl(logo);
            }
            channelMap.put(key, newChannel);
        }
    }

    public void mergeChannels(List<Channel> newChannels) {
        synchronized (channelListLock) {
            Map<String, Channel> mergedMap = new LinkedHashMap<>();
            for (Channel ch : channelSourceList) {
                String key = !TextUtils.isEmpty(ch.getChannelId()) ? ch.getChannelId() : ch.getName();
                if (!TextUtils.isEmpty(key)) {
                    mergedMap.put(key, ch);
                }
            }
            for (Channel ch : newChannels) {
                String key = !TextUtils.isEmpty(ch.getChannelId()) ? ch.getChannelId() : ch.getName();
                if (TextUtils.isEmpty(key)) continue;

                Channel existing = mergedMap.get(key);
                if (existing != null) {
                    for (String url : ch.getBackupUrls()) {
                        existing.addBackupUrl(url);
                    }
                    String newGroup = ch.getGroup();
                    if (!TextUtils.isEmpty(newGroup)) {
                        existing.setGroup(newGroup);
                    }
                } else {
                    mergedMap.put(key, ch);
                }
            }
            channelSourceList.clear();
            channelSourceList.addAll(mergedMap.values());
        }
    }

    public void registerReceivers() {
        if (receiversRegistered) return;
        toggleControllerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                isControllerVisible = !isControllerVisible;
            }
        };
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                    AppExecutors.io(() -> {
                        if (cacheManager != null) {
                            cacheManager.clearAll();
                            log("【缓存】已强制清除所有缓存，正在重新拉取最新数据");
                        }
                        synchronized (channelListLock) {
                            channelSourceList.clear();
                        }

                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String customLive = sp.getString("custom_live_url", "");
                        // 【2026-10-19 源8 移除】kvm4:// 死地址不要再当自定义源用
                        if (customLive != null && customLive.startsWith("kvm4://")) {
                            sp.edit().remove("custom_live_url").apply();
                            log("【源8 已移除】清除遗留自定义直播源: " + customLive);
                            customLive = "";
                        }
                        if (!TextUtils.isEmpty(customLive)) {
                            UrlConfig.LIVE_URL = customLive;
                            log("【推送】成功读取到网页推送的直播源地址：" + customLive);
                        } else {
                            SourceManager liveManager = new SourceManager(context, "live_history");
                            String defaultLive = liveManager.getDefaultUrl();
                            if (!TextUtils.isEmpty(defaultLive)) {
                                UrlConfig.LIVE_URL = defaultLive;
                                log("【推送】未找到推送地址，使用历史默认源：" + defaultLive);
                            }
                        }

                        String customEpg = sp.getString("custom_epg_url", "");
                        if (!TextUtils.isEmpty(customEpg)) {
                            UrlConfig.EPG_URL = customEpg;
                            log("【推送】成功读取到网页推送的EPG地址：" + customEpg);
                        } else {
                            SourceManager epgManager = new SourceManager(context, "epg_history");
                            String defaultEpg = epgManager.getDefaultUrl();
                            if (!TextUtils.isEmpty(defaultEpg)) {
                                UrlConfig.EPG_URL = defaultEpg;
                            }
                        }

                        hasPlayedWithCache = false;
                        if (refreshListener != null) {
                            refreshListener.onRefreshNeeded();
                        }
                        // 切换源后强制刷新直播源与 EPG。
                        // loadLiveAndEpg 有 6s 去重窗口 + in-flight 守卫，切换节目单时可能被跳过，
                        // 导致 EPG 缓存被 clearAll 清空但未重新下载、面板显示空。
                        // 因此改走「切源单飞」入口 forceReloadLiveSource()：它保证用户显式切源
                        // 一定生效（不被去重窗口、也不被另一个 AppCoreManager 实例正在飞的加载
                        // 挡掉），同时绝不与在飞加载并发，并把多个接收器收到的同源重复广播折叠成
                        // 一次 —— 避免对第三方源站重复/并发请求。
                        forceReloadLiveSource();
                        // 额外直接调用 loadEpg() 确保节目单一定重载（EPG 走独立 serial 链路）。
                        loadEpg();
                    });
                }
            }
        };
        try {
            IntentFilter filterToggle = new IntentFilter("com.tv.live.TOGGLE_CONTROL");
            ContextCompat.registerReceiver(context, toggleControllerReceiver, filterToggle, ContextCompat.RECEIVER_NOT_EXPORTED);

            IntentFilter filterRefresh = new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG");
            ContextCompat.registerReceiver(context, refreshReceiver, filterRefresh, ContextCompat.RECEIVER_NOT_EXPORTED);

            receiversRegistered = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void unregisterReceivers() {
        if (!receiversRegistered) return;
        try {
            if (toggleControllerReceiver != null) {
                context.unregisterReceiver(toggleControllerReceiver);
            }
            if (refreshReceiver != null) {
                context.unregisterReceiver(refreshReceiver);
            }
            receiversRegistered = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isControllerVisible() { return isControllerVisible; }

    public boolean onPause() {
        if (isOpeningSettings) return false;
        if (playerManager != null) {
            playerManager.onBackground();
        }
        return true;
    }

    public boolean onResume() {
        if (isOpeningSettings) {
            isOpeningSettings = false;
            return false;
        }
        if (playerManager != null) {
            playerManager.onForeground();
        }
        return true;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
    }

    public void onDestroy() {
        unregisterReceivers();
        timeoutHandler.removeCallbacksAndMessages(null);
        // 这里不能释放 playerManager：TVPlayerManager 是跨 Activity 共享的单例，
        // 切换源模式（直播 ⇄ OTT）时新 Activity 的 onCreate 早于旧 Activity 的
        // onDestroy，新 Activity 拿到的就是这个实例；若在此 release，会把新
        // Activity 正在使用的播放器销毁，表现为播放器卡死、不再自动播放。
        // 播放器释放由各 Activity 按 switchingToOtt / switchingToLive 自行决定。
        synchronized (channelListLock) {
            if (channelSourceList != null) {
                channelSourceList.clear();
            } else {
                channelSourceList = new ArrayList<>();
            }
        }
    }

    public void beforeOpenSettings() {
        isOpeningSettings = true;
    }

    public boolean isOpeningSettings() { return isOpeningSettings; }

    public boolean hasPlayedWithCache() { return hasPlayedWithCache; }
    public void setHasPlayedWithCache(boolean played) { this.hasPlayedWithCache = played; }

    public List<Channel> getChannelList() {
        synchronized (channelListLock) {
            if (channelSourceList == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(channelSourceList);
        }
    }

    public void setOnSourceSkipListener(OnSourceSkipListener listener) {
        this.sourceSkipListener = listener;
    }

    public boolean handleSourceFailed(String currentChannelName) {
        consecutiveFailedCount++;
        int count = consecutiveFailedCount;
        if (sourceSkipListener != null) {
            sourceSkipListener.onSourceFailed(currentChannelName, count);
        }
        if (count >= MAX_CONSECUTIVE_SKIP) {
            if (sourceSkipListener != null) {
                sourceSkipListener.onSkipLimitReached(MAX_CONSECUTIVE_SKIP);
            }
            return false;
        }
        if (sourceSkipListener != null) {
            sourceSkipListener.onNeedSkipChannel();
        }
        return true;
    }

    public void resetSourceFailedCount() { consecutiveFailedCount = 0; }
    public int getConsecutiveFailedCount() { return consecutiveFailedCount; }
    public int getMaxConsecutiveSkip() { return MAX_CONSECUTIVE_SKIP; }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppExecutors.io(() -> {
            String prevLive = UrlConfig.LIVE_URL;
            String prevEpg = UrlConfig.EPG_URL;
            appConfig.setCustomUrls(liveUrl, epgUrl);
            if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
            if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
            log("【远程配置】更新直播源：" + liveUrl);
            log("【远程配置】更新EPG：" + epgUrl);

            // 【P0-C】源地址没变、本地也已有频道数据 → 不必清缓存再下一遍
            boolean liveChanged = (liveUrl != null && !liveUrl.equals(prevLive));
            boolean epgChanged = (epgUrl != null && !epgUrl.equals(prevEpg));
            boolean hasData;
            synchronized (channelListLock) {
                hasData = !channelSourceList.isEmpty();
            }
            if (!liveChanged && hasData) {
                log("【远程配置】直播源地址未变化且已有频道数据，跳过重复下载");
                if (epgChanged) {
                    loadEpg();
                }
                return;
            }

            if (cacheManager != null) {
                cacheManager.clearAll();
                log("【缓存】远程配置触发，强制清除旧缓存");
            }

            synchronized (channelListLock) {
                channelSourceList.clear();
            }

            hasPlayedWithCache = false;
            // 【2026-10-11 切源单飞】与广播路径（refreshReceiver）对齐：此处刚 clearAll 掉
            // live_source 缓存并清空了 channelSourceList，若用非 forced 的 loadLiveAndEpg()，
            // 一旦此刻有别的加载在飞就会被直接跳过（不登记 pending）→ 远程配置下发的源被吞掉、
            // 界面停在空列表。改用 forceReloadLiveSource()，保证「源变了就一定重载」。
            forceReloadLiveSource();
        });
    }

    public void setOnDataLoadListener(OnDataLoadListener listener) { this.dataLoadListener = listener; }
    public void setOnRefreshListener(OnRefreshListener listener) { this.refreshListener = listener; }

    /**
     * 【2026-10-22 I1-04】d 级日志在 release 下被 proguard 剥离（logcat 与 9527 内存通道
     * 双不可见），本类承载直播源加载/切源/闸门等关键链路，统一升为 i 级保证 release 可观测。
     * 调用方均为源加载/刷新级低频场景（非逐帧/逐频道），i 级全量收集无压力。
     */
    private void log(String msg) {
        LogBridge.i("AppCoreManager", msg);
    }

    public void release() {
        onDestroy();
        context = null;
        playerManager = null;
        appConfig = null;
        cacheManager = null;
        dataLoadListener = null;
        refreshListener = null;
        sourceSkipListener = null;
    }
}
