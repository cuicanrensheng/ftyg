package com.tv.live.util;

import com.tv.live.Channel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 频道屏蔽名单：<b>① 频道名精确匹配</b> + <b>② 成人内容关键词匹配</b>。
 *
 * <p>用途：
 * <ul>
 *   <li>① 部分频道在站点侧长期失效（起播即卡顿/黑屏/取不到流），或按用户要求整批剔除；</li>
 *   <li>② 源7（afhgrj 影视点播）等聚合源会混入色情影视/动漫，需要过滤掉。</li>
 * </ul>
 * 在「频道表最终出口」直接剔除，任何来源（网络抓取、镜像竞速、本地文件缓存、本地永久兜底）
 * 走一遍 {@link #filter(List)} 后就都不含它。
 *
 * <p><b>两套机制的分工</b>：
 * <ul>
 *   <li>{@link #BLOCKED_NAMES} —— <b>去空格后全等匹配</b>。用于「点名剔除」某些具体频道，
 *       绝不误伤同名或近似名的正常频道。</li>
 *   <li>{@link #ADULT_KEYWORDS} —— <b>包含匹配（不区分大小写）</b>。用于覆盖站点后续新增、
 *       标题带明显成人字样的频道（无法逐个点名）。</li>
 * </ul>
 * 关键词表刻意<b>只收高置信度词</b>：社区通用清单里的「激情 / 福利 / 诱惑 / 主播 / 美女 /
 * 偷拍 / 秘书 / 俱乐部」等词在正常影视综艺标题里也会出现（实测「激情奋斗的我们」
 * 「乌鸦俱乐部」「秘书镇」都会被误伤），故<b>未纳入</b>。如出现漏网或误伤，按需增减本表。
 */
public final class ChannelBlocklist {

    private static final String TAG = "ChannelBlocklist";

    /**
     * 被屏蔽的频道名（精确匹配，区分大小写）。
     *
     * <p><b>A. 源7「首页」空海报区块</b>（2026-10-10 用户要求整批屏蔽；实测它们并非源失效，
     * 只是首页模板缺缩略图，见 {@code _s7_emptycheck.py} 结论）：
     * <pre>
     * 主角电视剧         vodId 122157
     * 雨霖铃2026电视剧    vodId 122436
     * 良陈美锦电视剧      vodId 121533
     * 两心不疑电视剧      vodId 121413
     * 低智商犯罪电视剧    vodId 121663
     * 黑夜告白电视剧      vodId 120878
     * 爱情没有神话电视剧  vodId 121060
     * 佳偶天成2026电视剧  vodId 120790
     * </pre>
     *
     * <p><b>B. 源7 成人内容</b>（2026-10-11 用户要求屏蔽涉黄影视/动漫）：
     * <pre>
     * 深夜加班 ~肉食后辈的暗恋实习~   vodId 174026  电视剧（日系成人向）
     * 女风 ～女性需要××××吗？        vodId 174161  电视剧（日系成人向）
     * </pre>
     *
     * <p>注意：聚合站内容会轮换，换剧后上述名字会失效；届时靠 {@link #ADULT_KEYWORDS} 兜底，
     * 或更新本名单。
     */
    private static final Set<String> BLOCKED_NAMES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // A. 源7 首页空海报区块
                    "主角电视剧",
                    "雨霖铃2026电视剧",
                    "良陈美锦电视剧",
                    "两心不疑电视剧",
                    "低智商犯罪电视剧",
                    "黑夜告白电视剧",
                    "爱情没有神话电视剧",
                    "佳偶天成2026电视剧",
                    // B. 源7 成人内容
                    "深夜加班 ~肉食后辈的暗恋实习~",
                    "女风 ～女性需要××××吗？"
            )));

    /**
     * 成人内容关键词（<b>包含匹配</b>，忽略大小写）。
     *
     * <p>选词原则：<b>只保留在正常影视/综艺/动漫标题里几乎不会出现的字词</b>，
     * 宁可少收也不误伤。社区清单（LunaTV / SafeTVBox / tvbox-cfg）中偏「营销向」的词
     * （激情、福利、诱惑、偷拍、自拍、主播、美女、写真、禁忌、猎奇、秘书、俱乐部、夜店等）
     * <b>一律未收</b>——它们在正常标题里出现频率不低。
     *
     * <p>参考来源：LunaTV《TVBOX.md》成人过滤关键词（29+ 条）、
     * SafeTVBox {@code src/filter.js} 的 {@code BLACKLIST}。
     */
    private static final String[] ADULT_KEYWORDS = {
            // 分类 / 术语
            "伦理片", "里番", "成人片", "成人动漫", "成人动画", "三级片", "限制级", "18禁", "r18",
            // 明确涉黄
            "情色", "色情", "无码", "有码", "艳舞", "艳照", "春药", "催情",
            "淫", "嫖", "妓", "做爱", "性爱", "性交", "口交", "痴女", "痴汉", "调教", "凌辱",
            // 日系成人常见
            "人妻", "少妇", "熟女", "巨乳", "美乳", "情趣", "偷情", "不伦", "情妇",
            "肉食后辈", "制服诱惑",
            // 【2026-10-11】源7 具体成人作品（用户指认；用「包含」匹配以兼容标点/译名差异）
            "学生会也有洞",
            "萤火虫之婚", "萤火虫的出嫁",
    };

    private ChannelBlocklist() {}

    /** 该频道名是否应被屏蔽（null 安全，忽略首尾空白）：精确名单 ∪ 成人关键词 */
    public static boolean isBlocked(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        if (BLOCKED_NAMES.contains(n)) return true;
        return matchesAdultKeyword(n);
    }

    /** 关键词包含匹配（忽略大小写，仅对拉丁字母有意义，如 r18/R18） */
    private static boolean matchesAdultKeyword(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String kw : ADULT_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 过滤掉被屏蔽的频道。
     *
     * @param in 解析后的频道表；为 {@code null} 时原样返回 {@code null}
     * @return 过滤后的新列表（不修改入参）
     */
    public static List<Channel> filter(List<Channel> in) {
        if (in == null) return null;
        List<Channel> out = new ArrayList<>(in.size());
        int removed = 0;
        for (Channel c : in) {
            if (c == null) continue;
            String name = c.getName();
            if (isBlocked(name)) {
                removed++;
                // 【2026-10-13 去名字化】原实现把频道名整条打进日志。对被屏蔽的成人内容而言，
                //     这等于在日志里复现了一遍片名：既暴露了"屏蔽机制命中哪一类"，又把违规
                //     标题二次留存（9527 可被同网段任意设备拉取）。改为只记录分组 + 命中规则，
                //     数量由下方汇总行体现，排障时靠组名定位是哪一批源出的问题。
                final boolean byName = BLOCKED_NAMES.contains(name.trim());
                LogBridge.i(TAG, "已屏蔽 1 条 group=" + c.getGroup()
                        + " 规则=" + (byName ? "点名" : "关键词"));
                continue;
            }
            out.add(c);
        }
        if (removed > 0) {
            LogBridge.i(TAG, "频道屏蔽名单生效：过滤 " + removed + " 个，剩余 " + out.size() + " 个");
        }
        return out;
    }
}
