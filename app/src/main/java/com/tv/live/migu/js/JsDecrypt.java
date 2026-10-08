package com.tv.live.migu.js;

import android.util.Base64;

import com.tv.live.util.LogBridge;

/**
 * JS 插件解密 —— 与畅享TV {@code org.fungo.jsparser.Parser.decrypt} 逐指令等价。
 *
 * <p><b>算法（smali 级复刻）</b>：
 * <pre>
 *   byte[]  b1 = Base64.decode(密文, 0);
 *   for (byte x : b1) sb.append((char) ((x - 1) % 256));   // 每字节 -1，rem-int/lit16 256
 *   String  s2 = sb.toString();
 *   byte[]  b2 = Base64.decode(s2, 0);
 *   String  out = new String(b2).replace("com.yuntu.a8.info.encrypt", "");
 * </pre>
 *
 * <p>注意：{@code (x - 1) % 256} 与 {@code int-to-char} 的行为刻意保持一致（负数会转成
 * 大码位 char）。实测密文第二层 Base64 文本全部是可打印 ASCII（33~126），减一后仍为正数，
 * 故该分支不会触发 —— 与线上行为一致。
 */
public final class JsDecrypt {

    private static final String TAG = "MiguJsDecrypt";

    /** 水印标记，解密后需删除 */
    private static final String WATERMARK = "com.yuntu.a8.info.encrypt";

    private JsDecrypt() {}

    /** 解密 JS 插件密文；失败返回空串 */
    public static String decrypt(String cipher) {
        if (cipher == null || cipher.isEmpty()) return "";
        try {
            // 去掉换行/空白，避免 Base64 decode 受格式影响
            String compact = cipher.replaceAll("\\s+", "");
            byte[] step1 = Base64.decode(compact, Base64.DEFAULT);
            if (step1.length == 0) return "";

            StringBuilder sb = new StringBuilder(step1.length);
            for (byte b : step1) {
                sb.append((char) ((b - 1) % 256));
            }

            byte[] step2 = Base64.decode(sb.toString(), Base64.DEFAULT);
            if (step2.length == 0) return "";

            return new String(step2, "UTF-8").replace(WATERMARK, "");
        } catch (Throwable t) {
            LogBridge.w(TAG, "解密失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return "";
        }
    }
}
