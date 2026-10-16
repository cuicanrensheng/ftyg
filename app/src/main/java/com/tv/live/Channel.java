package com.tv.live;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

public class Channel implements Parcelable {
    private String name;

    private String mainPlayUrl;

    private List<String> backupUrls;

    private List<String> huyaLineLabels;
    private String group;
    private String channelId;

    private int currentLineIndex = 0;

    private boolean isTogetherWatch = false;
    private int huyaRoomId = 0;

    private boolean isHuyaSdkTogetherWatch = false;

    private long huyaUid = 0;

    /** 【2026-09-29 频道台标】频道台标 URL（来自 M3U 的 tvg-logo 或畅享 tvLogo），可能为空 */
    private String logoUrl;

    public Channel(String name, String mainPlayUrl, String group, String channelId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
        this.huyaLineLabels = new ArrayList<>();
    }

    public Channel(String name, String mainPlayUrl, String group, String channelId,
                   boolean isTogetherWatch, int huyaRoomId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
        this.huyaLineLabels = new ArrayList<>();
        this.isTogetherWatch = isTogetherWatch;
        this.huyaRoomId = huyaRoomId;
    }

    public void addBackupUrl(String url) {
        if (url != null && !backupUrls.contains(url)) {
            backupUrls.add(url);
        }
    }

    public void addBackupUrl(String url, String lineLabel) {
        if (url != null && !backupUrls.contains(url)) {
            backupUrls.add(url);
            huyaLineLabels.add(lineLabel != null ? lineLabel : "");
        }
    }

    public List<String> getHuyaLineLabels() {
        return huyaLineLabels;
    }

    public void setHuyaLineLabels(List<String> labels) {
        this.huyaLineLabels = labels != null ? labels : new ArrayList<>();
    }

    public void clearBackupUrls() {
        backupUrls.clear();
        huyaLineLabels.clear();
    }

    public String getPlayUrl() {
        if (currentLineIndex > 0 && currentLineIndex - 1 < backupUrls.size()) {
            return backupUrls.get(currentLineIndex - 1);
        }
        return mainPlayUrl;
    }

    public void setCurrentLineIndex(int index) {
        this.currentLineIndex = index;
    }

    public int getCurrentLineIndex() {
        return currentLineIndex;
    }

    public String getMainPlayUrl() {
        return mainPlayUrl;
    }

    public void setMainPlayUrl(String mainPlayUrl) {
        this.mainPlayUrl = mainPlayUrl;
    }

    public List<String> getBackupUrls() {
        return backupUrls;
    }

    public String getName() {
        return name;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    /** 【2026-09-29 频道台标】 */
    public String getLogoUrl() {
        return logoUrl;
    }

    /** 【2026-09-29 频道台标】 */
    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getChannelId() {
        return channelId;
    }

    public boolean isTogetherWatch() {
        return isTogetherWatch;
    }

    public void setTogetherWatch(boolean togetherWatch) {
        isTogetherWatch = togetherWatch;
    }

    public int getHuyaRoomId() {
        return huyaRoomId;
    }

    public void setHuyaRoomId(int huyaRoomId) {
        this.huyaRoomId = huyaRoomId;
    }

    public boolean isHuyaSdkTogetherWatch() {
        return isHuyaSdkTogetherWatch;
    }

    public void setHuyaSdkTogetherWatch(boolean v) {
        isHuyaSdkTogetherWatch = v;
    }

    public long getHuyaUid() {
        return huyaUid;
    }

    public void setHuyaUid(long huyaUid) {
        this.huyaUid = huyaUid;
    }

    protected Channel(Parcel in) {
        name = in.readString();
        mainPlayUrl = in.readString();
        backupUrls = in.createStringArrayList();
        huyaLineLabels = in.createStringArrayList();
        group = in.readString();
        channelId = in.readString();
        currentLineIndex = in.readInt();
        isTogetherWatch = in.readByte() != 0;
        huyaRoomId = in.readInt();
        isHuyaSdkTogetherWatch = in.readByte() != 0;
        huyaUid = in.readLong();
        logoUrl = in.readString();   // 【2026-09-29 频道台标】顺序须与 writeToParcel 末尾一致
    }

    public static final Creator<Channel> CREATOR = new Creator<Channel>() {
        @Override
        public Channel createFromParcel(Parcel in) {
            return new Channel(in);
        }

        @Override
        public Channel[] newArray(int size) {
            return new Channel[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(mainPlayUrl);
        dest.writeStringList(backupUrls);
        dest.writeStringList(huyaLineLabels);
        dest.writeString(group);
        dest.writeString(channelId);
        dest.writeInt(currentLineIndex);
        dest.writeByte((byte) (isTogetherWatch ? 1 : 0));
        dest.writeInt(huyaRoomId);
        dest.writeByte((byte) (isHuyaSdkTogetherWatch ? 1 : 0));
        dest.writeLong(huyaUid);
        dest.writeString(logoUrl);   // 【2026-09-29 频道台标】顺序须与 Channel(Parcel) 末尾一致
    }

    public static class EpgItem {
        public String dayName;
        public String time;
        public String title;
        public boolean isPlaying;

        public int dateYMD;

        /**
         * 【2026-10-08】数据来源标记：咪咕轮播节目单（{@code CarouselApi} 的
         * {@code /p/get_current_carousel} 响应 {@code data[]}），而非 XMLTV 广播节目单。
         * 用于 EPG 面板区分「可回看」的点击行为。
         */
        public boolean fromMiguCarousel;

        /** 【2026-10-08】咪咕轮播节目 id（= 回看 {@code /p/get_carousel_video?pid=}）；空=非咪咕节目 */
        public String miguProgramId;

        /** 【2026-10-08】咪咕轮播频道 tvId；0=非咪咕节目 */
        public int miguTvId;

        /** 【2026-10-08】节目时段起点（Unix 秒）；0=未知 */
        public long startTs;

        /** 【2026-10-08】节目时段终点（Unix 秒）；0=未知 */
        public long endTs;

        /**
         * 【2026-10-10】内置源7（afhgrj 影视点播）用：该「集」对应的可直接播放的
         * 待解析地址（{@code afhgrj://<vodId>-<sid>-<nid>}）。非空时，EPG 面板点击该项
         * 直接播放此地址（起播时再由 AfhgrjUrlResolver 惰性换成真实 m3u8）。
         * 其它内置源为 {@code null}，行为完全不受影响。
         */
        public String customPlayUrl;

        public EpgItem(String dayName, String time, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
            this.dateYMD = 0;
        }

        public EpgItem(String dayName, String time, String title, boolean isPlaying, int dateYMD) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
            this.dateYMD = dateYMD;
        }

        public String getReplayUrl() {
            return null;
        }
    }
}
