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

    /**
     * 【2026-10-22 L1-15】具名 Runnable 字段：原 onLongOk 用
     * {@code removeCallbacksAndMessages(null)} 清场，会把同 handler 上 onPrev/onNext 挂的
     * 「isGestureLocked 复位」一并清掉 → 手势锁水不释放，之后上下滑永久失效。
     * 改为对具名 Runnable 精确 remove/post，两条复位互不干扰。
     */
    private final Runnable mUnlockGestureRunnable = () -> isGestureLocked = false;
    private final Runnable mClearLongPressRunnable = () -> isLongPressTriggered = false;

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
                // 【2026-10-22 L1-15】只清自己的长按复位（勿再 removeCallbacksAndMessages(null)）
                mainHandler.removeCallbacks(mClearLongPressRunnable);
                mainHandler.postDelayed(mClearLongPressRunnable, DEBOUNCE_DELAY_MS);

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
                    // 【2026-10-22 L1-15】具名 Runnable：重复触发时先摘旧复位再挂新的
                    mainHandler.removeCallbacks(mUnlockGestureRunnable);
                    mainHandler.postDelayed(mUnlockGestureRunnable, DEBOUNCE_DELAY_MS);
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
                    // 【2026-10-22 L1-15】具名 Runnable：同上
                    mainHandler.removeCallbacks(mUnlockGestureRunnable);
                    mainHandler.postDelayed(mUnlockGestureRunnable, DEBOUNCE_DELAY_MS);
                }
            }
        });
    }
}
