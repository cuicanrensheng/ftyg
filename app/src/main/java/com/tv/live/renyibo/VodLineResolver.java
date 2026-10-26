package com.tv.live.renyibo;

import com.tv.live.security.SecurityCore;
import com.tv.live.util.LogBridge;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 源5（爱其意/任意播）加密线路 token 解析器 —— 纯 HTTP，无 WebView。
 *
 * <p><b>【2026-10-17 线路解析链全图（真机抓包 + 离线解密实证）】</b>
 * vod_url_with_player 中的加密线路 token <b>不需要本地密钥</b>：爱其意 App 的做法是
 * 把 token <b>原样转发</b>给它自带的解析服务器，由服务端完成解析后返回可播直链。
 * 本类按同一契约实现（token 放在 {@code url=} 查询参数，原样转发）：
 *
 * <pre>
 *   token 前缀 / 形态              解析端点（GET，Host 以 2026-10-17 抓包为准）        返回
 *   ─────────────────────────────────────────────────────────────────────────────
 *   deja-…                        {49.233.180.127:880}/vertify.php/?url=          mp4/m3u8（JSON:url）
 *   ZY-icial_… / ZY-anime_…       {49.233.180.127:880}/jxxh.php/?url=             m3u8（JSON:url，优酷/腾讯系）
 *   yzx-…                         {49.233.180.127:880}/zsyzx.php/?url=            m3u8（JSON:url，服务器缓存）
 *   https://www.4kvm.top/play/…   {49.233.180.127:880}/4kys.php/?url=              m3u8（JSON:url，服务器缓存）
 *   NBY-…                         api.nbyjson.top:7788 /api/?key=…&url=           m3u8（JSON:url）
 *   co_…                          coffee.1ljx.com:32000 /api/?key=…&url=          m3u8（JSON:url，cibn-edge CDN）
 *   Ace_Net-…                     175.24.181.180:5000 /api/jiexi/common?Key=…&url= mp4/m3u8（JSON:url）
 *   48 字节 Base64（YKTX 线）     jx.nbpicture.xyz /jx?url=                        m3u8（JSON:url）
 * </pre>
 *
 * <p>⚠ <b>必须用域名</b>（2026-10-17 实测）：NBY/co/YKTX 三端点直连「当时抓包 IP」会因
 * 虚拟主机不匹配返回 404 —— 抓包里的 43.248.96.62 / 43.248.102.96 / 118.107.11.4
 * 只是 DNS 解析结果，真 Host 是上表域名（见 secstore.cpp 注释）。
 *
 * <p>端点属敏感常量：明文不在 dex，经 {@link SecurityCore#secureString(int)}
 * 从 .so（secstore.cpp）取。
 *
 * <p><b>红线</b>：不批量、不并发、不重试 —— 每次起播只对「当前所选线路首集」
 * 发一次解析请求，失败即返回 null 交回上层降级（测速换线/回退直链）。
 * 本类只被「起播惰性解析」通路调用（已在双闸门之后），不单独检查开关。
 */
public final class VodLineResolver {

    private static final String TAG = "VodLineResolver";

    /** 连接超时（解析服务器通常秒回；8s 兜底）。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    /** 读取超时。 */
    private static final int READ_TIMEOUT_MS = 15000;
    /** 响应体上限（解析接口只返回小 JSON；超限截断防异常大响应）。 */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    /** 解析结果缓存：token → 直链（会话内同 token 只解析一次）。 */
    private static final Map<String, String> sTokenCache = new ConcurrentHashMap<>();

    // ---- 端点常量（SO 惰性取值，dex 无明文）----
    private static volatile boolean sEpLoaded;
    private static String sEpVertify, sEpJxxh, sEpZsyzx, sEp4kys, sEpNby, sEpCo, sEpAce, sEpYktx, sEpDmm;

    private VodLineResolver() {}

    /**
     * 该串是否属于「服务端解析」型加密线路（deja- / ZY- / yzx- / NBY- / co_ /
     * Ace_Net- / 4kvm 解析页 / 48B Base64(YKTX)）。
     */
    public static boolean isResolvableToken(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.startsWith("deja-")
                || s.startsWith("ZY-")
                || s.startsWith("yzx-")
                || s.startsWith("NBY-")
                || s.startsWith("co_")
                || s.startsWith("Ace_Net-")
                || s.contains("4kvm.top/play/")
                || looksLikeYktxB64(s);
    }

    /**
     * 官方路线编号：1=①BBA / 2=②NBY / 3=③ZY / 4=④YKTX / 5=⑤co / 6=⑥yzx；
     * 非官方路线（含 {@code ZY-anime_} 动漫专线DMM、{@code Ace_Net-}、4kvm 解析页）返回 0。
     *
     * <p>与 {@link #typeName} 的官方命名同口径，供线路表按「官方①–⑥」排序。</p>
     */
    public static int officialNo(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (s.startsWith("deja-")) return 1;          // 官方①BBA
        if (s.startsWith("NBY-")) return 2;           // 官方②NBY
        if (s.startsWith("ZY-anime_")) return 0;      // 动漫专线DMM：不占官方编号
        if (s.startsWith("ZY-")) return 3;            // 官方③ZY
        if (s.startsWith("co_")) return 5;            // 官方⑤co
        if (s.startsWith("yzx-")) return 6;           // 官方⑥yzx
        if (looksLikeYktxB64(s)) return 4;            // 官方④YKTX
        return 0;
    }

    /**
     * 该 token 是否属于「官方解析」路线（官方①BBA / ②NBY / ③ZY / ④YKTX / ⑤co / ⑥yzx）。
     *
     * <p>与 {@link #typeName} 的官方命名同口径；<b>不含</b> {@code Ace_Net-}（第三方 Ace）
     * 与 {@code 4kvm.top} 解析页（高清①4kys）—— 这两类不属于爱其意官方自有解析路线。
     * {@code ZY-anime_}（动漫专线DMM）以 {@code ZY-} 前缀计入官方③ZY 同族。</p>
     */
    public static boolean isOfficialToken(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.startsWith("deja-")
                || s.startsWith("ZY-")
                || s.startsWith("yzx-")
                || s.startsWith("NBY-")
                || s.startsWith("co_")
                || looksLikeYktxB64(s);
    }

    /** YKTX 线 token = 标准 Base64 48 字节（64 字符，字符集 A-Za-z0-9+/=）。 */
    private static boolean looksLikeYktxB64(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * 解析加密线路 token → 可播放直链。
     *
     * @param token 首集 token（如 deja-6339… / NBY-XMYAES…|… / co_… / yzx-… / 48B b64）
     * @return 直链 URL；失败返回 {@code null}（不抛出，调用方按其既有降级路径处理）
     */
    public static String resolveToken(String token) {
        if (token == null || token.isEmpty()) return null;
        String cached = sTokenCache.get(token);
        if (cached != null) {
            LogBridge.d(TAG, "解析命中缓存 → " + cached);
            return cached;
        }
        ensureEndpoints();
        String base = endpointFor(token);
        if (base == null || base.isEmpty()) {
            LogBridge.w(TAG, "无可用解析端点（SO 未加载或 token 类型未知）: "
                    + abbrev(token));
            return null;
        }
        String reqUrl = base + enc(token);
        LogBridge.i(TAG, "开始解析 [" + typeName(token) + "] " + abbrev(token));
        String url = httpGetJsonUrl(reqUrl);
        if (url == null) {
            LogBridge.w(TAG, "解析失败（无 url 字段/网络异常）: " + abbrev(token));
            return null;
        }
        sTokenCache.put(token, url);
        LogBridge.i(TAG, "解析成功 [" + typeName(token) + "] → " + url);
        return url;
    }

    /** token → 解析端点前缀（含 {@code url=} 参数名）。 */
    private static String endpointFor(String token) {
        if (token.startsWith("deja-")) return sEpVertify;
        if (token.startsWith("ZY-anime_")) return sEpDmm;     // 动漫专线（2026-10-17 真机抓包：dmm.php 独立端点）
        if (token.startsWith("ZY-")) return sEpJxxh;          // ZY-icial_ 经典线
        if (token.startsWith("yzx-")) return sEpZsyzx;
        if (token.startsWith("NBY-")) return sEpNby;
        if (token.startsWith("co_")) return sEpCo;
        if (token.startsWith("Ace_Net-")) return sEpAce;
        if (looksLikeYktxB64(token)) return sEpYktx;
        if (token.contains("4kvm.top/play/")) return sEp4kys;
        return null;
    }

    /** 取端点常量（从 .so 惰性加载并缓存；SO 不可用则为 null）。 */
    private static void ensureEndpoints() {
        if (sEpLoaded) return;
        synchronized (VodLineResolver.class) {
            if (sEpLoaded) return;
            sEpVertify = SecurityCore.secureString(SecurityCore.C_VODP_VERTIFY);
            sEpJxxh    = SecurityCore.secureString(SecurityCore.C_VODP_JXXH);
            sEpZsyzx   = SecurityCore.secureString(SecurityCore.C_VODP_ZSYZX);
            sEp4kys    = SecurityCore.secureString(SecurityCore.C_VODP_4KYS);
            sEpNby     = SecurityCore.secureString(SecurityCore.C_VODP_NBY);
            sEpCo      = SecurityCore.secureString(SecurityCore.C_VODP_CO);
            sEpAce     = SecurityCore.secureString(SecurityCore.C_VODP_ACE);
            sEpYktx    = SecurityCore.secureString(SecurityCore.C_VODP_YKTX);
            sEpDmm     = SecurityCore.secureString(SecurityCore.C_VODP_DMM);
            sEpLoaded = true;
        }
    }

    /**
     * token 原样转发（与 App 抓包一致：仅转义 {@code '|'} 与空格，
     * {@code + / = :} 等一律保持原样 —— 服务端按原始串校验）。
     */
    private static String enc(String s) {
        return s.replace("|", "%7C").replace(" ", "%20");
    }

    /**
     * 单次 GET → 解析 JSON {@code {"code":…, "url":"…"}} → 返回 url。
     * 手动跟随 3xx 跳转（≤4 跳，跨协议/跨主机均跟随——NBY 端点实测返回 302，
     * Java HttpURLConnection 不自动跨协议跟随）；自动 gzip 解压。不重试；异常返回 null。
     */
    private static String httpGetJsonUrl(String urlStr) {
        String cur = urlStr;
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(cur);
                // 【2026-10-25 NO_PROXY】播放链路统一绕开系统代理（见虎牙 6 处同口径注释）
                conn = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);   // 手动控管跳转（含 http↔https）
                // 完全镜像抓包头部：UA=Dart、Accept-Encoding=gzip（缺任一项可能被服务端
                // WAF 判机器人返 302，2026-10-17 实测）。gzip 响应本方法自行解压。
                conn.setRequestProperty("User-Agent", "Dart/3.11 (dart:io)");
                conn.setRequestProperty("Accept-Encoding", "gzip");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        LogBridge.w(TAG, "HTTP " + code + " 无 Location ← " + hostOfReq(cur));
                        return null;
                    }
                    // 相对 Location 补全
                    if (loc.startsWith("/")) {
                        loc = url.getProtocol() + "://" + url.getAuthority() + loc;
                    }
                    LogBridge.i(TAG, "HTTP " + code + " 跳转 → " + abbrev(loc));
                    cur = loc;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    LogBridge.w(TAG, "HTTP " + code + " ← " + hostOfReq(cur));
                    return null;
                }
                InputStream is = conn.getInputStream();
                String enc = conn.getHeaderField("Content-Encoding");
                if (enc != null && enc.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                    is = new java.util.zip.GZIPInputStream(is);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > MAX_BODY_BYTES) break;
                }
                try { is.close(); } catch (Throwable ignored) {}
                String body = new String(bos.toByteArray(), "UTF-8").trim();
                JSONObject j = new JSONObject(body);
                String u = j.optString("url", "").trim();
                if (u.startsWith("http://") || u.startsWith("https://")) return u;
                LogBridge.w(TAG, "响应无有效 url 字段: " + abbrev(body));
                return null;
            } catch (Throwable t) {
                LogBridge.w(TAG, "解析请求异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }
        LogBridge.w(TAG, "跳转次数过多，放弃: " + abbrev(urlStr));
        return null;
    }

    /** 清空解析缓存（调试/切源策略变化时可调）。 */
    public static void clearCache() {
        sTokenCache.clear();
    }

    private static String typeName(String token) {
        if (token.startsWith("deja-")) return "官方①BBA";
        if (token.startsWith("ZY-anime_")) return "动漫专线DMM";
        if (token.startsWith("ZY-")) return "官方③ZY";
        if (token.startsWith("yzx-")) return "官方⑥yzx";
        if (token.startsWith("NBY-")) return "官方②NBY";
        if (token.startsWith("co_")) return "官方⑤co";
        if (token.startsWith("Ace_Net-")) return "Ace";
        if (looksLikeYktxB64(token)) return "官方④YKTX";
        if (token.contains("4kvm.top")) return "高清①4kys";
        return "未知";
    }

    private static String abbrev(String s) {
        if (s == null) return "null";
        return s.length() <= 64 ? s : s.substring(0, 64) + "…(" + s.length() + ")";
    }

    private static String hostOfReq(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Throwable t) {
            return urlStr;
        }
    }
}
