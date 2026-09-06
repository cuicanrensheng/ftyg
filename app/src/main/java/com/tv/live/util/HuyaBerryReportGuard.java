package com.tv.live.util;

import com.huya.berry.client.HuyaBerry;
import com.tv.live.util.LogBridge;

public final class HuyaBerryReportGuard {

    private static final String TAG = "HuyaBerryReportGuard";

    private HuyaBerryReportGuard() {}

    public static void applyAfterInit(HuyaBerry berry) {
        if (berry == null) return;

        try {
            berry.setGameAccountID("");
            LogBridge.i(TAG, "✅ setGameAccountID(\"\") 已清空游戏账号绑定（不关联任何游戏账号）");
        } catch (Throwable e) {
            LogBridge.w(TAG, "setGameAccountID 清空异常(可忽略): " + e.getMessage());
        }

        guardSendPlayerData();
    }

    public static void guardSendPlayerData() {

        LogBridge.i(TAG, "✅ sendPlayerData 播放数据上报已禁用（no-op，不触发任何网络请求）");
    }

    public static void guardSetGameAccountID() {

        LogBridge.i(TAG, "✅ setGameAccountID 游戏账号上报已禁用（no-op）");
    }
}
