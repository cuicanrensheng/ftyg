package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CacheManager {

    private static final String CACHE_DIR = "tv_cache";
    private static final long CACHE_VALID_TIME = 24 * 60 * 60 * 1000;
    private static final String SP_NAME = "tv_cache_sp";
    private static final String KEY_LAST_PLAY_URL = "last_play_url";
    private static final String KEY_LAST_PLAY_NAME = "last_play_name";
    private static final String KEY_LAST_PLAY_INDEX = "last_play_index";

    private static final long MAX_CACHE_SIZE = 20 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    private Context context;
    private SharedPreferences sp;
    private static CacheManager instance;

    public static CacheManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new CacheManager(ctx);
        }
        return instance;
    }

    private CacheManager(Context ctx) {
        context = ctx.getApplicationContext();
        sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public long saveFileCache(String key, InputStream is) {
        if (is == null) {
            return -1;
        }
        trimCacheIfNeeded();

        File cacheFile = getCacheFile(key);

        try {
            if (cacheFile.exists()) {

                cacheFile.delete();
            }
        } catch (Throwable ignored) {}

        FileOutputStream fos = null;
        long totalBytes = 0;

        try {
            fos = new FileOutputStream(cacheFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            fos.flush();

            if (totalBytes <= 0) {
                try { fos.close(); fos = null; } catch (Throwable ignored) {}

                cacheFile.delete();
                return -1;
            }
            return totalBytes;

        } catch (IOException e) {
            e.printStackTrace();

            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            fos = null;

            cacheFile.delete();
            return -1;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }

            try { trimCacheIfNeeded(); } catch (Throwable ignored) {}
        }
    }

    public InputStream getFileCacheStream(String key) {
        File cacheFile = getCacheFile(key);
        if (!cacheFile.exists()) {
            return null;
        }

        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_VALID_TIME) {
            return null;
        }

        try {
            return new FileInputStream(cacheFile);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getFileCache(String key) {
        File cacheFile = getCacheFile(key);
        if (!cacheFile.exists()) {
            return null;
        }

        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_VALID_TIME) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile)));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    public void saveFileCache(String key, String content) {
        if (TextUtils.isEmpty(content)) {
            return;
        }

        File cacheFile = getCacheFile(key);

        try {
            if (cacheFile.exists()) {

                cacheFile.delete();
            }
        } catch (Throwable ignored) {}

        FileOutputStream fos = null;
        boolean success = false;
        try {
            fos = new FileOutputStream(cacheFile);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            success = true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
            if (!success) {

                cacheFile.delete();
            } else {
                try { trimCacheIfNeeded(); } catch (Throwable ignored) {}
            }
        }
    }

    public File getCacheFile(String key) {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return new File(cacheDir, key + ".cache");
    }

    private void trimCacheIfNeeded() {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists() || !cacheDir.isDirectory()) return;

        long totalSize = getCacheDirSize(cacheDir);
        if (totalSize < MAX_CACHE_SIZE) return;

        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        long targetSize = MAX_CACHE_SIZE / 2;
        long currentSize = totalSize;
        for (File file : files) {
            if (currentSize <= targetSize) break;
            if (file.delete()) {
                currentSize -= file.length();
            }
        }
    }

    private long getCacheDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    size += getCacheDirSize(file);
                } else {
                    size += file.length();
                }
            }
        }
        return size;
    }

    public long getCacheTotalSize() {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        return getCacheDirSize(cacheDir);
    }

    public String getCacheTotalSizeReadable() {
        long size = getCacheTotalSize();
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.2f KB", size / 1024.0);
        return String.format(Locale.ROOT, "%.2f MB", size / (1024.0 * 1024));
    }

    public boolean isCacheValid(String key) {
        File cacheFile = getCacheFile(key);
        if (!cacheFile.exists()) {
            return false;
        }
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        return age <= CACHE_VALID_TIME;
    }

    public void saveLastPlay(String url, String name, int index) {
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(KEY_LAST_PLAY_URL, url);
        editor.putString(KEY_LAST_PLAY_NAME, name);
        editor.putInt(KEY_LAST_PLAY_INDEX, index);
        editor.apply();
    }

    public String getLastPlayUrl() {
        return sp.getString(KEY_LAST_PLAY_URL, "");
    }

    public String getLastPlayName() {
        return sp.getString(KEY_LAST_PLAY_NAME, "");
    }

    public int getLastPlayIndex() {
        return sp.getInt(KEY_LAST_PLAY_INDEX, 0);
    }

    public void clearAllFileCache() {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    public void clearAll() {
        clearAllFileCache();
        sp.edit().clear().apply();
    }

    public static class HuyaCacheGovernor {

    private static final String TAG = "HuyaCacheGov";

    private static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    private static final long TARGET_TOTAL_BYTES = 15L * 1024 * 1024;

    private static final long MIN_AGE_MS = 2 * 60 * 60 * 1000L;

    private static final long EXPIRE_MS = 7L * 24 * 60 * 60 * 1000L;

    private static final String[] LOG_EXT = {".xlog", ".log", ".txt", ".bak", ".trace"};
    private static final String[] CRASH_DIR_HINTS = {"crash", "tombstone", "dump", "anr", "dropbox", "core"};

    public static void startupCleanup(final Context ctx) {
        if (ctx == null) return;

        new Thread(() -> {
            try {
                performCleanup(ctx);
            } catch (Throwable t) {
                LogBridge.w(TAG, "startupCleanup failed: " + t.getMessage());
            }
        }, "HuyaCacheCleanup").start();
    }


    private static void performCleanup(Context ctx) {
        long start = System.currentTimeMillis();
        List<File> candidates = collectHuyaCandidateDirs(ctx);

        long beforeBytes = 0L;
        List<FileEntry> all = new ArrayList<>();
        for (File root : candidates) beforeBytes += walkAndCollect(root, all, true);

        LogBridge.i(TAG, "扫描到 SDK 候选目录 " + candidates.size() + " 个, 共 " + all.size()
                + " 个文件, 当前占用 = " + human(beforeBytes));

        long deletedStep1 = 0L;
        for (FileEntry e : all) {
            boolean shouldDelete = false;
            if (isLogOrCrashFile(e.file)) shouldDelete = true;
            long age = System.currentTimeMillis() - e.file.lastModified();
            if (age > EXPIRE_MS) shouldDelete = true;

            if (shouldDelete && age > MIN_AGE_MS) {
                if (e.file.delete()) deletedStep1 += e.size;
            }
        }

        all.clear();
        long afterStep1 = 0L;
        for (File root : candidates) afterStep1 += walkAndCollect(root, all, false);
        LogBridge.i(TAG, "Step1(日志/崩溃/过期) 清理: " + human(deletedStep1)
                + "  剩余 " + all.size() + " 文件 = " + human(afterStep1));

        if (afterStep1 > MAX_TOTAL_BYTES) {
            Collections.sort(all, new Comparator<FileEntry>() {
                @Override public int compare(FileEntry a, FileEntry b) {
                    return Long.compare(a.file.lastModified(), b.file.lastModified());
                }
            });
            long toFree = afterStep1 - TARGET_TOTAL_BYTES;
            long freed = 0L;
            for (FileEntry e : all) {
                if (freed >= toFree) break;
                long age = System.currentTimeMillis() - e.file.lastModified();
                if (age < MIN_AGE_MS) continue;
                if (isLogOrCrashFile(e.file) || age > MIN_AGE_MS) {
                    if (e.file.delete()) {
                        freed += e.size;
                    }
                }
            }
            LogBridge.i(TAG, "Step2(容量超限LRU) 清理: " + human(freed));
        }

        long after = 0L;
        for (File root : candidates) after += walkSize(root);
        long cost = System.currentTimeMillis() - start;
        LogBridge.i(TAG, "✅ 清理完成: " + human(beforeBytes) + " → " + human(after)
                + "  节省 " + human(beforeBytes - after) + "  耗时 " + cost + "ms");
    }

    private static List<File> collectHuyaCandidateDirs(Context ctx) {
        List<File> result = new ArrayList<>();
        String pkg = ctx.getPackageName();

        addDirIfExists(result, ctx.getFilesDir());
        addDirIfExists(result, ctx.getCacheDir());
        addDirIfExists(result, ctx.getDir("huya_sdk", Context.MODE_PRIVATE));
        File databases = new File(ctx.getApplicationInfo().dataDir, "databases");
        addDirIfExists(result, databases);
        File sharedPrefs = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
        addDirIfExists(result, sharedPrefs);
        File codeCache = new File(ctx.getApplicationInfo().dataDir, "code_cache");
        addDirIfExists(result, codeCache);
        File noBackup = new File(ctx.getApplicationInfo().dataDir, "no_backup");
        addDirIfExists(result, noBackup);

        try {
            File extFiles = ctx.getExternalFilesDir(null);
            addDirIfExists(result, extFiles);
            File extCache = ctx.getExternalCacheDir();
            addDirIfExists(result, extCache);
            if (extFiles != null) {
                addDirIfExists(result, new File(extFiles.getParentFile(), "cache"));
            }
        } catch (Throwable ignored) {}

        try {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                File sd = Environment.getExternalStorageDirectory();
                for (String legacy : new String[]{
                        "Android/data/" + pkg + "/files/huya_sdk",
                        "Android/data/" + pkg + "/files/HuyaBerry",
                        "Android/data/" + pkg + "/files/tencent/MobileQQ",
                        "HuyaBerry",
                        "Huya",
                        "huya_sdk",
                        "Duowan",
                        "NLog",
                        "QQBrowser/.tmp/huya_sdk",
                }) {
                    if (legacy.contains("MobileQQ")) continue;
                    addDirIfExists(result, new File(sd, legacy));
                }
            }
        } catch (Throwable ignored) {}

        addDirIfExists(result, new File(ctx.getCacheDir(), "huya_sdk"));

        return result;
    }

    private static void addDirIfExists(List<File> list, File dir) {
        if (dir == null) return;
        if (!dir.exists() || !dir.isDirectory()) return;
        list.add(dir);
    }

    private static boolean isLogOrCrashFile(File f) {
        if (f == null) return false;
        String name = f.getName().toLowerCase(Locale.ROOT);
        String abs = f.getAbsolutePath().toLowerCase(Locale.ROOT);
        for (String ext : LOG_EXT) {
            if (name.endsWith(ext)) return true;
        }
        for (String hint : CRASH_DIR_HINTS) {
            if (name.contains(hint)) return true;
            if (abs.contains("/" + hint + "/") || abs.contains("\\" + hint + "\\")) return true;
        }
        if (name.startsWith("core-") && f.length() > 1024 * 1024) return true;
        if (name.endsWith(".dmp") || name.endsWith(".dmp.bak")) return true;
        if (name.startsWith("crash_") && name.endsWith(".txt")) return true;
        if (name.endsWith(".lock") && name.contains("mmkv")) return true;
        if (name.startsWith("httpdns") && name.endsWith(".cache")) return true;
        if (name.startsWith("dns_cache")) return true;
        if (name.endsWith(".db-shm") || name.endsWith(".db-wal")) return true;
        return false;
    }

    private static long walkAndCollect(File root, List<FileEntry> out, boolean includeDirs) {
        if (root == null || !root.exists()) return 0L;
        AtomicLong sum = new AtomicLong(0);
        walkRecursive(root, out, includeDirs, sum);
        return sum.get();
    }

    private static void walkRecursive(File node, List<FileEntry> out, boolean includeDirs, AtomicLong sum) {
        if (node == null || !node.exists()) return;
        if (node.isDirectory()) {

            String n = node.getName();
            if ("tv_cache".equals(n)) return;

            File[] subs = node.listFiles();
            if (subs == null) return;
            for (File s : subs) walkRecursive(s, out, includeDirs, sum);
        } else if (node.isFile()) {
            long sz = node.length();
            sum.addAndGet(sz);
            if (out != null) out.add(new FileEntry(node, sz));
        }
    }

    private static long walkSize(File root) {
        return walkAndCollect(root, null, false);
    }

    private static String human(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static class FileEntry {
        final File file;
        final long size;
        FileEntry(File f, long s) { file = f; size = s; }
    }
    }

    public static class AppCacheInspector {

    private static final String TAG = "AppCacheInspector";

    private static final long TARGET_TV_CACHE_BYTES       = 10L * 1024 * 1024;
    private static final long HARD_TV_CACHE_BYTES         = 15L * 1024 * 1024;
    private static final long TV_CACHE_ITEM_AGE_MS        = 24L * 60 * 60 * 1000;

    private static final long TARGET_EXO_TMP_BYTES        =  2L * 1024 * 1024;
    private static final long HARD_EXO_TMP_BYTES          =  5L * 1024 * 1024;
    private static final long EXO_TMP_FILE_AGE_MS         =  6L * 60 * 60 * 1000;

    private static final int  KEEP_LAST_CRASH_LOGS        =  3;
    private static final long CRASH_LOG_AGE_MS            =  7L * 24 * 60 * 60 * 1000;

    private static final long JS_PARSER_PLUGIN_AGE_MS     =  14L * 24 * 60 * 60 * 1000;

    private static final String[] DEAD_TV_CACHE_KEYS = new String[]{
            "epg_old", "epg_v1", "live_old", "epg_backup", "live_backup",
            "playlist_backup", "channels_old", "sub_old", "huya_room_list",
            "huya_category", "tvbox_full", "raw_response"
    };

    public static void startupCleanup(final Context ctx) {
        if (ctx == null) return;
        new Thread(() -> {
            try {
                performFullInspection(ctx.getApplicationContext());
            } catch (Throwable t) {
                LogBridge.w(TAG, "startupCleanup 异常, 忽略: " + t.getMessage());
            }
        }, "AppCacheInspector-Cleanup").start();
    }

    private static void performFullInspection(Context ctx) {
        final long t0 = System.currentTimeMillis();

        File cacheDir     = ctx.getCacheDir();
        File filesDir     = ctx.getFilesDir();
        File codeCacheDir = safeGetCodeCache(ctx);
        File extFiles     = ctx.getExternalFilesDir(null);
        File extCache     = ctx.getExternalCacheDir();

        long before = sizeOf(cacheDir) + sizeOf(filesDir) + sizeOf(codeCacheDir) + sizeOf(extFiles) + sizeOf(extCache);

        int  tvSaved   = 0;
        long tvFreed   = 0;
        File tvDir = new File(cacheDir, "tv_cache");
        if (tvDir.isDirectory()) {

            for (String deadKey : DEAD_TV_CACHE_KEYS) {
                File f = new File(tvDir, deadKey + ".cache");
                if (f.isFile()) {
                    long sz = f.length();
                    if (f.delete()) { tvFreed += sz; tvSaved++; }
                }
            }

            File[] all = tvDir.listFiles();
            if (all != null) {
                long now = System.currentTimeMillis();
                for (File f : all) {
                    if (!f.isFile()) continue;
                    // 【2026-10-05 修复】EPG / 直播源业务缓存有自己的 7 天生命周期管理
                    // （EpgManager.isEpgCacheFresh + 下载兜底 / AppCoreManager.isLiveSourceCacheFresh），
                    // 不能被通用巡检按 24h「超龄」误删。否则 ERW EPG 缓存（单文件可达 18MB）
                    // 每次启动都被删 → 永不命中缓存 → 每次都重新下载（违背 7 天策略 + 触发下载次数限制）。
                    if (isBusinessTvCache(f.getName())) continue;
                    long age = now - f.lastModified();
                    if (age > TV_CACHE_ITEM_AGE_MS) {
                        long sz = f.length();
                        if (f.delete()) { tvFreed += sz; tvSaved++; }
                    }
                }
            }

            // 【2026-10-05 修复】硬上限删除时同样豁免 EPG / 直播源业务缓存，
            // 否则单个 ERW EPG 缓存（18MB）超 15MB 硬上限，会被 lruDeleteFromDir 当作最旧文件删掉。
            long szAfter = dirSizeExcludingBusiness(tvDir);
            if (szAfter > HARD_TV_CACHE_BYTES) {
                long needFree = szAfter - TARGET_TV_CACHE_BYTES;
                tvFreed += lruDeleteFromDirExcludingBusiness(tvDir, needFree);
            }
        }

        int  exoSaved = 0;
        long exoFreed = 0;
        File exoTmp = new File(cacheDir, "exo_tmp");
        if (exoTmp.isDirectory()) {
            long now = System.currentTimeMillis();
            File[] fs = exoTmp.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    long age = now - f.lastModified();
                    if (age > EXO_TMP_FILE_AGE_MS) {
                        long sz = f.length();
                        if (f.delete()) { exoFreed += sz; exoSaved++; }
                    }
                }
            }
            long szAfter = dirSize(exoTmp);
            if (szAfter > HARD_EXO_TMP_BYTES) {
                exoFreed += lruDeleteFromDir(exoTmp, szAfter - TARGET_EXO_TMP_BYTES);
            }
        }

        File[] rootScatter = cacheDir.listFiles();
        if (rootScatter != null) {
            long now = System.currentTimeMillis();
            for (File f : rootScatter) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                boolean isExoScatter = name.endsWith(".exo")
                        || name.endsWith(".part")
                        || name.endsWith(".download")
                        || name.startsWith("exoplayer-")
                        || name.startsWith("media3-")
                        || (name.endsWith(".cache") && !f.getParentFile().getName().equals("tv_cache"));
                if (isExoScatter && (now - f.lastModified()) > EXO_TMP_FILE_AGE_MS) {
                    long sz = f.length();
                    if (f.delete()) { exoFreed += sz; exoSaved++; }
                }
            }
        }

        int  crashSaved = 0;
        long crashFreed = 0;
        File crashDir = new File(filesDir, "crash_logs");
        if (crashDir.isDirectory()) {
            long now = System.currentTimeMillis();
            List<File> list = listFilesSortedByMtimeDesc(crashDir);

            for (int i = 0; i < list.size(); i++) {
                File f = list.get(i);
                if (!f.isFile()) continue;
                if (now - f.lastModified() > CRASH_LOG_AGE_MS) {
                    long sz = f.length();
                    if (f.delete()) { crashFreed += sz; crashSaved++; list.remove(i); i--; }
                }
            }

            for (int i = KEEP_LAST_CRASH_LOGS; i < list.size(); i++) {
                File f = list.get(i);
                if (f.isFile()) {
                    long sz = f.length();
                    if (f.delete()) { crashFreed += sz; crashSaved++; }
                }
            }
        }

        int  apkSaved = 0;
        long apkFreed = 0;
        if (extFiles != null) {
            File downloads = new File(extFiles, Environment_SUBDIR_DOWNLOADS());
            File[] apks = downloads.listFiles();
            long now = System.currentTimeMillis();
            if (apks != null) {
                for (File f : apks) {
                    if (!f.isFile()) continue;
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if ((n.startsWith("tv_live_update") && n.endsWith(".apk"))
                            || (n.endsWith(".apk") && (now - f.lastModified()) > 3L * 24 * 60 * 60 * 1000)) {
                        long sz = f.length();
                        if (f.delete()) { apkFreed += sz; apkSaved++; }
                    }
                }
            }

            if (BuildCheckAtLeastQ()) {
                File pub = new File(extFiles, Environment_SUBDIR_DOWNLOADS());
                cleanUpdateApksInDir(pub);
            }
        }

        int  jsSaved = 0;
        long jsFreed = 0;
        File jsDir = new File(filesDir, "js/parser");
        if (jsDir.isDirectory()) {
            long now = System.currentTimeMillis();
            File[] fs = jsDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    long age = now - f.lastModified();
                    if (age > JS_PARSER_PLUGIN_AGE_MS) {
                        long sz = f.length();
                        if (f.delete()) { jsFreed += sz; jsSaved++; }
                    }
                }
            }
        }

        int  rtSaved = 0;
        long rtFreed = 0;
        if (codeCacheDir != null && codeCacheDir.isDirectory()) {
            long now = System.currentTimeMillis();
            List<File> all = deepFiles(codeCacheDir);
            for (File f : all) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                boolean drop = name.startsWith("traces")
                        || name.contains("trace-")
                        || name.startsWith("prof")
                        || name.endsWith(".dmp")
                        || name.endsWith(".prof")
                        || (name.contains("strictmode") && (now - f.lastModified() > 3L * 24 * 3600 * 1000));
                if (drop) {
                    long sz = f.length();
                    if (f.delete()) { rtFreed += sz; rtSaved++; }
                }
            }
        }

        int  wvSaved = 0;
        long wvFreed = 0;
        String[] webviewSubs = new String[]{
                "app_webview", "webview", "WebView",
                "org.chromium.android_webview", "Default"
        };
        for (String sub : webviewSubs) {
            File d1 = new File(filesDir, ".." + File.separator + sub);
            File d2 = new File(filesDir.getParent(), sub);
            for (File d : new File[]{d1, d2}) {
                if (d == null || !d.isDirectory()) continue;
                List<File> files = deepFiles(d);
                long now = System.currentTimeMillis();
                for (File f : files) {
                    if (!f.isFile()) continue;
                    long age = now - f.lastModified();
                    String p = f.getAbsolutePath().toLowerCase(Locale.ROOT);
                    boolean hit = p.contains("/cache/")
                            || p.contains("/gpu cache/")
                            || p.contains("/code cache/")
                            || p.contains("/service worker/")
                            || p.endsWith(".tmp");
                    if (hit && age > 7L * 24 * 3600 * 1000) {
                        long sz = f.length();
                        if (f.delete()) { wvFreed += sz; wvSaved++; }
                    }
                }
            }
        }

        List<File> filesAll = deepFiles(filesDir);
        long now1 = System.currentTimeMillis();
        for (File f : filesAll) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".bak") || name.endsWith(".tmp") || name.endsWith(".old")) {
                if (now1 - f.lastModified() > 24L * 3600 * 1000) {
                    long sz = f.length();
                    if (f.delete()) { rtFreed += sz; rtSaved++; }
                }
            }
        }

        if (extCache != null && extCache.isDirectory()) {
            List<File> all = deepFiles(extCache);
            long now2 = System.currentTimeMillis();
            for (File f : all) {
                if (!f.isFile()) continue;
                if (now2 - f.lastModified() > 3L * 24 * 3600 * 1000) {
                    long sz = f.length();
                    if (f.delete()) { rtFreed += sz; rtSaved++; }
                }
            }
        }

        long after = sizeOf(cacheDir) + sizeOf(filesDir) + sizeOf(codeCacheDir) + sizeOf(extFiles) + sizeOf(extCache);
        long cost  = System.currentTimeMillis() - t0;
        long totalFreed = before - after;
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 应用缓存巡检完成: ").append(human(before)).append(" → ").append(human(after))
                .append("  释放 ").append(human(totalFreed))
                .append("  耗时 ").append(cost).append("ms\n");
        if (tvSaved    > 0) sb.append("  · 业务缓存(EPG/直播源)  : -").append(human(tvFreed)).append(" 文件数=").append(tvSaved).append("\n");
        if (exoSaved   > 0) sb.append("  · ExoPlayer 临时分片    : -").append(human(exoFreed)).append(" 文件数=").append(exoSaved).append("\n");
        if (crashSaved > 0) sb.append("  · Crash 日志            : -").append(human(crashFreed)).append(" 文件数=").append(crashSaved).append("\n");
        if (apkSaved   > 0) sb.append("  · 更新遗留 APK          : -").append(human(apkFreed)).append(" 文件数=").append(apkSaved).append("\n");
        if (jsSaved    > 0) sb.append("  · JsParser 过期插件     : -").append(human(jsFreed)).append(" 文件数=").append(jsSaved).append("\n");
        if (rtSaved    > 0) sb.append("  · 运行时 traces/drops   : -").append(human(rtFreed)).append(" 文件数=").append(rtSaved).append("\n");
        if (wvSaved    > 0) sb.append("  · WebView 资源缓存      : -").append(human(wvFreed)).append(" 文件数=").append(wvSaved).append("\n");
        LogBridge.i(TAG, sb.toString());
    }

    private static File safeGetCodeCache(Context ctx) {
        try {
            return ctx.getCodeCacheDir();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String Environment_SUBDIR_DOWNLOADS() {

        return android.os.Environment.DIRECTORY_DOWNLOADS;
    }

    private static boolean BuildCheckAtLeastQ() {
        try {
            int Q = 29;
            return android.os.Build.VERSION.SDK_INT >= Q;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void cleanUpdateApksInDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if ((n.startsWith("tv_live_update") && n.endsWith(".apk"))
                    || (n.endsWith(".apk") && now - f.lastModified() > 3L * 24 * 60 * 60 * 1000)) {

                f.delete();
            }
        }
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0L;
        return dirSize(f);
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long s = 0L;
        if (dir.isFile()) return dir.length();
        File[] fs = dir.listFiles();
        if (fs == null) return 0L;
        for (File f : fs) s += dirSize(f);
        return s;
    }

    private static List<File> listFilesSortedByMtimeDesc(File dir) {
        File[] arr = dir.listFiles();
        if (arr == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(arr));
        Collections.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return list;
    }

    private static long lruDeleteFromDir(File dir, long needFree) {
        if (dir == null || !dir.isDirectory() || needFree <= 0) return 0L;
        File[] arr = dir.listFiles();
        if (arr == null || arr.length == 0) return 0L;
        List<File> list = new ArrayList<>(Arrays.asList(arr));
        Collections.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long freed = 0L;
        for (File f : list) {
            if (!f.isFile()) continue;
            if (freed >= needFree) break;
            long sz = f.length();
            if (f.delete()) freed += sz;
        }
        return freed;
    }

    /** 【2026-10-05】判断是否 EPG / 直播源业务缓存（有自己的 7 天生命周期，巡检不得误删）。
     *  覆盖：epg_ 前缀（含 URL hash 后缀）、live_source、以及历史 live_* 源缓存。 */
    private static boolean isBusinessTvCache(String fileName) {
        if (fileName == null) return false;
        return fileName.startsWith("epg_") || fileName.startsWith("live_")
                || fileName.startsWith("live_source");
    }

    /** 计算 tv_cache 目录大小，排除 EPG / 直播源业务缓存（供硬上限判断用）。 */
    private static long dirSizeExcludingBusiness(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        File[] fs = dir.listFiles();
        if (fs == null) return 0L;
        long s = 0L;
        for (File f : fs) {
            if (f.isDirectory()) s += dirSizeExcludingBusiness(f);
            else if (!isBusinessTvCache(f.getName())) s += f.length();
        }
        return s;
    }

    /** 从 tv_cache 删除非业务缓存文件直到释放 needFree 字节（豁免 EPG / 直播源缓存）。 */
    private static long lruDeleteFromDirExcludingBusiness(File dir, long needFree) {
        if (dir == null || !dir.isDirectory() || needFree <= 0) return 0L;
        File[] arr = dir.listFiles();
        if (arr == null || arr.length == 0) return 0L;
        List<File> list = new ArrayList<>(Arrays.asList(arr));
        Collections.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long freed = 0L;
        for (File f : list) {
            if (!f.isFile()) continue;
            if (isBusinessTvCache(f.getName())) continue;
            if (freed >= needFree) break;
            long sz = f.length();
            if (f.delete()) freed += sz;
        }
        return freed;
    }

    private static List<File> deepFiles(File root) {
        List<File> out = new ArrayList<>();
        if (root == null || !root.exists()) return out;
        if (root.isFile()) { out.add(root); return out; }
        File[] fs = root.listFiles();
        if (fs == null) return out;
        for (File f : fs) out.addAll(deepFiles(f));
        return out;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static File getExoPlayerCacheDir(Context ctx) {
        if (ctx == null) return null;
        File dir = new File(ctx.getApplicationContext().getCacheDir(), "exo_tmp");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    public static void onBeforePlayback(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File exoTmp = new File(app.getCacheDir(), "exo_tmp");
                    long sz = dirSize(exoTmp);
                    if (sz > HARD_EXO_TMP_BYTES) {
                        lruDeleteFromDir(exoTmp, sz - TARGET_EXO_TMP_BYTES);
                    }
                } catch (Throwable ignored) {}
            }
        }, "AppCacheInspector-ExoPrePlay").start();
    }

    public static long clearAllUserCache(Context ctx) {
        if (ctx == null) return 0;
        Context app = ctx.getApplicationContext();
        File cache = app.getCacheDir();
        File extCache = app.getExternalCacheDir();
        long before = sizeOf(cache) + sizeOf(extCache);

        deleteChildren(cache);
        deleteChildren(extCache);

        long after = sizeOf(cache) + sizeOf(extCache);
        LogBridge.i(TAG, "clearAllUserCache: " + human(before) + " → " + human(after));
        return Math.max(0L, before - after);
    }

    private static void deleteChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) deleteRecursive(f);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }

        f.delete();
    }
    }

    public static class SecureDataStore {

    private static final String TAG = "SecureDataStore";
    private static final String PREFS_NAME = "secure_data_store";
    private static final String KEY_ALIAS = "secure_data_key_v2";
    private static final String KEY_VERSION_PREF = "key_version";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private static volatile SecureDataStore sInstance;
    private Context mContext;
    private SharedPreferences mPrefs;
    private SecretKey mSecretKey;
    private boolean mInitialized = false;

    private final java.util.Map<String, Object> mCache = new java.util.HashMap<>();
    private final java.util.Map<String, Boolean> mCacheValid = new java.util.HashMap<>();

    private SecureDataStore() {
    }

    public static SecureDataStore getInstance() {
        if (sInstance == null) {
            synchronized (SecureDataStore.class) {
                if (sInstance == null) {
                    sInstance = new SecureDataStore();
                }
            }
        }
        return sInstance;
    }

    public synchronized void init(Context context) {
        if (mInitialized) {
            return;
        }

        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        try {
            initializeKey();
            mInitialized = true;
            LogBridge.i(TAG, "安全存储初始化完成");
        } catch (Exception e) {
            LogBridge.e(TAG, "安全存储初始化失败: " + e.getMessage());
        }
    }

    private void initializeKey() throws Exception {
        // 【2026-10-10 Lint/NewApi】Android Keystore 的 KeyGenParameterSpec 自 API 23 起才有，
        // 而本工程 minSdk=21。旧实现无任何守卫，在 Android 5.0/5.1 上会抛 NoClassDefFoundError；
        // 虽然外层 init() 的 catch 兜住了（表现为"安全数据存储初始化失败"），但属不确定行为。
        // 现显式提前抛出，交由 init() 的 catch 统一降级为"加密存储不可用"，行为确定且可读。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new UnsupportedOperationException(
                    "Android Keystore 需要 API 23+，当前 API " + Build.VERSION.SDK_INT + "，安全存储降级");
        }
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {

            generateKey();
        }

        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(
            KEY_ALIAS, null);
        mSecretKey = entry.getSecretKey();

        LogBridge.d(TAG, "加密密钥已就绪");
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private void generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

        keyGenerator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build());

        keyGenerator.generateKey();
        LogBridge.d(TAG, "新加密密钥已生成");
    }

    public void putString(String key, String value) {
        if (!mInitialized || value == null) {
            return;
        }

        try {
            String encryptedValue = encrypt(value);
            mPrefs.edit()
                .putString(key, encryptedValue)
                .apply();

            mCache.put(key, value);
            mCacheValid.put(key, true);

            LogBridge.d(TAG, "已存储加密值: " + key);
        } catch (Exception e) {
            LogBridge.e(TAG, "存储失败 [" + key + "]: " + e.getMessage());
        }
    }

    public String getString(String key, String defaultValue) {
        if (!mInitialized) {
            return defaultValue;
        }

        if (mCache.containsKey(key) && mCacheValid.get(key)) {
            return (String) mCache.get(key);
        }

        try {
            String encryptedValue = mPrefs.getString(key, null);
            if (encryptedValue == null) {
                return defaultValue;
            }

            String decryptedValue = decrypt(encryptedValue);
            if (decryptedValue != null) {

                mCache.put(key, decryptedValue);
                mCacheValid.put(key, true);
                return decryptedValue;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "读取失败 [" + key + "]: " + e.getMessage());
        }

        return defaultValue;
    }

    public void putInt(String key, int value) {
        putString(key + "_int", String.valueOf(value));
    }

    public int getInt(String key, int defaultValue) {
        String strValue = getString(key + "_int", null);
        if (strValue != null) {
            try {
                return Integer.parseInt(strValue);
            } catch (NumberFormatException e) {

            }
        }
        return defaultValue;
    }

    public void putBoolean(String key, boolean value) {
        putString(key + "_bool", String.valueOf(value));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String strValue = getString(key + "_bool", null);
        if (strValue != null) {
            return "true".equals(strValue);
        }
        return defaultValue;
    }

    public void remove(String key) {
        if (!mInitialized) {
            return;
        }

        mPrefs.edit().remove(key).apply();
        mPrefs.edit().remove(key + "_int").apply();
        mPrefs.edit().remove(key + "_bool").apply();
        mCache.remove(key);
        mCache.remove(key + "_int");
        mCache.remove(key + "_bool");
        mCacheValid.remove(key);
        LogBridge.d(TAG, "已删除: " + key);
    }

    public void clearAll() {
        if (!mInitialized) {
            return;
        }

        mPrefs.edit().clear().apply();
        mCache.clear();
        mCacheValid.clear();
        LogBridge.i(TAG, "已清除所有安全存储数据");
    }

    public boolean contains(String key) {
        return mPrefs.contains(key) ||
               mPrefs.contains(key + "_int") ||
               mPrefs.contains(key + "_bool");
    }

    private String encrypt(String plaintext) throws Exception {

        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/" +
            KeyProperties.BLOCK_MODE_GCM + "/" +
            KeyProperties.ENCRYPTION_PADDING_NONE);

        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, mSecretKey, gcmSpec);

        byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes("UTF-8"));

        byte[] combined = new byte[iv.length + encryptedBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String decrypt(String encryptedText) throws Exception {
        byte[] combined = Base64.decode(encryptedText, Base64.NO_WRAP);

        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] encryptedBytes = new byte[combined.length - GCM_IV_LENGTH];

        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, encryptedBytes, 0, encryptedBytes.length);

        Cipher cipher = Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/" +
            KeyProperties.BLOCK_MODE_GCM + "/" +
            KeyProperties.ENCRYPTION_PADDING_NONE);

        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, mSecretKey, gcmSpec);

        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public static String calculateHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes("UTF-8"));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public void clearCache() {
        mCache.clear();
        mCacheValid.clear();
        LogBridge.d(TAG, "内存缓存已清除");
    }
    }

    public static class EncryptedStorage {

    private static final String TAG = "EncryptedStorage";

    private static final String PREFS_NAME = "encrypted_prefs";
    private static final String KEY_ALIAS = "tv_live_encryption_key";

    private static final String KEY_SALT = "key_salt";
    private static final String KEY_Version = "key_version";

    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private Context context;
    private SharedPreferences prefs;
    private SecretKey secretKey;
    private boolean initialized = false;

    private static volatile EncryptedStorage instance;

    public static EncryptedStorage getInstance(Context context) {
        if (instance == null) {
            synchronized (EncryptedStorage.class) {
                if (instance == null) {
                    instance = new EncryptedStorage(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private EncryptedStorage(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initialize();
    }

    private void initialize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    generateOrLoadKey();
                } catch (Exception keystoreError) {
                    LogBridge.w(TAG, "Android Keystore 不可用，降级到旧版本方案: " + keystoreError.getMessage());
                    generateLegacyKey();
                }
            } else {

                generateLegacyKey();
            }
            initialized = true;
            LogBridge.i(TAG, "✅ EncryptedStorage 初始化成功");
        } catch (Exception e) {
            LogBridge.e(TAG, "❌ EncryptedStorage 初始化失败: " + e.getMessage());
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private void generateOrLoadKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");

            keyGenerator.init(
                    new KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setUserAuthenticationRequired(false)
                            .build());

            keyGenerator.generateKey();
            LogBridge.i(TAG, "✅ 生成新的 AES-256 密钥");
        }

        KeyStore.SecretKeyEntry keyEntry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
        secretKey = keyEntry.getSecretKey();
        LogBridge.i(TAG, "✅ 加载 AES-256 密钥成功");
    }

    private void generateLegacyKey() throws Exception {
        String existingSalt = prefs.getString(KEY_SALT, null);
        byte[] salt;

        if (existingSalt == null) {
            salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply();
        } else {
            salt = Base64.decode(existingSalt, Base64.NO_WRAP);
        }

        String deviceKey = getDeviceKeyMaterial();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] keyBytes = digest.digest(deviceKey.getBytes("UTF-8"));

        secretKey = new SecretKeySpec(keyBytes, "AES");
        LogBridge.w(TAG, "✅ 使用降级方案生成密钥（安全性较低）");
    }

    private String getDeviceKeyMaterial() {
        StringBuilder sb = new StringBuilder();

        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            sb.append(androidId != null ? androidId : "unknown");
        } catch (Exception e) {
            sb.append("unknown");
        }
        sb.append(android.os.Build.MODEL);
        sb.append(android.os.Build.BRAND);
        sb.append(context.getPackageName());

        return sb.toString();
    }

    public String encrypt(String key, String plainText) {
        if (!initialized || secretKey == null) {
            LogBridge.e(TAG, "加密存储未初始化");
            return plainText;
        }

        try {

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();

            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes("UTF-8"));

            String encrypted = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                             Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

            prefs.edit().putString(key, encrypted).apply();

            return encrypted;
        } catch (Exception e) {
            LogBridge.w(TAG, "加密失败: " + e.getMessage());
            return plainText;
        }
    }

    public String decrypt(String key) {
        if (!initialized || secretKey == null) {
            LogBridge.e(TAG, "加密存储未初始化");
            return null;
        }

        String encrypted = prefs.getString(key, null);
        if (encrypted == null) {
            return null;
        }

        try {

            String[] parts = encrypted.split(":");
            if (parts.length != 2) {
                LogBridge.e(TAG, "密文格式错误");
                return null;
            }

            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, "UTF-8");
        } catch (Exception e) {
            LogBridge.e(TAG, "解密失败（可能密钥已失效）: " + e.getMessage());

            prefs.edit().remove(key).apply();
            return null;
        }
    }

    public void putString(String key, String value) {
        encrypt(key, value);
    }

    public String getString(String key, String defaultValue) {
        String result = decrypt(key);
        return result != null ? result : defaultValue;
    }

    public void putInt(String key, int value) {
        encrypt(key, String.valueOf(value));
    }

    public int getInt(String key, int defaultValue) {
        String result = decrypt(key);
        if (result == null) return defaultValue;
        try {
            return Integer.parseInt(result);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    public boolean contains(String key) {
        return prefs.contains(key);
    }

    public void clearAll() {
        prefs.edit().clear().apply();
        LogBridge.i(TAG, "已清除所有加密存储数据");
    }

    public boolean isInitialized() {
        return initialized && secretKey != null;
    }

    public boolean isKeyValid() {
        if (!initialized || secretKey == null) return false;

        try {

            String testData = "key_validation_test";
            String encrypted = encrypt("__key_validation__", testData);
            String decrypted = decrypt("__key_validation__");
            prefs.edit().remove("__key_validation__").apply();

            return testData.equals(decrypted);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean regenerateKey() {
        try {
            clearAll();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS);
                }
                generateOrLoadKey();
            } else {

                prefs.edit().remove(KEY_SALT).apply();
                generateLegacyKey();
            }

            LogBridge.i(TAG, "✅ 密钥已重新生成");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "❌ 密钥重新生成失败: " + e.getMessage());
            return false;
        }
    }
    }
}