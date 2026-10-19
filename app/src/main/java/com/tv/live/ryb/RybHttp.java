package com.tv.live.ryb;

import com.tv.live.util.LogBridge;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 【2026-10-19 源8 renyibo】站点专用 HTTP 客户端。
 *
 * <p>为什么不直接用 {@code NetUtil.syncGetWithHeaders}：
 * <ul>
 *   <li>它无条件给请求加 {@code Accept-Encoding: identity} → 站点返回**未压缩** HTML，
 *       renyibo 的分类页/详情页都是 40~230KB，gzip 后只有约 1/5；</li>
 *   <li>它每次请求都 {@code newBuilder().build()} 新建 OkHttpClient → 连接池不共享，
 *       16 个列表页 = 16 次 TCP+TLS 握手。</li>
 * </ul>
 * 这里自带连接池（4 条 / 5 分钟）且**不设置** Accept-Encoding（OkHttp 自动加 gzip 并解压），
 * 同时仍走 {@link com.tv.live.util.SecurityCertificatePinner} 保留证书固定。
 */
final class RybHttp {

    private static final String TAG = "RybHttp";

    static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final long CONNECT_MS = 8000L;
    private static final long READ_MS = 15000L;
    private static final long CALL_MS = 20000L;

    private static volatile OkHttpClient sHttp;

    private RybHttp() {
    }

    static OkHttpClient client() {
        OkHttpClient c = sHttp;
        if (c != null) return c;
        synchronized (RybHttp.class) {
            if (sHttp == null) {
                java.util.concurrent.TimeUnit ms = java.util.concurrent.TimeUnit.MILLISECONDS;
                OkHttpClient.Builder b = new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_MS, ms)
                        .readTimeout(READ_MS, ms)
                        .callTimeout(CALL_MS, ms)
                        .retryOnConnectionFailure(true)
                        .connectionPool(new okhttp3.ConnectionPool(4, 5, java.util.concurrent.TimeUnit.MINUTES))
                        .proxy(java.net.Proxy.NO_PROXY);
                b = com.tv.live.util.SecurityCertificatePinner.getInstance().configureOkHttpClient(b);
                sHttp = b.build();
            }
            return sHttp;
        }
    }

    /**
     * GET 文本（HTML/JSON）。
     *
     * @return 响应体；非 2xx 或异常返回 null
     */
    static String get(String url) {
        long t0 = System.currentTimeMillis();
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .get()
                    .build();
            try (Response resp = client().newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    LogBridge.w(TAG, "GET 失败 code=" + resp.code()
                            + " 耗时=" + (System.currentTimeMillis() - t0) + "ms url=" + url);
                    return null;
                }
                ResponseBody body = resp.body();
                String text = body == null ? null : body.string();
                // 【2026-10-19 性能日志】详情页/列表页耗时口径（源8 集数加载慢的定位依据）
                LogBridge.i(TAG, "GET code=" + resp.code()
                        + " 耗时=" + (System.currentTimeMillis() - t0) + "ms"
                        + " len=" + (text == null ? 0 : text.length())
                        + " url=" + url);
                return text;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET 异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage()
                    + " 耗时=" + (System.currentTimeMillis() - t0) + "ms url=" + url);
            return null;
        }
    }
}
