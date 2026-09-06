package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;

public class PlayerGestureHelper {
    private final GestureDetector gestureDetector;
    private final GestureCallback callback;

    private boolean isScrollLocked = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long SCROLL_LOCK_DELAY = 500;

    public PlayerGestureHelper(Context context, GestureCallback callback) {
        this.callback = callback;
        gestureDetector = new GestureDetector(context, new MyGestureListener());
    }

    public void handleTouch(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            callback.onOk();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            callback.onMenu();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            callback.onLongOk();
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {

            if (isScrollLocked) {
                return true;
            }

            if (Math.abs(dy) > Math.abs(dx)) {

                if (dy > 10) {
                    callback.onNextChannel();
                    lockScroll();
                }

                else if (dy < -10) {
                    callback.onPrevChannel();
                    lockScroll();
                }
            }
            return true;
        }

        private void lockScroll() {
            isScrollLocked = true;
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler.postDelayed(() -> {
                isScrollLocked = false;
            }, SCROLL_LOCK_DELAY);
        }
    }

    public interface GestureCallback {
        void onOk();
        void onLongOk();
        void onMenu();
        void onPrevChannel();
        void onNextChannel();
    }
}
