package org.proninyaroslav.libretorrent.cinema_hub;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.proninyaroslav.libretorrent.R;

/**
 * Companion controller for CinemaBrowser's "Browser settings" sheet/dialog:
 * owns behavior MaterialSwitch rows, Desktop Mode toggle sync, live Page Zoom
 * (50%-150%) slider with Reset (100%), and Clear Browsing Data confirmation.
 */
final class CinemaBrowserSettingsSheet {
    private final CinemaBrowser host;
    private AlertDialog activeDialog;

    CinemaBrowserSettingsSheet(CinemaBrowser host) {
        this.host = host;
    }

    boolean isShowing() {
        return activeDialog != null && activeDialog.isShowing();
    }

    void show() {
        String[] keys = {"popups", "redirects", "https", "desktop", "malware"};
        String[] labels = {
                host.context.getString(R.string.cinema_popup_ask),
                host.context.getString(R.string.cinema_block_cross_site_redirects),
                host.context.getString(R.string.cinema_https_only),
                host.context.getString(R.string.cinema_desktop_site),
                host.context.getString(R.string.browser_malware_protection)
        };
        var scroll = new android.widget.ScrollView(host.context);
        var root = new LinearLayout(host.context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(host.isArabic() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPadding(host.dp(20), host.dp(10), host.dp(20), host.dp(10));
        scroll.addView(root);

        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            final String key = keys[i];
            boolean curVal = idx == 3 ? host.isDesktopMode() : host.settings.getBoolean(key, true);
            root.addView(buildSwitchRow(labels[i], curVal, isChecked -> {
                if (idx == 3) {
                    host.setDesktopMode(isChecked);
                } else {
                    host.settings.edit().putBoolean(key, isChecked).apply();
                    host.setHttpsMode();
                }
            }));
        }

        var divider = new View(host.context);
        divider.setBackgroundColor(0xff303035);
        var divLp = new LinearLayout.LayoutParams(-1, host.dp(1));
        divLp.topMargin = host.dp(8);
        divLp.bottomMargin = host.dp(12);
        root.addView(divider, divLp);

        addZoomControls(root);

        var dlg = new AlertDialog.Builder(host.context)
                .setTitle(host.context.getString(R.string.cinema_browser_settings))
                .setView(scroll)
                .setNeutralButton(host.context.getString(R.string.cinema_clear_browsing_data),
                        (dialog, which) -> confirmClear())
                .setPositiveButton(android.R.string.ok, null)
                .create();
        activeDialog = dlg;
        dlg.setOnDismissListener(d -> {
            if (activeDialog == dlg) activeDialog = null;
            host.resumeBrowserRendering();
        });
        dlg.show();
        CinemaBrowser.styleDarkDialog(dlg);
    }

    private LinearLayout buildSwitchRow(String label, boolean initialValue,
                                        java.util.function.Consumer<Boolean> onToggle) {
        var row = new LinearLayout(host.context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(4), host.dp(8), host.dp(4), host.dp(8));

        var lbl = new TextView(host.context);
        lbl.setText(label);
        lbl.setTextSize(14);
        lbl.setTextColor(0xfffafafa);
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1));

        var sw = new MaterialSwitch(host.context);
        sw.setChecked(initialValue);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> onToggle.accept(isChecked));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private void addZoomControls(LinearLayout root) {
        int currentZoom = host.getBrowserZoomPercent();
        var zoomHeader = new LinearLayout(host.context);
        zoomHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);

        var zoomTitle = new TextView(host.context);
        zoomTitle.setText(host.l("حجم / تكبير الصفحة", "Page Zoom / Scale"));
        zoomTitle.setTextSize(14);
        zoomTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        zoomTitle.setTextColor(0xfffafafa);
        zoomHeader.addView(zoomTitle, new LinearLayout.LayoutParams(0, -2, 1));

        var zoomBadge = new TextView(host.context);
        zoomBadge.setText(currentZoom + "%");
        zoomBadge.setTextSize(13);
        zoomBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        zoomBadge.setTextColor(0xffe5b84b);
        zoomBadge.setPadding(host.dp(8), 0, host.dp(8), 0);
        zoomHeader.addView(zoomBadge);

        var resetBtn = new MaterialButton(host.context);
        resetBtn.setText(host.l("إعادة (100%)", "Reset (100%)"));
        resetBtn.setAllCaps(false);
        resetBtn.setTextSize(12);
        resetBtn.setInsetTop(0);
        resetBtn.setInsetBottom(0);
        resetBtn.setCornerRadius(host.dp(10));
        resetBtn.setStrokeWidth(host.dp(1));
        resetBtn.setStrokeColor(android.content.res.ColorStateList.valueOf(0x44e5b84b));
        resetBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff25252a));
        resetBtn.setTextColor(0xffe5b84b);
        zoomHeader.addView(resetBtn, new LinearLayout.LayoutParams(-2, host.dp(34)));
        root.addView(zoomHeader);

        var slider = new SeekBar(host.context);
        slider.setMax(20); // 50% .. 150% in 5% steps
        slider.setProgress((currentZoom - 50) / 5);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        var sliderLp = new LinearLayout.LayoutParams(-1, -2);
        sliderLp.topMargin = host.dp(8);
        sliderLp.bottomMargin = host.dp(4);
        root.addView(slider, sliderLp);

        var scaleTicks = new LinearLayout(host.context);
        scaleTicks.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (String t : new String[]{"50%", "100%", "150%"}) {
            var tv = new TextView(host.context);
            tv.setText(t);
            tv.setTextSize(11);
            tv.setTextColor(0xffa1a1aa);
            tv.setGravity("100%".equals(t) ? android.view.Gravity.CENTER
                    : ("150%".equals(t) ? android.view.Gravity.END : android.view.Gravity.START));
            scaleTicks.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(scaleTicks);

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = 50 + (progress * 5);
                zoomBadge.setText(pct + "%");
                if (fromUser) host.setBrowserZoomPercent(pct);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int pct = 50 + (seekBar.getProgress() * 5);
                host.setBrowserZoomPercent(pct);
            }
        });
        resetBtn.setOnClickListener(v -> {
            slider.setProgress(10);
            zoomBadge.setText("100%");
            host.setBrowserZoomPercent(100);
        });
    }

    void confirmClear() {
        CinemaBrowser.styleDarkDialog(new AlertDialog.Builder(host.context)
                .setTitle(host.context.getString(R.string.cinema_clear_browsing_data_prompt))
                .setMessage(org.proninyaroslav.libretorrent.R.string.cinema_browser_clear_data_notice)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_action_clear, (dialog, which) -> host.performClearBrowsingData())
                .show());
    }
}
