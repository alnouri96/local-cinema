package com.archos.mediacenter.video.leanback.cinema;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.archos.mediacenter.video.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified `#171717` / `#E5A93B` D-pad modal menu for poster long-press options and row `Sort By`.
 * Uses crisp vector icons only (zero emojis) and relies strictly on the Remote Back key to close
 * (zero `Cancel` button).
 */
public final class CinemaContextDialog {
    private static final int CANVAS_BG = 0xFF171717;
    private static final int CARD_IDLE_BG = 0xFF1E1E22;
    private static final int CARD_ACTIVE_BG = 0xFF242017;
    private static final int CARD_BORDER = 0xFF2E2E34;
    private static final int CARD_ACTIVE_BORDER = 0x88E5A93B;
    private static final int GOLD = 0xFFE5A93B;
    private static final int ON_GOLD = 0xFF171717;
    private static final int SUB_IDLE = 0xFFA6A6B2;
    private static final int SUB_ON_GOLD = 0xFF2E2410;

    public record Option(
            int iconRes,
            String title,
            String subtitle,
            boolean active,
            Runnable action) {
        public Option(int iconRes, String title, Runnable action) {
            this(iconRes, title, null, false, action);
        }

        public Option(int iconRes, String title, String subtitle, Runnable action) {
            this(iconRes, title, subtitle, false, action);
        }
    }

    private CinemaContextDialog() {}

    public static AlertDialog show(
            Context context,
            int headerIconRes,
            String title,
            String subtitle,
            List<Option> options) {
        if (context == null || options == null || options.isEmpty()) return null;
        boolean rtl = arabic(context);

        FrameLayout dialogHost = new FrameLayout(context);
        dialogHost.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        dialogHost.setClipChildren(false);
        dialogHost.setClipToPadding(false);
        int outerPad = dp(context, 12);
        dialogHost.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        shell.setBackground(rounded(CANVAS_BG, dp(context, 18), CARD_BORDER));
        shell.setClipChildren(false);
        shell.setClipToPadding(false);
        dialogHost.addView(shell, new FrameLayout.LayoutParams(-1, -2));

        // Header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 14));
        shell.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (headerIconRes != 0) {
            FrameLayout badge = new FrameLayout(context);
            badge.setBackground(rounded(0xFF222227, dp(context, 10), 0xFF3A3322));
            ImageView hIcon = new ImageView(context);
            hIcon.setImageResource(headerIconRes);
            hIcon.setImageTintList(ColorStateList.valueOf(GOLD));
            int ip = dp(context, 8);
            hIcon.setPadding(ip, ip, ip, ip);
            badge.addView(hIcon, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(context, 38), dp(context, 38));
            badgeLp.setMarginEnd(dp(context, 14));
            header.addView(badge, badgeLp);
        }

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView titleTv = text(context, 18, true);
        titleTv.setText(title == null ? "" : title);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        titleCol.addView(titleTv, new LinearLayout.LayoutParams(-1, -2));

        if (subtitle != null && !subtitle.isBlank()) {
            TextView subTv = text(context, 12, false);
            subTv.setText(subtitle);
            subTv.setTextColor(GOLD);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
            sLp.topMargin = dp(context, 2);
            titleCol.addView(subTv, sLp);
        }

        View divider = new View(context);
        divider.setBackgroundColor(0xFF26262C);
        shell.addView(divider, new LinearLayout.LayoutParams(-1, dp(context, 1)));

        // Clipped viewport wrapping an unclipped focus-padded container (prevents focus ring clipping)
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setFillViewport(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        int focusPad = dp(context, 14);
        list.setPadding(focusPad, dp(context, 12), focusPad, dp(context, 14));
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));

        AlertDialog dialog = CinemaUi.dialog(context).setView(dialogHost).create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        List<LinearLayout> focusRows = new ArrayList<>(options.size());
        int preferredFocusIndex = 0;

        for (int i = 0; i < options.size(); i++) {
            Option opt = options.get(i);
            if (opt == null) continue;
            if (opt.active() && preferredFocusIndex == 0) {
                preferredFocusIndex = focusRows.size();
            }
            LinearLayout row = buildOptionRow(context, opt, scroll, focusPad, dialog, i == options.size() - 1);
            focusRows.add(row);
            list.addView(row);
        }

        for (int i = 0; i < focusRows.size(); i++) {
            LinearLayout cur = focusRows.get(i);
            LinearLayout prev = i > 0 ? focusRows.get(i - 1) : cur;
            LinearLayout next = i < focusRows.size() - 1 ? focusRows.get(i + 1) : cur;
            cur.setNextFocusUpId(prev.getId());
            cur.setNextFocusDownId(next.getId());
            cur.setNextFocusLeftId(cur.getId());
            cur.setNextFocusRightId(cur.getId());
        }

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            int screenH = context.getResources().getDisplayMetrics().heightPixels;
            int targetW = Math.min(dp(context, 520), Math.round(screenW * 0.62f));
            window.setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);

            int maxScrollH = Math.round(screenH * 0.68f);
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (scroll.getHeight() <= 0) return;
                    scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (scroll.getHeight() > maxScrollH) {
                        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                        lp.height = maxScrollH;
                        scroll.setLayoutParams(lp);
                    }
                }
            });
        }

        if (!focusRows.isEmpty()) {
            LinearLayout initial = focusRows.get(Math.max(0, Math.min(preferredFocusIndex, focusRows.size() - 1)));
            initial.requestFocus();
            initial.post(() -> {
                if (dialog.isShowing() && initial.isAttachedToWindow()) {
                    initial.requestFocus();
                }
            });
        }
        return dialog;
    }

    private static LinearLayout buildOptionRow(
            Context ctx,
            Option opt,
            ScrollView scroll,
            int focusPad,
            AlertDialog dialog,
            boolean isLast) {
        LinearLayout row = new LinearLayout(ctx);
        row.setId(View.generateViewId());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11));
        int idleBg = opt.active() ? CARD_ACTIVE_BG : CARD_IDLE_BG;
        int idleBorder = opt.active() ? CARD_ACTIVE_BORDER : CARD_BORDER;
        row.setBackground(rounded(idleBg, dp(ctx, 12), idleBorder));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        if (!isLast) rowLp.bottomMargin = dp(ctx, 8);
        row.setLayoutParams(rowLp);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(opt.iconRes() != 0 ? opt.iconRes() : R.drawable.cinema_more);
        icon.setImageTintList(ColorStateList.valueOf(GOLD));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22));
        iconLp.setMarginEnd(dp(ctx, 14));
        row.addView(icon, iconLp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView titleTv = text(ctx, 15, true);
        titleTv.setText(opt.title() == null ? "" : opt.title());
        titleTv.setTextColor(opt.active() ? GOLD : Color.WHITE);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        col.addView(titleTv, new LinearLayout.LayoutParams(-1, -2));

        TextView subTv = null;
        if (opt.subtitle() != null && !opt.subtitle().isBlank()) {
            subTv = text(ctx, 12, false);
            subTv.setText(opt.subtitle());
            subTv.setTextColor(SUB_IDLE);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = dp(ctx, 2);
            col.addView(subTv, subLp);
        }
        final TextView finalSub = subTv;

        FrameLayout activeBadge = null;
        ImageView checkIcon = null;
        TextView activeTag = null;
        if (opt.active()) {
            LinearLayout pill = new LinearLayout(ctx);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 9), dp(ctx, 3));
            pill.setBackground(rounded(0x33E5A93B, dp(ctx, 8), GOLD));

            checkIcon = new ImageView(ctx);
            checkIcon.setImageResource(R.drawable.cinema_check);
            checkIcon.setImageTintList(ColorStateList.valueOf(GOLD));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(ctx, 13), dp(ctx, 13));
            cLp.setMarginEnd(dp(ctx, 4));
            pill.addView(checkIcon, cLp);

            activeTag = text(ctx, 11, true);
            activeTag.setText(label(ctx, "نشط", "Active"));
            activeTag.setTextColor(GOLD);
            pill.addView(activeTag);

            activeBadge = new FrameLayout(ctx);
            activeBadge.addView(pill);
            LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(-2, -2);
            abLp.setMarginStart(dp(ctx, 10));
            row.addView(activeBadge, abLp);
        }
        final FrameLayout finalActiveBadge = activeBadge;
        final ImageView finalCheckIcon = checkIcon;
        final TextView finalActiveTag = activeTag;

        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                var bg = rounded(GOLD, dp(ctx, 12), 0xFFFFF3CF);
                bg.setStroke(dp(ctx, 2), 0xFFFFF3CF);
                row.setBackground(bg);
                icon.setImageTintList(ColorStateList.valueOf(ON_GOLD));
                titleTv.setTextColor(ON_GOLD);
                if (finalSub != null) finalSub.setTextColor(SUB_ON_GOLD);
                if (finalActiveBadge != null && finalActiveBadge.getChildAt(0) != null) {
                    finalActiveBadge.getChildAt(0).setBackground(rounded(ON_GOLD, dp(ctx, 8), ON_GOLD));
                    if (finalCheckIcon != null) finalCheckIcon.setImageTintList(ColorStateList.valueOf(GOLD));
                    if (finalActiveTag != null) finalActiveTag.setTextColor(GOLD);
                }
                row.animate().scaleX(1.018f).scaleY(1.018f).setDuration(110).start();
                scroll.post(() -> {
                    Rect rect = new Rect(0, -focusPad, row.getWidth(), row.getHeight() + focusPad);
                    scroll.requestChildRectangleOnScreen(row, rect, true);
                });
            } else {
                row.setBackground(rounded(idleBg, dp(ctx, 12), idleBorder));
                icon.setImageTintList(ColorStateList.valueOf(GOLD));
                titleTv.setTextColor(opt.active() ? GOLD : Color.WHITE);
                if (finalSub != null) finalSub.setTextColor(SUB_IDLE);
                if (finalActiveBadge != null && finalActiveBadge.getChildAt(0) != null) {
                    finalActiveBadge.getChildAt(0).setBackground(rounded(0x33E5A93B, dp(ctx, 8), GOLD));
                    if (finalCheckIcon != null) finalCheckIcon.setImageTintList(ColorStateList.valueOf(GOLD));
                    if (finalActiveTag != null) finalActiveTag.setTextColor(GOLD);
                }
                row.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            }
        });

        row.setOnClickListener(v -> {
            dialog.dismiss();
            if (opt.action() != null) opt.action().run();
        });
        return row;
    }
}
