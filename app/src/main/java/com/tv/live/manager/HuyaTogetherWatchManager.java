package com.tv.live.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;

import com.tv.live.Channel;
import com.tv.live.MyApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class HuyaTogetherWatchManager {
    private static final String TAG = "HuyaTogetherWatch";
    private static volatile HuyaTogetherWatchManager sInstance;

    private static final String API_TMP_LIST = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList";
    private static final String API_CACHE_LIST = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=";

    private static final String FALLBACK_FILE = "huya_together_fallback.json";

    private static final int MIN_ROOMS_FOR_FALLBACK = 10;

    private static final int CATEGORY_ID_TOGETHER_WATCH = 2135;

    private static final int[] MOVIE_TMP_IDS = {2067, 2069, 2071, 2073, 2075, 2077, 2068, 2070, 2072, 2074, 2076};
    private static final int[] TV_TMP_IDS = {2079, 2081, 2083, 2085, 2087, 2089, 2080, 2082, 2084, 2086, 2088, 2090};

    private static final int[] ANIME_TMP_IDS = {6861, 2543, 2544, 2545, 2546, 2547, 2548};
    private static final int SUB_CATEGORY_VARIETY = 1011;
    private static final int[] VARIETY_TMP_IDS = {1011, 2091, 2093, 2095};

    // 【2026-09-24 提速】翻页深度收敛：各子分类实际返回远小于 10 页，缓存页数一并下调，
    // 减少串行请求次数（原先 12/10 页 × 300~600ms 抖动是 30s 耗时的主要来源）
    private static final int ANIME_CACHE_PAGES = 5;
    private static final int VARIETY_CACHE_PAGES = 5;
    private static final int GENERAL_CACHE_PAGES = 5;

    // 【2026-10-03 提速】动漫/综艺共用的 cache 列表缓存。
    // 病根：fetchAnimeRooms() 与 fetchVarietyRooms() 各自调 fetchFromCacheApi(5, ...)，
    //   即同一份 getLiveListByPage&gameId=2135 的 5 页被拉了两遍（真机日志：两次「获取到 600 个房间」），
    //   而这 10 页在第二路（HuyaTogetherWatchGroupManager）刚刚已经拉过。
    // 现在：5 页只拉一次，动漫/综艺共享，各自只做关键词过滤。
    private static final long CACHE_LIST_TTL_MS = 60 * 1000L;

    /**
     * 【2026-10-03 提速·拆阻塞】自己并发翻页时允许同时在途的页数。
     * <p>取值与已验证安全的 {@link #NO_THROTTLE_CONCURRENCY}（=8）一致；实际对外并发仍由
     * {@link #NO_THROTTLE_SEM} 收敛到 8，所以这里设 8 不会突破 WAF 安全阈值。</p>
     */
    private static final int CACHE_PAGE_CONCURRENCY = 8;

    /** 单页并发拉取的等待上限（毫秒）：超时视为该页失败，不影响其余页 */
    private static final long CACHE_PAGE_TIMEOUT_MS = 10000L;

    private final Object mCacheListLock = new Object();
    private volatile List<TogetherWatchRoom> mCachedListRooms = null;
    private volatile long mCachedListTs = 0;

    /**
     * 【2026-10-03 方案①·去限流】本类的列表拉取（子分类 getTmpLiveList / cache getLiveListByPage）
     * 已改用裸 HttpURLConnection 通道（见 httpGetRawNoThrottle），不再经过 OkHttp，
     * 因此不再受 HuyaHttpThrottle 的全局信号量限制。
     * <p>HuyaHttpThrottle 仍挂在 NetUtil 的 OkHttpClient 上，继续为 SDK Transporter、播放地址
     * 等其余虎牙请求兜底限流（并发常量见 HuyaStreamPlayer.HuyaHttpThrottle）。</p>
     * 【2026-09-19 503 修复】限流本身已下沉到 OkHttp 拦截器层，本类不持有独立信号量，避免双重限流。
     * 页间抖动延迟保留（60~150ms 随机错峰）。
     */
    private static final String HTTP_UA =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    /** 本类新通道（HttpURLConnection）的连接/读取超时 */
    private static final int HTTP_TIMEOUT_MS = 8000;

    /**
     * 【2026-10-03 方案①·补齐并发上限】新通道（绕过 HuyaHttpThrottle）的显式并发上限。
     * <p>来源：对齐第二路 {@code HuyaTogetherWatchGroupManager.LIST_PAGE_CONCURRENCY=8}
     * —— 该值已实测安全（8 并发拉 10 页 cache.php，0 次 503）。
     * 历史教训：{@code 开发记录_2026-09-15.md} 与 {@code 开发记录_2026-09-19.md} 均记录
     * 「提高并发 → 触发虎牙 WAF 503」，两次都是靠降并发解决，故这里不裸奔线程池宽度（10）。</p>
     */
    private static final int NO_THROTTLE_CONCURRENCY = 8;

    /** 新通道并发闸门：保证对外实际并发 ≤ {@link #NO_THROTTLE_CONCURRENCY}，避免重新引入 WAF 503 */
    private static final java.util.concurrent.Semaphore NO_THROTTLE_SEM =
            new java.util.concurrent.Semaphore(NO_THROTTLE_CONCURRENCY);

    /** acquire 许可的最长等待：超时宁可让该子分类失败（上层 break 并走既有兜底），也不无限阻塞 */
    private static final int NO_THROTTLE_ACQUIRE_TIMEOUT_MS = 10000;

    public static final String[] HTTP_API_GROUP_NAMES = {
            "怀旧老片", "外国电影", "影视热播",
            "海外追剧", "剧集追剧",
            "海外动漫", "动漫动画",
            "综艺娱乐"
    };
    private static final String[][] MOVIE_CATEGORY_KEYWORDS = {

        {"怀旧老片", "周星驰|星爷|林正英|英叔|僵尸|金庸|古龙|武侠|港片|怀旧|老电影|经典|老片|扁豆|乌贼|大象|亮哥|越哥|解说|影评|讲电影|聊电影|李小龙|黄飞鸿|英雄本色|无间道|上海滩|陈翔六点半|六点半|霍元甲|王晶|五福星|许氏三杰|小马哥|邱淑贞|王祖贤|张曼玉|梁家辉|林家栋|吴镇宇|古天乐|许绍雄|银河映像|话事人|古惑仔|粤语|港式|叶师傅|周润发|洪金宝"},

        {"外国电影", "纪录片|纪实|探索发现|动物世界|人与自然|国家地理|bbc|自然|人文|舌尖|航拍中国|跟着书本|大熊猫|故宫|长城|古文明|通史|贝爷|荒野求生|漫威|蜘蛛侠|钢铁侠|复仇者|雷神|美国队长|银河护卫队|dc|蝙蝠侠|超人|神奇女侠|正义联盟|科幻|星际|星球大战|宇宙|外星人|赛博朋克|侏罗纪|恐龙|哥斯拉|金刚|变形金刚|x战警|好莱坞|欧美|韩国电影|日本电影|印度|宝莱坞|外语片|泰坦尼克|阿凡达|指环王|哈利波特|007|速度与激情|流浪地球|马东锡|泡菜国|杰森·斯坦森|郭达|巨石强森|敢死队|兰博|史泰龙|阿汤哥|憨豆|异形|铁血战士|黑夜传说|人类清除计划|小黄人|纳尼亚传奇|博物馆奇妙夜|美恐|黑寡妇|小丑女|蝙蝠洞|超级英雄|剑心|一梦中土|首尔之春|韩国"},

        {"影视热播", "热播|热门|新片|大片|票房|院线|热映|最新|沈腾|黄渤|王宝强|开心麻花|爆笑|喜剧|小品|相声|成龙|李连杰|甄子丹|吴京|动作|武打|警匪|枪战|犯罪|追车|格斗|功夫|杀破狼|叶问|碟中谍|谍影|悬疑|推理|恐怖|惊悚|鬼片|丧尸|午夜凶铃|招魂|盗墓|鬼吹灯|古墓|盗墓笔记|奥斯卡|豆瓣|高评分|战争|二战|历史|抗日|谍战|冒险|探险|电影"},
    };

    private static final String[][] TV_CATEGORY_KEYWORDS = {

        {"海外追剧", "韩剧|日剧|美剧|英剧|泰剧|海外剧|港剧|台剧|欧美剧|日韩剧|tvb|海外|越狱|纸牌屋|权力的游戏|西部世界|绝命毒师|来自星星的你|太阳的后裔|请回答|我的大叔|顶楼|黑暗荣耀|虽然是精神病|窥探|信号|w两个世界|大长今|鱿鱼|禁忌女孩|吸血鬼日记|金智媛|秀智|毒枭|神探夏洛克|曼达洛人|沙丘|阿索卡|星战|太空|韩剧|美剧"},

        {"剧集追剧", "古装|宫廷|后宫|甄嬛|如懿|延禧|乾隆|康熙|雍正|清朝|唐朝|明朝|汉服|仙剑|仙侠|武侠剧|金庸剧|古龙剧|封神|琅琊榜|庆余年|赘婿|知否|陈情令|山河令|三生三世|花千骨|步步惊心|宫锁|军旅|战争|特种兵|亮剑|士兵突击|抗战|抗日|谍战|潜伏|伪装者|风筝|悬崖|雪豹|我的团长|部队|军人|长津湖|跨过鸭绿江|刑侦|破案|法医|犯罪|心理罪|白夜追凶|隐秘的角落|沉默的真相|无证之罪|狂飙|他是谁|法医秦明|盗墓剧|鬼吹灯剧|盗墓笔记剧|现代|都市|爱情|职场|家庭|生活|偶像剧|青春|校园|恋爱|情感|都市情感|欢乐颂|三十而已|我的前半生|都挺好|小欢喜|小别离|少年派|西游记|三国演义|水浒传|红楼梦|武林外传|爱情公寓|家有儿女|还珠格格|琼瑶|四大名著|经典剧集|怀旧剧集|90年代|老剧|新白娘子传奇|射雕英雄传|天龙八部|神雕侠侣|鹿鼎记|笑傲江湖|国产剧|内地剧|电视剧|剧集|剧情"},
    };

    private static final String[][] ANIME_CATEGORY_KEYWORDS = {

        {"海外动漫", "火影|海贼王|one piece|龙珠|七龍珠|鬼灭|咒术|进击的巨人|一拳超人|电锯人|柯南|犬夜叉|死神|妖尾|妖精的尾巴|七大罪|黑色五叶草|间谍过家家|芙莉莲|葬送|国王排名|勇者|高达|eva|新世纪福音战士|机甲|灌篮高手|幽游白书|乱马|网球王子|棋魂|通灵王|Tong灵王|游戏王|数码宝贝|神奇宝贝|宠物小精灵|中华小当家|铁臂阿童木|圣斗士|全职猎人|黑子的篮球|钢之炼金术师|钢炼|鲁路修|头文字d|城市猎人|四驱兄弟|神龙斗士|齐木|当哒当|超自然武装|影之实力者|食戟之灵|第一神拳|排球|足球小将|皮卡丘|宝可梦|瑞克与莫蒂|马男|双城之战|成龙历险记|猫和老鼠|宫崎骏|日本动画|少女|乙女|恋爱|魔法少女|美少女战士|魔卡少女|百变小樱|后宫番|逆后宫|女性向|少女番|恋爱番|紫罗兰|辉夜大小姐|五等分|青春恋爱|月刊少女|堀与宫村|更衣人偶|莉可丽丝|彻夜之歌|租借女友|日常|治愈|蜡笔小新|哆啦a梦|机器猫|樱桃小丸子|海绵宝宝|夏目友人帐|虫师|玉子爱情故事|摇曳露营|轻音少女|k-on|工作细胞|日常番|搞笑动漫|萌系|萌番|校园日常|轻松|悠哉日常大王|向山进发|比宇宙更远的地方|四月是你的谎言|未闻花名|clannad|日漫|新番|番剧"},

        {"动漫动画", "国漫|国产动画|中国风|秦时明月|武庚纪|斗罗大陆|斗破苍穹|完美世界|遮天|仙逆|凡人修仙|吞噬星空|武动乾坤|画江湖|不良人|狐妖|一人之下|镇魂街|灵笼|天官赐福|魔道祖师|凹凸世界|罗小黑|刺客伍六七|雾山五行|时光代理人|少年歌行|盘龙|雪鹰领主|神印王座|星辰变|天行九歌|全职高手|元尊|魁拔|白蛇|哪吒|姜子牙|深海|长安三万里|雄狮少年|新神榜|大鱼海棠|黑猫警长|葫芦娃|喜羊羊|熊出没|猪猪侠|铠甲勇士|热血|战斗|运动番|体育番|动漫|动画"},
    };

    private static final String[][] VARIETY_CATEGORY_KEYWORDS = {

        {"综艺娱乐", "搞笑|脱口秀|吐槽大会|奇葩说|相声|小品|欢乐喜剧人|笑傲江湖|喜剧总动员|跨界喜剧王|麻花|开心|快乐大本营|天天向上|非诚勿扰|我们都爱笑|百变大咖秀|王牌对王牌|今夜百乐门|德云社|辽宁民间艺术团|奔跑吧|跑男|极限挑战|真人秀|歌手|好声音|中国好声音|中国新歌声|创造营|青春有你|乘风破浪的姐姐|披荆斩棘的哥哥|爸爸去哪儿|中餐厅|向往的生活|花儿与少年|花样姐姐|亲爱的客栈|我是歌手|舞蹈生|这就是街舞|中国有嘻哈|说唱新世代|选秀|偶像练习生|王牌|运动吧少年|声临其境|最强大脑|一站到底|非你莫属|音乐|演唱会|唱歌|综艺|娱乐"},
    };

    private static final String[] ANIME_RELATED_KEYWORDS = {

        "动漫", "动画", "番剧", "国漫", "日漫", "二次元", "剧场版", "ova", "新番",

        "火影", "海贼", "龙珠", "鬼灭", "咒术", "柯南", "犬夜叉", "死神",
        "银魂", "蜡笔小新", "哆啦A梦", "樱桃小丸子", "海绵宝宝", "进击的巨人",
        "一拳超人", "妖尾", "妖精的尾巴", "七大罪", "黑色五叶草", "电锯人",
        "间谍过家家", "芙莉莲", "葬送", "国王排名", "勇者",

        "斗罗", "斗破", "秦时明月", "画江湖", "狐妖", "全职高手", "武庚纪",
        "吞噬星空", "武动乾坤", "遮天", "仙逆", "凡人修仙", "灵笼", "元尊",
        "星辰变", "天行九歌", "不良人", "镇魂街", "一人之下", "魔道祖师",
        "天官赐福", "凹凸世界", "罗小黑", "刺客伍六七", "雾山五行", "时光代理人",
        "少年歌行", "盘龙", "雪鹰领主", "神印王座",

        "高达", "eva", "新世纪福音战士", "机甲", "机动战士",

        "圣斗士", "灌篮高手", "幽游白书", "乱马", "网球王子", "棋魂",
        "夏目", "虫师", "通灵王", "Tong灵王", "游戏王", "数码宝贝", "神奇宝贝", "宠物小精灵",
        "中华小当家", "机器猫", "铁臂阿童木", "黑猫警长", "葫芦娃",

        "魔卡少女", "百变小樱", "美少女战士", "魔法少女",

        "异世界", "转生", "史莱姆", "无职转生", "re:0", "re0", "从零开始",
        "刀剑神域", "overlord",

        "全职猎人", "黑子的篮球", "钢之炼金术师", "钢炼", "鲁路修", "头文字d",
        "城市猎人", "四驱兄弟", "神龙斗士", "齐木", "当哒当", "超自然武装",
        "影之实力者", "食戟之灵", "第一神拳", "排球", "足球小将", "皮卡丘",
        "宝可梦", "瑞克与莫蒂", "马男", "双城之战", "成龙历险记", "猫和老鼠",
        "宫崎骏", "千与千寻", "龙猫", "你的名字", "天气之子",

        "魁拔", "大鱼海棠", "白蛇", "哪吒", "姜子牙", "深海", "长安三万里",
        "雄狮少年", "新神榜",

        "喜羊羊", "熊出没", "猪猪侠", "铠甲勇士"
    };

    private static final String[] VARIETY_RELATED_KEYWORDS = {

        "综艺", "快乐大本营", "天天向上", "跑男", "奔跑吧", "极限挑战", "鸡条", "王牌对王牌",
        "脱口秀", "吐槽大会", "奇葩说", "欢乐喜剧人", "笑傲江湖", "德云社", "郭德纲",
        "好声音", "中国新歌声", "歌手", "创造营", "青春有你", "乘风破浪",
        "披荆斩棘", "爸爸去哪儿", "中餐厅", "向往的生活", "花儿与少年", "亲爱的客栈", "客栈",
        "我就是演员", "演员请就位", "声临其境", "最强大脑", "一站到底", "非你莫属",
        "这！就是街舞", "这就是街舞", "舞蹈生", "中国有嘻哈", "说唱", "选秀", "偶像练习生",
        "非诚勿扰", "相亲", "恋综", "我们恋爱吧", "半熟恋人", "心动",
        "脱口秀大会", "今晚80后", "金星秀", "百变大咖秀",

        "音乐", "演唱会", "唱歌", "跨年晚会", "晚会", "歌王", "合唱", "现场",

        "娱乐", "明星", "八卦", "红毯", "颁奖礼",

        "体育", "赛事", "比赛", "nba", "cba", "世界杯", "足球", "篮球", "电竞", "游戏解说",

        "桃花坞", "青环", "毛雪汪", "天赐", "爱情保卫战", "高能少年团",
        "种地", "二十四小时", "新西游记", "大侦探", "相声", "即兴喜剧",
        "小品", "春晚", "捧腹", "笑哈哈", "宋小宝", "易中天", "百家讲坛",
        "T台秀", "运动", "旅游", "现在就出发"
    };

    private static final String[] VARIETY_EXCLUDE_KEYWORDS = {
        "电影", "影院", "院线", "票房", "大片", "漫威", "dc", "好莱坞", "奥斯卡",
        "美剧", "韩剧", "日剧", "英剧", "泰剧", "港剧", "台剧", "电视剧", "剧集",
        "古装", "宫廷", "仙侠", "武侠", "宫斗", "刑侦", "谍战", "抗日", "抗战",
        "tvb", "越狱", "权力的游戏", "绝命毒师", "大长今", "来自星星的你",
        "CSI", "犯罪现场", "神探", "罪案",
        "电视剧", "全集", "连续剧",

        "纪录片", "纪实", "通史", "求生", "荒野", "贝爷", "貝爺", "原始技术", "解放西",
        "盗墓笔记", "鬼吹灯", "胡八一", "云南虫谷", "天龙八部", "情景喜剧", "案发现场",
        "武媚娘", "篮球火", "中超", "警察", "喜剧片", "鹊刀门",
    };

    /**
     * 【2026-10-03 第五轮·扩容 worker 池】子分类 worker 池容量 = 4 个分类的子分类总数
     * （电影 11 + 剧集 12 + 动漫 7 + 综艺 4 = 34）。
     * <p>归因依据（v10 真机数据）：容量为 8 时 34 个 worker 分 4+ 波 FIFO 排队，先提交的分类占满线程，
     * 后提交的分类只能干等 —— 实测<b>电影</b>整类 1862ms 完成，而其最慢子分类仅 965ms，
     * 中间约 1000ms 纯属等线程（排队，不是干活）；分类完成时刻因此呈阶梯排队。</p>
     * <p>【安全性·为何加大池不放大对虎牙的并发压力】每个 worker 真正发起 HTTP 前都必须通过
     * {@link #NO_THROTTLE_SEM}（容量 {@link #NO_THROTTLE_CONCURRENCY}=8，
     * 在 {@code httpGetRawNoThrottle} 内 {@code tryAcquire}，超时 10s）。所以<b>实际同时在网的对外并发
     * 仍被硬性收敛到 8</b>；线程变多只是让已提交的 worker「站在门口排队」，而非全部同时打到虎牙。
     * 这与第二路 {@code LIST_PAGE_CONCURRENCY=8} 的实测安全值一致；历史 WAF 503 的诱因是对虎牙的
     * 并发/请求密度，本次改动<b>不改变对虎牙的并发</b>，只改变本进程内的排队位置。</p>
     */
    /**
     * 【2026-10-03 第六轮·回退扩容】容量 34 → 8。第五轮扩容的实测结论是**负收益**：
     * <p>真机 3 轮第一路均值 3603ms（对比容量 8 时的 3253ms，<b>+10.7% 变慢</b>）。原因：真正的并发闸门是
     * {@link #NO_THROTTLE_SEM}（容量 {@link #NO_THROTTLE_CONCURRENCY}=8），34 个 worker ÷ 8 许可 = 5 波排队。
     * 扩池只是把「池内 FIFO」挪成「信号量前 FIFO」，排队总量不变，反而多了 26 个线程的创建/切换开销。</p>
     * <p>唯一实质改善是「分类倒挂消失」（分类间差值收敛到 5~23ms），但总时长代价不值得，故回退。</p>
     * <p>后续方向：见 {@link #LONG_TAIL_TMP_ID} 长尾预热的说明——真正的下界由单个最慢子分类决定，不是池容量。</p>
     */
    private static final int SUB_WORKER_POOL_SIZE = 8;

    // 【2026-09-24 提速】子分类并行：本池只跑「子分类 worker」（峰值 34 个任务）。
    // 【2026-10-03 方案①】子分类拉取已改走不受限流的 HttpURLConnection 通道。
    // 【2026-10-03 第四轮·拆池】分类/协调任务已移出本池（见 mCategoryExecutor）。
    // 【2026-10-03 第五轮·扩容 34】已在第六轮回退为 8（实测负收益 +10.7%，见 SUB_WORKER_POOL_SIZE 注释）。
    // 其余经 NetUtil 的虎牙请求仍由 HuyaHttpThrottle 限流（见 HuyaStreamPlayer.HuyaHttpThrottle）。
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(SUB_WORKER_POOL_SIZE);

    /**
     * 【2026-10-03 第四轮·拆池】分类协调任务专用池，容量 5（外层协调 1 + 4 个分类任务）。
     * <p>用途：让「会 latch.await 等待子任务」的协调/分类任务不再占用 worker 池线程。</p>
     * <p>【定位·如实说明】这属于正确的清理，但<b>不是主要瓶颈</b>：真机数据显示拆池本身只带来
     * −1.3%（噪声级）。第五轮实测归因表明主要瓶颈是 worker 池容量（34 个 worker 挤在 8 线程里排队），
     * 故本次把 worker 池扩到 34，本池保持 5。</p>
     * <p>容量必须 ≥ 5：若只开 4，第 5 个「会阻塞等待」的任务会被排到协调任务之后，
     * 重新引入缩水版的阶梯串行。这些任务全程只做 latch.await + 轻量分类，不占 CPU。</p>
     */
    private final ExecutorService mCategoryExecutor = Executors.newFixedThreadPool(5);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * 【2026-10-03 第七轮·长尾预热】单独一根线程，专供「已知最慢子分类」抢先起跑。
     * <p>归因（v11 真机 3 轮，数据一致）：{@code tmpId=2079}（剧集·电视剧）每轮都是全场最慢子分类
     * （2182 / 2307 / 3157ms），而剧集类总耗时几乎等于它（≈ 各子分类已并行，瓶颈就是这个单点）。
     * 它只请求 <b>1 页</b>却耗时 2~3s，返回 <b>305 条</b>（vList=305，全场最大响应体）——
     * 所以慢的不是翻页、不是建连，而是<b>单请求的数据量</b>（传输 + {@code org.json} 逐字段解析）。</p>
     * <p>此前它排在 {@code TV_TMP_IDS} 首位但与其他 11 个剧集子分类同时提交到 8 线程池，
     * 大概率排到第 2 波才开跑，白白浪费一个波次。本预热把它提到<b>协调层一开始</b>用专属线程发出，
     * 与其余 33 个请求完全重叠；{@code fetchBySubCategory} 命中预热缓存即直接返回，不重复请求。</p>
     * <p>并发安全：预热走同一 {@link #NO_THROTTLE_SEM}（容量 8），对外总并发不变。</p>
     */
    private final ExecutorService mLongTailExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "huya-longtail-warmup");
        t.setDaemon(true);
        return t;
    });

    /** 长尾预热的已知最慢子分类 id（剧集·电视剧，305 房间，全场最大响应体） */
    private static final int LONG_TAIL_TMP_ID = 2079;

    /**
     * 长尾预热结果：{@code tmpId -> 已解析房间列表}。
     * <p>用 {@link java.util.concurrent.Future} 承载：{@code fetchBySubCategory} 命中时 {@code get()} 取结果，
     * 若预热尚未完成则自然阻塞等待（等价于自己发请求，但请求早已在途，总耗时更短）。</p>
     */
    private final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.Future<List<TogetherWatchRoom>>>
            mLongTailFutures = new java.util.concurrent.ConcurrentHashMap<>();

    private final Context mAppContext;

    private List<TogetherWatchRoom> mRoomList = new ArrayList<>();
    private long mLastFetchTime = 0;
    private static final long CACHE_VALID_MS = 5 * 60 * 1000;
    // 【2026-09-24 提速】在途去重标记：冷启动 setChannels 可能被连续调用两次（缓存/网络各一次），
    // 若已有一次一起看拉取在途，第二次直接复用，避免两倍请求互相抢占虎牙服务端配额
    private final AtomicBoolean mFetching = new AtomicBoolean(false);

    public interface OnFetchListener {
        void onSuccess(List<TogetherWatchRoom> rooms);
        void onFailed(String errorMsg);
        /** 【2026-09-24 提速】增量回调：某个分类先完成时立即回传，供面板"先展示后补齐" */
        default void onPartial(List<TogetherWatchRoom> rooms) {}
    }

    public interface OnPlayUrlListener {
        void onSuccess(String hlsUrl, String flvUrl);
        void onFailed(String errorMsg);
    }

    public interface OnChannelsFetchedListener {
        void onSuccess(List<Channel> channels);
        void onFailed(String errorMsg);
        /** 【2026-09-24 提速】增量回调：某分类先完成时立即回传，面板可先行展示 */
        default void onPartial(List<Channel> channels) {}
    }

    public static class TogetherWatchRoom {
        public int roomId;
        public int profileRoom;
        public String roomName;
        public String nickName;
        public String coverUrl;
        public int onlineCount;
        public String playUrl;
        public boolean isLive;
        public String category;

        public long huyaUid = 0;

        public TogetherWatchRoom(int roomId, int profileRoom, String roomName, String nickName,
                                String coverUrl, int onlineCount, String category) {
            this.roomId = roomId;
            this.profileRoom = profileRoom > 0 ? profileRoom : roomId;
            this.roomName = roomName;
            this.nickName = nickName;
            this.coverUrl = coverUrl;
            this.onlineCount = onlineCount;
            this.category = category;
        }

        public Channel toChannel() {
            String displayName = roomName;
            String channelIdStr;
            if (huyaUid > 0) channelIdStr = "huya_uid_" + huyaUid;
            else if (roomId > 0) channelIdStr = "huya_" + roomId;
            else channelIdStr = "huya_long_" + profileRoom;

            Channel channel;
            if (huyaUid > 0) {
                channel = new Channel(displayName, "huya://uid/" + huyaUid,
                        category, channelIdStr, true, profileRoom);
                channel.setHuyaUid(huyaUid);
            } else {
                channel = new Channel(displayName, "huya://room/" + profileRoom,
                        category, channelIdStr, true, profileRoom);
            }
            // 【2026-09-29 频道台标】把虎牙封面图写入 Channel.logoUrl，供 OTT 首页卡片加载。
            //   实时拉取与磁盘兜底（loadFallbackFromDisk 读入 coverUrl）最终都经本方法转换，一处即覆盖两条链路。
            if (coverUrl != null && !coverUrl.isEmpty()) {
                channel.setLogoUrl(coverUrl);
            }
            return channel;
        }
    }

    private HuyaTogetherWatchManager() {
        this.mAppContext = getAppContext();
    }

    private static Context getAppContext() {
        MyApplication app = MyApplication.getInstance();
        if (app != null) {
            return app.getApplicationContext();
        }
        LogBridge.w(TAG, "MyApplication 实例尚未初始化，无法获取 Application Context");
        return null;
    }

    public static HuyaTogetherWatchManager getInstance() {
        if (sInstance == null) {
            synchronized (HuyaTogetherWatchManager.class) {
                if (sInstance == null) {
                    sInstance = new HuyaTogetherWatchManager();
                }
            }
        }
        return sInstance;
    }

    public void fetchTogetherWatchRooms(OnFetchListener listener) {
        long now = System.currentTimeMillis();
        if (now - mLastFetchTime < CACHE_VALID_MS && !mRoomList.isEmpty()) {
            mMainHandler.post(() -> listener.onSuccess(mRoomList));
            return;
        }

        // 【2026-09-24 提速】并发去重：已有一次拉取在途时跳过本次，由在途拉取统一回传
        if (!mFetching.compareAndSet(false, true)) {
            LogBridge.d(TAG, "【一起看】已有拉取在途，跳过重复请求（避免双倍请求抢限流槽）");
            return;
        }

        // 【2026-10-03 第四轮·拆池】外层协调任务改到 mCategoryExecutor：它会 latch.await(60s) 等 4 个分类，
        // 若留在 mExecutor 会白占一个子分类 worker 的线程（同池自饥饿的第 5 处）。
        mCategoryExecutor.execute(() -> {
            try {
            final long tFetchStart = System.currentTimeMillis();
            List<TogetherWatchRoom> rooms = new ArrayList<>();
            boolean networkOk = true;

            boolean networkReturnedValidRooms = false;
            String lastError = null;
            try {

                // 【2026-10-03 第七轮·长尾预热】最先发出已知最慢子分类（tmpId=2079，305 房间），
                // 让它的 2~3s 传输+解析与其余 33 个请求完全重叠，消除剧集类的固定拖尾。
                warmUpLongTailSubCategory();

                final AtomicReference<List<TogetherWatchRoom>> movieRef = new AtomicReference<>(new ArrayList<>());
                final AtomicReference<List<TogetherWatchRoom>> tvRef = new AtomicReference<>(new ArrayList<>());
                final AtomicReference<List<TogetherWatchRoom>> animeRef = new AtomicReference<>(new ArrayList<>());
                final AtomicReference<List<TogetherWatchRoom>> varietyRef = new AtomicReference<>(new ArrayList<>());
                CountDownLatch latch = new CountDownLatch(4);

                mCategoryExecutor.execute(() -> {
                    try {
                        List<TogetherWatchRoom> r = classifyRoomsByKeywords(fetchMovieRooms(), MOVIE_CATEGORY_KEYWORDS, "影视热播");
                        movieRef.set(r);
                        if (!r.isEmpty()) postPartial(listener, r);
                    } catch (Exception e) { LogBridge.w(TAG, "电影分类异常: " + e.getMessage()); }
                    finally { latch.countDown(); }
                });
                mCategoryExecutor.execute(() -> {
                    try {
                        List<TogetherWatchRoom> r = classifyRoomsByKeywords(fetchTvRooms(), TV_CATEGORY_KEYWORDS, "剧集追剧");
                        tvRef.set(r);
                        if (!r.isEmpty()) postPartial(listener, r);
                    } catch (Exception e) { LogBridge.w(TAG, "剧集分类异常: " + e.getMessage()); }
                    finally { latch.countDown(); }
                });
                mCategoryExecutor.execute(() -> {
                    try {
                        List<TogetherWatchRoom> r = classifyRoomsByKeywords(fetchAnimeRooms(), ANIME_CATEGORY_KEYWORDS, "动漫动画");
                        animeRef.set(r);
                        if (!r.isEmpty()) postPartial(listener, r);
                    } catch (Exception e) { LogBridge.w(TAG, "动漫分类异常: " + e.getMessage()); }
                    finally { latch.countDown(); }
                });
                mCategoryExecutor.execute(() -> {
                    try {
                        List<TogetherWatchRoom> r = classifyRoomsByKeywords(fetchVarietyRooms(), VARIETY_CATEGORY_KEYWORDS, "综艺娱乐");
                        varietyRef.set(r);
                        if (!r.isEmpty()) postPartial(listener, r);
                    } catch (Exception e) { LogBridge.w(TAG, "综艺分类异常: " + e.getMessage()); }
                    finally { latch.countDown(); }
                });

                latch.await(60, TimeUnit.SECONDS);

                // 【2026-09-24 提速】分类已在各任务内完成，此处仅合并 + 判定是否拿到有效数据
                if (!movieRef.get().isEmpty()) { rooms.addAll(movieRef.get()); networkReturnedValidRooms = true; }
                if (!tvRef.get().isEmpty()) { rooms.addAll(tvRef.get()); networkReturnedValidRooms = true; }
                if (!animeRef.get().isEmpty()) { rooms.addAll(animeRef.get()); networkReturnedValidRooms = true; }
                if (!varietyRef.get().isEmpty()) { rooms.addAll(varietyRef.get()); networkReturnedValidRooms = true; }
            } catch (Exception e) {
                networkOk = false;
                lastError = "网络异常：" + e.getMessage();
                LogBridge.w(TAG, "网络请求异常，开始兜底：" + e.getMessage());
            }

            LogBridge.i(TAG, "⏱️【链路-HTTP线路】HTTP下载+JSON解析+分类 阶段耗时 "
                    + (System.currentTimeMillis() - tFetchStart) + "ms，房间=" + rooms.size());

            int animeCount = 0, varietyCount = 0;
            for (TogetherWatchRoom r : rooms) {
                if (r.category != null && (r.category.equals("动漫动画") || r.category.equals("海外动漫"))) animeCount++;
                if (r.category != null && r.category.equals("综艺娱乐")) varietyCount++;
            }
            if (animeCount < 5 || varietyCount < 5) {
                LogBridge.w(TAG, "动漫/综艺频道过少（动漫=" + animeCount + ", 综艺=" + varietyCount + "），追加静态兜底");
                rooms.addAll(getFallbackRooms());
            }

            if (networkOk && networkReturnedValidRooms) {
                mRoomList = rooms;
                mLastFetchTime = now;
                postSuccess(listener, rooms);

                if (rooms.size() >= MIN_ROOMS_FOR_FALLBACK) {
                    saveFallbackToDisk(rooms);
                } else {
                    LogBridge.w(TAG, "网络房间数过少（" + rooms.size() + " < " + MIN_ROOMS_FOR_FALLBACK + "），不覆盖永久兜底");
                }
                return;
            }

            // 两条自研 HTTP 线路（本路 + GroupManager line2）之外只剩本地磁盘兜底 + 静态内置兜底。
            if (networkReturnedValidRooms && rooms.size() >= 10) {
                mRoomList = rooms;
                mLastFetchTime = now;
                postSuccess(listener, rooms);
                if (rooms.size() >= MIN_ROOMS_FOR_FALLBACK) {
                    saveFallbackToDisk(rooms);
                } else {
                    LogBridge.w(TAG, "兜底后房间数过少（" + rooms.size() + " < " + MIN_ROOMS_FOR_FALLBACK + "），不覆盖永久兜底");
                }
                return;
            }

            if (rooms.isEmpty() || rooms.size() < 10) {
                List<TogetherWatchRoom> diskFb = loadFallbackFromDisk();
                if (diskFb != null && !diskFb.isEmpty()) {
                    LogBridge.i(TAG, "【兜底】启用本地永久兜底 " + FALLBACK_FILE + "，房间数=" + diskFb.size());
                    rooms = diskFb;
                } else {
                    LogBridge.w(TAG, "【兜底】本地永久兜底无数据，启用静态内置兜底");
                    if (rooms.isEmpty()) rooms = new ArrayList<>(getFallbackRooms());
                }
            }

            if (rooms.isEmpty()) {
                postFailed(listener, (lastError != null ? lastError : "未获取到一起看内容") + "（且无本地兜底）");
                return;
            }

            mRoomList = rooms;
            mLastFetchTime = now;
            postSuccess(listener, rooms);
            } catch (Throwable t) {
                LogBridge.e(TAG, "一起看拉取异常: " + t.getMessage());
                mFetching.set(false);
            }
        });
    }

    private static boolean isNumericString(String s) {
        if (TextUtils.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }


    private static String safeStr(String s) { return s == null ? "" : s; }

    private static int parseAudienceCount(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        try {
            String x = s.trim().replace(",", "");
            if (x.endsWith("万") || x.endsWith("w") || x.endsWith("W")) {
                double d = Double.parseDouble(x.substring(0, x.length() - 1));
                return (int) (d * 10000.0);
            }
            if (x.endsWith("亿")) {
                double d = Double.parseDouble(x.substring(0, x.length() - 1));
                return (int) (d * 100000000.0);
            }
            return (int) Double.parseDouble(x);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 【2026-09-24 提速】并行拉取多个子分类并合并（去重前）。
     * 原实现每个分类内部串行翻页，任一子分类慢/触发重试都会阻塞整类；
     * 改为子分类并行后，慢的子分类不再拖累其它子分类。
     * 【2026-10-03 方案①】子分类请求走不受限流的 HttpURLConnection 通道（httpGetRawNoThrottle），
     * 并发不再被 HuyaHttpThrottle 压到 6 个槽而串行排队。
     */
    private List<TogetherWatchRoom> fetchSubCategoriesParallel(int[] tmpIds, String categoryName) {
        List<TogetherWatchRoom> merged = Collections.synchronizedList(new ArrayList<TogetherWatchRoom>());
        if (tmpIds == null || tmpIds.length == 0) return merged;
        // 【2026-10-03 埋点】量测各子分类真实耗时与页数分布。
        // 【2026-10-03 埋点】前两次优化（免限流 14.6%、拆阻塞 0.2%）均因「凭猜测归因」而收益低于预期，
        //   故此处先只加度量、不改行为：拿到 ① 真实页数分布（子分类多为 1 页还是到 4 页）
        //   ② 各子分类耗时谁在拖尾 ③ 单类总耗时与最慢子分类的关系，再决定优化方向。
        final long tCatStart = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicInteger catSlowestId = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong catSlowestMs = new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch latch = new CountDownLatch(tmpIds.length);
        for (final int tmpId : tmpIds) {
            mExecutor.execute(() -> {
                final long tSub = System.currentTimeMillis();
                try {
                    List<TogetherWatchRoom> rooms = fetchBySubCategory(tmpId, categoryName);
                    if (rooms != null && !rooms.isEmpty()) merged.addAll(rooms);
                } catch (Exception e) {
                    LogBridge.d(TAG, "获取" + categoryName + "子分类失败: tmpId=" + tmpId + ", " + e.getMessage());
                } finally {
                    long subCost = System.currentTimeMillis() - tSub;
                    if (subCost > catSlowestMs.get()) {
                        catSlowestMs.set(subCost);
                        catSlowestId.set(tmpId);
                    }
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LogBridge.i(TAG, "⏱️【子分类埋点】" + categoryName + " 完成：子分类数=" + tmpIds.length
                + "，总耗时=" + (System.currentTimeMillis() - tCatStart) + "ms"
                + "，房间=" + merged.size()
                + "，最慢子分类 tmpId=" + catSlowestId.get() + "(" + catSlowestMs.get() + "ms)"
                + "（各子分类页数见上方逐条日志）");
        return merged;
    }

    private List<TogetherWatchRoom> fetchMovieRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = fetchSubCategoriesParallel(MOVIE_TMP_IDS, "电影");
        LogBridge.d(TAG, "电影类总共获取到 " + allRooms.size() + " 个房间");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private List<TogetherWatchRoom> fetchTvRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = fetchSubCategoriesParallel(TV_TMP_IDS, "剧集");
        LogBridge.d(TAG, "剧集类总共获取到 " + allRooms.size() + " 个房间");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private List<TogetherWatchRoom> fetchAnimeRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = fetchSubCategoriesParallel(ANIME_TMP_IDS, "动漫");
        try {
            // 【2026-10-03 提速】改用共享 cache 列表（不再自己翻 5 页）
            List<TogetherWatchRoom> cacheRooms = fetchCacheListShared();

            List<TogetherWatchRoom> animeCacheRooms = new ArrayList<>();
            for (TogetherWatchRoom room : cacheRooms) {
                if (isAnimeRelatedRoom(room.roomName, room.nickName)) {
                    animeCacheRooms.add(room);
                }
            }
            LogBridge.d(TAG, "cache API 返回 " + cacheRooms.size() + " 个房间，过滤出 "
                    + animeCacheRooms.size() + " 个动漫房间");
            allRooms.addAll(animeCacheRooms);
        } catch (Exception e) {
            LogBridge.d(TAG, "从cache获取动漫失败: " + e.getMessage());
        }
        LogBridge.d(TAG, "动漫类总共获取到 " + allRooms.size() + " 个房间（去重前）");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private boolean isAnimeRelatedRoom(String roomName, String nickName) {
        if (TextUtils.isEmpty(roomName) && TextUtils.isEmpty(nickName)) return false;
        String text = (roomName + " " + nickName).toLowerCase();
        for (String kw : ANIME_RELATED_KEYWORDS) {
            if (!TextUtils.isEmpty(kw) && text.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<TogetherWatchRoom> fetchVarietyRooms() throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();

        List<TogetherWatchRoom> rawRooms = fetchSubCategoriesParallel(VARIETY_TMP_IDS, "综艺");
        for (TogetherWatchRoom room : rawRooms) {
            if (isVarietyRelatedRoom(room.roomName, room.nickName)) {
                allRooms.add(room);
            }
        }
        try {
            // 【2026-10-03 提速】改用共享 cache 列表（不再自己翻 5 页）
            List<TogetherWatchRoom> cacheRooms = fetchCacheListShared();

            List<TogetherWatchRoom> varietyCacheRooms = new ArrayList<>();
            for (TogetherWatchRoom room : cacheRooms) {
                if (isVarietyRelatedRoom(room.roomName, room.nickName)) {
                    varietyCacheRooms.add(room);
                }
            }
            LogBridge.d(TAG, "cache API 返回 " + cacheRooms.size() + " 个房间，过滤出 "
                    + varietyCacheRooms.size() + " 个综艺房间");
            allRooms.addAll(varietyCacheRooms);
        } catch (Exception e) {
            LogBridge.d(TAG, "从cache获取综艺失败: " + e.getMessage());
        }
        LogBridge.d(TAG, "综艺类总共获取到 " + allRooms.size() + " 个房间（去重前）");
        return filterAndSortRooms(deduplicateRooms(allRooms));
    }

    private boolean isVarietyRelatedRoom(String roomName, String nickName) {
        if (TextUtils.isEmpty(roomName) && TextUtils.isEmpty(nickName)) return false;
        String text = (roomName + " " + nickName).toLowerCase();

        if (isAnimeRelatedRoom(roomName, nickName)) return false;

        for (String kw : VARIETY_EXCLUDE_KEYWORDS) {
            if (!TextUtils.isEmpty(kw) && text.contains(kw.toLowerCase())) {
                return false;
            }
        }

        for (String kw : VARIETY_RELATED_KEYWORDS) {
            if (!TextUtils.isEmpty(kw) && text.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【2026-10-03 方案①·修数据竞争】裸请求结果容器：把状态码与响应体一起返回，
     * 让重试循环只依赖「本线程自己那次请求」的状态码，而不是实例字段。
     * <p>背景：{@code fetchSubCategoriesParallel} 用固定线程池并发跑最多 34 个子分类任务，
     * 若状态码存在实例字段上会互相串台（T1 的 503 被 T2 的 200 覆盖 → 退避力度与「连续 503 放弃」
     * 判断全部错乱，放大请求密度、正是历史 WAF 503 的诱因）。</p>
     */
    private static final class HttpRawResult {
        final int code;
        final String body;
        HttpRawResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /**
     * 【2026-10-03 方案①·去限流】裸 HttpURLConnection GET，不走 NetUtil / 不受 HuyaHttpThrottle 限制。
     * <p>行为对齐第二路 {@code HuyaTogetherWatchGroupManager.fetchHttpLiveListBlocking} 的写法
     * （相同 UA / Referer / 超时风格），但只负责取回响应体字符串，JSON 解析交给调用方复用现有逻辑。</p>
     * <p>【并发上限】绕过限流后并发不再受 HuyaHttpThrottle 保护，这里用 {@link #NO_THROTTLE_SEM}
     * 显式收敛到 {@link #NO_THROTTLE_CONCURRENCY}（=8，对齐第二路实测安全值）。
     * 许可在**单次请求**粒度获取（acquire）与释放（finally release），确保重试期间不占用许可。</p>
     *
     * @return 状态码与响应体（非 200 时抛 {@link IOException}，状态码同时保留在异常文案里）
     */
    private HttpRawResult httpGetRawNoThrottle(String url) throws IOException {
        boolean acquired = false;
        try {
            acquired = NO_THROTTLE_SEM.tryAcquire(NO_THROTTLE_ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("列表通道等待并发许可被中断 url=" + url, e);
        }
        if (!acquired) {
            throw new IOException("列表通道并发繁忙超时 url=" + url);
        }
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", HTTP_UA);
            conn.setRequestProperty("Referer", "https://www.huya.com/");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " url=" + url);
            }
            is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new HttpRawResult(code, bos.toString("UTF-8"));
        } finally {
            if (is != null) {
                try { is.close(); } catch (Throwable ignored) {}
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
            // 只在成功 acquire 时 release，避免异常路径泄漏许可把通道永久锁死
            if (acquired) {
                NO_THROTTLE_SEM.release();
            }
        }
    }

    /**
     * 【2026-10-03 方案①·去限流】带退避重试的新通道包装，复刻原 {@code syncGetWithRetry} 的策略：
     * 退避基数 503 → 1500ms×attempt，其他 → 1000ms×attempt；最多重试 3 次；
     * 连续 503（attempt>=2 仍 503）直接放弃，交由上层翻页循环 break，不阻塞整类加载。
     * <p>失败一律向上抛 {@link IOException}，异常路径不吞。</p>
     * <p>【线程独立】503 判定用本轮 catch 到的异常解析出的<b>局部</b>状态码（{@link #parseHttpCode}），
     * 不读任何实例字段，保证并发子分类各自的重试语义互不串台。</p>
     */
    private String httpGetNoThrottleWithRetry(String url, String tag) throws IOException {
        final int MAX_RETRY = 3;
        IOException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                return httpGetRawNoThrottle(url).body;
            } catch (IOException e) {
                lastError = e;
                int localCode = parseHttpCode(e);   // 本线程本轮请求的状态码，线程内局部，避免共享字段串台
                LogBridge.d(TAG, tag + " 请求失败：" + e.getMessage());
                if (localCode == 503 && attempt >= 2) {
                    // 连续 503：与旧逻辑一致，放弃重试，让上层 break 出翻页循环
                    throw e;
                }
                if (attempt >= MAX_RETRY) {
                    break;
                }
                // 退避梯度：503 → 1500ms×attempt，其他 → 1000ms×attempt
                long delay = (localCode == 503) ? 1500L * attempt : 1000L * attempt;
                LogBridge.d(TAG, tag + " 请求失败，延迟 " + delay + "ms 后重试 (" + attempt + "/" + MAX_RETRY + ")");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(tag + " 重试被中断", ie);
                }
            }
        }
        throw lastError != null ? lastError : new IOException(tag + " 请求失败: url=" + url);
    }

    /**
     * 从 {@link #httpGetRawNoThrottle} 抛出的异常文案里解析 HTTP 状态码（格式 "HTTP 503 url=..."）。
     * <p>非 HTTP 状态类失败（连接超时、并发许可超时等）返回 -1，回退到 1000ms×attempt 的通用退避。</p>
     */
    private static int parseHttpCode(IOException e) {
        String msg = e.getMessage();
        if (msg == null || !msg.startsWith("HTTP ")) return -1;
        int end = msg.indexOf(' ', 5);
        String num = (end > 5) ? msg.substring(5, end) : msg.substring(5);
        try {
            return Integer.parseInt(num.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * 【2026-10-03 提速·拆阻塞】cache 列表（getLiveListByPage&gameId=2135）统一入口，带 60s 缓存。
     * <p>关键：第一路<b>不再向第二路要数据</b>，只在自己的免限流通道上并发拉取；第二路的成果仅通过
     * 非阻塞 peek 复用（锦上添花，绝不阻塞）。真机实测原先第一路 ~3170ms ≈ 第二路拉 2135 的 2080ms，
     * 根因就是这里阻塞等第二路。</p>
     * <p>两条路径：① {@link #peekLine2List()} 非阻塞命中第二路已有列表 → 直接转换返回；
     * ② 未命中 → 第一路自己 {@link #fetchFromCacheApi} 并发翻页（走 {@code httpGetNoThrottleWithRetry}）。</p>
     */
    private List<TogetherWatchRoom> fetchCacheListShared() throws IOException {
        List<TogetherWatchRoom> cached = mCachedListRooms;
        long age = System.currentTimeMillis() - mCachedListTs;
        if (cached != null && !cached.isEmpty() && age < CACHE_LIST_TTL_MS) {
            LogBridge.i(TAG, "【cache列表·共享】命中本类缓存，房间=" + cached.size() + "，年龄=" + age + "ms");
            return cached;
        }
        synchronized (mCacheListLock) {
            cached = mCachedListRooms;
            age = System.currentTimeMillis() - mCachedListTs;
            if (cached != null && !cached.isEmpty() && age < CACHE_LIST_TTL_MS) {
                return cached;
            }

            // ① 非阻塞复用第二路已拉好的（同一接口）2135 全量列表；未命中返回 null，绝不阻塞
            List<TogetherWatchRoom> fromLine2 = peekLine2List();
            if (fromLine2 != null && !fromLine2.isEmpty()) {
                mCachedListRooms = fromLine2;
                mCachedListTs = System.currentTimeMillis();
                LogBridge.i(TAG, "【cache列表·共享】命中第二路已有共享列表（非阻塞 peek），房间="
                        + fromLine2.size() + "，本轮无需自拉");
                return fromLine2;
            }

            // ② 未命中：第一路自己并发拉（免限流通道），不等第二路
            long t0 = System.currentTimeMillis();
            int pages = Math.max(ANIME_CACHE_PAGES, VARIETY_CACHE_PAGES);
            List<TogetherWatchRoom> all = fetchFromCacheApi(pages, "共享");
            long cost = System.currentTimeMillis() - t0;
            if (all != null && !all.isEmpty()) {
                mCachedListRooms = all;
                mCachedListTs = System.currentTimeMillis();
                LogBridge.i(TAG, "【cache列表·共享】自己并发拉取完成，房间=" + all.size()
                        + "，页数≤" + pages + "，耗时=" + cost + "ms");
            } else {
                LogBridge.w(TAG, "【cache列表·共享】自己并发拉取为空，耗时=" + cost + "ms（不写缓存）");
            }
            return all;
        }
    }

    /**
     * 【2026-10-03 提速·拆阻塞】非阻塞复用第二路的 2135 全量列表。
     * <p>只调 {@link HuyaTogetherWatchGroupManager#peekSharedLiveList()}（非阻塞契约：无缓存返回 null），
     * <b>绝不调用 getSharedLiveListBlocking()</b>，避免第一路阻塞等第二路翻页。</p>
     *
     * @return 转换后的房间列表；第二路无缓存或转换失败返回 null（由调用方自行拉取）
     */
    private List<TogetherWatchRoom> peekLine2List() {
        try {
            HuyaTogetherWatchGroupManager gm = HuyaTogetherWatchGroupManager.getInstance();
            List<com.tv.live.huya.HuyaLiveItem> raw = gm.peekSharedLiveList();
            if (raw == null || raw.isEmpty()) return null;
            return tryBuildFromLine2Raw(raw);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【cache列表·共享】peek 第二路列表失败（忽略，改自拉）：" + t.getMessage());
            return null;
        }
    }

    /**
     * 【2026-10-03 提速】把第二路的 {@code LiveListInfo} 列表转成第一路的 {@code TogetherWatchRoom}（纯转换）。
     * 两者字段口径不同（前者 title/nickName/coverUrl/channelId，后者 roomName/nickName/coverUrl/roomId），
     * 这里做一次映射；房间号取 profileRoom（channelId），与 parseCacheRoomList 的口径一致。
     */
    private List<TogetherWatchRoom> tryBuildFromLine2Raw(
            List<com.tv.live.huya.HuyaLiveItem> raw) {
        if (raw == null || raw.isEmpty()) return null;
        List<TogetherWatchRoom> out = new ArrayList<>(raw.size());
        for (com.tv.live.huya.HuyaLiveItem info : raw) {
            if (info == null) continue;
            long profileRoom = info.channelId;
            if (profileRoom <= 0 || profileRoom > Integer.MAX_VALUE) continue;
            int rid = (int) profileRoom;
            String roomName = safeStr(info.title);
            if (TextUtils.isEmpty(roomName)) roomName = "精彩节目";
            String nick = safeStr(info.nickName);
            TogetherWatchRoom r = new TogetherWatchRoom(rid, rid, roomName, nick,
                    safeStr(info.coverUrl), 0, "");
            r.isLive = true;
            // 【重要】huyaUid 必须保持 0：toChannel() 在 huyaUid>0 时会生成 huya://uid/ ，
            // 而自研解析器只能按真实房间号取流（uid 截断会得到无关假房间号）。
            // 第二路灌数据时也是刻意置 0 的，这里保持一致。
            r.huyaUid = 0;
            out.add(r);
        }
        return out;
    }

    /**
     * 【2026-10-03 提速·并发翻页】拉取 cache 列表（getLiveListByPage&gameId=2135）。
     * <p>先串行拉 page=1 探总页数（首页不满一页＝只有一页），再<b>并发</b>拉剩余页，按页序合并。
     * 原先串行翻页（每页之间还 sleep 60~150ms）是 ~3170ms 的主要构成；并发后 ≈ 1×RTT + 尾页延迟。
     * 走 {@code httpGetNoThrottleWithRetry}（免限流通道），实际对外并发由 {@code NO_THROTTLE_SEM} 收敛。</p>
     * <p>容错：单页失败/超时不影响其余页（参考第二路对 {@code Future.get(timeout)} 的处理）。</p>
     */
    private List<TogetherWatchRoom> fetchFromCacheApi(int maxPages, String categoryName) throws IOException {
        final int pageSize = 120;
        List<TogetherWatchRoom> allRooms = new ArrayList<>();

        // 先拉首页：既拿数据，也用它判断是否还有后续页
        FetchPage first = fetchCachePage(1, categoryName, pageSize);
        if (first == null || first.rooms.isEmpty()) {
            LogBridge.d(TAG, "从Cache API获取到 0 个" + categoryName + "房间（首页为空）");
            return allRooms;
        }
        allRooms.addAll(first.rooms);

        // 首页不满一页 → 只有一页；否则并发拉 2..maxPages（对齐第二路 fetchAllLiveListShared 的做法）
        if (!first.hasMore || maxPages <= 1) {
            LogBridge.d(TAG, "从Cache API获取到 " + allRooms.size() + " 个" + categoryName + "房间（单页）");
            return allRooms;
        }

        final int pageCount = maxPages;
        ExecutorService pagePool = Executors.newFixedThreadPool(CACHE_PAGE_CONCURRENCY);
        try {
            List<Future<FetchPage>> futures = new ArrayList<>();
            for (int p = 2; p <= pageCount; p++) {
                final int pp = p;
                futures.add(pagePool.submit(new Callable<FetchPage>() {
                    @Override public FetchPage call() {
                        return fetchCachePage(pp, categoryName, pageSize);
                    }
                }));
            }
            // 按页序合并，保证顺序稳定（去重在下游做）
            for (Future<FetchPage> f : futures) {
                try {
                    FetchPage fp = f.get(CACHE_PAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (fp != null && !fp.rooms.isEmpty()) allRooms.addAll(fp.rooms);
                } catch (Throwable ignored) { /* 单页失败不影响其余页 */ }
            }
        } finally {
            pagePool.shutdown();
        }

        LogBridge.d(TAG, "从Cache API获取到 " + allRooms.size() + " 个" + categoryName + "房间");
        return allRooms;
    }

    /** 单页拉取结果：rooms 为本页房间；hasMore 表示本页已满（可能还有下一页）。 */
    private static final class FetchPage {
        final List<TogetherWatchRoom> rooms;
        final boolean hasMore;
        FetchPage(List<TogetherWatchRoom> rooms, boolean hasMore) {
            this.rooms = rooms;
            this.hasMore = hasMore;
        }
    }

    /** 拉取 cache 列表的单独一页；失败返回 null（由调用方判定终止或跳过）。 */
    private FetchPage fetchCachePage(int page, String categoryName, int pageSize) {
        String url = API_CACHE_LIST + page;
        try {
            // 【2026-10-03 方案①】走不受限流的 HttpURLConnection 通道
            String resStr = httpGetNoThrottleWithRetry(url, "Cache API p" + page);

            JSONObject json = new JSONObject(resStr);
            JSONObject data = json.optJSONObject("data");
            JSONArray datas = data != null ? data.optJSONArray("datas") : null;
            List<TogetherWatchRoom> pageRooms = parseCacheRoomList(resStr, categoryName);
            boolean hasMore = (datas != null && datas.length() >= pageSize);
            return new FetchPage(pageRooms, hasMore);
        } catch (Exception e) {
            LogBridge.d(TAG, "Cache API请求/解析失败 p" + page + ": " + e.getMessage());
            return null;
        }
    }

    private List<TogetherWatchRoom> parseCacheRoomList(String jsonStr, String categoryName) throws Exception {
        List<TogetherWatchRoom> rooms = new ArrayList<>();
        JSONObject json = new JSONObject(jsonStr);

        JSONObject data = json.optJSONObject("data");
        if (data == null) return rooms;

        JSONArray datas = data.optJSONArray("datas");
        if (datas == null || datas.length() == 0) return rooms;

        for (int i = 0; i < datas.length(); i++) {
            JSONObject room = datas.getJSONObject(i);

            // 【2026-09-29 修复 · 自研解析器 100% 失败的根因之一】
            //   原代码读的是 "roomNo"，但 cache.php LiveList 返回的 datas[] 里根本没有这个键
            //   （真实键是 profileRoom），于是 roomNo 恒为 0 → 回落成 uid。
            //   危害有两层：
            //     ① 播 put 的 huyaRoomId 变成 uid，而 mp profileRoom 接口只认真正的 profileRoom，
            //        查询直接返回 422「该主播不存在」→ 自研解析器无候选地址；
            //     ② uid（如 1099531785141）远超 int 范围，被 (int) 截断后变成随机房间号
            //        （1099531785141 → 20157365），日志里那些"查不到的主播"就是这么来的。
            //   这里改为读真实字段 profileRoom。
            long roomNo = room.optLong("roomNo", 0);
            long uid = room.optLong("uid", 0);
            long profile = room.optLong("profileRoom", 0);
            if (profile <= 0) profile = roomNo;
            if (profile <= 0) profile = uid;
            if (profile <= 0 || profile > Integer.MAX_VALUE) {
                LogBridge.d(TAG, "跳过无有效 profileRoom 的房间: uid=" + uid);
                continue;
            }
            int roomId = (int) profile;

            String roomName = room.optString("roomName", "");
            if (TextUtils.isEmpty(roomName)) {

                roomName = room.optString("introduction", "");
            }
            if (TextUtils.isEmpty(roomName)) roomName = "精彩节目";

            String nickName = room.optString("nick", "");
            if (TextUtils.isEmpty(nickName)) nickName = "精彩节目";

            if ("精彩节目".equals(roomName) && "精彩节目".equals(nickName)) {
                LogBridge.d(TAG, "过滤精彩节目占位: roomId=" + roomId);
                continue;
            }

            String coverUrl = room.optString("screenshot", "");

            String totalCountStr = room.optString("totalCount", "0");
            int onlineCount = 0;
            try {
                onlineCount = Integer.parseInt(totalCountStr);
            } catch (Exception e) {}

            int bIsLive = room.optInt("isLive", -1);
            boolean isLive = (bIsLive == 1 || onlineCount > 0);

            if (!isLive && onlineCount == 0) {
                continue;
            }

            TogetherWatchRoom twRoom = new TogetherWatchRoom(roomId, roomId, roomName, nickName,
                    coverUrl, onlineCount, categoryName);
            // 【2026-09-29 修复】把主播 uid 一并带上：toChannel() 会据此生成 "huya://uid/<uid>"，
            //   同时把上面解析出的真 profileRoom 作为 huyaRoomId 传给播放层。
            //   这样自研解析器能按 profileRoom 命中 mp 接口；万一失败，SDK 还能按 uid 兜底。
            if (uid > 0 && uid != roomId) {
                twRoom.huyaUid = uid;
            }
            twRoom.isLive = isLive;
            rooms.add(twRoom);
        }

        return rooms;
    }

    private List<TogetherWatchRoom> deduplicateRooms(List<TogetherWatchRoom> rooms) {
        List<TogetherWatchRoom> result = new ArrayList<>();
        List<Integer> seenIds = new ArrayList<>();
        for (TogetherWatchRoom room : rooms) {
            if (!seenIds.contains(room.roomId)) {
                seenIds.add(room.roomId);
                result.add(room);
            }
        }
        if (result.size() < rooms.size()) {
            LogBridge.d(TAG, "去重后房间数: " + result.size() + " (原: " + rooms.size() + ")");
        }
        return result;
    }

    private List<TogetherWatchRoom> filterAndSortRooms(List<TogetherWatchRoom> rooms) {
        List<TogetherWatchRoom> validRooms = new ArrayList<>();
        List<TogetherWatchRoom> suspectRooms = new ArrayList<>();

        for (TogetherWatchRoom room : rooms) {
            if (room.onlineCount > 10 || room.isLive) {
                validRooms.add(room);
            } else if (room.onlineCount > 0) {
                suspectRooms.add(room);
            }
        }

        validRooms.addAll(suspectRooms);

        Collections.sort(validRooms, (a, b) -> {
            if (b.isLive != a.isLive) {
                return b.isLive ? 1 : -1;
            }
            return b.onlineCount - a.onlineCount;
        });

        LogBridge.d(TAG, "过滤排序后房间数: " + validRooms.size() + " (原: " + rooms.size() + ")");
        return validRooms;
    }

    private List<TogetherWatchRoom> classifyRoomsByKeywords(List<TogetherWatchRoom> sourceRooms,
                                                           String[][] categoryKeywords,
                                                           String defaultCategory) {
        List<TogetherWatchRoom> result = new ArrayList<>();
        if (sourceRooms.isEmpty()) return result;

        java.util.Map<String, Integer> categoryCount = new java.util.LinkedHashMap<>();
        List<String> defaultRoomNames = new ArrayList<>();

        for (TogetherWatchRoom room : sourceRooms) {
            String searchText = (room.roomName + " " + room.nickName).toLowerCase();
            String matchedCategory = defaultCategory;

            for (String[] ck : categoryKeywords) {
                String categoryName = ck[0];
                String keywords = ck[1];
                if (TextUtils.isEmpty(keywords)) continue;

                String[] keywordArr = keywords.split("\\|");
                for (String kw : keywordArr) {
                    if (!TextUtils.isEmpty(kw) && searchText.contains(kw.toLowerCase())) {
                        matchedCategory = categoryName;
                        break;
                    }
                }
                if (matchedCategory != null && !matchedCategory.equals(defaultCategory)) break;
            }

            if (matchedCategory != null) {

                TogetherWatchRoom copy = new TogetherWatchRoom(room.roomId, room.profileRoom, room.roomName, room.nickName,
                        room.coverUrl, room.onlineCount, matchedCategory);
                copy.isLive = room.isLive;
                result.add(copy);
                categoryCount.merge(matchedCategory, 1, Integer::sum);
                if (matchedCategory.equals(defaultCategory)) {
                    defaultRoomNames.add(room.roomName);
                }
            }
        }

        LogBridge.d(TAG, "按关键词分类完成: " + result.size() + " 个房间，默认分类: " + defaultCategory);

        for (java.util.Map.Entry<String, Integer> e : categoryCount.entrySet()) {
            LogBridge.d(TAG, "  分组[" + e.getKey() + "] = " + e.getValue() + " 个频道");
        }

        if (!defaultRoomNames.isEmpty()) {
            LogBridge.d(TAG, "  兜底[" + defaultCategory + "]频道数: " + defaultRoomNames.size());
        }
        return result;
    }

    /**
     * 【2026-10-03 第七轮·长尾预热】在协调层最开始抢先发出「已知最慢子分类」（{@value #LONG_TAIL_TMP_ID}）。
     * <p>幂等：同一进程内只在首次真正发起；重复调用无副作用。</p>
     * <p>与 {@code fetchSubCategoriesParallel} 的协作：预热把 Future 放进 {@link #mLongTailFutures}，
     * 剧集任务跑到该 tmpId 时 {@code fetchBySubCategory} 直接复用 Future 的结果（不重复请求）。</p>
     */
    private void warmUpLongTailSubCategory() {
        final int tmpId = LONG_TAIL_TMP_ID;
        if (mLongTailFutures.containsKey(tmpId)) return;
        final long tWarm = System.currentTimeMillis();
        java.util.concurrent.Future<List<TogetherWatchRoom>> f = mLongTailExecutor.submit(() -> {
            List<TogetherWatchRoom> rooms = fetchBySubCategoryDirect(tmpId, "剧集");
            LogBridge.i(TAG, "⏱️【长尾预热】tmpId=" + tmpId + " 抢先完成：房间=" + rooms.size()
                    + "，耗时=" + (System.currentTimeMillis() - tWarm) + "ms（已与其他子分类重叠）");
            return rooms;
        });
        mLongTailFutures.put(tmpId, f);
        LogBridge.i(TAG, "⏱️【长尾预热】tmpId=" + tmpId + " 已抢先起跑（专属线程，不等 8 线程池排波）");
    }

    private List<TogetherWatchRoom> fetchBySubCategory(int subCategoryId, String categoryName) throws IOException {
        // 【2026-10-03 第七轮·长尾预热】命中预热 Future → 直接复用（请求已在途/已完成，不重复发）
        java.util.concurrent.Future<List<TogetherWatchRoom>> warm = mLongTailFutures.get(subCategoryId);
        if (warm != null) {
            try {
                List<TogetherWatchRoom> r = warm.get();
                LogBridge.i(TAG, "⏱️【长尾预热】tmpId=" + subCategoryId + " 复用预热结果：房间="
                        + (r == null ? 0 : r.size()) + "（省去重复请求）");
                return r != null ? r : new ArrayList<>();
            } catch (Exception e) {
                LogBridge.w(TAG, "⏱️【长尾预热】tmpId=" + subCategoryId + " 预热结果异常，回退自取：" + e.getMessage());
                // 预热失败 → 走下方正常路径重取
            }
        }
        return fetchBySubCategoryDirect(subCategoryId, categoryName);
    }

    /** 真正发请求抓一个子分类（长尾预热与常规路径共用） */
    private List<TogetherWatchRoom> fetchBySubCategoryDirect(int subCategoryId, String categoryName) throws IOException {
        List<TogetherWatchRoom> allRooms = new ArrayList<>();
        // 【2026-09-24 提速】翻页深度 10→4：实测各子分类远不足 10 页，收敛上限减少串行请求
        int maxPages = 4;
        int pageSize = 500;
        // 【2026-10-03 埋点】记录本子分类实际请求了几页（用于判定「多数 1 页」假设是否成立）
        int pagesFetched = 0;

        for (int page = 1; page <= maxPages; page++) {
            String url = API_TMP_LIST + "?iGid=" + CATEGORY_ID_TOGETHER_WATCH +
                    "&iTmpId=" + subCategoryId + "&iPageNo=" + page + "&iPageSize=" + pageSize;

            // 【2026-10-03 方案①】改走不受限流的 HttpURLConnection 通道；
            // 非 200（含连续 503）抛 IOException → 由下层 catch break，不再排队等信号量
            String resStr = httpGetNoThrottleWithRetry(url, "API");
            pagesFetched++;

            try {
                JSONObject json = new JSONObject(resStr);
                JSONArray vList = json.optJSONArray("vList");
                List<TogetherWatchRoom> pageRooms = parseRoomList(resStr, categoryName);
                if (pageRooms.isEmpty()) {
                    // 【2026-10-03 埋点】子分类页数分布（page=1 即 break 说明单页；否则真在翻页）
                    LogBridge.i(TAG, "⏱️【子分类埋点】tmpId=" + subCategoryId + "（" + categoryName
                            + "）翻页结束于 page=" + page + "，共 " + pagesFetched + " 页，房间=" + allRooms.size());
                    break;
                }
                allRooms.addAll(pageRooms);

                if (vList == null || vList.length() < pageSize) {
                    LogBridge.i(TAG, "⏱️【子分类埋点】tmpId=" + subCategoryId + "（" + categoryName
                            + "）翻页结束于 page=" + page + "，共 " + pagesFetched + " 页，房间=" + allRooms.size()
                            + "（vList=" + (vList == null ? "null" : vList.length()) + " < " + pageSize + " 视为末页）");
                    break;
                }
                // 【2026-09-24 提速】页间抖动 300~600ms→60~150ms：子分类已并行，
                // 这里仅保留最小错峰，避免同 IP 峰值过猛
                try {
                    Thread.sleep(60L + (long) (Math.random() * 90));
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            } catch (Exception e) {
                LogBridge.d(TAG, "解析失败：" + e.getMessage());
                break;
            }
        }

        return allRooms;
    }

    private List<TogetherWatchRoom> parseRoomList(String jsonStr, String categoryName) throws Exception {
        List<TogetherWatchRoom> rooms = new ArrayList<>();
        JSONObject json = new JSONObject(jsonStr);

        JSONArray vList = json.optJSONArray("vList");
        if (vList != null) {
            for (int i = 0; i < vList.length(); i++) {
                JSONObject room = vList.getJSONObject(i);

                long lRoomId = room.optLong("lRoomId", 0);
                long lUid = room.optLong("lUid", 0);
                long lProfileRoom = room.optLong("lProfileRoom", 0);
                // 【2026-09-29 修复】同 parseCacheRoomList：房间号必须是真实 profileRoom，
                //   不能用 uid 顶替（uid 远超 int，(int) 截断会得到无关的假房间号）。
                long realProfile = lProfileRoom > 0 ? lProfileRoom : lRoomId;
                if (realProfile <= 0 || realProfile > Integer.MAX_VALUE) continue;
                int roomId = (int) realProfile;
                int profileRoomId = roomId;
                if (roomId <= 0) continue;

                String roomName = room.optString("sRoomName", "");
                String nickName = room.optString("sIntroduction", "");
                if (TextUtils.isEmpty(roomName)) roomName = "精彩节目";
                if (TextUtils.isEmpty(nickName)) nickName = "精彩节目";

                if ("精彩节目".equals(roomName) && "精彩节目".equals(nickName)) {
                    continue;
                }

                String coverUrl = room.optString("sScreenshot", "");

                long userCount = room.optLong("lUserCount", 0);
                long totalCount = room.optLong("lTotalCount", 0);
                int onlineCount = (int) Math.max(userCount, totalCount);

                int liveStatus = room.optInt("iLiveStatus", -1);
                int bIsLive = room.optInt("bIsLive", -1);
                boolean isLive = (liveStatus == 1 || bIsLive == 1 || onlineCount > 0);

                if (!isLive && onlineCount == 0) {
                    continue;
                }

                TogetherWatchRoom twRoom = new TogetherWatchRoom(roomId, profileRoomId, roomName, nickName,
                        coverUrl, onlineCount, categoryName);
                // 【2026-09-29 修复】带上主播 uid：toChannel() 生成 "huya://uid/<uid>"，
                //   同时把真 profileRoom 作为 huyaRoomId 传给播放层（自研解析用）。
                if (lUid > 0 && lUid != roomId) {
                    twRoom.huyaUid = lUid;
                }
                twRoom.isLive = isLive;
                rooms.add(twRoom);
            }
        }

        return rooms;
    }

    private List<TogetherWatchRoom> getFallbackRooms() {
        List<TogetherWatchRoom> rooms = new ArrayList<>();

        rooms.add(new TogetherWatchRoom(1394575534, 11342412, "【周星星】星爷经典不间断", "周星星", "", 5000, "怀旧老片"));
        rooms.add(new TogetherWatchRoom(1394575543, 11342421, "英叔护体 | 林正英搞笑僵尸系列", "7喜先生", "", 4500, "怀旧老片"));
        rooms.add(new TogetherWatchRoom(1524439855, 880261, "我摊牌啦 一起看热门大片", "虎牙八点档", "", 6000, "影视热播"));
        for (TogetherWatchRoom r : rooms) {
            r.isLive = true;
        }
        return rooms;
    }

    public void fetchTogetherWatchChannels(OnChannelsFetchedListener listener) {
        final long t0 = System.currentTimeMillis();
        fetchTogetherWatchRooms(new OnFetchListener() {
            @Override
            public void onPartial(List<TogetherWatchRoom> rooms) {
                if (rooms == null || rooms.isEmpty()) return;
                List<Channel> channels = new ArrayList<>();
                for (TogetherWatchRoom room : rooms) {
                    channels.add(room.toChannel());
                }
                LogBridge.i(TAG, "⏱️【链路-HTTP线路】增量批次 " + channels.size()
                        + " 频道，已用 " + (System.currentTimeMillis() - t0) + "ms");
                listener.onPartial(channels);
            }

            @Override
            public void onSuccess(List<TogetherWatchRoom> rooms) {
                long tParseStart = System.currentTimeMillis();
                List<Channel> channels = new ArrayList<>();
                for (TogetherWatchRoom room : rooms) {
                    channels.add(room.toChannel());
                }
                LogBridge.i(TAG, "⏱️【链路-HTTP线路】解析(toChannel) " + (System.currentTimeMillis() - tParseStart)
                        + "ms，房间=" + rooms.size() + " → 频道=" + channels.size());
                LogBridge.i(TAG, "⏱️【链路-HTTP线路】端到端总耗时 " + (System.currentTimeMillis() - t0)
                        + "ms（HTTP下载+JSON解析+关键词分类+toChannel）");
                listener.onSuccess(channels);
            }

            @Override
            public void onFailed(String errorMsg) {
                LogBridge.w(TAG, "⏱️【链路-HTTP线路】失败，总耗时 " + (System.currentTimeMillis() - t0) + "ms：" + errorMsg);
                listener.onFailed(errorMsg);
            }
        });
    }

    public List<Channel> loadCachedChannelsSync() {
        List<TogetherWatchRoom> rooms = (mRoomList != null && !mRoomList.isEmpty())
                ? mRoomList : loadFallbackFromDisk();
        if (rooms == null || rooms.isEmpty()) {
            rooms = getFallbackRooms();
        }
        List<Channel> channels = new ArrayList<>();
        if (rooms != null) {
            for (TogetherWatchRoom r : rooms) {
                if (r != null) channels.add(r.toChannel());
            }
        }
        return channels;
    }

    /**
     * 取播放地址：只走自研解析器（{@link com.tv.live.util.HuyaStreamPlayer.HuyaParser}）。
     */
    public void getPlayUrl(final int roomId, final OnPlayUrlListener listener) {
        com.tv.live.util.HuyaStreamPlayer.HuyaParser.parse(roomId,
                new com.tv.live.util.HuyaStreamPlayer.HuyaParser.OnParseResultListener() {
                    @Override
                    public void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch) {
                        // 虎牙 FLV 是短分片（实测 0.36s 就 EOF），故 HLS 优先
                        String playUrl = !TextUtils.isEmpty(hlsUrl) ? hlsUrl : flvUrl;
                        if (!TextUtils.isEmpty(playUrl)) {
                            listener.onSuccess(hlsUrl, flvUrl);
                        } else {
                            listener.onFailed("未获取到播放地址");
                        }
                    }

                    @Override
                    public void onFailed(String errorMsg) {
                        LogBridge.w(TAG, "【自研解析】getPlayUrl 失败: " + errorMsg);
                        listener.onFailed(errorMsg);
                    }
                });
    }

    private void postSuccess(OnFetchListener listener, List<TogetherWatchRoom> rooms) {
        mFetching.set(false);
        mMainHandler.post(() -> listener.onSuccess(rooms));
    }

    /** 【2026-09-24 提速】分类级增量回调，供面板"先展示后补齐" */
    private void postPartial(OnFetchListener listener, List<TogetherWatchRoom> rooms) {
        mMainHandler.post(() -> listener.onPartial(rooms));
    }

    private void postFailed(OnFetchListener listener, String msg) {
        mFetching.set(false);
        mMainHandler.post(() -> listener.onFailed(msg));
    }

    public void release() {
        mExecutor.shutdownNow();
        mCategoryExecutor.shutdownNow();
        mLongTailExecutor.shutdownNow();
        mLongTailFutures.clear();
        mRoomList.clear();
    }

    private File getFallbackFile() {
        if (mAppContext == null) return null;
        return new File(mAppContext.getFilesDir(), FALLBACK_FILE);
    }

    private void saveFallbackToDisk(List<TogetherWatchRoom> rooms) {
        File file = getFallbackFile();
        if (file == null || rooms == null || rooms.isEmpty()) return;
        // 【2026-09-29 修复】HTTP 线路撞 60s latch 超时只拿到部分房间（实测 167 个）时，
        // 不得用小数据覆盖此前累积的更大兜底文件。兜底只在网络全挂时启用，"多而稍旧"
        // 优于"少而新"：新数据不少于现有兜底才允许覆盖。
        try {
            List<TogetherWatchRoom> existing = loadFallbackFromDisk();
            if (existing != null && existing.size() > rooms.size()) {
                LogBridge.i(TAG, "【兜底】新数据(" + rooms.size() + ")少于现有兜底("
                        + existing.size() + ")，保留现有兜底不覆盖");
                return;
            }
        } catch (Throwable ignore) { }
        try {
            JSONArray arr = new JSONArray();
            for (TogetherWatchRoom r : rooms) {

                if (!r.isLive) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("rid", r.roomId);
                o.put("prid", r.profileRoom);
                o.put("rname", r.roomName == null ? "" : r.roomName);
                o.put("nname", r.nickName == null ? "" : r.nickName);
                o.put("cover", r.coverUrl == null ? "" : r.coverUrl);
                o.put("online", r.onlineCount);
                o.put("cat", r.category == null ? "" : r.category);
                o.put("live", r.isLive);
                arr.put(o);
            }
            byte[] data = arr.toString().getBytes("UTF-8");
            FileOutputStream fos = new FileOutputStream(file);
            try { fos.write(data); fos.flush(); } finally { fos.close(); }
            LogBridge.i(TAG, "【兜底】已写入永久兜底，房间数=" + rooms.size() + "，字节=" + data.length);
        } catch (Throwable t) {
            LogBridge.e(TAG, "【兜底】写入失败：" + t.getMessage());
        }
    }

    private List<TogetherWatchRoom> loadFallbackFromDisk() {
        File file = getFallbackFile();
        if (file == null || !file.exists() || file.length() <= 0) return null;
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            List<TogetherWatchRoom> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int rid = o.optInt("rid", 0);
                int prid = o.optInt("prid", rid);
                if (rid <= 0) continue;
                TogetherWatchRoom r = new TogetherWatchRoom(
                        rid, prid,
                        o.optString("rname", "精彩节目"),
                        o.optString("nname", "精彩节目"),
                        o.optString("cover", ""),
                        o.optInt("online", 0),
                        o.optString("cat", "")
                );
                r.isLive = o.optBoolean("live", true);

                if (!r.isLive) {
                    continue;
                }
                list.add(r);
            }
            return list;
        } catch (Throwable t) {
            LogBridge.e(TAG, "【兜底】读取失败：" + t.getMessage());
            return null;
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
        }
    }
}
