package com.tv.live;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tv.live.afhgrj.AfhgrjUrlResolver;
import com.tv.live.util.ChannelLogoLoader;
import com.tv.live.util.LogBridge;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 【2026-10-17 界面重构 v2 · 点播详情页】
 *
 * <p>设计稿：{@code _ott_vod_detail_design.html}。解决「点播海报按 OK 直接起播」的问题：
 * 先给一层「看简介 / 选集 / 确认再播」的详情页，与银河奇异果、云视听极光、CIBN 酷喵一致。
 *
 * <pre>
 *  ┌ 返回 · 回到《电影》                          源5 · 点播 ┐
 *  │ ┌──────┐  野狗骨头                                      │
 *  │ │ 2:3  │  2026 · 大陆 · 国产剧 · 更新至番外集全 · 8.6 分  │
 *  │ │ 海报 │  主演 / 导演 / 类型                             │
 *  │ │      │  简介（3 行截断）                               │
 *  │ └──────┘  [▶ 播放]  [＋ 收藏]                            │
 *  │  选集 · 共 28 集                    [01][02]…[12*]…      │
 *  └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>交互约定：
 * <ul>
 *   <li><b>默认焦点 = 「播放」</b>（10-foot 规范：主 CTA 必须是首焦点）；</li>
 *   <li>按钮行 左右移动、按下进选集；选集为**纵向网格**，左右逐集（行末跨行）、上下逐行（首行上回按钮）；</li>
 *   <li>返回键只关详情页，由宿主把焦点还给来源海报 / 或回到正在播放的详情页。</li>
 * </ul>
 *
 * <p>为什么是覆盖层而不是 Dialog / Activity：焦点与返回栈都在宿主手里，
 * 关闭后能精确恢复「来源海报」的位置；Dialog 会抢 window focus，Activity 会破坏 OTT 单栈结构。
 */
public class VodDetailPanel {

    private static final String TAG = "VodDetailPanel";

    /** 宿主回调（全部由 OttHomeActivity 实现） */
    public interface Host {
        /** 播放本片（整片/续播） */
        void onDetailPlay(Channel ch);

        /** 播放指定集 */
        void onDetailPlayEpisode(Channel ch, Channel.EpgItem item);

        boolean onDetailIsFavorite(Channel ch);

        boolean onDetailToggleFavorite(Channel ch);

        /** 详情页关闭（宿主据此恢复焦点到来源海报） */
        void onDetailClose();
    }

    private final Activity ctx;
    private final FrameLayout root;
    private final Channel channel;
    private final int currentEpisodeIdx;
    private final Host host;

    private FrameLayout panel;
    private TextView btnPlay, btnFav;
    private TextView tvTitle, tvSubtitle, tvFields, tvSynopsis, tvLineInfo;
    /** 内容根容器（算选集可用高度时要它的总高） */
    private LinearLayout contentRoot;
    /**
     * 【2026-10-18 选集改纵向】原来是一条横向滚动的单行，集数一多就得一直按右键。
     * 现在改成「多列网格 + 纵向滚动」：高度吃掉内容区剩余空间（weight=1），不写死，
     * 列数按可用宽度算，行数由高度自然决定 —— 空间够就多显示几行，不够就滚动。
     */
    private LinearLayout epList;
    private ScrollView epScroll;
    private LinearLayout epSection;
    private TextView tvEpTitle;
    /** 选集格子按播放顺序的扁平列表（下标 = 集数-1），焦点跳转与滚动定位都靠它 */
    private final List<View> epCells = new java.util.ArrayList<>();
    /** 每一行的容器，用于判断「焦点在第几行」（上下键切行用） */
    private final List<LinearLayout> epRows = new java.util.ArrayList<>();
    private int epColumns = 6;
    /** EPG 集数异步就绪回调（详情页打开瞬间集数往往还没解析完） */
    private EpgManager.OnEpgDataReadyListener epgListener;

    private boolean showing;
    /** 【2026-10-22 O3】hide 后正在淡出、尚未移除的面板（供快速重开时摘除） */
    private FrameLayout fadingPanel;
    /** 【2026-10-22 O3】「当前生效实例」唯一事实源：宿主每次 openVodDetail 都 new 面板，
     *  实例级 epoch 管不到跨实例回调；WeakReference 防持有已销毁 Activity 的实例。 */
    private static WeakReference<VodDetailPanel> sCurrent;

    public VodDetailPanel(Activity ctx, FrameLayout root, Channel channel,
                          int currentEpisodeIdx, Host host) {
        this.ctx = ctx;
        this.root = root;
        this.channel = channel;
        this.currentEpisodeIdx = currentEpisodeIdx;
        this.host = host;
    }

    public boolean isShowing() {
        return showing;
    }

    // ==================== 显示 / 关闭 ====================

    public void show() {
        show(true);
    }

    /**
     * @param animate false = 直接以不透明状态出现。
     *                从全屏退回详情页时必须用它：淡入会让底下的首页先露一帧，
     *                用户看到的就是「先跳回 OTT 界面，再回到详情页」。
     */
    public void show(boolean animate) {
        if (root == null || channel == null) return;
        if (showing) return;
        // 【2026-10-22 O3】hide 后 120ms 淡出未结束时快速重开：先摘掉淡出中的旧面板
        // （cancel + 立即移除，消除双面板叠层与焦点互抢），再把 sCurrent 指向本实例，
        // 旧实例的 withEndAction 因 sCurrent 已不指向它而不再回调 onDetailClose。
        dismissFadingOutPanel();
        sCurrent = new WeakReference<>(this);
        build();
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showing = true;
        if (animate) {
            panel.setAlpha(0f);
            panel.animate().alpha(1f).setDuration(160).start();
        } else {
            panel.setAlpha(1f);
        }
        // 默认焦点：主 CTA。面板刚 addView 时可能还没完成布局 → requestFocus 失败，
        // 失败就再等一帧重试（否则焦点一直留在背后的网格，用户按方向键操作的是首页）
        panel.post(() -> requestDefaultFocus(0));
        LogBridge.d(TAG, "详情页打开" + (animate ? "" : "（无淡入）") + "：" + channel.getName());
    }

    private void requestDefaultFocus(int attempt) {
        if (!showing || panel == null || btnPlay == null) return;
        // 面板里已经有焦点（用户已操作过）→ 不再抢
        View cur = ctx.getCurrentFocus();
        if (cur != null && isInPanel(cur)) return;
        if (btnPlay.requestFocus()) {
            LogBridge.d(TAG, "默认焦点 → 播放（第 " + attempt + " 次尝试）");
            return;
        }
        // 详情页内容多，布局可能要几百毫秒才完成；太早 requestFocus 会一直失败
        if (attempt < 10) {
            panel.postDelayed(() -> requestDefaultFocus(attempt + 1), 120);
        } else {
            LogBridge.w(TAG, "默认焦点请求失败（面板未就绪）");
        }
    }

    private boolean isInPanel(View v) {
        View cur = v;
        while (cur != null) {
            if (cur == panel) return true;
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    public void hide() {
        if (!showing || panel == null) return;
        showing = false;
        unregisterEpgListener();
        final FrameLayout p = panel;
        panel = null;
        fadingPanel = p;
        p.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            fadingPanel = null;
            // removeView 无条件执行（防旧面板残留叠层）；仅当本实例仍是「当前生效实例」时
            // 才回调宿主关闭 —— hide 后 120ms 内又 show 了新面板时 sCurrent 已指向新实例，
            // 宿主并未真正回到列表，此时回调 onDetailClose 会清空宿主引用并抢焦点。
            if (root != null && p.getParent() == root) root.removeView(p);
            if (sCurrent != null && sCurrent.get() == VodDetailPanel.this) {
                sCurrent = null;
                if (host != null) host.onDetailClose();
            }
        }).start();
        LogBridge.d(TAG, "详情页关闭：" + channel.getName());
    }

    /**
     * 【2026-10-22 O3】摘除仍在淡出动画中的上一个详情页面板（若有）。
     * 先切断 sCurrent 指向再 cancel：cancel 会同步触发旧 withEndAction，届时
     * sCurrent 已不指向旧实例 → 不回调 onDetailClose，仅完成 removeView。
     */
    private void dismissFadingOutPanel() {
        VodDetailPanel prev = (sCurrent != null) ? sCurrent.get() : null;
        if (prev == null || prev == this) return;
        sCurrent = null;
        FrameLayout fading = prev.fadingPanel;
        if (fading != null) {
            fading.animate().cancel();
            if (prev.root != null && fading.getParent() == prev.root) prev.root.removeView(fading);
            prev.fadingPanel = null;
        }
        prev.showing = false;
        LogBridge.i(TAG, "详情页快速重开：摘除淡出中的旧面板");
    }

    /** 注销 EPG 就绪监听（详情页关闭 / 已拿到集数时） */
    private void unregisterEpgListener() {
        if (epgListener == null) return;
        try {
            EpgManager.getInstance(ctx.getApplicationContext())
                    .unregisterEpgDataReadyListener(epgListener);
        } catch (Throwable ignored) {
        }
        epgListener = null;
    }

    // ==================== 构建 ====================

    private void build() {
        panel = new BoundaryFrameLayout(ctx);
        panel.setBackgroundColor(0xFF05070D);
        panel.setFocusable(true);
        panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        // ---- 背景：海报放大 + 深色渐变（不用 RenderEffect，minSdk 21 可用） ----
        ImageView bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        panel.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bg.setAlpha(0.5f);
        ChannelLogoLoader.getInstance(ctx).load(bg, safeLogo(), R.drawable.ic_channel_logo_placeholder);

        View scrim = new View(ctx);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF705070D, 0xD905070D, 0xA605070D});
        scrim.setBackground(g);
        panel.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 内容容器 ----
        LinearLayout content = new LinearLayout(ctx);
        contentRoot = content;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(55), dp(31), dp(55), dp(31));
        panel.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏
        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvBack = text("‹ 返回", 15, 0xFFC3CEDF, false);
        tvBack.setPadding(dp(10), dp(6), dp(10), dp(6));
        tvBack.setFocusable(false);
        topRow.addView(tvBack);

        TextView tvBackHint = text("回到点播列表", 14, 0xFF8E9AB3, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.leftMargin = dp(10);
        topRow.addView(tvBackHint, hintLp);

        View spacer = new View(ctx);
        topRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView tvSource = text(sourceLabel(), 14, 0xFF6C7A93, false);
        topRow.addView(tvSource);

        // ---- 主区：海报 + 信息 ----
        LinearLayout main = new LinearLayout(ctx);
        main.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mainLp.topMargin = dp(26);
        content.addView(main, mainLp);

        // 海报尺寸随屏幕高度收放：高屏封顶 291dp，矮屏（如 720p / 1080p 高密度电视）按比例缩，
        // 保证下方选集区始终有可用高度（集数区自适应见 epSection 的 weight=1）
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int posterH = Math.min(dp(291), (int) (screenH * 0.42f));
        int posterW = Math.max(dp(120), posterH * 2 / 3);
        main.addView(buildPoster(), new LinearLayout.LayoutParams(posterW, posterH));

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(26);
        main.addView(info, infoLp);

        tvTitle = text(channel.getName(), 26, 0xFFFFFFFF, true);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(tvTitle);

        tvSubtitle = text("", 14, 0xFFA9B6CC, false);
        tvSubtitle.setSingleLine(true);
        tvSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(9);
        info.addView(tvSubtitle, subLp);

        tvFields = text("", 14, 0xFFC3CEDF, false);
        tvFields.setLineSpacing(dp(5), 1f);
        tvFields.setMaxLines(3);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(11);
        info.addView(tvFields, fLp);

        tvSynopsis = text("", 15, 0xFFB9C6DA, false);
        tvSynopsis.setLineSpacing(dp(6), 1f);
        tvSynopsis.setMaxLines(3);
        tvSynopsis.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(12);
        info.addView(tvSynopsis, sLp);

        // ---- 按钮行 ----
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(20);
        info.addView(actions, aLp);

        btnPlay = actionButton("▶ 播放", true);
        btnPlay.setOnClickListener(v -> {
            LogBridge.i(TAG, "详情页「播放」→ " + channel.getName());
            hide();
            if (host != null) host.onDetailPlay(channel);
        });
        actions.addView(btnPlay);

        btnFav = actionButton("＋ 收藏", false);
        LinearLayout.LayoutParams fvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fvLp.leftMargin = dp(14);
        actions.addView(btnFav, fvLp);
        refreshFavorite();
        btnFav.setOnClickListener(v -> {
            if (host != null) host.onDetailToggleFavorite(channel);
            refreshFavorite();
        });

        // 线路：只做信息展示，切换在播放后的控制栏里（避免详情页再挂一层抽屉）
        tvLineInfo = text(lineInfoLabel(), 13, 0xFF7C8AA5, false);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lineLp.leftMargin = dp(16);
        actions.addView(tvLineInfo, lineLp);

        // ---- 选集（纵向网格，高度按内容与剩余空间二者取小）----
        epSection = new LinearLayout(ctx);
        epSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams epLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        epLp.topMargin = dp(22);
        content.addView(epSection, epLp);

        tvEpTitle = text("选集", 15, 0xFFFFFFFF, true);
        epSection.addView(tvEpTitle);

        epScroll = new ScrollView(ctx);
        epScroll.setVerticalScrollBarEnabled(false);
        epScroll.setHorizontalScrollBarEnabled(false);
        epScroll.setClipToPadding(false);
        // 【2026-10-18 焦点】ScrollView.initScrollView() 里默认 setFocusable(true)，
        //   于是它自己会成为焦点落点，并把方向键当「滚动」吃掉 —— 表现就是
        //   「选集格按上下左右不动」。这里必须显式关掉自身焦点，只让格子可获焦。
        epScroll.setFocusable(false);
        epScroll.setFocusableInTouchMode(false);
        epScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // 先给一行的高度：避免首帧按「全部集数」撑出屏幕，布局完成后再按实际空间定高
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        scLp.topMargin = dp(10);
        epSection.addView(epScroll, scLp);

        epList = new LinearLayout(ctx);
        epList.setFocusable(false);
        epList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        epList.setOrientation(LinearLayout.VERTICAL);
        epScroll.addView(epList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildEpisodes();

        // 焦点链：播放 ↔ 收藏（左右）；按钮 ↓ → 选集；选集 ↑ → 播放
        btnPlay.setNextFocusRightId(btnFav.getId());
        btnFav.setNextFocusLeftId(btnPlay.getId());
        btnFav.setNextFocusRightId(View.NO_ID);

        // 骨架数据（列表里已有的：片名/海报/更新状态）
        fillSkeleton();
        loadDetailAsync();
    }

    /** 竖版海报卡：海报 + 右上角更新状态角标 + 底部片名 */
    private View buildPoster() {
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A1F2B);
        bg.setCornerRadius(dp(8));
        card.setBackground(bg);
        card.setClipToOutline(true);          // API 21+：按背景 outline 裁剪圆角
        card.setFocusable(false);

        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ChannelLogoLoader.getInstance(ctx).load(iv, safeLogo(), R.drawable.ic_channel_logo_placeholder);

        return card;
    }

    /**
     * 选集纵向网格：一格一集，当前集金框。列数按可用宽度算（67dp 格 + 9dp 间距），
     * 高度吃满剩余空间、超出就纵向滚动；焦点移动时自动把目标格滚进视野。
     *
     * 【2026-10-17 v2 修复「详情里不显示集数」】集数是**异步解析**的：
     * 用户点开详情时 EpgManager 往往还没拿到该片的集数列表（实测详情页打开后 0~1s 才就绪），
     * 原来只同步取一次 → 为空就永久隐藏。现在：
     * ① 取不到 → 注册 EPG 就绪回调，数据到了自动重建选集；
     * ② 取到了 → 注销回调，避免重复刷新。
     */
    private void buildEpisodes() {
        if (epSection == null) return;
        List<Channel.EpgItem> items = epgOfChannel();
        if (items == null || items.isEmpty()) {
            epSection.setVisibility(View.GONE);
            registerEpgListener();
            return;
        }
        unregisterEpgListener();
        epSection.setVisibility(View.VISIBLE);
        String cnt = "共 " + items.size() + " 集";
        if (currentEpisodeIdx >= 0 && currentEpisodeIdx < items.size()) {
            cnt += " · 上次看到第 " + (currentEpisodeIdx + 1) + " 集";
        }
        tvEpTitle.setText("选集　" + cnt);

        epList.removeAllViews();
        epCells.clear();
        epRows.clear();
        epColumns = calcEpColumns();

        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Channel.EpgItem item = items.get(i);
            if (i % epColumns == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(9);
                epList.addView(row, rlp);
                epRows.add(row);
            }
            TextView ep = new TextView(ctx);
            ep.setId(View.generateViewId());
            ep.setText(String.format(java.util.Locale.US, "%02d", i + 1));
            ep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            ep.setGravity(Gravity.CENTER);
            ep.setSingleLine(true);
            ep.setFocusable(true);
            ep.setFocusableInTouchMode(true);   // touch mode 下也要能获焦（同「播放」按钮）
            ep.setClickable(true);
            ep.setIncludeFontPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(67), dp(38));
            lp.rightMargin = dp(9);
            ep.setLayoutParams(lp);
            applyEpStyle(ep, i == currentEpisodeIdx, false);
            ep.setOnClickListener(v -> {
                LogBridge.d(TAG, "详情页选集 → 第" + (idx + 1) + "集");
                hide();
                if (host != null) host.onDetailPlayEpisode(channel, item);
            });
            ep.setOnFocusChangeListener((v, has) -> {
                applyEpStyle((TextView) v, idx == currentEpisodeIdx, has);
                if (has) scrollEpIntoView(v);
            });
            row.addView(ep, lp);
            epCells.add(ep);
        }
        // 按钮 ↓ 仍指向第一集（几何兜底；实际上下键由 BoundaryFrameLayout 手动切）
        if (!epCells.isEmpty() && btnPlay != null) {
            btnPlay.setNextFocusDownId(epCells.get(0).getId());
            if (btnFav != null) btnFav.setNextFocusDownId(epCells.get(0).getId());
        }
        // 打开时把「上次看到的那一集」滚进视野（布局完成后才算得出滚动量）
        if (currentEpisodeIdx >= 0 && currentEpisodeIdx < epCells.size()) {
            final View cur = epCells.get(currentEpisodeIdx);
            epScroll.post(() -> scrollEpIntoView(cur));
        }
        postSizeEpisodeArea();
    }

    private void applyEpStyle(TextView tv, boolean current, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        if (focused) {
            g.setColor(0xFFFFD166);
            g.setStroke(dp(2), 0xFFFFFFFF);
            tv.setTextColor(0xFF141414);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        } else if (current) {
            g.setColor(0x24FFD166);
            g.setStroke(dp(1), 0x99FFD166);
            tv.setTextColor(0xFFFFD166);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            g.setColor(0x14FFFFFF);
            g.setStroke(dp(1), 0x33FFFFFF);
            tv.setTextColor(0xFFD6E0F0);
            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        tv.setBackground(g);
    }

    /**
     * 列数按可用宽度算：一格 67dp + 右间距 9dp。
     * 面板刚创建时宽度还是 0，用「屏幕宽 − 左右 padding」兜底，保证首帧就是正确列数。
     */
    private int calcEpColumns() {
        int avail;
        if (epScroll != null && epScroll.getWidth() > 0) {
            avail = epScroll.getWidth();
        } else {
            avail = ctx.getResources().getDisplayMetrics().widthPixels - dp(110);
        }
        int cell = Math.max(1, dp(67) + dp(9));
        return Math.max(3, Math.min(10, avail / cell));
    }

    /**
     * 【2026-10-18】选集区高度自适应。
     *
     * <p>取「内容实际高度」与「面板剩余空间」二者较小值：
     * 集数少 → 只占内容那么高，底部不留大片空白；集数多 → 占满剩余并纵向滚动。
     * 剩余空间要等一帧布局完成才算得出，所以 post 到 View 队列里做。
     */
    private void postSizeEpisodeArea() {
        if (epScroll == null || epList == null || epSection == null || contentRoot == null) return;
        epScroll.post(() -> {
            if (!showing || epScroll == null || epList == null || epSection == null) return;
            try {
                int contentH = epList.getHeight();
                if (contentH <= 0) return;
                int avail = contentRoot.getHeight() - epSection.getTop()
                        - (tvEpTitle == null ? 0 : tvEpTitle.getHeight())
                        - dp(10) - contentRoot.getPaddingBottom();
                avail = Math.max(dp(38), avail);          // 至少留一行可视
                int target = Math.min(contentH, avail);
                ViewGroup.LayoutParams lp = epScroll.getLayoutParams();
                if (lp.height != target) {
                    lp.height = target;
                    epScroll.setLayoutParams(lp);
                    LogBridge.d(TAG, "选集区高度自适应：" + target + "px（内容 " + contentH
                            + " / 可用 " + avail + "，列数 " + epColumns + "）");
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 焦点格滚进视野（纵向）：上下留一点余量，避免贴边 */
    private void scrollEpIntoView(final View child) {
        if (epScroll == null || epList == null) return;
        epScroll.post(() -> {
            try {
                int y = child.getTop();
                View p = (View) child.getParent();
                if (p != null && p != epList) y += p.getTop();   // 行容器在 epList 内的偏移
                int margin = dp(12);
                int top = y - margin;
                int bottom = y + child.getHeight() + margin;
                if (top < epScroll.getScrollY()) {
                    epScroll.smoothScrollTo(0, Math.max(0, top));
                } else if (bottom > epScroll.getScrollY() + epScroll.getHeight()) {
                    epScroll.smoothScrollTo(0, bottom - epScroll.getHeight());
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private TextView actionButton(String label, boolean primary) {
        TextView tv = new TextView(ctx);
        tv.setId(View.generateViewId());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setPadding(dp(26), 0, dp(26), 0);
        tv.setFocusable(true);
        // 【2026-10-17 v2】必须同时声明 InTouchMode：
        //   一旦设备处于 touch mode（用过鼠标/触屏，adb tap 也算），只有
        //   focusableInTouchMode=true 的 View 才能获得焦点 —— 否则 requestFocus 永远返回 false，
        //   默认焦点落不到「播放」上（实测：日志刷 "默认焦点请求失败"）。
        tv.setFocusableInTouchMode(true);
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(37));
        tv.setLayoutParams(lp);
        applyBtnStyle(tv, primary, false);
        tv.setOnFocusChangeListener((v, has) -> applyBtnStyle((TextView) v, primary, has));
        return tv;
    }

    private void applyBtnStyle(TextView tv, boolean primary, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(19));
        if (focused) {
            g.setColor(primary ? 0xFFFFD166 : 0xFF2C3446);
            g.setStroke(dp(2), 0xFFFFFFFF);
            tv.setTextColor(primary ? 0xFF141414 : 0xFFFFFFFF);
        } else if (primary) {
            g.setColor(0xFFFFD166);
            tv.setTextColor(0xFF141414);
        } else {
            g.setColor(0x14FFFFFF);
            g.setStroke(dp(1), 0x33FFFFFF);
            tv.setTextColor(0xFFE9EEF7);
        }
        tv.setBackground(g);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
    }

    private void refreshFavorite() {
        if (btnFav == null) return;
        boolean fav = host != null && host.onDetailIsFavorite(channel);
        btnFav.setText(fav ? "✓ 已收藏" : "＋ 收藏");
    }

    // ==================== 数据填充 ====================

    private String safeLogo() {
        return (channel == null) ? null : channel.getLogoUrl();
    }

    private String sourceLabel() {
        int src = 0;
        try {
            String cid = (channel == null) ? null : channel.getChannelId();
            if (channel != null) {
                if (AfhgrjUrlResolver.vodIdOf(channel) != null) src = 7;
                else if (!TextUtils.isEmpty(
                        com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(cid))) src = 5;
            }
        } catch (Throwable ignored) {
        }
        return src > 0 ? ("源" + src + " · 点播") : "点播";
    }

    private String lineInfoLabel() {
        try {
            String vodId = AfhgrjUrlResolver.vodIdOf(channel);
            if (vodId != null) {
                int[] sids = AfhgrjUrlResolver.sidsOf(vodId);
                if (sids != null && sids.length > 1) {
                    return "共 " + sids.length + " 条线路 · 播放后可切换";
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 列表里已经有的信息先渲染，不白屏等详情 */
    private void fillSkeleton() {
        if (tvTitle != null) tvTitle.setText(channel.getName());
        if (tvSubtitle != null) tvSubtitle.setText("正在读取详情…");
        if (tvFields != null) tvFields.setVisibility(View.GONE);
        if (tvSynopsis != null) tvSynopsis.setVisibility(View.GONE);
    }

    private void loadDetailAsync() {
        final String vodId = AfhgrjUrlResolver.vodIdOf(channel);
        if (vodId == null) {
            // 源5：没有详情接口，用列表信息收口（名称里通常已含「· 全28集」）
            mainPost(() -> {
                if (tvSubtitle != null) tvSubtitle.setText("点播影片");
                if (tvSynopsis != null) {
                    tvSynopsis.setVisibility(View.VISIBLE);
                    tvSynopsis.setText("该影片暂无简介，可直接播放。");
                }
            });
            return;
        }
        new Thread(() -> {
            AfhgrjUrlResolver.Detail d = null;
            try {
                d = AfhgrjUrlResolver.getDetail(vodId);
            } catch (Throwable t) {
                LogBridge.w(TAG, "取详情失败: " + t.getMessage());
            }
            final AfhgrjUrlResolver.Detail detail = d;
            mainPost(() -> renderDetail(detail));
        }, "VodDetailPanel-Load").start();
    }

    private void renderDetail(AfhgrjUrlResolver.Detail d) {
        if (!showing || panel == null) return;
        if (d == null || d.isEmpty()) {
            if (tvSubtitle != null) tvSubtitle.setText("暂无详细信息");
            if (tvSynopsis != null) {
                tvSynopsis.setVisibility(View.VISIBLE);
                tvSynopsis.setText("该影片详情暂不可用，可直接播放或选集。");
            }
            return;
        }
        if (tvSubtitle != null && !TextUtils.isEmpty(d.subtitle())) {
            tvSubtitle.setText(d.subtitle());
        }
        StringBuilder fields = new StringBuilder();
        if (!TextUtils.isEmpty(d.actor)) fields.append("主演：").append(d.actor);
        if (!TextUtils.isEmpty(d.director)) {
            if (fields.length() > 0) fields.append("\n");
            fields.append("导演：").append(d.director);
        }
        if (!TextUtils.isEmpty(d.category)) {
            if (fields.length() > 0) fields.append("\n");
            fields.append("类型：").append(d.category);
        }
        if (tvFields != null) {
            if (fields.length() > 0) {
                tvFields.setText(fields.toString());
                tvFields.setVisibility(View.VISIBLE);
            } else {
                tvFields.setVisibility(View.GONE);
            }
        }
        if (tvSynopsis != null && !TextUtils.isEmpty(d.content)) {
            tvSynopsis.setText(d.content);
            tvSynopsis.setVisibility(View.VISIBLE);
        }
        LogBridge.d(TAG, "详情已补全：" + channel.getName());
        // 详情与集数往往同一批解析完成：这里再探一次集数（仅在选择区还没显示时重建，避免打断焦点）
        if (epSection != null && epSection.getVisibility() != View.VISIBLE) {
            buildEpisodes();
        }
        // ⚠ 只更新文本，不重建可聚焦视图 —— 否则焦点会掉
    }

    private List<Channel.EpgItem> epgOfChannel() {
        try {
            return EpgManager.getInstance(ctx.getApplicationContext()).getEpg(channel);
        } catch (Throwable t) {
            LogBridge.w(TAG, "取集数失败: " + t.getMessage());
            return null;
        }
    }

    /** 集数未就绪 → 等 EPG 回调到了再重建一次选集（只注册一次） */
    private void registerEpgListener() {
        if (epgListener != null) return;
        try {
            final EpgManager mgr = EpgManager.getInstance(ctx.getApplicationContext());
            epgListener = () -> mainPost(() -> {
                if (!showing || epSection == null) {
                    unregisterEpgListener();
                    return;
                }
                List<Channel.EpgItem> items = epgOfChannel();
                if (items == null || items.isEmpty()) return;   // 不是这部片的数据，继续等
                buildEpisodes();
            });
            mgr.registerEpgDataReadyListener(epgListener);
            LogBridge.d(TAG, "集数未就绪 → 已注册 EPG 回调：" + channel.getName());
        } catch (Throwable t) {
            LogBridge.w(TAG, "注册 EPG 回调失败: " + t.getMessage());
            epgListener = null;
        }
    }

    // ==================== 工具 ====================

    private void mainPost(Runnable r) {
        if (ctx == null || ctx.isFinishing()) return;
        ctx.runOnUiThread(r);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(s == null ? "" : s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    /**
     * 【2026-10-17 v2】焦点边界容器。
     *
     * <p>详情页是 addView 到 ott_root 的普通覆盖层，**不是独立 Window**：
     * 当某方向找不到可聚焦目标时，系统焦点搜索会越过本面板，落到下层的频道网格上 ——
     * 表现就是「详情页还在，焦点却跑到背后」，再按 OK 还会把背后的海报点开（实测）。
     *
     * <p>这里做两件事：
     * <ol>
     *   <li>{@link #focusSearch}：算出来的目标不在面板内（含 null）一律留在原地；</li>
     *   <li>{@link #dispatchKeyEvent}：上/下键在「按钮行 ↔ 选集行」之间**手动**切，
     *       不依赖 nextFocusDownId（实测重建选集后该值会失效，焦点会跳到最后一集）。</li>
     * </ol>
     */
    class BoundaryFrameLayout extends FrameLayout {
        BoundaryFrameLayout(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final int kc = event.getKeyCode();
                final View focus = ctx.getCurrentFocus();
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN && isActionRow(focus)) {
                    // 按钮行 ↓ → 当前集（没有集数就吞掉，绝不让焦点漏到背后的网格）
                    if (!epCells.isEmpty()) {
                        int idx = Math.max(0, Math.min(
                                currentEpisodeIdx < 0 ? 0 : currentEpisodeIdx,
                                epCells.size() - 1));
                        focusCell(epCells.get(idx));
                        LogBridge.d(TAG, "焦点：按钮行 ↓ → 选集第 " + (idx + 1) + " 集");
                    } else {
                        LogBridge.d(TAG, "焦点：无选集，↓ 键吞掉");
                    }
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP && isEpisodeCell(focus)) {
                    // 纵向网格：不在首行就先回到上一行同列，首行才回按钮
                    int rowIdx = rowOf(focus);
                    if (rowIdx > 0) {
                        int col = epCells.indexOf(focus) % epColumns;
                        int prev = (rowIdx - 1) * epColumns + col;
                        if (prev >= 0 && prev < epCells.size()) {
                            focusCell(epCells.get(prev));
                            LogBridge.d(TAG, "焦点：选集 ↑ → 上一行第 " + (prev + 1) + " 集");
                            return true;
                        }
                    }
                    if (btnPlay != null) {
                        btnPlay.requestFocus();
                        LogBridge.d(TAG, "焦点：选集 ↑ → 播放按钮");
                    }
                    return true;
                }
                // 行末右键 / 行首左键：跨行接续（几何搜索在这两种位置会走空）
                if ((kc == KeyEvent.KEYCODE_DPAD_RIGHT || kc == KeyEvent.KEYCODE_DPAD_LEFT)
                        && isEpisodeCell(focus)) {
                    int i = epCells.indexOf(focus);
                    boolean wrapRight = kc == KeyEvent.KEYCODE_DPAD_RIGHT
                            && (i % epColumns == epColumns - 1) && i + 1 < epCells.size();
                    boolean wrapLeft = kc == KeyEvent.KEYCODE_DPAD_LEFT
                            && (i % epColumns == 0) && i - 1 >= 0;
                    if (wrapRight || wrapLeft) {
                        focusCell(epCells.get(wrapRight ? i + 1 : i - 1));
                        return true;
                    }
                }
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public View focusSearch(View focused, int direction) {
            // 焦点不在本面板内时不干预（否则会影响别处的正常搜索）
            if (focused == null || !isInside(focused)) {
                return super.focusSearch(focused, direction);
            }
            // 【2026-10-18 横向移动失效修复】关键：**只在面板子树里搜**。
            //   super.focusSearch 会把搜索范围放大到整个窗口，面板背后首页的海报网格同样在候选里，
            //   且与选集格处在同一水平/垂直带上 —— 横向移动时常常选中背后的海报，被下面的越界守卫
            //   拦下后表现为「按左右键完全不动」（实测：↓ 能走、←→ 全被拦）。
            //   以本面板为 root 搜索，候选集天然只有按钮行 + 选集格，方向键行为可预期。
            View next = android.view.FocusFinder.getInstance()
                    .findNextFocus(this, focused, direction);
            if (next == null || !isInside(next)) {
                LogBridge.d(TAG, "详情页焦点边界：方向 " + direction
                        + " 无可用目标/越界 → 留在原地");
                return focused;
            }
            return next;
        }

        private boolean isInside(View v) {
            View cur = v;
            while (cur != null) {
                if (cur == this) return true;
                android.view.ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
            return false;
        }
    }

    private boolean isActionRow(View v) {
        return v != null && (v == btnPlay || v == btnFav);
    }

    private boolean isEpisodeCell(View v) {
        return v != null && epCells.contains(v);
    }

    /** 该格子在第几行（-1 = 不在选集里） */
    private int rowOf(View cell) {
        if (cell == null) return -1;
        int idx = epCells.indexOf(cell);
        return idx < 0 ? -1 : idx / epColumns;
    }

    /** 聚焦某个格子并保证它滚进视野 */
    private void focusCell(View cell) {
        if (cell == null) return;
        cell.requestFocus();
        scrollEpIntoView(cell);
    }
}
