package com.tv.live;

import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import com.tv.live.security.SecurityCore;

public final class UrlConfig {

    private static final String TAG = "UrlConfig";

    // ------------------------------------------------------------------
    // 【2026-10-13 SO 内建加密】源地址 / EPG 地址不再以任何形式留在 dex：
    //
    //   明文常量（← 最初，反编译一眼见）→ Java 侧 Base64 密文（← 上午，
    //   但密文的生产依赖外部脚本 gen_cipher.py / _gen_cipher.mjs）→
    //   整表下沉 .so（← 现在）。
    //
    //   常量表在 app/src/main/cpp/secstore.cpp：编译期异或加密后进 .rodata，
    //   dex 与 strings 都扫不到；生成密文的能力也搬进了 SO
    //   （SecurityCore.encryptToString），脚本丢失不再影响维护。
    //
    // ⚠️ 必须惰性取值，不可写进 static final：SecurityCore.init() 要 loadLibrary，
    // 可达数秒，放进 <clinit> 会把首次访问 UrlConfig 的线程（可能是主线程）卡住。
    // ------------------------------------------------------------------

    /** 源1地址（取到后缓存；"" = 尚未取到） */
    public static volatile String LIVE_1_URL = "";
    /** 源2地址（同上） */
    public static volatile String LIVE_2_URL = "";

    private static volatile String sEpg1, sEpg2;

    private UrlConfig() {}

    public static volatile String LIVE_URL_1_RAW = "";
    public static volatile String LIVE_URL_2_RAW = "";
    public static volatile String LIVE_URL_3_RAW = "";
    public static volatile String LIVE_URL_4_RAW = "";
    public static volatile String LIVE_URL_5_RAW = "";
    public static volatile String LIVE_URL_6_RAW = "";
    public static volatile String LIVE_URL_7_RAW = "";
    public static volatile String LIVE_URL_8_RAW = "";
    public static volatile String LIVE_URL_9_RAW = "";
    public static volatile String EPG_URL_1_RAW  = "";
    public static volatile String EPG_URL_2_RAW  = "";

    /** 源1 地址：惰性取值并缓存。取不到保持空串，由调用方按「空源」处理。 */
    public static String getLiveUrl()  {
        if (TextUtils.isEmpty(LIVE_1_URL)) {
            String v = SecurityCore.secureString(SecurityCore.C_LIVE_1);
            if (v != null) LIVE_1_URL = v;
        }
        return LIVE_1_URL;
    }

    /** 源2 地址：惰性取值并缓存（同上）。 */
    public static String getLiveUrl2() {
        if (TextUtils.isEmpty(LIVE_2_URL)) {
            String v = SecurityCore.secureString(SecurityCore.C_LIVE_2);
            if (v != null) LIVE_2_URL = v;
        }
        return LIVE_2_URL;
    }

    /** EPG-1（catvod）。失败不缓存，下次访问重试。 */
    public static String getEpgUrl()   {
        String v = sEpg1;
        if (v != null) return v;
        String plain = SecurityCore.secureString(SecurityCore.C_EPG_1);
        if (plain == null) return null;
        sEpg1 = plain;
        return sEpg1;
    }

    /**
     * EPG-2：erw.cc 的 gitee 镜像（518 频道含 CCTV1/卫视/地方台，
     * channel+programme 交替排列，兼容本解析器）。
     * 【2026-10-13】常量由明文→密文→下沉 SO，见类头注释。
     */
    public static String getEpgUrl2()  {
        String v = sEpg2;
        if (v != null) return v;
        String plain = SecurityCore.secureString(SecurityCore.C_EPG_2);
        sEpg2 = plain != null ? plain : "";
        return sEpg2;
    }

    public static String LIVE_URL   = "";
    public static String LIVE_URL_2 = "";
    /** 内置源3：本地实时生成 M3U（央视/卫视，无需外部订阅） */
    public static String LIVE_URL_CHANXIANG = "chanxiang://all";
    /** 内置源4：影视直播源，随包打进 assets/live_source_4.txt，不依赖网络订阅 */
    public static String LIVE_URL_4 = "asset://live_source_4";
    /** 内置源5：爱其意(任意播)央视/卫视等 97 频道，加载时逐个向 live.php 取实时直链 */
    public static String LIVE_URL_5 = "renyibo://all";
    /**
     * 内置源6：咪咕视频直播（央视/卫视/地方/影视/少儿）。
     * 频道表来自咪咕官方频道目录（program-sc.miguvideo.com），地址写成 miguhd://&lt;pID&gt;，
     * 起播那一刻才用 MiguUrlResolver 实时解析成带签名的 m3u8（直链短时有效，不可预解析）。
     */
    public static String LIVE_URL_6 = "migu://all";
    /**
     * 内置源7：afhgrj 影视点播（首页/电影/电视剧/综艺/动漫 5 分组）。
     * 频道表来自站点分类页（www.afhgrj.com），地址写成 afhgrj://&lt;vodId&gt;，
     * 起播那一刻才用 AfhgrjUrlResolver 解析播放页 player_aaaa 取真实 m3u8。
     */
    public static String LIVE_URL_7 = "afhgrj://all";
    /**
     * 【2026-10-19】内置源8 换成 renyibo.com（原 4kvm.top 已全站 404 下线）。
     * 架构照源7：频道表由 {@code RybSourceLoader} 纯 HTTP 抓 8 个分组生成，
     * 地址写成 {@code ryb://<slug>}，起播那一刻由 {@code RybUrlResolver} 解析详情页里的
     * {@code PLAY_SOURCES}（线路 + 集数 + 直链）拿真实 m3u8。
     */
    public static String LIVE_URL_8 = "ryb://all";
    /**
     * 内置源9：橘汁点播（协议层见 {@code com.tv.live.juzhi}）。
     * baseUrl 由 OSS 域名池动态下发；内容接口为 RSA/AES 加密 protobuf。
     * M3U 地址写作 {@code juzhi://<vodId>}，起播时惰性换 m3u8。
     */
    public static String LIVE_URL_9 = "juzhi://all";
    public static String EPG_URL    = "";
    public static String EPG_URL_2  = "";

    public static void fillPublicFields() {
        // 【修复】这里在 init-urlconfig-early 后台线程执行，SecurityCore.init() 可达数秒，
        //         晚于 MainActivity.onCreate 恢复的用户选择（epg_history / live_history 的 isDefault）。
        //         若无条件赋值会把用户选的源（如 ERW EPG）覆盖回默认第一条（Catvod）。
        //         改为「仅当为空时才兜底」，保留 MainActivity 已恢复的用户选择值。
        // 【2026-10-13 日志降级】原实现把 URL 本体直接拼进日志，而 9527 无需鉴权即可被
        //     同网段设备拉取 → 源订阅地址/EPG 镜像全量外泄。此处只输出「序号 + 类型」，
        //     排障所需的"第 N 路源是否就绪、是内置还是外链"信息完整保留。
        if (TextUtils.isEmpty(LIVE_URL)) LIVE_URL = getLiveUrl();
        if (TextUtils.isEmpty(LIVE_URL_1_RAW)) LIVE_URL_1_RAW = getLiveUrl();
        LogBridge.i(TAG, "源1 就绪 " + srcKind(LIVE_URL));

        if (TextUtils.isEmpty(LIVE_URL_2)) LIVE_URL_2 = getLiveUrl2();
        if (TextUtils.isEmpty(LIVE_URL_2_RAW)) LIVE_URL_2_RAW = getLiveUrl2();
        LogBridge.i(TAG, "源2 就绪 " + srcKind(LIVE_URL_2));

        if (TextUtils.isEmpty(LIVE_URL_3_RAW)) LIVE_URL_3_RAW = LIVE_URL_CHANXIANG;
        LogBridge.i(TAG, "源3 就绪 " + srcKind(LIVE_URL_CHANXIANG));

        if (TextUtils.isEmpty(LIVE_URL_4_RAW)) LIVE_URL_4_RAW = LIVE_URL_4;
        LogBridge.i(TAG, "源4 就绪 " + srcKind(LIVE_URL_4));

        if (TextUtils.isEmpty(LIVE_URL_5_RAW)) LIVE_URL_5_RAW = LIVE_URL_5;
        LogBridge.i(TAG, "源5 就绪 " + srcKind(LIVE_URL_5));

        if (TextUtils.isEmpty(LIVE_URL_6_RAW)) LIVE_URL_6_RAW = LIVE_URL_6;
        LogBridge.i(TAG, "源6 就绪 " + srcKind(LIVE_URL_6));

        if (TextUtils.isEmpty(LIVE_URL_7_RAW)) LIVE_URL_7_RAW = LIVE_URL_7;
        LogBridge.i(TAG, "源7 就绪 " + srcKind(LIVE_URL_7));

        // 【2026-10-19】源8 已换为 renyibo.com（原 4kvm 站点全站 404，kvm4 代码已移除）。
        if (TextUtils.isEmpty(LIVE_URL_8_RAW)) LIVE_URL_8_RAW = LIVE_URL_8;
        if (TextUtils.isEmpty(LIVE_URL_9_RAW)) LIVE_URL_9_RAW = LIVE_URL_9;

        String e1 = getEpgUrl();   if (e1 != null) { if (TextUtils.isEmpty(EPG_URL)) EPG_URL = e1; if (TextUtils.isEmpty(EPG_URL_1_RAW)) EPG_URL_1_RAW = e1; }
        String e2 = getEpgUrl2();  if (e2 != null) { if (TextUtils.isEmpty(EPG_URL_2)) EPG_URL_2 = e2; if (TextUtils.isEmpty(EPG_URL_2_RAW)) EPG_URL_2_RAW = e2; }

        LogBridge.i(TAG, "URL 配置解密完成，SecurityCore.isLoaded=" + SecurityCore.isLoaded() +
                   " | RAW[L1=" + (TextUtils.isEmpty(LIVE_URL_1_RAW) ? "null" : "ok") +
                   ", L2=" + (TextUtils.isEmpty(LIVE_URL_2_RAW) ? "null" : "ok") +
                   ", E1=" + (TextUtils.isEmpty(EPG_URL_1_RAW)  ? "null" : "ok") +
                   ", E2=" + (TextUtils.isEmpty(EPG_URL_2_RAW)  ? "null" : "ok") +
                   "]");
    }

    /**
     * 源地址类型判定：只区分「内置 / 外链」，<b>绝不输出 URL 本体</b>。
     *
     * <p>与 {@link com.tv.live.util.LogRedactor} 的地址降级规则形成双保险：
     * 即便此处漏改，脱敏器也会把 {@code https://…} 抹成 {@code https://host/<长度>}。
     */
    private static String srcKind(String url) {
        if (TextUtils.isEmpty(url)) return "[空]";
        return (url.startsWith("http://") || url.startsWith("https://")) ? "[外链]" : "[内置]";
    }
}
