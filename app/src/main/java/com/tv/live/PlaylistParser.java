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
                m3uName = ""; m3uTvgId = ""; m3uGroup = "";
                continue;
            }

            int diypComma = findFirstHttpComma(line);
            if (diypComma > 0) {
                String diypName = line.substring(0, diypComma).trim();
                String diypUri = line.substring(diypComma + 1).trim();
                if (!diypName.isEmpty() && diypUri.startsWith("http")) {
                    addOrMergeChannel(channelMap, diypName, diypUri, currentGroup, "");
                    pendingM3uUri = false;
                    m3uName = ""; m3uTvgId = ""; m3uGroup = "";
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
                if (line.contains(",")) {
                    m3uName = line.substring(line.indexOf(",") + 1).trim();
                }
                pendingM3uUri = true;
                continue;
            }

            if (!line.startsWith("#") && pendingM3uUri) {
                String uri = line;
                if (uri.startsWith("http")) {
                    String key = !m3uTvgId.isEmpty() ? m3uTvgId : m3uName;
                    if (!key.isEmpty()) {
                        addOrMergeChannel(channelMap, m3uName, uri, m3uGroup, m3uTvgId);
                    }
                }
                pendingM3uUri = false;
                m3uName = ""; m3uTvgId = ""; m3uGroup = "";
                continue;
            }

            if (!line.startsWith("#") && line.startsWith("http")) {
                addOrMergeChannel(channelMap, line, line, currentGroup, "");
            }
        }
        br.close();
        return new ArrayList<>(channelMap.values());
    }

    private static void addOrMergeChannel(Map<String, Channel> channelMap,
                                          String name, String uri,
                                          String group, String tvgId) {
        if (name == null || name.isEmpty() || uri == null || !uri.startsWith("http")) return;
        String key = (tvgId != null && !tvgId.isEmpty()) ? tvgId : name;
        if (key.isEmpty()) return;

        Channel existing = channelMap.get(key);
        if (existing != null) {
            existing.addBackupUrl(uri);
            if (group != null && !group.isEmpty()) {
                existing.setGroup(group);
            }
        } else {
            Channel newChannel = new Channel(name, uri, group, tvgId);
            channelMap.put(key, newChannel);
        }
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
