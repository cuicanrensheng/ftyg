package com.tv.live.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3U8 广告剥离结果缓存
 * - LRU 淘汰，最大 100 条
 * - 按 URL 缓存，5 分钟过期
 * - 用于切换频道/换集时秒开
 */
public class M3u8Cache {
    private static final int MAX_ENTRIES = 100;
    private static final long EXPIRY_MS = 5 * 60 * 1000L; // 5分钟

    private static class CacheEntry {
        final String content;
        final long timestamp;
        final int originalSegments;
        final int strippedSegments;

        CacheEntry(String content, int originalSegments, int strippedSegments) {
            this.content = content;
            this.timestamp = System.currentTimeMillis();
            this.originalSegments = originalSegments;
            this.strippedSegments = strippedSegments;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > EXPIRY_MS;
        }
    }

    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private static M3u8Cache instance;

    public static synchronized M3u8Cache getInstance() {
        if (instance == null) {
            instance = new M3u8Cache();
        }
        return instance;
    }

    private M3u8Cache() {}

    /**
     * 获取缓存
     * @return 缓存的 m3u8 内容，未命中或过期返回 null
     */
    public synchronized String get(String url) {
        CacheEntry entry = cache.get(url);
        if (entry == null) return null;
        if (entry.isExpired()) {
            cache.remove(url);
            return null;
        }
        LogBridge.d("M3u8Cache", "命中缓存: " + url.substring(0, Math.min(50, url.length()))
                + " (剔除" + (entry.originalSegments - entry.strippedSegments) + "分片)");
        return entry.content;
    }

    /**
     * 存入缓存
     */
    public synchronized void put(String url, String content, int originalSegments, int strippedSegments) {
        cache.put(url, new CacheEntry(content, originalSegments, strippedSegments));
        LogBridge.d("M3u8Cache", "存入缓存: " + url.substring(0, Math.min(50, url.length()))
                + " (原" + originalSegments + "片, 剩" + strippedSegments + "片)");
    }

    /**
     * 检查是否有有效缓存
     */
    public synchronized boolean hasValid(String url) {
        CacheEntry entry = cache.get(url);
        return entry != null && !entry.isExpired();
    }

    /**
     * 清除所有缓存
     */
    public synchronized void clear() {
        cache.clear();
        LogBridge.d("M3u8Cache", "缓存已清空");
    }

    /**
     * 获取缓存大小
     */
    public synchronized int size() {
        return cache.size();
    }
}
