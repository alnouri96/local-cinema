package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.archos.mediacenter.video.R;

import java.util.Locale;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

/**
 * Semi-transparent, compact Live Timing Overlay Bar anchored at the very top of the video screen
 * so users can adjust subtitle timing in real time without obscuring the video or bottom subtitles.
 *
 * Ergonomics:
 * - Opens with focus on the live stepper pill: D-pad Left/Right adjusts ±0.1s immediately on screen,
 *   OK resets to 0.0s.
 * - D-pad Down moves to the quick buttons row (-0.5s · -0.1s · Current/Reset · +0.1s · +0.5s)
 *   in physical Left-to-Right order; D-pad Up returns to the live stepper pill.
 * - Back closes the bar and saves the session timing without stopping playback.
 */
public final class CinemaSubtitleTimingOverlay {
    public static final long MIN_DELAY_MS = -60_000L;
    public static final long MAX_DELAY_MS = 60_000L;
    private static final int STEP_FINE_MS = 100;
    private static final int STEP_COARSE_MS = 500;

    public interface Listener {
        void onDelayChanged(long newDelayMs);
        void onDismissed(long finalDelayMs);
    }

    private final Activity activity;
    private final ViewGroup hostRoot;
    private final LinearLayout container;
    private final LinearLayout liveStepperRow;
    private final TextView leftArrowTv;
    private final TextView liveValueTv;
    private final TextView rightArrowTv;
    private final TextView[] quickButtons = new TextView[5];

    private Listener listener;
    private long currentDelayMs;
    private boolean showing;
    private boolean swallowNextBackUp;
    private boolean stepperFocused = true;
    private int focusedQuickIndex = 2;

    public CinemaSubtitleTimingOverlay(Activity activity, ViewGroup hostRoot) {
        this.activity = activity;
        this.hostRoot = hostRoot;
        boolean ar = arabic(activity);

        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        container.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12));
        container.setBackground(panelSurface(activity));
        container.setElevation(dp(activity, 12));
        container.setVisibility(View.GONE);
        container.setClickable(true);

        // Header line: icon + title + compact key hint
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.cinema_ic_timer);
        icon.setColorFilter(CinemaTokens.GOLD);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(activity, 16), dp(activity, 16));
        iconLp.setMarginEnd(dp(activity, 8));
        header.addView(icon, iconLp);

        TextView titleTv = text(activity, 14, true);
        titleTv.setText(activity.getString(R.string.cinema_sub_timing_shortcut));
        titleTv.setTextColor(Color.WHITE);
        header.addView(titleTv);

        TextView hintTv = text(activity, 11, false);
        hintTv.setText("  ·  " + activity.getString(R.string.cinema_sub_timing_overlay_hint));
        hintTv.setTextColor(0xFFD0D0D8);
        header.addView(hintTv);

        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-2, -2);
        headerLp.bottomMargin = dp(activity, 8);
        container.addView(header, headerLp);

        // Row 1: Direct Left/Right live stepper pill (LTR physical alignment so Left = minus, Right = plus)
        liveStepperRow = new LinearLayout(activity);
        liveStepperRow.setOrientation(LinearLayout.HORIZONTAL);
        liveStepperRow.setGravity(Gravity.CENTER);
        liveStepperRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        liveStepperRow.setFocusable(true);
        liveStepperRow.setClickable(true);
        liveStepperRow.setPadding(dp(activity, 16), dp(activity, 7), dp(activity, 16), dp(activity, 7));

        leftArrowTv = text(activity, 14, true);
        leftArrowTv.setText("◀  " + formatDeltaShort(activity, -STEP_FINE_MS));
        leftArrowTv.setPadding(dp(activity, 6), 0, dp(activity, 14), 0);
        leftArrowTv.setClickable(true);
        leftArrowTv.setOnClickListener(v -> adjustBy(-STEP_FINE_MS, true));
        liveStepperRow.addView(leftArrowTv);

        liveValueTv = text(activity, 18, true);
        liveValueTv.setGravity(Gravity.CENTER);
        liveValueTv.setMinWidth(dp(activity, 108));
        liveValueTv.setPadding(dp(activity, 10), 0, dp(activity, 10), 0);
        liveStepperRow.addView(liveValueTv);

        rightArrowTv = text(activity, 14, true);
        rightArrowTv.setText(formatDeltaShort(activity, STEP_FINE_MS) + "  ▶");
        rightArrowTv.setPadding(dp(activity, 14), 0, dp(activity, 6), 0);
        rightArrowTv.setClickable(true);
        rightArrowTv.setOnClickListener(v -> adjustBy(STEP_FINE_MS, false));
        liveStepperRow.addView(rightArrowTv);

        liveStepperRow.setOnClickListener(v -> resetDelay());

        LinearLayout.LayoutParams stepperLp = new LinearLayout.LayoutParams(-2, -2);
        stepperLp.bottomMargin = dp(activity, 8);
        container.addView(liveStepperRow, stepperLp);

        // Row 2: Quick step buttons in physical Left-to-Right order: -0.5s · -0.1s · Current(Reset) · +0.1s · +0.5s
        LinearLayout quickRow = new LinearLayout(activity);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER);
        quickRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        quickRow.setClipChildren(false);
        quickRow.setClipToPadding(false);

        final int[] deltas = {-STEP_COARSE_MS, -STEP_FINE_MS, 0, STEP_FINE_MS, STEP_COARSE_MS};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            final int delta = deltas[i];
            TextView btn = text(activity, 13, true);
            btn.setGravity(Gravity.CENTER);
            btn.setSingleLine(true);
            btn.setFocusable(true);
            btn.setClickable(true);
            btn.setMinWidth(dp(activity, idx == 2 ? 128 : 78));
            btn.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7));
            if (delta != 0) {
                btn.setText(formatDeltaShort(activity, delta));
            }
            btn.setOnClickListener(v -> {
                stepperFocused = false;
                focusedQuickIndex = idx;
                updateVisualFocus();
                if (delta == 0) resetDelay();
                else adjustBy(delta, delta < 0);
            });
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            if (idx > 0) bLp.leftMargin = dp(activity, 8);
            quickRow.addView(btn, bLp);
            quickButtons[i] = btn;
        }
        container.addView(quickRow, new LinearLayout.LayoutParams(-2, -2));

        attachToHost();
        updateValueTexts();
        updateVisualFocus();
    }

    private void attachToHost() {
        if (hostRoot == null) return;
        int topMargin = dp(activity, 20);
        if (hostRoot instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = topMargin;
            hostRoot.addView(container, lp);
        } else if (hostRoot instanceof RelativeLayout) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            lp.topMargin = topMargin;
            hostRoot.addView(container, lp);
        } else {
            hostRoot.addView(container, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    public void show(long initialDelayMs, Listener listener) {
        this.listener = listener;
        this.currentDelayMs = clamp(initialDelayMs);
        this.showing = true;
        this.swallowNextBackUp = false;
        this.stepperFocused = true;
        this.focusedQuickIndex = 2;
        updateValueTexts();
        updateVisualFocus();
        container.setVisibility(View.VISIBLE);
        container.bringToFront();
        container.setAlpha(0f);
        container.animate().alpha(1f).setDuration(140).start();
        liveStepperRow.post(liveStepperRow::requestFocus);
    }

    public void dismiss() {
        if (!showing) return;
        showing = false;
        container.animate().cancel();
        container.setVisibility(View.GONE);
        Listener cb = listener;
        long finalDelay = currentDelayMs;
        if (cb != null) cb.onDismissed(finalDelay);
    }

    public boolean isShowing() {
        return showing;
    }

    public long getCurrentDelayMs() {
        return currentDelayMs;
    }

    /**
     * Call from Activity.dispatchKeyEvent() before player OSD / seek / back handlers.
     * Returns true when the event is consumed by the Live Timing Overlay Bar.
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        int action = event.getAction();

        // Swallow matching ACTION_UP right after Back closed the bar so playback never exits.
        if (!showing) {
            if (swallowNextBackUp && isBackKey(code)) {
                if (action == KeyEvent.ACTION_UP) swallowNextBackUp = false;
                return true;
            }
            return false;
        }

        // Let volume and dedicated hardware media keys pass through untouched.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            return false;
        }

        if (isBackKey(code) || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_CAPTIONS) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                swallowNextBackUp = true;
                dismiss();
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (action == KeyEvent.ACTION_DOWN) {
                boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT;
                if (stepperFocused) {
                    adjustBy(left ? -STEP_FINE_MS : STEP_FINE_MS, left);
                } else {
                    focusedQuickIndex = Math.max(0, Math.min(quickButtons.length - 1,
                            focusedQuickIndex + (left ? -1 : 1)));
                    updateVisualFocus();
                }
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (action == KeyEvent.ACTION_DOWN && stepperFocused) {
                stepperFocused = false;
                focusedQuickIndex = 2;
                updateVisualFocus();
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (action == KeyEvent.ACTION_DOWN && !stepperFocused) {
                stepperFocused = true;
                updateVisualFocus();
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (stepperFocused) {
                    resetDelay();
                } else {
                    quickButtons[focusedQuickIndex].performClick();
                }
            }
            return true;
        }

        return false;
    }

    private static boolean isBackKey(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B;
    }

    private void adjustBy(int deltaMs, boolean pulseLeft) {
        long next = clamp(currentDelayMs + deltaMs);
        currentDelayMs = next;
        updateValueTexts();
        pulseArrow(pulseLeft ? leftArrowTv : rightArrowTv);
        if (listener != null) listener.onDelayChanged(currentDelayMs);
    }

    private void resetDelay() {
        currentDelayMs = 0L;
        updateValueTexts();
        if (listener != null) listener.onDelayChanged(0L);
    }

    private void pulseArrow(TextView arrow) {
        if (arrow == null) return;
        arrow.animate().cancel();
        arrow.setScaleX(1.16f);
        arrow.setScaleY(1.16f);
        arrow.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
    }

    private void updateValueTexts() {
        String formatted = formatDelaySeconds(activity, currentDelayMs);
        liveValueTv.setText(formatted);
        if (currentDelayMs == 0L) {
            quickButtons[2].setText(formatted);
        } else {
            quickButtons[2].setText(activity.getString(R.string.cinema_sub_timing_reset_label, formatted));
        }
    }

    private void updateVisualFocus() {
        int rad = dp(activity, 10);
        if (stepperFocused) {
            liveStepperRow.setBackground(rounded(0xFFE5A93B, rad, 0xFFFFE08A));
            liveValueTv.setTextColor(0xFF141517);
            leftArrowTv.setTextColor(0xFF141517);
            rightArrowTv.setTextColor(0xFF141517);
            liveStepperRow.requestFocus();
        } else {
            liveStepperRow.setBackground(rounded(0xB3222329, rad, 0x886A5B36));
            liveValueTv.setTextColor(0xFFE5A93B);
            leftArrowTv.setTextColor(0xFFD8D8DF);
            rightArrowTv.setTextColor(0xFFD8D8DF);
        }

        for (int i = 0; i < quickButtons.length; i++) {
            TextView btn = quickButtons[i];
            boolean focused = !stepperFocused && focusedQuickIndex == i;
            if (focused) {
                btn.setBackground(rounded(0xFFE5A93B, dp(activity, 9), 0xFFFFE08A));
                btn.setTextColor(0xFF141517);
                btn.requestFocus();
            } else {
                boolean isReset = (i == 2);
                btn.setBackground(rounded(isReset ? 0xCC26241E : 0xCC22232A, dp(activity, 9),
                        isReset ? 0x99E5A93B : 0x66524B3A));
                btn.setTextColor(isReset ? 0xFFE5A93B : Color.WHITE);
            }
        }
    }

    private static GradientDrawable panelSurface(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xCC171717);
        d.setCornerRadius(dp(c, 16));
        d.setStroke(dp(c, 1.5f), 0xCCE5A93B);
        return d;
    }

    private static long clamp(long ms) {
        return Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, ms));
    }

    /** Formats a subtitle timing offset cleanly with explicit LRE/PDF BiDi isolation: e.g. "0.0 ث", "+0.3 ث", "-0.5s". */
    public static String formatDelaySeconds(Context context, long delayMs) {
        double sec = delayMs / 1000.0;
        String sign = delayMs > 0 ? "+" : (delayMs < 0 ? "-" : "");
        String abs = String.format(Locale.US, "%.1f", Math.abs(sec));
        if (abs.equals("0.0")) sign = "";
        String isolated = "\u202A" + sign + abs + "\u202C";
        return context.getString(R.string.cinema_sub_timing_sec_unit, isolated);
    }

    private static String formatDeltaShort(Context context, int deltaMs) {
        return formatDelaySeconds(context, deltaMs);
    }

    /** Formats the top menu shortcut title including the active timing offset. */
    public static String formatShortcutTitle(Context context, long delayMs) {
        return context.getString(R.string.cinema_sub_timing_shortcut_value,
                formatDelaySeconds(context, delayMs));
    }

    /** Compact title for the native player TVMenu card so it never wraps or overflows. */
    public static String formatCompactShortcutTitle(Context context, long delayMs) {
        return context.getString(R.string.cinema_sub_timing_shortcut_compact,
                formatDelaySeconds(context, delayMs));
    }

    /** Formats an audio or timing delay value in localized ms / م.ث with explicit LRE/PDF BiDi isolation. */
    public static String formatAudioDelayMs(Context context, long delayMs) {
        String sign = delayMs > 0 ? "+" : (delayMs < 0 ? "-" : "");
        String isolated = "\u202A" + sign + Math.abs(delayMs) + "\u202C";
        return context.getString(R.string.cinema_audio_delay_ms_unit, isolated);
    }
}
