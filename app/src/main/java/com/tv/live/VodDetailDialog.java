package com.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tv.live.afhgrj.AfhgrjUrlResolver;
import com.tv.live.util.LogBridge;

import java.util.List;

/**
 * 【2026-10-17 OTT 详情页 + 线路切换】
 *
 * <p>数据来源：{@link AfhgrjUrlResolver#getDetail(String)} —— 复用「集数解析」已抓的
 * {@code /yd/{vodId}/} 详情页 HTML，不产生任何额外请求（符合项目「不并发、不批量」红线）。
 *
 * <p>可做两件事：
 * <ol>
 *   <li>看简介/主演/导演/年份/地区/评分（MacCMS 模板字段）；</li>
 *   <li>手动切换播放线路（源7 每部片 1~3 条 CDN，某条卡死时不必退出重进）。</li>
 * </ol>
 *
 * <p>电视端焦点约定：上下键在「线路 → 按钮」之间移动，左右键在线路间横移，返回键关闭。
 */
public class VodDetailDialog extends Dialog {

    private static final String TAG = "VodDetailDialog";

    /** 线路切换/重新播放回调（由 OTT 页面实现：记住 sid 后重播当前集） */
    public interface Callback {
        void onSwitchLine(String vodId, int sid);
        void onPlay();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final String vodId;
    private final String initialHint;

    private LinearLayout root;
    private LinearLayout lineRow;
    private TextView tvLoading;

    public VodDetailDialog(Context context, String vodId, String channelName, Callback callback) {
        super(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.vodId = vodId;
        this.callback = callback;
        this.initialHint = channelName;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xCC000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            w.setAttributes(lp);
        }

        root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(60), dp(36), dp(60), dp(30));
        root.setBackground(roundRect(0xF21A1A1A, 24));

        FrameLayout wrap = new FrameLayout(getContext());
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(1180), ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.CENTER;
        wrap.addView(root, rlp);

        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(wrap);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        setContentView(scroll);

        tvLoading = new TextView(getContext());
        tvLoading.setText("正在读取详情…");
        tvLoading.setTextColor(0xFFB0B0B0);
        tvLoading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(tvLoading);

        loadDetailAsync();
    }

    /** 后台线程取详情，主线程渲染（详情来自内存缓存或一次既有请求的结果，不会额外打网络） */
    private void loadDetailAsync() {
        new Thread(() -> {
            AfhgrjUrlResolver.Detail d = null;
            try {
                d = AfhgrjUrlResolver.getDetail(vodId);
            } catch (Throwable t) {
                LogBridge.w(TAG, "取详情失败: " + t.getMessage());
            }
            final AfhgrjUrlResolver.Detail detail = d;
            mainHandler.post(() -> render(detail));
        }, "VodDetail-Load").start();
    }

    private void render(AfhgrjUrlResolver.Detail d) {
        root.removeAllViews();

        String title = (d != null && !TextUtils.isEmpty(d.title)) ? d.title : initialHint;
        TextView tvTitle = text(title, 30, 0xFFFFFFFF, true);
        root.addView(tvTitle);

        if (d != null && !TextUtils.isEmpty(d.subtitle())) {
            TextView tvSub = text(d.subtitle(), 16, 0xFF9E9E9E, false);
            tvSub.setPadding(0, dp(6), 0, dp(14));
            root.addView(tvSub);
        } else {
            root.addView(space(dp(12)));
        }

        if (d != null && !TextUtils.isEmpty(d.actor)) {
            root.addView(field("主演", d.actor));
        }
        if (d != null && !TextUtils.isEmpty(d.director)) {
            root.addView(field("导演", d.director));
        }
        if (d != null && !TextUtils.isEmpty(d.category)) {
            root.addView(field("类型", d.category));
        }

        // 简介（限高滚动，避免长文把按钮顶出屏幕）
        if (d != null && !TextUtils.isEmpty(d.content)) {
            TextView tvContent = text(d.content, 16, 0xFFDDDDDD, false);
            tvContent.setLineSpacing(dp(6), 1f);
            ScrollView sv = new ScrollView(getContext());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(210));
            slp.topMargin = dp(10);
            sv.addView(tvContent);
            root.addView(sv, slp);
        } else if (d == null || d.isEmpty()) {
            TextView tv = text("（该片详情暂不可用，可直接播放或切换线路）", 15, 0xFF888888, false);
            tv.setPadding(0, dp(8), 0, 0);
            root.addView(tv);
        }

        // ---- 线路区 ----
        root.addView(sectionTitle("播放线路"));
        lineRow = new LinearLayout(getContext());
        lineRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        root.addView(lineRow, llp);
        buildLineChips();

        // ---- 按钮区 ----
        LinearLayout btnRow = new LinearLayout(getContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(26);
        root.addView(btnRow, blp);

        TextView btnPlay = chip("播放本片", true);
        btnPlay.setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onPlay();
        });
        btnRow.addView(btnPlay);

        TextView btnClose = chip("关闭", false);
        btnClose.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(18);
        btnRow.addView(btnClose, clp);

        // 初始焦点：第一条线路（没有线路则落在「播放本片」）
        if (lineRow.getChildCount() > 0) lineRow.getChildAt(0).requestFocus();
        else btnPlay.requestFocus();
    }

    private void buildLineChips() {
        lineRow.removeAllViews();
        int[] sids = AfhgrjUrlResolver.sidsOf(vodId);
        int working = AfhgrjUrlResolver.workingSidOf(vodId);
        if (sids.length == 0) {
            TextView tv = text("（线路信息未就绪：起播后即可切换）", 15, 0xFF888888, false);
            lineRow.addView(tv);
            return;
        }
        List<String> names = AfhgrjUrlResolver.lineNamesOf(vodId);
        for (int i = 0; i < sids.length; i++) {
            final int sid = sids[i];
            boolean current = (sid == working);
            String label = (i < names.size() ? names.get(i) : ("线路" + sid)) + (current ? " ✓" : "");
            TextView c = chip(label, current);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(14);
            c.setOnClickListener(v -> {
                if (sid == AfhgrjUrlResolver.workingSidOf(vodId)) {
                    dismiss();
                    return;
                }
                AfhgrjUrlResolver.rememberSid(vodId, sid);
                AfhgrjUrlResolver.invalidate(vodId);
                LogBridge.d(TAG, "用户手选线路 vodId=" + vodId + " sid=" + sid + " → 重播");
                dismiss();
                if (callback != null) callback.onSwitchLine(vodId, sid);
            });
            lineRow.addView(c, lp);
        }
    }

    // ---------------- 小工具 ----------------

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setText(s == null ? "" : s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private LinearLayout field(String label, String value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        TextView l = text(label, 15, 0xFF9E9E9E, false);
        l.setMinWidth(dp(60));
        TextView v = text(value, 15, 0xFFE0E0E0, false);
        row.addView(l);
        row.addView(v);
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    private TextView sectionTitle(String s) {
        TextView tv = text(s, 17, 0xFFFFD166, true);
        tv.setPadding(0, dp(20), 0, 0);
        return tv;
    }

    private TextView chip(String label, boolean highlighted) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(highlighted ? 0xFF101010 : 0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        tv.setFocusable(true);
        tv.setBackground(roundRect(highlighted ? 0xFFFFD166 : 0xFF333333, 999));
        tv.setOnFocusChangeListener((v, has) -> v.setBackground(
                roundRect(has ? 0xFFFFD166 : (highlighted ? 0xFFFFD166 : 0xFF333333), 999)));
        return tv;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp == 999 ? 40 : radiusDp));
        return g;
    }

    private View space(int h) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
