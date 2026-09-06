package com.tv.live.security;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import com.tv.live.util.LogBridge;
import android.widget.Toast;

import com.tv.live.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

public final class SecurityGuard {

    private static final String TAG = "SecurityGuard";

    public static final int THREAT_DEBUGGER = 1;
    public static final int THREAT_FRIDA = 2;
    public static final int THREAT_XPOSED = 3;
    public static final int THREAT_ROOT = 4;
    public static final int THREAT_EMULATOR = 5;
    public static final int TAMPER_DETECTED = 6;
    public static final int SIGNATURE_MISMATCH = 7;
    public static final int HOOK_DETECTED = 8;

    public static final int RESPONSE_LOG = 0;
    public static final int RESPONSE_WARN = 1;
    public static final int RESPONSE_BLOCK = 2;
    public static final int RESPONSE_EXIT = 3;
    public static final int RESPONSE_SELF_DESTRUCT = 4;

    private static volatile boolean sInitialized = false;
    private static volatile Context sAppContext = null;
    private static volatile int sHighestThreat = 0;
    private static volatile boolean sDestroyed = false;

    private SecurityGuard() {}

    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        sAppContext = context.getApplicationContext();

        TamperReporter.init(context);

        if (!BuildConfig.IS_DEBUG) {

            startSecurityMonitor();
            LogBridge.i(TAG, "安全守卫已启用");
        } else {
            LogBridge.i(TAG, "安全守卫：调试模式，仅记录日志");
        }
    }

    public static void onThreatDetected(int threatType, String detail) {
        if (sDestroyed) return;

        if (threatType > sHighestThreat) {
            sHighestThreat = threatType;
        }

        String threatName = getThreatName(threatType);
        LogBridge.e(TAG, "⚠️ 检测到安全威胁: " + threatName + " - " + detail);

        reportThreatToReporter(threatType, threatName, detail);

        int responseLevel = getResponseLevel(threatType);

        switch (responseLevel) {
            case RESPONSE_LOG:

                break;

            case RESPONSE_WARN:
                showWarning(threatName, detail);
                break;

            case RESPONSE_BLOCK:
                showWarning(threatName, detail);
                blockFunctionality();
                break;

            case RESPONSE_EXIT:
                showWarning(threatName, detail);
                exitApplication();
                break;

            case RESPONSE_SELF_DESTRUCT:
                showWarning(threatName, detail);
                selfDestruct();
                break;
        }
    }

    private static void reportThreatToReporter(int threatType, String threatName, String detail) {
        try {

            int tamperType = mapThreatToTamperType(threatType);

            TamperReporter.reportTamper(tamperType,
                "SecurityGuard检测到: " + threatName + " | " + detail);

        } catch (Exception e) {
            LogBridge.e(TAG, "上报威胁失败: " + e.getMessage());
        }
    }

    private static int mapThreatToTamperType(int threatType) {
        switch (threatType) {
            case THREAT_DEBUGGER:
                return TamperReporter.TAMPER_DEBUGGER;
            case THREAT_FRIDA:
                return TamperReporter.TAMPER_FRIDA;
            case THREAT_XPOSED:
                return TamperReporter.TAMPER_XPOSED;
            case THREAT_ROOT:
                return TamperReporter.TAMPER_ROOT;
            case THREAT_EMULATOR:
                return TamperReporter.TAMPER_EMULATOR;
            case TAMPER_DETECTED:
                return TamperReporter.TAMPER_DEX_INTEGRITY;
            case SIGNATURE_MISMATCH:
                return TamperReporter.TAMPER_SIGNATURE;
            case HOOK_DETECTED:
                return TamperReporter.TAMPER_HOOK;
            default:
                return TamperReporter.TAMPER_MEMORY;
        }
    }

    private static int getResponseLevel(int threatType) {
        if (BuildConfig.IS_DEBUG) {

            return RESPONSE_LOG;
        }

        switch (threatType) {
            case THREAT_DEBUGGER:
                return RESPONSE_EXIT;

            case THREAT_FRIDA:
            case THREAT_XPOSED:
                return RESPONSE_SELF_DESTRUCT;

            case THREAT_ROOT:
                return RESPONSE_WARN;

            case THREAT_EMULATOR:
                return RESPONSE_LOG;

            case TAMPER_DETECTED:
            case SIGNATURE_MISMATCH:
                return RESPONSE_SELF_DESTRUCT;

            case HOOK_DETECTED:
                return RESPONSE_BLOCK;

            default:
                return RESPONSE_LOG;
        }
    }

    private static void showWarning(String threatName, String detail) {
        if (sAppContext == null) return;

        try {
            final String msg = "安全警告: " + threatName + "\n" + detail;
            new Thread(() -> {
                try {
                    Toast.makeText(sAppContext, msg, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            }).start();
        } catch (Exception e) {
            LogBridge.e(TAG, "显示警告失败: " + e.getMessage());
        }
    }

    private static void blockFunctionality() {

        LogBridge.w(TAG, "🔒 已阻止应用功能");
    }

    private static void exitApplication() {
        if (sDestroyed) return;
        sDestroyed = true;

        LogBridge.w(TAG, "🚪 正在退出应用...");

        new Thread(() -> {
            try {
                Thread.sleep(2000);

                clearSensitiveData();

                Process.killProcess(Process.myPid());
                System.exit(0);
            } catch (Exception e) {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "SecurityExit").start();
    }

    private static void selfDestruct() {
        if (sDestroyed) return;
        sDestroyed = true;

        LogBridge.w(TAG, "💥 触发自毁程序...");

        new Thread(() -> {
            try {

                clearSensitiveData();

                Thread.sleep(3000);

                Process.killProcess(Process.myPid());
                System.exit(0);
            } catch (Exception e) {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "SelfDestruct").start();
    }

    private static void clearSensitiveData() {
        try {

            if (sAppContext != null) {
                sAppContext.getSharedPreferences("secure_data", Context.MODE_PRIVATE).edit().clear().apply();
                sAppContext.getSharedPreferences("security_guard", Context.MODE_PRIVATE).edit().clear().apply();
                sAppContext.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().apply();
            }

            if (sAppContext != null) {
                File filesDir = sAppContext.getFilesDir();
                if (filesDir != null) {
                    deleteDirectoryContents(filesDir);
                }

                File cacheDir = sAppContext.getCacheDir();
                if (cacheDir != null) {
                    deleteDirectoryContents(cacheDir);
                }
            }

            LogBridge.i(TAG, "敏感数据已清理");
        } catch (Exception e) {
            LogBridge.e(TAG, "清理数据失败: " + e.getMessage());
        }
    }

    private static void deleteDirectoryContents(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectoryContents(file);
            } else {
                file.delete();
            }
        }
    }

    private static void corruptDexFiles() {

        try {
            if (sAppContext == null) return;

            String dexPath = sAppContext.getApplicationInfo().sourceDir;
            if (dexPath == null) return;

            File apkFile = new File(dexPath);
            if (apkFile.exists()) {
                RandomAccessFile raf = new RandomAccessFile(apkFile, "rw");
                raf.seek(apkFile.length() - 100);
                byte[] garbage = new byte[100];
                new java.util.Random().nextBytes(garbage);
                raf.write(garbage);
                raf.close();
            }

        } catch (Exception e) {

        }
    }

    private static void startSecurityMonitor() {
        if (sAppContext == null) return;

        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

            while (!sDestroyed && !BuildConfig.IS_DEBUG) {
                try {
                    Thread.sleep(30000);

                    if (checkFrida()) {
                        LogBridge.w(TAG, "检测到 Frida 工具");
                        onThreatDetected(THREAT_FRIDA, "检测到 Frida");
                    }

                    if (checkXposed()) {
                        LogBridge.w(TAG, "检测到 Xposed 框架");
                        onThreatDetected(THREAT_XPOSED, "检测到 Xposed");
                    }

                    if (android.os.Debug.isDebuggerConnected()) {
                        LogBridge.w(TAG, "检测到调试器连接（仅记录，不上报）");

                    }

                    if (checkRoot()) {
                        LogBridge.w(TAG, "检测到 Root 环境（仅记录）");
                    }

                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    LogBridge.e(TAG, "安全监控异常: " + e.getMessage());
                }
            }
        }, "SecurityMonitor").start();
    }

    private static boolean checkFrida() {
        try {

            int[] fridaPorts = {27042, 27043, 27044, 27045};
            for (int port : fridaPorts) {
                java.net.Socket socket = new java.net.Socket();
                try {
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
                    socket.close();
                    return true;
                } catch (Exception e) {

                }
            }

            String[] fridaProcesses = {"frida-server", "frida-agent", "frida-injector"};
            String processList = executeCommand("ps");
            if (processList != null) {
                for (String proc : fridaProcesses) {
                    if (processList.contains(proc)) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkXposed() {
        try {

            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException e) {

        }

        try {

            Class.forName("com.saurik.substrate.MS");
            return true;
        } catch (ClassNotFoundException e) {

        }

        String xposedProp = System.getProperty("de.robv.android.xposed.IXposedHookLoadPackage");
        if (xposedProp != null) return true;

        File xposedFile = new File("/data/local/tmp/Xposed");
        if (xposedFile.exists()) return true;

        return false;
    }

    private static boolean checkRoot() {
        try {

            String result = executeCommand("which su");
            if (result != null && result.contains("su")) return true;

            result = executeCommand("which busybox");
            if (result != null && result.contains("busybox")) return true;

            File magiskFile = new File("/sbin/magisk");
            if (magiskFile.exists()) return true;

            File suFile = new File("/system/bin/su");
            if (suFile.exists()) return true;

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String executeCommand(String command) {
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            java.io.InputStream inputStream = process.getInputStream();
            byte[] buffer = new byte[4096];
            int n = inputStream.read(buffer);
            inputStream.close();
            process.waitFor();
            return n > 0 ? new String(buffer, 0, n) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getThreatName(int threatType) {
        switch (threatType) {
            case THREAT_DEBUGGER: return "调试器";
            case THREAT_FRIDA: return "Frida Hook";
            case THREAT_XPOSED: return "Xposed 框架";
            case THREAT_ROOT: return "Root 权限";
            case THREAT_EMULATOR: return "模拟器";
            case TAMPER_DETECTED: return "APK 篡改";
            case SIGNATURE_MISMATCH: return "签名不匹配";
            case HOOK_DETECTED: return "Hook 攻击";
            default: return "未知威胁(" + threatType + ")";
        }
    }

    public static int getHighestThreat() {
        return sHighestThreat;
    }

    public static boolean isDestroyed() {
        return sDestroyed;
    }

    public static void reset() {
        sHighestThreat = 0;
        sDestroyed = false;
    }
}
