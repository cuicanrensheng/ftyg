package com.tv.live;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.ui.PlayerView;

import com.tv.live.config.AppConfig;
import com.tv.live.manager.AppCoreManager;
import com.tv.live.manager.DisplayManager;
import com.tv.live.manager.HuyaTogetherWatchGroupManager;
import com.tv.live.manager.HuyaTogetherWatchManager;
import com.tv.live.util.AppExecutors;
import com.tv.live.util.ChannelLogoLoader;
import com.tv.live.util.LogBridge;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OTT 模式首页（电视端）。
 *
 * 版式参考 YM 的 fragment_horizontal_home（顶部 Logo + 分类 Tab + 内容网格），
 * 并在左侧补一块 16:9 的实时播放预览，选中频道即在预览区起播。
 *
 * 遥控交互：
 *   - 左右键：在顶部「分类 Tab」之间移动；进入网格后左右键移动列焦点
 *   - 上下键：网格换行 / 全屏态下切换上下一个频道
 *   - OK   ：网格项起播；全屏态下退出全屏
 *   - 菜单键：打开设置（可切换回「直播模式」）
 *   - 返回键：全屏态先退全屏；非全屏态连按两次退出
 */
public class OttHomeActivity extends AppCompatActivity {

    private static final String TAG = "OttHome";
    public static final String EXTRA_SKIP_SOURCE_ROUTE = "skip_source_route";

    private static final long DOUBLE_BACK_INTERVAL_MS = 2000L;
    private static final int PREVIEW_HEIGHT_DP = 200;
    /** 虎牙 SDK 就绪等待上限：超过则视为不可用，避免一直显示「加载中」 */
    private static final long HUYA_SDK_WAIT_MS = 12000L;
    /** 【2026-09-29 自动全屏】起播后若本时长内无任何用户操作，自动进入全屏 */
    private static final long AUTO_FULLSCREEN_DELAY_MS = 5000L;

    private AppConfig appConfig;
    private TVPlayerManager mPlayerManager;
    private AppCoreManager appCoreManager;
    private DisplayManager displayManager;

    private PlayerView playerView;
    private FrameLayout flPreview;
    /** 【2026-09-29 播放器获焦】焦点环与提示（必须叠在 PlayerView 之上，父背景描边会被 PlayerView 盖住） */
    private View previewFocusRing;
    private View previewFocusHint;
    /** 【2026-09-29 自动全屏提示】仅"5s 无操作自动进全屏"时短暂显示，普通点按进入全屏不显示 */
    private static final long FULLSCREEN_HINT_MS = 3000L;
    private TextView fullscreenHint;
    private final Runnable hideFullscreenHintRunnable = () -> {
        if (fullscreenHint != null) fullscreenHint.setVisibility(View.GONE);
    };
    private LinearLayout llCategory;
    private GridView gvChannel;
    /** 虎牙子分组胶囊条（与下方频道网格同页显示） */
    private HorizontalScrollView svSubGroup;
    private LinearLayout llSubGroup;
    private final List<TextView> subGroupChipViews = new ArrayList<>();
    private TextView tvNowChannel;
    private TextView tvNowProgram;
    /** 【2026-09-29 方案 A 电信 IPTV 风格】左面板「节目单 + 元数据」 */
    private LinearLayout llEpgPanel;
    private LinearLayout llEpgList;
    private TextView tvMetadata;
    private TextView tvPreviewTip;
    private TextView tvBreadcrumb;
    private TextView tvEmpty;
    private View topBar;
    private View rightPanel;
    private View btnMenu;
    private View btnFullscreen;

    /** 【2026-09-29 OTT真全屏】外层带装饰内边距的 LinearLayout 与内容行，全屏时临时清零其边距 */
    private LinearLayout ottOuter;
    private View ottContentRow;
    private int savedPadLeft, savedPadTop, savedPadRight, savedPadBottom, savedContentMarginTop;
    private boolean ottInsetsSaved = false;

    private final List<Channel> allChannels = new ArrayList<>();
    private final List<Channel> shownChannels = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private final List<String> lastGroupNames = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 虎牙两条线路（HTTP API 一起看 + SDK 固定分组）合并后的频道 */
    private final List<Channel> huyaChannels = new ArrayList<>();
    private final List<String> huyaGroupNames = new ArrayList<>();
    private final Set<String> huyaChannelKeys = new LinkedHashSet<>();

    /** 第2路（虎牙电影/电视剧/动漫/综艺/其他 固定分组）独立池：不再并入首页共享池 huyaChannels，
     *  首页网格照常显示，但两路不复用同一个去重池，避免同名房间被合并去重。 */
    private final List<Channel> huyaLine2Channels = new ArrayList<>();
    private final Set<String> huyaLine2Keys = new LinkedHashSet<>();

    private ChannelAdapter channelAdapter;
    private String currentGroup;
    private String huyaSubGroup;
    /** 胶囊条当前指向的子分组下标（-1 = 尚未定位，按正在播放的频道回落） */
    private int huyaGroupCursor = -1;
    private boolean huyaLoadStarted = false;
    private boolean huyaLoadDone = false;
    private Channel playingChannel;
    private boolean fullscreen = false;
    private boolean switchingToLive = false;
    private boolean tabsBuiltOnce = false;
    private long lastBackMs = 0L;

    /** 【2026-09-29 OTT 起播】用户是否自己动手选过分组。
     *  没选过 → 顶部 Tab 一直跟随正在播放的频道；选过 → 尊重用户选择，不再自动挪。 */
    private boolean userPickedGroup = false;

    /** 【2026-09-29 退全屏焦点空窗】setFullscreen(false) 里 flPreview 恢复可获焦与
     *  gvChannel.post(requestFocus) 之间隔了一帧，空窗期内系统会把焦点交给布局里第一个
     *  可获焦控件（顶栏第一个 Tab「全部」），命中 Tab 的「焦点即切换」→ 分组被重置。
     *  本标志在整段过渡期间屏蔽 Tab 的焦点即切换，延后一帧再解除。
     *  证据：真机日志 `15:41:29 Tab 获焦即切换分组：卫视 → 全部` 与 `15:41:29 全屏状态：false` 同一时刻，独立复现 3 次。 */
    private boolean suppressTabFocusSwitch = false;

    /** 【2026-09-29 退全屏蓝框修复】全屏切换代际计数：每次 setFullscreen 递增，
     *  迟到的上一轮焦点恢复任务（postDelayed secondHop）凭 epoch 失配自动作废。 */
    private int fullscreenEpoch = 0;

    /** 【2026-09-29 频道号 OSD】右上角显示频道号，3s 自动隐藏（口径同直播模式） */
    private TextView tvOttChannelNum;
    private static final long CHANNEL_NUM_HIDE_MS = 3000L;
    private final Runnable hideChannelNumRunnable = () -> {
        if (tvOttChannelNum != null) tvOttChannelNum.setVisibility(View.GONE);
    };

    /** 【2026-09-29 数字键输号跳台】4 位缓冲，1.5s 无输入即确认（口径同直播模式） */
    private final StringBuilder numberInputBuffer = new StringBuilder();
    private static final long NUMBER_INPUT_CONFIRM_MS = 1500L;
    private final Runnable numberInputConfirmRunnable = this::confirmNumberInputJump;

    /** 【2026-09-29 自动全屏】计时器是否待触发（用于日志降噪与幂等取消） */
    private boolean autoFullscreenArmed = false;

    /** 【2026-09-29 自动全屏】到期后若仍在播放且未全屏，则进入全屏 */
    private final Runnable autoFullscreenRunnable = new Runnable() {
        @Override public void run() {
            autoFullscreenArmed = false;
            if (isFinishing() || fullscreen || playingChannel == null) return;
            LogBridge.i(TAG, "5s 内无操作，自动进入全屏");
            setFullscreen(true);
            // 【2026-09-29 自动全屏提示】全屏后 onKeyDown 会接管按键做播放器动作，
            // 必须给用户一个"我已进入全屏"的提示，否则会以为遥控失灵
            showFullscreenHint();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appConfig = AppConfig.getInstance(this);
        currentGroup = getString(R.string.ott_group_all);

        setContentView(R.layout.activity_ott_home);

        displayManager = new DisplayManager(this);
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bindViews();
        setupActions();
        initPlayer();
        initAppCoreManager();

        AppExecutors.io(() -> {
            if (appCoreManager != null) appCoreManager.loadLiveAndEpg();
        });

        ensureHuyaLoaded();

        LogBridge.i(TAG, "OTT 首页已启动");
    }

    private void bindViews() {
        flPreview = findViewById(R.id.fl_ott_preview);
        // 【2026-09-29 播放器获焦】焦点环 / 提示（叠在 PlayerView 之上）
        previewFocusRing = findViewById(R.id.v_ott_preview_focus_ring);
        previewFocusHint = findViewById(R.id.tv_ott_preview_focus_hint);
        // 【2026-09-29 自动全屏提示】叠在 ott_root 最上层，仅自动全屏时短暂可见
        fullscreenHint = findViewById(R.id.tv_ott_fullscreen_hint);
        // 【2026-09-29 频道号 OSD】右上角频道号，3s 自动隐藏
        tvOttChannelNum = findViewById(R.id.tv_ott_channel_num);
        llCategory = findViewById(R.id.ll_ott_category);
        gvChannel = findViewById(R.id.gv_ott_channel);
        svSubGroup = findViewById(R.id.sv_ott_subgroup);
        llSubGroup = findViewById(R.id.ll_ott_subgroup);
        tvNowChannel = findViewById(R.id.tv_ott_now_channel);
        tvNowProgram = findViewById(R.id.tv_ott_now_program);
        llEpgPanel = findViewById(R.id.ll_ott_epg_panel);
        llEpgList = findViewById(R.id.ll_ott_epg_list);
        tvMetadata = findViewById(R.id.tv_ott_metadata);
        tvPreviewTip = findViewById(R.id.tv_ott_preview_tip);
        tvBreadcrumb = findViewById(R.id.tv_ott_breadcrumb);
        tvEmpty = findViewById(R.id.tv_ott_empty);
        playerView = findViewById(R.id.ott_player_view);
        topBar = findViewById(R.id.ott_top_bar);
        rightPanel = findViewById(R.id.ott_right_panel);
        btnMenu = findViewById(R.id.btn_ott_menu);
        btnFullscreen = findViewById(R.id.btn_ott_fullscreen);
        // 【2026-09-29 OTT真全屏】全屏时需要清零边距的两个容器
        ottOuter = findViewById(R.id.ll_ott_outer);
        ottContentRow = findViewById(R.id.ott_content_row);

        if (playerView != null) {
            playerView.setUseController(false);
            playerView.setFocusable(false);
            playerView.setFocusableInTouchMode(false);
        }

        // 【2026-09-29 播放器获焦】预览卡可获焦：遥控移到播放器上、按确认键进入全屏
        flPreview.setFocusable(true);
        flPreview.setFocusableInTouchMode(false);
        flPreview.setOnClickListener(v -> {
            LogBridge.i(TAG, "播放器获焦后按确认键 → 进入全屏");
            setFullscreen(true);
        });
        flPreview.setOnFocusChangeListener((v, hasFocus) -> {
            LogBridge.i(TAG, "播放器焦点：" + hasFocus);
            // 【2026-09-29 播放器获焦】全屏时 flPreview 会成为唯一可获焦视图、被系统反复抢焦点，
            // 回调可能在 setFullscreen 收环之后再次点亮 → 必须用 fullscreen 兜底，
            // 否则全屏画面会贴出一圈蓝框（此前只靠"最后一次恰为 false"侥幸没出问题）
            boolean show = hasFocus && !fullscreen;
            if (previewFocusRing != null) {
                previewFocusRing.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (previewFocusHint != null) {
                previewFocusHint.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });

        channelAdapter = new ChannelAdapter();
        gvChannel.setAdapter(channelAdapter);
        // 遥控焦点框：GridView 自身持有焦点，listSelector 画在卡片之上，方向键在网格内逐格移动
        gvChannel.setDrawSelectorOnTop(true);
        gvChannel.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < shownChannels.size()) {
                playChannel(shownChannels.get(position));
            }
        });

        // 子分组胶囊条：单行横向滚动，焦点/点按哪个胶囊，下方网格就显示该分组的频道
        if (svSubGroup != null) {
            svSubGroup.setVisibility(View.GONE);
        }

        tvPreviewTip.setText(R.string.ott_preview_idle);
    }

    /** 子分组胶囊条总高度（仅一行）：供 HorizontalScrollView 固定高度，避免分组变多时多占行 */
    private int subGroupStripHeight() {
        return dp(31);
    }

    /**
     * 重建子分组胶囊条。用动态 TextView 而非 GridView：
     * 胶囊按内容宽度自适应、横排一行、超出可横向滚动，恒占 1 行高度。
     */
    private void buildSubGroupChips() {
        if (llSubGroup == null) return;
        llSubGroup.removeAllViews();
        subGroupChipViews.clear();

        for (int i = 0; i < huyaGroupNames.size(); i++) {
            final int position = i;
            String name = huyaGroupNames.get(i);

            TextView chip = new TextView(this);
            chip.setId(View.generateViewId());
            chip.setText(name);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTextColor(Color.WHITE);
            chip.setBackgroundResource(R.drawable.ott_tab_item_bg);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setPadding(dp(18), dp(7), dp(18), dp(7));
            chip.setNextFocusDownId(R.id.gv_ott_channel);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            chip.setLayoutParams(lp);

            chip.setSelected(name.equals(huyaSubGroup));
            chip.setTag(position);

            chip.setOnClickListener(v -> {
                huyaGroupCursor = position;
                huyaSubGroup = huyaGroupNames.get(position);
                refreshHuyaChannelGrid();
                syncSubGroupChipState();
                // 选定分组后焦点直接落到频道网格，遥控一次「下」即可选台
                gvChannel.post(() -> {
                    gvChannel.setSelection(0);
                    gvChannel.requestFocus();
                });
            });
            chip.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) return;
                if (huyaGroupCursor == position && huyaSubGroup == null) return;
                huyaGroupCursor = position;
                // 焦点浏览时解除上一级选定，网格重新跟随焦点
                huyaSubGroup = null;
                refreshHuyaChannelGrid();
                syncSubGroupChipState();
            });

            llSubGroup.addView(chip);
            subGroupChipViews.add(chip);
        }
    }

    /** 刷新胶囊选中态：只有「已选定」的胶囊高亮，焦点态由背景 selector/系统焦点框体现 */
    private void syncSubGroupChipState() {
        for (int i = 0; i < subGroupChipViews.size(); i++) {
            View chip = subGroupChipViews.get(i);
            Object tag = chip.getTag();
            int position = (tag instanceof Integer) ? (Integer) tag : -1;
            boolean selected = position >= 0 && position < huyaGroupNames.size()
                    && huyaGroupNames.get(position).equals(huyaSubGroup);
            chip.setSelected(selected);
        }
    }

    /** 把焦点/滚动定位到当前子分组胶囊 */
    private void focusSubGroupChip() {
        if (llSubGroup == null || huyaGroupCursor < 0 || huyaGroupCursor >= subGroupChipViews.size()) return;
        final View chip = subGroupChipViews.get(huyaGroupCursor);
        chip.post(() -> {
            chip.requestFocus();
            if (svSubGroup != null) {
                int x = chip.getLeft() - dp(16);
                svSubGroup.smoothScrollTo(Math.max(0, x), 0);
            }
        });
    }

    private void setupActions() {
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> openSettings());
        }
        if (btnFullscreen != null) {
            btnFullscreen.setOnClickListener(v -> setFullscreen(!fullscreen));
        }
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.setOnPlayerViewRecreatedListener(newPlayerView -> {
            OttHomeActivity.this.playerView = newPlayerView;
            if (newPlayerView != null) {
                newPlayerView.setUseController(false);
            }
            LogBridge.d(TAG, "PlayerView 重建完成");
        });
        mPlayerManager.attachPlayerView(playerView);
        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override public void onIdle() { setPreviewLoading(false); }
            @Override public void onBuffering() { setPreviewLoading(true); }
            @Override public void onPlayReady() { setPreviewLoading(false); }
            @Override public void onPlayEnd() { setPreviewLoading(false); }
            @Override public void onPlayError(String msg) { setPreviewLoading(false); }
        });
        // 【2026-09-29 方案 A 电信 IPTV 风格】LiveInfo（分辨率/码率/格式/音频）回调刷元数据小字
        mPlayerManager.setOnLiveInfoUpdateListener(info -> mainHandler.post(() -> refreshMetadata()));
    }

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);
        // 直播模式下的 AppCoreManager 由 MainActivity 注册广播接收器；OTT 首页是独立 Activity，
        // 必须自己注册，否则设置里切换直播源发出的 REFRESH_LIVE_AND_EPG 收不到，
        // 顶部分组 Tab 会一直停在上一次直播源的分组上（表现为「分组被固定」）。
        appCoreManager.registerReceivers();
        appCoreManager.setOnRefreshListener(() -> mainHandler.post(() -> {
            LogBridge.i(TAG, "直播源已切换/更新，等待新分组数据到达");
            refreshNowPlayingProgram();
        }));
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                final List<Channel> list = appCoreManager.getChannelList();
                mainHandler.post(() -> applyChannels(list));
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                mainHandler.post(() -> {
                    if (allChannels.isEmpty()) {
                        showEmpty(getString(R.string.ott_channel_empty));
                    }
                });
            }

            @Override
            public void onEpgLoaded() {
                mainHandler.post(() -> refreshNowPlayingProgram());
            }

            @Override
            public void onLoadTimeout(boolean hasData) {
                if (!hasData) {
                    mainHandler.post(() -> showEmpty(getString(R.string.ott_channel_empty)));
                }
            }
        });
    }

    // ============================ 频道 / 分类 ============================

    private void applyChannels(List<Channel> list) {
        if (list == null) return;
        // 切换直播源时 AppCoreManager 会先回一次空列表（缓存已清、网络数据未回），
        // 此时保留当前分组与频道不动，等新源数据到达再整体刷新，避免分组 Tab 闪没。
        if (list.isEmpty()) {
            if (allChannels.isEmpty()) showEmpty(getString(R.string.ott_channel_empty));
            return;
        }

        allChannels.clear();
        allChannels.addAll(list);

        buildCategoryTabs();
        applyFilter();

        // 【2026-09-29 OTT 起播】直播源分批回调会反复重建分组集合，
        // 只要用户还没自己选过分组，就让顶部 Tab 继续跟随正在播放的频道
        if (!userPickedGroup && playingChannel != null) {
            syncTabsToPlayingChannel();
        }

        // 冷启动只自动起播直播源频道：虎牙频道要等 SDK 就绪后才可播（见 ensureHuyaLoaded）
        // 【2026-09-29 修复：切到虎牙频道后被强行拉回 CCTV1】
        // 原判据只用 isPlaying()，但它在缓冲/重建/Surface 交接等瞬时状态下可能为 false，
        // 此时 autoPlayFirstChannel() 会按「上次记录」（getOttLastPlayIndex）续播 ——
        // 而虎牙频道根本不会写进该记录（indexOfEquivalent 在 allChannels 里找不到虎牙频道，
        // 见 playChannel 里的 liveIndex >= 0 判断），记录仍是上一次的直播源频道
        // （第一个直播源分组的首个频道，通常就是 CCTV1综合）→ 用户正在看的虎牙频道被顶掉。
        // 判据补上「播放器是否还持有频道」：只要还拿着频道（含虎牙），就不自动起播。
        if (mPlayerManager.getCurrentChannel() == null && !mPlayerManager.isPlaying()) {
            autoPlayFirstChannel();
        }
    }

    /**
     * 【2026-09-29 OTT 起播】冷启动起播：
     * ① 有有效的"上次播放"记录 → 续播（保留"记住上次看什么"）；
     * ② 无记录（OTT 专用键默认 -1；旧键 last_play_index 的默认值 0 不再参与）→
     *    从「第一个直播源分组」的第一个频道起播，而不是全列表第一个
     *    （此前会落到「全部」里的 CCTV14少儿，用户反馈"每次自动播放的都是全部里面的频道"）；
     * ③ 起播后把顶部分组切到该频道所属分组，实现"Tab 跟随起播频道"。
     */
    private void autoPlayFirstChannel() {
        int idx = appConfig.getOttLastPlayIndex();
        Channel target = null;
        if (idx >= 0 && idx < allChannels.size()) {
            target = allChannels.get(idx);
        }
        if (target != null) {
            LogBridge.i(TAG, "OTT 起播：按上次记录续播 idx=" + idx + " 频道=" + target.getName());
        } else {
            target = firstChannelOfFirstLiveGroup();
            LogBridge.i(TAG, "OTT 起播：无有效记录，取第一个直播源分组的首个频道="
                    + (target == null ? "(无)" : target.getName()
                       + " 分组=" + GroupListManager.getNormalizedGroup(target)));
        }
        if (target == null && !allChannels.isEmpty()) {
            target = allChannels.get(0);
            LogBridge.w(TAG, "OTT 起播：未取到目标频道，回落 allChannels[0]");
        }
        if (target == null) return;

        playChannel(target);
        syncTabsToPlayingChannel();
    }

    /**
     * 【2026-09-29 OTT 起播】第一个直播源分组的第一个可播频道。
     * 跳过「全部」（它是合成的全量列表，不是直播源分组）与「虎牙频道」（要等 SDK 就绪才可播）。
     * 分组顺序由 buildGroupNames() 动态推导（= [全部] + allChannels 顺序去重后的归一化分组 + [虎牙频道]），
     * 所以这里不要硬编码分组名。
     */
    private Channel firstChannelOfFirstLiveGroup() {
        List<String> cats = buildGroupNames();
        String all = getString(R.string.ott_group_all);
        String huya = getString(R.string.ott_group_huya);
        for (String g : cats) {
            if (g == null || g.equals(all) || g.equals(huya)) continue;
            for (Channel c : allChannels) {
                if (c == null || TextUtils.isEmpty(c.getPlayUrl())) continue;
                if (g.equals(GroupListManager.getNormalizedGroup(c))) return c;
            }
        }
        return null;
    }

    /** 【2026-09-29 OTT 起播】把顶部分组切到正在播放的频道所属分组（起播后 Tab 跟随） */
    private void syncTabsToPlayingChannel() {
        if (playingChannel == null) {
            LogBridge.i(TAG, "Tab跟随：playingChannel 为空，跳过");
            return;
        }
        if (isHuyaTab()) {
            LogBridge.i(TAG, "Tab跟随：当前已是虎牙页，跳过");
            return;
        }
        String g = GroupListManager.getNormalizedGroup(playingChannel);
        if (TextUtils.isEmpty(g)) {
            LogBridge.i(TAG, "Tab跟随：频道无分组，跳过");
            return;
        }
        if (!buildGroupNames().contains(g)) {
            LogBridge.i(TAG, "Tab跟随：分组[" + g + "]不在Tab集合里，跳过");
            return;
        }
        if (g.equals(currentGroup)) {
            LogBridge.i(TAG, "Tab跟随：已一致=" + g);
            return;
        }
        // 先把"原分组"打进日志，再覆盖 currentGroup，否则原值会被自己冲掉
        LogBridge.i(TAG, "Tab跟随起播频道 → " + g + "（原=" + currentGroup + "）");
        currentGroup = g;
        huyaSubGroup = null;
        buildCategoryTabs();
        applyFilter();
    }

    /** 顶部分组 Tab = 全部 + 直播源分组（按 GroupListManager 归一化口径）+ 虎牙频道 */
    private void buildCategoryTabs() {
        List<String> cats = buildGroupNames();

        // 直播源切换/更新后，原先选中的分组可能已不存在：优先跟随正在播放的频道，
        // 其次才回落「全部」，否则会停留在旧分组上一直显示「暂无频道」。
        if (currentGroup != null && !cats.contains(currentGroup)) {
            // 【2026-09-29 OTT 起播】原分组已不存在：优先跟随正在播放的频道，其次才回落「全部」
            String pg = (playingChannel == null)
                    ? null : GroupListManager.getNormalizedGroup(playingChannel);
            currentGroup = (pg != null && cats.contains(pg))
                    ? pg : getString(R.string.ott_group_all);
            huyaSubGroup = null;
            LogBridge.i(TAG, "分组集合变更，原分组失效 → 当前分组=" + currentGroup);
        }

        // 分组集合未变时不重建 Tab，避免打断遥控焦点
        if (cats.equals(lastGroupNames) && !tabViews.isEmpty()) {
            syncTabSelection();
            syncGridFocusUp();
            return;
        }
        lastGroupNames.clear();
        lastGroupNames.addAll(cats);

        llCategory.removeAllViews();
        tabViews.clear();

        for (String cat : cats) {
            TextView tab = new TextView(this);
            tab.setId(View.generateViewId());
            tab.setText(cat);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setTextColor(Color.WHITE);
            tab.setBackgroundResource(R.drawable.ott_tab_item_bg);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setPadding(dp(18), dp(7), dp(18), dp(7));
            tab.setNextFocusDownId(R.id.gv_ott_channel);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            tab.setLayoutParams(lp);

            tab.setOnClickListener(v -> selectGroup(cat));
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                v.setScaleX(hasFocus ? 1.06f : 1f);
                v.setScaleY(hasFocus ? 1.06f : 1f);
                // 【2026-09-29 焦点即切换】与下方子分组胶囊交互统一：焦点移到哪个分组就立刻切过去，
                // 不再要求再按一次确认键（此前两处交互不一致，用户反馈"需要二次按键确认"）。
                // 守卫 cat.equals(currentGroup)：从网格按「上」落回当前 Tab 时不触发无谓重建。
                // 【2026-09-29 退全屏焦点空窗】suppressTabFocusSwitch 期间（退全屏过渡的一帧内）
                // 焦点若被系统自动丢给 Tab，不算用户操作，不得改分组
                if (hasFocus && !suppressTabFocusSwitch && !cat.equals(currentGroup)) {
                    LogBridge.i(TAG, "Tab 获焦即切换分组：" + currentGroup + " → " + cat);
                    selectGroup(cat);
                }
            });

            tab.setSelected(cat.equals(currentGroup));
            llCategory.addView(tab);
            tabViews.add(tab);
        }

        syncGridFocusUp();

        // 仅首次构建时把焦点落到网格；后续刷新分组不再抢焦点
        if (!tabsBuiltOnce) {
            tabsBuiltOnce = true;
            if (gvChannel != null) {
                gvChannel.post(() -> {
                    gvChannel.setSelection(0);
                    gvChannel.requestFocus();
                });
            }
        }
    }

    /** 分组集合：全部 + 直播源分组（按 GroupListManager 归一化）+ 虎牙频道 */
    private List<String> buildGroupNames() {
        Set<String> autoGroups = new LinkedHashSet<>();
        for (Channel c : allChannels) {
            if (c == null) continue;
            autoGroups.add(GroupListManager.getNormalizedGroup(c));
        }

        List<String> cats = new ArrayList<>();
        cats.add(getString(R.string.ott_group_all));
        cats.addAll(autoGroups);
        cats.add(getString(R.string.ott_group_huya));
        return cats;
    }

    private boolean isHuyaTab() {
        return getString(R.string.ott_group_huya).equals(currentGroup);
    }

    private void syncTabSelection() {
        for (TextView tab : tabViews) {
            tab.setSelected(currentGroup != null && currentGroup.contentEquals(tab.getText()));
        }
    }

    /** 【2026-09-29 播放器按上】当前分组对应的 Tab 视图；找不到返回 null */
    private TextView selectedTabView() {
        for (TextView tab : tabViews) {
            if (tab != null && currentGroup != null
                    && currentGroup.contentEquals(tab.getText())) {
                return tab;
            }
        }
        return null;
    }

    private void selectGroup(String group) {
        userPickedGroup = true;   // 【2026-09-29 OTT 起播】Tab 点击/焦点即切换都算用户主动选择
        currentGroup = group;
        huyaSubGroup = null;
        if (isHuyaTab()) {
            // 进入虎牙页：胶囊条默认落在「正在播放的频道所属分组」上，下方网格随之展示
            huyaGroupCursor = initialHuyaGroupCursor();
        }
        syncTabSelection();
        syncGridFocusUp();
        applyFilter();
    }

    /**
     * 遥控焦点路径：
     * 上「分类 Tab」→ 子分组胶囊条（虎牙页）→ 频道网格；
     * 非虎牙页则是 Tab → 频道网格。
     */
    private void syncGridFocusUp() {
        if (gvChannel == null) return;
        for (TextView tab : tabViews) {
            if (tab.isSelected()) {
                View chip = currentSubGroupChip();
                boolean withStrip = isHuyaTab() && svSubGroup != null
                        && svSubGroup.getVisibility() == View.VISIBLE && chip != null;
                // 【2026-09-29 子分组焦点】进出胶囊条都直接对接具体胶囊，
                // 不让 sv_ott_subgroup 当中间站（它无可视焦点指示，会让人以为按键失灵）
                tab.setNextFocusDownId(withStrip ? chip.getId() : R.id.gv_ott_channel);
                gvChannel.setNextFocusUpId(withStrip ? chip.getId() : tab.getId());
                for (View c : subGroupChipViews) {
                    if (c != null) c.setNextFocusUpId(tab.getId());
                }
                return;
            }
        }
    }

    /** 【2026-09-29 子分组焦点】当前生效的子分组胶囊（huyaGroupCursor 指向的那个） */
    private View currentSubGroupChip() {
        if (huyaGroupCursor < 0 || huyaGroupCursor >= subGroupChipViews.size()) return null;
        return subGroupChipViews.get(huyaGroupCursor);
    }

    private void applyFilter() {
        // 先校正遥控焦点链：虎牙页要经过胶囊条，其它页直连网格
        syncGridFocusUp();

        if (isHuyaTab()) {
            applyHuyaFilter();
            return;
        }

        huyaSubGroup = null;
        setBreadcrumbText(null);
        setSubGroupStripVisible(false);
        shownChannels.clear();
        String all = getString(R.string.ott_group_all);
        for (Channel c : allChannels) {
            if (all.equals(currentGroup)
                    || currentGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                shownChannels.add(c);
            }
        }
        if (gvChannel.getAdapter() != channelAdapter) gvChannel.setAdapter(channelAdapter);
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();

        updateEmptyTip(shownChannels.isEmpty(), getString(R.string.ott_channel_empty));
    }

    /** 虎牙子分组胶囊条显隐：仅「虎牙频道」页展示；固定单行高度，分组再多也占 1 行 */
    private void setSubGroupStripVisible(boolean visible) {
        if (svSubGroup == null) return;
        if (visible) {
            ViewGroup.LayoutParams lp = svSubGroup.getLayoutParams();
            int h = subGroupStripHeight();
            if (lp != null && lp.height != h) {
                lp.height = h;
                svSubGroup.setLayoutParams(lp);
            }
        }
        int want = visible ? View.VISIBLE : View.GONE;
        if (svSubGroup.getVisibility() != want) svSubGroup.setVisibility(want);
    }

    /**
     * 虎牙频道：一屏两级。
     * 上方胶囊条（单行横向滚动）列出全部子分组（HTTP API 8 个 + SDK 固定 4 个），
     * 下方网格展示「当前子分组」的频道 —— 不再跳页，避免第二级留下大片空白。
     */
    private void applyHuyaFilter() {
        buildHuyaGroupNames();
        buildSubGroupChips();
        setSubGroupStripVisible(true);
        if (huyaGroupCursor < 0 || huyaGroupCursor >= huyaGroupNames.size()) {
            huyaGroupCursor = initialHuyaGroupCursor();
        }
        syncSubGroupChipState();

        // 【2026-09-29 子分组焦点】胶囊此时才建好，必须在这里重接焦点链，
        // 否则 applyFilter() 里那次 syncGridFocusUp() 看到的 llSubGroup 还是空的
        syncGridFocusUp();

        refreshHuyaChannelGrid();
    }

    /** 下方网格当前展示的虎牙子分组：已选定 > 胶囊焦点 > 正在播放的频道所属分组 > 第一个 */
    private String huyaGridGroup() {
        if (huyaGroupNames.isEmpty()) return null;
        if (!TextUtils.isEmpty(huyaSubGroup)) return huyaSubGroup;
        if (huyaGroupCursor >= 0 && huyaGroupCursor < huyaGroupNames.size()) {
            return huyaGroupNames.get(huyaGroupCursor);
        }
        if (playingChannel != null && !TextUtils.isEmpty(playingChannel.getGroup())
                && huyaGroupNames.contains(playingChannel.getGroup())) {
            return playingChannel.getGroup();
        }
        return huyaGroupNames.get(0);
    }

    private int initialHuyaGroupCursor() {
        if (huyaGroupNames.isEmpty()) return -1;
        if (playingChannel != null && !TextUtils.isEmpty(playingChannel.getGroup())) {
            int idx = huyaGroupNames.indexOf(playingChannel.getGroup());
            if (idx >= 0) return idx;
        }
        return 0;
    }

    /** 用「当前子分组」的频道刷新下方网格（虎牙页专用） */
    private void refreshHuyaChannelGrid() {
        String group = huyaGridGroup();
        shownChannels.clear();
        if (group != null) {
            for (Channel c : huyaChannels) {
                if (c != null && group.equals(c.getGroup())) {
                    shownChannels.add(c);
                }
            }
            // 【取消两路共享池】同步遍历第2路独立池（虎牙电影/电视剧/动漫/综艺/其他），
            // 两路不复用同一去重池，但首页网格照常显示全部分组。
            for (Channel c : huyaLine2Channels) {
                if (c != null && group.equals(c.getGroup())) {
                    shownChannels.add(c);
                }
            }
        }
        if (gvChannel.getAdapter() != channelAdapter) gvChannel.setAdapter(channelAdapter);
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
        // 换分组后回到列表顶部，避免沿用上个子分组的滚动偏移
        gvChannel.setSelection(0);

        if (huyaChannels.isEmpty() && huyaLine2Channels.isEmpty()) {
            // 数据未就绪：提示放面包屑位置，不要在网格里报「暂无频道」
            setBreadcrumbText(getString(huyaLoadDone
                    ? R.string.ott_huya_unavailable : R.string.ott_huya_loading));
            updateEmptyTip(false, null);
            return;
        }

        setBreadcrumbText(group == null
                ? null : getString(R.string.ott_group_huya) + " / " + group);
        updateEmptyTip(shownChannels.isEmpty(), getString(R.string.ott_channel_empty));
    }

    /**
     * 子分组清单与展示顺序（参照直播源 OTT 分组口径：主分类打头、衍生分组收尾）：
     * ① 影视/剧集主分组：怀旧老片 / 外国电影 / 影视热播 / 海外追剧 / 剧集追剧（正好占满第一行）
     * ② 虎牙 SDK 固定分组紧跟「剧集追剧」：虎牙电影 / 虎牙电视剧 / 虎牙动漫 / 虎牙综艺
     * ③ 动漫、综艺类补在末尾：海外动漫 / 动漫动画 / 综艺娱乐
     * 顺序只影响胶囊条呈现，其余逻辑（按名查找、按位置点选）不依赖下标。
     */
    private void buildHuyaGroupNames() {
        huyaGroupNames.clear();
        List<String> tail = new ArrayList<>();
        for (String n : HuyaTogetherWatchManager.HTTP_API_GROUP_NAMES) {
            if (TextUtils.isEmpty(n) || huyaGroupNames.contains(n) || tail.contains(n)) continue;
            if (isHuyaTailGroup(n)) tail.add(n);
            else huyaGroupNames.add(n);
        }
        for (String n : HuyaTogetherWatchGroupManager.GROUP_NAMES) {
            if (!TextUtils.isEmpty(n) && !huyaGroupNames.contains(n) && !tail.contains(n)) {
                huyaGroupNames.add(n);
            }
        }
        huyaGroupNames.addAll(tail);
    }

    /** 动漫 / 综艺类分组收在末尾，不插在影视剧主分组中间 */
    private static boolean isHuyaTailGroup(String name) {
        return name.contains("动漫") || name.contains("动画")
                || name.contains("综艺") || name.contains("娱乐");
    }

    private void setBreadcrumbText(String text) {
        if (tvBreadcrumb == null) return;
        if (TextUtils.isEmpty(text)) {
            tvBreadcrumb.setVisibility(View.GONE);
            return;
        }
        tvBreadcrumb.setText(text);
        tvBreadcrumb.setVisibility(View.VISIBLE);
    }

    private void updateEmptyTip(boolean empty, String text) {
        if (tvEmpty == null) return;
        if (empty) {
            tvEmpty.setText(text);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void showEmpty(String text) {
        updateEmptyTip(true, text);
    }

    // ============================ 播放 ============================

    private void playChannel(Channel channel) {
        if (channel == null || TextUtils.isEmpty(channel.getPlayUrl())) return;

        // 【2026-09-29 输号跳台】与直播模式对齐：任何一次起播都清掉待确认的数字输入缓冲，
        // 避免"输了号还没确认就被别的途径切台 → 1.5s 后突然跳台"
        numberInputBuffer.setLength(0);
        mainHandler.removeCallbacks(numberInputConfirmRunnable);

        playingChannel = channel;
        int liveIndex = indexOfEquivalent(allChannels, channel);
        if (liveIndex >= 0) {
            appConfig.setLastPlayIndex(liveIndex);
            // 【2026-09-29 OTT 起播】同时记一把 OTT 专用键（默认 -1），才能区分"没记录"与"记录是第 0 项"
            appConfig.setOttLastPlayIndex(liveIndex);
        }
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception ignored) {
        }

        setPreviewLoading(true);
        tvPreviewTip.setVisibility(View.GONE);
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName(), channel);

        tvNowChannel.setText(channel.getName());
        refreshNowPlayingProgram();
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();

        LogBridge.i(TAG, "起播频道：" + channel.getName());
        // 【2026-09-29 频道号 OSD】口径同直播模式：编号 = 全局列表 allChannels 里的下标 + 1
        int num = indexOfEquivalent(allChannels, channel) + 1;
        showChannelNum(num);
        LogBridge.i(TAG, "显示频道号：" + num + " 频道=" + channel.getName());
        // 【2026-09-29 方案 A 电信 IPTV 风格】刷新「节目单 + 元数据」面板
        refreshEpgPanel(channel);
        refreshMetadata();
        // 【2026-09-29 自动全屏】起播（含网格点击 / 开机自动起播 / 全屏内换台）后开始 5s 无操作计时
        armAutoFullscreen();
    }

    private void playRelativeChannel(int delta) {
        if (shownChannels.isEmpty()) return;
        int base = indexOfEquivalent(shownChannels, playingChannel);
        if (base < 0) base = 0;
        int next = (base + delta + shownChannels.size()) % shownChannels.size();
        playChannel(shownChannels.get(next));
    }

    /** 【2026-09-29 触摸点击修复】鼠标/手指点击频道卡片的入口（与 GridView.onItemClick 同语义）。
     *  卡片根布局 android:clickable="true" 但 ChannelAdapter 之前从未设监听，
     *  触摸点击被卡片自己消费、GridView.onItemClick 收不到 → 点频道无反应。这里显式接上。 */
    private void onChannelCardClick(int position) {
        if (position < 0 || position >= shownChannels.size()) return;
        if (gvChannel != null) gvChannel.setSelection(position);   // 让焦点框跟着走
        playChannel(shownChannels.get(position));
    }

    /** 【2026-09-29】Channel 没有 equals/hashCode，源刷新后对象会整体换新，
     *  不能用 List.indexOf 定位，必须按稳定 key 回查等价对象。 */
    private int indexOfEquivalent(List<Channel> list, Channel target) {
        if (list == null || target == null) return -1;
        int byRef = list.indexOf(target);
        if (byRef >= 0) return byRef;
        String tid = target.getChannelId();
        if (!TextUtils.isEmpty(tid)) {
            for (int i = 0; i < list.size(); i++) {
                Channel c = list.get(i);
                if (c != null && tid.equals(c.getChannelId())) return i;
            }
        }
        String tn = target.getName();
        if (TextUtils.isEmpty(tn)) return -1;
        String tg = target.getGroup();
        for (int i = 0; i < list.size(); i++) {          // 再按 name + group
            Channel c = list.get(i);
            if (c != null && tn.equals(c.getName()) && tg != null && tg.equals(c.getGroup())) return i;
        }
        for (int i = 0; i < list.size(); i++) {          // 最后只按 name
            Channel c = list.get(i);
            if (c != null && tn.equals(c.getName())) return i;
        }
        return -1;
    }

    private void refreshNowPlayingProgram() {
        Channel ch = playingChannel;
        if (ch == null) return;
        String program = null;
        try {
            EpgManager epg = EpgManager.getInstance(getApplicationContext());
            List<Channel.EpgItem> items = epg.getEpg(ch);
            if (items != null) {
                for (Channel.EpgItem it : items) {
                    if (it != null && it.isPlaying) {
                        program = it.title;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        String prefix = getString(R.string.ott_now_playing);
        if (TextUtils.isEmpty(program)) {
            String group = TextUtils.isEmpty(ch.getGroup()) ? "" : ch.getGroup();
            tvNowProgram.setText(group.isEmpty() ? prefix : prefix + " · " + group);
        } else {
            tvNowProgram.setText(prefix + " · " + program);
        }
    }

    /**
     * 【2026-09-29 方案 A 电信 IPTV 风格】左面板「节目单 3 行」刷新。
     * 取 EpgManager.getEpg(ch) 中正在播放的下标 + 接下来 2 条；找不到正在播放时退回列表前 3 条；
     * 列表为空（EPG 缺失）时显示单行「暂无节目信息」。
     * 每行：左侧时间戳（11sp 半透明）+ 右侧节目名（13sp 白字）；
     * 当前播放行用蓝色加粗 + 「▸ 播放中」后缀。
     */
    private void refreshEpgPanel(Channel ch) {
        if (llEpgList == null) return;
        llEpgList.removeAllViews();
        if (ch == null) {
            addEpgRow("暂无节目信息", "", false);
            return;
        }
        List<Channel.EpgItem> items = null;
        try {
            EpgManager epg = EpgManager.getInstance(getApplicationContext());
            items = epg.getEpg(ch);
        } catch (Exception ignored) {}
        if (items == null || items.isEmpty()) {
            addEpgRow("暂无节目信息", "", false);
            return;
        }

        // 【2026-09-29 节目单时间修正】口径对齐 InfoDisplayManager.calculateEpgData()：
        // ① 先过滤「今天」的节目（dayName = 今天 / 星期几 / yyyy-MM-dd），空则回退全列表
        // ② 按开始时间排序
        // ③ 用当前 HH:mm 与每项 [start, next.start) 比较，定位正在播放的节目
        // ④ 显示完整时间区间 "00:00 - 00:05"（此前只截了开始时间，用户误以为时间错乱）
        List<Channel.EpgItem> today = filterTodayEpg(items);
        if (today.isEmpty()) today = items;
        sortEpgByStartTime(today);

        String now = nowHHmm();
        int playingIdx = -1;
        for (int i = 0; i < today.size(); i++) {
            Channel.EpgItem it = today.get(i);
            if (it == null) continue;
            String start = epgStart(it);
            String end = (i + 1 < today.size()) ? epgStart(today.get(i + 1)) : "23:59";
            if (end.isEmpty()) end = "23:59";
            // 虎牙历史 EPG：时间段含「直播中」= 正在播放
            boolean huyaPlaying = it.time != null && it.time.contains("直播中");
            if (huyaPlaying || (!start.isEmpty() && timeInRanges(now, start, end))) {
                playingIdx = i;
                break;
            }
        }

        int startIdx = (playingIdx >= 0) ? playingIdx : 0;
        int showN = Math.min(3, today.size() - startIdx);
        if (showN <= 0) showN = Math.min(3, today.size());

        for (int i = 0; i < showN; i++) {
            Channel.EpgItem it = today.get(startIdx + i);
            if (it == null) continue;
            String title = it.title == null ? "" : it.title;
            String range = epgRange(it, (startIdx + i + 1 < today.size()) ? today.get(startIdx + i + 1) : null);
            boolean isPlaying = (startIdx + i) == playingIdx;
            String displayTitle = isPlaying ? (title.isEmpty() ? "直播中" : title + "  ▸ 播放中") : title;
            addEpgRow(displayTitle, range, isPlaying);
        }
    }

    /** 取 EPG 项的开始时间（"00:00 - 00:05" → "00:00"；"00:00 - 直播中" → "00:00"） */
    private static String epgStart(Channel.EpgItem it) {
        if (it == null || it.time == null) return "";
        String t = it.time.trim();
        int dash = t.indexOf('-');
        String s = (dash > 0) ? t.substring(0, dash).trim() : t;
        // 去掉"直播中"这类非时间尾巴
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp).trim();
        return s.matches("\\d{1,2}:\\d{2}") ? s : "";
    }

    /** 生成显示区间：有下一项用下一项开始时间兜底，否则用自身的结束段；最后一段兜 "23:59" */
    private static String epgRange(Channel.EpgItem it, Channel.EpgItem next) {
        String start = epgStart(it);
        if (start.isEmpty()) return "";
        String end = "";
        if (it.time != null && it.time.contains("-")) {
            String tail = it.time.substring(it.time.indexOf('-') + 1).trim();
            if (tail.matches("\\d{1,2}:\\d{2}")) end = tail;
        }
        if (end.isEmpty()) end = (next != null) ? epgStart(next) : "";
        if (end.isEmpty() || end.compareTo(start) <= 0) end = "23:59";
        return start + " - " + end;
    }

    /** 【口径同 InfoDisplayManager.filterTodayEpg】只保留今天的节目 */
    private List<Channel.EpgItem> filterTodayEpg(List<Channel.EpgItem> source) {
        List<Channel.EpgItem> res = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int weekNum = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekArr = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String todayWeek = weekArr[weekNum - 1];
        String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.getTime());
        for (Channel.EpgItem item : source) {
            if (item == null || item.dayName == null) continue;
            String day = item.dayName.trim();
            if ("今天".equals(day) || todayWeek.equals(day) || todayDate.equals(day)) {
                res.add(item);
            }
        }
        return res;
    }

    /** 按开始时间升序（"HH:mm" 零填充字典序即时间序） */
    private static void sortEpgByStartTime(List<Channel.EpgItem> list) {
        Collections.sort(list, (a, b) -> epgStart(a).compareTo(epgStart(b)));
    }

    /** 当前时刻 "HH:mm" */
    private String nowHHmm() {
        Calendar cal = Calendar.getInstance();
        return String.format(Locale.ROOT, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    /** 零填充 HH:mm 的字典序比较（同一天内等价于数值比较） */
    private static boolean timeInRanges(String now, String start, String end) {
        return !now.isEmpty() && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
    }

    private void addEpgRow(String title, String time, boolean isPlaying) {
        if (llEpgList == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvTime = new TextView(this);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                dp(110), LinearLayout.LayoutParams.WRAP_CONTENT);
        tvTime.setLayoutParams(timeLp);
        tvTime.setText(time);
        tvTime.setTextSize(11);
        tvTime.setTextColor(0x99FFFFFF);
        tvTime.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        row.addView(tvTime);

        TextView tvTitle = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(titleLp);
        tvTitle.setText(title);
        tvTitle.setTextSize(13);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (isPlaying) {
            tvTitle.setTextColor(0xFF40A9FF);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            tvTitle.setTextColor(0xFFFFFFFF);
        }
        row.addView(tvTitle);

        llEpgList.addView(row);
    }

    /**
     * 【2026-09-29 方案 A 电信 IPTV 风格】元数据小字：「1920×1080 · 2.5 Mbps · H.264 · AAC 48kHz」。
     * 数据来自 TVPlayerManager.getLiveInfo()（player.format 解码后由 OnLiveInfoUpdateListener 回调）。
     * 任一字段为"未知/0"则隐藏该项；全空时显示"解码信息未就绪"。
     */
    private void refreshMetadata() {
        if (tvMetadata == null) return;
        if (mPlayerManager == null) {
            tvMetadata.setText("解码信息未就绪");
            return;
        }
        try {
            TVPlayerManager.LiveInfo info = mPlayerManager.getLiveInfo();
            if (info == null) {
                tvMetadata.setText("解码信息未就绪");
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(info.resolution) && !"未知".equals(info.resolution)) sb.append(info.resolution);
            if (!TextUtils.isEmpty(info.bitrate) && !"0".equals(info.bitrate)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(info.bitrate);
            }
            if (!TextUtils.isEmpty(info.format) && !"未知".equals(info.format)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(info.format);
            }
            if (!TextUtils.isEmpty(info.audio) && !"未知".equals(info.audio)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(info.audio);
            }
            tvMetadata.setText(sb.length() == 0 ? "解码信息未就绪" : sb.toString());
        } catch (Exception e) {
            tvMetadata.setText("解码信息未就绪");
        }
    }

    private void setPreviewLoading(boolean loading) {
        if (loading && tvPreviewTip != null) {
            tvPreviewTip.setVisibility(View.GONE);
        }
    }

    // ============================ 虎牙频道 ============================

    /**
     * 虎牙频道加载（两线并行）：
     * 缓存 + HTTP 线路立即发出，第二路自研 2135 线路紧跟补发。
     * 起播安全不受影响：加载只是把频道放进列表，点击虎牙频道起播仍走 TVPlayerManager 的等待链。
     */
    private void ensureHuyaLoaded() {
        if (huyaLoadStarted) return;
        huyaLoadStarted = true;

        // 第一路：缓存 + HTTP API 线路，立即发出
        loadHuyaCacheAndHttp();

        // 第二路：自研 HTTP 取 2135 → 立即发车，与 HTTP 线路并行
        LogBridge.i(TAG, "虎牙：HTTP 线路已先发，自研线路立即补发");
        loadHuyaSdkLine();
    }

    /** 虎牙第一路：读缓存 + HTTP API 线路（不依赖 SDK，启动即发） */
    private void loadHuyaCacheAndHttp() {
        if (isFinishing() || isDestroyed()) return;

        // 先上缓存（HTTP 一起看缓存 + SDK 固定分组磁盘缓存），避免空窗
        try {
            mergeHuyaChannels(HuyaTogetherWatchManager.getInstance().loadCachedChannelsSync());
            List<HuyaTogetherWatchGroupManager.Group> cachedGroups =
                    HuyaTogetherWatchGroupManager.getInstance().loadCachedGroupsSync();
            if (cachedGroups != null) {
                for (HuyaTogetherWatchGroupManager.Group g : cachedGroups) {
                    if (g != null) mergeHuyaLine2Channels(g.channels);
                }
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "虎牙频道缓存读取失败: " + t.getMessage());
        }
        onHuyaDataChanged(false);

        HuyaTogetherWatchManager.getInstance().fetchTogetherWatchChannels(
                new HuyaTogetherWatchManager.OnChannelsFetchedListener() {
                    @Override
                    public void onPartial(List<Channel> channels) {
                        mainHandler.post(() -> {
                            mergeHuyaChannels(channels);
                            onHuyaDataChanged(false);
                        });
                    }

                    @Override
                    public void onSuccess(List<Channel> channels) {
                        mainHandler.post(() -> {
                            mergeHuyaChannels(channels);
                            onHuyaDataChanged(true);
                            LogBridge.i(TAG, "虎牙 HTTP API 线路频道就绪，共 " + huyaChannels.size() + " 个");
                        });
                    }

                    @Override
                    public void onFailed(String errorMsg) {
                        mainHandler.post(() -> {
                            LogBridge.w(TAG, "虎牙 HTTP API 线路加载失败: " + errorMsg);
                            onHuyaDataChanged(true);
                        });
                    }
                });
    }

    /** 虎牙第二路：自研 2135 固定分组线路（启动即发） */
    private void loadHuyaSdkLine() {
        if (isFinishing() || isDestroyed()) return;
        HuyaTogetherWatchGroupManager.getInstance().fetchAllGroups(
                new HuyaTogetherWatchGroupManager.OnGroupsListener() {
                    @Override
                    public void onSuccess(List<HuyaTogetherWatchGroupManager.Group> groups) {
                        mainHandler.post(() -> {
                            if (groups != null) {
                                for (HuyaTogetherWatchGroupManager.Group g : groups) {
                                    if (g != null) mergeHuyaLine2Channels(g.channels);
                                }
                            }
                            onHuyaDataChanged(true);
                            LogBridge.i(TAG, "虎牙自研线路频道就绪，共 " + huyaChannels.size() + " 个");
                        });
                    }

                    @Override
                    public void onError(String errMsg) {
                        mainHandler.post(() -> {
                            LogBridge.w(TAG, "虎牙自研线路加载失败: " + errMsg);
                            onHuyaDataChanged(true);
                        });
                    }
                });
    }

    /** 合并两条线路的虎牙频道，按房间号/频道号去重 */
    private void mergeHuyaChannels(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return;
        for (Channel c : channels) {
            if (c == null || TextUtils.isEmpty(c.getPlayUrl())) continue;
            String key = c.getHuyaRoomId() > 0
                    ? "r" + c.getHuyaRoomId()
                    : "c" + (c.getChannelId() != null ? c.getChannelId() : c.getName());
            if (huyaChannelKeys.add(key)) {
                huyaChannels.add(c);
            }
        }
    }

    /** 仅合并第2路（HuyaTogetherWatchGroupManager 固定分组）频道到第2路独立池，
     *  不并入共享池 huyaChannels，避免与第1路同名房间共用去重池。 */
    private void mergeHuyaLine2Channels(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return;
        for (Channel c : channels) {
            if (c == null || TextUtils.isEmpty(c.getPlayUrl())) continue;
            if (!isLine2FixedGroup(c.getGroup())) continue;
            String key = c.getHuyaRoomId() > 0
                    ? "r" + c.getHuyaRoomId()
                    : "c" + (c.getChannelId() != null ? c.getChannelId() : c.getName());
            if (huyaLine2Keys.add(key)) {
                huyaLine2Channels.add(c);
            }
        }
    }

    /** 判断 group 是否为第2路固定分组（虎牙电影/电视剧/动漫/综艺/其他） */
    private static boolean isLine2FixedGroup(String g) {
        if (g == null) return false;
        for (String n : HuyaTogetherWatchGroupManager.GROUP_NAMES) {
            if (n.equals(g)) return true;
        }
        return false;
    }

    private void onHuyaDataChanged(boolean loadDone) {
        if (loadDone) huyaLoadDone = true;
        if (isHuyaTab()) applyFilter();
    }

    // ============================ 全屏 ============================

    private void setFullscreen(boolean on) {
        fullscreen = on;
        // 【2026-09-29 退全屏焦点空窗】整段过渡期间屏蔽 Tab 的「焦点即切换」，
        // 直到下方延迟一帧恢复焦点、清掉标志为止（见 if (!on) 分支）
        suppressTabFocusSwitch = true;
        topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        rightPanel.setVisibility(on ? View.GONE : View.VISIBLE);
        tvNowChannel.setVisibility(on ? View.GONE : View.VISIBLE);
        tvNowProgram.setVisibility(on ? View.GONE : View.VISIBLE);
        // 【2026-09-29 方案 A 电信 IPTV 风格】左面板「节目单 + 元数据」全屏时一并隐藏
        if (llEpgPanel != null) llEpgPanel.setVisibility(on ? View.GONE : View.VISIBLE);

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) flPreview.getLayoutParams();
        if (on) {
            lp.height = 0;
            lp.weight = 1f;
        } else {
            lp.height = dp(PREVIEW_HEIGHT_DP);
            lp.weight = 0f;
        }
        flPreview.setLayoutParams(lp);

        // 【2026-09-29 OTT真全屏】全屏时清掉外层装饰边距，让 flPreview 真正铺满整屏。
        // 只改 padding/margin，不移动任何视图 → 不触发 Surface 重建（避免重演黑屏问题）。
        if (on) {
            // 【2026-09-29 播放器获焦】全屏时播放器铺满整屏，必须清焦点，否则焦点态蓝描边会贴屏幕四边形成一圈蓝框
            if (flPreview != null) {
                flPreview.clearFocus();        // 顺序不能反：先 clearFocus 再关 focusable
                flPreview.setFocusable(false);
            }
            // 【2026-09-29 播放器获焦】显式收起焦点环/提示，防止监听未回调时全屏边缘残留一圈蓝框
            if (previewFocusRing != null) previewFocusRing.setVisibility(View.GONE);
            if (previewFocusHint != null) previewFocusHint.setVisibility(View.GONE);
            if (!ottInsetsSaved && ottOuter != null) {
                savedPadLeft = ottOuter.getPaddingLeft();
                savedPadTop = ottOuter.getPaddingTop();
                savedPadRight = ottOuter.getPaddingRight();
                savedPadBottom = ottOuter.getPaddingBottom();
                if (ottContentRow != null) {
                    ViewGroup.LayoutParams clp = ottContentRow.getLayoutParams();
                    if (clp instanceof LinearLayout.LayoutParams) {
                        savedContentMarginTop = ((LinearLayout.LayoutParams) clp).topMargin;
                    }
                }
                ottInsetsSaved = true;
            }
            if (ottOuter != null) ottOuter.setPadding(0, 0, 0, 0);
            if (ottContentRow != null) {
                ViewGroup.LayoutParams clp = ottContentRow.getLayoutParams();
                if (clp instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) clp).topMargin = 0;
                    ottContentRow.setLayoutParams(clp);
                }
            }
        } else {
            // 【2026-09-29 播放器获焦】退出全屏恢复预览卡可获焦
            // 【2026-09-29 退全屏蓝框修复 V2】不在退全屏瞬间恢复 flPreview 可获焦！
            // 空窗期系统会把焦点派给「布局里第一个可获焦视图」，flPreview 排在最前，
            // 一旦命中 → 焦点环+蓝描边点亮，且时序竞争下第二跳收不回（用户实测仍现蓝框）。
            // 可获焦恢复挪到第二跳（焦点已定网格之后）执行，空窗期 flPreview 根本不可获焦。
            // 【2026-09-29 自动全屏提示】兜底：3s 计时器未到就退出全屏时，别让提示残留
            hideFullscreenHint();
            if (ottInsetsSaved && ottOuter != null) {
                ottOuter.setPadding(savedPadLeft, savedPadTop, savedPadRight, savedPadBottom);
                if (ottContentRow != null) {
                    ViewGroup.LayoutParams clp = ottContentRow.getLayoutParams();
                    if (clp instanceof LinearLayout.LayoutParams) {
                        ((LinearLayout.LayoutParams) clp).topMargin = savedContentMarginTop;
                        ottContentRow.setLayoutParams(clp);
                    }
                }
            }
        }
        // 【2026-09-29】日志打印"当前生效值"，不要打印 saved 快照 —— 否则验证时会把保存值误读成生效值
        int effMarginTop = -1;
        if (ottContentRow != null) {
            ViewGroup.LayoutParams clp2 = ottContentRow.getLayoutParams();
            if (clp2 instanceof LinearLayout.LayoutParams) {
                effMarginTop = ((LinearLayout.LayoutParams) clp2).topMargin;
            }
        }
        LogBridge.i(TAG, "全屏边距" + (on ? "已清零" : "已还原")
                + " 生效padding=" + (ottOuter == null ? -1 : ottOuter.getPaddingLeft()) + "/" + (ottOuter == null ? -1 : ottOuter.getPaddingTop())
                + " 生效contentMarginTop=" + effMarginTop
                + " 保存值=" + savedContentMarginTop);

        if (btnFullscreen instanceof TextView) {
            ((TextView) btnFullscreen).setText(on ? R.string.ott_exit_fullscreen : R.string.ott_fullscreen);
        }

        // 退出全屏后把焦点还给右侧网格，遥控可继续选台；否则焦点会落回顶栏 Tab
        if (!on) {
            boolean toStrip = isHuyaTab() && huyaSubGroup == null && svSubGroup != null
                    && svSubGroup.getVisibility() == View.VISIBLE
                    && !subGroupChipViews.isEmpty();
            // 【2026-09-29 退全屏蓝框修复】代际计数：快速连续进出全屏时，
            // 上一轮迟到的恢复任务不得干扰本轮（否则会互相清掉对方的抑制窗/抢焦点）
            final int epoch = ++fullscreenEpoch;
            Runnable restore = () -> {
                if (epoch != fullscreenEpoch) return;
                // 第一跳：优先网格/胶囊。注意 rightPanel 刚从 GONE 恢复 VISIBLE，
                // 同帧布局可能未完成 → requestFocus 可能失败，所以下面还要第二跳重试。
                // 空窗期焦点即使短暂落在 Tab 上也安全：suppress 保护着不会切分组，
                // flPreview 此时不可获焦（V2），不存在"借预览卡接住"的需要。
                if (toStrip) {
                    focusSubGroupChip();
                } else if (gvChannel != null) {
                    gvChannel.requestFocus();
                }
                // 第二跳（约 +2 帧）：网格此刻布局必已完成，焦点稳定后：
                // ① 再恢复 flPreview 可获焦（遥控左移到播放器的能力）；
                // ② 兜底重试把焦点收回网格 —— 否则 flPreview 的焦点环+蓝描边常驻，
                //    就是用户报的「多次退全屏后播放器两侧蓝框」。
                Runnable secondHop = () -> {
                    if (epoch != fullscreenEpoch || isFinishing() || isDestroyed()) return;
                    if (flPreview != null) flPreview.setFocusable(true);
                    if (toStrip) {
                        focusSubGroupChip();
                    } else if (gvChannel != null) {
                        gvChannel.requestFocus();
                    }
                    // 防御：焦点不在 flPreview 时强制收掉环/提示（幂等，防监听时序漏灭）
                    View cf2 = getCurrentFocus();
                    if (cf2 != flPreview) {
                        if (previewFocusRing != null) previewFocusRing.setVisibility(View.GONE);
                        if (previewFocusHint != null) previewFocusHint.setVisibility(View.GONE);
                    }
                    suppressTabFocusSwitch = false;
                };
                if (gvChannel != null) gvChannel.postDelayed(secondHop, 32);
                else suppressTabFocusSwitch = false;
            };
            if (gvChannel != null) gvChannel.post(restore); else restore.run();
        }
        LogBridge.i(TAG, "全屏状态：" + on);
    }

    /** 【2026-09-29 自动全屏】起播后重新计时：AUTO_FULLSCREEN_DELAY_MS 内无用户操作则自动全屏 */
    private void armAutoFullscreen() {
        if (fullscreen) return;                 // 已全屏，无需再自动全屏
        autoFullscreenArmed = true;
        mainHandler.removeCallbacks(autoFullscreenRunnable);
        mainHandler.postDelayed(autoFullscreenRunnable, AUTO_FULLSCREEN_DELAY_MS);
        LogBridge.i(TAG, "已开始 5s 自动全屏计时");
    }

    /** 【2026-09-29 自动全屏】用户有任何操作 → 取消本次自动全屏（幂等；只在真的取消时打日志，避免刷屏） */
    private void cancelAutoFullscreen() {
        if (!autoFullscreenArmed) return;
        autoFullscreenArmed = false;
        mainHandler.removeCallbacks(autoFullscreenRunnable);
        LogBridge.i(TAG, "检测到用户操作，取消自动全屏");
    }

    /** 【2026-09-29 自动全屏提示】显示 FULLSCREEN_HINT_MS 后自动收起；任何用户操作会提前收起 */
    private void showFullscreenHint() {
        if (fullscreenHint == null) return;
        fullscreenHint.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideFullscreenHintRunnable);
        mainHandler.postDelayed(hideFullscreenHintRunnable, FULLSCREEN_HINT_MS);
        LogBridge.i(TAG, "自动全屏提示已显示 " + FULLSCREEN_HINT_MS + "ms");
    }

    private void hideFullscreenHint() {
        if (fullscreenHint == null) return;
        mainHandler.removeCallbacks(hideFullscreenHintRunnable);
        if (fullscreenHint.getVisibility() != View.GONE) fullscreenHint.setVisibility(View.GONE);
    }

    /** 【2026-09-29 频道号 OSD】显示 CHANNEL_NUM_HIDE_MS 后自动隐藏（反复调用会续期） */
    private void showChannelNumText(String text) {
        if (tvOttChannelNum == null || TextUtils.isEmpty(text)) return;
        tvOttChannelNum.setText(text);
        tvOttChannelNum.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideChannelNumRunnable);
        mainHandler.postDelayed(hideChannelNumRunnable, CHANNEL_NUM_HIDE_MS);
    }

    /** 频道号 = 在「全部」列表 allChannels 里的下标 + 1（与直播模式口径一致） */
    private void showChannelNum(int num) {
        if (num <= 0) return;
        showChannelNumText(String.valueOf(num));
    }

    /** 【2026-09-29】遥控数字键输号：4 位缓冲，1.5s 无输入即跳台（口径同直播模式） */
    private void handleNumberKey(int keyCode) {
        if (appConfig == null || !appConfig.isNumberChannelEnable()) return;
        int num = keyCode - KeyEvent.KEYCODE_0;
        if (num < 0 || num > 9) return;
        mainHandler.removeCallbacks(numberInputConfirmRunnable);
        numberInputBuffer.append(num);
        if (numberInputBuffer.length() > 4) {
            numberInputBuffer.delete(0, numberInputBuffer.length() - 4);
        }
        showChannelNumText(numberInputBuffer.toString() + "-");
        LogBridge.i(TAG, "输号中：" + numberInputBuffer.toString());
        mainHandler.postDelayed(numberInputConfirmRunnable, NUMBER_INPUT_CONFIRM_MS);
    }

    private void confirmNumberInputJump() {
        if (numberInputBuffer.length() == 0) return;
        int n;
        try {
            n = Integer.parseInt(numberInputBuffer.toString());
        } catch (NumberFormatException e) {
            numberInputBuffer.setLength(0);
            return;
        }
        numberInputBuffer.setLength(0);
        if (n <= 0 || allChannels.isEmpty()) return;
        int idx = n - 1;
        if (idx >= allChannels.size()) idx = allChannels.size() - 1;   // 越界夹紧，不报错
        if (idx < 0) idx = 0;
        Channel target = allChannels.get(idx);
        LogBridge.i(TAG, "输号跳台：" + n + " → " + target.getName());
        playChannel(target);
        // 【必须】跳台后把顶部 Tab 切到该频道所属分组，否则网格仍停在原分组，观感像"没跳"
        syncTabsToPlayingChannel();
    }

    // ============================ 设置 ============================

    private void openSettings() {
        try {
            final SettingsDialog dialog = new SettingsDialog(this);
            dialog.setOnDismissListener(d -> mainHandler.postDelayed(this::handleSettingsClosed, 200));
            dialog.show();
        } catch (Exception e) {
            LogBridge.e(TAG, "打开设置失败", e);
        }
    }

    private void handleSettingsClosed() {
        if (appConfig.getSourceMode() == AppConfig.SOURCE_MODE_LIVE) {
            LogBridge.i(TAG, "源模式已切换为直播模式，重启进入直播");
            switchingToLive = true;
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_SKIP_SOURCE_ROUTE, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        }
    }

    // ============================ 生命周期 / 按键 ============================

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // 【2026-09-29 自动全屏】遥控按键 / 触摸都算"有动作" → 取消本次自动全屏
        cancelAutoFullscreen();
        // 【2026-09-29 自动全屏提示】用户一旦操作就立刻收起提示，不占用画面
        hideFullscreenHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        displayManager.reapplyFullScreen();
        if (mPlayerManager != null) {
            // 【2026-09-29 黑屏修复】重新进入时先重绑视图：attachPlayerView 内部会校验
            // 实际 Surface 是否可用，未就绪则启动重试轮询；onForeground 再兜底推进一次。
            mPlayerManager.attachPlayerView(playerView);
            mPlayerManager.onForeground();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 【2026-09-29 自动全屏】退后台时取消计时，避免后台到期、回前台被直接弹进全屏
        cancelAutoFullscreen();
        if (mPlayerManager != null) {
            mPlayerManager.onBackground();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 【2026-09-29 黑屏修复】补上此前缺失的 onStop：退后台（HOME / 返回键退出）时
        // 再次主动失效 Surface 状态，确保回前台必然走「重建 + 重试」分支，
        // 不会把画面播到已失效的 Surface 上（黑屏根因）。
        if (mPlayerManager != null) {
            mPlayerManager.onBackground();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 【2026-09-29 数字键输号跳台】全屏/非全屏都支持（口径同直播模式）
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            handleNumberKey(keyCode);
            return true;
        }
        // 【2026-09-29 播放器按上】fl_ott_preview 没有 nextFocusUpId，按「上」会走几何焦点搜索，
        // 落到顶栏「第一个」Tab（全部）而不是当前选中的 Tab，被 Tab 的「焦点即切换」误当用户操作
        // → 分组被重置（真机 2/2 复现）。这里定向把焦点交给当前分组对应的 Tab，
        // 命中后 :596 的 cat.equals(currentGroup) 守卫会拦下切换。
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && flPreview != null && flPreview.hasFocus()) {
            TextView sel = selectedTabView();
            if (sel != null && sel.requestFocus()) {
                LogBridge.i(TAG, "播放器按上 → 定向到当前分组 Tab：" + currentGroup);
            } else {
                // 极少数情况 requestFocus 失败：仍然吃掉本次按键，
                // 绝不让几何搜索把焦点交给别的 Tab
                LogBridge.w(TAG, "播放器按上 → 未找到当前分组 Tab，吞掉本次按键");
            }
            return true;
        }
        if (fullscreen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    playRelativeChannel(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    playRelativeChannel(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    setFullscreen(false);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        // 虎牙子分组已选定：返回键退回胶囊条（频道网格保持在同页），不退出应用
        if (isHuyaTab() && huyaSubGroup != null) {
            huyaSubGroup = null;
            refreshHuyaChannelGrid();
            syncSubGroupChipState();
            focusSubGroupChip();
            return;
        }
        // 虎牙页焦点在频道网格时，返回键把焦点还给胶囊条
        if (isHuyaTab() && svSubGroup != null && svSubGroup.getVisibility() == View.VISIBLE
                && !subGroupChipViews.isEmpty() && gvChannel != null && gvChannel.hasFocus()) {
            focusSubGroupChip();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackMs < DOUBLE_BACK_INTERVAL_MS) {
            finishAffinity();
            return;
        }
        lastBackMs = now;
        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (appCoreManager != null) {
            appCoreManager.release();
            appCoreManager = null;
        }
        if (mPlayerManager != null) {
            mPlayerManager.setOnPlayStateListener(null);
            mPlayerManager.setOnPlayerViewRecreatedListener(null);
            if (isFinishing() && !switchingToLive) {
                mPlayerManager.release();
            }
            mPlayerManager = null;
        }
        if (displayManager != null) {
            displayManager.release();
            displayManager = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ============================ 网格适配器 ============================

    private class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownChannels.size();
        }

        @Override
        public Object getItem(int position) {
            return shownChannels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(OttHomeActivity.this)
                        .inflate(R.layout.item_ott_channel, parent, false);
                holder = new ViewHolder();
                holder.name = convertView.findViewById(R.id.tv_ott_item_name);
                // 【2026-09-29 频道台标】台标 ImageView 引用
                holder.logo = convertView.findViewById(R.id.iv_ott_item_logo);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            Channel channel = shownChannels.get(position);
            holder.name.setText(channel.getName());
            // 【2026-09-29 频道台标】按内容形态选择缩放方式（卡片台标位是 127dp×32dp 的宽横条）：
            //   · 虎牙同看频道：台标来自直播封面，是 16:9 实拍截图 → centerCrop 满铺，
            //     否则 fitCenter 会把 16:9 缩到 32dp 高、两侧各留一半空白，观感极小；
            //   · M3U / 畅享频道：台标是透明底 logo 图（方形 210×210、长条 640×320 等都有）
            //     → 必须 fitCenter，用 centerCrop 会把 logo 上下裁掉只剩中间一条。
            holder.logo.setScaleType(
                    (channel.isTogetherWatch() || channel.isHuyaSdkTogetherWatch())
                            ? ImageView.ScaleType.CENTER_CROP
                            : ImageView.ScaleType.FIT_CENTER);
            // 加载频道台标（空/失败回退占位图，内部已做复用防错位）
            ChannelLogoLoader.getInstance(getApplicationContext())
                    .load(holder.logo, channel.getLogoUrl(), R.drawable.ic_channel_logo_placeholder);

            convertView.setSelected(channel == playingChannel);

            // 【2026-09-29 触摸点击修复】卡片根布局 android:clickable="true" 但从未设监听，
            // 触摸点击被卡片吞掉、GridView.onItemClick 收不到 → 鼠标/手指点频道无反应。
            // 行为与键盘 onItemClick 一致。
            // ⚠️ 必须每次 getView 都设置：GridView 复用 convertView，只在新建时挂会捕获过期 position。
            convertView.setOnClickListener(v -> onChannelCardClick(position));

            return convertView;
        }
    }

    private static class ViewHolder {
        TextView name;
        // 【2026-09-29 频道台标】原 group TextView 已移除（卡片高度限制），改为台标 ImageView
        ImageView logo;
    }
}