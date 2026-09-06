package com.tv.live.util;

import android.util.Log;

public final class LogBridge {
    private LogBridge() {}

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
        forward(tag, msg, LogCollector.TYPE_DEBUG);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        forward(tag, msg, LogCollector.TYPE_DEBUG);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        forward(tag, msg, LogCollector.TYPE_INFO);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        forward(tag, msg, LogCollector.TYPE_WARN);
    }

    public static void w(String tag, String msg, Throwable tr) {
        Log.w(tag, msg, tr);
        forward(tag, msg + (tr != null ? " : " + tr.getMessage() : ""), LogCollector.TYPE_WARN);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        forward(tag, msg, LogCollector.TYPE_ERROR);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        forward(tag, msg + (tr != null ? " : " + tr.getMessage() : ""), LogCollector.TYPE_ERROR);
    }

    public static int println(int priority, String tag, String msg) {
        int r = Log.println(priority, tag, msg);
        forward(tag, msg, LogCollector.TYPE_INFO);
        return r;
    }

    private static void forward(String tag, String msg, String type) {
        try {

            LogCollector.getInstance().addLogNoReport(tag, msg, type);
        } catch (Throwable ignored) {}
    }
}
