package com.tv.live.security;

import android.content.Context;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;
import com.tv.live.BuildConfig;
import com.tv.live.util.LogBridge;
import com.tv.live.util.LogBridge.LogCollector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityCore {

    private static final String TAG = "SecurityCore";

    private static volatile boolean sLoaded = false;

    private SecurityCore() {}

    public static synchronized void init() {
        if (sLoaded) return;
        try {
            System.loadLibrary("tvlive_security");
            sLoaded = true;
            LogBridge.d(TAG, "libtvlive_security.so 加载成功");
            LogBridge.i(TAG, "SECURITY_INIT: Native SO loaded OK");
            // 【2026-10-22 I1-07】把当前证书 SHA-256 绑给 native：
            //   之后 AES 工作密钥与 secstore 常量都由「签名哈希 + 内置片段」派生，
            //   签名不匹配时解出的是乱码（而不是靠可被 patch 的 killProcess 拦）。
            tryBindSignature();
        } catch (Throwable t) {
            sLoaded = false;
            LogBridge.w(TAG, "libtvlive_security.so 加载失败: " + t.getMessage());
            LogBridge.w(TAG, "SECURITY_INIT: Native SO FAILED: " + t.getMessage());
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    // ===== 【2026-10-22 I1-07】签名 → native 绑定（密钥派生用） =====
    // SecurityCore.init() 是无参静态方法，拿不到 Context；Application 会在最早时机
    // 调 setAppContext()，之后任何一次 init()/取常量都会尝试绑定，绑定成功后不再重复。
    private static volatile Context sCtx = null;
    private static volatile boolean sSigBound = false;

    public static void setAppContext(Context ctx) {
        if (ctx != null && sCtx == null) {
            try { sCtx = ctx.getApplicationContext(); } catch (Throwable ignored) {}
        }
    }

    /** 把当前证书 SHA-256 绑定给 native；未加载/无 context/已绑定都是无操作。 */
    private static void tryBindSignature() {
        if (sSigBound || !sLoaded) return;
        Context c = sCtx;
        if (c == null) return;
        try {
            byte[] sha = currentSignatureSha256(c);
            if (sha == null || sha.length != 32) return;
            nativeBindSignature(sha);
            sSigBound = true;
            // 只打印布尔结果，不打印哈希本身（哈希是公开的证书摘要，但没必要进日志）
            // 探针：绑定后再取一个常量，看它是否还是合法形态（http 开头）。
            // 只为排障：不打印内容本身，避免常量再次进日志。
            String probe = null;
            try { probe = secureString(C_LIVE_1); } catch (Throwable ignored) {}
            boolean okForm = probe != null && probe.startsWith("http");
            LogBridge.i(TAG, "签名哈希已绑定 native，校验结果=" + (nativeSignatureOk() ? "匹配" : "不匹配")
                    + "，常量污染=" + (nativePoisoned() == 1 ? "开启" : "关闭")
                    + "，常量形态=" + (okForm ? "正常" : "异常"));
        } catch (Throwable ignored) {
        }
    }

    /** 取当前 APK 签名证书的 SHA-256（32 字节）；失败返回 null。 */
    private static byte[] currentSignatureSha256(Context appCtx) {
        try {
            PackageManager pm = appCtx.getPackageManager();
            PackageInfo pi;
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = (pi.signingInfo != null && pi.signingInfo.hasMultipleSigners())
                        ? pi.signingInfo.getApkContentsSigners()
                        : (pi.signingInfo != null ? pi.signingInfo.getSigningCertificateHistory() : null);
            } else {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            return MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void antiDebug() {
        if (sLoaded) {
            try { nativeAntiDebug(); } catch (Throwable ignored) {}
        }

        try {
            // 【2026-10-13 加固】proc 路径与字段名由 ObStr 运行时解码
            final String statusPath = ObStr.d(ObStr.S_PROC_STATUS);
            final String tracerKey = ObStr.d(ObStr.T_TRACERPID_COLON);
            String status = readFile(statusPath);
            if (status != null) {
                for (String line : status.split("\n")) {
                    if (line.startsWith(tracerKey)) {
                        String v = line.substring(tracerKey.length()).trim();
                        if (!"0".equals(v) && !v.isEmpty()) {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(9);
                            return;
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void startMonitor() {
        if (sLoaded) {
            try { nativeStartMonitor(); } catch (Throwable ignored) {}
        }
    }

    public static int check() {
        if (!sLoaded) return 0;
        try { return nativeCheck(); } catch (Throwable t) { return 0; }
    }

    public static String getSecurityStatus() {
        if (!sLoaded) return "SecurityCore not loaded";
        try { return nativeGetSecurityStatus(); } catch (Throwable t) { return "error: " + t.getMessage(); }
    }

    // =============================================================
    // 【2026-10-13 原生扩展】以下能力全部走 libtvlive_security.so，
    // 任一失败都返回 null / 负数，不抛异常——调用方按「不可用」处理即可。
    // 与既有 CBC 解密路径完全解耦，不影响 UrlConfig 的解密。
    // =============================================================

    /** SHA-256，返回 32 字节；不可用返回 null */
    public static byte[] sha256(byte[] data) {
        if (!sLoaded || data == null) return null;
        try { return nativeSha256(data); } catch (Throwable t) { return null; }
    }

    /** HMAC-SHA256，返回 32 字节；不可用返回 null */
    public static byte[] hmacSha256(byte[] key, byte[] msg) {
        if (!sLoaded || key == null || msg == null) return null;
        try { return nativeHmacSha256(key, msg); } catch (Throwable t) { return null; }
    }

    /** HKDF-SHA256 派生密钥，salt/info 允许为 null；不可用返回 null */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen) {
        if (!sLoaded || ikm == null || outLen <= 0) return null;
        try { return nativeHkdfSha256(ikm, salt, info, outLen); } catch (Throwable t) { return null; }
    }

    /** AES-256-GCM 加密。iv 建议 12 字节；aad 可为 null。返回 密文‖16 字节标签 */
    public static byte[] aesGcmEncrypt(byte[] key, byte[] iv, byte[] plain, byte[] aad) {
        if (!sLoaded || key == null || key.length != 32 || iv == null || iv.length == 0 || plain == null) {
            return null;
        }
        try { return nativeAesGcmEncrypt(key, iv, plain, aad); } catch (Throwable t) { return null; }
    }

    /** AES-256-GCM 解密并验签。入参为 密文‖16 字节标签；标签不符返回 null */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] ctAndTag, byte[] aad) {
        if (!sLoaded || key == null || key.length != 32 || iv == null || iv.length == 0
                || ctAndTag == null || ctAndTag.length < 16) {
            return null;
        }
        try { return nativeAesGcmDecrypt(key, iv, ctAndTag, aad); } catch (Throwable t) { return null; }
    }

    /**
     * X25519 (RFC 7748) 标量乘：out = X25519(scalar, uPoint)，两者均须 32 字节。
     * scalar 在 native 内钳制（k[0]&amp;=248; k[31]&amp;=127; k[31]|=64）。
     * 不可用或参数非法返回 null。
     */
    public static byte[] x25519(byte[] scalar, byte[] uPoint) {
        if (!sLoaded || scalar == null || scalar.length != 32
                || uPoint == null || uPoint.length != 32) {
            return null;
        }
        try { return nativeX25519(scalar, uPoint); } catch (Throwable t) { return null; }
    }

    /**
     * 代码段自校验：0=未改动，1=.text 被改写（疑似 inline hook），负数=不可用。
     * 只在 so 加载成功时有意义。
     */
    public static int selfCheck() {
        if (!sLoaded) return -1;
        try { return nativeSelfCheck(); } catch (Throwable t) { return -1; }
    }

    /** 自身可执行段长度（字节），用于确认自校验真的在跑 */
    public static int textSegmentLength() {
        if (!sLoaded) return -1;
        try { return nativeTextLen(); } catch (Throwable t) { return -1; }
    }

    /** 最近一次自校验结果（不重新计算） */
    public static int lastSelfCheck() {
        if (!sLoaded) return -1;
        try { return nativeLastSelfCheck(); } catch (Throwable t) { return -1; }
    }

    public static String decryptToString(String cipherB64) {
        if (cipherB64 == null) {
            LogBridge.i(TAG, "DECRYPT: cipherB64 is null");
            return null;
        }
        byte[] cipher;
        try {
            cipher = Base64.decode(cipherB64, Base64.NO_WRAP);
        } catch (Throwable t) {
            LogBridge.e(TAG, "DECRYPT: Base64 decode failed: " + t.getMessage());
            return null;
        }

        if (cipher == null || cipher.length < 32) {
            LogBridge.e(TAG, "DECRYPT: cipher invalid, len=" + (cipher != null ? cipher.length : "null"));
            return null;
        }

        // 【2026-09-24】init() 在后台线程 earlyUrl 中执行，若主线程先调用解密，
        // sLoaded 可能仍为 false。此处兜底：未加载则同步 init 一次再走 native。
        if (!sLoaded) {
            try { init(); } catch (Throwable ignored) {}
        }

        if (sLoaded) {
            try {
                byte[] plain = nativeDecrypt(cipher);
                if (plain != null) {
                    String s = new String(plain, StandardCharsets.UTF_8);
                    Arrays.fill(plain, (byte) 0);
                    LogBridge.d(TAG, "DECRYPT: Native decrypted OK, len=" + s.length());
                    return s;
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "Native 解密失败: " + t.getMessage());
            }
        }
        return null;
    }

    // =============================================================
    // 【2026-10-13 SO 内建加密】敏感常量仓库 + 正向加密
    //
    // 取代原先「Java 侧 Base64 密文常量 + 外部脚本生成」的方案：
    //   · 明文与密文都不再出现在 dex 里，常量整表下沉到 cpp/secstore.cpp，
    //     编译期异或后存进 .so 的 .rodata；
    //   · 增删/轮换常量 = 改 secstore.cpp 一行 + 重新编译，
    //     不再依赖 gen_cipher.py / _gen_cipher.mjs（脚本丢了也不影响）；
    //   · encryptToString() 提供对称的加密出口，需要密文时随时可由 SO 产出。
    // =============================================================

    /** secstore.cpp 常量编号（必须与 native 侧 switch 严格一致） */
    public static final int C_LIVE_1   = 1;   // 源1 订阅地址
    public static final int C_LIVE_2   = 2;   // 源2 订阅地址
    public static final int C_EPG_2    = 3;   // EPG-2（erw.cc 镜像）
    public static final int C_EPG_1    = 6;   // EPG-1（catvod，主 EPG）
    public static final int C_VOD_BASE = 4;   // 源5 点播端点
    public static final int C_LIVE_PHP = 5;   // 源5 直播端点（含 token）
    public static final int C_LIVE_HOST = 7;  // 源5 直播服务器主机名（识别 302 跳转器用）
    public static final int C_VOD_KEY   = 8;  // 源5 点播 AES-128 密钥（hex）

    // ---- 【2026-10-15 第三批】上游 API 端点密文化（原为 public static final 明文常量）----
    // ⚠️ 这批**未**迁移 HTTPS（服务端 TLS 支持未验证），明文仍是 http:// 原样。
    public static final int C_API_NOWTV_NEW = 9;   // 主直播 host（nowtv-new.xiaoyouzb.cn）
    public static final int C_API_NOWTV     = 10;  // 旧版直播 host（nowtv.xiaoyouzb.cn）
    public static final int C_API_USER_CN   = 11;  // 用户服务 host（user.xiaoyouzb.cn）
    public static final int C_API_PROBE     = 12;  // 探测 host（probe.xiaoyouzb.cn）
    public static final int C_API_A8        = 13;  // A8 游戏接口 host
    public static final int C_API_USER_NET  = 14;  // 用户服务 host（user.xiaoyouzb.net）
    public static final int C_API_NOWTV_NET = 15;  // JS 插件热更元数据接口（.net）
    public static final int C_API_GITV_AUTH = 16;  // GITV 点播鉴权端点前缀
    public static final int C_API_GITV_KEY  = 17;  // GITV 鉴权固定 key（128 字符）

    // ---- 【2026-10-17 第四批】源5 加密线路「服务端解析」端点（见 VodLineResolver）----
    public static final int C_VODP_VERTIFY = 18;   // vertify.php（官方① BBA/deja-）
    public static final int C_VODP_JXXH    = 19;   // jxxh.php（官方③ ZY-icial_/ZY-anime_）
    public static final int C_VODP_ZSYZX   = 20;   // zsyzx.php（官方⑥ yzx-）
    public static final int C_VODP_4KYS    = 21;   // 4kys.php（高清① 4kvm.top/play）
    public static final int C_VODP_NBY     = 22;   // 43.248.96.62 /api（官方② NBY）
    public static final int C_VODP_CO      = 23;   // 43.248.102.96 /api（官方⑤ co_）
    public static final int C_VODP_ACE     = 24;   // 175.24.181.180:5000 /api/jiexi（Ace）
    public static final int C_VODP_YKTX    = 25;   // 118.107.11.4 /jx（官方④ YKTX）
    public static final int C_VODP_DMM     = 26;   // 49.233.180.127:7788/dmm.php（动漫专线 ZY-anime_）
    public static final int C_BUGLY_APP_ID = 27;   // 腾讯 Bugly App ID（I1-07 卫生项：dex 不再有明文）

    // 【2026-10-23 第五批】内置源9 橘汁（com.tv.live.juzhi）协议常量。
    // 原为 JzProtocol 的 Java 常量（多为 public static final → 编译期内联，dex 明文可见）。
    // ⚠ 与 cpp/secstore.cpp 的 case 必须成对维护；取值见 JzProtocol 的惰性 getter。
    public static final int C_JZ_SAFE_CODE       = 28;  // 橘汁 body 加密工作密钥（AES-256）
    public static final int C_JZ_SAFE_SECRET_KEY = 29;  // 橘汁 sig2/sig3 与 GET query 加密密钥
    public static final int C_JZ_DECRYPT_KEY     = 30;  // 橘汁 firstLevelPublic 解密密钥
    public static final int C_JZ_OSS_URL_1       = 31;  // 域名引导 OSS①（cos.ap-guangzhou）
    public static final int C_JZ_OSS_URL_2       = 32;  // 域名引导 OSS②（cos.ap-chengdu）
    public static final int C_JZ_OSS_URL_3       = 33;  // 域名引导 OSS③（cos.ap-shanghai）
    public static final int C_JZ_OSS_URL_4       = 34;  // 域名引导 OSS④（123pan tv4.txt）
    public static final int C_JZ_OSS_URL_5       = 35;  // 域名引导 OSS⑤（123pan tv5.txt）
    public static final int C_JZ_FALLBACK_BASE   = 36;  // 橘汁兜底 baseUrl（OSS 全不可达时）
    public static final int C_JZ_FLP_CIPHER      = 37;  // firstLevelPublic 密文（与 id=30 成对）
    public static final int C_JZ_SLP_FALLBACK    = 38;  // secondLevelPublic 兜底 RSA 公钥
    public static final int C_JZ_V_APP           = 39;  // 橘汁 versionCode（服务端按它校 sig）
    public static final int C_JZ_V_NAME          = 40;  // 橘汁 versionName
    public static final int C_JZ_PKG             = 41;  // 橘汁包名 com.exxammpea.a1
    public static final int C_JZ_APP_NAME        = 42;  // 橘汁 App 名
    public static final int C_JZ_PATH_SEARCH     = 43;  // 点播搜索接口
    public static final int C_JZ_PATH_DETAIL     = 44;  // 详情/集数接口
    public static final int C_JZ_PATH_PLAY_URL   = 45;  // 换直链接口
    public static final int C_JZ_PATH_RSA_ZONE   = 46;  // 密钥协商端点（RSARequest 专用）
    public static final int C_JZ_PATH_CATEGORY   = 47;  // 直播分组接口
    public static final int C_JZ_PATH_LIVE_LIST  = 48;  // 直播分组卡/频道列表（GET ?query=）
    public static final int C_JZ_PATH_TAG_LIST   = 49;  // tab 列表（GET ?query=）
    public static final int C_HUYA_PHP           = 50;  // 虎牙第三条线路（任意播）huya.php 端点前缀（含 token）

    /**
     * 取加密常量明文。SO 不可用时返回 null（调用方按「该源不可用」降级）。
     * 不可缓存到 static final：init() 要 loadLibrary，可能耗时。
     */
    public static String secureString(int id) {
        if (!sLoaded) {
            try { init(); } catch (Throwable ignored) {}
        }
        if (!sLoaded) return null;
        tryBindSignature();
        try { return nativeConst(id); } catch (Throwable t) { return null; }
    }

    /**
     * AES-256-CBC 加密（与 {@link #decryptToString} 完全对称：iv(16B)‖ct，整体 base64）。
     * 用于需要落盘/下发的密文场景；SO 不可用返回 null。
     */
    public static String encryptToString(String plain) {
        if (plain == null) return null;
        if (!sLoaded) {
            try { init(); } catch (Throwable ignored) {}
        }
        if (!sLoaded) return null;
        try {
            byte[] ct = nativeEncrypt(plain.getBytes(StandardCharsets.UTF_8));
            if (ct == null) return null;
            String b64 = Base64.encodeToString(ct, Base64.NO_WRAP);
            Arrays.fill(ct, (byte) 0);
            return b64;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            byte[] buf = new byte[4096];
            int n = fis.read(buf);
            fis.close();
            return n > 0 ? new String(buf, 0, n, StandardCharsets.ISO_8859_1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static native void nativeAntiDebug();
    private static native void nativeStartMonitor();
    private static native int  nativeCheck();
    private static native byte[] nativeDecrypt(byte[] cipher);
    private static native String nativeGetSecurityStatus();

    // 【2026-10-13 原生扩展】AES-GCM / SHA-256 / HMAC / HKDF 与代码段自校验
    private static native byte[] nativeSha256(byte[] data);
    private static native byte[] nativeHmacSha256(byte[] key, byte[] msg);
    private static native byte[] nativeHkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen);
    private static native byte[] nativeAesGcmEncrypt(byte[] key, byte[] iv, byte[] plain, byte[] aad);
    private static native byte[] nativeAesGcmDecrypt(byte[] key, byte[] iv, byte[] ctAndTag, byte[] aad);
    // 【2026-10-21】X25519 (RFC 7748) 标量乘，供 NbMovie 密钥交换；
    // scalar 内部按 RFC 钳制，uPoint 高位屏蔽，与 Go curve25519.X25519 对齐。
    private static native byte[] nativeX25519(byte[] scalar, byte[] uPoint);
    private static native int  nativeSelfCheck();
    private static native int  nativeTextLen();
    private static native int  nativeLastSelfCheck();

    // 【2026-10-13 SO 内建】敏感常量仓库（cpp/secstore.cpp）与正向加密
    private static native String nativeConst(int id);
    // 【2026-10-22 I1-07】签名哈希绑定 / 判定：期望值只在 .so 里，dex 不再有明文
    private static native void nativeBindSignature(byte[] sigSha256);
    private static native boolean nativeSignatureOk();
    private static native int nativePoisoned();
    private static native byte[] nativeEncrypt(byte[] plain);

    public static class SecurityGuard {

    private static final String TAG = "SecurityGuard";

    public static final int THREAT_DEBUGGER = 1;
    public static final int THREAT_FRIDA = 2;
    public static final int THREAT_XPOSED = 3;
    public static final int THREAT_ROOT = 4;
    public static final int THREAT_EMULATOR = 5;
    public static final int TAMPER_DETECTED = 6;
    public static final int SIGNATURE_MISMATCH = 7;
    public static final int HOOK_DETECTED = 8;

    public static final int RESPONSE_LOG = 0;
    public static final int RESPONSE_WARN = 1;
    public static final int RESPONSE_BLOCK = 2;
    public static final int RESPONSE_EXIT = 3;
    public static final int RESPONSE_SELF_DESTRUCT = 4;

    private static volatile boolean sInitialized = false;
    private static volatile Context sAppContext = null;
    private static volatile int sHighestThreat = 0;
    private static volatile boolean sDestroyed = false;

    private SecurityGuard() {}

    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        sAppContext = context.getApplicationContext();

        TamperReporter.init(context);

        if (!BuildConfig.IS_DEBUG) {

            startSecurityMonitor();
            LogBridge.i(TAG, "安全守卫已启用");
        } else {
            LogBridge.i(TAG, "安全守卫：调试模式，仅记录日志");
        }
    }

    public static void onThreatDetected(int threatType, String detail) {
        if (sDestroyed) return;

        if (threatType > sHighestThreat) {
            sHighestThreat = threatType;
        }

        String threatName = getThreatName(threatType);
        LogBridge.w(TAG, "⚠️ 检测到安全威胁: " + threatName + " - " + detail);

        reportThreatToReporter(threatType, threatName, detail);

        int responseLevel = getResponseLevel(threatType);

        switch (responseLevel) {
            case RESPONSE_LOG:

                break;

            case RESPONSE_WARN:
                showWarning(threatName, detail);
                break;

            case RESPONSE_BLOCK:
                showWarning(threatName, detail);
                blockFunctionality();
                break;

            case RESPONSE_EXIT:
                showWarning(threatName, detail);
                exitApplication();
                break;

            case RESPONSE_SELF_DESTRUCT:
                showWarning(threatName, detail);
                selfDestruct();
                break;
        }
    }

    private static void reportThreatToReporter(int threatType, String threatName, String detail) {
        try {

            int tamperType = mapThreatToTamperType(threatType);

            TamperReporter.reportTamper(tamperType,
                "SecurityGuard检测到: " + threatName + " | " + detail);

        } catch (Exception e) {
            LogBridge.e(TAG, "上报威胁失败: " + e.getMessage());
        }
    }

    private static int mapThreatToTamperType(int threatType) {
        switch (threatType) {
            case THREAT_DEBUGGER:
                return TamperReporter.TAMPER_DEBUGGER;
            case THREAT_FRIDA:
                return TamperReporter.TAMPER_FRIDA;
            case THREAT_XPOSED:
                return TamperReporter.TAMPER_XPOSED;
            case THREAT_ROOT:
                return TamperReporter.TAMPER_ROOT;
            case THREAT_EMULATOR:
                return TamperReporter.TAMPER_EMULATOR;
            case TAMPER_DETECTED:
                return TamperReporter.TAMPER_DEX_INTEGRITY;
            case SIGNATURE_MISMATCH:
                return TamperReporter.TAMPER_SIGNATURE;
            case HOOK_DETECTED:
                return TamperReporter.TAMPER_HOOK;
            default:
                return TamperReporter.TAMPER_MEMORY;
        }
    }

    private static int getResponseLevel(int threatType) {
        // 【保持】调试构建一律只记录 —— 否则会杀掉自己的调试会话
        if (BuildConfig.IS_DEBUG) {

            return RESPONSE_LOG;
        }

        switch (threatType) {
            case THREAT_DEBUGGER:
                return RESPONSE_EXIT;

            // 【2026-10-13 策略调整】原为 RESPONSE_SELF_DESTRUCT。
            // selfDestruct() 会 clearSensitiveData() + corruptDexFiles()，在真机上会把 APK 的
            // dex 破坏到不可启动 —— 不可逆，对"注入环境"这一档过重。
            // 改为 BLOCK：禁用敏感功能、降级运行。既让检测真正生效，又不毁用户数据。
            // 明确的攻击行为（重打包 TAMPER_DETECTED / 签名不符 SIGNATURE_MISMATCH）仍保留自毁。
            case THREAT_FRIDA:
            case THREAT_XPOSED:
                return RESPONSE_BLOCK;

            case THREAT_ROOT:
                return RESPONSE_WARN;

            case THREAT_EMULATOR:
                return RESPONSE_LOG;

            case TAMPER_DETECTED:
            case SIGNATURE_MISMATCH:
                return RESPONSE_SELF_DESTRUCT;

            case HOOK_DETECTED:
                return RESPONSE_BLOCK;

            default:
                return RESPONSE_LOG;
        }
    }

    private static void showWarning(String threatName, String detail) {
        if (sAppContext == null) return;

        try {
            final String msg = "安全警告: " + threatName + "\n" + detail;
            new Thread(() -> {
                try {
                    Toast.makeText(sAppContext, msg, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            }).start();
        } catch (Exception e) {
            LogBridge.e(TAG, "显示警告失败: " + e.getMessage());
        }
    }

    private static void blockFunctionality() {

        LogBridge.w(TAG, "🔒 已阻止应用功能");
    }

    private static void exitApplication() {
        if (sDestroyed) return;
        sDestroyed = true;

        LogBridge.w(TAG, "🚪 正在退出应用...");

        new Thread(() -> {
            try {
                Thread.sleep(2000);

                clearSensitiveData();

                Process.killProcess(Process.myPid());
                System.exit(0);
            } catch (Exception e) {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "SecurityExit").start();
    }

    private static void selfDestruct() {
        if (sDestroyed) return;
        sDestroyed = true;

        LogBridge.w(TAG, "💥 触发自毁程序...");

        new Thread(() -> {
            try {

                clearSensitiveData();

                Thread.sleep(3000);

                Process.killProcess(Process.myPid());
                System.exit(0);
            } catch (Exception e) {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "SelfDestruct").start();
    }

    private static void clearSensitiveData() {
        try {

            if (sAppContext != null) {
                sAppContext.getSharedPreferences("secure_data", Context.MODE_PRIVATE).edit().clear().apply();
                sAppContext.getSharedPreferences("security_guard", Context.MODE_PRIVATE).edit().clear().apply();
                sAppContext.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().apply();
            }

            if (sAppContext != null) {
                File filesDir = sAppContext.getFilesDir();
                if (filesDir != null) {
                    deleteDirectoryContents(filesDir);
                }

                File cacheDir = sAppContext.getCacheDir();
                if (cacheDir != null) {
                    deleteDirectoryContents(cacheDir);
                }
            }

            LogBridge.i(TAG, "敏感数据已清理");
        } catch (Exception e) {
            LogBridge.e(TAG, "清理数据失败: " + e.getMessage());
        }
    }

    private static void deleteDirectoryContents(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectoryContents(file);
            } else {
                file.delete();
            }
        }
    }

    private static void corruptDexFiles() {

        try {
            if (sAppContext == null) return;

            String dexPath = sAppContext.getApplicationInfo().sourceDir;
            if (dexPath == null) return;

            File apkFile = new File(dexPath);
            if (apkFile.exists()) {
                RandomAccessFile raf = new RandomAccessFile(apkFile, "rw");
                raf.seek(apkFile.length() - 100);
                byte[] garbage = new byte[100];
                new java.util.Random().nextBytes(garbage);
                raf.write(garbage);
                raf.close();
            }

        } catch (Exception e) {

        }
    }

    private static void startSecurityMonitor() {
        if (sAppContext == null) return;

        // 【2026-09-24】威胁告警去重：首次检测打 warn，后续重复检测仅打 debug，
        // 避免模拟器环境下每 30 秒刷屏（Frida/Xposed/Root 在模拟器中恒为阳性）。
        final boolean[] reported = {false, false, false, false}; // frida, xposed, debugger, root

        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

            while (!sDestroyed && !BuildConfig.IS_DEBUG) {
                try {
                    // 【2026-10-15】检测间隔从30秒改为30分钟，避免模拟器环境下频繁触发
                    Thread.sleep(30 * 60 * 1000);

                    if (checkFrida()) {
                        if (!reported[0]) { LogBridge.d(TAG, "安全监控：命中 [CHK-01]"); reported[0] = true; }
                        else {  }
                        onThreatDetected(THREAT_FRIDA, "运行时注入环境异常");
                    }

                    if (checkXposed()) {
                        if (!reported[1]) { LogBridge.d(TAG, "安全监控：命中 [CHK-02]"); reported[1] = true; }
                        else {  }
                        onThreatDetected(THREAT_XPOSED, "运行时注入框架异常");
                    }

                    if (android.os.Debug.isDebuggerConnected()) {
                        if (!reported[2]) { LogBridge.d(TAG, "检测到调试器连接（仅记录，不上报）"); reported[2] = true; }
                        else {  }
                    }

                    if (checkRoot()) {
                        if (!reported[3]) { LogBridge.d(TAG, "检测到 Root 环境（仅记录）"); reported[3] = true; }
                        else {  }
                    }

                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    LogBridge.e(TAG, "安全监控异常: " + e.getMessage());
                }
            }
        }, "SecurityMonitor").start();
    }

    private static boolean checkFrida() {
        try {

            int[] fridaPorts = {27042, 27043, 27044, 27045};
            for (int port : fridaPorts) {
                java.net.Socket socket = new java.net.Socket();
                try {
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
                    socket.close();
                    return true;
                } catch (Exception e) {

                }
            }

            // 【2026-10-13 加固】进程名与命令改由 ObStr 运行时解码
            String[] fridaProcesses = {
                ObStr.d(ObStr.F_FRIDA_SRV),
                ObStr.d(ObStr.F_FRIDA_AGENT),
                ObStr.d(ObStr.F_FRIDA_INJECT)
            };
            String processList = executeCommand(ObStr.d(ObStr.R_PS_CMD));
            if (processList != null) {
                for (String proc : fridaProcesses) {
                    if (processList.contains(proc)) {
                        return true;
                    }
                }
            }

            // 【2026-10-13 口径对齐 · 关键修复】原实现只查端口与进程，漏掉了文件路径检测，
            // 于是出现「AntiDebug 命中文件、本监控线程判定未命中」的错位，
            // 导致 release 包检测到 Frida 也不进阻断分支（实测 2.0.204 即如此）。
            String[] fridaPaths = {
                ObStr.d(ObStr.F_P_TMP_FRIDA),
                ObStr.d(ObStr.F_P_TMP_GADGET),
                ObStr.d(ObStr.F_P_BIN_FRIDA),
                ObStr.d(ObStr.F_P_XBIN_FRIDA)
            };
            for (String path : fridaPaths) {
                if (new File(path).exists()) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkXposed() {
        // 栈轨迹检测：遍历当前线程栈帧，检查是否有 Xposed / Substrate 类
        // （不使用 Class.forName，避免反射加载外部类）
        try {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                String cn = element.getClassName();
                if (cn.startsWith("de.robv.android.xposed.")
                        || cn.startsWith("com.saurik.substrate.")
                        || cn.startsWith("org.lsposed.")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        String xposedProp = System.getProperty(ObStr.d(ObStr.X_PROP_HOOKLOAD));
        if (xposedProp != null) return true;

        File xposedFile = new File(ObStr.d(ObStr.X_P_TMP_XPOSED));
        if (xposedFile.exists()) return true;

        // 【2026-10-13 口径对齐】补齐 AntiDebug 侧的 LSPosed 目录与 jar 路径
        String[] xposedPaths = {
            ObStr.d(ObStr.X_P_LSPD_CFG),
            ObStr.d(ObStr.X_P_LSPD_MOD),
            ObStr.d(ObStr.X_P_JAR_FW),
            ObStr.d(ObStr.X_P_JAR_TMP)
        };
        for (String path : xposedPaths) {
            if (new File(path).exists()) return true;
        }

        return false;
    }

    private static boolean checkRoot() {
        try {

            // 【2026-10-13 加固】命令与路径改由 ObStr 运行时解码
            final String su = ObStr.d(ObStr.R_SU_CMD);
            final String busybox = ObStr.d(ObStr.R_BUSYBOX);

            String result = executeCommand(ObStr.d(ObStr.R_WHICH_SU));
            if (result != null && result.contains(su)) return true;

            result = executeCommand(ObStr.d(ObStr.R_WHICH_BUSYBOX));
            if (result != null && result.contains(busybox)) return true;

            File magiskFile = new File(ObStr.d(ObStr.M_P_SBIN));
            if (magiskFile.exists()) return true;

            File suFile = new File(ObStr.d(ObStr.S_P_BIN_SU));
            if (suFile.exists()) return true;

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String executeCommand(String command) {
        try {
            // 【2026-10-13 加固】shell 路径由 ObStr 运行时解码
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{ObStr.d(ObStr.R_SH), "-c", command});
            java.io.InputStream inputStream = process.getInputStream();
            byte[] buffer = new byte[4096];
            int n = inputStream.read(buffer);
            inputStream.close();
            process.waitFor();
            return n > 0 ? new String(buffer, 0, n) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getThreatName(int threatType) {
        // 【2026-10-16 解除日志脱敏】返回真实威胁名
        switch (threatType) {
            case THREAT_DEBUGGER: return "调试器";
            case THREAT_FRIDA: return "Frida";
            case THREAT_XPOSED: return "Xposed";
            case THREAT_ROOT: return "Root";
            case THREAT_EMULATOR: return "模拟器";
            case TAMPER_DETECTED: return "APK 篡改";
            case SIGNATURE_MISMATCH: return "签名不匹配";
            case HOOK_DETECTED: return "Hook 攻击";
            default: return "未知威胁(" + threatType + ")";
        }
    }

    public static int getHighestThreat() {
        return sHighestThreat;
    }

    public static boolean isDestroyed() {
        return sDestroyed;
    }

    public static void reset() {
        sHighestThreat = 0;
        sDestroyed = false;
    }
}

    public static class DexProtector {

    private static final String TAG = "DexProtector";
    private static final String DEX_DIR = "dex_protected";
    private static final String DEX_SUFFIX = ".dex";
    private static final String ENCRYPTED_SUFFIX = ".enc";

    private static volatile boolean sInitialized = false;
    private static volatile long sDexLastModified = 0;
    private static volatile String sDexHash = null;

    private DexProtector() {}

    public static boolean init(Context context) {
        if (sInitialized) return true;
        sInitialized = true;

        if (!BuildConfig.IS_DEBUG) {
            try {
                protectDex(context);
                checkDexIntegrity(context);
                startDexMonitor(context);
                LogBridge.d(TAG, "DEX 保护初始化完成");
            } catch (Throwable e) {
                LogBridge.w(TAG, "DEX 保护初始化异常: " + e.getMessage());
            }
        }
        return true;
    }

    private static void protectDex(Context context) {
        try {

            String apkPath = context.getApplicationInfo().sourceDir;
            if (apkPath == null) return;

            File apkFile = new File(apkPath);
            sDexLastModified = apkFile.lastModified();

            sDexHash = computeFileHash(apkFile);
            LogBridge.i(TAG, "DEX hash: " + (sDexHash != null ? sDexHash.substring(0, 16) + "..." : "null"));

        } catch (Exception e) {
            LogBridge.e(TAG, "保护 DEX 失败: " + e.getMessage());
        }
    }

    private static void checkDexIntegrity(Context context) {
        try {
            String apkPath = context.getApplicationInfo().sourceDir;
            if (apkPath == null) return;

            File apkFile = new File(apkPath);

            if (sDexLastModified > 0 && apkFile.lastModified() != sDexLastModified) {
                LogBridge.e(TAG, "⚠️ APK 文件被修改! lastModified 已变化");
                triggerTamperDetected(context, "APK 文件被修改");
                return;
            }

            checkDexFile(context);

        } catch (Exception e) {
            LogBridge.e(TAG, "DEX 完整性检查失败: " + e.getMessage());
        }
    }

    private static void checkDexFile(Context context) {
        try {

            File dataDir = new File(context.getApplicationInfo().dataDir);
            if (dataDir.exists()) {
                checkDirectoryForHooks(dataDir);
            }

            //noinspection AndroidLintObsoleteSdkInt
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                File codeCacheDir = new File(context.getCodeCacheDir().getAbsolutePath());
                if (codeCacheDir.exists()) {
                    checkDirectoryForHooks(codeCacheDir);
                }
            }

        } catch (Exception e) {
            LogBridge.w(TAG, "DEX 文件检查异常: " + e.getMessage());
        }
    }

    private static void checkDirectoryForHooks(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;

            // 【2026-10-13 加固】可疑文件名词表由 ObStr 运行时解码。
            // 注：原表里的 " freedom" 带一个前导空格（笔误），这里按 "freedom" 修正。
            String[] suspiciousNames = {
                ObStr.d(ObStr.X_LOWER), ObStr.d(ObStr.F_FRIDA), ObStr.d(ObStr.X_SUBSTRATE),
                ObStr.d(ObStr.G_GAMEGUARDIAN), ObStr.d(ObStr.G_LUCKY),
                ObStr.d(ObStr.G_FREEDOM), ObStr.d(ObStr.G_CREEPER)
            };

            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                for (String suspicious : suspiciousNames) {
                    if (name.contains(suspicious)) {
                        LogBridge.d(TAG, "检测到可疑文件: " + file.getAbsolutePath());
                        break;
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    private static void startDexMonitor(Context context) {
        if (!BuildConfig.IS_DEBUG) {
            new Thread(() -> {
                int checks = 0;
                while (sInitialized && !BuildConfig.IS_DEBUG) {
                    try {
                        Thread.sleep(5000);
                        checks++;

                        if (checks % 30 == 0) {
                            checkDexIntegrity(context);
                        }

                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable e) {
                        LogBridge.e(TAG, "DEX 监控异常: " + e.getMessage());
                    }
                }
            }, "DexMonitor").start();
        }
    }

    private static void triggerTamperDetected(Context context, String reason) {
        LogBridge.w(TAG, "⚠️ 触发篡改检测: " + reason);

        try {
            SecurityGuard.onThreatDetected(SecurityGuard.TAMPER_DETECTED, reason);
        } catch (Throwable ignored) {}
    }

    public static String computeFileHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            fis.close();
            byte[] hash = digest.digest();
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getDexHash() {
        return sDexHash;
    }

    public static long getDexLastModified() {
        return sDexLastModified;
    }
}

    public static class AntiDebug {

    private static final String TAG = "AntiDebug";
    private static volatile boolean initialized = false;
    private static volatile boolean debugDetected = false;

    public static boolean init(Context context) {
        return init(context, true);
    }

    public static boolean init(Context context, boolean enable) {
        if (!enable) {
            LogBridge.i(TAG, "反调试检测已禁用（调试模式）");
            initialized = true;
            debugDetected = false;
            return false;
        }
        if (initialized) return debugDetected;
        initialized = true;

        TamperReporter.init(context);

        boolean debuggerFound = checkDebugger(context);
        boolean fridaFound = checkFrida();
        boolean xposedFound = checkXposed();
        boolean emulatorFound = checkEmulator(context);

        boolean confirmedDebugger = Debug.isDebuggerConnected();

        debugDetected = fridaFound || xposedFound || confirmedDebugger;

        if (debugDetected) {
            // 【2026-10-16 解除日志脱敏】直写真实特征名
            LogBridge.w(TAG, "⚠️ 检测到可疑环境: " +
                (confirmedDebugger ? "调试器 " : "") +
                (fridaFound ? "Frida " : "") +
                (xposedFound ? "Xposed " : ""));

            if (!BuildConfig.IS_DEBUG) {
                if (confirmedDebugger && !fridaFound && !xposedFound) {

                    TamperReporter.reportSuspicious(
                        TamperReporter.TAMPER_DEBUGGER,
                        "检测到调试器连接，但不确认是否为篡改");
                }
                if (fridaFound) {

                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_FRIDA,
                        "AntiDebug检测到注入工具");
                }
                if (xposedFound) {

                    TamperReporter.reportTamper(
                        TamperReporter.TAMPER_XPOSED,
                        "AntiDebug检测到注入框架");
                }

                // 【2026-10-13 阻断打通 · 关键修复】原实现只上报 TamperReporter（写日志），
                // 从不进入 SecurityGuard 的响应分级链路 —— 这正是"检测到了却不阻断"的直接根因。
                // 此处补齐，使 AntiDebug 的命中同样触发分级动作。
                if (fridaFound) {
                    SecurityGuard.onThreatDetected(
                        SecurityGuard.THREAT_FRIDA, "运行时注入环境异常");
                }
                if (xposedFound) {
                    SecurityGuard.onThreatDetected(
                        SecurityGuard.THREAT_XPOSED, "运行时注入框架异常");
                }
                if (confirmedDebugger && !fridaFound && !xposedFound) {
                    SecurityGuard.onThreatDetected(
                        SecurityGuard.THREAT_DEBUGGER, "检测到调试器连接");
                }
            }
        }

        boolean adbEnabled = checkAdbEnabled(context);
        boolean mockLocation = checkMockLocation(context);

        if (adbEnabled) {
            LogBridge.w(TAG, "⚠️ USB调试已开启（可能用于开发调试，非篡改）");
        }
        if (mockLocation) {
            LogBridge.w(TAG, "⚠️ 检测到模拟位置应用（可能用于测试，非篡改）");
        }
        if (emulatorFound) {
            LogBridge.w(TAG, "⚠️ 检测到模拟器环境（可能是合法测试，非篡改）");
        }

        if (debuggerFound && !confirmedDebugger) {
            LogBridge.w(TAG, "调试器检测结果存疑：[CHK-04] 异常但 Debug.isDebuggerConnected() 未确认");
        }

        return debugDetected;
    }

    private static boolean checkDebugger(Context context) {
        try {

            if (Debug.isDebuggerConnected()) {
                LogBridge.w(TAG, "检测到调试器连接 (Debug.isDebuggerConnected=true)");
                return true;
            }

            ApplicationInfo appInfo = context.getApplicationInfo();
            boolean isDebuggable = (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (isDebuggable && !BuildConfig.IS_DEBUG) {

                LogBridge.w(TAG, "⚠️ 正式版应用但标记为可调试（可能被重打包）");

            }

            try {
                // 【2026-10-13 加固】路径与字段名由 ObStr 运行时解码，日志改代号
                final String statusPath = ObStr.d(ObStr.S_PROC_STATUS);
                final String tracerKey = ObStr.d(ObStr.T_TRACERPID_COLON);
                File file = new File(statusPath);
                if (file.exists()) {
                    FileInputStream fis = new FileInputStream(file);
                    byte[] buffer = new byte[1024];
                    int len = fis.read(buffer);
                    fis.close();
                    String content = new String(buffer, 0, len);

                    for (String line : content.split("\n")) {
                        if (line.startsWith(tracerKey)) {
                            String pidStr = line.substring(tracerKey.length()).trim();
                            int pid = Integer.parseInt(pidStr);

                            if (pid != 0) {


                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {

            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测调试器失败: " + e.getMessage());
        }

        return Debug.isDebuggerConnected();
    }

    private static boolean checkFrida() {
        try {

            // 【2026-10-13 加固】特征串改由 ObStr 运行时解码，dex 内不再出现明文常量
            final String sFrida = ObStr.d(ObStr.F_FRIDA);
            final String sReject = ObStr.d(ObStr.R_REJECT);

            File procDir = new File("/proc");
            String[] pids = procDir.list();
            if (pids != null) {
                for (String pid : pids) {
                    try {
                        File cmdLineFile = new File("/proc/" + pid + "/cmdline");
                        if (cmdLineFile.exists()) {
                            FileInputStream fis = new FileInputStream(cmdLineFile);
                            byte[] buffer = new byte[256];
                            int len = fis.read(buffer);
                            fis.close();
                            String cmdline = new String(buffer, 0, len);
                            // 用 "frida" 一个词即可覆盖 frida-server / frida-qt / frida-agent 等全部变体
                            if (cmdline.contains(sFrida) || cmdline.contains(sReject)) {
                                LogBridge.w(TAG, "检测到可疑注入进程");
                                return true;
                            }
                        }
                    } catch (Exception e) {

                    }
                }
            }

            String[] fridaPorts = {"27042", "27043", "27044", "27045"};
            for (String port : fridaPorts) {
                try {
                    java.net.Socket socket = new java.net.Socket();
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1",
                        Integer.parseInt(port)), 100);
                    socket.close();
                    LogBridge.d(TAG, "检测到可疑监听端口: " + port);
                    return true;
                } catch (Exception e) {

                }
            }

            // 命中路径不再回写日志：原实现会把 /data/local/tmp/... 明文打进 9527 日志
            String[] fridaPaths = {
                ObStr.d(ObStr.F_P_TMP_FRIDA),
                ObStr.d(ObStr.F_P_TMP_GADGET),
                ObStr.d(ObStr.F_P_BIN_FRIDA),
                ObStr.d(ObStr.F_P_XBIN_FRIDA)
            };
            for (String path : fridaPaths) {
                if (new File(path).exists()) {
                    LogBridge.d(TAG, "检测到特征文件命中");
                    return true;
                }
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean checkXposed() {
        try {

            // 【2026-10-13 加固】类名 / 路径 / 环境变量名全部由 ObStr 运行时解码
            try {
                Class.forName(ObStr.d(ObStr.X_CLASS_BRIDGE));
                LogBridge.w(TAG, "检测到注入框架");
                return true;
            } catch (ClassNotFoundException e) {

            }

            String[] xposedPaths = {
                ObStr.d(ObStr.X_P_LSPD_CFG),
                ObStr.d(ObStr.X_P_LSPD_MOD),
                ObStr.d(ObStr.X_P_JAR_FW),
                ObStr.d(ObStr.X_P_JAR_TMP)
            };
            for (String path : xposedPaths) {
                if (new File(path).exists()) {
                    LogBridge.d(TAG, "检测到注入框架特征文件");
                    return true;
                }
            }

            try {
                final String upper = ObStr.d(ObStr.X_UPPER);
                final String lower = ObStr.d(ObStr.X_LOWER);
                for (java.util.Map.Entry<String, String> entry : System.getenv().entrySet()) {
                    if (entry.getKey().contains(upper) ||
                        entry.getValue().contains(lower)) {
                        LogBridge.d(TAG, "检测到注入框架环境变量");
                        return true;
                    }
                }
            } catch (Exception e) {

            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean checkEmulator(Context context) {
        try {

            // 【2026-10-13 加固】模拟器特征路径由 ObStr 运行时解码；命中路径不再回写日志
            String[] emulatorFiles = {
                ObStr.d(ObStr.Q_SOCKET),
                ObStr.d(ObStr.Q_PIPE),
                ObStr.d(ObStr.Q_LIBMALLOC),
                ObStr.d(ObStr.Q_TRACE),
                ObStr.d(ObStr.Q_PROPS)
            };
            for (String path : emulatorFiles) {
                if (new File(path).exists()) {
                    LogBridge.d(TAG, "检测到模拟器特征");
                    return true;
                }
            }

            String fingerprint = android.os.Build.FINGERPRINT;
            if (fingerprint != null) {
                String lower = fingerprint.toLowerCase(Locale.ROOT);
                if (lower.startsWith("generic")
                        || lower.contains("sdk_gphone")
                        || lower.contains("vbox")
                        || lower.contains("emulator")) {
                    LogBridge.w(TAG, "检测到模拟器指纹: " + fingerprint);
                    return true;
                }
            }

            String hardware = android.os.Build.HARDWARE;
            if (hardware != null) {
                String h = hardware.toLowerCase(Locale.ROOT);
                if (h.contains("goldfish") || h.contains("ranchu")) {
                    LogBridge.w(TAG, "检测到模拟器硬件: " + hardware);
                    return true;
                }
            }

        } catch (Exception e) {
            LogBridge.e(TAG, "检测模拟器失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean checkAdbEnabled(Context context) {
        try {
            if (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) == 1) {
                LogBridge.w(TAG, "USB调试已开启");
                return true;
            }
        } catch (Exception e) {

        }
        return false;
    }

    private static boolean checkMockLocation(Context context) {
        try {
            String mockLocationApp = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ALLOW_MOCK_LOCATION);
            if (mockLocationApp != null && mockLocationApp.equals("1")) {
                LogBridge.w(TAG, "检测到模拟位置应用");
                return true;
            }
        } catch (Exception e) {

        }
        return false;
    }

    private static boolean isReleaseBuild() {
        try {

            String packageName = "com.tv.live";
            return !packageName.contains("debug");
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isDebugDetected() {
        return debugDetected;
    }

    public static String calculateMD5(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return null;

            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                md.update(buffer, 0, len);
            }
            fis.close();

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            LogBridge.e(TAG, "计算 MD5 失败: " + e.getMessage());
            return null;
        }
    }
}

    public static class IntegrityCheck {

    private IntegrityCheck() {}

    public static byte[] computeApkHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(new File(apkPath));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            fis.close();
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 【2026-10-21 P0 修复（I1-01）】哈希口径：APK 内全部 classes*.dex（classes.dex、
     * classes2.dex…按数字序）依次喂入同一个 SHA-256。原实现只哈希 classes.dex，
     * multiDex 的 classes2+ 被篡改检测不到；构建侧（app/build.gradle 注入任务）
     * 必须用同一口径计算期望值。
     */
    public static byte[] computeDexHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath)) {
                java.util.List<java.util.zip.ZipEntry> dexEntries = new java.util.ArrayList<>();
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e != null && !e.isDirectory()
                            && e.getName().matches("classes(\\d+)?\\.dex")) {
                        dexEntries.add(e);
                    }
                }
                if (dexEntries.isEmpty()) return null;
                dexEntries.sort((a, b) -> {
                    int ia = dexIndexOf(a.getName());
                    int ib = dexIndexOf(b.getName());
                    if (ia != ib) return Integer.compare(ia, ib);
                    return a.getName().compareTo(b.getName());
                });
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[64 * 1024];
                for (java.util.zip.ZipEntry e : dexEntries) {
                    try (java.io.InputStream is = zf.getInputStream(e)) {
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            md.update(buf, 0, n);
                        }
                    }
                }
                return md.digest();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** classes.dex → 0；classes2.dex → 2；无法解析时排到最后 */
    private static int dexIndexOf(String name) {
        try {
            String num = name.substring("classes".length(), name.length() - ".dex".length());
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}

    public static class StringObfuscator {

    private static final String TAG = "StrObfuscator";

    private static final Map<String, String> stringCache = new HashMap<>();

    private static final int XOR_KEY = 0x3C;
    private static final int XOR_KEY_2 = 0xA5;

    public static String encodeString(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            c ^= XOR_KEY;
            c ^= XOR_KEY_2;
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }

    public static String decodeString(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;

        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }

        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);

                c ^= XOR_KEY_2;
                c ^= XOR_KEY;
                sb.append(c);
            }

            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            LogBridge.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }

    public static String simpleEncode(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c >= 'a' && c <= 'z') {
                c = (char) ((c - 'a' + 13) % 26 + 'a');
            } else if (c >= 'A' && c <= 'Z') {
                c = (char) ((c - 'A' + 13) % 26 + 'A');
            } else if (c >= '0' && c <= '9') {
                c = (char) ((c - '0' + 5) % 10 + '0');
            }
            sb.append(c);
        }
        return Base64.encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
    }

    public static String simpleDecode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;

        if (stringCache.containsKey(encoded)) {
            return stringCache.get(encoded);
        }

        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            String str = new String(decoded, StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);

                if (c >= 'a' && c <= 'z') {
                    c = (char) ((c - 'a' - 13 + 26) % 26 + 'a');
                } else if (c >= 'A' && c <= 'Z') {
                    c = (char) ((c - 'A' - 13 + 26) % 26 + 'A');
                } else if (c >= '0' && c <= '9') {
                    c = (char) ((c - '0' - 5 + 10) % 10 + '0');
                }
                sb.append(c);
            }

            String result = sb.toString();
            stringCache.put(encoded, result);
            return result;
        } catch (Exception e) {
            LogBridge.e(TAG, "解码字符串失败: " + e.getMessage());
            return null;
        }
    }

    public static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }

    public static void clearCache() {
        stringCache.clear();
    }

    public static String encodeInt(int value) {
        return encodeString(String.valueOf(value));
    }

    public static int decodeInt(String encoded) {
        String decoded = decodeString(encoded);
        if (decoded == null) return -1;
        try {
            return Integer.parseInt(decoded);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

    public static class TamperReporter {

    private static final String TAG = "TamperReporter";

    public static final int TAMPER_SIGNATURE = 1;
    public static final int TAMPER_PACKAGE_NAME = 2;
    public static final int TAMPER_DEX_INTEGRITY = 3;
    public static final int TAMPER_DEBUGGER = 4;
    public static final int TAMPER_FRIDA = 5;
    public static final int TAMPER_XPOSED = 6;
    public static final int TAMPER_HOOK = 7;
    public static final int TAMPER_ROOT = 8;
    public static final int TAMPER_EMULATOR = 9;
    public static final int TAMPER_MEMORY = 10;

    private static volatile boolean sReported = false;
    private static volatile boolean sCrashing = false;
    private static Context sAppContext;

    private TamperReporter() {}

    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
        if (!BuildConfig.IS_DEBUG) {
            LogBridge.d(TAG, "反编译检测器已启用");
        } else {
            LogBridge.d(TAG, "反编译检测器：调试模式，仅记录日志");
        }
    }

    public static void reportTamper(int tamperType, String detail) {
        if (sReported) return;
        sReported = true;

        String typeName = getTypeName(tamperType);
        // 【2026-10-28 单通道】同一篡改事件原写 3 处：缓冲摘要 + 缓冲结构化 + tamper_logs/*.log 文件。
        //   现只保留一条结构化缓冲记录（含 ⚠️ 前缀，便于按关键字检索）。
        reportToLocalMonitor(tamperType, typeName, detail);
    }

    public static void reportSuspicious(int tamperType, String detail) {
        String typeName = getTypeName(tamperType);
        LogCollector.getInstance().warn(TAG,
            "可疑环境: " + typeName + " | " + detail);
    }

    private static void reportToLocalMonitor(int tamperType, String typeName, String detail) {
        try {

            StringBuilder sb = new StringBuilder();
            sb.append("[TAMPER] ⚠️ 反编译检测: ").append(typeName).append("\n");
            sb.append("Detail: ").append(detail).append("\n");
            sb.append("Package: ").append(BuildConfig.APPLICATION_ID).append("\n");
            sb.append("Version: ").append(BuildConfig.VERSION_NAME).append("\n");
            sb.append("Device: ").append(Build.MODEL).append("\n");
            sb.append("Time: ").append(System.currentTimeMillis());

            LogCollector.getInstance().error(TAG, sb.toString());

        } catch (Exception e) {
            LogBridge.e(TAG, "本地上报失败: " + e.getMessage());
        }
    }

    public static void triggerCrash(int tamperType, String detail) {
        if (sCrashing) return;
        sCrashing = true;

        reportTamper(tamperType, detail);

        if (BuildConfig.IS_DEBUG) {

            LogBridge.w(TAG, "调试模式：跳过崩溃触发");
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(500);

                throw new TamperFatalException(
                    "【致命错误】检测到应用被篡改，已终止运行: " + detail);
            } catch (InterruptedException e) {

                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        }, "TamperCrash").start();
    }

    public static void emergencyReport(int tamperType, String detail) {
        if (!BuildConfig.IS_DEBUG) {

            reportTamper(tamperType, detail);
            Process.killProcess(Process.myPid());
            System.exit(0);
        }
    }

    public static String getTypeName(int tamperType) {
        switch (tamperType) {
            case TAMPER_SIGNATURE: return "签名不匹配";
            case TAMPER_PACKAGE_NAME: return "包名不匹配";
            case TAMPER_DEX_INTEGRITY: return "DEX完整性校验失败";
            case TAMPER_DEBUGGER: return "调试器检测";
            // 【2026-10-16 解除日志脱敏】返回真实类型名
            case TAMPER_FRIDA: return "Frida检测";
            case TAMPER_XPOSED: return "Xposed检测";
            case TAMPER_HOOK: return "Hook攻击检测";
            case TAMPER_ROOT: return "Root环境";
            case TAMPER_EMULATOR: return "模拟器环境";
            case TAMPER_MEMORY: return "内存篡改检测";
            default: return "未知篡改类型(" + tamperType + ")";
        }
    }

    public static boolean isReported() {
        return sReported;
    }

    public static boolean isCrashing() {
        return sCrashing;
    }

    private static class TamperException extends Exception {
        public TamperException(String message) {
            super(message);

            StackTraceElement[] stack = new StackTraceElement[] {
                new StackTraceElement(
                    "com.tv.live.security.TamperReporter",
                    "reportTamper",
                    "TamperReporter.java",
                    100),
                new StackTraceElement(
                    "com.tv.live.security.SecurityCheck",
                    "verifyOnStart",
                    "SecurityCheck.java",
                    50),
                new StackTraceElement(
                    "com.tv.live.MyApplication",
                    "onCreate",
                    "MyApplication.java",
                    100),
            };
            setStackTrace(stack);
        }
    }

    private static class TamperFatalException extends RuntimeException {
        public TamperFatalException(String message) {
            super(message);
        }
    }
}

    /**
     * 第三方 SDK native 库加载的统一安全入口.
     *
     * <p>由 buildSrc ASM 插桩（OptionalNativeLoadClassVisitorFactory，scope=ALL）自动把
     * 所有第三方类（非 com.tv.live.*）里的 {@code System.loadLibrary(name)} 重定向到本方法，
     * App 自身代码的调用保持原样以保留诊断语义（见 HuyaSDKParser.checkMarsNativeLibraries）。</p>
     *
     * <p>设计安全目标：APK 通过 packagingOptions 全局排除了 libc++_shared.so /
     * libstlport_shared.so（包内 libmarsstn 为 hook stub、libtvlive_security 为自有库，
     * 均不 DT_NEEDED 任何 C++ 运行时库）。第三方 SDK（历史上 MMKV/Mars/Xlog）的静态块
     * 常无守卫地显式 loadLibrary 这些运行时库，在系统不自带它们的设备上会抛
     * UnsatisfiedLinkError → ExceptionInInitializerError 导致整个类初始化失败。</p>
     *
     * <p>策略：
     * <ul>
     *   <li>白名单内的"可选 C++ 运行时库"：尝试加载，失败静默吞掉——显式加载它们本就是
     *       SDK 为满足后续 .so 的 DT_NEEDED 而预热；包内无库 DT_NEEDED 它们，缺失零影响；
     *       若未来引入真实 C++ 库真正依赖它们，该库自身 dlopen 时仍会明确报错（而非类初始化
     *             阶段以迷惑性的 ExceptionInInitializerError 崩溃），届时应把运行时库加回 APK。</li>
     *   <li>其他库名：原样调用 System.loadLibrary，完整保留原始错误抛出语义。</li>
     * </ul></p>
     */
    public static class NativeLibraryCompat {

    /**
     * APK 中确定不提供、且包内现存 SO 均不依赖的 C++ 运行时库。
     * 必须与 app/build.gradle packagingOptions.jniLibs.excludes 保持一致。
     */
    private static final Set<String> OPTIONAL_RUNTIME_LIBS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("c++_shared", "stlport_shared")));

    private NativeLibraryCompat() {
    }

    /**
     * ASM 重定向入口。签名与 {@link System#loadLibrary(String)} 完全一致，
     * 插桩时只改 invoke 指令的 owner，不动寄存器与参数。
     */
    public static void loadLibrarySafe(String name) {
        if (name != null && OPTIONAL_RUNTIME_LIBS.contains(name)) {
            try {
                System.loadLibrary(name);
            } catch (Throwable ignored) {
                // 运行时库缺失：包内无任何 SO DT_NEEDED 它，属于预期情况，静默处理
            }
            return;
        }
        System.loadLibrary(name);
    }
}

    public static class SecurityCheck {

    private static final String TAG = "SecChk";
    private static final String EXPECTED_PKG = "com.tv.live";

    // 【2026-10-22 I1-07】期望签名不再以 Base64 明文留在 dex 里（原来 strings 一下就能拿到，
    //   拿到后重签自己的证书也能过校验）。现在只存在于 libtvlive_security.so，
    //   Java 侧仅通过 nativeSignatureOk() 取判定结果。

    // 【2026-10-21 P0 修复（I1-01）】DEX 期望哈希改由构建流水线注入 assets/dexhash.png
    //   （assembleRelease 后处理：计算 APK 内 classes*.dex 的 SHA-256 → Base64，
    //   原地改写资源条目 → apksigner 重签名）。原硬编码常量 EXPECTED_DEX_B64 长期为
    //   占位符 "REPLACE_WITH_DEX_SHA256_BASE64"，verifyDexIntegrity 命中即 return true，
    //   校验完全空转；且该值若写死在 dex 里会随 dex 变化而失效（先有蛋还是先有鸡），
    //   放 assets 可由打包后处理注入，篡改则被签名校验（verifySignature）覆盖。
    //   ⚠️ 扩展名用 .png 而非 .txt：本工程 AGP 版本的 packagingOptions.resources 无
    //   noCompress 入口，而 .png 默认 STORED 进包，注入任务才能等长原地改写（压缩流
    //   改写会改变长度、破坏 zip 结构）。文件内容是 44 字节 ASCII，不是真图片。
    private static final String DEX_HASH_ASSET = "dexhash.png";

    private static volatile String sExpectedDexB64;

    /** 【2026-10-27】verifyOnStart 幂等化：首个调用者执行，其余等待并复用结果。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sVerifyStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.CountDownLatch sVerifyLatch =
            new java.util.concurrent.CountDownLatch(1);
    private static volatile boolean sVerifyResult = true;

    private SecurityCheck() {}

    public static boolean verifyOnStart(Context ctx) {
        // 【2026-10-27 去重复执行】原实现被两处各调一次：
        //   MyApplication.initSecurity()（"init-security" 后台线程）
        //   MainActivity.onCreate → AppExecutors.io(...)
        // 两者同时在跑 → 签名校验 + 包名校验 + classes*.dex 全量 SHA-256 各做两遍，
        //   dex 哈希是整包读一遍，重复执行白耗启动时间，日志里每条校验结果也固定出现两行。
        // 现改为幂等：首个调用者执行，其余调用者等它结束直接复用结果。
        if (!sVerifyStarted.compareAndSet(false, true)) {
            try {
                sVerifyLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return sVerifyResult;
        }
        try {
            sVerifyResult = doVerifyOnStart(ctx);
            return sVerifyResult;
        } finally {
            sVerifyLatch.countDown();
        }
    }

    /** 签名/包名/dex 校验的实际执行体，只允许经 {@link #verifyOnStart} 进入（保证幂等）。 */
    private static boolean doVerifyOnStart(Context ctx) {
        if (BuildConfig.IS_DEBUG) {
            LogBridge.i(TAG, "🔓 调试版：跳过签名校验");
            return true;
        }

        LogBridge.i(TAG, "🔒 正式版：启用签名校验");

        try {
            TamperReporter.init(ctx);
        } catch (Throwable t) {
            LogBridge.w(TAG, "TamperReporter 初始化失败: " + t.getMessage());
        }

        boolean allPassed = true;

        if (!verifySignature(ctx)) {
            LogBridge.e(TAG, "⚠️ 签名校验未通过，将上报但不阻断启动");
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_SIGNATURE,
                    "签名校验失败"
                );
            } catch (Throwable t) {
                LogBridge.w(TAG, "篡改上报失败: " + t.getMessage());
            }
            allPassed = false;
        }

        String pkgName = ctx.getPackageName();
        if (!EXPECTED_PKG.equals(pkgName)) {
            LogBridge.e(TAG, "❌ 包名不匹配! expected=" + EXPECTED_PKG + " current=" + pkgName);
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_PACKAGE_NAME,
                    "包名校验失败, expected=" + EXPECTED_PKG + " current=" + pkgName
                );
            } catch (Throwable t) {

            }
            allPassed = false;
        } else {
            LogBridge.d(TAG, "✅ 包名校验通过");
        }

        if (!verifyDexIntegrity(ctx)) {
            LogBridge.e(TAG, "⚠️ DEX 完整性校验未通过");
            try {
                TamperReporter.reportTamper(
                    TamperReporter.TAMPER_DEX_INTEGRITY,
                    "DEX完整性校验失败"
                );
            } catch (Throwable t) {

            }
            // 【2026-10-21 P0 修复（I1-01）】与签名校验口径对齐：dex 不匹配即阻断。
            //   误报风险评估：classes.dex 位于已签名 APK 内，安装后不可变；
            //   能被改写的包必然签名也已失效（先被 verifySignature 拦下）。
            //   期望值未注入（占位符/资源缺失）时 verifyDexIntegrity 返回 true，不会误伤。
            toastAndExit(ctx, "应用完整性校验失败，APK 被修改");
            allPassed = false;
        }

        if (!allPassed) {
            LogBridge.w(TAG, "⚠️ 部分安全检查未通过，但应用将继续运行（降级模式）");
        }
        return allPassed;
    }

    private static boolean verifySignature(Context appCtx) {
        try {
            // 【2026-10-22 修复·release 234 启动崩溃】这里原来直接调 nativeBindSignature /
            //   nativeSignatureOk，假定 SO 已加载——而 SecurityCore.init() 跑在
            //   MyApplication 的 init-urlconfig-early 后台线程里，与本方法存在**竞态**。
            //   2.0.232 碰巧赢了这个竞态；2.0.234 输了 → 启动即
            //   UnsatisfiedLinkError: nativeSignatureOk → FATAL（AppIO-2）。
            //   这里先同步确保加载（幂等，已加载时立即返回），仍失败则按「降级模式」放行，
            //   绝不让一个 JNI 调用把整个进程打死。
            if (!sLoaded) {
                try { init(); } catch (Throwable ignored) {}
            }
            if (!sLoaded) {
                LogBridge.w(TAG, "⚠️ 安全库未加载，签名校验降级放行（不影响功能）");
                return true;
            }
            PackageManager pm = appCtx.getPackageManager();
            PackageInfo pi;
            byte[] certBytes;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] sigs = (pi.signingInfo != null && pi.signingInfo.hasMultipleSigners())
                        ? pi.signingInfo.getApkContentsSigners()
                        : (pi.signingInfo != null ? pi.signingInfo.getSigningCertificateHistory() : null);
                if (sigs == null || sigs.length == 0) {
                    LogBridge.w(TAG, "未找到签名");
                    return false;
                }
                certBytes = sigs[0].toByteArray();
            } else {
                pi = pm.getPackageInfo(appCtx.getPackageName(), PackageManager.GET_SIGNATURES);
                Signature[] sigs = pi.signatures;
                if (sigs == null || sigs.length == 0) {
                    LogBridge.w(TAG, "未找到签名");
                    return false;
                }
                certBytes = sigs[0].toByteArray();
            }

            byte[] shaBytes = MessageDigest.getInstance("SHA-256").digest(certBytes);

            // 【2026-10-22 I1-07】顺手补一次绑定（幂等）：绑定成功后 AES 工作密钥与
            //   secstore 常量都由「签名哈希 + 内置片段」派生，改包会解出乱码。
            try {
                if (shaBytes.length == 32) {
                    nativeBindSignature(shaBytes);
                    sSigBound = true;
                }
            } catch (Throwable ignored) {}

            // 【2026-10-22 I1-07】只打印前 12 个字符：完整哈希会把「期望值」原样写进 9527 日志
            //   （实测 release 日志里出现过完整 Base64），等于把比对基准重新公开，抵消了
            //   "期望值只存在于 .so" 的改造。前缀足够区分包是否被换签名。
            String sigB64 = Base64.encodeToString(shaBytes, Base64.NO_WRAP);
            LogBridge.d(TAG, "当前签名 SHA256=" + sigB64.substring(0, Math.min(12, sigB64.length())) + "…");

            boolean ok;
            try {
                ok = nativeSignatureOk();
            } catch (Throwable t) {
                LogBridge.w(TAG, "签名校验 JNI 不可用，降级放行: " + t.getMessage());
                return true;
            }
            if (!ok) {
                LogBridge.e(TAG, "❌ 签名校验失败：与 .so 内置期望不一致（加密常量已失效）");
                toastAndExit(appCtx, "签名校验失败，APK 被修改");
                return false;
            }
            LogBridge.d(TAG, "✅ 签名校验通过");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "verify error", e);
            return false;
        }
    }

    /**
     * 【2026-10-21 P0 修复（I1-01）】期望 DEX 哈希从 assets/dexhash.png 读取（构建流水线注入）。
     * Base64(SHA-256) 恒为 44 字符且以 '=' 结尾；占位符/缺失/格式异常一律视为「未配置」，
     * 返回 true 不阻断（本地包、未走注入流水线的包不受影响），但打醒目告警。
     */
    private static String expectedDexB64(Context ctx) {
        if (sExpectedDexB64 != null) return sExpectedDexB64;
        String v = null;
        try (java.io.InputStream is = ctx.getAssets().open(DEX_HASH_ASSET);
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(is, "UTF-8"))) {
            String line = r.readLine();
            if (line != null) v = line.trim();
        } catch (Exception ignored) {
            // 资源不存在：未配置
        }
        sExpectedDexB64 = v;
        return v;
    }

    private static boolean verifyDexIntegrity(Context appCtx) {

        String expected = expectedDexB64(appCtx);
        if (expected == null || expected.isEmpty() || !expected.endsWith("=")) {
            // 【2026-10-21 P0 修复（I1-01）】原逻辑：常量占位符命中即 return true，且
            //   校验体内还嵌了一层重复的占位符判断（死分支）。现改为读注入的期望值，
            //   未配置时不阻断但告警（i 级，release 9527 通道可见）。
            LogBridge.w(TAG, "⚠️ DEX 完整性校验未配置（assets/" + DEX_HASH_ASSET
                    + " 未注入真实值），当前包不做 dex 校验");
            return true;
        }
        try {
            byte[] hash = IntegrityCheck.computeDexHash(appCtx);
            if (hash == null) {
                // APK 结构异常取不到 dex：不阻断（可用性优先），但留下痕迹
                LogBridge.w(TAG, "dex hash 计算失败（APK 内无 classes*.dex?），跳过校验");
                return true;
            }
            String currentB64 = Base64.encodeToString(hash, Base64.NO_WRAP);
            if (!expected.equals(currentB64)) {
                LogBridge.e(TAG, "❌ dex hash 不匹配！expected=" + expected + " current=" + currentB64);
                return false;
            }
            LogBridge.d(TAG, "✅ dex 完整性校验通过（classes*.dex SHA-256 匹配）");
            return true;
        } catch (Exception e) {
            LogBridge.e(TAG, "verify dex error", e);
            return true;
        }
    }

    private static void toastAndExit(Context ctx, String msg) {
        LogBridge.e(TAG, msg);
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                }, 1500);
    }
}

@SuppressLint("StaticFieldLeak")
    public static class SecurityGuardManager {

    private static final String TAG = "SecurityGuard";
    private static final String PREFS_NAME = "security_guard";
    private static final String KEY_APP_SIGNATURE = "app_signature";
    private static final String KEY_LAST_CHECK = "last_check_time";

    private static volatile SecurityGuardManager sInstance;
    private Context mContext;
    private boolean mInitialized = false;

    private final SecurityReport mLastReport = new SecurityReport();

    private String mExpectedSignatureHash;

    public static class SecurityReport {
        public boolean debugDetected = false;
        public boolean fridaDetected = false;
        public boolean xposedDetected = false;
        public boolean rootDetected = false;
        public boolean hookDetected = false;
        public boolean integrityOk = true;
        public boolean proxyDetected = false;
        public boolean vpnDetected = false;
        public boolean emulatorDetected = false;
        public boolean usbDebuggingEnabled = false;
        public long checkTime = 0;
        public List<String> threats = new ArrayList<>();

        public boolean hasAnyThreat() {
            return !threats.isEmpty();
        }

        public String getSummary() {
            if (!hasAnyThreat()) {
                return "安全检测通过";
            }
            StringBuilder sb = new StringBuilder("检测到安全威胁:\n");
            for (String threat : threats) {
                sb.append("⚠️ ").append(threat).append("\n");
            }
            return sb.toString();
        }

        public void update(SecurityReport source) {
            if (source == null) return;
            this.debugDetected = source.debugDetected;
            this.fridaDetected = source.fridaDetected;
            this.xposedDetected = source.xposedDetected;
            this.rootDetected = source.rootDetected;
            this.hookDetected = source.hookDetected;
            this.integrityOk = source.integrityOk;
            this.proxyDetected = source.proxyDetected;
            this.vpnDetected = source.vpnDetected;
            this.emulatorDetected = source.emulatorDetected;
            this.usbDebuggingEnabled = source.usbDebuggingEnabled;
            this.checkTime = source.checkTime;
            this.threats = new ArrayList<>(source.threats);
        }
    }

    private SecurityGuardManager() {
    }

    public static SecurityGuardManager getInstance() {
        if (sInstance == null) {
            synchronized (SecurityGuardManager.class) {
                if (sInstance == null) {
                    sInstance = new SecurityGuardManager();
                }
            }
        }
        return sInstance;
    }

    public void init(Context context) {
        mContext = context.getApplicationContext();
        mExpectedSignatureHash = getAppSignatureHash();

        if (mExpectedSignatureHash != null) {
            mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP_SIGNATURE, mExpectedSignatureHash)
                .apply();
        }

        mInitialized = true;
        LogBridge.d(TAG, "安全管理器初始化完成，签名哈希: " +
            (mExpectedSignatureHash != null ? mExpectedSignatureHash.substring(0, 16) + "..." : "unknown"));
    }

    public SecurityReport performFullSecurityCheck() {
        if (!mInitialized) {
            init(mContext);
        }

        SecurityReport report = new SecurityReport();
        report.checkTime = System.currentTimeMillis();

        checkDebugger(report);

        checkHookingFrameworks(report);

        checkRootAccess(report);

        checkApkIntegrity(report);

        checkRuntimeEnvironment(report);

        if (report.hasAnyThreat()) {
            // 【2026-10-27 去日志双写】原先这里把 getSummary() 全文（= ⚠️ 逐项威胁列表）再打一遍，
            // 而每一项威胁在各自的 check 里已经单独打过 ⚠️ 行了 → 同一批内容在缓冲里出现两遍；
            // 再叠加 MyApplication 层的「安全检测发现风险: <同一份 summary>」，一次命中变三行。
            // 现在：逐项明细由各 check 负责，这里只给一行结论 + 计数。
            LogBridge.w(TAG, "安全检测完成：发现 " + report.threats.size()
                    + " 项风险（明细见上方 ⚠️ 行）");
        } else {
            LogBridge.i(TAG, "安全检测完成：未发现风险");
        }

        mLastReport.update(report);
        return report;
    }

    private void checkDebugger(SecurityReport report) {

        try {
            if (Debug.isDebuggerConnected()) {
                report.debugDetected = true;
                report.threats.add("检测到调试器");
                LogBridge.w(TAG, "⚠️ 调试器已附加");
                return;
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "调试检测异常: " + e.getMessage());
        }

        try {
            // 【2026-10-13 加固】字段名由 ObStr 运行时解码，日志改代号
            String tracerPid = readProcStatus(ObStr.d(ObStr.T_TRACERPID_COLON));
            if (tracerPid != null) {
                int pid = Integer.parseInt(tracerPid.trim());
                if (pid > 0) {
                    report.debugDetected = true;
                    report.threats.add("检测到调试器([CHK-04]=" + pid + ")");
                    LogBridge.w(TAG, "⚠️ [CHK-04]=" + pid);
                }
            }
        } catch (Exception e) {

        }

        try {
            ApplicationInfo appInfo = mContext.getApplicationInfo();
            boolean isDebuggable = (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (isDebuggable && !Build.TAGS.equals("release-keys")) {

                LogBridge.d(TAG, "应用为可调试版本 (debuggable=true)");
            }
        } catch (Exception e) {

        }
    }

    private void checkHookingFrameworks(SecurityReport report) {

        // 【2026-10-16 解除日志脱敏】直写真实名称
        if (isFridaPortOpen()) {
            report.fridaDetected = true;
            report.hookDetected = true;
            report.threats.add("检测到 Frida 框架");
            LogBridge.w(TAG, "⚠️ Frida 端口已开启");
        }

        if (isXposedFrameworkDetected()) {
            report.xposedDetected = true;
            report.hookDetected = true;
            report.threats.add("检测到 Xposed 框架");
            LogBridge.w(TAG, "⚠️ Xposed 框架已加载");
        }

        if (isMagiskDetected()) {
            report.hookDetected = true;
            report.threats.add("检测到 [CHK-03] 模块");
            LogBridge.w(TAG, "⚠️ [CHK-03] 已安装");
        }

        if (detectSuspiciousLibraries()) {
            report.hookDetected = true;
            report.threats.add("检测到可疑的本地库注入");
            LogBridge.w(TAG, "⚠️ 检测到可疑库");
        }
    }

    private boolean isFridaPortOpen() {
        int[] fridaPorts = {27042, 27043, 37177};
        for (int port : fridaPorts) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
                socket.close();
                return true;
            } catch (Exception e) {

            }
        }
        return false;
    }

    private boolean isXposedFrameworkDetected() {
        try {

            // 【2026-10-13 加固】类名与路径全部由 ObStr 运行时解码
            Class.forName(ObStr.d(ObStr.X_CLASS_BRIDGE), false, null);
            return true;
        } catch (ClassNotFoundException e) {

        }

        String[] suspiciousFiles = {
            ObStr.d(ObStr.X_P_LIB32),
            ObStr.d(ObStr.X_P_LIB64),
            ObStr.d(ObStr.X_P_JAR_FW),
            ObStr.d(ObStr.X_P_TMP_LOWER),
            ObStr.d(ObStr.F_P_TMP_FRIDA)
        };

        for (String path : suspiciousFiles) {
            if (new File(path).exists()) {
                return true;
            }
        }

        try {
            java.util.Enumeration<java.net.URL> libraries = ClassLoader.getSystemClassLoader()
                .getSystemResources(ObStr.d(ObStr.X_META_INIT));
            if (libraries.hasMoreElements()) {
                return true;
            }
        } catch (Exception e) {

        }

        return false;
    }

    private boolean isMagiskDetected() {
        // 【2026-10-13 加固】路径与环境变量名由 ObStr 运行时解码
        String[] magiskPaths = {
            ObStr.d(ObStr.M_P_SBIN),
            ObStr.d(ObStr.M_P_BIN_MAGISK),
            ObStr.d(ObStr.M_P_ADB_MAGISK),
            ObStr.d(ObStr.M_P_MODULES)
        };

        for (String path : magiskPaths) {
            if (new File(path).exists()) {
                return true;
            }
        }

        try {
            String magiskPath = System.getenv(ObStr.d(ObStr.M_ENV_MAGISK));
            if (magiskPath != null && !magiskPath.isEmpty()) {
                return true;
            }
        } catch (Exception e) {

        }

        return false;
    }

    private boolean detectSuspiciousLibraries() {
        try {

            // 【2026-10-13 加固】maps 路径与可疑库名词表由 ObStr 运行时解码
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(ObStr.d(ObStr.S_PROC_MAPS)));
            String line;
            Set<String> suspiciousNames = new HashSet<>();
            suspiciousNames.add(ObStr.d(ObStr.F_FRIDA));
            suspiciousNames.add(ObStr.d(ObStr.X_LOWER));
            suspiciousNames.add(ObStr.d(ObStr.X_SUBSTRATE));
            suspiciousNames.add(ObStr.d(ObStr.G_GAMEGUARDIAN));
            suspiciousNames.add(ObStr.d(ObStr.G_LUCKY));
            suspiciousNames.add(ObStr.d(ObStr.M_MAGISK));

            while ((line = reader.readLine()) != null) {
                for (String name : suspiciousNames) {
                    if (line.toLowerCase(Locale.ROOT).contains(name)) {
                        reader.close();
                        return true;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {

        }
        return false;
    }

    private void checkRootAccess(SecurityReport report) {

        // 【2026-10-13 加固】su 路径由 ObStr 运行时解码，日志改代号
        String[] suPaths = {
            ObStr.d(ObStr.S_P_BIN_SU),
            ObStr.d(ObStr.S_P_XBIN_SU),
            ObStr.d(ObStr.S_P_EXT_SU),
            ObStr.d(ObStr.S_P_RECURSE),
            ObStr.d(ObStr.S_P_INITD),
            ObStr.d(ObStr.S_P_SU_BIN),
            ObStr.d(ObStr.M_P_CORE_SU)
        };

        boolean suFound = false;
        for (String path : suPaths) {
            if (new File(path).exists()) {
                suFound = true;
                break;
            }
        }

        if (suFound || isTestKeyBuild() || hasWriteAccess("/system")) {
            report.rootDetected = true;
            report.threats.add("检测到 [CHK-05] 权限");
            // 【2026-10-27 去日志双写】文案与 threats 条目保持一致（原来这里写「环境」、
            // 汇总里写「权限」，同一次检测出现两种叫法，排查时误以为是两个问题）。
            LogBridge.w(TAG, "⚠️ 检测到 [CHK-05] 权限（root / 测试密钥 / 系统可写）");
        }
    }

    private boolean isTestKeyBuild() {
        return Build.TAGS != null && Build.TAGS.contains("test-keys");
    }

    private boolean hasWriteAccess(String path) {
        try {
            File testFile = new File(path, ".security_test");
            return testFile.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    private void checkApkIntegrity(SecurityReport report) {

        String currentSignature = getAppSignatureHash();
        if (currentSignature == null) {
            report.integrityOk = false;
            report.threats.add("无法获取应用签名");
            LogBridge.e(TAG, "❌ 无法获取应用签名");
            return;
        }

        String storedSignature = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_SIGNATURE, null);

        if (storedSignature != null && !currentSignature.equals(storedSignature)) {
            report.integrityOk = false;
            report.threats.add("应用签名已变更（可能被二次打包）");
            LogBridge.e(TAG, "❌ 签名不匹配! 当前: " + currentSignature.substring(0, 16) +
                "... 存储: " + storedSignature.substring(0, 16) + "...");
        }

        if (isTestKeyBuild()) {
            report.integrityOk = false;
            report.threats.add("使用测试密钥构建");
            LogBridge.w(TAG, "⚠️ 应用使用测试密钥");
        }
    }

    private String getAppSignatureHash() {
        try {
            PackageInfo packageInfo = mContext.getPackageManager().getPackageInfo(
                mContext.getPackageName(), PackageManager.GET_SIGNATURES);

            Signature[] signatures = packageInfo.signatures;
            if (signatures != null && signatures.length > 0) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(signatures[0].toByteArray());
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "获取签名失败: " + e.getMessage());
        }
        return null;
    }

    private void checkRuntimeEnvironment(SecurityReport report) {

        if (isProxyConfigured()) {
            report.proxyDetected = true;
            report.threats.add("检测到代理服务器");
            LogBridge.w(TAG, "⚠️ 检测到代理配置");
        }

        if (isVpnActive()) {
            report.vpnDetected = true;
            report.threats.add("检测到 VPN 连接");
            LogBridge.w(TAG, "⚠️ 检测到 VPN");
        }

        if (isEmulator()) {
            report.emulatorDetected = true;
            report.threats.add("运行在模拟器环境");
            LogBridge.d(TAG, "ℹ️ 应用运行在模拟器上");
        }

        if (isUsbDebuggingEnabled()) {
            report.usbDebuggingEnabled = true;
            report.threats.add("USB 调试已开启");
            LogBridge.w(TAG, "⚠️ USB 调试已开启");
        }
    }

    private boolean isProxyConfigured() {
        try {

            String proxyHost = Settings.Global.getString(
                mContext.getContentResolver(), Settings.Global.HTTP_PROXY);
            if (proxyHost != null && !proxyHost.isEmpty()) {
                return true;
            }

        } catch (Exception e) {

        }
        return false;
    }

    private boolean isVpnActive() {
        try {

            java.net.NetworkInterface.getNetworkInterfaces();
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.getName().startsWith("tun") ||
                    iface.getName().startsWith("ppp") ||
                    iface.getName().startsWith("vpn")) {
                    return true;
                }
            }
        } catch (Exception e) {

        }
        return false;
    }

    private boolean isEmulator() {

        // 【2026-10-13 加固】模拟器特征路径由 ObStr 运行时解码
        String[] emulatorFiles = {
            ObStr.d(ObStr.Q_SOCKET),
            ObStr.d(ObStr.Q_PIPE),
            ObStr.d(ObStr.Q_LIBMALLOC),
            ObStr.d(ObStr.Q_TRACE),
            ObStr.d(ObStr.Q_PROPS)
        };

        for (String path : emulatorFiles) {
            if (new File(path).exists()) {
                return true;
            }
        }

        try {
            String fingerprint = Build.FINGERPRINT;
            if (fingerprint != null) {
                String lowerFingerprint = fingerprint.toLowerCase(Locale.ROOT);
                if (lowerFingerprint.startsWith("generic")
                        || lowerFingerprint.contains("sdk_gphone")
                        || lowerFingerprint.contains("emulator")) {
                    return true;
                }
            }

            String hardware = Build.HARDWARE;
            if (hardware != null) {
                String h = hardware.toLowerCase(Locale.ROOT);
                if (h.contains("goldfish") || h.contains("ranchu")) {
                    return true;
                }
            }

            String model = Build.MODEL;
            if (model != null && model.toLowerCase(Locale.ROOT).contains("emulator")) {
                return true;
            }
        } catch (Exception e) {

        }

        return false;
    }

    private boolean isUsbDebuggingEnabled() {
        try {
            int adbEnabled = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0);
            return adbEnabled == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private String readProcStatus(String key) {
        try {
            int pid = android.os.Process.myPid();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    reader.close();
                    return line.substring(key.length()).trim();
                }
            }
            reader.close();
        } catch (Exception e) {

        }
        return null;
    }

    public SecurityReport getLastReport() {
        return mLastReport;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public boolean shouldBlockExecution(boolean blockRoot, boolean blockDebug, boolean blockHooking) {
        SecurityReport report = mLastReport;

        if (blockRoot && report.rootDetected) {
            LogBridge.e(TAG, "❌ Root 环境被禁止运行");
            return true;
        }

        if (blockDebug && (report.debugDetected || report.hookDetected)) {
            LogBridge.e(TAG, "❌ 调试/Hook 环境被禁止运行");
            return true;
        }

        if (blockHooking && report.hookDetected) {
            LogBridge.e(TAG, "❌ Hook 框架被禁止运行");
            return true;
        }

        return false;
    }
}
}