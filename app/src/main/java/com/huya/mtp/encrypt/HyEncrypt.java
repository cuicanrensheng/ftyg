package com.huya.mtp.encrypt;

import android.content.Context;
import android.util.Log;

public class HyEncrypt {
    private static final String TAG = "HyEncrypt";

    public static void init() {
    }

    public static byte[] encrypt(Context context, String key, byte[] data) {
        return EncryptJni.keyEncrypt2(context, key != null ? key.getBytes() : null, data);
    }

    public static byte[] decrypt(Context context, String key, byte[] data) {
        return EncryptJni.keyDecrypt2(context, key != null ? key.getBytes() : null, data);
    }

    public static void encryptFile(Context context, String key, String src, String dst) {
        EncryptJni.jniEncryptFile(key == null ? null : key.getBytes(), src, dst);
    }

    public static void decryptFile(Context context, String key, String src, String dst) {
        EncryptJni.jniDecryptFile(key == null ? null : key.getBytes(), src, dst);
    }

    static {
        Log.d(TAG, "mtpencrypt replaced by pure-Java stub (no native)");
    }
}
