package com.tv.live.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tv.live.util.LogRedactor;

import org.junit.Test;

/**
 * ObStr / LogRedactor 纯离线单测（2026-10-13 加固）。
 *
 * <p>核心目的：验证 {@code int[]} 密文表能被运行时正确解码回明文 ——
 * 密文是脚本按 {@code key = f(BuildConfig.APPLICATION_ID)} 生成的，
 * 一旦密钥派生公式或数组内容有任何偏差，这里会立刻失败，
 * 避免"编译通过但检测静默失效"这种最难发现的问题。
 */
public class ObStrTest {

    @Test
    public void decode_matches_plaintext() {
        assertEquals("frida-server", ObStr.d(ObStr.F_FRIDA_SRV));
        assertEquals("frida-agent", ObStr.d(ObStr.F_FRIDA_AGENT));
        assertEquals("frida-injector", ObStr.d(ObStr.F_FRIDA_INJECT));
        assertEquals("frida", ObStr.d(ObStr.F_FRIDA));
        assertEquals("gadget", ObStr.d(ObStr.F_GADGET));

        assertEquals("/data/local/tmp/frida-server", ObStr.d(ObStr.F_P_TMP_FRIDA));
        assertEquals("/data/local/tmp/frida-gadget", ObStr.d(ObStr.F_P_TMP_GADGET));
        assertEquals("/system/bin/frida-server", ObStr.d(ObStr.F_P_BIN_FRIDA));
        assertEquals("/system/xbin/frida-server", ObStr.d(ObStr.F_P_XBIN_FRIDA));

        assertEquals("de.robv.android.xposed.XposedBridge", ObStr.d(ObStr.X_CLASS_BRIDGE));
        assertEquals("com.saurik.substrate.MS", ObStr.d(ObStr.X_CLASS_SUBSTRATE));
        assertEquals("/system/framework/XposedBridge.jar", ObStr.d(ObStr.X_P_JAR_FW));
        assertEquals("/data/local/tmp/XposedBridge.jar", ObStr.d(ObStr.X_P_JAR_TMP));
        assertEquals("/data/local/tmp/Xposed", ObStr.d(ObStr.X_P_TMP_XPOSED));
        assertEquals("/data/adb/modules/lsposed", ObStr.d(ObStr.X_P_LSPD_MOD));

        assertEquals("magisk", ObStr.d(ObStr.M_MAGISK));
        assertEquals("/sbin/magisk", ObStr.d(ObStr.M_P_SBIN));
        assertEquals("/system/bin/su", ObStr.d(ObStr.S_P_BIN_SU));
        assertEquals("TracerPid", ObStr.d(ObStr.R_TRACERPID));
        assertEquals("/system/bin/sh", ObStr.d(ObStr.R_SH));
    }

    @Test
    public void decode_is_stable_and_nonEmpty() {
        // 多次调用结果一致（key 只算一次，但也不应受调用顺序影响）
        for (int i = 0; i < 5; i++) {
            assertEquals("frida-server", ObStr.d(ObStr.F_FRIDA_SRV));
        }
        assertEquals("", ObStr.d(null));
        assertEquals("", ObStr.d(new int[0]));
    }

    @Test
    public void redact_replaces_detection_terms_with_codes() {
        String out = LogRedactor.redact("检测到 Frida 文件: /data/local/tmp/frida-server");
        assertFalse("不得残留明文特征", out.toLowerCase().contains("frida"));
        assertTrue("应替换为代号", out.contains("[CHK-01]"));
    }

    @Test
    public void redact_masks_sensitive_values() {
        String sha = LogRedactor.redact("当前签名 SHA256=xQdedEk3xbKAsqg0WqDdH0qmjiYAkARaVVtrTVXdQAQ=");
        assertFalse("签名全量值不得外泄", sha.contains("xQdedEk3xbKAsqg0WqDdH0qmjiYAkARaVVtrTVXdQAQ="));
        assertTrue(sha.contains("<masked>"));

        // auth_key 在非 URL 语境下仍须被标签规则命中
        String ak = LogRedactor.redact("已附加参数 auth_key=179192790044dea7ccc6a42d8931525f32b4961a2a");
        assertFalse("auth_key 不得外泄", ak.contains("179192790044dea7ccc6a42d8931525f32b4961a2a"));
        assertTrue(ak.contains("<masked>"));

        String dex = LogRedactor.redact("DEX hash: m4e7Ac+BmLQ3YXyDxxx");
        assertTrue(dex.contains("<masked>"));
    }

    @Test
    public void redact_degrades_web_urls_to_host_only() {
        // 源订阅地址：域名保留（排障要区分源站），仓库路径与文件名必须抹掉
        String src = LogRedactor.redact(
                "LIVE_URL_2(源2): https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u");
        assertFalse("仓库名不得外泄", src.contains("qf_1111"));
        assertFalse("文件名不得外泄", src.contains("playlist.m3u"));
        assertTrue("域名保留以便排障", src.contains("https://gitee.com/"));

        // CDN 直链：签名/鉴权参数随路径一并丢弃
        String cdn = LogRedactor.redact(
                "【咪咕】回看拿到直链 → http://hlsbkmgspvod.miguvideo.com/xx/yy.m3u8?auth=abc123&uid=9");
        assertFalse("签名参数不得外泄", cdn.contains("auth=abc123"));
        assertFalse("vodId 不得外泄", cdn.contains("yy.m3u8"));
        assertTrue(cdn.contains("hlsbkmgspvod.miguvideo.com"));

        // 仅域名、无路径时原样保留
        assertEquals("https://example.com", LogRedactor.redact("https://example.com"));
    }

    @Test
    public void redact_degrades_internal_schemes() {
        // 内部伪协议：scheme 已表明是哪个源，值（all / 资源名 / vodId）无排障价值，全部掩码
        assertEquals("renyibo://<3>", LogRedactor.redact("renyibo://all"));
        assertEquals("chanxiang://<3>", LogRedactor.redact("chanxiang://all"));
        assertEquals("migu://<3>", LogRedactor.redact("migu://all"));
        assertEquals("asset://<13>", LogRedactor.redact("asset://live_source_4"));
        assertEquals("afhgrj://<6>", LogRedactor.redact("afhgrj://122157"));
        assertEquals("miguhd://<6>", LogRedactor.redact("miguhd://600001"));
    }

    @Test
    public void redact_keeps_normal_logs_intact() {
        // 普通业务日志不应被误伤
        String normal = "起播耗时分解: 下载=120ms 归一化=3ms 剥离=18ms 总计=141ms 分片=512";
        assertEquals(normal, LogRedactor.redact(normal));

        // 类名/包名不含 "://"，不应被当成地址
        String cls = "com.tv.live.security.SecurityCore.isLoaded=true";
        assertEquals(cls, LogRedactor.redact(cls));
    }

    // ==================== 2026-10-13 补：响应体整体收纳 ====================

    @Test
    public void redact_collapses_plaintext_json_body() {
        // 实测原样：源5点播 POST 响应体被整体 dump，明文 JSON 里带片名/vod_id/图片 CDN
        String in = "源5点播：POST VOD resp code=200 body={\"code\":1,\"msg\":\"视频列表\","
                + "\"page\":3,\"data\":[{\"vod_id\":497487,\"vod_name\":\"碎玉成昭\","
                + "\"vod_pic\":\"https://p6-novel.byteimg.com/xxx.webp\"}]}";
        String out = LogRedactor.redact(in);
        assertFalse("片名不得外泄", out.contains("碎玉成昭"));
        assertFalse("vod_id 不得外泄", out.contains("497487"));
        assertFalse("图片 CDN 不得外泄", out.contains("byteimg"));
        assertTrue("code=200 等排障信息应保留", out.contains("resp code=200"));
        assertTrue("应留收纳提示", out.contains("body=\u2026("));
    }

    @Test
    public void redact_collapses_plaintext_json_head_label() {
        // 实测原样：VodResponse 的另一个出口 —— 明文头=
        String in = "信封解密成功：SEG1 token 数=16 buf=19424 明文头={\"code\":1,"
                + "\"msg\":\"视频详情\",\"data\":{\"vod_info\":{\"vod_id\":537136,"
                + "\"vod_name\":\"铁拳教育\"}}}";
        String out = LogRedactor.redact(in);
        assertFalse("片名不得外泄", out.contains("铁拳教育"));
        assertFalse("vod_id 不得外泄", out.contains("537136"));
        assertTrue("buf 等排障字段应保留", out.contains("buf=19424"));
        assertTrue("应留收纳提示", out.contains("明文头=\u2026("));
    }

    @Test
    public void redact_collapses_base64_body_containing_plus() {
        // ★ 回归点：P_LONG_B64 刻意排除了 '+'，含 '+' 的密文段曾整段逃逸
        //   实测泄漏样本：body=MHg1OT…(107)=DQRnSb+KpyW+X9zfwi4IWcpC7IcOYjLSZ+izZluZ…(58)…
        final String leak = "DQRnSb+KpyW+X9zfwi4IWcpC7IcOYjLSZ+izZluZ";
        String in = "源5点播：POST VOD resp code=200 body="
                + "MHg1OTc0ZDMxM2MxY2IxY2IxY2IxY2IxY2IxY2Ix" + leak
                + "QWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=";
        String out = LogRedactor.redact(in);
        assertFalse("含 + 的密文段不得外泄（本次回归点）", out.contains(leak));
        assertFalse("整段 base64 不得残留", out.contains("MHg1OTc0ZDMxM2MxY2Ix"));
        assertTrue("应留收纳提示", out.contains("body=\u2026("));
    }

    @Test
    public void redact_body_collapse_is_idempotent() {
        String in = "源5点播：POST VOD resp code=200 body={\"code\":1,\"vod_name\":\"碎玉成昭\"}";
        String once = LogRedactor.redact(in);
        String twice = LogRedactor.redact(once);
        assertEquals("二次脱敏不得再改变结果", once, twice);
        assertTrue(once.contains("body=\u2026("));
    }

    @Test
    public void redact_does_not_touch_identifier_like_body_token() {
        // 词边界保护：httpBody= 之类标识符内部不应被当成响应体标签
        String s = "httpBody=abcdefg 普通文本";
        assertEquals(s, LogRedactor.redact(s));
    }

    @Test
    public void redact_collapses_json_weak_label_data() {
        // ★ 电视端实测漏网出口：video_detail 无直链时把明文 JSON 打在 data= 上
        String in = "源5点播：video_detail 可解析但无直链 play，data={\"code\":1,"
                + "\"msg\":\"视频详情\",\"data\":{\"vod_info\":{\"vod_id\":491632,"
                + "\"vod_name\":\"锦鲤小奶团，带领全家逆天改命\"}}}";
        String out = LogRedactor.redact(in);
        assertFalse("片名不得外泄", out.contains("锦鲤小奶团"));
        assertFalse("vod_id 不得外泄", out.contains("491632"));
        assertTrue("提示语保留", out.contains("无直链 play"));
        assertTrue("应留收纳提示", out.contains("data=\u2026("));
    }

    @Test
    public void redact_keeps_non_json_weak_labels_intact() {
        // 弱标签只在值是 JSON 时才收纳，非 JSON 值一律放过（避免大面积误伤）
        String a = "JobScheduler 兜底任务设置失败 result=0";
        assertEquals(a, LogRedactor.redact(a));
        String b = "someData=1234567890 普通日志";
        assertEquals(b, LogRedactor.redact(b));
        String c = "detail=已完成，耗时 12ms data=abc";
        assertEquals(c, LogRedactor.redact(c));
    }

    @Test
    public void redact_keeps_pure_m3u8_log_intact() {
        // 起播埋点里没有 body= 标签，不得被误伤
        String s = "起播耗时分解: 下载=796ms 归一化=1ms 剥离=0ms 总计=797ms 原始字节=96 分片=0";
        assertEquals(s, LogRedactor.redact(s));
    }
}
