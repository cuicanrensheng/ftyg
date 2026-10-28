package com.tv.live.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.MyApplication;
import com.tv.live.util.LogBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class HuyaTogetherWatchGroupManager {

    private static final String TAG = "TogetherWatchGroup";

    private static volatile HuyaTogetherWatchGroupManager sInstance;

    private static final int SDK_TAG_TIMEOUT_SEC = 8;

    private static final int CONCURRENCY = 2;

    private static final String CACHE_FILE = "huya_sdk_groups_cache.json";

    private Context getAppContext() {
        return MyApplication.getInstance();
    }

    /** 【2026-10-03】清空 2135 列表缓存（所有页数档），下次 fetchAllGroups 强制重新拉取。 */
    public void invalidateLiveListCache() {
        synchronized (mListCacheLock) {
            mCachedLiveListByPages.clear();
            mCachedLiveListTsByPages.clear();
        }
    }

    /**
     * 【2026-10-03 提速】供第一路复用 2135 列表（60s 内命中则直接返回，不阻塞）。
     * <p>第一路原先自己走 OkHttp 串行翻 5 页（实测 2654ms，是全链路最慢单点）；第二路
     * 用 HttpURLConnection 拉同一份数据快得多。让第一路直接复用，省掉整段等待。</p>
     * <p>【2026-10-04】按页数分档缓存，本方法取「最大页数档」（即数据最全的那一份）给第一路。</p>
     *
     * @return 已缓存的列表；无缓存时返回 null（调用方自行回退到自己的拉取逻辑）
     */
    public List<com.tv.live.huya.HuyaLiveItem> peekSharedLiveList() {
        long now = System.currentTimeMillis();
        List<com.tv.live.huya.HuyaLiveItem> best = null;
        // 优先取页数最多的那一档（数据更全）
        for (int p = 2; p >= 1; p--) {
            List<com.tv.live.huya.HuyaLiveItem> c = mCachedLiveListByPages.get(p);
            Long ts = mCachedLiveListTsByPages.get(p);
            if (c != null && !c.isEmpty()
                    && now - (ts == null ? 0L : ts) < LIVE_LIST_CACHE_MS) {
                best = c;
                break;
            }
        }
        return best;
    }

    /**
     * 【2026-10-03 提速】供第一路主动触发一次列表拉取（不受 OkHttp 限流）。
     * 与 {@link #peekSharedLiveList()} 配合：第一路先 peek，未命中则调本方法拉取。
     * <p>【2026-10-04】默认拉 1 页（第一路不关心动漫/综艺的额外一页）。</p>
     */
    public List<com.tv.live.huya.HuyaLiveItem> getSharedLiveListBlocking() {
        return fetchAllLiveListShared(DEFAULT_LIST_PAGES);
    }

    public static final String GROUP_MOVIE    = "虎牙电影";
    public static final String GROUP_TV       = "虎牙电视剧";
    public static final String GROUP_ANIME    = "虎牙动漫";
    public static final String GROUP_VARIETY  = "虎牙综艺";
    /** 【2026-10-03 重新分组】兜底组：标题无任何作品名/题材词（如「我是一颗小虎牙」
     *  「欢迎来到我的直播间」）的房间。实测占 28.7%（303/1055），是数据本质决定的结果——
     *  这类标题压根没提作品，任何词典都归不了类，强行归组只会污染其他分组。 */
    public static final String GROUP_OTHER    = "虎牙其他";

    public static final String[] GROUP_NAMES = {
            GROUP_MOVIE, GROUP_TV, GROUP_ANIME, GROUP_VARIETY, GROUP_OTHER
    };

    // 【2026-10-03 重新分组】词库全量替换。
    // 病根：旧词库只有 4 个「品类词」每组十几个词，而 2135 语料 88% 的标题是
    //   「作品名 + 角色/演员」（如「雍正王朝」「士兵突击」「蜡笔小新」），
    //   64% 的标题一个品类词都不含 → 电影组靠「电影/娱乐」等泛词虚高到 190，
    //   电视剧/动漫/综艺分别只有 18/15/5，组间比 38:1。
    // 改法：基于 1055 条真实标题做词频挖掘，构建「作品名/角色名/演员名」为主的词库
    //   （共 532 个核心词，均在真实数据中命中过），并重排优先级。
    // 实测结果（1055 条）：动漫 122 / 综艺 66 / 电视剧 323 / 电影 239 / 其他 303；
    //   抽样准确率：电影 93.3% / 电视剧 100% / 动漫 100% / 综艺 86.7%（加权 95.2%）。
    // 词库来源与验证脚本：项目根目录 `_classify_report.md`、`_data/`。
    private static final String[][] GROUP_KEYWORDS = new String[][] {
            { GROUP_ANIME,   "动画,动漫,蜡笔小新,姜子牙,变身,动画电影,柯南,中华小当家,罗峰,猫和老鼠,美猴王,剧场版,国漫,仙逆,哆啦a梦,海绵宝宝,斗宗强者,完美世界,火影忍者,七龍珠,光头强,齐天大圣,七龙珠,超级赛亚人,网球王子,海贼王,四驱兄弟,龙珠超,路飞,全职高手,航海王,五条,特摄,樱桃小丸子,假面骑士,十刃,赛亚人,不良人,ko榜,斗气化马,魔法少女,十万伏特,猎人,石昊,全职猎人,剑来,奇迹的世代,铠甲勇士,5庚纪,圣魔大陆,天澜魔法高中,动画版,五条悟,古拉顿,小黄人,剑心,神龙斗士,动画片,怪盗基德,小当家,中二,林修,一念永恒,夏目友人,开挂之,防风少年,以武证道,齐神,妖神记,罗小黑,谁再说我是不可燃物,火影,遮天,钢之炼金术师,魔道祖师,唐三,打小怪兽,浦饭幽助,阿尼亚,影之实力者,头文字d,路明非,tong灵王,葫芦娃,雪鹰领主,食戟之灵,当哒当,炼气,足球小将,eva,高达,鲁路修,第一神拳" },
            { GROUP_VARIETY, "爆笑,沈腾,赵本山,宋小宝,跑男,综艺,乡村爱情,情景喜剧,杨迪,笑到,相声,鹊刀门,兴森一家,小品,白鹿,晚会,朱时茂,易中天品三国,美食视频,奔跑吧,现在就出发,乔杉,赵露思,刘小光,马东,街访show,鸡条,开始推理吧,大侦探,桃花坞,天赐,毛雪汪,贾玲,谢广坤,天天有喜,演技派,最强大脑,宋丹丹,欢乐喜剧人,美食分享,王小利,二十四小时,笑傲江湖,乐呵,高能少年团,笑点" },
            { GROUP_TV,      "电视剧,纪晓岚,雍正,士兵突击,抗日,神探狄仁杰,狄仁杰,新三国,家有儿女,胡八一,父母爱情,许三多,韦小宝,西游记,抗战,大秦,刑侦,谍战,上海滩,李卫当官,雍正王朝,康熙微服私访记,古灵精探,悟空,雪豹,僵尸道长,大江大河,令狐冲,创世纪,盗墓笔记,活佛济公,张无忌,武媚娘,楚留香,四大名捕,武则天,铁血,绝代双骄,曼达洛人,庆佘年,剧集,叶问,刘关张,庆yu年,王胖子,tvb,大背头,倩女幽魂,山基,黄飞鸿,水浒传,雍正皇帝,情满四合院,范闲,和珅,凤雏,看剧,桃园结义,魏璎珞,大棍子,康熙,火蓝刀锋,芈月传,刘星,苏大强,老农民,康熙大帝,雪见,西游,炊事班,大宋,一代枭雄,大秦帝国,上古,喜来乐,我爱男保姆,洗冤录,扫黑风暴,我的前半生,李淳罡,夏雪,睡在我上铺的兄弟,神貂侠侣,刘老根,风筝,繁花,铃铛,摸金校尉,唐朝,封神榜,陆小凤,王漫妮,楚汉骄雄,漫长的季节,小别离,刑事侦缉档案,白子画,寻秦记,隋唐英雄,你是我的荣耀,玫瑰的故事,还珠格格,洪武大案,蓝忘机,陀枪师姐,既生瑜何生亮,天龙八部,刘三好,鉴证实录,医疗剧,李卫,穆桂英挂帅,笑看风云,昆仑神宫,巾帼枭雄,大唐双龙传,宝lian灯,赳赳老秦,汉武大帝,外科风云,妙手仁心,乔家的儿女,案发现场,公主嫁到,开端,特战荣耀,步惊云,林依晨,宁缺,东北一家人,王大锤,金太狼,妈祖,湄公河大案,鸡毛飞上天,古潼京,在暴雪时分,一起同过窗,杨戬,终极一家,怒晴湘西,首尔之春,白眉大侠,武林,偶像剧,宫斗,檀健次,白风夕,与凤行,万人迷,laughing,隋唐演义,绝密生涯,城城,快乐星球,我的兄弟叫顺溜,陆贞传奇,谈判专家,扫黄先锋,红高粱,权力的游戏,拍档,神隐,使徒行者,大长今,真假千金,李寻欢,香蜜沉沉烬如霜,聂小倩,海豚湾恋人,大宋提刑官,连续剧,大院,天仙配,贞观,宋慈,剧版,帝君,公主小妹,大女主,犀利仁师,乡村剧,小夭,黄石,溏心风暴,赵祯,大明王朝,无心fa师,解放,陈道明,天涯明月刀,黄仙庙,安幼舆,身世,越狱,民调局异闻录,毒枭,吸血鬼日记,王子变青蛙,小龟,邢昭林,致命女人,飞虎,出轨,神探夏洛克,克拉恋人,心理罪,金枝欲孽,玫瑰之战,中国神探,黎明前的抉择,女子之道,哪吒,容嬷嬷,女主,浴血黑帮,爽剧,白素贞,有翡,欢喜哥,长孙无极,顶楼,逆水寒,男二,秀智,小欢喜,周翡,人民的名义,紫川,安多,雪山飞狐,沙僧,f4,laughing哥,法证,第五季,军营,三少爷的剑,中偶,军旅,三国,粉红女郎,禁忌女孩,画皮,怀玉公主,成长之路,黑斯廷斯,金智媛,周芷若,三毛流浪记,云海玉弓缘,张校长,从前有座灵剑山,太阳的后裔,顾晓梦,东游记,皇朝太医,侠客行" },
            { GROUP_MOVIE,   "电影,大片,港片,经典电影,林正英,动作片,电影合集,归来,僵尸,高分电影,喜剧电影,恐怖电影,方世玉,周星星,鬼片,影帝,漫威,速度与激情,名场面,无间道,僵尸片,张卫健,影视,意大利炮,动作电影,警匪片,犯罪电影,丧尸,盗墓,爱情电影,枪战,古天乐,恐怖片,我要打十个,超人,封神,少林寺,末日,釜山行,星爷,让一台冲锋车消失,黑社会,新僵尸先生,武侠片,警察故事,拳拳到肉,五亿探长雷洛传,前任3,疯狂的石头,来打三爷,加勒比海盗,王晶,泰迪熊,乌鸦,贺岁档,一口气看完,毛骗,山鸡哥,五福星,百鸟朝凤,犯罪片,一周不死,张曼玉,宝贝计划,金庸,宫崎骏,开心鬼,战争片,洪金宝,杰克船长,哈利波特,敢死队,巨石强森,闪灵,九阴真经,悬疑电影,28岁未成年,詹姆斯邦德,手中有剑,能过审是奇迹,澳洲之光,斗牛,经典之作,喜剧片,吴京,星际迷航,阿汤哥,鬼玩人系列,科幻电影,镖行天下,乌龙院,欧美,血浆,cult,b级片,林青霞,教父,阴阳路系列,蜘蛛侠,a计划,速看,超级教师,张震,黑夜传说,好莱坞,狄仁杰,孙俪,李连杰,小鬼当家,马男,太极宗师,拳王,博物馆奇妙夜,神话,漏洞百出,三更,释小龙,犯罪现场,有缘人,反转,钱小豪,剧情片,特种兵,人类清除计划,海难诅咒,杀人足球,兰博基尼,独当一面,命案,古惑仔,荒诞世界,永夜,哥斯拉" },
    };

    /** 【2026-10-03 重新分组】电影强信号。
     *  问题：作品名可剧可影时（「狄仁杰」「黄飞鸿」），组优先级会让它归电视剧，
     *  但标题若明说「电影/片」，用户心智里就是电影。
     *  规则：命中「虎牙电视剧」后，若标题含下列信号词 → 改判「虎牙电影」。 */
    private static final String[] MOVIE_STRONG_SIGNALS = {
            "电影", "影片", "影院", "大片", "剧场版", "片"
    };

    /** 【2026-10-03 重新分组】标题噪声词：命中这些说明标题无作品信息，应走兜底而非归组 */
    private static final String[] NOISE_TITLES = {
            "欢迎来到我的直播间", "我是一颗小虎牙", "一起看", "精彩直播", "精彩节目"
    };

    private static final int CATEGORY_ID_TOGETHER_WATCH = 2135;

    // 【2026-10-04 提速】词库预编译：原先 assignGroupByKeywords 在**每个房间**上都执行
    //   `row[1].split(",")`（每次分配新数组）+ `kw.trim()` + `k.toLowerCase(Locale.ROOT)`，
    //   全量 1200 房间 × 各组词库 ≈ 每轮 1200 次 split、数万次 toLowerCase。
    //   （分类跑两遍：fetchRecommendGroupedBlocking 一次 + 每个 tag 的 filterByGroup 一次 → 翻倍）
    //   现改为类初始化时一次性预拆分成小写数组，运行时只做 contains。
    private static final String[][] GROUP_KEYWORDS_LC = buildPrecompiledGroupKeywords();

    private static String[][] buildPrecompiledGroupKeywords() {
        String[][] out = new String[GROUP_KEYWORDS.length][];
        for (int i = 0; i < GROUP_KEYWORDS.length; i++) {
            String[] parts = GROUP_KEYWORDS[i][1].split(",");
            List<String> keep = new ArrayList<>(parts.length);
            for (String kw : parts) {
                String k = kw.trim().toLowerCase(Locale.ROOT);
                // 单字词（影/剧/魔…）误伤率极高，直接跳过（与旧逻辑一致）
                if (k.length() <= 1) continue;
                keep.add(k);
            }
            out[i] = keep.toArray(new String[0]);
        }
        return out;
    }

    // 【2026-10-25 起播保护②】MIN_PRIORITY：与第一路 HuyaTogetherWatchManager 同理，
    //   预加载线程不与解码器抢 CPU（默认优先级下实测造成 1~2s 连发微缓冲）。
    private final ExecutorService mExecutor =
            Executors.newFixedThreadPool(CONCURRENCY, r -> {
                Thread t = new Thread(r, "huya-grp-worker");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // 【2026-10-03 提速】2135 列表「一次拉取、多分组共享」缓存。
    // 病根：原先每个 TagSpec 各自跑 fetchHttpLiveListBlocking(0..9)，4 个分组 = 最多 40 次
    //   HTTP，真机日志实测同一 page 被拉了 4~5 遍（38 次重复请求）→ 第二路 8.1s。
    // 【2026-10-04 定案】默认只拉 page=0 一页；仅「虎牙动漫」「虎牙综艺」两组翻 2 页
    //   （见 TWO_PAGE_GROUPS）。缓存按页数分档（mCachedLiveListByPages），
    //   1 页档与 2 页档互不干扰。
    private static final long LIVE_LIST_CACHE_MS = 60 * 1000L;

    /** 【2026-10-04】需要翻 2 页的分组（其余分组只翻 1 页）。 */
    private static final java.util.Set<String> TWO_PAGE_GROUPS =
            new java.util.HashSet<>(java.util.Arrays.asList(GROUP_ANIME, GROUP_VARIETY));

    /** 默认拉取页数（未在 {@link #TWO_PAGE_GROUPS} 中的分组走这个）。 */
    private static final int DEFAULT_LIST_PAGES = 1;

    /** 需要翻 2 页的分组所用的页数（也是推荐列表要覆盖的最大页数）。 */
    private static final int MAX_LIST_PAGES = 2;

    /** 是否为「动漫/综艺」这类需要多翻一页的分组。 */
    private static int pagesForGroup(String groupName) {
        return (groupName != null && TWO_PAGE_GROUPS.contains(groupName)) ? MAX_LIST_PAGES : DEFAULT_LIST_PAGES;
    }

    private final Object mListCacheLock = new Object();
    // 按页数分档缓存：key=页数，value=[房间列表]
    private final Map<Integer, List<com.tv.live.huya.HuyaLiveItem>>
            mCachedLiveListByPages = new java.util.HashMap<>();
    private final Map<Integer, Long> mCachedLiveListTsByPages = new java.util.HashMap<>();

    // 【2026-09-29 修复】此前 fetchGroup/fetchAllGroups 共用一个 mFetching 原子锁：
    // 任一请求在途时，后续 fetchGroup 会被立即拒绝（"正在获取中，请稍候"）且调用方不重试，
    // fetchAllGroups 则被静默丢弃。真机实测（18:18）：用户快速连选 虎牙电影→虎牙电视剧→
    // 虎牙动漫→虎牙综艺，只有第一个分组能加载，其余分组永远空白。
    // 现改为"排队闸门"：在途时后续请求排队，当前请求完成后依次执行。
    private static final int MAX_PENDING_FETCH = 6;
    private final Object mGateLock = new Object();
    private boolean mGateBusy = false;
    private final ArrayDeque<GateTask> mPendingFetches = new ArrayDeque<>();

    /** 一次受闸门调度的抓取任务；onDrop 在排队溢出被淘汰时回调（向调用方报错） */
    private interface GateTask {
        void run();

        void onDrop();
    }

    private void submitThroughGate(final GateTask task) {
        final Runnable wrapped = () -> {
            try {
                task.run();
            } finally {
                dispatchNextFetch();
            }
        };
        boolean runNow = false;
        GateTask evicted = null;
        synchronized (mGateLock) {
            if (!mGateBusy) {
                mGateBusy = true;
                runNow = true;
            } else {
                if (mPendingFetches.size() >= MAX_PENDING_FETCH) {
                    evicted = mPendingFetches.removeFirst();
                }
                mPendingFetches.addLast(task);
            }
        }
        if (evicted != null) {
            evicted.onDrop();
        }
        if (runNow) mExecutor.execute(wrapped);
    }

    /** 当前抓取结束后取下一个排队任务；队列空则释放闸门（含置空瞬间的竞态兜底） */
    private void dispatchNextFetch() {
        Runnable next = null;
        synchronized (mGateLock) {
            GateTask t = mPendingFetches.pollFirst();
            if (t != null) {
                next = () -> {
                    try {
                        t.run();
                    } finally {
                        dispatchNextFetch();
                    }
                };
            } else {
                mGateBusy = false;
            }
        }
        if (next != null) mExecutor.execute(next);
    }

    private HuyaTogetherWatchGroupManager() {
    }

    public static HuyaTogetherWatchGroupManager getInstance() {
        if (sInstance == null) {
            synchronized (HuyaTogetherWatchGroupManager.class) {
                if (sInstance == null) {
                    sInstance = new HuyaTogetherWatchGroupManager();
                }
            }
        }
        return sInstance;
    }

    public interface OnGroupsListener {

        void onSuccess(List<Group> groups);

        void onError(String errMsg);
    }

    public interface OnChannelsListener {
        void onSuccess(List<Channel> channels);

        void onError(String errMsg);
    }

    public static class Group {
        public final String name;
        public final List<Channel> channels = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        public boolean isEmpty() {
            return channels.isEmpty();
        }
    }

    public List<Group> loadCachedGroupsSync() {
        return loadGroupsFromDisk();
    }

    public void fetchAllGroups(final OnGroupsListener listener) {
        if (listener == null) return;
        // 第二路走自研 HTTP 取 2135；先返回缓存（瞬时），后台再刷新
        List<Group> cached = loadGroupsFromDisk();
        if (cached != null && !cached.isEmpty()) {
            postOnSuccess(listener, cached);
        }
        submitThroughGate(new GateTask() {
            @Override public void run() {
                final long t0 = System.currentTimeMillis();
                try {
                    List<Group> result = doFetchAllGroupsBlocking();
                    if (result != null) {
                        saveGroupsToDisk(result);
                        int total = 0;
                        for (Group g : result) { if (g != null) total += g.channels.size(); }


                        postOnSuccess(listener, result);
                    } else {
                        postOnError(listener, "获取分组失败（SDK 分类列表为空或超时）");
                    }
                } catch (Throwable t) {
                    postOnError(listener, "获取分组异常");
                }
            }
            @Override public void onDrop() {
            }
        });
    }

    public void fetchGroup(final String groupName, final OnChannelsListener listener) {
        if (listener == null) return;
        if (!isValidGroupName(groupName)) {
            postOnError(listener, "未知分组: " + groupName);
            return;
        }
        submitThroughGate(new GateTask() {
            @Override public void run() {
                try {
                    List<Channel> channels = doFetchGroupBlocking(groupName);
                    if (channels == null) {
                        postOnError(listener, "获取分组失败（分类列表为空或超时）");
                    } else {
                        postOnSuccess(listener, channels);
                    }
                } catch (Throwable t) {
                    postOnError(listener, "获取分组异常");
                }
            }
            @Override public void onDrop() {
                postOnError(listener, "虎牙请求排队已满，请稍后重试");
            }
        });
    }

    private List<Group> doFetchAllGroupsBlocking() throws Exception {
        long tTag = System.currentTimeMillis();
        Map<String, List<TagSpec>> byGroup = resolveSDKTagSpecsGrouped();
        if (byGroup == null) return null;

        long tRec = System.currentTimeMillis();
        Map<String, List<Channel>> recommend = fetchRecommendGroupedBlocking();

        // 【2026-10-25 起播保护】原来 5 个分组一把并发（GROUP_NAMES.length）。
        //   叠加每条线路内部 2 并发 → 启动瞬间十几个请求同时压上来，把播放器的 5.3MB 分片挤到饥饿，
        //   实测启动后 10s 内反复缓冲 83 次。改为一次只跑 2 个分组，总并发受控。
        // 【2026-10-25 起播保护②】线程 MIN_PRIORITY：收尾阶段的 600 房间 JSON 解析/分类
        //   仍在默认优先级上挤解码器，实测预加载发车后还有 1~2 波 0.2~0.3s 的顿挫。
        ExecutorService groupPool = Executors.newFixedThreadPool(Math.min(2, GROUP_NAMES.length), r -> {
            Thread t = new Thread(r, "huya-grp-group");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        try {
            List<Future<Group>> groupFutures = new ArrayList<>();
            for (final String gName : GROUP_NAMES) {
                groupFutures.add(groupPool.submit(new Callable<Group>() {
                    @Override public Group call() {
                        Group g = new Group(gName);
                        try {
                            List<TagSpec> tags = byGroup.get(gName);
                            if (tags != null && !tags.isEmpty()) {
                                fetchChannelsIntoGroup(tags, g);
                            }

                            if (recommend != null) {
                                mergeChannels(g.channels, recommend.get(gName));
                            }
                        } catch (Throwable t) {
                        }
                        return g;
                    }
                }));
            }
            List<Group> groups = new ArrayList<>();
            for (Future<Group> f : groupFutures) {
                try {
                    groups.add(f.get(SDK_TAG_TIMEOUT_SEC + 5, TimeUnit.SECONDS));
                } catch (Throwable t) {
                }
            }
            return groups;
        } finally {
            groupPool.shutdownNow();
        }
    }

    private List<Channel> doFetchGroupBlocking(String groupName) throws Exception {
        Map<String, List<TagSpec>> byGroup = resolveSDKTagSpecsGrouped();
        if (byGroup == null) return null;

        Group g = new Group(groupName);
        List<TagSpec> tags = byGroup.get(groupName);
        if (tags != null && !tags.isEmpty()) {
            fetchChannelsIntoGroup(tags, g);
        }

        Map<String, List<Channel>> recommend = fetchRecommendGroupedBlocking();
        if (recommend != null) {
            mergeChannels(g.channels, recommend.get(groupName));
        }
        return g.channels;
    }

    private void fetchChannelsIntoGroup(List<TagSpec> tags, final Group g) {
        List<Future<List<Channel>>> futures = new ArrayList<>();
        for (TagSpec t : tags) {
            futures.add(mExecutor.submit(new Callable<List<Channel>>() {
                @Override public List<Channel> call() {
                    return fetchTagBlocking(t);
                }
            }));
        }
        LinkedHashSet<Integer> seenRoom = new LinkedHashSet<>();
        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (Future<List<Channel>> f : futures) {
            try {
                List<Channel> chs = f.get(SDK_TAG_TIMEOUT_SEC + 2, TimeUnit.SECONDS);
                if (chs == null || chs.isEmpty()) continue;
                for (Channel c : chs) {
                    if (c == null) continue;
                    boolean dup;
                    if (c.getHuyaRoomId() > 0) {
                        dup = !seenRoom.add(c.getHuyaRoomId());
                    } else {
                        dup = !seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
                    }
                    if (!dup) g.channels.add(c);
                }
            } catch (Throwable ignored) {

            }
        }
    }

    private static void mergeChannels(List<Channel> target, List<Channel> extra) {
        if (extra == null || extra.isEmpty()) return;
        LinkedHashSet<Integer> seenRoom = new LinkedHashSet<>();
        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (Channel c : target) {
            if (c == null) continue;
            if (c.getHuyaRoomId() > 0) seenRoom.add(c.getHuyaRoomId());
            else seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
        }
        for (Channel c : extra) {
            if (c == null) continue;
            boolean dup;
            if (c.getHuyaRoomId() > 0) {
                dup = !seenRoom.add(c.getHuyaRoomId());
            } else {
                dup = !seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
            }
            if (!dup) target.add(c);
        }
    }

    // ==================== 【2026-10-03 第二路：自研 HTTP 线路，不再依赖真 SDK】====================
    // 直接用 HTTP 拉「虎牙一起看(2135)」，结果填进 HuyaLiveItem 的 public 字段，
    // 下游 buildChannelFromInfo()/buildChannels() 一行都不用改。

    private static final String API_HTTP_LIVE_LIST =
            "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=";

    private static final int HTTP_LIST_TIMEOUT_MS = 8000;

    private static final String HTTP_UA =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    /** 自研 HTTP 拉一页 2135 列表，返回填好 public 字段的 HuyaLiveItem */
    private List<com.tv.live.huya.HuyaLiveItem> fetchHttpLiveListBlocking(int page) {
        List<com.tv.live.huya.HuyaLiveItem> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_HTTP_LIVE_LIST + page + "&_=" + System.currentTimeMillis());
            // 【2026-10-25 代理坑修复】绕过系统代理（失效代理会让第二路列表整体超时）
            conn = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            conn.setConnectTimeout(HTTP_LIST_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_LIST_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", HTTP_UA);
            conn.setRequestProperty("Referer", "https://www.huya.com/");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code != 200) {
                return out;
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();

            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            JSONObject data = root.optJSONObject("data");
            JSONArray datas = data == null ? null : data.optJSONArray("datas");
            if (datas == null) {
                return out;
            }
            for (int i = 0; i < datas.length(); i++) {
                JSONObject room = datas.optJSONObject(i);
                if (room == null) continue;
                // 【坑】datas[] 里没有 roomId / roomNo 键，房间号在 profileRoom（roomId 字符串）
                long roomId = 0;
                try {
                    roomId = Long.parseLong(room.optString("profileRoom", "").trim());
                } catch (Exception ignored) {
                }
                if (roomId <= 0) continue;
                com.tv.live.huya.HuyaLiveItem info =
                        new com.tv.live.huya.HuyaLiveItem();
                info.channelId = roomId;
                info.subId = 0;
                // uid 保持 0 → 下游生成 huya://room/<roomId>，走自研解析器按房间号取流
                info.uid = 0;
                info.title = room.optString("roomName", "");
                if (TextUtils.isEmpty(info.title)) {
                    info.title = room.optString("introduction", "");
                }
                info.nickName = room.optString("nick", "");
                info.coverUrl = room.optString("screenshot", "");
                info.gameId = room.optInt("gid", 0);
                out.add(info);
            }
        } catch (Throwable t) {
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** 按分组关键词过滤自研 HTTP 拉回来的房间（等价于原来「按 tagId 拉该分组专属房间」） */
    private List<com.tv.live.huya.HuyaLiveItem> filterByGroup(
            List<com.tv.live.huya.HuyaLiveItem> src, String groupName) {
        List<com.tv.live.huya.HuyaLiveItem> out = new ArrayList<>();
        if (src == null || src.isEmpty()) return out;
        for (com.tv.live.huya.HuyaLiveItem info : src) {
            if (info == null) continue;
            String g = assignGroupByKeywords(safeStr(info.title), safeStr(info.nickName));
            if (g != null && g.equals(groupName)) out.add(info);
        }
        return out;
    }

    /**
     * 【2026-10-04 定案】拉取 2135 列表，带 60s 内存缓存。
     * <p>默认只翻**第 1 页**（page=0）；「虎牙动漫」「虎牙综艺」两组翻 **2 页**（page=0,1）。</p>
     * <p>原先 {@link #fetchRecommendGroupedBlocking()} 与每个 {@code TagSpec} 的
     * {@link #fetchTagBlocking} 都各自从头翻页，导致同一份 page=0..9 被重复拉 4~5 遍
     * （真机日志实测 38 次重复 page 请求）。这里统一收敛为「一次拉取、多消费者共享」。</p>
     * <p>【2026-10-04】把「翻 10 页」降为「1 页 / 2 页」——翻页要 ~1.5~2.0s（2 波 RTT），
     * 且多页合并存在服务端偶发空页导致误截断的隐患。现在按分组需要的最小页数拉取。</p>
     * <p>并发安全：用 {@code mListCacheLock} + 双重检查，首个进入者拉取，其余线程等待同一结果；
     * 拉取失败不写缓存（下次可重试），成功结果缓 60s。<b>缓存按页数分档</b>，
     * 1 页档与 2 页档互不覆盖（否则先加载 1 页组会把 2 页档的数据冲掉）。</p>
     *
     * @param pages 期望页数（≤1 只拉 page=0；=2 拉 page=0,1）
     */
    private List<com.tv.live.huya.HuyaLiveItem> fetchAllLiveListShared(int pages) {
        final int wantPages = pages < 2 ? 1 : 2;
        long now = System.currentTimeMillis();
        List<com.tv.live.huya.HuyaLiveItem> cached =
                mCachedLiveListByPages.get(wantPages);
        Long ts = mCachedLiveListTsByPages.get(wantPages);
        long age = now - (ts == null ? 0L : ts);
        if (cached != null && !cached.isEmpty() && age < LIVE_LIST_CACHE_MS) {
            return cached;
        }
        synchronized (mListCacheLock) {
            now = System.currentTimeMillis();
            cached = mCachedLiveListByPages.get(wantPages);
            ts = mCachedLiveListTsByPages.get(wantPages);
            age = now - (ts == null ? 0L : ts);
            if (cached != null && !cached.isEmpty() && age < LIVE_LIST_CACHE_MS) {
                return cached;
            }
            long t0 = System.currentTimeMillis();
            // 【2026-10-04 修正】该接口分页是 **1-based**：响应里明确带 page/pageSize/totalPage/totalCount
            //   （实测 totalPage=10, totalCount=1112, pageSize=120），且请求 page=0 会被服务端归一化成 page=1，
            //   导致 page=0 与 page=1 返回**完全相同的 120 个房间**（交集=120）→ 房间被重复计数
            //   （表现为「动漫 24 个 / 综艺 18 个」，实际只有 12 / 9 个）。
            //   故这里从 1 开始翻页。不做「首个不满页就停」的判定（服务端会偶发中间页返回 0 条 → 误截断），
            //   页数完全由分组需求决定，行为可预期。
            List<com.tv.live.huya.HuyaLiveItem> all = new ArrayList<>();
            // 去重兜底：即便某页与前页重叠（服务端调整分页、或再次出现 page=0/1 归一化），
            // 也不会把同一房间重复计入分组。键取 uid 优先、其次 channelId（与 fetchRecommendGroupedBlocking 同口径）。
            LinkedHashSet<Long> seenRoomKeys = new LinkedHashSet<>();
            int rawCount = 0;
            for (int p = 1; p <= wantPages; p++) {
                List<com.tv.live.huya.HuyaLiveItem> pl = fetchHttpLiveListBlocking(p);
                if (pl == null || pl.isEmpty()) continue;
                rawCount += pl.size();
                for (com.tv.live.huya.HuyaLiveItem info : pl) {
                    if (info == null) continue;
                    long key = info.uid > 0 ? info.uid : info.channelId;
                    if (key > 0 && !seenRoomKeys.add(key)) continue;   // 重复房间，跳过
                    all.add(info);
                }
            }
            long cost = System.currentTimeMillis() - t0;
            if (!all.isEmpty()) {
                mCachedLiveListByPages.put(wantPages, all);
                mCachedLiveListTsByPages.put(wantPages, System.currentTimeMillis());
            } else {
            }
            return all;
        }
    }

    private Map<String, List<Channel>> fetchRecommendGroupedBlocking() {
        // 【2026-10-03 提速】改为复用共享列表（原先这里自己又翻了一遍 10 页）
        // 【2026-10-04】这里一次要给 5 个分组都分好类，故取「最大页数档」(2 页)，
        //   否则动漫/综艺的第 2 页数据在推荐列表里会缺。
        List<com.tv.live.huya.HuyaLiveItem> shared =
                fetchAllLiveListShared(MAX_LIST_PAGES);
        List<com.tv.live.huya.HuyaLiveItem> allList = new ArrayList<>();
        if (shared != null) {
            LinkedHashSet<Long> seenUids = new LinkedHashSet<>();
            for (com.tv.live.huya.HuyaLiveItem info : shared) {
                if (info == null) continue;
                long key = info.uid > 0 ? info.uid : info.channelId;
                if (key > 0 && seenUids.add(key)) allList.add(info);
            }
        }
        if (allList.isEmpty()) return null;

        Map<String, List<Channel>> grouped = new LinkedHashMap<>();
        for (String g : GROUP_NAMES) grouped.put(g, new ArrayList<Channel>());
        for (com.tv.live.huya.HuyaLiveItem info : allList) {
            try {

                if (info.gameId != 0 && info.gameId != CATEGORY_ID_TOGETHER_WATCH) continue;
                String title = safeStr(info.title);
                String nick = safeStr(info.nickName);
                if (TextUtils.isEmpty(title) && TextUtils.isEmpty(nick)) continue;
                String display = TextUtils.isEmpty(title) ? nick : title;
                if ("精彩直播".equals(display) || "精彩节目".equals(display)) continue;
                String gName = assignGroupByKeywords(title, nick);
                // 【2026-10-03 重新分组】assignGroupByKeywords 现在恒返回非 null（兜底 GROUP_OTHER）
                if (gName == null) gName = GROUP_OTHER;
                Channel ch = buildChannelFromInfo(info, gName);
                if (ch == null) continue;
                List<Channel> bucket = grouped.get(gName);
                if (bucket == null) bucket = grouped.get(GROUP_OTHER);
                if (bucket != null) bucket.add(ch);
            } catch (Throwable ignored) {  }
        }
        int total = 0;
        for (List<Channel> v : grouped.values()) total += v.size();
        if (total == 0) return null;
        return grouped;
    }

    /**
     * 【2026-10-03 重新分组】按「作品名/角色名/演员名」为主的词库给房间分类。
     * <p>流程：标题命中词库 → 归类（含电影强信号覆盖）→ 都不命中 → {@link #GROUP_OTHER} 兜底。</p>
     * <p>组优先级（{@link #GROUP_KEYWORDS} 数组顺序）：动漫 &gt; 综艺 &gt; 电视剧 &gt; 电影，
     * 按「内容排他性」排序——「柯南剧场版」既是动画又是电影，归动漫更符合用户心智。</p>
     * <p>注意：本频道标题多数是剧情剧透/情绪表达（如「家人们我笑麻了」），
     * 不再用主播名做主判据——带「说剧/影视」后缀的主播什么都播，直接归类会大面积误判。</p>
     */
    private static String assignGroupByKeywords(String title, String nick) {
        String t = safeStr(title);
        String low = (t + " " + safeStr(nick)).toLowerCase(Locale.ROOT);

        // 标题是纯噪声（无作品信息）→ 直接兜底，避免被昵称里的泛词误伤
        String tl = t.trim();
        for (String noise : NOISE_TITLES) {
            if (noise.equals(tl)) return GROUP_OTHER;
        }

        // 【2026-10-04 提速】改用预编译小写词表（GROUP_KEYWORDS_LC），运行时零 split/trim/toLowerCase
        for (int i = 0; i < GROUP_KEYWORDS_LC.length; i++) {
            String gName = GROUP_KEYWORDS[i][0];
            for (String k : GROUP_KEYWORDS_LC[i]) {
                if (low.contains(k)) {
                    // 电影强信号覆盖：先命中电视剧但标题明说「电影/片」→ 改判电影
                    if (GROUP_TV.equals(gName) && hasMovieStrongSignal(t)) {
                        return GROUP_MOVIE;
                    }
                    return gName;
                }
            }
        }
        return GROUP_OTHER;
    }

    /** 标题是否含强电影信号（「电影/影片/影院/大片/剧场版/片」） */
    private static boolean hasMovieStrongSignal(String title) {
        String t = safeStr(title);
        if (t.isEmpty()) return false;
        for (String sig : MOVIE_STRONG_SIGNALS) {
            if (t.contains(sig)) return true;
        }
        return false;
    }

    private static Channel buildChannelFromInfo(com.tv.live.huya.HuyaLiveItem info, String groupName) {
        try {
            long channelId = info.channelId;
            long subId = info.subId;
            long uid = info.uid;
            if (channelId <= 0) return null;
            String title = safeStr(info.title);
            String nick = safeStr(info.nickName);
            String display = TextUtils.isEmpty(title)
                    ? (TextUtils.isEmpty(nick) ? "精彩节目" : nick) : title;
            if ("精彩直播".equals(display) || "精彩节目".equals(display)
                    || "精彩直播".equals(nick) || "精彩节目".equals(nick)) {
                return null;
            }
            long realProfileRoom = subId > 0 ? subId : channelId;
            int profileRoom = (realProfileRoom > 0 && realProfileRoom <= Integer.MAX_VALUE)
                    ? (int) realProfileRoom : 0;
            String channelIdStr;
            if (uid > 0) channelIdStr = "huya_uid_" + uid;
            else if (profileRoom > 0) channelIdStr = "huya_" + profileRoom;
            else channelIdStr = "huya_long_" + channelId;
            // 【2026-10-25 短名接入】按 profileRoom 查第二路短名表：
            // 命中用「类型_内容」短名（TV 面板放得下），未命中回落虎牙原名。
            // 第二路频道实时上下线，映射表天然是「快照 + 兜底」，新房间走原名属正常。
            String displayName = HuyaTogetherWatchGroupShortNames.of(profileRoom);
            if (TextUtils.isEmpty(displayName)) displayName = display;
            Channel ch;
            if (uid > 0) {
                ch = new Channel(displayName, "huya://uid/" + uid, groupName, channelIdStr, true, profileRoom);
                ch.setHuyaUid(uid);
            } else if (profileRoom > 0) {
                ch = new Channel(displayName, "huya://room/" + profileRoom, groupName, channelIdStr, true, profileRoom);
            } else {
                return null;
            }
            // 【2026-09-29 频道台标】写入虎牙封面图，供 OTT 首页卡片加载
            String cover = safeStr(info.coverUrl);
            if (!TextUtils.isEmpty(cover)) {
                ch.setLogoUrl(cover);
            }
            ch.setHuyaSdkTogetherWatch(true);
            return ch;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<Channel> fetchTagBlocking(final TagSpec tag) {

        final long tTagStart = System.currentTimeMillis();

        // 【2026-10-03】原两条 SDK 路径（getLiveListByTagRaw 手动游标 + getLiveListByTag 官方翻页）
        // 统一换成自研 HTTP 拉 2135 列表，再按分组关键词过滤出本组房间。
        // 【2026-10-03 提速】改为共享缓存：列表只拉一次，多分组复用，不再各自翻 10 页。
        // 【2026-10-04】按分组决定页数：动漫/综艺翻 2 页，其余翻 1 页。
        final int pages = pagesForGroup(tag.groupName);
        List<com.tv.live.huya.HuyaLiveItem> allList = fetchAllLiveListShared(pages);
        if (allList != null && !allList.isEmpty()) {
            List<com.tv.live.huya.HuyaLiveItem> filtered = filterByGroup(allList, tag.groupName);
            if (!filtered.isEmpty()) {
                List<Channel> r = buildChannels(tag, filtered);
                return r;
            }
        }

        // 【2026-10-03 重新分组】去掉「过滤为空就把全量塞进本组」的兜底。
        // 旧逻辑在 4 组泛词时代还算合理（怕某组空），但现在词库精确、且新增了兜底组
        // 「虎牙其他」，若某组真的没内容（如「虎牙综艺」很少），全量回填会让同一批房间
        // 在 5 个组里重复出现，用户上下切换分组会看到大量重复频道。
        // 正确做法：本组过滤为空就返回 null，UI 侧显示「本组暂无频道」。

        return null;
    }

    private List<Channel> buildChannels(final TagSpec tag,
                                        List<com.tv.live.huya.HuyaLiveItem> allList) {
        List<Channel> result = new ArrayList<>();
        for (com.tv.live.huya.HuyaLiveItem info : allList) {
            try {
                long channelId = info.channelId;
                long subId = info.subId;
                long uid = info.uid;
                if (channelId <= 0) continue;
                String title = safeStr(info.title);
                String nick = safeStr(info.nickName);
                String display = (TextUtils.isEmpty(title)
                        ? (TextUtils.isEmpty(nick) ? "精彩节目" : nick) : title);

                int roomId = (channelId > 0 && channelId <= Integer.MAX_VALUE) ? (int) channelId : 0;
                long realProfileRoom = subId > 0 ? subId : channelId;
                int profileRoom = (realProfileRoom > 0 && realProfileRoom <= Integer.MAX_VALUE) ? (int) realProfileRoom : 0;

                if (TextUtils.isEmpty(display)
                        || "精彩直播".equals(display) || "精彩节目".equals(display)
                        || "精彩直播".equals(nick) || "精彩节目".equals(nick)) {
                    continue;
                }

                String channelIdStr;
                if (uid > 0) channelIdStr = "huya_uid_" + uid;
                else if (roomId > 0) channelIdStr = "huya_" + roomId;
                else channelIdStr = "huya_long_" + channelId;
                // 【2026-10-25 短名接入】同 buildChannelFromInfo：按 profileRoom 查表，
                // 命中用短名，未命中回落原名。
                String displayName = HuyaTogetherWatchGroupShortNames.of(profileRoom);
                if (TextUtils.isEmpty(displayName)) displayName = display;
                Channel ch;
                if (uid > 0) {

                    ch = new Channel(displayName, "huya://uid/" + uid,
                            tag.groupName, channelIdStr, true, profileRoom);
                    ch.setHuyaUid(uid);
                } else if (profileRoom > 0) {

                    ch = new Channel(displayName, "huya://room/" + profileRoom,
                            tag.groupName, channelIdStr, true, profileRoom);
                } else {
                    continue;
                }

                // 【2026-09-29 频道台标】写入虎牙封面图，供 OTT 首页卡片加载
                String cover = safeStr(info.coverUrl);
                if (!TextUtils.isEmpty(cover)) {
                    ch.setLogoUrl(cover);
                }
                ch.setHuyaSdkTogetherWatch(true);
                result.add(ch);
            } catch (Throwable ignored) {

            }
        }

        logLogoCoverage(tag.tagName, result);
        return result;
    }

    private Map<String, List<TagSpec>> resolveSDKTagSpecsGrouped() {
        List<TagSpec> specs = resolveSDKTagSpecsBlocking();
        if (specs == null) return null;

        Map<String, List<TagSpec>> byGroup = new LinkedHashMap<>();
        for (String gName : GROUP_NAMES) {
            byGroup.put(gName, new ArrayList<TagSpec>());
        }
        for (TagSpec t : specs) {
            List<TagSpec> list = byGroup.get(t.groupName);
            if (list != null) list.add(t);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<TagSpec>> e : byGroup.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue().size()).append(' ');
        }
        return byGroup;
    }

    /** 【2026-10-03 第二路切自研 HTTP】分组规格不再从真 SDK getTagList 取，
     *  直接由 GROUP_NAMES（虎牙电影/电视剧/动漫/综艺）生成，第二路彻底不依赖 SDK init。
     *  tagId 只作标识用（fetchTagBlocking 已改为按分组关键词过滤，不再按 tagId 拉流）。 */
    private List<TagSpec> resolveSDKTagSpecsBlocking() {
        List<TagSpec> result = new ArrayList<>();
        for (int i = 0; i < GROUP_NAMES.length; i++) {
            String groupName = GROUP_NAMES[i];
            result.add(new TagSpec(groupName, groupName, groupName));
        }
        return result;
    }

    private static String matchGroupName(String tagName) {
        if (TextUtils.isEmpty(tagName)) return null;
        String n = tagName.trim();
        String low = n.toLowerCase(Locale.ROOT);

        // 【2026-10-03 重新分组】tagName 已是 GROUP_NAMES 里的正式分组名，直接命中
        for (String g : GROUP_NAMES) {
            if (g.equals(n)) return g;
        }
        if ("全部".equals(n)) return GROUP_MOVIE;   // 兼容旧口径
        if ("最新".equals(n)) return GROUP_TV;
        if ("up".equals(low)) return GROUP_ANIME;

        // 兜底：按词库匹配（与 assignGroupByKeywords 同口径）
        return assignGroupByKeywords(n, "");
    }


    private static boolean isValidGroupName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        for (String g : GROUP_NAMES) {
            if (g.equals(name)) return true;
        }
        return false;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    /** 【2026-09-29 台标】统计一组频道里台标覆盖率，用于上线后观测台标是否断流（低频、每分组一次） */
    private static void logLogoCoverage(String where, List<Channel> list) {
        if (list == null || list.isEmpty()) return;
        int withLogo = 0;
        for (Channel c : list) {
            if (c != null && !TextUtils.isEmpty(c.getLogoUrl())) withLogo++;
        }
    }

    private static boolean isNumericString(String s) {
        if (TextUtils.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private void postOnSuccess(final OnGroupsListener listener, final List<Group> groups) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onSuccess(groups);
            }
        });
    }

    private void postOnError(final OnGroupsListener listener, final String err) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onError(err);
            }
        });
    }

    private void postOnSuccess(final OnChannelsListener listener, final List<Channel> channels) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onSuccess(channels);
            }
        });
    }

    private void postOnError(final OnChannelsListener listener, final String err) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onError(err);
            }
        });
    }

    // ===== 本地永久缓存（与 HTTP 线路一致）=====

    private File getCacheFile() {
        Context ctx = getAppContext();
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), CACHE_FILE);
    }

    private void saveGroupsToDisk(List<Group> groups) {
        File file = getCacheFile();
        if (file == null || groups == null || groups.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (Group g : groups) {
                if (g == null || g.channels.isEmpty()) continue;
                JSONObject go = new JSONObject();
                go.put("name", g.name);
                JSONArray carr = new JSONArray();
                for (Channel ch : g.channels) {
                    if (ch == null) continue;
                    JSONObject co = new JSONObject();
                    co.put("name", safeStr(ch.getName()));
                    co.put("url", safeStr(ch.getMainPlayUrl()));
                    co.put("group", safeStr(ch.getGroup()));
                    co.put("cid", safeStr(ch.getChannelId()));
                    co.put("rid", ch.getHuyaRoomId());
                    co.put("uid", ch.getHuyaUid());
                    co.put("sdk", ch.isHuyaSdkTogetherWatch());
                    // 【2026-09-29 频道台标】台标 URL 一并持久化，否则下次启动走缓存时台标全部为空
                    co.put("logo", safeStr(ch.getLogoUrl()));
                    carr.put(co);
                }
                go.put("channels", carr);
                arr.put(go);
            }
            byte[] data = arr.toString().getBytes("UTF-8");
            FileOutputStream fos = new FileOutputStream(file);
            try { fos.write(data); fos.flush(); } finally { fos.close(); }
            int total = 0;
            for (Group g : groups) {
                if (g != null) {
                    total += g.channels.size();

                }
            }
        } catch (Throwable t) {
        }
    }

    private List<Group> loadGroupsFromDisk() {
        File file = getCacheFile();
        if (file == null || !file.exists() || file.length() <= 0) return null;
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            List<Group> groups = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject go = arr.getJSONObject(i);
                Group g = new Group(go.optString("name", ""));
                JSONArray carr = go.optJSONArray("channels");
                if (carr != null) {
                    for (int j = 0; j < carr.length(); j++) {
                        JSONObject co = carr.getJSONObject(j);
                        int rid = co.optInt("rid", 0);
                        long uid = co.optLong("uid", 0L);
                        // 【2026-10-25 短名接入】缓存里存的是写缓存那一刻的显示名。
                        // 旧版本写下的缓存是长名，若不在回读时覆盖，升级后仍会看到长名，
                        // 必须等下一次联网刷新才生效。这里按 rid(profileRoom) 统一覆盖一次，
                        // 命中短名表则用短名，未收录保留缓存原名。
                        String cachedName = co.optString("name", "精彩节目");
                        String shortName = HuyaTogetherWatchGroupShortNames.of(rid);
                        if (!TextUtils.isEmpty(shortName)) cachedName = shortName;
                        Channel ch = new Channel(
                                cachedName,
                                co.optString("url", ""),
                                co.optString("group", ""),
                                co.optString("cid", "")
                        );
                        if (rid > 0) ch.setHuyaRoomId(rid);
                        if (uid > 0) ch.setHuyaUid(uid);
                        if (co.optBoolean("sdk", false)) ch.setHuyaSdkTogetherWatch(true);
                        // 【2026-09-29 频道台标】还原台标 URL（老缓存无此字段时为空字符串，不影响其它逻辑）
                        ch.setLogoUrl(co.optString("logo", ""));
                        g.channels.add(ch);
                    }
                }
                if (!g.channels.isEmpty()) groups.add(g);
            }
            if (groups.isEmpty()) return null;
            int total = 0;
            for (Group g : groups) total += g.channels.size();
            return groups;
        } catch (Throwable t) {
            return null;
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
    }

    private static final class TagSpec {
        final String tagId;
        final String tagName;
        final String groupName;

        TagSpec(String tagId, String tagName, String groupName) {
            this.tagId = tagId;
            this.tagName = tagName;
            this.groupName = groupName;
        }
    }

    private static final class Pair {
        final String id;
        final String name;

        Pair(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
