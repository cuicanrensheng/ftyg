# -*- coding: utf-8 -*-
import io, sys, glob, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

kw = sys.argv[1] if len(sys.argv) > 1 else 'marsstn'
day = sys.argv[2] if len(sys.argv) > 2 else '08-29'
files = [f for f in glob.glob('*.md') if day in f and f.startswith('开发记录')]
if not files:
    print('未找到记录文件:', day)
    sys.exit(0)
fname = files[0]
print('== 文件:', fname, '关键词:', kw, '==')
data = open(fname, encoding='utf-8').read()
lines = data.splitlines()
cnt = 0
for i, l in enumerate(lines):
    if kw.lower() in l.lower():
        lo = max(0, i - 2)
        hi = min(len(lines), i + 4)
        print('\n'.join(lines[lo:hi]))
        print('---')
        cnt += 1
        if cnt >= 30:
            break
if cnt == 0:
    print('(无匹配)')
