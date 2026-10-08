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
     * 娱乐 / 少儿 / 纪实 / 印象天下 / 电台。
     * 默认只取下面 5 个最常用的，请求量 = 1（根）+ 5（分类）= 6 次，串行且限速。
     * 想收更多分类，把它加进来即可（每多一个分类多 1 次请求）。
     */
    private static final String[] ENABLED_CATEGORIES = {"央视", "卫视", "地方", "影视", "少儿"};

    /** 分类请求之间的最小间隔（毫秒），串行限速，避免对咪咕侧造成压力 */
    private static final long CATEGORY_INTERVAL_MS = 200L;

    /** 磁盘缓存文件名 */
    private static final String CACHE_FILE = "migu_source.m3u";
    /** 缓存有效期：12 小时（期间不再联网取目录） */
    private static final long CACHE_TTL_MS = 12L * 60L * 60L * 1000L;

    /** assets 兜底频道表 */
    private static final String ASSET_FILE = "migu_channels.txt";

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
    private static boolean isBroadbandNetwork(Context context) {
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
     * <p>服务端首页共 16 个 {@code type=live} 分类（已按 id 去重）：央视 / 卫视 / 体育 /
     * 电影 / 少儿 / 综艺 / 春晚 / 儿童动画 / 热播剧集 / 央视大剧 / 经典韩剧 / 人文记录 /
     * 地方 / 财经 / 教育 / 美女 —— 上限设为 16 即全取，M3U 里表现为 16 个分组。
     * 结果写 12h 磁盘缓存，正常一天只跑一轮（1 次分类表 + 16 次频道表，共 17 次串行请求）。
     */
    private static final int NOWTV_MAX_CATEGORIES = 16;

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
        // ---- ① 磁盘缓存新鲜且频道数正常 → 直接用，不联网 ----
        String cached = readDiskCache(context);
        if (!TextUtils.isEmpty(cached) && countM3uChannels(cached) < MIN_CHANNELS) {
            LogBridge.w(TAG, "源6(咪咕)：磁盘缓存疑似残表（仅 "
                    + countM3uChannels(cached) + " 个频道 < " + MIN_CHANNELS + "），忽略");
            cached = "";
        }
        if (!TextUtils.isEmpty(cached) && !isCacheExpired(context)) {
            LogBridge.i(TAG, "源6(咪咕)：命中磁盘缓存，跳过目录请求");
            return cached;
        }

        // ---- ①b 蜂窝/热点出口：直接用本地频道表，跳过控制面请求 ----
        // 实测该出口下 nowtv 下发 0 频道、咪咕官方目录缩水成 1 个占位频道（畅享TV 原版同现象），
        // 请求只是浪费且可能拿到残表；本地缓存或 assets 兜底表在蜂窝下均可正常播放。
        if (!isBroadbandNetwork(context)) {
            if (!TextUtils.isEmpty(cached)) {
                LogBridge.i(TAG, "源6(咪咕)：非WiFi出口，跳过目录请求，直接用本地频道表（"
                        + countM3uChannels(cached) + " 频道）");
                return cached;
            }
            String fb = readAssetFallback(context);
            if (!TextUtils.isEmpty(fb)) {
                LogBridge.w(TAG, "源6(咪咕)：非WiFi出口且无本地频道表，直接启用 assets 兜底（"
                        + countM3uChannels(fb) + " 频道，不请求控制面）");
                return fb;
            }
            LogBridge.w(TAG, "源6(咪咕)：非WiFi出口但缓存与 assets 兜底均不可用，仍尝试在线目录");
        }

        // ---- ② 网络：服务端下发优先（对应原版链路第 ① 步） ----
        String fresh = fetchNowTvAsM3u(context);
        if (!TextUtils.isEmpty(fresh) && countM3uChannels(fresh) >= MIN_CHANNELS) {
            writeDiskCache(context, fresh);
            LogBridge.i(TAG, "源6(咪咕)：服务端下发频道表构建成功，已写缓存");
            return fresh;
        }

        // ---- ②b 降级：咪咕官方频道目录 ----
        fresh = fetchCatalogAsM3u();
        if (!TextUtils.isEmpty(fresh) && countM3uChannels(fresh) >= MIN_CHANNELS) {
            writeDiskCache(context, fresh);
            LogBridge.i(TAG, "源6(咪咕)：官方目录构建成功，已写缓存");
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
            LogBridge.w(TAG, "源6(咪咕)：目录与缓存均不可用，启用 assets 静态兜底频道表");
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
                LogBridge.w(TAG, "服务端未下发频道（或接口不可用）");
                return null;
            }
            Set<String> groups = new LinkedHashSet<>();
            StringBuilder sb = new StringBuilder(channels.size() * 110 + 16);
            sb.append("#EXTM3U\n");
            for (NowTvSourceApi.Channel c : channels) {
                String group = esc(TextUtils.isEmpty(c.group) ? GROUP_FALLBACK : c.group);
                String name = esc(c.name);
                groups.add(group);
                sb.append("#EXTINF:-1 tvg-id=\"nowtv:").append(c.tvId)
                  .append("\" tvg-name=\"").append(name)
                  .append("\" group-title=\"").append(group)
                  .append("\",").append(name).append("\n");
                sb.append(SCHEME_NOWTV).append(c.tvId).append("\n");
            }
            LogBridge.i(TAG, "源6(咪咕)：服务端下发 " + channels.size() + " 个频道，"
                    + groups.size() + " 个分组：" + groups);
            return sb.toString();
        } catch (Throwable t) {
            LogBridge.w(TAG, "服务端下发异常：" + t.getClass().getSimpleName()
                    + " " + t.getMessage());
            return null;
        }
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
                    LogBridge.w(TAG, "目录中未找到分类：" + wanted);
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
                    LogBridge.w(TAG, "分类[" + wanted + "]明细为空");
                    continue;
                }
                JSONObject catJson = new JSONObject(catText);
                JSONObject catBody = catJson.optJSONObject("body");
                JSONArray dataList = catBody == null ? null : catBody.optJSONArray("dataList");
                if (dataList == null) {
                    LogBridge.w(TAG, "分类[" + wanted + "]无 dataList");
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
                LogBridge.i(TAG, "分类[" + wanted + "] vomsID=" + vomsId + " 取到 " + n + " 个频道");
            }

            if (rows.isEmpty()) {
                LogBridge.w(TAG, "目录构建结果为空");
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
            LogBridge.i(TAG, "assets 兜底频道表命中，共 " + rows.size() + " 个频道");
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
            sb.append("#EXTINF:-1 tvg-id=\"").append(MiguUrlResolver.TVG_PREFIX).append(pid)
              .append("\" tvg-name=\"").append(name)
              .append("\" group-title=\"").append(group)
              .append("\",").append(name).append("\n");
            sb.append(MiguUrlResolver.SCHEME).append(pid).append("\n");
        }
        return sb.toString();
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
