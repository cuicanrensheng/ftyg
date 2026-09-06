package com.tv.live;

import com.tv.live.util.LogBridge;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class SubscriptionAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private OnActionListener actionListener;
    private android.widget.ListView listViewRef;

    public interface OnActionListener {
        void onSwitch(int position);
        void onDelete(int position);
    }

    public SubscriptionAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        if (listViewRef != null && position >= 0) {
            listViewRef.setSelection(position);

            if (position < listViewRef.getCount()) {
                listViewRef.setItemChecked(position, true);
            }

            applyImmediateRowActivated(listViewRef, position);
            if (listViewRef.getChildCount() == 0) {
                final int pos = position;
                listViewRef.postDelayed(() -> applyImmediateRowActivated(listViewRef, pos), 150);
            }
        }
        notifyDataSetChanged();
    }

    public void ensureActivatedImmediate() {
        syncSelectedFromList();
        if (listViewRef != null && selectedPosition >= 0) {
            applyImmediateRowActivated(listViewRef, selectedPosition);
        }
    }

    public int getSelectedPosition() {

        syncSelectedFromList();
        return selectedPosition;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.actionListener = listener;
    }

    public void setListView(android.widget.ListView listView) {
        this.listViewRef = listView;
    }

    private void syncSelectedFromList() {
        if (listViewRef == null) return;
        int nativePos = listViewRef.getSelectedItemPosition();

        if (nativePos >= 0 && nativePos < getCount()) {
            selectedPosition = nativePos;
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_subscription_list, parent, false);
            holder = new ViewHolder();
            holder.tvCheck = convertView.findViewById(R.id.tv_check);
            holder.tvUrl = convertView.findViewById(R.id.tv_url);

            holder.tvUrlBold = convertView.findViewById(R.id.tv_url_bold);
            holder.contentLayout = convertView.findViewById(R.id.content_layout);
            holder.btnCopy = convertView.findViewById(R.id.btn_copy);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) return convertView;

        StringBuilder display = new StringBuilder(item.name != null ? item.name : "");
        if (item.isDefault) display.append("  ⭐");
        String text = display.toString();
        holder.tvUrl.setText(text);

        holder.tvUrlBold.setText(text);
        holder.tvUrlBold.getPaint().setFakeBoldText(true);

        {
            String n = item.name != null ? item.name : "";
            String u = item.url != null ? item.url : "";
            boolean nameHit =
                    SourceManager.BUILTIN_NAME_LIVE_1.equals(n)
                 || SourceManager.BUILTIN_NAME_LIVE_2.equals(n)
                 || SourceManager.BUILTIN_NAME_LIVE_3.equals(n)
                 || SourceManager.BUILTIN_NAME_EPG_1.equals(n)
                 || SourceManager.BUILTIN_NAME_EPG_2.equals(n);
            boolean urlHit = !u.isEmpty() && (
                    u.equals(UrlConfig.LIVE_URL)
                 || u.equals(UrlConfig.LIVE_URL_2)

                 || u.equals(UrlConfig.EPG_URL)
                 || u.equals(UrlConfig.EPG_URL_2)
            );
            boolean isProtected = nameHit || urlHit;
            int vis = isProtected ? View.GONE : View.VISIBLE;
            holder.btnDelete.setVisibility(vis);
            holder.btnCopy.setVisibility(vis);
            holder.btnDelete.setFocusable(!isProtected);
            holder.btnCopy.setFocusable(!isProtected);
            holder.btnDelete.setClickable(!isProtected);
            holder.btnCopy.setClickable(!isProtected);
        }

        final View finalView = convertView;
        final int finalPosition = position;

        finalView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    LogBridge.e("SUBSCRIPTION", "finalView onKey ENTER/CENTER: finalPos=" + finalPosition + " v.hasFocus=" + v.hasFocus());
                    syncSelectedFromList();
                    if (selectedPosition < 0 || selectedPosition >= getCount()) selectedPosition = finalPosition;
                    if (actionListener != null) actionListener.onSwitch(selectedPosition);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (finalPosition < getCount() - 1) {
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View target = findChildByPosition(parent, next);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (finalPosition > 0) {
                        int prev = finalPosition - 1;
                        if (listViewRef != null) listViewRef.setSelection(prev);
                        View target = findChildByPosition(parent, prev);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (finalPosition > 0) {
                        int prev = finalPosition - 1;
                        if (listViewRef != null) listViewRef.setSelection(prev);
                        View target = findChildByPosition(parent, prev);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (finalPosition < getCount() - 1) {
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View target = findChildByPosition(parent, next);
                        if (target != null) target.requestFocus();
                    }
                    return true;
                }
            }
            return false;
        });

        holder.btnCopy.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
                    android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (holder.btnDelete.getVisibility() == View.VISIBLE) {
                        holder.btnDelete.requestFocus();
                    } else if (finalPosition < getCount() - 1) {
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View nextItem = findChildByPosition(parent, next);
                        if (nextItem != null) nextItem.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    finalView.requestFocus();
                    return true;
                }
            }
            return false;
        });

        holder.btnDelete.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (actionListener != null && finalPosition >= 0 && finalPosition < getCount()) {
                        actionListener.onDelete(finalPosition);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    holder.btnCopy.requestFocus();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (finalPosition < getCount() - 1) {
                        int next = finalPosition + 1;
                        if (listViewRef != null) listViewRef.setSelection(next);
                        View nextItem = findChildByPosition(parent, next);
                        if (nextItem != null) nextItem.requestFocus();
                    }
                    return true;
                }
            }
            return false;
        });

        convertView.setOnClickListener(v -> {
            LogBridge.e("SUBSCRIPTION", "convertView onClick: position=" + position + " actionListenerNull=" + (actionListener == null));
            if (actionListener != null && position >= 0 && position < getCount()) {
                selectedPosition = position;
                if (listViewRef != null) {
                    listViewRef.setSelection(position);
                    listViewRef.setItemChecked(position, true);
                }

                applyImmediateRowActivated(listViewRef, position);
                actionListener.onSwitch(position);
            }
        });
        holder.contentLayout.setOnClickListener(v -> {
            LogBridge.e("SUBSCRIPTION", "contentLayout onClick: position=" + position);
            if (actionListener != null && position >= 0 && position < getCount()) {
                selectedPosition = position;
                if (listViewRef != null) {
                    listViewRef.setSelection(position);
                    listViewRef.setItemChecked(position, true);
                }

                applyImmediateRowActivated(listViewRef, position);
                actionListener.onSwitch(position);
            }
        });
        holder.btnCopy.setOnClickListener(v -> {
            if (item.url != null && !item.url.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
                android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });

        syncSelectedFromList();
        boolean isSel = selectedPosition >= 0 && position == selectedPosition;
        convertView.setActivated(isSel);
        convertView.setSelected(isSel);

        return convertView;
    }

    private static View findChildByPosition(ViewGroup parent, int position) {
        if (parent instanceof android.widget.ListView) {
            android.widget.ListView listView = (android.widget.ListView) parent;
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child != null && listView.getPositionForView(child) == position) {
                    return child;
                }
            }
        }
        return null;
    }

    private static void applyImmediateRowActivated(android.widget.ListView listView, int clickedPosition) {
        if (listView == null || clickedPosition < 0) return;
        final int N = listView.getChildCount();
        for (int i = 0; i < N; i++) {
            View child = listView.getChildAt(i);
            if (child == null) continue;
            int pos = listView.getPositionForView(child);
            if (pos < 0) continue;
            boolean isTarget = (pos == clickedPosition);
            child.setActivated(isTarget);
            child.setSelected(isTarget);
        }
    }

    private static class ViewHolder {
        TextView tvCheck;
        TextView tvUrl;
        TextView tvUrlBold;
        android.widget.LinearLayout contentLayout;
        Button btnCopy;
        Button btnDelete;
    }
}
