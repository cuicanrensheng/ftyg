package com.tv.live.util;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.tv.live.util.LogBridge.LogCollector;

/**
 * 方案 G 探针：MediaCodec 真实预创建（触发硬解 HAL 加载）。
 *
 * 背景（详见 开发记录_2026-09-10.md 第十三章）：
 *   MediaCodecUtil.warmDecoderInfoCache / getDecoderInfos 均"只枚举不创建"，
 *   全模块唯一真正创建点 = SynchronousMediaCodecAdapter:60 MediaCodec.createByCodecName()。
 *   因此真预热必须走到 createByCodecName + configure + start。
 *
 * 设计原则：
 *   1. 默认关闭（ENABLED = false），主流程零副作用；开启只需改这一个常量。
 *   2. 全部在子线程执行 —— 吸取方案 A 教训（TV 上 225ms 枚举抢占主线程峰值窗口 → 反慢 0.2s）。
 *   3. 全流程 try-catch 包裹，任何异常静默失败，绝不崩溃、绝不阻塞播放。
 *   4. 只使用公开 API（MediaCodecList / MediaCodec），不依赖 @UnstableApi。
 *
 * 三档模式用于定位 HAL 加载究竟发生在哪一步：
 *   MODE_CREATE_ONLY   = 仅 createByCodecName
 *   MODE_CREATE_CONFIG = create + configure
 *   MODE_FULL          = create + configure + start  ← 真正触发 HAL
 */
public final class CodecWarmupProbe {

    private static final String TAG = "CodecWarmupProbe";

    /** 【唯一开关】true 开启预创建，false 完全不执行（当前：开启） */
    public static final boolean ENABLED = true;

    /** 幂等标记：进程内只预热一次（Application 与 TVPlayerManager 两处挂载都不会重复执行） */
    private static final java.util.concurrent.atomic.AtomicBoolean sStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 实验模式：0=仅创建 1=创建+configure 2=创建+configure+start（推荐） */
    public static final int MODE_CREATE_ONLY = 0;
    public static final int MODE_CREATE_CONFIG = 1;
    public static final int MODE_FULL = 2;
    public static final int MODE = MODE_FULL;

    /** 预热码流参数（仅用于 configure，不需要真实数据） */
    private static final String MIME = "video/avc";
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    /** 软解特征（预创建软解无意义，跳过） */
    private static final String[] SOFTWARE_PREFIX = {
            "OMX.google.", "c2.android.", "OMX.ffmpeg.", "OMX.SEC.", "OMX.rk.video_dec"
    };

    private static volatile Result sLastResult;

    private CodecWarmupProbe() {
    }

    /**
     * 开关关闭时立即返回，零开销。建议挂在 initPlayerAsync() 之后。
     */
    public static void runIfEnabled(Context context) {
        if (!ENABLED) {
            return;
        }
        if (!sStarted.compareAndSet(false, true)) {
            return; // 已预热过，跳过
        }
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        AppExecutors.io(() -> doWarmup(appCtx));
    }

    /** 是否已触发过（供日志排查） */
    public static boolean isStarted() {
        return sStarted.get();
    }

    /** 同步执行版本（测试用，勿在主线程调用） */
    public static Result runNow(Context context) {
        Context appCtx = context != null ? context.getApplicationContext() : null;
        return doWarmup(appCtx);
    }

    public static Result getLastResult() {
        return sLastResult;
    }

    private static Result doWarmup(Context context) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        r.startUptimeMs = android.os.SystemClock.uptimeMillis();
        MediaCodec codec = null;
        SurfaceTexture st = null;
        Surface surface = null;

        try {
            String codecName = pickHardwareDecoderName();
            r.codecName = codecName;
            if (codecName == null) {
                r.error = "no hardware decoder for " + MIME;
                log(context, "warmup ABORT: " + r.error);
                return r;
            }

            // 1) createByCodecName
            long t1 = System.currentTimeMillis();
            codec = MediaCodec.createByCodecName(codecName);
            r.createMs = System.currentTimeMillis() - t1;

            if (MODE >= MODE_CREATE_CONFIG) {
                // 2) configure —— 需要 Surface，用 0x0 假 SurfaceTexture，不需要任何真实 View
                //    （对应 media3 源码中的 video/PlaceholderSurface 思路）
                st = new SurfaceTexture(0);
                surface = new Surface(st);
                MediaFormat fmt = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
                fmt.setInteger(MediaFormat.KEY_WIDTH, WIDTH);
                fmt.setInteger(MediaFormat.KEY_HEIGHT, HEIGHT);

                long t2 = System.currentTimeMillis();
                codec.configure(fmt, surface, null, 0);
                r.configureMs = System.currentTimeMillis() - t2;

                if (MODE >= MODE_FULL) {
                    // 3) start —— 真正触发 HAL / OMX 组件加载的关键一步
                    long t3 = System.currentTimeMillis();
                    codec.start();
                    r.startMs = System.currentTimeMillis() - t3;
                }
            }

            r.success = true;
            r.totalMs = System.currentTimeMillis() - t0;
            log(context, "warmup OK codec=" + codecName
                    + " create=" + r.createMs + "ms"
                    + " configure=" + r.configureMs + "ms"
                    + " start=" + r.startMs + "ms"
                    + " total=" + r.totalMs + "ms"
                    + " [uptime " + r.startUptimeMs + "->" + android.os.SystemClock.uptimeMillis() + "]"
                    + " (sdk=" + Build.VERSION.SDK_INT + ")");
        } catch (Throwable e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            r.totalMs = System.currentTimeMillis() - t0;
            log(context, "warmup FAILED after " + r.totalMs + "ms -> " + r.error);
        } finally {
            // 立即释放：预创建只为触发 HAL 常驻加载，不持有实例
            try {
                if (codec != null) {
                    if (r.success && MODE >= MODE_FULL) {
                        try {
                            codec.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (surface != null) surface.release();
            } catch (Throwable ignored) {
            }
            try {
                if (st != null) st.release();
            } catch (Throwable ignored) {
            }
        }

        r.endUptimeMs = android.os.SystemClock.uptimeMillis();
        sLastResult = r;
        return r;
    }

    /**
     * 挑选硬解码器名称（公开 API，等价于 media3 内部 getDecoderInfos 的枚举逻辑，
     * 但不依赖 @UnstableApi）。
     */
    private static String pickHardwareDecoderName() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaCodecInfo best = null;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                boolean match = false;
                for (String type : info.getSupportedTypes()) {
                    if (MIME.equalsIgnoreCase(type)) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    continue;
                }
                String name = info.getName();
                if (isSoftware(name)) {
                    continue;
                }
                // 优先选设备厂商解码器（MTK / 高通 / 海思 / Amlogic）
                if (best == null || isVendorPreferred(name)) {
                    best = info;
                }
            }
            return best == null ? null : best.getName();
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean isSoftware(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        for (String p : SOFTWARE_PREFIX) {
            if (lower.startsWith(p.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVendorPreferred(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("mtk") || lower.contains("mediatek")
                || lower.contains("qcom") || lower.contains("hisilicon")
                || lower.contains("amlogic") || lower.contains("rockchip");
    }

    private static void log(Context context, String msg) {
        LogBridge.i(TAG, msg);
        try {
            LogCollector.getInstance().addLog(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static final class Result {
        public boolean success;
        public String codecName;
        public long createMs;
        public long configureMs;
        public long startMs;
        public long totalMs;
        /** 预热开始/结束时相对系统启动的毫秒数，用于与首帧时刻对齐对比 */
        public long startUptimeMs;
        public long endUptimeMs;
        public String error;

        @Override
        public String toString() {
            return "CodecWarmupProbe.Result{success=" + success
                    + ", codec=" + codecName
                    + ", create=" + createMs + "ms"
                    + ", configure=" + configureMs + "ms"
                    + ", start=" + startMs + "ms"
                    + ", total=" + totalMs + "ms"
                    + ", error=" + error + "}";
        }
    }
}
