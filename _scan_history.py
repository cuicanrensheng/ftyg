# -*- coding: utf-8 -*-
"""扫描所有历史日志文件，确认 marsstn / SDK 初始化历史状态"""
import sys, os, json, glob
sys.stdout.reconfigure(encoding='utf-8')

files = ['_9527_all.txt', '_9527_berry.txt', '_9527_report.txt',
         '_emu9527_full.txt', '_emu9527_v2.txt', '_emu9528_full.txt',
         '_emu9528_v2.txt', '_emu_log_20260829.json', '_emu_line_logs.txt',
         '_emu_line_logs2.txt', '_emu_log_v7a_restored.json']
keys = ['e_machine', 'SECURITY_INIT', 'marsstn', 'SDK 初始化', 'Mars 初始化',
        'checkMarsNativeLibraries', 'sInitOk', '无法加载', '降级', '纯 HTTP',
        '初始化成功', 'SECURITY']

for fn in files:
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
                if any(k.lower() in (l.get('tag','')+ ' ' + m).lower() for k in keys):
                    print(f'  [{l.get("time")}] <{l.get("tag")}> [{l.get("type")}] {m[:180]}')
        else:
            with open(fn, encoding='utf-8-sig', errors='replace') as f:
                content = f.read()
            lines = content.splitlines()
            print(f'  总行数: {len(lines)}')
            for ln in lines:
                if any(k.lower() in ln.lower() for k in keys):
                    print(f'  {ln[:200]}')
    except Exception as ex:
        print(f'  读取失败: {ex}')
    print()
