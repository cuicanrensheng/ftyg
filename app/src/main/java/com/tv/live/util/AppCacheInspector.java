package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AppCacheInspector {

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
                    long age = now - f.lastModified();
                    if (age > TV_CACHE_ITEM_AGE_MS) {
                        long sz = f.length();
                        if (f.delete()) { tvFreed += sz; tvSaved++; }
                    }
                }
            }

            long szAfter = dirSize(tvDir);
            if (szAfter > HARD_TV_CACHE_BYTES) {
                long needFree = szAfter - TARGET_TV_CACHE_BYTES;
                tvFreed += lruDeleteFromDir(tvDir, needFree);
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
