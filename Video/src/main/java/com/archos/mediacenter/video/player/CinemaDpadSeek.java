package com.archos.mediacenter.video.player;

import android.view.KeyEvent;

/** Remote seek steps shared by the native and adaptive TV players. */
public final class CinemaDpadSeek {
    private long lastStepAt;

    public static boolean isHorizontal(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    public int offset(KeyEvent event) {
        long now = event.getEventTime();
        if (event.getRepeatCount() > 0 && now - lastStepAt < 300) return 0;
        lastStepAt = now;
        long held = event.getRepeatCount() == 0 ? 0 : now - event.getDownTime();
        int step = held >= 3500 ? 30_000 : held >= 1500 ? 20_000 : 10_000;
        return event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step;
    }

    public static int clamp(int duration, int position, int offset) {
        return (int) Math.max(0, Math.min(duration, (long) position + offset));
    }
}
