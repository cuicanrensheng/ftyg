package com.tv.live.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import com.huya.berry.client.HuyaBerryConfig;
import com.tv.live.util.LogBridge;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class HuyaCacheGovernor {

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

    public static void applyOnBuilder(HuyaBerryConfig.Builder builder, Context ctx) {
        if (builder == null) return;
        try {

            try {
                File base = new File(ctx.getCacheDir(), "huya_sdk");
                if (!base.exists()) base.mkdirs();
                File cacheDir = new File(base, "cache");
                File fileDir  = new File(base, "files");
                File logDir   = new File(base, "logs");
                File tmpDir   = new File(base, "tmp");
                for (File d : new File[]{cacheDir, fileDir, logDir, tmpDir}) {
                    try { if (!d.exists()) d.mkdirs(); } catch (Throwable ignored) {}
                }
                trySetDir(builder, "setRootDir",    base);
                trySetDir(builder, "rootDir",       base);
                trySetDir(builder, "setBaseDir",    base);
                trySetDir(builder, "baseDir",       base);
                trySetDir(builder, "setWorkDir",    base);
                trySetDir(builder, "workDir",       base);
                trySetDir(builder, "setSdkDir",     base);
                trySetDir(builder, "sdkDir",        base);
                trySetDir(builder, "setDataDir",    fileDir);
                trySetDir(builder, "dataDir",       fileDir);
                trySetDir(builder, "setFileDir",    fileDir);
                trySetDir(builder, "fileDir",       fileDir);
                trySetDir(builder, "setFilesDir",   fileDir);
                trySetDir(builder, "filesDir",      fileDir);
                trySetDir(builder, "setCacheDir",   cacheDir);
                trySetDir(builder, "cacheDir",      cacheDir);
                trySetDir(builder, "setTempDir",    tmpDir);
                trySetDir(builder, "tempDir",       tmpDir);
                trySetDir(builder, "setLogDir",     logDir);
                trySetDir(builder, "logDir",        logDir);
                trySetDir(builder, "setXLogDir",    logDir);
                trySetDir(builder, "xLogDir",       logDir);
                trySetDir(builder, "setCrashDir",   new File(base, "crash"));
                trySetDir(builder, "crashDir",      new File(base, "crash"));
                LogBridge.i(TAG, "✅ SDK 根目录重定向至: " + base);
            } catch (Throwable t) {
                LogBridge.w(TAG, "⚠️ SDK 目录重定向失败: " + t.getMessage());
            }

            builder.isOpenBugly(false);
            builder.debugMode(false);
            builder.isNeedPlay(false);
            builder.cameraMode(false);
            builder.oneKeyGangUp(false);
            builder.hidePauseBtn(false);
            builder.landscapeMode(false);
            LogBridge.i(TAG, "✅ SDK 精简开关已直接调用（isOpenBugly/debugMode/isNeedPlay/cameraMode/oneKeyGangUp/hidePauseBtn/landscapeMode = false）");
        } catch (Throwable t) {
            LogBridge.w(TAG, "applyOnBuilder failed, ignore: " + t.getMessage());
        }
    }

    private static void trySetDir(Object builder, String methodName, File dir) {
        try {
            Method m = findMethod(builder, methodName, File.class);
            if (m == null) return;
            m.invoke(builder, dir);
        } catch (Throwable t) {  }
    }

    private static Method findMethod(Object o, String name, Class<?> paramType) {
        Method[] ms = o.getClass().getMethods();
        for (Method m : ms) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 1) continue;
            Class<?> pt = pts[0];
            if (pt == paramType) {
                return m;
            }

            if (paramType == boolean.class && pt == Boolean.class) return m;
        }
        return null;
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
