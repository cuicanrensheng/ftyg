package com.tv.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tv.live.util.HuyaStreamPlayer.IntCallback;

/**
 * 二级 / 三级「胶囊分页面板」。
 *
 * 结构参照参考实现：从设置面板位置滑出的浮层，首行「‹ 返回 · 标题」，
 * 每行文本自带当前值；内部 ScrollView 纵向滚动，可再叠一层（由 SettingsDialog 的返回栈管理）。
 *
 * 配色沿用本项目已定稿口径，不使用参考包的蓝灰系：
 *  - 面板底 #FF1A1A1A + 1dp #33FFFFFF 描边 + 圆角 14dp
 *  - 行默认底 list_item_default(#212121)，焦点底 list_item_focused(橄榄 #3C3525 + 2dp 白描边)
 *  - 文字：焦点 / 选中 = 黄 #FFD166，其余 = 白 #FFFFFFFF
 */
class SettingsCapsulePanel {

    private final Context context;
    private final FrameLayout host;
    /** 一级面板根视图（打开胶囊时隐藏，关闭时恢复） */
    private final View level1Root;
    /** 面板外的空白 View（打开胶囊时屏蔽点击与焦点） */
    private final View outsideView;
    private final String title;
    private final String[] items;
    private final int checkedItem;
    private final IntCallback onPick;
    private final boolean keepOpenOnPick;

    private View panel;
    private TextView[] rows;
    private TextView titleRowRef;
    private View trigger;
    private Runnable onRequestClose;
    /** 打开后要聚焦的行号（-1 = 聚焦 checkedItem / 首行） */
    private int initialFocusIndex = -1;
    /** 展示前背景层的 focusable 状态，hide 时原样恢复 */
    private boolean outsideWasFocusable = true;
    /** 置位后本层只渲染「二维码 + 地址 + 关闭」内容，忽略 items（订阅管理三级用） */
    private Bitmap qrBitmap;
    private String qrAddress;

    SettingsCapsulePanel(Context context, FrameLayout host, View level1Root, View outsideView,
                         String title, String[] items, int checkedItem, IntCallback onPick) {
        this(context, host, level1Root, outsideView, title, items, checkedItem, onPick, false, null, null);
    }

    SettingsCapsulePanel(Context context, FrameLayout host, View level1Root, View outsideView,
                         String title, String[] items, int checkedItem, IntCallback onPick,
                         boolean keepOpenOnPick) {
        this(context, host, level1Root, outsideView, title, items, checkedItem, onPick, keepOpenOnPick, null, null);
    }

    SettingsCapsulePanel(Context context, FrameLayout host, View level1Root, View outsideView,
                         String title, String[] items, int checkedItem, IntCallback onPick,
                         boolean keepOpenOnPick, Bitmap qrBitmap, String qrAddress) {
        this.context = context;
        this.host = host;
        this.level1Root = level1Root;
        this.outsideView = outsideView;
        this.title = title;
        this.items = items;
        this.checkedItem = checkedItem;
        this.onPick = onPick;
        this.keepOpenOnPick = keepOpenOnPick;
        this.qrBitmap = qrBitmap;
        this.qrAddress = qrAddress;
    }

    // ==================== 对外状态 ====================

    boolean isKeepOpen() {
        return keepOpenOnPick;
    }

    String getTitle() {
        return title;
    }

    View getView() {
        return panel;
    }

    SettingsCapsulePanel setOnRequestClose(Runnable onRequestClose) {
        this.onRequestClose = onRequestClose;
        return this;
    }

    /** 指定打开后聚焦的行号（重建本层时用它把焦点留在原行） */
    void setInitialFocus(int index) {
        this.initialFocusIndex = index;
    }

    // ==================== 显示 / 关闭 ====================

    void show() {
        if (panel != null) {
            return;
        }
        trigger = host.findFocus();

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int px = Math.round(dm.density);
        int panelWidth = Math.round(dm.density * 280f);
        final int maxPanelHeight = Math.round(dm.density * 420f);
        // checkedItem < 0 表示这是一组「动作项」而非单选，不画 ●/○ 标记
        final boolean useMarker = checkedItem >= 0;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A1A1A);
        bg.setCornerRadius(dm.density * 14f);
        bg.setStroke(px, 0x33FFFFFF);
        root.setBackground(bg);
        root.setPadding(Math.round(dm.density * 12f), Math.round(dm.density * 14f),
                Math.round(dm.density * 12f), Math.round(dm.density * 12f));
        root.setFocusable(false);

        // 首行：‹ 返回 · 标题
        TextView titleRow = new TextView(context);
        titleRow.setText("‹ 返回 · " + title);
        titleRow.setTextSize(15f);
        titleRow.setTextColor(0xFFFFD166);
        titleRow.setSingleLine(true);
        titleRow.setPadding(Math.round(dm.density * 6f), Math.round(dm.density * 4f),
                Math.round(dm.density * 6f), Math.round(dm.density * 10f));
        titleRow.setFocusable(true);
        titleRow.setClickable(true);
        titleRowRef = titleRow;
        titleRow.setOnClickListener(v -> requestClose());
        titleRow.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                requestClose();
                return true;
            }
            return false;
        });
        root.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 二维码区（订阅管理三级）：二维码需要白底深码才可被相机识别
        if (qrBitmap != null) {
            FrameLayout qrBox = new FrameLayout(context);
            GradientDrawable qrBg = new GradientDrawable();
            qrBg.setColor(0xFFFFFFFF);
            qrBg.setCornerRadius(dm.density * 8f);
            qrBox.setBackground(qrBg);
            int qrPad = Math.round(dm.density * 8f);
            qrBox.setPadding(qrPad, qrPad, qrPad, qrPad);

            ImageView ivQr = new ImageView(context);
            ivQr.setImageBitmap(qrBitmap);
            ivQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int qrSize = Math.round(dm.density * 150f);
            qrBox.addView(ivQr, new FrameLayout.LayoutParams(qrSize, qrSize));

            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            qrLp.gravity = Gravity.CENTER_HORIZONTAL;
            qrLp.bottomMargin = Math.round(dm.density * 6f);
            root.addView(qrBox, qrLp);

            if (qrAddress != null && !qrAddress.isEmpty()) {
                TextView addr = new TextView(context);
                addr.setText(qrAddress);
                addr.setTextSize(13f);
                addr.setTextColor(0xFFAAAAAA);
                addr.setGravity(Gravity.CENTER);
                addr.setSingleLine(true);
                addr.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                addr.setPadding(0, 0, 0, Math.round(dm.density * 8f));
                root.addView(addr, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        // 分隔线
        View divider = new View(context);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px);
        dlp.bottomMargin = Math.round(dm.density * 8f);
        root.addView(divider, dlp);

        // 选项行
        LinearLayout rowBox = new LinearLayout(context);
        rowBox.setOrientation(LinearLayout.VERTICAL);
        rows = new TextView[items.length];
        final int rowHeight = Math.round(dm.density * 44f);
        final int rowGap = Math.round(dm.density * 7f);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            final boolean checked = (i == checkedItem);
            final TextView row = new TextView(context);
            row.setText(useMarker ? (checked ? "● " : "○ ") + items[i] : items[i]);
            row.setTextSize(15.5f);
            row.setTextColor(checked ? 0xFFFFD166 : 0xFFFFFFFF);
            row.setSingleLine(true);
            // 线路 / 清晰度 / 订阅源名可能较长（如「畅享(白天用) · HD中字」），超宽时尾部省略
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(dm.density * 12f), 0, Math.round(dm.density * 12f), 0);
            row.setBackgroundResource(R.drawable.list_item_default);
            row.setFocusable(true);
            row.setClickable(true);
            // 实测 state_focused 选择器在这些行上不生效，与设置面板一致：按 hasFocus 显式指定背景
            row.setOnFocusChangeListener((v, hasFocus) -> {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.list_item_focused : R.drawable.list_item_default);
                row.setTextColor(hasFocus || checked ? 0xFFFFD166 : 0xFFFFFFFF);
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight);
            rlp.bottomMargin = rowGap;
            rowBox.addView(row, rlp);
            rows[i] = row;

            row.setOnClickListener(v -> pick(index));
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    requestClose();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    pick(index);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (index + 1 < rows.length && rows[index + 1] != null) {
                        rows[index + 1].requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (index - 1 >= 0 && rows[index - 1] != null) {
                        rows[index - 1].requestFocus();
                    } else if (titleRowRef != null) {
                        titleRowRef.requestFocus();
                    }
                    return true;
                }
                return false;
            });
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.addView(rowBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        // 线路 / 订阅源条目数不固定：内容短时按内容高度，超过上限后内部滚动，避免顶出屏幕
        int contentHeight = items.length * (rowHeight + rowGap);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.min(contentHeight, maxPanelHeight)));

        panel = root;
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(panelWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        flp.rightMargin = Math.round(dm.density * 20f);
        host.addView(panel, flp);

        if (level1Root != null) {
            level1Root.setVisibility(View.GONE);
        }
        if (outsideView != null) {
            // 【2026-10-21】点击播放器（胶囊外区域）与一级菜单一致：直接关闭整个设置面板。
            //   因此保留 outsideView 的点击能力（其点击回调是 SettingsDialog#dismiss），
            //   仅关闭焦点能力，避免 DPAD 焦点被背景抢走。
            outsideWasFocusable = outsideView.isFocusable();
            outsideView.setFocusable(false);
        }

        int focusIndex;
        if (initialFocusIndex >= 0 && initialFocusIndex < rows.length) {
            focusIndex = initialFocusIndex;
        } else if (checkedItem >= 0 && checkedItem < rows.length) {
            focusIndex = checkedItem;
        } else {
            focusIndex = 0;
        }
        panel.postDelayed(() -> {
            if (rows != null && focusIndex < rows.length && rows[focusIndex] != null) {
                rows[focusIndex].requestFocus();
            }
        }, 280L);
    }

    void hide() {
        if (panel == null) {
            return;
        }
        try {
            host.removeView(panel);
        } catch (Throwable ignored) {
        }
        panel = null;
        rows = null;
        if (level1Root != null) {
            level1Root.setVisibility(View.VISIBLE);
        }
        if (outsideView != null) {
            outsideView.setFocusable(outsideWasFocusable);
        }
        if (trigger != null && trigger.getVisibility() == View.VISIBLE) {
            trigger.requestFocus();
        }
        trigger = null;
    }

    /** 直接丢弃视图，不恢复一级面板、不触发回调（整窗销毁时用） */
    void discard() {
        if (panel == null) {
            return;
        }
        try {
            host.removeView(panel);
        } catch (Throwable ignored) {
        }
        panel = null;
        rows = null;
    }

    private void pick(int index) {
        if (!keepOpenOnPick) {
            hide();
        }
        if (onPick != null) {
            onPick.accept(index);
        }
    }

    private void requestClose() {
        if (onRequestClose != null) {
            onRequestClose.run();
        } else {
            hide();
        }
    }
}
