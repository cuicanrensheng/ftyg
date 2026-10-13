package com.tv.live.renyibo;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内置源5（爱其意/任意播）影视点播（VOD）加解密工具类。
 *
 * <p><b>来源</b>：本类源自对爱其意 App（Dart AOT）运行时加解密请求体的逆向结论
 * （见 F 盘既有分析报告），已通过「用固定 key + 任意 iv 解密已抓请求密文」铁证确认：
 * <ul>
 *   <li>算法：AES-128-CBC + PKCS7</li>
 *   <li>密钥：固定 {@code 7dbe4c69aa5b98ad90ba3468e21c6fd2}（16 字节）</li>
 *   <li>密文载体：URL-safe Base64、无 {@code =} 填充，长度恒为 16 的倍数</li>
 *   <li>IV：每个请求随机</li>
 * </ul>
 *
 * <p><b>⚠️ 红线</b>：本类<b>只做编解码原语，绝不发起任何网络请求</b>。
 * <p><b>【2026-10-12 线格式闭环】</b>请求体 = base64url(IV(16B) ‖ 密文)；响应为
 * 「三段 Base64 信封」（b64(SEG1)+b64(SEG2)+b64("xddappsecretkey"+N)），
 * 解码与解密编排见 {@link VodResponse}，{@link #decodeSeg1} 提供 SEG1 的
 * token 逆序+偏移解码。实际发送仍受 {@link RenYiBoVodSourceLoader} 双闸门约束。
 */
public final class VodCrypto {

    private VodCrypto() {}

    /** 已破译真密钥（32 hex = AES-128）。与 {@link RenYiBoVodCatalog#VOD_KEY}（废弃）不同。 */
    public static final String VOD_KEY_HEX = "7dbe4c69aa5b98ad90ba3468e21c6fd2";

    /** 运行时密钥字节（16 字节）。 */
    private static final byte[] VOD_KEY_BYTES = hexToBytes(VOD_KEY_HEX);

    private static final SecureRandom SRANDOM = new SecureRandom();

    /** 随机生成 16 字节 IV。 */
    public static byte[] genRandomIv() {
        byte[] iv = new byte[16];
        SRANDOM.nextBytes(iv);
        return iv;
    }

    /** URL-safe 无 padding Base64 编码。 */
    public static String b64urlNoPad(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** URL-safe Base64 解码（容忍缺省等号填充的输入）。 */
    public static byte[] b64urlDecode(String s) {
        // Base64.getUrlDecoder() 要求无 padding；服务端载体本就无 '='，
        // 此处按"无填充"直解，避免在编解码语义上臆测。
        return Base64.getUrlDecoder().decode(s);
    }

    /**
     * 标准 Base64 解码（响应信封三段用），容忍末尾缺省 {@code =} 填充。
     *
     * <p>服务端响应三段均为带填充的标准 Base64（如 SEG2 常见 {@code ==} 结尾），
     * 此处统一做 4 对齐再解，兼容无填充变体。
     */
    public static byte[] b64Decode(String s) {
        String t = (s == null ? "" : s.trim());
        int rem = t.length() % 4;
        if (rem != 0) {
            StringBuilder sb = new StringBuilder(t);
            for (int i = rem; i < 4; i++) sb.append('=');
            t = sb.toString();
        }
        return Base64.getDecoder().decode(t);
    }

    /**
     * SEG1「{@code 0x}+十进制」token 串解码（响应密文信封第一段）。
     *
     * <p>真实语义（2026-10-12 离线 52/52 抓包样本闭环验证）：
     * <ol>
     *   <li>按 {@code 0x} 切分十进制串得到 token 数组（如
     *       {@code "0x1850x256…"} → {@code [185, 256, …]}）；</li>
     *   <li>token 数组<b>逆序</b>；</li>
     *   <li>按<b>输出下标</b>减偏移：偶数下标 -11；奇数下标且 %3==0 减 4、
     *       %5==0 减 2、其余减 3；</li>
     *   <li>结果 mod 256 即明文字节。</li>
     * </ol>
     *
     * @param asciiTokens SEG1 经 Base64 解码后的 ASCII 字节（{@code "0x…"} 串）
     * @return 该段对应的密文缓冲前部字节
     */
    public static byte[] decodeSeg1(byte[] asciiTokens) {
        String s = new String(asciiTokens, StandardCharsets.UTF_8);
        String[] parts = s.split("0x");
        int n = 0;
        for (String p : parts) {
            if (!p.isEmpty()) n++;
        }
        int[] vals = new int[n];
        int k = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            vals[k++] = Integer.parseInt(p.trim());
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int v = vals[n - 1 - i]; // 逆序
            int off;
            if (i % 2 == 0) {
                off = 11;
            } else if (i % 3 == 0) {
                off = 4;
            } else if (i % 5 == 0) {
                off = 2;
            } else {
                off = 3;
            }
            out[i] = (byte) ((v - off) & 0xFF);
        }
        return out;
    }

    /** AES-128-CBC + PKCS7 加密（参数化 IV，供测试与授权后启用时使用）。 */
    public static byte[] encrypt(String plaintext, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(VOD_KEY_BYTES, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /** AES-128-CBC + PKCS7 解密为字节。 */
    public static byte[] decrypt(byte[] cipherBytes, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(VOD_KEY_BYTES, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(cipherBytes);
    }

    /** 解密为字符串。 */
    public static String decryptToString(byte[] cipherBytes, byte[] iv) throws Exception {
        return new String(decrypt(cipherBytes, iv), StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}