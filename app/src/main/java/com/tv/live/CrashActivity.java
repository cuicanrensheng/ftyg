package com.tv.live;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class CrashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        try {
            Window w = getWindow();
            if (w != null) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();

                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;
                lp.width  = Math.max(screenW * 92 / 100, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                lp.height = Math.max(screenH * 82 / 100, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER;
                w.setAttributes(lp);
            }
        } catch (Throwable ignored) {}

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFFFFFFF);
        rootLayout.setPadding(dp(24), dp(24), dp(24), dp(24));
        rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("😢 应用崩溃了");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp(16));
        tvTitle.setLayoutParams(titleParams);
        rootLayout.addView(tvTitle);

        TextView tvError = new TextView(this);
        tvError.setTextSize(15);
        tvError.setTextColor(0xFFFF4D4F);
        tvError.setGravity(Gravity.CENTER);
        tvError.setMaxLines(4);
        tvError.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(0, 0, 0, dp(16));
        tvError.setLayoutParams(errorParams);
        rootLayout.addView(tvError);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1);
        scrollParams.setMargins(0, 0, 0, dp(16));
        scrollView.setLayoutParams(scrollParams);

        TextView tvDetail = new TextView(this);
        tvDetail.setTextSize(11);
        tvDetail.setTextColor(Color.BLACK);
        tvDetail.setPadding(dp(16), dp(16), dp(16), dp(16));
        tvDetail.setBackgroundColor(0xFFF5F5F5);

        tvDetail.setMinEms(40);
        tvDetail.setHorizontallyScrolling(false);
        tvDetail.setSingleLine(false);

        scrollView.addView(tvDetail, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        rootLayout.addView(scrollView);

        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLayout.setLayoutParams(btnLayoutParams);

        Button btnRestart = new Button(this);
        btnRestart.setText("重启应用");
        btnRestart.setTextColor(Color.WHITE);
        btnRestart.setBackgroundColor(0xFF40A9FF);
        btnRestart.setMinHeight(dp(44));
        LinearLayout.LayoutParams btnRestartParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1);
        btnRestartParams.setMargins(0, 0, dp(8), 0);
        btnRestart.setLayoutParams(btnRestartParams);
        btnLayout.addView(btnRestart);

        Button btnExit = new Button(this);
        btnExit.setText("退出应用");
        btnExit.setTextColor(Color.WHITE);
        btnExit.setBackgroundColor(0xFFFF6B6B);
        btnExit.setMinHeight(dp(44));
        LinearLayout.LayoutParams btnExitParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1);
        btnExitParams.setMargins(dp(8), 0, 0, 0);
        btnExit.setLayoutParams(btnExitParams);
        btnLayout.addView(btnExit);

        rootLayout.addView(btnLayout);
        setContentView(rootLayout);

        String crashLog = CrashHandler.CRASH_LOG;
        if (TextUtils.isEmpty(crashLog)) {
            crashLog = CrashHandler.getInstance().getLatestCrashLog();
        }

        String errorMsg = "发生了未处理的异常";
        String detailMsg = "无详细信息 (日志文件可能未保存)";

        if (!TextUtils.isEmpty(crashLog)) {
            String[] lines = crashLog.split("\n");
            for (String line : lines) {
                if (line.startsWith("异常信息：")) {
                    errorMsg = line.replace("异常信息：", "");
                    break;
                }
            }
            detailMsg = crashLog;
        }

        tvError.setText(errorMsg);
        tvDetail.setText(detailMsg);

        btnRestart.setOnClickListener(v -> {
            try {
                AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                Intent intent = new Intent(CrashActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                PendingIntent pendingIntent = PendingIntent.getActivity(
                        CrashActivity.this,
                        0,
                        intent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
                );
                if (alarmManager != null) {
                    alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent);
                }
            } catch (Exception ignored) {}

            finish();
        });

        btnExit.setOnClickListener(v -> {

            finish();

            Process.killProcess(Process.myPid());
            System.exit(0);
        });
    }

    @Override
    public void onBackPressed() {
        finish();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
