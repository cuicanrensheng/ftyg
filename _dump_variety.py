# -*- coding: utf-8 -*-
"""模拟综艺 tmp 子分类拉取并检查内容纯度"""
import sys, requests
sys.stdout.reconfigure(encoding='utf-8')
UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'}
API_TMP = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList"
r = requests.get(f"{API_TMP}?iGid=2135&iTmpId=1011&iPageNo=1&iPageSize=500", timeout=10, headers=UA)
d = r.json()
vlist = d.get('vList') or []
print(f"tmp=1011 返回 {len(vlist)} 个")
for room in vlist:
    name = room.get('sRoomName') or ''
    intro = room.get('sIntroduction') or ''
    print(f"  {name} | {intro}")
