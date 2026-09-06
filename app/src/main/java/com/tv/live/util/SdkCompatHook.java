package com.tv.live.util;

import com.tv.live.util.LogBridge;

import java.lang.reflect.Field;

public final class SdkCompatHook {
    private static final String TAG = "SdkCompatHook";
    private static volatile boolean sNeutralized = false;

    private SdkCompatHook() {
    }

    public static void neutralizeCrashIfDebug() {
        if (sNeutralized) {
            return;
        }
        sNeutralized = true;
        try {
            Class<?> baseApi = Class.forName("com.huya.live.common.api.BaseApi");
            Field listener = baseApi.getDeclaredField("sOnCrashListener");
            listener.setAccessible(true);
            listener.set(null, null);
            LogBridge.i(TAG, "✅ 已中和 BaseApi.crashIfDebug（sOnCrashListener=null），SDK init 不再因非致命错误抛异常");
        } catch (Throwable t) {

            LogBridge.w(TAG, "⚠️ 中和 BaseApi.crashIfDebug 失败（不影响主流程）: " + t);
        }
    }
}
