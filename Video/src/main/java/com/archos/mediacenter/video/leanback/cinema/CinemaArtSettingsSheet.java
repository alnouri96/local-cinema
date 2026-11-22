package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Switch;

import com.archos.mediacenter.video.R;

final class CinemaArtSettingsSheet {
    static final int[] INTERVAL_SECONDS = {30, 60, 300, 900, 1800};
    static final int[] SLEEP_MINUTES = {0, 15, 30, 60, 120};
    static final String FRAME = "frame", TONE = "tone", BRIGHTNESS = "brightness", INTERVAL = "interval";
    static final String FRAME_SIZE = "frame_size";
    static final String SHIFT = "shift", SLEEP = "sleep", CLOCK = "clock";

    private static final int GOLD = 0xffe5a93b;
    private static final int ROW = 0xff252423;

    static void show(Activity activity, SharedPreferences settings, Runnable choose, Runnable update) {
        var rows = column(activity);
        int inset = CinemaUi.dp(activity, 12);
        rows.setPaddingRelative(inset, 0, inset, inset);
        var scroll = scrollable(activity, rows);
        var sheet = CinemaUi.dialog(activity).setTitle(R.string.cinema_art_settings).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).create();

        section(rows, R.string.cinema_art_appearance);
        var pick = new Button(activity);
        pick.setAllCaps(false);
        pick.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        pick.setText(R.string.cinema_art_choose_images);
        pick.setTextColor(Color.WHITE);
        decorate(pick);
        CinemaUi.icon(pick, R.drawable.cinema_folder);
        addRow(rows, pick);
        pick.setOnClickListener(v -> {
            sheet.dismiss();
            choose.run();
        });

        var appearanceChoices = column(activity);
        rows.addView(appearanceChoices);
        var size = slider(rows, settings, FRAME_SIZE, R.string.cinema_art_frame_size,
                CinemaArtFrameView.MIN_SIZE, CinemaArtFrameView.MAX_SIZE, CinemaArtFrameView.SIZE_STEP,
                CinemaArtFrameView.DEFAULT_SIZE, true, update);
        refreshSizeControl(size, settings);
        Runnable changeFrame = () -> {
            refreshSizeControl(size, settings);
            update.run();
        };
        addChoice(appearanceChoices, settings, FRAME, 0, R.string.cinema_art_frame, R.array.cinema_art_frames, changeFrame);
        addChoice(appearanceChoices, settings, TONE, 0, R.string.cinema_art_tone, R.array.cinema_art_tones, update);
        slider(rows, settings, BRIGHTNESS, R.string.cinema_art_brightness, 20, 100, 5, 85, false, update);

        section(rows, R.string.cinema_art_display);
        addChoice(rows, settings, INTERVAL, 1, R.string.cinema_art_interval,
                R.array.cinema_art_intervals, update);
        addChoice(rows, settings, SLEEP, 0, R.string.cinema_art_sleep,
                R.array.cinema_art_sleep_times, update);
        toggle(rows, settings, SHIFT, true, R.string.cinema_art_shift, update);
        toggle(rows, settings, CLOCK, false, R.string.cinema_art_clock, update);

        sheet.show();
        var window = sheet.getWindow();
        if (window != null) {
            var attributes = window.getAttributes();
            attributes.width = Math.min(CinemaUi.dp(activity, 410),
                    activity.getResources().getDisplayMetrics().widthPixels - 2 * inset);
            attributes.gravity = Gravity.CENTER_VERTICAL | (CinemaUi.arabic(activity) ? Gravity.LEFT : Gravity.RIGHT);
            attributes.x = inset;
            attributes.dimAmount = .20f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(attributes);
        }
        CinemaUi.focusDialogControl(sheet, pick);
    }

    private static LinearLayout column(android.content.Context context) {
        var rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setLayoutDirection(CinemaUi.arabic(context) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        return rows;
    }

    private static ScrollView scrollable(android.content.Context context, LinearLayout rows) {
        var scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private static void section(LinearLayout rows, int title) {
        var heading = CinemaUi.text(rows.getContext(), 14, true);
        heading.setText(title);
        heading.setTextColor(GOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setPaddingRelative(CinemaUi.dp(rows.getContext(), 12), CinemaUi.dp(rows.getContext(), 10),
                CinemaUi.dp(rows.getContext(), 12), CinemaUi.dp(rows.getContext(), 6));
        rows.addView(heading);
    }

    private static void addRow(LinearLayout rows, View view) {
        var size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.bottomMargin = CinemaUi.dp(rows.getContext(), 5);
        rows.addView(view, size);
    }

    private static void decorate(View control) {
        var context = control.getContext();
        int radius = CinemaUi.dp(context, 12);
        var states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, CinemaUi.rounded(0xff202020, radius, 0xff303030));
        GradientDrawable focus = CinemaUi.rounded(0xff342b1e, radius, 0);
        focus.setStroke(CinemaUi.dp(context, 2), GOLD);
        states.addState(new int[]{android.R.attr.state_focused}, focus);
        states.addState(new int[]{}, CinemaUi.rounded(ROW, radius, 0xff3a3631));
        control.setBackground(states);
        control.setMinimumHeight(CinemaUi.dp(context, 48));
        int gutter = CinemaUi.dp(context, 14);
        control.setPaddingRelative(gutter, 0, gutter, 0);
        control.setFocusable(true);
    }

    private static SeekBar slider(LinearLayout rows, SharedPreferences settings, String key, int label,
                                  int min, int max, int step, int fallback, boolean showDefault, Runnable update) {
        var context = rows.getContext();
        var group = column(context);
        group.setBackground(CinemaUi.rounded(ROW, CinemaUi.dp(context, 12), 0xff3a3631));
        var header = new LinearLayout(context);
        int gutter = CinemaUi.dp(context, 14);
        header.setPaddingRelative(gutter, CinemaUi.dp(context, 9), gutter, 0);
        var title = CinemaUi.text(context, 15, true);
        title.setText(label);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        var current = CinemaUi.text(context, 14, true);
        current.setTextColor(GOLD);
        header.addView(current);
        group.addView(header);

        var bar = new SeekBar(context);
        decorate(bar);
        bar.setMax((max - min) / step);
        bar.setKeyProgressIncrement(1);
        bar.setProgressTintList(ColorStateList.valueOf(GOLD));
        bar.setThumbTintList(ColorStateList.valueOf(GOLD));
        bar.setContentDescription(context.getString(label));
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        current.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        java.util.function.IntConsumer render = value -> {
            String percent = java.text.NumberFormat.getPercentInstance().format(value / 100d);
            String text = showDefault && value == fallback
                    ? context.getString(R.string.cinema_art_default_percent, percent) : percent;
            current.setText(text);
            if (android.os.Build.VERSION.SDK_INT >= 30) bar.setStateDescription(text);
        };
        bar.setProgress((Math.max(min, Math.min(max, settings.getInt(key, fallback))) - min) / step);
        render.accept(min + bar.getProgress() * step);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                int value = min + step * progress;
                render.accept(value);
                if (fromUser) {
                    settings.edit().putInt(key, value).apply();
                    update.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        group.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        addRow(rows, group);
        return bar;
    }

    private static void refreshSizeControl(SeekBar bar, SharedPreferences settings) {
        boolean enabled = settings.getInt(FRAME, 0) != CinemaArtFrameView.FULL_BLEED;
        bar.setEnabled(enabled);
        ((View) bar.getParent()).setAlpha(enabled ? 1f : .52f);
    }

    private static void addChoice(LinearLayout rows, SharedPreferences settings, String key, int fallback,
                                  int label, int values, Runnable update) {
        var context = rows.getContext();
        var control = new LinearLayout(context);
        control.setGravity(Gravity.CENTER_VERTICAL);
        decorate(control);
        var title = CinemaUi.text(context, 15, true);
        title.setText(label);
        control.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        var current = CinemaUi.text(context, 14, false);
        current.setTextColor(0xffded2bc);
        current.setGravity(Gravity.END);
        control.addView(current, new LinearLayout.LayoutParams(-2, -2));
        String[] choices = context.getResources().getStringArray(values);
        java.util.function.IntConsumer render = index -> {
            current.setText(choices[index]);
            control.setContentDescription(context.getString(R.string.cinema_art_option_value, title.getText(), choices[index]));
        };
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        current.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        render.accept(settings.getInt(key, fallback));
        control.setOnClickListener(v -> {
            var options = column(context);
            int inset = CinemaUi.dp(context, 12);
            options.setPaddingRelative(inset, 0, inset, inset);
            var menu = CinemaUi.dialog(context).setTitle(label).setView(scrollable(context, options))
                    .setNegativeButton(android.R.string.cancel, null).create();
            int selected = settings.getInt(key, fallback);
            for (int i = 0; i < choices.length; i++) {
                int index = i;
                var item = new RadioButton(context);
                item.setText(choices[index]);
                item.setTextColor(Color.WHITE);
                item.setButtonTintList(ColorStateList.valueOf(GOLD));
                item.setChecked(index == selected);
                decorate(item);
                item.setOnClickListener(view -> {
                    settings.edit().putInt(key, index).apply();
                    render.accept(index);
                    menu.dismiss();
                    update.run();
                });
                addRow(options, item);
            }
            menu.setOnDismissListener(dialog -> control.requestFocus());
            menu.show();
            CinemaUi.focusDialogControl(menu, options.getChildAt(selected));
        });
        addRow(rows, control);
    }

    static Button addButton(LinearLayout parent, CharSequence label) {
        var button = new Button(parent.getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPaddingRelative(CinemaUi.dp(parent.getContext(), 14), 0,
                CinemaUi.dp(parent.getContext(), 14), 0);
        CinemaUi.focusable(button);
        button.setOnFocusChangeListener((view, focused) -> button.setTextColor(
                focused ? CinemaTokens.ON_FOCUS : CinemaTokens.TEXT));
        button.setTextColor(CinemaTokens.TEXT);
        var size = new LinearLayout.LayoutParams(-1, CinemaUi.dp(parent.getContext(), 48));
        size.bottomMargin = CinemaUi.dp(parent.getContext(), 6);
        parent.addView(button, size);
        return button;
    }

    static void option(LinearLayout rows, SharedPreferences settings, String key, int fallback,
                       int label, int values, Runnable update) {
        var context = rows.getContext();
        String title = context.getString(label);
        String[] choices = context.getResources().getStringArray(values);
        var button = addButton(rows, title + " · " + choices[settings.getInt(key, fallback)]);
        button.setOnClickListener(v -> CinemaUi.dialog(context).setTitle(label)
                .setSingleChoiceItems(choices, settings.getInt(key, fallback), (dialog, index) -> {
                    settings.edit().putInt(key, index).apply();
                    button.setText(title + " · " + choices[index]);
                    dialog.dismiss();
                    update.run();
                }).setNegativeButton(android.R.string.cancel, null).show());
    }

    private static void toggle(LinearLayout rows, SharedPreferences settings, String key, boolean fallback,
                               int label, Runnable update) {
        var toggle = new Switch(rows.getContext());
        toggle.setText(label);
        toggle.setChecked(settings.getBoolean(key, fallback));
        toggle.setTextColor(Color.WHITE);
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{GOLD, 0xffa6a29a}));
        decorate(toggle);
        toggle.setOnCheckedChangeListener((v, on) -> {
            settings.edit().putBoolean(key, on).apply();
            update.run();
        });
        addRow(rows, toggle);
    }
}
