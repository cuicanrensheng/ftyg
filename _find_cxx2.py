# -*- coding: utf-8 -*-
"""扫描 app/libs 全部 aar/jar 与项目依赖中的 libc++_shared.so"""
import io, sys, zipfile, glob, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

for aar in sorted(glob.glob(r'app\libs\*.aar') + glob.glob(r'app\libs\*.jar')):
    try:
        z = zipfile.ZipFile(aar)
    except Exception:
        continue
    for i in z.infolist():
        n = i.filename
        if 'libc++_shared.so' in n:
            print(f'{os.path.basename(aar):45s} {n:50s} {i.file_size/1024:8.1f}KB')
