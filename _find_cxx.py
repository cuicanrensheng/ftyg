# -*- coding: utf-8 -*-
"""找出 libc++_shared.so 来自哪个 aar/jar"""
import io, sys, zipfile, glob, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

for aar in glob.glob(r'app\libs\*.aar') + glob.glob(r'app\libs\*.jar'):
    try:
        z = zipfile.ZipFile(aar)
    except Exception:
        continue
    for i in z.infolist():
        n = i.filename
        if n.endswith('libc++_shared.so') or n.endswith('libmmkv.so') or n.endswith('libstlport_shared.so'):
            print(f'{os.path.basename(aar):45s} {n:55s} {i.file_size/1024:8.1f}KB')
