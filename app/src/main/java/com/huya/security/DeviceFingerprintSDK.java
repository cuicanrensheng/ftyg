package com.huya.security;

import java.io.IOException;
import java.util.ArrayList;

public final class DeviceFingerprintSDK {
    public static final String TAG = "DeviceFingerprintSDK";

    private static final String STUB_DEVICE_ID = "00000000-0000-0000-0000-000000000000";

    public static String kiwiHost;
    public static String nimoHost;
    public static String openApiHost;
    public static String host;
    public static String urlCheck;
    public static String urlLog;
    public static String urlLink;
    public static Thread workThread;

    public final ArrayList<SdidHandler> sdidHandlers = new ArrayList<>();

    public DeviceFingerprintSDK() {
    }

    public static DeviceFingerprintSDK getInstance() {
        return Holder.INSTANCE;
    }

    public static DeviceFingerprintSDK getInstance(String url) {
        return Holder.INSTANCE;
    }

    public static byte[] getOldLog() throws IOException {
        return new byte[0];
    }

    public void init() {

    }

    public String getCDID() {
        return STUB_DEVICE_ID;
    }

    public String getSDID() {
        return "";
    }

    public void addSdidHandler(SdidHandler handler) {

    }

    public void link(String url, String arg, String appId) {

    }

    private static final class Holder {
        static final DeviceFingerprintSDK INSTANCE = new DeviceFingerprintSDK();
    }
}
