package com.tv.live.migu.source;

import android.text.TextUtils;

import com.tv.live.util.LogBridge;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import com.tv.live.security.SecurityCore;
import java.util.Locale;
import java.util.Random;

/**
 * 「追光影界」点播（GITV 银河 VOD）鉴权链解析器。
 *
 * <p><b>解决什么问题</b>：剧场组 / 央视大剧这类轮播频道的节目里
 * {@code /p/get_current_carousel} 的 {@code data[0]} 往往<b>没有</b> {@code videos[]}，
 * 只给一个 {@code videoHtml}：
 * <pre>
 *   videoHtml = "http://zgyj_vod/eyJkYXRhIjogW3siY2RuTmFtZSI6ICJ5aW5oZSIs..."
 *              （base64 = {"data":[{"cdnName":"yinhe","partnerType":"VOD",
 *                          "contentId":"clear/5010/…/index.m3u8","vids":[]}],"size":1}）
 * </pre>
 * 原版把该地址交给 JS 插件 {@code other_live.js::zgyjvodParsePlay}，链路为：
 * <pre>
 *   GET http://hecmcc-gvp-zy-vod.playauth.gitv.tv/gitv_vod_auth/&lt;base64&gt;
 *       ?playAuthToken=&lt;token&gt;&amp;gAreaId=CHN&amp;gAppChannel=DEFAULT&amp;gSoftTermId=SMART_SPEAKER
 *       &amp;gManufacturer=vivo&amp;gModel=V2185A&amp;gMac=&lt;随机MAC&gt;&amp;gStbId=unknown
 *       &amp;gOsType=android&amp;gOsVersion=12&amp;gApiLevel=32&amp;gAppVersionCode=1482
 *       &amp;gAppVersionName=1.4.82&amp;gDowngrade=NONEMERGENCY&amp;gRomVersion=DEFAULT
 *       &amp;gProductLineCode=GVP&amp;gTimestamp=&lt;yyyyMMddHHmmss&gt;&amp;gRequests=1
 *     User-Agent: okhttp/3.8.1
 *   → {"code":"A000000","data":{"playUrl":"http://hegd-vod.ali-cdn.gitv.tv/clear/…/index.m3u8?…"}}
 * </pre>
 * 拿到 {@code data.playUrl} 后即是阿里 CDN 可播地址。
 *
 * <p><b>playAuthToken 算法</b>（1:1 移植，已用抓包逐字节验证）：
 * <pre>
 *   key  = 下方 {@code gitvKey()}（128 字符固定串，原版 JS 内硬编码）
 *   len  = floor(timestamp % (key.length - 19))        // 128-19 = 109
 *   str  = key.substring(len, len + 20)
 *   token= SHA256(str + "86400").toLowerCase() + "_" + timestamp + "_86400"
 * </pre>
 *
 * <p><b>验证依据</b>：{@code qa_dumps/dj2.pcap}（2026-10-07 原版单次实机点击「央视大剧」抓包）：
 * <pre>
 *   timestamp = 1791355577   → len = 91   → str = "UyQprvoEqM2I8Dfe2oX0"
 *   token     = 8efb3dcfc68a8c31ea709ce91c34030c125363fbef39ee0f6d99cd4ca36e2e0a_1791355577_86400
 *   gTimestamp= 20261007144617（设备本地时区 yyyyMMddHHmmss）
 *   响应      = {"message":"ok","data":{"playUrl":"http://hegd-vod.ali-cdn.gitv.tv/…"},"code":"A000000"}
 * </pre>
 *
 * <p><b>红线</b>：单次 GET、不重试、不并发；任何异常返回 {@code null} 交上层降级。
 */
public final class ZgyjVodApi {

    private static final String TAG = "ZgyjVod";

    /** 追光影界 VOD 伪协议前缀（原版 {@code yt_source_root.js} 路由键 {@code http://zgyj_vod/}） */
    private static final String PREFIX_HTTP = "http://zgyj_vod/";
    private static final String PREFIX_HTTPS = "https://zgyj_vod/";

    /** 路径锚点（用于从任意形态里取出 base64 段） */
    private static final String PATH_ANCHOR = "zgyj_vod/";

    /**
     * GITV 鉴权端点前缀（原版 JS 内硬编码）。
     * <p>【2026-10-15 密文化】原为 private static final 明文常量 —— 编译期常量会被内联到
     * 使用点，dex 里可见；改为惰性 getter 后明文只存在于 SO（cpp/secstore.cpp）。
     * ⚠️ 惰性解密铁律：不可写回 static final / {@code <clinit>}。
     */
    private static volatile String sAuthEndpoint;

    private static String authEndpoint() {
        String v = sAuthEndpoint;
        if (v == null) {
            String d = SecurityCore.secureString(SecurityCore.C_API_GITV_AUTH);
            v = sAuthEndpoint = (d == null ? "" : d);
        }
        return v;
    }

    /**
     * 原版 JS 内硬编码的 128 字符 key（<b>值不可改</b>，服务端会校验）。
     * <p>【2026-10-15 密文化】原为 private static final 明文常量，现改为惰性 getter。
     */
    private static volatile String sGitvKey;

    private static String gitvKey() {
        String v = sGitvKey;
        if (v == null) {
            String d = SecurityCore.secureString(SecurityCore.C_API_GITV_KEY);
            v = sGitvKey = (d == null ? "" : d);
        }
        return v;
    }

    /** 原版 JS 固定 UA（服务端校验用，勿改） */
    private static final String UA = "okhttp/3.8.1";

    /** 单次请求超时（连接 / 读取） */
    private static final int TIMEOUT_MS = 6000;

    /** 解析结果 */
    public static final class Result {
        /** 可直接播放的地址（{@code data.playUrl}，阿里 CDN） */
        public final String url;
        /** 本次使用的 playAuthToken（便于日志排查） */
        public final String playAuthToken;
        /** 本次请求时间戳（秒） */
        public final long timestamp;

        Result(String url, String playAuthToken, long timestamp) {
            this.url = url == null ? "" : url;
            this.playAuthToken = playAuthToken == null ? "" : playAuthToken;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "playUrl=" + url;
        }
    }

    private ZgyjVodApi() {}

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 是否为追光影界 VOD 鉴权链地址（{@code http(s)://zgyj_vod/<base64>}） */
    public static boolean isZgyjVodUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String t = url.trim();
        return t.startsWith(PREFIX_HTTP) || t.startsWith(PREFIX_HTTPS);
    }

    /**
     * 解析 {@code http://zgyj_vod/<base64>} → GITV {@code playUrl}。
     *
     * @param zgyjUrl 形如 {@code http://zgyj_vod/eyJkYXRhIjog…}
     * @return 解析结果；失败返回 {@code null}
     */
    public static Result resolve(String zgyjUrl) {
        if (!isZgyjVodUrl(zgyjUrl)) return null;
        String payload = payloadOf(zgyjUrl);
        if (TextUtils.isEmpty(payload)) {
            LogBridge.w(TAG, "zgyj_vod 载荷为空：" + zgyjUrl);
            return null;
        }

        long ts = System.currentTimeMillis() / 1000L;
        String gTimestamp = formatDateTime(ts);
        String token = playAuthToken(ts);
        String mac = randomMac();

        String req = authEndpoint() + payload
                + "?playAuthToken=" + token
                + "&gAreaId=CHN&gAppChannel=DEFAULT&gSoftTermId=SMART_SPEAKER"
                + "&gManufacturer=vivo&gModel=V2185A&gMac=" + mac
                + "&gStbId=unknown&gOsType=android&gOsVersion=12&gApiLevel=32"
                + "&gAppVersionCode=1482&gAppVersionName=1.4.82"
                + "&gDowngrade=NONEMERGENCY&gRomVersion=DEFAULT&gProductLineCode=GVP"
                + "&gTimestamp=" + gTimestamp + "&gRequests=1";

        // 红线：单次 GET、不重试、不并发
        String body = httpGet(req);
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "GITV 鉴权无响应（token=" + abbreviate(token) + "）");
            return null;
        }
        try {
            JSONObject o = new JSONObject(body);
            String code = o.optString("code", "");
            if (!"A000000".equals(code)) {
                LogBridge.w(TAG, "GITV 鉴权失败 code=" + code
                        + " msg=" + o.optString("message", "") + "：" + abbreviate(body));
                return null;
            }
            JSONObject d = o.optJSONObject("data");
            String playUrl = d == null ? "" : d.optString("playUrl", "").trim();
            if (TextUtils.isEmpty(playUrl)) {
                LogBridge.w(TAG, "GITV 鉴权响应无 playUrl：" + abbreviate(body));
                return null;
            }
            Result r = new Result(playUrl, token, ts);
            LogBridge.i(TAG, "GITV 鉴权命中 → " + r);
            return r;
        } catch (Throwable t) {
            LogBridge.w(TAG, "GITV 鉴权响应解析失败："
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 算法（1:1 移植 other_live.js::zgyjvodParsePlay）
    // ------------------------------------------------------------------

    /** 从 {@code http(s)://zgyj_vod/<base64>} 取出 base64 段 */
    private static String payloadOf(String url) {
        String t = url.trim();
        int i = t.indexOf(PATH_ANCHOR);
        if (i < 0) return "";
        return t.substring(i + PATH_ANCHOR.length()).trim();
    }

    /**
     * 计算 {@code playAuthToken}（算法见类注释；已用抓包逐字节验证）。
     *
     * @param tsSec 当前 Unix 秒
     */
    static String playAuthToken(long tsSec) {
        final String key = gitvKey();
        if (key.length() < 20) return "";             // SO 不可用 → 交上层降级，不抛异常
        int span = key.length() - 19;                 // 109
        int start = (int) (tsSec % span);
        String str = key.substring(start, start + 20);
        return sha256Hex(str + "86400") + "_" + tsSec + "_86400";
    }

    /** 秒级时间戳 → {@code yyyyMMddHHmmss}（设备本地时区，与原版 JS {@code formatDateTime} 一致） */
    private static String formatDateTime(long sec) {
        if (sec <= 0L) return "0";
        return new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                .format(new Date(sec * 1000L));
    }

    /** 生成随机 MAC（原版 JS {@code generateRandomMAC}：6 组两位大写十六进制，冒号分隔） */
    private static String randomMac() {
        char[] hex = "0123456789ABCDEF".toCharArray();
        Random r = new Random();
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            sb.append(hex[r.nextInt(16)]).append(hex[r.nextInt(16)]);
            if (i < 5) sb.append(':');
        }
        return sb.toString();
    }

    /** SHA-256 → 小写十六进制（对应 JS {@code CryptoJS.SHA256(...).toString()}） */
    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            LogBridge.w(TAG, "SHA-256 不可用：" + t.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------

    /**
     * 单次 GET（明文 HTTP；与抓包一致只带 User-Agent）。
     * 不设置 {@code Accept-Encoding}，避免额外的 gzip 解码分支。
     */
    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            // 【2026-10-25 NO_PROXY】播放链路统一绕开系统代理（见虎牙 6 处同口径注释）
            conn = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code != 200) {
                LogBridge.w(TAG, "GITV 鉴权 HTTP " + code);
                return "";
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LogBridge.w(TAG, "GITV 鉴权请求异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return "";
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
