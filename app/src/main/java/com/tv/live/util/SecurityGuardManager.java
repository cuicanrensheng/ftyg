package com.tv.live.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.provider.Settings;
import android.util.Base64;
import com.tv.live.util.LogBridge;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SecurityGuardManager {

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

        LogBridge.i(TAG, "安全检测完成: " + report.getSummary());

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
                String lowerFingerprint = fingerprint.toLowerCase();
                if (lowerFingerprint.contains("sdk_gphone") ||
                    lowerFingerprint.contains("emulator") ||
                    lowerFingerprint.contains("generic")) {
                    return true;
                }
            }

            String model = Build.MODEL;
            if (model != null && model.contains("Emulator")) {
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
