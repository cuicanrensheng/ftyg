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
import com.tv.live.util.CacheManager.AppCacheInspector;
import com.tv.live.util.LogBridge.LogCollector;
import com.tv.live.util.LogBridge.LogServer;
import com.tv.live.util.LogBridge.CrashHandler;
import com.tv.live.util.NetUtil;
import com.tv.live.util.CacheManager.HuyaCacheGovernor;
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
import com.tv.live.security.SecurityCore.StringProtector;
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
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
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

    private void initLogServices() {
        try {
            ExceptionReporter.init(this);
            ExceptionReporter.setEnabled(!BuildConfig.IS_DEBUG);
            LogCollector.getInstance().info("MyApplication", "全局异常收集初始化完成");
        } catch (Throwable e) {
            LogBridge.w("MyApplication", "ExceptionReporter init failed: " + e.getMessage());
        }

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
