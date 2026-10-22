package com.tv.live;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class SourceAdapter extends ArrayAdapter<SourceManager.SourceItem> {
    private final Context context;
    private int selectedPosition = -1;
    private OnDeleteClickListener onDeleteClickListener;

    // 【2026-10-20 橄榄底 + 黄字方案】原蓝色焦点语言（#40A9FF / #3340A9FF）改为
    // 与直播频道面板一致的黄字 #FFD166；行背景改用统一的 list_item_focused / list_item_default。
    private static final int COLOR_FOCUS = 0xFFFFD166;
    private static final int COLOR_NORMAL = 0xFFFFFFFF;

    public interface OnDeleteClickListener {
        void onDelete(int position);
    }

    public SourceAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, R.layout.item_settings, items);
        this.context = context;
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.onDeleteClickListener = listener;
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_settings, parent, false);
            holder = new ViewHolder();
            holder.tv = convertView.findViewById(R.id.tv_setting_item);
            holder.indexTv = convertView.findViewById(R.id.tv_index);
            holder.deleteBtn = convertView.findViewById(R.id.btn_delete);

            holder.tv.setTextSize(14);
            holder.tv.setLineSpacing(4, 1);
            holder.tv.setSingleLine(false);
            holder.tv.setEllipsize(null);

            holder.deleteBtn.setOnClickListener(v -> {
                if (onDeleteClickListener != null) {

                    Object tag = v.getTag();
                    if (tag instanceof Integer) {
                        onDeleteClickListener.onDelete((Integer) tag);
                    }
                }
            });

            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);

        if (item == null) {
            return convertView;
        }

        holder.indexTv.setText(String.format(Locale.ROOT, "%d. ", position + 1));

        StringBuilder displayText = new StringBuilder();
        displayText.append(item.name);
        if (item.isDefault) displayText.append("  ⭐");
        displayText.append("\n").append(item.url);
        if (!item.autoUpdate) displayText.append("  🔕");

        holder.tv.setText(displayText.toString());

        holder.deleteBtn.setTag(position);
        holder.deleteBtn.setClickable(true);
        holder.deleteBtn.setFocusable(false);

        if (convertView.isFocused()) {
            // 焦点态：橄榄底 + 2dp 白描边 + 黄字
            holder.tv.setTextColor(COLOR_FOCUS);
            holder.indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundResource(R.drawable.list_item_focused);
        } else if (position == selectedPosition) {
            // 选中未聚焦：默认胶囊底 + 黄字
            holder.tv.setTextColor(COLOR_FOCUS);
            holder.indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundResource(R.drawable.list_item_default);
        } else {
            holder.tv.setTextColor(COLOR_NORMAL);
            holder.indexTv.setTextColor(COLOR_NORMAL);
            convertView.setBackgroundResource(R.drawable.list_item_default);
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView tv;
        TextView indexTv;
        Button deleteBtn;
    }
}
