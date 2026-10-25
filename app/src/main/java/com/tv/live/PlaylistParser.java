package com.tv.live;

import com.tv.live.util.NetUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Response;

public class PlaylistParser {

    public static List<Channel> parse(String url) throws Exception {
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        try (Response response = NetUtil.getInstance().syncGet(url)) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("请求失败 code=" + response.code());
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(response.body().byteStream()));
            return parseInternal(br, channelMap);
        }
    }

    public static List<Channel> parseContent(String content) throws Exception {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        BufferedReader br = new BufferedReader(new StringReader(content));
        return parseInternal(br, channelMap);
    }

    /**
     * 【历史遗留，2026-10-22 标注】不去重版本的 M3U 解析入口。
     *
     * <p>本方法为旧 4kvm 时代「源8 片单分组含大量重复视频、用户要求是多少视频就显示多少
     * 视频」的特殊承诺而写。<b>现行口径已变更</b>：源8 已换 renyibo，M3U 由构建端按 slug
     * 去重、tvg-id 天然唯一（见 AppCoreManager#parseLiveSource 硬编码 noDedup=false），
     * 不再需要不去重特殊路径。全工程已无调用方，保留仅供追溯备查，请勿在新代码中调用。
     *
     * @param content M3U 文本
     * @return 频道列表（包含重复条目）
     * @deprecated 现行口径为构建端去重，不再使用
     */
    @Deprecated
    public static List<Channel> parseContentNoDedup(String content) throws Exception {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        List<Channel> channels = new ArrayList<>();
        BufferedReader br = new BufferedReader(new StringReader(content));
        return parseInternalNoDedup(br, channels);
    }

    /**
     * 剥离行首 BOM（U+FEFF）。
     *
     * <p>内置「央视」片段（{@code assets/cctv_group.m3u}）若带 UTF-8 BOM，拼接进直播源后
     * 该片段首行会变成 {@code "\uFEFF#EXTINF…"}；{@link String#trim()} 不会去掉 \uFEFF，
     * 于是这一行被当成「普通行」丢弃 —— 紧随其后的地址行沦为空有 URL 的野频道
     * （名字=URL、无台标、group=未分类）。实测：源7/源8 的「央视」分组里 CCTV1 就这么丢的。
     *
     * <p>在解析入口统一剥离，兼容内置资产 / {@code live_source} 缓存 / 永久兜底三种来源，
     * 使内置源与网络源（源1，无 BOM）解析口径一致。
     */
    private static String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1).trim();
        }
        return line;
    }

    private static List<Channel> parseInternal(BufferedReader br, Map<String, Channel> channelMap) throws Exception {
        String line;
        String currentGroup = "未分类";

        String m3uName = "";
        String m3uTvgId = "";
        String m3uGroup = "";
        String m3uLogo = "";   // 【2026-09-29 频道台标】当前 #EXTINF 的 tvg-logo
        boolean pendingM3uUri = false;

        while ((line = br.readLine()) != null) {
            line = stripBom(line.trim());
            if (line.isEmpty()) continue;

            if (line.endsWith(",#genre#") || line.endsWith("#genre#")) {

                String group = line;
                if (group.endsWith(",#genre#")) {
                    group = group.substring(0, group.length() - ",#genre#".length()).trim();
                } else if (group.endsWith("#genre#")) {
                    int idx = group.lastIndexOf("#genre#");
                    if (idx > 0) group = group.substring(0, idx).trim();
                    if (group.endsWith(",")) group = group.substring(0, group.length() - 1).trim();
                }
                if (!group.isEmpty()) currentGroup = group;

                pendingM3uUri = false;
                m3uName = ""; m3uTvgId = ""; m3uGroup = ""; m3uLogo = "";
                continue;
            }

            int diypComma = findFirstHttpComma(line);
            if (diypComma > 0) {
                String diypName = line.substring(0, diypComma).trim();
                String diypUri = line.substring(diypComma + 1).trim();
                if (!diypName.isEmpty() && diypUri.startsWith("http")) {
                    addOrMergeChannel(channelMap, diypName, diypUri, currentGroup, "", "");
                    pendingM3uUri = false;
                    m3uName = ""; m3uTvgId = ""; m3uGroup = ""; m3uLogo = "";
                    continue;
                }
            }

            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTGRP:")) {
                currentGroup = line.substring(8).trim();
                continue;
            }

            if (line.startsWith("#EXTINF:")) {
                m3uName = "";
                m3uTvgId = "";
                m3uGroup = currentGroup;
                m3uLogo = "";   // 【2026-09-29 频道台标】

                if (line.contains("tvg-id=\"")) {
                    try {
                        m3uTvgId = line.split("tvg-id=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                if (line.contains("group-title=\"")) {
                    try {
                        m3uGroup = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 【2026-09-29 频道台标】解析 tvg-logo
                if (line.contains("tvg-logo=\"")) {
                    try { m3uLogo = line.split("tvg-logo=\"")[1].split("\"")[0].trim(); } catch (Exception ignored) {}
                }
                if (line.contains(",")) {
                    m3uName = line.substring(line.indexOf(",") + 1).trim();
                }
                pendingM3uUri = true;
                continue;
            }

            if (!line.startsWith("#") && pendingM3uUri) {
                String uri = line;
                if (isSupportedUri(uri)) {
                    String key = !m3uTvgId.isEmpty() ? m3uTvgId : m3uName;
                    if (!key.isEmpty()) {
                        addOrMergeChannel(channelMap, m3uName, uri, m3uGroup, m3uTvgId, m3uLogo);
                    }
                }
                pendingM3uUri = false;
                m3uName = ""; m3uTvgId = ""; m3uGroup = ""; m3uLogo = "";
                continue;
            }

            if (!line.startsWith("#") && isSupportedUri(line)) {
                addOrMergeChannel(channelMap, line, line, currentGroup, "", "");
            }
        }
        br.close();
        return new ArrayList<>(channelMap.values());
    }

    /**
     * 不去重版本的 M3U 解析（历史遗留，见 {@link #parseContentNoDedup} 的口径说明）。
     * 相同 tvgId/name 的条目不会合并，每条都作为独立频道保留。
     */
    private static List<Channel> parseInternalNoDedup(BufferedReader br, List<Channel> channels) throws Exception {
        String line;
        String currentGroup = "未分类";

        String m3uName = "";
        String m3uTvgId = "";
        String m3uGroup = "";
        String m3uLogo = "";
        boolean pendingM3uUri = false;

        while ((line = br.readLine()) != null) {
            line = stripBom(line.trim());
            if (line.isEmpty()) continue;

            if (line.endsWith(",#genre#") || line.endsWith("#genre#")) {
                String group = line;
                if (group.endsWith(",#genre#")) {
                    group = group.substring(0, group.length() - ",#genre#".length()).trim();
                } else if (group.endsWith("#genre#")) {
                    int idx = group.lastIndexOf("#genre#");
                    if (idx > 0) group = group.substring(0, idx).trim();
                    if (group.endsWith(",")) group = group.substring(0, group.length() - 1).trim();
                }
                if (!group.isEmpty()) currentGroup = group;
                pendingM3uUri = false;
                m3uName = ""; m3uTvgId = ""; m3uGroup = ""; m3uLogo = "";
                continue;
            }

            int diypComma = findFirstHttpComma(line);
            if (diypComma > 0) {
                String diypName = line.substring(0, diypComma).trim();
                String diypUri = line.substring(diypComma + 1).trim();
                if (!diypName.isEmpty() && diypUri.startsWith("http")) {
                    channels.add(new Channel(diypName, diypUri, currentGroup, ""));
                    pendingM3uUri = false;
                    m3uName = ""; m3uTvgId = ""; m3uGroup = ""; m3uLogo = "";
                    continue;
                }
            }

            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTGRP:")) {
                currentGroup = line.substring(8).trim();
                continue;
            }

            if (line.startsWith("#EXTINF:")) {
                m3uName = "";
                m3uTvgId = "";
                m3uGroup = currentGroup;
                m3uLogo = "";

                if (line.contains("tvg-id=\"")) {
                    try {
                        m3uTvgId = line.split("tvg-id=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                if (line.contains("group-title=\"")) {
                    try {
                        m3uGroup = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                if (line.contains("tvg-logo=\"")) {
                    try { m3uLogo = line.split("tvg-logo=\"")[1].split("\"")[0].trim(); } catch (Exception ignored) {}
                }
                if (line.contains(",")) {
                    m3uName = line.substring(line.indexOf(",") + 1).trim();
                }
                pendingM3uUri = true;
                continue;
            }

            if (!line.startsWith("#") && pendingM3uUri) {
                String uri = line;
                if (isSupportedUri(uri)) {
                    Channel ch = new Channel(m3uName, uri, m3uGroup, m3uTvgId);
                    if (m3uLogo != null && !m3uLogo.isEmpty()) {
                        ch.setLogoUrl(m3uLogo);
                    }
                    channels.add(ch);
                }
                pendingM3uUri = false;
                m3uName = ""; m3uTvgId = ""; m3uGroup = ""; m3uLogo = "";
                continue;
            }

            if (!line.startsWith("#") && isSupportedUri(line)) {
                channels.add(new Channel(line, line, currentGroup, ""));
            }
        }
        br.close();
        return channels;
    }

    private static void addOrMergeChannel(Map<String, Channel> channelMap,
                                          String name, String uri,
                                          String group, String tvgId, String logo) {
        if (name == null || name.isEmpty() || !isSupportedUri(uri)) return;
        String key = (tvgId != null && !tvgId.isEmpty()) ? tvgId : name;
        if (key.isEmpty()) return;

        Channel existing = channelMap.get(key);
        if (existing != null) {
            existing.addBackupUrl(uri);
            if (group != null && !group.isEmpty()) {
                existing.setGroup(group);
            }
            // 【2026-09-29 频道台标】命中既有频道时，若其 logo 为空则补上
            if (logo != null && !logo.isEmpty()
                    && (existing.getLogoUrl() == null || existing.getLogoUrl().isEmpty())) {
                existing.setLogoUrl(logo);
            }
        } else {
            Channel newChannel = new Channel(name, uri, group, tvgId);
            // 【2026-09-29 频道台标】
            if (logo != null && !logo.isEmpty()) {
                newChannel.setLogoUrl(logo);
            }
            channelMap.put(key, newChannel);
        }
    }

    /**
     * 支持的播放地址协议：标准 http(s)，以及内置源的自定义协议。
     *
     * <p>自定义协议：
     * <ul>
     *   <li>内置源6（咪咕）{@code miguhd://<pID>} / {@code nowtv://<tvId>} —— 待解析地址，
     *       由 {@code MiguUrlResolver} 在起播那一刻实时换成带签名的 m3u8；</li>
     *   <li>内置源5（爱其意）{@code renyibo://<apiId>}（2026-10-07 新增）—— 同为待解析地址，
     *       起播时经 live.php + 302 解析落地（惰性解析，参照咪咕模式）。</li>
     *   <li>内置源5 点播（爱其意影视）{@code renyibo-vod://<typeId>:<vodId>}（2026-10-12 新增）
     *       —— 同为待解析协议串；本期播放地址待加密接口补齐，解析返回 null。</li>
     *   <li>内置源7（afhgrj 影视）{@code afhgrj://<vodId>}（2026-10-10 新增）—— 待解析地址，
     *       起播时抓播放页 player_aaaa 取真实 m3u8（惰性解析）。</li>
     *   <li>内置源8（renyibo 影视）{@code ryb://<slug>}（2026-10-19 换源）—— 待解析地址，
     *       起播时抓详情页 PLAY_SOURCES 取真实 m3u8（惰性解析，纯 HTTP）。</li>
     *   <li>内置源2 追加的 4K影视 {@code nbm://<vodId>}（2026-10-21 新增）—— 待解析地址，
     *       起播时走 NM 加密帧通道（X25519 换密钥 + AES-256-GCM）取真实 m3u8（惰性解析）。</li>
     * </ul>
     */
    public static boolean isSupportedUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        return uri.startsWith("http")
                || uri.startsWith(com.tv.live.migu.MiguUrlResolver.SCHEME)
                || uri.startsWith(com.tv.live.migu.MiguSourceLoader.SCHEME_NOWTV)
                || uri.startsWith(com.tv.live.renyibo.RenYiBoSourceLoader.SCHEME)
                || uri.startsWith(com.tv.live.renyibo.RenYiBoVodSourceLoader.SCHEME)
                || uri.startsWith(com.tv.live.afhgrj.AfhgrjUrlResolver.SCHEME)
                || uri.startsWith(com.tv.live.ryb.RybUrlResolver.SCHEME)
                || uri.startsWith(com.tv.live.nbmovie.NbMovieUrlResolver.SCHEME)
                // 【2026-10-23 源9 橘汁】juzhi://<vodId> 待解析地址。
                //   白名单漏项时 M3U 里所有 juzhi:// 行会被丢弃 → 「解析后频道数=0」。
                || uri.startsWith(com.tv.live.juzhi.JzProtocol.SCHEME);
    }

    private static int findFirstHttpComma(String line) {
        if (line == null || line.isEmpty()) return -1;
        int httpIdx = line.indexOf("http://");
        if (httpIdx < 0) httpIdx = line.indexOf("https://");
        if (httpIdx <= 1) return -1;

        int comma = line.lastIndexOf(',', httpIdx - 1);
        if (comma <= 0) return -1;
        return comma;
    }
}
