package com.tv.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import com.tv.live.util.LogBridge;

public class BootStartForegroundService extends Service {

    private static final String TAG = "BootStartFgService";
    private static final String CHANNEL_ID = "boot_start_channel";
    private static final int NOTIFICATION_ID = 2001;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogBridge.d(TAG, "保活服务启动(sticky)，尝试拉起 MainActivity");

        // 【2026-10-13 单飞闸门】sticky 服务被反复重启时不再重复拉起前台，
        // 否则每次 onStartCommand 都会重建整条 OTT 界面（观感＝界面自己刷新了一遍）。
        boolean success = startMainActivity();

        if (!success) {

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            startMainActivity();
        }

        // 【2026-10-13 自启兜底】开机窗口内排一次"到前台确认"，防被 Launcher/清理顶掉
        scheduleForegroundRecovery();

        return START_STICKY;
    }

    // ------------------------------------------------------------------
    // 【2026-10-13 自启兜底】"到前台确认"
    //   开机阶段 App 被拉起后，可能又被系统 Launcher 顶掉（或厂商清理杀掉），
    //   用户看到的就是"自启偶尔失效"。这里在 +5s / +10s 各确认一次：
    //   只要发现不在前台就补拉一次（每轮最多 {@link BootStartManager#MAX_RECOVERY_PER_BOOT} 次）；
    //   已在前台则完全不动作，不会重建 OTT 首页。
    //   只在"开机窗口"内启用（uptime < 120s），避免用户平时手动开 App 后被误补拉。
    // ------------------------------------------------------------------
    private static final long BOOT_WINDOW_MS = 120_000L;

    private static final java.util.concurrent.atomic.AtomicBoolean sRecoveryScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void scheduleForegroundRecovery() {
        if (android.os.SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) {
            return;                     // 非开机窗口，不打扰
        }
        if (!sRecoveryScheduled.compareAndSet(false, true)) {
            return;                     // onStartCommand 可能被多次调用，只排一次
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 2; i++) {
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (BootStartManager.isSelfForegroundSafe(BootStartForegroundService.this)) {
                            LogBridge.i(TAG, "【自启兜底】已确认 App 在前台，结束确认");
                            return;
                        }
                        if (!BootStartManager.allowRecoveryStart(BootStartForegroundService.this)) {
                            return;
                        }
                        startMainActivityForce();
                    } catch (Throwable t) {
                        LogBridge.w(TAG, "【自启兜底】确认异常: " + t.getMessage());
                    }
                }
            }
        }, "boot-fg-recovery").start();
    }

    /** 补拉用（闸门已在 {@link BootStartManager#allowRecoveryStart} 判过，此处不再过时间窗） */
    private boolean startMainActivityForce() {
        try {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(mainIntent);
            LogBridge.i(TAG, "【自启兜底】补拉 MainActivity 成功");
            BootStartManager.noteLaunchSource(this, "开机窗口补拉", "startActivity 成功");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "【自启兜底】补拉 MainActivity 失败", e);
            BootStartManager.noteLaunchSource(this, "开机窗口补拉", "startActivity 失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "开机自启",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于提高开机自启成功率");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(getString(R.string.app_name))
                .setContentText("后台保活运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setAutoCancel(true);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private boolean startMainActivity() {
        // 【2026-10-13 单飞闸门】sticky 服务被反复重启 / 被 Alarm+Job 同时触发时，
        // 不再重复拉起前台 —— 否则每次都会重建整条 OTT 界面。
        // 【2026-10-13 第二轮】必须按「服务来源」过闸门：App 已在前台时（例如用户在设置里
        // 开关「开机自启」触发本服务）绝不能再 startActivity，否则重绑 Surface → 黑屏。
        final boolean allowed = BootStartManager.allowForegroundStart(
                this, BootStartManager.SRC_SERVICE);
        BootStartManager.noteLaunchSource(this, "sticky前台服务", "闸门放行=" + allowed);
        if (!allowed) {
            return false;
        }
        try {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT);
            }
            startActivity(mainIntent);
            LogBridge.d(TAG, "从前台服务启动 MainActivity 成功");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "从前台服务启动 MainActivity 失败", e);
            return false;
        }
    }
}
