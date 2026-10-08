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

    public PlayerStateListenerImpl(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCurrentChannelName(String name) {
        this.currentChannelName = name;
    }

    @Override
    public void onIdle() {

    }

    @Override
    public void onBuffering() {

    }

    @Override
    public void onPlayReady() {
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
