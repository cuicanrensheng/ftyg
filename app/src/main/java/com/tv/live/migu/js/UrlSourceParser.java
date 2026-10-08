package com.tv.live.migu.js;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 解析编排器 —— 1:1 复刻畅享TV 的
 * {@code org.fungo.jsparser.Parser.parse(String, JsCallback)} + {@code Parser$1}。
 *
 * <p><b>两步求值协议</b>：
 * <pre>
 *   第 1 步：yt_source_root.js + "getParser('&lt;url&gt;');"
 *            → ["other_live.js", "miguLiveHdParsePlay"]      // 路由结果
 *
 *   第 2 步：other_live.js   + ";getParseResult('&lt;fn&gt;','&lt;url&gt;');"
 *            → ["http://.../index.m3u8?...&amp;ddCalcu=..."]      // 真实播放地址
 * </pre>
 *
 * <p>路由表（{@code RootFunction}）里咪咕相关共 5 条：
 * {@code miguhd://} / {@code migu://} + {@code migu1://} / {@code miguold://} /
 * {@code migu_sport://} / {@code miguNew://}。
 */
public final class UrlSourceParser {

    private static final String TAG = "MiguUrlSourceParser";

    /** 根路由插件名（原版硬编码在 Parser.parse 中） */
    private static final String ROOT_PLUGIN = "yt_source_root.js";

    /** 解析失败提示（与原版 Parser$1 的文案保持一致） */
    public static final String ERR_NO_PARSER = "no js parser";

    private UrlSourceParser() {}

    /** 解析回调 */
    public interface Callback {
        /** @param urls 解析出的地址列表（首项即可播地址） */
        void onSuccess(List<String> urls);
        void onError(String message);
    }

    // ------------------------------------------------------------------
    // 异步入口
    // ------------------------------------------------------------------

    /**
     * 解析任意自定义协议地址（如 {@code miguhd://608807420}）为真实可播地址。
     * 内部自动初始化 JS 引擎，可在后台线程调用。
     */
    public static void parse(final Context ctx, final String url, final Callback cb) {
        if (ctx == null || TextUtils.isEmpty(url)) {
            cb.onError("illegal argument");
            return;
        }
        JsEngine engine = JsEngine.get();
        engine.init(ctx);
        if (!engine.isAvailable()) {
            cb.onError("js engine unavailable");
            return;
        }

        // 第 1 步：路由
        final String rootJs = JsPluginStore.getPlugin(ctx, ROOT_PLUGIN);
        if (TextUtils.isEmpty(rootJs)) {
            cb.onError("no root js");
            return;
        }
        String code1 = rootJs + ";getParser('" + escapeJs(url) + "');";
        engine.evaluate(code1, new JsEngine.ResultCallback() {
            @Override
            public void onResult(String routeResult) {
                List<String> route = parseStringArray(routeResult);
                if (route.size() < 2) {
                    LogBridge.w(TAG, "路由失败：" + routeResult);
                    cb.onError(ERR_NO_PARSER);
                    return;
                }
                final String pluginName = route.get(0);
                String fnName = route.get(1);
                LogBridge.i(TAG, "路由：" + url + " → " + pluginName + "::" + fnName);

                // 第 2 步：执行具体解析函数
                final String pluginJs = JsPluginStore.getPlugin(ctx, pluginName);
                if (TextUtils.isEmpty(pluginJs)) {
                    cb.onError("plugin not found: " + pluginName);
                    return;
                }
                String code2 = pluginJs
                        + ";getParseResult('" + escapeJs(fnName) + "','" + escapeJs(url) + "');";
                JsEngine.get().evaluate(code2, new JsEngine.ResultCallback() {
                    @Override
                    public void onResult(String result) {
                        List<String> urls = parseStringArray(result);
                        urls.removeAll(Collections.singleton(""));
                        if (urls.isEmpty()) {
                            LogBridge.w(TAG, "解析结果为空（" + pluginName + "）");
                            cb.onError("empty parse result");
                            return;
                        }
                        LogBridge.i(TAG, "解析成功：" + url + " → " + abbreviate(urls.get(0)));
                        cb.onSuccess(urls);
                    }

                    @Override
                    public void onError(String message) {
                        cb.onError(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                cb.onError(message);
            }
        });
    }

    // ------------------------------------------------------------------
    // 同步入口（供 TVPlayerManager 在后台线程直接调用）
    // ------------------------------------------------------------------

    /**
     * 同步解析，内部等待回调完成。
     *
     * @param timeoutMs 整体超时；超时返回 null
     * @return 首个可播地址；失败/超时返回 null
     */
    public static String parseSync(Context ctx, String url, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> holder = new ArrayList<>(1);
        parse(ctx, url, new Callback() {
            @Override
            public void onSuccess(List<String> urls) {
                if (!urls.isEmpty()) holder.add(urls.get(0));
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                LogBridge.w(TAG, "同步解析超时：" + url);
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return holder.isEmpty() ? null : holder.get(0);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析 {@code ["a","b"]} 形式的 JSON 字符串数组 */
    public static List<String> parseStringArray(String json) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return out;
        String s = json.trim();
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                out.add(v);
            }
            return out;
        } catch (Throwable t) {
            // 容错：可能是单个字符串或非数组
            if (!s.startsWith("[")) out.add(s);
            return out;
        }
    }

    /**
     * JS 单引号字符串转义。
     *
     * <p>原版直接拼接不做转义；这里补上转义，避免 URL 含 {@code '} 时截断 JS 语句。
     * 对 {@code miguhd://608807420} 这类地址，转义前后完全等价。
     */
    static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", "");
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    /** 供调试：列出协议会路由到哪个插件（不执行解析） */
    public static List<String> describeRoute(Context ctx, String url) {
        List<String> out = new ArrayList<>();
        String rootJs = JsPluginStore.getPlugin(ctx, ROOT_PLUGIN);
        if (TextUtils.isEmpty(rootJs)) return out;
        List<String> route = parseSyncRoute(ctx, rootJs, url);
        return route == null ? out : route;
    }

    private static List<String> parseSyncRoute(Context ctx, String rootJs, String url) {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> holder = new ArrayList<>(Arrays.asList("", ""));
        JsEngine.get().init(ctx);
        JsEngine.get().evaluate(rootJs + ";getParser('" + escapeJs(url) + "');",
                new JsEngine.ResultCallback() {
                    @Override
                    public void onResult(String value) {
                        List<String> r = parseStringArray(value);
                        if (r.size() >= 2) {
                            holder.set(0, r.get(0));
                            holder.set(1, r.get(1));
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        latch.countDown();
                    }
                });
        try {
            if (!latch.await(20_000L, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return holder;
    }
}
