# -*- coding: utf-8 -*-
"""读失败日志 00:47 的完整启动序列"""
import sys, json
sys.stdout.reconfigure(encoding='utf-8')

with open('_emu_log_v7a_restored.json', encoding='utf-8-sig') as f:
    d = json.load(f)
logs = d.get('logs', [])
print('总条数:', len(logs))
# 打印 00:47:40 之后的所有日志
for l in logs:
    t = l.get('time', '')
    if t >= '00:47:40':
        print(f'[{t}] <{l.get("tag")}> [{l.get("type")}] {l.get("message","")[:220]}')
