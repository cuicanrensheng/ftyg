# -*- coding: utf-8 -*-
"""解析 classes.dex 的类名分布，定位占 dex 大头的包"""
import io, sys, struct, glob, zipfile
from collections import Counter
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

apks = glob.glob(r'app\build\outputs\apk\release\*arm64*.apk')
z = zipfile.ZipFile(apks[0])
data = z.read('classes.dex')

def parse_dex_type_names(data):
    # header 布局: magic(8) checksum(4) signature(20) file_size(4) header_size(4)
    #   endian(4) link_size(4) link_off(4) map_off(4) string_ids_size(4)
    #   string_ids_off(4) type_ids_size(4) type_ids_off(4) ...
    fields = struct.unpack('<8sI20x10I', data[:72])
    magic, checksum, file_size, header_size, endian, link_size, link_off, map_off, \
        string_ids_size, string_ids_off, type_ids_size, type_ids_off = fields
    # type_ids: 每个 4 字节 descriptor_idx
    type_names = []
    for i in range(type_ids_size):
        desc_idx = struct.unpack('<I', data[type_ids_off + i * 4: type_ids_off + i * 4 + 4])[0]
        str_off = struct.unpack('<I', data[string_ids_off + desc_idx * 4: string_ids_off + desc_idx * 4 + 4])[0]
        # MUTF-8: uleb128 长度 + utf16 数据
        p = str_off
        v = data[p] & 0x7f
        p += 1
        while data[p - 1] & 0x80:
            v = (v << 7) | (data[p] & 0x7f)
            p += 1
        raw = data[p:p + v]
        s = raw.decode('utf-8', 'replace')
        type_names.append(s)
    return type_names
    # type_ids: descriptor_idx (4 bytes each)
    type_names = []
    for i in range(type_ids_size):
        desc_idx = struct.unpack('<I', data[type_ids_off + i * 4: type_ids_off + i * 4 + 4])[0]
        # string at string_ids[desc_idx]
        str_off = struct.unpack('<I', data[string_ids_off + desc_idx * 4: string_ids_off + desc_idx * 4 + 4])[0]
        # uleb128 length
        p = str_off
        while data[p] & 0x80:
            p += 1
        ln = data[str_off] & 0x7f
        # read MUTF-8 string
        end = p + 1 + ln
        raw = data[p + 1:end]
        s = raw.decode('utf-8', 'replace')
        type_names.append(s)
    return type_names

types = parse_dex_type_names(data)
print('classes.dex 类型总数:', len(types))

cnt = Counter()
for t in types:
    # 格式 Lpackage/Class;
    if not t.startswith('L'):
        continue
    path = t[1:-1].replace('/', '.')
    parts = path.split('.')
    # 取前 2 段作为包前缀（类名去掉）
    cls = parts[-1]
    if '$' in cls or 'R$' in cls or cls == 'R' or cls == 'BuildConfig':
        pkg = '.'.join(parts[:-1]) + '.R-class'
    else:
        pkg = '.'.join(parts[:-1])
    # 归类到顶层/二级包
    top = '.'.join(parts[:2]) if len(parts) >= 2 else '.'.join(parts)
    cnt[top] += 1

print()
print('== 类型数 top 30（二级包）==')
for pkg, n in cnt.most_common(30):
    print(f'  {pkg:55s} {n}')
