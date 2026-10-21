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
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tv.live.util.LogBridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 【2026-10-21 OTT 频道列表弹窗 · 双栏版】
 *
 * <p>按设计稿重排（原为「顶部横向页签 + 单列频道」）：
 * <pre>
 *  ┌ 频道列表                                              共911台 ┐
 *  │ ┌────────┐   ┌────────────────────────────────────────────┐ │
 *  │ │  全部  │   │ 01  CCTV1综合                              │ │
 *  │ │  卫视  │   │ 02  CCTV2财经                              │ │
 *  │ │ 电影…  │   │ 03  CCTV3综艺                              │ │
 *  │ │ 点播…  │   │ …                                          │ │
 *  │ │ 虎牙…  │   │                                            │ │
 *  │ └────────┘   └────────────────────────────────────────────┘ │
 *  │ CH± 翻页 · 数字键输频道号 · 返回键回到控制栏                   │
 *  └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>交互约定（延续原口径）：
 * <ul>
 *   <li>焦点<b>锁定在弹窗内</b>，只能按返回键回控制栏（由宿主统一处理）；</li>
 *   <li>左右键在「分组列 ↔ 频道列」之间移动，上下键在各自列内移动；</li>
 *   <li>CH± 按可视行数滚动，数字键 = 当前可视区第 N 个。</li>
 * </ul>
 */
public class ChannelListPanel extends LinearLayout {

    private static final String TAG = "ChannelList";

    // ---- 视觉规格（对齐设计稿） ----
    private static final int PANEL_BG = 0xFF1B1B1B;
    private static final int ITEM_BG = 0xFF292929;
    private static final int ITEM_BG_CURRENT = 0xFF303030;
    private static final int GOLD = 0xFFFFD166;
    private static final int GOLD_TOP = 0xFFFFDF85;
    private static final int GOLD_BOTTOM = 0xFFFFC94D;

    private static final int ROW_HEIGHT_DP = 37;
    private static final int ROW_GAP_DP = 11;
    private static final int GROUP_HEIGHT_DP = 41;
    private static final int GROUP_GAP_DP = 11;
    /** 弹窗宽度：恢复改造前的 560dp（贴右侧、控制栏上方） */
    private static final int PANEL_WIDTH_DP = 560;

    public interface OnPick {
        void onPick(Channel channel);
    }

    public interface OnClose {
        void onClose();
    }

    /**
     * 由宿主提供节目名。
     * 【2026-10-21】设计稿的行内只有「序号 + 频道名」，不再渲染节目名；
     * 保留该入参只为兼容宿主调用，后续若要在行尾加节目名可直接用。
     */
    public interface ProgramLookup {
        String nowPlaying(Channel ch);
    }

    /** 由宿主判断是否已收藏（"收藏"页签过滤用） */
    public interface FavoriteLookup {
        boolean isFavorite(Channel ch);
    }

    private final FrameLayout root;
    private final List<Channel> allChannels;
    private final Channel current;
    private final ProgramLookup lookup;
    private FavoriteLookup favLookup;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<String> groups = new ArrayList<>();
    private String activeGroup = "全部";
    private boolean closed;

    private OnPick onPick;
    private OnClose onClose;

    /** 主体（左右两列），高度按可视空间封顶，超出时两列各自滚动 */
    private LinearLayout body;
    /** 右列：频道列表 */
    private LinearLayout listBox;
    /** 左列：分组胶囊 */
    private LinearLayout groupBox;
    private ScrollView groupScroll, listScroll;
    private TextView tvPage;
    /** 当前选中的分组胶囊（行内左键回落到它） */
    private TextView activeTabView;

    private final List<View> rows = new ArrayList<>();
    private final List<View> tabViews = new ArrayList<>();
    private final List<Channel> rowChannels = new ArrayList<>();
    private List<Channel> filtered = new ArrayList<>();
    /** 最近一次聚焦的频道行（从分组列按右键回列表时用它定位） */
    private int lastRowIdx = 0;
    private int capRetry = 0;
    /** 弹窗内最后一次拥有焦点的控件（焦点被系统清空/越界时拉回它） */
    private View lastFocusedInside;
    /**
     * 【2026-10-21】虚拟焦点区：0=左侧分组列，1=右侧频道列。
     *
     * <p>实测左侧分组胶囊拿到 Android 焦点后会被系统清成 null（requestFocus 返回 true，
     * 但 0.5s 后焦点丢失），所以分组列不再依赖真实焦点：Android 焦点固定在频道行上，
     * 分组列的"焦点态"由本字段 + 自绘白描边表达，方向键全部由 {@link #handleNav} 驱动。
     */
    private int focusZone = 1;
    private int groupFocusIdx = 0;

    /**
     * 【2026-10-21】弹窗自带焦点守卫。
     *
     * <p>实测：焦点从频道行移到左侧分组胶囊后，约 0.5s 会被系统清成 null
     * （下一次按键时 {@code findFocus()} 为 null，按键落到控制栏）。宿主的
     * 抽屉看门狗只在 newFocus 非空时才会兜底，清空事件拦不住，所以这里自己兜：
     * 一旦焦点离开弹窗或被清空，<b>立刻</b>还回上一次在弹窗内的控件。
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusGuard =
            (oldF, newF) -> {
                if (closed) return;
                LogBridge.i(TAG, "焦点 " + describe(oldF) + " → " + describe(newF));
                if (newF != null && isDescendantOf(newF, this)) {
                    lastFocusedInside = newF;
                    return;
                }
                post(() -> {
                    if (closed || hasFocusInside()) return;
                    View back = (lastFocusedInside != null && lastFocusedInside.isAttachedToWindow()
                            && lastFocusedInside.isFocusable())
                            ? lastFocusedInside : null;
                    if (back == null && !rows.isEmpty()) {
                        back = rows.get(Math.min(lastRowIdx, rows.size() - 1));
                    }
                    if (back == null) return;
                    boolean ok = back.requestFocus();
                    LogBridge.i(TAG, "焦点被清空/越界 → 拉回 " + describe(back) + " 成功=" + ok);
                });
            };

    public ChannelListPanel(Context context, FrameLayout root, List<Channel> channels,
                            Channel current, ProgramLookup lookup) {
        super(context);
        this.root = root;
        this.allChannels = new ArrayList<>(channels);
        this.current = current;
        this.lookup = lookup;
        setOrientation(VERTICAL);
        setBackground(round(PANEL_BG, 24));
        setPadding(dp(24), dp(16), dp(24), dp(16));
        setFocusable(false);
        collectGroups();
        build();
    }

    public ChannelListPanel onFavoriteLookup(FavoriteLookup l) {
        this.favLookup = l;
        return this;
    }

    public ChannelListPanel onPick(OnPick p) {
        this.onPick = p;
        return this;
    }

    public ChannelListPanel onClose(OnClose c) {
        this.onClose = c;
        return this;
    }

    /**
     * 频道 → 弹窗里的分组名。
     *
     * <p>与主界面 Tab 完全同口径：
     * <ul>
     *   <li>虎牙频道统一归「虎牙频道」（主界面也是这样并把子分组放到胶囊条，不在 Tab 里拆开）；</li>
     *   <li>其余走 {@link com.tv.live.widget.GroupListManager#getNormalizedGroup}，空 group → 「未分类」。</li>
     * </ul>
     */
    private String groupOf(Channel c) {
        if (c == null) return "未分类";
        // 虎牙频道：roomId > 0（自研线路/HTTP API 两路都会带）
        try {
            if (c.getHuyaRoomId() > 0) return "虎牙频道";
        } catch (Throwable ignored) {
        }
        String g = com.tv.live.widget.GroupListManager.getNormalizedGroup(c);
        return isEmpty(g) ? "未分类" : g;
    }

    private void collectGroups() {
        groups.add("全部");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (Channel c : allChannels) {
            String g = groupOf(c);
            if (isEmpty(g) || "全部".equals(g)) continue;
            set.add(g);
        }
        groups.addAll(set);
        LogBridge.i(TAG, "频道列表分组共 " + groups.size() + " 个：" + groups);
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ==================== 构建 ====================

    private void build() {
        // ---- 顶栏：标题 + 总台数 ----
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("频道列表", 18, 0xFF9A9A9A, false);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvPage = text("", 15, GOLD, false);
        header.addView(tvPage);

        // ---- 主体：左分组列 + 右频道列 ----
        body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        addView(body, blp);

        // 左列：竖向分组胶囊（分组多时可滚动）
        groupBox = new LinearLayout(getContext());
        groupBox.setOrientation(VERTICAL);
        groupScroll = new ScrollView(getContext());
        groupScroll.setVerticalScrollBarEnabled(false);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setFocusable(false);
        groupScroll.addView(groupBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f);
        body.addView(groupScroll, glp);

        // 右列：频道列表
        listBox = new LinearLayout(getContext());
        listBox.setOrientation(VERTICAL);
        listScroll = new ScrollView(getContext());
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setFocusable(false);
        listScroll.addView(listBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        slp.leftMargin = dp(18);
        body.addView(listScroll, slp);

        buildGroups();

        // ---- 底部提示 ----
        TextView hint = text("CH± 翻页 · 数字键输频道号 · 返回键回到控制栏", 11, 0xFF6E6E6E, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        addView(hint, hlp);
    }

    private void buildGroups() {
        groupBox.removeAllViews();
        tabViews.clear();
        for (final String g : groups) {
            final TextView tab = new TextView(getContext());
            tab.setText(g);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tab.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            tab.setIncludeFontPadding(false);
            tab.setSingleLine(true);
            tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tab.setPadding(dp(19), 0, dp(12), 0);
            // 分组列走"虚拟焦点"：不让 Android 真实焦点落在这里（会被系统清空），
            // 焦点态由 focusZone/groupFocusIdx + 白描边自绘。
            tab.setFocusable(false);
            tab.setFocusableInTouchMode(false);
            if (g.equals(activeGroup)) activeTabView = tab;
            styleGroup(tab, g.equals(activeGroup), false);
            tab.setOnClickListener(v -> activateGroup(g, tab));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(GROUP_HEIGHT_DP));
            lp.bottomMargin = dp(GROUP_GAP_DP);
            groupBox.addView(tab, lp);
            tabViews.add(tab);
        }
    }

    /** 切分组：只重刷胶囊样式 + 重建右列，焦点留在分组列 */
    private void activateGroup(String g, TextView tab) {
        int idx = indexOf(tabViews, tab);
        if (idx >= 0) groupFocusIdx = idx;
        if (g.equals(activeGroup)) {
            focusZone = 0;
            restyleGroups();
            restyleRows();
            return;
        }
        activeGroup = g;
        activeTabView = tab;
        restyleGroups();
        // 重建会摘掉当前焦点行，期间焦点守卫可能把焦点收回；重建后仍停在分组列
        rebuild(false);
        focusZone = 0;
        // 切分组后右列从头开始看：把锚点行重置到第一行（Android 焦点仍在频道列内部，
        // 只是不再有金色焦点态），同时 ScrollView 自动滚回顶部
        if (!rows.isEmpty()) {
            lastRowIdx = 0;
            rows.get(0).requestFocus();
        }
        listScroll.scrollTo(0, 0);
        restyleGroups();
        restyleRows();
        ensureGroupVisible();
        LogBridge.i(TAG, "切换分组：" + g + "（分组列焦点 idx=" + groupFocusIdx + "），右列重建");
    }

    /** 只重刷分组样式，不重建视图（重建会把焦点所有者删掉 → 焦点掉到控制栏） */
    private void restyleGroups() {
        for (int i = 0; i < groupBox.getChildCount(); i++) {
            View c = groupBox.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            TextView tv = (TextView) c;
            String label = tv.getText() == null ? "" : tv.getText().toString();
            boolean selected = label.equals(activeGroup);
            if (selected) activeTabView = tv;
            boolean focused = (focusZone == 0) && (i == groupFocusIdx);
            styleGroup(tv, selected, focused);
        }
        ensureGroupVisible();
    }

    /** 重刷频道行：只有「焦点在频道列」时，获焦行才显示金色焦点态 */
    private void restyleRows() {
        for (int i = 0; i < rows.size(); i++) {
            View v = rows.get(i);
            if (!(v instanceof LinearLayout)) continue;
            boolean cur = i < rowChannels.size() && sameChannel(rowChannels.get(i), current);
            styleRow((LinearLayout) v, cur, focusZone == 1 && v.isFocused());
        }
    }

    /**
     * 把当前分组胶囊滚进左列可视区。
     *
     * <p>⚠ 不能用 {@code groupScroll.requestChildFocus(t, t)}：那会把系统记录在
     * ViewGroup 里的"焦点子控件"改写成 groupScroll（而真正的焦点还在右侧频道行），
     * 焦点链被写坏后系统会静默把行焦点清成 null（实测：每次按左键进分组列后
     * 下一次按键都读到 焦点=null）。这里只做纯滚动。
     */
    private void ensureGroupVisible() {
        if (groupFocusIdx < 0 || groupFocusIdx >= tabViews.size()) return;
        View t = tabViews.get(groupFocusIdx);
        if (t == null) return;
        int top = t.getTop();
        int y = groupScroll.getScrollY();
        int h = groupScroll.getHeight();
        if (h <= 0) return;
        if (top < y) {
            groupScroll.scrollTo(0, Math.max(0, top));
        } else if (top + t.getHeight() > y + h) {
            groupScroll.scrollTo(0, top + t.getHeight() - h);
        }
    }

    /**
     * 分组胶囊三态：
     * <ul>
     *   <li>焦点：金色渐变底 + 深色字 + 2dp 白描边（与右侧列表焦点完全一致）；</li>
     *   <li>仅选中（当前生效分组）：深灰提亮底 + 白字，<b>不用金色</b>；</li>
     *   <li>普通：深灰底 + 浅灰字。</li>
     * </ul>
     * 【2026-10-21】金色只表示焦点：原先 selected||focused 都套金色，会出现
     * "选中分组 + 焦点分组"两个金色胶囊（用户反馈的"分组内有两个焦点颜色"）。
     */
    private void styleGroup(TextView tv, boolean selected, boolean focused) {
        if (focused) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{GOLD_TOP, GOLD_BOTTOM});
            g.setCornerRadius(dp(8));
            g.setStroke(dp(2), 0xFFFFFFFF);
            tv.setBackground(g);
            tv.setTextColor(0xFF111111);
        } else if (selected) {
            tv.setBackground(round(ITEM_BG_CURRENT, 8));
            tv.setTextColor(0xFFFFFFFF);
        } else {
            tv.setBackground(round(ITEM_BG, 8));
            tv.setTextColor(0xFFD6D6D6);
        }
    }

    // ------------------------- 列表 -------------------------

    /**
     * 铺当前分组的全部频道。
     *
     * @param restoreFocus 重建后是否把焦点还原到原来的行；切分组时传 false，
     *                     由调用方把锚点行重置到第一行（列表从顶部开始看）。
     */
    private void rebuild(boolean restoreFocus) {
        long t0 = System.currentTimeMillis();
        final int keepIdx = restoreFocus ? focusedRowIndex() : -1;
        if (keepIdx >= 0) {
            // 重建期间让 listBox 临时持有焦点，焦点始终留在弹窗内
            listBox.setFocusableInTouchMode(true);
            listBox.setFocusable(true);
            listBox.requestFocus();
        }

        filtered = new ArrayList<>();
        boolean favTab = "收藏".equals(activeGroup);
        for (Channel c : allChannels) {
            if (favTab) {
                if (favLookup != null && favLookup.isFavorite(c)) filtered.add(c);
            } else if ("全部".equals(activeGroup) || activeGroup.equals(groupOf(c))) {
                filtered.add(c);
            }
        }

        listBox.removeAllViews();
        rows.clear();
        rowChannels.clear();

        for (int i = 0; i < filtered.size(); i++) {
            final Channel ch = filtered.get(i);
            View row = makeRow(ch, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP));
            lp.bottomMargin = dp(ROW_GAP_DP);
            listBox.addView(row, lp);
            rows.add(row);
            rowChannels.add(ch);
        }
        tvPage.setText("共" + filtered.size() + "台");
        clampFocusInside();
        // ⚠ 顺序很重要：先让目标行拿到焦点，再撤 listBox 的临时可聚焦性；
        // 反过来会把停在 listBox 上的焦点清空 → 又掉回控制栏。
        if (keepIdx >= 0) {
            if (rows.isEmpty()) {
                releaseListFocusHolder();
            } else {
                final int k = Math.min(keepIdx, rows.size() - 1);
                if (rows.get(k).requestFocus()) {
                    releaseListFocusHolder();
                } else {
                    post(() -> {
                        if (closed || k >= rows.size()) return;
                        if (rows.get(k).requestFocus()) releaseListFocusHolder();
                    });
                }
            }
        }
        LogBridge.i(TAG, "频道列表 " + activeGroup + " 共 " + filtered.size()
                + " 台（双栏，随焦点滚动），保留焦点行=" + keepIdx
                + "，构建耗时=" + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 撤掉 listBox 的"临时焦点持有者"身份（焦点已在某行上，撤掉不会丢失焦点） */
    private void releaseListFocusHolder() {
        listBox.setFocusable(false);
        listBox.setFocusableInTouchMode(false);
    }

    /** 当前聚焦行下标；焦点不在列表里返回 -1 */
    private int focusedRowIndex() {
        View f = getRootView().findFocus();
        if (f == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == f) return i;
        }
        return -1;
    }

    /** 行间距（{@link #ROW_HEIGHT_DP} 行高 + {@link #ROW_GAP_DP} 上边距） */
    private int rowPitch() {
        return Math.max(1, dp(ROW_HEIGHT_DP + ROW_GAP_DP));
    }

    /** 一屏可见行数（CH± 与数字键按它滚动） */
    private int visibleRows() {
        int h = listScroll.getHeight();
        if (h <= 0) return 6;
        return Math.max(1, h / rowPitch());
    }

    /** 当前可视区首行下标（数字键「第N个」以它为准） */
    private int firstVisibleRow() {
        int v = listScroll.getScrollY() / rowPitch();
        return Math.max(0, Math.min(v, Math.max(0, rows.size() - 1)));
    }

    /** 聚焦第 idx 行并把它滚进可视区（ScrollView 会自动滚；越界则钳在首/末行） */
    private void focusRow(int idx) {
        if (rows.isEmpty()) return;
        int i = Math.max(0, Math.min(idx, rows.size() - 1));
        lastRowIdx = i;
        boolean ok = rows.get(i).requestFocus();
        LogBridge.i(TAG, "聚焦频道行[" + i + "] 成功=" + ok);
    }

    /** 在分组列内上下移动"虚拟焦点"（越界钳在首/末个） */
    private void moveGroupFocus(int delta) {
        if (tabViews.isEmpty()) return;
        focusZone = 0;
        groupFocusIdx = Math.max(0, Math.min(groupFocusIdx + delta, tabViews.size() - 1));
        restyleGroups();
        restyleRows();
        LogBridge.i(TAG, "分组列焦点 → " + describe(tabViews.get(groupFocusIdx)));
    }

    /** 焦点切到左侧分组列（Android 焦点仍留在频道行上，只切换绘制区） */
    private void enterGroupZone() {
        focusZone = 0;
        int idx = indexOf(tabViews, activeTabView);
        groupFocusIdx = idx >= 0 ? idx : Math.max(0, Math.min(groupFocusIdx, tabViews.size() - 1));
        restyleGroups();
        restyleRows();
        LogBridge.i(TAG, "焦点进入分组列：" + describe(tabViews.get(groupFocusIdx)));
    }

    /** 焦点切回右侧频道列（光标行保持原来的金色焦点态） */
    private void enterRowZone() {
        focusZone = 1;
        restyleGroups();
        restyleRows();
        LogBridge.i(TAG, "焦点回到频道列，第 " + (lastRowIdx + 1) + " 行");
    }

    /** 焦点控件描述（类名 + 文案），用于定位焦点问题 */
    private String describe(View v) {
        if (v == null) return "null";
        String cls = v.getClass().getSimpleName();
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) return cls + ":" + t;
        }
        return cls;
    }

    private int indexOf(List<View> list, View v) {
        if (v == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == v) return i;
        }
        return -1;
    }

    /**
     * 【2026-10-21】方向键在弹窗内自己接管。
     *
     * <p>为什么不让系统做焦点搜索：iOS/Android TV 的默认焦点搜索会跳出弹窗落到控制栏
     * （实测日志「焦点越界 → 拉回」），用户按方向键时焦点乱跳。这里显式约定：
     * <ul>
     *   <li>左列：上下换分组、右键进频道列、左键停住；</li>
     *   <li>右列：上下换频道（自动滚动）、左键回分组列、右键停住；</li>
     *   <li>OK：分组=切分组；频道=切台并关窗。</li>
     * </ul>
     * 方向键与确定键无论是否命中都吃掉（return true），保证焦点绝不出窗。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean nav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (!nav) return super.dispatchKeyEvent(event);
        // 长按重复只处理第一次，避免按住方向键时分组列/频道列被连续推着跑
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            handleNav(code);
        }
        return true;
    }

    private void handleNav(int code) {
        View f = getRootView().findFocus();
        int ri = indexOf(rows, f);
        LogBridge.i(TAG, "按键 code=" + code + " 焦点=" + describe(f)
                + " 区=" + (focusZone == 0 ? "分组" : "频道") + " 行=" + ri);

        // Android 焦点被系统清空/落在弹窗外 → 先收回频道行，再继续处理本次按键。
        // 注意不能直接 return：否则用户在分组列里按上下键会"按了没反应"。
        if (ri < 0) {
            restoreRowFocus();
            if (rows.isEmpty()) return;
            ri = Math.max(0, Math.min(lastRowIdx, rows.size() - 1));
        }
        lastRowIdx = ri;

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (focusZone == 0 && groupFocusIdx < tabViews.size()) {
                activateGroup(groups.get(groupFocusIdx), (TextView) tabViews.get(groupFocusIdx));
            } else if (ri < rowChannels.size()) {
                pick(rowChannels.get(ri));
            }
            return;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focusZone == 1) enterGroupZone();      // 频道列 → 分组列
                break;                                      // 已在最左，停住
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focusZone == 0) enterRowZone();         // 分组列 → 频道列
                break;                                      // 已在最右，停住
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusZone == 0) moveGroupFocus(-1);
                else focusRow(ri - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusZone == 0) moveGroupFocus(1);
                else focusRow(ri + 1);
                break;
            default:
                break;
        }
    }

    /** CH± ：按"一屏可见行数"滚动，而不是老的翻页重建 */
    private void scrollByRows(int delta) {
        if (rows.isEmpty()) return;
        focusRow(firstVisibleRow() + delta);
    }

    /**
     * 焦点锁定在弹窗内：
     * <ul>
     *   <li>左列分组：上下在分组间移动，右键进频道列（同序行）；左键恒指自己，首/末行上下恒指自己。</li>
     *   <li>右列频道：上下在行间移动，左键回左列当前选中的分组；右键恒指自己，首/末行上下恒指自己。</li>
     * </ul>
     */
    private void clampFocusInside() {
        java.util.List<View> tabs = new java.util.ArrayList<>();
        for (View t : tabViews) {
            if (t != null && t.isFocusable()) {
                if (t.getId() == View.NO_ID) t.setId(View.generateViewId());
                tabs.add(t);
            }
        }
        java.util.List<View> list = new java.util.ArrayList<>();
        for (View v : rows) {
            if (v != null && v.isFocusable()) {
                if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
                list.add(v);
            }
        }
        // 左列
        for (int i = 0; i < tabs.size(); i++) {
            View t = tabs.get(i);
            t.setNextFocusLeftId(t.getId());
            t.setNextFocusUpId((i == 0) ? t.getId() : tabs.get(i - 1).getId());
            t.setNextFocusDownId((i == tabs.size() - 1) ? t.getId() : tabs.get(i + 1).getId());
            t.setNextFocusRightId(list.isEmpty()
                    ? t.getId()
                    : list.get(Math.min(i, list.size() - 1)).getId());
        }
        // 右列：左键回当前选中的分组胶囊
        View backTab = activeTabView;
        if (backTab != null && backTab.getId() == View.NO_ID) backTab.setId(View.generateViewId());
        for (int i = 0; i < list.size(); i++) {
            View v = list.get(i);
            v.setNextFocusRightId(v.getId());
            v.setNextFocusLeftId(backTab != null ? backTab.getId() : v.getId());
            v.setNextFocusUpId((i == 0) ? v.getId() : list.get(i - 1).getId());
            v.setNextFocusDownId((i == list.size() - 1) ? v.getId() : list.get(i + 1).getId());
        }
        LogBridge.i(TAG, "【频道列表】焦点已锁定在内（双栏：分组↔频道）");
    }

    private View makeRow(final Channel ch, final int index) {
        final boolean isCur = sameChannel(ch, current);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setPadding(dp(14), 0, dp(14), 0);

        TextView num = text(String.format(Locale.US, "%02d", index + 1), 15,
                isCur ? GOLD : 0xFF9E9E9E, false);
        row.addView(num, new LinearLayout.LayoutParams(dp(30),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = text(ch.getName() == null ? "" : ch.getName(), 14,
                0xFFFFFFFF, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(8);
        row.addView(name, nlp);

        styleRow(row, isCur, false);
        row.setOnClickListener(v -> pick(ch));
        row.setOnFocusChangeListener((v, has) -> {
            // 【2026-10-21】行获焦一律**不**切换 focusZone：
            //   焦点守卫把焦点从 null 拉回行、重建后还原行焦点、ScrollView 滚动抢焦等
            //   都会走到这里；若在此把 zone 改回频道列，用户刚用左键进入的分组列
            //   白描边会立刻消失（实测 BUG：左键进分组后按上下键又跳回金色频道行）。
            //   zone 只由方向键（handleNav）和点击分组（activateGroup）决定。
            styleRow((LinearLayout) v, isCur, has && focusZone == 1);
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            // 数字键 = 「当前可视区第 N 个」（以首个可见行为基准，符合用户直觉）
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                int n = keyCode - KeyEvent.KEYCODE_0;
                int target = firstVisibleRow() + (n == 0 ? 9 : n - 1);
                if (target >= 0 && target < filtered.size()) {
                    pick(filtered.get(target));
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                scrollByRows(-visibleRows());
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                scrollByRows(visibleRows());
                return true;
            }
            return false;
        });
        return row;
    }

    /**
     * 行样式（设计稿）：默认深灰；当前频道稍亮 + 序号黄字；
     * 焦点 = 金色渐变底 + 2dp 白描边 + 深色字。
     */
    private void styleRow(LinearLayout row, boolean isCurrent, boolean focused) {
        GradientDrawable g;
        if (focused) {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{GOLD_TOP, GOLD_BOTTOM});
            g.setCornerRadius(dp(7));
            g.setStroke(dp(2), 0xFFFFFFFF);
        } else {
            g = round(isCurrent ? ITEM_BG_CURRENT : ITEM_BG, 7);
        }
        row.setBackground(g);

        int numberColor = focused ? 0xFF111111 : (isCurrent ? GOLD : 0xFF9E9E9E);
        int nameColor = focused ? 0xFF111111 : 0xFFFFFFFF;

        int textIndex = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            TextView tv = (TextView) c;
            tv.setTextColor(textIndex == 0 ? numberColor : nameColor);
            textIndex++;
        }
    }

    private boolean sameChannel(Channel a, Channel b) {
        if (a == null || b == null) return false;
        String ia = a.getChannelId(), ib = b.getChannelId();
        // ⚠ 源1/源2 的 channelId 是**空串**（不是 null）：直接 equals 会让所有行都被判成
        // "当前频道"（序号全变黄、整列高亮）。空串必须回落到「名称」比较。
        if (!isEmpty(ia) && !isEmpty(ib)) return ia.equals(ib);
        return a.getName() != null && a.getName().equals(b.getName());
    }

    private void pick(Channel ch) {
        LogBridge.i(TAG, "选中频道 " + ch.getName());
        if (onPick != null) onPick.onPick(ch);
        close();
    }

    // ------------------------- 显示/关闭 -------------------------

    public void show() {
        if (root == null || closed) return;
        rebuild(true);
        // 恢复改造前的弹窗尺寸：560dp 宽、贴右下、位于控制栏上方
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(32);
        lp.bottomMargin = dp(132);
        root.addView(this, lp);
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusGuard);

        // 高度不能超过控制栏上方的可用空间，超出时两列各自滚动
        post(this::capBodyHeight);
        // 打开即定位到「正在播放的频道」那一行（找不到才退回第一行）
        focusCurrentRowWithRetry(0);
        LogBridge.i(TAG, "频道列表显示（双栏/560dp），共" + allChannels.size()
                + "台，准备定位当前播放频道");
    }

    /** 标题行 + 内边距 + 底部提示等固定占用（dp），用于精确算主体可用高度 */
    private static final int CHROME_DP = 96;

    /**
     * 把主体高度封顶在「控制栏上方」，避免弹窗顶到屏幕外。
     * 用固定占用值估算，而不是拿上一次 getHeight 反推 —— 否则标签行会被压掉半行。
     */
    private void capBodyHeight() {
        if (closed || body == null || root == null) return;
        int rh = root.getHeight();
        if (rh <= 0) {
            if (capRetry++ < 10) postDelayed(this::capBodyHeight, 80);
            return;
        }
        int maxBody = Math.max(dp(40), rh - dp(132) - dp(16) - dp(CHROME_DP));
        int need = rows.size() * rowPitch() - dp(ROW_GAP_DP);
        int h = Math.max(dp(40), Math.min(maxBody, need));
        ViewGroup.LayoutParams lp = body.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            body.setLayoutParams(lp);
            LogBridge.i(TAG, "弹窗主体高度=" + h + "px（上限=" + maxBody + "px，内容=" + need + "px）");
        }
    }

    /**
     * 打开时把焦点放到正在播放的频道行。
     * View 刚 add 进来时可能还没完成布局 → requestFocus 会失败，所以按 150ms 重试，
     * 直到焦点确实落在弹窗内为止（焦点一旦进弹窗就不再抢，尊重用户操作）。
     */
    private void focusCurrentRowWithRetry(int attempt) {
        if (closed || !isShowing()) return;
        if (hasFocusInside()) return;
        if (focusCurrentRow()) return;
        if (attempt < 8) {
            postDelayed(() -> focusCurrentRowWithRetry(attempt + 1), 150);
        } else {
            LogBridge.w(TAG, "打开后未能把焦点定位到当前频道行");
        }
    }

    /** 焦点被系统清空/越界时，把 Android 焦点收回上一次的频道行（不改变 focusZone） */
    private void restoreRowFocus() {
        if (rows.isEmpty()) return;
        int i = Math.max(0, Math.min(lastRowIdx, rows.size() - 1));
        boolean ok = rows.get(i).requestFocus();
        LogBridge.i(TAG, "焦点被清空/越界 → 拉回频道行[" + i + "] 成功=" + ok);
    }

    /** 定位并聚焦「正在播放」的频道；无匹配时聚焦第一行 */
    private boolean focusCurrentRow() {
        if (rows.isEmpty()) return false;
        int idx = 0;
        for (int i = 0; i < filtered.size(); i++) {
            if (sameChannel(filtered.get(i), current)) {
                idx = i;
                break;
            }
        }
        lastRowIdx = idx;
        boolean ok = rows.get(idx).requestFocus();
        if (ok) {
            // 双保险：ScrollView 在 requestFocus 时会自动滚动，这里再显式滚一次，
            // 避免"焦点到了但行还在可视区外"。
            final int y = Math.max(0, idx * rowPitch() - rowPitch());
            listScroll.post(() -> listScroll.scrollTo(0, y));
            LogBridge.i(TAG, "已定位到正在播放频道：第" + (idx + 1) + " 行 " + current.getName());
        }
        return ok;
    }

    /** 焦点是否已经在弹窗内 */
    private boolean hasFocusInside() {
        View f = getRootView().findFocus();
        return f != null && isDescendantOf(f, this);
    }

    private boolean isDescendantOf(View v, View ancestor) {
        View cur = v;
        while (cur != null) {
            if (cur == ancestor) return true;
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        try {
            getViewTreeObserver().removeOnGlobalFocusChangeListener(focusGuard);
        } catch (Throwable ignored) {
        }
        // 【2026-10-17】先 onClose 把焦点还给触发按钮，再移除视图；否则 removeView 删掉焦点所有者，
        // 系统会把焦点丢给控制栏「‹ 上一频道」，60ms 后才纠正（用户看到焦点跳走）。
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
