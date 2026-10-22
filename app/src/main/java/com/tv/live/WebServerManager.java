package com.tv.live;
import android.util.Log;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.LinkedHashMap;

import com.tv.live.util.M3u8AdStripper;
import com.tv.live.util.LogBridge;

public class WebServerManager {

    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_CUSTOM_UA = "custom_user_agent";
    private static final String SP_NAME = "app_settings";

    private static final long SUBMIT_COOLDOWN = 2000;

    private Context context;
    private int port;
    private ServerSocket serverSocket;
    private Handler handler = new Handler(Looper.getMainLooper());
    // 【2026-10-22 P2-05】volatile：accept 线程写、请求线程/stop() 读，无可见性保证时
    //   停服后 accept 循环可能仍在跑（旧值缓存）
    private volatile boolean isRunning = false;

    /**
     * 【2026-10-22 I1-12】代理接口 token：每次实例化随机生成。
     * /m3u8 与 /ts 原为无鉴权开放代理——同网段任何设备可以电视为跳板拉取任意 URL
     * （含内网地址），并消耗设备带宽/触发其对第三方站点的请求。现加 token 校验：
     * 播放器 URL 生成端（{@link #getAdStripProxyUrl} / {@link #rewriteSegmentUrls}）
     * 自动拼 token，网页端无 token 一律 403。不绑 127.0.0.1 是为了保住「网页设置」
     * 的 LAN 功能（strings.xml:34 明示 http://设备IP:10481）——token 是两者兼顾的折中。
     */
    private final String proxyToken = java.util.UUID.randomUUID().toString();

    /** 代理接口 token 校验（/m3u8、/ts 共用） */
    private boolean checkProxyToken(String token) {
        return proxyToken != null && proxyToken.equals(token);
    }

    private ExecutorService acceptExecutor;
    private ExecutorService requestExecutor;

    private static WebServerManager runningInstance;

    /**
     * 【2026-10-22 I1-05】单实例启动守卫。
     *
     * <p>runningInstance 在 accept 线程 bind 成功后异步赋值，仅靠它去重存在竞态窗口：
     * 预启动实例 bind 途中，第二个实例的 getRunningInstance() 仍为 null → 也去 bind
     * 10481 → findAvailablePort 恰好探测到端口空闲 → 漂移绑 10482 并篡夺
     * runningInstance（实测双代理并存、端口漂移、孤儿实例）。
     * 以静态锁 + 「已启动」标记收敛：任何入口重复 start() 直接跳过。
     */
    private static final Object sStartLock = new Object();
    private static boolean sStartingOrRunning = false;

    private long lastSubmitTime = 0;

    // ========== 广告剥离m3u8缓存（5分钟过期，LRU 100条） ==========
    private static final int M3U8_CACHE_MAX_SIZE = 100;
    private static final long M3U8_CACHE_TTL_MS = 5 * 60 * 1000L;
    // 【2026-10-17 直播黑屏修复】直播清单（无 #EXT-X-ENDLIST）绝不能按 5 分钟缓存：
    //   直播清单是滑动窗口（本例 9 分片×4s≈36s），窗口播完后代理仍返回同一份旧清单，
    //   播放器拿不到新分片 → 卡 6s → ExoPlaybackException(Source error, 2000) → STATE_IDLE(黑屏)
    //   → 3s 后自动重试、代理换新实例拿到新清单才恢复（用户看到"黑屏一下又马上出画面"）。
    //   实测证据：09:39:16/21/26 三次 5s 轮询全部"缓存命中 0ms"，26.95s 报严重卡顿 6317ms +
    //   Source error，旧位置 88s（正好一个窗口播完）。直播只保留极短缓存，仅用于合并
    //   起播瞬间的重复请求（不降低上游请求频率：播放器本来就是每 targetDuration 拉一次）。
    private static final long M3U8_LIVE_CACHE_TTL_MS = 1200L;
    private static final String TAG_HLS_ENDLIST = "#EXT-X-ENDLIST";
    private static final long STRIP_TIMEOUT_MS = 500L;

    // ========== 分片代理缓存（2026-10-21 seek 快速恢复：SEEK 中断后代理继续转发并缓存，下次命中秒回） ==========
    /** 内存上限 32MB（电视盒子内存有限，按字节 LRU 淘汰，防止缓存膨胀） */
    private static final int SEG_CACHE_MAX_BYTES = 32 * 1024 * 1024;
    /** 点播分片是静态文件，10 分钟 TTL 足够覆盖 seek 回放窗口 */
    private static final long SEG_CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 分片条目数上限（防御性保护，配合字节上限双保险） */
    private static final int SEG_CACHE_MAX_ENTRIES = 128;
    /** 分片转发上游（CDN）的读取超时 */
    private static final long SEG_FETCH_TIMEOUT_MS = 8000L;
    /** 并发去重等待上限 */
    private static final long SEG_DEDUP_WAIT_MS = 5000L;

    // 内存LRU缓存：key=原始URL, value=[剥离后内容, 时间戳]
    private final LinkedHashMap<String, Object[]> m3u8Cache = new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
            return size() > M3U8_CACHE_MAX_SIZE;
        }
    };

    // 剥离线程池（用于超时控制和异步预热）
    private ExecutorService stripExecutor;
    // 【2026-10-16】预加载串行化控制：避免与播放请求并发导致网络拥塞
    private final AtomicBoolean preloadRunning = new AtomicBoolean(false);
    private volatile Future<?> preloadFuture = null;

    // 【2026-10-16】广告剥离请求去重：相同URL的并发请求共享同一个处理结果，避免重复下载+剥离
    private final java.util.concurrent.ConcurrentHashMap<String, Future<String>> pendingStripRequests = new java.util.concurrent.ConcurrentHashMap<>();

    // ========== 分片代理（2026-10-21 seek 快速恢复） ==========
    // 分片内存 LRU 缓存：key=分片URL+"|"+Range头, value=[byte[], 时间戳, 字节数, 响应码]
    private final LinkedHashMap<String, Object[]> segCache = new LinkedHashMap<String, Object[]>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
            return size() > SEG_CACHE_MAX_ENTRIES;
        }
    };
    private long segCacheBytes = 0;
    // 分片请求去重：相同分片并发请求共享结果。SEEK 打断播放器侧后，
    // 代理线程继续转发完成并回填缓存 = 天然的"预取"（下次 seek 回该位置直接命中）
    private final java.util.concurrent.ConcurrentHashMap<String, Future<SegResult>> pendingTsRequests = new java.util.concurrent.ConcurrentHashMap<>();
    // 分片转发独立线程池：不挤占剥离线程（剥离是起播关键路径）
    private ExecutorService segExecutor;

    public WebServerManager(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    public void start() {
        if (isRunning) return;
        synchronized (sStartLock) {
            if (sStartingOrRunning) {
                LogBridge.i("WebServer", "已有代理实例启动中/运行中，跳过重复启动");
                return;
            }
            sStartingOrRunning = true;
        }

        int actualPort = findAvailablePort(port);
        if (actualPort == -1) {
            isRunning = false;
            synchronized (sStartLock) { sStartingOrRunning = false; }
            return;
        }
        this.port = actualPort;
        final int finalPort = actualPort;

        acceptExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WebServer-Accept");
            t.setDaemon(true);
            return t;
        });
        requestExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "WebServer-Conn");
            t.setDaemon(true);
            return t;
        });
        stripExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AdStrip-Worker");
            t.setDaemon(true);
            return t;
        });
        segExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AdStrip-Seg");
            t.setDaemon(true);
            return t;
        });

        acceptExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress(finalPort));

                isRunning = true;
                runningInstance = this;

                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        requestExecutor.execute(() -> handleHttpRequest(socket));
                    } catch (Exception e) {
                        if (!serverSocket.isClosed()) {

                        }
                    }
                }

                isRunning = false;
                runningInstance = null;
                // 【2026-10-22 I1-05】accept 循环退出（socket 关闭）同样复位守卫
                synchronized (sStartLock) { sStartingOrRunning = false; }

            } catch (Exception e) {
                e.printStackTrace();
                isRunning = false;
                runningInstance = null;
                // 【2026-10-22 I1-05】bind 失败需复位守卫，否则后续启动全被拒
                synchronized (sStartLock) { sStartingOrRunning = false; }
            }
        });
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                isRunning = false;
                if (runningInstance == this) {
                    runningInstance = null;
                }
                // 【2026-10-22 I1-05】显式停止后复位守卫，允许后续重新启动
                synchronized (sStartLock) { sStartingOrRunning = false; }
            }
            handler.removeCallbacksAndMessages(null);
            if (acceptExecutor != null) acceptExecutor.shutdownNow();
            if (requestExecutor != null) requestExecutor.shutdownNow();
            if (stripExecutor != null) stripExecutor.shutdownNow();
            if (segExecutor != null) segExecutor.shutdownNow();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int findAvailablePort(int startPort) {
        int maxTry = 10;
        for (int i = 0; i < maxTry; i++) {
            int tryPort = startPort + i;
            if (!isPortInUse(tryPort)) {
                return tryPort;
            }
        }
        return -1;
    }

    private boolean isPortInUse(int port) {
        try {
            ServerSocket testSocket = new ServerSocket();
            testSocket.setReuseAddress(true);
            testSocket.bind(new java.net.InetSocketAddress(port));
            testSocket.close();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public String getAccessUrl() {
        return "http://" + getDeviceIPAddress() + ":" + port;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getPort() {
        return port;
    }

    private void handleHttpRequest(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            List<String> headerLines = new ArrayList<>();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (line.isEmpty()) break;
                headerLines.add(line);
                if (lineCount > 100) break;
            }

            if (headerLines.isEmpty()) {
                socket.close();
                return;
            }

            String firstLine = headerLines.get(0);
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                sendResponse(socket, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            for (String headerLine : headerLines) {
                if (headerLine.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    } catch (Exception e) {
                        contentLength = 0;
                    }
                    break;
                }
            }

            String body = "";
            if ("POST".equals(method) && contentLength > 0) {
                char[] bodyBuffer = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int len = reader.read(bodyBuffer, totalRead, contentLength - totalRead);
                    if (len <= 0) break;
                    totalRead += len;
                }
                body = new String(bodyBuffer, 0, totalRead);
            }

            String responseBody = "";
            String contentType = "text/html; charset=utf-8";
            String purePath = path.contains("?") ? path.split("\\?")[0] : path;

            if ("GET".equals(method) && "/m3u8".equals(purePath)) {
                // 广告剥离代理接口：/m3u8?url=原始m3u8地址
                String targetUrl = "";
                String channelName = "";
                String token = "";
                if (path.contains("?")) {
                    String query = path.substring(path.indexOf("?") + 1);
                    for (String pair : query.split("&")) {
                        if (pair.startsWith("url=")) {
                            targetUrl = URLDecoder.decode(pair.substring(4), "UTF-8");
                        } else if (pair.startsWith("ch=")) {
                            channelName = URLDecoder.decode(pair.substring(3), "UTF-8");
                        } else if (pair.startsWith("token=")) {
                            token = URLDecoder.decode(pair.substring(6), "UTF-8");
                        }
                    }
                }
                if (targetUrl.isEmpty()) {
                    sendResponse(socket, "400 Bad Request", "text/plain", "Missing url parameter");
                    return;
                }
                // 【2026-10-22 I1-12】token 校验：无 token/错 token 一律 403（见字段声明处）
                if (!checkProxyToken(token)) {
                    LogBridge.w("WebServer", "代理接口 token 校验失败（/m3u8），已拒绝");
                    sendResponse(socket, "403 Forbidden", "text/plain", "Invalid token");
                    return;
                }
                LogBridge.i("WebServer", "========== 广告剥离代理请求开始 ==========");
                if (!channelName.isEmpty()) {
                    LogBridge.i("WebServer", "频道: " + channelName);
                }
                LogBridge.i("WebServer", "目标URL: " + targetUrl.substring(0, Math.min(100, targetUrl.length())));
                long stripStartTime = System.currentTimeMillis();
                String cached = getM3u8Cache(targetUrl);
                if (cached != null) {
                    LogBridge.i("WebServer", "缓存命中，直接返回，耗时=" + (System.currentTimeMillis() - stripStartTime) + "ms");
                    sendResponse(socket, "200 OK", "application/vnd.apple.mpegurl; charset=utf-8", cached);
                    return;
                }

                // 【2026-10-16】请求去重：检查是否有相同URL正在处理
                Future<String> existingFuture = pendingStripRequests.get(targetUrl);
                if (existingFuture != null && !existingFuture.isDone()) {
                    LogBridge.i("WebServer", "请求去重：等待相同URL的处理结果");
                    try {
                        String dedupResult = existingFuture.get(3, TimeUnit.SECONDS);
                        LogBridge.i("WebServer", "请求去重：获取到共享结果，耗时=" + (System.currentTimeMillis() - stripStartTime) + "ms");
                        sendResponse(socket, "200 OK", "application/vnd.apple.mpegurl; charset=utf-8", dedupResult);
                        return;
                    } catch (Exception e) {
                        LogBridge.w("WebServer", "请求去重等待超时(" + (System.currentTimeMillis() - stripStartTime) + "ms)，自行处理: " + e.getMessage());
                    }
                }

                // 创建处理任务
                final String finalTargetUrl = targetUrl;
                Callable<String> stripTask = new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        try {
                            String m3u8Content = downloadM3u8(finalTargetUrl);
                            if (m3u8Content.isEmpty()) return null;
                            String finalContent = m3u8Content;
                            String finalBaseUrl = finalTargetUrl;
                            if (M3u8AdStripper.isMasterPlaylist(m3u8Content)) {
                                LogBridge.i("WebServer", "检测到 Master Playlist，下载第一个子流");
                                String variantUrl = M3u8AdStripper.getFirstVariantUrl(m3u8Content, finalTargetUrl);
                                if (variantUrl != null && !variantUrl.isEmpty()) {
                                    String variantContent = downloadM3u8(variantUrl);
                                    if (!variantContent.isEmpty()) {
                                        finalContent = variantContent;
                                        finalBaseUrl = variantUrl;
                                    }
                                }
                            }
                            int beforeSegments = countSegmentsInM3u8(finalContent);
                            LogBridge.i("WebServer", "剥离前: 分片数=" + beforeSegments);
                            // 【2026-10-22 P1-05】剥离步骤改走 stripWithTimeout（500ms 超时降级
                            //   返回原文 + 后台继续剥离存缓存），替代原先同步硬算：
                            //   慢源大体量清单（多维评分/编辑距离）此前把起播关键路径拖过 5s，
                            //   代理返 500 → 播放器 3s 退避重试 → 再次超时，形成重试风暴。
                            String stripped;
                            try {
                                stripped = stripWithTimeout(finalContent, finalBaseUrl, finalTargetUrl);
                            } catch (Exception stripEx) {
                                LogBridge.e("WebServer", "广告剥离异常，返回原文(已绝对化): " + stripEx.getMessage());
                                try {
                                    stripped = M3u8AdStripper.resolveAllUrls(finalContent, finalBaseUrl);
                                } catch (Exception rex) {
                                    stripped = finalContent;
                                }
                            }
                            int afterSegments = countSegmentsInM3u8(stripped);
                            LogBridge.i("WebServer", "剥离后: 分片数=" + afterSegments + ", 剔除=" + (beforeSegments - afterSegments) + " 个");
                            return putM3u8Cache(finalTargetUrl, stripped);
                        } finally {
                            pendingStripRequests.remove(finalTargetUrl);
                        }
                    }
                };

                Future<String> future = stripExecutor.submit(stripTask);
                pendingStripRequests.put(targetUrl, future);

                try {
                    String result = future.get(5, TimeUnit.SECONDS);
                    if (result == null) {
                        sendResponse(socket, "502 Bad Gateway", "text/plain", "Failed to download m3u8");
                        return;
                    }
                    LogBridge.i("WebServer", "========== 广告剥离代理请求结束，总耗时=" + (System.currentTimeMillis() - stripStartTime) + "ms ==========");
                    sendResponse(socket, "200 OK", "application/vnd.apple.mpegurl; charset=utf-8", result);
                } catch (Exception e) {
                    // 【2026-10-22 P1-05】仅剩「上游下载慢」会走到这里（剥离已 500ms 降级不阻塞）；
                    //   502 语义为上游网关超时。播放器 3s 退避重试时后台任务多已存入缓存，
                    //   下次请求直接命中，不再形成连续超时风暴。
                    LogBridge.e("WebServer", "广告剥离下载超时或异常: " + e.getMessage());
                    sendResponse(socket, "502 Bad Gateway", "text/plain", "Upstream download timeout");
                }
                return;
            } else if ("GET".equals(method) && "/ts".equals(purePath)) {
                // 【2026-10-21 seek 快速恢复】点播分片代理接口：/ts?url=原始分片地址
                // 命中缓存秒回；未命中由代理转发 CDN 并回填缓存（Range 透传，兼容 BYTERANGE 分片）。
                String segUrl = "";
                String token = "";
                if (path.contains("?")) {
                    String query = path.substring(path.indexOf("?") + 1);
                    for (String pair : query.split("&")) {
                        if (pair.startsWith("url=")) {
                            segUrl = URLDecoder.decode(pair.substring(4), "UTF-8");
                        } else if (pair.startsWith("token=")) {
                            token = URLDecoder.decode(pair.substring(6), "UTF-8");
                        }
                    }
                }
                if (segUrl.isEmpty()) {
                    sendResponse(socket, "400 Bad Request", "text/plain", "Missing url parameter");
                    return;
                }
                // 【2026-10-22 I1-12】token 校验：与 /m3u8 同口径（见字段声明处）
                if (!checkProxyToken(token)) {
                    LogBridge.w("WebServer", "代理接口 token 校验失败（/ts），已拒绝");
                    sendResponse(socket, "403 Forbidden", "text/plain", "Invalid token");
                    return;
                }
                // 透传播放器的 Range 头（#EXT-X-BYTERANGE 分片场景，响应须 206 避免播放器按 200 全片做 position 对齐）
                String rangeHeader = "";
                for (String headerLine : headerLines) {
                    String hl = headerLine.toLowerCase(Locale.ROOT);
                    if (hl.startsWith("range:")) {
                        rangeHeader = headerLine.substring(headerLine.indexOf(':') + 1).trim();
                        break;
                    }
                }
                // 透传 UA / Referer（防盗链 CDN 会校验，必须与播放器直连时一致，否则分片 403）
                String uaHeader = "";
                String refererHeader = "";
                for (String headerLine : headerLines) {
                    String hl = headerLine.toLowerCase(Locale.ROOT);
                    if (uaHeader.isEmpty() && hl.startsWith("user-agent:")) {
                        uaHeader = headerLine.substring(headerLine.indexOf(':') + 1).trim();
                    } else if (refererHeader.isEmpty() && hl.startsWith("referer:")) {
                        refererHeader = headerLine.substring(headerLine.indexOf(':') + 1).trim();
                    }
                }
                String cacheKey = segUrl + "|" + rangeHeader;
                SegResult cached = getSegmentCache(cacheKey);
                if (cached != null) {
                    sendBinaryResponse(socket, cached.code == 206 ? "206 Partial Content" : "200 OK", "video/mp2t", cached.data);
                    return;
                }
                Future<SegResult> existing = pendingTsRequests.get(cacheKey);
                if (existing != null && !existing.isDone()) {
                    try {
                        SegResult dedupResult = existing.get(SEG_DEDUP_WAIT_MS, TimeUnit.MILLISECONDS);
                        if (dedupResult != null) {
                            sendBinaryResponse(socket, dedupResult.code == 206 ? "206 Partial Content" : "200 OK", "video/mp2t", dedupResult.data);
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                final String finalSegUrl = segUrl;
                final String finalRange = rangeHeader;
                final String finalUa = uaHeader;
                final String finalReferer = refererHeader;
                Callable<SegResult> segTask = new Callable<SegResult>() {
                    @Override
                    public SegResult call() {
                        return downloadSegment(finalSegUrl, finalRange, finalUa, finalReferer);
                    }
                };
                Future<SegResult> future = segExecutor.submit(segTask);
                pendingTsRequests.put(cacheKey, future);
                try {
                    SegResult result = future.get(SEG_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (result == null || result.data == null || result.data.length == 0) {
                        sendResponse(socket, "502 Bad Gateway", "text/plain", "Failed to fetch segment");
                    } else {
                        sendBinaryResponse(socket, result.code == 206 ? "206 Partial Content" : "200 OK", "video/mp2t", result.data);
                    }
                } catch (Exception e) {
                    // 【2026-10-21 线程释放】播放器 seek/切台丢弃请求时 future.get 抛异常（或超时）
                    //   → cancel 中断 downloadSegment 下载线程，立即释放 segExecutor 线程，
                    //   避免极端连按下转发线程被占满、新分片请求排队导致恢复变慢。
                    try {
                        future.cancel(true);
                    } catch (Throwable ignored) {
                    }
                    LogBridge.w("WebServer", "分片代理超时或异常: " + e.getMessage());
                    try {
                        sendResponse(socket, "504 Gateway Timeout", "text/plain", "Segment fetch timeout");
                    } catch (Exception ignored) {
                        // 对端已断开（Broken pipe）时静默，不抛给外层刷 error
                    }
                } finally {
                    pendingTsRequests.remove(cacheKey);
                }
                return;
            } else if ("GET".equals(method) && ("/".equals(purePath) || "/index.html".equals(purePath))) {
                responseBody = buildConfigPage();
            } else if ("POST".equals(method) && "/submit".equals(purePath)) {

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSubmitTime < SUBMIT_COOLDOWN) {
                    sendResponse(socket, "429 Too Many Requests", "text/plain", "操作过于频繁，请稍后再试！");
                    return;
                }
                lastSubmitTime = currentTime;

                Map<String, String> params = parseFormData(body);
                final String liveUrl = params.get("live_url");
                final String epgUrl = params.get("epg_url");
                final String customUa = params.get("custom_ua");

                handler.post(() -> {
                    boolean hasUpdate = false;
                    SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);

                    if (liveUrl != null && !liveUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_LIVE, liveUrl.trim()).apply();
                        addHistory("live_history", liveUrl.trim());
                        hasUpdate = true;
                    }
                    if (epgUrl != null && !epgUrl.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_EPG, epgUrl.trim()).apply();
                        addHistory("epg_history", epgUrl.trim());
                        hasUpdate = true;
                    }
                    if (customUa != null && !customUa.trim().isEmpty()) {
                        sp.edit().putString(KEY_CUSTOM_UA, customUa.trim()).apply();
                        hasUpdate = true;
                    }

                    if (hasUpdate) {

                        MainActivity activity = MainActivity.getRunningInstance();
                        if (activity != null && !activity.isFinishing()) {
                            activity.onReceiveConfig(liveUrl, epgUrl);
                        } else {

                            Intent refreshIntent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                            refreshIntent.setPackage(context.getPackageName());
                            context.sendBroadcast(refreshIntent);
                        }
                    }
                });

                responseBody = buildSuccessPage();
            } else {
                responseBody = "404 Not Found";
                contentType = "text/plain; charset=utf-8";
            }

            sendResponse(socket, "200 OK", contentType, responseBody);

        } catch (Exception e) {
            // 【2026-10-21 降噪】播放器 seek/切台取消请求导致对端断开（Broken pipe / Connection reset，
            //   均为 SocketException）属预期行为，降级为 warn——与 thread interrupted 同一思路，不再刷 error。
            if (e instanceof java.net.SocketException) {
                LogBridge.w("WebServer", "请求处理异常（对端断开，忽略）: " + e.getMessage());
            } else {
                e.printStackTrace();
                LogBridge.e("WebServer", "请求处理异常: " + e.getMessage());
            }
            try {
                sendResponse(socket, "500 Internal Server Error", "text/plain", "Server Error");
            } catch (Exception ignored) {
                try { socket.close(); } catch (Exception ignored2) {}
            }
        }
    }

    private void sendResponse(Socket socket, String status, String contentType, String body) throws Exception {
        byte[] bodyBytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();
        socket.close();
    }

    private void sendRedirect(Socket socket, String location) throws Exception {
        String header = "HTTP/1.1 302 Found\r\n" +
                "Location: " + location + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.flush();
        socket.close();
    }

    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new java.util.HashMap<>();
        if (body == null || body.isEmpty()) return params;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    String key = URLDecoder.decode(kv[0], "UTF-8");
                    String value = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    if (!params.containsKey(key) || params.get(key).isEmpty()) {
                        params.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return params;
    }

    private String buildConfigPage() {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String currentLive = sp.getString(KEY_CUSTOM_LIVE, "");
        String currentEpg = sp.getString(KEY_CUSTOM_EPG, "");
        String currentUa = sp.getString(KEY_CUSTOM_UA, "");
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "    <title>我的电视</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif; background: #f5f5f5; color: #333; font-size: 14px; line-height: 1.5; padding-bottom: 16px; }\n" +
                "        .section-title { padding: 16px 16px 8px; font-size: 14px; color: #999; font-weight: normal; }\n" +
                "        .card { background: #fff; margin: 0 12px; }\n" +
                "        .card:first-of-type { border-radius: 12px 12px 0 0; }\n" +
                "        .card:last-of-type { border-radius: 0 0 12px 12px; }\n" +
                "        .item { display: flex; align-items: center; padding: 14px 16px; border-bottom: 1px solid #f0f0f0; min-height: 48px; }\n" +
                "        .item:last-child { border-bottom: none; }\n" +
                "        .item-label { flex-shrink: 0; width: 70px; color: #333; font-size: 15px; }\n" +
                "        .item input[type=text] { flex: 1; text-align: right; border: none; outline: none; font-size: 14px; color: #333; background: transparent; }\n" +
                "        .item input[type=text]::placeholder { color: #ccc; }\n" +
                "        .header-item { flex-direction: column; align-items: flex-start; padding: 16px; }\n" +
                "        .header-title { font-size: 17px; color: #333; font-weight: 500; margin-bottom: 4px; }\n" +
                "        .header-desc { font-size: 13px; color: #999; }\n" +
                "        .btn-blue { display: block; margin: 12px 12px 0; padding: 8px 20px; background: #40A9FF; color: white; border: none; border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer; float: right; }\n" +
                "        .btn-blue:active { background: #1890FF; }\n" +
                "        .btn-wrap { overflow: hidden; padding: 0 0 12px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "\n" +
                "    <!-- 1. 直播源：仅远程推送 -->\n" +
                "    <div class=\"section-title\">直播源</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义直播源</div>\n" +
                "            <div class=\"header-desc\">支持 m3u、txt 格式，远程推送</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">名称</div>\n" +
                "                <input type=\"text\" name=\"live_name\" placeholder=\"添加于 " + dateStr + "\" value=\"\">\n" +
                "            </div>\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"live_url\" placeholder=\"直播源链接\" value=\"" + currentLive + "\">\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送直播源</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 2. 节目单：仅远程推送 -->\n" +
                "    <div class=\"section-title\">节目单</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义节目单</div>\n" +
                "            <div class=\"header-desc\">支持 xml、xml.gz 格式，远程推送</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">名称</div>\n" +
                "                <input type=\"text\" name=\"epg_name\" placeholder=\"添加于 " + dateStr + "\">\n" +
                "            </div>\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\">链接</div>\n" +
                "                <input type=\"text\" name=\"epg_url\" placeholder=\"节目单链接\" value=\"" + currentEpg + "\">\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送节目单</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- 3. 播放器 UA -->\n" +
                "    <div class=\"section-title\">播放器</div>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"item header-item\">\n" +
                "            <div class=\"header-title\">自定义UA</div>\n" +
                "            <div class=\"header-desc\">播放器自定义 User-Agent</div>\n" +
                "        </div>\n" +
                "        <form method=\"post\" action=\"/submit\">\n" +
                "            <div class=\"item\">\n" +
                "                <div class=\"item-label\"></div>\n" +
                "                <input type=\"text\" name=\"custom_ua\" placeholder=\"自定义 User-Agent\" value=\"" + currentUa + "\">\n" +
                "            </div>\n" +
                "            <div class=\"btn-wrap\">\n" +
                "                <button type=\"submit\" class=\"btn-blue\">推送</button>\n" +
                "            </div>\n" +
                "        </form>\n" +
                "    </div>\n" +
                "\n" +
                "</body>\n" +
                "</html>";
    }

    private String buildSuccessPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>保存成功</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif; background: #f5f5f5; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 100vh; }\n" +
                "        .container { max-width: 400px; background: white; border-radius: 12px; padding: 32px 24px; text-align: center; box-shadow: 0 2px 12px rgba(0,0,0,0.1); }\n" +
                "        .icon { font-size: 48px; margin-bottom: 16px; }\n" +
                "        h2 { font-size: 20px; color: #333; margin-bottom: 12px; }\n" +
                "        p { font-size: 14px; color: #666; margin-bottom: 24px; }\n" +
                "        a { display: inline-block; padding: 10px 24px; background: #40A9FF; color: white; text-decoration: none; border-radius: 8px; font-size: 14px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <div class=\"icon\">✅</div>\n" +
                "        <h2>配置保存成功！</h2>\n" +
                "        <p>配置已更新，电视端正在刷新...</p>\n" +
                "        <a href=\"/\">返回继续修改</a>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    private String getDeviceIPAddress() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            return "192.168.1.100";
        }
    }

    private void addHistory(String key, String url) {
        SourceManager sourceManager = new SourceManager(context, key);
        sourceManager.addSource("网页推送", url);
    }

    /**
     * 统计 m3u8 中的分片数量
     */
    private int countSegmentsInM3u8(String content) {
        if (content == null || content.isEmpty()) return 0;
        int count = 0;
        for (String line : content.split("\\r?\\n")) {
            if (line.trim().startsWith("#EXTINF:")) count++;
        }
        return count;
    }

    /**
     * 从缓存获取剥离后的m3u8内容
     * 点播（含 #EXT-X-ENDLIST）：5 分钟 TTL
     * 直播（无 ENDLIST）：1.2s 短 TTL（2026-10-17 修直播窗口过期黑屏）
     */
    private String getM3u8Cache(String url) {
        synchronized (m3u8Cache) {
            Object[] entry = m3u8Cache.get(url);
            if (entry != null) {
                long timestamp = (Long) entry[1];
                String content = (String) entry[0];
                // 【2026-10-17】直播清单只允许 1.2s 短缓存，避免滑动窗口过期后继续发旧清单
                boolean live = content != null && !content.contains(TAG_HLS_ENDLIST);
                long ttl = live ? M3U8_LIVE_CACHE_TTL_MS : M3U8_CACHE_TTL_MS;
                if (System.currentTimeMillis() - timestamp < ttl) {
                    LogBridge.i("WebServer", "[缓存命中]" + (live ? "(直播短缓存)" : "") + " "
                            + url.substring(0, Math.min(60, url.length())));
                    return content;
                } else {
                    m3u8Cache.remove(url);
                }
            }
            return null;
        }
    }

    /**
     * 存入缓存；点播清单（含 ENDLIST）会把分片 URL 重写为本地代理 /ts 地址（SEEK 快速恢复）。
     * 直播（无 ENDLIST）绝不重写，保持直连（见 M3U8_LIVE_CACHE_TTL_MS 注释）。
     *
     * @return 实际存入缓存的内容（重写后），调用方应把它返回给播放器，保证响应与缓存一致
     */
    private String putM3u8Cache(String url, String content) {
        synchronized (m3u8Cache) {
            String toCache = content;
            // 【2026-10-21 seek 快速恢复】点播清单分片 URL 重写为本地代理 /ts，
            //   后续 seek 重拉分片走本地缓存/转发，不再每次直连 CDN 等建连。
            //   重写幂等：已重写的内容（127.0.0.1 前缀）会原样跳过。
            if (toCache != null && toCache.contains(TAG_HLS_ENDLIST)) {
                toCache = rewriteSegmentUrls(toCache);
            }
            m3u8Cache.put(url, new Object[]{toCache, System.currentTimeMillis()});
            return toCache;
        }
    }

    /**
     * 【2026-10-21 seek 快速恢复】把点播 m3u8 里的分片/KEY/MAP URL 重写为本地代理 /ts 地址。
     * 仅点播清单调用（直播直连，绝不重写）。重写后播放器请求分片走本地代理：
     * 命中缓存秒回；未命中由代理转发 CDN 并回填缓存——SEEK 打断播放器侧后，
     * 代理线程继续转发完成并缓存，下次 seek 回该位置直接命中，不再等 CDN 重新建连。
     * 幂等：已以 http://127.0.0.1 开头的内容保持原样。
     */
    private String rewriteSegmentUrls(String m3u8Content) {
        if (TextUtils.isEmpty(m3u8Content)) return m3u8Content;
        String selfPrefix = "http://127.0.0.1:" + port;
        StringBuilder sb = new StringBuilder(m3u8Content.length() + 256);
        String[] lines = m3u8Content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(line).append('\n');
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("http://") || lower.startsWith("https://"))
                    && !lower.startsWith("http://127.0.0.1")) {
                try {
                    String encoded = java.net.URLEncoder.encode(trimmed, "UTF-8");
                    // 【2026-10-22 I1-12】/ts 重写同样拼 token（服务端校验，见字段声明处）
                    sb.append(selfPrefix).append("/ts?url=").append(encoded)
                            .append("&token=")
                            .append(java.net.URLEncoder.encode(proxyToken, "UTF-8"))
                            .append('\n');
                } catch (Exception e) {
                    sb.append(line).append('\n');
                }
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 分片代理结果：CDN 响应码 + 字节。透传真实码——带 Range 请求时 CDN 回 206 区间字节、
     * 忽略 Range 时回 200 全片；播放器按码决定是否做 position 对齐（200+position>0 需 skip）。
     */
    private static final class SegResult {
        final int code;
        final byte[] data;
        SegResult(int code, byte[] data) {
            this.code = code;
            this.data = data;
        }
    }

    /**
     * 读取分片缓存（key=分片URL+"|"+Range头）；TTL 过期则淘汰。
     */
    private SegResult getSegmentCache(String key) {
        synchronized (segCache) {
            Object[] entry = segCache.get(key);
            if (entry != null) {
                long ts = (Long) entry[1];
                if (System.currentTimeMillis() - ts < SEG_CACHE_TTL_MS) {
                    return new SegResult((Integer) entry[3], (byte[]) entry[0]);
                } else {
                    segCacheBytes -= (Long) entry[2];
                    segCache.remove(key);
                }
            }
            return null;
        }
    }

    /**
     * 写入分片缓存；超过字节上限按 LRU 顺序淘汰最老条目（条目数上限由 removeEldestEntry 兜底）。
     */
    private void putSegmentCache(String key, int code, byte[] data) {
        synchronized (segCache) {
            Object[] old = segCache.get(key);
            if (old != null) {
                segCacheBytes -= (Long) old[2];
            }
            segCache.put(key, new Object[]{data, System.currentTimeMillis(), (long) data.length, code});
            segCacheBytes += data.length;
            while (segCacheBytes > SEG_CACHE_MAX_BYTES && !segCache.isEmpty()) {
                java.util.Iterator<java.util.Map.Entry<String, Object[]>> it = segCache.entrySet().iterator();
                java.util.Map.Entry<String, Object[]> eldest = it.next();
                segCacheBytes -= (Long) eldest.getValue()[2];
                it.remove();
            }
        }
    }

    /**
     * 【2026-10-21 seek 快速恢复】转发下载 CDN 分片（带 Range 透传），成功后回填缓存。
     * 与 downloadM3u8 保持一致：不主动 disconnect，输入流关闭后连接自动回池复用。
     */
    private SegResult downloadSegment(String segUrl, String rangeHeader, String uaHeader, String refererHeader) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(segUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            // 优先透传播放器请求头（防盗链校验需要与直连时一致），空则用默认 UA 兜底
            conn.setRequestProperty("User-Agent", uaHeader != null && !uaHeader.isEmpty() ? uaHeader
                    : "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "*/*");
            if (refererHeader != null && !refererHeader.isEmpty()) {
                conn.setRequestProperty("Referer", refererHeader);
            }
            if (rangeHeader != null && !rangeHeader.isEmpty()) {
                conn.setRequestProperty("Range", rangeHeader);
            }
            int responseCode = conn.getResponseCode();
            if (responseCode != 200 && responseCode != 206) {
                LogBridge.w("WebServer", "分片下载失败: HTTP " + responseCode
                        + " url=" + segUrl.substring(0, Math.min(80, segUrl.length())));
                return null;
            }
            java.io.InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, conn.getContentLength()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                bos.write(buf, 0, n);
            }
            in.close();
            byte[] data = bos.toByteArray();
            if (data.length == 0) return null;
            // 回填缓存（后续 seek/重复请求命中秒回）；保留真实响应码供播放器对齐 position
            String cacheKey = segUrl + "|" + (rangeHeader != null ? rangeHeader : "");
            putSegmentCache(cacheKey, responseCode, data);
            return new SegResult(responseCode, data);
        } catch (Exception e) {
            // 【2026-10-21 坏连接清理】异常路径必须断开：损坏/半途的连接若留回连接池，
            //   下一次复用会直接抛 "Connection reset"（极端连按下已实测）。
            try {
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {
            }
            LogBridge.e("WebServer", "分片下载异常: " + e.getMessage());
            return null;
        } finally {
            // 与 downloadM3u8 一致：成功路径不调用 disconnect，保持连接复用；输入流已关闭，连接自动返回到连接池
        }
    }

    /**
     * 二进制响应（分片代理用，支持自定义状态码：200 / 206）
     */
    private void sendBinaryResponse(Socket socket, String status, String contentType, byte[] body) throws Exception {
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.write(body);
        out.flush();
        socket.close();
    }

    /**
     * 预加载：异步下载并剥离m3u8，存入缓存（用于下一集秒开）
     */
    /**
     * 取消正在运行的预加载（切换源时调用，避免并发网络请求）
     */
    public void cancelPreload() {
        if (preloadFuture != null && !preloadFuture.isDone()) {
            preloadFuture.cancel(true);
            LogBridge.i("WebServer", "[预加载] 已取消（切换源）");
        }
        preloadRunning.set(false);
    }

    /**
     * 预加载：异步下载并剥离m3u8，存入缓存（用于下一集秒开）
     * 串行化：同一时间只允许一个预加载任务，避免与播放请求并发
     */
    /**
     * 预加载：异步下载并剥离m3u8，存入缓存（用于下一集秒开）
     * 串行化：同一时间只允许一个预加载任务，避免与播放请求并发
     */
    public void preload(final String url) {
        if (stripExecutor == null || TextUtils.isEmpty(url)) return;
        if (getM3u8Cache(url) != null) return; // 已有缓存
        if (!preloadRunning.compareAndSet(false, true)) {
            LogBridge.d("WebServer", "[预加载] 已有任务在运行，跳过");
            return;
        }
        preloadFuture = stripExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    LogBridge.i("WebServer", "[预加载] 开始: " + url.substring(0, Math.min(60, url.length())));
                    String m3u8Content = downloadM3u8(url);
                    if (TextUtils.isEmpty(m3u8Content)) return;
                    String finalContent = m3u8Content;
                    String finalBaseUrl = url;
                    if (M3u8AdStripper.isMasterPlaylist(m3u8Content)) {
                        String variantUrl = M3u8AdStripper.getFirstVariantUrl(m3u8Content, url);
                        if (variantUrl != null && !variantUrl.isEmpty()) {
                            String variantContent = downloadM3u8(variantUrl);
                            if (!TextUtils.isEmpty(variantContent)) {
                                finalContent = variantContent;
                                finalBaseUrl = variantUrl;
                            }
                        }
                    }
                    String stripped;
                    try {
                        stripped = M3u8AdStripper.stripAds(finalContent, finalBaseUrl);
                    } catch (Exception stripEx) {
                        LogBridge.e("WebServer", "广告剥离异常，返回原文(已绝对化): " + stripEx.getMessage());
                        try {
                            stripped = M3u8AdStripper.resolveAllUrls(finalContent, finalBaseUrl);
                        } catch (Exception rex) {
                            stripped = finalContent;
                        }
                    }
                    putM3u8Cache(url, stripped);
                    LogBridge.i("WebServer", "[预加载] 完成，存入缓存");                } catch (Exception e) {
                    LogBridge.e("WebServer", "[预加载] 失败: " + e.getMessage());
                } finally {
                    preloadRunning.set(false);
                    preloadFuture = null;
                }
            }
        });
    }

    /**
     * 【2026-10-16】播放前预剥离：提前下载+剥离m3u8，播放器请求时缓存已命中
     * 与代理请求共享 pendingStripRequests，避免重复下载
     */
    public Future<String> preStrip(String url) {
        if (TextUtils.isEmpty(url)) return null;
        if (getM3u8Cache(url) != null) return null;
        Future<String> existing = pendingStripRequests.get(url);
        if (existing != null && !existing.isDone()) return existing;

        final String finalUrl = url;
        Callable<String> task = new Callable<String>() {
            @Override
            public String call() throws Exception {
                try {
                    LogBridge.i("WebServer", "[预剥离] 开始: " + finalUrl.substring(0, Math.min(60, finalUrl.length())));
                    String m3u8Content = downloadM3u8(finalUrl);
                    if (m3u8Content.isEmpty()) return null;
                    String finalContent = m3u8Content;
                    String finalBaseUrl = finalUrl;
                    if (M3u8AdStripper.isMasterPlaylist(m3u8Content)) {
                        String variantUrl = M3u8AdStripper.getFirstVariantUrl(m3u8Content, finalUrl);
                        if (variantUrl != null && !variantUrl.isEmpty()) {
                            String variantContent = downloadM3u8(variantUrl);
                            if (!variantContent.isEmpty()) {
                                finalContent = variantContent;
                                finalBaseUrl = variantUrl;
                            }
                        }
                    }
                    String stripped;
                    try {
                        stripped = M3u8AdStripper.stripAds(finalContent, finalBaseUrl);
                    } catch (Exception stripEx) {
                        LogBridge.e("WebServer", "预剥离异常，返回原文: " + stripEx.getMessage());
                        // 【2026-10-17 源7 播放失败修复·兜底】即使剥离抛异常，也必须把相对分片
                        //   地址转成绝对地址：否则播放器以「本地代理 URL」为 base 解析相对路径，
                        //   请求打到 127.0.0.1:1048x → 返回 HTML → "Cannot find sync byte"（3001）。
                        try {
                            stripped = M3u8AdStripper.resolveAllUrls(finalContent, finalBaseUrl);
                        } catch (Exception resolveEx) {
                            LogBridge.e("WebServer", "回退绝对化也失败: " + resolveEx.getMessage());
                            stripped = finalContent;
                        }
                    }
                    String cachedContent = putM3u8Cache(finalUrl, stripped);
                    LogBridge.i("WebServer", "[预剥离] 完成，存入缓存");
                    return cachedContent;
                } finally {
                    pendingStripRequests.remove(finalUrl);
                }
            }
        };

        Future<String> future = stripExecutor.submit(task);
        pendingStripRequests.put(url, future);
        return future;
    }

    /**
     * 带超时的广告剥离：超过500ms返回原文，后台继续剥离存缓存
     */
    private String stripWithTimeout(final String content, final String baseUrl, final String cacheKey) {
        if (stripExecutor == null) {
            return M3u8AdStripper.stripAds(content, baseUrl);
        }
        Future<String> future = stripExecutor.submit(new Callable<String>() {
            @Override
            public String call() {
                return M3u8AdStripper.stripAds(content, baseUrl);
            }
        });
        try {
            String result = future.get(STRIP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return putM3u8Cache(cacheKey, result);
        } catch (Exception e) {
            LogBridge.w("WebServer", "[超时降级] 剥离超过" + STRIP_TIMEOUT_MS + "ms，返回原文，后台继续剥离");
            // 后台继续剥离并存缓存
            stripExecutor.execute(() -> {
                try {
                    String stripped = M3u8AdStripper.stripAds(content, baseUrl);
                    putM3u8Cache(cacheKey, stripped);
                    LogBridge.i("WebServer", "[超时降级] 后台剥离完成，已存入缓存");
                } catch (Exception ignored) {}
            });
            // 【2026-10-17】返回原文前也要把相对分片转绝对，否则播放器会拿本地代理地址当 base
            try {
                return M3u8AdStripper.resolveAllUrls(content, baseUrl);
            } catch (Exception ignored) {
                return content;
            }
        }
    }
    /**
     * 下载原始 m3u8 内容
     */
    private String downloadM3u8(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "keep-alive");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                LogBridge.w("WebServer", "m3u8下载失败: HTTP " + responseCode);
                return "";
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            LogBridge.e("WebServer", "m3u8下载异常: " + e.getMessage());
            return "";
        } finally {
            // 不调用disconnect，保持连接复用；输入流已关闭，连接会自动返回到连接池
        }
    }

    /**
     * 获取广告剥离代理地址
     */
    public String getAdStripProxyUrl(String originalUrl) {
        return getAdStripProxyUrl(originalUrl, "");
    }

    public String getAdStripProxyUrl(String originalUrl, String channelName) {
        try {
            String encoded = java.net.URLEncoder.encode(originalUrl, "UTF-8");
            String url = "http://127.0.0.1:" + port + "/m3u8?url=" + encoded;
            if (channelName != null && !channelName.isEmpty()) {
                url += "&ch=" + java.net.URLEncoder.encode(channelName, "UTF-8");
            }
            // 【2026-10-22 I1-12】播放器侧自动拼 token（服务端 /m3u8 校验，见字段声明处）
            url += "&token=" + java.net.URLEncoder.encode(proxyToken, "UTF-8");
            return url;
        } catch (Exception e) {
            return originalUrl;
        }
    }

    public static WebServerManager getRunningInstance() {
        return runningInstance;
    }
}
