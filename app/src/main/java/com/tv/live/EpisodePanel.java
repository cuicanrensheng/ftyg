package com.tv.live;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tv.live.util.LogBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 【2026-10-17 OTT 选集抽屉 · v1.6 右侧竖排】
 *
 * <p>与爱奇艺/优酷 TV 一致：选集不浮在控制栏上方，而是贴右侧展开抽屉。
 * 网格 **5 列 × 4 行 = 20 格/页**（电视横向宽、竖向短，5×4 比 4×5 更省高度），
 * 支持 CH+/CH- 翻页、上一页/下一页胶囊、数字键直跳（1.5s 超时或 OK 立即跳）。
 *
 * <p>打开时定位当前集所在页并聚焦当前集；末页不足 20 格留空不拉伸。
 */
public class EpisodePanel extends LinearLayout {

    private static final String TAG = "EpisodeDrawer";
    private static final int COLS = 5;
    private static final int ROWS = 4;
    private static final int PER_PAGE = COLS * ROWS;
    private static final long DIGIT_TIMEOUT_MS = 1500L;

    /** 抽屉宽（dp）：5 列 × 70 + 间距 + 内距 */
    public static final int DRAWER_WIDTH_DP = 440;

    public interface OnPick {
        void onPick(int index);
    }

    public interface OnClose {
        void onClose();
    }

    public interface Watched {
        boolean isWatched(int index);
    }

    private final FrameLayout root;
    private final int total;
    private final Watched watched;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int currentIdx;
    private boolean desc;
    private int page;
    private boolean closed;

    private OnPick onPick;
    private OnClose onClose;

    private LinearLayout grid;
    private TextView tvInfo, tvPage, btnOrder, btnPrev, btnNext;
    private final List<View> cells = new ArrayList<>();
    private final List<Integer> cellEpisode = new ArrayList<>();

    private final StringBuilder digits = new StringBuilder();
    private TextView digitOverlay;

    private final Runnable digitCommit = this::commitDigits;

    public EpisodePanel(Context context, FrameLayout root, int total, int currentIdx, Watched watched) {
        super(context);
        this.root = root;
        this.total = Math.max(1, total);
        this.currentIdx = Math.max(0, Math.min(currentIdx, this.total - 1));
        this.watched = watched;
        setOrientation(VERTICAL);
        setBackground(round(0xFF1A1A1A, 16));
        setPadding(dp(24), dp(18), dp(24), dp(18));
        setFocusable(false);
        build();
    }

    public EpisodePanel onPick(OnPick p) {
        this.onPick = p;
        return this;
    }

    public EpisodePanel onClose(OnClose c) {
        this.onClose = c;
        return this;
    }

    // ------------------------- 构建 -------------------------

    private void build() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = text("选集", 18, 0xFF9E9E9E, false);
        header.addView(tvTitle);

        tvInfo = text("", 13, 0xFF6A6A6A, false);
        tvInfo.setSingleLine(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(12);
        header.addView(tvInfo, ilp);

        btnOrder = capsule("正序", v -> {
            desc = !desc;
            rebuild();
            focusCurrentIfVisible();
        });
        header.addView(btnOrder);

        tvPage = text("", 13, 0xFFFFD166, false);
        tvPage.setSingleLine(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(12);
        header.addView(tvPage, plp);

        grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        addView(grid, glp);

        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(10);
        addView(footer, flp);

        btnPrev = capsule("‹ 上一页", v -> turnPage(-1));
        footer.addView(btnPrev);

        TextView hint = text("数字键跳集", 12, 0xFF5F5F5F, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.leftMargin = dp(12);
        footer.addView(hint, hlp);

        btnNext = capsule("下一页 ›", v -> turnPage(1));
        footer.addView(btnNext);
    }

    private TextView capsule(String label, View.OnClickListener click) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setPadding(dp(16), 0, dp(16), 0);
        tv.setMinWidth(dp(76));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setOnClickListener(click);
        tv.setOnFocusChangeListener((v, has) -> styleCapsule((TextView) v, has));
        styleCapsule(tv, false);
        return tv;
    }

    private void styleCapsule(TextView tv, boolean focused) {
        GradientDrawable g = round(focused ? 0xFFFFD166 : 0xFF2E2E2E, 18);
        if (focused) g.setStroke(dp(2), 0xFFFFFFFF);
        tv.setBackground(g);
        tv.setTextColor(focused ? 0xFF111111 : 0xFFFFFFFF);
    }

    // ------------------------- 网格 -------------------------

    private int pageCount() {
        return Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
    }

    private int pageOf(int episodeIdx) {
        int seq = desc ? (total - 1 - episodeIdx) : episodeIdx;
        return Math.max(0, Math.min(pageCount() - 1, seq / PER_PAGE));
    }

    private void rebuild() {
        // 【2026-10-17 修复焦点跳走】removeAllViews 会删掉被聚焦的格子 → 焦点所有者消失，
        // Android 把焦点交给层级里第一个可聚焦控件（实测是控制栏「‹ 上一频道」/「暂停」/进度条）。
        // 两道保险：
        //   ① 重建期间让 grid 自己临时持有焦点 → 焦点始终留在抽屉内，不会"跑出去再被拉回来"；
        //   ② 重建后用下标把焦点同步还原到同一格（同步 requestFocus，不 post，
        //      避免中间让控制栏吃掉一次按键）。
        final int keepCell = focusedCellIndex();
        if (keepCell >= 0) {
            grid.setFocusableInTouchMode(true);
            grid.setFocusable(true);
            grid.requestFocus();
        }

        grid.removeAllViews();
        cells.clear();
        cellEpisode.clear();

        int pages = pageCount();
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;

        for (int r = 0; r < ROWS; r++) {
            LinearLayout rowView = new LinearLayout(getContext());
            rowView.setOrientation(HORIZONTAL);
            for (int c = 0; c < COLS; c++) {
                int pos = r * COLS + c;
                int seq = page * PER_PAGE + pos;
                if (seq >= total) {
                    // 末页不足：留空占位，不拉伸
                    rowView.addView(new View(getContext()), cellLp());
                    continue;
                }
                final int epIdx = desc ? (total - 1 - seq) : seq;
                View cell = makeCell(epIdx, c, r);
                rowView.addView(cell, cellLp());
                cells.add(cell);
                cellEpisode.add(epIdx);
            }
            grid.addView(rowView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        tvInfo.setText("第" + (currentIdx + 1) + "集 / 共" + total + "集");
        int from = page * PER_PAGE + 1;
        int to = Math.min(total, (page + 1) * PER_PAGE);
        tvPage.setText((desc ? (total - to + 1) + "-" + (total - from + 1) : from + "-" + to)
                + " / " + pages + "页");
        btnOrder.setText(desc ? "倒序" : "正序");
        btnPrev.setAlpha(page <= 0 ? 0.4f : 1f);
        btnNext.setAlpha(page >= pages - 1 ? 0.4f : 1f);
        clampFocusInside();
        // ⚠ 顺序很重要：必须**先把焦点交给目标格**，再撤掉 grid 的临时可聚焦性。
        // 反过来做（先 setFocusable(false)）会把正停在 grid 上的焦点清空 → 又掉回控制栏（已实测踩坑）。
        if (keepCell >= 0) {
            if (cells.isEmpty()) {
                releaseGridFocusHolder();
            } else {
                final int k = Math.min(keepCell, cells.size() - 1);
                if (cells.get(k).requestFocus()) {
                    releaseGridFocusHolder();
                } else {
                    // 极少见：视图还没附到窗口 → 退一帧再试，成功后立刻撤掉临时持有者
                    post(() -> {
                        if (closed || k >= cells.size()) return;
                        if (cells.get(k).requestFocus()) releaseGridFocusHolder();
                    });
                }
            }
        }
        LogBridge.i(TAG, "选集第 " + (page + 1) + "/" + pages + " 页，排序=" + (desc ? "倒序" : "正序"));
    }

    /**
     * 【2026-10-17 用户口径】焦点**锁死在选集抽屉内**：四边到底都不外跳，
     * 只能按返回键回到控制栏。
     *
     * <p>网格是「5 列 × 行」的 LinearLayout 组合，系统 FocusFinder 在
     * 最右列按右键 / 最后一行按下键时会搜到右侧与下方的控制栏按钮，
     * 造成「焦点从弹窗直接跑到控制栏」。这里逐格显式指定四向目标：
     * 左右相邻（到边指自己）、上下同列相邻（到边指自己），
     * 最后一行的下键指向抽屉自己的「下一页」按钮（仍在抽屉内）。
     */
    private void clampFocusInside() {
        int rowCount = grid.getChildCount();
        // 第一遍：给所有可聚焦格分配 id
        for (int r = 0; r < rowCount; r++) {
            View rv = grid.getChildAt(r);
            if (!(rv instanceof android.view.ViewGroup)) continue;
            android.view.ViewGroup rowG = (android.view.ViewGroup) rv;
            for (int c = 0; c < rowG.getChildCount(); c++) {
                View cell = rowG.getChildAt(c);
                if (cell != null && cell.isFocusable() && cell.getId() == View.NO_ID) {
                    cell.setId(View.generateViewId());
                }
            }
        }
        // 第二遍：钉住四向
        for (int r = 0; r < rowCount; r++) {
            View rv = grid.getChildAt(r);
            if (!(rv instanceof android.view.ViewGroup)) continue;
            android.view.ViewGroup rowG = (android.view.ViewGroup) rv;
            for (int c = 0; c < rowG.getChildCount(); c++) {
                View cell = rowG.getChildAt(c);
                if (cell == null || !cell.isFocusable()) continue;
                View left = null, right = null;
                for (int k = c - 1; k >= 0; k--) {
                    View t = rowG.getChildAt(k);
                    if (t != null && t.isFocusable()) { left = t; break; }
                }
                for (int k = c + 1; k < rowG.getChildCount(); k++) {
                    View t = rowG.getChildAt(k);
                    if (t != null && t.isFocusable()) { right = t; break; }
                }
                cell.setNextFocusLeftId(left == null ? cell.getId() : left.getId());
                cell.setNextFocusRightId(right == null ? cell.getId() : right.getId());

                View up = cellAt(r - 1, c);
                View down = cellAt(r + 1, c);
                if (down == null) {
                    // 【2026-10-17 修复「焦点到不了上一页」】末页不足时下面可能整行都是空占位，
                    // cellAt 返回 null。原实现只在"网格最后一行(r==rowCount-1)"才兜底到「下一页」，
                    // 于是倒数第二行（如 31/32 所在行）的下键被钉死在自己身上 → 焦点永远到不了
                    // 上一页/下一页按钮（用户实测红框处卡住）。
                    // 现在只要下面没格子就落到抽屉内的翻页按钮：左半区→上一页，右半区→下一页。
                    down = (c * 2 < COLS) ? btnPrev : btnNext;
                    if (down == null || !down.isFocusable()) down = btnNext;
                    if (down != null && !down.isFocusable()) down = null;
                    if (down != null && down.getId() == View.NO_ID) {
                        down.setId(View.generateViewId());
                    }
                }
                cell.setNextFocusUpId(up == null ? cell.getId() : up.getId());
                cell.setNextFocusDownId(down == null ? cell.getId() : down.getId());
            }
        }
        // 底部三个按钮：下键钉在自己身上（否则会搜到下方控制栏），上键回网格「最后一个有格子的行」
        View lastRowCell = null;
        for (int r = rowCount - 1; r >= 0 && lastRowCell == null; r--) {
            lastRowCell = cellAt(r, 0);
        }
        View[] footers = new View[]{btnPrev, btnNext, btnOrder};
        for (View b : footers) {
            if (b == null || !b.isFocusable()) continue;
            if (b.getId() == View.NO_ID) b.setId(View.generateViewId());
            b.setNextFocusDownId(b.getId());
            if (lastRowCell != null) b.setNextFocusUpId(lastRowCell.getId());
        }
        // 翻页按钮左右互通（中间的"数字键跳集"提示不可聚焦，默认搜索也能过，这里显式钉死）
        if (btnPrev != null && btnNext != null && btnPrev.isFocusable() && btnNext.isFocusable()) {
            btnPrev.setNextFocusRightId(btnNext.getId());
            btnNext.setNextFocusLeftId(btnPrev.getId());
        }
        // 「正序/倒序」在头部：上键钉自己、下键落网格首个格子
        if (btnOrder != null && btnOrder.isFocusable()) {
            btnOrder.setNextFocusUpId(btnOrder.getId());
            View firstCell = cellAt(0, 0);
            if (firstCell != null) btnOrder.setNextFocusDownId(firstCell.getId());
        }
        LogBridge.i(TAG, "【选集】焦点已锁定在内（返回键才回控制栏）");
    }

    /** 撤掉 grid 的"临时焦点持有者"身份（此时焦点应已在某个格子上，撤掉不会丢失焦点） */
    private void releaseGridFocusHolder() {
        grid.setFocusable(false);
        grid.setFocusableInTouchMode(false);
    }

    /** 当前聚焦格下标；焦点不在格子上返回 -1（用于重建后把焦点接回来） */
    private int focusedCellIndex() {
        View f = getRootView().findFocus();
        if (f == null) return -1;
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i) == f) return i;
        }
        return -1;
    }

    /** 取第 r 行第 c 列的可聚焦格；越界/空位返回 null */
    private View cellAt(int r, int c) {
        if (r < 0 || r >= grid.getChildCount()) return null;
        View rv = grid.getChildAt(r);
        if (!(rv instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup rowG = (android.view.ViewGroup) rv;
        if (c < 0 || c >= rowG.getChildCount()) return null;
        View v = rowG.getChildAt(c);
        return (v != null && v.isFocusable()) ? v : null;
    }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(70), dp(44));
        lp.rightMargin = dp(10);
        lp.topMargin = dp(10);
        return lp;
    }

    private View makeCell(final int epIdx, final int col, final int row) {
        FrameLayout cell = new FrameLayout(getContext());
        final boolean isCurrent = (epIdx == currentIdx);
        final boolean seen = watched != null && watched.isWatched(epIdx);

        TextView tv = new TextView(getContext());
        tv.setText(String.format(Locale.US, "%02d", epIdx + 1));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(isCurrent ? 0xFF111111 : (seen ? 0xFF7A7A7A : 0xFFFFFFFF));
        cell.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (seen && !isCurrent) {
            View dot = new View(getContext());
            GradientDrawable dg = new GradientDrawable();
            dg.setColor(0xFF6FCF97);
            dg.setCornerRadius(dp(3));
            dot.setBackground(dg);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(6), dp(6));
            dlp.gravity = Gravity.BOTTOM | Gravity.END;
            dlp.rightMargin = dp(8);
            dlp.bottomMargin = dp(6);
            cell.addView(dot, dlp);
        }

        styleCell(cell, isCurrent, seen, false);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setOnClickListener(v -> pick(epIdx));
        cell.setOnFocusChangeListener((v, has) -> styleCell((FrameLayout) v, isCurrent, seen, has));
        cell.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                appendDigit(keyCode - KeyEvent.KEYCODE_0);
                return true;
            }
            if (digits.length() > 0
                    && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                commitDigits();
                return true;
            }
            // BACK 由 Activity.onKeyDown 统一收口（这里再处理会导致一次按键关两层）
            // 【2026-10-17 用户口径】最右列往右**不再收起抽屉**：抽屉在右侧，右键会把焦点
            // 直接送到控制栏。规则要求「只能按返回键回控制栏」→ 这里吃掉按键，焦点不动。
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && col == COLS - 1) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                turnPage(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                turnPage(1);
                return true;
            }
            return false;
        });
        return cell;
    }

    private void styleCell(FrameLayout cell, boolean isCurrent, boolean seen, boolean focused) {
        int fill;
        if (focused) fill = 0xFFFFD166;          // 只有焦点是黄底
        else if (isCurrent) fill = 0xFF2E2E2E;   // 当前集：深底 + 黄字（不抢焦点）
        else if (seen) fill = 0xFF2A2A2A;
        else fill = 0xFF2E2E2E;
        GradientDrawable g = round(fill, 22);   // 胶囊：r = 高(44) ÷ 2
        if (focused) g.setStroke(dp(2), 0xFFFFFFFF);
        cell.setBackground(g);
        if (cell.getChildAt(0) instanceof TextView) {
            int tc;
            if (focused) tc = 0xFF111111;
            else if (isCurrent) tc = 0xFFFFD166;   // 当前集黄字
            else if (seen) tc = 0xFF7A7A7A;
            else tc = 0xFFFFFFFF;
            ((TextView) cell.getChildAt(0)).setTextColor(tc);
        }
    }

    // ------------------------- 交互 -------------------------

    private void turnPage(int delta) {
        int target = page + delta;
        if (target < 0 || target >= pageCount()) {
            LogBridge.i(TAG, "已到" + (delta > 0 ? "最后一页" : "第一页"));
            return;
        }
        page = target;
        rebuild();
        focusCurrentIfVisible();
    }

    /**
     * 打开/翻页后把焦点放到当前集（找不到则第一格）。
     * 布局完成前 requestFocus 会失败、焦点被"正序"按钮抢走（实测），
     * 所以延迟 300ms 再来一次，仍失败就再补一次。
     */
    private void focusCurrentIfVisible() {
        focusCurrentOnce(300);
        focusCurrentOnce(700);
    }

    private void focusCurrentOnce(long delayMs) {
        postDelayed(() -> {
            if (closed || !isShown()) return;
            View target = null;
            for (int i = 0; i < cellEpisode.size(); i++) {
                if (cellEpisode.get(i) == currentIdx) {
                    target = cells.get(i);
                    break;
                }
            }
            if (target == null && !cells.isEmpty()) target = cells.get(0);
            if (target == null) return;
            if (target.requestFocus()) {
                LogBridge.i(TAG, "焦点落到第" + (currentIdx + 1) + "集");
            }
        }, delayMs);
    }

    private void pick(int epIdx) {
        LogBridge.i(TAG, "切集 → 第" + (epIdx + 1) + "集");
        if (onPick != null) onPick.onPick(epIdx);
        close();
    }

    // ------------------------- 数字键跳集 -------------------------

    private void appendDigit(int d) {
        if (digits.length() >= 4) digits.setLength(0);
        digits.append(d);
        showDigitOverlay();
        handler.removeCallbacks(digitCommit);
        handler.postDelayed(digitCommit, DIGIT_TIMEOUT_MS);
    }

    private void showDigitOverlay() {
        if (digitOverlay == null) {
            digitOverlay = new TextView(getContext());
            digitOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            digitOverlay.setTextColor(0xFFFFD166);
            digitOverlay.setGravity(Gravity.CENTER);
            GradientDrawable g = round(0xFF1A1A1A, 16);
            g.setStroke(dp(2), 0xFFFFD166);
            digitOverlay.setBackground(g);
            digitOverlay.setPadding(dp(32), dp(18), dp(32), dp(18));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            root.addView(digitOverlay, lp);
        }
        String num = digits.toString();
        String full = "跳转到第 " + num + " 集";
        SpannableString ss = new SpannableString(full);
        try {
            ss.setSpan(new RelativeSizeSpan(1.7f), 5, 5 + num.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Throwable ignored) {
        }
        digitOverlay.setText(ss);
        digitOverlay.setVisibility(View.VISIBLE);
        digitOverlay.bringToFront();
    }

    private void commitDigits() {
        if (digits.length() == 0) return;
        int n;
        try {
            n = Integer.parseInt(digits.toString());
        } catch (Throwable t) {
            n = 1;
        }
        digits.setLength(0);
        hideDigitOverlay();
        int idx = Math.max(0, Math.min(total - 1, n - 1));
        LogBridge.i(TAG, "数字直跳 输入=" + n + " → 第" + (idx + 1) + "集");
        pick(idx);
    }

    private void hideDigitOverlay() {
        if (digitOverlay != null) {
            if (digitOverlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) digitOverlay.getParent()).removeView(digitOverlay);
            }
            digitOverlay = null;
        }
    }

    // ------------------------- 显示 / 关闭 -------------------------

    /** 抽屉贴右显示：底边 = 控制栏顶边 − 16dp */
    public void show(int bottomMarginPx) {
        if (root == null || closed) return;
        page = pageOf(currentIdx);
        rebuild();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(DRAWER_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(32);
        lp.bottomMargin = bottomMarginPx + dp(16);
        root.addView(this, lp);
        focusCurrentIfVisible();
        LogBridge.i(TAG, "选集抽屉显示：共" + total + "集，当前第" + (currentIdx + 1) + "集");
    }

    public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        hideDigitOverlay();
        // 【2026-10-17】先 onClose 把焦点还给触发按钮，再移除视图；否则 removeView 删掉焦点所有者，
        // 系统会把焦点丢给控制栏「‹ 上一集」，60ms 后才纠正（用户看到焦点跳走）。
        if (onClose != null) onClose.onClose();
        if (root != null) root.removeView(this);
    }

    public boolean isShowing() {
        return !closed && getParent() != null;
    }

    // ------------------------- 工具 -------------------------

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
