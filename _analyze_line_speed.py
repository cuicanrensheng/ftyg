# -*- coding: utf-8 -*-
"""分析 HTTP API 线路 vs SDK 独立线路 加载分组/频道耗时"""
import json
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

raw = open(sys.argv[1], encoding='utf-8').read()
idx = raw.find('{"count"')
if idx < 0:
    idx = raw.find('{')
d = json.loads(raw[idx:])
logs = d['logs']
print('total entries =', len(logs))

entries = []
for l in logs:
    msg = l['message']
    tag = l['tag']
    t = l.get('timestamp', 0)
    time_str = l.get('time', '')
    entries.append((t, time_str, tag, msg))

entries.sort(key=lambda x: x[0])

print()
print('===== 关键节点：类获取/加载完成/分组加载（去重） =====')
seen = set()
for t, ts, tag, msg in entries:
    # 只保留关键完成节点
    if ('总共获取到' in msg or '加载完成' in msg or '新增' in msg
            or '缓存命中' in msg or 'Cache API 请求失败' in msg
            or '兜底' in msg or '【SDK→TagList】' in msg or '【SDK→tag=' in msg
            or 'fetchGroup' in msg or '固定分组' in msg
            or '全部完成' in msg or '刷新完成' in msg or 'loadDone' in msg
            or '缓存加载' in msg or '缓存读取' in msg):
        key = (t, msg[:100])
        if key in seen:
            continue
        seen.add(key)
        print(f"{ts} [{t}] [{tag}] {msg[:150]}")

print()
print('===== 全部分组/频道加载相关（TogetherWatch 完成前） =====')
# 找 loading 起点：ChannelPanel setChannels 或 loadTogetherWatch
for t, ts, tag, msg in entries:
    if ('setChannels' in msg or '加载虎牙' in msg or 'loadTogether' in msg
            or 'startLoad' in msg or '开始加载' in msg or 'loadHuya' in msg):
        print(f"{ts} [{t}] [{tag}] {msg[:150]}")
