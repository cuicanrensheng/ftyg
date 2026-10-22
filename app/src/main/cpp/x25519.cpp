// =============================================================
// x25519.cpp —— X25519 (RFC 7748) 标量乘，纯 C 实现，零第三方依赖
//
// 背景：4K影视（NbMovie）协议的密钥交换。其 Go 侧 libgojni.so 用
// golang.org/x/crypto/curve25519.X25519 做 ECDH：
//     shared = X25519(priv, serverPub)   // 32B 直接作 AES-256 key，无 KDF
// 本项目「不移植任何第三方 .so」，故按 RFC 7748 重新实现。
//
// 算法：Montgomery ladder + GF(2^255-19) 16 位 limb 域运算
//（经典公开域实现，与 TweetNaCl / ref10 数学等价）。
//
// 与 Go 侧行为对齐点（缺一不可，否则 ECDH 结果不匹配）：
//   1. scalar 钳制：k[0] &= 248; k[31] &= 127; k[31] |= 64（库内完成）
//   2. u 坐标解包时屏蔽最高位（o[15] &= 0x7fff）
//   3. 输出 32 字节小端打包；all-zero 输出不判错（Go 侧同样不判）
//
// 编译开关 X25519_STANDALONE：去掉 jni.h/android-log 依赖，
// 只留 C 入口 tvl_x25519，供 NDK 独立可执行文件跑官方向量自检。
// =============================================================
#include <cstdint>
#include <cstring>

#ifndef X25519_STANDALONE
#include <jni.h>
#include <android/log.h>
#define TAG "TVLS"
#define LOGE2(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

namespace {

// ---------------- GF(2^255-19) 域元素：16 个 16 位 limb ----------------
typedef int64_t gf[16];

// 121665 = (p-5)/8 的系数，Montgomery ladder 常数
const int64_t GF_121665[16] = {0xDB41, 1};

void car25519(gf o) {
    for (int i = 0; i < 16; ++i) {
        o[i] += (1LL << 16);
        int64_t c = o[i] >> 16;
        o[(i + 1) * (i < 15)] += c - 1 + 37 * (c - 1) * (i == 15);
        o[i] -= c << 16;
    }
}

// 常数时间条件交换：b==1 时交换 p/q
void sel25519(gf p, gf q, int b) {
    int64_t c = ~(b - 1);
    for (int i = 0; i < 16; ++i) {
        int64_t t = c & (p[i] ^ q[i]);
        p[i] ^= t;
        q[i] ^= t;
    }
}

void pack25519(uint8_t* o, const gf n) {
    gf m, t;
    for (int i = 0; i < 16; ++i) t[i] = n[i];
    car25519(t);
    car25519(t);
    car25519(t);
    for (int j = 0; j < 2; ++j) {
        m[0] = t[0] - 0xffed;
        for (int i = 1; i < 15; ++i) {
            m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
            m[i - 1] &= 0xffff;
        }
        m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
        int b = (m[15] >> 16) & 1;
        m[14] &= 0xffff;
        sel25519(t, m, 1 - b);
    }
    for (int i = 0; i < 16; ++i) {
        o[2 * i] = (uint8_t)(t[i] & 0xff);
        o[2 * i + 1] = (uint8_t)(t[i] >> 8);
    }
}

void unpack25519(gf o, const uint8_t* n) {
    for (int i = 0; i < 16; ++i) o[i] = n[2 * i] + ((int64_t)n[2 * i + 1] << 8);
    o[15] &= 0x7fff;  // u 坐标最高位按 RFC 7748 屏蔽
}

void add_gf(gf o, const gf a, const gf b) {
    for (int i = 0; i < 16; ++i) o[i] = a[i] + b[i];
}

void sub_gf(gf o, const gf a, const gf b) {
    for (int i = 0; i < 16; ++i) o[i] = a[i] - b[i];
}

void mul_gf(gf o, const gf a, const gf b) {
    int64_t t[31];
    for (int i = 0; i < 31; ++i) t[i] = 0;
    for (int i = 0; i < 16; ++i) {
        for (int j = 0; j < 16; ++j) t[i + j] += a[i] * b[j];
    }
    // 归约：乘积高 16~30 项权重为 2^256 起，2^256 ≡ 38 (mod 2^255-19)，
    // 故 t[i+16] 折回 t[i] 需乘 38（i=0..14 恰好覆盖 t[16..30]）。
    for (int i = 0; i < 15; ++i) t[i] += 38 * t[i + 16];
    for (int i = 0; i < 16; ++i) o[i] = t[i];
    car25519(o);
    car25519(o);
}

void sqr_gf(gf o, const gf a) { mul_gf(o, a, a); }

// 模逆：a^(p-2)，p-2 = 2^255 - 21
void inv25519(gf o, const gf i) {
    gf c;
    for (int a = 0; a < 16; ++a) c[a] = i[a];
    for (int a = 253; a >= 0; --a) {
        sqr_gf(c, c);
        if (a != 2 && a != 4) mul_gf(c, c, i);
    }
    for (int a = 0; a < 16; ++a) o[a] = c[a];
}

// RFC 7748 X25519：q = X25519(n, p)
void x25519_core(uint8_t* q, const uint8_t* n, const uint8_t* p) {
    uint8_t z[32];
    for (int i = 0; i < 31; ++i) z[i] = n[i];
    z[31] = (uint8_t)((n[31] & 127) | 64);  // 钳制高位
    z[0] = (uint8_t)(z[0] & 248);           // 钳制低位

    int64_t x[80];
    gf a, b, c, d, e, f;
    unpack25519(x, p);
    for (int i = 0; i < 16; ++i) {
        b[i] = x[i];
        d[i] = a[i] = c[i] = 0;
    }
    a[0] = d[0] = 1;
    for (int i = 254; i >= 0; --i) {
        int r = (z[i >> 3] >> (i & 7)) & 1;
        sel25519(a, b, r);
        sel25519(c, d, r);
        add_gf(e, a, c);
        sub_gf(a, a, c);
        add_gf(c, b, d);
        sub_gf(b, b, d);
        sqr_gf(d, e);
        sqr_gf(f, a);
        mul_gf(a, c, a);
        mul_gf(c, b, e);
        add_gf(e, a, c);
        sub_gf(a, a, c);
        sqr_gf(b, a);
        sub_gf(c, d, f);
        mul_gf(a, c, GF_121665);
        add_gf(a, a, d);
        mul_gf(c, c, a);
        mul_gf(a, d, f);
        mul_gf(d, b, x);   // x[0..15] 仍持有解包后的原始 u 坐标
        sqr_gf(b, e);
        sel25519(a, b, r);
        sel25519(c, d, r);
    }
    for (int i = 0; i < 16; ++i) {
        x[i + 16] = a[i];
        x[i + 32] = c[i];
        x[i + 48] = b[i];
        x[i + 64] = d[i];
    }
    inv25519(x + 32, x + 32);
    mul_gf(x + 16, x + 16, x + 32);
    pack25519(q, x + 16);
}

}  // namespace

extern "C" void tvl_x25519(uint8_t out[32], const uint8_t scalar[32], const uint8_t u[32]) {
    x25519_core(out, scalar, u);
}

#ifndef X25519_STANDALONE
// =============================================================
// JNI：SecurityCore.nativeX25519 —— Java 直达的 X25519 原语
// =============================================================
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeX25519(JNIEnv* env, jclass,
                                                    jbyteArray scalar, jbyteArray uPoint) {
    if (scalar == nullptr || uPoint == nullptr) {
        LOGE2("x25519: null input");
        return nullptr;
    }
    if (env->GetArrayLength(scalar) != 32 || env->GetArrayLength(uPoint) != 32) {
        LOGE2("x25519: bad len scalar=%d u=%d",
              env->GetArrayLength(scalar), env->GetArrayLength(uPoint));
        return nullptr;
    }
    uint8_t k[32], u[32], q[32];
    env->GetByteArrayRegion(scalar, 0, 32, (jbyte*)k);
    env->GetByteArrayRegion(uPoint, 0, 32, (jbyte*)u);
    x25519_core(q, k, u);
    memset(k, 0, sizeof(k));
    jbyteArray out = env->NewByteArray(32);
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, 32, (const jbyte*)q);
    return out;
}
#endif  // X25519_STANDALONE
