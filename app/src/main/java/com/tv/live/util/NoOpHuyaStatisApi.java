package com.tv.live.util;

import com.duowan.live.one.module.report.HuyaStatisApi;
import com.huya.statistics.core.StatisticsContent;

public class NoOpHuyaStatisApi extends HuyaStatisApi {

    @Override
    public void init(android.content.Context context, String str, String str2,
                     String str3, String str4, String str5) {

    }

    @Override
    public void install() {
    }

    @Override
    public void installApps() {
    }

    @Override
    public void changeSessionId() {
    }

    @Override
    public void error(String str) {
    }

    @Override
    public void login() {
    }

    @Override
    public void startLive(long j, long j2, long j3, String str) {
    }

    @Override
    public void stopLive() {
    }

    @Override
    public void setGameId(long j) {
    }

    @Override
    public void reportEvent(String str, String str2) {
    }

    @Override
    public void reportEvent(String str, String str2, String str3,
                            String str4, StatisticsContent statisticsContent) {
    }

    @Override
    public void setRso(String str) {
    }

    @Override
    public void setUve(String str) {
    }

    @Override
    public void setReferrer(String str) {
    }

    @Override
    public void setYyUid(java.lang.Long l) {
    }

    @Override
    public void setGuid(String str) {
    }

    @Override
    public String getMid() {
        return "";
    }
}
