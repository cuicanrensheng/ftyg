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
import java.util.List;

/**
 * 【2026-10-17 OTT 右侧抽屉 · v1.6】清晰度 / 线路 / 倍速 共用。
 *
 * <p>与爱奇艺 / 优酷 / 云视听 TV 一致：设置面板贴**右侧竖排**，控制栏仍是底部一条，
 * 两者在空间上不重叠（抽屉底边 = 控制栏顶边 − 16dp）。
 *
 * <ul>
 *   <li>清晰度 / 倍速：竖向胶囊单列（高 52、r26、选中黄底 + ✓）</li>
 *   <li>线路：两行信息行（高 64、r12，上行「线路N · host」，右下状态「当前/可用/较慢/未探测」）</li>
 * </ul>
 */
public class CapsuleChoicePanel extends LinearLayout {

    private static final String TAG = "DrawerPanel";

    public interface OnPick {
        void onPick(int index);
    }

    public interface OnClose {
        void onClose();
    }

    public static class Item {
        // 【2026-10-18】去 final：二级弹窗切换选项后宿主回写一级面板显示（updateItemLabel）
        public String label;
        public final String sub;      // 第二行（线路 host / 说明），非空则按两行信息行渲染
        public final String status;   // 右侧状态标签
        public final boolean selected;
        public final boolean warn;
        public final boolean enabled;

        public Item(String label, String sub, String status,
                    boolean selected, boolean warn, boolean enabled) {
            this.label = label;
            this.sub = sub;
            this.status = status;
            this.selected = selected;
            this.warn = warn;
            this.enabled = enabled;
        }

        /** 单行胶囊（清晰度 / 倍速） */
        public static Item capsule(String label, boolean selected, boolean enabled) {
            return new Item(label, null, null, selected, false, enabled);
        }

        /** 两行信息行（线路） */
        public static Item line(String label, String sub, String status,
                                boolean selected, boolean warn, boolean enabled) {
            return new Item(label, sub, status, selected, warn, enabled);
        }
    }

    private final List<Item> items;
    private final List<View> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OnPick onPick;
    private OnClose onClose;
    private FrameLayout host;
    private android.widget.ScrollView scroll;
    private boolean closed;
    /** 【2026-10-18 两级导航】true = pick 后不自动关闭（一级菜单用），BACK 由宿主逐层关闭 */
    private boolean keepOpenAfterPick;

    public CapsuleChoicePanel(Context context, String title, String subtitle, List<Item> items) {
        super(context);
        this.items = items;
        setOrientation(VERTICAL);
        setBackground(round(0xFF1A1A1A, 16));
        setPadding(dp(28), dp(20), dp(28), dp(20));
        setFocusable(false);
        build(title, subtitle);
    }

    public CapsuleChoicePanel onPick(OnPick p) {
        this.onPick = p;
        return this;
    }

    /** 【2026-10-18 两级导航】一级菜单设 true：pick 后保持显示（BACK 由宿主逐层关闭） */
    public CapsuleChoicePanel keepOpenAfterPick(boolean v) {
        this.keepOpenAfterPick = v;
        return this;
    }

    public CapsuleChoicePanel onClose(OnClose c) {
        this.onClose = c;
        return this;
    }

    private void build(String title, String subtitle) {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = text(title, 18, 0xFF9E9E9E, false);
        tvTitle.setSingleLine(true);   // 【2026-10-18】防长副标题把标题挤成竖排
        header.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (subtitle != null && subtitle.length() > 0) {
            TextView tvSub = text(subtitle, 13, 0xFF6A6A6A, false);
            tvSub.setSingleLine(true);
            header.addView(tvSub);
        }

        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(VERTICAL);
        scroll = new android.widget.ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(14);
        addView(scroll, llp);

        View firstEnabled = null;
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final Item item = items.get(i);
            View row = item.sub == null ? buildCapsuleRow(item) : buildInfoRow(item);
            boolean selectable = item.enabled;
            row.setFocusable(selectable);
            row.setFocusableInTouchMode(selectable);
            if (selectable) {
                row.setOnClickListener(v -> pick(index));
                row.setOnFocusChangeListener((v, has) -> applyRowStyle(v, item, has));
                row.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    // BACK 不在这里处理：统一由 Activity.onKeyDown 收口（两处处理会执行两次，
                    // 实测会把控制栏也一起收掉、直接退出全屏）。
                    // 【2026-10-17 用户口径】左右键**不再收起抽屉**：抽屉是右侧栏，
                    // 按左键就会把焦点送到控制栏，而规则要求「只能按返回键回控制栏」。
                    // 这里直接吃掉左右键，焦点留在原行（视觉上无变化，符合"到底不动"）。
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        return true;
                    }
                    // 【2026-10-19 焦点逃逸修复】上下键由面板自己吃掉并显式移动焦点：
                    // 放行给视图树时，内部 ScrollView 的箭头滚动会先抢走事件，
                    // 焦点直接跳出抽屉落到顶部「分类 Tab」（用户实测：按↓从行内跳到"电影恐怖片"）。
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        moveFocusWithin(index, 1);
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        moveFocusWithin(index, -1);
                        return true;
                    }
                    return false;
                });
                if (firstEnabled == null) firstEnabled = row;
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(item.sub == null ? 52 : 64));
            rlp.topMargin = dp(10);
            list.addView(row, rlp);
            rows.add(row);
        }
        clampFocusInside();
        setTag(firstEnabled);
    }

    /**
     * 【2026-10-17 用户口径】焦点**锁死在抽屉内**：到底不再跳到控制栏，左右也不出界。
     * 只有 BACK 键才能把焦点交回控制栏（由 Activity.onKeyDown 统一收口）。
     *
     * <p>为什么必须显式写 nextFocus*：抽屉贴在右侧、控制栏在下方，
     * 系统 FocusFinder 在「最后一行按下键」「最右一行按右键」时会一路搜到控制栏按钮，
     * 焦点就"直接跑到控制栏"了。这里把四个方向都钉在抽屉内部：
     * 上下相邻、首行上键/末行下键指回自己、左右恒指自己。
     */
    private void clampFocusInside() {
        java.util.List<View> focusables = new java.util.ArrayList<>();
        for (View v : rows) {
            if (v != null && v.isFocusable()) focusables.add(v);
        }
        if (focusables.isEmpty()) return;
        // 第一遍：确保都有 id（nextFocus*Id 必须引用真实 id）
        for (View v : focusables) {
            if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        }
        // 第二遍：钉住四向
        for (int i = 0; i < focusables.size(); i++) {
            View v = focusables.get(i);
            v.setNextFocusLeftId(v.getId());
            v.setNextFocusRightId(v.getId());
            v.setNextFocusUpId((i == 0) ? v.getId() : focusables.get(i - 1).getId());
            v.setNextFocusDownId((i == focusables.size() - 1)
                    ? v.getId() : focusables.get(i + 1).getId());
        }
        LogBridge.i(TAG, "【抽屉】焦点已锁定在内，共 "
                + focusables.size() + " 行（返回键才回控制栏）");
    }

    /**
     * 【2026-10-19】抽屉内部按上下键时把焦点移到相邻的**可聚焦**行（跳过只读信息行）；
     * 到顶/到底就不动 —— 焦点始终留在抽屉内，不会逃到顶部 Tab 或控制栏。
     */
    private void moveFocusWithin(int fromIndex, int dir) {
        for (int i = fromIndex + dir; i >= 0 && i < rows.size(); i += dir) {
            View v = rows.get(i);
            if (v != null && v.isFocusable()) {
                v.requestFocus();
                LogBridge.i(TAG, "【抽屉】焦点移动 " + fromIndex + " → " + i);
                return;
            }
        }
        LogBridge.i(TAG, "【抽屉】焦点已到头（index=" + fromIndex + " dir=" + dir + "），保持在原行");
    }

    /** 清晰度 / 倍速：单行胶囊（左对齐，像列表而不是按钮组） */
    private View buildCapsuleRow(Item item) {
        TextView tv = new TextView(getContext());
        tv.setText(item.label + (item.selected ? "   ✓" : ""));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setPadding(dp(24), 0, dp(24), 0);
        applyRowStyle(tv, item, false);
        return tv;
    }

    /** 线路：两行信息行（上行 线路N · host，右下状态标签） */
    private View buildInfoRow(Item item) {
        FrameLayout row = new FrameLayout(getContext());
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tlp.leftMargin = dp(20);
        texts.setPadding(0, dp(8), 0, dp(8));
        row.addView(texts, tlp);

        TextView title = new TextView(getContext());
        title.setText(item.label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        title.setTag("row_title");
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(getContext());
        sub.setText(item.sub == null ? "" : item.sub);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setSingleLine(true);
        sub.setIncludeFontPadding(false);
        sub.setTag("row_sub");
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        texts.addView(sub, slp);

        if (item.status != null && item.status.length() > 0) {
            TextView status = new TextView(getContext());
            status.setText(item.status + (item.selected ? " ✓" : ""));
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            status.setSingleLine(true);
            status.setTag("row_status");
            FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stp.gravity = Gravity.END | Gravity.BOTTOM;
            stp.rightMargin = dp(20);
            stp.bottomMargin = dp(12);
            row.addView(status, stp);
        }

        applyRowStyle(row, item, false);
        return row;
    }

    /** 抽屉挂到根布局：贴右、底边 = 控制栏顶边 − 16dp */
    public void showIn(FrameLayout root, int widthDp, int bottomMarginPx) {
        if (root == null || closed) return;
        this.host = root;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(32);
        lp.bottomMargin = bottomMarginPx + dp(16);
        // 【限高】抽屉底边到屏幕顶的可用高度：超出部分内部滚动，绝不压到控制栏
        int maxH = root.getHeight() - lp.bottomMargin - dp(24);
        if (maxH > dp(160) && scroll != null) {
            int contentH = 0;
            for (Item it : items) contentH += dp(it.sub == null ? 52 : 64) + dp(10);
            int listMax = Math.max(dp(120), maxH - dp(120));   // 减去标题与内距
            if (contentH > listMax) {
                scroll.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, listMax));
                LogBridge.i(TAG, "线路/清晰度列表限高 " + listMax + "px，启用内部滚动");
            }
        }
        root.addView(this, lp);
        // 【2026-10-18 焦点瞬态修复】addView 后**同步**抢焦点：原来延迟 150ms，
        //   窗口内焦点被系统丢给控制栏「上一频道」（用户看到焦点闪跳再进面板）。
        //   二级面板叠加时宿主会在 prev GONE 前显式转移焦点，同样依赖这里的同步目标。
        View it0 = initialFocusTarget();
        if (it0 != null) it0.requestFocus();
        postDelayed(() -> {
            View target = initialFocusTarget();
            boolean ok = target != null && target.requestFocus();
            LogBridge.i(TAG, "抽屉显示 " + items.size() + " 项，抢焦点=" + ok);
        }, 150);
    }

    /** 【2026-10-18 两级导航】同步取初始焦点目标：选中项优先，否则第一个可聚焦行 */
    public View initialFocusTarget() {
        View target = null;
        for (int i = 0; i < rows.size(); i++) {
            if (items.get(i).selected && rows.get(i).isFocusable()) {
                target = rows.get(i);
                break;
            }
        }
        return target != null ? target : (View) getTag();
    }

    public void close() {
        if (closed) return;
        closed = true;
        LogBridge.i(TAG, "close() 1 removeCallbacks");
        handler.removeCallbacksAndMessages(null);
        // 【2026-10-17】先 onClose（把焦点还给触发按钮）再移除视图。
        // 反过来的话：removeView 会删掉焦点所有者 → 系统立刻把焦点丢给控制栏第一颗按钮
        // （‹ 上一频道 / ‹ 上一集），要到 60ms 后才被「焦点回位」纠正 —— 用户看到的就是
        // 「按返回键焦点跳到上一频道」。
        LogBridge.i(TAG, "close() 2 onClose（先还焦点）");
        if (onClose != null) onClose.onClose();
        LogBridge.i(TAG, "close() 3 removeView");
        if (host != null) host.removeView(this);
        LogBridge.i(TAG, "close() 4 done");
    }

    public boolean isShowing() {
        return !closed && getParent() != null;
    }

    private void pick(int index) {
        LogBridge.i(TAG, "抽屉选中 index=" + index + " " + items.get(index).label);
        if (onPick != null) onPick.onPick(index);
        // 【2026-10-18 两级导航】一级菜单 keepOpen：pick 后保持显示，由宿主叠加二级面板；
        // BACK 由宿主逐层关闭（close → onClose → 栈管理 → 露出本层并 resumeFocus）
        if (!keepOpenAfterPick) close();
    }

    /** 【2026-10-18 两级导航】BACK 返回上一层时：把焦点还给本层（选中项优先，否则第一个可聚焦行） */
    public void resumeFocus() {
        View t = initialFocusTarget();
        if (t != null) t.requestFocus();
    }

    /**
     * 【2026-10-18】二级弹窗切换选项后刷新本面板某行文案与样式
     * （宿主先回写 items.get(index).label，再调本方法重绘该行）。
     */
    public void refreshItem(int index) {
        if (index < 0 || index >= rows.size() || index >= items.size()) return;
        Item it = items.get(index);
        View row = rows.get(index);
        if (row instanceof TextView) {
            ((TextView) row).setText(it.label);
            styleCapsule((TextView) row, it, row.hasFocus());
        } else if (row instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) row;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof TextView) {
                    ((TextView) c).setText(it.label);
                    break;
                }
            }
        }
    }

    // ------------------------- 样式 -------------------------

    private void applyRowStyle(View row, Item item, boolean focused) {
        if (row instanceof TextView) {
            styleCapsule((TextView) row, item, focused);
            return;
        }
        int fill;
        int titleColor;
        if (!item.enabled) {
            fill = 0xFF222222;
            titleColor = 0xFF6A6A6A;
        } else if (focused) {
            // 【2026-10-23 回退】曾改为 OTT 蓝 #FF1E7BE0，用户要求改回原金色口径。
            fill = 0xFFFFD166;
            titleColor = 0xFF111111;
        } else if (item.selected) {
            fill = 0xFF2E2E2E;
            titleColor = 0xFFFFD166;   // 当前线路：黄字，但不抢焦点黄底
        } else if (item.warn) {
            fill = 0xFF2E2E2E;
            titleColor = 0xFFFFB4A2;
        } else {
            fill = 0xFF2E2E2E;
            titleColor = 0xFFFFFFFF;
        }
        GradientDrawable g = round(fill, 12);
        if (focused) g.setStroke(dp(2), 0xFFFFFFFF);
        row.setBackground(g);
        TextView title = row.findViewWithTag("row_title");
        if (title != null) title.setTextColor(titleColor);
        TextView sub = row.findViewWithTag("row_sub");
        if (sub != null) {
            sub.setTextColor((focused || item.selected) ? 0xCC111111 : 0xFF7A7A7A);
        }
        TextView status = row.findViewWithTag("row_status");
        if (status != null) {
            int c;
            if (focused || item.selected) c = 0xFF111111;
            else if (item.warn) c = 0xFFFFB4A2;
            else if (!item.enabled) c = 0xFF5F5F5F;
            else c = 0xFF9E9E9E;
            status.setTextColor(c);
        }
    }

    /**
     * 【焦点唯一】只有 focused 才是黄底；选中项用「深底 + 黄字 + ✓」表示，
     * 否则选中项和焦点项同时发黄，看起来像两个焦点（用户实测截图指出）。
     */
    private void styleCapsule(TextView tv, Item item, boolean focused) {
        int fill;
        int textColor;
        if (!item.enabled) {
            fill = 0xFF222222;
            textColor = 0xFF6A6A6A;
        } else if (focused) {
            // 【2026-10-23 回退】曾改为 OTT 蓝 #FF1E7BE0，用户要求改回原金色口径。
            fill = 0xFFFFD166;
            textColor = 0xFF111111;
        } else if (item.selected) {
            fill = 0xFF2E2E2E;
            textColor = 0xFFFFD166;   // 选中但未聚焦：黄字 + 尾部 ✓
        } else {
            fill = 0xFF2E2E2E;
            textColor = 0xFFFFFFFF;
        }
        GradientDrawable g = round(fill, 26);   // 胶囊：r = 高(52) ÷ 2
        if (focused) g.setStroke(dp(2), 0xFFFFFFFF);
        tv.setBackground(g);
        tv.setTextColor(textColor);
    }

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
