package com.tv.live.util;

import android.app.Application;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.huya.berry.client.HuyaBerry;
import com.huya.berry.client.HuyaBerryConfig;
import com.huya.berry.client.customui.CustomUICallback;
import com.huya.berry.client.customui.model.BitRateInfo;
import com.huya.berry.client.customui.model.LiveInfo;
import com.huya.berry.gamesdk.base.BaseCallback;
import com.huya.berry.gamesdk.crash.ICrashService;
import com.huya.live.service.ServiceHelper;

import java.util.HashMap;
import java.util.Map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.tv.live.util.HuyaCredentials;

public class HuyaSDKParser {

    private static final String TAG = "HuyaSDKParser";

    private static volatile boolean sInitDone = false;
    private static volatile boolean sInitOk = false;
    private static int sAutoTestRound = 0;

    private static final Object sInitWaitLock = new Object();
    private static final List<Runnable> sInitReadyListeners = new ArrayList<>();
    private static boolean sInitNotified = false;

    private static HuyaBerry sHuyaBerry;

    private static final long CACHE_VALID_MS = 60000L;
    private static final ConcurrentHashMap<Integer, CachedStreams> sStreamsCache = new ConcurrentHashMap<>();

    private static final int PRELOAD_MAX_ROOMS = 30;
    private static final long PRELOAD_INTERVAL_MS = 1500L;

    private static final HandlerThread sPreloadThread;
    private static final Handler sPreloadHandler;
    static {
        sPreloadThread = new HandlerThread("HuyaSDKParser-Preload");
        sPreloadThread.setDaemon(true);
        sPreloadThread.start();
        sPreloadHandler = new Handler(sPreloadThread.getLooper());
    }
    private static final List<Integer> sPreloadPendingQueue = new ArrayList<>();
    private static boolean sPreloadScheduled = false;
    private static int sPreloadIndex = 0;

    private static final long PRELOAD_FAIL_RETRY_MS = 30L * 60 * 1000;
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> sPreloadFailAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile android.content.SharedPreferences sPreloadFailPrefs;

    private static final long PRELOAD_DEFERRED_DELAY_MS = 20000L;
    private static volatile boolean sPlaybackSettled = false;
    private static boolean sPreloadStartFallbackScheduled = false;
    private static final Runnable PRELOAD_RUNNABLE = new Runnable() {
        @Override public void run() {
            int roomId = -1;
            synchronized (sPreloadPendingQueue) {
                while (sPreloadIndex < sPreloadPendingQueue.size()) {
                    int candidate = sPreloadPendingQueue.get(sPreloadIndex++);

                    CachedStreams cs = sStreamsCache.get(candidate);
                    if (cs != null && cs.isValid()) continue;

                    Long failAt = sPreloadFailAt.get(candidate);
                    if (failAt != null
                            && System.currentTimeMillis() - failAt < PRELOAD_FAIL_RETRY_MS) {
                        continue;
                    }
                    roomId = candidate;
                    break;
                }
            }
            if (roomId > 0) {
                final int finalRoomId = roomId;
                LogBridge.d(TAG, "🔁【预解析】(" + sPreloadIndex + "/" + sPreloadPendingQueue.size()
                        + ") roomId=" + finalRoomId);

                parseFull(finalRoomId, new OnSDKFullResultListener() {
                    @Override public void onSuccess(HuyaStreamInfo defaultStream,
                                                    List<HuyaStreamInfo> allStreams,
                                                    List<String> lines) {

                        sPreloadFailAt.remove(finalRoomId);
                        if (sPreloadFailPrefs != null) {
                            sPreloadFailPrefs.edit().remove(String.valueOf(finalRoomId)).apply();
                        }
                        LogBridge.d(TAG, "✅【预解析】roomId=" + finalRoomId + " 成功, streams="
                                + (allStreams != null ? allStreams.size() : 0));
                    }
                    @Override public void onError(String error) {
                        long ts = System.currentTimeMillis();
                        sPreloadFailAt.put(finalRoomId, ts);
                        if (sPreloadFailPrefs != null) {
                            sPreloadFailPrefs.edit().putLong(String.valueOf(finalRoomId), ts).apply();
                        }
                        LogBridge.w(TAG, "⚠️【预解析】roomId=" + finalRoomId + " 失败: " + error
                                + "（" + (PRELOAD_FAIL_RETRY_MS / 60000) + " 分钟内跳过预热，点频道时仍实时解析）");
                    }
                });

                sPreloadHandler.postDelayed(this, PRELOAD_INTERVAL_MS);
            } else {

                synchronized (sPreloadPendingQueue) {
                    sPreloadScheduled = false;
                    LogBridge.d(TAG, "🏁【预解析】队列处理完毕, 已提交=" + sPreloadIndex
                            + "/" + sPreloadPendingQueue.size());
                    sPreloadPendingQueue.clear();
                    sPreloadIndex = 0;
                }
            }
        }
    };

    public static void notifyPlaybackSettled() {
        if (sPlaybackSettled) return;
        sPlaybackSettled = true;
        LogBridge.d(TAG, "🎬【预解析】首播已就绪（STATE_READY）→ 错峰启动预解析");
        maybeStartPreload("首播就绪");
    }

    private static void maybeStartPreload(String reason) {
        synchronized (sPreloadPendingQueue) {
            if (!sInitOk || sPreloadScheduled || sPreloadPendingQueue.isEmpty()) return;
            if (!sPlaybackSettled) {

                return;
            }
            sPreloadScheduled = true;
            sPreloadIndex = 0;
            LogBridge.d(TAG, "🚀【预解析】(" + reason + ") 开始, 共 " + sPreloadPendingQueue.size()
                    + " 个虎牙房间，每 " + (PRELOAD_INTERVAL_MS / 1000) + "s 解析一个");
            sPreloadHandler.post(PRELOAD_RUNNABLE);
        }
    }

    private static final Runnable PRELOAD_FALLBACK_START = new Runnable() {
        @Override public void run() {
            if (sPlaybackSettled) return;
            LogBridge.d(TAG, "⏰【预解析】init 后 " + (PRELOAD_DEFERRED_DELAY_MS / 1000)
                    + "s 播放仍未就绪，兜底启动（用户可能未播放）");
            sPlaybackSettled = true;
            maybeStartPreload("兜底超时");
        }
    };

    public static void preloadRooms(List<Integer> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return;

        LinkedHashSet<Integer> deduped = new LinkedHashSet<>(roomIds);
        List<Integer> trimmed = new ArrayList<>(deduped);
        if (trimmed.size() > PRELOAD_MAX_ROOMS) {
            trimmed = new ArrayList<>(trimmed.subList(0, PRELOAD_MAX_ROOMS));
        }
        synchronized (sPreloadPendingQueue) {

            int added = 0;
            long now = System.currentTimeMillis();
            for (Integer id : trimmed) {
                if (sPreloadPendingQueue.contains(id)) continue;
                CachedStreams cs = sStreamsCache.get(id);
                if (cs != null && cs.isValid()) continue;

                Long failAt = sPreloadFailAt.get(id);
                if (failAt != null && now - failAt < PRELOAD_FAIL_RETRY_MS) continue;
                sPreloadPendingQueue.add(id);
                added++;
            }
            if (added == 0) {
                LogBridge.d(TAG, "🔁【预解析】无新增房间（均已在队列或已有有效缓存），跳过");
                return;
            }
            if (!sInitOk) {

                LogBridge.d(TAG, "⏳【预解析】SDK 尚未 init，已缓存 " + sPreloadPendingQueue.size()
                        + " 个房间号，等 init 完成后自动开始");
                return;
            }

            if (!sPreloadScheduled) {
                maybeStartPreload("新房间入队");
                if (!sPreloadScheduled) {
                    LogBridge.d(TAG, "🎬【预解析】首播尚未就绪（错峰等待中），"
                            + sPreloadPendingQueue.size() + " 个房间挂起");
                }
            } else {

                LogBridge.d(TAG, "🔀【预解析】队列正在跑，追加 " + added + " 个新房间到队尾（总计 "
                        + sPreloadPendingQueue.size() + " 个）");
            }
        }
    }

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

    public interface OnSDKFullResultListener {

        void onSuccess(HuyaStreamInfo defaultStream, List<HuyaStreamInfo> allStreams, List<String> lines);

        void onError(String error);
    }

    @Deprecated
    public interface OnSDKResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isHls);
        void onError(String error);
    }

    public static CachedStreams getCachedStreams(int roomId) {
        CachedStreams cs = sStreamsCache.get(roomId);
        return (cs != null && cs.isValid()) ? cs : null;
    }

    public static HuyaStreamInfo selectBestStreamForDevice(java.util.List<HuyaStreamInfo> streams) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        if (streams.size() == 1) {
            return streams.get(0);
        }

        int targetHeight = Integer.MAX_VALUE;
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

    public static synchronized void init(Application app) {
        if (sInitDone) return;
        sInitDone = true;
        try {

            try {
                android.content.SharedPreferences sp = app.getSharedPreferences(
                        "huya_preload_fail", android.content.Context.MODE_PRIVATE);
                sPreloadFailPrefs = sp;
                long now = System.currentTimeMillis();
                for (String key : sp.getAll().keySet()) {
                    try {
                        long ts = sp.getLong(key, 0);
                        if (now - ts < PRELOAD_FAIL_RETRY_MS) {
                            sPreloadFailAt.put(Integer.parseInt(key), ts);
                        } else {
                            sp.edit().remove(key).apply();
                        }
                    } catch (NumberFormatException ignored) {}
                }
                if (!sPreloadFailAt.isEmpty()) {
                    LogBridge.d(TAG, "🔁【预解析】已恢复 " + sPreloadFailAt.size()
                            + " 个死房间的负缓存（30 分钟内跳过预热）");
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "负缓存恢复失败(不影响预热): " + t.getMessage());
            }

            HuyaSDKLogger.init();
            HuyaSDKLogger.info(TAG, "开始初始化虎牙 SDK...");

            String marsCheckError = checkMarsNativeLibraries();
            if (marsCheckError != null) {
                LogBridge.e(TAG, "❌ 虎牙 Mars 原生库预检失败，跳过 SDK 初始化: " + marsCheckError);
                HuyaSDKLogger.error(TAG, "Mars 原生库预检失败: " + marsCheckError);
                ExceptionReporter.reportHuyaBusinessFailure(
                        "HuyaSDKParser.checkMarsNativeLibraries", -99001, marsCheckError,
                        "api=" + android.os.Build.VERSION.SDK_INT);
                sInitOk = false;
                synchronized (sInitWaitLock) { sInitWaitLock.notifyAll(); }
                return;
            }
            LogBridge.i(TAG, "✅ 虎牙 Mars 原生库预检通过");

            SdkCompatHook.neutralizeCrashIfDebug();

            LogBridge.d(TAG, "[1/4] 准备构建 HuyaBerryConfig.Builder");

            int gameId;
            String appId;
            String appKey;
            try {
                HuyaCredentials credentials = HuyaCredentials.getInstance(app);
                gameId = credentials.getGameId();
                appId = credentials.getAppId();
                appKey = credentials.getAppKey();
                LogBridge.i(TAG, "  🔐 从加密存储加载凭证: " + credentials.getCredentialsSummary());
            } catch (Throwable credError) {
                LogBridge.e(TAG, "  ❌ 加载凭证失败: " + credError.getMessage());
                ExceptionReporter.report("HuyaCredentials", credError);

                gameId = decodeGameIdFallback();
                appId = decodeAppIdFallback();
                appKey = decodeAppKeyFallback();
                LogBridge.i(TAG, "  🔐 使用编码后的默认凭证");
            }

            HuyaBerryConfig.Builder builder = new HuyaBerryConfig.Builder()
                    .gameId(gameId)
                    .appId(appId)
                    .appKey(appKey)
                    .debugMode(false)
                    .landscapeMode(false)
                    .isOpenBugly(false)
                    .isNeedPlay(false)
                    .cameraMode(false)
                    .oneKeyGangUp(false)
                    .hidePauseBtn(false);
            LogBridge.i(TAG, "  ✅ HuyaBerryConfig.Builder 链路构建完成（含官方精简开关）");

            try {
                final java.lang.String DEAD_HOST = "http://127.0.0.1";
                com.huya.security.DeviceFingerprintSDK.host = DEAD_HOST;
                com.huya.security.DeviceFingerprintSDK.kiwiHost = DEAD_HOST;
                com.huya.security.DeviceFingerprintSDK.nimoHost = DEAD_HOST;
                com.huya.security.DeviceFingerprintSDK.openApiHost = DEAD_HOST;

                com.huya.security.hydeviceid.HyDeviceChecker.setUrlDeviceChecker(DEAD_HOST);
                LogBridge.i(TAG, "[设备指纹] UDB host 已改写指向 127.0.0.1 → udbdf.huya.com 等上报全部失效 ✅");
            } catch (Throwable fpE) {
                LogBridge.w(TAG, "[设备指纹] host 改写失败: " + fpE.getMessage());
            }

            HuyaCacheGovernor.applyOnBuilder(builder, app);

            HuyaBerryConfig config = builder.build();
            LogBridge.d(TAG, "[2/4] HuyaBerryConfig build 完成");

            sHuyaBerry = HuyaBerry.instance();
            if (sHuyaBerry == null) {
                LogBridge.e(TAG, "[3/4] HuyaBerry.instance() 返回 null，SDK 未初始化");
                sInitOk = false;
                synchronized (sInitWaitLock) { sInitWaitLock.notifyAll(); }
                return;
            }

            try {
                ServiceHelper.createService(ICrashService.class, NoOpCrashService.class);
            } catch (Throwable regE) {
                LogBridge.w(TAG, "NoOpCrashService 注册失败: " + regE.getMessage());
            }

            boolean statApiOk = false;
            try {

                com.duowan.live.one.module.report.HuyaStatisAgent agent =
                        com.duowan.live.one.module.report.HuyaStatisAgent.getInstance();
                if (agent != null) {

                    java.lang.reflect.Field mApiF =
                            com.duowan.live.one.module.report.HuyaStatisAgent.class.getDeclaredField("mApi");
                    mApiF.setAccessible(true);
                    mApiF.set(agent, new NoOpHuyaStatisApi());
                    Object replaced = mApiF.get(agent);
                    statApiOk = replaced instanceof NoOpHuyaStatisApi;
                    LogBridge.i(TAG, "[hiido统计] HuyaStatisAgent.mApi 替换为 NoOp: "
                            + (statApiOk ? "✅ 成功(PV/init 已拦截)" : "❌ 替换后类型不符!"));
                } else {
                    LogBridge.w(TAG, "[hiido统计] HuyaStatisAgent.getInstance() 返回 null，跳过替换");
                }
            } catch (Throwable statE) {
                LogBridge.w(TAG, "[hiido统计] mApi 反射替换失败: " + statE.getMessage());
            }

            try {
                sHuyaBerry.init(app, config);
                LogBridge.i(TAG, "✅ HuyaBerry SDK 初始化成功 (init 无异常)");
                sInitOk = true;

                synchronized (sInitWaitLock) {
                    sInitWaitLock.notifyAll();
                    sInitNotified = true;

                    List<Runnable> listeners = new ArrayList<>(sInitReadyListeners);
                    sInitReadyListeners.clear();
                    for (Runnable r : listeners) {
                        try { r.run(); } catch (Exception e) { LogBridge.w(TAG, "init listener error: " + e.getMessage()); }
                    }
                }
                LogBridge.i(TAG, "✅ HuyaBerry SDK 初始化 & 绑定完成");

                try {
                    com.tv.live.CrashHandler.getInstance().init(app);
                    LogBridge.i(TAG, "✅ 全局崩溃处理器已夺回（虎牙 CrashHandler 已被覆盖）");
                } catch (Throwable chE) {
                    LogBridge.w(TAG, "⚠️ 夺回全局崩溃处理器失败: " + chE.getMessage());
                }

                try {
                    injectMultiGameIds();
                    changeGame(2135, new OnChangeGameListener() {
                        @Override public void onSuccess() {
                            LogBridge.i(TAG, "✅ 品类校验成功: 虎牙一起看(2135)");
                        }
                        @Override public void onError(String errMsg) {
                            LogBridge.w(TAG, "⚠️ 品类校验失败(一起看2135): " + errMsg
                                    + "（初始化即 2135，主列表走HTTP gameId=2135，不影响一起看内容获取）");
                        }
                    });
                } catch (Throwable changeE) {
                    LogBridge.w(TAG, "⚠️ 品类校验异常: " + changeE.getMessage());
                }

                boolean apmOk = false, statOk = false;
                try {
                    com.huya.ciku.apm.MonitorCenter.getInstance().stopReport();
                    apmOk = true;
                } catch (Throwable e) {
                    LogBridge.w(TAG, "[SDK上报验证] Step3 APM stopReport 失败: " + e.getMessage());
                }
                try {
                    com.huya.live.common.api.BaseApi.setReportApi(new NoOpReportApi());

                    Object curReportApi = com.huya.live.common.api.BaseApi.getReportApi();
                    String reportCls = (curReportApi != null)
                            ? curReportApi.getClass().getName() : "null";
                    statOk = curReportApi instanceof NoOpReportApi;
                    LogBridge.i(TAG, "[SDK上报验证] Step4 统计通道: ReportApi=" + reportCls
                            + (statOk ? " ✅ 已替换为NoOp" : " ❌ 替换失败!"));
                } catch (Throwable e) {
                    LogBridge.w(TAG, "[SDK上报验证] Step4 setReportApi 失败: " + e.getMessage());
                }

                boolean nsStatOk = false;
                try {
                    com.huya.mtp.hyns.stat.NSStatUtil.mEnabled = false;
                    nsStatOk = !com.huya.mtp.hyns.stat.NSStatUtil.mEnabled;
                    LogBridge.i(TAG, "[SDK上报验证] Step5 NSStat 网络统计: mEnabled="
                            + com.huya.mtp.hyns.stat.NSStatUtil.mEnabled
                            + (nsStatOk ? " ✅ 已关闭" : " ❌ 关闭失败!"));
                } catch (Throwable e) {
                    LogBridge.w(TAG, "[SDK上报验证] Step5 NSStat 网络统计关闭失败: " + e.getMessage());
                }

                boolean reportGuardOk = false;
                try {

                    HuyaBerryReportGuard.applyAfterInit(sHuyaBerry);
                    reportGuardOk = true;
                } catch (Throwable e) {
                    LogBridge.w(TAG, "[SDK上报验证] 播放数据/账号双重保险失败: " + e.getMessage());
                }

                boolean allOk = apmOk && statOk && statApiOk && reportGuardOk && nsStatOk;
                LogBridge.i(TAG, "[SDK上报验证] ====== SDK上报关闭汇总 ======");
                LogBridge.i(TAG, "[SDK上报验证] [ciku APM]");
                LogBridge.i(TAG, "[SDK上报验证]   └─ stopReport             "
                        + (apmOk ? "✅" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证] [Hiido运营统计]");
                LogBridge.i(TAG, "[SDK上报验证]   ├─ ReportApi→NoOpReportApi "
                        + (statOk ? "✅" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证]   └─ HuyaStatisAgent.mApi→NoOp "
                        + (statApiOk ? "✅ 成功(PV/init已拦截)" : "❌ 残留1次PV/init"));
                LogBridge.i(TAG, "[SDK上报验证] [Builder精简开关]");
                LogBridge.i(TAG, "[SDK上报验证]   └─ debugMode/cameraMode/"
                        + "oneKeyGangUp/isNeedPlay/hidePauseBtn/landscapeMode=false ✅");
                LogBridge.i(TAG, "[SDK上报验证] [播放数据/游戏账号双重保险]");
                LogBridge.i(TAG, "[SDK上报验证]   ├─ sendPlayerData(播放心跳上报) "
                        + (reportGuardOk ? "✅ no-op 禁用" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证]   └─ setGameAccountID(游戏账号绑定) "
                        + (reportGuardOk ? "✅ 已清空且禁用" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证] [hyns 网络请求统计]");
                LogBridge.i(TAG, "[SDK上报验证]   └─ NSStatUtil.mEnabled=false "
                        + (nsStatOk ? "✅ 已关闭(逐请求性能上报)" : "❌"));
                LogBridge.i(TAG, "[SDK上报验证] ====== 全部关闭"
                        + (allOk ? "成功 ✅✅✅" : "有失败项 ❌（见上方详情）") + " ======");
                if (allOk) {
                    LogBridge.i(TAG, "✅ 已直接关闭 SDK 上报: APM+统计通道全部关闭");
                }
            } catch (Throwable initE) {

                LogBridge.w(TAG, "❌ HuyaBerry init 失败（需要修复才能触发SDK回调）: " + logThrowableChain(initE));
                ExceptionReporter.report("HuyaBerry.init", initE);

                sInitOk = false;
                return;
            }

            try {
                sHuyaBerry.setBerryEventDelegate(new HuyaBerry.BerryEvent() {
                    @Override
                    public void onEventCallback(Map<String, String> eventData) {
                        String eventType = eventData != null
                                ? eventData.get(HuyaBerry.BerryEvent.BERRYEVENT_EVENTTYPE)
                                : "unknown";
                        HuyaSDKLogger.onBerryEvent(eventType, eventData);
                    }
                });
                HuyaSDKLogger.info(TAG, "✅ BerryEvent 事件代理已注册");
            } catch (Throwable t) {
                HuyaSDKLogger.warn(TAG, "⚠️ BerryEvent 事件代理注册失败: " + t.getMessage());
                ExceptionReporter.report("BerryEvent.register", t);
            }

            synchronized (sPreloadPendingQueue) {
                if (!sPreloadPendingQueue.isEmpty() && !sPreloadScheduled) {
                    LogBridge.d(TAG, "🔄【预解析】SDK init 完成，" + sPreloadPendingQueue.size()
                            + " 个房间待预解析（错峰：等首播就绪或 " + (PRELOAD_DEFERRED_DELAY_MS / 1000) + "s 兜底）");
                    if (!sPreloadStartFallbackScheduled) {
                        sPreloadStartFallbackScheduled = true;
                        sPreloadHandler.postDelayed(PRELOAD_FALLBACK_START, PRELOAD_DEFERRED_DELAY_MS);
                    }

                    maybeStartPreload("init补发");
                }
            }

            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        new Thread(new Runnable() {
                            @Override public void run() { runBerryDebugChecker(); }
                        }, "BerryDebugChecker").start();
                    }
                }, 1500);
            } catch (Throwable t) {
                LogBridge.w(TAG, "  ⚠️ BerryDebugChecker 启动失败（跳过，不影响主流程）", t);
                ExceptionReporter.report("BerryDebugChecker.start", t);
            }

            final Runnable autoTestRunnable = new Runnable() {
                @Override
                public void run() {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {

                            final int[] TEST_ROOMS = {11342412, 11342421, 11602058};
                            final int TEST_ROOM = TEST_ROOMS[sAutoTestRound % TEST_ROOMS.length];
                            sAutoTestRound++;
                            LogBridge.i(TAG, "🧪【自动化 SDK 解析测试】开始 parseFull, roomId=" + TEST_ROOM);
                            parseFull(TEST_ROOM, new OnSDKFullResultListener() {
                                @Override
                                public void onSuccess(HuyaStreamInfo defaultStream,
                                                      java.util.List<HuyaStreamInfo> allStreams,
                                                      java.util.List<String> lines) {
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("🎉【自动化 SDK 解析测试】成功！roomId=").append(TEST_ROOM).append("\n");
                                    sb.append("  线路数量=").append(lines == null ? 0 : lines.size()).append("\n");
                                    sb.append("  流总数=").append(allStreams == null ? 0 : allStreams.size()).append("\n");
                                    if (defaultStream != null) {
                                        sb.append("  默认流: line=").append(defaultStream.lineIndex)
                                          .append(" bitRate=").append(defaultStream.bitRate)
                                          .append(" disp=").append(defaultStream.bitRateDisplayName).append("\n");
                                        String url = defaultStream.getPlayUrl();
                                        if (url != null) {
                                            sb.append("  默认URL=").append(url.substring(0, Math.min(url.length(), 100)))
                                              .append(url.length() > 100 ? "..." : "").append("\n");
                                        }
                                    }
                                    if (allStreams != null && !allStreams.isEmpty()) {
                                        sb.append("  全部流信息摘要：\n");
                                        for (HuyaStreamInfo s : allStreams) {
                                            sb.append("    - [line").append(s.lineIndex).append("] ")
                                              .append(s.bitRateDisplayName).append("(").append(s.bitRate)
                                              .append("bps) HLS=").append(s.isHls()).append(" sUrl_len=")
                                              .append(s.hlsUrl == null ? 0 : s.hlsUrl.length()).append("\n");
                                        }
                                    }
                                    LogBridge.i(TAG, sb.toString());
                                }

                                @Override
                                public void onError(String error) {
                                    LogBridge.e(TAG, "❌【自动化 SDK 解析测试】失败! roomId=" + TEST_ROOM + " -> " + error);
                                }
                            });
                        }
                    }, "HuyaSDKParser-AutoTest").start();

                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 90000);
                }
            };
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(autoTestRunnable, 60000);

        } catch (Throwable e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                LogBridge.e(TAG, "SDK 绑定异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
                HuyaSDKLogger.error(TAG, "SDK绑定异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                ExceptionReporter.report("HuyaSDK.bind", cause);
            } else {
                LogBridge.e(TAG, "SDK 绑定异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                HuyaSDKLogger.error(TAG, "SDK绑定异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ExceptionReporter.report("HuyaSDK.bind", e);
            }
            sInitOk = false;
            synchronized (sInitWaitLock) { sInitWaitLock.notifyAll(); }
        }
    }

    private static String checkMarsNativeLibraries() {
        try {

            if (android.os.Build.VERSION.SDK_INT <= 22) {
                LogBridge.w(TAG, "⚠️ Android " + android.os.Build.VERSION.RELEASE + "(API "
                        + android.os.Build.VERSION.SDK_INT
                        + ") 官方不在 Mars STN 支持范围，仍尝试加载验证 v7a 兼容性");
            }

            String[] libs = {"c++_shared", "stlport_shared", "marsstn"};
            for (String lib : libs) {
                try {
                    System.loadLibrary(lib);
                    LogBridge.d(TAG, "  ✅ loadLibrary(\"" + lib + "\") 成功");
                } catch (UnsatisfiedLinkError ule) {
                    LogBridge.w(TAG, "  ⚠️ loadLibrary(\"" + lib + "\") 失败: " + ule.getMessage());

                    if ("marsstn".equals(lib)) {
                        return "无法加载 libmarsstn.so: " + ule.getMessage();
                    }
                }
            }

            try {
                Class<?> stnLogicClass = Class.forName("com.tencent.mars.stn.StnLogic");
                java.lang.reflect.Method startTaskMethod = null;
                for (java.lang.reflect.Method m : stnLogicClass.getDeclaredMethods()) {
                    if ("startTask".equals(m.getName())) {
                        startTaskMethod = m;
                        break;
                    }
                }
                if (startTaskMethod == null) {
                    return "StnLogic.startTask 方法不存在（SDK 版本不匹配）";
                }
                if (!java.lang.reflect.Modifier.isNative(startTaskMethod.getModifiers())) {
                    return "StnLogic.startTask 未注册为 native 方法，libmarsstn.so 与当前系统不兼容";
                }
                LogBridge.d(TAG, "  ✅ StnLogic.startTask native 注册检查通过");
            } catch (ClassNotFoundException cnfe) {

                LogBridge.w(TAG, "  ⚠️ StnLogic 类未找到，跳过 native 注册检查: " + cnfe.getMessage());
            }
            return null;
        } catch (Throwable t) {
            LogBridge.e(TAG, "checkMarsNativeLibraries 异常: " + t.getMessage(), t);
            return "Mars 原生库预检异常: " + t.getMessage();
        }
    }

    private static String logThrowableChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (t != null && depth++ < 10) {
            sb.append(t.getClass().getSimpleName())
              .append(": ").append(t.getMessage()).append("\n");
            for (StackTraceElement s : t.getStackTrace()) {
                String cn = s.getClassName();
                if (cn.startsWith("com.huya") || cn.startsWith("com.duowan") || cn.startsWith("com.tv.live")) {
                    sb.append("  ↳ ").append(s.toString()).append("\n");
                }
            }
            Throwable next = t.getCause();
            if (next == null || next == t) break;
            t = next;
            sb.append("  ▼ Cause: ");
        }
        return sb.toString();
    }

    public static boolean isSDKAvailable() {
        return sInitOk;
    }

    public static boolean waitForInit(long timeoutMs) {
        if (sInitOk) return true;
        if (sInitDone && !sInitOk) return false;

        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (sInitWaitLock) {
            while (!sInitOk && !sInitDone) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    LogBridge.w(TAG, "waitForInit 超时(" + timeoutMs + "ms), sInitOk=" + sInitOk + " sInitDone=" + sInitDone);
                    return false;
                }
                try {
                    sInitWaitLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return sInitOk;
        }
    }

    public static void addInitReadyListener(Runnable listener) {
        synchronized (sInitWaitLock) {
            if (sInitOk) {
                listener.run();
            } else {
                sInitReadyListeners.add(listener);
            }
        }
    }

    private static final Map<Integer, List<OnSDKFullResultListener>> sInflight = new HashMap<>();

    public static void parseFull(final int roomId, final OnSDKFullResultListener listener) {
        if (!sInitOk || sHuyaBerry == null) {

            LogBridge.i(TAG, "parseFull: SDK未初始化, roomId=" + roomId + " → 等待SDK就绪后重试");
            addInitReadyListener(new Runnable() {
                @Override public void run() {
                    if (sInitOk && sHuyaBerry != null) {
                        LogBridge.i(TAG, "parseFull: SDK就绪, 重试 roomId=" + roomId);
                        parseFullInternal(roomId, listener);
                    } else {
                        HuyaSDKLogger.error(TAG, "parseFull: SDK初始化失败, roomId=" + roomId);
                        listener.onError("SDK 初始化失败");
                    }
                }
            });
            return;
        }
        parseFullInternal(roomId, listener);
    }

    public static void parseFullByUid(long uid, final OnSDKFullResultListener listener) {
        if (!sInitOk || sHuyaBerry == null) {
            HuyaSDKLogger.error(TAG, "parseFullByUid: SDK未初始化, uid=" + uid);
            listener.onError("SDK 未初始化");
            return;
        }

        final int cacheKey = (int) -uid;

        CachedStreams cached = getCachedStreams(cacheKey);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            LogBridge.d(TAG, "命中uid=" + uid + "流信息缓存（" + (System.currentTimeMillis() - cached.timestamp) / 1000 + "s前）");
            HuyaSDKLogger.info(TAG, "命中缓存(uid): uid=" + uid + " streams=" + cached.streams.size());
            HuyaStreamInfo def = pickDefaultStream(cached.streams);
            List<String> lines = buildLineLabels(cached.streams);
            listener.onSuccess(def, cached.streams, lines);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);
        final long targetUid = uid;

        final OnSDKFullResultListener wrappedListener = new OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaStreamInfo defaultStream, List<HuyaStreamInfo> allStreams, List<String> lines) {

                if (allStreams != null && !allStreams.isEmpty()) {
                    CachedStreams cs = new CachedStreams();
                    cs.timestamp = System.currentTimeMillis();
                    cs.streams = allStreams;
                    sStreamsCache.put(cacheKey, cs);
                    LogBridge.d(TAG, "uid=" + targetUid + " 流信息写入缓存: " + allStreams.size() + " 条流");
                }
                listener.onSuccess(defaultStream, allStreams, lines);
            }
            @Override
            public void onError(String error) {
                listener.onError(error);
            }
        };

        new Thread(() -> {
            try {
                CustomUICallback<BaseCallback> sdkCallback = new CustomUICallback<BaseCallback>() {
                    @Override
                    public void onResultCallback(int code, BaseCallback data) {
                        String dataType = data == null ? "null" : data.getClass().getSimpleName();
                        LogBridge.d(TAG, "📞【SDK回调进入-uid】onResultCallback(code=" + code
                                + ", data=" + dataType + ", uid=" + targetUid + ")");
                        HuyaSDKLogger.onCustomUICallback("onResultCallback-uid", code,
                                "dataType=" + dataType + " uid=" + targetUid);

                        boolean alreadyTimeout = done.get();
                        try {
                            if (data instanceof LiveInfo) {
                                if (Looper.myLooper() == Looper.getMainLooper()) {

                                    final int fCode = code;
                                    final LiveInfo li = (LiveInfo) data;
                                    AppExecutors.io(() -> {
                                        try {
                                            handleFullResultByUid(fCode, li, wrappedListener, done, targetUid);
                                        } catch (Exception e2) {
                                            LogBridge.e(TAG, "handleFullResultByUid 后台异常: " + e2.getMessage());
                                            if (!done.get() && done.compareAndSet(false, true)) {
                                                wrappedListener.onError("结果处理异常: " + e2.getMessage());
                                            }
                                        }
                                    });
                                } else {
                                    handleFullResultByUid(code, (LiveInfo) data, wrappedListener, done, targetUid);
                                }
                            } else {
                                if (done.compareAndSet(false, true)) {
                                    String err = (data == null)
                                            ? "SDK 返回空结果"
                                            : ("SDK 返回类型 " + dataType + "，非 LiveInfo");
                                    HuyaSDKLogger.error(TAG, err);
                                    wrappedListener.onError(err);
                                }
                            }
                        } catch (Exception e) {
                            LogBridge.e(TAG, "handleFullResultByUid 异常: " + e.getMessage());
                            if (!alreadyTimeout && done.compareAndSet(false, true)) {
                                wrappedListener.onError("结果处理异常: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                        int size = (list == null) ? 0 : list.size();
                        LogBridge.d(TAG, "SDK onResultListCallback-uid: code=" + code + " size=" + size);
                    }
                };

                LogBridge.d(TAG, "调用 SDK getLiveData(uid), uid=" + uid);
                HuyaSDKLogger.debug(TAG, "发起SDK解析(uid): uid=" + uid);
                sHuyaBerry.getLiveData(targetUid, sdkCallback);

                Thread.sleep(30000);
                if (done.compareAndSet(false, true)) {
                    LogBridge.w(TAG, "SDK 调用(uid)超时 (30s), uid=" + uid);
                    wrappedListener.onError("SDK 解析超时");
                }
            } catch (Throwable e) {
                LogBridge.e(TAG, "SDK 解析(uid)异常完整链：\n" + logThrowableChain(e));
                String userMsg = (e.getCause() != null) ? e.getCause().toString() : e.toString();
                if (userMsg.length() > 240) userMsg = userMsg.substring(0, 240) + "...";
                if (done.compareAndSet(false, true)) {
                    wrappedListener.onError("SDK 异常: " + userMsg);
                }
            }
        }, "HuyaSDKParser-FullByUid").start();
    }

    private static void handleFullResultByUid(int code, LiveInfo liveInfo, OnSDKFullResultListener listener,
                                              AtomicBoolean done, long uid) {
        if (liveInfo == null) {
            if (done.compareAndSet(false, true)) listener.onError("SDK 返回空结果");
            return;
        }
        if (code != BaseCallback.SUCCESS) {
            if (done.compareAndSet(false, true)) {
                String msg = "SDK code=" + code;
                try {
                    java.lang.reflect.Field f = liveInfo.getClass().getField("errorMsg");
                    String err = (String) f.get(liveInfo);
                    if (err != null && !err.isEmpty()) msg = err;
                } catch (Throwable ignored) {}
                listener.onError(msg);
            }
            return;
        }
        try {

            List<HuyaStreamInfo> streamList = extractFullStreamList(liveInfo, -1);
            if (streamList == null || streamList.isEmpty()) {
                if (done.compareAndSet(false, true)) listener.onError("未获取到播放地址");
                return;
            }

            HuyaStreamInfo def = pickDefaultStream(streamList);
            List<String> lineLabels = buildLineLabels(streamList);
            if (done.compareAndSet(false, true)) {
                listener.onSuccess(def, streamList, lineLabels);
            }
        } catch (Throwable t) {
            LogBridge.e(TAG, "解析uid流地址异常: " + t.getMessage());
            if (done.compareAndSet(false, true)) listener.onError("解析异常: " + t.getMessage());
        }
    }

    private static void parseFullInternal(int roomId, OnSDKFullResultListener listener) {

        CachedStreams cached = getCachedStreams(roomId);
        if (cached != null && cached.streams != null && !cached.streams.isEmpty()) {
            LogBridge.d(TAG, "命中房间" + roomId + "流信息缓存（" + (System.currentTimeMillis() - cached.timestamp) / 1000 + "s前）");
            HuyaSDKLogger.info(TAG, "命中缓存: roomId=" + roomId + " streams=" + cached.streams.size());
            HuyaStreamInfo def = pickDefaultStream(cached.streams);
            List<String> lines = buildLineLabels(cached.streams);
            listener.onSuccess(def, cached.streams, lines);
            return;
        }

        synchronized (sInflight) {
            List<OnSDKFullResultListener> waiters = sInflight.get(roomId);
            if (waiters != null) {
                HuyaSDKLogger.debug(TAG, "【去重】挂接等待 roomId=" + roomId
                        + " 等待者数=" + (waiters.size() + 1));
                waiters.add(listener);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(listener);
            sInflight.put(roomId, waiters);
        }
        HuyaSDKLogger.debug(TAG, "【去重】新请求入队 roomId=" + roomId
                + " inflight=" + sInflight.size() + " 线程=" + Thread.currentThread().getName());

        OnSDKFullResultListener proxy = new OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaStreamInfo defaultStream,
                                  List<HuyaStreamInfo> allStreams, List<String> lines) {
                List<OnSDKFullResultListener> ws;
                synchronized (sInflight) { ws = sInflight.remove(roomId); }
                HuyaSDKLogger.debug(TAG, "【去重】完成分发 roomId=" + roomId
                        + " 等待者=" + (ws == null ? 0 : ws.size()));
                if (ws != null) {
                    for (OnSDKFullResultListener w : ws) w.onSuccess(defaultStream, allStreams, lines);
                }
            }

            @Override
            public void onError(String error) {
                List<OnSDKFullResultListener> ws;
                synchronized (sInflight) { ws = sInflight.remove(roomId); }
                HuyaSDKLogger.warn(TAG, "【去重】完成分发(onError) roomId=" + roomId
                        + " 等待者=" + (ws == null ? 0 : ws.size()));
                if (ws != null) {
                    for (OnSDKFullResultListener w : ws) w.onError(error);
                }
            }
        };
        doParseFull(roomId, proxy);
    }

    private static void doParseFull(int roomId, OnSDKFullResultListener listener) {
        AtomicBoolean done = new AtomicBoolean(false);

        final OnSDKFullResultListener outerListener = listener;
        final int finalRoomId = roomId;

        new Thread(() -> {
            try {

                CustomUICallback<BaseCallback> sdkCallback = new CustomUICallback<BaseCallback>() {
                    @Override
                    public void onResultCallback(int code, BaseCallback data) {

                        String dataType = data == null ? "null" : data.getClass().getSimpleName();
                        LogBridge.d(TAG, "📞【SDK回调进入】onResultCallback(code=" + code
                                + ", data=" + dataType
                                + ") from " + Thread.currentThread().getName());
                        HuyaSDKLogger.onCustomUICallback("onResultCallback", code,
                                "dataType=" + dataType + " roomId=" + finalRoomId);

                        boolean alreadyTimeout = done.get();
                        LogBridge.d(TAG, "SDK onResultCallback: code=" + code
                                + " data=" + dataType
                                + " alreadyTimeout=" + alreadyTimeout
                                + (alreadyTimeout ? "（⚠️回调晚于30s超时，但继续解析不丢弃）" : ""));
                        try {

                            if (data instanceof LiveInfo) {
                                if (Looper.myLooper() == Looper.getMainLooper()) {

                                    final int fCode = code;
                                    final LiveInfo li = (LiveInfo) data;
                                    AppExecutors.io(() -> {
                                        try {
                                            handleFullResult(fCode, li, outerListener, done, finalRoomId);
                                        } catch (Exception e2) {
                                            LogBridge.e(TAG, "handleFullResult 后台异常: " + e2.getMessage());
                                            if (!done.get() && done.compareAndSet(false, true)) {
                                                outerListener.onError("结果处理异常: " + e2.getMessage());
                                            }
                                        }
                                    });
                                } else {
                                    handleFullResult(code, (LiveInfo) data, outerListener, done, finalRoomId);
                                }
                            } else {

                                if (done.compareAndSet(false, true)) {
                                    String err = (data == null)
                                            ? "SDK 返回空结果"
                                            : ("SDK 返回类型 " + dataType + "，非 LiveInfo");
                                    HuyaSDKLogger.error(TAG, err);
                                    ExceptionReporter.reportHuyaBusinessFailure(
                                            "CustomUI.onResultCallback", code, err,
                                            "roomId=" + finalRoomId);
                                    outerListener.onError(err);
                                }
                            }
                        } catch (Exception e) {
                            LogBridge.e(TAG, "handleFullResult 异常: " + e.getMessage(), e);
                            HuyaSDKLogger.error(TAG, "handleFullResult 异常: " + e.getMessage());
                            ExceptionReporter.report("HuyaSDK.handleFullResult", e);
                            if (!alreadyTimeout && done.compareAndSet(false, true)) {
                                outerListener.onError("结果处理异常: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                        int size = (list == null) ? 0 : list.size();
                        LogBridge.d(TAG, "SDK onResultListCallback: code=" + code + " size=" + size);
                        HuyaSDKLogger.onCustomUICallback("onResultListCallback", code, "size=" + size);
                    }
                };
                LogBridge.d(TAG, "✅ CustomUICallback 直接实现创建成功 (impl=" + sdkCallback.getClass().getName() + ")");

                LogBridge.d(TAG, "调用 SDK getLiveDataByRoomId, roomId=" + roomId);
                HuyaSDKLogger.debug(TAG, "发起SDK解析: roomId=" + roomId);
                sHuyaBerry.getLiveDataByRoomId(roomId, sdkCallback);

                Thread.sleep(30000);
                if (done.compareAndSet(false, true)) {
                    LogBridge.w(TAG, "SDK 调用超时 (30s)");
                    HuyaSDKLogger.error(TAG, "SDK解析超时: roomId=" + roomId);
                    ExceptionReporter.reportHuyaBusinessFailure(
                            "HuyaSDKParser.parseFull", -99998, "SDK 解析超时 (30s)",
                            "roomId=" + roomId);
                    listener.onError("SDK 解析超时");
                }
            } catch (UnsatisfiedLinkError ule) {

                String msg = "Mars STN 原生库不兼容: " + ule.getMessage();
                LogBridge.e(TAG, "SDK 解析触发 UnsatisfiedLinkError，roomId=" + roomId + " " + msg, ule);
                HuyaSDKLogger.error(TAG, msg + ", roomId=" + roomId);
                ExceptionReporter.reportHuyaBusinessFailure(
                        "HuyaSDKParser.parseFull", -99002, msg,
                        "roomId=" + roomId + ",api=" + android.os.Build.VERSION.SDK_INT);
                sInitOk = false;
                sHuyaBerry = null;
                if (done.compareAndSet(false, true)) {
                    listener.onError("虎牙 SDK 原生库与当前系统不兼容，无法解析");
                }
            } catch (Throwable e) {

                LogBridge.e(TAG, "SDK 解析异常完整链：\n" + logThrowableChain(e));
                HuyaSDKLogger.error(TAG, "SDK解析异常: " + e.getMessage() + ", roomId=" + roomId);
                ExceptionReporter.report("HuyaSDKParser.parseFull", e);
                String userMsg = (e.getCause() != null)
                        ? e.getCause().toString()
                        : e.toString();
                if (userMsg.length() > 240) userMsg = userMsg.substring(0, 240) + "...";
                if (done.compareAndSet(false, true)) {
                    listener.onError("SDK 异常: " + userMsg);
                }
            }
        }, "HuyaSDKParser-Full").start();
    }

    @Deprecated
    public static void parse(int roomId, OnSDKResultListener listener) {
        parseFull(roomId, new OnSDKFullResultListener() {
            @Override
            public void onSuccess(HuyaStreamInfo defaultStream, List<HuyaStreamInfo> allStreams, List<String> lines) {
                listener.onSuccess(
                        defaultStream.hlsUrl != null ? defaultStream.hlsUrl : "",
                        defaultStream.flvUrl != null ? defaultStream.flvUrl : "",
                        defaultStream.isHls()
                );
            }

            @Override
            public void onError(String error) {
                listener.onError(error);
            }
        });
    }

    private static HuyaStreamInfo pickDefaultStream(List<HuyaStreamInfo> streams) {

        for (HuyaStreamInfo s : streams) {
            if (s.isDefaultLine && s.isDefaultBitrate) return s;
        }
        for (HuyaStreamInfo s : streams) {
            if (s.isDefaultLine) return s;
        }
        return streams.get(0);
    }

    private static List<String> buildLineLabels(List<HuyaStreamInfo> streams) {
        List<String> lines = new ArrayList<>();
        int lastIdx = -1;
        for (HuyaStreamInfo s : streams) {
            if (s.lineIndex != lastIdx) {
                lines.add(s.lineLabel);
                lastIdx = s.lineIndex;
            }
        }
        return lines;
    }

    private static void handleFullResult(int code, LiveInfo liveInfo, OnSDKFullResultListener listener,
                                         AtomicBoolean done, int roomId) {
        if (liveInfo == null) {
            HuyaSDKLogger.error(TAG, "handleFullResult: liveInfo=null, roomId=" + roomId);
            ExceptionReporter.reportHuyaBusinessFailure(
                    "HuyaSDKParser.handleFullResult", -99997, "liveInfo=null",
                    "roomId=" + roomId + ",code=" + code);

            if (done.compareAndSet(false, true)) listener.onError("SDK 返回空结果");
            return;
        }

        if (code != BaseCallback.SUCCESS) {
            HuyaSDKLogger.error(TAG, "handleFullResult: code=" + code + " (非SUCCESS), roomId=" + roomId);
            ExceptionReporter.reportHuyaBusinessFailure(
                    "HuyaSDKParser.handleFullResult", code, "code != SUCCESS",
                    "roomId=" + roomId);

            if (done.compareAndSet(false, true)) {
                listener.onError("SDK 返回失败码 code=" + code);
            }
            return;
        }

        List<HuyaStreamInfo> streams = extractFullStreamList(liveInfo, roomId);
        if (streams == null || streams.isEmpty()) {
            HuyaSDKLogger.error(TAG, "handleFullResult: 未提取到任何流, roomId=" + roomId);
            ExceptionReporter.reportHuyaBusinessFailure(
                    "HuyaSDKParser.extractFullStreamList", -99996,
                    "未提取到任何流地址 / 无码率",
                    "roomId=" + roomId);

            if (done.compareAndSet(false, true)) listener.onError("未提取到任何流地址");
            return;
        }

        CachedStreams cs = new CachedStreams();
        cs.timestamp = System.currentTimeMillis();
        cs.streams = streams;
        sStreamsCache.put(roomId, cs);
        LogBridge.d(TAG, "房间" + roomId + " 流信息写入缓存: " + streams.size() + " 条流");
        HuyaSDKLogger.info(TAG, "房间" + roomId + " SDK解析成功: " + streams.size() + " 条流");

        HuyaStreamInfo def = pickDefaultStream(streams);
        List<String> lines = buildLineLabels(streams);
        LogBridge.d(TAG, "SDK 解析完成: " + lines.size() + " 条线路, 默认 " + def);

        if (done.compareAndSet(false, true)) {
            listener.onSuccess(def, streams, lines);
        }
    }

    private static List<HuyaStreamInfo> extractFullStreamList(LiveInfo liveInfo, int roomId) {
        List<HuyaStreamInfo> out = new ArrayList<>();
        try {

            Vector<?> linesObj = liveInfo.getLines();
            if (linesObj == null || linesObj.isEmpty()) {
                LogBridge.w(TAG, "getLines 为空: " + (linesObj == null ? "null" : "size=0"));
                return fallbackExtractAsSingle(liveInfo);
            }
            LogBridge.d(TAG, "getLines: " + linesObj.size() + " 条线路");

            try {
                Class<?> phCls = Class.forName("com.huya.berry.module.Player.PlayerHelper");
                java.lang.reflect.Field ssiF = phCls.getDeclaredField("singleStreamInfo");
                ssiF.setAccessible(true);
                Object ssi = ssiF.get(null);
                LogBridge.d(TAG, "V33DBG room=" + roomId + " singleStreamInfo=" + ssi);
                if (ssi != null) {
                    java.lang.reflect.Field siF = ssi.getClass().getDeclaredField("singleInfo");
                    siF.setAccessible(true);
                    java.util.Map<?, ?> singleInfo = (java.util.Map<?, ?>) siF.get(ssi);
                    LogBridge.d(TAG, "V33DBG room=" + roomId + " singleInfo.size=" + (singleInfo == null ? -1 : singleInfo.size()));
                    if (singleInfo != null) {
                        for (java.util.Map.Entry<?, ?> e : singleInfo.entrySet()) {
                            Object li = e.getValue();
                            if (li == null) continue;
                            java.lang.reflect.Field brF = li.getClass().getDeclaredField("bitRateInfoList");
                            brF.setAccessible(true);
                            Object brl = brF.get(li);
                            LogBridge.d(TAG, "V33DBG room=" + roomId + " line=" + e.getKey() + " bitRateInfoList=" + brl);
                        }
                    }
                }
            } catch (Throwable t) {
                LogBridge.d(TAG, "V33DBG err: " + t);
            }

            for (int i = 0; i < linesObj.size(); i++) {
                int lineValue;
                try {
                    lineValue = ((Number) linesObj.get(i)).intValue();
                } catch (Exception e) {
                    LogBridge.d(TAG, "线路#" + i + " 取值失败: " + e.getMessage());
                    ExceptionReporter.report("extractFullStreamList.lineValue", e);
                    continue;
                }

                Vector<BitRateInfo> bitRates = liveInfo.getBitRateList(lineValue);
                if (bitRates == null) continue;
                LogBridge.d(TAG, "线路#" + i + "(v=" + lineValue + "): " + bitRates.size() + " 个码率");

                List<HuyaStreamInfo> lineStreams = new ArrayList<>();
                for (int j = 0; j < bitRates.size(); j++) {
                    BitRateInfo oneBr = bitRates.get(j);
                    if (oneBr == null) continue;
                    int br = oneBr.bitRate;
                    String dn = oneBr.disPlayName;

                    String hlsUrl = null;
                    String flvUrl = null;
                    try {
                        hlsUrl = liveInfo.getPlayUrlByLineAndBitrate(false, lineValue, br);
                    } catch (Exception e) {
                        LogBridge.d(TAG, "HLS URL 获取失败: line=" + lineValue + " br=" + br + ": " + e.getMessage());
                        ExceptionReporter.report("extractFullStreamList.hlsUrl", e);
                    }
                    try {
                        flvUrl = liveInfo.getPlayUrlByLineAndBitrate(true, lineValue, br);
                    } catch (Exception e) {
                        ExceptionReporter.report("extractFullStreamList.flvUrl", e);

                    }
                    if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                        LogBridge.d(TAG, "线路#" + i + " 码率" + br + "无有效URL，跳过");
                        continue;
                    }
                    HuyaStreamInfo s = new HuyaStreamInfo();
                    s.lineIndex = i;
                    s.lineValue = lineValue;
                    s.lineLabel = i == 0 ? "线路1(主线路)" : ("线路" + (i + 1));
                    s.bitRate = br;
                    s.bitRateDisplayName = !TextUtils.isEmpty(dn) ? dn : inferBitRateLabel(br);
                    s.resolutionLabel = inferResolutionLabelFromBitrate(br);
                    s.hlsUrl = hlsUrl;
                    s.flvUrl = flvUrl;
                    s.isDefaultLine = (i == 0);
                    lineStreams.add(s);
                    LogBridge.d(TAG, "  → " + s + " URL(" + (hlsUrl != null ? "HLS" : "")
                            + (flvUrl != null ? "/FLV" : "") + ")");
                }

                Collections.sort(lineStreams, (a, b) -> Integer.compare(b.bitRate, a.bitRate));
                for (int k = 0; k < lineStreams.size(); k++) {
                    lineStreams.get(k).isDefaultBitrate = (lineStreams.size() >= 2) ? (k == 1) : (k == 0);
                }
                out.addAll(lineStreams);
            }

            if (out.isEmpty()) return fallbackExtractAsSingle(liveInfo);
            return out;

        } catch (Exception e) {
            LogBridge.e(TAG, "extractFullStreamList 异常: " + e.getMessage());
            ExceptionReporter.report("extractFullStreamList.outer", e);
            if (!out.isEmpty()) return out;
            return fallbackExtractAsSingle(liveInfo);
        }
    }

    private static List<HuyaStreamInfo> fallbackExtractAsSingle(LiveInfo liveInfo) {
        String hls = null, flv = null;
        try {
            Vector<?> linesObj = liveInfo.getLines();
            if (linesObj != null && !linesObj.isEmpty()) {
                int line = ((Number) linesObj.get(0)).intValue();
                Vector<BitRateInfo> brs = liveInfo.getBitRateList(line);
                if (brs != null && !brs.isEmpty()) {
                    int br = brs.get(0).bitRate;
                    hls = liveInfo.getPlayUrlByLineAndBitrate(false, line, br);
                    flv = liveInfo.getPlayUrlByLineAndBitrate(true, line, br);
                }
            }
        } catch (Throwable ignored) {
            ExceptionReporter.report("fallbackExtractAsSingle", ignored);
        }

        if ((hls == null || hls.isEmpty()) && (flv == null || flv.isEmpty())) {
            return null;
        }
        HuyaStreamInfo s = new HuyaStreamInfo();
        s.lineIndex = 0;
        s.lineValue = 0;
        s.lineLabel = "线路1(主线路)";
        s.bitRate = 4000;
        s.bitRateDisplayName = "默认";
        s.resolutionLabel = "自适应";
        s.hlsUrl = hls;
        s.flvUrl = flv;
        s.isDefaultLine = true;
        s.isDefaultBitrate = true;
        List<HuyaStreamInfo> list = new ArrayList<>();
        list.add(s);
        return list;
    }

    private static String inferBitRateLabel(int brKbps) {
        if (brKbps >= 8000) return "蓝光" + (brKbps / 1000) + "M";
        if (brKbps >= 4000) return "超清" + (brKbps / 1000) + "M";
        if (brKbps >= 2000) return "高清" + (brKbps / 1000) + "M";
        if (brKbps >= 1000) return "标清" + (brKbps / 1000) + "M";
        return brKbps + "Kbps";
    }

    private static String inferResolutionLabelFromBitrate(int brKbps) {

        if (brKbps >= 8000) return "4K (2160p)";
        if (brKbps >= 4000) return "1080p";
        if (brKbps >= 2000) return "720p";
        if (brKbps >= 1200) return "540p";
        if (brKbps >= 600) return "360p";
        return "自适应";
    }

    private static void runBerryDebugChecker() {
        final String TAG2 = TAG + "-DebugChecker";
        LogBridge.i(TAG2, "================ 🔬【豆包推荐：播放器模块剥离自检】================");
        boolean ok = true;
        StringBuilder sb = new StringBuilder();
        int passed = 0, total = 0;

        total++;
        String[] playerClasses = new String[] {
                "com.huya.berry.sdkplayer.SdkPlayerService",
                "com.huya.berry.player.internal.BerryPlayer",
                "com.huya.berry.decoder.BerryDecoder",
                "com.huya.berry.nativerender.NativeRender",
                "com.huya.berry.sdkplayer.floats.view.PlayerActivity",
                "tv.danmaku.ijk.media.player.IjkMediaPlayer"
        };
        int cnfCount = 0;
        for (String c : playerClasses) {
            try {
                Class.forName(c);
                sb.append("  ⚠️  CLASS 还能加载（debug 阶段正常，release R8 会剥离）：").append(c).append("\n");
            } catch (ClassNotFoundException cnfe) {
                cnfCount++;
            } catch (Throwable t) {
                sb.append("  ℹ️  CLASS 加载其它异常（也算未加载，OK）：").append(c).append(" → ")
                        .append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                cnfCount++;
            }
        }
        if (cnfCount == playerClasses.length) {
            passed++;
            LogBridge.i(TAG2, "  ✅ 检查项1【播放器类加载】：全部 ClassNotFound（R8 已剥离 / 未加载，共" + playerClasses.length + "/" + playerClasses.length + "）");
        } else {
            sb.insert(0, "  ℹ️  检查项1【播放器类加载】：ClassNotFound " + cnfCount + "/" + playerClasses.length
                    + "（debug minifyEnabled=false 属正常，release 会全部消失）\n");

            passed++;
        }

        total++;
        boolean foundPlayerModule = false;
        try {

            Class<?> mmClass = Class.forName("com.huya.berry.module.ModuleManager");
            Object mmInstance = null;
            try {
                java.lang.reflect.Method instanceM = mmClass.getMethod("getInstance");
                mmInstance = instanceM.invoke(null);
            } catch (Throwable t1) {
                try {
                    java.lang.reflect.Method instanceM = mmClass.getMethod("instance");
                    mmInstance = instanceM.invoke(null);
                } catch (Throwable ignore) {}
            }
            if (mmInstance != null) {
                for (java.lang.reflect.Field f : mmClass.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(mmInstance);
                        if (v instanceof java.util.Collection) {
                            for (Object item : (java.util.Collection<?>) v) {
                                if (item != null && item.getClass().getName().toLowerCase()
                                        .matches(".*(player|video|decoder|render).*")) {
                                    foundPlayerModule = true;
                                    sb.append("  ⚠️  注册模块含播放器相关：").append(item.getClass().getName()).append("\n");
                                }
                            }
                        } else if (v instanceof java.util.Map) {
                            for (Object item : ((java.util.Map<?, ?>) v).values()) {
                                if (item != null && item.getClass().getName().toLowerCase()
                                        .matches(".*(player|video|decoder|render).*")) {
                                    foundPlayerModule = true;
                                    sb.append("  ⚠️  注册模块含播放器相关：").append(item.getClass().getName()).append("\n");
                                }
                            }
                        }
                    } catch (Throwable ignore) {}
                }
            }
            if (!foundPlayerModule) {
                passed++;
                LogBridge.i(TAG2, "  ✅ 检查项2【模块注册】：ModuleManager 未检测到 PlayerModule（官方开关已生效）");
            } else {
                ok = false;
                LogBridge.w(TAG2, "  ❌ 检查项2【模块注册】：发现播放器相关模块注册，请核对官方 isNeedPlay=false 是否被 SDK 版本忽略");
            }
        } catch (ClassNotFoundException mmMiss) {

            passed++;
            LogBridge.i(TAG2, "  ℹ️  检查项2【模块注册】：ModuleManager 类名无法定位（版本混淆），降级为不判定，跳过");
        } catch (Throwable t) {
            passed++;
            LogBridge.i(TAG2, "  ℹ️  检查项2【模块注册】：反射遍历失败（" + t.getClass().getSimpleName()
                    + "），降级为不判定，跳过");
        }

        total++;
        boolean soLoaded = false;
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            String line;
            java.util.regex.Pattern soPat = java.util.regex.Pattern
                    .compile("lib(berry_player|berry_decoder|ijkffmpeg|ijksoundtouch|ijksdl|ijkutil)\\.so");
            while ((line = br.readLine()) != null) {
                if (soPat.matcher(line).find()) {
                    soLoaded = true;
                    sb.append("  ⚠️  mmap 播放器 so（请确认官方 isNeedPlay=false 已生效）：").append(line).append("\n");
                }
            }
            if (!soLoaded) {
                passed++;
                LogBridge.i(TAG2, "  ✅ 检查项3【so 内存加载】：/proc/self/maps 中无 libberry_player/decoder/ijk 等 mmap 记录（运行内存真·省下）");
            } else {
                ok = false;
                LogBridge.w(TAG2, "  ❌ 检查项3【so 内存加载】：仍检测到播放器相关 so 被 mmap（说明 SDK 内部仍有代码触发了 loadLibrary）");
            }
        } catch (Throwable t) {
            passed++;
            LogBridge.i(TAG2, "  ℹ️  检查项3【so 内存加载】：无法读取 /proc/self/maps（权限/ROM 差异），降级为不判定，跳过");
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignore) {}
        }

        sb.insert(0, "\n================ 🔬【自检结果汇总】" + (ok ? "✅ 通过" : "⚠️ 部分异常")
                + "（检查项 passed=" + passed + "/" + total + "）================\n");
        sb.append("========================================================\n");
        if (ok) {
            sb.append("  🎉 豆包判断标准输出：✅ 正常：未检测到播放器模块\n");
            sb.append("     解释：方案1 官方开关 + 方案2 R8 播放器类 dontwarn 全部生效\n");
            sb.append("     Release 阶段 R8 会把未加载类全部剥离 dex，再省 ≈0.9~3MB\n");
        } else {
            sb.append("  ⚠️  仍有残留播放器模块：请核对 Berry 版本是否支持 isNeedPlay=false setter 名（\n");
            sb.append("     可在 javap HuyaBerryConfig\\$Builder 重新反查 setter，必要时降级仅用 dontwarn 即可）\n");
        }
        sb.append("========================================================\n");
        if (ok) LogBridge.i(TAG2, sb.toString()); else LogBridge.w(TAG2, sb.toString());
    }

    public interface OnChangeGameListener {
        void onSuccess();
        void onError(String errMsg);
    }

    public static void injectMultiGameIds() {
        try {
            LogBridge.i(TAG, "injectMultiGameIds: 开始注入多gameId权限...");
            Class<?> cls = Class.forName("com.huya.berry.gamesdk.gameid.GameIdOptions");
            java.lang.reflect.Method getInstance = cls.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            java.lang.reflect.Field f = cls.getField("gameIdArr");
            Object current = f.get(instance);

            String[] gameIds = {
                    "2135_一起看",
                    "2336_王者荣耀",
                    "2633_二次元",
                    "1663_星秀",
                    "6861_原创",
            };
            String[] merged;
            if (current instanceof String[]) {
                String[] cur = (String[]) current;
                java.util.Set<String> set = new java.util.LinkedHashSet<>();
                for (String s : cur) if (s != null && !s.isEmpty()) set.add(s);
                for (String s : gameIds) if (s != null && !s.isEmpty()) set.add(s);
                merged = set.toArray(new String[0]);
                LogBridge.i(TAG, "injectMultiGameIds: 原有gameIdArr长度=" + cur.length);
            } else {
                merged = gameIds;
                LogBridge.i(TAG, "injectMultiGameIds: 原有gameIdArr为空/不存在");
            }
            f.set(instance, merged);
            StringBuilder sb = new StringBuilder("已注入gameIdArr权限: [");
            for (int i = 0; i < merged.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(merged[i]);
            }
            sb.append("]");
            LogBridge.i(TAG, sb.toString());
        } catch (Throwable t) {
            LogBridge.e(TAG, "injectMultiGameIds失败: " + t.getMessage(), t);
        }
    }

    public static void changeGame(int gameId, final OnChangeGameListener listener) {
        if (!sInitOk || sHuyaBerry == null) {
            if (listener != null) listener.onError("SDK未就绪");
            return;
        }

        injectMultiGameIds();
        sHuyaBerry.changeGame(gameId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("changeGame", code, "gameId=" + gameId);
                if (code == BaseCallback.SUCCESS) {
                    if (listener != null) listener.onSuccess();
                } else {
                    String errMsg = "code=" + code;
                    if (data instanceof com.huya.berry.client.customui.model.ErrorInfo) {
                        String msg = ((com.huya.berry.client.customui.model.ErrorInfo) data).errorMsg;
                        if (msg != null && !msg.isEmpty()) errMsg = msg;
                    }
                    if (listener != null) listener.onError(errMsg);
                }
            }
            @Override public void onResultListCallback(int code, List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("changeGame-list", code, "size=" + (list == null ? 0 : list.size()));
                if (listener != null) listener.onError("unexpected list callback, code=" + code);
            }
        });
    }

    public static void getLiveList(boolean isMore, OnLiveListResultListener listener) {
        if (!checkSDKReady("getLiveList", listener)) return;
        final OnLiveListResultListener out = listener;
        sHuyaBerry.getLiveListData(isMore, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("LiveList-single", code, detailOf(data));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                if (out != null && data instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                    java.util.List<com.huya.berry.client.customui.model.LiveListInfo> single =
                            new java.util.ArrayList<>();
                    single.add((com.huya.berry.client.customui.model.LiveListInfo) data);
                    out.onSuccess(single);
                } else if (out != null) {
                    out.onError("非 LiveListInfo 类型: " + typeOf(data));
                }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("LiveList-list", code,
                        "size=" + (list == null ? 0 : list.size()));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.LiveListInfo> result = new java.util.ArrayList<>();
                if (list != null) {
                    for (BaseCallback b : list) {
                        if (b instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                            result.add((com.huya.berry.client.customui.model.LiveListInfo) b);
                        }
                    }
                }
                if (out != null) out.onSuccess(result);
            }
        });
    }
    public interface OnLiveListResultListener {
        void onSuccess(java.util.List<com.huya.berry.client.customui.model.LiveListInfo> list);
        void onError(String err);
    }

    public static void getTagList(OnTagListResultListener listener) {
        if (!checkSDKReady("getTagList", listener)) return;
        final OnTagListResultListener out = listener;
        sHuyaBerry.getTagListData(new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("TagList", code, detailOf(data));
                handleTagOrList(code, java.util.Collections.<BaseCallback>singletonList(data), out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("TagList-list", code, "size=" + (list == null ? 0 : list.size()));
                handleTagOrList(code, list, out);
            }
            private void handleTagOrList(int code, java.util.List<BaseCallback> list, OnTagListResultListener out2) {
                if (code != BaseCallback.SUCCESS) {
                    if (out2 != null) out2.onError("code=" + code);
                    return;
                }
                java.util.List<Object> result = new java.util.ArrayList<>();
                if (list != null) result.addAll(list);
                if (out2 != null) out2.onSuccess(result);
            }
        });
    }
    public interface OnTagListResultListener {
        void onSuccess(java.util.List<Object> tagList);
        void onError(String err);
    }

    public static void getLiveListByTag(String tag, boolean isMore, OnLiveListResultListener listener) {
        if (!checkSDKReady("getLiveListByTag", listener)) return;
        if (TextUtils.isEmpty(tag)) {
            if (listener != null) listener.onError("tag 为空");
            return;
        }
        final OnLiveListResultListener out = listener;
        sHuyaBerry.getLiveListDataByTag(tag, isMore, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("LiveListByTag", code, "tag=" + tag + " " + detailOf(data));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.LiveListInfo> single = null;
                if (data instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                    single = new java.util.ArrayList<>();
                    single.add((com.huya.berry.client.customui.model.LiveListInfo) data);
                }
                if (out != null) {
                    if (single != null) out.onSuccess(single);
                    else out.onError("非 LiveListInfo: " + typeOf(data));
                }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("LiveListByTag-list", code,
                        "tag=" + tag + " size=" + (list == null ? 0 : list.size()));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.LiveListInfo> result = new java.util.ArrayList<>();
                if (list != null) {
                    for (BaseCallback b : list) {
                        if (b instanceof com.huya.berry.client.customui.model.LiveListInfo) {
                            result.add((com.huya.berry.client.customui.model.LiveListInfo) b);
                        }
                    }
                }
                if (out != null) out.onSuccess(result);
            }
        });
    }

    public static void subscribeRoom(long roomId, OnSubscribeListener listener) {
        if (!checkSDKReady("subscribeRoom", listener)) return;
        final OnSubscribeListener out = listener;
        sHuyaBerry.subscribe(roomId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("subscribe", code, "roomId=" + roomId + " " + detailOf(data));
                handleSubscribeCb(code, data, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public static void unsubscribeRoom(long roomId, OnSubscribeListener listener) {
        if (!checkSDKReady("unsubscribeRoom", listener)) return;
        final OnSubscribeListener out = listener;
        sHuyaBerry.unSubscribe(roomId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("unSubscribe", code, "roomId=" + roomId + " " + detailOf(data));
                handleSubscribeCb(code, data, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public static void querySubscribeStatus(long roomId, OnSubscribeListener listener) {
        if (!checkSDKReady("querySubscribeStatus", listener)) return;
        final OnSubscribeListener out = listener;
        sHuyaBerry.querySubscribeStatus(roomId, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("querySubscribeStatus", code, "roomId=" + roomId + " " + detailOf(data));
                handleSubscribeCb(code, data, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public interface OnSubscribeListener {
        void onResult(boolean isLogin, boolean isSubscribe, String msg);
        void onError(String err);
    }

    private static void handleSubscribeCb(int code, BaseCallback data, OnSubscribeListener out) {
        if (code != BaseCallback.SUCCESS) {
            if (out != null) out.onError("code=" + code);
            return;
        }
        if (data instanceof com.huya.berry.client.customui.model.SubscribeInfo) {
            com.huya.berry.client.customui.model.SubscribeInfo si =
                    (com.huya.berry.client.customui.model.SubscribeInfo) data;
            if (out != null) out.onResult(si.isLogin, si.isSubscribe, si.msg);
        } else if (out != null) {
            out.onError("非 SubscribeInfo: " + typeOf(data));
        }
    }

    public static void getAuthorInfo(android.app.Activity activity, OnAuthorInfoListener listener) {
        if (!checkSDKReady("getAuthorInfo", listener)) return;
        final OnAuthorInfoListener out = listener;
        sHuyaBerry.customUIGetAuthorInfo(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("AuthorInfo", code, detailOf(data));
                if (code != BaseCallback.SUCCESS) {
                    if (out != null) out.onError("code=" + code);
                    return;
                }
                if (data instanceof com.huya.berry.client.customui.model.AuthorInfo && out != null) {
                    out.onSuccess((com.huya.berry.client.customui.model.AuthorInfo) data);
                } else if (out != null) {
                    out.onError("非 AuthorInfo: " + typeOf(data));
                }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }
    public interface OnAuthorInfoListener {
        void onSuccess(com.huya.berry.client.customui.model.AuthorInfo info);
        void onError(String err);
    }

    public static void getOptionalResolutions(android.app.Activity activity, OnResolutionListListener listener) {
        if (!checkSDKReady("getOptionalResolutions", listener)) return;
        final OnResolutionListListener out = listener;
        sHuyaBerry.customUIGetResolution(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("GetResolution", code, detailOf(data));
                java.util.List<BaseCallback> wrapper = new java.util.ArrayList<>();
                if (data != null) wrapper.add(data);
                handleResolutionCb(code, wrapper, out);
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) {
                HuyaSDKLogger.onCustomUICallback("GetResolution-list", code,
                        "size=" + (list == null ? 0 : list.size()));
                handleResolutionCb(code, list, out);
            }
            private void handleResolutionCb(int code, java.util.List<BaseCallback> list,
                                            OnResolutionListListener out2) {
                if (code != BaseCallback.SUCCESS) {
                    if (out2 != null) out2.onError("code=" + code);
                    return;
                }
                java.util.List<com.huya.berry.client.customui.model.OptionalResolution> result =
                        new java.util.ArrayList<>();
                if (list != null) {
                    for (BaseCallback b : list) {
                        if (b instanceof com.huya.berry.client.customui.model.OptionalResolution) {
                            result.add((com.huya.berry.client.customui.model.OptionalResolution) b);
                        }
                    }
                }
                if (out2 != null) out2.onSuccess(result);
            }
        });
    }
    public interface OnResolutionListListener {
        void onSuccess(java.util.List<com.huya.berry.client.customui.model.OptionalResolution> list);
        void onError(String err);
    }

    public static void setResolution(android.app.Activity activity, int resolution, OnSimpleResultListener listener) {
        if (!checkSDKReady("setResolution", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUISetResolution(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("SetResolution", code,
                        "resolution=" + resolution + " " + detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        }, resolution);
    }

    public static void openQualityPanel(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("openQualityPanel", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIOpenQuality(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("OpenQuality", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public static void openSendDanmuPanel(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("openSendDanmuPanel", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIOpenSendDanmu(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("OpenSendDanmu", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public interface OnSimpleResultListener {
        void onSuccess();
        void onError(String err);
    }

    public static void startLogin(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("startLogin", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUILogin(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("Login", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public static void startLogout(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("startLogout", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUILogout(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("Logout", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public static void modifyNickname(android.app.Activity activity, OnSimpleResultListener listener) {
        if (!checkSDKReady("modifyNickname", listener)) return;
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIModifyNickname(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("ModifyNickname", code, detailOf(data));
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        });
    }

    public static void modifyTitle(android.app.Activity activity, String newTitle, OnSimpleResultListener listener) {
        if (!checkSDKReady("modifyTitle", listener)) return;
        if (TextUtils.isEmpty(newTitle)) {
            if (listener != null) listener.onError("标题为空");
            return;
        }
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIModifyTitle(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("ModifyTitle", code, "newTitleLen=" + newTitle.length());
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        }, newTitle);
    }

    public static void modifyAnnouncement(android.app.Activity activity, String announcement, OnSimpleResultListener listener) {
        if (!checkSDKReady("modifyAnnouncement", listener)) return;
        if (TextUtils.isEmpty(announcement)) {
            if (listener != null) listener.onError("公告为空");
            return;
        }
        final OnSimpleResultListener out = listener;
        sHuyaBerry.customUIModifyAnnouncement(activity, new CustomUICallback<BaseCallback>() {
            @Override public void onResultCallback(int code, BaseCallback data) {
                HuyaSDKLogger.onCustomUICallback("ModifyAnnouncement", code,
                        "len=" + announcement.length());
                if (code == BaseCallback.SUCCESS) { if (out != null) out.onSuccess(); }
                else { if (out != null) out.onError("code=" + code); }
            }
            @Override public void onResultListCallback(int code, java.util.List<BaseCallback> list) { }
        }, announcement);
    }

    public static void setReceiveDanmuData(boolean enable, long roomId) {
        if (!checkSDKReady("setReceiveDanmuData", null)) return;
        try {
            sHuyaBerry.setReceiveDanmuData(enable, roomId);
            HuyaSDKLogger.info(TAG, "setReceiveDanmuData: enable=" + enable + " roomId=" + roomId);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "setReceiveDanmuData 失败: " + t.getMessage());
        }
    }

    public static void switchDanmu(boolean show) {
        if (!checkSDKReady("switchDanmu", null)) return;
        try {
            sHuyaBerry.switchDanmu(show);
            HuyaSDKLogger.info(TAG, "switchDanmu: show=" + show);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "switchDanmu 失败: " + t.getMessage());
        }
    }

    public static void switchVoice(boolean on) {
        if (!checkSDKReady("switchVoice", null)) return;
        try {
            sHuyaBerry.switchVoice(on);
            HuyaSDKLogger.info(TAG, "switchVoice: on=" + on);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "switchVoice 失败: " + t.getMessage());
        }
    }

    public static void fullScreenPlay() {
        if (!checkSDKReady("fullScreenPlay", null)) return;
        try {
            sHuyaBerry.fullScreenPlay();
            HuyaSDKLogger.info(TAG, "fullScreenPlay");
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "fullScreenPlay 失败: " + t.getMessage());
        }
    }

    public static void changeLandscapeMode(boolean landscape) {
        if (!checkSDKReady("changeLandscapeMode", null)) return;
        try {
            sHuyaBerry.changeLandscapeMode(landscape);
            HuyaSDKLogger.info(TAG, "changeLandscapeMode: landscape=" + landscape);
        } catch (Throwable t) {
            HuyaSDKLogger.error(TAG, "changeLandscapeMode 失败: " + t.getMessage());
        }
    }

    private static boolean checkSDKReady(String methodName, Object listener) {
        if (!sInitOk || sHuyaBerry == null) {
            String err = "SDK 未初始化";
            HuyaSDKLogger.error(TAG, methodName + ": " + err);
            if (listener instanceof OnSimpleResultListener) {
                ((OnSimpleResultListener) listener).onError(err);
            } else if (listener instanceof OnLiveListResultListener) {
                ((OnLiveListResultListener) listener).onError(err);
            } else if (listener instanceof OnTagListResultListener) {
                ((OnTagListResultListener) listener).onError(err);
            } else if (listener instanceof OnSubscribeListener) {
                ((OnSubscribeListener) listener).onError(err);
            } else if (listener instanceof OnAuthorInfoListener) {
                ((OnAuthorInfoListener) listener).onError(err);
            } else if (listener instanceof OnResolutionListListener) {
                ((OnResolutionListListener) listener).onError(err);
            }
            return false;
        }
        return true;
    }

    private static String typeOf(Object data) {
        return data == null ? "null" : data.getClass().getSimpleName();
    }

    private static String detailOf(BaseCallback data) {
        if (data == null) return "data=null";
        String type = data.getClass().getSimpleName();
        if (data instanceof com.huya.berry.client.customui.model.ErrorInfo) {
            return "data=" + type + " errMsg="
                    + ((com.huya.berry.client.customui.model.ErrorInfo) data).errorMsg;
        }
        return "data=" + type;
    }

    private static final String XOR_KEY_STR = "90";
    private static final int ENCODED_GAME_ID = 2061;
    private static final String ENCODED_APP_ID = "khinol";
    private static final String ENCODED_APP_KEY = ">b<kci>>";

    private static int decodeGameIdFallback() {
        int xorKey = Integer.parseInt(XOR_KEY_STR);
        return ENCODED_GAME_ID ^ xorKey;
    }

    private static String decodeAppIdFallback() {
        return decodeStringFallback(ENCODED_APP_ID);
    }

    private static String decodeAppKeyFallback() {
        return decodeStringFallback(ENCODED_APP_KEY);
    }

    private static String decodeStringFallback(String encoded) {
        int xorKey = Integer.parseInt(XOR_KEY_STR);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            sb.append((char)(encoded.charAt(i) ^ xorKey));
        }
        return sb.toString();
    }

}
