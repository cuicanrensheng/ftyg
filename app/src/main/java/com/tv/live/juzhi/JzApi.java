package com.tv.live.juzhi;

import android.text.TextUtils;

import com.tv.live.util.LogBridge;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 橘汁加密 API 客户端。
 *
 * <p>完整链路（对照原版 com.exxammpea.a1 v3.0.2.6）：
 * <ol>
 *   <li><b>域名引导</b>：GET OSS 池 {@code tv1..tv5.txt}，取 {@code enabled=true} 的 domain；</li>
 *   <li><b>密钥协商</b>：POST {@code /api/v5/find/app/zone}（RSARequest）
 *       → 服务端返回 RSAPublic（5 段）→ 按 fakePos=3 拼成 secondLevelPublic；</li>
 *   <li><b>业务请求</b>：POST {@code /api/proto/v5/*}，body 为 SecureRequest；
 *       请求头 {@code publicParams} 携带 5 个签名字段。</li>
 * </ol>
 *
 * <p>三处曾踩过的坑，已固化在实现里：
 * <ul>
 *   <li>业务包不能发到 {@code /api/v5/find/app/zone}（那是 RSARequest 专用端点，
 *       服务端按 RSARequest 解析会把 aesEncrypt1 的长度当成时间戳 → 100001 请求时间戳过期）；</li>
 *   <li>RSARequest 字段序是 {@code 1=timestamp, 2=sign, 3=fake1, 4=randomStr, 5=fake2}；</li>
 *   <li>Base64 必须带 '=' 填充（原版 Base64.NO_WRAP），否则 sig2/sig3 对不上 → 100009。</li>
 * </ul>
 */
public final class JzApi {

    private static final String TAG = "JzApi";

    private static final long CONNECT_MS = 8000L;
    private static final long READ_MS = 12000L;
    private static final long CALL_MS = 15000L;
    private static final long BASE_URL_TTL_MS = 10L * 60L * 1000L;

    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36";

    private static volatile String sBaseUrl = "";
    private static volatile long sBaseUrlAt = 0L;
    private static volatile boolean sNegotiationTried = false;

    private static volatile OkHttpClient sHttp;

    /**
     * 【2026-10-23 测速选线】探针专用短超时客户端。
     * 主客户端 CALL_MS=15s：死线路的 videoUsableUrl 会挂满 15s，把测速选线
     * （11 条候选）拖到 20s+。探针用 2.5s/2.5s/3s，死线路快速失败。
     */
    private static volatile OkHttpClient sHttpFast;

    private static final long FAST_CONNECT_MS = 2500L;
    private static final long FAST_READ_MS = 2500L;
    private static final long FAST_CALL_MS = 3000L;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private JzApi() {
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private static OkHttpClient http() {
        OkHttpClient c = sHttp;
        if (c != null) return c;
        synchronized (JzApi.class) {
            if (sHttp == null) {
                sHttp = new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(READ_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(CALL_MS, TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(true)
                        .connectionPool(new okhttp3.ConnectionPool(4, 5, TimeUnit.MINUTES))
                        .proxy(java.net.Proxy.NO_PROXY)
                        .build();
            }
            return sHttp;
        }
    }

    /** 探针专用短超时客户端（测速选线用，死线路 3s 内失败） */
    private static OkHttpClient httpFast() {
        OkHttpClient c = sHttpFast;
        if (c != null) return c;
        synchronized (JzApi.class) {
            if (sHttpFast == null) {
                sHttpFast = new OkHttpClient.Builder()
                        .connectTimeout(FAST_CONNECT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(FAST_READ_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(FAST_CALL_MS, TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(true)
                        .connectionPool(new okhttp3.ConnectionPool(4, 5, TimeUnit.MINUTES))
                        .proxy(java.net.Proxy.NO_PROXY)
                        .build();
            }
            return sHttpFast;
        }
    }

    /** 业务 baseUrl（OSS 池引导，10 分钟缓存） */
    public static String baseUrl() {
        long now = System.currentTimeMillis();
        if (!TextUtils.isEmpty(sBaseUrl) && now - sBaseUrlAt < BASE_URL_TTL_MS) {
            return sBaseUrl;
        }
        String fetched = fetchBaseUrlFromOss();
        if (!TextUtils.isEmpty(fetched)) {
            sBaseUrl = fetched;
            sBaseUrlAt = now;
            LogBridge.i(TAG, "baseUrl 刷新 → " + fetched);
        } else if (TextUtils.isEmpty(sBaseUrl)) {
            sBaseUrl = JzProtocol.fallbackBaseUrl();
            LogBridge.w(TAG, "OSS 不可达，用兜底 baseUrl = " + JzProtocol.fallbackBaseUrl());
        } else {
            LogBridge.w(TAG, "OSS 刷新失败，沿用 " + sBaseUrl);
        }
        return sBaseUrl;
    }

    private static String fetchBaseUrlFromOss() {
        for (String url : JzProtocol.ossUrls()) {
            try {
                Request req = new Request.Builder().url(url).header("User-Agent", UA).get().build();
                try (Response resp = http().newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        LogBridge.w(TAG, "OSS 失败 code=" + resp.code() + " " + url);
                        continue;
                    }
                    JSONObject jo = new JSONObject(resp.body().string().trim());
                    String domain = jo.optString("domain", "").trim();
                    if (jo.optBoolean("enabled", false) && !TextUtils.isEmpty(domain)) {
                        LogBridge.i(TAG, "OSS 命中 " + domain);
                        return domain;
                    }
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "OSS 异常 " + t.getClass().getSimpleName() + " " + url);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 密钥协商（RSARequest）
    // ------------------------------------------------------------------

    /**
     * 向服务端协商 secondLevelPublic（进程内只需一次）。
     * 失败时沿用 {@link JzProtocol} 的兜底公钥，不阻塞业务请求。
     */
    public static synchronized boolean negotiate() {
        if (JzProtocol.isNegotiated()) return true;
        if (sNegotiationTried) return false;
        sNegotiationTried = true;

        try {
            long ts = System.currentTimeMillis();
            String randomStr = JzProtocol.token(16);
            String sign = JzProtocol.rsaEncryptBase64(JzProtocol.firstLevelPublic(), ts + randomStr);
            String fake1 = JzProtocol.token(16);
            String fake2 = JzProtocol.token(16);

            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            JzProtocol.writeVarintField(out, 1, ts);        // 1: timestamp
            JzProtocol.writeStringField(out, 2, sign);       // 2: sign
            JzProtocol.writeStringField(out, 3, fake1);      // 3: fake1
            JzProtocol.writeStringField(out, 4, randomStr);  // 4: randomStr
            JzProtocol.writeStringField(out, 5, fake2);      // 5: fake2

            Request req = new Request.Builder()
                    .url(baseUrl() + JzProtocol.pathRsaZone())
                    .header("User-Agent", UA)
                    .header("Accept", "application/x-protobuf")
                    .header("Content-Type", "application/x-protobuf")
                    .header("Cache-Control", "no-cache")
                    // 协商阶段无 secondLevelPublic → 原版签名字段段整体缺失
                    .header("publicParams", buildPublicParams(false))
                    .post(RequestBody.create(MediaType.parse("application/x-protobuf"), out.toByteArray()))
                    .build();

            try (Response resp = http().newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    LogBridge.w(TAG, "RSARequest HTTP " + resp.code());
                    return false;
                }
                byte[] raw = resp.body().bytes();
                ApiResult r = ApiResult.parse(raw);
                LogBridge.i(TAG, "RSARequest code=" + r.code + " msg=" + r.msg
                        + " data_len=" + (r.data == null ? 0 : r.data.length));
                if (r.code != 200 || r.data == null) return false;

                // RSAPublic: 1=str1 2=str2 3=str3 4=str4 5=str5；fakePos=3 → 1+2+4+5
                String s1 = JzProtocol.str(r.data, 1);
                String s2 = JzProtocol.str(r.data, 2);
                String s4 = JzProtocol.str(r.data, 4);
                String s5 = JzProtocol.str(r.data, 5);
                String key = s1 + s2 + s4 + s5;
                if (TextUtils.isEmpty(key)) {
                    LogBridge.w(TAG, "RSAPublic 为空");
                    return false;
                }
                JzProtocol.setSecondLevelPublic(key);
                LogBridge.i(TAG, "secondLevelPublic 协商成功 len=" + key.length());
                return true;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "协商异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 业务请求
    // ------------------------------------------------------------------

    /**
     * 发加密 protobuf 业务请求。
     *
     * @param path   业务路径（{@link JzProtocol#PATH_SEARCH} 等）
     * @param params 业务参数（按 Map 迭代顺序拼 {@code k=v&k=v}）
     * @return ApiResult.data（业务 protobuf）；失败返回 null
     */
    public static byte[] post(String path, Map<String, String> params) {
        return post(path, params, false);
    }

    /**
     * 【2026-10-23 测速选线】探针专用：同 {@link #post}，但走短超时客户端
     * （{@link #httpFast()}）——死线路的 videoUsableUrl 3s 内失败，不拖垮整体测速。
     */
    public static byte[] postFast(String path, Map<String, String> params) {
        return post(path, params, true);
    }

    private static byte[] post(String path, Map<String, String> params, boolean fast) {
        try {
            negotiate();

            StringBuilder paramStr = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (paramStr.length() > 0) paramStr.append('&');
                paramStr.append(e.getKey()).append('=').append(e.getValue());
            }

            long ts = System.currentTimeMillis();
            String random8 = JzProtocol.token(8);
            String fakeStr = JzProtocol.token(20);

            // 明文 = 参数串 + 时间戳；AES(safeCode) → Base64（带填充）
            String aes = JzProtocol.aesEncryptBase64(JzProtocol.safeCode(), paramStr + String.valueOf(ts));
            String combined = random8 + aes;
            String enc1 = combined.substring(0, Math.min(20, combined.length()));
            String enc2 = combined.length() > 20 ? combined.substring(20) : "";

            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            JzProtocol.writeStringField(out, 1, enc1);       // aesEncrypt1
            JzProtocol.writeStringField(out, 2, enc2);       // aesEncrypt2
            JzProtocol.writeStringField(out, 3, fakeStr);    // aesFakestr
            JzProtocol.writeVarintField(out, 4, ts);         // timestamp
            JzProtocol.writeStringField(out, 5, random8);    // randomStr

            Request req = new Request.Builder()
                    .url(baseUrl() + path)
                    .header("User-Agent", UA)
                    .header("Accept", "application/x-protobuf")
                    .header("Content-Type", "application/x-protobuf")
                    .header("Cache-Control", "no-cache")
                    .header("publicParams", buildPublicParams(true))
                    .post(RequestBody.create(MediaType.parse("application/x-protobuf"), out.toByteArray()))
                    .build();

            try (Response resp = (fast ? httpFast() : http()).newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    LogBridge.w(TAG, "POST " + path + " HTTP " + resp.code());
                    return null;
                }
                ApiResult r = ApiResult.parse(resp.body().bytes());
                if (r.code != 200) {
                    LogBridge.w(TAG, "POST " + path + " code=" + r.code + " msg=" + r.msg);
                    return null;
                }
                return r.data;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "POST " + path + " 异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // GET + query 双重加密通道（列表类接口，如 /api/ex/v3/security/drama/list）
    // ------------------------------------------------------------------

    /**
     * 请求参数双重加密：{@code Base64(AES(safeCode, Base64(AES(safeSecretKey, inner))))}。
     *
     * <p>对应原版 {@code ResponseDecodeManager.encodeQuery()} —— 只用于 security 类接口，
     * 作为 GET 的 {@code ?query=} 值传入。
     */
    public static String encryptQuery(String inner) throws Exception {
        String layer1 = JzProtocol.aesEncryptBase64(JzProtocol.safeSecretKey(), inner);
        return JzProtocol.aesEncryptBase64(JzProtocol.safeCode(), layer1);
    }

    /**
     * 响应解密：{@code data} 是 Base64 串，先 AES(safeCode) 解出一层 Base64，
     * 再 AES(safeSecretKey) 解出明文 JSON。
     *
     * <p>对应原版 {@code ResponseDecodeManager.decodeResponseData()}：
     * {@code AESUtl.b(safeSecretKey, AESUtl.b(safeCode, str))} —— 两次 b() 都含 Base64 解码。
     */
    public static String decryptPayload(String b64) throws Exception {
        String layer1 = JzProtocol.aesDecryptBase64(JzProtocol.safeCode(), b64);
        return JzProtocol.aesDecryptBase64(JzProtocol.safeSecretKey(), layer1);
    }

    /**
     * GET 加密接口，返回解密后的明文 JSON。
     *
     * @param path       接口路径（如 {@code /api/ex/v3/security/drama/list}）
     * @param innerParams 内层参数串（如 {@code page=1&pagesize=50&typeId1=25}）
     * @return 明文 JSON；失败返回 null
     */
    public static String getJson(String path, String innerParams) {
        try {
            negotiate();
            String url = baseUrl() + path + "?query=" + uriEncode(encryptQuery(innerParams));
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .header("publicParams", buildPublicParams(true))
                    .get()
                    .build();
            try (Response resp = http().newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    LogBridge.w(TAG, "GET " + path + " HTTP " + resp.code());
                    return null;
                }
                String body = resp.body().string();
                JSONObject jo = new JSONObject(body);
                int code = jo.optInt("code", -1);
                String data = jo.optString("data", "");
                if (code != 200 || TextUtils.isEmpty(data)) {
                    LogBridge.w(TAG, "GET " + path + " code=" + code + " msg=" + jo.optString("msg"));
                    return null;
                }
                String plain = decryptPayload(data);
                LogBridge.i(TAG, "GET " + path + " ok, 明文 " + plain.length() + "B");
                return plain;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET " + path + " 异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // publicParams 请求头
    // ------------------------------------------------------------------

    /**
     * 构造 {@code publicParams} 头（Gson 序列化的等价物）。
     *
     * <p>编码口径必须与原版一致：<b>基础字段 Uri.encode，动态字段裸值</b>。
     * 动态字段（timestamp/random_str/sig/sig2/sig3）在原版里是编码循环之后才写入的，
     * 若也做 URL 编码（{@code +}→{@code %2B}、{@code =}→{@code %3D}），
     * 服务端按裸值比对必然不一致。
     *
     * @param withSignature 是否附带签名字段（RSARequest 阶段传 false）
     */
    private static String buildPublicParams(boolean withSignature) {
        long ts = System.currentTimeMillis();
        StringBuilder j = new StringBuilder(512);
        j.append('{');
        appendEnc(j, "plat", "tv");
        appendEnc(j, "vOs", android.os.Build.VERSION.RELEASE);
        appendEnc(j, "_vOsCode", String.valueOf(android.os.Build.VERSION.SDK_INT));
        appendEnc(j, "vApp", JzProtocol.vApp());
        appendEnc(j, "vName", JzProtocol.vName());
        appendEnc(j, "pkg", JzProtocol.pkg());
        appendEnc(j, "appName", JzProtocol.appName());
        appendEnc(j, "user_mac", "02:00:00:00:00:00");
        appendEnc(j, "model", android.os.Build.MODEL);
        appendEnc(j, "brand", android.os.Build.BRAND);
        appendEnc(j, "facturer", android.os.Build.MANUFACTURER);
        appendEnc(j, "udid", udid());
        appendEnc(j, "uuid", "00000000-0000-0000-0000-000000000000");
        appendEnc(j, "chid", "official");
        appendEnc(j, "androidID", androidId());
        appendEnc(j, "resolution", "1920*1080");
        appendEnc(j, "density", "2.0");
        appendEnc(j, "dpi", "320");
        appendEnc(j, "net", "wifi");
        appendEnc(j, "carrier", "");
        appendEnc(j, "cpu", firstAbi());
        appendEnc(j, "abid", firstAbi());
        appendEnc(j, "cpuId", "0");
        j.append("\"device\":1,");
        appendEnc(j, "lang", "zh");
        appendEnc(j, "country", "CN");
        j.append("\"test_timestamp\":").append(ts).append(',');
        j.append("\"young\":0");

        if (withSignature) {
            String randomStr16 = JzProtocol.token(16);
            String sig2 = "";
            String sig3 = "";
            String sig = "";
            try {
                String aesSig = JzProtocol.aesEncryptBase64(JzProtocol.safeSecretKey(), ts + randomStr16);
                sig2 = aesSig.length() >= 8 ? aesSig.substring(0, 8) : "";
                sig3 = aesSig.length() > 8 ? aesSig.substring(8) : "";
            } catch (Throwable t) {
                LogBridge.w(TAG, "sig2/sig3 失败 " + t.getMessage());
            }
            try {
                sig = JzProtocol.rsaEncryptBase64(JzProtocol.secondLevelPublic(),
                        ts + randomStr16 + JzProtocol.vApp());
            } catch (Throwable t) {
                LogBridge.w(TAG, "sig 失败 " + t.getMessage());
            }
            j.append(',');
            j.append("\"timestamp\":").append(ts).append(',');
            appendRaw(j, "random_str", randomStr16);
            j.append(',');
            appendRaw(j, "sig", sig);
            j.append(',');
            appendRaw(j, "sig2", sig2);
            j.append(',');
            appendRaw(j, "sig3", sig3);
        }
        j.append('}');
        return j.toString();
    }

    private static void appendEnc(StringBuilder j, String key, String value) {
        j.append('"').append(key).append("\":\"")
                .append(uriEncode(value == null ? "" : value)).append("\",");
    }

    private static void appendRaw(StringBuilder j, String key, String value) {
        j.append('"').append(key).append("\":\"")
                .append(value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append('"');
    }

    /** 等价 android.net.Uri.encode（保留 A-Z a-z 0-9 - _ . ! ~ * ' ( )） */
    private static String uriEncode(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!'
                    || c == '~' || c == '*' || c == '\'' || c == '(' || c == ')';
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    private static String firstAbi() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        return (abis != null && abis.length > 0) ? abis[0] : "arm64-v8a";
    }

    private static volatile String sUdid;
    private static volatile String sAndroidId;

    /** 伪 udid（统计字段，进程内稳定即可） */
    private static String udid() {
        String v = sUdid;
        if (v == null) {
            v = randomHex(16);
            sUdid = v;
        }
        return v;
    }

    /** 伪 androidID（统计字段） */
    private static String androidId() {
        String v = sAndroidId;
        if (v == null) {
            v = randomHex(16);
            sAndroidId = v;
        }
        return v;
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < len; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // ApiResult 信封
    // ------------------------------------------------------------------

    /** ApiResult：1=code(int32) 2=msg(string) 3=data(bytes) 4=fromCache(bool) */
    static final class ApiResult {
        int code;
        String msg = "";
        byte[] data;

        static ApiResult parse(byte[] raw) {
            ApiResult r = new ApiResult();
            if (raw == null || raw.length == 0) return r;
            long code = JzProtocol.num(raw, 1, 0);
            r.code = (int) code;
            r.msg = JzProtocol.str(raw, 2);
            List<byte[]> dataList = JzProtocol.messages(raw, 3);
            if (!dataList.isEmpty()) r.data = dataList.get(0);
            return r;
        }
    }
}
