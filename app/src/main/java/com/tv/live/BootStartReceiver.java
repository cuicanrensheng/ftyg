package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.tv.live.util.LogBridge;

public class BootStartReceiver extends BroadcastReceiver {

    private static final String TAG = "BootStartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        LogBridge.d(TAG, "收到启动广播：" + action);

        if (!"com.tv.live.START_APP".equals(action)) {
            return;
        }

        // 【2026-10-13 单飞闸门】与 JobScheduler / sticky 前台服务互斥：
        // 开机阶段这几条路径会在几秒内互相重复拉起，而 MainActivity 跳转 OTT 首页后会
        // finish 自己、OTT 首页又是 standard 启动模式 → 每次拉起都重建整条界面，
        // 用户观感就是「频道刚出来，界面又自己刷新加载了一遍」。
        final boolean allowed = BootStartManager.allowForegroundStart(context);
        BootStartManager.noteLaunchSource(context, "Alarm(BootStartReceiver)",
                "action=" + action + " | 闸门放行=" + allowed);
        if (!allowed) {
            return;
        }

        LogBridge.d(TAG, "开始启动应用...");

        boolean success = startActivityDirectly(context);

        if (success) {
            LogBridge.d(TAG, "方案 1 成功：直接启动 Activity");
            return;
        }

        LogBridge.w(TAG, "方案 1 失败，尝试方案 2：特殊标志启动");
        success = startActivityWithSpecialFlags(context);

        if (success) {
            LogBridge.d(TAG, "方案 2 成功：特殊标志启动");
            return;
        }

        LogBridge.w(TAG, "方案 2 失败，尝试方案 3：Launcher 方式启动");
        success = startActivityAsLauncher(context);

        if (success) {
            LogBridge.d(TAG, "方案 3 成功：Launcher 方式启动");
            return;
        }

        LogBridge.e(TAG, "所有方案都失败，启动应用失败");
    }

    private boolean startActivityDirectly(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT);
            }

            context.startActivity(mainIntent);
            LogBridge.d(TAG, "直接启动 Activity 成功");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "直接启动 Activity 失败", e);
            return false;
        }
    }

    private boolean startActivityWithSpecialFlags(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            mainIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

            context.startActivity(mainIntent);
            LogBridge.d(TAG, "特殊标志启动成功");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "特殊标志启动失败", e);
            return false;
        }
    }

    private boolean startActivityAsLauncher(Context context) {
        try {
            Intent mainIntent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());

            if (mainIntent != null) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(mainIntent);
                LogBridge.d(TAG, "Launcher 方式启动成功");
                return true;
            } else {
                LogBridge.e(TAG, "获取 Launcher Intent 失败");
                return false;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "Launcher 方式启动失败", e);
            return false;
        }
    }
}
