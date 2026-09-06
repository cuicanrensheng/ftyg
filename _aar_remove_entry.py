"""
AAR 单文件剔除器（保持 entries 顺序与空目录）
=================================================

【背景】
直接用 ZipFile.ExtractToDirectory + CreateFromDirectory 会丢失空目录
（如 assets/），导致 AGP mergeReleaseAssets 报 NullPointerException。

本工具：原地拷贝原 AAR 字节流，仅删除指定 entry，保留其余全部 entry
（含空目录）的顺序与元数据。

【用法】
  python _aar_remove_entry.py <aar> <entry_name_to_remove>
  示例：python _aar_remove_entry.py hyhal-1.9.105-exvolley.aar assets/cacert.pem
"""

import sys
import zipfile
import shutil
from pathlib import Path


def main():
    if len(sys.argv) < 3:
        print("用法: python _aar_remove_entry.py <aar> <entry>")
        sys.exit(1)

    aar = Path(sys.argv[1])
    target = sys.argv[2]
    if not aar.exists():
        print(f"ERR: {aar} 不存在")
        sys.exit(1)

    bak = aar.with_suffix(aar.suffix + ".bak")
    if not bak.exists():
        shutil.copy2(aar, bak)
        print(f"备份: {bak}")

    # 读所有 entry；过滤掉 target
    tmp = aar.with_suffix(aar.suffix + ".tmp")
    with zipfile.ZipFile(bak, 'r') as zin:
        with zipfile.ZipFile(tmp, 'w', allowZip64=True) as zout:
            removed = 0
            for info in zin.infolist():
                if info.filename == target:
                    removed += 1
                    print(f"剔除: {info.filename} ({info.file_size:,} B)")
                    continue
                # 保留 entry（包含其元数据）
                data = zin.read(info.filename)
                # 保持压缩类型（zipfile 默认会重新压一遍，但保留目录 entry 需 is_dir 标记）
                new_info = zipfile.ZipInfo(filename=info.filename, date_time=info.date_time)
                new_info.compress_type = info.compress_type
                new_info.external_attr = info.external_attr
                new_info.create_system = info.create_system
                if info.is_dir():
                    # 显式保留空目录 entry
                    zout.writestr(new_info, b'')
                else:
                    zout.writestr(new_info, data)
            if removed == 0:
                print(f"WARN: 未找到 entry '{target}'，无需修改")
                tmp.unlink()
                sys.exit(0)
    shutil.move(tmp, aar)
    new_size = aar.stat().st_size
    print(f"OK: {aar} -> {new_size:,} B")


if __name__ == '__main__':
    main()
