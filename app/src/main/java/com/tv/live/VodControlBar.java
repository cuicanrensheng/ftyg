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

        /** 当前源自己的清晰度标签（"自动" / "1080P"），用于「清晰度」胶囊上的实时值 */
        String barQualityLabel();

        void barTogglePlay();

        void barPrevEpisode();

        void barNextEpisode();

        void barSeekTo(long positionMs);

        void barShowSpeed();

        void barShowQuality();

        void barShowLine();

        void barShowEpisodes();

        /** 直播：频道列表抽屉 */
        void barShowChannelList();

        /** 直播：节目单抽屉 */
        void barShowEpg();

        /** 收藏当前频道/影片（返回切换后的状态：true=已收藏） */
        boolean barToggleFavorite();

        /** 当前是否已收藏 */
        boolean barIsFavorite();

        void barCycleAspect();

        /** 【2026-10-18】打开设置（两种形态都显示的「设置」胶囊入口） */
        void barOpenSettings();
    }

    private Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTitle, tvChips, tvPos, tvDur, btnPlay, btnSpeed, btnAspect;
    private TextView btnPrev, btnNext, btnQuality, btnLine, btnEpisodes, btnChannelList, btnEpg, btnFav, btnSettings;
    /** 最近获得焦点的按钮：弹层关闭后把焦点还给它（而不是回「播放/暂停」） */
    private TextView lastFocusedButton;
    private SeekBar seek;
    private LinearLayout btnRow, seekRow;
    private boolean capsuleGroupStarted;
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
        setPadding(dp(36), dp(20), dp(36), dp(20));
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

        tvTitle = text(20, 0xFFFFFFFF, true);
        tvTitle.setSingleLine(true);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvChips = text(14, 0xFF9E9E9E, false);
        tvChips.setSingleLine(true);
        header.addView(tvChips);

        seekRow = new LinearLayout(getContext());
        seekRow.setOrientation(HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        addView(seekRow, slp);

        tvPos = text(14, 0xFFB0B0B0, false);
        tvPos.setMinWidth(dp(64));
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

        tvDur = text(14, 0xFFB0B0B0, false);
        tvDur.setMinWidth(dp(64));
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
        blp.topMargin = dp(20);
        addView(btnRow, blp);

        // 【v1.4】集数块（方形圆角）+ 胶囊组（除集数块外全部胶囊，去掉了 «10s / 10s» 两个按钮）
        btnPrev = addEpisodeBlock("‹ 上一集", v -> host.barPrevEpisode());
        btnNext = addEpisodeBlock("下一集 ›", v -> host.barNextEpisode());
        btnPlay = addCapsule("播放", v -> host.barTogglePlay(), true);
        btnSpeed = addCapsule("倍速", v -> host.barShowSpeed(), false);
        btnQuality = addCapsule("清晰度", v -> host.barShowQuality(), false);
        btnLine = addCapsule("线路", v -> host.barShowLine(), false);
        btnEpisodes = addCapsule("选集", v -> host.barShowEpisodes(), false);
        btnChannelList = addCapsule("频道列表", v -> host.barShowChannelList(), false);
        btnEpg = addCapsule("节目单", v -> host.barShowEpg(), false);
        btnFav = addCapsule("收藏", v -> {
            host.barToggleFavorite();
            handler.postDelayed(this::refreshChips, 100);
        }, false);
        // 【2026-10-18 用户需求】「设置」胶囊：点播/直播两种形态都显示，打开 OTT 设置面板
        btnSettings = addCapsule("设置", v -> host.barOpenSettings(), false);
        // 【2026-10-17 用户口径】删除「比例/填充」按钮（按钮行只保留 6 个：播放/倍速/清晰度/线路/选集）

        // 焦点链：进度条 ↔ 按钮行
        seek.setNextFocusDownId(View.NO_ID);
    }

    /** 集数块：方形圆角（r=10），固定宽 136、高 56 —— 与胶囊区分，形成「换集 / 调设置」两组 */
    private TextView addEpisodeBlock(String label, View.OnClickListener click) {
        TextView tv = makeButton(label, click, false, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(52));
        lp.rightMargin = dp(10);
        tv.setLayoutParams(lp);
        if (!capsuleGroupStarted) {
            // 第一块前面不加额外间距
        }
        btnRow.addView(tv, lp);
        return tv;
    }

    /** 胶囊：r = 高 ÷ 2 = 28，最小宽 96，左右内距 28，等高 56 */
    private TextView addCapsule(String label, View.OnClickListener click, boolean primary) {
        TextView tv = makeButton(label, click, primary, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        lp.rightMargin = dp(10);
        if (!capsuleGroupStarted) {
            // 集数块与胶囊组之间留 20（比组内 12 大，形成视觉分组）
            lp.leftMargin = dp(16);
            capsuleGroupStarted = true;
        }
        btnRow.addView(tv, lp);
        return tv;
    }

    private TextView makeButton(String label, View.OnClickListener click,
                                boolean primary, boolean capsule) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setPadding(capsule ? dp(20) : dp(8), 0, capsule ? dp(20) : dp(8), 0);
        tv.setMinWidth(capsule ? dp(80) : dp(112));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setTag(primary ? "primary" : "normal");
        applyStyle(tv, primary, false, capsule);
        tv.setTextColor(0xFFFFFFFF);
        tv.setOnClickListener(v -> {
            LogBridge.i(TAG, "按钮点击=" + label);
            click.onClick(v);
        });
        tv.setOnFocusChangeListener((v, has) -> {
            boolean isPrimary = "primary".equals(v.getTag());
            applyStyle((TextView) v, isPrimary, has, capsule);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xFFFFFFFF);
            if (has) {
                lastFocusedButton = (TextView) v;
                LogBridge.i(TAG, "焦点=" + label);
            }
        });
        return tv;
    }

    /**
     * 焦点态 = 黄底 + 2px 白描边；**只有焦点项是黄底**。
     * 原实现让「播放/暂停」常驻黄底（primary），焦点移走后出现"两个黄块"，
     * 用户会以为有两处焦点（实测截图确认）。现在 primary 不再影响底色。
     */
    private void applyStyle(TextView tv, boolean primary, boolean focused, boolean capsule) {
        int fill = focused ? 0xFFFFD166 : 0xFF2E2E2E;
        GradientDrawable g = roundRect(fill, capsule ? 26 : 10);
        if (focused) g.setStroke(dp(2), 0xFFFFFFFF);
        tv.setBackground(g);
    }

    /**
     * 抽屉关闭后把焦点交回控制栏，由**原生焦点系统**决定落到哪个子按钮
     * （ViewGroup 会恢复自己上次聚焦的子 View，不再手写 restore 目标）。
     */
    public void restoreFocus() {
        postDelayed(() -> {
            boolean ok = requestFocus();
            LogBridge.i(TAG, "焦点交回控制栏（原生恢复上次子项）成功=" + ok);
        }, 60);
    }

    /** 供宿主在抽屉关闭时同步调用：优先上次聚焦的按钮，否则播放/暂停 */
    public boolean restoreFocusNow() {
        // 直播形态下 btnPlay 是隐藏的（直播不显示暂停/播放），回退目标要跟着形态走
        TextView target = (lastFocusedButton != null && lastFocusedButton.isShown())
                ? lastFocusedButton : null;
        if (target == null) {
            View t = pickDefaultFocusTarget();
            target = (t instanceof TextView) ? (TextView) t : btnPrev;
        }
        return target != null && target.requestFocus();
    }

    /**
     * 【2026-10-17】点播/直播两套形态：
     * 点播 → 上/下集 + 进度条 + 倍速 + 线路 + 选集（追剧全功能）；
     * 直播 → 隐藏上/下集、进度条、倍速、线路、选集（直播没有集数概念，时长也只是窗口）。
     */
    public void setVodMode(boolean vod) {
        this.vodMode = vod;
        int vis = vod ? View.VISIBLE : View.GONE;
        int liveVis = vod ? View.GONE : View.VISIBLE;
        if (btnPrev != null) {
            btnPrev.setText(vod ? "‹ 上一集" : "‹ 上一频道");
            btnPrev.setVisibility(View.VISIBLE);
        }
        if (btnNext != null) {
            btnNext.setText(vod ? "下一集 ›" : "下一频道 ›");
            btnNext.setVisibility(View.VISIBLE);
        }
        if (seekRow != null) seekRow.setVisibility(vis);
        if (btnLine != null) btnLine.setVisibility(View.VISIBLE);   // 线路：点播/直播都保留
        if (btnEpisodes != null) btnEpisodes.setVisibility(vis);       // 选集：仅点播
        if (btnSpeed != null) btnSpeed.setVisibility(vis);             // 倍速：仅点播
        if (btnChannelList != null) btnChannelList.setVisibility(liveVis);  // 频道列表：仅直播
        if (btnEpg != null) btnEpg.setVisibility(liveVis);                  // 节目单：仅直播
        if (btnFav != null) btnFav.setVisibility(View.VISIBLE);             // 收藏：两种形态都保留
        if (btnSettings != null) btnSettings.setVisibility(View.VISIBLE);   // 【2026-10-18】设置：两种形态都保留
        // 【2026-10-17 用户口径】直播不需要「暂停/播放」：直播流暂停没有意义，
        // 点播才需要。直播形态下整颗胶囊隐藏，空出的位置由「上一频道」当默认焦点。
        if (btnPlay != null) btnPlay.setVisibility(vod ? View.VISIBLE : View.GONE);
        keepFocusVisible();
        LogBridge.i(TAG, "控制栏形态=" + (vod ? "点播" : "直播"));
    }

    /**
     * 【2026-10-17 焦点兜底】形态切换会把「倍速/选集/频道列表/节目单」等按钮设成 GONE。
     * 若焦点正停在将要隐藏的按钮上，系统会把焦点交给窗口里第一个可聚焦控件
     * （= 「‹ 上一频道 / ‹ 上一集」），用户看到的就是"焦点自己跳回上一频道"。
     * 这里检测到这种情况就把焦点移到「播放/暂停」（两种形态都可见）。
     */
    private void keepFocusVisible() {
        try {
            View f = findFocus();
            if (f == null) return;
            if (f.getVisibility() == View.VISIBLE) return;
            View target = pickDefaultFocusTarget();
            if (target != null && target.requestFocus()) {
                LogBridge.i(TAG, "【焦点保持】原焦点按钮已隐藏 → 焦点移到 " + describeButton(target));
            }
        } catch (Throwable ignored) {
        }
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
        boolean wasVisible = getVisibility() == View.VISIBLE;
        // 【2026-10-17】变可见的瞬间，系统会按遍历顺序自动把焦点给"第一颗可聚焦按钮"
        // （直播 ‹ 上一频道 / 点播 ‹ 上一集），随后我们再 requestFocus 到播放 —— 日志实测
        // 会出现一次 `焦点=‹ 上一集` 再 `焦点=播放`。这里在 VISIBLE 前后临时屏蔽子控件获取焦点，
        // 让系统这一次自动分配直接落空，只保留我们指定的目标按钮。
        if (!wasVisible) setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setVisibility(View.VISIBLE);
        if (!wasVisible) setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        refreshing = true;
        refreshAll();
        handler.removeCallbacks(refreshTick);
        handler.post(refreshTick);
        // 【重要】只有"由隐藏变可见"才落焦点。
        //   控制栏本来就在显示（例如刚关掉抽屉）时再抢焦点，会把用户刚回位的焦点
        //   从"上一频道/频道列表"抢到"暂停"胶囊（用户实测）。
        if (wasVisible) {
            LogBridge.i(TAG, "控制栏已可见，不重置焦点");
            return;
        }
        // 【2026-10-17 修复「焦点先从上一频道跳到播放」】原来是 postDelayed(150) 才 requestFocus：
        //   这 150ms 里控制栏刚变 VISIBLE，系统已经按遍历顺序把焦点给了第一颗按钮
        //   （直播=‹ 上一频道 / 点播=‹ 上一集），150ms 后才跳到「播放/暂停」——用户看到的
        //   就是"先上一频道再播放"的两次跳动。改为**立刻同步**落位：同一帧内完成，
        //   不会有中间焦点帧；同步失败（布局未完成）才退回延迟兜底。
        final View target = pickDefaultFocusTarget();
        final boolean nowOk = (target != null) && target.requestFocus();
        LogBridge.i(TAG, "默认焦点请求(即时)=" + describeButton(target) + " 成功=" + nowOk);
        if (!nowOk) {
            postDelayed(() -> {
                if (getVisibility() != View.VISIBLE) return;
                View t = pickDefaultFocusTarget();
                boolean ok = (t != null) && t.requestFocus();
                LogBridge.i(TAG, "默认焦点请求(延迟)=" + describeButton(t) + " 成功=" + ok);
            }, 150);
        }
    }

    /**
     * 控制栏显示时该把焦点放哪：
     * 点播 → 「播放/暂停」；直播 → 「‹ 上一频道」（播放按钮在直播形态下已隐藏）。
     * 两者都不可见时才退到上次聚焦的按钮。
     */
    private View pickDefaultFocusTarget() {
        if (btnPlay != null && btnPlay.getVisibility() == View.VISIBLE) return btnPlay;
        if (btnPrev != null && btnPrev.getVisibility() == View.VISIBLE) return btnPrev;
        View v = lastFocusedButton;
        if (v != null && v.getVisibility() == View.VISIBLE && v.isShown()) return v;
        return null;
    }

    private String describeButton(View v) {
        if (v == null) return "null";
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            return (cs == null) ? "TextView" : cs.toString();
        }
        return v.getClass().getSimpleName();
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
            if (btnQuality != null) btnQuality.setText("清晰度 " + host.barQualityLabel());
            if (btnAspect != null) btnAspect.setText("比例 " + host.barAspectLabel());
            if (btnFav != null) btnFav.setText(host.barIsFavorite() ? "已收藏" : "收藏");
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
