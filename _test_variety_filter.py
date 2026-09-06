# -*- coding: utf-8 -*-
"""测试综艺 cache 正向过滤效果：cache 房间先按综艺特征词过滤，再分类"""
import sys, json
from collections import Counter
sys.stdout.reconfigure(encoding='utf-8')

VARIETY_FILTER_KW = [
    "综艺", "真人秀", "脱口秀", "吐槽大会", "奇葩说", "相声", "小品", "欢乐喜剧人",
    "笑傲江湖", "喜剧总动员", "跨界喜剧王", "快乐大本营", "天天向上", "非诚勿扰",
    "我们都爱笑", "百变大咖秀", "王牌对王牌", "今夜百乐门", "德云社", "奔跑吧",
    "跑男", "极限挑战", "歌手", "好声音", "中国新歌声", "创造营", "青春有你",
    "乘风破浪", "披荆斩棘", "爸爸去哪儿", "中餐厅", "向往的生活", "花儿与少年",
    "花样姐姐", "亲爱的客栈", "我是歌手", "舞蹈生", "这就是街舞", "中国有嘻哈",
    "说唱", "选秀", "偶像练习生", "运动吧少年", "声临其境", "最强大脑", "一站到底",
    "非你莫属", "音乐", "演唱会", "唱歌", "娱乐", "搞笑", "喜剧", "相亲", "恋爱",
    "心动", "脱口秀", "舞蹈", "街舞", "舞台", "演出", "开唱", "歌王", "合唱",
    "欢乐", "开心", "大笑", "笑点", "笑死", "哈哈", "综艺感", "体育赛事", "赛事",
    "比赛", "竞技", "选秀", "纪录片", "纪实", "通史", "荒野", "探险", "美食",
    "旅游", "文化", "知识", "考古", "历史", "访谈", "对话", "讲座",
]

raw = json.load(open('_huya_together_raw.json', encoding='utf-8'))
variety = raw['variety']

# 统计 variety 源里哪些来自 cache（需要临时标记）。脚本没保存来源细分，重新推断：
# 直接看过滤后保留多少
kept, dropped = [], []
for r in variety:
    text = f"{r['roomName']} {r['nickName']}".lower()
    if any(k.lower() in text for k in VARIETY_FILTER_KW):
        kept.append(r)
    else:
        dropped.append(r)

print(f"综艺源总 {len(variety)} -> 过滤保留 {len(kept)}，丢弃 {len(dropped)}")
print("\n===== 保留的样例（前 40）=====")
for r in kept[:40]:
    print(f"  {r['roomName']} | {r['nickName']}")
print(f"\n===== 丢弃的样例（前 25，含明显电影/剧集）=====")
for r in dropped[:25]:
    print(f"  {r['roomName']} | {r['nickName']}")
