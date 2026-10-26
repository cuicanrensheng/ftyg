package com.tv.live.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.tv.live.util.LogBridge;

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
        String v = appSp.getString("custom_live_url", null);
        // 【2026-10-19 源8 移除】4kvm.top 全站 404，整源不可用。
        //   老用户若把「内置源8」选成了自定义源，这里就地清掉并回落到默认源，
        //   否则启动会一直卡在拉取一个死地址上。
        if (v != null && v.startsWith("kvm4://")) {
            appSp.edit().remove("custom_live_url").apply();
            LogBridge.i("AppConfig", "【源8 已移除】清除遗留自定义直播源: " + v);
            return null;
        }
        return v;
    }

    public String getCustomEpgUrl() {
        return appSp.getString("custom_epg_url", null);
    }

    // ==================== 【2026-10-26 缓存归属】直播源缓存绑定源标识 ====================
    // 背景：live_source 缓存不记所属源，而 AppCoreManager 有「7 天内跳过网络刷新」策略。
    //   "切源后加载还没完成就退出"会留下上一个源的缓存，之后最长 7 天冷却启动都显示旧源频道，
    //   与 SP 里选的源完全不一致（实测切回默认源1 仍加载源9 的 343 个频道）。
    // 做法：写缓存时打标当前源标识，冷启动读缓存前校验，不一致直接当无缓存走网络。
    private static final String KEY_LIVE_CACHE_SRC = "live_source_cache_src";

    /** 当前生效的直播源标识：自定义源优先，否则视为内置默认源 {@code default}。 */
    public String getLiveSourceKey() {
        String v;
        try {
            v = getCustomLiveUrl();
        } catch (Exception e) {
            v = null;
        }
        if (v == null || v.trim().isEmpty()) return "default";
        return v.trim();
    }

    /** 写缓存后打标：记录这份 live_source 缓存属于哪个源。 */
    public void markLiveSourceCacheOwner() {
        appSp.edit().putString(KEY_LIVE_CACHE_SRC, getLiveSourceKey()).apply();
    }

    /**
     * 缓存是否属于当前源。老版本写入的缓存没有标记（空串）→ 返回 false，
     * 让本次冷启动走一次网络刷新，代价可控且方向安全（宁多拉一次也不显示错源）。
     */
    public boolean isLiveSourceCacheOwnedByCurrent() {
        String owner = appSp.getString(KEY_LIVE_CACHE_SRC, "");
        String current = getLiveSourceKey();
        boolean same = current.equals(owner);
        if (!same) {
            LogBridge.i("AppConfig", "【缓存归属】不一致：缓存属于=" + (owner.isEmpty() ? "(未标记)" : owner)
                    + "，当前源=" + current + " → 本次不使用缓存");
        }
        return same;
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

    // ==================== 【2026-10-17 OTT 清晰度】 ====================

    /** 点播最高清晰度（视频高度 px）；0 = 自动（不封顶） */
    public int getVodMaxHeight() {
        return appSp.getInt("vod_max_height", 0);
    }

    public void setVodMaxHeight(int height) {
        appSp.edit().putInt("vod_max_height", height).apply();
    }

    // ==================== 【2026-10-17 收藏】频道/影片收藏 ====================

    private static final String KEY_FAVORITES = "fav_channels";

    public java.util.Set<String> getFavorites() {
        return new java.util.HashSet<>(appSp.getStringSet(KEY_FAVORITES,
                new java.util.HashSet<String>()));
    }

    public boolean isFavorite(String key) {
        return key != null && getFavorites().contains(key);
    }

    /** 切换收藏并返回切换后的状态（true=已收藏） */
    public boolean toggleFavorite(String key) {
        if (key == null) return false;
        java.util.Set<String> set = getFavorites();
        boolean added;
        if (set.contains(key)) {
            set.remove(key);
            added = false;
        } else {
            set.add(key);
            added = true;
        }
        appSp.edit().putStringSet(KEY_FAVORITES, set).apply();
        return added;
    }

    /** 记住用户上次选择的清晰度档位（用于菜单打勾） */
    public int getVodQualityChoice() {
        return appSp.getInt("vod_quality_choice", 0);
    }

    public void setVodQualityChoice(int height) {
        appSp.edit().putInt("vod_quality_choice", height).apply();
    }

    // ==================== 【2026-10-17 按源清晰度】 直播/点播各自独立记忆 ====================

    /** 按源记忆的清晰度键前缀：`vod_quality_src_<源号>`，0 = 自动（不封顶） */
    private static final String KEY_QUALITY_SRC = "vod_quality_src_";

    /**
     * 取「某个源」上次选择的清晰度封顶值。
     *
     * <p>源号口径见 OttHomeActivity#sourceIndexOf：1/2/3=普通直播，4=影视点播，
     * 5=renyibo（直播+点播双形态），6=咪咕，7=afhgrj 点播。
     * 每个源独立存一份，切源时才能显示/应用「该源自己的」档位。
     */
    public int getQualityForSource(int src) {
        if (src <= 0) return getVodQualityChoice();
        return appSp.getInt(KEY_QUALITY_SRC + src, 0);
    }

    /**
     * 记住「某个源」的清晰度选择。
     *
     * <p>同时把选择写回全局键（vod_quality_choice / vod_max_height），
     * 因为起播瞬间还没解析出源号时，播放器要先套用一个生效值。
     */
    public void setQualityForSource(int src, int height) {
        if (src > 0) appSp.edit().putInt(KEY_QUALITY_SRC + src, height).apply();
        setVodQualityChoice(height);
        setVodMaxHeight(height);
    }

    public String getScreenRatio() {
        return appSp.getString("screen_ratio", "全屏");
    }

    /** 【2026-10-18 OTT两级设置弹窗】屏幕比例写入（画面比例二级弹窗用），调用方负责 setScaleMode */
    public void setScreenRatio(String ratio) {
        appSp.edit().putString("screen_ratio", ratio).apply();
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
        /**
         * 【2026-10-17 广告剥离适配】保存进度时的**片长**。
         *
         * <p>点播走本地代理时会做广告剥离（剔除广告分片），时间轴因此被压缩。
         * 下次起播若剥离结果不同（缓存/阈值边界差异），同一个"秒数"会落到不同画面。
         * 恢复时用「当前片长 ÷ 保存时片长」做比例校正，即可消除这种漂移。
         * 0 = 未知（旧数据），不做校正。
         */
        public final long durationMs;

        public OttHistory(String name, String vodId, int episodeOrdinal) {
            this(name, vodId, episodeOrdinal, System.currentTimeMillis(), 0, 0);
        }

        public OttHistory(String name, String vodId, int episodeOrdinal, long timestamp) {
            this(name, vodId, episodeOrdinal, timestamp, 0, 0);
        }

        public OttHistory(String name, String vodId, int episodeOrdinal, long timestamp, long positionMs) {
            this(name, vodId, episodeOrdinal, timestamp, positionMs, 0);
        }

        public OttHistory(String name, String vodId, int episodeOrdinal, long timestamp,
                          long positionMs, long durationMs) {
            this.name = name == null ? "" : name;
            this.vodId = vodId == null ? "" : vodId;
            this.episodeOrdinal = episodeOrdinal;
            this.timestamp = timestamp;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
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
            // 【2026-10-17 用户口径】播放记录**只保留点播**：直播条目没有集数（ordinal=0），
            // 在读取出口统一过滤 —— 老版本残留的直播记录也不会再显示，
            // 并在下一次 addOttHistory 写回时被永久清掉（与上面的成人屏蔽同一套自愈逻辑）。
            if (ord <= 0) continue;
            long ts = 0;
            if (f.length >= 4) {
                try { ts = Long.parseLong(f[3].trim()); } catch (Exception ignored) {}
            }
            long pos = 0;
            if (f.length >= 5) {
                try { pos = Long.parseLong(f[4].trim()); } catch (Exception ignored) {}
            }
            long dur = 0;
            if (f.length >= 6) {
                try { dur = Long.parseLong(f[5].trim()); } catch (Exception ignored) {}
            }
            out.add(new OttHistory(f[0], f[1], ord, ts, pos, dur));
        }
        return out;
    }

    /** 记一条播放历史：同名去重（就地更新集数）→ 置顶 → 超过 10 条挤掉最旧 */
    public void addOttHistory(String channelName, String vodId, int episodeOrdinal) {
        addOttHistory(channelName, vodId, episodeOrdinal, 0);
    }

    /**
     * 【2026-10-17】带播放进度写入。
     *
     * <p>原实现新建记录时 {@code positionMs} 恒为 0 —— 而 `playChannel()` 每次起播都会调它，
     * 于是"已保存的位置"在起播瞬间就被清零；一旦起播后被杀/seek 失败，进度就永久丢了。
     * 起播路径现在把读到的 position 一起传进来，保证历史里的位置不被抹掉。
     *
     * @param positionMs 已知的播放位置；0 表示从头开始（切集时用 0 是正确的）
     */
    public void addOttHistory(String channelName, String vodId, int episodeOrdinal, long positionMs) {
        addOttHistory(channelName, vodId, episodeOrdinal, positionMs, 0);
    }

    /**
     * @param durationMs 保存时的片长；用于下次续播做「时间轴比例校正」（广告剥离会改变片长）
     */
    public void addOttHistory(String channelName, String vodId, int episodeOrdinal,
                              long positionMs, long durationMs) {
        if (channelName == null || channelName.isEmpty()) return;
        List<OttHistory> list = getOttHistory();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (channelName.equals(list.get(i).name)) list.remove(i);
        }
        list.add(0, new OttHistory(channelName, vodId, episodeOrdinal,
                System.currentTimeMillis(), Math.max(0, positionMs), Math.max(0, durationMs)));
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
                    .append(HISTORY_FIELD).append(h.positionMs)
                    .append(HISTORY_FIELD).append(h.durationMs);
        }
        appSp.edit().putString(KEY_OTT_HISTORY, sb.toString()).apply();
    }

    /** 更新某频道历史记录的播放进度（毫秒） */
    public void updateOttHistoryPosition(String channelName, long positionMs) {
        updateOttHistoryPosition(channelName, positionMs, 0);
    }

    /**
     * 更新播放进度，并记录当时的片长（广告剥离会改变片长，续播时据此做比例校正）。
     *
     * @param durationMs 0 = 保留记录里原来的片长
     */
    public void updateOttHistoryPosition(String channelName, long positionMs, long durationMs) {
        if (channelName == null || channelName.isEmpty()) return;
        List<OttHistory> list = getOttHistory();
        for (int i = 0; i < list.size(); i++) {
            OttHistory h = list.get(i);
            if (channelName.equals(h.name)) {
                long dur = (durationMs > 0) ? durationMs : h.durationMs;
                list.set(i, new OttHistory(h.name, h.vodId, h.episodeOrdinal, h.timestamp,
                        positionMs, dur));
                // 写回
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < list.size(); j++) {
                    OttHistory hh = list.get(j);
                    if (j > 0) sb.append(HISTORY_LINE);
                    sb.append(hh.name).append(HISTORY_FIELD).append(hh.vodId)
                            .append(HISTORY_FIELD).append(hh.episodeOrdinal)
                            .append(HISTORY_FIELD).append(hh.timestamp)
                            .append(HISTORY_FIELD).append(hh.positionMs)
                            .append(HISTORY_FIELD).append(hh.durationMs);
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

    /** 【2026-10-17】取保存进度时的片长（毫秒）；无记录/旧数据返回 0 */
    public long getOttHistoryDuration(String channelName) {
        if (channelName == null || channelName.isEmpty()) return 0;
        for (OttHistory h : getOttHistory()) {
            if (channelName.equals(h.name)) return h.durationMs;
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

    /**
     * 【2026-10-19】清空 OTT 播放历史。
     *
     * <p>切换直播源时调用：历史条目只存频道名（如源7/源8 的影片名），换源后这些影片
     * 在新源里既不存在也点不开，继续展示即为「切换源后仍显示上一个源的播放记录」的BUG。
     */
    public void clearOttHistory() {
        appSp.edit().remove(KEY_OTT_HISTORY).apply();
    }
}
