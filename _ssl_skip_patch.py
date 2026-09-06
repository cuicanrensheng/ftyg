#!/usr/bin/env python3
"""
A4: 跳 SSL 验证 - libhyssl.so 函数体 patch
===========================================

【攻击点】
1. SSL_get_verify_result (arm64 @ 0x65030, v7a 同样位置) 永远返回 X509_V_OK (0)
2. SSL_CTX_load_verify_locations 永远返回 1（成功）

【实现】
- arm64: 函数体前 8 字节改为  mov x0, #0; ret  = 0x00 0x00 0x80 0xd2  0xc0 0x03 0x5f 0xd6
- v7a:   函数体前 4 字节改为  mov r0, #0; bx lr   = 0x00 0x00 0xa0 0xe3  0x1e 0xff 0x2f 0xe1

【约束】
- 文件大小不变
- 仅修改 .text section 函数体首部
- 备份原 SO 文件

【用法】
  python _ssl_skip_patch.py arm64 <path/to/libhyssl.so>
  python _ssl_skip_patch.py v7a <path/to/libhyssl.so>
"""

import sys
import struct
from pathlib import Path

# arm64: mov x0, #0; ret
ARM64_RET0 = bytes.fromhex('000080d2c0035fd6')
# v7a:   mov r0, #0; bx lr
ARM32_RET0 = bytes.fromhex('0000a0e31eff2fe1')

# patch 点（每个 ABI 一个）
PATCHES = {
    'arm64': {
        'SSL_get_verify_result': 0x65030,
        'SSL_CTX_load_verify_locations': 0x64fbc,
    },
    'v7a': {
        'SSL_get_verify_result': None,  # v7a 偏移不同
        'SSL_CTX_load_verify_locations': None,
    },
}

def read_u32(buf, off):
    return struct.unpack_from('<I', buf, off)[0]

def main():
    if len(sys.argv) != 3:
        print("用法: python _ssl_skip_patch.py <arm64|v7a> <path/to/libhyssl.so>")
        sys.exit(1)

    abi = sys.argv[1]
    so = Path(sys.argv[2])
    if not so.exists():
        print(f"ERR: {so} 不存在")
        sys.exit(1)

    # 校验 ELF
    with open(so, 'rb') as f:
        magic = f.read(4)
    if magic != b'\x7fELF':
        print(f"ERR: {so} 不是 ELF")
        sys.exit(1)

    # 备份
    bak = so.with_suffix(so.suffix + '.pre-ssl-skip')
    if not bak.exists():
        bak.write_bytes(so.read_bytes())
        print(f"备份: {bak}")

    # 找偏移
    if abi == 'arm64':
        targets = {
            0x65030: 'SSL_get_verify_result',
            0x64fbc: 'SSL_CTX_load_verify_locations',
        }
        patch_bytes = ARM64_RET0
    elif abi == 'v7a':
        targets = {
            0x4a6c1: 'SSL_get_verify_result',
            0x4a667: 'SSL_CTX_load_verify_locations',
        }
        patch_bytes = ARM32_RET0
    else:
        print(f"未知 ABI: {abi}")
        sys.exit(1)

    # 读 SO
    data = bytearray(so.read_bytes())

    for off, name in targets.items():
        if off + 8 > len(data):
            print(f"WARN: {name} @ 0x{off:x} 超出文件范围")
            continue
        orig = bytes(data[off:off+8])
        data[off:off+8] = patch_bytes
        print(f"  {name} @ 0x{off:x}: {orig.hex()} -> {patch_bytes.hex()}")

    so.write_bytes(data)
    print(f"OK: {so} patched ({len(data):,} bytes)")

if __name__ == '__main__':
    main()
