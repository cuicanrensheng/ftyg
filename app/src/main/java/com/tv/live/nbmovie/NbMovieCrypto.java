package com.tv.live.nbmovie;

import com.tv.live.util.LogBridge;

/**
 * 4K影视（NbMovie）加密协议桥接。
 *
 * <p>算法已 100% 下沉 libtvlive_security.so（cpp/nbmovie.cpp），本类只做
 * 薄封装：会话状态（私钥/公钥/共享密钥）由 SO 进程内持有，运行时自动完成
 * 加密/解密，Java 层不编排任何密码学细节。
 *
 * <p>协议（静态逆向自 libgojni.so，不移植其 .so）：
 * <ul>
 *   <li>{@link #generateKeyPair()} → "pubB64|sigB64|ts"，ts 为 10 位 Unix 秒</li>
 *   <li>{@link #computeSharedSecret(String)} 传服务端公钥 base64，空串 = 成功</li>
 *   <li>{@link #encryptRaw(byte[])} → nonce(12)||ct||tag(16)，最小 28 字节</li>
 *   <li>{@link #decryptRaw(byte[])} 逆过程，验签失败返回 null</li>
 * </ul>
 *
 * <p>base64 口径：默认 StdEncoding（44 字符）；若服务端 exchange 校验失败，
 * 可 {@link #setBase64Url(boolean)} 切到 RawURLEncoding（43 字符）重试。
 * 解码侧自动识别两种字母表。
 */
public final class NbMovieCrypto {

    private static final String TAG = "NbMovieCrypto";

    private static volatile boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("tvlive_security");
            sLoaded = true;
        } catch (Throwable t) {
            sLoaded = false;
            LogBridge.w(TAG, "libtvlive_security.so 加载失败: " + t.getMessage());
        }
    }

    private NbMovieCrypto() {}

    /** SO 是否可用（协议全部下沉 SO，不可用时一切操作返回失败） */
    public static boolean isAvailable() {
        return sLoaded;
    }

    /**
     * 生成 X25519 密钥对并用 HMAC-SHA256 签名。
     *
     * @return "pubB64|sigB64|ts"（恰好 3 段，竖线分隔）；ts 为 10 位 Unix 秒
     *         （服务端按秒校验签名新鲜度，毫秒会被判「签名已过期」403）
     */
    public static native String nativeGenerateKeyPair();

    public static String generateKeyPair() {
        if (!sLoaded) return null;
        try {
            return nativeGenerateKeyPair();
        } catch (Throwable t) {
            LogBridge.w(TAG, "generateKeyPair 异常: " + t.getMessage());
            return null;
        }
    }

    /**
     * 用服务端公钥派生共享密钥（直接作 AES-256 key，无 KDF）。
     *
     * @return 空串 = 成功；非空 = 错误原因（"no_keypair" / "bad_server_pub"）
     */
    public static native String nativeComputeSharedSecret(String serverPubB64);

    public static String computeSharedSecret(String serverPubB64) {
        if (!sLoaded || serverPubB64 == null || serverPubB64.isEmpty()) return "bad_server_pub";
        try {
            return nativeComputeSharedSecret(serverPubB64);
        } catch (Throwable t) {
            LogBridge.w(TAG, "computeSharedSecret 异常: " + t.getMessage());
            return "exception";
        }
    }

    /** 明文 → nonce(12)||ct||tag(16)；失败返回 null */
    public static native byte[] nativeEncryptRaw(byte[] plain);

    public static byte[] encryptRaw(byte[] plain) {
        if (!sLoaded || plain == null) return null;
        try {
            return nativeEncryptRaw(plain);
        } catch (Throwable t) {
            LogBridge.w(TAG, "encryptRaw 异常: " + t.getMessage());
            return null;
        }
    }

    /** nonce(12)||ct||tag(16) → 明文；验签失败返回 null */
    public static native byte[] nativeDecryptRaw(byte[] blob);

    public static byte[] decryptRaw(byte[] blob) {
        if (!sLoaded || blob == null) return null;
        try {
            return nativeDecryptRaw(blob);
        } catch (Throwable t) {
            LogBridge.w(TAG, "decryptRaw 异常: " + t.getMessage());
            return null;
        }
    }

    /** base64 口径切换：false=StdEncoding(默认, 44 字符) true=RawURLEncoding(43 字符) */
    public static native void nativeSetBase64Url(boolean url);

    public static void setBase64Url(boolean url) {
        if (!sLoaded) return;
        try {
            nativeSetBase64Url(url);
        } catch (Throwable ignored) {
        }
    }

    /** 会话是否就绪（已生成密钥对且已建立共享密钥） */
    public static native boolean nativeSessionReady();

    public static boolean sessionReady() {
        if (!sLoaded) return false;
        try {
            return nativeSessionReady();
        } catch (Throwable t) {
            return false;
        }
    }
}
