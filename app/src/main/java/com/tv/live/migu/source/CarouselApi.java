package com.tv.live.migu.source;

import android.text.TextUtils;

import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.tv.live.security.SecurityCore;
import okhttp3.Response;

/**
 * 「轮播」协议 {@code tvnow://} —— 复刻畅享TV 的
 * {@code UrlParser.parse()} 中 {@code tvnow://} 分支 + {@code UrlParser$1.onHttpSuccess}
 * + {@code UrlParser.getSourceByLunboItem()}。
 *
 * <p><b>为什么需要它</b>：服务端下发的 {@code sourceLabels[].source} 里，剧场/轮播类频道
 * （电影 / 少儿 / 综艺 / 热播剧集 / 央视大剧 / 经典韩剧 / 人文记录 / 儿童动画 …）给的是
 * <b>嵌套地址</b>：
 * <pre>
 *   tvnow://http://user.xiaoyouzb.net/p/get_current_carousel?tvId=600455
 * </pre>
 * 即 {@code tvnow://} + 一个内层 http URL，并不是可直接播放的流；同一批里还会混一条
 * {@code http://www.le.com} 这类<b>网页</b>（原版拿去开 H5，不可播放）。
 *
 * <p><b>原版行为（smali 级复刻）</b>：
 * <pre>
 *   UrlParser.parse:
 *     if (url.startsWith("tvnow://")) {
 *         String inner = url.substring(8);
 *         HttpUtils.doGet(new CommonGet(inner), new UrlParser$1(this));
 *     }
 *   UrlParser$1.onHttpSuccess:
 *     LunboItem item = JSON.parseObject(body, LunboItem.class);
 *     data = item.getData();                      // 节目单
 *     offSet(data.get(0).getOffsetTs());          // 已播时长 → 起播定位
 *     ArrayList&lt;String&gt; src = getSourceByLunboItem(item);
 *     if (src.size() &gt; 0) parse(src.get(0));       // 递归（内层可能又是别的协议）
 *   getSourceByLunboItem:   ← ★ 完整语义（2026-10-07 从 smali 逐行校订）
 *     DataBean d = item.getData().get(0);         // 只取当前正在播的那一条
 *     List&lt;VideosBean&gt; all = d.getAllSitesVideoHtml();
 *     List&lt;VideosBean&gt; vid = d.getVideos();
 *     if (all 非空)      for (v : all) list.add(v.getUrl() + "#" + v.getTag());
 *     else if (vid 非空) for (v : vid) list.add(v.getUrl() + "#" + v.getTag());
 *     else { list.add(d.getVideoHtml()); isNeedParserAgain = true; }   // 网页 → 交 JS 再解析
 *   UrlParser$1.onHttpSuccess:
 *     if (!isNeedParserAgain) parseCallback.onSuccess(list);  // ★ 把【全部】候选交给播放器
 * </pre>
 *
 * <p><b>实测</b>（2026-10-06，{@code tvId=600455} 电影-科幻）：
 * <pre>
 *   {"msg":"","data":[{"offsetTs":654,"videoType":"M","tvName":"科幻",
 *      "videos":[{"tag":"高清","url":"https://hlsbkmgspvod.miguvideo.com/.../5105066729_5103698808_56.mp4"}],
 *      "id":"2585083","title":"蚁人与黄蜂女：量子狂潮"}, …]}
 * </pre>
 *
 * <p><b>红线</b>：单次 GET、不重试、不并发；异常一律返回 null 交给上层降级。
 */
public final class CarouselApi {

    private static final String TAG = "MiguCarousel";

    /** 轮播协议前缀（原版 {@code Constants.YUNTU_LUNBOSOURCE_HEADER}） */
    public static final String SCHEME = "tvnow://";

    /**
     * 【2026-10-07 补齐旁路①】回看协议前缀
     * （原版 {@code UrlParser.YUNTU_LUNBOLBK_HEADER = "lunbolbk://"}）。
     *
     * <p>{@code lunbolbk://<节目pid>} → {@code GET <USER>/p/get_carousel_video?pid=<pid>}
     * （原版 {@code feature_jsparser/api/CarouselVideo}，host = {@code ServiceHostManager.user_service}）。
     */
    public static final String SCHEME_LB = "lunbolbk://";

    /**
     * 【2026-10-07 补齐旁路②】回看解析串前缀
     * （原版 {@code lunbo.js::yuntuLbParsePlay}，路由由 {@code yt_source_root.js} 的
     * {@code url.indexOf('lunboParser')} 命中）。
     *
     * <p>形如 {@code lunboParser_<tvId>_<offset>_&?start=<ts>&end=<ts>&}。
     */
    public static final String SCHEME_LUNBO_PARSER = "lunboParser_";

    /**
     * 回看接口 host。
     *
     * <p>实测（2026-10-07，单次 GET）：{@code http://user.xiaoyouzb.cn/p/get_carousel_video?pid=2585083}
     * → 200 + {@code {"videos":[{"tag":"高清","url":"…"}]}}；
     * 而原版 smali 常量 {@code user_service = http://nowtv.xiaoyouzb.cn} 实测返回
     * 垃圾文本（{@code hello aaaa}）—— 故此处用实测可用的 {@code user.xiaoyouzb.cn}。
     */
    // 【2026-10-15 密文化】host 原为 public static final String —— 编译期常量会被**内联到
    // 每个使用点**，dex 里处处可见。改为惰性 getter，明文只存在于 SO（cpp/secstore.cpp）。
    // ⚠️ 惰性解密铁律：不可写进 static final / <clinit>（SecurityCore.init() 需 loadLibrary，会卡线程）。
    private static volatile String sHostUser, sHostUserNet, sHostNowtv;

    private static String soApi(int id) {
        String d = SecurityCore.secureString(id);
        return d == null ? "" : d;
    }

    /** 回看接口 host（原常量 HOST_USER）。解密失败返回空串 → 上层得到一次失败请求并降级。 */
    public static String hostUser() {
        String v = sHostUser;
        if (v == null) v = sHostUser = soApi(SecurityCore.C_API_USER_CN);
        return v;
    }

    /** 回看（look-back）端点 host（原常量 HOST_USER_NET，lunbo.js 线路） */
    public static String hostUserNet() {
        String v = sHostUserNet;
        if (v == null) v = sHostUserNet = soApi(SecurityCore.C_API_USER_NET);
        return v;
    }

    /** 原版多天节目单接口 host（原常量 HOST_NOWTV，program_v3.php 用） */
    private static String hostNowtv() {
        String v = sHostNowtv;
        if (v == null) v = sHostNowtv = soApi(SecurityCore.C_API_NOWTV);
        return v;
    }

    /**
     * 回看（look-back）端点 host —— {@code lunbo.js} 里<b>硬编码</b>的是
     * {@code http://user.xiaoyouzb.net}（见其 {@code yuntuLbParsePlay}），与 Java 侧
     * 使用的 {@code .cn} 是两套域名，实测两者都能通（200）。
     * <p>【2026-10-15 密文化】原为 public static final 明文常量，现改为 {@link #hostUserNet()}。
     */

    /** 解析结果：真实播放地址 + 起播定位 */
    public static final class Resolution {
        /** 可直接播放的地址（m3u8 / mp4 / 也可能又是其它协议）—— 已过滤，取首选 */
        public final String url;
        /** 当前节目已播秒数（原版 offSet 回调值），用于起播定位 */
        public final int offsetSec;
        /** 节目名（便于日志） */
        public final String title;

        /**
         * 该节目的<b>全部</b>候选地址（保序，index 0 为首选）。
         *
         * <p>原版 {@code UrlParser.getSourceByLunboItem} 会把 {@code allSitesVideoHtml}
         * （优先）或 {@code videos} 里的<b>每一条</b>都塞进 {@code ArrayList} 交给
         * {@code ParseCallback.onSuccess(List)}，播放器再逐条回退；
         * 早期实现只取 {@code videos[0]}，等于把原版的「多源回退」丢掉了 —— 这是
         * 「同分组里有的节目能播、有的不能播」的代码侧原因之一。
         */
        public final java.util.List<String> candidates;

        /**
         * 当前节目的 {@code id}（原版 {@code PlayerActivity} 里
         * {@code programItem.getPid()} 的来源）。
         *
         * <p>用途：轮播首选全部命中黑洞时，用它拼 {@code lunbolbk://<id>} 走旁路①再试一次
         * （见 {@link #resolveLookBack(String)}）。为空表示服务端未给 id。
         */
        public final String programId;

        /**
         * 轮播源 {@code tvId}（从内层 URL 的 {@code ?tvId=} 取）。
         * 供回看旁路② {@code get_carousel_video_for_lb} 使用。
         */
        public final String carouselTvId;

        /** 当前节目的时段起点（秒；0=未知）—— 回看旁路②的 {@code start} 参数 */
        public final long startTs;

        /** 当前节目的时段终点（秒；0=未知）—— 回看旁路②的 {@code end} 参数 */
        public final long endTs;

        Resolution(String url, int offsetSec, String title, java.util.List<String> candidates) {
            this(url, offsetSec, title, candidates, "", "", 0L, 0L);
        }

        Resolution(String url, int offsetSec, String title,
                   java.util.List<String> candidates, String programId) {
            this(url, offsetSec, title, candidates, programId, "", 0L, 0L);
        }

        Resolution(String url, int offsetSec, String title,
                   java.util.List<String> candidates, String programId,
                   String carouselTvId, long startTs, long endTs) {
            this.url = url;
            this.offsetSec = Math.max(0, offsetSec);
            this.title = title == null ? "" : title;
            this.candidates = candidates == null
                    ? java.util.Collections.emptyList() : candidates;
            this.programId = programId == null ? "" : programId;
            this.carouselTvId = carouselTvId == null ? "" : carouselTvId;
            this.startTs = Math.max(0L, startTs);
            this.endTs = Math.max(0L, endTs);
        }

        /** 是否「所有候选都在已知黑洞主机上」（此时值得走旁路再试一次） */
        public boolean allCandidatesDead() {
            if (candidates.isEmpty()) return false;
            for (String c : candidates) {
                if (!isKnownDead(c)) return false;
            }
            return true;
        }

        /** 【2026-10-08】换线：同字段复制，仅替换 {@code url}（DNS 探活/候选轮换后用）。 */
        public Resolution withUrl(String newUrl) {
            return new Resolution(newUrl == null ? url : newUrl, offsetSec, title,
                    candidates, programId, carouselTvId, startTs, endTs);
        }

        @Override
        public String toString() {
            return "offset=" + offsetSec + "s title=" + title + " url=" + url
                    + " 候选=" + candidates.size() + "条";
        }
    }

    /** 域名 DNS 探活结果缓存（进程级：true=可解析 / false=确认失效），避免反复查询。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> sDnsAliveCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 判断一个 URL 的主机名当前是否可被 DNS 解析。
     *
     * <p><b>纯本机查询，零 HTTP 请求</b>（不触碰任何 CDN/服务端 —— 红线友好）；
     * 结果进程级缓存（域名失效/有效状态在会话期内基本不变）。
     * 主机名取不到/URL 结构异常时保守返回 {@code true}（保持原行为）。
     */
    public static boolean isHostResolvable(String url) {
        String host = hostOf(url);
        if (TextUtils.isEmpty(host)) return true;
        Boolean cached = sDnsAliveCache.get(host);
        if (cached != null) return cached;
        boolean alive;
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            alive = addrs != null && addrs.length > 0;
        } catch (Throwable t) {
            alive = false;
            LogBridge.w(TAG, "候选域名 DNS 失效：" + host);
        }
        sDnsAliveCache.put(host, alive);
        return alive;
    }

    /**
     * ★【2026-10-08】按序取第一条「域名可解析」的候选地址 —— 起播前的 DNS 探活换线。
     *
     * <p>背景（2026-10-07 热播剧集取证，tvId=600470）：轮播 {@code videos[]} 常是第三方
     * 资源站 CDN，服务端排序第一条可能域名已死（{@code cdn.wlcdn99.com} 已无 A 记录）——
     * 早期实现固定播第一条，ExoPlayer DNS 失败后自动重试仍打同一条死域名 → 必失败
     * （而候选[1..5] 域名均活着）。原版语义是把全部候选交播放器逐条回退；本方法在
     * 起播<b>前</b>用纯 DNS 查询完成同样的过滤，<b>不产生任何对 CDN 的请求</b>。
     *
     * <p><b>⚠️ 含阻塞 DNS 查询，禁止在主线程调用</b> —— 主线程请用
     * {@link #pickResolvableCandidateCached}（只读缓存）+ {@link #warmHostAliveAsync}（预热）。
     *
     * @param candidates 候选地址（保序）
     * @return 首个域名可解析的候选；入参为空返回 {@code null}；全部失效返回第一条
     *         （沿用首选，保持原失败语义）
     */
    public static String pickResolvableCandidate(java.util.List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        String fallback = null;
        for (String c : candidates) {
            if (TextUtils.isEmpty(c)) continue;
            if (fallback == null) fallback = c;
            if (isHostResolvable(c)) return c;
        }
        return fallback;
    }

    /**
     * ★【2026-10-08】{@link #pickResolvableCandidate} 的<b>只读缓存版</b>（主线程安全）。
     *
     * <ul>
     *   <li>所选线路已知活着 → 返回所选（不换线）；</li>
     *   <li>所选线路已知失效 → 返回第一条已知活着的候选；全已知且全死 → 返回所选；</li>
     *   <li>所选线路<b>无缓存记录</b> → 返回 {@code null}（调用方照常起播所选线路，
     *       并应调用 {@link #warmHostAliveAsync} 预热，供下次秒判 / 失效接管）。</li>
     * </ul>
     */
    public static String pickResolvableCandidateCached(java.util.List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        String wanted = null;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (String c : candidates) {
            if (TextUtils.isEmpty(c)) continue;
            if (wanted == null) {
                wanted = c;
            } else {
                rest.add(c);
            }
        }
        if (wanted == null) return null;
        Boolean wantAlive = hostAliveCached(wanted);
        if (wantAlive == null) return null;                 // 无信息 → 不动，交给预热
        if (wantAlive) return wanted;                       // 已知活着 → 原样
        // 所选已知失效 → 找第一条已知活着的
        for (String c : rest) {
            Boolean alive = hostAliveCached(c);
            if (alive != null && alive) return c;
        }
        return wanted;                                      // 全死/未知 → 沿用所选
    }

    /**
     * ★【2026-10-08】只读缓存查询：域名是否可解析。
     *
     * @return {@code TRUE}=已知可解析 / {@code FALSE}=已知失效 / {@code null}=从未查过。
     *         <b>任何线程可调用、零阻塞</b>（纯内存 map 读取）。
     */
    public static Boolean hostAliveCached(String url) {
        String host = hostOf(url);
        if (TextUtils.isEmpty(host)) return Boolean.TRUE;
        return sDnsAliveCache.get(host);
    }

    /** DNS 预热单线程执行器（串行、守护线程；候选通常 ≤ 6 条，一条域名一次系统查询） */
    private static final java.util.concurrent.ExecutorService sDnsWarmExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Migu-DnsWarm");
                t.setDaemon(true);
                return t;
            });

    /**
     * ★【2026-10-08】后台异步预热候选域名的 DNS 探活缓存（主线程安全）。
     *
     * <p>对未缓存的域名逐个做一次系统 DNS 查询（<b>纯本机查询，零 HTTP 请求</b>，
     * 串行、不重试），结果写入 {@link #sDnsAliveCache}。预热完成后回调
     * {@code onDone}（在 DNS 线程回调，调用方如需碰 UI/播放器须自行 post 到主线程）。
     *
     * @param urls   候选地址列表（内部按 host 去重、跳过已缓存项）
     * @param onDone 预热完成回调（可为 null）
     */
    public static void warmHostAliveAsync(java.util.List<String> urls, Runnable onDone) {
        if (urls == null || urls.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        java.util.List<String> hosts = new java.util.ArrayList<>();
        for (String u : urls) {
            if (TextUtils.isEmpty(u)) continue;
            String h = hostOf(u);
            if (!TextUtils.isEmpty(h) && !sDnsAliveCache.containsKey(h) && !hosts.contains(h)) {
                hosts.add(h);
            }
        }
        if (hosts.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        sDnsWarmExecutor.execute(() -> {
            try {
                for (String h : hosts) {
                    boolean alive;
                    try {
                        java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(h);
                        alive = addrs != null && addrs.length > 0;
                    } catch (Throwable t) {
                        alive = false;
                        LogBridge.w(TAG, "候选域名 DNS 失效（预热）：" + h);
                    }
                    sDnsAliveCache.put(h, alive);
                }
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }

    /** {@link #warmHostAliveAsync(List, Runnable)} 无回调便捷重载 */
    public static void warmHostAliveAsync(java.util.List<String> urls) {
        warmHostAliveAsync(urls, null);
    }

    /**
     * 轮播节目单里的<b>单条节目</b>（EPG 数据源）。
     *
     * <p>来源：{@code /p/get_current_carousel} 响应 {@code data[]} 的每一条 —— 实测该数组
     * 带回<b>整段时段表</b>（当前节目 + 后续节目），每条都含 {@code title / startTs /
     * endTs / offsetTs / id}，正好是 EPG 所需的「节目名 + 时段 + 回看 id」。
     *
     * <pre>
     *   {"msg":"","data":[
     *     {"offsetTs":5323,"tvName":"青春","endTs":1791307646,"startTs":1791301285,
     *      "videos":[…],"id":"2584962","title":"会考试的猛犸象"},
     *     {"offsetTs":0,"tvName":"青春","endTs":1791313005,"startTs":1791307647,
     *      "videos":[…],"id":"2584963","title":"我的青春不再见"}],
     *    "error_code":1}
     * </pre>
     */
    public static final class Program {
        /** 节目名 */
        public final String title;
        /** 时段起点（Unix 秒；0=未知） */
        public final long startTs;
        /** 时段终点（Unix 秒；0=未知） */
        public final long endTs;
        /** 已播秒数（>0 表示「正在播」这一条） */
        public final int offsetSec;
        /** 节目 id（= 回看 {@code lunbolbk://<pid>} 的 pid；空=服务端未给） */
        public final String programId;

        public Program(String title, long startTs, long endTs, int offsetSec, String programId) {
            this.title = title == null ? "" : title;
            this.startTs = Math.max(0L, startTs);
            this.endTs = Math.max(0L, endTs);
            this.offsetSec = Math.max(0, offsetSec);
            this.programId = programId == null ? "" : programId;
        }

        @Override
        public String toString() {
            return title + "(" + startTs + "~" + endTs + (offsetSec > 0 ? " 已播" + offsetSec + "s" : "") + ")";
        }
    }

    /**
     * 最近一次成功解析到的轮播节目单，按 {@code tvId} 缓存。
     *
     * <p><b>零额外请求</b>：{@link #resolveInner} 本来就要请求
     * {@code /p/get_current_carousel}，这里只是把它响应里<b>已经带回来</b>的整段
     * {@code data[]} 顺手留下，供 EPG 使用；不会为此再发任何请求。
     */
    private static final Map<String, List<Program>> sProgramCache = new ConcurrentHashMap<>();

    /** 取缓存的轮播节目单；没有则返回 {@code null}（绝不发请求）。 */
    public static List<Program> getCachedPrograms(String tvId) {
        if (TextUtils.isEmpty(tvId)) return null;
        List<Program> list = sProgramCache.get(tvId.trim());
        return (list == null || list.isEmpty()) ? null : list;
    }

    /**
     * 从<b>已缓存</b>节目单里取「另一条节目」的回看 pid（典型 = 下一条）。
     * 【2026-10-07】剧场组当前节目全黑洞时的最后兜底：同一频道下一条节目若已获
     * GITV 授权（videoHtml=zgyj_vod），其回看 pid 可取到可播地址（实测定量：
     * pid=2603967 → zgyj_vod → contentId=clear/2/53426/246705500 与原版实播一致）。
     * 只读缓存，不发任何请求；没有则返回空串。
     */
    public static String nextProgramPid(String tvId, String currentPid) {
        List<Program> list = getCachedPrograms(tvId);
        if (list == null) return "";
        for (Program p : list) {
            if (p == null || TextUtils.isEmpty(p.programId)) continue;
            if (p.programId.equals(currentPid)) continue;
            return p.programId;
        }
        return "";
    }

    /** 原版多天节目单接口（2026-10-07 抓包定性：nowtv.xiaoyouzb.cn/program_v3.php）。
     *  <p>【2026-10-15 密文化】原为 public static final 明文常量，现改为 {@link #hostNowtv()}。 */

    /** program_v3 每频道每天至多尝试 1 次（进程级去重） */
    private static final java.util.Set<String> sV3FetchedToday =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * ★【2026-10-07】拉取「全天节目单」并覆盖缓存（原版 EPG 面板的数据源）。
     * <p>GET {@code nowtv.xiaoyouzb.cn/program_v3.php?date=<yyyyMMdd>&tv_id=<tvId>}，
     * 响应体 = 服务端标准变体加密（{@code 删前2字符 + 删第20位字符} 后 Base64），
     * 明文为 JSON 数组：{@code [{t:标题, st/et:起止Unix秒, showTime:"HH:mm", pid:回看id}]}。
     * <p>请求经济性：每频道每天至多 1 次（已有 ≥5 条的缓存则直接跳过，零请求）。
     * 成功后覆盖 {@code sProgramCache} —— EPG 面板/回看按钮与原版一致（全天带 pid）。
     *
     * @return 拉到的节目条数（跳过/失败返回 -1）
     */
    public static int ensureDaySchedule(String tvId) {
        if (TextUtils.isEmpty(tvId)) return -1;
        tvId = tvId.trim();
        List<Program> cached = sProgramCache.get(tvId);
        if (cached != null && cached.size() >= 5) return -1;   // 已是全天单，零请求
        if (sV3FetchedToday.contains(tvId)) return -1;         // 今天已试过，不重复
        String date = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        String body = httpGet(hostNowtv() + "/program_v3.php?date=" + date + "&tv_id=" + tvId);
        sV3FetchedToday.add(tvId);
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "program_v3 无响应 tvId=" + tvId);
            return -1;
        }
        try {
            // 服务端标准变体解包：删前 2 字符 → 删第 20 位字符 → Base64 → UTF-8
            String s = body.trim();
            if (s.length() > 24) {
                s = s.substring(2);
                s = s.substring(0, 20) + s.substring(21);
            }
            byte[] raw = android.util.Base64.decode(
                    s + (s.length() % 4 == 0 ? "" : "====".substring(s.length() % 4)),
                    android.util.Base64.DEFAULT);
            JSONArray arr = new JSONArray(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            List<Program> list = new ArrayList<>();
            long now = System.currentTimeMillis() / 1000L;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = o.optString("t", "").trim();
                if (TextUtils.isEmpty(title)) continue;
                long st = o.optLong("st", 0L);
                long et = o.optLong("et", 0L);
                int offset = (st > 0 && now >= st && (et <= 0 || now < et))
                        ? (int) Math.max(0, now - st) : 0;
                list.add(new Program(title, st, et, offset, o.optString("pid", "")));
            }
            if (list.isEmpty()) return -1;
            sProgramCache.put(tvId, list);
            LogBridge.i(TAG, "program_v3 全天节目单 tvId=" + tvId + " 共 "
                    + list.size() + " 条（date=" + date + "）");
            return list.size();
        } catch (Throwable t) {
            LogBridge.w(TAG, "program_v3 解析失败 tvId=" + tvId + "："
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return -1;
        }
    }

    /**
     * 【2026-10-08】多天节目单缓存（EPG 面板用）：key = {@code tvId|yyyyMMdd}。
     * 与 {@link #sProgramCache}（播放兜底用，永远只放「当前轮播日」）分开存储，互不干扰。
     */
    private static final Map<String, List<Program>> sDayScheduleCache = new ConcurrentHashMap<>();

    /** program_v3 每「频道×天」至多尝试 1 次（进程级去重，失败不重试 —— 红线）。 */
    private static final java.util.Set<String> sV3FetchedKeys =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** 取<b>已缓存</b>的指定日期节目单；没有则返回 {@code null}（绝不发请求）。 */
    public static List<Program> getCachedDaySchedule(String tvId, String dateYmd) {
        if (TextUtils.isEmpty(tvId) || TextUtils.isEmpty(dateYmd)) return null;
        List<Program> l = sDayScheduleCache.get(tvId.trim() + "|" + dateYmd.trim());
        return (l == null || l.isEmpty()) ? null : l;
    }

    /**
     * ★【2026-10-08】按日期拉取「全天节目单」—— EPG 多天面板（过去7天/未来7天）数据源。
     * <p>GET {@code nowtv.xiaoyouzb.cn/program_v3.php?date=<yyyyMMdd>&tv_id=<tvId>}，
     * 响应体与 {@link #ensureDaySchedule(String)} 同款变体加密。原版 EPG 面板即按天调此接口
     * 取多天节目单（2026-10-07 抓包：10/02~10/08 多天均为该接口返回）。
     * <p><b>请求经济性（红线）</b>：每「频道×天」至多 1 次（内存缓存 + 进程级去重），
     * 单次串行、失败不重试、不并发；与原版行为一致。
     *
     * @param tvId    咪咕频道 tvId
     * @param dateYmd {@code yyyyMMdd}（含过去日期）
     * @return 节目列表；无缓存且未拉到 / 今天已试过 / 解析失败 → {@code null}
     */
    public static List<Program> fetchDaySchedule(String tvId, String dateYmd) {
        if (TextUtils.isEmpty(tvId) || TextUtils.isEmpty(dateYmd)) return null;
        tvId = tvId.trim();
        dateYmd = dateYmd.trim();
        String key = tvId + "|" + dateYmd;
        List<Program> cached = sDayScheduleCache.get(key);
        if (cached != null) return cached;
        if (!sV3FetchedKeys.add(key)) return null;   // 该频道该天已试过，不重复发请求
        String body = httpGet(hostNowtv() + "/program_v3.php?date=" + dateYmd + "&tv_id=" + tvId);
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "program_v3 无响应 tvId=" + tvId + " date=" + dateYmd);
            return null;
        }
        List<Program> list = parseProgramV3(body);
        if (list != null && !list.isEmpty()) {
            sDayScheduleCache.put(key, list);
            LogBridge.i(TAG, "program_v3 全天节目单 tvId=" + tvId + " 共 "
                    + list.size() + " 条（date=" + dateYmd + "）");
        }
        return list;
    }

    /**
     * program_v3 响应体解包+解析（服务端标准变体加密：{@code 删前2字符 + 删第20位字符} 后 Base64）。
     *
     * @return 节目列表（可能为空）；解析失败返回 {@code null}
     */
    private static List<Program> parseProgramV3(String body) {
        try {
            String s = body.trim();
            if (s.length() > 24) {
                s = s.substring(2);
                s = s.substring(0, 20) + s.substring(21);
            }
            byte[] raw = android.util.Base64.decode(
                    s + (s.length() % 4 == 0 ? "" : "====".substring(s.length() % 4)),
                    android.util.Base64.DEFAULT);
            JSONArray arr = new JSONArray(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            List<Program> list = new ArrayList<>();
            long now = System.currentTimeMillis() / 1000L;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = o.optString("t", "").trim();
                if (TextUtils.isEmpty(title)) continue;
                long st = o.optLong("st", 0L);
                long et = o.optLong("et", 0L);
                int offset = (st > 0 && now >= st && (et <= 0 || now < et))
                        ? (int) Math.max(0, now - st) : 0;
                list.add(new Program(title, st, et, offset, o.optString("pid", "")));
            }
            return list.isEmpty() ? null : list;
        } catch (Throwable t) {
            LogBridge.w(TAG, "program_v3 解析失败："
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    /**
     * 从<b>已缓存</b>节目单挑「回看候选 pid」序列：优先下一条未播的（预约式），
     * 再按时间逆序补已结束的（回看，最近的优先）。最多 3 条，调用方逐条尝试。
     */
    public static List<String> lookBackPidCandidates(String tvId, String currentPid) {
        List<String> out = new ArrayList<>();
        List<Program> list = getCachedPrograms(tvId);
        if (list == null) return out;
        long now = System.currentTimeMillis() / 1000L;
        for (Program p : list) {                       // 下一条未播
            if (p == null || TextUtils.isEmpty(p.programId)) continue;
            if (p.programId.equals(currentPid)) continue;
            if (p.startTs > now) { out.add(p.programId); break; }
        }
        for (int i = list.size() - 1; i >= 0 && out.size() < 3; i--) {  // 已结束逆序
            Program p = list.get(i);
            if (p == null || TextUtils.isEmpty(p.programId)) continue;
            if (p.programId.equals(currentPid) || out.contains(p.programId)) continue;
            if (p.endTs > 0 && p.endTs < now) out.add(p.programId);
        }
        return out;
    }

    /** 把 {@code data[]} 全量转成 {@link Program} 并缓存（供 EPG 使用）。 */
    private static void cachePrograms(JSONArray data, String tvId) {
        if (data == null || TextUtils.isEmpty(tvId)) return;
        try {
            List<Program> list = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("title", "").trim();
                if (TextUtils.isEmpty(t)) continue;
                list.add(new Program(t,
                        o.optLong("startTs", 0L),
                        o.optLong("endTs", 0L),
                        o.optInt("offsetTs", 0),
                        o.optString("id", "")));
            }
            if (!list.isEmpty()) {
                sProgramCache.put(tvId.trim(), list);
                LogBridge.i(TAG, "轮播节目单已缓存 tvId=" + tvId + " 共 " + list.size() + " 条");
            }
        } catch (Throwable ignored) {
        }
    }

    private CarouselApi() {}

    /** 是否为轮播地址（{@code tvnow://<内层URL>}） */
    public static boolean isCarouselUrl(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /**
     * 取轮播地址中「当前正在播」的那一条节目地址。
     *
     * @param carouselUrl {@code tvnow://http://...}
     * @return 解析结果；失败返回 {@code null}
     */
    public static Resolution resolve(String carouselUrl) {
        if (!isCarouselUrl(carouselUrl)) return null;
        String inner = carouselUrl.substring(SCHEME.length()).trim();
        if (TextUtils.isEmpty(inner)) {
            LogBridge.w(TAG, "轮播地址内层为空：" + carouselUrl);
            return null;
        }
        return resolveInner(inner);
    }

    /**
     * 直接按内层 URL 取当前节目。
     *
     * @param innerUrl 如 {@code http://user.xiaoyouzb.net/p/get_current_carousel?tvId=600455}
     */
    public static Resolution resolveInner(String innerUrl) {
        if (TextUtils.isEmpty(innerUrl)) return null;
        String body = httpGet(innerUrl);
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "轮播接口无响应：" + innerUrl);
            return null;
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                LogBridge.w(TAG, "轮播接口 data 为空：" + abbreviate(body));
                return null;
            }
            // 原版只取 data[0]（= 当前正在播的那一条）
            JSONObject cur = data.optJSONObject(0);
            if (cur == null) return null;

            // 【2026-10-07 诊断】打印当前节目原始 JSON（截断）——
            //   关键判据：黑洞节目（videos[]→hlsbkmgspvod）是否**同时**带可播的
            //   videoHtml（zgyj_vod→GITV），若带则应按原版优先级重新取值。
            LogBridge.i(TAG, "当前节目原始 JSON(截断)："
                    + abbreviate(String.valueOf(cur), 700));

            int offset = cur.optInt("offsetTs", 0);
            String title = cur.optString("title", "");
            String programId = cur.optString("id", "");
            // 节目时段 + 频道 tvId —— 原版 DataEntity 的 startTs / endTs，
            // 由 PlayerActivity 在「回看模式」下拼成回看请求（本次用作黑洞兜底）。
            long startTs = cur.optLong("startTs", 0L);
            long endTs = cur.optLong("endTs", 0L);
            String carouselTvId = between(innerUrl, "tvId=", "&");

            // ★ 逐字节复刻原版 getSourceByLunboItem 的三级取值：
            //   ① allSitesVideoHtml（优先，非空即用）  ② videos  ③ videoHtml（网页，需 JS 再解析）
            //   注：tvnow 响应里这两个字段是「数组」；而 get_carousel_video 旁路的同类字段
            //   可能是「字符串化数组」—— optArray 两种都吃。
            List<String> cands = new ArrayList<>();
            JSONArray allSites = optArray(cur, "allSitesVideoHtml");
            if (allSites != null && allSites.length() > 0) {
                collectUrls(allSites, cands);
                if (!cands.isEmpty()) {
                    LogBridge.i(TAG, "命中 allSitesVideoHtml → " + cands.size() + " 条候选");
                }
            }
            if (cands.isEmpty()) {
                JSONArray videos = optArray(cur, "videos");
                if (videos != null && videos.length() > 0) {
                    collectUrls(videos, cands);
                }
            }
            if (cands.isEmpty()) {
                // ★【2026-10-07 修复】原版此处是 `list.add(d.getVideoHtml());
                //   isNeedParserAgain = true;` —— 把 videoHtml 作为**候选地址**回传给
                //   JS 引擎再解析一次。videoHtml 实测有两类：
                //     ① `http://zgyj_vod/<base64>` → GITV 银河 VOD 鉴权链
                //        （央视大剧 / 剧场组，原版能播，走 other_live.js::zgyjvodParsePlay）；
                //     ② `http://www.le.com` 之类网页 → JS 路由表（letv_vod.js）。
                //   早期实现直接 `return null`，于是「只要当前节目没有 videos[] 就必失败」——
                //   这正是央视大剧/剧场组 100% 起播失败的根因
                //   （日志 `W/MiguCarousel: 当前节目无 videos`）。
                //   现在改为与 allSitesVideoHtml / videos 一致：作为候选交给解析链，
                //   由 TVPlayerManager.resolveMiguChain 负责落到真实可播地址。
                String html = cur.optString("videoHtml", "").trim();
                if (TextUtils.isEmpty(html)) {
                    LogBridge.w(TAG, "当前节目既无 videos/allSitesVideoHtml 也无 videoHtml："
                            + abbreviate(body));
                    return null;
                }
                LogBridge.i(TAG, "当前节目只有 videoHtml → 作为候选交解析链："
                        + abbreviate(html));
                cands.add(html);
            }

            // 【2026-10-08】把整段节目单缓存下来供 EPG 使用 —— 本次请求顺带完成，零额外请求。
            cachePrograms(data, carouselTvId);

            // 首选：跳过已知黑洞主机；全为黑洞时仍取首条（与「先试第一条」等价）
            String chosen = pickPlayable(cands);
            Resolution r = new Resolution(chosen, offset, title, cands, programId,
                    carouselTvId, startTs, endTs);
            LogBridge.i(TAG, "轮播当前节目 → " + r);
            if (cands.size() > 1) {
                for (int i = 0; i < cands.size(); i++) {
                    LogBridge.i(TAG, "  候选[" + i + "] host=" + hostOf(cands.get(i))
                            + (isKnownDead(cands.get(i)) ? "  ← 已知黑洞" : ""));
                }
            }
            return r;
        } catch (Throwable t) {
            LogBridge.w(TAG, "轮播响应解析失败：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 旁路①  lunbolbk://<pid>  →  /p/get_carousel_video
    // 旁路②  lunboParser_<tvId>_<offset>_&?start=..&end=..  →  /p/get_carousel_video_for_lb
    // （2026-10-07 按原版 UrlParser / lunbo.js 补齐 —— 之前完全没实现）
    // ------------------------------------------------------------------

    /** 是否回看协议 {@code lunbolbk://<pid>}（原版 {@code UrlParser$2} 分支） */
    public static boolean isLookBackUrl(String url) {
        return url != null && url.startsWith(SCHEME_LB);
    }

    /** 是否回看解析串 {@code lunboParser_<tvId>_<offset>_…}（原版 {@code lunbo.js} 分支） */
    public static boolean isLunboParserUrl(String url) {
        return url != null && url.startsWith(SCHEME_LUNBO_PARSER);
    }

    /**
     * 旁路①：{@code lunbolbk://<pid>} → {@code GET /p/get_carousel_video?pid=<pid>}。
     *
     * <p>1:1 复刻原版 {@code UrlParser.parse} 的 {@code lunbolbk://} 分支 +
     * {@code UrlParser$2.onHttpSuccess}（三级取值 {@code allSitesVideoHtml} → {@code videos}
     * → {@code videoHtml}，全部 url 交给播放器回退）。
     *
     * @param lookBackUrl {@code lunbolbk://2585083}
     */
    public static Resolution resolveLookBack(String lookBackUrl) {
        if (!isLookBackUrl(lookBackUrl)) return null;
        String pid = lookBackUrl.substring(SCHEME_LB.length()).trim();
        if (TextUtils.isEmpty(pid)) {
            LogBridge.w(TAG, "回看地址 pid 为空：" + lookBackUrl);
            return null;
        }
        return getCarouselVideo(pid, "回看pid=" + pid);
    }

    /**
     * 【2026-10-07】云图回看「合成源」协议前缀。
     *
     * <p>服务端 {@code backSourceLabels[].source} 给的是一条<b>关键字</b>
     * {@code "yuntulunbo"}（不是可直接请求的地址，而是「回看通道标记」）。
     * 本移植版把它合成为 {@code yuntulunbo://<tvId>}，作为<b>一条可选线路</b>
     * 摆进「线路」轴（{@code MiguStreamAxis} → 展示名「回看·云图」），
     * 解析时再补 EPG 时间窗：
     * <pre>
     *   yuntulunbo://&lt;tvId&gt;
     *     → get_channel_current_epg?tvId=           → st / et
     *     → lunboParser_&lt;tvId&gt;_1_&amp;?start=&lt;st&gt;&amp;end=&lt;et&gt;&amp;
     *     → GET /p/get_carousel_video_for_lb?tvId=&amp;offsert=1&amp;start=&amp;end=
     * </pre>
     */
    public static final String SCHEME_YUNTU_LB = "yuntulunbo://";

    /** 是否云图回看合成源 {@code yuntulunbo://<tvId>} */
    public static boolean isYunTuLookBackUrl(String url) {
        return url != null && url.startsWith(SCHEME_YUNTU_LB);
    }

    /** 从 {@code yuntulunbo://<tvId>} 取 tvId；非法返回 0 */
    public static int yunTuTvIdOf(String url) {
        if (!isYunTuLookBackUrl(url)) return 0;
        try {
            return Integer.parseInt(url.substring(SCHEME_YUNTU_LB.length()).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 构造回看解析串
     * {@code lunboParser_<tvId>_<offset>_&?start=<ts>&end=<ts>&}
     * ——原版 JS {@code lunbo.js::yuntuLbParsePlay} 的入参格式。
     *
     * <p>形如：{@code lunboParser_600455_1_&?start=1791301285&end=1791307646&}
     * （注意：{@code start/end} 是<b>原始秒级时间戳</b>；JS 侧再 {@code formatDateTime}
     * 转成 {@code yyyyMMddHHmmss} 才发请求，本类 {@link #resolveLunboParser} 同款处理）。
     *
     * <p>时间窗由 {@code /channel/get_channel_current_epg} 提供
     * （原版同款接口，回 {@code st/et}）。
     *
     * @param tvId    频道 tvId
     * @param offset  原版 {@code split('_')[2]}，缺省 {@code "1"}
     * @param startTs 当前节目开始（Unix 秒）
     * @param endTs   当前节目结束（Unix 秒）
     * @return 回看解析串；入参不合法返回空串
     */
    public static String buildLunboParserUrl(String tvId, String offset, long startTs, long endTs) {
        if (TextUtils.isEmpty(tvId) || startTs <= 0L || endTs <= 0L) return "";
        String off = TextUtils.isEmpty(offset) ? "1" : offset.trim();
        return SCHEME_LUNBO_PARSER + tvId.trim() + "_" + off
                + "_&?start=" + startTs + "&end=" + endTs + "&";
    }

    /**
     * 旁路②：{@code lunboParser_<tvId>_<offset>_&?start=<ts>&end=<ts>&}
     * → {@code GET http://user.xiaoyouzb.net/p/get_carousel_video_for_lb}。
     *
     * <p>1:1 复刻原版 {@code lunbo.js::yuntuLbParsePlay}：
     * <pre>
     *   tvId   = split('_')[1]
     *   offsert= split('_')[2] ?: 1
     *   start  = formatDateTime(between("?start=" ,"&"))   // → yyyyMMddHHmmss
     *   end    = formatDateTime(between("&end="  ,"&"))
     *   url    = urlRequest(user.xiaoyouzb.net/p/get_carousel_video_for_lb?tvId=..&offsert=..&start=..&end=..)
     *   return [url]      // ★ 响应体本身就是播放地址明文
     * </pre>
     */
    public static Resolution resolveLunboParser(String rawUrl) {
        if (!isLunboParserUrl(rawUrl)) return null;
        try {
            String[] parts = rawUrl.split("_");
            // parts[0]=lunboParser, parts[1]=tvId, parts[2]=offset
            String tvId = parts.length > 1 ? parts[1].trim() : "";
            String offsert = (parts.length > 2 && !TextUtils.isEmpty(parts[2].trim()))
                    ? parts[2].trim() : "1";
            if (TextUtils.isEmpty(tvId)) {
                LogBridge.w(TAG, "lunboParser 串缺 tvId：" + rawUrl);
                return null;
            }
            long startSec = parseTs(between(rawUrl, "?start=", "&"));
            long endSec = parseTs(between(rawUrl, "&end=", "&"));
            String req = hostUserNet() + "/p/get_carousel_video_for_lb"
                    + "?tvId=" + tvId + "&offsert=" + offsert
                    + "&start=" + formatDateTime(startSec)
                    + "&end=" + formatDateTime(endSec);
            // 红线：单次 GET、不重试、不并发
            String body = httpGet(req);
            if (TextUtils.isEmpty(body)) {
                LogBridge.w(TAG, "回看接口无数据（该时段无回看缓存）tvId=" + tvId);
                return null;
            }
            String url = extractUrl(body);
            if (TextUtils.isEmpty(url)) {
                LogBridge.w(TAG, "回看响应非 URL：" + abbreviate(body));
                return null;
            }
            Resolution r = new Resolution(url, 0, "回看 tvId=" + tvId,
                    java.util.Collections.singletonList(url));
            LogBridge.i(TAG, "回看旁路命中 → " + r);
            return r;
        } catch (Throwable t) {
            LogBridge.w(TAG, "回看解析失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    /**
     * {@code GET /p/get_carousel_video?pid=<pid>} 并按原版三级取值取候选。
     * 亦作为「轮播首选全命中黑洞」时的兜底（见 {@link Resolution#allCandidatesDead()}）。
     */
    public static Resolution getCarouselVideo(String pid, String label) {
        if (TextUtils.isEmpty(pid)) return null;
        String body = httpGet(hostUser() + "/p/get_carousel_video?pid=" + pid);
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "get_carousel_video 无响应 pid=" + pid);
            return null;
        }
        try {
            JSONObject root = new JSONObject(stripEncryption(body));
            List<String> cands = new ArrayList<>();
            JSONArray allSites = optArray(root, "allSitesVideoHtml");
            if (allSites != null && allSites.length() > 0) collectUrls(allSites, cands);
            if (cands.isEmpty()) {
                JSONArray videos = optArray(root, "videos");
                if (videos != null && videos.length() > 0) collectUrls(videos, cands);
            }
            if (cands.isEmpty()) {
                String html = root.optString("videoHtml", "");
                if (html.startsWith("http")) cands.add(html);
            }
            if (cands.isEmpty()) {
                LogBridge.w(TAG, "get_carousel_video 无可用直链 pid=" + pid
                        + "：" + abbreviate(stripEncryption(body)));
                return null;
            }
            String chosen = pickPlayable(cands);
            Resolution r = new Resolution(chosen, 0, label, cands, pid);
            LogBridge.i(TAG, "get_carousel_video 命中 → " + r);
            for (int i = 0; i < cands.size(); i++) {
                LogBridge.i(TAG, "  候选[" + i + "] host=" + hostOf(cands.get(i))
                        + (isKnownDead(cands.get(i)) ? "  ← 已知黑洞" : ""));
            }
            return r;
        } catch (Throwable t) {
            LogBridge.w(TAG, "get_carousel_video 解析失败 pid=" + pid + "："
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    /**
     * 回看端点：{@code GET /p/get_carousel_video_for_lb?tvId=&offsert=&start=&end=}
     * （原版 {@code lunbo.js::yuntuLbParsePlay}）。
     *
     * @param startTs 节目时段起点（秒）→ 格式化为 {@code yyyyMMddHHmmss}
     * @param endTs   节目时段终点（秒）
     */
    public static Resolution getLookBackVideo(String tvId, long startTs, long endTs) {
        if (TextUtils.isEmpty(tvId) || startTs <= 0L || endTs <= 0L) return null;
        String req = hostUserNet() + "/p/get_carousel_video_for_lb"
                + "?tvId=" + tvId + "&offsert=1"
                + "&start=" + formatDateTime(startTs)
                + "&end=" + formatDateTime(endTs);
        // 红线：单次 GET、不重试、不并发
        String body = httpGet(req);
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "回看端点无数据（该时段无回看缓存）tvId=" + tvId);
            return null;
        }
        String url = extractUrl(body);
        if (TextUtils.isEmpty(url)) {
            LogBridge.w(TAG, "回看端点响应非 URL：" + abbreviate(body));
            return null;
        }
        Resolution r = new Resolution(url, 0, "回看 tvId=" + tvId,
                java.util.Collections.singletonList(url), "", tvId, startTs, endTs);
        LogBridge.i(TAG, "回看端点命中 → " + r);
        return r;
    }

    /**
     * 轮播首选<b>全部命中黑洞</b>时的两条旁路兜底（2026-10-07 补齐）。
     *
     * <p>顺序与原版真实可用的路径一致：
     * <ol>
     *   <li>旁路① {@code /p/get_carousel_video?pid=<节目id>}；</li>
     *   <li>旁路② {@code /p/get_carousel_video_for_lb?tvId=&offsert=1&start=&end=}（回看）。</li>
     * </ol>
     *
     * <p><b>红线</b>：串行、每条最多 1 次、不重试、不并发；前一条取到可用地址即停止，
     * 不发下一条。仅当轮播候选「全黑洞」时才可能产生额外请求。
     *
     * @return 取到「非全黑洞」候选的解析结果；两条都无果返回 {@code null}
     */
    public static Resolution tryBypass(Resolution base) {
        if (base == null || !base.allCandidatesDead()) return null;
        if (!TextUtils.isEmpty(base.programId)) {
            Resolution a = getCarouselVideo(base.programId,
                    "轮播兜底·节目id=" + base.programId);
            if (a != null && !a.allCandidatesDead()) return a;
        }
        if (!TextUtils.isEmpty(base.carouselTvId) && base.startTs > 0L && base.endTs > 0L) {
            Resolution b = getLookBackVideo(base.carouselTvId, base.startTs, base.endTs);
            if (b != null && !b.allCandidatesDead()) return b;
        }
        return null;
    }

    /**
     * 兼容 {@code {"data":"<base64>","hasEncryption":true}} 包裹（与直播接口同款加密体）。
     * 明文响应原样返回。
     */
    private static String stripEncryption(String body) {
        if (TextUtils.isEmpty(body)) return body;
        String t = body.trim();
        if (!t.startsWith("{")) return body;
        try {
            JSONObject o = new JSONObject(t);
            if (o.optBoolean("hasEncryption", false)) {
                String d = o.optString("data", "");
                if (!TextUtils.isEmpty(d)) {
                    String dec = NowTvSourceApi.decodeEPG(d);
                    if (!TextUtils.isEmpty(dec)) return dec;
                }
            }
        } catch (Throwable ignored) {
            // 不是 JSON / 无加密标记 → 原样返回
        }
        return body;
    }

    /** 响应体 → 播放地址：优先认「本身就是 URL」，其次尝试从 JSON 里取第一条 url */
    private static String extractUrl(String body) {
        String t = body.trim();
        if (t.startsWith("http://") || t.startsWith("https://")) {
            int nl = t.indexOf('\n');
            return (nl > 0 ? t.substring(0, nl) : t).trim();
        }
        try {
            JSONObject o = new JSONObject(stripEncryption(t));
            List<String> cands = new ArrayList<>();
            JSONArray allSites = optArray(o, "allSitesVideoHtml");
            if (allSites != null) collectUrls(allSites, cands);
            if (cands.isEmpty()) {
                JSONArray videos = optArray(o, "videos");
                if (videos != null) collectUrls(videos, cands);
            }
            if (cands.isEmpty()) {
                String html = o.optString("videoHtml", "");
                if (html.startsWith("http")) cands.add(html);
            }
            if (!cands.isEmpty()) return pickPlayable(cands);
        } catch (Throwable ignored) {
            // 非 JSON → 落到下面返回空
        }
        return "";
    }

    /** 读一个「可能是数组、也可能是字符串化数组」的字段 */
    private static JSONArray optArray(JSONObject o, String key) {
        if (o == null || TextUtils.isEmpty(key)) return null;
        JSONArray arr = o.optJSONArray(key);
        if (arr != null) return arr;
        String raw = o.optString(key, "");
        if (TextUtils.isEmpty(raw)) return null;
        raw = raw.trim();
        if (!raw.startsWith("[")) return null;
        try {
            return new JSONArray(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 {@code a…b} 之间的子串（对应原版 JS {@code getStringBtween}） */
    private static String between(String src, String a, String b) {
        if (TextUtils.isEmpty(src)) return "";
        int i = src.indexOf(a);
        if (i < 0) return "";
        int from = i + a.length();
        int j = src.indexOf(b, from);
        return j < 0 ? src.substring(from) : src.substring(from, j);
    }

    /** 纯数字时间戳 → 秒；解析失败返回 0 */
    private static long parseTs(String s) {
        if (TextUtils.isEmpty(s)) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 秒级时间戳 → {@code yyyyMMddHHmmss}（本地时区）。
     *
     * <p>对应原版 JS {@code lunbo.js::formatDateTime} —— 它是把 {@code Date} 的
     * 本地字段拼成字符串，故这里同样用默认时区。
     */
    private static String formatDateTime(long sec) {
        if (sec <= 0L) return "0";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US);
        return f.format(new java.util.Date(sec * 1000L));
    }

    // ------------------------------------------------------------------

    /**
     * 已知「黑洞」主机：咪咕自家 DNS 把点播 VOD 主机解析到 {@code 1.1.1.2}
     * （CNAME 链末端 = {@code heidong.cdn.cmvideo.cn}），连接必然 5s 超时。
     *
     * <p>命中时仅用于<b>排序</b>：若该节目还给了别的候选地址，优先用别的；
     * 若全部命中则仍取首条（保持原版「先试第一条」的行为，不改变失败语义）。
     */
    private static final String[] KNOWN_DEAD_HOST_SUFFIX = {
            "hlsbkmgspvod.miguvideo.com",
    };

    /**
     * 是否为已知黑洞主机上的地址。
     *
     * <p>【2026-10-07 提为 public】供 {@code TVPlayerManager} 做「黑洞预判 + 快速失败」：
     * 命中时直接提示「该节目当前版权受限」，不再让播放器干等 8~45 秒黑屏；
     * {@code resolveMiguRedirect} 也据此跳过无谓的 HEAD 跳转探测。
     */
    public static boolean isKnownDeadHost(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : KNOWN_DEAD_HOST_SUFFIX) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    /** 内部别名（保持既有调用点可读） */
    private static boolean isKnownDead(String url) {
        return isKnownDeadHost(url);
    }

    /** 取 URL 的 host（无 scheme 时返回空串，不抛异常） */
    private static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            int i = url.indexOf("://");
            if (i < 0) return "";
            String rest = url.substring(i + 3);
            int slash = rest.indexOf('/');
            String host = slash < 0 ? rest : rest.substring(0, slash);
            int at = host.indexOf('@');
            if (at >= 0) host = host.substring(at + 1);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            return host;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从候选数组收集 url（保序去重，忽略空值） */
    private static void collectUrls(JSONArray arr, List<String> out) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url", "").trim();
            if (!u.isEmpty() && !out.contains(u)) out.add(u);
        }
    }

    /** 首选：第一条不命中黑洞的；全命中则回退首条 */
    private static String pickPlayable(List<String> cands) {
        for (String c : cands) {
            if (!isKnownDead(c)) return c;
        }
        return cands.get(0);
    }

    // ------------------------------------------------------------------

    private static String httpGet(String url) {
        try (Response resp = NetUtil.getInstance().syncGet(url)) {
            if (resp == null || !resp.isSuccessful() || resp.body() == null) {
                LogBridge.w(TAG, "轮播 GET 失败 code=" + (resp == null ? -1 : resp.code()));
                return "";
            }
            return resp.body().string();
        } catch (Throwable t) {
            LogBridge.w(TAG, "轮播 GET 异常 " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return "";
        }
    }

    private static String abbreviate(String s) {
        return abbreviate(s, 200);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
