package com.tv.live;

import android.app.Application;
import android.annotation.SuppressLint;
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
import com.tv.live.util.CacheManager.AppCacheInspector;
import com.tv.live.util.LogBridge.LogCollector;
import com.tv.live.util.LogBridge.LogServer;
import com.tv.live.util.LogBridge.CrashHandler;
import com.tv.live.util.NetUtil;
import com.tv.live.util.CacheManager.AppCacheGovernor;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.CacheManager.EncryptedStorage;
import com.tv.live.util.SecurityCertificatePinner;
import com.tv.live.security.SecurityCore.SecurityGuardManager;
import com.tv.live.util.CacheManager.SecureDataStore;
import com.tv.live.util.LogBridge.ExceptionReporter;
import com.tv.live.security.SecurityCore;
import com.tv.live.security.SecurityCore.AntiDebug;
import com.tv.live.security.SecurityCore.StringObfuscator;
import com.tv.live.security.SecurityCore.DexProtector;
import com.tv.live.security.SecurityCore.SecurityGuard;
import com.tv.live.security.SecurityCore.SecurityCheck;
public class MyApplication extends Application {

    private static MyApplication sInstance;

    public static MyApplication getInstance() {
        return sInstance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 虎牙流信息仓库就绪兜底超时：首帧迟迟不来时兜底拉起，避免仓库永不就绪 */
    private static final long HUYA_REPO_INIT_FALLBACK_MS = 30000;

    /** 虎牙流信息仓库是否已就绪（首帧 / 兜底 二选一，保证只跑一次） */
    private final java.util.concurrent.atomic.AtomicBoolean sHuyaStreamRepoStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // 虎牙流信息仓库初始化推迟到"播放器出画面后"再执行，避免与首屏争抢资源

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiverCompat(receiver, filter, null, null, 0);
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return registerReceiverCompat(receiver, filter, null, null, flags);
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                   String broadcastPermission, Handler scheduler) {
        return registerReceiverCompat(receiver, filter, broadcastPermission, scheduler, 0);
    }

    /**
     * Android 14 (targetSdk 34+) 强制要求动态注册 receiver 声明导出标志。
     * 调用方未显式声明时默认 RECEIVER_NOT_EXPORTED（系统广播与自有广播均可送达）；
     * 调用方已显式指定 EXPORTED/NOT_EXPORTED 时尊重原值。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 【IPv4 优先 · 必须在最早钩子设置】attachBaseContext 早于 onCreate，
        // 尽量赶在 InetAddress 首次解析 / socket 工厂初始化之前，否则属性被固化、无效。
        // 背景：自有直播源 hwrr.jx.chinamobile.com（移动 IPTV）调度器按"连接调度域名所用 IP 族"返回节点：
        //   用 IPv4 连 → 返回 IPv4 节点（117.169.31.42，可达）
        //   用 IPv6 连 → 返回 IPv6 节点（2409:8087:...，设备无公网 IPv6 → 连接超时 3-5s）
        // 实测 2026-09-14：curl -4 得 IPv4、curl -6 得 IPv6，证实按 IP 族调度。
        // 本套走 HttpURLConnection（Media3 播放器），故强制 IPv4；虎牙取流走 mars 栈、不受影响。
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // 【2026-10-22 I1-07】尽早把 Application context 交给 SecurityCore：
        //   绑定签名哈希要用 PackageManager，而 SecurityCore.init() 是无参静态方法。
        try { SecurityCore.setAppContext(this); } catch (Throwable ignored) {}
        // 【2026-10-26 源9 网页线路】网页播放页解析（咪咕 JS 插件 / WebView）需要 Context。
        try { com.tv.live.juzhi.JzUrlResolver.initContext(this); } catch (Throwable ignored) {}
        // 双保险：attachBaseContext 已设 IPv4 优先，这里再设一次（幂等、无副作用）。
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");
        } catch (Throwable ignored) {
        }

        // 【P0-A】URL 配置极早期初始化（解除直播源加载的串行依赖）
        // 原链路：init-security 线程依次跑 AntiDebug → 字符串混淆 → SecurityCore.init →
        //        UrlConfig.fillPublicFields，实测 fillPublicFields 要到 +4.3s 才完成，
        //        而直播源下载必须等 LIVE_URL 就绪（LiveSourceLoader 里还有"读到空 URL"的
        //        竞态兜底注释），等于首帧被这条链条强行串行阻塞约 4s。
        // 改法：这里独立起一条高优先级线程只做 SecurityCore.init + fillPublicFields，
        //        两步都是幂等的（SecurityCore 有 sLoaded 判断，fillPublicFields 只是常量赋值），
        //        initSecurity() 里保留原调用不会重复劳动。详见 开发记录_2026-09-10.md 13.6
        try {
            Thread earlyUrl = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        SecurityCore.init();
                    } catch (Throwable ignored) {
                    }
                    try {
                        UrlConfig.fillPublicFields();
                    } catch (Throwable ignored) {
                    }
                }
            }, "init-urlconfig-early");
            earlyUrl.setPriority(Thread.MAX_PRIORITY);
            earlyUrl.start();
        } catch (Throwable ignored) {
        }

        // 方案 G：MediaCodec 真实预创建（触发硬解 HAL 加载），尽早启动、与后续 SDK 初始化并行，
        // 把 HAL 加载时间藏进等待窗口。子线程执行、全流程兜异常，失败只记日志不影响主流程。
        // 开关：CodecWarmupProbe.ENABLED（当前 true）；详见 开发记录_2026-09-10.md 13.4
        try {
            com.tv.live.util.CodecWarmupProbe.runIfEnabled(this);
        } catch (Throwable ignored) {
        }

        // 【方案1】源7（afhgrj 影视）落地 m3u8 缓存初始化：注入 application context
        //   并加载上次会话的落地缓存。源7 为惰性解析，冷启动时播放器要串行联网（详情页+
        //   播放页+探活）才拿到真实 m3u8；缓存命中则起播 0 次网络，配合下方「方案2」预热
        //   真实 m3u8 host，显著压低源7 冷启动首帧延迟。全程兜异常，失败不影响主流程。
        try {
            com.tv.live.afhgrj.AfhgrjUrlResolver.init(this);
        } catch (Throwable ignored) {
        }

        // 方案 H（2026-09-11）：预热阶段预解析「上次播放流地址」主机 DNS，与初始化并行，
        // 省掉 ExoPlayer 首次建连的 DNS 往返。纯后台、全兜异常，失败不影响主流程。
        // 【2026-09-14 冷启动优化】在 DNS 之上追加「TCP + TLS 预连接」：
        //   播放走的是 RedirectLoggingHttpDataSource → HttpURLConnection，
        //   因此这里也用 HttpURLConnection 请求同一地址，连接会进入 JVM 的
        //   KeepAliveCache（按 host:port 索引，默认保活 5s）。
        //   实测预热(+0.15s) → 播放(+1.29s) 间隔约 1.1s < 5s，连接可直接复用，
        //   省掉握手往返（约 0.3~1s）。读完后 close() 而不 disconnect()，连接才会回池。
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    prewarmLastPlayUrl();
                }
            }, "dns-prewarm").start();
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

        // 工程内已无 io.reactivex 代码路径，RxJavaPlugins 全局兜底不再需要。

        initializeAsync();

        LogCollector.getInstance().info("MyApplication", "应用启动完成（快速模式）");
    }

    /**
     * 预热「上次播放流地址」：DNS + TCP + TLS 三段一起做掉。
     *
     * <p><b>为什么用 HttpURLConnection</b>：播放链路 {@code RedirectLoggingHttpDataSource}
     * 基于 {@code HttpURLConnection}，其连接池是 JVM 级 {@code KeepAliveCache}
     * （按 host:port 索引，默认保活 5 秒）。这里用**同一栈**预热，播放时即可复用连接，
     * 省掉 TCP + TLS 握手往返（约 0.3~1s）。
     *
     * <p><b>关键点</b>：读完响应体后必须 <b>close() 而不是 disconnect()</b> ——
     * 只有 close 才会把连接交还连接池，disconnect() 会直接关掉连接。
     */
    private void prewarmLastPlayUrl() {
        String last = null;
        try {
            last = com.tv.live.util.CacheManager.getInstance(this).getLastPlayUrl();
        } catch (Throwable t) {
            LogCollector.getInstance().info("MyApplication", "流地址预热: 读取 lastPlayUrl 异常 " + t);
        }
        if (last == null || !last.startsWith("http")) {
            // 【方案A】首次安装 / 数据被清：没有「上次播放地址」可复用。
            // 若不处理，首帧必须现等主 CDN 主域名首跳 TCP（实测 hwrr.jx.chinamobile.com:8080
            // 建连 5.3s，DNS 极快 1ms → 卡在 TCP 握手）。但启动的关键路径上必然要先
            // 「下载直播源频道列表」（宿主即主 CDN，见 UrlConfig.LIVE_URL），这里改为
            // 直接预热「默认源主地址」：
            //   · DNS + TCP + TLS 全在后台提前完成，连接 close() 回 KeepAliveCache；
            //   · 随后首帧如果命中同一 host:port（直播源列表 / 首频道），即可直接复用连接，
            //     把 5.3s 首跳藏进预热窗口；
            //   · 即使 host 不完全一致，也把主 CDN 调度域名的握手提前做掉了。
            String defaultUrl = UrlConfig.LIVE_URL;
            if (defaultUrl == null || !defaultUrl.startsWith("http")) {
                LogCollector.getInstance().info("MyApplication",
                        "流地址预热跳过: lastPlayUrl 无效=[" + last + "] 且默认源主地址不可用");
                return;
            }
            LogCollector.getInstance().info("MyApplication",
                    "【方案A】无上次播放地址，预热默认源主地址: " + defaultUrl);
            prewarmUrl(defaultUrl);
            return;
        }
        prewarmUrl(last);
    }

    /**
     * 预热单个流地址：DNS + TCP + TLS + 读空响应体 close() 回连接池。
     * 纯后台、全兜异常，失败只记日志不影响主流程。
     *
     * <p>用 {@link HttpURLConnection}（同一个 HTTP 栈）预热，连接进入 JVM 级
     * {@code KeepAliveCache}（按 host:port 索引，默认保活 5s），播放链路
     * {@code RedirectLoggingHttpDataSource} 同样基于 {@code HttpURLConnection}，
     * 命中同一 key 时直接复用，省掉 TCP + TLS 握手往返。
     */
    private static void prewarmUrl(String urlStr) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        String host = null;
        try {
            java.net.URL u = new java.net.URL(urlStr);
            host = u.getHost();
            if (host == null || host.isEmpty()) {
                return;
            }

            // ① DNS 预热
            java.net.InetAddress.getByName(host);
            long tDns = android.os.SystemClock.elapsedRealtime();

            // ② TCP + TLS 预连接（同一 HTTP 栈 → 连接进 KeepAliveCache 供播放复用）
            // 【2026-10-25 NO_PROXY】预热必须与播放链路同口径：播放侧已统一 NO_PROXY，
            //   这里若走系统代理，预热出来的连接与真正播放用的连接不在同一目标上，
            //   预连接就白做了（设备配代理时甚至会连到代理端口，等于把预热浪费掉）。
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    u.openConnection(java.net.Proxy.NO_PROXY);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            // ⚠️【方案A关键】绝不跟随重定向。播放链路（RedirectLoggingHttpDataSource）
            // 首跳是「手动 while 循环 + 不跟随系统重定向」——它先直连主调度域名
            // (如 hwrr.jx.chinamobile.com:8080) 拿到 302 后才 disconnect 并连往重定向 IP。
            // 若这里跟随重定向，连接会回池到「重定向目标 IP」，与播放首跳的 key 不一致，
            // 起不到复用作用（实测首跳仍现等 TCP 握手 6-10s）。
            // 必须保持同一 key（原域名:port）回池，播放首跳才能复用手上的已建连接。
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code;
            try {
                code = conn.getResponseCode();
            } catch (Throwable t) {
                // 个别实现读 3xx 响应头可能抛异常，兜住避免中断预热
                code = -1;
            }

            // 完整读掉响应体（m3u8/302 都很小），再 close 让连接回池。
            // 3xx 不跟随时 getInputStream 可能抛异常，回退读错误流。
            java.io.InputStream is = null;
            try {
                try {
                    is = conn.getInputStream();
                } catch (Throwable t) {
                    is = conn.getErrorStream();
                }
                if (is != null) {
                    byte[] buf = new byte[4096];
                    int n;
                    long total = 0;
                    while ((n = is.read(buf)) > 0 && total < 256 * 1024L) {
                        total += n;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            }
            long tConn = android.os.SystemClock.elapsedRealtime();
            LogCollector.getInstance().info("MyApplication",
                    "流地址预热完成: " + host + " code=" + code
                            + " DNS=" + (tDns - t0) + "ms 建连+响应+首包=" + (tConn - tDns) + "ms"
                            + " 总计=" + (tConn - t0) + "ms（连接已回池供播放复用）");
        } catch (Throwable t) {
            LogCollector.getInstance().info("MyApplication",
                    "流地址预热失败(不影响播放): " + host + " " + t.getMessage());
        }
    }

    /** 供上层（如首次自动播放前）尽早预热指定流地址：异步后台执行、全兜异常。 */
    public static void prewarmStreamAsync(final String url) {
        if (url == null || !url.startsWith("http")) return;
        try {
            new Thread(() -> prewarmUrl(url), "dns-prewarm-extra").start();
        } catch (Throwable ignored) {}
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

        // 【2026-10-17 启动提速】广告剥离代理（WebServerManager:10481）预启动：
        //   原为 doPlay 时懒启动 + 主线程 Thread.sleep 等待（最多 2s），冷启动首个频道起播时
        //   白白多付启动窗口。这里在 Application 阶段就后台拉起，起播时 getRunningInstance()
        //   直接命中，省 ~100-300ms 并消除主线程阻塞等待风险。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (WebServerManager.getRunningInstance() == null) {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        WebServerManager ws = new WebServerManager(MyApplication.this, 10481);
                        ws.start();
                        LogCollector.getInstance().info("MyApplication",
                                "广告剥离代理预启动完成: " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                    }
                } catch (Throwable t) {
                    LogBridge.w("MyApplication", "广告剥离代理预启动失败: " + t.getMessage());
                }
            }
        }, "init-webserver").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                initSecurity();
            }
        }, "init-security").start();

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

                // 虎牙流信息仓库改为「首帧触发 + 兜底超时」：由播放器出画面时
                // onFirstFrameShown() 触发；这里保留兜底，避免首帧迟迟不来时仓库永不就绪。
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initHuyaStreamRepoDeferred("兜底超时");
                    }
                }, HUYA_REPO_INIT_FALLBACK_MS);
            }
        }, 2000);
    }

    /**
     * 【2026-10-22 I1-07】Bugly 初始化（异步 + App ID 来自 native secstore）。
     *
     * <p>背景：原实现把 App ID 以字符串字面量写在 dex 里（`"4a23007223"`），
     * 任何 `strings classes.dex` 都能捞到；且在主线程直接 init 会与 SO 加载抢时序。
     *
     * <p>现在：App ID 只在 libtvlive_security.so（secstore id=27）里，这里等 SO 加载完成
     * （最多 4s，超时就跳过）后再初始化，全程脱离主线程。取不到只意味着「不上报崩溃」，
     * 不影响任何功能。
     */
    private void initBuglyAsync() {
        try {
            Thread t = new Thread(() -> {
                for (int i = 0; i < 80 && !SecurityCore.isLoaded(); i++) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                String appId = null;
                try {
                    appId = SecurityCore.secureString(SecurityCore.C_BUGLY_APP_ID);
                } catch (Throwable ignored) {
                }
                if (appId == null || appId.length() == 0) {
                    LogBridge.d("MyApplication", "Bugly 跳过：native 常量不可用（不影响功能）");
                    return;
                }
                try {
                    com.tencent.bugly.crashreport.CrashReport.UserStrategy strategy =
                            new com.tencent.bugly.crashreport.CrashReport.UserStrategy(MyApplication.this);
                    strategy.setAppVersion(BuildConfig.VERSION_NAME);
                    strategy.setAppReportDelay(10000); // 10秒延迟上报，避免启动时阻塞
                    com.tencent.bugly.crashreport.CrashReport.initCrashReport(
                            getApplicationContext(), appId, !BuildConfig.IS_DEBUG);
                    LogCollector.getInstance().info("MyApplication", "Bugly 崩溃上报初始化完成");
                } catch (Throwable e) {
                    LogBridge.w("MyApplication", "Bugly init failed: " + e.getMessage());
                }
            }, "init-bugly");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "Bugly 线程启动失败: " + e.getMessage());
        }
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
            // 【2026-10-27 去日志双写】SecurityCore.init 内部已打「SECURITY_INIT: Native SO loaded OK」
            // 与「签名哈希已绑定 native，校验结果=…」，此处再打「SecurityCore 初始化成功」是同一事件第三行。
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "SecurityCore init failed: " + e.getMessage());
        }

        try {
            // fillPublicFields 内部会打一条「URL 配置解密完成 …」（含 RAW 就绪位），
            // 【2026-10-27 日志单写】这里不再重复打一条同义日志。
            UrlConfig.fillPublicFields();
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
            // 【2026-10-27 去日志双写】DexProtector.init 内部已打 "DEX hash: …"，
            // 此处再打「DEX 保护初始化完成」= 同一事件两行。
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "DEX 保护初始化失败: " + e.getMessage());
        }

        // 【2026-10-15 移除虎牙死代码】原此处调用 StringProtector.init + register("huya_app_id" /
        // "huya_app_key" / "huya_game_id")，用固定密钥 XOR 混淆三个虎牙占位常量。
        // 虎牙 SDK 已于 2026-10-08 整体拆除，且全仓库已无任何代码读取这三个键
        // （StringProtector.get() 零调用者），属纯死代码 —— 连同其固定密钥一并删除。

        try {
            SecurityGuard.init(this);
            // 【2026-10-27 去日志双写】SecurityGuard.init 内部已打「安全守卫已启用」。
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

            // 【2026-10-27 去日志双写】结果行统一由 SecurityCore.performFullSecurityCheck 输出：
            //   逐项威胁 → 各 check 内的 ⚠️ 行；结论 → 「安全检测完成：发现 N 项风险」。
            // 此处原来再打一条「安全检测发现风险: <同一份 summary>」，与 SecurityCore 的
            // 「安全检测完成: <同一份 summary>」正文完全一致、间隔 0~1ms → 一次检测固定两行。
            guardManager.performFullSecurityCheck();
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

    private void initLogServices() {
        try {
            ExceptionReporter.init(this);
            ExceptionReporter.setEnabled(!BuildConfig.IS_DEBUG);
            // 【2026-10-27 去日志双写】ExceptionReporter.init 内部已打「initialized (policy…)」。
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "ExceptionReporter init failed: " + e.getMessage());
        }

        // 【2026-10-16】腾讯 Bugly 崩溃上报初始化（必须在 CrashHandler 之前）
        // 【2026-10-22 I1-07】App ID 不再明文写在 dex 里：改为从 libtvlive_security.so
        //   的 secstore 常量（id=27）取，且延后到 SO 就绪后再初始化——取不到只丢崩溃上报，
        //   不影响任何功能，也绝不在主线程等 loadLibrary。
        initBuglyAsync();

        try {
            CrashHandler.getInstance().init(this);
            // 【2026-10-27 去日志双写】CrashHandler.init 内部已打「全局崩溃捕获器已初始化」。
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "CrashHandler init failed: " + e.getMessage());
        }

        try {
            LogServer.getInstance(this).start();
            // 【2026-10-27 去日志双写】原此处打「LogServer 启动成功: ip:port」，而 LogServer 内部
            // 还会打「starting on port …」和「HTTP Server listening on port …」→ 一次启动三行。
            // 现收敛为 LogServer 内部那一条（绑定成功后才打，并带上本机 IP 便于直接访问 9527）。
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "LogServer init failed (ignore): " + e.getMessage());
        }

        // 【2026-10-16】启动本地广告剥离代理服务器（端口 10481）
        // 【2026-10-22 I1-05】补 getRunningInstance() 守卫，与 initializeAsync 预启动对称：
        //   此前此处无守卫，与预启动竞态时双实例并存（第二个 findAvailablePort 漂移绑
        //   10482 并篡夺 runningInstance）。WebServerManager.start() 内已有静态单实例
        //   守卫兜底，这里再加一层语义明确的跳过。
        try {
            if (WebServerManager.getRunningInstance() == null) {
                WebServerManager adStripServer = new WebServerManager(this, 10481);
                adStripServer.start();
                // start() 是异步的，等待最多 2 秒让服务器启动
                for (int i = 0; i < 20; i++) {
                    if (adStripServer.isRunning()) break;
                    Thread.sleep(100);
                }
                if (adStripServer.isRunning()) {
                    LogCollector.getInstance().info("MyApplication", "广告剥离代理启动成功: 127.0.0.1:" + adStripServer.getPort());
                } else {
                    LogBridge.w("MyApplication", "广告剥离代理启动失败，端口可能被占用");
                }
            } else {
                LogCollector.getInstance().info("MyApplication", "广告剥离代理已在运行，跳过重复启动: 127.0.0.1:"
                        + WebServerManager.getRunningInstance().getPort());
            }
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "广告剥离代理启动失败 (ignore): " + e.getMessage());
        }

        // 【2026-10-29 恢复】启动日志文件旁路恢复后，应用启动时裁剪 boot_logs.txt 体积
        BootReceiver.trimBootLog(this);

    }

    private void initCacheCleanup() {
        // 【2026-10-25 首帧优先】缓存巡检 263ms + 清理 722ms（扫 296 个文件）原本在启动后
        //   2s 就跑，正好压在首帧上。纯后台任务，延后到首帧后执行；IO 仍留在独立线程，
        //   不要把扫描挪到主线程。
        runAfterFirstFrame(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            AppCacheInspector.startupCleanup(MyApplication.this);
                        } catch (Exception e) {
                            LogBridge.w("MyApplication", "AppCacheInspector failed: " + e.getMessage());
                        }
                        try {
                            AppCacheGovernor.startupCleanup(MyApplication.this);
                        } catch (Exception e) {
                            LogBridge.w("MyApplication", "AppCacheGovernor failed: " + e.getMessage());
                        }
                    }
                }, "init-cache-cleanup").start();
            }
        }, 10000L);
    }

    /**
     * 播放器出画面（STATE_READY）后调用，此刻再就绪虎牙流信息仓库不会与首帧争抢资源。
     * 幂等：与 30s 兜底竞争，先到者执行，另一方直接返回。
     */
    public void onFirstFrameShown() {
        try {
            initHuyaStreamRepoDeferred("首帧后");
        } catch (Throwable t) {
            LogBridge.w("MyApplication", "onFirstFrameShown 触发流信息仓库就绪失败: " + t.getMessage());
        }
        runDeferredAfterFirstFrameTasks();
    }

    // ================= 【2026-10-25 首帧优先】启动期重任务延后执行 =================
    // 背景（9527 冷启动实测 14:58:32~14:58:36）：
    //   启动后 EPG 磁盘缓存解析（2.5MB XML）在这台电视上要 **2916ms**，
    //   同时还有缓存巡检 263ms + 缓存清理 722ms 一起跑，
    //   全部挤在首帧那 2 秒里和解码抢 CPU → 日志里直接出现
    //   「[PLAY] 短暂缓冲恢复，时长：1977ms」，用户看到的就是“打开软件画面卡几秒”。
    //   这些任务都不影响出画（EPG 只影响信息栏节目名、缓存清理纯后台），
    //   因此统一挂到首帧之后再跑；首帧迟迟不来（无信号等）由兜底超时放行。
    private static final long FIRST_FRAME_GRACE_MS = 1200L;   // 首帧后再等 1.2s，避开淡入与首缓冲
    private volatile boolean firstFrameShown = false;
    private final java.util.List<Runnable> pendingAfterFirstFrame =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.Map<Runnable, Long> pendingGrace =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 首帧是否已经出过（供调用方决定要不要给「首帧宽限」延迟）。 */
    public boolean isFirstFrameShown() {
        return firstFrameShown;
    }

    /**
     * 把启动期重任务挂到「首帧之后 + 1.2s」执行；若首帧一直不来，超时后照样执行。
     * 注意：传入的 Runnable 会在调用方线程/主线程执行，涉及 IO/CPU 请自行切后台线程。
     */
    public void runAfterFirstFrame(final Runnable task, long fallbackTimeoutMs) {
        runAfterFirstFrame(task, fallbackTimeoutMs, FIRST_FRAME_GRACE_MS);
    }

    /** 同上，可自定义首帧后的宽限延迟（越吃 CPU 的任务应给越长的宽限）。 */
    public void runAfterFirstFrame(final Runnable task, long fallbackTimeoutMs, final long graceMs) {
        if (task == null) return;
        final long grace = Math.max(0L, graceMs);
        if (firstFrameShown) {
            mainHandler.postDelayed(task, grace);
            return;
        }
        pendingAfterFirstFrame.add(task);
        pendingGrace.put(task, grace);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 兜底：首帧超时未到（黑屏/无信号/未起播），也要把任务放出去
                if (pendingAfterFirstFrame.remove(task)) {
                    pendingGrace.remove(task);
                    LogBridge.i("MyApplication", "【首帧优先】首帧超时未到，兜底放行延后任务");
                    task.run();
                }
            }
        }, Math.max(fallbackTimeoutMs, grace));
    }

    private void runDeferredAfterFirstFrameTasks() {
        if (firstFrameShown) return;
        firstFrameShown = true;
        if (pendingAfterFirstFrame.isEmpty()) return;
        // 每个任务各自的宽限延迟不同，这里统一收集后按各自的 grace 投递。
        final java.util.List<java.util.Map.Entry<Runnable, Long>> tasks =
                new java.util.ArrayList<>();
        for (Runnable r : pendingAfterFirstFrame) tasks.add(new java.util.AbstractMap.SimpleEntry<>(r, pendingGrace.get(r)));
        pendingAfterFirstFrame.clear();
        pendingGrace.clear();
        for (final java.util.Map.Entry<Runnable, Long> e : tasks) {
            long grace = e.getValue() == null ? FIRST_FRAME_GRACE_MS : e.getValue();
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try { e.getKey().run(); } catch (Throwable t) {
                        LogBridge.w("MyApplication", "【首帧优先】延后任务执行失败: " + t.getMessage());
                    }
                }
            }, grace);
        }
        LogBridge.d("MyApplication", "【首帧优先】首帧已到，安排 " + tasks.size() + " 个启动期任务延后执行");
    }

    private void initHuyaStreamRepoDeferred(String reason) {
        if (!sHuyaStreamRepoStarted.compareAndSet(false, true)) {
            return;
        }
        LogCollector.getInstance().info("MyApplication", "虎牙流信息仓库就绪开始（触发源：" + reason + "）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                initSecurityGuardManager();
                initSecureDataStore();
                initHuyaStreamRepo();
            }
        }, "security-deferred").start();
    }

    private void initHuyaStreamRepo() {
        try {
            HuyaSDKParser.init(MyApplication.this);
            LogCollector.getInstance().info("MyApplication", "虎牙流信息仓库就绪");
        } catch (Throwable e) {
            LogBridge.e("MyApplication", "虎牙流信息仓库初始化异常: " + e.getMessage());
        }
    }

}
