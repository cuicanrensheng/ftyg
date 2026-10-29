package com.tv.live;
import android.util.Log;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
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
    /**
     * 【2026-10-23 已废弃】原「剥离超过 500ms 就返回原文」的超时阈值。
     *
     * <p>⚠️ 该降级是**广告漏出到播放器的直接原因**（2026-10-23 电视实测）：
     * <pre>
     *   09:19:51  【算法选择①】主算法成功！剔除广告分片 10 个   ← 剥掉了
     *   09:19:51  [超时降级] 剥离超过500ms，返回原文            ← 但已超时
     *   09:19:51  剥离后: 分片数=367, 剔除=0 个← 播放器拿到的是原文
     * </pre>
     * 更糟的是它把**原文也写进了 m3u8 缓存**，等于把带广告的清单固化了。
     * 现改为：剥离未完成就**不返回**（见 stripBlocking），由外层 5s 统一兜底为 502，
     * 播放器退避重试时后台任务已存入剥离版缓存 → 下次命中缓存拿到干净清单。
     */
    @SuppressWarnings("unused")
    private static final long STRIP_TIMEOUT_MS_DEPRECATED = 500L;

    // ========== 分片代理缓存（2026-10-21 seek 快速恢复：SEEK 中断后代理继续转发并缓存，下次命中秒回） ==========
    /** 内存上限 32MB（电视盒子内存有限，按字节 LRU 淘汰，防止缓存膨胀） */
    private static final int SEG_CACHE_MAX_BYTES = 32 * 1024 * 1024;
    /** 点播分片是静态文件，10 分钟 TTL 足够覆盖 seek 回放窗口 */
    private static final long SEG_CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 分片条目数上限（防御性保护，配合字节上限双保险） */
    private static final int SEG_CACHE_MAX_ENTRIES = 128;
    /**
     * 【2026-10-23 源9 慢速 CDN 专项】单个分片代理任务（建连 + 等上游响应头 + 流式回传）的总时长上限。
     *
     * <p>原值 8s 的语义是「等整片下完」——源9《仙逆》实测 www.shenhua7.vip（Telegram 托管 CDN）
     * 单连接仅 35~60KB/s，一个 1.5MB 分片需 25~43s，8s 必杀 → 「第一片能播、第二片超时」
     * → 20s 卡顿检测重启 doPlay → 再杀，24s 一次重试风暴（18 次/4 分钟）。
     *
     * <p>流式转发改造后（见 {@link #streamSegmentToSocket}）本常量的含义变为任务总时长硬顶：
     * 播放器只要还在读，字节就在流动；真正挂死由上游 30s 读不活动超时兜底。
     * 60s 覆盖实测最坏组合（最慢 35KB/s × 最大 1.6MB ≈ 46s）。
     */
    private static final long SEG_FETCH_TIMEOUT_MS = 60000L;
    /** 并发去重等待上限：等首个同 key 请求把分片完整拉完并回填缓存（同步放宽到 60s） */
    private static final long SEG_DEDUP_WAIT_MS = 60000L;
    /**
     * 【2026-10-23】上游 CDN 单次读「不活动」超时（原 downloadSegmentOnce 的 read 10s）。
     * 流式转发下这就是「CDN stall 判定」：30s 收不到任何字节才认挂死，
     * 慢速但活着（每 ~0.25s 来一个 8KB 块）的连接不会被误杀。
     */
    private static final int SEG_READ_TIMEOUT_MS = 30000;

    /**
     * 【2026-10-23】最近一次「网络字节到达」的时间戳（0=从未）。
     * 卡顿检测看门狗（{@code TVPlayerManager.STUCK_TIMEOUT=20s}）通过
     * {@link #isNetProgressing()} 把「字节正在流动」视为有进展，避免看门狗杀死
     * 慢速长分片下载（源9 实测单连接 35~60KB/s，一个 1.5MB 分片需 25~45s，
     * 播放器这段时间一直 STATE_BUFFERING）。
     * 写入方：代理 /ts 流式回传（本类）+ 播放器数据源直连读取
     * （{@code RedirectLoggingHttpDataSource.read}）——源9 点播直连也受益。
     */
    private static final java.util.concurrent.atomic.AtomicLong LAST_NET_BYTE_TS =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 「有进展」判定窗口：10s 内有字节即算活着（看门狗 2s 一轮，窗口留足余量） */
    private static final long PROXY_PROGRESS_WINDOW_MS = 10000L;

    /** 网络是否正在交付字节（慢速但活着）。供卡住检测看门狗豁免用。 */
    public static boolean isNetProgressing() {
        return System.currentTimeMillis() - LAST_NET_BYTE_TS.get() < PROXY_PROGRESS_WINDOW_MS;
    }

    /** 记录一次网络字节交付（代理回传 / 播放器直连读取均可调用） */
    public static void markNetProgress() {
        LAST_NET_BYTE_TS.set(System.currentTimeMillis());
    }

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
                LogBridge.d("WebServer", "已有代理实例启动中/运行中，跳过重复启动");
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
        String ip = getDeviceIPAddress();
        if (ip == null) return null; // 无网时不给出假地址（旧行为会拼出 http://0.0.0.0:10481）
        return "http://" + ip + ":" + port;
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
                long stripStartTime = System.currentTimeMillis();
                // 【2026-10-28】内部类里要用频道名，先固化成 final
                final String fChannelName = channelName;
                String cached = getM3u8Cache(targetUrl);
                if (cached != null) {
                    sendResponse(socket, "200 OK", "application/vnd.apple.mpegurl; charset=utf-8", cached);
                    return;
                }

                // 【2026-10-16】请求去重：检查是否有相同URL正在处理
                Future<String> existingFuture = pendingStripRequests.get(targetUrl);
                if (existingFuture != null && !existingFuture.isDone()) {
                    LogBridge.d("WebServer", "请求去重：等待相同URL的处理结果");
                    try {
                        String dedupResult = existingFuture.get(3, TimeUnit.SECONDS);
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
                            // 【2026-10-23 禁止降级返回原文】原先这里是 stripWithTimeout：
                            //   剥离超过 500ms 就降级返回「未剥离原文」+ 后台继续剥离存缓存。
                            //   实测证明这条降级正是**广告漏出到播放器的直接原因**：主算法明明
                            //   成功剔除了 10 个分片，却因为耗时 498~3959ms 超过 500ms 而把原文
                            //   发了出去，而且原文还被写进 m3u8 缓存固化（后续请求全命中带广告清单）。
                            //   现改为 stripBlocking：**阻塞等剥离完成**，未完成就不返回；
                            //   剥离抛异常同样**不返回原文**（上抛 → 外层统一回 502）。
                            //   外层已有 5s 上限 → 502 后播放器 3s 退避重试，届时后台任务多已把
                            //   剥离版写进缓存，下次直接命中（实测缓存命中仅 9ms）。
                            String stripped = stripBlocking(finalContent, finalBaseUrl, finalTargetUrl);
                            int afterSegments = countSegmentsInM3u8(stripped);
                            // 【2026-10-28 只留一条结果】正常流程只记这一行（频道 + 剥离前后 + 剔除数）
                            LogBridge.d("WebServer", "剥离" + (fChannelName.isEmpty() ? "" : "[" + fChannelName + "]")
                                    + ": " + beforeSegments + " → " + afterSegments
                                    + " 片（剔除 " + (beforeSegments - afterSegments) + "）"
                                    + " 算法=" + M3u8AdStripper.getLastAlgoName()
                                    + " 耗时=" + M3u8AdStripper.getLastCostMs() + "ms");
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
                // 【2026-10-23 源9 慢速 CDN 专项】改为流式转发：拿到上游响应头立即回播放器，
                //   边读边写并 tee 回填 segCache。命中缓存仍秒回；未命中全权交给 segExecutor 任务
                //   （任务内部自行回 502/断连），连接线程提交后立即释放——requestExecutor 仅
                //   4 线程，不能被 25~45s 的慢速分片占住。
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
                final String finalCacheKey = cacheKey;
                final Socket finalSocket = socket;
                Callable<SegResult> segTask = new Callable<SegResult>() {
                    @Override
                    public SegResult call() {
                        return streamSegmentToSocket(finalSocket, finalSegUrl, finalRange, finalUa, finalReferer, finalCacheKey);
                    }
                };
                final Future<SegResult> future;
                try {
                    future = segExecutor.submit(segTask);
                } catch (Exception submitEx) {
                    // 代理正在停止（shutdownNow 拒收）等场景：不能放着 socket 无人应答
                    LogBridge.e("WebServer", "分片代理任务提交失败: " + submitEx.getMessage());
                    try {
                        sendResponse(socket, "503 Service Unavailable", "text/plain", "Proxy shutting down");
                    } catch (Exception ignored) {
                    }
                    return;
                }
                pendingTsRequests.put(cacheKey, future);
                try {
                    // 任务内部已把响应（含 502 失败路径）直接流式写给播放器并关闭 socket，
                    // 这里只做「总时长硬顶」：超时即关闭 socket，任务下一次写 socket 时
                    // 自然 Broken pipe 退出（见下方修复4，不再 cancel(true) 一刀切）。
                    future.get(SEG_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // 【2026-10-29 修复】future.get 超时（SEG_FETCH_TIMEOUT_MS=60s 总时长硬顶）仅表示
                    //   "调用方不再阻塞等待"，不再强制关闭与播放器的 socket —— 否则正在途的分片被中途
                    //   掐断，播放器读到 unexpected end of stream（premature EOF，见 13:51:16 日志）。
                    //   任务内的流式回传会继续把字节写给播放器，直到上游 EOF（正常收尾 1255 行 close）
                    //   或播放器放弃（写 socket 抛 Broken pipe 自然退出）；上游真卡死由
                    //   SEG_READ_TIMEOUT_MS=30s 读不活动超时兜底。遵循「任其自然结束」的本意。
                    LogBridge.w("WebServer", "分片代理总时长达上限，交由在途回传自然结束（不强制关闭播放器连接）: " + e.getMessage());
                } finally {
                    // 仅移除自己的映射（并发同 key 请求可能已换成新的 future）
                    pendingTsRequests.remove(cacheKey, future);
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

    /**
     * 【2026-10-26 修二维码 IP 显示 0.0.0.0】取本机「同网段手机能访问到」的 IPv4。
     *
     * <p>旧实现只问 WifiManager：电视走有线（eth0）或 Wi-Fi 未连接时
     * {@code WifiInfo.getIpAddress()} 返回 0，原样拼出来就是 {@code http://0.0.0.0:10481}，
     * 手机扫码/手输都进不去。实机复现：创维 8N05_Q840，Wi-Fi DISCONNECTED、eth0 192.168.5.60。
     *
     * <p>兜底顺序：Wi-Fi 已连接地址 → ConnectivityManager 各已连网络 LinkProperties
     * → 网卡枚举（wlan0、eth0 等）。取不到返回 null，由调用方提示，绝不返回 0.0.0.0。
     */
    private String getDeviceIPAddress() {
        String ip = pickUsableIpv4(readWifiIpv4());
        if (ip == null) ip = pickUsableIpv4(readActiveNetworkIpv4());
        if (ip == null) ip = pickUsableIpv4(scanNetworkInterfacesIpv4());
        if (ip == null) {
            LogBridge.w("WebServer", "【订阅地址】未取到局域网 IPv4（网线/Wi-Fi 均未连接？），二维码不给地址");
        } else {
            LogBridge.i("WebServer", "【订阅地址】本机局域网 IP = " + ip);
        }
        return ip;
    }

    /** Wi-Fi 已连接时的地址；未连接/未拿到 DHCP 返回 null（这正是原 0.0.0.0 的来源） */
    private String readWifiIpv4() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            int ip = info.getIpAddress();
            if (ip == 0) return null;
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 遍历 ConnectivityManager 已连接网络（有线/Wi-Fi/热点），优先 Wi-Fi，其次以太网 */
    private String readActiveNetworkIpv4() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            String wifi = null, ethernet = null, other = null;
            for (Network network : cm.getAllNetworks()) {
                NetworkInfo ni = cm.getNetworkInfo(network);
                if (ni == null || !ni.isConnected()) continue;
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    java.net.InetAddress a = la.getAddress();
                    if (a == null || !(a instanceof java.net.Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String cand = a.getHostAddress();
                    if (pickUsableIpv4(cand) == null) continue;
                    if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                        if (wifi == null) wifi = cand;
                    } else if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                        if (ethernet == null) ethernet = cand;
                    } else if (other == null) {
                        other = cand;
                    }
                }
            }
            return wifi != null ? wifi : (ethernet != null ? ethernet : other);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 最后兜底：直接枚举网卡（部分电视的 ConnectivityManager 不给有线网络信息） */
    private String scanNetworkInterfacesIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            String wifi = null, ethernet = null, other = null;
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface nif = ifs.nextElement();
                try {
                    if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                    String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.ROOT);
                    if (name.startsWith("dummy") || name.startsWith("rmnet") || name.startsWith("p2p")) continue;
                    java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress a = addrs.nextElement();
                        if (!(a instanceof java.net.Inet4Address)) continue;
                        if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                        String cand = a.getHostAddress();
                        if (pickUsableIpv4(cand) == null) continue;
                        if (name.startsWith("wlan")) {
                            if (wifi == null) wifi = cand;
                        } else if (name.startsWith("eth")) {
                            if (ethernet == null) ethernet = cand;
                        } else if (other == null) {
                            other = cand;
                        }
                    }
                } catch (Exception ignore) {
                }
            }
            return wifi != null ? wifi : (ethernet != null ? ethernet : other);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 合法性过滤：必须是 4 段数字，且排除 0.0.0.0 / 127.x / 169.254.x（链路本地） */
    private static String pickUsableIpv4(String raw) {
        if (raw == null) return null;
        String ip = raw.trim();
        if (ip.isEmpty()) return null;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return null;
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return null;
            } catch (Exception e) {
                return null;
            }
        }
        if ("0.0.0.0".equals(ip) || ip.startsWith("127.") || ip.startsWith("169.254.")) return null;
        return ip;
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
                    LogBridge.d("WebServer", "[缓存命中]" + (live ? "(直播短缓存)" : "") + " "
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
     * 【2026-10-23 源9 慢速 CDN 专项】流式转发单个分片给播放器（取代原 downloadSegment/
     * downloadSegmentOnce 的「整片缓冲再回」实现）。
     *
     * <p>与原实现的本质区别：拿到上游响应头（200/206 + Content-Length/Content-Range）
     * <b>立即</b>写给播放器，随后边读边写，同时 tee 进字节数组供 {@link #putSegmentCache}
     * 回填。慢速 CDN（源9 实测单连接 35~60KB/s）下播放器可即时起播、边下边播，
     * 不再有「8s 等整片」硬顶 + 「超时即一刀切」的组合杀。
     *
     * <p>失败语义：
     * <ul>
     *   <li>上游非 200/206 → 回 502（响应头未发出，可安全回错误响应）；</li>
     *   <li>上游读不活动超过 {@link #SEG_READ_TIMEOUT_MS} → 认挂死，静默断连；</li>
     *   <li>写播放器 socket 失败（播放器已放弃/断连：Broken pipe / Socket closed）→
     *       静默断连。响应头已发出时不能再回错误响应，只能断连让播放器自重试；</li>
     *   <li>完整读完 → tee 数组回填缓存，供 seek 回放/重复请求命中秒回。</li>
     * </ul>
     *
     * @param cacheKey 分片缓存 key（分片URL+"|"+Range头）
     * @return 完整转发成功返回 {@link SegResult}（供去重等待方使用）；任何失败返回 null
     */
    private SegResult streamSegmentToSocket(Socket socket, String segUrl, String rangeHeader,
                                            String uaHeader, String refererHeader, String cacheKey) {
        HttpURLConnection conn = null;
        boolean headersSent = false;
        try {
            // 【2026-10-23 手动处理 302 重定向】跨协议重定向 setInstanceFollowRedirects 在
            //   Android 上不可靠（与原 downloadSegment 同口径），最多 3 跳
            String curUrl = segUrl;
            int responseCode = -1;
            for (int redirect = 0; redirect < 3; redirect++) {
                // 【2026-10-25 NO_PROXY】本地剥离代理拉 CDN 分片这一段也必须直连：
                //   服务已在 127.0.0.1 上，再套一层系统代理既无意义又会把 CDN 请求转给代理。
                conn = (HttpURLConnection) new java.net.URL(curUrl)
                        .openConnection(java.net.Proxy.NO_PROXY);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(5000);
                // 单次读「不活动」超时：慢速但活着（持续来字节）的连接不会被误杀
                conn.setReadTimeout(SEG_READ_TIMEOUT_MS);
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
                responseCode = conn.getResponseCode();
                if (responseCode == 301 || responseCode == 302 || responseCode == 303
                        || responseCode == 307 || responseCode == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location != null && !location.isEmpty() && redirect < 2) {

                        try { conn.disconnect(); } catch (Throwable ignored) {}
                        conn = null;
                        curUrl = location;
                        continue;
                    }
                }
                break;
            }
            if (conn == null || (responseCode != 200 && responseCode != 206)) {
                LogBridge.w("WebServer", "分片代理上游失败: HTTP " + responseCode
                        + " url=" + segUrl.substring(0, Math.min(80, segUrl.length())));
                sendResponse(socket, "502 Bad Gateway", "text/plain", "Failed to fetch segment");
                return null;
            }
            // 透传上游响应头：Content-Length/Range 决定播放器是否做 position 对齐
            //   （200+position>0 需 skip；上游未给 Content-Length 时按连接关闭 delimitation，
            //   播放器读到 EOF 为止——与 sendBinaryResponse 的历史行为一致）
            String contentRange = conn.getHeaderField("Content-Range");
            int contentLength = conn.getContentLength();
            StringBuilder header = new StringBuilder(160);
            header.append("HTTP/1.1 ").append(responseCode == 206 ? "206 Partial Content" : "200 OK").append("\r\n");
            header.append("Content-Type: video/mp2t\r\n");
            if (contentLength >= 0) {
                header.append("Content-Length: ").append(contentLength).append("\r\n");
            }
            if (contentRange != null && !contentRange.isEmpty()) {
                header.append("Content-Range: ").append(contentRange).append("\r\n");
            }
            header.append("Accept-Ranges: bytes\r\n");
            header.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(header.toString().getBytes("UTF-8"));
            out.flush();
            headersSent = true;
            // 边读边写 + tee 回填缓存
            java.io.InputStream in = conn.getInputStream();
            ByteArrayOutputStream tee = new ByteArrayOutputStream(Math.max(1024, contentLength > 0 ? contentLength : 1024));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                out.write(buf, 0, n);
                tee.write(buf, 0, n);
                // 供卡住检测看门狗判定「网络正在交付字节」（慢速但活着）
                markNetProgress();
            }
            out.flush();
            byte[] data = tee.toByteArray();
            if (data.length == 0) {
                LogBridge.i("WebServer", "分片代理上游返回空 url=" + segUrl.substring(0, Math.min(80, segUrl.length())));
                try { socket.close(); } catch (Exception ignored) {}
                return null;
            }
            // 回填缓存，保留真实响应码供播放器对齐 position
            putSegmentCache(cacheKey, responseCode, data);
            socket.close();
            return new SegResult(responseCode, data);
        } catch (Exception e) {
            // 响应头未发出：还能回一个明确的 502；已发出（流式回传中断）：只能断连，
            //   让播放器按 premature EOF 自重试（此时回错误响应头会污染字节流）。
            if (headersSent) {
                // 【2026-10-28 降噪】ConnectionReset / Broken pipe 是播放器主动放弃分片下载
                //   （切台、seek、预取淘汰）的正常产物，每次起播必现，不算故障 → 不记；
                //   其余异常仍按 warn 记录。
                String cn = e.getClass().getSimpleName();
                if (!"ConnectionResetException".equals(cn) && !"SocketException".equals(cn)) {
                    LogBridge.w("WebServer", "分片流式回传中断（上游中断）: "
                            + cn + " " + e.getMessage());
                }
                try { socket.close(); } catch (Exception ignored) {}
            } else {
                LogBridge.e("WebServer", "分片代理打开上游失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                try {
                    sendResponse(socket, "502 Bad Gateway", "text/plain", "Failed to fetch segment");
                } catch (Exception ignored) {
                    try { socket.close(); } catch (Exception ignored2) {}
                }
            }
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
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
                    LogBridge.d("WebServer", "[预加载] 开始: " + url.substring(0, Math.min(60, url.length())));
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
                    LogBridge.d("WebServer", "[预加载] 完成，存入缓存");                } catch (Exception e) {
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
                    LogBridge.d("WebServer", "[预剥离] 开始: " + finalUrl.substring(0, Math.min(60, finalUrl.length())));
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
                    LogBridge.d("WebServer", "[预剥离] 完成，存入缓存");
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
     * 【2026-10-23 新增】广告剥离：**只接受剥离完成的结果，绝不返回未剥离的原文**。
     *
     * <p>取代原 {@code stripWithTimeout(…, 500ms)}。原实现在超过 500ms 时会把
     * <b>未剥离的原文</b>返回给播放器并写进 m3u8 缓存（双重伤害：既漏广告、又固化清单），
     * 实测主算法耗时 448~3959ms，6 次里 3 次踩线 → 广告照播。
     *
     * <p>现在的口径：
     * <ul>
     *   <li>剥离未完成 → 本方法<b>阻塞等待</b>（不设内部超时）；超时由外层
     *       {@code future.get(5, SECONDS)} 统一兜底为 502，绝不把原文发出去；</li>
     *   <li>剥离抛异常 → 直接抛出，交给外层回 502。<b>不做「绝对化原文」兜底</b>，
     *       因为那仍是带广告的清单，与本方法目的相悖；</li>
     *   <li>502 之后播放器 3s 退避重试，此时后台任务通常已把<b>剥离版</b>写入缓存，
     *       下次请求直接命中（实测缓存命中 9ms）→ 最终仍拿到干净清单。</li>
     * </ul>
     */
    private String stripBlocking(final String content, final String baseUrl, final String cacheKey) {
        // 【2026-10-23 修复死锁】原实现把 stripAds 提交到 stripExecutor（仅2线程）后
        //   无超时阻塞等待。当 2 个 stripTask 同时运行时，线程池被占满，内层 task 永远
        //   得不到执行 → 死锁 → 外层 5s 超时 → 502 → 播放器重试 → 又死锁 → 卡死。
        //   修复：直接在当前线程调用 stripAds（纯 CPU 正则操作，无需异步）。
        try {
            String result = M3u8AdStripper.stripAds(content, baseUrl);
            return putM3u8Cache(cacheKey, result);
        } catch (Exception e) {
            // ⚠️ 刻意**不**回退到 resolveAllUrls(原文)：那仍是带广告的清单。
            //    直接上抛 → 外层 502 → 播放器退避重试 → 命中缓存后的剥离版。
            LogBridge.e("WebServer", "[禁止降级] 广告剥离失败，不返回原文，向上抛出让本请求回 502: "
                    + e.getMessage());
            throw new IllegalStateException("广告剥离失败，拒绝返回未剥离原文", e);
        }
    }
    /**
     * 下载原始 m3u8 内容。
     * 【2026-10-23 复用 M3u8AdStripper.downloadContent】
     *   测速与代理共用同一套 HTTP 下载逻辑，避免重复代码。
     */
    /**
     * 下载原始 m3u8 内容。
     *
     * <p>【2026-10-23 修复5】源9 在播时透传 {@code videoUsableUrl} 下发的防盗链播放头
     * （{@link com.tv.live.juzhi.JzUrlResolver#playHeaders()}；非源9 播放已由
     * TVPlayerManager 清空 → 保持历史默认：Mozilla UA + shenhua7 Referer，既有源行为不变）。
     * 只取 User-Agent/Referer 两个键，避免把 Cookie 等带到无关 CDN。
     */
    private String downloadM3u8(String urlStr) {
        String ua = null;
        String referer = null;
        java.util.Map<String, String> ph = com.tv.live.juzhi.JzUrlResolver.playHeaders();
        if (ph != null && !ph.isEmpty()) {
            for (java.util.Map.Entry<String, String> e : ph.entrySet()) {
                if (e.getKey() == null) continue;
                if ("user-agent".equalsIgnoreCase(e.getKey())) {
                    ua = e.getValue();
                } else if ("referer".equalsIgnoreCase(e.getKey())) {
                    referer = e.getValue();
                }
            }
            LogBridge.i("WebServer", "m3u8 透传源9播放头 ua=" + (ua != null) + " referer=" + (referer != null));
        }
        String content = M3u8AdStripper.downloadContent(urlStr, -1,
                (referer == null || referer.isEmpty()) ? "https://www.shenhua7.vip/" : referer,
                ua);
        if (content == null) {
            LogBridge.w("WebServer", "m3u8下载失败: " + urlStr.substring(0, Math.min(80, urlStr.length())));
            return "";
        }
        // 十进制编码解码
        content = M3u8AdStripper.decodeDecimalM3u8(content);
        return content;
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
