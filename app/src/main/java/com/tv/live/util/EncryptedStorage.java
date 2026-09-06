package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.tv.live.util.LogBridge;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptedStorage {

    private static final String TAG = "EncryptedStorage";

    private static final String PREFS_NAME = "encrypted_prefs";
    private static final String KEY_ALIAS = "tv_live_encryption_key";

    private static final String KEY_SALT = "key_salt";
    private static final String KEY_Version = "key_version";

    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private Context context;
    private SharedPreferences prefs;
    private SecretKey secretKey;
    private boolean initialized = false;

    private static volatile EncryptedStorage instance;

    public static EncryptedStorage getInstance(Context context) {
        if (instance == null) {
            synchronized (EncryptedStorage.class) {
                if (instance == null) {
                    instance = new EncryptedStorage(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private EncryptedStorage(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initialize();
    }

    private void initialize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    generateOrLoadKey();
                } catch (Exception keystoreError) {
                    LogBridge.w(TAG, "Android Keystore 不可用，降级到旧版本方案: " + keystoreError.getMessage());
                    generateLegacyKey();
                }
            } else {

                generateLegacyKey();
            }
            initialized = true;
            LogBridge.i(TAG, "✅ EncryptedStorage 初始化成功");
        } catch (Exception e) {
            LogBridge.e(TAG, "❌ EncryptedStorage 初始化失败: " + e.getMessage());
        }
    }

    private void generateOrLoadKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");

            keyGenerator.init(
                    new KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setUserAuthenticationRequired(false)
                            .build());

            keyGenerator.generateKey();
            LogBridge.i(TAG, "✅ 生成新的 AES-256 密钥");
        }

        KeyStore.SecretKeyEntry keyEntry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
        secretKey = keyEntry.getSecretKey();
        LogBridge.i(TAG, "✅ 加载 AES-256 密钥成功");
    }

    private void generateLegacyKey() throws Exception {
        String existingSalt = prefs.getString(KEY_SALT, null);
        byte[] salt;

        if (existingSalt == null) {
            salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply();
        } else {
            salt = Base64.decode(existingSalt, Base64.NO_WRAP);
        }

        String deviceKey = getDeviceKeyMaterial();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] keyBytes = digest.digest(deviceKey.getBytes("UTF-8"));

        secretKey = new SecretKeySpec(keyBytes, "AES");
        LogBridge.i(TAG, "✅ 使用降级方案生成密钥（安全性较低）");
    }

    private String getDeviceKeyMaterial() {
        StringBuilder sb = new StringBuilder();

        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            sb.append(androidId != null ? androidId : "unknown");
        } catch (Exception e) {
            sb.append("unknown");
        }
        sb.append(android.os.Build.MODEL);
        sb.append(android.os.Build.BRAND);
        sb.append(context.getPackageName());

        return sb.toString();
    }

    public String encrypt(String key, String plainText) {
        if (!initialized || secretKey == null) {
            LogBridge.e(TAG, "加密存储未初始化");
            return plainText;
        }

        try {

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();

            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes("UTF-8"));

            String encrypted = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                             Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

            prefs.edit().putString(key, encrypted).apply();

            return encrypted;
        } catch (Exception e) {
            LogBridge.w(TAG, "加密失败: " + e.getMessage());
            return plainText;
        }
    }

    public String decrypt(String key) {
        if (!initialized || secretKey == null) {
            LogBridge.e(TAG, "加密存储未初始化");
            return null;
        }

        String encrypted = prefs.getString(key, null);
        if (encrypted == null) {
            return null;
        }

        try {

            String[] parts = encrypted.split(":");
            if (parts.length != 2) {
                LogBridge.e(TAG, "密文格式错误");
                return null;
            }

            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, "UTF-8");
        } catch (Exception e) {
            LogBridge.e(TAG, "解密失败（可能密钥已失效）: " + e.getMessage());

            prefs.edit().remove(key).apply();
            return null;
        }
    }

    public void putString(String key, String value) {
        encrypt(key, value);
    }

    public String getString(String key, String defaultValue) {
        String result = decrypt(key);
        return result != null ? result : defaultValue;
    }

    public void putInt(String key, int value) {
        encrypt(key, String.valueOf(value));
    }

    public int getInt(String key, int defaultValue) {
        String result = decrypt(key);
        if (result == null) return defaultValue;
        try {
            return Integer.parseInt(result);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    public boolean contains(String key) {
        return prefs.contains(key);
    }

    public void clearAll() {
        prefs.edit().clear().apply();
        LogBridge.i(TAG, "已清除所有加密存储数据");
    }

    public boolean isInitialized() {
        return initialized && secretKey != null;
    }

    public boolean isKeyValid() {
        if (!initialized || secretKey == null) return false;

        try {

            String testData = "key_validation_test";
            String encrypted = encrypt("__key_validation__", testData);
            String decrypted = decrypt("__key_validation__");
            prefs.edit().remove("__key_validation__").apply();

            return testData.equals(decrypted);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean regenerateKey() {
        try {
            clearAll();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS);
                }
                generateOrLoadKey();
            } else {

                prefs.edit().remove(KEY_SALT).apply();
                generateLegacyKey();
            }

            LogBridge.i(TAG, "✅ 密钥已重新生成");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "❌ 密钥重新生成失败: " + e.getMessage());
            return false;
        }
    }
}
