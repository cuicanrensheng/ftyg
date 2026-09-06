# -*- coding: utf-8 -*-
"""把 builtins archive 中 6 个 __aeabi_* 符号的 visibility 从 HIDDEN 改为 DEFAULT。
直接修改 ELF32 symtab 的 st_other 低 2 位。"""
import io, sys, subprocess, os, struct, shutil
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

AR = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-ar.exe'
BT = r'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\18\lib\linux\libclang_rt.builtins-arm-android.a'
WORK = '_so_analyze\\aeabi_fix'
TARGET = set(['__aeabi_idiv', '__aeabi_idivmod', '__aeabi_ldivmod',
              '__aeabi_uidiv', '__aeabi_uidivmod', '__aeabi_uldivmod'])

shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)

subprocess.run([AR, 'x', BT], cwd=WORK, check=True)
members = sorted(os.listdir(WORK))
print(f'解包 {len(members)} 个成员')

def fix_o(path):
    with open(path, 'rb') as f:
        data = f.read()
    if len(data) < 52 or data[:4] != b'\x7fELF':
        return 0
    e_shoff = struct.unpack_from('<I', data, 32)[0]
    e_shentsize = struct.unpack_from('<H', data, 46)[0]
    e_shnum = struct.unpack_from('<H', data, 48)[0]
    symtab = None
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        sh_type = struct.unpack_from('<I', data, off + 4)[0]
        sh_offset = struct.unpack_from('<I', data, off + 16)[0]
        sh_size = struct.unpack_from('<I', data, off + 20)[0]
        sh_link = struct.unpack_from('<I', data, off + 24)[0]
        sh_entsize = struct.unpack_from('<I', data, off + 36)[0]
        if sh_type == 2:  # SHT_SYMTAB
            symtab = (sh_offset, sh_size, sh_link, sh_entsize)
    if not symtab:
        return 0
    so, ss, slink, sent = symtab
    if sent != 16:
        return 0
    strtab_off = strtab_sz = None
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        sh_offset = struct.unpack_from('<I', data, off + 16)[0]
        sh_size = struct.unpack_from('<I', data, off + 20)[0]
        if i == slink:
            strtab_off = sh_offset
            strtab_sz = sh_size
            break
    if strtab_off is None:
        return 0
    count = ss // 16
    patches = []
    for i in range(count):
        ent = so + i * 16
        st_name = struct.unpack_from('<I', data, ent)[0]
        st_other = data[ent + 13]
        if st_name >= strtab_sz:
            continue
        end = data.find(b'\x00', strtab_off + st_name)
        name = data[strtab_off + st_name:end].decode('ascii', 'replace')
        if name in TARGET and (st_other & 0x3) != 0:
            patches.append((ent + 13, (st_other & ~0x3)))
    if patches:
        with open(path, 'r+b') as f:
            for off, val in patches:
                f.seek(off)
                f.write(bytes([val]))
    return len(patches)

total = 0
for m in members:
    p = os.path.join(WORK, m)
    c = fix_o(p)
    if c:
        print(f'  {m}: 修改 {c} 个符号')
        total += c

OUT = os.path.abspath('_so_analyze\\builtins_aeabi.a')
subprocess.run([AR, 'rcs', OUT] + members, cwd=WORK, check=True)
print(f'打包完成 {OUT}, 共修改 {total} 个符号')
