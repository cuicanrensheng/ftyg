package com.tv.live.util;

import android.content.Context;
import com.tv.live.util.LogBridge;

public class HuyaCredentials {

    private static final String TAG = "HYC";

    private static final String KEY_GAME_ID = "huya_game_id";
    private static final String KEY_APP_ID = "huya_app_id";
    private static final String KEY_APP_KEY = "huya_app_key";

    private static final String XOR_KEY_STR = "90";

    private static final int ENCRYPTED_GAME_ID = 2061;
    private static final String ENCRYPTED_APP_ID = "khinol";
    private static final String ENCRYPTED_APP_KEY = ">b<kci>>";

    private static final int LEGACY_DEFAULT_GAME_ID = 2336;
    private static final int DEFAULT_GAME_ID = 2135;

    private EncryptedStorage encryptedStorage;
    private boolean initialized = false;

    private volatile int cachedGameId = -1;
    private volatile String cachedAppId = null;
    private volatile String cachedAppKey = null;

    private static volatile HuyaCredentials instance;

    public static HuyaCredentials getInstance(Context context) {
        if (instance == null) {
            synchronized (HuyaCredentials.class) {
                if (instance == null) {
                    instance = new HuyaCredentials(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private HuyaCredentials(Context context) {
        this.encryptedStorage = EncryptedStorage.getInstance(context);
        initialize(context);
    }

    private static int decodeGameId() {
        int xorKey = Integer.parseInt(XOR_KEY_STR);
        return ENCRYPTED_GAME_ID ^ xorKey;
    }

    private static String decodeAppId() {
        return decodeString(ENCRYPTED_APP_ID);
    }

    private static String decodeAppKey() {
        return decodeString(ENCRYPTED_APP_KEY);
    }

    private static String decodeString(String encoded) {
        int xorKey = Integer.parseInt(XOR_KEY_STR);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            sb.append((char)(encoded.charAt(i) ^ xorKey));
        }
        return sb.toString();
    }

    private void initialize(Context context) {
        try {
            if (!encryptedStorage.isInitialized()) {
                LogBridge.w(TAG, "加密存储未初始化");
                loadDefaultCredentials();
                initialized = true;
                return;
            }

            if (!encryptedStorage.contains(KEY_APP_ID)) {
                LogBridge.i(TAG, "首次初始化凭证");
                storeDefaultCredentials();
            }

            loadCredentials();
            initialized = true;
            LogBridge.i(TAG, "凭证初始化完成");
        } catch (Exception e) {
            LogBridge.e(TAG, "凭证初始化失败: " + e.getMessage());
            loadDefaultCredentials();
            initialized = true;
        }
    }

    private void loadDefaultCredentials() {
        cachedGameId = decodeGameId();
        cachedAppId = decodeAppId();
        cachedAppKey = decodeAppKey();
        LogBridge.i(TAG, "使用默认凭证");
    }

    private void storeDefaultCredentials() {
        encryptedStorage.putInt(KEY_GAME_ID, decodeGameId());
        encryptedStorage.putString(KEY_APP_ID, decodeAppId());
        encryptedStorage.putString(KEY_APP_KEY, decodeAppKey());
        LogBridge.i(TAG, "默认凭证已加密存储");
    }

    private void loadCredentials() {
        try {
            cachedGameId = encryptedStorage.getInt(KEY_GAME_ID, decodeGameId());

            if (cachedGameId == LEGACY_DEFAULT_GAME_ID) {
                LogBridge.i(TAG, "检测到旧默认 gameId=" + LEGACY_DEFAULT_GAME_ID
                        + "(王者荣耀)，迁移为 " + DEFAULT_GAME_ID + "(虎牙一起看)");
                cachedGameId = DEFAULT_GAME_ID;
                encryptedStorage.putInt(KEY_GAME_ID, DEFAULT_GAME_ID);
            }
            cachedAppId = encryptedStorage.getString(KEY_APP_ID, decodeAppId());
            cachedAppKey = encryptedStorage.getString(KEY_APP_KEY, decodeAppKey());

            if (cachedAppId == null || cachedAppKey == null) {
                LogBridge.w(TAG, "凭证不完整");
                cachedGameId = decodeGameId();
                cachedAppId = decodeAppId();
                cachedAppKey = decodeAppKey();
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "加载凭证失败: " + e.getMessage());
            cachedGameId = decodeGameId();
            cachedAppId = decodeAppId();
            cachedAppKey = decodeAppKey();
        }
    }

    public int getGameId() {
        return cachedGameId;
    }

    public String getAppId() {
        return cachedAppId;
    }

    public String getAppKey() {
        return cachedAppKey;
    }

    public void updateCredentials(Integer gameId, String appId, String appKey) {
        if (!encryptedStorage.isInitialized()) {
            LogBridge.e(TAG, "加密存储未初始化");
            return;
        }

        try {
            boolean changed = false;

            if (gameId != null && gameId != cachedGameId) {
                encryptedStorage.putInt(KEY_GAME_ID, gameId);
                cachedGameId = gameId;
                changed = true;
            }

            if (appId != null && !appId.equals(cachedAppId)) {
                encryptedStorage.putString(KEY_APP_ID, appId);
                cachedAppId = appId;
                changed = true;
            }

            if (appKey != null && !appKey.equals(cachedAppKey)) {
                encryptedStorage.putString(KEY_APP_KEY, appKey);
                cachedAppKey = appKey;
                changed = true;
            }

            if (changed) {
                LogBridge.i(TAG, "凭证已更新");
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "更新凭证失败: " + e.getMessage());
        }
    }

    public void resetToDefault() {
        try {
            storeDefaultCredentials();
            loadCredentials();
            LogBridge.i(TAG, "凭证已重置");
        } catch (Exception e) {
            LogBridge.e(TAG, "重置凭证失败: " + e.getMessage());
        }
    }

    public String getCredentialsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("gameId=").append(cachedGameId);
        sb.append(", appId=").append(maskString(cachedAppId));
        sb.append(", appKey=").append(maskString(cachedAppKey));
        return sb.toString();
    }

    private String maskString(String value) {
        if (value == null || value.isEmpty()) return "***";
        if (value.length() <= 4) return "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    public boolean isInitialized() {
        return initialized;
    }
}
