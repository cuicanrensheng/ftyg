package com.tv.live.util;

import android.app.Activity;

import com.huya.live.common.api.report.ReportApi;

import java.util.Map;

@SuppressWarnings({"rawtypes", "unchecked"})
public class NoOpReportApi implements ReportApi {

    @Override
    public void init(String appKey, String env, String channel, String version) {

    }

    @Override
    public void event(String eventName) {
    }

    @Override
    public void event(String eventName, String param1) {
    }

    @Override
    public void event(String eventName, String param1, String param2) {
    }

    @Override
    public void event(String eventName, String param1, String param2, Map map) {
    }

    @Override
    public void event(String eventName, String param1, String param2, String param3) {
    }

    @Override
    public void event(String eventName, String param1, String param2, String param3, Map map) {
    }

    @Override
    public void eventHuya(String eventName, String param1) {
    }

    @Override
    public void startLive(long roomId, long liveId, long uid, String anchorNick) {
    }

    @Override
    public void stopLive() {
    }

    @Override
    public void loginSuccess(long uid) {
    }

    @Override
    public void reportGuid(String guid) {
    }

    @Override
    public void changeHuyaSessionId() {
    }

    @Override
    public void value(String key, int value) {
    }

    @Override
    public void value(String key, String param, int value) {
    }

    @Override
    public void error(String errorType, String errorCode, String errorMsg) {
    }

    @Override
    public void resume(Activity activity) {
    }

    @Override
    public void pause(Activity activity) {
    }
}
