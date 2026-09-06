# -*- coding: utf-8 -*-
"""对比原版 vs 裁剪版 marsstn v7a 的 program headers 与 dynamic 段"""
import zipfile, tempfile, os, sys
from elftools.elf.elffile import ELFFile

def dump(p, label):
    print(f'===== {label} =====')
    with open(p, 'rb') as f:
        e = ELFFile(f)
        print('PHDR:')
        for seg in e.iter_segments():
            print(f'  {seg.header.p_type:12s} vaddr={seg.header.p_vaddr:#x} filesz={seg.header.p_filesz:#x} memsz={seg.header.p_memsz:#x} align={seg.header.p_align}')
        dyn = e.get_section_by_name('.dynamic')
        if dyn:
            print('DYNAMIC:')
            for t in dyn.iter_tags():
                if t.entry.d_tag in ('DT_NEEDED', 'DT_SONAME', 'DT_RPATH', 'DT_RUNPATH'):
                    val = t.needed if t.entry.d_tag in ('DT_NEEDED', 'DT_SONAME') else t.entry.d_val
                    print(f'  {t.entry.d_tag:12s} = {val}')
        symtab = e.get_section_by_name('.dynsym')
        if symtab:
            print(f'.dynsym symbols: {symtab.num_symbols()}')

def extract(aar, abi, out):
    with zipfile.ZipFile(aar) as z:
        name = [n for n in z.namelist() if n.endswith('libmarsstn.so') and abi in n]
        open(out, 'wb').write(z.read(name[0]))
    return out

a1 = extract('app/libs/hysignal-quic-1.5.113.aar', 'armeabi-v7a', '_m_new.so')
a2 = extract('_ehframe_backup/hysignal-quic-1.5.113_orig.aar', 'armeabi-v7a', '_m_orig.so')
dump(a2, '原版 v7a marsstn')
dump(a1, '裁剪版 v7a marsstn')
os.remove(a1); os.remove(a2)
