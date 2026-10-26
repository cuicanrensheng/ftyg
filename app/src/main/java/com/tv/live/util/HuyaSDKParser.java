package com.tv.live.util;

import android.app.Application;
import android.content.Context;
import android.text.TextUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 虎牙流信息仓库（自研纯 Java 解析器）。
 *
 * <p>只依赖自研 HTTP 能力：</p>
 * <ul>
 *   <li>{@link HuyaStreamInfo} / {@link CachedStreams}：流描述与 60s 内存缓存；</li>
 *   <li>{@link #getCachedStreams} / {@link #invalidateCachedStreams}：404 专项缓存失效；</li>
 *   <li>{@link #selectBestStreamForDevice}：≤720p 设备适配选流；</li>
 *   <li>{@link HuyaLiveProbe}：OkHttp 纯 HTTP 探活（快速 800ms / 宽限 2000ms）；</li>
 *   <li>{@link #applySdkCtype}：URL ctype 归一化（自研解析器/探活沿用）；</li>
 *   <li>{@link #init} / {@link #isSDKAvailable}：兼容旧调用点的轻量就绪闸门（同步完成）。</li>
 * </ul>
 *
 * <p>播放地址解析一律走自研解析器 {@link HuyaStreamPlayer.HuyaParser}（纯 HTTP：
 * mp 接口 + cache 接口 + 房间页兜底），两条虎牙一起看线路
 * （{@link com.tv.live.manager.HuyaTogetherWatchGroupManager} /
 * {@link com.tv.live.manager.HuyaTogetherWatchManager}）同样只走自研 HTTP。</p>
 */
public class HuyaSDKParser {
    private static final String TAG = "HuyaSDKParser";

    // ------------------------------------------------------------------
    // 就绪闸门（兼容旧调用点；同步完成，无真实初始化等待）
    // ------------------------------------------------------------------
    private static volatile boolean sInitDone = false;
    private static volatile boolean sInitFinished = false;
    private static volatile boolean sInitOk = false;
    private static volatile Context sAppContext;
    private static final Object sInitWaitLock = new Object();
    private static final List<Runnable> sInitReadyListeners = new ArrayList<>();
    private static boolean sInitNotified = false;

    // ------------------------------------------------------------------
    // 流信息缓存（404 专项）
    // ------------------------------------------------------------------
    private static final long CACHE_VALID_MS = 60000L;
    private static final ConcurrentHashMap<Integer, CachedStreams> sStreamsCache = new ConcurrentHashMap<>();

    private static volatile boolean sPlaybackSettled = false;

    /** 起播稳定后调用（仅记标志位） */
    public static void notifyPlaybackSettled() {
        sPlaybackSettled = true;
    }

    /**
     * 房间预热入口，现为 no-op：自研解析器按需单房间解析，
     * 无需批量预热（且批量预热会打爆虎牙接口，已被 2026-09 实测否定）。
     */
    public static void preloadRooms(List<Integer> roomIds) {
        // no-op
    }

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------
    public static class CachedStreams {
        public long timestamp;
        public List<HuyaStreamInfo> streams;

        public boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_VALID_MS;
        }

        public long getAgeSec() {
            return (System.currentTimeMillis() - timestamp) / 1000;
        }

        public int size() {
            return streams != null ? streams.size() : 0;
        }
    }

    /** 一条虎牙线路×清晰度组合的流描述（纯数据类） */
    public static class HuyaStreamInfo {
        public int lineIndex;
        public int lineValue;
        public String lineLabel;
        public int bitRate;
        public String bitRateDisplayName;
        public String resolutionLabel;
        public String hlsUrl;
        public String flvUrl;
        public boolean isDefaultLine;
        public boolean isDefaultBitrate;

        /**
         * 选 HLS（FLV 仅作兜底）。
         *
         * <p>【2026-09-19 实测结论：虎牙 FLV 不能用】PC 直连实测（https+Referer 已放行）：
         * 无论点播型（{@code /src/}）还是直播型（{@code /huyalive/}）线路，CDN 都是
         * <b>0.36s 内吐完约 0.5~0.7MB 就 Connection: close</b>（chunked，无 Content-Length）。
         * 即虎牙 FLV 不是持续长连接，而是「短分片」——ExoPlayer 的 ProgressiveMediaSource
         * 一读到 EOF 就 STATE_ENDED，反而造成严重卡顿。HLS 是唯一可用路径。</p>
         */
        public String getPlayUrl() {
            return !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
        }

        public boolean isHls() {
            return !TextUtils.isEmpty(hlsUrl);
        }

        @Override
        public String toString() {
            return "Stream[line#" + lineIndex + "(v=" + lineValue + ") " + bitRateDisplayName
                    + "(" + bitRate + "bps) HLS=" + (hlsUrl != null) + " FLV=" + (flvUrl != null) + "]";
        }
    }

    // ------------------------------------------------------------------
    // 缓存存取
    // ------------------------------------------------------------------
    public static CachedStreams getCachedStreams(int roomId) {
        CachedStreams cs = sStreamsCache.get(roomId);
        return (cs != null && cs.isValid()) ? cs : null;
    }

    /**
     * 【404 专项】使指定房间/uid 的流信息缓存立即失效。
     * 虎牙 m3u8 地址带签名（wsSecret/wsTime），主播切流/房间刷新后旧地址会 404；
     * 不清缓存会让重试反复命中同一份旧签名列表。
     */
    public static void invalidateCachedStreams(int roomId) {
        if (roomId == 0) return;
        sStreamsCache.remove(roomId);
        LogBridge.i(TAG, "🚮【404】已使 roomId=" + roomId + " 流信息缓存失效，下次播放强制实时解析");
    }

    /** uid 通道缓存键为 -uid，404 时一并失效 */
    public static void invalidateCachedStreamsByUid(long uid) {
        if (uid == 0) return;
        int cacheKey = (int) -uid;
        sStreamsCache.remove(cacheKey);
        LogBridge.i(TAG, "🚮【404】已使 uid=" + uid + " 流信息缓存失效，下次播放强制实时解析");
    }

    // ------------------------------------------------------------------
    // 设备适配选流
    // ------------------------------------------------------------------
    public static HuyaStreamInfo selectBestStreamForDevice(java.util.List<HuyaStreamInfo> streams) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        if (streams.size() == 1) {
            return streams.get(0);
        }

        int targetHeight = 720;  // 🎯 2026-09-10 卡顿修复：默认限制 720p（用户可手动切蓝光）
        java.util.List<HuyaStreamInfo> valid = new java.util.ArrayList<>();
        for (HuyaStreamInfo s : streams) {
            if (s != null && !TextUtils.isEmpty(s.getPlayUrl())) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) return null;

        HuyaStreamInfo best = null;
        for (HuyaStreamInfo s : valid) {
            int h = parseStreamHeight(s);
            if (h <= targetHeight) {
                if (best == null || s.bitRate > best.bitRate) {
                    best = s;
                }
            }
        }

        if (best == null) {
            LogBridge.w(TAG, "【适配选择】没有 <= " + targetHeight + "p 的流，退回选择最高码率");
            best = valid.get(0);
            for (HuyaStreamInfo s : valid) {
                if (s.bitRate > best.bitRate) best = s;
            }
        }

        LogBridge.i(TAG, "【适配选择】选中流: " + best
                + " (resolutionLabel=" + best.resolutionLabel
                + ", bitRate=" + best.bitRate + "bps)");
        return best;
    }

    private static int parseStreamHeight(HuyaStreamInfo s) {
        if (s == null) return 0;

        if (!TextUtils.isEmpty(s.resolutionLabel)) {
            String l = s.resolutionLabel.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("2160") || l.contains("4k")) return 2160;
            if (l.contains("1080")) return 1080;
            if (l.contains("720")) return 720;
            if (l.contains("540")) return 540;
            if (l.contains("480")) return 480;
            if (l.contains("360")) return 360;
        }

        if (!TextUtils.isEmpty(s.bitRateDisplayName)) {
            String b = s.bitRateDisplayName.toLowerCase(java.util.Locale.ROOT);
            if (b.contains("4k")) return 2160;
        }

        int brKbps = s.bitRate / 1000;
        if (brKbps >= 8000) return 2160;
        if (brKbps >= 4000) return 1080;
        if (brKbps >= 2000) return 720;
        if (brKbps >= 1200) return 540;
        if (brKbps >= 600) return 360;
        return 0;
    }

    // ------------------------------------------------------------------
    // 就绪闸门（同步完成）
    // ------------------------------------------------------------------
    /** 轻量初始化：只登记 Application 上下文并立即置就绪（原 SDK 异步 init 的兼容壳）。 */
    public static synchronized void init(Application app) {
        if (sInitDone) return;
        sInitDone = true;
        sAppContext = app != null ? app.getApplicationContext() : null;
        sInitOk = true;
        sInitFinished = true;
        notifyReadyLocked();
    }

    /** 就绪标志（等价于 {@link #init} 已执行；自研解析不依赖它） */
    public static boolean isSDKAvailable() {
        return sInitOk;
    }

    public static Context getAppContext() {
        return sAppContext;
    }

    /** 兼容旧「延迟/立即初始化」调用点：直接同步 init。 */
    public static void requestImmediateInit(final Application app) {
        init(app);
    }

    /** 就绪后立即回调（init 同步完成，注册即执行或立即执行） */
    public static void addInitReadyListener(Runnable listener) {
        if (listener == null) return;
        boolean ready;
        synchronized (sInitWaitLock) {
            ready = sInitNotified;
            if (!ready) sInitReadyListeners.add(listener);
        }
        if (ready) listener.run();
    }

    private static void notifyReadyLocked() {
        synchronized (sInitWaitLock) {
            sInitNotified = true;
            List<Runnable> pending = new ArrayList<>(sInitReadyListeners);
            sInitReadyListeners.clear();
            for (Runnable r : pending) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // ctype 归一化（自研解析器/探活共用）
    // ------------------------------------------------------------------
    static final String SDK_CTYPE = "huya_adr_game_sdk";

    /**
     * 把 URL 的 {@code ctype} 参数替换为 {@link #SDK_CTYPE}；没有该参数则追加。
     * 替换幂等，可安全重复调用。2026-10-03 取证：不带该 ctype 的 tars_mp 地址会被 CDN 403。
     */
    static String applySdkCtype(String url) {
        if (TextUtils.isEmpty(url)) return url;
        if (url.contains("ctype=")) {
            return url.replaceAll("([?&]ctype=)[^&]*", "$1" + SDK_CTYPE);
        }
        return url + (url.contains("?") ? "&" : "?") + "ctype=" + SDK_CTYPE;
    }

    // ------------------------------------------------------------------
    // 纯 HTTP 探活
    // ------------------------------------------------------------------
    public static final class HuyaLiveProbe {
        private static final String TAG = "HuyaLiveProbe";
        // 【2026-09-27 换台卡顿优化】探活从「关键路径过滤器」降级为「选路提示」：
        //   现关键路径只快速探「默认流」一条，上限 800ms。
        private static final int PROBE_CONNECT_TIMEOUT_MS = 800;
        private static final int PROBE_READ_TIMEOUT_MS = 800;
        // 宽限探活：仅后台补验使用（不在换台关键路径上），2s+2s
        private static final int GRACE_CONNECT_TIMEOUT_MS = 2000;
        private static final int GRACE_READ_TIMEOUT_MS = 2000;
        private static final int MAX_CONCURRENCY = 10;
        // 【2026-10-03】filterAvailable 改用 2000ms 宽限探活后，原 2s latch 会把合法的 HS 结果截断，
        //   故提到 3s（仍在后台线程，不影响换台关键路径）。
        private static final long PROBE_AWAIT_SEC = 3L;
        private static volatile OkHttpClient sClient;

        private HuyaLiveProbe() {}

        private static OkHttpClient client() {
            OkHttpClient c = sClient;
            if (c == null) {
                synchronized (HuyaLiveProbe.class) {
                    c = sClient;
                    if (c == null) {
                        c = new OkHttpClient.Builder()
                                .connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                .readTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                .retryOnConnectionFailure(false)
                                // 【2026-10-25 NO_PROXY】与 HuyaStreamPlayer.sClient 同口径
                                .proxy(java.net.Proxy.NO_PROXY)
                                .build();
                        sClient = c;
                    }
                }
            }
            return c;
        }

        /**
         * 【2026-09-27 换台卡顿优化】快速探活：只探「默认流」一条，超时上限 800ms。
         * 仅用于决定首播是否直接采用默认流；探测结果<b>不再裁剪候选池</b>——
         * 全量候选由 TVPlayerManager.mLastAliveStreams 保留，
         * 供 403/404 时 retryHuyaImmediately → pickDifferentLineStream 切线路使用。
         */
        public static boolean probeDefaultFast(HuyaStreamInfo s) {
            if (s == null) return false;
            return checkSingle(s.getPlayUrl());
        }

        /**
         * 【2026-09-27 主动探活】起播后巡检当前播放地址是否仍可取（复用快速探活通道，800ms）。
         * 与 probeDefaultFast 的区别仅在于语义：这个探的是"正在播的地址"。
         */
        public static boolean probeOnce(String url) {
            return checkSingle(url);
        }

        /**
         * 【2026-10-03 BUG B 修复】后台补验候选线路，使用 <b>2000ms 宽限探活</b>。
         *
         * <p>原实现走 800ms 的 {@link #checkSingle}，HS 本身抖动大、实测成功率 20~50%，
         * 800ms 会造成大量假阴性 → AL/HS 被判死 → mLastAliveStreams 只剩 TX。
         * 本方法在<b>后台线程</b>（非换台关键路径）执行，放宽到 2000ms 是安全的；
         * {@link #probeDefaultFast} 仍维持 800ms，换台延迟不受影响。</p>
         */
        public static List<HuyaStreamInfo> filterAvailable(List<HuyaStreamInfo> streams) throws InterruptedException {
            if (streams == null || streams.isEmpty()) return new ArrayList<>();
            if (streams.size() == 1) return new ArrayList<>(streams);
            final int n = streams.size();
            final boolean[] alive = new boolean[n];
            final CountDownLatch latch = new CountDownLatch(n);
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENCY, n));
            try {
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    final HuyaStreamInfo s = streams.get(idx);
                    pool.submit(() -> {
                        try { alive[idx] = checkSingleWithGrace(s.getPlayUrl()); }
                        catch (Throwable t) { alive[idx] = false; }
                        finally { latch.countDown(); }
                    });
                }
                latch.await(PROBE_AWAIT_SEC, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            List<HuyaStreamInfo> result = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (alive[i]) result.add(streams.get(i));
            }
            LogBridge.i(TAG, "【探活】候选 " + n + " 条线路，可用 " + result.size() + " 条（宽限 2000ms）");
            return result;
        }

        private static boolean checkSingle(String url) {
            if (url == null || url.trim().isEmpty()) return false;
            // 【2026-10-03 BUG A 修复】探活必须走 SDK ctype，否则测的是注定 403 的 tars_mp 地址。
            url = applySdkCtype(url);
            Response resp = null;
            try {
                Request req = new Request.Builder()
                        .url(url).get()
                        .addHeader("Connection", "close")
                        .build();
                resp = client().newCall(req).execute();
                int code = resp.code();
                // 【2026-09-27】原实现把 3xx 也判为通过，但虎牙 m3u8 的跳转只是第一跳，
                //   跳转后的 CDN 节点仍可能 403 —— 出现「探活说可用、播放却 403」的假阳性，
                //   白白等满超时后照样触发重试链。改为只认真正取到内容的 2xx。
                boolean ok = (code >= 200 && code < 300);
                if (!ok) LogBridge.d(TAG, "【探活】线路失败 code=" + code + " url=" + head(url, 80));
                return ok;
            } catch (IOException | IllegalArgumentException e) {
                LogBridge.d(TAG, "【探活】线路异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            } catch (Throwable t) {
                LogBridge.d(TAG, "【探活】未知异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return false;
            } finally {
                if (resp != null) { try { resp.close(); } catch (Throwable ignored) {} }
            }
        }

        private static String head(String s, int max) {
            if (s == null) return "null";
            return s.length() < max ? s : s.substring(0, max) + "...";
        }

        /**
         * 【2026-10-03 BUG A+B】2000ms 宽限探活的核心实现：同样套用 SDK ctype。
         * {@link #filterAvailable} 与 {@link #probeSingleWithGrace} 都走这里。
         */
        private static boolean checkSingleWithGrace(String url) {
            if (url == null || url.trim().isEmpty()) return false;
            url = applySdkCtype(url);
            OkHttpClient graceClient = new OkHttpClient.Builder()
                    .connectTimeout(GRACE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(GRACE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    // 【2026-10-25 NO_PROXY】与 HuyaStreamPlayer.sClient 同口径
                    .proxy(java.net.Proxy.NO_PROXY)
                    .build();
            Response resp = null;
            try {
                Request req = new Request.Builder()
                        .url(url).get()
                        .addHeader("Connection", "close")
                        .build();
                resp = graceClient.newCall(req).execute();
                int code = resp.code();
                boolean ok = resp.isSuccessful() || (code >= 300 && code < 400);
                if (!ok) LogBridge.d(TAG, "【探活·宽限】失败 code=" + code + " url=" + head(url, 80));
                return ok;
            } catch (Throwable t) {
                LogBridge.d(TAG, "【探活·宽限】异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return false;
            } finally {
                if (resp != null) { try { resp.close(); } catch (Throwable ignored) {} }
            }
        }

        public static boolean probeSingleWithGrace(String url) {
            if (url == null || url.trim().isEmpty()) return false;
            boolean ok = checkSingleWithGrace(url);
            LogBridge.i(TAG, "【探活·宽限】默认流 " + (ok ? "通过" : "失败"));
            return ok;
        }
    }
}
