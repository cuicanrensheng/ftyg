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
                // 单击：源7 回看模式唤起频道/集数面板（绕开回看守卫，见 MainActivity.handleSingleTap），
                // 其它源维持原有 togglePanel 逻辑
                activity.handleSingleTap();
            }

            @Override
            public void onLongOk() {
                // 回看模式：长按不再唤起设置面板（源7 集数回看同样走此路径）
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
                // 回看模式：双击唤起播放器控制栏（进度条）
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
