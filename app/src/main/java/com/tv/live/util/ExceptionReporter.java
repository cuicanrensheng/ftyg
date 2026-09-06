package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import com.tv.live.MyApplication;
import com.tv.live.util.LogBridge;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ExceptionReporter {
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
            LogBridge.w(TAG, "[non-throwable skip Bugly-exception] module=" + module
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

        try {
            BuglyLogSender.reportHuyaExceptionSafely(module, throwable, context);
        } catch (Throwable t) {
            LogBridge.e(TAG, "Bugly report failed", t);
        }
    }

    public static void reportHuyaBusinessFailure(String module, int code, String errorMsg, String roomInfo) {
        if (!enabled) return; // 与 report() 一致：静止上报时连带屏蔽业务失败上报（本地日志 + Bugly 事件）
        StringBuilder sb = new StringBuilder();
        sb.append("[HUYA_BIZ_FAIL local+track_event] ")
          .append(module == null ? "" : module)
          .append(" | code=").append(code)
          .append(" | errorMsg=").append(errorMsg == null ? "" : errorMsg)
          .append(" | room=").append(roomInfo == null ? "" : roomInfo);
        LogBridge.e(TAG, sb.toString());
        // LogBridge.e 内部已同步进 LogCollector，无需重复入队（重复条目还会被
        // filterSensitive 脱敏成 [MASKED_BIZ]，形成每房间两行的噪音）

        try {
            Context ctx = getAppContext();
            if (ctx != null) {
                BuglyLogSender.getInstance(ctx)
                        .reportHuyaBusinessFailureAsEvent(module, code, errorMsg, roomInfo);
            } else {
                LogBridge.w(TAG, "App Context unavailable, skip huya biz-fail track event upload");
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "reportHuyaBusinessFailureAsEvent upload failed (local log still kept)", t);
        }
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

    private static Context getAppContext() {
        MyApplication app = MyApplication.getInstance();
        if (app != null) {
            return app.getApplicationContext();
        }
        return null;
    }

    public static String getStats() {
        return String.format(Locale.ROOT,
                "Reports: %d/%d, Tracked: %d, Enabled: %s",
                reportCount.get(), MAX_REPORTS_PER_SESSION,
                lastReportTimeMap.size(), enabled);
    }
}
