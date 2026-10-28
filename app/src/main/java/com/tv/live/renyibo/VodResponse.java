package com.tv.live.renyibo;

import com.tv.live.util.LogBridge;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 内置源5（爱其意/任意播）影视点播（VOD）响应解析器。
 *
 * <p>响应两种形态（均来自授权抓包离线实测）：
 * <ol>
 *   <li><b>纯明文 JSON</b> —— 列表类接口（video_list / dm_list 等）返回；</li>
 *   <li><b>三段 Base64 信封</b> —— 导航、video_detail 等加密接口返回：
 *       <pre>b64(SEG1) + b64(SEG2) + b64("xddappsecretkey"+N)</pre>
 *       其中 {@code N = b64(SEG1)} 的字符数（含填充）。SEG1 解码后是
 *       「{@code 0x}+十进制」token 串（见 {@link VodCrypto#decodeSeg1}），
 *       SEG2 为原始字节。真实密文缓冲
 *       {@code buf = decodeSeg1(SEG1) ‖ decode(SEG2)}，且
 *       <b>IV = buf[0:16]、密文 = buf[16:]</b>，AES-128-CBC + PKCS7、
 *       固定密钥（下沉 SO，见 {@link VodCrypto}）解密即业务明文 JSON。</li>
 * </ol>
 *
 * <p><b>⛔ 本类不联网</b>，只做解析与本地解密。
 * <b>2026-10-12 已闭环</b>：52/52 抓包样本按上述结构离线解密全部命中明文 JSON。
 */
public final class VodResponse {

    private static final String TAG = "VodResponse";

    /** 三段信封第三段锚点（b64("xddappsecretkey") 前缀，尾部跟数字 N）。 */
    private static final String ANCHOR = "eGRkYXBwc2VjcmV0a2V5";

    private VodResponse() {}

    /**
     * 解析响应正文为 JSONObject（自动识别明文 / 三段信封两形态）。
     *
     * @param rawBody 服务端响应正文
     * @return 业务 JSONObject；无法解析返回 {@code null}（不抛出）
     */
    public static JSONObject parse(String rawBody) {
        if (rawBody == null || rawBody.trim().isEmpty()) return null;
        String body = rawBody.trim();
        int idx = body.indexOf(ANCHOR);
        if (idx < 0) {
            // 形态①：纯明文 JSON
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                LogBridge.w(TAG, "响应非明文 JSON 且无信封锚点，解析失败");
                return null;
            }
        }
        // 形态②：三段 Base64 信封
        try {
            byte[] seg3 = VodCrypto.b64Decode(body.substring(idx));
            String seg3s = new String(seg3, StandardCharsets.UTF_8);
            String num = seg3s.startsWith("xddappsecretkey")
                    ? seg3s.substring("xddappsecretkey".length()) : seg3s;
            int n = Integer.parseInt(num.trim());
            byte[] d1 = VodCrypto.b64Decode(body.substring(0, n));
            byte[] d2 = VodCrypto.b64Decode(body.substring(n, idx));
            byte[] a = VodCrypto.decodeSeg1(d1);
            byte[] buf = new byte[a.length + d2.length];
            System.arraycopy(a, 0, buf, 0, a.length);
            System.arraycopy(d2, 0, buf, a.length, d2.length);
            if (buf.length < 32) {
                LogBridge.d(TAG, "信封解密缓冲过短 len=" + buf.length);
                return null;
            }
            byte[] iv = new byte[16];
            byte[] ct = new byte[buf.length - 16];
            System.arraycopy(buf, 0, iv, 0, 16);
            System.arraycopy(buf, 16, ct, 0, ct.length);
            String plain = VodCrypto.decryptToString(ct, iv);
            return new JSONObject(plain);
        } catch (Exception e) {
            LogBridge.w(TAG, "信封解密失败 " + e.getMessage());
            return null;
        }
    }

    /**
     * 兼容旧签名的入口：信封形态的 IV 已内嵌响应（buf[0:16]），无需外部 IV。
     *
     * @deprecated 请使用 {@link #parse(String)}。
     */
    @Deprecated
    public static JSONObject parse(String rawBody, byte[] decryptIv) {
        return parse(rawBody);
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}