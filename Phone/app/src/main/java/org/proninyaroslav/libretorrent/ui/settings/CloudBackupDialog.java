package org.proninyaroslav.libretorrent.ui.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import org.proninyaroslav.libretorrent.R;

import java.util.List;

final class CloudBackupDialog {
    private static final int GOLD = 0xffe5a93b;
    static final int RED = 0xffef5350;
    private static final int TEXT = 0xfff2ede7;
    static final int MUTED = 0xffbdb7ae;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    record Action(int title, int description, int icon, int color, Runnable run) {
        Action(int title, int description, int icon, Runnable run) {
            this(title, description, icon, GOLD, run);
        }
    }

    private final Context context;
    private final Dialog dialog;
    private final LinearLayout panel;

    private CloudBackupDialog(Context context, int title, CharSequence subtitle, int icon, int accent) {
        this.context = context;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(title);
        var scroll = new ScrollView(context);
        scroll.setClipToOutline(true);
        scroll.setBackground(surface(0xff171717, 0xff2a2a2a, 24));
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPaddingRelative(dp(16), dp(18), dp(16), dp(16));
        scroll.addView(panel, new ScrollView.LayoutParams(MATCH, WRAP));
        dialog.setContentView(scroll);

        LinearLayout header = line();
        header.addView(iconBadge(icon, accent), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = labels(header);
        TextView heading = text(context.getText(title), 20, TEXT);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        ViewCompat.setAccessibilityHeading(heading, true);
        labels.addView(heading);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView detail = text(subtitle, 13, MUTED);
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(detail);
        }
        panel.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    static void showOptions(Context context, CharSequence account, List<Action> actions) {
        var modal = new CloudBackupDialog(context, R.string.cinema_backup_options, account,
                R.drawable.ic_backup_cloud, GOLD);
        for (Action action : actions) modal.addAction(action);
        modal.addButton(android.R.string.cancel, TEXT, 0xff222222, 0xff333333, null);
        modal.show();
    }

    static void confirmDelete(Context context, Runnable confirmed) {
        confirm(context, R.string.cinema_delete_cloud_backup, R.string.cinema_delete_cloud_confirm,
                R.drawable.ic_delete_24px, RED, confirmed);
    }

    static void confirmSignOut(Context context, Runnable confirmed) {
        confirm(context, R.string.cinema_backup_sign_out, R.string.cinema_backup_sign_out_confirm,
                R.drawable.ic_backup_sign_out, GOLD, confirmed);
    }

    private static void confirm(Context context, int title, int message, int icon, int accent, Runnable confirmed) {
        var modal = new CloudBackupDialog(context, title, null, icon, accent);
        TextView detail = modal.text(context.getText(message), 15, TEXT);
        detail.setLineSpacing(modal.dp(3), 1f);
        modal.addWithTopMargin(detail, 16);
        modal.addButton(title, accent, 0xff222222, accent == RED ? 0xff643736 : 0xff6a512c, confirmed);
        modal.addButton(android.R.string.cancel, TEXT, 0xff222222, 0xff333333, null);
        modal.show();
    }

    private void addAction(Action action) {
        LinearLayout row = line();
        row.setPaddingRelative(dp(10), dp(8), dp(10), dp(8));
        row.setMinimumHeight(dp(60));
        boolean destructive = action.color() == RED;
        row.setBackground(clickableSurface(0xff1f1f1f, destructive ? 0xff643736 : 0xff2e2e2e, 14));
        row.addView(iconBadge(action.icon(), action.color()), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout labels = labels(row);
        TextView title = text(context.getText(action.title()), 15, destructive ? RED : TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(title);
        TextView description = text(context.getText(action.description()), 12, destructive ? RED : MUTED);
        description.setSingleLine(true);
        description.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(description);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setContentDescription(context.getString(action.title()) + ". " + context.getString(action.description()));
        row.setFocusable(true);
        row.setOnClickListener(view -> {
            dialog.dismiss();
            action.run().run();
        });
        addWithTopMargin(row, 7);
    }

    private void addButton(int label, int textColor, int fill, int border, Runnable action) {
        var button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(textColor);
        button.setMinHeight(dp(48));
        button.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
        button.setBackgroundTintList(null);
        button.setBackground(clickableSurface(fill, border, 24));
        button.setOnClickListener(view -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        addWithTopMargin(button, 10);
    }

    private void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            int width = context.getResources().getConfiguration().screenWidthDp;
            window.setLayout(dp(Math.min(450, Math.max(1, width - 32))), WRAP);
        }
    }

    private LinearLayout line() {
        var row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout labels(LinearLayout row) {
        var labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        var size = new LinearLayout.LayoutParams(0, WRAP, 1f);
        size.setMarginStart(dp(12));
        row.addView(labels, size);
        return labels;
    }

    private ImageView iconBadge(int icon, int color) {
        var view = new ImageView(context);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(color));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setBackground(surface(0x18000000 | (color & 0x00ffffff), 0, 11));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private TextView text(CharSequence text, int size, int color) {
        var label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.START);
        return label;
    }

    private void addWithTopMargin(View child, int top) {
        var size = new LinearLayout.LayoutParams(MATCH, WRAP);
        size.topMargin = dp(top);
        panel.addView(child, size);
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        var background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private RippleDrawable clickableSurface(int fill, int stroke, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x2affffff), surface(fill, stroke, radius), null);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
