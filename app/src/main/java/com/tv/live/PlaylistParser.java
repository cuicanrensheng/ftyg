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

    private static List<Channel> parseInternal(BufferedReader br, Map<String, Channel> channelMap) throws Exception {
        String line;
        String currentGroup = "未分类";

        String m3uName = "";
        String m3uTvgId = "";
        String m3uGroup = "";
        String m3uLogo = "";   // 【2026-09-29 频道台标】当前 #EXTINF 的 tvg-logo
        boolean pendingM3uUri = false;

        while ((line = br.readLine()) != null) {
            line = line.trim();
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
     * </ul>
     */
    public static boolean isSupportedUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        return uri.startsWith("http")
                || uri.startsWith(com.tv.live.migu.MiguUrlResolver.SCHEME)
                || uri.startsWith(com.tv.live.migu.MiguSourceLoader.SCHEME_NOWTV)
                || uri.startsWith(com.tv.live.renyibo.RenYiBoSourceLoader.SCHEME)
                || uri.startsWith(com.tv.live.renyibo.RenYiBoVodSourceLoader.SCHEME)
                || uri.startsWith(com.tv.live.afhgrj.AfhgrjUrlResolver.SCHEME);
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
