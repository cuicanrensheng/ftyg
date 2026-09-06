package com.tv.live.util;

import com.huya.berry.gamesdk.crash.ICrashService;
import com.huya.live.service.AbsService;

public class NoOpCrashService extends AbsService implements ICrashService {

    @Override
    public void init() {

    }

    @Override
    public void postCatchedException(java.lang.Throwable th) {

    }
}
