package com.tv.live.listener;

import android.content.Context;
import com.tv.live.TVPlayerManager;

public class PlayerStateListenerImpl implements TVPlayerManager.OnPlayStateListener {

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

    }
}
