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
 * <p><b>⚠️ 未确证项（D5）</b>：已确证 dm_list 首页/分页用 {@code group_key}；
 * 分类列表（typeId）与 {@code vod_detail}/{@code play} 的参数字段名
 * <b>没有真实抓包样例</b>。下列类别方法按最可能结构给出骨架，标注
 * {@code formatUnlocked()} 为 {@code false}，即<b>不会真正发送</b>，待授权一次
 * 真机请求后锁定字段再启用。
 */
public final class VodRequest {

    private VodRequest() {}

    /** 请求明文是否已锁定（授权真机核对后置 true）。置 false 则任何请求都不得发送。 */
    private static volatile boolean FORMAT_UNLOCKED = false;

    /** 仅供调试/迁移时输出；默认 false，保持红线。 */
    public static boolean formatUnlocked() {
        return FORMAT_UNLOCKED;
    }

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
        JSONObject data = new JSONObject();
        put(data, "pg", page);
        put(data, "tid", typeId);
        put(data, "class", "");
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