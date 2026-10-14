// =============================================================
// selfcheck.cpp —— 运行时 .text 完整性自校验
//
// 目的：现有 check_memory_integrity() 是死代码（所有分支恒 return 0），
// 且没有任何机制能发现「代码段被运行时改写」——Frida 的
// Interceptor.attach 正是靠 inline hook 改函数序言。
//
// 做法（不需要在构建期写死校验值，因此不会被重打包绕开）：
//   1) JNI_OnLoad 时定位自身 so 的可执行 PT_LOAD 段，算一次 SHA-256 作基准；
//   2) nativeSelfCheck() 随时重算比对 —— 代码段被改一个字节即报 1。
//
// 与 SHA-256 实现共用 crypto2.cpp 里的私有实现会造成跨 TU 重复，
// 这里用一段独立的 FNV-1a 64（够用且零依赖，比对的是"有无变化"而非抗碰撞）。
// =============================================================
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <cstdint>
#include <cstring>

#define TAG "TVLS"
#define LOGE3(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 前置声明：locate_text() 里要取它的地址做兜底定位
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);

namespace {

struct SegHit {
    uintptr_t base;
    uintptr_t vaddr;
    size_t    memsz;
    int       found;
};

int phdr_cb(struct dl_phdr_info* info, size_t, void* data) {
    SegHit* s = (SegHit*)data;
    if ((uintptr_t)info->dlpi_addr != s->base) return 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        if ((ph->p_flags & PF_X) == 0) continue;
        s->vaddr = (uintptr_t)ph->p_vaddr;
        s->memsz = (size_t)ph->p_memsz;
        s->found = 1;
        return 1;
    }
    return 0;
}

// 通过 .so 文件名前缀定位自身模块（隐藏可见性下 dladdr 拿不到本地符号，
// 但 JNI_OnLoad 一定在 .dynsym 里，两条路都留着互为兜底）。
int name_cb(struct dl_phdr_info* info, size_t, void* data) {
    SegHit* s = (SegHit*)data;
    if (info->dlpi_name == nullptr) return 0;
    if (strstr(info->dlpi_name, "tvlive_security") == nullptr) return 0;
    s->base = (uintptr_t)info->dlpi_addr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        if ((ph->p_flags & PF_X) == 0) continue;
        s->vaddr = (uintptr_t)ph->p_vaddr;
        s->memsz = (size_t)ph->p_memsz;
        s->found = 1;
        return 1;
    }
    return 0;
}

uint64_t fnv1a64(const uint8_t* p, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; ++i) {
        h ^= (uint64_t)p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

uint8_t*  g_text = nullptr;
size_t    g_text_len = 0;
uint64_t  g_baseline = 0;
volatile int g_ready = 0;
volatile int g_last_result = 0;

bool locate_text() {
    SegHit s;
    memset(&s, 0, sizeof(s));
    dl_iterate_phdr(name_cb, &s);
    if (!s.found) {
        // 兜底：用导出的 JNI_OnLoad 反查模块基址
        Dl_info di;
        memset(&di, 0, sizeof(di));
        if (dladdr((void*)&JNI_OnLoad, &di) != 0 && di.dli_fbase != nullptr) {
            s.base = (uintptr_t)di.dli_fbase;
            s.found = 0;
            dl_iterate_phdr(phdr_cb, &s);
        }
    }
    if (!s.found || s.memsz == 0) return false;
    g_text = (uint8_t*)(s.base + s.vaddr);
    g_text_len = s.memsz;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    if (locate_text()) {
        g_baseline = fnv1a64(g_text, g_text_len);
        g_ready = 1;
    } else {
        LOGE3("selfcheck: 未能定位自身 .text 段，自校验停用");
    }
    return JNI_VERSION_1_6;
}

// 供 security.cpp 的监控线程调用（同 .so 内静态链接，无需导出到 .dynsym）
extern "C" int tvl_selfcheck_verify(void) {
    if (!g_ready) return -2;
    uint64_t now = fnv1a64(g_text, g_text_len);
    g_last_result = (now == g_baseline) ? 0 : 1;
    return g_last_result;
}

// 0 = 未被改动；1 = 代码段被改写；负数 = 不可用
extern "C" JNIEXPORT jint JNICALL
Java_com_tv_live_security_SecurityCore_nativeSelfCheck(JNIEnv*, jclass) {
    return tvl_selfcheck_verify();
}

// 暴露段长与最近一次结果，便于在 9527 日志里核对自校验是否真的在跑
extern "C" JNIEXPORT jint JNICALL
Java_com_tv_live_security_SecurityCore_nativeTextLen(JNIEnv*, jclass) {
    return (jint)g_text_len;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tv_live_security_SecurityCore_nativeLastSelfCheck(JNIEnv*, jclass) {
    return g_last_result;
}
