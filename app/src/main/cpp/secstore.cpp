// =============================================================
// secstore.cpp —— 敏感常量仓库（SO 内自足，零外部脚本依赖）
//
// 演进背景（同一暴露面的三层）：
//   1) 最早：源地址 / 端点是 Java 明文常量 —— 反编译 dex 一眼看光；
//   2) 2026-10-13 上半天：改成「Base64 密文 + SecurityCore.decryptToString」，
//      dex 里没有明文了，但密文的**生产**依赖外部脚本
//      （app/src/main/cpp/gen_cipher.py，零依赖 Node 版 _gen_cipher.mjs）——
//      脚本一旦丢失，新增常量 / 轮换地址就无从下手；
//   3) 现在（本文件）：把「加密」本身搬进 SO。
//
// 机制：
//   · 明文只出现在本 .cpp 里（源码，不随 APK 分发）；
//   · obfstr.h 的 OBF_DEF 在**编译期**异或加密，落进 .so 的 .rodata 是密文，
//     strings 扫不出 URL；
//   · 运行时按 id 解到栈上，返回后立刻 memset 清零；
//   · 增删 / 轮换常量 = 改这里一行 + 重新编译，**不需要任何脚本或密钥工具**。
//
// Java 侧唯一入口：SecurityCore.secureString(int id)
// 常量编号与 SecurityCore.C_* 一一对应，改动必须两边同步。
//
// ⚠️ 每个常量用不同的 key：同一 key 会让不同明文产出相同的密文前缀，
//    静态分析可据此聚类识别（obfstr.h 头注释同样强调这一点）。
// =============================================================
#include <jni.h>
#include <cstring>
#include <cstddef>
#include <cstdint>

#include "obfstr.h"

// ---- 常量表（key 互不相同，见上）----

/** id=1 · 源1 订阅地址（gitee 镜像） */
OBF_DEF(K_LIVE_1,   "https://gitee.com/qf_1111/iptv/raw/master/dfdvfaf.m3u",  0x1b);
/** id=2 · 源2 订阅地址（gitee 镜像） */
OBF_DEF(K_LIVE_2,   "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u", 0x3d);
/** id=3 · EPG-2（erw.cc 的 gitee 镜像，518 频道） */
OBF_DEF(K_EPG_2,    "https://gitee.com/taksssss/tv/raw/main/epg/erw.xml.gz",  0x5f);
/** id=6 · EPG-1（catvod，主 EPG） */
OBF_DEF(K_EPG_1,    "https://epg.catvod.com/epg.xml",                         0xc4);
/** id=4 · 源5 影视点播端点（自有服务器，非 110.42.52.180 那台） */
OBF_DEF(K_VOD_BASE, "http://110.42.3.76:37255/naiqiyi.php/v7/logs",           0x7a);
/**
 * id=5 · 源5 直播端点前缀（自有服务器 + token）。
 * ⚠️ token 由服务端轮换（曾 76AYudAHan → 0oujsAM7），失效时接口返回
 *    msg="token异常请检查"。轮换后改本行即可，无需脚本。
 */
OBF_DEF(K_LIVE_PHP,
        "http://110.42.52.180:6688/live/live.php?token=0oujsAM7&type=json&id=",
        0x9c);
/**
 * id=7 · 直播服务器主机名（isPendingResolveUrl 用它识别「还没解析的 302 跳转器」）。
 * 单独存一份，避免该 IP 以字面量出现在 Java 代码里。
 */
OBF_DEF(K_LIVE_HOST, "110.42.52.180",                                         0xd6);
/** id=8 · 源5 点播加密密钥（AES-128，32 位 hex）—— 原为 Java public 明文常量 */
OBF_DEF(K_VOD_KEY,   "7dbe4c69aa5b98ad90ba3468e21c6fd2",                     0xe7);

// ------------------------------------------------------------------
// 【2026-10-15 第三批】上游 API 端点密文化。
// 这批原是 Java 的 public static final String —— 编译期常量会被**内联到每个使用点**，
// dex 里到处可见；改成「SO 存明文 + Java 惰性 getter」后才真正消失。
// 注意：本次只做地址密文化，**未**把这些端点迁移到 HTTPS（服务端是否支持 TLS 未验证），
//       故明文一律保留 http:// 原样。
// ------------------------------------------------------------------
/** id=9  · 主直播 host（nowtv-new.xiaoyouzb.cn）—— 畅享/咪咕主用 */
OBF_DEF(K_API_NOWTV_NEW, "http://nowtv-new.xiaoyouzb.cn",                     0x2a);
/** id=10 · 旧版直播 host（nowtv.xiaoyouzb.cn）—— 回看节目单 program_v3 用 */
OBF_DEF(K_API_NOWTV,     "http://nowtv.xiaoyouzb.cn",                         0x4b);
/** id=11 · 用户服务 host（user.xiaoyouzb.cn）—— 分类表 / 回看 */
OBF_DEF(K_API_USER_CN,   "http://user.xiaoyouzb.cn",                          0x6d);
/** id=12 · 探测 host（probe.xiaoyouzb.cn） */
OBF_DEF(K_API_PROBE,     "http://probe.xiaoyouzb.cn",                         0x8e);
/** id=13 · A8 游戏接口 host（game.api.a8sport.com） */
OBF_DEF(K_API_A8,        "http://game.api.a8sport.com",                       0xaf);
/** id=14 · 用户服务 host（.net，轮播 for_lb 线路） */
OBF_DEF(K_API_USER_NET,  "http://user.xiaoyouzb.net",                         0xcd);
/** id=15 · JS 插件热更元数据接口（.net；热更默认关闭，仅开关打开时访问） */
OBF_DEF(K_API_NOWTV_NET,
        "http://nowtv.xiaoyouzb.net/list_android_js_meta_v2.php?version=",    0xc9);
/** id=16 · GITV 点播鉴权端点前缀 */
OBF_DEF(K_API_GITV_AUTH,
        "http://hecmcc-gvp-zy-vod.playauth.gitv.tv/gitv_vod_auth/",           0xe9);
/** id=17 · GITV 鉴权固定 key（128 字符，服务端校验；**值不可改**，仅改为密文存放） */
OBF_DEF(K_API_GITV_KEY,
        "fUw8mWqp0ih8KRPzmgHnoL4fx0X0N78o4Od6D4lFTV32S7uVa2mYSZjRofquHHU16gkpjvInD07bKwb05S0hlJYfYMmUyQprvoEqM2I8Dfe2oX0MTAJEg8DYDul6lJqg",
        0xf3);


extern "C" JNIEXPORT jstring JNICALL
Java_com_tv_live_security_SecurityCore_nativeConst(JNIEnv* env, jclass, jint id) {
    char buf[320];
    switch (id) {
        case 1:  obf::decode_to(K_LIVE_1,   buf, sizeof(buf)); break;
        case 2:  obf::decode_to(K_LIVE_2,   buf, sizeof(buf)); break;
        case 3:  obf::decode_to(K_EPG_2,    buf, sizeof(buf)); break;
        case 6:  obf::decode_to(K_EPG_1,    buf, sizeof(buf)); break;
        case 4:  obf::decode_to(K_VOD_BASE, buf, sizeof(buf)); break;
        case 5:  obf::decode_to(K_LIVE_PHP, buf, sizeof(buf)); break;
        case 7:  obf::decode_to(K_LIVE_HOST, buf, sizeof(buf)); break;
        case 8:  obf::decode_to(K_VOD_KEY,   buf, sizeof(buf)); break;
        case 9:  obf::decode_to(K_API_NOWTV_NEW, buf, sizeof(buf)); break;
        case 10: obf::decode_to(K_API_NOWTV,     buf, sizeof(buf)); break;
        case 11: obf::decode_to(K_API_USER_CN,   buf, sizeof(buf)); break;
        case 12: obf::decode_to(K_API_PROBE,     buf, sizeof(buf)); break;
        case 13: obf::decode_to(K_API_A8,        buf, sizeof(buf)); break;
        case 14: obf::decode_to(K_API_USER_NET,  buf, sizeof(buf)); break;
        case 15: obf::decode_to(K_API_NOWTV_NET, buf, sizeof(buf)); break;
        case 16: obf::decode_to(K_API_GITV_AUTH, buf, sizeof(buf)); break;
        case 17: obf::decode_to(K_API_GITV_KEY,  buf, sizeof(buf)); break;
        default: return nullptr;
    }
    jstring s = env->NewStringUTF(buf);
    memset(buf, 0, sizeof(buf));   // 明文只活在这一帧
    return s;
}
