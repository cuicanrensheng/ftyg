package com.tv.live.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tv.live.BuildConfig;
import com.tv.live.MainActivity;
import com.tv.live.MyApplication;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class LogBridge {
    private LogBridge() {}

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
        forward(tag, msg, LogCollector.TYPE_DEBUG);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        // 卡顿优化：d 级日志量最大（HuyaSDKParser 数十处），release 下不进 LogCollector，
        // info/warn/error/playback 等仍全量收集（9527 /api/logs 依赖它们）
        if (com.tv.live.BuildConfig.IS_DEBUG) {
            forward(tag, msg, LogCollector.TYPE_DEBUG);
        }
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        forward(tag, msg, LogCollector.TYPE_INFO);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        forward(tag, msg, LogCollector.TYPE_WARN);
    }

    public static void w(String tag, String msg, Throwable tr) {
        Log.w(tag, msg, tr);
        forward(tag, msg + (tr != null ? " : " + tr.getMessage() : ""), LogCollector.TYPE_WARN);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        forward(tag, msg, LogCollector.TYPE_ERROR);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        forward(tag, msg + (tr != null ? " : " + tr.getMessage() : ""), LogCollector.TYPE_ERROR);
    }

    public static int println(int priority, String tag, String msg) {
        int r = Log.println(priority, tag, msg);
        forward(tag, msg, LogCollector.TYPE_INFO);
        return r;
    }

    private static void forward(String tag, String msg, String type) {
        try {

            LogCollector.getInstance().addLogNoReport(tag, msg, type);
        } catch (Throwable ignored) {}
    }

    public static class LogCollector {
        private static volatile LogCollector sInstance;
        private final List<String> logs;
        private final List<LogEntry> logEntries;
        private final SimpleDateFormat sdf;
        private final List<LogListener> listeners;
        private final List<DeviceInfoProvider> deviceInfoProviders;

        // ===== 网络请求统计（供 /api/perf 返回）=====
        private long netRequestCount = 0;
        private long netSuccessCount = 0;
        private long netErrorCount = 0;
        private long netLatencySumMs = 0;
        private long netMaxLatencyMs = 0;
        // ===== 播放性能统计（供 /api/perf 返回）=====
        private long playCount = 0;
        private long stallCount = 0;
        private long stallSumMs = 0;
        private long maxStallMs = 0;
        private long decodeErrCount = 0;


        public static final String DIVIDER_TOKEN = "###DIVIDER###";

        public static final String TYPE_INFO = "info";
        public static final String TYPE_WARN = "warn";
        public static final String TYPE_ERROR = "error";
        public static final String TYPE_DEBUG = "debug";
        public static final String TYPE_CRASH = "crash";
        public static final String TYPE_NETWORK = "network";
        public static final String TYPE_PARSE = "parse";
        public static final String TYPE_PLAYBACK = "playback";
        public static final String TYPE_OPERATION = "operation";

        private static final String[] NON_REPORTABLE_KEYWORDS = {
            "success", "GetLivingInfo", "GetLivingInfoRsp",
            "SDK-Auk", "BerryEvent", "CustomUI",
            "onResultCallback", "playUrl", "stream",

            "检测到调试", "检测到可疑环境", "检测到模拟器",
            "检测到usb调试", "检测到模拟位置", "检测到root",
            "应用可能被逆向", "反调试检测", "安全检测发现风险",
            "安全检查未通过", "降级运行", "篡改",

            "连接超时", "超时", "timeout",
            "404 not found", "http 404", "失败: http"
        };

        public interface LogListener {
            void onLogAdded(LogEntry entry);
            void onLogCleared();
        }

        public interface DeviceInfoProvider {
            String getDeviceId();
            String getDeviceName();
            String getDeviceModel();
            String getAppVersion();
        }

        public static class LogEntry {
            public final long timestamp;
            public final String time;
            public final String tag;
            public final String message;
            public final String type;
            public final String deviceId;
            public final String deviceName;
            public final String deviceModel;
            public final String appVersion;

            public LogEntry(long timestamp, String time, String tag, String message, String type,
                            String deviceId, String deviceName, String deviceModel, String appVersion) {
                this.timestamp = timestamp;
                this.time = time;
                this.tag = tag;
                this.message = message;
                this.type = type;
                this.deviceId = deviceId;
                this.deviceName = deviceName;
                this.deviceModel = deviceModel;
                this.appVersion = appVersion;
            }
        }

        private LogCollector() {
            logs = new ArrayList<>();
            logEntries = new ArrayList<>();
            sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            listeners = new CopyOnWriteArrayList<>();
            deviceInfoProviders = new CopyOnWriteArrayList<>();
        }

        public static LogCollector getInstance() {
            if (sInstance == null) {
                synchronized (LogCollector.class) {
                    if (sInstance == null) {
                        sInstance = new LogCollector();
                    }
                }
            }
            return sInstance;
        }

        // 获取网络请求统计
        public java.util.Map<String, Object> getNetStats() {
            java.util.Map<String, Object> stats = new java.util.HashMap<>();
            stats.put("requestCount", netRequestCount);
            stats.put("successCount", netSuccessCount);
            stats.put("errorCount", netErrorCount);
            stats.put("avgLatencyMs", netRequestCount > 0 ? (netLatencySumMs / netRequestCount) : 0);
            stats.put("maxLatencyMs", netMaxLatencyMs);
            stats.put("successRate", netRequestCount > 0 ? (netSuccessCount * 100.0 / netRequestCount) : 0);
            return stats;
        }

        // 获取播放性能统计
        public java.util.Map<String, Object> getPlayStats() {
            java.util.Map<String, Object> stats = new java.util.HashMap<>();
            stats.put("playCount", playCount);
            stats.put("stallCount", stallCount);
            stats.put("avgStallMs", stallCount > 0 ? (stallSumMs / stallCount) : 0);
            stats.put("maxStallMs", maxStallMs);
            stats.put("decodeErrCount", decodeErrCount);
            return stats;
        }

        public void registerDeviceInfoProvider(DeviceInfoProvider provider) {
            deviceInfoProviders.add(provider);
        }

        public void unregisterDeviceInfoProvider(DeviceInfoProvider provider) {
            deviceInfoProviders.remove(provider);
        }

        public void addLogListener(LogListener listener) {
            if (listener != null && !listeners.contains(listener)) {
                listeners.add(listener);
            }
        }

        public void removeLogListener(LogListener listener) {
            listeners.remove(listener);
        }

        private LogEntry createLogEntry(String tag, String msg, String type, long timestamp) {
            String time = sdf.format(new Date(timestamp));
            String deviceId = "unknown";
            String deviceName = "Unknown Device";
            String deviceModel = "Unknown";
            String appVersion = "0.0.0";

            for (DeviceInfoProvider provider : deviceInfoProviders) {
                try {
                    String id = provider.getDeviceId();
                    if (!TextUtils.isEmpty(id)) deviceId = id;
                    String name = provider.getDeviceName();
                    if (!TextUtils.isEmpty(name)) deviceName = name;
                    String model = provider.getDeviceModel();
                    if (!TextUtils.isEmpty(model)) deviceModel = model;
                    String version = provider.getAppVersion();
                    if (!TextUtils.isEmpty(version)) appVersion = version;
                } catch (Exception ignored) {}
            }

            return new LogEntry(timestamp, time, tag, msg, type, deviceId, deviceName, deviceModel, appVersion);
        }

        private void notifyListeners(LogEntry entry) {
            for (LogListener listener : listeners) {
                try {
                    listener.onLogAdded(entry);
                } catch (Exception ignored) {}
            }
        }

        public void addLog(String tag, String msg) {
            addLog(tag, msg, TYPE_INFO);
        }

        public void addLog(String tag, String msg, String type) {
            // 直调 LogCollector 的调用方**没有**写 logcat，由 writer 负责 echo 一次（保证这类日志仍可见）
            addLogInternal(tag, msg, type, true, true);
        }

        public void addLogNoReport(String tag, String msg, String type) {
            // 【2026-10-05 日志去重】本入口**只**被 LogBridge.forward() 调用；而 LogBridge.v/d/i/w/e/println
            // 在调用线程已经先 android.util.Log.x() 写过一次 logcat，所以 writer 必须**跳过 echo**，
            // 否则每条 app 日志会在 logcat 里出现两份（实测 debug 包 76% 的行都有 0~20ms 内的重复副本）。
            // 注意：debug 包才可见——release 包 proguard 有
            //   -assumenosideeffects class android.util.Log { v/d/i/w/e/println }
            // 会把两处 Log 调用全部在编译期删掉。
            addLogInternal(tag, msg, type, false, false);
        }

        /** 单线程写队列：addLog 的重活（格式化/锁/listeners/logd）全部移出调用线程（含主线程），防播放卡顿 */
        private final java.util.concurrent.ExecutorService sLogWriter =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "LogCollector-Writer");
                    t.setDaemon(true);
                    return t;
                });

        /**
         * @param autoReport   error/crash 是否自动上报
         * @param echoToLogcat writer 是否要把这条再写一次 logcat。
         *                     来自 {@link LogBridge} 的条目传 false（调用方已写过）；直调 LogCollector 的传 true。
         */
        private void addLogInternal(String tag, String msg, String type, boolean autoReport, boolean echoToLogcat) {
            if (msg != null && msg.length() > 500) {
                msg = msg.substring(0, 500) + "...[已截断]";
            }
            // 【2026-10-13 日志脱敏】分工明确：
            //   fMsg    → 脱敏后正文，进 9527 的 logs / logEntries（【2026-10-22 I1-02】GET /api/logs
            //            已加鉴权：回环来源豁免，非回环须 ?token= 有效 token；WS 须 subscribe 过才收广播）
            //   fMsgRaw → 原文，仅用于 logcat echo 与 ExceptionReporter 上报（都走授权通道）
            final String fTag = tag, fType = type;
            final String fMsgRaw = msg;
            final String fMsg = LogRedactor.redact(msg);
            final boolean fReport = autoReport;
            final boolean fEcho = echoToLogcat;
            final long ts = System.currentTimeMillis(); // 提交时打时间戳，保证异步后仍单调
            sLogWriter.execute(() -> addLogInternalOnWriter(fTag, fMsg, fType, fReport, fEcho, ts, fMsgRaw));
        }

        private void addLogInternalOnWriter(String tag, String msg, String type, boolean autoReport,
                                            boolean echoToLogcat, long timestamp, String rawMsg) {
            // msg = 已脱敏（展示/落盘）；rawMsg = 原文（logcat echo 与异常上报）
            LogEntry entry = createLogEntry(tag, msg, type, timestamp);
            String line;
            if (!TextUtils.isEmpty(tag)) {
                line = entry.time + " 【" + tag + "】 " + msg;
            } else {
                line = entry.time + " " + msg;
            }
            synchronized (logs) {
                logs.add(0, line);
                if (logs.size() > 2000) {
                    logs.remove(logs.size() - 1);
                }
            }
            synchronized (logEntries) {
                logEntries.add(0, entry);
                if (logEntries.size() > 2000) {
                    logEntries.remove(logEntries.size() - 1);
                }
            }
            notifyListeners(entry);

            // ===== 网络请求统计 =====
            if (TYPE_NETWORK.equals(type)) {
                netRequestCount++;
                String lowerMsg = msg != null ? msg.toLowerCase() : "";
                // 判断成功/失败
                boolean isError = lowerMsg.contains("失败") || lowerMsg.contains("error")
                        || lowerMsg.contains("超时") || lowerMsg.contains("timeout")
                        || lowerMsg.contains("404") || lowerMsg.contains("500")
                        || lowerMsg.contains("502") || lowerMsg.contains("503");
                if (isError) {
                    netErrorCount++;
                } else {
                    netSuccessCount++;
                }
                // 尝试提取延迟（匹配 "xxxms" 或 "耗时=xxx" 等格式）
                try {
                    java.util.regex.Matcher latencyMatcher = java.util.regex.Pattern
                            .compile("(?:耗时|延迟|time|latency)[^\\d]*(\\d+)\\s*ms", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(msg != null ? msg : "");
                    if (latencyMatcher.find()) {
                        long latency = Long.parseLong(latencyMatcher.group(1));
                        netLatencySumMs += latency;
                        if (latency > netMaxLatencyMs) netMaxLatencyMs = latency;
                    }
                } catch (Exception ignored) {}
            }

            // ===== 播放性能统计 =====
            if (TYPE_PLAYBACK.equals(type)) {
                playCount++;
                String lowerMsg = msg != null ? msg.toLowerCase() : "";
                // 卡顿统计
                if (lowerMsg.contains("卡顿") || lowerMsg.contains("stall") || lowerMsg.contains("buffering")) {
                    stallCount++;
                    // 尝试提取卡顿时长
                    try {
                        java.util.regex.Matcher stallMatcher = java.util.regex.Pattern
                                .compile("(?:卡顿|stall|buffering)[^\\d]*(\\d+)\\s*ms", java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(msg != null ? msg : "");
                        if (stallMatcher.find()) {
                            long stallMs = Long.parseLong(stallMatcher.group(1));
                            stallSumMs += stallMs;
                            if (stallMs > maxStallMs) maxStallMs = stallMs;
                        }
                    } catch (Exception ignored) {}
                }
                // 解码错误统计
                if (lowerMsg.contains("解码错误") || lowerMsg.contains("decode error") || lowerMsg.contains("codec error")) {
                    decodeErrCount++;
                }
            }

            if (autoReport && (TYPE_ERROR.equals(type) || TYPE_CRASH.equals(type))) {
                // 【2026-10-13 脱敏】去重判定与上报都用原文，避免脱敏干扰服务端诊断
                final String rMsg = rawMsg != null ? rawMsg : msg;
                if (shouldReportError(rMsg)) {
                    try {
                        ExceptionReporter.reportError(
                                tag != null ? tag : "TVLive",
                                rMsg != null ? rMsg : "Unknown error",
                                new RuntimeException(rMsg != null ? rMsg : "Unknown error"));
                    } catch (Throwable ignored) {}
                } else {

                    android.util.Log.w("LogCollector", "日志已过滤不记录: " +
                        (rMsg != null ? rMsg.substring(0, Math.min(100, rMsg.length())) : ""));
                }
            }

            // 【2026-10-05 日志去重】来自 LogBridge 的条目在调用线程已经 Log.x() 写过 logcat，
            // 这里必须跳过 echo，否则同一条日志在 logcat 里出现两份（实测重复率 76%）。
            // 直调 LogCollector 的条目（autoReport=true 路径）仍照常 echo，保证它们不会从 logcat 消失。
            if (!echoToLogcat) return;

            try {
                // 【2026-10-13 脱敏】logcat 走原文：需要 adb 授权才能读，
                // 且 release 包的 android.util.Log 已被 proguard 整体剥离，不存在额外的暴露面。
                // 这样 debug 包调试时仍能看清完整特征串。
                final String echoMsg = rawMsg != null ? rawMsg : msg;
                String logTag = TextUtils.isEmpty(tag) ? "TVLive" : tag;
                switch (type) {
                    case TYPE_ERROR:
                        android.util.Log.e(logTag, echoMsg);
                        break;
                    case TYPE_WARN:
                        android.util.Log.w(logTag, echoMsg);
                        break;
                    case TYPE_DEBUG:
                        android.util.Log.d(logTag, echoMsg);
                        break;
                    case TYPE_CRASH:
                        android.util.Log.e(logTag, "CRASH: " + echoMsg);
                        break;
                    case TYPE_NETWORK:
                        android.util.Log.i(logTag, "[NET] " + echoMsg);
                        break;
                    case TYPE_PARSE:
                        android.util.Log.i(logTag, "[PARSE] " + echoMsg);
                        break;
                    case TYPE_PLAYBACK:
                        android.util.Log.i(logTag, "[PLAY] " + echoMsg);
                        break;
                    case TYPE_OPERATION:
                        android.util.Log.i(logTag, "[OP] " + echoMsg);
                        break;
                    default:
                        android.util.Log.i(logTag, echoMsg);
                        break;
                }
            } catch (Throwable ignored) {}
        }

        public void info(String tag, String msg) { addLog(tag, msg, TYPE_INFO); }
        public void warn(String tag, String msg) { addLog(tag, msg, TYPE_WARN); }
        public void error(String tag, String msg) { addLog(tag, msg, TYPE_ERROR); }

        // 【2026-10-15 审计修复 · 清理反射残留】原此处为「JCE 反射动态白名单」采集打点：
        //   sTraceEnabled / setTraceEnabled / isTraceEnabled / trace(String) / traceObj(Object)。
        // 该机制的调用方是构建期 ASM 注入（_jarstub/JceJarPatcher.java），注入目标为
        // com.duowan.jce.wup.BasicClassTypeUtil 与 com.duowan.taf.jce.JceInputStream —— 都是
        // **虎牙 SDK 的 JCE 类**，而虎牙 SDK 已于 2026-10-08 整体拆除。
        // 实测：四个成员在 java / assets / cpp / xml 中**零调用者**，proguard 里也已无任何
        // JCE/duowan 相关 -keep 规则（原注释所称「白名单已固化」已过时）→ 纯死代码，删除。

        public void error(String tag, String msg, Throwable throwable) {
            addLog(tag, msg + (throwable != null ? ": " + throwable.getMessage() : ""), TYPE_ERROR);
            if (throwable != null) {
                ExceptionReporter.reportError(tag, msg, throwable);
            }
        }
        public void debug(String tag, String msg) { addLog(tag, msg, TYPE_DEBUG); }
        public void crash(String tag, String msg) { addLog(tag, msg, TYPE_CRASH); }
        public void crash(String tag, String msg, Throwable throwable) {
            addLog(tag, msg + (throwable != null ? ": " + throwable.getMessage() : ""), TYPE_CRASH);
            if (throwable != null) {
                ExceptionReporter.report(tag, throwable);
            }
        }
        public void network(String tag, String msg) {

            if (BuildConfig.IS_DEBUG) {
                addLog(tag, msg, TYPE_NETWORK);
            }
        }
        public void parse(String tag, String msg) {

            if (BuildConfig.IS_DEBUG) {
                addLog(tag, msg, TYPE_PARSE);
            }
        }
        public void playback(String tag, String msg) {

            if (BuildConfig.IS_DEBUG) {
                addLog(tag, msg, TYPE_PLAYBACK);
            }
        }
        public void operation(String tag, String msg) { addLog(tag, msg, TYPE_OPERATION); }

        public void trackPageView(String pageName) {
            operation("PageView", pageName);
        }

        public void trackFeatureUse(String featureName, String detail) {
            operation("FeatureUse", featureName + (detail != null ? ": " + detail : ""));
        }

        public void trackEvent(String eventName, java.util.Map<String, String> params) {
            operation("TrackEvent", eventName);
        }

        public void trackPlayStart(String channelName) {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("channel", channelName != null ? channelName : "unknown");
            params.put("action", "start");
            trackEvent("playback", params);
        }

        public void trackPlayEnd(String channelName, long durationMs) {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("channel", channelName != null ? channelName : "unknown");
            params.put("action", "end");
            params.put("duration_ms", String.valueOf(durationMs));
            trackEvent("playback", params);
        }

        public void trackChannelSelect(String channelName, int position) {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("channel", channelName != null ? channelName : "unknown");
            params.put("position", String.valueOf(position));
            trackEvent("channel_select", params);
        }

        public void trackSearch(String keyword, int resultCount) {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("keyword", keyword != null ? keyword : "");
            params.put("result_count", String.valueOf(resultCount));
            trackEvent("search", params);
        }

        public void addDivider() {
            String time = sdf.format(new Date());
            synchronized (logs) {
                logs.add(0, time + " " + DIVIDER_TOKEN);
                if (logs.size() > 2000) {
                    logs.remove(logs.size() - 1);
                }
            }
        }

        public String getAllLogs() {
            StringBuilder sb = new StringBuilder();
            synchronized (logs) {
                for (String log : logs) {
                    sb.append(log).append("\n");
                }
            }
            return sb.toString();
        }

        public List<LogEntry> getStructuredLogs() {
            synchronized (logEntries) {
                return new ArrayList<>(logEntries);
            }
        }

        public void clear() {
            synchronized (logs) {
                logs.clear();
            }
            synchronized (logEntries) {
                logEntries.clear();
            }
            for (LogListener listener : listeners) {
                try {
                    listener.onLogCleared();
                } catch (Exception ignored) {}
            }
        }

        private static boolean shouldReportError(String msg) {
            if (msg == null || msg.isEmpty()) return false;

            String lowerMsg = msg.toLowerCase(Locale.ROOT);

            for (String keyword : NON_REPORTABLE_KEYWORDS) {
                if (lowerMsg.contains(keyword.toLowerCase(Locale.ROOT))) {

                    if (keyword.equals("success")) {
                        if (lowerMsg.contains("error") || lowerMsg.contains("fail") ||
                            lowerMsg.contains("exception") || lowerMsg.contains("crash")) {
                            return true;
                        }
                    }

                    return false;
                }
            }

            return true;
        }
    }

    public static class LogServer {
        private static final String TAG = "LogServer";
        private static final int DEFAULT_PORT = 9527;
        private static final String PROTOCOL_VERSION = "1.0";

        private static final int MAX_CLIENTS = 5;
        private static volatile LogServer sInstance;
        private final Context context;
        private final OkHttpClient httpClient;
        private final Gson gson;
        private final CopyOnWriteArrayList<WebSocket> webSockets;
        /** 【2026-10-22 I1-02】已订阅（subscribe 消息 token 校验通过）的 WS 连接：
         *  只有它们才收日志广播 / 待发日志 / 可调 get_logs·clear_logs，未订阅连接零推送 */
        private final CopyOnWriteArrayList<WebSocket> subscribedSockets;
        private final List<Map<String, Object>> pendingLogs;
        private volatile boolean isRunning;
        private Thread serverThread;
        private java.net.ServerSocket serverSocket;
        private ExecutorService clientExecutor;
        private int port;
        private String deviceId;
        private String deviceName;
        private String deviceModel;
        private String appVersion;
        private String token;
        private final SimpleDateFormat sdf;

        private LogServer(Context context) {
            this.context = context.getApplicationContext();
            this.httpClient = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            this.gson = new GsonBuilder().serializeNulls().create();
            this.webSockets = new CopyOnWriteArrayList<>();
            this.subscribedSockets = new CopyOnWriteArrayList<>();
            this.pendingLogs = new ArrayList<>();
            this.isRunning = false;
            this.port = DEFAULT_PORT;
            this.sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            initDeviceInfo();
            generateToken();
            setupLogListener();
        }

        public static LogServer getInstance(Context context) {
            if (sInstance == null) {
                synchronized (LogServer.class) {
                    if (sInstance == null) {
                        sInstance = new LogServer(context);
                    }
                }
            }
            return sInstance;
        }

        private void initDeviceInfo() {
            try {
                deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (TextUtils.isEmpty(deviceId)) {
                    deviceId = "unknown_" + Build.SERIAL;
                }
                deviceModel = Build.MODEL;
                deviceName = Build.MANUFACTURER + " " + Build.MODEL;
                try {
                    PackageManager pm = context.getPackageManager();
                    PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
                    long vc = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi);
                    appVersion = pi.versionName + " (" + vc + ")";
                } catch (Exception e) {
                    appVersion = "unknown";
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "Failed to init device info", e);
                deviceId = "unknown";
                deviceName = "Unknown Device";
                deviceModel = "Unknown";
                appVersion = "0.0.0";
            }
        }

        private void generateToken() {
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            token = sb.toString();
        }

        private void setupLogListener() {
            LogCollector.getInstance().registerDeviceInfoProvider(new LogCollector.DeviceInfoProvider() {
                @Override
                public String getDeviceId() { return deviceId; }
                @Override
                public String getDeviceName() { return deviceName; }
                @Override
                public String getDeviceModel() { return deviceModel; }
                @Override
                public String getAppVersion() { return appVersion; }
            });

            LogCollector.getInstance().addLogListener(new LogCollector.LogListener() {
                @Override
                public void onLogAdded(LogCollector.LogEntry entry) {
                    broadcastLog(entry);
                }

                @Override
                public void onLogCleared() {
                    broadcastLogCleared();
                }
            });
        }

        public void start() {
            if (isRunning) {

                if (webSockets.size() > MAX_CLIENTS) {
                    LogBridge.w(TAG, "Clearing " + webSockets.size() + " stale connections (max " + MAX_CLIENTS + ")");
                    for (WebSocket ws : webSockets) {
                        try {
                            ws.close(1000, "Stale cleanup");
                        } catch (Exception ignored) {}
                    }
                    webSockets.clear();
                }
                return;
            }

            for (WebSocket ws : webSockets) {
                try {
                    ws.close(1000, "Restarting");
                } catch (Exception ignored) {}
            }
            webSockets.clear();

            isRunning = true;
            clientExecutor = Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "LogServer-Client");
                t.setDaemon(true);
                return t;
            });
            serverThread = new Thread(() -> {
                try {
                    startHttpServer();
                } catch (Exception e) {
                    LogBridge.e(TAG, "Failed to start LogServer", e);
                    isRunning = false;
                }
            }, "LogServer");
            serverThread.setDaemon(true);
            serverThread.start();
            LogBridge.i(TAG, "LogServer starting on port " + port + " (cleared " + webSockets.size() + " old connections)");

            startConnectionCleaner();
        }

        private void startConnectionCleaner() {
            Thread cleaner = new Thread(() -> {
                while (isRunning) {
                    try {
                        Thread.sleep(30000);

                        while (webSockets.size() > MAX_CLIENTS) {
                            WebSocket oldest = webSockets.get(0);
                            try {
                                oldest.close(1000, "Cleanup");
                            } catch (Exception ignored) {}
                            webSockets.remove(0);
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
            }, "LogServer-Cleaner");
            cleaner.setDaemon(true);
            cleaner.start();
        }

        public void stop() {
            isRunning = false;
            for (WebSocket ws : webSockets) {
                try {
                    ws.close(1000, "Server stopped");
                } catch (Exception ignored) {}
            }
            webSockets.clear();
            subscribedSockets.clear();
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (Exception ignored) {}
            serverSocket = null;
            if (clientExecutor != null) {
                clientExecutor.shutdownNow();
                clientExecutor = null;
            }
            LogBridge.i(TAG, "LogServer stopped");
        }

        private void startHttpServer() throws IOException {
            serverSocket = new java.net.ServerSocket(port);
            serverSocket.setReuseAddress(true);
            LogBridge.i(TAG, "HTTP Server listening on port " + port);

            // 【2026-10-22 I1-11】连续 accept 失败计数：退避 100ms→500ms→1s 封顶。
            //   accept 持续异常（fd 耗尽/socket 异常态）时，无退避的空转会瞬间刷爆
            //   2000 条环形缓冲，把真正排障的日志冲掉。
            int acceptFailStreak = 0;

            while (isRunning) {
                try {
                    java.net.Socket clientSocket = serverSocket.accept();
                    if (clientExecutor != null) {
                        clientExecutor.execute(() -> handleClient(clientSocket));
                    } else {
                        clientSocket.close();
                    }
                    acceptFailStreak = 0;   // 成功即复位
                } catch (IOException e) {
                    if (isRunning) {
                        acceptFailStreak++;
                        long backoffMs = acceptFailStreak <= 1 ? 100L
                                : (acceptFailStreak == 2 ? 500L : 1000L);
                        LogBridge.e(TAG, "Error accepting client（连续第 " + acceptFailStreak
                                + " 次，退避 " + backoffMs + "ms）", e);
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        private void handleClient(java.net.Socket clientSocket) {
            try {
                java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
                java.io.OutputStream out = clientSocket.getOutputStream();

                StringBuilder request = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    request.append(line).append("\r\n");
                }

                String requestStr = request.toString();
                if (requestStr.contains("Upgrade: websocket") || requestStr.contains("upgrade: websocket")) {
                    handleWebSocketUpgrade(clientSocket, requestStr, out);
                } else if (requestStr.startsWith("GET")) {
                    handleHttpRequest(clientSocket, requestStr, out);
                } else if (requestStr.startsWith("POST")) {
                    handleHttpPost(clientSocket, requestStr, in, out);
                } else {
                    sendHttpResponse(out, "HTTP/1.0 400 Bad Request", "text/plain", "Bad Request");
                    clientSocket.close();
                }
            } catch (Exception e) {
                // 【修复日志刷屏】Broken pipe / Connection reset 是轮询断开连接时的正常现象，
                // 改成 debug 级避免形成死循环（请求→断开→报错→记录→下次请求又拉到）
                String errMsg = e.getMessage() == null ? "" : e.getMessage();
                if (errMsg.contains("Broken pipe") || errMsg.contains("Connection reset")) {
                    LogBridge.d(TAG, "Error handling client: " + errMsg);
                } else {
                    LogBridge.e(TAG, "Error handling client", e);
                }
                try {
                    clientSocket.close();
                } catch (Exception ignored) {}
            }
        }

        /**
         * 【2026-10-20 新增 /api/cache】各缓存目录占用明细。
         *
         * <p>起因：电视端「应用存储」显示 40MB，但应用自报的巡检只有 15MB，
         * 差值怀疑在 {@code app_webview}（Chromium WebView 数据目录）——
         * 它在 {@code dataDir} 下，<b>既不在 filesDir 也不在 cacheDir</b>，
         * 所以既没被 {@code performFullInspection} 统计、也没被及时清理。
         * 本接口把每个目录单独列出来，避免再猜。
         *
         * <p>返回值除 {@code dirs}（目录占用）外，另有 {@code clearables}
         * （可清理项 + 当前占用），供电脑端日志工具渲染勾选框。
         */
        /**
         * 【2026-10-20 新增】可清理缓存项白名单：id → 显示名。
         *
         * <p>{@code cleanCache} 的 {@code target} 取值与此一一对应，两边必须同步改。
         * 只收「删掉能自动重建」的目录；databases（播放历史/收藏）、shared_prefs（设置）、
         * filesDir 下各源 M3U 落地缓存（renyibo_vod.m3u 等业务数据）、filesDir/boot_logs.txt
         * （开机日志，用户还要看）一律不在白名单内。
         */
        private static final String[][] CLEARABLE_CACHE_ITEMS = {
                {"channel_logo", "台标图片缓存"},
                {"tv_cache", "节目单 / 列表缓存"},
                {"webview", "WebView 可再生缓存"},
                {"code_cache", "ART 编译缓存"},
        };

        /**
         * WebView（{@code app_webview}）下允许删除的子路径 —— 只列纯可再生缓存，
         * 不碰 Cookies / Local Storage / IndexedDB / Service Worker 的注册与脚本，
         * 避免把登录态或离线数据清掉。
         */
        private static final String[] WEBVIEW_CACHE_SUBPATHS = {
                "Default/HTTP Cache",
                "Default/Code Cache",
                "Default/GPUCache",
                "Default/Service Worker/CacheStorage",
                "Cache",
                "Code Cache",
                "GPUCache",
        };

        /**
         * 【2026-10-20 新增】清理缓存。
         *
         * <p>供电脑端日志工具（log-monitor）调用，避免没有 root 时只能干瞪眼。
         * <b>只删可再生成的缓存</b>，不碰数据库与用户数据。
         *
         * @param target 单个 id、逗号分隔的多个 id，或 {@code all}（= 白名单全部）
         */
        private Map<String, Object> cleanCache(Context ctx, String target) {
            Map<String, Object> r = new LinkedHashMap<>();
            long freed = 0L;
            int files = 0;
            List<String> done = new ArrayList<>();
            try {
                File cacheDir = ctx.getCacheDir();
                File dataDir = new File(ctx.getApplicationInfo().dataDir);
                String raw = target == null ? "all" : target.trim().toLowerCase(Locale.ROOT);
                boolean all = raw.isEmpty() || "all".equals(raw);

                // 目标集合：all → 白名单全部；否则按逗号拆，并过滤掉非法 id
                java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>();
                if (all) {
                    for (String[] item : CLEARABLE_CACHE_ITEMS) wanted.add(item[0]);
                } else {
                    for (String t : raw.split(",")) {
                        String s = t.trim();
                        if (s.isEmpty()) continue;
                        for (String[] item : CLEARABLE_CACHE_ITEMS) {
                            if (item[0].equals(s)) { wanted.add(s); break; }
                        }
                    }
                    if (wanted.isEmpty()) {
                        r.put("ok", false);
                        r.put("error", "非法 target（可选：" + clearableIds() + " 或 all）");
                        return r;
                    }
                }

                for (String t : wanted) {
                    if ("channel_logo".equals(t) || "tv_cache".equals(t) || "code_cache".equals(t)) {
                        File dir = "code_cache".equals(t) ? new File(dataDir, "code_cache")
                                : new File(cacheDir, t);
                        long[] c = new long[1];
                        freed += deleteRecursively(dir, c);
                        files += (int) c[0];
                        done.add(t);
                    } else if ("webview".equals(t)) {
                        for (String rel : WEBVIEW_CACHE_SUBPATHS) {
                            long[] c = new long[1];
                            freed += deleteRecursively(new File(dataDir, "app_webview/" + rel), c);
                            files += (int) c[0];
                        }
                        done.add(t);
                    }
                }

                r.put("target", raw);
                r.put("cleared", done);
                r.put("freedBytes", freed);
                r.put("freedHuman", humanBytes(freed));
                r.put("files", files);
                r.put("ok", true);
                LogBridge.i("LogBridge", "【缓存清理】target=" + raw + " 释放 " + humanBytes(freed)
                        + "（" + files + " 个文件）");
            } catch (Throwable e) {
                r.put("ok", false);
                r.put("error", String.valueOf(e.getMessage()));
            }
            return r;
        }

        /** 白名单 id 拼串，仅用于报错提示 */
        private String clearableIds() {
            StringBuilder sb = new StringBuilder();
            for (String[] item : CLEARABLE_CACHE_ITEMS) {
                if (sb.length() > 0) sb.append(",");
                sb.append(item[0]);
            }
            return sb.toString();
        }

        /**
         * 【2026-10-20 新增】逐个统计白名单里的可清理项当前占用，供电脑端渲染勾选框。
         * 与 {@link #cleanCache} 的 target 取值严格对齐，避免"看得见却清不掉"。
         */
        private List<Map<String, Object>> buildClearables(Context ctx) {
            List<Map<String, Object>> out = new ArrayList<>();
            if (ctx == null) return out;
            File cacheDir = ctx.getCacheDir();
            File dataDir = new File(ctx.getApplicationInfo().dataDir);

            for (String[] item : CLEARABLE_CACHE_ITEMS) {
                String id = item[0];
                long[] cnt = new long[1];
                long bytes = 0L;
                List<String> parts = new ArrayList<>();

                if ("channel_logo".equals(id) || "tv_cache".equals(id)) {
                    bytes = dirSize(new File(cacheDir, id), cnt);
                } else if ("code_cache".equals(id)) {
                    bytes = dirSize(new File(dataDir, "code_cache"), cnt);
                } else if ("webview".equals(id)) {
                    for (String rel : WEBVIEW_CACHE_SUBPATHS) {
                        File f = new File(dataDir, "app_webview/" + rel);
                        if (f == null || !f.exists()) continue;
                        long[] c2 = new long[1];
                        long s = dirSize(f, c2);
                        if (s > 0) {
                            bytes += s;
                            cnt[0] += c2[0];
                            parts.add(rel + " → " + humanBytes(s));
                        }
                    }
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("label", item[1]);
                m.put("bytes", bytes);
                m.put("human", humanBytes(bytes));
                m.put("files", cnt[0]);
                if (!parts.isEmpty()) m.put("parts", parts);
                out.add(m);
            }
            return out;
        }


        private long deleteRecursively(File f, long[] fileCount) {
            if (f == null || !f.exists()) return 0L;
            long sum = 0L;
            if (f.isFile()) {
                long sz = f.length();
                if (f.delete()) {
                    sum = sz;
                    fileCount[0]++;
                }
                return sum;
            }
            File[] arr = f.listFiles();
            if (arr != null) {
                for (File c : arr) sum += deleteRecursively(c, fileCount);
            }
            // 目录本身删不掉无所谓（内容已清空），但先尝试一下
            try { f.delete(); } catch (Throwable ignored) {}
            return sum;
        }

        private Map<String, Object> buildCacheReport(boolean detail) {
            Map<String, Object> root = new LinkedHashMap<>();
            Context ctx = context;   // LogServer 持有的应用上下文
            if (ctx == null) {
                root.put("error", "no context");
                return root;
            }
            try {
                File dataDir = new File(ctx.getApplicationInfo().dataDir);
                File cacheDir = ctx.getCacheDir();
                File filesDir = ctx.getFilesDir();
                File codeCache = new File(dataDir, "code_cache");
                File webview = new File(dataDir, "app_webview");
                File databases = new File(dataDir, "databases");
                File sharedPrefs = new File(dataDir, "shared_prefs");
                File noBackup = new File(dataDir, "no_backup");

                java.util.LinkedHashMap<String, Object> dirs = new java.util.LinkedHashMap<>();
                long total = 0L;
                total += addCacheEntry(dirs, "cacheDir", cacheDir);
                total += addCacheEntry(dirs, "filesDir", filesDir);
                total += addCacheEntry(dirs, "code_cache", codeCache);
                total += addCacheEntry(dirs, "app_webview", webview);
                total += addCacheEntry(dirs, "databases", databases);
                total += addCacheEntry(dirs, "shared_prefs", sharedPrefs);
                total += addCacheEntry(dirs, "no_backup", noBackup);
                try {
                    total += addCacheEntry(dirs, "extFiles", ctx.getExternalFilesDir(null));
                    total += addCacheEntry(dirs, "extCache", ctx.getExternalCacheDir());
                } catch (Throwable ignored) {}

                root.put("totalBytes", total);
                root.put("totalHuman", humanBytes(total));
                root.put("dirs", dirs);
                // 【2026-10-20 新增】可清理项：电脑端据此渲染勾选框，id 直接作为 clean 的 target
                root.put("clearables", buildClearables(ctx));

                // 【明细模式】?detail=1：把 cacheDir / filesDir 的一级子目录逐个列出，
                // 用于确认"到底是哪些目录占空间、能不能安全清理"。
                if (detail) {
                    Map<String, Object> sub = new LinkedHashMap<>();
                    sub.put("cacheDir", listSubDirs(cacheDir));
                    sub.put("filesDir", listSubDirs(filesDir));
                    root.put("subdirs", sub);
                }
            } catch (Throwable t) {
                root.put("error", String.valueOf(t.getMessage()));
            }
            return root;
        }

        /** 列出某个目录下的一级子目录/文件（含大小），按占用倒序 */
        private List<Map<String, Object>> listSubDirs(File dir) {
            List<Map<String, Object>> out = new ArrayList<>();
            if (dir == null || !dir.isDirectory()) return out;
            File[] arr = dir.listFiles();
            if (arr == null) return out;
            for (File c : arr) {
                long[] cnt = new long[1];
                long size;
                if (c.isFile()) {
                    cnt[0] = 1;
                    size = c.length();
                } else {
                    size = dirSize(c, cnt);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.getName());
                m.put("dir", c.isDirectory());
                m.put("bytes", size);
                m.put("human", humanBytes(size));
                m.put("files", cnt[0]);
                m.put("modified", c.lastModified());
                out.add(m);
            }
            java.util.Collections.sort(out, new java.util.Comparator<Map<String, Object>>() {
                @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                    return Long.compare((Long) b.get("bytes"), (Long) a.get("bytes"));
                }
            });
            return out;
        }

        private long addCacheEntry(Map<String, Object> dirs, String name, File dir) {
            if (dir == null || !dir.exists()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("exists", false);
                dirs.put(name, m);
                return 0L;
            }
            long[] cnt = new long[1];   // cnt[0] = 文件数
            long size = dirSize(dir, cnt);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exists", true);
            m.put("path", dir.getAbsolutePath());
            m.put("bytes", size);
            m.put("human", humanBytes(size));
            m.put("files", cnt[0]);
            dirs.put(name, m);
            return size;
        }

        private long dirSize(File f, long[] fileCount) {
            if (f == null || !f.exists()) return 0L;
            if (f.isFile()) {
                fileCount[0]++;
                return f.length();
            }
            long sum = 0L;
            File[] arr = f.listFiles();
            if (arr != null) {
                for (File c : arr) sum += dirSize(c, fileCount);
            }
            return sum;
        }

        private String humanBytes(long b) {
            if (b < 1024) return b + " B";
            if (b < 1024 * 1024) return String.format("%.2f KB", b / 1024.0);
            if (b < 1024L * 1024 * 1024) return String.format("%.2f MB", b / (1024.0 * 1024));
            return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
        }

        private void handleHttpRequest(java.net.Socket clientSocket, String request, java.io.OutputStream out) throws Exception {
            String path = extractPath(request);

            // 【2026-10-22 I1-02】敏感端点鉴权（服务绑定 0.0.0.0，局域网可达）：
            //   /api/token            仅回环可获取（adb forward 排障链路零影响；PC 工具走 forward）
            //   /api/info·logs·bootlog·cache·cache/clean·perf  回环豁免，非回环须 ?token= 有效 token
            //   / 与 /health          保持公开（探活与引导页）
            if ("/api/token".equals(path)) {
                if (!isLoopback(clientSocket)) {
                    sendSensitiveDenied(out);
                    clientSocket.close();
                    return;
                }
            } else if ("/api/info".equals(path) || "/api/logs".equals(path) || "/api/bootlog".equals(path)
                    || "/api/cache".equals(path) || "/api/perf".equals(path)
                    || "/logs".equals(path) || "/ui".equals(path)
                    || (path != null && path.startsWith("/api/cache/clean"))) {
                if (!isSensitiveAllowed(clientSocket, request)) {
                    sendSensitiveDenied(out);
                    clientSocket.close();
                    return;
                }
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                sendHttpResponse(out, "HTTP/1.0 200 OK", "text/html; charset=utf-8", getIndexHtml());
            } else if ("/logs".equals(path) || "/ui".equals(path)) {
                // 【2026-10-22 新增】图形化日志页（鉴权口径同 /api/logs，已在上面统一校验）
                sendHttpResponse(out, "HTTP/1.0 200 OK", "text/html; charset=utf-8", getLogsHtml());
            } else if ("/api/info".equals(path)) {
                Map<String, Object> info = getServerInfo();
                sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(info));
            } else if ("/api/logs".equals(path)) {
                handleApiLogs(out, request);
            } else if ("/api/bootlog".equals(path)) {
                handleApiBootLog(out);
            } else if ("/api/token".equals(path)) {
                Map<String, Object> result = new HashMap<>();
                result.put("token", token);
                result.put("port", port);
                result.put("protocol", PROTOCOL_VERSION);
                sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(result));
            } else if ("/health".equals(path)) {
                Map<String, Object> health = new HashMap<>();
                health.put("status", "ok");
                health.put("connected_clients", webSockets.size());
                health.put("pending_logs", pendingLogs.size());
                sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(health));
            } else if (path != null && path.startsWith("/api/cache/clean")) {
                // 【2026-10-20 新增】清理缓存：/api/cache/clean?target=channel_logo|tv_cache|webview|code_cache|all
                // 支持逗号分隔多选，如 target=channel_logo,tv_cache
                // 【修复】extractPath() 已经把 query 剥掉了，原实现用 path.indexOf('?') 取 target
                //         永远取不到（qi 恒为 -1），target 会静默退化成 all。改用 extractQueryString(request)。
                String target = queryParam(request, "target", "all");
                sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json",
                        gson.toJson(cleanCache(context, target)));
            } else if ("/api/cache".equals(path)) {
                boolean detail = "1".equals(queryParam(request, "detail", "0"))
                        || (request != null && request.contains("detail=1"));
                sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json",
                        gson.toJson(buildCacheReport(detail)));
            } else if ("/api/perf".equals(path)) {
                // 本进程性能数据：供日志工具在局域网（无 ADB）模式下做性能分析
                sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(getPerfInfo()));
            } else {
                sendHttpResponse(out, "HTTP/1.0 404 Not Found", "text/plain", "Not Found");
            }
            clientSocket.close();
        }

        // ========== 局域网控制端点（POST） ==========
        // /api/remote/key  {action}          遥控器语义动作（对齐 RemoteKeyHandler 面板关闭语义）
        // /api/app/restart {token}           重启应用（Alarm 计划启动 + 自杀进程）
        // /api/app/stop     {token}          退出应用
        private void handleHttpPost(java.net.Socket clientSocket, String request,
                                    java.io.BufferedReader in, java.io.OutputStream out) throws Exception {
            String path = extractPath(request);

            // 读取请求体（按 Content-Length）
            int contentLength = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Content-Length:\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(request);
            if (m.find()) contentLength = Integer.parseInt(m.group(1));
            String body = "";
            if (contentLength > 0) {
                // in 已是 UTF-8 的 BufferedReader，逐字符读即可（此工具链的 String 不支持 char[] 构造器）
                StringBuilder bodyBuilder = new StringBuilder(contentLength);
                int remaining = contentLength;
                while (remaining > 0) {
                    int c = in.read();
                    if (c < 0) break;
                    bodyBuilder.append((char) c);
                    remaining--;
                }
                body = bodyBuilder.toString();
            }

            Map<String, Object> bj = new HashMap<>();
            try {
                if (body.trim().startsWith("{")) {
                    Map<String, Object> parsed = gson.fromJson(body, Map.class);
                    if (parsed != null) bj = parsed;
                }
            } catch (Exception ignored) {}

            try {
                if ("/api/remote/key".equals(path)) {
                    // 【2026-10-22 I1-02】遥控动作同属敏感端点：回环豁免，非回环须有效 token
                    // （body.token 或 query token=，与 /api/app/restart|stop 同口径）
                    String tokenValue = bj.get("token") != null ? String.valueOf(bj.get("token"))
                            : queryParam(request, "token", null);
                    if (!isLoopback(clientSocket) && !checkToken(tokenValue)) {
                        sendSensitiveDenied(out);
                        clientSocket.close();
                        return;
                    }
                    String action = bj.get("action") != null ? String.valueOf(bj.get("action"))
                            : queryParam(request, "action");
                    boolean ok = action != null && RemoteKeyHandler.dispatchRemoteAction(action);
                    if (ok) {
                        LogBridge.i(TAG, "[LAN] 遥控动作: " + action);
                        sendJsonOk(out, true, "已触发: " + action);
                    } else {
                        sendJsonOk(out, false, "无活跃的播放界面（应用可能不在前台或未初始化）");
                    }
                } else if ("/api/app/restart".equals(path) || "/api/app/stop".equals(path)) {
                    boolean restart = path.endsWith("restart");
                    String token = bj.get("token") != null ? String.valueOf(bj.get("token"))
                            : queryParam(request, "token");
                    if (!checkToken(token)) {
                        sendHttpResponse(out, "HTTP/1.0 403 Forbidden", "application/json",
                                gson.toJson(simpleMsg(false, "token 不匹配，请先 GET /api/token 获取")));
                    } else {
                        LogBridge.i(TAG, "[LAN] " + (restart ? "重启应用" : "退出应用") + "（来自局域网控制）");
                        scheduleSelf(restart);
                        sendJsonOk(out, true, restart ? "应用将在约 1 秒后重启" : "应用将在约 1 秒后退出");
                    }
                } else {
                    sendHttpResponse(out, "HTTP/1.0 404 Not Found", "text/plain", "Not Found");
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "handleHttpPost 异常", e);
                sendJsonOk(out, false, "处理异常: " + e.getMessage());
            }
            clientSocket.close();
        }

        private boolean checkToken(String token) {
            return this.token != null && this.token.equals(token);
        }

        /**
         * 【2026-10-22 I1-02】回环来源判定：adb forward 排障链路（PC 127.0.0.1:9527 →
         * 设备 9527）必须豁免，否则 release 无 logcat 时排障通道被自己锁死。
         * 双保险：isLoopbackAddress() + hostAddress 文本（部分 ROM 回环映射异常）。
         */
        private boolean isLoopback(java.net.Socket s) {
            if (s == null) return false;
            try {
                java.net.InetAddress addr = s.getInetAddress();
                if (addr == null) return false;
                if (addr.isLoopbackAddress()) return true;
                String h = addr.getHostAddress();
                return h != null && (h.startsWith("127.") || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h));
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 【2026-10-22 I1-02】敏感端点准入：回环来源直接放行；非回环（局域网）须带有效 token
         * （GET /api/token 获取，拼在 URL query 上：/api/logs?token=xxx）。
         */
        private boolean isSensitiveAllowed(java.net.Socket s, String request) {
            if (isLoopback(s)) return true;
            return checkToken(queryParam(request, "token", null));
        }

        /** 【2026-10-22 I1-02】敏感端点拒绝响应（非回环且 token 无效/缺失） */
        private void sendSensitiveDenied(java.io.OutputStream out) throws IOException {
            LogBridge.w(TAG, "[安全] 非回环来源访问敏感端点被拒（token 无效或缺失；回环来源豁免）");
            sendHttpResponse(out, "HTTP/1.0 403 Forbidden", "application/json",
                    gson.toJson(simpleMsg(false,
                            "token 无效或缺失：回环来源（adb forward）豁免；局域网访问请先 GET /api/token 获取并以 ?token= 附带")));
        }

        /** 【2026-10-22 I1-02】WebSocket 错误消息便捷构造 */
        private Map<String, Object> wsError(String message) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "error");
            m.put("message", message);
            return m;
        }

        private Map<String, Object> simpleMsg(boolean ok, String message) {
            Map<String, Object> m = new HashMap<>();
            m.put("success", ok);
            m.put("message", message);
            return m;
        }

        private void sendJsonOk(java.io.OutputStream out, boolean ok, String message) throws Exception {
            sendHttpResponse(out, ok ? "HTTP/1.0 200 OK" : "HTTP/1.0 400 Bad Request",
                    "application/json", gson.toJson(simpleMsg(ok, message)));
        }

        private String queryParam(String request, String name) {
            try {
                String first = request.split("\r\n")[0];
                int qIdx = first.indexOf('?');
                int end = first.lastIndexOf(' ');
                if (qIdx < 0 || end <= qIdx) return null;
                String query = first.substring(qIdx + 1, end);
                for (String p : query.split("&")) {
                    String[] kv = p.split("=", 2);
                    if (kv.length == 2 && kv[0].equals(name)) {
                        return java.net.URLDecoder.decode(kv[1], "UTF-8");
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }

        /** 重启：Alarm 计划拉起启动页后自杀进程；停止：直接自杀进程（连接方会收到连接断开） */
        private void scheduleSelf(final boolean restart) {
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.postDelayed(() -> {
                try {
                    if (restart) {
                        android.content.Intent i = context.getPackageManager()
                                .getLaunchIntentForPackage(context.getPackageName());
                        if (i != null) {
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, 1001, i,
                                    android.app.PendingIntent.FLAG_IMMUTABLE
                                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                            android.app.AlarmManager am = (android.app.AlarmManager)
                                    context.getSystemService(Context.ALARM_SERVICE);
                            if (am != null) {
                                long at = android.os.SystemClock.elapsedRealtime() + 600;
                                // 【2026-10-10 Lint/ScheduleExactAlarm】Android 12+ 若未授予
                                // SCHEDULE_EXACT_ALARM，setExact 会抛 SecurityException，导致
                                // 「崩溃后自杀重启」失败。无权限时降级为非精确闹钟（误差数百毫秒可接受）。
                                if (android.os.Build.VERSION.SDK_INT >= 31
                                        && !am.canScheduleExactAlarms()) {
                                    am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                                } else {
                                    am.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LogBridge.e(TAG, "scheduleSelf 异常", e);
                }
                main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
            }, 300);
        }

        // ========== 本进程性能采样 ==========
        private long lastCpuSampleAt = 0;
        private long lastCpuUtime = 0;
        private long lastCpuStime = 0;

        private Map<String, Object> getPerfInfo() {
            Map<String, Object> m = new HashMap<>();
            m.put("pid", android.os.Process.myPid());
            m.put("timestamp", System.currentTimeMillis());
            try {
                android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
                android.os.Debug.getMemoryInfo(mi);
                m.put("totalPssMb", round1(mi.getTotalPss() / 1024.0));
                m.put("dalvikPssMb", round1(mi.dalvikPss / 1024.0));
                m.put("nativePssMb", round1(mi.nativePss / 1024.0));
                m.put("otherPssMb", round1(mi.otherPss / 1024.0));

                // 【2026-10-13 补口径】部分环境（模拟器 / 受限 ROM）Debug.getMemoryInfo 全字段返回 0
                // （实测 totalPss=dalvikPss=nativePss=otherPss=0），内存分析因此缺一维。
                // 回退顺序：/proc/self/smaps_rollup 的 Pss → /proc/self/statm 的 RSS。
                if (mi.getTotalPss() <= 0) {
                    long pssKb = readSmapsRollupKb("Pss:");
                    if (pssKb >= 0) {
                        m.put("memSource", "smaps_rollup");
                    } else {
                        pssKb = readStatmRssKb();
                        m.put("memSource", pssKb >= 0 ? "statm_rss" : "unavailable");
                    }
                    if (pssKb >= 0) {
                        double mb = pssKb / 1024.0;
                        m.put("totalPssMb", round1(mb));
                        m.put("otherPssMb", round1(mb));
                    }
                } else {
                    m.put("memSource", "debug_api");
                }
            } catch (Exception e) {
                m.put("totalPssMb", -1);
                m.put("memSource", "error");
            }
            try {
                Runtime rt = Runtime.getRuntime();
                m.put("heapUsedMb", round1((rt.totalMemory() - rt.freeMemory()) / 1048576.0));
                m.put("heapMaxMb", round1(rt.maxMemory() / 1048576.0));
            } catch (Exception ignored) {}
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo sys = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(sys);
                    m.put("sysAvailMb", sys.availMem / 1048576L);
                    m.put("sysTotalMb", sys.totalMem / 1048576L);
                    m.put("sysLowMemory", sys.lowMemory);
                }
            } catch (Exception ignored) {}
            // CPU%：/proc/self/stat 的 utime+stime 差分（两次调用间隔越准越准）
            try {
                String s = readFirstLine("/proc/self/stat");
                if (s != null) {
                    int idx = s.lastIndexOf(')');
                    String[] rest = s.substring(idx + 2).split("\\s+");
                    // rest[0]=state(字段3)；字段14 utime → rest[11]，字段15 stime → rest[12]
                    long utime = Long.parseLong(rest[11]);
                    long stime = Long.parseLong(rest[12]);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (lastCpuSampleAt > 0 && now > lastCpuSampleAt) {
                        long dTicks = (utime - lastCpuUtime) + (stime - lastCpuStime);
                        double cpu = dTicks * 1000.0 / 100.0 / (now - lastCpuSampleAt) * 100.0; // USER_HZ=100
                        m.put("cpuPercent", round1(Math.min(cpu, 800)));
                    }
                    lastCpuSampleAt = now;
                    lastCpuUtime = utime;
                    lastCpuStime = stime;
                }
            } catch (Exception ignored) {}
            m.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
            // 流量统计：TrafficStats API（单应用粒度，模拟器也支持）
            try {
                int uid = android.os.Process.myUid();
                long rx = android.net.TrafficStats.getUidRxBytes(uid);
                long tx = android.net.TrafficStats.getUidTxBytes(uid);
                if (rx > 0) m.put("rxBytes", rx);
                if (tx > 0) m.put("txBytes", tx);
                m.put("trafficSource", "traffic_stats");
            } catch (Exception ignored) {}
            // 网络请求统计
            try {
                java.util.Map<String, Object> netStats = LogCollector.getInstance().getNetStats();
                m.put("netRequestCount", netStats.get("requestCount"));
                m.put("netSuccessCount", netStats.get("successCount"));
                m.put("netErrorCount", netStats.get("errorCount"));
                m.put("netAvgLatencyMs", netStats.get("avgLatencyMs"));
                m.put("netMaxLatencyMs", netStats.get("maxLatencyMs"));
                m.put("netSuccessRate", netStats.get("successRate"));
            // 播放性能统计
            try {
                java.util.Map<String, Object> playStats = LogCollector.getInstance().getPlayStats();
                m.put("playCount", playStats.get("playCount"));
                m.put("stallCount", playStats.get("stallCount"));
                m.put("avgStallMs", playStats.get("avgStallMs"));
                m.put("maxStallMs", playStats.get("maxStallMs"));
                m.put("decodeErrCount", playStats.get("decodeErrCount"));
            } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            return m;
        }

        private double round1(double v) {
            return Math.round(v * 10.0) / 10.0;
        }

        private String readFirstLine(String path) {
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.FileReader(path));
                return r.readLine();
            } catch (Exception e) {
                return null;
            } finally {
                if (r != null) try { r.close(); } catch (Exception ignored) {}
            }
        }

        /** 【2026-10-13】读 /proc/self/smaps_rollup 指定字段（单位 kB）；不可用返回 -1 */
        private long readSmapsRollupKb(String field) {
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/smaps_rollup"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(field)) {
                        String[] p = line.trim().split("\\s+");
                        if (p.length >= 2) return Long.parseLong(p[1]);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (r != null) try { r.close(); } catch (Throwable ignored) {}
            }
            return -1;
        }

        /** 【2026-10-13】读 /proc/self/statm 第 2 列（resident pages）并换算为 kB；不可用返回 -1 */
        private long readStatmRssKb() {
            String s = readFirstLine("/proc/self/statm");
            if (s == null) return -1;
            try {
                String[] p = s.trim().split("\\s+");
                if (p.length < 2) return -1;
                long pages = Long.parseLong(p[1]);
                int pageKb = 4;
                try {
                    pageKb = (int) (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024);
                } catch (Throwable ignored) {
                }
                if (pageKb <= 0) pageKb = 4;
                return pages * pageKb;
            } catch (Throwable e) {
                return -1;
            }
        }

        private void handleApiLogs(java.io.OutputStream out, String request) throws Exception {
            String query = extractQueryString(request);
            if (query != null && query.contains("clear=true")) {
                LogCollector.getInstance().clear();
                Map<String, Object> result = new HashMap<>();
                result.put("status", "cleared");
                sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(result));
                return;
            }

            List<LogCollector.LogEntry> structuredLogs = LogCollector.getInstance().getStructuredLogs();

            // 【2026-10-25】支持 limit / since —— 原来这两个参数被完全忽略，
            //   每次轮询都得回传整个缓冲（2000 条 ≈ 300KB），局域网弱 WiFi 下容易超时
            //   被工具判定为离线。现在：
            //     limit=N  只回最新 N 条（环形缓冲是倒序，index 0 = 最新）
            //     since=T  只回 timestamp >= T 的条目（工具端拿上一轮最大 timestamp 做增量）
            //   两者缺省 = 旧行为（全量），对老版工具完全兼容；参数非法/空值一律按缺省处理，
            //   绝不因为一个坏参数就 500。
            //   注：since 用 >= 而非 >，同毫秒多条日志不会漏；重复由工具端按指纹去重。
            int limit = parseIntDef(queryParam(request, "limit", null), 0);
            long since = parseLongDef(queryParam(request, "since", null), 0L);

            List<LogCollector.LogEntry> selected = structuredLogs;
            if (since > 0L) {
                // 倒序数组：从新到旧扫，遇到第一条「比 since 还老」的就可以停
                int i = 0;
                int n = structuredLogs.size();
                while (i < n && structuredLogs.get(i).timestamp >= since) i++;
                selected = structuredLogs.subList(0, i);
            }
            if (limit > 0 && selected.size() > limit) {
                selected = selected.subList(0, limit);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("logs", selected);
            result.put("count", selected.size());
            // total = 环形缓冲当前总量（未做 limit/since 过滤），供工具端判断积压与是否触顶
            result.put("total", structuredLogs.size());
            result.put("timestamp", System.currentTimeMillis());
            sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(result));
        }

        private void handleApiBootLog(java.io.OutputStream out) throws Exception {
            Map<String, Object> result = new HashMap<>();
            java.io.File bootLog = new java.io.File(context.getFilesDir(), "boot_logs.txt");
            if (bootLog.exists()) {
                java.io.FileInputStream fis = new java.io.FileInputStream(bootLog);
                byte[] buf = new byte[(int) Math.min(bootLog.length(), 1024 * 1024)];
                int n = fis.read(buf);
                fis.close();
                String content = n > 0 ? new String(buf, 0, n, "UTF-8") : "";
                result.put("exists", true);
                result.put("size", bootLog.length());
                result.put("content", content);
            } else {
                result.put("exists", false);
                result.put("size", 0);
                result.put("content", "");
            }
            result.put("timestamp", System.currentTimeMillis());
            sendHttpResponse(out, "HTTP/1.0 200 OK", "application/json", gson.toJson(result));
        }

        private void handleWebSocketUpgrade(java.net.Socket clientSocket, String request, java.io.OutputStream out) throws Exception {
            String key = extractHeader(request, "Sec-WebSocket-Key");
            if (TextUtils.isEmpty(key)) {
                sendHttpResponse(out, "HTTP/1.0 400 Bad Request", "text/plain", "Missing WebSocket Key");
                clientSocket.close();
                return;
            }

            if (webSockets.size() >= MAX_CLIENTS) {

                WebSocket oldest = webSockets.get(0);
                oldest.close(1000, "Too many connections");
                webSockets.remove(0);
                // 【2026-10-22 I1-02】同步移出订阅名单
                subscribedSockets.remove(oldest);
            }

            String acceptKey = computeWebSocketAccept(key);
            String upgradeResponse = "HTTP/1.0 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                    "\r\n";

            out.write(upgradeResponse.getBytes("UTF-8"));
            out.flush();

            try {
                clientSocket.close();
            } catch (Exception ignored) {}

            LogBridge.d(TAG, "WebSocket client connected, key=" + key);

            WebSocket webSocket = httpClient.newWebSocket(
                    new Request.Builder().url("ws://localhost:" + port).build(),
                    new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            webSockets.add(webSocket);
                            // 【2026-10-22 I1-02】不再主动推送 init/pendingLogs：连接后须先发
                            // subscribe 消息带 token 校验，通过后才补发（见 handleIncomingMessage）。
                            // 原实现 onOpen 即向所有连接推设备信息+全量待发日志，等于 WS 无门。
                            LogBridge.d(TAG, "WebSocket client opened (未订阅，等待 token 校验), total clients: " + webSockets.size());
                        }

                        @Override
                        public void onMessage(WebSocket webSocket, String text) {
                            handleIncomingMessage(webSocket, text);
                        }

                        @Override
                        public void onClosing(WebSocket webSocket, int code, String reason) {
                            webSocket.close(1000, null);
                        }

                        @Override
                        public void onClosed(WebSocket webSocket, int code, String reason) {
                            webSockets.remove(webSocket);
                            // 【2026-10-22 I1-02】同步移出订阅名单，防泄漏
                            subscribedSockets.remove(webSocket);
                            LogBridge.d(TAG, "WebSocket client closed, total clients: " + webSockets.size());
                        }

                        @Override
                        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                            webSockets.remove(webSocket);
                            subscribedSockets.remove(webSocket);
                            LogBridge.e(TAG, "WebSocket failure", t);
                        }
                    });
        }

        private void sendInitMessage(WebSocket webSocket) {
            try {
                Map<String, Object> init = new HashMap<>();
                init.put("type", "init");
                init.put("protocol", PROTOCOL_VERSION);
                init.put("timestamp", System.currentTimeMillis());
                init.put("device", getServerInfo());
                webSocket.send(gson.toJson(init));
            } catch (Exception e) {
                LogBridge.e(TAG, "Error sending init message", e);
            }
        }

        private void sendPendingLogs(WebSocket webSocket) {
            try {
                List<Map<String, Object>> logsToSend;
                synchronized (pendingLogs) {
                    logsToSend = new ArrayList<>(pendingLogs);
                    pendingLogs.clear();
                }
                if (!logsToSend.isEmpty()) {
                    Map<String, Object> batch = new HashMap<>();
                    batch.put("type", "batch_logs");
                    batch.put("logs", logsToSend);
                    batch.put("timestamp", System.currentTimeMillis());
                    webSocket.send(gson.toJson(batch));
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "Error sending pending logs", e);
            }
        }

        private void handleIncomingMessage(WebSocket webSocket, String text) {
            try {
                Map<String, Object> message = gson.fromJson(text, Map.class);
                if (message == null) return;

                String type = (String) message.get("type");
                if ("ping".equals(type)) {
                    Map<String, Object> pong = new HashMap<>();
                    pong.put("type", "pong");
                    pong.put("timestamp", System.currentTimeMillis());
                    webSocket.send(gson.toJson(pong));
                } else if ("subscribe".equals(type)) {
                    String tokenValue = (String) message.get("token");
                    if (token != null && token.equals(tokenValue)) {
                        // 【2026-10-22 I1-02】订阅成功才纳入广播名单，并补发 init + 待发日志
                        // （原 onOpen 的主动推送后移到此，保证 PC 工具体验不变：连上→subscribe→收到数据）
                        subscribedSockets.add(webSocket);
                        Map<String, Object> ack = new HashMap<>();
                        ack.put("type", "subscribed");
                        ack.put("success", true);
                        ack.put("device", getServerInfo());
                        webSocket.send(gson.toJson(ack));
                        sendInitMessage(webSocket);
                        sendPendingLogs(webSocket);
                    } else {
                        Map<String, Object> ack = new HashMap<>();
                        ack.put("type", "error");
                        ack.put("message", "Invalid token");
                        webSocket.send(gson.toJson(ack));
                    }
                } else if ("clear_logs".equals(type)) {
                    // 【2026-10-22 I1-02】仅已订阅连接可清日志
                    if (!subscribedSockets.contains(webSocket)) {
                        webSocket.send(gson.toJson(wsError("未订阅：请先发送 subscribe 消息并携带有效 token")));
                    } else {
                        LogCollector.getInstance().clear();
                        Map<String, Object> ack = new HashMap<>();
                        ack.put("type", "logs_cleared");
                        ack.put("timestamp", System.currentTimeMillis());
                        webSocket.send(gson.toJson(ack));
                    }
                } else if ("get_logs".equals(type)) {
                    // 【2026-10-22 I1-02】仅已订阅连接可拉全量日志
                    if (!subscribedSockets.contains(webSocket)) {
                        webSocket.send(gson.toJson(wsError("未订阅：请先发送 subscribe 消息并携带有效 token")));
                    } else {
                        String allLogs = LogCollector.getInstance().getAllLogs();
                        Map<String, Object> result = new HashMap<>();
                        result.put("type", "logs_response");
                        result.put("logs", allLogs);
                        result.put("timestamp", System.currentTimeMillis());
                        webSocket.send(gson.toJson(result));
                    }
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "Error handling incoming message", e);
            }
        }

        private void broadcastLog(LogCollector.LogEntry entry) {
            try {
                Map<String, Object> logMap = new HashMap<>();
                logMap.put("type", "log");
                logMap.put("timestamp", entry.timestamp);
                logMap.put("time", entry.time);
                logMap.put("tag", entry.tag);
                logMap.put("message", entry.message);
                logMap.put("logType", entry.type);
                logMap.put("deviceId", entry.deviceId);
                logMap.put("deviceName", entry.deviceName);
                logMap.put("deviceModel", entry.deviceModel);
                logMap.put("appVersion", entry.appVersion);

                String json = gson.toJson(logMap);

                boolean allSucceeded = true;
                // 【2026-10-22 I1-02】仅向已订阅（token 校验通过）的连接广播日志
                for (WebSocket ws : subscribedSockets) {
                    try {
                        if (!ws.send(json)) {
                            allSucceeded = false;
                        }
                    } catch (Exception e) {
                        allSucceeded = false;
                    }
                }

                if (!allSucceeded) {
                    synchronized (pendingLogs) {
                        pendingLogs.add(logMap);
                        if (pendingLogs.size() > 1000) {
                            pendingLogs.remove(0);
                        }
                    }
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "Error broadcasting log", e);
            }
        }

        private void broadcastLogCleared() {
            try {
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "logs_cleared");
                msg.put("timestamp", System.currentTimeMillis());
                String json = gson.toJson(msg);
                // 【2026-10-22 I1-02】仅向已订阅连接广播（未订阅连接零推送）
                for (WebSocket ws : subscribedSockets) {
                    try {
                        ws.send(json);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "Error broadcasting log cleared", e);
            }
        }

        public void sendCrashLog(String crashLog) {
            try {
                Map<String, Object> logMap = new HashMap<>();
                logMap.put("type", "crash");
                logMap.put("timestamp", System.currentTimeMillis());
                logMap.put("time", sdf.format(new Date()));
                logMap.put("tag", "CrashHandler");
                logMap.put("message", crashLog);
                logMap.put("logType", LogCollector.TYPE_CRASH);
                logMap.put("deviceId", deviceId);
                logMap.put("deviceName", deviceName);
                logMap.put("deviceModel", deviceModel);
                logMap.put("appVersion", appVersion);

                String json = gson.toJson(logMap);
                for (WebSocket ws : webSockets) {
                    try {
                        ws.send(json);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "Error sending crash log", e);
            }
        }

        private Map<String, Object> getServerInfo() {
            Map<String, Object> info = new HashMap<>();
            info.put("deviceId", deviceId);
            info.put("deviceName", deviceName);
            info.put("deviceModel", deviceModel);
            info.put("appVersion", appVersion);
            info.put("sdkVersion", Build.VERSION.SDK_INT);
            info.put("androidVersion", Build.VERSION.RELEASE);
            info.put("brand", Build.BRAND);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("hardware", Build.HARDWARE);
            info.put("protocol", PROTOCOL_VERSION);
            info.put("port", port);
            return info;
        }

        private String getIndexHtml() {
            return "<!DOCTYPE html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "    <meta charset=\"utf-8\">\n" +
                    "    <title>TV Live Log Server</title>\n" +
                    "    <style>\n" +
                    "        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }\n" +
                    "        .container { max-width: 600px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                    "        h1 { color: #333; }\n" +
                    "        .info { background: #e8f4f8; padding: 15px; border-radius: 5px; margin: 15px 0; }\n" +
                    "        .status { color: #4CAF50; font-weight: bold; }\n" +
                    "        code { background: #f0f0f0; padding: 2px 5px; border-radius: 3px; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <div class=\"container\">\n" +
                    "        <h1>📺 TV Live Log Server</h1>\n" +
                    "        <p class=\"status\">● Running</p>\n" +
                    "        <div class=\"info\" style=\"background:#eef6e9;\">\n" +
                    "            <h3>日志查看器</h3>\n" +
                    "            <p><a href=\"/logs\" style=\"font-size:18px;font-weight:bold;color:#1e7be0;\">▶ 打开图形化日志页（/logs）</a></p>\n" +
                    "            <p style=\"color:#666;font-size:12px;\">支持关键字/Tag 过滤、级别筛选、2s 自动刷新、启动日志（bootlog）</p>\n" +
                    "        </div>\n" +
                    "        <div class=\"info\">\n" +
                    "            <h3>Device Info</h3>\n" +
                    "            <p><strong>Device:</strong> " + deviceName + "</p>\n" +
                    "            <p><strong>Model:</strong> " + deviceModel + "</p>\n" +
                    "            <p><strong>Version:</strong> " + appVersion + "</p>\n" +
                    "            <p><strong>Port:</strong> " + port + "</p>\n" +
                    "        </div>\n" +
                    "        <div class=\"info\">\n" +
                    "            <h3>Connection Info</h3>\n" +
                    "            <p><strong>WebSocket:</strong> <code>ws://" + getDeviceIpAddress() + ":" + port + "</code></p>\n" +
                    "            <p><strong>HTTP API:</strong> <code>http://" + getDeviceIpAddress() + ":" + port + "/api/info</code></p>\n" +
                    "        </div>\n" +
                    "        <div class=\"info\">\n" +
                    "            <h3>Token</h3>\n" +
                    // 【2026-10-25】给一个稳定的机器可读锚点 data-token：局域网工具靠抓这个页面拿
                    //   token（/api/token 仅回环可取），原来只能用正则匹配 <p><code>，页面一改版
                    //   就全 403 且无从自查。保留下面那行明文，老版工具继续可用。
                    "            <p style=\"display:none\" data-token=\"" + token + "\"></p>\n" +
                    "            <p><code>" + token + "</code></p>\n" +
                    "            <p style=\"color: #666; font-size: 12px;\">Use this token to connect from desktop client</p>\n" +
                    "        </div>\n" +
                    "    </div>\n" +
                    "</body>\n" +
                    "</html>";
        }

        /**
         * 【2026-10-22 新增】图形化日志页 /logs。
         *
         * <p>背景：此前 9527 只有 /api/logs 的裸 JSON，浏览器里看日志要靠插件格式化，
         * 或者 adb forward 后拉文件解析；移动端/电视上更是没法看。本页为纯前端渲染
         * （无外部依赖、可离线），支持关键字与 Tag 过滤、级别筛选、2s 自动刷新、暂停、
         * 清空缓冲、切到启动日志（boot_logs.txt）。
         *
         * <p>鉴权口径与 /api/logs 对齐：回环来源（adb forward）直接放行，局域网须带 ?token=；
         * 页面自身注入 token 供其后续请求使用，因此局域网打开本页后无需再手工带参。
         */
        private String getLogsHtml() {
            String page = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>TV Live 日志</title>
              <style>
                :root{--bg:#16181d;--panel:#1e2128;--line:#2b2f38;--fg:#e6e8ee;--dim:#8b93a3;
                      --acc:#3aa0ff;--gold:#ffd166;--err:#ff5c6c;--warn:#ffb020;}
                *{box-sizing:border-box}
                body{margin:0;background:var(--bg);color:var(--fg);
                     font:13px/1.5 -apple-system,"Segoe UI",Roboto,"Microsoft YaHei",sans-serif}
                header{position:sticky;top:0;z-index:5;background:rgba(22,24,29,.96);
                       border-bottom:1px solid var(--line);padding:10px 14px}
                h1{font-size:15px;margin:0 0 8px;display:flex;align-items:center;gap:8px}
                h1 small{font-weight:400;color:var(--dim);font-size:12px}
                .tools{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
                input[type=text]{flex:1 1 260px;min-width:180px;background:var(--panel);
                     border:1px solid var(--line);color:var(--fg);border-radius:6px;
                     padding:6px 9px;outline:none}
                input[type=text]:focus{border-color:var(--acc)}
                select,button{background:var(--panel);border:1px solid var(--line);
                     color:var(--fg);border-radius:6px;padding:6px 10px;cursor:pointer;font-size:13px}
                button:hover{border-color:var(--acc)}
                button.on{border-color:var(--acc);color:var(--acc)}
                label.chk{display:flex;align-items:center;gap:5px;color:var(--dim);
                     cursor:pointer;user-select:none}
                #stat{color:var(--dim);font-size:12px;margin-left:auto;white-space:nowrap}
                #stat b{color:var(--fg);font-weight:600}
                main{padding:6px 0 40px}
                .row{display:grid;grid-template-columns:68px 148px 1fr;gap:10px;
                     padding:3px 14px;border-bottom:1px solid rgba(255,255,255,.045);
                     align-items:start}
                .row:hover{background:rgba(58,160,255,.07)}
                .row .t{color:var(--dim);font-variant-numeric:tabular-nums}
                .row .g{color:var(--acc);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
                .row .m{white-space:pre-wrap;word-break:break-word}
                .row.error .m,.row.crash .m{color:var(--err)}
                .row.warn .m{color:var(--warn)}
                .row.playback .m{color:var(--gold)}
                .row.debug .m{color:var(--dim)}
                .row .m em{background:rgba(255,209,102,.28);font-style:normal;border-radius:2px}
                #bootWrap{padding:12px 14px}
                #bootBox{background:var(--panel);border:1px solid var(--line);border-radius:8px;
                     padding:12px;white-space:pre-wrap;word-break:break-word;
                     max-height:74vh;overflow:auto;margin:0}
                .empty{color:var(--dim);text-align:center;padding:40px}
              </style>
            </head>
            <body>
            <header>
              <h1>TV Live 日志 <small id="dev"></small></h1>
              <div class="tools">
                <input type="text" id="q" placeholder="搜索：关键字 / Tag（空格分隔 = 同时满足，如 咪咕 缓冲）">
                <select id="lvl">
                  <option value="all">全部级别</option>
                  <option value="error">error / crash</option>
                  <option value="warn">warn</option>
                  <option value="playback">playback</option>
                  <option value="info">info</option>
                  <option value="debug">debug</option>
                  <option value="network">network</option>
                </select>
                <label class="chk"><input type="checkbox" id="auto" checked>自动刷新</label>
                <button id="boot">启动日志</button>
                <button id="clear">清空</button>
                <span id="stat">加载中…</span>
              </div>
            </header>
            <main id="list"></main>
            <div id="bootWrap" hidden><pre id="bootBox"></pre></div>
            <script>
            var TOKEN = "__TOKEN__";
            var API = function (p) {
              return TOKEN ? (p + (p.indexOf("?") >= 0 ? "&" : "?") + "token=" + TOKEN) : p;
            };
            var LOGS = [], timer = null, bootMode = false;
            function el(id) { return document.getElementById(id); }
            function esc(s) {
              return String(s == null ? "" : s).replace(/[&<>]/g, function (c) {
                return c === "&" ? "&amp;" : c === "<" ? "&lt;" : "&gt;";
              });
            }
            function terms() {
              var raw = el("q").value.toLowerCase().split(" "), out = [];
              for (var i = 0; i < raw.length; i++) { if (raw[i]) out.push(raw[i]); }
              return out;
            }
            function mark(s, qs) {
              var text = String(s == null ? "" : s);
              if (!qs.length) return esc(text);
              var lower = text.toLowerCase(), out = "", i = 0;
              while (i < text.length) {
                var hit = 0;
                for (var k = 0; k < qs.length; k++) {
                  if (lower.substr(i, qs[k].length) === qs[k]) { hit = qs[k].length; break; }
                }
                if (hit) { out += "<em>" + esc(text.substr(i, hit)) + "</em>"; i += hit; }
                else { out += esc(text.charAt(i)); i++; }
              }
              return out;
            }
            function render() {
              if (bootMode) return;
              var qs = terms(), lvl = el("lvl").value;
              var html = "", shown = 0, errs = 0;
              for (var i = 0; i < LOGS.length; i++) {
                var L = LOGS[i], ty = (L.type || "info").toLowerCase();
                if (ty === "error" || ty === "crash") errs++;
                if (lvl !== "all") {
                  if (lvl === "error") { if (ty !== "error" && ty !== "crash") continue; }
                  else if (ty !== lvl) continue;
                }
                if (qs.length) {
                  var hay = ((L.time || "") + " " + (L.tag || "") + " " + (L.message || "")
                             + " " + ty).toLowerCase(), ok = true;
                  for (var k = 0; k < qs.length; k++) {
                    if (hay.indexOf(qs[k]) < 0) { ok = false; break; }
                  }
                  if (!ok) continue;
                }
                shown++;
                html += '<div class="row ' + ty + '"><span class="t">' + esc(L.time)
                      + '</span><span class="g" title="' + esc(L.tag) + '">' + esc(L.tag)
                      + '</span><span class="m">' + mark(L.message, qs) + '</span></div>';
              }
              var top = window.scrollY;
              el("list").innerHTML = html || '<div class="empty">没有匹配的日志</div>';
              var s = "共 <b>" + LOGS.length + "</b> 条 · 显示 <b>" + shown + "</b>";
              if (errs) s += ' · <b style="color:#ff5c6c">错误 ' + errs + '</b>';
              el("stat").innerHTML = s;
              window.scrollTo(0, top);
            }
            function load() {
              fetch(API("/api/logs")).then(function (r) {
                if (!r.ok) throw new Error("HTTP " + r.status);
                return r.json();
              }).then(function (d) {
                LOGS = d.logs || [];
                if (LOGS.length) {
                  el("dev").textContent = "v" + (LOGS[0].appVersion || "?") + " · "
                      + (LOGS[0].deviceName || "") + " · 端口 9527";
                }
                render();
              }).catch(function (e) {
                el("stat").innerHTML = '<b style="color:#ff5c6c">' + esc(e.message) + '</b>';
              });
            }
            function loadBoot() {
              fetch(API("/api/bootlog")).then(function (r) { return r.json(); }).then(function (d) {
                el("bootBox").textContent = d.exists ? d.content : "（无 boot_logs.txt）";
              }).catch(function (e) { el("bootBox").textContent = "读取失败：" + e.message; });
            }
            function stopAuto() { if (timer) { clearInterval(timer); timer = null; } }
            function startAuto() { stopAuto(); timer = setInterval(load, 2000); }
            el("auto").onchange = function () { this.checked ? startAuto() : stopAuto(); };
            el("boot").onclick = function () {
              bootMode = !bootMode;
              this.classList.toggle("on", bootMode);
              el("bootWrap").hidden = !bootMode;
              el("list").style.display = bootMode ? "none" : "";
              if (bootMode) { el("auto").checked = false; stopAuto(); loadBoot(); }
              else { el("auto").checked = true; startAuto(); load(); }
            };
            el("clear").onclick = function () {
              if (!confirm("清空当前日志环形缓冲？（不影响 bootlog）")) return;
              fetch(API("/api/logs?clear=true")).then(function () {
                LOGS = []; render();
              }).catch(function (e) { alert("清空失败：" + e.message); });
            };
            el("q").oninput = render;
            el("lvl").onchange = render;
            load(); startAuto();
            </script>
            </body>
            </html>
            """;
            return token == null ? page : page.replace("__TOKEN__", token);
        }

        private void sendHttpResponse(java.io.OutputStream out, String status, String contentType, String body) throws IOException {
            byte[] bodyBytes = body.getBytes("UTF-8");
            String response = status + "\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + bodyBytes.length + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            out.write(response.getBytes("UTF-8"));
            out.write(bodyBytes);
            out.flush();
        }

        private String extractPath(String request) {
            try {
                String[] lines = request.split("\r\n");
                if (lines.length > 0) {
                    String[] parts = lines[0].split(" ");
                    if (parts.length > 1) {
                        String fullPath = parts[1];
                        int queryIndex = fullPath.indexOf('?');
                        return queryIndex > 0 ? fullPath.substring(0, queryIndex) : fullPath;
                    }
                }
            } catch (Exception ignored) {}
            return "/";
        }

        private String extractQueryString(String request) {
            try {
                String[] lines = request.split("\r\n");
                if (lines.length > 0) {
                    String[] parts = lines[0].split(" ");
                    if (parts.length > 1) {
                        String fullPath = parts[1];
                        int queryIndex = fullPath.indexOf('?');
                        return queryIndex > 0 ? fullPath.substring(queryIndex + 1) : null;
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }

        /** 【2026-10-20 新增】从 query string 里取一个参数值（URL 解码简化版：只处理 %2C/%20 等常见项） */
        private String queryParam(String request, String key, String def) {
            try {
                String q = extractQueryString(request);
                if (q == null || q.isEmpty()) return def;
                for (String kv : q.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    if (key.equals(kv.substring(0, eq))) {
                        String v = kv.substring(eq + 1);
                        return v == null || v.isEmpty() ? def : v;
                    }
                }
            } catch (Throwable ignored) {}
            return def;
        }

        /** 【2026-10-25】宽松整数解析：空/非法/负数一律回落默认值，不抛异常（query 参数不可信）。 */
        private int parseIntDef(String v, int def) {
            if (v == null) return def;
            String s = v.trim();
            if (s.isEmpty()) return def;
            try {
                int n = Integer.parseInt(s);
                return n > 0 ? n : def;
            } catch (Throwable t) {
                return def;
            }
        }

        /** 【2026-10-25】宽松 long 解析（给 since=timestamp 用），口径同 parseIntDef。 */
        private long parseLongDef(String v, long def) {
            if (v == null) return def;
            String s = v.trim();
            if (s.isEmpty()) return def;
            try {
                long n = Long.parseLong(s);
                return n > 0L ? n : def;
            } catch (Throwable t) {
                return def;
            }
        }

        private String extractHeader(String request, String headerName) {
            try {
                String[] lines = request.split("\r\n");
                for (String line : lines) {
                    if (line.toLowerCase(Locale.ROOT).startsWith(headerName.toLowerCase(Locale.ROOT) + ":")) {
                        return line.substring(headerName.length() + 1).trim();
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }

        private String computeWebSocketAccept(String key) {
            try {
                java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
                String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
                byte[] digest = sha1.digest(combined.getBytes("UTF-8"));
                return java.util.Base64.getEncoder().encodeToString(digest);
            } catch (Exception e) {
                return "";
            }
        }

        public String getDeviceIpAddress() {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "Error getting IP address", e);
            }
            return "127.0.0.1";
        }

        public int getPort() {
            return port;
        }

        public String getToken() {
            return token;
        }

        public boolean isRunning() {
            return isRunning;
        }

        public int getConnectedClientCount() {
            return webSockets.size();
        }

        public void setPort(int port) {
            if (!isRunning) {
                this.port = port;
            }
        }

        public void regenerateToken() {
            generateToken();
        }
    }

    public static class ExceptionReporter {
        private static final String TAG = "ExceptionReporter";

        private static final String[] CREDENTIAL_SENSITIVE_KEYWORDS = {
            "password", "token", "secret", "credential",
            "api_key", "apikey", "HY_APPKEY", "HY_APPID",
            "appkey", "signkey", "wssecret", "encryptkey", "privatekey"
        };

        private static final String[] BUSINESS_SENSITIVE_KEYWORDS = {
            "直播源", "频道", "虎牙", "rtmp", "hls", "flv", "m3u8",
            "房间号", "roomId", "频道名", "liveId",
            ".tv", ".com/live", "LiveInfo", "SubscribeInfo",
            "streamId", "live_list", "togetherWatchChannel",
            "http://", "https://"
        };

        private static final long DEDUP_WINDOW_MS = 60_000;
        private static final int MAX_REPORTS_PER_SESSION = 500;

        private static final Map<String, AtomicLong> lastReportTimeMap = new ConcurrentHashMap<>();
        private static final AtomicInteger reportCount = new AtomicInteger(0);
        private static volatile boolean enabled = true;

        public static void init(Context context) {
            enabled = true;
            LogBridge.i(TAG, "ExceptionReporter initialized (policy: throwable+events / biz-masked / tracking-on)");
        }

        public static void setEnabled(boolean value) {
            enabled = value;
            LogBridge.i(TAG, "ExceptionReporter " + (value ? "enabled" : "disabled"));
        }

        public static void reportError(String tag, String msg, Throwable throwable) {
            if (throwable != null) {
                report(tag, msg, throwable);
            } else {
                LogBridge.w(TAG, "[reportError no-throwable skip] tag=" + (tag == null ? "" : tag)
                        + " msg=" + filterSensitive(msg == null ? "" : msg));
            }
        }

        public static void report(String module, Throwable throwable) {
            report(module, null, throwable);
        }

        public static void report(String module, String context, Throwable throwable) {
            if (!enabled) return;

            if (throwable == null) {
                LogBridge.w(TAG, "[non-throwable skip] module=" + module
                        + " context=" + filterSensitive(context == null ? "" : context));
                return;
            }

            String key = buildKey(module, throwable);
            if (!shouldReport(key)) {
                return;
            }

            int count = reportCount.incrementAndGet();
            if (count > MAX_REPORTS_PER_SESSION) {
                if (count == MAX_REPORTS_PER_SESSION + 1) {
                    LogBridge.w(TAG, "Report count exceeded limit " + MAX_REPORTS_PER_SESSION + ", stop reporting.");
                }
                return;
            }

            String stackTrace = getStackTrace(throwable);

            // msg 不再拼 throwable.getMessage()（LogBridge.e 还会追加 tr.getMessage()，造成 "X : X" 重复）；
            // 完整堆栈已由下方 LogCollector 条目承载
            LogBridge.e(TAG, "[" + module + "] Exception reported: " + throwable.getMessage());
            if (context != null && !context.isEmpty()) {
                LogBridge.e(TAG, "[" + module + "] Context: " + context);
            }
            try {
                LogCollector.getInstance().error(module,
                        (context != null ? context + " | " : "") + stackTrace);
            } catch (Throwable ignored) {}
        }

        public static void reportHuyaBusinessFailure(String module, int code, String errorMsg, String roomInfo) {
            if (!enabled) return; // 与 report() 一致：静止上报时连带屏蔽业务失败上报
            StringBuilder sb = new StringBuilder();
            sb.append("[HUYA_BIZ_FAIL local] ")
              .append(module == null ? "" : module)
              .append(" | code=").append(code)
              .append(" | errorMsg=").append(errorMsg == null ? "" : errorMsg)
              .append(" | room=").append(roomInfo == null ? "" : roomInfo);
            LogBridge.e(TAG, sb.toString());
            // LogBridge.e 内部已同步进 LogCollector，无需重复入队（重复条目还会被
            // filterSensitive 脱敏成 [MASKED_BIZ]，形成每房间两行的噪音）
        }

        static String filterSensitive(String msg) {
            if (TextUtils.isEmpty(msg)) return "";

            if (containsBiz(msg)) return "[MASKED_BIZ]";

            if (!containsCredential(msg)) return msg;
            String out = msg;
            for (String kw : CREDENTIAL_SENSITIVE_KEYWORDS) {
                java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
                        "(?i)(" + java.util.regex.Pattern.quote(kw) + "\\s*[=:]\\s*)([^&,\\s\\n\"]+)",
                        java.util.regex.Pattern.CASE_INSENSITIVE);
                out = p1.matcher(out).replaceAll("$1****");
            }
            return out;
        }

        private static boolean containsBiz(String msg) {
            if (TextUtils.isEmpty(msg)) return false;
            String lower = msg.toLowerCase(Locale.ROOT);
            for (String kw : BUSINESS_SENSITIVE_KEYWORDS) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }

        private static boolean containsCredential(String msg) {
            if (TextUtils.isEmpty(msg)) return false;
            String lower = msg.toLowerCase(Locale.ROOT);
            for (String kw : CREDENTIAL_SENSITIVE_KEYWORDS) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }

        private static boolean shouldReport(String key) {
            long now = System.currentTimeMillis();
            AtomicLong last = lastReportTimeMap.get(key);
            if (last == null) {
                last = new AtomicLong(0);
                AtomicLong prev = lastReportTimeMap.putIfAbsent(key, last);
                if (prev != null) last = prev;
            }
            long prevTime = last.get();
            if (now - prevTime < DEDUP_WINDOW_MS) {
                return false;
            }
            return last.compareAndSet(prevTime, now);
        }

        private static String buildKey(String module, Throwable throwable) {
            StringBuilder sb = new StringBuilder();
            sb.append(module == null ? "unknown" : module).append("|");
            sb.append(throwable.getClass().getName()).append("|");
            String msg = throwable.getMessage();
            if (msg != null) {
                sb.append(msg.length() < 100 ? msg : msg.substring(0, 100));
            }
            StackTraceElement[] stack = throwable.getStackTrace();
            if (stack != null && stack.length > 0) {
                sb.append("|").append(stack[0].getClassName()).append(":")
                  .append(stack[0].getMethodName()).append(":").append(stack[0].getLineNumber());
            }
            return sb.toString();
        }

        private static String getStackTrace(Throwable throwable) {
            StringWriter sw = new StringWriter(512);
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        }

        public static String getStats() {
            return String.format(Locale.ROOT,
                    "Reports: %d/%d, Tracked: %d, Enabled: %s",
                    reportCount.get(), MAX_REPORTS_PER_SESSION,
                    lastReportTimeMap.size(), enabled);
        }
    }

@SuppressLint("StaticFieldLeak")
    public static class CrashHandler implements Thread.UncaughtExceptionHandler {

        private static final String TAG = "CrashHandler";

        private static CrashHandler instance;

        private Context context;

        private Thread.UncaughtExceptionHandler defaultHandler;

        private static final String CRASH_DIR_NAME = "crash_logs";

        private static final int MAX_CRASH_LOG_COUNT = 10;

        private static final String CRASH_FILE_PREFIX = "crash_";

        private static final String CRASH_FILE_SUFFIX = ".txt";

        public static volatile String CRASH_LOG = "";

        private static final long CRASH_PAGE_DISPLAY_DURATION = 60 * 1000;

        private boolean autoRestartEnabled = false;

        private static final long RESTART_DELAY = 1000;

        private CrashHandler() {}

        public static CrashHandler getInstance() {
            if (instance == null) {
                instance = new CrashHandler();
            }
            return instance;
        }

        public void init(Context ctx) {
            context = ctx.getApplicationContext();

            defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

            Thread.setDefaultUncaughtExceptionHandler(this);

            LogBridge.i(TAG, "全局崩溃捕获器已初始化");
            LogBridge.d(TAG, "崩溃日志保存目录：" + getCrashDir().getAbsolutePath());
            LogBridge.d(TAG, "崩溃页面显示时长：" + CRASH_PAGE_DISPLAY_DURATION / 1000 + " 秒");
            LogBridge.d(TAG, "自动重启：" + (autoRestartEnabled ? "已开启" : "已关闭"));
        }

        public void setAutoRestartEnabled(boolean enabled) {
            this.autoRestartEnabled = enabled;
            LogBridge.d(TAG, "自动重启已" + (enabled ? "开启" : "关闭"));
        }

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            try {

                if (isIgnoredMarsLinkError(ex)) {
                    LogBridge.w(TAG, "🛡️【mars 黑名单】忽略已知 SO 不兼容异常: " + ex.getClass().getSimpleName()
                            + ": " + ex.getMessage());
                    try {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        ex.printStackTrace(pw);
                        pw.close();
                        LogCollector.getInstance().error("CrashHandler", "mars_ignored:" + sw.toString());
                        if (context != null) {
                            LogServer.getInstance(context).sendCrashLog(
                                    "【mars 黑名单免疫】\n" + sw.toString());
                        }
                    } catch (Throwable ignored) {
                    }
                    return;
                }

                String crashLog = buildCrashLog(thread, ex);

                CRASH_LOG = crashLog;
                LogBridge.e(TAG, crashLog);

                saveCrashLogToFile(crashLog);

                try {
                    LogBridge.e(TAG, "【崩溃】" + ex.getClass().getName() + ": " + ex.getMessage());
                    LogBridge.e(TAG, "【崩溃】详细日志已保存到文件");
                    LogBridge.e(TAG, "【崩溃】崩溃页面将显示 " + (CRASH_PAGE_DISPLAY_DURATION / 1000) + " 秒");

                    LogCollector.getInstance().crash("CrashHandler",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());

                    if (context != null) {
                        LogServer.getInstance(context).sendCrashLog(crashLog);
                    }
                } catch (Exception ignored) {}

                startCrashActivity();

                new Thread(() -> {
                    try {
                        Thread.sleep(CRASH_PAGE_DISPLAY_DURATION);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }

                    if (autoRestartEnabled) {
                        restartApp();
                    }

                    Process.killProcess(Process.myPid());
                    System.exit(1);
                }, "crash-handler").start();

            } catch (Exception e) {
                LogBridge.e(TAG, "崩溃处理失败", e);
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                }
            }
        }

        private String buildCrashLog(Thread thread, Throwable ex) {
            StringBuilder sb = new StringBuilder();

            sb.append("================ 崩溃日志 ================\n");
            sb.append("时间：").append(getCurrentTime()).append("\n");
            sb.append("线程：").append(thread.getName()).append(" (ID: ").append(thread.getId()).append(")\n");
            sb.append("异常类型：").append(ex.getClass().getName()).append("\n");
            sb.append("异常信息：").append(ex.getMessage()).append("\n");

            sb.append("\n========== 设备信息 ==========\n");
            sb.append("品牌：").append(Build.BRAND).append("\n");
            sb.append("型号：").append(Build.MODEL).append("\n");
            sb.append("产品：").append(Build.PRODUCT).append("\n");
            sb.append("系统版本：Android ").append(Build.VERSION.RELEASE).append("\n");
            sb.append("SDK版本：").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("构建版本：").append(Build.DISPLAY).append("\n");
            sb.append("CPU架构：").append(Build.SUPPORTED_ABIS[0]).append("\n");

            sb.append("\n========== APP信息 ==========\n");
            try {
                PackageManager pm = context.getPackageManager();
                PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
                sb.append("包名：").append(pi.packageName).append("\n");
                sb.append("版本名：").append(pi.versionName).append("\n");
                sb.append("版本号：").append(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)).append("\n");
            } catch (PackageManager.NameNotFoundException e) {
                sb.append("包名：").append(context.getPackageName()).append("\n");
                sb.append("版本信息：获取失败\n");
            }

            sb.append("\n========== 屏幕信息 ==========\n");
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics metrics = new DisplayMetrics();
                wm.getDefaultDisplay().getMetrics(metrics);
                sb.append("分辨率：").append(metrics.widthPixels).append(" x ").append(metrics.heightPixels).append("\n");
                sb.append("密度：").append(metrics.densityDpi).append("dpi\n");
                sb.append("缩放比例：").append(metrics.density).append("\n");
            } catch (Exception e) {
                sb.append("屏幕信息：获取失败\n");
            }

            sb.append("\n========== 堆栈信息 ==========\n");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            pw.close();
            sb.append(sw.toString());

            sb.append("\n========== 提示 ==========\n");
            if (autoRestartEnabled) {
                sb.append("页面将在 ").append(CRASH_PAGE_DISPLAY_DURATION / 1000).append(" 秒后自动重启应用\n");
            } else {
                sb.append("页面将在 ").append(CRASH_PAGE_DISPLAY_DURATION / 1000).append(" 秒后自动关闭\n");
            }
            sb.append("详细日志已保存到本地文件，可在设置页面查看\n");

            sb.append("\n========================================\n");

            return sb.toString();
        }

        private void saveCrashLogToFile(String crashLog) {
            try {

                File crashDir = getCrashDir();
                if (!crashDir.exists()) {
                    crashDir.mkdirs();
                }

                String fileName = CRASH_FILE_PREFIX
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                        + CRASH_FILE_SUFFIX;

                File crashFile = new File(crashDir, fileName);

                FileWriter writer = new FileWriter(crashFile);
                writer.write(crashLog);
                writer.flush();
                writer.close();

                LogBridge.d(TAG, "崩溃日志已保存：" + crashFile.getAbsolutePath());

                cleanOldCrashLogs();

            } catch (Exception e) {
                LogBridge.e(TAG, "保存崩溃日志到文件失败", e);
            }
        }

        private void cleanOldCrashLogs() {
            try {
                File crashDir = getCrashDir();
                File[] files = crashDir.listFiles();

                if (files == null || files.length <= MAX_CRASH_LOG_COUNT) {
                    return;
                }

                List<File> fileList = new ArrayList<>(Arrays.asList(files));
                Collections.sort(fileList, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return Long.compare(f2.lastModified(), f1.lastModified());
                    }
                });

                for (int i = MAX_CRASH_LOG_COUNT; i < fileList.size(); i++) {
                    File oldFile = fileList.get(i);
                    if (oldFile.delete()) {
                        LogBridge.d(TAG, "已删除旧的崩溃日志：" + oldFile.getName());
                    }
                }

            } catch (Exception e) {
                LogBridge.e(TAG, "清理旧崩溃日志失败", e);
            }
        }

        private void restartApp() {
            try {

                Intent intent = new Intent(context, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                PendingIntent pendingIntent = PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
                );

                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

                if (alarmManager != null) {
                    alarmManager.set(
                            AlarmManager.RTC,
                            System.currentTimeMillis() + RESTART_DELAY,
                            pendingIntent
                    );
                    LogBridge.d(TAG, "已设置自动重启，" + RESTART_DELAY + "ms 后启动");
                }

            } catch (Exception e) {
                LogBridge.e(TAG, "设置自动重启失败", e);
            }
        }

        private void startCrashActivity() {
            try {
                Intent intent = new Intent(context, CrashActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(intent);
                LogBridge.d(TAG, "已启动崩溃页面");
            } catch (Exception e) {
                LogBridge.e(TAG, "启动崩溃页面失败", e);
            }
        }

        private File getCrashDir() {
            return new File(context.getFilesDir(), CRASH_DIR_NAME);
        }

        private String getCurrentTime() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        }

        public List<File> getCrashLogList() {
            try {
                File crashDir = getCrashDir();
                File[] files = crashDir.listFiles();

                if (files == null || files.length == 0) {
                    return new ArrayList<>();
                }

                List<File> fileList = new ArrayList<>(Arrays.asList(files));
                Collections.sort(fileList, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return Long.compare(f2.lastModified(), f1.lastModified());
                    }
                });

                return fileList;

            } catch (Exception e) {
                LogBridge.e(TAG, "获取崩溃日志列表失败", e);
                return new ArrayList<>();
            }
        }

        public String getLatestCrashLog() {
            List<File> list = getCrashLogList();
            if (list.isEmpty()) {
                return null;
            }

            try {
                File latestFile = list.get(0);
                return readFileToString(latestFile);
            } catch (Exception e) {
                LogBridge.e(TAG, "读取最新崩溃日志失败", e);
                return null;
            }
        }

        public int clearAllCrashLogs() {
            try {
                File crashDir = getCrashDir();
                File[] files = crashDir.listFiles();

                if (files == null || files.length == 0) {
                    return 0;
                }

                int count = 0;
                for (File file : files) {
                    if (file.delete()) {
                        count++;
                    }
                }

                LogBridge.d(TAG, "已清空 " + count + " 个崩溃日志");
                return count;

            } catch (Exception e) {
                LogBridge.e(TAG, "清空崩溃日志失败", e);
                return 0;
            }
        }

        private boolean isIgnoredMarsLinkError(Throwable ex) {
            if (ex == null) return false;
            Throwable cur = ex;
            int depth = 0;
            while (cur != null && depth < 10) {
                if (cur instanceof UnsatisfiedLinkError || cur instanceof NoSuchMethodError) {
                    String msg = (cur.getMessage() == null ? "" : cur.getMessage())
                            + " " + stackToString(cur);
                    if (msg.contains("mars") || msg.contains("StnLogic")
                            || msg.contains("libmarsstn") || msg.contains("MtpMarsTransporter")) {
                        return true;
                    }
                }

                // 按简单类名判断（避免引入 rxjava 依赖），语义不变：解包取真实 cause。
                if ("UndeliverableException".equals(cur.getClass().getSimpleName())) {
                    cur = cur.getCause();
                    depth++;
                    continue;
                }
                cur = cur.getCause();
                depth++;
            }
            return false;
        }

        private String stackToString(Throwable t) {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                t.printStackTrace(pw);
                pw.close();
                return sw.toString();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private String readFileToString(File file) {
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] buffer = new byte[(int) file.length()];
                fis.read(buffer);
                fis.close();
                return new String(buffer);
            } catch (Exception e) {
                LogBridge.e(TAG, "读取文件失败：" + file.getAbsolutePath(), e);
                return null;
            }
        }
    }

    public static class CrashActivity extends Activity {

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            requestWindowFeature(Window.FEATURE_NO_TITLE);

            try {
                Window w = getWindow();
                if (w != null) {
                    android.view.WindowManager.LayoutParams lp = w.getAttributes();

                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int screenH = getResources().getDisplayMetrics().heightPixels;
                    lp.width  = Math.max(screenW * 92 / 100, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                    lp.height = Math.max(screenH * 82 / 100, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = Gravity.CENTER;
                    w.setAttributes(lp);
                }
            } catch (Throwable ignored) {}

            LinearLayout rootLayout = new LinearLayout(this);
            rootLayout.setOrientation(LinearLayout.VERTICAL);
            rootLayout.setBackgroundColor(0xFFFFFFFF);
            rootLayout.setPadding(dp(24), dp(24), dp(24), dp(24));
            rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);

            rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            TextView tvTitle = new TextView(this);
            tvTitle.setText("😢 应用崩溃了");
            tvTitle.setTextSize(22);
            tvTitle.setTextColor(Color.BLACK);
            tvTitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.setMargins(0, 0, 0, dp(16));
            tvTitle.setLayoutParams(titleParams);
            rootLayout.addView(tvTitle);

            TextView tvError = new TextView(this);
            tvError.setTextSize(15);
            tvError.setTextColor(0xFFFF4D4F);
            tvError.setGravity(Gravity.CENTER);
            tvError.setMaxLines(4);
            tvError.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            errorParams.setMargins(0, 0, 0, dp(16));
            tvError.setLayoutParams(errorParams);
            rootLayout.addView(tvError);

            ScrollView scrollView = new ScrollView(this);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1);
            scrollParams.setMargins(0, 0, 0, dp(16));
            scrollView.setLayoutParams(scrollParams);

            TextView tvDetail = new TextView(this);
            tvDetail.setTextSize(11);
            tvDetail.setTextColor(Color.BLACK);
            tvDetail.setPadding(dp(16), dp(16), dp(16), dp(16));
            tvDetail.setBackgroundColor(0xFFF5F5F5);

            tvDetail.setMinEms(40);
            tvDetail.setHorizontallyScrolling(false);
            tvDetail.setSingleLine(false);

            scrollView.addView(tvDetail, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            rootLayout.addView(scrollView);

            LinearLayout btnLayout = new LinearLayout(this);
            btnLayout.setOrientation(LinearLayout.HORIZONTAL);
            btnLayout.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams btnLayoutParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLayout.setLayoutParams(btnLayoutParams);

            Button btnRestart = new Button(this);
            btnRestart.setText("重启应用");
            btnRestart.setTextColor(Color.WHITE);
            btnRestart.setBackgroundColor(0xFF40A9FF);
            btnRestart.setMinHeight(dp(44));
            LinearLayout.LayoutParams btnRestartParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1);
            btnRestartParams.setMargins(0, 0, dp(8), 0);
            btnRestart.setLayoutParams(btnRestartParams);
            btnLayout.addView(btnRestart);

            Button btnExit = new Button(this);
            btnExit.setText("退出应用");
            btnExit.setTextColor(Color.WHITE);
            btnExit.setBackgroundColor(0xFFFF6B6B);
            btnExit.setMinHeight(dp(44));
            LinearLayout.LayoutParams btnExitParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1);
            btnExitParams.setMargins(dp(8), 0, 0, 0);
            btnExit.setLayoutParams(btnExitParams);
            btnLayout.addView(btnExit);

            rootLayout.addView(btnLayout);
            setContentView(rootLayout);

            String crashLog = CrashHandler.CRASH_LOG;
            if (TextUtils.isEmpty(crashLog)) {
                crashLog = CrashHandler.getInstance().getLatestCrashLog();
            }

            String errorMsg = "发生了未处理的异常";
            String detailMsg = "无详细信息 (日志文件可能未保存)";

            if (!TextUtils.isEmpty(crashLog)) {
                String[] lines = crashLog.split("\n");
                for (String line : lines) {
                    if (line.startsWith("异常信息：")) {
                        errorMsg = line.replace("异常信息：", "");
                        break;
                    }
                }
                detailMsg = crashLog;
            }

            tvError.setText(errorMsg);
            tvDetail.setText(detailMsg);

            btnRestart.setOnClickListener(v -> {
                try {
                    AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    Intent intent = new Intent(CrashActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                    PendingIntent pendingIntent = PendingIntent.getActivity(
                            CrashActivity.this,
                            0,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
                    );
                    if (alarmManager != null) {
                        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent);
                    }
                } catch (Exception ignored) {}

                finish();
            });

            btnExit.setOnClickListener(v -> {

                finish();

                Process.killProcess(Process.myPid());
                System.exit(0);
            });
        }

        @Override
        public void onBackPressed() {
            finish();
            Process.killProcess(Process.myPid());
            System.exit(0);
        }

        private int dp(int value) {
            float density = getResources().getDisplayMetrics().density;
            return (int) (value * density + 0.5f);
        }
    }
}
