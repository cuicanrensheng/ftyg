# -*- coding: utf-8 -*-
"""对照本地分类清单 + 解析 UI xml 中的文本"""
import json
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

# 1) 本地分类清单
d = json.load(open('_huya_together_classified.json', encoding='utf-8'))
for cat in ['纪录片', '海外动漫', '综艺娱乐', '外国电影', '海外追剧', '动漫动画', '怀旧老片', '影视热播']:
    items = [x['roomName'] for x in d if x['category'] == cat]
    print(f"[本地] {cat} 共{len(items)}")
    print('   ' + ' | '.join(items[:10]))
    print()

# 2) UI 文本
xml = open('_ui.xml', encoding='utf-8').read()
texts = re.findall(r'text="([^"]+)"', xml)
texts = [t for t in texts if t.strip()]
print("===== 当前 UI 文本 =====")
for t in texts:
    print('  ', t.replace('&#10;', '/'))
