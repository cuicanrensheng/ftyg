// =============================================================
// tvl_crypto.h —— libtvlive_security.so 内部密码学原语（跨编译单元）
//
// 沿用 crypto2.cpp 既有的 tvl_* C 链接转发约定（见该文件末尾的
// tvl_aes256_expand / tvl_aes_encrypt_block）：-fvisibility=hidden 下
// 这些符号不进动态符号表，但同 .so 内跨编译单元链接不受影响。
//
// 使用者：nbmovie.cpp（4K影视协议层）、独立单测可执行文件。
// =============================================================
#ifndef TVLIVE_TVL_CRYPTO_H
#define TVLIVE_TVL_CRYPTO_H

#include <cstddef>
#include <cstdint>

extern "C" {

// SHA-256：out[32]
void tvl_sha256(const uint8_t* data, size_t len, uint8_t out[32]);

// HMAC-SHA256：out[32]
void tvl_hmac_sha256(const uint8_t* key, size_t keyLen,
                     const uint8_t* msg, size_t msgLen, uint8_t out[32]);

// AES-256 密钥扩展（rk 240B）
void tvl_aes256_expand(const uint8_t key[32], uint8_t rk[240]);

// AES-256 单分组加密
void tvl_aes_encrypt_block(const uint8_t in[16], uint8_t out[16], const uint8_t* rk);

// AES-256-GCM 加密。密文||16B tag 写入 out（容量 >= plainLen+16）。
// nonceLen 任意（12 时走标准 J0；其余走 GHASH 派生）。aad 可为 nullptr。
// 返回 0；参数错 -2。
int tvl_aes256_gcm_seal(const uint8_t key[32], const uint8_t* nonce, size_t nonceLen,
                        const uint8_t* plain, size_t plainLen,
                        const uint8_t* aad, size_t aadLen, uint8_t* out);

// AES-256-GCM 解密并验签。入参 ctTag = 密文||16B tag；验通过写入 out
//（容量 >= ctTagLen-16）。返回 0；tag 不符 -1；参数错 -2。
int tvl_aes256_gcm_open(const uint8_t key[32], const uint8_t* nonce, size_t nonceLen,
                        const uint8_t* ctTag, size_t ctTagLen,
                        const uint8_t* aad, size_t aadLen, uint8_t* out);

}  // extern "C"

#endif  // TVLIVE_TVL_CRYPTO_H
