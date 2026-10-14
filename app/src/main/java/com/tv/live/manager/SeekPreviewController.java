package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 回看进度预览浮层。
 *
 * <p><b>背景（2026-09-14 体验修复）</b>：
 * 原先长按左右键是「每 200ms 真 seek 一次」，而 seek 发起后
 * {@code getCurrentPosition()} 仍返回旧值 → <b>位置完全不累积</b>
 * （长按 5 秒只前进 10 秒），且每次 seek 都打断上一次的缓冲 → 极慢、进度条不跟手。
 *
 * <p><b>现在</b>：长按/滑动期间<b>只更新本浮层的"目标位置"</b>（即时跟手、零网络开销），
 * 松手时才用最终位置<b>一次性 seek</b>（只缓冲一次）。
 */
public class SeekPreviewController {

    /** 默认自动隐藏延时（ms） */
    private static final long AUTO_HIDE_MS = 1200;

    private final View panel;
    private final TextView tvAction;
    private final TextView tvTime;
    private final TextView tvRemaining;
    private final ProgressBar pb;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideTask = new Runnable() {
        @Override
        public void run() {
            if (panel != null) {
                panel.setVisibility(View.GONE);
            }
        }
    };

    public SeekPreviewController(View panel, TextView tvAction, TextView tvTime,
                                 TextView tvRemaining, ProgressBar pb) {
        this.panel = panel;
        this.tvAction = tvAction;
        this.tvTime = tvTime;
        this.tvRemaining = tvRemaining;
        this.pb = pb;
    }

    /** 浮层是否可用（对应的 View 已找到） */
    public boolean isReady() {
        return panel != null;
    }

    /**
     * 更新并显示预览。
     *
     * @param positionMs 预览目标位置
     * @param durationMs 总时长
     * @param forward    true=快进 / false=快退
     */
    public void show(long positionMs, long durationMs, boolean forward) {
        if (panel == null) {
            return;
        }
        handler.removeCallbacks(hideTask);

        if (tvAction != null) {
            tvAction.setText(forward ? "快进" : "快退");
        }
        if (tvTime != null) {
            tvTime.setText(String.format(Locale.ROOT, "%s / %s",
                    fmt(positionMs), fmt(durationMs)));
        }
        if (tvRemaining != null) {
            long left = Math.max(0, durationMs - positionMs);
            tvRemaining.setText(String.format(Locale.ROOT, "距结束 %s", fmt(left)));
        }
        if (pb != null && durationMs > 0) {
            int p = (int) Math.max(0, Math.min(1000, positionMs * 1000 / durationMs));
            pb.setProgress(p);
        }
        panel.setVisibility(View.VISIBLE);
    }

    /** 延时自动隐藏（seek 提交后调用，让用户看到结果） */
    public void hideDelayed(long delayMs) {
        if (panel == null) {
            return;
        }
        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, delayMs > 0 ? delayMs : AUTO_HIDE_MS);
    }

    /** 立即隐藏 */
    public void hide() {
        if (panel == null) {
            return;
        }
        handler.removeCallbacks(hideTask);
        panel.setVisibility(View.GONE);
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
    }

    /** 毫秒 → mm:ss（超过 1 小时为 h:mm:ss） */
    private static String fmt(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }
}
