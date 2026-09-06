# -*- coding: utf-8 -*-
"""打印怀旧老片与影视热播完整清单，核对 UI 归属"""
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

d = json.load(open('_huya_together_classified.json', encoding='utf-8'))
for cat in ['怀旧老片', '影视热播']:
    items = [x['roomName'] for x in d if x['category'] == cat]
    print(f"[本地] {cat} 共{len(items)}")
    for i, t in enumerate(items, 1):
        print(f"  {i:3d} {t[:44]}")
    print()
