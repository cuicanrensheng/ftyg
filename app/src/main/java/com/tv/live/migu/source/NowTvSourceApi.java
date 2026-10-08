package com.tv.live.migu.source;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Response;

/**
 * 「服务端下发」通道 —— 复刻畅享TV 的
 * {@code org.fungo.fungobox.api.ChannelSource} + {@code HttpUtils} 公共参数与签名。
 *
 * <p><b>接口</b>：
 * <pre>
 *   GET http://nowtv-new.xiaoyouzb.cn/channel/get_channel_source
 *       ?tvId=50661&amp;s_ver=5.0.4&amp;&lt;公共参数&gt;
 *   → { data: { tvId, path, backUrl, sourceLabels: [ { source: "miguhd://608807420", ... } ] } }
 * </pre>
 *
 * <p><b>公共参数与签名（smali 级复刻，已用真实抓包逐字节验证）</b>：
 * <pre>
 *   nwtime = (int)(System.currentTimeMillis() / 1000)      // 原版再减 networkTimeOffset，默认 0
 *   sign   = MD5("fungolive" + nwtime)
 * </pre>
 * 实测样例：{@code nwtime=1791296974} → {@code sign=22f9fdd56a69f06e33f4cd524441f62b}（一致 ✔）
 *
 * <p><b>红线约束</b>：本类所有方法均为「单次、串行、不重试」。调用方负责限频；
 * 任何异常一律返回空结果交给上层降级，绝不重试、绝不并发、绝不枚举。
 */
public final class NowTvSourceApi {

    private static final String TAG = "NowTvSource";

    /** 直播服务 host（原版 ServiceHostManager$Host.nowtv_new_service） */
    public static final String HOST = "http://nowtv-new.xiaoyouzb.cn";

    /**
     * 用户服务 host（原版 ServiceHostManager$Host.user_service）。
     *
     * <p><b>关键</b>：首页分类表 {@code /v3/conf/app_page_cate} 走的是<b>这个</b> host
     * （原版 {@code HomeTabList extends UserServer}），打到 {@link #HOST} 上会 404。
     * 实测 {@code http://user.xiaoyouzb.cn/v3/conf/app_page_cate} → 200 + 明文
     * {@code cateList}（29 项，含 id/title/type）。
     */
    public static final String HOST_USER = "http://user.xiaoyouzb.cn";

    /** 单频道取源 */
    private static final String API_CHANNEL_SOURCE = "/channel/get_channel_source";
    /**
     * 单频道「当前节目单」（原版 {@code ChannelCurrentEpg}）。
     *
     * <p>实测（2026-10-07，原版抓包 {@code orig_http/04_*.txt} 请求原文）：
     * {@code GET /channel/get_channel_current_epg?sign=…&tvId=600460&nwtime=…}
     * → 加密体 {@code {"st","et","nst","net","t","nt","tvName","tvId"}}。
     * 回看链（{@code lunboParser} → {@code /p/get_carousel_video_for_lb}）的
     * {@code start}/{@code end} 即取自这里的 {@code st/et}。
     */
    private static final String API_CHANNEL_CURRENT_EPG = "/channel/get_channel_current_epg";
    /** 分类下的频道列表（带 tagId 时返回该分类频道；不带则返回默认分类） */
    private static final String API_TAG_LIST = "/channel/get_tag_list";
    /** 首页分类表（对应原版 HomeTabList） */
    private static final String API_PAGE_CATE = "/v3/conf/app_page_cate";

    /** 分类请求之间的最小间隔（毫秒），串行限速，避免对第三方造成压力 */
    private static final long CATEGORY_INTERVAL_MS = 250L;

    /** 客户端源协议版本（原版 ChannelSource 构造时传入） */
    public static final String S_VER = "5.0.4";

    /** 渠道标记（与原版抓包一致） */
    private static final String CHANNEL = "tvbox";
    private static final int MARKET = 1;

    /**
     * 上报给服务端的「应用包名」——<b>必须写原版包名</b>，不能用 {@code ctx.getPackageName()}。
     *
     * <p>实测（2026-10-06）：{@code /v3/conf/app_page_cate} 在 {@code apppn=com.tv.live}
     * 时返回 {@code {"msg":"服务端异常","error_code":-1000}}；换成
     * {@code apppn=org.fungo.fungobox} 才返回 200 + 完整 {@code cateList}。
     * {@code /channel/get_tag_list} 虽然容错，但为一致性也统一用原版包名。
     */
    private static final String ORIGIN_PKG = "org.fungo.fungobox";

    private NowTvSourceApi() {}

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    /** 服务端下发的单条源标签（对应 SourceItem$SourceLabelsBean） */
    public static final class SourceLabel {
        public final String source;
        public final String quality;
        public final String qualityName;
        public final String tag;
        public final String from;
        public final int score;
        public final int huyaLine;
        public final int huyaBitRate;

        SourceLabel(String source, String quality, String qualityName, String tag,
                    String from, int score, int huyaLine, int huyaBitRate) {
            this.source = source;
            this.quality = quality;
            this.qualityName = qualityName;
            this.tag = tag;
            this.from = from;
            this.score = score;
            this.huyaLine = huyaLine;
            this.huyaBitRate = huyaBitRate;
        }

        /** 是否为咪咕系协议（miguhd:// / migu:// / migu1:// / miguold:// / migu_sport:// / miguNew://） */
        public boolean isMigu() {
            return source != null && source.startsWith("migu");
        }

        @Override
        public String toString() {
            return "SourceLabel{source=" + source + ", quality=" + quality
                    + ", score=" + score + ", tag=" + tag + "}";
        }
    }

    /**
     * 合成一条「虚拟源」——用于把服务端的关键字式通道（如回看关键字
     * {@code yuntulunbo}）摆进「线路」轴，供 UI 展示与手动选择。
     *
     * <p>典型：剧场组 {@code backSourceLabels=[{"source":"yuntulunbo"}]} 是一条
     * <b>通道标记</b>而非地址，直接塞给播放器无法解析；合成成
     * {@code yuntulunbo://<tvId>} 后由 {@code TVPlayerManager} 补 EPG 时间窗再解析。
     */
    public static SourceLabel synthesized(String source, String qualityName, String tag) {
        return new SourceLabel(source, "", qualityName, tag, "网络", 0, 0, 0);
    }

    /**
     * 频道「源信息」——{@code get_channel_source} 的完整结果（2026-10-07 新增）。
     *
     * <p>原实现只取 {@code sourceLabels}（直播源），把服务端<b>另外单独下发</b>的
     * 回看源整段丢掉了。实测原版 {@code tvId=600460} 响应解包原文：
     * <pre>
     *   "sourceLabels":     [ tvnow://…get_current_carousel , http://www.le.com ]   // 直播
     *   "backUrl":          "yuntulunbo"                                            // 回看关键字
     *   "backSourceLabels": [ {"source":"yuntulunbo"} ]                             // ★回看源
     * </pre>
     * 剧场组（电影/少儿/综艺/热播剧集…）的直播轮播「当前节目」候选往往全落
     * {@code hlsbkmgspvod.miguvideo.com}（=1.1.1.2 黑洞），真正能播的是这条
     * <b>回看链</b>（原版 {@code type=lookBack}）—— 见 {@link #isYunTuLookBack()}。
     */
    public static final class ChannelSourceInfo {
        /** 直播源（= {@code sourceLabels}，保序） */
        public final List<SourceLabel> sources;
        /** 回看关键字（原版 {@code backUrl}，如 {@code "yuntulunbo"}；空=无） */
        public final String backUrl;
        /** 回看源（= {@code backSourceLabels}，保序） */
        public final List<SourceLabel> backSources;

        ChannelSourceInfo(List<SourceLabel> sources, String backUrl, List<SourceLabel> backSources) {
            this.sources = sources == null ? new ArrayList<>() : sources;
            this.backUrl = backUrl == null ? "" : backUrl;
            this.backSources = backSources == null ? new ArrayList<>() : backSources;
        }

        /**
         * 该频道是否「云图轮播回看」型 —— 服务端的 {@code backUrl} / {@code backSourceLabels}
         * 指向 {@code yuntulunbo}（对应 JS {@code lunbo.js::yuntuLbParsePlay}，
         * 即 {@code lunboParser_<tvId>_<offset>_&?start=&end=} → {@code /p/get_carousel_video_for_lb}）。
         */
        public boolean isYunTuLookBack() {
            if (!TextUtils.isEmpty(backUrl)
                    && backUrl.toLowerCase().contains("yuntulunbo")) return true;
            for (SourceLabel l : backSources) {
                if (l != null && l.source != null
                        && l.source.toLowerCase().contains("yuntulunbo")) return true;
            }
            return false;
        }

        /**
         * 第一条「网页类」源（http(s) 但不像媒体流，典型 {@code http://www.le.com}）；
         * 无则空串。用于「直播轮播全黑洞」时的网页兜底（B，走 JS {@code letv_vod.js}）。
         */
        public String firstWebPageSource() {
            for (SourceLabel l : sources) {
                if (l == null || TextUtils.isEmpty(l.source)) continue;
                if (com.tv.live.migu.MiguUrlResolver.isWebPageUrl(l.source)) return l.source;
            }
            return "";
        }

        @Override
        public String toString() {
            return "sourceLabels=" + sources.size() + " backUrl=" + backUrl
                    + " backSourceLabels=" + backSources.size();
        }
    }

    /**
     * 「当前节目单」——{@code /channel/get_channel_current_epg} 的返回（2026-10-07 新增）。
     *
     * <p>原版用它给回看链提供时间窗（JS {@code lunbo.js::yuntuLbParsePlay} 的
     * {@code start}/{@code end}）。实测 {@code tvId=600460} 解包原文：
     * <pre>
     *   {"st":"1791301285","t":"会考试的猛犸象","nt":"我的青春不再见","tvName":"青春",
     *    "tvId":"600460","net":"1791313005","nst":"1791307647","et":"1791307646"}
     * </pre>
     * 与 {@code get_current_carousel} 的 {@code data[0].startTs/endTs} 完全一致（互为印证）。
     */
    public static final class CurrentEpg {
        /** 当前节目开始时间（Unix 秒） */
        public final long st;
        /** 当前节目结束时间（Unix 秒） */
        public final long et;
        /** 下一个节目开始时间（Unix 秒） */
        public final long nst;
        /** 下一个节目结束时间（Unix 秒） */
        public final long net;
        /** 当前节目名（原版 {@code t}） */
        public final String title;
        /** 下一个节目名（原版 {@code nt}） */
        public final String nextTitle;

        CurrentEpg(long st, long et, long nst, long net, String title, String nextTitle) {
            this.st = st;
            this.et = et;
            this.nst = nst;
            this.net = net;
            this.title = title == null ? "" : title;
            this.nextTitle = nextTitle == null ? "" : nextTitle;
        }

        /** 时间窗是否可用（回看链需要 st/et） */
        public boolean valid() {
            return st > 0L && et > 0L;
        }

        @Override
        public String toString() {
            return "st=" + st + " et=" + et + " nst=" + nst + " net=" + net + " t=" + title;
        }
    }

    /** 频道条目 */
    public static final class Channel {
        public final int tvId;
        public final String name;
        public final String group;

        public Channel(int tvId, String name, String group) {
            this.tvId = tvId;
            this.name = name == null ? "" : name;
            this.group = group == null ? "" : group;
        }
    }

    /**
     * 首页分类（对应 {@code /v3/conf/app_page_cate} 的 {@code data.cateList[]}）。
     *
     * <p>实测结构：{@code {"id":9,"title":"央视","icon":"...","type":"live","info1":"央视"}}。
     * {@code type} 取值：{@code live}（直播频道）/ {@code huya}（虎牙直播间）/ {@code h5}（网页）。
     * <b>title 就是 M3U 的 group-title</b>。
     */
    public static final class Category {
        public final int id;
        public final String title;
        public final String type;

        public Category(int id, String title, String type) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.type = type == null ? "" : type;
        }

        /** 是否为可直接取直播源的分类（排除 huya 直播间与 h5 网页） */
        public boolean isLive() {
            return "live".equalsIgnoreCase(type);
        }

        @Override
        public String toString() {
            return title + "(id=" + id + "," + type + ")";
        }
    }

    // ------------------------------------------------------------------
    // ① 单频道取源（链路第 1 步）
    // ------------------------------------------------------------------

    /**
     * 取某个频道（tvId）的服务端下发源列表。
     *
     * @return 源列表；失败返回空列表（不抛异常、不重试）
     */
    public static List<SourceLabel> getChannelSource(Context ctx, int tvId) {
        return getChannelSourceInfo(ctx, tvId).sources;
    }

    /**
     * 取某个频道（tvId）的<b>完整</b>源信息：直播源 + {@code backUrl} + 回看源。
     *
     * <p>【2026-10-07 新增】此前本方法只解析 {@code sourceLabels}，把服务端
     * <b>单独下发</b>的 {@code backUrl} / {@code backSourceLabels} 整段丢弃 ——
     * 剧场组（电影/少儿/综艺…）因此丢了唯一的可播路径（云图回看）。
     *
     * @return 源信息；失败返回空对象（不抛异常、不重试）
     */
    public static ChannelSourceInfo getChannelSourceInfo(Context ctx, int tvId) {
        List<SourceLabel> out = new ArrayList<>();
        List<SourceLabel> back = new ArrayList<>();
        String backUrl = "";
        if (ctx == null || tvId <= 0) return new ChannelSourceInfo(out, backUrl, back);

        Map<String, String> params = publicParams(ctx);
        params.put("tvId", String.valueOf(tvId));
        params.put("s_ver", S_VER);

        String url = buildUrl(API_CHANNEL_SOURCE, params);
        String body = httpGet(url);
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "get_channel_source 无响应 tvId=" + tvId);
            return new ChannelSourceInfo(out, backUrl, back);
        }

        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                LogBridge.w(TAG, "get_channel_source 无 data 字段 tvId=" + tvId
                        + " body=" + abbreviate(body));
                return new ChannelSourceInfo(out, backUrl, back);
            }
            backUrl = data.optString("backUrl", "").trim();
            LogBridge.i(TAG, "服务端下发 tvId=" + tvId
                    + " path=" + data.optString("path", "")
                    + " backUrl=" + abbreviate(backUrl));

            JSONArray labels = data.optJSONArray("sourceLabels");
            if (labels == null) labels = data.optJSONArray("sourceLabelList");
            parseLabels(labels, out);
            parseLabels(data.optJSONArray("backSourceLabels"), back);

            LogBridge.i(TAG, "服务端下发 tvId=" + tvId + " → 直播源 " + out.size()
                    + " 条 / 回看源 " + back.size() + " 条");
            for (int i = 0; i < out.size(); i++) {
                LogBridge.i(TAG, "  源[" + i + "] " + out.get(i));
            }
            for (int i = 0; i < back.size(); i++) {
                LogBridge.i(TAG, "  回看源[" + i + "] " + back.get(i));
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "解析 sourceLabels 失败：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
        return new ChannelSourceInfo(out, backUrl, back);
    }

    /** 解析一组 {@code SourceLabel} JSON 数组（直播源 / 回看源共用） */
    private static void parseLabels(JSONArray labels, List<SourceLabel> out) {
        if (labels == null) return;
        for (int i = 0; i < labels.length(); i++) {
            JSONObject o = labels.optJSONObject(i);
            if (o == null) continue;
            String source = o.optString("source", "").trim();
            if (source.isEmpty()) continue;
            out.add(new SourceLabel(
                    source,
                    o.optString("quality", ""),
                    o.optString("qualityName", ""),
                    o.optString("tag", ""),
                    o.optString("from", ""),
                    o.optInt("score", 0),
                    o.optInt("huyaLine", 0),
                    o.optInt("huyaBitRate", 0)));
        }
    }

    /**
     * 取单频道「当前节目单」——{@code GET /channel/get_channel_current_epg?tvId=}。
     *
     * <p>专供回看链（A）提供时间窗：{@code st/et} → {@code lunboParser} 的
     * {@code ?start=&end=}。单次 GET、不重试、不并发。
     *
     * @return 当前节目单；失败或时间窗无效返回 {@code null}
     */
    public static CurrentEpg getChannelCurrentEpg(Context ctx, int tvId) {
        if (ctx == null || tvId <= 0) return null;

        Map<String, String> params = publicParams(ctx);
        params.put("tvId", String.valueOf(tvId));

        String body = httpGet(buildUrl(API_CHANNEL_CURRENT_EPG, params));
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "get_channel_current_epg 无响应 tvId=" + tvId);
            return null;
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                LogBridge.w(TAG, "当前节目单无 data，tvId=" + tvId
                        + " body=" + abbreviate(body));
                return null;
            }
            CurrentEpg e = new CurrentEpg(
                    optLongSafe(data, "st"), optLongSafe(data, "et"),
                    optLongSafe(data, "nst"), optLongSafe(data, "net"),
                    data.optString("t", ""), data.optString("nt", ""));
            LogBridge.i(TAG, "当前节目单 tvId=" + tvId + " → " + e);
            return e.valid() ? e : null;
        } catch (Throwable t) {
            LogBridge.w(TAG, "当前节目单解析失败 tvId=" + tvId + "："
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    /** 宽松取 long（服务端把时间戳写成字符串） */
    private static long optLongSafe(JSONObject o, String key) {
        String s = o.optString(key, "");
        if (TextUtils.isEmpty(s)) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 取该频道下<b>第一个本移植版能解析</b>的源串。
     *
     * <p>服务端下发的 {@code sourceLabels[].source} 协议是<b>混合</b>的，实测（2026-10-06）：
     * <pre>
     *   tvId=50661(CCTV-1)    → miguhd://608807420                        （咪咕高清）
     *   tvId=43  (湖南卫视)   → ysp://shd//600002475//2024054803          （咪咕 ysp，走 ysp_live.js）
     *                          + http://livea-u-js1000.ichuanghi.com/...m3u8（CDN 直链）
     * </pre>
     * 因此按<b>优先级</b>挑第一个可用源（同级保持服务端顺序）：
     * <ol>
     *   <li>{@code migu*} 系协议 —— 有 Java 兜底（{@link com.tv.live.migu.MiguUrlResolver}），最稳；</li>
     *   <li>{@code tvnow://} 轮播 —— 剧场类频道；</li>
     *   <li>{@code http(s)://} 直链 —— 直接起播，无需解析；</li>
     *   <li>{@code ysp*} 系协议 —— ★<b>末位兜底</b>：走 JS（{@code ysp_live.js}）最终落央视频 CDN，实测易花屏；</li>
     *   <li>其它任意非空协议 —— 兜底返回，交给 JS 路由表试一次。</li>
     * </ol>
     *
     * <p><b>不要</b>只看 {@code startsWith("migu")}：那样大片卫视频道会被判「无源」而播不了。
     * 无 {@code migu*} 下发源的频道（如 {@code tvId=43} 湖南卫视）另由
     * {@code TVPlayerManager} 用 {@link com.tv.live.migu.MiguSourceLoader#lookupPidByName}
     * 按频道名补 {@code miguhd://<pID>} 优先起播。
     *
     * @return 源串；一条都没有时返回空串（不重试）
     */
    public static String getFirstPlayableSource(Context ctx, int tvId) {
        return pickFirstPlayable(getChannelSource(ctx, tvId));
    }

    /**
     * 【2026-10-07 抽出】从一份已下发的源列表里按优先级挑第一个可播源。
     *
     * <p>拆分动机：「频道线路选择 / 清晰度选择」两个 UI 轴需要拿到<b>完整</b>的源列表
     * （{@link #getPlayableSources}），而原来只有「挑第一条」这一个出口、列表被丢掉了。
     * 现在两者共用同一次 {@link #getChannelSource} 结果 —— <b>不额外发请求</b>。
     */
    public static String pickFirstPlayable(List<SourceLabel> labels) {
        if (labels == null || labels.isEmpty()) {
            LogBridge.w(TAG, "该频道无可播源（空列表）");
            return "";
        }

        // ① migu* 系（miguhd / migu / migu1 / miguold / migu_sport / miguNew）
        for (SourceLabel l : labels) {
            if (l.source != null && l.source.startsWith("migu")) return l.source;
        }
        // ② 轮播 / 回看类：tvnow:// 轮播、lunbolbk://<pid> 回看、lunboParser_… 回看串
        //    （剧场类频道：电影/少儿/综艺/热播剧集/央视大剧/经典韩剧/人文记录…）
        for (SourceLabel l : labels) {
            if (CarouselApi.isCarouselUrl(l.source)
                    || CarouselApi.isLookBackUrl(l.source)
                    || CarouselApi.isLunboParserUrl(l.source)) {
                return l.source;
            }
        }
        // ③ http(s) 直链 —— 但必须「看起来是流」，否则是原版拿去开 H5 的网页
        for (SourceLabel l : labels) {
            if (com.tv.live.migu.MiguUrlResolver.isHttpUrl(l.source)
                    && com.tv.live.migu.MiguUrlResolver.isStreamLike(l.source)) {
                return l.source;
            }
        }
        // ④ ysp* 系（ysp / ysp1 / ysp2）—— ★ 已降级为「末位兜底」：
        //    ysp 最终落到央视频 CDN（ysp.cctv.cn），实测湖南卫视等频道画面花屏；
        //    有 migu* 源的频道在 ① 就返回，这里只在「无 migu 源、无 tvnow、无直链」时才兜底。
        //    （无 migu 源的频道另见 TVPlayerManager：「按频道名补 miguhd://」优先于本函数返回值）
        for (SourceLabel l : labels) {
            if (l.source != null && l.source.startsWith("ysp")) return l.source;
        }
        // ⑤ 兜底：任意非空、且不是 http(s) 的未知协议（交给 JS 路由表试一次）
        for (SourceLabel l : labels) {
            if (TextUtils.isEmpty(l.source)) continue;
            if (com.tv.live.migu.MiguUrlResolver.isHttpUrl(l.source)) continue;  // 网页，跳过
            LogBridge.w(TAG, "仅剩非预期协议源 → " + l.source);
            return l.source;
        }

        LogBridge.w(TAG, "该频道无可播源，源列表=" + labels);
        return "";
    }

    /**
     * 取该频道<b>全部可播源</b>（供「频道线路选择 / 清晰度选择」两个 UI 轴使用）。
     *
     * <p>与「挑第一条」共用同一次 {@link #getChannelSource} 的结果，<b>不额外发请求</b>。
     * 过滤规则见 {@link #filterForAxis}。
     */
    public static List<SourceLabel> getPlayableSources(Context ctx, int tvId) {
        return filterForAxis(getChannelSource(ctx, tvId));
    }

    /**
     * 过滤出「可构造 UI 轴」的源：非空即可 —— <b>含网页类</b>。
     *
     * <p>【2026-10-07 语义调整】早期版本会把 {@code http://www.le.com} 这类<b>网页</b>
     * 源整条剔除，理由是"摆进 UI 只会让用户选到必然失败的项"。但对照原版实机抓包
     * （{@code tvId=600460}）：原版的 {@code get_channel_source} 同样下发这条网页源，
     * 且原版把它<b>保留为一条可手动选择的线路</b>（选中后走 JS 路由
     * {@code yt_source_root.js} → {@code letv_vod.js::letvVodParsePlay}）。
     * 这是「本轮唯一确证的原版行为差异」，故改为保留。
     *
     * <p>两条边界：
     * <ul>
     *   <li><b>自动选源不受影响</b> —— {@link #pickFirstPlayable} 仍只认
     *       {@code migu*} / 轮播 / 媒体流直链 / {@code ysp*}，网页源永远轮不到自动选；</li>
     *   <li>网页项在 {@code MiguStreamAxis} 里被归为「网页」线路，只有用户手动切到它才解析。</li>
     * </ul>
     */
    public static List<SourceLabel> filterForAxis(List<SourceLabel> labels) {
        List<SourceLabel> out = new ArrayList<>();
        if (labels == null) return out;
        for (SourceLabel l : labels) {
            if (l == null || TextUtils.isEmpty(l.source)) continue;
            out.add(l);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // ② 频道列表（服务端下发通道用）
    // ------------------------------------------------------------------

    /**
     * 拉取首页分类表（{@code HomeTabList} / {@code CategoryViewModel.getHomeTabList*}）。
     *
     * <p><b>host 是 {@link #HOST_USER}</b>（{@code user.xiaoyouzb.cn}），不是直播 host。
     * 实测响应是<b>明文</b>（无 {@code hasEncryption}），形如：
     * <pre>
     *   {"data":{"updateTime":"...","cateList":[
     *      {"id":9,"title":"央视","icon":"...","type":"live","info1":"央视","block":"...",…},
     *      {"id":291,"title":"一起看电影","type":"huya",…},
     *      … ]},"error_code":0}
     * </pre>
     *
     * <p>只保留 {@code type=live} 的分类（排除 {@code huya} 直播间与 {@code h5} 网页），
     * 并按 {@code id} 去重（服务端有多个子页签共用同一 id，如「热播剧集/都市生活」都是 222）。
     * <b>分类 title 就是最终的 group-title</b>。
     *
     * @return 分类列表（保持服务端顺序）；失败返回空列表（不重试）
     */
    public static List<Category> fetchCategories(Context ctx) {
        List<Category> out = new ArrayList<>();
        if (ctx == null) return out;

        String body = httpGet(buildUrl(HOST_USER, API_PAGE_CATE, publicParams(ctx)));
        if (TextUtils.isEmpty(body)) {
            LogBridge.w(TAG, "首页分类表为空（host=" + HOST_USER + "）");
            return out;
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            JSONArray cateList = data == null ? null : data.optJSONArray("cateList");
            if (cateList == null) {
                LogBridge.w(TAG, "首页分类表无 cateList，body=" + abbreviate(body));
                return out;
            }
            Set<Integer> seenId = new LinkedHashSet<>();
            for (int i = 0; i < cateList.length(); i++) {
                JSONObject o = cateList.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id", 0);
                String title = o.optString("title", "").trim();
                String type = o.optString("type", "").trim();
                if (id <= 0 || title.isEmpty()) continue;
                if (!"live".equalsIgnoreCase(type)) continue;      // 排除 huya / h5
                if (!seenId.add(id)) continue;                     // 同 id 子页签去重
                out.add(new Category(id, title, type));
            }
            LogBridge.i(TAG, "首页分类表 " + cateList.length() + " 项 → 直播分类 "
                    + out.size() + " 个：" + out);
        } catch (Throwable t) {
            LogBridge.w(TAG, "首页分类表解析失败：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
        return out;
    }

    /**
     * 拉取服务端下发的频道表（<b>按分类分组</b>）。
     *
     * <p>完整复刻原版首页结构：
     * <pre>
     *   ① GET user.xiaoyouzb.cn/v3/conf/app_page_cate      → cateList[{id,title,type}]
     *   ② 对每个 live 分类串行 GET nowtv-new…/channel/get_tag_list?tagId=&lt;id&gt;
     *      → [{contentId(=tvId), tvName, authorInfo.nickname, …}]
     *   ③ group = 分类 title
     * </pre>
     *
     * <p>实测（2026-10-06，channel=tvbox）：
     * <ul>
     *   <li>{@code tagId=9}(央视) → 19 条（CCTV-1…CGTN）；</li>
     *   <li>{@code tagId=250}(电影) → 12 条（科幻/漫威/动作…轮播频道）。</li>
     * </ul>
     *
     * <p><b>红线</b>：全程串行，分类之间 {@value #CATEGORY_INTERVAL_MS} ms 限速，
     * <b>单次不重试、不并发、不枚举</b>；结果进 12h 磁盘缓存，正常一天只跑一轮。
     *
     * @param maxCategories 最多取几个分类（&lt;=0 表示不限制）；超出时按服务端顺序截断
     * @return 频道列表（按 tvId 全局去重，保留首次出现的分类）；失败返回空列表
     */
    public static List<Channel> fetchChannelList(Context ctx, int maxCategories) {
        List<Channel> out = new ArrayList<>();
        if (ctx == null) return out;

        List<Category> categories = fetchCategories(ctx);
        if (categories.isEmpty()) {
            // 分类表不可用 → 退化为「单次无参查询」（服务端默认返回央视 19 条）
            LogBridge.w(TAG, "分类表不可用，退化为单次无参频道查询");
            categories = new ArrayList<>();
            categories.add(new Category(0, "", ""));
        } else if (maxCategories > 0 && categories.size() > maxCategories) {
            LogBridge.i(TAG, "分类数 " + categories.size() + " 超过上限 "
                    + maxCategories + "，按顺序截断");
            categories = new ArrayList<>(categories.subList(0, maxCategories));
        }

        Map<Integer, Boolean> seen = new LinkedHashMap<>();
        int taken = 0;
        for (Category cate : categories) {
            if (taken > 0) sleepQuietly(CATEGORY_INTERVAL_MS);   // 串行限速
            taken++;

            Map<String, String> params = publicParams(ctx);
            if (cate.id > 0) params.put("tagId", String.valueOf(cate.id));
            if (!TextUtils.isEmpty(cate.title)) params.put("tagName", cate.title);

            String body = httpGet(buildUrl(HOST, API_TAG_LIST, params));
            List<Channel> list = parseChannels(body);

            int added = 0;
            for (Channel c : list) {
                if (seen.put(c.tvId, Boolean.TRUE) == null) {
                    // 分组 = 分类标题（服务端未给分类时退回频道自带的 tagName）
                    String group = !TextUtils.isEmpty(cate.title) ? cate.title : c.group;
                    out.add(new Channel(c.tvId, c.name, group));
                    added++;
                }
            }
            LogBridge.i(TAG, "分类[" + (cate.title.isEmpty() ? "默认" : cate.title)
                    + "] tagId=" + cate.id + " 返回 " + list.size() + " 条，新增 " + added);
        }
        LogBridge.i(TAG, "服务端下发频道表共 " + out.size() + " 个（"
                + taken + " 个分类）");
        return out;
    }

    // ------------------------------------------------------------------
    // 容错解析
    // ------------------------------------------------------------------

    /** 从任意层级抽出频道对象 */
    private static List<Channel> parseChannels(String body) {
        List<Channel> out = new ArrayList<>();
        if (TextUtils.isEmpty(body)) return out;
        try {
            JSONObject root = new JSONObject(body);
            collectChannels(root, out, 0);
        } catch (Throwable t) {
            LogBridge.w(TAG, "频道解析失败：" + t.getMessage());
        }
        return out;
    }

    private static void collectChannels(Object node, List<Channel> out, int depth) {
        if (node == null || depth > 6 || out.size() > 3000) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectChannels(arr.opt(i), out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;

        int tvId = optIntAny(o, "contentId", "tvId", "tv_id", "channelId", "channel_id");
        String name = firstNonEmpty(o, "tvName", "tv_name", "channelName", "channel_name", "name");
        if (tvId > 0 && !name.isEmpty()) {
            // 注意：**不要**用 authorInfo.nickname 当分组 —— 那是「来源方/作者」
            // （央视一套 / QM工作室 / 探秘历史 …），逐频道不同，会把 19 个频道
            // 拆成 19 个分组。真正的分组由调用方按「分类 title」赋值。
            String group = firstNonEmpty(o, "tagName", "tag_name", "groupName", "group_name", "cateName");
            out.add(new Channel(tvId, name, group));
            return;
        }
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            collectChannels(o.opt(it.next()), out, depth + 1);
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!TextUtils.isEmpty(v) && !"null".equalsIgnoreCase(v)) return v;
        }
        return "";
    }

    private static int optIntAny(JSONObject o, String... keys) {
        for (String k : keys) {
            int v = o.optInt(k, 0);
            if (v > 0) return v;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 公共参数 + 签名
    // ------------------------------------------------------------------

    /** 构建公共参数（对应 HttpUtils.getQueryMap）+ nwtime + sign */
    public static Map<String, String> publicParams(Context ctx) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("version", appVersion(ctx));
        p.put("platform", "android");
        p.put("appx", "");
        p.put("apppn", ORIGIN_PKG);   // 必须原版包名，见 ORIGIN_PKG 注释
        p.put("enterprise", "0");
        p.put("channel", CHANNEL);
        p.put("market", String.valueOf(MARKET));
        p.put("os_version", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
        p.put("device_model", Build.MODEL == null ? "" : Build.MODEL);
        p.put("source", "android");
        p.put("isVip", "N");
        p.put("uid", pseudoUid(ctx));
        p.put("oaid", "");

        int nwtime = currentTime();
        p.put("nwtime", String.valueOf(nwtime));
        p.put("sign", timeSign(nwtime));
        return p;
    }

    /** 对应 SignUtils.getCurrentTime()：秒级时间戳 - networkTimeOffset(默认 0) */
    public static int currentTime() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    /** 对应 SignUtils.getTimeSign("fungolive", t) = MD5("fungolive" + t) */
    public static String timeSign(int nwtime) {
        return md5Hex("fungolive" + nwtime);
    }

    /**
     * 伪 uid —— AH-main 无畅享TV 的账号体系，这里由设备标识派生一个稳定的数字串，
     * 避免每次请求都不同。若服务端严格校验 uid，此处可能需要替换为真实值。
     */
    private static String pseudoUid(Context ctx) {
        try {
            String seed = Build.FINGERPRINT + "|" + ctx.getPackageName();
            String hex = md5Hex(seed);
            // 转成 12 位十进制数字串（与原版 uid 形态一致）
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hex.length() && sb.length() < 12; i++) {
                char c = hex.charAt(i);
                sb.append(Character.digit(c, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "100000000000";
        }
    }

    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    private static String buildUrl(String api, Map<String, String> params) {
        return buildUrl(HOST, api, params);
    }

    /** 指定 host 构建 URL（分类表走 {@link #HOST_USER}，其余走 {@link #HOST}） */
    private static String buildUrl(String host, String api, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(host).append(api).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String httpGet(String url) {
        try (Response resp = NetUtil.getInstance().syncGet(url)) {
            if (resp == null || !resp.isSuccessful() || resp.body() == null) {
                LogBridge.w(TAG, "GET 失败 code=" + (resp == null ? -1 : resp.code()));
                return "";
            }
            return normalizeEnvelope(resp.body().string());
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET 异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 响应解包（复刻 RequestHandler.requestSuccess 的 hasEncryption 分支）
    // ------------------------------------------------------------------

    /**
     * 服务端所有接口的响应外层形如
     * {@code {"code":0,"data":"<加密串>","hasEncryption":true,"success":true}}。
     *
     * <p>原版 {@code RequestHandler.requestSuccess}（Kotlin 源码 line 119-140）在
     * {@code hasEncryption=true} 时：取 {@code data} 字符串 → {@link #decodeEPG(String)} →
     * 若以 <code>{</code> 开头回填为 JSONObject、<code>[</code> 开头回填为 JSONArray，
     * 否则回填明文串；解码为空则回填 null。此处 1:1 复刻，把 {@code data}
     * 原地换成明文 JSON，调用方即可按普通 JSON 读取。
     */
    static String normalizeEnvelope(String body) {
        if (TextUtils.isEmpty(body) || !body.contains("hasEncryption")) return body;
        try {
            JSONObject root = new JSONObject(body);
            if (!root.optBoolean("hasEncryption", false)) return body;
            Object dataObj = root.opt("data");
            if (!(dataObj instanceof String)) return body;

            String decoded = decodeEPG((String) dataObj);
            if (TextUtils.isEmpty(decoded)) {
                root.put("data", JSONObject.NULL);
                LogBridge.w(TAG, "响应 hasEncryption=true 但解码为空");
                return root.toString();
            }
            String t = decoded.trim();
            if (t.startsWith("{")) {
                root.put("data", new JSONObject(t));
            } else if (t.startsWith("[")) {
                root.put("data", new JSONArray(t));
            } else {
                root.put("data", t);
            }
            LogBridge.i(TAG, "响应解包 hasEncryption=true，data "
                    + ((String) dataObj).length() + " → " + decoded.length() + " 字节");
            return root.toString();
        } catch (Throwable t) {
            LogBridge.w(TAG, "响应解包失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return body;
        }
    }

    /**
     * 1:1 复刻 {@code RequestHandler.decodeEPG}（smali 55-89 / Kotlin 258-264）：
     * <pre>
     *   StringBuffer buf = new StringBuffer(content);
     *   buf.delete(0, 2);        // 去掉 2 字符前缀
     *   buf.deleteCharAt(20);    // 再删掉下标 20 处的干扰字符
     *   byte[] b = Base64.decode(buf.toString(), Base64.DEFAULT);
     *   return new String(b, UTF_8);
     * </pre>
     * 实测 tvId=50661 的 data 解出
     * {@code {"path":"CCTV-CCTV1","sourceLabels":[{"source":"miguhd://608807420",...}]}}，逐字节一致 ✔
     */
    public static String decodeEPG(String content) {
        if (TextUtils.isEmpty(content)) return null;
        try {
            StringBuilder sb = new StringBuilder(content);
            sb.delete(0, 2);
            if (sb.length() > 20) sb.deleteCharAt(20);
            String b64 = sb.toString();
            byte[] bytes;
            try {
                bytes = Base64.decode(b64, Base64.DEFAULT);
            } catch (Throwable bad) {
                // 容错：长度非 4 倍数时补齐 '=' 再解
                StringBuilder pad = new StringBuilder(b64);
                if (pad.length() % 4 == 1) pad.setLength(pad.length() - 1);
                while (pad.length() % 4 != 0) pad.append('=');
                bytes = Base64.decode(pad.toString(), Base64.DEFAULT);
            }
            return new String(bytes, "UTF-8");
        } catch (Throwable t) {
            LogBridge.w(TAG, "decodeEPG 失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String md5Hex(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
