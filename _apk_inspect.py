# -*- coding: utf-8 -*-
import zipfile, io, sys, glob
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

apks = glob.glob(r'app\build\outputs\apk\release\*arm64*.apk')
if not apks:
    print('未找到 arm64 APK')
    sys.exit(0)
apk = apks[0]
z = zipfile.ZipFile(apk)
cats = {}
for i in z.infolist():
    name = i.filename
    if name.endswith('.so'):
        c = 'so'
    elif name.endswith('.dex'):
        c = 'dex'
    elif name.startswith('res/'):
        c = 'res'
    elif name.startswith('assets/'):
        c = 'assets'
    elif name == 'resources.arsc':
        c = 'arsc'
    elif name.startswith('META-INF'):
        c = 'meta'
    else:
        c = 'other'
    cats.setdefault(c, [0, 0])
    cats[c][0] += i.file_size
    cats[c][1] += i.compress_size

print('== APK 构成（原始 raw / 压缩 comp）==')
for c, (raw, comp) in sorted(cats.items(), key=lambda kv: -kv[1][0]):
    print(f'{c:8s} raw={raw/1024:8.0f}KB  comp={comp/1024:7.0f}KB')

print()
print('== 未在 APK 中的 libs so ==')
have = {i.filename.split('/')[-1] for i in z.infolist() if i.filename.endswith('.so')}
for name in ['libhyutils.so', 'libhydeviceid.so', 'libmtpencrypt.so', 'libtvlive_security.so']:
    print(f'  {name}: {"在" if name in have else "不在"}')

print()
print('== 资源大类（按扩展名，comp）==')
exts = {}
for i in z.infolist():
    if not i.filename.startswith('res/') and not i.filename.startswith('assets/'):
        continue
    base = i.filename.rsplit('/', 1)[-1]
    ext = base.rsplit('.', 1)[-1] if '.' in base else '(dir)'
    exts.setdefault(ext, [0, 0])
    exts[ext][0] += i.file_size
    exts[ext][1] += i.compress_size
for e, (raw, comp) in sorted(exts.items(), key=lambda kv: -kv[1][0])[:12]:
    print(f'  {e:10s} raw={raw/1024:8.0f}KB  comp={comp/1024:7.0f}KB')
