# -*- coding: utf-8 -*-
"""验证 v7a marsstn 对 c++_shared 的符号需求是否与 arm64 一致"""
import io, sys, subprocess, os, zipfile, glob
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

NM = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe'

def und(path):
    out = subprocess.run([NM, '-D', '-u', path], capture_output=True, text=True, errors='replace').stdout
    return {l.split()[-1] for l in out.splitlines() if l.split()}

def dyn(path):
    out = subprocess.run([NM, '-D', '--defined-only', path], capture_output=True, text=True, errors='replace').stdout
    return {l.split()[-1] for l in out.splitlines() if l.split()}

# 从 v7a APK 提取
apks = glob.glob(r'app\build\outputs\apk\release\*v7a*.apk')
if not apks:
    apks = glob.glob(r'app\build\outputs\apk\release\*.apk')
z = zipfile.ZipFile(apks[0])
def extract(so_name):
    fn = [i.filename for i in z.infolist() if i.filename.endswith('/' + so_name) and ('v7a' in i.filename or 'armeabi' in i.filename)]
    if not fn:
        return None
    p = os.path.join('_so_analyze', 'v7a_' + so_name)
    with z.open(fn[0]) as f:
        open(p, 'wb').write(f.read())
    return p

m = extract('libmarsstn.so')
c = extract('libc++_shared.so')
if not m or not c:
    print('v7a so 未找到, apks:', apks)
    sys.exit(0)

u = und(m)
e = dyn(c)
need = sorted(u & e)
print(f'v7a marsstn UND={len(u)}  c++_shared 导出={len(e)}  交集={len(need)}')
print('交集符号:')
for s in need:
    print(f'  {s}')
