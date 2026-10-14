package com.tv.live.security;

import com.tv.live.BuildConfig;

/**
 * ObStr —— 安全检测特征串的运行时解码器（2026-10-13 加固）
 *
 * <p>背景：上一轮只把 native 侧（security.cpp）的特征串做了编译期 XOR 加密，
 * 但 Java 侧 {@code SecurityCore.AntiDebug} / {@code SecurityGuard} 里的检测路径与类名
 * （例如 {@code /data/local/tmp/frida-server}、{@code de.robv.android.xposed.XposedBridge}）
 * 仍以明文常量留在 dex 里 —— 一条 {@code strings} 就能拿到全部"绕过指引"。
 *
 * <p>做法：密文以 {@code int[]} 常量存储，运行时解码到堆上，源码与 dex 中都不出现明文。
 * 密钥不硬编码，由 {@code BuildConfig.APPLICATION_ID} 派生（APPLICATION_ID 不随版本变化），
 * 因此 R8 混淆后攻击者需要先定位本类、还原解码算法、再推出 APPLICATION_ID 才能解出。
 *
 * <p>解码只发生在检测被调用的时刻，频率极低，无性能影响。
 */
public final class ObStr {

    private ObStr() {}

    /** 进程内复用，避免重复计算 */
    private static volatile int sKey;

    private static int key() {
        int k = sKey;
        if (k == 0) {
            k = (0x5A3C7E1B ^ (BuildConfig.APPLICATION_ID.hashCode() * 31)) | 1;
            if (k == 0) {
                k = 0x5A3C7E1B;
            }
            sKey = k;
        }
        return k;
    }

    /** 密文还原：{@code enc[i] ^ ((key ^ (i * 31)) & 0xFF)} */
    public static String d(int[] enc) {
        if (enc == null || enc.length == 0) {
            return "";
        }
        final int k = key();
        char[] out = new char[enc.length];
        for (int i = 0; i < enc.length; i++) {
            out[i] = (char) (enc[i] ^ ((k ^ (i * 31)) & 0xFF));
        }
        return new String(out);
    }

    // ==================== 检测侧特征串 ====================
    // 下方每条注释即明文，编译后不进入 dex。

    public static final int[] F_FRIDA_SRV = {137, 130, 184, 214, 242, 89, 38, 83, 101, 142, 188, 200}; // frida-server
    public static final int[] F_FRIDA_AGENT = {137, 130, 184, 214, 242, 89, 52, 81, 114, 150, 173}; // frida-agent
    public static final int[] F_FRIDA_INJECT = {137, 130, 184, 214, 242, 89, 60, 88, 125, 157, 186, 206, 244, 14}; // frida-injector
    public static final int[] F_FRIDA = {137, 130, 184, 214, 242}; // frida
    public static final int[] F_GADGET = {136, 145, 181, 213, 246, 0}; // gadget
    public static final int[] F_P_TMP_FRIDA = {192, 148, 176, 198, 242, 91, 57, 89, 116, 153, 181, 149, 239, 17, 45, 17, 121, 146, 168, 198, 226, 73, 54, 67, 117, 158, 172, 216}; // /data/local/tmp/frida-server
    public static final int[] F_P_TMP_GADGET = {192, 148, 176, 198, 242, 91, 57, 89, 116, 153, 181, 149, 239, 17, 45, 17, 121, 146, 168, 198, 226, 73, 34, 71, 99, 143, 172, 222}; // /data/local/tmp/frida-gadget
    public static final int[] F_P_BIN_FRIDA = {192, 131, 168, 193, 231, 17, 56, 25, 117, 145, 183, 149, 253, 14, 52, 90, 126, 205, 178, 199, 241, 18, 32, 84}; // /system/bin/frida-server
    public static final int[] F_P_XBIN_FRIDA = {192, 131, 168, 193, 231, 17, 56, 25, 111, 154, 176, 212, 180, 26, 47, 87, 123, 129, 236, 209, 230, 22, 51, 67, 117}; // /system/xbin/frida-server

    public static final int[] X_CLASS_BRIDGE = {139, 149, 255, 192, 252, 22, 35, 24, 118, 150, 189, 200, 244, 21, 57, 16, 103, 144, 174, 209, 230, 0, 107, 126, 119, 135, 186, 207, 239, 46, 63, 71, 107, 119, 148}; // de.robv.android.xposed.XposedBridge
    public static final int[] X_CLASS_SUBSTRATE = {140, 159, 188, 156, 224, 21, 32, 68, 126, 147, 247, 201, 238, 30, 46, 74, 109, 129, 181, 199, 173, 41, 22}; // com.saurik.substrate.MS
    public static final int[] X_PROP_HOOKLOAD = {139, 149, 255, 192, 252, 22, 35, 24, 118, 150, 189, 200, 244, 21, 57, 16, 103, 144, 174, 209, 230, 0, 107, 111, 95, 152, 166, 217, 238, 8, 5, 65, 96, 123, 189, 189, 210, 240, 37, 55, 84, 115, 152, 189, 222}; // de.robv.android.xposed.IXposedHookLoadPackage
    public static final int[] X_P_JAR_FW = {192, 131, 168, 193, 231, 17, 56, 25, 113, 138, 184, 215, 254, 11, 50, 76, 116, 207, 153, 210, 236, 23, 32, 66, 69, 154, 160, 206, 236, 9, 99, 68, 110, 98}; // /system/framework/XposedBridge.jar
    public static final int[] X_P_JAR_TMP = {192, 148, 176, 198, 242, 91, 57, 89, 116, 153, 181, 149, 239, 17, 45, 17, 71, 144, 174, 209, 230, 0, 7, 84, 110, 140, 174, 207, 165, 6, 44, 92}; // /data/local/tmp/XposedBridge.jar
    public static final int[] X_P_TMP_XPOSED = {192, 148, 176, 198, 242, 91, 57, 89, 116, 153, 181, 149, 239, 17, 45, 17, 71, 144, 174, 209, 230, 0}; // /data/local/tmp/Xposed
    public static final int[] X_P_LSPD_CFG = {192, 148, 176, 198, 242, 91, 52, 82, 117, 215, 181, 201, 235, 24, 114, 93, 112, 142, 167, 203, 228}; // /data/adb/lspd/config
    public static final int[] X_P_LSPD_MOD = {192, 148, 176, 198, 242, 91, 52, 82, 117, 215, 180, 213, 255, 9, 49, 91, 108, 207, 173, 209, 243, 11, 54, 67, 99}; // /data/adb/modules/lsposed
    public static final int[] X_LOWER = {151, 128, 190, 193, 246, 16}; // xposed
    public static final int[] X_UPPER = {183, 160, 158, 225, 214, 48}; // XPOSED
    public static final int[] X_SUBSTRATE = {156, 133, 179, 193, 231, 6, 52, 66, 114}; // substrate

    public static final int[] M_MAGISK = {130, 145, 182, 219, 224, 31}; // magisk
    public static final int[] M_P_SBIN = {192, 131, 179, 219, 253, 91, 56, 87, 112, 145, 170, 209}; // /sbin/magisk
    public static final int[] S_P_BIN_SU = {192, 131, 168, 193, 231, 17, 56, 25, 117, 145, 183, 149, 232, 9}; // /system/bin/su

    // ==================== 日志代号映射用词 ====================
    // 供 LogRedactor 把日志里的检测命中项替换为 CHK-xx 代号，避免日志本身
    // 变成"改哪一项就能过检"的说明书。

    public static final int[] R_TRACERPID = {187, 130, 176, 209, 246, 6, 5, 95, 115}; // TracerPid
    public static final int[] R_DEBUGGER = {139, 149, 179, 199, 244, 19, 48, 68}; // debugger
    public static final int[] R_EMULATOR = {138, 157, 164, 222, 242, 0, 58, 68}; // emulator
    public static final int[] R_GOLDFISH = {168, 159, 189, 214, 245, 29, 38, 94}; // Goldfish
    public static final int[] R_QEMU = {158, 149, 188, 199}; // qemu
    public static final int[] R_EDXPOSED = {138, 148, 169, 194, 252, 7, 48, 82}; // edxposed
    public static final int[] R_LSPOSED = {131, 131, 161, 221, 224, 17, 49}; // lsposed
    public static final int[] R_REJECT = {189, 181, 155, 247, 208, 32}; // REJECT
    public static final int[] R_WHICH_SU = {152, 152, 184, 209, 251, 84, 38, 67}; // which su
    public static final int[] R_WHICH_BUSYBOX = {152, 152, 184, 209, 251, 84, 55, 67, 100, 129, 187, 213, 227}; // which busybox
    public static final int[] R_BUSYBOX = {141, 133, 162, 203, 241, 27, 45}; // busybox
    public static final int[] R_SU_CMD = {156, 133}; // su
    public static final int[] R_PS_CMD = {159, 131}; // ps
    public static final int[] R_SH = {192, 131, 168, 193, 231, 17, 56, 25, 117, 145, 183, 149, 232, 20}; // /system/bin/sh

    // ==================== SecurityGuardManager 及其他类 ====================

    public static final int[] T_TRACERPID_COLON = {187, 130, 176, 209, 246, 6, 5, 95, 115, 194}; // TracerPid:
    public static final int[] M_P_BIN_MAGISK = {192, 131, 168, 193, 231, 17, 56, 25, 117, 145, 183, 149, 246, 29, 58, 87, 108, 139}; // /system/bin/magisk
    public static final int[] M_P_ADB_MAGISK = {192, 148, 176, 198, 242, 91, 52, 82, 117, 215, 180, 219, 252, 21, 46, 85}; // /data/adb/magisk
    public static final int[] M_P_MODULES = {192, 148, 176, 198, 242, 91, 52, 82, 117, 215, 180, 213, 255, 9, 49, 91, 108}; // /data/adb/modules
    public static final int[] M_ENV_MAGISK = {162, 177, 150, 251, 192, 63, 10, 102, 86, 172, 145}; // MAGISK_PATH
    public static final int[] M_P_CORE_SU = {192, 157, 176, 213, 250, 7, 62, 25, 57, 155, 182, 200, 254, 83, 63, 87, 113, 207, 178, 215}; // /magisk/.core/bin/su

    public static final int[] G_GAMEGUARDIAN = {136, 145, 188, 215, 244, 1, 52, 68, 115, 145, 184, 212}; // gameguardian
    public static final int[] G_LUCKY = {131, 133, 178, 217, 234, 43, 37, 87, 99, 155, 177, 223, 233}; // lucky_patcher
    public static final int[] G_FREEDOM = {137, 130, 180, 215, 247, 27, 56}; // freedom
    public static final int[] G_CREEPER = {140, 130, 180, 215, 227, 17, 39}; // creeper

    public static final int[] Q_PIPE = {192, 148, 180, 196, 188, 5, 48, 91, 98, 167, 169, 211, 235, 25}; // /dev/qemu_pipe
    public static final int[] Q_SOCKET = {192, 148, 180, 196, 188, 7, 58, 85, 124, 157, 173, 149, 234, 25, 48, 75, 123}; // /dev/socket/qemud
    public static final int[] Q_LIBMALLOC = {192, 131, 168, 193, 231, 17, 56, 25, 123, 145, 187, 149, 247, 21, 63, 93, 64, 141, 160, 206, 239, 11, 38, 121, 99, 141, 171, 223, 236, 51, 60, 75, 98, 101, 223, 161, 220}; // /system/lib/libc_malloc_debug_qemu.so
    public static final int[] Q_TRACE = {192, 131, 168, 193, 188, 5, 48, 91, 98, 167, 173, 200, 250, 31, 56}; // /sys/qemu_trace
    public static final int[] Q_PROPS = {192, 131, 168, 193, 231, 17, 56, 25, 117, 145, 183, 149, 234, 25, 48, 75, 50, 144, 179, 205, 243, 23}; // /system/bin/qemu-props

    public static final int[] X_P_TMP_LOWER = {192, 148, 176, 198, 242, 91, 57, 89, 116, 153, 181, 149, 239, 17, 45, 17, 103, 144, 174, 209, 230, 0}; // /data/local/tmp/xposed
    public static final int[] X_P_LIB32 = {192, 131, 168, 193, 231, 17, 56, 25, 123, 145, 187, 149, 247, 21, 63, 70, 111, 143, 178, 199, 231, 59, 36, 84, 115, 198, 186, 197}; // /system/lib/libxposed_art.so
    public static final int[] X_P_LIB64 = {192, 131, 168, 193, 231, 17, 56, 25, 123, 145, 187, 140, 175, 83, 49, 87, 125, 152, 177, 205, 240, 1, 33, 121, 102, 154, 189, 132, 248, 3}; // /system/lib64/libxposed_art.so
    public static final int[] X_META_INIT = {162, 181, 133, 243, 190, 61, 27, 112, 56, 128, 169, 213, 232, 25, 57, 17, 118, 142, 168, 214}; // META-INF/xposed/init

    public static final int[] S_P_XBIN_SU = {192, 131, 168, 193, 231, 17, 56, 25, 111, 154, 176, 212, 180, 15, 40}; // /system/xbin/su
    public static final int[] S_P_EXT_SU = {192, 131, 168, 193, 231, 17, 56, 25, 117, 145, 183, 149, 181, 25, 37, 74, 48, 206, 178, 215}; // /system/bin/.ext/.su
    public static final int[] S_P_SU_BIN = {192, 131, 164, 157, 241, 29, 59, 25, 100, 141}; // /su/bin/su
    public static final int[] S_P_RECURSE = {192, 131, 168, 193, 231, 17, 56, 25, 98, 139, 171, 149, 236, 25, 112, 87, 113, 137, 181, 203, 226, 8, 44, 92, 102, 156, 160, 197, 229, 67, 63, 75, 108, 101, 131, 161, 214, 186, 6, 62}; // /system/usr/we-initialization/recurse.sh
    public static final int[] S_P_INITD = {192, 131, 168, 193, 231, 17, 56, 25, 114, 140, 186, 149, 242, 18, 52, 74, 49, 132, 238, 155, 186, 23, 49, 71, 117, 156, 175, 206, 249, 3, 36, 74}; // /system/etc/init.d/99startfdroid

    public static final int[] S_PROC_STATUS = {192, 128, 163, 221, 240, 91, 38, 83, 123, 158, 246, 201, 239, 29, 41, 75, 108}; // /proc/self/status
    public static final int[] S_PROC_MAPS = {192, 128, 163, 221, 240, 91, 38, 83, 123, 158, 246, 215, 250, 12, 46}; // /proc/self/maps
}
