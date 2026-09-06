# -*- coding: utf-8 -*-
import subprocess, io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
pre = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin'

def jni_syms(path):
    out = subprocess.run([pre + r'\llvm-nm.exe', '-D', '--defined-only', path],
                         capture_output=True, text=True, errors='replace').stdout
    return {l.split()[-1] for l in out.splitlines() if 'Java_' in l or 'JNI_OnLoad' in l}

orig = jni_syms(r'd:\ASDF\TV Live\AH-main\_tmp_so\lib_armeabi-v7a_libmmkv.so')
stub = jni_syms(r'd:\ASDF\TV Live\AH-main\_mmkv_stub\libmmkv_v7a.so')
print('原版 JNI 符号:', len(orig))
print('stub JNI 符号:', len(stub))
missing = orig - stub
extra = stub - orig
print('stub 缺失:', sorted(missing) if missing else '无')
print('stub 多余:', sorted(extra) if extra else '无')
