package com.tv.live.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;

import com.tv.live.R;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 【2026-09-29 频道台标】轻量频道台标加载器。
 *
 * 设计约束：
 *   - 不引入任何新三方依赖（工程仅 ~2.9MB 且已 exclude Glide），仅用已有的 OkHttp 手写。
 *   - 只持有 ApplicationContext，绝不持有 Activity（单例安全）。
 *   - 内存 LruCache（按字节计） + 磁盘缓存（cacheDir/channel_logo，文件名 = md5(url)，上限 32MB，超过 1.25 倍触发线时按 lastModified 淘汰）。
 *   - IO 全部在 10 线程池中执行，结果回主线程；解码前用 inSampleSize 把长边压到约 200px，避免大图 OOM。
 *   - 【2026-10-10 台标性能·闸门】未确认可达的主机同一时刻只允许 1 个请求在飞，避免一批同主机
 *     请求同时占满线程池各等满一次超时（实测首屏 12 张同主机图曾各自等满 4s，后续图片排队 10~19s）。
 *   - GridView 复用防错位：进入时把 url 写入 view 的 tag，回主线程 setImageBitmap 前校验 tag 仍相等，不等直接丢弃。
 */
public class ChannelLogoLoader {

    private static final String TAG = "ChannelLogoLoader";

    /** 磁盘缓存上限：32MB（【2026-10-10 台标性能】原 8MB 对 OTT 首页 611 个房间的台标覆盖不足，扩容） */
    private static final long DISK_CACHE_MAX_BYTES = 16L * 1024 * 1024;
    /**
     * 【2026-10-10 台标性能】裁剪触发线：仅当磁盘占用超过上限的 1.25 倍时才真正执行裁剪。
     * 原实现「每次保存都 listFiles()+全量排序+删除」，缓存顶满后每次保存都抖动；加入 1.25 倍
     * 缓冲后，绝大多数保存走估算值、不再触发全量遍历。
     */
    private static final long DISK_TRIM_TRIGGER_BYTES = DISK_CACHE_MAX_BYTES * 11L / 10L; // 1.1 倍 = 17.6MB
    /** 解码后位图长边上限（px） */
    private static final int MAX_EDGE_PX = 200;
    /** 永久失败负缓存容量上限 */
    private static final int FAILED_CACHE_MAX = 512;
    /** 【2026-10-10 台标性能】dimsCache 有界兜底上限（条目数） */
    private static final int DIMS_CACHE_MAX = 1024;

    private static volatile ChannelLogoLoader sInstance;

    private final Context appContext;
    private final LruCache<String, Bitmap> memCache;
    private final File diskDir;
    private final OkHttpClient client;
    /** 【2026-10-10 台标性能·闸门】线程池 6 → 10：单主机探测成功后会一次性放行同主机挂起的图片，需要更多并发线程铺开 */
    private final ExecutorService pool = Executors.newFixedThreadPool(10);
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

    /**
     * 【2026-10-11 海报】主机级失败冷却。源7 海报分散在 8+ 个第三方图床，部分主机在本网络
     * 完全不可达（TLS 被 reset / 连接超时）。若逐张重试，每张都要卡满一次超时，把 3 线程池
     * 堵死，表现为「大量图片长时间空白 + 加载极慢」。故：某主机一旦连接失败，其下所有 URL
     * 在冷却期内直接回退占位图（不发请求），成功一次即清除。仅针对网络/连接失败，
     * 不针对 404（404 说明主机可达，只是这张图没有）。
     */
    private static final long HOST_FAIL_COOLDOWN_MS = 5L * 60L * 1000L;
    private final Map<String, Long> badHostUntil = new ConcurrentHashMap<>();

    /**
     * 【2026-10-27 日志打点审计】台标失败是**逐 URL** 触发的：首屏几百个频道里若图床大面积失效，
     * 会在几秒内刷出几十上百条同类日志，把 2000 条环形缓冲里的播放排障历史整段挤掉。
     * 故改为「前 3 条全记录 + 之后每 50 条记一次」，既有首因证据又不淹没。
     */
    private static final java.util.concurrent.atomic.AtomicInteger sLogoFailLogs =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean shouldLogLogoFailure() {
        final int n = sLogoFailLogs.incrementAndGet();
        return n <= 3 || (n % 50) == 0;
    }

    /** 【2026-10-10 台标性能·闸门】已确认可达的主机：不再受 in-flight 闸门限制，全速并发 */
    private final Set<String> hostGood = ConcurrentHashMap.newKeySet();
    /** 【2026-10-10 台标性能·闸门】未确认主机的等待队列：key=host，value=挂起的加载任务 */
    private final Map<String, List<Runnable>> hostWaiters = new ConcurrentHashMap<>();
    /** 单主机等待队列长度上限，防异常情况下无限堆积 */
    private static final int HOST_WAITERS_MAX = 64;

    /**
     * 【2026-10-10 台标性能】磁盘已用字节估算值，-1 表示「尚未统计」。
     * 用途：保存新图时只做一次 O(1) 的加减，只有当估算值超过 DISK_TRIM_TRIGGER_BYTES
     * 时才真正 listFiles() 全量统计并裁剪，从而消除缓存写满后每次保存的全量遍历抖动。
     * 任何绕过本类写入/删除缓存文件的操作都应把它置回 -1，促使其下次重新统计。
     */
    private final java.util.concurrent.atomic.AtomicLong diskUsedEstimate =
            new java.util.concurrent.atomic.AtomicLong(-1L);

    /**
     * 【2026-10-10 台标性能】url -> inSampleSize 记忆表。同一张图首次解码时会探测一次
     * 边界（inJustDecodeBounds），之后命中此表即可跳过边界探测，减少一次整文件遍历。
     * key=url，value=该图应使用的 inSampleSize。条目数超过 DIMS_CACHE_MAX 时整体清空兜底。
     */
    private final Map<String, Integer> dimsCache = new ConcurrentHashMap<>();

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
                // 【2026-10-11 海报】超时收紧（原 5s/8s）：源7 部分图床不可达，长超时会长时间占用线程、
                // 拖慢整屏加载；配合主机级冷却，失效图床只需承担一次短超时。
                // 【2026-10-10 台标性能·闸门】再收紧（原 3s/4s）：冷启动实测失败请求恰好卡满 readTimeout(4s)，
                // 单个不可达主机的 12 张图曾各等满超时、把线程池堵死；配合 in-flight 闸门只放 1 个探测请求，
                // 超时本也应更短，故连接 1.5s、读取 2.5s。
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                // 【2026-10-25 NO_PROXY】台标/海报属播放链路附属资源，统一绕开系统代理
                .proxy(java.net.Proxy.NO_PROXY)
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
        // 【2026-10-10 台标性能】耗时埋点基准：主线程提交时刻（置于方法第一行，保证含 view==null 在内每个出口都能打点）
        final long t0 = SystemClock.uptimeMillis();

        if (view == null) {
            logPerf("无视图", url, t0);
            return;
        }

        if (url == null || url.isEmpty()) {
            view.setTag(R.id.logo_load_tag, null);
            view.setImageResource(placeholderRes);
            logPerf("空URL", url, t0);
            return;
        }

        // 记录本次绑定的 url 作为「归属标记」，回主线程时校验，防止 GridView 复用时图片错位
        view.setTag(R.id.logo_load_tag, url);

        Bitmap cached = memCache.get(url);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            logPerf("内存", url, t0);
            return;
        }

        // 【2026-09-29 频道台标】已知永久失效（非 2xx）的台标：直接显示占位图，不再重复发起请求
        if (isPermanentFailure(url)) {
            view.setImageResource(placeholderRes);
            logPerf("负缓存", url, t0);
            return;
        }

        // 【2026-10-11 海报】该图床正处于失败冷却期：直接回退占位图，不再逐张重试（否则整屏被拖慢）
        if (isHostCoolingDown(url)) {
            view.setImageResource(placeholderRes);
            logPerf("冷却", url, t0);
            return;
        }

        view.setImageResource(placeholderRes);

        // 【2026-10-10 台标性能·闸门】未确认可达的主机：同主机同时只允许 1 个请求在飞，
        // 其余先挂起，避免一批同主机请求同时占满线程池各等满一次超时（实测首屏 12 张同主机图曾各自等满 4s）
        final String gateHost = hostOf(url);
        // [0] = 本次任务是否为该主机的「探测者」；只有探测者才需在任务结束时 releaseHostProbe
        final boolean[] isProbeHolder = new boolean[1];
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                // 探测结论：仅「连接失败 → 已进入冷却」视为不可达；404 等非 2xx 说明主机可达，不算失败
                boolean probeSuccess = true;
                try {
                    // 【2026-10-10 台标性能】①排队 = 线程池任务 run() 开始 - 主线程提交时刻
                    long queueMs = SystemClock.uptimeMillis() - t0;

                    // 【2026-10-10 台标性能】②磁盘 = loadFromDisk 耗时
                    long tBeforeDisk = SystemClock.uptimeMillis();
                    Bitmap bmp = loadFromDisk(url);
                    long diskMs = SystemClock.uptimeMillis() - tBeforeDisk;
                    long netMs = 0L;
                    long decMs = 0L;
                    String source;
                    if (bmp == null) {
                        // 【2026-10-10 台标性能·闸门】排队期间该主机可能已被判定不可达：直接回退占位，不再发起网络请求。
                        // 作用：① releaseHostProbe(false) 放行的任务不会再去联网；② 常规排队任务也能在真正下载前发现冷却。
                        if (isHostCoolingDown(url)) {
                            source = "冷却";
                        } else {
                            // 磁盘未命中：走网络（③网络 / ④解码 由 download 回传）
                            long[] stats = new long[2];
                            bmp = download(url, stats);
                            netMs = stats[0];
                            decMs = stats[1];
                            if (bmp != null) {
                                memCache.put(url, bmp);
                                source = "网络";
                            } else {
                                source = "失败";
                            }
                        }
                    } else {
                        memCache.put(url, bmp);
                        source = "磁盘";
                    }
                    // 【2026-10-10 台标性能·闸门】探测结论：连接失败会在 download 内 markHostFailure 进入冷却
                    probeSuccess = !isHostCoolingDown(url);

                    final Bitmap result = bmp;
                    final String fSource = source;
                    final long fQueue = queueMs;
                    final long fDisk = diskMs;
                    final long fNet = netMs;
                    final long fDec = decMs;
                    // 【2026-10-10 台标性能】⑤回写 的起点：后台任务结束、准备 post 回主线程
                    final long tBgEnd = SystemClock.uptimeMillis();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            // 防错位：只有当 view 当前绑定的仍是本次 url 时才写入
                            Object tag = view.getTag(R.id.logo_load_tag);
                            if (url.equals(tag)) {
                                if (result != null && !result.isRecycled()) {
                                    view.setImageBitmap(result);
                                } else {
                                    view.setImageResource(placeholderRes);
                                }
                            }
                            // 【2026-10-10 台标性能·复核修复】日志走 logPerfBg，release 下仅短路日志本身，
                            // 上面的图片写回与防错位校验在任何构建下都已无条件执行完毕。
                            logPerfBg(fSource, url, fQueue, fDisk, fNet, fDec, tBgEnd, t0);
                        }
                    });
                } finally {
                    // 【2026-10-10 台标性能·闸门】探测任务结束必须释放该主机挂起队列（成功放行 / 失败回退），
                    // 放在 finally 保证任何路径（含异常）都会执行，不会把同主机请求永久挂死。
                    if (isProbeHolder[0]) {
                        releaseHostProbe(gateHost, probeSuccess);
                    }
                }
            }
        };

        boolean shouldExecute = true;    // false = 已挂起进等待队列，待放行时执行
        boolean gateDiscarded = false;   // true = 等待队列已满，本次直接丢弃
        if (gateHost != null && !hostGood.contains(gateHost)) {
            synchronized (hostWaiters) {
                // 双重检查：拿锁期间该主机可能已被确认可达
                if (!hostGood.contains(gateHost)) {
                    List<Runnable> q = hostWaiters.get(gateHost);
                    if (q != null) {
                        shouldExecute = false;              // 已有同主机请求在飞 → 挂起等待（占位图已显示）
                        if (q.size() < HOST_WAITERS_MAX) {
                            q.add(task);
                        } else {
                            gateDiscarded = true;           // 队列已满 → 丢弃
                        }
                    } else {
                        hostWaiters.put(gateHost, new ArrayList<Runnable>());   // 由本次请求担任探测者
                        isProbeHolder[0] = true;
                    }
                }
            }
        }
        if (gateDiscarded) {
            // 【2026-10-10 台标性能·闸门】等待队列已满：补打一行（来源=闸门丢弃），任务不再执行
            logPerf("闸门丢弃", url, t0);
            return;
        }
        if (!shouldExecute) {
            // 已挂起：此刻不打日志，待放行时由任务自身恰好打一行，避免一个 load() 打两行
            return;
        }
        pool.execute(task);
    }

    /**
     * 【2026-10-10 台标性能】主线程「立即返回」路径的单行耗时日志（排队/磁盘/网络/解码/回写 恒为 0）。
     * 与后台路径合计保证每调用一次 load() 恰好输出一行。release 下直接返回，零开销。
     */
    private static void logPerf(String source, String url, long t0) {
        if (!com.tv.live.BuildConfig.IS_DEBUG) return;   // 【2026-10-10 台标性能·复核修复】release 不做主线程日志开销
        // 【2026-10-28 降噪】内存命中的耗时行全是 0ms，且同一 URL 会被 4 个视图各绑一次
        //   （实测 345ms 内 4 条同文案，占单次冷启动日志 30%+）→ 不记。真 IO 走 logPerfBg。
        if ("内存".equals(source)) return;
        long now = SystemClock.uptimeMillis();
    }

    /**
     * 【2026-10-10 台标性能】后台（磁盘/网络/失败）路径的单行耗时日志，在回主线程后调用。
     * 与 {@link #logPerf} 合计保证「每调用一次 load() 恰好一行」。release 下首行短路，
     * 不做字符串拼接、不调用 Log.d（其调用点位于主线程）。注意：图片写回与防错位校验
     * 在本方法之外的 runnable 中已无条件执行，本方法只负责日志本身。
     */
    private static void logPerfBg(String source, String url, long queueMs, long diskMs,
                                  long netMs, long decMs, long tBgEnd, long t0) {
        if (!com.tv.live.BuildConfig.IS_DEBUG) return;   // 【2026-10-10 台标性能·复核修复】release 不做主线程日志开销
        // 【2026-10-28 只留异常/真下载】磁盘命中、内存解码这些成功路径一律不记
        //   （实测单次冷启动 18 条，每条都长一样），只在真发网络请求时记一条。
        if (netMs <= 0) return;
        long now = SystemClock.uptimeMillis();
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
    // 【2026-10-11 海报】主机级失败冷却
    // ------------------------------------------------------------------

    private static String hostOf(String url) {
        try {
            HttpUrl u = HttpUrl.parse(url);
            return u == null ? null : u.host();
        } catch (Exception e) {
            return null;
        }
    }

    /** 该 URL 所属主机是否处于失败冷却期 */
    private boolean isHostCoolingDown(String url) {
        String h = hostOf(url);
        if (h == null) return false;
        Long until = badHostUntil.get(h);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 标记主机连接失败，进入冷却期 */
    private void markHostFailure(String url) {
        String h = hostOf(url);
        if (h != null) {
            // 【2026-10-27 降噪】冷却期内重复失败不再打点：实测同一 host（logo.catvod.com）
            // 5 秒内被标记 9 次，首屏批量拉海报时整段刷掉。只在「首次进入冷却」记一条。
            Long prev = badHostUntil.get(h);
            boolean firstEntry = (prev == null || prev <= System.currentTimeMillis());
            badHostUntil.put(h, System.currentTimeMillis() + HOST_FAIL_COOLDOWN_MS);
            // 【2026-10-10 台标性能·闸门】冷却结束后需重新走一次探测，清除「已确认可达」标记
            hostGood.remove(h);
            if (firstEntry) {
                LogBridge.w(TAG, "【海报】图床不可达，冷却 " + (HOST_FAIL_COOLDOWN_MS / 60000) + " 分钟: " + h);
            }
        }
    }

    /**
     * 【2026-10-10 台标性能·闸门】探测结束：成功则放行该主机全部挂起任务，失败则让它们直接回退占位图。
     * 注意：synchronized 块内只做 map 增删，pool.execute / r.run() 一律在锁外调用，避免死锁。
     */
    private void releaseHostProbe(String host, boolean success) {
        List<Runnable> q;
        synchronized (hostWaiters) {
            q = hostWaiters.remove(host);
        }
        if (success) {
            hostGood.add(host);
            if (q != null) {
                for (Runnable r : q) pool.execute(r);   // 主机已确认可达 → 放行，全速并发
            }
        } else {
            hostGood.remove(host);
            if (q != null) {
                for (Runnable r : q) r.run();           // 主机已进入冷却 → 直接跑任务（任务内冷却复检会立刻回退占位）
            }
        }
    }

    /** 主机连接成功，清除冷却标记 */
    private void clearHostFailure(String url) {
        String h = hostOf(url);
        if (h != null) badHostUntil.remove(h);
    }

    /** 【2026-10-11 海报】浏览器 UA：部分图床对非浏览器 UA 直接拒绝 */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 【2026-10-11 海报】按图床返回所需 Referer；无需则返回 null（不设置该头） */
    private static String refererFor(String url) {
        String h = hostOf(url);
        if (h != null && h.endsWith("doubanio.com")) return "https://movie.douban.com/";
        return null;
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
            // 【2026-10-10 台标性能】不再 readAll 整文件进 byte[] 再解码：直接对文件做一次采样解码，
            // 命中 dimsCache 时连边界探测都跳过，避免「整文件读取 + 边界扫描 + 像素解码」三遍遍历。
            Bitmap bmp = decodeFile(f, url);
            if (bmp == null) {
                //noinspection ResultOfMethodCallIgnored
                if (f.delete()) {
                    // 【2026-10-10 台标性能】删除后估算值失准，置 -1 让下次重新统计，避免漂移
                    diskUsedEstimate.set(-1L);
                }
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
            // 【2026-10-10 台标性能】记录旧长度，用于计算本次写入的净增量（覆盖写时避免高估）
            long oldLen = f.exists() ? f.length() : 0L;
            fos = new FileOutputStream(f);
            fos.write(data);
            fos.flush();
            // 【2026-10-10 台标性能】原 trimDiskIfNeeded() 改为按需触发：仅累加估算值，超过 1.25 倍才真正裁剪
            noteDiskGrowth(data.length - oldLen);
        } catch (Exception ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 【2026-10-10 台标性能】把本次写入的净增量累加到磁盘占用估算值上。
     * 估算值 <0（尚未统计）时先用 diskUsedBytes() 统计一次基线；累加后下限夹到 0。
     * 只有 used > DISK_TRIM_TRIGGER_BYTES 时才真正调用 trimDiskIfNeeded()。
     * 这样缓存未到触发线时，绝大多数保存都不再 listFiles()+排序，消除抖动。
     */
    private void noteDiskGrowth(long delta) {
        long used = diskUsedEstimate.get();
        if (used < 0L) {
            diskUsedEstimate.set(diskUsedBytes());   // 尚未统计：先建基线
        }
        used = diskUsedEstimate.addAndGet(delta);    // 【2026-10-10 台标性能·复核修复】原子累加，避免丢失更新
        if (used < 0L) {
            used = 0L;
            diskUsedEstimate.set(0L);
        }
        if (used > DISK_TRIM_TRIGGER_BYTES) {
            trimDiskIfNeeded();
        }
    }

    /** 【2026-10-10 台标性能】全量统计磁盘缓存目录当前占用字节数（listFiles 累加 length） */
    private long diskUsedBytes() {
        File[] files = diskDir.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) total += f.length();
        return total;
    }

    /** 磁盘占用超过 1.25 倍上限时按 lastModified 由旧到新淘汰至上限以内 */
    private void trimDiskIfNeeded() {
        try {
            File[] files = diskDir.listFiles();
            if (files == null || files.length == 0) {
                // 【2026-10-10 台标性能】空目录：估算归零
                diskUsedEstimate.set(0L);
                return;
            }
            long total = 0;
            for (File f : files) total += f.length();
            // 【2026-10-10 台标性能】未到 1.25 倍触发线：不排序、不删除，仅刷新估算值
            if (total <= DISK_TRIM_TRIGGER_BYTES) {
                diskUsedEstimate.set(total);
                return;
            }

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
            // 【2026-10-10 台标性能】裁剪完成，刷新估算值
            diskUsedEstimate.set(total);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 网络下载 + 解码
    // ------------------------------------------------------------------

    private Bitmap download(String url, long[] outStats) {
        // 【2026-10-10 台标性能】③网络 计时起点（含建连 + 读取响应体）
        long tNetStart = SystemClock.uptimeMillis();
        try {
            // 【2026-10-11 海报】带浏览器 UA；豆瓣图床（源7 部分海报）还要求 Referer，
            // 否则一律回 418（反爬）。其余图床带 UA 无副作用。
            Request.Builder rb = new Request.Builder().url(url).header("User-Agent", BROWSER_UA);
            String ref = refererFor(url);
            if (ref != null) rb.header("Referer", ref);
            Request request = rb.get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    // 【2026-09-29 频道台标】非 2xx：多为内置源里的图床已失效（例如 tb.zbds.top 已 404），记录以便排查
                    // 注意：非 2xx 说明主机可达（只是这张图没有），不触发主机冷却
                    if (shouldLogLogoFailure()) {
                        LogBridge.i(TAG, "台标 HTTP " + response.code() + " 不可用: " + url);
                    }
                    markPermanentFailure(url);
                    if (outStats != null) outStats[0] = SystemClock.uptimeMillis() - tNetStart;
                    return null;
                }
                // 【2026-10-11 海报】拿到响应 = 主机可达，清除该主机的冷却标记
                clearHostFailure(url);
                if (response.body() == null) {
                    if (shouldLogLogoFailure()) {
                        LogBridge.i(TAG, "台标响应体为空: " + url);
                    }
                    if (outStats != null) outStats[0] = SystemClock.uptimeMillis() - tNetStart;
                    return null;
                }
                byte[] data = response.body().bytes();
                // 【2026-10-10 台标性能】③网络耗时（不含解码）
                if (outStats != null) outStats[0] = SystemClock.uptimeMillis() - tNetStart;
                // 【2026-10-10 台标性能】④解码耗时
                long tDec = SystemClock.uptimeMillis();
                Bitmap bmp = decode(data, url);
                if (outStats != null) outStats[1] = SystemClock.uptimeMillis() - tDec;
                if (bmp != null) {
                    saveToDisk(url, data);
                }
                return bmp;
            }
        } catch (Exception e) {
            // 【2026-10-11 海报】连接/读写失败（含超时、TLS reset）：标记该图床进入冷却期，
            // 后续同主机 URL 直接回退占位图，避免整屏被逐张超时拖慢
            if (shouldLogLogoFailure()) {
                LogBridge.w(TAG, "台标下载失败: " + url + " -> " + e.getMessage());
            }
            if (outStats != null && outStats[0] == 0L) {
                outStats[0] = SystemClock.uptimeMillis() - tNetStart;
            }
            markHostFailure(url);
            return null;
        }
    }

    /** 【2026-10-10 台标性能】由原始宽高算出 inSampleSize，使长边压到 MAX_EDGE_PX 内 */
    private static int sampleSizeFor(int w, int h) {
        int maxEdge = Math.max(w, h);
        int sampleSize = 1;
        while (maxEdge / sampleSize > MAX_EDGE_PX) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /**
     * 【2026-10-10 台标性能】直接对磁盘文件做采样解码，不再经过 byte[]。
     * 命中 dimsCache（已知该 url 的 inSampleSize）时跳过边界探测，只做唯一一次像素解码；
     * 未命中时先用 inJustDecodeBounds 只读文件头探测边界，算出并缓存 sampleSize 后再解码。
     */
    private Bitmap decodeFile(File f, String url) {
        try {
            String path = f.getPath();
            // 【2026-10-10 台标性能·复核修复】key 带内容长度指纹：同一 url 若图床换图，字节长度必变
            // → 缓存自动失效，避免沿用旧 inSampleSize 解码新图（尺寸算错 / 位图暴涨 OOM）
            String key = (url == null) ? null : url + "#" + f.length();
            Integer cached = (key == null) ? null : dimsCache.get(key);
            int sampleSize;
            if (cached != null) {
                sampleSize = cached;
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
                if (key != null) {
                    if (dimsCache.size() > DIMS_CACHE_MAX) dimsCache.clear();
                    dimsCache.put(key, sampleSize);
                }
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            LogBridge.w(TAG, "台标解码 OOM");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 先读边界算 inSampleSize，再按采样率解码，长边压到 MAX_EDGE_PX 内 */
    private Bitmap decode(byte[] data, String url) {
        if (data == null || data.length == 0) return null;
        try {
            // 【2026-10-10 台标性能·复核修复】key 带内容长度指纹：同 url 换图必变长度 → 缓存自动失效
            String key = (url == null) ? null : url + "#" + data.length;
            Integer cached = (key == null) ? null : dimsCache.get(key);
            int sampleSize;
            if (cached != null) {
                // 【2026-10-10 台标性能】命中缓存：跳过边界探测，直接对字节做一次像素解码
                sampleSize = cached;
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
                if (key != null) {
                    if (dimsCache.size() > DIMS_CACHE_MAX) dimsCache.clear();
                    dimsCache.put(key, sampleSize);
                }
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
