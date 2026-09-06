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

        boolean success = startMainActivity();

        if (!success) {

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            startMainActivity();
        }

        return START_STICKY;
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
