# -*- coding: utf-8 -*-
import sys, struct

p = sys.argv[1]
raw = open(p, 'rb').read()
endian = '<'
e_phoff = struct.unpack(endian + 'Q', raw[32:40])[0]
e_shoff = struct.unpack(endian + 'Q', raw[40:48])[0]
e_phnum = struct.unpack(endian + 'H', raw[56:58])[0]
e_shentsize = struct.unpack(endian + 'H', raw[58:60])[0]
e_shnum = struct.unpack(endian + 'H', raw[60:62])[0]
e_shstrndx = struct.unpack(endian + 'H', raw[62:64])[0]
print(f'file size   : {len(raw)}')
print(f'e_phoff     : {e_phoff:#x}  phnum={e_phnum} phentsize={e_shentsize}')
print(f'e_shoff     : {e_shoff:#x}  shnum={e_shnum} shentsize={e_shentsize}')
print(f'e_shstrndx  : {e_shstrndx}')
print(f'PHDR range  : {e_phoff:#x} .. {e_phoff + e_phnum * e_shentsize:#x}')
print(f'SHDR range  : {e_shoff:#x} .. {e_shoff + e_shnum * e_shentsize:#x}')

# dump program headers
e_phentsize = struct.unpack(endian + 'H', raw[54:56])[0]
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    typ, fl, poff, pva, ppa, pfs, pms, pal = struct.unpack(endian + 'IIQQQQQQ', raw[off:off + 56])
    print(f'  PH[{i}] type={typ:#x} flags={fl:#x} off={poff:#x} vaddr={pva:#x} filesz={pfs:#x} memsz={pms:#x} align={pal:#x}')

# dump section headers
print('sections:')
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    nm, typ, fl, addr, soff, sz, lnk, inf, al, en = struct.unpack(endian + 'IIQQQQIIQQ', raw[off:off + 64])
    print(f'  SH[{i}] name_off={nm} type={typ:#x} addr={addr:#x} off={soff:#x} size={sz:#x} link={lnk} info={inf}')

# shstrtab content
if e_shstrndx and e_shstrndx < e_shnum:
    off = e_shoff + e_shstrndx * e_shentsize
    nm, typ, fl, addr, soff, sz, lnk, inf, al, en = struct.unpack(endian + 'IIQQQQIIQQ', raw[off:off + 64])
    print(f'shstrtab off={soff:#x} size={sz:#x}')
    print('  names:', raw[soff:soff + sz].split(b'\x00'))
