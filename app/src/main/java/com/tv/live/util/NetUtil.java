package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;
import com.tv.live.util.HuyaStreamPlayer.HuyaHttpThrottle;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NetUtil {

    private static final String TAG = "NetUtil";

    private static volatile NetUtil sInstance;
    private static Context sAppContext;
    // 【2026-10-05 冷启动提速·P0-c】改为懒构造：不再是 final，由 client()/secureClient() 双检锁按需创建。
    private volatile OkHttpClient mClient;
    private volatile OkHttpClient mSecureClient;

    private static final long CONNECT_TIMEOUT = 10000L;
    private static final long READ_TIMEOUT = 15000L;
    private static final long WRITE_TIMEOUT = 10000L;

    private static final String[] SENSITIVE_DOMAINS = {
        "github.com",
        "githubusercontent.com",
        "huya.com",
        "huya.cn",
        "cloud.tv.live.com"
    };

    // 【2026-10-22 I1-06】删除 X-Request-Signature/X-Request-Timestamp/X-Request-Nonce/
    //   X-Device-Id/X-App-Version 一整套「完整性头」：签名是对 method\nurl\nts\nnonce 的
    //   裸 SHA-256，无共享密钥 → 同网段任何人可重放/伪造，"防误不防攻"，头存在本身反而
    //   易误导维护者高估防护；且每次请求白跑一次 SecureRandom。第三方服务器没有我们的
    //   共享密钥，不可能校验这些头。如需真鉴权，应在密钥经 secstore 下沉后改 HMAC-SHA256。

    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    private NetUtil() {
        // 【2026-10-05 冷启动提速·P0-c】原实现在构造函数里就把两个 OkHttpClient 建好：
        //   createBaseClientBuilder()
        //     → SecurityCertificatePinner.configureOkHttpClient(builder)
        //       → HttpsURLConnection.getDefaultSSLSocketFactory()  ← 触发默认 SSLContext 冷初始化
        //       → new CertAwareTrustManager()                      ← TrustManagerFactory.init(null) 加载系统信任库
        // 冷启动埋点实测（设备端同一时钟）：单例构造共 **1765ms**（mClient 1519ms + mSecureClient 246ms），
        // 而第一个调用方 `doPlayNormal` 只是为了 `createCommonHeaders()`（纯 `Headers.of` 拼装，
        // **一个 client 都用不到**）→ 这 1.8s 白白挡在「doPlay → 播放器发出首个 m3u8 请求」的关键路径上。
        // 现改为**懒构造**：构造函数与 getInstance() 变为瞬时；OkHttpClient 推迟到首次真正发请求时创建
        // （此时通常已在后台线程，如直播源 / EPG / 虎牙线路的下载路径）。
        // ⚠️ 安全性完全不变：builder 链、拦截器、证书固定（CertAwareTrustManager / HostnameVerifier）
        //    与虎牙限流配置**逐字节保持原样**，仅构建时机后移。
    }

    /** 主客户端（安全请求头 + 虎牙限流）。懒构造，双检锁。 */
    private OkHttpClient client() {
        OkHttpClient c = mClient;
        if (c == null) {
            synchronized (this) {
                c = mClient;
                if (c == null) {
                    long t0 = System.currentTimeMillis();
                    // 【2026-09-19 503 彻底修复】挂虎牙限流拦截器：所有去 huya.com /
                    // live.cdn.huya.com 的请求（含一起看拉取、频道列表、播放地址等）统一收敛到
                    // 全局信号量（并发 ≤4），从源头消除 WAF 503。
                    c = mClient = HuyaHttpThrottle.attach(createBaseClientBuilder())
                            .addInterceptor(new SecurityRequestInterceptor(false))
                            .build();
                }
            }
        }
        return c;
    }

    /** 敏感域名客户端（证书固定 + 敏感数据保护 + 虎牙限流）。懒构造，双检锁。 */
    private OkHttpClient secureClient() {
        OkHttpClient c = mSecureClient;
        if (c == null) {
            synchronized (this) {
                c = mSecureClient;
                if (c == null) {
                    long t0 = System.currentTimeMillis();
                    c = mSecureClient = HuyaHttpThrottle.attach(createBaseClientBuilder())
                            .addInterceptor(new SecurityRequestInterceptor(true))
                            .addNetworkInterceptor(new SensitiveDataProtectionInterceptor())
                            .build();
                }
            }
        }
        return c;
    }

    private OkHttpClient.Builder createBaseClientBuilder() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .proxy(java.net.Proxy.NO_PROXY);

        SecurityCertificatePinner pinner = SecurityCertificatePinner.getInstance();
        return pinner.configureOkHttpClient(builder);
    }

    public static NetUtil getInstance() {
        if (sInstance == null) {
            synchronized (NetUtil.class) {
                if (sInstance == null) {
                    sInstance = new NetUtil();
                }
            }
        }
        return sInstance;
    }

    private class SecurityRequestInterceptor implements Interceptor {
        private final boolean isSecure;

        SecurityRequestInterceptor(boolean isSecure) {
            this.isSecure = isSecure;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            String url = originalRequest.url().toString();

            Request.Builder requestBuilder = originalRequest.newBuilder()
                    .header("Accept-Encoding", "identity");
            // 【2026-10-22 I1-06】完整性头已整体删除（见类顶部说明）：不再 addIntegrityHeaders

            Response response;
            try {
                response = chain.proceed(requestBuilder.build());
            } catch (IOException e) {
                LogBridge.e(TAG, "请求失败: " + url + " -> " + e.getMessage());
                throw e;
            }

            int code = response.code();
            if (code >= 400) {
                LogBridge.w(TAG, "异常响应: " + code + " " + url);
            }

            return response;
        }
    }

    private class SensitiveDataProtectionInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = chain.proceed(request);

            String host = request.url().host();
            if (isSensitiveDomain(host)) {
                int code = response.code();

                if (code >= 400) {
                    LogBridge.w(TAG, "敏感请求异常响应: " + host + " -> " + code);
                }
            }

            return response;
        }
    }

    private boolean isSensitiveDomain(String host) {
        if (host == null) return false;
        for (String domain : SENSITIVE_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    public Headers createCommonHeaders(String url) {
        Map<String, String> headerMap = new HashMap<>();

        String userAgent = "ExoPlayer";

        if (sAppContext != null) {
            SharedPreferences sp = sAppContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            String customUA = sp.getString("custom_user_agent", "");
            if (!TextUtils.isEmpty(customUA)) {
                userAgent = customUA;
            } else {
                String uaMode = sp.getString("user_agent_mode", "exo");
                if ("vlc".equals(uaMode)) {
                    userAgent = "VLC";
                }
            }
        }

        headerMap.put("User-Agent", userAgent);
        headerMap.put("Accept", "*");
        headerMap.put("Connection", "keep-alive");
        headerMap.put("Icy-MetaData", "1");
        headerMap.put("Accept-Language", "zh-CN,zh;q=0.9");

        String referer = null, origin = null;
        if (url.contains("huya.com") || url.contains("huya.cn")) {
            referer = "https://www.huya.com/";
            origin = "https://www.huya.com";
        } else if (url.contains("douyu.com") || url.contains("douyucdn.cn")) {
            referer = "https://www.douyu.com";
            origin = "https://www.douyu.com";
        } else if (url.contains("bdstatic.com") || url.contains("baidu.com")) {
            // ⚠️【2026-09-16 修复】百度系 CDN（vdse/vd2/vd3/vd4/p2.bdstatic.com，本工程影视源 140 条）
            //   有防盗链校验：带虎牙 Referer 一定 403（实测 ExoPlayer UA + huya Referer → 403；
            //   ExoPlayer UA 单独或带 baidu Referer → 206）。
            referer = "https://www.baidu.com/";
            origin = "https://www.baidu.com";
        }
        // ★【2026-09-16 修复】其它域名一律**不再注入** Referer/Origin。
        //   旧实现对未知域名也塞 https://www.huya.com/，实测危害（经典怀旧音乐组 399 条逐个探测）：
        //     带虎牙 Referer 403 → 不带 Referer 206 的有 114 条（sog.mvbox.cn 53 条全中、
        //     vd2/vd3/vd4.bdstatic.com 58 条、其余零散），占该组 28.6%。
        //   对齐参考实现（哦了电视/TVBox：Live.getHeaders() 只在源配置里显式给了 ua/origin/referer
        //   时才发送）后，这 114 条即可正常 206。
        if (referer != null) headerMap.put("Referer", referer);
        if (origin != null) headerMap.put("Origin", origin);
        return Headers.of(headerMap);
    }

    public Headers createHuyaFixedHeaders() {
        return createCommonHeaders("https://www.huya.com");
    }

    public Response syncGet(String url) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();

        OkHttpClient okClient = isSensitiveUrl(url) ? secureClient() : client();
        Call call = okClient.newCall(request);
        return call.execute();
    }

    public Response syncGetWithHeaders(String url, Map<String, String> customHeaders) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .headers(headers);

        if (customHeaders != null) {
            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        Request request = requestBuilder.get().build();

        OkHttpClient okClient = isSensitiveUrl(url) ? secureClient() : client();
        return okClient.newCall(request).execute();
    }

    /**
     * 【2026-10-11 源7降级加固】带自定义超时的 GET 重载。
     *
     * <p>仅用 {@code client()/secureClient()} 的 {@code newBuilder()} 派生一个<b>一次性</b>客户端，
     * 覆盖本次请求的 connect/read 超时；<b>不修改全局 client 的超时、拦截器或任何既有行为</b>
     * （{@code newBuilder()} 共享连接池/线程池/拦截器，只改变时间参数）。
     *
     * <p>headers 构造逻辑与 {@link #syncGetWithHeaders(String, Map)} 完全一致：
     * 先铺 {@code createCommonHeaders(url)}，再用 {@code customHeaders} 逐项覆盖。
     *
     * @param connectMs 本次连接超时（毫秒）
     * @param readMs    本次读取超时（毫秒）
     */
    public Response syncGetWithHeaders(String url, Map<String, String> customHeaders,
                                       long connectMs, long readMs) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .headers(headers);

        if (customHeaders != null) {
            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        Request request = requestBuilder.get().build();

        OkHttpClient base = isSensitiveUrl(url) ? secureClient() : client();
        OkHttpClient okClient = base.newBuilder()
                .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
                .readTimeout(readMs, TimeUnit.MILLISECONDS)
                .build();
        return okClient.newCall(request).execute();
    }

    public String syncGetText(String url) throws IOException {
        try (Response response = syncGet(url)) {
            int code = response.code();
            if (code == 403) {
                throw new IOException("HTTP 403 防盗链拦截 url=" + url);
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("请求失败 code=" + code);
            }
            return response.body().string();
        }
    }

    private boolean isSensitiveUrl(String url) {
        if (url == null) return false;
        for (String domain : SENSITIVE_DOMAINS) {
            if (url.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    public OkHttpClient getClient() {
        return client();
    }

    public OkHttpClient getSecureClient() {
        return secureClient();
    }

    public Response syncGetNoRedirect(String url) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        OkHttpClient noRedirectClient = client().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        return noRedirectClient.newCall(request).execute();
    }
}
