package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;

/**
 * Plex-style action button: an idle translucent circle that shows only an icon, and grows into a
 * pill showing the action name while focused. The name lives in the content description.
 */
public final class CinemaActionChip {
    public static final int SIZE = 44, ICON = 18;

    private CinemaActionChip() {}

    public static TextView create(Context c, LinearLayout parent, CharSequence name, int icon, Runnable action) {
        TextView t = CinemaUi.text(c, 13, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(null);
        t.setFocusable(true);
        t.setClickable(true);
        applyStyle(t, false);
        t.setMinWidth(dp(c, SIZE));
        t.setMinHeight(dp(c, SIZE));
        t.setContentDescription(name);
        t.setTooltipText(name);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, SIZE));
        p.setMarginEnd(dp(c, 10));
        parent.addView(t, p);
        t.setOnClickListener(v -> action.run());
        setIcon(t, icon);
        t.setOnFocusChangeListener((v, focused) -> sync((TextView) v));
        sync(t);
        return t;
    }

    public static void setActive(TextView t, boolean active) {
        if (t == null) return;
        t.setForeground(null);
        applyStyle(t, active);
        t.setCompoundDrawableTintList(t.getTextColors());
        sync(t);
    }

    private static void applyStyle(TextView t, boolean active) {
        Context c = t.getContext();
        int r = dp(c, SIZE / 2f);
        StateListDrawable bg = new StateListDrawable();
        if (active) {
            GradientDrawable focusedBg = CinemaUi.rounded(CinemaTokens.FOCUS, r, 0);
            focusedBg.setStroke(dp(c, 2), 0xFFFFFFFF);
            bg.addState(new int[]{android.R.attr.state_pressed}, CinemaUi.rounded(CinemaTokens.PRESSED, r, 0));
            bg.addState(new int[]{android.R.attr.state_focused}, focusedBg);
            bg.addState(new int[]{}, CinemaUi.rounded(CinemaTokens.FOCUS, r, 0));
            t.setTextColor(ColorStateList.valueOf(CinemaTokens.ON_FOCUS));
        } else {
            bg.addState(new int[]{android.R.attr.state_pressed}, CinemaUi.rounded(CinemaTokens.PRESSED, r, 0));
            bg.addState(new int[]{android.R.attr.state_focused}, CinemaUi.rounded(CinemaTokens.FOCUS, r, 0));
            GradientDrawable idle = CinemaUi.rounded(0x1EFFFFFF, r, 0);
            idle.setStroke(1, 0x22FFFFFF);
            bg.addState(new int[]{}, idle);
            t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{CinemaTokens.ON_FOCUS, CinemaTokens.TEXT}));
        }
        t.setBackground(bg);
    }

    public static void setIcon(TextView t, int icon) {
        Context c = t.getContext();
        Drawable d = ContextCompat.getDrawable(c, icon).mutate();
        int s = dp(c, ICON);
        d.setBounds(0, 0, s, s);
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawableTintList(t.getTextColors());
    }

    public static void setLabel(TextView t, CharSequence name) {
        t.setContentDescription(name);
        t.setTooltipText(name);
        sync(t);
    }

    /** Label only while focused; idle padding keeps the shape a perfect circle. */
    public static void sync(TextView t) {
        Context c = t.getContext();
        int pad = (dp(c, SIZE) - dp(c, ICON)) / 2;
        if (t.hasFocus()) {
            CharSequence label = t.getContentDescription();
            t.setText(label == null ? "" : label);
            t.setCompoundDrawablePadding(dp(c, 9));
            t.setPadding(dp(c, 15), 0, dp(c, 18), 0);
        } else {
            t.setText("");
            t.setCompoundDrawablePadding(0);
            t.setPadding(pad, 0, pad, 0);
        }
    }
}
