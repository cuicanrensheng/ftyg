package com.tv.live.renyibo;

import org.json.JSONObject;

/**
 * 内置源5（爱其意/任意播）影视点播（VOD）请求明文构造器。
 *
 * <p><b>来源</b>：请求明文结构逆向自爱其意运行时抓包，铁证格式：
 * <pre>{@code {"method":<dm_list|vod_heat_add|vod_detail|play>,
 *          "data":{<id|group_key>,"token":""},
 *          "csrf":<毫秒时间戳>}}</pre>
 *
 * <p><b>⛔ 本类不联网</b>，只生成 JSON 明文。加密与发送由
 * {@link RenYiBoVodSourceLoader} 在「默认关闭开关 + 格式锁定守卫」后方可执行。
 *
 * <p><b>⚠️ 历史未确证项（D5）</b>：分类列表（typeId）与 {@code vod_detail}/{@code play}
 * 的参数字段名曾无真实抓包样例。【2026-10-12 授权实测】已完成真机核对并锁定字段，
 * 原 {@code formatUnlocked()} 死闸门（无任何检查点）已于 2026-10-22 删除，见 S1-07。
 */
public final class VodRequest {

    private VodRequest() {}

    /** 首页推荐 / 分类分页（dm_list）。已确证：首页用 group_key 拉取分页。 */
    public static String dmList(String groupKey, int page) {
        JSONObject data = new JSONObject();
        put(data, "group_key", groupKey);
        put(data, "page", page);
        put(data, "token", "");
        return envelope("dm_list", data);
    }

    /**
     * 分类视频列表（按 type_id 分类）。
     * 【2026-10-12 单次在线实测锁定】参数形状；【2026-10-13】order 由「最新」改为
     * <b>「最热」</b>：A/B 实测「最新」返回冷门老片（1954/1995 年等），「最热」返回
     * 热播大片（哪吒2/抓娃娃/热辣滚烫…），六个点播分组统一走最热分支。
     */
    public static String videoList(int typeId, int page) {
        return videoList(typeId, page, "");
    }

    /** 【2026-10-14】带分组级 class 筛选的分类列表（如恐怖分组传 "恐怖"）。其余参数不变。 */
    public static String videoList(int typeId, int page, String classFilter) {
        JSONObject data = new JSONObject();
        put(data, "pg", page);
        put(data, "tid", typeId);
        put(data, "class", classFilter == null ? "" : classFilter);
        put(data, "area", "");
        put(data, "lang", "");
        put(data, "year", "");
        put(data, "order", "最热");
        put(data, "token", "");
        return envelope("video_list", data);
    }

    /** 视频详情（video_detail）。【2026-10-12 真机抓包定案】参数形状={id,sign_md5,apk_path,apk_length,token}。 */
    public static String videoDetail(String id) {
        JSONObject data = new JSONObject();
        put(data, "id", id);
        put(data, "sign_md5", "");
        // apk_path / apk_length 来自原 App 实抓，作为高概率可接受占位（详情以 id 为准）
        put(data, "apk_path",
                "/data/app/~~25Ure03G-AbkB36oysb-1g==/com.CDAStudio.ReinforcingBarAndroid.aqya-QpeQIP-h2-lqiNOoYJT0Cw==/base.apk");
        put(data, "apk_length", 65290025);
        put(data, "token", "");
        return envelope("video_detail", data);
    }

    /** 取流（play）。⚠️ 参数形状未确证，默认不启用。 */
    public static String play(String vodId, String epId) {
        JSONObject data = new JSONObject();
        put(data, "vod_id", vodId);
        put(data, "ep", epId);
        put(data, "token", "");
        return envelope("play", data);
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (org.json.JSONException e) {
            // 纯字符串/数字构造不会真正触发 JSONException，防御性吞掉
        }
    }

    private static String envelope(String method, JSONObject data) {
        JSONObject root = new JSONObject();
        put(root, "method", method);
        put(root, "data", data);
        put(root, "csrf", System.currentTimeMillis());
        return root.toString();
    }
}