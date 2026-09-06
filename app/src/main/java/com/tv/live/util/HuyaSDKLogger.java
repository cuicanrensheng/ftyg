package com.tv.live.util;

import android.Manifest;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
import com.tv.live.MyApplication;
import com.tv.live.util.LogBridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class HuyaSDKLogger {

    private static final String TAG = "HuyaSDKLogger";

    private static final int MAX_RECENT = 1000;

    public static final int VERBOSE = 0;
    public static final int DEBUG   = 1;
    public static final int INFO    = 2;
    public static final int WARN    = 3;
    public static final int ERROR   = 4;

    public static class LogEntry {
        public final long timestamp;
        public final int level;
        public final String tag;
        public final String msg;
        public final String source;

        LogEntry(long ts, int lv, String t, String m, String src) {
            this.timestamp = ts;
            this.level = lv;
            this.tag = t;
            this.msg = m;
            this.source = src;
        }

        @Override
        public String toString() {
            String levelStr;
            switch (level) {
                case ERROR: levelStr = "E"; break;
                case WARN:  levelStr = "W"; break;
                case INFO:  levelStr = "I"; break;
                case DEBUG: levelStr = "D"; break;
                default:    levelStr = "V"; break;
            }
            return String.format("[%s] %s/%s: %s",
                    levelStr, tag, source, msg);
        }
    }

    public interface OnLogListener {
        void onLog(LogEntry entry);
    }

    private static final AtomicBoolean sInitialized = new AtomicBoolean(false);
    private static final List<LogEntry> sRecentLogs =
            Collections.synchronizedList(new ArrayList<LogEntry>());
    private static final List<OnLogListener> sListeners =
            new CopyOnWriteArrayList<>();
    private static volatile int sMinLevel = VERBOSE;
    private static volatile boolean sLogcatEnabled = true;
    private static volatile boolean sForwardToLogCollector = true;

    private static final ConcurrentHashMap<String, String> sEventNames = new ConcurrentHashMap<>();
    static {
        sEventNames.put("init", "SDK初始化");
        sEventNames.put("startUp", "启动直播");
        sEventNames.put("startLive", "开始直播");
        sEventNames.put("reStartLive", "重开直播");
        sEventNames.put("endLive", "结束直播");
        sEventNames.put("sendPlayerData", "发送玩家数据");
        sEventNames.put("receiveDanmu", "接收弹幕");
        sEventNames.put("exitFullScreen", "退出全屏");
        sEventNames.put("fullScreen", "进入全屏");
        sEventNames.put("closeLiveList", "关闭直播列表");
        sEventNames.put("showFloating", "显示浮窗");
        sEventNames.put("getLiveData", "获取直播数据");
        sEventNames.put("getLiveListData", "获取直播列表");
        sEventNames.put("getTagListData", "获取标签列表");
        sEventNames.put("subscribe", "关注");
        sEventNames.put("unSubscribe", "取消关注");
        sEventNames.put("querySubscribeStatus", "查询关注状态");
        sEventNames.put("customUIGetAuthorInfo", "获取主播信息");
        sEventNames.put("customUILogin", "登录");
        sEventNames.put("customUILogout", "登出");
        sEventNames.put("customUIGetResolution", "获取清晰度");
        sEventNames.put("customUISetResolution", "设置清晰度");
        sEventNames.put("setReceiveDanmuData", "设置弹幕接收");
    }

    public static void init() {
        if (!sInitialized.compareAndSet(false, true)) return;

        if (sForwardToLogCollector) {
            addLogListener(new OnLogListener() {
                @Override
                public void onLog(LogEntry entry) {
                    forwardToLogCollector(entry);
                }
            });
        }

        startAukLogCapture();

        LogBridge.i(TAG, "✅ HuyaSDKLogger 初始化完成（logcat 监听 SDK 日志）");
    }

    private static void startAukLogCapture() {
        if (ContextCompat.checkSelfPermission(MyApplication.getInstance(),
                Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            LogBridge.w(TAG, "⚠️ 无 READ_LOGS 权限，跳过 logcat 监听（避免触发系统日志权限弹窗）");
            return;
        }
        Thread captureThread = new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            try {

                try {
                    Runtime.getRuntime().exec("logcat -c").waitFor();
                } catch (Exception ignored) {}

                process = Runtime.getRuntime().exec(new String[]{"logcat", "-s", "auk:D", "HuyaSDKLogger:I"});
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    parseAndDispatchAukLog(line);
                }
            } catch (Exception e) {
                LogBridge.w(TAG, "⚠️ auk 日志捕获线程退出: " + e.getMessage());
            } finally {
                try {
                    if (reader != null) reader.close();
                    if (process != null) process.destroy();
                } catch (Exception ignored) {}
            }
        }, "AukLogCapture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private static void parseAndDispatchAukLog(String line) {
        if (line == null || line.isEmpty()) return;

        try {

            if (line.contains("[SDK-Auk]") || line.contains("[HuyaSDKLogger]")) return;

            if (line.contains("【SDK-Auk】")) return;

            int colonIndex = line.indexOf(": ");
            if (colonIndex <= 0) return;

            if (!line.contains(" auk ") && !line.contains(": auk")) return;

            String logContent = line.substring(colonIndex + 2).trim();

            int level = DEBUG;
            if (line.contains(" E auk ")) level = ERROR;
            else if (line.contains(" W auk ")) level = WARN;
            else if (line.contains(" I auk ")) level = INFO;
            else if (line.contains(" D auk ")) level = DEBUG;
            else if (line.contains(" V auk ")) level = VERBOSE;

            if (shouldCaptureLog(logContent)) {
                dispatch(level, "auk", logContent, "SDK-Auk");
            }
        } catch (Exception ignored) {}
    }

    private static boolean shouldCaptureLog(String msg) {
        if (msg == null) return false;

        if (msg.contains("success") || msg.contains("error") || msg.contains("fail") ||
            msg.contains("GetLivingInfo") || msg.contains("GetLivingInfoRsp") ||
            msg.contains("onResultCallback") || msg.contains("startLive") ||
            msg.contains("playUrl") || msg.contains("stream") ||
            msg.contains("code=") || msg.contains("retCode")) {
            return true;
        }

        if (msg.contains("cgi:/") || msg.contains("NS request") ||
            msg.contains("WupRsp") || msg.contains("WupReq") ||
            msg.contains("deliverResponse") || msg.contains("execute")) {
            return true;
        }

        return false;
    }

    private static void forwardToLogCollector(LogEntry entry) {
        if (!sForwardToLogCollector) return;

        try {
            String type;
            switch (entry.level) {
                case ERROR:
                    type = LogCollector.TYPE_ERROR;
                    break;
                case WARN:
                    type = LogCollector.TYPE_WARN;
                    break;
                case INFO:
                    type = LogCollector.TYPE_NETWORK;
                    break;
                case DEBUG:
                default:
                    type = LogCollector.TYPE_DEBUG;
                    break;
            }

            String logMsg = "【" + entry.source + "】" + entry.msg;
            LogCollector.getInstance().addLog(entry.tag, logMsg, type);
        } catch (Throwable ignored) {
        }
    }

    public static void log(int level, String tag, String msg) {
        dispatch(level, tag, msg, "App");
    }

    public static void error(String tag, String msg) {
        dispatch(ERROR, tag, msg, "App");
    }

    public static void error(String tag, String msg, Throwable throwable) {
        if (throwable != null) {
            BuglyLogSender.reportHuyaExceptionSafely(tag, throwable, msg);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(msg == null ? "" : msg);
        if (throwable != null) {
            sb.append(" | ").append(throwable.getClass().getSimpleName())
              .append(": ").append(throwable.getMessage());
        }
        dispatch(ERROR, tag, sb.toString(), "App");
    }

    public static void info(String tag, String msg) {
        dispatch(INFO, tag, msg, "App");
    }

    public static void debug(String tag, String msg) {
        dispatch(DEBUG, tag, msg, "App");
    }

    public static void warn(String tag, String msg) {
        dispatch(WARN, tag, msg, "App");
    }

    public static void warn(String tag, String msg, Throwable throwable) {
        if (throwable != null) {
            BuglyLogSender.reportHuyaExceptionSafely(tag, throwable, msg);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(msg == null ? "" : msg);
        if (throwable != null) {
            sb.append(" | ").append(throwable.getClass().getSimpleName())
              .append(": ").append(throwable.getMessage());
        }
        dispatch(WARN, tag, sb.toString(), "App");
    }

    public static void onBerryEvent(String eventType, Map<String, String> eventData) {
        String readableName = sEventNames.containsKey(eventType)
                ? sEventNames.get(eventType)
                : eventType;
        StringBuilder sb = new StringBuilder();
        sb.append("【BerryEvent:").append(readableName).append("】");
        if (eventData != null && !eventData.isEmpty()) {
            for (Map.Entry<String, String> e : eventData.entrySet()) {
                sb.append(" ").append(e.getKey()).append("=").append(e.getValue());
            }
        }

        int level = INFO;
        if ("endLive".equals(eventType)) {
            level = DEBUG;
        } else if ("init".equals(eventType)) {
            level = INFO;
        }

        dispatch(level, "BerryEvent", sb.toString(), "BerryEvent");
    }

    public static void onCustomUICallback(String callbackName, int code, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("【CustomUI:").append(callbackName).append("】code=").append(code);
        if (!TextUtils.isEmpty(detail)) {
            sb.append(" ").append(detail);
        }
        // 已知 SDK 误报：onResultCallback 在业务正常时仍可能返回非 0 code，
        // 降级为 DEBUG 并不再触发业务失败上报，避免污染崩溃分组与远端上报。
        boolean falseAlarm = "onResultCallback".equals(callbackName);
        int level = (code == 0) ? DEBUG : (falseAlarm ? DEBUG : ERROR);
        dispatch(level, "CustomUI", sb.toString(), "CustomUICallback");

        if (code != 0 && !falseAlarm) {
            ExceptionReporter.reportHuyaBusinessFailure(
                    "CustomUI." + callbackName, code, detail, null);
        }
    }

    public static void onSDKError(String tag, String errorMsg, Throwable throwable) {

        if (throwable != null) {
            BuglyLogSender.reportHuyaExceptionSafely(tag, throwable, errorMsg);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【SDK-Error】").append(errorMsg);
        if (throwable != null) {
            sb.append(" - ").append(throwable.getClass().getSimpleName())
              .append(": ").append(throwable.getMessage());
        }
        dispatch(ERROR, tag, sb.toString(), "Internal");

        if (throwable == null) {
            ExceptionReporter.reportHuyaBusinessFailure(
                    tag == null ? "HuyaSDK" : tag, 0, errorMsg, null);
        }
    }

    public static void onSDKState(String tag, String stateMsg) {
        dispatch(DEBUG, tag, "【SDK-State】" + stateMsg, "Internal");
    }

    public static void addLogListener(OnLogListener listener) {
        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    public static void removeLogListener(OnLogListener listener) {
        if (listener != null) {
            sListeners.remove(listener);
        }
    }

    public static List<LogEntry> getRecentLogs() {
        synchronized (sRecentLogs) {
            return new ArrayList<>(sRecentLogs);
        }
    }

    public static void clearLogs() {
        synchronized (sRecentLogs) {
            sRecentLogs.clear();
        }
    }

    public static void setMinLevel(int level) {
        sMinLevel = level;
    }

    public static void setLogcatEnabled(boolean enabled) {
        sLogcatEnabled = enabled;
    }

    public static void setForwardToLogCollector(boolean enabled) {
        sForwardToLogCollector = enabled;
    }

    public static void dispatch(int level, String tag, String msg, String source) {
        if (level < sMinLevel) return;

        LogEntry entry = new LogEntry(System.currentTimeMillis(), level, tag, msg, source);

        synchronized (sRecentLogs) {
            sRecentLogs.add(entry);
            if (sRecentLogs.size() > MAX_RECENT) {
                sRecentLogs.remove(0);
            }
        }

        for (OnLogListener l : sListeners) {
            try {
                l.onLog(entry);
            } catch (Throwable ignored) {
            }
        }

        forwardToLogCollector(entry);

    }

    private HuyaSDKLogger() {
    }
}
