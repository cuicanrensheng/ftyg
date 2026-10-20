package com.tv.live.widget;

import com.tv.live.util.LogBridge;
import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.R;

import java.util.ArrayList;
import java.util.List;

public class ChannelListManager {
    private final ListView lvChannelList;
    private int selectedPosition = 0;
    private int focusedPosition = -1;
    private int currentPlayIndex = 0;

    // 【2026-10-20 焦点降亮 / 八次调整】三态：
    //   焦点（focused）  → 暗金（橄榄）底 #FF3C3525 + 2dp 白描边 + 黄字 #FFFFD166（list_item_focused）
    //   选中但未聚焦     → 默认底 #FF212121 + 黄字 #FFFFD166（"当前"通道，已去除橄榄底）
    //   其余            → 中性深灰底 #FF212121（list_item_default）+ 白字
    //   焦点靠橄榄底 + 那圈 2dp 白描边区分（原亮金底 #FFD166 用户反馈太亮）。
    // ListView 中 item 的 state_focused / state_selected 实测均不生效，故按
    // 「本列表是否持有焦点 && 本行是否被选中」显式指定（见构造函数的焦点监听重绘）。
    private static final int COLOR_TEXT = 0xFFFFFFFF;       // 主文字（同 OTT 卡片剧名）
    private static final int COLOR_MUTED = 0x99B9C4D8;      // 次级文字（同 OTT 卡片节目文字）
    private static final int COLOR_CURRENT = 0xFFFFD166;    // 「当前」黄字（同 OTT 焦点金 #FFD166）
    private static final int COLOR_FOCUS = 0xFFFFD166;      // 焦点态黄字（与选中态同色，底为暗金橄榄）

    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    public interface OnChannelLongClickListener {
        boolean onChannelLongClick(String channelName, int position);
    }
    private OnChannelLongClickListener onChannelLongClickListener;

    public void setOnChannelLongClickListener(OnChannelLongClickListener listener) {
        this.onChannelLongClickListener = listener;
    }

    public ChannelListManager(Context context, ListView lvChannelList) {
        this.lvChannelList = lvChannelList;

        lvChannelList.setItemsCanFocus(true);
        lvChannelList.setFocusable(true);
        lvChannelList.setFocusableInTouchMode(true);

        // 【2026-10-20 焦点改为金色】焦点态（金底）由适配器按「本列表是否持有焦点」决定，
        // 而 ListView 自身不会因焦点进出重跑 getView → 这里手动触发一次重绘。
        lvChannelList.setOnFocusChangeListener((v, hasFocus) -> {
            android.widget.ListAdapter a = lvChannelList.getAdapter();
            if (a instanceof android.widget.BaseAdapter) {
                ((android.widget.BaseAdapter) a).notifyDataSetChanged();
            }
        });

        lvChannelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 选中行没变就不要 notify：ListView 的 notifyDataSetChanged 会重绘所有可见行
                // （实测一次 12 行 getView），遥控器上下移动时每次都全刷会造成明显闪烁
                if (selectedPosition == position) return;
                selectedPosition = position;
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            if (selectedPosition != position) {
                selectedPosition = position;
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

        lvChannelList.setOnItemLongClickListener((parent, view, position, id) -> {
            if (onChannelLongClickListener != null) {
                String channelName = null;
                if (parent.getAdapter() != null && position < parent.getAdapter().getCount()) {
                    Object item = parent.getAdapter().getItem(position);
                    if (item != null) {
                        channelName = item.toString();
                    }
                }
                return onChannelLongClickListener.onChannelLongClick(channelName, position);
            }
            return false;
        });
    }

    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        final long t0 = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());

        selectedPosition = currentPlayIndex;
        focusedPosition = currentPlayIndex;
        this.currentPlayIndex = currentPlayIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == currentPlayIndex) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                boolean isSelected = (position == selectedPosition);
                // LogBridge.d("ChannelList", "getView pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                // 【2026-10-20 焦点暗金橄榄 + 选中态去橄榄底】
                //   focused（本列表有焦点且本行选中）→ 暗金底 #3C3525 + 白描边 + 黄字 #FFD166
                //   isSelected 但未聚焦              → 默认底 #212121 + 黄字 #FFD166（"当前选中"通道）
                //   正在播放行                        → 默认底 #212121 + ▶ 序号黄字
                //   其余                             → 默认底 #212121 + 白字
                boolean focused = lvChannelList.hasFocus() && isSelected;
                convertView.setBackgroundResource(focused
                        ? R.drawable.list_item_focused
                        : R.drawable.list_item_default);
                holder.tvChannel.setTextColor(focused ? COLOR_FOCUS
                        : (isSelected ? COLOR_CURRENT : COLOR_TEXT));
                holder.tvChannel.setTypeface(null, (focused || isSelected)
                        ? Typeface.BOLD : Typeface.NORMAL);
                holder.tvIndex.setTextColor(focused ? COLOR_FOCUS
                        : (position == currentPlayIndex ? COLOR_CURRENT : COLOR_MUTED));
                // 序号底：layout 里是深灰 #333333 小方块，暗金底上会变成「深字压深底」不可读，
                // 故焦点行置为透明（黄字数字直接落在暗金底上），其余行保持原小方块。
                holder.tvIndex.setBackgroundColor(focused ? 0x00000000 : 0xFF333333);

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
        LogBridge.i("ChannelList", "⏱️【链路-列表】setChannels 构建adapter 耗时 "
                + (System.currentTimeMillis() - t0) + "ms，行数=" + names.size());
    }

    public void setChannelsByGroup(List<Channel> channelSourceList, String group, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        int realIndex = 0;
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel c = channelSourceList.get(i);

            if (group == null || group.isEmpty() || group.equals(GroupListManager.getNormalizedGroup(c))) {
                names.add(c.getName());
                if (i == currentPlayIndex) {
                    realIndex = names.size() - 1;
                }
            }
        }

        selectedPosition = realIndex;
        focusedPosition = realIndex;
        this.currentPlayIndex = realIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == currentPlayIndex) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                boolean isSelected = (position == selectedPosition);
                // LogBridge.d("ChannelList", "getView(byGroup) pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                // 【2026-10-20 焦点暗金橄榄 + 选中态去橄榄底】
                //   focused（本列表有焦点且本行选中）→ 暗金底 #3C3525 + 白描边 + 黄字 #FFD166
                //   isSelected 但未聚焦              → 默认底 #212121 + 黄字 #FFD166（"当前选中"通道）
                //   正在播放行                        → 默认底 #212121 + ▶ 序号黄字
                //   其余                             → 默认底 #212121 + 白字
                boolean focused = lvChannelList.hasFocus() && isSelected;
                convertView.setBackgroundResource(focused
                        ? R.drawable.list_item_focused
                        : R.drawable.list_item_default);
                holder.tvChannel.setTextColor(focused ? COLOR_FOCUS
                        : (isSelected ? COLOR_CURRENT : COLOR_TEXT));
                holder.tvChannel.setTypeface(null, (focused || isSelected)
                        ? Typeface.BOLD : Typeface.NORMAL);
                holder.tvIndex.setTextColor(focused ? COLOR_FOCUS
                        : (position == currentPlayIndex ? COLOR_CURRENT : COLOR_MUTED));
                // 序号底：layout 里是深灰 #333333 小方块，暗金底上会变成「深字压深底」不可读，
                // 故焦点行置为透明（黄字数字直接落在暗金底上），其余行保持原小方块。
                holder.tvIndex.setBackgroundColor(focused ? 0x00000000 : 0xFF333333);

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    /**
     * 增量刷新：复用当前 adapter，仅 clear/addAll + notifyDataSetChanged，
     * 不重建 adapter、不 setSelection——用于后台线路（如 SDK 固定分组预加载）
     * 数据晚到时平滑追加，避免可见的全量列表刷新与焦点跳动。
     * 仅对 setChannels / setChannelsByGroup 创建的 adapter 有效
     * （其 getView 通过 this.currentPlayIndex 渲染 ▶ 标记）。
     * 若 adapter 已失效则回退为 setChannelsByGroup 重建。
     */
    @SuppressWarnings("unchecked")
    public void refreshChannelsNoRebuild(List<Channel> channelSourceList, int currentPlayIndex, String group) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        android.widget.ListAdapter adapter = lvChannelList.getAdapter();
        if (!(adapter instanceof ArrayAdapter)) {
            setChannelsByGroup(channelSourceList, group, currentPlayIndex);
            return;
        }

        List<String> names = new ArrayList<>();
        int realIndex = -1;
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel c = channelSourceList.get(i);
            if (group == null || group.isEmpty()
                    || group.equals(GroupListManager.getNormalizedGroup(c))) {
                names.add(c.getName());
                if (i == currentPlayIndex) {
                    realIndex = names.size() - 1;
                }
            }
        }

        // setChannels / setChannelsByGroup 的 adapter getView 读 this.currentPlayIndex。
        // 仅当正在播放的频道确实落在当前分组内时才更新；否则保持原值，
        // 避免被误置为 0 导致 ▶ 标记错位、上下键从错误位置开始切换。
        if (realIndex >= 0) {
            this.currentPlayIndex = realIndex;
        }
        // selectedPosition 保持不变 —— 维持当前焦点行，不跳动

        ArrayAdapter<String> arrayAdapter = (ArrayAdapter<String>) adapter;
        arrayAdapter.setNotifyOnChange(false);
        arrayAdapter.clear();
        arrayAdapter.addAll(names);
        arrayAdapter.setNotifyOnChange(true);
        arrayAdapter.notifyDataSetChanged();
    }

    public void setFilteredChannels(List<Channel> filteredChannels, String currentPlayChannelName) {
        List<String> names = new ArrayList<>();
        int playIndex = 0;

        if (filteredChannels != null) {
            for (int i = 0; i < filteredChannels.size(); i++) {
                Channel c = filteredChannels.get(i);
                names.add(c.getName());
                if (currentPlayChannelName != null && currentPlayChannelName.equals(c.getName())) {
                    playIndex = i;
                }
            }
        }

        selectedPosition = playIndex;
        focusedPosition = playIndex;
        this.currentPlayIndex = playIndex;
        final int finalPlayIndex = playIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == finalPlayIndex && names.size() > 0) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                boolean isSelected = (position == selectedPosition);
                // LogBridge.d("ChannelList", "getView(byGroup) pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                // 【2026-10-20 焦点暗金橄榄 + 选中态去橄榄底】
                //   focused（本列表有焦点且本行选中）→ 暗金底 #3C3525 + 白描边 + 黄字 #FFD166
                //   isSelected 但未聚焦              → 默认底 #212121 + 黄字 #FFD166（"当前选中"通道）
                //   正在播放行                        → 默认底 #212121 + ▶ 序号黄字
                //   其余                             → 默认底 #212121 + 白字
                boolean focused = lvChannelList.hasFocus() && isSelected;
                convertView.setBackgroundResource(focused
                        ? R.drawable.list_item_focused
                        : R.drawable.list_item_default);
                holder.tvChannel.setTextColor(focused ? COLOR_FOCUS
                        : (isSelected ? COLOR_CURRENT : COLOR_TEXT));
                holder.tvChannel.setTypeface(null, (focused || isSelected)
                        ? Typeface.BOLD : Typeface.NORMAL);
                holder.tvIndex.setTextColor(focused ? COLOR_FOCUS
                        : (position == currentPlayIndex ? COLOR_CURRENT : COLOR_MUTED));
                // 序号底：layout 里是深灰 #333333 小方块，暗金底上会变成「深字压深底」不可读，
                // 故焦点行置为透明（黄字数字直接落在暗金底上），其余行保持原小方块。
                holder.tvIndex.setBackgroundColor(focused ? 0x00000000 : 0xFF333333);

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    private static class ViewHolder {
        TextView tvIndex;
        TextView tvChannel;
    }

    public void release() {
        if (lvChannelList != null) {
            lvChannelList.setAdapter(null);
            lvChannelList.setOnItemClickListener(null);
            lvChannelList.setOnItemSelectedListener(null);
        }
        onChannelClickListener = null;
        onChannelLongClickListener = null;
    }
}
