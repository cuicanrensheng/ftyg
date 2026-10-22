package com.tv.live.nbmovie;

import android.content.Context;
import android.content.SharedPreferences;

import com.tv.live.util.LogBridge;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【2026-10-21 内置源2 接入 4K影视（NbMovie）】接口编排 + 会话持久化。
 *
 * <p>会话模型（静态逆向 {@code network/B1.java} + {@code R1.a}）：
 * <ol>
 *   <li>GET {@code /app/v1/config}（明文）→ {@code data.domain} 换业务域；</li>
 *   <li>{@link NbMovieCrypto#generateKeyPair()} → "pubB64|sigB64|ts"；</li>
 *   <li>POST {@code /app/v1/exchange}（明文 JSON）→ {@code payload}：
 *       优先 {@code data.server_public_key}+{@code data.session_id}，
 *       回退根级两字段；{@code code==200} 才算成功；</li>
 *   <li>{@link NbMovieCrypto#computeSharedSecret(String)} 返回空串 = 成功，
 *       写 SP（session_id + now+24h）。</li>
 * </ol>
 *
 * <p>SO 的共享密钥仅进程内持有：重启后 {@code sessionReady()} 为 false，
 * 必须重新走 generateKeyPair→exchange→computeSharedSecret；SP 只存
 * session_id / 过期时间 / 域名，不作为会话可用依据。
 *
 * <p>合规：所有网络请求由调用方串行发起，本类不建线程池、不并行、不自动重试；
 * 任一步失败仅打 warn 返回，不清空已有会话，下次调用自然重试。
 */
public final class NbMovieApi {

    private static final String TAG = "NbMovieApi";

    private static final String SP_NAME = "nbmovie_session";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_SESSION_EXPIRE = "session_expire_time";
    private static final String KEY_DOMAIN = "domain";

    /** 默认入口域名（config 下发前的兜底，逆向源码 C0598a 常量） */
    private static final String ENTRY_DOMAIN = "https://4kvm.appleapic.com";
    private static final long SESSION_TTL_MS = 24L * 60L * 60L * 1000L;

    /** 业务成功码（联调若发现其它成功码再调整） */
    private static final int CODE_OK_A = 0;
    private static final int CODE_OK_B = 200;

    private static volatile Context sCtx;
    private static volatile String sBaseUrl = ENTRY_DOMAIN;
    private static volatile String sSessionId = "";

    private NbMovieApi() {
    }

    /** 仅供 SP / 日志初始化，幂等 */
    public static void attach(Context ctx) {
        if (ctx == null) return;
        Context c = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sCtx = c;
        SharedPreferences sp = c.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String domain = sp.getString(KEY_DOMAIN, "");
        if (domain != null && !domain.isEmpty()) {
            sBaseUrl = trimSlash(domain);
        }
        String sid = sp.getString(KEY_SESSION_ID, "");
        sSessionId = sid == null ? "" : sid;
        LogBridge.i(TAG, "attach 完成 baseUrl=" + sBaseUrl + " session_id="
                + (sSessionId.isEmpty() ? "(空)" : sSessionId));
    }

    /** 当前业务基地址（SP domain 优先，回退入口域名） */
    public static String getBaseUrl() {
        String base = sBaseUrl;
        return (base == null || base.isEmpty()) ? ENTRY_DOMAIN : base;
    }

    /** 最近一次交换得到的 session_id（未交换为空串） */
    public static String getSessionId() {
        return sSessionId == null ? "" : sSessionId;
    }

    /**
     * 确保加密会话可用。
     *
     * @return true = 会话就绪（原本就绪或本次新建成功）；false = 不可用（已打 warn）
     */
    public static synchronized boolean ensureSession() {
        if (NbMovieCrypto.sessionReady() && !spSessionExpired() && !getSessionId().isEmpty()) {
            return true;
        }
        if (!NbMovieCrypto.isAvailable()) {
            LogBridge.w(TAG, "加密库不可用，无法建立会话");
            return false;
        }

        // 1. config 换域（失败不阻塞，继续用当前域换密钥）
        String cfg = NbMovieHttp.call("GET", "/app/v1/config", null);
        if (cfg != null) {
            String domain = pickDomain(cfg);
            if (domain != null && !domain.equals(getBaseUrl())) {
                sBaseUrl = domain;
                sp().edit().putString(KEY_DOMAIN, domain).apply();
                LogBridge.i(TAG, "config 换域: " + domain);
            }
        } else {
            LogBridge.w(TAG, "config 请求失败，沿用当前域 " + getBaseUrl());
        }

        // 2. 生成本机密钥对
        String kp = NbMovieCrypto.generateKeyPair();
        if (kp == null) {
            LogBridge.w(TAG, "generateKeyPair 失败");
            return false;
        }
        String[] parts = kp.split("\\|");
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            LogBridge.w(TAG, "generateKeyPair 返回格式异常: " + kp);
            return false;
        }
        String clientPub = parts[0];
        String signature = parts[1];
        long timestamp;
        try {
            timestamp = Long.parseLong(parts[2].trim());
        } catch (NumberFormatException e) {
            LogBridge.w(TAG, "generateKeyPair 时间戳非数字: " + parts[2]);
            return false;
        }

        // 3. exchange（明文 JSON POST）
        JSONObject body = new JSONObject();
        try {
            body.put("client_public_key", clientPub);
            body.put("signature", signature);
            body.put("timestamp", timestamp);
        } catch (Throwable t) {
            LogBridge.w(TAG, "exchange 请求体构造失败: " + t.getMessage());
            return false;
        }
        String resp = NbMovieHttp.postJson("/app/v1/exchange", body.toString());
        if (resp == null) {
            LogBridge.w(TAG, "exchange 请求失败");
            return false;
        }
        String[] payload = parseExchange(resp);
        if (payload == null) {
            LogBridge.w(TAG, "exchange 响应解析失败");
            return false;
        }

        // 4. 派生共享密钥（空串 = 成功）
        String err = NbMovieCrypto.computeSharedSecret(payload[0]);
        if (!err.isEmpty()) {
            LogBridge.w(TAG, "computeSharedSecret 失败: " + err);
            return false;
        }
        sSessionId = payload[1];
        sp().edit()
                .putString(KEY_SESSION_ID, payload[1])
                .putLong(KEY_SESSION_EXPIRE, System.currentTimeMillis() + SESSION_TTL_MS)
                .apply();
        LogBridge.i(TAG, "会话建立成功 session_id=" + payload[1]);
        return true;
    }

    // ------------------------------------------------------------------ 业务接口

    /** GET /app/v1/home → data（含 home_modules） */
    public static JSONObject home() {
        return dataOf(NbMovieHttp.call("GET", "/app/v1/home", null), "home");
    }

    /** GET /app/v1/movie-page → data（含 movie_modules） */
    public static JSONObject moviePage() {
        return dataOf(NbMovieHttp.call("GET", "/app/v1/movie-page", null), "moviePage");
    }

    /** GET /app/v1/tv-page → data（含 tv_modules） */
    public static JSONObject tvPage() {
        return dataOf(NbMovieHttp.call("GET", "/app/v1/tv-page", null), "tvPage");
    }

    /** GET /app/v1/anime-page → data（含 anime_modules） */
    public static JSONObject animePage() {
        return dataOf(NbMovieHttp.call("GET", "/app/v1/anime-page", null), "animePage");
    }

    /** GET /app/v1/movie/{vodId} → data.vod_info（含 play_urls） */
    public static JSONObject detail(int vodId) {
        if (vodId <= 0) {
            LogBridge.w(TAG, "detail vodId 非法: " + vodId);
            return null;
        }
        JSONObject data = dataOf(NbMovieHttp.call("GET", "/app/v1/movie/" + vodId, null), "detail");
        if (data == null) return null;
        JSONObject vod = data.optJSONObject("vod_info");
        return vod != null ? vod : data;
    }

    /**
     * GET /app/v1/play/{pid}?vid={vodId}&nid={epNum}&ep={epNum}&q={quality}&user_id=0
     * → data（含 quality_urls / current_quality）
     */
    public static JSONObject play(int pid, int vodId, int epNum, String quality) {
        if (pid <= 0 || vodId <= 0) {
            LogBridge.w(TAG, "play 参数非法 pid=" + pid + " vodId=" + vodId);
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vid", String.valueOf(vodId));
        params.put("nid", String.valueOf(epNum));
        params.put("ep", String.valueOf(epNum));
        params.put("q", quality == null || quality.isEmpty() ? "1080" : quality);
        params.put("user_id", "0");
        return dataOf(NbMovieHttp.call("GET", "/app/v1/play/" + pid, params), "play");
    }

    // ------------------------------------------------------------------ 内部

    /** 解信封：code==0/200 取 data */
    private static JSONObject dataOf(String resp, String tag) {
        if (resp == null || resp.isEmpty()) {
            LogBridge.w(TAG, tag + " 响应为空");
            return null;
        }
        try {
            JSONObject root = new JSONObject(resp);
            int code = root.optInt("code", -1);
            if (code != CODE_OK_A && code != CODE_OK_B) {
                LogBridge.w(TAG, tag + " 业务失败 code=" + code
                        + " msg=" + root.optString("message"));
                return null;
            }
            return root.optJSONObject("data");
        } catch (Throwable t) {
            LogBridge.w(TAG, tag + " 响应 JSON 解析失败: " + t.getMessage());
            return null;
        }
    }

    /** 从 config 响应取域名：data.domain 优先，回退根级 domain */
    private static String pickDomain(String resp) {
        try {
            JSONObject root = new JSONObject(resp);
            int code = root.optInt("code", -1);
            if (code != CODE_OK_A && code != CODE_OK_B) {
                return null;
            }
            JSONObject data = root.optJSONObject("data");
            String domain = data != null ? data.optString("domain", null) : null;
            if (domain == null || domain.isEmpty()) {
                domain = root.optString("domain", null);
            }
            if (domain == null || domain.isEmpty()) {
                return null;
            }
            if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                domain = "https://" + domain;
            }
            return trimSlash(domain);
        } catch (Throwable t) {
            LogBridge.w(TAG, "config 解析失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 解 exchange 响应（对照 KeyExchangeResponse.payload()）：
     * 优先 data.{server_public_key, session_id}，回退根级两字段。
     *
     * @return {serverPublicKey, sessionId}；失败返回 null
     */
    private static String[] parseExchange(String resp) {
        try {
            JSONObject root = new JSONObject(resp);
            if (root.optInt("code", -1) != CODE_OK_B) {
                LogBridge.w(TAG, "exchange code=" + root.optInt("code", -1)
                        + " msg=" + root.optString("message"));
                return null;
            }
            JSONObject data = root.optJSONObject("data");
            String pub = data != null ? data.optString("server_public_key", null) : null;
            String sid = data != null ? data.optString("session_id", null) : null;
            if (pub == null || pub.isEmpty() || sid == null || sid.isEmpty()) {
                String rp = root.optString("server_public_key", null);
                String rs = root.optString("session_id", null);
                if (rp != null && !rp.isEmpty() && rs != null && !rs.isEmpty()) {
                    pub = rp;
                    sid = rs;
                }
            }
            if (pub == null || pub.isEmpty() || sid == null || sid.isEmpty()) {
                return null;
            }
            return new String[]{pub, sid};
        } catch (Throwable t) {
            LogBridge.w(TAG, "exchange 响应 JSON 解析失败: " + t.getMessage());
            return null;
        }
    }

    private static boolean spSessionExpired() {
        long expire = sp().getLong(KEY_SESSION_EXPIRE, 0L);
        return expire <= System.currentTimeMillis();
    }

    private static SharedPreferences sp() {
        Context c = sCtx;
        if (c == null) {
            throw new IllegalStateException("NbMovieApi.attach 未调用");
        }
        return c.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String v = s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
