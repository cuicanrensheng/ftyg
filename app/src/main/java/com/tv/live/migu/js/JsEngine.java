package com.tv.live.migu.js;

import android.content.Context;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tv.live.util.LogBridge;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置源6（咪咕）JS 求值引擎 —— 1:1 复刻畅享TV 的 {@code org.fungo.jslayer.JsLayer}
 * （其底层为第三方库 {@code com.evgenii.jsevaluator.JsEvaluator}，本质就是一个 WebView）。
 *
 * <p><b>求值约定（与原版完全一致）</b>：
 * <ol>
 *   <li>先把 {@code assets/js/native_layer.js} 与目标 JS <b>拼接</b> 后一次性执行；</li>
 *   <li>{@code native_layer.js} 里的 {@code urlRequest/getMD5/log/...} 全部转调注入对象
 *       {@code client}（即 {@link JsBridge}）；</li>
 *   <li>返回值由 {@code evaluateJavascript} 的回调带回，需二次解包（见 {@link #unwrap}）。</li>
 * </ol>
 *
 * <p><b>线程</b>：WebView 必须在主线程创建与调用，本类内部自动 post 到主线程；
 * 调用方可以在后台线程直接调用 {@link #evaluate}。
 *
 * <p><b>不可用即降级</b>：没有 WebView 系统组件（部分精简 ROM / 电视盒子）或初始化失败时，
 * {@link #isAvailable()} 返回 false，调用方应回退到纯 Java 实现
 * （{@link com.tv.live.migu.MiguUrlResolver}）。
 */
@SuppressLint("StaticFieldLeak")
public final class JsEngine {

    private static final String TAG = "MiguJsEngine";

    /** 宿主公共层：定义 urlRequest / getMD5 / log 等，原版固定由原生提供 */
    private static final String ASSET_NATIVE_LAYER = "js/native_layer.js";

    /** JS 求值整体超时（毫秒） */
    private static final long EVAL_TIMEOUT_MS = 25_000L;

    /** 【2026-10-26】JS 异常回传前缀（evaluateJavascript 的返回值以它开头即表示脚本抛异常）。 */
    private static final String JS_ERROR_PREFIX = "__JSERR__";
    /** 等待页面就绪超时（毫秒） */
    private static final long READY_TIMEOUT_MS = 8_000L;
    /** 就绪前最多排队多少个求值任务 */
    private static final int MAX_PENDING = 8;

    private static final JsEngine INSTANCE = new JsEngine();

    public static JsEngine get() {
        return INSTANCE;
    }

    /** JS 求值回调 */
    public interface ResultCallback {
        void onResult(String value);
        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<Runnable> pending = new ArrayDeque<>();

    private WebView webView;
    private boolean pageReady;
    /** 已确认不可用（无 WebView 组件 / 初始化异常） */
    private boolean unavailable;
    private String nativeLayerJs = "";

    private JsEngine() {}

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 幂等初始化；可在任意线程调用 */
    public void init(final Context context) {
        if (context == null) return;
        if (!isMainThread()) {
            final Context app = context.getApplicationContext();
            main.post(() -> init(app));
            return;
        }
        if (webView != null || unavailable) return;

        if (!hasWebViewFeature(context)) {
            unavailable = true;
            LogBridge.i(TAG, "本机无 WebView 组件，JS 引擎不可用（将降级为 Java 解析）");
            return;
        }

        try {
            nativeLayerJs = JsPluginStore.readAsset(context, ASSET_NATIVE_LAYER);
            if (TextUtils.isEmpty(nativeLayerJs)) {
                unavailable = true;
                LogBridge.i(TAG, "缺少 " + ASSET_NATIVE_LAYER + "，JS 引擎不可用");
                return;
            }

            webView = new WebView(context.getApplicationContext());
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(false);
            ws.setDatabaseEnabled(false);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
            ws.setSupportZoom(false);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    onPageReady();
                }
            });
            // 注入名固定为 client —— 与 native_layer.js 内的调用一致，不可更改
            webView.addJavascriptInterface(new JsBridge(context.getApplicationContext()), "client");

            webView.loadDataWithBaseURL("file:///android_asset/",
                    "<html><head><meta charset=\"utf-8\"></head><body></body></html>",
                    "text/html", "utf-8", null);
            LogBridge.d(TAG, "JS 引擎初始化中…");
        } catch (Throwable t) {
            unavailable = true;
            webView = null;
            LogBridge.w(TAG, "JS 引擎初始化异常：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    private void onPageReady() {
        pageReady = true;
        LogBridge.d(TAG, "JS 引擎就绪");
        List<Runnable> tasks = new ArrayList<>(pending);
        pending.clear();
        for (Runnable r : tasks) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /** JS 引擎是否已就绪可用（未就绪时会排队等待，见 {@link #evaluate}） */
    public boolean isAvailable() {
        return !unavailable;
    }

    /** WebView 组件存在性检测 */
    private static boolean hasWebViewFeature(Context ctx) {
        try {
            return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------

    /**
     * 执行一段 JS（会自动前置 {@code native_layer.js}）。
     *
     * @param code 目标 JS 代码，通常形如 {@code xxxx.js 内容 + ";getParser('...');"}
     * @param cb   结果回调（主线程）
     */
    public void evaluate(final String code, final ResultCallback cb) {
        if (cb == null) return;
        if (TextUtils.isEmpty(code)) {
            cb.onError("empty js code");
            return;
        }
        main.post(() -> {
            if (unavailable) {
                LogBridge.w(TAG, "求值失败：JS 引擎不可用");
                cb.onError("js engine unavailable");
                return;
            }
            if (pageReady && webView != null) {
                doEvaluate(code, cb);
                return;
            }
            if (pending.size() >= MAX_PENDING) {
                LogBridge.w(TAG, "求值失败：JS 引擎忙（排队 " + pending.size() + " 条）");
                cb.onError("js engine busy");
                return;
            }
            final Runnable task = () -> {
                if (unavailable || webView == null) cb.onError("js engine unavailable");
                else doEvaluate(code, cb);
            };
            pending.add(task);
            main.postDelayed(() -> {
                if (pending.remove(task)) {
                    // 【2026-10-26 补日志】原来是静默 onError，上层看不到「引擎没就绪」
                    LogBridge.w(TAG, "求值失败：JS 引擎未就绪（等待 " + READY_TIMEOUT_MS
                            + "ms 超时，pageReady=" + pageReady + "）");
                    cb.onError("js engine not ready");
                }
            }, READY_TIMEOUT_MS);
        });
    }

    private void doEvaluate(final String code, final ResultCallback cb) {
        if (webView == null) {
            cb.onError("js engine unavailable");
            return;
        }
        final AtomicBoolean finished = new AtomicBoolean(false);

        main.postDelayed(() -> {
            if (finished.compareAndSet(false, true)) {
                LogBridge.w(TAG, "JS 求值超时");
                cb.onError("js timeout");
            }
        }, EVAL_TIMEOUT_MS);

        try {
            // 【2026-10-26 暴露 JS 异常】JS 一旦抛异常（如插件里的正则没匹配上 → TypeError），
            //   WebView 只会回一个 "null"，上层只能看到"结果为空/超时"，看不到原因。
            //   这里不做整体 IIFE 包装——插件的函数声明必须留在**顶层作用域**，
            //   否则 native_layer.js 的 `window[methodName]` 取不到插件函数（实测 "fn is not a function"）。
            //   异常捕获由调用方（UrlSourceParser）在最后那一次调用上自行包 try/catch，
            //   结果以 __JSERR__ 前缀回传，这里识别并转成 onError。
            webView.evaluateJavascript(nativeLayerJs + code, value -> {
                if (!finished.compareAndSet(false, true)) return;
                String out = unwrap(value);
                if (out == null) {
                    cb.onError("js returned null");
                } else if (out.startsWith(JS_ERROR_PREFIX)) {
                    String msg = out.substring(JS_ERROR_PREFIX.length());
                    LogBridge.w(TAG, "JS 插件执行异常: " + msg);
                    cb.onError("js exception: " + msg);
                } else {
                    cb.onResult(out);
                }
            });
        } catch (Throwable t) {
            if (finished.compareAndSet(false, true)) {
                cb.onError("js evaluate exception: " + t.getMessage());
            }
        }
    }

    /**
     * 解包 {@code evaluateJavascript} 的返回值。
     *
     * <p>WebView 返回的是「对 JS 返回值再做一次 JSON 编码」的字符串。例如 JS 返回
     * <pre>
     *   ["other_live.js","miguLiveHdParsePlay"]
     * </pre>
     * 回来的是 {@code "[\"other_live.js\",\"miguLiveHdParsePlay\"]"}。
     * 用 {@code new JSONArray("[" + raw + "]")} 做一次反转义即可拿到原字符串。
     */
    private static String unwrap(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || "null".equals(v) || "undefined".equals(v)) return null;
        try {
            JSONArray arr = new JSONArray("[" + v + "]");
            if (arr.isNull(0)) return null;
            return arr.getString(0);
        } catch (Throwable t) {
            // 兜底：手工去掉最外层引号
            if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                v = v.substring(1, v.length() - 1);
            }
            return v.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        }
    }

    // ------------------------------------------------------------------

    /** 释放 WebView（应用退出时调用；不调用也不影响功能） */
    public void release() {
        if (!isMainThread()) {
            main.post(this::release);
            return;
        }
        pending.clear();
        pageReady = false;
        WebView wv = webView;
        webView = null;
        if (wv != null) {
            try {
                wv.removeAllViews();
                wv.destroy();
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 读取 assets 文本（对外共用，供 {@link JsPluginStore} 复用） */
    static String readAssetText(Context ctx, String path) {
        try (InputStream in = ctx.getAssets().open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
