package com.tv.live;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import com.tv.live.util.LogBridge;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class BootStartManager {

    private static final String TAG = "BootStartManager";
    private static final String KEY_BOOT_AUTO_START = "boot_auto_start";

    // ------------------------------------------------------------------
    // 【2026-10-13 单飞闸门】修复「频道刚出来又整个刷新一遍」
    //
    //   现象：开机自启后 OTT 首页会重复重建 4~5 次，用户观感是"界面自己刷新了一遍"。
    //   成因：
    //     ① 拉起路径有多条且互不协调 —— AlarmManager(两个开机 action 各排一次)、
    //        JobScheduler 兜底、BootStartForegroundService(sticky)；
    //     ② MainActivity 是「跳转 OTT 首页后 finish 自己」的路由页，且 MainActivity 与
    //        OttHomeActivity 都没配 launchMode（默认 standard）→ 每次拉起都是全新实例，
    //        整条 OTT 界面被销毁重建（频道列表清空 → 重新加载）。
    //   对策：所有「拉起前台」的路径先过这道闸门，同一进程 30s 窗口内只真正拉起一次。
    // ------------------------------------------------------------------
    /** 触发来源：开机广播 / 闹钟 / Job —— 明确的「自启」意图 */
    public static final int SRC_BOOT = 0;
    /** 触发来源：sticky 前台服务 —— 职责只是保活，"App 已在前台"时绝不该再拉 */
    public static final int SRC_SERVICE = 1;

    private static final long START_SINGLEFLIGHT_WINDOW_MS = 30_000L;
    private static final AtomicLong sLastForegroundStartAtMs = new AtomicLong(0L);

    /** 【自启兜底】每次拉起后最多补拉次数，防无限重试 */
    private static final int MAX_RECOVERY_PER_BOOT = 2;
    private static final java.util.concurrent.atomic.AtomicInteger sRecoveryCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 兼容旧调用（按开机路径处理） */
    public static boolean allowForegroundStart(Context context) {
        return allowForegroundStart(context, SRC_BOOT);
    }

    /**
     * 单飞闸门：决定"这次拉起要不要真的执行"。
     *
     * <p><b>为什么必须分来源</b>（2026-10-13 黑屏修复·第二轮）：
     * <ul>
     *   <li><b>开机路径</b>（{@link #SRC_BOOT}：Alarm / Job / BootReceiver 直连）＝明确的
     *       "自启"意图。这条路径<b>不看</b>「是否已在前台」—— 因为
     *       {@code getRunningAppProcesses()} 在部分 TV ROM 上可能误报前台，
     *       一旦据此拦截，整条自启链会被挡死（那比多重建一次严重得多）。故这里 fail-open。</li>
     *   <li><b>sticky 前台服务</b>（{@link #SRC_SERVICE}）＝职责只是"保活"。它由
     *       {@code MyApplication.onCreate} 在<b>每次</b> App 启动时拉起，若 App 已经在前台
     *       还去 startActivity，就会把 MainActivity 重新带到前台 → OttHome 重建 →
     *       <b>Surface detach → 黑屏</b>（实测：用户在设置里开关「开机自启」时必然复现，
     *       日志为 {@code 播放异常 cause链=[{yq: Detaching surface}}）。
     *       所以这条路径必须过「已在前台」判断。</li>
     * </ul>
     *
     * <p>时间窗（{@link #START_SINGLEFLIGHT_WINDOW_MS}）对两条路径都生效：进程刚起来、
     * 还没变成前台的那几秒里，防止 Alarm + Job + sticky 服务同时开火。
     *
     * @param source {@link #SRC_BOOT} 或 {@link #SRC_SERVICE}
     * @return {@code true} 允许拉起；{@code false} 跳过
     */
    public static boolean allowForegroundStart(Context context, int source) {
        final long now = SystemClock.elapsedRealtime();
        final long last = sLastForegroundStartAtMs.get();

        // ① 时间窗：同一进程 30s 内只真正拉起一次
        if (last > 0L && (now - last) < START_SINGLEFLIGHT_WINDOW_MS) {
            LogBridge.i(TAG, "【单飞闸门】距上次拉起仅 " + (now - last) + "ms（< "
                    + START_SINGLEFLIGHT_WINDOW_MS + "ms），跳过本次拉起"
                    + "（来源=" + sourceName(source) + "）");
            return false;
        }

        // ② sticky 服务：App 已在前台就绝不打扰（否则重建界面 → Surface detach → 黑屏）
        if (source == SRC_SERVICE && isSelfForeground(context)) {
            LogBridge.i(TAG, "【单飞闸门】App 已在前台，sticky 服务跳过本次拉起（避免黑屏重建）");
            return false;
        }

        sLastForegroundStartAtMs.set(now);
        sRecoveryCount.set(0);      // 新一轮拉起，补拉额度重置
        LogBridge.i(TAG, "【单飞闸门】放行本次拉起（来源=" + sourceName(source) + "）");
        return true;
    }

    private static String sourceName(int source) {
        return source == SRC_SERVICE ? "sticky前台服务" : "开机路径";
    }

    /**
     * 【2026-10-13 自启兜底】"到前台确认"专用的补拉闸门。
     *
     * <p>与 {@link #allowForegroundStart} 的区别：<b>刻意忽略时间窗</b> ——
     * 首次拉起后的前 30 秒恰恰是最容易被系统 Launcher / 厂商清理抢走焦点的窗口，
     * 用时间窗会把最需要补拉的时刻挡掉。这里只要求「当前确实不在前台」+ 次数未超上限。
     *
     * @return {@code true} 允许补拉
     */
    public static boolean allowRecoveryStart(Context context) {
        if (isSelfForeground(context)) {
            // 已在前台：绝不打扰，更不会重建界面
            return false;
        }
        final int n = sRecoveryCount.get();
        if (n >= MAX_RECOVERY_PER_BOOT) {
            LogBridge.i(TAG, "【自启兜底】补拉次数已达上限 " + MAX_RECOVERY_PER_BOOT + " 次，放弃");
            return false;
        }
        sRecoveryCount.incrementAndGet();
        LogBridge.i(TAG, "【自启兜底】确认 App 不在前台，补拉第 " + (n + 1) + " 次");
        return true;
    }

    /** 供自启兜底判断"App 是否真的在前台"（对外暴露，内部走同一实现） */
    public static boolean isSelfForegroundSafe(Context context) {
        return isSelfForeground(context);
    }

    // ==================================================================
    // 【2026-10-13 自启埋点】记录"本次由哪条路径拉起 / 闸门是否放行"
    //
    //   背景：bootlog 原先只记广播链路（BootReceiver 收到什么 action），而
    //   JobScheduler（BootJobService）与 sticky 前台服务（BootStartForegroundService）
    //   这两条路径**完全没有记录** —— 事后无法判断"自启到底是哪条路径拉起的、
    //   有没有被闸门拦住"，也就没法定位"偶尔自启失效"。
    //   以下埋点补齐这一环，写 bootlog（持久化文件，不受 9527 环形缓冲影响）+ 9527 日志。
    // ==================================================================

    /**
     * 记录一次拉起来源。
     *
     * @param source 路径名，如 {@code Alarm(BootStartReceiver)} / {@code JobScheduler} /
     *               {@code sticky前台服务} / {@code BootReceiver直连} / {@code 开机窗口补拉}
     * @param detail 细节，如 action 名、闸门是否放行、调用结果
     */
    public static void noteLaunchSource(Context context, String source, String detail) {
        String line = "来源=" + source + (detail == null || detail.isEmpty() ? "" : " | " + detail);
        LogBridge.i(TAG, "【自启埋点】" + line);
        try {
            BootReceiver.writeBootLog(context, "【自启埋点】" + line);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 本 App 是否已处于前台（只查自己的进程，Android 8+ 允许）。
     *
     * <p>App 不在运行、或在后台/被缓存时返回 {@code false}，此时应当放行拉起
     * （后者是为了让「按遥控器把 App 拉回前台」这类正常行为仍然有效）。
     */
    private static boolean isSelfForeground(Context context) {
        if (context == null) {
            return false;
        }
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            final java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list =
                    am.getRunningAppProcesses();
            if (list == null) {
                return false;
            }
            final String pkg = context.getPackageName();
            for (android.app.ActivityManager.RunningAppProcessInfo pi : list) {
                if (pi == null) {
                    continue;
                }
                if (pkg.equals(pi.processName)) {
                    return pi.importance
                            == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Throwable ignored) {
            // 受限时按「不在前台」处理，宁可多拉一次也不要把 App 彻底拉不起来
        }
        return false;
    }

    private final Context context;
    private final SharedPreferences sp;

    public enum BootStatus {
        NORMAL, NO_PERMISSION, COMPONENT_DISABLED, SYSTEM_RESTRICTED
    }

    public BootStartManager(Context context, SharedPreferences sp) {
        this.context = context;
        this.sp = sp;
    }

    public void updateBootStatusText(TextView tvStatus) {
        if (tvStatus == null) return;
        boolean enabled = sp.getBoolean(KEY_BOOT_AUTO_START, false);
        if (!enabled) {
            tvStatus.setText("未开启");
            tvStatus.setTextColor(Color.parseColor("#999999"));
            return;
        }
        BootStatus status = checkBootStatus();
        switch (status) {
            case NORMAL:
                tvStatus.setText("已开启 · 正常");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                break;
            case NO_PERMISSION:
                tvStatus.setText("需授权自启权限");
                tvStatus.setTextColor(Color.parseColor("#FF9800"));
                break;
            case COMPONENT_DISABLED:
                tvStatus.setText("组件被禁用");
                tvStatus.setTextColor(Color.parseColor("#F44336"));
                break;
            case SYSTEM_RESTRICTED:
                tvStatus.setText("需在系统设置中开启");
                tvStatus.setTextColor(Color.parseColor("#FF9800"));
                break;
            default:
                tvStatus.setText("已开启");
                tvStatus.setTextColor(Color.parseColor("#999999"));
                break;
        }
    }

    public BootStatus checkBootStatus() {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, BootReceiver.class);
            int state = pm.getComponentEnabledSetting(componentName);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                LogBridge.w(TAG, "【自启】组件被禁用");
                return BootStatus.COMPONENT_DISABLED;
            }
        } catch (Exception e) {
            LogBridge.w(TAG, "【自启】检查组件状态异常：" + e.getMessage());
        }

        String manufacturer = Build.MANUFACTURER;
        String brand = Build.BRAND;
        if (manufacturer != null) {
            manufacturer = manufacturer.toLowerCase(Locale.ROOT);
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                LogBridge.w(TAG, "【自启】检测到 MIUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                LogBridge.w(TAG, "【自启】检测到 EMUI 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                LogBridge.w(TAG, "【自启】检测到 ColorOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }
            if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                LogBridge.w(TAG, "【自启】检测到 OriginOS 系统，需手动开启自启");
                return BootStatus.SYSTEM_RESTRICTED;
            }

            if (manufacturer.contains("skyworth") || manufacturer.contains("coocaa")
                    || (brand != null && (brand.toLowerCase(Locale.ROOT).contains("skyworth")
                                          || brand.toLowerCase(Locale.ROOT).contains("coocaa")))) {
                LogBridge.w(TAG, "【自启】检测到创维/酷开系统，需手动开启自启");
                BootReceiver.writeBootLog(context, "checkBootStatus: 检测到创维/酷开系统，需系统自启白名单授权");
                return BootStatus.SYSTEM_RESTRICTED;
            }
        }
        LogBridge.d(TAG, "【自启】状态检测：正常");
        BootReceiver.writeBootLog(context, "checkBootStatus: 状态正常，广播接收组件可用");
        return BootStatus.NORMAL;
    }

    public void showBootGuideDialog() {
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("检测到当前系统会拦截第三方应用的开机广播。\n");
            sb.append("仅开启应用内开关还不够，请在系统设置中允许本应用开机自启：\n\n");

            String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
            String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
            if (manufacturer.contains("skyworth") || brand.contains("skyworth")
                    || manufacturer.contains("coocaa") || brand.contains("coocaa")) {
                sb.append("【创维/酷开电视】\n");
                sb.append("① 设置 → 应用管理 → 应用权限管理\n");
                sb.append("② 找到本应用，允许「开机自启」/「后台自启」\n");
                sb.append("（部分机型需在「酷开应用圈」或系统管家中授权）\n\n");
            } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                sb.append("【小米电视】\n设置 → 应用 → 应用管理 → 找到本应用\n→ 自启动 → 允许\n\n");
            } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                sb.append("【华为/荣耀电视】\n设置 → 应用 → 应用启动管理\n→ 关闭「自动管理」，手动开启自启动\n\n");
            } else if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")) {
                sb.append("【OPPO/一加电视】\n设置 → 应用管理 → 自启动管理 → 允许\n\n");
            } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
                sb.append("【vivo电视】\n设置 → 应用与权限 → 权限管理 → 自启动 → 允许\n\n");
            } else {
                sb.append("设置 → 应用/应用管理 → 找到本应用\n→ 允许「开机自启动」「后台运行」\n\n");
            }

            sb.append("设置完成后重启电视，应用即可自动启动。\n\n");
            sb.append("若系统设置中没有相关选项，说明系统不自带自启管理，");
            sb.append("应用已通过多广播兜底（开机/屏幕唤醒/网络/存储挂载）自动拉起。");

            new AlertDialog.Builder(context)
                    .setTitle("需在系统设置中授权自启")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
            LogBridge.d(TAG, "【自启】已显示系统自启授权引导");
            BootReceiver.writeBootLog(context, "showBootGuideDialog: 已引导用户在系统设置中授权自启");
        } catch (Exception e) {
            LogBridge.w(TAG, "【自启】显示引导对话框失败：" + e.getMessage());
            BootReceiver.writeBootLog(context, "showBootGuideDialog 失败: " + e.getMessage());
        }
    }

    public void showBootStatusDialog() {
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        try {
            boolean enabled = sp.getBoolean(KEY_BOOT_AUTO_START, false);
            BootStatus status = checkBootStatus();
            StringBuilder sb = new StringBuilder();
            sb.append("应用内开关：").append(enabled ? "已开启" : "未开启").append("\n");
            sb.append("自启条件：");
            switch (status) {
                case NORMAL:
                    sb.append("正常（已具备接收开机广播条件）");
                    break;
                case NO_PERMISSION:
                    sb.append("缺少开机广播权限");
                    break;
                case COMPONENT_DISABLED:
                    sb.append("广播接收组件被系统禁用");
                    break;
                case SYSTEM_RESTRICTED:
                    sb.append("需在系统自启管理中授权");
                    break;
                default:
                    sb.append("未知");
            }
            sb.append("\n\n设备：").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL);
            sb.append("\n系统：Android ").append(Build.VERSION.RELEASE)
              .append(" (API ").append(Build.VERSION.SDK_INT).append(")");
            sb.append("\n\n提示：开机自启依赖系统广播，若长时间无效，");
            sb.append("请在系统自启管理中允许本应用，并避免使用「一键清理」关闭本应用。");

            new AlertDialog.Builder(context)
                    .setTitle("开机自启状态")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
            LogBridge.d(TAG, "【自启】已显示状态详情");
            BootReceiver.writeBootLog(context, "showBootStatusDialog: 已显示自启状态详情");
        } catch (Exception e) {
            LogBridge.w(TAG, "【自启】显示状态对话框失败：" + e.getMessage());
            BootReceiver.writeBootLog(context, "showBootStatusDialog 失败: " + e.getMessage());
        }
    }

    public void testBootAutoStart() {
        LogBridge.d(TAG, "【自启】开始测试自启功能");
        BootReceiver.writeBootLog(context, "testBootAutoStart: 手动测试，发送 BOOT_COMPLETED 广播");
        try {
            Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            intent.setComponent(new ComponentName(context, BootReceiver.class));
            context.sendBroadcast(intent);
            Toast.makeText(context, "已发送开机广播测试\n\n请观察应用是否会重新启动", Toast.LENGTH_LONG).show();
            LogBridge.d(TAG, "【自启】测试广播已发送");
            BootReceiver.writeBootLog(context, "testBootAutoStart: BOOT_COMPLETED 测试广播已发送");
        } catch (Exception e) {
            LogBridge.w(TAG, "【自启】测试失败：" + e.getMessage());
            BootReceiver.writeBootLog(context, "testBootAutoStart 发送失败: " + e.getMessage());
            Toast.makeText(context, "测试失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void toggleBoot(boolean isChecked, TextView tvStatus) {
        sp.edit().putBoolean(KEY_BOOT_AUTO_START, isChecked).apply();
        LogBridge.d(TAG, "【设置】开机自启" + (isChecked ? "已开启" : "已关闭"));
        BootReceiver.writeBootLog(context, "toggleBoot: 用户" + (isChecked ? "开启" : "关闭") + "应用内自启开关");
        updateBootStatusText(tvStatus);
        if (isChecked) {

            try {
                Intent svc = new Intent(context, BootStartForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                BootReceiver.writeBootLog(context, "toggleBoot: 已启动常驻保活服务");
            } catch (Throwable t) {
                LogBridge.w(TAG, "启动保活服务失败: " + t.getMessage());
            }

            BootStatus status = checkBootStatus();
            if (status == BootStatus.NORMAL) {
                Toast.makeText(context, "开机自启已开启\n\n电视重启后会自动启动应用", Toast.LENGTH_LONG).show();
            } else {
                showBootGuideDialog();
            }
        } else {

            try {
                context.stopService(new Intent(context, BootStartForegroundService.class));
                BootReceiver.writeBootLog(context, "toggleBoot: 已停止常驻保活服务");
            } catch (Throwable t) {
                LogBridge.w(TAG, "停止保活服务失败: " + t.getMessage());
            }
            Toast.makeText(context, "开机自启已关闭", Toast.LENGTH_SHORT).show();
        }
    }
}
