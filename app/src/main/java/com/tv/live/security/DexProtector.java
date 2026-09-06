package com.tv.live.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import com.tv.live.util.LogBridge;

import com.tv.live.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;

public final class DexProtector {

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
        LogBridge.e(TAG, "⚠️ 触发篡改检测: " + reason);

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
