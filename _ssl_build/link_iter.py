import struct, subprocess, sys, re

NDK = 'C:/Users/16937/Android/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin'
CLANG = NDK + '/clang.exe'
STRIP = NDK + '/llvm-strip.exe'
NM = NDK + '/llvm-nm.exe'

def und(data):
    is64 = data[4] == 2
    if is64:
        e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
        es, en, sx = struct.unpack_from('<HHH', data, 0x3A)
        def sh(i):
            o = e_shoff + i*es
            return struct.unpack_from('<IIQQQQII', data, o)
        symfmt, symsz = '<IBBHQQ', 24
    else:
        e_shoff = struct.unpack_from('<I', data, 0x20)[0]
        es, en, sx = struct.unpack_from('<HHH', data, 0x2E)
        def sh(i):
            o = e_shoff + i*es
            return struct.unpack_from('<IIIIIIII', data, o)
        symfmt, symsz = '<IIIBBH', 16
    secs = [sh(i) for i in range(en)]
    imp = set()
    for sec in secs:
        nm, typ, flags, addr, off, size, link, info = sec[:8]
        if typ != 11:
            continue
        stroff = secs[link][4]
        for p in range(off, off+size, symsz):
            if is64:
                st_name, st_info, _, _, st_shndx, _ = struct.unpack_from(symfmt, data, p)
            else:
                st_name, _, _, st_info, _, st_shndx = struct.unpack_from(symfmt, data, p)
            if st_name == 0 or st_shndx != 0 or (st_info >> 4) not in (1, 2):
                continue
            imp.add(data[stroff+st_name:data.index(b'\x00', stroff+st_name)].decode(errors='ignore'))
    return imp

def nm_defined(aar):
    out = subprocess.run([NM, '--defined-only', aar], capture_output=True, text=True).stdout
    return set(l.split()[-1] for l in out.splitlines() if re.search(r' [TWDBiRWw] ', l))

def is_libc(s):
    return (s.startswith(('__cxa_atexit', '__cxa_finalize', '__aeabi', '__gnu_'))
            or re.match(r'^__(memcpy|memmove|memset|strlen|strcpy|strncpy|strcmp|strncmp|strcat|strchr|__strlen|__strcpy)*_chk$', s) is not None
            or s.endswith('_chk'))

for tag, tgt, outdir, bld, m6 in [('arm64', 'aarch64-linux-android21', 'out_arm64', 'build_arm64',
                                    ['ERR_clear_error', 'ERR_free_strings', 'ERR_peek_error', 'X509_STORE_CTX_get_error',
                                     'X509_VERIFY_PARAM_add1_host', 'X509_VERIFY_PARAM_set_hostflags']),
                                   ('v7a', 'armv7a-linux-androideabi21', 'out_v7a', 'build_v7a',
                                    ['ERR_clear_error', 'ERR_free_strings', 'ERR_peek_error', 'X509_STORE_CTX_get_error',
                                     'X509_VERIFY_PARAM_add1_host', 'X509_VERIFY_PARAM_set_hostflags'])]:
    shim_o = '_boringssl/cxx_shim_arm64.o' if tag == 'arm64' else '_boringssl/cxx_shim_v7a.o'
    ver = '_boringssl/crypto_ver.txt' if tag == 'arm64' else '_boringssl/crypto_ver_v7a.txt'
    crypto_defs = nm_defined('_boringssl/'+bld+'/libcrypto.a')
    # 初始导出面 = libssl UND ∩ crypto 定义 + 6 个 marsstn X509/ERR
    und_ssl = set(l.split()[-1] for l in subprocess.run([NM, '--undefined-only', '_boringssl/'+bld+'/libssl.a'],
                  capture_output=True, text=True).stdout.splitlines() if re.search(r' U ', l))
    syms = sorted((und_ssl & crypto_defs) | set(m6))
    with open(ver, 'w') as f:
        f.write('LIBHYCRYPTO {\nglobal:\n')
        for s in syms:
            f.write('  '+s+';\n')
        f.write('local:\n  *;\n};\n')
    for round_i in range(4):
        r = subprocess.run([CLANG, '--target='+tgt, '-shared', '-Wl,--whole-archive',
                            '_boringssl/'+bld+'/libcrypto.a', '-Wl,--no-whole-archive',
                            '-Wl,--gc-sections', '-Wl,--version-script='+ver,
                            '-Wl,-soname,libhycrypto.so', '-O2', '-o',
                            '_boringssl/'+outdir+'/libhycrypto.so', shim_o, '-lm'],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(tag, 'hycrypto 链接失败:', r.stderr[:400]); sys.exit(1)
        r = subprocess.run([CLANG, '--target='+tgt, '-shared', '-Wl,--whole-archive',
                            '_boringssl/'+bld+'/libssl.a', '-Wl,--no-whole-archive',
                            '-Wl,--gc-sections', '-Wl,--version-script=_boringssl/ssl_ver'+('_v7a' if tag == 'v7a' else '')+'.txt',
                            '-Wl,-soname,libhyssl.so', '-O2', '-o',
                            '_boringssl/'+outdir+'/libhyssl.so',
                            '_boringssl/'+outdir+'/libhycrypto.so', shim_o, '-lc++_static', '-lc++abi'],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(tag, 'hyssl 链接失败:', r.stderr[:400]); sys.exit(1)
        u = und(open('_boringssl/'+outdir+'/libhyssl.so', 'rb').read())
        missing = sorted(s for s in u if not is_libc(s) and not s.startswith(('SSL_', 'TLS_')))
        if not missing:
            print(f'{tag} 第{round_i+1}轮收敛 ✅ hyssl UND {len(u)}')
            break
        add = [s for s in missing if s in crypto_defs]
        leftover = [s for s in missing if s not in crypto_defs]
        with open(ver, 'a') as f:
            pass
        # 重写脚本（保持格式），插入新增符号
        syms = sorted(set(syms) | set(add))
        with open(ver, 'w') as f:
            f.write('LIBHYCRYPTO {\nglobal:\n')
            for s in syms:
                f.write('  '+s+';\n')
            f.write('local:\n  *;\n};\n')
        print(f'{tag} 第{round_i+1}轮: 追加 {len(add)} 个（非crypto定义 {len(leftover)}: {leftover[:3]}）')
    subprocess.run([STRIP, '--strip-unneeded',
                    '_boringssl/'+outdir+'/libhycrypto.so',
                    '_boringssl/'+outdir+'/libhyssl.so'])
    u2 = und(open('_boringssl/'+outdir+'/libhyssl.so', 'rb').read())
    uh = und(open('_boringssl/'+outdir+'/libhycrypto.so', 'rb').read())
    bad2 = sorted(s for s in u2 if s.startswith(('_ZNSt', '_Zdl', '_Znwm', '_ZSt', '_ZTI', '_ZTV', '__libcpp')))
    badh = sorted(s for s in uh if s.startswith(('_ZNSt', '_Zdl', '_Znwm', '_ZSt', '_ZTI', '_ZTV', '__libcpp')))
    print(f'{tag} 终态: hyssl UND {len(u2)} C++残留 {len(bad2)} {bad2[:2] if bad2 else "OK"} | hycrypto UND {len(uh)} C++残留 {len(badh)} {badh[:2] if badh else "OK"}')
