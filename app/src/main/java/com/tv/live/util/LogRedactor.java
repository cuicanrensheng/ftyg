package com.tv.live.util;

import com.tv.live.security.ObStr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LogRedactor —— 9527 日志脱敏器（2026-10-13）
 *
 * <p>背景：9527 HTTP 服务的 {@code /api/logs} 无需鉴权即可拉取全量日志（该问题本轮不修，
 * 用户指定只做 3~7）。既然日志可被同网段任意设备读取，日志内容本身就必须"去说明书化"：
 * <ol>
 *   <li><b>检测命中项改代号</b>：原文写"检测到 Frida 文件: /data/local/tmp/frida-server"，
 *       等于直接告诉逆向者改哪一项就能过检。</li>
 *   <li><b>敏感值掩码</b>：签名 SHA256、DEX hash、auth_key、信封密文等不应以全量形式外泄。</li>
 *   <li><b>地址降级</b>（2026-10-13 补）：源订阅地址、CDN 直链、内部伪协议在日志里
 *       全量出现，等于把"内容从哪来"直接交出去。此处统一降级为
 *       {@code scheme://host/<长度>}，只保留排障必需的域名，具体路径/参数/频道 ID 一律丢弃。</li>
 *   <li><b>响应体整体收纳</b>（2026-10-13 补，本文件 L203+）：点播接口的响应体被
 *       {@code body=} / {@code 明文头=} 整体 dump，明文 JSON 里带 {@code vod_id} /
 *       {@code vod_name}（片名）/ {@code vod_pic}（图片 CDN），是「内容层」泄露 ——
 *       与"屏蔽名单去名字化"同类，只是换了个出口。且因为
 *       {@link #P_LONG_B64} 刻意排除了 {@code +}，含 {@code +} 的密文段会整段逃逸。
 *       故此处按<b>标签锚定</b>把标签后整段值收纳成 {@code …(字符数)}：
 *       既覆盖 JSON 体，也覆盖含 {@code +}/{@code /} 的 base64 体。</li>
 * </ol>
 *
 * <p>代号对照表（只存在于源码注释，编译后不进入 dex）：
 * <pre>
 *   [CHK-01] 运行时注入工具   （原 frida / frida-server / frida-agent / frida-injector / gadget）
 *   [CHK-02] 运行时注入框架   （原 xposed / lsposed / edxposed / substrate / XposedBridge）
 *   [CHK-03] 提权工具         （原 magisk）
 *   [CHK-04] 调试跟踪         （原 TracerPid）
 *   [CHK-05] 提权环境         （原 root）
 *   [CHK-06] 虚拟化环境       （原 emulator / Goldfish / qemu）
 *   [CHK-07] 调试器           （原 debugger）
 * </pre>
 *
 * <p>脱敏只作用于"展示与落盘"的日志；{@code ExceptionReporter} 上报仍在脱敏前取原文，
 * 保证服务端拿到完整现场。
 */
public final class LogRedactor {

    private LogRedactor() {}

    private static final String CODE_F = "[CHK-01]";
    private static final String CODE_X = "[CHK-02]";
    private static final String CODE_M = "[CHK-03]";
    private static final String CODE_T = "[CHK-04]";
    private static final String CODE_R = "[CHK-05]";
    private static final String CODE_E = "[CHK-06]";
    private static final String CODE_D = "[CHK-07]";

    /**
     * 长 base64url 串兜底：信封密文 body 等。
     *
     * <p>字符集<b>刻意排除 {@code /} 与 {@code +}</b>：这两个字符一旦纳入，
     * URL 路径会被连成一整片（例如
     * {@code com/20260705/12678_66ebbba5/index} 恰好 33 字符 → 误判为密文并掩码），
     * 导致源地址被破坏、调试不可用。实测该误伤已由单测
     * {@code ObStrTest#redact_keeps_normal_logs_intact} 捕获。
     * 标准 base64（含 {@code +} / {@code /}）的敏感值改由上方<b>带标签规则</b>精确覆盖：
     * {@code SHA256=} / {@code auth_key=} / {@code 签名哈希:} / 以及 2026-10-13 新增的
     * {@link #P_LABELED_BODY}（{@code body=} / {@code 明文头=}，整段收纳）。
     */
    private static final Pattern P_LONG_B64 =
            Pattern.compile("[A-Za-z0-9_\\-]{32,}");
    /** root 用词边界，避免误伤 rootView、/root/ 之类的普通文本 */
    private static final Pattern P_ROOT =
            Pattern.compile("\\broot\\b", Pattern.CASE_INSENSITIVE);
    /** auth_key=xxx（CCTV 等 CDN 的鉴权串） */
    private static final Pattern P_AUTHKEY =
            Pattern.compile("(auth_?key=)([A-Za-z0-9_\\-]{6,})", Pattern.CASE_INSENSITIVE);
    /** 当前签名 SHA256=xxx */
    private static final Pattern P_SHA =
            Pattern.compile("(SHA256=)([A-Za-z0-9+/=]{6,})", Pattern.CASE_INSENSITIVE);
    /** 「签名哈希: xxx」「DEX hash: xxx」 */
    private static final Pattern P_LABELED_HASH =
            Pattern.compile("((?:签名哈希|DEX\\s*hash)\\s*[:：]\\s*)([A-Za-z0-9+/=]{6,})",
                    Pattern.CASE_INSENSITIVE);
    /**
     * 任意带 scheme 的地址（{@code https://…}、{@code renyibo://all}、{@code miguhd://600001} …）。
     *
     * <p>字符集按 RFC 3986 的 allowed 集合**刻意剔除了 {@code ' ( ) [ ] ,}** ——
     * 这几个字符在日志里通常充当包裹符（如 {@code url('http://x/y')}、
     * {@code [源5] http://x/y, code=200}），纳入后会把尾随符号吞进地址，破坏可读性。
     * 中文与空白天然不在集合内，故地址后紧跟中文注释时能正确截断。
     */
    private static final Pattern P_URL = Pattern.compile(
            "[a-zA-Z][a-zA-Z0-9+.\\-]*://[A-Za-z0-9\\-._~:/?#@!$&*+;=%]+");

    /**
     * 带标签的响应体：{@code body=…} / {@code 明文头=…}。
     *
     * <p>这些标签后面跟的是<b>完整响应体</b>（明文 JSON 或密文 base64），必须整段收掉。
     * 与 {@link #P_LONG_B64} 的区别：那条靠「字符集 + 长度」猜，本规则靠<b>标签锚定</b>，
     * 因此不受字符集限制 —— 这正是修掉「含 {@code +} 的密文段逃逸」的关键。
     *
     * <p>来源（2026-10-13 实测校准，全部为「标签 + 值」且值在行尾）：
     * <ul>
     *   <li>{@code RenYiBoVodSourceLoader}：{@code "…resp code=200 body=" + trunc(body,200)}</li>
     *   <li>{@code RenYiBoVodSourceLoader}：{@code "…body_len=.. body=" + trunc(cipherBody,120)}</li>
     *   <li>{@code VodResponse}：{@code "…buf=.. 明文头=" + trunc(plain,80)}</li>
     *   <li>{@code NowTvSourceApi} ×3：{@code "…body=" + abbreviate(body)}</li>
     * </ul>
     */
    private static final Pattern P_LABELED_BODY =
            Pattern.compile("(?:body=|明文头=)", Pattern.CASE_INSENSITIVE);

    /**
     * <b>弱标签</b>：这些词在正常日志里太常见（{@code result=0}、{@code data=5}…），
     * 故<b>只在值确实以 &#123; / [ 开头时才收纳</b>（见 {@link #opaqueValueEnd}）。
     *
     * <p>2026-10-13 二次补强实测漏网出口（电视端抓到）：
     * {@code RenYiBoVodSourceLoader} 的
     * {@code "源5点播：video_detail 可解析但无直链 play，data=" + detail} —— 明文 JSON，
     * 含 {@code vod_id}/{@code vod_name}，标签为 {@code data=} 而非 {@code body=}。
     */
    private static final Pattern P_LABELED_JSON = Pattern.compile(
            "(?:data=|resp=|payload=|json=|result=|detail=)", Pattern.CASE_INSENSITIVE);

    /** 收纳后的提示符（同时用作幂等判据：值以它开头说明已处理过） */
    private static final char ELLIPSIS = '\u2026';   // …

    /**
     * 对一条日志正文做脱敏。幂等，可重复调用。
     *
     * @param msg 原始正文（可能为 null）
     * @return 脱敏后的正文；入参为 null / 空串时原样返回
     */
    public static String redact(String msg) {
        if (msg == null || msg.isEmpty()) {
            return msg;
        }
        String s = codes(msg);
        s = mask(s);
        return s;
    }

    // ==================== 一、检测命中项 → 代号 ====================

    private static String codes(String s) {
        // 注入工具（先长后短，避免 "frida" 先把 "frida-server" 截断成 "[CHK-01]-server"）
        s = repCI(s, ObStr.d(ObStr.F_FRIDA_SRV), CODE_F);
        s = repCI(s, ObStr.d(ObStr.F_FRIDA_AGENT), CODE_F);
        s = repCI(s, ObStr.d(ObStr.F_FRIDA_INJECT), CODE_F);
        s = repCI(s, ObStr.d(ObStr.F_FRIDA), CODE_F);
        s = repCI(s, ObStr.d(ObStr.F_GADGET), CODE_F);

        // 注入框架
        s = repCI(s, ObStr.d(ObStr.X_CLASS_BRIDGE), CODE_X);
        s = repCI(s, ObStr.d(ObStr.X_P_JAR_FW), CODE_X);
        s = repCI(s, ObStr.d(ObStr.X_P_JAR_TMP), CODE_X);
        s = repCI(s, ObStr.d(ObStr.R_LSPOSED), CODE_X);
        s = repCI(s, ObStr.d(ObStr.R_EDXPOSED), CODE_X);
        s = repCI(s, ObStr.d(ObStr.X_SUBSTRATE), CODE_X);
        s = repCI(s, ObStr.d(ObStr.X_LOWER), CODE_X);

        // 其余单项
        s = repCI(s, ObStr.d(ObStr.M_MAGISK), CODE_M);
        s = repCI(s, ObStr.d(ObStr.R_TRACERPID), CODE_T);
        s = repCI(s, ObStr.d(ObStr.R_DEBUGGER), CODE_D);
        s = repCI(s, ObStr.d(ObStr.R_EMULATOR), CODE_E);
        s = repCI(s, ObStr.d(ObStr.R_GOLDFISH), CODE_E);
        s = repCI(s, ObStr.d(ObStr.R_QEMU), CODE_E);
        s = P_ROOT.matcher(s).replaceAll(CODE_R);
        return s;
    }

    /** 大小写不敏感地替换全部命中（indexOf 实现，比正则快） */
    private static String repCI(String s, String key, String code) {
        if (s == null || key == null || key.isEmpty()) {
            return s;
        }
        final String low = s.toLowerCase(java.util.Locale.ROOT);
        final String k = key.toLowerCase(java.util.Locale.ROOT);
        int idx = low.indexOf(k);
        if (idx < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int from = 0;
        while (idx >= 0) {
            sb.append(s, from, idx).append(code);
            from = idx + key.length();
            idx = low.indexOf(k, from);
        }
        sb.append(s, from, s.length());
        return sb.toString();
    }

    // ==================== 二、敏感值 → 掩码 ====================

    private static String mask(String s) {
        // 顺序：响应体整段收纳（最外层，先把整个 body 吃掉，避免里面 URL/密文被"降级后残留"）
        //       → 地址降级 → 带标签哈希 → SHA256= → auth_key= → 长 base64 兜底
        s = maskOpaqueBodies(s);
        s = maskUrls(s);
        s = P_LABELED_HASH.matcher(s).replaceAll("$1<masked>");
        s = P_SHA.matcher(s).replaceAll("$1<masked>");
        s = P_AUTHKEY.matcher(s).replaceAll("$1<masked>");
        s = maskLongB64(s);
        return s;
    }

    // ==================== 二·补、响应体整体收纳 ====================

    /**
     * 把「标签 + 响应体 / JSON 值」整段收纳成 {@code …(字符数)}。
     *
     * <p>为什么必须"整段"而不是靠 {@link #P_LONG_B64}：
     * <ol>
     *   <li>明文 JSON 体带花括号、引号、反斜杠转义（片名就是反斜杠-u 形式）等结构字符，
     *       {@link #P_LONG_B64} 的字符集根本不含它们，
     *       原样落地会把 {@code vod_id}/{@code vod_name}/{@code vod_pic} 直接交出去；</li>
     *   <li>密文体里含 {@code +}（标准 base64 的 62 号字符），而 {@link #P_LONG_B64} <b>刻意排除了
     *       {@code +} 与 {@code /}</b>（为了不误伤 URL 路径），于是含 {@code +} 的那一段会整段逃逸
     *       —— 实测踩到：{@code body=MHg1OT…(107)=DQRnSb+KpyW+X9zfwi4ICP7IcOYjLSZ+izZluZ…}。</li>
     * </ol>
     *
     * <p><b>两级标签</b>：
     * <ul>
     *   <li>{@link #P_LABELED_BODY} <b>强标签</b>（{@code body=} / {@code 明文头=}）：
     *       值一定就是响应体 → 即便不是 JSON（base64 密文）也整段丢到行尾；</li>
     *   <li>{@link #P_LABELED_JSON} <b>弱标签</b>（{@code data=} / {@code resp=} / {@code json=} /
     *       {@code payload=} / {@code result=} / {@code detail=}）：只收 JSON 值，其余原样放过。</li>
     * </ul>
     *
     * <p>取值边界：
     * <ul>
     *   <li>值以 &#123; / [ 开头 → 按括号配对（含字符串与转义识别）取到配对处；
     *       截断的 JSON 配不上对 → 取到行尾（实测 {@code trunc()} 就是行尾）；</li>
     *   <li>强标签的其它形态（base64 / 纯文本体）→ 取到行尾；弱标签的其它形态 → 不处理。</li>
     * </ul>
     *
     * <p>幂等：值以 {@link #ELLIPSIS} 开头时视为已处理，原样跳过。
     */
    private static String maskOpaqueBodies(String s) {
        if (s.indexOf('=') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int from = 0;
        while (from < s.length()) {
            final int[] hit = findOpaqueLabel(s, from);
            if (hit == null) {
                sb.append(s, from, s.length());
                break;
            }
            final int valStart = hit[0];
            final boolean strong = hit[1] == 1;
            int q = valStart;
            while (q < s.length() && s.charAt(q) == ' ') {
                q++;
            }
            if (q < s.length() && s.charAt(q) == ELLIPSIS) {
                // 已收纳过：保留原样，从 … 继续往后扫（不会再命中同一个标签）
                sb.append(s, from, q);
                from = q;
                continue;
            }
            final int valEnd = opaqueValueEnd(s, q, strong);
            if (valEnd < 0) {
                // 弱标签但值不是 JSON → 不动它（避免误伤 result=0 之类）
                sb.append(s, from, q);
                from = q;
                continue;
            }
            sb.append(s, from, valStart);   // 到标签为止
            sb.append(s, valStart, q);      // 保留原有空格
            sb.append(ELLIPSIS).append('(').append(valEnd - q).append(')');
            from = valEnd;
        }
        return sb.toString();
    }

    /**
     * 取 {@code from} 之后<b>最早</b>的有效标签（强 / 弱两组一起比）。
     *
     * @return {@code [值起始下标, 是否强标签(1/0)]}；找不到返回 {@code null}
     */
    private static int[] findOpaqueLabel(String s, int from) {
        int bestAt = -1, bestEnd = -1;
        boolean bestStrong = false;
        for (int pass = 0; pass < 2; pass++) {
            final Pattern p = (pass == 0) ? P_LABELED_BODY : P_LABELED_JSON;
            final Matcher m = p.matcher(s);
            m.region(from, s.length());
            while (m.find()) {
                // 词边界保护：不命中 httpBody= / someData= 之类的标识符内部
                final int at = m.start();
                if (at > 0 && Character.isLetterOrDigit(s.charAt(at - 1))) {
                    continue;
                }
                if (bestAt < 0 || at < bestAt) {
                    bestAt = at;
                    bestEnd = m.end();
                    bestStrong = (pass == 0);
                }
                break;      // 同一 pattern 内 find() 已是最早命中
            }
        }
        return bestAt < 0 ? null : new int[]{bestEnd, bestStrong ? 1 : 0};
    }

    /**
     * 求"标签值"的结束下标，见 {@link #maskOpaqueBodies} 取值边界。
     *
     * @param strong 强标签（{@code body=} / {@code 明文头=}）：非 JSON 也整段丢弃到行尾
     * @return 值结束下标；{@code -1} 表示"弱标签且值不是 JSON，不处理"
     */
    private static int opaqueValueEnd(String s, int start, boolean strong) {
        if (start >= s.length()) {
            return strong ? s.length() : -1;
        }
        final char c = s.charAt(start);
        if (c != '{' && c != '[') {
            return strong ? s.length() : -1;   // 强标签：整段到行尾；弱标签：放过
        }
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    inStr = false;
                }
                continue;
            }
            if (ch == '"') {
                inStr = true;
            } else if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                if (depth <= 0) {
                    return i + 1;
                }
            }
        }
        return s.length();                  // 截断的 JSON：吃到行尾
    }

    // ==================== 三、地址降级 ====================

    /**
     * 把所有带 scheme 的地址降级为「域名 + 长度」，路径/查询/频道 ID 全部丢弃。
     *
     * <p>保留域名是为了排障（区分是打到 {@code miguvideo} 还是 {@code gitee}），
     * 域名本身是公开信息、不含密钥；真正有价值的 {@code /qf_1111/iptv/raw/master/xxx.m3u}
     * 路径与 {@code ?auth=…} 参数才是「内容从哪来」的答案，必须抹掉。
     */
    private static String maskUrls(String s) {
        Matcher m = P_URL.matcher(s);
        if (!m.find()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int last = 0;
        do {
            sb.append(s, last, m.start());
            sb.append(shortenUrl(m.group()));
            last = m.end();
        } while (m.find());
        sb.append(s, last, s.length());
        return sb.toString();
    }

    /** 见 {@link #maskUrls}。内部伪协议（renyibo/asset/migu/afhgrj…）的值一并掩码。 */
    private static String shortenUrl(String url) {
        final int sep = url.indexOf("://");
        if (sep <= 0) {
            return url;
        }
        final String scheme = url.substring(0, sep);
        final String rest = url.substring(sep + 3);
        final boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!web) {
            // 内部伪协议：scheme 本身已表明是哪个源，值（all / live_source_4 / <vodId>）无排障价值
            return scheme + "://<" + rest.length() + ">";
        }
        final int slash = rest.indexOf('/');
        if (slash < 0) {
            // 只有域名：https://example.com
            return scheme + "://" + rest;
        }
        final String host = rest.substring(0, slash);
        final String tail = rest.substring(slash);
        return scheme + "://" + host + (tail.isEmpty() ? "" : "/<" + tail.length() + ">");
    }

    private static String maskLongB64(String s) {
        Matcher m = P_LONG_B64.matcher(s);
        if (!m.find()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int last = 0;
        do {
            sb.append(s, last, m.start());
            final String v = m.group();
            // 保留前 6 位便于对账，长度给出，其余丢弃
            sb.append(v, 0, 6).append("…(").append(v.length()).append(')');
            last = m.end();
        } while (m.find());
        sb.append(s, last, s.length());
        return sb.toString();
    }
}
