# -*- coding: utf-8 -*-
"""定量分析: 各 so 的 UND 符号需求与 c++_shared 导出覆盖"""
import io, sys, subprocess, os, zipfile, glob
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

NM = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe'

def syms(path, kind):
    out = subprocess.run([NM, kind, '--defined-only', path], capture_output=True, text=True, errors='replace').stdout
    return {l.split()[-1] for l in out.splitlines() if l.split()}

def und(path):
    out = subprocess.run([NM, '-D', '-u', path], capture_output=True, text=True, errors='replace').stdout
    return {l.split()[-1] for l in out.splitlines() if l.split()}

def so_from_apk(name, apk):
    z = zipfile.ZipFile(apk)
    fn = [i.filename for i in z.infolist() if i.filename.endswith('/' + name) and 'arm64' in i.filename]
    if not fn:
        return None
    with z.open(fn[0]) as f:
        data = f.read()
    p = os.path.join('_so_analyze', 'cur_' + name)
    with open(p, 'wb') as f:
        f.write(data)
    return p

apk = glob.glob(r'app\build\outputs\apk\release\*arm64*.apk')[0]

targets = ['libc++_shared.so', 'libmarsstn.so', 'libhyssl.so', 'libhycrypto.so', 'libhyquic.so']
paths = {}
for t in targets:
    p = so_from_apk(t, apk)
    if p:
        paths[t] = p
        print(f'{t}: {os.path.getsize(p)//1024} KB')
    else:
        print(f'{t}: (不在 APK)')

cpp = paths.get('libc++_shared.so')
if cpp:
    exports = syms(cpp, '-D')
    print(f'\nlibc++_shared.so 导出符号: {len(exports)}')
    for t in ['libmarsstn.so', 'libhyssl.so', 'libhycrypto.so', 'libhyquic.so']:
        p = paths.get(t)
        if not p:
            continue
        u = und(p)
        need = u & exports
        print(f'  {t}: UND={len(u)}  ∩c++_shared={len(need)}')

# 谁 DT_NEEDED libc++_shared
print('\n== DT_NEEDED 检查 ==')
for t, p in paths.items():
    if t == 'libc++_shared.so':
        continue
    out = subprocess.run([r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe',
                          '-d', p], capture_output=True, text=True, errors='replace').stdout
    needs = [l.split('[')[-1].rstrip(']') for l in out.splitlines() if 'NEEDED' in l]
    print(f'  {t}: NEEDED={needs}')
