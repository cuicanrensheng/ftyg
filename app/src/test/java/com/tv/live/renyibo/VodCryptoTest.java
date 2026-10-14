package com.tv.live.renyibo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 内置源5 VOD 加密移植【纯离线自检】。
 *
 * <p>使用<b>已抓包的真实请求密文 / 明文配对</b>验证 {@link VodCrypto} 的
 * AES-128-CBC+PKCS7 实现正确（不发任何网络请求）：
 * <ul>
 *   <li>用例1：用固定 key=7dbe + 零 IV 解密已抓请求密文，第二块起（CBC 不依赖 IV）
 *       应还原出已确证明文，证明 key+mode+padding 正确；</li>
 *   <li>用例2：encrypt(decrypt) 往返一致，证明加解密互为逆运算；</li>
 *   <li>用例3：URL-safe Base64 无 {@code =} 填充、长度恒为 16 的倍数。</li>
 * </ul>
 *
 * <p>数据来源：{@code D:\TVlive\_work\frida\a1_out.jsonl} 中 encrypt_entry 抓到的
 * dm_list 请求（128B 密文），明文 {@code {"method":"dm_list","data":{"group_key":...}}}。
 */
public class VodCryptoTest {

    /** 已抓包的真实请求密文（URL-safe base64url，无 = 填充，128B）。 */
    private static final String CAPTURED_CIPHER =
            "VPYEbT3EtEfuxtZGCzD_p48eTQKxdiVj7-WtSIS8svR6_1dUJVx9YPOeypON_84oSAzxXPBac86"
            + "HtLQEk0tdpupV8oc2j5JmWm0Dp9ukGqfLdxywsHlF7EO5wqVvTrW7qfzxOkdIWodaexa6"
            + "ASesWb_dLaBdwv8ItcDX3-pveg0";

    /** 该密文（零 IV、自第二块起）还原出的已确证明文。 */
    private static final String KNOWN_PLAINTEXT =
            "{\"method\":\"dm_list\",\"data\":{\"group_key\":\"7f5a419eda65b3b40d79\","
            + "\"token\":\"\"},\"csrf\":1791800481814}";

    @Test
    public void decryptCapturedCipher_yieldsKnownPlaintext() throws Exception {
        byte[] cipher = VodCrypto.b64urlDecode(CAPTURED_CIPHER);
        assertEquals("密文应为 128B（8 个 AES 块）", 128, cipher.length);
        assertEquals("密文长度恒为 16 的倍数", 0, cipher.length % 16);

        // 零 IV：CBC 第二块起解密不依赖 IV，故结果应含完整明文（第一块会乱码但可越过后匹配）
        String dec = VodCrypto.decryptToString(cipher, new byte[16]);
        assertTrue("应还原出 dm_list 明文", dec.contains("\"method\":\"dm_list\""));
        assertTrue("应还原出 group_key", dec.contains("\"group_key\":\"7f5a419eda65b3b40d79\""));
        // 引擎语义正确性：固定 key 解出的明文必须完整包含确证的明文串
        assertTrue("应包含完整已确证明文", dec.contains(KNOWN_PLAINTEXT));
    }

    @Test
    public void encryptDecrypt_roundTrip_isInverse() throws Exception {
        byte[] iv = VodCrypto.genRandomIv();
        assertEquals("IV 应为 16 字节", 16, iv.length);

        byte[] cipher = VodCrypto.encrypt(KNOWN_PLAINTEXT, iv);
        assertEquals(0, cipher.length % 16);

        String dec = VodCrypto.decryptToString(cipher, iv);
        assertEquals("加解密应互为逆运算", KNOWN_PLAINTEXT, dec);
    }

    @Test
    public void b64url_noPad_encodeDecode_isRoundTrip() throws Exception {
        byte[] cipher = VodCrypto.b64urlDecode(CAPTURED_CIPHER);
        String enc = VodCrypto.b64urlNoPad(cipher);
        assertFalse("URL-safe Base64 不应含 '=' 填充", enc.contains("="));
        assertFalse("URL-safe Base64 不应含标准 '+'", enc.contains("+"));
        assertEquals("编码应与抓包密文一致", CAPTURED_CIPHER, enc);

        byte[] back = VodCrypto.b64urlDecode(enc);
        byte[] cp = KNOWN_PLAINTEXT.getBytes(StandardCharsets.UTF_8);
        // 由于 0x10 满块填充，密文长度 = 明文对齐到 16 再加一整块
        assertTrue(back.length > cp.length);
    }
}