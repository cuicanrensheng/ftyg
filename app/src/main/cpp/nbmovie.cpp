// =============================================================
// nbmovie.cpp —— 4K影视（NbMovie）加密协议完整下沉 libtvlive_security.so
//
// 算法来源：libgojni.so 静态逆向（见《4K影视_加密算法逆向报告.md》），
// 不移植其任何 .so，全部用本库原语 + 纯 C 重写：
//
//   GenerateKeyPair():
//     priv   = crypto/rand(32B)
//     pub    = X25519(priv, basepoint)            // 32B
//     pubB64 = Base64(pub)
//     ts     = str(Unix())                        // 10 位秒（服务端按秒校验，
//                                                  // 毫秒会被判「签名已过期」）
//     sig    = Base64(HMAC_SHA256(HMAC_KEY, pubB64 + "|" + ts))
//     return pubB64 + "|" + sig + "|" + ts
//
//   ComputeSharedSecret(serverPubB64):
//     shared = X25519(priv, Base64Decode(serverPubB64))  // 32B 直接作
//                                                          // AES-256 key，无 KDF
//     return ""                                     // 空串 = 成功
//
//   EncryptRaw(plain):
//     nonce = crypto/rand(12B)
//     return nonce(12) || AES-256-GCM-Seal(shared, nonce, plain, aad=nil) || tag(16)
//
//   DecryptRaw(blob):
//     nonce = blob[0:12]; ctTag = blob[12:]
//     return AES-256-GCM-Open(shared, nonce, ctTag, aad=nil)
//
// 会话状态（priv/pub/shared）由本模块进程内持有并加锁保护，
// Java 侧（com.tv.live.nbmovie.NbMovieCrypto）只做薄桥接，
// 运行时自动完成加密/解密，无需 Java 层编排密码学细节。
//
// base64 口径：逆向确认 Go 侧三处共用同一个 Encoding 实例，但未能最终
// 判定是 StdEncoding 还是 URLEncoding（两者仅差 62/63 两个字符）。
// 默认走 StdEncoding（带 padding，44 字符）；如服务端 exchange 校验失败，
// 可运行时切换为 RawURLEncoding（无 padding，43 字符）重试——
// nb_set_base64_url() / NbMovieCrypto.setBase64Url()。
// 解码侧（服务端公钥）自动识别两种字母表并容忍缺 padding。
// =============================================================
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <pthread.h>

#include "x25519.h"
#include "tvl_crypto.h"
#include "obfstr.h"

#define TAG "TVLS-NB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------- 常量 ----------------

// HMAC-SHA256 密钥（33B）："mymovie_encryption_secret_2025_v1"
// 编译期异或混淆进 .rodata，运行时按需栈上解出、用完即散（obfstr.h）。
constexpr int kHmacKeyLen = 33;
const obf::Str<kHmacKeyLen + 1, 0x4E42> kHmacKeyObf("mymovie_encryption_secret_2025_v1");

// X25519 基点（RFC 7748：u = 9）
const uint8_t kBasePoint[32] = {9};

// ---------------- 会话状态（进程内单例） ----------------

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
uint8_t g_priv[32];
uint8_t g_pub[32];
uint8_t g_shared[32];   // 直接作 AES-256 key
bool g_hasPriv = false;
bool g_hasShared = false;
bool g_b64Url = false;  // false=StdEncoding(44) true=RawURLEncoding(43)

// ---------------- 随机数与时间 ----------------

bool rand_bytes(uint8_t* out, size_t n) {
    FILE* f = fopen("/dev/urandom", "rb");
    if (f == nullptr) {
        LOGE("rand: open /dev/urandom failed");
        return false;
    }
    size_t rd = fread(out, 1, n, f);
    fclose(f);
    if (rd != n) {
        LOGE("rand: short read %zu/%zu", rd, n);
        return false;
    }
    return true;
}

// 【2026-10-21 联调修正】时间戳取 Unix **秒**（10 位）：服务端签名校验与新鲜度
// 窗口均按秒解析（毫秒会被判「签名已过期」403，实测 ant.xhssapp.com 确认）。
int64_t now_s() {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec;
}

// ---------------- base64（Std / URL 双字母表，解码自动识别） ----------------

const char kB64Std[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const char kB64Url[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

size_t b64_encode(const uint8_t* in, size_t n, char* out, size_t cap) {
    const char* A = g_b64Url ? kB64Url : kB64Std;
    const bool pad = !g_b64Url;  // Std 带 padding；RawURL 不带
    size_t o = 0, i = 0;
    while (i + 3 <= n) {
        uint32_t v = ((uint32_t)in[i] << 16) | ((uint32_t)in[i + 1] << 8) | in[i + 2];
        if (o + 4 > cap) return 0;
        out[o++] = A[(v >> 18) & 63];
        out[o++] = A[(v >> 12) & 63];
        out[o++] = A[(v >> 6) & 63];
        out[o++] = A[v & 63];
        i += 3;
    }
    size_t rem = n - i;
    if (rem == 1) {
        uint32_t v = (uint32_t)in[i] << 16;
        if (o + 4 > cap) return 0;
        out[o++] = A[(v >> 18) & 63];
        out[o++] = A[(v >> 12) & 63];
        if (pad) { out[o++] = '='; out[o++] = '='; }
    } else if (rem == 2) {
        uint32_t v = ((uint32_t)in[i] << 16) | ((uint32_t)in[i + 1] << 8);
        if (o + 4 > cap) return 0;
        out[o++] = A[(v >> 18) & 63];
        out[o++] = A[(v >> 12) & 63];
        out[o++] = A[(v >> 6) & 63];
        if (pad) out[o++] = '=';
    }
    if (o >= cap) return 0;
    out[o] = 0;
    return o;
}

int b64_val(char c, bool url) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (url) {
        if (c == '-') return 62;
        if (c == '_') return 63;
    } else {
        if (c == '+') return 62;
        if (c == '/') return 63;
    }
    return -1;
}

// 自动识别字母表（出现 -/_ 判为 URL），容忍缺/多 padding。
// 成功返回字节数；失败 -1。
int b64_decode_auto(const char* s, uint8_t* out, size_t cap) {
    if (s == nullptr) return -1;
    size_t len = strlen(s);
    while (len > 0 && s[len - 1] == '=') --len;
    bool url = false;
    for (size_t i = 0; i < len; ++i) {
        if (s[i] == '-' || s[i] == '_') { url = true; break; }
    }
    size_t o = 0;
    uint32_t acc = 0;
    int bits = 0;
    for (size_t i = 0; i < len; ++i) {
        int v = b64_val(s[i], url);
        if (v < 0) return -1;
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (o >= cap) return -1;
            out[o++] = (uint8_t)((acc >> bits) & 0xFF);
        }
    }
    return (int)o;
}

}  // namespace

// =============================================================
// C 层会话 API（供单测与同库其他模块使用；JNI 只是薄壳）
// =============================================================

extern "C" {

// 生成密钥对并签名。out 接收 "pubB64|sigB64|ts"。返回 0；负数为错误。
int nb_generate_keypair(char* out, size_t cap) {
    if (out == nullptr || cap < 160) return -2;
    pthread_mutex_lock(&g_mu);

    if (!rand_bytes(g_priv, 32)) {
        pthread_mutex_unlock(&g_mu);
        return -1;
    }
    tvl_x25519(g_pub, g_priv, kBasePoint);
    g_hasPriv = true;
    g_hasShared = false;  // 新密钥对使旧共享密钥作废

    char pubB64[48];
    if (b64_encode(g_pub, 32, pubB64, sizeof(pubB64)) == 0) {
        pthread_mutex_unlock(&g_mu);
        return -3;
    }
    char ts[24];
    snprintf(ts, sizeof(ts), "%lld", (long long)now_s());

    // msg = pubB64 + "|" + ts
    char msg[96];
    snprintf(msg, sizeof(msg), "%s|%s", pubB64, ts);

    char key[kHmacKeyLen + 1];
    obf::decode_to(kHmacKeyObf, key, sizeof(key));
    uint8_t mac[32];
    tvl_hmac_sha256((const uint8_t*)key, kHmacKeyLen,
                    (const uint8_t*)msg, strlen(msg), mac);
    memset(key, 0, sizeof(key));

    char sigB64[48];
    if (b64_encode(mac, 32, sigB64, sizeof(sigB64)) == 0) {
        memset(mac, 0, sizeof(mac));
        pthread_mutex_unlock(&g_mu);
        return -3;
    }
    memset(mac, 0, sizeof(mac));

    int w = snprintf(out, cap, "%s|%s|%s", pubB64, sigB64, ts);
    pthread_mutex_unlock(&g_mu);
    if (w <= 0 || (size_t)w >= cap) return -4;
    LOGI("keypair generated, ts=%s", ts);
    return 0;
}

// 服务端公钥 base64 → 派生 AES key。返回 0；-1 无密钥对；-2 公钥非法。
int nb_compute_shared_secret(const char* serverPubB64) {
    if (serverPubB64 == nullptr) return -2;
    uint8_t sp[64];
    int n = b64_decode_auto(serverPubB64, sp, sizeof(sp));
    if (n != 32) {
        LOGE("shared: server pub decode len=%d (want 32)", n);
        return -2;
    }
    pthread_mutex_lock(&g_mu);
    if (!g_hasPriv) {
        pthread_mutex_unlock(&g_mu);
        LOGE("shared: no keypair, call nb_generate_keypair first");
        return -1;
    }
    tvl_x25519(g_shared, g_priv, sp);
    g_hasShared = true;
    pthread_mutex_unlock(&g_mu);
    LOGI("shared secret established");
    return 0;
}

// 明文 → nonce(12)||ct||tag(16)。out 容量需 >= plainLen+28。
// 返回 0；-1 未建立共享密钥；-2 随机数失败；-3 容量不足；-4 GCM 失败。
int nb_encrypt_raw(const uint8_t* plain, size_t plainLen,
                   uint8_t* out, size_t cap, size_t* outLen) {
    pthread_mutex_lock(&g_mu);
    if (!g_hasShared) {
        pthread_mutex_unlock(&g_mu);
        return -1;
    }
    uint8_t nonce[12];
    if (!rand_bytes(nonce, 12)) {
        pthread_mutex_unlock(&g_mu);
        return -2;
    }
    size_t need = 12 + plainLen + 16;
    if (cap < need) {
        pthread_mutex_unlock(&g_mu);
        return -3;
    }
    memcpy(out, nonce, 12);
    int rc = tvl_aes256_gcm_seal(g_shared, nonce, 12,
                                 plainLen > 0 ? plain : nullptr, plainLen,
                                 nullptr, 0, out + 12);
    pthread_mutex_unlock(&g_mu);
    if (rc != 0) {
        memset(out, 0, need);
        return -4;
    }
    if (outLen != nullptr) *outLen = need;
    return 0;
}

// nonce(12)||ct||tag(16) → 明文。out 容量需 >= blobLen-28。
// 返回 0；-1 长度不足（<28）；-2 未建立共享密钥；-3 tag 校验失败。
int nb_decrypt_raw(const uint8_t* blob, size_t blobLen,
                   uint8_t* out, size_t cap, size_t* outLen) {
    if (blob == nullptr || blobLen < 28) return -1;  // 12 nonce + 16 tag 最小 28
    pthread_mutex_lock(&g_mu);
    if (!g_hasShared) {
        pthread_mutex_unlock(&g_mu);
        return -2;
    }
    int rc = tvl_aes256_gcm_open(g_shared, blob, 12, blob + 12, blobLen - 12,
                                 nullptr, 0, out);
    pthread_mutex_unlock(&g_mu);
    if (rc == -1) return -3;
    if (rc != 0) return -4;
    if (outLen != nullptr) *outLen = blobLen - 12 - 16;
    return 0;
}

// base64 口径切换：0=StdEncoding(44,默认) 1=RawURLEncoding(43)。
void nb_set_base64_url(int urlMode) {
    pthread_mutex_lock(&g_mu);
    g_b64Url = (urlMode != 0);
    pthread_mutex_unlock(&g_mu);
    LOGI("base64 mode -> %s", g_b64Url ? "RawURLEncoding" : "StdEncoding");
}

int nb_session_ready(void) {
    pthread_mutex_lock(&g_mu);
    int r = (g_hasPriv && g_hasShared) ? 1 : 0;
    pthread_mutex_unlock(&g_mu);
    return r;
}

}  // extern "C"

// =============================================================
// JNI：com.tv.live.nbmovie.NbMovieCrypto 的 native 实现
// =============================================================

extern "C" JNIEXPORT jstring JNICALL
Java_com_tv_live_nbmovie_NbMovieCrypto_nativeGenerateKeyPair(JNIEnv* env, jclass) {
    char buf[160];
    int rc = nb_generate_keypair(buf, sizeof(buf));
    if (rc != 0) {
        LOGE("nativeGenerateKeyPair rc=%d", rc);
        return nullptr;
    }
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tv_live_nbmovie_NbMovieCrypto_nativeComputeSharedSecret(JNIEnv* env, jclass,
                                                                 jstring serverPubB64) {
    if (serverPubB64 == nullptr) return nullptr;
    const char* s = env->GetStringUTFChars(serverPubB64, nullptr);
    if (s == nullptr) return nullptr;
    int rc = nb_compute_shared_secret(s);
    env->ReleaseStringUTFChars(serverPubB64, s);
    if (rc != 0) {
        LOGE("nativeComputeSharedSecret rc=%d", rc);
        // 与 Go 侧语义对齐：非空串 = 错误原因
        return env->NewStringUTF(rc == -1 ? "no_keypair" : "bad_server_pub");
    }
    return env->NewStringUTF("");  // 空串 = 成功
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_nbmovie_NbMovieCrypto_nativeEncryptRaw(JNIEnv* env, jclass, jbyteArray plain) {
    if (plain == nullptr) return nullptr;
    jsize n = env->GetArrayLength(plain);
    uint8_t* in = (uint8_t*)malloc((size_t)(n > 0 ? n : 1));
    uint8_t* out = (uint8_t*)malloc((size_t)n + 28);
    if (in == nullptr || out == nullptr) {
        free(in); free(out);
        return nullptr;
    }
    if (n > 0) env->GetByteArrayRegion(plain, 0, n, (jbyte*)in);
    size_t outLen = 0;
    int rc = nb_encrypt_raw(n > 0 ? in : nullptr, (size_t)n, out, (size_t)n + 28, &outLen);
    jbyteArray r = nullptr;
    if (rc == 0) {
        r = env->NewByteArray((jsize)outLen);
        if (r != nullptr) env->SetByteArrayRegion(r, 0, (jsize)outLen, (const jbyte*)out);
    } else {
        LOGE("nativeEncryptRaw rc=%d", rc);
    }
    memset(in, 0, (size_t)(n > 0 ? n : 1));
    memset(out, 0, (size_t)n + 28);
    free(in);
    free(out);
    return r;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_nbmovie_NbMovieCrypto_nativeDecryptRaw(JNIEnv* env, jclass, jbyteArray blob) {
    if (blob == nullptr) return nullptr;
    jsize n = env->GetArrayLength(blob);
    if (n < 28) {
        LOGE("nativeDecryptRaw: blob %d < 28", n);
        return nullptr;
    }
    uint8_t* in = (uint8_t*)malloc((size_t)n);
    uint8_t* out = (uint8_t*)malloc((size_t)n);  // 明文 <= n-28
    if (in == nullptr || out == nullptr) {
        free(in); free(out);
        return nullptr;
    }
    env->GetByteArrayRegion(blob, 0, n, (jbyte*)in);
    size_t outLen = 0;
    int rc = nb_decrypt_raw(in, (size_t)n, out, (size_t)n, &outLen);
    jbyteArray r = nullptr;
    if (rc == 0) {
        r = env->NewByteArray((jsize)outLen);
        if (r != nullptr) env->SetByteArrayRegion(r, 0, (jsize)outLen, (const jbyte*)out);
    } else {
        LOGE("nativeDecryptRaw rc=%d", rc);
    }
    memset(in, 0, (size_t)n);
    memset(out, 0, (size_t)n);
    free(in);
    free(out);
    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tv_live_nbmovie_NbMovieCrypto_nativeSetBase64Url(JNIEnv* env, jclass, jboolean url) {
    nb_set_base64_url(url ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tv_live_nbmovie_NbMovieCrypto_nativeSessionReady(JNIEnv* env, jclass) {
    return nb_session_ready() ? JNI_TRUE : JNI_FALSE;
}
