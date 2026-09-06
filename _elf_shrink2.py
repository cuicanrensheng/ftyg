# -*- coding: utf-8 -*-
"""ELF 紧凑重写器 V2（保守模式 --keep-unwind）

V1 (_elf_shrink.py) 删除 .eh_frame/.eh_frame_hdr/.ARM.exidx 及全部非 LOAD program
headers，导致 Houdini 12 转译 ARM64 时 SIGSEGV（十五节），arm64 裁剪整体回滚。

V2 假设：Houdini 12 的 unwinder 依赖合法的 EH_FRAME 段描述。
V2 策略：
  - 保留 .eh_frame / .eh_frame_hdr / .ARM.exidx 数据与 section
  - 保留 PT_GNU_EH_FRAME / PT_GNU_RELRO / PT_GNU_STACK / PT_NOTE / PT_ARM_EXIDX
    program headers，offset 按新布局重算（vaddr 不变）
  - 仅删除 .note.android.ident / .comment + 布局紧凑重排（vaddr 不变）
  - PT_PHDR 丢弃（V1 无它可正常加载，已验证）

用法: python _elf_shrink2.py <in.so> <out.so>
"""
import sys
import struct
from elftools.elf.elffile import ELFFile

DROP = {'.note.android.ident', '.comment'}

PT_NULL, PT_LOAD, PT_DYNAMIC, PT_INTERP, PT_NOTE = 0, 1, 2, 3, 4
PT_GNU_EH_FRAME = 0x6474e550
PT_GNU_STACK = 0x6474e551
PT_GNU_RELRO = 0x6474e552
PT_PHDR = 6
PT_ARM_EXIDX = 0x70000001


def align_up(x, a):
    if a <= 1:
        return x
    return (x + a - 1) & ~(a - 1)


def same_remainder_atleast(pos, vaddr, align):
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
            PH_FMT = endian + 'IIQQQQQQ'
            SH_FMT = endian + 'IIQQQQIIQQ'
            EH_FMT = endian + '16sHHIQQQIHHHHHH'
            PH_SIZE, SH_SIZE, EH_SIZE = 56, 64, 64
        else:
            PH_FMT = endian + 'IIIIIIII'
            SH_FMT = endian + 'IIIIIIIIII'
            EH_FMT = endian + '16sHHIIIIIHHHHHH'
            PH_SIZE, SH_SIZE, EH_SIZE = 32, 40, 52

        e_ident_bytes = raw[0:16]
        e_type_r = struct.unpack(endian + 'H', raw[16:18])[0]
        e_machine_r = struct.unpack(endian + 'H', raw[18:20])[0]
        e_version_r = struct.unpack(endian + 'I', raw[20:24])[0]
        if is64:
            e_entry_r = struct.unpack(endian + 'Q', raw[24:32])[0]
        else:
            e_entry_r = struct.unpack(endian + 'I', raw[24:28])[0]
        e_flags_r = struct.unpack(endian + 'I', raw[36:40])[0]

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

        raw_phdrs = []
        for seg in elf.iter_segments():
            p = seg.header
            t = p['p_type']
            if not isinstance(t, int):
                tm = {'PT_NULL': 0, 'PT_LOAD': 1, 'PT_DYNAMIC': 2, 'PT_INTERP': 3, 'PT_NOTE': 4,
                      'PT_PHDR': 6, 'PT_TLS': 7, 'PT_GNU_EH_FRAME': PT_GNU_EH_FRAME,
                      'PT_GNU_STACK': PT_GNU_STACK, 'PT_GNU_RELRO': PT_GNU_RELRO,
                      'PT_ARM_EXIDX': PT_ARM_EXIDX}
                t = tm.get(t, t)
            raw_phdrs.append((t, p['p_flags'], p['p_offset'], p['p_vaddr'],
                              p['p_filesz'], p['p_memsz'], p['p_align']))

        drop_names = {s['name'] for s in secs if s['type'] != 'SHT_NULL' and s['name'] in DROP}

        # ---- LOAD runs（同 V1）----
        runs = []
        for (pt, flags, poff, pvaddr, pfilesz, pmemsz, palign) in raw_phdrs:
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

        covered = set()
        for r in runs:
            for s in r['sections']:
                covered.add(s['name'])
        extra = [s for s in secs if s['type'] != 'SHT_NULL' and s['name'] not in drop_names
                 and s['name'] not in covered]

        n_ph = len(runs) + 2 + len([p for p in raw_phdrs if p[0] not in (PT_LOAD, PT_PHDR)])
        header_size = EH_SIZE + n_ph * PH_SIZE
        blob = bytearray(b'\x00' * header_size)
        pos = header_size

        new_loads = []
        for r in runs:
            mem = r['sections']
            start_v = mem[0]['addr']
            end_v = mem[-1]['addr'] + mem[-1]['size']
            for s in mem:
                end_v = max(end_v, s['addr'] + s['size'])
            memsz = end_v - start_v
            align = 0x1000
            file_off = same_remainder_atleast(pos, start_v, align)
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

        for s in extra:
            if len(blob) < pos:
                blob += b'\x00' * (pos - len(blob))
            s['new_off'] = pos
            blob += s['data']
            pos += len(s['data'])

        # vaddr -> 新 file offset 映射（PH remap 用）
        def map_off(vaddr):
            for (t, fl, off, va, fs, ms, al) in new_loads:
                if va <= vaddr < va + max(fs, ms):
                    if vaddr - va >= fs:
                        return None  # NOBITS 区域，无文件映射
                    return off + (vaddr - va)
            return None

        # ---- 新 PH 列表 ----
        new_ph = [('PT_NULL', 0, 0, 0, 0, 0, 0)]
        for (t, fl, off, va, fs, ms, al) in new_loads:
            new_ph.append((t, fl, off, va, fs, ms, al))

        dyn = next((s for s in secs if s['name'] == '.dynamic'), None)
        kept_note = 0
        dropped_ph = []
        for (pt, flags, poff, pvaddr, pfilesz, pmemsz, palign) in raw_phdrs:
            if pt in (PT_LOAD, PT_PHDR, PT_NULL):
                if pt == PT_PHDR:
                    dropped_ph.append('PT_PHDR')
                continue
            if pt == PT_DYNAMIC:
                continue  # 已单独处理
            if pt == PT_NOTE:
                # 仅保留仍存在的 note section（.note.android.ident 已删）
                matched = None
                for s in secs:
                    if s['type'] == 'SHT_NOTE' and s['name'] not in drop_names:
                        if poff <= s['offset'] < poff + pfilesz:
                            matched = s
                            break
                if matched is not None:
                    new_ph.append(('PT_NOTE', flags, matched['new_off'], matched['addr'],
                                   matched['size'], matched['size'], palign))
                    kept_note += 1
                else:
                    dropped_ph.append('PT_NOTE(android.ident)')
                continue
            # PT_GNU_EH_FRAME / PT_GNU_RELRO / PT_GNU_STACK / PT_ARM_EXIDX ...
            new_off = map_off(pvaddr) if pfilesz > 0 else 0
            if pfilesz > 0 and new_off is None:
                dropped_ph.append(f'PH({hex(pt)}) 无法映射，丢弃')
                continue
            if pfilesz == 0:
                new_off = 0
            new_ph.append((pt, flags, new_off, pvaddr, pfilesz, pmemsz, palign))
        # PT_DYNAMIC 放最后附加（同 V1 顺序尾部）
        if dyn:
            new_ph.append(('PT_DYNAMIC', 2, dyn['new_off'], dyn['addr'], dyn['size'], dyn['size'], 8))
        else:
            print('[WARN] .dynamic not found')

        for i, (t, fl, off, va, fs, ms, al) in enumerate(new_ph):
            if isinstance(t, str):
                t_i = {'PT_NULL': 0, 'PT_LOAD': 1, 'PT_DYNAMIC': 2, 'PT_NOTE': 4}.get(t)
                if t_i is None:
                    raise SystemExit(f'unknown ph type {t}')
            else:
                t_i = t
            if t_i == 0:
                phb = b'\x00' * PH_SIZE
            elif is64:
                phb = struct.pack(PH_FMT, t_i, fl, off, va, va, fs, ms, al)
            else:
                phb = struct.pack(PH_FMT, t_i, off, va, va, fs, ms, fl, al)
            blob[EH_SIZE + i * PH_SIZE: EH_SIZE + (i + 1) * PH_SIZE] = phb

        # ---- section header table（同 V1）----
        old_idx_name = [s['name'] for s in secs]
        new_idx = 0
        idx_map = {}
        for s in secs:
            if s['type'] == 'SHT_NULL' or s['name'] in drop_names:
                continue
            new_idx += 1
            idx_map[s['name']] = new_idx

        def remap(old_index):
            if old_index == 0:
                return 0
            if old_index < len(old_idx_name):
                return idx_map.get(old_idx_name[old_index], 0)
            return 0

        shoff = align_up(len(blob), 8)
        if shoff != len(blob):
            blob += b'\x00' * (shoff - len(blob))

        shnum = new_idx + 1
        shstrndx = idx_map.get('.shstrtab', 0)
        blob += b'\x00' * SH_SIZE
        for s in secs:
            if s['type'] == 'SHT_NULL' or s['name'] in drop_names:
                continue
            new_off = s.get('new_off', s['offset'])
            link = remap(s['link'])
            st_i = s['type'] if isinstance(s['type'], int) else {
                'SHT_NULL': 0, 'SHT_PROGBITS': 1, 'SHT_SYMTAB': 2, 'SHT_STRTAB': 3, 'SHT_RELA': 4,
                'SHT_HASH': 5, 'SHT_DYNAMIC': 6, 'SHT_NOTE': 7, 'SHT_NOBITS': 8, 'SHT_REL': 9,
                'SHT_DYNSYM': 11, 'SHT_INIT_ARRAY': 14, 'SHT_FINI_ARRAY': 15,
                'SHT_PREINIT_ARRAY': 16, 'SHT_GROUP': 17, 'SHT_SYMTAB_SHNDX': 18,
                'SHT_GNU_versym': 0x6fffffff, 'SHT_GNU_verdef': 0x6ffffffd,
                'SHT_GNU_verneed': 0x6ffffffe, 'SHT_GNU_HASH': 0x6ffffff6,
                'SHT_ARM_EXIDX': 0x70000001, 'SHT_ARM_ATTRIBUTES': 0x70000003,
                'SHT_RELR': 19, 'SHT_ANDROID_RELR': 0x6fffff00}.get(s['type'], 1)
            info = remap(s['info']) if st_i in (4, 9, 17) else s['info']
            shb = struct.pack(SH_FMT, s['name_off'], st_i, s['flags'], s['addr'],
                              new_off, s['size'], link, info, s['align'], s['entsize'])
            blob += shb

        e_phoff = EH_SIZE
        e_shoff = shoff
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
        print(f'     dropped_ph={dropped_ph}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
