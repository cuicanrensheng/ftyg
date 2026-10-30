package com.tv.live.migu;

import android.content.Context;
import android.text.TextUtils;

import com.tv.live.util.LogBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 源6（咪咕）<b>失效频道记忆</b> —— 运行时自学习的黑名单。
 *
 * <p>背景（2026-10-30 实测）：省份地方频道里约 82% 的源已失效
 * （聚合源 {@code v.91kds.com} 解析到境外 IP、各地广电源 403/404/内网 IP、
 * 以及 {@code sddf://}/{@code anhui://} 等客户端未实现的协议）。
 *
 * <p><b>为什么不在客户端做全量探活</b>：那要对 CDN 发起上千次 HTTP 请求，
 * 属于对第三方的批量探测，红线禁止。改为<b>渐进收敛</b>：
 * <ol>
 *   <li>上线时只带 {@code assets/migu_channels_local.txt} 里<b>已离线探活过</b>的频道；</li>
 *   <li>在线刷新（{@link MiguSourceLoader} 的省份自更新）补进来的新频道，未探活；</li>
 *   <li>这些新频道一旦播放失败，就记进本名单；下次组装 M3U 时直接剔除，
 *       用户不会反复踩同一个死频道。</li>
 * </ol>
 *
 * <p>存储：{@code files/migu_dead_channels.txt}，每行一个 tvId（纯文本，便于排查）。
 * 上限 {@link #MAX_ENTRIES} 条，超出时整表清空重建（避免无限膨胀）。
 */
public final class MiguDeadChannelStore {

    private static final String TAG = "MiguDeadStore";

    private static final String FILE_NAME = "migu_dead_channels.txt";

    /** 上限：超过则整表清空（正常使用很难触达，防御性上限） */
    private static final int MAX_ENTRIES = 2000;

    private static volatile Set<Integer> sCache;
    private static final Object LOCK = new Object();

    private MiguDeadChannelStore() {}

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** 该 tvId 是否已被记为失效 */
    public static boolean isDead(Context context, int tvId) {
        if (context == null || tvId <= 0) return false;
        Set<Integer> set = load(context);
        return set.contains(tvId);
    }

    /**
     * 记录一个失效频道。重复调用幂等。
     *
     * <p>调用点应只在「确定播不出来」时触发（解析失败 / 起播超时），
     * 避免把偶发网络抖动的频道误判死刑。
     */
    public static void markDead(Context context, int tvId) {
        if (context == null || tvId <= 0) return;
        synchronized (LOCK) {
            Set<Integer> set = load(context);
            if (!set.add(tvId)) return;
            if (set.size() > MAX_ENTRIES) {
                LogBridge.w(TAG, "失效名单超过 " + MAX_ENTRIES + " 条，整表重置");
                set.clear();
                set.add(tvId);
            }
            write(context, set);
            LogBridge.i(TAG, "记录失效频道 tvId=" + tvId + "，累计 " + set.size() + " 条");
        }
    }

    /** 播放成功时调用，撤销误判（同一个 tvId 之前被记为死、这次通了） */
    public static void markAlive(Context context, int tvId) {
        if (context == null || tvId <= 0) return;
        synchronized (LOCK) {
            Set<Integer> set = load(context);
            if (!set.remove(tvId)) return;
            write(context, set);
            LogBridge.i(TAG, "撤销失效标记 tvId=" + tvId + "，剩余 " + set.size() + " 条");
        }
    }

    /** 清空名单（调试/排查用） */
    public static void clear(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            sCache = new LinkedHashSet<>();
            write(context, sCache);
            LogBridge.i(TAG, "失效名单已清空");
        }
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    private static Set<Integer> load(Context context) {
        Set<Integer> cur = sCache;
        if (cur != null) return cur;
        synchronized (LOCK) {
            if (sCache != null) return sCache;
            Set<Integer> set = new LinkedHashSet<>();
            File f = file(context);
            if (f.exists() && f.length() > 0) {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (TextUtils.isEmpty(line) || line.startsWith("#")) continue;
                        int comma = line.indexOf(',');
                        String idText = comma > 0 ? line.substring(0, comma) : line;
                        try {
                            int id = Integer.parseInt(idText.trim());
                            if (id > 0) set.add(id);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    LogBridge.d(TAG, "失效名单载入 " + set.size() + " 条");
                } catch (Throwable t) {
                    LogBridge.w(TAG, "失效名单读取失败：" + t.getMessage());
                } finally {
                    if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
                }
            }
            sCache = set;
            return set;
        }
    }

    private static void write(Context context, Set<Integer> set) {
        FileOutputStream fos = null;
        try {
            StringBuilder sb = new StringBuilder(set.size() * 9 + 32);
            sb.append("# 源6(咪咕) 失效频道名单：每行一个 tvId，由运行时播放失败自动记录\n");
            for (Integer id : set) {
                sb.append(id).append('\n');
            }
            fos = new FileOutputStream(file(context));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            LogBridge.w(TAG, "失效名单写入失败：" + t.getMessage());
        } finally {
            if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
        }
    }
}
