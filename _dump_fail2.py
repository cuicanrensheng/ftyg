# -*- coding: utf-8 -*-
"""提取失败日志中 loadLibrary / e_machine 相关完整上下文"""
import sys, json
sys.stdout.reconfigure(encoding='utf-8')

for fn in ['_emu_log_20260829.json', '_emu_log_v7a_restored.json']:
    print(f'########## {fn} ##########')
    with open(fn, encoding='utf-8-sig') as f:
        d = json.load(f)
    logs = d.get('logs', [])
    # 找关键行索引
    idxs = []
    for i, l in enumerate(logs):
        m = l.get('message', '')
        if any(k in m for k in ['loadLibrary', 'e_machine', '预检', '无法加载', 'marsstn', 'Mars 原生']):
            idxs.append(i)
    if not idxs:
        print('  无匹配行')
        continue
    # 打印每个匹配行及前后 3 行
    printed = set()
    for i in idxs:
        for j in range(max(0, i-2), min(len(logs), i+3)):
            if j in printed:
                continue
            l = logs[j]
            printed.add(j)
            print(f'[{l.get("time")}] <{l.get("tag")}> [{l.get("type")}] {l.get("message","")[:250]}')
        print('  ...')
    print()
