# =============================================================
# TVLive 反调试 / Anti-Frida / AES 解密 - 原生层
# =============================================================
cmake_minimum_required(VERSION 3.22.1)
project(tvlive_security C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 关闭 stack-protector / fortify 让逆向更麻烦
add_compile_options(
        -fvisibility=hidden
        -fno-rtti
        -fno-exceptions
        -fno-stack-protector
        -fno-unwind-tables
        -fno-asynchronous-unwind-tables
        -ffunction-sections
        -fdata-sections
        -Os
        # 2026-10-13：arm32(Thumb) 下 r7 默认被占作帧指针，raw syscall 需要它
        # 承载调用号（svc #0 约定 r7=__NR_*）。放掉帧指针后 r7 才可分配。
        # 代价：本 .so 自身不再带 FP 链——本库不参与 App 的崩溃回溯，无影响。
        -fomit-frame-pointer
)
# 不导出无关符号，strip 后体积更小
add_link_options(
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,noexecstack
        -Wl,-z,relro
        -Wl,-z,now
)

add_library(tvlive_security SHARED
        security.cpp
        aes.cpp
        crypto2.cpp
        selfcheck.cpp
        # 2026-10-13：敏感常量仓库（源地址/端点），编译期加密后落 .rodata，
        # 取代原先「Base64 密文常量 + 外部生成脚本」的做法。
        secstore.cpp)

# log 库 + 动态加载（selfcheck 需要 dl_iterate_phdr / dladdr 定位自身 .text）
find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(tvlive_security ${log-lib} ${dl-lib})

# =============================================================
# 2026-09-17 移除 3 个空转 stub target（udbauthunify / hyquic / nsdt）：
#   它们编译出的 .so 都被 app/build.gradle 的 packagingOptions excludes 排除、
#   不进 APK，属于空转构建。运行时方案已不依赖这些 .so：
#   - nsdt / udbauthunify：buildSrc ASM 插桩（SafeClinitClassVisitorFactory）
#     给 NSDT/HuyaAuthCore 的 <clinit> 注入 try-catch 包裹 loadLibrary，
#     native 方法改为 noop Java 方法，SO 不存在也能加载；
#   - hyquic：libmarsstn.so 已是 hook 替空 stub（jniLibs/hook_src/hook.c），
#     无 DT_NEEDED libhyquic；Mars.java 的 loadLibrary 亦有 try-catch。
# stub 源码（*_stub.cpp）保留在本目录存档，不再参与编译。
# =============================================================

# =============================================================
# libhyssl.so / libhycrypto.so —— 恢复真实 BoringSSL（2026-08-29 定案）
# 背景：stub 化（空实现/转发系统 TLS）均失败：
#   - v1 空实现：TLS 建链失败，房间解析超时 30s
#   - v2 转发 /system/lib/libssl.so：Android 8.0+ linker 命名空间隔离
#     (app namespace "clns-*" 不可访问系统 libssl/libcrypto)，dlsym 全 NULL
# 定案：恢复 hyssl-0.1.11.aar 内真实 BoringSSL（v7a: 534KB + 1.18MB），
# 与 marsstn（BoringSSL 编译）符号级完全兼容，TLS 真实可用。
# 真实 so 位于 app/src/main/jniLibs/armeabi-v7a/（已从 aar 提取）。
# =============================================================
