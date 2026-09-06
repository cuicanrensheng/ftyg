package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
    private final OkHttpClient mClient;
    private final OkHttpClient mSecureClient;

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

    private static final String HEADER_REQUEST_SIGNATURE = "X-Request-Signature";
    private static final String HEADER_REQUEST_TIMESTAMP = "X-Request-Timestamp";
    private static final String HEADER_REQUEST_NONCE = "X-Request-Nonce";
    private static final String HEADER_APP_VERSION = "X-App-Version";
    private static final String HEADER_DEVICE_ID = "X-Device-Id";

    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    private NetUtil() {

        mClient = createBaseClientBuilder()
                .addInterceptor(new SecurityRequestInterceptor(false))
                .build();

        mSecureClient = createBaseClientBuilder()
                .addInterceptor(new SecurityRequestInterceptor(true))
                .addNetworkInterceptor(new SensitiveDataProtectionInterceptor())
                .build();

        LogBridge.i(TAG, "NetUtil 初始化完成（含安全配置）");
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

            if (url.startsWith("https://")) {
                addIntegrityHeaders(requestBuilder, originalRequest);
            }

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

    private void addIntegrityHeaders(Request.Builder builder, Request originalRequest) {

        String timestamp = String.valueOf(System.currentTimeMillis());

        String nonce = generateNonce();

        String deviceId = getAnonymousDeviceId();

        String signature = calculateRequestSignature(
                originalRequest.method(),
                originalRequest.url().toString(),
                timestamp,
                nonce
        );

        builder.header(HEADER_REQUEST_TIMESTAMP, timestamp)
               .header(HEADER_REQUEST_NONCE, nonce)
               .header(HEADER_REQUEST_SIGNATURE, signature)
               .header(HEADER_DEVICE_ID, deviceId)
               .header(HEADER_APP_VERSION, getAppVersion());
    }

    private String generateNonce() {
        SecureRandom random = new SecureRandom();
        byte[] nonceBytes = new byte[8];
        random.nextBytes(nonceBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : nonceBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String calculateRequestSignature(String method, String url, String timestamp, String nonce) {
        try {

            String signData = method + "\n" + url + "\n" + timestamp + "\n" + nonce;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signData.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }

            return hexString.toString();
        } catch (Exception e) {
            LogBridge.e(TAG, "计算请求签名失败: " + e.getMessage());
            return "";
        }
    }

    private String getAnonymousDeviceId() {
        try {
            if (sAppContext != null) {
                String androidId = android.provider.Settings.Secure.getString(
                        sAppContext.getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID);
                if (androidId != null) {

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] hash = digest.digest(androidId.getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        sb.append(String.format("%02x", hash[i]));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            LogBridge.w(TAG, "获取设备ID失败: " + e.getMessage());
        }
        return "unknown";
    }

    private String getAppVersion() {
        try {
            if (sAppContext != null) {
                return sAppContext.getPackageManager()
                        .getPackageInfo(sAppContext.getPackageName(), 0)
                        .versionName;
            }
        } catch (Exception e) {

        }
        return "1.0.0";
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

        String referer, origin;
        if (url.contains("huya.com") || url.contains("huya.cn")) {
            referer = "https://www.huya.com/";
            origin = "https://www.huya.com";
        } else if (url.contains("douyu.com") || url.contains("douyucdn.cn")) {
            referer = "https://www.douyu.com";
            origin = "https://www.douyu.com";
        } else {
            referer = "https://www.huya.com/";
            origin = "https://www.huya.com";
        }
        headerMap.put("Referer", referer);
        headerMap.put("Origin", origin);
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

        OkHttpClient client = isSensitiveUrl(url) ? mSecureClient : mClient;
        Call call = client.newCall(request);
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

        OkHttpClient client = isSensitiveUrl(url) ? mSecureClient : mClient;
        return client.newCall(request).execute();
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
        return mClient;
    }

    public OkHttpClient getSecureClient() {
        return mSecureClient;
    }

    public Response syncGetNoRedirect(String url) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        OkHttpClient noRedirectClient = mClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        return noRedirectClient.newCall(request).execute();
    }
}
