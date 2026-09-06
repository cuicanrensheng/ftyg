package com.tv.live;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.tv.live.util.LogBridge;

import com.tv.live.BuildConfig;
import com.tv.live.util.AppCacheInspector;

import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.exceptions.UndeliverableException;
import java.net.UnknownHostException;
import java.net.SocketException;
import com.tv.live.util.LogCollector;
import com.tv.live.util.LogServer;
import com.tv.live.util.NetUtil;
import com.tv.live.util.HuyaCacheGovernor;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.HuyaCredentials;
import com.tv.live.util.EncryptedStorage;
import com.tv.live.util.SecurityCertificatePinner;
import com.tv.live.util.SecurityGuardManager;
import com.tv.live.util.SecureDataStore;
import com.tv.live.util.ExceptionReporter;
import com.tv.live.security.SecurityCore;
import com.tv.live.security.AntiDebug;
import com.tv.live.security.StringObfuscator;
import com.tv.live.security.DexProtector;
import com.tv.live.security.StringProtector;
import com.tv.live.security.SecurityGuard;
public class MyApplication extends Application {

    private static MyApplication sInstance;

    public static MyApplication getInstance() {
        return sInstance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiverCompat(receiver, filter, null, null, 0);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return registerReceiverCompat(receiver, filter, null, null, flags);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                   String broadcastPermission, Handler scheduler) {
        return registerReceiverCompat(receiver, filter, broadcastPermission, scheduler, 0);
    }

    /**
     * Android 14 (targetSdk 34+) 强制要求动态注册 receiver 声明导出标志。
     * 调用方未显式声明时默认 RECEIVER_NOT_EXPORTED（系统广播与自有广播均可送达）；
     * 调用方已显式指定 EXPORTED/NOT_EXPORTED 时尊重原值。
     */
    private Intent registerReceiverCompat(BroadcastReceiver receiver, IntentFilter filter,
                                          String broadcastPermission, Handler scheduler, int flags) {
        if (Build.VERSION.SDK_INT >= 33) {
            if ((flags & (Context.RECEIVER_EXPORTED | Context.RECEIVER_NOT_EXPORTED)) == 0) {
                flags |= Context.RECEIVER_NOT_EXPORTED;
            }
            if (broadcastPermission != null || scheduler != null) {
                return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags);
            }
            return super.registerReceiver(receiver, filter, flags);
        }
        if (broadcastPermission != null || scheduler != null) {
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler);
        }
        return super.registerReceiver(receiver, filter);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // IPTV 中继在部分网络下 IPv6 不可达，HttpURLConnection 无 Happy Eyeballs，
        // 会先死等 IPv6 超时 5s 再回退 IPv4，冷启动首帧被放大。全局 IPv4 优先，
        // 虎牙取流走自家 mars 栈不受影响。
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
        } catch (Throwable ignored) {
        }

        try {
            BootReceiver.registerDynamic(this);
        } catch (Throwable t) {
            LogBridge.w("MyApplication", "动态注册开机兜底监听失败: " + t.getMessage());
        }

        try {
            boolean autoStart = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getBoolean("boot_auto_start", false);
            if (autoStart) {
                Intent svc = new Intent(this, BootStartForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                LogCollector.getInstance().info("MyApplication", "已启动常驻保活前台服务(sticky)");
            }
        } catch (Throwable t) {
            LogBridge.w("MyApplication", "启动保活服务失败: " + t.getMessage());
        }

        LogCollector.getInstance();

        RxJavaPlugins.setErrorHandler(error -> {
            Throwable actual = error;
            if (error instanceof UndeliverableException && error.getCause() != null) {
                actual = error.getCause();
            }

            if (actual instanceof UnknownHostException
                    || actual instanceof SocketException
                    || actual instanceof InterruptedException) {
                LogBridge.w("MyApplication", "RxJava 可忽略错误: " + actual.getMessage());
                return;
            }

            LogBridge.e("MyApplication", "RxJava 未投递异常: " + actual.getMessage(), actual);
            try {
                ExceptionReporter.report("RxJavaPlugins.onError", actual);
            } catch (Throwable ignored) {
            }
        });

        initializeAsync();

        LogCollector.getInstance().info("MyApplication", "应用启动完成（快速模式）");
    }

    private void initializeAsync() {

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    TVPlayerManager.getInstance(MyApplication.this);
                    long dt = android.os.SystemClock.elapsedRealtime() - t0;
                    LogCollector.getInstance().info("MyApplication",
                            "TVPlayerManager 预热完成（后台线程）: " + dt + "ms");
                } catch (Throwable t) {
                    LogBridge.w("MyApplication", "TVPlayerManager 预热失败: " + t.getMessage());
                }
            }
        }, "init-player-preheat").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                initSecurity();
            }
        }, "init-security").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                initBugly();
            }
        }, "init-bugly").start();

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        initLogServices();
                        initCacheCleanup();
                    }
                }, "init-deferred").start();

                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                initHuyaSDK();
                            }
                        }, "init-huya-sdk").start();
                    }
                }, 2000);

                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                initSecurityGuardManager();
                                initSecureDataStore();
                            }
                        }, "security-deferred").start();
                    }
                }, 3000);
            }
        }, 2000);
    }

    private void initSecurity() {

        try {

            boolean debugDetected = AntiDebug.init(this, !BuildConfig.IS_DEBUG);
            if (debugDetected) {

                LogCollector.getInstance().warn("MyApplication",
                    "⚠️ 检测到可疑环境（已记录，详见反调试日志）");
            } else {
                String mode = BuildConfig.IS_DEBUG ? "调试版：跳过反调试检测" : "反调试检测通过";
                LogCollector.getInstance().info("MyApplication", mode);
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "反调试检测失败: " + e.getMessage());
        }

        try {
            String decodedKey = StringObfuscator.decodeString("a1b2c3d4e5f6");
            if (decodedKey != null) {
                LogCollector.getInstance().info("MyApplication", "字符串混淆测试通过");
            }
        } catch (Throwable e) {

        }

        try {
            SecurityCore.init();
            LogCollector.getInstance().info("MyApplication", "SecurityCore 初始化成功");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "SecurityCore init failed: " + e.getMessage());
        }

        try {
            UrlConfig.fillPublicFields();
            LogCollector.getInstance().info("MyApplication", "URL 配置解密完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "URL 配置解密失败: " + e.getMessage());
        }

        boolean securityPassed = true;
        try {
            if (!SecurityCheck.verifyOnStart(this)) {
                LogCollector.getInstance().warn("MyApplication", "⚠️ 安全检查未通过（已降级运行模式）");
                securityPassed = false;

            } else {
                LogCollector.getInstance().info("MyApplication", "安全检查通过");
            }
        } catch (Throwable e) {
            LogBridge.e("MyApplication", "SecurityCheck failed: " + e.getMessage());
        }

        try {
            DexProtector.init(this);
            LogCollector.getInstance().info("MyApplication", "DEX 保护初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "DEX 保护初始化失败: " + e.getMessage());
        }

        try {
            StringProtector.init(!BuildConfig.IS_DEBUG);

            StringProtector.register("huya_app_id",
                BuildConfig.IS_DEBUG ? "123456" : StringProtector.quickEncrypt("123456"));
            StringProtector.register("huya_app_key",
                BuildConfig.IS_DEBUG ? "d8f193dd" : StringProtector.quickEncrypt("d8f193dd"));
            StringProtector.register("huya_game_id",
                BuildConfig.IS_DEBUG ? "2135" : StringProtector.quickEncrypt("2135"));
            LogCollector.getInstance().info("MyApplication", "字符串保护初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "字符串保护初始化失败: " + e.getMessage());
        }

        try {
            SecurityGuard.init(this);
            LogCollector.getInstance().info("MyApplication", "安全守卫初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "安全守卫初始化失败: " + e.getMessage());
        }

        try {
            EncryptedStorage storage = EncryptedStorage.getInstance(this);
            if (storage.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "加密存储初始化成功");
            } else {
                LogCollector.getInstance().warn("MyApplication", "加密存储未完全初始化");
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "加密存储初始化失败: " + e.getMessage());
        }

        try {
            HuyaCredentials credentials = HuyaCredentials.getInstance(this);
            if (credentials.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "虎牙 SDK 凭证加载成功: " + credentials.getCredentialsSummary());
            } else {
                LogCollector.getInstance().warn("MyApplication", "虎牙 SDK 凭证未完全初始化");
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "虎牙 SDK 凭证初始化失败: " + e.getMessage());
        }

        try {
            SecurityCertificatePinner sslPinner = SecurityCertificatePinner.getInstance();
            sslPinner.init(this);
            LogCollector.getInstance().info("MyApplication", "SSL 证书管理器初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "SSL 证书管理器初始化失败: " + e.getMessage());
        }

        try {
            NetUtil.init(this);
            LogCollector.getInstance().info("MyApplication", "网络工具初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "网络工具初始化失败: " + e.getMessage());
        }

    }

    private void initSecurityGuardManager() {
        try {
            SecurityGuardManager guardManager = SecurityGuardManager.getInstance();
            guardManager.init(this);

            SecurityGuardManager.SecurityReport report = guardManager.performFullSecurityCheck();
            if (report.hasAnyThreat()) {
                LogCollector.getInstance().warn("MyApplication",
                    "安全检测发现风险: " + report.getSummary());
            } else {
                LogCollector.getInstance().info("MyApplication", "安全检测通过");
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "安全检测初始化失败: " + e.getMessage());
        }
    }

    private void initSecureDataStore() {
        try {
            SecureDataStore secureStore = SecureDataStore.getInstance();
            secureStore.init(this);
            if (secureStore.isInitialized()) {
                LogCollector.getInstance().info("MyApplication", "安全数据存储初始化完成");
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "安全数据存储初始化失败: " + e.getMessage());
        }
    }

    private void initBugly() {
        try {
            ExceptionReporter.init(this);
            ExceptionReporter.setEnabled(!BuildConfig.IS_DEBUG);
            LogCollector.getInstance().info("MyApplication", "全局异常上报初始化完成（Bugly 已移除）");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "ExceptionReporter init failed: " + e.getMessage());
        }
    }

    private void initLogServices() {
        try {
            CrashHandler.getInstance().init(this);
            LogCollector.getInstance().info("MyApplication", "崩溃处理器初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "CrashHandler init failed: " + e.getMessage());
        }

        try {
            LogServer.getInstance(this).start();
            String ip = LogServer.getInstance(this).getDeviceIpAddress();
            int port = LogServer.getInstance(this).getPort();
            LogCollector.getInstance().info("MyApplication", "LogServer 启动成功: " + ip + ":" + port);
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "LogServer init failed (ignore): " + e.getMessage());
        }

        try {
            BootReceiver.trimBootLog(this);
        } catch (Throwable ignored) {
        }

    }

    private void initCacheCleanup() {
        try {
            AppCacheInspector.startupCleanup(this);
        } catch (Exception e) {
            LogBridge.w("MyApplication", "AppCacheInspector failed: " + e.getMessage());
        }

        try {
            HuyaCacheGovernor.startupCleanup(this);
        } catch (Exception e) {
            LogBridge.w("MyApplication", "HuyaCacheGovernor failed: " + e.getMessage());
        }
    }

    private void initHuyaSDK() {
        try {
            if (Build.VERSION.SDK_INT <= 22) {

                LogBridge.w("MyApplication", "⚠️ API <= 22（Android 5.x）尝试初始化虎牙 SDK"
                        + "（useLegacyPackaging + v7a 兼容验证）");
            }

            HandlerThread sdkThread = new HandlerThread("huya-sdk-init");
            sdkThread.start();
            new Handler(sdkThread.getLooper()).post(() -> {
                try {
                    HuyaSDKParser.init(MyApplication.this);
                    LogCollector.getInstance().info("MyApplication", "虎牙 SDK 初始化完成");
                } catch (Exception e) {
                    LogBridge.e("MyApplication", "虎牙 SDK 初始化异常: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LogBridge.e("MyApplication", "虎牙 SDK 初始化异常: " + e.getMessage());
        }
    }

}
