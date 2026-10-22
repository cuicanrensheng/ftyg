package com.tv.live.huya;

/**
 * 虎牙房间列表条目（自研数据类）。
 *
 * <p>字段名与语义 1:1 对齐（public 字段直填直读），两条虎牙一起看线路
 * （{@link com.tv.live.manager.HuyaTogetherWatchGroupManager} 自研 HTTP 线路 与
 * {@link com.tv.live.manager.HuyaTogetherWatchManager} 第一路 HTTP 线路）
 * 的取数、过滤、转 {@code Channel} 全部走本类。</p>
 */
public class HuyaLiveItem {
    /** 房间号（虎牙 roomId，也即 sid） */
    public long channelId;
    /** 子频道 id（自研 HTTP 线路恒为 0） */
    public long subId;
    /** 主播 uid（自研 HTTP 线路保持 0 → 下游生成 huya://room/&lt;roomId&gt; 走自研解析器） */
    public long uid;
    /** 房间标题 */
    public String title;
    /** 主播昵称 */
    public String nickName;
    /** 封面图 URL */
    public String coverUrl;
    /** 游戏分类 id（gid） */
    public int gameId;
    /** 的人气值（万/原始值，仅展示用；自研 HTTP 线路可能不填） */
    public long audienceCount;

    @Override
    public String toString() {
        return "HuyaLiveItem{room=" + channelId + ", uid=" + uid
                + ", title=" + title + ", nick=" + nickName + "}";
    }
}
