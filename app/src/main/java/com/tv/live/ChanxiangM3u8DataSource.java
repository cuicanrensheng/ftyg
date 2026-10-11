package com.tv.live;

import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.tv.live.util.LogBridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 兼容畅享（Chanxiang）下发的非标准 HLS m3u8：
 * 其片段时长写成科学计数法（如 #EXTINF:3E+00,），ExoPlayer 的 HLS 解析器不认，
 * 这里在拉取到 m3u8 文本后把时长归一化为标准十进制（#EXTINF:3.0,）再交给解析器。
 * .ts 分片不受影响，直接透传。
 *
 * <p>【2026-10-10 新增】内置源7（afhgrj 影视）中插广告剥离：源7 的 CDN 会在正片中间
 * 用两个 {@code #EXT-X-DISCONTINUITY} 夹入一段（约 26s）博彩广告，其分片路径形如
 * {@code /video/adjump/time/<id>.ts}。当 {@code stripAds=true}（仅源7 起播时置位）时，
 * 这里把广告块整体删掉再交给解析器；其它内置源 {@code stripAds=false}，行为完全不变。
 */
// 【2026-10-10 Lint】本类刻意使用 media3 的 @UnstableApi 内部 API（自定义 DataSource）。
// 用 androidx.annotation.OptIn 就地 opt-in，而不是给类打 @UnstableApi —— 后者会把
// opt-in 要求传染给所有调用方，凭空制造新的 UnsafeOptInUsageError。
@OptIn(markerClass = UnstableApi.class)
public final class ChanxiangM3u8DataSource implements DataSource {

    private static final String TAG = "ChanxiangM3u8DS";
    private static final Pattern EXTINF = Pattern.compile("#EXTINF:([^,\r\n]+),");

    /** HLS 断点标记：源7 的广告块正是被两个该标记夹住 */
    private static final String DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    /** 源7 广告分片的路径特征（/video/adjump/time/xxx.ts） */
    private static final String AD_PATH_MARK = "/adjump/";

    private final DataSource upstream;
    /** 【2026-10-10】是否剥离源7 中插广告分片（仅内置源7 起播时为 true，其它源恒为 false） */
    private final boolean stripAds;
    @Nullable private ByteArrayInputStream rewritten;
    @Nullable private DataSpec dataSpec;

    public ChanxiangM3u8DataSource(DataSource upstream) {
        this(upstream, false);
    }

    public ChanxiangM3u8DataSource(DataSource upstream, boolean stripAds) {
        this.upstream = upstream;
        this.stripAds = stripAds;
    }

    @Override
    public void addTransferListener(@Nullable TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        String uri = dataSpec.uri.toString();
        // 仅对 m3u8（排除 .ts）做归一化；其他资源透传给上游
        if (uri.contains(".m3u8") && !uri.contains(".ts")) {
            long opened = upstream.open(dataSpec);
            Log.d(TAG, "拦截 m3u8 进行归一化: " + uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = upstream.read(buf, 0, buf.length)) != -1) {
                    bos.write(buf, 0, n);
                }
            } finally {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                }
            }
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            String normalized = normalize(text);
            if (stripAds) {
                String stripped = stripAdSegments(normalized);
                if (!stripped.equals(normalized)) {
                    LogBridge.i(TAG, "源7 中插广告已剥离: " + uri);
                }
                normalized = stripped;
            }
            byte[] out = normalized.getBytes(StandardCharsets.UTF_8);
            rewritten = new ByteArrayInputStream(out);
            return C.LENGTH_UNSET;
        }
        return upstream.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (rewritten != null) {
            return rewritten.read(buffer, offset, length);
        }
        return upstream.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        if (rewritten != null) {
            rewritten.close();
            rewritten = null;
        }
        upstream.close();
    }

    @Nullable
    @Override
    public Uri getUri() {
        if (rewritten != null && dataSpec != null) return dataSpec.uri;
        try {
            return upstream.getUri();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        if (rewritten != null) return Collections.emptyMap();
        return upstream.getResponseHeaders();
    }

    private static String normalize(String text) {
        Matcher m = EXTINF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String raw = m.group(1).trim();
            String replacement;
            if (raw.matches(".*[eE].*")) {
                try {
                    double v = Double.parseDouble(raw);
                    replacement = "#EXTINF:" + v + ",";
                } catch (NumberFormatException e) {
                    replacement = m.group(0);
                }
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 【2026-10-10】剥离源7（afhgrj 影视）的中插广告分片。
     *
     * <p>源7 的各 CDN 用两套广告特征：
     * <ol>
     *   <li><b>明码</b>：分片路径形如 {@code /video/adjump/time/*.ts}（未混淆，直接按
     *       {@code /adjump/} 命中剔除）；</li>
     *   <li><b>混淆</b>：EFV 类转码系统开启「混淆插播」后，动态改写 m3u8，使广告分片与正片
     *       特征<b>完全一致</b>（全部伪装成 {@code index<16位随机>.jpeg}、同名同长目录、时长
     *       与正片相同），此时 {@code /adjump/} 已失效。但广告段会<b>更换内容目录</b>并从独立
     *       目录/CDN 读取，且通常被 {@code #EXT-X-DISCONTINUITY} 两处夹住。实测《交锋》正片
     *       全在 {@code /videos/.../7cc884/}（约600段），广告夹在 {@code /stream/.../02955c/}
     *       （仅5段）。</li>
     * </ol>
     *
     * <p>处理策略（仅作用于含 {@code #EXTINF} 的<b>媒体播放列表</b>，master 主清单直接放行）：
     * <ol>
     *   <li>统计每个「内容目录」的分片数量，出现最多的目录视为正片；</li>
     *   <li>命中 {@code /adjump/} 的、或<b>非正片目录</b>且正片目录分片数 ≥ 其 2 倍的独立小目录
     *       段，判为广告并连同其 {@code #EXTINF} 一起剔除；</li>
     *   <li>清理被整体抽空后残留的孤立 {@code #EXT-X-DISCONTINUITY}。</li>
     * </ol>
     * 无任何广告特征时原样返回，毫发无损；仅内置源7（{@code stripAds=true}）启用。
     */
    private static String stripAdSegments(String text) {
        if (text == null || text.indexOf("#EXTINF") < 0) return text;
        String[] lines = text.split("\n", -1);
        int n = lines.length;

        // ---- 第1步：统计内容目录的分片数，找出占比占优的正片目录 ----
        Map<String, Integer> dirCount = new HashMap<>();
        for (String ln : lines) {
            String dir = contentDir(segmentUri(ln));
            if (dir != null) dirCount.put(dir, dirCount.getOrDefault(dir, 0) + 1);
        }
        if (dirCount.isEmpty()) return text;

        String mainDir = null;
        int mainCount = 0;
        for (Map.Entry<String, Integer> e : dirCount.entrySet()) {
            if (e.getValue() > mainCount) { mainCount = e.getValue(); mainDir = e.getKey(); }
        }
        boolean haveAdPath = text.indexOf(AD_PATH_MARK) >= 0;
        boolean multipleDirs = dirCount.size() > 1;

        // ---- 第2步：判定并标记删除行 ----
        boolean[] keep = new boolean[n];
        java.util.Arrays.fill(keep, true);
        int removed = 0;
        for (int i = 0; i < n; i++) {
            String seg = segmentUri(lines[i].trim());
            if (seg == null) continue;
            boolean ad = false;
            if (haveAdPath && seg.contains(AD_PATH_MARK)) ad = true;
            if (!ad && multipleDirs) {
                String dir = contentDir(seg);
                if (dir != null && !dir.equals(mainDir) && mainCount >= 2 * dirCount.getOrDefault(dir, 0)) {
                    ad = true;
                }
            }
            if (ad) {
                keep[i] = false;
                int p = i - 1;
                while (p >= 0 && lines[p].trim().isEmpty()) p--;
                if (p >= 0 && lines[p].trim().startsWith("#EXTINF")) keep[p] = false;
                removed++;
            }
        }
        if (removed == 0) return text;

        // ---- 第3步：清理标记「正片↔广告」边界的 DISCONTINUITY（其一侧已落删除区） ----
        // 广告在正片中间被两个 DISCONTINUITY 夹住，删掉广告分片后该断点已无意义，一并移除，
        // 使正片前后无缝衔接，避免相邻断点引发的空区间/时间轴跳变。
        for (int i = 0; i < n; i++) {
            if (!DISCONTINUITY.equals(lines[i].trim())) continue;
            int before = i - 1;
            while (before >= 0 && lines[before].trim().isEmpty()) before--;
            int after = i + 1;
            while (after < n && lines[after].trim().isEmpty()) after++;
            boolean bDel = before >= 0 && !keep[before];
            boolean aDel = after < n && !keep[after];
            if (bDel || aDel) keep[i] = false;
        }

        // ---- 输出保留行 ----
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < n; i++) {
            if (keep[i]) sb.append(lines[i]).append('\n');
        }
        LogBridge.i(TAG, "源7：剥离中插广告分片 " + removed + " 条");
        return sb.toString();
    }

    /** 若行是分片 URI（非空、不以 # 开头）则返回它；否则返回 {@code null} */
    private static String segmentUri(String t) {
        if (t == null || t.isEmpty() || t.startsWith("#")) return null;
        return t;
    }

    /** 从分片 URI 取「内容目录」：去掉分片文件名后的路径部分（相对/绝对均可） */
    private static String contentDir(String seg) {
        if (seg == null) return null;
        int slash = seg.lastIndexOf('/');
        return slash < 0 ? null : seg.substring(0, slash);
    }

    /** 工厂：包装任意上游 DataSource.Factory，仅对 m3u8 做归一化。 */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;
        private final boolean stripAds;

        public Factory(DataSource.Factory upstreamFactory) {
            this(upstreamFactory, false);
        }

        /** 【2026-10-10】stripAds=true 时额外剥离源7 中插广告（仅内置源7 起播传入） */
        public Factory(DataSource.Factory upstreamFactory, boolean stripAds) {
            this.upstreamFactory = upstreamFactory;
            this.stripAds = stripAds;
        }

        @Override
        public DataSource createDataSource() {
            return new ChanxiangM3u8DataSource(upstreamFactory.createDataSource(), stripAds);
        }
    }
}
