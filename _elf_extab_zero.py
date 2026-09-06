#!/usr/bin/env python3
"""
ELF .ARM.extab 原位清零工具
=====================

【背景】
v7a 三个核心库（libhycrypto / libhyssl / libmarsstn）已采用 CANTUNWIND 方案：
- .ARM.exidx 每条 entry 的 data 字节写 1（EXIDX_CANTUNWIND）
- ARM32 unwinder 读 data=1 → 立即返回 "无法回溯"，不再去 .ARM.extab 查附加表

本脚本：在该前提下，原位清零 .ARM.extab 文件内容（写全 0），
既不影响功能（unwinder 不再读它），又能让 deflate 压缩率极高。

【关键约束】
- 文件大小、文件 offset、section header、program header **零改动**
- 仅把 sh_offset 起始的 sh_size 字节写 0
- 适配所有 .ARM.extab section

【用法】
  python _elf_extab_zero.py <so_path> [--inplace]
  默认: 仅报告；--inplace 才真正修改

【已验证】可安全对以下库执行（与 CANTUNWIND 方案配合）：
  - libc++_shared.so (v7a)
  - libhycrypto.so (v7a)
  - libhyssl.so (v7a)
  - libmarsstn.so (v7a, AAR 内)
"""

import sys
import struct
import argparse
from pathlib import Path

# ELF 常量
ELFCLASS32 = 1
ELFCLASS64 = 2
ELFDATA2LSB = 1
EI_NIDENT = 16
SHT_PROGBITS = 1
SHT_ARM_EXIDX = 0x70000001  # ARM exception index table

# Section header 32/64 布局（来自 ELF spec）
def parse_elf_header(f):
    """解析 ELF header，返回 (class, data, ehdr_size, shoff, shentsize, shnum, shstrndx)"""
    f.seek(0)
    ident = f.read(EI_NIDENT)
    if ident[:4] != b'\x7fELF':
        raise ValueError("Not an ELF file")
    elf_class = ident[4]
    elf_data = ident[5]
    if elf_data != ELFDATA2LSB:
        raise ValueError("Only little-endian supported")
    if elf_class == ELFCLASS32:
        # ELF32 header layout (52 bytes total):
        #   e_ident[16] | e_type(2) e_machine(2) e_version(4) e_entry(4) e_phoff(4)
        #   e_shoff(4) e_flags(4) e_ehsize(2) e_phentsize(2) e_phnum(2)
        #   e_shentsize(2) e_shnum(2) e_shstrndx(2)
        # e_ident(16) + 后续 36 字节包含以上所有字段
        # 字段偏移（相对 e_ident 之后）:
        #   0..1   e_type
        #   2..3   e_machine
        #   4..7   e_version
        #   8..11  e_entry
        #   12..15 e_phoff
        #   16..19 e_shoff
        #   20..23 e_flags
        #   24..25 e_ehsize
        #   26..27 e_phentsize
        #   28..29 e_phnum
        #   30..31 e_shentsize
        #   32..33 e_shnum
        #   34..35 e_shstrndx
        rest = f.read(36)
        e_shoff = struct.unpack('<I', rest[16:20])[0]
        e_shentsize = struct.unpack('<H', rest[30:32])[0]
        e_shnum = struct.unpack('<H', rest[32:34])[0]
        e_shstrndx = struct.unpack('<H', rest[34:36])[0]
        ehdr_size = 52
    elif elf_class == ELFCLASS64:
        rest = f.read(48)
        e_shoff = struct.unpack('<Q', rest[40:48])[0]
        e_shentsize = struct.unpack('<H', rest[58:60])[0]
        e_shnum = struct.unpack('<H', rest[60:62])[0]
        e_shstrndx = struct.unpack('<H', rest[62:64])[0]
        ehdr_size = 64
    else:
        raise ValueError(f"Unknown ELF class: {elf_class}")
    return elf_class, e_shoff, e_shentsize, e_shnum, e_shstrndx, ehdr_size


def find_extab_sections(f, elf_class, shoff, shentsize, shnum, shstrndx):
    """扫描所有 section header，找出 .ARM.extab / .ARM.exidx section"""
    # 先读 shstrtab 拿字符串表
    if shentsize == 0 or shnum == 0:
        return []
    shstr_off, shstr_size = None, None
    if elf_class == ELFCLASS32:
        # 读 shstrndx 的 section header
        f.seek(shoff + shstrndx * shentsize)
        sh_data = f.read(shentsize)
        shstr_off = struct.unpack('<I', sh_data[16:20])[0]
        shstr_size = struct.unpack('<I', sh_data[20:24])[0]
    else:
        f.seek(shoff + shstrndx * shentsize)
        sh_data = f.read(shentsize)
        shstr_off = struct.unpack('<Q', sh_data[24:32])[0]
        shstr_size = struct.unpack('<Q', sh_data[32:40])[0]
    f.seek(shstr_off)
    shstr = f.read(shstr_size)

    # 遍历所有 section
    results = []
    for i in range(shnum):
        f.seek(shoff + i * shentsize)
        sh = f.read(shentsize)
        if elf_class == ELFCLASS32:
            sh_name = struct.unpack('<I', sh[0:4])[0]
            sh_type = struct.unpack('<I', sh[4:8])[0]
            sh_offset = struct.unpack('<I', sh[16:20])[0]
            sh_size = struct.unpack('<I', sh[20:24])[0]
        else:
            sh_name = struct.unpack('<I', sh[0:4])[0]
            sh_type = struct.unpack('<I', sh[4:8])[0]
            sh_offset = struct.unpack('<Q', sh[24:32])[0]
            sh_size = struct.unpack('<Q', sh[32:40])[0]
        # 找 section 名
        name_end = shstr.find(b'\0', sh_name)
        if name_end < 0:
            name_end = shstr_size
        sec_name = shstr[sh_name:name_end].decode('ascii', errors='replace')
        # 仅关注 .ARM.extab（PROGBITS，size>0）
        if sec_name == '.ARM.extab' and sh_type == SHT_PROGBITS and sh_size > 0:
            results.append((sec_name, sh_offset, sh_size))
    return results


def main():
    ap = argparse.ArgumentParser(description='ELF .ARM.extab 原位清零（依赖 CANTUNWIND 前提）')
    ap.add_argument('so', help='目标 SO 文件')
    ap.add_argument('--inplace', action='store_true', help='原地修改（默认 dry-run）')
    args = ap.parse_args()

    so = Path(args.so)
    if not so.exists():
        print(f"ERR: {so} 不存在", file=sys.stderr)
        sys.exit(1)

    with open(so, 'rb+' if args.inplace else 'rb') as f:
        elf_class, shoff, shentsize, shnum, shstrndx, ehdr_size = parse_elf_header(f)
        extabs = find_extab_sections(f, elf_class, shoff, shentsize, shnum, shstrndx)

    if not extabs:
        print(f"{so.name}: 无 .ARM.extab section，跳过")
        return

    total = 0
    for name, off, size in extabs:
        total += size
        print(f"  {name}: offset=0x{off:x}, size=0x{size:x} ({size:,} B / {size/1024:.1f} KB)")

    if not args.inplace:
        print(f"\n[DRY-RUN] {so.name}: 可清零总计 {total:,} B ({total/1024:.1f} KB)")
        print(f"  启用 --inplace 执行原位清零")
        return

    # 真正清零
    with open(so, 'rb+') as f:
        for name, off, size in extabs:
            f.seek(off)
            f.write(b'\x00' * size)
        f.flush()
    print(f"\n[OK] {so.name}: 已清零 {total:,} B ({total/1024:.1f} KB)")


if __name__ == '__main__':
    main()
