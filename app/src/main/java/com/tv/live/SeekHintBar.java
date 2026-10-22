package com.tv.live;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 【2026-10-17 简易进度条】控制栏隐藏时按左右键快进/快退的轻量反馈。
 *
 * <p>只显示三样东西：目标时间、进度条（含当前位置标记）、总时长 + 本次步长（+10s / -10s）。
 * 1.4 秒无操作自动消失，不抢占焦点、不遮挡画面主体，也不唤起完整控制栏。
 */
public class SeekHintBar extends LinearLayout {

    private static final long HIDE_DELAY_MS = 2500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvPos, tvDelta, tvDur;
    private FrameLayout track;
    private View progress, marker;
    private FrameLayout host;

    private long pendingTarget, pendingDuration;
    private boolean attached;

    /** 到点只隐藏、**不移除**：移除后 attached=false，第二次 show() 会直接返回
     *  —— 这正是"第一次按有进度条、后面按不出来"的原因。 */
    private final Runnable hideTask = () -> setVisibility(View.GONE);

    public SeekHintBar(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(round(0xE6121212, 12));
        setPadding(dp(24), dp(12), dp(24), dp(12));
        setFocusable(false);
        setClickable(false);
        build();
    }

    private void build() {
        tvPos = text(16, 0xFFFFFFFF, true);
        tvPos.setMinWidth(dp(76));
        addView(tvPos);

        track = new FrameLayout(getContext());
        GradientDrawable trackBg = round(0xFF3A3A3A, 3);
        track.setBackground(trackBg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(6), 1f);
        tlp.leftMargin = dp(16);
        tlp.rightMargin = dp(16);
        addView(track, tlp);

        progress = new View(getContext());
        progress.setBackground(round(0xFFFFD166, 3));
        track.addView(progress, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

        marker = new View(getContext());
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFFFD166);
        marker.setBackground(dot);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(14), dp(14));
        mlp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        track.addView(marker, mlp);

        tvDelta = text(14, 0xFFFFD166, false);
        tvDelta.setMinWidth(dp(52));
        tvDelta.setGravity(Gravity.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = dp(12);
        addView(tvDelta, dlp);
        tvDur = text(16, 0xFFB0B0B0, false);
        tvDur.setMinWidth(dp(76));
        tvDur.setGravity(Gravity.END);
        addView(tvDur);
    }

    /** 挂到根布局（底部居中，位于控制栏上方一点，避免与将来的控制栏重叠） */
    public void attachTo(FrameLayout root) {
        if (root == null || attached) return;
        this.host = root;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(560), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(120);
        root.addView(this, lp);
        attached = true;
        setVisibility(View.GONE);
    }

    /** 显示/刷新：targetMs 目标位置，durationMs 总时长，deltaMs 本次步长（±10000） */
    public void show(long targetMs, long durationMs, long deltaMs) {
        if (!attached) return;
        if (durationMs <= 0) return;   // 直播/无时长：不显示进度条
        pendingTarget = targetMs;
        pendingDuration = durationMs;
        setVisibility(View.VISIBLE);
        bringToFront();

        tvPos.setText(fmt(targetMs));
        tvDur.setText(fmt(durationMs));
        tvDelta.setText((deltaMs >= 0 ? "+" : "−") + Math.abs(deltaMs) / 1000 + "s");
        tvDelta.setTextColor(deltaMs >= 0 ? 0xFFFFD166 : 0xFFB0B0B0);

        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, HIDE_DELAY_MS);
        post(this::applyProgress);
        com.tv.live.util.LogBridge.i("SeekHint", "显示 " + fmt(targetMs) + " / " + fmt(durationMs)
                + " 步长=" + deltaMs + "ms 可见=" + (getVisibility() == View.VISIBLE));
    }

    private void applyProgress() {
        int w = track.getWidth();
        if (w <= 0) return;
        float pct = (pendingDuration > 0)
                ? Math.max(0f, Math.min(1f, pendingTarget / (float) pendingDuration)) : 0f;
        int pw = (int) (w * pct);
        ViewGroup.LayoutParams plp = progress.getLayoutParams();
        plp.width = pw;
        progress.setLayoutParams(plp);
        marker.setTranslationX(Math.max(0, Math.min(w - dp(14), pw - dp(7))));
    }

    public void hide() {
        handler.removeCallbacks(hideTask);
        setVisibility(View.GONE);
    }

    // ------------------------- 工具 -------------------------

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%02d:%02d", m, s);
    }
}
