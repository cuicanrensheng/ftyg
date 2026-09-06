package com.tv.live;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import com.tv.live.util.LogBridge;
import android.widget.Toast;

import com.tv.live.BuildConfig;
import com.tv.live.security.IntegrityCheck;
import com.tv.live.security.SecurityCore;
import com.tv.live.security.TamperReporter;

import java.security.MessageDigest;

public final class SecurityCheck {

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
                LogBridge.w(TAG, "✅ dex 完整性校验通过");
            } else {

                LogBridge.w(TAG, "dex hash (人工对比) = " + currentB64);
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
