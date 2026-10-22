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
 * 【2026-10-17 直播切台信息条（zapping banner）】
 *
 * <p>换台瞬间只弹这条轻量信息（左下角），**不弹控制栏**、不打断播放，3 秒后自动消失；
 * 连续换台只刷新内容并重置计时，符合爱奇艺/优酷/云视听 TV 的习惯。
 */
public class ChannelInfoBar extends LinearLayout {

    private static final long HIDE_DELAY_MS = 3000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvNumber, tvName, tvNow, tvNext;
    private View progress;
    private FrameLayout track;
    private FrameLayout host;
    private boolean attached;
    private float pendingPct = -1f;

    private final Runnable hideTask = () -> setVisibility(View.GONE);

    public ChannelInfoBar(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackground(round(0xE6121212, 14));
        setPadding(dp(32), dp(24), dp(36), dp(24));
        setFocusable(false);
        setClickable(false);
        build();
    }

    private void build() {
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        addView(head);
        tvNumber = text(30, 0xFFFFD166, true);
        tvNumber.setSingleLine(true);
        LinearLayout.LayoutParams nlp0 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp0.rightMargin = dp(12);
        head.addView(tvNumber, nlp0);
        tvName = text(26, 0xFFFFFFFF, true);
        tvName.setSingleLine(true);
        head.addView(tvName);

        track = new FrameLayout(getContext());
        track.setBackground(round(0xFF3A3A3A, 1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        tlp.topMargin = dp(8);
        addView(track, tlp);

        progress = new View(getContext());
        progress.setBackground(round(0xFFFFD166, 1));
        track.addView(progress, new FrameLayout.LayoutParams(0, dp(3)));

        tvNow = text(19, 0xFFB0B0B0, false);
        tvNow.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        addView(tvNow, nlp);

        tvNext = text(19, 0xFF7A7A7A, false);
        tvNext.setSingleLine(true);
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        xlp.topMargin = dp(4);
        addView(tvNext, xlp);
    }

    public void attachTo(FrameLayout root) {
        if (root == null || attached) return;
        this.host = root;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(780), ViewGroup.LayoutParams.WRAP_CONTENT);
        // 【用户口径】信息条改为**水平居中**（原来贴左下角）
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        lp.bottomMargin = dp(64);
        root.addView(this, lp);
        attached = true;
        setVisibility(View.GONE);
        com.tv.live.util.LogBridge.i("ChannelInfo", "attachTo 完成 width=" + dp(780));
    }

    /**
     * 换台时刷新。
     *
     * @param name    频道名
     * @param now     正在播（含时间），可空
     * @param next    下一档（含时间），可空
     * @param pct     当前节目进度 0~1，-1 表示未知（不画进度）
     */
    public void show(String name, String now, String next, float pct) {
        show(null, name, now, next, pct, -1);
    }

    public void show(String number, String name, String now, String next, float pct) {
        show(number, name, now, next, pct, -1);
    }

    /** bottomMarginPx > 0 时动态调整底边距（控制栏可见时上移，避免重叠） */
    public void show(String name, String now, String next, float pct, int bottomMarginPx) {
        show(null, name, now, next, pct, bottomMarginPx);
    }

    public void show(String number, String name, String now, String next,
                     float pct, int bottomMarginPx) {
        com.tv.live.util.LogBridge.i("ChannelInfo", "SHOW-CALL attached=" + attached
                + " name=" + name);
        if (!attached) return;
        if (bottomMarginPx > 0 && getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            lp.bottomMargin = bottomMarginPx;
            setLayoutParams(lp);
        }
        tvNumber.setText(number == null ? "" : number);
        tvNumber.setVisibility(number == null ? View.GONE : View.VISIBLE);
        tvName.setText(name == null ? "" : name);
        tvNow.setText(now == null ? "正在播：直播中" : ("正在播：" + now));
        if (next == null) {
            tvNext.setText("下一档：节目单暂未获取");
            tvNext.setVisibility(View.VISIBLE);
        } else {
            tvNext.setText("下一档：" + next);
            tvNext.setVisibility(View.VISIBLE);
        }
        pendingPct = pct;
        setVisibility(View.VISIBLE);
        bringToFront();
        post(() -> com.tv.live.util.LogBridge.i("ChannelInfo", "SHOWN vis=" + getVisibility()
                + " w=" + getWidth() + " h=" + getHeight() + " x=" + getX() + " y=" + getY()
                + " parent=" + (getParent() == null ? "null" : getParent().getClass().getSimpleName())
                + " text=" + tvName.getText()));
        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, HIDE_DELAY_MS);
        post(this::applyProgress);
    }

    private void applyProgress() {
        int w = track.getWidth();
        if (w <= 0 || pendingPct < 0) return;
        ViewGroup.LayoutParams lp = progress.getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.LayoutParams((int) (w * pendingPct), dp(3));
        }
        lp.width = (int) (w * Math.max(0f, Math.min(1f, pendingPct)));
        progress.setLayoutParams(lp);
    }

    public void hide() {
        handler.removeCallbacks(hideTask);
        setVisibility(View.GONE);
    }

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
}
