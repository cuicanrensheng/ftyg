package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.tv.live.util.LogBridge;

import com.tv.live.Channel;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SourceHealthChecker {
    private static final String TAG = "SourceHealthChecker";

    private static final String SP_NAME = "source_health";
    private static final String KEY_ENABLED = "health_check_enabled";
    private static final String KEY_FAIL_PREFIX = "fail_";
    private static final String KEY_LAST_CHECK = "last_full_check";

    private static final int FAIL_THRESHOLD = 3;

    private static final int CHECK_TIMEOUT_MS = 8000;

    private static final long FULL_CHECK_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L;

    private static final int MAX_CONCURRENT = 8;

    private static final long DNS_FAIL_CACHE_MS = 30 * 60 * 1000L;

    private final Context context;
    private final SharedPreferences sp;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService checkExecutor =
            Executors.newFixedThreadPool(MAX_CONCURRENT);
    private final ExecutorService singleExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SourceHealth-Worker");
                t.setDaemon(true);
                return t;
            });

    private final Map<String, Integer> failCountMap = new ConcurrentHashMap<>();

    private final Map<String, Long> removedUrls = new ConcurrentHashMap<>();

    private final Map<String, Long> dnsFailHosts = new ConcurrentHashMap<>();

    private volatile boolean isFullCheckRunning = false;
    private OnHealthCheckListener listener;

    public interface OnHealthCheckListener {

        void onCheckComplete(int removedCount, int totalChecked);

        void onUrlRemoved(String channelName, String url);
    }

    public SourceHealthChecker(Context context) {
        this.context = context.getApplicationContext();
        this.sp = this.context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        loadFailCounts();
    }

    public void setListener(OnHealthCheckListener listener) {
        this.listener = listener;
    }

    public boolean isEnabled() {
        return sp.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public void markFailed(String url, Channel channel) {
        if (url == null || url.isEmpty()) return;
        if (!isEnabled()) return;
        if (removedUrls.containsKey(url)) return;

        singleExecutor.execute(() -> {
            Integer prevCount = failCountMap.get(url);
            int count = (prevCount == null ? 0 : prevCount) + 1;
            failCountMap.put(url, count);
            saveFailCount(url, count);
            LogBridge.w(TAG, "源失败标记 [" + count + "/" + FAIL_THRESHOLD + "]: " + url);

            if (count >= FAIL_THRESHOLD) {
                removeUrl(url, channel);
            }
        });
    }

    public void markSuccess(String url) {
        if (url == null || url.isEmpty()) return;
        Integer count = failCountMap.remove(url);
        if (count != null && count > 0) {
            clearFailCount(url);
            LogBridge.d(TAG, "源成功，重置失败计数: " + url);
        }
    }

    private void removeUrl(String url, Channel channel) {
        if (url == null) return;
        removedUrls.put(url, System.currentTimeMillis());

        if (channel != null) {

            Iterator<String> it = channel.getBackupUrls().iterator();
            boolean removed = false;
            while (it.hasNext()) {
                if (url.equals(it.next())) {
                    it.remove();
                    removed = true;
                    LogBridge.w(TAG, "已剔除失效备用源: " + channel.getName() + " → " + url);
                    break;
                }
            }

            if (!removed && url.equals(channel.getMainPlayUrl())) {
                if (!channel.getBackupUrls().isEmpty()) {
                    String newMain = channel.getBackupUrls().remove(0);
                    channel.setMainPlayUrl(newMain);
                    LogBridge.w(TAG, "主源失效，提升备用源为主源: " + channel.getName() +
                            "\n  旧: " + url + "\n  新: " + newMain);
                    removed = true;
                }
            }
        }

        clearFailCount(url);

        if (listener != null) {
            final String chName = (channel != null) ? channel.getName() : "未知";
            mainHandler.post(() -> {
                if (listener != null) listener.onUrlRemoved(chName, url);
            });
        }
    }

    public boolean isRemoved(String url) {
        return removedUrls.containsKey(url);
    }

    public void checkAll(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return;
        if (!isEnabled()) return;
        if (isFullCheckRunning) {
            LogBridge.d(TAG, "全量检测已在运行中，跳过");
            return;
        }

        isFullCheckRunning = true;
        long now = System.currentTimeMillis();
        long lastCheck = sp.getLong(KEY_LAST_CHECK, 0);
        if (now - lastCheck < FULL_CHECK_INTERVAL_MS) {
            LogBridge.d(TAG, "距上次全量检测不足7天，跳过");
            isFullCheckRunning = false;
            return;
        }
        sp.edit().putLong(KEY_LAST_CHECK, now).apply();

        singleExecutor.execute(() -> {
            LogBridge.i(TAG, "开始全量源检测，频道数: " + channels.size());
            final AtomicInteger totalChecked = new AtomicInteger(0);
            final AtomicInteger removedCount = new AtomicInteger(0);

            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            for (Channel ch : channels) {

                futures.add(checkExecutor.submit(() -> {
                    String url = ch.getMainPlayUrl();
                    if (url != null && !url.isEmpty() && !isRemoved(url)) {

                        if (isHuyaRoomUrl(url)) {

                            return;
                        }
                        totalChecked.incrementAndGet();
                        if (!checkUrl(url)) {
                            Integer prevFails = failCountMap.get(url);
                            int fails = (prevFails == null ? 0 : prevFails) + 1;
                            failCountMap.put(url, fails);
                            saveFailCount(url, fails);
                            if (fails >= FAIL_THRESHOLD) {
                                removeUrl(url, ch);
                                removedCount.incrementAndGet();
                            }
                        } else {
                            failCountMap.remove(url);
                            clearFailCount(url);
                        }
                    }
                }));

                List<String> backups = new ArrayList<>(ch.getBackupUrls());
                for (String url : backups) {
                    if (isRemoved(url)) continue;
                    futures.add(checkExecutor.submit(() -> {
                        if (isHuyaRoomUrl(url)) {

                            return;
                        }
                        totalChecked.incrementAndGet();
                        if (!checkUrl(url)) {
                            Integer prevFails = failCountMap.get(url);
                            int fails = (prevFails == null ? 0 : prevFails) + 1;
                            failCountMap.put(url, fails);
                            saveFailCount(url, fails);
                            if (fails >= FAIL_THRESHOLD) {
                                removeUrl(url, ch);
                                removedCount.incrementAndGet();
                            }
                        } else {
                            failCountMap.remove(url);
                            clearFailCount(url);
                        }
                    }));
                }
            }

            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }

            final int checked = totalChecked.get();
            final int removed = removedCount.get();
            LogBridge.i(TAG, "全量源检测完成: 检测 " + checked + " 个URL, 剔除 " + removed + " 个失效源");

            isFullCheckRunning = false;

            if (listener != null) {
                mainHandler.post(() -> {
                    if (listener != null) listener.onCheckComplete(removed, checked);
                });
            }
        });
    }

    private boolean isHuyaRoomUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            if (!host.contains("huya.com") && !host.contains("huya.cn")) return false;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return false;
            String roomIdStr = path.replace("/", "").trim();
            return roomIdStr.matches("\\d+");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkUrl(String urlStr) {

        if (isDnsRecentlyFailed(urlStr)) {
            LogBridge.d(TAG, "跳过检测（DNS 近期失败）: " + urlStr);
            return true;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CHECK_TIMEOUT_MS);
            conn.setReadTimeout(CHECK_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "TVLive-HealthCheck/1.0");

            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();

            return code >= 200 && code < 400;
        } catch (UnknownHostException e) {

            recordDnsFailure(urlStr, e);
            LogBridge.d(TAG, "DNS 解析失败(不计入失效，已缓存跳过): " + urlStr + " → " + e.getMessage());
            return true;
        } catch (Exception e) {

            LogBridge.d(TAG, "检测异常(不计入失效): " + urlStr + " → " + e.getMessage());
            return true;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String extractHost(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isDnsRecentlyFailed(String urlStr) {
        String host = extractHost(urlStr);
        if (host == null) return false;
        Long t = dnsFailHosts.get(host);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > DNS_FAIL_CACHE_MS) {
            dnsFailHosts.remove(host);
            return false;
        }
        return true;
    }

    private void recordDnsFailure(String urlStr, Throwable e) {
        String host = null;
        String msg = (e != null) ? e.getMessage() : null;
        if (msg != null) {
            int i = msg.indexOf('"');
            int j = (i >= 0) ? msg.indexOf('"', i + 1) : -1;
            if (i >= 0 && j > i) {
                host = msg.substring(i + 1, j);
            }
        }
        if (host == null || host.isEmpty()) {
            host = extractHost(urlStr);
        }
        if (host != null && !host.isEmpty()) {
            dnsFailHosts.put(host, System.currentTimeMillis());
        }
    }

    private void loadFailCounts() {
        Map<String, ?> all = sp.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_FAIL_PREFIX)) {
                try {
                    String url = key.substring(KEY_FAIL_PREFIX.length());
                    int count = (Integer) entry.getValue();
                    failCountMap.put(url, count);
                } catch (Exception ignored) {}
            }
        }
        LogBridge.d(TAG, "加载失败记录: " + failCountMap.size() + " 条");
    }

    private void saveFailCount(String url, int count) {
        sp.edit().putInt(KEY_FAIL_PREFIX + url, count).apply();
    }

    private void clearFailCount(String url) {
        sp.edit().remove(KEY_FAIL_PREFIX + url).apply();
    }

    public void resetAll() {
        failCountMap.clear();
        removedUrls.clear();
        dnsFailHosts.clear();
        SharedPreferences.Editor editor = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(KEY_FAIL_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
        LogBridge.i(TAG, "已重置所有源健康记录");
    }

    public String getStats() {
        return "已剔除: " + removedUrls.size() + " 个源, " +
                "失败记录: " + failCountMap.size() + " 条";
    }

    public void release() {
        try {
            mainHandler.removeCallbacksAndMessages(null);
            if (!checkExecutor.isShutdown()) {
                checkExecutor.shutdownNow();
            }
            if (!singleExecutor.isShutdown()) {
                singleExecutor.shutdownNow();
            }
            failCountMap.clear();
            removedUrls.clear();
            dnsFailHosts.clear();
            listener = null;
        } catch (Exception e) {
            LogBridge.e(TAG, "release异常: " + e.getMessage());
        }
    }
}
