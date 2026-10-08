package com.tv.live.migu.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Response;

/**
 * JS 插件库 —— 1:1 对应畅享TV 的 {@code org.fungo.jsparser.Parser} 中的
 * {@code getParseJs / getJs / getDecryptJs} 三件套。
 *
 * <p><b>查找顺序（与原版一致）</b>：
 * <pre>
 *   ① 内存缓存（jsMap）
 *   ② files/js/&lt;name&gt;  —— 热更产物，明文
 *   ③ assets/js/&lt;name&gt; —— 内置插件
 * </pre>
 *
 * <p><b>与原版的一处有意差异</b>：原版 assets 里放<b>密文</b>、运行时解密；本移植版放<b>明文</b>。
 * 密文是 Base64，几乎压不动（deflate 后 166KB），换成明文后只需 61KB ——
 * 同样功能省下约 <b>105KB</b> 安装包体积。两种格式都能被 {@link #looksLikePlainJs} 自动识别，
 * 所以即使换回原版密文资源也能正常工作。
 *
 * <p><b>热更（默认关闭）</b>：原版会从 {@code nowtv.xiaoyouzb.net/list_android_js_meta_v2.php}
 * 拉取插件更新。本移植版把它收在 {@link #sUpdateEnabled} 开关之后，<b>默认 false</b>，
 * 即插件只从 assets 读取 —— 安全、离线可用、不产生任何额外网络请求。
 * 需要时把它设为 true 并在启动流程调用 {@link #updateIfEnabled}。
 */
public final class JsPluginStore {

    private static final String TAG = "MiguJsPlugin";

    /** assets 目录前缀 */
    private static final String ASSET_DIR = "js/";
    /** 应用私有目录下的插件目录名 */
    private static final String LOCAL_DIR = "js";

    /** 热更元数据接口（仅在 {@link #sUpdateEnabled} 为 true 时才会访问） */
    private static final String UPDATE_META_URL =
            "http://nowtv.xiaoyouzb.net/list_android_js_meta_v2.php?version=";
    /** 客户端版本号，用于向服务端声明自身版本 */
    private static final String CLIENT_JS_VERSION = "4.1.6";

    /** 已解密插件缓存（name → 明文 JS） */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /**
     * ★ 热更开关：<b>默认关闭</b>。
     *
     * <p>关闭时：插件只从 assets 读，不请求 xiaoyouzb，也不在 WebView 执行远程代码。
     * 打开时：会调用 {@link #updateIfEnabled} 拉取更新，仅覆盖<b>本地已存在</b>的插件。
     */
    public static volatile boolean sUpdateEnabled = false;

    private JsPluginStore() {}

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 取插件明文 JS。
     *
     * @param name 插件文件名，如 {@code yt_source_root.js}
     * @return 明文 JS；找不到返回空串
     */
    public static String getPlugin(Context ctx, String name) {
        if (ctx == null || TextUtils.isEmpty(name)) return "";

        String cached = CACHE.get(name);
        if (cached != null) return cached;

        // ② 热更产物（明文）
        String js = readLocalPlain(ctx, name);
        boolean fromLocal = !TextUtils.isEmpty(js);

        // ③ 内置插件：本项目按「明文」放置（省约 105KB 体积）；
        //    若换成原版的密文格式，这里会自动识别并解密。
        if (!fromLocal) {
            String asset = JsEngine.readAssetText(ctx, ASSET_DIR + name);
            if (!TextUtils.isEmpty(asset)) {
                js = looksLikePlainJs(asset) ? asset : JsDecrypt.decrypt(asset);
            }
        }

        if (TextUtils.isEmpty(js)) {
            LogBridge.w(TAG, "插件不可用：" + name);
            return "";
        }
        if (fromLocal) {
            LogBridge.i(TAG, "插件命中热更版本：" + name + "（" + js.length() + " 字符）");
        }
        CACHE.put(name, js);
        return js;
    }

    /** 清掉内存缓存（热更落地后调用） */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 粗略判断 assets 里放的是明文 JS 还是原版的密文。
     *
     * <p>密文是纯 Base64 字符集（{@code A-Za-z0-9+/=}），不含大括号、分号与空格；
     * 明文 JS 必然包含 {@code function} 或 {@code {} 或 {@code ;}。
     */
    private static boolean looksLikePlainJs(String s) {
        if (TextUtils.isEmpty(s)) return false;
        return s.contains("function") || s.contains("{") || s.contains(";");
    }

    private static String readLocalPlain(Context ctx, String name) {
        File f = new File(localDir(ctx), name);
        if (!f.exists() || f.length() <= 0) return "";
        try {
            return new String(readAll(f), "UTF-8");
        } catch (Throwable t) {
            LogBridge.w(TAG, "读本地插件失败：" + name + " " + t.getMessage());
            return "";
        }
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return buf;
        } finally {
            try { fis.close(); } catch (Throwable ignored) {}
        }
    }

    /** 应用私有插件目录 files/js */
    public static File localDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), LOCAL_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 写本地插件文件（供 {@link JsBridge#writeFile} 与热更共用） */
    public static boolean writeLocalFile(Context ctx, String fileName, String content) {
        if (ctx == null || TextUtils.isEmpty(fileName)) return false;
        FileOutputStream fos = null;
        try {
            File out = new File(localDir(ctx), fileName);
            fos = new FileOutputStream(out);
            fos.write((content == null ? "" : content).getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Throwable t) {
            LogBridge.w(TAG, "写插件文件失败：" + fileName + " " + t.getMessage());
            return false;
        } finally {
            if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
        }
    }

    /** 读 assets 文本（转发给 {@link JsEngine}，避免重复实现） */
    public static String readAsset(Context ctx, String path) {
        return JsEngine.readAssetText(ctx, path);
    }

    // ------------------------------------------------------------------
    // 热更（默认关闭）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 热更（默认关闭；开启后「按 md5 对齐」做增量，见 updateInternal）
    // ------------------------------------------------------------------

    /** 内置基线清单（assets 内）：记录「内置插件所对应的线上密文 md5」 */
    private static final String BASELINE_ASSET = ASSET_DIR + "js_meta_baseline.json";
    /** 本地热更状态文件（落在 files/js/js_meta_state.json） */
    private static final String STATE_FILE = "js_meta_state.json";
    /** 热更开关的持久化键（SharedPreferences: app_settings） */
    private static final String PREF_KEY_JS_UPDATE = "js_plugin_hot_update";
    /** 两次热更检查的最小间隔：24 小时（开关常开也不会频繁请求） */
    private static final long UPDATE_MIN_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }

    /** 从 SharedPreferences 读取热更开关（默认关）。App 启动时调用一次即可。 */
    public static void init(Context ctx) {
        if (ctx == null) return;
        try {
            sUpdateEnabled = prefs(ctx).getBoolean(PREF_KEY_JS_UPDATE, false);
        } catch (Throwable t) {
            LogBridge.w(TAG, "读取热更开关失败：" + t.getMessage());
        }
    }

    /** 设置并持久化热更开关（供设置面板使用） */
    public static void setUpdateEnabled(Context ctx, boolean enabled) {
        sUpdateEnabled = enabled;
        if (ctx == null) return;
        try {
            prefs(ctx).edit().putBoolean(PREF_KEY_JS_UPDATE, enabled).apply();
        } catch (Throwable t) {
            LogBridge.w(TAG, "写入热更开关失败：" + t.getMessage());
        }
    }

    /**
     * 按开关 + 24h 节流决定是否执行插件热更。
     *
     * <p><b>默认 {@link #sUpdateEnabled} 为 false，直接返回</b>（不发任何请求）。
     * 开关为 true 时：① 拉元数据清单（单次 GET）；② 与本地基准逐条 md5 对齐，
     * <b>一致即零下载</b>；③ 仅对<b>变更</b>且<b>本地已打包</b>的插件下载覆盖；
     * ④ 全程单线程串行 + 300ms 限速，不并发、不重试、不枚举。
     */
    public static void updateIfEnabled(Context ctx) {
        if (ctx == null) return;
        if (!sUpdateEnabled) {
            init(ctx);                          // 进程内未显式设置时再读一次持久化开关
            if (!sUpdateEnabled) return;
        }
        long last = lastCheckAt(ctx);
        if (last > 0 && System.currentTimeMillis() - last < UPDATE_MIN_INTERVAL_MS) {
            LogBridge.i(TAG, "热更检查间隔未到（<24h），跳过");
            return;
        }
        // 先落时间戳，避免异常/并发导致的重复请求
        writeState(ctx, readStateFiles(ctx), System.currentTimeMillis());
        try {
            updateInternal(ctx);
        } catch (Throwable t) {
            LogBridge.w(TAG, "插件热更异常：" + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 增量热更主体 —— <b>按 md5 对齐</b>。
     *
     * <p><b>md5 语义（已用原版实机抓包验证）</b>：元数据里 {@code md5} = 服务端下发的
     * {@code encrypt_<name>.js} <b>密文文件</b>的 md5（原版设备 21 个插件的密文、
     * 以及 {@code assets/js/*.js} 明文对应的密文，md5 逐条一致）。
     *
     * <p><b>本地基准</b>取两级：① 热更状态里的记录（上次确认过的远端 md5）；
     * ② 内置基线清单 {@code assets/js/js_meta_baseline.json}。因为内置 assets 放的是
     * <b>明文</b>（省约 105KB 体积），无法直接从明文算出密文 md5，故在打包时把
     * 「内置版本对应的线上密文 md5」固化成清单 —— 首次启动即可判定"内置即最新"，
     * 从而<b>零下载</b>；之后仅在服务端真更新时才下载变更的那几个插件。
     *
     * <p>下载后会校验密文 md5，不符直接丢弃（不落盘、不执行）。
     */
    private static void updateInternal(Context ctx) {
        String meta = httpGet(UPDATE_META_URL + CLIENT_JS_VERSION);
        if (TextUtils.isEmpty(meta)) return;

        JSONObject root;
        try {
            root = new JSONObject(meta);
        } catch (Throwable t) {
            LogBridge.w(TAG, "插件元数据不是合法 JSON：" + t.getMessage());
            return;
        }
        JSONArray data = root.optJSONArray("data");
        if (data == null) return;

        Map<String, String> baseline = readBaseline(ctx);   // 内置插件对应的线上密文 md5
        Map<String, String> state = readStateFiles(ctx);    // 上次确认过的线上密文 md5

        int updated = 0, skipped = 0, absent = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "");
            String remoteMd5 = item.optString("md5", "").trim();
            if (url.isEmpty()) continue;

            // 远端文件名形如 encrypt_other_live.js → 归一化为 other_live.js
            String remoteName = url.substring(url.lastIndexOf('/') + 1);
            String name = remoteName.startsWith("encrypt_")
                    ? remoteName.substring("encrypt_".length()) : remoteName;

            // 只更新「本地已打包」的插件，不新增 —— 避免体积与攻击面失控
            if (TextUtils.isEmpty(JsEngine.readAssetText(ctx, ASSET_DIR + name))) {
                absent++;
                continue;
            }

            // ★ md5 对齐：本地基准 = 热更记录优先，其次内置基线清单
            String localBase = state.get(name);
            if (TextUtils.isEmpty(localBase)) localBase = baseline.get(name);
            if (!TextUtils.isEmpty(remoteMd5) && remoteMd5.equalsIgnoreCase(localBase)) {
                state.put(name, remoteMd5);
                skipped++;
                continue;                                   // ★ 已是最新 → 零下载
            }

            byte[] raw = httpGetBytes(url);
            if (raw == null || raw.length == 0) continue;
            if (!TextUtils.isEmpty(remoteMd5) && !remoteMd5.equalsIgnoreCase(md5Hex(raw))) {
                LogBridge.w(TAG, "热更下载内容 md5 不符，已丢弃：" + name);
                continue;
            }
            String plain = JsDecrypt.decrypt(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            if (TextUtils.isEmpty(plain)) {
                LogBridge.w(TAG, "热更解密失败：" + name);
                continue;
            }
            if (writeLocalFile(ctx, name, plain)) {
                state.put(name, remoteMd5);
                updated++;
                LogBridge.i(TAG, "插件已热更：" + name);
            }
            // 串行限速，避免给第三方服务造成压力
            sleepQuietly(300L);
        }
        writeState(ctx, state, System.currentTimeMillis());
        clearCache();
        LogBridge.i(TAG, "插件热更完成：更新 " + updated + "，跳过(已最新) " + skipped
                + "，未打包 " + absent);
    }

    /** 字节数组 → 16 进制小写 md5（与元数据清单同口径：对「下载到的密文文件字节」求 md5） */
    private static String md5Hex(byte[] data) {
        if (data == null) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit((b & 0xF), 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** GET 原始字节 —— 热更必须用「下载到的密文文件字节」算 md5，不能先转文本再编码 */
    private static byte[] httpGetBytes(String url) {
        try (Response resp = NetUtil.getInstance().syncGet(url)) {
            if (resp == null || !resp.isSuccessful() || resp.body() == null) {
                LogBridge.w(TAG, "热更 GET 失败 code=" + (resp == null ? -1 : resp.code()));
                return null;
            }
            return resp.body().bytes();
        } catch (Throwable t) {
            LogBridge.w(TAG, "热更 GET 异常：" + url + " " + t.getMessage());
            return null;
        }
    }

    // ---- 基线清单 / 热更状态 ----

    /** 读内置基线清单（{@code assets/js/js_meta_baseline.json} → files{name: 密文 md5}） */
    private static Map<String, String> readBaseline(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ctx == null) return out;
        copyStringMap(readJsonText(JsEngine.readAssetText(ctx, BASELINE_ASSET)), out);
        return out;
    }

    /** 读热更状态里的 files{name: 远端密文 md5} */
    private static Map<String, String> readStateFiles(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ctx == null) return out;
        copyStringMap(readJsonText(readFileText(new File(localDir(ctx), STATE_FILE))), out);
        return out;
    }

    /** 上次热更检查时刻（0 = 从未检查过） */
    private static long lastCheckAt(Context ctx) {
        if (ctx == null) return 0L;
        JSONObject o = readJsonText(readFileText(new File(localDir(ctx), STATE_FILE)));
        return o == null ? 0L : o.optLong("checkedAt", 0L);
    }

    /** 写热更状态（checkedAt + files{name: md5}） */
    private static void writeState(Context ctx, Map<String, String> files, long checkedAt) {
        if (ctx == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("checkedAt", checkedAt);
            JSONObject f = new JSONObject();
            if (files != null) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    f.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
            o.put("files", f);
            writeLocalFile(ctx, STATE_FILE, o.toString());
        } catch (Throwable t) {
            LogBridge.w(TAG, "写热更状态失败：" + t.getMessage());
        }
    }

    /** 把 {@code {"files":{k:v}}} 里的键值拷进 map */
    private static void copyStringMap(JSONObject root, Map<String, String> out) {
        if (root == null || out == null) return;
        JSONObject f = root.optJSONObject("files");
        if (f == null) return;
        for (java.util.Iterator<String> it = f.keys(); it.hasNext(); ) {
            String k = it.next();
            String v = f.optString(k, "").trim();
            if (!TextUtils.isEmpty(v)) out.put(k, v);
        }
    }

    private static JSONObject readJsonText(String text) {
        if (TextUtils.isEmpty(text)) return null;
        try {
            return new JSONObject(text.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFileText(File f) {
        if (f == null || !f.exists() || f.length() <= 0) return "";
        try {
            return new String(readAll(f), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String httpGet(String url) {
        try (Response resp = NetUtil.getInstance().syncGet(url)) {
            if (resp == null || !resp.isSuccessful() || resp.body() == null) return "";
            return resp.body().string();
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET 失败：" + url);
            return "";
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
