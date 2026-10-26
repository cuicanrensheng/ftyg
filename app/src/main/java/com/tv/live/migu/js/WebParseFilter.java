package com.tv.live.migu.js;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tv.live.util.LogBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 【2026-10-26】网页解析过滤器 —— 1:1 复刻畅享TV 的
 * {@code org.fungo.jsparser.Parser.getWebParseConf()} + {@code Parser.webviewParse()}。
 *
 * <p><b>原版机制</b>（{@code org.fungo.jsparser.Parser}）：
 * <pre>
 *   parse(viewGroup, url, cb):
 *       conf = getWebParseConf(url)          // 读 js/web_parse_filter.json
 *       if (conf != null) webviewParse(...)  // 走 WebView 拦截
 *       else               parse(url, cb)   // 走 JS 插件两步求值
 *
 *   webviewParse(...):
 *       webView.loadUrl(conf.replaceUrl(url))
 *       shouldInterceptRequest(request):
 *           if (request.url.contains(conf.filter))   // 例如 ups.youku.com/ups/get.json
 *               parse(request.url, cb)               // 把拦截到的真实接口 URL 交给 JS 插件解析
 * </pre>
 *
 * <p><b>为什么必须有这条链路</b>：优酷（youku.com）是 SPA，页面 HTML 里没有视频地址，
 * 真实取流接口 {@code ups.youku.com/ups/get.json?vid=..&utid=..&ckey=..} 是页面 JS 运行后
 * 才发出的请求。只有用 WebView 加载页面并拦截这个请求，才能拿到带鉴权参数的接口 URL，
 * 再交给 {@code yk_parse.js} 解析出 m3u8。纯 HTTP 抓页面拿不到（我方此前完全没实现这条链路，
 * 导致优酷线路 100% 失败）。
 *
 * <p><b>与原版的差异</b>：原版把 WebView 挂在一个 {@code ViewGroup} 上（需要 Activity 布局），
 * 我方没有那个容器，改成自建一个不可见的 1x1 WebView，用完即销毁。
 */
public final class WebParseFilter {

    private static final String TAG = "MiguWebFilter";

    /** 配置文件名（assets/js/ 下） */
    private static final String CONF_FILE = "web_parse_filter.json";

    /** 原版 webviewParse 的超时：15s */
    private static final long TIMEOUT_MS = 15_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 配置缓存 */
    private static volatile List<Conf> sConfCache;
    private static volatile boolean sConfLoaded;

    private WebParseFilter() {}

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 单条过滤配置（对应原版 {@code org.fungo.jsparser.WebParseConf}） */
    public static final class Conf {
        /** URL 命中该 key 时改走 WebView 拦截 */
        public String key = "";
        /** 需要拦截的请求 URL 特征 */
        public String filter = "";
        /** 加载页面前先做的字符串替换 */
        public final List<String> oldstr = new ArrayList<>();
        public final List<String> newstr = new ArrayList<>();
        /** 自定义 UA，空表示用系统默认 */
        public String ua = "";

        /** 原版 {@code WebParseConf.replaceUrl()} */
        public String replaceUrl(String url) {
            if (url == null) return "";
            String out = url;
            int n = Math.min(oldstr.size(), newstr.size());
            for (int i = 0; i < n; i++) {
                try {
                    out = out.replace(oldstr.get(i), newstr.get(i));
                } catch (Throwable ignored) {
                }
            }
            return out;
        }
    }

    /** 读取并缓存配置 */
    private static List<Conf> loadConfs(Context ctx) {
        if (sConfLoaded && sConfCache != null) return sConfCache;
        List<Conf> list = new ArrayList<>();
        String text = "";
        // ① 热更目录（files/js）优先，与 JsPluginStore 的优先级一致
        try {
            text = readLocalPlain(ctx, CONF_FILE);
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(text)) {
            try {
                text = JsEngine.readAssetText(ctx, "js/" + CONF_FILE);
            } catch (Throwable ignored) {
            }
        }
        if (!TextUtils.isEmpty(text)) {
            try {
                JSONArray arr = new JSONArray(text);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Conf c = new Conf();
                    c.key = o.optString("key", "");
                    c.filter = o.optString("filter", "");
                    c.ua = o.optString("ua", "");
                    JSONArray os = o.optJSONArray("oldstr");
                    if (os != null) {
                        for (int j = 0; j < os.length(); j++) c.oldstr.add(os.optString(j, ""));
                    }
                    JSONArray ns = o.optJSONArray("newstr");
                    if (ns != null) {
                        for (int j = 0; j < ns.length(); j++) c.newstr.add(ns.optString(j, ""));
                    }
                    if (!TextUtils.isEmpty(c.key) && !TextUtils.isEmpty(c.filter)) {
                        list.add(c);
                    }
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "解析 " + CONF_FILE + " 失败：" + t.getMessage());
            }
        }
        sConfCache = list;
        sConfLoaded = true;
        LogBridge.i(TAG, "网页解析过滤配置载入 " + list.size() + " 条");
        return list;
    }

    /** 命中配置则返回它，否则返回 null（= 该 URL 走普通 JS 插件两步求值） */
    public static Conf match(Context ctx, String url) {
        if (ctx == null || TextUtils.isEmpty(url)) return null;
        for (Conf c : loadConfs(ctx)) {
            if (!TextUtils.isEmpty(c.key) && url.contains(c.key)) return c;
        }
        return null;
    }

    /** 清缓存（热更落地后调用） */
    public static void clearCache() {
        sConfCache = null;
        sConfLoaded = false;
    }

    // ------------------------------------------------------------------
    // WebView 拦截解析
    // ------------------------------------------------------------------

    /**
     * 用 WebView 加载页面，拦截命中 {@code conf.filter} 的请求，
     * 把拦截到的真实接口 URL 交给 JS 插件解析。
     */
    public static void parseWithWebView(final Context ctx, final Conf conf,
                                        final String url, final UrlSourceParser.Callback cb) {
        final String loadUrl = conf.replaceUrl(url);
        LogBridge.i(TAG, "走 WebView 拦截解析：" + loadUrl + "（拦截特征=" + conf.filter + "）");
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = null;
                try {
                    wv = new WebView(ctx.getApplicationContext());
                    wv.getSettings().setJavaScriptEnabled(true);
                    wv.getSettings().setDomStorageEnabled(true);
                    wv.getSettings().setDatabaseEnabled(true);
                    wv.getSettings().setAllowFileAccess(true);
                    wv.getSettings().setUserAgentString(
                            TextUtils.isEmpty(conf.ua) ? defaultUa(ctx) : conf.ua);

                    final WebView target = wv;
                    final boolean[] done = new boolean[]{false};
                    final Handler h = MAIN;

                    // 超时兜底（对齐原版 15s）
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (done[0]) return;
                            done[0] = true;
                            LogBridge.w(TAG, "webview 解析 timeout：" + url);
                            destroy(target);
                            cb.onError("webview 解析 timeout");
                        }
                    }, TIMEOUT_MS);

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                            String ru = (request != null && request.getUrl() != null)
                                    ? request.getUrl().toString() : null;
                            if (ru != null && !TextUtils.isEmpty(conf.filter) && ru.contains(conf.filter)) {
                                LogBridge.i(TAG, "拦截到 url：" + ru);
                                if (done[0]) return null;
                                done[0] = true;
                                final String hit = ru;
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        destroy(target);
                                        // 拦截到的真实接口 URL 交给 JS 插件解析（不再走 filter，避免递归）
                                        UrlSourceParser.parse(ctx, hit, cb, false);
                                    }
                                });
                            }
                            return null;
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                            return false;
                        }
                    });
                    wv.loadUrl(loadUrl);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "WebView 拦截解析异常：" + t.getMessage());
                    if (wv != null) destroy(wv);
                    cb.onError("webview 解析异常: " + t.getMessage());
                }
            }
        });
    }

    private static void destroy(WebView wv) {
        try {
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.destroy();
        } catch (Throwable ignored) {
        }
    }

    /** 默认 UA：移动端 Chrome，避免站点按 UA 拒绝 */
    private static String defaultUa(Context ctx) {
        try {
            String ua = null;
            try {
                java.lang.reflect.Method m = WebView.class.getMethod("getDefaultUserAgent");
                Object r = m.invoke(null);
                if (r instanceof String && !TextUtils.isEmpty((String) r)) {
                    ua = (String) r;
                }
            } catch (Throwable ignored) {
            }
            if (!TextUtils.isEmpty(ua)) return ua;
        } catch (Throwable ignored) {
        }
        return "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/71.0.3578.98 Safari/537.36";
    }

    /** 读热更目录（files/js）下的文本；不存在返回空 */
    private static String readLocalPlain(Context ctx, String name) {
        try {
            java.io.File dir = new java.io.File(ctx.getFilesDir(), "js");
            java.io.File f = new java.io.File(dir, name);
            if (!f.exists() || !f.isFile()) return "";
            java.io.InputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
                int n = in.read(buf);
                return (n > 0) ? new String(buf, 0, n, "UTF-8") : "";
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return "";
        }
    }
}
