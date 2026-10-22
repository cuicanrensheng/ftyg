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

// ------------------------------------------------------------------
// 【2026-10-17 第四批】源5 加密线路「服务端解析」端点密文化。
// 实证（2026-10-17 真机抓包 + 离线解密）：App 不本地解密这些 token，
// 而是原样转发给下列自带解析服务器，由服务端出直链（见 Java: VodLineResolver）。
// 均为第三方解析服务（非自有服务器），明文只留在本 .cpp，不进 dex。
// ------------------------------------------------------------------
/** id=18 · 解析: vertify.php（官方① BBA/deja- 线） */
OBF_DEF(K_VODP_VERTIFY, "http://49.233.180.127:880/vertify.php/?url=",           0x1e);
/** id=19 · 解析: jxxh.php（官方③ ZY-icial_/ZY-anime_ 线） */
OBF_DEF(K_VODP_JXXH,    "http://49.233.180.127:880/jxxh.php/?url=",              0x3f);
/** id=20 · 解析: zsyzx.php（官方⑥ yzx- 线） */
OBF_DEF(K_VODP_ZSYZX,   "http://49.233.180.127:880/zsyzx.php/?url=",             0x5a);
/** id=21 · 解析: 4kys.php（高清① 4kvm.top/play 页） */
OBF_DEF(K_VODP_4KYS,    "http://49.233.180.127:880/4kys.php/?url=",              0x7b);
/** id=22 · 解析: NBY 两段链第一段（官方②）。
 *  ⚠ 2026-10-17 实测修正：必须用抓包 Host 域名 api.nbyjson.top:7788（43.248.96.62 只是当时解析 IP，
 *  直连 IP:80 因虚拟主机不匹配返回 404）。 */
OBF_DEF(K_VODP_NBY,     "http://api.nbyjson.top:7788/api/?key=c8kz5yCrtpSR7Lz8wY&url=",  0x9d);
/** id=23 · 解析: coffee /api（官方⑤ co_ 线）。
 *  ⚠ 2026-10-17 实测修正：必须用抓包 Host 域名 coffee.1ljx.com:32000（43.248.102.96 只是当时解析 IP）。 */
OBF_DEF(K_VODP_CO,
        "http://coffee.1ljx.com:32000/api/?key=05224795-d7d1-4ea3-8ae8-c04b8f366d42&url=", 0xbe);
/** id=24 · 解析: 175.24.181.180:5000 /api/jiexi/common（Ace 线；Host 即 IP:5000） */
OBF_DEF(K_VODP_ACE,
        "http://175.24.181.180:5000/api/jiexi/common?Key=qRAFU8027p8kal2Jtv&url=", 0xd1);
/** id=25 · 解析: /jx（官方④ YKTX 线，48B Base64 token）。
 *  ⚠ 2026-10-17 实测修正：必须用抓包 Host 域名 jx.nbpicture.xyz（118.107.11.4 只是当时解析 IP）。 */
OBF_DEF(K_VODP_YKTX,    "http://jx.nbpicture.xyz/jx?url=",                       0xe4);

/** id=26 · 解析: dmm.php（动漫专线 ZY-anime_，2026-10-17 真机抓包确认；端口 7788 非 880）。
 *  返回两种形态：49.233.180.127:7788/cache/m3u8/<hash>.m3u8 或整集视频直链（xhscdn 伪装）。 */
OBF_DEF(K_VODP_DMM,     "http://49.233.180.127:7788/dmm.php/?url=",              0xc3);

/** id=27 · 腾讯 Bugly App ID（2026-10-22 I1-07 卫生项：从 dex 明文搬进 .so）。
 *  Bugly 初始化晚于 SecurityCore.init，取值失败仅退化为「不上报崩溃」，不影响功能。 */
OBF_DEF(K_BUGLY_APP_ID, "4a23007223",                                            0x5d);


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
        case 18: obf::decode_to(K_VODP_VERTIFY, buf, sizeof(buf)); break;
        case 19: obf::decode_to(K_VODP_JXXH,    buf, sizeof(buf)); break;
        case 20: obf::decode_to(K_VODP_ZSYZX,   buf, sizeof(buf)); break;
        case 21: obf::decode_to(K_VODP_4KYS,    buf, sizeof(buf)); break;
        case 22: obf::decode_to(K_VODP_NBY,     buf, sizeof(buf)); break;
        case 23: obf::decode_to(K_VODP_CO,      buf, sizeof(buf)); break;
        case 24: obf::decode_to(K_VODP_ACE,     buf, sizeof(buf)); break;
        case 25: obf::decode_to(K_VODP_YKTX,    buf, sizeof(buf)); break;
        case 26: obf::decode_to(K_VODP_DMM,     buf, sizeof(buf)); break;
        case 27: obf::decode_to(K_BUGLY_APP_ID, buf, sizeof(buf)); break;
        default: return nullptr;
    }
    // 【2026-10-22 I1-07】签名不匹配 → 把常量就地搅乱再返回：
    //   接口地址/密钥全变成无效串，App 自然起不来直播/点播，无需依赖可被 patch 的 killProcess。
    //   未绑定时 tvl_signature_poisoned() 恒为 0，行为与旧版一致。
    extern int tvl_signature_poisoned(void);
    if (tvl_signature_poisoned()) {
        for (size_t i = 0; buf[i] != 0 && i < sizeof(buf); ++i) {
            buf[i] = (char)((buf[i] ^ 0x5A) + 1);
        }
    }

    jstring s = env->NewStringUTF(buf);
    memset(buf, 0, sizeof(buf));   // 明文只活在这一帧
    return s;
}
