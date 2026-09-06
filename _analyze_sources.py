# -*- coding: utf-8 -*-
"""分析各拉取源(电影/剧集/动漫/综艺)实际内容构成，检查源内混入情况"""
import sys, json
from collections import Counter
sys.stdout.reconfigure(encoding='utf-8')

raw = json.load(open('_huya_together_raw.json', encoding='utf-8'))
for src in ['movie', 'tv', 'anime', 'variety']:
    rooms = raw[src]
    print(f"===== 源[{src}] 共 {len(rooms)} 个 =====")
    # 统计是否含动漫/综艺/电影词
    c = Counter()
    for r in rooms:
        text = f"{r['roomName']} {r['nickName']}".lower()
        if any(k in text for k in ['动漫', '动画', '番剧', '日漫', '国漫']):
            c['含动漫词'] += 1
        elif any(k in text for k in ['综艺', '真人秀', '脱口秀', '跑男', '极限挑战', '好声音', '选秀', '德云社']):
            c['含综艺词'] += 1
        elif any(k in text for k in ['韩剧', '美剧', '日剧', '英剧', '泰剧', '港剧']):
            c['含海外剧词'] += 1
        elif any(k in text for k in ['电影', '影院', '大片', '漫威', '好莱坞']):
            c['含电影词'] += 1
        else:
            c['无特征'] += 1
    print('  特征分布:', dict(c))
    # 抽样 15 个无特征/异常
    n = 0
    for r in rooms:
        text = f"{r['roomName']} {r['nickName']}".lower()
        hit = any(k in text for k in ['动漫', '动画', '番剧', '日漫', '国漫', '综艺', '真人秀', '脱口秀', '跑男', '韩剧', '美剧', '电影', '影院', '大片', '漫威', '好莱坞'])
        if not hit:
            print(f"    [无特征] {r['roomName']} | {r['nickName']}")
            n += 1
            if n >= 15:
                break
