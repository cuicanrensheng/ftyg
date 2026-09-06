package com.tv.live;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import com.tv.live.util.LogBridge;
import android.view.WindowManager;

import com.tv.live.util.LogCollector;
import com.tv.live.util.LogServer;

import io.reactivex.exceptions.UndeliverableException;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressLint("StaticFieldLeak")
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    private static CrashHandler instance;

    private Context context;

    private Thread.UncaughtExceptionHandler defaultHandler;

    private static final String CRASH_DIR_NAME = "crash_logs";

    private static final int MAX_CRASH_LOG_COUNT = 10;

    private static final String CRASH_FILE_PREFIX = "crash_";

    private static final String CRASH_FILE_SUFFIX = ".txt";

    public static volatile String CRASH_LOG = "";

    private static final long CRASH_PAGE_DISPLAY_DURATION = 60 * 1000;

    private boolean autoRestartEnabled = false;

    private static final long RESTART_DELAY = 1000;

    private CrashHandler() {}

    public static CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    public void init(Context ctx) {
        context = ctx.getApplicationContext();

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(this);

        LogBridge.d(TAG, "全局崩溃捕获器已初始化");
        LogBridge.d(TAG, "崩溃日志保存目录：" + getCrashDir().getAbsolutePath());
        LogBridge.d(TAG, "崩溃页面显示时长：" + CRASH_PAGE_DISPLAY_DURATION / 1000 + " 秒");
        LogBridge.d(TAG, "自动重启：" + (autoRestartEnabled ? "已开启" : "已关闭"));
    }

    public void setAutoRestartEnabled(boolean enabled) {
        this.autoRestartEnabled = enabled;
        LogBridge.d(TAG, "自动重启已" + (enabled ? "开启" : "关闭"));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {

            if (isIgnoredMarsLinkError(ex)) {
                LogBridge.e(TAG, "🛡️【mars 黑名单】忽略已知 SO 不兼容异常: " + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ex.printStackTrace(pw);
                    pw.close();
                    LogCollector.getInstance().error("CrashHandler", "mars_ignored:" + sw.toString());
                    if (context != null) {
                        LogServer.getInstance(context).sendCrashLog(
                                "【mars 黑名单免疫】\n" + sw.toString());
                    }
                } catch (Throwable ignored) {
                }
                return;
            }

            String crashLog = buildCrashLog(thread, ex);

            CRASH_LOG = crashLog;
            LogBridge.e(TAG, crashLog);

            saveCrashLogToFile(crashLog);

            try {
                LogBridge.e(TAG, "【崩溃】" + ex.getClass().getName() + ": " + ex.getMessage());
                LogBridge.e(TAG, "【崩溃】详细日志已保存到文件");
                LogBridge.e(TAG, "【崩溃】崩溃页面将显示 " + (CRASH_PAGE_DISPLAY_DURATION / 1000) + " 秒");

                LogCollector.getInstance().crash("CrashHandler",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());

                if (context != null) {
                    LogServer.getInstance(context).sendCrashLog(crashLog);
                }
            } catch (Exception ignored) {}

            startCrashActivity();

            new Thread(() -> {
                try {
                    Thread.sleep(CRASH_PAGE_DISPLAY_DURATION);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                if (autoRestartEnabled) {
                    restartApp();
                }

                Process.killProcess(Process.myPid());
                System.exit(1);
            }, "crash-handler").start();

        } catch (Exception e) {
            LogBridge.e(TAG, "崩溃处理失败", e);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        }
    }

    private String buildCrashLog(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder();

        sb.append("================ 崩溃日志 ================\n");
        sb.append("时间：").append(getCurrentTime()).append("\n");
        sb.append("线程：").append(thread.getName()).append(" (ID: ").append(thread.getId()).append(")\n");
        sb.append("异常类型：").append(ex.getClass().getName()).append("\n");
        sb.append("异常信息：").append(ex.getMessage()).append("\n");

        sb.append("\n========== 设备信息 ==========\n");
        sb.append("品牌：").append(Build.BRAND).append("\n");
        sb.append("型号：").append(Build.MODEL).append("\n");
        sb.append("产品：").append(Build.PRODUCT).append("\n");
        sb.append("系统版本：Android ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK版本：").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("构建版本：").append(Build.DISPLAY).append("\n");
        sb.append("CPU架构：").append(Build.SUPPORTED_ABIS[0]).append("\n");

        sb.append("\n========== APP信息 ==========\n");
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            sb.append("包名：").append(pi.packageName).append("\n");
            sb.append("版本名：").append(pi.versionName).append("\n");
            sb.append("版本号：").append(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)).append("\n");
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("包名：").append(context.getPackageName()).append("\n");
            sb.append("版本信息：获取失败\n");
        }

        sb.append("\n========== 屏幕信息 ==========\n");
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);
            sb.append("分辨率：").append(metrics.widthPixels).append(" x ").append(metrics.heightPixels).append("\n");
            sb.append("密度：").append(metrics.densityDpi).append("dpi\n");
            sb.append("缩放比例：").append(metrics.density).append("\n");
        } catch (Exception e) {
            sb.append("屏幕信息：获取失败\n");
        }

        sb.append("\n========== 堆栈信息 ==========\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.close();
        sb.append(sw.toString());

        sb.append("\n========== 提示 ==========\n");
        if (autoRestartEnabled) {
            sb.append("页面将在 ").append(CRASH_PAGE_DISPLAY_DURATION / 1000).append(" 秒后自动重启应用\n");
        } else {
            sb.append("页面将在 ").append(CRASH_PAGE_DISPLAY_DURATION / 1000).append(" 秒后自动关闭\n");
        }
        sb.append("详细日志已保存到本地文件，可在设置页面查看\n");

        sb.append("\n========================================\n");

        return sb.toString();
    }

    private void saveCrashLogToFile(String crashLog) {
        try {

            File crashDir = getCrashDir();
            if (!crashDir.exists()) {
                crashDir.mkdirs();
            }

            String fileName = CRASH_FILE_PREFIX
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                    + CRASH_FILE_SUFFIX;

            File crashFile = new File(crashDir, fileName);

            FileWriter writer = new FileWriter(crashFile);
            writer.write(crashLog);
            writer.flush();
            writer.close();

            LogBridge.d(TAG, "崩溃日志已保存：" + crashFile.getAbsolutePath());

            cleanOldCrashLogs();

        } catch (Exception e) {
            LogBridge.e(TAG, "保存崩溃日志到文件失败", e);
        }
    }

    private void cleanOldCrashLogs() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();

            if (files == null || files.length <= MAX_CRASH_LOG_COUNT) {
                return;
            }

            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });

            for (int i = MAX_CRASH_LOG_COUNT; i < fileList.size(); i++) {
                File oldFile = fileList.get(i);
                if (oldFile.delete()) {
                    LogBridge.d(TAG, "已删除旧的崩溃日志：" + oldFile.getName());
                }
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "清理旧崩溃日志失败", e);
        }
    }

    private void restartApp() {
        try {

            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + RESTART_DELAY,
                        pendingIntent
                );
                LogBridge.d(TAG, "已设置自动重启，" + RESTART_DELAY + "ms 后启动");
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "设置自动重启失败", e);
        }
    }

    private void startCrashActivity() {
        try {
            Intent intent = new Intent(context, CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            LogBridge.d(TAG, "已启动崩溃页面");
        } catch (Exception e) {
            LogBridge.e(TAG, "启动崩溃页面失败", e);
        }
    }

    private File getCrashDir() {
        return new File(context.getFilesDir(), CRASH_DIR_NAME);
    }

    private String getCurrentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    public List<File> getCrashLogList() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();

            if (files == null || files.length == 0) {
                return new ArrayList<>();
            }

            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });

            return fileList;

        } catch (Exception e) {
            LogBridge.e(TAG, "获取崩溃日志列表失败", e);
            return new ArrayList<>();
        }
    }

    public String getLatestCrashLog() {
        List<File> list = getCrashLogList();
        if (list.isEmpty()) {
            return null;
        }

        try {
            File latestFile = list.get(0);
            return readFileToString(latestFile);
        } catch (Exception e) {
            LogBridge.e(TAG, "读取最新崩溃日志失败", e);
            return null;
        }
    }

    public int clearAllCrashLogs() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();

            if (files == null || files.length == 0) {
                return 0;
            }

            int count = 0;
            for (File file : files) {
                if (file.delete()) {
                    count++;
                }
            }

            LogBridge.d(TAG, "已清空 " + count + " 个崩溃日志");
            return count;

        } catch (Exception e) {
            LogBridge.e(TAG, "清空崩溃日志失败", e);
            return 0;
        }
    }

    private boolean isIgnoredMarsLinkError(Throwable ex) {
        if (ex == null) return false;
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 10) {
            if (cur instanceof UnsatisfiedLinkError || cur instanceof NoSuchMethodError) {
                String msg = (cur.getMessage() == null ? "" : cur.getMessage())
                        + " " + stackToString(cur);
                if (msg.contains("mars") || msg.contains("StnLogic")
                        || msg.contains("libmarsstn") || msg.contains("MtpMarsTransporter")) {
                    return true;
                }
            }

            if (cur instanceof UndeliverableException) {
                cur = cur.getCause();
                depth++;
                continue;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private String stackToString(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.close();
            return sw.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String readFileToString(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            fis.close();
            return new String(buffer);
        } catch (Exception e) {
            LogBridge.e(TAG, "读取文件失败：" + file.getAbsolutePath(), e);
            return null;
        }
    }
}
