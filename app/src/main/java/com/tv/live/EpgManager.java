package com.tv.live;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.tv.live.manager.ChannelPanelController;
import com.tv.live.util.AppExecutors;
import com.tv.live.util.CacheManager;
import com.tv.live.util.LogBridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

@SuppressLint("StaticFieldLeak")
public class EpgManager {

    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new ConcurrentHashMap<>();

    private final Map<String, List<Channel.EpgItem>> huyaGeneratedEpgMap = new ConcurrentHashMap<>();

    /**
     * 【2026-10-08】咪咕轮播节目单：tvId → 节目列表。
     *
     * <p>来源：播放轮播频道时 {@code CarouselApi} 顺带缓存下来的
     * {@code /p/get_current_carousel} 响应 {@code data[]}（见
     * {@link #putMiguEpgFromPrograms(int, java.util.List)}）。
     * <b>零额外请求</b> —— 只把「本来就要请求」的那次响应接进 EPG，
     * 不为此另发任何请求。
     */
    private final Map<Integer, List<Channel.EpgItem>> miguEpgMap = new ConcurrentHashMap<>();

    /**
     * 【2026-10-08】咪咕多天节目单（EPG 过去7天/未来7天面板用）：key = {@code tvId|yyyyMMdd}。
     *
     * <p>来源：用户在面板选中某个非今天日期时，按天调
     * {@code CarouselApi.fetchDaySchedule(tvId, dateYmd)}（program_v3.php）拉取，
     * <b>每「频道×天」至多 1 次</b>（缓存 + 进程级去重，失败不重试 —— 红线）。
     * 今天的节目单不走这里（仍由 {@link #miguEpgMap} 供给，原逻辑不变）。
     */
    private final Map<String, List<Channel.EpgItem>> miguDayEpgMap = new ConcurrentHashMap<>();

    private String epgUrl = UrlConfig.EPG_URL;
    private boolean hasPrintedSample = false;

    private CacheManager cacheManager;
    private Context context;

    private final Map<String, String> normalizedNameCache = new ConcurrentHashMap<>();

    /** 【2026-10-05】归一化频道名 → EPG 原始频道名 的反向索引。
     *  在 parseXml 完成后一次性构建，getEpg(name) 据此 O(1) 命中，
     *  消除「每次查询都全表模糊匹配（516 频道 × normalize+score）」的开销。 */
    private final Map<String, String> normalizedEpgIndex = new ConcurrentHashMap<>();

    /** 【2026-10-05】频道名 → EPG 列表的匹配结果缓存（含未命中空结果）。
     *  避免每次打开面板/换台都对 channelEpgMap 全表模糊匹配（2000 频道 × normalize+score）。 */
    private final Map<String, List<Channel.EpgItem>> epgMatchCache = new ConcurrentHashMap<>();

    private static final String CACHE_KEY_EPG = "epg";

    /** 【2026-10-05】EPG 缓存有效期：7 天。7 天内启动直接用本地缓存，不重新下载，
     *  避免 EPG 链接的下载次数限制；满 7 天才重新拉一次。 */
    private static final long EPG_CACHE_VALID_MS = 7L * 24 * 60 * 60 * 1000;

    /** 【2026-10-05 修复】缓存 key 绑定当前 EPG 源 URL：每个源独立缓存文件，
     *  切换订阅源后自动落到新 key，彻底避免「旧源缓存被当新源数据用」的错乱。
     *  不再依赖单独的 epg_cache_url SP 记录（那套机制在切源 clearAll 后残留旧标记，
     *  导致重启后 isEpgCacheFresh 误判、加载第一条源数据）。 */
    private String getEpgCacheKey() {
        String url = (epgUrl == null) ? "" : epgUrl;
        int h = url.hashCode();
        return CACHE_KEY_EPG + "_" + Integer.toHexString(h);
    }

    private static final int MAX_CHANNELS = 2000;
    private static final int MAX_PROGRAMS_PER_CHANNEL = 500;

    public static EpgManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new EpgManager(ctx.getApplicationContext());
        }
        return instance;
    }

    public static EpgManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("EpgManager 未初始化，请先调用 getInstance(Context)");
        }
        return instance;
    }

    private EpgManager(Context ctx) {
        this.context = ctx;
        this.cacheManager = CacheManager.getInstance(ctx);
    }

    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    public void loadEpgFromM3u(String m3uUrl, Runnable callback) {
        AppExecutors.io(() -> {
            String extractedEpgUrl = extractEpgUrlFromM3u(m3uUrl);
            if (extractedEpgUrl != null && !extractedEpgUrl.isEmpty()) {
                epgUrl = extractedEpgUrl;
            }
            loadEpg(callback);
        });
    }

    private String extractEpgUrlFromM3u(String m3uUrl) {
        BufferedReader reader = null;
        try (okhttp3.Response response = com.tv.live.util.NetUtil.getInstance().syncGet(m3uUrl)) {
            if (!response.isSuccessful() || response.body() == null) return null;
            InputStream is = response.body().byteStream();
            if (m3uUrl.endsWith(".gz")) {
                is = new GZIPInputStream(is);
            }
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 10) {
                lineCount++;
                if (line.contains("x-tvg-url") || line.contains("tvgtvg-url")) {
                    int start = line.indexOf("\"");
                    if (start >= 0) {
                        int end = line.indexOf("\"", start + 1);
                        if (end > start) {
                            return line.substring(start + 1, end).trim();
                        }
                    }
                    String[] parts = line.split("x-tvg-url=");
                    if (parts.length >= 2) {
                        String urlPart = parts[1].trim();
                        if (urlPart.startsWith("\"")) urlPart = urlPart.substring(1);
                        int spaceIdx = urlPart.indexOf(" ");
                        if (spaceIdx > 0) urlPart = urlPart.substring(0, spaceIdx);
                        if (urlPart.endsWith("\"")) urlPart = urlPart.substring(0, urlPart.length() - 1);
                        return urlPart.trim();
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    public void loadEpg(Runnable callback) {
        // 【2026-10-05】串行执行器：EPG 的缓存加载/下载/解析必须全链路串行，
        // 并行会导致 channelEpgMap.clear() 与 parseXml 竞态（节目单随机空白）。
        AppExecutors.serial(() -> {
            LogBridge.i("EpgManager", "【EPG】loadEpg 任务开始执行（serial 队列已到达），url=" + epgUrl);
            // 【2026-10-05】7 天内不重复下载：缓存仍新鲜（存在+<7天+来源匹配）时直接读本地，
            // 跳过网络请求，避免 EPG 链接的下载次数限制。
            // 【2026-10-05 修复】若缓存加载后 channelEpgMap 仍为空（缓存损坏/解析 0 频道），
            // 必须继续走下载兜底——原实现直接 return，坏缓存会导致 7 天内永久空白。
            boolean usedCache = false;
            if (isEpgCacheFresh()) {
                if (channelEpgMap.isEmpty()) {
                    usedCache = loadEpgFromCache();
                } else {
                    usedCache = true;
                }
                if (usedCache) {
                    LogBridge.i("EpgManager", "【EPG】缓存未满 7 天，跳过下载，直接使用本地缓存");
                    notifyEpgDataReady();
                    if (callback != null) {
                        new Handler(Looper.getMainLooper()).post(callback);
                    }
                    return;
                }
                LogBridge.w("EpgManager", "【EPG】缓存加载后仍无数据（损坏/来源不匹配），回退网络下载");
            }

            long t0 = System.currentTimeMillis();
            LogBridge.i("EpgManager", "【EPG】开始下载节目单...");
            try (okhttp3.Response response = getEpgHttpClient().newCall(
                    new okhttp3.Request.Builder().url(epgUrl).build()).execute()) {
                LogBridge.i("EpgManager", "【EPG】下载响应 code=" + response.code()
                        + "，耗时 " + (System.currentTimeMillis() - t0) + "ms");
                if (!response.isSuccessful() || response.body() == null) return;

                InputStream rawIn = response.body().byteStream();
                InputStream in = epgUrl.endsWith(".gz") ? new GZIPInputStream(rawIn) : rawIn;

                try {
                    long savedBytes = cacheManager.saveFileCache(getEpgCacheKey(), in);
                    if (savedBytes <= 0) {
                        return;
                    }
                    long tDownload = System.currentTimeMillis();

                    hasPrintedSample = false;
                    channelEpgMap.clear();
                    epgMatchCache.clear();

                    InputStream cacheIs = openEpgCacheStream();
                    if (cacheIs == null) {
                        return;
                    }

                    try {
                        parseXml(cacheIs);
                    } finally {
                        cacheIs.close();
                    }
                    LogBridge.i("EpgManager", "【EPG】网络节目单下载完成：下载+落盘 "
                            + (tDownload - t0) + "ms，解析 " + (System.currentTimeMillis() - tDownload)
                            + "ms，频道数=" + channelEpgMap.size());
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            notifyEpgDataReady();
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        });
    }

    public boolean loadEpgFromCache() {
        try {
            File f = getEpgCacheFile();
            if (f == null || !f.exists()) {
                return false;
            }

            hasPrintedSample = false;
            channelEpgMap.clear();
            epgMatchCache.clear();

            long t0 = System.currentTimeMillis();
            InputStream cacheIs = openEpgCacheStream();
            if (cacheIs == null) {
                return false;
            }
            try {
                parseXml(cacheIs);
            } finally {
                cacheIs.close();
            }

            // 【2026-10-05】坏缓存自愈：解析出 0 个频道（文件损坏/被截断/格式不兼容）时
            // 删除坏缓存文件，让下次 loadEpg 走下载兜底，
            // 避免「7 天内永不重下 → 节目单永久空白」。
            if (channelEpgMap.isEmpty()) {
                LogBridge.w("EpgManager", "【EPG】缓存解析出 0 个频道（疑似损坏），删除坏缓存");
                try { f.delete(); } catch (Exception ignored) {}
                return false;
            }

            LogBridge.i("EpgManager", "【EPG】本地缓存节目单加载完成：解析 "
                    + (System.currentTimeMillis() - t0) + "ms，频道数=" + channelEpgMap.size());

            notifyEpgDataReady();
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    /** EPG 缓存文件（key 绑定当前源 URL，直接用 lastModified 判 7 天） */
    private File getEpgCacheFile() {
        return cacheManager.getCacheFile(getEpgCacheKey());
    }

    /** EPG 专用 HTTP 客户端：【2026-10-05】加 callTimeout 总时长上限。
     *  通用 NetUtil 的 readTimeout 只是「两次读之间」的间隔——EPG 服务器慢速滴流时
     *  总时长可无限长，曾把串行队列堵死 2 分钟+（节目单空白元凶之一）。
     *  EPG 是 7 天一次的低频请求，独立客户端无复用压力。 */
    private volatile okhttp3.OkHttpClient epgHttpClient;
    private okhttp3.OkHttpClient getEpgHttpClient() {
        if (epgHttpClient == null) {
            synchronized (this) {
                if (epgHttpClient == null) {
                    epgHttpClient = new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return epgHttpClient;
    }

    /** 打开 EPG 缓存流：只要文件存在就返回，不做 24h 过期判断（EPG 走自己的 7 天策略） */
    private InputStream openEpgCacheStream() {
        File f = getEpgCacheFile();
        if (f == null || !f.exists()) return null;
        try {
            return new java.io.FileInputStream(f);
        } catch (Exception e) {
            return null;
        }
    }

    /** EPG 缓存是否可用：文件存在 且 <7 天（key 已绑定源 URL，无需再比对来源）。 */
    private boolean isEpgCacheFresh() {
        File f = getEpgCacheFile();
        if (f == null || !f.exists()) {
            LogBridge.w("EpgManager", "【EPG】isEpgCacheFresh=false：文件不存在或 null，key=" + getEpgCacheKey()
                    + "，path=" + (f == null ? "null" : f.getAbsolutePath()) + "，epgUrl=" + epgUrl);
            return false;
        }
        long age = System.currentTimeMillis() - f.lastModified();
        boolean fresh = age < EPG_CACHE_VALID_MS;
        LogBridge.i("EpgManager", "【EPG】isEpgCacheFresh=" + fresh + "，age=" + age + "ms，file="
                + f.getName() + "，len=" + f.length());
        return fresh;
    }

    /**
     * 【2026-10-05 性能修复】原实现对每个 <programme> 都创建 2~3 个 Calendar + 2 次
     * SimpleDateFormat.parse + 2 次 String.format，几万条节目在 TV 低端盒子上解析 10s+，
     * 期间 channelEpgMap 为空 → 右侧 EPG 面板长时间空白。
     * 改用纯整数历法（Howard Hinnant days_from_civil/civil_from_days）直接算
     * ymd/HHMM/周几，不再实例化 Calendar、不再 parse/format，实测提速 10 倍以上。
     */
    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        // 今天 00:00 的天数序号（epoch day），循环外只算一次
        Calendar todayCal = Calendar.getInstance();
        long todayDays = daysFromCivil(
                todayCal.get(Calendar.YEAR),
                todayCal.get(Calendar.MONTH) + 1,
                todayCal.get(Calendar.DAY_OF_MONTH));
        long maxDays = todayDays + 7;   // 只保留未来 7 天内（与原 maxDate 逻辑对齐）

        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {

                    if (currentChannelName != null && !tempPrograms.isEmpty()) {
                        if (channelEpgMap.size() < MAX_CHANNELS) {
                            channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                        }
                        tempPrograms.clear();
                    }
                    currentChannelName = null;
                }

                if ("display-name".equals(tag)) {
                    currentChannelName = xml.nextText().trim();
                }

                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start");
                    String stop = xml.getAttributeValue(null, "stop");
                    if (start == null || stop == null) continue;

                    try {
                        // 时区偏移解析（保持原逻辑：' ' 后跟 [+/-]HHMM）
                        int offsetMinutes = 8 * 60;
                        int spaceIdx = start.indexOf(' ');
                        if (spaceIdx > 0 && spaceIdx + 5 <= start.length()) {
                            String tzStr = start.substring(spaceIdx).trim();
                            try {
                                char sign = tzStr.charAt(0);
                                int hh = Integer.parseInt(tzStr.substring(1, 3));
                                int mm = Integer.parseInt(tzStr.substring(3, 5));
                                int raw = hh * 60 + mm;
                                if (sign == '-') raw = -raw;
                                offsetMinutes = raw;
                            } catch (Exception ignore) {}
                        }
                        int deltaMin = (8 * 60) - offsetMinutes;   // 统一折算到东八区

                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);
                        if (start.length() < 12 || stop.length() < 12) continue;

                        // 纯整数解析 yyyyMMddHHmmss → epoch 分钟（东八区）
                        long startTotalMin = epochMinuteOf(
                                Integer.parseInt(start.substring(0, 4)),
                                Integer.parseInt(start.substring(4, 6)),
                                Integer.parseInt(start.substring(6, 8)),
                                Integer.parseInt(start.substring(8, 10)),
                                Integer.parseInt(start.substring(10, 12)),
                                deltaMin);

                        long startDays = Math.floorDiv(startTotalMin, 1440L);
                        if (startDays > maxDays) {
                            continue;
                        }

                        long dayMin = Math.floorMod(startTotalMin, 1440L);
                        int startHH = (int) (dayMin / 60);
                        int startMI = (int) (dayMin % 60);

                        // ymd（用于按天过滤）
                        long[] civil = civilFromDays(startDays);
                        int ymd = (int) (civil[0] * 10000 + civil[1] * 100 + civil[2]);

                        // 周几：1970-01-01 是周四 → (epochDays + 4) mod 7，0=周日
                        String dayName = (startDays == todayDays)
                                ? "今天"
                                : weekDays[(int) Math.floorMod(startDays + 4, 7L)];

                        // 节目结束时刻
                        String stopHHMM;
                        try {
                            long stopTotalMin = epochMinuteOf(
                                    Integer.parseInt(stop.substring(0, 4)),
                                    Integer.parseInt(stop.substring(4, 6)),
                                    Integer.parseInt(stop.substring(6, 8)),
                                    Integer.parseInt(stop.substring(8, 10)),
                                    Integer.parseInt(stop.substring(10, 12)),
                                    deltaMin);
                            long stopDayMin = Math.floorMod(stopTotalMin, 1440L);
                            stopHHMM = twoDigits((int) (stopDayMin / 60)) + ":" + twoDigits((int) (stopDayMin % 60));
                        } catch (Exception ex) {
                            stopHHMM = start.substring(8, 10) + ":" + start.substring(10, 12);
                        }
                        String timeStr = twoDigits(startHH) + ":" + twoDigits(startMI) + " - " + stopHHMM;

                        if (tempPrograms.size() >= MAX_PROGRAMS_PER_CHANNEL) {
                            continue;
                        }
                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false, ymd);
                        tempPrograms.add(item);

                    } catch (Exception e) {
                    }
                }

                if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                    String title = xml.nextText().trim();
                    tempPrograms.get(tempPrograms.size() - 1).title = title;
                }
            }

            xml.next();
        }

        if (currentChannelName != null && !tempPrograms.isEmpty()) {
            if (channelEpgMap.size() < MAX_CHANNELS) {
                channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
            }
        }

        // 【2026-10-05 提速】解析完成后一次性构建「归一化频道名 → 原始名」反向索引，
        // 供 getEpg(name) O(1) 命中，避免每次查询全表模糊匹配。
        buildNormalizedEpgIndex();
    }

    /** 构建归一化频道名反向索引（parseXml 完成后调用一次）。 */
    private void buildNormalizedEpgIndex() {
        normalizedEpgIndex.clear();
        for (String epgName : channelEpgMap.keySet()) {
            if (epgName == null || epgName.isEmpty()) continue;
            String normalized = normalizeChannelName(epgName);
            // 归一化后重名时保留先到者（与原全表模糊匹配「取最高分」语义基本一致）
            if (!normalizedEpgIndex.containsKey(normalized)) {
                normalizedEpgIndex.put(normalized, epgName);
            }
        }
    }

    /** Howard Hinnant days_from_civil：y-m-d → epoch 天数（1970-01-01 = 0），纯整数无历法对象 */
    private static long daysFromCivil(long y, long m, long d) {
        y -= (m <= 2) ? 1 : 0;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;                                  // [0, 399]
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1; // [0, 365]
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;          // [0, 146096]
        return era * 146097 + doe - 719468;
    }

    /** Howard Hinnant civil_from_days：epoch 天数 → [y, m, d] */
    private static long[] civilFromDays(long z) {
        z += 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;                               // [0, 146096]
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; // [0, 399]
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);        // [0, 365]
        long mp = (5 * doy + 2) / 153;                             // [0, 11]
        long d = doy - (153 * mp + 2) / 5 + 1;                     // [1, 31]
        long m = mp + (mp < 10 ? 3 : -9);                          // [1, 12]
        return new long[]{y + ((m <= 2) ? 1 : 0), m, d};
    }

    /** y/m/d/h/m（本地时刻）+ 时区修正分钟 → 东八区 epoch 分钟 */
    private static long epochMinuteOf(long y, long mo, long d, long hh, long mi, int deltaMin) {
        return daysFromCivil(y, mo, d) * 1440L + hh * 60L + mi + deltaMin;
    }

    private static String twoDigits(int v) {
        return (v < 10 ? "0" : "") + v;
    }

    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        if (channelEpgMap.containsKey(channelName)) {
            return channelEpgMap.get(channelName);
        }

        // 匹配结果缓存：模糊匹配是 O(N) 全表遍历，同一频道名反复查询（开面板/换台）代价高
        List<Channel.EpgItem> cached = epgMatchCache.get(channelName);
        if (cached != null) {
            return cached;
        }

        // 【2026-10-05 提速】先走反向索引 O(1)：归一化频道名直接命中 EPG 原始名，
        // 覆盖绝大多数「同名/轻微差异（卫视/高清/HD/台 等后缀）」场景，跳过全表遍历。
        String cleanName = normalizeChannelName(channelName);
        String exactEpgName = normalizedEpgIndex.get(cleanName);
        if (exactEpgName != null) {
            List<Channel.EpgItem> r = channelEpgMap.get(exactEpgName);
            if (r != null) {
                epgMatchCache.put(channelName, r);
                return r;
            }
        }

        String bestMatch = null;
        int bestScore = 0;

        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String epgName = entry.getKey();
            String cleanEpgName = normalizeChannelName(epgName);

            int score = calculateMatchScore(cleanName, cleanEpgName);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = epgName;
            }
        }

        List<Channel.EpgItem> result;
        if (bestMatch != null && bestScore >= 20) {
            result = channelEpgMap.get(bestMatch);
        } else {
            result = new ArrayList<>();
        }
        if (result == null) result = new ArrayList<>();
        epgMatchCache.put(channelName, result);
        return result;
    }

    public List<Channel.EpgItem> getEpg(Channel channel) {
        if (channel == null) {
            return new ArrayList<>();
        }

        // 【2026-10-05】虎牙两条线路（一起看 + 固定分组）不需要 EPG 节目单：
        // 直接返回空，既不做回看历史匹配，也不做 name 模糊匹配（否则会误配到
        // 普通直播源同名频道，显示错误的节目单）。右侧面板据此展示空节目单区。
        boolean isHuyaChannel = (channel.isTogetherWatch() || channel.getHuyaRoomId() > 0);
        if (isHuyaChannel) {
            return new ArrayList<>();
        }

        // 【2026-10-10】内置源7（afhgrj 影视）：频道即一部影片，EPG 面板改为「集数列表」，
        //   点击某一集即播放该集。只依赖 channelId 前缀 afhgrj: 判定，源1~6 行为零变化。
        //   只读内存缓存（不联网）；未命中则后台抓一次详情页，完成后通知面板重渲染。
        String afhgrjVodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(channel);
        if (!TextUtils.isEmpty(afhgrjVodId)) {
            List<Channel.EpgItem> episodes =
                    com.tv.live.afhgrj.AfhgrjUrlResolver.getEpisodes(afhgrjVodId);
            if (episodes != null && !episodes.isEmpty()) {
                return episodes;
            }
            final String vid = afhgrjVodId;
            AppExecutors.io(() -> {
                List<Channel.EpgItem> l =
                        com.tv.live.afhgrj.AfhgrjUrlResolver.fetchEpisodes(vid);
                if (l != null && !l.isEmpty()) {
                    LogBridge.i("EpgManager", "【EPG·源7】集数列表就绪 vodId=" + vid
                            + " 共 " + l.size() + " 集");
                    notifyEpgDataReady();
                }
            });
            return new ArrayList<>();
        }

        // 【2026-10-12】内置源5（aiqiyi）VOD 频道：EPG 面板改为「选集列表」（参照源7 模式）。
        //   只依赖 channelId 前缀 renyibo-vod: 判定，源1~7 其它频道行为零变化。
        //   只读内存缓存（不联网）；未命中则后台抓一次 video_detail（与取流同端点、
        //   同双闸门约束），完成后通知面板重渲染。项 customPlayUrl 已是视频_detail
        //   明文里的直链 m3u8，点击即播（无需起播惰性解析）。
        String renyiboVodId = com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(channel.getChannelId());
        if (!TextUtils.isEmpty(renyiboVodId)) {
            List<Channel.EpgItem> episodes =
                    com.tv.live.renyibo.RenYiBoVodSourceLoader.getVodEpisodes(renyiboVodId);
            if (episodes != null && !episodes.isEmpty()) {
                return episodes;
            }
            final String vid = renyiboVodId;
            AppExecutors.io(() -> {
                List<Channel.EpgItem> l =
                        com.tv.live.renyibo.RenYiBoVodSourceLoader.fetchVodEpisodes(vid);
                if (l != null && !l.isEmpty()) {
                    LogBridge.i("EpgManager", "【EPG·源5】选集列表就绪 vodId=" + vid
                            + " 共 " + l.size() + " 项");
                    notifyEpgDataReady();
                }
            });
            return new ArrayList<>();
        }

        // 【2026-10-08】咪咕轮播/剧场频道（channelId = nowtv:<tvId>）：优先用咪咕自带的
        //   轮播节目单（播放时顺带缓存，零额外请求）。这类频道在 XMLTV 广播节目单里
        //   通常匹配不到（频道名是「电影-科幻」这类聚合名），原先面板只能空白。
        //   这里只读缓存 —— 不联网、不批量请求；没有缓存则回退原有 name 模糊匹配。
        int miguTvId = miguTvIdOf(channel);
        if (miguTvId > 0) {
            List<Channel.EpgItem> migu = miguEpgMap.get(miguTvId);
            if (migu != null && !migu.isEmpty()) {
                return migu;
            }
        }

        return getEpg(channel.getName());
    }

    /**
     * 从频道 id（{@code nowtv:<tvId>}）还原咪咕 tvId；非畅通TV咪咕通道返回 0。
     * 与 {@code TVPlayerManager.miguTvIdOf} 同口径（此处独立实现避免循环依赖）。
     */
    public static int miguTvIdOf(Channel ch) {
        if (ch == null) return 0;
        String cid = ch.getChannelId();
        if (TextUtils.isEmpty(cid)) return 0;
        String prefix = com.tv.live.migu.MiguSourceLoader.TVG_PREFIX_NOWTV;
        if (!cid.startsWith(prefix)) return 0;
        try {
            return Integer.parseInt(cid.substring(prefix.length()).trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 该频道是否已有咪咕轮播节目单（供 UI 判断「是否走咪咕 EPG」）。 */
    public boolean hasMiguEpg(Channel ch) {
        int tvId = miguTvIdOf(ch);
        if (tvId <= 0) return false;
        List<Channel.EpgItem> l = miguEpgMap.get(tvId);
        return l != null && !l.isEmpty();
    }

    /**
     * 【2026-10-08】把一次轮播接口解析出的节目单转成 EPG 条目并缓存。
     *
     * <p>由 {@code TVPlayerManager} 在轮播频道解析成功时调用 —— 数据来自本次
     * {@code /p/get_current_carousel} 响应，<b>不新增任何请求</b>。
     *
     * @param tvId     轮播频道 tvId
     * @param programs {@code CarouselApi.getCachedPrograms(tvId)} 的结果
     */
    public void putMiguEpgFromPrograms(int tvId,
                                       List<com.tv.live.migu.source.CarouselApi.Program> programs) {
        if (tvId <= 0 || programs == null || programs.isEmpty()) return;

        List<Channel.EpgItem> items = buildMiguEpgItems(tvId, programs);
        if (items.isEmpty()) return;

        miguEpgMap.put(tvId, items);
        LogBridge.i("EpgManager", "【EPG·咪咕】轮播节目单已接入：tvId=" + tvId
                + "，节目 " + items.size() + " 条（首条《" + items.get(0).title + "》）");

        // 面板/信息栏可能已经打开 → 通知重渲染
        notifyEpgDataReady();
    }

    /**
     * 【2026-10-08】咪咕节目单 → EPG 条目的统一转换（轮播接入与多天面板共用）。
     * 条目带 {@code dateYMD/startTs/miguProgramId}：面板按 YMD 精确过滤，
     * 过去条目直接命中「回看」按钮 → {@code /p/get_carousel_video?pid=} 已验证链路。
     */
    private static List<Channel.EpgItem> buildMiguEpgItems(int tvId,
            List<com.tv.live.migu.source.CarouselApi.Program> programs) {
        List<Channel.EpgItem> items = new ArrayList<>();
        if (programs == null) return items;
        for (com.tv.live.migu.source.CarouselApi.Program p : programs) {
            if (p == null || TextUtils.isEmpty(p.title)) continue;
            Channel.EpgItem it = new Channel.EpgItem(
                    dayNameOf(p.startTs), timeRangeOf(p.startTs, p.endTs),
                    p.title, p.offsetSec > 0, ymdOf(p.startTs));
            it.fromMiguCarousel = true;
            it.miguProgramId = p.programId;
            it.miguTvId = tvId;
            it.startTs = p.startTs;
            it.endTs = p.endTs;
            items.add(it);
        }
        Collections.sort(items, (a, b) -> Long.compare(a.startTs, b.startTs));
        return items;
    }

    /**
     * ★【2026-10-08】取指定「日期偏移」的咪咕多天节目单（EPG 面板过去7天/未来7天）。
     *
     * <p>无缓存时按天发 <b>一次</b> {@code program_v3.php?date=&tv_id=} 请求
     * （每「频道×天」至多 1 次，进程级去重；失败不重试、不并发 —— 红线）。
     * {@code dayOffset == 0}（今天）不走本方法 —— 今天仍由 {@link #miguEpgMap} 供给，
     * 原 EPG 逻辑不受影响。
     *
     * @param tvId      咪咕频道 tvId
     * @param dayOffset 日期偏移（今天=0，昨天=-1 …，与日期列表选中位换算一致）
     * @return 节目条目（按 startTs 升序）；未拉到返回 {@code null}
     */
    public List<Channel.EpgItem> getOrFetchMiguDayEpg(int tvId, int dayOffset) {
        if (tvId <= 0 || dayOffset == 0) return null;
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        int ymd = cal.get(Calendar.YEAR) * 10000
                + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);
        String key = tvId + "|" + ymd;
        List<Channel.EpgItem> cached = miguDayEpgMap.get(key);
        if (cached != null) return cached;

        String dateStr = String.format(Locale.ROOT, "%08d", ymd);
        List<com.tv.live.migu.source.CarouselApi.Program> programs =
                com.tv.live.migu.source.CarouselApi.fetchDaySchedule(String.valueOf(tvId), dateStr);
        if (programs == null || programs.isEmpty()) return null;

        List<Channel.EpgItem> items = buildMiguEpgItems(tvId, programs);
        if (items.isEmpty()) return null;
        miguDayEpgMap.put(key, items);
        LogBridge.i("EpgManager", "【EPG·咪咕】多天节目单接入：tvId=" + tvId
                + " date=" + dateStr + "，节目 " + items.size() + " 条");
        return items;
    }

    /** 清空某频道的咪咕节目单（频道换台/数据过期时用）。 */
    public void clearMiguEpg(int tvId) {
        if (tvId > 0) miguEpgMap.remove(tvId);
    }

    /** Unix 秒 → 「今天」/周几（与整个 EPG 面板的口径一致，本地时区）。 */
    private static String dayNameOf(long tsSec) {
        if (tsSec <= 0L) return "今天";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(tsSec * 1000L);
        Calendar today = Calendar.getInstance();
        if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return "今天";
        }
        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        return weekDays[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    private static int ymdOf(long tsSec) {
        if (tsSec <= 0L) return 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(tsSec * 1000L);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** Unix 秒起止 → {@code "HH:mm - HH:mm"}（面板按 "-" 切分取开始时间，格式须与 XMLTV 一致）。 */
    private static String timeRangeOf(long startSec, long endSec) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", Locale.CHINA);
        String s = startSec > 0L ? f.format(new java.util.Date(startSec * 1000L)) : "--:--";
        String e = endSec > 0L ? f.format(new java.util.Date(endSec * 1000L)) : "--:--";
        return s + " - " + e;
    }

    public static class PlaybackSegment {
        public final String title;
        public final long startTimeMs;
        public final long endTimeMs;
        public PlaybackSegment(String title, long startTimeMs, long endTimeMs) {
            this.title = title; this.startTimeMs = startTimeMs; this.endTimeMs = endTimeMs;
        }
    }

    private final Map<String, List<PlaybackSegment>> playbackHistoryMap = new ConcurrentHashMap<>();

    private final Map<String, Long> playingStartMap = new ConcurrentHashMap<>();

    private final Map<String, String> playingTitleMap = new ConcurrentHashMap<>();

    private final Set<OnHuyaEpgReadyListener> huyaEpgReadyListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<OnHuyaEpgReadyListener, Boolean>());

    public interface OnHuyaEpgReadyListener {
        void onHuyaEpgReady(Channel channel);
    }
    public void registerHuyaEpgReadyListener(OnHuyaEpgReadyListener listener) {
        if (listener != null) huyaEpgReadyListeners.add(listener);
    }
    public void unregisterHuyaEpgReadyListener(OnHuyaEpgReadyListener listener) {
        if (listener != null) huyaEpgReadyListeners.remove(listener);
    }

    /** 【2026-10-05】直播源 EPG 全量数据就绪通知（channelEpgMap 可用/有更新）。
     *  原问题：解析完成只回调 AppCoreManager 刷信息栏，已打开的右侧 EPG 面板不会重渲染，
     *  用户看到的是空白节目单，必须关掉重开面板才出现数据。 */
    public interface OnEpgDataReadyListener {
        void onEpgDataReady();
    }
    private final Set<OnEpgDataReadyListener> epgDataReadyListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<OnEpgDataReadyListener, Boolean>());
    public void registerEpgDataReadyListener(OnEpgDataReadyListener listener) {
        if (listener != null) epgDataReadyListeners.add(listener);
    }
    public void unregisterEpgDataReadyListener(OnEpgDataReadyListener listener) {
        if (listener != null) epgDataReadyListeners.remove(listener);
    }
    private void notifyEpgDataReady() {
        if (epgDataReadyListeners.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnEpgDataReadyListener l : epgDataReadyListeners) {
                try { l.onEpgDataReady(); } catch (Exception ignored) {}
            }
        });
    }
    private void notifyHuyaEpgReady(Channel channel) {
        if (channel == null || huyaEpgReadyListeners.isEmpty()) return;
        for (OnHuyaEpgReadyListener l : huyaEpgReadyListeners) {
            try { l.onHuyaEpgReady(channel); } catch (Exception ignored) {}
        }
    }

    private static String channelKey(Channel ch) {
        if (ch == null) return "";

        if (ch.getHuyaRoomId() > 0) return "huya:" + ch.getHuyaRoomId();
        if (!TextUtils.isEmpty(ch.getChannelId())) return "id:" + ch.getChannelId();
        return "name:" + ch.getName();
    }
    private static String channelKey(String channelName) {
        return "name:" + (channelName == null ? "" : channelName);
    }

    public void startPlayback(Channel ch, String programTitle) {
        if (ch == null) return;
        String key = channelKey(ch);
        long now = System.currentTimeMillis();
        String safeTitle = TextUtils.isEmpty(programTitle) ? (ch.getName() + " · 正在直播") : programTitle;

        Long prevStart = playingStartMap.remove(key);
        String prevTitle = playingTitleMap.remove(key);
        if (prevStart != null && now - prevStart >= 30_000L) {
            List<PlaybackSegment> list = playbackHistoryMap.get(key);
            if (list == null) {
                list = Collections.synchronizedList(new ArrayList<PlaybackSegment>());
                playbackHistoryMap.put(key, list);
            }
            list.add(new PlaybackSegment(prevTitle == null ? ch.getName() : prevTitle, prevStart, now));
        }

        playingStartMap.put(key, now);
        playingTitleMap.put(key, safeTitle);

        boolean isHuya = (ch.isTogetherWatch() || ch.getHuyaRoomId() > 0);
        if (isHuya) {
            notifyHuyaEpgReady(ch);
        }
    }

    public void stopPlayback(Channel ch) {
        if (ch == null) return;
        String key = channelKey(ch);
        Long start = playingStartMap.remove(key);
        String title = playingTitleMap.remove(key);
        if (start == null) return;
        long now = System.currentTimeMillis();
        if (now - start < 30_000L) return;
        List<PlaybackSegment> list = playbackHistoryMap.get(key);
        if (list == null) {
            list = Collections.synchronizedList(new ArrayList<PlaybackSegment>());
            playbackHistoryMap.put(key, list);
        }
        list.add(new PlaybackSegment(title == null ? ch.getName() : title, start, now));
    }

    private List<Channel.EpgItem> getPlaybackHistoryEpg(Channel ch) {
        if (ch == null) return null;
        return buildHistoryEpgFromStore(channelKey(ch), ch.getName());
    }
    private List<Channel.EpgItem> getPlaybackHistoryEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) return null;
        return buildHistoryEpgFromStore(channelKey(channelName), channelName);
    }

    private List<Channel.EpgItem> buildHistoryEpgFromStore(String key, String fallbackName) {
        List<PlaybackSegment> raw = playbackHistoryMap.get(key);
        List<PlaybackSegment> segments = new ArrayList<>();
        if (raw != null) segments.addAll(raw);

        Long playingStart = playingStartMap.get(key);
        String playingTitle = playingTitleMap.get(key);
        boolean hasPlaying = (playingStart != null);
        if (hasPlaying) {
            segments.add(new PlaybackSegment(
                    playingTitle == null ? fallbackName : playingTitle,
                    playingStart, -1L));
        }
        if (segments.isEmpty()) return null;

        Collections.sort(segments, (a, b) -> Long.compare(a.startTimeMs, b.startTimeMs));

        SimpleDateFormat sdfHm = new SimpleDateFormat("HH:mm", Locale.CHINA);
        Calendar today = Calendar.getInstance();
        List<Channel.EpgItem> result = new ArrayList<>();
        for (PlaybackSegment seg : segments) {
            boolean isPlaying = (seg.endTimeMs < 0);
            String startHm = sdfHm.format(new java.util.Date(seg.startTimeMs));
            String endHm;
            if (isPlaying) endHm = "直播中";
            else endHm = sdfHm.format(new java.util.Date(seg.endTimeMs));

            Channel.EpgItem item = new Channel.EpgItem(
                    "今天",
                    startHm + " - " + endHm,
                    seg.title,
                    isPlaying
            );
            result.add(item);
        }
        return result;
    }

    private String normalizeChannelName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (normalizedNameCache.containsKey(name)) {
            return normalizedNameCache.get(name);
        }

        String result = name.toLowerCase(Locale.ROOT);

        // 【2026-10-05 提速】原实现用 6 次正则 replaceAll("(?i)hd") 等，每次调用都编译正则，
        // 全表模糊匹配时 516 频道 × 6 正则 = 数千次正则编译+扫描，是开面板慢的隐藏元凶。
        // 已 toLowerCase，直接普通 replace 小写串即可，且按 fhd/uhd→hd 顺序防「先删 hd 把 fhd 删成 f」。
        result = result.replace("fhd", "");
        result = result.replace("uhd", "");
        result = result.replace("sdtv", "");
        result = result.replace("hdtv", "");
        result = result.replace("hd", "");
        result = result.replace("高清", "");
        result = result.replace("超清", "");
        result = result.replace("标清", "");
        result = result.replace("4k", "");
        result = result.replace("8k", "");

        result = result.replace(" ", "");
        result = result.replace("-", "");
        result = result.replace("_", "");
        result = result.replace(".", "");
        result = result.replace("·", "");
        result = result.replace(":", "");
        result = result.replace("：", "");

        result = result.replace("频道", "");
        result = result.replace("卫视", "");
        result = result.replace("电视台", "");
        result = result.replace("台", "");
        result = result.replace("传媒", "");

        result = result.replace("一套", "1套");
        result = result.replace("二套", "2套");
        result = result.replace("三套", "3套");
        result = result.replace("四套", "4套");
        result = result.replace("五套", "5套");
        result = result.replace("六套", "6套");
        result = result.replace("七套", "7套");
        result = result.replace("八套", "8套");
        result = result.replace("九套", "9套");
        result = result.replace("十套", "10套");
        result = result.replace("十一", "11");
        result = result.replace("十二", "12");
        result = result.replace("十三", "13");
        result = result.replace("十四", "14");
        result = result.replace("十五", "15");

        result = result.replace("cctv", "央视");

        normalizedNameCache.put(name, result);
        return result;
    }

    private int calculateMatchScore(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) {
            return 0;
        }

        if (s1.equals(s2)) {
            return 100;
        }

        if (s1.contains(s2) || s2.contains(s1)) {
            int minLen = Math.min(s1.length(), s2.length());
            int maxLen = Math.max(s1.length(), s2.length());
            return 50 + (minLen * 40 / maxLen);
        }

        int prefixLen = 0;
        int minLen = Math.min(s1.length(), s2.length());
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefixLen++;
            } else {
                break;
            }
        }
        if (prefixLen >= 2) {
            return prefixLen * 5;
        }

        return 0;
    }

    public String getDayName(Calendar itemCal, Calendar todayCal) {
        Calendar itemDay = Calendar.getInstance();
        itemDay.setTime(itemCal.getTime());
        itemDay.set(Calendar.HOUR_OF_DAY, 0);
        itemDay.set(Calendar.MINUTE, 0);
        itemDay.set(Calendar.SECOND, 0);
        itemDay.set(Calendar.MILLISECOND, 0);

        Calendar todayDay = Calendar.getInstance();
        todayDay.setTime(todayCal.getTime());
        todayDay.set(Calendar.HOUR_OF_DAY, 0);
        todayDay.set(Calendar.MINUTE, 0);
        todayDay.set(Calendar.SECOND, 0);
        todayDay.set(Calendar.MILLISECOND, 0);

        if (itemDay.get(Calendar.YEAR) == todayDay.get(Calendar.YEAR)
                && itemDay.get(Calendar.DAY_OF_YEAR) == todayDay.get(Calendar.DAY_OF_YEAR)) {
            return "今天";
        }

        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemDay.get(Calendar.DAY_OF_WEEK) - 1;
        return weekDays[dayOfWeek];
    }

    public int getChannelEpgMapSize() {
        return channelEpgMap.size();
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ===== EpgManagerWrapper (原 com.tv.live.widget.EpgManagerWrapper) =====
    public static class EpgManagerWrapper {
        private final ListView lvEpg;
        private Context context;
        private EpgAdapter adapter;
        private final Set<String> bookedSet = new HashSet<>();
        private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
        private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";

        private static final int COLOR_BLUE = 0xFF40A9FF;
        private static final int COLOR_BG_BLUE = 0x3340A9FF;
        private static final int COLOR_WHITE = 0xFFFFFFFF;
        private static final int COLOR_GRAY = 0xFFCCCCCC;

        private int selectedPosition = 0;
        private int playingIndex = -1;
        private int selectDayIndex = 0;

        private BroadcastReceiver reminderReceiver;

        public EpgManagerWrapper(Context context, ListView lvEpg) {
            this.context = context;
            this.lvEpg = lvEpg;

            lvEpg.setFocusable(true);
            lvEpg.setFocusableInTouchMode(false);
            lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

            lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedPosition = position;
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            lvEpg.setOnItemClickListener((parent, view, position, id) -> {
                if (position == selectedPosition) {

                    TextView actionBtn = view.findViewById(R.id.tv_action);
                    if (actionBtn != null && actionBtn.isEnabled()) {
                        actionBtn.performClick();
                    }
                } else {

                    selectedPosition = position;
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    lvEpg.setSelection(position);
                }
            });

            registerReminderReceiver();
        }

        public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
            if (currentChannel == null) return;
            playingIndex = -1;
            selectDayIndex = dateIndex;
            epgEndTimeMap.clear();
            AppExecutors.io(() -> {
                List<Channel.EpgItem> originEpgList;
                try {
                    // 【2026-10-08】内置咪咕源 + 非今天日期：按天取多天节目单（program_v3，
                    //   每「频道×天」至多 1 次请求，缓存+去重，失败不重试 —— 红线）。
                    //   今天（dateIndex==0）与所有非咪咕源仍走原逻辑，完全不变。
                    int miguTvId = EpgManager.miguTvIdOf(currentChannel);
                    if (miguTvId > 0 && dateIndex != 0) {
                        List<Channel.EpgItem> dayList =
                                EpgManager.getInstance().getOrFetchMiguDayEpg(miguTvId, dateIndex);
                        originEpgList = (dayList == null) ? new ArrayList<>() : new ArrayList<>(dayList);
                    } else {
                        List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel);
                        originEpgList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
                    }
                } catch (Exception e) {
                    originEpgList = new ArrayList<>();
                }
                List<Channel.EpgItem> data = new ArrayList<>();
                if (!originEpgList.isEmpty()) {
                    String targetDay;
                    String targetWeekDay = null;

                    int dayOffset = dateIndex;
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_YEAR, dayOffset);

                    int targetYMD = cal.get(Calendar.YEAR) * 10000
                            + (cal.get(Calendar.MONTH) + 1) * 100
                            + cal.get(Calendar.DAY_OF_MONTH);
                    int w = cal.get(Calendar.DAY_OF_WEEK);
                    String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                    String weekDay = weekMap[w - 1];
                    if (dateIndex == 0) {
                        targetDay = "今天";
                    } else {
                        targetDay = weekDay;
                    }
                    targetWeekDay = weekDay;
                    for (Channel.EpgItem item : originEpgList) {
                        if (item.dayName == null) continue;
                        String dayName = item.dayName.trim();
                        boolean match = false;

                        if (item.dateYMD > 0 && targetYMD > 0) {
                            match = (item.dateYMD == targetYMD);
                        }

                        if (!match) match = targetDay.equals(dayName);

                        if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);

                        if (!match && item.dateYMD > 0 && targetYMD > 0) {
                            match = (item.dateYMD == targetYMD);
                        }
                        if (match) {
                            data.add(item);
                        }
                    }
                    Collections.sort(data, (a, b) -> a.time.compareTo(b.time));
                    if (dateIndex == 0) {
                        String now = getNow();
                        Channel.EpgItem playing = null;
                        for (int i = 0; i < data.size(); i++) {
                            Channel.EpgItem curr = data.get(i);
                            boolean isHuyaHistoryEpg = (currentChannel.isTogetherWatch() || currentChannel.getHuyaRoomId() > 0)
                                    && (curr.time != null) && (curr.time.contains("直播中") || curr.time.contains(" - "));
                            if (!isHuyaHistoryEpg && !TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                                curr.time = curr.time.split("-")[0].trim();
                            if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                                if (isHuyaHistoryEpg && curr.time.contains(" - ")) {
                                    String[] parts = curr.time.split(" - ");
                                    String startPart = parts[0].trim();
                                    String endPart = parts.length > 1 ? parts[1].trim() : "23:59";
                                    if (!endPart.contains(":") || endPart.equals("直播中")) endPart = "23:59";
                                    epgEndTimeMap.put(curr, endPart);
                                    curr.time = startPart;
                                } else if (i + 1 < data.size()) {
                                    Channel.EpgItem next = data.get(i + 1);
                                    String nextTime = next.time;
                                    if (nextTime != null && nextTime.contains(" - ")) nextTime = nextTime.split(" - ")[0].trim();
                                    if (nextTime != null && nextTime.contains("-")) nextTime = nextTime.split("-")[0].trim();
                                    epgEndTimeMap.put(curr, nextTime);
                                } else {
                                    epgEndTimeMap.put(curr, addOneHour(curr.time));
                                }
                            }
                            curr.isPlaying = false;
                            String currEnd = epgEndTimeMap.get(curr);
                            if (isHuyaHistoryEpg && curr.isPlaying) {
                                playingIndex = i;
                            } else if (isTimeBetween(now, curr.time, currEnd)) {
                                curr.isPlaying = true;
                                playing = curr;
                                playingIndex = i;
                            }
                        }
                    } else {
                        playingIndex = -1;
                        for (int i = 0; i < data.size(); i++) {
                            Channel.EpgItem curr = data.get(i);
                            boolean isHuyaHistoryEpg = (currentChannel.isTogetherWatch() || currentChannel.getHuyaRoomId() > 0)
                                    && (curr.time != null) && (curr.time.contains("直播中") || curr.time.contains(" - "));
                            if (!isHuyaHistoryEpg && !TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                                curr.time = curr.time.split("-")[0].trim();
                            if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                                if (isHuyaHistoryEpg && curr.time.contains(" - ")) {
                                    String[] parts = curr.time.split(" - ");
                                    String startPart = parts[0].trim();
                                    String endPart = parts.length > 1 ? parts[1].trim() : "23:59";
                                    if (!endPart.contains(":") || endPart.equals("直播中")) endPart = "23:59";
                                    epgEndTimeMap.put(curr, endPart);
                                    curr.time = startPart;
                                } else if (i + 1 < data.size())
                                    epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                                else
                                    epgEndTimeMap.put(curr, addOneHour(curr.time));
                            }
                            curr.isPlaying = false;
                        }
                    }
                }
                // 【2026-10-13】回看/自定义选集自动定位：定位「当前正在播放」的项，
                // 而非按墙钟时间（源5/源7 选集项 time 恒空 → 永远定位第一集；
                // 普通源回看目标时间 ≠ 现在 → 定位到直播中节目）。匹配不到维持原行为。
                Channel.EpgItem curPlaying = findCurrentPlayingItem(currentChannel, data);
                if (curPlaying != null) {
                    int cp = data.indexOf(curPlaying);
                    if (cp >= 0) {
                        playingIndex = cp;
                        LogBridge.i("EpgManager", "【EPG】自动定位当前播放项 [" + cp + "] "
                                + curPlaying.title);
                    }
                }
                final List<Channel.EpgItem> finalData = data;
                final Channel finalChannel = currentChannel;
                ((MainActivity) context).runOnUiThread(() -> {
                    if (adapter == null) {
                        adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                        lvEpg.setAdapter(adapter);
                    } else {
                        adapter.setData(finalChannel, finalData, selectDayIndex);
                    }

                    if (!finalData.isEmpty()) {
                        int focusPos = 0;
                        if (playingIndex >= 0 && playingIndex < finalData.size()) {
                            focusPos = playingIndex;
                        }
                        selectedPosition = focusPos;
                        lvEpg.setSelection(focusPos);
                    }

                    adapter.notifyDataSetChanged();
                    if (!finalData.isEmpty()) {
                        lvEpg.post(() -> lvEpg.setSelection(selectedPosition));
                    }
                    scrollToCurrentProgram(finalData);
                });
            });
        }

        /**
         * 【2026-10-13】回看/自定义选集下，找「当前正在播放」的 EPG 项：
         * ① 自定义选集（customPlayUrl 非空）：源5 按 currentUrl 与选集项直链全等；
         *    源7 按 afhgrjCurNid 解析 afhgrj://&lt;vodId&gt;-&lt;sid&gt;-&lt;nid&gt; 匹配；
         * ② 普通源时移回看（playseek）：仅回看模式，解析 url 中 playseek=YYYY…HH:mm
         *    与节目起点时间对齐。
         * 匹配不到返回 null（维持原墙钟定位行为）。
         */
        private Channel.EpgItem findCurrentPlayingItem(Channel ch, List<Channel.EpgItem> data) {
            if (data == null || data.isEmpty()) return null;
            try {
                TVPlayerManager tvm = TVPlayerManager.getInstance(context);
                if (tvm == null) return null;
                String curUrl = tvm.getCurrentUrl();
                boolean isCustomPanel = !TextUtils.isEmpty(data.get(0).customPlayUrl);
                if (isCustomPanel) {
                    if (!TextUtils.isEmpty(curUrl)) {
                        for (Channel.EpgItem it : data) {
                            if (curUrl.equals(it.customPlayUrl)) return it; // 源5：直链全等
                        }
                    }
                    int nid = tvm.getAfhgrjCurNid(); // 源7：当前集
                    if (nid > 0) {
                        for (Channel.EpgItem it : data) {
                            String cp = it.customPlayUrl;
                            if (cp != null && cp.startsWith("afhgrj://")) {
                                String body = cp.substring("afhgrj://".length());
                                String[] p = body.split("-");
                                if (p.length >= 3 && String.valueOf(nid).equals(p[p.length - 1]))
                                    return it;
                            }
                        }
                    }
                    return null;
                }
                // 普通源：回看模式 + playseek 目标时间对齐
                if (!(context instanceof MainActivity)) return null;
                if (!((MainActivity) context).isInCatchUpMode()) return null;
                String hm = extractPlayseekHm(curUrl);
                if (hm == null) return null;
                for (Channel.EpgItem it : data) {
                    if (it.time != null && it.time.startsWith(hm)) return it;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        /** 从 url 的 playseek=YYYY-MM-DD HH:mm:ss-… 参数取 HH:mm；无则 null。 */
        private static String extractPlayseekHm(String url) {
            if (url == null) return null;
            int k = url.indexOf("playseek=");
            if (k < 0) return null;
            String rest = url.substring(k + "playseek=".length());
            if (rest.length() < 16) return null;
            String hm = rest.substring(11, 16);
            return hm.matches("\\d{2}:\\d{2}") ? hm : null;
        }

        private void scrollToCurrentProgram(List<Channel.EpgItem> epgList) {
            if (epgList == null || epgList.isEmpty() || selectDayIndex != 0) {
                return;
            }
            // 【2026-10-13】回看/自定义选集已按「当前播放项」定位时，禁止墙钟居中覆盖
            boolean custom = !TextUtils.isEmpty(epgList.get(0).customPlayUrl);
            boolean catchUp = (context instanceof MainActivity)
                    && ((MainActivity) context).isInCatchUpMode();
            if (custom || catchUp) {
                return;
            }
            String now = getNow();
            for (int i = 0; i < epgList.size(); i++) {
                Channel.EpgItem item = epgList.get(i);
                String start = item.time;
                String end = epgEndTimeMap.get(item);
                if (start != null && end != null && isTimeBetween(now, start, end)) {
                    final int scrollPos = i;
                    lvEpg.post(() -> {
                        lvEpg.setSelection(scrollPos);
                        lvEpg.setSelectionFromTop(scrollPos, lvEpg.getHeight() / 2);
                    });
                    break;
                }
            }
        }

        private boolean isTimeBetween(String now, String start, String end) {
            try {
                if (now == null || start == null || end == null) return false;
                return now.contains(":") && start.contains(":") && end.contains(":")
                        && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
            } catch (Exception e) {
                return false;
            }
        }

        private String addOneHour(String hm) {
            try {
                if (hm == null || !hm.contains(":")) return "23:59";
                hm = hm.trim();
                if (hm.contains("-")) hm = hm.split("-")[0].trim();
                String[] arr = hm.split(":");
                int h = Integer.parseInt(arr[0].trim());
                int m = Integer.parseInt(arr[1].trim());
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, h);
                c.set(Calendar.MINUTE, m);
                c.add(Calendar.MINUTE, 60);
                return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
            } catch (Exception e) {
                return "23:59";
            }
        }

        private String getNow() {
            return String.format(Locale.ROOT, "%02d:%02d",
                    Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                    Calendar.getInstance().get(Calendar.MINUTE));
        }

        private void registerReminderReceiver() {
            reminderReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_REMINDER.equals(intent.getAction())) {
                        String title = intent.getStringExtra("title");
                        Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_REMINDER);
            ContextCompat.registerReceiver(context, reminderReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }

        public void release() {
            if (context != null && reminderReceiver != null) {
                try {
                    context.unregisterReceiver(reminderReceiver);
                } catch (Exception ignored) {}
                reminderReceiver = null;
            }
            if (adapter != null) {
                adapter.clear();
                adapter = null;
            }
            bookedSet.clear();
            epgEndTimeMap.clear();
            if (lvEpg != null) {
                lvEpg.setAdapter(null);
                lvEpg.setOnItemSelectedListener(null);
                lvEpg.setOnFocusChangeListener(null);
                lvEpg.setOnItemClickListener(null);
            }
            context = null;
        }

        private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
            private final Context ctx;
            private Channel currentChannel;
            private List<Channel.EpgItem> list;
            private final LayoutInflater inflater;
            private int dayIndex;
            private String currentNowStr;
            private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

            private final View.OnClickListener actionClickListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Object tag = v.getTag();
                    if (!(tag instanceof ItemActionTag)) return;
                    ItemActionTag actionTag = (ItemActionTag) tag;

                    Channel.EpgItem item = actionTag.item;
                    String key = actionTag.key;

                    if (actionTag.isPast) {
                        // ★【2026-10-10】内置源7（afhgrj 影视）+【2026-10-12】内置源5（aiqiyi VOD）：
                        //   集数/选集项携带 customPlayUrl，点击直接播放。源7 起播时由
                        //   AfhgrjUrlResolver 惰性解析真实 m3u8；源5 已直接携带 video_detail
                        //   明文里的直链 m3u8，无需二次解析。
                        if (item != null && !TextUtils.isEmpty(item.customPlayUrl)) {
                            playCustomEpisode(item);
                            return;
                        }
                        // ★【2026-10-08】咪咕轮播节目：走后端回看接口
                        //   /p/get_carousel_video?pid=<节目id>（等价原版 lunbolbk://<pid>），
                        //   而非对直播地址拼 playseek（轮播源并非 IPTV PLTV 时移流）。
                        if (item != null && !TextUtils.isEmpty(item.miguProgramId)) {
                            playMiguLookBack(item);
                            return;
                        }
                        try {
                            String liveUrl = currentChannel.getPlayUrl();
                            if (TextUtils.isEmpty(liveUrl)) {
                                Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            boolean isHuyaChannel = (currentChannel.isTogetherWatch()
                                    || currentChannel.getHuyaRoomId() > 0
                                    || liveUrl.contains("huya.com") || liveUrl.contains("huya.cn"));

                            if (isHuyaChannel) {

                                if (ctx instanceof MainActivity) {
                                    MainActivity activity = (MainActivity) ctx;
                                    ChannelPanelController controller = activity.getChannelPanelController();
                                    if (controller != null) {
                                        if (controller.isPanelOpen()) controller.hidePanel();
                                        activity.setCatchUpMode(false);
                                        activity.showExoController();

                                        activity.mPlayerManager.playUrl(liveUrl, currentChannel.getName(), currentChannel);
                                    }
                                }
                                Toast.makeText(ctx, "虎牙直播源，已为您切换到该频道直播：" + item.title, Toast.LENGTH_LONG).show();
                                return;
                            }

                            Calendar playDay = Calendar.getInstance();
                            playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                            String[] startHm = item.time.split(":");
                            Calendar startCal = (Calendar) playDay.clone();
                            startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0].trim()));
                            startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1].trim()));
                            startCal.set(Calendar.SECOND, 0);
                            String endTime = epgEndTimeMap.get(item);
                            String[] endHm = endTime.split(":");
                            Calendar endCal = (Calendar) playDay.clone();
                            endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                            endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                            endCal.set(Calendar.SECOND, 0);
                            String startStr = sdfFull.format(startCal.getTime());
                            String endStr = sdfFull.format(endCal.getTime());
                            String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                            catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr + "-" + endStr;

                            if (ctx instanceof MainActivity) {
                                MainActivity activity = (MainActivity) ctx;
                                ChannelPanelController controller = activity.getChannelPanelController();
                                if (controller != null && controller.isPanelOpen()) {
                                    controller.hidePanel();
                                }
                                activity.setCatchUpMode(true);
                                activity.showExoController();
                                activity.mPlayerManager.playUrl(catchUrl);
                            }
                            Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        if (bookedSet.contains(key)) {
                            bookedSet.remove(key);
                            Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                        } else {
                            bookedSet.add(key);
                            Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                        }
                        updateActionButtonState(v, actionTag);
                    }
                }
            };

            /**
             * 【2026-10-08】咪咕轮播节目回看：异步 {@code GET /p/get_carousel_video?pid=<节目id>}
             * 取真实地址后起播 —— 等价原版 {@code lunbolbk://<pid>} 路径
             * （见 {@code CarouselApi.getCarouselVideo}）。
             *
             * <p>为什么不能沿用下面的 playseek 拼法：{@code playseek} 是 IPTV 时移流的约定，
             * 轮播源是咪咕点播 VOD 时间表，两者不是一回事。
             *
             * <p><b>红线</b>：单次请求、不重试、不并发。
             */
            private void playMiguLookBack(final Channel.EpgItem item) {
                final Context c = ctx;
                Toast.makeText(c, "正在获取回看：" + item.title, Toast.LENGTH_SHORT).show();
                com.tv.live.util.AppExecutors.io(() -> {
                    com.tv.live.migu.source.CarouselApi.Resolution r = null;
                    try {
                        r = com.tv.live.migu.source.CarouselApi.getCarouselVideo(
                                item.miguProgramId, "回看·" + item.title);
                    } catch (Throwable ignored) {
                    }
                    // 【2026-10-08】DNS 探活换线：候选里死域名直接跳过（纯本机查询，零 HTTP 请求）
                    if (r != null && r.candidates.size() > 1) {
                        String pick = com.tv.live.migu.source.CarouselApi
                                .pickResolvableCandidate(r.candidates);
                        if (!TextUtils.isEmpty(pick) && !pick.equals(r.url)) {
                            LogBridge.i("EpgManager", "【EPG·咪咕】回看候选 DNS 探活换线 → " + pick);
                            r = r.withUrl(pick);
                        }
                    }
                    final com.tv.live.migu.source.CarouselApi.Resolution rr = r;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (rr == null || TextUtils.isEmpty(rr.url)) {
                            Toast.makeText(c, "该节目暂无回看源", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (com.tv.live.migu.source.CarouselApi.isKnownDeadHost(rr.url)) {
                            Toast.makeText(c, "该节目当前版权受限，暂无法回看", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (c instanceof MainActivity) {
                            MainActivity activity = (MainActivity) c;
                            ChannelPanelController controller = activity.getChannelPanelController();
                            if (controller != null && controller.isPanelOpen()) {
                                controller.hidePanel();
                            }
                            activity.setCatchUpMode(true);
                            activity.showExoController();
                            activity.mPlayerManager.playUrl(rr.url);
                        }
                        Toast.makeText(c, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    });
                });
            }

            /**
             * 【2026-10-10/12】自定义播放项切集（内置源7 afhgrj 影视 + 内置源5 aiqiyi VOD）：
             * 选集项携带 {@code customPlayUrl}（源7：{@code afhgrj://<vodId>-<sid>-<nid>}，
             * 起播那一刻才由 {@code TVPlayerManager} 抓播放页取真实 m3u8；源5：video_detail
             * 明文内的直链 m3u8），直接交给播放器。
             *
             * <p>【2026-10-10 回归修复】与源1 的「过去节目回看」一致，进入 {@code catchUpMode}
             * （回看模式）：这样长按不再弹设置、双击唤起控制栏（见 GestureManager）对源7/源5
             * 同样生效，且无需任何专属特判即可隔离于其它源。
             */
            private void playCustomEpisode(final Channel.EpgItem item) {
                final Context c = ctx;
                if (!(c instanceof MainActivity)) return;
                MainActivity activity = (MainActivity) c;
                ChannelPanelController controller = activity.getChannelPanelController();
                if (controller != null && controller.isPanelOpen()) {
                    controller.hidePanel();
                }
                activity.setCatchUpMode(true);
                activity.showExoController();
                activity.mPlayerManager.playUrl(item.customPlayUrl,
                        currentChannel.getName(), currentChannel);
                LogBridge.i("EpgManager", "【EPG·自定义】切集播放：" + item.title
                        + " → " + item.customPlayUrl);
                Toast.makeText(c, "播放：" + item.title, Toast.LENGTH_SHORT).show();
            }

            public EpgAdapter(Context ctx, Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
                super(ctx, R.layout.item_epg, list);
                this.ctx = ctx;
                this.currentChannel = currentChannel;
                this.list = list;
                this.inflater = LayoutInflater.from(ctx);
                this.dayIndex = dayIndex;
            }

            public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
                this.currentChannel = currentChannel;
                this.list.clear();
                this.list.addAll(list);
                this.dayIndex = dayIndex;
                this.currentNowStr = getNow();
                notifyDataSetChanged();
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.item_epg, parent, false);
                    holder = new ViewHolder();
                    holder.tv_dayName = convertView.findViewById(R.id.tv_dayName);
                    holder.tv_time = convertView.findViewById(R.id.tv_time);
                    holder.tv_title = convertView.findViewById(R.id.tv_title);
                    holder.tv_action = convertView.findViewById(R.id.tv_action);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position < 0 || position >= list.size()) {
                    return convertView;
                }

                Channel.EpgItem item = list.get(position);
                String endTime = epgEndTimeMap.get(item);
                holder.tv_dayName.setText(item.dayName);
                holder.tv_time.setText(String.format(Locale.ROOT, "%s-%s", item.time, endTime));
                holder.tv_title.setText(item.title);

                boolean isSelected = (position == selectedPosition);

                if (isSelected) {
                    holder.tv_dayName.setTextColor(COLOR_BLUE);
                    holder.tv_time.setTextColor(COLOR_BLUE);
                    holder.tv_title.setTextColor(COLOR_BLUE);
                    holder.tv_dayName.setTypeface(null, Typeface.BOLD);
                    holder.tv_time.setTypeface(null, Typeface.BOLD);
                    holder.tv_title.setTypeface(null, Typeface.BOLD);
                    convertView.setBackgroundColor(COLOR_BG_BLUE);
                } else {
                    holder.tv_dayName.setTextColor(COLOR_WHITE);
                    holder.tv_time.setTextColor(COLOR_GRAY);
                    holder.tv_title.setTextColor(COLOR_WHITE);
                    holder.tv_dayName.setTypeface(null, Typeface.NORMAL);
                    holder.tv_time.setTypeface(null, Typeface.NORMAL);
                    holder.tv_title.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                }

                String key = currentChannel.getName() + "_" + position;
                boolean isPast = false;
                if (dayIndex < 0) {
                    // 【2026-10-08】过去日期（咪咕多天 EPG）：全天节目均已播完 → 全部可回看
                    isPast = true;
                } else if (dayIndex == 0) {

                    if (currentNowStr == null) currentNowStr = getNow();
                    try {
                        if (item.time != null) {
                            isPast = item.time.compareTo(currentNowStr) < 0;
                        }
                    } catch (Exception ignored) {}
                }

                ItemActionTag tag = new ItemActionTag();
                tag.item = item;
                tag.key = key;
                tag.isPast = isPast;
                holder.tv_action.setTag(tag);
                holder.tv_action.setOnClickListener(actionClickListener);

                if (dayIndex == 0) {

                    if (item.isPlaying) {
                        holder.tv_action.setText("播放中");
                        holder.tv_action.setBackgroundColor(0xFFFF9800);
                        holder.tv_action.setEnabled(false);
                    } else if (isPast) {
                        holder.tv_action.setText("回看");
                        holder.tv_action.setBackgroundColor(0xFF607D8B);
                        holder.tv_action.setEnabled(true);
                    } else {
                        holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                        holder.tv_action.setBackgroundColor(0xFF4CAF50);
                        holder.tv_action.setEnabled(true);
                    }
                } else if (dayIndex < 0) {
                    // 【2026-10-08】过去日期：节目均已结束 → 按钮同「回看」
                    // （咪咕节目带 miguProgramId，点击走 /p/get_carousel_video?pid= 回看链路）
                    holder.tv_action.setText("回看");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(true);
                } else {

                    holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                }

                return convertView;
            }

            private void updateActionButtonState(View rootView, ItemActionTag tag) {
                TextView actionBtn = rootView.findViewById(R.id.tv_action);
                if (actionBtn == null) return;
                if (tag.isPast) {

                } else {
                    boolean isBooked = bookedSet.contains(tag.key);
                    actionBtn.setText(isBooked ? "已预约" : "预约");
                    actionBtn.setBackgroundColor(0xFF4CAF50);
                }
            }

            private class ViewHolder {
                TextView tv_dayName;
                TextView tv_time;
                TextView tv_title;
                TextView tv_action;
            }

            private class ItemActionTag {
                Channel.EpgItem item;
                String key;
                boolean isPast;
            }
        }
    }

    // ===== DateListManager (原 com.tv.live.widget.DateListManager) =====
    public static class DateListManager {
        private final ListView lvDate;
        private Context context;
        private int selectedPosition = 0;
        private int focusedPosition = -1;
        private OnDateSelectedListener listener;
        private ArrayAdapter<String> adapter;
        private List<String> dateDisplayList;

        /** 【2026-10-08】「今天」在当前列表中的位置：无过去天=0；含过去7天=7。 */
        private int todayPosition = 0;

        /** 【2026-10-08】当前列表是否含过去7天（仅内置咪咕源为 true）。 */
        private boolean showingPastDays = false;

        private static final int COLOR_BLUE = 0xFF40A9FF;
        private static final int COLOR_BG_BLUE = 0x3340A9FF;
        private static final int COLOR_WHITE = 0xFFFFFFFF;

        public interface OnDateSelectedListener {
            void onDateSelected(int position);
        }

        public void setOnDateSelectedListener(OnDateSelectedListener listener) {
            this.listener = listener;
        }

        public DateListManager(Context context, ListView lvDate) {
            this.context = context;
            this.lvDate = lvDate;

            lvDate.setItemsCanFocus(true);
            lvDate.setFocusable(true);
            lvDate.setFocusableInTouchMode(true);
            lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            lvDate.setFocusable(true);
            lvDate.setFocusableInTouchMode(true);
            lvDate.setItemsCanFocus(true);

            lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedPosition = position;
                    LogBridge.d("DateList", "onItemSelected pos:" + position);
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            lvDate.setOnItemClickListener((parent, view, position, id) -> {
                selectedPosition = position;
                LogBridge.d("DateList", "onItemClick pos:" + position);
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        }

        public void initDate() {
            buildDateList(false);
        }

        /**
         * ★【2026-10-08】按当前内置源形态重建日期列表。
         *
         * <ul>
         *   <li>{@code withPastDays=false}：今天 + 未来6天（原有 7 天，<b>所有内置源</b>的默认形态，
         *       原逻辑完全不变）；</li>
         *   <li>{@code withPastDays=true}：<b>过去7天 + 今天 + 未来6天</b>（共 15 天，仅内置咪咕源切换时
         *       由 {@code ChannelPanelController.syncDateListForCurrentChannel()} 调用）。</li>
         * </ul>
         *
         * <p>幂等：形态未变时不重建（不打扰用户已选中的日期）；形态变化时把选中日
         * 重置回「今天」。重建不回调 {@link OnDateSelectedListener}（避免重建过程触发刷新竞态）。
         *
         * @param withPastDays 是否显示过去 7 天
         */
        public void rebuild(boolean withPastDays) {
            if (adapter != null && dateDisplayList != null && !dateDisplayList.isEmpty()
                    && showingPastDays == withPastDays) {
                return;   // 形态未变，保持现状
            }
            buildDateList(withPastDays);
        }

        /** 选中日静默重置回「今天」（不触发 {@link OnDateSelectedListener}）。 */
        public void resetToToday() {
            selectedPosition = todayPosition;
            if (adapter != null) adapter.notifyDataSetChanged();
        }

        /** 「今天」在当前日期列表中的位置（选中位换算日期偏移用）。 */
        public int getTodayPosition() {
            return todayPosition;
        }

        /** 当前日期列表是否含过去 7 天。 */
        public boolean isShowingPastDays() {
            return showingPastDays;
        }

        /**
         * 列表 position → 日期偏移（今天=0，昨天=-1…未来+1…）。
         * 无过去天的原 7 天列表中 {@code todayPosition=0}，偏移=position —— 与原有逻辑完全兼容。
         */
        public int dayOffsetOf(int position) {
            if (position < 0) return 0;
            return position - todayPosition;
        }

        private void buildDateList(boolean withPastDays) {
            dateDisplayList = new ArrayList<>();

            Calendar cal = Calendar.getInstance();
            String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

            todayPosition = 0;

            // 【2026-10-08】过去 7 天（仅内置咪咕源）：昨天在前，今天紧随其后（位置 7）
            if (withPastDays) {
                Calendar past = Calendar.getInstance();
                past.add(Calendar.DAY_OF_YEAR, -7);
                for (int i = 0; i < 7; i++) {
                    int dayOfWeek = past.get(Calendar.DAY_OF_WEEK);
                    String weekStr = week[dayOfWeek - 1];
                    int month = past.get(Calendar.MONTH) + 1;
                    int day = past.get(Calendar.DAY_OF_MONTH);
                    String dateStr = String.format(Locale.ROOT, "%d/%d", month, day);
                    dateDisplayList.add(weekStr + "\n" + dateStr);
                    past.add(Calendar.DAY_OF_YEAR, 1);
                }
                todayPosition = 7;
            }

            selectedPosition = todayPosition;
            showingPastDays = withPastDays;

            // 今天 + 未来 6 天（原逻辑不动）
            for (int i = 0; i < 7; i++) {
                String weekStr;
                if (i == 0) {
                    weekStr = "今天";
                } else {
                    int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                    weekStr = week[dayOfWeek - 1];
                }

                int month = cal.get(Calendar.MONTH) + 1;
                int day = cal.get(Calendar.DAY_OF_MONTH);
                String dateStr = String.format(Locale.ROOT, "%d/%d", month, day);

                dateDisplayList.add(weekStr + "\n" + dateStr);
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }

            adapter = new ArrayAdapter<String>(context, R.layout.item_date, dateDisplayList) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView tv = (TextView) super.getView(position, convertView, parent);

                    tv.setSingleLine(false);
                    tv.setMaxLines(2);
                    tv.setTextSize(14);
                    tv.setGravity(android.view.Gravity.CENTER);

                    boolean isSelected = (position == selectedPosition);
                    LogBridge.d("DateList", "getView pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                    if (isSelected) {
                        tv.setTextColor(COLOR_BLUE);
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(COLOR_BG_BLUE);
                    } else {
                        tv.setTextColor(COLOR_WHITE);
                        tv.setTypeface(null, Typeface.NORMAL);
                        tv.setBackgroundColor(Color.TRANSPARENT);
                    }
                    return tv;
                }
            };

            lvDate.setAdapter(adapter);

            lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedPosition = position;
                    LogBridge.d("DateList", "onItemSelected pos:" + position);
                    if (adapter != null) adapter.notifyDataSetChanged();
                    if (listener != null) {
                        listener.onDateSelected(position);
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            lvDate.setOnItemClickListener((parent, view, position, id) -> {
                selectedPosition = position;
                adapter.notifyDataSetChanged();
                if (listener != null) {
                    listener.onDateSelected(position);
                }
            });
        }

        public void setSelectedPosition(int position) {
            if (dateDisplayList == null || adapter == null) return;
            if (position < 0 || position >= dateDisplayList.size()) return;
            if (this.selectedPosition == position) return;

            selectedPosition = position;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

        public void release() {
            if (adapter != null) {
                adapter.clear();
                adapter = null;
            }
            if (lvDate != null) {
                lvDate.setAdapter(null);
                lvDate.setOnItemClickListener(null);
                lvDate.setOnItemSelectedListener(null);
            }
            if (dateDisplayList != null) {
                dateDisplayList.clear();
                dateDisplayList = null;
            }
            listener = null;
            context = null;
        }
    }
}
