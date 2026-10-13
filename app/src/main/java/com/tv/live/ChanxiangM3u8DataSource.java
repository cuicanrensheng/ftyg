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
import java.util.ArrayList;
import java.util.Collections;
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
 * <p>【2026-10-13 重写·广告剥离】参照「哦了电视」的 m3u8 广告过滤机制（DISCONTINUITY
 * 切块 + 夹层长度/同目录/编辑距离同源判定 + 多数决反转 + 熔断），对所有内置源统一启用
 * （{@code stripAds=true}）。
 *
 * <p>【2026-10-13 直播硬跳过·三层防护】直播源绝不参与剥离，三层任一层命中即原样放行：
 * <ol>
 *   <li><b>URL 特征层</b>：m3u8 地址含 {@code /live/}、{@code live.php}、{@code /lives/}、
 *       {@code /video-hls/}、{@code liveshow} 等直播特征串 → 直接跳过（{@link #isLikelyLiveUrl}）；</li>
 *   <li><b>频道类型层</b>：源5 直播频道（channelId 前缀 {@code renyibo:}，非 {@code renyibo-vod:}）
 *       在起播处即把 {@code stripAds=false} 传入（见 TVPlayerManager#doPlayNormal）；</li>
 *   <li><b>内容门控层</b>：剥离器内部仅处理含 {@code #EXT-X-ENDLIST} 的<b>点播全集</b>，
 *       直播/事件型（{@code #EXT-X-PLAYLIST-TYPE:EVENT}）与 master 主清单原样放行。</li>
 * </ol>
 * 三层均为「保守放行」方向——误判只会少剥一次广告，绝不损伤直播播放。
 */
// 【2026-10-10 Lint】本类刻意使用 media3 的 @UnstableApi 内部 API（自定义 DataSource）。
// 用 androidx.annotation.OptIn 就地 opt-in，而不是给类打 @UnstableApi —— 后者会把
// opt-in 要求传染给所有调用方，凭空制造新的 UnsafeOptInUsageError。
@OptIn(markerClass = UnstableApi.class)
public final class ChanxiangM3u8DataSource implements DataSource {

    private static final String TAG = "ChanxiangM3u8DS";
    private static final Pattern EXTINF = Pattern.compile("#EXTINF:([^,\r\n]+),");

    /** HLS 断点标记：广告块被两个该标记夹住 */
    private static final String DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    /** 源7 广告分片的路径特征（/video/adjump/time/xxx.ts），高置信硬规则 */
    private static final String AD_PATH_MARK = "/adjump/";

    /**
     * 【2026-10-13 直播硬跳过】m3u8 地址中的直播特征标记（小写比对）。
     * 命中任一即视为直播源，广告剥离整体跳过。方向「保守放行」：
     * 误命中只会少剥一次广告，绝不损伤直播播放。
     *
     * <p>覆盖各内置源直播形态：源4/源6 的 {@code /live/}、{@code /longvideo/transcode/video/live/}；
     * 源5 直播 {@code live.php}；移动系 {@code /bs3/video-hls/}；百度系 {@code liveshow} 等。
     */
    private static final String[] LIVE_URL_MARKERS = {
            "/live/", "/lives/", "/live.", "live.php", "/hls/live",
            "liveshow", "/video-hls/", "type=live", "liveplay", "livestream",
    };

    private final DataSource upstream;
    /** 【2026-10-13】是否启用中插广告剥离（点播源 true；直播源由 URL 特征/频道类型/ENDLIST 三层硬跳过） */
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
            // 【2026-10-13 直播硬跳过】URL 带直播特征 → 只做 EXTINF 归一化，不做广告剥离
            if (stripAds && !isLikelyLiveUrl(uri)) {
                String stripped = stripAdSegments(normalized);
                if (!stripped.equals(normalized)) {
                    LogBridge.i(TAG, "内置源中插广告已剥离: " + uri);
                }
                normalized = stripped;
            } else if (stripAds) {
                LogBridge.i(TAG, "直播源跳过广告剥离: " + uri);
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
     * 【2026-10-13 重写】广告剥离引擎：参照「哦了电视」的通用 m3u8 广告过滤机制
     * （DISCONTINUITY 切块判定 + 多数决反转 + 熔断），对所有内置源统一启用。
     *
     * <p>门控：仅处理含 {@code #EXT-X-ENDLIST} 的<b>点播全集</b>媒体播放列表；直播 m3u8
     * 与 master 主清单原样放行（同哦了电视入口行为，直播不干预避免误伤）。
     *
     * <p>算法：
     * <ol>
     *   <li><b>adjump 硬规则</b>：{@code /adjump/} 路径分片连 {@code #EXTINF} 直接剔除
     *       （源7 明码广告，零误伤）；</li>
     *   <li><b>切块同源判定</b>：按 {@code #EXT-X-DISCONTINUITY} 切块，首块恒为正片。
     *       后续块三维判定——夹层长度（本块首分片与前块、后块首分片长度呈夹层即判广告）、
     *       同目录（目录前缀一致视为同源）、编辑距离（跨块首分片 Levenshtein 距离不得超
     *       正片相邻分片最大漂移值，同目录 +1 容忍）；</li>
     *   <li><b>夹层兜底</b>：通用规则零命中时，2~15 分片的中间块（两端均有断点）判广告
     *       （实测 play.ly166.com：广告 5 分片与正片同为随机命名，仅夹层可辨）；</li>
     *   <li><b>多数决反转</b>：广告分片数反超正片且广告分片不全相同 → 判定识别反了，取反
     *       输出被剔块，并把原正片块首部的头部标签（{@code #EXT-X-KEY}/{@code #EXT-X-MAP}
     *       等，按缺失回补）搬给输出首块；</li>
     *   <li><b>熔断</b>：拟剔除分片 &gt; 100 视为误判，放弃剥离原样返回。</li>
     * </ol>
     * 输出末块缺失 {@code #EXT-X-ENDLIST} 时自动补齐。
     */
    private static String stripAdSegments(String text) {
        if (text == null || text.indexOf("#EXTINF") < 0 || text.indexOf("#EXT-X-ENDLIST") < 0) return text;
        // 【2026-10-13 直播硬跳过·内容层】事件型播放列表（直播回放/伪直播）不做剥离
        if (text.contains("#EXT-X-PLAYLIST-TYPE:EVENT")) return text;

        // ---- 第1步：/adjump/ 硬规则（源7 明码广告，零误伤）----
        if (text.contains(AD_PATH_MARK)) {
            String[] alines = text.split("\n", -1);
            boolean[] akeep = new boolean[alines.length];
            java.util.Arrays.fill(akeep, true);
            int arm = 0;
            for (int i = 0; i < alines.length; i++) {
                String seg = segmentUri(alines[i]);
                if (seg != null && seg.contains(AD_PATH_MARK)) {
                    akeep[i] = false;
                    int p = i - 1;
                    while (p >= 0 && alines[p].trim().isEmpty()) p--;
                    if (p >= 0 && alines[p].trim().startsWith("#EXTINF")) akeep[p] = false;
                    arm++;
                }
            }
            if (arm > 0) {
                StringBuilder nb = new StringBuilder(text.length());
                for (int i = 0; i < alines.length; i++) {
                    if (akeep[i]) nb.append(alines[i]).append('\n');
                }
                text = nb.toString();
                LogBridge.i(TAG, "内置源：/adjump/ 广告分片剔除 " + arm + " 条");
            }
        }

        // ---- 第2步：按 #EXT-X-DISCONTINUITY 切块 ----
        String[] blocks = text.split(Pattern.quote(DISCONTINUITY));
        int bn = blocks.length;
        List<List<String>> segLists = new ArrayList<>(bn);
        int totalSegs = 0;
        for (String b : blocks) {
            List<String> segs = new ArrayList<>();
            for (String line : b.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) segs.add(t);
            }
            segLists.add(segs);
            totalSegs += segs.size();
        }
        if (totalSegs == 0) return text;

        // ---- 第3步：逐块同源判定（哦了电视机制）----
        boolean[] isAd = new boolean[bn];
        String prevFirst = "";
        String firstEver = "";
        int maxDrift = 0;
        for (int bi = 0; bi < bn; bi++) {
            List<String> segs = segLists.get(bi);
            if (segs.isEmpty()) continue;                 // 无分片的标签块恒保留
            if (firstEver.isEmpty()) firstEver = segs.get(0);
            String curFirst = segs.get(0);
            String nextFirst = null;
            for (int nb = bi + 1; nb < bn; nb++) {
                if (!segLists.get(nb).isEmpty()) {
                    nextFirst = segLists.get(nb).get(0);
                    break;
                }
            }
            int lenPrev = prevFirst.length();
            int lenCur = curFirst.length();
            int lenNext = nextFirst != null ? nextFirst.length() : firstEver.length();
            boolean sameDir = sameDir(curFirst, prevFirst);
            int d = editDistance(curFirst, prevFirst);
            boolean keepOk = maxDrift == 0
                    || ((lenPrev != lenNext || lenPrev == lenCur || lenNext <= 0)
                        && (sameDir || d <= maxDrift)
                        && (d <= maxDrift + 1 || !sameDir));
            if (keepOk) {
                prevFirst = curFirst;
                String prevSeg = curFirst;
                for (String sg : segs) {
                    int dd = editDistance(sg, prevSeg);
                    if (dd > maxDrift) maxDrift = dd;
                    prevSeg = sg;
                }
            } else {
                isAd[bi] = true;
                LogBridge.i(TAG, "内置源：块 " + bi + " 判广告（" + segs.size() + " 分片，首片 "
                        + truncate(curFirst) + "）");
            }
        }

        // ---- 第3.5步：夹层兜底（通用规则零命中时，2~15 分片的中间块判广告）----
        boolean anyAd = false;
        for (boolean b : isAd) {
            if (b) {
                anyAd = true;
                break;
            }
        }
        boolean fallback = false;
        if (!anyAd && bn >= 3) {
            for (int bi = 1; bi < bn - 1; bi++) {
                List<String> segs = segLists.get(bi);
                if (segs.size() >= 2 && segs.size() <= 15) {
                    isAd[bi] = true;
                    fallback = true;
                    LogBridge.i(TAG, "内置源：夹层兜底判广告（第 " + bi + " 块 " + segs.size() + " 分片）");
                }
            }
        }

        long adCount = 0;
        for (int bi = 0; bi < bn; bi++) {
            if (isAd[bi]) adCount += segLists.get(bi).size();
        }
        if (adCount == 0) return text;
        long keptCount = totalSegs - adCount;

        // ---- 第4步：多数决反转（广告分片反而占多数且不全相同 → 判定识别反了，取反输出）----
        boolean inverted = adCount > keptCount;
        if (inverted) {
            String firstAd = null;
            int same = 0;
            for (int bi = 0; bi < bn; bi++) {
                if (!isAd[bi]) continue;
                for (String s : segLists.get(bi)) {
                    if (firstAd == null) firstAd = s;
                    if (s.equals(firstAd)) same++;
                }
            }
            if (same > 1) inverted = false;   // 剔除分片全部相同（如整段重复）时不可信，不取反
        }

        List<String> outBlocks = new ArrayList<>();
        for (int bi = 0; bi < bn; bi++) {
            if (isAd[bi] == inverted) outBlocks.add(blocks[bi]);
        }
        if (outBlocks.isEmpty()) return text;
        long filtered = inverted ? keptCount : adCount;

        // 反转时把原「正片块」首部的头部标签回补给输出首块（哦了电视同款：KEY/MAP 按缺失补）
        if (inverted) {
            String firstOut = outBlocks.get(0);
            boolean outHasKey = firstOut.contains("#EXT-X-KEY");
            boolean outHasMap = firstOut.contains("#EXT-X-MAP");
            int headSrc = -1;
            for (int bi = 0; bi < bn; bi++) {
                if (!isAd[bi] && !segLists.get(bi).isEmpty()) {
                    headSrc = bi;
                    break;
                }
            }
            if (headSrc >= 0) {
                List<String> heads = new ArrayList<>();
                for (String line : blocks[headSrc].split("\n")) {
                    String t = line.trim();
                    if (!t.startsWith("#") || t.startsWith("#EXTINF")) continue;
                    if (t.startsWith("#EXT-X-KEY") && outHasKey) continue;
                    if (t.startsWith("#EXT-X-MAP") && outHasMap) continue;
                    heads.add(t);
                }
                if (!heads.isEmpty()) {
                    outBlocks.add(0, joinLines(heads) + "\n");
                }
            }
        }

        // ---- 第5步：熔断（拟剔除 >100 分片必是误判，放弃剥离）----
        if (filtered > 100) {
            LogBridge.w(TAG, "内置源：疑似误判（拟剔 " + filtered + " 分片 >100），放弃剥离");
            return text;
        }

        String joined = joinWithSeparator(outBlocks);
        if (!outBlocks.get(outBlocks.size() - 1).contains("#EXT-X-ENDLIST")) {
            joined = joined + "#EXT-X-ENDLIST\n";
        }
        LogBridge.i(TAG, "内置源：剥离中插广告分片 " + filtered + " 条"
                + (inverted ? "（多数决反转）" : "") + (fallback ? "（夹层兜底）" : ""));
        return joined;
    }

    /**
     * 【2026-10-13 直播硬跳过】判断 m3u8 地址是否为直播源（含直播特征标记）。
     * 命中即跳过广告剥离。大小写不敏感，方向保守（宁可少剥，不误伤直播）。
     */
    private static boolean isLikelyLiveUrl(@Nullable String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase(java.util.Locale.ROOT);
        for (String m : LIVE_URL_MARKERS) {
            if (u.contains(m)) return true;
        }
        return false;
    }

    /** 行若是分片 URI（非空、不以 # 开头）则返回（已 trim）；否则返回 {@code null} */
    private static String segmentUri(String t) {
        if (t == null || t.isEmpty() || t.startsWith("#")) return null;
        return t.trim();
    }

    /** 两条分片 URI 是否同目录（文件名之外的路径前缀一致） */
    private static boolean sameDir(String a, String b) {
        int ia = a.lastIndexOf('/');
        int ib = b.lastIndexOf('/');
        if (ia != -1 && ib != -1) return a.substring(0, ia).equals(b.substring(0, ib));
        return a.equals(b);
    }

    /** Levenshtein 编辑距离（哦了电视同源判定的距离函数） */
    private static int editDistance(String a, String b) {
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();
        int la = a.length();
        int lb = b.length();
        int[][] dp = new int[lb + 1][la + 1];
        for (int i = 0; i <= la; i++) dp[0][i] = i;
        for (int j = 0; j <= lb; j++) dp[j][0] = j;
        for (int j = 1; j <= lb; j++) {
            for (int i = 1; i <= la; i++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[j][i] = Math.min(Math.min(dp[j][i - 1] + 1, dp[j - 1][i] + 1), dp[j - 1][i - 1] + cost);
            }
        }
        return dp[lb][la];
    }

    /** 日志用截断（最多 60 字符） */
    private static String truncate(String s) {
        return s == null ? "" : (s.length() <= 60 ? s : s.substring(0, 60));
    }

    /** 多行拼接（\n 分隔） */
    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** 块拼接（#EXT-X-DISCONTINUITY 分隔） */
    private static String joinWithSeparator(List<String> blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(DISCONTINUITY);
            sb.append(blocks.get(i));
        }
        return sb.toString();
    }

    /** 工厂：包装任意上游 DataSource.Factory，仅对 m3u8 做归一化。 */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;
        private final boolean stripAds;

        public Factory(DataSource.Factory upstreamFactory) {
            this(upstreamFactory, false);
        }

        /** 【2026-10-13】stripAds=true 时启用中插广告剥离（所有内置源统一传入 true） */
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