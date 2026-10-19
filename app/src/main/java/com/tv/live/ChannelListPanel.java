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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tv.live.util.LogBridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 【2026-10-17 直播频道列表抽屉】
 *
 * <p>直播形态的"选集"：贴右侧竖排，顶部按分组过滤（全部 + 源里的 group-title），
 * 每行 = 频道号 + 频道名 + 当前节目；当前频道黄底（不抢焦点色，焦点才黄底）。
 * 支持 CH± 翻页、数字键直输频道号、**焦点锁定在抽屉内**（只能按返回键回控制栏，由宿主统一处理）。
 */
public class ChannelListPanel extends LinearLayout {

    private static final String TAG = "ChannelList";
    private static final int ROWS = 6;

    public interface OnPick {
        void onPick(Channel channel);
    }

    public interface OnClose {
        void onClose();
    }

    /** 由宿主提供节目名（可为 null） */
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
    /** 首屏高度测量失败时的重试次数（避免无限 postDelayed） */
    private int capRetry;
    private boolean closed;

    private OnPick onPick;
    private OnClose onClose;

    private LinearLayout listBox, tabRow;
    /** 页签行容器：分组多时横向滚动，避免后面的分组被挤出抽屉 */
    private android.widget.HorizontalScrollView svTabs;
    /** 列表容器：让焦点移动能把目标行滚进可视区（行数超出抽屉高度时必需） */
    private android.widget.ScrollView listScroll;
    private TextView tvPage;
    private final List<View> rows = new ArrayList<>();
    private final List<Channel> rowChannels = new ArrayList<>();
    private List<Channel> filtered = new ArrayList<>();

    public ChannelListPanel(Context context, FrameLayout root, List<Channel> channels,
                            Channel current, ProgramLookup lookup) {
        super(context);
        this.root = root;
        this.allChannels = new ArrayList<>(channels);
        this.current = current;
        this.lookup = lookup;
        setOrientation(VERTICAL);
        setBackground(round(0xFF1A1A1A, 16));
        setPadding(dp(24), dp(18), dp(24), dp(18));
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
     * 频道 → 抽屉里的分组名。
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
            // 【2026-10-17】与主界面分组同一口径（见 groupOf）。
            String g = groupOf(c);
            if (isEmpty(g) || "全部".equals(g)) continue;
            set.add(g);
        }
        // 【2026-10-17】去掉「只取前 4 个分组」的上限：只取 4 个会导致后面的分组
        // 根本不出现在抽屉里（用户实测"还有分组没有显示"）。
        // 页签行已放进 HorizontalScrollView，分组再多也能横向滚动。
        groups.addAll(set);
        LogBridge.i(TAG, "频道列表分组共 " + groups.size() + " 个：" + groups);
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void build() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("频道列表", 18, 0xFF9E9E9E, false);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvPage = text("", 13, 0xFFFFD166, false);
        header.addView(tvPage);

        tabRow = new LinearLayout(getContext());
        tabRow.setOrientation(HORIZONTAL);
        // 【2026-10-17】页签行放进横向滚动容器：源1/源2 有 卫视/总台/央视/地方… 多个分组，
        // 抽屉只有 560dp 宽，直接 addView 会把后面的分组挤到屏幕外（"还有分组没有显示"）。
        // 放在 HorizontalScrollView 里，焦点移到哪个页签就自动横向滚过去。
        svTabs = new android.widget.HorizontalScrollView(getContext());
        svTabs.setHorizontalScrollBarEnabled(false);
        svTabs.setFocusable(false);
        svTabs.addView(tabRow, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        addView(svTabs, tlp);
        buildTabs();

        listBox = new LinearLayout(getContext());
        listBox.setOrientation(VERTICAL);
        // 【2026-10-17】列表包一层 ScrollView：抽屉高度被屏幕限制时，
        // 焦点移动要能把目标行"滚进可视区"（ScrollView.requestChildFocus 自带此行为）。
        // 之前直接 addView(listBox)：行数超出可视高度时既不能滚、焦点也带不动列表。
        listScroll = new android.widget.ScrollView(getContext());
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setFocusable(false);
        listScroll.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        addView(listScroll, slp);

        TextView hint = text("CH± 翻页 · 数字键输频道号 · 返回键回到控制栏", 12, 0xFF5F5F5F, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        addView(hint, hlp);
    }

    private void buildTabs() {
        tabRow.removeAllViews();
        for (final String g : groups) {
            TextView tab = new TextView(getContext());
            tab.setText(g);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setGravity(Gravity.CENTER);
            tab.setIncludeFontPadding(false);
            tab.setSingleLine(true);
            tab.setPadding(dp(14), 0, dp(14), 0);
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
            styleTab(tab, g.equals(activeGroup), false);
            tab.setOnClickListener(v -> {
                activeGroup = g;
                // 【2026-10-17 修复「点分组后焦点跳回上一频道」】
                // 原来这里调 buildTabs() = tabRow.removeAllViews()，把**正在被聚焦的这个页签**
                // 一起删掉 → 窗口里焦点所有者消失，Android 把焦点交给层级里第一个可聚焦控件
                // （就是控制栏的「‹ 上一频道」）。现在改成只重刷样式，页签对象不动：
                // 焦点原地不动，且不会出现"接下来按确认键又落回全部"的连带问题。
                restyleTabs();
                rebuild();
                capListHeight();
                listScroll.scrollTo(0, 0);   // 换分组回到列表顶部
                v.requestFocus();
            });
            tab.setOnFocusChangeListener((v, has) -> styleTab((TextView) v, g.equals(activeGroup), has));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.rightMargin = dp(8);
            tabRow.addView(tab, lp);
        }
    }

    /** 只重刷页签样式，不重建视图（重建会把焦点所有者删掉 → 焦点掉到控制栏） */
    private void restyleTabs() {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View c = tabRow.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            TextView tv = (TextView) c;
            String label = tv.getText() == null ? "" : tv.getText().toString();
            styleTab(tv, label.equals(activeGroup), tv.isFocused());
        }
    }

    private void styleTab(TextView tv, boolean selected, boolean focused) {
        int fill;
        int color;
        if (focused) {
            fill = 0xFFFFD166;
            color = 0xFF111111;
        } else if (selected) {
            fill = 0xFF2E2E2E;
            color = 0xFFFFD166;
        } else {
            fill = 0xFF2E2E2E;
            color = 0xFFCFCFCF;
        }
        GradientDrawable g = round(fill, 16);
        if (focused) g.setStroke(dp(2), 0xFFFFFFFF);
        tv.setBackground(g);
        tv.setTextColor(color);
    }

    // ------------------------- 列表 -------------------------

    /**
     * 【2026-10-17 连续滚动列表】一次把当前页签的**全部频道**铺进 ScrollView，
     * 不再按 6 条一页切片。
     *
     * <p>原实现是「6 行/页 + 重建式翻页」：翻页时 removeAllViews 会把带焦点的行一起删掉，
     * Android 只能把焦点丢给最近的控件（实测丢到控制栏「上一频道」一帧，日志可见
     * `焦点=‹ 上一频道`），列表看起来就是不跟焦点走。改成连续列表后：
     * 焦点在行间移动 → ScrollView 自动把目标行滚进可视区（requestChildFocus），
     * 视觉上就是「列表跟着按键滚动」，且不再有任何重建/失焦。
     */
    private void rebuild() {
        long t0 = System.currentTimeMillis();
        // 【2026-10-17 修复焦点跳走】removeAllViews 会删掉"当前被聚焦的那一行"，
        // 焦点所有者消失后 Android 只能把焦点交给层级里第一个可聚焦控件
        // （实测 = 控制栏「‹ 上一频道」）。所以重建前先记住焦点行下标，重建后立刻还原。
        final int keepIdx = focusedRowIndex();
        if (keepIdx >= 0) {
            // 重建期间让 listBox 自己临时持有焦点：焦点始终留在抽屉内，不会"跑出去再被拉回来"
            listBox.setFocusableInTouchMode(true);
            listBox.setFocusable(true);
            listBox.requestFocus();
        }

        filtered = new ArrayList<>();
        boolean favTab = "收藏".equals(activeGroup);
        for (Channel c : allChannels) {
            if (favTab) {
                if (favLookup != null && favLookup.isFavorite(c)) filtered.add(c);
            } else if ("全部".equals(activeGroup)
                    || activeGroup.equals(groupOf(c))) {
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
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            lp.topMargin = dp(6);
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
                + " 台（连续列表，随焦点滚动），保留焦点行=" + keepIdx
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

    /** 行间距（40dp 行高 + 6dp 上边距），用于按 scrollY 反推"当前看到第几行" */
    private int rowPitch() {
        return Math.max(1, dp(46));
    }

    /** 当前可视区首行下标（数字键「第N个」以它为准） */
    private int firstVisibleRow() {
        int v = listScroll.getScrollY() / rowPitch();
        return Math.max(0, Math.min(v, Math.max(0, rows.size() - 1)));
    }

    /** 聚焦第 idx 行并把它滚进可视区（ScrollView 会自动滚） */
    private void focusRow(int idx) {
        if (rows.isEmpty()) return;
        int i = Math.max(0, Math.min(idx, rows.size() - 1));
        rows.get(i).requestFocus();
    }

    /** CH± ：按"一屏 6 行"滚动，而不是老的翻页重建 */
    private void scrollByRows(int delta) {
        if (rows.isEmpty()) return;
        int target = firstVisibleRow() + delta;
        focusRow(target);
    }

    /**
     * 【2026-10-17 用户口径】焦点**锁死在频道列表抽屉内**，只能按返回键回控制栏。
     *
     * <ul>
     *   <li>行：左右恒指自己（抽屉在右侧，右键会跑出去）；末行下键指自己
     *       （下面就是控制栏）；首行上键指向抽屉自己的页签行（仍在抽屉内）。</li>
     *   <li>页签：上键指自己（防跳出抽屉上沿），下键落第一行，左右仍可在页签间移动。</li>
     * </ul>
     */
    private void clampFocusInside() {
        // 页签
        java.util.List<View> tabs = new java.util.ArrayList<>();
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View t = tabRow.getChildAt(i);
            if (t != null && t.isFocusable()) {
                if (t.getId() == View.NO_ID) t.setId(View.generateViewId());
                tabs.add(t);
            }
        }
        // 行
        java.util.List<View> list = new java.util.ArrayList<>();
        for (View v : rows) {
            if (v != null && v.isFocusable()) {
                if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
                list.add(v);
            }
        }
        View firstTab = tabs.isEmpty() ? null : tabs.get(0);
        for (View t : tabs) {
            t.setNextFocusUpId(t.getId());
            if (!list.isEmpty()) t.setNextFocusDownId(list.get(0).getId());
        }
        for (int i = 0; i < list.size(); i++) {
            View v = list.get(i);
            v.setNextFocusLeftId(v.getId());
            v.setNextFocusRightId(v.getId());
            v.setNextFocusUpId((i == 0)
                    ? (firstTab != null ? firstTab.getId() : v.getId())
                    : list.get(i - 1).getId());
            v.setNextFocusDownId((i == list.size() - 1) ? v.getId() : list.get(i + 1).getId());
        }
        LogBridge.i(TAG, "【频道列表】焦点已锁定在内（返回键才回控制栏）");
    }

    private View makeRow(final Channel ch, final int index) {
        final boolean isCur = sameChannel(ch, current);
        FrameLayout row = new FrameLayout(getContext());
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);

        TextView num = text(String.format(Locale.US, "%02d", index + 1), 12,
                isCur ? 0xFF111111 : 0xFF9E9E9E, false);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        nlp.leftMargin = dp(14);
        row.addView(num, nlp);

        TextView name = text(ch.getName() == null ? "" : ch.getName(), 14,
                isCur ? 0xFF111111 : 0xFFFFFFFF, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams nmlp = new FrameLayout.LayoutParams(
                dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        nmlp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        nmlp.leftMargin = dp(44);
        row.addView(name, nmlp);

        String prog = (lookup == null) ? null : lookup.nowPlaying(ch);
        if (prog != null && !prog.isEmpty()) {
            TextView tvProg = text(prog, 11, isCur ? 0xFF3A3A2A : 0xFF7A7A7A, false);
            tvProg.setSingleLine(true);
            tvProg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    dp(180), ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            plp.rightMargin = dp(12);
            row.addView(tvProg, plp);
        }

        styleRow(row, isCur, false);
        row.setOnClickListener(v -> pick(ch));
        row.setOnFocusChangeListener((v, has) -> styleRow((FrameLayout) v, isCur, has));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            // 【2026-10-17 连续列表】不再有"翻页重建"：上下键由系统在行间移动，
            // ScrollView 自动把目标行滚进可视区 → 列表跟着焦点滚动，且不会丢焦点。
            // 数字键 = 「当前可视区第 N 个」（以首个可见行为基准，符合用户直觉）。
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                int n = keyCode - KeyEvent.KEYCODE_0;
                int target = firstVisibleRow() + (n == 0 ? 9 : n - 1);
                if (target >= 0 && target < filtered.size()) {
                    pick(filtered.get(target));
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                scrollByRows(-ROWS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                scrollByRows(ROWS);
                return true;
            }
            return false;
        });
        return row;
    }

    /**
     * 行样式：**每种状态都要把三个子 View 的颜色明确写全**。
     * 原实现只在"焦点/当前"时改色，失焦后没有恢复 → 文字停在上一次的黑字，
     * 在深色行上完全看不清（用户截图实测：移动焦点后整行变黑字）。
     */
    private void styleRow(FrameLayout row, boolean isCurrent, boolean focused) {
        int fill;
        if (focused) fill = 0xFFFFD166;
        else if (isCurrent) fill = 0xFF2E2E2E;
        else fill = 0xFF2A2A2A;
        GradientDrawable g = round(fill, 10);
        if (focused) g.setStroke(dp(2), 0xFFFFFFFF);
        row.setBackground(g);

        int numberColor = focused ? 0xFF111111 : (isCurrent ? 0xFFFFD166 : 0xFF9E9E9E);
        int nameColor = focused ? 0xFF111111 : 0xFFFFFFFF;
        int progColor = focused ? 0xCC111111 : (isCurrent ? 0xFFFFD166 : 0xFF7A7A7A);

        int textIndex = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            TextView tv = (TextView) c;
            int color;
            if (textIndex == 0) color = numberColor;        // 频道号
            else if (textIndex == 1) color = nameColor;     // 频道名
            else color = progColor;                          // 当前节目
            tv.setTextColor(color);
            textIndex++;
        }
    }

    private boolean sameChannel(Channel a, Channel b) {
        if (a == null || b == null) return false;
        String ia = a.getChannelId(), ib = b.getChannelId();
        if (ia != null && ib != null) return ia.equals(ib);
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
        rebuild();
        capListHeight();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(560), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(32);
        lp.bottomMargin = dp(20 + 96 + 16);
        root.addView(this, lp);
        // 打开时把焦点定位到「当前频道」那一行；ScrollView 会自动滚到它。
        // 找不到就聚焦第一行（焦点不能留在控制栏，否则等于抽屉没接管按键）。
        int focusIdx = 0;
        for (int i = 0; i < filtered.size(); i++) {
            if (sameChannel(filtered.get(i), current)) {
                focusIdx = i;
                break;
            }
        }
        final int fi = focusIdx;
        postDelayed(() -> {
            if (closed) return;
            if (!rows.isEmpty()) rows.get(Math.min(fi, rows.size() - 1)).requestFocus();
        }, 250);
        LogBridge.i(TAG, "频道列表显示，共" + allChannels.size() + "台，定位第" + (fi + 1) + "行");
    }

    /**
     * 列表高度封顶：抽屉总高不得超过「控制栏顶边以上的可用空间」。
     *
     * <p>超出时让 {@link #listScroll} 内部滚动 —— 焦点移动会把目标行自动滚进可视区，
     * 这就是「列表跟着焦点滚动」；之前 wrap_content 顶出屏幕后既滚不了也看不到。
     * 根布局还没测量完（height<=0）时延后一帧重试。
     */
    private void capListHeight() {
        try {
            int avail = (root == null) ? 0 : root.getHeight();
            if (avail <= 0) {
                if (capRetry++ < 12) postDelayed(this::capListHeight, 80);
                return;
            }
            capRetry = 0;
            int panelMax = avail - dp(20 + 96 + 16);       // 底边距 = 控制栏顶 − 16dp
            int chrome = dp(30 + 10 + 32 + 10 + 22 + 20);  // 标题/页签/提示/内边距
            int listMax = Math.max(dp(92), panelMax - chrome);
            int need = rows.size() * dp(46);
            int h = Math.min(listMax, Math.max(need, dp(92)));
            ViewGroup.LayoutParams slp = listScroll.getLayoutParams();
            if (slp != null && slp.height != h) {
                slp.height = h;
                listScroll.setLayoutParams(slp);
                LogBridge.i(TAG, "列表可视高=" + h + "px（需要 " + need + "px，上限 " + listMax + "px）");
            }
        } catch (Throwable ignored) {
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
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
