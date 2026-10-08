package com.tv.live.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.tv.live.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 【2026-09-29 频道台标】轻量频道台标加载器。
 *
 * 设计约束：
 *   - 不引入任何新三方依赖（工程仅 ~2.9MB 且已 exclude Glide），仅用已有的 OkHttp 手写。
 *   - 只持有 ApplicationContext，绝不持有 Activity（单例安全）。
 *   - 内存 LruCache（按字节计） + 磁盘缓存（cacheDir/channel_logo，文件名 = md5(url)，上限 8MB，按 lastModified 淘汰）。
 *   - IO 全部在 3 线程池中执行，结果回主线程；解码前用 inSampleSize 把长边压到约 200px，避免大图 OOM。
 *   - GridView 复用防错位：进入时把 url 写入 view 的 tag，回主线程 setImageBitmap 前校验 tag 仍相等，不等直接丢弃。
 */
public class ChannelLogoLoader {

    private static final String TAG = "ChannelLogoLoader";

    /** 磁盘缓存上限：8MB */
    private static final long DISK_CACHE_MAX_BYTES = 8L * 1024 * 1024;
    /** 解码后位图长边上限（px） */
    private static final int MAX_EDGE_PX = 200;
    /** 永久失败负缓存容量上限 */
    private static final int FAILED_CACHE_MAX = 512;

    private static volatile ChannelLogoLoader sInstance;

    private final Context appContext;
    private final LruCache<String, Bitmap> memCache;
    private final File diskDir;
    private final OkHttpClient client;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 【2026-09-29 频道台标】确定不可用的台标 URL（非 2xx）。只缓存"服务器明确说没有"的情况，
     * 不缓存超时/DNS 失败等瞬时错误，避免断网启动后永久不再重试。LRU 淘汰，容量上限 512。
     */
    private final LinkedHashMap<String, Boolean> failedUrls =
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > FAILED_CACHE_MAX;
                }
            };

    public static ChannelLogoLoader getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (ChannelLogoLoader.class) {
                if (sInstance == null) {
                    sInstance = new ChannelLogoLoader(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private ChannelLogoLoader(Context appContext) {
        this.appContext = appContext;

        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 16);
        this.memCache = new LruCache<String, Bitmap>(maxMemory) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        this.diskDir = new File(appContext.getCacheDir(), "channel_logo");
        if (!diskDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            diskDir.mkdirs();
        }

        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 加载台标到 ImageView。
     *
     * @param view          目标 ImageView
     * @param url           台标 URL，为空时直接显示占位图
     * @param placeholderRes 占位图资源
     */
    public void load(final ImageView view, final String url, final int placeholderRes) {
        if (view == null) return;

        if (url == null || url.isEmpty()) {
            view.setTag(R.id.logo_load_tag, null);
            view.setImageResource(placeholderRes);
            return;
        }

        // 记录本次绑定的 url 作为「归属标记」，回主线程时校验，防止 GridView 复用时图片错位
        view.setTag(R.id.logo_load_tag, url);

        Bitmap cached = memCache.get(url);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }

        // 【2026-09-29 频道台标】已知永久失效（非 2xx）的台标：直接显示占位图，不再重复发起请求
        if (isPermanentFailure(url)) {
            view.setImageResource(placeholderRes);
            return;
        }

        view.setImageResource(placeholderRes);

        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = loadFromDisk(url);
                if (bmp == null) {
                    bmp = download(url);
                    if (bmp != null) {
                        memCache.put(url, bmp);
                    }
                } else {
                    memCache.put(url, bmp);
                }

                final Bitmap result = bmp;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 防错位：只有当 view 当前绑定的仍是本次 url 时才写入
                        Object tag = view.getTag(R.id.logo_load_tag);
                        if (!url.equals(tag)) return;
                        if (result != null && !result.isRecycled()) {
                            view.setImageBitmap(result);
                        } else {
                            view.setImageResource(placeholderRes);
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // 永久失败负缓存
    // ------------------------------------------------------------------

    /** 【2026-09-29 频道台标】记录一个确定失效（非 2xx）的台标 URL */
    private synchronized void markPermanentFailure(String url) {
        if (url != null) failedUrls.put(url, Boolean.TRUE);
    }

    /** 【2026-09-29 频道台标】该 URL 是否已被标记为永久失效 */
    private synchronized boolean isPermanentFailure(String url) {
        return url != null && failedUrls.containsKey(url);
    }

    // ------------------------------------------------------------------
    // 磁盘缓存
    // ------------------------------------------------------------------

    private File diskFile(String url) {
        return new File(diskDir, md5(url));
    }

    private Bitmap loadFromDisk(String url) {
        try {
            File f = diskFile(url);
            if (!f.exists()) return null;
            byte[] data = readAll(new FileInputStream(f));
            Bitmap bmp = decode(data);
            if (bmp == null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            // LRU 触碰：更新最后修改时间
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveToDisk(String url, byte[] data) {
        FileOutputStream fos = null;
        try {
            File f = diskFile(url);
            fos = new FileOutputStream(f);
            fos.write(data);
            fos.flush();
            trimDiskIfNeeded();
        } catch (Exception ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 磁盘超限时按 lastModified 由旧到新淘汰 */
    private void trimDiskIfNeeded() {
        try {
            File[] files = diskDir.listFiles();
            if (files == null || files.length == 0) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= DISK_CACHE_MAX_BYTES) return;

            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : files) {
                if (total <= DISK_CACHE_MAX_BYTES) break;
                long len = f.length();
                //noinspection ResultOfMethodCallIgnored
                if (f.delete()) total -= len;
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 网络下载 + 解码
    // ------------------------------------------------------------------

    private Bitmap download(String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    // 【2026-09-29 频道台标】非 2xx：多为内置源里的图床已失效（例如 tb.zbds.top 已 404），记录以便排查
                    LogBridge.w(TAG, "台标 HTTP " + response.code() + " 不可用: " + url);
                    markPermanentFailure(url);
                    return null;
                }
                if (response.body() == null) {
                    LogBridge.w(TAG, "台标响应体为空: " + url);
                    return null;
                }
                byte[] data = response.body().bytes();
                Bitmap bmp = decode(data);
                if (bmp != null) {
                    saveToDisk(url, data);
                }
                return bmp;
            }
        } catch (Exception e) {
            LogBridge.w(TAG, "台标下载失败: " + url + " -> " + e.getMessage());
            return null;
        }
    }

    /** 先读边界算 inSampleSize，再按采样率解码，长边压到 MAX_EDGE_PX 内 */
    private static Bitmap decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int maxEdge = Math.max(bounds.outWidth, bounds.outHeight);
            int sampleSize = 1;
            while (maxEdge / sampleSize > MAX_EDGE_PX) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            LogBridge.w(TAG, "台标解码 OOM");
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
                sb.append(Character.forDigit(b & 0x0F, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极端兜底：退回 hashCode，保证仍有唯一文件名
            return Integer.toHexString(s.hashCode());
        }
    }
}
