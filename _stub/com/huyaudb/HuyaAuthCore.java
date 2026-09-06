package com.huyaudb;

/**
 * HuyaAuthCore 纯 Java no-op stub（体积优化专项 2026-08-28）。
 *
 * 原类为 huyaudbunify aar 内的 native 桥（static 块 System.loadLibrary("udbauthunify")），
 * libudbauthunify.so 占 1.27MB/ABI。全树仅 HuyaAuthMgr 引用本类：
 *   - HuyaAuthMgr.init()   → getInstance().init()（try-catch Exception 包裹）
 *   - HuyaAuthMgr.unInit() → getInstance().unInit()
 *   - HuyaAuthMgr.sendMessage(long, byte[]) → getInstance().sendMsg(...)（try-catch Exception，null 安全）
 * HuyaAuth.init()（唯一触发 HuyaAuthMgr.init 的入口）在全反编译树中零调用方。
 *
 * 本 stub 移除 loadLibrary 与全部 native 方法，签名与原类完全一致：
 * 即使类被意外初始化也不会抛 UnsatisfiedLinkError/ExceptionInInitializerError。
 * sendMsg 返回 new byte[0] → HuyaAuthMgr.sendMessage 收到 ""，调用方（登录/证书 H5 流程）本应用不可达。
 */
public class HuyaAuthCore {

    private static volatile com.huyaudb.HuyaAuthCore mHuyaAuthCore;
    private com.huyaudbunify.inter.IIHuyaAuthCoreCallBack mAuthCorecallBack;

    public static com.huyaudb.HuyaAuthCore getInstance() {
        if (mHuyaAuthCore == null) {
            synchronized (com.huyaudb.HuyaAuthCore.class) {
                if (mHuyaAuthCore == null) {
                    mHuyaAuthCore = new com.huyaudb.HuyaAuthCore();
                }
            }
        }
        return mHuyaAuthCore;
    }

    public void setAuthCorecallBack(com.huyaudbunify.inter.IIHuyaAuthCoreCallBack iIHuyaAuthCoreCallBack) {
        this.mAuthCorecallBack = iIHuyaAuthCoreCallBack;
    }

    /** 原 native。stub：no-op（本应用无登录链路，永不触达） */
    public void init() {
    }

    /** 原 native。stub：no-op */
    public void unInit() {
    }

    /** 原 native。stub：返回空应答（HuyaAuthMgr.sendMessage null 安全 → 返回 ""） */
    public byte[] sendMsg(long j, byte[] bArr) {
        return new byte[0];
    }

    /** 原 native。stub：no-op */
    public void receiveNet(byte[] bArr, int i, int i2, int i3) {
    }

    public void sendNet(long j, int i, byte[] bArr) {
        if (this.mAuthCorecallBack != null) {
            this.mAuthCorecallBack.sendNet(j, i, bArr);
        }
    }

    public void receiveMsg(long j, byte[] bArr) {
        if (this.mAuthCorecallBack != null) {
            this.mAuthCorecallBack.receiveMsg(j, bArr);
        }
    }

    public void log(byte[] bArr) {
        if (this.mAuthCorecallBack != null) {
            this.mAuthCorecallBack.log(new String(bArr));
        }
    }

    // 原 static { System.loadLibrary("udbauthunify"); } —— stub 不再加载任何 so
}
