package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

public class GestureManager {

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long DEBOUNCE_DELAY_MS = 500;
    private boolean isGestureLocked = false;

    private boolean isLongPressTriggered = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                if (activity.isInCatchUpMode()) {
                    return;
                }
                isLongPressTriggered = true;
                mainHandler.removeCallbacksAndMessages(null);
                mainHandler.postDelayed(() -> isLongPressTriggered = false, DEBOUNCE_DELAY_MS);

                activity.openSettings();
            }

            @Override
            public void onMenu() {
                if (activity.isInCatchUpMode()) {
                    activity.showExoController();
                }
            }

            @Override
            public void onPrevChannel() {
                if (isLongPressTriggered) return;

                if (!isGestureLocked) {
                    isGestureLocked = true;
                    boolean isReverse = activity.isChannelReverse();
                    if (isReverse) {
                        activity.playNext();
                    } else {
                        activity.playPrev();
                    }
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }

            @Override
            public void onNextChannel() {
                if (isLongPressTriggered) return;

                if (!isGestureLocked) {
                    isGestureLocked = true;
                    boolean isReverse = activity.isChannelReverse();
                    if (isReverse) {
                        activity.playPrev();
                    } else {
                        activity.playNext();
                    }
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }
        });
    }
}
