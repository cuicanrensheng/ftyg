package com.tv.live.manager;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.media3.ui.PlayerView;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

@SuppressLint("UnsafeOptInUsageError")
public class PlayerControlManager {

    private final MainActivity activity;
    private final InfoDisplayManager infoDisplayManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GestureManager gestureManager;
    private boolean isControllerShowing = false;

    private final Runnable hideControllerRunnable = new Runnable() {
        @Override
        public void run() {
            PlayerView currentPlayerView = activity.getPlayerView();
            if (currentPlayerView != null && isControllerShowing) {
                hideExoController();
            }
        }
    };

    public PlayerControlManager(MainActivity activity,
                                 GestureManager gestureManager,
                                 InfoDisplayManager infoDisplayManager) {
        this.activity = activity;
        this.gestureManager = gestureManager;
        this.infoDisplayManager = infoDisplayManager;
    }

    public void updateGestureManager(GestureManager newGestureManager) {
        this.gestureManager = newGestureManager;
    }

    public boolean isControllerShowing() {
        return isControllerShowing;
    }

    public void showExoController() {
        if (!activity.isInCatchUpMode()) return;

        PlayerView currentPlayerView = activity.getPlayerView();
        if (currentPlayerView == null) return;

        if (activity.getPipManager() != null && activity.getPipManager().isInPipMode()) {
            return;
        }

        mainHandler.removeCallbacks(hideControllerRunnable);

        if (activity.getTouchListener() != null) {
            currentPlayerView.setOnTouchListener(null);
        }

        currentPlayerView.setUseController(true);
        currentPlayerView.showController();
        isControllerShowing = true;
        mainHandler.postDelayed(hideControllerRunnable, 5000);

        if (infoDisplayManager != null) {
            infoDisplayManager.hideInfoBar();
        }
    }

    public void hideExoController() {
        PlayerView currentPlayerView = activity.getPlayerView();
        if (currentPlayerView == null) return;

        mainHandler.removeCallbacks(hideControllerRunnable);

        currentPlayerView.hideController();
        currentPlayerView.setUseController(false);
        isControllerShowing = false;

        if (activity.getTouchListener() != null) {
            if (gestureManager != null) {
                final PlayerGestureHelper newGestureHelper = gestureManager.create();
                activity.getTouchListener().updateGestureHelper(newGestureHelper);
            }
            currentPlayerView.setOnTouchListener(activity.getTouchListener());
        }
    }

    public void onResume() {
        if (activity.getPipManager() != null && activity.getPipManager().isInPipMode()) {
            PlayerView currentPlayerView = activity.getPlayerView();
            if (currentPlayerView != null) {
                currentPlayerView.setUseController(false);
                currentPlayerView.hideController();
                isControllerShowing = false;
            }
            return;
        }
        hideExoController();
    }

    public void onOpenSettings() {
        hideExoController();
        PlayerView currentPlayerView = activity.getPlayerView();
        if (currentPlayerView != null) {
            currentPlayerView.setUseController(false);
        }
    }

    public void onSettingsClosed() {
        hideExoController();
    }

    /** 每次前进/后退的步长（2026-09-14 由 10s 提到 15s，配合 100ms 连发 → 约 150 倍速） */
    private static final long SEEK_STEP_MS = 15_000;

    /** 预览目标位置（ms）；>= 0 表示正在预览中（尚未提交 seek） */
    private long previewSeekMs = -1;
    private SeekPreviewController seekPreview;

    public void setSeekPreviewController(SeekPreviewController controller) {
        this.seekPreview = controller;
    }

    /** 是否正在预览（长按/滑动中，尚未真正 seek） */
    public boolean isSeekPreviewing() {
        return previewSeekMs >= 0;
    }

    public long getPreviewSeekMs() {
        return previewSeekMs;
    }

    /**
     * 单步调整预览位置（<b>不立即 seek</b>）。
     *
     * <p><b>修复背景（2026-09-14）</b>：旧实现每次都
     * {@code getCurrentPosition()} 后立刻 {@code seekTo()}，而上一次 seek 未完成时
     * 该值不会变化，导致：
     * <ul>
     *   <li>位置<b>完全不累积</b> —— 长按 5 秒只前进 10 秒；</li>
     *   <li>每次 seek 都<b>打断上一次缓冲</b> —— 网络流反复重新定位，极慢。</li>
     * </ul>
     * 现在改为累积 {@link #previewSeekMs} 并即时刷新浮层（零延迟、零网络开销），
     * 松手时才由 {@link #commitSeek()} 用最终位置做<b>一次</b> seek。
     */
    public void seekStep(boolean forward) {
        MainActivity act = activity;
        if (act == null || !act.isInCatchUpMode()) return;
        if (act.getPlayerManager() == null) return;
        long dur = act.getPlayerManager().getDuration();
        if (dur <= 0) return;

        long base = (previewSeekMs >= 0) ? previewSeekMs
                                         : act.getPlayerManager().getCurrentPosition();
        long target = forward ? Math.min(base + SEEK_STEP_MS, dur)
                              : Math.max(base - SEEK_STEP_MS, 0);
        previewSeekMs = target;

        // 即时反馈：只更新浮层，不碰播放器
        if (seekPreview != null && seekPreview.isReady()) {
            seekPreview.show(target, dur, forward);
        }
        showExoController();
    }

    /** 单步快进（现在只更新预览，松手才 seek） */
    public void seekForward() {
        seekStep(true);
    }

    /** 单步快退（现在只更新预览，松手才 seek） */
    public void seekBackward() {
        seekStep(false);
    }

    /**
     * 提交预览位置 —— 松手时调用，全程只做<b>一次</b> seek。
     */
    public void commitSeek() {
        long target = previewSeekMs;
        previewSeekMs = -1;
        if (target < 0) {
            return;
        }
        MainActivity act = activity;
        if (act != null && act.getPlayerManager() != null) {
            act.getPlayerManager().seekTo(target);
        }
        if (seekPreview != null && seekPreview.isReady()) {
            seekPreview.hideDelayed(1500);
        }
        mainHandler.removeCallbacks(hideControllerRunnable);
        mainHandler.postDelayed(hideControllerRunnable, 5000);
    }

    /** 丢弃预览（退出回看 / 切台时调用） */
    public void cancelSeekPreview() {
        previewSeekMs = -1;
        if (seekPreview != null) {
            seekPreview.hide();
        }
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
    }
}
