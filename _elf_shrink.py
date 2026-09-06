# -*- coding: utf-8 -*-
"""ELF 紧凑重写器：删除指定 section（.eh_frame/.eh_frame_hdr/.note.android.ident/.comment）
并真正压缩文件布局：按 PT_LOAD 分组重排文件偏移、拆分 LOAD、重建 PHDR/SHDR。
用法: python _elf_shrink.py <in.so> <out.so>
"""
import sys
import struct
from elftools.elf.elffile import ELFFile

DROP = {'.eh_frame', '.eh_frame_hdr', '.note.android.ident', '.comment', '.ARM.exidx'}

PT_NULL, PT_LOAD, PT_DYNAMIC, PT_INTERP, PT_NOTE = 0, 1, 2, 3, 4
_PT_MAP = {
    'PT_NULL': 0, 'PT_LOAD': 1, 'PT_DYNAMIC': 2, 'PT_INTERP': 3, 'PT_NOTE': 4,
    'PT_PHDR': 6, 'PT_TLS': 7, 'PT_GNU_EH_FRAME': 0x6474e50, 'PT_GNU_STACK': 0x6474e51,
    'PT_GNU_RELRO': 0x6474e52, 'PT_ARM_EXIDX': 0x70000001,
}
_SH_MAP = {
    'SHT_NULL': 0, 'SHT_PROGBITS': 1, 'SHT_SYMTAB': 2, 'SHT_STRTAB': 3, 'SHT_RELA': 4,
    'SHT_HASH': 5, 'SHT_DYNAMIC': 6, 'SHT_NOTE': 7, 'SHT_NOBITS': 8, 'SHT_REL': 9,
    'SHT_DYNSYM': 11, 'SHT_INIT_ARRAY': 14, 'SHT_FINI_ARRAY': 15, 'SHT_PREINIT_ARRAY': 16,
    'SHT_GROUP': 17, 'SHT_SYMTAB_SHNDX': 18,     'SHT_GNU_versym': 0x6fffffff,
    'SHT_GNU_verdef': 0x6ffffffd, 'SHT_GNU_verneed': 0x6ffffffe, 'SHT_GNU_HASH': 0x6ffffff6,
    'SHT_ARM_EXIDX': 0x70000001, 'SHT_ARM_ATTRIBUTES': 0x70000003,
    'SHT_RELR': 19, 'SHT_ANDROID_RELR': 0x6fffff00,
}


def _ptype(v):
    return _PT_MAP.get(v, v) if isinstance(v, str) else v


def _stype(v):
    return _SH_MAP.get(v, v) if isinstance(v, str) else v


def align_up(x, a):
    if a <= 1:
        return x
    return (x + a - 1) & ~(a - 1)


def same_remainder_atleast(pos, vaddr, align):
    """最小 x >= pos 且 x % align == vaddr % align"""
    if align <= 1:
        return pos
    r = vaddr % align
    m = pos % align
    if m <= r:
        return pos + (r - m)
    return pos + (align - m + r)


def main(src, dst):
    with open(src, 'rb') as f:
        raw = f.read()
        elf = ELFFile(f)
        e = elf.header
        ident = e['e_ident']
        is64 = ident['EI_CLASS'] == 'ELFCLASS64'
        endian = '<' if ident['EI_DATA'] == 'ELFDATA2LSB' else '>'

        if is64:
            PH_FMT = endian + 'IIQQQQQQ'   # type flags offset vaddr paddr filesz memsz align
            SH_FMT = endian + 'IIQQQQIIQQ'  # name type flags addr offset size link info align entsize
            EH_FMT = endian + '16sHHIQQQIHHHHHH'
            PH_SIZE, SH_SIZE, EH_SIZE = 56, 64, 64
        else:
            PH_FMT = endian + 'IIIIIIII'   # type offset vaddr paddr filesz memsz flags align
            SH_FMT = endian + 'IIIIIIIIII'
            EH_FMT = endian + '16sHHIIIIIHHHHHH'
            PH_SIZE, SH_SIZE, EH_SIZE = 32, 40, 52

        e_ident_bytes = raw[0:16]
        # raw numeric fields from ELF header (avoid pyelftools enum strings)
        e_type_r = struct.unpack(endian + 'H', raw[16:18])[0]
        e_machine_r = struct.unpack(endian + 'H', raw[18:20])[0]
        e_version_r = struct.unpack(endian + 'I', raw[20:24])[0]
        if is64:
            e_entry_r = struct.unpack(endian + 'Q', raw[24:32])[0]
        else:
            e_entry_r = struct.unpack(endian + 'I', raw[24:28])[0]
        e_flags_r = struct.unpack(endian + 'I', raw[36:40])[0]

        # ---- collect sections ----
        secs = []
        for s in elf.iter_sections():
            h = s.header
            st = h['sh_type']
            data = b'' if st == 'SHT_NOBITS' else raw[h['sh_offset']:h['sh_offset'] + h['sh_size']]
            secs.append({
                'name': s.name, 'type': st, 'flags': h['sh_flags'], 'addr': h['sh_addr'],
                'offset': h['sh_offset'], 'size': h['sh_size'], 'link': h['sh_link'],
                'info': h['sh_info'], 'align': h['sh_addralign'], 'entsize': h['sh_entsize'],
                'data': data, 'name_off': h['sh_name'],
            })

        # ---- collect program headers ----
        phdrs = []
        for seg in elf.iter_segments():
            p = seg.header
            phdrs.append((_ptype(p['p_type']), p['p_flags'], p['p_offset'], p['p_vaddr'],
                          p['p_filesz'], p['p_memsz'], p['p_align']))

        drop_names = {s['name'] for s in secs if s['type'] != 'SHT_NULL' and s['name'] in DROP}

        # ---- group keep sections into LOAD runs (split at addr gap > 0x1000) ----
        runs = []  # {'flags', 'sections':[...]}
        for (pt, flags, poff, pvaddr, pfilesz, pmemsz, palign) in phdrs:
            if pt != PT_LOAD:
                continue
            end_v = pvaddr + max(pfilesz, pmemsz)
            end_f = poff + pfilesz
            members = [s for s in secs
                       if s['name'] not in drop_names and s['type'] != 'SHT_NULL'
                       and pvaddr <= s['addr'] < end_v
                       and poff <= s['offset'] < end_f]
            members.sort(key=lambda s: s['addr'])
            cur = []
            for s in members:
                if cur and (s['addr'] - (cur[-1]['addr'] + cur[-1]['size'])) > 0x1000:
                    runs.append({'flags': flags, 'sections': cur})
                    cur = []
                cur.append(s)
            if cur:
                runs.append({'flags': flags, 'sections': cur})

        # sections NOT covered by any run (e.g. .shstrtab) get appended after runs
        covered = set()
        for r in runs:
            for s in r['sections']:
                covered.add(s['name'])
        extra = [s for s in secs if s['type'] != 'SHT_NULL' and s['name'] not in drop_names
                 and s['name'] not in covered]

        # ---- compute layout ----
        n_ph = len(runs) + 2 + 1  # runs + DYNAMIC + NOTE + NULL
        header_size = EH_SIZE + n_ph * PH_SIZE
        blob = bytearray(b'\x00' * header_size)
        pos = header_size

        new_loads = []  # (type, flags, file_off, vaddr, filesz, memsz, align)
        for r in runs:
            mem = r['sections']
            start_v = mem[0]['addr']
            end_v = mem[-1]['addr'] + mem[-1]['size']
            for s in mem:  # include NOBITS memsz
                end_v = max(end_v, s['addr'] + s['size'])
            memsz = end_v - start_v
            align = 0x1000
            file_off = same_remainder_atleast(pos, start_v, align)
            # pad blob to the load start offset
            if len(blob) < file_off:
                blob += b'\x00' * (file_off - len(blob))
            cur = file_off
            for s in mem:
                if s['type'] == 'SHT_NOBITS':
                    continue
                want = file_off + (s['addr'] - start_v)
                gap = want - cur
                assert gap >= 0, f"negative gap {gap}"
                blob += b'\x00' * gap
                blob += s['data']
                s['new_off'] = want
                cur = want + len(s['data'])
            filesz = cur - file_off
            new_loads.append(('PT_LOAD', r['flags'], file_off, start_v, filesz, memsz, align))
            pos = cur

        # place extra sections (shstrtab etc.)
        for s in extra:
            if len(blob) < pos:
                blob += b'\x00' * (pos - len(blob))
            s['new_off'] = pos
            blob += s['data']
            pos += len(s['data'])

        dyn = next((s for s in secs if s['name'] == '.dynamic'), None)
        note = next((s for s in secs if s['name'] == '.note.gnu.build-id'), None)
        print(f'[DBG] dyn={dyn is not None} note={note is not None} runs={len(runs)} loads={len(new_loads)}')

        # ---- build new PHDR list ----
        new_ph = [('PT_NULL', 0, 0, 0, 0, 0, 0)]
        for (t, fl, off, va, fs, ms, al) in new_loads:
            new_ph.append((t, fl, off, va, fs, ms, al))
        if dyn:
            new_ph.append(('PT_DYNAMIC', 2, dyn['new_off'], dyn['addr'], dyn['size'], dyn['size'], 8))
        else:
            print('[WARN] .dynamic not found')
        if note:
            new_ph.append(('PT_NOTE', 4, note['new_off'], note['addr'], note['size'], note['size'], 4))
        else:
            print('[WARN] .note.gnu.build-id not found')

        # ---- write PHDRs ----
        for i, (t, fl, off, va, fs, ms, al) in enumerate(new_ph):
            t_i = _ptype(t)
            if t_i == 0:
                phb = b'\x00' * PH_SIZE
            elif is64:
                phb = struct.pack(PH_FMT, t_i, fl, off, va, va, fs, ms, al)
            else:
                phb = struct.pack(PH_FMT, t_i, off, va, va, fs, ms, fl, al)
            blob[EH_SIZE + i * PH_SIZE: EH_SIZE + (i + 1) * PH_SIZE] = phb

        # ---- build section header table ----
        # old index -> name mapping
        old_idx_name = []
        for s in secs:
            old_idx_name.append(s['name'])
        # assign new indices
        new_idx = 0
        idx_map = {}  # name -> new index
        for s in secs:
            if s['type'] == 'SHT_NULL' or s['name'] in drop_names:
                continue
            new_idx += 1
            idx_map[s['name']] = new_idx

        def remap(old_index):
            if old_index == 0:
                return 0
            if old_index < len(old_idx_name):
                nm = old_idx_name[old_index]
                return idx_map.get(nm, 0)
            return 0

        shoff = align_up(len(blob), 8)
        if shoff != len(blob):
            blob += b'\x00' * (shoff - len(blob))

        shnum = new_idx + 1
        shstrndx = idx_map.get('.shstrtab', 0)

        blob += b'\x00' * SH_SIZE  # NULL section header (index 0)

        for s in secs:
            if s['type'] == 'SHT_NULL' or s['name'] in drop_names:
                continue
            new_off = s.get('new_off', s['offset'])
            sh_size = s['size']
            # .shstrtab content offset: it lives where we placed it
            if s['name'] == '.shstrtab':
                new_off = s['new_off']
            link = remap(s['link'])
            # sh_info is a section index only for REL/RELA sections;
            # for DYNSYM it is the first-global index, for VERDEF/VERNEED it
            # is the number of definitions/needs, for others usually 0.
            st_i = _stype(s['type'])
            if st_i in (4, 9, 17):  # SHT_RELA, SHT_REL, SHT_GROUP
                info = remap(s['info'])
            else:
                info = s['info']
            if is64:
                shb = struct.pack(SH_FMT, s['name_off'], _stype(s['type']), s['flags'], s['addr'],
                                  new_off, sh_size, link, info, s['align'], s['entsize'])
            else:
                shb = struct.pack(SH_FMT, s['name_off'], _stype(s['type']), s['flags'], s['addr'],
                                  new_off, sh_size, link, info, s['align'], s['entsize'])
            blob += shb

        # ---- write ELF header ----
        e_phoff = EH_SIZE
        e_shoff = shoff
        if is64:
            ehb = struct.pack(EH_FMT, e_ident_bytes, e_type_r, e_machine_r, e_version_r,
                              e_entry_r, e_phoff, e_shoff, e_flags_r, EH_SIZE, PH_SIZE,
                              len(new_ph), SH_SIZE, shnum, shstrndx)
        else:
            ehb = struct.pack(EH_FMT, e_ident_bytes, e_type_r, e_machine_r, e_version_r,
                              e_entry_r, e_phoff, e_shoff, e_flags_r, EH_SIZE, PH_SIZE,
                              len(new_ph), SH_SIZE, shnum, shstrndx)
        blob[0:EH_SIZE] = ehb

        with open(dst, 'wb') as f:
            f.write(blob)

        saved = len(raw) - len(blob)
        print(f'[OK] {src}')
        print(f'     {len(raw)} -> {len(blob)} bytes (saved {saved})')
        print(f'     phdrs={len(new_ph)} shnum={shnum} shstrndx={shstrndx} dropped={sorted(drop_names)}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
