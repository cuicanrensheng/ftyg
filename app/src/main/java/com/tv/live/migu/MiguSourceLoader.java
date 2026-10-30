package com.tv.live.migu;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.text.TextUtils;

import com.tv.live.migu.source.NowTvSourceApi;
import com.tv.live.util.LogBridge;
import com.tv.live.util.NetUtil;

import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置源6：咪咕视频直播源加载器。
 *
 * <p>与源5（爱其意）不同，本加载器**不预先解析播放地址**，只生成「频道表」：
 * M3U 里每个频道的地址写成自定义协议 {@code miguhd://<pID>}，真正取流在起播那一刻
 * 由 {@link MiguUrlResolver} 惰性完成（见 TVPlayerManager#doPlay）。原因：
 * <ol>
 *   <li>咪咕直链带 {@code SecurityKey}/{@code puData} 短时签名，预解析会大片过期；</li>
 *   <li>避免加载源时对咪咕发起"每频道一次"的批量请求。</li>
 * </ol>
 *
 * <p><b>频道表来源（四级降级）</b>：
 * <pre>
 *   ⓪ 服务端下发（原版链路第 ① 步，首选）
 *        GET http://user.xiaoyouzb.cn/v3/conf/app_page_cate
 *            → data.cateList[] = 分类 {id, title, type}   ← 只留 type=live，title 即分组名
 *        GET http://nowtv-new.xiaoyouzb.cn/channel/get_tag_list?tagId=&lt;id&gt;
 *            → [{contentId(=tvId), tvName, …}]
 *        （串行 + 250ms 限速；结果写 12h 磁盘缓存）
 *   ① 网络降级：咪咕官方频道目录
 *        GET https://program-sc.miguvideo.com/live/v2/tv-data/a5f78af9d160418eb679a6dd0429c920
 *            → body.liveList[]  = 分类 {name, vomsID}
 *        GET https://program-sc.miguvideo.com/live/v2/tv-data/{vomsID}
 *            → body.dataList[] = 频道 {name, pID}
 *        （串行 + 限速，只取 {@link #ENABLED_CATEGORIES} 中列出的分类）
 *   ② 本地磁盘缓存 files/migu_source.m3u（{@link #CACHE_TTL_MS} 内直接复用，不再联网）
 *   ③ 打包兜底 assets/migu_channels.txt（离线首启可用）
 * </pre>
 *
 * <p>频道表格式（assets 兜底）：每行 {@code 分组,频道名,pID}，{@code #} 开头为注释。
 */
public final class MiguSourceLoader {

    private static final String TAG = "MiguSourceLoader";

    /** 咪咕频道目录根接口（返回分类 liveList） */
    private static final String CATALOG_ROOT =
            "https://program-sc.miguvideo.com/live/v2/tv-data/a5f78af9d160418eb679a6dd0429c920";
    /** 分类明细接口前缀（拼 vomsID） */
    private static final String CATALOG_ITEM_PREFIX =
            "https://program-sc.miguvideo.com/live/v2/tv-data/";

    /**
     * 需要纳入内置源6 的分类（顺序即 M3U 中的展示顺序）。
     *
     * <p>咪咕目录里共有：热门 / 体育 / 央视 / 卫视 / 地方 / 影视 / 新闻 / 教育 / 熊猫 /
     * 娱乐 / 少儿 / 纪实 / 印象天下 / 电台 —— 此处<b>全量列出</b>，不再只取 5 个。
     *
     * <p>【2026-10-30 全量放开】原先只取前 5 个（央视/卫视/地方/影视/少儿）。放开到 14 个的
     * 代价仅是「每多一个有内容的分类多 1 次串行请求」（{@link #CATEGORY_INTERVAL_MS} 限速），
     * 且结果进 12h 磁盘缓存，正常一天只跑一轮；目录里<b>不存在</b>的分类由
     * {@code findVomsId} 返回空 → 直接跳过，零额外请求代价。
     *
     * <p><b>实测现状（2026-10-30，宽带出口）</b>：该目录接口已大幅缩水 ——
     * 根地址的 {@code body.liveList} 只回「央视」1 个分类，{@code dataList} 只有 1 条
     * {@code CCTV3综艺}（pID=111111，已下线占位）。加 {@code fitArea}/{@code clientId}
     * 参数结果相同。因此这 14 个分类当前<b>实际只能取到 1 条残值</b>，
     * 会被 {@link #MIN_CHANNELS} 判为残表不采用、不缓存。
     * 这份清单的作用是<b>接口若恢复即自动满血</b>，不是当前可用性的承诺。
     */
    private static final String[] ENABLED_CATEGORIES = {
            "央视", "卫视", "热门", "体育", "影视", "新闻", "教育",
            "少儿", "熊猫", "娱乐", "纪实", "印象天下", "地方", "电台"
    };

    /** 分类请求之间的最小间隔（毫秒），串行限速，避免对咪咕侧造成压力 */
    private static final long CATEGORY_INTERVAL_MS = 200L;

    /** 磁盘缓存文件名 */
    private static final String CACHE_FILE = "migu_source.m3u";
    /** 缓存有效期：12 小时（期间不再联网取目录） */
    private static final long CACHE_TTL_MS = 12L * 60L * 60L * 1000L;

    /** assets 兜底频道表 */
    private static final String ASSET_FILE = "migu_channels.txt";

    /**
     * 【2026-10-30 本地全量表】assets/migu_channels_local.txt —— 44 组 / 1780 频道的固化快照。
     *
     * <p>格式 {@code 分组,频道名,tvId}（第 3 列是 contentId，<b>不是</b> pID），
     * 由 {@code _migu_build_local.py} 一次性抓取生成：
     * 16 个 {@code type=live} 分类（342 条）+ 30 个省份地方频道（1438 条，去重后）。
     *
     * <p>省份分组的来源见 {@code ContiesProvince / ContiesVideoList}（原版
     * {@code /channel/get_all_province} + {@code /channel/get_df_channel_by_province}）——
     * 这是「地方」分组唯一正确的入口，{@code tagId=14} 直查永远为空。
     *
     * <p>命中的效果：源6 加载 <b>零网络请求</b>、秒出 44 组；起播时仍按
     * {@code nowtv://<tvId>} 惰性取源（与在线通道完全同一条播放链）。
     */
    private static final String ASSET_LOCAL_FULL = "migu_channels_local.txt";

    /**
     * 是否优先使用本地全量表（{@link #ASSET_LOCAL_FULL}）。
     *
     * <p>true（默认）= 命中即返回，不再发任何目录请求；本地表缺失或异常（&lt;
     * {@link #MIN_CHANNELS}）时自动回落到原四层降级链路。
     * 想恢复「走在线目录」把它改 false 即可。
     */
    private static final boolean PREFER_LOCAL_FULL = true;

    /**
     * 省份频道在线缓存（{@code files/migu_province.txt}，格式 {@code 省名,频道名,tvId}）。
     *
     * <p>【2026-10-30 自更新】assets 表是<b>死快照</b>，tvId 会随咪咕侧漂移。
     * 这里保留一份「在线刷新得到的省份频道」，与 assets 合并使用：
     * 同 tvId 以缓存为准（分组统一归到「地方」），缓存里多出来的频道直接追加。
     *
     * <p>刷新是<b>后台异步</b>的（{@link #maybeRefreshProvincesAsync}），
     * 只在 WiFi/以太网出口、且缓存超过 {@link #PROVINCE_TTL_MS} 时触发一次，
     * 全程串行 + {@link #PROVINCE_INTERVAL_MS} 限速，绝不影响切源速度。
     */
    private static final String PROVINCE_CACHE = "migu_province.txt";

    /** 省份缓存有效期：24 小时 */
    private static final long PROVINCE_TTL_MS = 24L * 60L * 60L * 1000L;

    /** 省份之间的请求间隔（毫秒），串行限速 */
    private static final long PROVINCE_INTERVAL_MS = 250L;

    /** 合并后的地方分组统一名称（与原 assets 表一致） */
    private static final String MERGED_LOCAL_GROUP = "地方";

    /** 省份刷新是否进行中（防重入） */
    private static volatile boolean sProvinceRefreshing;

    /**
     * 服务端下发通道产出的自定义协议：{@code nowtv://<tvId>}。
     *
     * <p>播放该地址时，会先向 {@code nowtv-new.xiaoyouzb.cn} 换取真实源串
     * （如 {@code miguhd://608807420}），再交给 JS 引擎解析 —— 即原版链路
     * 「服务端下发 → 客户端分发 → JS 解析」的完整复刻。
     */
    public static final String SCHEME_NOWTV = "nowtv://";

    /** M3U 里写的 tvg-id 前缀，用于播放失败时识别「该频道属于服务端下发通道」 */
    public static final String TVG_PREFIX_NOWTV = "nowtv:";

    /** 分类表取不到时的兜底分组名（正常情况下分组 = 服务端分类标题） */
    private static final String GROUP_FALLBACK = "咪咕";

    /**
     * 【2026-10-08】频道表健全性下限。
     *
     * <p>实测两次事故：设备走<b>非 WiFi 出口</b>（手机热点 / 移动蜂窝）时，
     * 服务端下发 0 频道、咪咕官方目录缩水成 1 个分类 1 个频道，
     * 且 pID=111111 是已下线占位 → 这份残表照常写进 12h 磁盘缓存，
     * 导致源6只剩这一个播不了的频道。路由器宽带出口则一切正常。
     * 故低于该阈值的目录视为异常：<b>不采用、不缓存</b>，
     * 继续走后续降级（过期残值 / assets 兜底表 ~70 频道全真 pid）。
     */
    private static final int MIN_CHANNELS = 10;

    /** 统计 M3U 里的频道条数（非 # 开头的非空行 = 地址行） */
    private static int countM3uChannels(String m3u) {
        if (TextUtils.isEmpty(m3u)) return 0;
        int n = 0;
        for (String line : m3u.split("\n")) {
            String t = line == null ? "" : line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) n++;
        }
        return n;
    }

    /**
     * 当前默认网络是否为宽带出口（WiFi / 以太网）。
     *
     * <p>实测（2026-10-08 真机）：CMCC 蜂窝 / 热点出口下，nowtv 服务端下发 0 频道、
     * 咪咕官方目录缩水成 1 个占位频道 —— 控制面接口对运营商 CGNAT 出口 IP 段
     * 主动降级（畅享TV 原版同现象，与设备无关）；而咪咕 CDN 播放正常。
     * 宽带出口（路由器）一切正常。
     *
     * <p>判定失败 / 异常时保守返回 {@code true}（按宽带处理，走原在线链路），
     * 宁可多花几次请求，也不误伤正常网络。
     */
    public static boolean isBroadbandNetwork(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return true;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) return true;
                return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return true;
            int type = ni.getType();
            return type == ConnectivityManager.TYPE_WIFI
                    || type == ConnectivityManager.TYPE_ETHERNET;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 服务端下发频道表最多取几个分类（控制请求量；全程串行 + 250ms 限速）。
     *
     * <p>服务端首页 {@code type=live} 分类现为 <b>16 个</b>（已按 id 去重）：央视 / 卫视 /
     * 体育 / 电影 / 少儿 / 综艺 / 春晚 / 儿童动画 / 热播剧集 / 央视大剧 / 经典韩剧 /
     * 人文记录 / 地方 / 财经 / 教育 / 美女。
     *
     * <p>【2026-10-30 改为不限制 = 0】原先硬编码 16，恰好等于当时的分类总数 ——
     * 服务端一旦新增第 17 个 {@code type=live} 分类，就会在
     * {@code fetchChannelList} 里被<b>静默截断</b>（只在日志留一句「超过上限」），
     * 表现为「服务端加了分组但客户端看不到」。改为 0（不限制）后分类随服务端走，
     * 上限只受服务端自身下发的列表长度约束。
     *
     * <p>请求量：1 次分类表 + N 次频道表（N = 当前分类数，现为 16），全程串行、
     * {@code CATEGORY_INTERVAL_MS}=250ms 限速；结果写 12h 磁盘缓存，正常一天只跑一轮。
     * 即便服务端把分类扩到 30 个，也只是每 12 小时多 30 次串行请求，仍在红线内。
     */
    private static final int NOWTV_MAX_CATEGORIES = 0;

    /** 是否为服务端下发地址（{@code nowtv://<tvId>}） */
    public static boolean isNowTvUrl(String url) {
        return url != null && url.startsWith(SCHEME_NOWTV);
    }

    private MiguSourceLoader() {}

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 拉取咪咕频道表并拼成 M3U 文本（供 {@code PlaylistParser.parseContent} 复用）。
     * 该方法会阻塞网络，必须在 IO 线程调用。
     *
     * @return M3U 文本；全部来源都失败时返回空串
     */
    public static String loadAllLiveAsM3u(Context context) {
        // 【2026-10-18 台标接入】统一出站后处理：三条路径（缓存/服务端下发/官方目录/assets 兜底）
        //   产出的 M3U 若无 tvg-logo，按频道名补 catvod 台标库链接（幂等：已含则原样返回）
        return ensureMiguLogos(loadAllLiveAsM3uInner(context));
    }

    private static String loadAllLiveAsM3uInner(Context context) {
        // ---- ⓪ 本地表（2026-10-30）：assets 快照 + 省份在线缓存，零网络请求 ----
        if (PREFER_LOCAL_FULL) {
            List<NowTvSourceApi.Channel> local = buildLocalChannels(context);
            if (local.size() >= MIN_CHANNELS) {
                // 合并成功 → 顺手看要不要后台刷新省份（不阻塞、失败静默、只 WiFi）
                maybeRefreshProvincesAsync(context);
                return buildNowTvM3u(local, "本地表+省份缓存");
            }
            if (!local.isEmpty()) {
                LogBridge.w(TAG, "源6(咪咕)：本地表仅 " + local.size()
                        + " 条（<" + MIN_CHANNELS + "），视为异常，回落在线链路");
            }
        }

        // ---- ① 磁盘缓存新鲜且频道数正常 → 直接用，不联网 ----
        String cached = readDiskCache(context);
        if (!TextUtils.isEmpty(cached) && countM3uChannels(cached) < MIN_CHANNELS) {
            LogBridge.w(TAG, "源6(咪咕)：磁盘缓存疑似残表（仅 "
                    + countM3uChannels(cached) + " 个频道 < " + MIN_CHANNELS + "），忽略");
            cached = "";
        }
        if (!TextUtils.isEmpty(cached) && !isCacheExpired(context)) {
            return cached;
        }

        // ---- ①b 蜂窝/热点出口：直接用本地频道表，跳过控制面请求 ----
        // 实测该出口下 nowtv 下发 0 频道、咪咕官方目录缩水成 1 个占位频道（畅享TV 原版同现象），
        // 请求只是浪费且可能拿到残表；本地缓存或 assets 兜底表在蜂窝下均可正常播放。
        if (!isBroadbandNetwork(context)) {
            if (!TextUtils.isEmpty(cached)) {
                return cached;
            }
            String fb = readAssetFallback(context);
            if (!TextUtils.isEmpty(fb)) {
                LogBridge.d(TAG, "源6(咪咕)：非WiFi出口且无本地频道表，直接启用 assets 兜底（"
                        + countM3uChannels(fb) + " 频道，不请求控制面）");
                return fb;
            }
            LogBridge.d(TAG, "源6(咪咕)：非WiFi出口但缓存与 assets 兜底均不可用，仍尝试在线目录");
        }

        // ---- ② 网络：服务端下发优先（对应原版链路第 ① 步） ----
        String fresh = fetchNowTvAsM3u(context);
        if (!TextUtils.isEmpty(fresh) && countM3uChannels(fresh) >= MIN_CHANNELS) {
            writeDiskCache(context, fresh);
            return fresh;
        }

        // ---- ②b 降级：咪咕官方频道目录 ----
        fresh = fetchCatalogAsM3u();
        if (!TextUtils.isEmpty(fresh) && countM3uChannels(fresh) >= MIN_CHANNELS) {
            writeDiskCache(context, fresh);
            return fresh;
        }
        if (!TextUtils.isEmpty(fresh)) {
            LogBridge.w(TAG, "源6(咪咕)：官方目录仅 " + countM3uChannels(fresh)
                    + " 个频道（<" + MIN_CHANNELS + "，非WiFi出口服务端缩水），不采用不缓存，继续降级");
        }

        // ---- ③ 过期缓存的残值 ----
        if (!TextUtils.isEmpty(cached)) {
            LogBridge.w(TAG, "源6(咪咕)：目录请求失败，沿用过期磁盘缓存");
            return cached;
        }

        // ---- ④ assets 兜底 ----
        String fallback = readAssetFallback(context);
        if (!TextUtils.isEmpty(fallback)) {
            LogBridge.d(TAG, "源6(咪咕)：目录与缓存均不可用，启用 assets 静态兜底频道表");
            return fallback;
        }

        LogBridge.e(TAG, "源6(咪咕)：无任何可用频道表");
        return "";
    }

    // ------------------------------------------------------------------
    // ① 服务端下发（nowtv-new.xiaoyouzb.cn）—— 完整链路第 ① 步
    // ------------------------------------------------------------------

    /**
     * 走服务端下发通道构建频道表。
     *
     * <p>产出地址为 {@code nowtv://<tvId>}，真正的源串在<b>播放那一刻</b>才去换取
     * （见 {@code TVPlayerManager}）—— 因为原版也是逐频道按需查询，预取会构成批量请求。
     *
     * <p>{@code group-title} 直接用服务端首页的<b>分类标题</b>
     * （央视 / 卫视 / 体育 / 电影 / 少儿 …，见 {@code NowTvSourceApi.fetchCategories}）。
     * 分类表不可用时才退化为单一分组 {@link #GROUP_FALLBACK}。
     *
     * <p>失败返回 null，由调用方降级到咪咕官方目录。
     */
    private static String fetchNowTvAsM3u(Context context) {
        try {
            List<NowTvSourceApi.Channel> channels =
                    NowTvSourceApi.fetchChannelList(context, NOWTV_MAX_CATEGORIES);
            if (channels.isEmpty()) {
                LogBridge.i(TAG, "服务端未下发频道（或接口不可用）");
                return null;
            }
            return buildNowTvM3u(channels, "服务端下发");
        } catch (Throwable t) {
            LogBridge.w(TAG, "服务端下发异常：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
            return null;
        }
    }

    /**
     * 把「服务端下发式」频道表（{@code nowtv://<tvId>} 通道）拼成 M3U。
     *
     * <p>两条路径共用本方法，保证产物格式逐字节一致：
     * <ol>
     *   <li>{@link #fetchNowTvAsM3u} —— 在线拉 16 个分类；</li>
     *   <li>{@link #readAssetLocalFull} —— 读 assets 本地全量表（零请求）。</li>
     * </ol>
     *
     * @param sourceTag 仅用于日志区分来源（"服务端下发" / "本地全量表"）
     */
    private static String buildNowTvM3u(List<NowTvSourceApi.Channel> channels, String sourceTag) {
        Set<String> groups = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder(channels.size() * 110 + 16);
        sb.append("#EXTM3U\n");
        for (NowTvSourceApi.Channel c : channels) {
            String group = esc(TextUtils.isEmpty(c.group) ? GROUP_FALLBACK : c.group);
            String name = esc(c.name);
            groups.add(group);
            sb.append("#EXTINF:-1 tvg-id=\"").append(TVG_PREFIX_NOWTV).append(c.tvId)
              .append("\" tvg-name=\"").append(name)
              .append("\" group-title=\"").append(group)
              .append("\",").append(name).append("\n");
            sb.append(SCHEME_NOWTV).append(c.tvId).append("\n");
        }
        LogBridge.i(TAG, "源6(咪咕)：" + sourceTag + " " + channels.size() + " 个频道，"
                + groups.size() + " 个分组：" + groups);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // ② 咪咕官方频道目录
    // ------------------------------------------------------------------

    private static String fetchCatalogAsM3u() {
        try {
            String rootText = getText(CATALOG_ROOT);
            if (TextUtils.isEmpty(rootText)) {
                LogBridge.w(TAG, "目录根接口返回空");
                return null;
            }
            JSONObject root = new JSONObject(rootText);
            JSONObject rootBody = root.optJSONObject("body");
            JSONArray liveList = rootBody == null ? null : rootBody.optJSONArray("liveList");
            if (liveList == null || liveList.length() == 0) {
                LogBridge.w(TAG, "目录根接口无 liveList");
                return null;
            }

            List<String[]> rows = new ArrayList<>();   // {group, name, pid}
            Set<String> seenPid = new LinkedHashSet<>();

            int fetchedCategories = 0;
            for (String wanted : ENABLED_CATEGORIES) {
                String vomsId = findVomsId(liveList, wanted);
                if (TextUtils.isEmpty(vomsId)) {
                    LogBridge.d(TAG, "目录中未找到分类：" + wanted);
                    continue;
                }

                if (fetchedCategories > 0) {
                    try {
                        Thread.sleep(CATEGORY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                fetchedCategories++;

                String catText = getText(CATALOG_ITEM_PREFIX + vomsId);
                if (TextUtils.isEmpty(catText)) {

                    continue;
                }
                JSONObject catJson = new JSONObject(catText);
                JSONObject catBody = catJson.optJSONObject("body");
                JSONArray dataList = catBody == null ? null : catBody.optJSONArray("dataList");
                if (dataList == null) {

                    continue;
                }
                int n = 0;
                for (int i = 0; i < dataList.length(); i++) {
                    JSONObject item = dataList.optJSONObject(i);
                    if (item == null) continue;
                    String name = item.optString("name", "").trim();
                    String pid = item.optString("pID", "").trim();
                    if (name.isEmpty() || pid.isEmpty()) continue;
                    if (!seenPid.add(pid)) continue;
                    rows.add(new String[]{wanted, name, pid});
                    n++;
                }

            }

            if (rows.isEmpty()) {
                LogBridge.i(TAG, "目录构建结果为空");
                return null;
            }
            LogBridge.i(TAG, "源6(咪咕)：联网目录共 " + rows.size() + " 个频道，"
                    + fetchedCategories + " 个分类");
            return buildM3u(rows);

        } catch (Throwable t) {
            LogBridge.w(TAG, "目录构建异常：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static String findVomsId(JSONArray liveList, String categoryName) {
        for (int i = 0; i < liveList.length(); i++) {
            JSONObject o = liveList.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "").trim();
            if (categoryName.equals(name)) {
                String vomsId = o.optString("vomsID", "").trim();
                if (vomsId.isEmpty()) vomsId = o.optString("vomsId", "").trim();
                return vomsId;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // ⓪ assets 本地全量表（44 组 / 1780 频道，零请求）
    // ------------------------------------------------------------------

    /**
     * 读 {@link #ASSET_LOCAL_FULL}（{@code 分组,频道名,tvId}）→ 频道列表。
     *
     * <p>纯本地、无网络；{@code tvId} 全局去重（保留首次出现，即分类优先于省份）。
     * 任何异常都返回已解析到的部分（不抛），由调用方按 {@link #MIN_CHANNELS} 判断可用性。
     */
    private static List<NowTvSourceApi.Channel> readAssetLocalFull(Context context) {
        List<NowTvSourceApi.Channel> out = new ArrayList<>();
        if (context == null) return out;
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_LOCAL_FULL);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            Set<Integer> seen = new LinkedHashSet<>();
            String line;
            int bad = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int a = line.indexOf(',');
                int b = a < 0 ? -1 : line.indexOf(',', a + 1);
                if (a <= 0 || b <= a) { bad++; continue; }
                String group = line.substring(0, a).trim();
                String name = line.substring(a + 1, b).trim();
                String tvText = line.substring(b + 1).trim();
                if (group.isEmpty() || name.isEmpty() || tvText.isEmpty()) { bad++; continue; }
                int tvId;
                try {
                    tvId = Integer.parseInt(tvText);
                } catch (NumberFormatException nfe) {
                    bad++;
                    continue;
                }
                if (tvId <= 0) { bad++; continue; }
                if (!seen.add(tvId)) continue;
                out.add(new NowTvSourceApi.Channel(tvId, name, group));
            }
            LogBridge.i(TAG, "源6(咪咕)：本地全量表解析 " + out.size() + " 条"
                    + (bad > 0 ? "（跳过异常行 " + bad + "）" : ""));
        } catch (IOException e) {
            LogBridge.w(TAG, "读取本地全量表失败：" + e.getMessage());
        } catch (Throwable t) {
            LogBridge.w(TAG, "解析本地全量表异常：" + t.getMessage());
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) {} }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // ⓪b 省份自更新（在线刷新 + 失效剔除）
    // ------------------------------------------------------------------

    /**
     * 组装本地频道表：{@code assets 快照} + {@code 省份在线缓存}，并剔除已判死的频道。
     *
     * <p>优先级：同 tvId 以省份缓存为准（分组统一归到 {@link #MERGED_LOCAL_GROUP}）；
     * 缓存里多出来的频道追加。全部为纯本地操作，<b>零网络请求</b>。
     */
    private static List<NowTvSourceApi.Channel> buildLocalChannels(Context context) {
        List<NowTvSourceApi.Channel> base = readAssetLocalFull(context);
        List<NowTvSourceApi.Channel> prov = readProvinceCache(context);

        // 在线缓存只用于「tvId 迁移」：按频道名把 assets 里的旧 tvId 换成服务端当前 tvId。
        // 绝不把缓存里「assets 没有的频道」灌进来 —— 那些未经验证，是今天剔除掉的 1179 条死源的同类。
        Map<String, Integer> onlineTvIdByName = new LinkedHashMap<>();
        for (NowTvSourceApi.Channel c : prov) {
            if (c.tvId > 0) onlineTvIdByName.put(c.name, c.tvId);
        }

        Map<Integer, NowTvSourceApi.Channel> merged = new LinkedHashMap<>();
        int migrated = 0;
        for (NowTvSourceApi.Channel c : base) {
            Integer newTv = onlineTvIdByName.get(c.name);
            if (newTv != null && newTv != c.tvId) {
                merged.put(newTv, new NowTvSourceApi.Channel(newTv, c.name, c.group));
                migrated++;
            } else {
                merged.put(c.tvId, c);
            }
        }

        List<NowTvSourceApi.Channel> out = new ArrayList<>(merged.size());
        int dropped = 0;
        for (NowTvSourceApi.Channel c : merged.values()) {
            // 只对「地方」分组应用失效名单 —— 央视/卫视是主力频道，宁可留着也别误删
            if (MERGED_LOCAL_GROUP.equals(c.group)
                    && MiguDeadChannelStore.isDead(context, c.tvId)) {
                dropped++;
                continue;
            }
            out.add(c);
        }
        LogBridge.i(TAG, "本地表合并：assets " + base.size() + " + 在线缓存 " + prov.size()
                + " → " + out.size() + " 条（tvId 迁移 " + migrated + "，剔除失效 " + dropped + "）");
        return out;
    }

    private static File provinceCacheFile(Context context) {
        return new File(context.getFilesDir(), PROVINCE_CACHE);
    }

    private static boolean isProvinceCacheExpired(Context context) {
        File f = provinceCacheFile(context);
        if (!f.exists() || f.length() <= 0) return true;
        return System.currentTimeMillis() - f.lastModified() > PROVINCE_TTL_MS;
    }

    /** 读省份在线缓存（{@code 省名,频道名,tvId}）；不存在或异常返回空表 */
    private static List<NowTvSourceApi.Channel> readProvinceCache(Context context) {
        List<NowTvSourceApi.Channel> out = new ArrayList<>();
        if (context == null) return out;
        File f = provinceCacheFile(context);
        if (!f.exists() || f.length() <= 0) return out;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            Set<Integer> seen = new LinkedHashSet<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int a = line.indexOf(',');
                int b = a < 0 ? -1 : line.indexOf(',', a + 1);
                if (a <= 0 || b <= a) continue;
                String prov = line.substring(0, a).trim();
                String name = line.substring(a + 1, b).trim();
                String tvText = line.substring(b + 1).trim();
                if (prov.isEmpty() || name.isEmpty() || tvText.isEmpty()) continue;
                int tvId;
                try {
                    tvId = Integer.parseInt(tvText);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                if (tvId <= 0 || !seen.add(tvId)) continue;
                out.add(new NowTvSourceApi.Channel(tvId, name, prov));
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "省份缓存读取失败：" + t.getMessage());
            return out;
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }
        return out;
    }

    private static void writeProvinceCache(Context context, List<NowTvSourceApi.Channel> channels) {
        if (context == null || channels == null || channels.isEmpty()) return;
        FileOutputStream fos = null;
        try {
            StringBuilder sb = new StringBuilder(channels.size() * 24 + 64);
            sb.append("# 源6(咪咕) 省份频道在线缓存 —— 由后台刷新写入，勿手改\n");
            sb.append("# 格式：省名,频道名,tvId（加载时分组统一归到「地方」）\n");
            for (NowTvSourceApi.Channel c : channels) {
                sb.append(esc(c.group)).append(',')
                  .append(esc(c.name)).append(',')
                  .append(c.tvId).append('\n');
            }
            fos = new FileOutputStream(provinceCacheFile(context));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            LogBridge.w(TAG, "省份缓存写入失败：" + t.getMessage());
        } finally {
            if (fos != null) { try { fos.close(); } catch (IOException ignored) {} }
        }
    }

    /**
     * 需要时启动<b>后台</b>省份刷新（不阻塞调用方）。
     *
     * <p>触发条件（全部满足）：非蜂窝/热点出口、缓存缺失或超过 {@link #PROVINCE_TTL_MS}、
     * 当前没有刷新在跑。失败静默，下一轮再试。
     */
    private static void maybeRefreshProvincesAsync(final Context context) {
        if (context == null || sProvinceRefreshing) return;
        if (!isBroadbandNetwork(context)) {
            LogBridge.d(TAG, "非宽带出口，跳过省份后台刷新");
            return;
        }
        if (!isProvinceCacheExpired(context)) return;

        sProvinceRefreshing = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshProvinces(context);
                } catch (Throwable e) {
                    LogBridge.w(TAG, "省份后台刷新异常：" + e.getClass().getSimpleName()
                            + " " + e.getMessage());
                } finally {
                    sProvinceRefreshing = false;
                }
            }
        }, "migu-province-refresh");
        t.setDaemon(true);
        t.start();
        LogBridge.i(TAG, "省份缓存缺失/过期 → 已启动后台刷新（串行、限速 "
                + PROVINCE_INTERVAL_MS + "ms）");
    }

    /**
     * 串行拉一遍「省份列表 → 各省频道」，写 {@link #PROVINCE_CACHE}。
     *
     * <p>对应原版 {@code CategoryViewModel.getProvincesList()} +
     * {@code getChannelByProvince()}（{@code /channel/get_all_province} 与
     * {@code /channel/get_df_channel_by_province}）。全程串行、不重试、不并发。
     */
    private static void refreshProvinces(Context context) {
        List<String> provs = NowTvSourceApi.fetchProvinces(context);
        if (provs.isEmpty()) {
            LogBridge.w(TAG, "省份列表为空，本轮刷新放弃");
            return;
        }
        List<NowTvSourceApi.Channel> all = new ArrayList<>();
        int okProv = 0;
        for (int i = 0; i < provs.size(); i++) {
            if (i > 0) {
                try {
                    Thread.sleep(PROVINCE_INTERVAL_MS);   // 串行限速
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            List<NowTvSourceApi.Channel> one =
                    NowTvSourceApi.fetchChannelsByProvince(context, provs.get(i));
            if (!one.isEmpty()) {
                okProv++;
                all.addAll(one);
            }
        }
        if (all.size() < MIN_CHANNELS) {
            LogBridge.w(TAG, "省份刷新结果仅 " + all.size() + " 条（<" + MIN_CHANNELS
                    + "），不写缓存");
            return;
        }

        // 只保留「assets 里已有同名频道」的条目 —— 缓存仅用于把这些频道的旧 tvId 迁移到服务端当前 tvId。
        // 不认识的频道一律丢弃：它们是未经验证的死源（与服务端下发的 1179 条同类），灌进来就是倒退。
        Set<String> knownNames = new LinkedHashSet<>();
        for (NowTvSourceApi.Channel c : readAssetLocalFull(context)) knownNames.add(c.name);

        List<NowTvSourceApi.Channel> keep = new ArrayList<>();
        int unknown = 0;
        for (NowTvSourceApi.Channel c : all) {
            if (knownNames.contains(c.name)) keep.add(c);
            else unknown++;
        }
        if (keep.size() < MIN_CHANNELS) {
            LogBridge.w(TAG, "省份刷新：" + all.size() + " 条里只匹配到 " + keep.size()
                    + " 个已知频道（<" + MIN_CHANNELS + "），不写缓存");
            return;
        }
        writeProvinceCache(context, keep);
        LogBridge.i(TAG, "省份后台刷新完成：" + okProv + "/" + provs.size() + " 个省份，在线 "
                + all.size() + " 条 → 已知频道 " + keep.size() + " 条写入缓存，丢弃未知 "
                + unknown + " 条（下次加载生效）");
    }

    // ------------------------------------------------------------------
    // ④ assets 兜底
    // ------------------------------------------------------------------

    private static String readAssetFallback(Context context) {
        if (context == null) return "";
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_FILE);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            List<String[]> rows = new ArrayList<>();
            Set<String> seenPid = new LinkedHashSet<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                String group = parts[0].trim();
                String name = parts[1].trim();
                String pid = parts[2].trim();
                if (group.isEmpty() || name.isEmpty() || pid.isEmpty()) continue;
                if (!seenPid.add(pid)) continue;
                rows.add(new String[]{group, name, pid});
            }
            if (rows.isEmpty()) return "";
            LogBridge.d(TAG, "assets 兜底频道表命中，共 " + rows.size() + " 个频道");
            return buildM3u(rows);
        } catch (IOException e) {
            LogBridge.w(TAG, "读取 assets 兜底失败：" + e.getMessage());
            return "";
        } catch (Throwable t) {
            LogBridge.w(TAG, "解析 assets 兜底异常：" + t.getMessage());
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // 按频道名补咪咕 pID（「全部优先咪咕线路」支持）
    // ------------------------------------------------------------------

    /** 进程内「频道名 → 咪咕 pID」映射（懒加载；磁盘缓存变更后按 lastModified 自动重建） */
    private static volatile Map<String, String> sNameToPid;
    private static volatile long sNameToPidStamp = -1L;

    /**
     * 按<b>频道名</b>查咪咕 pID（纯离线，<b>零网络请求</b>）。
     *
     * <p>用途：当服务端下发的 {@code sourceLabels} 里<b>没有</b> {@code migu*} 源时
     * （典型：{@code tvId=43} 湖南卫视只下发 {@code ysp://…} + 一条 CDN 直链），
     * 按频道名从本地表补一条 {@code miguhd://<pID>} 优先起播 —— 即「所有频道优先咪咕线路」。
     *
     * <p>数据源（都已在本地，不发请求）：
     * <ol>
     *   <li>{@code files/migu_source.m3u} 缓存里形如 {@code miguhd://<pID>} 的条目（较新，优先）；</li>
     *   <li>{@code assets/migu_channels.txt} 静态兜底表（{@code 分组,频道名,pID}）。</li>
     * </ol>
     *
     * @return pID；查不到返回 {@code null}（调用方原样回退服务端下发源，不重试）
     */
    public static String lookupPidByName(Context context, String name) {
        if (context == null || TextUtils.isEmpty(name)) return null;
        String key = name.trim();
        if (key.isEmpty()) return null;
        Map<String, String> map = nameToPidMap(context);
        if (map.isEmpty()) return null;

        String pid = map.get(key);
        if (!TextUtils.isEmpty(pid)) return pid;

        // 宽松匹配①：把「高清/超清/标清/4K/HD」等后缀去掉再试
        String stripped = stripQuality(key);
        if (!stripped.isEmpty() && !stripped.equals(key)) {
            pid = map.get(stripped);
            if (!TextUtils.isEmpty(pid)) return pid;
        }
        // 宽松匹配②：表里的键去掉后缀后与目标一致（表里可能存的是「湖南卫视高清」这类）
        for (Map.Entry<String, String> e : map.entrySet()) {
            String k2 = stripQuality(e.getKey());
            if ((!stripped.isEmpty() && k2.equals(stripped)) || k2.equals(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 去掉频道名尾部的画质后缀，便于宽松匹配 */
    private static String stripQuality(String s) {
        if (s == null) return "";
        return s.replaceAll("(高清|超清|标清|蓝光|4K|8K|HD|FHD|\\s)+$", "").trim();
    }

    private static Map<String, String> nameToPidMap(Context context) {
        long stamp = cacheStamp(context);
        Map<String, String> cur = sNameToPid;
        if (cur != null && stamp == sNameToPidStamp) return cur;

        Map<String, String> map = new LinkedHashMap<>();
        // ① 磁盘缓存 M3U（官方目录通道产出的是 miguhd://，较新 → 优先生效）
        parseM3uNamePid(readDiskCache(context), map);
        // ② assets 静态兜底（不覆盖 ① 已有条目）
        parseAssetNamePid(context, map);

        sNameToPid = map;
        sNameToPidStamp = stamp;
        return map;
    }

    private static long cacheStamp(Context context) {
        try {
            File f = cacheFile(context);
            return f.exists() ? f.lastModified() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 从 M3U 文本里提取 {@code tvg-name → miguhd://<pID>}（只认 miguhd:// 行） */
    private static void parseM3uNamePid(String m3u, Map<String, String> out) {
        if (TextUtils.isEmpty(m3u)) return;
        String pendingName = null;
        for (String raw : m3u.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int a = line.indexOf("tvg-name=\"");
                if (a >= 0) {
                    int b = line.indexOf('"', a + 10);
                    if (b > a + 10) pendingName = line.substring(a + 10, b).trim();
                }
                continue;
            }
            if (line.startsWith("#")) continue;
            if (pendingName != null && line.startsWith(MiguUrlResolver.SCHEME)) {
                String pid = line.substring(MiguUrlResolver.SCHEME.length()).trim();
                if (!pid.isEmpty()) out.put(pendingName, pid);
            }
            pendingName = null;
        }
    }

    /** 从 assets 兜底表提取 {@code 频道名 → pID}（已存在的不覆盖） */
    private static void parseAssetNamePid(Context context, Map<String, String> out) {
        if (context == null) return;
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_FILE);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                String name = parts[1].trim();
                String pid = parts[2].trim();
                if (name.isEmpty() || pid.isEmpty()) continue;
                if (!out.containsKey(name)) out.put(name, pid);
            }
        } catch (Throwable ignore) {
            // 静默：兜底表不可用只是少一条补源路径
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) {} }
        }
    }

    // ------------------------------------------------------------------
    // M3U 构建
    // ------------------------------------------------------------------

    private static String buildM3u(List<String[]> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 110 + 16);
        sb.append("#EXTM3U\n");
        for (String[] r : rows) {
            String group = esc(r[0]);
            String name = esc(r[1]);
            String pid = esc(r[2]);
            // 【2026-10-18 台标接入】咪咕官方接口不下发图片 URL → 用 catvod 台标库
            // （logo.catvod.com/{标准名}.png，CCTV/卫视系全覆盖；未命中 404 落占位图，无害）
            String logoName = esc(catvodLogoName(name));
            sb.append("#EXTINF:-1 tvg-id=\"").append(MiguUrlResolver.TVG_PREFIX).append(pid)
              .append("\" tvg-name=\"").append(name)
              .append("\" tvg-logo=\"https://logo.catvod.com/").append(logoName)
              .append(".png\" group-title=\"").append(group)
              .append("\",").append(name).append("\n");
            sb.append(MiguUrlResolver.SCHEME).append(pid).append("\n");
        }
        return sb.toString();
    }

    /**
     * 【2026-10-18 台标接入】咪咕频道名 → catvod 台标库标准名。
     * CCTV3综艺→CCTV3、CCTV5+体育→CCTV5+、广东卫视→广东卫视；
     * 咪咕特有频道（未命中台标库）404 → 卡片自动回退占位图，无害。
     */
    private static String catvodLogoName(String name) {
        if (name == null || name.isEmpty()) return "";
        String n = name.replace("-", "").replace("－", "").trim();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^(CCTV\\d+\\+?)").matcher(n);
        if (m.find()) return m.group(1);
        if (n.endsWith("卫视")) return n;
        return n;
    }

    /** M3U 属性值转义；顺便把逗号替换掉，避免破坏 {@code #EXTINF:...,频道名} 的解析 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\"", "'")
                .replace(",", " ")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    // ------------------------------------------------------------------
    // 磁盘缓存
    // ------------------------------------------------------------------

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE);
    }

    private static boolean isCacheExpired(Context context) {
        File f = cacheFile(context);
        if (!f.exists()) return true;
        return System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS;
    }

    private static String readDiskCache(Context context) {
        if (context == null) return "";
        File f = cacheFile(context);
        if (!f.exists() || f.length() <= 0) return "";
        FileInputStream fis = null;
        BufferedReader br = null;
        try {
            fis = new FileInputStream(f);
            br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            LogBridge.w(TAG, "读缓存失败：" + t.getMessage());
            return "";
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
            if (fis != null) { try { fis.close(); } catch (IOException ignored) {} }
        }
    }

    private static void writeDiskCache(Context context, String content) {
        if (context == null || TextUtils.isEmpty(content)) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cacheFile(context));
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            LogBridge.w(TAG, "写缓存失败：" + t.getMessage());
        } finally {
            if (fos != null) { try { fos.close(); } catch (IOException ignored) {} }
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /**
     * 【2026-10-18 台标接入】咪咕 M3U 统一补台标：#EXTINF 行若无 tvg-logo，
     * 按频道名标准化后补 catvod 台标库链接（logo.catvod.com/{名}.png）。
     * 幂等：文本中已含 tvg-logo= 则原样返回（新版缓存/官方目录路径）。
     */
    private static String ensureMiguLogos(String m3u) {
        if (m3u == null || m3u.isEmpty() || m3u.contains("tvg-logo=")) return m3u;
        StringBuilder sb = new StringBuilder(m3u.length() + m3u.length() / 6 + 64);
        for (String line : m3u.split("\\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#EXTINF") && !t.contains("tvg-logo=\"")) {
                int comma = t.lastIndexOf(",");
                String chName = comma >= 0 ? t.substring(comma + 1).trim() : "";
                String logoName = catvodLogoName(esc(chName));
                if (!logoName.isEmpty()) {
                    int attr = t.indexOf(" tvg-name=");
                    if (attr < 0) attr = t.indexOf(" tvg-id=");
                    if (attr > 0) {
                        sb.append(t.substring(0, attr).trim())
                          .append(" tvg-logo=\"https://logo.catvod.com/").append(logoName)
                          .append(".png\"").append(t.substring(attr)).append("\n");
                        continue;
                    }
                }
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static String getText(String url) {
        try (Response resp = NetUtil.getInstance().syncGet(url)) {
            if (resp == null || !resp.isSuccessful() || resp.body() == null) {
                LogBridge.w(TAG, "GET 失败 code=" + (resp == null ? -1 : resp.code()) + " url=" + url);
                return null;
            }
            return resp.body().string();
        } catch (Throwable t) {
            LogBridge.w(TAG, "GET 异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }
}
