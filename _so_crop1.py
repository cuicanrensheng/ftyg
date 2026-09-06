# -*- coding: utf-8 -*-
"""打印 marsstn 引用 c++_shared 的符号清单"""
import io, sys, subprocess, os, zipfile, glob
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

NM = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe'

def und(path):
    out = subprocess.run([NM, '-D', '-u', path], capture_output=True, text=True, errors='replace').stdout
    return {l.split()[-1] for l in out.splitlines() if l.split()}

def dyn(path):
    out = subprocess.run([NM, '-D', '--defined-only', path], capture_output=True, text=True, errors='replace').stdout
    return {l.split()[-1] for l in out.splitlines() if l.split()}

marsstn = r'_so_analyze\cur_libmarsstn.so'
cpp = r'_so_analyze\cur_libc++_shared.so'
u = und(marsstn)
e = dyn(cpp)
need = sorted(u & e)
print(f'marsstn 引用 c++_shared 的符号数: {len(need)}\n')

# demangle 显示
DEM = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-cxxfilt.exe'
d = subprocess.run([DEM] + need, capture_output=True, text=True, errors='replace').stdout.splitlines()
for m, dn in zip(need, d):
    print(f'  {m}')
    print(f'      -> {dn}')
