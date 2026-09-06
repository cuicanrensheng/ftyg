package com.tv.live.manager;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class DisplayManager {

    private final Activity activity;
    private View loadingView;
    private TextView tvLoadingText;
    private boolean loadingViewInitialized = false;

    private boolean fullScreenApplied = false;

    public DisplayManager(Activity activity) {
        this.activity = activity;
    }

    public void applyFullScreen() {

        if (fullScreenApplied) {
            return;
        }

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
                    lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                activity.getWindow().setAttributes(lp);
            }

            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    android.view.WindowInsetsController controller =
                            activity.getWindow().getInsetsController();
                    if (controller != null) {
                        controller.hide(android.view.WindowInsets.Type.systemBars());
                        controller.setSystemBarsBehavior(
                                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        );
                    }
                    activity.getWindow().setDecorFitsSystemWindows(false);
                } catch (Exception e) {

                    e.printStackTrace();
                }
            }

            fullScreenApplied = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void reapplyFullScreen() {

        fullScreenApplied = false;
        applyFullScreen();
    }

    private void initLoadingView() {
        if (loadingViewInitialized) return;

        try {
            FrameLayout rootLayout = activity.findViewById(android.R.id.content);

            FrameLayout loadingLayout = new FrameLayout(activity);
            loadingLayout.setBackgroundColor(0xEE000000);
            loadingLayout.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            loadingLayout.setVisibility(View.GONE);

            LinearLayout linearLayout = new LinearLayout(activity);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            llParams.gravity = Gravity.CENTER;
            linearLayout.setLayoutParams(llParams);

            ProgressBar progressBar = new ProgressBar(activity);
            linearLayout.addView(progressBar);

            tvLoadingText = new TextView(activity);
            tvLoadingText.setText("加载中...");
            tvLoadingText.setTextColor(Color.WHITE);
            tvLoadingText.setTextSize(16);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMargins(0, 20, 0, 0);
            tvLoadingText.setLayoutParams(textParams);
            linearLayout.addView(tvLoadingText);

            loadingLayout.addView(linearLayout);
            rootLayout.addView(loadingLayout);

            loadingView = loadingLayout;
            loadingViewInitialized = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void showLoading(String text) {
        if (!loadingViewInitialized) {
            initLoadingView();
        }

        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (tvLoadingText != null && text != null) {
            tvLoadingText.setText(text);
        }
    }

    public void showLoading() {
        showLoading("加载中...");
    }

    public void updateLoadingText(String text) {
        if (tvLoadingText != null && text != null) {
            tvLoadingText.setText(text);
        }
    }

    public void hideLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
    }

    public boolean isLoadingShowing() {
        return loadingView != null && loadingView.getVisibility() == View.VISIBLE;
    }

    public void release() {

        if (loadingView != null && loadingView.getParent() != null) {
            try {
                ((ViewGroup) loadingView.getParent()).removeView(loadingView);
            } catch (Exception e) {

            }
        }
        loadingView = null;
        tvLoadingText = null;
        loadingViewInitialized = false;
        fullScreenApplied = false;
    }
}
