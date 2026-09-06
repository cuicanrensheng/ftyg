package com.tv.live;

import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import com.tv.live.security.SecurityCore;

public final class UrlConfig {

    private static final String TAG = "UrlConfig";

    public static final String LIVE_1_URL = "https://gitee.com/qf_1111/iptv/raw/master/dfdvfaf.m3u";

    public static final String LIVE_2_URL = "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u";

    private static final String B_EPG_1  =
            "zZBnTSdOZW8g3DioT8rZiEwqv7K/TdIhF9s8x56Dh9WSVEZop4ruoPZnJY4MRpqO";
    private static final String B_EPG_2  =
            "BVdyAjq5u+rbwdBJG2GM10tMvJ4HKY7eC9CcKxTlJEPP9zOxQoHqCSLTHeOMxg8q";

    private static volatile String sEpg1, sEpg2;

    private UrlConfig() {}

    public static volatile String LIVE_URL_1_RAW = "";
    public static volatile String LIVE_URL_2_RAW = "";
    public static volatile String EPG_URL_1_RAW  = "";
    public static volatile String EPG_URL_2_RAW  = "";

    private static String d(String cipherB64) {

        String s = SecurityCore.decryptToString(cipherB64);
        if (s != null) return s;

        return null;
    }

    public static String getLiveUrl()  { return LIVE_1_URL; }
    public static String getLiveUrl2() { return LIVE_2_URL; }
    public static String getEpgUrl()   {
        String v = sEpg1;
        return v != null ? v : (sEpg1 = d(B_EPG_1));
    }
    public static String getEpgUrl2()  {
        String v = sEpg2;
        return v != null ? v : (sEpg2 = d(B_EPG_2));
    }

    public static String LIVE_URL   = "";
    public static String LIVE_URL_2 = "";
    public static String EPG_URL    = "";
    public static String EPG_URL_2  = "";

    public static void fillPublicFields() {
        LIVE_URL = LIVE_1_URL;
        if (TextUtils.isEmpty(LIVE_URL_1_RAW)) LIVE_URL_1_RAW = LIVE_1_URL;
        LogBridge.i(TAG, "LIVE_URL(源1): " + LIVE_1_URL);

        LIVE_URL_2 = LIVE_2_URL;
        if (TextUtils.isEmpty(LIVE_URL_2_RAW)) LIVE_URL_2_RAW = LIVE_2_URL;
        LogBridge.i(TAG, "LIVE_URL_2(源2): " + LIVE_2_URL);

        String e1 = getEpgUrl();   if (e1 != null) { EPG_URL    = e1; if (TextUtils.isEmpty(EPG_URL_1_RAW))  EPG_URL_1_RAW  = e1; }
        String e2 = getEpgUrl2();  if (e2 != null) { EPG_URL_2  = e2; if (TextUtils.isEmpty(EPG_URL_2_RAW))  EPG_URL_2_RAW  = e2; }

        LogBridge.i(TAG, "URL 配置解密完成，SecurityCore.isLoaded=" + SecurityCore.isLoaded() +
                   " | RAW[L1=" + (TextUtils.isEmpty(LIVE_URL_1_RAW) ? "null" : "ok") +
                   ", L2=" + (TextUtils.isEmpty(LIVE_URL_2_RAW) ? "null" : "ok") +
                   ", E1=" + (TextUtils.isEmpty(EPG_URL_1_RAW)  ? "null" : "ok") +
                   ", E2=" + (TextUtils.isEmpty(EPG_URL_2_RAW)  ? "null" : "ok") +
                   "]");
    }
}
