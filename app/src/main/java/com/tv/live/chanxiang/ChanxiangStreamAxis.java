package com.tv.live.chanxiang;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 畅享（内置源3）「线路 × 清晰度」轴模型（2026-10-07 新增）。
 *
 * <p><b>设计完全参照咪咕的 {@link com.tv.live.migu.MiguStreamAxis}</b>：源3 与咪咕源
 * 本就出自<b>同一个服务端</b>（{@code /channel/get_channel_source} 的 sourceLabels），
 * 咪咕那边把整张源表拆成了两个正交轴，源3 此前却没有 —— 线路只剩「主源/源N」、
 * 清晰度恒「自适应」。本类把服务端下发的 http(s) 源（源3 实际可播的子集）同样拆成：
 * <ul>
 *   <li>{@link #lines} —— 线路名（CDN 节点归组，保序去重）；</li>
 *   <li>{@link #qualities} —— 清晰度名（按码率档降序去重，高清在前）；</li>
 *   <li>{@link #entries} —— 每条源，带它所属的线路名 + 清晰度名。</li>
 * </ul>
 *
 * <p><b>归组依据（2026-10-07 对 tvId=50661/CCTV-1 实抓确认）</b>：http 源形如
 * <pre>
 *   http://livea-u-zj500.ichuanghi.com:8088/YT0013/index.m3u8?_et=…   quality=720 tag=高清线路
 *   http://livea-u-js1000.ichuanghi.com/TV051@1080/index.m3u8?_et=…   quality=1080 tag=超清线路
 * </pre>
 * 线路 = CDN 节点（主机名里的 zj500 / js1000 段），清晰度 = 服务端 quality 数值
 * —— 两个维度天然正交，<b>不</b>像旧实现那样把「高清线路/超清线路」这类 tag
 * 既当线路又当清晰度混为一谈。
 *
 * <p>本类是纯内存计算，<b>不产生任何网络请求</b>（网络获取见
 * {@code ChanxiangSourceLoader#fetchHttpSources}）。
 */
public final class ChanxiangStreamAxis {

    private static final String TAG = "ChanxiangStreamAxis";

    /** 一条可播源（= 线路 × 清晰度 的一个格点） */
    public static final class Entry {
        /** 可播 http(s) 直链 */
        public final String source;
        /** 线路名（CDN 节点，如「节点zj500」「节点js1000」） */
        public final String lineName;
        /** 清晰度展示名（如「720p 高清」） */
        public final String qualityLabel;
        /** 清晰度数值（480/720/1080；0=未知） */
        public final int qualityNum;
        /** 服务端原始 tag（仅排查用） */
        public final String rawTag;

        Entry(String source, String lineName, String qualityLabel, int qualityNum, String rawTag) {
            this.source = source;
            this.lineName = lineName;
            this.qualityLabel = qualityLabel;
            this.qualityNum = qualityNum;
            this.rawTag = rawTag;
        }

        @Override
        public String toString() {
            return lineName + " / " + qualityLabel + " → " + source;
        }
    }

    /** 轴构建输入：一条 http(s) 源 + 它的清晰度信息 */
    public static final class HttpSource {
        public final String url;
        public final int qualityNum;
        public final String tag;
        public final String qualityName;

        public HttpSource(String url, int qualityNum, String tag, String qualityName) {
            this.url = url;
            this.qualityNum = qualityNum;
            this.tag = tag;
            this.qualityName = qualityName;
        }
    }

    /** 线路名列表（保序去重） */
    public final List<String> lines;
    /** 清晰度名列表（按码率降序去重） */
    public final List<String> qualities;
    /** 全部格点（保序） */
    public final List<Entry> entries;

    private ChanxiangStreamAxis(List<String> lines, List<String> qualities, List<Entry> entries) {
        this.lines = lines;
        this.qualities = qualities;
        this.entries = entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public static ChanxiangStreamAxis empty() {
        return new ChanxiangStreamAxis(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    /**
     * 由服务端 sourceLabels 的 http(s) 子集构建两轴。
     *
     * @param sources 见 {@link HttpSource}（调用方用
     *                {@code ChanxiangSourceLoader#fetchHttpSources} 获取）
     */
    public static ChanxiangStreamAxis build(List<HttpSource> sources) {
        List<Entry> entries = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Map<String, Integer> qualityNumOfLabel = new LinkedHashMap<>();
        if (sources == null || sources.isEmpty()) return empty();

        // 同一节点的多条源（少见）→ 加序号区分，避免 UI 里出现两条同名线路
        Map<String, Integer> lineSeen = new LinkedHashMap<>();

        for (HttpSource s : sources) {
            if (s == null || TextUtils.isEmpty(s.url)) continue;
            String base = lineNameOf(s.url);
            int n = lineSeen.containsKey(base) ? lineSeen.get(base) + 1 : 1;
            lineSeen.put(base, n);
            String line = n > 1 ? base + n : base;

            int qn = s.qualityNum > 0 ? s.qualityNum : qualityNumFromName(s.qualityName);
            String q = qualityLabelOf(qn);

            entries.add(new Entry(s.url, line, q, qn, s.tag));
            if (!lines.contains(line)) lines.add(line);
            if (!qualityNumOfLabel.containsKey(q)) qualityNumOfLabel.put(q, qn);
        }

        // 清晰度降序（高清在前），同档保持首次出现顺序
        List<String> qualities = new ArrayList<>(qualityNumOfLabel.keySet());
        qualities.sort((a, b) -> {
            int na = qualityNumOfLabel.containsKey(a) ? qualityNumOfLabel.get(a) : 0;
            int nb = qualityNumOfLabel.containsKey(b) ? qualityNumOfLabel.get(b) : 0;
            return Integer.compare(nb, na);
        });

        return new ChanxiangStreamAxis(lines, qualities, entries);
    }

    /**
     * 按 (线路, 清晰度) 取一条源。
     *
     * <p>匹配逐级放宽：① 精确匹配 → ② 只锁定线路（该线路任意档）→
     * ③ 只锁定清晰度（任意线路该档）→ ④ 首条。任一入参为空则跳过对应级别。
     */
    public Entry find(String line, String quality) {
        if (entries.isEmpty()) return null;
        if (!TextUtils.isEmpty(line) && !TextUtils.isEmpty(quality)) {
            for (Entry e : entries) {
                if (eq(e.lineName, line) && eq(e.qualityLabel, quality)) return e;
            }
        }
        if (!TextUtils.isEmpty(line)) {
            for (Entry e : entries) {
                if (eq(e.lineName, line)) return e;
            }
        }
        if (!TextUtils.isEmpty(quality)) {
            for (Entry e : entries) {
                if (eq(e.qualityLabel, quality)) return e;
            }
        }
        return entries.get(0);
    }

    /** 该线路的默认（首选）清晰度 */
    public String firstQualityOf(String line) {
        for (Entry e : entries) {
            if (eq(e.lineName, line)) return e.qualityLabel;
        }
        return qualities.isEmpty() ? "" : qualities.get(0);
    }

    /** 线路下标（0 基）；找不到返回 -1 */
    public int indexOfLine(String line) {
        return line == null ? -1 : lines.indexOf(line);
    }

    /** 按 URL 精确找格点（用于把「正在播的地址」映射回线路/清晰度显示）；找不到返回 null */
    public Entry findBySource(String url) {
        if (TextUtils.isEmpty(url)) return null;
        for (Entry e : entries) {
            if (eq(e.source, url)) return e;
        }
        return null;
    }

    /** 清晰度最高的格点（找不到时 null；构建入参非空则恒有解） */
    public Entry bestEntry() {
        Entry best = null;
        for (Entry e : entries) {
            if (best == null || e.qualityNum > best.qualityNum) best = e;
        }
        return best;
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equals(b);
    }

    // ------------------------------------------------------------------
    // 推导规则
    // ------------------------------------------------------------------

    /**
     * 由 http(s) 直链推导「线路名」（= CDN 节点归属）。
     *
     * <p>实测主机形态 {@code livea-u-zj500.ichuanghi.com} / {@code livea-u-js1000.ichuanghi.com}
     * —— 取主机名里<b>含数字的最小子段</b>（zj500 / js1000）作节点标识，展示为「节点zj500」。
     * 没有节点段的退化为主域（ichuanghi），再不行用整个主机名。不用服务端 tag
     * —— tag 是「高清线路/超清线路」，嵌着清晰度字样，当线路名会与清晰度轴串味。
     */
    public static String lineNameOf(String url) {
        if (TextUtils.isEmpty(url)) return "直链";
        String host;
        try {
            String s = url.trim();
            int schemeEnd = s.indexOf("://");
            String rest = schemeEnd > 0 ? s.substring(schemeEnd + 3) : s;
            int pathStart = rest.indexOf('/');
            host = pathStart > 0 ? rest.substring(0, pathStart) : rest;
            int port = host.indexOf(':');
            if (port > 0) host = host.substring(0, port);
        } catch (Throwable t) {
            return "直链";
        }
        if (TextUtils.isEmpty(host)) return "直链";
        String[] labels = host.toLowerCase().split("\\.");
        for (String label : labels) {
            String node = nodeIdOf(label);
            if (node != null) return "节点" + node;
        }
        // 主机名里没有节点段 → 退化用主域（倒数第二段）
        if (labels.length >= 2) return labels[labels.length - 2];
        return host;
    }

    /**
     * 从主机名的一个 label 里提取节点子段。
     * 规则：按 {@code -} 拆分，取<b>最后一个含数字且 ≤8 字符</b>的子段
     * （{@code livea-u-zj500} → {@code zj500}；{@code u} 不含数字被跳过）。
     */
    private static String nodeIdOf(String hostLabel) {
        if (hostLabel == null || hostLabel.isEmpty()) return null;
        String[] parts = hostLabel.split("-");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.length() <= 8 && p.matches(".*\\d.*") && p.matches("[a-z0-9]+")) return p;
        }
        return null;
    }

    /** qualityName 里解析清晰度数值（「1080P」→1080；解析不出返回 0） */
    public static int qualityNumFromName(String qualityName) {
        if (TextUtils.isEmpty(qualityName)) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{3,4})").matcher(qualityName);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /** 清晰度数值 → 展示名（与咪咕轴完全同口径，两个源的清晰度弹窗文案一致） */
    public static String qualityLabelOf(int qualityNum) {
        if (qualityNum >= 1080) return "1080p 超清";
        if (qualityNum >= 720) return "720p 高清";
        if (qualityNum >= 480) return "480p 标清";
        if (qualityNum > 0) return qualityNum + "p 流畅";
        return "自适应";
    }
}
