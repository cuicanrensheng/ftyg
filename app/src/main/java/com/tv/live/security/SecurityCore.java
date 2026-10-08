package com.tv.live.security;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;
import com.tv.live.BuildConfig;
import com.tv.live.util.LogBridge;
import com.tv.live.util.LogBridge.LogCollector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityCore {

    private static final String TAG = "SecurityCore";

    private static volatile boolean sLoaded = false;
    private static volatile byte[] sToken = null;

    private SecurityCore() {}

    private static final byte[] FIXED_TOKEN =
            "TVLiveSec!2026Se".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static synchronized void init() {
        if (sLoaded) return;
        try {
            System.loadLibrary("tvlive_security");
            sToken = FIXED_TOKEN.clone();
            nativeSetToken(sToken);
            sLoaded = true;
            LogBridge.i(TAG, "libtvlive_security.so 加载成功");
            LogBridge.i(TAG, "SECURITY_INIT: Native SO loaded OK");
        } catch (Throwable t) {
            sLoaded = false;
            LogBridge.w(TAG, "libtvlive_security.so 加载失败: " + t.getMessage() + "，将使用 Java fallback 解密");
            LogBridge.w(TAG, "SECURITY_INIT: Native SO FAILED, using Java fallback: " + t.getMessage());
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static void antiDebug() {
        if (sLoaded) {
            try { nativeAntiDebug(); } catch (Throwable ignored) {}
        }

        try {
            String status = readFile("/proc/self/status");
            if (status != null) {
                for (String line : status.split("\n")) {
                    if (line.startsWith("TracerPid:")) {
                        String v = line.substring(10).trim();
                        if (!"0".equals(v) && !v.isEmpty()) {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(9);
                            return;
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void startMonitor() {
        if (sLoaded) {
            try { nativeStartMonitor(); } catch (Throwable ignored) {}
        }
    }

    public static int check() {
        if (!sLoaded) return 0;
        try { return nativeCheck(); } catch (Throwable t) { return 0; }
    }

    public static String getSecurityStatus() {
        if (!sLoaded) return "SecurityCore not loaded";
        try { return nativeGetSecurityStatus(); } catch (Throwable t) { return "error: " + t.getMessage(); }
    }

    public static String decryptToString(String cipherB64) {
        if (cipherB64 == null) {
            LogBridge.w(TAG, "DECRYPT: cipherB64 is null");
            return null;
        }
        byte[] cipher;
        try {
            cipher = Base64.decode(cipherB64, Base64.NO_WRAP);
        } catch (Throwable t) {
            LogBridge.e(TAG, "DECRYPT: Base64 decode failed: " + t.getMessage());
            return null;
        }

        if (cipher == null || cipher.length < 32) {
            LogBridge.e(TAG, "DECRYPT: cipher invalid, len=" + (cipher != null ? cipher.length : "null"));
            return null;
        }

        // 【2026-09-24】init() 在后台线程 earlyUrl 中执行，若主线程先调用解密，
        // sLoaded 可能仍为 false。此处兜底：未加载则同步 init 一次再走 native。
        if (!sLoaded) {
            try { init(); } catch (Throwable ignored) {}
        }

        if (sLoaded) {
            try {
                byte[] plain = nativeDecrypt(cipher);
                if (plain != null) {
                    String s = new String(plain, StandardCharsets.UTF_8);
                    Arrays.fill(plain, (byte) 0);
                    LogBridge.d(TAG, "DECRYPT: Native decrypted OK, len=" + s.length());
                    return s;
                }
                LogBridge.w(TAG, "DECRYPT: Native decrypt returned null, trying Java fallback");
            } catch (Throwable t) {
                LogBridge.w(TAG, "Native 解密失败: " + t.getMessage() + "，尝试 Java fallback");
                LogBridge.e(TAG, "DECRYPT: Native decrypt exception: " + t.getMessage());
            }
        } else {
            LogBridge.w(TAG, "DECRYPT: Native not loaded, using Java fallback directly");
        }

        try {
            String s = javaAesDecrypt(cipher);
            if (s != null && !s.isEmpty()) {
                LogBridge.d(TAG, "DECRYPT: Java fallback decrypted OK, len=" + s.length());
                return s;
            }
            LogBridge.e(TAG, "Java fallback 解密也失败");
            LogBridge.e(TAG, "DECRYPT: Java fallback returned empty/null");
            return null;
        } catch (Throwable t) {
            LogBridge.e(TAG, "Java fallback 解密异常: " + t.getMessage());
            LogBridge.e(TAG, "DECRYPT: Java fallback exception: " + t.getMessage());
            return null;
        }
    }

    private static final byte[] KEY_PART_A = {
            (byte) 0x9c, (byte) 0x3f, (byte) 0xa1, (byte) 0x77,
            (byte) 0x55, (byte) 0x88, (byte) 0x10, (byte) 0xcc,
            (byte) 0x2d, (byte) 0x4b, (byte) 0xe6, (byte) 0x91,
            (byte) 0x07, (byte) 0xb3, (byte) 0xd5, (byte) 0x42
    };

    private static final byte[] KEY_PART_B = {
            (byte) 0x7a, (byte) 0xb1, (byte) 0x05, (byte) 0xe9,
            (byte) 0x33, (byte) 0x6f, (byte) 0xc2, (byte) 0x4d,
            (byte) 0x18, (byte) 0xfa, (byte) 0x82, (byte) 0x59,
            (byte) 0xa0, (byte) 0x21, (byte) 0x6c, (byte) 0xd7
    };

    private static byte[] buildAesKeyJava() {
        byte[] token = sToken != null ? sToken : FIXED_TOKEN;
        byte[] key = new byte[32];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (KEY_PART_A[i] ^ token[i]);
            key[16 + i] = (byte) (KEY_PART_B[i] ^ token[i]);
        }
        return key;
    }

    private static String javaAesDecrypt(byte[] cipher) throws Exception {
        if (cipher.length < 32 || (cipher.length - 16) % 16 != 0) {
            return null;
        }
        byte[] iv = new byte[16];
        System.arraycopy(cipher, 0, iv, 0, 16);
        byte[] encrypted = new byte[cipher.length - 16];
        System.arraycopy(cipher, 16, encrypted, 0, encrypted.length);

        byte[] key = buildAesKeyJava();
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipherObj = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipherObj.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        byte[] plain = cipherObj.doFinal(encrypted);
        Arrays.fill(key, (byte) 0);

        return new String(plain, StandardCharsets.UTF_8);
    }

    private static String readFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            byte[] buf = new byte[4096];
            int n = fis.read(buf);
            fis.close();
            return n > 0 ? new String(buf, 0, n, StandardCharsets.ISO_8859_1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static native void nativeSetToken(byte[] token);
    private static native void nativeAntiDebug();
    private static native void nativeStartMonitor();
    private static native int  nativeCheck();
    private static native byte[] nativeDecrypt(byte[] cipher);
    private static native String nativeGetSecurityStatus();

    public static class SecurityGuard {

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
        LogBridge.w(TAG, "⚠️ 检测到安全威胁: " + threatName + " - " + detail);

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

        // 【2026-09-24】威胁告警去重：首次检测打 warn，后续重复检测仅打 debug，
        // 避免模拟器环境下每 30 秒刷屏（Frida/Xposed/Root 在模拟器中恒为阳性）。
        final boolean[] reported = {false, false, false, false}; // frida, xposed, debugger, root

        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

            while (!sDestroyed && !BuildConfig.IS_DEBUG) {
                try {
                    Thread.sleep(30000);

                    if (checkFrida()) {
                        if (!reported[0]) { LogBridge.w(TAG, "检测到 Frida 工具"); reported[0] = true; }
                        else { LogBridge.d(TAG, "安全监控：Frida 仍存在（已报告，跳过重复告警）"); }
                        onThreatDetected(THREAT_FRIDA, "检测到 Frida");
                    }

                    if (checkXposed()) {
                        if (!reported[1]) { LogBridge.w(TAG, "检测到 Xposed 框架"); reported[1] = true; }
                        else { LogBridge.d(TAG, "安全监控：Xposed 仍存在（已报告，跳过重复告警）"); }
                        onThreatDetected(THREAT_XPOSED, "检测到 Xposed");
                    }

                    if (android.os.Debug.isDebuggerConnected()) {
                        if (!reported[2]) { LogBridge.w(TAG, "检测到调试器连接（仅记录，不上报）"); reported[2] = true; }
                        else { LogBridge.d(TAG, "安全监控：调试器仍连接（已报告，跳过重复告警）"); }
                    }

                    if (checkRoot()) {
                        if (!reported[3]) { LogBridge.w(TAG, "检测到 Root 环境（仅记录）"); reported[3] = true; }
                        else { LogBridge.d(TAG, "安全监控：Root 仍存在（已报告，跳过重复告警）"); }
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

    public static class DexProtector {

    private static final String TAG = "DexProtector";
    private static final String DEX_DIR = "dex_protected";
    private static final String DEX_SUFFIX = ".dex";
    private static final String ENCRYPTED_SUFFIX = ".enc";

    private static volatile boolean sInitialized = false;
    private static volatile long sDexLastModified = 0;
    private static volatile String sDexHash = null;

    private DexProtector() {}

    public static boolean init(Context context) {
        if (sInitialized) return true;
        sInitialized = true;

        if (!BuildConfig.IS_DEBUG) {
            try {
                protectDex(context);
                checkDexIntegrity(context);
                startDexMonitor(context);
                LogBridge.i(TAG, "DEX 保护初始化完成");
            } catch (Throwable e) {
                LogBridge.w(TAG, "DEX 保护初始化异常: " + e.getMessage());
            }
        }
        return true;
    }

    private static void protectDex(Context context) {
        try {

            String apkPath = context.getApplicationInfo().sourceDir;
            if (apkPath == null) return;

            File apkFile = new File(apkPath);
            sDexLastModified = apkFile.lastModified();

            sDexHash = computeFileHash(apkFile);
            LogBridge.i(TAG, "DEX hash: " + (sDexHash != null ? sDexHash.substring(0, 16) + "..." : "null"));

        } catch (Exception e) {
            LogBridge.e(TAG, "保护 DEX 失败: " + e.getMessage());
        }
    }

    private static void checkDexIntegrity(Context context) {
        try {
            String apkPath = context.getApplicationInfo().sourceDir;
            if (apkPath == null) return;

            File apkFile = new File(apkPath);

            if (sDexLastModified > 0 && apkFile.lastModified() != sDexLastModified) {
                LogBridge.e(TAG, "⚠️ APK 文件被修改! lastModified 已变化");
                triggerTamperDetected(context, "APK 文件被修改");
                return;
            }

            checkDexFile(context);

        } catch (Exception e) {
            LogBridge.e(TAG, "DEX 完整性检查失败: " + e.getMessage());
        }
    }

    private static void checkDexFile(Context context) {
        try {

            File dataDir = new File(context.getApplicationInfo().dataDir);
            if (dataDir.exists()) {
                checkDirectoryForHooks(dataDir);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                File codeCacheDir = new File(context.getCodeCacheDir().getAbsolutePath());
                if (codeCacheDir.exists()) {
                    checkDirectoryForHooks(codeCacheDir);
                }
            }

        } catch (Exception e) {
            LogBridge.w(TAG, "DEX 文件检查异常: " + e.getMessage());
        }
    }

    private static void checkDirectoryForHooks(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;

            String[] suspiciousNames = {
                "xposed", "frida", "substrate", "gameguardian",
                "lucky_patcher", " freedom", "creeper"
            };

            for (File file : files) {
                String name = file.getName().toLowerCase();
                for (String suspicious : suspiciousNames) {
                    if (name.contains(suspicious)) {
                        LogBridge.w(TAG, "检测到可疑文件: " + file.getAbsolutePath());
                        break;
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    private static void startDexMonitor(Context context) {
        if (!BuildConfig.IS_DEBUG) {
            new Thread(() -> {
                int checks = 0;
                while (sInitialized && !BuildConfig.IS_DEBUG) {
                    try {
                        Thread.sleep(5000);
                        checks++;

                        if (checks % 30 == 0) {
                            checkDexIntegrity(context);
                        }

                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable e) {
                        LogBridge.e(TAG, "DEX 监控异常: " + e.getMessage());
                    }
                }
            }, "DexMonitor").start();
        }
    }

    private static void triggerTamperDetected(Context context, String reason) {
        LogBridge.w(TAG, "⚠️ 触发篡改检测: " + reason);

        try {
            SecurityGuard.onThreatDetected(SecurityGuard.TAMPER_DETECTED, reason);
        } catch (Throwable ignored) {}
    }

    public static String computeFileHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            fis.close();
            byte[] hash = digest.digest();
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object[] getDexObjects() {
        try {
            ClassLoader classLoader = DexProtector.class.getClassLoader();
            if (classLoader == null) return null;

            Field pathListField = findField(classLoader.getClass(), "pathList");
            if (pathListField == null) return null;

            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) return null;

            Field dexElementsField = findField(pathList.getClass(), "dexElements");
            if (dexElementsField == null) return null;

            dexElementsField.setAccessible(true);
            Object dexElements = dexElementsField.get(pathList);
            if (dexElements == null) return null;

            int length = Array.getLength(dexElements);
            Object[] result = new Object[length];
            for (int i = 0; i < length; i++) {
                result[i] = Array.get(dexElements, i);
            }
            return result;

        } catch (Exception e) {
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> searchType = clazz;
        while (searchType != null) {
            try {
                return searchType.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                searchType = searchType.getSuperclass();
            }
        }
        return null;
    }

    public static String getDexHash() {
        return sDexHash;
    }

    public static long getDexLastModified() {
        return sDexLastModified;
    }
}

    public static class AntiDebug {

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
            if (fingerprint != null) {
                String lower = fingerprint.toLowerCase(Locale.ROOT);
                if (lower.startsWith("generic")
                        || lower.contains("sdk_gphone")
                        || lower.contains("vbox")
                        || lower.contains("emulator")) {
                    LogBridge.w(TAG, "检测到模拟器指纹: " + fingerprint);
                    return true;
                }
            }

            String hardware = android.os.Build.HARDWARE;
            if (hardware != null) {
                String h = hardware.toLowerCase(Locale.ROOT);
                if (h.contains("goldfish") || h.contains("ranchu")) {
                    LogBridge.w(TAG, "检测到模拟器硬件: " + hardware);
                    return true;
                }
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
            if (mockLocationApp != null && mockLocationApp.equals("1")) {
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

    public static class IntegrityCheck {

    private IntegrityCheck() {}

    public static byte[] computeApkHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(new File(apkPath));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            fis.close();
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }

    public static byte[] computeDexHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
            java.util.zip.ZipEntry entry = zf.getEntry("classes.dex");
            if (entry == null) { zf.close(); return null; }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            java.io.InputStream is = zf.getInputStream(entry);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            is.close();
            zf.close();
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }
}

    public static class StringObfuscator {

    private static final String TAG = "StrObfuscator";

    private static final Map<String, String> stringCache = new HashMap<>();

    private static final int XOR_KEY = 0x3C;
    private static final int XOR_KEY_2 = 0xA5;

    public static String encodeString(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            c ^= XOR_KEY;
            c ^= XOR_KEY_2;
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }

    public static String decodeString(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;

        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }

        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);

                c ^= XOR_KEY_2;
                c ^= XOR_KEY;
                sb.append(c);
            }

            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            LogBridge.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }

    public static String simpleEncode(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c >= 'a' && c <= 'z') {
                c = (char) ((c - 'a' + 13) % 26 + 'a');
            } else if (c >= 'A' && c <= 'Z') {
                c = (char) ((c - 'A' + 13) % 26 + 'A');
            } else if (c >= '0' && c <= '9') {
                c = (char) ((c - '0' + 5) % 10 + '0');
            }
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }

    public static String simpleDecode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;

        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }

        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);

                if (c >= 'a' && c <= 'z') {
                    c = (char) ((c - 'a' - 13 + 26) % 26 + 'a');
                } else if (c >= 'A' && c <= 'Z') {
                    c = (char) ((c - 'A' - 13 + 26) % 26 + 'A');
                } else if (c >= '0' && c <= '9') {
                    c = (char) ((c - '0' - 5 + 10) % 10 + '0');
                }
                sb.append(c);
            }

            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            LogBridge.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }

    public static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }

    public static void clearCache() {
        stringCache.clear();
    }

    public static String encodeInt(int value) {
        return encodeString(String.valueOf(value));
    }

    public static int decodeInt(String encoded) {
        String decoded = decodeString(encoded);
        if (decoded == null) return -1;
        try {
            return Integer.parseInt(decoded);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

    public static class StringProtector {

    private static final String TAG = "StringProtector";
    private static volatile boolean sEnabled = false;

    private static final Map<String, String> sEncryptedCache = new HashMap<>();

    private static final Map<String, String> sDecryptedCache = new HashMap<>();

    private static volatile byte[] sDynamicKey = null;

    private static final int XOR_KEY_LENGTH = 16;

    private StringProtector() {}

    public static void init(boolean enable) {
        sEnabled = enable;
        if (enable) {

            sDynamicKey = generateDynamicKey();
            LogBridge.i(TAG, "字符串保护已启用");
        } else {
            LogBridge.i(TAG, "字符串保护已禁用（调试模式）");
        }
    }

    public static void register(String key, String encryptedValue) {
        if (key == null || encryptedValue == null) return;
        sEncryptedCache.put(key, encryptedValue);

        sDecryptedCache.remove(key);
    }

    public static void register(String key, String value, boolean encrypt) {
        if (key == null || value == null) return;

        if (encrypt && sEnabled) {
            String encrypted = encryptValue(value);
            sEncryptedCache.put(key, encrypted);
            sDecryptedCache.remove(key);
        } else {

            sDecryptedCache.put(key, value);
        }
    }

    public static String get(String key) {
        if (key == null) return null;

        String cached = sDecryptedCache.get(key);
        if (cached != null) return cached;

        String encrypted = sEncryptedCache.get(key);
        if (encrypted != null) {
            try {
                String decrypted = decryptValue(encrypted);

                if (decrypted != null) {
                    sDecryptedCache.put(key, decrypted);
                }
                return decrypted;
            } catch (Exception e) {
                LogBridge.e(TAG, "解密字符串失败: " + key);
                return null;
            }
        }

        return null;
    }

    public static String[] getBatch(String... keys) {
        if (keys == null) return null;
        String[] result = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = get(keys[i]);
        }
        return result;
    }

    private static String encryptValue(String plain) {
        if (plain == null) return null;
        try {
            byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBytes = xorEncrypt(plainBytes, sDynamicKey);
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            LogBridge.w(TAG, "加密失败: " + e.getMessage());
            return plain;
        }
    }

    private static String decryptValue(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        try {
            byte[] encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            byte[] decryptedBytes = xorDecrypt(encryptedBytes, sDynamicKey);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LogBridge.e(TAG, "解密失败: " + e.getMessage());
            return null;
        }
    }

    private static byte[] xorEncrypt(byte[] data, byte[] key) {
        if (data == null || key == null) return null;
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }

    private static byte[] xorDecrypt(byte[] data, byte[] key) {

        return xorEncrypt(data, key);
    }

    private static byte[] generateDynamicKey() {
        try {

            String seed = "TVLive_" + System.currentTimeMillis() + "_" +
                         android.os.Build.VERSION.SDK_INT;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[XOR_KEY_LENGTH];
            System.arraycopy(hash, 0, key, 0, XOR_KEY_LENGTH);
            return key;
        } catch (Exception e) {

            return "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        }
    }

    public static void clearCache() {
        sDecryptedCache.clear();
    }

    public static void rotateKey() {
        if (!sEnabled) return;
        sDynamicKey = generateDynamicKey();
        clearCache();
        LogBridge.i(TAG, "动态密钥已更新");
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static int getRegisteredCount() {
        return sEncryptedCache.size();
    }

    public static String quickEncrypt(String plain) {
        if (plain == null) return null;
        byte[] key = "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = new byte[plainBytes.length];
        for (int i = 0; i < plainBytes.length; i++) {
            encryptedBytes[i] = (byte) (plainBytes[i] ^ key[i % key.length]);
        }
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
    }

    public static String quickDecrypt(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        byte[] key = "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP);
        byte[] decryptedBytes = new byte[encryptedBytes.length];
        for (int i = 0; i < encryptedBytes.length; i++) {
            decryptedBytes[i] = (byte) (encryptedBytes[i] ^ key[i % key.length]);
        }
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}

    public static class TamperReporter {

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
        LogBridge.w(TAG, "⚠️ 检测到篡改行为: " + typeName + " - " + detail);

        LogCollector.getInstance().error(TAG,
            "⚠️ 反编译检测: " + typeName + " | " + detail);

        reportToLocalMonitor(tamperType, typeName, detail);

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

    /**
     * 第三方 SDK native 库加载的统一安全入口.
     *
     * <p>由 buildSrc ASM 插桩（OptionalNativeLoadClassVisitorFactory，scope=ALL）自动把
     * 所有第三方类（非 com.tv.live.*）里的 {@code System.loadLibrary(name)} 重定向到本方法，
     * App 自身代码的调用保持原样以保留诊断语义（见 HuyaSDKParser.checkMarsNativeLibraries）。</p>
     *
     * <p>设计安全目标：APK 通过 packagingOptions 全局排除了 libc++_shared.so /
     * libstlport_shared.so（包内 libmarsstn 为 hook stub、libtvlive_security 为自有库，
     * 均不 DT_NEEDED 任何 C++ 运行时库）。第三方 SDK（历史上 MMKV/Mars/Xlog）的静态块
     * 常无守卫地显式 loadLibrary 这些运行时库，在系统不自带它们的设备上会抛
     * UnsatisfiedLinkError → ExceptionInInitializerError 导致整个类初始化失败。</p>
     *
     * <p>策略：
     * <ul>
     *   <li>白名单内的"可选 C++ 运行时库"：尝试加载，失败静默吞掉——显式加载它们本就是
     *       SDK 为满足后续 .so 的 DT_NEEDED 而预热；包内无库 DT_NEEDED 它们，缺失零影响；
     *       若未来引入真实 C++ 库真正依赖它们，该库自身 dlopen 时仍会明确报错（而非类初始化
     *             阶段以迷惑性的 ExceptionInInitializerError 崩溃），届时应把运行时库加回 APK。</li>
     *   <li>其他库名：原样调用 System.loadLibrary，完整保留原始错误抛出语义。</li>
     * </ul></p>
     */
    public static class NativeLibraryCompat {

    /**
     * APK 中确定不提供、且包内现存 SO 均不依赖的 C++ 运行时库。
     * 必须与 app/build.gradle packagingOptions.jniLibs.excludes 保持一致。
     */
    private static final Set<String> OPTIONAL_RUNTIME_LIBS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("c++_shared", "stlport_shared")));

    private NativeLibraryCompat() {
    }

    /**
     * ASM 重定向入口。签名与 {@link System#loadLibrary(String)} 完全一致，
     * 插桩时只改 invoke 指令的 owner，不动寄存器与参数。
     */
    public static void loadLibrarySafe(String name) {
        if (name != null && OPTIONAL_RUNTIME_LIBS.contains(name)) {
            try {
                System.loadLibrary(name);
            } catch (Throwable ignored) {
                // 运行时库缺失：包内无任何 SO DT_NEEDED 它，属于预期情况，静默处理
            }
            return;
        }
        System.loadLibrary(name);
    }
}

    public static class SecurityCheck {

    private static final String TAG = "SecChk";
    private static final String EXPECTED_PKG = "com.tv.live";

    private static final String EXPECTED_DEX_B64 = "REPLACE_WITH_DEX_SHA256_BASE64";

    private static final String EXPECTED_SIG_BASE64 = "xQdedEk3xbKAsqg0WqDdH0qmjiYAkARaVVtrTVXdQAQ=";

    private SecurityCheck() {}

    public static boolean verifyOnStart(Context ctx) {
        if (BuildConfig.IS_DEBUG) {
            LogBridge.i(TAG, "🔓 调试版：跳过签名校验");
            return true;
        }

        LogBridge.i(TAG, "🔒 正式版：启用签名校验");

        try {
            TamperReporter.init(ctx);
        } catch (Throwable t) {
            LogBridge.w(TAG, "TamperReporter 初始化失败: " + t.getMessage());
        }

        boolean allPassed = true;

        if (!verifySignature(ctx)) {
            LogBridge.e(TAG, "⚠️ 签名校验未通过，将上报但不阻断启动");
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_SIGNATURE,
                    "签名校验失败"
                );
            } catch (Throwable t) {
                LogBridge.w(TAG, "篡改上报失败: " + t.getMessage());
            }
            allPassed = false;
        }

        String pkgName = ctx.getPackageName();
        if (!EXPECTED_PKG.equals(pkgName)) {
            LogBridge.e(TAG, "❌ 包名不匹配! expected=" + EXPECTED_PKG + " current=" + pkgName);
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_PACKAGE_NAME,
                    "包名校验失败, expected=" + EXPECTED_PKG + " current=" + pkgName
                );
            } catch (Throwable t) {
                LogBridge.w(TAG, "篡改上报失败: " + t.getMessage());
            }
            allPassed = false;
        } else {
            LogBridge.i(TAG, "✅ 包名校验通过");
        }

        if (!verifyDexIntegrity(ctx)) {
            LogBridge.e(TAG, "⚠️ DEX 完整性校验未通过");
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_DEX_INTEGRITY,
                    "DEX完整性校验失败"
                );
            } catch (Throwable t) {
                LogBridge.w(TAG, "篡改上报失败: " + t.getMessage());
            }
            allPassed = false;
        }

        if (!allPassed) {
            LogBridge.w(TAG, "⚠️ 部分安全检查未通过，但应用将继续运行（降级模式）");
        }
        return allPassed;
    }

    private static boolean verifySignature(Context appCtx) {
        try {
            PackageManager pm = appCtx.getPackageManager();
            PackageInfo pi;
            byte[] certBytes;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] sigs = (pi.signingInfo != null && pi.signingInfo.hasMultipleSigners())
                        ? pi.signingInfo.getApkContentsSigners()
                        : (pi.signingInfo != null ? pi.signingInfo.getSigningCertificateHistory() : null);
                if (sigs == null || sigs.length == 0) {
                    LogBridge.w(TAG, "未找到签名");
                    return false;
                }
                certBytes = sigs[0].toByteArray();
            } else {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNATURES);
                Signature[] sigs = pi.signatures;
                if (sigs == null || sigs.length == 0) {
                    LogBridge.w(TAG, "未找到签名");
                    return false;
                }
                certBytes = sigs[0].toByteArray();
            }

            byte[] shaBytes = MessageDigest.getInstance("SHA-256").digest(certBytes);
            String currentB64 = Base64.encodeToString(shaBytes, Base64.NO_WRAP);
            LogBridge.i(TAG, "当前签名 SHA256=" + currentB64);

            if (!EXPECTED_SIG_BASE64.equals(currentB64)) {
                LogBridge.e(TAG, "❌ 签名校验失败! expected=" + EXPECTED_SIG_BASE64 + " current=" + currentB64);
                toastAndExit(appCtx, "签名校验失败，APK 被修改");
                return false;
            }
            LogBridge.i(TAG, "✅ 签名校验通过");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "verify error", e);
            return false;
        }
    }

    private static boolean verifyDexIntegrity(Context appCtx) {

        if ("REPLACE_WITH_DEX_SHA256_BASE64".equals(EXPECTED_DEX_B64)) {
            return true;
        }
        try {
            byte[] hash = IntegrityCheck.computeDexHash(appCtx);
            if (hash == null) return true;
            String currentB64 = Base64.encodeToString(hash, Base64.NO_WRAP);
            LogBridge.i(TAG, "EXPECTED_DEX_SHA256=" + currentB64);
            if (!"REPLACE_WITH_DEX_SHA256_BASE64".equals(EXPECTED_DEX_B64)) {

                if (!EXPECTED_DEX_B64.equals(currentB64)) {
                    LogBridge.e(TAG, "dex hash 不匹配！expected=" + EXPECTED_DEX_B64 + " current=" + currentB64);
                    return false;
                }
                LogBridge.i(TAG, "✅ dex 完整性校验通过");
            } else {

                LogBridge.d(TAG, "dex hash (人工对比) = " + currentB64);
            }
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "verify dex error", e);
            return true;
        }
    }

    private static void toastAndExit(Context ctx, String msg) {
        LogBridge.e(TAG, msg);
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                }, 1500);
    }
}

    public static class SecurityGuardManager {

    private static final String TAG = "SecurityGuard";
    private static final String PREFS_NAME = "security_guard";
    private static final String KEY_APP_SIGNATURE = "app_signature";
    private static final String KEY_LAST_CHECK = "last_check_time";

    private static volatile SecurityGuardManager sInstance;
    private Context mContext;
    private boolean mInitialized = false;

    private final SecurityReport mLastReport = new SecurityReport();

    private String mExpectedSignatureHash;

    public static class SecurityReport {
        public boolean debugDetected = false;
        public boolean fridaDetected = false;
        public boolean xposedDetected = false;
        public boolean rootDetected = false;
        public boolean hookDetected = false;
        public boolean integrityOk = true;
        public boolean proxyDetected = false;
        public boolean vpnDetected = false;
        public boolean emulatorDetected = false;
        public boolean usbDebuggingEnabled = false;
        public long checkTime = 0;
        public List<String> threats = new ArrayList<>();

        public boolean hasAnyThreat() {
            return !threats.isEmpty();
        }

        public String getSummary() {
            if (!hasAnyThreat()) {
                return "安全检测通过";
            }
            StringBuilder sb = new StringBuilder("检测到安全威胁:\n");
            for (String threat : threats) {
                sb.append("⚠️ ").append(threat).append("\n");
            }
            return sb.toString();
        }

        public void update(SecurityReport source) {
            if (source == null) return;
            this.debugDetected = source.debugDetected;
            this.fridaDetected = source.fridaDetected;
            this.xposedDetected = source.xposedDetected;
            this.rootDetected = source.rootDetected;
            this.hookDetected = source.hookDetected;
            this.integrityOk = source.integrityOk;
            this.proxyDetected = source.proxyDetected;
            this.vpnDetected = source.vpnDetected;
            this.emulatorDetected = source.emulatorDetected;
            this.usbDebuggingEnabled = source.usbDebuggingEnabled;
            this.checkTime = source.checkTime;
            this.threats = new ArrayList<>(source.threats);
        }
    }

    private SecurityGuardManager() {
    }

    public static SecurityGuardManager getInstance() {
        if (sInstance == null) {
            synchronized (SecurityGuardManager.class) {
                if (sInstance == null) {
                    sInstance = new SecurityGuardManager();
                }
            }
        }
        return sInstance;
    }

    public void init(Context context) {
        mContext = context.getApplicationContext();
        mExpectedSignatureHash = getAppSignatureHash();

        if (mExpectedSignatureHash != null) {
            mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP_SIGNATURE, mExpectedSignatureHash)
                .apply();
        }

        mInitialized = true;
        LogBridge.i(TAG, "安全管理器初始化完成，签名哈希: " +
            (mExpectedSignatureHash != null ? mExpectedSignatureHash.substring(0, 16) + "..." : "unknown"));
    }

    public SecurityReport performFullSecurityCheck() {
        if (!mInitialized) {
            init(mContext);
        }

        SecurityReport report = new SecurityReport();
        report.checkTime = System.currentTimeMillis();

        checkDebugger(report);

        checkHookingFrameworks(report);

        checkRootAccess(report);

        checkApkIntegrity(report);

        checkRuntimeEnvironment(report);

        if (report.hasAnyThreat()) {
            LogBridge.w(TAG, "安全检测完成: " + report.getSummary());
        } else {
            LogBridge.i(TAG, "安全检测完成: " + report.getSummary());
        }

        mLastReport.update(report);
        return report;
    }

    private void checkDebugger(SecurityReport report) {

        try {
            if (Debug.isDebuggerConnected()) {
                report.debugDetected = true;
                report.threats.add("检测到调试器");
                LogBridge.w(TAG, "⚠️ 调试器已附加");
                return;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "调试检测异常: " + e.getMessage());
        }

        try {
            String tracerPid = readProcStatus("TracerPid:");
            if (tracerPid != null) {
                int pid = Integer.parseInt(tracerPid.trim());
                if (pid > 0) {
                    report.debugDetected = true;
                    report.threats.add("检测到调试器(TracerPid=" + pid + ")");
                    LogBridge.w(TAG, "⚠️ TracerPid=" + pid);
                }
            }
        } catch (Exception e) {

        }

        try {
            ApplicationInfo appInfo = mContext.getApplicationInfo();
            boolean isDebuggable = (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (isDebuggable && !Build.TAGS.equals("release-keys")) {

                LogBridge.d(TAG, "应用为可调试版本 (debuggable=true)");
            }
        } catch (Exception e) {

        }
    }

    private void checkHookingFrameworks(SecurityReport report) {

        if (isFridaPortOpen()) {
            report.fridaDetected = true;
            report.hookDetected = true;
            report.threats.add("检测到 Frida 框架");
            LogBridge.w(TAG, "⚠️ Frida 端口已开启");
        }

        if (isXposedFrameworkDetected()) {
            report.xposedDetected = true;
            report.hookDetected = true;
            report.threats.add("检测到 Xposed/Substrate 框架");
            LogBridge.w(TAG, "⚠️ Xposed 框架已加载");
        }

        if (isMagiskDetected()) {
            report.hookDetected = true;
            report.threats.add("检测到 Magisk 模块");
            LogBridge.w(TAG, "⚠️ Magisk 已安装");
        }

        if (detectSuspiciousLibraries()) {
            report.hookDetected = true;
            report.threats.add("检测到可疑的本地库注入");
            LogBridge.w(TAG, "⚠️ 检测到可疑库");
        }
    }

    private boolean isFridaPortOpen() {
        int[] fridaPorts = {27042, 27043, 37177};
        for (int port : fridaPorts) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
                socket.close();
                return true;
            } catch (Exception e) {

            }
        }
        return false;
    }

    private boolean isXposedFrameworkDetected() {
        try {

            Class.forName("de.robv.android.xposed.XposedBridge", false, null);
            return true;
        } catch (ClassNotFoundException e) {

        }

        String[] suspiciousFiles = {
            "/system/lib/libxposed_art.so",
            "/system/lib64/libxposed_art.so",
            "/system/framework/XposedBridge.jar",
            "/data/local/tmp/xposed",
            "/data/local/tmp/frida-server"
        };

        for (String path : suspiciousFiles) {
            if (new File(path).exists()) {
                return true;
            }
        }

        try {
            java.util.Enumeration<java.net.URL> libraries = ClassLoader.getSystemClassLoader()
                .getSystemResources("META-INF/xposed/init");
            if (libraries.hasMoreElements()) {
                return true;
            }
        } catch (Exception e) {

        }

        return false;
    }

    private boolean isMagiskDetected() {
        String[] magiskPaths = {
            "/sbin/magisk",
            "/system/bin/magisk",
            "/data/adb/magisk",
            "/data/adb/modules"
        };

        for (String path : magiskPaths) {
            if (new File(path).exists()) {
                return true;
            }
        }

        try {
            String magiskPath = System.getenv("MAGISK_PATH");
            if (magiskPath != null && !magiskPath.isEmpty()) {
                return true;
            }
        } catch (Exception e) {

        }

        return false;
    }

    private boolean detectSuspiciousLibraries() {
        try {

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/maps"));
            String line;
            Set<String> suspiciousNames = new HashSet<>();
            suspiciousNames.add("frida");
            suspiciousNames.add("xposed");
            suspiciousNames.add("substrate");
            suspiciousNames.add("gameguardian");
            suspiciousNames.add("lucky_patcher");
            suspiciousNames.add("magisk");

            while ((line = reader.readLine()) != null) {
                for (String name : suspiciousNames) {
                    if (line.toLowerCase().contains(name)) {
                        reader.close();
                        return true;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {

        }
        return false;
    }

    private void checkRootAccess(SecurityReport report) {

        String[] suPaths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-initialization/recurse.sh",
            "/system/etc/init.d/99startfdroid",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        };

        boolean suFound = false;
        for (String path : suPaths) {
            if (new File(path).exists()) {
                suFound = true;
                break;
            }
        }

        if (suFound || isTestKeyBuild() || hasWriteAccess("/system")) {
            report.rootDetected = true;
            report.threats.add("检测到 Root 权限");
            LogBridge.w(TAG, "⚠️ 检测到 Root 环境");
        }
    }

    private boolean isTestKeyBuild() {
        return Build.TAGS != null && Build.TAGS.contains("test-keys");
    }

    private boolean hasWriteAccess(String path) {
        try {
            File testFile = new File(path, ".security_test");
            return testFile.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    private void checkApkIntegrity(SecurityReport report) {

        String currentSignature = getAppSignatureHash();
        if (currentSignature == null) {
            report.integrityOk = false;
            report.threats.add("无法获取应用签名");
            LogBridge.e(TAG, "❌ 无法获取应用签名");
            return;
        }

        String storedSignature = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_SIGNATURE, null);

        if (storedSignature != null && !currentSignature.equals(storedSignature)) {
            report.integrityOk = false;
            report.threats.add("应用签名已变更（可能被二次打包）");
            LogBridge.e(TAG, "❌ 签名不匹配! 当前: " + currentSignature.substring(0, 16) +
                "... 存储: " + storedSignature.substring(0, 16) + "...");
        }

        if (isTestKeyBuild()) {
            report.integrityOk = false;
            report.threats.add("使用测试密钥构建");
            LogBridge.w(TAG, "⚠️ 应用使用测试密钥");
        }
    }

    private String getAppSignatureHash() {
        try {
            PackageInfo packageInfo = mContext.getPackageManager().getPackageInfo(
                mContext.getPackageName(), PackageManager.GET_SIGNATURES);

            Signature[] signatures = packageInfo.signatures;
            if (signatures != null && signatures.length > 0) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(signatures[0].toByteArray());
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "获取签名失败: " + e.getMessage());
        }
        return null;
    }

    private void checkRuntimeEnvironment(SecurityReport report) {

        if (isProxyConfigured()) {
            report.proxyDetected = true;
            report.threats.add("检测到代理服务器");
            LogBridge.w(TAG, "⚠️ 检测到代理配置");
        }

        if (isVpnActive()) {
            report.vpnDetected = true;
            report.threats.add("检测到 VPN 连接");
            LogBridge.w(TAG, "⚠️ 检测到 VPN");
        }

        if (isEmulator()) {
            report.emulatorDetected = true;
            report.threats.add("运行在模拟器环境");
            LogBridge.d(TAG, "ℹ️ 应用运行在模拟器上");
        }

        if (isUsbDebuggingEnabled()) {
            report.usbDebuggingEnabled = true;
            report.threats.add("USB 调试已开启");
            LogBridge.w(TAG, "⚠️ USB 调试已开启");
        }
    }

    private boolean isProxyConfigured() {
        try {

            String proxyHost = Settings.Global.getString(
                mContext.getContentResolver(), Settings.Global.HTTP_PROXY);
            if (proxyHost != null && !proxyHost.isEmpty()) {
                return true;
            }

            try {
                Class<?> connectivityManagerClass = Class.forName("android.net.ConnectivityManager");

            } catch (Exception e) {

            }
        } catch (Exception e) {

        }
        return false;
    }

    private boolean isVpnActive() {
        try {

            java.net.NetworkInterface.getNetworkInterfaces();
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.getName().startsWith("tun") ||
                    iface.getName().startsWith("ppp") ||
                    iface.getName().startsWith("vpn")) {
                    return true;
                }
            }
        } catch (Exception e) {

        }
        return false;
    }

    private boolean isEmulator() {

        String[] emulatorFiles = {
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props"
        };

        for (String path : emulatorFiles) {
            if (new File(path).exists()) {
                return true;
            }
        }

        try {
            String fingerprint = Build.FINGERPRINT;
            if (fingerprint != null) {
                String lowerFingerprint = fingerprint.toLowerCase(Locale.ROOT);
                if (lowerFingerprint.startsWith("generic")
                        || lowerFingerprint.contains("sdk_gphone")
                        || lowerFingerprint.contains("emulator")) {
                    return true;
                }
            }

            String hardware = Build.HARDWARE;
            if (hardware != null) {
                String h = hardware.toLowerCase(Locale.ROOT);
                if (h.contains("goldfish") || h.contains("ranchu")) {
                    return true;
                }
            }

            String model = Build.MODEL;
            if (model != null && model.toLowerCase(Locale.ROOT).contains("emulator")) {
                return true;
            }
        } catch (Exception e) {

        }

        return false;
    }

    private boolean isUsbDebuggingEnabled() {
        try {
            int adbEnabled = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0);
            return adbEnabled == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private String readProcStatus(String key) {
        try {
            int pid = android.os.Process.myPid();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    reader.close();
                    return line.substring(key.length()).trim();
                }
            }
            reader.close();
        } catch (Exception e) {

        }
        return null;
    }

    public SecurityReport getLastReport() {
        return mLastReport;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public boolean shouldBlockExecution(boolean blockRoot, boolean blockDebug, boolean blockHooking) {
        SecurityReport report = mLastReport;

        if (blockRoot && report.rootDetected) {
            LogBridge.e(TAG, "❌ Root 环境被禁止运行");
            return true;
        }

        if (blockDebug && (report.debugDetected || report.hookDetected)) {
            LogBridge.e(TAG, "❌ 调试/Hook 环境被禁止运行");
            return true;
        }

        if (blockHooking && report.hookDetected) {
            LogBridge.e(TAG, "❌ Hook 框架被禁止运行");
            return true;
        }

        return false;
    }
}
}