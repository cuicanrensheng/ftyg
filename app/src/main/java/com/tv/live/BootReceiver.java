package com.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import com.tv.live.util.LogBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    private static final String ACTION_BOOT_COMPLETED = Intent.ACTION_BOOT_COMPLETED;
    private static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
    private static final String ACTION_MY_PACKAGE_REPLACED = Intent.ACTION_MY_PACKAGE_REPLACED;
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_POWER_CONNECTED = Intent.ACTION_POWER_CONNECTED;

    private static final String ACTION_SCREEN_ON = Intent.ACTION_SCREEN_ON;

    private static final String ACTION_USER_PRESENT = Intent.ACTION_USER_PRESENT;

    private static final String ACTION_MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";

    private static final String ACTION_MEDIA_EJECT = "android.intent.action.MEDIA_EJECT";

    private static final String ACTION_TIME_SET = "android.intent.action.TIME_SET";

    private static final String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";

    private static final String ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";

    private static final long START_DELAY_MS = 3000;
    private static final long SHORT_DELAY_MS = 1000;

    private static final long SCREEN_ON_DELAY_MS = 1500;

    private static final long MIN_START_INTERVAL_MS = 5 * 60 * 1000;
    private static final String SP_KEY_LAST_BOOT_START = "last_boot_start_time";

    private static final int BOOT_JOB_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            writeBootLog(context, "onReceive: context 或 intent 为 null");
            return;
        }

        String action = intent.getAction();
        LogBridge.d(TAG, "收到广播：" + action);
        writeBootLog(context, "收到广播 action=" + action);

        if (!isBootRelatedAction(action)) {
            LogBridge.d(TAG, "非开机相关广播，忽略：" + action);
            writeBootLog(context, "忽略非开机相关广播 action=" + action);
            return;
        }

        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean autoStart = sp.getBoolean("boot_auto_start", false);
        LogBridge.d(TAG, "开机自启开关状态：" + autoStart);
        writeBootLog(context, "开机相关广播 action=" + action + ", 自启开关=" + autoStart);
        if (!autoStart) {
            LogBridge.d(TAG, "用户未开启开机自启，不启动");
            writeBootLog(context, "自启开关未开启，放弃启动");
            return;
        }

        if (isRepeatableAction(action)) {
            long lastStart = sp.getLong(SP_KEY_LAST_BOOT_START, 0);
            long elapsed = SystemClock.elapsedRealtime() - lastStart;

            if (elapsed <= 0) {
                writeBootLog(context, "防重复：elapsed=" + elapsed + "ms（跨重启导致），重置时间戳并放行 action=" + action);
                sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();
            } else if (elapsed < MIN_START_INTERVAL_MS) {
                LogBridge.d(TAG, "距离上次启动仅 " + elapsed + "ms，跳过重复启动");
                writeBootLog(context, "防重复触发：距上次启动 " + elapsed + "ms，跳过 action=" + action);
                return;
            }
        }

        long delay = getDelayByAction(action);
        LogBridge.d(TAG, "延迟 " + delay + "ms 后启动应用");
        writeBootLog(context, "自启链路通过检查，准备延迟 " + delay + "ms 启动");

        scheduleDelayedStart(context, delay, sp);
    }

    private boolean isBootRelatedAction(String action) {
        if (action == null) return false;
        return action.equals(ACTION_BOOT_COMPLETED)
                || action.equals(ACTION_LOCKED_BOOT_COMPLETED)
                || action.equals(ACTION_MY_PACKAGE_REPLACED)
                || action.equals(ACTION_QUICKBOOT_POWERON)
                || action.equals(ACTION_QUICKBOOT_POWERON_HTC)
                || action.equals(ACTION_POWER_CONNECTED)
                || action.equals(ACTION_SCREEN_ON)
                || action.equals(ACTION_USER_PRESENT)
                || action.equals(ACTION_MEDIA_MOUNTED)
                || action.equals(ACTION_MEDIA_EJECT)
                || action.equals(ACTION_TIME_SET)
                || action.equals(ACTION_TIMEZONE_CHANGED)
                || action.equals(ACTION_CONNECTIVITY_CHANGE);
    }

    private boolean isRepeatableAction(String action) {
        return ACTION_SCREEN_ON.equals(action)
                || ACTION_USER_PRESENT.equals(action)
                || ACTION_POWER_CONNECTED.equals(action)
                || ACTION_MEDIA_MOUNTED.equals(action)
                || ACTION_MEDIA_EJECT.equals(action)
                || ACTION_TIME_SET.equals(action)
                || ACTION_TIMEZONE_CHANGED.equals(action)
                || ACTION_CONNECTIVITY_CHANGE.equals(action);
    }

    private long getDelayByAction(String action) {
        if (action == null) return START_DELAY_MS;
        if (ACTION_MY_PACKAGE_REPLACED.equals(action)) return SHORT_DELAY_MS;
        if (ACTION_SCREEN_ON.equals(action) || ACTION_USER_PRESENT.equals(action)) return SCREEN_ON_DELAY_MS;

        if (ACTION_CONNECTIVITY_CHANGE.equals(action)
                || ACTION_MEDIA_MOUNTED.equals(action)
                || ACTION_MEDIA_EJECT.equals(action)
                || ACTION_TIME_SET.equals(action)
                || ACTION_TIMEZONE_CHANGED.equals(action)) {
            return START_DELAY_MS;
        }
        return START_DELAY_MS;
    }

    private void scheduleDelayedStart(Context context, long delayMs, SharedPreferences sp) {

        sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();
        writeBootLog(context, "scheduleDelayedStart: 已记录防重复时间戳");

        boolean alarmScheduled = false;

        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                Intent startIntent = new Intent(context, BootStartReceiver.class);
                startIntent.setAction("com.tv.live.START_APP");

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context, 0, startIntent, flags);

                long triggerAt = SystemClock.elapsedRealtime() + delayMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                }
                LogBridge.d(TAG, "已设置 AlarmManager 延迟启动（ELAPSED_REALTIME_WAKEUP），" + delayMs + "ms 后启动");
                writeBootLog(context, "AlarmManager 设置成功，触发点=" + triggerAt + "ms，延迟=" + delayMs + "ms");
                alarmScheduled = true;
            } else {
                writeBootLog(context, "AlarmManager 为 null，无法设置延迟启动");
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "AlarmManager 设置失败", e);
            writeBootLog(context, "AlarmManager 设置失败: " + e);
        }

        if (!alarmScheduled || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                scheduleBootJob(context, delayMs);
            } catch (Exception e) {
                LogBridge.e(TAG, "JobScheduler 设置失败", e);
                writeBootLog(context, "JobScheduler 设置失败: " + e);
            }
        }

        if (!alarmScheduled) {
            LogBridge.w(TAG, "AlarmManager 不可用，尝试直接启动");
            writeBootLog(context, "AlarmManager 不可用，直接启动 MainActivity");
            startMainActivity(context);
        }
    }

    private void scheduleBootJob(Context context, long delayMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) return;

        jobScheduler.cancel(BOOT_JOB_ID);

        ComponentName componentName = new ComponentName(context, BootJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(BOOT_JOB_ID, componentName);

        builder.setMinimumLatency(delayMs);

        builder.setRequiresCharging(false);

        builder.setRequiresDeviceIdle(false);

        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPersisted(true);
        }

        int result = jobScheduler.schedule(builder.build());
        if (result == JobScheduler.RESULT_SUCCESS) {
            LogBridge.d(TAG, "JobScheduler 兜底任务已设置");
            writeBootLog(context, "JobScheduler 兜底任务已设置，delay=" + delayMs + "ms");
        } else {
            LogBridge.w(TAG, "JobScheduler 兜底任务设置失败");
            writeBootLog(context, "JobScheduler 兜底任务设置失败 result=" + result);
        }
    }

    private void startMainActivity(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.startActivity(mainIntent);
            LogBridge.d(TAG, "已启动 MainActivity");
            writeBootLog(context, "startActivity(MainActivity) 调用成功");
        } catch (Exception e) {
            LogBridge.e(TAG, "启动 MainActivity 失败", e);
            writeBootLog(context, "startActivity(MainActivity) 失败: " + e);
        }
    }

    public static void registerDynamic(Context context) {
        try {
            BootReceiver receiver = new BootReceiver();

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            filter.addAction("android.intent.action.LOCKED_BOOT_COMPLETED");
            filter.addAction(Intent.ACTION_MY_PACKAGE_REPLACED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction("android.intent.action.TIME_SET");
            filter.addAction("android.intent.action.TIMEZONE_CHANGED");
            filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
            context.registerReceiver(receiver, filter);

            IntentFilter mediaFilter = new IntentFilter();
            mediaFilter.addAction("android.intent.action.MEDIA_MOUNTED");
            mediaFilter.addAction("android.intent.action.MEDIA_EJECT");
            mediaFilter.addDataScheme("file");
            context.registerReceiver(receiver, mediaFilter);

            LogBridge.d(TAG, "动态开机兜底监听已注册（覆盖快速开机/SCREEN_ON 场景）");
            writeBootLog(context, "动态开机监听注册成功（SCREEN_ON/USER_PRESENT/POWER_CONNECTED/MEDIA_MOUNTED 等）");
        } catch (Exception e) {
            LogBridge.e(TAG, "动态注册开机监听失败", e);
            writeBootLog(context, "动态开机监听注册失败: " + e);
        }
    }

    private static final String BOOT_LOG_FILE = "boot_logs.txt";

    public static void writeBootLog(Context context, String message) {
        if (context == null) return;
        try {
            File file = new File(context.getFilesDir(), BOOT_LOG_FILE);
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date())
                    + " | " + message + "\n";
            FileOutputStream fos = new FileOutputStream(file, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    public static void trimBootLog(Context context) {
        if (context == null) return;
        try {
            File file = new File(context.getFilesDir(), BOOT_LOG_FILE);
            if (!file.exists() || file.length() < 200 * 1024) return;
            List<String> lines = new ArrayList<>();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();
            int start = Math.max(0, lines.size() - 500);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }
}
