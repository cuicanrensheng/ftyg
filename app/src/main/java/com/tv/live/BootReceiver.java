package com.tv.live;

import android.app.AlarmManager;
import android.annotation.SuppressLint;
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

    private static final String ACTION_SCREEN_ON = Intent.ACTION_SCREEN_ON;

    private static final String ACTION_USER_PRESENT = Intent.ACTION_USER_PRESENT;

    /**
     * 【2026-10-13 开机提速】原为 3000ms。
     *
     * <p>实测（创维 8N05_Q840 / Android 8）：开机 → 系统冷启动占 26.6s，
     * App 进程在 26.6s 被广播拉起，再等 3000ms 才拉起 MainActivity → 界面出现在 ~29.9s。
     * 这 3000ms 里 Application 初始化只占 ~0.3s，其余是纯等待，故下调到 1200ms。
     */
    private static final long START_DELAY_MS = 1200;
    private static final long SHORT_DELAY_MS = 1000;

    private static final long SCREEN_ON_DELAY_MS = 1500;

    private static final long MIN_START_INTERVAL_MS = 30 * 60 * 1000;
    private static final String SP_KEY_LAST_BOOT_START = "last_boot_start_time";

    /**
     * 【2026-10-14 黑屏修复】供 BootStartManager 在放行拉起时同步更新防重复时间戳。
     *
     * <p>背景：sticky 前台服务放行后，BOOT_COMPLETED 会在 ~200ms 后到达。
     * 若不同步更新，BOOT_COMPLETED 的 elapsed 仍为负数（跨重启），
     * 被判定为"跨重启"并放行，又设置 AlarmManager 延迟 1200ms
     * → 第二次启动 → Activity 重建 → 黑屏。
     */
    public static void updateLastBootStart(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static final int BOOT_JOB_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        // 【2026-10-14 详细日志】记录每次收到广播的完整信息
        long uptime = android.os.SystemClock.elapsedRealtime();
        String brand = android.os.Build.BRAND;
        String model = android.os.Build.MODEL;

        if (context == null || intent == null) {
            writeBootLog(context, "onReceive: context 或 intent 为 null | uptime=" + uptime + "ms");
            return;
        }

        String action = intent.getAction();
        String pkg = intent.getPackage();
        writeBootLog(context, "【广播入口】action=" + action + " | pkg=" + pkg
                + " | uptime=" + uptime + "ms | device=" + brand + "/" + model);

        if (!isBootRelatedAction(action)) {
            writeBootLog(context, "非开机相关广播，忽略：" + action);
            return;
        }

        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean autoStart = sp.getBoolean("boot_auto_start", false);
        if (!autoStart) {
            writeBootLog(context, "用户未开启开机自启，不启动");
            return;
        }

        if (isRepeatableAction(action)) {
            long lastStart = sp.getLong(SP_KEY_LAST_BOOT_START, 0);
            long elapsed = SystemClock.elapsedRealtime() - lastStart;

            if (elapsed <= 0) {
                writeBootLog(context, "防重复：elapsed=" + elapsed + "ms（跨重启导致），重置时间戳并放行 action=" + action);
                sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();
            } else if (elapsed < MIN_START_INTERVAL_MS) {
                writeBootLog(context, "距离上次启动仅 " + elapsed + "ms，跳过重复启动");
                return;
            }
        }

        long delay = getDelayByAction(action);
        writeBootLog(context, "延迟 " + delay + "ms 后启动应用");

        scheduleDelayedStart(context, delay, sp);
    }

    private boolean isBootRelatedAction(String action) {
        if (action == null) return false;
        // 【2026-10-14 精简广播】只保留真正的开机广播 + 创维电视兜底广播
        //   （SCREEN_ON/USER_PRESENT 是创维电视 BOOT_COMPLETED 被拦截后的兜底启动广播，
        //    必须保留；但已加入 30 分钟防重复，不会导致黑屏）
        //   （POWER_CONNECTED/MEDIA_MOUNTED/TIME_SET/CONNECTIVITY_CHANGE 这些多余广播已去掉）
        return action.equals(ACTION_BOOT_COMPLETED)
                || action.equals(ACTION_LOCKED_BOOT_COMPLETED)
                || action.equals(ACTION_QUICKBOOT_POWERON)
                || action.equals(ACTION_QUICKBOOT_POWERON_HTC)
                || action.equals(ACTION_MY_PACKAGE_REPLACED)
                || action.equals(ACTION_SCREEN_ON)
                || action.equals(ACTION_USER_PRESENT);
    }

    private boolean isRepeatableAction(String action) {
        // 【2026-10-14 修复开机自启后重启】把所有开机相关广播都加入防重复列表，
        // 避免 BOOT_COMPLETED + LOCKED_BOOT_COMPLETED + QUICKBOOT_POWERON 同时触发多次启动
        return ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || ACTION_QUICKBOOT_POWERON_HTC.equals(action)
                || ACTION_MY_PACKAGE_REPLACED.equals(action)
                || ACTION_SCREEN_ON.equals(action)
                || ACTION_USER_PRESENT.equals(action);
    }

    private long getDelayByAction(String action) {
        if (action == null) return START_DELAY_MS;
        if (ACTION_MY_PACKAGE_REPLACED.equals(action)) return SHORT_DELAY_MS;
        if (ACTION_SCREEN_ON.equals(action) || ACTION_USER_PRESENT.equals(action)) return SCREEN_ON_DELAY_MS;
        return START_DELAY_MS;
    }

    private void scheduleDelayedStart(Context context, long delayMs, SharedPreferences sp) {

        sp.edit().putLong(SP_KEY_LAST_BOOT_START, SystemClock.elapsedRealtime()).apply();
        writeBootLog(context, "【启动计划】delay=" + delayMs + "ms | uptime="
                + android.os.SystemClock.elapsedRealtime() + "ms");

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
                alarmScheduled = true;
            } else {
                writeBootLog(context, "AlarmManager 为 null，无法设置延迟启动");
            }
        } catch (Exception e) {
            writeBootLog(context, "AlarmManager 设置失败" + e);
        }

        if (!alarmScheduled || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                scheduleBootJob(context, delayMs);
            } catch (Exception e) {
                writeBootLog(context, "JobScheduler 设置失败" + e);
            }
        }

        if (!alarmScheduled) {
            writeBootLog(context, "AlarmManager 不可用，尝试直接启动");
            startMainActivity(context);
        }
    }

    @SuppressLint("ObsoleteSdkInt")
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
            writeBootLog(context, "JobScheduler 兜底任务已设置");
        } else {
            writeBootLog(context, "JobScheduler 兜底任务设置失败");
        }
    }

    private void startMainActivity(Context context) {
        // 【2026-10-13 单飞闸门】与 JobScheduler / sticky 前台服务互斥，避免重复拉起重建 OTT 首页
        final boolean allowed = BootStartManager.allowForegroundStart(context);
        BootStartManager.noteLaunchSource(context, "BootReceiver直连", "闸门放行=" + allowed);
        if (!allowed) {
            return;
        }
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.startActivity(mainIntent);
            writeBootLog(context, "已启动 MainActivity");
        } catch (Exception e) {
            writeBootLog(context, "启动 MainActivity 失败" + e);
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
            // 【修复】Android 13+ 需要显式指定导出标志
            // 这个接收器只监听系统广播，不需要外部应用发送，用 NOT_EXPORTED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }

            writeBootLog(context, "动态开机兜底监听已注册（覆盖快速开机/SCREEN_ON 场景）");
        } catch (Exception e) {
            writeBootLog(context, "动态注册开机监听失败" + e);
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
            int start2 = Math.max(0, lines.size() - 500);
            StringBuilder sb = new StringBuilder();
            for (int i = start2; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }
}
