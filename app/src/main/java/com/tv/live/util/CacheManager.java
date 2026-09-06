package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

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
}
