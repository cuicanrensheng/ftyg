# -*- coding: utf-8 -*-
"""验证虎牙一起看 Cache API 可达性与数据格式"""
import json
import urllib.request

url = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=1"
req = urllib.request.Request(url, headers={
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
    "Referer": "https://www.huya.com/g/2135",
})
try:
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read().decode("utf-8", errors="replace")
        print("HTTP", resp.status, "len=", len(body))
        root = json.loads(body)
        data = root.get("data") or {}
        datas = data.get("datas") or []
        print("datas 数量:", len(datas))
        if datas:
            print("完整字段 keys:", list(datas[0].keys()))
        for i, r in enumerate(datas[:6]):
            print("  [%d] uid=%s profileRoom=%s privateHost=%s aliveNum=%s totalCount=%s roomName=%s" % (
                i, r.get("uid"), r.get("profileRoom"), r.get("privateHost"),
                r.get("aliveNum"), r.get("totalCount"), (r.get("roomName") or "")[:20]))
except Exception as e:
    print("FAIL:", e)
