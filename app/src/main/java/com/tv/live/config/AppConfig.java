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

    public String getScreenRatio() {
        return appSp.getString("screen_ratio", "全屏");
    }

    public int getLastPlayIndex() {
        return playSp.getInt("last_play_index", 0);
    }

    public void setLastPlayIndex(int index) {
        playSp.edit().putInt("last_play_index", index).apply();
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
