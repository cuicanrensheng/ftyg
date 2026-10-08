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
    public static volatile String LIVE_URL_3_RAW = "";
    public static volatile String LIVE_URL_4_RAW = "";
    public static volatile String LIVE_URL_5_RAW = "";
    public static volatile String LIVE_URL_6_RAW = "";
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
        if (v != null) return v;
        // 第二套 EPG：erw.cc 的 gitee 镜像（518频道含CCTV1/卫视/地方台，channel+programme交替排列，兼容本解析器）
        sEpg2 = "https://gitee.com/taksssss/tv/raw/main/epg/erw.xml.gz";
        return sEpg2;
    }

    public static String LIVE_URL   = "";
    public static String LIVE_URL_2 = "";
    /** 内置源3：本地实时生成 M3U（央视/卫视，无需外部订阅） */
    public static String LIVE_URL_CHANXIANG = "chanxiang://all";
    /** 内置源4：影视直播源，随包打进 assets/live_source_4.txt，不依赖网络订阅 */
    public static String LIVE_URL_4 = "asset://live_source_4";
    /** 内置源5：爱其意(任意播)央视/卫视等 97 频道，加载时逐个向 live.php 取实时直链 */
    public static String LIVE_URL_5 = "renyibo://all";
    /**
     * 内置源6：咪咕视频直播（央视/卫视/地方/影视/少儿）。
     * 频道表来自咪咕官方频道目录（program-sc.miguvideo.com），地址写成 miguhd://&lt;pID&gt;，
     * 起播那一刻才用 MiguUrlResolver 实时解析成带签名的 m3u8（直链短时有效，不可预解析）。
     */
    public static String LIVE_URL_6 = "migu://all";
    public static String EPG_URL    = "";
    public static String EPG_URL_2  = "";

    public static void fillPublicFields() {
        // 【修复】这里在 init-urlconfig-early 后台线程执行，SecurityCore.init() 可达数秒，
        //         晚于 MainActivity.onCreate 恢复的用户选择（epg_history / live_history 的 isDefault）。
        //         若无条件赋值会把用户选的源（如 ERW EPG）覆盖回默认第一条（Catvod）。
        //         改为「仅当为空时才兜底」，保留 MainActivity 已恢复的用户选择值。
        if (TextUtils.isEmpty(LIVE_URL)) LIVE_URL = LIVE_1_URL;
        if (TextUtils.isEmpty(LIVE_URL_1_RAW)) LIVE_URL_1_RAW = LIVE_1_URL;
        LogBridge.i(TAG, "LIVE_URL(源1): " + LIVE_URL);

        if (TextUtils.isEmpty(LIVE_URL_2)) LIVE_URL_2 = LIVE_2_URL;
        if (TextUtils.isEmpty(LIVE_URL_2_RAW)) LIVE_URL_2_RAW = LIVE_2_URL;
        LogBridge.i(TAG, "LIVE_URL_2(源2): " + LIVE_URL_2);

        if (TextUtils.isEmpty(LIVE_URL_3_RAW)) LIVE_URL_3_RAW = LIVE_URL_CHANXIANG;
        LogBridge.i(TAG, "LIVE_URL_CHANXIANG(源3): " + LIVE_URL_CHANXIANG);

        if (TextUtils.isEmpty(LIVE_URL_4_RAW)) LIVE_URL_4_RAW = LIVE_URL_4;
        LogBridge.i(TAG, "LIVE_URL_4(源4): " + LIVE_URL_4);

        if (TextUtils.isEmpty(LIVE_URL_5_RAW)) LIVE_URL_5_RAW = LIVE_URL_5;
        LogBridge.i(TAG, "LIVE_URL_5(源5): " + LIVE_URL_5);

        if (TextUtils.isEmpty(LIVE_URL_6_RAW)) LIVE_URL_6_RAW = LIVE_URL_6;
        LogBridge.i(TAG, "LIVE_URL_6(源6): " + LIVE_URL_6);

        String e1 = getEpgUrl();   if (e1 != null) { if (TextUtils.isEmpty(EPG_URL)) EPG_URL = e1; if (TextUtils.isEmpty(EPG_URL_1_RAW)) EPG_URL_1_RAW = e1; }
        String e2 = getEpgUrl2();  if (e2 != null) { if (TextUtils.isEmpty(EPG_URL_2)) EPG_URL_2 = e2; if (TextUtils.isEmpty(EPG_URL_2_RAW)) EPG_URL_2_RAW = e2; }

        LogBridge.i(TAG, "URL 配置解密完成，SecurityCore.isLoaded=" + SecurityCore.isLoaded() +
                   " | RAW[L1=" + (TextUtils.isEmpty(LIVE_URL_1_RAW) ? "null" : "ok") +
                   ", L2=" + (TextUtils.isEmpty(LIVE_URL_2_RAW) ? "null" : "ok") +
                   ", E1=" + (TextUtils.isEmpty(EPG_URL_1_RAW)  ? "null" : "ok") +
                   ", E2=" + (TextUtils.isEmpty(EPG_URL_2_RAW)  ? "null" : "ok") +
                   "]");
    }
}
