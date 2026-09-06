package com.tv.live.security;

import android.util.Base64;
import com.tv.live.util.LogBridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class StringObfuscator {

    private static final String TAG = "StrObfuscator";

    private static final Map<String, String> stringCache = new HashMap<>();

    private static final int XOR_KEY = 0x3C;
    private static final int XOR_KEY_2 = 0xA5;

    public static String encodeString(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            c ^= XOR_KEY;
            c ^= XOR_KEY_2;
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }

    public static String decodeString(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;

        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }

        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);

                c ^= XOR_KEY_2;
                c ^= XOR_KEY;
                sb.append(c);
            }

            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            LogBridge.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }

    public static String simpleEncode(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c >= 'a' && c <= 'z') {
                c = (char) ((c - 'a' + 13) % 26 + 'a');
            } else if (c >= 'A' && c <= 'Z') {
                c = (char) ((c - 'A' + 13) % 26 + 'A');
            } else if (c >= '0' && c <= '9') {
                c = (char) ((c - '0' + 5) % 10 + '0');
            }
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }

    public static String simpleDecode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;

        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }

        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);

                if (c >= 'a' && c <= 'z') {
                    c = (char) ((c - 'a' - 13 + 26) % 26 + 'a');
                } else if (c >= 'A' && c <= 'Z') {
                    c = (char) ((c - 'A' - 13 + 26) % 26 + 'A');
                } else if (c >= '0' && c <= '9') {
                    c = (char) ((c - '0' - 5 + 10) % 10 + '0');
                }
                sb.append(c);
            }

            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            LogBridge.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }

    public static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }

    public static void clearCache() {
        stringCache.clear();
    }

    public static String encodeInt(int value) {
        return encodeString(String.valueOf(value));
    }

    public static int decodeInt(String encoded) {
        String decoded = decodeString(encoded);
        if (decoded == null) return -1;
        try {
            return Integer.parseInt(decoded);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
