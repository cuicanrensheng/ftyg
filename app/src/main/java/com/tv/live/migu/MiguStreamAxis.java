package com.tv.live.migu;

import android.text.TextUtils;

import com.tv.live.migu.source.NowTvSourceApi;

import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 咪咕「线路 × 清晰度」轴模型（2026-10-07 新增）。
 *
 * <p><b>背景</b>：设置里的「频道线路选择」与「清晰度选择」此前只为虎牙频道服务 ——
 * 咪咕频道解析后只剩一条最终直链，两个列表恒为空（线路只剩「主源」、清晰度直接弹
 * 「当前直播源不支持清晰度切换」）。
 *
 * <p>而服务端对咪咕频道下发的 {@code sourceLabels[]} 天然就是一张扁平矩阵。
 * 以 {@code CCTV-1 (tvId=50661)} 为例（2026-10-06 抓包）：
 * <pre>
 *   ytp2p://rc21Vih...                      → 线路 P2P
 *   ysp://fhd//600001859//2024078201        → 线路 央视频 · 清晰度 1080p
 *   ysp://shd//600001859//2024078201        → 线路 央视频 · 清晰度 720p
 *   cctv://ldncctvwbcdbd.a.bdydns.com/...   → 线路 央视
 *   miguhd://608807420                      → 线路 咪咕 · 清晰度 720p
 *   migu://608807420                        → 线路 咪咕 · 清晰度 480p（同一 pID！）
 *   …
 * </pre>
 * 本类即把这串扁平源列表<b>拆成两个正交的轴</b>：
 * <ul>
 *   <li>{@link #lines} —— 线路名（按协议来源归组，保序去重）；</li>
 *   <li>{@link #qualities} —— 清晰度名（按码率档归组，<b>降序</b>去重，高清在前）；</li>
 *   <li>{@link #entries} —— 每条源，带它所属的线路名 + 清晰度名。</li>
 * </ul>
 *
 * <p><b>与虎牙的分工</b>：虎牙的矩阵走 {@code VariantManager.variantList}（那套依赖 master
 * m3u8 的 STREAM-INF）；咪咕的源是「待解析协议串」（{@code miguhd://…}），混进 variantList
 * 会被 {@code doPlayNormal} 的 master 解析清空，故单独用本模型承载 —— 互不干扰。
 *
 * <p>本类是纯内存计算，<b>不产生任何网络请求</b>。
 */
public final class MiguStreamAxis {

    private static final String TAG = "MiguStreamAxis";

    /** 一条可播源（= 线路 × 清晰度 的一个格点） */
    public static final class Entry {
        /** 待解析源串（{@code miguhd://…} / {@code ysp://…} / {@code http…}），交给播放器解析链 */
        public final String source;
        /** 线路名（协议来源，如「咪咕」「央视频」「CDN直链」） */
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

    /** 线路名列表（保序去重） */
    public final List<String> lines;
    /** 清晰度名列表（按码率降序去重） */
    public final List<String> qualities;
    /** 全部格点（保序） */
    public final List<Entry> entries;

    private MiguStreamAxis(List<String> lines, List<String> qualities, List<Entry> entries) {
        this.lines = lines;
        this.qualities = qualities;
        this.entries = entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public static MiguStreamAxis empty() {
        return new MiguStreamAxis(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    /**
     * 由服务端下发的源列表构建两轴。
     *
     * @param labels 可播源列表（调用方应先用
     *               {@link NowTvSourceApi#filterForAxis} 保留网页项）
     */
    public static MiguStreamAxis build(List<NowTvSourceApi.SourceLabel> labels) {
        List<Entry> entries = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Map<String, Integer> qualityNumOfLabel = new LinkedHashMap<>();
        if (labels == null || labels.isEmpty()) return empty();

        // 同一来源的多个节点（如两条不同 CDN 直链）→ 加序号区分，避免 UI 里出现两条同名线路
        Map<String, Integer> lineSeen = new LinkedHashMap<>();

        for (NowTvSourceApi.SourceLabel l : labels) {
            if (l == null || TextUtils.isEmpty(l.source)) continue;
            String base = lineNameOf(l.source);
            int n = lineSeen.containsKey(base) ? lineSeen.get(base) + 1 : 1;
            lineSeen.put(base, n);
            String line = n > 1 ? base + n : base;

            int qn = qualityNumOf(l.source, l.quality);
            String q = qualityLabelOf(qn);

            entries.add(new Entry(l.source, line, q, qn, l.tag));
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

        return new MiguStreamAxis(lines, qualities, entries);
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

    private static boolean eq(String a, String b) {
        return a != null && a.equals(b);
    }

    // ------------------------------------------------------------------
    // 推导规则
    // ------------------------------------------------------------------

    /**
     * 由源串推导「线路名」（= 内容来源 / CDN 归属）。
     *
     * <p>取协议前缀而非服务端 {@code tag} —— 实测 {@code tag} 语义混乱
     * （湖南卫视的 ysp 源 tag 是「高清」，那描述的是清晰度不是线路），
     * 而协议前缀稳定且可解释。
     */
    public static String lineNameOf(String source) {
        if (TextUtils.isEmpty(source)) return "未知";
        String s = source.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("migu")) return "咪咕";
        if (lower.startsWith("ysp")) return "央视频";
        if (lower.startsWith("cctvh5")) return "央视H5";
        if (lower.startsWith("cctv")) return "央视";
        if (lower.startsWith("fymb")) return "风云";
        if (lower.startsWith("ytp2p")) return "P2P";
        if (lower.startsWith("tvnow")) return "直播轮播";
        // 【2026-10-07】回看与直播轮播<b>分开命名</b>（此前都叫「轮播」，去重后成「轮播/轮播2」，
        //   掩盖了两者语义差别）。`lunbolbk://<pid>` 与 `lunboParser_<tvId>_<offset>_…` 都是回看。
        if (lower.startsWith("lunbolbk")) return "回看";
        if (lower.startsWith("lunboparser")) return "回看·云图";
        // 服务端 backSourceLabels 的 yuntulunbo 关键字 → 合成源（见 CarouselApi.SCHEME_YUNTU_LB）
        if (lower.startsWith("yuntulunbo")) return "回看·云图";
        if (lower.startsWith("http")) {
            // 【2026-10-07】http 分两类：像流的 = CDN 直链；不像流的 = 网页
            //   （典型 http://www.le.com → 原版交 letv_vod.js 解析，单列为一条可选线路）
            return com.tv.live.migu.MiguUrlResolver.isStreamLike(s) ? "CDN直链" : "网页";
        }
        int i = s.indexOf("://");
        if (i > 0) return s.substring(0, i);
        return "直链";
    }

    /**
     * 推导清晰度数值（480/720/1080…；0=未知）。
     *
     * <p>优先用服务端 {@code quality} 字段；缺失时回退到协议内嵌档位
     * （{@code ysp://fhd} / {@code ysp://shd} / {@code miguhd://} / {@code migu://}）。
     */
    public static int qualityNumOf(String source, String qualityField) {
        int q = 0;
        if (!TextUtils.isEmpty(qualityField)) {
            try {
                q = Integer.parseInt(qualityField.trim());
            } catch (Throwable ignored) {
            }
        }
        if (q > 0) return q;
        String lower = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("miguhd")) return 720;
        if (lower.startsWith("migu")) return 480;
        if (lower.contains("://fhd") || lower.contains("//fhd")) return 1080;
        if (lower.contains("://shd") || lower.contains("//shd")) return 720;
        if (lower.contains("://hd") || lower.contains("//hd")) return 720;
        return 0;
    }

    /** 清晰度数值 → 展示名（数字在前，便于排序与既有 height 解析兼容） */
    public static String qualityLabelOf(int qualityNum) {
        if (qualityNum >= 1080) return "1080p 超清";
        if (qualityNum >= 720) return "720p 高清";
        if (qualityNum >= 480) return "480p 标清";
        if (qualityNum > 0) return qualityNum + "p 流畅";
        return "自适应";
    }
}
