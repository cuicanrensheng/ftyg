# -*- coding: utf-8 -*-
import sys, json
sys.stdout.reconfigure(encoding='utf-8')

data = json.load(open('_huya_together_classified.json', encoding='utf-8'))
want = set(sys.argv[1:])
order = ["纪录片", "怀旧老片", "外国电影", "影视热播", "海外追剧", "剧集追剧", "海外动漫", "动漫动画", "综艺娱乐"]
for cat in order:
    if want and cat not in want:
        continue
    rooms = [r for r in data if r['category'] == cat]
    print(f"===== {cat} ({len(rooms)}) =====")
    for r in rooms:
        print(f"  {r['roomName']} | {r['nickName']}")
    print()
