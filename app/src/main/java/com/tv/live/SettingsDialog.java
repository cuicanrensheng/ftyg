package com.tv.live;

import com.tv.live.util.LogBridge;
import com.tv.live.config.AppConfig;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.res.ColorStateList;
import java.util.List;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.GridLayout;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

import com.tv.live.util.HuyaStreamPlayer.IntCallback;
import com.tv.live.widget.SettingsDialogHelper;

public class SettingsDialog extends android.app.Dialog {
    private SwitchCompat sw_boot, sw_reverse, sw_pip, sw_js_update;
    private TextView tv_screen_ratio, tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    private TextView tv_channel_line;
    private TextView tv_resolution_status;
    private View itemResolution;

    private View itemExitDialog;
    private TextView tv_exit_dialog_status;

    private View itemVersionInfo;
    private TextView tv_version_short;

    private LinearLayout itemLiveSubscribe, itemEpgSubscribe;

    private TextView tv_background_status;
    private TextView tv_source_mode_status;

    private SharedPreferences sp;
    private ScrollView scrollView;

    private BootStartManager bootStartManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private SettingsDialogHelper dialogHelper;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;

    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";
    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";
    private static final String KEY_PANEL_BACKGROUND = "panel_background";

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private int selectedItemPosition = 0;

    private long mShowTime = 0;
    private static final long IGNORE_KEY_DELAY_MS = 500;

    private boolean isDismissing = false;
    private boolean backHandled = false;

    public SettingsDialog(android.content.Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(android.view.Gravity.FILL);
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.dimAmount = 0f;
            window.setAttributes(layoutParams);
        }

        setContentView(R.layout.activity_settings);

        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> dismiss());

        sp = getContext().getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE);
        initRedirectDefaultConfig();
        dialogHelper = new SettingsDialogHelper(getContext());

        sp.edit().putBoolean("epg_enable", true).apply();
        sp.edit().putBoolean("number_channel_enable", true).apply();

        sw_boot = findViewById(R.id.sw_boot);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_pip = findViewById(R.id.sw_pip);
        sw_js_update = findViewById(R.id.sw_js_update);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        tv_redirect_setting = findViewById(R.id.tv_redirect_setting);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);

        itemResolution = findViewById(R.id.item_resolution);
        tv_resolution_status = findViewById(R.id.tv_resolution_status);

        itemExitDialog = findViewById(R.id.item_exit_dialog);
        tv_exit_dialog_status = findViewById(R.id.tv_exit_dialog_status);

        itemVersionInfo = findViewById(R.id.item_version_info);
        tv_version_short = findViewById(R.id.tv_version_short);

        tv_background_status = findViewById(R.id.tv_background_status);
        applyPanelBackgroundForSettings();
        tv_source_mode_status = findViewById(R.id.tv_source_mode_status);
        applySourceModeForSettings();

        bootStartManager = new BootStartManager(getContext(), sp);
        sourceDialogManager = new SourceDialogManager(getContext(), sp);
        qrCodeManager = new QRCodeManager(getContext());
        webServerManager = new WebServerManager(getContext(), WEB_SERVER_PORT);

        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);

        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);

        tv_boot_status.setOnClickListener(v -> bootStartManager.showBootStatusDialog());
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        sw_js_update.setChecked(sp.getBoolean("js_plugin_hot_update", false));

        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        updateRedirectSettingText();

        tv_channel_line = findViewById(R.id.tv_channel_line);
        refreshStreamStatusRows();

        boolean exitDialogEnabled = sp.getBoolean("exit_dialog_enable", false);
        tv_exit_dialog_status.setText(exitDialogEnabled ? "开启" : "关闭");

        initSettingsItemList();

        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        SourceManager liveManager = new SourceManager(getContext(), "live_history");
        if (liveManager.size() == 0) {
            liveManager.addSource("默认直播源", UrlConfig.LIVE_URL);
        }
        SourceManager epgManager = new SourceManager(getContext(), "epg_history");
        if (epgManager.size() == 0) {
            epgManager.addSource("默认节目单", UrlConfig.EPG_URL);
        }
    }

    private void initSettingsItemList() {
        View[] items = {
            findViewById(R.id.item_boot),
            findViewById(R.id.item_reverse),
            findViewById(R.id.item_pip),
            findViewById(R.id.item_channel_line),
            findViewById(R.id.item_resolution),
            findViewById(R.id.item_decoder),
            findViewById(R.id.item_renderer),
            findViewById(R.id.tv_screen_ratio),
            findViewById(R.id.item_redirect),
            findViewById(R.id.item_live_subscribe),
            findViewById(R.id.item_epg_subscribe),
            findViewById(R.id.item_source_mode),
            findViewById(R.id.item_background_switch),
            findViewById(R.id.item_exit_dialog),
            findViewById(R.id.item_version_info),
            findViewById(R.id.item_js_update)
        };

        for (View item : items) {
            item.setBackgroundResource(R.drawable.item_settings_bg);
            item.setFocusable(true);
            item.setClickable(true);
        }

        for (int i = 0; i < items.length; i++) {
            View item = items[i];
            int finalI = i;
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    if (selectedItemPosition != finalI) {
                        items[selectedItemPosition].setSelected(false);
                        setChildTextViewsBold(items[selectedItemPosition], false);
                        items[finalI].setSelected(true);
                        setChildTextViewsBold(items[finalI], true);
                        selectedItemPosition = finalI;
                    }
                }
                v.setBackgroundResource(R.drawable.item_settings_bg);
            });
        }

        View.OnClickListener clickListener = v -> {
            int clickedIndex = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    clickedIndex = i;
                    break;
                }
            }
            if (clickedIndex == -1) return;

            if (clickedIndex == selectedItemPosition) {
                performItemAction(clickedIndex);
            } else {
                items[selectedItemPosition].setSelected(false);
                setChildTextViewsBold(items[selectedItemPosition], false);
                items[clickedIndex].setSelected(true);
                setChildTextViewsBold(items[clickedIndex], true);
                selectedItemPosition = clickedIndex;
            }
        };

        for (View item : items) {
            item.setOnClickListener(clickListener);
        }

        items[0].setSelected(true);
        setChildTextViewsBold(items[0], true);

        if (scrollView != null) {
            scrollView.setFocusable(false);
            scrollView.setFocusableInTouchMode(false);
            scrollView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            scrollView.scrollTo(0, 0);
        }

        mainHandler.postDelayed(() -> {
            items[0].requestFocus();
            LogBridge.d("Settings", "First item focused");
        }, 100);
    }

    @Override
    public void show() {
        mShowTime = System.currentTimeMillis();
        isDismissing = false;
        backHandled = false;

        super.show();
        mainHandler.postDelayed(() -> {
            View[] items = {
                findViewById(R.id.item_boot),
                findViewById(R.id.item_reverse),
                findViewById(R.id.item_pip),
                findViewById(R.id.item_channel_line),
                findViewById(R.id.item_resolution),
                findViewById(R.id.item_decoder),
                findViewById(R.id.item_renderer),
                findViewById(R.id.tv_screen_ratio),
                findViewById(R.id.item_redirect),
                findViewById(R.id.item_live_subscribe),
                findViewById(R.id.item_epg_subscribe),
                findViewById(R.id.item_source_mode),
                findViewById(R.id.item_background_switch),
                findViewById(R.id.item_exit_dialog),
                findViewById(R.id.item_version_info),
                findViewById(R.id.item_js_update)
            };
            if (items.length > 0 && items[0] != null) {
                items[0].requestFocus();
                LogBridge.d("SettingsDialog", "Focus requested to first item");
            }
        }, 300);
    }

    private void performItemAction(int index) {
        switch (index) {
            case 0:
                boolean bootChecked = !sw_boot.isChecked();
                sw_boot.setChecked(bootChecked);
                bootStartManager.toggleBoot(bootChecked, tv_boot_status);
                break;
            case 1:
                boolean reverseChecked = !sw_reverse.isChecked();
                sw_reverse.setChecked(reverseChecked);
                sp.edit().putBoolean("channel_reverse", reverseChecked).apply();
                Toast.makeText(getContext(), "换台反转" + (reverseChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
                break;
            case 2:
                boolean pipChecked = !sw_pip.isChecked();
                sw_pip.setChecked(pipChecked);
                sp.edit().putBoolean("pip_enable", pipChecked).apply();
                if (pipChecked) {
                    Toast.makeText(getContext(), "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "画中画已关闭", Toast.LENGTH_SHORT).show();
                }
                break;
            case 3:
                showChannelLineDialog();
                break;
            case 4:
                showResolutionDialog();
                break;
            case 5:
                showDecoderModeDialog();
                break;
            case 6:
                showRendererModeDialog();
                break;
            case 7:
                showRatioDialog();
                break;
            case 8:
                showRedirectConfigDialog();
                break;
            case 9:
                showSubscriptionDialog("live_history", "直播源订阅");
                break;
            case 10:
                showSubscriptionDialog("epg_history", "节目单订阅");
                break;
            case 11:
                showSourceModeDialog();
                break;
            case 12:
                showBackgroundSwitchDialog();
                break;
            case 13:
                boolean exitDialogEnabled = sp.getBoolean("exit_dialog_enable", false);
                boolean newState = !exitDialogEnabled;
                sp.edit().putBoolean("exit_dialog_enable", newState).apply();
                tv_exit_dialog_status.setText(newState ? "开启" : "关闭");
                Toast.makeText(getContext(), "退出弹窗已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                break;
            case 14:
                showVersionInfoDialog();
                break;
            case 15:
                // 【2026-10-07】插件热更开关：默认关；开启后按 md5 增量对齐 JS 解析插件
                boolean jsUpdateChecked = !sw_js_update.isChecked();
                sw_js_update.setChecked(jsUpdateChecked);
                com.tv.live.migu.js.JsPluginStore.setUpdateEnabled(getContext(), jsUpdateChecked);
                Toast.makeText(getContext(), jsUpdateChecked
                                ? "插件热更已开启（24 小时内最多检查一次）"
                                : "插件热更已关闭",
                        Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private int getPanelBackgroundRes(int index) {
        switch (index) {
            case 1: return R.drawable.panel_bg_1;
            case 2: return R.drawable.panel_bg_2;
            case 3: return R.drawable.panel_bg_3;
            case 4: return R.drawable.panel_bg_4;
            default: return R.drawable.panel_bg;
        }
    }

    private String getPanelBackgroundName(int index) {
        switch (index) {
            case 1: return getContext().getString(R.string.background_1);
            case 2: return getContext().getString(R.string.background_2);
            case 3: return getContext().getString(R.string.background_3);
            case 4: return getContext().getString(R.string.background_4);
            default: return getContext().getString(R.string.background_default);
        }
    }

    private void applyPanelBackgroundForSettings() {
        int bg = sp.getInt(KEY_PANEL_BACKGROUND, 0);
        if (scrollView != null) scrollView.setBackgroundResource(getPanelBackgroundRes(bg));
        if (tv_background_status != null) tv_background_status.setText(getPanelBackgroundName(bg));
    }

    private void showBackgroundSwitchDialog() {
        String[] options = {
            getContext().getString(R.string.background_default),
            getContext().getString(R.string.background_1),
            getContext().getString(R.string.background_2),
            getContext().getString(R.string.background_3),
            getContext().getString(R.string.background_4)
        };
        int current = sp.getInt(KEY_PANEL_BACKGROUND, 0);

        showCommonSelectionDialog(getContext().getString(R.string.background_select), options, current, selected -> {
            sp.edit().putInt(KEY_PANEL_BACKGROUND, selected).apply();
            applyPanelBackgroundForSettings();
            MainActivity activity = MainActivity.getRunningInstance();
            if (activity != null) {
                activity.applyPanelBackground(selected);
            }
            Toast.makeText(getContext(), "已切换背景：" + getPanelBackgroundName(selected), Toast.LENGTH_SHORT).show();
        });
    }

    private void applySourceModeForSettings() {
        int mode = AppConfig.getInstance(getContext()).getSourceMode();
        if (tv_source_mode_status != null) {
            tv_source_mode_status.setText(mode == AppConfig.SOURCE_MODE_OTT
                    ? getContext().getString(R.string.source_mode_ott)
                    : getContext().getString(R.string.source_mode_live));
        }
    }

    private void showSourceModeDialog() {
        final String[] options = {
            getContext().getString(R.string.source_mode_live),
            getContext().getString(R.string.source_mode_ott)
        };
        int current = AppConfig.getInstance(getContext()).getSourceMode();

        showCommonSelectionDialog(getContext().getString(R.string.source_mode_select), options, current, selected -> {
            AppConfig.getInstance(getContext()).setSourceMode(selected);
            applySourceModeForSettings();
            Toast.makeText(getContext(), "源模式已切换为" + options[selected], Toast.LENGTH_SHORT).show();

            if (selected == AppConfig.SOURCE_MODE_OTT) {
                // 直播模式 -> OTT：拉起 OTT 首页，并标记不释放共享播放器，保证首页预览可续播
                MainActivity activity = MainActivity.getRunningInstance();
                if (activity != null && !activity.isFinishing()) {
                    activity.switchingToOtt = true;
                    Intent intent = new Intent(activity, OttHomeActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    activity.finish();
                }
            }
        });
    }

    private void showVersionInfoDialog() {
        String versionName = BuildConfig.VERSION_NAME;
        int versionCode = BuildConfig.VERSION_CODE;
        String userAgent = sp.getString("custom_user_agent", "");
        if (TextUtils.isEmpty(userAgent)) {
            String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
            if ("vlc".equals(uaMode)) {
                userAgent = "VLC/3.0.21 LibVLC/3.0.21";
            } else {
                userAgent = "ExoPlayer";
            }
        }
        String sdkVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String playerVersion = "androidx.media3 1.7.1";

        StringBuilder sb = new StringBuilder();
        sb.append("版本信息: v").append(versionName).append(" (").append(versionCode).append(")\n\n");
        sb.append("UA: ").append(userAgent).append("\n\n");
        sb.append("SDK 版本: ").append(sdkVersion).append("\n\n");
        sb.append("播放器版本: ").append(playerVersion).append("\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n");

        String message = sb.toString();
        SpannableString spannableString = new SpannableString(message);

        int p;
        p = message.indexOf("版本信息");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("UA:");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("SDK 版本");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        p = message.indexOf("播放器版本");
        if (p != -1) spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), p, p + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.dialog_bg_corner);
        int pad = dp2px(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(getContext());
        titleView.setText("📱 应用详情");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, dp2px(8));
        layout.addView(titleView);

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setScrollbarFadingEnabled(false);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setFillViewport(true);

        TextView msgView = new TextView(getContext());
        msgView.setText(spannableString);
        msgView.setTextColor(Color.WHITE);
        msgView.setTextSize(16);
        msgView.setLineSpacing(0, 1.25f);
        msgView.setFocusable(true);
        msgView.setFocusableInTouchMode(true);

        scrollView.addView(msgView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(380)
        ));

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        mainHandler.postDelayed(() -> {
            if (msgView != null && dialog.isShowing()) {
                msgView.requestFocus();
            }
        }, 200);
    }

    private int dp2px(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    private void showNumberInputDialog(int currentValue, IntCallback onConfirmed) {
        LinearLayout dialogView = new LinearLayout(getContext());
        dialogView.setOrientation(LinearLayout.VERTICAL);
        dialogView.setBackgroundResource(R.drawable.dialog_bg_corner);
        dialogView.setPadding(24, 24, 24, 24);

        TextView titleView = new TextView(getContext());
        titleView.setText("请输入数字");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 24);
        dialogView.addView(titleView);

        final TextView displayView = new TextView(getContext());
        displayView.setText(String.valueOf(currentValue));
        displayView.setTextColor(Color.WHITE);
        displayView.setTextSize(48);
        displayView.setGravity(Gravity.CENTER);
        displayView.setBackgroundColor(0xFF333545);
        displayView.setPadding(16, 16, 16, 16);
        dialogView.addView(displayView);

        LinearLayout keysLayout = new LinearLayout(getContext());
        keysLayout.setOrientation(LinearLayout.VERTICAL);
        keysLayout.setPadding(16, 24, 16, 0);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "清空", "0", "确认"};
        final TextView[] keyViews = new TextView[keys.length];

        for (int row = 0; row < 4; row++) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                TextView keyView = new TextView(getContext());
                keyView.setText(keys[index]);
                keyView.setTextColor(Color.WHITE);
                keyView.setTextSize(20);
                keyView.setGravity(Gravity.CENTER);
                keyView.setBackgroundColor(0xFF333545);
                keyView.setPadding(32, 20, 32, 20);
                keyView.setFocusable(true);
                keyView.setFocusableInTouchMode(true);
                keyView.setTag(keys[index]);
                keyViews[index] = keyView;

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.setMargins(8, 8, 8, 8);
                rowLayout.addView(keyView, params);
            }
            keysLayout.addView(rowLayout);
        }
        dialogView.addView(keysLayout);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final StringBuilder inputValue = new StringBuilder(String.valueOf(currentValue));

        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            final String key = keys[i];
            TextView keyView = keyViews[i];

            keyView.setOnClickListener(v -> {
                if ("清空".equals(key)) {
                    inputValue.setLength(0);
                    displayView.setText("");
                } else if ("确认".equals(key)) {
                    int value = 0;
                    try {
                        value = Integer.parseInt(inputValue.toString());
                    } catch (NumberFormatException e) {
                        value = currentValue;
                    }
                    if (value < 1) value = 1;
                    if (value > 20) value = 20;
                    onConfirmed.accept(value);
                    dialog.dismiss();
                } else {
                    if (inputValue.length() < 2) {
                        inputValue.append(key);
                        int tempValue = Integer.parseInt(inputValue.toString());
                        if (tempValue <= 20) {
                            displayView.setText(inputValue.toString());
                        } else {
                            inputValue.setLength(inputValue.length() - 1);
                        }
                    }
                }
            });

            keyView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if ("清空".equals(key)) {
                            inputValue.setLength(0);
                            displayView.setText("");
                        } else if ("确认".equals(key)) {
                            int value = 0;
                            try {
                                value = Integer.parseInt(inputValue.toString());
                            } catch (NumberFormatException e) {
                                value = currentValue;
                            }
                            if (value < 1) value = 1;
                            if (value > 20) value = 20;
                            onConfirmed.accept(value);
                            dialog.dismiss();
                        } else {
                            if (inputValue.length() < 2) {
                                inputValue.append(key);
                                int tempValue = Integer.parseInt(inputValue.toString());
                                if (tempValue <= 20) {
                                    displayView.setText(inputValue.toString());
                                } else {
                                    inputValue.setLength(inputValue.length() - 1);
                                }
                            }
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        dialog.dismiss();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && idx >= 3) {
                        keyViews[idx - 3].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && idx < 9) {
                        keyViews[idx + 3].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && idx % 3 > 0) {
                        keyViews[idx - 1].requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && idx % 3 < 2) {
                        keyViews[idx + 1].requestFocus();
                        return true;
                    }
                }
                return false;
            });

            keyView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    keyView.setBackgroundColor(0x3340A9FF);
                    keyView.setTextColor(0xFF40A9FF);
                } else {
                    keyView.setBackgroundColor(0xFF333545);
                    keyView.setTextColor(Color.WHITE);
                }
            });
        }

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dialog.dismiss();
                return true;
            }
            return false;
        });

        dialog.show();

        mainHandler.postDelayed(() -> {
            if (keyViews.length > 0 && dialog.isShowing()) {
                keyViews[4].requestFocus();
            }
        }, 200);
    }

    private void showCommonSelectionDialog(String title, String[] items, int checkedItem, IntCallback onSelected) {
        ListView listView = new ListView(getContext());
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        final int[] pendingPos = {checkedItem};
        final AlertDialog[] dialogHolder = new AlertDialog[1];

        class CustomAdapter extends ArrayAdapter<String> {
            private int selectedPos;

            public CustomAdapter(android.content.Context context, String[] items, int initialPos) {
                super(context, android.R.layout.simple_list_item_single_choice, items);
                selectedPos = initialPos;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(16, 16, 16, 16);

                if (position == selectedPos) {
                    tv.setTextColor(0xFF40A9FF);
                    view.setBackgroundColor(0x3340A9FF);
                } else {
                    tv.setTextColor(Color.WHITE);
                    view.setBackgroundColor(0x00000000);
                }
                return view;
            }

            public void setSelectedPos(int pos) {
                selectedPos = pos;
                notifyDataSetChanged();
            }
        }

        CustomAdapter adapter = new CustomAdapter(getContext(), items, checkedItem);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checkedItem, true);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            pendingPos[0] = position;
            adapter.setSelectedPos(position);
            listView.setItemChecked(position, true);
            onSelected.accept(position);
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });

        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (pendingPos[0] != position) {
                    pendingPos[0] = position;
                    adapter.setSelectedPos(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        listView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    int selected = listView.getSelectedItemPosition();
                    if (selected >= 0 && selected < items.length) {
                        if (pendingPos[0] == selected) {
                            onSelected.accept(selected);
                            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                        } else {
                            pendingPos[0] = selected;
                            adapter.setSelectedPos(selected);
                            listView.setItemChecked(selected, true);
                            Toast.makeText(getContext(), "再次按确认键选择", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    return true;
                }
            }
            return false;
        });

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(24, 24, 24, 0);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.dialog_bg_corner);
        layout.setPadding(24, 24, 24, 24);

        layout.addView(titleView);

        int itemHeightPx = (int) (55 * getContext().getResources().getDisplayMetrics().density);
        int wantH = itemHeightPx * items.length + 32;
        int maxH = (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.7f);
        int listH = Math.min(wantH, maxH);
        listView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listH));
        layout.addView(listView);

        dialogHolder[0] = new AlertDialog.Builder(getContext())
                .setView(layout)
                .create();
        if (dialogHolder[0].getWindow() != null) {
            dialogHolder[0].getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialogHolder[0].show();
        mainHandler.postDelayed(() -> {
            if (listView != null && dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                listView.requestFocus();
            }
        }, 200);
    }

    /** 清理线路标签中的 emoji/变体选择符，避免电视字体显示成 ? */
    private static String normalizeLineLabel(String raw) {
        if (raw == null) return null;
        // 把云 emoji（含带 U+FE0F 变体）统一替换为中文“线路”
        String s = raw.replace("☁️", "线路").replace("☁", "线路");
        // 去掉文本/emoji 变体选择符
        s = s.replace("\uFE0E", "").replace("\uFE0F", "").replace("\u200D", "");
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 刷新「频道线路选择 / 清晰度选择」两行状态文字。
     *
     * 【2026-09-29 抽出】虎牙频道的线路表/清晰度表是**解析完成后**才写进 TVPlayerManager 的
     * （自研解析实测 1.7~2.2s），而设置面板经常在解析完成**之前**就被打开 ——
     * 那一瞬间两行只能显示初值（清晰度会停在初始值「自适应」），看着像"没生效"。
     * 现在解析就绪时会回调本方法刷新（{@code TVPlayerManager#setOnStreamOptionsReadyListener}），
     * 面板自己会跟上，不必关掉再开。
     */
    public void refreshStreamStatusRows() {
        if (tv_resolution_status == null || tv_channel_line == null) return;
        try {
            TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
            Channel currentChannel = playerManager.getCurrentChannel();
            // 2026-09-19：这里原先用 SP["resolution_<频道>"] 的记忆值，但播放链路没有任何地方读取它，
            // 结果「手动选过蓝光4M」会永久显示在设置界面，而实际输出每次起播又都被 selectBestStreamForDevice
            // 压回 ≤720p 的超清 → 显示与真实档位脱节。改为直接显示当前实际生效的清晰度。
            String actualRes = playerManager.getCurrentResolutionLabel();
            tv_resolution_status.setText(TextUtils.isEmpty(actualRes) ? "自动" : actualRes);

            int currentLineIndex = 0;
            if (currentChannel != null) {
                String channelKey = currentChannel.getChannelId();
                if (TextUtils.isEmpty(channelKey)) {
                    channelKey = currentChannel.getName();
                }
                String prefKey = "channel_line_index_" + channelKey;
                currentLineIndex = sp.getInt(prefKey, 0);
            } else {
                currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
            }
            // 【2026-09-30 定稿】虎牙频道：这行显示当前生效的**子线路**（形如「源2 · HS」），
            // 与弹窗条目口径完全一致（不含总线路、不含清晰度）。
            // 非虎牙 / 该房间无子线路时再走下面的兜底。
            List<String> huyaSubs = playerManager.getHuyaMultiLineLabels();
            if (!huyaSubs.isEmpty()) {
                int curSrc = playerManager.getCurrentHuyaSourceUiIndex();
                // 匹配不到就如实写「未识别」，**不要**退化成第 1 项 —— 那会显示成一条实际没在播的线路。
                tv_channel_line.setText((curSrc >= 0 && curSrc < huyaSubs.size())
                        ? huyaSubs.get(curSrc) : "未识别");
                return;
            }

            // 【2026-10-07】咪咕频道：线路行显示「线路 × 清晰度」轴里**当前生效**的线路
            //   （切线路 / 解析选源都会更新），不盲信 SP 记忆下标。
            if (playerManager.hasMiguAxis()) {
                List<String> miguLines = playerManager.getMiguLineNames();
                int miguIdx = playerManager.getCurrentMiguLineUiIndex();
                tv_channel_line.setText((miguIdx >= 0 && miguIdx < miguLines.size())
                        ? miguLines.get(miguIdx) : "未识别");
                return;
            }

            // 【2026-10-07】畅享（源3）频道：与咪咕同构的「线路 × 清晰度」轴。
            //   轴未就绪时惰性构建（单请求 + 10min 缓存），就绪后回调刷新本方法。
            if (currentChannel != null && playerManager.isChanxiangHttpChannel(currentChannel)) {
                if (playerManager.hasChanxiangAxis()) {
                    List<String> cxLines = playerManager.getChanxiangLineNames();
                    int cxi = playerManager.getCurrentChanxiangLineUiIndex();
                    tv_channel_line.setText((cxi >= 0 && cxi < cxLines.size())
                            ? cxLines.get(cxi) : "未识别");
                    String cxq = playerManager.getCurrentChanxiangQualityLabel();
                    tv_resolution_status.setText(TextUtils.isEmpty(cxq) ? "自适应" : cxq);
                } else {
                    tv_channel_line.setText("加载中…");
                    playerManager.ensureChanxiangAxisAsync(this::refreshStreamStatusRows);
                }
                return;
            }

            // 【2026-10-11】源7（afhgrj 影视点播）频道：线路 = 该片播放源 sid。
            //   清晰度无枚举维度（落地流是单条 m3u8，多码率播放器自适应），恒显示「自适应」。
            if (playerManager.isAfhgrjChannel()) {
                List<String> afLines = playerManager.getAfhgrjLineNames();
                if (afLines.isEmpty()) {
                    tv_channel_line.setText("加载中…");
                } else {
                    int afi = playerManager.getCurrentAfhgrjLineUiIndex();
                    tv_channel_line.setText((afi >= 0 && afi < afLines.size())
                            ? afLines.get(afi) : "未识别");
                }
                tv_resolution_status.setText("自适应");
                return;
            }

            // 非虎牙频道（或无子线路）：沿用「主源 / 源N」+ 真实线路名
            int realLineUi = playerManager.getCurrentHuyaLineUiIndex();
            if (realLineUi >= 0) currentLineIndex = realLineUi;
            List<String> availableLines = playerManager.getAvailableLines();
            if (currentLineIndex >= 0 && currentLineIndex < availableLines.size()) {
                String lineLabel = normalizeLineLabel(availableLines.get(currentLineIndex));
                tv_channel_line.setText(TextUtils.isEmpty(lineLabel) ? getLineName(currentLineIndex) : lineLabel);
            } else {
                tv_channel_line.setText(getLineName(currentLineIndex));
            }
        } catch (Throwable t) {
            LogBridge.w("SettingsDialog", "刷新线路/清晰度状态行失败: " + t.getMessage());
        }
    }

    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel == null) {
            Toast.makeText(getContext(), "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
            return;
        }

        // 【2026-10-11】源7（afhgrj 影视）频道：线路 = 该片播放源 sid，切换走 switchAfhgrjLine
        if (playerManager.isAfhgrjChannel()) {
            showAfhgrjLineDialog(playerManager);
            return;
        }

        // 【2026-10-07】咪咕频道：线路名来自「线路 × 清晰度」轴，切换走 switchMiguLine
        if (playerManager.hasMiguAxis()) {
            showMiguLineDialog(playerManager);
            return;
        }

        // 【2026-10-07】畅享（源3）频道：与咪咕同构，切换走 switchChanxiangLine
        if (playerManager.isChanxiangHttpChannel(currentChannel)) {
            if (playerManager.hasChanxiangAxis()) {
                showChanxiangLineDialog(playerManager);
            } else {
                Toast.makeText(getContext(), "畅享线路加载中，请稍候再试", Toast.LENGTH_SHORT).show();
                playerManager.ensureChanxiangAxisAsync(this::refreshStreamStatusRows);
            }
            return;
        }

        String channelKey = currentChannel.getChannelId();
        if (TextUtils.isEmpty(channelKey)) {
            channelKey = currentChannel.getName();
        }
        String prefKey = "channel_line_index_" + channelKey;
        int currentLineIndex = sp.getInt(prefKey, 0);

        // 【2026-09-30 定稿·用户最终口径】虎牙频道的「频道线路选择」= **只列子线路**，
        //   每项形如「源1 · TX」「源2 · HS」——N 是子线路序号，后缀是它所属的 CDN / 总线路。
        //   ⚠️ 严格排除：① **3 条总线路本身**（不再有「主源」项，也不列「线路1/2/3」）；
        //                ② **任何清晰度字样**（那是独立的另一个轴，由「清晰度选择」按钮负责）。
        //   条数随房间变化（服务端下发几条就是几条），由 TVPlayerManager.getAvailableLines() 生产。
        // 非虎牙频道 / 该房间没有子线路 → 回落原有逻辑（主源 / 源N / 总线路）
        // 【2026-09-29 修正】线路弹窗**只列线路**，文案由 TVPlayerManager 生产，规则为：
        //   item =「线路」+ 升序序号（1..N）→ 显示的就是「线路1」「线路2」「线路3」，不含任何 CDN/清晰度信息
        //   为什么不照抄 SDK 的 String.format("线路%s", lineIndex) 填原值：
        //   mp 接口下发的 iLineIndex 是**CDN 编码**（实测 AL=3 / TX=5 / HS=14），与 SDK 内部的连续序号不同源，
        //   直接当序号用会显示成「线路3 / 线路5 / 线路14」，语义完全不对（SDK 那条"v=5/v=14"日志同理）。
        //   （清晰度是另一个独立轴，对应「清晰度选择」按钮，两个轴不混。）
        //   虎牙：线路条数来自 baseSteamInfoList（服务端下发几条就是几条）；
        //   打勾位置用**真实生效线路**（getCurrentHuyaLineUiIndex），不盲信 SP 记忆
        //   —— 冷启动恢复/自动选线后 SP 常常与实播线路不一致，会打错勾。
        List<String> lineList = playerManager.getAvailableLines();
        if (lineList.isEmpty()) {
            lineList = new ArrayList<>();
            lineList.add("主源");
            List<String> labels = currentChannel.getHuyaLineLabels();
            List<String> backups = currentChannel.getBackupUrls();
            int n = (labels != null && !labels.isEmpty()) ? labels.size() : (backups != null ? backups.size() : 0);
            for (int i = 0; i < n; i++) {
                String raw = (labels != null && i < labels.size()) ? labels.get(i) : null;
                String label = normalizeLineLabel(raw);
                if (TextUtils.isEmpty(label)) {
                    label = "源" + (i + 1);
                }
                lineList.add(label);
            }
        }

        String[] lineArray = new String[lineList.size()];
        for (int i = 0; i < lineList.size(); i++) {
            String label = normalizeLineLabel(lineList.get(i));
            if (TextUtils.isEmpty(label)) label = "源" + (i + 1);
            lineArray[i] = label;
        }

        // 【2026-09-30 定稿】虎牙「只列子线路（源N · CDN）」口径优先：打勾落在真实在播的那条子线路上。
        // ⚠️ 匹配不到时**必须保留 -1**（一项都不高亮），不能夹到 0 —— 那会把勾打在「源1」上（打勾铁律）。
        boolean huyaSubMode = playerManager.isHuyaSource(currentChannel.getMainPlayUrl())
                && !playerManager.getHuyaMultiLineLabels().isEmpty();
        if (huyaSubMode) {
            currentLineIndex = playerManager.getCurrentHuyaSourceUiIndex();
        } else {
            int realIdx = playerManager.getCurrentHuyaLineUiIndex();
            if (realIdx >= 0 && realIdx < lineList.size()) currentLineIndex = realIdx;
            if (currentLineIndex >= lineList.size() || currentLineIndex < 0) currentLineIndex = 0;
        }

        showCommonSelectionDialog("频道线路选择", lineArray, currentLineIndex, (which) -> {
            sp.edit().putInt(prefKey, which).apply();
            sp.edit().putInt(KEY_CHANNEL_LINE_INDEX, which).apply();
            tv_channel_line.setText(lineArray[which]);

            if (playerManager != null && currentChannel != null) {

                if (playerManager.isHuyaSource(currentChannel.getMainPlayUrl())) {
                    playerManager.switchToHuyaLine(which);
                } else {

                    String playUrl;
                    if (which == 0) {
                        playUrl = currentChannel.getMainPlayUrl();
                    } else {
                        List<String> backups = currentChannel.getBackupUrls();
                        int backupIndex = which - 1;
                        if (backupIndex >= 0 && backupIndex < backups.size()) {
                            playUrl = backups.get(backupIndex);
                        } else {
                            playUrl = currentChannel.getMainPlayUrl();
                        }
                    }
                    playerManager.playUrl(playUrl, currentChannel.getName(), currentChannel);
                }
            }
            // 换线路后清晰度可能跟着变（目标线路没有当前档位时会回落到该线路最高档），
            // 所以「清晰度选择」那行也要跟着刷，避免显示与实际不符。
            refreshStreamStatusRows();
            Toast.makeText(getContext(), "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 【2026-10-07 新增】咪咕「频道线路选择」。
     *
     * <p>线路名来自播放器的「线路 × 清晰度」轴（{@code MiguStreamAxis}），形态如
     * 「咪咕」「央视频」「央视」「CDN直链」，而非虎牙那种「源N · CDN」。
     * 打勾落在<b>当前实际生效</b>的线路上（解析选源 / 手动切换都会更新），不盲信 SP 记忆下标。
     */
    private void showMiguLineDialog(TVPlayerManager playerManager) {
        List<String> miguLines = playerManager.getMiguLineNames();
        if (miguLines.isEmpty()) {
            Toast.makeText(getContext(), "咪咕线路尚未就绪，请稍候再试", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lineArray = miguLines.toArray(new String[0]);
        // 打勾落在当前实际生效的线路；匹配不到时交给弹窗的默认选中项
        int currentLineIndex = playerManager.getCurrentMiguLineUiIndex();
        int checkedItem = (currentLineIndex >= 0 && currentLineIndex < lineArray.length)
                ? currentLineIndex : 0;

        showCommonSelectionDialog("频道线路选择", lineArray, checkedItem, (which) -> {
            tv_channel_line.setText(lineArray[which]);
            playerManager.switchMiguLine(which);
            // 换线路后该线路可能没有当前档位 → 清晰度会跟着变，两行一起刷
            refreshStreamStatusRows();
            Toast.makeText(getContext(), "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 【2026-10-07 新增】畅享（源3）「频道线路选择」—— 完全镜像 {@link #showMiguLineDialog}。
     *
     * <p>线路名来自播放器的「线路 × 清晰度」轴（{@code ChanxiangStreamAxis}），形态如
     * 「节点zj500」「节点js1000」（CDN 节点），不含任何清晰度字样。
     * 打勾落在<b>当前实际生效</b>的线路上；切换后两行状态一起刷。
     */
    private void showChanxiangLineDialog(TVPlayerManager playerManager) {
        List<String> cxLines = playerManager.getChanxiangLineNames();
        if (cxLines.isEmpty()) {
            Toast.makeText(getContext(), "畅享线路尚未就绪，请稍候再试", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lineArray = cxLines.toArray(new String[0]);
        int currentLineIndex = playerManager.getCurrentChanxiangLineUiIndex();
        int checkedItem = (currentLineIndex >= 0 && currentLineIndex < lineArray.length)
                ? currentLineIndex : 0;

        showCommonSelectionDialog("频道线路选择", lineArray, checkedItem, (which) -> {
            tv_channel_line.setText(lineArray[which]);
            playerManager.switchChanxiangLine(which);
            // 换线路后该线路可能没有当前档位 → 清晰度会跟着变，两行一起刷
            refreshStreamStatusRows();
            Toast.makeText(getContext(), "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 【2026-10-11 新增】源7（afhgrj 影视）「频道线路选择」—— 参照 {@link #showMiguLineDialog}。
     *
     * <p>线路名来自该片的播放源 sid（形如「线路1」「线路2」，每部片 1~3 个，各走不同 CDN）。
     * 打勾落在<b>最近生效/手选</b>的 sid 上；切换走 {@code switchAfhgrjLine}（保持当前集重播）。
     */
    private void showAfhgrjLineDialog(TVPlayerManager playerManager) {
        List<String> afLines = playerManager.getAfhgrjLineNames();
        if (afLines.isEmpty()) {
            Toast.makeText(getContext(), "源7线路尚未就绪，请先播放再切换", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lineArray = afLines.toArray(new String[0]);
        int currentLineIndex = playerManager.getCurrentAfhgrjLineUiIndex();
        int checkedItem = (currentLineIndex >= 0 && currentLineIndex < lineArray.length)
                ? currentLineIndex : 0;

        showCommonSelectionDialog("频道线路选择", lineArray, checkedItem, (which) -> {
            tv_channel_line.setText(lineArray[which]);
            playerManager.switchAfhgrjLine(which);
            // 换线路后清晰度仍为自适应，两行一起刷即可
            refreshStreamStatusRows();
            Toast.makeText(getContext(), "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
    }

    private void initRedirectDefaultConfig() {
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT,5);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL,false);
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            editor.putString(KEY_USER_AGENT_MODE, "exo");
            editor.apply();
        }
    }

    private void updateRedirectSettingText() {
        int max = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        String uaLabel = "exo".equals(uaMode) ? "ExoPlayer" : "VLC";
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain?"开":"关").append(" | ");
        sb.append("跨协议：").append(crossProto?"开":"关").append("\n");
        sb.append("携带请求头：").append(followHeader?"开":"关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl?"开":"关").append(" | ");
        sb.append("授权令牌：").append(sendCookie?"开":"关").append(" | ");
        sb.append("UA：").append(uaLabel);
        tv_redirect_setting.setText(sb.toString());
    }

    private void showResolutionDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(getContext());
        if (playerManager == null) {
            return;
        }
        // 【2026-09-29 照抄 SDK】清晰度弹窗标题**固定，不带线路名**。
        // SDK 的 LineListFragment 里「线路」与「清晰度」是两块并列列表，标题各自恒定
        // （"线路："/"清晰度："）—— 清晰度标题从不标注它属于哪条线路，两轴互不串味。
        // 列表内容本身仍按当前线路收敛（见 TVPlayerManager#getAvailableResolutions），只是标题不写。
        // 【2026-10-07】畅享频道轴未就绪时先惰性构建（单请求 + 缓存），就绪后回调刷新，提示稍候。
        Channel cxChannel = playerManager.getCurrentChannel();
        if (cxChannel != null && playerManager.isChanxiangHttpChannel(cxChannel)
                && !playerManager.hasChanxiangAxis()) {
            Toast.makeText(getContext(), "清晰度加载中，请稍候再试", Toast.LENGTH_SHORT).show();
            playerManager.ensureChanxiangAxisAsync(this::refreshStreamStatusRows);
            return;
        }
        // 【2026-10-11】源7（afhgrj 影视）频道：落地流是单条 m3u8，无枚举清晰度，
        //   多码率由播放器自适应 —— 提供一个「自适应」档（不报「不支持切换」）。
        if (playerManager.isAfhgrjChannel()) {
            String[] a7Items = {"自适应"};
            showCommonSelectionDialog("清晰度选择", a7Items, 0, (which) -> {
                tv_resolution_status.setText(a7Items[which]);
                Toast.makeText(getContext(), "已切换至: " + a7Items[which], Toast.LENGTH_SHORT).show();
            });
            return;
        }
        List<String> resolutions = playerManager.getAvailableResolutions();
        if (resolutions.isEmpty()) {
            Toast.makeText(getContext(), "当前直播源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = resolutions.toArray(new String[0]);

        String currentResLabel = playerManager.getCurrentResolutionLabel();
        int initialPos = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(currentResLabel)) {
                initialPos = i;
                break;
            }
        }

        showCommonSelectionDialog("清晰度选择", items, initialPos, (which) -> {
            String selectedLabel = items[which];
            int targetHeight = 0;
            if (selectedLabel.contains("4K")) targetHeight = 2160;
            else if (selectedLabel.contains("1080p")) targetHeight = 1080;
            else if (selectedLabel.contains("720p")) targetHeight = 720;
            else {
                try {
                    targetHeight = Integer.parseInt(selectedLabel.replace("p", ""));
                } catch (Exception ignored) {

                }
            }

            // 2026-09-19：不再写 SP memory（播放链路不读它，写了只会让设置界面长期显示过期的档位）。
            // 手动选择只对本次播放生效；下次起播仍由播放链路按实际可用性决定档位。
            playerManager.switchToResolution(targetHeight, selectedLabel);
            tv_resolution_status.setText(selectedLabel);
            Toast.makeText(getContext(), "已切换至: " + selectedLabel, Toast.LENGTH_SHORT).show();
        });
    }

    private void showDecoderModeDialog() {
        final String[] modes = {"自动", "硬解", "软解"};
        final String[] modeValues = {"auto", "hard", "soft"};
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }

        showCommonSelectionDialog("解码器选择", modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("decoder_mode", selectedMode).apply();
            updateDecoderModeText(selectedMode);

            Intent intent = new Intent("com.tv.live.DECODER_MODE_CHANGED");
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);

            Toast.makeText(getContext(), "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard": tv_decoder_mode.setText("硬解"); break;
            case "soft": tv_decoder_mode.setText("软解"); break;
            case "auto": default: tv_decoder_mode.setText("自动"); break;
        }
    }

    private void showRendererModeDialog() {
        final String[] modes = {"SurfaceView（默认）", "TextureView（兼容）"};
        final String[] modeValues = {"surface", "texture"};
        String currentMode = sp.getString("renderer_type", "surface");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }

        showCommonSelectionDialog(getContext().getString(R.string.render_mode_select), modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("renderer_type", selectedMode).apply();
            updateRendererModeText(selectedMode);

            Intent intent = new Intent("com.tv.live.RENDERER_TYPE_CHANGED");
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);

            Toast.makeText(getContext(), "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateRendererModeText(String mode) {
        if (tv_renderer_type == null) return;
        switch (mode) {
            case "texture": tv_renderer_type.setText(getContext().getString(R.string.texture_view)); break;
            case "surface": default: tv_renderer_type.setText(getContext().getString(R.string.surface_view)); break;
        }
    }

    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        String currentMode = sp.getString("screen_ratio", "全屏");
        int checkedItem = 0;
        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }

        showCommonSelectionDialog("屏幕比例", ratios, checkedItem, (which) -> {
            sp.edit().putString("screen_ratio", ratios[which]).apply();
            Toast.makeText(getContext(), "已设置", Toast.LENGTH_SHORT).show();
        });
    }

    private void showSubscriptionDialog(String spKey, String title) {
        SourceManager sourceManager = new SourceManager(getContext(), spKey);
        List<SourceManager.SourceItem> sources = sourceManager.getAllSources();

        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(
                new android.view.ContextThemeWrapper(getContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
        );
        View dialogView = inflater.inflate(R.layout.dialog_subscription, null);

        ListView lvSourceList = dialogView.findViewById(R.id.lv_source_list);
        ImageView ivQrCode = dialogView.findViewById(R.id.iv_qr_code);
        TextView tvIpAddress = dialogView.findViewById(R.id.tv_ip_address);
        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        LinearLayout llScanHeader = dialogView.findViewById(R.id.ll_scan_header);
        EditText etName = dialogView.findViewById(R.id.et_name);
        EditText etUrl = dialogView.findViewById(R.id.et_url);
        Button btnClear = dialogView.findViewById(R.id.btn_clear);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        Button btnClose = dialogView.findViewById(R.id.btn_close);

        boolean isLive = "live_history".equals(spKey);
        tvIpAddress.setText(currentWebUrl);

        if (isLive) {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.VISIBLE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.VISIBLE);

            com.tv.live.util.AppExecutors.io(() -> {
                Bitmap qrBitmap = null;
                try {
                    qrBitmap = qrCodeManager.createQR(currentWebUrl, 240);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final Bitmap finalQrBitmap = qrBitmap;
                mainHandler.post(() -> {
                    if (finalQrBitmap != null) {
                        ivQrCode.setImageBitmap(finalQrBitmap);
                    } else {
                        ivQrCode.setBackgroundColor(Color.LTGRAY);
                    }
                });
            });

            ivQrCode.setOnClickListener(v -> {
                Toast.makeText(getContext(), "已生成二维码，请扫码", Toast.LENGTH_SHORT).show();
            });
            etName.setHint("请输入名称(选填)");
            etUrl.setHint("请输入地址");
        } else {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.GONE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.GONE);
            etName.setHint("请输入节目单名称(选填)");
            etUrl.setHint("请输入EPG节目单地址");
        }

        etName.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    lvSourceList.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {

                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    etUrl.requestFocus();
                    return true;
                }
            }
            return false;
        });
        etUrl.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    lvSourceList.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    etName.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {

                    btnClear.requestFocus();
                    return true;
                }
            }
            return false;
        });

        int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
        SubscriptionAdapter adapter = new SubscriptionAdapter(getContext(), sources);

        adapter.setOnActionListener(new SubscriptionAdapter.OnActionListener() {
            @Override
            public void onSwitch(int position) {

                SourceManager.SourceItem pickedDiagnose = (position >= 0 && position < sources.size()) ? sources.get(position) : null;
                LogBridge.d("SUBSCRIPTION", "onSwitch called: position=" + position
                    + " | pickedName=" + (pickedDiagnose != null ? pickedDiagnose.name : "null")
                    + " | pickedUrl=" + (pickedDiagnose != null ? pickedDiagnose.url : "null")
                    + " | spKey=" + spKey);

                if (position < 0 || position >= sources.size()) return;
                SourceManager.SourceItem picked = sources.get(position);

                List<SourceManager.SourceItem> latest = sourceManager.getAllSources();
                int realPos = -1;
                for (int i = 0; i < latest.size(); i++) {
                    SourceManager.SourceItem si = latest.get(i);
                    if (si != null && TextUtils.equals(si.url, picked.url) && TextUtils.equals(si.name, picked.name)) {
                        realPos = i; break;
                    }
                }
                if (realPos < 0) {

                    for (int i = 0; i < latest.size(); i++) {
                        SourceManager.SourceItem si = latest.get(i);
                        if (si != null && TextUtils.equals(si.name, picked.name)) { realPos = i; break; }
                    }
                }
                if (realPos < 0) {

                    if (position < 0 || position >= latest.size()) return;
                    realPos = position;
                }
                sourceManager.setDefault(realPos);

                SharedPreferences appSp = getContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                if ("live_history".equals(spKey)) {
                    appSp.edit().remove("custom_live_url").apply();
                    UrlConfig.LIVE_URL = latest.get(realPos).url;

                } else if ("epg_history".equals(spKey)) {
                    appSp.edit().remove("custom_epg_url").apply();
                    UrlConfig.EPG_URL = latest.get(realPos).url;
                }

                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(getContext().getPackageName());
                getContext().sendBroadcast(intent);

                Toast.makeText(getContext(), "已切换到：" + latest.get(realPos).name, Toast.LENGTH_SHORT).show();
                adapter.setSelectedPosition(position);
                lvSourceList.post(() -> {
                    adapter.notifyDataSetChanged();
                    lvSourceList.setSelection(position);
                    lvSourceList.requestFocus();
                });
            }

            @Override
            public void onDelete(int position) {
                if (position < 0 || position >= sources.size()) {
                    return;
                }
                SourceManager.SourceItem item = sources.get(position);

                AlertDialog deleteDialog = new AlertDialog.Builder(getContext())
                        .setTitle("确认删除")
                        .setMessage("确定要删除「" + item.name + "」吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            int realIndex = sourceManager.indexOfUrl(item.url);
                            if (realIndex >= 0 && realIndex < sourceManager.size()) {
                                sourceManager.removeSource(realIndex);
                                sources.clear();
                                sources.addAll(sourceManager.getAllSources());
                                int newDefaultPos = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
                                adapter.setSelectedPosition(newDefaultPos);
                                lvSourceList.post(() -> {
                                    adapter.notifyDataSetChanged();
                                    if (newDefaultPos >= 0) {
                                        lvSourceList.setSelection(newDefaultPos);
                                    }
                                    lvSourceList.requestFocus();
                                });
                                Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(getContext(), "删除失败，源未找到", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .create();

                if (deleteDialog.getWindow() != null) {
                    deleteDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                deleteDialog.show();

                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF55576A));
            }
        });

        lvSourceList.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        lvSourceList.setAdapter(adapter);
        adapter.setListView(lvSourceList);

        if (currentDefault >= 0) {
            adapter.setSelectedPosition(currentDefault);
        }

        lvSourceList.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {

                lvSourceList.setItemChecked(position, true);

                if (view != null) {

                    if (parent instanceof android.view.ViewGroup) {
                        android.view.ViewGroup vg = (android.view.ViewGroup) parent;
                        for (int i = 0; i < vg.getChildCount(); i++) {
                            android.view.View sib = vg.getChildAt(i);
                            if (sib != null && sib != view) sib.setSelected(false);
                        }
                    }
                    view.setSelected(true);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        final Button finalBtnConfirm = btnConfirm;
        final Button finalBtnClose = btnClose;

        setupTwoStepTintButton(finalBtnConfirm, () -> {
            String name = etName.getText().toString().trim();
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(getContext(), "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (sourceManager.addSource(name, url)) {
                etName.setText("");
                etUrl.setText("");
                sources.clear();
                sources.addAll(sourceManager.getAllSources());
                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                adapter.notifyDataSetChanged();
                Toast.makeText(getContext(), "已添加，正在刷新...", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(getContext().getPackageName());
                getContext().sendBroadcast(intent);
            } else {
                Toast.makeText(getContext(), "该地址已存在", Toast.LENGTH_SHORT).show();
            }
        }, Color.WHITE, 0xFF55576A);

        final Button finalBtnClear = btnClear;
        setupTwoStepTintButton(finalBtnClear, () -> {
            etName.setText("");
            etUrl.setText("");
        }, Color.WHITE, 0xFF55576A);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    LogBridge.d("SUBSCRIPTION", "ENTER/DPAD_CENTER global: hasFocus=" + lvSourceList.hasFocus() + " selectedPos=" + adapter.getSelectedPosition() + " sources.size=" + sources.size());
                    if (lvSourceList.hasFocus()) {
                        int position = adapter.getSelectedPosition();
                        if (position >= 0 && position < sources.size()) {

                            SourceManager.SourceItem picked = sources.get(position);
                            List<SourceManager.SourceItem> latest = sourceManager.getAllSources();
                            int realPos = -1;
                            for (int i = 0; i < latest.size(); i++) {
                                SourceManager.SourceItem li = latest.get(i);
                                if (picked.name.equals(li.name) && picked.url.equals(li.url)) { realPos = i; break; }
                            }
                            if (realPos < 0) {
                                for (int i = 0; i < latest.size(); i++) {
                                    SourceManager.SourceItem li = latest.get(i);
                                    if (picked.name.equals(li.name)) { realPos = i; break; }
                                }
                            }
                            if (realPos < 0) {
                                if (position < latest.size()) realPos = position;
                                else return true;
                            }
                            sourceManager.setDefault(realPos);
                            SharedPreferences appSp = getContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                            if ("live_history".equals(spKey)) {
                                appSp.edit().remove("custom_live_url").apply();
                                UrlConfig.LIVE_URL = latest.get(realPos).url;
                            } else if ("epg_history".equals(spKey)) {
                                appSp.edit().remove("custom_epg_url").apply();
                                UrlConfig.EPG_URL = latest.get(realPos).url;
                            }
                            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                            intent.setPackage(getContext().getPackageName());
                            getContext().sendBroadcast(intent);
                            Toast.makeText(getContext(), "已切换到：" + latest.get(realPos).name, Toast.LENGTH_SHORT).show();
                            adapter.setSelectedPosition(position);
                            lvSourceList.post(() -> {
                                adapter.notifyDataSetChanged();
                                lvSourceList.setSelection(position);
                                lvSourceList.requestFocus();
                            });
                        }
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                }
            }
            return false;
        });
        dialog.show();
        if (btnClose != null) {
            setupTwoStepTintButton(finalBtnClose, () -> dialog.dismiss(), Color.WHITE, 0xFF55576A);
        }
        mainHandler.postDelayed(() -> {
            if (lvSourceList != null && dialog.isShowing()) {
                lvSourceList.requestFocus();
                if (currentDefault >= 0) {
                    lvSourceList.setSelection(currentDefault);
                }

                adapter.ensureActivatedImmediate();
            }
        }, 200);
    }

    private void showRedirectConfigDialog() {
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        final String[] currentUaMode = { sp.getString(KEY_USER_AGENT_MODE, "exo") };

        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(
                new android.view.ContextThemeWrapper(getContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
        );
        View dialogView = inflater.inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);
        SwitchCompat swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        SwitchCompat swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        SwitchCompat swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        SwitchCompat swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        SwitchCompat swSendCookie = dialogView.findViewById(R.id.sw_send_cookie);
        LinearLayout llUserAgent = dialogView.findViewById(R.id.ll_user_agent);
        TextView tvUserAgentStatus = dialogView.findViewById(R.id.tv_user_agent_status);
        Button btnCancel = dialogView.findViewById(R.id.btn_redirect_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_redirect_save);

        android.widget.ScrollView svContent = dialogView.findViewById(R.id.sv_redirect_content);
        if (svContent != null) {
            android.util.DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            int maxHeightPx = (int) (dm.heightPixels * 0.6f);
            android.view.ViewGroup.LayoutParams lp = svContent.getLayoutParams();
            if (lp.height > maxHeightPx) {
                lp.height = maxHeightPx;
                svContent.setLayoutParams(lp);
            }
        }

        LinearLayout llMaxCount = dialogView.findViewById(R.id.ll_max_count);
        LinearLayout llCrossDomain = dialogView.findViewById(R.id.ll_cross_domain);
        LinearLayout llCrossProto = dialogView.findViewById(R.id.ll_cross_proto);
        LinearLayout llFollowHeader = dialogView.findViewById(R.id.ll_follow_header);
        LinearLayout llSendCookie = dialogView.findViewById(R.id.ll_send_cookie);
        LinearLayout llIgnoreSsl = dialogView.findViewById(R.id.ll_ignore_ssl);
        TextView tvCrossDomain = dialogView.findViewById(R.id.tv_cross_domain);
        TextView tvCrossProto = dialogView.findViewById(R.id.tv_cross_proto);
        TextView tvFollowHeader = dialogView.findViewById(R.id.tv_follow_header);
        TextView tvSendCookie = dialogView.findViewById(R.id.tv_send_cookie);
        TextView tvIgnoreSsl = dialogView.findViewById(R.id.tv_ignore_ssl);

        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC");
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        etMax.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_ENTER) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(etMax.getWindowToken(), 0);
                    llCrossDomain.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(etMax.getWindowToken(), 0);
                    llMaxCount.requestFocus();
                    return true;
                }
            }
            return false;
        });
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        final int[] pendingPos = {0};
        final LinearLayout[] items = {llMaxCount, llCrossDomain, llCrossProto, llFollowHeader, llSendCookie, llIgnoreSsl, llUserAgent};
        final String[] itemNames = {"maxCount", "crossDomain", "crossProto", "followHeader", "sendCookie", "ignoreSsl", "userAgent"};

        llMaxCount.setBackgroundColor(0x3340A9FF);
        for (int i = 0; i < llMaxCount.getChildCount(); i++) {
            View child = llMaxCount.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(0xFF40A9FF);
            } else if (child instanceof EditText) {
                ((EditText) child).setTextColor(0xFF40A9FF);
            }
        }

        View.OnClickListener clickListener = v -> {
            int currentPos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    currentPos = i;
                    break;
                }
            }

            if (pendingPos[0] == currentPos) {
                if (currentPos == 0) {
                    int currentVal = Integer.parseInt(etMax.getText().toString());
                    showNumberInputDialog(currentVal, newVal -> {
                        etMax.setText(String.valueOf(newVal));
                    });
                } else if (currentPos == 1) {
                    swCrossDomain.setChecked(!swCrossDomain.isChecked());
                } else if (currentPos == 2) {
                    swCrossProto.setChecked(!swCrossProto.isChecked());
                } else if (currentPos == 3) {
                    swFollowHeader.setChecked(!swFollowHeader.isChecked());
                } else if (currentPos == 4) {
                    swSendCookie.setChecked(!swSendCookie.isChecked());
                } else if (currentPos == 5) {
                    swIgnoreSsl.setChecked(!swIgnoreSsl.isChecked());
                } else if (currentPos == 6) {
                    final String[] uaOptions = {"ExoPlayer默认", "VLC"};
                    final String[] uaValues = {"exo", "vlc"};
                    int checkedItem = 0;
                    for (int i = 0; i < uaValues.length; i++) {
                        if (uaValues[i].equals(currentUaMode[0])) {
                            checkedItem = i;
                            break;
                        }
                    }
                    showCommonSelectionDialog("UA切换", uaOptions, checkedItem, (which) -> {
                        currentUaMode[0] = uaValues[which];
                        tvUserAgentStatus.setText(uaOptions[which]);
                    });
                }
            } else {
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0xFF272B3A);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        } else if (child instanceof EditText) {
                            ((EditText) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = currentPos;
            }
        };

        View.OnKeyListener keyListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0) {
                        if (currentPos == 0) {
                            int currentVal = Integer.parseInt(etMax.getText().toString());
                            showNumberInputDialog(currentVal, newVal -> {
                                etMax.setText(String.valueOf(newVal));
                            });
                        } else if (currentPos == 1) {
                            swCrossDomain.setChecked(!swCrossDomain.isChecked());
                        } else if (currentPos == 2) {
                            swCrossProto.setChecked(!swCrossProto.isChecked());
                        } else if (currentPos == 3) {
                            swFollowHeader.setChecked(!swFollowHeader.isChecked());
                        } else if (currentPos == 4) {
                            swSendCookie.setChecked(!swSendCookie.isChecked());
                        } else if (currentPos == 5) {
                            swIgnoreSsl.setChecked(!swIgnoreSsl.isChecked());
                        } else if (currentPos == 6) {
                            final String[] uaOptions = {"ExoPlayer默认", "VLC"};
                            final String[] uaValues = {"exo", "vlc"};
                            int checkedItem = 0;
                            for (int i = 0; i < uaValues.length; i++) {
                                if (uaValues[i].equals(currentUaMode[0])) {
                                    checkedItem = i;
                                    break;
                                }
                            }
                            showCommonSelectionDialog("UA切换", uaOptions, checkedItem, (which) -> {
                                currentUaMode[0] = uaValues[which];
                                tvUserAgentStatus.setText(uaOptions[which]);
                            });
                        }
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0 && currentPos == items.length - 1 && btnCancel != null) {
                        btnCancel.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    int currentPos = -1;
                    for (int i = 0; i < items.length; i++) {
                        if (items[i] == v) {
                            currentPos = i;
                            break;
                        }
                    }
                    if (currentPos >= 0 && currentPos == 0) {
                        return true;
                    }
                }
            }
            return false;
        };

        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            int pos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    pos = i;
                    break;
                }
            }
            if (hasFocus && pos >= 0) {
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0x00000000);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        } else if (child instanceof EditText) {
                            ((EditText) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = pos;
            } else {
                v.setBackgroundColor(0x00000000);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(Color.WHITE);
                    } else if (child instanceof EditText) {
                        ((EditText) child).setTextColor(Color.WHITE);
                    }
                }
            }
        };

        llMaxCount.setOnClickListener(clickListener);
        llMaxCount.setOnKeyListener(keyListener);
        llMaxCount.setOnFocusChangeListener(focusListener);
        llMaxCount.setNextFocusDownId(R.id.ll_cross_domain);
        llCrossDomain.setNextFocusUpId(R.id.ll_max_count);
        llCrossDomain.setOnClickListener(clickListener);
        llCrossDomain.setOnKeyListener(keyListener);
        llCrossDomain.setOnFocusChangeListener(focusListener);
        llCrossProto.setOnClickListener(clickListener);
        llCrossProto.setOnKeyListener(keyListener);
        llCrossProto.setOnFocusChangeListener(focusListener);
        llFollowHeader.setOnClickListener(clickListener);
        llFollowHeader.setOnKeyListener(keyListener);
        llFollowHeader.setOnFocusChangeListener(focusListener);
        llSendCookie.setOnClickListener(clickListener);
        llSendCookie.setOnKeyListener(keyListener);
        llSendCookie.setOnFocusChangeListener(focusListener);
        llIgnoreSsl.setOnClickListener(clickListener);
        llIgnoreSsl.setOnKeyListener(keyListener);
        llIgnoreSsl.setOnFocusChangeListener(focusListener);
        llUserAgent.setOnClickListener(clickListener);
        llUserAgent.setOnKeyListener(keyListener);
        llUserAgent.setOnFocusChangeListener(focusListener);
        llUserAgent.setNextFocusDownId(R.id.btn_redirect_cancel);
        btnCancel.setNextFocusUpId(R.id.ll_user_agent);
        btnCancel.setNextFocusRightId(R.id.btn_redirect_save);
        btnSave.setNextFocusLeftId(R.id.btn_redirect_cancel);
        btnSave.setNextFocusUpId(R.id.ll_user_agent);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.85),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        final Button finalBtnCancel = btnCancel;
        final Button finalBtnSave = btnSave;
        final LinearLayout finalLlUserAgent = llUserAgent;
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalLlUserAgent) && finalBtnCancel != null) {
                        finalBtnCancel.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && (focused.equals(finalBtnCancel) || focused.equals(finalBtnSave)) && finalLlUserAgent != null) {
                        finalLlUserAgent.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalBtnCancel) && finalBtnSave != null) {
                        finalBtnSave.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    View focused = dialog.getCurrentFocus();
                    if (focused != null && focused.equals(finalBtnSave) && finalBtnCancel != null) {
                        finalBtnCancel.requestFocus();
                        return true;
                    }
                }
            }
            return false;
        });
        dialog.show();
        mainHandler.postDelayed(() -> {
            if (llMaxCount != null && dialog.isShowing()) {
                llMaxCount.requestFocus();
            }
        }, 200);

        setupTwoStepTintButton(finalBtnCancel, () -> {
            dialog.dismiss();
        }, Color.WHITE, 0xFF55576A);

        btnCancel.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && finalLlUserAgent != null) {
                    finalLlUserAgent.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return true;
                }
            }
            return false;
        });

        setupTwoStepTintButton(finalBtnSave, () -> {
            String maxStr = etMax.getText().toString().trim();
            int newMax = 5;
            if (!TextUtils.isEmpty(maxStr)) {
                try {
                    newMax = Integer.parseInt(maxStr);
                    if(newMax < 1) newMax = 1;
                    if(newMax > 20) newMax = 20;
                }catch (Exception ignored){ newMax =5; }
            }
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT, newMax);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, swCrossDomain.isChecked());
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, swCrossProto.isChecked());
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, swFollowHeader.isChecked());
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, swIgnoreSsl.isChecked());
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, swSendCookie.isChecked());
            editor.putString(KEY_USER_AGENT_MODE, currentUaMode[0]);
            editor.apply();
            updateRedirectSettingText();
            Toast.makeText(getContext(), "重定向配置保存成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }, Color.WHITE, 0xFF55576A);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            View[] items = {
                findViewById(R.id.item_boot),
                findViewById(R.id.item_reverse),
                findViewById(R.id.item_pip),
                findViewById(R.id.item_channel_line),
                findViewById(R.id.item_resolution),
                findViewById(R.id.item_decoder),
                findViewById(R.id.item_renderer),
                findViewById(R.id.tv_screen_ratio),
                findViewById(R.id.item_redirect),
                findViewById(R.id.item_live_subscribe),
                findViewById(R.id.item_epg_subscribe),
                findViewById(R.id.item_source_mode),
                findViewById(R.id.item_background_switch),
                findViewById(R.id.item_exit_dialog),
                findViewById(R.id.item_version_info),
                findViewById(R.id.item_js_update)
            };

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                backHandled = true;
                dismiss();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int nextPos = selectedItemPosition + 1;
                if (nextPos < items.length && items[nextPos] != null) {
                    items[nextPos].requestFocus();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                int prevPos = selectedItemPosition - 1;
                if (prevPos >= 0 && items[prevPos] != null) {
                    items[prevPos].requestFocus();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (System.currentTimeMillis() - mShowTime < IGNORE_KEY_DELAY_MS) {
                    LogBridge.d("SettingsDialog", "忽略打开后短时间内的按键，防止误触");
                    return true;
                }
                performItemAction(selectedItemPosition);
                return true;
            }
        } catch (Exception e) {
            LogBridge.e("SettingsDialog", "onKeyDown 异常: " + e.getMessage(), e);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        try {
            return super.onKeyUp(keyCode, event);
        } catch (Exception e) {
            LogBridge.e("SettingsDialog", "onKeyUp 异常: " + e.getMessage(), e);
            return true;
        }
    }

    @Override
    public void onBackPressed() {
        if (!isDismissing) {
            dismiss();
        }
    }

    @Override
    public void dismiss() {
        if (isDismissing) {
            LogBridge.d("SettingsDialog", "dismiss 已在执行中，忽略重复调用");
            return;
        }
        isDismissing = true;
        try {
            if (webServerManager != null) {
                webServerManager.stop();
            }
            mainHandler.removeCallbacksAndMessages(null);

            Intent unlockIntent = new Intent("com.tv.live.UNLOCK_SETTINGS");
            unlockIntent.setPackage(getContext().getPackageName());
            try {
                getContext().sendBroadcast(unlockIntent);
            } catch (Exception e) {
                LogBridge.e("SettingsDialog", "发送解锁广播失败: " + e.getMessage(), e);
            }

            super.dismiss();
        } catch (Exception e) {
            LogBridge.e("SettingsDialog", "dismiss 异常: " + e.getMessage(), e);
            isDismissing = false;
        }

        try {
            MainActivity activity = MainActivity.getRunningInstance();
            if (activity != null && !activity.isFinishing()) {
                activity.refreshSettings();
            }
        } catch (Exception e) {
            LogBridge.e("SettingsDialog", "refreshSettings 异常: " + e.getMessage(), e);
        }
    }

    private void setupTwoStepTintButton(Button button, Runnable action, int normalTextColor, int normalBgColor) {
        final boolean[] pending = {false};
        final int selectedTextColor = 0xFF40A9FF;
        final int selectedBgColor = 0xFFB3D9FF;

        final ColorStateList origTint = button.getBackgroundTintList();
        final android.graphics.drawable.Drawable origBg = button.getBackground();
        final int origTextColor = normalTextColor;
        final Typeface origTypeface = button.getTypeface();

        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                button.setTextColor(selectedTextColor);
                button.setTypeface(origTypeface, Typeface.BOLD);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(selectedBgColor);
                gd.setCornerRadius(dp2px(6));
                button.setBackground(gd);
            } else {
                pending[0] = false;
                button.setTextColor(origTextColor);
                button.setTypeface(origTypeface, Typeface.NORMAL);
                if (origTint != null) {
                    android.graphics.drawable.Drawable clonedBg = origBg.getConstantState().newDrawable().mutate();
                    button.setBackground(clonedBg);
                    button.setBackgroundTintList(origTint);
                } else {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor(normalBgColor);
                    gd.setCornerRadius(dp2px(6));
                    button.setBackground(gd);
                }
            }
        });

        button.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {

                    pending[0] = false;
                    action.run();
                    return true;
                }
            }
            return false;
        });

        button.setOnClickListener(v -> {
            pending[0] = false;
            action.run();
        });
    }

    private void setChildTextViewsBold(android.view.View parent, boolean bold) {
        try {
            if (parent instanceof android.widget.TextView) {
                ((android.widget.TextView) parent).setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            } else if (parent instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) parent;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    setChildTextViewsBold(vg.getChildAt(i), bold);
                }
            }
        } catch (Exception ignored) {}
    }}
