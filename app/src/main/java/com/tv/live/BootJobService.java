package com.tv.live;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import com.tv.live.util.LogBridge;

public class BootJobService extends JobService {

    private static final String TAG = "BootJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        LogBridge.d(TAG, "JobScheduler 触发启动");

        // 【2026-10-13 单飞闸门】与 Alarm / sticky 前台服务互斥，避免重复拉起重建 OTT 首页
        final boolean allowed = BootStartManager.allowForegroundStart(this);
        BootStartManager.noteLaunchSource(this, "JobScheduler(BootJobService)",
                "闸门放行=" + allowed);
        if (!allowed) {
            jobFinished(params, false);
            return false;
        }

        new Thread(() -> {
            boolean success = false;

            success = startActivityDirectly();
            if (success) {
                LogBridge.d(TAG, "JobScheduler 直接启动成功");
                jobFinished(params, false);
                return;
            }

            success = startActivityAsLauncher();
            if (success) {
                LogBridge.d(TAG, "JobScheduler Launcher 方式启动成功");
                jobFinished(params, false);
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                success = startWithForegroundService();
                if (success) {
                    LogBridge.d(TAG, "JobScheduler 前台服务方式启动成功");
                    jobFinished(params, false);
                    return;
                }
            }

            LogBridge.e(TAG, "JobScheduler 所有启动方案均失败");
            jobFinished(params, false);
        }).start();

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        LogBridge.w(TAG, "JobScheduler 任务被系统中断");

        return true;
    }

    private boolean startActivityDirectly() {
        try {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT);
            }
            startActivity(mainIntent);
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "直接启动失败", e);
            return false;
        }
    }

    private boolean startActivityAsLauncher() {
        try {
            Intent mainIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (mainIntent != null) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(mainIntent);
                return true;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "Launcher 方式启动失败", e);
        }
        return false;
    }

    private boolean startWithForegroundService() {
        try {
            Intent serviceIntent = new Intent(this, BootStartForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            LogBridge.d(TAG, "已启动前台服务兜底");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "前台服务启动失败", e);
            return false;
        }
    }
}
