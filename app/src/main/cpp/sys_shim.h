// =============================================================
// sys_shim.h —— 直陷内核的原始系统调用封装
//
// 目的：绕开 libc 的 PLT/GOT。检测类代码若走 ptrace()/access()/open()，
// 这些符号都是可被 PLT hook 的导入项，Frida 挂一个 interceptor 就全废。
// 用 svc #0 直发系统调用，不打 libc 符号表，hook 成本陡增。
//
// 约定：所有 tvl_sys_* 返回原始内核返回值（负值即 -errno），不做重试、不设 errno。
// =============================================================
#ifndef TVLIVE_SYS_SHIM_H
#define TVLIVE_SYS_SHIM_H

#include <cstddef>
#include <cstdint>
#include <sys/types.h>

#if defined(__aarch64__)
#define TVL_HAS_RAW_SYSCALL 1
#define TVL_NR_read       63
#define TVL_NR_close      57
#define TVL_NR_getpid     172
#define TVL_NR_ptrace     117
#define TVL_NR_openat     56
#define TVL_NR_faccessat  48
#define TVL_NR_readlinkat 78
#elif defined(__arm__)
#define TVL_HAS_RAW_SYSCALL 1
#define TVL_NR_read       3
#define TVL_NR_close      6
#define TVL_NR_getpid     20
#define TVL_NR_ptrace     26
#define TVL_NR_openat     322
#define TVL_NR_faccessat  334
#define TVL_NR_readlinkat 327
#elif defined(__x86_64__)
#define TVL_HAS_RAW_SYSCALL 1
#define TVL_NR_read       0
#define TVL_NR_close      3
#define TVL_NR_getpid     39
#define TVL_NR_ptrace     101
#define TVL_NR_openat     257
#define TVL_NR_faccessat  269
#define TVL_NR_readlinkat 267
#else
#define TVL_HAS_RAW_SYSCALL 0
#endif

#if TVL_HAS_RAW_SYSCALL

#if defined(__aarch64__)
static inline long tvl_syscall6(long n, long a1, long a2, long a3, long a4, long a5, long a6) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a1;
    register long x1 __asm__("x1") = a2;
    register long x2 __asm__("x2") = a3;
    register long x3 __asm__("x3") = a4;
    register long x4 __asm__("x4") = a5;
    register long x5 __asm__("x5") = a6;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                     : "memory", "cc");
    return x0;
}
#elif defined(__arm__)
static inline long tvl_syscall6(long n, long a1, long a2, long a3, long a4, long a5, long a6) {
    register long r7 __asm__("r7") = n;
    register long r0 __asm__("r0") = a1;
    register long r1 __asm__("r1") = a2;
    register long r2 __asm__("r2") = a3;
    register long r3 __asm__("r3") = a4;
    register long r4 __asm__("r4") = a5;
    register long r5 __asm__("r5") = a6;
    __asm__ volatile("svc #0"
                     : "+r"(r0)
                     : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5)
                     : "memory", "cc");
    return r0;
}
#elif defined(__x86_64__)
static inline long tvl_syscall6(long n, long a1, long a2, long a3, long a4, long a5, long a6) {
    long ret;
    register long r10 __asm__("r10") = a4;
    register long r8 __asm__("r8") = a5;
    register long r9 __asm__("r9") = a6;
    __asm__ volatile("syscall"
                     : "=a"(ret)
                     : "a"(n), "D"(a1), "S"(a2), "d"(a3), "r"(r10), "r"(r8), "r"(r9)
                     : "rcx", "r11", "memory");
    return ret;
}
#endif

static inline long tvl_sys_read(int fd, void* buf, size_t count) {
    return tvl_syscall6(TVL_NR_read, (long)fd, (long)buf, (long)count, 0, 0, 0);
}
static inline long tvl_sys_close(int fd) {
    return tvl_syscall6(TVL_NR_close, (long)fd, 0, 0, 0, 0, 0);
}
static inline long tvl_sys_getpid() {
    return tvl_syscall6(TVL_NR_getpid, 0, 0, 0, 0, 0, 0);
}
static inline long tvl_sys_ptrace(long request, long pid, void* addr, void* data) {
    return tvl_syscall6(TVL_NR_ptrace, request, pid, (long)addr, (long)data, 0, 0);
}
static inline long tvl_sys_openat(int dirfd, const char* path, int flags, int mode) {
    return tvl_syscall6(TVL_NR_openat, (long)dirfd, (long)path, (long)flags, (long)mode, 0, 0);
}
static inline long tvl_sys_faccessat(int dirfd, const char* path, int mode) {
    return tvl_syscall6(TVL_NR_faccessat, (long)dirfd, (long)path, (long)mode, 0, 0, 0);
}
static inline long tvl_sys_readlinkat(int dirfd, const char* path, char* buf, size_t bufsz) {
    return tvl_syscall6(TVL_NR_readlinkat, (long)dirfd, (long)path, (long)buf, (long)bufsz, 0, 0);
}

#else  // 不支持的架构：退回 libc，保证可编译

#include <unistd.h>
#include <fcntl.h>

static inline long tvl_syscall6(long n, long a1, long a2, long a3, long a4, long a5, long a6) {
    (void)n; (void)a1; (void)a2; (void)a3; (void)a4; (void)a5; (void)a6;
    return -1;
}
static inline long tvl_sys_read(int fd, void* buf, size_t count) { return (long)read(fd, buf, count); }
static inline long tvl_sys_close(int fd) { return (long)close(fd); }
static inline long tvl_sys_getpid() { return (long)getpid(); }
static inline long tvl_sys_ptrace(long request, long pid, void* addr, void* data) { (void)request; (void)pid; (void)addr; (void)data; return -1; }
static inline long tvl_sys_openat(int dirfd, const char* path, int flags, int mode) { return (long)openat(dirfd, path, flags, mode); }
static inline long tvl_sys_faccessat(int dirfd, const char* path, int mode) { return (long)faccessat(dirfd, path, mode, 0); }
static inline long tvl_sys_readlinkat(int dirfd, const char* path, char* buf, size_t bufsz) { (void)dirfd; (void)path; (void)buf; (void)bufsz; return -1; }

#endif

// AT_FDCWD 在两条路径下都需要
#ifndef AT_FDCWD
#define AT_FDCWD (-100)
#endif

// 变参系统调用：Android 的 openat 走内核原语，这里给一个「打开只读文件」的便捷封装。
static inline int tvl_open_ro(const char* path) {
    // O_RDONLY=0 O_CLOEXEC=0x80000(__aarch64__) —— 用字面量避免拉进 <fcntl.h> 的平台差异
#if defined(__aarch64__) || defined(__x86_64__)
    long fd = tvl_sys_openat(AT_FDCWD, path, 0 /*O_RDONLY*/, 0);
#else
    long fd = tvl_sys_openat(AT_FDCWD, path, 0 /*O_RDONLY*/, 0);
#endif
    return (fd < 0) ? -1 : (int)fd;
}

// 判定路径是否可访问（存在性）。返回 1=存在。
static inline int tvl_exists(const char* path) {
    long r = tvl_sys_faccessat(AT_FDCWD, path, 0 /*F_OK*/);
    return r == 0 ? 1 : 0;
}

// 用原始系统调用把整个文件读进 buf，返回读取字节数（-1 = 失败）。
static inline long tvl_read_file(const char* path, char* buf, size_t cap) {
    if (cap == 0) return -1;
    int fd = tvl_open_ro(path);
    if (fd < 0) return -1;
    size_t total = 0;
    while (total < cap - 1) {
        long n = tvl_sys_read(fd, buf + total, cap - 1 - total);
        if (n <= 0) break;
        total += (size_t)n;
    }
    tvl_sys_close(fd);
    buf[total] = 0;
    return (long)total;
}

#endif  // TVLIVE_SYS_SHIM_H
