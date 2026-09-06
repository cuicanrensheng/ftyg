# -*- coding: utf-8 -*-
"""扫描与逍遥/21513/marsstn 成功记录相关的日志证据"""
import sys, os, json
sys.stdout.reconfigure(encoding='utf-8')

keys = ['e_machine', 'marsstn', 'SECURITY_INIT', 'SDK 初始化', 'loadLibrary', '21513', '逍遥', 'Houdini', 'Unsatis', 'dlopen']

for fn in ['_emu_line_logs.txt', '_emu_line_logs2.txt', '_emu_log_v7a_restored.json', '_emu_log_20260829.json']:
    if not os.path.exists(fn):
        continue
    print(f'===== {fn} =====')
    try:
        if fn.endswith('.json'):
            with open(fn, encoding='utf-8-sig') as f:
                d = json.load(f)
            logs = d.get('logs', [])
            print(f'  总条数: {len(logs)}')
            for l in logs:
                m = l.get('message', '')
                if any(k.lower() in (l.get('tag','') + ' ' + m).lower() for k in keys):
                    print(f'  [{l.get("time")}] <{l.get("tag")}> [{l.get("type")}] {m[:220]}')
        else:
            with open(fn, encoding='utf-8-sig', errors='replace') as f:
                lines = f.read().splitlines()
            print(f'  总行数: {len(lines)}')
            for ln in lines:
                if any(k.lower() in ln.lower() for k in keys):
                    print(f'  {ln[:220]}')
    except Exception as ex:
        print(f'  读取失败: {ex}')
    print()
