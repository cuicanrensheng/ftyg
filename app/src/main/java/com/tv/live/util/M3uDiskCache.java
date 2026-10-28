package com.tv.live.util;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置源磁盘缓存（2026-10-13）。
 *
 * <p>背景：内置源里 {@code 源7(afhgrj)} / {@code 源6(咪咕)} 早已具备「磁盘缓存 + 缓存优先」
 * （见 {@code AfhgrjSourceLoader} / {@code MiguSourceLoader}），而
 * {@code 源3(畅享)} / {@code 源5(爱其意)} / {@code 源5点播} 是<b>纯同步网络</b>：
 * 每次启动都要现场拉一遍，网络慢或端点超时（实测源5 直播端点有 10s 超时）时，
 * OTT 首页的分组频道会长时间空着。本类把源7 那套模板抽成公共实现，供三个源复用。
 *
 * <p>语义（与 {@code AfhgrjSourceLoader} 完全一致）：
 * <ol>
 *   <li><b>缓存优先（stale-while-revalidate）</b>：只要磁盘上有内容——哪怕已过期——
 *       也立刻返回，绝不阻塞首屏；过期时再异步触发一次后台刷新；</li>
 *   <li><b>TTL</b>：过期只影响「是否触发后台刷新」，不影响「能否用缓存」；</li>
 *   <li><b>失败冷却</b>：一次抓取彻底失败（结果为空）后进入冷却期，冷却期内
 *       无缓存则直接失败返回（不再空等网络），有缓存则只读缓存不联网；</li>
 *   <li><b>原子写</b>：先写唯一临时文件再 rename 覆盖，避免并发读到半截文件；</li>
 *   <li><b>后台刷新单飞</b>：{@link AtomicBoolean} 保证同一时刻只有一个刷新在跑。</li>
 * </ol>
 *
 * <p>线程安全：读缓存走文件读（无共享可变状态），失败时刻与刷新守卫均为 volatile/atomic。
 *
 * <p>注意：缓存内容在返回给上层后仍会经过 {@code LiveSourceLoader} 的统一出口
 * {@link ChannelBlocklist#filter}，因此被屏蔽的内容不会从缓存里"复活"。
 */
public final class M3uDiskCache {

    /** 抓取器：返回要缓存的文本（M3U 全文或片段）；返回 null / 空串表示失败。 */
    public interface Fetcher {
        String fetch();
    }

    private final String tag;
    /** 日志里的源名，如「源5(爱其意)直播」 */
    private final String label;
    private final String fileName;
    private final long ttlMs;
    private final long failCooldownMs;
    private final Fetcher fetcher;

    /**
     * 【2026-10-22 I1-03】全部 M3uDiskCache 业务缓存文件名注册表。
     *
     * <p>启动清理（{@code CacheManager.AppCacheGovernor}）原先按「age>7 天即删」扫
     * filesDir 全树，把各源磁盘缓存误删，表现为「放了几天的设备分组缓存反复重抓」。
     * 构造时自动注册，新增源无需改清理端；清理端经
     * {@link #isBusinessDiskCacheName(String)} 查询豁免。
     */
    private static final java.util.Set<String> sDiskCacheNames =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 是否是 M3uDiskCache 业务磁盘缓存文件名（启动清理豁免判定用）。 */
    public static boolean isBusinessDiskCacheName(String fileName) {
        return fileName != null && sDiskCacheNames.contains(fileName);
    }

    private volatile long lastFailAtMs = 0L;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final ExecutorService executor;

    public M3uDiskCache(String tag, String label, String fileName,
                        long ttlMs, long failCooldownMs, Fetcher fetcher) {
        this.tag = tag;
        this.label = label;
        this.fileName = fileName;
        this.ttlMs = ttlMs;
        this.failCooldownMs = failCooldownMs;
        this.fetcher = fetcher;
        sDiskCacheNames.add(fileName);
        final String threadName = "src-cache-" + fileName;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 取内容：缓存优先。
     *
     * @return 内容文本；既无缓存、又抓取失败时返回 {@code null}
     */
    public String load(Context context) {
        String cached = readDiskCache(context);

        // ① 有缓存（含过期）→ 立刻返回，过期时异步刷新
        if (!TextUtils.isEmpty(cached)) {
            if (isExpired(context)) {
                if (isInFailCooldown()) {
                    LogBridge.i(tag, label + "：抓取失败冷却期内，直接使用磁盘缓存，不联网");
                } else {
                    LogBridge.i(tag, label + "：磁盘缓存已过期，先用缓存返回并异步刷新");
                    triggerBackgroundRefresh(context);
                }
            } else {
                LogBridge.i(tag, label + "：命中磁盘缓存，跳过抓取");
            }
            return cached;
        }

        // ② 无缓存且处于失败冷却期 → 直接失败返回，避免每次启动都空等
        if (isInFailCooldown()) {
            LogBridge.w(tag, label + "：无磁盘缓存且处于抓取失败冷却期内，跳过同步抓取");
            return null;
        }

        // ③ 无缓存且非冷却期 → 同步抓取
        return fetchAndCacheBlocking(context);
    }

    /**
     * 同步抓取并写缓存。
     *
     * <p>抓取失败（结果为空）时记录失败时刻并进入冷却期；抓取抛异常同样按失败处理，
     * 绝不让异常穿透到调用方（调用方原本用 try/catch 包着，这里保持同样的兜底）。
     */
    public String fetchAndCacheBlocking(Context context) {
        String out;
        try {
            out = fetcher.fetch();
        } catch (Throwable t) {
            LogBridge.w(tag, label + "：抓取异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            out = null;
        }
        if (TextUtils.isEmpty(out)) {
            lastFailAtMs = System.currentTimeMillis();
            LogBridge.w(tag, label + "：抓取失败，进入 " + (failCooldownMs / 1000L)
                    + "s 冷却期");
            return null;
        }
        lastFailAtMs = 0L;
        writeDiskCache(context, out);
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 是否处于抓取失败冷却期内 */
    private boolean isInFailCooldown() {
        long last = lastFailAtMs;
        return last > 0L && (System.currentTimeMillis() - last) < failCooldownMs;
    }

    /** 异步触发一次后台刷新（陈旧缓存快路径的 revalidate 半），单飞守卫 */
    private void triggerBackgroundRefresh(final Context context) {
        if (context == null) {
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            LogBridge.i(tag, label + "：后台刷新已在进行，跳过重复触发");
            return;
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        LogBridge.i(tag, label + "：后台刷新开始（陈旧缓存快路径触发）");
                        fetchAndCacheBlocking(appCtx);
                    } catch (Throwable t) {
                        LogBridge.w(tag, label + "：后台刷新异常 " + t.getMessage());
                    } finally {
                        refreshing.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            refreshing.set(false);
            LogBridge.w(tag, label + "：后台刷新调度失败 " + t.getMessage());
        }
    }

    private File cacheFile(Context context) {
        if (context == null) {
            return null;
        }
        return new File(context.getFilesDir(), fileName);
    }

    public boolean isExpired(Context context) {
        File f = cacheFile(context);
        if (f == null || !f.exists()) {
            return true;
        }
        return System.currentTimeMillis() - f.lastModified() > ttlMs;
    }

    public String readDiskCache(Context context) {
        File f = cacheFile(context);
        if (f == null || !f.exists() || f.length() <= 0) {
            return "";
        }
        FileInputStream fis = null;
        BufferedReader br = null;
        try {
            fis = new FileInputStream(f);
            br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            LogBridge.w(tag, label + "：读缓存失败 " + t.getMessage());
            return "";
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
            if (fis != null) { try { fis.close(); } catch (IOException ignored) {} }
        }
    }

    public void writeDiskCache(Context context, String content) {
        if (context == null || TextUtils.isEmpty(content)) {
            return;
        }
        File target = cacheFile(context);
        if (target == null) {
            return;
        }
        // 原子写：唯一临时名（nanoTime + 线程 id）避免并发写同一 tmp 造成内容交错
        File tmp = new File(target.getParentFile(),
                target.getName() + ".tmp." + System.nanoTime() + "." + Thread.currentThread().getId());
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            fos = null;
            if (!tmp.renameTo(target)) {
                LogBridge.w(tag, label + "：写缓存 rename 失败，删除临时文件");
                if (!tmp.delete()) {
                    LogBridge.w(tag, label + "：删除临时文件失败 " + tmp.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            LogBridge.w(tag, label + "：写缓存失败 " + t.getMessage());
            if (tmp.exists() && !tmp.delete()) {
                LogBridge.w(tag, label + "：删除临时文件失败 " + tmp.getAbsolutePath());
            }
        } finally {
            if (fos != null) { try { fos.close(); } catch (IOException ignored) {} }
        }
    }
}
