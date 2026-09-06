# -*- coding: utf-8 -*-
"""生成分组统计 + 各分组兜底频道报告"""
import sys, json
from collections import Counter
sys.stdout.reconfigure(encoding='utf-8')

data = json.load(open('_huya_together_classified.json', encoding='utf-8'))
c = Counter(r['category'] for r in data)
order = ["纪录片", "怀旧老片", "外国电影", "影视热播", "海外追剧", "剧集追剧", "海外动漫", "动漫动画", "综艺娱乐"]
print("===== 全量分组统计 =====")
total = 0
for cat in order:
    n = c.get(cat, 0)
    total += n
    print(f"  {cat}: {n}")
print(f"  总计: {total}")
print()

# 输出每个分组的完整频道
for cat in order:
    rooms = [r for r in data if r['category'] == cat]
    print(f"===== {cat} ({len(rooms)}) =====")
    for r in rooms:
        print(f"  {r['roomName']} | {r['nickName']}")
    print()
