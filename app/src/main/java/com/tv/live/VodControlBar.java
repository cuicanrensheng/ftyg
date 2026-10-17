package com.tv.live;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.tv.live.util.LogBridge;

/**
 * 【2026-10-17 OTT 点播控制栏】
 *
 * <p>参考爱奇艺/优酷/TIVRA 的 TV 端范式：
 * <pre>
 *   ┌ 标题 · 第N集/共M集                1080P · 线路2 · 1.0× ┐
 *   │ 18:42 ▬▬▬▬▬▬▬●▬▬▬▬ 45:10（缓冲条 + 可拖动）           │
 *   │ [上一集][下一集][«10s][暂停][10s»][倍速][清晰度][线路][选集][比例] │
 * </pre>
 *
 * <p>交互约定（与主流 TV 端一致）：
 * <ul>
 *   <li>进度条获得焦点时：左右键 ±10s，长按加速（10→20→40→80→120s），
 *       <b>松手后只 seek 一次</b>（350ms 防抖，避免连续 seek 卡死）；</li>
 *   <li>确认键在进度条上 = 播放/暂停；</li>
 *   <li>焦点在按钮行时左右移动，上键回进度条；</li>
 *   <li>显示/隐藏与 5 秒自动收起由宿主 Activity 控制。</li>
 * </ul>
 */
public class VodControlBar extends LinearLayout {

    private static final String TAG = "VodControlBar";

    /** 宿主（OttHomeActivity）提供数据与动作 */
    public interface Host {
        String barTitle();

        String barChips();

        long barPosition();

        long barDuration();

        long barBuffered();

        boolean barIsPlaying();

        String barSpeedLabel();

        String barAspectLabel();

        void barTogglePlay();

        void barPrevEpisode();

        void barNextEpisode();

        void barSeekTo(long positionMs);

        void barShowSpeed();

        void barShowQuality();

        void barShowLine();

        void barShowEpisodes();

        void barCycleAspect();
    }

    private Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTitle, tvChips, tvPos, tvDur, btnPlay, btnSpeed, btnAspect;
    private TextView btnPrev, btnNext, btnLine, btnEpisodes;
    private SeekBar seek;
    private LinearLayout btnRow, seekRow;
    /** true=点播（显示上/下集、进度条、倍速）；false=直播（只保留播放/暂停、清晰度、比例） */
    private boolean vodMode = true;

    private boolean refreshing;
    private long seekTargetMs = -1;
    private long seekStepMs = 10_000L;
    private int lastSeekDir = 0;

    private final Runnable seekCommit = () -> {
        if (seekTargetMs >= 0) {
            long target = seekTargetMs;
            seekTargetMs = -1;
            seekStepMs = 10_000L;
            lastSeekDir = 0;
            LogBridge.i(TAG, "控制栏 seek → " + target + "ms（一次提交）");
            host.barSeekTo(target);
        }
    };

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (!refreshing) return;
            refreshProgress();
            handler.postDelayed(this, 500);
        }
    };

    public VodControlBar(Context context, Host host) {
        super(context);
        this.host = host;
        setOrientation(VERTICAL);
        setBackground(roundRect(0xE6121212, 16));
        setPadding(dp(28), dp(16), dp(28), dp(16));
        setFocusable(false);
        build();
    }

    // ------------------------- UI 构建 -------------------------

    private void build() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvTitle = text(15, 0xFFFFFFFF, true);
        tvTitle.setSingleLine(true);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvChips = text(12, 0xFF9E9E9E, false);
        tvChips.setSingleLine(true);
        header.addView(tvChips);

        seekRow = new LinearLayout(getContext());
        seekRow.setOrientation(HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        addView(seekRow, slp);

        tvPos = text(12, 0xFFB0B0B0, false);
        tvPos.setMinWidth(dp(56));
        seekRow.addView(tvPos);

        seek = new SeekBar(getContext());
        seek.setMax(1000);
        seek.setFocusable(true);
        seek.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams sklp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sklp.leftMargin = dp(10);
        sklp.rightMargin = dp(10);
        seekRow.addView(seek, sklp);

        tvDur = text(12, 0xFFB0B0B0, false);
        tvDur.setMinWidth(dp(56));
        seekRow.addView(tvDur);

        seek.setOnKeyListener((v, keyCode, event) -> handleSeekKey(keyCode, event));
        seek.setOnFocusChangeListener((v, has) -> {
            if (has) seek.setAlpha(1f);
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    long dur = host.barDuration();
                    if (dur > 0) {
                        seekTargetMs = dur * progress / 1000L;
                        tvPos.setText(fmt(seekTargetMs));
                        handler.removeCallbacks(seekCommit);
                        handler.postDelayed(seekCommit, 350);
                    }
                }
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        btnRow = new LinearLayout(getContext());
        btnRow.setOrientation(HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        addView(btnRow, blp);

        btnPrev = addButton("上一集", v -> host.barPrevEpisode(), true);
        btnNext = addButton("下一集", v -> host.barNextEpisode(), true);
        addButton("« 10s", v -> nudge(-10_000L), false);
        btnPlay = addButton("暂停", v -> host.barTogglePlay(), true);
        addButton("10s »", v -> nudge(10_000L), false);
        btnSpeed = addButton("倍速", v -> host.barShowSpeed(), false);
        addButton("清晰度", v -> host.barShowQuality(), false);
        btnLine = addButton("线路", v -> host.barShowLine(), false);
        btnEpisodes = addButton("选集", v -> host.barShowEpisodes(), false);
        btnAspect = addButton("比例", v -> {
            host.barCycleAspect();
            handler.postDelayed(this::refreshChips, 100);
        }, false);

        // 焦点链：进度条 ↔ 按钮行
        seek.setNextFocusDownId(View.NO_ID);
    }

    private TextView addButton(String label, View.OnClickListener click, boolean primary) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(9), dp(16), dp(9));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setBackground(primary ? roundRect(0xFFFFD166, 10) : roundRect(0xFF2E2E2E, 10));
        tv.setTextColor(primary ? 0xFF111111 : 0xFFFFFFFF);
        tv.setOnClickListener(click);
        tv.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? roundRect(0xFFFFD166, 10)
                    : (primary ? roundRect(0xFFFFD166, 10) : roundRect(0xFF2E2E2E, 10)));
            ((TextView) v).setTextColor((has || primary) ? 0xFF111111 : 0xFFFFFFFF);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        btnRow.addView(tv, lp);
        return tv;
    }

    /**
     * 【2026-10-17】点播/直播两套形态：
     * 点播 → 上/下集 + 进度条 + 倍速 + 线路 + 选集（追剧全功能）；
     * 直播 → 隐藏上/下集、进度条、倍速、线路、选集（直播没有集数概念，时长也只是窗口）。
     */
    public void setVodMode(boolean vod) {
        this.vodMode = vod;
        int vis = vod ? View.VISIBLE : View.GONE;
        if (btnPrev != null) btnPrev.setVisibility(vis);
        if (btnNext != null) btnNext.setVisibility(vis);
        if (seekRow != null) seekRow.setVisibility(vis);
        if (btnLine != null) btnLine.setVisibility(vis);
        if (btnEpisodes != null) btnEpisodes.setVisibility(vis);
        if (btnSpeed != null) btnSpeed.setVisibility(vis);
        LogBridge.i(TAG, "控制栏形态=" + (vod ? "点播" : "直播"));
    }

    // ------------------------- 进度交互 -------------------------

    /** 进度条上的左右键：累加目标位置，长按加速，松手后一次性提交 */
    private boolean handleSeekKey(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int dir = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ? 1 : -1;
                if (event.getRepeatCount() == 0 || dir != lastSeekDir) {
                    seekStepMs = 10_000L;
                    lastSeekDir = dir;
                } else {
                    seekStepMs = Math.min(seekStepMs * 2, 120_000L);
                }
                long base = (seekTargetMs >= 0) ? seekTargetMs : host.barPosition();
                long dur = host.barDuration();
                long target = Math.max(0, base + dir * seekStepMs);
                if (dur > 0 && target > dur) target = dur;
                seekTargetMs = target;
                tvPos.setText(fmt(target));
                if (dur > 0) seek.setProgress((int) (target * 1000 / dur));
                handler.removeCallbacks(seekCommit);
                handler.postDelayed(seekCommit, 350);
                return true;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // 若刚拖动过，先提交再切换播放状态
                if (seekTargetMs >= 0) {
                    handler.removeCallbacks(seekCommit);
                    seekCommit.run();
                } else {
                    host.barTogglePlay();
                    handler.postDelayed(this::refreshProgress, 120);
                }
                return true;
            }
            return true;
        }
        return false;
    }

    private void nudge(long deltaMs) {
        long dur = host.barDuration();
        long target = Math.max(0, host.barPosition() + deltaMs);
        if (dur > 0 && target > dur) target = dur;
        LogBridge.i(TAG, "控制栏快捷 seek " + deltaMs + "ms → " + target + "ms");
        host.barSeekTo(target);
        handler.postDelayed(this::refreshProgress, 150);
    }

    // ------------------------- 刷新 -------------------------

    public void show() {
        setVisibility(View.VISIBLE);
        refreshing = true;
        refreshAll();
        handler.removeCallbacks(refreshTick);
        handler.post(refreshTick);
        // 默认焦点落在「播放/暂停」，与 TIVRA 一致
        post(() -> {
            if (btnPlay != null) btnPlay.requestFocus();
        });
    }

    public void hide() {
        refreshing = false;
        handler.removeCallbacks(refreshTick);
        handler.removeCallbacks(seekCommit);
        seekTargetMs = -1;
        setVisibility(View.GONE);
    }

    public void refreshAll() {
        refreshTitle();
        refreshChips();
        refreshProgress();
    }

    private void refreshTitle() {
        try {
            tvTitle.setText(host.barTitle());
        } catch (Throwable ignored) {
        }
    }

    private void refreshChips() {
        try {
            tvChips.setText(host.barChips());
            if (btnSpeed != null) btnSpeed.setText("倍速 " + host.barSpeedLabel());
            if (btnAspect != null) btnAspect.setText("比例 " + host.barAspectLabel());
        } catch (Throwable ignored) {
        }
    }

    private void refreshProgress() {
        try {
            long pos = host.barPosition();
            long dur = host.barDuration();
            long buf = host.barBuffered();
            if (seekTargetMs >= 0) return;   // 正在拖动：不被刷新覆盖
            if (dur > 0) {
                int p = (int) (pos * 1000 / dur);
                seek.setProgress(Math.max(0, Math.min(1000, p)));
                tvPos.setText(fmt(pos));
                tvDur.setText(fmt(dur));
                seek.setSecondaryProgress((int) Math.max(0, Math.min(1000, buf * 1000 / dur)));
            } else {
                tvPos.setText(fmt(pos));
                tvDur.setText("--:--");
            }
            if (btnPlay != null) btnPlay.setText(host.barIsPlaying() ? "暂停" : "播放");
        } catch (Throwable ignored) {
        }
    }

    // ------------------------- 工具 -------------------------

    private TextView text(int sp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
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
