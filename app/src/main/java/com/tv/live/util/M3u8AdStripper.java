package com.tv.live.util;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.util.LogBridge;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.Response;

/**
 * M3U8 广告剥离工具（移植自脱壳样本的完整算法）
 *
 * 核心原理：
 * 1. 按 #EXT-X-DISCONTINUITY 将 m3u8 切成多个块
 * 2. 逐块判定是否同源（同目录 + URL 编辑距离 ≤ 阈值）
 * 3. 广告块通常 URL 目录/命名规则与正片不同
 * 4. 多数决反转：若广告分片数 > 正片分片数，说明识别反了，取反
 * 5. 熔断保护：拟剔除分片 > 100 时放弃剥离，避免误删正片
 *
 * 另一种检测方式：按时长异常检测
 * - 计算每个块的总时长
 * - 时长明显偏短（< 平均值一半 且 < 60秒）的块判为广告
 * - 总广告时长 > 180秒 或 无广告块 → 放弃剥离
 */
public class M3u8AdStripper {

    private static final String TAG = "M3u8AdStripper";

    // 【测试开关】禁用所有4层广告剥离算法，仅测试DNS拦截
    // true = 禁用m3u8层剥离，广告分片会保留，由DNS拦截在HTTP层拦截
    // false = 正常使用4层算法（默认）
    public static final boolean DISABLE_ALL_STRIP_ALGORITHMS = false;

    // 匹配 #EXT-X-DISCONTINUITY 到下一个 DISCONTINUITY 或结尾的块
    private static final Pattern DISCONTINUITY_BLOCK_PATTERN =
            Pattern.compile("#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|$)");

    // 匹配 #EXTINF:时长
    private static final Pattern EXTINF_DURATION_PATTERN =
            Pattern.compile("#EXTINF:([\\d\\.]+)\\b");

    // 匹配 URI="..."
    private static final Pattern URI_PATTERN =
            Pattern.compile("URI=\"(.+?)\"");

    // 熔断阈值：拟剔除分片超过此数则放弃剥离
    private static final int MAX_STRIP_SEGMENTS = 500; // 【2026-10-16】从100提高到500：长视频中插广告可能占50%以上，原阈值过低导致熔断

    // 编辑距离阈值：超过此值认为不同源
    private static final int EDIT_DISTANCE_THRESHOLD = 3;

    // 广告块时长上限（秒）：超过此值不认为是广告
    private static final int AD_BLOCK_MAX_DURATION = 60;

    // 广告总时长熔断阈值（秒）：超过此值放弃剥离
    private static final int AD_TOTAL_DURATION_FUSE = 180;

    // ========================================================================
    // 主入口：剥离 m3u8 中的广告
    // ========================================================================

    /**
     * 剥离 m3u8 内容中的广告块（不带 baseUrl，不做相对URL转换）
     */
    public static String stripAds(String m3u8Content) {
        return stripAds(m3u8Content, null);
    }

    /**
     * 剥离 m3u8 内容中的广告块（带 baseUrl，自动转换相对URL为绝对URL）
     *
     * @param m3u8Content 原始 m3u8 内容
     * @param baseUrl     m3u8 的原始 URL，用于解析相对路径
     * @return 剥离广告后的 m3u8 内容
     */
    public static String stripAds(String m3u8Content, String baseUrl) {
        if (TextUtils.isEmpty(m3u8Content)) {
            return m3u8Content;
        }

        long startTime = System.currentTimeMillis();
        // 归一化换行符
        String content = m3u8Content.replace("\r\n", "\n");

        // 统计分片数量
        int segmentCount = countSegments(content);
        int discontinuityCount = countOccurrences(content, "#EXT-X-DISCONTINUITY");

        LogBridge.i(TAG, "========== 广告剥离流程开始 ==========");
        LogBridge.i(TAG, "输入: 分片数=" + segmentCount + ", DISCONTINUITY数=" + discontinuityCount
                + ", baseUrl=" + (baseUrl != null ? baseUrl.substring(0, Math.min(50, baseUrl.length())) : "null"));

        // 【自动识别2】是点播（有ENDLIST）？
        boolean isVod = content.contains("#EXT-X-ENDLIST");
        if (!isVod) {
            LogBridge.i(TAG, "【自动识别】直播流（无 ENDLIST），跳过广告剥离，仅转相对URL为绝对URL");
            String result = content;
            if (!TextUtils.isEmpty(baseUrl)) {
                result = resolveAllUrls(content, baseUrl);
            }
            LogBridge.i(TAG, "========== 广告剥离流程结束（直播跳过）耗时=" + (System.currentTimeMillis() - startTime) + "ms ==========");
            return result;
        }
        LogBridge.i(TAG, "【自动识别】点播流（有 ENDLIST），进入广告剥离");

        // 【测试开关】如果禁用所有算法，只转相对URL为绝对，直接返回
        if (DISABLE_ALL_STRIP_ALGORITHMS) {
            LogBridge.w(TAG, "【测试模式】已禁用所有4层剥离算法，仅转相对URL为绝对，广告由DNS拦截处理");
            if (!TextUtils.isEmpty(baseUrl)) {
                content = resolveAllUrls(content, baseUrl);
            }
            LogBridge.i(TAG, "========== 广告剥离流程结束（测试模式-禁用算法）耗时=" + (System.currentTimeMillis() - startTime) + "ms ==========");
            return content;
        }

        // 先把相对URL转绝对（确保后续同源判定准确）
        if (!TextUtils.isEmpty(baseUrl)) {
            content = resolveAllUrls(content, baseUrl);
            LogBridge.d(TAG, "相对URL已转为绝对URL");
        }

        // 【自动选择算法①】DISCONTINUITY 切块 + 同源判定（主算法）
        LogBridge.i(TAG, "【算法选择①】尝试 DISCONTINUITY 切块 + 同源判定（主算法）");
        String result = stripByDiscontinuity(content);
        if (result != null && !result.equals(content)) {
            int strippedCount = segmentCount - countSegments(result);
            LogBridge.i(TAG, "【算法选择①】主算法成功！剔除广告分片 " + strippedCount + " 个");
            LogBridge.i(TAG, "========== 广告剥离流程结束（主算法成功）耗时=" + (System.currentTimeMillis() - startTime) + "ms ==========");
            return result;
        }
        LogBridge.i(TAG, "【算法选择①】主算法未命中，降级到备用算法");

        // 【自动选择算法②】按时长异常检测广告块（备用算法）
        LogBridge.i(TAG, "【算法选择②】尝试 时长异常检测（备用算法）");
        result = stripByDurationAnomaly(content);
        if (result != null && !result.equals(content)) {
            int strippedCount = segmentCount - countSegments(result);
            LogBridge.i(TAG, "【算法选择②】时长异常算法成功！剔除广告分片 " + strippedCount + " 个");
            LogBridge.i(TAG, "========== 广告剥离流程结束（时长异常成功）耗时=" + (System.currentTimeMillis() - startTime) + "ms ==========");
            return result;
        }
        LogBridge.i(TAG, "【算法选择②】时长异常算法未命中");

        // 【自动选择算法③】按广告关键词/URL 路径过滤
        LogBridge.i(TAG, "【算法选择③】尝试 广告关键词/URL 路径过滤");
        result = stripByAdKeyword(content);
        if (result != null && !result.equals(content)) {
            int strippedCount = segmentCount - countSegments(result);
            LogBridge.i(TAG, "【算法选择③】关键词过滤成功！剔除广告分片 " + strippedCount + " 个");
            LogBridge.i(TAG, "========== 广告剥离流程结束（关键词过滤成功）耗时=" + (System.currentTimeMillis() - startTime) + "ms ==========");
            return result;
        }
        LogBridge.i(TAG, "【算法选择③】关键词过滤未命中");

        // 【自动选择算法④】按广告时长前缀过滤（常见广告时长 15s/30s/45s/60s）
        LogBridge.i(TAG, "【算法选择④】尝试 广告时长前缀过滤（15/30/45/60s）");
        java.util.List<String> durationPrefixes = new java.util.ArrayList<>();
        durationPrefixes.add("15");
        durationPrefixes.add("30");
        durationPrefixes.add("45");
        durationPrefixes.add("60");
        result = stripByDurationPrefix(content, durationPrefixes);
        if (result != null && !result.equals(content)) {
            int strippedCount = segmentCount - countSegments(result);
            LogBridge.i(TAG, "【算法选择④】时长前缀过滤成功！剔除广告分片 " + strippedCount + " 个");
            LogBridge.i(TAG, "========== 广告剥离流程结束（时长前缀成功）耗时=" + (System.currentTimeMillis() - startTime) + "ms ==========");
            return result;
        }
        LogBridge.i(TAG, "【算法选择④】时长前缀过滤未命中");

        // 【自动选择算法⑤】都失败 → 返回原文，不影响播放
        LogBridge.i(TAG, "【算法选择⑤】所有算法均未命中，返回原文，不影响播放");
        LogBridge.i(TAG, "========== 广告剥离流程结束（未命中）耗时=" + (System.currentTimeMillis() - startTime) + "ms ==========");
        return content;
    }

    /**
     * 统计 m3u8 中的分片数量（#EXTINF 行数）
     */
    private static int countSegments(String content) {
        if (TextUtils.isEmpty(content)) return 0;
        int count = 0;
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("#EXTINF:")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计字符串中某个子串出现的次数
     */
    private static int countOccurrences(String content, String pattern) {
        if (TextUtils.isEmpty(content) || TextUtils.isEmpty(pattern)) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    /**
     * 检测是否为 Master Playlist（包含 #EXT-X-STREAM-INF）
     */
    public static boolean isMasterPlaylist(String content) {
        if (TextUtils.isEmpty(content)) return false;
        return content.contains("#EXT-X-STREAM-INF");
    }

    /**
     * 从 Master Playlist 中获取第一个子流的 URL（已转为绝对路径）
     *
     * @param content master m3u8 内容
     * @param baseUrl master m3u8 的原始 URL
     * @return 第一个子流的绝对 URL，失败返回 null
     */
    public static String getFirstVariantUrl(String content, String baseUrl) {
        if (TextUtils.isEmpty(content) || TextUtils.isEmpty(baseUrl)) return null;
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.length) {
                String variantUrl = lines[i + 1].trim();
                if (!TextUtils.isEmpty(variantUrl)) {
                    return resolveRelative(baseUrl, variantUrl);
                }
            }
        }
        return null;
    }

    /**
     * 将 m3u8 中所有相对 URL 转换为绝对 URL
     * 包括：分片 URL、#EXT-X-KEY:URI、#EXT-X-MAP:URI
     */
    public static String resolveAllUrls(String content, String baseUrl) {
        if (TextUtils.isEmpty(content) || TextUtils.isEmpty(baseUrl)) {
            return content;
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (needsUrlResolution(trimmed)) {
                // 普通分片 URL（相对路径）
                if (!trimmed.startsWith("#") && !trimmed.startsWith("http")) {
                    sb.append(resolveRelative(baseUrl, trimmed));
                }
                // #EXT-X-KEY:URI="..." 或 #EXT-X-MAP:URI="..."
                else if (trimmed.startsWith("#") && trimmed.contains("URI=")) {
                    sb.append(resolveKeyUri(line, baseUrl));
                } else {
                    sb.append(line);
                }
            } else {
                sb.append(line);
            }

            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 解析 #EXT-X-KEY / #EXT-X-MAP 中的 URI 相对路径
     */
    private static String resolveKeyUri(String line, String baseUrl) {
        java.util.regex.Matcher matcher = URI_PATTERN.matcher(line);
        if (matcher.find()) {
            String uri = matcher.group(1);
            if (uri != null && !uri.startsWith("http://") && !uri.startsWith("https://")) {
                String resolved = resolveRelative(baseUrl, uri);
                return line.replace(uri, resolved);
            }
        }
        return line;
    }

    // ========================================================================
    // 算法一：DISCONTINUITY 切块 + 同源判定 + 多数决反转
    // ========================================================================

    /**
     * 按 #EXT-X-DISCONTINUITY 切块，通过同源判定识别广告块
     *
     * 算法流程：
     * 1. 按 DISCONTINUITY 切块
     * 2. 逐块收集所有分片 URL
     * 3. 与前一块比较：同目录判定 + 编辑距离
     * 4. 广告块计数 vs 正片块计数
     * 5. 多数决反转：广告 > 正片 → 取反
     * 6. 熔断：拟剔除 > 100 分片 → 返回原文
     */
    private static String stripByDiscontinuity(String content) {
        String[] blocks = content.split("#EXT-X-DISCONTINUITY");

        // 【2026-10-16 优化】多维度广告块检测：
        // 1. ts文件名连续性检测（广告块文件名通常不连续）
        // 2. 块内分片数异常检测（广告块通常1-3个分片）
        // 3. EXTINF时长异常检测（广告块时长偏短）
        // 4. #EXT-X-CUE-OUT/CUE-IN 标准广告标记检测
        // 5. 同源判定（不同源的块可能是广告）
        // 综合评分，超过阈值判定为广告块

        // 先检测标准广告标记
        boolean hasCueMarkers = content.contains("#EXT-X-CUE-OUT") || content.contains("#EXT-X-CUE-IN");
        if (hasCueMarkers) {
            LogBridge.i(TAG, "【算法选择①】检测到 CUE-OUT/CUE-IN 广告标记");
        }

        // 计算每个块的特征
        int[] blockSegCounts = new int[blocks.length];
        BigDecimal[] blockDurations = new BigDecimal[blocks.length];
        boolean[] blockFilenameContinuous = new boolean[blocks.length];
        int avgSegCount = 0;
        BigDecimal avgDuration = BigDecimal.ZERO;
        int validBlockCount = 0;

        for (int i = 0; i < blocks.length; i++) {
            String[] lines = blocks[i].split("\n");
            ArrayList<String> segUrls = new ArrayList<>();
            BigDecimal duration = BigDecimal.ZERO;
            for (String line : lines) {
                String trim = line.trim();
                if (trim.startsWith("#EXTINF:")) {
                    try {
                        String d = trim.substring(8).split(",")[0];
                        duration = duration.add(new BigDecimal(d));
                    } catch (Exception ignored) {}
                } else if (!trim.startsWith("#") && !trim.isEmpty()) {
                    segUrls.add(trim);
                }
            }
            blockSegCounts[i] = segUrls.size();
            blockDurations[i] = duration;
            if (segUrls.size() > 0) {
                avgSegCount += segUrls.size();
                avgDuration = avgDuration.add(duration);
                validBlockCount++;
                // 检测文件名连续性
                blockFilenameContinuous[i] = isFilenamesContinuous(segUrls);
            }
        }
        if (validBlockCount > 0) {
            avgSegCount /= validBlockCount;
            avgDuration = avgDuration.divide(new BigDecimal(validBlockCount), 2, RoundingMode.HALF_UP);
        }
        LogBridge.d(TAG, "【算法选择①】块统计: 有效块=" + validBlockCount + ", 平均分片数=" + avgSegCount + ", 平均时长=" + avgDuration + "s");

        LinkedList<String> keepBlocks = new LinkedList<>();  // 保留的块（正片）
        LinkedList<String> adBlocks = new LinkedList<>();    // 广告块
        ArrayList<String> keepUrls = new ArrayList<>();      // 正片分片URL
        ArrayList<String> adUrls = new ArrayList<>();        // 广告分片URL
        ArrayList<Integer> adBlockIndices = new ArrayList<>(); // 【2026-10-16】广告块索引，用于位置分布分析

        String firstUrl = "";
        String lastUrl = "";
        int totalSegments = 0;
        int adSegments = 0;
        int maxEditDistance = 0;
        BigDecimal cumulativeDuration = BigDecimal.ZERO; // 累计时长，用于计算广告块位置

        // 【2026-10-16】全局统计哈希文件名比例：如果大部分块都是哈希文件名，说明该源命名方式就是哈希，
        // 维度7（文件名哈希检测）不应加分，否则会把所有正片都误判为广告
        int hashFilenameCount = 0;
        int totalNonEmptyBlocks = 0;
        for (int bi = 0; bi < blocks.length; bi++) {
            String[] bLines = blocks[bi].split("\n");
            String firstSegUrl = "";
            for (String line : bLines) {
                if (!line.startsWith("#") && !TextUtils.isEmpty(line)) {
                    firstSegUrl = line;
                    break;
                }
            }
            if (!TextUtils.isEmpty(firstSegUrl)) {
                totalNonEmptyBlocks++;
                int sIdx = firstSegUrl.lastIndexOf("/");
                String fname = sIdx >= 0 ? firstSegUrl.substring(sIdx + 1) : firstSegUrl;
                int qIdx = fname.indexOf("?");
                if (qIdx > 0) fname = fname.substring(0, qIdx);
                fname = fname.replace(".ts", "").replace(".m4s", "");
                if (fname.length() == 32 && !fname.matches("\\d+")) {
                    hashFilenameCount++;
                }
            }
        }
        boolean hashFilenameDominant = totalNonEmptyBlocks > 0 && (hashFilenameCount * 2 > totalNonEmptyBlocks);
        if (hashFilenameDominant) {
            LogBridge.d(TAG, "全局统计：哈希文件名块=" + hashFilenameCount + "/" + totalNonEmptyBlocks + "，该源命名方式为哈希，禁用维度7");
        }

        for (int i = 0; i < blocks.length; i++) {
            String block = blocks[i];
            String[] lines = block.split("\n");

            // 收集本块的所有分片 URL
            ArrayList<String> blockUrls = new ArrayList<>();
            for (String line : lines) {
                if (!line.startsWith("#") && !TextUtils.isEmpty(line)) {
                    blockUrls.add(line);
                }
            }

            if (blockUrls.isEmpty()) {
                // 空块（头部信息），直接保留
                keepBlocks.add(block);
                continue;
            }

            // 记录第一个 URL
            if (firstUrl.length() == 0) {
                firstUrl = blockUrls.get(0);
            }

            // 与上一块比较是否同源
            boolean sameDir = isSameDirectory(blockUrls.get(0), lastUrl);
            int editDist = levenshteinDistance(blockUrls.get(0), lastUrl);

            // 看下一块的第一个 URL 长度（用于边界判定）
            int nextUrlLen = 0;
            if (i + 1 < blocks.length) {
                String[] nextLines = blocks[i + 1].split("\n");
                for (String line : nextLines) {
                    if (!TextUtils.isEmpty(line) && !line.startsWith("#")) {
                        nextUrlLen = line.length();
                        break;
                    }
                }
            } else {
                nextUrlLen = firstUrl.length();
            }

            int curUrlLen = blockUrls.get(0).length();
            int lastUrlLen = lastUrl.length();

            // 【2026-10-16 优化】排除0时长或≤1分片的块（m3u8头部，样本7开头就是3分片广告不能排除）
            boolean isHeaderBlock = (blockDurations[i] == null || blockDurations[i].intValue() == 0) && blockSegCounts[i] <= 1;
            if (isHeaderBlock) {
                if (blockDurations[i] != null) {
                    cumulativeDuration = cumulativeDuration.add(blockDurations[i]);
                }
                lastUrl = blockUrls.get(0);
                for (String url : blockUrls) {
                    int d = levenshteinDistance(url, lastUrl);
                    if (d > maxEditDistance) maxEditDistance = d;
                    keepUrls.add(url);
                    lastUrl = url;
                }
                keepBlocks.add(block);
                totalSegments += blockUrls.size();
                continue;
            }

            // 【2026-10-16 优化】多维度综合评分判定广告块
            // 维度1: 块内分片数异常（26样本统计：广告块固定3个分片，正片5-10个）
            boolean segCountAnomaly = blockSegCounts[i] > 0 && blockSegCounts[i] <= 3 &&
                    blockSegCounts[i] < avgSegCount / 2;
            boolean exactlyThreeSeg = blockSegCounts[i] == 3 && blockSegCounts[i] < avgSegCount / 2;
            // 维度2: 时长异常（广告块时长偏短）
            boolean durationAnomaly = blockDurations[i] != null &&
                    blockDurations[i].compareTo(avgDuration.divide(new BigDecimal(2), 2, RoundingMode.HALF_UP)) < 0 &&
                    blockDurations[i].intValue() < 30;
            // 维度3: 文件名不连续（广告块文件名通常不连续）
            boolean filenameDiscontinuous = !blockFilenameContinuous[i];
            // 维度4: 不同源且编辑距离超阈值
            boolean originAnomaly = !sameDir && editDist > maxEditDistance;
            // 维度5: URL长度突变
            boolean urlLenAnomaly = lastUrl.length() > 0 && lastUrlLen != curUrlLen &&
                    lastUrlLen == nextUrlLen && nextUrlLen > 0;
            // 维度6: 相邻小块联合判定（连续两个<=3分片的小块，通常是广告）
            boolean adjacentSmallBlock = false;
            if (blockSegCounts[i] > 0 && blockSegCounts[i] <= 3) {
                boolean prevSmall = (i > 0 && blockSegCounts[i - 1] > 0 && blockSegCounts[i - 1] <= 3);
                boolean nextSmall = (i + 1 < blocks.length && blockSegCounts[i + 1] > 0 && blockSegCounts[i + 1] <= 3);
                adjacentSmallBlock = prevSmall || nextSmall;
            }
            // 维度7: 文件名哈希长度检测（广告块文件名多为32位MD5哈希，正片多为数字递增）
            // 【2026-10-16】仅当该源不是哈希命名主导时才启用，否则所有正片都会被误判
            boolean filenameHashAnomaly = false;
            if (!hashFilenameDominant && blockUrls.size() > 0) {
                String firstName = blockUrls.get(0);
                int slashIdx = firstName.lastIndexOf("/");
                String fname = slashIdx >= 0 ? firstName.substring(slashIdx + 1) : firstName;
                int qIdx = fname.indexOf("?");
                if (qIdx > 0) fname = fname.substring(0, qIdx);
                fname = fname.replace(".ts", "").replace(".m4s", "");
                // 32位哈希且非纯数字
                if (fname.length() == 32 && !fname.matches("\\d+")) {
                    filenameHashAnomaly = true;
                }
            }

            // 综合评分：满足2个以上维度判定为广告块
            int adScore = 0;
            if (segCountAnomaly) adScore++;
            if (exactlyThreeSeg) adScore += 2;  // 26样本统计：广告块几乎都是恰好3分片
            if (durationAnomaly) adScore++;
            if (filenameDiscontinuous) adScore++;
            if (originAnomaly) adScore++;
            if (urlLenAnomaly) adScore++;
            if (adjacentSmallBlock) adScore += 2;
            if (filenameHashAnomaly) adScore++;

            // 【2026-10-16 修复】广告块上限阈值：避免大块正片误判
            // 广告块不可能超过60秒或超过20个分片（26样本统计：广告块9-26秒/3-9分片）
            boolean tooLargeForAd = (blockDurations[i] != null && blockDurations[i].intValue() > 60) ||
                    blockSegCounts[i] > 20;
            boolean isAdBlock = (lastUrl.length() > 0) && adScore >= 2 && !tooLargeForAd;
            if (isAdBlock) {
                int startSec = cumulativeDuration.intValue();
                int min = startSec / 60;
                int sec = startSec % 60;
                LogBridge.i(TAG, "  [广告块" + i + "] 位置=" + min + "分" + sec + "秒, 时长=" +
                        blockDurations[i] + "s, 评分=" + adScore + " (分片数=" + blockSegCounts[i] +
                        ", 文件名连续=" + blockFilenameContinuous[i] + ")");
            }
            // 累加当前块时长
            if (blockDurations[i] != null) {
                cumulativeDuration = cumulativeDuration.add(blockDurations[i]);
            }

            if (!isAdBlock) {
                // 正片块
                lastUrl = blockUrls.get(0);
                for (String url : blockUrls) {
                    int d = levenshteinDistance(url, lastUrl);
                    if (d > maxEditDistance) {
                        maxEditDistance = d;
                    }
                    keepUrls.add(url);
                    lastUrl = url;
                }
                keepBlocks.add(block);
                totalSegments += blockUrls.size();
            } else {
                // 广告块
                adSegments += blockUrls.size();
                for (String url : blockUrls) {
                    adUrls.add(url);
                }
                adBlocks.add(block);
                adBlockIndices.add(i); // 记录广告块索引
                totalSegments += blockUrls.size();
            }
        }

        // 【2026-10-16 优化】智能多数决反转：结合广告块位置分布判断
        // 原逻辑：广告分片 > 正片分片 → 取反。但当视频确实广告占大部分时（如盗版视频后10分钟全是广告），
        // 会错误地把正片当成广告剔除。
        // 新逻辑：只有当广告块分散分布在整个视频中（不集中在某一段），且广告分片 > 正片分片时，才取反。
        // 如果广告块集中在某一段（如后半部分），即使占多数也不取反（因为确实是广告）。
        int keepSegments = totalSegments - adSegments;
        boolean reversed = false;
        if (adSegments > keepSegments && adBlockIndices.size() > 0) {
            // 分析广告块位置分布：前30%、中40%、后30%
            int totalBlocks = blocks.length;
            int frontCount = 0, middleCount = 0, endCount = 0;
            for (int idx : adBlockIndices) {
                double pos = (double) idx / totalBlocks;
                if (pos < 0.3) frontCount++;
                else if (pos < 0.7) middleCount++;
                else endCount++;
            }
            int adBlockCount = adBlockIndices.size();
            // 如果广告块集中在某一区域（>60%在同一区域），说明确实是广告集中段，不取反
            boolean concentrated = (frontCount * 1.0 / adBlockCount > 0.6) ||
                                   (middleCount * 1.0 / adBlockCount > 0.6) ||
                                   (endCount * 1.0 / adBlockCount > 0.6);
            if (!concentrated) {
                reversed = true;
                LogBridge.d(TAG, "智能多数决反转：广告分片(" + adSegments + ") > 正片分片(" + keepSegments +
                        ")，且广告块分散分布(前=" + frontCount + " 中=" + middleCount + " 后=" + endCount + ")，取反");
            } else {
                LogBridge.d(TAG, "智能多数决反转：广告分片(" + adSegments + ") > 正片分片(" + keepSegments +
                        ")，但广告块集中分布(前=" + frontCount + " 中=" + middleCount + " 后=" + endCount + ")，不取反");
            }
        }

        LinkedList<String> finalBlocks;
        ArrayList<String> finalAdUrls;
        int finalAdCount;

        if (reversed) {
            // 反转：原来的"广告块"其实是正片，原来的"正片块"其实是广告
            LogBridge.d(TAG, "多数决反转：广告分片(" + adSegments + ") > 正片分片(" + keepSegments + ")，取反");

            // 把头部信息（第一个 keepBlock）移到 adBlocks 前面
            String headerBlock = "";
            if (!keepBlocks.isEmpty() && keepBlocks.get(0).split("\n").length <= 3) {
                headerBlock = keepBlocks.remove(0);
            }

            // 检查第一个广告块是否包含 KEY/MAP 信息
            boolean hasKey = !adBlocks.isEmpty() && adBlocks.getFirst().contains("#EXT-X-KEY");
            boolean hasMap = !adBlocks.isEmpty() && adBlocks.getFirst().contains("#EXT-X-MAP");

            if (!TextUtils.isEmpty(headerBlock)) {
                // 从 headerBlock 中提取非 EXTINF/非URL 的头部行
                String[] headerLines = headerBlock.split("\n");
                ArrayList<String> headerOnly = new ArrayList<>();
                for (String line : headerLines) {
                    if (line.startsWith("#") && !line.startsWith("#EXTINF") &&
                        (!line.startsWith("#EXT-X-KEY") || (line.startsWith("#EXT-X-KEY") && !hasKey)) &&
                        (!line.startsWith("#EXT-X-MAP") || (line.startsWith("#EXT-X-MAP") && !hasMap))) {
                        headerOnly.add(line);
                    }
                }
                adBlocks.addFirst(TextUtils.join("\n", headerOnly) + "\n");
            }

            finalBlocks = adBlocks;
            finalAdUrls = keepUrls;
            finalAdCount = keepSegments;
        } else {
            finalBlocks = keepBlocks;
            finalAdUrls = adUrls;
            finalAdCount = adSegments;
        }

        // 熔断：拟剔除分片超过阈值，放弃剥离
        if (finalAdCount > MAX_STRIP_SEGMENTS) {
            LogBridge.w(TAG, "熔断：拟剔除 " + finalAdCount + " 分片 > " + MAX_STRIP_SEGMENTS + "，放弃剥离");
            return content;
        }

        // 输出日志（已注释详细URL，避免刷屏；汇总统计见下方"广告剥离完成"）
        // for (String url : finalAdUrls) {
        //     LogBridge.d(TAG, "过滤广告分片: " + url);
        // }

        if (finalAdCount > 0) {
            LogBridge.i(TAG, "广告剥离完成：剔除 " + finalAdCount + " 个分片");
        }

        // 拼接结果
        String result = TextUtils.join("#EXT-X-DISCONTINUITY", finalBlocks);

        // 【修复】确保结果包含 #EXTM3U 头（第一个块可能被当作广告剔除）
        if (!result.contains("#EXTM3U")) {
            StringBuilder header = new StringBuilder("#EXTM3U\n");
            for (String hline : content.split("\n")) {
                String th = hline.trim();
                if (th.startsWith("#EXT-X-VERSION") || th.startsWith("#EXT-X-TARGETDURATION") ||
                    th.startsWith("#EXT-X-MEDIA-SEQUENCE") || th.startsWith("#EXT-X-KEY") ||
                    th.startsWith("#EXT-X-MAP") || th.startsWith("#EXT-X-PLAYLIST-TYPE")) {
                    header.append(hline).append("\n");
                }
            }
            result = header.toString() + result;
        }

        // 确保以 ENDLIST 结尾
        if (!finalBlocks.isEmpty() && finalBlocks.getLast().contains("#EXT-X-ENDLIST")) {
            return result;
        }
        return result + "#EXT-X-ENDLIST\n";
    }

    // ========================================================================
    // 算法二：按时长异常检测广告块
    // ========================================================================

    /**
     * 按时长异常检测广告块
     *
     * 原理：广告块通常时长明显短于正片块
     * - 计算每个 DISCONTINUITY 块的总时长
     * - 时长 < 平均值一半 且 < 60秒 的块判为广告
     * - 总广告时长 > 180秒 或 无广告块 → 放弃剥离
     */
    private static String stripByDurationAnomaly(String content) {
        String[] blocks = content.split("#EXT-X-DISCONTINUITY");

        ArrayList<BigDecimal> blockDurations = new ArrayList<>();
        ArrayList<Integer> blockIndices = new ArrayList<>();
        HashMap<Integer, Integer> indexMap = new HashMap<>();

        BigDecimal totalDuration = BigDecimal.ZERO;
        int validBlockCount = 0;

        for (int i = 0; i < blocks.length; i++) {
            String block = blocks[i];
            BigDecimal blockDuration = BigDecimal.ZERO;

            java.util.regex.Matcher matcher = EXTINF_DURATION_PATTERN.matcher(block);
            while (matcher.find()) {
                blockDuration = blockDuration.add(new BigDecimal(matcher.group(1)));
            }

            if (!blockDuration.equals(BigDecimal.ZERO)) {
                blockDurations.add(blockDuration);
                totalDuration = totalDuration.add(blockDuration);
                indexMap.put(validBlockCount, i);
                validBlockCount++;
            }
        }

        // 块数太少，无法判定
        if (validBlockCount < 2) {
            return null;
        }

        // 计算平均时长
        BigDecimal avgDuration = totalDuration.divide(
                new BigDecimal(validBlockCount), 2, RoundingMode.HALF_UP);

        // 找出时长异常偏短的块
        ArrayList<Integer> adBlockIndices = new ArrayList<>();
        int adTotalDuration = 0;
        BigDecimal cumulativeDuration = BigDecimal.ZERO;

        for (int i = 0; i < validBlockCount; i++) {
            BigDecimal duration = blockDurations.get(i);
            int originalBlockIndex = indexMap.get(i);
            // 时长 < 平均值一半 且 < 60秒 → 广告块
            if (duration.subtract(avgDuration).intValue() < 0 &&
                avgDuration.intValue() > duration.intValue() * 2 &&
                duration.intValue() < AD_BLOCK_MAX_DURATION) {
                adBlockIndices.add(originalBlockIndex);
                adTotalDuration += duration.intValue();
                // 计算广告块在视频中的时间位置（分:秒）
                int startSec = cumulativeDuration.intValue();
                int min = startSec / 60;
                int sec = startSec % 60;
                LogBridge.i(TAG, "  [广告块" + originalBlockIndex + "] 位置=" + min + "分" + sec + "秒, 时长=" + duration.intValue() + "秒, 平均=" + avgDuration.intValue() + "秒");
            }
            cumulativeDuration = cumulativeDuration.add(duration);
        }

        // 无广告块 或 广告总时长过长（可能误判）→ 放弃
        if (adBlockIndices.isEmpty() || adTotalDuration > AD_TOTAL_DURATION_FUSE) {
            return null;
        }

        return filterBlocks(content, blocks, adBlockIndices);
    }

    /**
     * 过滤指定的广告块，输出剥离后的 m3u8
     */
    private static String filterBlocks(String original, String[] blocks, List<Integer> adIndices) {
        ArrayList<String> keepBlocks = new ArrayList<>();
        int removedSegments = 0;

        for (int i = 0; i < blocks.length; i++) {
            if (adIndices.contains(i)) {
                // 统计被剔除的分片数
                int segCount = 0;
                for (String line : blocks[i].split("\n")) {
                    if (!line.startsWith("#") && !TextUtils.isEmpty(line)) {
                        segCount++;
                    }
                }

                // 分片数 > 60 的块不剔除（可能是正片）
                if (segCount > 60) {
                    keepBlocks.add(blocks[i]);
                    continue;
                }

                // 第一个块保留头部信息（#EXTM3U 等）
                if (i == 0) {
                    String[] lines = blocks[i].split("\n");
                    ArrayList<String> headerLines = new ArrayList<>();
                    for (String line : lines) {
                        if (!line.startsWith("#EXTINF") && !line.startsWith("http")) {
                            headerLines.add(line);
                        }
                    }
                    keepBlocks.add(TextUtils.join("\n", headerLines) + "\n");
                }

                removedSegments += segCount;
                LogBridge.d(TAG, "过滤广告块 " + i + "（" + segCount + " 分片）");
            } else {
                keepBlocks.add(blocks[i]);
            }
        }

        // 熔断
        if (removedSegments > MAX_STRIP_SEGMENTS) {
            LogBridge.w(TAG, "熔断：剔除 " + removedSegments + " 分片 > " + MAX_STRIP_SEGMENTS);
            return original;
        }

        if (removedSegments > 0) {
            LogBridge.i(TAG, "时长异常检测：剔除 " + removedSegments + " 个广告分片");
        }

        String result = TextUtils.join("#EXT-X-DISCONTINUITY", keepBlocks);

        if (result.contains("#EXT-X-ENDLIST")) {
            return result;
        }
        return result + "#EXT-X-ENDLIST\n";
    }

    // ========================================================================
    // 辅助方法：同源判定
    // ========================================================================

    /**
     * 判定所有块是否同源（同目录 + 编辑距离 ≤ 阈值）
     */
    private static boolean isSameOrigin(String[] blocks) {
        // 第一轮：检查目录是否一致
        String lastUrl = "";
        for (String block : blocks) {
            for (String line : block.split("\n")) {
                if (line.startsWith("http")) {
                    if (!TextUtils.isEmpty(lastUrl)) {
                        int lastSlash = lastUrl.lastIndexOf("/");
                        int curSlash = line.lastIndexOf("/");
                        if (lastSlash <= -1 || curSlash <= -1) {
                            return false;
                        }
                        if (!line.substring(0, curSlash).equals(lastUrl.substring(0, lastSlash))) {
                            return true; // 目录不同 → 存在广告
                        }
                    }
                    lastUrl = line;
                }
            }
        }

        // 第二轮：检查编辑距离
        String firstUrl = "";
        for (String block : blocks) {
            for (String line : block.split("\n")) {
                if (line.startsWith("http")) {
                    if (!TextUtils.isEmpty(firstUrl) && levenshteinDistance(firstUrl, line) > EDIT_DISTANCE_THRESHOLD) {
                        return false; // 编辑距离超阈值 → 不同源
                    }
                    firstUrl = line;
                }
            }
        }
        return true;
    }

    /**
     * 检查所有分片 URL 长度是否一致
     */
    private static boolean isAllUrlLengthSame(String[] blocks) {
        int urlLen = 0;
        for (String block : blocks) {
            for (String line : block.split("\n")) {
                if (line.startsWith("http")) {
                    if (urlLen <= 0) {
                        urlLen = line.length();
                    }
                    if (urlLen > 0 && line.length() != urlLen) {
                        return false;
                    }
                }
            }
        }
        return urlLen > 0;
    }

    /**
     * 两个 URL 是否同目录
     */
    private static boolean isSameDirectory(String url1, String url2) {
        if (TextUtils.isEmpty(url1) || TextUtils.isEmpty(url2)) {
            return false;
        }
        int slash1 = url1.lastIndexOf("/");
        int slash2 = url2.lastIndexOf("/");
        if (slash1 == -1 || slash2 == -1) {
            return url1.equals(url2);
        }
        return url1.substring(0, slash1).equals(url2.substring(0, slash2));
    }

    // ========================================================================
    // 辅助方法：Levenshtein 编辑距离
    // ========================================================================

    /**
     * 计算两个字符串的 Levenshtein 编辑距离
     */
    private static int levenshteinDistance(String s1, String s2) {
        if (s1 == null || s1.isEmpty()) {
            return s2 == null ? 0 : s2.length();
        }
        if (s2 == null || s2.isEmpty()) {
            return s1.length();
        }

        int[][] dp = new int[s2.length() + 1][s1.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[0][i] = i;
        }
        for (int i = 0; i <= s2.length(); i++) {
            dp[i][0] = i;
        }

        for (int i = 1; i <= s2.length(); i++) {
            for (int j = 1; j <= s1.length(); j++) {
                int cost = s1.charAt(j - 1) == s2.charAt(i - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i][j - 1] + 1, dp[i - 1][j] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[s2.length()][s1.length()];
    }

    // ========================================================================
    // 辅助方法：URL 处理
    // ========================================================================

    /**
     * 解析相对 URL 为绝对 URL
     */
    private static String resolveUrl(String baseUrl, String relativeUrl) {
        if (TextUtils.isEmpty(relativeUrl)) {
            return relativeUrl;
        }
        // 不是相对路径（不以 # 开头，或包含 URI=）
        if (!relativeUrl.startsWith("#") || !relativeUrl.contains("URI=")) {
            return resolveRelative(baseUrl, relativeUrl);
        }
        // 处理 #EXT-X-KEY:URI="..." 中的相对路径
        java.util.regex.Matcher matcher = URI_PATTERN.matcher(relativeUrl);
        if (matcher.find()) {
            String uri = matcher.group(1);
            if (uri != null) {
                return relativeUrl.replace(uri, resolveRelative(baseUrl, uri));
            }
        }
        return relativeUrl;
    }

    /**
     * 简单的相对 URL 解析
     */
    private static String resolveRelative(String baseUrl, String relativeUrl) {
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(relativeUrl)) {
            return relativeUrl;
        }
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        try {
            // 先去掉 baseUrl 的文件名部分，只保留目录路径
            String baseDir = baseUrl;
            int lastSlash = baseUrl.lastIndexOf("/");
            if (lastSlash > 8) { // 跳过 http:// 或 https:// 中的 /
                baseDir = baseUrl.substring(0, lastSlash + 1);
            }
            if (relativeUrl.startsWith("/")) {
                // 绝对路径：使用 protocol + host + relativeUrl
                Uri base = Uri.parse(baseUrl);
                return base.getScheme() + "://" + base.getHost() + relativeUrl;
            } else {
                // 相对路径：baseDir + relativeUrl
                return baseDir + relativeUrl;
            }
        } catch (Exception e) {
            return relativeUrl;
        }
    }

    /**
     * 判断是否需要处理的行（相对URL或包含URI=的标签）
     */
    private static boolean needsUrlResolution(String line) {
        if (TextUtils.isEmpty(line.trim())) {
            return false;
        }
        // 不以 # 开头且不以 http 开头 → 相对 URL
        if (!line.startsWith("#") && !line.startsWith("http")) {
            return true;
        }
        // 以 # 开头且包含 URI= → 需要解析
        return line.startsWith("#") && line.contains("URI=");
    }

    /**
     * 构建 HTTP 请求头（过滤掉不需要的头）
     */
    private static Headers buildHeaders(Map<String, String> headers) {
        Headers.Builder builder = new Headers.Builder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (!"remote-addr".equals(name) && !"http-client-ip".equals(name) && !"host".equals(name)) {
                builder.add(name, entry.getValue());
            }
        }
        builder.removeAll("accept-encoding");
        return builder.build();
    }

    // ========================================================================
    // 算法三：按广告时长前缀过滤
    // ========================================================================

    /**
     * 按广告时长前缀过滤广告块
     *
     * 原理：已知广告块的总时长前缀（如 "15"、"30"），
     * 遍历所有 DISCONTINUITY 块，计算每块总时长，
     * 如果时长字符串以指定前缀开头，则直接删除该块。
     *
     * @param content       m3u8 内容
     * @param durationPrefixes 广告时长前缀列表（如 ["15", "30", "45"]）
     * @return 过滤后的 m3u8
     */
    // 【2026-10-16 优化】常见广告时长列表（秒），参考行业标准广告时长
    private static final double[] COMMON_AD_DURATIONS = {
        5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 45.0, 60.0, 90.0, 120.0
    };
    private static final double DURATION_TOLERANCE = 1.0; // 时长容差（秒）

    /**
     * 【2026-10-16 优化】按广告时长前缀过滤
     * 优化点：
     * 1. 支持常见广告时长自动匹配（15s/30s/45s/60s/90s/120s）
     * 2. 支持时长容差匹配（±1秒）
     * 3. 结合 DISCONTINUITY 上下文判断
     * 4. 支持自定义时长前缀列表
     */
    public static String stripByDurationPrefix(String content, List<String> durationPrefixes) {
        if (TextUtils.isEmpty(content)) {
            return content;
        }

        // 构建时长匹配列表（自定义 + 常见广告时长）
        ArrayList<Double> matchDurations = new ArrayList<>();
        if (durationPrefixes != null) {
            for (String prefix : durationPrefixes) {
                try {
                    matchDurations.add(Double.parseDouble(prefix));
                } catch (NumberFormatException ignored) {}
            }
        }
        for (double d : COMMON_AD_DURATIONS) {
            matchDurations.add(d);
        }

        java.util.regex.Matcher matcher = DISCONTINUITY_BLOCK_PATTERN.matcher(content);
        int removedBlocks = 0;

        while (matcher.find()) {
            String block = matcher.group();
            BigDecimal totalDuration = BigDecimal.ZERO;
            int segCount = 0;

            java.util.regex.Matcher durationMatcher = EXTINF_DURATION_PATTERN.matcher(block);
            while (durationMatcher.find()) {
                totalDuration = totalDuration.add(new BigDecimal(durationMatcher.group(1)));
                segCount++;
            }

            // 跳过空块或分片数过多的块（可能是正片）
            if (segCount == 0 || segCount > 10) {
                continue;
            }

            double blockDuration = totalDuration.doubleValue();
            boolean isAdDuration = false;
            String matchedDuration = "";

            // 容差匹配
            for (double adDuration : matchDurations) {
                if (Math.abs(blockDuration - adDuration) <= DURATION_TOLERANCE) {
                    isAdDuration = true;
                    matchedDuration = String.valueOf(adDuration);
                    break;
                }
            }

            // 自定义前缀匹配（向后兼容）
            if (!isAdDuration && durationPrefixes != null) {
                String durationStr = totalDuration.toString();
                for (String prefix : durationPrefixes) {
                    if (durationStr.startsWith(prefix)) {
                        isAdDuration = true;
                        matchedDuration = prefix;
                        break;
                    }
                }
            }

            if (isAdDuration) {
                String blockWithoutEndlist = block.replace("#EXT-X-ENDLIST", "");
                content = content.replace(blockWithoutEndlist, "");
                removedBlocks++;
                LogBridge.d(TAG, "【算法④】按时长过滤广告块：时长=" + blockDuration +
                        "s, 匹配=" + matchedDuration + "s, 分片数=" + segCount);
            }
        }

        if (removedBlocks > 0) {
            LogBridge.i(TAG, "【算法④】按时长过滤广告块 " + removedBlocks + " 个");
        }
        return content;
    }

    // ========================================================================
    // 算法四：按广告列表过滤
    // ========================================================================

    /**
     * 按广告片段列表过滤
     *
     * 支持两种过滤方式：
     * 1. 片段包含 #EXT-X-DISCONTINUITY 或 #EXTINF → 直接 replaceAll 删除
     * 2. 片段是纯数字（时长）→ 转为时长前缀过滤
     *
     * @param content  m3u8 内容
     * @param adList   广告片段列表（可以是 m3u8 片段文本，也可以是时长字符串）
     * @return 过滤后的 m3u8
     */
    public static String stripByAdList(String content, List<String> adList) {
        if (TextUtils.isEmpty(content) || adList == null || adList.isEmpty()) {
            return content;
        }

        boolean hasDurationItem = false;

        for (String item : adList) {
            if (TextUtils.isEmpty(item)) {
                continue;
            }

            if (item.contains("#EXT-X-DISCONTINUITY") || item.contains("#EXTINF")) {
                content = content.replaceAll(item, "");
                LogBridge.d(TAG, "按广告列表过滤片段: " + item.substring(0, Math.min(50, item.length())));
            } else if (isValidDuration(item)) {
                hasDurationItem = true;
            }
        }

        if (hasDurationItem) {
            content = stripByDurationPrefix(content, adList);
        }

        return content;
    }

    /**
     * 判断字符串是否是有效的时长（可解析为 double 且 > 0）
     */
    private static boolean isValidDuration(String str) {
        try {
            return Double.parseDouble(str.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ========================================================================
    // 算法③：按广告关键词/URL 路径过滤
    // ========================================================================

    /**
     * 广告 URL 路径关键词列表
     */
    // 【2026-10-16 优化】扩展广告关键词库，参考 EasyList/anti-AD/AdRules 等开源广告拦截列表
    private static final String[] AD_URL_KEYWORDS = {
        // 标准广告路径
        "/ad/", "/ads/", "/advert/", "/advertisement/",
        "/adjump/", "/adbreak/", "/adslot/", "/adspot/",
        "/promo/", "/promotion/", "/sponsor/", "/sponsored/",
        "/commercial/", "/insert/", "/midroll/", "/preroll/",
        "/postroll/", "/vast/", "/vmap/", "/adserver/",
        "/tracking/", "/tracker/", "/pixel/", "/beacon/",
        // 广告技术平台
        "/doubleclick/", "/googlesyndication/", "/googletagservices/",
        "/googleadservices/", "/adnxs/", "/advertising.",
        "/pubmatic/", "/openx/", "/rubicon/", "/criteo/",
        "/taboola/", "/outbrain/", "/zedo/", "/exosrv/",
        // 赌博/博彩广告（澳门新葡京等）
        "801801", "xinpujing", "pujing", "macau888",
        "aomen", "duchang", "bocai", "macau", "casino",
        "bet365", "bet9", "ag88", "ky88", "gd88",
        "澳门", "新葡京", "博彩", "赌博", "赌场",
        // 常见广告域名关键词
        ".ads.", ".ad.", "-ads.", "-ad.",
        "adservice", "adsdk", "adsdk", "adconfig",
        "adcolony", "chartboost", "unityads", "vungle",
        "applovin", "ironsrc", "mopub", "admob",
        // SCTE-35 广告标记相关
        "cue-out", "cue-in", "scte35", "scte-35",
        "ad-marker", "admarker", "avail"
    };

    // SCTE-35 标准广告标记（HLS 中用于标识广告段）
    private static final String[] SCTE35_AD_TAGS = {
        "#EXT-X-CUE-OUT", "#EXT-X-CUE-IN",
        "#EXT-X-CUE-OUT-CONT", "#EXT-OATCLS-SCTE35",
        "#EXT-X-ASSET", "#EXT-X-DATERANGE"
    };

    /**
     * 【2026-10-16 优化】按广告关键词过滤分片 URL
     * 优化点：
     * 1. 扩展广告关键词库（参考 EasyList/anti-AD/AdRules）
     * 2. 添加 SCTE-35 标准广告标记检测（#EXT-X-CUE-OUT/IN）
     * 3. 支持广告块整体删除（CUE-OUT 到 CUE-IN 之间的所有分片）
     */
    public static String stripByAdKeyword(String content) {
        if (TextUtils.isEmpty(content)) {
            return content;
        }

        // 先检测 SCTE-35 广告标记
        boolean hasScte35 = false;
        for (String tag : SCTE35_AD_TAGS) {
            if (content.contains(tag)) {
                hasScte35 = true;
                LogBridge.i(TAG, "【算法③】检测到 SCTE-35 广告标记: " + tag);
                break;
            }
        }

        String[] lines = content.split("\n");
        StringBuilder result = new StringBuilder();
        int removedCount = 0;
        int i = 0;
        boolean inAdBreak = false; // SCTE-35 广告段标记

        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // SCTE-35 标记处理
            if (trimmed.startsWith("#EXT-X-CUE-OUT")) {
                inAdBreak = true;
                LogBridge.d(TAG, "【算法③】SCTE-35 广告段开始");
                i++;
                continue;
            }
            if (trimmed.startsWith("#EXT-X-CUE-IN")) {
                inAdBreak = false;
                LogBridge.d(TAG, "【算法③】SCTE-35 广告段结束");
                i++;
                continue;
            }
            // 在广告段内的所有内容都跳过
            if (inAdBreak) {
                if (trimmed.startsWith("#EXTINF:")) {
                    removedCount++;
                }
                i++;
                continue;
            }

            // 关键词匹配
            if (trimmed.startsWith("#EXTINF:") && i + 1 < lines.length) {
                String urlLine = lines[i + 1].trim();
                boolean isAd = false;
                for (String keyword : AD_URL_KEYWORDS) {
                    if (urlLine.toLowerCase().contains(keyword.toLowerCase())) {
                        isAd = true;
                        // LogBridge.d(TAG, "【算法③】按关键词过滤广告分片: " + keyword + " -> " +
                        //         urlLine.substring(0, Math.min(60, urlLine.length())));
                        break;
                    }
                }
                if (isAd) {
                    removedCount++;
                    i += 2;
                    // 跳过后续的 DISCONTINUITY 标记
                    while (i < lines.length && lines[i].trim().startsWith("#EXT-X-DISCONTINUITY")) {
                        i++;
                    }
                    continue;
                }
            }

            result.append(line).append("\n");
            i++;
        }

        if (removedCount > 0) {
            LogBridge.i(TAG, "【算法③】按关键词/SCTE-35过滤广告分片 " + removedCount + " 个");
        }
        return result.toString();
    }

    /**
     * 检查 URL 是否包含广告关键词
     */
    public static boolean isAdUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase();
        for (String keyword : AD_URL_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测分片URL中的文件名是否连续递增
     * 正常视频文件名通常是连续的（如 0001.ts, 0002.ts）
     * 广告块文件名通常不连续或格式不同
     */
    private static boolean isFilenamesContinuous(ArrayList<String> urls) {
        if (urls == null || urls.size() < 2) return true;
        try {
            ArrayList<Long> nums = new ArrayList<>();
            for (String url : urls) {
                String fileName = url;
                int slash = url.lastIndexOf("/");
                if (slash >= 0) fileName = url.substring(slash + 1);
                int dot = fileName.lastIndexOf(".");
                if (dot > 0) fileName = fileName.substring(0, dot);
                StringBuilder numStr = new StringBuilder();
                for (char c : fileName.toCharArray()) {
                    if (c >= '0' && c <= '9') numStr.append(c);
                    else if (numStr.length() > 0) break;
                }
                if (numStr.length() > 0) nums.add(Long.parseLong(numStr.toString()));
            }
            if (nums.size() < 2) return true;
            for (int i = 1; i < nums.size(); i++) {
                if (nums.get(i) - nums.get(i - 1) != 1) return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
