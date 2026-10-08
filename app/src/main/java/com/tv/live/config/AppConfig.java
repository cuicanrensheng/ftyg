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
}
