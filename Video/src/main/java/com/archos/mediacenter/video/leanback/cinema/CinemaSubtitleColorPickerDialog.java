package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.archos.mediacenter.video.R;
import com.meedx.providers.UnifiedSubtitleConfig;

import java.util.function.IntConsumer;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dialog;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

/**
 * Isolated D-pad color picker dialogs for Subtitle Text Color (9 colors) and Outline/Stroke Color (5 colors).
 * Closes immediately upon selection and restores focus to the launching control button.
 */
public final class CinemaSubtitleColorPickerDialog {

    private CinemaSubtitleColorPickerDialog() {}

    /** Opens the compact 9-color picker for primary subtitle text color. */
    public static AlertDialog showTextColorPicker(
            Activity activity,
            int currentColor,
            IntConsumer onColorPicked,
            View returnFocusView) {
        boolean ar = arabic(activity);
        int[] colors = UnifiedSubtitleConfig.TEXT_COLORS;
        String[] labels = new String[colors.length];
        int activeIdx = 0;
        for (int i = 0; i < colors.length; i++) {
            labels[i] = UnifiedSubtitleConfig.textColorLabel(colors[i], ar);
            if (UnifiedSubtitleConfig.isSameSubtitleColor(currentColor, colors[i])) {
                activeIdx = i;
            }
        }
        return showGridPicker(
                activity,
                label(activity, "اختر لون نص الترجمة", "Choose Subtitle Text Color"),
                colors,
                labels,
                activeIdx,
                3,
                onColorPicked,
                returnFocusView);
    }

    /** Opens the compact 5-color picker for subtitle outline / stroke color. */
    public static AlertDialog showOutlineColorPicker(
            Activity activity,
            int currentOutlineColor,
            IntConsumer onColorPicked,
            View returnFocusView) {
        boolean ar = arabic(activity);
        int[] colors = UnifiedSubtitleConfig.OUTLINE_COLORS;
        String[] labels = new String[colors.length];
        int activeIdx = 0;
        for (int i = 0; i < colors.length; i++) {
            labels[i] = UnifiedSubtitleConfig.outlineColorLabel(colors[i], ar);
            if (UnifiedSubtitleConfig.isSameOutlineColor(currentOutlineColor, colors[i])) {
                activeIdx = i;
            }
        }
        return showGridPicker(
                activity,
                label(activity, "اختر لون إطار الترجمة", "Choose Outline Color"),
                colors,
                labels,
                activeIdx,
                3,
                onColorPicked,
                returnFocusView);
    }

    private static AlertDialog showGridPicker(
            Activity a,
            String title,
            int[] colors,
            String[] names,
            int activeIndex,
            int columns,
            IntConsumer onPicked,
            View returnFocusView) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return null;
        boolean ar = arabic(a);

        ScrollView scroll = new ScrollView(a);
        scroll.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(a, 18), dp(a, 12), dp(a, 18), dp(a, 14));
        scroll.addView(grid);

        final AlertDialog[] dlgRef = {null};
        final View[] initialFocus = {null};

        LinearLayout currentRow = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(a);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setClipChildren(false);
                currentRow.setClipToPadding(false);
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
                rLp.bottomMargin = dp(a, 10);
                grid.addView(currentRow, rLp);
            }
            final int pickedColor = colors[i];
            final boolean isSelected = (i == activeIndex);
            View cell = buildColorCell(a, pickedColor, names[i], isSelected, () -> {
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                if (onPicked != null) onPicked.accept(pickedColor);
            });
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1f);
            if ((i % columns) < columns - 1) {
                cLp.setMarginEnd(dp(a, 10));
            }
            currentRow.addView(cell, cLp);
            if (isSelected || initialFocus[0] == null) {
                initialFocus[0] = cell;
            }
        }
        // Pad incomplete trailing row with invisible spacers so cells keep uniform width
        int remainder = colors.length % columns;
        if (remainder != 0 && currentRow != null) {
            for (int pad = remainder; pad < columns; pad++) {
                View spacer = new View(a);
                spacer.setVisibility(View.INVISIBLE);
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(a, 1), 1f);
                if (pad < columns - 1) sLp.setMarginEnd(dp(a, 10));
                currentRow.addView(spacer, sLp);
            }
        }

        AlertDialog dlg = dialog(a)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(R.string.addon_back, null)
                .create();
        dlgRef[0] = dlg;
        dlg.setOnShowListener(d -> {
            if (dlg.getWindow() != null) {
                int w = Math.min(dp(a, 620),
                        Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.58f));
                dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (initialFocus[0] != null) initialFocus[0].requestFocus();
        });
        dlg.setOnDismissListener(d -> {
            if (returnFocusView != null && returnFocusView.isShown()) {
                returnFocusView.post(returnFocusView::requestFocus);
            }
        });
        dlg.show();
        return dlg;
    }

    private static View buildColorCell(
            Activity a,
            int swatchColor,
            String name,
            boolean selected,
            Runnable onClick) {
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));

        View dot = new View(a);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(swatchColor);
        d.setStroke(dp(a, 1.5f), swatchColor == Color.BLACK ? 0xaaFFFFFF : 0x99000000);
        dot.setBackground(d);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18));
        dotLp.setMarginEnd(dp(a, 10));
        cell.addView(dot, dotLp);

        TextView labelTv = text(a, 14, true);
        labelTv.setText(selected ? (name + " ✓") : name);
        labelTv.setSingleLine(true);
        cell.addView(labelTv, new LinearLayout.LayoutParams(0, -2, 1f));

        Runnable styleRest = () -> {
            cell.setBackground(rounded(selected ? 0xff292418 : 0xff202127, dp(a, 10),
                    selected ? CinemaTokens.GOLD : 0xff3b372c));
            labelTv.setTextColor(selected ? 0xfff8e2a6 : CinemaTokens.TEXT);
        };
        styleRest.run();

        cell.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                cell.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
                labelTv.setTextColor(0xff141517);
            } else {
                styleRest.run();
            }
        });
        cell.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return cell;
    }

    /** Creates a small circular color swatch drawable for inline picker buttons. */
    public static GradientDrawable createColorDot(Activity a, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(a, 1.2f), (color | 0xff000000) == Color.BLACK ? 0xbbFFFFFF : 0x88000000);
        return d;
    }
}
