package com.tv.live.src4;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.util.LogBridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【2026-10-30】内置源4（影视）：频道即一部剧，EPG 面板复用为「选集列表」。
 *
 * <p>与源5/源7/源8 的做法一致（{@code EpgManager.getEpg} 按 channelId 前缀分流），
 * 但本源是 assets 静态表，**集数自带直链，零联网、零闸门**：
 * <ul>
 *   <li>{@code live_source_4.txt}：剧集型分组已压成「一部剧 = 1 个频道」，
 *       行格式为 M3U，携带 {@code tvg-id="src4:dNNN"}（解析后成为 channelId）；</li>
 *   <li>{@code src4_episodes.tsv}：{@code dNNN \t 集号 \t 直链}，本类据此构建
 *       {@link Channel.EpgItem}，每项 {@code customPlayUrl} 即该集直链，
 *       点击由 EPG 面板直接起播。</li>
 * </ul>
 *
 * <p>索引在首次调用时解析一次（5 千行级别，毫秒量级），之后走内存 Map。
 */
public final class Src4EpisodeIndex {

    private static final String TAG = "Src4Eps";

    /** channelId 前缀；EpgManager 据此判定走选集分支 */
    public static final String ID_PREFIX = "src4:";

    private static final String ASSET = "src4_episodes.tsv";

    private static volatile Map<String, List<Channel.EpgItem>> sIndex;
    private static volatile boolean sLoading;

    /** 已打过「选集命中」日志的剧集 id（getEpg 调用频繁，按 id 去重避免刷屏） */
    private static final java.util.Set<String> sHitLogged =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private Src4EpisodeIndex() {
    }

    /** 频道对应的剧集 id；非本源返回空串 */
    public static String vodIdOf(Channel ch) {
        if (ch == null) return "";
        return vodIdOfChannelId(ch.getChannelId());
    }

    public static String vodIdOfChannelId(String channelId) {
        if (TextUtils.isEmpty(channelId) || !channelId.startsWith(ID_PREFIX)) return "";
        return channelId.substring(ID_PREFIX.length()).trim();
    }

    /**
     * 取该剧的选集列表（防御性拷贝，避免面板原地改写污染缓存）。
     *
     * @return 永不为 null；无数据返回空列表
     */
    public static List<Channel.EpgItem> getEpisodes(Context context, String id) {
        if (TextUtils.isEmpty(id)) return new ArrayList<>();
        Map<String, List<Channel.EpgItem>> idx = ensureLoaded(context);
        if (idx == null) return new ArrayList<>();
        List<Channel.EpgItem> src = idx.get(id);
        if (src == null || src.isEmpty()) return new ArrayList<>();
        List<Channel.EpgItem> out = new ArrayList<>(src.size());
        for (Channel.EpgItem it : src) {
            Channel.EpgItem n = new Channel.EpgItem(it.dayName, it.time, it.title, false,
                    it.dateYMD);
            n.customPlayUrl = it.customPlayUrl;
            out.add(n);
        }
        if (!out.isEmpty() && sHitLogged.size() < 400 && sHitLogged.add(id)) {
            LogBridge.i(TAG, "选集命中 " + id + " 共 " + out.size() + " 集");
        }
        return out;
    }

    private static Map<String, List<Channel.EpgItem>> ensureLoaded(Context context) {
        Map<String, List<Channel.EpgItem>> idx = sIndex;
        if (idx != null) return idx;
        synchronized (Src4EpisodeIndex.class) {
            if (sIndex != null) return sIndex;
            if (context == null || sLoading) return null;
            sLoading = true;
            try {
                sIndex = load(context.getApplicationContext());
            } catch (Throwable t) {
                LogBridge.w(TAG, "集数索引加载失败：" + t.getClass().getSimpleName()
                        + " " + t.getMessage());
                sIndex = Collections.emptyMap();
            } finally {
                sLoading = false;
            }
            return sIndex;
        }
    }

    private static Map<String, List<Channel.EpgItem>> load(Context ctx) throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, List<Channel.EpgItem>> map = new HashMap<>(256);
        BufferedReader br = null;
        int rows = 0;
        try {
            br = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open(ASSET), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                int a = line.indexOf('\t');
                if (a <= 0) continue;
                int b = line.indexOf('\t', a + 1);
                if (b <= a) continue;
                String id = line.substring(0, a).trim();
                int num;
                try {
                    num = Integer.parseInt(line.substring(a + 1, b).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                String url = line.substring(b + 1).trim();
                if (id.isEmpty() || url.isEmpty()) continue;

                List<Channel.EpgItem> list = map.get(id);
                if (list == null) {
                    list = new ArrayList<>();
                    map.put(id, list);
                }
                // 集号 → 标题：至少两位，超过 99 集按实际位数（第100集）
                String title = "第" + (num < 10 ? "0" + num : String.valueOf(num)) + "集";
                // ⚠️ dayName 必须是「今天」：EPG 面板按 dayName/dateYMD 过滤（见
                //   EpgManager 里 targetDay.equals(dayName) 的判定），用「选集」之类的
                //   自定义名会被整列表过滤掉，面板显示空白。与源5 的写法保持一致。
                Channel.EpgItem it = new Channel.EpgItem("今天", "", title, false, 0);
                it.customPlayUrl = url;
                list.add(it);
                rows++;
            }
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
        LogBridge.i(TAG, "集数索引就绪：" + map.size() + " 部 / " + rows + " 集，耗时 "
                + (System.currentTimeMillis() - t0) + "ms");
        return map;
    }
}
