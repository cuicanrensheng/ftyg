package com.tv.live.util;

import com.tv.live.util.LogBridge;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.List;

public class RemoteKeyHandler {
    public interface OnKeyAction {
        void onMenuKey();
        void onOkKey();
        void onChannelUp();
        void onChannelDown();
        void onSeekBackward();
        void onSeekForward();
        /** 左右键抬起：提交预览位置（长按/连按期间只累积预览，此时才真正 seek 一次） */
        void onSeekCommit();
        void onPlayPause();
        void onStop();
        void onBackKey();
    }

    public interface OnNumberInput {
        void onNumberInput(String input);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private long okKeyDownTime = 0;
    private boolean okKeyTriggered = false;
    private boolean okKeyLongPressed = false;
    private static final long OK_LONG_PRESS_DURATION = 1500;

    private boolean isInCatchUpMode = false;
    private int longPressKeyCode = -1;

    // 连发节奏（2026-09-14 按实测反馈调快：原先 400/200 感觉进度条跟得慢）
    private static final long SEEK_REPEAT_DELAY_MS = 250;
    private static final long SEEK_REPEAT_INTERVAL_MS = 100;

    private final StringBuilder numberInputBuffer = new StringBuilder();
    private Runnable numberInputConfirmTask;
    private OnNumberInput numberInputCallback;
    private boolean numberInputEnabled = true;

    private OnKeyAction keyAction;

    /** 当前活跃的处理器（由构造器注册，release() 注销），供 LogServer 局域网控制端点使用 */
    private static volatile RemoteKeyHandler sActive;

    private final Runnable longPressSeekRunnable = new Runnable() {
        @Override
        public void run() {
            if (longPressKeyCode == -1 || !isInCatchUpMode) return;
            if (keyAction == null) {
                longPressKeyCode = -1;
                return;
            }
            if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                keyAction.onSeekBackward();
            } else if (longPressKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                keyAction.onSeekForward();
            }
            mainHandler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS);
        }
    };

    public RemoteKeyHandler(OnKeyAction keyAction) {
        this.keyAction = keyAction;
        sActive = this;
    }

    /**
     * 供 LogServer 局域网控制端点调用：按「面板关闭」语义触发与物理遥控器按键相同的内部动作
     * （up=频道+ down=频道- ok/left=确定 right/menu=菜单 back=返回 playpause/stop/seekback/seekforward）。
     * 动作会 post 到主线程执行；返回 false 表示当前没有活跃的播放界面。
     */
    public static boolean dispatchRemoteAction(String action) {
        final RemoteKeyHandler h = sActive;
        if (h == null || h.keyAction == null || action == null) return false;
        final String a = action.trim().toLowerCase(java.util.Locale.US);
        h.mainHandler.post(() -> {
            if (h.keyAction == null) return;
            try {
                switch (a) {
                    case "up": case "channelup":
                        h.keyAction.onChannelUp(); break;
                    case "down": case "channeldown":
                        h.keyAction.onChannelDown(); break;
                    case "ok": case "enter": case "left":
                        h.keyAction.onOkKey(); break;
                    case "right": case "menu":
                        h.keyAction.onMenuKey(); break;
                    case "back":
                        h.keyAction.onBackKey(); break;
                    case "playpause":
                        h.keyAction.onPlayPause(); break;
                    case "stop":
                        h.keyAction.onStop(); break;
                    case "seekback": case "rewind":
                        h.keyAction.onSeekBackward(); break;
                    case "seekforward": case "forward":
                        h.keyAction.onSeekForward(); break;
                    default:
                        LogBridge.w("RemoteKeyHandler", "dispatchRemoteAction 未知动作: " + a);
                        break;
                }
            } catch (Exception e) {
                LogBridge.e("RemoteKeyHandler", "dispatchRemoteAction 异常: " + e.getMessage(), e);
            }
        });
        return true;
    }

    public void setNumberInputCallback(OnNumberInput callback) {
        this.numberInputCallback = callback;
    }

    public void setNumberInputEnabled(boolean enabled) {
        this.numberInputEnabled = enabled;
    }

    public void setCatchUpMode(boolean enabled) {
        this.isInCatchUpMode = enabled;
        if (!enabled) {
            longPressKeyCode = -1;
            mainHandler.removeCallbacks(longPressSeekRunnable);
        }
    }

    public boolean isInCatchUpMode() {
        return isInCatchUpMode;
    }

    public void resetOkKeyState() {
        okKeyLongPressed = false;
        okKeyTriggered = false;
        okKeyDownTime = 0;
    }

    public void clearNumberInput() {
        numberInputBuffer.setLength(0);
        if (numberInputConfirmTask != null) {
            mainHandler.removeCallbacks(numberInputConfirmTask);
        }
    }

    public String getNumberInputBuffer() {
        return numberInputBuffer.toString();
    }

    public static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == 100;
    }

    public static boolean isMenuKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_HELP
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == 101;
    }

    public static boolean isChannelUpKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    public static boolean isChannelDownKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    public boolean handleKeyEvent(KeyEvent event, boolean panelOpen) {
        if (event == null || keyAction == null) {
            return false;
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();

        try {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    return handleKeyDown(keyCode, panelOpen);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                return handleKeyUp(keyCode, panelOpen);
            }
        } catch (Exception e) {
            LogBridge.e("RemoteKeyHandler", "handleKeyEvent 异常 keyCode=" + keyCode + ": " + e.getMessage(), e);
        }

        return false;
    }

    private boolean handleKeyDown(int keyCode, boolean panelOpen) {
        if (isMenuKey(keyCode)) {
            keyAction.onMenuKey();
            return true;
        }

        if (isOkKey(keyCode)) {
            if (!panelOpen) {
                keyAction.onOkKey();
                return true;
            }
        }

        if (isChannelUpKey(keyCode)) {
            if (!panelOpen) {
                keyAction.onChannelUp();
                return true;
            }
        } else if (isChannelDownKey(keyCode)) {
            if (!panelOpen) {
                keyAction.onChannelDown();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            // 【2026-10-12 修复】回看模式+面板打开时禁用进度条快退，左右键归还面板（同 MainActivity 口径）。
            if (isInCatchUpMode && !panelOpen) {
                keyAction.onSeekBackward();
                longPressKeyCode = keyCode;
                mainHandler.removeCallbacks(longPressSeekRunnable);
                mainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                return true;
            }
            if (!panelOpen) {
                keyAction.onOkKey();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // 【2026-10-12 修复】同上：面板打开期间禁用进度条快进。
            if (isInCatchUpMode && !panelOpen) {
                keyAction.onSeekForward();
                longPressKeyCode = keyCode;
                mainHandler.removeCallbacks(longPressSeekRunnable);
                mainHandler.postDelayed(longPressSeekRunnable, SEEK_REPEAT_DELAY_MS);
                return true;
            }
            if (!panelOpen) {
                keyAction.onMenuKey();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            keyAction.onPlayPause();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            keyAction.onStop();
            return true;
        } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            handleNumberKey(keyCode);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            keyAction.onBackKey();
            return true;
        }

        return false;
    }

    private boolean handleKeyUp(int keyCode, boolean panelOpen) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (longPressKeyCode == keyCode) {
                longPressKeyCode = -1;
                mainHandler.removeCallbacks(longPressSeekRunnable);
                // 松手：用最终预览位置做一次 seek（回看模式下才有预览）
                if (isInCatchUpMode && keyAction != null) {
                    keyAction.onSeekCommit();
                }
            }
        }
        if (isOkKey(keyCode)) {
            resetOkKeyState();
            if (!panelOpen) {
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }

        return false;
    }

    private void handleNumberKey(int keyCode) {
        if (!numberInputEnabled) return;
        if (numberInputCallback == null) return;

        int num = keyCode - KeyEvent.KEYCODE_0;
        if (num < 0 || num > 9) return;

        if (numberInputConfirmTask != null) {
            mainHandler.removeCallbacks(numberInputConfirmTask);
        }

        numberInputBuffer.append(num);

        if (numberInputBuffer.length() > 4) {
            numberInputBuffer.delete(0, numberInputBuffer.length() - 4);
        }

        numberInputCallback.onNumberInput(numberInputBuffer.toString());

        numberInputConfirmTask = () -> {
            String input = numberInputBuffer.toString();
            numberInputBuffer.setLength(0);
            numberInputCallback.onNumberInput(input);
        };
        mainHandler.postDelayed(numberInputConfirmTask, 1500);
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
        longPressKeyCode = -1;
        numberInputBuffer.setLength(0);
        numberInputConfirmTask = null;
        numberInputCallback = null;
        keyAction = null;
    }
}
