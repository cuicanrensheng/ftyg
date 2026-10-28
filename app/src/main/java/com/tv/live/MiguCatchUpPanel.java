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

import com.tv.live.migu.source.CarouselApi;
import com.tv.live.util.AppExecutors;
import com.tv.live.util.LogBridge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 【2026-10-22 源6 咪咕·回看弹窗】<b>完全照搬</b>「直播控制栏 → 频道列表」弹窗
 * （{@link ChannelListPanel}）的设计：同样的 560dp 贴右下布局、深灰圆角底、左列胶囊 + 右列列表
 * 双栏结构、金色渐变焦点 + 2dp 白描边、虚拟焦点区、方向键/确认键全接管、主体高度封顶、
 * 分片构建防 ANR。
 *
 * <p>差异只在数据与行内容 —— 咪咕剧场没有集数，只有「按天回看」（今天 + 过去 7 天，共 8 天）：
 * <pre>
 *  ┌ 回看 · 漫威                                          共11档 ┐
 *  │ ┌────────┐   ┌────────────────────────────────────────────┐ │
 *  │ │ 今天   │   │ 周四  01:25-03:25  蚁人与黄蜂女…       回看 │ │
 *  │ │ 10/15  │   │ 周四  03:25-06:00  黑豹2               回看 │ │
 *  │ │  …     │   │ …                                          │ │
 *  │ │ 今天   │   │ 周四  08:34-10:36  蜘蛛侠…             直播 │ │
 *  │ │ 10/22  │   │                                            │ │
 *  │ └────────┘   └────────────────────────────────────────────┘ │
 *  │ 左右切换日期 · 上下选节目 · 返回键回到控制栏                  │
 *  └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>数据源：{@link CarouselApi#fetchDaySchedule(String, String)}（program_v3.php?date=&tv_id=，
 * 每「频道×天」进程内至多 1 次请求、失败不重试 —— 与项目红线一致）。
 */
public class MiguCatchUpPanel extends LinearLayout {

    private static final String TAG = "MiguCatchUp";

    // ---- 视觉规格（与 ChannelListPanel 完全一致） ----
    private static final int PANEL_BG = 0xFF1B1B1B;
    private static final int ITEM_BG = 0xFF292929;
    private static final int ITEM_BG_CURRENT = 0xFF303030;
    private static final int GOLD = 0xFFFFD166;
    private static final int GOLD_TOP = 0xFFFFDF85;
    private static final int GOLD_BOTTOM = 0xFFFFC94D;

    private static final int ROW_HEIGHT_DP = 52;      // 片名允许两行 → 比频道行高一点
    private static final int ROW_GAP_DP = 11;
    private static final int GROUP_HEIGHT_DP = 41;
    private static final int GROUP_GAP_DP = 11;
    private static final int PANEL_WIDTH_DP = 560;
    private static final int CHROME_DP = 96;
    /** 「回看/直播」小胶囊的标记（styleRow 跳过它的配色，保留自己的蓝灰底） */
    private static final String TAG_ACTION = "migu_action";

    public interface Host {
        /**
         * 用户选择了某天的某个节目。
         *
         * @param playing true = 该节目此刻正在播（宿主应切回直播，而不是取回看）
         */
        void onCatchUpPick(Channel ch, String title, String pid, long startSec, long endSec,
                           String dateYmd, boolean playing);

        /** 弹窗关闭（宿主据此把焦点还给控制栏） */
        void onCatchUpClosed();
    }

    private final FrameLayout root;
    private final Channel channel;
    private final Host host;
    private final String tvId;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 左列日期（yyyyMMdd，倒序：今天 → 今天-1 … → 今天-7，今天在最上） */
    private final List<String> dates = new ArrayList<>();
    private String activeDate;

    private LinearLayout body, listBox, groupBox;
    private ScrollView groupScroll, listScroll;
    private TextView tvPage, activeTabView;

    private final List<View> rows = new ArrayList<>();
    private final List<View> tabViews = new ArrayList<>();
    private final List<CarouselApi.Program> rowPrograms = new ArrayList<>();
    private List<CarouselApi.Program> filtered = new ArrayList<>();

    private int lastRowIdx = 0;
    private int capRetry = 0;
    private View lastFocusedInside;
    /** 0=左侧日期列，1=右侧节目列（虚拟焦点区，与频道列表同机制） */
    private int focusZone = 1;
    private int groupFocusIdx = 0;
    private boolean closed;
    private int loadSeq = 0;
    private boolean loading;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusGuard =
            (oldF, newF) -> {
                if (closed) return;
                if (newF != null && isDescendantOf(newF, this)) {
                    lastFocusedInside = newF;
                    return;
                }
                post(() -> {
                    if (closed || hasFocusInside()) return;
                    View back = (lastFocusedInside != null && lastFocusedInside.isAttachedToWindow()
                            && lastFocusedInside.isFocusable()) ? lastFocusedInside : null;
                    if (back == null && !rows.isEmpty()) {
                        back = rows.get(Math.min(lastRowIdx, rows.size() - 1));
                    }
                    if (back != null) back.requestFocus();
                });
            };

    public MiguCatchUpPanel(Context context, FrameLayout root, Channel channel, Host host) {
        super(context);
        this.root = root;
        this.channel = channel;
        this.host = host;
        this.tvId = tvIdOf(channel);
        setOrientation(VERTICAL);
        setBackground(round(PANEL_BG, 24));
        setPadding(dp(24), dp(16), dp(24), dp(16));
        setFocusable(false);
        collectDates();
        build();
    }

    private static String tvIdOf(Channel c) {
        if (c == null) return "";
        String u = c.getMainPlayUrl();
        if (u != null && u.startsWith("nowtv://")) {
            return u.substring("nowtv://".length()).trim();
        }
        String id = c.getChannelId();
        if (id != null && id.startsWith("nowtv:")) {
            return id.substring("nowtv:".length()).trim();
        }
        return "";
    }

    private void collectDates() {
        dates.clear();
        Calendar cal = Calendar.getInstance();
        // 【2026-10-22 用户口径】今天排最前，其余按日期倒序（今天 → 昨天 → … → 7 天前）
        for (int i = 0; i <= 7; i++) {
            Calendar c = (Calendar) cal.clone();
            c.add(Calendar.DAY_OF_YEAR, -i);
            dates.add(ymd(c.getTime()));
        }
        activeDate = dates.get(0);   // 默认「今天」（列表首项）
        groupFocusIdx = 0;
        LogBridge.d(TAG, "回看日期列共 " + dates.size() + " 天：" + dates + "（默认=" + activeDate + "）");
    }

    // ==================== 构建（照搬 ChannelListPanel.build） ====================

    private void build() {
        // ---- 顶栏：标题 + 总档数 ----
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("回看" + (channel != null && channel.getName() != null
                ? " · " + channel.getName() : ""), 18, 0xFF9A9A9A, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvPage = text("", 15, GOLD, false);
        header.addView(tvPage);

        // ---- 主体：左日期列 + 右节目列 ----
        body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        addView(body, blp);

        groupBox = new LinearLayout(getContext());
        groupBox.setOrientation(VERTICAL);
        groupScroll = new ScrollView(getContext());
        groupScroll.setVerticalScrollBarEnabled(false);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setFocusable(false);
        groupScroll.addView(groupBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.50f);
        body.addView(groupScroll, glp);

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

        buildDateTabs();

        // ---- 底部提示 ----
        TextView hint = text("左右切换日期 · 上下选节目 · 返回键回到控制栏", 11, 0xFF6E6E6E, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        addView(hint, hlp);
    }

    private void buildDateTabs() {
        groupBox.removeAllViews();
        tabViews.clear();
        for (final String d : dates) {
            final boolean isToday = d.equals(dates.get(0));
            final TextView tab = new TextView(getContext());
            tab.setText((isToday ? "今天" : weekCn(d)) + "\n" + md(d));
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setLineSpacing(0f, 1f);
            tab.setGravity(Gravity.CENTER);
            tab.setIncludeFontPadding(false);
            tab.setPadding(dp(6), dp(3), dp(6), dp(3));
            // 虚拟焦点区：不让 Android 真实焦点落到日期胶囊（会被系统清空），
            // 焦点态由 focusZone/groupFocusIdx + 白描边自绘（与 ChannelListPanel 同）。
            tab.setFocusable(false);
            tab.setFocusableInTouchMode(false);
            if (d.equals(activeDate)) activeTabView = tab;
            styleGroup(tab, d.equals(activeDate), false);
            tab.setOnClickListener(v -> activateDate(d, tab));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(GROUP_HEIGHT_DP));
            lp.bottomMargin = dp(GROUP_GAP_DP);
            groupBox.addView(tab, lp);
            tabViews.add(tab);
        }
    }

    /** 切日期：重刷胶囊样式 + 重建右列（数据走缓存/单次请求） */
    private void activateDate(String d, TextView tab) {
        int idx = indexOf(tabViews, tab);
        if (idx >= 0) groupFocusIdx = idx;
        if (d.equals(activeDate)) {
            focusZone = 0;
            restyleGroups();
            restyleRows();
            return;
        }
        activeDate = d;
        activeTabView = tab;
        filtered = new ArrayList<>();
        restyleGroups();
        setLoadingRow();
        focusZone = 0;
        loadPrograms();
        ensureGroupVisible();
    }

    // ==================== 数据 ====================

    private void loadPrograms() {
        final String date = activeDate;
        final int seq = ++loadSeq;
        loading = true;
        List<CarouselApi.Program> cached = CarouselApi.getCachedDaySchedule(tvId, date);
        if (cached != null && !cached.isEmpty()) {
            loading = false;
            renderPrograms(seq, date, cached);
            return;
        }
        setLoadingRow();
        AppExecutors.io(() -> {
            List<CarouselApi.Program> list = null;
            try {
                list = CarouselApi.fetchDaySchedule(tvId, date);
            } catch (Throwable t) {
                LogBridge.w(TAG, "取节目单失败 " + date + "：" + t.getMessage());
            }
            final List<CarouselApi.Program> got = list;
            handler.post(() -> {
                if (closed || seq != loadSeq) return;
                loading = false;
                renderPrograms(seq, date, got);
            });
        });
    }

    private void setLoadingRow() {
        listBox.removeAllViews();
        rows.clear();
        rowPrograms.clear();
        TextView tv = text("加载中…", 14, 0xFF9E9E9E, false);
        tv.setGravity(Gravity.CENTER);
        listBox.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        if (tvPage != null) tvPage.setText("");
    }

    private void renderPrograms(int seq, String date, List<CarouselApi.Program> list) {
        if (closed || seq != loadSeq) return;
        filtered = (list == null) ? new ArrayList<>() : new ArrayList<>(list);
        if (filtered.isEmpty()) {
            listBox.removeAllViews();
            rows.clear();
            rowPrograms.clear();
            TextView tv = text("该日期暂无节目单", 14, 0xFF9E9E9E, false);
            tv.setGravity(Gravity.CENTER);
            listBox.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
            if (tvPage != null) tvPage.setText("0 档");
            return;
        }
        rebuild(true);
        if (tvPage != null) tvPage.setText("共" + filtered.size() + "档");
        capBodyHeight();
        LogBridge.d(TAG, "日期 " + date + " 共 " + filtered.size() + " 档");
    }

    // ==================== 列表 ====================

    private void rebuild(boolean restoreFocus) {
        long t0 = System.currentTimeMillis();
        final int keepIdx = restoreFocus ? focusedRowIndex() : -1;
        if (keepIdx >= 0) {
            listBox.setFocusableInTouchMode(true);
            listBox.setFocusable(true);
            listBox.requestFocus();
        }
        listBox.removeAllViews();
        rows.clear();
        rowPrograms.clear();
        if (filtered.isEmpty()) {
            releaseListFocusHolder();
            return;
        }
        if (tvPage != null) tvPage.setText("共" + filtered.size() + "档");
        // 分片构建（与 ChannelListPanel 相同的 ANR 防护）
        final int gen = ++rebuildGen;
        appendRowsChunk(gen, 0, keepIdx, t0);
    }

    private static final int REBUILD_CHUNK = 12;
    private int rebuildGen;
    private int pendingFocusIdx = -1;

    private void appendRowsChunk(final int gen, final int start, final int keepIdx, final long t0) {
        if (gen != rebuildGen || closed) return;
        final int end = Math.min(start + REBUILD_CHUNK, filtered.size());
        for (int i = start; i < end; i++) {
            final CarouselApi.Program p = filtered.get(i);
            View row = makeRow(p, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP));
            lp.bottomMargin = dp(ROW_GAP_DP);
            listBox.addView(row, lp);
            rows.add(row);
            rowPrograms.add(p);
        }
        if (end < filtered.size()) {
            listBox.post(() -> appendRowsChunk(gen, end, keepIdx, t0));
            return;
        }
        clampFocusInside();
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
        if (pendingFocusIdx >= 0) {
            final int want = pendingFocusIdx;
            pendingFocusIdx = -1;
            if (want < rows.size() && !hasFocusInside()) {
                lastRowIdx = want;
                if (rows.get(want).requestFocus()) {
                    final int y = Math.max(0, want * rowPitch() - rowPitch());
                    listScroll.post(() -> listScroll.scrollTo(0, y));
                }
            }
        }
    }

    private void releaseListFocusHolder() {
        listBox.setFocusable(false);
        listBox.setFocusableInTouchMode(false);
    }

    private int focusedRowIndex() {
        View f = getRootView().findFocus();
        if (f == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == f) return i;
        }
        return -1;
    }

    /** 行内容：周X | 时段 | 片名 | 回看/直播 胶囊 */
    private View makeRow(final CarouselApi.Program p, final int index) {
        final boolean playingNow = isPlayingNow(p);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setPadding(dp(14), 0, dp(14), 0);

        TextView week = text(weekCn(activeDate), 14, playingNow ? GOLD : 0xFF9E9E9E, false);
        row.addView(week, new LinearLayout.LayoutParams(dp(42),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView time = text(period(p), 14, playingNow ? GOLD : 0xFFA9BFD0, false);
        time.setSingleLine(true);
        row.addView(time, new LinearLayout.LayoutParams(dp(104),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = text(p.title == null ? "" : p.title, 14, playingNow ? GOLD : 0xFFFFFFFF, false);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(8);
        row.addView(name, nlp);

        TextView action = text(playingNow ? "直播" : "回看", 13, 0xFFFFFFFF, false);
        action.setTag(TAG_ACTION);
        action.setGravity(Gravity.CENTER);
        action.setBackground(round(0xFF2F5D6B, 6));
        action.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        row.addView(action, alp);

        styleRow(row, playingNow, false);
        row.setOnClickListener(v -> pick(p));
        action.setOnClickListener(v -> pick(p));
        row.setOnFocusChangeListener((v, has) ->
                // 与频道列表一致：行获焦不改 focusZone（见 ChannelListPanel.makeRow 注释）
                styleRow((LinearLayout) v, playingNow, has && focusZone == 1));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                int n = keyCode - KeyEvent.KEYCODE_0;
                int target = firstVisibleRow() + (n == 0 ? 9 : n - 1);
                if (target >= 0 && target < filtered.size()) pick(filtered.get(target));
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

    private boolean isPlayingNow(CarouselApi.Program p) {
        if (p == null || p.startTs <= 0) return false;
        long now = System.currentTimeMillis() / 1000L;
        return now >= p.startTs && (p.endTs <= 0 || now < p.endTs);
    }

    /** 行样式（与频道列表一致）：焦点=金色渐变+2dp白描边；当前档=提亮底色 */
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

        // 【2026-10-22 用户口径】「正在直播」的那一档整行文字都用黄色（周X / 时段 / 片名），
        //   不再只有周X 是金色；获焦时仍统一压成深色字（金底黑字，保证可读）。
        int textIndex = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            TextView tv = (TextView) c;
            if (TAG_ACTION.equals(tv.getTag())) continue;   // 「回看/直播」胶囊保留自己的配色
            int color;
            if (focused) {
                color = 0xFF111111;
            } else if (isCurrent) {
                color = GOLD;
            } else if (textIndex == 0) {
                color = 0xFF9E9E9E;
            } else if (textIndex == 1) {
                color = 0xFFA9BFD0;
            } else {
                color = 0xFFFFFFFF;
            }
            tv.setTextColor(color);
            textIndex++;
        }
    }

    private void pick(CarouselApi.Program p) {
        if (p == null) return;
        LogBridge.i(TAG, "选中回看：" + p.title + " pid=" + p.programId
                + " 日期=" + activeDate + " 正在播=" + isPlayingNow(p));
        if (host != null) {
            host.onCatchUpPick(channel, p.title, p.programId, p.startTs, p.endTs,
                    activeDate, isPlayingNow(p));
        }
        close();
    }

    // ------------------------- 焦点 / 按键（照搬频道列表） -------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean nav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (!nav) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            handleNav(code);
        }
        return true;
    }

    private void handleNav(int code) {
        View f = getRootView().findFocus();
        int ri = indexOf(rows, f);
        LogBridge.d(TAG, "按键 code=" + code + " 焦点=" + describe(f)
                + " 区=" + (focusZone == 0 ? "日期" : "节目") + " 行=" + ri);

        if (ri < 0) {
            restoreRowFocus();
            if (rows.isEmpty()) return;
            ri = Math.max(0, Math.min(lastRowIdx, rows.size() - 1));
        }
        lastRowIdx = ri;

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (focusZone == 0 && groupFocusIdx < tabViews.size()) {
                activateDate(dates.get(groupFocusIdx), (TextView) tabViews.get(groupFocusIdx));
            } else if (ri < rowPrograms.size()) {
                pick(rowPrograms.get(ri));
            }
            return;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focusZone == 1) enterGroupZone();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focusZone == 0) enterRowZone();
                break;
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

    private void scrollByRows(int delta) {
        if (rows.isEmpty()) return;
        focusRow(firstVisibleRow() + delta);
    }

    private void moveGroupFocus(int delta) {
        if (tabViews.isEmpty()) return;
        focusZone = 0;
        groupFocusIdx = Math.max(0, Math.min(groupFocusIdx + delta, tabViews.size() - 1));
        restyleGroups();
        restyleRows();
        ensureGroupVisible();
    }

    private void enterGroupZone() {
        focusZone = 0;
        int idx = indexOf(tabViews, activeTabView);
        groupFocusIdx = idx >= 0 ? idx : Math.max(0, Math.min(groupFocusIdx, tabViews.size() - 1));
        restyleGroups();
        restyleRows();
    }

    private void enterRowZone() {
        focusZone = 1;
        restyleGroups();
        restyleRows();
    }

    private void focusRow(int idx) {
        if (rows.isEmpty()) return;
        int i = Math.max(0, Math.min(idx, rows.size() - 1));
        lastRowIdx = i;
        boolean ok = rows.get(i).requestFocus();
        LogBridge.d(TAG, "聚焦回看行[" + i + "] 成功=" + ok);
    }

    private void restoreRowFocus() {
        if (rows.isEmpty()) return;
        int i = Math.max(0, Math.min(lastRowIdx, rows.size() - 1));
        rows.get(i).requestFocus();
    }

    private void clampFocusInside() {
        List<View> list = new ArrayList<>();
        for (View v : rows) {
            if (v != null && v.isFocusable()) {
                if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
                list.add(v);
            }
        }
        for (int i = 0; i < list.size(); i++) {
            View v = list.get(i);
            v.setNextFocusRightId(v.getId());
            v.setNextFocusLeftId(v.getId());
            v.setNextFocusUpId((i == 0) ? v.getId() : list.get(i - 1).getId());
            v.setNextFocusDownId((i == list.size() - 1) ? v.getId() : list.get(i + 1).getId());
        }
    }

    private void restyleGroups() {
        for (int i = 0; i < groupBox.getChildCount(); i++) {
            View c = groupBox.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            TextView tv = (TextView) c;
            boolean selected = i < dates.size() && dates.get(i).equals(activeDate);
            if (selected) activeTabView = tv;
            styleGroup(tv, selected, focusZone == 0 && i == groupFocusIdx);
        }
        ensureGroupVisible();
    }

    private void restyleRows() {
        for (int i = 0; i < rows.size(); i++) {
            View v = rows.get(i);
            if (!(v instanceof LinearLayout)) continue;
            boolean cur = i < rowPrograms.size() && isPlayingNow(rowPrograms.get(i));
            styleRow((LinearLayout) v, cur, focusZone == 1 && v.isFocused());
        }
    }

    /** 日期胶囊三态（与频道列表 styleGroup 完全一致） */
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

    private void ensureGroupVisible() {
        if (groupFocusIdx < 0 || groupFocusIdx >= tabViews.size()) return;
        View t = tabViews.get(groupFocusIdx);
        if (t == null) return;
        int top = t.getTop();
        int y = groupScroll.getScrollY();
        int h = groupScroll.getHeight();
        if (h <= 0) return;
        if (top < y) groupScroll.scrollTo(0, Math.max(0, top));
        else if (top + t.getHeight() > y + h) groupScroll.scrollTo(0, top + t.getHeight() - h);
    }

    // ------------------------- 显示/关闭 -------------------------

    public void show() {
        if (root == null || closed) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(32);
        lp.bottomMargin = dp(132);
        root.addView(this, lp);
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusGuard);
        post(this::capBodyHeight);
        // 日期列「今天」在最上 → 滚到顶部即可见
        groupScroll.post(() -> groupScroll.scrollTo(0, 0));
        loadPrograms();
        focusFirstRowWithRetry(0);
        LogBridge.d(TAG, "回看弹窗显示（双栏/560dp）：" + channel.getName() + " tvId=" + tvId);
    }

    private void capBodyHeight() {
        if (closed || body == null || root == null) return;
        int rh = root.getHeight();
        if (rh <= 0) {
            if (capRetry++ < 10) postDelayed(this::capBodyHeight, 80);
            return;
        }
        int maxBody = Math.max(dp(40), rh - dp(132) - dp(16) - dp(CHROME_DP));
        int need = Math.max(1, rows.size()) * rowPitch() - dp(ROW_GAP_DP);
        int h = Math.max(dp(40), Math.min(maxBody, need));
        ViewGroup.LayoutParams lp = body.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            body.setLayoutParams(lp);
        }
    }

    private void focusFirstRowWithRetry(int attempt) {
        if (closed) return;
        if (attempt > 12) return;
        if (loading || rows.isEmpty()) {
            postDelayed(() -> focusFirstRowWithRetry(attempt + 1), 200);
            return;
        }
        if (hasFocusInside()) return;
        lastRowIdx = 0;
        if (rows.get(0).requestFocus()) {
            clampFocusInside();
            return;
        }
        postDelayed(() -> focusFirstRowWithRetry(attempt + 1), 150);
    }

    public void close() {
        if (closed) return;
        closed = true;
        loadSeq++;
        handler.removeCallbacksAndMessages(null);
        try {
            getViewTreeObserver().removeOnGlobalFocusChangeListener(focusGuard);
        } catch (Throwable ignored) {
        }
        if (host != null) host.onCatchUpClosed();
        if (root != null) root.removeView(this);
    }

    public boolean isShowing() {
        return !closed && getParent() != null;
    }

    /** 供宿主关闭（兼容 dismiss 命名） */
    public void dismiss() {
        close();
    }

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

    // ------------------------- 工具 -------------------------

    private int rowPitch() {
        return Math.max(1, dp(ROW_HEIGHT_DP + ROW_GAP_DP));
    }

    private int visibleRows() {
        int h = listScroll.getHeight();
        if (h <= 0) return 6;
        return Math.max(1, h / rowPitch());
    }

    private int firstVisibleRow() {
        int v = listScroll.getScrollY() / rowPitch();
        return Math.max(0, Math.min(v, Math.max(0, rows.size() - 1)));
    }

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
        for (int i = 0; i < list.size(); i++) if (list.get(i) == v) return i;
        return -1;
    }

    private static String ymd(Date d) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(d);
    }

    private static final String[] WEEKS = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    private static String weekCn(String ymd) {
        try {
            Date d = new SimpleDateFormat("yyyyMMdd", Locale.US).parse(ymd);
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            return WEEKS[c.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Throwable t) {
            return "";
        }
    }

    private static String md(String ymd) {
        if (ymd == null || ymd.length() < 8) return "";
        return ymd.substring(4, 6) + "/" + ymd.substring(6, 8);
    }

    private static String period(CarouselApi.Program p) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        String s = p.startTs > 0 ? f.format(new Date(p.startTs * 1000L)) : "--:--";
        String e = p.endTs > 0 ? f.format(new Date(p.endTs * 1000L)) : "--:--";
        return s + "-" + e;
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
