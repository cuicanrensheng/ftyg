package com.tv.live.manager;

import android.content.Context;
import com.tv.live.util.LogBridge;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
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

    private List<Channel> channelSourceList = new ArrayList<>();
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private String currentGroupName = "";
    private int currentPlayIndex = 0;
    private int currentSelectedDateIndex = 0;

    private boolean epgPanelOpen = false;
    private boolean epgEnable = true;

    private final Set<String> loadedFixedGroups = new HashSet<>();

    private boolean sdkGroupsPreloadStarted = false;

    private volatile boolean sdkGroupsPreloading = false;

    private final ExecutorService sdkPreloadExecutor = Executors.newSingleThreadExecutor();

    private boolean mIsFirstLaunch = true;

    private boolean isReverse = false;
    private long lastChannelChangeTime = 0;

    private String lastSwitchDirection = "";
    private boolean isSwitchingChannel = false;
    private int autoSkipCount = 0;

    private OnChannelChangeListener channelChangeListener;
    private OnPanelStateListener panelStateListener;

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
        initClickListeners();

        try {
            com.tv.live.EpgManager.getInstance().registerHuyaEpgReadyListener(
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
                                epgManagerWrapper.refresh(nowPlaying, channelSourceList, currentSelectedDateIndex);
                                LogBridge.d("ChannelPanel", "🟢【虎牙EPG刷新】解析完成，已刷新右侧面板："
                                        + (nowPlaying.getName() == null ? "" : nowPlaying.getName().substring(0, Math.min(20, nowPlaying.getName().length()))));
                            }
                        } catch (Exception ignored) {}
                    });
                }
            });
        } catch (Exception ignored) {}
    }

    public void updatePanelBackground(int resId) {
        if (llLeftPanel != null) llLeftPanel.setBackgroundResource(resId);
        if (llRightPanel != null) llRightPanel.setBackgroundResource(resId);
    }

    private void initClickListeners() {
        lvGroup.setOnItemClickListener((parent, view, position, id) -> onGroupClicked(position));
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        lvChannelListEpg.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));

        channelListManager.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, false));
        channelListManagerEpg.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, true));

        btnShowEpg.setOnClickListener(v -> onEpgButtonClicked());
        btnShowEpg.setOnFocusChangeListener((v, hasFocus) -> {
            btnShowEpg.setTextColor(hasFocus ? 0xFF40A9FF : 0xFFFFFFFF);
            btnShowEpg.setBackgroundColor(hasFocus ? 0x3340A9FF : 0x00000000);
        });
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
        btnBackGroup.setOnFocusChangeListener((v, hasFocus) -> {
            btnBackGroup.setTextColor(hasFocus ? 0xFF40A9FF : 0xFFFFFFFF);
            btnBackGroup.setBackgroundColor(hasFocus ? 0x3340A9FF : 0x00000000);
        });
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

    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = new java.util.ArrayList<>(channels);
        LogBridge.d("ChannelPanel", "setChannels: 原始频道数=" + channels.size());

        groupListManager.setFixedGroups(HuyaTogetherWatchGroupManager.GROUP_NAMES);

        groupListManager.setPresetGroups(HuyaTogetherWatchManager.HTTP_API_GROUP_NAMES);
        loadedFixedGroups.clear();
        groupListManager.setGroups(this.channelSourceList);

        currentGroupName = GroupListManager.GROUP_ALL;
        currentGroupChannelList.clear();
        currentGroupChannelList.addAll(this.channelSourceList);
        channelListManager.setChannels(this.channelSourceList, currentPlayIndex);
        channelListManagerEpg.setChannels(this.channelSourceList, currentPlayIndex);

        loadHuyaTogetherWatchChannels();

        preloadSdkFixedGroups();
    }

    private void preloadSdkFixedGroups() {
        if (sdkGroupsPreloadStarted) {

            if (loadedFixedGroups.isEmpty() && !sdkGroupsPreloading) {
                sdkPreloadExecutor.execute(() -> doPreloadSdkFixedGroups());
            }
            return;
        }
        sdkGroupsPreloadStarted = true;

        sdkPreloadExecutor.execute(new Runnable() {
            @Override public void run() {
                if (HuyaSDKParser.isSDKAvailable()) {
                    doPreloadSdkFixedGroups();
                } else {
                    HuyaSDKParser.addInitReadyListener(new Runnable() {
                        @Override public void run() {
                            sdkPreloadExecutor.execute(() -> doPreloadSdkFixedGroups());
                        }
                    });
                }
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

                HashSet<String> existIds = new HashSet<>();
                HashSet<Integer> existRooms = new HashSet<>();
                for (Channel c : channelSourceList) {
                    if (c.getChannelId() != null) existIds.add(c.getChannelId());
                    if (c.getHuyaRoomId() > 0) existRooms.add(c.getHuyaRoomId());
                }
                int totalAdded = 0;
                java.util.ArrayList<Integer> twRoomIds = new java.util.ArrayList<>();
                for (HuyaTogetherWatchGroupManager.Group g : groups) {
                    if (g == null || g.name == null || g.channels == null) continue;
                    int added = 0;
                    for (Channel c : g.channels) {
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
                    loadedFixedGroups.add(g.name);
                    LogBridge.d("ChannelPanel", "SDK 分组[" + g.name + "]预加载完成，新增 " + added + " 个频道");
                    totalAdded += added;
                }
                LogBridge.d("ChannelPanel", "✅ SDK 5 大固定分组预加载完成，共新增 " + totalAdded
                        + " 个频道，总计 " + channelSourceList.size()
                        + "，耗时 " + (System.currentTimeMillis() - t0) + "ms");

                if (!twRoomIds.isEmpty()) {
                    HuyaSDKParser.preloadRooms(twRoomIds);
                }

                groupListManager.setGroups(channelSourceList, false);
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
                channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
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
            public void onSuccess(List<Channel> channels) {
                if (channels == null || channels.isEmpty()) {
                    LogBridge.d("ChannelPanel", "虎牙一起看: 未获取到频道");
                    return;
                }
                panelLayout.post(() -> {
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

                    java.util.HashSet<String> existIds = new java.util.HashSet<>();
                    for (Channel c : channelSourceList) {
                        if (c.getChannelId() != null) existIds.add(c.getChannelId());
                    }
                    int added = 0;
                    java.util.ArrayList<Integer> twRoomIds = new java.util.ArrayList<>();
                    for (Channel c : channels) {
                        if (c == null) continue;
                        if (c.getChannelId() != null && !existIds.contains(c.getChannelId())) {
                            channelSourceList.add(c);
                            existIds.add(c.getChannelId());
                            added++;

                            if (c.getHuyaRoomId() > 0) twRoomIds.add(c.getHuyaRoomId());
                        }
                    }
                    LogBridge.d("ChannelPanel", "虎牙一起看加载完成，新增 " + added + " 个频道，总计 " + channelSourceList.size());
                    if (!twRoomIds.isEmpty()) {
                        LogBridge.d("ChannelPanel", "🟢【虎牙预解析】(一起看) 收集到 " + twRoomIds.size() + " 个房间，开始后台并行预解析");
                        com.tv.live.util.HuyaSDKParser.preloadRooms(twRoomIds);
                    }

                    groupListManager.setGroups(channelSourceList, false);

                    currentGroupName = GroupListManager.GROUP_ALL;
                    currentGroupChannelList.clear();
                    currentGroupChannelList.addAll(channelSourceList);
                    channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
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

        if (loadedFixedGroups.contains(groupName)) {
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(GroupListManager.getNormalizedGroup(c))) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            return;
        }

        currentGroupChannelList.clear();
        channelListManager.setFilteredChannels(new ArrayList<Channel>(), null);

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
                        LogBridge.d("ChannelPanel", "虎牙固定分组[" + groupName + "]加载完成，新增 " + added + " 个频道，总计 " + channelSourceList.size());

                        if (!twRoomIds.isEmpty()) {
                            com.tv.live.util.HuyaSDKParser.preloadRooms(twRoomIds);
                        }

                        groupListManager.setGroups(channelSourceList, false);

                        currentGroupChannelList.clear();
                        for (Channel c : channelSourceList) {
                            if (groupName.equals(GroupListManager.getNormalizedGroup(c))) {
                                currentGroupChannelList.add(c);
                            }
                        }
                        channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
                        lvChannelList.setSelection(0);
                    }

                    @Override
                    public void onError(String errMsg) {
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                        LogBridge.w("ChannelPanel", "虎牙固定分组[" + groupName + "]加载失败: " + errMsg);

                    }
                });
    }

    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;

        if (groupListManager.isFixedGroup(position)) {
            loadFixedGroupChannels(groupName);
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

        if (channelListManager != null && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            int targetPos = -1;
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    targetPos = i;
                    break;
                }
            }
            if (targetPos >= 0) {
                lvChannelList.setSelection(targetPos);
            } else {
                lvChannelList.setSelection(0);
            }
        }
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
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }

        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
            currentPlayIndex = channelSourceList.size() - 1;
            LogBridge.w("ChannelPanelController", "playPrev: currentPlayIndex 越界，已重置为最后一个有效索引 " + currentPlayIndex);
        }

        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = GroupListManager.getNormalizedGroup(currentChannel);
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);
        int globalIndex = channelSourceList.indexOf(prevChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }

        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
            currentPlayIndex = channelSourceList.size() - 1;
            LogBridge.w("ChannelPanelController", "playNext: currentPlayIndex 越界，已重置为最后一个有效索引 " + currentPlayIndex);
        }

        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = GroupListManager.getNormalizedGroup(currentChannel);
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);
        int globalIndex = channelSourceList.indexOf(nextChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
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

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;

        String channelGroup = GroupListManager.getNormalizedGroup(ch);
        if (channelGroup != null && !channelGroup.isEmpty()) {
            if (!channelGroup.equals(currentGroupName)) {
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (channelGroup.equals(GroupListManager.getNormalizedGroup(c))) {
                        currentGroupChannelList.add(c);
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
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        }
        channelListManagerEpg.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
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

        int globalIndex = channelSourceList.indexOf(selectedChannel);
        if (globalIndex != -1) {
            lastSwitchDirection = "";
            isSwitchingChannel = false;
            autoSkipCount = 0;
            playChannel(globalIndex);
            togglePanel();
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
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
            } else {
                channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
            }
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        }

        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        panelLayout.postDelayed(() -> {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                LogBridge.d("ChannelPanelController", "togglePanel postDelayed: Activity已销毁，取消操作");
                return;
            }

            if (isPanelOpen()) {

                lvChannelList.requestFocus();

                lvChannelList.setSelection(getChannelListSelection());
            } else {

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
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
            panelLayout.post(() -> {
                lvChannelListEpg.requestFocus();
                LogBridge.d("ChannelPanel", "Right panel opened, focus on channel list");
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

            panelLayout.post(() -> lvChannelList.requestFocus());
        }
    }

    public boolean backFromRightPanel() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            panelLayout.post(() -> {
                if (lvChannelList != null) lvChannelList.requestFocus();
            });
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

            panelLayout.post(() -> lvChannelList.requestFocus());
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);
        if (!channelSourceList.isEmpty()
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            return currentPlayIndex;
        } else {
            if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
                return 0;
            }
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
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

    public void clearPanelFocus() {

    }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    public void release() {
        LogBridge.d("ChannelPanelController", "release: 级联清理所有组件引用");

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
