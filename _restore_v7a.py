# -*- coding: utf-8 -*-
"""恢复 hysignal-quic AAR 内 v7a libmarsstn.so 为原版（arm64 保持裁剪版）"""
import zipfile, shutil, os

AAR = 'app/libs/hysignal-quic-1.5.113.aar'
BAK = '_ehframe_backup/hysignal-quic-1.5.113_orig.aar'
ORIG_ENTRY = 'jni/armeabi-v7a/libmarsstn.so'
TMP = '_aar_tmp.zip'

# 读原版 v7a marsstn
with zipfile.ZipFile(BAK) as z:
    orig = z.read(ORIG_ENTRY)
print(f'原版 v7a marsstn: {len(orig)} bytes')

# 备份当前 AAR（含裁剪版）
shutil.copy2(AAR, '_ehframe_backup/hysignal-quic-1.5.113_shrunk.aar')

# 重建 AAR：替换 v7a marsstn
with zipfile.ZipFile(AAR) as zin, zipfile.ZipFile(TMP, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == ORIG_ENTRY:
            data = orig
            print(f'替换 {item.filename} -> 原版 {len(data)} bytes')
        zout.writestr(item, data)

shutil.move(TMP, AAR)
print('AAR 已更新')

# 验证
with zipfile.ZipFile(AAR) as z:
    for m in z.namelist():
        if 'marsstn' in m:
            print(f'  {m}: {z.getinfo(m).file_size} bytes')
