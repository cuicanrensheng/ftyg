package com.huya.mtp.encrypt;

import android.content.Context;

class EncryptJni {
    EncryptJni() {
    }

    public static void jniEncryptFile(byte[] key, String src, String dst) {

    }

    public static void jniDecryptFile(byte[] key, String src, String dst) {
    }

    public static byte[] keyEncrypt2(Context context, byte[] key, byte[] data) {
        return data;
    }

    public static byte[] keyDecrypt2(Context context, byte[] key, byte[] data) {
        return data;
    }
}
