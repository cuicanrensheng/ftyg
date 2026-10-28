package com.tv.live;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;
import android.webkit.CookieManager;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.tv.live.exception.RedirectFailedException;
import com.tv.live.util.LogBridge.LogCollector;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import com.tv.live.BuildConfig;

@SuppressLint("UnsafeOptInUsageError")
public class RedirectLoggingHttpDataSource extends BaseDataSource implements HttpDataSource {
    private static final String TAG = "RedirectHttp";
    private static final Map<String, Long> LAST_FAIL_LOG = new ConcurrentHashMap<>();
    private boolean isTsRequest = false; // ts分片请求只打印错误，避免刷屏

    /**
     * 【2026-09-19 404 专项】最近一次 404 的时间戳（epoch ms）。
     * 数据源层在抛出 HttpDataSourceException(HTTP 404) 前打标；
     * TVPlayerManager#onPlayerError 里读取，判断虎牙 m3u8 地址时效失效。
     * 用 volatile + 时间戳而非布尔：避免旧 404 标记污染下一次播放错误判断
     * （读取方校验 5s 内新鲜度即可）。
     */
    public static volatile long LAST_HTTP_404_TS = 0L;

    /**
     * 【2026-09-24 403 专项】最近一次 403 的时间戳（epoch ms）。
     * 虎牙防盗链签名过期时 CDN 返回 403 Forbidden，与 404 同属"签名失效"，
     * 需立即清缓存 + 重解析签名，不走 3s 退避重试。
     */
    public static volatile long LAST_HTTP_403_TS = 0L;

    /**
     * 【2026-09-24 5xx 专项】最近一次 503/504 的时间戳（epoch ms）。
     * 503 = WAF 限流/服务不可用，504 = CDN 网关超时，
     * 二者均为 CDN 临时错误（签名仍有效），无需清缓存，
     * 但应快速重试当前线路（不等 3s 退避）。
     */
    public static volatile long LAST_HTTP_5XX_TS = 0L;

    /**
     * 【2026-09-29 虎牙 CDN 403 抖动专项】同一 URL 的内部重试次数与退避。
     *
     * <p>实测（绕过本机代理、直连 hs.hls.huya.com）：**同一条** m3u8 地址反复拉取时，
     * 403 与 200 随机交替（连续 10 次里 0~6 次成功，同一 URL 前后都能 200），
     * 与签名、参数（txyp/fm/ratio…）、Cookie、HTTP/HTTPS、IPv4/IPv6 均无关；
     * 而分片(.ts) 请求从不 403。即：播放列表请求被 CDN 侧"抽风式"拒绝。
     *
     * <p>HLS 直播必须每 2~4s 刷新一次播放列表，于是每次抽风都被上抛成
     * {@code errorCode=2001 Source error} → 上层判定"签名失效"→ 清缓存重解析 → 重启播放，
     * 表现就是"播几秒卡一下再重新接着播"。
     *
     * <p>对策：在数据源内部先重试（不惊动播放器、不触发重解析）。
     * 单次成功率约 40%，重试 5 次后单次刷新成功率 ≈ 92%。
     */
    private static final int HUYA_RETRY_MAX = 5;
    /** 逐次退避（ms），下标即第几次重试 */
    private static final long[] HUYA_RETRY_DELAYS = {120L, 220L, 350L, 500L, 700L};

    /**
     * 【2026-10-21 CDN timeout 兜底】建连/响应阶段超时（SocketTimeoutException）退避重试。
     * <p>背景：点播/直播分片直连 CDN 时，5s connectTimeout 期间网络抖动会直接抛
     * "打开连接失败: timeout" → 播放器"媒体加载错误 SocketTimeoutException" → 缓冲黑屏
     * （日志 11:14/11:18 各连续两次，CDN 抖动时周期性复发）。
     * <p>对策：数据源内部先退避重试，与虎牙 403 抖动同一思路——不惊动播放器。
     * 只重试「超时」，线程被 interrupt（播放器 seek/切台取消旧加载，即日志里的
     * "thread interrupted"）不算网络失败，立即上抛，避免把取消误当失败再空等一轮。
     */
    private static final int TIMEOUT_RETRY_MAX = 2;
    /**
     * 逐次退避（ms），下标即第几次重试。
     * ⚠️ 2026-10-23 注：直连 CDN 路径总最坏等待约 5+0.3+5+1.2 ≈ 11.5s < STUCK_TIMEOUT(20s)；
     * 本地代理路径读超时已放宽至 60s（见 {@link #LOCAL_PROXY_READ_TIMEOUT_MS}），
     * 该路径不适用此不等式——慢速分片在途期间由「网络字节进展」豁免看门狗
     * （{@code WebServerManager.isNetProgressing()}），真正 stall 由上游 30s 读超时先暴露。
     */
    private static final long[] TIMEOUT_RETRY_DELAYS = {300L, 1200L};

    private int maxRedirects = 5;
    private int connectTimeout = 10000;
    private int readTimeout = 15000;

    /**
     * 【2026-10-23 源9 慢速 CDN 专项】本地代理地址（127.0.0.1 / localhost）的读超时。
     * 代理流式转发慢速 CDN 分片时，单次读「不活动」上限 60s：分片在途即不算卡死。
     */
    private static final int LOCAL_PROXY_READ_TIMEOUT_MS = 60000;

    /** 是否为本地代理地址（WebServerManager 的 /m3u8、/ts 等） */
    private static boolean isLocalProxyUrl(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:");
    }
    private final Map<String, String> defaultRequestProperties;
    private final boolean allowCrossProtocolRedirects;
    private final boolean allowCrossDomainRedirects;
    private final boolean followRedirectsWithHeaders;
    private final boolean ignoreSslErrorRedirect;
    private HttpURLConnection connection;
    private InputStream inputStream;
    private boolean opened;
    private long bytesToRead;
    private long bytesRead;
    private int responseCode = -1;
    /** 【2026-10-28 四件套合并】响应类型与建连起点，供 close() 打一条汇总行 */
    private String respContentType = "";
    /** 【2026-10-28】请求 URL 尾段，替代原「请求链接」整行 */
    private String reqTail = "";
    /** 【2026-10-28】最近一次请求的完整 URL，错误行统一补在尾巴上 */
    private String lastUriStr = "";
    private long tOpenStartMs = 0;
    private String currentChannelName = "";

    /**
     * 【原生跟随模式】true 表示不再走 App 手写的 while 重定向解析循环，
     * 交给 HttpURLConnection 在系统层跟随 3xx。
     * 适用：虎牙等"SDK 已经解析出最终直连流地址"的场景——播放器不该再解析一遍；
     * 且能避免"禁止跨域/跨协议"等自定义限制把 CDN 的正常调度跳判成失败。
     */
    private final boolean nativeRedirectFollow;
    private OnFinalUrlListener finalUrlListener;

    /** 回调：请求结束后告知「原始地址 -> 最终地址」，供上层缓存，下次起播零跳。 */
    public interface OnFinalUrlListener {
        void onFinalUrl(String originalUrl, String finalUrl);
    }

    public void setFinalUrlListener(OnFinalUrlListener listener) {
        this.finalUrlListener = listener;
    }

    private String getTimeStr() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        return sdf.format(new Date());
    }

    /**
     * 【2026-09-29 虎牙 CDN 403 抖动专项】是否值得在数据源内部重试该响应码。
     *
     * <p>只在虎牙自有 CDN 主机上启用：其它源（影视源等）的 403/404 多是确定性防盗链失败，
     * 重试只会白等；虎牙则是随机抖动，重试命中率约 40%/次。
     */
    private static boolean shouldRetryHuyaStatus(String url, int code) {
        if (url == null) return false;
        if (!(code == 403 || code == 404 || code == 503 || code == 504)) return false;
        return url.contains(".hls.huya.com") || url.contains(".flv.huya.com")
                || url.contains(".hls.huya.cn") || url.contains(".flv.huya.cn");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String red(String text) {
        return "<font color='#FF0000'>" + text + "</font>";
    }

    /** 【2026-10-28】Content-Type 压缩成短标签，供单行汇总使用 */
    private String shortType(String ct) {
        if (ct == null || ct.isEmpty()) return "-";
        String t = ct.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("mpegurl")) return "m3u8";
        if (t.contains("video/mp2t") || t.contains("video/mpeg")) return "ts";
        if (t.contains("mp4")) return "mp4";
        if (t.contains("octet-stream")) return "bin";
        int slash = t.indexOf('/');
        String sub = slash >= 0 ? t.substring(slash + 1) : t;
        int semi = sub.indexOf(';');
        if (semi > 0) sub = sub.substring(0, semi);
        return sub.length() > 12 ? sub.substring(0, 12) : sub;
    }

    private void printLog(boolean isError, String msg) {
        // 【2026-10-28 只留错误】正常 HTTP 流程（请求成功/重定向/汇总/重定向落地）一律不记，
        //   只保留错误与告警；错误行统一补全「频道 + 完整 URL」，保证单条即可定位。
        if (!isError) return;
        // 同一失败消息 60s 内只打一条（中继不可达时探测会周期重试，避免刷屏）；map 有界防增长
        long now60 = System.currentTimeMillis();
        Long lastSame = LAST_FAIL_LOG.get(msg);
        if (lastSame != null && now60 - lastSame < 60000L) return;
        if (LAST_FAIL_LOG.size() > 64) LAST_FAIL_LOG.clear();
        LAST_FAIL_LOG.put(msg, now60);
        // 历史遗留的 HTML 着色标签对 9527 无意义，去掉
        if (msg != null) msg = msg.replaceAll("</?font[^>]*>", "");
        String finalMsg = "[" + getTimeStr() + "] " + msg;
        if (msg != null && !msg.contains("url=") && lastUriStr != null && !lastUriStr.isEmpty()) {
            finalMsg = finalMsg + " | url=" + lastUriStr;
        }
        if (!currentChannelName.isEmpty() && (msg == null || !msg.contains(currentChannelName))) {
            finalMsg = currentChannelName + " " + finalMsg;
        }
        LogCollector.getInstance().error(TAG, finalMsg);
    }

    public void setChannelName(String channelName) {
        this.currentChannelName = (channelName != null) ? channelName : "";
    }

    protected RedirectLoggingHttpDataSource(
            Map<String, String> defaultRequestProperties,
            boolean allowCrossProtocolRedirects,
            boolean allowCrossDomainRedirects,
            boolean followRedirectsWithHeaders,
            boolean ignoreSslErrorRedirect,
            int maxRedirects,
            int connectTimeout,
            int readTimeout,
            boolean nativeRedirectFollow
    ) {
        super(true);
        this.nativeRedirectFollow = nativeRedirectFollow;
        this.defaultRequestProperties = defaultRequestProperties != null
                ? new HashMap<>(defaultRequestProperties)
                : new HashMap<>();
        this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
        this.allowCrossDomainRedirects = allowCrossDomainRedirects;
        this.followRedirectsWithHeaders = followRedirectsWithHeaders;
        this.ignoreSslErrorRedirect = ignoreSslErrorRedirect;
        this.maxRedirects = maxRedirects;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
        try {
            transferInitializing(dataSpec);
            // 【DNS 广告拦截】检查请求域名是否在广告黑名单中
            String urlStr = dataSpec.uri.toString();
            if (com.tv.live.util.DnsAdBlocker.isUrlBlocked(urlStr)) {
                LogBridge.w(TAG, "DNS 拦截广告请求: " + urlStr.substring(0, Math.min(80, urlStr.length())));
                throw new HttpDataSource.HttpDataSourceException(
                    new IOException("Blocked by DNS ad filter"),
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN);
            }

            // 【URL 关键词广告拦截】检查 URL 路径是否包含广告关键词
            if (com.tv.live.util.M3u8AdStripper.isAdUrl(urlStr)) {
                LogBridge.w(TAG, "URL关键词拦截广告请求: " + urlStr.substring(0, Math.min(80, urlStr.length())));
                throw new HttpDataSource.HttpDataSourceException(
                    new IOException("Blocked by ad keyword filter"),
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN);
            }
            long tOpen0 = android.os.SystemClock.elapsedRealtime();
            tOpenStartMs = tOpen0;
            lastUriStr = dataSpec.uri.toString();
            {
                String u = dataSpec.uri.toString();
                int q = u.indexOf('?');
                if (q > 0) u = u.substring(0, q);
                int sl = u.lastIndexOf('/');
                reqTail = sl >= 0 ? u.substring(sl) : u;
                if (reqTail.length() > 28) reqTail = reqTail.substring(reqTail.length() - 28);
            }
            // 【2026-09-29 虎牙 CDN 403 抖动专项】连接 + 取响应码放进重试循环：
            // 虎牙 CDN 对同一条 m3u8 的 403/404/5xx 是随机抖动（同一 URL 前后都能 200），
            // 直接上抛会让播放器报错、上层清缓存重解析 → "播几秒卡一下再重播"。
            // 这里先在数据源内部退避重试，重试耗尽才打标（LAST_HTTP_403_TS 等）并抛出。
            String openUrl = dataSpec.uri.toString();
            int attempt = 0;
            int timeoutRetry = 0;
            while (true) {
                try {
                    connection = openConnection(dataSpec);
                    responseCode = connection.getResponseCode();
                } catch (IOException ioe) {
                    // 【2026-10-21 CDN timeout 兜底】建连/响应阶段超时（含 connect timeout）时
                    //   退避重试再上抛，避免一次超时就直接"媒体加载错误"导致黑屏。
                    //   线程被 interrupt（播放器 seek/切台取消旧加载）不算超时，立即上抛不重试，
                    //   否则会把"取消"误当"网络失败"再等一轮重试，反而拉长恢复时间。
                    boolean threadInterrupted = Thread.currentThread().isInterrupted();
                    boolean isTimeout = (ioe instanceof SocketTimeoutException) && !threadInterrupted;
                    if (!isTimeout || timeoutRetry >= TIMEOUT_RETRY_MAX) {
                        throw ioe;
                    }
                    timeoutRetry++;
                    long delay = TIMEOUT_RETRY_DELAYS[Math.min(timeoutRetry - 1, TIMEOUT_RETRY_DELAYS.length - 1)];
                    printLog(true, (currentChannelName.isEmpty() ? "" : currentChannelName + " ")
                            + "CDN 连接超时，第 " + timeoutRetry + "/" + TIMEOUT_RETRY_MAX
                            + " 次退避重试（+" + delay + "ms）: " + urlStr);
                    connection = null;
                    sleepQuietly(delay);
                    continue;
                }
                if (!shouldRetryHuyaStatus(openUrl, responseCode) || attempt >= HUYA_RETRY_MAX) {
                    break;
                }
                attempt++;
                long delay = HUYA_RETRY_DELAYS[Math.min(attempt - 1, HUYA_RETRY_DELAYS.length - 1)];
                printLog(false, (currentChannelName.isEmpty() ? "" : currentChannelName + " ")
                        + "虎牙CDN HTTP " + responseCode + " 抖动，第 " + attempt + "/" + HUYA_RETRY_MAX
                        + " 次重试（+" + delay + "ms）");
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
                connection = null;
                sleepQuietly(delay);
            }
            // 【2026-10-28 四件套合并】原「open 耗时」独立 info 行已并入 close() 的一条汇总行。

            // 【最终地址回传】原生跟随模式下由系统完成跳转，这里把"真实落地地址"交给上层缓存，
            // 下次起播/重连直接用它，避免每个分片、每次重连都再解析一遍。
            if (finalUrlListener != null && connection != null) {
                try {
                    java.net.URL finalUrlObj = connection.getURL();
                    String finalUrl = finalUrlObj != null ? finalUrlObj.toString() : null;
                    String originalUrl = dataSpec.uri.toString();
                    if (finalUrl != null) {
                        if (!finalUrl.equals(originalUrl)) {
                            printLog(false, (currentChannelName.isEmpty() ? "" : currentChannelName + " ")
                                    + "原生跟随重定向 -> 最终地址: " + finalUrl);
                        }
                        finalUrlListener.onFinalUrl(originalUrl, finalUrl);
                    }
                } catch (Throwable ignored) {}
            }

            syncResponseCookies(connection, dataSpec.uri.toString());

            if (responseCode >= 200 && responseCode < 300) {

                long contentLength = getContentLength(connection);
                String contentType = connection.getContentType();
                String contentEncoding = connection.getContentEncoding();
                // 【2026-10-28 四件套合并】不再单独打「响应」行，摘要留到 close() 汇总。
                respContentType = (contentType != null ? contentType : "unknown");
                if (contentEncoding != null) respContentType += "/" + contentEncoding;
            }

            if (responseCode < 200 || responseCode > 299) {
                String responseMessage = connection.getResponseMessage();

                printLog(true, "失败: HTTP " + red(responseCode + " " + responseMessage)
                        + " | 请求区间 position=" + dataSpec.position
                        + " length=" + (dataSpec.length == C.LENGTH_UNSET ? "unset" : dataSpec.length)
                        + " url=" + dataSpec.uri);
                // 【2026-09-19 404 专项】打标：供播放错误处理识别"地址时效失效"，
                // 走"清缓存 + 立即重解析签名"快路径（见 TVPlayerManager#isHuya404Error）
                if (responseCode == 404) {
                    LAST_HTTP_404_TS = System.currentTimeMillis();
                }
                // 【2026-09-24 403 专项】403 Forbidden（防盗链签名过期）同属签名失效
                if (responseCode == 403) {
                    LAST_HTTP_403_TS = System.currentTimeMillis();
                }
                // 【2026-09-24 5xx 专项】503(WAF限流)/504(网关超时) 均为 CDN 临时错误
                if (responseCode == 503 || responseCode == 504) {
                    LAST_HTTP_5XX_TS = System.currentTimeMillis();
                }
                throw new HttpDataSource.HttpDataSourceException(
                        "HTTP " + responseCode + " " + responseMessage,
                        dataSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN);
            }
            try {
                inputStream = connection.getInputStream();
                String contentEncoding = connection.getContentEncoding();
                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                    inputStream = new GZIPInputStream(inputStream);
                    printLog(false, "启用 GZIP 解压");
                }
            } catch (IOException e) {
                inputStream = connection.getErrorStream();
                printLog(true, "获取输入流失败，使用错误流: " + e.getMessage());
            }
            long contentLength = getContentLength(connection);
            // ⚠️【2026-09-16 修复：区间长度换算】
            //   我们带了 Range: bytes=<position>- 时，服务端回 206，此时 Content-Length 是
            //   **本次区间**的长度（Content-Range: bytes <position>-<end>/<total>），流也从 position 开始。
            //   旧实现无条件再减一次 position（contentLength - position），于是：
            //     尾部 seek（position≈52.99MB，区间长度 195KB）→ bytesToRead = 195411-52990480 = -52795069
            //     这个负数被传进 InputStream.read(buffer, offset, negative) → Android HTTP 流
            //     （okio 实现）抛 ArrayIndexOutOfBoundsException: size=16 offset=0 byteCount=-53766800
            //     → media3 包成 Loader$UnexpectedLoaderException → errorCode=2000 Source error，
            //     日志只剩一个负数，完全看不出原因（影视源里「moov 在文件尾部」的 MP4 全部中招）。
            //   现在按 HTTP 语义区分：
            //     ① 206（服务端支持 Range）：Content-Length 已是「从 position 起可读字节数」，直接用；
            //     ② 200（服务端忽略 Range，返回全长）：必须自己把流跳过 position 字节对齐，
            //        再用全长减 position。
            boolean rangeIgnored = (responseCode == 200
                    && dataSpec.position != C.POSITION_UNSET && dataSpec.position > 0);
            if (rangeIgnored) {
                long skipped = skipFully(inputStream, dataSpec.position);
                printLog(true, "服务端忽略 Range(HTTP 200 全长)，已跳过 " + skipped
                        + " 字节对齐到 position=" + dataSpec.position + " url=" + dataSpec.uri);
            }
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesToRead = dataSpec.length;
            } else if (contentLength != C.LENGTH_UNSET) {
                bytesToRead = rangeIgnored ? (contentLength - dataSpec.position) : contentLength;
            } else {
                bytesToRead = C.LENGTH_UNSET;
            }
            bytesRead = 0;
            opened = true;
            transferStarted(dataSpec);
            // 【2026-10-28 四件套合并】「连接成功，开始传输」已删，字节数由 close() 汇总行给出。

            // ⚠️【2026-09-16 越界防护】position 超过资源实际长度时（越界 seek / CDN 长度与
            //   moov 在尾部的 MP4 不匹配），contentLength - position 会算出**负数**。
            //   旧实现把这个负数一路传进 InputStream.read(buffer, offset, negative)，
            //   Android 的 HTTP 流（内部 okio 实现）会抛
            //     ArrayIndexOutOfBoundsException: size=<buf> offset=0 byteCount=-53766800
            //   被 media3 包成 Loader$UnexpectedLoaderException → errorCode=2000 Source error，
            //   日志里只剩一个「负数」完全看不出原因（实测 经典怀旧音乐 组 33 条 vdse 源如此）。
            //   这里显式识别并抛出可读异常，交由上层「失效源」逻辑处理。
            if (bytesToRead != C.LENGTH_UNSET && bytesToRead < 0) {
                printLog(true, "请求越界: position=" + dataSpec.position
                        + " > 资源长度=" + contentLength
                        + "（bytesToRead=" + bytesToRead + "），判定为源不可用 url=" + dataSpec.uri);
                closeConnectionQuietly();
                throw new HttpDataSource.HttpDataSourceException(
                        "请求越界: position=" + dataSpec.position + " > contentLength=" + contentLength,
                        dataSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN);
            }
            return bytesToRead;
        } catch (IOException e) {
            closeConnectionQuietly();
            // 【2026-10-21 降噪】播放器 seek/切台取消旧加载 → "打开连接失败: thread interrupted"，
            //   属预期行为而非网络故障。降级为 warn：不再打 error（LogCollector 对 TYPE_ERROR
            //   会自动包装 RuntimeException 上报 ExceptionReporter，每次快进/快退都会刷 error）。
            boolean threadInterrupted = Thread.currentThread().isInterrupted()
                    || e instanceof java.io.InterruptedIOException
                    || (e.getMessage() != null && e.getMessage().contains("thread interrupted"));
            if (threadInterrupted) {
                LogCollector.getInstance().warn(TAG, "[" + getTimeStr() + "] 打开连接失败: "
                        + e.getMessage() + "（播放器取消加载，忽略）");
            } else {
                printLog(true, "打开连接失败: " + e.getMessage());
            }
            throw new HttpDataSource.HttpDataSourceException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        }
    }

    private void syncResponseCookies(HttpURLConnection conn, String requestUrl) {
        Map<String, List<String>> headerMap = conn.getHeaderFields();
        List<String> cookieList = headerMap.get("Set-Cookie");
        if (cookieList == null || cookieList.isEmpty()) return;
        CookieManager cookieManager = CookieManager.getInstance();
        for (String cookieStr : cookieList) {
            cookieManager.setCookie(requestUrl, cookieStr);
        }
    }

    /** 建立单跳连接（超时/请求头/Range），不含重定向处理——重定向由调用方决定策略。 */
    private HttpURLConnection openSingleConnection(DataSpec dataSpec, String url,
                                                   Map<String, String> headers,
                                                   boolean firstHop) throws IOException {
        // 【2026-10-25 NO_PROXY】这是**所有源共用**的播放数据源（虎牙/源9/任意播/咪咕…）。
        //   openConnection() 无参会走默认 ProxySelector，设备配了 WiFi 代理就整体播不动；
        //   而 127.0.0.1 的本地剥离代理同样应直连（部分 ROM 不豁免回环）。
        //   统一绕开系统代理，与虎牙 6 处口径一致。
        HttpURLConnection conn = (HttpURLConnection) new URL(url)
                .openConnection(java.net.Proxy.NO_PROXY);
        conn.setConnectTimeout(connectTimeout);
        // 【2026-10-23 源9 慢速 CDN 专项】本地代理地址（127.0.0.1:<port>）动态放宽读超时：
        //   代理侧已改为流式转发（边下边播），慢速 CDN 下一个大分片需 25~45s，
        //   播放器 4s 读超时会把「正在途上」的数据误判成卡死并取消加载（实测源9 24s 重试风暴）。
        //   直连 CDN 路径保持 4s 不动——虎牙分片 TTFB<300ms 的调优（见 Factory 处注释）不能破。
        conn.setReadTimeout(isLocalProxyUrl(url) ? LOCAL_PROXY_READ_TIMEOUT_MS : readTimeout);
        conn.setDoInput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        if (followRedirectsWithHeaders || firstHop) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (dataSpec.position != C.POSITION_UNSET) {
            String rangeValue = "bytes=" + dataSpec.position + "-";
            if (dataSpec.length != C.LENGTH_UNSET) {
                rangeValue += (dataSpec.position + dataSpec.length - 1);
            }
            conn.setRequestProperty("Range", rangeValue);
        }
        return conn;
    }

    private HttpURLConnection openConnection(DataSpec dataSpec) throws IOException {
        String originalUrl = dataSpec.uri.toString();
        String currentUrl = originalUrl;
        int redirectCount = 0;
        Map<String, String> originHeaders = new HashMap<>(defaultRequestProperties);

        long startTime = System.currentTimeMillis();
        final long MAX_TOTAL_DELAY = 15000;

        String prefix = TextUtils.isEmpty(currentChannelName) ? "" : (currentChannelName + " ");
        // 【2026-10-28 合并】「请求链接」独立行已删（与 close() 汇总行重复），
        //   URL 尾段（index.m3u8 / 179314354.ts）随汇总行一起输出，见 open() 里的 reqTail。

        // 【原生跟随模式】SDK 已解析出直连流地址（虎牙等）：不再自己解析重定向，
        // 交给 HttpURLConnection 在系统层跟随 3xx。省掉每跳 disconnect+重建+重设头，
        // 也不会被"禁止跨域/跨协议"等自定义限制误伤。
        if (nativeRedirectFollow) {
            HttpURLConnection conn = openSingleConnection(dataSpec, currentUrl, originHeaders, true);
            conn.setInstanceFollowRedirects(true);
            return conn;
        }

        while (true) {
            if (System.currentTimeMillis() - startTime > MAX_TOTAL_DELAY) {
                printLog(true, "失败: 重定向总耗时超时");
                throw new RedirectFailedException("重定向总耗时超时", -1, originalUrl, currentUrl);
            }

            if (redirectCount > maxRedirects) {

                printLog(true, "失败: 重定向次数超过限制(" + red(String.valueOf(maxRedirects)) + "次)");
                throw new RedirectFailedException("重定向次数超限", -1, originalUrl, currentUrl);
            }
            HttpURLConnection conn = openSingleConnection(dataSpec, currentUrl, originHeaders, redirectCount == 0);
            long tReq0 = android.os.SystemClock.elapsedRealtime();
            int respCode = conn.getResponseCode();
            long tReq1 = android.os.SystemClock.elapsedRealtime();
            // 冷启动诊断：单跳"建连+响应"耗时，仅 debug 包打印（避免 release 每分片刷屏）
            if (BuildConfig.IS_DEBUG && (tReq1 - tReq0 > 200 || redirectCount > 0)) {
                LogCollector.getInstance().debug(TAG, prefix + "单跳建连+响应 " + (tReq1 - tReq0)
                        + "ms (第" + (redirectCount + 1) + "跳) url=" + currentUrl);
            }
            boolean isRedirect = (respCode == 301 || respCode == 302
                    || respCode == 303 || respCode == 307 || respCode == 308);
            if (!isRedirect) {
                printLog(false, prefix + "请求成功 状态码 " + respCode + ": " + currentUrl);
                return conn;
            }
            redirectCount++;
            String location = conn.getHeaderField("Location");
            if (TextUtils.isEmpty(location)) {

                printLog(true, "失败: 第" + red(String.valueOf(redirectCount)) + "次重定向无Location头");
                conn.disconnect();
                throw new RedirectFailedException("重定向Location为空", respCode, originalUrl, currentUrl);
            }
            String redirectUrl = resolveRedirectUrl(currentUrl, location);
            Uri baseUri = Uri.parse(currentUrl);
            Uri targetUri = Uri.parse(redirectUrl);
            boolean crossProtocol = !Objects.equals(baseUri.getScheme(), targetUri.getScheme());
            if (crossProtocol && !allowCrossProtocolRedirects) {
                printLog(true, "失败: 禁止跨协议跳转");
                conn.disconnect();
                throw new RedirectFailedException("跨协议重定向被禁用", respCode, originalUrl, redirectUrl);
            }
            boolean crossDomain = !Objects.equals(baseUri.getHost(), targetUri.getHost());
            boolean isInner = isInnerIp(targetUri.getHost());
            if (crossDomain && !allowCrossDomainRedirects && !isInner) {
                printLog(true, "失败: 禁止跨域名跳转");
                conn.disconnect();
                throw new RedirectFailedException("跨域名重定向被禁用", respCode, originalUrl, redirectUrl);
            }
            if (ignoreSslErrorRedirect && "https".equals(targetUri.getScheme())) {

            }

            printLog(false, prefix + "重定向" + redirectCount + "次~ 状态码 " + respCode + ": " + redirectUrl);

            conn.disconnect();
            currentUrl = redirectUrl;
        }
    }

    private boolean isInnerIp(String host) {
        if (host == null) return false;
        return host.startsWith("127.")
                || host.startsWith("192.168.")
                || host.startsWith("10.")
                || host.equals("localhost");
    }

    private String resolveRedirectUrl(String baseUrl, String location) throws IOException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        Uri baseUri = Uri.parse(baseUrl);
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost();
        int port = baseUri.getPort();
        String path = baseUri.getPath();
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1 && port != 80 && port != 443) {
            sb.append(":").append(port);
        }
        if (location.startsWith("/")) {
            sb.append(location);
        } else {
            if (path != null && path.contains("/")) {
                String parentPath = path.substring(0, path.lastIndexOf('/') + 1);
                sb.append(parentPath).append(location);
            } else {
                sb.append("/").append(location);
            }
        }
        return sb.toString();
    }

    /**
     * 尽力跳过 n 字节（仅用于「服务端忽略 Range 返回 200 全长」时对齐 position）。
     * 跳过不足则抛 IOException，避免把错位的数据交给解析器。
     */
    private long skipFully(InputStream in, long n) throws IOException {
        long total = 0;
        byte[] buf = null;
        while (total < n) {
            long skipped = in.skip(n - total);
            if (skipped > 0) {
                total += skipped;
                continue;
            }
            if (buf == null) buf = new byte[8192];
            int r = in.read(buf, 0, (int) Math.min(buf.length, n - total));
            if (r == -1) break;
            total += r;
        }
        if (total < n) {
            throw new IOException("跳过字节不足: " + total + "/" + n + "（服务端返回 200 但未给全量数据）");
        }
        return total;
    }

    private long getContentLength(HttpURLConnection connection) {
        String contentLength = connection.getHeaderField("Content-Length");
        if (!TextUtils.isEmpty(contentLength)) {
            try {
                return Long.parseLong(contentLength);
            } catch (NumberFormatException ignored) {}
        }
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSource.HttpDataSourceException {
        if (readLength == 0) return 0;
        if (bytesToRead == 0) return C.RESULT_END_OF_INPUT;

        // 剩余可读字节数（用 long 计算，避免 bytesToRead - bytesRead 变负数）：
        // 越界时直接返回 EOF，绝不把负的 length 传进 InputStream.read（见 open() 里的说明）。
        long remaining = (bytesToRead == C.LENGTH_UNSET) ? Long.MAX_VALUE : (bytesToRead - bytesRead);
        if (remaining <= 0) return C.RESULT_END_OF_INPUT;
        if (readLength < 0) return 0;

        try {
            int maxRead = (int) Math.min(readLength, remaining);
            int readSize = inputStream.read(buffer, offset, maxRead);
            if (readSize == -1) {
                if (bytesToRead != C.LENGTH_UNSET && bytesRead != bytesToRead) {
                    throw new HttpDataSource.HttpDataSourceException(
                            "流提前中断",
                            new DataSpec(Uri.parse(connection.getURL().toString())),
                            HttpDataSource.HttpDataSourceException.TYPE_READ);
                }
                return C.RESULT_END_OF_INPUT;
            }
            bytesRead += readSize;
            bytesTransferred(readSize);
            // 【2026-10-23】网络字节交付打点：卡住检测看门狗据此把「慢速但活着」的
            //   直连读取（源9 点播直连 CDN 等）视为有进展，不误判卡死（见 WebServerManager）。
            WebServerManager.markNetProgress();
            return readSize;
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(e,
                    new DataSpec(Uri.parse(connection.getURL().toString())),
                    HttpDataSource.HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public Uri getUri() {
        return connection == null ? null : Uri.parse(connection.getURL().toString());
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return connection == null ? null : connection.getHeaderFields();
    }

    @Override
    public void setRequestProperty(String name, String value) {
        defaultRequestProperties.put(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        defaultRequestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        defaultRequestProperties.clear();
    }

    @Override
    public void close() throws HttpDataSource.HttpDataSourceException {
        if (opened) {
            opened = false;
            transferEnded();

            if (bytesRead > 0) {
                // 【2026-10-28 四件套合并】每请求只留这一条：频道 + 状态码 + 类型 + 字节 + 耗时
                long cost = tOpenStartMs > 0 ? (android.os.SystemClock.elapsedRealtime() - tOpenStartMs) : -1;
                printLog(false, (currentChannelName.isEmpty() ? "" : currentChannelName + " ")
                        + "HTTP " + responseCode + " " + shortType(respContentType)
                        + " " + bytesRead + "B" + (bytesRead >= 1024 ? "(" + (bytesRead / 1024) + "KB)" : "")
                        + (cost >= 0 ? " 耗时=" + cost + "ms" : "")
                        + " " + reqTail);
            }
            closeConnectionQuietly();
        }
    }

    private void closeConnectionQuietly() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}
        inputStream = null;
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    public static final class Factory implements DataSource.Factory {
        private final Map<String, String> defaultRequestProperties = new HashMap<>();
        private boolean allowCrossProtocolRedirects = true;
        private boolean allowCrossDomainRedirects = true;
        private boolean followRedirectsWithHeaders = true;
        private boolean ignoreSslErrorRedirect = false;
        private int maxRedirects = 5;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 15000;
        private String channelName = "";
        private boolean nativeRedirectFollow = false;
        private OnFinalUrlListener finalUrlListener = null;

        public Factory() {}

        public Factory setDefaultRequestProperties(Map<String, String> map) {
            defaultRequestProperties.clear();
            if (map != null) defaultRequestProperties.putAll(map);
            return this;
        }

        public Factory setMaxRedirects(int count) {
            this.maxRedirects = count;
            return this;
        }

        public Factory setAllowCrossProtocolRedirects(boolean enable) {
            this.allowCrossProtocolRedirects = enable;
            return this;
        }

        public Factory setAllowCrossDomainRedirects(boolean enable) {
            this.allowCrossDomainRedirects = enable;
            return this;
        }

        public Factory setFollowRedirectsWithHeaders(boolean enable) {
            this.followRedirectsWithHeaders = enable;
            return this;
        }

        public Factory setIgnoreSslErrorRedirect(boolean ignore) {
            this.ignoreSslErrorRedirect = ignore;
            return this;
        }

        public Factory setConnectTimeoutMs(int ms) {
            this.connectTimeoutMs = ms;
            return this;
        }

        public Factory setReadTimeoutMs(int ms) {
            this.readTimeoutMs = ms;
            return this;
        }

        public Factory setChannelName(String name) {
            this.channelName = name;
            return this;
        }

        /**
         * 开启原生跟随：地址已由上游（虎牙 SDK）解析好时开启，
         * 播放器不再自己解析重定向，交由 HttpURLConnection 跟随 3xx。
         */
        public Factory setNativeRedirectFollow(boolean enable) {
            this.nativeRedirectFollow = enable;
            return this;
        }

        /** 设置「原始地址 -> 最终地址」回传，用于上层缓存、下次直连。 */
        public Factory setFinalUrlListener(OnFinalUrlListener listener) {
            this.finalUrlListener = listener;
            return this;
        }

        @Override
        public DataSource createDataSource() {
            RedirectLoggingHttpDataSource source = new RedirectLoggingHttpDataSource(
                    defaultRequestProperties,
                    allowCrossProtocolRedirects,
                    allowCrossDomainRedirects,
                    followRedirectsWithHeaders,
                    ignoreSslErrorRedirect,
                    maxRedirects,
                    connectTimeoutMs,
                    readTimeoutMs,
                    nativeRedirectFollow
            );
            source.setChannelName(channelName);
            source.setFinalUrlListener(finalUrlListener);
            return source;
        }
    }
}
