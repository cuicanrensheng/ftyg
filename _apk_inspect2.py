# -*- coding: utf-8 -*-
import zipfile, io, sys, glob
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

apks = glob.glob(r'app\build\outputs\apk\release\*arm64*.apk')
z = zipfile.ZipFile(apks[0])

print('== dex 文件 ==')
for i in sorted(z.infolist(), key=lambda i: -i.file_size):
    if i.filename.endswith('.dex'):
        print(f'  {i.filename} raw={i.file_size/1024:.0f}KB comp={i.compress_size/1024:.0f}KB')

print()
print('== assets 大文件 top20（raw）==')
items = sorted([i for i in z.infolist() if i.filename.startswith('assets/')], key=lambda i: -i.file_size)
for i in items[:20]:
    print(f'  {i.filename}  raw={i.file_size/1024:8.1f}KB  comp={i.compress_size/1024:7.1f}KB')

print()
print('== res 大文件 top15（raw）==')
items = sorted([i for i in z.infolist() if i.filename.startswith('res/')], key=lambda i: -i.file_size)
for i in items[:15]:
    print(f'  {i.filename}  raw={i.file_size/1024:8.1f}KB  comp={i.compress_size/1024:7.1f}KB')
