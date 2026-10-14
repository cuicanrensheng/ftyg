// =============================================================
// obfstr.h —— 编译期字符串加密
//
// 问题：security.cpp 里的检测特征（"frida" / "magisk" / "/system/bin/su" …）
// 全是 static const char[]，会原样落进 .rodata。一条 strings 就能把检测清单
// 全部列出来（实测 173 条可打印串里命中 34 条），改掉特征串即可绕过。
//
// 做法：用 constexpr 在编译期把字面量与 keystream 异或，密文进 .rodata；
// 运行时只在栈上按需解出，用完即散。特征串不再以明文出现在二进制里。
//
// 注意：keystream 由 (key, index) 决定，**同一个字符串务必用不同 key**，
// 否则密文相同、仍可被"聚类"识别。
// =============================================================
#ifndef TVLIVE_OBFSTR_H
#define TVLIVE_OBFSTR_H

#include <cstddef>
#include <cstdint>

namespace obf {

// 编译期 keystream（编码用，等价于 seed==1）
constexpr char ks_enc(int key, int i) {
    return (char)(((key * 131) + (i * 37) + 0x5A) & 0x7F);
}

// 运行时哨兵：初值恒为 1，但它是 volatile，编译器不得假定其值。
// 乘上去后 keystream 变成运行期未知量 → 解密结果无法被常量折叠回明文，
// 否则 clang 会把 s.enc[i]^ks(...) 直接算成字面量塞回 .text，加密形同虚设。
static volatile int g_obf_seed = 1;

inline char ks_rt(int key, int i) {
    return (char)((((key * 131) + (i * 37) + 0x5A) * (int)g_obf_seed) & 0x7F);
}

template <int N, int KEY>
struct Str {
    char enc[N];
    constexpr explicit Str(const char (&s)[N]) : enc() {
        for (int i = 0; i < N; ++i) enc[i] = (char)(s[i] ^ ks_enc(KEY, i));
    }
};

// 解到调用方缓冲区，并补 NUL。cap 不足则截断。
template <int N, int KEY>
inline void decode_to(const Str<N, KEY>& s, char* out, size_t cap) {
    if (cap == 0) return;
    const volatile char* src = s.enc;   // 走 volatile 读，禁止把密文当常量传播
    size_t n = (size_t)(N - 1);
    if (n > cap - 1) n = cap - 1;
    for (size_t i = 0; i < n; ++i) out[i] = (char)(src[i] ^ ks_rt(KEY, (int)i));
    out[n] = 0;
}

// hay 中是否包含加密串（大小写不敏感）。不分配内存。
template <int N, int KEY>
inline bool contains_ci(const char* hay, size_t hlen, const Str<N, KEY>& s) {
    const size_t n = (size_t)(N - 1);
    if (n == 0 || hay == nullptr || hlen < n) return false;
    char pat[128];
    if (n >= sizeof(pat)) return false;
    decode_to(s, pat, sizeof(pat));
    for (size_t i = 0; i + n <= hlen; ++i) {
        size_t j = 0;
        while (j < n) {
            char a = hay[i + j];
            char b = pat[j];
            if (a >= 'A' && a <= 'Z') a = (char)(a + 32);
            if (b >= 'A' && b <= 'Z') b = (char)(b + 32);
            if (a != b) break;
            ++j;
        }
        if (j == n) return true;
    }
    return false;
}

// 以 NUL 结尾的 C 串版本
template <int N, int KEY>
inline bool contains_ci_cstr(const char* hay, const Str<N, KEY>& s) {
    if (hay == nullptr) return false;
    size_t hlen = 0;
    while (hay[hlen] != 0) ++hlen;
    return contains_ci(hay, hlen, s);
}

// 完全相等（大小写敏感）
template <int N, int KEY>
inline bool equals(const char* hay, size_t hlen, const Str<N, KEY>& s) {
    const size_t n = (size_t)(N - 1);
    if (hay == nullptr || hlen != n) return false;
    char pat[128];
    if (n >= sizeof(pat)) return false;
    decode_to(s, pat, sizeof(pat));
    for (size_t i = 0; i < n; ++i) {
        if (hay[i] != pat[i]) return false;
    }
    return true;
}

}  // namespace obf

// 定义加密字符串常量：OBF_DEF(名字, "明文", 唯一 key)
#define OBF_DEF(name, lit, key) \
    static constexpr obf::Str<sizeof(lit), (key)> name{lit}

#endif  // TVLIVE_OBFSTR_H
