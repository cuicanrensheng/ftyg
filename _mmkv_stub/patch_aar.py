# -*- coding: utf-8 -*-
"""修改 mmkv-1.0.12.aar：替换 arm64-v8a / armeabi-v7a 的 libmmkv.so 为 JNI stub"""
import zipfile, shutil, io, sys, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

AAR = r"d:\ASDF\TV Live\AH-main\app\libs\mmkv-1.0.12.aar"
BAK = r"d:\ASDF\TV Live\AH-main\app\libs\mmkv-1.0.12.aar.bak"
STUB_V7A = r"d:\ASDF\TV Live\AH-main\_mmkv_stub\libmmkv_v7a.so"
STUB_ARM64 = r"d:\ASDF\TV Live\AH-main\_mmkv_stub\libmmkv_arm64.so"

# 1. 备份
shutil.copy2(AAR, BAK)
print("已备份 ->", os.path.basename(BAK))

# 2. 读取原 aar 全部条目
zin = zipfile.ZipFile(AAR)
entries = []
for info in zin.infolist():
    entries.append((info, zin.read(info.filename)))
zin.close()

# 3. 替换目标 so
targets = {
    "jni/armeabi-v7a/libmmkv.so": STUB_V7A,
    "jni/arm64-v8a/libmmkv.so": STUB_ARM64,
}
replaced = 0
for i, (info, data) in enumerate(entries):
    if info.filename in targets:
        entries[i] = (info, open(targets[info.filename], "rb").read())
        print("替换:", info.filename, f"{len(data)} B -> {len(entries[i][1])} B")
        replaced += 1
assert replaced == 2, f"应替换 2 个 so，实际 {replaced}"

# 4. 写新 aar（压缩）
with zipfile.ZipFile(AAR, "w", zipfile.ZIP_DEFLATED) as zout:
    for info, data in entries:
        zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = info.external_attr
        zout.writestr(zi, data)

# 5. 验证
z = zipfile.ZipFile(AAR)
for n in sorted(z.namelist()):
    if "libmmkv" in n:
        print(f"  验证 {n}: {z.getinfo(n).file_size} B")
print("aar 修改完成")
