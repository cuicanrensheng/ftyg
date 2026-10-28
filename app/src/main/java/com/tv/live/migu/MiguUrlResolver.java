package com.tv.live.migu;

import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import okhttp3.Response;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内置源6（咪咕）取流解析器 —— 高清/安卓线路（{@code miguhd://<pID>}）。
 *
 * <p>算法来源：畅享TV（org.fungo.fungobox）运行时 JS 插件 {@code other_live.js}
 * 的 {@code miguLiveHdParsePlay()}，由 smali 逆向 + 设备端解密 JS + tcpdump 抓包
 * 三重验证（签名与抓包逐字节一致）。此处为 1:1 Java 移植，**不做任何"优化"**，
 * 以免破坏与咪咕服务端的校验约定。
 *
 * <p>链路：
 * <pre>
 *   GET https://play.miguvideo.com/playurl/v1/play/playurl
 *       ?sign=..&amp;rateType=3&amp;contId=&lt;pid&gt;&amp;timestamp=..&amp;salt=..
 *       &amp;flvEnable=false&amp;super4k=false
 *     → body.urlInfo.url（带 puData 的 HLS 地址）
 *     → 追加 ddCalcu 二次签名 → 最终可播放 m3u8
 * </pre>
 *
 * <p><b>签名构成</b>：
 * <pre>
 *   md5   = MD5(timestamp + pid + appVersion[0:8])
 *   salt  = 6 位随机数左补零 + "25"
 *   sign  = MD5(md5 + "2cac4f2c6c3346a5b34e085725ef7e33migu" + salt[0:4])
 * </pre>
 *
 * <p><b>ddCalcu</b>：把 puData 从两端向中间逐字节交错重排，并在固定轮次插入
 * {@code v} / {@code keys[dateStr[2]]} / {@code keys[programId[6]]} / {@code a}
 * （{@code keys = "cdabyzwxkl"}），结尾拼 {@code &amp;sv=10004&amp;ct=android}。
 *
 * <p><b>时效性</b>：返回地址带 {@code SecurityKey}/{@code puData} 短时签名，缓存久了会失效，
 * 因此本类**不做任何缓存**，每次起播都重新解析（见 TVPlayerManager#doPlay 惰性解析）。
 */
public final class MiguUrlResolver {

    private static final String TAG = "MiguUrlResolver";

    /** 自定义协议前缀：M3U 中咪咕频道地址写作 {@code miguhd://<pID>} */
    public static final String SCHEME = "miguhd://";

    /**
     * M3U 中写成 {@code tvg-id="migu:<pID>"}，解析后
     * {@link com.tv.live.Channel#getChannelId()} 即为 {@code migu:<pID>}。
     * 播放失败时据此识别「本频道属于源6」并实时重解析（见 TVPlayerManager#tryRefreshMiguSource）。
     */
    public static final String TVG_PREFIX = "migu:";

    /** 客户端版本号（咪咕侧校验用，勿随意改动） */
    private static final String APP_VERSION = "2600034600";
    /** X-UP-CLIENT-CHANNEL-ID：appVersion + 固定终端后缀 */
    private static final String APP_VERSION_ID = APP_VERSION + "-99000-201600010010028";
    /** 签名固定盐（咪咕客户端硬编码在 JS 内） */
    private static final String SIGN_SALT = "2cac4f2c6c3346a5b34e085725ef7e33migu";
    private static final String PLAYURL_API = "https://play.miguvideo.com/playurl/v1/play/playurl";
    /** ddCalcu 取值表 */
    private static final String DD_KEYS = "cdabyzwxkl";

    /**
     * 原版 {@code yt_source_root.js} 路由表里、本移植版<b>已打包插件</b>能解析的协议。
     *
     * <p>服务端下发的 {@code sourceLabels[].source} 并不总是 {@code miguhd://}：
     * 实测 tvId=43（湖南卫视）下发的是 {@code ysp://shd//600002475//2024054803}
     * （路由到 {@code ysp_live.js::extract}）和一条 CDN 直链。
     * 因此选源时不能只认 {@code migu} 前缀，否则大片卫视频道会「无源可播」。
     */
    private static final String[] JS_ROUTABLE_SCHEMES = {
            "miguhd://", "migu://", "migu1://", "miguold://",
            "migu_sport://", "miguNew://",
            "ysp://", "ysp1://", "ysp2://",
    };

    private MiguUrlResolver() {}

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 咪咕系自定义协议前缀全集。
     *
     * <p>实测（2026-10-06）服务端 {@code sourceLabels[].source} 会下发多种咪咕前缀，
     * 且同一频道的 {@code miguhd://} 与 {@code migu://} 用的是<b>同一个 pID</b>
     * （如 CCTV-1 的 {@code miguhd://608807420} / {@code migu://608807420}）。
     * 早期只认 {@code miguhd://}，导致 {@code migu://} 在 JS 引擎失败时报
     * 「非 migu* 协议且 JS 不可用，无法降级」—— 明明可降级却没有降级。
     */
    private static final String[] MIGU_SCHEMES = {
            "miguhd://", "migu://", "migu1://", "miguold://",
            "migu_sport://", "miguNew://",
    };

    /** 其中「参数即咪咕 pID、可走 playurl 接口降级」的那几个 */
    private static final String[] MIGU_PID_SCHEMES = {
            "miguhd://", "migu://", "migu1://", "miguold://",
    };

    /** 是否为咪咕系自定义协议地址（{@link #MIGU_SCHEMES} 之一） */
    public static boolean isMiguUrl(String url) {
        return matchedScheme(url, MIGU_SCHEMES) != null;
    }

    private static String matchedScheme(String url, String[] schemes) {
        if (url == null) return null;
        for (String s : schemes) {
            if (url.startsWith(s)) return s;
        }
        return null;
    }

    /** 是否为 http/https 直链（服务端偶尔直接下发 CDN 地址） */
    public static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 该 http(s) 地址是否「看起来是媒体流」。
     *
     * <p>【2026-10-07 提取】原为 {@code NowTvSourceApi.looksLikeStream}（私有），
     * 因「线路 / 清晰度」轴与解析链都要用同一判定，提到这里做公共口径。
     *
     * <p>服务端偶尔把 {@code http://www.le.com} 这类<b>网页</b>塞进 sourceLabels
     * （对应原版 {@code videoHtml} → 交 JS 引擎的 {@code letv_vod.js} 解析），
     * 它既不是线路也不是清晰度，直接丢给播放器必然失败。按扩展名/路径特征区分；
     * {@code .m3u8} 类大多带 query，故先取 '?' 前的部分判断。
     */
    public static boolean isStreamLike(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String s = url.toLowerCase(Locale.ROOT);
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        return s.endsWith(".m3u8") || s.endsWith(".m3u")
                || s.endsWith(".mp4") || s.endsWith(".flv")
                || s.endsWith(".ts") || s.endsWith(".m4s")
                || s.endsWith(".mpd") || s.endsWith(".mkv")
                || s.endsWith(".avi") || s.contains(".m3u8/")
                || s.contains("/index.m3u8") || s.contains("_web.m3u8");
    }

    /**
     * 是否为「网页类」源：http(s) 但不像媒体流。
     *
     * <p>典型 = {@code http://www.le.com}（原版 {@code get_channel_source} 会下发它）。
     * 原版把它保留为一条<b>可选线路</b>（手动切到它才用），不参与自动选源；
     * 选中后交 JS 路由表 —— {@code yt_source_root.js} 里 {@code .le.com} 命中
     * {@code letv_vod.js::letvVodParsePlay}。
     */
    public static boolean isWebPageUrl(String url) {
        return isHttpUrl(url) && !isStreamLike(url);
    }

    /** 该地址能否交给 JS 路由表解析（{@link #JS_ROUTABLE_SCHEMES} 之一） */
    public static boolean isJsRoutableUrl(String url) {
        if (url == null) return false;
        for (String s : JS_ROUTABLE_SCHEMES) {
            if (url.startsWith(s)) return true;
        }
        return false;
    }

    /** 从 {@code migu*://<pID>} 中取出 pID；非咪咕 / 无 pID（如 migu_sport）返回 null */
    public static String pidOf(String url) {
        String scheme = matchedScheme(url, MIGU_PID_SCHEMES);
        if (scheme == null) return null;
        String pid = url.substring(scheme.length()).trim();
        return pid.isEmpty() ? null : pid;
    }

    /**
     * 解析咪咕协议地址为可直接播放的 m3u8 地址。
     *
     * @param miguUrl {@code miguhd://<pID>}
     * @return 最终 m3u8（含 ddCalcu 签名）；失败返回 {@code null}
     */
    public static String resolve(String miguUrl) {
        String pid = pidOf(miguUrl);
        if (pid == null) {
            LogBridge.w(TAG, "非法咪咕地址：" + miguUrl);
            return null;
        }
        return resolvePid(pid);
    }

    /**
     * 按 pID 解析播放地址。
     *
     * @param pid 咪咕直播节目 ID，如 {@code 608807420}
     * @return 最终 m3u8；失败返回 {@code null}
     */
    public static String resolvePid(String pid) {
        if (TextUtils.isEmpty(pid)) return null;
        try {
            // ---- 1) 计算 sign ----
            long nowMs = System.currentTimeMillis();

            // JS: Math.round(Date.now()).toString()
            String timestamp = Long.toString(nowMs);

            // JS: getMD5(Date.now().toString())  ← 另取一次时间戳（与上者在毫秒级通常相同）
            String clientId = md5Hex(Long.toString(System.currentTimeMillis()));

            // JS: getMD5(timestramp + pid + appVersion.substring(0, 8))
            String base = timestamp + pid + APP_VERSION.substring(0, 8);
            String md5 = md5Hex(base);

            // JS: ("000000" + randomNum).slice(-6) + "25"
            int randomNum = (int) (Math.random() * 1000000);
            String salt = padLeft6(randomNum) + "25";

            // JS: "2cac...migu" + salt.substring(0, 4)
            String suffix = SIGN_SALT + salt.substring(0, 4);
            String sign = md5Hex(md5 + suffix);

            String requestUrl = PLAYURL_API
                    + "?sign=" + sign
                    + "&rateType=3"
                    + "&contId=" + pid
                    + "&timestamp=" + timestamp
                    + "&salt=" + salt
                    + "&flvEnable=false"
                    + "&super4k=false";

            Map<String, String> headers = new HashMap<>();
            headers.put("AppVersion", APP_VERSION);
            headers.put("TerminalId", "android");
            headers.put("X-UP-CLIENT-CHANNEL-ID", APP_VERSION_ID);
            headers.put("ClientId", clientId);
            headers.put("appCode", "miguvideo_default_android");

            // ---- 2) 取带 puData 的 HLS 地址 ----
            String respText;
            try (Response resp = NetUtil.getInstance().syncGetWithHeaders(requestUrl, headers)) {
                if (resp == null || !resp.isSuccessful() || resp.body() == null) {
                    LogBridge.w(TAG, "playurl 请求失败 pid=" + pid
                            + " code=" + (resp == null ? -1 : resp.code()));
                    return null;
                }
                respText = resp.body().string();
            }
            if (TextUtils.isEmpty(respText)) {
                LogBridge.i(TAG, "playurl 响应为空 pid=" + pid);
                return null;
            }

            JSONObject json = new JSONObject(respText);
            JSONObject body = json.optJSONObject("body");
            JSONObject urlInfo = body == null ? null : body.optJSONObject("urlInfo");
            String rawUrl = urlInfo == null ? "" : urlInfo.optString("url", "");
            if (TextUtils.isEmpty(rawUrl)) {
                LogBridge.w(TAG, "playurl 未返回 url pid=" + pid
                        + " msg=" + json.optString("message", ""));
                return null;
            }

            // ---- 3) ddCalcu 二次签名 ----
            String finalUrl = withDdCalcu(rawUrl, pid, todayStr());
            if (TextUtils.isEmpty(finalUrl)) {
                LogBridge.w(TAG, "ddCalcu 计算失败 pid=" + pid);
                return null;
            }
            return finalUrl;

        } catch (Throwable t) {
            LogBridge.w(TAG, "解析异常 pid=" + pid + " : " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ddCalcu
    // ------------------------------------------------------------------

    /**
     * 给带 {@code puData} 的地址追加 ddCalcu 签名。
     *
     * @param url       原始地址（含 {@code &puData=xxx}）
     * @param programId 节目 ID（= pID）
     * @param dateStr   {@code YYYYMMDD}
     */
    static String withDdCalcu(String url, String programId, String dateStr) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(programId)) return "";
        int idx = url.indexOf("&puData=");
        if (idx < 0) {
            // JS 在无 puData 时返回空串（getddCalcu720p 的 !puData 分支）
            LogBridge.w(TAG, "地址缺少 puData，无法签名：" + url.substring(0, Math.min(90, url.length())));
            return "";
        }
        String rest = url.substring(idx + "&puData=".length());
        // JS 里 puData 恒为最后一个参数；这里额外按 '&' 截断，兼容参数顺序变化
        int amp = rest.indexOf('&');
        String puData = amp >= 0 ? rest.substring(0, amp) : rest;
        if (puData.isEmpty()) return "";

        String ddCalcu = ddCalcu(puData, programId, dateStr);
        if (ddCalcu.isEmpty()) return "";
        return url + "&ddCalcu=" + ddCalcu + "&sv=10004&ct=android";
    }

    /**
     * ddCalcu 核心算法（1:1 对应 JS {@code getddCalcu720p}）。
     *
     * <pre>
     *   halfLen = len/2
     *   for i in [0, halfLen):
     *     push puData[len-1-i]; push puData[i]
     *     i==1 → push 'v'
     *     i==2 → push keys[dateStr[2]]
     *     i==3 → push keys[programId[6]]
     *     i==4 → push 'a'
     * </pre>
     */
    static String ddCalcu(String puData, String programId, String dateStr) {
        if (TextUtils.isEmpty(puData) || TextUtils.isEmpty(programId)) return "";
        if (dateStr == null || dateStr.length() < 3) return "";
        if (programId.length() < 7) return "";

        int len = puData.length();
        int halfLen = len / 2;
        StringBuilder sb = new StringBuilder(halfLen * 2 + 4);

        try {
            for (int i = 0; i < halfLen; i++) {
                sb.append(puData.charAt(len - 1 - i));
                sb.append(puData.charAt(i));
                switch (i) {
                    case 1:
                        sb.append('v');
                        break;
                    case 2: {
                        int keyIdx = dateStr.charAt(2) - '0';
                        if (keyIdx < 0 || keyIdx >= DD_KEYS.length()) return "";
                        sb.append(DD_KEYS.charAt(keyIdx));
                        break;
                    }
                    case 3: {
                        int keyIdx = programId.charAt(6) - '0';
                        if (keyIdx < 0 || keyIdx >= DD_KEYS.length()) return "";
                        sb.append(DD_KEYS.charAt(keyIdx));
                        break;
                    }
                    case 4:
                        sb.append('a');
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "ddCalcu 计算异常：" + t.getMessage());
            return "";
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** JS {@code ("000000" + n).slice(-6)} */
    private static String padLeft6(int n) {
        String s = Integer.toString(n);
        while (s.length() < 6) s = "0" + s;
        if (s.length() > 6) s = s.substring(s.length() - 6);
        return s;
    }

    private static String todayStr() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** 标准 MD5 十六进制小写（等价 JS getMD5） */
    static String md5Hex(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            LogBridge.w(TAG, "MD5 计算失败：" + t.getMessage());
            return "";
        }
    }
}
