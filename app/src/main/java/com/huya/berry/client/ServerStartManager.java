package com.huya.berry.client;

import android.os.Bundle;

public class ServerStartManager {
    public void startModules() {
        com.duowan.auk.Ark.startModule(com.huya.berry.module.HysignalPushModule.class);
        com.huya.live.service.ServiceHelper.createService(com.huya.berry.gamesdk.module.ICommonService.class, com.huya.berry.gamesdk.module.CommonService.class);
        com.huya.live.service.ServiceHelper.createService(com.huya.berry.module.live.ISdkLiveService.class, com.huya.berry.module.live.SdkLiveService.class);
        com.huya.live.service.ServiceHelper.createService(com.huya.berry.sdklive.api.ILiveService.class, com.huya.berry.sdklive.LiveService.class);
    }
}
