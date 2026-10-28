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

    /** 整体解析超时（毫秒）—— 4kvm 等站点有 5 秒广告，需等待广告结束后播放器才初始化 */
    private static final long RESOLVE_TIMEOUT_MS = 30_000L;
    /** 等待 video 元素出现的轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 500L;
    /** 最大轮询次数（30秒 / 500ms = 60次） */
    private static final int MAX_POLL_COUNT = 60;
    /**
     * 片单页最少轮询轮数才允许「与骨架相同」的结果被接受（4 轮 ≈ 2 秒）。
     * 用于等 SPA 主体内容渲染，避免抓到页面骨架里的全站「最新更新」区块。
     */
    private static final int PLAYLIST_MIN_ACCEPT_ATTEMPT = 4;

    /**
     * 站点「最新更新」推荐卡片的时间尾巴（如「电影 刚刚」「电视剧 3分钟前」）。
     * 命中即判定为该区块的重复项，不计入片单内容。
     */
    private static final java.util.regex.Pattern SITE_CHROME_TAIL =
            java.util.regex.Pattern.compile(
                    "(刚刚|\\d+\\s*(秒|分钟|小时|天)前|昨天|前天|今天)\\s*$");

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
        return resolve(pageUrl, null);
    }

    /**
     * 【2026-10-18 EPG 选集】解析指定集：加载播放页后先点击目标集，再取「点击后新增」的 m3u8。
     *
     * <p>4kvm 的播放页把所有集数内联在同一文档里（{@code a[data-line][data-episode]}），
     * 换集是前端行为、URL 不变。若不区分，直接读 performance 会拿到上一集/第 1 集残留的地址。
     * 因此先用 JS 记录当前已存在的 m3u8 列表作为基线，点击后再取不在基线里的那一条。
     *
     * @param pageUrl       播放页地址
     * @param clickSelector 目标集的 CSS 选择器；{@code null} 表示不点击（整片/第 1 集）
     */
    public String resolve(String pageUrl, final String clickSelector) {
        return resolve(pageUrl, clickSelector, null);
    }

    /**
     * 【2026-10-18 清晰度】三参数版本：额外要求站点播放器使用指定清晰度档。
     *
     * <p>4kvm 的清晰度由页面读 {@code localStorage.artplayer_settings.quality}
     * （{@code 4k/1080/720/480}）决定请求哪一档。这里的做法是：页面加载完成后先比对
     * localStorage，不一致就写入并 {@code location.reload()} 一次，再继续取流。
     * 只在「档位与上次不同」时多一次加载，稳定后为 0 额外成本。
     *
     * @param qualityKey 站点清晰度 key；{@code null} = 不干预（自动）
     */
    public String resolve(String pageUrl, final String clickSelector, final String qualityKey) {
        if (TextUtils.isEmpty(pageUrl)) return null;

        final String cacheKey = (TextUtils.isEmpty(clickSelector)
                ? pageUrl : (pageUrl + "|" + clickSelector))
                + (TextUtils.isEmpty(qualityKey) ? "" : ("|q" + qualityKey));

        // 检查缓存
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 检查是否是已知的解析源
        if (!isSupportedPage(pageUrl)) {
            LogBridge.d(TAG, "不支持的解析页面: " + pageUrl);
            return null;
        }

        final AtomicReference<String> resultRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] qualityDone = {false};

        mainHandler.post(() -> {
            try {
                ensureWebView();
                if (webView == null) {
                    latch.countDown();
                    return;
                }


                // 设置 WebViewClient 监听页面加载
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        // 【2026-10-18 双声道修复】站点播放器会自动播放并出声，而我们是把这个
                        // WebView 当「取地址工具」用，真正出声的是 ExoPlayer → 两路音频同时响
                        // （一路是正片进度，一路是刚从头开始）。页面一加载完立即静音。
                        muteAllVideo(view);
                        // 【2026-10-18 清晰度】先确保 localStorage 档位正确（不一致则重载一次）
                        if (!TextUtils.isEmpty(qualityKey) && !qualityDone[0]) {
                            qualityDone[0] = true;
                            applyQualityThenProceed(view, qualityKey, clickSelector, resultRef, latch);
                            return;
                        }
                        proceedAfterPageReady(view, clickSelector, resultRef, latch);
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

        // 【2026-10-18 双声道修复】地址已拿到，这个 WebView 不再需要出声/播放：
        // 暂停并静音页面里所有 video，避免与 ExoPlayer 形成第二路音频。
        stopWebViewMedia();

        if (result != null) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 【2026-10-18 双声道修复】静音 WebView 内所有 video（不暂停，保证网络请求继续，
     * 否则站点播放器可能根本不拉 m3u8）。
     */
    private void muteAllVideo(WebView view) {
        try {
            view.evaluateJavascript(
                    "(function(){try{var vs=document.querySelectorAll('video');"
                            + "for(var i=0;i<vs.length;i++){vs[i].muted=true;vs[i].volume=0;}}catch(e){}})()",
                    null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【2026-10-18 双声道修复】暂停并静音 WebView 内所有 video（地址已取到，不再需要播放）。
     */
    private void stopWebViewMedia() {
        mainHandler.post(() -> {
            if (webView == null) return;
            try {
                webView.evaluateJavascript(
                        "(function(){try{var vs=document.querySelectorAll('video');"
                                + "for(var i=0;i<vs.length;i++){try{vs[i].pause();}catch(e){}"
                                + "vs[i].muted=true;vs[i].volume=0;}}catch(e){}})()",
                        null);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 【2026-10-18 EPG 选集】记录当前 m3u8 基线，点击目标集，然后开始轮询。
     *
     * <p>基线写入 {@code window.__kvm4Base}，供 {@link #pollVideoSource} 的 JS 做差集。
     */
    private void clickEpisodeThenPoll(WebView view, String clickSelector,
                                       AtomicReference<String> resultRef, CountDownLatch latch) {
        String sel = clickSelector.replace("\\", "\\\\").replace("\"", "\\\"");
        String js = "(function() {"
                + "  try {"
                + "    var r = performance.getEntriesByType('resource');"
                + "    var base = [];"
                + "    for (var i = 0; i < r.length; i++) {"
                + "      var n = r[i].name;"
                + "      if (n && (n.indexOf('.m3u8') > -1 || n.indexOf('/m3u8/') > -1)) base.push(n);"
                + "    }"
                + "    window.__kvm4Base = base;"
                + "  } catch (e) { window.__kvm4Base = []; }"
                + "  var a = document.querySelector(\"" + sel + "\");"
                + "  if (a) { a.click();"
                // 换集后站点可能重置音量，稍后再静音一次（不阻止播放，只消音）
                + "    setTimeout(function(){try{var vs=document.querySelectorAll('video');"
                + "      for(var i=0;i<vs.length;i++){vs[i].muted=true;vs[i].volume=0;}}catch(e){}}, 300);"
                + "    return 'clicked'; }"
                + "  return 'notfound';"
                + "})()";
        view.evaluateJavascript(js, value -> {
            LogBridge.d(TAG, "选集点击结果: " + value + " selector=" + sel);
            pollVideoSource(view, resultRef, latch, 0);
        });
    }

    /**
     * 轮询 video 元素的 currentSrc，同时检查 performance 网络请求中的 m3u8 地址。
     *
     * <p>4kvm 等站点使用 MSE（Media Source Extensions）技术，video.currentSrc 是 blob: URL，
     * 真实 m3u8 地址需要从 performance 资源记录中提取。
     */
    private void pollVideoSource(WebView view, AtomicReference<String> resultRef,
                                  CountDownLatch latch, int attempt) {
        if (attempt >= MAX_POLL_COUNT) {
            LogBridge.i(TAG, "轮询达到最大次数，未找到 video 元素");
            latch.countDown();
            return;
        }

        view.evaluateJavascript(
                "(function() { " +
                // 0) 选集模式：只认点击后「新增」的 m3u8（window.__kvm4Base 为点击前的基线）
                "  var base = window.__kvm4Base || []; " +
                "  if (base.length > 0) { " +
                "    try { " +
                "      var rr = performance.getEntriesByType('resource'); " +
                "      var pick = ''; " +
                "      for (var x = 0; x < rr.length; x++) { " +
                "        var nm = rr[x].name; " +
                "        if (!nm) continue; " +
                "        if (nm.indexOf('.m3u8') < 0 && nm.indexOf('/m3u8/') < 0) continue; " +
                "        if (base.indexOf(nm) < 0) pick = nm; " +
                "      } " +
                "      if (pick) return pick; " +
                "    } catch (e) {} " +
                "    return ''; " +
                "  } " +
                // 1) 先检查 video 元素的直接 src
                "  var videos = document.querySelectorAll('video'); " +
                "  for (var i = 0; i < videos.length; i++) { " +
                "    var src = videos[i].currentSrc || videos[i].src; " +
                "    if (src && src.indexOf('http') === 0 && src.indexOf('.m3u8') > -1) return src; " +
                "  } " +
                // 2) 从 performance 网络请求中提取 m3u8 地址
                "  try { " +
                "    var resources = performance.getEntriesByType('resource'); " +
                "    for (var j = 0; j < resources.length; j++) { " +
                "      var name = resources[j].name; " +
                "      if (name && (name.indexOf('.m3u8') > -1 || name.indexOf('/m3u8/') > -1)) { " +
                "        return name; " +
                "      } " +
                "    } " +
                "  } catch(e) {} " +
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
            // 【2026-10-18】改成「不允许自动播放」：WebView 只用来取地址，一出声就会出现
            // “两路声音”（站点播放器从头播 + ExoPlayer 播正片）。设为 true 后站点播放器
            // 不会被媒体策略放行出声；HLS 清单请求仍会发出（播放器初始化即拉流），
            // 地址照样能从 performance 里取到。下面还有 mute/pause 作为双保险。
            settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 10; SM-G975F) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/91.0.4472.120 Mobile Safari/537.36");
            webView.setWebChromeClient(new WebChromeClient());
            initialized = true;
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
    }

    /**
     * 解析 playlist 片单页面，提取视频列表。
     *
     * <p>4kvm.top 的 playlist 页面是 SPA（单页应用），HTTP 下载的 HTML 不含视频链接，
     * 必须用 WebView 加载并等待 JS 渲染后才能提取。
     *
     * @param playlistUrl 片单页面 URL（如 https://4kvm.top/playlist/4405）
     * @return 视频列表，每个元素含 playId 和 title；失败返回空列表
     */
    public java.util.List<java.util.Map<String, String>> resolvePlaylist(String playlistUrl) {
        if (TextUtils.isEmpty(playlistUrl)) return new java.util.ArrayList<>();

        final AtomicReference<String> resultRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            try {
                ensureWebView();
                if (webView == null) {
                    latch.countDown();
                    return;
                }


                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        // 等待 JS 渲染后提取视频链接
                        pollPlaylistLinks(view, resultRef, latch, 0, new String[2]);
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request,
                                                WebResourceError error) {
                        super.onReceivedError(view, request, error);
                        if (request.isForMainFrame()) {
                            LogBridge.w(TAG, "片单页面加载错误: " + error.getDescription());
                        }
                    }
                });

                webView.loadUrl(playlistUrl);
            } catch (Exception e) {
                LogBridge.e(TAG, "解析片单页面异常: " + e.getMessage());
                latch.countDown();
            }
        });

        try {
            boolean finished = latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                LogBridge.w(TAG, "解析片单超时: " + playlistUrl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String json = resultRef.get();
        if (TextUtils.isEmpty(json)) return new java.util.ArrayList<>();

        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            java.util.List<java.util.Map<String, String>> result = new java.util.ArrayList<>();
            int skipped = 0;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                String title = obj.optString("title", "");
                // 【2026-10-18】剔除全站「最新更新」区块的链接：卡片文字尾部带
                // 「刚刚 / N分钟前 / 昨天」等相对时间（实测 24 个片单页各约 10 条，
                // 合计 240 条重复项，且这些影片已在首页/电影分组里出现）。
                if (SITE_CHROME_TAIL.matcher(title).find()) {
                    skipped++;
                    continue;
                }
                java.util.Map<String, String> item = new java.util.HashMap<>();
                item.put("playId", obj.optString("playId", ""));
                item.put("title", title);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            LogBridge.w(TAG, "片单结果解析失败: " + e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    /**
     * 轮询 playlist 页面的视频链接。
     *
     * <p><b>【2026-10-18 修复】</b>原实现「首次拿到非空结果就返回」，而 4kvm 是 SPA：
     * 页面骨架里有一个<b>全站「最新更新」区块</b>（首页/电影/电视剧/动漫各页都有同样的
     * 7 部，标题带"刚刚"）。轮询第一次（500ms）就命中这 7 个 /play/ 链接并立即 countDown，
     * 片单主体还没渲染 → 24 个不同片单全部返回同一份 7 部，168 条（24×7）里只有 7 个唯一 tvg-id。
     *
     * <p>现改为：记录首轮结果作为「骨架基线」，之后必须
     * <ol>
     *   <li>连续两轮结果完全一致（渲染稳定），且</li>
     *   <li>结果与骨架基线不同（片单主体已渲染出来），或已等到
     *       {@link #PLAYLIST_MIN_ACCEPT_ATTEMPT} 轮（约 2 秒）</li>
     * </ol>
     * 才接受结果。超时仍无更好结果时退回最后一轮，避免整体失败。
     *
     * @param state 长度 2 的容器：[0]=首轮骨架基线，[1]=上一轮结果
     */
    private void pollPlaylistLinks(WebView view, AtomicReference<String> resultRef,
                                    CountDownLatch latch, int attempt, final String[] state) {
        if (attempt >= MAX_POLL_COUNT) {
            if (!TextUtils.isEmpty(state[1])) {
                LogBridge.w(TAG, "片单轮询达上限，退回最后一轮结果（疑似主体未渲染）");
                resultRef.set(state[1]);
            } else {
                LogBridge.i(TAG, "片单轮询达到最大次数，未找到视频链接");
            }
            latch.countDown();
            return;
        }

        view.evaluateJavascript(
                "(function() { " +
                "  var links = document.querySelectorAll('a[href*=\"/play/\"]'); " +
                "  var results = []; " +
                "  var seen = {}; " +
                "  links.forEach(function(a) { " +
                "    var href = a.getAttribute('href') || ''; " +
                "    var m = href.match(/\\/play\\/(ch[a-zA-Z0-9]+)/); " +
                "    if (m && !seen[m[1]]) { " +
                "      seen[m[1]] = true; " +
                "      var text = a.textContent.trim().replace(/\\s+/g, ' '); " +
                "      results.push({playId: m[1], title: text}); " +
                "    } " +
                "  }); " +
                "  return JSON.stringify(results); " +
                "})()",
                value -> {
                    String json = unquoteJson(value);
                    if (TextUtils.isEmpty(json) || json.equals("[]")) {
                        // 页面还没渲染出任何链接，继续等
                        mainHandler.postDelayed(() ->
                                        pollPlaylistLinks(view, resultRef, latch, attempt + 1, state),
                                POLL_INTERVAL_MS);
                        return;
                    }

                    if (state[0] == null) state[0] = json;   // 首轮 = 页面骨架基线
                    final boolean stable = json.equals(state[1]);
                    final boolean changedFromShell = !json.equals(state[0]);
                    state[1] = json;

                    // ① 已脱离骨架且稳定 → 片单主体已渲染，立即接受
                    // ② 仍与骨架相同但已等够 2 秒且稳定 → 接受（避免必然超时）
                    if (stable && (changedFromShell || attempt >= PLAYLIST_MIN_ACCEPT_ATTEMPT)) {
                        resultRef.set(json);
                        latch.countDown();
                        return;
                    }

                    mainHandler.postDelayed(() ->
                                    pollPlaylistLinks(view, resultRef, latch, attempt + 1, state),
                            POLL_INTERVAL_MS);
                });
    }

    /**
     * 【2026-10-18 清晰度】把站点 localStorage 的档位写成期望值；不一致则重载页面一次。
     *
     * <p>重载后 {@code qualityDone} 已为 true，下一次 onPageFinished 直接进入取流流程。
     */
    private void applyQualityThenProceed(final WebView view, final String qualityKey,
                                          final String clickSelector,
                                          final AtomicReference<String> resultRef,
                                          final CountDownLatch latch) {
        String js = "(function(){try{"
                + "var s=JSON.parse(localStorage.getItem('artplayer_settings')||'{}');"
                + "if(String(s.quality||'')===String('" + qualityKey + "'))return 'ok';"
                + "s.quality='" + qualityKey + "';"
                + "localStorage.setItem('artplayer_settings',JSON.stringify(s));"
                + "return 'reload';"
                + "}catch(e){return 'err';}})()";
        try {
            view.evaluateJavascript(js, value -> {
                String r = value == null ? "" : value;
                if (r.contains("reload")) {
                    LogBridge.i(TAG, "清晰度写入 " + qualityKey + " → 重载页面使其生效");
                    try {
                        view.reload();
                    } catch (Throwable t) {
                        proceedAfterPageReady(view, clickSelector, resultRef, latch);
                    }
                } else {
                    LogBridge.d(TAG, "清晰度已是 " + qualityKey + "（无需重载）");
                    proceedAfterPageReady(view, clickSelector, resultRef, latch);
                }
            });
        } catch (Throwable t) {
            proceedAfterPageReady(view, clickSelector, resultRef, latch);
        }
    }

    /** 页面就绪（且清晰度已对齐）后的分派：整片直接轮询，指定集先点击再轮询。 */
    private void proceedAfterPageReady(WebView view, String clickSelector,
                                        AtomicReference<String> resultRef, CountDownLatch latch) {
        if (TextUtils.isEmpty(clickSelector)) {
            pollVideoSource(view, resultRef, latch, 0);
        } else {
            clickEpisodeThenPoll(view, clickSelector, resultRef, latch);
        }
    }

    /**
     * 去掉 evaluateJavascript 回调里 JSON 字符串两端的引号与转义。
     */
    private static String unquoteJson(String value) {
        String json = value != null ? value.trim() : "";
        if (json.startsWith("\"") && json.endsWith("\"") && json.length() >= 2) {
            json = json.substring(1, json.length() - 1);
            json = json.replace("\\\"", "\"");
        }
        return json;
    }

    /**
     * 从 /playlists 片单列表页获取所有片单链接。
     *
     * <p>/playlists 页面是片单列表，每个卡片链接到 /playlist/&lt;id&gt;，
     * 需要进入具体片单页才能提取里面的电影。
     *
     * @param playlistsUrl 片单列表页 URL（如 https://4kvm.top/playlists）
     * @return 片单列表，每个元素含 playlistId 和 title；失败返回空列表
     */
    public java.util.List<java.util.Map<String, String>> resolvePlaylistList(String playlistsUrl) {
        if (TextUtils.isEmpty(playlistsUrl)) return new java.util.ArrayList<>();

        final AtomicReference<String> resultRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(() -> {
            try {
                ensureWebView();
                if (webView == null) {
                    latch.countDown();
                    return;
                }


                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        // 等待 JS 渲染后提取片单链接
                        pollPlaylistListLinks(view, resultRef, latch, 0);
                    }
                });

                webView.loadUrl(playlistsUrl);
            } catch (Exception e) {
                LogBridge.e(TAG, "解析片单列表页异常: " + e.getMessage());
                latch.countDown();
            }
        });

        try {
            latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String json = resultRef.get();
        if (TextUtils.isEmpty(json)) return new java.util.ArrayList<>();

        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            java.util.List<java.util.Map<String, String>> result = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                java.util.Map<String, String> item = new java.util.HashMap<>();
                item.put("playlistId", obj.optString("playlistId", ""));
                item.put("title", obj.optString("title", ""));
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            LogBridge.w(TAG, "片单列表结果解析失败: " + e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    /**
     * 轮询 /playlists 页面的片单链接。
     */
    private void pollPlaylistListLinks(WebView view, AtomicReference<String> resultRef,
                                        CountDownLatch latch, int attempt) {
        if (attempt >= MAX_POLL_COUNT) {
            latch.countDown();
            return;
        }

        view.evaluateJavascript(
                "(function() { " +
                "  var links = document.querySelectorAll('a[href*=\"/playlist/\"]'); " +
                "  var results = []; " +
                "  var seen = {}; " +
                "  links.forEach(function(a) { " +
                "    var href = a.getAttribute('href') || ''; " +
                "    var m = href.match(/\\/playlist\\/(\\d+)/); " +
                "    if (m && !seen[m[1]]) { " +
                "      seen[m[1]] = true; " +
                "      var text = a.textContent.trim().replace(/\\s+/g, ' ').substring(0, 30); " +
                "      results.push({playlistId: m[1], title: text}); " +
                "    } " +
                "  }); " +
                "  return JSON.stringify(results); " +
                "})()",
                value -> {
                    String json = value != null ? value.trim() : "";
                    if (json.startsWith("\"") && json.endsWith("\"")) {
                        json = json.substring(1, json.length() - 1);
                        json = json.replace("\\\"", "\"");
                    }
                    if (!TextUtils.isEmpty(json) && !json.equals("[]") && !json.equals("\"[]\"")) {
                        resultRef.set(json);
                        latch.countDown();
                    } else {
                        mainHandler.postDelayed(() ->
                                        pollPlaylistListLinks(view, resultRef, latch, attempt + 1),
                                POLL_INTERVAL_MS);
                    }
                });
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
