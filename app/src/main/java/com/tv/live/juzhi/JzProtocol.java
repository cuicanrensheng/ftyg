package com.tv.live.juzhi;

import android.util.Base64;

import com.tv.live.security.SecurityCore;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 橘汁（com.exxammpea.a1 v3.0.2.6）协议基元。
 *
 * <p>只做三件事：<b>常量</b>、<b>AES/RSA 原语</b>、<b>protobuf 读写</b>。
 * 不含网络、不含业务，便于单独核对。
 *
 * <p><b>加密体系（2026-10-23 对服务端逐项实测确认）</b>
 * <pre>
 *   safeSecretKey = AES-256/ECB 解密固定密文得到 → OC1A06E197EF10CF3F6058CA7A803B5E
 *                   （key = KEY1+KEY4+KEY6+MAX_ENCRYPTKEY+Md1khdaF）
 *   safeCode      = 签名派生（双重 Base64），实测值 SGNWTJHSK05SU3NBSLJQSELXREFRPT0=
 *                   （key = KEY2+KEY3+KEY5+MAX_ENCRYPTKEY+Md1khdaF）
 *   firstLevelPublic  = AES 解密固定密文得到的 RSA 公钥（1024bit，DER 162B）
 *   secondLevelPublic = POST /api/v5/find/app/zone 协商下发（RSAPublic 5 段拼装）
 * </pre>
 */
public final class JzProtocol {

    private JzProtocol() {
    }

    // ------------------------------------------------------------------
    // 协议常量
    // ------------------------------------------------------------------

    /** M3U 里的自定义协议前缀（与 afhgrj://、ryb://、nbm:// 同角色）。
     *  非敏感（源分发判断用，源码即可推断），保留字面量。 */
    public static final String SCHEME = "juzhi://";

    /** 内置源9 在 UrlConfig 里的源标识（用于分发分支） */
    public static final String SOURCE_URL = "juzhi://all";

    // ------------------------------------------------------------------
    // 【2026-10-23 第五批】敏感常量全部下沉 SO（cpp/secstore.cpp id=28~49）。
    //
    // ⚠️ 三条规定，改这里前必读：
    //   1) **不能改回 static final**：编译期常量会被内联到每个使用点，
    //      dex 里又见明文（safeCode/safeSecretKey/decryptKey 三把 AES 工作密钥同此）；
    //   2) **不能进 <clinit>**：secureString 首次调用会触发 SecurityCore.init()
    //      的 loadLibrary（可达数秒），所有取值必须是「首次访问才解密 + volatile 缓存」；
    //   3) **调用方必须在非主线程**：当前 JzApi / JzSourceLoader / JzUrlResolver
    //      的取值点都在网络 IO 线程；若将来要在主线程用，先自行预热。
    //
    // 签名不匹配时 SO 返回的是**搅乱串而非 null**（见 cpp/security.cpp 的
    // EXPECTED_SIG_SHA256 红线）→ 表现为静默失效，没有显式报错。
    // ------------------------------------------------------------------

    private static String secure(int id) {
        String d = SecurityCore.secureString(id);
        return d == null ? "" : d;
    }

    /** body 加密 key（= safeCode，32 字节 → AES-256） */
    private static volatile String sSafeCode;

    public static String safeCode() {
        String v = sSafeCode;
        if (v == null) {
            v = sSafeCode = secure(SecurityCore.C_JZ_SAFE_CODE);
        }
        return v;
    }

    /** sig2/sig3 与 GET query 加密 key（= safeSecretKey，32 字节） */
    private static volatile String sSafeSecretKey;

    public static String safeSecretKey() {
        String v = sSafeSecretKey;
        if (v == null) {
            v = sSafeSecretKey = secure(SecurityCore.C_JZ_SAFE_SECRET_KEY);
        }
        return v;
    }

    /** getDecryptInfo 等价密钥（KEY1+KEY4+KEY6+MAX_ENCRYPTKEY+Md1khdaF） */
    private static volatile String sDecryptKey;

    private static String decryptKey() {
        String v = sDecryptKey;
        if (v == null) {
            v = sDecryptKey = secure(SecurityCore.C_JZ_DECRYPT_KEY);
        }
        return v;
    }

    /** OSS 域名池（原版启动时逐个 GET，取 enabled=true 的 domain；空项已过滤） */
    private static volatile String[] sOssUrls;

    public static String[] ossUrls() {
        String[] v = sOssUrls;
        if (v != null) return v;
        String[] all = {
                secure(SecurityCore.C_JZ_OSS_URL_1),
                secure(SecurityCore.C_JZ_OSS_URL_2),
                secure(SecurityCore.C_JZ_OSS_URL_3),
                secure(SecurityCore.C_JZ_OSS_URL_4),
                secure(SecurityCore.C_JZ_OSS_URL_5),
        };
        List<String> ok = new ArrayList<>();
        for (String s : all) {
            if (s != null && !s.isEmpty()) ok.add(s);
        }
        v = ok.toArray(new String[0]);
        sOssUrls = v;
        return v;
    }

    /** OSS 全部不可达时的兜底 baseUrl */
    private static volatile String sFallbackBaseUrl;

    public static String fallbackBaseUrl() {
        String v = sFallbackBaseUrl;
        if (v == null) {
            v = sFallbackBaseUrl = secure(SecurityCore.C_JZ_FALLBACK_BASE);
        }
        return v;
    }

    /** 原版版本信息（服务端按 versionCode 校 sig） */
    private static volatile String sVApp;

    public static String vApp() {
        String v = sVApp;
        if (v == null) {
            v = sVApp = secure(SecurityCore.C_JZ_V_APP);
        }
        return v;
    }

    private static volatile String sVName;

    public static String vName() {
        String v = sVName;
        if (v == null) {
            v = sVName = secure(SecurityCore.C_JZ_V_NAME);
        }
        return v;
    }

    private static volatile String sPkg;

    public static String pkg() {
        String v = sPkg;
        if (v == null) {
            v = sPkg = secure(SecurityCore.C_JZ_PKG);
        }
        return v;
    }

    private static volatile String sAppName;

    public static String appName() {
        String v = sAppName;
        if (v == null) {
            v = sAppName = secure(SecurityCore.C_JZ_APP_NAME);
        }
        return v;
    }

    /** firstLevelPublic 密文（原版 NetSecurityRequestManager.d() 里的字面量） */
    private static volatile String sFlpCipher;

    private static String firstLevelPublicCipher() {
        String v = sFlpCipher;
        if (v == null) {
            v = sFlpCipher = secure(SecurityCore.C_JZ_FLP_CIPHER);
        }
        return v;
    }

    /** secondLevelPublic 兜底值（实测与协商下发值逐字节一致） */
    private static volatile String sSlpFallback;

    private static String secondLevelPublicFallback() {
        String v = sSlpFallback;
        if (v == null) {
            v = sSlpFallback = secure(SecurityCore.C_JZ_SLP_FALLBACK);
        }
        return v;
    }

    // ---- 业务接口路径（与域名拼齐才是完整攻击面，故一并下沉） ----

    private static volatile String sPathSearch;

    public static String pathSearch() {
        String v = sPathSearch;
        if (v == null) {
            v = sPathSearch = secure(SecurityCore.C_JZ_PATH_SEARCH);
        }
        return v;
    }

    private static volatile String sPathDetail;

    public static String pathDetail() {
        String v = sPathDetail;
        if (v == null) {
            v = sPathDetail = secure(SecurityCore.C_JZ_PATH_DETAIL);
        }
        return v;
    }

    private static volatile String sPathPlayUrl;

    public static String pathPlayUrl() {
        String v = sPathPlayUrl;
        if (v == null) {
            v = sPathPlayUrl = secure(SecurityCore.C_JZ_PATH_PLAY_URL);
        }
        return v;
    }

    private static volatile String sPathRsaZone;

    public static String pathRsaZone() {
        String v = sPathRsaZone;
        if (v == null) {
            v = sPathRsaZone = secure(SecurityCore.C_JZ_PATH_RSA_ZONE);
        }
        return v;
    }

    private static volatile String sPathCategory;

    public static String pathCategory() {
        String v = sPathCategory;
        if (v == null) {
            v = sPathCategory = secure(SecurityCore.C_JZ_PATH_CATEGORY);
        }
        return v;
    }

    private static volatile String sPathLiveList;

    public static String pathLiveList() {
        String v = sPathLiveList;
        if (v == null) {
            v = sPathLiveList = secure(SecurityCore.C_JZ_PATH_LIVE_LIST);
        }
        return v;
    }

    private static volatile String sPathTagList;

    public static String pathTagList() {
        String v = sPathTagList;
        if (v == null) {
            v = sPathTagList = secure(SecurityCore.C_JZ_PATH_TAG_LIST);
        }
        return v;
    }

    /** 随机串字母表（原版 NetSecurityParamsHelper.d(int)）—— 非敏感，保留字面量 */
    private static final char[] TOKEN_CHARS =
            "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final SecureRandom SRANDOM = new SecureRandom();

    /**
     * 生成随机串：n-1 个不重复字母数字 + 末尾 '='（原版 d(int) 的等价实现）。
     */
    public static String token(int n) {
        if (n <= 1) return "=";
        java.util.Set<Character> set = new java.util.LinkedHashSet<>();
        while (set.size() < n - 1) {
            set.add(TOKEN_CHARS[SRANDOM.nextInt(TOKEN_CHARS.length)]);
        }
        StringBuilder sb = new StringBuilder(n);
        for (Character c : set) sb.append(c);
        return sb.append('=').toString();
    }

    // ------------------------------------------------------------------
    // AES / RSA
    // ------------------------------------------------------------------

    /** AES/ECB/PKCS7 加密 → Base64（<b>带 '=' 填充</b>，与原版 Base64.NO_WRAP 一致） */
    public static String aesEncryptBase64(String key, String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS7Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    /** AES/ECB/PKCS7 解密 Base64 密文 → 文本 */
    static String aesDecryptBase64(String key, String b64) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS7Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] out = c.doFinal(Base64.decode(b64, Base64.NO_WRAP));
        return new String(out, StandardCharsets.UTF_8);
    }

    /** RSA/ECB/PKCS1Padding 公钥加密 → Base64（原版 RsaUtils.a()） */
    public static String rsaEncryptBase64(String publicKeyB64, String plain) throws Exception {
        PublicKey key = parseRsaPublicKey(publicKeyB64);
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static PublicKey parseRsaPublicKey(String b64) throws Exception {
        byte[] der = Base64.decode(b64, Base64.NO_WRAP);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** firstLevelPublic（AES 解密固定密文，进程内缓存） */
    private static volatile String sFirstLevelPublic;

    static String firstLevelPublic() {
        String v = sFirstLevelPublic;
        if (v != null) return v;
        synchronized (JzProtocol.class) {
            if (sFirstLevelPublic == null) {
                try {
                    sFirstLevelPublic = aesDecryptBase64(decryptKey(), firstLevelPublicCipher());
                } catch (Throwable t) {
                    sFirstLevelPublic = "";
                }
            }
            return sFirstLevelPublic;
        }
    }

    /**
     * secondLevelPublic 当前生效值（Base64）。
     *
     * <p>⚠️ 兜底值**不能**在字段声明处直接赋值（那会进 {@code <clinit>}，
     * 首次触碰本类就可能触发 loadLibrary，可能卡住主线程）—— 改为首次访问时解密。
     */
    private static volatile String sSecondLevelPublicB64 = "";

    /** secondLevelPublic 是否已由服务端协商下发（仅用于日志/诊断） */
    private static volatile boolean sNegotiated = false;

    /** 当前生效的 secondLevelPublic（Base64）：协商下发值优先，否则兜底常量 */
    public static String secondLevelPublic() {
        String v = sSecondLevelPublicB64;
        if (v != null && !v.isEmpty()) return v;
        synchronized (JzProtocol.class) {
            if (sSecondLevelPublicB64 == null || sSecondLevelPublicB64.isEmpty()) {
                sSecondLevelPublicB64 = secondLevelPublicFallback();
            }
            return sSecondLevelPublicB64;
        }
    }

    /** 设置协商下发的 secondLevelPublic（RSAPublic 五段按 fakePos=3 拼装后传入） */
    public static void setSecondLevelPublic(String publicKeyB64) throws Exception {
        parseRsaPublicKey(publicKeyB64);   // 解析失败会抛异常，不污染现有值
        sSecondLevelPublicB64 = publicKeyB64;
        sNegotiated = true;
    }

    public static boolean isNegotiated() {
        return sNegotiated;
    }

    // ------------------------------------------------------------------
    // protobuf 编码
    // ------------------------------------------------------------------

    public static void writeVarint(java.io.ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    public static void writeVarintField(java.io.ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, (long) field << 3);
        writeVarint(out, value);
    }

    public static void writeStringField(java.io.ByteArrayOutputStream out, int field, String value) {
        writeVarint(out, ((long) field << 3) | 2);
        byte[] b = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        writeVarint(out, b.length);
        out.write(b, 0, b.length);
    }

    // ------------------------------------------------------------------
    // protobuf 解码
    // ------------------------------------------------------------------

    /** 读取某字段的全部 length-delimited 值（repeated 消息/字符串） */
    public static List<byte[]> messages(byte[] msg, int field) {
        List<byte[]> out = new ArrayList<>();
        if (msg == null || msg.length == 0) return out;
        int pos = 0;
        while (pos < msg.length) {
            int tagStart = pos;
            long tag = readVarint(msg, pos);
            pos += varintSize(tag);
            if (pos == tagStart) break;
            int fn = (int) (tag >>> 3);
            int wt = (int) (tag & 0x7);
            if (wt == 0) {
                pos += varintSize(readVarint(msg, pos));
            } else if (wt == 2) {
                long lenL = readVarint(msg, pos);
                pos += varintSize(lenL);
                int len = (int) lenL;
                if (len < 0 || pos + len > msg.length) break;
                if (fn == field) {
                    byte[] chunk = new byte[len];
                    System.arraycopy(msg, pos, chunk, 0, len);
                    out.add(chunk);
                }
                pos += len;
            } else if (wt == 5) {
                pos += 4;
            } else if (wt == 1) {
                pos += 8;
            } else {
                break;
            }
        }
        return out;
    }

    public static byte[] firstMessage(byte[] msg, int field) {
        List<byte[]> list = messages(msg, field);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 读字符串字段（第一个匹配）；缺失返回 "" */
    public static String str(byte[] msg, int field) {
        byte[] b = firstMessage(msg, field);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    /**
     * 【2026-10-23 修复5】读 map&lt;string,string&gt; 字段（如 ParsePlayUrlBean.headers = 6，
     * 橘汁服务端下发的防盗链播放请求头 UA/Referer）。
     *
     * <p>protobuf map 在线上的表现形式与 repeated message 相同：多个同 field 的长度分隔消息，
     * 每个消息内部 {@code key = 1}、{@code value = 2}（MapEntryLite 默认表）。
     */
    public static java.util.Map<String, String> mapStr(byte[] msg, int field) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (msg == null) return out;
        for (byte[] entry : messages(msg, field)) {
            if (entry == null || entry.length == 0) continue;
            String k = str(entry, 1);
            if (k.isEmpty()) continue;
            out.put(k, str(entry, 2));
        }
        return out;
    }

    /** 读 varint 字段；缺失返回 def */
    public static long num(byte[] msg, int field, long def) {
        if (msg == null) return def;
        int pos = 0;
        while (pos < msg.length) {
            int tagStart = pos;
            long tag = readVarint(msg, pos);
            pos += varintSize(tag);
            if (pos == tagStart) break;
            int fn = (int) (tag >>> 3);
            int wt = (int) (tag & 0x7);
            if (wt == 0) {
                long v = readVarint(msg, pos);
                pos += varintSize(v);
                if (fn == field) return v;
            } else if (wt == 2) {
                long lenL = readVarint(msg, pos);
                pos += varintSize(lenL);
                int len = (int) lenL;
                if (len < 0 || pos + len > msg.length) break;
                pos += len;
            } else if (wt == 5) {
                pos += 4;
            } else if (wt == 1) {
                pos += 8;
            } else {
                break;
            }
        }
        return def;
    }

    private static long readVarint(byte[] data, int pos) {
        long result = 0;
        int shift = 0;
        int p = pos;
        while (p < data.length) {
            byte b = data[p++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift >= 64) break;
        }
        return result;
    }

    private static int varintSize(long value) {
        int size = 1;
        long v = value >>> 7;
        while (v != 0) {
            v >>>= 7;
            size++;
        }
        return size;
    }
}
