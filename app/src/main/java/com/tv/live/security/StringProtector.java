package com.tv.live.security;

import android.util.Base64;
import com.tv.live.util.LogBridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public final class StringProtector {

    private static final String TAG = "StringProtector";
    private static volatile boolean sEnabled = false;

    private static final Map<String, String> sEncryptedCache = new HashMap<>();

    private static final Map<String, String> sDecryptedCache = new HashMap<>();

    private static volatile byte[] sDynamicKey = null;

    private static final int XOR_KEY_LENGTH = 16;

    private StringProtector() {}

    public static void init(boolean enable) {
        sEnabled = enable;
        if (enable) {

            sDynamicKey = generateDynamicKey();
            LogBridge.i(TAG, "字符串保护已启用");
        } else {
            LogBridge.i(TAG, "字符串保护已禁用（调试模式）");
        }
    }

    public static void register(String key, String encryptedValue) {
        if (key == null || encryptedValue == null) return;
        sEncryptedCache.put(key, encryptedValue);

        sDecryptedCache.remove(key);
    }

    public static void register(String key, String value, boolean encrypt) {
        if (key == null || value == null) return;

        if (encrypt && sEnabled) {
            String encrypted = encryptValue(value);
            sEncryptedCache.put(key, encrypted);
            sDecryptedCache.remove(key);
        } else {

            sDecryptedCache.put(key, value);
        }
    }

    public static String get(String key) {
        if (key == null) return null;

        String cached = sDecryptedCache.get(key);
        if (cached != null) return cached;

        String encrypted = sEncryptedCache.get(key);
        if (encrypted != null) {
            try {
                String decrypted = decryptValue(encrypted);

                if (decrypted != null) {
                    sDecryptedCache.put(key, decrypted);
                }
                return decrypted;
            } catch (Exception e) {
                LogBridge.e(TAG, "解密字符串失败: " + key);
                return null;
            }
        }

        return null;
    }

    public static String[] getBatch(String... keys) {
        if (keys == null) return null;
        String[] result = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = get(keys[i]);
        }
        return result;
    }

    private static String encryptValue(String plain) {
        if (plain == null) return null;
        try {
            byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBytes = xorEncrypt(plainBytes, sDynamicKey);
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            LogBridge.w(TAG, "加密失败: " + e.getMessage());
            return plain;
        }
    }

    private static String decryptValue(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        try {
            byte[] encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            byte[] decryptedBytes = xorDecrypt(encryptedBytes, sDynamicKey);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LogBridge.e(TAG, "解密失败: " + e.getMessage());
            return null;
        }
    }

    private static byte[] xorEncrypt(byte[] data, byte[] key) {
        if (data == null || key == null) return null;
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }

    private static byte[] xorDecrypt(byte[] data, byte[] key) {

        return xorEncrypt(data, key);
    }

    private static byte[] generateDynamicKey() {
        try {

            String seed = "TVLive_" + System.currentTimeMillis() + "_" +
                         android.os.Build.VERSION.SDK_INT;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[XOR_KEY_LENGTH];
            System.arraycopy(hash, 0, key, 0, XOR_KEY_LENGTH);
            return key;
        } catch (Exception e) {

            return "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        }
    }

    public static void clearCache() {
        sDecryptedCache.clear();
    }

    public static void rotateKey() {
        if (!sEnabled) return;
        sDynamicKey = generateDynamicKey();
        clearCache();
        LogBridge.i(TAG, "动态密钥已更新");
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static int getRegisteredCount() {
        return sEncryptedCache.size();
    }

    public static String quickEncrypt(String plain) {
        if (plain == null) return null;
        byte[] key = "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = new byte[plainBytes.length];
        for (int i = 0; i < plainBytes.length; i++) {
            encryptedBytes[i] = (byte) (plainBytes[i] ^ key[i % key.length]);
        }
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
    }

    public static String quickDecrypt(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        byte[] key = "TVLive2026Key!".getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP);
        byte[] decryptedBytes = new byte[encryptedBytes.length];
        for (int i = 0; i < encryptedBytes.length; i++) {
            decryptedBytes[i] = (byte) (encryptedBytes[i] ^ key[i % key.length]);
        }
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}
