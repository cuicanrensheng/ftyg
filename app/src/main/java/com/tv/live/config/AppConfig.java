package com.tv.live.config;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private static AppConfig instance;
    private final SharedPreferences appSp;
    private final SharedPreferences playSp;

    private static final String SEPARATOR = "|||";
    private static final String SEPARATOR_REGEX = "\\|\\|\\|";

    private AppConfig(Context context) {
        appSp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        playSp = context.getSharedPreferences("play_config", Context.MODE_PRIVATE);
    }

    public static AppConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppConfig(context.getApplicationContext());
        }
        return instance;
    }

    public String getCustomLiveUrl() {
        return appSp.getString("custom_live_url", null);
    }

    public String getCustomEpgUrl() {
        return appSp.getString("custom_epg_url", null);
    }

    public void setCustomUrls(String liveUrl, String epgUrl) {
        SharedPreferences.Editor editor = appSp.edit();
        if (liveUrl != null) editor.putString("custom_live_url", liveUrl);
        if (epgUrl != null) editor.putString("custom_epg_url", epgUrl);
        editor.apply();
    }

    public boolean isChannelReverse() {
        return appSp.getBoolean("channel_reverse", false);
    }

    /** 源模式：直播模式，启动直进播放（默认，保持原有行为） */
    public static final int SOURCE_MODE_LIVE = 0;
    /** 源模式：OTT 模式，启动先进入 OTT 首页（分类 + 频道网格 + 播放预览） */
    public static final int SOURCE_MODE_OTT = 1;

    public int getSourceMode() {
        return appSp.getInt("source_mode", SOURCE_MODE_LIVE);
    }

    public void setSourceMode(int mode) {
        appSp.edit().putInt("source_mode", mode).apply();
    }

    public String getScreenRatio() {
        return appSp.getString("screen_ratio", "全屏");
    }

    public int getLastPlayIndex() {
        return playSp.getInt("last_play_index", 0);
    }

    public void setLastPlayIndex(int index) {
        playSp.edit().putInt("last_play_index", index).apply();
    }

    /** 【2026-09-29 OTT 起播】OTT 首页专用的"上次播放"记录。
     *  与竖屏版 last_play_index 分开存，避免两边语义互踩；
     *  默认 -1 表示"尚无记录"，这样才能与"记录恰好是第 0 项"区分开。 */
    public int getOttLastPlayIndex() {
        return playSp.getInt("ott_last_play_index", -1);
    }

    public void setOttLastPlayIndex(int index) {
        playSp.edit().putInt("ott_last_play_index", index).apply();
    }

    /** 【2026-09-29】数字键输号跳台开关（与直播模式 MainActivity:1103 同一把键，默认开） */
    public boolean isNumberChannelEnable() {
        return appSp.getBoolean("number_channel_enable", true);
    }

    public int getCurrentRatioIndex() {
        return playSp.getInt("play_ratio", 2);
    }

    private static final String KEY_RECENT_CHANNELS = "recent_channels";
    private static final int MAX_RECENT_COUNT = 10;

    public List<String> getRecentChannels() {
        String saved = appSp.getString(KEY_RECENT_CHANNELS, "");
        List<String> list = new ArrayList<>();
        if (saved.isEmpty()) return list;
        String[] names = saved.split(SEPARATOR_REGEX);
        for (String name : names) {
            if (!name.isEmpty()) {
                list.add(name);
            }
        }
        return list;
    }

    public void addRecentChannel(String channelName) {
        List<String> recent = getRecentChannels();

        recent.remove(channelName);

        recent.add(0, channelName);

        while (recent.size() > MAX_RECENT_COUNT) {
            recent.remove(recent.size() - 1);
        }
        saveRecent(recent);
    }

    private void saveRecent(List<String> recent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(recent.get(i));
        }
        appSp.edit().putString(KEY_RECENT_CHANNELS, sb.toString()).apply();
    }

    // ==================================================================
    // 【2026-10-11 新增】OTT 播放历史（最多 10 条；每条带「看到第几集」）
    //
    // 一物两用：
    //   ① OTT 小窗口下方的「历史记录」面板直接渲染这 10 条（最新在前）；
    //   ② 源7 续播 —— 打开软件自动起播的频道若在历史里，按其记录集数续播那一集。
    // 存储：{@code 频道名 \t vodId \t 集数}，每条一行；同名去重（就地更新到最新集数）。
    // ==================================================================

    private static final String KEY_OTT_HISTORY = "ott_history";
    private static final int MAX_OTT_HISTORY = 10;
    private static final String HISTORY_FIELD = "\t";
    private static final String HISTORY_LINE = "\n";

    /** 一条 OTT 播放历史：频道名 + （源7）影片 id / 看到的集数（1 基；0 = 无集数概念） */
    public static class OttHistory {
        public final String name;
        public final String vodId;
        public final int episodeOrdinal;
        public final long timestamp;
        public final long positionMs;

        public OttHistory(String name, String vodId, int episodeOrdinal) {
            this(name, vodId, episodeOrdinal, System.currentTimeMillis(), 0);
        }

        public OttHistory(String name, String vodId, int episodeOrdinal, long timestamp) {
            this(name, vodId, episodeOrdinal, timestamp, 0);
        }

        public OttHistory(String name, String vodId, int episodeOrdinal, long timestamp, long positionMs) {
            this.name = name == null ? "" : name;
            this.vodId = vodId == null ? "" : vodId;
            this.episodeOrdinal = episodeOrdinal;
            this.timestamp = timestamp;
            this.positionMs = positionMs;
        }

        /** 展示文案：频道名 · 第N集 · 进度mm:ss */
        public String displayText() {
            String base = episodeOrdinal > 0 ? (name + " · 第" + episodeOrdinal + "集") : name;
            if (positionMs > 60000) {
                long sec = positionMs / 1000;
                base += " · " + String.format("%02d:%02d", sec / 60, sec % 60);
            }
            return base;
        }
    }

    public List<OttHistory> getOttHistory() {
        List<OttHistory> out = new ArrayList<>();
        String saved = appSp.getString(KEY_OTT_HISTORY, "");
        if (saved == null || saved.isEmpty()) return out;
        for (String line : saved.split(HISTORY_LINE)) {
            if (line == null || line.isEmpty()) continue;
            String[] f = line.split(HISTORY_FIELD, -1);
            if (f.length < 3 || f[0].isEmpty()) continue;
            // 【2026-10-11 成人屏蔽】播放历史是独立持久化清单，不经过 ChannelBlocklist.filter，
            // 若不过滤，已被剔除的成人频道仍会留在历史里、且可点击回看。在读取出口统一拦截：
            // 既不再显示，下一次 addOttHistory 写回时也会被永久清掉（自愈）。
            if (com.tv.live.util.ChannelBlocklist.isBlocked(f[0])) continue;
            int ord = 0;
            try {
                ord = Integer.parseInt(f[2].trim());
            } catch (Exception ignored) {
            }
            long ts = 0;
            if (f.length >= 4) {
                try { ts = Long.parseLong(f[3].trim()); } catch (Exception ignored) {}
            }
            long pos = 0;
            if (f.length >= 5) {
                try { pos = Long.parseLong(f[4].trim()); } catch (Exception ignored) {}
            }
            out.add(new OttHistory(f[0], f[1], ord, ts, pos));
        }
        return out;
    }

    /** 记一条播放历史：同名去重（就地更新集数）→ 置顶 → 超过 10 条挤掉最旧 */
    public void addOttHistory(String channelName, String vodId, int episodeOrdinal) {
        if (channelName == null || channelName.isEmpty()) return;
        List<OttHistory> list = getOttHistory();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (channelName.equals(list.get(i).name)) list.remove(i);
        }
        list.add(0, new OttHistory(channelName, vodId, episodeOrdinal));
        while (list.size() > MAX_OTT_HISTORY) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            OttHistory h = list.get(i);
            if (i > 0) sb.append(HISTORY_LINE);
            sb.append(h.name).append(HISTORY_FIELD).append(h.vodId)
                    .append(HISTORY_FIELD).append(h.episodeOrdinal)
                    .append(HISTORY_FIELD).append(h.timestamp)
                    .append(HISTORY_FIELD).append(h.positionMs);
        }
        appSp.edit().putString(KEY_OTT_HISTORY, sb.toString()).apply();
    }

    /** 更新某频道历史记录的播放进度（毫秒） */
    public void updateOttHistoryPosition(String channelName, long positionMs) {
        if (channelName == null || channelName.isEmpty()) return;
        List<OttHistory> list = getOttHistory();
        for (int i = 0; i < list.size(); i++) {
            OttHistory h = list.get(i);
            if (channelName.equals(h.name)) {
                list.set(i, new OttHistory(h.name, h.vodId, h.episodeOrdinal, h.timestamp, positionMs));
                // 写回
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < list.size(); j++) {
                    OttHistory hh = list.get(j);
                    if (j > 0) sb.append(HISTORY_LINE);
                    sb.append(hh.name).append(HISTORY_FIELD).append(hh.vodId)
                            .append(HISTORY_FIELD).append(hh.episodeOrdinal)
                            .append(HISTORY_FIELD).append(hh.timestamp)
                            .append(HISTORY_FIELD).append(hh.positionMs);
                }
                appSp.edit().putString(KEY_OTT_HISTORY, sb.toString()).apply();
                return;
            }
        }
    }

    /** 取某频道历史记录的播放进度（毫秒）；无记录返回 0 */
    public long getOttHistoryPosition(String channelName) {
        if (channelName == null || channelName.isEmpty()) return 0;
        for (OttHistory h : getOttHistory()) {
            if (channelName.equals(h.name)) return h.positionMs;
        }
        return 0;
    }

    /** 取某频道在历史里记录的集数（1 基）；无记录返回 0 */
    public int getOttHistoryOrdinal(String channelName) {
        if (channelName == null || channelName.isEmpty()) return 0;
        for (OttHistory h : getOttHistory()) {
            if (channelName.equals(h.name)) return h.episodeOrdinal;
        }
        return 0;
    }
}
