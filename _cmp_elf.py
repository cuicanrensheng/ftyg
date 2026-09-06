# -*- coding: utf-8 -*-
"""对比三个 marsstn v7a 版本：AAR 当前(裁剪) vs 原版 vs 设备上安装的"""
import sys, struct

def header(p, label):
    with open(p, 'rb') as f:
        d = f.read(64)
    if len(d) < 20:
        print(f'{label}: 文件过小或读取失败 len={len(d)}')
        return
    magic = d[:4]
    cls = d[4]          # 1=32bit 2=64bit
    endian = d[5]       # 1=LE
    emachine = struct.unpack('<H', d[18:20])[0]
    print(f'{label:28s} magic={magic.hex()} class={cls}({"ELF32" if cls==1 else "ELF64"}) endian={endian} e_machine={emachine}({ {40:"ARM", 3:"x86", 62:"x86_64", 183:"AARCH64"}.get(emachine,"?") }) size={len(d) if len(d)>=64 else "?"}')

for p, label in [
    (r'app/libs/hysignal-quic-1.5.113.aar', 'AAR 当前(裁剪版)'),
]:
    pass

if __name__ == '__main__':
    import zipfile, tempfile, os
    files = {}
    # 1) AAR 当前 v7a
    with zipfile.ZipFile('app/libs/hysignal-quic-1.5.113.aar') as z:
        name = [n for n in z.namelist() if n.endswith('libmarsstn.so') and 'armeabi-v7a' in n]
        if name:
            t = tempfile.mktemp(suffix='.so'); open(t,'wb').write(z.read(name[0])); files['AAR当前(裁剪版)'] = t
    # 2) 原版 AAR v7a
    with zipfile.ZipFile('_ehframe_backup/hysignal-quic-1.5.113_orig.aar') as z:
        name = [n for n in z.namelist() if n.endswith('libmarsstn.so') and 'armeabi-v7a' in n]
        if name:
            t = tempfile.mktemp(suffix='.so'); open(t,'wb').write(z.read(name[0])); files['原版(854KB)'] = t
    # 3) 设备上拉取的
    dev = '_dev_marsstn_v7a.so'
    if os.path.exists(dev):
        files['设备实际安装'] = dev
    for label, p in files.items():
        header(p, label)
        os.remove(p)
