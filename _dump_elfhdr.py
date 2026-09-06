# -*- coding: utf-8 -*-
"""对比 v7a 库的 ELF 头"""
import sys
sys.stdout.reconfigure(encoding='utf-8')
from elftools.elf.elffile import ELFFile
import os

for p in ['_so_v7a/lib/armeabi-v7a/libtvlive_security.so',
          '_ehframe_backup/libmarsstn_v7a_orig.so',
          '_ehframe_backup/libhycrypto_v7a_orig.so',
          '_ehframe_backup/libhyssl_v7a_orig.so']:
    with open(p, 'rb') as f:
        e = ELFFile(f)
        h = e.header
        iden = h['e_ident']
        print(f'== {os.path.basename(p)} ==')
        print(f'  class={iden["EI_CLASS"]} data={iden["EI_DATA"]} osabi={iden["EI_OSABI"]} abiver={iden["EI_ABIVERSION"]}')
        print(f'  type={h["e_type"]} machine={h["e_machine"]} version={h["e_version"]}')
        print(f'  flags={h["e_flags"]:#x} entry={h["e_entry"]:#x}')
        print(f'  phnum={h["e_phnum"]} shnum={h["e_shnum"]}')
        # ARM flags 解释
        flags = h["e_flags"]
        if 'v7a' in p or True:
            abi = flags & 0xff000000
            print(f'  ARM_ABI_VERSION={abi:#x} {"(EABI5)" if abi==0x05000000 else ""} float={flags & 0x00f00000:#x} {"(softfp)" if (flags & 0x00f00000)==0x00000000 else "(hardfp)" if (flags & 0x00f00000)==0x00400000 else ""}')
        print()
