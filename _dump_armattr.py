# -*- coding: utf-8 -*-
"""解析 .ARM.attributes + e_flags + DT_NEEDED，对比 Houdini 成功/失败库"""
import sys, struct
sys.stdout.reconfigure(encoding='utf-8')
from elftools.elf.elffile import ELFFile
from elftools.elf.dynamic import DynamicSection

TAGS = {
    4: 'Tag_ARM_ISA_use', 5: 'Tag_THUMB_ISA_use', 6: 'Tag_CPU_arch',
    7: 'Tag_CPU_arch_profile', 8: 'Tag_ARM_ISA_use(ARMv8)', 10: 'Tag_NEON',
    28: 'Tag_ABI_VFP_args', 44: 'Tag_VFP_arch', 64: 'Tag_Advanced_SIMD_arch',
    68: 'Tag_Advanced_SIMD_arch2',
}
ARCH = {0:'pre-v4',1:'v4',2:'v4T',3:'v5T',4:'v5TE',5:'v5TEJ',6:'v6',7:'v6KZ',
        8:'v6T2',9:'v6K',10:'v7',11:'v6-M',12:'v6S-M',13:'v7E-M',14:'v8',
        15:'v8-M',16:'v8-R',17:'v8.1-M',18:'v8.1',19:'v8.2',20:'v8.3',21:'v8.4'}
PROF = {0:'None',1:'A',2:'R',3:'M',4:'S'}

def parse_arm_attributes(data):
    out = []
    pos = 0
    while pos < len(data):
        tag = data[pos]; pos += 1
        (ln,) = struct.unpack_from('<I', data, pos); pos += 4
        end = pos + ln - 1
        name = data[pos:pos+4]
        if name == b'aeabi':
            pos += 4
            # 子节
            while pos < end:
                st = data[pos]; pos += 1
                (sln,) = struct.unpack_from('<I', data, pos); pos += 4
                send = pos + sln - 1
                if st == 1:  # Tag_File
                    while pos < send:
                        t = data[pos]; pos += 1
                        (tln,) = struct.unpack_from('<I', data, pos); pos += 4
                        val = data[pos:pos+tln-1]
                        pos += tln - 1
                        if t == 6:
                            out.append(f'{TAGS.get(t,t)}={ARCH.get(val[0] if val else 0, val)}')
                        elif t == 7:
                            out.append(f'{TAGS.get(t,t)}={PROF.get(val[0] if val else 0, val)}')
                        else:
                            out.append(f'{TAGS.get(t,t)}={val!r}')
                else:
                    out.append(f'subsection tag={st} len={sln}')
                    pos = send
        pos = end + 1
    return out

FILES = [
    ('成功-tvlive_security', '_so_v7a/lib/armeabi-v7a/libtvlive_security.so'),
    ('成功-stlport_shared(stub)', '_so_v7a/lib/armeabi-v7a/libstlport_shared.so'),
    ('失败-c++_shared', '_so_v7a/lib/armeabi-v7a/libc++_shared.so'),
    ('失败-marsstn(原版)', '_ehframe_backup/libmarsstn_v7a_orig.so'),
    ('hycrypto(裁剪版)', '_so_v7a/lib/armeabi-v7a/libhycrypto.so'),
    ('hyssl(裁剪版)', '_so_v7a/lib/armeabi-v7a/libhyssl.so'),
]

for label, p in FILES:
    try:
        with open(p, 'rb') as f:
            e = ELFFile(f)
            h = e.header
            iden = h['e_ident']
            size = len(open(p, 'rb').read())
            print(f'== {label} | {p} | {size} bytes ==')
            print(f'  osabi={iden["EI_OSABI"]} flags={h["e_flags"]:#x} entry={h["e_entry"]:#x}')
            flags = h['e_flags']
            abi = flags & 0xff000000
            print(f'  ABI={abi:#x} (EABI5={abi==0x05000000}) float={flags & 0x00f00000:#x}')
            # .ARM.attributes
            for sec in e.iter_sections():
                if sec.name == '.ARM.attributes':
                    attrs = parse_arm_attributes(sec.data())
                    print('  .ARM.attributes:', ' | '.join(attrs))
                if sec.name == '.ARM.exidx':
                    print(f'  .ARM.exidx size={sec["sh_size"]}')
            # DT_NEEDED
            for sec in e.iter_sections():
                if isinstance(sec, DynamicSection):
                    needed = [t.entry.d_val for t in sec.iter_tags('DT_NEEDED')]
                    if needed:
                        strs = [sec.stringtable.get_string(n) for n in needed]
                        print('  DT_NEEDED:', ', '.join(strs))
            print()
    except Exception as ex:
        print(f'== {label} ERROR: {ex}')
        print()
