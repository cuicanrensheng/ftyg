package com.tv.live.migu.js;

import android.content.Context;
import android.annotation.SuppressLint;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tv.live.util.LogBridge;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * JS 引擎的原生桥接对象 —— 1:1 对应畅享TV 的
 * {@code org.fungo.jslayer.JsLayer$ParserJsInterface}。
 *
 * <p>注入名固定为 <b>{@code client}</b>（见 {@link JsEngine}），
 * {@code assets/js/native_layer.js} 里的 {@code urlRequest / getMD5 / log / ...}
 * 全部转调本对象。
 *
 * <p><b>为什么可以同步</b>：{@code @JavascriptInterface} 方法由 WebView 在 JavaBridge
 * 线程执行，JS 侧调用会阻塞等待返回值 —— 这正是原版 {@code var data = urlRequest(URL)}
 * 能直接拿到字符串的原因。因此这里用 OkHttp 同步请求是正确做法，且不会阻塞主线程。
 *
 * <p><b>红线约束</b>：{@code retryOnConnectionFailure(false)}、不做任何并发与重试，
 * 单次解析只发它自己那一次请求。
 *
 * <p>仅咪咕链路实际用到 {@code getMD5} / {@code getUrlContent} / {@code setLog}；
 * 其余方法为兼容其它协议保留实现或安全兜底，避免 JS 抛「方法不存在」中断解析。
 */
public final class JsBridge {

    private static final String TAG = "MiguJsBridge";

    /** 单次网络调用的整体超时（毫秒）——JS 会同步等待，故不宜过长 */
    private static final long CALL_TIMEOUT_SEC = 20L;

    private final Context appContext;
    private final OkHttpClient client;

    public JsBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                // 【2026-10-25 NO_PROXY】播放链路统一绕开系统代理
                .proxy(java.net.Proxy.NO_PROXY)
                // 红线：不做连接失败重试
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    // ------------------------------------------------------------------
    // 网络 —— 对应 native_layer.js 的 urlRequest / getUrlContent / postUrl
    // ------------------------------------------------------------------

    /** 同步 GET。{@code headerJson} 形如 {@code "{'User-Agent':'...'}"}（单引号，宽松解析） */
    @JavascriptInterface
    public String getUrlContent(String url, String headerJson) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            Request.Builder rb = new Request.Builder().url(url).get();
            applyHeaders(rb, parseHeaders(headerJson));
            return execute(rb.build());
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET 失败 url=" + abbreviate(url) + " : " + brief(t));
            return "";
        }
    }

    /** 同步 GET，返回「响应头 + 换行 + 响应体」 */
    @JavascriptInterface
    public String getRespAndHeaders(String url, String headerJson) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            Request.Builder rb = new Request.Builder().url(url).get();
            applyHeaders(rb, parseHeaders(headerJson));
            try (Response resp = client.newCall(rb.build()).execute()) {
                StringBuilder sb = new StringBuilder();
                for (String name : resp.headers().names()) {
                    sb.append(name).append(": ").append(resp.header(name)).append('\n');
                }
                sb.append('\n');
                if (resp.body() != null) sb.append(resp.body().string());
                return sb.toString();
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET(带响应头) 失败：" + brief(t));
            return "";
        }
    }

    /** 同步 POST。{@code mediaType} 通常为 {@code application/json} */
    @JavascriptInterface
    public String postUrlContent(String url, String post, String headerJson, String mediaType) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            MediaType mt = MediaType.parse(
                    TextUtils.isEmpty(mediaType) ? "application/json" : mediaType);
            RequestBody body = RequestBody.create(post == null ? "" : post, mt);
            Request.Builder rb = new Request.Builder().url(url).post(body);
            applyHeaders(rb, parseHeaders(headerJson));
            return execute(rb.build());
        } catch (Throwable t) {
            LogBridge.w(TAG, "POST 失败 url=" + abbreviate(url) + " : " + brief(t));
            return "";
        }
    }

    private String execute(Request request) throws Exception {
        try (Response resp = client.newCall(request).execute()) {
            if (resp.body() == null) return "";
            return resp.body().string();
        }
    }

    private static void applyHeaders(Request.Builder rb, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (TextUtils.isEmpty(k)) continue;
            try {
                rb.header(k, v == null ? "" : v);
            } catch (Throwable ignored) {
                // 单个非法头不影响整体请求
            }
        }
    }

    /**
     * 宽松解析 header JSON。
     *
     * <p>{@code native_layer.js} 拼出来的是 {@code "{'User-Agent':'...'}"} —— 用<b>单引号</b>，
     * 标准 JSON 不合法（原版靠 fastjson 宽松模式吃下）。这里做等价兼容：先把单引号整体
     * 替换成双引号再交给 Gson。
     */
    private static Map<String, String> parseHeaders(String headerJson) {
        Map<String, String> out = new HashMap<>();
        if (TextUtils.isEmpty(headerJson)) return out;
        String json = headerJson.trim();
        if (json.isEmpty() || "{}".equals(json)) return out;
        if (json.indexOf('\'') >= 0) json = json.replace('\'', '"');
        try {
            Map<String, String> parsed = new Gson().fromJson(
                    json, new TypeToken<Map<String, String>>() {}.getType());
            if (parsed != null) out.putAll(parsed);
        } catch (Throwable t) {
            LogBridge.w(TAG, "header 解析失败：" + abbreviate(json));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 对应 JS {@code getMD5(str)} */
    @JavascriptInterface
    public String getMD5(String input) {
        return md5Hex(input);
    }

    /** 对应 native_layer.js 里被覆盖的 {@code console.log} */
    @JavascriptInterface
    public void setLog(String content) {
        if (content != null) LogBridge.d(TAG, "[JS] " + content);
    }

    /** 对应 JS {@code getVersion()} —— 返回应用版本名 */
    @JavascriptInterface
    public String getVersion() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    @JavascriptInterface
    public String getPlatform() {
        return "android";
    }

    @JavascriptInterface
    public String getSysVersion() {
        return Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
    }

    @JavascriptInterface
    public String getPackageName() {
        return appContext.getPackageName();
    }

    @JavascriptInterface
    public String getDeviceModel() {
        return Build.MODEL == null ? "" : Build.MODEL;
    }

    @JavascriptInterface
    public String getDeviceId() {
        try {
            String id = Build.FINGERPRINT;
            return TextUtils.isEmpty(id) ? "none" : id;
        } catch (Throwable t) {
            return "none";
        }
    }

    @JavascriptInterface
    @SuppressLint("WifiManagerPotentialLeak")
    public String getIPAddress() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getConnectionInfo() == null) return "";
            int ip = wm.getConnectionInfo().getIpAddress();
            return String.format(Locale.US, "%d.%d.%d.%d",
                    ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
        } catch (Throwable t) {
            return "";
        }
    }

    @JavascriptInterface
    @SuppressLint("WifiManagerPotentialLeak")
    public boolean getWifiStatus() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 云图 id —— 本工程无此概念，返回空串走 JS 兜底分支 */
    @JavascriptInterface
    public String getYuntvId() {
        return "";
    }

    /** 用户信息 —— 无登录态，返回空对象 */
    @JavascriptInterface
    public String getUserProfile() {
        return "{}";
    }

    // ------------------------------------------------------------------
    // 以下方法咪咕链路不涉及，做安全兜底以免 JS 中断
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String getUtdid() {
        return getDeviceId();
    }

    /** 写文件到应用私有目录（热更/缓存用） */
    @JavascriptInterface
    public String writeFile(String content, String fileName) {
        return JsPluginStore.writeLocalFile(appContext, fileName, content) ? "1" : "0";
    }

    @JavascriptInterface
    public String readFileToHex(String fileName) {
        return "";
    }

    /** 屏蔽配置 —— 本工程无此机制，返回空配置 */
    @JavascriptInterface
    public String getBlockConfs(String channelId) {
        return "";
    }

    @JavascriptInterface
    public String getBlockBackConfs(String channelId) {
        return "";
    }

    @JavascriptInterface
    public String getLocation(String path) {
        return "";
    }

    @JavascriptInterface
    public String AESDecrypt128(String content, String key) {
        return "";
    }

    @JavascriptInterface
    public String aes(int mode, String content, String key, String cipherType) {
        return "";
    }

    @JavascriptInterface
    public String encC20Ep(String content, String key) {
        return "";
    }

    @JavascriptInterface
    public String getBstFk(String content, String key) {
        return "";
    }

    // ------------------------------------------------------------------

    private static String md5Hex(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String brief(Throwable t) {
        return t == null ? "null" : (t.getClass().getSimpleName() + " " + t.getMessage());
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 90 ? s : s.substring(0, 90) + "...";
    }
}
