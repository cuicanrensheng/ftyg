#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <pthread.h>
#include <string>
#include <vector>
#include <algorithm>
#include <errno.h>
#include <signal.h>
#include <time.h>
#include "sys_shim.h"
#include "obfstr.h"

#define TAG "TVLS"

// =============================================================
// 检测特征串：全部编译期异或加密，运行时只在栈上解出。
// 每个串用不同 key（否则密文相同、仍可被聚类识别）。
// 这样 .rodata 里不再出现 "frida"/"magisk"/"/system/bin/su" 等明文。
// =============================================================
OBF_DEF(O_MAPS,         "/proc/self/maps",                0x11);
OBF_DEF(O_STATUS,       "/proc/self/status",              0x25);
OBF_DEF(O_CPUINFO,      "/proc/cpuinfo",                  0x33);
OBF_DEF(O_MOUNTS,       "/proc/mounts",                   0x47);
OBF_DEF(O_BUILDPROP,    "/system/build.prop",             0x5b);
OBF_DEF(O_TRACERPID,    "TracerPid:",                     0x69);

OBF_DEF(O_FRIDA,        "frida",                          0x71);
OBF_DEF(O_GADGET,       "gadget",                         0x83);
OBF_DEF(O_GMAIN,        "gmain",                          0x97);
OBF_DEF(O_AGENT,        "agent",                          0xa5);
OBF_DEF(O_XPOSED,       "xposed",                         0xb9);
OBF_DEF(O_LIBXPOSED,    "libxposed",                      0xc1);
OBF_DEF(O_SUBSTRATE,    "substrate",                      0xd3);
OBF_DEF(O_EDXP,         "edxp",                           0xe7);
OBF_DEF(O_LSPOSED,      "lsposed",                        0xf1);
OBF_DEF(O_MAGISK,       "magisk",                         0x0f);

OBF_DEF(O_SU_XBIN,      "/system/xbin/su",                0x1d);
OBF_DEF(O_SU_BIN,       "/system/bin/su",                 0x2b);
OBF_DEF(O_SU_SBIN,      "/sbin/su",                       0x39);
OBF_DEF(O_SU_ROOT,      "/system/su",                     0x4d);
OBF_DEF(O_SUPERUSER,    "/system/app/Superuser.apk",      0x5f);
OBF_DEF(O_SUPERSU,      "/system/app/SuperSU.apk",        0x6d);
OBF_DEF(O_ADB_MAGISK,   "/data/adb/magisk",               0x7b);
OBF_DEF(O_SBIN_MAGISK,  "/sbin/magisk",                   0x8e);
OBF_DEF(O_ADB_KSU,      "/data/adb/ksu",                  0x9c);
OBF_DEF(O_ADB_MODULES,  "/data/adb/modules",              0xaf);
OBF_DEF(O_ADB_LSPD,     "/data/adb/lspd",                 0xbd);
OBF_DEF(O_ADB_RIRU,     "/data/adb/riru",                 0xcb);
OBF_DEF(O_MAGISK_PKG,   "/data/data/com.topjohnwu.magisk", 0xd9);
OBF_DEF(O_WHICH_SU,     "which su 2>/dev/null",           0xeb);
OBF_DEF(O_DEBUGGABLE,   "ro.debuggable",                  0xf7);

OBF_DEF(O_QEMU_PIPE,    "/dev/qemu_pipe",                 0x13);
OBF_DEF(O_GOLDFISH,     "/dev/goldfish_pipe",             0x21);
OBF_DEF(O_QEMU_MALLOC,  "/system/lib/libc_malloc_debug_qemu.so", 0x37);
OBF_DEF(O_QEMU_TRACE,   "/sys/qemu_trace",                0x49);
OBF_DEF(O_QEMU_PROPS,   "/system/bin/qemu-props",         0x57);
OBF_DEF(O_LDUTILS,      "/system/lib/libldutils.so",      0x65);
OBF_DEF(O_NEMU,         "/system/lib/libnemu.so",         0x73);
OBF_DEF(O_MICROVIRT,    "/data/data/com.microvirt.tools", 0x81);
OBF_DEF(O_BIGNOX,       "/data/data/com.bignox.app",      0x9f);
OBF_DEF(O_VPHONE,       "/data/data/com.vphone.helper",   0xad);
OBF_DEF(O_BLUESTACKS,   "/data/data/com.bluestacks",      0xbb);
OBF_DEF(O_LDMNQ,        "/data/data/com.ldmnq.makemachine", 0xc9);
OBF_DEF(O_MUMU,         "/data/data/com.mumu.electron",   0xd7);
OBF_DEF(O_PRODUCT_MODEL, "ro.product.model",              0xe5);
OBF_DEF(O_PRODUCT_DEVICE, "ro.product.device",            0xf3);

OBF_DEF(O_GOLDFISH_CPU, "Goldfish",                       0x15);
OBF_DEF(O_RANCHU,       "Ranchu",                         0x27);
OBF_DEF(O_INTEL,        "Intel",                          0x35);
OBF_DEF(O_AMD64,        "amd64",                          0x43);
OBF_DEF(O_MU_MUI,       "MuMu",                           0x51);
OBF_DEF(O_LEIDIAN,      "雷电",                            0x6f);
OBF_DEF(O_NIGHTGOD,     "夜神",                            0x7d);
OBF_DEF(O_NOX,          "Nox",                            0x8b);
OBF_DEF(O_BLUESTACKS_S, "BlueStacks",                     0x99);
OBF_DEF(O_GENERIC,      "generic",                        0xa7);
OBF_DEF(O_VBOX86,       "vbox86",                         0xb5);
OBF_DEF(O_EMU64A,       "emu64a",                         0xc3);
OBF_DEF(O_EMU86A,       "emu86a",                         0xd1);
OBF_DEF(O_ANDROID_SDK,  "Android SDK",                    0xdf);
OBF_DEF(O_EMULATOR,     "Emulator",                       0xed);

OBF_DEF(O_APP_PROCESS,  "app_process",                    0x17);
OBF_DEF(O_LIBART,       "libart",                         0x29);
OBF_DEF(O_LIBDVM,       "libdvm",                         0x3b);
OBF_DEF(O_ZYGOTE,       "zygote",                         0x4f);
OBF_DEF(O_TAICHI,       "taichi",                         0x5d);

// nativeEncrypt 的 IV 来源（见文件末尾 aes256_cbc_encrypt）
OBF_DEF(O_URANDOM,      "/dev/urandom",                   0xa9);

// ---- 加密串的运行时用法（解到栈上，用完即散）----

template <int N, int KEY>
static inline long tvl_read_file_at(const obf::Str<N, KEY>& path, char* buf, size_t cap) {
    char p[192];
    obf::decode_to(path, p, sizeof(p));
    return tvl_read_file(p, buf, cap);
}

template <int N, int KEY>
static inline bool obf_exists(const obf::Str<N, KEY>& path) {
    char p[192];
    obf::decode_to(path, p, sizeof(p));
    return tvl_exists(p) == 1;
}

// 在整块文本里找加密串（大小写不敏感），返回位置或 nullptr
template <int N, int KEY>
static inline const char* obf_find(const char* hay, long haylen,
                                   const obf::Str<N, KEY>& needle) {
    if (hay == nullptr || haylen <= 0) return nullptr;
    if (!obf::contains_ci(hay, (size_t)haylen, needle)) return nullptr;
    return hay;   // 仅需"是否命中"，不需要精确位置
}

#define LOGI(...) ((void)0)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// selfcheck.cpp 提供：0=代码段未变 / 1=被改写 / 负数=不可用
extern "C" int tvl_selfcheck_verify(void);

static volatile int g_ptrace_locked = 0;
static volatile int g_ptrace_failed = 0;
static volatile int g_ptrace_errno  = 0;
static volatile pid_t g_my_pid = 0;
static volatile int g_code_tampered = 0;

static void signal_handler(int signum) {
    if (signum == SIGTRAP || signum == SIGILL || signum == SIGSEGV) {
        g_ptrace_locked = 1;
        LOGE("⚠️ 检测到调试器附加 (signal=%d)", signum);
    }
}

static void anti_debug_ptrace() {
    g_my_pid = (pid_t)tvl_sys_getpid();

    signal(SIGTRAP, signal_handler);
    signal(SIGILL, signal_handler);
    signal(SIGSEGV, signal_handler);

    // 直发 PTRACE_TRACEME，绕开 libc 的 ptrace 导入项（PLT hook 常见位点）
    long ret = tvl_sys_ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
    if (ret < 0) {
        g_ptrace_failed = 1;
        g_ptrace_errno  = (int)(-ret);   // 原始系统调用以 -errno 返回，不经 errno
        LOGW("ptrace TRACEME failed errno=%d", (int)(-ret));
        return;
    }

    tvl_sys_ptrace(PTRACE_DETACH, 0, nullptr, nullptr);
}

static int check_ptrace_attach() {
    long ret = tvl_sys_ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
    if (ret == -EBUSY) {
        LOGE("ptrace EBUSY - debugger attached!");
        return 1;
    }
    if (ret == -ESRCH) {
        return 0;
    }
    tvl_sys_ptrace(PTRACE_DETACH, 0, nullptr, nullptr);
    return 0;
}

static int check_tracer_pid() {
    char buf[4096];
    char key[32];
    obf::decode_to(O_TRACERPID, key, sizeof(key));

    long n = tvl_read_file_at(O_STATUS, buf, sizeof(buf));
    if (n <= 0) return 0;

    const char* p = strstr(buf, key);
    if (!p) return 0;
    p += strlen(key);
    while (*p == ' ' || *p == '\t') p++;
    int pid = atoi(p);

    if (pid > 0) {
        LOGE("tracer pid=%d, debugger running", pid);
        return 1;
    }
    return 0;
}

static const int FRIDA_PORTS[] = {
    27042, 27043,
    4455, 4456, 4457,
    8000, 8080, 8888,
    0
};

static int scan_frida_ports() {
    for (int i = 0; FRIDA_PORTS[i] != 0; ++i) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) continue;
        struct sockaddr_in sa{};
        sa.sin_family = AF_INET;
        sa.sin_port = htons(FRIDA_PORTS[i]);
        sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

        struct timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 100000;
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        int r = connect(sock, (struct sockaddr*)&sa, sizeof(sa));
        if (r == 0) {
            LOGE("suspicious loopback port %d open", FRIDA_PORTS[i]);
            close(sock);
            return 1;
        }
        close(sock);
    }
    return 0;
}

static int g_frida_in_maps = 0;

// 命中时把特征名解到栈上再打印——避免明文重新落回 .rodata
template <int N, int KEY>
static inline void log_obf_hit(const obf::Str<N, KEY>& s) {
    char p[96];
    obf::decode_to(s, p, sizeof(p));
    LOGE("hook framework detected in maps: %s", p);
}

static void scan_maps_for_frida() {
    char buf[16384];
    long total = tvl_read_file_at(O_MAPS, buf, sizeof(buf));
    if (total <= 0) return;

    if (obf::contains_ci(buf, (size_t)total, O_FRIDA))     { g_frida_in_maps = 1; log_obf_hit(O_FRIDA); return; }
    if (obf::contains_ci(buf, (size_t)total, O_GADGET))    { g_frida_in_maps = 1; log_obf_hit(O_GADGET); return; }
    if (obf::contains_ci(buf, (size_t)total, O_GMAIN))     { g_frida_in_maps = 1; log_obf_hit(O_GMAIN); return; }
    if (obf::contains_ci(buf, (size_t)total, O_AGENT))     { g_frida_in_maps = 1; log_obf_hit(O_AGENT); return; }
    if (obf::contains_ci(buf, (size_t)total, O_XPOSED))    { g_frida_in_maps = 1; log_obf_hit(O_XPOSED); return; }
    if (obf::contains_ci(buf, (size_t)total, O_SUBSTRATE)) { g_frida_in_maps = 1; log_obf_hit(O_SUBSTRATE); return; }
    if (obf::contains_ci(buf, (size_t)total, O_LIBXPOSED)) { g_frida_in_maps = 1; log_obf_hit(O_LIBXPOSED); return; }
    if (obf::contains_ci(buf, (size_t)total, O_EDXP))      { g_frida_in_maps = 1; log_obf_hit(O_EDXP); return; }
    if (obf::contains_ci(buf, (size_t)total, O_LSPOSED))   { g_frida_in_maps = 1; log_obf_hit(O_LSPOSED); return; }
    if (obf::contains_ci(buf, (size_t)total, O_MAGISK))    { g_frida_in_maps = 1; log_obf_hit(O_MAGISK); return; }
}

// 用原始系统调用读整个文件后按行扫描——不再走 fopen/fgets（stdio 全在可 hook 的 libc 里）
static int read_file_line(const char* path, const char* key, char* out, int outlen) {
    if (path == nullptr || key == nullptr || out == nullptr || outlen <= 1) return 0;
    char buf[8192];
    long n = tvl_read_file(path, buf, sizeof(buf));
    if (n <= 0) return 0;

    const size_t klen = strlen(key);
    if (klen == 0) return 0;

    int rc = 0;
    size_t i = 0;
    while (i < (size_t)n) {
        size_t j = i;
        while (j < (size_t)n && buf[j] != '\n') ++j;
        const size_t linelen = j - i;
        bool hit = false;
        if (linelen >= klen) {
            for (size_t k = 0; k + klen <= linelen; ++k) {
                if (memcmp(buf + i + k, key, klen) == 0) {
                    hit = true;
                    break;
                }
            }
        }
        if (hit) {
            size_t copylen = (linelen < (size_t)(outlen - 1)) ? linelen : (size_t)(outlen - 1);
            memcpy(out, buf + i, copylen);
            out[copylen] = 0;
            rc = 1;
            break;
        }
        i = j + 1;
    }
    return rc;
}

template <int N1, int K1, int N2, int K2>
static int read_file_line_obf(const obf::Str<N1, K1>& path, const obf::Str<N2, K2>& key,
                              char* out, int outlen) {
    char p[192];
    char k[192];
    obf::decode_to(path, p, sizeof(p));
    obf::decode_to(key, k, sizeof(k));
    return read_file_line(p, k, out, outlen);
}

static int check_root() {
    // 全部走 faccessat 原始系统调用；路径字符串编译期加密。
    // 原实现里 popen("which su") 已移除——它在电视端会 fork 一个 /bin/sh，
    // 既重又可被一条 libc hook 抹掉；改为直接补全 su 常见落点，信噪比更高。
    if (obf_exists(O_SU_XBIN) || obf_exists(O_SU_BIN) || obf_exists(O_SU_SBIN) ||
        obf_exists(O_SU_ROOT) || obf_exists(O_SUPERUSER) || obf_exists(O_SUPERSU) ||
        obf_exists(O_ADB_MAGISK) || obf_exists(O_SBIN_MAGISK) || obf_exists(O_ADB_KSU) ||
        obf_exists(O_ADB_MODULES) || obf_exists(O_ADB_LSPD) || obf_exists(O_ADB_RIRU) ||
        obf_exists(O_MAGISK_PKG)) {
        LOGW("root indicator file present");
        return 1;
    }

    char line[1024];
    if (read_file_line_obf(O_BUILDPROP, O_DEBUGGABLE, line, sizeof(line))) {
        if (strstr(line, "=1") || strstr(line, "=true")) {
            LOGE("system property indicates eng/userdebug build");
            return 1;
        }
    }

    if (read_file_line_obf(O_MOUNTS, O_MAGISK, line, sizeof(line))) {
        log_obf_hit(O_MAGISK);
        return 1;
    }
    return 0;
}

static int check_emulator() {
    char buf[8192];
    long n = tvl_read_file_at(O_CPUINFO, buf, sizeof(buf));
    if (n > 0) {
        if (obf::contains_ci(buf, (size_t)n, O_GOLDFISH_CPU) ||
            obf::contains_ci(buf, (size_t)n, O_RANCHU) ||
            obf::contains_ci(buf, (size_t)n, O_INTEL) ||
            obf::contains_ci(buf, (size_t)n, O_AMD64)) {
            return 1;
        }
    }

    if (obf_exists(O_QEMU_PIPE) || obf_exists(O_GOLDFISH) || obf_exists(O_QEMU_MALLOC) ||
        obf_exists(O_QEMU_TRACE) || obf_exists(O_QEMU_PROPS) || obf_exists(O_LDUTILS) ||
        obf_exists(O_NEMU) || obf_exists(O_MICROVIRT) || obf_exists(O_BIGNOX) ||
        obf_exists(O_VPHONE) || obf_exists(O_BLUESTACKS) || obf_exists(O_LDMNQ) ||
        obf_exists(O_MUMU)) {
        return 1;
    }

    char line[1024];
    if (read_file_line_obf(O_BUILDPROP, O_PRODUCT_MODEL, line, sizeof(line))) {
        if (obf::contains_ci_cstr(line, O_EMULATOR) ||
            obf::contains_ci_cstr(line, O_ANDROID_SDK) ||
            obf::contains_ci_cstr(line, O_MU_MUI) ||
            obf::contains_ci_cstr(line, O_LEIDIAN) ||
            obf::contains_ci_cstr(line, O_NIGHTGOD) ||
            obf::contains_ci_cstr(line, O_BLUESTACKS_S) ||
            obf::contains_ci_cstr(line, O_NOX)) {
            return 1;
        }
    }
    if (read_file_line_obf(O_BUILDPROP, O_PRODUCT_DEVICE, line, sizeof(line))) {
        if (obf::contains_ci_cstr(line, O_GENERIC) ||
            obf::contains_ci_cstr(line, O_VBOX86) ||
            obf::contains_ci_cstr(line, O_EMU64A) ||
            obf::contains_ci_cstr(line, O_EMU86A)) {
            return 1;
        }
    }
    return 0;
}

static const uint8_t KEY_PART_A[16] = {
    0x9c, 0x3f, 0xa1, 0x77, 0x55, 0x88, 0x10, 0xcc,
    0x2d, 0x4b, 0xe6, 0x91, 0x07, 0xb3, 0xd5, 0x42
};
static const uint8_t KEY_PART_B[16] = {
    0x7a, 0xb1, 0x05, 0xe9, 0x33, 0x6f, 0xc2, 0x4d,
    0x18, 0xfa, 0x82, 0x59, 0xa0, 0x21, 0x6c, 0xd7
};

static uint8_t g_runtime_token[16] = {0};

extern "C" JNIEXPORT void JNICALL
Java_com_tv_live_security_SecurityCore_nativeSetToken(JNIEnv* env, jclass, jbyteArray token) {
    if (!token) return;
    jsize len = env->GetArrayLength(token);
    if (len > 16) len = 16;
    env->GetByteArrayRegion(token, 0, len, (jbyte*)g_runtime_token);

    for (int i = len; i < 16; ++i) g_runtime_token[i] = 0;
}

static void build_aes_key(uint8_t out[32]) {

    for (int i = 0; i < 16; ++i) out[i]      = KEY_PART_A[i] ^ g_runtime_token[i];
    for (int i = 0; i < 16; ++i) out[16 + i] = KEY_PART_B[i] ^ g_runtime_token[i];
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeDecrypt(JNIEnv* env, jclass, jbyteArray cipher) {
    if (!cipher) return nullptr;
    jsize len = env->GetArrayLength(cipher);

    if (len < 32 || (len - 16) % 16 != 0) {
        LOGE("invalid cipher length=%d", len);
        return nullptr;
    }
    jbyte* data = env->GetByteArrayElements(cipher, nullptr);
    if (!data) return nullptr;

    extern int aes256_cbc_decrypt(const uint8_t* in, int in_len,
                                   const uint8_t key[32], uint8_t* out, int* out_len);
    uint8_t key[32];
    build_aes_key(key);

    int out_len = 0;
    int cap = len;
    uint8_t* out = (uint8_t*)malloc(cap);
    int rc = aes256_cbc_decrypt((const uint8_t*)data, len, key, out, &out_len);
    env->ReleaseByteArrayElements(cipher, data, JNI_ABORT);
    if (rc != 0) {
        LOGE("aes decrypt failed rc=%d", rc);
        free(out);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(out_len);
    env->SetByteArrayRegion(result, 0, out_len, (jbyte*)out);
    free(out);
    return result;
}

// =============================================================
// 【2026-10-13 加密能力内建】正向 AES-256-CBC（与 nativeDecrypt 完全对称）
//
// 动机：源地址等常量原以「Base64 密文」写在 Java 里，密文的生产依赖外部脚本
// （gen_cipher.py / _gen_cipher.mjs）。脚本一旦丢失，轮换常量就断了。
// 这里把「生成密文」搬进 SO —— 任何时候都能用
// SecurityCore.encryptToString(明文) 造出新密文，脚本彻底变成可选。
//
// 格式：iv(16B，随机) ‖ AES-256-CBC(PKCS7)，与 nativeDecrypt 逐字节对称。
// 密钥：与解密同一 build_aes_key()（KEY_PART_A‖KEY_PART_B XOR runtime token）。
// =============================================================
extern "C" void tvl_aes256_expand(const uint8_t key[32], uint8_t rk[240]);
extern "C" void tvl_aes_encrypt_block(const uint8_t in[16], uint8_t out[16],
                                      const uint8_t* rk);

/** 随机 IV：优先内核 urandom（内联 syscall，不经 libc PLT） */
static void fill_random_iv(uint8_t iv[16]) {
    char p[64];
    obf::decode_to(O_URANDOM, p, sizeof(p));

    int fd = tvl_open_ro(p);
    if (fd >= 0) {
        long got = 0;
        while (got < 16) {
            long n = tvl_sys_read(fd, iv + got, (size_t)(16 - got));
            if (n <= 0) break;
            got += n;
        }
        tvl_sys_close(fd);
        if (got == 16) return;
    }

    // 兜底：单调时钟 + pid 混合（仅当 urandom 不可用时走到）
    struct timespec ts;
    unsigned long long s = 0;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        s = (unsigned long long)ts.tv_nsec ^ ((unsigned long long)ts.tv_sec << 20);
    }
    s ^= ((unsigned long long)tvl_sys_getpid() << 40);
    for (int i = 0; i < 16; ++i) {
        s = s * 6364136223846793005ULL + 1442695040888963407ULL;
        iv[i] = (uint8_t)(s >> 33);
    }
}

static int aes256_cbc_encrypt(const uint8_t* in, int in_len,
                              const uint8_t key[32], uint8_t* out, int* out_len) {
    if (!in || !out || !out_len || in_len < 0) return -1;

    uint8_t rk[240];
    tvl_aes256_expand(key, rk);

    int pad = 16 - (in_len % 16);        // PKCS7：恒 1..16，长度恰为 16 的倍数时补满一块
    int ct_len = in_len + pad;

    uint8_t iv[16];
    fill_random_iv(iv);
    memcpy(out, iv, 16);

    uint8_t prev[16];
    memcpy(prev, iv, 16);

    uint8_t* p = out + 16;
    for (int off = 0; off < ct_len; off += 16) {
        uint8_t blk[16];
        for (int j = 0; j < 16; ++j) {
            int idx = off + j;
            uint8_t v = (idx < in_len) ? in[idx] : (uint8_t)pad;
            blk[j] = (uint8_t)(v ^ prev[j]);
        }
        tvl_aes_encrypt_block(blk, p + off, rk);
        memcpy(prev, p + off, 16);
        memset(blk, 0, sizeof(blk));
    }

    memset(rk, 0, sizeof(rk));
    memset(iv, 0, sizeof(iv));
    memset(prev, 0, sizeof(prev));
    *out_len = 16 + ct_len;
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeEncrypt(JNIEnv* env, jclass, jbyteArray plain) {
    if (!plain) return nullptr;
    jsize len = env->GetArrayLength(plain);
    if (len < 0 || len > (1 << 20)) return nullptr;   // 常量不会这么大，拒绝异常入参

    jbyte* data = env->GetByteArrayElements(plain, nullptr);
    if (!data) return nullptr;

    uint8_t key[32];
    build_aes_key(key);

    int cap = 16 + len + 16;
    uint8_t* out = (uint8_t*)malloc((size_t)cap);
    int out_len = 0;
    int rc = out ? aes256_cbc_encrypt((const uint8_t*)data, len, key, out, &out_len) : -2;

    env->ReleaseByteArrayElements(plain, data, JNI_ABORT);
    memset(key, 0, sizeof(key));

    jbyteArray result = nullptr;
    if (rc == 0) {
        result = env->NewByteArray(out_len);
        if (result) env->SetByteArrayRegion(result, 0, out_len, (jbyte*)out);
    }
    if (out) { memset(out, 0, (size_t)cap); free(out); }
    return result;
}

static int g_hook_detected = 0;

// 【2026-10-13】原 check_memory_integrity() 已删除：它三分支恒 return 0，
// 且调用点也没有——纯死代码。代码段完整性校验改由 selfcheck.cpp 承担
// （JNI_OnLoad 抓 .text 基准，之后随时重算比对），比原来那段空壳实在。

static int check_art_hooks() {
    char buf[32768];
    long total = tvl_read_file_at(O_MAPS, buf, sizeof(buf));
    if (total <= 0) return 0;

    // 原实现是「先全串匹配、再对 app_process/libart/zygote 做白名单豁免」，
    // 等价于这三个正常映射恒忽略；这里直接只查真正的 hook 框架特征。
    (void)sizeof(O_APP_PROCESS);
    (void)sizeof(O_LIBART);
    (void)sizeof(O_LIBDVM);
    (void)sizeof(O_ZYGOTE);

    if (obf::contains_ci(buf, (size_t)total, O_MAGISK))    { log_obf_hit(O_MAGISK);    g_hook_detected = 1; return 1; }
    if (obf::contains_ci(buf, (size_t)total, O_LSPOSED))   { log_obf_hit(O_LSPOSED);   g_hook_detected = 1; return 1; }
    if (obf::contains_ci(buf, (size_t)total, O_EDXP))      { log_obf_hit(O_EDXP);      g_hook_detected = 1; return 1; }
    if (obf::contains_ci(buf, (size_t)total, O_TAICHI))    { log_obf_hit(O_TAICHI);    g_hook_detected = 1; return 1; }
    if (obf::contains_ci(buf, (size_t)total, O_XPOSED))    { log_obf_hit(O_XPOSED);    g_hook_detected = 1; return 1; }
    if (obf::contains_ci(buf, (size_t)total, O_SUBSTRATE)) { log_obf_hit(O_SUBSTRATE); g_hook_detected = 1; return 1; }
    if (obf::contains_ci(buf, (size_t)total, O_FRIDA))     { log_obf_hit(O_FRIDA);     g_hook_detected = 1; return 1; }
    if (obf::contains_ci(buf, (size_t)total, O_GADGET))    { log_obf_hit(O_GADGET);    g_hook_detected = 1; return 1; }

    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tv_live_security_SecurityCore_nativeCheck(JNIEnv*, jclass) {
    int result = 0;
    if (check_tracer_pid()) result |= 0x01;
    if (scan_frida_ports())  result |= 0x02;
    if (g_frida_in_maps)     result |= 0x04;
    if (check_root())        result |= 0x08;
    if (check_emulator())    result |= 0x10;
    if (check_ptrace_attach()) result |= 0x20;
    if (check_art_hooks())   result |= 0x40;
    if (g_ptrace_locked)     result |= 0x80;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tv_live_security_SecurityCore_nativeAntiDebug(JNIEnv*, jclass) {
    anti_debug_ptrace();
}

static pthread_t g_monitor_thread;
static volatile int g_monitor_run = 0;
static void* monitor_loop(void*) {
    int tick = 0;
    while (g_monitor_run) {

        scan_maps_for_frida();

        tick++;

        if (tick % 5 == 0) {

            if (check_ptrace_attach()) {
                LOGE("⚠️ Ptrace 反附加检测到调试器!");
                g_ptrace_locked = 1;
            }

            check_art_hooks();

            // 【2026-10-13】代码段完整性：.text 被改写即为 inline hook 的强信号
            if (tvl_selfcheck_verify() == 1 && !g_code_tampered) {
                g_code_tampered = 1;
                LOGE("⚠️ 代码段校验失败：.text 已被改写（疑似 inline hook）");
            }

            int tracerPid = check_tracer_pid();
            if (tracerPid) {
                LOGE("⚠️ tracer 检测到调试器!");
            }
        }

        if (tick % 30 == 0) {
            signal(SIGTRAP, signal_handler);
            signal(SIGILL, signal_handler);
            signal(SIGSEGV, signal_handler);
        }

        usleep(2000 * 1000);
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tv_live_security_SecurityCore_nativeStartMonitor(JNIEnv*, jclass) {
    if (g_monitor_run) return;
    g_monitor_run = 1;
    pthread_create(&g_monitor_thread, nullptr, monitor_loop, nullptr);
    pthread_detach(g_monitor_thread);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tv_live_security_SecurityCore_nativeGetSecurityStatus(JNIEnv* env, jclass) {
    char buf[256];
    snprintf(buf, sizeof(buf),
        "Tracer=%d MapsHit=%d PortHit=%d Root=%d Emu=%d PtraceLocked=%d Hooks=%d CodeTampered=%d",
        check_tracer_pid(), g_frida_in_maps, scan_frida_ports(),
        check_root(), check_emulator(), g_ptrace_locked, g_hook_detected, g_code_tampered);
    return env->NewStringUTF(buf);
}
