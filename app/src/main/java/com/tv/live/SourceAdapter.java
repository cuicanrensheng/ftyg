package com.tv.live;

import android.content.Context;
import android.graphics.Color;
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

    private static final int COLOR_FOCUS = Color.parseColor("#40A9FF");
    private static final int COLOR_FOCUS_BG = 0x3340A9FF;
    private static final int COLOR_HOVER_BG = 0x4440A9FF;

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

        if (position == selectedPosition) {
            holder.tv.setTextColor(COLOR_FOCUS);
            holder.indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundColor(COLOR_FOCUS_BG);
        } else if (convertView.isFocused()) {
            holder.tv.setTextColor(COLOR_FOCUS);
            holder.indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundColor(COLOR_HOVER_BG);
        } else {
            holder.tv.setTextColor(Color.WHITE);
            holder.indexTv.setTextColor(Color.WHITE);
            convertView.setBackgroundColor(Color.TRANSPARENT);
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView tv;
        TextView indexTv;
        Button deleteBtn;
    }
}
