# -*- coding: utf-8 -*-
"""分析分类结果：输出每个分组完整频道清单 + 兜底频道清单"""
import sys, json
from collections import Counter
sys.stdout.reconfigure(encoding='utf-8')

data = json.load(open('_huya_together_classified.json', encoding='utf-8'))
c = Counter(r['category'] for r in data)
print('===== 全部分类分布 =====')
for cat, cnt in c.most_common():
    print(f"  {cat}: {cnt}")
print()

for cat in sorted(set(r['category'] for r in data)):
    print(f"===== {cat} ({c[cat]}) =====")
    for r in data:
        if r['category'] == cat:
            print(f"  {r['roomName']} | {r['nickName']}")
    print()
