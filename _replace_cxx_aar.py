# -*- coding: utf-8 -*-
"""用迷你 libc++_shared.so 替换 mmkv aar 内 jni 的对应文件"""
import io, sys, zipfile, shutil, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

AAR = r'app\libs\mmkv-1.0.12.aar'
BAK = r'_backup\mmkv-1.0.12.aar.orig2'
NEW = {
    'jni/arm64-v8a/libc++_shared.so': r'_so_analyze\mini_libc++_shared_arm64.so',
    'jni/armeabi-v7a/libc++_shared.so': r'_so_analyze\mini_libc++_shared_v7a.so',
}

if not os.path.exists(BAK):
    shutil.copy2(AAR, BAK)
    print(f'备份 -> {BAK}')

z = zipfile.ZipFile(AAR)
items = []
for i in z.infolist():
    if i.filename in NEW:
        with z.open(i.filename) as f:
            data = f.read()
        # 使用原压缩参数
        items.append((i, data))
    else:
        with z.open(i.filename) as f:
            items.append((i, f.read()))
z.close()

# 找到要替换的条目并替换数据
out = AAR + '.tmp'
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zo:
    for i, data in items:
        if i.filename in NEW:
            with open(NEW[i.filename], 'rb') as f:
                data = f.read()
            print(f'替换 {i.filename}: {os.path.getsize(NEW[i.filename])//1024} KB')
        info = zipfile.ZipInfo(i.filename, date_time=i.date_time)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = i.external_attr
        zo.writestr(info, data)

os.replace(out, AAR)
print('替换完成')
