package com.tv.live.manager;

import com.tv.live.widget.DateListManager;
import android.view.View;
import com.tv.live.Channel;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.EpgManagerWrapper;
import java.util.List;

public class PanelManager {

    private final View panelLayout;

    private final ChannelListManager channelListManager;

    private final EpgManagerWrapper epgManagerWrapper;

    private int currentDateIndex = 0;

    public PanelManager(View panelLayout, ChannelListManager channelListManager, EpgManagerWrapper epgManagerWrapper) {
        this.panelLayout = panelLayout;
        this.channelListManager = channelListManager;
        this.epgManagerWrapper = epgManagerWrapper;
    }

    public void setCurrentDateIndex(int dateIndex) {
        this.currentDateIndex = dateIndex;
    }

    public void toggle(List<Channel> channelList, int currentIndex, DateListManager dateListManager) {
        if (panelLayout.getVisibility() == View.VISIBLE) {

            panelLayout.setVisibility(View.GONE);
        } else {

            panelLayout.setVisibility(View.VISIBLE);

            panelLayout.post(() -> {
                dateListManager.setSelectedPosition(currentDateIndex);

                if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                    Channel currentChannel = channelList.get(currentIndex);
                    epgManagerWrapper.refresh(currentChannel, channelList, currentDateIndex);
                }
            });
        }
    }
}
