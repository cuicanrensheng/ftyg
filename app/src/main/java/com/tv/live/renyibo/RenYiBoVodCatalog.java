package com.tv.live.renyibo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置源5（爱其意/任意播）影视点播（VOD）目录元数据。
 *
 * <p><b>数据来源</b>：逆向自爱其意 App 运行时抓包（F 盘既有分析工作区），
 * 明文响应 <i>「导航列表」</i> / <i>「首页推荐」</i> 两份 JSON（抓包期间未加密下发）。
 *
 * <p><b>【2026-10-12 明文+骨架落地】</b>服务端 {@code naiqiyi.php} 的请求/响应加密
 * 已确证为 <b>AES-128-CBC + PKCS7</b>、固定密钥 {@code 7dbe4c69aa5b98ad90ba3468e21c6fd2}、
 * 随机 IV（见 {@link VodCrypto}）。早期"流式加密 / 密钥派生未破译"的判断<b>已推翻</b>
 * （当时"不扩张"是因密文块数恰与明文一致）。当前让 App 立即可编译、显示完整分类骨架
 * 与已抓到的真实内容，仍<b>静态内置明文</b>（不发起请求）；加密封装见
 * {@link RenYiBoVodSourceLoader}（默认关闭双闸门）。
 *
 * <p><b>分类结构（type_id）</b>：1 电影 / 2 电视剧 / 3 综艺 / 4 动漫 / 5 纪录 /
 * <b>208 少儿</b>（注意少儿是 208 而非 6，与服务端一致，不可臆改）、
 * <b>207 短剧</b>（2026-10-13 加页第 8 组，经 video_list 实测返 21 条）。
 */
public final class RenYiBoVodCatalog {

    private RenYiBoVodCatalog() {}

    // ------------------------------------------------------------------
    // 【预留】加密 VOD 端点常量（待补齐加密后启用）
    // ------------------------------------------------------------------

    /**
     * VOD 唯一业务端点。⚠️ 注意 host 是 {@code 110.42.3.76:37255}，
     * <b>不是</b>直播源那台 {@code 110.42.52.180:6688}（两台服务互相独立）。
     */
    public static final String VOD_BASE = "http://110.42.3.76:37255/naiqiyi.php/v7/logs";

    /**
     * 【废弃】早期抓包登记的"密钥明文"（{@code xddappsecretkey124}，长度 19）。
     * 与破译出的<b>真密钥不同</b> —— 真密钥为 AES-128 固定密钥
     * {@code 7dbe4c69aa5b98ad90ba3468e21c6fd2}（见 {@link VodCrypto#VOD_KEY_HEX}）。
     * 保留此字段仅备历史核查，<b>不得用于任何加密运算</b>。
     */
    public static final String VOD_KEY = "xddappsecretkey124";

    /**
     * Dart 客户端请求头模板（原样来自抓包）。服务端会校验其中若干项，
     * 例如 {@code content-type: text/plain} 与 {@code version} / {@code version-number}。
     */
    public static final String[][] DART_HEADERS = {
            {"user-agent", "Dart/3.11 (dart:io)"},
            {"version", "2.0.1"},
            {"version-number", "101"},
            {"content-type", "text/plain"},
            {"pk-id", "com.CDAStudio.ReinforcingBarAndroid.aqya"},
            {"build-time", "1774333081066"},
            {"platform", "android"},
            {"platform-version", "UQ1A.240205.07021608 release-keys"},
    };

    // ------------------------------------------------------------------
    // 分类定义
    // ------------------------------------------------------------------

    /** 一个 VOD 分类：type_id + 名称 + 筛选维度（全部来自服务端「导航列表」明文）。 */
    public static final class Category {
        public final int typeId;
        public final String name;
        /** 每项 = {维度名, 逗号分隔的候选值}，维度顺序与服务端一致 */
        public final String[][] filters;

        Category(int typeId, String name, String[][] filters) {
            this.typeId = typeId;
            this.name = name;
            this.filters = filters;
        }

        public String filterValues(String key) {
            for (String[] f : filters) {
                if (f[0].equals(key)) return f[1];
            }
            return "";
        }
    }

    /** 六个 VOD 分类（顺序即 M3U 分组展示顺序）。 */
    public static final Category[] CATEGORIES = {
            new Category(1, "电影", new String[][]{
                    {"class", "喜剧,爱情,恐怖,动作,科幻,剧情,战争,警匪,犯罪,动画,奇幻,武侠,冒险,枪战,悬疑,惊悚,谍战,灾难,经典青春,文艺,微电影,古装,历史,运动,农村,儿童,网络电影,歌舞,传记,纪录,其他"},
                    {"area", "大陆,香港,台湾,美国,韩国,日本,法国,英国,德国,泰国,印度,其他"},
                    {"lang", "国语,英语,粤语,闽南语,韩语,日语,法语,德语,其它"},
                    {"year", "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016,2015,2014,2013,2012,2011,2010,2009,2008,2007,2006,2005,2004,2003,2002,2001,2000,1999,1998,1997,1996"},
            }),
            new Category(2, "电视剧", new String[][]{
                    {"class", "喜剧,古装,家庭,犯罪,动作,奇幻,武侠,爱情,青春,悬疑,科幻,警匪,谍战,商战,偶像,军事,神话,战争,都市,青春偶像,剧情,历史,经典,乡村,情景,网剧,其他"},
                    {"area", "大陆,香港,台湾,美国,韩国,日本,法国,英国,德国,泰国,印度,其他"},
                    {"lang", "国语,英语,粤语,闽南语,韩语,日语,法语,德语,其它"},
                    {"year", "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016,2015,2014,2013,2012,2011,2010,2009,2008,2007,2006,2005,2004,2003,2002,2001,2000,1999,1998,1997,1996"},
            }),
            new Category(3, "综艺", new String[][]{
                    {"class", "脱口秀,真人秀,搞笑,访谈,生活,晚会,美食,游戏,亲子,旅游,文化,体育,时尚,纪实,益智,演艺,歌舞,音乐,播报,选秀,情感,曲艺,游戏互动,财经,求职"},
                    {"area", "大陆,香港,台湾,美国,韩国,日本,法国,英国,德国,泰国,印度,其他"},
                    {"lang", "国语,英语,粤语,闽南语,韩语,日语,法语,德语,其它"},
                    {"year", "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016,2015,2014,2013,2012,2011,2010,2009,2008,2007,2006,2005,2004,2003,2002,2001,2000,1999,1998,1997,1996"},
            }),
            new Category(4, "动漫", new String[][]{
                    {"class", "情感,科幻,热血,推理,搞笑,神魔,真人,青春,魔法,神话,冒险,剧情,历史,萝莉,校园,动作,机战,运动,战争,少年,少女,社会,原创,亲子,益智,励志,格斗,恋爱,美少女,LOLI,竞技,童话,教育,其他"},
                    {"area", "大陆,香港,台湾,美国,韩国,日本,法国,英国,德国,泰国,印度,其他"},
                    {"lang", "国语,英语,粤语,闽南语,韩语,日语,法语,德语,其它"},
                    {"year", "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016,2015,2014,2013,2012,2011,2010,2009,2008,2007,2006,2005,2004,2003,2002,2001,2000,1999,1998,1997,1996"},
            }),
            new Category(5, "纪录", new String[][]{
                    {"class", "人物,军事,历史,自然,探险,科技,文化,刑侦,社会,旅游,其他"},
                    {"area", "大陆,香港,台湾,美国,韩国,日本,法国,英国,德国,泰国,印度,其他"},
                    {"lang", "国语,英语,粤语,闽南语,韩语,日语,法语,德语,其它"},
                    {"year", "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016,2015,2014,2013,2012,2011,2010,2009,2008,2007,2006,2005,2004,2003,2002,2001,2000,1999,1998,1997,1996"},
            }),
            new Category(208, "少儿", new String[][]{
                    {"class", "儿歌,益智,手工·绘画,玩具,搞笑,英语,早教,动画,数学,国学,冒险,交通工具,魔幻·科幻,动物,真人特摄,探索,其他"},
                    {"area", "大陆,香港,日本,韩国,欧美,新加坡,其他"},
                    {"lang", ""},
                    {"year", "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016,2015,2014,2013,2012,2011,2010,2009,2008,2007,2006,2005,2004,2003,2002,2001,2000,1999,1998,1997,1996"},
            }),
            // 【2026-10-13 加页】短剧（type_id=207，经 video_list 实测返 21 条/共 30 页），
            // 作为源5 点播的第 8 组，走与其余六分类完全相同的加密 video_list 拉取链路。
            new Category(207, "短剧", new String[][]{
                    {"class", "情感,逆袭,热血,搞笑,悬疑,复仇,都市,古装,穿越,甜宠,战神,赘婿,其他"},
                    {"area", "大陆,香港,台湾,其他"},
                    {"lang", "国语,粤语,其它"},
                    {"year", "2026,2025,2024,2023,2022,2021,2020,2019,2018,2017,2016"},
            }),
    };

    /** 按 type_id 找分类；未找到返回 null。 */
    public static Category byTypeId(int typeId) {
        for (Category c : CATEGORIES) {
            if (c.typeId == typeId) return c;
        }
        return null;
    }

    /** 分类名 → type_id（用于协议串 {@code renyibo-vod://<typeId>}，也用于日志可读性）。 */
    public static int typeIdOfName(String name) {
        for (Category c : CATEGORIES) {
            if (c.name.equals(name)) return c.typeId;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 首页推荐（已抓到的真实明文，静态内置）
    // ------------------------------------------------------------------

    /**
     * 首页 banner 与推荐位清单。
     *
     * <p>结构原样来自服务端「首页推荐」响应。banner 的 {@code req_type}=1 表示
     * 「跳转到某个视频/直播间」（{@code req_content} 即目标 id）。
     */
    public static final String HOME_RECOMMEND_JSON = ""
            + "{\"code\":1,\"msg\":\"首页推荐\",\"data\":{"
            + "\"banners\":["
            + "{\"id\":1,\"content\":\"https://pic1.imgdb.cn/item/69bccd30ed27ae3d10a960ed.jpg\",\"name\":\"live\",\"req_type\":1,\"req_content\":\"500502\",\"real_package_id\":\"\"},"
            + "{\"id\":2,\"content\":\"https://cdnjson.com/images/2023/07/26/p2894000057.webp\",\"name\":\"偷偷藏不住\",\"req_type\":1,\"req_content\":\"728\",\"real_package_id\":\"\"}"
            + "],\"videos\":["
            + "{\"id\":1,\"name\":\"热播剧\",\"type_id\":0,\"more_req_type\":2,\"more_text\":\"\",\"has_more\":false,\"vlist\":["
            + "{\"vod_id\":537136,\"vod_name\":\"野狗骨头\",\"vod_pic\":\"https://pic3.yzzyimg.online/upload/vod/2026-07-05/202607051783224796.jpg\",\"type_id\":2,\"vod_remarks\":\"更新至番外集全\"},"
            + "{\"vod_id\":605021,\"vod_name\":\"昨夜将至\",\"vod_pic\":\"https://pic3.yzzyimg.online/upload/vod/2026-06-24/202606241782294975.jpg\",\"type_id\":2,\"vod_remarks\":\"12集全\"},"
            + "{\"vod_id\":606408,\"vod_name\":\"百花杀\",\"vod_pic\":\"https://pic3.yzzyimg.online/upload/vod/2026-07-09/202607091783595191.jpg\",\"type_id\":2,\"vod_remarks\":\"已完结\"},"
            + "{\"vod_id\":612871,\"vod_name\":\"雀骨\",\"vod_pic\":\"https://pic3.yzzyimg.online/upload/vod/2026-07-11/202607111783767264.jpg\",\"type_id\":2,\"vod_remarks\":\"全28集\"}"
            + "]},"
            + "{\"id\":2,\"name\":\"日韩\",\"type_id\":0,\"more_req_type\":0,\"more_text\":\"\",\"has_more\":false,\"vlist\":["
            + "{\"vod_id\":603732,\"vod_name\":\"铁拳教育\",\"vod_pic\":\"https://pic3.yzzyimg.online/upload/vod/2026-06-05/202606051780650774.jpg\",\"type_id\":2,\"vod_remarks\":\"全10集\"},"
            + "{\"vod_id\":47642,\"vod_name\":\"死期将至\",\"vod_pic\":\"https://pps.vodfeiss.com/upload/vod/20221031-1/1f0239447520e49e3901ea1024ac4f5f.jpg\",\"type_id\":1,\"vod_remarks\":\"HD中字\"},"
            + "{\"vod_id\":189206,\"vod_name\":\"与恶魔有约\",\"vod_pic\":\"https://pps.vodfeiss.com/upload/vod/20231124-1/2233f235d4eb5c358d422fdbdaaf3e00.jpg\",\"type_id\":2,\"vod_remarks\":\"16集全\"},"
            + "{\"vod_id\":195080,\"vod_name\":\"京城怪物\",\"vod_pic\":\"https://pps.vodfeiss.com/upload/vod/20231222-1/ee0898512dc68a8caf9de74bc6f5368d.jpg\",\"type_id\":2,\"vod_remarks\":\"正片\"}"
            + "]},"
            + "{\"id\":5,\"name\":\"热播综艺\",\"type_id\":3,\"more_req_type\":1,\"more_text\":\"\",\"has_more\":true,\"vlist\":["
            + "{\"vod_id\":399041,\"vod_name\":\"现在就出发第二季\",\"vod_pic\":\"http://2fimg.youkuyouku.pro/pic/55758e26dd5eb794d23d1ad51443e79b\",\"type_id\":3,\"vod_remarks\":\"正片\"},"
            + "{\"vod_id\":497242,\"vod_name\":\"现在就出发第三季\",\"vod_pic\":\"https://img.jisuimage.com/cover/85ff7d09978a519342151403940ca43e.jpg\",\"type_id\":3,\"vod_remarks\":\"正片\"},"
            + "{\"vod_id\":40984,\"vod_name\":\"你好星期六\",\"vod_pic\":\"https://pps.vodfeiss.com/upload/vod/20221102-1/fd6e7b0bc241ae5ef8487bc864aad9f4.jpg\",\"type_id\":3,\"vod_remarks\":\"20260726(加更版)\"}"
            + "]},"
            + "{\"id\":6,\"name\":\"热播漫\",\"type_id\":0,\"more_req_type\":2,\"more_text\":\"\",\"has_more\":false,\"vlist\":["
            + "{\"vod_id\":162798,\"vod_name\":\"仙逆\",\"vod_pic\":\"https://pic3.yzzyimg.online/upload/vod/2023-09-25/16956175061.jpg\",\"type_id\":4,\"vod_remarks\":\"更新至151集\"},"
            + "{\"vod_id\":510572,\"vod_name\":\"剑来第二季\",\"vod_pic\":\"https://img.jisuimage.com/cover/46ca629891f3c3bd16b4a4978d31e483.jpg\",\"type_id\":4,\"vod_remarks\":\"更至陈平安角色曲本心集全\"},"
            + "{\"vod_id\":43504,\"vod_name\":\"凡人修仙传\",\"vod_pic\":\"https://pic3.yzzyimg.online/upload/vod/2022-04-03/16489732380.jpg\",\"type_id\":4,\"vod_remarks\":\"更新至184集\"}"
            + "]}"
            + "]}}";

    /**
     * 首页推荐里的视频条目，便于直接生成 M3U 条目（不经过 JSON 二次解析）。
     * 每项 = {vodId, vodName, vodPic, typeId, remarks}。
     */
    public static List<String[]> homeVideos() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(HOME_RECOMMEND_JSON);
            JSONArray videos = root.optJSONObject("data").optJSONArray("videos");
            for (int i = 0; i < videos.length(); i++) {
                JSONObject g = videos.getJSONObject(i);
                JSONArray vlist = g.optJSONArray("vlist");
                if (vlist == null) continue;
                for (int j = 0; j < vlist.length(); j++) {
                    JSONObject v = vlist.getJSONObject(j);
                    String id = v.optString("vod_id", "");
                    if (id.isEmpty()) continue;
                    out.add(new String[]{
                            id,
                            v.optString("vod_name", ""),
                            v.optString("vod_pic", ""),
                            String.valueOf(v.optInt("type_id", 0)),
                            v.optString("vod_remarks", ""),
                    });
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 首页 banner 条目：每项 = {id, name, pic, reqType, reqContent}。 */
    public static List<String[]> homeBanners() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(HOME_RECOMMEND_JSON);
            JSONArray banners = root.optJSONObject("data").optJSONArray("banners");
            for (int i = 0; i < banners.length(); i++) {
                JSONObject b = banners.getJSONObject(i);
                out.add(new String[]{
                        b.optString("id", ""),
                        b.optString("name", ""),
                        b.optString("content", ""),
                        String.valueOf(b.optInt("req_type", 0)),
                        b.optString("req_content", ""),
                });
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
