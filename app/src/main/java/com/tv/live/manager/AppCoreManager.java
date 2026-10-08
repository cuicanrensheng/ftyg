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

    private boolean hasPlayedWithCache = false;
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;

    // 【P0-C】直播源加载去重
    // 冷启动期间 MainActivity、远程配置回调、推送回调会并发/连续触发 loadLiveAndEpg()，
    // 实测同一份 m3u 在 +0.59s 与 +4.51s 各被完整下载一遍，多花约 1s 并抢占首帧带宽。
    private final java.util.concurrent.atomic.AtomicBoolean liveSourceLoadInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastLiveSourceLoadAtMs = 0;
    private static final long LOAD_DEDUPE_WINDOW_MS = 6000;
    private static final long LOAD_DEDUPE_RELEASE_MS = 30000;
    private final Handler dedupeHandler = new Handler(Looper.getMainLooper());

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
        long now = android.os.SystemClock.elapsedRealtime();
        if (liveSourceLoadInFlight.get()) {
            log("【直播源】上一次加载仍在进行，跳过重复加载");
            return;
        }
        if (now - lastLiveSourceLoadAtMs < LOAD_DEDUPE_WINDOW_MS) {
            log("【直播源】距上次加载不足 " + (LOAD_DEDUPE_WINDOW_MS / 1000) + "s，跳过重复加载");
            return;
        }
        if (!liveSourceLoadInFlight.compareAndSet(false, true)) {
            log("【直播源】并发加载被去重拦截");
            return;
        }
        lastLiveSourceLoadAtMs = now;
        // 兜底释放：异常路径若没走到 onSuccess/onError，避免标志永久卡死
        dedupeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                liveSourceLoadInFlight.set(false);
            }
        }, LOAD_DEDUPE_RELEASE_MS);
        try {
            loadLiveAndEpgInternal();
        } catch (Throwable t) {
            liveSourceLoadInFlight.set(false);
            log("【直播源】加载异常：" + t.getMessage());
        }
    }

    private void loadLiveAndEpgInternal() {
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
                    startNetworkLiveSourceLoad();
                } catch (Throwable t) {
                    log("【网络】延后刷新异常：" + t.getMessage());
                }
            }, NETWORK_REFRESH_DELAY_MS);
        } else {
            startNetworkLiveSourceLoad();
        }
    }

    /** 【2026-09-29 启动卡顿修复】从 loadLiveAndEpgInternal 抽出的后台网络加载（原逻辑不动）。 */
    private void startNetworkLiveSourceLoad() {
        lastNetworkLoadAtMs = android.os.SystemClock.elapsedRealtime();
        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(context).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                liveSourceLoadInFlight.set(false);
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
                liveSourceLoadInFlight.set(false);
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
            return sb.toString();
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
        if (TextUtils.isEmpty(content)) {
            return new ArrayList<>();
        }
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
                        mergeChannelInto(channelMap, diypName, diypUri, currentGroup, "", "");
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
                    mergeChannelInto(channelMap, currentName, playUrl, currentGroup, currentTvgId, currentLogo);
                }
                pendingM3uUri = false;
                currentName = ""; currentTvgId = ""; currentLogo = "";
                continue;
            }

            if (!pendingM3uUri && com.tv.live.PlaylistParser.isSupportedUri(line)) {
                mergeChannelInto(channelMap, line, line, currentGroup, "", "");
            }
        }
        return new ArrayList<>(channelMap.values());
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
                        // 因此重置去重计时器，并额外直接调用 loadEpg() 确保节目单一定重载。
                        lastLiveSourceLoadAtMs = 0;
                        loadLiveAndEpg();
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
            loadLiveAndEpg();
        });
    }

    public void setOnDataLoadListener(OnDataLoadListener listener) { this.dataLoadListener = listener; }
    public void setOnRefreshListener(OnRefreshListener listener) { this.refreshListener = listener; }

    private void log(String msg) {
        LogBridge.d("AppCoreManager", msg);
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
