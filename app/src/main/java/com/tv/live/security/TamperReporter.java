package com.tv.live.security;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import com.tv.live.util.LogBridge;

import com.tv.live.BuildConfig;
import com.tv.live.util.LogCollector;

public final class TamperReporter {

    private static final String TAG = "TamperReporter";

    public static final int TAMPER_SIGNATURE = 1;
    public static final int TAMPER_PACKAGE_NAME = 2;
    public static final int TAMPER_DEX_INTEGRITY = 3;
    public static final int TAMPER_DEBUGGER = 4;
    public static final int TAMPER_FRIDA = 5;
    public static final int TAMPER_XPOSED = 6;
    public static final int TAMPER_HOOK = 7;
    public static final int TAMPER_ROOT = 8;
    public static final int TAMPER_EMULATOR = 9;
    public static final int TAMPER_MEMORY = 10;

    private static volatile boolean sReported = false;
    private static volatile boolean sCrashing = false;
    private static Context sAppContext;

    private TamperReporter() {}

    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
        if (!BuildConfig.IS_DEBUG) {
            LogBridge.i(TAG, "反编译检测器已启用");
        } else {
            LogBridge.i(TAG, "反编译检测器：调试模式，仅记录日志");
        }
    }

    public static void reportTamper(int tamperType, String detail) {
        if (sReported) return;
        sReported = true;

        String typeName = getTypeName(tamperType);
        LogBridge.e(TAG, "⚠️ 检测到篡改行为: " + typeName + " - " + detail);

        LogCollector.getInstance().error(TAG,
            "⚠️ 反编译检测: " + typeName + " | " + detail);

        reportToLocalMonitor(tamperType, typeName, detail);

        if (!BuildConfig.IS_DEBUG) {
            reportToBugly(tamperType, typeName, detail);
        }

        writeTamperLogToFile(tamperType, typeName, detail);
    }

    public static void reportSuspicious(int tamperType, String detail) {
        String typeName = getTypeName(tamperType);
        LogBridge.w(TAG, "⚠️ 检测到可疑环境: " + typeName + " - " + detail);

        LogCollector.getInstance().warn(TAG,
            "可疑环境: " + typeName + " | " + detail);
    }

    private static void writeTamperLogToFile(int tamperType, String typeName, String detail) {
        try {
            if (sAppContext == null) return;

            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("\n===== TAMPER DETECTED =====\n");
            logBuilder.append("Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date())).append("\n");
            logBuilder.append("Type: ").append(typeName).append(" (").append(tamperType).append(")\n");
            logBuilder.append("Detail: ").append(detail).append("\n");
            logBuilder.append("Package: ").append(BuildConfig.APPLICATION_ID).append("\n");
            logBuilder.append("Version: ").append(BuildConfig.VERSION_NAME).append("\n");
            logBuilder.append("Device: ").append(Build.MODEL).append("\n");
            logBuilder.append("Brand: ").append(Build.BRAND).append("\n");
            logBuilder.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
            logBuilder.append("Build: ").append(BuildConfig.IS_DEBUG ? "debug" : "release").append("\n");
            logBuilder.append("Process: ").append(android.os.Process.myPid()).append("\n");
            logBuilder.append("==========================\n\n");

            java.io.File dir = new java.io.File(sAppContext.getFilesDir(), "tamper_logs");
            if (!dir.exists()) dir.mkdirs();

            java.io.File logFile = new java.io.File(dir, "tamper_" + System.currentTimeMillis() + ".log");
            java.io.FileWriter writer = new java.io.FileWriter(logFile, true);
            writer.write(logBuilder.toString());
            writer.close();

            LogBridge.i(TAG, "篡改日志已写入: " + logFile.getAbsolutePath());

        } catch (Exception e) {
            LogBridge.e(TAG, "写入篡改日志失败: " + e.getMessage());
        }
    }

    private static void reportToLocalMonitor(int tamperType, String typeName, String detail) {
        try {

            StringBuilder sb = new StringBuilder();
            sb.append("[TAMPER] ").append(typeName).append("\n");
            sb.append("Detail: ").append(detail).append("\n");
            sb.append("Package: ").append(BuildConfig.APPLICATION_ID).append("\n");
            sb.append("Version: ").append(BuildConfig.VERSION_NAME).append("\n");
            sb.append("Device: ").append(Build.MODEL).append("\n");
            sb.append("Time: ").append(System.currentTimeMillis());

            LogCollector.getInstance().error(TAG, sb.toString());

        } catch (Exception e) {
            LogBridge.e(TAG, "本地上报失败: " + e.getMessage());
        }
    }

    private static void reportToBugly(int tamperType, String typeName, String detail) {
        try {

            StringBuilder stackTrace = new StringBuilder();
            stackTrace.append("检测到反编译/篡改行为\n");
            stackTrace.append("类型: ").append(typeName).append("\n");
            stackTrace.append("详情: ").append(detail).append("\n");
            stackTrace.append("包名: ").append(BuildConfig.APPLICATION_ID).append("\n");
            stackTrace.append("版本: ").append(BuildConfig.VERSION_NAME).append("\n");
            stackTrace.append("设备: ").append(Build.MODEL).append("\n");
            stackTrace.append("品牌: ").append(Build.BRAND).append("\n");
            stackTrace.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
            stackTrace.append("时间: ").append(System.currentTimeMillis()).append("\n");
            stackTrace.append("构建类型: ").append(BuildConfig.IS_DEBUG ? "debug" : "release");

            LogCollector.getInstance().error(TAG,
                "[TAMPER-LOCAL] " + typeName + " | " + detail + "\n" + stackTrace);
            LogBridge.i(TAG, "篡改行为已记录到本地（Bugly 已移除）");
        } catch (Exception e) {
            LogBridge.e(TAG, "本地篡改记录失败: " + e.getMessage());
        }
    }

    public static void triggerCrash(int tamperType, String detail) {
        if (sCrashing) return;
        sCrashing = true;

        reportTamper(tamperType, detail);

        if (BuildConfig.IS_DEBUG) {

            LogBridge.w(TAG, "调试模式：跳过崩溃触发");
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(500);

                throw new TamperFatalException(
                    "【致命错误】检测到应用被篡改，已终止运行: " + detail);
            } catch (InterruptedException e) {

                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "TamperCrash").start();
    }

    public static void emergencyReport(int tamperType, String detail) {
        if (!BuildConfig.IS_DEBUG) {

            reportTamper(tamperType, detail);
            Process.killProcess(Process.myPid());
            System.exit(0);
        }
    }

    public static String getTypeName(int tamperType) {
        switch (tamperType) {
            case TAMPER_SIGNATURE: return "签名不匹配";
            case TAMPER_PACKAGE_NAME: return "包名不匹配";
            case TAMPER_DEX_INTEGRITY: return "DEX完整性校验失败";
            case TAMPER_DEBUGGER: return "调试器检测";
            case TAMPER_FRIDA: return "Frida检测";
            case TAMPER_XPOSED: return "Xposed框架检测";
            case TAMPER_HOOK: return "Hook攻击检测";
            case TAMPER_ROOT: return "Root环境";
            case TAMPER_EMULATOR: return "模拟器环境";
            case TAMPER_MEMORY: return "内存篡改检测";
            default: return "未知篡改类型(" + tamperType + ")";
        }
    }

    public static boolean isReported() {
        return sReported;
    }

    public static boolean isCrashing() {
        return sCrashing;
    }

    private static class TamperException extends Exception {
        public TamperException(String message) {
            super(message);

            StackTraceElement[] stack = new StackTraceElement[] {
                new StackTraceElement(
                    "com.tv.live.security.TamperReporter",
                    "reportTamper",
                    "TamperReporter.java",
                    100),
                new StackTraceElement(
                    "com.tv.live.security.SecurityCheck",
                    "verifyOnStart",
                    "SecurityCheck.java",
                    50),
                new StackTraceElement(
                    "com.tv.live.MyApplication",
                    "onCreate",
                    "MyApplication.java",
                    100),
            };
            setStackTrace(stack);
        }
    }

    private static class TamperFatalException extends RuntimeException {
        public TamperFatalException(String message) {
            super(message);
        }
    }
}
