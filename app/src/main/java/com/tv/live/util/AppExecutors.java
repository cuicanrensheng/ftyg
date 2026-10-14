package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;

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
