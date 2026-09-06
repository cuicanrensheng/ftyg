# -*- coding: utf-8 -*-
"""列出 builtins 中 __aeabi 定义所在成员（用 llvm-nm 的 -A 输出）"""
import io, sys, subprocess
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

NM = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe'
BT = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\18\lib\linux\libclang_rt.builtins-arm-android.a'

out = subprocess.run([NM, '-A', BT], capture_output=True, text=True, errors='replace').stdout
# 每行: path:member.o:  addr  T  name
import re
for line in out.splitlines():
    if '__aeabi_idiv' in line or '__aeabi_uidiv' in line or '__aeabi_ldivmod' in line or '__aeabi_uldivmod' in line:
        m = re.search(r':([^:]+\.o):\s+\S+\s+[TW]\s+(\S+)$', line)
        if m:
            print(f'{m.group(2):24s} <- {m.group(1)}')
