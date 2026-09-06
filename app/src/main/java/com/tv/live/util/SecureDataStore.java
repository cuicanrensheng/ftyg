package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
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

public class SecureDataStore {

    private static final String TAG = "SecureDataStore";
    private static final String PREFS_NAME = "secure_data_store";
    private static final String KEY_ALIAS = "secure_data_key_v2";
    private static final String KEY_VERSION_PREF = "key_version";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private static volatile SecureDataStore sInstance;
    private Context mContext;
    private SharedPreferences mPrefs;
    private SecretKey mSecretKey;
    private boolean mInitialized = false;

    private final java.util.Map<String, Object> mCache = new java.util.HashMap<>();
    private final java.util.Map<String, Boolean> mCacheValid = new java.util.HashMap<>();

    private SecureDataStore() {
    }

    public static SecureDataStore getInstance() {
        if (sInstance == null) {
            synchronized (SecureDataStore.class) {
                if (sInstance == null) {
                    sInstance = new SecureDataStore();
                }
            }
        }
        return sInstance;
    }

    public synchronized void init(Context context) {
        if (mInitialized) {
            return;
        }

        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        try {
            initializeKey();
            mInitialized = true;
            LogBridge.i(TAG, "安全存储初始化完成");
        } catch (Exception e) {
            LogBridge.e(TAG, "安全存储初始化失败: " + e.getMessage());
        }
    }

    private void initializeKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {

            generateKey();
        }

        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(
            KEY_ALIAS, null);
        mSecretKey = entry.getSecretKey();

        LogBridge.d(TAG, "加密密钥已就绪");
    }

    private void generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

        keyGenerator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build());

        keyGenerator.generateKey();
        LogBridge.d(TAG, "新加密密钥已生成");
    }

    public void putString(String key, String value) {
        if (!mInitialized || value == null) {
            return;
        }

        try {
            String encryptedValue = encrypt(value);
            mPrefs.edit()
                .putString(key, encryptedValue)
                .apply();

            mCache.put(key, value);
            mCacheValid.put(key, true);

            LogBridge.d(TAG, "已存储加密值: " + key);
        } catch (Exception e) {
            LogBridge.e(TAG, "存储失败 [" + key + "]: " + e.getMessage());
        }
    }

    public String getString(String key, String defaultValue) {
        if (!mInitialized) {
            return defaultValue;
        }

        if (mCache.containsKey(key) && mCacheValid.get(key)) {
            return (String) mCache.get(key);
        }

        try {
            String encryptedValue = mPrefs.getString(key, null);
            if (encryptedValue == null) {
                return defaultValue;
            }

            String decryptedValue = decrypt(encryptedValue);
            if (decryptedValue != null) {

                mCache.put(key, decryptedValue);
                mCacheValid.put(key, true);
                return decryptedValue;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "读取失败 [" + key + "]: " + e.getMessage());
        }

        return defaultValue;
    }

    public void putInt(String key, int value) {
        putString(key + "_int", String.valueOf(value));
    }

    public int getInt(String key, int defaultValue) {
        String strValue = getString(key + "_int", null);
        if (strValue != null) {
            try {
                return Integer.parseInt(strValue);
            } catch (NumberFormatException e) {

            }
        }
        return defaultValue;
    }

    public void putBoolean(String key, boolean value) {
        putString(key + "_bool", String.valueOf(value));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String strValue = getString(key + "_bool", null);
        if (strValue != null) {
            return "true".equals(strValue);
        }
        return defaultValue;
    }

    public void remove(String key) {
        if (!mInitialized) {
            return;
        }

        mPrefs.edit().remove(key).apply();
        mPrefs.edit().remove(key + "_int").apply();
        mPrefs.edit().remove(key + "_bool").apply();
        mCache.remove(key);
        mCache.remove(key + "_int");
        mCache.remove(key + "_bool");
        mCacheValid.remove(key);
        LogBridge.d(TAG, "已删除: " + key);
    }

    public void clearAll() {
        if (!mInitialized) {
            return;
        }

        mPrefs.edit().clear().apply();
        mCache.clear();
        mCacheValid.clear();
        LogBridge.i(TAG, "已清除所有安全存储数据");
    }

    public boolean contains(String key) {
        return mPrefs.contains(key) ||
               mPrefs.contains(key + "_int") ||
               mPrefs.contains(key + "_bool");
    }

    private String encrypt(String plaintext) throws Exception {

        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/" +
            KeyProperties.BLOCK_MODE_GCM + "/" +
            KeyProperties.ENCRYPTION_PADDING_NONE);

        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, mSecretKey, gcmSpec);

        byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes("UTF-8"));

        byte[] combined = new byte[iv.length + encryptedBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String decrypt(String encryptedText) throws Exception {
        byte[] combined = Base64.decode(encryptedText, Base64.NO_WRAP);

        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] encryptedBytes = new byte[combined.length - GCM_IV_LENGTH];

        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, encryptedBytes, 0, encryptedBytes.length);

        Cipher cipher = Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/" +
            KeyProperties.BLOCK_MODE_GCM + "/" +
            KeyProperties.ENCRYPTION_PADDING_NONE);

        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, mSecretKey, gcmSpec);

        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public static String calculateHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes("UTF-8"));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public void clearCache() {
        mCache.clear();
        mCacheValid.clear();
        LogBridge.d(TAG, "内存缓存已清除");
    }
}
