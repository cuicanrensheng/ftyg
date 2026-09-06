# -*- coding: utf-8 -*-
"""解析 9527 拉取的日志 JSON"""
import json, sys
sys.stdout.reconfigure(encoding='utf-8')

path = sys.argv[1]
with open(path, encoding='utf-8-sig') as f:
    d = json.load(f)

logs = d.get('logs', [])
print(f'日志条数: {len(logs)}')
from collections import Counter
print('level:', dict(Counter(l.get('type') for l in logs)))
print()
# 打印 error 和关键 SDK/Mars 信息
for l in logs:
    t = l.get('tag', '')
    m = l.get('message', '')
    if l.get('type') == 'error':
        print(f'[ERR] [{l.get("time")}] <{t}> {m[:300]}')
print()
for l in logs:
    t = l.get('tag', '')
    m = l.get('message', '')
    if any(k in (t + ' ' + m) for k in ['SDK', 'Mars', '凭证', 'checkMars', 'code=', 'STATE_', '播放']):
        print(f'[{l.get("time")}] <{t}> {m[:200]}')
