# -*- coding: utf-8 -*-
"""检查 hycrypto 是否导出 SSL_* 符号（决定 hyssl stub 是否安全）"""
import subprocess

READELF = r"C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"

def defined_global(so):
    out = subprocess.run([READELF, "--dyn-syms", so], capture_output=True, text=True, errors="replace").stdout
    syms = set()
    for line in out.splitlines():
        if "GLOBAL" in line and "DEFAULT" in line and "UND" not in line:
            parts = line.split()
            if parts:
                syms.add(parts[-1])
    return syms

SSL_SYMS = [
    "SSL_CTX_free", "SSL_CTX_load_verify_locations", "SSL_CTX_new",
    "SSL_CTX_set_cipher_list", "SSL_CTX_set_max_proto_version", "SSL_CTX_set_mode",
    "SSL_CTX_set_options", "SSL_connect", "SSL_free", "SSL_get0_param",
    "SSL_get_error", "SSL_get_fd", "SSL_library_init", "SSL_load_error_strings",
    "SSL_new", "SSL_pending", "SSL_read", "SSL_set1_param", "SSL_set_connect_state",
    "SSL_set_fd", "SSL_set_tlsext_host_name", "SSL_set_verify", "SSL_version",
    "SSL_want", "SSL_write", "TLS_client_method",
]

for tag, so in [("arm64", "_so_extract/libhycrypto.so"),
                ("v7a", "_so_extract/v7a_libhycrypto.so")]:
    try:
        c = defined_global(so)
        hit = [s for s in SSL_SYMS if s in c]
        print(f"{tag} hycrypto exported SSL_*: {len(hit)}/{len(SSL_SYMS)}")
        print("   ", ", ".join(hit) if hit else "(none)")
    except Exception as e:
        print(f"{tag}: {e}")
