# -*- coding: utf-8 -*-
"""
从 HuyaTogetherWatchManager（HTTP 路线）拉取全量频道保存本地，并用 9 分组关键词做命中分析。
模拟 Java 逻辑：
  电影: MOVIE_TMP_IDS = {2067,2069,2071,2073,2075,2077,2068,2070,2072,2074,2076}
  剧集: TV_TMP_IDS    = {2079,2081,2083,2085,2087,2089,2080,2082,2084,2086,2088,2090}
  动漫: ANIME_TMP_IDS = {6861,2543,2544,2545,2546,2547,2548} + cache 20页(动漫过滤)
  综艺: VARIETY_TMP_IDS = {1011,2091,2093,2095} + cache 16页(综艺过滤+负向排除)
  cache: https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=N
"""
import json
import os
import re
import sys
import time
import requests

sys.stdout.reconfigure(encoding='utf-8')

UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'}
API_TMP = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList"
API_CACHE = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page="
GID = 2135

MOVIE_TMP_IDS = [2067, 2069, 2071, 2073, 2075, 2077, 2068, 2070, 2072, 2074, 2076]
TV_TMP_IDS = [2079, 2081, 2083, 2085, 2087, 2089, 2080, 2082, 2084, 2086, 2088, 2090]
ANIME_TMP_IDS = [6861, 2543, 2544, 2545, 2546, 2547, 2548]
VARIETY_TMP_IDS = [1011, 2091, 2093, 2095]
ANIME_CACHE_PAGES = 20
VARIETY_CACHE_PAGES = 16
PAGE_SIZE = 500
CACHE_PAGE_SIZE = 120

# ============ 9 分组关键词（与 Java 当前一致） ============
MOVIE_CATEGORY_KEYWORDS = [
    ("纪录片", "纪录片|纪实|探索发现|动物世界|人与自然|国家地理|bbc|自然|人文|舌尖|航拍中国|跟着书本|大熊猫|故宫|长城|古文明|通史|贝爷|荒野求生"),
    ("怀旧老片", "周星驰|星爷|林正英|英叔|僵尸|金庸|古龙|武侠|港片|怀旧|老电影|经典|老片|扁豆|乌贼|大象|亮哥|越哥|解说|影评|讲电影|聊电影|李小龙|黄飞鸿|英雄本色|无间道|上海滩|陈翔六点半|六点半|霍元甲|王晶|五福星|许氏三杰|小马哥|邱淑贞|王祖贤|张曼玉|梁家辉|林家栋|吴镇宇|古天乐|许绍雄|银河映像|话事人|古惑仔|粤语|港式|叶师傅|周润发|洪金宝"),
    ("外国电影", "漫威|蜘蛛侠|钢铁侠|复仇者|雷神|美国队长|银河护卫队|dc|蝙蝠侠|超人|神奇女侠|正义联盟|科幻|星际|星球大战|宇宙|外星人|赛博朋克|侏罗纪|恐龙|哥斯拉|金刚|变形金刚|x战警|好莱坞|欧美|韩国电影|日本电影|印度|宝莱坞|外语片|泰坦尼克|阿凡达|指环王|哈利波特|007|速度与激情|流浪地球|马东锡|泡菜国|杰森·斯坦森|郭达|巨石强森|敢死队|兰博|史泰龙|阿汤哥|憨豆|异形|铁血战士|黑夜传说|人类清除计划|小黄人|纳尼亚传奇|博物馆奇妙夜|美恐|黑寡妇|小丑女|蝙蝠洞|超级英雄|剑心|一梦中土|首尔之春|韩国"),
    ("影视热播", "热播|热门|新片|大片|票房|院线|热映|最新|沈腾|黄渤|王宝强|开心麻花|爆笑|喜剧|小品|相声|成龙|李连杰|甄子丹|吴京|动作|武打|警匪|枪战|犯罪|追车|格斗|功夫|杀破狼|叶问|碟中谍|谍影|悬疑|推理|恐怖|惊悚|鬼片|丧尸|午夜凶铃|招魂|盗墓|鬼吹灯|古墓|盗墓笔记|奥斯卡|豆瓣|高评分|战争|二战|历史|抗日|谍战|冒险|探险|电影"),
]
TV_CATEGORY_KEYWORDS = [
    ("海外追剧", "韩剧|日剧|美剧|英剧|泰剧|海外剧|港剧|台剧|欧美剧|日韩剧|tvb|海外|越狱|纸牌屋|权力的游戏|西部世界|绝命毒师|来自星星的你|太阳的后裔|请回答|我的大叔|顶楼|黑暗荣耀|虽然是精神病|窥探|信号|w两个世界|大长今|鱿鱼|禁忌女孩|吸血鬼日记|金智媛|秀智|毒枭|神探夏洛克|曼达洛人|沙丘|阿索卡|星战|太空"),
    ("剧集追剧", "古装|宫廷|后宫|甄嬛|如懿|延禧|乾隆|康熙|雍正|清朝|唐朝|明朝|汉服|仙剑|仙侠|武侠剧|金庸剧|古龙剧|封神|琅琊榜|庆余年|赘婿|知否|陈情令|山河令|三生三世|花千骨|步步惊心|宫锁|军旅|战争|特种兵|亮剑|士兵突击|抗战|抗日|谍战|潜伏|伪装者|风筝|悬崖|雪豹|我的团长|部队|军人|长津湖|跨过鸭绿江|刑侦|破案|法医|犯罪|心理罪|白夜追凶|隐秘的角落|沉默的真相|无证之罪|狂飙|他是谁|法医秦明|盗墓剧|鬼吹灯剧|盗墓笔记剧|现代|都市|爱情|职场|家庭|生活|偶像剧|青春|校园|恋爱|情感|都市情感|欢乐颂|三十而已|我的前半生|都挺好|小欢喜|小别离|少年派|西游记|三国演义|水浒传|红楼梦|武林外传|爱情公寓|家有儿女|还珠格格|琼瑶|四大名著|经典剧集|怀旧剧集|90年代|老剧|新白娘子传奇|射雕英雄传|天龙八部|神雕侠侣|鹿鼎记|笑傲江湖|国产剧|内地剧|电视剧|剧集|剧情"),
]
ANIME_CATEGORY_KEYWORDS = [
    ("海外动漫", "火影|海贼王|one piece|龙珠|七龍珠|鬼灭|咒术|进击的巨人|一拳超人|电锯人|柯南|犬夜叉|死神|妖尾|妖精的尾巴|七大罪|黑色五叶草|间谍过家家|芙莉莲|葬送|国王排名|勇者|高达|eva|新世纪福音战士|机甲|灌篮高手|幽游白书|乱马|网球王子|棋魂|通灵王|Tong灵王|游戏王|数码宝贝|神奇宝贝|宠物小精灵|中华小当家|铁臂阿童木|圣斗士|全职猎人|黑子的篮球|钢之炼金术师|钢炼|鲁路修|头文字d|城市猎人|四驱兄弟|神龙斗士|齐木|当哒当|超自然武装|影之实力者|食戟之灵|第一神拳|排球|足球小将|皮卡丘|宝可梦|瑞克与莫蒂|马男|双城之战|成龙历险记|猫和老鼠|宫崎骏|日本动画|少女|乙女|恋爱|魔法少女|美少女战士|魔卡少女|百变小樱|后宫番|逆后宫|女性向|少女番|恋爱番|紫罗兰|辉夜大小姐|五等分|青春恋爱|月刊少女|堀与宫村|更衣人偶|莉可丽丝|彻夜之歌|租借女友|日常|治愈|蜡笔小新|哆啦a梦|机器猫|樱桃小丸子|海绵宝宝|夏目友人帐|虫师|玉子爱情故事|摇曳露营|轻音少女|k-on|工作细胞|日常番|搞笑动漫|萌系|萌番|校园日常|轻松|悠哉日常大王|向山进发|比宇宙更远的地方|四月是你的谎言|未闻花名|clannad|日漫|新番|番剧"),
    ("动漫动画", "国漫|国产动画|中国风|秦时明月|武庚纪|斗罗大陆|斗破苍穹|完美世界|遮天|仙逆|凡人修仙|吞噬星空|武动乾坤|画江湖|不良人|狐妖|一人之下|镇魂街|灵笼|天官赐福|魔道祖师|凹凸世界|罗小黑|刺客伍六七|雾山五行|时光代理人|少年歌行|盘龙|雪鹰领主|神印王座|星辰变|天行九歌|全职高手|元尊|魁拔|白蛇|哪吒|姜子牙|深海|长安三万里|雄狮少年|新神榜|大鱼海棠|黑猫警长|葫芦娃|喜羊羊|熊出没|猪猪侠|铠甲勇士|热血|战斗|运动番|体育番|动漫|动画"),
]
VARIETY_CATEGORY_KEYWORDS = [
    ("综艺娱乐", "搞笑|脱口秀|吐槽大会|奇葩说|相声|小品|欢乐喜剧人|笑傲江湖|喜剧总动员|跨界喜剧王|麻花|开心|快乐大本营|天天向上|非诚勿扰|我们都爱笑|百变大咖秀|王牌对王牌|今夜百乐门|德云社|辽宁民间艺术团|奔跑吧|跑男|极限挑战|真人秀|歌手|好声音|中国好声音|中国新歌声|创造营|青春有你|乘风破浪的姐姐|披荆斩棘的哥哥|爸爸去哪儿|中餐厅|向往的生活|花儿与少年|花样姐姐|亲爱的客栈|我是歌手|舞蹈生|这就是街舞|中国有嘻哈|说唱新世代|选秀|偶像练习生|王牌|运动吧少年|声临其境|最强大脑|一站到底|非你莫属|音乐|演唱会|唱歌|综艺|娱乐"),
]

VARIETY_RELATED_KEYWORDS = [
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
    # 真实综艺节目名/特征（tmp 子分类过滤需要）
    "桃花坞", "青环", "毛雪汪", "天赐", "爱情保卫战", "高能少年团",
    "种地", "二十四小时", "新西游记", "大侦探", "相声", "即兴喜剧",
    "小品", "春晚", "捧腹", "笑哈哈", "宋小宝", "易中天", "百家讲坛",
    "T台秀", "运动", "旅游", "现在就出发",
]

VARIETY_EXCLUDE_KEYWORDS = [
    "电影", "影院", "院线", "票房", "大片", "漫威", "dc", "好莱坞", "奥斯卡",
    "美剧", "韩剧", "日剧", "英剧", "泰剧", "港剧", "台剧", "电视剧", "剧集",
    "古装", "宫廷", "仙侠", "武侠", "宫斗", "刑侦", "谍战", "抗日", "抗战",
    "tvb", "越狱", "权力的游戏", "绝命毒师", "大长今", "来自星星的你",
    "CSI", "犯罪现场", "神探", "罪案",
    "连续剧",
    # 纪录片/剧集特征（防止混入综艺源）
    "纪录片", "纪实", "通史", "求生", "荒野", "贝爷", "貝爺", "原始技术", "解放西",
    "盗墓笔记", "鬼吹灯", "胡八一", "云南虫谷", "天龙八部", "情景喜剧", "案发现场",
    "武媚娘", "篮球火", "中超", "警察", "喜剧片", "鹊刀门",
]

ANIME_RELATED_KEYWORDS = [
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
    "喜羊羊", "熊出没", "猪猪侠", "铠甲勇士",
]


def fetch_tmp_sub(sub_id, category):
    """模拟 fetchBySubCategory：最多10页，每页500，解析 vList"""
    rooms = []
    for page in range(1, 11):
        url = f"{API_TMP}?iGid={GID}&iTmpId={sub_id}&iPageNo={page}&iPageSize={PAGE_SIZE}"
        try:
            r = requests.get(url, timeout=10, headers=UA)
            if r.status_code != 200:
                break
            data = r.json()
            vlist = data.get('vList') or []
            if not vlist:
                break
            for room in vlist:
                lRoomId = room.get('lRoomId') or 0
                lUid = room.get('lUid') or 0
                lProfileRoom = room.get('lProfileRoom') or 0
                roomId = lRoomId if lRoomId > 0 else lUid
                profileRoomId = lProfileRoom if lProfileRoom > 0 else roomId
                if roomId <= 0:
                    continue
                roomName = room.get('sRoomName') or ''
                if not roomName:
                    roomName = '精彩节目'
                sIntro = room.get('sIntroduction') or ''
                nickName = sIntro if sIntro else '精彩节目'
                # 仅当 标题和主播名 都为空（"精彩节目"）才判定为下播/失效占位
                if roomName == '精彩节目' and nickName == '精彩节目':
                    continue
                coverUrl = room.get('sScreenshot') or ''
                userCount = room.get('lUserCount') or 0
                totalCount = room.get('lTotalCount') or 0
                onlineCount = int(max(userCount, totalCount))
                liveStatus = room.get('iLiveStatus') or -1
                bIsLive = room.get('bIsLive') or -1
                isLive = (liveStatus == 1 or bIsLive == 1 or onlineCount > 0)
                if not isLive and onlineCount == 0:
                    continue
                rooms.append({
                    'roomId': roomId, 'profileRoom': profileRoomId,
                    'roomName': roomName, 'nickName': nickName,
                    'coverUrl': coverUrl, 'onlineCount': onlineCount,
                    'isLive': isLive, 'source': category,
                })
            if len(vlist) < PAGE_SIZE:
                break
        except Exception as e:
            print(f"  [warn] tmp sub={sub_id} page={page}: {e}")
            break
    return rooms


def fetch_cache_pages(max_pages, category):
    """模拟 fetchFromCacheApi：解析 data.datas"""
    rooms = []
    for page in range(1, max_pages + 1):
        url = API_CACHE + str(page)
        try:
            r = requests.get(url, timeout=10, headers=UA)
            if r.status_code != 200:
                break
            data = r.json()
            datas = ((data.get('data') or {}).get('datas')) or []
            if not datas:
                break
            for room in datas:
                try:
                    roomNo = int(room.get('roomNo') or 0)
                except Exception:
                    roomNo = 0
                try:
                    uid = int(room.get('uid') or 0)
                except Exception:
                    uid = 0
                roomId = roomNo if roomNo > 0 else uid
                if roomId <= 0:
                    continue
                roomName = room.get('roomName') or ''
                if not roomName:
                    # 部分在播房间 roomName 为空但 introduction 有标题（如"经典9.8分电影"），兜底避免误杀
                    roomName = room.get('introduction') or ''
                if not roomName:
                    roomName = '精彩节目'
                nick = room.get('nick') or ''
                if not nick:
                    nick = '精彩节目'
                # 仅当 标题和主播名 都为空（"精彩节目"）才判定为下播/失效占位
                if roomName == '精彩节目' and nick == '精彩节目':
                    continue
                coverUrl = room.get('screenshot') or ''
                try:
                    onlineCount = int(room.get('totalCount') or 0)
                except Exception:
                    onlineCount = 0
                bIsLive = room.get('isLive') or -1
                isLive = (bIsLive == 1 or onlineCount > 0)
                if not isLive and onlineCount == 0:
                    continue
                rooms.append({
                    'roomId': roomId, 'profileRoom': roomId,
                    'roomName': roomName, 'nickName': nick,
                    'coverUrl': coverUrl, 'onlineCount': onlineCount,
                    'isLive': isLive, 'source': category,
                })
            if len(datas) < CACHE_PAGE_SIZE:
                break
        except Exception as e:
            print(f"  [warn] cache page={page}: {e}")
            break
    return rooms


def is_anime_related(room):
    text = f"{room['roomName']} {room['nickName']}".lower()
    for kw in ANIME_RELATED_KEYWORDS:
        if kw and kw.lower() in text:
            return True
    return False


def is_variety_related(room):
    text = f"{room['roomName']} {room['nickName']}".lower()
    if is_anime_related(room):
        return False
    for kw in VARIETY_EXCLUDE_KEYWORDS:
        if kw and kw.lower() in text:
            return False
    for kw in VARIETY_RELATED_KEYWORDS:
        if kw and kw.lower() in text:
            return True
    return False


def dedup(rooms):
    seen = set()
    out = []
    for r in rooms:
        if r['roomId'] not in seen:
            seen.add(r['roomId'])
            out.append(r)
    return out


def filter_sort(rooms):
    valid = [r for r in rooms if r['onlineCount'] > 10 or r['isLive']]
    suspect = [r for r in rooms if r['onlineCount'] > 0 and r not in valid]
    merged = valid + suspect
    merged.sort(key=lambda r: (r['isLive'], r['onlineCount']), reverse=True)
    return merged


def classify(rooms, table, default_cat):
    """模拟 classifyRoomsByKeywords"""
    result = []
    counts = {}
    default_names = []
    for room in rooms:
        text = f"{room['roomName']} {room['nickName']}".lower()
        matched = default_cat
        for cat_name, kw_str in table:
            if not kw_str:
                continue
            for kw in kw_str.split('|'):
                if kw and kw.lower() in text:
                    matched = cat_name
                    break
            if matched != default_cat:
                break
        new_room = dict(room)
        new_room['category'] = matched
        result.append(new_room)
        counts[matched] = counts.get(matched, 0) + 1
        if matched == default_cat:
            default_names.append(f"{room['roomName']} | {room['nickName']}")
    return result, counts, default_names


def main():
    all_movie, all_tv, all_anime, all_variety = [], [], [], []

    print("== 拉取电影 ==")
    for tid in MOVIE_TMP_IDS:
        rs = fetch_tmp_sub(tid, '电影')
        all_movie += rs
        print(f"  tmp={tid}: {len(rs)}")

    print("== 拉取剧集 ==")
    for tid in TV_TMP_IDS:
        rs = fetch_tmp_sub(tid, '剧集')
        all_tv += rs
        print(f"  tmp={tid}: {len(rs)}")

    print("== 拉取动漫 ==")
    for tid in ANIME_TMP_IDS:
        rs = fetch_tmp_sub(tid, '动漫')
        all_anime += rs
        print(f"  tmp={tid}: {len(rs)}")
    cache_rooms = fetch_cache_pages(ANIME_CACHE_PAGES, '动漫')
    anime_cache = [r for r in cache_rooms if is_anime_related(r)]
    print(f"  cache 20页: {len(cache_rooms)} -> 动漫过滤 {len(anime_cache)}")
    all_anime += anime_cache

    print("== 拉取综艺 ==")
    for tid in VARIETY_TMP_IDS:
        rs = fetch_tmp_sub(tid, '综艺')
        rs = [r for r in rs if is_variety_related(r)]
        all_variety += rs
        print(f"  tmp={tid}: 过滤后 {len(rs)}")
    variety_cache = fetch_cache_pages(VARIETY_CACHE_PAGES, '综艺')
    variety_cache = [r for r in variety_cache if is_variety_related(r)]
    print(f"  cache 16页: 过滤后 {len(variety_cache)}")
    all_variety += variety_cache

    movie = filter_sort(dedup(all_movie))
    tv = filter_sort(dedup(all_tv))
    anime = filter_sort(dedup(all_anime))
    variety = filter_sort(dedup(all_variety))

    print("\n==== 去重过滤后 ====")
    print(f"电影: {len(movie)}  剧集: {len(tv)}  动漫: {len(anime)}  综艺: {len(variety)}  总计: {len(movie)+len(tv)+len(anime)+len(variety)}")

    # 保存原始数据
    raw = {'movie': movie, 'tv': tv, 'anime': anime, 'variety': variety}
    with open('_huya_together_raw.json', 'w', encoding='utf-8') as f:
        json.dump(raw, f, ensure_ascii=False, indent=1)
    print("已保存 _huya_together_raw.json")

    # 分类分析
    sections = [
        ('电影(影视热播兜底)', movie, MOVIE_CATEGORY_KEYWORDS, '影视热播'),
        ('剧集(剧集追剧兜底)', tv, TV_CATEGORY_KEYWORDS, '剧集追剧'),
        ('动漫(动漫动画兜底)', anime, ANIME_CATEGORY_KEYWORDS, '动漫动画'),
        ('综艺(综艺娱乐兜底)', variety, VARIETY_CATEGORY_KEYWORDS, '综艺娱乐'),
    ]
    report_lines = []
    all_classified = []
    for title, rooms, table, default in sections:
        result, counts, default_names = classify(rooms, table, default)
        all_classified += result
        print(f"\n==== {title} ====")
        for cat, cnt in counts.items():
            print(f"  {cat}: {cnt}")
        print(f"  兜底[{default}] 共 {len(default_names)} 个频道:")
        for name in default_names:
            print(f"    - {name}")
        report_lines.append(f"==== {title} ==== counts={counts} 兜底={len(default_names)}")
        report_lines += [f"    - {n}" for n in default_names]

    with open('_huya_together_classified.json', 'w', encoding='utf-8') as f:
        json.dump(all_classified, f, ensure_ascii=False, indent=1)
    print("\n已保存 _huya_together_classified.json")

    with open('_huya_analysis_report.txt', 'w', encoding='utf-8') as f:
        f.write('\n'.join(report_lines))
    print("已保存 _huya_analysis_report.txt")


def reclassify():
    """复用已拉取的 raw JSON 重新分类（验证关键词优化）
    综艺源会重新应用 is_variety_related 过滤（模拟 tmp+cache 过滤逻辑）。"""
    raw = json.load(open('_huya_together_raw.json', encoding='utf-8'))
    variety = [r for r in raw['variety'] if is_variety_related(r)]
    print(f"综艺源重新过滤: {len(raw['variety'])} -> {len(variety)}")
    sections = [
        ('电影(影视热播兜底)', raw['movie'], MOVIE_CATEGORY_KEYWORDS, '影视热播'),
        ('剧集(剧集追剧兜底)', raw['tv'], TV_CATEGORY_KEYWORDS, '剧集追剧'),
        ('动漫(动漫动画兜底)', raw['anime'], ANIME_CATEGORY_KEYWORDS, '动漫动画'),
        ('综艺(综艺娱乐兜底)', variety, VARIETY_CATEGORY_KEYWORDS, '综艺娱乐'),
    ]
    all_classified = []
    for title, rooms, table, default in sections:
        result, counts, default_names = classify(rooms, table, default)
        all_classified += result
        print(f"==== {title} ====")
        for cat, cnt in counts.items():
            print(f"  {cat}: {cnt}")
        print(f"  兜底[{default}] 共 {len(default_names)} 个:")
        for name in default_names:
            print(f"    - {name}")
    with open('_huya_together_classified.json', 'w', encoding='utf-8') as f:
        json.dump(all_classified, f, ensure_ascii=False, indent=1)
    print("\n已保存 _huya_together_classified.json")


if __name__ == '__main__':
    if '--reclassify' in sys.argv:
        reclassify()
    else:
        main()
