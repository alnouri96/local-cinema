package org.proninyaroslav.libretorrent.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.cinema_hub.CinemaRemoteBridge;

final class PlaybackBubble extends ImageButton {
    private static final int GOLD = 0xffe5a93b;
    private final FrameLayout host;
    private final SharedPreferences preferences;
    private final int touchSlop;
    private final int size;
    private final View.OnLayoutChangeListener positionOnLayout =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (!this.dragging) restorePosition();
            };
    private float downX;
    private float downY;
    private float startX;
    private float startY;
    private int activePointer = MotionEvent.INVALID_POINTER_ID;
    private boolean dragging;
    private boolean audio;

    PlaybackBubble(FrameLayout host, Runnable open) {
        super(host.getContext());
        this.host = host;
        preferences = getContext().getSharedPreferences("cinema_playback_bubble", Context.MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        size = dp(52);
        var circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xff171717);
        circle.setStroke(dp(1), GOLD);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x44e5a93b), circle, null));
        setImageTintList(ColorStateList.valueOf(GOLD));
        setImageResource(R.drawable.ic_cinema_cast_play);
        setElevation(dp(8));
        int inset = dp(12);
        setPadding(inset, inset, inset, inset);
        setScaleType(ScaleType.FIT_CENTER);
        setFocusable(true);
        setOnClickListener(view -> open.run());
        setVisibility(GONE);
        host.addView(this, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
        host.addOnLayoutChangeListener(positionOnLayout);
    }

    void refresh() {
        String kind = CinemaRemoteBridge.getCastKind();
        boolean active = CinemaRemoteBridge.isCastActive() && ("audio".equals(kind) || "video".equals(kind));
        if (!active) {
            clearDrag();
            setVisibility(GONE);
            return;
        }
        boolean music = "audio".equals(kind);
        if (music != audio) {
            audio = music;
            setImageResource(audio ? R.drawable.ic_audiotrack_24 : R.drawable.ic_cinema_cast_play);
        }
        String description = getResources().getString(audio ? R.string.cinema_bubble_music : R.string.cinema_bubble_tv);
        if (!description.contentEquals(getContentDescription() == null ? "" : getContentDescription())) {
            setContentDescription(description);
        }
        if (getVisibility() != VISIBLE) {
            restorePosition();
            setVisibility(VISIBLE);
            bringToFront();
        }
    }

    void detach() {
        clearDrag();
        host.removeOnLayoutChangeListener(positionOnLayout);
        host.removeView(this);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = event.getPointerId(0);
                downX = event.getRawX();
                downY = event.getRawY();
                startX = getTranslationX();
                startY = getTranslationY();
                setPressed(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.findPointerIndex(activePointer) != 0) {
                    clearDrag();
                    return true;
                }
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) >= touchSlop) {
                    dragging = true;
                    setPressed(false);
                }
                if (dragging) place(startX + dx, startY + dy);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == activePointer) finishDrag(false);
                return true;
            case MotionEvent.ACTION_UP:
                finishDrag(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishDrag(false);
                return true;
            default:
                return true;
        }
    }

    private void finishDrag(boolean click) {
        boolean tapped = click && !dragging && activePointer != MotionEvent.INVALID_POINTER_ID;
        if (dragging) {
            var bounds = bounds();
            preferences.edit()
                    .putFloat("x", fraction(getTranslationX(), bounds.left, bounds.right))
                    .putFloat("y", fraction(getTranslationY(), bounds.top, bounds.bottom)).apply();
        }
        clearDrag();
        if (tapped) performClick();
    }

    private void clearDrag() {
        if (activePointer != MotionEvent.INVALID_POINTER_ID && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        activePointer = MotionEvent.INVALID_POINTER_ID;
        dragging = false;
        setPressed(false);
    }

    private void restorePosition() {
        var area = bounds();
        float x = preferences.getFloat("x", host.getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 0f : 1f);
        float y = preferences.getFloat("y", .74f);
        place(area.left + x * (area.right - area.left), area.top + y * (area.bottom - area.top));
    }

    private android.graphics.Rect bounds() {
        int gutter = dp(8);
        int verticalSpace = Math.max(0, host.getHeight() - size);
        int toolbarClearance = Math.min(dp(80), verticalSpace / 3);
        return new android.graphics.Rect(gutter, toolbarClearance,
                Math.max(gutter, host.getWidth() - size - gutter), verticalSpace - toolbarClearance);
    }

    private void place(float x, float y) {
        var area = bounds();
        setTranslationX(Math.max(area.left, Math.min(area.right, x)));
        setTranslationY(Math.max(area.top, Math.min(area.bottom, y)));
    }

    private static float fraction(float value, int start, int end) {
        return end == start ? 0f : (value - start) / (end - start);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
