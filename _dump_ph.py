# -*- coding: utf-8 -*-
"""dump 原版/裁剪版 v7a marsstn 的完整 PHDR + 关键 section"""
import zipfile, tempfile, os
from elftools.elf.elffile import ELFFile

def dump(p, label):
    print(f'===== {label} =====')
    with open(p, 'rb') as f:
        e = ELFFile(f)
        for seg in e.iter_segments():
            h = seg.header
            print(f'  PHDR {h.p_type:18s} off={h.p_offset:#x} vaddr={h.p_vaddr:#x} filesz={h.p_filesz:#x} memsz={h.p_memsz:#x} align={h.p_align}')
        print('  sections:')
        for s in e.iter_sections():
            h = s.header
            if h['sh_size'] and h['sh_type'] != 'SHT_NOBITS':
                print(f'    {s.name:26s} addr={h["sh_addr"]:#x} off={h["sh_offset"]:#x} size={h["sh_size"]:#x}')

def extract(aar, abi, out):
    with zipfile.ZipFile(aar) as z:
        name = [n for n in z.namelist() if n.endswith('libmarsstn.so') and abi in n][0]
        open(out, 'wb').write(z.read(name))
    return out

a1 = extract('app/libs/hysignal-quic-1.5.113.aar', 'armeabi-v7a', '_m_new.so')
a2 = extract('_ehframe_backup/hysignal-quic-1.5.113_orig.aar', 'armeabi-v7a', '_m_orig.so')
dump(a2, '原版 v7a marsstn')
dump(a1, '裁剪版 v7a marsstn')
os.remove(a1); os.remove(a2)
