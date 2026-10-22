// =============================================================
// x25519.h —— X25519 (RFC 7748) 标量乘内部接口
//
// 供同库其他编译单元（nbmovie.cpp 协议层、单测）使用；
// Java 侧另有 SecurityCore.nativeX25519 直达入口（见 x25519.cpp）。
// =============================================================
#ifndef TVLIVE_X25519_H
#define TVLIVE_X25519_H

#include <cstdint>

// out = X25519(scalar, u)。scalar 内部按 RFC 7748 钳制
// （k[0]&=248; k[31]&=127; k[31]|=64），u 高位屏蔽，与
// golang.org/x/crypto/curve25519.X25519 行为一致。
extern "C" void tvl_x25519(uint8_t out[32], const uint8_t scalar[32], const uint8_t u[32]);

#endif  // TVLIVE_X25519_H
