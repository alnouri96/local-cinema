package org.proninyaroslav.libretorrent.cinema_hub;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

/**
 * Full-text multi-line dark+gold Snackbar helper that replaces clipped system Toasts
 * across Phone activities, allowing up to 6 full lines without "..." truncation.
 */
public final class CinemaSnackBanner {
    private static final int BG = 0xff1a1a1f;
    private static final int GOLD = 0xffe5b94c;
    private static final int TEXT = 0xfff4f4f6;

    private CinemaSnackBanner() {}

    public static void show(Context context, String message) {
        show(context, message, null, null);
    }

    public static void show(Context context, String message, String actionLabel, Runnable onAction) {
        if (context == null || message == null || message.isBlank()) return;
        View anchor = null;
        if (context instanceof Activity act && !act.isFinishing() && !act.isDestroyed()) {
            anchor = act.findViewById(android.R.id.content);
        }
        if (anchor == null) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        showOnView(anchor, message, actionLabel, onAction);
    }

    public static void showOnView(View anchor, String message, String actionLabel, Runnable onAction) {
        if (anchor == null || message == null || message.isBlank()) return;
        Runnable work = () -> {
            try {
                int duration = (message.length() > 75 || onAction != null) ? 5500 : Snackbar.LENGTH_LONG;
                Snackbar sb = Snackbar.make(anchor, message, duration);
                View sbView = sb.getView();
                float d = anchor.getResources().getDisplayMetrics().density;
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(BG);
                bg.setStroke(Math.max(1, Math.round(d)), GOLD);
                bg.setCornerRadius(d * 14f);
                sbView.setBackground(bg);
                sbView.setElevation(d * 8f);
                TextView tv = sbView.findViewById(com.google.android.material.R.id.snackbar_text);
                if (tv != null) {
                    tv.setTextColor(TEXT);
                    tv.setSingleLine(false);
                    tv.setMaxLines(6);
                    tv.setEllipsize(null);
                    tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                }
                sb.setActionTextColor(GOLD);
                if (actionLabel != null && onAction != null) {
                    sb.setAction(actionLabel, v -> onAction.run());
                } else {
                    String dismissLabel = isArabic(anchor.getContext()) ? "إغلاق" : "Dismiss";
                    sb.setAction(dismissLabel, v -> sb.dismiss());
                }
                sb.show();
            } catch (Throwable ignored) {}
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            work.run();
        } else {
            anchor.post(work);
        }
    }

    private static boolean isArabic(Context c) {
        if (c == null) return false;
        var locale = androidx.core.os.ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
        return locale != null && "ar".equals(locale.getLanguage());
    }
}
