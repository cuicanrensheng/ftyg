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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GroupListManager {

    private final ListView lvGroup;
    private Context context;
    private List<String> groupDisplayList;
    private List<String> groupNameList;
    private int selectedPosition = 0;
    private int focusedPosition = -1;
    private ArrayAdapter<String> adapter;
    private OnGroupSelectedListener listener;

    private List<String> fixedGroupNames = new ArrayList<>();
    private Set<String> fixedGroupNameSet = new HashSet<>();

    private List<String> presetGroupNames = new ArrayList<>();

    public static final String GROUP_ALL = "全部";

    public static final String GROUP_HUYA_CLASSIC_FILM = "经典影视";

    private static final java.util.regex.Pattern HUYA_CLASSIC_MERGE_PATTERN =
            java.util.regex.Pattern.compile("星爷|英叔|喜剧|搞笑|动作|港片|武侠|热血|警匪|悬疑|盗墓");

    // 【2026-10-20 焦点降亮 / 八次调整】三态：
    //   焦点（focused）  → 暗金（橄榄）底 #FF3C3525 + 2dp 白描边 + 黄字 #FFFFD166（ott_tab_focused）
    //   选中但未聚焦     → 默认底 #FF212121 + 黄字 #FFFFD166（"当前分组"，已去除橄榄底）
    //   其余            → 中性深灰底 #FF212121（list_item_default）+ 白字
    //   焦点靠橄榄底 + 那圈 2dp 白描边区分（原亮金底 #FFD166 用户反馈太亮）。
    // 【2026-10-20 面板/胶囊配色对齐参考图】面板底 #1A1A1A、胶囊底 #212121、焦点底 #3C3525，
    // 全部改为不透明（原 #33FFFFFF / #660E1E3C / 深蓝黑面板均已作废）。
    // 间距由 lv_group 的 dividerHeight=8dp 提供，频道 / 日期 / 节目单列表同样加了 8dp。
    private static final int COLOR_TAB_TEXT = 0xFFFFFFFF;    // 默认字色（同 OTT 分组胶囊常白）
    private static final int COLOR_TAB_CURRENT = 0xFFFFD166; // 当前分组（选中未聚焦）→ 黄字
    private static final int COLOR_FOCUS = 0xFFFFD166;       // 焦点态黄字（与选中态同色，底为暗金橄榄）

    public static String getNormalizedGroup(Channel c) {
        if (c == null) return "未分类";
        String raw = c.getGroup();
        if (raw == null) return "未分类";
        String g = raw.trim();
        if (g.isEmpty()) return "未分类";

        if (g.startsWith("动漫") || g.startsWith("综艺")
                || g.startsWith("剧集")) {
            return g;
        }

        boolean isTogetherWatchChannel = c.isTogetherWatch() || c.getHuyaRoomId() > 0;
        if (isTogetherWatchChannel && HUYA_CLASSIC_MERGE_PATTERN.matcher(g).find()) {
            return GROUP_HUYA_CLASSIC_FILM;
        }
        return g;
    }

    public static String getNormalizedGroup(String rawGroup) {
        if (rawGroup == null) return "未分类";
        String g = rawGroup.trim();
        return g.isEmpty() ? "未分类" : g;
    }

    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;

        lvGroup.setItemsCanFocus(true);
        lvGroup.setFocusable(true);
        lvGroup.setFocusableInTouchMode(true);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 【2026-10-20 焦点改为金色】焦点态（金底）由适配器按「本列表是否持有焦点」决定，
        // ListView 自身不会因焦点进出重跑 getView → 手动触发重绘。
        lvGroup.setOnFocusChangeListener((v, hasFocus) -> {
            if (adapter != null) adapter.notifyDataSetChanged();
        });

        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 同上：选中行未变化就不再全量重绘分组列表
                if (selectedPosition == position) return;
                selectedPosition = position;
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        lvGroup.setOnItemClickListener((parent, view, position, id) -> {
            setSelectedPosition(position);
        });
    }

    public void setFixedGroups(String[] names) {
        fixedGroupNames.clear();
        fixedGroupNameSet.clear();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.isEmpty()) {
                    fixedGroupNames.add(n);
                    fixedGroupNameSet.add(n);
                }
            }
        }
    }

    public void setPresetGroups(String[] names) {
        presetGroupNames.clear();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.isEmpty() && !presetGroupNames.contains(n)) {
                    presetGroupNames.add(n);
                }
            }
        }
    }

    public boolean isFixedGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return false;
        return fixedGroupNameSet.contains(groupNameList.get(position));
    }

    /**
     * 增量刷新分组（不重建 adapter，保住用户焦点）。
     *
     * <p>【2026-10-03 Bug 修复】原实现只更新第 0 项「全部 (N)」的文字、**绝不重建分组名列表**。
     * 但分组名列表是在 {@link #setGroups(List)}（rebuild=true，由 ChannelPanelController.setChannels
     * 调用）时**一次性构建**的；而虎牙频道是**异步晚到**的（HTTP 一起看 / 固定分组增量合并）——
     * 首次 setChannels 时 channelSourceList 里只有主源频道，构建出的分组表里**没有虎牙分组**。
     * 之后增量到达只改了「全部 (N)」的数字，虎牙分组名始终没进表 → 用户看到「全部加载完了，
     * 但分组里一个频道都没有」，必须重开面板（重新 setChannels → rebuild）才有。
     *
     * <p>修法：检测**分组名集合是否发生变化**（新增/减少分组）；变了就升级为重建（rebuild），
     * 没变则保持原轻量路径（只改计数），不破坏「保住焦点、不切组」的初衷。</p>
     */
    public void setGroups(List<Channel> channelSourceList, boolean rebuild) {
        if (rebuild) {
            setGroups(channelSourceList);
            return;
        }
        if (groupDisplayList == null || groupDisplayList.isEmpty() || channelSourceList == null) return;

        // 【Bug 修复】分组名集合变化（尤其虎牙分组晚到）时必须重建，否则分组表永远缺这些分组
        if (hasGroupSetChanged(channelSourceList)) {
            LogBridge.i("GroupList", "增量合并后发现分组集合变化 → 重建分组表"
                    + "（原分组数=" + (groupNameList == null ? 0 : groupNameList.size()) + "）");
            rebuildKeepingSelection(channelSourceList);
            return;
        }

        groupDisplayList.set(0, GROUP_ALL + " (" + channelSourceList.size() + ")");
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /**
     * 重建分组表，但**保留用户当前选中的分组**（按分组名定位，而非按旧下标）。
     * <p>用于增量晚到场景：{@link #setGroups(List)} 会把 selectedPosition 重置为 0（跳回「全部」），
     * 而增量合并不该改变用户所在分组。分组名若在新表里不存在（理论上不该发生），回退到 0。</p>
     */
    private void rebuildKeepingSelection(List<Channel> channelSourceList) {
        String keepGroupName = null;
        if (groupNameList != null && selectedPosition >= 0 && selectedPosition < groupNameList.size()) {
            keepGroupName = groupNameList.get(selectedPosition);
        }
        setGroups(channelSourceList);
        if (keepGroupName != null) {
            int pos = getGroupPosition(keepGroupName);
            // getGroupPosition 找不到时返回 0（约定），故再用 getCurrentGroup 复核一次
            if (keepGroupName.equals(getCurrentGroup(pos))) {
                selectedPosition = pos;
                focusedPosition = pos;
                lvGroup.setSelection(pos);
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        }
    }

    /** 按现有逻辑推算「本次 channelSourceList 会生成的分组名集合」，与当前 groupNameList 比较 */
    private boolean hasGroupSetChanged(List<Channel> channelSourceList) {
        if (groupNameList == null) return false;
        Set<String> nowNames = new LinkedHashSet<>();
        nowNames.add(GROUP_ALL);
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {
            if (c == null) continue;
            if (c.isHuyaSdkTogetherWatch()) continue;
            String g = getNormalizedGroup(c);
            if (fixedGroupNameSet.contains(g)) continue;
            groupSet.add(g);
        }
        List<String> originalGroups = new ArrayList<>(groupSet);
        for (String pg : presetGroupNames) {
            if (!originalGroups.contains(pg)) originalGroups.add(pg);
        }
        nowNames.addAll(originalGroups);
        nowNames.addAll(fixedGroupNames);
        return !nowNames.equals(new LinkedHashSet<>(groupNameList));
    }

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {

            if (c.isHuyaSdkTogetherWatch()) continue;
            String g = getNormalizedGroup(c);

            if (fixedGroupNameSet.contains(g)) continue;
            groupSet.add(g);
        }
        List<String> originalGroups = new ArrayList<>(groupSet);

        for (String pg : presetGroupNames) {
            if (!originalGroups.contains(pg)) {
                originalGroups.add(pg);
            }
        }

        groupNameList = new ArrayList<>();
        groupNameList.add(GROUP_ALL);

        groupNameList.addAll(originalGroups);
        groupNameList.addAll(fixedGroupNames);

        groupDisplayList = new ArrayList<>();
        groupDisplayList.add(GROUP_ALL + " (" + channelSourceList.size() + ")");
        for (String group : originalGroups) {
            int count = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(getNormalizedGroup(c))) {
                    count++;
                }
            }

            groupDisplayList.add(group);
        }
        for (String group : fixedGroupNames) {
            groupDisplayList.add(group);
        }

        adapter = new ArrayAdapter<String>(lvGroup.getContext(), android.R.layout.simple_list_item_1, groupDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    LayoutInflater inflater = LayoutInflater.from(context);
                    convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                    TextView tv = convertView.findViewById(android.R.id.text1);
                    holder = new ViewHolder();
                    holder.tv = tv;
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (holder == null || holder.tv == null) {
                    LayoutInflater inflater = LayoutInflater.from(context);
                    convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                    TextView tv = convertView.findViewById(android.R.id.text1);
                    holder = new ViewHolder();
                    holder.tv = tv;
                    convertView.setTag(holder);
                }

                TextView tv = holder.tv;
                if (tv == null) {
                    return convertView;
                }

                String text = groupDisplayList.get(position);
                tv.setText(text);

                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                boolean isSelected = (position == selectedPosition);
                // LogBridge.d("GroupList", "getView pos:" + position + ", selectedPos:" + selectedPosition + ", isSelected:" + isSelected);

                // 【2026-10-20 焦点暗金橄榄 + 选中态去橄榄底】
                //   focused（本列表有焦点且本项选中）→ 暗金底 #3C3525 + 白描边 + 黄字 #FFD166
                //   isSelected 但未聚焦              → 默认底 #212121 + 黄字 #FFD166（当前分组）
                //   其余                             → 默认底 #212121 + 白字
                // 默认底与频道行同为 list_item_default(#212121)，分组列与中列颜色一致；
                // 胶囊间距由 lv_group 的 dividerHeight 提供（activity_main.xml）。
                boolean focused = lvGroup.hasFocus() && isSelected;
                tv.setBackgroundResource(focused
                        ? R.drawable.ott_tab_focused
                        : R.drawable.list_item_default);
                tv.setTextColor(focused ? COLOR_FOCUS
                        : (isSelected ? COLOR_TAB_CURRENT : COLOR_TAB_TEXT));
                tv.setTypeface(null, (focused || isSelected) ? Typeface.BOLD : Typeface.NORMAL);

                return convertView;
            }
        };
        lvGroup.setAdapter(adapter);
        selectedPosition = 0;
        focusedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        if (groupDisplayList == null || adapter == null) return;
        if (position < 0 || position >= groupDisplayList.size()) return;
        if (selectedPosition == position) return;

        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) {
            listener.onGroupSelected(position, groupNameList.get(position));
        }
    }

    public String getCurrentGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return "";
        return groupNameList.get(position);
    }

    public int getGroupPosition(String groupName) {
        if (groupNameList == null || groupName == null) return 0;
        for (int i = 0; i < groupNameList.size(); i++) {
            if (groupName.equals(groupNameList.get(i))) {
                return i;
            }
        }
        return 0;
    }

    public boolean isAllGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return false;
        return GROUP_ALL.equals(groupNameList.get(position));
    }

    public boolean isSpecialGroup(int position) {
        return position == 0;
    }

    public void onBackPressed() {}

    private static class ViewHolder {
        TextView tv;
    }

    public void release() {
        if (adapter != null) {
            adapter.clear();
            adapter = null;
        }
        if (lvGroup != null) {
            lvGroup.setAdapter(null);
            lvGroup.setOnItemClickListener(null);
            lvGroup.setOnItemSelectedListener(null);
        }
        if (groupDisplayList != null) {
            groupDisplayList.clear();
            groupDisplayList = null;
        }
        if (groupNameList != null) {
            groupNameList.clear();
            groupNameList = null;
        }
        listener = null;
        context = null;
    }
}
