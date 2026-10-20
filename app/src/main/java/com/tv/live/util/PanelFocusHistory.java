package com.tv.live.util;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;

/**
 * 【2026-10-21】直播模式「频道面板」焦点历史记录器。
 *
 * <p>把频道面板拆成 7 个焦点区域：
 * <pre>
 *   左面板：分组列表 → 频道列表 → 节目单按钮
 *   右面板：频道组按钮 → 频道列表 → 日期列表 → 节目单列表
 * </pre>
 *
 * <p>规则：焦点离开某个区域时，记录该区域当前停留的位置
 * （ListView 取 {@link ListView#getSelectedItemPosition()}，按钮记 0）；
 * 焦点回到该区域（或回到左/右面板）时，先 setSelection 再 requestFocus，
 * 把焦点放回上次离开的位置。这样左/右面板互切、面板关闭再打开、
 * 分组/日期之间来回移动都不会丢失原来的焦点位置。
 */
public class PanelFocusHistory {

    private static final String TAG = "PanelFocus";

    public static final int ZONE_NONE = -1;

    /** 左面板：分组列表 */
    public static final int ZONE_LEFT_GROUP = 0;
    /** 左面板：频道列表 */
    public static final int ZONE_LEFT_CHANNEL = 1;
    /** 左面板：节目单按钮 */
    public static final int ZONE_LEFT_EPG_BUTTON = 2;
    /** 右面板：频道组（返回）按钮 */
    public static final int ZONE_RIGHT_BACK_BUTTON = 3;
    /** 右面板：频道列表 */
    public static final int ZONE_RIGHT_CHANNEL = 4;
    /** 右面板：日期列表 */
    public static final int ZONE_RIGHT_DATE = 5;
    /** 右面板：节目单列表 */
    public static final int ZONE_RIGHT_EPG = 6;

    private static final int ZONE_COUNT = 7;

    private static final String[] ZONE_NAMES = {
            "左侧-分组", "左侧-频道", "左侧-节目单按钮",
            "右侧-频道组按钮", "右侧-频道", "右侧-日期", "右侧-节目单"
    };

    /** 焦点变化监听的根视图；ViewTreeObserver 为整窗共享，能拿到面板内外的所有焦点变化 */
    private final View root;
    private final View[] zoneViews = new View[ZONE_COUNT];
    private final int[] positions = new int[ZONE_COUNT];
    private final boolean[] hasPosition = new boolean[ZONE_COUNT];

    /** 最近一次「离开」的左侧区域（面板自动把焦点塞给分组列表时不会被误记） */
    private int lastLeftZone = ZONE_NONE;
    /** 最近一次「离开」的右侧区域 */
    private int lastRightZone = ZONE_NONE;

    private boolean attached;
    /** 主动 requestFocus 的时刻：面板切换可见性时系统会先把焦点塞给第一个可聚焦项，这类变化不记历史 */
    private long lastRestoreAt;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener =
            new ViewTreeObserver.OnGlobalFocusChangeListener() {
                @Override
                public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                    int oldZone = zoneOf(oldFocus);
                    int newZone = zoneOf(newFocus);
                    // 还原焦点之后短暂窗口内的焦点变化是系统自动分配（如刚显示的面板塞给分组列表），
                    // 若照常记录会把"上次停留区域"覆盖成系统默认项，这里直接忽略。
                    if (SystemClock.uptimeMillis() - lastRestoreAt < 300) {
                        return;
                    }
                    if (oldZone != ZONE_NONE && oldZone != newZone) {
                        record(oldZone);
                    }
                    // 焦点进入某个有历史记录的区域：ListView 自己的类焦点恢复并不可靠
                    // （实测从分组列表按右键回来时选中行会漂移 2 行），这里按记录值主动纠偏。
                    if (oldZone != newZone) {
                        reapply(newZone);
                    }
                }
            };

    public PanelFocusHistory(View root) {
        this.root = root;
    }

    /** 绑定区域与视图（zone 取值见 ZONE_* 常量） */
    public void bind(int zone, View view) {
        if (zone < 0 || zone >= ZONE_COUNT) return;
        zoneViews[zone] = view;
    }

    /** 开始监听焦点变化 */
    public void attach() {
        if (attached || root == null) return;
        ViewTreeObserver vto = root.getViewTreeObserver();
        if (vto == null || !vto.isAlive()) return;
        vto.addOnGlobalFocusChangeListener(focusListener);
        attached = true;
    }

    /** 释放监听（Activity 销毁时调用，避免持有已销毁视图） */
    public void detach() {
        if (!attached || root == null) return;
        ViewTreeObserver vto = root.getViewTreeObserver();
        if (vto != null && vto.isAlive()) {
            vto.removeOnGlobalFocusChangeListener(focusListener);
        }
        attached = false;
    }

    /** 清除指定区域记录的位置（如切换分组后旧索引已失效） */
    public void resetPosition(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return;
        hasPosition[zone] = false;
        positions[zone] = 0;
    }

    /**
     * 把焦点放回左侧面板最近停留的位置。
     *
     * @param channelFallbackPosition 无历史时频道列表的落点（通常是正在播放的频道下标）
     * @return 是否成功拿到焦点
     */
    public boolean focusLastLeft(int channelFallbackPosition) {
        int zone = (lastLeftZone == ZONE_NONE) ? ZONE_LEFT_CHANNEL : lastLeftZone;
        if (focus(zone, zone == ZONE_LEFT_CHANNEL ? channelFallbackPosition : -1)) {
            return true;
        }
        return focus(ZONE_LEFT_CHANNEL, channelFallbackPosition)
                || focus(ZONE_LEFT_GROUP, -1)
                || focus(ZONE_LEFT_EPG_BUTTON, -1);
    }

    /** 把焦点放回右侧面板最近停留的位置 */
    public boolean focusLastRight() {
        int zone = (lastRightZone == ZONE_NONE) ? ZONE_RIGHT_CHANNEL : lastRightZone;
        if (focus(zone, -1)) {
            return true;
        }
        return focus(ZONE_RIGHT_CHANNEL, -1)
                || focus(ZONE_RIGHT_DATE, -1)
                || focus(ZONE_RIGHT_EPG, -1)
                || focus(ZONE_RIGHT_BACK_BUTTON, -1);
    }

    private boolean focus(int zone, int fallbackPosition) {
        View view = zoneViews[zone];
        if (view == null) return false;
        // 不依赖 isShown() 提前返回：面板刚切换可见性的同一帧里 isShown 可能还没刷新，
        // 直接尝试 requestFocus，失败（如面板 GONE）时由调用方顺延下一个区域。
        if (view instanceof ListView) {
            ListView lv = (ListView) view;
            int count = lv.getAdapter() == null ? 0 : lv.getAdapter().getCount();
            int pos = hasPosition[zone] ? positions[zone] : fallbackPosition;
            if (count > 0 && pos >= 0) {
                pos = Math.min(pos, count - 1);
                lv.setSelection(pos);
            }
        }
        // 必须在 requestFocus 之前打时间戳：焦点变化回调是同步派发的，
        // 晚一步设置就拦不住"系统先把焦点塞给分组列表、随后被我们移走"这类记录。
        lastRestoreAt = SystemClock.uptimeMillis();
        boolean ok = view.requestFocus();
        if (ok) {
            if (isLeftZone(zone)) {
                lastLeftZone = zone;
            } else if (isRightZone(zone)) {
                lastRightZone = zone;
            }
        }
        LogBridge.d(TAG, "还原焦点 " + zoneName(zone) + " pos="
                + (hasPosition[zone] ? positions[zone] : fallbackPosition) + " 成功=" + ok);
        return ok;
    }

    /**
     * 焦点进入区域后按记录值纠偏（ListView 自身恢复会漂移，如从分组按右键回来时多跳 2 行）。
     * 用 post 避开焦点回调过程中的布局，回投时再确认该区域仍持有焦点，避免抢走快速切换后的焦点。
     */
    private void reapply(int zone) {
        if (zone < 0 || zone >= ZONE_COUNT) return;
        if (!hasPosition[zone]) return;
        final View view = zoneViews[zone];
        if (!(view instanceof ListView)) return;
        final ListView lv = (ListView) view;
        final int pos = positions[zone];
        lv.post(() -> {
            if (!lv.hasFocus()) return;
            int count = lv.getAdapter() == null ? 0 : lv.getAdapter().getCount();
            if (count <= 0) return;
            int target = Math.min(pos, count - 1);
            if (lv.getSelectedItemPosition() == target) return;
            lv.setSelection(target);
            LogBridge.d(TAG, "纠偏焦点 " + zoneName(zone) + " pos=" + target);
        });
    }

    /** 记录区域当前位置（仅在焦点离开该区域时调用） */
    private void record(int zone) {
        View view = zoneViews[zone];
        if (view == null) return;
        if (view instanceof ListView) {
            int pos = ((ListView) view).getSelectedItemPosition();
            if (pos < 0) return;
            positions[zone] = pos;
        } else {
            positions[zone] = 0;
        }
        hasPosition[zone] = true;
        if (isLeftZone(zone)) {
            lastLeftZone = zone;
        } else if (isRightZone(zone)) {
            lastRightZone = zone;
        }
        LogBridge.d(TAG, "记录焦点 " + zoneName(zone) + " pos=" + positions[zone]);
    }

    /** 由焦点视图反查所属区域（视图可能是区域的子视图，故向上遍历父链） */
    private int zoneOf(View view) {
        if (view == null) return ZONE_NONE;
        View cur = view;
        while (cur != null) {
            for (int i = 0; i < ZONE_COUNT; i++) {
                if (zoneViews[i] == cur) return i;
            }
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return ZONE_NONE;
    }

    private static boolean isLeftZone(int zone) {
        return zone == ZONE_LEFT_GROUP || zone == ZONE_LEFT_CHANNEL || zone == ZONE_LEFT_EPG_BUTTON;
    }

    private static boolean isRightZone(int zone) {
        return zone == ZONE_RIGHT_BACK_BUTTON || zone == ZONE_RIGHT_CHANNEL
                || zone == ZONE_RIGHT_DATE || zone == ZONE_RIGHT_EPG;
    }

    private static String zoneName(int zone) {
        return (zone >= 0 && zone < ZONE_COUNT) ? ZONE_NAMES[zone] : "无";
    }
}
