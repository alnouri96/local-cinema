package org.proninyaroslav.libretorrent.cinema_hub;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.proninyaroslav.libretorrent.R;

final class FloatingRemoteLayout extends FrameLayout {
    private static final int GOLD = 0xffe5a93b;
    private static final int SOFT_BLACK = 0xff171717;
    private static final String WIDTH = "remote_width_dp";
    private static final String HEIGHT = "remote_height_dp";
    private static final String X = "remote_x_fraction";
    private static final String Y = "remote_y_fraction";

    private enum Drag { TOP, BOTTOM, LEFT, RIGHT, MOVE }

    private final SharedPreferences prefs;
    private final View remote;
    private final FrameLayout card;
    private final FrameLayout editor;
    private final GradientDrawable outline = new GradientDrawable();
    private final Rect bounds = new Rect();
    private int viewportWidth;
    private int viewportHeight;
    private boolean editing;
    private boolean reset;
    private boolean atLimit;
    private long lastLimitPulse;

    private final int[] screenXY = new int[2];
    private View headerBottom;
    private final Runnable dismiss;
    private float downX;
    private float downY;
    private boolean trackingSlide;
    private boolean sliding;

    void setSlideRegion(View headerBottom) {
        this.headerBottom = headerBottom;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (headerBottom == null || editing) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                card.getLocationOnScreen(screenXY);
                int left = screenXY[0];
                int top = screenXY[1];
                downX = event.getRawX();
                downY = event.getRawY();
                headerBottom.getLocationOnScreen(screenXY);
                trackingSlide = downX >= left && downX < left + card.getWidth()
                        && downY >= top && downY < Math.min(top + card.getHeight(), screenXY[1] + headerBottom.getHeight() + dp(4));
                sliding = false;
                return super.dispatchTouchEvent(event) || trackingSlide;
            case MotionEvent.ACTION_MOVE:
                if (!trackingSlide) break;
                float dy = event.getRawY() - downY;
                if (!sliding && dy > dp(14) && dy > Math.abs(event.getRawX() - downX) * 1.25f) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    card.animate().cancel();
                    sliding = true;
                }
                if (sliding) {
                    card.setTranslationY(Math.max(0, dy));
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                trackingSlide = false;
                if (sliding) {
                    sliding = false;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            && Math.abs(card.getTranslationY()) >= dp(56)) {
                        card.animate().translationY(getHeight() - bounds.top)
                                .setDuration(160).withEndAction(dismiss).start();
                    } else {
                        card.animate().translationY(0).setDuration(140).start();
                    }
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event) || trackingSlide;
    }

    FloatingRemoteLayout(Context context, SharedPreferences prefs, View remote, Runnable dismiss) {
        super(context);
        this.prefs = prefs;
        this.dismiss = dismiss;
        this.remote = remote;
        setOnClickListener(v -> {
            if (!editing) dismiss.run();
        });

        card = new FrameLayout(context);
        card.setClickable(true);
        addView(card, new LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
        card.addView(remote, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

        editor = new FrameLayout(context);
        editor.setVisibility(GONE);
        editor.setClickable(true);
        editor.setBackground(surface(0xc4171717, 22));
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(22));
        outline.setStroke(dp(2), GOLD);
        editor.setForeground(outline);
        card.addView(editor, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

        addGrip(Drag.TOP, Gravity.TOP | Gravity.CENTER_HORIZONTAL, R.string.cinema_remote_resize_top);
        addGrip(Drag.BOTTOM, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, R.string.cinema_remote_resize_bottom);
        addGrip(Drag.LEFT, Gravity.LEFT | Gravity.CENTER_VERTICAL, R.string.cinema_remote_resize_left);
        addGrip(Drag.RIGHT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, R.string.cinema_remote_resize_right);

        ImageView move = new ImageView(context);
        move.setBackground(surface(SOFT_BLACK, 25));
        move.setImageResource(R.drawable.ic_remote_dpad);
        move.setColorFilter(GOLD);
        move.setPadding(dp(13), dp(13), dp(13), dp(13));
        move.setContentDescription(context.getString(R.string.cinema_remote_move));
        editor.addView(move, new LayoutParams(dp(50), dp(50), Gravity.CENTER));
        bindDrag(move, Drag.MOVE);

        var actions = new LinearLayout(context);
        var actionParams = new LayoutParams(WRAP_CONTENT, dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        actionParams.bottomMargin = dp(40);
        editor.addView(actions, actionParams);
        addAction(actions, R.string.cinema_remote_reset, false, () -> {
            reset = true;
            restoreBounds();
            requestLayout();
        });
        addAction(actions, R.string.cinema_remote_done, true, () -> {
            saveBounds();
            setEditing(false);
        });
    }

    void setEditing(boolean editing) {
        this.editing = editing;
        editor.setVisibility(editing ? VISIBLE : GONE);
        remote.setImportantForAccessibility(editing
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (!editing) reset = false;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        if (viewportWidth != width || viewportHeight != height) {
            viewportWidth = width;
            viewportHeight = height;
            if (width <= dp(16) || height <= dp(16)) {
                bounds.setEmpty();
            } else if (editing && !bounds.isEmpty()) {
                updateBounds(bounds.left, bounds.top, Math.min(bounds.width(), maxWidth()),
                        Math.min(bounds.height(), maxHeight()));
            } else {
                restoreBounds();
            }
        }
        card.measure(MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        card.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    private void restoreBounds() {
        if (viewportWidth <= dp(16) || viewportHeight <= dp(16)) return;
        int defaultHeight = Math.min(dp(570), Math.max(dp(505), Math.round(viewportHeight * .72f)));
        int width = reset ? maxWidth() : dp(prefs.getInt(WIDTH, 460));
        int height = reset ? defaultHeight : dp(prefs.getInt(HEIGHT, Math.round(defaultHeight / density())));
        width = clamp(width, minWidth(), maxWidth());
        height = clamp(height, minHeight(), maxHeight());
        float x = reset ? .5f : prefs.getFloat(X, .5f);
        float y = reset ? 1f : prefs.getFloat(Y, 1f);
        updateBounds(dp(8) + Math.round((maxWidth() - width) * x),
                dp(8) + Math.round((maxHeight() - height) * y), width, height);
    }

    private void saveBounds() {
        prefs.edit()
                .putInt(WIDTH, Math.round(bounds.width() / density()))
                .putInt(HEIGHT, Math.round(bounds.height() / density()))
                .putFloat(X, fraction(bounds.left - dp(8), maxWidth() - bounds.width(), .5f))
                .putFloat(Y, fraction(bounds.top - dp(8), maxHeight() - bounds.height(), 1f))
                .apply();
    }

    private static float fraction(int offset, int available, float fallback) {
        return available > 0 ? offset / (float) available : fallback;
    }

    private void updateBounds(int left, int top, int width, int height) {
        left = clamp(left, dp(8), viewportWidth - dp(8) - width);
        top = clamp(top, dp(8), viewportHeight - dp(8) - height);
        bounds.set(left, top, left + width, top + height);
    }

    private void place(int left, int top, int width, int height) {
        updateBounds(left, top, width, height);
        requestLayout();
    }

    private void addGrip(Drag direction, int gravity, int description) {
        boolean horizontal = direction == Drag.TOP || direction == Drag.BOTTOM;
        var area = new FrameLayout(getContext());
        var mark = new View(getContext());
        mark.setBackground(surface(GOLD, 3));
        area.addView(mark, new LayoutParams(dp(horizontal ? 30 : 5),
                dp(horizontal ? 5 : 30), Gravity.CENTER));
        editor.addView(area, new LayoutParams(dp(48), dp(48), gravity));
        area.setContentDescription(getContext().getString(description));
        bindDrag(area, direction);
    }

    private void bindDrag(View view, Drag direction) {
        var start = new Rect();
        float[] down = new float[2];
        view.setFocusable(true);
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    start.set(bounds);
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    atLimit = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    adjust(direction, start, Math.round(event.getRawX() - down[0]),
                            Math.round(event.getRawY() - down[1]));
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    return true;
                }
                default -> { return false; }
            }
        });
        view.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int step = dp(12);
            switch (key) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT -> adjust(direction, new Rect(bounds), -step, 0);
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> adjust(direction, new Rect(bounds), step, 0);
                case android.view.KeyEvent.KEYCODE_DPAD_UP -> adjust(direction, new Rect(bounds), 0, -step);
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN -> adjust(direction, new Rect(bounds), 0, step);
                default -> { return false; }
            }
            return true;
        });
    }

    private void adjust(Drag direction, Rect start, int dx, int dy) {
        int left = start.left;
        int top = start.top;
        int right = start.right;
        int bottom = start.bottom;
        switch (direction) {
            case MOVE -> {
                place(left + dx, top + dy, start.width(), start.height());
                return;
            }
            case TOP -> top = clamp(top + dy, dp(8), bottom - minHeight());
            case BOTTOM -> bottom = clamp(bottom + dy, top + minHeight(), viewportHeight - dp(8));
            case LEFT -> left = clamp(left + dx, dp(8), right - minWidth());
            case RIGHT -> right = clamp(right + dx, left + minWidth(), viewportWidth - dp(8));
        }
        int width = right - left;
        int height = bottom - top;
        boolean limit = switch (direction) {
            case LEFT -> left == dp(8) || width == minWidth();
            case RIGHT -> right == viewportWidth - dp(8) || width == minWidth();
            case TOP -> top == dp(8) || height == minHeight();
            case BOTTOM -> bottom == viewportHeight - dp(8) || height == minHeight();
            case MOVE -> false;
        };
        long now = SystemClock.uptimeMillis();
        if (limit && !atLimit && now - lastLimitPulse > 170) {
            lastLimitPulse = now;
            outline.setStroke(dp(3), 0xffffebbd);
            editor.postDelayed(() -> outline.setStroke(dp(2), GOLD), 150);
        }
        atLimit = limit;
        place(left, top, width, height);
    }

    private void addAction(LinearLayout group, int label, boolean filled, Runnable onClick) {
        var button = new TextView(getContext());
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(filled ? SOFT_BLACK : GOLD);
        button.setPaddingRelative(dp(14), 0, dp(14), 0);
        button.setBackground(surface(filled ? GOLD : SOFT_BLACK, 20));
        button.setOnClickListener(v -> onClick.run());
        button.setFocusable(true);
        var params = new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT);
        params.setMarginStart(dp(4));
        params.setMarginEnd(dp(4));
        group.addView(button, params);
    }

    private GradientDrawable surface(int color, int radiusDp) {
        var drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int minWidth() { return Math.min(dp(300), maxWidth()); }
    private int minHeight() { return Math.min(dp(384), maxHeight()); }
    private int maxWidth() { return Math.max(1, viewportWidth - dp(16)); }
    private int maxHeight() { return Math.max(1, viewportHeight - dp(16)); }
    private float density() { return getResources().getDisplayMetrics().density; }
    private int dp(int value) { return Math.round(value * density()); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
