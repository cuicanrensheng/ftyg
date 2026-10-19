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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
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
@OptIn(markerClass = UnstableApi.class)
public class OttHomeActivity extends AppCompatActivity {

    private static final String TAG = "OttHome";
    public static final String EXTRA_SKIP_SOURCE_ROUTE = "skip_source_route";

    /** 【2026-10-18 启动图】直播模式热切换到 OTT 时透传：跳过 App 内启动覆盖层。
     *  热切换时共享播放器还在播，不会再回调 onPlayReady，展示启动图会一直挂到 9s 保底。 */
    public static final String EXTRA_WARM_START = "warm_start";

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
    /** 【2026-10-11 源7】EPG 列表外层滚动容器（源7 集数多时按上限限高、可上下滚动） */
    private ScrollView svEpgList;
    /** 【2026-10-11 源7】EPG 列表最大高度（dp）：约 6 行，超出则滚动 */
    private static final int EPG_LIST_MAX_HEIGHT_DP = 240;
    /** 【2026-10-11 源7】当前播放的集数下标（-1=未知/非源7），用于 EPG 面板高亮 */
    private int mCurrentAfhgrjEpisodeIdx = -1;
    /** 【2026-10-13 源5】当前播放的选集下标（-1=未知/非源5），用于 renyibo EPG 面板高亮 */
    private int mCurrentRenyiboEpisodeIdx = -1;
    private EpgManager.OnEpgDataReadyListener epgDataReadyListener;

    // ==================== 【2026-10-11】OTT 播放历史 + 源7 联播/续播 ====================

    /** 历史记录列（与集数列表并排，最多 10 条） */
    private LinearLayout llOttHistory;
    private LinearLayout llOttHistoryRows;
    private ScrollView svOttHistory;
    /** 历史记录条数上限（口径与 AppConfig.MAX_OTT_HISTORY 一致） */
    private static final int OTT_HISTORY_MAX = 10;
    /**
     * 【2026-10-19】频道网格列数（见 activity_ott_home.xml 的 {@code android:numColumns="6"}）。
     * 用于判定「网格左边缘」：选中位 % 列数 == 0 即最左一列 → 按左键进播放器预览窗。
     */
    private static final int OTT_GRID_COLUMNS = 6;
    /** 【2026-10-14 焦点记忆】集数列表中最后聚焦的行 ID（-1 = 无，回退到首行）。
     *  用户从集数列表移到历史记录再移回来时，焦点应回到此行而非第 1 集。 */
    private int lastFocusedEpgRowId = -1;
    /** 待续播的集数下标（-1 = 无）：起播时集数列表尚未就绪则记在这里，列表就绪后切过去 */
    private int pendingResumeEpisodeIdx = -1;
    /**
     * 【2026-10-11】播放状态监听器存字段：单例播放器上 MainActivity 与 OTT 共用同一个槽位，
     * 旧 Activity 的 onDestroy 会把新 Activity 刚注册的监听器清成 null（实测 net effect：
     * OTT 的 onPlayEnd 一次都没触发）。存字段后 onDestroy 只清「自己的那个」。
     */
    private TVPlayerManager.OnPlayStateListener ottPlayStateListener;
    /** 【2026-10-11 源7 联播】专用回调（独立槽位，MainActivity 不会清它） */
    private TVPlayerManager.OnAfhgrjEpisodeListener afhgrjEpisodeListener;
    /** 【2026-10-13 源5 联播】专用回调（独立槽位，与源7 各自独立，互不清理） */
    private TVPlayerManager.OnRenyiboEpisodeListener renyiboEpisodeListener;
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

    /**
     * 【2026-10-17 进度防丢】点播播放期间每 10 秒落一次盘。
     *
     * <p>原来只在 onPause / onStop / 切台三个点保存：进程被系统直接杀掉（最近任务清理、低内存）
     * 时 onPause/onStop 不一定执行，进度就丢了。这里补一个低成本的心跳（只在点播时真正写盘，
     * 直播被 {@code savePlaybackPosition()} 内部直接 return）。
     */
    private static final long PERIODIC_SAVE_MS = 10_000L;
    private final Runnable periodicSaveRunnable = new Runnable() {
        @Override
        public void run() {
            savePlaybackPosition();
            mainHandler.postDelayed(this, PERIODIC_SAVE_MS);
        }
    };

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

    // ==================== 【2026-10-17 界面重构 v2】一级板块 ====================
    /** 一级板块：直播 / 点播 / 我的。三者互相独立，二级分组各自生成。 */
    private static final int SECTION_LIVE = 0;
    private static final int SECTION_VOD = 1;
    private static final int SECTION_MINE = 2;
    private int topSection = SECTION_LIVE;
    /**
     * 【2026-10-19】用户是否手动切过一级板块（直播/点播/我的）。
     * true = 不再让「本源无直播频道 → 自动落点播」把用户从「直播」板块拽走；切源时复位。
     */
    private boolean userPickedBoard = false;
    /** 一级导航项（直播/点播/我的），程序化创建 */
    private final List<TextView> topNavViews = new ArrayList<>();
    private LinearLayout llTopNav;
    private HorizontalScrollView svCategoryBar;
    private View llMinePanel;
    private LinearLayout llMineHistory, llMineFavorite;

    // 【2026-10-17 v2】点播详情页覆盖层（设计稿 _ott_vod_detail_design.html）
    private VodDetailPanel detailPanel;
    /** 打开详情页时来源海报的位置：关闭后焦点回到这里 */
    private int detailSourcePosition = -1;
    /**
     * 【2026-10-18】「从详情页起播」的那部片。
     *
     * <p>非空表示：本次全屏是从详情页的「播放 / 选集」进去的。退出全屏时不该回首页网格，
     * 而应把详情页重新打开（并高亮正在播的那一集）—— 主流 TV 端都是这个返回语义。
     * 用户主动换台 / 换片时清空，避免被拽回无关影片的详情页。
     */
    private Channel detailReopenOnExit;
    /** 胶囊条当前指向的子分组下标（-1 = 尚未定位，按正在播放的频道回落） */
    private int huyaGroupCursor = -1;
    private boolean huyaLoadStarted = false;
    private boolean huyaLoadDone = false;
    private Channel playingChannel;
    private boolean fullscreen = false;
    // 【2026-10-14】标记是否从历史记录点击进入全屏，退出时焦点恢复到历史记录列
    private boolean fromHistoryFullscreen = false;
    // 【2026-10-14】记录点击历史记录时的行，退出全屏后恢复焦点
    private View lastHistoryFocusRow = null;
    // 【2026-10-14】标记是否从集数列表进入全屏，退出时焦点恢复到集数列
    private boolean fromEpgFullscreen = false;
    // 【2026-10-14】记录当前播放的集数下标，退出全屏后恢复焦点
    private int lastEpgFocusIndex = -1;
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

    /** 【2026-10-18 启动图】App 内启动覆盖层（冷启动展示，播放就绪/保底超时/用户操作后淡出）。
     *
     *  <p>背景：OTT 模式下 {@link MainActivity#onCreate} 在 setContentView 之前就跳转本页并
     *  return，MainActivity 的 splash_overlay 从未建立 → 直播模式有的「启动图全程覆盖」
     *  在 OTT 模式丢失（只剩系统预览窗那一段，首帧一出来就直接看到半成品首页）。
     *  现按同一口径在本页补上：系统预览窗（windowBackground）负责「点击图标 → 首帧」，
     *  本层负责「首帧 → 播放就绪」，淡出即画面。
     *  跳过条件：① Activity 重建（savedInstanceState != null）；
     *  ② 调试直达（debug_* extras，自动化流程不该被拖住）；
     *  ③ 直播模式热切换（EXTRA_WARM_START，播放器还在播，不会再来就绪信号）。 */
    private View splashOverlay;
    private boolean splashDismissed = false;
    /** 【2026-10-18 启动图】保底自动淡出时长 ms（就绪信号优先；停留「我的」板块/播放异常时兜底放行） */
    private static final long SPLASH_FALLBACK_MS = 9000L;
    private static final long SPLASH_FADE_MS = 350L;
    private final Runnable splashFallbackRunnable = () -> dismissSplashOverlay("保底超时");

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

        // 【2026-10-18 启动图】冷启动展示 App 内启动覆盖层（详见字段注释；直播模式 MainActivity 同款）
        setupSplashOverlay(savedInstanceState);

        bindViews();
        setupActions();
        initPlayer();
        initAppCoreManager();
        initVodControlBar();
        installDrawerFocusWatcher();

        // 【2026-10-17 调试入口】am start ... --es debug_vod_id <vodId> 时直接弹该片详情，
        //   避免在混合列表里靠遥控器找源7 条目（正式使用不会带该参数，无副作用）。
        final String debugVodId = getIntent() == null ? null
                : getIntent().getStringExtra("debug_vod_id");
        // 【2026-10-17 调试入口2】--es debug_play_name <片名关键字>：直接起播匹配到的频道（测控制栏用）
        final String debugPlayName = getIntent() == null ? null
                : getIntent().getStringExtra("debug_play_name");
        // 【2026-10-17 调试入口3】--es debug_panel quality|line|speed|eps：起播后直接打开对应抽屉，
        //   避免依赖 5s 自动收起造成的时间抖动（正式使用不会带该参数）。
        final String debugPanel = getIntent() == null ? null
                : getIntent().getStringExtra("debug_panel");
        if (!TextUtils.isEmpty(debugPlayName)) {
            mainHandler.postDelayed(() -> {
                try {
                    LogBridge.i(TAG, "【调试起播】候选 shownChannels=" + shownChannels.size()
                            + " allChannels=" + allChannels.size());
                    java.util.List<Channel> pool = new java.util.ArrayList<>(allChannels);
                    pool.addAll(shownChannels);
                    Channel hit = null;
                    for (Channel c : pool) {
                        if (c.getName() != null && c.getName().contains(debugPlayName)) {
                            hit = c;
                            break;
                        }
                    }
                    // 【2026-10-17 调试入口扩展】点播分组（源5）是「进影视页才按需加载」的，
                    // 不在 allChannels 里 → 调试时再查一遍点播缓存片段，否则测不到点播形态面板。
                    if (hit == null) {
                        try {
                            String frag = com.tv.live.renyibo.RenYiBoVodSourceLoader
                                    .loadVodFragmentCached(OttHomeActivity.this);
                            if (!TextUtils.isEmpty(frag)) {
                                java.util.List<Channel> vods =
                                        com.tv.live.PlaylistParser.parseContent(frag);
                                if (vods != null) {
                                    LogBridge.i(TAG, "【调试起播】点播缓存候选 " + vods.size() + " 条");
                                    for (Channel c : vods) {
                                        if (c.getName() != null && c.getName().contains(debugPlayName)) {
                                            hit = c;
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            LogBridge.w(TAG, "【调试起播】点播缓存解析失败: " + t.getMessage());
                        }
                    }
                    if (hit != null) {
                        LogBridge.i(TAG, "【调试起播】命中 " + hit.getName()
                                + " → 直接播放（源" + sourceIndexOf(hit) + "）");
                        playChannel(hit);
                        enterFullscreenImmediately();
                        return;
                    }
                    LogBridge.w(TAG, "【调试起播】未命中 " + debugPlayName);
                } catch (Throwable t) {
                    LogBridge.w(TAG, "【调试起播】异常: " + t.getMessage());
                }
            }, 4500);
            if (!TextUtils.isEmpty(debugPanel)) {
                mainHandler.postDelayed(() -> {
                    try {
                        showSource7Controller();
                        // 等控制栏完成一次布局再开抽屉（否则位置/焦点都会错位）
                        mainHandler.postDelayed(() -> {
                            LogBridge.i(TAG, "【调试抽屉】打开 " + debugPanel);
                            if ("quality".equals(debugPanel)) showQualityPanel();
                            else if ("line".equals(debugPanel)) showLinePanel();
                            else if ("speed".equals(debugPanel)) showSpeedPanel();
                            else if ("eps".equals(debugPanel)) showEpisodePanel();
                            else if ("chlist".equals(debugPanel)) showChannelListPanel();
                            else if ("epg".equals(debugPanel)) showEpgPanel();
                            else if ("info".equals(debugPanel)) showChannelInfo(playingChannel);
                        }, 600);
                    } catch (Throwable t) {
                        LogBridge.w(TAG, "【调试抽屉】异常: " + t);
                    }
                }, 9000);
            }
        }
        if (!TextUtils.isEmpty(debugVodId)) {
            LogBridge.i(TAG, "【OTT·详情】调试直达 vodId=" + debugVodId);
            mainHandler.postDelayed(() -> {
                try {
                    VodDetailDialog dlg = new VodDetailDialog(OttHomeActivity.this, debugVodId,
                            "调试影片 " + debugVodId, new VodDetailDialog.Callback() {
                                @Override public void onSwitchLine(String vId, int sid) {
                                    LogBridge.i(TAG, "【OTT·详情·调试】选择线路 sid=" + sid);
                                }
                                @Override public void onPlay() {
                                    LogBridge.i(TAG, "【OTT·详情·调试】点击播放");
                                }
                            });
                    dlg.show();
                } catch (Throwable t) {
                    LogBridge.w(TAG, "【OTT·详情·调试】弹窗失败: " + t.getMessage());
                }
            }, 1500);
        }

        AppExecutors.io(() -> {
            if (appCoreManager != null) appCoreManager.loadLiveAndEpg();
        });

        ensureHuyaLoaded();

        // 【2026-10-11 源7】注册 EPG 数据就绪监听：异步抓取集数完成后自动刷新面板
        epgDataReadyListener = () -> mainHandler.post(() -> {
            if (playingChannel != null) {
                refreshEpgPanel(playingChannel);
                // 【2026-10-19】集数比起播晚到时，起播那一刻的「正在播放」取不到集数、
                //   会回落成组名（源8 用户报障）；这里同步补刷一次。
                refreshNowPlayingProgram();
            }
        });
        EpgManager.getInstance(getApplicationContext())
                .registerEpgDataReadyListener(epgDataReadyListener);

        // 【2026-10-11】先渲染一次历史记录（此时还没起播，展示上次留下的 10 条）
        renderHistoryPanel();

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
        // 【2026-10-17 界面重构 v2】一级导航 + 二级分组条 + 「我的」板块
        svCategoryBar = findViewById(R.id.sv_ott_category);
        llTopNav = findViewById(R.id.ll_ott_top_nav);
        llMinePanel = findViewById(R.id.ll_ott_mine);
        llMineHistory = findViewById(R.id.ll_ott_mine_history);
        llMineFavorite = findViewById(R.id.ll_ott_mine_favorite);
        // 【2026-10-17 v2】横向滚动条两端渐隐：分组放不下时，用户能看出「右边还有」
        //   （零布局改动，只在滚动视图上开 fadingEdge）。
        if (svSubGroup != null) {
            svSubGroup.setHorizontalFadingEdgeEnabled(true);
            svSubGroup.setFadingEdgeLength(dp(40));
        }
        if (svCategoryBar != null) {
            svCategoryBar.setHorizontalFadingEdgeEnabled(true);
            svCategoryBar.setFadingEdgeLength(dp(40));
        }
        buildTopNav();
        gvChannel = findViewById(R.id.gv_ott_channel);
        svSubGroup = findViewById(R.id.sv_ott_subgroup);
        llSubGroup = findViewById(R.id.ll_ott_subgroup);
        tvNowChannel = findViewById(R.id.tv_ott_now_channel);
        tvNowProgram = findViewById(R.id.tv_ott_now_program);
        llEpgPanel = findViewById(R.id.ll_ott_epg_panel);
        llEpgList = findViewById(R.id.ll_ott_epg_list);
        svEpgList = findViewById(R.id.sv_ott_epg_list);
        // 【2026-10-11】播放历史（与集数列表并排）
        llOttHistory = findViewById(R.id.ll_ott_history);
        llOttHistoryRows = findViewById(R.id.ll_ott_history_rows);
        svOttHistory = findViewById(R.id.sv_ott_history);
        tvMetadata = findViewById(R.id.tv_ott_metadata);
        // 【2026-10-14】隐藏底部视频元数据信息栏
        tvMetadata.setVisibility(android.view.View.GONE);
        tvPreviewTip = findViewById(R.id.tv_ott_preview_tip);
        tvBreadcrumb = findViewById(R.id.tv_ott_breadcrumb);
        tvEmpty = findViewById(R.id.tv_ott_empty);
        playerView = findViewById(R.id.ott_player_view);
        topAdMask = findViewById(R.id.v_ott_top_ad_mask);
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
            if (fullscreen) {
                // 【2026-10-17 用户口径】直播**取消「单击屏幕切换播放/暂停」**：
                //   直播流暂停没有意义，误触后画面停住很容易被当成卡死。
                //   直播下单击只做两件事：控制栏没显示→唤起；已显示→收起（不改播放状态）。
                //   点播（源7/源5）保持原行为：单击 = 播放/暂停。
                final boolean vodPlayback = isSource7Playing() || isRenyiboVodChannel(playingChannel);
                if (ottControllerShowing) {
                    if (vodPlayback) {
                        mPlayerManager.togglePlayWhenReady();
                        LogBridge.i(TAG, "【OTT·点播】全屏单击 → 切换播放/暂停");
                    } else {
                        hideSource7Controller();
                        LogBridge.i(TAG, "【OTT·直播】全屏单击 → 收起控制栏（不改播放状态）");
                    }
                } else {
                    // 【2026-10-11 源7 专属】全屏下「单击触摸」→ 唤起播放器控制栏。
                    //   非源7 时 showSource7Controller() 内部直接 return → 什么都不做，
                    //   源1~源6 全屏行为与改前完全一致。
                    showSource7Controller();
                }
                return;
            }
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
                Channel picked = shownChannels.get(position);
                // 【2026-10-17 v2】点播先进详情页（看简介/选集/确认再播）；直播保持「点一下就播」
                if (isVodChannel(picked)) {
                    openVodDetail(picked, position);
                    return;
                }
                playChannel(picked);
                // 【2026-10-13】网格点击频道立即全屏，取消 5s 自动全屏等待
                enterFullscreenImmediately();
            }
        });
        // 【2026-10-17 OTT 详情页】长按 OK：弹出影片详情 + 播放线路切换（仅源7 影视有详情数据）
        gvChannel.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownChannels.size()) return false;
            Channel ch = shownChannels.get(position);
            showVodDetail(ch);
            return true;
        });

        // 【2026-10-19 用户口径】网格**最左一列**（选中位 % 列数 == 0，即网格左边缘）按「左」键
        //   → 焦点直接进入左侧「播放器预览窗」，不再转到 EPG 集数行；
        //   其余列按「左」保持**行内左移**（返回 false，交给原来的焦点链）。
        //   收口在 GridView 自身：本网格由 GridView 持有焦点（见上方 setDrawSelectorOnTop 处的口径），
        //   所以 ViewGroup.dispatchKeyEvent 会走 "自己获焦" 分支 → OnKeyListener 先于
        //   AbsListView.onKeyDown（arrowScroll）拿到事件，因此能拦住。
        gvChannel.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            int pos = gridFocusedPosition();
            if (pos < 0 || (pos % OTT_GRID_COLUMNS) != 0) return false;   // 非最左列：行内左移
            if (flPreview == null) return false;
            // 预览卡在「退全屏回详情」等分支会被 setFocusable(false) 遗留，这里先补回来，
            // 否则 requestFocus() 直接失败 → 焦点退回分类条/集数行。
            if (!flPreview.isFocusable()) flPreview.setFocusable(true);
            boolean ok = flPreview.requestFocus();
            LogBridge.i(TAG, "【OTT·网格】最左列按左键 → 预览窗（pos=" + pos + "，抢焦点=" + ok + "）");
            return ok;   // 抢不到就放行，交给原来的焦点链（集数行 / 预览卡）
        });

        // 子分组胶囊条：单行横向滚动，焦点/点按哪个胶囊，下方网格就显示该分组的频道
        if (svSubGroup != null) {
            svSubGroup.setVisibility(View.GONE);
        }

        tvPreviewTip.setText(R.string.ott_preview_idle);
    }

    /**
     * 【2026-10-19】网格当前焦点所在卡片的下标：子卡片获焦则取它的 position
     * （{@code getPositionForView}），否则取 GridView 自身的选中位；取不到返回 -1。
     * 用于「网格最左一列」判定（{@code pos % OTT_GRID_COLUMNS == 0}）。
     */
    private int gridFocusedPosition() {
        if (gvChannel == null) return -1;
        View f = getCurrentFocus();
        if (f != null && f != gvChannel && f.getParent() == gvChannel) {
            int p = gvChannel.getPositionForView(f);
            if (p != AdapterView.INVALID_POSITION) return p;
        }
        return gvChannel.getSelectedItemPosition();
    }

    /**
     * 子分组胶囊条总高度（仅一行）：供 HorizontalScrollView 固定高度，避免分组变多时多占行。
     *
     * 【2026-10-17 v2 修复】31dp → 38dp：胶囊是 16sp 文字 + 上下各 7dp padding，
     * 实际高度约 34–36dp，被 31dp 的条高裁掉上下各一截 → 用户看到「字体只出现一半」。
     */
    private int subGroupStripHeight() {
        return dp(38);
    }

    /**
     * 重建子分组胶囊条。用动态 TextView 而非 GridView：
     * 胶囊按内容宽度自适应、横排一行、超出可横向滚动，恒占 1 行高度。
     */
    private void buildSubGroupChips() {
        if (llSubGroup == null) return;
        // 【2026-10-17】重建前记住焦点所在胶囊：removeAllViews 会连焦点所有者一起删掉，
        // 焦点就会掉到控制栏「‹ 上一频道」。重建后按文案找回同一个胶囊。
        final String keepChip = focusedChildText(llSubGroup);
        llSubGroup.removeAllViews();
        subGroupChipViews.clear();

        for (int i = 0; i < huyaGroupNames.size(); i++) {
            final int position = i;
            String name = huyaGroupNames.get(i);

            TextView chip = new TextView(this);
            chip.setId(View.generateViewId());
            chip.setText(name);
            // 【2026-10-17 v2 排版收口】16sp/18dp 内距 → 15sp/14dp 内距：虎牙分组常有 6~10 个，
            //   原尺寸一行放不下，末尾分组被挤出可视区。压缩后一屏能多放 1~2 个。
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            chip.setTextColor(Color.WHITE);
            chip.setBackgroundResource(R.drawable.ott_tab_item_bg);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            chip.setNextFocusDownId(R.id.gv_ott_channel);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
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
                // 【2026-10-17 v2】焦点移到右侧看不见的胶囊时，视野要跟着滚过去，
                //   否则用户按右键"焦点消失"，只能盲按（分组多于 6 个时必现）。
                scrollChildIntoView(svSubGroup, v);
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
        restoreChildFocusByText(llSubGroup, keepChip);
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

    /**
     * 【2026-10-17 v2】把横向滚动条里的某个子项滚进可视区（两侧留 28dp 余量）。
     *
     * <p>适用「子分组胶囊」「二级分组 Tab」这类一排放不下、需要横向滚动的条。
     * 不做这件事的后果：分组多于可见宽度时，按右键焦点跑到屏幕外，用户看到的是
     * 「焦点突然消失、再按没反应」，实际焦点还在但看不见。
     */
    private void scrollChildIntoView(final HorizontalScrollView sv, final View child) {
        if (sv == null || child == null) return;
        sv.post(() -> {
            try {
                int margin = dp(28);
                int left = child.getLeft();
                int right = child.getRight();
                int viewLeft = sv.getScrollX();
                int viewRight = viewLeft + sv.getWidth();
                int target = -1;
                if (left - margin < viewLeft) {
                    target = Math.max(0, left - margin);
                } else if (right + margin > viewRight) {
                    target = right + margin - sv.getWidth();
                }
                if (target >= 0) sv.smoothScrollTo(target, 0);
            } catch (Throwable ignored) {
            }
        });
    }

    private void setupActions() {
        if (btnMenu != null) {
            // 【2026-10-18 用户口径】「菜单」→ OTT 两级设置弹窗（6 项），不再打开直播旧设置面板
            btnMenu.setOnClickListener(v -> showMenuSettingsPanel());
        }
        if (btnFullscreen != null) {
            // 【2026-10-18 用户需求】原「全屏」按钮 → 直播/OTT 模式一键切换（切回直播模式）
            btnFullscreen.setOnClickListener(v -> switchToLiveMode());
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
        ottPlayStateListener = new TVPlayerManager.OnPlayStateListener() {
            @Override public void onIdle() { setPreviewLoading(false); }
            @Override public void onBuffering() { setPreviewLoading(true); }
            @Override public void onPlayReady() {
                setPreviewLoading(false);
                // 【2026-10-18 启动图】首次播放就绪 → 淡出启动覆盖层（dismiss 幂等）
                mainHandler.post(() -> dismissSplashOverlay("播放就绪"));
            }
            @Override public void onPlayEnd() { setPreviewLoading(false); }
            @Override public void onPlayError(String msg) { setPreviewLoading(false); }
        };
        mPlayerManager.setOnPlayStateListener(ottPlayStateListener);

        // 【2026-10-11 源7 联播】专用回调（独立槽位，MainActivity 不会清它）：
        //   自动联播换集 → 同步 EPG 高亮 + 播放历史；全部播完 → 退出全屏并收起控制栏。
        //   联播「播下一集 / 末集停播」的判定在 TVPlayerManager（STATE_ENDED 处），
        //   这里只做界面同步，避免依赖会被清空的共享监听器槽位。
        afhgrjEpisodeListener = new TVPlayerManager.OnAfhgrjEpisodeListener() {
            @Override
            public void onAfhgrjEpisodeChanged(int ordinal) {
                mCurrentAfhgrjEpisodeIdx = Math.max(0, ordinal - 1);
                Channel ch = playingChannel;
                if (ch == null) return;
                appConfig.addOttHistory(ch.getName(),
                        com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch), ordinal);
                renderHistoryPanel();
                refreshEpgPanel(ch);
                // 频道名下的「正在播放 · 第N集」也要跟着联播更新（否则一直停在起播那一集）
                refreshNowPlayingProgram();
            }

            @Override
            public void onAfhgrjAllEpisodesEnded() {
                LogBridge.i(TAG, "【OTT·源7】全部集数播完 → 退出全屏并停止");
                if (fullscreen) setFullscreen(false);
                hideSource7Controller();
            }
        };
        mPlayerManager.setOnAfhgrjEpisodeListener(afhgrjEpisodeListener);

        // 【2026-10-13 源5 联播】专用回调（独立槽位）：自动联播换集 → 同步 renyibo 选集面板
        //   高亮 + 「正在播放·第N集」 + 播放历史；全部播完 → 退出全屏并收起控制栏（与源7 同行为）。
        renyiboEpisodeListener = new TVPlayerManager.OnRenyiboEpisodeListener() {
            @Override
            public void onRenyiboEpisodeChanged(int ordinal) {
                mCurrentRenyiboEpisodeIdx = Math.max(0, ordinal - 1);
                Channel ch = playingChannel;
                if (ch == null) return;
                String vodId = com.tv.live.renyibo.RenYiBoVodSourceLoader
                        .vodIdOfChannelId(ch.getChannelId());
                if (!TextUtils.isEmpty(vodId)) {
                    appConfig.addOttHistory(ch.getName(), vodId, ordinal);
                }
                renderHistoryPanel();
                refreshEpgPanel(ch);
                // 「正在播放 · 第N集」也要跟着联播更新（否则一直停在起播那一集）
                refreshNowPlayingProgram();
            }

            @Override
            public void onRenyiboAllEpisodesEnded() {
                LogBridge.i(TAG, "【OTT·源5】全部集数播完 → 退出全屏并停止");
                if (fullscreen) setFullscreen(false);
                hideSource7Controller();
            }
        };
        mPlayerManager.setOnRenyiboEpisodeListener(renyiboEpisodeListener);

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
                // 【2026-10-11 源7】「加载超时」不等于「频道列表为空」。
                // 切源后 AppCoreManager 会先清空 channelSourceList（故 hasData=false），但界面上仍是
                // 上一个源的频道 —— applyChannels() 对空列表的策略就是「保留当前分组与频道不动」。
                // 此处若见到 hasData=false 就直接 showEmpty，会让一次**正常的慢加载**（源7 首次抓取
                // 为首页 1 页 + 4 个分类各 2 页 = 共 9 页、实测约 19s > LOAD_TIMEOUT 15s；
                // 【2026-10-10】一度收敛为每分类 1 页共 5 页、约 11s，【2026-10-11】按需求恢复为
                // 每分类 2 页（9 页）；站点降级时仍可能超过 15s）在 15s 时闪出「频道列表为空」的
                // 假失败，随后数据到达又恢复 —— 正是用户报的「加载分组和频道失败，几分钟后才成功」。
                // 修正：只有界面**真的一个频道都没有**时才提示空列表。
                mainHandler.post(() -> {
                    if (allChannels.isEmpty()) showEmpty(getString(R.string.ott_channel_empty));
                });
            }
        });
    }

    // ============================ 频道 / 分类 ============================

    // ==================== 【2026-10-17 界面重构 v2】一级导航 直播 / 点播 / 我的 ====================

    /**
     * 构建一级导航（直播 / 点播 / 我的）。
     *
     * <p>设计口径（见 `_ott_ui_v2_plan.html`）：一级是**内容形态**，固定三项，不随数据源变化；
     * 原来的「频道分组 / 点播分类」下移为二级分组条。
     */
    private void buildTopNav() {
        if (llTopNav == null) return;
        llTopNav.removeAllViews();
        topNavViews.clear();
        topNavViews.add(addTopNavItem(getString(R.string.ott_top_live), SECTION_LIVE));
        topNavViews.add(addTopNavItem(getString(R.string.ott_top_vod), SECTION_VOD));
        topNavViews.add(addTopNavItem(getString(R.string.ott_top_mine), SECTION_MINE));
        // 【2026-10-17 v2 焦点链】一级导航内部左右移动必须显式声明：
        //   不声明时 FocusFinder 会按几何位置直接跳到下方网格（实测「直播」按右键落到 CCTV1 卡片）。
        for (int i = 0; i < topNavViews.size(); i++) {
            TextView tv = topNavViews.get(i);
            if (i > 0) tv.setNextFocusLeftId(topNavViews.get(i - 1).getId());
            if (i < topNavViews.size() - 1) tv.setNextFocusRightId(topNavViews.get(i + 1).getId());
        }
        syncTopNavStyle();
        LogBridge.i(TAG, "【OTT·一级导航】已构建 3 项");
    }

    private TextView addTopNavItem(String label, int section) {
        TextView tv = new TextView(this);
        tv.setId(View.generateViewId());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setPadding(dp(18), 0, dp(18), 0);
        tv.setFocusable(true);
        // 【2026-10-17 v2】focusableInTouchMode 必须为 false：
        //   为 true 时（触屏/鼠标）第一次点击只会「给焦点」不触发 onClick，用户要点两下才切板块
        //   （实测：点「点播」只高亮不切换）。false 时遥控照样可聚焦，点击直接生效。
        tv.setFocusableInTouchMode(false);
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = dp(4);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            // 【2026-10-19】用户手动切过板块 → 不再让"无直播频道自动落点播"把焦点拽走
            userPickedBoard = true;
            switchTopSection(section);
        });
        tv.setOnFocusChangeListener((v, has) -> {
            v.setScaleX(has ? 1.08f : 1f);
            v.setScaleY(has ? 1.08f : 1f);
            applyTopNavItemStyle((TextView) v, section);
        });
        applyTopNavItemStyle(tv, section);
        llTopNav.addView(tv, lp);
        return tv;
    }

    /** 一级导航项样式：选中=蓝底白字；焦点=金底黑字（两种视觉通道，10-foot 规范） */
    private void applyTopNavItemStyle(TextView tv, int section) {
        if (tv == null) return;
        boolean selected = (section == topSection);
        boolean focused = tv.hasFocus();
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(18));
        if (focused) {
            g.setColor(0xFFFFD166);
            g.setStroke(dp(2), 0xFFFFFFFF);
            tv.setTextColor(0xFF111111);
        } else if (selected) {
            g.setColor(0x332F81F7);
            g.setStroke(dp(1), 0x883AA0FF);
            tv.setTextColor(0xFFFFFFFF);
        } else {
            g.setColor(0x00000000);
            tv.setTextColor(0xFF8E9AB3);
        }
        tv.setBackground(g);
        tv.setTypeface(null, (selected || focused)
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void syncTopNavStyle() {
        for (int i = 0; i < topNavViews.size(); i++) {
            applyTopNavItemStyle(topNavViews.get(i), i);
        }
        bindTopNavFocusDown();
    }

    /** 焦点链：一级导航按「下」→ 二级分组当前项（我的页则是列表第一行） */
    private void bindTopNavFocusDown() {
        if (topNavViews.isEmpty()) return;
        int idx = Math.min(Math.max(topSection, 0), topNavViews.size() - 1);
        TextView cur = topNavViews.get(idx);
        if (topSection == SECTION_MINE) {
            View first = firstFocusableChild(llMineHistory);
            if (first == null) first = firstFocusableChild(llMineFavorite);
            cur.setNextFocusDownId(first != null && first.getId() != View.NO_ID
                    ? first.getId() : View.NO_ID);
            return;
        }
        TextView selTab = selectedTabView();
        cur.setNextFocusDownId(selTab != null ? selTab.getId()
                : (gvChannel != null ? gvChannel.getId() : View.NO_ID));
    }

    private String sectionName(int s) {
        switch (s) {
            case SECTION_VOD: return "点播";
            case SECTION_MINE: return "我的";
            default: return "直播";
        }
    }

    /**
     * 切换一级板块。
     *
     * <p>切板块时二级分组重置为「全部」并强制重建（清 lastGroupNames/tabViews），
     * 因为两个板块的分组集合完全不同，沿用旧分组会落到「暂无频道」。
     */
    private void switchTopSection(int section) {
        if (section == topSection) {
            LogBridge.i(TAG, "【OTT·一级导航】已在「" + sectionName(section) + "」，焦点送内容区");
            focusIntoSection();
            return;
        }
        LogBridge.i(TAG, "【OTT·一级导航】" + sectionName(topSection) + " → " + sectionName(section));
        topSection = section;
        // 离开直播板块就撤掉自动全屏计时，别让 5 秒后突然跳全屏打断浏览
        if (section != SECTION_LIVE) cancelAutoFullscreen();
        currentGroup = getString(R.string.ott_group_all);
        huyaSubGroup = null;
        lastGroupNames.clear();
        tabViews.clear();
        syncTopNavStyle();
        applyTopSectionVisibility();

        if (section == SECTION_MINE) {
            renderMinePanel();
            focusIntoSection();
            return;
        }
        // 【2026-10-17 v2】布局刚变化（分组条出现/消失）→ 重新按剩余空间收口左栏列表高度
        if (section != SECTION_MINE) {
            mainHandler.postDelayed(this::capEpgListHeight, 200);
        }
        buildCategoryTabs();
        applyFilter();
        focusIntoSection();
    }

    /** 板块可见性：我的页 ↔ 内容区互斥；全屏时二级分组条与我的页都收起 */
    private void applyTopSectionVisibility() {
        boolean mine = (topSection == SECTION_MINE);
        if (svCategoryBar != null) {
            svCategoryBar.setVisibility((mine || fullscreen) ? View.GONE : View.VISIBLE);
        }
        if (llMinePanel != null) {
            llMinePanel.setVisibility((mine && !fullscreen) ? View.VISIBLE : View.GONE);
        }
        if (ottContentRow != null) {
            ottContentRow.setVisibility(mine ? View.GONE : View.VISIBLE);
        }
    }

    /** 切板块后把焦点送到该板块的合理位置（绝不能让焦点停在导航上或丢失） */
    private void focusIntoSection() {
        mainHandler.post(() -> {
            try {
                if (topSection == SECTION_MINE) {
                    View first = firstFocusableChild(llMineHistory);
                    if (first == null) first = firstFocusableChild(llMineFavorite);
                    if (first != null && first.requestFocus()) {
                        LogBridge.i(TAG, "【OTT·一级导航】焦点 → 我的列表");
                    }
                    return;
                }
                if (gvChannel != null && gvChannel.requestFocus()) {
                    LogBridge.i(TAG, "【OTT·一级导航】焦点 → 频道网格");
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "【OTT·一级导航】焦点恢复失败: " + t.getMessage());
            }
        });
    }

    private View firstFocusableChild(ViewGroup parent) {
        if (parent == null) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != null && c.getVisibility() == View.VISIBLE && c.isFocusable()) return c;
        }
        return null;
    }

    /**
     * 是否点播（影视）频道：源7（afhgrj）、源8（renyibo，ryb://）、源5（renyibo-vod）。
     * 截图里的「电影 / 恐怖 / 电视剧 / 综艺 / 动漫」分组就来自这类频道，
     * 界面重构后它们只出现在「点播」板块。
     */
    private static boolean isVodChannel(Channel c) {
        if (c == null) return false;
        if (com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(c) != null) return true;
        // 【2026-10-19 源8（renyibo，ryb://）】与源7/源5 同为「频道即一部影片」的点播形态。
        //   漏这一条 → 源8 影视频道被当成直播频道：落到「直播」板块、左栏走直播节目单通路
        //   （最多 3 行）、「正在播放」取不到集数回落成组名、起播不记历史也不续播
        //   （用户报障：源8 的正在播放与 EPG 集数显示异常，源7 正常）。
        if (com.tv.live.ryb.RybUrlResolver.vodIdOf(c) != null) return true;
        // ⚠ 源5 的 vodIdOfChannelId 对非源5 频道返回 ""（不是 null）→ 必须 isEmpty 判断
        return !TextUtils.isEmpty(
                com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(c.getChannelId()));
    }

    // -------------------- 「我的」板块（播放记录 + 收藏） --------------------

    private void renderMinePanel() {
        renderMineHistoryPanel();
        renderMineFavoritePanel();
    }

    private void renderMineHistoryPanel() {
        if (llMineHistory == null) return;
        llMineHistory.removeAllViews();
        List<AppConfig.OttHistory> list = appConfig.getOttHistory();
        if (list == null || list.isEmpty()) {
            llMineHistory.addView(mineEmptyRow(getString(R.string.ott_mine_empty_history)));
            return;
        }
        int showN = Math.min(list.size(), OTT_HISTORY_MAX);
        for (int i = 0; i < showN; i++) {
            AppConfig.OttHistory h = list.get(i);
            if (h != null) llMineHistory.addView(buildHistoryRow(h));
        }
    }

    private void renderMineFavoritePanel() {
        if (llMineFavorite == null) return;
        llMineFavorite.removeAllViews();
        Set<String> keys = (appConfig == null) ? null : appConfig.getFavorites();
        if (keys == null || keys.isEmpty()) {
            llMineFavorite.addView(mineEmptyRow(getString(R.string.ott_mine_empty_favorite)));
            return;
        }
        int added = 0;
        for (String key : keys) {
            Channel target = null;
            for (Channel c : allChannels) {
                if (c != null && key.equals(favoriteKey(c))) { target = c; break; }
            }
            if (target == null) continue;   // 当前源里已不存在（换源后）→ 静默跳过
            final Channel ch = target;
            TextView row = new TextView(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            row.setText("★ " + ch.getName());
            row.setTextSize(17);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setTextColor(0xCCFFFFFF);
            row.setFocusable(true);
            row.setFocusableInTouchMode(false);
            row.setClickable(true);
            row.setId(View.generateViewId());
            row.setBackground(focusHighlightBg());
            row.setOnClickListener(v -> {
                playChannel(ch);
                enterFullscreenImmediately();
            });
            llMineFavorite.addView(row);
            added++;
        }
        if (added == 0) {
            llMineFavorite.addView(mineEmptyRow(getString(R.string.ott_mine_empty_favorite)));
        }
    }

    private TextView mineEmptyRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(0x66FFFFFF);
        tv.setPadding(dp(8), dp(10), dp(8), dp(10));
        return tv;
    }

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
        LogBridge.i(TAG, "【OTT·频道】applyChannels 收到 " + list.size() + " 个频道");

        buildCategoryTabs();
        applyFilter();

        // 【2026-10-19 用户口径】本源只有点播频道（没有自家的直播分组，如源7/源8）→
        //   首页自动落在「点播」板块，不再停在只有虎牙频道的空「直播」板块。
        //   判据要求"已经出现自家频道且全是点播"，避免分批回调时"只有虎牙数据"被误判；
        //   用户手动切过板块就不再自动调整（切源后由 applySourceSwitch 复位）。
        if (!userPickedBoard && topSection == SECTION_LIVE && ownLiveChannelState() == 0) {
            LogBridge.i(TAG, "【OTT·板块】本源无自家直播频道（只有点播）→ 自动落在「点播」");
            switchTopSection(SECTION_VOD);
        }

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
     * 【2026-10-19】本源「自家」频道的直播状态（虎牙系频道不算自家 —— 任何源都带虎牙）。
     *
     * @return 1 = 有自家直播频道；0 = 有自家频道但全是点播；-1 = 还没有自家频道（数据未到）
     */
    private int ownLiveChannelState() {
        boolean hasOwn = false;
        for (Channel c : allChannels) {
            if (c == null) continue;
            if (isHuyaChannel(c) || c.isTogetherWatch() || c.isHuyaSdkTogetherWatch()) continue;
            hasOwn = true;
            if (!isVodChannel(c)) return 1;
        }
        return hasOwn ? 0 : -1;
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

        // 【2026-10-17 用户口径】打开软件（冷启动自动起播）时**不弹**切台信息条，
        // 只有用户自己切台/换源才弹。用一次性标志，避免影响后续手动换台。
        suppressInfoBarOnce = true;
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

        // 【2026-10-17】重建前记住焦点所在 Tab：直播源分批到达时会多次进这里，
        // removeAllViews 会删掉焦点所有者 → 焦点掉到控制栏「‹ 上一频道」（用户实测）。
        final String keepTab = focusedChildText(llCategory);
        llCategory.removeAllViews();
        tabViews.clear();

        for (String cat : cats) {
            TextView tab = new TextView(this);
            tab.setId(View.generateViewId());
            tab.setText(cat);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tab.setTextColor(Color.WHITE);
            tab.setBackgroundResource(R.drawable.ott_tab_item_bg);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setPadding(dp(18), dp(7), dp(18), dp(7));
            tab.setNextFocusDownId(R.id.gv_ott_channel);
            // 【2026-10-17 v2】二级分组按「上」→ 回到一级导航当前项（重构后分组独占一行）
            if (!topNavViews.isEmpty() && topSection < topNavViews.size()) {
                tab.setNextFocusUpId(topNavViews.get(topSection).getId());
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            tab.setLayoutParams(lp);

            tab.setOnClickListener(v -> selectGroup(cat));
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                v.setScaleX(hasFocus ? 1.06f : 1f);
                v.setScaleY(hasFocus ? 1.06f : 1f);
                // 【2026-10-17 v2】二级分组多于可见宽度时，焦点要带着视野一起滚
                if (hasFocus) scrollChildIntoView(svCategoryBar, v);
                // 【2026-10-17 界面重构 v2】取消「焦点即切换」：
                //   原实现焦点经过哪个 Tab 就立刻切分组并重建列表。界面重构后二级分组独占一行、
                //   条目更多，遥控连按右键时路过的分组全被触发（实测切到点播板块后 currentGroup
                //   被甩到「动漫」，用户在不知情的情况下换了内容）。现改为主流做法：
                //   **焦点只做高亮，按确认键才切换**（见上面的 setOnClickListener）。
                //   suppressTabFocusSwitch 与 selectedTabView 等既有机制保持不变。
            });

            tab.setSelected(cat.equals(currentGroup));
            llCategory.addView(tab);
            tabViews.add(tab);
        }

        syncGridFocusUp();
        // 焦点原本在某个分组 Tab 上 → 重建后按文案还给它（否则会掉到控制栏）
        restoreChildFocusByText(llCategory, keepTab);

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

    /**
     * 二级分组集合（按当前一级板块生成）：
     * <ul>
     *   <li>直播板块 = 全部 + 直播频道分组 + 虎牙频道（<b>不再包含电影/电视剧等点播分类</b>）</li>
     *   <li>点播板块 = 全部 + 点播内容分类（电影/电视剧/综艺/动漫…，来自源5 type_id / 源7 影视片）</li>
     * </ul>
     * 这是 2026-10-17 界面重构的核心：把两种内容形态彻底分开，用户不再需要猜。
     */
    private List<String> buildGroupNames() {
        Set<String> autoGroups = new LinkedHashSet<>();
        final boolean vodSection = (topSection == SECTION_VOD);
        for (Channel c : allChannels) {
            if (c == null) continue;
            boolean vod = isVodChannel(c);
            if (vodSection) {
                if (!vod) continue;          // 点播板块只收影视片
            } else if (vod) {
                continue;                    // 直播板块剔除影视片
            }
            autoGroups.add(GroupListManager.getNormalizedGroup(c));
        }

        List<String> cats = new ArrayList<>();
        cats.add(getString(R.string.ott_group_all));
        cats.addAll(autoGroups);
        // 虎牙是直播频道 → 只在直播板块出现
        if (!vodSection) {
            cats.add(getString(R.string.ott_group_huya));
        }
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
     * 上「一级导航」→「二级分组 Tab」→ 子分组胶囊条（虎牙页）→ 频道网格；
     * 非虎牙页则是一级导航 → 二级分组 Tab → 频道网格。
     *
     * 【2026-10-17 v2 修复】原实现靠 `tab.isSelected()` 找当前 Tab，一旦 selected 状态与
     * currentGroup 不同步（切板块重建、异步源回调都会出现），焦点链就直接落空 ——
     * 实测网格按「上」跳过二级分组、直接窜到一级导航的「点播」。改为按 currentGroup 文本匹配，
     * 匹配不到时兜底第一个 Tab，保证这条链**永远有终点**。
     */
    private void syncGridFocusUp() {
        if (gvChannel == null) return;
        TextView cur = null;
        for (TextView tab : tabViews) {
            if (tab != null && currentGroup != null && currentGroup.contentEquals(tab.getText())) {
                cur = tab;
                break;
            }
        }
        if (cur == null && !tabViews.isEmpty()) cur = tabViews.get(0);
        if (cur == null) return;
        View chip = currentSubGroupChip();
        boolean withStrip = isHuyaTab() && svSubGroup != null
                && svSubGroup.getVisibility() == View.VISIBLE && chip != null;
        // 【2026-09-29 子分组焦点】进出胶囊条都直接对接具体胶囊，
        // 不让 sv_ott_subgroup 当中间站（它无可视焦点指示，会让人以为按键失灵）
        cur.setNextFocusDownId(withStrip ? chip.getId() : R.id.gv_ott_channel);
        gvChannel.setNextFocusUpId(withStrip ? chip.getId() : cur.getId());
        for (View c : subGroupChipViews) {
            if (c != null) c.setNextFocusUpId(cur.getId());
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
        final boolean vodSection = (topSection == SECTION_VOD);
        for (Channel c : allChannels) {
            if (c == null) continue;
            // 【2026-10-17 界面重构 v2】板块隔离：直播板块不显示影视片，点播板块只显示影视片
            boolean vod = isVodChannel(c);
            if (vodSection != vod) continue;
            if (all.equals(currentGroup)
                    || currentGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                shownChannels.add(c);
            }
        }
        if (gvChannel.getAdapter() != channelAdapter) gvChannel.setAdapter(channelAdapter);
        // 【2026-10-17】过滤后网格数据变了：若被聚焦的格子已不存在，系统会把焦点交给
        // 窗口第一个可聚焦控件（控制栏「‹ 上一频道」）。刷新后把焦点还给网格。
        keepGridFocusDuring(() -> {
            if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
        });

        updateEmptyTip(shownChannels.isEmpty(),
                vodSection ? getString(R.string.ott_vod_empty) : getString(R.string.ott_channel_empty));
        // 【2026-10-17 v2 诊断】板块隔离后要能一眼看出"为什么网格空"
        LogBridge.i(TAG, "【OTT·过滤】板块=" + sectionName(topSection)
                + " 分组=" + currentGroup
                + " all=" + allChannels.size() + " shown=" + shownChannels.size());
    }

    /**
     * 【2026-10-17 焦点保持】网格数据刷新期间保住焦点。
     *
     * <p>GridView 过滤/重绑后，原来被聚焦的格子可能已不存在 → 系统焦点搜索会落到
     * 控制栏第一颗按钮（「‹ 上一频道」/「‹ 上一集」），这就是用户看到的"焦点自己跳走"。
     * 本方法在刷新前记录「网格是否持有焦点 + 选中位置」，刷新后下一帧把焦点还回去。
     * 若网格当时没焦点（例如焦点在按钮/抽屉），则完全不动，避免抢焦点。
     */
    private void keepGridFocusDuring(Runnable refresh) {
        final boolean hadFocus = gridHasFocus();
        final int pos = (gvChannel == null) ? -1 : gvChannel.getSelectedItemPosition();
        refresh.run();
        if (!hadFocus || gvChannel == null) return;
        gvChannel.post(() -> {
            try {
                if (!gvChannel.isShown()) return;
                int n = (channelAdapter == null) ? 0 : channelAdapter.getCount();
                if (n <= 0) return;
                int p = Math.max(0, Math.min(pos, n - 1));
                gvChannel.setSelection(p);
                if (gvChannel.requestFocus()) {
                    LogBridge.i(TAG, "【焦点保持】网格刷新后回到 pos=" + p);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 网格（或其子项）当前是否持有焦点 */
    private boolean gridHasFocus() {
        if (gvChannel == null) return false;
        if (gvChannel.hasFocus()) return true;
        View f = getCurrentFocus();
        return f != null && isDescendantOf(gvChannel, f);
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
        // 虎牙网格同样做焦点保持（子分组切换/两路数据合并都会换掉列表内容）
        keepGridFocusDuring(() -> {
            if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
        });
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
        playChannel(channel, false);
    }

    /** 【2026-10-14】silent=true 时不刷新集数列表/历史记录面板，避免点击历史记录后列表重建丢焦点 */
    /**
     * 【2026-10-19 源8 顶部横幅广告遮罩】站点会把澳门博彩横幅**烧进画面**顶部，
     * 这种广告不在 m3u8 里（是逐帧叠加），剥离代理去不掉，只能用遮罩盖。
     * 策略：开播先盖住 → 广告走完自动收起（不是常驻，避免长期挡住正片画面）。
     */
    private View topAdMask = null;
    /** 广告时间窗时长：广告段实测 25.8s，放宽到 40s 留足余量（覆盖横幅可能的提前/延后） */
    /**
     * 【2026-10-19 暂时禁用】顶部横幅遮罩总开关。
     *
     * <p>关闭原因：PixelCopy 在 HLS + seek 场景下拿不到稳定的有效画面
     * （实测多为全黑/近黑帧），检测一再误判成"无横幅" → 遮罩不生效。
     * 先关掉；相关代码全部保留，把这里改成 true 即可恢复。
     */
    private static final boolean TOP_AD_MASK_ENABLED = false;

    private static final long AD_MASK_AUTO_HIDE_MS = 40_000L;
    private final Runnable hideTopAdMaskRunnable = new Runnable() {
        @Override
        public void run() {
            if (topAdMask != null) topAdMask.setVisibility(View.GONE);
        }
    };

    /** 【2026-10-19】按广告时间窗自动显示/收起遮罩的轮询（1s 一次，仅在源8 播放时跑） */
    private final Runnable adMaskTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (topAdMask == null || playingChannel == null
                    || com.tv.live.ryb.RybUrlResolver.vodIdOf(playingChannel) == null) {
                stopAdMaskTick();
                if (topAdMask != null) topAdMask.setVisibility(View.GONE);
                return;
            }
            long[] w = com.tv.live.util.M3u8AdStripper.getAdWindows(
                    com.tv.live.ryb.RybUrlResolver.sLastResolvedUrl);
            long pos = (mPlayerManager == null) ? 0 : mPlayerManager.getCurrentPosition();
            boolean show = false;
            if (w != null) {
                for (int i = 0; i + 2 < w.length; i += 3) {
                    // 原始时间 → 剥离后播放时间：减去本段之前已被剔除的广告时长
                    long startMs = w[i] - w[i + 2];
                    if (pos >= startMs - 1000L && pos <= startMs + AD_MASK_AUTO_HIDE_MS) {
                        // 只有检测到真有横幅才盖，避免误遮正片
                        show = windowHasBanner(startMs);
                        break;
                    }
                }
            }
            topAdMask.setVisibility(show ? View.VISIBLE : View.GONE);
            mainHandler.postDelayed(this, 1000L);
        }
    };

    /**
     * 【2026-10-19 防误遮盖】某个候选时间窗**是否真的出现了顶部横幅**。
     * 结果按窗缓存，窗口第一次进入时后台抓一帧判断一次，避免每秒解码。
     *
     * <p>判据：横幅是「压在画面顶部的一条文字条」，特征是顶部 8% 区域的
     * **横向边缘密度**明显高于紧邻其下的同高区域（文字笔画多、对比强）。
     * 抓帧失败或拿不到画面时**保守返回 false**（宁可不盖，也不误遮正片）。
     */
    private final java.util.Map<Long, Boolean> sBannerChecked = new java.util.HashMap<>();
    /** 正在探测中的窗口（避免同一窗口并发/重复起探测线程） */
    private final java.util.Set<Long> sProbing = new java.util.HashSet<>();

    private boolean windowHasBanner(long startMs) {
        if (startMs < 0) return true;
        synchronized (sBannerChecked) {
            Boolean v = sBannerChecked.get(startMs);
            if (v != null) return v;
        }
        // 同一窗口同时只允许一个探测在跑；未出结果前**先遮盖**，绝不留空窗
        synchronized (sProbing) {
            if (!sProbing.add(startMs)) return true;
        }
        final long at = startMs + 1000L;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Boolean hit = detectTopBanner(at);
                    if (hit == null) {
                        // 拿不到有效画面：不写缓存，下一轮继续重试
                        LogBridge.i(TAG, "【顶部横幅检测】窗口 " + (startMs / 1000)
                                + "s → 画面无效/取帧失败，稍后重试（本轮先遮盖）");
                        return;
                    }
                    synchronized (sBannerChecked) {
                        sBannerChecked.put(startMs, hit);
                    }
                    LogBridge.i(TAG, "【顶部横幅检测】窗口 " + (startMs / 1000) + "s → "
                            + (hit ? "有横幅（将遮盖）" : "无横幅（不遮盖）"));
                } finally {
                    synchronized (sProbing) {
                        sProbing.remove(startMs);
                    }
                }
            }
        }, "AdMask-Probe").start();
        return true;
    }

    /**
     * 【2026-10-19】按设置里选定的渲染方式，用对应手段取当前帧：
     * <ul>
     *   <li><b>TextureView</b> → 直接 {@code getBitmap()}（API 无要求，最简单）；</li>
     *   <li><b>SurfaceView（API 26+）</b> → {@code PixelCopy} 只拷顶部条带，开销最小；</li>
     *   <li><b>SurfaceView（&lt; 26）</b> → 退回 {@code MediaMetadataRetriever}，
     *       HLS 上常失败 → 返回 null 走保守遮盖。</li>
     * </ul>
     *
     * @return true=有横幅 / false=确认无横幅 / null=拿不到画面（无法判断）
     */
    private Boolean detectTopBanner(long posMs) {
        if (playerView == null) return null;
        android.view.View sv = playerView.getVideoSurfaceView();

        if (sv instanceof android.view.TextureView) {
            android.graphics.Bitmap bmp = ((android.view.TextureView) sv).getBitmap();
            LogBridge.i(TAG, "【顶部横幅检测】取帧方式=TextureView.getBitmap");
            return bmp == null ? null : analyzeTopBanner(bmp);
        }
        if (sv instanceof android.view.SurfaceView) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                LogBridge.i(TAG, "【顶部横幅检测】取帧方式=PixelCopy(SurfaceView)");
                return pixelCopyTopBanner((android.view.SurfaceView) sv);
            }
            LogBridge.i(TAG, "【顶部横幅检测】取帧方式=MediaMetadataRetriever(API<26 回退)");
            return mmrTopBanner(posMs);
        }
        return null;
    }

    /** PixelCopy 只拷顶部 16%（含对照区），成功后交给 {@link #analyzeTopBanner} */
    private Boolean pixelCopyTopBanner(android.view.SurfaceView sv) {
        int w = sv.getWidth();
        int h = sv.getHeight();
        if (w <= 0 || h <= 0) return null;
        int copyH = Math.max(8, Math.round(h * 0.16f));
        android.graphics.Bitmap bmp =
                android.graphics.Bitmap.createBitmap(w, copyH, android.graphics.Bitmap.Config.ARGB_8888);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] result = new int[]{-1};
        android.os.HandlerThread ht = new android.os.HandlerThread("AdMask-PixelCopy");
        ht.start();
        try {
            android.view.PixelCopy.request(
                    sv,
                    new android.graphics.Rect(0, 0, w, copyH),
                    bmp,
                    copyResult -> {
                        result[0] = copyResult;
                        latch.countDown();
                    },
                    new android.os.Handler(ht.getLooper()));
            latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【顶部横幅检测】PixelCopy 异常: " + t.getMessage());
            return null;
        } finally {
            ht.quitSafely();
        }
        if (result[0] != android.view.PixelCopy.SUCCESS) {
            bmp.recycle();
            return null;
        }
        Boolean r = analyzeTopBanner(bmp);
        bmp.recycle();
        return r;
    }

    /** MediaMetadataRetriever 回退（HLS 上经常失败） */
    private Boolean mmrTopBanner(long posMs) {
        android.media.MediaMetadataRetriever mmr = null;
        try {
            String url = com.tv.live.ryb.RybUrlResolver.sLastResolvedUrl;
            if (android.text.TextUtils.isEmpty(url)) return null;
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(url, new java.util.HashMap<String, String>());
            android.graphics.Bitmap bmp =
                    mmr.getFrameAtTime(posMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST);
            return bmp == null ? null : analyzeTopBanner(bmp);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【顶部横幅检测】MMR 异常: " + t.getMessage());
            return null;
        } finally {
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 用「顶部 8% vs 紧邻下方 8%」的横向边缘密度判断有没有横幅 */
    private Boolean analyzeTopBanner(android.graphics.Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int stripH = Math.max(4, Math.round(h * 0.08f));
        if (stripH * 2 >= h) return null;
        android.graphics.Bitmap top = android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, stripH);
        android.graphics.Bitmap below = android.graphics.Bitmap.createBitmap(bmp, 0, stripH, w, stripH);
        double e1 = edgeDensity(top);
        double e2 = edgeDensity(below);
        top.recycle();
        below.recycle();
        LogBridge.i(TAG, "【顶部横幅检测】顶部边缘密度=" + String.format("%.4f", e1)
                + " 下方=" + String.format("%.4f", e2));
        // 【2026-10-19 二次修正】"确认无横幅"必须建立在**画面确实有内容**的前提下。
        // 实测 seek 之后会拷到近黑帧（顶部 0.0000 / 下方 0.0017），此时两个值都极低，
        // 若只要求 > 1e-6 会被当成有效样本 → 误判成"无横幅" → 遮罩不显示（用户看到广告）。
        // 这里要求对照区（下方）密度达到 0.01，证明确实取到了真实画面，才允许判"无横幅"；
        // 否则一律判为无效（null）→ 走保守遮盖并重试。
        if (e2 < 0.01) {
            LogBridge.i(TAG, "【顶部横幅检测】画面内容不足（下方密度 " + String.format("%.4f", e2)
                    + " < 0.01），判为无效，本轮先遮盖");
            return null;
        }
        return e1 > 1.6 * Math.max(e2, 1e-6) && e1 > 0.02;
    }

    /** 横向边缘密度：相邻像素亮度差超过阈值的比例（文字/边框多则高） */
    private double edgeDensity(android.graphics.Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        long cnt = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w; x++) {
                int a = px[y * w + x - 1];
                int c = px[y * w + x];
                int la = (a >> 16 & 0xff) + (a >> 8 & 0xff) + (a & 0xff);
                int lc = (c >> 16 & 0xff) + (c >> 8 & 0xff) + (c & 0xff);
                if (Math.abs(la - lc) > 90) cnt++;
            }
        }
        return (double) cnt / (w * h);
    }

    /**
     * 【2026-10-19】开播就把所有广告窗的横幅检测**提前跑起来**。
     * 之前是"进入窗口才检测"，而抓帧要几百毫秒到几秒，结果出来时窗口都过去一大半了
     * （实测：09:15:40 seek 到 310s，09:15:42 才出检测结果，窗口 331s 就结束）。
     */
    private void prewarmBannerProbes() {
        long[] w = com.tv.live.util.M3u8AdStripper.getAdWindows(
                com.tv.live.ryb.RybUrlResolver.sLastResolvedUrl);
        if (w == null) return;
        for (int i = 0; i + 2 < w.length; i += 3) {
            windowHasBanner(w[i] - w[i + 2]);
        }
    }

    private void startAdMaskTick() {
        mainHandler.removeCallbacks(adMaskTickRunnable);
        mainHandler.postDelayed(adMaskTickRunnable, 1000L);
    }

    private void stopAdMaskTick() {
        mainHandler.removeCallbacks(adMaskTickRunnable);
    }

    private void updateTopAdMask() {
        if (!TOP_AD_MASK_ENABLED) {
            stopAdMaskTick();
            if (topAdMask != null) topAdMask.setVisibility(View.GONE);
            return;
        }
        if (topAdMask == null || playerView == null) return;
        mainHandler.removeCallbacks(hideTopAdMaskRunnable);
        boolean isRyb = playingChannel != null
                && com.tv.live.ryb.RybUrlResolver.vodIdOf(playingChannel) != null;
        if (!isRyb) {
            stopAdMaskTick();
            topAdMask.setVisibility(View.GONE);
            return;
        }
        // 不再「开播常驻」：改由 adMaskTickRunnable 按广告时间窗自动显示/收起
        startAdMaskTick();
        prewarmBannerProbes();
        // 高度按播放器实际高度的 10% 计算（预览窗与全屏都要对得上画面顶部那条）
        playerView.post(new Runnable() {
            @Override
            public void run() {
                if (topAdMask == null || playerView == null) return;
                int h = Math.round(playerView.getHeight() * 0.10f);
                if (h <= 0) return;
                android.view.ViewGroup.LayoutParams lp = topAdMask.getLayoutParams();
                if (lp != null && Math.abs(lp.height - h) > 2) {
                    lp.height = h;
                    topAdMask.setLayoutParams(lp);
                }
            }
        });
        mainHandler.postDelayed(hideTopAdMaskRunnable, AD_MASK_AUTO_HIDE_MS);
    }

    private void playChannel(Channel channel, boolean silent) {
        if (channel == null || TextUtils.isEmpty(channel.getPlayUrl())) return;

        // 【2026-10-18】换到别的片就不该再回原来那部的详情页；同一部（重播/续播）保留标记
        if (detailReopenOnExit != null && detailReopenOnExit != channel) {
            LogBridge.i(TAG, "【详情页】换片 → 清除「退全屏回详情」标记（原：" + detailReopenOnExit.getName() + "）");
            detailReopenOnExit = null;
        }

        // 【2026-10-14 视频进度续播】切台时先保存上一个频道的进度（只对点播，直播不记）
        if (playingChannel != null && mPlayerManager != null
                && isVodChannel(playingChannel)
                && !channel.getName().equals(playingChannel.getName())) {
            try {
                long pos = mPlayerManager.getCurrentPosition();
                long dur = mPlayerManager.getDuration();
                if (pos > 5000) {
                    appConfig.updateOttHistoryPosition(playingChannel.getName(), pos, dur);
                    LogBridge.i(TAG, "【OTT·切台保存进度】频道=" + playingChannel.getName()
                            + " 位置=" + pos + "ms 片长=" + dur + "ms");
                }
            } catch (Throwable ignored) {}
        }

        // 【2026-09-29 输号跳台】与直播模式对齐：任何一次起播都清掉待确认的数字输入缓冲，
        // 避免"输了号还没确认就被别的途径切台 → 1.5s 后突然跳台"
        numberInputBuffer.setLength(0);
        mainHandler.removeCallbacks(numberInputConfirmRunnable);

        playingChannel = channel;
        updateTopAdMask();
        LogBridge.i(TAG, "PLAYCHAN-ENTER " + channel.getName());
        // 直播换台：轻量信息条（3s 消失）。冷启动首次自动起播不弹（用户口径 2026-10-17）。
        if (suppressInfoBarOnce) {
            suppressInfoBarOnce = false;
            LogBridge.i(TAG, "【信息条】冷启动首次起播 → 不弹切台信息条");
        } else if (switchHintsSuppressed()) {
            // 【2026-10-17 用户口径】控制栏可见（用「频道列表 / 选集」抽屉切台）时不再叠加信息条：
            //   控制栏标题行本来就显示频道名 + 清晰度，信息条只会重复并挡住画面。
            //   右上角频道号 OSD 同属"换台提示"，一并抑制。
            if (channelInfoBar != null) channelInfoBar.hide();
            hideChannelNumOsd();
            LogBridge.i(TAG, "【信息条】控制栏可见 → 不弹信息条/频道号 OSD：" + channel.getName());
        } else {
            showChannelInfo(channel);
        }

        // 【2026-10-17 续播重构】集数续播统一支持源7 与源5：
        //   ① 集数列表已缓存 → 直接用那一集的地址起播（不先播第 1 集再跳，省一次解析）；
        //   ② 列表未就绪 → 先按频道默认地址起播，列表就绪后由各源的面板切过去；
        //   ③ 第 1 集（resumeIdx=0）同样走缓存直起，不再依赖 channel.getPlayUrl() 恰好是第 1 集。
        final boolean vodChannel = isVodChannel(channel);
        final String vodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(channel);           // 源7
        final String rnVodId = com.tv.live.renyibo.RenYiBoVodSourceLoader
                .vodIdOfChannelId(channel.getChannelId());                                    // 源5
        final String rybSlug = com.tv.live.ryb.RybUrlResolver.vodIdOf(channel);                // 源8
        final String histVodId = (vodId != null) ? vodId
                : (!TextUtils.isEmpty(rnVodId) ? rnVodId : rybSlug);                           // 写历史用的 id
        final int resumeOrdinal = vodChannel ? appConfig.getOttHistoryOrdinal(channel.getName()) : 0;
        final int resumeIdx = (resumeOrdinal > 0) ? (resumeOrdinal - 1) : -1;

        // 先复位两个集数下标，再按源恢复（以前源5 无条件被重置为 -1，导致集数永远从第 1 集开始）
        mCurrentAfhgrjEpisodeIdx = -1;
        mCurrentRenyiboEpisodeIdx = -1;
        pendingResumeEpisodeIdx = -1;
        if (vodId != null) {
            mCurrentAfhgrjEpisodeIdx = Math.max(0, resumeIdx);
        } else if (!TextUtils.isEmpty(rnVodId)) {
            mCurrentRenyiboEpisodeIdx = Math.max(0, resumeIdx);
        }

        String playUrl = channel.getPlayUrl();
        if (vodChannel && resumeIdx >= 0) {
            Channel.EpgItem cachedEp = cachedAfhgrjEpisode(channel, resumeIdx);
            if (cachedEp != null && !TextUtils.isEmpty(cachedEp.customPlayUrl)) {
                playUrl = cachedEp.customPlayUrl;
                LogBridge.i(TAG, "【OTT·续播】" + (vodId != null ? "源7" : "源5")
                        + " 缓存直起 第" + (resumeIdx + 1) + "集");
            } else {
                pendingResumeEpisodeIdx = resumeIdx;
                LogBridge.i(TAG, "【OTT·续播】" + (vodId != null ? "源7" : "源5")
                        + " 待列表就绪切第" + (resumeIdx + 1) + "集");
            }
        }
        // 【2026-10-14 视频进度续播】先读上次位置（必须在写历史之前，否则会被重置为 0）
        long resumePos = appConfig.getOttHistoryPosition(channel.getName());
        // 【2026-10-17 广告剥离适配】再读"保存时的片长"，用于恢复时做时间轴比例校正
        final long resumeDur = vodChannel ? appConfig.getOttHistoryDuration(channel.getName()) : 0;
        LogBridge.i(TAG, "【OTT·续播】频道=" + channel.getName()
                + " 上次集数=" + resumeOrdinal + " 上次位置=" + resumePos
                + "ms 上次片长=" + resumeDur + "ms");

        // 【2026-10-17 用户口径】播放记录**只记点播**：直播频道不再写入历史。
        //   直播没有"续播"语义（进来就是看当下），留在列表里只会挤掉点播影片的名额。
        //   源7 用 vodIdOf 判定、源5 用 channelId 前缀判定，统一走 isVodChannel()。
        if (vodChannel) {
            // 集数至少记 1，保证条目可续播；同时**带上已知位置**，
            // 否则 addOttHistory 新建对象会把 positionMs 清零（起播后被杀就丢进度）。
            appConfig.addOttHistory(channel.getName(), histVodId,
                    Math.max(1, resumeOrdinal), Math.max(0, resumePos), Math.max(0, resumeDur));
        } else {
            LogBridge.i(TAG, "【播放历史】直播频道不记录：" + channel.getName());
        }

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
        mPlayerManager.playUrl(playUrl, channel.getName(), channel);
        // 【2026-10-17 按源清晰度】起播即套用**该源自己**上次选择的封顶值（0=自动）
        // 必须无条件调用（含 0），否则从「封顶 720 的源」切到「自动的源」会残留上一源的封顶。
        try {
            final int src = sourceIndexOf(channel);
            int maxH = appConfig.getQualityForSource(src);
            appConfig.setVodMaxHeight(maxH);
            mPlayerManager.setMaxVideoHeight(maxH);
            LogBridge.i(TAG, "【清晰度】起播套用 源" + src + " → "
                    + (maxH <= 0 ? "自动" : maxH + "P"));
        } catch (Throwable ignored) {
        }
        // 【2026-10-14 视频进度续播】播放开始后，恢复到上次保存的位置（仅点播：直播没有"续播"语义，
        //   且直播流 seek 可能跳到异常位置）
        // 【2026-10-17】改为带校验的重试：m3u8 起播后 1.5s 时清单/时长往往还没就绪，
        //   此时 seekTo 会被丢弃（实测日志显示调了 seek 到 75s，8 秒后位置仍是 4.5s）。
        if (vodChannel && resumePos > 5000) {
            mainHandler.postDelayed(() -> seekResumeWithRetry(resumePos, resumeDur, 1), 1500);
        }
        if (!silent) renderHistoryPanel();

        tvNowChannel.setText(channel.getName());
        refreshNowPlayingProgram();
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();

        LogBridge.i(TAG, "起播频道：" + channel.getName());
        // 【2026-09-29 频道号 OSD】口径同直播模式：编号 = 全局列表 allChannels 里的下标 + 1
        int num = indexOfEquivalent(allChannels, channel) + 1;
        if (switchHintsSuppressed()) {
            // 控制栏可见时切台 → 右上角大数字也一并抑制（用户口径 2026-10-17）
            LogBridge.i(TAG, "【频道号 OSD】控制栏可见 → 跳过（" + num + "）");
        } else {
            showChannelNum(num);
            LogBridge.i(TAG, "显示频道号：" + num + " 频道=" + channel.getName());
        }
        // 控制栏可见时切台：标题行/清晰度胶囊要跟着换台刷新，
        // 否则抽屉关掉后控制栏还挂着上一个频道的名字（用户实测）。
        if (switchHintsSuppressed() && vodBar != null) {
            mainHandler.postDelayed(vodBar::refreshAll, 400);
        }
        // 【2026-09-29 方案 A 电信 IPTV 风格】刷新「节目单 + 元数据」面板
        if (!silent) refreshEpgPanel(channel);
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

    /**
     * 【2026-10-17 OTT 清晰度切换】全屏菜单键入口。
     *
     * <p>2026-10-17 起统一收口到 {@link #showQualityPanel()}：清晰度只有一条实现，
     * 才能保证「按源隔离」的口径在两条入口上完全一致（直播 1/2/3/5/6、点播 4/5/6/7）。
     */
    private void showQualityDialog() {
        showQualityPanel();
    }

    /**
     * 【2026-10-17 OTT 详情页 / 线路切换】长按频道卡片弹出：
     * 影片简介、主演、年份、地区、评分 + 播放线路选择（源7 才有详情数据）。
     * 详情复用集数解析已抓的详情页 HTML，不产生额外网络请求。
     */
    private void showVodDetail(Channel ch) {
        if (ch == null) return;
        final String vodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch);
        if (TextUtils.isEmpty(vodId)) {
            Toast.makeText(this, "该频道无详情信息（仅源7 影视支持）", Toast.LENGTH_SHORT).show();
            LogBridge.i(TAG, "【OTT·详情】非源7 频道，跳过: " + ch.getName());
            return;
        }
        final Channel target = ch;
        try {
            VodDetailDialog dlg = new VodDetailDialog(this, vodId, ch.getName(),
                    new VodDetailDialog.Callback() {
                        @Override
                        public void onSwitchLine(String vId, int sid) {
                            // Dialog 内已 rememberSid + invalidate，这里只负责重播当前集
                            LogBridge.i(TAG, "【OTT·详情】切线路 vodId=" + vId + " sid=" + sid
                                    + " → 重播 " + target.getName());
                            playChannel(target, true);
                            enterFullscreenImmediately();
                        }

                        @Override
                        public void onPlay() {
                            playChannel(target);
                            enterFullscreenImmediately();
                        }
                    });
            dlg.show();
            LogBridge.i(TAG, "【OTT·详情】打开详情弹窗 vodId=" + vodId + " 频道=" + ch.getName());
        } catch (Throwable t) {
            LogBridge.w(TAG, "【OTT·详情】弹窗失败: " + t.getMessage());
        }
    }

    /** 【2026-10-14】全屏快进/快退：按偏移量调整播放位置，边界自动钳制。 */
    private void seekByOffset(long offsetMs) {
        if (mPlayerManager == null) return;
        long cur = mPlayerManager.getCurrentPosition();
        long dur = mPlayerManager.getDuration();
        long target = cur + offsetMs;
        if (target < 0) target = 0;
        if (dur > 0 && target > dur) target = dur;
        mPlayerManager.seekTo(target);
        if (seekHint != null) seekHint.show(target, dur, offsetMs);   // 简易进度条反馈
        LogBridge.i(TAG, "【OTT·点播】快进/快退 offset=" + offsetMs + "ms cur=" + cur + "ms target=" + target + "ms");
    }

    /** 【2026-09-29 触摸点击修复】鼠标/手指点击频道卡片的入口（与 GridView.onItemClick 同语义）。
     *  卡片根布局 android:clickable="true" 但 ChannelAdapter 之前从未设监听，
     *  触摸点击被卡片自己消费、GridView.onItemClick 收不到 → 点频道无反应。这里显式接上。 */
    private void onChannelCardClick(int position) {
        if (position < 0 || position >= shownChannels.size()) return;
        Channel clicked = shownChannels.get(position);
        // 【2026-10-13 触摸修复】日志：确认触摸点击确实到达 playChannel 链路
        LogBridge.i(TAG, "触摸点击频道卡片 position=" + position + " 频道=" + clicked.getName());
        if (gvChannel != null) gvChannel.setSelection(position);   // 让焦点框跟着走
        // 【2026-10-17 v2】与遥控点击一致：点播进详情页，直播直连播放
        if (isVodChannel(clicked)) {
            openVodDetail(clicked, position);
            return;
        }
        playChannel(clicked);
        // 【2026-10-13】触摸点击频道同样立即全屏，取消 5s 自动全屏等待
        enterFullscreenImmediately();
    }

    /**
     * 【2026-10-17 v2】打开点播详情页（覆盖层）。
     *
     * <p>记录来源位置，关闭时把焦点还给那张海报；同时取消 5s 自动全屏计时，
     * 否则用户在本页看简介时会被强制拉进全屏。
     */
    private void openVodDetail(Channel ch, int position) {
        openVodDetail(ch, position, false);
    }

    /**
     * @param instant true = 无淡入、当场铺满（从全屏退回详情页时用，
     *                否则淡入过程中会先看到一帧 OTT 首页）
     */
    private void openVodDetail(Channel ch, int position, boolean instant) {
        if (ch == null || isFinishing()) return;
        if (detailPanel != null && detailPanel.isShowing()) return;
        FrameLayout root = findViewById(R.id.ott_root);
        if (root == null) return;
        detailSourcePosition = (position >= 0) ? position
                : (gvChannel == null ? -1 : gvChannel.getSelectedItemPosition());
        cancelAutoFullscreen();
        // 【2026-10-19 集数提速】标记「用户正在查看」的影片（源8 ryb）：EPG 预取闸门放行它，
        //   用户在详情页读简介/挑线路时集数已抓好，按下播放即刻有列表（此前要等按下播放才联网）。
        com.tv.live.ryb.RybUrlResolver.setForegroundSlug(
                com.tv.live.ryb.RybUrlResolver.vodIdOf(ch));
        LogBridge.i(TAG, "【详情页】打开：" + ch.getName()
                + "（源8 集数预取已放行）");
        // 该片自己的续播集数（不是「当前正在播的频道」的集数）
        int epIdx = historyEpisodeIndex(ch);
        detailPanel = new VodDetailPanel(this, root, ch, epIdx, new VodDetailPanel.Host() {
            @Override
            public void onDetailPlay(Channel c) {
                LogBridge.i(TAG, "【详情页】播放本片：" + c.getName());
                // 【2026-10-18】不再把来源位置清成 -1：退出全屏要回到详情页，
                //   之后再关详情页还得用这个位置把焦点还给来源海报。
                playChannel(c);
                enterFullscreenImmediately();
                detailReopenOnExit = c;   // 必须在 playChannel 之后（内部会按频道差异清标记）
                LogBridge.i(TAG, "【详情页】已登记「退全屏回详情」：" + c.getName()
                        + " 来源 position=" + detailSourcePosition);
            }

            @Override
            public void onDetailPlayEpisode(Channel c, Channel.EpgItem item) {
                LogBridge.i(TAG, "【详情页】播放指定集：" + c.getName()
                        + " / " + (item == null ? "null" : item.title));
                if (c == null || item == null) return;
                // 详情页可能来自「还没播过的片」→ 先把当前频道对齐，选集逻辑才对得上
                if (playingChannel != c) playingChannel = c;
                playAfhgrjEpisodeOtt(item);
                detailReopenOnExit = c;
                LogBridge.i(TAG, "【详情页】已登记「退全屏回详情」：" + c.getName()
                        + " 来源 position=" + detailSourcePosition);
            }

            @Override
            public boolean onDetailIsFavorite(Channel c) {
                return c != null && appConfig.isFavorite(favoriteKey(c));
            }

            @Override
            public boolean onDetailToggleFavorite(Channel c) {
                if (c == null) return false;
                boolean added = appConfig.toggleFavorite(favoriteKey(c));
                Toast.makeText(OttHomeActivity.this,
                        added ? ("已收藏：" + c.getName()) : ("已取消收藏：" + c.getName()),
                        Toast.LENGTH_SHORT).show();
                return added;
            }

            @Override
            public void onDetailClose() {
                detailPanel = null;
                // 【2026-10-18】起播流程里关详情（hide() 的 120ms 动画回调）不得把焦点抢回网格：
                //   否则要么打断刚进的全屏，要么把「退全屏要重开详情」的焦点链路搅乱。
                if (detailReopenOnExit != null || fullscreen) {
                    LogBridge.i(TAG, "【详情页】关闭：起播流程中 → 不抢网格焦点（来源 position="
                            + detailSourcePosition + "）");
                    return;
                }
                LogBridge.i(TAG, "【详情页】已关闭 → 焦点回到 position=" + detailSourcePosition);
                restoreGridFocusTo(detailSourcePosition);
            }
        });
        detailPanel.show(!instant);
    }

    /** 某部影片自己的续播集数下标（无记录返回 -1） */
    private int historyEpisodeIndex(Channel ch) {
        if (ch == null) return -1;
        try {
            boolean vod = isVodChannel(ch);
            if (!vod) return -1;
            int ord = appConfig.getOttHistoryOrdinal(ch.getName());
            return (ord > 0) ? (ord - 1) : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 详情页关闭后把焦点还给来源海报 */
    private void restoreGridFocusTo(int position) {
        if (gvChannel == null) return;
        mainHandler.postDelayed(() -> {
            try {
                int n = (channelAdapter == null) ? 0 : channelAdapter.getCount();
                if (n <= 0) return;
                int p = (position >= 0) ? Math.min(position, n - 1) : 0;
                gvChannel.setSelection(p);
                if (gvChannel.requestFocus()) {
                    LogBridge.i(TAG, "【详情页】焦点已回到网格 pos=" + p);
                }
            } catch (Throwable ignored) {
            }
        }, 90);
    }

    /** 【2026-10-13】网格点击频道后立即全屏：取消 5s 自动全屏计时并直接进入全屏态。 */
    private void enterFullscreenImmediately() {
        cancelAutoFullscreen();
        if (!fullscreen) setFullscreen(true);
    }

    /**
     * 【2026-10-17 续播 seek 重试 + 广告剥离校正】把播放位置恢复到上次保存处。
     *
     * <p>两个问题一起解决：
     * <ol>
     *   <li><b>seek 太早</b>：m3u8 起播后约 1.5s 时清单/时长常未就绪，此刻 {@code seekTo}
     *       会被丢弃 —— 实测「seek 到 75s」之后 8 秒位置仍是 4.5s。改为每 2.5s 校验重试，
     *       时长未知就跳过，偏离超过 5s 再 seek，最多 4 次。</li>
     *   <li><b>广告剥离改变时间轴</b>：点播走本地代理会剔除广告分片，片长因而比源清单短。
     *       两次剥离结果不同时，同一个"秒数"会落到不同画面。这里用
     *       「当前片长 ÷ 保存时片长」做比例校正（差异 &gt;3% 才生效）。</li>
     * </ol>
     *
     * @param savedDurMs 保存进度时的片长；0 = 旧数据，不做比例校正
     */
    private void seekResumeWithRetry(final long targetMs, final long savedDurMs, final int attempt) {
        if (mPlayerManager == null) return;
        try {
            long dur = mPlayerManager.getDuration();
            long cur = mPlayerManager.getCurrentPosition();
            long fixed = targetMs;
            // 广告剥离导致的时间轴偏差：按片长比例映射
            if (dur > 0 && savedDurMs > 0) {
                double ratio = (double) dur / (double) savedDurMs;
                if (Math.abs(ratio - 1.0) > 0.03) {
                    fixed = (long) (targetMs * ratio);
                    LogBridge.i(TAG, "【OTT·续播】片长变化 " + (savedDurMs / 1000) + "s → "
                            + (dur / 1000) + "s（广告剥离差异）→ 位置校正 "
                            + (targetMs / 1000) + "s → " + (fixed / 1000) + "s");
                }
            }
            if (dur > 0 && Math.abs(cur - fixed) <= 5000) {
                LogBridge.i(TAG, "【OTT·续播】已就位 cur=" + (cur / 1000) + "s");
                return;
            }
            if (dur > 0 && fixed > dur - 5000) fixed = Math.max(0, dur - 5000);
            if (dur > 0) {
                mPlayerManager.seekTo(fixed);
                LogBridge.i(TAG, "【OTT·续播】seek 第" + attempt + "次 → "
                        + (fixed / 1000) + "s（当前 " + (cur / 1000) + "s / 时长 "
                        + (dur / 1000) + "s）");
            } else {
                LogBridge.i(TAG, "【OTT·续播】时长未就绪，第" + attempt + "次等待后重试");
            }
        } catch (Throwable ignored) {
        }
        if (attempt < 4) {
            mainHandler.postDelayed(() -> seekResumeWithRetry(targetMs, savedDurMs, attempt + 1), 2500);
        }
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
                // 【2026-10-11 源7】内置源7（afhgrj 影视）：EPG 项即「集数」，均无 isPlaying 标记，
                //   用当前集下标 mCurrentAfhgrjEpisodeIdx 取标题作为「正在播放」。
                if (com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch) != null) {
                    int idx = mCurrentAfhgrjEpisodeIdx;
                    if (idx < 0) idx = 0;
                    if (idx < items.size() && items.get(idx) != null) {
                        program = items.get(idx).title;
                    }
                } else if (!TextUtils.isEmpty(com.tv.live.renyibo.RenYiBoVodSourceLoader
                        .vodIdOfChannelId(ch.getChannelId()))) {
                    // 【2026-10-13 源5】renyibo-vod 选集：用 mCurrentRenyiboEpisodeIdx 取标题
                    int idx = mCurrentRenyiboEpisodeIdx;
                    if (idx < 0) idx = 0;
                    if (idx < items.size() && items.get(idx) != null) {
                        program = items.get(idx).title;
                    }
                } else if (com.tv.live.ryb.RybUrlResolver.vodIdOf(ch) != null) {
                    // 【2026-10-19 源8】ryb（renyibo）选集：当前集下标由 {@link RybUrlResolver}
                    //   记录（起播解析/点选集都会写入），与选集列表同源。漏这条分支时
                    //   ryb 的 EpgItem 没有 isPlaying 标记 → program 取不到 → 回落显示分组名
                    //   （用户报障：源8「正在播放 · 电视剧国产剧」）。
                    int idx = currentEpisodeIndex();
                    if (idx < 0) idx = 0;
                    if (idx < items.size() && items.get(idx) != null) {
                        program = items.get(idx).title;
                    }
                } else {
                    for (Channel.EpgItem it : items) {
                        if (it != null && it.isPlaying) {
                            program = it.title;
                            break;
                        }
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
        // 【2026-10-17】先记焦点行：重建会删掉焦点所有者 → 焦点掉到控制栏「‹ 上一频道」
        final int keepRow = focusedChildIndex(llEpgList);
        llEpgList.removeAllViews();
        fillEpgPanel(ch);
        // 【2026-10-11】面板重建后统一重连焦点（集数列表 + 历史记录 + 网格 + 预览卡）
        rewireOttFocus();
        capEpgListHeight();
        // 【2026-10-14】面板重建后自动把焦点定位到当前播放集数，避免回到第01集
        if (mCurrentAfhgrjEpisodeIdx >= 0 && llEpgList.getChildCount() > mCurrentAfhgrjEpisodeIdx) {
            llEpgList.post(() -> focusAfhgrjEpgRow(mCurrentAfhgrjEpisodeIdx));
        } else {
            // 非源7（直播）：焦点原本在节目单某一行 → 重建后还回同一行
            restoreChildFocusByIndex(llEpgList, keepRow);
        }
    }

    /**
     * 【2026-10-11 源7】给 EPG 列表加「最大高度 + 可滚动」。
     *
     * <p>{@code android:maxHeight} 在 {@code ScrollView} 上<b>不生效</b>（该属性仅 ImageView
     * 等读取，ViewGroup 不读），故在填充完成后按「内容高度 vs 上限」取小值，回设 ScrollView
     * 的 layout_height。直播源仅 3 行 < 上限 → 高度 = 内容高度，行为与改前完全一致；
     * 源7 集数多（如 28 集）→ 限高后可上下滚动。
     */
    private void capEpgListHeight() {
        if (svEpgList == null || llEpgList == null) return;
        svEpgList.post(() -> {
            int width = svEpgList.getWidth();
            if (width <= 0) return;
            // 【2026-10-17 界面重构 v2】上限不再写死 240dp：
            //   顶部多了一行二级分组条，左栏垂直空间变少，固定上限会把「集数/节目单 + 播放记录」
            //   顶出可视区（用户截图：第 30 集跑到屏幕外，焦点跟着走出画面看不见）。
            //   现在取 min(设计上限, 左面板实际剩余高度)，列表在自身范围内滚动。
            int maxH = dp(EPG_LIST_MAX_HEIGHT_DP);
            int avail = leftListAvailHeight();
            if (avail > 0) maxH = Math.min(maxH, avail);
            int target = measureCappedHeight(llEpgList, width, maxH);
            applyHeight(svEpgList, target);
            // 【2026-10-14】历史记录高度与集数列表同高，同样受剩余空间约束
            if (svOttHistory != null) {
                applyHeight(svOttHistory, maxH);
            }
        });
    }

    /**
     * 【2026-10-17 v2】左栏「集数/节目单 + 播放记录」可用的最大高度（px）。
     *
     * <p>由左面板总高减去预览卡、频道名、正在播行、面板 margin 与底部余量得到。
     * 无法测量（未布局 / 全屏 / 我的板块隐藏）时返回 -1，调用方回退到固定上限。
     */
    private int leftListAvailHeight() {
        View left = findViewById(R.id.ott_left_panel);
        if (left == null || left.getHeight() <= 0) return -1;
        if (fullscreen || left.getVisibility() != View.VISIBLE) return -1;
        int used = 0;
        if (flPreview != null && flPreview.getHeight() > 0) {
            used += flPreview.getHeight() + dp(10);      // 预览卡 + 频道名 marginTop
        }
        if (tvNowChannel != null && tvNowChannel.getVisibility() == View.VISIBLE) {
            used += tvNowChannel.getHeight();
        }
        View nowRow = findViewById(R.id.ll_ott_now_row);
        if (nowRow != null && nowRow.getVisibility() == View.VISIBLE) {
            used += nowRow.getHeight() + dp(4);          // 正在播行 + marginTop
        }
        if (llEpgPanel != null && llEpgPanel.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            used += ((LinearLayout.LayoutParams) llEpgPanel.getLayoutParams()).topMargin;
        }
        used += dp(14);                                   // 底部安全余量
        int avail = left.getHeight() - used;
        return avail > 0 ? avail : -1;
    }

    /** 量出内容高度，并按设计上限裁剪 */
    private int measureCappedHeight(View content, int width) {
        return measureCappedHeight(content, width, dp(EPG_LIST_MAX_HEIGHT_DP));
    }

    /** 量出内容高度，并按给定上限（px）裁剪 */
    private int measureCappedHeight(View content, int width, int maxPx) {
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return Math.max(0, Math.min(content.getMeasuredHeight(), maxPx));
    }

    private static void applyHeight(View v, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            v.setLayoutParams(lp);
        }
    }

    /**
     * EPG 面板实际填充逻辑（调用方 {@link #refreshEpgPanel} 负责清空与限高）。
     */
    private void fillEpgPanel(Channel ch) {
        if (llEpgList == null) return;
        // 【2026-10-11 源7 遥控焦点】先复位为布局默认，避免源7 集数的焦点链接残留在
        //   非源7 频道上（源7 分支内部会按需重新接线）。
        if (flPreview != null) flPreview.setNextFocusDownId(R.id.gv_ott_channel);
        if (gvChannel != null) gvChannel.setNextFocusLeftId(R.id.fl_ott_preview);
        if (ch == null) {
            addEpgRow("暂无节目信息", "", false);
            return;
        }
        // 【2026-10-11 源7】内置源7（afhgrj 影视点播）：EPG 面板改为「集数列表」，
        //   每行一集、可点击切换（数据源与直播模式 EpgManager.EpgAdapter 一致）。
        //   只依赖 channelId 前缀 afhgrj: 判定，源1~源6 行为零变化。
        if (com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch) != null) {
            refreshAfhgrjEpgPanel(ch);
            return;
        }
        // 【2026-10-12 源5】内置源5（aiqiyi VOD）与源7 同形态：EPG 面板为「选集列表」。
        //   只依赖 channelId 前缀 renyibo-vod: 判定，源1~源7 其它频道行为零变化。
        String rnVodId = com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(ch.getChannelId());
        if (!TextUtils.isEmpty(rnVodId)) {
            refreshRenyiboEpgPanel(ch);
            return;
        }
        // 【2026-10-19 源8】内置源8（renyibo，ryb://）与源7/源5 同形态：EPG 面板为「选集列表」。
        //   只依赖 ryb:// 协议判定，源1~7 其它频道行为零变化。
        //   漏这条分支时 ryb 的选集被当成「直播节目单」处理（filterToday/sortByTime + 最多 3 行）
        //   → 用户看到「只显示第01~03集」（用户报障：源8 EPG 集数显示异常）。
        if (com.tv.live.ryb.RybUrlResolver.vodIdOf(ch) != null) {
            refreshRybEpgPanel(ch);
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

    // ==================== 【2026-10-11 源7】OTT EPG 面板 · 集数列表 ====================

    /**
     * 内置源7（afhgrj 影视点播）在 OTT 左面板 EPG 区展示「集数列表」。
     *
     * <p>数据源与直播模式完全一致：{@link EpgManager#getEpg(Channel)} 对 {@code afhgrj:} 前缀
     * 频道返回 {@link com.tv.live.afhgrj.AfhgrjUrlResolver} 的集数项（{@code title=第N集}、
     * {@code customPlayUrl=afhgrj://vodId-sid-nid}）。只读内存缓存；未命中时 EpgManager 会
     * 后台抓一次详情页，完成后 {@code notifyEpgDataReady()} → 本页已注册监听 → 自动重刷。
     *
     * <p>每行整行可点击（触摸）/ 可获焦（遥控），点击即切到该集播放；当前集蓝色加粗。
     */
    private void refreshAfhgrjEpgPanel(Channel ch) {
        List<Channel.EpgItem> items = null;
        try {
            EpgManager epg = EpgManager.getInstance(getApplicationContext());
            items = epg.getEpg(ch);
        } catch (Exception ignored) {}
        if (items == null || items.isEmpty()) {
            // 集数尚未就绪（首次进入后台抓详情页中）；就绪后由 EPG 监听器自动重刷
            addEpgRow("集数加载中…", "", false);
            return;
        }
        // 【2026-10-11 源7 续播】列表就绪：若起播时因列表未就绪而挂了「待续播集数」，现在切过去。
        //   只用一次（pending 用完即清）；非源7 / 无续播时恒为 -1，不参与。
        if (pendingResumeEpisodeIdx > 0) {
            int target = pendingResumeEpisodeIdx;
            pendingResumeEpisodeIdx = -1;
            if (target < items.size()) {
                Channel.EpgItem t = items.get(target);
                if (t != null && !TextUtils.isEmpty(t.customPlayUrl)) {
                    LogBridge.i(TAG, "【OTT·源7】列表就绪 → 切到上次集数 第" + (target + 1) + "集");
                    playAfhgrjEpisodeOtt(t);
                    return;   // playAfhgrjEpisodeOtt 内部会重建面板
                }
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Channel.EpgItem it = items.get(i);
            if (it == null) continue;
            String title = TextUtils.isEmpty(it.title) ? ("第" + (i + 1) + "集") : it.title;
            addAfhgrjEpgRow(title, it, i == mCurrentAfhgrjEpisodeIdx);
        }
        // 焦点接线由 refreshEpgPanel 统一做（rewireOttFocus），此处不再单独接线
    }

    /**
     * 【2026-10-12】内置源5（aiqiyi VOD）在 OTT 左面板 EPG 区展示「选集列表」（与源7
     * 集数列表同形态：每行一项、整行可点）。
     *
     * <p>数据源与直播模式完全一致：{@link EpgManager#getEpg(Channel)} 对
     * {@code renyibo-vod:} 前缀频道返回选集项（{@code title=集数/清晰度}、
     * {@code customPlayUrl=video_detail 明文里的直链 m3u8}）。只读内存缓存；未命中时
     * EpgManager 会后台抓一次 video_detail，完成后 {@code notifyEpgDataReady()} →
     * 本页已注册监听 → 自动重刷。行点击直接播放直链（无需起播惰性解析）。
     */
    private void refreshRenyiboEpgPanel(Channel ch) {
        List<Channel.EpgItem> items = null;
        try {
            EpgManager epg = EpgManager.getInstance(getApplicationContext());
            items = epg.getEpg(ch);
        } catch (Exception ignored) {}
        if (items == null || items.isEmpty()) {
            // 选集尚未就绪（首次进入后台抓 video_detail 中）；就绪后由 EPG 监听器自动重刷
            addEpgRow("选集加载中…", "", false);
            return;
        }
        // 【2026-10-17 源5 续播】列表就绪：若起播时列表未就绪而挂了「待续播集数」，现在切过去。
        //   与源7 的 refreshAfhgrjEpgPanel 同一套逻辑（源5 以前完全没有这段 → 集数永远从第 1 集开始）。
        if (pendingResumeEpisodeIdx > 0 && pendingResumeEpisodeIdx < items.size()) {
            int target = pendingResumeEpisodeIdx;
            pendingResumeEpisodeIdx = -1;
            Channel.EpgItem t = items.get(target);
            if (t != null && !TextUtils.isEmpty(t.customPlayUrl)) {
                LogBridge.i(TAG, "【OTT·源5】列表就绪 → 切到上次集数 第" + (target + 1) + "集");
                playAfhgrjEpisodeOtt(t);
                return;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Channel.EpgItem it = items.get(i);
            if (it == null) continue;
            String title = TextUtils.isEmpty(it.title) ? ("第" + (i + 1) + "集") : it.title;
            // 复用源7 的可点击行样式（点击 → playAfhgrjEpisodeOtt → 直接播放 customPlayUrl）；
            // 【2026-10-13 源5 联播】用 mCurrentRenyiboEpisodeIdx 高亮当前播放的选集项。
            addAfhgrjEpgRow(title, it, i == mCurrentRenyiboEpisodeIdx);
        }
    }

    /**
     * 【2026-10-19】内置源8（renyibo，ryb://）在 OTT 左面板 EPG 区展示「选集列表」，
     * 与源7（afhgrj）/源5（renyibo-vod）同形态：每行一集、整行可点、当前集高亮。
     *
     * <p>数据源：{@link EpgManager#getEpg(Channel)} 对 {@code ryb:} 前缀频道返回
     * {@link com.tv.live.ryb.RybUrlResolver} 的选集项（{@code title=第NN集}、
     * {@code customPlayUrl=ryb://<slug>@<src>-<ep>}）。当前集下标取解析器记录的真实值
     * （点选集时面板会同步写入），不复用 mCurrentAfhgrjEpisodeIdx（那是源7 的槽位）。
     */
    private void refreshRybEpgPanel(Channel ch) {
        List<Channel.EpgItem> items = null;
        try {
            EpgManager epg = EpgManager.getInstance(getApplicationContext());
            items = epg.getEpg(ch);
        } catch (Exception ignored) {}
        if (items == null || items.isEmpty()) {
            // 集数尚未就绪（首次进入后台抓详情页中）；就绪后由 EPG 监听器自动重刷
            addEpgRow("集数加载中…", "", false);
            return;
        }
        final int cur = currentEpisodeIndex();
        for (int i = 0; i < items.size(); i++) {
            Channel.EpgItem it = items.get(i);
            if (it == null) continue;
            String title = TextUtils.isEmpty(it.title) ? ("第" + (i + 1) + "集") : it.title;
            addAfhgrjEpgRow(title, it, i == cur);
        }
    }

    /**
     * 【2026-10-11】统一重连 OTT 左面板（预览卡 / 集数列表 / 历史记录 / 频道网格）的遥控焦点。
     *
     * <p>原布局 {@code 预览卡↓=网格}、{@code 网格←=预览卡} 是<b>闭环</b>，集数行/历史行
     * 靠几何搜索够不着（用户实测「焦点移不到 EPG」）。这里按当前真实存在的子视图重连，
     * <b>幂等</b>，可随面板反复重建而反复调用：
     * <ul>
     *   <li>预览卡「下」→ 集数首行（无集数行 → 网格）</li>
     *   <li>网格「左」→ 集数首行（无 → 预览卡）</li>
     *   <li>集数首行「上」→ 预览卡；集数末行「下」→ 网格；集数行「右」→ 历史首行（无历史 → 网格）</li>
     *   <li>历史行「左」→ 集数首行（无 → 预览卡）、「右」→ 网格；历史首行「上」→ 预览卡、末行「下」→ 网格</li>
     * </ul>
     * 行与行之间的上下交给几何搜索（ScrollView 会自动滚动到获焦行）。
     */
    private void rewireOttFocus() {
        List<View> epgRows = focusableChildren(llEpgList);
        List<View> histRows = focusableChildren(llOttHistoryRows);
        View epgFirst = epgRows.isEmpty() ? null : epgRows.get(0);
        View epgLast = epgRows.isEmpty() ? null : epgRows.get(epgRows.size() - 1);
        View histFirst = histRows.isEmpty() ? null : histRows.get(0);
        View histLast = histRows.isEmpty() ? null : histRows.get(histRows.size() - 1);

        // 【2026-10-14 焦点记忆】回到集数列表时优先回到上次聚焦的行；
        //   记忆失效则回退首行；无集数行时 epgReturnId=-1，各方向按场景自行回退。
        int epgReturnId = -1;
        if (lastFocusedEpgRowId != -1 && findViewById(lastFocusedEpgRowId) != null) {
            epgReturnId = lastFocusedEpgRowId;
        } else if (epgFirst != null) {
            epgReturnId = epgFirst.getId();
        }
        if (flPreview != null) {
            // 预览卡「下」：有集数行到记忆行；无集数行但有历史记录到历史首行；否则到频道网格
            int previewDownId = R.id.gv_ott_channel;
            if (epgReturnId != -1) {
                previewDownId = epgReturnId;
            } else if (histFirst != null) {
                previewDownId = histFirst.getId();
            }
            flPreview.setNextFocusDownId(previewDownId);
        }
        if (gvChannel != null) {
            // 频道网格「左」：有集数行到记忆行；无集数行但有历史记录到历史首行；否则到预览卡
            int gridLeftId = R.id.fl_ott_preview;
            if (epgReturnId != -1) {
                gridLeftId = epgReturnId;
            } else if (histFirst != null) {
                gridLeftId = histFirst.getId();
            }
            gvChannel.setNextFocusLeftId(gridLeftId);
        }
        if (epgFirst != null) {
            epgFirst.setNextFocusUpId(R.id.fl_ott_preview);
        }
        if (epgLast != null && epgLast != epgFirst) {
            // 【2026-10-14】末行下键指向自己，停在原地（避免跳到网格）
            epgLast.setNextFocusDownId(epgLast.getId());
        }
        int epgRightId = (histFirst != null) ? histFirst.getId() : R.id.gv_ott_channel;
        for (int i = 0; i < epgRows.size(); i++) {
            View r = epgRows.get(i);
            r.setNextFocusRightId(epgRightId);
            // 【2026-10-14】显式设置行与行之间的上下焦点，避免依赖几何搜索
            //   （ScrollView 未自动滚动时，几何搜索会跳过不可见的下行直接找到网格）
            if (i > 0) r.setNextFocusUpId(epgRows.get(i - 1).getId());
            if (i < epgRows.size() - 1) r.setNextFocusDownId(epgRows.get(i + 1).getId());
        }
        for (int i = 0; i < histRows.size(); i++) {
            View r = histRows.get(i);
            // 历史记录「左」：有集数行到记忆行，否则到预览卡
            r.setNextFocusLeftId(epgReturnId != -1 ? epgReturnId : R.id.fl_ott_preview);
            r.setNextFocusRightId(R.id.gv_ott_channel);
            // 【2026-10-14】显式设置行与行之间的上下焦点
            if (i > 0) r.setNextFocusUpId(histRows.get(i - 1).getId());
            if (i < histRows.size() - 1) r.setNextFocusDownId(histRows.get(i + 1).getId());
        }
        if (histFirst != null) {
            histFirst.setNextFocusUpId(R.id.fl_ott_preview);
        }
        if (histLast != null && histLast != histFirst) {
            // 【2026-10-14】末行下键指向自己，停在原地
            histLast.setNextFocusDownId(histLast.getId());
        }
    }

    /** 取容器内「可获焦」的子视图（空列表 = 没有可聚焦项，如直播源的只读节目单） */
    private static List<View> focusableChildren(ViewGroup parent) {
        List<View> out = new ArrayList<>();
        if (parent == null) return out;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (v != null && v.isFocusable()) out.add(v);
        }
        return out;
    }

    // ==================== 【2026-10-11】源7 自动联播 / 续播辅助 ====================

    // ------------------------------------------------------------------
    // 【2026-10-11 新增】源7 专属「全屏播放器控制栏」
    //
    // 需求：OTT 播放器全屏时，内置源7 按确认键 / 单击触摸要能唤起播放器控制栏（可拖动进度、
    //       继续/暂停），且<b>只对源7 生效</b>——源1~源6（含虎牙）保持 useController=false，
    //       行为完全不变（它们没有"整片集数"概念，控制栏会带来误操作）。
    // 实现：初始一律 setUseController(false)；仅当「全屏 + 当前频道是源7」时临时打开并 show，
    //       5s 后（或退出全屏/切台/退后台时）收起并复位为 false。
    // ------------------------------------------------------------------

    /** 控制栏自动隐藏延时（与直播模式 PlayerControlManager 同口径 5s） */
    private static final long CONTROLLER_HIDE_MS = 5000L;
    /** 控制栏是否展示中（仅源7 全屏） */
    private boolean ottControllerShowing = false;
    /**
     * 【2026-10-17 用户口径】冷启动自动起播时**不弹**切台信息条（一次性）。
     * 用户自己切台/换源仍照常弹；用完立即复位，不影响后续换台。
     */
    private boolean suppressInfoBarOnce = false;
    /** 【2026-10-17】自绘点播控制栏（替代 PlayerView 内置控制栏） */
    private VodControlBar vodBar;
    /** 【2026-10-17】控制栏上方的胶囊弹层（清晰度/线路/倍速）与选集弹层，同一时刻只留一个 */
    private CapsuleChoicePanel capsulePanel;
    /** 【2026-10-18 两级导航】胶囊面板层级栈：一级 keepOpen 叠加二级，BACK 逐层返回，栈空回控制栏 */
    private final java.util.ArrayDeque<CapsuleChoicePanel> capsuleStack = new java.util.ArrayDeque<>();
    /** 打开一级胶囊面板时的触发视图（栈清空时焦点回它） */
    private View capsuleTrigger;
    /** 【2026-10-18】一级面板/条目引用：二级切换选项后回写一级显示（refreshItem） */
    private CapsuleChoicePanel playRootPanel, menuRootPanel;
    private java.util.List<CapsuleChoicePanel.Item> playRootItems, menuRootItems;
    /** 源5 线路表未就绪时的自动重试标记（同一部片只重试一次，避免无限递归把主线程打死→ANR） */
    private String lineAutoRetryVodId;
    private EpisodePanel episodePanel;
    /** 控制栏隐藏时的简易进度条（左右键快进/快退反馈） */
    private SeekHintBar seekHint;
    /** 直播：频道列表抽屉 / 切台信息条 */
    private ChannelListPanel channelListPanel;
    /** 抽屉刚打开的时间戳：抢焦点需要一帧，期间不判"焦点离开" */
    private long panelOpenedAtMs;
    /**
     * 抽屉内最后一次获得焦点的控件。抽屉内部重建会销毁焦点所有者（焦点瞬落到控制栏），
     * 越界兜底时优先把它抢回来，而不是退到"第一个可聚焦控件"。
     */
    private View lastFocusInPanel;
    /**
     * 【2026-10-17】抽屉正在被**主动**关闭（返回键 / 选中提交）。
     * 置位期间焦点看门狗不把焦点抢回抽屉 —— 否则返回键会把焦点弹回去，永远关不掉。
     */
    private volatile boolean panelClosing = false;
    private ChannelInfoBar channelInfoBar;
    /** 画面比例循环下标：0 适应 / 1 填充 / 2 拉伸 */
    private int mAspectIdx = 1;
    private static final String[] ASPECT_LABELS = {"适应", "填充", "拉伸"};
    private final Runnable hideOttControllerRunnable = this::maybeHideController;

    /** 自动收起判定：暂停期间不收起（与主流 TV 端一致） */
    private void maybeHideController() {
        if (!ottControllerShowing) return;
        if (mPlayerManager != null && !mPlayerManager.isPlaying()) {
            mainHandler.postDelayed(hideOttControllerRunnable, 2000);
            return;
        }
        hideSource7Controller();
    }

    /** 当前频道是否为内置源7（afhgrj 影视点播） */
    private boolean isSource7Playing() {
        Channel ch = playingChannel;
        return ch != null && com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch) != null;
    }

    /** 【2026-10-13】是否支持全屏播放器控制栏：源7（afhgrj）或源5（renyibo-vod）点播 */
    private boolean isVodControllerEligible() {
        Channel ch = playingChannel;
        if (ch == null) return false;
        // 源7（afhgrj）/ 源5（renyibo-vod）点播频道
        if (com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch) != null) return true;
        if (isRenyiboVodChannel(ch)) return true;
        // 源4（影视）/ 源6（咪咕）：直播频道，全屏也允许呼出控制栏（暂停/信息）
        return true;
    }

    /**
     * 【2026-10-11 源7 / 2026-10-13 扩展源5】全屏下唤起播放器控制栏（可拖动进度、±15s、继续/暂停）。
     * 非全屏 / 非点播频道（源7 afhgrj、源5 renyibo-vod）/ 播放器未就绪时直接返回，
     * 其它源因此完全不受影响。
     */
    private void showSource7Controller() {
        if (!fullscreen || !isVodControllerEligible()) return;
        if (vodBar == null) return;
        if (seekHint != null) seekHint.hide();
        if (channelInfoBar != null) channelInfoBar.hide();
        mainHandler.removeCallbacks(hideOttControllerRunnable);
        // 点播=追剧形态（上/下集+进度条+倍速），直播=简化形态（仅播放/清晰度/比例）
        boolean vod = isSource7Playing() || isRenyiboVodChannel(playingChannel)
                || com.tv.live.ryb.RybUrlResolver.vodIdOf(playingChannel) != null;
        vodBar.setVodMode(vod);
        vodBar.show();
        ottControllerShowing = true;
        mainHandler.postDelayed(hideOttControllerRunnable, CONTROLLER_HIDE_MS);
        LogBridge.i(TAG, "【OTT·控制栏】显示（5s 无操作自动收起，暂停时保持）");
    }

    /** 收起控制栏（同时关掉挂在它上方的弹层，避免残留） */
    private void hideSource7Controller() {
        // 【v1.6】抽屉打开期间不允许自动收起控制栏：否则 5s 到点会把抽屉一起关掉（实测抽屉自己消失）
        // 【2026-10-17】直播的「频道列表」抽屉也必须算进来 —— 之前只判 capsule/episode，
        // 导致直播里打开频道列表后 5 秒被自动收起，紧接着焦点被系统丢给控制栏「‹ 上一频道」。
        if (capsulePanel != null || episodePanel != null || channelListPanel != null) {
            LogBridge.i(TAG, "【OTT·控制栏】抽屉打开中，忽略自动收起");
            mainHandler.removeCallbacks(hideOttControllerRunnable);
            return;
        }
        ottControllerShowing = false;
        closeControlPanels();
        if (vodBar != null) vodBar.hide();
        mainHandler.removeCallbacks(hideOttControllerRunnable);
    }

    // ==================== 【2026-10-17】自绘点播控制栏 ====================

    private void initVodControlBar() {
        try {
            FrameLayout root = findViewById(R.id.ott_root);
            if (root == null) return;
            vodBar = new VodControlBar(this, new VodControlBar.Host() {
                @Override public String barTitle() { return buildBarTitle(); }
                @Override public String barChips() { return buildBarChips(); }
                @Override public long barPosition() {
                    return mPlayerManager == null ? 0 : mPlayerManager.getCurrentPosition();
                }
                @Override public long barDuration() {
                    return mPlayerManager == null ? 0 : mPlayerManager.getDuration();
                }
                @Override public long barBuffered() {
                    return mPlayerManager == null ? 0 : mPlayerManager.getBufferedPosition();
                }
                @Override public boolean barIsPlaying() {
                    return mPlayerManager != null && mPlayerManager.isPlaying();
                }
                @Override public String barSpeedLabel() {
                    float s = (mPlayerManager == null) ? 1f : mPlayerManager.getPlaybackSpeed();
                    return String.format(java.util.Locale.US, "%.1f×", s);
                }
                @Override public String barAspectLabel() { return ASPECT_LABELS[mAspectIdx]; }
                @Override public String barQualityLabel() {
                    // 【2026-10-17 按源清晰度】胶囊文字跟当前源走（源3 自动 / 源7 1080P）
                    return qualityChipLabel();
                }

                @Override public void barTogglePlay() {
                    if (mPlayerManager == null) return;
                    mPlayerManager.togglePlayWhenReady();
                    if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 150);
                }
                // 【2026-10-17 修复】直播形态下这两颗按钮文字是「‹ 上一频道 / 下一频道 ›」，
                //   但原实现无条件下发 switchVodEpisode()（只认 EPG 剧集列表），
                //   直播频道没有剧集 → 命中「暂无剧集信息」，或把 EPG 节目单当剧集播 →
                //   用户看到的就是「焦点在上一频道/下一频道上按 OK 没反应、切不了台」。
                @Override public void barPrevEpisode() { stepChannelOrEpisode(-1); }
                @Override public void barNextEpisode() { stepChannelOrEpisode(1); }
                @Override public void barSeekTo(long positionMs) {
                    if (mPlayerManager == null) return;
                    mPlayerManager.seekTo(positionMs);
                    if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 250);
                }
                @Override public void barShowSpeed() { showSpeedPanel(); }
                @Override public void barShowQuality() { showQualityPanel(); }
                @Override public void barShowLine() { showLinePanel(); }
                @Override public void barShowEpisodes() { showEpisodePanel(); }
                @Override public void barShowChannelList() { showChannelListPanel(); }
                @Override public void barShowEpg() { showEpgPanel(); }
                /** 【2026-10-18 用户口径】控制栏「设置」胶囊 → 两级「播放设置」弹窗
                 *  （一级列表 → 二级选项；不再打开全屏 SettingsDialog） */
                @Override public void barOpenSettings() { showPlaySettingsPanel(); }
                @Override public boolean barToggleFavorite() {
                    Channel ch = playingChannel;
                    if (ch == null) return false;
                    String key = favoriteKey(ch);
                    boolean added = appConfig.toggleFavorite(key);
                    Toast.makeText(OttHomeActivity.this,
                            added ? ("已收藏：" + ch.getName()) : ("已取消收藏：" + ch.getName()),
                            Toast.LENGTH_SHORT).show();
                    LogBridge.i(TAG, "【收藏】" + (added ? "加入" : "取消") + " " + ch.getName());
                    return added;
                }
                @Override public boolean barIsFavorite() {
                    Channel ch = playingChannel;
                    return ch != null && appConfig.isFavorite(favoriteKey(ch));
                }
                @Override public void barCycleAspect() { cycleAspect(); }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM;
            lp.leftMargin = dp(28);
            lp.rightMargin = dp(28);
            lp.bottomMargin = dp(20);
            root.addView(vodBar, lp);
            vodBar.setVisibility(View.GONE);
            seekHint = new SeekHintBar(this);
            seekHint.attachTo(root);
            channelInfoBar = new ChannelInfoBar(this);
            channelInfoBar.attachTo(root);
            // EPG 异步就绪后刷新信息条（首次换台时常还没解析完，否则一直显示"直播中"）
            try {
                EpgManager.getInstance(getApplicationContext())
                        .registerEpgDataReadyListener(() -> {
                            if (channelInfoBar != null
                                    && channelInfoBar.getVisibility() == View.VISIBLE
                                    && playingChannel != null) {
                                showChannelInfo(playingChannel);
                            }
                        });
            } catch (Throwable ignored) {
            }
            LogBridge.i(TAG, "【OTT·控制栏】已初始化");
        } catch (Throwable t) {
            LogBridge.w(TAG, "【OTT·控制栏】初始化失败: " + t.getMessage());
        }
    }

    /**
     * 【2026-10-17 修复 OTT 控制栏「上一频道/下一频道」按 OK 不切台】
     *
     * <p>控制栏头两颗方块按钮在两种形态下复用同一个 View：
     * 点播 = 「‹ 上一集 / 下一集 ›」，直播 = 「‹ 上一频道 / 下一频道 ›」。
     * 之前两者都直接调 {@link #switchVodEpisode(int)}，而它只按「EPG 剧集列表」翻页：
     * <ul>
     *   <li>直播频道拿不到剧集 → Toast「暂无剧集信息」，什么都不发生；</li>
     *   <li>某些直播频道的 EPG 面板有条目 → 反而把节目单当剧集去播，同样不是切台。</li>
     * </ul>
     * 两种表现都是「按了确认键切不了台」。这里按当前播放类型分派，
     * 直播走与全屏上/下键完全一致的 {@link #playRelativeChannel(int)}（当前分组列表内翻台）。
     */
    private void stepChannelOrEpisode(int delta) {
        // 【2026-10-19】源8（renyibo）是点播追剧形态，上一集/下一集走切集而不是切台
        boolean vod = isSource7Playing() || isRenyiboVodChannel(playingChannel)
                || com.tv.live.ryb.RybUrlResolver.vodIdOf(playingChannel) != null;
        if (vod) {
            switchVodEpisode(delta);
        } else {
            LogBridge.i(TAG, "【OTT·控制栏】" + (delta > 0 ? "下一频道 ›" : "‹ 上一频道")
                    + " 按确认键 → 直播切台");
            playRelativeChannel(delta);
            if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 400);
        }
        // 操作过控制栏就重置「5 秒自动收起」计时，避免刚切完台就被收掉
        mainHandler.removeCallbacks(hideOttControllerRunnable);
        if (ottControllerShowing) {
            mainHandler.postDelayed(hideOttControllerRunnable, CONTROLLER_HIDE_MS);
        }
    }

    // ==================== 【2026-10-17】控制栏弹层（胶囊单选 / 选集分页） ====================

    /** 弹层底边距 = 控制栏底部 20 + 控制栏高度（弹层与控制栏之间再留 12dp，在面板内部加） */
    private int panelBottomMargin() {
        int barH = (vodBar != null && vodBar.getVisibility() == View.VISIBLE)
                ? vodBar.getHeight() : dp(168);
        return dp(20) + barH;
    }

    /** 弹层关闭后重新开始 3s 自动收起计时（比默认 5s 短，用户刚操作完） */
    /**
     * 【v1.6】抽屉打开时，只要焦点回到控制栏，就自动关闭抽屉 ——
     * 用户按上/下/左右把焦点移回按钮，等于"我要操作控制栏"，不必再按返回键。
     * 用全局焦点监听实现，避免在每个按钮上挂 OnFocusChange。
     */
    /**
     * 抽屉焦点看门狗（2026-10-17 口径变更）。
     *
     * <p><b>旧行为</b>：焦点一旦离开抽屉就自动关闭抽屉，焦点落到控制栏。
     * <br><b>新行为（用户要求）</b>：焦点**不允许**从抽屉直接跑到控制栏，
     * 只能按返回键回控制栏。所以这里不再关闭，而是把焦点**拉回抽屉内**
     * （回到越界前的那一行；拿不到就取抽屉内第一个可聚焦控件）。
     *
     * <p>这是个兜底层：三个面板内部已各自用 nextFocus* 把四向钉死在抽屉里
     * （见 CapsuleChoicePanel / EpisodePanel / ChannelListPanel 的 clampFocusInside）。
     *
     * <p>注意 {@code panelClosing}：按返回键/选项提交时会主动收抽屉，
     * 此时焦点本来就该回控制栏，不能被这里抢回来。
     */
    private void installDrawerFocusWatcher() {
        FrameLayout root = findViewById(R.id.ott_root);
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus == null) return;
            // 主动关闭抽屉期间（返回键/选中提交）：焦点本来就该回控制栏按钮，
            // 所有兜底逻辑必须闭嘴，否则会把焦点抢回正在关闭/已关闭的抽屉。
            if (panelClosing) return;

            // ==================== 【2026-10-17 焦点诊断 + 兜底】 ====================
            // "焦点自己跳回控制栏第一个按钮（‹ 上一频道 / ‹ 上一集）"的**共同根因**：
            //   某个容器 rebuild（removeAllViews）把"当前焦点所有者"从视图树上摘掉了，
            //   Android 只能按遍历顺序把焦点交给窗口里第一个可聚焦控件 = 控制栏第一颗按钮。
            // 这里统一兜底：只要检测到「旧焦点已脱离窗口/已变不可见」，就把焦点还给频道网格，
            // 并打印旧控件的类名+文案，方便定位到底是哪个容器在重建。
            if (oldFocus != null && newFocus != null
                    && capsulePanel == null && episodePanel == null && channelListPanel == null
                    && (!oldFocus.isAttachedToWindow() || oldFocus.getVisibility() != View.VISIBLE)) {
                LogBridge.w(TAG, "【焦点兜底】旧焦点(" + describeView(oldFocus)
                        + ") 已被移除/隐藏 → 焦点掉到 " + describeView(newFocus)
                        + "，拉回频道网格");
                restoreGridFocus();
                return;
            }

            View panel = (capsulePanel != null) ? capsulePanel
                    : (episodePanel != null ? episodePanel : channelListPanel);
            if (panel == null) return;
            if (System.currentTimeMillis() - panelOpenedAtMs < 400) return;
            if (isDescendantOf(panel, newFocus)) {
                lastFocusInPanel = newFocus;   // 记住抽屉内最后一次焦点，越界时原样还回去
                return;
            }

            // 越界 → 拉回抽屉内（用 post 避免在焦点回调里同步 requestFocus 造成重入）。
            // 优先还给「抽屉内上一次聚焦的控件」：抽屉内部重建（换分组/翻页）会先删掉
            // 焦点所有者，这时焦点会瞬间落到控制栏，若无脑取 firstFocusableIn 就会落到
            // 第一个页签上（用户随后按确认键会把分组打回「全部」）—— 见 2026-10-17 实测。
            View back = (lastFocusInPanel != null
                    && isDescendantOf(panel, lastFocusInPanel)
                    && lastFocusInPanel.isAttachedToWindow())
                    ? lastFocusInPanel
                    : firstFocusableIn(panel);
            if (back == null) return;
            LogBridge.i(TAG, "【抽屉】焦点越界(" + describeView(newFocus) + ") → 拉回抽屉内 "
                    + describeView(back));
            mainHandler.post(back::requestFocus);
        });
    }

    /** 把焦点还给频道网格（焦点被重建"甩飞"时的统一兜底） */
    private void restoreGridFocus() {
        try {
            if (gvChannel == null || !gvChannel.isShown() || gvChannel.getCount() == 0) return;
            int sel = gvChannel.getSelectedItemPosition();
            if (sel < 0) sel = 0;
            gvChannel.setSelection(sel);
            if (gvChannel.requestFocus()) {
                LogBridge.i(TAG, "【焦点兜底】已把焦点还给频道网格 pos=" + sel);
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "【焦点兜底】归还失败: " + t.getMessage());
        }
    }

    /** 容器内当前焦点子控件的文案（重建后按文案找回同一个 tab）；无焦点返回 null */
    private String focusedChildText(ViewGroup container) {
        View f = getCurrentFocus();
        if (f == null || container == null) return null;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) == f) {
                return (f instanceof TextView) ? String.valueOf(((TextView) f).getText()) : null;
            }
        }
        return null;
    }

    /** 重建后把焦点还给文案相同的子控件（分组/子分组胶囊条专用） */
    private void restoreChildFocusByText(final ViewGroup container, final String text) {
        if (container == null || TextUtils.isEmpty(text)) return;
        container.post(() -> {
            try {
                for (int i = 0; i < container.getChildCount(); i++) {
                    View v = container.getChildAt(i);
                    if (v instanceof TextView
                            && text.contentEquals(String.valueOf(((TextView) v).getText()))) {
                        if (v.requestFocus()) {
                            LogBridge.i(TAG, "【焦点保持】重建后回到「" + text + "」");
                        }
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 容器内当前焦点子控件的下标；不在容器内返回 -1 */
    private int focusedChildIndex(ViewGroup container) {
        View f = getCurrentFocus();
        if (f == null || container == null) return -1;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) == f) return i;
        }
        return -1;
    }

    /** 重建后把焦点还到同下标的子控件（行列表专用） */
    private void restoreChildFocusByIndex(final ViewGroup container, final int index) {
        if (container == null || index < 0) return;
        container.post(() -> {
            try {
                if (index >= container.getChildCount()) return;
                View v = container.getChildAt(index);
                if (v != null && v.isFocusable()) {
                    if (v.requestFocus()) {
                        LogBridge.i(TAG, "【焦点保持】重建后回到第 " + index + " 行");
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 取容器内第一个可聚焦且可见的控件（抽屉兜底回位用） */
    private View firstFocusableIn(View parent) {
        if (parent == null) return null;
        if (parent.getVisibility() != View.VISIBLE) return null;
        if (parent.isFocusable()) return parent;
        if (parent instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) parent;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusableIn(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** 日志用：控件简述（类名 + 文案），便于定位焦点跑到了哪 */
    private String describeView(View v) {
        if (v == null) return "null";
        String txt = "";
        try {
            if (v instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) v).getText();
                txt = cs == null ? "" : cs.toString();
            }
        } catch (Throwable ignored) {
        }
        String cls = v.getClass().getSimpleName();
        return txt.isEmpty() ? cls : (cls + ":" + txt);
    }

    private boolean isDescendantOf(View parent, View child) {
        View p = child;
        while (p != null) {
            if (p == parent) return true;
            android.view.ViewParent vp = p.getParent();
            p = (vp instanceof View) ? (View) vp : null;
        }
        return false;
    }

    /** 抽屉关闭后把焦点还给打开它的那颗按钮（失败则回控制栏第一个可聚焦项） */
    private void restoreFocusTo(final View trigger) {
        // 【2026-10-17】第 0 毫秒：同步抢一次。抽屉移除的瞬间系统会把焦点丢给控制栏第一颗按钮
        // （‹ 上一频道 / ‹ 上一集），同步抢回来就发生在同一帧内，用户看不到跳变。
        // 下面 60ms / 180ms 两次是给"部分机型会再做一次几何焦点搜索"的兜底。
        boolean nowOk = false;
        try {
            if (trigger != null && trigger.isAttachedToWindow()) nowOk = trigger.requestFocus();
        } catch (Throwable ignored) {
        }
        LogBridge.i(TAG, "【焦点回位·即时】trigger=" + describeView(trigger) + " 成功=" + nowOk);
        // 60ms：赶在系统把焦点落到"第一个可聚焦项（上一频道）"之后、但早于任何延迟抢焦点
        mainHandler.postDelayed(() -> {
            if (vodBar == null || vodBar.getVisibility() != View.VISIBLE) return;
            boolean ok = (trigger != null) && trigger.isAttachedToWindow() && trigger.requestFocus();
            if (!ok) ok = vodBar.restoreFocusNow();
            LogBridge.i(TAG, "【焦点回位】trigger="
                    + (trigger instanceof TextView ? ((TextView) trigger).getText() : "null")
                    + " 成功=" + ok);
        }, 60);
        // 再补一次：部分机型在移除抽屉后会再做一次几何焦点搜索
        mainHandler.postDelayed(() -> {
            if (vodBar == null || vodBar.getVisibility() != View.VISIBLE) return;
            if (trigger != null && trigger.isAttachedToWindow() && !trigger.hasFocus()) {
                boolean ok = trigger.requestFocus();
                LogBridge.i(TAG, "【焦点回位·补】成功=" + ok);
            }
        }, 180);
    }

    private void scheduleControllerAutoHide() {
        mainHandler.removeCallbacks(hideOttControllerRunnable);
        if (ottControllerShowing) mainHandler.postDelayed(hideOttControllerRunnable, 3000);
    }

    private void closeControlPanels() {
        // 【2026-10-18 两级导航】整组胶囊面板（可能多层）先于字段检查关闭——
        // close → onCapsuleClosed 会逐层把栈排空（最后一层触发栈空收尾）
        while (!capsuleStack.isEmpty()) {
            capsuleStack.peek().close();
        }
        if (capsulePanel == null && episodePanel == null && channelListPanel == null) return;
        lastFocusInPanel = null;
        // 主动关闭（返回键 / 选中提交 / 换面板）：期间焦点本来就该回控制栏，
        // 先让焦点看门狗闭嘴，否则它会把焦点抢回正在关闭的抽屉 → 返回键失效。
        panelClosing = true;
        mainHandler.removeCallbacks(clearPanelClosingRunnable);
        mainHandler.postDelayed(clearPanelClosingRunnable, 600);
        if (capsulePanel != null) {
            capsulePanel.close();
            capsulePanel = null;
        }
        if (episodePanel != null) {
            episodePanel.close();
            episodePanel = null;
        }
        if (channelListPanel != null) {
            channelListPanel.close();
            channelListPanel = null;
        }
    }

    private final Runnable clearPanelClosingRunnable = () -> panelClosing = false;

    private void showCapsulePanel(String title, String subtitle,
                                  java.util.List<CapsuleChoicePanel.Item> items,
                                  CapsuleChoicePanel.OnPick onPick) {
        FrameLayout root = findViewById(R.id.ott_root);
        if (root == null) return;
        if (capsuleStack.isEmpty()) {
            // 【两级导航】只有打开第一层时才收控制栏/记录触发视图；二级叠加时保持现状
            closeControlPanels();
            mainHandler.removeCallbacks(hideOttControllerRunnable);   // 弹层期间不自动收起
            // 【焦点回位】记录"打开抽屉的那颗按钮"，栈清空时把焦点显式还给它
            capsuleTrigger = getCurrentFocus();
        }
        CapsuleChoicePanel p = new CapsuleChoicePanel(this, title, subtitle, items);
        p.onPick(onPick);
        p.onClose(() -> onCapsuleClosed(p));
        CapsuleChoicePanel prev = capsuleStack.peek();   // 上一层（可能为 null）
        capsuleStack.push(p);
        capsulePanel = p;   // 始终指向栈顶（看门狗 / 全屏 BACK 分支兼容）
        lastFocusInPanel = null;
        panelOpenedAtMs = System.currentTimeMillis();
        p.showIn(root, 340, panelBottomMargin());
        // 【2026-10-18 焦点瞬态修复】同步把焦点显式转移到新面板，再隐藏上一层——
        //   消除「焦点闪过控制栏‹上一频道 → 再进二级」的视觉跳变
        View it0 = p.initialFocusTarget();
        if (it0 != null) it0.requestFocus();
        if (prev != null && prev != p) {
            prev.setVisibility(View.GONE);
        }
        if (vodBar != null) vodBar.setAlpha(0.6f);   // 抽屉打开 → 控制栏退到 60%
    }

    /**
     * 【2026-10-18 两级导航】胶囊面板关闭联动：
     * 栈非空 → 露出上一层并还焦点；栈空 → 收尾（焦点回触发按钮、控制栏计时恢复）。
     */
    private void onCapsuleClosed(CapsuleChoicePanel p) {
        capsuleStack.remove(p);
        if (capsuleStack.isEmpty()) {
            capsulePanel = null;
            if (vodBar != null) vodBar.setAlpha(1f);
            restoreFocusTo(capsuleTrigger);
            scheduleControllerAutoHide();
        } else {
            // 【2026-10-18 透出修复】恢复上一层显示（打开本层时它被 GONE 隐藏了）再还焦点
            capsulePanel = capsuleStack.peek();
            capsulePanel.setVisibility(View.VISIBLE);
            capsulePanel.resumeFocus();
        }
    }

    /** 直播形态允许的源（用户口径：直播界面的清晰度/线路接 源1/2/3/5/6） */
    private static final int[] SRC_LIVE = {1, 2, 3, 5, 6};
    /** 点播形态允许的源（用户口径：点播界面的清晰度/线路接 源4/5/6/7/8） */
    private static final int[] SRC_VOD = {4, 5, 6, 7, 8};
    /** 每个源最近一次真实观测到的清晰度档位（切源后轨道未就绪时用它占位，避免串档） */
    private final java.util.HashMap<Integer, java.util.List<Integer>> mHeightsBySrc =
            new java.util.HashMap<>();

    /** 当前是否点播上下文（源7 影视 / 源5 点播 / 源8 4kvm），与线路面板同一判据 */
    private boolean isVodContext(Channel ch) {
        if (ch == null) return false;
        String afhVodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch);
        if (afhVodId != null) return true;
        if (!TextUtils.isEmpty(
                com.tv.live.ryb.RybUrlResolver.vodIdOf(ch))) return true;
        return !TextUtils.isEmpty(
                com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(ch.getChannelId()));
    }

    /** 清晰度口径的源号：源3（畅享）有独立阶梯，必须与源1/2 分开记 */
    private int qualitySrcOf(Channel c) {
        if (c == null) return 0;
        try {
            if (mPlayerManager != null && mPlayerManager.isChanxiangHttpChannel(c)) return 3;
        } catch (Throwable ignored) {
        }
        return sourceIndexOf(c);
    }

    /** 控制栏「清晰度」胶囊文字：只反映**当前源**的选择，不串源 */
    private String qualityChipLabel() {
        try {
            if (mPlayerManager != null) {
                if (mPlayerManager.hasChanxiangAxis()) {
                    return shortQuality(mPlayerManager.getCurrentChanxiangQualityLabel());
                }
                if (mPlayerManager.hasMiguAxis()) {
                    return shortQuality(mPlayerManager.getCurrentMiguQualityLabel());
                }
            }
        } catch (Throwable ignored) {
        }
        int src = qualitySrcOf(playingChannel);
        int h = appConfig.getQualityForSource(src);
        // 【2026-10-18】源8 的 4K 档在 AppConfig 里记作 2160，胶囊文字要显示「4K」
        if (src == 8) {
            if (h <= 0) return "自动";
            return h >= 2160 ? "4K" : (h + "P");
        }
        return (h <= 0) ? "自动" : (h + "P");
    }

    /** 清晰度展示名压缩：「1080p 超清」→「1080P」；「自适应」→「自动」 */
    private String shortQuality(String label) {
        if (label == null || label.trim().isEmpty()) return "自动";
        String s = label.trim();
        if (s.contains("自适应") || s.equals("自动")) return "自动";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})").matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) + "P";
            } catch (Throwable ignored) {
            }
        }
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    /** 清晰度名比较：`1080p 超清` ≈ `1080P` */
    private boolean sameQualityName(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equalsIgnoreCase(b)) return true;
        return shortQuality(a).equals(shortQuality(b));
    }

    /**
     * 清晰度抽屉：**按源隔离**（2026-10-17 用户口径）。
     *
     * <ul>
     *   <li>直播形态（源 1/2/3/5/6）→ 只列该直播源自己的清晰度</li>
     *   <li>点播形态（源 4/5/6/7）→ 只列该点播源自己的清晰度</li>
     *   <li>选择结果按源分别持久化（{@code vod_quality_src_<n>}），切源后自动套该源上次的值</li>
     * </ul>
     *
     * <p>两条通路，按该源「真实能力」自动分流：
     * <ol>
     *   <li><b>名档位</b>（源3 畅享 / 源6 咪咕）：服务端 {@code sourceLabels} 下发了真实清晰度阶梯
     *       （1080p超清/720p高清/480p标清），选档 = <b>换真源重播</b>，画质真变；</li>
     *   <li><b>轨道封顶</b>（源1/2/5/7 等）：按当前 HLS master 的真实视频轨做封顶，
     *       弱网仍可在封顶下自适应降档；该源本身单码率时只给「自动」，不虚报 1080/720/480。</li>
     * </ol>
     */
    private void showQualityPanel() {
        try {
            final Channel ch = playingChannel;
            // ⓪ 虎牙频道：名档位面板（蓝光4M/超清/高清…，按当前线路收敛）。
            //   档位表随虎牙解析就绪（1.7~2.2s），未就绪提示稍后再试。
            if (isHuyaChannel(ch) && mPlayerManager != null) {
                java.util.List<String> huyaNames = mPlayerManager.getAvailableResolutions();
                if (huyaNames == null || huyaNames.isEmpty()) {
                    Toast.makeText(this, "清晰度解析中，请稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
                for (String n : huyaNames) {
                    items.add(CapsuleChoicePanel.Item.capsule(n, false, true));
                }
                showCapsulePanel("清晰度", "OK 切换（当前线路内）", items, idx -> {
                    if (idx >= 0 && idx < huyaNames.size()) {
                        mPlayerManager.switchToResolution(0, huyaNames.get(idx));
                    }
                });
                return;
            }
            final int src = qualitySrcOf(ch);

            // 【2026-10-19】源8（renyibo）：清晰度走「轨道封顶」通路（与源1/2/5/7 一致）——
            //   按当前 HLS 的真实视频轨做封顶；若该集是单码率流，面板里只会出现「自动」，
            //   不虚报 1080/720 档位。
            if (src == 8 && com.tv.live.ryb.RybUrlResolver.vodIdOf(ch) != null) {
                showTrackQualityPanel(src, isVodContext(ch));
                return;
            }

            if (showNamedQualityPanel(src)) return;
            showTrackQualityPanel(src, isVodContext(ch));
        } catch (Throwable t) {
            LogBridge.w(TAG, "【OTT·清晰度】弹层失败: " + t.getMessage());
        }
    }

    /** 虎牙频道判定：huya:// 协议 id / 播放地址 */
    private boolean isHuyaChannel(Channel c) {
        if (c == null) return false;
        String id = c.getChannelId();
        String url = c.getPlayUrl();
        return (id != null && id.startsWith("huya:")) || (url != null && url.startsWith("huya://"));
    }

    /**
     * 名档位面板（源3 畅享 / 源6 咪咕）。
     *
     * @return true = 已由本方法处理（或正在等待轴构建）；false = 该源无阶梯，交回轨道模式
     */
    private boolean showNamedQualityPanel(final int src) {
        if (mPlayerManager == null) return false;

        final boolean cx;
        java.util.List<String> names;
        if (mPlayerManager.hasChanxiangAxis()) {
            cx = true;
            names = mPlayerManager.getChanxiangQualityNames();
        } else if (mPlayerManager.hasMiguAxis()) {
            cx = false;
            names = mPlayerManager.getMiguQualityNames();
        } else {
            // 源3 的轴是「面板打开时惰性构建」的：先构建（单次请求），就绪后自动打开面板
            if (mPlayerManager.isChanxiangHttpChannel(playingChannel)) {
                Toast.makeText(this, "清晰度加载中…", Toast.LENGTH_SHORT).show();
                mPlayerManager.ensureChanxiangAxisAsync(() -> mainHandler.postDelayed(() -> {
                    if (!isFinishing()) showQualityPanel();
                }, 250));
                return true;
            }
            return false;
        }
        if (names == null || names.isEmpty()) return false;

        final String curLabel = cx
                ? mPlayerManager.getCurrentChanxiangQualityLabel()
                : mPlayerManager.getCurrentMiguQualityLabel();
        final java.util.List<String> values = new java.util.ArrayList<>();
        final java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        for (String n : names) {
            items.add(CapsuleChoicePanel.Item.capsule(
                    shortQuality(n), sameQualityName(n, curLabel), true));
            values.add(n);
        }

        final String form = isVodContext(playingChannel) ? "点播" : "直播";
        final String srcName = cx ? "源3 畅享" : "源6 咪咕";
        LogBridge.i(TAG, "【OTT·清晰度】名档位 源" + src + " 形态=" + form
                + " 当前=" + curLabel + " 可选=" + names);

        showCapsulePanel("清晰度 · " + form,
                srcName + " · 共 " + items.size() + " 档 · 当前 " + shortQuality(curLabel),
                items, idx -> {
                    String want = values.get(idx);
                    if (cx) mPlayerManager.switchChanxiangQuality(want);
                    else mPlayerManager.switchMiguQuality(want);
                    // 名档位由「换真源」实现，清掉轨道封顶残留，避免双重限制
                    appConfig.setQualityForSource(src, 0);
                    mPlayerManager.setMaxVideoHeight(0);
                    Toast.makeText(this, "清晰度：" + shortQuality(want), Toast.LENGTH_SHORT).show();
                    LogBridge.i(TAG, "【OTT·清晰度】源" + src + " 切到 " + want);
                    if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 400);
                });
        return true;
    }

    /** 轨道封顶面板：按当前流的真实视频高度（源1/2/5/7 等无服务端阶梯的源） */
    private void showTrackQualityPanel(final int src, final boolean vodCtx) {
        final int[] allowed = vodCtx ? SRC_VOD : SRC_LIVE;
        final boolean srcInForm = containsInt(allowed, src);

        java.util.List<Integer> heights = (mPlayerManager == null)
                ? new java.util.ArrayList<Integer>()
                : mPlayerManager.getAvailableVideoHeights();
        if (heights == null) heights = new java.util.ArrayList<>();
        if (!heights.isEmpty()) {
            mHeightsBySrc.put(src, new java.util.ArrayList<>(heights));
        } else {
            java.util.List<Integer> cached = mHeightsBySrc.get(src);
            if (cached != null) heights = new java.util.ArrayList<>(cached);
        }

        final int cur = appConfig.getQualityForSource(src);
        final java.util.List<Integer> values = new java.util.ArrayList<>();
        final java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        items.add(CapsuleChoicePanel.Item.capsule("自动", cur <= 0, true));
        values.add(0);
        if (heights.isEmpty()) {
            LogBridge.i(TAG, "【OTT·清晰度】源" + src + " 暂无可选轨道（单码率流）→ 仅列自动");
        } else {
            for (Integer h : heights) {
                items.add(CapsuleChoicePanel.Item.capsule(h + "P", cur == h, true));
                values.add(h);
            }
        }

        final String form = vodCtx ? "点播" : "直播";
        final String curLabel = (cur <= 0) ? "自动" : (cur + "P");
        LogBridge.i(TAG, "【OTT·清晰度】轨道封顶 形态=" + form + " 源" + src
                + (srcInForm ? "" : "（该源不在本形态推荐源内）")
                + " 当前=" + curLabel + " 可选轨=" + heights);

        showCapsulePanel("清晰度 · " + form,
                "源" + src + " · 共 " + items.size() + " 档 · 当前 " + curLabel,
                items, idx -> {
                    int h = values.get(idx);
                    appConfig.setQualityForSource(src, h);
                    if (mPlayerManager != null) mPlayerManager.setMaxVideoHeight(h);
                    Toast.makeText(this, h <= 0 ? "清晰度：自动" : ("清晰度：最高 " + h + "P"),
                            Toast.LENGTH_SHORT).show();
                    LogBridge.i(TAG, "【OTT·清晰度】源" + src + " 选择 "
                            + (h <= 0 ? "自动" : h + "P"));
                    if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 200);
                });
    }

    /**
     * 线路抽屉：数据必须接真实来源，不能空开。
     * ① 源7 影视 → AfhgrjUrlResolver 的 sid 列表（含 CDN host 名）
     * ② 源5 影视 → 与「设置 · 频道线路选择」同一份 RenYiBoVodSourceLoader.getVodLines
     * ③ 其它源 → 明确提示「无多线路」，不再弹空抽屉
     */
    private void showLinePanel() {
        try {
            showLinePanelInner();
        } catch (Throwable t) {
            LogBridge.e(TAG, "【OTT·线路】打开失败: " + t);
            Toast.makeText(this, "线路面板打开失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 线路候选项：每个选项带自己的切换动作（源7 sid / 源5 线路 / 跨源同名） */
    private static class LineOption {
        final CapsuleChoicePanel.Item item;
        final Runnable action;

        LineOption(CapsuleChoicePanel.Item item, Runnable action) {
            this.item = item;
            this.action = action;
        }
    }

    /**
     * 线路抽屉：按形态接入指定源
     * <ul>
     *   <li>点播（源4/5/6/7）：源7 sid 列表 + 源5 线路表 + **跨源同名影片**</li>
     *   <li>直播（源1/2/3/5/6）：源5/源6 自带线路 + **跨源同名频道**</li>
     * </ul>
     */
    private void showLinePanelInner() {
        final Channel ch = playingChannel;
        if (ch == null) return;

        final String afhVodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch);
        final String rbVodId =
                com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(ch.getChannelId());
        final boolean vodCtx = (afhVodId != null) || !TextUtils.isEmpty(rbVodId);

        java.util.List<LineOption> opts = new java.util.ArrayList<>();

        // ⓪ 虎牙频道：子线路表（hls.multiLine，与直播设置「频道线路选择」同口径）。
        //   表随虎牙解析就绪；未就绪提示稍后再试。切换走 switchToHuyaMultiLine。
        if (isHuyaChannel(ch) && mPlayerManager != null) {
            java.util.List<String> huyaLines = mPlayerManager.getAvailableLines();
            if (huyaLines == null || huyaLines.isEmpty()) {
                Toast.makeText(this, "虎牙线路解析中，请稍后再试", Toast.LENGTH_SHORT).show();
                return;
            }
            int cur = mPlayerManager.getCurrentHuyaSourceUiIndex();
            for (int i = 0; i < huyaLines.size(); i++) {
                final int idx = i;
                opts.add(new LineOption(
                        CapsuleChoicePanel.Item.line(huyaLines.get(i),
                                shortHost(ch.getPlayUrl()), "可用", i == cur, false, true),
                        () -> {
                            LogBridge.i(TAG, "【线路】虎牙子线路切换 → 第" + (idx + 1) + "条");
                            mPlayerManager.switchToHuyaMultiLine(idx);
                        }));
            }
            presentLineOptions(opts, vodCtx);
            return;
        }

        // ① 源7：sid 线路
        if (afhVodId != null) {
            final int[] sids = com.tv.live.afhgrj.AfhgrjUrlResolver.sidsOf(afhVodId);
            final int working = com.tv.live.afhgrj.AfhgrjUrlResolver.workingSidOf(afhVodId);
            java.util.List<String> hosts = com.tv.live.afhgrj.AfhgrjUrlResolver.lineNamesOf(afhVodId);
            for (int i = 0; i < sids.length; i++) {
                final int sid = sids[i];
                boolean cur = (sid == working);
                String host = (hosts != null && i < hosts.size() && hosts.get(i) != null)
                        ? hosts.get(i) : "源7 CDN";
                opts.add(new LineOption(
                        CapsuleChoicePanel.Item.line("源7 · 线路" + sid, host,
                                cur ? "当前" : "可用", cur, false, true),
                        () -> {
                            com.tv.live.afhgrj.AfhgrjUrlResolver.rememberSid(afhVodId, sid);
                            com.tv.live.afhgrj.AfhgrjUrlResolver.invalidate(afhVodId);
                            LogBridge.i(TAG, "【线路】源7 sid=" + sid + " → 重播当前集");
                            if (playingChannel != null) playChannel(playingChannel, true);
                        }));
            }
        }

        // ①.3 【2026-10-19】源8（renyibo）：线路 = 详情页 PLAY_SOURCES 的条目
        final String rybSlug = com.tv.live.ryb.RybUrlResolver.vodIdOf(ch);
        if (!TextUtils.isEmpty(rybSlug)) {
            final java.util.List<String> rybLines =
                    com.tv.live.ryb.RybUrlResolver.lineNames(rybSlug);
            if (rybLines.isEmpty()) {
                Toast.makeText(this, "线路解析中，请稍后再试", Toast.LENGTH_SHORT).show();
                // 后台预热一次，完成后重新弹面板（与源7 同构）
                new Thread(() -> {
                    com.tv.live.ryb.RybUrlResolver.fetchEpisodes(rybSlug);
                    mainHandler.postDelayed(() -> {
                        if (!isFinishing() && playingChannel != null) showLinePanel();
                    }, 250);
                }, "Ryb-LineWarm").start();
            } else {
                final int curLine = com.tv.live.ryb.RybUrlResolver.getSelectedLine(rybSlug);
                for (int i = 0; i < rybLines.size(); i++) {
                    final int idx = i;
                    boolean sel = (i == curLine);
                    opts.add(new LineOption(
                            CapsuleChoicePanel.Item.line(rybLines.get(i), "",
                                    sel ? "当前" : "可用", sel, false, true),
                            () -> {
                                com.tv.live.ryb.RybUrlResolver.setSelectedLine(rybSlug, idx);
                                LogBridge.i(TAG, "【线路】源8 切到第" + (idx + 1) + "条 → 重播");
                                if (playingChannel != null) playChannel(playingChannel, true);
                                enterFullscreenImmediately();
                            }));
                }
            }
        }

        // ①.5 源6 咪咕（直播）：服务端下发的真实线路阶梯（P2P/央视频/咪咕/风云/CDN直链…）
        if (!vodCtx && mPlayerManager != null && mPlayerManager.hasMiguAxis()) {
            final java.util.List<String> miguLines = mPlayerManager.getMiguLineNames();
            final int miguCur = mPlayerManager.getCurrentMiguLineUiIndex();
            final String miguQ = shortQuality(mPlayerManager.getCurrentMiguQualityLabel());
            for (int i = 0; i < miguLines.size(); i++) {
                final int idx = i;
                boolean sel = (i == miguCur);
                opts.add(new LineOption(
                        CapsuleChoicePanel.Item.line("源6 · " + miguLines.get(i), miguQ,
                                sel ? "当前" : "可用", sel, false, true),
                        () -> {
                            LogBridge.i(TAG, "【线路】源6 切线路 idx=" + idx
                                    + " → " + miguLines.get(idx));
                            mPlayerManager.switchMiguLine(idx);
                        }));
            }
        }

        // ①.6 源3 畅享（直播）：同构阶梯；轴惰性构建 → 未就绪先触发构建并提示
        if (!vodCtx && mPlayerManager != null && mPlayerManager.isChanxiangHttpChannel(ch)) {
            if (!mPlayerManager.hasChanxiangAxis()) {
                mPlayerManager.ensureChanxiangAxisAsync(() -> mainHandler.postDelayed(() -> {
                    if (!isFinishing() && playingChannel != null) showLinePanel();
                }, 250));
            } else {
                final java.util.List<String> cxLines = mPlayerManager.getChanxiangLineNames();
                final int cxCur = mPlayerManager.getCurrentChanxiangLineUiIndex();
                final String cxQ = shortQuality(mPlayerManager.getCurrentChanxiangQualityLabel());
                for (int i = 0; i < cxLines.size(); i++) {
                    final int idx = i;
                    boolean sel = (i == cxCur);
                    opts.add(new LineOption(
                            CapsuleChoicePanel.Item.line("源3 · " + cxLines.get(i), cxQ,
                                    sel ? "当前" : "可用", sel, false, true),
                            () -> {
                                LogBridge.i(TAG, "【线路】源3 切线路 idx=" + idx
                                        + " → " + cxLines.get(idx));
                                mPlayerManager.switchChanxiangLine(idx);
                            }));
                }
            }
        }

        // ② 源5：线路表（与设置面板同一份）
        if (!TextUtils.isEmpty(rbVodId)) {
            java.util.List<String[]> lines =
                    com.tv.live.renyibo.RenYiBoVodSourceLoader.getVodLines(rbVodId);
            if (lines == null || lines.isEmpty()) {
                if (rbVodId.equals(lineAutoRetryVodId)) {
                    lineAutoRetryVodId = null;
                    LogBridge.w(TAG, "【线路】源5 线路表仍为空，停止重试 vodId=" + rbVodId);
                } else {
                    lineAutoRetryVodId = rbVodId;
                    Toast.makeText(this, "线路表加载中，就绪后自动打开", Toast.LENGTH_SHORT).show();
                    com.tv.live.renyibo.RenYiBoVodSourceLoader.ensureVodLinesAsync(rbVodId,
                            () -> mainHandler.postDelayed(() -> {
                                if (!isFinishing() && playingChannel != null) showLinePanel();
                            }, 300));
                }
            } else {
                lineAutoRetryVodId = null;
                final java.util.List<String[]> fl = lines;
                final int curIdx =
                        com.tv.live.renyibo.RenYiBoVodSourceLoader.getVodLineIndex(rbVodId);
                for (int i = 0; i < fl.size(); i++) {
                    final int idx = i;
                    String[] ln = fl.get(i);
                    boolean sel = (i == curIdx);
                    String seg = (ln.length > 2 && ln[2] != null && !ln[2].isEmpty())
                            ? ln[2] : "播放源";
                    opts.add(new LineOption(
                            CapsuleChoicePanel.Item.line("源5 · " + ln[0], seg,
                                    sel ? "当前" : "可用", sel, false, true),
                            () -> {
                                String url = com.tv.live.renyibo.RenYiBoVodSourceLoader
                                        .switchVodLine(rbVodId, idx);
                                LogBridge.i(TAG, "【线路】源5 idx=" + idx + " → " + url);
                                if (url != null && playingChannel != null && mPlayerManager != null) {
                                    mPlayerManager.playUrl(url, playingChannel.getName(), playingChannel);
                                }
                            }));
                }
            }
        }

        // ③ 同源内同名多线路：**必须是当前源自己的条目**（用户口径：切到哪个源就只显示该源的线路）
        final int mySrc = sourceIndexOf(ch);
        final int[] allowed = new int[]{mySrc};
        java.util.LinkedHashMap<String, Channel> pool = new java.util.LinkedHashMap<>();
        for (Channel c : allChannels) {
            if (c != null) pool.put(String.valueOf(c.getChannelId()) + "|" + c.getName(), c);
        }
        for (Channel c : shownChannels) {
            if (c != null) pool.put(String.valueOf(c.getChannelId()) + "|" + c.getName(), c);
        }
        for (final Channel c : pool.values()) {
            if (c == ch || sameChannel(c, ch)) continue;
            if (!sameChannelName(c.getName(), ch.getName())) continue;
            int src = sourceIndexOf(c);
            if (!containsInt(allowed, src)) continue;
            opts.add(new LineOption(
                    CapsuleChoicePanel.Item.line("源" + src + " · " + (vodCtx ? "同名影片" : "同名频道"),
                            shortHost(c.getPlayUrl()), "可用", false, false, true),
                    () -> {
                        LogBridge.i(TAG, "【线路】跨源切到 源" + src + " " + c.getName());
                        playChannel(c);
                    }));
        }

        if (opts.isEmpty()) {
            Toast.makeText(this, vodCtx ? "该片暂无可切换线路" : "该频道暂无可切换线路",
                    Toast.LENGTH_SHORT).show();
            LogBridge.w(TAG, "【线路】无候选 vodCtx=" + vodCtx + " ch=" + ch.getName());
            return;
        }
        presentLineOptions(opts, vodCtx);
    }

    /** 线路候选统一呈现（虎牙分支与常规分支共用） */
    private void presentLineOptions(java.util.List<LineOption> opts, boolean vodCtx) {
        final Channel ch = playingChannel;
        final int mySrc = sourceIndexOf(ch);
        final java.util.List<LineOption> finalOpts = opts;
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        for (LineOption o : finalOpts) items.add(o.item);
        LogBridge.i(TAG, "【线路】源" + mySrc + " 候选 " + items.size() + " 条，形态="
                + (vodCtx ? "点播" : "直播"));
        showCapsulePanel((vodCtx ? "播放线路 · 点播" : "播放线路 · 直播"),
                "源" + mySrc + " · 共 " + items.size() + " 条", items,
                idx -> finalOpts.get(idx).action.run());
    }

    /** 频道所属源序号（1/2/3 归为普通直播源，返回 1） */
    private int sourceIndexOf(Channel c) {
        if (c == null) return 0;
        String id = c.getChannelId() == null ? "" : c.getChannelId();
        String url = c.getPlayUrl() == null ? "" : c.getPlayUrl();
        String group = c.getGroup() == null ? "" : c.getGroup();
        // 【2026-10-19】源8（renyibo）必须放在其它判定之前：其频道 id 是 ryb:<slug>、
        //   地址是 ryb://<slug>，不判定会一路落到最后的 return 1（控制栏显示「源1」）。
        if (id.startsWith("ryb:") || url.startsWith("ryb://")) return 8;
        if (id.startsWith("afhgrj:") || url.startsWith("afhgrj://")) return 7;
        if (id.startsWith("renyibo-vod:") || id.startsWith("renyibo:")
                || url.startsWith("renyibo-vod://") || url.startsWith("renyibo://")) return 5;
        if (id.startsWith("nowtv:") || id.startsWith("miguhd") || id.startsWith("tvnow")
                || url.startsWith("miguhd://") || url.startsWith("tvnow://")
                || url.startsWith("nowtv://")) return 6;
        if (id.startsWith("zgyj") || url.startsWith("zgyj") || group.contains("影视")) return 4;
        return 1;
    }

    private boolean containsInt(int[] arr, int v) {
        for (int a : arr) if (a == v) return true;
        return false;
    }

    private boolean sameChannel(Channel a, Channel b) {
        if (a == null || b == null) return false;
        String ia = a.getChannelId(), ib = b.getChannelId();
        if (ia != null && ib != null && ia.equals(ib)) return true;
        return sameChannelName(a.getName(), b.getName());
    }

    /** 频道名归一化比较：去空格、去「高清/HD/超清/频道」等后缀 */
    private boolean sameChannelName(String a, String b) {
        if (a == null || b == null) return false;
        String x = normalizeName(a), y = normalizeName(b);
        return !x.isEmpty() && x.equals(y);
    }

    private String normalizeName(String s) {
        String r = s.replaceAll("\\s+", "")
                .replace("高清", "").replace("超清", "").replace("蓝光", "")
                .replace("HD", "").replace("hd", "").replace("频道", "");
        return r.trim();
    }

    private String shortHost(String url) {
        if (url == null) return "";
        try {
            java.net.URI u = java.net.URI.create(url);
            String h = u.getHost();
            return h == null ? "已解析" : h;
        } catch (Throwable t) {
            return "已解析";
        }
    }

    /** 倍速：同一套抽屉（0.5× ~ 2.0×） */
    private void showSpeedPanel() {
        final float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        float cur = (mPlayerManager == null) ? 1f : mPlayerManager.getPlaybackSpeed();
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        int curIdx = 2;
        for (int i = 0; i < speeds.length; i++) {
            boolean sel = Math.abs(cur - speeds[i]) < 0.01f;
            if (sel) curIdx = i;
            items.add(CapsuleChoicePanel.Item.capsule(
                    String.format(java.util.Locale.US, "%.2f×", speeds[i]), sel, true));
        }
        showCapsulePanel("播放速度",
                "当前：" + String.format(java.util.Locale.US, "%.2f×", speeds[curIdx]),
                items, idx -> {
                    if (mPlayerManager != null) mPlayerManager.setPlaybackSpeed(speeds[idx]);
                    Toast.makeText(this, String.format(java.util.Locale.US, "倍速 %.2f×", speeds[idx]),
                            Toast.LENGTH_SHORT).show();
                    if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 200);
                });
    }

    /**
     * 【2026-10-18 用户口径】控制栏「设置」→ 一级「播放设置」弹窗（与倍速/清晰度弹窗同款
     * CapsuleChoicePanel），四项各带当前值；选中某项 → 打开对应的二级弹窗（清晰度/倍速复用
     * 现有面板；比例/解码为新增小面板）。**去重口径**：清晰度/线路已由控制栏按钮直达，
     * 全屏设置页不再显示这两行。
     */
    private void showPlaySettingsPanel() {
        FrameLayout root = findViewById(R.id.ott_root);
        if (root == null) return;
        closeControlPanels();
        mainHandler.removeCallbacks(hideOttControllerRunnable);
        capsuleTrigger = getCurrentFocus();

        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        // 【2026-10-18 用户口径】设置只显示 3 项：解码器选择/渲染方式/画面比例（开机自启在「菜单」弹窗）
        //（倍速/清晰度/线路控制栏已有，按去重规则不再出现）
        items.add(CapsuleChoicePanel.Item.capsule(
                "解码器选择 · " + decoderModeLabel(), false, true));
        items.add(CapsuleChoicePanel.Item.capsule(
                "渲染方式 · " + rendererModeLabel(), false, true));
        items.add(CapsuleChoicePanel.Item.capsule(
                "画面比例 · " + (appConfig != null ? appConfig.getScreenRatio() : "全屏"), false, true));

        playRootItems = items;
        CapsuleChoicePanel p = new CapsuleChoicePanel(this, "设置", "OK 进入对应设置", items);
        p.keepOpenAfterPick(true);   // 【两级导航】OK 进二级（叠加显示），BACK 逐层返回
        p.onPick(idx -> {
            if (isFinishing()) return;
            if (idx == 0) showDecoderPanel();
            else if (idx == 1) showRendererPanel();
            else showRatioPanel();
        });
        p.onClose(() -> { onCapsuleClosed(p); playRootPanel = null; playRootItems = null; });
        playRootPanel = p;
        capsuleStack.push(p);
        capsulePanel = p;
        lastFocusInPanel = null;
        panelOpenedAtMs = System.currentTimeMillis();
        p.showIn(root, 340, panelBottomMargin());
        // 【2026-10-18 焦点瞬态修复】同步聚焦（showIn 内部原为 150ms 异步，窗口内焦点会闪到控制栏）
        View fIt = p.initialFocusTarget();
        if (fIt != null) fIt.requestFocus();
        if (vodBar != null) vodBar.setAlpha(0.6f);   // 抽屉打开 → 控制栏退到 60%
    }

    /** 解码方式当前值（SP "decoder_mode"，与 SettingsDialog 同键同文件） */
    private String decoderModeLabel() {
        String v = getSharedPreferences("app_settings", MODE_PRIVATE)
                .getString("decoder_mode", "auto");
        if ("hard".equals(v)) return "硬解";
        if ("soft".equals(v)) return "软解";
        return "自动";
    }

    /** 【2026-10-18】开机自启当前值（SP "boot_auto_start"，与 SettingsDialog/BootStartManager 同键） */
    private boolean isBootAutoStartOn() {
        return getSharedPreferences("app_settings", MODE_PRIVATE)
                .getBoolean("boot_auto_start", false);
    }

    /** 【2026-10-18】渲染方式当前值（SP "renderer_type"，与 SettingsDialog 同键） */
    private String rendererModeLabel() {
        return "texture".equals(getSharedPreferences("app_settings", MODE_PRIVATE)
                .getString("renderer_type", "surface")) ? "TextureView" : "SurfaceView";
    }

    /** 二级：开机自启（开启/关闭），写 SP "boot_auto_start"（BootReceiver/MyApplication 读同键） */
    private void showBootPanel() {
        boolean cur = isBootAutoStartOn();
        String[] names = {"开启", "关闭"};
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        int curIdx = cur ? 0 : 1;
        for (int i = 0; i < names.length; i++) {
            items.add(CapsuleChoicePanel.Item.capsule(names[i], i == curIdx, true));
        }
        showCapsulePanel("开机自启", "当前：" + (cur ? "已开启" : "已关闭"), items, idx -> {
            boolean on = (idx == 0);
            getSharedPreferences("app_settings", MODE_PRIVATE)
                    .edit().putBoolean("boot_auto_start", on).apply();
            Toast.makeText(this, "开机自启已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
            updateRootItemLabel(true, 0, "开机自启 · " + (on ? "已开启" : "已关闭"));
        });
    }

    /** 二级：渲染方式（SurfaceView/TextureView），写 SP 后广播 RENDERER_TYPE_CHANGED（播放器重建） */
    private void showRendererPanel() {
        android.content.SharedPreferences sp =
                getSharedPreferences("app_settings", MODE_PRIVATE);
        String[] names = {"SurfaceView（默认）", "TextureView（兼容）"};
        String[] values = {"surface", "texture"};
        String cur = sp.getString("renderer_type", "surface");
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        int curIdx = 0;
        for (int i = 0; i < values.length; i++) {
            boolean sel = values[i].equals(cur);
            if (sel) curIdx = i;
            items.add(CapsuleChoicePanel.Item.capsule(names[i], sel, true));
        }
        showCapsulePanel("渲染方式", "切换后重新加载", items, idx -> {
            sp.edit().putString("renderer_type", values[idx]).apply();

            Intent intent = new Intent("com.tv.live.RENDERER_TYPE_CHANGED");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Toast.makeText(this, "已切换到" + names[idx] + "，正在应用…", Toast.LENGTH_SHORT).show();
            updateRootItemLabel(false, 1, "渲染方式 · " + names[idx]);
        });
    }

    /** 二级：画面比例（全屏/填充/原始），行内选择即时生效（直接切 ScaleMode） */
    private void showRatioPanel() {
        String[] names = {"全屏", "填充", "原始"};
        TVPlayerManager.ScaleMode[] modes = {
                TVPlayerManager.ScaleMode.FILL, TVPlayerManager.ScaleMode.ZOOM, TVPlayerManager.ScaleMode.FIT};
        String cur = (appConfig != null) ? appConfig.getScreenRatio() : "全屏";
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        int curIdx = 0;
        for (int i = 0; i < names.length; i++) {
            boolean sel = names[i].equals(cur);
            if (sel) curIdx = i;
            items.add(CapsuleChoicePanel.Item.capsule(names[i], sel, true));
        }
        showCapsulePanel("画面比例", "当前：" + names[curIdx], items, idx -> {
            if (appConfig != null) appConfig.setScreenRatio(names[idx]);
            if (mPlayerManager != null) mPlayerManager.setScaleMode(modes[idx]);
            Toast.makeText(this, "屏幕比例：" + names[idx], Toast.LENGTH_SHORT).show();
            updateRootItemLabel(false, 2, "画面比例 · " + names[idx]);
            if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 200);
        });
    }

    /** 二级：解码方式（自动/硬解/软解），写 SP 后广播 DECODER_MODE_CHANGED（播放器重载） */
    private void showDecoderPanel() {
        android.content.SharedPreferences sp =
                getSharedPreferences("app_settings", MODE_PRIVATE);
        String[] names = {"自动", "硬解", "软解"};
        String[] values = {"auto", "hard", "soft"};
        String cur = sp.getString("decoder_mode", "auto");
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        int curIdx = 0;
        for (int i = 0; i < values.length; i++) {
            boolean sel = values[i].equals(cur);
            if (sel) curIdx = i;
            items.add(CapsuleChoicePanel.Item.capsule(names[i], sel, true));
        }
        showCapsulePanel("解码方式", "切换后重新加载", items, idx -> {
            sp.edit().putString("decoder_mode", values[idx]).apply();
            Intent intent = new Intent("com.tv.live.DECODER_MODE_CHANGED");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Toast.makeText(this, "已切换到" + names[idx] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
            updateRootItemLabel(false, 0, "解码器选择 · " + names[idx]);
        });
    }

    /**
     * 【2026-10-19 OTT 菜单设置 v2】顶栏「菜单」按钮 → 两级弹窗（与控制栏「设置」弹窗同交互），
     * 内容 4 项：开机自启/直播源切换/节目单切换/版本信息。
     * 三项全部走 OTT 新版胶囊面板（二级），**不再复用直播模式的旧订阅/版本窗口**
     * （旧口径的「直播源订阅/节目单订阅」入口已移除；增删源仍在直播模式的订阅窗口里做）。
     */
    private void showMenuSettingsPanel() {
        FrameLayout root = findViewById(R.id.ott_root);
        if (root == null) return;
        closeControlPanels();
        mainHandler.removeCallbacks(hideOttControllerRunnable);
        capsuleTrigger = getCurrentFocus();

        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        items.add(CapsuleChoicePanel.Item.capsule(
                "开机自启 · " + (isBootAutoStartOn() ? "已开启" : "已关闭"), false, true));
        // 【2026-10-19 用户口径】切换项只显示标题，不带当前值（带值会撑出胶囊被截断）
        items.add(CapsuleChoicePanel.Item.capsule("直播源切换", false, true));
        items.add(CapsuleChoicePanel.Item.capsule("节目单切换", false, true));
        items.add(CapsuleChoicePanel.Item.capsule("版本信息", false, true));

        menuRootItems = items;
        CapsuleChoicePanel p = new CapsuleChoicePanel(this, "OTT 设置", "OK 进入对应设置", items);
        p.keepOpenAfterPick(true);   // 【两级导航】OK 进二级（叠加显示），BACK 逐层返回
        p.onPick(idx -> {
            if (isFinishing()) return;
            if (idx == 0) showBootPanel();
            else if (idx == 1) showSourceSwitchPanel("live_history", "直播源切换");
            else if (idx == 2) showSourceSwitchPanel("epg_history", "节目单切换");
            else showVersionPanel();
        });
        p.onClose(() -> { onCapsuleClosed(p); menuRootPanel = null; menuRootItems = null; });
        menuRootPanel = p;
        capsuleStack.push(p);
        capsulePanel = p;
        lastFocusInPanel = null;
        panelOpenedAtMs = System.currentTimeMillis();
        p.showIn(root, 340, panelBottomMargin());
        // 【2026-10-18 焦点瞬态修复】同步聚焦（showIn 内部原为 150ms 异步）
        View mIt = p.initialFocusTarget();
        if (mIt != null) mIt.requestFocus();
    }

    /**
     * 【2026-10-18】二级弹窗切换选项后回写一级面板显示：
     * menu=true 刷新「菜单」一级（开机自启行），否则刷新控制栏「设置」一级（解码/渲染/比例行）。
     */
    private void updateRootItemLabel(boolean menu, int index, String label) {
        CapsuleChoicePanel panel = menu ? menuRootPanel : playRootPanel;
        java.util.List<CapsuleChoicePanel.Item> items = menu ? menuRootItems : playRootItems;
        if (panel == null || items == null || index < 0 || index >= items.size()) return;
        items.get(index).label = label;
        panel.refreshItem(index);
    }

    /**
     * 【2026-10-19 OTT 直播源/节目单切换】二级胶囊面板：列出该 SP 下的全部源（当前源打勾 + 「当前」），
     * OK 即设为默认并广播刷新。OTT 侧**只做切换**，不提供增删/二维码（那些仍在直播模式的订阅窗口里）。
     * 与直播模式的旧订阅窗口在实现上完全独立（旧窗口是 AlertDialog + ListView + EditText）。
     */
    private void showSourceSwitchPanel(String spKey, String title) {
        final SourceManager mgr = new SourceManager(this, spKey);
        final java.util.List<SourceManager.SourceItem> list = mgr.getAllSources();
        if (list.isEmpty()) {
            Toast.makeText(this, title + "：暂无可切换的源", Toast.LENGTH_SHORT).show();
            return;
        }
        final String curUrl = mgr.getDefaultUrl();
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        for (SourceManager.SourceItem si : list) {
            boolean sel = si.url != null && si.url.equals(curUrl);
            items.add(CapsuleChoicePanel.Item.line(
                    si.name, shortSourceUrl(si.url), sel ? "当前" : "切换", sel, false, true));
        }
        showCapsulePanel(title, "OK 切换并刷新", items, idx -> {
            if (idx < 0 || idx >= list.size()) return;
            SourceManager latest = new SourceManager(this, spKey);
            int realPos = latest.indexOfUrl(list.get(idx).url);
            if (realPos < 0) realPos = idx;
            latest.setDefault(realPos);
            SourceManager.SourceItem picked = latest.get(realPos);
            String name = picked != null ? picked.name : list.get(idx).name;
            applySourceSwitch(spKey, list.get(idx).url);
            LogBridge.i(TAG, "【OTT·切源】" + title + " → " + name + "（" + list.get(idx).url + "）");
            Toast.makeText(this, "已切换到：" + name, Toast.LENGTH_SHORT).show();
            // 一级菜单只显示标题（不带当前值），故切完无需回写
        });
    }

    /**
     * 【2026-10-19 OTT 切源落地】清掉「网页推送」遗留的自定义地址，写 URL 并广播
     * REFRESH_LIVE_AND_EPG（与直播模式切源、旧订阅窗口同口径，OTT 侧 AppCoreManager 会重载源与 EPG）。
     */
    private void applySourceSwitch(String spKey, String url) {
        if ("epg_history".equals(spKey)) {
            getSharedPreferences("app_settings", MODE_PRIVATE).edit().remove("custom_epg_url").apply();
            UrlConfig.EPG_URL = url;
        } else {
            boolean changed = !TextUtils.equals(UrlConfig.LIVE_URL, url);
            getSharedPreferences("app_settings", MODE_PRIVATE).edit().remove("custom_live_url").apply();
            if (changed) {
                // 【2026-10-19 播放记录BUG】历史条目只存频道名（源7/源8 的影片名），换源后
                // 在新源里既不存在也点不开 —— 换源即清空，避免"切换源后仍显示上一个源的播放记录"。
                if (appConfig != null) appConfig.clearOttHistory();
                renderHistoryPanel();
                renderMineHistoryPanel();
                LogBridge.i(TAG, "【OTT·切源】直播源已变化 → 清空播放记录（旧源条目在新源不可播）");
            }
            UrlConfig.LIVE_URL = url;
            // 【2026-10-19】换源后重新允许「无自家直播频道 → 自动落点播」的自动调整
            userPickedBoard = false;
        }
        Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /** 切换行副标题：只显示源地址的 host（过长则截断），便于区分同名源 */
    private String shortSourceUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            String host = java.net.URI.create(url).getHost();
            if (!TextUtils.isEmpty(host)) return host;
        } catch (Exception ignored) {
        }
        return url.length() > 40 ? url.substring(0, 40) + "…" : url;
    }

    /**
     * 【2026-10-19 OTT 版本信息】二级胶囊面板：三行只读信息 + 「返回」行。
     * 沿用 OTT 新版面板样式（旧版是直播模式 SettingsDialog 的版本对话框 + OttSettingsSubDialogs.showVersion）。
     */
    private void showVersionPanel() {
        String uaMode = getSharedPreferences("app_settings", MODE_PRIVATE)
                .getString("user_agent_mode", "exo");
        String uaName = "exo".equals(uaMode) ? "ExoPlayer默认" : "VLC";
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        items.add(CapsuleChoicePanel.Item.line("版本号",
                "TVLive v" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）",
                null, false, false, false));
        items.add(CapsuleChoicePanel.Item.line("播放内核", "EXO / IJK", null, false, false, false));
        items.add(CapsuleChoicePanel.Item.line("UA 模式", uaName, null, false, false, false));
        items.add(CapsuleChoicePanel.Item.capsule("返回", false, true));
        showCapsulePanel("版本信息", "返回键关闭", items, idx -> {
            // 「返回」行：pick 即关（本层未设 keepOpenAfterPick，收起后回到「菜单」一级）
        });
    }

    /**
     * 选集抽屉：数据接 EPG 集数（EpgManager）。
     * EPG 未就绪时不再空开/干等，而是注册一次性就绪回调，数据到了自动打开（8s 超时兜底）。
     */
    private void showEpisodePanel() {
        final Channel ch = playingChannel;
        if (ch == null) return;
        java.util.List<Channel.EpgItem> items = epgOf(ch);
        if (items == null || items.isEmpty()) {
            Toast.makeText(this, "剧集加载中，就绪后自动打开", Toast.LENGTH_SHORT).show();
            final EpgManager mgr = EpgManager.getInstance(getApplicationContext());
            final EpgManager.OnEpgDataReadyListener[] holder = new EpgManager.OnEpgDataReadyListener[1];
            holder[0] = () -> {
                mgr.unregisterEpgDataReadyListener(holder[0]);
                mainHandler.post(() -> {
                    if (!isFinishing()) openEpisodeDrawer(ch);
                });
            };
            mgr.registerEpgDataReadyListener(holder[0]);
            mainHandler.postDelayed(() -> mgr.unregisterEpgDataReadyListener(holder[0]), 8000);
            LogBridge.i(TAG, "【OTT·选集】EPG 未就绪 → 已注册就绪回调，等待自动打开");
            return;
        }
        openEpisodeDrawer(ch);
    }

    /**
     * 【当前集索引】源7 与源5 各自维护一个下标：
     *   源7 → mCurrentAfhgrjEpisodeIdx；源5(renyibo-vod) → mCurrentRenyiboEpisodeIdx。
     * 选集抽屉/标题必须走这里统一取值，否则源5 永远读到 -1 → 每次打开都回到第 1 集（用户实测）。
     */
    /** 收藏用 key：优先 channelId，退化到频道名 */
    private String favoriteKey(Channel ch) {
        if (ch == null) return "";
        String id = ch.getChannelId();
        return (id != null && !id.isEmpty()) ? id : String.valueOf(ch.getName());
    }

    private int currentEpisodeIndex() {
        Channel ch = playingChannel;
        if (ch != null && !TextUtils.isEmpty(
                com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(ch.getChannelId()))) {
            return mCurrentRenyiboEpisodeIdx;
        }
        // 【2026-10-19 源8 renyibo】以解析器记录的真实集下标为准（起播/切集都会写入），
        //   避免依赖 EPG 点击路径才更新的 mCurrentAfhgrjEpisodeIdx。
        String rybSlug = ch == null ? null : com.tv.live.ryb.RybUrlResolver.vodIdOf(ch);
        if (!TextUtils.isEmpty(rybSlug)) {
            int ep = com.tv.live.ryb.RybUrlResolver.getCurrentEpisode(rybSlug);
            return ep < 0 ? 0 : ep;
        }
        return mCurrentAfhgrjEpisodeIdx;
    }

    private java.util.List<Channel.EpgItem> epgOf(Channel ch) {
        try {
            return EpgManager.getInstance(getApplicationContext()).getEpg(ch);
        } catch (Throwable t) {
            LogBridge.w(TAG, "【OTT·选集】取 EPG 失败: " + t.getMessage());
            return null;
        }
    }

    private void openEpisodeDrawer(Channel ch) {
        java.util.List<Channel.EpgItem> items = epgOf(ch);
        if (items == null || items.isEmpty()) {
            Toast.makeText(this, "暂无可选剧集", Toast.LENGTH_SHORT).show();
            return;
        }
        FrameLayout root = findViewById(R.id.ott_root);
        if (root == null) return;
        closeControlPanels();
        mainHandler.removeCallbacks(hideOttControllerRunnable);
        final java.util.List<Channel.EpgItem> list = items;
        final View trigger = getCurrentFocus();
        int base = currentEpisodeIndex();
        if (base < 0) base = 0;
        final int cur = Math.max(0, Math.min(base, list.size() - 1));
        LogBridge.i(TAG, "【OTT·选集】打开抽屉：当前集下标=" + cur + " / " + list.size());
        EpisodePanel p = new EpisodePanel(this, root, list.size(), cur, idx -> idx < cur);
        p.onPick(idx -> {
            LogBridge.i(TAG, "【OTT·选集】跳到第" + (idx + 1) + "集 / 共" + list.size() + "集");
            playAfhgrjEpisodeOtt(list.get(idx));
        });
        p.onClose(() -> {
            episodePanel = null;
            if (vodBar != null) vodBar.setAlpha(1f);
            restoreFocusTo(trigger);
            scheduleControllerAutoHide();
        });
        episodePanel = p;
        lastFocusInPanel = null;
        panelOpenedAtMs = System.currentTimeMillis();
        p.show(panelBottomMargin());
        if (vodBar != null) vodBar.setAlpha(0.6f);
    }

    // ==================== 【直播】频道列表 / 节目单 / 切台信息条 ====================

    /** 频道列表抽屉：只列直播频道（排除源5/源7 的影视点播） */
    private void showChannelListPanel() {
        FrameLayout root = findViewById(R.id.ott_root);
        if (root == null) return;
        // 【重要】OTT 模式下 allChannels 可能为空，必须并上 shownChannels（当前页数据），
        // 再按 channelId 去重；否则会误判"暂无直播频道"（实测点按钮无反应）。
        java.util.LinkedHashMap<String, Channel> uniq = new java.util.LinkedHashMap<>();
        java.util.List<Channel> source = new java.util.ArrayList<>(allChannels);
        source.addAll(shownChannels);
        // 【2026-10-17】把虎牙频道也纳进来：它们不在 allChannels 里（单独池），
        // 导致抽屉的分组 Tab 缺少「虎牙频道」这一项（用户实测"还有分组没有显示"）。
        source.addAll(huyaChannels);
        source.addAll(huyaLine2Channels);
        for (Channel c : source) {
            if (c == null) continue;
            if (!TextUtils.isEmpty(
                    com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(c.getChannelId()))) continue;
            if (com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(c) != null) continue;
            // 【2026-10-17 修复「源1/源2 频道列表里没有分组频道」】
            // ⚠ 源1/源2 的 channelId 是**空串**（不是 null）。原写法 `getChannelId() != null`
            //   判断为真 → key 全是 ""，125 台全部被折叠成 1 条（实测日志「全部 共 1 台」）。
            // 现在：空 id 用「名称+地址」兜底；并把源号并入 key，避免不同源同 id 互相吃掉。
            final String cid = c.getChannelId();
            final String src = "S" + sourceIndexOf(c);
            final String key = !TextUtils.isEmpty(cid)
                    ? (src + "#" + cid)
                    : (src + "#" + c.getName() + "|" + (c.getPlayUrl() == null ? "" : c.getPlayUrl()));
            if (!uniq.containsKey(key)) uniq.put(key, c);
        }
        java.util.List<Channel> pool = new java.util.ArrayList<>(uniq.values());
        LogBridge.i(TAG, "【直播·频道列表】候选=" + source.size() + " 去重后=" + pool.size());
        if (pool.isEmpty()) {
            Toast.makeText(this, "暂无直播频道", Toast.LENGTH_SHORT).show();
            return;
        }
        closeControlPanels();
        mainHandler.removeCallbacks(hideOttControllerRunnable);
        final View trigger = getCurrentFocus();
        ChannelListPanel p = new ChannelListPanel(this, root, pool, playingChannel, ch -> {
            java.util.List<Channel.EpgItem> list = epgOf(ch);
            if (list == null) return null;
            for (Channel.EpgItem it : list) {
                if (it.isPlaying) return it.title;
            }
            return null;
        });
        p.onFavoriteLookup(ch -> appConfig.isFavorite(favoriteKey(ch)));
        p.onPick(ch -> {
            LogBridge.i(TAG, "【直播·频道列表】切台 → " + ch.getName());
            playChannel(ch);
        });
        p.onClose(() -> {
            channelListPanel = null;
            if (vodBar != null) vodBar.setAlpha(1f);
            restoreFocusTo(trigger);
            scheduleControllerAutoHide();
        });
        channelListPanel = p;
        lastFocusInPanel = null;
        panelOpenedAtMs = System.currentTimeMillis();
        p.show();
        if (vodBar != null) vodBar.setAlpha(0.6f);
    }

    /** 节目单抽屉：复用胶囊抽屉（两行信息行 = 节目名 + 时间 + 正在播） */
    private void showEpgPanel() {
        Channel ch = playingChannel;
        if (ch == null) return;
        java.util.List<Channel.EpgItem> list = epgOf(ch);
        if (list == null || list.isEmpty()) {
            Toast.makeText(this, "节目单加载中，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<CapsuleChoicePanel.Item> items = new java.util.ArrayList<>();
        final int curEpg = currentEpgIndex(list);
        for (int i = 0; i < list.size(); i++) {
            Channel.EpgItem it = list.get(i);
            String time = (it.time == null) ? "" : it.time;
            boolean isCur = (i == curEpg);
            // 【2026-10-17】把「能不能回看」显式标出来，用户一眼知道哪些能点
            String status;
            if (isCur) status = "正在播";
            else if (i < curEpg && canCatchUp(ch, it)) status = "回看";
            else status = "";
            items.add(new CapsuleChoicePanel.Item(it.title, time, status, isCur, false, true));
        }
        LogBridge.i(TAG, "【直播·节目单】打开 " + ch.getName() + " 共" + list.size() + " 档");
        showCapsulePanel("节目单 · " + ch.getName(), "共 " + list.size() + " 档", items, idx -> {
            Channel.EpgItem it = list.get(idx);
            // ⚠ 用「下标是否当前档」判断正在播，而不是 it.isPlaying：
            //   EPG 里 isPlaying 可能有多档为 true（不同解析路径标记不一致），
            //   之前用它判断会把已播完的档误判成"正在直播"→ 静默 return，回看根本进不去。
            boolean playing = (curEpg >= 0) ? (idx == curEpg) : it.isPlaying;
            LogBridge.i(TAG, "【直播·节目单】点击 idx=" + idx + " " + it.title
                    + " playing=" + playing
                    + " custom=" + (!TextUtils.isEmpty(it.customPlayUrl))
                    + " miguPid=" + (!TextUtils.isEmpty(it.miguProgramId))
                    + " time=" + it.time);
            if (playing) {
                Toast.makeText(this, "该节目正在直播", Toast.LENGTH_SHORT).show();
                return;
            }
            // ① 自带回看直链（源5/源7 集数项、部分源的回看地址）
            if (!TextUtils.isEmpty(it.customPlayUrl)) {
                LogBridge.i(TAG, "【直播·节目单】回看直链播放：" + it.title + " → " + it.customPlayUrl);
                if (mPlayerManager != null) mPlayerManager.playUrl(it.customPlayUrl, ch.getName(), ch);
                return;
            }
            // ② 咪咕轮播节目：单次请求换取回看地址（守规矩：串行、不重试）
            if (!TextUtils.isEmpty(it.miguProgramId)) {
                Toast.makeText(this, "正在获取回看：" + it.title, Toast.LENGTH_SHORT).show();
                final String pid = it.miguProgramId;
                com.tv.live.util.AppExecutors.io(() -> {
                    String url = null;
                    try {
                        com.tv.live.migu.source.CarouselApi.Resolution r =
                                com.tv.live.migu.source.CarouselApi.getCarouselVideo(pid, "回看·" + it.title);
                        if (r != null && !TextUtils.isEmpty(r.url)) {
                            url = com.tv.live.migu.source.CarouselApi.pickResolvableCandidate(r.candidates);
                            if (TextUtils.isEmpty(url)) url = r.url;
                        }
                    } catch (Throwable t) {
                        LogBridge.w(TAG, "【直播·节目单】取回看失败: " + t.getMessage());
                    }
                    final String playUrl = url;
                    mainHandler.post(() -> {
                        if (TextUtils.isEmpty(playUrl)) {
                            Toast.makeText(this, "该节目暂无回看源", Toast.LENGTH_SHORT).show();
                        } else if (mPlayerManager != null) {
                            mPlayerManager.playUrl(playUrl, ch.getName(), ch);
                        }
                    });
                });
                return;
            }
            // ③ 普通 IPTV 源（源1/源2 等）：走**直播模式同一套**时移回看线路
            //    （EpgManager.buildTimeshiftUrl：PLTV→TVOD + playseek=起-止）
            String liveUrl = ch.getPlayUrl();
            String catchUrl = com.tv.live.EpgManager.buildTimeshiftUrl(liveUrl, list, idx);
            if (TextUtils.isEmpty(catchUrl)) {
                Toast.makeText(this, "该节目暂无回看源", Toast.LENGTH_SHORT).show();
                LogBridge.w(TAG, "【直播·节目单】无回看线路 url=" + liveUrl
                        + " 时移支持=" + com.tv.live.EpgManager.isTimeshiftCapable(liveUrl));
                return;
            }
            LogBridge.i(TAG, "【直播·节目单】时移回看：" + it.title + " → " + catchUrl);
            Toast.makeText(this, "回看：" + it.title, Toast.LENGTH_SHORT).show();
            if (mPlayerManager != null) mPlayerManager.playUrl(catchUrl, ch.getName() + " 回看", ch);
        });
    }

    /** 节目能否回看（用于列表右侧状态标签） */
    private boolean canCatchUp(Channel ch, Channel.EpgItem it) {
        if (it == null) return false;
        if (!TextUtils.isEmpty(it.customPlayUrl)) return true;
        if (!TextUtils.isEmpty(it.miguProgramId)) return true;
        return ch != null && com.tv.live.EpgManager.isTimeshiftCapable(ch.getPlayUrl());
    }

    // 时移回看地址的拼装已统一收口到 EpgManager.buildTimeshiftUrl（与直播模式共用一套规则），
    // 这里不再保留副本，避免两边规则漂移。

    /** 当前正在播的节目下标：优先 isPlaying，否则按时间段覆盖当前时刻判断；找不到返回 -1 */
    private int currentEpgIndex(java.util.List<Channel.EpgItem> list) {
        if (list == null || list.isEmpty()) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isPlaying) return i;
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        for (int i = 0; i < list.size(); i++) {
            int[] r = parseRange(list.get(i).time);
            if (r == null) continue;
            int s0 = r[0], e0 = r[1];
            if (nowMin >= s0 && nowMin < e0) return i;
            if (e0 < s0 && (nowMin >= s0 || nowMin < e0)) return i;   // 跨零点
        }
        return -1;
    }

    /** "20:00-20:30" → [startMin, endMin]；解析失败返回 null */
    private int[] parseRange(String timeRange) {
        if (timeRange == null) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,2}):(\\d{2})\\s*[-~—]\\s*(\\d{1,2}):(\\d{2})")
                    .matcher(timeRange);
            if (!m.find()) return null;
            return new int[]{
                    Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4))};
        } catch (Throwable t) {
            return null;
        }
    }

    /** "20:00-20:30" → 当前时刻在该节目内的进度 0~1；解析失败返回 -1 */
    private float programProgress(String timeRange) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,2}):(\\d{2})\\s*[-~—]\\s*(\\d{1,2}):(\\d{2})")
                    .matcher(timeRange);
            if (!m.find()) return -1f;
            int start = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            int end = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
            if (end <= start) end += 24 * 60;
            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            if (nowMin < start) nowMin += 24 * 60;
            float p = (nowMin - start) / (float) (end - start);
            return Math.max(0f, Math.min(1f, p));
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** 切台信息条：频道名 + 正在播 + 下一档，3 秒自动消失（不弹控制栏） */
    private void showChannelInfo(Channel ch) {
        LogBridge.i(TAG, "INFO-ENTER ch=" + (ch == null ? "null" : ch.getName())
                + " barNull=" + (channelInfoBar == null));
        if (ch == null || channelInfoBar == null) {
            LogBridge.w(TAG, "【切台信息条】跳过：ch=" + (ch == null) + " bar=" + (channelInfoBar == null));
            return;
        }
        // 【2026-10-17 用户口径】控制栏可见期间不弹信息条（详见 switchHintsSuppressed 注释）。
        // 这里再兜一层：EPG 就绪回调等旁路调用同样不会把信息条顶出来。
        if (switchHintsSuppressed()) {
            channelInfoBar.hide();
            LogBridge.i(TAG, "【切台信息条】控制栏可见 → 抑制显示：" + ch.getName());
            return;
        }
        LogBridge.i(TAG, "【切台信息条】准备显示 " + ch.getName());
        // 影视点播不走直播信息条
        // ⚠ vodIdOfChannelId 对非源5 频道返回 ""（不是 null），必须用 isEmpty 判断，
        //   原写法 != null 会把所有直播频道都误判成源5 点播 → 信息条永远不显示（已踩坑）。
        if (!TextUtils.isEmpty(
                com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(ch.getChannelId()))) {
            LogBridge.i(TAG, "【切台信息条】源5 点播，跳过");
            return;
        }
        if (com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch) != null) {
            LogBridge.i(TAG, "【切台信息条】源7 影视，跳过");
            return;
        }
        String now = null, next = null;
        java.util.List<Channel.EpgItem> list = epgOf(ch);
        if (list != null && !list.isEmpty()) {
            // 【关键】XMLTV 节目单不一定有 isPlaying 标记，必须按「时间段是否覆盖当前时刻」判断，
            //   否则永远取不到当前节目（用户实测：节目单里有数据，信息条却一直"直播中"）。
            int curIdx = currentEpgIndex(list);
            if (curIdx >= 0) {
                Channel.EpgItem it = list.get(curIdx);
                now = it.title + (it.time == null ? "" : "  " + it.time);
                if (curIdx + 1 < list.size()) {
                    Channel.EpgItem nx = list.get(curIdx + 1);
                    next = nx.title + (nx.time == null ? "" : "  " + nx.time);
                }
            }
        }
        String title = ch.getName();
        if (ch.getGroup() != null && !ch.getGroup().isEmpty()) title += " · " + ch.getGroup();
        int bottom = dp(48);
        if (ottControllerShowing && vodBar != null && vodBar.getVisibility() == View.VISIBLE) {
            bottom = dp(20) + vodBar.getHeight() + dp(16);
        }
        // 频道号：按当前频道在列表里的位置（01 起），找不到就不显示
        String number = null;
        int idx = -1;
        for (int i = 0; i < shownChannels.size(); i++) {
            Channel c = shownChannels.get(i);
            if (c != null && c.getName() != null && c.getName().equals(ch.getName())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) number = String.format(java.util.Locale.US, "%02d", idx + 1);

        // 当前节目进度：从「20:00-20:30」这类时间段算，拿不到就不画
        float pct = -1f;
        if (list != null && !list.isEmpty()) {
            int curIdx = currentEpgIndex(list);
            if (curIdx >= 0 && list.get(curIdx).time != null) {
                pct = programProgress(list.get(curIdx).time);
            }
        }
        channelInfoBar.show(number, ch.getName() + (ch.getGroup() == null || ch.getGroup().isEmpty()
                ? "" : " · " + ch.getGroup()), now, next, pct, bottom);
    }

    private String buildBarTitle() {
        String name = (playingChannel == null) ? "未在播放" : playingChannel.getName();
        final int curEp = currentEpisodeIndex();
        if (curEp < 0 || playingChannel == null) return name;
        int total = 0;
        try {
            java.util.List<Channel.EpgItem> items =
                    EpgManager.getInstance(getApplicationContext()).getEpg(playingChannel);
            if (items != null) total = items.size();
        } catch (Throwable ignored) {
        }
        return total > 0
                ? name + " · 第" + (curEp + 1) + "集 / 共" + total + "集"
                : name + " · 第" + (curEp + 1) + "集";
    }

    private String buildBarChips() {
        // 【2026-10-17 按源清晰度】顶部信息必须反映**当前源自己的**选择，不能串源
        final int srcNow = sourceIndexOf(playingChannel);
        // 与「清晰度」胶囊同一取值口径：名档位（源3/源6）取服务端当前档，其它源取封顶值
        String q = "自动";
        try {
            q = qualityChipLabel();
        } catch (Throwable ignored) {
        }
        int sid = -1;
        try {
            String vodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(playingChannel);
            if (vodId != null) sid = com.tv.live.afhgrj.AfhgrjUrlResolver.workingSidOf(vodId);
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        if (srcNow > 0) sb.append("源").append(srcNow).append(" · ");
        sb.append(q);
        if (sid > 0) sb.append(" · 线路").append(sid);
        // 直播没有倍速概念，不显示 1.0×（点播才显示）
        boolean vodCtx = isSource7Playing() || isRenyiboVodChannel(playingChannel);
        if (vodCtx && mPlayerManager != null) {
            sb.append(String.format(java.util.Locale.US, " · %.1f×", mPlayerManager.getPlaybackSpeed()));
        }
        return sb.toString();
    }

    /**
     * 【2026-10-17】点播全屏上/下键 = 上一集/下一集（原实现是切频道，会误跳片）。
     * 直播频道仍保持切台语义。
     *
     * <p>【2026-10-18 接入选集】当前集下标改用 {@link #currentEpisodeIndex()}（源7 → 
     * mCurrentAfhgrjEpisodeIdx、源5 → mCurrentRenyiboEpisodeIdx），与选集抽屉
     * {@link #openEpisodeDrawer} 完全同源。原来这里硬读 mCurrentAfhgrjEpisodeIdx：
     * 源5 续播/联播/选集点击更新的都是 mCurrentRenyiboEpisodeIdx，控制栏读不到 →
     * 表现为「源5 按下一集原地打转/跳错集」（用户实测）。
     */
    private void switchVodEpisode(int delta) {
        Channel ch = playingChannel;
        if (ch == null) return;
        java.util.List<Channel.EpgItem> items = epgOf(ch);
        if (items == null || items.isEmpty()) {
            Toast.makeText(this, "暂无剧集信息", Toast.LENGTH_SHORT).show();
            LogBridge.w(TAG, "【OTT·切集】无剧集列表 channel=" + ch.getName());
            return;
        }
        int cur = currentEpisodeIndex();
        if (cur < 0) cur = 0;
        int next = cur + delta;
        if (next < 0) {
            Toast.makeText(this, "已经是第一集了", Toast.LENGTH_SHORT).show();
            return;
        }
        if (next >= items.size()) {
            Toast.makeText(this, "已经是最后一集了", Toast.LENGTH_SHORT).show();
            return;
        }
        LogBridge.i(TAG, "【OTT·切集】" + (delta > 0 ? "下一集" : "上一集")
                + " → 第" + (next + 1) + "集 / 共" + items.size() + "集（当前下标=" + cur + "）");
        playAfhgrjEpisodeOtt(items.get(next));
        if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 400);
    }

    /** 倍速选择（0.5× ~ 2.0×，与主流 TV 端一致） */
    private void showSpeedDialog() {
        final float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        String[] labels = new String[speeds.length];
        float cur = (mPlayerManager == null) ? 1f : mPlayerManager.getPlaybackSpeed();
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = (Math.abs(cur - speeds[i]) < 0.01f ? "✓ " : "")
                    + String.format(java.util.Locale.US, "%.2f×", speeds[i]);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setItems(labels, (d, which) -> {
                    if (mPlayerManager != null) mPlayerManager.setPlaybackSpeed(speeds[which]);
                    if (vodBar != null) mainHandler.postDelayed(vodBar::refreshAll, 200);
                    Toast.makeText(this, String.format(java.util.Locale.US, "倍速 %.2f×", speeds[which]),
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** 线路切换（源7 多 CDN）：与详情页同一套 rememberSid + invalidate + 重播 */
    private void showLineDialog() {
        final String vodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(playingChannel);
        if (vodId == null) {
            Toast.makeText(this, "当前频道无多线路信息", Toast.LENGTH_SHORT).show();
            return;
        }
        final int[] sids = com.tv.live.afhgrj.AfhgrjUrlResolver.sidsOf(vodId);
        if (sids.length == 0) {
            Toast.makeText(this, "线路信息未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        int working = com.tv.live.afhgrj.AfhgrjUrlResolver.workingSidOf(vodId);
        String[] labels = new String[sids.length];
        for (int i = 0; i < sids.length; i++) {
            labels[i] = (sids[i] == working ? "✓ " : "") + "线路" + sids[i];
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("播放线路")
                .setItems(labels, (d, which) -> {
                    int sid = sids[which];
                    com.tv.live.afhgrj.AfhgrjUrlResolver.rememberSid(vodId, sid);
                    com.tv.live.afhgrj.AfhgrjUrlResolver.invalidate(vodId);
                    LogBridge.i(TAG, "【OTT·线路】手选 sid=" + sid + " → 重播当前集");
                    if (playingChannel != null) playChannel(playingChannel, true);
                })
                .show();
    }

    /** 画面比例循环：适应 → 填充 → 拉伸 */
    private void cycleAspect() {
        mAspectIdx = (mAspectIdx + 1) % 3;
        try {
            TVPlayerManager.ScaleMode mode;
            switch (mAspectIdx) {
                case 0: mode = TVPlayerManager.ScaleMode.FIT; break;
                case 2: mode = TVPlayerManager.ScaleMode.ZOOM; break;
                default: mode = TVPlayerManager.ScaleMode.FILL; break;
            }
            if (mPlayerManager != null) mPlayerManager.setScaleMode(mode);
            LogBridge.i(TAG, "【OTT·画面比例】切换为 " + ASPECT_LABELS[mAspectIdx]);
            Toast.makeText(this, "画面比例：" + ASPECT_LABELS[mAspectIdx], Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            LogBridge.w(TAG, "切换画面比例失败: " + t.getMessage());
        }
    }

    /**
     * 【2026-10-11 源7 自动联播】判定已下沉到 {@code TVPlayerManager#handleAfhgrjEpisodeEnded()}
     * （STATE_ENDED 发生在播放器内部，且原本会走 autoRetry 重播本集）。
     *
     * <p>这里只保留界面同步，通过专用的 {@link TVPlayerManager.OnAfhgrjEpisodeListener} 实现：
     * 换集 → 刷 EPG 高亮 + 播放历史；全部播完 → 退出全屏并收起控制栏（见 initPlayer）。
     * 之所以不再用 OnPlayStateListener 的 onPlayEnd：单例播放器上该槽位会被
     * MainActivity.onDestroy 清空（实测 OTT 侧 onPlayEnd 一次都没触发）。
     */

    /** 【2026-10-11 源7】只读缓存取某一集（不联网）；未缓存 / 越界返回 null */
    private Channel.EpgItem cachedAfhgrjEpisode(Channel ch, int idx) {
        if (ch == null || idx < 0) return null;
        try {
            List<Channel.EpgItem> items =
                    EpgManager.getInstance(getApplicationContext()).getEpg(ch);
            if (items != null && idx < items.size()) return items.get(idx);
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================== 【2026-10-11】OTT 播放历史列 ====================

    /**
     * 渲染「历史记录」列（与集数列表并排）：最多 10 条，最新在前。
     * 条目文案 {@code 频道名 · 第N集}（源7）/ {@code 频道名}（其它源）；点击 → 续播该频道该集。
     */
    private void renderHistoryPanel() {
        if (llOttHistoryRows == null) return;
        // 【2026-10-17】先记焦点行，重建后还回去（否则焦点掉到控制栏「‹ 上一频道」）
        final int keepRow = focusedChildIndex(llOttHistoryRows);
        llOttHistoryRows.removeAllViews();
        List<AppConfig.OttHistory> list = appConfig.getOttHistory();
        if (list.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.ott_history_empty);
            tv.setTextSize(14);
            tv.setTextColor(0x66FFFFFF);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            llOttHistoryRows.addView(tv);
            rewireOttFocus();
            capEpgListHeight();
            return;
        }
        int showN = Math.min(list.size(), OTT_HISTORY_MAX);
        for (int i = 0; i < showN; i++) {
            AppConfig.OttHistory h = list.get(i);
            if (h != null) llOttHistoryRows.addView(buildHistoryRow(h));
        }
        rewireOttFocus();
        capEpgListHeight();
        restoreChildFocusByIndex(llOttHistoryRows, keepRow);
    }

    /** 单条历史：整行可点击 / 可获焦；焦点态用程序化 StateListDrawable，不新增资源 */
    private View buildHistoryRow(AppConfig.OttHistory h) {
        TextView row = new TextView(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        row.setText(h.displayText());
        row.setTextSize(16);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setTextColor(0xCCFFFFFF);
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);
        row.setClickable(true);
        row.setId(View.generateViewId());
        row.setBackground(focusHighlightBg());
        row.setOnClickListener(v -> playHistoryEntry(h, v));
        // 【2026-10-14】历史记录焦点行自动滚动到可见区域
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // 历史记录焦点行暂不做特殊处理
            }
        });
        return row;
    }

    /** 历史条目点击：切到该频道，并按记录集数续播（源7）；当前源里找不到该频道则提示后忽略 */
    private void playHistoryEntry(AppConfig.OttHistory h, View clickedRow) {
        if (h == null) return;
        Channel target = null;
        for (Channel c : allChannels) {
            if (c != null && h.name.equals(c.getName())) {
                target = c;
                break;
            }
        }
        if (target == null) {
            Toast.makeText(this, "当前源里没有「" + h.name + "」", Toast.LENGTH_SHORT).show();
            return;
        }
        // 把「要续播的集数 + 已看进度」写回历史，playChannel 会据此直接起播那一集。
        // 【2026-10-17 修复】① 以前只对源7 写，源5 点历史回不来集数；② 以前不带 position，
        //   新建记录把进度清零 → 点一次历史条目就等于"从头再看"。
        String vodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(target);
        String writeId = !TextUtils.isEmpty(h.vodId) ? h.vodId : (vodId != null ? vodId : "");
        appConfig.addOttHistory(h.name, writeId, Math.max(1, h.episodeOrdinal),
                h.positionMs, h.durationMs);
        LogBridge.i(TAG, "【OTT·历史】点击历史 → " + h.name
                + (h.episodeOrdinal > 0 ? " 第" + h.episodeOrdinal + "集" : ""));
        // 【2026-10-14】静默切台：不刷新集数列表/历史记录面板，保持焦点和数据不丢失
        playChannel(target, true);
        // 【2026-10-14】记录历史记录行，退出全屏后恢复焦点
        fromHistoryFullscreen = true;
        lastHistoryFocusRow = clickedRow;
        // 【2026-10-14】点击后保持焦点在历史记录行上，避免跳到频道网格/全部分组
        if (clickedRow != null) {
            clickedRow.postDelayed(clickedRow::requestFocus, 200);
        }
    }

    /** 行焦点高亮背景（程序化，不新增 drawable 资源） */
    private android.graphics.drawable.Drawable focusHighlightBg() {
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},
                new android.graphics.drawable.ColorDrawable(0xFF1E7BE0));
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(0xFF1E7BE0));
        bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return bg;
    }

    /** 【2026-10-11 源7】把遥控焦点落到指定集数行（切集后保持焦点停在列表内） */
    private void focusAfhgrjEpgRow(int idx) {
        if (llEpgList == null || idx < 0 || idx >= llEpgList.getChildCount()) return;
        final View row = llEpgList.getChildAt(idx);
        if (row != null) row.post(row::requestFocus);
    }

    /**
     * 集数行：整行可点击 / 可获焦（遥控）；当前集蓝色加粗 + 「▸ 播放中」。
     *
     * <p>【焦点态】用程序化 {@link android.graphics.drawable.StateListDrawable} 提供焦点高亮，
     * 不新增资源文件，也不改动其它面板（仅源7 集数行走此路径）。
     */
    private View addAfhgrjEpgRow(String title, Channel.EpgItem item, boolean isCurrent) {
        if (llEpgList == null) return null;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);
        row.setClickable(true);
        // 【2026-10-11 源7 遥控焦点】分配稳定 id 并挂上左右方向键链接；
        // 上边界（首行→预览）、下边界（末行→网格）由 refreshAfhgrjEpgPanel 统一接线。
        row.setId(View.generateViewId());
        row.setNextFocusLeftId(R.id.fl_ott_preview);
        row.setNextFocusRightId(R.id.gv_ott_channel);

        row.setBackground(focusHighlightBg());
        // 【2026-10-14 焦点记忆】记录集数列表中最后聚焦的行，供从右侧回来时恢复；
        //   当前播放集所在行创建时同步记忆（列表重建后默认回到正在播放的集）。
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                lastFocusedEpgRowId = v.getId();
                // 【2026-10-14】焦点行固定在可视区域中间：计算目标滚动位置让行中心
                //   对齐 ScrollView 中心，并做上下边界钳制，避免焦点滚出可视范围。
                if (svEpgList != null && llEpgList != null) {
                    final View fv = v;
                    svEpgList.post(() -> {
                        int rowTop = fv.getTop();
                        int rowBottom = fv.getBottom();
                        int rowH = fv.getHeight();
                        int svHeight = svEpgList.getHeight();
                        int curScroll = svEpgList.getScrollY();
                        int maxScrollY = Math.max(0, llEpgList.getHeight() - svHeight);
                        int targetScrollY;
                        // 优先确保焦点行完全可见；在中间区域时保持居中
                        if (rowTop < curScroll) {
                            targetScrollY = rowTop;
                        } else if (rowBottom > curScroll + svHeight) {
                            targetScrollY = rowBottom - svHeight;
                        } else {
                            targetScrollY = rowTop + rowH / 2 - svHeight / 2;
                        }
                        targetScrollY = Math.max(0, Math.min(targetScrollY, maxScrollY));
                        svEpgList.scrollTo(0, targetScrollY);
                    });
                }
            }
        });
        if (isCurrent) lastFocusedEpgRowId = row.getId();

        TextView tvTitle = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(titleLp);
        tvTitle.setText(isCurrent ? (title + "  ▸ 播放中") : title);
        tvTitle.setTextSize(17);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (isCurrent) {
            tvTitle.setTextColor(0xFF40A9FF);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            tvTitle.setTextColor(0xFFFFFFFF);
        }
        row.addView(tvTitle);

        row.setOnClickListener(v -> playAfhgrjEpisodeOtt(item));
        llEpgList.addView(row);
        return row;
    }

    /**
     * 【2026-10-11 源7】OTT 模式切集：与直播模式 {@code EpgManager.playAfhgrjEpisode}
     * 同口径 —— 直接把集数项携带的 {@code afhgrj://<vodId>-<sid>-<nid>} 交给播放器，
     * 起播那一刻再由 {@link TVPlayerManager} 抓播放页取真实 m3u8（惰性解析）。
     */
    private void playAfhgrjEpisodeOtt(Channel.EpgItem item) {
        Channel ch = playingChannel;
        if (item == null || TextUtils.isEmpty(item.customPlayUrl) || ch == null) return;
        // 【2026-10-19 源8】ryb（renyibo）选集项：与源7/源5 同一路径，但「当前集下标」存在
        //   RybUrlResolver（起播解析时才写）。这里点选集时**同步置位**，否则面板高亮与
        //   「正在播放 · 第N集」会停在上一集（用户点第 5 集却显示第 1 集）。
        String rybSlug = com.tv.live.ryb.RybUrlResolver.vodIdOf(ch);
        if (!TextUtils.isEmpty(rybSlug)) {
            int idx = indexOfAfhgrjEpisode(item);
            if (idx >= 0) {
                com.tv.live.ryb.RybUrlResolver.setCurrentEpisode(rybSlug, idx);
                appConfig.addOttHistory(ch.getName(), rybSlug, idx + 1);
                renderHistoryPanel();
            }
            LogBridge.i(TAG, "【OTT·源8·自定义】切集播放：" + item.title + " → " + item.customPlayUrl);
            mPlayerManager.playUrl(item.customPlayUrl, ch.getName(), ch);
            refreshEpgPanel(ch);
            refreshNowPlayingProgram();
            focusAfhgrjEpgRow(idx);
            fromEpgFullscreen = true;
            lastEpgFocusIndex = idx;
            setFullscreen(true);
            Toast.makeText(this, "播放：" + (TextUtils.isEmpty(item.title) ? "该集" : item.title),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // 【2026-10-13 源5】renyibo-vod 选集项走同一切集路径，但下标/历史记到源5 名下，
        //   与源7 各自独立（自动联播回调同样只认各自的槽位）。
        String rnVodId = com.tv.live.renyibo.RenYiBoVodSourceLoader.vodIdOfChannelId(ch.getChannelId());
        if (!TextUtils.isEmpty(rnVodId)) {
            mCurrentRenyiboEpisodeIdx = indexOfAfhgrjEpisode(item);
            if (mCurrentRenyiboEpisodeIdx >= 0) {
                appConfig.addOttHistory(ch.getName(), rnVodId, mCurrentRenyiboEpisodeIdx + 1);
                renderHistoryPanel();
            }
            LogBridge.i(TAG, "【OTT·源5·自定义】切集播放：" + item.title + " → " + item.customPlayUrl);
            mPlayerManager.playUrl(item.customPlayUrl, ch.getName(), ch);
            refreshEpgPanel(ch);
            refreshNowPlayingProgram();
            focusAfhgrjEpgRow(mCurrentRenyiboEpisodeIdx);
            // 【2026-10-14】记录集数进入全屏标记，退出后恢复焦点到集数列
            fromEpgFullscreen = true;
            lastEpgFocusIndex = mCurrentRenyiboEpisodeIdx;
            // 【2026-10-14】点击集数播放后立刻进入全屏
            setFullscreen(true);
            Toast.makeText(this, "播放：" + (TextUtils.isEmpty(item.title) ? "该集" : item.title),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        mCurrentAfhgrjEpisodeIdx = indexOfAfhgrjEpisode(item);
        // 【2026-10-11 播放历史 / 续播】把「看到第几集」记进历史：手动切集、自动联播、
        //   以及历史点播都汇到这一处，重启后即可据此续播。
        String vodId = com.tv.live.afhgrj.AfhgrjUrlResolver.vodIdOf(ch);
        if (vodId != null && mCurrentAfhgrjEpisodeIdx >= 0) {
            appConfig.addOttHistory(ch.getName(), vodId, mCurrentAfhgrjEpisodeIdx + 1);
            renderHistoryPanel();
        }
        LogBridge.i(TAG, "【OTT·EPG·自定义】切集播放：" + item.title + " → " + item.customPlayUrl);
        mPlayerManager.playUrl(item.customPlayUrl, ch.getName(), ch);
        refreshEpgPanel(ch);
        refreshNowPlayingProgram();
        // 【2026-10-11 源7 遥控焦点】列表整表重建后，把焦点落回当前集所在行，
        //   避免遥控确认一次就被弹回频道网格。
        focusAfhgrjEpgRow(mCurrentAfhgrjEpisodeIdx);
        // 【2026-10-14】记录集数进入全屏标记，退出后恢复焦点到集数列
        fromEpgFullscreen = true;
        lastEpgFocusIndex = mCurrentAfhgrjEpisodeIdx;
        // 【2026-10-14】点击集数播放后立刻进入全屏
        setFullscreen(true);
        Toast.makeText(this, "播放：" + (TextUtils.isEmpty(item.title) ? "该集" : item.title),
                Toast.LENGTH_SHORT).show();
    }

    /** 在当前集数列表中定位某集下标（按 customPlayUrl 全等匹配）；找不到返回 -1 */
    private int indexOfAfhgrjEpisode(Channel.EpgItem target) {
        if (target == null || playingChannel == null) return -1;
        try {
            EpgManager epg = EpgManager.getInstance(getApplicationContext());
            List<Channel.EpgItem> items = epg.getEpg(playingChannel);
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    Channel.EpgItem it = items.get(i);
                    if (it != null && TextUtils.equals(it.customPlayUrl, target.customPlayUrl)) {
                        return i;
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
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
        updateTopAdMask();
        // 【2026-10-17 界面重构 v2】「我的」板块没有播放画面，不参与全屏
        if (on && topSection == SECTION_MINE) {
            LogBridge.i(TAG, "【OTT·全屏】当前在「我的」板块 → 忽略进入全屏");
            return;
        }
        // 【2026-10-18 诊断】进入全屏时记录调用方，便于排查「谁把它拉进/没拉进全屏」
        if (on && !fullscreen) {
            String caller = "?";
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                if (e.getClassName().contains("OttHomeActivity")
                        && !"setFullscreen".equals(e.getMethodName())) {
                    caller = e.getMethodName() + ":" + e.getLineNumber();
                    break;
                }
            }
            LogBridge.i(TAG, "【全屏】进入全屏 调用方=" + caller);
        }
        fullscreen = on;
        // 【2026-10-11 源7】全屏状态一变化就收起控制栏并复位 useController=false，
        //   避免控制栏残留在小窗口预览上（源1~源6 始终没有控制栏）。
        hideSource7Controller();
        // 【2026-09-29 退全屏焦点空窗】整段过渡期间屏蔽 Tab 的「焦点即切换」，
        // 直到下方延迟一帧恢复焦点、清掉标志为止（见 if (!on) 分支）
        suppressTabFocusSwitch = true;
        topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        // 【2026-10-17 界面重构 v2】全屏时二级分组条一并收起（原来 topBar 里的分组 Tab 随之隐藏）
        applyTopSectionVisibility();
        // 【2026-10-17 v2】退出全屏后顶栏/分组条回来，左栏可用高度变了 → 重新收口列表高度
        if (!on) mainHandler.postDelayed(this::capEpgListHeight, 180);
        rightPanel.setVisibility(on ? View.GONE : View.VISIBLE);
        tvNowChannel.setVisibility(on ? View.GONE : View.VISIBLE);
        // 【2026-10-14】正在播放行+播放记录标题整行隐藏，消除底部黑边
        View ottNowRow = findViewById(R.id.ll_ott_now_row);
        if (ottNowRow != null) ottNowRow.setVisibility(on ? View.GONE : View.VISIBLE);
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

        // 【2026-10-18】btnFullscreen 已改为「直播」模式切换按钮（文案恒定），不再随全屏状态切换

        // 退出全屏后把焦点还给右侧网格，遥控可继续选台；否则焦点会落回顶栏 Tab
        if (!on && detailReopenOnExit != null) {
            // 【2026-10-18】本次全屏是从详情页起播的 → 退全屏回到该片详情页（不是首页网格），
            //   与银河奇异果 / 云视听极光一致：详情页 = 这部片的“主页”，返回先回它。
            final Channel back = detailReopenOnExit;
            detailReopenOnExit = null;
            // 这一轮不恢复网格/历史/EPG 焦点：详情页自己会抢；这些残留标记一并清掉，
            //   否则后续某次退全屏会拿旧下标去 restore，跳到莫名其妙的位置。
            fromHistoryFullscreen = false;
            lastHistoryFocusRow = null;
            fromEpgFullscreen = false;
            lastEpgFocusIndex = -1;
            mainHandler.postDelayed(() -> suppressTabFocusSwitch = false, 260);
            // 【2026-10-18 无闪现】当场、无淡入地把详情页盖上来：
            //   原来延后 80ms + 160ms 淡入，中间会露出首页一帧，用户看到的就是「先跳回 OTT 界面」。
            openVodDetail(back, detailSourcePosition, true);
            LogBridge.i(TAG, "【详情页】退全屏 → 回到详情页：" + back.getName()
                    + " 来源 position=" + detailSourcePosition);
            // 【2026-10-18 焦点修复】详情页路径也必须恢复 flPreview 可获焦！
            //   进全屏时 setFocusable(false)（见上方 on 分支），正常退全屏由 else 分支的
            //   第二跳恢复；但本分支此前不经过第二跳 → flPreview 永久不可获焦，
            //   EPG/历史行的 nextFocusUp=fl_ott_preview 全部失效，几何兜底把「上键」
            //   交给网格海报（v2 六列布局末排海报底边距集数行仅 ~20px）→ 焦点跳网格。
            //   实测路径：详情页起播 → 退全屏回详情 → 关详情 → 网格 ← 集数行 → 上键必现。
            //   详情页此刻已盖在首页之上，延后 260ms（与 suppressTabFocusSwitch 复位同拍）
            //   恢复即可，无「空窗期焦点借预览卡」的蓝框风险。
            final View fp = flPreview;
            if (fp != null) {
                mainHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) fp.setFocusable(true);
                }, 260);
            }
        } else if (!on) {
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
                // 【2026-10-14】从历史记录/集数进入全屏时，退出后焦点恢复到对应列
                boolean restoreToHistory = fromHistoryFullscreen && lastHistoryFocusRow != null;
                boolean restoreToEpg = fromEpgFullscreen && lastEpgFocusIndex >= 0;
                if (restoreToHistory) {
                    lastHistoryFocusRow.requestFocus();
                } else if (restoreToEpg) {
                    focusAfhgrjEpgRow(lastEpgFocusIndex);
                } else if (toStrip) {
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
                    if (restoreToHistory) {
                        lastHistoryFocusRow.requestFocus();
                    } else if (restoreToEpg) {
                        focusAfhgrjEpgRow(lastEpgFocusIndex);
                    } else if (toStrip) {
                        focusSubGroupChip();
                    } else if (gvChannel != null) {
                        gvChannel.requestFocus();
                    }
                    // 【2026-10-14】恢复后清除标记
                    fromHistoryFullscreen = false;
                    lastHistoryFocusRow = null;
                    fromEpgFullscreen = false;
                    lastEpgFocusIndex = -1;
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
        // 【2026-10-17 界面重构 v2】只有直播板块沿用「5s 无操作自动全屏」：
        //   · 点播板块是**浏览海报选片**的场景，自动全屏会把用户刚打开的墙直接打断；
        //   · 我的板块没有画面。
        if (topSection != SECTION_LIVE) {
            LogBridge.i(TAG, "当前板块=" + sectionName(topSection) + " → 不启动自动全屏计时");
            return;
        }
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

    // ============================ 启动图（2026-10-18） ============================

    /** 【2026-10-18 启动图】建立 App 内启动覆盖层（仅冷启动展示；三路信号淡出，见字段注释） */
    private void setupSplashOverlay(Bundle savedInstanceState) {
        splashOverlay = findViewById(R.id.ott_splash_overlay);
        if (splashOverlay == null) return;
        // 就绪信号若早于本方法到达（理论上不可能：播放在 applyChannels 之后才起）→ 不再展示
        if (splashDismissed) {
            splashOverlay.setVisibility(View.GONE);
            return;
        }
        // ① Activity 重建（进程死亡恢复等）：跳过启动图，避免打断用户
        if (savedInstanceState != null) {
            splashOverlay.setVisibility(View.GONE);
            splashDismissed = true;
            return;
        }
        final Intent intent = getIntent();
        // ② 调试直达（am start --es debug_*）：自动化流程不该被启动图拖住
        if (intent != null && (intent.hasExtra("debug_vod_id")
                || intent.hasExtra("debug_play_name") || intent.hasExtra("debug_panel"))) {
            splashOverlay.setVisibility(View.GONE);
            splashDismissed = true;
            LogBridge.i(TAG, "【启动图】调试直达 → 跳过启动图");
            return;
        }
        // ③ 直播模式热切换过来：共享播放器还在播，不会再回调 onPlayReady
        if (intent != null && intent.getBooleanExtra(EXTRA_WARM_START, false)) {
            splashOverlay.setVisibility(View.GONE);
            splashDismissed = true;
            LogBridge.i(TAG, "【启动图】直播模式热切换 → 跳过启动图");
            return;
        }
        // 保底：超时无条件淡出（停留「我的」板块 / 播放异常等场景）
        mainHandler.removeCallbacks(splashFallbackRunnable);
        mainHandler.postDelayed(splashFallbackRunnable, SPLASH_FALLBACK_MS);
        // 触摸点击（触摸屏设备）→ 理解为「想操作」，立即放行
        splashOverlay.setOnClickListener(v -> dismissSplashOverlay("用户点击"));
        LogBridge.i(TAG, "【启动图】OTT 启动覆盖层已显示（就绪/9s 保底/用户操作后淡出）");
    }

    /** 【2026-10-18 启动图】淡出启动覆盖层（幂等）：动画结束后 GONE 并释放图片引用 */
    private void dismissSplashOverlay(String reason) {
        if (splashDismissed) return;
        splashDismissed = true;
        mainHandler.removeCallbacks(splashFallbackRunnable);
        LogBridge.i(TAG, "【启动图】淡出覆盖层: " + reason);
        if (splashOverlay == null) return;
        splashOverlay.animate()
                .alpha(0f)
                .setDuration(SPLASH_FADE_MS)
                .withEndAction(() -> {
                    splashOverlay.setVisibility(View.GONE);
                    View img = splashOverlay.findViewById(R.id.ott_splash_image);
                    if (img instanceof ImageView) {
                        ((ImageView) img).setImageDrawable(null);
                    }
                })
                .start();
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

    /**
     * 【2026-10-17 用户口径】控制栏可见时抑制「换台提示」。
     *
     * <p>用户正在用控制栏里的「频道列表 / 选集」抽屉切台，控制栏标题行本身已经显示
     * 频道名 + 清晰度，再叠上切台信息条（{@link ChannelInfoBar}）和右上角频道号 OSD
     * 既重复又挡住画面（用户截图反馈）。因此这两类提示在控制栏可见期间一律不显示。
     *
     * <p>控制栏收起后（方向键换台、数字键输号等场景）恢复原有提示行为。
     */
    private boolean switchHintsSuppressed() {
        return ottControllerShowing && vodBar != null && vodBar.getVisibility() == View.VISIBLE;
    }

    /** 立即隐藏右上角频道号 OSD（与 {@link #switchHintsSuppressed()} 配套） */
    private void hideChannelNumOsd() {
        mainHandler.removeCallbacks(hideChannelNumRunnable);
        if (tvOttChannelNum != null && tvOttChannelNum.getVisibility() != View.GONE) {
            tvOttChannelNum.setVisibility(View.GONE);
        }
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
            switchToLiveMode();
        }
    }

    /**
     * 【2026-10-18 用户需求】一键切回直播模式：
     * 顶栏原「全屏」按钮（现文案「直播」）与 设置切源模式 共用入口。
     * 写持久 sourceMode（重启后仍进直播）→ switchingToLive 让 onDestroy 保留播放器 →
     * EXTRA_SKIP_SOURCE_ROUTE 让 MainActivity 跳过路由直接进直播 UI。
     */
    private void switchToLiveMode() {
        if (switchingToLive) return;   // 幂等：连点/设置路径重复触发
        switchingToLive = true;
        appConfig.setSourceMode(AppConfig.SOURCE_MODE_LIVE);
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_SKIP_SOURCE_ROUTE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
        LogBridge.i(TAG, "【模式切换】OTT → 直播（顶栏按钮）");
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
        // 【2026-10-17】点播进度心跳：每 10s 落一次盘，防进程被强杀丢进度
        mainHandler.removeCallbacks(periodicSaveRunnable);
        mainHandler.postDelayed(periodicSaveRunnable, PERIODIC_SAVE_MS);
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
        // 【2026-10-17】退后台停心跳（onPause 已保存一次，不必继续跑）
        mainHandler.removeCallbacks(periodicSaveRunnable);
        // 【2026-09-29 自动全屏】退后台时取消计时，避免后台到期、回前台被直接弹进全屏
        cancelAutoFullscreen();
        // 【2026-10-11 源7】退后台收起控制栏，回来时保持干净画面
        hideSource7Controller();
        // 【2026-10-14 视频进度续播】退后台时保存当前播放进度
        savePlaybackPosition();
        if (mPlayerManager != null) {
            mPlayerManager.onBackground();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 【2026-10-14 视频进度续播】onStop 时也保存一次（防止 onPause 时播放器已释放）
        savePlaybackPosition();
        // 【2026-09-29 黑屏修复】补上此前缺失的 onStop：退后台（HOME / 返回键退出）时
        // 再次主动失效 Surface 状态，确保回前台必然走「重建 + 重试」分支，
        // 不会把画面播到已失效的 Surface 上（黑屏根因）。
        if (mPlayerManager != null) {
            mPlayerManager.onBackground();
        }
    }

    /** 【2026-10-14 视频进度续播】保存当前播放进度（只针对点播，直播不记） */
    private void savePlaybackPosition() {
        if (playingChannel == null || mPlayerManager == null) return;
        // 【2026-10-17 用户口径】直播频道不记录进度：既不写历史，也不产生"回来 seek 到某处"的副作用
        if (!isVodChannel(playingChannel)) return;
        try {
            long pos = mPlayerManager.getCurrentPosition();
            long dur = mPlayerManager.getDuration();
            // 【2026-10-17 广告剥离适配】片长一并记下：点播走代理时广告分片被剔除，
            //   时间轴比源清单短；下次剥离结果若不同，靠「片长比例」把位置校正回去。
            LogBridge.i(TAG, "【OTT·保存进度】频道=" + playingChannel.getName()
                    + " 位置=" + pos + "ms 片长=" + dur + "ms");
            if (pos > 5000) { // 至少播了5秒才记录
                appConfig.updateOttHistoryPosition(playingChannel.getName(), pos, dur);
                LogBridge.i(TAG, "【OTT·保存进度】已保存");
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "【OTT·保存进度】异常: " + t.getMessage());
        }
    }

    /** 【2026-10-14】在事件分发前拦截：集数/历史末行按向下键不跳转，停在原地 */
    

    private static boolean isDescendant(View parent, View child) {
        if (parent == null || child == null) return false;
        View v = child;
        while (v != null) {
            if (v == parent) return true;
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 【2026-10-18 启动图】覆盖层显示期间：任意按键 → 立即淡出并消费本次事件
        // （与 MainActivity.dispatchKeyEvent 同口径；淡出后原有逻辑不受影响）
        if (!splashDismissed && splashOverlay != null
                && splashOverlay.getVisibility() == View.VISIBLE) {
            dismissSplashOverlay("用户按键 keyCode=" + keyCode);
            return true;
        }
        // 【2026-10-18 两级导航】胶囊面板栈 BACK：多层 → 只关当前层露出上一级；仅一层 → 整体关闭
        if (keyCode == KeyEvent.KEYCODE_BACK && !capsuleStack.isEmpty()) {
            if (capsuleStack.size() > 1) {
                capsuleStack.peek().close();   // close → onCapsuleClosed → 露出上一层并还焦点
                LogBridge.i(TAG, "【按键】返回 → 胶囊面板回上一层（剩余 " + capsuleStack.size() + " 层）");
            } else {
                closeControlPanels();
                LogBridge.i(TAG, "【按键】返回 → 关闭胶囊面板（单层）");
            }
            return true;
        }
        LogBridge.i(TAG, "【按键】code=" + keyCode + " 控制栏=" + ottControllerShowing
                + " 全屏=" + fullscreen + " 焦点=" + (getCurrentFocus() == null ? "null"
                : getCurrentFocus().getClass().getSimpleName()));
        // 【2026-10-17 v2】详情页打开时：返回键只关详情页（不连带退全屏/退应用），
        //   其余按键交给面板内部的焦点系统处理（按钮行 ↔ 选集行）。
        if (detailPanel != null && detailPanel.isShowing()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                detailPanel.hide();
                LogBridge.i(TAG, "【详情页】返回键 → 关闭详情");
                return true;
            }
            return false;
        }
        // 【2026-09-29 数字键输号跳台】全屏/非全屏都支持（口径同直播模式）
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            handleNumberKey(keyCode);
            return true;
        }
        // 【2026-10-14】非全屏：集数/历史末行按向下键不跳转网格，停在原地
        if (!fullscreen && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            View cur = getCurrentFocus();
            if (cur != null && llEpgList != null) {
                List<View> epgRows = focusableChildren(llEpgList);
                List<View> histRows = (llOttHistoryRows != null) ? focusableChildren(llOttHistoryRows) : new ArrayList<>();
                View epgLast = epgRows.isEmpty() ? null : epgRows.get(epgRows.size() - 1);
                View histLast = histRows.isEmpty() ? null : histRows.get(histRows.size() - 1);
                if ((epgLast != null && cur == epgLast) || (histLast != null && cur == histLast)) {
                    LogBridge.i(TAG, "【OTT·焦点】末行按向下键，吞掉不跳转");
                    return true;
                }
            }
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
            // 【v1.6 原生焦点】控制栏/抽屉可见时，所有按键**完全交给 Android 原生焦点系统**：
            //   方向键 = FocusFinder 在可聚焦视图间移动；确认键 = 焦点视图自己消费（OnClickListener）；
            //   返回键 = 不拦截，走 onBackPressed 的分层处理（关抽屉 → 收控制栏 → 退全屏）。
            //   仅保留一件事：任意按键重置 5s 自动收起计时（主流 TV 端一致）。
            if (ottControllerShowing) {
                // 【返回键必须显式处理】onKeyDown 返回 false 后系统不会再走 onBackPressed
                //   （Activity 默认的 BACK→onBackPressed 需要 super.onKeyDown 里 startTracking），
                //   所以这里直接按"关抽屉 → 收控制栏"的顺序处理，实测必须这样做。
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    // 【2026-10-17】频道列表也必须算进来：焦点已锁定在抽屉内，
                    // 不会再靠"焦点跑掉自动关闭"兜底，返回键是唯一的出口。
                    if (capsulePanel != null || episodePanel != null || channelListPanel != null) {
                        closeControlPanels();
                        LogBridge.i(TAG, "【按键】返回 → 关闭抽屉");
                    } else {
                        hideSource7Controller();
                        LogBridge.i(TAG, "【按键】返回 → 收起控制栏");
                    }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    mainHandler.removeCallbacks(hideOttControllerRunnable);
                    mainHandler.postDelayed(hideOttControllerRunnable, CONTROLLER_HIDE_MS);
                }
                // 【v1.6 原生焦点】控制栏/抽屉可见时**不做任何拦截**：
                //   方向键/确认键在到达 onKeyDown 之前，已由 DecorView 分发给焦点视图
                //   （按钮 OnClickListener / 抽屉行 / 进度条）并消费；
                //   走到这里还没被消费的键，直接 return false 交回系统。
                //   ⚠ 绝不能再调 dispatchKeyEvent（会重入 onKeyDown → StackOverflowError，已实测崩溃）。
                return false;
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    // 【2026-10-17】点播全屏上键 = 上一集；直播仍为上一个频道
                    if (isSource7Playing() || isRenyiboVodChannel(playingChannel)) {
                        switchVodEpisode(-1);
                        return true;
                    }
                    playRelativeChannel(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (isSource7Playing() || isRenyiboVodChannel(playingChannel)) {
                        switchVodEpisode(1);
                        return true;
                    }
                    playRelativeChannel(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // 【2026-10-14】全屏左键 → 快退 10 秒（无需唤起控制栏）
                    seekByOffset(-10_000L);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // 【2026-10-14】全屏右键 → 快进 10 秒（无需唤起控制栏）
                    seekByOffset(10_000L);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // 【v1.6 修正】全屏确认键只负责**唤起控制栏**，不再顺带切换播放/暂停：
                    //   原实现 togglePlayWhenReady() + showSource7Controller() 同时执行，
                    //   用户按 OK 想看控制栏却把片子暂停了（实测误触）。
                    //   控制栏已显示时，确认键由焦点按钮自己消费，不会走到这里。
                    if (isVodControllerEligible()) {
                        showSource7Controller();
                        LogBridge.i(TAG, "【OTT·点播】全屏确认键 → 唤起控制栏（不改播放状态）");
                        return true;
                    }
                    setFullscreen(false);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    // 【2026-10-17 清晰度】点播全屏按菜单键 → 清晰度选择（不弹设置，避免误触）
                    if (isVodControllerEligible()) {
                        showQualityDialog();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    // 【2026-10-11 源7】控制栏展示中 → 先收起控制栏；否则退出全屏
                    if (ottControllerShowing) {
                        hideSource7Controller();
                        return true;
                    }
                    setFullscreen(false);                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // 【2026-10-17 v2】详情页最优先关闭（部分系统路径 BACK 不走 onKeyDown）
        if (detailPanel != null && detailPanel.isShowing()) {
            detailPanel.hide();
            LogBridge.i(TAG, "【详情页】onBackPressed → 关闭详情");
            return;
        }
        // 【v1.6】返回键分层（与主流 TV 端一致）：先关抽屉 → 再收控制栏 → 最后退全屏
        if (capsulePanel != null || episodePanel != null || channelListPanel != null) {
            closeControlPanels();
            return;
        }
        if (fullscreen) {
            // 【2026-10-11 源7】控制栏展示中：第一次返回先收起控制栏，再按一次才退出全屏
            if (ottControllerShowing) {
                hideSource7Controller();
                return;
            }
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
        // 【2026-10-17】停掉进度心跳，避免 Handler 持有 Activity
        mainHandler.removeCallbacks(periodicSaveRunnable);
        // 【2026-10-18 启动图】销毁时仍未淡出 → 摘掉保底回调，避免 Handler 持有已销毁的 Activity
        mainHandler.removeCallbacks(splashFallbackRunnable);
        // 【2026-10-11 源7】注销 EPG 数据就绪监听，避免 Activity 泄漏
        try {
            EpgManager.getInstance(getApplicationContext())
                    .unregisterEpgDataReadyListener(epgDataReadyListener);
        } catch (Exception ignored) {
        }
        if (appCoreManager != null) {
            appCoreManager.release();
            appCoreManager = null;
        }
        if (mPlayerManager != null) {
            // 【2026-10-11】只清「自己的」监听器：单例播放器上可能已有后创建 Activity 注册的
            //   新监听器，无脑置 null 会把它的回调一起清掉（本 Activity 才是被销毁的那个）。
            if (mPlayerManager.getOnPlayStateListener() == ottPlayStateListener) {
                mPlayerManager.setOnPlayStateListener(null);
            }
            if (mPlayerManager.getOnAfhgrjEpisodeListener() == afhgrjEpisodeListener) {
                mPlayerManager.setOnAfhgrjEpisodeListener(null);
            }
            if (mPlayerManager.getOnRenyiboEpisodeListener() == renyiboEpisodeListener) {
                mPlayerManager.setOnRenyiboEpisodeListener(null);
            }
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
                // 【2026-10-17 v2】直播卡的「当前节目 + 进度条」
                holder.program = convertView.findViewById(R.id.tv_ott_item_program);
                holder.progress = convertView.findViewById(R.id.pb_ott_item_progress);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            Channel channel = shownChannels.get(position);
            holder.name.setText(channel.getName());
            // 【2026-09-29 频道台标】【2026-10-11 改版式】【2026-10-13 电视视觉修复】按内容形态渲染：
            //   · 虎牙同看频道：台标来自直播封面，是 16:9 实拍截图 → centerCrop 满铺；
            //   · 源7(afhgrj) 影视 / 源5(renyibo-vod) 点播：绑定的是竖版海报图 → centerCrop 满铺；
            //   · M3U / 畅享 / 源5 直播台标：透明底 logo（210×210、640×320 等）→ fitCenter，
            //     且【电视修复】限制为 64×36dp 居中小块——之前 fitCenter 在 88dp 卡片里会把
            //     长条台标撑到整宽（占卡 60-70%），电视上看「图片太大」。
            // 【2026-10-19 源8 renyibo】竖版海报（270×382 实测），与源7/源5点播同形态 → 满铺。
            //   漏在这里会走 else 分支：FIT_CENTER + 固定 64×36dp 小块，电视上看「图片太小」。
            final boolean posterStyle = channel.isTogetherWatch() || channel.isHuyaSdkTogetherWatch()
                    || isAfhgrjChannel(channel) || isRenyiboVodChannel(channel)
                    || com.tv.live.ryb.RybUrlResolver.vodIdOf(channel) != null;
            if (posterStyle) {
                holder.logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.logo.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            } else {
                holder.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(64), dp(36),
                        Gravity.CENTER);
                holder.logo.setLayoutParams(lp);
            }
            // 加载频道台标（空/失败回退占位图，内部已做复用防错位）
            ChannelLogoLoader.getInstance(getApplicationContext())
                    .load(holder.logo, channel.getLogoUrl(), R.drawable.ic_channel_logo_placeholder);

            // 【2026-10-17 v2】直播卡显示「当前节目 + 进度」；点播卡保持海报+更新状态（不叠节目行）
            bindCardProgramInfo(holder, channel, posterStyle);

            convertView.setSelected(channel == playingChannel);

            // 【2026-09-29 触摸点击修复】卡片根布局 android:clickable="true" 但从未设监听，
            // 触摸点击被卡片吞掉、GridView.onItemClick 收不到 → 鼠标/手指点频道无反应。
            // 行为与键盘 onItemClick 一致。
            // ⚠️ 必须每次 getView 都设置：GridView 复用 convertView，只在新建时挂会捕获过期 position。
            convertView.setOnClickListener(v -> onChannelCardClick(position));

            return convertView;
        }

        /**
         * 【2026-10-17 v2】直播卡片的「当前节目 + 进度条」。
         *
         * <p>点播卡（海报风格）不显示：海报上已有「更新至 N 集」这类状态，再叠一行节目名会挤。
         * 直播卡拿不到 EPG 时降级显示「直播中」，EPG 就绪后由已有的
         * {@code registerEpgDataReadyListener} 触发重新渲染。
         */
        private void bindCardProgramInfo(ViewHolder holder, Channel ch, boolean posterStyle) {
            if (holder.program == null) return;
            if (posterStyle) {
                holder.program.setVisibility(View.GONE);
                if (holder.progress != null) holder.progress.setVisibility(View.GONE);
                return;
            }
            String title = null;
            float pct = -1f;
            try {
                java.util.List<Channel.EpgItem> list = epgOf(ch);
                if (list != null && !list.isEmpty()) {
                    int idx = currentEpgIndex(list);
                    if (idx >= 0) {
                        Channel.EpgItem it = list.get(idx);
                        title = it.title;
                        if (it.time != null) pct = programProgress(it.time);
                    }
                }
            } catch (Throwable ignored) {
            }
            boolean hasProgram = !TextUtils.isEmpty(title);
            holder.program.setVisibility(View.VISIBLE);
            holder.program.setText(hasProgram ? title : "直播中");
            holder.program.setTextColor(hasProgram ? 0x99B9C4D8 : 0x5599A0B4);
            if (holder.progress != null) {
                if (pct >= 0f) {
                    holder.progress.setVisibility(View.VISIBLE);
                    holder.progress.setProgress((int) Math.max(0, Math.min(100, pct * 100)));
                } else {
                    holder.progress.setVisibility(View.GONE);
                }
            }
        }
    }

    /** 源7(afhgrj) 频道判定：其主播放地址为自定义协议 {@code afhgrj://<vodId>} */
    private static boolean isAfhgrjChannel(Channel channel) {
        if (channel == null) return false;
        String url = channel.getMainPlayUrl();
        return url != null && url.startsWith("afhgrj://");
    }

    /** 源5点播(renyibo-vod) 频道判定：其主播放地址为自定义协议 {@code renyibo-vod://<typeId>:<vodId>} */
    private static boolean isRenyiboVodChannel(Channel channel) {
        if (channel == null) return false;
        String url = channel.getMainPlayUrl();
        return url != null && url.startsWith("renyibo-vod://");
    }

    private static class ViewHolder {
        TextView name;
        // 【2026-09-29 频道台标】原 group TextView 已移除（卡片高度限制），改为台标 ImageView
        ImageView logo;
        // 【2026-10-17 v2】直播卡：当前节目名 + 底部进度条
        TextView program;
        android.widget.ProgressBar progress;
    }
}