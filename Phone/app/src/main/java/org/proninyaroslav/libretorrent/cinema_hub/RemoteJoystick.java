package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.function.IntConsumer;

final class RemoteJoystick extends View {
    private static final int GOLD = 0xffe5b84b;
    private static final int TOUCH_ACCENT = 0xff3b82f6;
    private static final int DPAD_ACCENT = 0xfff59e0b;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final IntConsumer send;
    private final float density;
    private boolean touchpad = true;
    private int touchpadSpeed;
    private static final int[] TOUCHPAD_REPEAT_MS = {430, 300, 200};

    void setTouchpadSpeed(int speed) {
        touchpadSpeed = Math.max(0, Math.min(speed, TOUCHPAD_REPEAT_MS.length - 1));
    }
    private boolean touching;
    private boolean pointer;
    private java.util.function.BiConsumer<Double, Double> pointerMove;
    private long lastPointerMove;

    boolean isTouchpad() { return touchpad; }
    boolean isPointer() { return pointer; }

    void setPointer(boolean enabled, java.util.function.BiConsumer<Double, Double> move) {
        stop();
        pointer = enabled;
        pointerMove = move;
        if (enabled) setContentDescription(getContext().getString(org.proninyaroslav.libretorrent.R.string.cinema_remote_free_pointer));
        else setContentDescription(touchpad ? "Smart Touchpad" : "Classic D-Pad");
        invalidate();
    }
    private boolean tap;
    private boolean movedBeyondSlop;
    private float x, y, anchorX, anchorY, downX, downY, prevSwipeX;
    private long lastSwipeMs;
    private int direction;
    private IntConsumer seekHandler;
    private final Runnable hold = () -> {
        if (touching && tap && !movedBeyondSlop && isEnabled()) {
            tap = false;
            emit(-23);
        }
    };
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (!touching || direction == 0 || !isEnabled()) return;
            if (touchpad && !pointer && seekHandler != null && (direction == 21 || direction == 22)) {
                emitSeek(direction == 22 ? 10_000 : -10_000);
            } else {
                emit(direction);
            }
            postDelayed(this, touchpad ? TOUCHPAD_REPEAT_MS[touchpadSpeed] : 120);
        }
    };

    void setOnTouchpadSeekListener(IntConsumer handler) {
        this.seekHandler = handler;
    }

    RemoteJoystick(Context context, IntConsumer send) {
        super(context);
        this.send = send;
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setTouchpad(true);
    }

    void setTouchpad(boolean enabled) {
        stop();
        touchpad = enabled;
        setContentDescription(enabled ? "Smart Touchpad" : "Classic D-Pad");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        if (touchpad) {
            float corner = 16 * density;
            float inset = density * 1.6f;
            paint.setColor(0xff141720);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, corner, corner, paint);

            paint.setColor(isEnabled() ? TOUCH_ACCENT : 0xff747068);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density * 1.8f);
            canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, corner, corner, paint);
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(0x993b82f6);
            for (int row = -1; row <= 1; row++)
                for (int col = -1; col <= 1; col++)
                    canvas.drawCircle(cx + col * 12 * density, cy + row * 12 * density, 2.2f * density, paint);
            if (touching) {
                paint.setShader(new RadialGradient(x, y, 36 * density, 0x993b82f6, 0x003b82f6, Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, 36 * density, paint);
                paint.setShader(null);
            }
            return;
        }

        // Clean circular D-Pad (no outer box border around it)
        float radius = Math.min(cx, cy) - density * 4;
        paint.setColor(0xff1a1917);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(isEnabled() ? DPAD_ACCENT : 0xff747068);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * 1.8f);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(isEnabled() ? DPAD_ACCENT : 0xff747068);
        float arrowSize = radius * 0.145f;
        float arrowDist = radius * 0.66f;
        drawTriangle(canvas, cx, cy - arrowDist, arrowSize, 0, paint);
        drawTriangle(canvas, cx, cy + arrowDist, arrowSize, 180, paint);
        drawTriangle(canvas, cx - arrowDist, cy, arrowSize, 270, paint);
        drawTriangle(canvas, cx + arrowDist, cy, arrowSize, 90, paint);

        canvas.drawCircle(cx, cy, radius * 0.30f, paint);
        paint.setColor(0xff141517);
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * 0.21f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText("OK", cx, cy - (fm.ascent + fm.descent) / 2f, paint);
        paint.setFakeBoldText(false);
    }

    private static void drawTriangle(Canvas canvas, float tx, float ty, float s, float degrees, Paint p) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(0, -s);
        path.lineTo(s * 0.95f, s * 0.75f);
        path.lineTo(-s * 0.95f, s * 0.75f);
        path.close();
        canvas.save();
        canvas.translate(tx, ty);
        canvas.rotate(degrees);
        canvas.drawPath(path, p);
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            stop();
            return false;
        }
        x = event.getX();
        y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                downX = anchorX = prevSwipeX = x;
                downY = anchorY = y;
                lastSwipeMs = android.os.SystemClock.uptimeMillis();
                movedBeyondSlop = false;
                tap = true;
                if (!touchpad) animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).start();
                if (!touchpad) classic();
                if (tap && !pointer) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!movedBeyondSlop
                        && Math.hypot(x - downX, y - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    movedBeyondSlop = true;
                    tap = false;
                    removeCallbacks(hold);
                }
                if (pointer) movePointer(false);
                else if (touchpad) swipe();
                else classic();
            }
            case MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                if (pointer) movePointer(true);
                else if (touchpad) swipe();
                if (tap && !movedBeyondSlop) performClick();
                stop();
            }
            case MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                stop();
            }
        }
        invalidate();
        return true;
    }

    private void movePointer(boolean release) {
        double dx = x - anchorX, dy = y - anchorY;
        if (tap && Math.hypot(dx, dy) <= ViewConfiguration.get(getContext()).getScaledTouchSlop()) return;
        tap = false;
        long now = android.os.SystemClock.uptimeMillis();
        if (!release && now - lastPointerMove < 28) return;
        pointerMove.accept(dx / Math.max(1, getWidth()), dy / Math.max(1, getHeight()));
        anchorX = x;
        anchorY = y;
        lastPointerMove = now;
    }

    private void classic() {
        float dx = x - getWidth() / 2f, dy = y - getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f;
        int next = Math.hypot(dx, dy) < radius * .32f ? 0 : key(dx, dy);
        if (next == direction) return;
        repeat(next);
        if (next != 0) {
            tap = false;
            removeCallbacks(hold);
            emit(next);
        }
    }

    private void swipe() {
        long now = android.os.SystemClock.uptimeMillis();
        float dx = x - anchorX, dy = y - anchorY;
        if (Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
            movedBeyondSlop = true;
            tap = false;
            removeCallbacks(hold);
        }
        int next = key(dx, dy);
        boolean horizontalSeek = seekHandler != null && (next == 21 || next == 22);
        float step = (horizontalSeek ? 28f : 45f) * density;
        int steps = (int) (Math.max(Math.abs(dx), Math.abs(dy)) / step);
        if (horizontalSeek && steps > 0) {
            float dtMs = Math.max(1f, now - lastSwipeMs);
            float vx = Math.abs(x - prevSwipeX) / dtMs;
            int stepSecMs = vx > (1.8f * density) ? 30_000 : 10_000;
            int signedDeltaMs = dx > 0 ? stepSecMs : -stepSecMs;
            int emitted = Math.min(steps, 4);
            for (int i = 0; i < emitted; i++) emitSeek(signedDeltaMs);
            anchorX += Math.copySign(step * steps, dx);
            anchorY = y;
        } else {
            for (int i = 0; i < steps; i++) emit(next);
            if (steps > 0) {
                if (next == 21 || next == 22) {
                    anchorX += Math.copySign(step * steps, dx);
                    anchorY = y;
                } else {
                    anchorX = x;
                    anchorY += Math.copySign(step * steps, dy);
                }
            } else if (direction != 0) next = direction;
        }
        prevSwipeX = x;
        lastSwipeMs = now;
        if (steps > 0) repeat(next);
    }

    private void emitSeek(int deltaMs) {
        if (seekHandler == null) return;
        seekHandler.accept(deltaMs);
        pulseHaptic(Math.abs(deltaMs) >= 30_000);
    }

    private int key(float dx, float dy) {
        return Math.abs(dx) > Math.abs(dy) ? (dx < 0 ? 21 : 22) : (dy < 0 ? 19 : 20);
    }

    private void repeat(int next) {
        if (direction == next) return;
        removeCallbacks(repeat);
        direction = next;
        if (next != 0) postDelayed(repeat, touchpad ? TOUCHPAD_REPEAT_MS[touchpadSpeed] + 130L : 350L);
    }

    private void emit(int key) {
        send.accept(key);
        if (key == -23 && touchpad) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            pulseHaptic(false);
        } else {
            pulseHaptic(Math.abs(key) == 23);
        }
    }

    private void pulseHaptic(boolean strong) {
        performHapticFeedback(strong ? HapticFeedbackConstants.VIRTUAL_KEY : HapticFeedbackConstants.KEYBOARD_TAP);
        try {
            android.os.Vibrator vib = getContext().getSystemService(android.os.Vibrator.class);
            if (vib != null && vib.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vib.vibrate(android.os.VibrationEffect.createOneShot(strong ? 22 : 16, strong ? 110 : 85));
                } else {
                    vib.vibrate(strong ? 22 : 16);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!isEnabled()) return false;
        emit(23);
        return true;
    }

    private void stop() {
        removeCallbacks(repeat);
        removeCallbacks(hold);
        if (touching && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        touching = false;
        direction = 0;
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
