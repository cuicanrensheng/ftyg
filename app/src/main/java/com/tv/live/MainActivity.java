package com.tv.live;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import com.tv.live.util.LogBridge;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.PlayerView;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.util.LifecycleHelper;
import com.tv.live.util.LogCollector;
import com.tv.live.util.RemoteKeyHandler;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

@SuppressLint("UnsafeOptInUsageError")
public class MainActivity extends AppCompatActivity {
    private static WeakReference<MainActivity> mInstanceRef;

    public List<Channel> channelSourceList = new ArrayList<>(512);
    public int currentPlayIndex = 0;

    private PlayerView playerView;
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
    private PlayerStateListenerImpl playerStateListener;
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private ChannelPanelController channelPanelController;
    private AppCoreManager appCoreManager;
    private PictureInPictureManager pipManager;
    private View panelLayout;

    private PlayerControlManager playerControlManager;

    private RemoteKeyHandler remoteKeyHandler;
    private LifecycleHelper lifecycleHelper;

    private boolean pipEnable = false;
    private boolean channel_reverse;
    private boolean number_channel_enable;

    private boolean isOpeningSettings = false;

    private boolean settingsNeedReload = true;
    private SettingsDialog settingsDialog;
    private long settingsCloseTime = 0;
    private long lastSettingsOpenTime = 0;
    private long okKeyDownTime = 0;
    private boolean okKeyTriggered = false;
    private boolean okKeyLongPressed = false;
    private static final long OK_LONG_PRESS_DURATION = 1500;

    private long mLastBackHandleMs = 0L;

    private Object mSystemBackInvokedCb = null;

    private ContentObserver autoRotateObserver = null;

    private static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == 100;
    }

    private static boolean isMenuKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_HELP
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == 101;
    }

    private static boolean isChannelUpKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    private static boolean isChannelDownKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    private Runnable openSettingsRunnable = () -> {
        okKeyLongPressed = true;
        okKeyTriggered = true;
        openSettings();
    };

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private View logWindowContainer;
    private ScrollView logScrollView;
    private TextView tvLogContent;
    private boolean logWindowVisible = false;
    private Runnable logUpdateRunnable;

    private boolean isInCatchUpMode = false;

    private final StringBuilder numberInputBuffer = new StringBuilder();
    private final Runnable numberInputConfirmTask = () -> confirmNumberInputJump();

    private static final long SEEK_REPEAT_DELAY_MS = 400;
    private static final long SEEK_REPEAT_INTERVAL_MS = 200;
    private int longPressKeyCode = -1;
    private final Runnable longPressSeekRunnable = new Runnable() {
        @Override
        public void run() {
            if (longPressKeyCode == -1 || !isInCatchUpMode) return;
            if (playerControlManager == null) {
                longPressKeyCode = -1;
                return;
            }
            if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                playerControlManager.seekBackward();
            } else if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                playerControlManager.seekForward();
            }
            mMainHandler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS);
        }
    };

    private AlertDialog exitMenuDialog = null;

    private BroadcastReceiver unlockReceiver;

    public static MainActivity getRunningInstance() {
        return mInstanceRef != null ? mInstanceRef.get() : null;
    }

    public boolean isInCatchUpMode() {
        return isInCatchUpMode;
    }

    public TVPlayerManager getPlayerManager() {
        return mPlayerManager;
    }

    public PictureInPictureManager getPipManager() {
        return pipManager;
    }

    private PlayerTouchListener touchListener;
    public PlayerTouchListener getTouchListener() {
        return touchListener;
    }

    public PlayerView getPlayerView() {
        return playerView;
    }

    private void applyAutoRotateOrientation() {
        boolean autoRotate = false;
        try {

            autoRotate = Settings.System.getInt(
                    getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        } catch (Exception ignored) {
        }
        setRequestedOrientation(autoRotate
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        com.tv.live.util.AppExecutors.io(() -> SecurityCheck.verifyOnStart(this));
        mInstanceRef = new WeakReference<>(this);
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        applyAutoRotateOrientation();

        autoRotateObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                applyAutoRotateOrientation();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                    true, autoRotateObserver);
        } catch (Exception ignored) {
        }
        displayManager = new DisplayManager(this);
        setContentView(R.layout.activity_main);
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        logScrollView = findViewById(R.id.log_scroll_view);
        tvLogContent = findViewById(R.id.tv_log_content);

        initInfoDisplayManager();
        appConfig = AppConfig.getInstance(this);

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null && !customLive.isEmpty()) {
            UrlConfig.LIVE_URL = customLive;
        } else {

            SourceManager liveMgr = new SourceManager(this, "live_history");
            String defaultLive = liveMgr.getDefaultUrl();
            if (defaultLive != null && !defaultLive.isEmpty()) {
                UrlConfig.LIVE_URL = defaultLive;
            }
        }
        if (customEpg != null && !customEpg.isEmpty()) {
            UrlConfig.EPG_URL = customEpg;
        } else {
            SourceManager epgMgr = new SourceManager(this, "epg_history");
            String defaultEpg = epgMgr.getDefaultUrl();
            if (defaultEpg != null && !defaultEpg.isEmpty()) {
                UrlConfig.EPG_URL = defaultEpg;
            }
        }
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);
        try {
            playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        } catch (Exception e) {}

        initChannelPanelController();
        int savedBg = getSharedPreferences("app_settings", MODE_PRIVATE).getInt("panel_background", 0);
        applyPanelBackground(savedBg);
        initPictureInPicture();
        channelPanelController.handleFirstLaunch();

        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        mPlayerManager.registerRendererModeReceiver();

        loadSettings();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);

        initAppCoreManager();

        com.tv.live.util.AppExecutors.io(appCoreManager::loadLiveAndEpg);

        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.UNLOCK_SETTINGS".equals(intent.getAction())) {
                    isOpeningSettings = false;
                    settingsNeedReload = true;
                    settingsCloseTime = System.currentTimeMillis();
                    LogBridge.d("MainActivity", "📡 收到解锁广播，isOpeningSettings 已重置");
                }
            }
        };
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            new IntentFilter("com.tv.live.UNLOCK_SETTINGS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );

        setupBackPressHandling();

        initSubModules();
    }

    private void initSubModules() {
        lifecycleHelper = new LifecycleHelper(this);
        lifecycleHelper.setInfoDisplayManager(() -> { if (infoDisplayManager != null) infoDisplayManager.release(); });
        lifecycleHelper.setDisplayManager(() -> { if (displayManager != null) displayManager.release(); });
        lifecycleHelper.setChannelPanelController(() -> { if (channelPanelController != null) channelPanelController.release(); });
        lifecycleHelper.setAppCoreManager(() -> { if (appCoreManager != null) appCoreManager.release(); });
        lifecycleHelper.setPipManager(() -> { if (pipManager != null) pipManager.release(); });
        lifecycleHelper.setPlayerControlManager(() -> { if (playerControlManager != null) playerControlManager.release(); });
        lifecycleHelper.setPlayerManager(() -> {
            if (mPlayerManager != null) {
                mPlayerManager.setOnPlayStateListener(null);
                mPlayerManager.setOnLiveInfoUpdateListener(null);
                mPlayerManager.setOnSourceFailedListener(null);
                mPlayerManager.release();
            }
        });
        lifecycleHelper.setUnlockReceiver(unlockReceiver);
        lifecycleHelper.setTouchListenerSource(playerView);

        remoteKeyHandler = new RemoteKeyHandler(new RemoteKeyHandler.OnKeyAction() {
            @Override
            public void onMenuKey() {
                openSettings();
            }
            @Override
            public void onOkKey() {
                if (channelPanelController != null) channelPanelController.togglePanel();
            }
            @Override
            public void onChannelUp() {
                if (channelPanelController != null) channelPanelController.switchUp();
            }
            @Override
            public void onChannelDown() {
                if (channelPanelController != null) channelPanelController.switchDown();
            }
            @Override
            public void onSeekBackward() {
                if (playerControlManager != null) playerControlManager.seekBackward();
            }
            @Override
            public void onSeekForward() {
                if (playerControlManager != null) playerControlManager.seekForward();
            }
            @Override
            public void onPlayPause() {
                if (mPlayerManager != null) {
                    if (mPlayerManager.isPlaying()) mPlayerManager.pause();
                    else mPlayerManager.resume();
                }
            }
            @Override
            public void onStop() {
                if (mPlayerManager != null) mPlayerManager.pause();
            }
            @Override
            public void onBackKey() {
                if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                    channelPanelController.hidePanel();
                } else {
                    onBackPressed();
                }
            }
        });

        remoteKeyHandler.setNumberInputCallback(input -> {
            if (infoDisplayManager != null) {
                infoDisplayManager.showChannelNumInput(input);
            }
        });
        remoteKeyHandler.setNumberInputEnabled(number_channel_enable);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            return super.onKeyDown(keyCode, event);
        } catch (Exception e) {
            LogBridge.e("KEY_DEBUG", "onKeyDown 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
            return true;
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        try {
            return super.onKeyUp(keyCode, event);
        } catch (Exception e) {
            LogBridge.e("KEY_DEBUG", "onKeyUp 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
            return true;
        }
    }

    private boolean panelOpenOnBackDown = false;

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (channelPanelController == null || mPlayerManager == null || event == null) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean panelOpen;
        try {
            panelOpen = channelPanelController.isPanelOpen();
        } catch (Exception e) {
            LogBridge.e("KEY_DEBUG", "isPanelOpen 异常: " + e.getMessage(), e);
            panelOpen = false;
        }

        try {
            LogBridge.d("KEY_DEBUG", "keyCode=" + keyCode + " action=" + action + " repeat=" + event.getRepeatCount());

            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    if (isMenuKey(keyCode)) {
                        if (panelOpen) {
                            channelPanelController.hidePanel();
                        }
                        openSettings();
                        return true;
                    }

                    if (isOkKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.togglePanel();
                            return true;
                        }
                    }

                    if (isChannelUpKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.switchUp();
                            return true;
                        }
                    } else if (isChannelDownKey(keyCode)) {
                        if (!panelOpen) {
                            channelPanelController.switchDown();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (isInCatchUpMode && playerControlManager != null) {

                            if (event.getRepeatCount() == 0) {
                                playerControlManager.seekBackward();
                                longPressKeyCode = keyCode;
                                mMainHandler.removeCallbacks(longPressSeekRunnable);
                                mMainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                            }
                            return true;
                        }
                        if (!panelOpen) {
                            channelPanelController.togglePanel();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (isInCatchUpMode && playerControlManager != null) {
                            if (event.getRepeatCount() == 0) {
                                playerControlManager.seekForward();
                                longPressKeyCode = keyCode;
                                mMainHandler.removeCallbacks(longPressSeekRunnable);
                                mMainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                            }
                            return true;
                        }
                        if (!panelOpen) {
                            openSettings();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (mPlayerManager != null) {
                            if (mPlayerManager.isPlaying()) {
                                mPlayerManager.pause();
                            } else {
                                mPlayerManager.resume();
                            }
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                        if (mPlayerManager != null) {
                            mPlayerManager.pause();
                        }
                        return true;
                    } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                        handleNumberKey(keyCode);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        panelOpenOnBackDown = panelOpen;
                        LogBridge.d("KEY_DEBUG", "Back DOWN: panelOpen=" + panelOpen + ", panelOpenOnBackDown=" + panelOpenOnBackDown);
                        if (panelOpen) {
                            channelPanelController.hidePanel();
                        }
                        if (settingsDialog != null && settingsDialog.isShowing()) {
                            return super.dispatchKeyEvent(event);
                        }
                        return true;
                    }
                }
            } else if (action == KeyEvent.ACTION_UP) {

                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (longPressKeyCode == keyCode) {
                        longPressKeyCode = -1;
                        mMainHandler.removeCallbacks(longPressSeekRunnable);
                    }
                }
                if (isOkKey(keyCode)) {
                    okKeyLongPressed = false;
                    okKeyTriggered = false;
                    okKeyDownTime = 0;
                    if (!panelOpen) {
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    LogBridge.d("KEY_DEBUG", "Back UP: panelOpenOnBackDown=" + panelOpenOnBackDown + ", panelOpen=" + panelOpen);
                    if (settingsDialog != null && settingsDialog.isShowing()) {
                        return super.dispatchKeyEvent(event);
                    }
                    if (!panelOpenOnBackDown && !panelOpen) {
                        LogBridge.d("KEY_DEBUG", "Calling onBackPressed()");
                        onBackPressed();
                    }
                    return true;
                }
            }

            if (panelOpen && panelLayout != null) {
                if (panelLayout.dispatchKeyEvent(event)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LogBridge.e("KEY_DEBUG", "dispatchKeyEvent 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
        }

        return super.dispatchKeyEvent(event);
    }

    private void handleNumberKey(int keyCode) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (!number_channel_enable) return;

        int num = keyCode - KeyEvent.KEYCODE_0;
        if (num < 0 || num > 9) return;

        mMainHandler.removeCallbacks(numberInputConfirmTask);

        numberInputBuffer.append(num);

        if (numberInputBuffer.length() > 4) {
            numberInputBuffer.delete(0, numberInputBuffer.length() - 4);
        }

        if (infoDisplayManager != null) {
            infoDisplayManager.showChannelNumInput(numberInputBuffer.toString());
        }

        mMainHandler.postDelayed(numberInputConfirmTask, 1500);
    }

    private void confirmNumberInputJump() {
        if (numberInputBuffer.length() == 0) return;

        try {
            int channelNum = Integer.parseInt(numberInputBuffer.toString());
            numberInputBuffer.setLength(0);

            if (channelNum <= 0) return;

            int targetIndex = channelNum - 1;
            if (targetIndex < 0) targetIndex = 0;
            if (targetIndex >= channelSourceList.size()) {
                targetIndex = channelSourceList.size() - 1;
            }

            playChannel(channelSourceList.get(targetIndex), targetIndex);
        } catch (NumberFormatException e) {
            numberInputBuffer.setLength(0);
        }
    }

    public void showLogWindow() {
        if (logWindowVisible) return;
        logWindowVisible = true;
        logWindowContainer.setVisibility(View.VISIBLE);
        startLogUpdate();
    }

    public void hideLogWindow() {
        if (!logWindowVisible) return;
        logWindowVisible = false;
        logWindowContainer.setVisibility(View.GONE);
        stopLogUpdate();
    }

    private void startLogUpdate() {
        if (logUpdateRunnable != null) return;
        logUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!logWindowVisible) {
                    stopLogUpdate();
                    return;
                }

                String logs = LogCollector.getInstance().getAllLogs();

                if (logs.contains(LogCollector.DIVIDER_TOKEN)) {
                    int viewWidth = tvLogContent.getMeasuredWidth();
                    int eqCount = 30;

                    if (viewWidth > 0) {

                        float eqWidth = tvLogContent.getPaint().measureText("=");
                        eqCount = (int) (viewWidth / eqWidth);
                        if (eqCount > 200) eqCount = 200;
                    }

                    String eqString = new String(new char[eqCount]).replace('\0', '=');

                    logs = logs.replace(LogCollector.DIVIDER_TOKEN, eqString);
                }

                tvLogContent.setText(logs);
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                mMainHandler.postDelayed(this, 300);
            }
        };
        mMainHandler.post(logUpdateRunnable);
    }

    private void stopLogUpdate() {
        if (logUpdateRunnable != null) {
            mMainHandler.removeCallbacks(logUpdateRunnable);
            logUpdateRunnable = null;
        }
    }

    public static void toggleLogWindow(boolean enable) {
        MainActivity activity = getRunningInstance();
        if (activity != null) {
            if (enable) {
                activity.showLogWindow();
            } else {
                activity.hideLogWindow();
            }
        }
    }

    public void setCatchUpMode(boolean enabled) {
        this.isInCatchUpMode = enabled;
        if (!enabled) {

            longPressKeyCode = -1;
            mMainHandler.removeCallbacks(longPressSeekRunnable);
        }
    }

    public ChannelPanelController getChannelPanelController() {
        return channelPanelController;
    }

    public void showExoController() {
        if (playerControlManager != null) {
            playerControlManager.showExoController();
        }
    }

    public void hideExoController() {
        if (playerControlManager != null) {
            playerControlManager.hideExoController();
        }
    }

    private void exitPlaybackMode() {
        if (isInCatchUpMode) {
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                if (ch != null && mPlayerManager != null) {
                    mPlayerManager.playUrl(ch.getPlayUrl(), ch.getName(), ch);
                    TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
                    if (infoDisplayManager != null && live != null) {
                        infoDisplayManager.showInfoBar(ch, live);
                    }
                }
            }
            hideExoController();
            isInCatchUpMode = false;
        } else {
            if (playerControlManager != null && playerControlManager.isControllerShowing()) {
                hideExoController();
            }
        }
    }

    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            pipManager.setPipEnabled(pipEnable);
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });
            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    private void initInfoDisplayManager() {
        TextView tv_channel_num = findViewById(R.id.tv_channel_num);
        View info_bar = findViewById(R.id.info_bar);
        TextView tv_channel_name = findViewById(R.id.tv_channel_name);
        TextView tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        TextView tv_tag_audio = findViewById(R.id.tv_tag_audio);
        TextView tv_bitrate = findViewById(R.id.tv_bitrate);
        TextView tv_current_program_name = findViewById(R.id.tv_current_program_name);
        TextView tv_current_time_range = findViewById(R.id.tv_current_time_range);
        ProgressBar progress_program = findViewById(R.id.progress_program);
        TextView tv_remaining_time = findViewById(R.id.tv_remaining_time);
        TextView tv_next_program_name = findViewById(R.id.tv_next_program_name);
        TextView tv_next_time_range = findViewById(R.id.tv_next_time_range);
        infoDisplayManager = new InfoDisplayManager(
                this, tv_channel_num, info_bar, tv_channel_name, tv_tag_fhd, tv_tag_audio,
                tv_bitrate, tv_current_program_name, tv_current_time_range, progress_program,
                tv_remaining_time, tv_next_program_name, tv_next_time_range
        );
    }

    private void initChannelPanelController() {
        panelLayout = findViewById(R.id.panel_layout);
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        EpgManager.getInstance(this);
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panelLayout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> channelPanelController.setCurrentDateIndex(pos));

        channelPanelController = new ChannelPanelController(
                this, panelLayout, ll_left_panel, ll_right_panel, lvGroup, lvChannelList,
                lvChannelListEpg, lvDate, lvEpg, btn_show_epg, btn_back_group,
                groupListManager, channelListManager, channelListManagerEpg,
                dateListManager, epgManagerWrapper, panelManager
        );

        channelPanelController.setOnChannelChangeListener((channel, index) -> playChannel(channel, index));
    }

    public void applyPanelBackground(int bgIndex) {
        int resId;
        switch (bgIndex) {
            case 1: resId = R.drawable.panel_bg_1; break;
            case 2: resId = R.drawable.panel_bg_2; break;
            case 3: resId = R.drawable.panel_bg_3; break;
            case 4: resId = R.drawable.panel_bg_4; break;
            default: resId = R.drawable.panel_bg; break;
        }
        panelLayout.setBackgroundColor(0x00000000);
        if (channelPanelController != null) channelPanelController.updatePanelBackground(resId);
    }

    public static class PlayerTouchListener implements View.OnTouchListener {
        private final WeakReference<MainActivity> activityRef;
        private PlayerGestureHelper gestureHelper;

        public PlayerTouchListener(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public void updateGestureHelper(PlayerGestureHelper helper) {
            this.gestureHelper = helper;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (gestureHelper != null) {
                gestureHelper.handleTouch(event);
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return true;
        }
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);

        gestureManager = new GestureManager(this);
        playerControlManager = new PlayerControlManager(this, gestureManager, infoDisplayManager);

        mPlayerManager.setOnPlayerViewRecreatedListener(newPlayerView -> {
            MainActivity.this.playerView = newPlayerView;

            gestureManager = new GestureManager(MainActivity.this);
            final PlayerGestureHelper newGestureHelper = gestureManager.create();

            if (playerControlManager != null) {
                playerControlManager.updateGestureManager(gestureManager);
            }

            if (touchListener == null) {
                touchListener = new PlayerTouchListener(MainActivity.this);
            }
            touchListener.updateGestureHelper(newGestureHelper);
            newPlayerView.setOnTouchListener(touchListener);

            if (playerControlManager != null) {
                newPlayerView.setUseController(false);
                playerControlManager.hideExoController();
            }

            LogBridge.d("MainActivity", "PlayerView 重建完成");
        });

        mPlayerManager.attachPlayerView(playerView);

        touchListener = new PlayerTouchListener(MainActivity.this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        touchListener.updateGestureHelper(gestureHelper);
        playerView.setOnTouchListener(touchListener);

        FrameLayout sdkContainer = mPlayerManager.getSdkPlayerContainer();
        if (sdkContainer != null) {
            sdkContainer.setOnTouchListener(touchListener);
            sdkContainer.setClickable(true);
            sdkContainer.setFocusable(false);
            LogBridge.d("MainActivity", "SDK 播放器容器手势监听已绑定");
        }

        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            infoDisplayManager.updateLiveInfo(info);
            if (pipManager != null) pipManager.updatePlayState(true);
        });

        mPlayerManager.setOnSourceFailedListener(() -> mMainHandler.post(() -> {
            String channelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                if (ch != null) channelName = ch.getName();
            }
            appCoreManager.handleSourceFailed(channelName);
        }));
    }

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {

                mMainHandler.post(() -> {
                    List<Channel> finalList = appCoreManager.getChannelList();
                    channelSourceList.clear();
                    channelSourceList.addAll(finalList);
                    channelPanelController.setChannels(channelSourceList);

                    if (!channelSourceList.isEmpty()) {

                        String lastChannelName = null;
                        if (mPlayerManager != null
                                && mPlayerManager.getCurrentChannel() != null) {
                            lastChannelName = mPlayerManager.getCurrentChannel().getName();
                        }

                        if (TextUtils.isEmpty(lastChannelName)) {
                            int savedIdx = appConfig.getLastPlayIndex();
                            if (savedIdx >= 0 && savedIdx < channelSourceList.size()) {
                                lastChannelName = channelSourceList.get(savedIdx).getName();
                            }
                        }

                        int matchedIndex = -1;
                        if (!TextUtils.isEmpty(lastChannelName)) {
                            for (int i = 0; i < channelSourceList.size(); i++) {
                                if (lastChannelName.equals(channelSourceList.get(i).getName())) {
                                    matchedIndex = i;
                                    break;
                                }
                            }
                        }
                        if (matchedIndex >= 0) {
                            currentPlayIndex = matchedIndex;
                        } else if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {

                            int savedIdx = appConfig.getLastPlayIndex();
                            if (savedIdx >= 0 && savedIdx < channelSourceList.size()) {
                                currentPlayIndex = savedIdx;
                            } else {
                                currentPlayIndex = 0;
                            }
                        }
                    } else {
                        currentPlayIndex = 0;
                    }
                    appConfig.setLastPlayIndex(currentPlayIndex);
                    channelPanelController.setCurrentPlayIndex(currentPlayIndex);

                    appCoreManager.setHasPlayedWithCache(true);

                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel ch = channelSourceList.get(currentPlayIndex);
                        if (ch != null && !TextUtils.isEmpty(ch.getPlayUrl())) {

                            boolean shouldPlay = true;
                            Channel playingChannel = (mPlayerManager != null) ? mPlayerManager.getCurrentChannel() : null;
                            if (playingChannel != null) {
                                String playingName = playingChannel.getName();
                                String chName = ch.getName();
                                if (playingName != null && playingName.equals(chName)) {
                                    shouldPlay = false;
                                    log("【" + (fromCache ? "缓存" : "网络") + "】频道 #" + currentPlayIndex + "（" + chName + "）已在播放，跳过重复加载");
                                    channelPanelController.setCurrentPlayIndex(currentPlayIndex);
                                    appConfig.setLastPlayIndex(currentPlayIndex);
                                }
                            }
                            if (shouldPlay) {
                                log("【" + (fromCache ? "缓存" : "网络") + "】自动播放频道 #" + currentPlayIndex + "：" + ch.getName());
                                playChannel(ch, currentPlayIndex);
                            }
                        } else {
                            log("【" + (fromCache ? "缓存" : "网络") + "】⚠️ 当前索引频道无播放地址，尝试从头播放");

                            for (int i = 0; i < channelSourceList.size(); i++) {
                                Channel fallback = channelSourceList.get(i);
                                if (fallback != null && !TextUtils.isEmpty(fallback.getPlayUrl())) {
                                    currentPlayIndex = i;
                                    appConfig.setLastPlayIndex(i);
                                    channelPanelController.setCurrentPlayIndex(i);
                                    playChannel(fallback, i);
                                    break;
                                }
                            }
                        }
                    }
                    log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channelSourceList.size());
                });
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                mMainHandler.post(() -> {
                    if (channelSourceList.isEmpty()) {
                        Toast.makeText(MainActivity.this, "加载失败，请检查网络或稍后重试", Toast.LENGTH_LONG).show();
                    } else {
                        log("【网络】加载失败，继续使用现有数据（频道数" + channelSourceList.size() + "）");
                    }
                });
            }

            @Override
            public void onEpgLoaded() {

                mMainHandler.post(() -> {
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel curr = channelSourceList.get(currentPlayIndex);
                        infoDisplayManager.updateEpgInfo(curr);
                    }
                });
            }

            @Override
            public void onLoadTimeout(boolean hasData) {

                mMainHandler.post(() -> {
                    log("【加载】超时（当前已" + (hasData ? "有缓存数据" : "空列表") + "），后台将继续加载");
                });
            }
        });

        appCoreManager.setOnSourceSkipListener(new AppCoreManager.OnSourceSkipListener() {
            @Override
            public void onNeedSkipChannel() { channelPanelController.switchDown(); }
            @Override
            public void onSkipLimitReached(int maxSkip) {
                Toast.makeText(MainActivity.this, "已跳过 " + maxSkip + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onSourceFailed(String channelName, int failedCount) {}
        });

        appCoreManager.setOnRefreshListener(() -> {

            mMainHandler.post(() -> {
                List<Channel> newList = appCoreManager.getChannelList();

                if (newList == null || newList.isEmpty()) {
                    log("【刷新】频道列表尚未就绪，等待 onLiveSourceLoaded 加载完成后自动播放");
                    return;
                }
                channelSourceList.clear();
                channelSourceList.addAll(newList);
                channelPanelController.setChannels(channelSourceList);

                if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
                    currentPlayIndex = 0;
                }
                appConfig.setLastPlayIndex(currentPlayIndex);
                channelPanelController.setCurrentPlayIndex(currentPlayIndex);
                if (!channelSourceList.isEmpty()) {
                    playChannel(channelSourceList.get(currentPlayIndex), currentPlayIndex);
                }
                log("【刷新】频道列表已更新，频道数：" + channelSourceList.size());
            });
        });

        appCoreManager.registerReceivers();
    }

    private void loadSettings() {

        if (!settingsNeedReload) {
            return;
        }
        settingsNeedReload = false;
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);

        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_HARD;
        } else if ("soft".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_SOFT;
        } else {
            mode = TVPlayerManager.DECODER_MODE_AUTO;
        }

        if (mPlayerManager != null) mPlayerManager.setDecoderMode(mode);
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
    }

    public boolean isChannelReverse() { return channel_reverse; }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;

        numberInputBuffer.setLength(0);
        mMainHandler.removeCallbacks(numberInputConfirmTask);

        if (isInCatchUpMode) {
            exitPlaybackMode();
        }

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName(), channel);
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        if (infoDisplayManager != null) {
            infoDisplayManager.showInfoBar(channel, live);
            infoDisplayManager.showChannelNum(index + 1);
        }
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception ignored) {}
        appCoreManager.resetSourceFailedCount();

        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                pipManager.updateChannelInfo(index + 1, channel.getName() != null ? channel.getName() : "", live != null ? live.bitrate : "");
            } catch (Exception e) {
                log("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }

        if (sp.getBoolean("debug_log_enable", false)) {
            LogCollector.getInstance().addDivider();
        }
    }

    public void togglePanel() {
        if (isInCatchUpMode) {
            return;
        }
        channelPanelController.togglePanel();
    }

    public void playPrev() { channelPanelController.playPrev(); }
    public void playNext() { channelPanelController.playNext(); }

    public void showExitMenu() {
        if (exitMenuDialog != null && exitMenuDialog.isShowing()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_exit_menu, null);
        builder.setView(view);

        Button btnRest = view.findViewById(R.id.btn_rest);
        Button btnSettings = view.findViewById(R.id.btn_settings);

        exitMenuDialog = builder.create();

        if (exitMenuDialog != null) {
            if (exitMenuDialog.getWindow() != null) {
                exitMenuDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams lp = exitMenuDialog.getWindow().getAttributes();
                lp.dimAmount = 0.5f;
                exitMenuDialog.getWindow().setAttributes(lp);
                exitMenuDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            exitMenuDialog.setOnDismissListener(dialog -> exitMenuDialog = null);
            exitMenuDialog.show();

            btnRest.setFocusable(true);
            btnRest.setFocusableInTouchMode(true);
            btnSettings.setFocusable(true);
            btnSettings.setFocusableInTouchMode(true);

            btnRest.post(() -> btnRest.requestFocus());
        }

        btnRest.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            finishAffinity();
        });

        btnSettings.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            mMainHandler.postDelayed(() -> {
                isOpeningSettings = false;
                openSettings();
            }, 100);
        });
    }

    private boolean handleBackPressed() {
        long now = System.currentTimeMillis();
        if (now - mLastBackHandleMs < 300L) {
            LogBridge.d("KEY_DEBUG", "handleBackPressed: 300ms 内重复触发，已忽略 (安卓13+双路径去抖)");
            return true;
        }
        mLastBackHandleMs = now;
        try {

            if (isInCatchUpMode && playerControlManager != null && playerControlManager.isControllerShowing()) {
                exitPlaybackMode();
                return true;
            }

            if (channelPanelController != null) {
                try {
                    if (channelPanelController.isPanelOpen()) {
                        if (channelPanelController.backFromRightPanel()) {
                            return true;
                        }
                        channelPanelController.hidePanel();
                        return true;
                    }
                } catch (Exception e) {
                    LogBridge.e("KEY_DEBUG", "handleBackPressed hidePanel 异常: " + e.getMessage(), e);
                }
            }

            if (exitMenuDialog != null) {
                try {
                    if (exitMenuDialog.isShowing()) {
                        exitMenuDialog.dismiss();
                        exitMenuDialog = null;
                        return true;
                    }
                } catch (Exception e) {
                    LogBridge.e("KEY_DEBUG", "handleBackPressed exitMenu 异常: " + e.getMessage(), e);
                    exitMenuDialog = null;
                }
            }

            if (settingsDialog != null) {
                try {
                    if (settingsDialog.isShowing()) {
                        settingsDialog.dismiss();
                        settingsDialog = null;
                        return true;
                    }
                } catch (Exception e) {
                    LogBridge.e("KEY_DEBUG", "handleBackPressed settingsDialog 异常: " + e.getMessage(), e);
                    settingsDialog = null;
                }
            }

            if (settingsCloseTime > 0 && System.currentTimeMillis() - settingsCloseTime < 1000) {
                LogBridge.d("KEY_DEBUG", "设置刚关闭，忽略退出操作");
                return true;
            }

            boolean exitDialogEnabled = sp != null && sp.getBoolean("exit_dialog_enable", false);
            LogBridge.d("KEY_DEBUG", "退出确认弹窗开关: " + exitDialogEnabled + "，Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT);
            if (exitDialogEnabled) {
                showExitMenu();
            } else {
                finishAffinity();
            }
            return true;
        } catch (Exception e) {
            LogBridge.e("KEY_DEBUG", "handleBackPressed 异常: " + e.getMessage(), e);

            try { finishAffinity(); } catch (Exception ignored) {}
            return true;
        }
    }

    private void setupBackPressHandling() {
        try {

            OnBackPressedCallback callback = new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    LogBridge.d("KEY_DEBUG", "【OnBackPressedCallback】(AndroidX) triggered");
                    handleBackPressed();
                }
            };
            getOnBackPressedDispatcher().addCallback(this, callback);
            log("【BackCompat】已注册 AndroidX OnBackPressedCallback (通用方案)");
        } catch (Throwable t) {
            LogBridge.e("BackCompat", "注册 OnBackPressedCallback 失败: " + t.getMessage());
        }

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
                if (dispatcher != null) {

                    if (mSystemBackInvokedCb instanceof android.window.OnBackInvokedCallback) {
                        try {
                            dispatcher.unregisterOnBackInvokedCallback(
                                (android.window.OnBackInvokedCallback) mSystemBackInvokedCb);
                        } catch (Throwable ignore) {}
                    }
                    final Runnable handler = () -> {
                        LogBridge.d("KEY_DEBUG", "【OnBackInvokedCallback】(System API 33+) gesture back triggered");
                        runOnUiThread(this::handleBackPressed);
                    };
                    android.window.OnBackInvokedCallback systemCb = new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() { handler.run(); }
                    };
                    dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, systemCb
                    );
                    mSystemBackInvokedCb = systemCb;
                    log("【BackCompat】已注册系统级 OnBackInvokedCallback (API 33+ 荣耀安卓16兜底)");
                }
            } catch (Throwable t) {
                LogBridge.e("BackCompat", "注册系统 OnBackInvokedCallback 失败: " + t.getMessage());
            }
        }
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        handleBackPressed();
    }

    public void openSettings() {
        try {
            long now = System.currentTimeMillis();

            if (isOpeningSettings) {
                if (now - lastSettingsOpenTime > 5000) {
                    LogBridge.d("Settings", "强制解锁 isOpeningSettings（超过 5 秒）");
                    isOpeningSettings = false;
                } else {
                    LogBridge.d("Settings", "isOpeningSettings 为 true，被拦截（距离上次尝试不到 5 秒）");
                    return;
                }
            }

            if (isInCatchUpMode) return;

            lastSettingsOpenTime = now;
            isOpeningSettings = true;

            try {
                if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                    channelPanelController.hidePanel();
                }
            } catch (Exception e) {
                LogBridge.e("Settings", "hidePanel 失败", e);
            }

            try {
                if (playerControlManager != null) {
                    playerControlManager.onOpenSettings();
                }
            } catch (Exception e) {
                LogBridge.e("Settings", "onOpenSettings 失败", e);
            }

            try {
                settingsDialog = new SettingsDialog(this);
                settingsDialog.show();
                LogBridge.d("Settings", "SettingsDialog 显示成功");
            } catch (Exception e) {
                LogBridge.e("Settings", "显示 SettingsDialog 失败", e);
                isOpeningSettings = false;
            }
        } catch (Exception e) {
            LogBridge.e("Settings", "打开设置失败", e);
            isOpeningSettings = false;
        }
    }

    public void refreshSettings() {

        mMainHandler.post(() -> {
            loadSettings();

            if (screenRatioManager != null) {
                screenRatioManager.apply();
            }

            if (pipManager != null) {
                pipManager.setPipEnabled(pipEnable);
            }

            if (channelPanelController != null) {
                channelPanelController.setReverse(channel_reverse);
            }

            LogBridge.d("MainActivity", "设置已主动刷新，无需切后台");
        });
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        currentPlayIndex = 0;
        appConfig.setLastPlayIndex(0);
        channelPanelController.setCurrentPlayIndex(0);
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (pipManager != null) pipManager.enterPip(this, mPlayerManager, pipEnable);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception ignored) {}
        }
        if (pipManager != null) {
            if (isInPictureInPictureMode) {
                pipManager.handleEnterPip(this, channelPanelController, infoDisplayManager, mPlayerManager, playerView);
            } else {
                pipManager.handleExitPip(() -> {});
                pipManager.handleExitPipRestore(this, displayManager, playerView, mPlayerManager, channelSourceList, currentPlayIndex, infoDisplayManager);
            }
        }
    }

    private void log(String msg) {
        if (!sp.getBoolean("debug_log_enable", false)) {
            return;
        }
        LogBridge.d("MainActivity", msg);

        LogCollector.getInstance().addLog("播放", msg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isOpeningSettings) {
            return;
        }
        mMainHandler.removeCallbacks(openSettingsRunnable);
        mMainHandler.removeCallbacks(logUpdateRunnable);
        if (appCoreManager != null) {
            appCoreManager.onPause();
        }

        boolean isSystemVisible = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isSystemVisible = isInMultiWindowMode() || isInPictureInPictureMode();
        }

        if (pipManager != null && pipManager.isInPipMode()) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } else if (isSystemVisible) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } else {
            if (mPlayerManager != null) {
                mPlayerManager.onBackground();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pipManager != null) pipManager.setStopCalled(true);

        boolean isSystemVisible = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isSystemVisible = isInMultiWindowMode() || isInPictureInPictureMode();
        }
        if (isSystemVisible) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        if (isInMultiWindowMode) {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mMainHandler.postDelayed(() -> {
            if (mPlayerManager != null) {
                mPlayerManager.onForeground();
            }
            displayManager.reapplyFullScreen();
        }, 200);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100) {
            isOpeningSettings = false;
            okKeyTriggered = false;
            LogBridge.d("MainActivity", "onActivityResult: Settings closed, isOpeningSettings reset to false");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false;
        appCoreManager.onResume();
        if (pipManager != null) pipManager.setStopCalled(false);
        loadSettings();
        screenRatioManager.apply();
        displayManager.reapplyFullScreen();

        if (pipManager == null || !pipManager.isInPipMode()) {
            if (mPlayerManager != null) {
                mPlayerManager.onForeground();
            }
            if (playerControlManager != null) {
                playerControlManager.onResume();
            }
        } else {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        }

        if (channelPanelController != null) {
            channelPanelController.clearPanelFocus();
        }

        if (playerControlManager != null) {
            playerControlManager.onSettingsClosed();
        }
    }

    private boolean wasWindowFocused = true;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            displayManager.reapplyFullScreen();
            if (!wasWindowFocused && mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } else {

            if (mPlayerManager != null && !isOpeningSettings) {
                boolean isSystemVisible = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    isSystemVisible = isInMultiWindowMode() || isInPictureInPictureMode();
                }
                if (!isSystemVisible) {
                    mPlayerManager.pause();
                }
            }
        }
        wasWindowFocused = hasFocus;
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mInstanceRef != null) {
            mInstanceRef.clear();
            mInstanceRef = null;
        }

        mMainHandler.removeCallbacksAndMessages(null);

        if (remoteKeyHandler != null) {
            remoteKeyHandler.release();
            remoteKeyHandler = null;
        }

        if (lifecycleHelper != null) {
            lifecycleHelper.releaseAll();
            lifecycleHelper = null;
        }

        if (touchListener != null) {
            touchListener.updateGestureHelper(null);
            if (playerView != null) {
                playerView.setOnTouchListener(null);
            }
            touchListener = null;
        }

        if (settingsDialog != null) {
            try {
                if (settingsDialog.isShowing()) {
                    settingsDialog.dismiss();
                }
            } catch (Exception ignored) {}
            settingsDialog = null;
        }
        if (exitMenuDialog != null) {
            try {
                if (exitMenuDialog.isShowing()) {
                    exitMenuDialog.dismiss();
                }
            } catch (Exception ignored) {}
            exitMenuDialog = null;
        }

        if (infoDisplayManager != null) {
            infoDisplayManager.release();
            infoDisplayManager = null;
        }
        if (displayManager != null) {
            displayManager.release();
            displayManager = null;
        }
        if (channelPanelController != null) {
            channelPanelController.release();
            channelPanelController = null;
        }
        if (appCoreManager != null) {
            appCoreManager.release();
            appCoreManager = null;
        }
        if (pipManager != null) {
            pipManager.release();
            pipManager = null;
        }

        if (playerControlManager != null) {
            playerControlManager.release();
            playerControlManager = null;
        }

        if (mPlayerManager != null) {
            mPlayerManager.setOnPlayStateListener(null);
            mPlayerManager.setOnLiveInfoUpdateListener(null);
            mPlayerManager.setOnSourceFailedListener(null);
            mPlayerManager.release();
            mPlayerManager = null;
        }

        if (unlockReceiver != null) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {}
            unlockReceiver = null;
        }

        if (autoRotateObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(autoRotateObserver);
            } catch (Exception ignored) {}
            autoRotateObserver = null;
        }

        gestureManager = null;
        screenRatioManager = null;
        playerStateListener = null;
        appConfig = null;
        channelSourceList.clear();
        channelSourceList = null;
    }
}
