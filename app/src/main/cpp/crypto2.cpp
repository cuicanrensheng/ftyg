// =============================================================
// crypto2.cpp —— 原生密码学扩展
//
// 现状：aes.cpp 只实现了 AES-256-CBC 的**解密**（连正向分组都没有），
// 且 padding 校验失败时静默 return 0，接近 padding oracle 形态。
//
// 本文件补齐：
//   - AES-256 正向分组 / CTR / GCM(AEAD，带 16 字节认证标签)
//   - SHA-256 / HMAC-SHA256（常数时间比较）
//   - HKDF-SHA256（用于第 5 步「运行时密钥派生」）
//
// 全部为新增能力，不触碰 aes.cpp 既有的 CBC 解密路径。
// 文件内不导出任何多余符号（CMake 已开 -fvisibility=hidden）。
// =============================================================
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>

#define TAG "TVLS"
#define LOGE2(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------- AES-256 正向分组 ----------------

const uint8_t SBOX[256] = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
};

const uint8_t RCON[11] = {0x00,0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36};

void aes256_expand(const uint8_t key[32], uint8_t rk[240]) {
    memcpy(rk, key, 32);
    int i = 8;
    uint8_t t[4];
    while (i < 60) {
        for (int j = 0; j < 4; ++j) t[j] = rk[(i - 1) * 4 + j];
        if (i % 8 == 0) {
            uint8_t tmp = t[0];
            t[0] = (uint8_t)(SBOX[t[1]] ^ RCON[i / 8]);
            t[1] = SBOX[t[2]];
            t[2] = SBOX[t[3]];
            t[3] = SBOX[tmp];
        } else if (i % 8 == 4) {
            for (int j = 0; j < 4; ++j) t[j] = SBOX[t[j]];
        }
        for (int j = 0; j < 4; ++j) rk[i * 4 + j] = (uint8_t)(rk[(i - 8) * 4 + j] ^ t[j]);
        ++i;
    }
}

inline void sub_bytes(uint8_t s[16]) { for (int i = 0; i < 16; ++i) s[i] = SBOX[s[i]]; }

inline void shift_rows(uint8_t s[16]) {
    uint8_t t;
    t = s[1];  s[1]  = s[5];  s[5]  = s[9];  s[9]  = s[13]; s[13] = t;
    t = s[2];  s[2]  = s[10]; s[10] = t;
    t = s[6];  s[6]  = s[14]; s[14] = t;
    t = s[15]; s[15] = s[11]; s[11] = s[7];  s[7]  = s[3];  s[3]  = t;
}

inline uint8_t xtime2(uint8_t x) { return (uint8_t)((x << 1) ^ (((x >> 7) & 1) * 0x1b)); }

inline void mix_columns(uint8_t s[16]) {
    for (int c = 0; c < 4; ++c) {
        int i = c * 4;
        uint8_t a0 = s[i], a1 = s[i + 1], a2 = s[i + 2], a3 = s[i + 3];
        uint8_t x = (uint8_t)(a0 ^ a1 ^ a2 ^ a3);
        s[i]     = (uint8_t)(a0 ^ x ^ xtime2((uint8_t)(a0 ^ a1)));
        s[i + 1] = (uint8_t)(a1 ^ x ^ xtime2((uint8_t)(a1 ^ a2)));
        s[i + 2] = (uint8_t)(a2 ^ x ^ xtime2((uint8_t)(a2 ^ a3)));
        s[i + 3] = (uint8_t)(a3 ^ x ^ xtime2((uint8_t)(a3 ^ a0)));
    }
}

inline void add_rk(uint8_t s[16], const uint8_t* rk) { for (int i = 0; i < 16; ++i) s[i] ^= rk[i]; }

void aes_encrypt_block(const uint8_t in[16], uint8_t out[16], const uint8_t* rk) {
    uint8_t s[16];
    memcpy(s, in, 16);
    add_rk(s, rk);
    for (int r = 1; r < 14; ++r) {
        sub_bytes(s);
        shift_rows(s);
        mix_columns(s);
        add_rk(s, rk + r * 16);
    }
    sub_bytes(s);
    shift_rows(s);
    add_rk(s, rk + 14 * 16);
    memcpy(out, s, 16);
}

// GCM 计数器：只递增低 32 位（大端），inc32
inline void inc32(uint8_t ctr[16]) {
    for (int i = 15; i >= 12; --i) {
        if (++ctr[i] != 0) break;
    }
}

void ctr_xcrypt(const uint8_t* rk, uint8_t ctr[16],
                const uint8_t* in, size_t len, uint8_t* out) {
    size_t off = 0;
    uint8_t ks[16];
    while (off < len) {
        aes_encrypt_block(ctr, ks, rk);
        size_t n = (len - off < 16) ? (len - off) : 16;
        for (size_t i = 0; i < n; ++i) out[off + i] = (uint8_t)(in[off + i] ^ ks[i]);
        inc32(ctr);
        off += n;
    }
}

// GF(2^128) 乘法（GCM 约定：右端为最高位）
void gf_mul(uint8_t X[16], const uint8_t Y[16]) {
    uint8_t Z[16];
    uint8_t V[16];
    memset(Z, 0, 16);
    memcpy(V, Y, 16);
    for (int i = 0; i < 128; ++i) {
        if (X[i >> 3] & (0x80 >> (i & 7))) {
            for (int j = 0; j < 16; ++j) Z[j] ^= V[j];
        }
        uint8_t lsb = (uint8_t)(V[15] & 1);
        for (int j = 15; j > 0; --j) V[j] = (uint8_t)((V[j] >> 1) | (V[j - 1] << 7));
        V[0] >>= 1;
        if (lsb) V[0] ^= 0xe1;
    }
    memcpy(X, Z, 16);
}

void ghash(const uint8_t H[16],
           const uint8_t* aad, size_t aadlen,
           const uint8_t* ct, size_t ctlen,
           uint8_t out[16]) {
    uint8_t Y[16];
    uint8_t blk[16];
    memset(Y, 0, 16);

    const uint8_t* p = aad;
    size_t n = aadlen;
    while (n) {
        size_t k = (n < 16) ? n : 16;
        memset(blk, 0, 16);
        memcpy(blk, p, k);
        for (int j = 0; j < 16; ++j) Y[j] ^= blk[j];
        gf_mul(Y, H);
        p += k;
        n -= k;
    }

    p = ct;
    n = ctlen;
    while (n) {
        size_t k = (n < 16) ? n : 16;
        memset(blk, 0, 16);
        memcpy(blk, p, k);
        for (int j = 0; j < 16; ++j) Y[j] ^= blk[j];
        gf_mul(Y, H);
        p += k;
        n -= k;
    }

    uint8_t lenb[16];
    memset(lenb, 0, 16);
    uint64_t ab = (uint64_t)aadlen * 8u;
    uint64_t cb = (uint64_t)ctlen * 8u;
    for (int i = 0; i < 8; ++i) {
        lenb[7 - i]  = (uint8_t)(ab >> (8 * i));
        lenb[15 - i] = (uint8_t)(cb >> (8 * i));
    }
    for (int j = 0; j < 16; ++j) Y[j] ^= lenb[j];
    gf_mul(Y, H);
    memcpy(out, Y, 16);
}

// 常数时间比较，返回 1 = 相等
int ct_equal(const uint8_t* a, const uint8_t* b, size_t n) {
    uint8_t d = 0;
    for (size_t i = 0; i < n; ++i) d = (uint8_t)(d | (a[i] ^ b[i]));
    return d == 0 ? 1 : 0;
}

// ---------------- AES-256-GCM 核心（JNI 壳与 nbmovie.cpp 共用） ----------------
//
// 【2026-10-21】从下面的两个 JNI 函数中抽出，逻辑与原实现逐行等价：
// 密文||16B tag 写入 out（容量 >= plainLen+16）。返回 0；参数错 -2。
int gcm_seal_core(const uint8_t* key, const uint8_t* nonce, size_t nonceLen,
                  const uint8_t* plain, size_t plainLen,
                  const uint8_t* aad, size_t aadLen, uint8_t* out) {
    if (key == nullptr || nonce == nullptr || nonceLen == 0 || out == nullptr) return -2;
    if (plainLen > 0 && plain == nullptr) return -2;
    if (aadLen > 0 && aad == nullptr) return -2;

    uint8_t rk[240];
    aes256_expand(key, rk);

    uint8_t H[16];
    uint8_t zero[16];
    memset(zero, 0, 16);
    aes_encrypt_block(zero, H, rk);

    uint8_t J0[16];
    if (nonceLen == 12) {
        memcpy(J0, nonce, 12);
        J0[12] = 0; J0[13] = 0; J0[14] = 0; J0[15] = 1;
    } else {
        ghash(H, nullptr, 0, nonce, nonceLen, J0);
    }

    if (plainLen > 0) {
        uint8_t ctr[16];
        memcpy(ctr, J0, 16);
        inc32(ctr);
        ctr_xcrypt(rk, ctr, plain, plainLen, out);
    }

    uint8_t S[16];
    ghash(H, aadLen > 0 ? aad : nullptr, aadLen, out, plainLen, S);
    uint8_t E0[16];
    aes_encrypt_block(J0, E0, rk);
    for (int i = 0; i < 16; ++i) out[plainLen + i] = (uint8_t)(E0[i] ^ S[i]);

    memset(rk, 0, sizeof(rk));
    memset(H, 0, sizeof(H));
    memset(J0, 0, sizeof(J0));
    memset(S, 0, sizeof(S));
    memset(E0, 0, sizeof(E0));
    return 0;
}

// 验签通过：明文写入 out（容量 >= ctTagLen-16）。返回 0；tag 不符 -1；参数错 -2。
int gcm_open_core(const uint8_t* key, const uint8_t* nonce, size_t nonceLen,
                  const uint8_t* ctTag, size_t ctTagLen,
                  const uint8_t* aad, size_t aadLen, uint8_t* out) {
    if (key == nullptr || nonce == nullptr || nonceLen == 0 || out == nullptr ||
        ctTag == nullptr || ctTagLen < 16) {
        return -2;
    }
    if (aadLen > 0 && aad == nullptr) return -2;
    const size_t ctlen = ctTagLen - 16;

    uint8_t rk[240];
    aes256_expand(key, rk);

    uint8_t H[16];
    uint8_t zero[16];
    memset(zero, 0, 16);
    aes_encrypt_block(zero, H, rk);

    uint8_t J0[16];
    if (nonceLen == 12) {
        memcpy(J0, nonce, 12);
        J0[12] = 0; J0[13] = 0; J0[14] = 0; J0[15] = 1;
    } else {
        ghash(H, nullptr, 0, nonce, nonceLen, J0);
    }

    uint8_t S[16];
    ghash(H, aadLen > 0 ? aad : nullptr, aadLen, ctTag, ctlen, S);
    uint8_t E0[16];
    aes_encrypt_block(J0, E0, rk);
    uint8_t expect[16];
    for (int i = 0; i < 16; ++i) expect[i] = (uint8_t)(E0[i] ^ S[i]);

    int rc = ct_equal(expect, ctTag + ctlen, 16) ? 0 : -1;
    if (rc == 0 && ctlen > 0) {
        uint8_t ctr[16];
        memcpy(ctr, J0, 16);
        inc32(ctr);
        ctr_xcrypt(rk, ctr, ctTag, ctlen, out);
    }

    memset(rk, 0, sizeof(rk));
    memset(H, 0, sizeof(H));
    memset(J0, 0, sizeof(J0));
    memset(S, 0, sizeof(S));
    memset(E0, 0, sizeof(E0));
    memset(expect, 0, sizeof(expect));
    return rc;
}

// ---------------- SHA-256 ----------------

const uint32_t K256[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

inline uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

struct Sha256 {
    uint32_t h[8];
    uint64_t len;
    uint8_t buf[64];
    size_t buflen;
};

void sha256_init(Sha256* c) {
    c->h[0] = 0x6a09e667; c->h[1] = 0xbb67ae85;
    c->h[2] = 0x3c6ef372; c->h[3] = 0xa54ff53a;
    c->h[4] = 0x510e527f; c->h[5] = 0x9b05688c;
    c->h[6] = 0x1f83d9ab; c->h[7] = 0x5be0cd19;
    c->len = 0;
    c->buflen = 0;
}

void sha256_block(Sha256* c, const uint8_t* p) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
               ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = c->h[0], b = c->h[1], cc = c->h[2], d = c->h[3];
    uint32_t e = c->h[4], f = c->h[5], g = c->h[6], hh = c->h[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = hh + S1 + ch + K256[i] + w[i];
        uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & cc) ^ (b & cc);
        uint32_t t2 = S0 + maj;
        hh = g; g = f; f = e; e = d + t1;
        d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d;
    c->h[4] += e; c->h[5] += f; c->h[6] += g; c->h[7] += hh;
}

void sha256_update(Sha256* c, const uint8_t* p, size_t n) {
    c->len += (uint64_t)n;
    while (n) {
        if (c->buflen == 0 && n >= 64) {
            sha256_block(c, p);
            p += 64;
            n -= 64;
            continue;
        }
        size_t k = 64 - c->buflen;
        if (k > n) k = n;
        memcpy(c->buf + c->buflen, p, k);
        c->buflen += k;
        p += k;
        n -= k;
        if (c->buflen == 64) {
            sha256_block(c, c->buf);
            c->buflen = 0;
        }
    }
}

void sha256_final(Sha256* c, uint8_t out[32]) {
    uint64_t bits = c->len * 8u;
    uint8_t pad = 0x80;
    sha256_update(c, &pad, 1);
    uint8_t zero = 0x00;
    while (c->buflen != 56) sha256_update(c, &zero, 1);
    uint8_t lb[8];
    for (int i = 0; i < 8; ++i) lb[i] = (uint8_t)(bits >> (8 * (7 - i)));
    sha256_update(c, lb, 8);
    for (int i = 0; i < 8; ++i) {
        out[i * 4]     = (uint8_t)(c->h[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(c->h[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(c->h[i] >> 8);
        out[i * 4 + 3] = (uint8_t)(c->h[i]);
    }
}

void sha256(const uint8_t* p, size_t n, uint8_t out[32]) {
    Sha256 c;
    sha256_init(&c);
    sha256_update(&c, p, n);
    sha256_final(&c, out);
}

// ---------------- HMAC-SHA256 ----------------

void hmac_sha256(const uint8_t* key, size_t keylen,
                 const uint8_t* msg, size_t msglen,
                 uint8_t out[32]) {
    uint8_t k[64];
    memset(k, 0, 64);
    if (keylen > 64) {
        sha256(key, keylen, k);
    } else {
        memcpy(k, key, keylen);
    }
    uint8_t ipad[64], opad[64];
    for (int i = 0; i < 64; ++i) {
        ipad[i] = (uint8_t)(k[i] ^ 0x36);
        opad[i] = (uint8_t)(k[i] ^ 0x5c);
    }
    uint8_t inner[32];
    Sha256 c;
    sha256_init(&c);
    sha256_update(&c, ipad, 64);
    sha256_update(&c, msg, msglen);
    sha256_final(&c, inner);

    sha256_init(&c);
    sha256_update(&c, opad, 64);
    sha256_update(&c, inner, 32);
    sha256_final(&c, out);

    memset(k, 0, sizeof(k));
    memset(ipad, 0, sizeof(ipad));
    memset(opad, 0, sizeof(opad));
    memset(inner, 0, sizeof(inner));
}

// HKDF-SHA256
int hkdf_sha256(const uint8_t* ikm, size_t ikmlen,
                const uint8_t* salt, size_t saltlen,
                const uint8_t* info, size_t infolen,
                uint8_t* out, size_t outlen) {
    if (outlen == 0 || outlen > 255 * 32) return -1;
    uint8_t zero[32];
    memset(zero, 0, 32);
    if (salt == nullptr || saltlen == 0) {
        salt = zero;
        saltlen = 32;
    }
    uint8_t prk[32];
    hmac_sha256(salt, saltlen, ikm, ikmlen, prk);

    uint8_t t[32];
    size_t tlen = 0;
    size_t done = 0;
    uint8_t counter = 1;
    while (done < outlen) {
        Sha256 c;
        sha256_init(&c);
        sha256_update(&c, t, tlen);
        if (infolen) sha256_update(&c, info, infolen);
        sha256_update(&c, &counter, 1);
        sha256_final(&c, t);
        tlen = 32;
        size_t k = (outlen - done < 32) ? (outlen - done) : 32;
        memcpy(out + done, t, k);
        done += k;
        ++counter;
    }
    memset(prk, 0, sizeof(prk));
    memset(t, 0, sizeof(t));
    return 0;
}

// ---------------- JNI 辅助 ----------------

struct JBytes {
    uint8_t* p = nullptr;
    jsize len = 0;
    bool ok = false;
};

JBytes acquire(JNIEnv* env, jbyteArray arr) {
    JBytes b;
    if (arr == nullptr) return b;
    b.len = env->GetArrayLength(arr);
    if (b.len < 0) return b;
    b.p = (uint8_t*)malloc((size_t)(b.len > 0 ? b.len : 1));
    if (b.p == nullptr) return b;
    if (b.len > 0) env->GetByteArrayRegion(arr, 0, b.len, (jbyte*)b.p);
    b.ok = true;
    return b;
}

void release(JBytes* b) {
    if (b->p) {
        memset(b->p, 0, (size_t)(b->len > 0 ? b->len : 1));
        free(b->p);
        b->p = nullptr;
    }
    b->len = 0;
    b->ok = false;
}

jbyteArray make_array(JNIEnv* env, const uint8_t* p, size_t n) {
    jbyteArray out = env->NewByteArray((jsize)n);
    if (out == nullptr) return nullptr;
    if (n > 0) env->SetByteArrayRegion(out, 0, (jsize)n, (const jbyte*)p);
    return out;
}

}  // namespace

// =============================================================
// JNI 导出
// =============================================================

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeSha256(JNIEnv* env, jclass, jbyteArray data) {
    JBytes d = acquire(env, data);
    if (!d.ok) return nullptr;
    uint8_t out[32];
    sha256(d.p, (size_t)d.len, out);
    jbyteArray r = make_array(env, out, 32);
    release(&d);
    memset(out, 0, sizeof(out));
    return r;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeHmacSha256(JNIEnv* env, jclass,
                                                        jbyteArray key, jbyteArray msg) {
    JBytes k = acquire(env, key);
    JBytes m = acquire(env, msg);
    if (!k.ok || !m.ok) {
        release(&k);
        release(&m);
        return nullptr;
    }
    uint8_t out[32];
    hmac_sha256(k.p, (size_t)k.len, m.p, (size_t)m.len, out);
    jbyteArray r = make_array(env, out, 32);
    release(&k);
    release(&m);
    memset(out, 0, sizeof(out));
    return r;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeHkdfSha256(JNIEnv* env, jclass,
                                                        jbyteArray ikm, jbyteArray salt,
                                                        jbyteArray info, jint outLen) {
    if (outLen <= 0 || outLen > 255 * 32) return nullptr;
    JBytes ik = acquire(env, ikm);
    JBytes sa = acquire(env, salt);
    JBytes inf = acquire(env, info);
    if (!ik.ok) {
        release(&ik); release(&sa); release(&inf);
        return nullptr;
    }
    uint8_t* out = (uint8_t*)malloc((size_t)outLen);
    if (out == nullptr) {
        release(&ik); release(&sa); release(&inf);
        return nullptr;
    }
    int rc = hkdf_sha256(ik.p, (size_t)ik.len,
                         sa.ok ? sa.p : nullptr, sa.ok ? (size_t)sa.len : 0,
                         inf.ok ? inf.p : nullptr, inf.ok ? (size_t)inf.len : 0,
                         out, (size_t)outLen);
    jbyteArray r = nullptr;
    if (rc == 0) r = make_array(env, out, (size_t)outLen);
    else LOGE2("hkdf failed rc=%d", rc);
    memset(out, 0, (size_t)outLen);
    free(out);
    release(&ik); release(&sa); release(&inf);
    return r;
}

// 输出：密文 || 16 字节认证标签
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeAesGcmEncrypt(JNIEnv* env, jclass,
                                                           jbyteArray key, jbyteArray iv,
                                                           jbyteArray plain, jbyteArray aad) {
    JBytes k = acquire(env, key);
    JBytes v = acquire(env, iv);
    JBytes p = acquire(env, plain);
    JBytes a = acquire(env, aad);
    if (!k.ok || !v.ok || !p.ok || k.len != 32 || v.len == 0) {
        LOGE2("gcm enc: bad args key=%d iv=%d", k.len, v.len);
        release(&k); release(&v); release(&p); release(&a);
        return nullptr;
    }

    uint8_t* ct = (uint8_t*)malloc((size_t)p.len + 16);
    if (ct == nullptr) {
        release(&k); release(&v); release(&p); release(&a);
        return nullptr;
    }
    int rc = gcm_seal_core(k.p, v.p, (size_t)v.len, p.p, (size_t)p.len,
                           a.ok ? a.p : nullptr, a.ok ? (size_t)a.len : 0, ct);
    jbyteArray r = nullptr;
    if (rc == 0) {
        r = make_array(env, ct, (size_t)p.len + 16);
    } else {
        LOGE2("gcm enc: core rc=%d", rc);
    }
    memset(ct, 0, (size_t)p.len + 16);
    free(ct);
    release(&k); release(&v); release(&p); release(&a);
    return r;
}

// 入参：密文 || 16 字节标签；标签不符返回 null（不抛异常，由 Java 侧判空）
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeAesGcmDecrypt(JNIEnv* env, jclass,
                                                           jbyteArray key, jbyteArray iv,
                                                           jbyteArray ctTag, jbyteArray aad) {
    JBytes k = acquire(env, key);
    JBytes v = acquire(env, iv);
    JBytes c = acquire(env, ctTag);
    JBytes a = acquire(env, aad);
    if (!k.ok || !v.ok || !c.ok || k.len != 32 || v.len == 0 || c.len < 16) {
        LOGE2("gcm dec: bad args key=%d iv=%d ct=%d", k.len, v.len, c.len);
        release(&k); release(&v); release(&c); release(&a);
        return nullptr;
    }

    const size_t ctlen = (size_t)c.len - 16;
    uint8_t* pt = (uint8_t*)malloc(ctlen > 0 ? ctlen : 1);
    if (pt == nullptr) {
        release(&k); release(&v); release(&c); release(&a);
        return nullptr;
    }
    int rc = gcm_open_core(k.p, v.p, (size_t)v.len, c.p, (size_t)c.len,
                           a.ok ? a.p : nullptr, a.ok ? (size_t)a.len : 0, pt);
    jbyteArray r = nullptr;
    if (rc == 0) {
        r = make_array(env, pt, ctlen);
    } else if (rc == -1) {
        LOGE2("gcm dec: auth tag mismatch");
    } else {
        LOGE2("gcm dec: core rc=%d", rc);
    }
    memset(pt, 0, ctlen > 0 ? ctlen : 1);
    free(pt);
    release(&k); release(&v); release(&c); release(&a);
    return r;
}

// =============================================================
// 【2026-10-13】把正向 AES-256 分组能力开放给同库的 security.cpp。
//
// 匿名 namespace 内的 aes256_expand / aes_encrypt_block 无法跨编译单元引用，
// 这里做两个 C 链接的薄转发（-fvisibility=hidden 下同库内链接不受影响，
// 且不会进动态符号表）。
//
// 用途：security.cpp 的 nativeEncrypt 需要正向分组（把「生成密文」这件事
// 从外部脚本搬进 SO）。本文件自身的 GCM 路径不变。
// =============================================================
extern "C" void tvl_aes256_expand(const uint8_t key[32], uint8_t rk[240]) {
    aes256_expand(key, rk);
}

extern "C" void tvl_aes_encrypt_block(const uint8_t in[16], uint8_t out[16],
                                      const uint8_t* rk) {
    aes_encrypt_block(in, out, rk);
}

// =============================================================
// 【2026-10-21】以下 4 个转发器供 nbmovie.cpp（4K影视协议层）复用：
// 协议只需「HMAC 签名 + GCM 加解密」两个原语，且要与 Java 侧
// SecurityCore 走同一份实现，避免两套算法漂移。
// 声明见 cpp/tvl_crypto.h。
// =============================================================
extern "C" void tvl_sha256(const uint8_t* data, size_t len, uint8_t out[32]) {
    sha256(data, len, out);
}

extern "C" void tvl_hmac_sha256(const uint8_t* key, size_t keyLen,
                                const uint8_t* msg, size_t msgLen, uint8_t out[32]) {
    hmac_sha256(key, keyLen, msg, msgLen, out);
}

extern "C" int tvl_aes256_gcm_seal(const uint8_t key[32], const uint8_t* nonce, size_t nonceLen,
                                   const uint8_t* plain, size_t plainLen,
                                   const uint8_t* aad, size_t aadLen, uint8_t* out) {
    return gcm_seal_core(key, nonce, nonceLen, plain, plainLen, aad, aadLen, out);
}

extern "C" int tvl_aes256_gcm_open(const uint8_t key[32], const uint8_t* nonce, size_t nonceLen,
                                   const uint8_t* ctTag, size_t ctTagLen,
                                   const uint8_t* aad, size_t aadLen, uint8_t* out) {
    return gcm_open_core(key, nonce, nonceLen, ctTag, ctTagLen, aad, aadLen, out);
}
