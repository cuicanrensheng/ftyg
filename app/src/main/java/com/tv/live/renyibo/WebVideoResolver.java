package com.tv.live.renyibo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tv.live.util.LogBridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebView 视频播放页解析器 —— 用于解析 4kvm.top 等第三方解析页面，
 * 从页面中的 &lt;video&gt; 元素提取真实的 m3u8 直链。
 *
 * <p><b>使用场景</b>：源5（RenYiBo）的 vod_url_with_player 中部分线路
 * 是第三方解析页面（如 4kvm.top/play/xxx），不是直接的 m3u8 链接，
 * 被 firstHttpSeg() 过滤掉。本类通过 WebView 加载页面，等待播放器初始化后
 * 读取 video.currentSrc 获取真实播放地址。
 *
 * <p><b>线程</b>：WebView 必须在主线程创建与调用，本类内部自动 post 到主线程；
 * 调用方可以在后台线程直接调用 {@link #resolve(String)}，会阻塞等待结果。
 *
 * <p><b>超时</b>：整体解析超时 15 秒，超时返回 null，调用方应降级处理。
 *
 * <p><b>缓存</b>：解析结果按 URL 缓存，避免重复解析。
 */
@SuppressLint("StaticFieldLeak")
public final class WebVideoResolver {

    private static final String TAG = "WebVideoResolver";

    /** 整体解析超时（毫秒） */
    private static final long RESOLVE_TIMEOUT_MS = 15_000L;
    /** 等待 video 元素出现的轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 500L;
    /** 最大轮询次数 */
    private static final int MAX_POLL_COUNT = 20;

    private static volatile WebVideoResolver instance;

    private final Context appContext;
    private final Handler mainHandler;
    private WebView webView;
    private boolean initialized;

    /** 解析结果缓存：原始URL -> 解析后的直链URL */
    private final java.util.Map<String, String> cache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private WebVideoResolver(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static WebVideoResolver get(Context context) {
        if (instance == null) {
            synchronized (WebVideoResolver.class) {
                if (instance == null) {
                    instance = new WebVideoResolver(context);
                }
            }
        }
        return instance;
    }

    /**
     * 解析第三方播放页面，返回真实的 m3u8 直链。
     *
     * @param pageUrl 第三方解析页面 URL（如 https://www.4kvm.top/play/xxx）
     * @return 真实的 m3u8 直链；解析失败或超时时返回 null
     */
    public String resolve(String pageUrl) {
        if (TextUtils.isEmpty(pageUrl)) return null;

        // 检查缓存
        String cached = cache.get(pageUrl);
        if (cached != null) {
            LogBridge.d(TAG, "解析命中缓存: " + pageUrl + " -> " + cached);
            return cached;
        }

        // 检查是否是已知的解析源
        if (!isSupportedPage(pageUrl)) {
            LogBridge.d(TAG, "不支持的解析页面: " + pageUrl);
            return null;
        }

        final AtomicReference<String> resultRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            try {
                ensureWebView();
                if (webView == null) {
                    latch.countDown();
                    return;
                }

                LogBridge.i(TAG, "开始解析页面: " + pageUrl);

                // 设置 WebViewClient 监听页面加载
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        LogBridge.d(TAG, "页面加载完成: " + url);
                        // 页面加载完成后开始轮询 video 元素
                        pollVideoSource(view, resultRef, latch, 0);
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request,
                                                WebResourceError error) {
                        super.onReceivedError(view, request, error);
                        if (request.isForMainFrame()) {
                            LogBridge.w(TAG, "页面加载错误: " + error.getDescription());
                        }
                    }
                });

                webView.loadUrl(pageUrl);
            } catch (Exception e) {
                LogBridge.e(TAG, "解析页面异常: " + e.getMessage());
                latch.countDown();
            }
        });

        try {
            boolean finished = latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                LogBridge.w(TAG, "解析超时: " + pageUrl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogBridge.w(TAG, "解析被中断: " + pageUrl);
        }

        String result = resultRef.get();
        if (result != null) {
            cache.put(pageUrl, result);
            LogBridge.i(TAG, "解析成功: " + pageUrl + " -> " + result);
        }
        return result;
    }

    /**
     * 轮询 video 元素的 currentSrc。
     */
    private void pollVideoSource(WebView view, AtomicReference<String> resultRef,
                                  CountDownLatch latch, int attempt) {
        if (attempt >= MAX_POLL_COUNT) {
            LogBridge.w(TAG, "轮询达到最大次数，未找到 video 元素");
            latch.countDown();
            return;
        }

        view.evaluateJavascript(
                "(function() { " +
                "  var videos = document.querySelectorAll('video'); " +
                "  for (var i = 0; i < videos.length; i++) { " +
                "    var src = videos[i].currentSrc || videos[i].src; " +
                "    if (src && src.indexOf('http') === 0) return src; " +
                "  } " +
                "  return ''; " +
                "})()",
                value -> {
                    String src = value != null ? value.replace("\"", "").trim() : "";
                    if (!TextUtils.isEmpty(src) && src.startsWith("http")) {
                        resultRef.set(src);
                        latch.countDown();
                    } else {
                        // 继续轮询
                        mainHandler.postDelayed(() ->
                                        pollVideoSource(view, resultRef, latch, attempt + 1),
                                POLL_INTERVAL_MS);
                    }
                });
    }

    /**
     * 确保 WebView 已初始化。
     */
    private void ensureWebView() {
        if (initialized && webView != null) return;

        try {
            webView = new WebView(appContext);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 10; SM-G975F) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/91.0.4472.120 Mobile Safari/537.36");
            webView.setWebChromeClient(new WebChromeClient());
            initialized = true;
            LogBridge.i(TAG, "WebView 初始化成功");
        } catch (Exception e) {
            LogBridge.e(TAG, "WebView 初始化失败: " + e.getMessage());
            webView = null;
            initialized = false;
        }
    }

    /**
     * 判断是否是支持的解析页面。
     */
    private boolean isSupportedPage(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("4kvm.top") ||
               lower.contains("4kyslink") ||
               lower.contains("/play/");
    }

    /**
     * 清除解析缓存。
     */
    public void clearCache() {
        cache.clear();
        LogBridge.i(TAG, "解析缓存已清除");
    }

    /**
     * 销毁 WebView 资源。
     */
    public void destroy() {
        mainHandler.post(() -> {
            if (webView != null) {
                try {
                    webView.stopLoading();
                    webView.destroy();
                } catch (Exception e) {
                    // ignore
                }
                webView = null;
                initialized = false;
            }
        });
    }
}
