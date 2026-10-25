package com.tv.live.manager;

import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.security.SecurityCore;
import com.tv.live.util.LogBridge;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 【2026-10-24 新增】虎牙第三条线路（任意播）。
 *
 * <p>数据来源：2026-10-24 真机 HttpCanary 抓包（爱其意 App 全量换源浏览，
 * 111 个 huya.php 请求全量留存，其中 91 个返回 code=200 存活）。频道名与排序
 * 完全按照爱其意 App「全部剧集」换源列表截图（2026-10-22），共 91 个频道
 * 全部放入固定分组「任意播」，不再拆分；已失效频道不收录（用户确认按 91 个上线）。</p>
 *
 * <h3>与其他两条虎牙线路的关系（完全独立，互不污染）</h3>
 * <ul>
 *   <li>独立文件：本类不引用 {@link HuyaTogetherWatchManager}（第一路）与
 *       {@link HuyaTogetherWatchGroupManager}（第二路）的任何实例/缓存/线程池；</li>
 *   <li>独立分组：固定分组名「任意播」，单独一个组，全部 91 个频道都在本组内，
 *       不再拆分；分组名经 ChannelPanelController 合入 setFixedGroups 展示；</li>
 *   <li>独立协议：频道地址为 {@code rybhuya://<roomId>}（与第一路
 *       {@code huya://uid/}、第二路 {@code huya://room/} 完全不同的 scheme），
 *       由 TVPlayerManager 的独立解析分支处理；</li>
 *   <li>独立解析：起播时单独请求任意播 huya.php 拿落地 FLV 地址（单房间单次 GET，
 *       遵守「不对第三方服务器做批量/高频/并发请求」红线）；</li>
 *   <li>独立身份键：channelId 使用 {@code rybhuya_<roomId>} 前缀，与两路的
 *       {@code huya_uid_*}/{@code huya_*} 永不碰撞，频道列表去重互不影响。</li>
 * </ul>
 *
 * <h3>API 协议（抓包实证 2026-10-24）</h3>
 * <pre>
 * GET http://110.42.52.180:6688/live/huya.php?token=&lt;TOKEN&gt;&amp;type=json&amp;id=huya-&lt;roomId&gt;
 * → {"code":200,"msg":"获取成功","url":"https://al.flv.huya.com/src/....flv?wsSecret=..."}
 * → {"code":404,"msg":"主播休息了","url":""}
 * </pre>
 * 端点前缀（含 token）与源5 live.php 同源同 token，密文化存放于
 * {@code cpp/secstore.cpp}（id=50，{@link SecurityCore#C_HUYA_PHP}）。
 * token 由服务端轮换（曾 76AYudAHan → 0oujsAM7），失效时改 secstore.cpp 即可。
 */
public final class HuyaRenYiBoLineManager {

    private static final String TAG = "RenYiBoHuya3";

    /** 固定分组名（用户指定）：全部频道都在这一个组里，不拆分。 */
    public static final String GROUP_NAME = "任意播";

    /** 独立频道协议前缀：rybhuya://<roomId> */
    public static final String SCHEME = "rybhuya://";

    /** huya.php 响应 code：成功 */
    private static final int CODE_OK = 200;

    // ==================================================================
    // 频道清单（2026-10-24 真机抓包实证 91 个存活频道；名称与排序完全按照
    // 爱其意 App「全部剧集」换源列表截图（2026-10-22）——频道名↔房间号绑定
    // 由抓包请求顺序与截图列表顺序对齐还原，解说/剧集区 29 个锚点名逐一吻合。
    // 20 个已失效（404 主播休息了）频道与 1 个抓包缺失频道未收录。）
    // ==================================================================
    private static final Object[][] CHANNELS = {
            {"电影_星爷", 11342412},
            {"电影_英叔", 11342421},
            {"电影_发哥", 11342387},
            {"电影_成龙", 11342386},
            {"电影_王晶", 11602058},
            {"电影_沈腾", 11601968},
            {"电影_黄渤", 11352876},
            {"电影_强森", 21059581},
            {"电影_漫威", 11602034},
            {"电影_宇宙", 11342428},
            {"电影_怪兽", 21059577},
            {"电影_武侠", 11342427},
            {"电影_警匪", 11352886},
            {"电影_罪犯", 21059579},
            {"电影_枪战", 21059595},
            {"电影_海盗", 11279247},
            {"电影_赛车", 11602041},
            {"电影_推荐", 21059594},
            {"电影_救援", 11352934},
            {"电影_热血", 21059552},
            {"电影_盗墓", 11601971},
            {"电影_贺岁", 11342414},
            {"电影_玄幻", 21059585},
            {"电影_谍战", 21059592},
            {"电影_战争", 11352877},
            {"电影_喜剧", 11602043},
            {"电影_国产", 11336587},
            {"电影_周星星", 11342390},
            {"电影_李连杰", 11279251},
            {"电影_洪金宝", 11342429},
            {"电影_梁家辉", 11601972},
            {"电影_许冠英", 11342424},
            {"电影_刘德华", 21059588},
            {"电影_斯坦森", 11352963},
            {"电影_八点档", 880261},
            {"电影_蜘蛛侠", 21059599},
            {"电影_贺岁片", 11601986},
            {"电影_功夫片", 11352941},
            {"电影_黑帮片", 11336719},
            {"电影_丧尸片", 21059578},
            {"电影_灾难片", 11602075},
            {"电影_谍战片", 21059587},
            {"电影_战争片", 21059574},
            {"电影_犯罪片", 11352974},
            {"电影_古装片", 11336590},
            {"电影_高分动作", 11352884},
            {"电影_动作电影", 11602077},
            {"电影_搞笑喜剧", 11342423},
            {"电影_喜剧专场", 11602044},
            {"电影_欢笑影院", 11352894},
            {"电影_不挤影院", 11352897},
            {"电影_国产悬疑", 11342395},
            {"电影_快意江湖", 11342435},
            {"电影_黑帮斗争", 11342419},
            {"电影_古墓系列", 11352913},
            {"电影_嫣然影厅", 11601977},
            {"电影_女神系列", 11336571},
            {"电影_经典女神", 11352909},
            {"电影_许氏三杰", 11602033},
            {"电影_经典港片", 11352965},
            {"电影_镖行天下", 11352969},
            {"电影_高能反转", 11352887},
            {"电影_喜乐影院", 21059580},
            {"电影_蘑菇影厅", 11601981},
            {"电影_搞笑恐怖", 11601960},
            {"电影_死神来了", 11352903},
            {"电影_怪兽系列", 21059554},
            {"搞笑_陈翔六点半", 11274154},
            {"解说_电影狂人", 18715610},
            {"解说_斌哥漫说", 18632116},
            {"解说_小川侃电影", 15870999},
            {"解说_阿良说剧", 20488841},
            {"解说_鱼丸电影", 16615910},
            {"解说_阿斗说电影", 13303478},
            {"解说_扁豆看电影", 16028551},
            {"解说_大象放映室", 21277391},
            {"解说_科幻梦工场", 15152155},
            {"解说_亮哥讲电影", 16144175},
            {"解说_越哥说电影", 15894162},
            {"解说_涵哥评电影", 16865726},
            {"剧集_老三国", 11602081},
            {"剧集_新三国", 11352944},
            {"剧集_纪晓岚", 11342396},
            {"剧集_庆余年", 11352960},
            {"剧集_雍正王朝", 11342439},
            {"剧集_士兵突击", 11342430},
            {"剧集_爱情公寓", 11336726},
            {"剧集_家有儿女", 11352872},
            {"剧集_武林外传", 21059598},
            {"剧集_地下交通站", 11342433},
            {"剧集_邻里一家亲", 11342426},
    };

    private static volatile HuyaRenYiBoLineManager sInstance;

    /** 本地静态清单直接构建的频道列表（懒构建，无网络请求 —— 列表本身零请求）。 */
    private volatile List<Channel> mChannels;

    private HuyaRenYiBoLineManager() {
    }

    public static HuyaRenYiBoLineManager getInstance() {
        if (sInstance == null) {
            synchronized (HuyaRenYiBoLineManager.class) {
                if (sInstance == null) {
                    sInstance = new HuyaRenYiBoLineManager();
                }
            }
        }
        return sInstance;
    }

    /** 判断是否为本线路的待解析协议地址：rybhuya://<roomId> */
    public static boolean isSchemeUrl(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /** 从协议地址提取房间号；格式非法返回 0。 */
    public static int roomIdOfScheme(String url) {
        if (!isSchemeUrl(url)) return 0;
        try {
            return Integer.parseInt(url.substring(SCHEME.length()).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 生成协议地址（频道列表构建用）。 */
    public static String schemeOf(int roomId) {
        return SCHEME + roomId;
    }

    /**
     * 获取「任意播」分组全部频道（本地静态清单，瞬时返回，零网络请求）。
     * 返回的是不可变快照副本；调用方可直接渲染。
     */
    public List<Channel> getChannels() {
        List<Channel> local = mChannels;
        if (local == null) {
            synchronized (this) {
                local = mChannels;
                if (local == null) {
                    local = buildChannels();
                    mChannels = local;
                }
            }
        }
        return new ArrayList<>(local);
    }

    private static List<Channel> buildChannels() {
        List<Channel> out = new ArrayList<>(CHANNELS.length);
        for (Object[] row : CHANNELS) {
            try {
                String name = (String) row[0];
                int roomId = (Integer) row[1];
                if (TextUtils.isEmpty(name) || roomId <= 0) continue;
                // 独立身份键：rybhuya_<roomId> 前缀，与第一路 huya_uid_*、第二路 huya_* 不碰撞
                String cid = "rybhuya_" + roomId;
                Channel ch = new Channel(name, schemeOf(roomId), GROUP_NAME, cid, true, roomId);
                out.add(ch);
            } catch (Throwable ignored) {
            }
        }
        LogBridge.i(TAG, "任意播频道清单就绪：" + out.size() + " 个（本地静态清单，零请求）");
        return out;
    }

    // ==================================================================
    // 起播解析：rybhuya://<roomId> → huya.php → 落地 FLV 地址
    // ==================================================================

    /**
     * 解析房间落地播放地址（阻塞调用，须在工作线程执行）。
     *
     * <p>单房间单次 GET huya.php（6s 超时），成功返回 FLV 直链；
     * 失败（网络/404 主播休息了/token 失效）返回 null 并带出原因日志。</p>
     *
     * @return 落地播放地址（FLV）；失败返回 null
     */
    public static String resolvePlayUrlBlocking(int roomId) {
        if (roomId <= 0) return null;
        HttpURLConnection conn = null;
        try {
            String base = SecurityCore.secureString(SecurityCore.C_HUYA_PHP);
            if (TextUtils.isEmpty(base)) {
                LogBridge.w(TAG, "端点前缀为空（SO 常量缺失？）");
                return null;
            }
            URL url = new URL(base + roomId);
            // 【2026-10-25 代理坑修复】绕过系统代理（失效代理会让任意播取流整体超时）
            conn = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36");
            int code = conn.getResponseCode();
            if (code != CODE_OK) {
                LogBridge.w(TAG, "huya.php HTTP " + code + ", roomId=" + roomId);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            JSONObject jo = new JSONObject(sb.toString());
            int bizCode = jo.optInt("code", -1);
            String playUrl = jo.optString("url", "");
            if (bizCode == CODE_OK && !TextUtils.isEmpty(playUrl)) {
                LogBridge.i(TAG, "⚡任意播解析成功 roomId=" + roomId
                        + " url=" + playUrl.substring(0, Math.min(70, playUrl.length())));
                return playUrl;
            }
            LogBridge.w(TAG, "任意播解析失败 roomId=" + roomId
                    + " code=" + bizCode + " msg=" + jo.optString("msg", ""));
            return null;
        } catch (Throwable t) {
            LogBridge.w(TAG, "任意播解析异常 roomId=" + roomId + ": " + t.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
