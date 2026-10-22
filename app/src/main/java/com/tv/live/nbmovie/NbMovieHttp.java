package com.tv.live.nbmovie;

import com.tv.live.util.LogBridge;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 【2026-10-21 内置源2 接入 4K影视（NbMovie）】加密 HTTP 通道。
 *
 * <p>协议静态逆向自 4K影视（{@code network/E2.java} Ktor 加密插件 + {@code network/L2.java} 响应解密），
 * 加密算法全部下沉 {@link NbMovieCrypto}（libtvlive_security.so），本类只负责组帧与收发：
 * <ul>
 *   <li>帧格式（大端）：{@code short 20045}(0x4E4D) + version(1) + flags(0) + sid_len(1B) + session_id + {@code encryptRaw(明文)}；</li>
 *   <li>加密请求：GET/HEAD/DELETE 改 POST + 头 {@code X-NB-Method: <原方法>}；清空 URL query；
 *       {@code Content-Type: application/octet-stream}；明文 = URL 参数的 JSON（无参数 {@code "{}"}）；</li>
 *   <li>白名单（不加密，endWith 判定）：{@code /app/v1/config}、{@code /app/v1/version}、{@code /app/v1/exchange}；</li>
 *   <li>响应解密：body 头两字节 {@code 'N','M'} → 拆帧取密文 → {@code decryptRaw}；否则按明文处理。</li>
 * </ul>
 *
 * <p>合规：所有请求由调用方**串行**发起，本类不建线程池、不并行、不自动重试；失败一律返回 null 并打 warn。
 */
public final class NbMovieHttp {

    private static final String TAG = "NbMovieHttp";

    /** NM 加密帧标识：大端 short 20045（= 'N' 0x4E, 'M' 0x4D） */
    private static final int FRAME_MAGIC = 20045;
    private static final byte FRAME_VERSION = 1;
    private static final byte FRAME_FLAGS = 0;

    static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 明文白名单后缀（与逆向源码 E2.java 的三契约一致） */
    private static final String[] PLAIN_PATH_SUFFIX = {
            "/app/v1/config", "/app/v1/version", "/app/v1/exchange",
    };

    private static final long CONNECT_MS = 8000L;
    private static final long READ_MS = 15000L;
    private static final long CALL_MS = 20000L;

    private static volatile OkHttpClient sHttp;

    private NbMovieHttp() {
    }

    static OkHttpClient client() {
        OkHttpClient c = sHttp;
        if (c != null) return c;
        synchronized (NbMovieHttp.class) {
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

    /** 是否明文白名单路径（endWith 判定） */
    static boolean isPlainPath(String path) {
        if (path == null) return false;
        for (String s : PLAIN_PATH_SUFFIX) {
            if (path.endsWith(s)) return true;
        }
        return false;
    }

    /**
     * 通用业务调用。
     *
     * @param method HTTP 方法（业务侧均为 "GET"）
     * @param path   接口路径，如 {@code /app/v1/home}、{@code /app/v1/movie/123}
     * @param params 查询参数（白名单请求走 URL query；加密请求编为 JSON 明文体）
     * @return 响应明文字符串（调用方解 JSON）；失败返回 null
     */
    static String call(String method, String path, Map<String, String> params) {
        String base = NbMovieApi.getBaseUrl();
        String fullUrl = base + path;
        String m = (method == null || method.isEmpty()) ? "GET" : method;

        if (isPlainPath(path)) {
            return plainCall(m, fullUrl, params);
        }

        // 加密路径：先确保会话（内部会走 config + exchange 两个明文请求）
        if (!NbMovieApi.ensureSession()) {
            LogBridge.w(TAG, "会话不可用，放弃请求 path=" + path);
            return null;
        }
        String sid = NbMovieApi.getSessionId();
        if (sid == null || sid.isEmpty()) {
            LogBridge.w(TAG, "session_id 为空，放弃请求 path=" + path);
            return null;
        }

        // 明文体：URL 参数的 JSON（无参数时 "{}"）
        String plain;
        if (params == null || params.isEmpty()) {
            plain = "{}";
        } else {
            try {
                plain = new JSONObject(params).toString();
            } catch (Throwable t) {
                LogBridge.w(TAG, "参数 JSON 化失败: " + t.getMessage());
                return null;
            }
        }

        byte[] cipher = NbMovieCrypto.encryptRaw(plain.getBytes(StandardCharsets.UTF_8));
        if (cipher == null) {
            LogBridge.w(TAG, "加密失败 path=" + path);
            return null;
        }
        byte[] frame = buildFrame(sid, cipher);
        if (frame == null) {
            return null;
        }

        Request req = new Request.Builder()
                .url(fullUrl)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("X-NB-Method", m)
                .post(RequestBody.create(MediaType.get("application/octet-stream"), frame))
                .build();

        byte[] resp = execute(req);
        if (resp == null) {
            return null;
        }
        return decodeFrame(resp);
    }

    /**
     * 明文 JSON POST（仅 exchange 用：白名单路径，服务端按普通 JSON 收）。
     *
     * @return 响应字符串（调用方解 JSON）；失败返回 null
     */
    static String postJson(String path, String jsonBody) {
        String fullUrl = NbMovieApi.getBaseUrl() + path;
        String body = (jsonBody == null || jsonBody.isEmpty()) ? "{}" : jsonBody;
        Request req = new Request.Builder()
                .url(fullUrl)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .post(RequestBody.create(MediaType.get("application/json"), body))
                .build();
        byte[] resp = execute(req);
        if (resp == null) {
            return null;
        }
        return decodeFrame(resp);
    }

    /** 白名单明文请求：方法保持原样，参数走 URL query */
    private static String plainCall(String method, String fullUrl, Map<String, String> params) {
        HttpUrl parsed = HttpUrl.parse(fullUrl);
        if (parsed == null) {
            LogBridge.w(TAG, "URL 非法: " + fullUrl);
            return null;
        }
        HttpUrl.Builder ub = parsed.newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    ub.addQueryParameter(e.getKey(), e.getValue());
                }
            }
        }
        Request.Builder rb = new Request.Builder()
                .url(ub.build())
                .header("User-Agent", UA)
                .header("Accept", "application/json");
        String m = method.toUpperCase(java.util.Locale.US);
        if ("HEAD".equals(m)) {
            rb.head();
        } else if ("DELETE".equals(m)) {
            rb.delete();
        } else {
            rb.get();
        }
        byte[] resp = execute(rb.build());
        if (resp == null) {
            return null;
        }
        return decodeFrame(resp);
    }

    /**
     * 组 NM 加密帧：{@code magic(2) + version(1) + flags(0) + sid_len(1) + sid + cipher}。
     */
    private static byte[] buildFrame(String sid, byte[] cipher) {
        byte[] sidBytes = sid.getBytes(StandardCharsets.UTF_8);
        if (sidBytes.length == 0 || sidBytes.length > 255) {
            LogBridge.w(TAG, "session_id 长度非法: " + sidBytes.length);
            return null;
        }
        if (cipher.length < 28) {
            LogBridge.w(TAG, "密文过短: " + cipher.length);
            return null;
        }
        ByteBuffer bb = ByteBuffer.allocate(sidBytes.length + 5 + cipher.length)
                .order(ByteOrder.BIG_ENDIAN);
        bb.putShort((short) FRAME_MAGIC);
        bb.put(FRAME_VERSION);
        bb.put(FRAME_FLAGS);
        bb.put((byte) sidBytes.length);
        bb.put(sidBytes);
        bb.put(cipher);
        return bb.array();
    }

    /**
     * 解响应：头两字节 {@code 'N','M'} → 拆帧 + {@code decryptRaw}；否则原样转字符串。
     */
    private static String decodeFrame(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length < 2 || !(bytes[0] == 78 && bytes[1] == 77)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            if (bb.getShort() != (short) FRAME_MAGIC) {
                LogBridge.w(TAG, "响应帧标识无效");
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (bb.get() != FRAME_VERSION) {
                LogBridge.w(TAG, "响应帧版本不支持");
                return null;
            }
            bb.get(); // flags
            int sidLen = bb.get() & 255;
            if (sidLen <= 0) {
                LogBridge.w(TAG, "响应帧缺少会话ID");
                return null;
            }
            if (bytes.length < 5 + sidLen + 28) {
                LogBridge.w(TAG, "响应帧长度无效 len=" + bytes.length);
                return null;
            }
            byte[] sid = new byte[sidLen];
            bb.get(sid);
            byte[] cipher = new byte[bytes.length - bb.position()];
            bb.get(cipher);
            byte[] plain = NbMovieCrypto.decryptRaw(cipher);
            if (plain == null) {
                LogBridge.w(TAG, "响应解密失败 len=" + cipher.length);
                return null;
            }
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LogBridge.w(TAG, "响应解帧异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** 执行请求并读回字节；非 2xx / 异常返回 null */
    private static byte[] execute(Request req) {
        long t0 = System.currentTimeMillis();
        try (Response resp = client().newCall(req).execute()) {
            ResponseBody body = resp.body();
            byte[] bytes = body == null ? null : body.bytes();
            int len = bytes == null ? 0 : bytes.length;
            long cost = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                LogBridge.w(TAG, "HTTP " + resp.code()
                        + " 耗时=" + cost + "ms len=" + len + " url=" + req.url());
                return null;
            }
            LogBridge.i(TAG, "HTTP " + resp.code()
                    + " 耗时=" + cost + "ms len=" + len + " url=" + req.url());
            return bytes;
        } catch (Throwable t) {
            LogBridge.w(TAG, "请求异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage()
                    + " 耗时=" + (System.currentTimeMillis() - t0) + "ms url=" + req.url());
            return null;
        }
    }
}
