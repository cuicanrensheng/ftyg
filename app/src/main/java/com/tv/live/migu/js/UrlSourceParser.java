package com.tv.live.migu.js;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;

import org.json.JSONArray;

import java.util.ArrayList;
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
        parse(ctx, url, cb, true);
    }

    /**
     * 解析入口。
     *
     * @param allowWebFilter 是否允许走「网页解析过滤器」（原版 {@code web_parse_filter.json}
     *                       + WebView 拦截）。拦截到的真实接口 URL 再次进入本方法时
     *                       传 false，避免 filter 递归。
     */
    public static void parse(final Context ctx, final String url, final Callback cb,
                             final boolean allowWebFilter) {
        if (ctx == null || TextUtils.isEmpty(url)) {
            cb.onError("illegal argument");
            return;
        }
        // 【2026-10-26】1:1 复刻原版 Parser.parse(viewGroup,url,cb)：
        //   先查 web_parse_filter.json，命中就改走 WebView 拦截解析（优酷等 SPA 站点必须如此）。
        if (allowWebFilter) {
            WebParseFilter.Conf conf = WebParseFilter.match(ctx, url);
            if (conf != null) {
                WebParseFilter.parseWithWebView(ctx, conf, url, cb);
                return;
            }
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
        // 【2026-10-26 暴露 JS 异常】只把**最后那一次调用**包进 try/catch，
        //   插件/root 的函数声明仍留在顶层作用域（否则 window[methodName] 取不到插件函数）。
        //   异常以 __JSERR__ 前缀回传，JsEngine 识别后转成 onError，日志可见。
        String code1 = rootJs + ";try{__JZ_RES__=getParser('" + escapeJs(url)
                + "')}catch(e){__JZ_RES__='__JSERR__'+((e&&e.message)?e.message:String(e))}";
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
                LogBridge.d(TAG, "路由：" + url + " → " + pluginName + "::" + fnName);

                // 第 2 步：执行具体解析函数
                final String pluginJs = JsPluginStore.getPlugin(ctx, pluginName);
                if (TextUtils.isEmpty(pluginJs)) {
                    cb.onError("plugin not found: " + pluginName);
                    return;
                }
                String code2 = pluginJs
                        + ";try{__JZ_RES__=getParseResult('" + escapeJs(fnName) + "','" + escapeJs(url)
                        + "')}catch(e){__JZ_RES__='__JSERR__'+((e&&e.message)?e.message:String(e))}";
                JsEngine.get().evaluate(code2, new JsEngine.ResultCallback() {
                    @Override
                    public void onResult(String result) {
                        List<String> urls = parseStringArray(result);
                        urls.removeAll(Collections.singleton(""));
                        if (urls.isEmpty()) {
                            LogBridge.i(TAG, "解析结果为空（" + pluginName + "）");
                            cb.onError("empty parse result");
                            return;
                        }
                        cb.onSuccess(urls);
                    }

                    @Override
                    public void onError(String message) {
                        // 【2026-10-26】把失败原因打出来（原来只有"结果为空"，
                        //   看不到是 JS 抛异常、请求失败还是路由缺失）
                        LogBridge.w(TAG, "插件执行失败(" + pluginName + "): " + message);
                        cb.onError(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                // 【2026-10-26 补日志】原来这里只有 cb.onError(message)，
                //   上层 parseSync 收到 onError 只 countDown，不打任何日志 →
                //   整条 JS 插件链路（路由步骤）失败时**一条日志都没有**，
                //   只能看到「web 解析失败，判不可达」，无法定位是引擎未就绪还是路由缺函数。
                LogBridge.w(TAG, "路由步骤失败：" + url + " → " + message);
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
                // 【2026-10-26 补日志】原来这里只 countDown，失败原因被吞掉
                LogBridge.w(TAG, "同步解析失败：" + url + " → " + message);
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

    // 【2026-10-15 清理】原此处为两个调试方法：
    //   · describeRoute(Context, String) —— 「列出协议会路由到哪个插件（不执行解析）」，全仓库零调用者；
    //   · parseSyncRoute(Context, String, String) —— 只被前者调用。
    //   二者均为死代码，已删除。如需排查路由，UrlSourceParser.parse() 里已有现成日志：
    //   `LogBridge.i(TAG, "路由：" + url + " → " + pluginName + "::" + fnName)`。
}
