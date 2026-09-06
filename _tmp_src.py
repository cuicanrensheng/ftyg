# -*- coding: utf-8 -*-
import io, sys, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

lines = open('_src_fallback.m3u', encoding='utf-8-sig', errors='replace').read().splitlines()
print('total lines:', len(lines))

# 解析 m3u：#EXTINF 行 -> 下一行 URL
entries = []
name = None
grp = None
for l in lines:
    s = l.strip()
    if s.startswith('#EXTINF'):
        m = re.search(r'group-title="([^"]*)"', s)
        grp = m.group(1) if m else ''
        name = s.split(',', 1)[-1].strip() if ',' in s else s
    elif s.startswith('http'):
        entries.append((grp, name, s))
    elif s.startswith('#'):
        continue

print('total entries:', len(entries))
bad = [e for e in entries if 'jdshipin' in e[2]]
print("=== 失效频道 (jdshipin) : %d 条 ===" % len(bad))
for g, n, u in bad:
    print('  [%-10s] %-26s %s' % (g[:10], n[:26], u.split('id=')[-1]))

# 分组统计
from collections import Counter
print()
print('=== 失效频道分组分布 ===')
for g, c in Counter(e[0] for e in bad).items():
    print('  %-16s %d' % (g[:16], c))
