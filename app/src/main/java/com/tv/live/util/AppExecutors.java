package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AppExecutors {

    private static final AtomicInteger IO_THREAD_ID = new AtomicInteger(1);
    private static final AtomicInteger SERIAL_THREAD_ID = new AtomicInteger(1);

    private static final ExecutorService IO = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AppIO-" + IO_THREAD_ID.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService SERIAL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AppSerial-" + SERIAL_THREAD_ID.getAndIncrement());
        t.setDaemon(true);
        try {
            // 【2026-10-26】EPG 解析全链路走此串行队列。降到后台优先级（nice 10），
            // 弱 CPU（电视 A53@1.1GHz）下让出时间片给媒体解码/提取线程，
            // 避免冷启动/起播期 EPG 解析抢核造成播放缓冲见底。
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
        return t;
    });

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AppExecutors() {}

    public static ExecutorService io() {
        return IO;
    }

    public static ExecutorService serial() {
        return SERIAL;
    }

    public static void io(Runnable runnable) {
        IO.execute(runnable);
    }

    /** 串行执行：同一时刻只跑一个任务（用于有共享状态/必须保序的 IO 链路，如 EPG 缓存加载+下载+解析） */
    public static void serial(Runnable runnable) {
        SERIAL.execute(runnable);
    }

    public static void main(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            MAIN_HANDLER.post(runnable);
        }
    }
}
