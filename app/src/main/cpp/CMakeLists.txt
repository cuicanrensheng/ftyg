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
        aes.cpp)

# log 库
find_library(log-lib log)
target_link_libraries(tvlive_security ${log-lib})

# =============================================================
# libudbauthunify.so STUB - 替换虎牙 SDK 真实 so（4.39MB）
# 提供 4 个 JNI 方法的空实现，绕过 loadLibrary 失败
# =============================================================
add_library(udbauthunify SHARED
        udbauthunify_stub.cpp)
target_link_libraries(udbauthunify ${log-lib})

# =============================================================
# libhyquic.so STUB - 替换虎牙 SDK 真实 so（4.09MB）
# hyquic_stub.cpp 已完整导出 libmarsstn.so DT_NEEDED 的 16 个 posix_quic::* C++ 符号
# （带 visibility("default")），保证 mars 运行时 dlopen 成功。
# QUIC 传输不可用，自动回退 TCP 长链，本应用纯观看场景不受影响。
# =============================================================
add_library(hyquic SHARED
        hyquic_stub.cpp)
target_link_libraries(hyquic ${log-lib})

# =============================================================
# libnsdt.so STUB - 替换虎牙 SDK 真实 so（arm64 1.03MB / v7a 566KB）
# nsdt_stub.cpp 空实现 NSDT 的 8 个 native 方法，绕过 static 块无守卫的 loadLibrary。
# NSDT 为网络诊断/失败监控库，业务功能零依赖；诊断上报失效可容忍。
# =============================================================
add_library(nsdt SHARED
        nsdt_stub.cpp)
target_link_libraries(nsdt ${log-lib})

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
