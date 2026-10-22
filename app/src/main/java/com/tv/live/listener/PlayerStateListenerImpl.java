package com.tv.live.listener;

import android.content.Context;
import com.tv.live.MyApplication;
import com.tv.live.TVPlayerManager;
import com.tv.live.util.LogBridge;
import com.tv.live.util.LogBridge.LogCollector;

public class PlayerStateListenerImpl implements TVPlayerManager.OnPlayStateListener {

    private static final String TAG = "PlayerStateListenerImpl";

    private final Context context;

    private String currentChannelName = "";

    /** 【2026-10-17 启动图】「本次启动首次播放就绪」回调，供 MainActivity 淡出启动覆盖层；可为 null。 */
    private Runnable onReadyCallback;
    /** 【2026-10-17 启动图】仅通知一次（首次就绪即代表界面/播放已可展示）。 */
    private boolean readyNotified = false;

    public PlayerStateListenerImpl(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCurrentChannelName(String name) {
        this.currentChannelName = name;
    }

    /** 【2026-10-17 启动图】注册「首次播放就绪」回调（幂等，仅触发一次）。 */
    public void setOnReadyCallback(Runnable cb) {
        this.onReadyCallback = cb;
    }

    @Override
    public void onIdle() {

    }

    @Override
    public void onBuffering() {

    }

    @Override
    public void onPlayReady() {
        // 【2026-10-17 启动图】首次就绪 → 通知 UI 淡出启动覆盖层
        if (!readyNotified && onReadyCallback != null) {
            readyNotified = true;
            try {
                onReadyCallback.run();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPlayEnd() {

    }

    @Override
    public void onPlayError(String msg) {
        // 🎯【2026-09-08 卡顿诊断】把播放错误（含 TVPlayerManager 传入的
        // cause 链 + playUrl + 频道）打进 9527 日志，便于应用层侧定位。
        try {
            LogCollector.getInstance().warn(TAG, "[APP_PLAY_ERROR] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
