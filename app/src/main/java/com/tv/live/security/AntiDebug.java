package com.tv.live.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.provider.Settings;
import com.tv.live.util.LogBridge;

import com.tv.live.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public class AntiDebug {

    private static final String TAG = "AntiDebug";
    private static volatile boolean initialized = false;
    private static volatile boolean debugDetected = false;

    public static boolean init(Context context) {
        return init(context, true);
    }

    public static boolean init(Context context, boolean enable) {
        if (!enable) {
            LogBridge.i(TAG, "反调试检测已禁用（调试模式）");
            initialized = true;
            debugDetected = false;
            return false;
        }
        if (initialized) return debugDetected;
        initialized = true;

        TamperReporter.init(context);

        boolean debuggerFound = checkDebugger(context);
        boolean fridaFound = checkFrida();
        boolean xposedFound = checkXposed();
        boolean emulatorFound = checkEmulator(context);

        boolean confirmedDebugger = Debug.isDebuggerConnected();

        debugDetected = fridaFound || xposedFound || confirmedDebugger;

        if (debugDetected) {
            LogBridge.w(TAG, "⚠️ 检测到可疑环境: " +
                (confirmedDebugger ? "调试器" : "") +
                (fridaFound ? "Frida" : "") +
                (xposedFound ? "Xposed" : ""));

            if (!BuildConfig.IS_DEBUG) {
                if (confirmedDebugger && !fridaFound && !xposedFound) {

                    TamperReporter.reportSuspicious(
                        TamperReporter.TAMPER_DEBUGGER,
                        "检测到调试器连接，但不确认是否为篡改");
                }
                if (fridaFound) {

                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_FRIDA,
                        "AntiDebug检测到Frida工具");
                }
                if (xposedFound) {

                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_XPOSED,
                        "AntiDebug检测到Xposed框架");
                }
            }
        }

        boolean adbEnabled = checkAdbEnabled(context);
        boolean mockLocation = checkMockLocation(context);

        if (adbEnabled) {
            LogBridge.w(TAG, "⚠️ USB调试已开启（可能用于开发调试，非篡改）");
        }
        if (mockLocation) {
            LogBridge.w(TAG, "⚠️ 检测到模拟位置应用（可能用于测试，非篡改）");
        }
        if (emulatorFound) {
            LogBridge.w(TAG, "⚠️ 检测到模拟器环境（可能是合法测试，非篡改）");
        }

        if (debuggerFound && !confirmedDebugger) {
            LogBridge.w(TAG, "调试器检测结果存疑：TracerPid检测到异常但Debug.isDebuggerConnected()未确认");
        }

        return debugDetected;
    }

    private static boolean checkDebugger(Context context) {
        try {

            if (Debug.isDebuggerConnected()) {
                LogBridge.w(TAG, "检测到调试器连接 (Debug.isDebuggerConnected=true)");
                return true;
            }

            ApplicationInfo appInfo = context.getApplicationInfo();
            boolean isDebuggable = (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (isDebuggable && !BuildConfig.IS_DEBUG) {

                LogBridge.w(TAG, "⚠️ 正式版应用但标记为可调试（可能被重打包）");

            }

            try {
                File file = new File("/proc/self/status");
                if (file.exists()) {
                    FileInputStream fis = new FileInputStream(file);
                    byte[] buffer = new byte[1024];
                    int len = fis.read(buffer);
                    fis.close();
                    String content = new String(buffer, 0, len);

                    for (String line : content.split("\n")) {
                        if (line.startsWith("TracerPid:")) {
                            String pidStr = line.substring("TracerPid:".length()).trim();
                            int pid = Integer.parseInt(pidStr);

                            if (pid != 0) {
                                LogBridge.d(TAG, "TracerPid=" + pid + " (参考信息)");

                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {

            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测调试器失败: " + e.getMessage());
        }

        return Debug.isDebuggerConnected();
    }

    private static boolean checkFrida() {
        try {

            File procDir = new File("/proc");
            String[] pids = procDir.list();
            if (pids != null) {
                for (String pid : pids) {
                    try {
                        File cmdLineFile = new File("/proc/" + pid + "/cmdline");
                        if (cmdLineFile.exists()) {
                            FileInputStream fis = new FileInputStream(cmdLineFile);
                            byte[] buffer = new byte[256];
                            int len = fis.read(buffer);
                            fis.close();
                            String cmdline = new String(buffer, 0, len);
                            if (cmdline.contains("frida") ||
                                cmdline.contains("frida-server") ||
                                cmdline.contains("frida-qt") ||
                                cmdline.contains("REJECT")) {
                                LogBridge.w(TAG, "检测到 Frida 相关进程");
                                return true;
                            }
                        }
                    } catch (Exception e) {

                    }
                }
            }

            String[] fridaPorts = {"27042", "27043", "27044", "27045"};
            for (String port : fridaPorts) {
                try {
                    java.net.Socket socket = new java.net.Socket();
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1",
                        Integer.parseInt(port)), 100);
                    socket.close();
                    LogBridge.w(TAG, "检测到 Frida 默认端口: " + port);
                    return true;
                } catch (Exception e) {

                }
            }

            String[] fridaPaths = {
                "/data/local/tmp/frida-server",
                "/data/local/tmp/frida-gadget",
                "/system/bin/frida-server",
                "/system/xbin/frida-server"
            };
            for (String path : fridaPaths) {
                if (new File(path).exists()) {
                    LogBridge.w(TAG, "检测到 Frida 文件: " + path);
                    return true;
                }
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测 Frida 失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean checkXposed() {
        try {

            try {
                Class.forName("de.robv.android.xposed.XposedBridge");
                LogBridge.w(TAG, "检测到 Xposed 框架");
                return true;
            } catch (ClassNotFoundException e) {

            }

            String[] xposedPaths = {
                "/data/adb/lspd/config",
                "/data/adb/modules/lsposed",
                "/system/framework/XposedBridge.jar",
                "/data/local/tmp/XposedBridge.jar"
            };
            for (String path : xposedPaths) {
                if (new File(path).exists()) {
                    LogBridge.w(TAG, "检测到 Xposed/LSPosed: " + path);
                    return true;
                }
            }

            try {
                for (java.util.Map.Entry<String, String> entry : System.getenv().entrySet()) {
                    if (entry.getKey().contains("XPOSED") ||
                        entry.getValue().contains("xposed")) {
                        LogBridge.w(TAG, "检测到 Xposed 环境变量");
                        return true;
                    }
                }
            } catch (Exception e) {

            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测 Xposed 失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean checkEmulator(Context context) {
        try {

            String[] emulatorFiles = {
                "/dev/socket/qemud",
                "/dev/qemu_pipe",
                "/system/lib/libc_malloc_debug_qemu.so",
                "/sys/qemu_trace",
                "/system/bin/qemu-props"
            };
            for (String path : emulatorFiles) {
                if (new File(path).exists()) {
                    LogBridge.w(TAG, "检测到模拟器文件: " + path);
                    return true;
                }
            }

            String fingerprint = android.os.Build.FINGERPRINT;
            if (fingerprint.contains("generic") ||
                fingerprint.contains("sdk") ||
                fingerprint.contains("vbox") ||
                fingerprint.contains("emulator")) {
                LogBridge.w(TAG, "检测到模拟器指纹");
                return true;
            }

            try {
                File qemuPipe = new File("/dev/socket/qemud");
                if (qemuPipe.exists()) {
                    return true;
                }
            } catch (Exception e) {

            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测模拟器失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean checkAdbEnabled(Context context) {
        try {
            if (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) == 1) {
                LogBridge.w(TAG, "USB调试已开启");
                return true;
            }
        } catch (Exception e) {

        }
        return false;
    }

    private static boolean checkMockLocation(Context context) {
        try {
            String mockLocationApp = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ALLOW_MOCK_LOCATION);
            if (mockLocationApp != null && !mockLocationApp.isEmpty()) {
                LogBridge.w(TAG, "检测到模拟位置应用");
                return true;
            }
        } catch (Exception e) {

        }
        return false;
    }

    private static boolean isReleaseBuild() {
        try {

            String packageName = "com.tv.live";
            return !packageName.contains("debug");
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isDebugDetected() {
        return debugDetected;
    }

    public static String calculateMD5(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return null;

            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                md.update(buffer, 0, len);
            }
            fis.close();

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            LogBridge.e(TAG, "计算 MD5 失败: " + e.getMessage());
            return null;
        }
    }
}
