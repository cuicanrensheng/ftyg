package com.tv.live.manager;

import android.content.Context;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.R;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.PanelFocusHistory;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.EpgManager.DateListManager;
import com.tv.live.EpgManager.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChannelPanelController {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

    private MainActivity activity;
    private Context context;
    private View panelLayout;
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;
    private ListView lvDate;
    private ListView lvEpg;
    private TextView btnShowEpg;
    private TextView btnBackGroup;

    private View llLeftPanel;
    private View llRightPanel;
    private boolean rightPanelOpen = false;

    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    /** 【2026-10-21】直播模式频道面板焦点历史：记录左/右面板 7 个区域的停留位置 */
    private PanelFocusHistory focusHistory;

    private List<Channel> channelSourceList = new ArrayList<>();
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private String currentGroupName = "";
    private int currentPlayIndex = 0;
    /** 当前正在播放的频道对象。第2路固定分组频道不在 channelSourceList（取消共享池后），
     *  无法用 currentPlayIndex 反查定位，故单独保存对象，供列表高亮/自动定位使用。 */
    private Channel currentPlayingChannel = null;

    /** 右侧 EPG 列表实际渲染的「全量合并列表」（第1路 channelSourceList + 第2路固定分组全部频道），
     *  与渲染顺序一一对应。点击时按其 position 直接取对象起播，避免 position 映射错位。 */
    private final List<Channel> epgMergedChannelList = new ArrayList<>();
    private int currentSelectedDateIndex = 0;

    private boolean epgPanelOpen = false;
    private boolean epgEnable = true;

    private final Set<String> loadedFixedGroups = new HashSet<>();

    private boolean sdkGroupsPreloadStarted = false;

    private volatile boolean sdkGroupsPreloading = false;

    /** 晚到数据（HTTP 一起看 / SDK 固定分组）合并后的刷新去抖：两条线路几乎同时到达时只刷一次 */
    private static final long MERGE_REFRESH_DEBOUNCE_MS = 300;

    private final android.os.Handler mergeRefreshHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private Runnable pendingMergeRefresh;

    /** SDK 线路磁盘缓存是否为空：为空（如首次安装）时把预加载提前，避免长时间看不到虎牙频道 */
    private boolean sdkCacheEmpty = true;

    /** 最近一次被 setChannels 接受的直播源签名（用于幂等判断，避免重复全量重建） */
    private String lastSourceSignature = "";

    // 【2026-10-03 第九轮·与第一路完全对齐】
    // 原先固定分组预加载有两级人为延迟：① 固定 10s（理由："避免与首播争抢 SDK"）② isSDKAvailable 门控
    // （SDK 未就绪时等 init-ready，首帧后 ~4s）。但两条线路 2026-10-03 已全部改自研 HTTP、不碰 SDK，
    // 两个前提都失效。第八轮先去门控 + 降 10s→3s（实测冷缓存补齐 +10.9s → +4.3s，−61%）；
    // 第九轮再彻底去掉延迟，与第一路 loadHuyaTogetherWatchChannels() 同款「立即异步发车」。
    // 安全性：对外并发由两条线路各自的信号量（NO_THROTTLE_SEM=8 / LIST_PAGE_CONCURRENCY=8）收敛，
    // 同时到达的频道经 appendChannelsIncremental 去重 + 去抖合并为一次 UI 刷新。
    // 如需恢复错峰，在 preloadSdkFixedGroups() 的 execute 外包一层 postDelayed 即可。

    /** 固定分组预加载失败/未出结果时的最小补跑间隔，防止 setChannels 重复触发导致连拉两次 */
    private static final long SDK_PRELOAD_RETRY_MIN_INTERVAL_MS = 8000;

    private long lastSdkPreloadAttemptAt = 0;

    private final ExecutorService sdkPreloadExecutor = Executors.newSingleThreadExecutor();

    private boolean mIsFirstLaunch = true;

    private boolean isReverse = false;
    private long lastChannelChangeTime = 0;

    private String lastSwitchDirection = "";
    private boolean isSwitchingChannel = false;
    private int autoSkipCount = 0;

    private OnChannelChangeListener channelChangeListener;
    private OnPanelStateListener panelStateListener;

    /**
     * 【2026-10-22 L1-10】EPG 就绪监听提为字段（原为构造函数里的匿名类，只注册不注销）：
     * release() 成对 unregister，避免面板销毁后回调仍持 panelLayout/各 Manager 引用。
     * 注册仍在构造函数（与字段声明分离，保证先赋值后注册）。
     */
    private final com.tv.live.EpgManager.OnHuyaEpgReadyListener huyaEpgReadyListener =
            new com.tv.live.EpgManager.OnHuyaEpgReadyListener() {
                @Override
                public void onHuyaEpgReady(com.tv.live.Channel readyChannel) {
                    if (readyChannel == null) return;
                    if (panelLayout == null) return;
                    if (channelSourceList == null || currentPlayIndex < 0
                            || currentPlayIndex >= channelSourceList.size()) return;
                    com.tv.live.Channel nowPlaying = channelSourceList.get(currentPlayIndex);
                    if (nowPlaying == null) return;

                    boolean sameChannel = (readyChannel.getHuyaRoomId() > 0
                            && readyChannel.getHuyaRoomId() == nowPlaying.getHuyaRoomId())
                            || (readyChannel.getChannelId() != null
                            && readyChannel.getChannelId().equals(nowPlaying.getChannelId()))
                            || (readyChannel.getName() != null
                            && readyChannel.getName().equals(nowPlaying.getName()));
                    if (!sameChannel) return;
                    panelLayout.post(() -> {
                        try {
                            if (epgManagerWrapper != null) {
                                epgManagerWrapper.refresh(nowPlaying, channelSourceList, currentDateOffset());
                                LogBridge.d("ChannelPanel", "🟢【虎牙EPG刷新】解析完成，已刷新右侧面板："
                                        + (nowPlaying.getName() == null ? "" : nowPlaying.getName().substring(0, Math.min(20, nowPlaying.getName().length()))));
                            }
                        } catch (Exception ignored) {}
                    });
                }
            };

    /** 【2026-10-22 L1-10】直播源 EPG 全量就绪监听（字段化理由同上） */
    private final com.tv.live.EpgManager.OnEpgDataReadyListener epgDataReadyListener =
            new com.tv.live.EpgManager.OnEpgDataReadyListener() {
                @Override
                public void onEpgDataReady() {
                    if (panelLayout == null) return;
                    panelLayout.post(() -> {
                        try {
                            if (!rightPanelOpen) return;   // 面板没开不用刷
                            LogBridge.d("ChannelPanel", "🟢【EPG数据就绪】直播源节目单解析完成，刷新右侧面板");
                            syncEpgList();
                            Channel curr = getPlayingChannelForLocate();
                            if (curr != null && epgManagerWrapper != null) {
                                epgManagerWrapper.refresh(curr, channelSourceList, currentDateOffset());
                            }
                        } catch (Exception ignored) {}
                    });
                }
            };

    public interface OnChannelChangeListener {
        void onChannelChanged(Channel channel, int index);
    }

    public interface OnPanelStateListener {
        void onPanelStateChanged(boolean isOpen);
    }

    public ChannelPanelController(
            MainActivity activity,
            View panelLayout,
            View llLeftPanel,
            View llRightPanel,
            ListView lvGroup,
            ListView lvChannelList,
            ListView lvChannelListEpg,
            ListView lvDate,
            ListView lvEpg,
            TextView btnShowEpg,
            TextView btnBackGroup,
            GroupListManager groupListManager,
            ChannelListManager channelListManager,
            ChannelListManager channelListManagerEpg,
            DateListManager dateListManager,
            EpgManagerWrapper epgManagerWrapper,
            PanelManager panelManager
    ) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.panelLayout = panelLayout;
        this.llLeftPanel = llLeftPanel;
        this.llRightPanel = llRightPanel;
        this.lvGroup = lvGroup;
        this.lvChannelList = lvChannelList;
        this.lvChannelListEpg = lvChannelListEpg;
        this.lvDate = lvDate;
        this.lvEpg = lvEpg;
        this.btnShowEpg = btnShowEpg;
        this.btnBackGroup = btnBackGroup;
        this.groupListManager = groupListManager;
        this.channelListManager = channelListManager;
        this.channelListManagerEpg = channelListManagerEpg;
        this.dateListManager = dateListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        this.panelManager = panelManager;
        applyOttFocusStyle();
        initClickListeners();
        initFocusHistory();

        // 【2026-10-22 L1-10】注册字段化监听（字段声明处有成对注销说明）；release() 成对注销
        try {
            com.tv.live.EpgManager.getInstance().registerHuyaEpgReadyListener(huyaEpgReadyListener);
        } catch (Exception ignored) {}

        // 【2026-10-05】直播源 EPG 全量数据就绪（磁盘缓存先行/网络更新解析完成）：
        // 右侧面板若已打开，立即刷新节目单。原问题：解析完成只刷信息栏，
        // 开着面板的用户看到的一直是空白，必须关掉重开面板才有数据。
        try {
            com.tv.live.EpgManager.getInstance().registerEpgDataReadyListener(epgDataReadyListener);
        } catch (Exception ignored) {}
    }

    /**
     * 【2026-10-20 焦点改为金色（对齐 OTT 面板）】
     * 直播侧五组列表的焦点视觉改由「行背景」承担 —— 与 OTT 面板
     * （ChannelListPanel.styleRow / CapsuleChoicePanel.styleCapsule / EpisodePanel.styleCell）
     * 完全一致：焦点 → 金底 #FFD166 + 2dp 白描边 + 深色字 #111111。
     * 故此处把 listSelector 置为透明，避免原先复用 OTT 频道网格的蓝色焦点环
     * （ott_channel_focus）与金色行底叠加成两层框。
     */
    private void applyOttFocusStyle() {
        ListView[] lists = {lvGroup, lvChannelList, lvChannelListEpg, lvDate, lvEpg};
        for (ListView lv : lists) {
            if (lv == null) continue;
            lv.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
            lv.setDrawSelectorOnTop(false);
        }
    }

    public void updatePanelBackground(int resId) {
        if (llLeftPanel != null) llLeftPanel.setBackgroundResource(resId);
        if (llRightPanel != null) llRightPanel.setBackgroundResource(resId);
    }

    private void initClickListeners() {
        lvGroup.setOnItemClickListener((parent, view, position, id) -> onGroupClicked(position));
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        // 右侧 EPG 列表渲染的是「全量合并列表」（第1路 channelSourceList + 第2路固定分组），
        // position 无法直接映射回 channelSourceList，需按其独立合并列表取对象起播。
        lvChannelListEpg.setOnItemClickListener((p, v, pos, id) -> onEpgChannelClicked(pos));

        channelListManager.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, false));
        channelListManagerEpg.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, true));

        btnShowEpg.setOnClickListener(v -> onEpgButtonClicked());
        // 【2026-10-20 面板按钮一并金色化 → 焦点降亮】焦点表现与左侧胶囊项统一：
        // 焦点 → 暗金（橄榄）底 #3C3525 + 2dp 白描边 + 黄字 #FFFFD166（ott_tab_focused），
        // 默认 → list_item_default #212121 + 白字。
        applyGoldFocus(btnShowEpg);
        btnShowEpg.setOnKeyListener((v, keyCode, event) -> {
            LogBridge.d("ChannelPanel", "btnShowEpg onKey keyCode:" + keyCode + ", action:" + event.getAction());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    onEpgButtonClicked();
                    return true;
                }
            }
            return false;
        });

        btnBackGroup.setOnClickListener(v -> onBackGroupClicked());
        // 【2026-10-20 面板按钮一并金色化】同 btnShowEpg。
        applyGoldFocus(btnBackGroup);
        btnBackGroup.setOnKeyListener((v, keyCode, event) -> {
            LogBridge.d("ChannelPanel", "btnBackGroup onKey keyCode:" + keyCode + ", action:" + event.getAction());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    onBackGroupClicked();
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 【2026-10-21】频道面板焦点历史：绑定左/右面板 7 个焦点区域并开始监听。
     * 左：分组列表 / 频道列表 / 节目单按钮；右：频道组按钮 / 频道列表 / 日期 / 节目单。
     * 焦点离开某区域时记录其位置，焦点返回时（含左右面板互切、面板关闭再打开）复原。
     */
    private void initFocusHistory() {
        if (panelLayout == null) return;
        focusHistory = new PanelFocusHistory(panelLayout);
        focusHistory.bind(PanelFocusHistory.ZONE_LEFT_GROUP, lvGroup);
        focusHistory.bind(PanelFocusHistory.ZONE_LEFT_CHANNEL, lvChannelList);
        focusHistory.bind(PanelFocusHistory.ZONE_LEFT_EPG_BUTTON, btnShowEpg);
        focusHistory.bind(PanelFocusHistory.ZONE_RIGHT_BACK_BUTTON, btnBackGroup);
        focusHistory.bind(PanelFocusHistory.ZONE_RIGHT_CHANNEL, lvChannelListEpg);
        focusHistory.bind(PanelFocusHistory.ZONE_RIGHT_DATE, lvDate);
        focusHistory.bind(PanelFocusHistory.ZONE_RIGHT_EPG, lvEpg);
        focusHistory.attach();

        // 【2026-10-21】日期列「今天」分支：EPG 刷新完成后，把焦点从日期列移到
        // 「当前正在播放」的节目行。通过 focusZoneAt 先同步历史位置，
        // 避免焦点进入 EPG 时被 reapply() 按旧位置纠偏拉回去。
        if (epgManagerWrapper != null) {
            epgManagerWrapper.setOnPlayingProgramFocusListener(position -> {
                // 【2026-10-22 L1-08】守卫：EPG 刷新完成前用户可能已按 LEFT 离开面板，
                //   此时焦点在播放器/别处，不能再把焦点抢回右侧 EPG（此前表现为
                //   「用户已离开右侧，焦点仍被抢回节目单」）
                if (focusHistory != null
                        && focusHistory.currentZone() == PanelFocusHistory.ZONE_NONE) {
                    LogBridge.d("ChannelPanel", "「今天」定位取消：焦点已离开面板，避免抢夺");
                    return;
                }
                if (focusHistory != null
                        && focusHistory.focusZoneAt(PanelFocusHistory.ZONE_RIGHT_EPG, position)) {
                    return;
                }
                if (lvEpg != null && position >= 0) {
                    lvEpg.setSelection(position);
                    lvEpg.requestFocus();
                }
            });
        }
    }

    /** 焦点回到左侧面板：优先回上次离开的区域与位置，无历史时落到正在播放的频道行 */
    private void restoreLeftFocus() {
        if (lvChannelList == null) return;
        int fallback = getChannelListSelection();
        if (focusHistory != null && focusHistory.focusLastLeft(fallback)) {
            return;
        }
        lvChannelList.requestFocus();
        lvChannelList.setSelection(fallback);
    }

    /** 焦点回到右侧面板：优先回上次离开的区域与位置，无历史时落到右侧频道列表 */
    private void restoreRightFocus() {
        if (focusHistory != null && focusHistory.focusLastRight()) {
            return;
        }
        if (lvChannelListEpg != null) {
            lvChannelListEpg.requestFocus();
        }
    }

    /**
     * 【2026-10-20 面板按钮一并金色化 → 焦点降亮】面板按钮（节目单 / 返回组）的焦点态
     * 与左侧胶囊项统一：焦点 → 暗金（橄榄）底 #3C3525 + 2dp 白描边 + 黄字 #FFFFD166；
     * 默认底为与列表胶囊同色（list_item_default #212121）+ 白字，不再使用 ott_menu_btn_bg；
     * OTT 顶栏按钮本身仍用 ott_menu_btn_bg，不受此影响。
     */
    private void applyGoldFocus(TextView btn) {
        if (btn == null) return;
        btn.setBackgroundResource(R.drawable.list_item_default);
        btn.setTextColor(0xFFFFFFFF);
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            btn.setBackgroundResource(hasFocus
                    ? R.drawable.ott_tab_focused
                    : R.drawable.list_item_default);
            btn.setTextColor(hasFocus ? 0xFFFFD166 : 0xFFFFFFFF);
        });
    }

    public void setChannels(List<Channel> channels) {
        if (channels == null) return;

        final long t0 = System.currentTimeMillis();
        // 幂等保护：冷启动时 AppCoreManager 的 onLiveSourceLoaded 与 onRefresh 会在
        // 1~2s 内先后用同一份直播源各调用一次（9527 实测：setChannels 2 次、
        // 虎牙缓存重复合并 471 条、SDK 固定分组重复预加载 2 轮共 11 次 getRecListByGame）。
        // 直播源内容没变就直接跳过，保住已合并的虎牙频道与用户当前焦点。
        String sig = buildSourceSignature(channels);
        if (sig.equals(lastSourceSignature) && !channelSourceList.isEmpty()) {
            LogBridge.d("ChannelPanel", "setChannels: 直播源未变化（" + channels.size()
                    + " 条，签名一致）→ 跳过重建，当前总频道=" + channelSourceList.size());
            return;
        }
        lastSourceSignature = sig;

        // 直播源真的变了（用户手动刷新/换源）时，保留已经加载好的虎牙频道，
        // 否则"SDK 频道 + 直播源频道同屏"会被一次刷新冲掉，只剩直播源。
        List<Channel> keptHuya = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (c != null && c.getHuyaRoomId() > 0) keptHuya.add(c);
        }

        this.channelSourceList = new java.util.ArrayList<>(channels);
        LogBridge.d("ChannelPanel", "setChannels: 原始频道数=" + channels.size()
                + (keptHuya.isEmpty() ? "" : "，保留虎牙频道 " + keptHuya.size() + " 个"));
        if (!keptHuya.isEmpty()) {
            mergeHuyaChannelsIntoSource(keptHuya);
        }

        // 【2026-10-24】固定分组 = 第二路 5 组 + 第三条线路「任意播」（HuyaRenYiBoLineManager）。
        // 第三条线路完全独立：仅注册分组名用于面板展示，其频道数据由本类独立加载渲染，
        // 不并入 channelSourceList 共享池（与第一/二路互不污染）。
        String[] line2Fixed = HuyaTogetherWatchGroupManager.GROUP_NAMES;
        String[] allFixed = new String[line2Fixed.length + 1];
        System.arraycopy(line2Fixed, 0, allFixed, 0, line2Fixed.length);
        allFixed[line2Fixed.length] = HuyaRenYiBoLineManager.GROUP_NAME;
        groupListManager.setFixedGroups(allFixed);

        groupListManager.setPresetGroups(HuyaTogetherWatchManager.HTTP_API_GROUP_NAMES);
        loadedFixedGroups.clear();

        // 【2026-10-03】直播源整体换新后，固定分组缓存里留的是被丢弃的旧 Channel 实例。
        // 这些实例已不在 channelSourceList 中，继续渲染会导致点击时 indexOf 定位失败
        // （表现为有焦点高亮但点播无反应）。这里一并清空，由 loadFixedGroupChannels→fetchGroup 重新拉取。
        fixedGroupCache.clear();
        LogBridge.d("ChannelPanel", "setChannels: 已重置固定分组缓存 fixedGroupCache 与加载标记 loadedFixedGroups");

        // 虎牙两条线路（HTTP一起看 + SDK固定分组）复用本地缓存，与主源一样进场即显示，
        // 后续仅后台增量合并，避免各自加载完成后重复刷新整个列表。
        //
        // 【2026-10-05 冷启动提速·P0-a】这里原为同步调用 mergeCachedHuyaChannelsSync()。
        // 冷启动 logcat（设备端同一时钟绝对时刻对齐）实测：它在主线程**静默阻塞 294ms**
        // （占 setChannels 总耗时 371ms 的 79%）——期间零日志，结束后 3ms 主线程立刻报
        // "Choreographer: Skipped 45 frames"，并把紧随其后的 doPlay 起播整体推后到阻塞之后
        // （同一 Runnable 内 setChannels → playChannel 顺序执行）。
        // 其中真正昂贵的是 loadCachedChannelsSync() → loadFallbackFromDisk() 对兜底文件的
        // JSON 解析（日志可见兜底文件内有 628 个房间、过滤出 613 个）。纯磁盘/JSON 工作没有
        // 理由占主线程，故改为「后台解析 + 回主线程增量合并」，功能语义不变。
        loadCachedHuyaChannelsAsync();

        groupListManager.setGroups(this.channelSourceList);

        currentGroupName = GroupListManager.GROUP_ALL;
        currentGroupChannelList.clear();
        currentGroupChannelList.addAll(this.channelSourceList);
        channelListManager.setChannels(this.channelSourceList, currentPlayIndex);
        syncEpgList();

        // 【2026-10-16】有缓存时延迟5秒刷新，移到 loadCachedHuyaChannelsAsync 回调中处理
        // loadHuyaTogetherWatchChannels();

        // preloadSdkFixedGroups();
        LogBridge.i("ChannelPanel", "⏱️【链路-面板】setChannels 主源落地+首次渲染 耗时 "
                + (System.currentTimeMillis() - t0) + "ms，频道=" + this.channelSourceList.size());
    }

    /**
     * 直播源列表签名：条数 + 每条的（名称, 播放地址）。
     * 用于判断"这次 setChannels 带来的直播源是否和上次一致"，一致就不重建列表。
     */
    private static String buildSourceSignature(List<Channel> channels) {
        if (channels == null) return "null";
        StringBuilder sb = new StringBuilder(64);
        sb.append(channels.size()).append('|');
        for (int i = 0; i < channels.size(); i++) {
            Channel c = channels.get(i);
            if (c == null) {
                sb.append("-;");
                continue;
            }
            String n = c.getName();
            String u = c.getPlayUrl();
            sb.append(n == null ? "" : n).append('~').append(u == null ? "" : u).append(';');
        }
        return Integer.toString(sb.toString().hashCode());
    }

    /**
     * 【2026-10-05 冷启动提速·P0-a】虎牙缓存合并（异步版）。
     *
     * <p>原实现 {@code mergeCachedHuyaChannelsSync()} 在 {@link #setChannels} 里同步执行：
     * 冷启动实测该段在主线程**静默阻塞 294ms**（占 setChannels 371ms 的 79%），随后主线程
     * 立刻 Choreographer "Skipped 45 frames"，并把同一次 Runnable 里紧随其后的 doPlay 起播
     * 整体推后。昂贵部分是 loadCachedChannelsSync() → loadFallbackFromDisk() 的 JSON 解析
     * （兜底文件 628 个房间）。纯磁盘/JSON 工作不需要主线程。
     *
     * <p>改法：磁盘读取与 JSON 解析丢到 {@code sdkPreloadExecutor} 后台线程；结果回主线程后，
     * 复用与两条 HTTP 线路**完全相同**的增量通道 {@link #appendChannelsIncremental}
     * （去重合并 + 分组计数刷新 + 300ms 去抖的 UI 增量刷新）。因此：
     * <ul>
     *   <li>虎牙频道依旧「进场即显示」，只比原来晚几毫秒；首屏先渲染直播源（185 条）反而更快；</li>
     *   <li>主线程不再被 294ms 阻塞。</li>
     * </ul>
     *
     * <p>线程安全：{@code loadCachedChannelsSync()} 读取的 {@code mRoomList} 仅在
     * {@code HuyaTogetherWatchManager.release()} 里 clear()，启动期无并发写；
     * 合并与渲染全部回主线程（appendChannelsIncremental / cacheFixedGroupChannels 均在主线程调用）。
     */
    private void loadCachedHuyaChannelsAsync() {
        sdkPreloadExecutor.execute(new Runnable() {
            @Override public void run() {
                final List<Channel> incoming = new ArrayList<>();
                List<HuyaTogetherWatchGroupManager.Group> groups = null;
                try {
                    List<Channel> cached = HuyaTogetherWatchManager.getInstance().loadCachedChannelsSync();
                    if (cached != null) incoming.addAll(cached);
                } catch (Throwable ignored) {}
                try {
                    groups = HuyaTogetherWatchGroupManager.getInstance().loadCachedGroupsSync();
                } catch (Throwable ignored) {}
                final List<HuyaTogetherWatchGroupManager.Group> loadedGroups = groups;

                if (panelLayout == null) return;
                panelLayout.post(new Runnable() {
                    @Override public void run() {
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

                        // 【2026-10-03 方案C】预填固定分组独立缓存：让冷启动/重开面板时
                        // 点「虎牙电影」等分组能立刻用本组数据渲染，不依赖 roomId 去重后的残留。
                        if (loadedGroups != null) {
                            for (HuyaTogetherWatchGroupManager.Group g : loadedGroups) {
                                if (g != null && g.name != null && g.channels != null
                                        && !g.channels.isEmpty()) {
                                    cacheFixedGroupChannels(g.name, g.channels);
                                    incoming.addAll(g.channels);
                                }
                            }
                        }

                        sdkCacheEmpty = incoming.isEmpty();
                        if (sdkCacheEmpty) {
                            LogBridge.d("ChannelPanel", "虎牙缓存为空，立即后台刷新");
                            loadHuyaTogetherWatchChannels();
                            preloadSdkFixedGroups();
                            return;
                        }
                        // 与两条 HTTP 线路同一增量通道：去重合并 + 分组计数刷新 + 去抖增量刷新
                        // 【2026-10-17 启动提速】原为「立即合并」——608 条合并 + 分组刷新 + 通知刷新
                        //   恰好落在冷启动起播瞬间（首帧 0~2s 窗口），与播放器竞争主线程、并触发
                        //   3MB 级 GC（实测 786ms）。改为延迟 2 秒合并：基础列表先展示，虎牙分组稍后合入。
                        final java.util.List<Channel> mergePayload = incoming;
                        panelLayout.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                                appendChannelsIncremental(mergePayload, "虎牙缓存(异步)");
                            }
                        }, 2000);

                            // 【2026-10-16】有缓存时延迟5秒后再后台刷新，避免与视频播放竞争带宽
                            LogBridge.d("ChannelPanel", "虎牙缓存命中，延迟5秒后后台刷新");
                            panelLayout.postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                                    loadHuyaTogetherWatchChannels();
                                    preloadSdkFixedGroups();
                                }
                            }, 5000);
                    }
                });
            }
        });
    }

    /**
     * 晚到数据（HTTP 一起看 / SDK 固定分组）的统一增量入口：
     * 1) 去重合并进 channelSourceList；
     * 2) 刷新左侧分组计数（不重建分组列表）；
     * 3) 以 300ms 去抖的方式做一次增量刷新——两条线路几乎同时到达时只刷一次，
     *    且不重建 adapter、不 setSelection、不强制切回"全部"组，用户焦点不跳动。
     * 必须在主线程调用。
     */
    private void appendChannelsIncremental(List<Channel> incoming, String tag) {
        final long t0 = System.currentTimeMillis();
        List<Channel> addedList = mergeHuyaChannelsIntoSource(incoming);
        if (addedList.isEmpty()) {
            LogBridge.d("ChannelPanel", tag + " 无新增频道（已去重），跳过列表刷新");
            return;
        }
        LogBridge.d("ChannelPanel", tag + " 新增 " + addedList.size()
                + " 个频道，总计 " + channelSourceList.size());

        // 数据层立即更新（供上下键/EPG 使用），UI 再去抖刷新
        refreshCurrentGroupChannelList();

        groupListManager.setGroups(channelSourceList, false);

        java.util.ArrayList<Integer> twRoomIds = new java.util.ArrayList<>();
        for (Channel c : addedList) {
            if (c.getHuyaRoomId() > 0) twRoomIds.add(c.getHuyaRoomId());
        }
        if (!twRoomIds.isEmpty()) {
            com.tv.live.util.HuyaSDKParser.preloadRooms(twRoomIds);
        }

        scheduleIncrementalRefresh();
        LogBridge.i("ChannelPanel", "⏱️【链路-面板】" + tag + " 合并+分组刷新 耗时 "
                + (System.currentTimeMillis() - t0) + "ms（UI 去抖刷新 " + MERGE_REFRESH_DEBOUNCE_MS + "ms 后执行）");
    }

    /** 按 currentGroupName 重建当前分组的频道数据（不触碰 UI） */
    private void refreshCurrentGroupChannelList() {
        if (currentGroupChannelList == null) return;
        currentGroupChannelList.clear();
        // 【2026-10-10 修复「第2路点台后随机跳台」】第2路固定分组（虎牙电影等）的频道
        // **不在 channelSourceList**（见 mergeHuyaChannelsIntoSource 的 isLine2FixedGroup 跳过），
        // 用 channelSourceList 过滤会把 currentGroupChannelList 清空/换成错表 —— 而
        // onChannelClicked 正是按 position 从这里取被点对象 → 点第 N 行却播到别的频道。
        // 该分组有独立缓存时直接用缓存重建。
        if (currentGroupName != null && fixedGroupCache.containsKey(currentGroupName)) {
            currentGroupChannelList.addAll(fixedGroupCache.get(currentGroupName));
            return;
        }
        if (channelSourceList == null) return;
        if (currentGroupName == null || currentGroupName.isEmpty()
                || GroupListManager.GROUP_ALL.equals(currentGroupName)) {
            currentGroupChannelList.addAll(channelSourceList);
            return;
        }
        for (Channel c : channelSourceList) {
            if (currentGroupName.equals(GroupListManager.getNormalizedGroup(c))) {
                currentGroupChannelList.add(c);
            }
        }
    }

    /** 去抖刷新：多个后台线路在相近时刻到达时合并为一次列表刷新 */
    private void scheduleIncrementalRefresh() {
        if (pendingMergeRefresh != null) {
            mergeRefreshHandler.removeCallbacks(pendingMergeRefresh);
        }
        pendingMergeRefresh = new Runnable() {
            @Override public void run() {
                pendingMergeRefresh = null;
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                if (channelListManager == null || channelListManagerEpg == null) return;
                final long tUi = System.currentTimeMillis();
                // 【2026-10-10 修复「第2路点台后随机跳台」】第2路固定分组的可见列表由分组独立缓存
                // 驱动；用 channelSourceList + currentGroupName 过滤会把面板换成空表/错表（第2路频道
                // 不在该列表），与 currentGroupChannelList 的取对象口径错位。该分组直接用缓存刷新。
                if (currentGroupName != null && fixedGroupCache.containsKey(currentGroupName)) {
                    channelListManager.setFilteredChannels(
                            fixedGroupCache.get(currentGroupName), getPlayingChannelName());
                } else {
                    channelListManager.refreshChannelsNoRebuild(channelSourceList, currentPlayIndex, currentGroupName);
                }
                // 右侧 EPG 是全量合并视图，不能用 currentGroupName 过滤（否则会收窄成只有当前组）。
                // 合并列表构建后按「正在播对象名」增量刷新（复用 setFilteredChannels 的名字高亮）。
                syncEpgList();
                LogBridge.i("ChannelPanel", "⏱️【链路-面板】ListView 增量刷新(notifyDataSetChanged) 耗时 "
                        + (System.currentTimeMillis() - tUi) + "ms，频道=" + channelSourceList.size());
            }
        };
        mergeRefreshHandler.postDelayed(pendingMergeRefresh, MERGE_REFRESH_DEBOUNCE_MS);
    }

    private List<Channel> mergeHuyaChannelsIntoSource(List<Channel> incoming) {
        List<Channel> addedList = new ArrayList<>();
        if (incoming == null || incoming.isEmpty()) return addedList;
        HashSet<String> existIds = new HashSet<>();
        HashSet<Integer> existRooms = new HashSet<>();
        for (Channel c : channelSourceList) {
            if (c.getChannelId() != null) existIds.add(c.getChannelId());
            if (c.getHuyaRoomId() > 0) existRooms.add(c.getHuyaRoomId());
        }
        for (Channel c : incoming) {
            if (c == null) continue;
            // 【取消两路共享池】第2路固定分组（虎牙电影/电视剧/动漫/综艺/其他）不再并入面板共享列表
            // channelSourceList，它已通过 fixedGroupCache 独立缓存、并由 setFilteredChannels 独立渲染。
            if (isLine2FixedGroup(c.getGroup())) continue;
            if (c.getHuyaRoomId() > 0 && existRooms.contains(c.getHuyaRoomId())) continue;
            if (c.getChannelId() != null && existIds.contains(c.getChannelId())) continue;
            channelSourceList.add(c);
            if (c.getChannelId() != null) existIds.add(c.getChannelId());
            if (c.getHuyaRoomId() > 0) existRooms.add(c.getHuyaRoomId());
            addedList.add(c);
        }
        return addedList;
    }

    private void preloadSdkFixedGroups() {
        if (sdkGroupsPreloadStarted) {
            // 已经排过一次预加载：只有"固定分组确实一个都没加载出来"且距上次尝试
            // 超过最小间隔时才补跑。否则同一批 5 大分组会被拉两遍
            // （实测 11 次 getRecListByGame，其中一半是重复请求）。
            if (loadedFixedGroups.isEmpty() && !sdkGroupsPreloading
                    && System.currentTimeMillis() - lastSdkPreloadAttemptAt
                        > SDK_PRELOAD_RETRY_MIN_INTERVAL_MS) {
                lastSdkPreloadAttemptAt = System.currentTimeMillis();
                LogBridge.d("ChannelPanel", "SDK 固定分组仍未加载，距上次尝试已超 "
                        + SDK_PRELOAD_RETRY_MIN_INTERVAL_MS + "ms，补跑一次");
                sdkPreloadExecutor.execute(() -> doPreloadSdkFixedGroups());
            }
            return;
        }
        sdkGroupsPreloadStarted = true;
        lastSdkPreloadAttemptAt = System.currentTimeMillis();

        // 【2026-10-03 第九轮·与第一路对齐】去掉延后，改为立即发车。
        // 与 setChannels 里的 loadHuyaTogetherWatchChannels()（第一路）完全一致：
        // 都走自研 HTTP、都不碰 SDK，因此同样无需 isSDKAvailable 门控、无需错峰延迟。
        // 立即丢到 sdkPreloadExecutor 执行，不阻塞主线程（与第一路同款异步语义）。
        LogBridge.d("ChannelPanel", "固定分组预加载立即发车（无延迟，与第一路一致；自研线路无需等 SDK）");
        sdkPreloadExecutor.execute(new Runnable() {
            @Override public void run() {
                doPreloadSdkFixedGroups();
            }
        });
    }

    private void doPreloadSdkFixedGroups() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (sdkGroupsPreloading) return;
        sdkGroupsPreloading = true;
        LogBridge.d("ChannelPanel", "🚀 SDK 独立线路后台预加载虎牙 5 大固定分组…");
        HuyaTogetherWatchGroupManager.getInstance().fetchAllGroups(
                new HuyaTogetherWatchGroupManager.OnGroupsListener() {
            @Override
            public void onSuccess(List<HuyaTogetherWatchGroupManager.Group> groups) {
                sdkGroupsPreloading = false;
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                if (groups == null || groups.isEmpty()) {
                    LogBridge.w("ChannelPanel", "SDK 固定分组预加载: 返回空");
                    return;
                }
                long t0 = System.currentTimeMillis();

                List<Channel> incoming = new ArrayList<>();
                int rawCount = 0;
                for (HuyaTogetherWatchGroupManager.Group g : groups) {
                    if (g == null || g.name == null || g.channels == null) continue;
                    loadedFixedGroups.add(g.name);
                    // 【2026-10-03 方案C】固定分组独立数据源预填：
                    // 第二路预加载已拿到该组完整频道，直接缓存一份"本组自有列表"。
                    // 这样用户点「虎牙电视剧」等固定分组时，不必依赖 channelSourceList
                    // 去重后残留的零星频道（那些 roomId 已被第一路内容分类占位丢掉了）。
                    cacheFixedGroupChannels(g.name, g.channels);
                    for (Channel c : g.channels) {
                        if (c == null) continue;
                        incoming.add(c);
                        rawCount++;
                    }
                }
                LogBridge.d("ChannelPanel", "✅ SDK 固定分组拉取完成，原始 " + rawCount
                        + " 条，耗时 " + (System.currentTimeMillis() - t0) + "ms");

                // 统一增量入口：去重合并 + 分组计数刷新 + 去抖增量刷新（不重建、不切组）
                appendChannelsIncremental(incoming, "SDK 固定分组");
            }

            @Override
            public void onError(String errMsg) {
                sdkGroupsPreloading = false;
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                LogBridge.w("ChannelPanel", "SDK 固定分组预加载失败: " + errMsg
                        + "（用户点击分组时仍会走单组拉取）");
            }
        });
    }

    private void loadHuyaTogetherWatchChannels() {
        HuyaTogetherWatchManager.getInstance().fetchTogetherWatchChannels(
                new HuyaTogetherWatchManager.OnChannelsFetchedListener() {
            @Override
            public void onPartial(List<Channel> channels) {
                // 【2026-09-24 提速】先展示后补齐：某个分类先完成即增量合并，
                // 用户无需等到 4 个分类全部拉完（原先 ~30s）才能看到一起看频道
                if (channels == null || channels.isEmpty()) return;
                panelLayout.post(() -> {
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    appendChannelsIncremental(channels, "虎牙一起看(增量)");
                });
            }

            @Override
            public void onSuccess(List<Channel> channels) {
                if (channels == null || channels.isEmpty()) {
                    LogBridge.d("ChannelPanel", "虎牙一起看: 未获取到频道");
                    return;
                }
                panelLayout.post(() -> {
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

                    // 与 SDK 线路走同一入口：去重合并 + 去抖增量刷新，
                    // 不再 setChannels 全量重建、不再强制把用户切回"全部"组
                    appendChannelsIncremental(channels, "虎牙一起看");
                });
            }

            @Override
            public void onFailed(String errorMsg) {
                LogBridge.d("ChannelPanel", "加载虎牙一起看失败: " + errorMsg);
            }
        });
    }

    private void loadFixedGroupChannels(final String groupName) {
        if (channelSourceList == null) return;

        // 【2026-10-24】虎牙第三条线路「任意播」独立加载分支：
        // 数据来自 HuyaRenYiBoLineManager 本地静态清单（零网络请求），独立渲染，
        // 不并入 channelSourceList 共享池、不走 roomId 去重 —— 与第一/二路互不污染。
        if (HuyaRenYiBoLineManager.GROUP_NAME.equals(groupName)) {
            loadRenYiBoFixedGroup(groupName);
            return;
        }

        if (loadedFixedGroups.contains(groupName)) {
            // 【2026-10-03 方案C·固定分组独立数据源】快路径同样改为「直接用本组自己的频道」，
            // 不再从 channelSourceList 里按 group 过滤（原因见下方 loadFixedGroupChannels 注释）。
            List<Channel> own = getFixedGroupChannels(groupName);
            if (own != null && !own.isEmpty()) {
                currentGroupChannelList.clear();
                currentGroupChannelList.addAll(own);
                LogBridge.d("ChannelPanel", "【调试】固定分组[" + groupName + "]快路径(独立源)="
                        + own.size() + " 条");
                channelListManager.setFilteredChannels(own, getPlayingChannelName());
                return;
            }
            // 无独立数据（缓存尚未加载）→ 退回原有全局过滤
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(GroupListManager.getNormalizedGroup(c))) {
                    currentGroupChannelList.add(c);
                }
            }
            LogBridge.d("ChannelPanel", "【调试】固定分组[" + groupName + "]快路径过滤后=" + currentGroupChannelList.size() + " 总频道=" + channelSourceList.size());
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            return;
        }

        // 【2026-10-03 方案C·固定分组独立数据源】
        // 病根：同一个虎牙房间（roomId）同时属于「第一路的内容分类」（怀旧老片/影视热播/剧集追剧…）
        // 和「第二路的固定分组」（虎牙电影/虎牙电视剧/虎牙动漫/虎牙综艺）。`mergeHuyaChannelsIntoSource`
        // 按 roomId 去重 → **先到者独占**。第一路先到，把房间都挂上了内容分类名；第二路后到，同 roomId
        // 全部被丢 → 固定分组过滤后只剩零星漏网的（实测「虎牙电视剧」19 个只剩 5 个），
        // 严重时整组为 0，用户必须重开面板才有。
        // 修法：固定分组有**自己的完整数据**（HuyaTogetherWatchGroupManager 已按组拉全），
        // 直接用它渲染，不再经过 channelSourceList 的 roomId 去重。两套分组因此互不干扰：
        // 「全部」列表不重复、「内容分类」保持第一路的归属、「固定分组」保持第二路的归属。
        List<Channel> ownChannels = getFixedGroupChannels(groupName);
        final boolean hadCached = (ownChannels != null && !ownChannels.isEmpty());

        currentGroupChannelList.clear();
        if (hadCached) {
            currentGroupChannelList.addAll(ownChannels);
            channelListManager.setFilteredChannels(ownChannels, getPlayingChannelName());
        } else {
            // 无独立数据（缓存/网络都未就绪）→ 先占位空列表，等下方 fetchGroup 回调填充
            channelListManager.setFilteredChannels(new ArrayList<Channel>(), null);
        }

        HuyaTogetherWatchGroupManager.getInstance().fetchGroup(groupName,
                new HuyaTogetherWatchGroupManager.OnChannelsListener() {
                    @Override
                    public void onSuccess(List<Channel> channels) {
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                        loadedFixedGroups.add(groupName);
                        if (channels == null || channels.isEmpty()) {
                            LogBridge.w("ChannelPanel", "虎牙固定分组[" + groupName + "]无频道");
                            groupListManager.setGroups(channelSourceList, false);
                            return;
                        }

                        // 【方案C】本组自己的完整列表：加入固定分组缓存，供下次快路径直接使用。
                        // 仍然并入 channelSourceList（供「全部」/搜索/EPG 使用），
                        // 但**不再**把「并入成功与否」当作本组能否显示的条件。
                        cacheFixedGroupChannels(groupName, channels);

                        // 【取消两路共享池】仅当 groupName 不是第2路固定分组时才并入面板共享列表
                        // channelSourceList。第2路分组照常缓存 + 独立渲染，只是不再写进共享池，
                        // 从而彻底不与第1路（HTTP一起看内容分类）同名房间合并去重。
                        if (!isLine2FixedGroup(groupName)) {
                            HashSet<String> existIds = new HashSet<>();
                            HashSet<Integer> existRooms = new HashSet<>();
                            for (Channel c : channelSourceList) {
                                if (c.getChannelId() != null) existIds.add(c.getChannelId());
                                if (c.getHuyaRoomId() > 0) existRooms.add(c.getHuyaRoomId());
                            }
                            int added = 0;
                            java.util.ArrayList<Integer> twRoomIds = new java.util.ArrayList<>();
                            for (Channel c : channels) {
                                if (c == null) continue;
                                if (c.getHuyaRoomId() > 0 && existRooms.contains(c.getHuyaRoomId())) continue;
                                if (c.getChannelId() != null && existIds.contains(c.getChannelId())) continue;
                                channelSourceList.add(c);
                                if (c.getChannelId() != null) existIds.add(c.getChannelId());
                                if (c.getHuyaRoomId() > 0) {
                                    existRooms.add(c.getHuyaRoomId());
                                    twRoomIds.add(c.getHuyaRoomId());
                                }
                                added++;
                            }
                            LogBridge.d("ChannelPanel", "虎牙固定分组[" + groupName + "]加载完成，本组="
                                    + channels.size() + " 条，并入全部列表新增 " + added + " 个，总计 " + channelSourceList.size());

                            if (!twRoomIds.isEmpty()) {
                                com.tv.live.util.HuyaSDKParser.preloadRooms(twRoomIds);
                            }
                        }

                        groupListManager.setGroups(channelSourceList, false);

                        // 【方案C】直接用本组自己的列表渲染（不经过 channelSourceList 过滤）
                        currentGroupChannelList.clear();
                        currentGroupChannelList.addAll(channels);
                        LogBridge.d("ChannelPanel", "【调试】固定分组[" + groupName + "]渲染(独立源)="
                                + channels.size() + " 条");
                        channelListManager.setFilteredChannels(channels, getPlayingChannelName());
                        // 已经用缓存渲染过就不再强制回到第 0 行，避免焦点跳动
                        if (!hadCached) {
                            lvChannelList.setSelection(0);
                        }
                    }

                    @Override
                    public void onError(String errMsg) {
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                        LogBridge.w("ChannelPanel", "虎牙固定分组[" + groupName + "]加载失败: " + errMsg);

                    }
                });
    }

    // ==================== 【2026-10-03 方案C】固定分组独立数据源 ====================

    /**
     * 【2026-10-24 新增】虎牙第三条线路「任意播」分组加载（完全独立）。
     *
     * <p>频道清单为 {@link HuyaRenYiBoLineManager} 内置静态数据（91 个存活频道），
     * 本地瞬时返回、零网络请求；播放地址由 TVPlayerManager 在起播时经
     * {@code rybhuya://} 协议独立解析（直连任意播 huya.php，跳过自研解析器）。</p>
     *
     * <p>独立性保证：只写入自己的 fixedGroupCache 槽位、独立渲染本组列表，
     * 不并入 channelSourceList 共享池（不参与第一/二路的 roomId 去重）、
     * 不触发 HuyaSDKParser.preloadRooms（不占用两路的探活缓存）。</p>
     */
    private void loadRenYiBoFixedGroup(final String groupName) {
        List<Channel> channels;
        try {
            channels = HuyaRenYiBoLineManager.getInstance().getChannels();
        } catch (Throwable t) {
            LogBridge.w("ChannelPanel", "任意播分组加载异常: " + t.getMessage());
            channels = null;
        }
        if (channels == null || channels.isEmpty()) {
            LogBridge.w("ChannelPanel", "任意播分组无频道");
            channelListManager.setFilteredChannels(new ArrayList<Channel>(), null);
            return;
        }
        loadedFixedGroups.add(groupName);
        cacheFixedGroupChannels(groupName, channels);
        currentGroupChannelList.clear();
        currentGroupChannelList.addAll(channels);
        LogBridge.d("ChannelPanel", "【调试】固定分组[" + groupName + "]渲染(第三条线路·独立源)="
                + channels.size() + " 条");
        channelListManager.setFilteredChannels(channels, getPlayingChannelName());
    }

    /** 固定分组自己的频道列表（按组名缓存），与 channelSourceList 的 roomId 去重互不干扰 */
    private final java.util.Map<String, List<Channel>> fixedGroupCache = new java.util.HashMap<>();

    private void cacheFixedGroupChannels(String groupName, List<Channel> channels) {
        if (groupName == null || channels == null || channels.isEmpty()) return;
        fixedGroupCache.put(groupName, new ArrayList<>(channels));
    }

    private List<Channel> getFixedGroupChannels(String groupName) {
        if (groupName == null) return null;
        return fixedGroupCache.get(groupName);
    }

    /** 判断 groupName 是否为第2路（HuyaTogetherWatchGroupManager）固定分组，
     *  即「虎牙电影/虎牙电视剧/虎牙动漫/虎牙综艺/虎牙其他」之一。 */
    private static boolean isLine2FixedGroup(String g) {
        if (g == null) return false;
        for (String n : HuyaTogetherWatchGroupManager.GROUP_NAMES) {
            if (n.equals(g)) return true;
        }
        return false;
    }

    private void onGroupClicked(int position) {
        long t0 = System.currentTimeMillis();
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        String groupName = groupListManager.getCurrentGroup(position);
        String prevGroupName = currentGroupName;

        // 重复点击当前分组：列表数据没变，重建 adapter 会引发整屏 getView 重绘
        // （实测一次 12~18 行全量重绘）并让焦点回到顶部。这里只把频道列表
        // 滚回正在播放的那一行，不做任何重建/重刷。
        if (groupName != null && groupName.equals(prevGroupName)
                && !currentGroupChannelList.isEmpty()) {
            LogBridge.d("ChannelPanel", "分组[" + groupName + "]重复点击，跳过重建（已渲染 "
                    + currentGroupChannelList.size() + " 条）");
            scrollChannelListToPlaying();
            return;
        }
        currentGroupName = groupName;
        LogBridge.d("ChannelPanel", "切换分组: " + prevGroupName + " -> " + groupName);

        // 【2026-10-21】分组变了，频道列表内容整体替换，旧的行号已失去意义，
        // 清掉频道列表的焦点历史，返回右侧时落到正在播放的频道行。
        if (focusHistory != null) {
            focusHistory.resetPosition(PanelFocusHistory.ZONE_LEFT_CHANNEL);
            focusHistory.resetPosition(PanelFocusHistory.ZONE_RIGHT_CHANNEL);
        }

        if (groupListManager.isFixedGroup(position)) {
            loadFixedGroupChannels(groupName);
            LogBridge.d("ChannelPanel", "切换分组[" + groupName + "]（固定组）耗时 "
                    + (System.currentTimeMillis() - t0) + "ms");
            return;
        }
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else {
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(GroupListManager.getNormalizedGroup(c))) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
        }

        scrollChannelListToPlaying();
        LogBridge.d("ChannelPanel", "切换分组[" + groupName + "]完成: 频道 "
                + currentGroupChannelList.size() + " 条，耗时 "
                + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 把频道列表滚到正在播放的那一行（只滚动、不重建 adapter） */
    private void scrollChannelListToPlaying() {
        if (channelListManager == null || lvChannelList == null) return;
        Channel pc = getPlayingChannelForLocate();
        if (pc == null || pc.getName() == null) return;
        int targetPos = -1;
        // 优先按 name + group 精确匹配（同名房间跨线路时不误中另一条线路）
        for (int i = 0; i < currentGroupChannelList.size(); i++) {
            Channel c = currentGroupChannelList.get(i);
            if (c != null && pc.getName().equals(c.getName())
                    && (pc.getGroup() == null || pc.getGroup().equals(c.getGroup()))) {
                targetPos = i;
                break;
            }
        }
        if (targetPos < 0) {
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                Channel c = currentGroupChannelList.get(i);
                if (c != null && pc.getName().equals(c.getName())) {
                    targetPos = i;
                    break;
                }
            }
        }
        lvChannelList.setSelection(targetPos >= 0 ? targetPos : 0);
    }

    public String getCurrentGroupName() {
        return currentGroupName;
    }

    public List<Channel> getCurrentGroupChannels() {
        return currentGroupChannelList;
    }

    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    public void playPrev() {
        switchWithinCurrentPool(-1);
    }

    /**
     * 在 groupChannels 中定位 currentChannel 的下标。
     *
     * <p>★【2026-10-06 切台卡死修复】原实现只按 {@code getName()} 匹配并 break，
     * 遇到**同名频道**（如内置源5 的「CCTV5体育 / CCTV5体育」，两条不同 ID 同名同流）
     * 会永远命中第一个 → groupIndex 恒定 → 按 ↓/↑ 算出的「下一台」始终是那一条
     * → 表现为**卡在该频道无法前进/后退**。
     * 现改为：① 对象引用 → ② channelId → ③ 名称（最后兜底）。
     */
    private static int indexOfGroupChannel(List<Channel> groupChannels, Channel current) {
        if (groupChannels == null || current == null) return -1;
        int byRef = groupChannels.indexOf(current);
        if (byRef >= 0) return byRef;
        String cid = current.getChannelId();
        if (!TextUtils.isEmpty(cid)) {
            for (int i = 0; i < groupChannels.size(); i++) {
                Channel c = groupChannels.get(i);
                if (c != null && cid.equals(c.getChannelId())) return i;
            }
        }
        String name = current.getName();
        if (!TextUtils.isEmpty(name)) {
            for (int i = 0; i < groupChannels.size(); i++) {
                Channel c = groupChannels.get(i);
                if (c != null && name.equals(c.getName())) return i;
            }
        }
        return -1;
    }

    public void playNext() {
        switchWithinCurrentPool(+1);
    }

    /**
     * 【2026-10-10 修复「虎牙第2路固定分组：点台后再切台会随机跳到别的频道」】
     *
     * <p>原实现只认 {@code channelSourceList} 的全局下标。第2路固定分组频道由
     * {@link #playChannelByObject} 起播时会把 {@code currentPlayIndex} 置 <b>-1</b>
     * （合法状态，含义是"不在全局列表"），而 playNext/playPrev 把 -1 当越界、
     * 强行夹成 {@code channelSourceList.size()-1} → 「当前频道」变成**直播源列表的最后一条**，
     * 再按它的归一化分组去算下一台 → 跳到与正在看的频道毫无关系的组（用户感知＝随机切台）。
     *
     * <p>新口径：
     *   ① 当前频道：第1路下标有效 → {@code channelSourceList.get(currentPlayIndex)}；
     *      否则取 {@code currentPlayingChannel}（第2路/外部频道对象）；
     *      两者都没有才退回旧的"夹到最后一条"兜底；
     *   ② 频道池：第1路维持原口径（channelSourceList 内同归一化分组）；
     *      第2路改用面板**真实在显示**的那份列表（{@code fixedGroupCache} → {@code currentGroupChannelList}）；
     *   ③ 目标频道若在第1路列表里 → {@code playChannel(globalIndex)}；
     *      否则（第2路对象）→ {@code playChannelByObject(...)}，不做 roomId 反查（防命中同 roomId 的第1路频道）。
     *
     * @param dir +1 = 下一个，-1 = 上一个
     */
    private void switchWithinCurrentPool(int dir) {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;

        final boolean hasLiveList = channelSourceList != null && !channelSourceList.isEmpty();
        boolean route1Valid = hasLiveList
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size();

        Channel cur;
        if (route1Valid) {
            cur = channelSourceList.get(currentPlayIndex);
        } else if (currentPlayingChannel != null) {
            cur = currentPlayingChannel;
            // 当前频道其实就在第1路列表里（只是下标丢失）→ 回填下标，回归第1路口径
            int gi = hasLiveList ? channelSourceList.indexOf(cur) : -1;
            if (gi != -1) {
                currentPlayIndex = gi;
                route1Valid = true;
            }
        } else if (hasLiveList) {
            // 兜底（保持旧行为）：既无有效下标、也无当前频道对象
            currentPlayIndex = channelSourceList.size() - 1;
            LogBridge.w("ChannelPanelController", "切台: 无有效当前频道，下标已重置为最后一个有效索引 " + currentPlayIndex);
            cur = channelSourceList.get(currentPlayIndex);
            route1Valid = true;
        } else {
            return;
        }
        if (cur == null) return;

        // ② 选频道池
        List<Channel> pool = new ArrayList<>();
        if (route1Valid) {
            String g = GroupListManager.getNormalizedGroup(cur);
            for (Channel c : channelSourceList) {
                if (g.equals(GroupListManager.getNormalizedGroup(c))) pool.add(c);
            }
        } else if (currentGroupName != null && fixedGroupCache.containsKey(currentGroupName)) {
            // 第2路固定分组：面板实际显示的就是它
            pool.addAll(fixedGroupCache.get(currentGroupName));
        } else if (!currentGroupChannelList.isEmpty()) {
            pool.addAll(currentGroupChannelList);
        }

        int idx = indexOfGroupChannel(pool, cur);
        if (idx == -1) {
            // 当前频道不在所选池里（例如左侧分组被切到"全部"，人还停在第2路频道上）：
            // 退回"当前频道自己所属的固定分组缓存"，避免跨组乱跳
            String ownGroup = cur.getGroup();
            if (ownGroup != null && fixedGroupCache.containsKey(ownGroup)) {
                pool = new ArrayList<>(fixedGroupCache.get(ownGroup));
                idx = indexOfGroupChannel(pool, cur);
            }
        }
        if (idx == -1 || pool.size() <= 1) return;

        Channel target = pool.get((idx + dir + pool.size()) % pool.size());
        if (target == null) return;

        int globalIndex = hasLiveList ? channelSourceList.indexOf(target) : -1;
        if (globalIndex != -1) {
            playChannel(globalIndex);
        } else {
            // 第2路固定分组对象：直接以被点对象起播，避免 roomId 反查命中同 roomId 的第1路频道
            playChannelByObject(target, (idx + dir + pool.size()) % pool.size(), target.getGroup());
        }
        LogBridge.i("ChannelPanelController", "切台 dir=" + dir + "：" + cur.getName() + " -> " + target.getName()
                + "（池=" + pool.size() + "，下标=" + idx + "）");
    }

    public void switchUp() {
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playNext();
        } else {
            playPrev();
        }
    }

    public void switchDown() {
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playPrev();
        } else {
            playNext();
        }
    }

    /** 带"点击来源分组"的播放入口：点击固定分组（虎牙动漫等）频道时传入该分组，
     *  保持用户所选分组上下文，避免频道池按 roomId 去重后只保留"海外动漫"那条
     *  导致面板自动跳到别的组（#跨线路频道池碰撞）。 */
    public void playChannel(int index, String clickGroup) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;
        currentPlayingChannel = ch;

        String channelGroup = GroupListManager.getNormalizedGroup(ch);
        // 点击来自固定分组且本组有独立缓存：保持为该分组，不跳组
        if (clickGroup != null && !clickGroup.isEmpty()
                && fixedGroupCache.containsKey(clickGroup)) {
            channelGroup = clickGroup;
        }
        if (channelGroup != null && !channelGroup.isEmpty()) {
            if (!channelGroup.equals(currentGroupName)) {
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                if (fixedGroupCache.containsKey(channelGroup)) {
                    currentGroupChannelList.addAll(fixedGroupCache.get(channelGroup));
                } else {
                    for (Channel c : channelSourceList) {
                        if (channelGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                            currentGroupChannelList.add(c);
                        }
                    }
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);
            }
        }
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, index);
        } else if (fixedGroupCache.containsKey(currentGroupName)) {
            channelListManager.setFilteredChannels(fixedGroupCache.get(currentGroupName), getPlayingChannelName());
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        }
        syncEpgList();
        syncDateListForCurrentChannel();   // 【2026-10-08】换台（第1路）：按新频道内置源同步日期列表
        epgManagerWrapper.refresh(ch, channelSourceList, currentDateOffset());

        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }
    }

    public void playChannel(int index) {
        playChannel(index, null);
    }

    /** 以指定频道对象起播（不走 channelSourceList 反查）。
     *  用于第2路固定分组：取消共享池后这些频道不在全局 channelSourceList，
     *  若经 indexOfEquivalentChannel 的 roomId 回查会命中第1路同名房间（同 roomId）
     *  导致播错路、信息栏跳成「海外动漫」。直接用被点对象起播，源头消除跨线跳组。 */
    private void playChannelByObject(Channel ch, int displayPosition, String clickGroup) {
        // 第2路固定分组频道不在 channelSourceList，没有合法下标；置 -1 表示「不在全局列表」，
        // 列表定位/高亮改由 currentPlayingChannel 对象负责。
        currentPlayIndex = -1;
        if (ch == null) return;
        currentPlayingChannel = ch;

        String channelGroup = GroupListManager.getNormalizedGroup(ch);
        // 点击来自固定分组且本组有独立缓存：保持为该分组，不跳组
        if (clickGroup != null && !clickGroup.isEmpty()
                && fixedGroupCache.containsKey(clickGroup)) {
            channelGroup = clickGroup;
        }
        if (channelGroup != null && !channelGroup.isEmpty()) {
            if (!channelGroup.equals(currentGroupName)) {
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                if (fixedGroupCache.containsKey(channelGroup)) {
                    currentGroupChannelList.addAll(fixedGroupCache.get(channelGroup));
                } else {
                    for (Channel c : channelSourceList) {
                        if (channelGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                            currentGroupChannelList.add(c);
                        }
                    }
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);
            }
        }
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, displayPosition);
        } else if (fixedGroupCache.containsKey(currentGroupName)) {
            channelListManager.setFilteredChannels(fixedGroupCache.get(currentGroupName), getPlayingChannelName());
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, displayPosition);
        }
        syncEpgList();
        syncDateListForCurrentChannel();   // 【2026-10-08】换台（第2路固定分组）：按新频道内置源同步日期列表
        epgManagerWrapper.refresh(ch, channelSourceList, currentDateOffset());

        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, displayPosition);
        }
    }

    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        return false;
    }

    private void onChannelClicked(int position) {
        Channel selectedChannel = null;

        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
            selectedChannel = currentGroupChannelList.get(position);
        } else if (position < channelSourceList.size()) {
            selectedChannel = channelSourceList.get(position);
        }

        if (selectedChannel == null) return;

        String clickGroup = selectedChannel.getGroup();
        LogBridge.i("ChannelPanel", "频道点击: name=" + selectedChannel.getName()
                + ", 来源分组=" + clickGroup + ", 是否固定分组缓存=" + fixedGroupCache.containsKey(clickGroup));

        // 【根因修复】点击来自第2路固定分组（虎牙动漫等）：取消共享池后这些频道不在
        // channelSourceList，且与其他线路同名房间共享 roomId；若走 indexOfEquivalentChannel
        // 的 roomId 回查会命中第1路对象 → 播错路、显示跳组。直接以被点对象起播，拍到底不再反查。
        if (fixedGroupCache.containsKey(clickGroup)) {
            lastSwitchDirection = "";
            isSwitchingChannel = false;
            autoSkipCount = 0;
            playChannelByObject(selectedChannel, position, clickGroup);
        } else {
            int globalIndex = indexOfEquivalentChannel(selectedChannel);
            if (globalIndex != -1) {
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
                playChannel(globalIndex, clickGroup);
            } else {
                LogBridge.w("ChannelPanel", "频道点击未能定位到源列表: name=" + selectedChannel.getName() + "，已关闭面板");
            }
        }
        // 无论定位成功与否都关闭面板
        togglePanel();
    }

    /** 【2026-10-03】Channel 无 equals/hashCode，直播源刷新与固定分组缓存会各自持有
     *  不同实例，List.indexOf 会因引用不等而返回 -1。按稳定 key 逐级回查等价对象。
     *  语义与 OttHomeActivity.indexOfEquivalent 保持一致。 */
    private int indexOfEquivalentChannel(Channel target) {
        if (target == null) return -1;
        int byRef = channelSourceList.indexOf(target);
        if (byRef >= 0) return byRef;
        String tid = target.getChannelId();
        if (tid != null && !tid.isEmpty()) {
            for (int i = 0; i < channelSourceList.size(); i++) {
                Channel c = channelSourceList.get(i);
                if (c != null && tid.equals(c.getChannelId())) return i;
            }
        }
        String tn = target.getName();
        if (tn == null || tn.isEmpty()) return -1;
        String tg = target.getGroup();
        for (int i = 0; i < channelSourceList.size(); i++) {   // 再按 name + group
            Channel c = channelSourceList.get(i);
            if (c != null && tn.equals(c.getName()) && tg != null && tg.equals(c.getGroup())) return i;
        }
        // 【跨线路频道池碰撞修复】按 roomId 精确回查：虎牙房间真实身份，
        // 同名不同组的房间（如「海外动漫」与「虎牙动漫」的海绵宝宝）应指向同一路流，
        // 避免 name-only 兜底错配到被去重保留在 channelSourceList 里的另一条同名频道。
        int tr = target.getHuyaRoomId();
        if (tr > 0) {
            for (int i = 0; i < channelSourceList.size(); i++) {
                Channel c = channelSourceList.get(i);
                if (c != null && c.getHuyaRoomId() == tr) return i;
            }
        }
        for (int i = 0; i < channelSourceList.size(); i++) {   // 最后只按 name
            Channel c = channelSourceList.get(i);
            if (c != null && tn.equals(c.getName())) return i;
        }
        return -1;
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
        if (index >= 0 && channelSourceList != null && index < channelSourceList.size()) {
            currentPlayingChannel = channelSourceList.get(index);
        }
        // 【2026-10-21】面板关闭状态下换台（遥控器上下键）：旧的行号已指向别的频道，
        // 清掉频道列表的焦点历史，下次打开面板重新定位到正在播放的频道。
        if (focusHistory != null && !isPanelOpen()) {
            focusHistory.resetPosition(PanelFocusHistory.ZONE_LEFT_CHANNEL);
            focusHistory.resetPosition(PanelFocusHistory.ZONE_RIGHT_CHANNEL);
        }
    }

    public void togglePanel() {
        boolean willOpen = !isPanelOpen();

        if (willOpen) {

            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;

            if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || currentGroupName.isEmpty()
                    || currentGroupChannelList.isEmpty()) {
                currentGroupName = GroupListManager.GROUP_ALL;
                currentGroupChannelList.clear();
                currentGroupChannelList.addAll(channelSourceList);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
            } else if (fixedGroupCache.containsKey(currentGroupName)) {
                List<Channel> own = fixedGroupCache.get(currentGroupName);
                currentGroupChannelList.clear();
                currentGroupChannelList.addAll(own);
                channelListManager.setFilteredChannels(own, getPlayingChannelName());
                syncEpgList();
            } else {
                channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
            }
            syncEpgList();
        }

        // 【2026-10-08】打开频道面板：按当前频道内置源同步日期列表
        //（内置咪咕源=过去7天+今天+未来6天；其它源=原7天），再做原有 toggle 流程
        syncDateListForCurrentChannel();
        panelManager.toggle(channelSourceList, channelSourceList.indexOf(currentPlayingChannel), dateListManager);

        panelLayout.postDelayed(() -> {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                LogBridge.d("ChannelPanelController", "togglePanel postDelayed: Activity已销毁，取消操作");
                return;
            }

            if (isPanelOpen()) {
                // 【2026-10-21】优先回到上次离开时停留的左侧区域与位置；
                // 无历史（首次打开）时才落到正在播放的频道行。
                restoreLeftFocus();
            }
        }, 100);

        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(willOpen);
        }
    }

    public void showPanel() {
        if (!isPanelOpen()) {
            togglePanel();
        }
    }

    public void hidePanel() {
        if (isPanelOpen()) {
            togglePanel();
        }
    }

    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    public void handleFirstLaunch() {
        mIsFirstLaunch = false;
    }

    public boolean isFirstLaunch() {
        return mIsFirstLaunch;
    }

    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    private void onEpgButtonClicked() {
        if (!epgEnable) {
            return;
        }
        if (!rightPanelOpen) {
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.GONE);
            }
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = true;
            epgPanelOpen = true;
            syncEpgList();
            syncDateListForCurrentChannel();   // 【2026-10-08】打开EPG面板：按当前频道内置源同步日期列表
            Channel curr = getPlayingChannelForLocate();
            if (curr != null) {
                epgManagerWrapper.refresh(curr, channelSourceList, currentDateOffset());
            }
            panelLayout.post(() -> {
                // 【2026-10-21】回到右侧面板上次停留的区域与位置（无历史时落到右侧频道列表）
                restoreRightFocus();
                LogBridge.d("ChannelPanel", "Right panel opened, restore last right focus");
            });
        } else {
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;

            panelLayout.post(this::restoreLeftFocus);
        }
    }

    public boolean backFromRightPanel() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            panelLayout.post(this::restoreLeftFocus);
            return true;
        }
        return false;
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;

            panelLayout.post(this::restoreLeftFocus);
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);
        // 【2026-10-22 L1-04】原前置条件要求 currentPlayIndex 在 channelSourceList 内，
        //   第2路/VOD 播放时 currentPlayIndex == -1（currentPlayingChannel 单独保存），
        //   整体跳过 → 切日期 EPG 不刷新、「中途切日期作废」场景失效。
        //   改为按实际在播频道取；refresh 内部对 dateIndex != 0 会作废定位待办。
        Channel curr = getPlayingChannelForLocate();
        if (curr != null) {
            epgManagerWrapper.refresh(curr, channelSourceList, currentDateOffset());
        }
    }

    /**
     * 【2026-10-21】日期列点击分支：点击「今天」后，EPG 刷新完成时把焦点自动定位到
     * 当前正在播放的节目行（由 {@code PanelFocusHistory.focusZoneAt} 接管右侧 EPG 区域）。
     *
     * <p>其它日期仍保持原行为：只刷新节目单，不移动焦点。
     * 这里只处理「点击」而非选中变化 —— 焦点移入日期列时系统也会改变选中项，
     * 若按选中变化触发会把焦点误抢到 EPG。
     */
    public void onDateClicked(int position) {
        if (dateListManager == null || !dateListManager.isTodayPosition(position)) return;
        LogBridge.d("ChannelPanel", "日期列点击「今天」→ 请求定位当前播放节目");
        if (epgManagerWrapper != null) {
            epgManagerWrapper.requestFocusPlayingProgram();
        }
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    /**
     * 【2026-10-08】当前选中位 → 日期偏移（今天=0，昨天=-1…）。
     * 日期列表含过去 7 天（内置咪咕源）时「今天」在位置 7，须换算；
     * 原有 7 天列表「今天」在位置 0，偏移=位置 —— 与原逻辑完全兼容。
     */
    private int currentDateOffset() {
        return (dateListManager != null)
                ? dateListManager.dayOffsetOf(currentSelectedDateIndex)
                : currentSelectedDateIndex;
    }

    /**
     * ★【2026-10-08】按当前正在播频道的内置源形态同步日期列表：
     * <ul>
     *   <li>内置咪咕源（频道 id = {@code nowtv:<tvId>}）→ 列表 = <b>过去7天 + 今天 + 未来6天</b>；</li>
     *   <li>其它内置直播源 → 列表保持原有「今天 + 未来6天」，<b>原逻辑不受任何影响</b>。</li>
     * </ul>
     * 列表形态变化时把选中日重置回「今天」（偏移 0）。
     * {@code DateListManager.rebuild} 幂等：形态未变时不做任何事。
     */
    private void syncDateListForCurrentChannel() {
        if (dateListManager == null) return;
        Channel ch = getPlayingChannelForLocate();
        boolean migu = ch != null && com.tv.live.EpgManager.miguTvIdOf(ch) > 0;
        boolean before = dateListManager.isShowingPastDays();
        dateListManager.rebuild(migu);
        if (dateListManager.isShowingPastDays() != before) {
            // 列表形态发生过变化（7天 ↔ 15天）：选中位复位到「今天」
            //（静默，不触发日期选择回调；随后 refresh 用 todayPos 偏移 = 0，与原逻辑一致）
            currentSelectedDateIndex = dateListManager.getTodayPosition();
            panelManager.setCurrentDateIndex(currentSelectedDateIndex);
            dateListManager.resetToToday();
        }
    }

    /** 当前正在播放的频道对象（第2路不在 channelSourceList，单独保存用于定位）。
     *  优先返回 currentPlayingChannel，回退到 currentPlayIndex 反查。 */
    private Channel getPlayingChannelForLocate() {
        if (currentPlayingChannel != null) return currentPlayingChannel;
        if (channelSourceList != null && currentPlayIndex >= 0
                && currentPlayIndex < channelSourceList.size()) {
            return channelSourceList.get(currentPlayIndex);
        }
        return null;
    }

    /** 当前正在播放频道名（用于 setFilteredChannels 按名高亮）。 */
    private String getPlayingChannelName() {
        return (currentPlayingChannel != null) ? currentPlayingChannel.getName() : null;
    }

    /** 右侧 EPG 频道列表始终显示全部频道：第1路 channelSourceList + 第2路固定分组全部频道。
     *  取消共享池后第2路频道不在 channelSourceList，若只渲染 channelSourceList 会导致右侧列表
     *  缺第2路频道、且在播第2路频道时（currentPlayIndex=-1）▶ 停在位置0。故构建合并列表，
     *  并按「正在播对象名」定位高亮。 */
    private void syncEpgList() {
        if (channelListManagerEpg == null) return;
        List<Channel> merged = buildEpgMergedList();
        String playingName = getPlayingChannelName();
        channelListManagerEpg.setFilteredChannels(merged, playingName);
    }

    /** 构建右侧 EPG 全量合并列表：channelSourceList（第1路全部）+ fixedGroupCache 里所有第2路固定分组频道。
     *  按 roomId 去重（第2路频道虽不再并入 channelSourceList，但仍可能因历史原因与第1路同名同 roomId）。 */
    private List<Channel> buildEpgMergedList() {
        List<Channel> merged = new ArrayList<>(channelSourceList);
        java.util.Set<Integer> seenRooms = new java.util.HashSet<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (Channel c : channelSourceList) {
            if (c == null) continue;
            if (c.getHuyaRoomId() > 0) seenRooms.add(c.getHuyaRoomId());
            if (c.getChannelId() != null) seenIds.add(c.getChannelId());
        }
        for (java.util.Map.Entry<String, List<Channel>> e : fixedGroupCache.entrySet()) {
            List<Channel> list = e.getValue();
            if (list == null) continue;
            for (Channel c : list) {
                if (c == null) continue;
                boolean dup = (c.getHuyaRoomId() > 0 && seenRooms.contains(c.getHuyaRoomId()))
                        || (c.getChannelId() != null && seenIds.contains(c.getChannelId()));
                if (dup) continue;
                merged.add(c);
                if (c.getHuyaRoomId() > 0) seenRooms.add(c.getHuyaRoomId());
                if (c.getChannelId() != null) seenIds.add(c.getChannelId());
            }
        }
        epgMergedChannelList.clear();
        epgMergedChannelList.addAll(merged);
        return merged;
    }

    /** 右侧 EPG 列表点击：从 epgMergedChannelList 按 position 取对象。
     *  第2路固定分组频道走 playChannelByObject（绕开 roomId 反查），第1路走 playChannel。 */
    private void onEpgChannelClicked(int position) {
        if (position < 0 || position >= epgMergedChannelList.size()) return;
        Channel ch = epgMergedChannelList.get(position);
        if (ch == null) return;
        String clickGroup = ch.getGroup();
        LogBridge.i("ChannelPanel", "EPG频道点击: name=" + ch.getName()
                + ", 来源分组=" + clickGroup + ", 是否固定分组缓存=" + fixedGroupCache.containsKey(clickGroup));
        if (fixedGroupCache.containsKey(clickGroup)) {
            lastSwitchDirection = "";
            isSwitchingChannel = false;
            autoSkipCount = 0;
            playChannelByObject(ch, position, clickGroup);
        } else {
            int globalIndex = indexOfEquivalentChannel(ch);
            if (globalIndex != -1) {
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
                playChannel(globalIndex, clickGroup);
            } else {
                LogBridge.w("ChannelPanel", "EPG频道点击未能定位到源列表: name=" + ch.getName());
            }
        }
        togglePanel();
    }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            // 「全部」视图只含 channelSourceList（第2路不在其中），按对象反查；找不到则置顶
            int idx = (currentPlayingChannel != null) ? channelSourceList.indexOf(currentPlayingChannel) : -1;
            if (idx >= 0) return idx;
            return (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) ? currentPlayIndex : 0;
        } else {
            Channel pc = getPlayingChannelForLocate();
            if (pc == null) return 0;
            // 优先 name + group 精确匹配（同名房间跨线路时不误中另一条线路）
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                Channel c = currentGroupChannelList.get(i);
                if (c != null && pc.getName() != null && pc.getName().equals(c.getName())
                        && (pc.getGroup() == null || pc.getGroup().equals(c.getGroup()))) {
                    return i;
                }
            }
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                Channel c = currentGroupChannelList.get(i);
                if (c != null && pc.getName() != null && pc.getName().equals(c.getName())) {
                    return i;
                }
            }
            return 0;
        }
    }

    public void onPlaySuccess() {
        isSwitchingChannel = false;
        autoSkipCount = 0;
    }

    public boolean canAutoSkip() {
        return isSwitchingChannel
                && !"".equals(lastSwitchDirection)
                && autoSkipCount < MAX_AUTO_SKIP;
    }

    public boolean autoSkipFailedChannel() {
        if (!canAutoSkip()) {
            return false;
        }
        autoSkipCount++;
        if ("up".equals(lastSwitchDirection)) {
            if (isReverse) {
                playNext();
            } else {
                playPrev();
            }
        } else if ("down".equals(lastSwitchDirection)) {
            if (isReverse) {
                playPrev();
            } else {
                playNext();
            }
        }
        return true;
    }

    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    public boolean isReverse() {
        return isReverse;
    }

    /**
     * 【2026-10-22 L1-09】清除面板焦点历史（原为空实现，MainActivity.onResume 调用无效）。
     *
     * <p>仅在面板处于<b>关闭</b>状态时清理：MainActivity.onResume 在面板仍开着时也会触发
     * （如从设置界面返回），无条件清 7 区历史会把用户面板内的停留位置丢掉。
     * 面板关闭时清理后，下次打开按 focusLastLeft/focusLastRight 的默认分支重新定位。
     */
    public void clearPanelFocus() {
        if (focusHistory == null) return;
        if (isPanelOpen()) return;
        focusHistory.resetAll();
    }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    public void release() {
        LogBridge.d("ChannelPanelController", "release: 级联清理所有组件引用");

        // 【2026-10-22 L1-10】与构造函数注册成对注销（字段化监听，见字段声明处）：
        //   面板销毁后 EpgManager 回调若仍持 panelLayout/各 Manager 引用即泄漏
        try {
            com.tv.live.EpgManager.getInstance().unregisterHuyaEpgReadyListener(huyaEpgReadyListener);
        } catch (Exception ignored) {}
        try {
            com.tv.live.EpgManager.getInstance().unregisterEpgDataReadyListener(epgDataReadyListener);
        } catch (Exception ignored) {}

        // 【2026-10-21】先摘掉面板焦点监听，避免销毁后仍持有已回收的视图
        if (focusHistory != null) {
            focusHistory.detach();
            focusHistory = null;
        }

        // 取消尚未执行的增量刷新，避免 Activity 销毁后回调仍持有引用
        if (pendingMergeRefresh != null) {
            mergeRefreshHandler.removeCallbacks(pendingMergeRefresh);
            pendingMergeRefresh = null;
        }

        loadedFixedGroups.clear();
        if (groupListManager != null) {
            groupListManager.release();
            groupListManager = null;
        }
        if (channelListManager != null) {
            channelListManager.release();
            channelListManager = null;
        }
        if (channelListManagerEpg != null) {
            channelListManagerEpg.release();
            channelListManagerEpg = null;
        }
        if (dateListManager != null) {
            dateListManager.release();
            dateListManager = null;
        }
        if (epgManagerWrapper != null) {
            epgManagerWrapper.release();
            epgManagerWrapper = null;
        }
        if (panelManager != null) {
            panelManager = null;
        }

        channelChangeListener = null;
        panelStateListener = null;

        if (channelSourceList != null) {
            channelSourceList.clear();
            channelSourceList = null;
        }
        if (currentGroupChannelList != null) {
            currentGroupChannelList.clear();
            currentGroupChannelList = null;
        }

        if (lvGroup != null) {
            lvGroup.setAdapter(null);
            lvGroup.setOnItemClickListener(null);
        }
        if (lvChannelList != null) {
            lvChannelList.setAdapter(null);
            lvChannelList.setOnItemClickListener(null);
        }
        if (lvChannelListEpg != null) {
            lvChannelListEpg.setAdapter(null);
            lvChannelListEpg.setOnItemClickListener(null);
        }
        if (lvDate != null) {
            lvDate.setAdapter(null);
            lvDate.setOnItemClickListener(null);
        }
        if (lvEpg != null) {
            lvEpg.setAdapter(null);
            lvEpg.setOnItemClickListener(null);
        }
        if (btnShowEpg != null) {
            btnShowEpg.setOnClickListener(null);
        }
        if (btnBackGroup != null) {
            btnBackGroup.setOnClickListener(null);
        }

        this.activity = null;
        this.context = null;
    }
}
