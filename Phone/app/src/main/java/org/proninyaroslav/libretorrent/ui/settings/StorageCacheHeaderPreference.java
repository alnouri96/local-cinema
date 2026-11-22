package org.proninyaroslav.libretorrent.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.os.ConfigurationCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.cinema_hub.BrowserDownloadManager;
import org.proninyaroslav.libretorrent.cinema_hub.CinemaCacheManager;
import org.proninyaroslav.libretorrent.cinema_hub.CinemaSnackBanner;

/**
 * Dark & Gold Settings card for "Storage & Cache", showing live breakdown
 * (App & Image Cache, Browser Cache, Temp Stream Files) and a safe one-tap
 * "Clear Cache" button with confirmation.
 */
public final class StorageCacheHeaderPreference extends Preference {
    private static final int GOLD = 0xfff5c518;
    private static final int CARD_ID = 0x51000102;

    private CinemaCacheManager.StorageBreakdown breakdown =
            new CinemaCacheManager.StorageBreakdown(0L, 0L, 0L);
    private boolean loading = true;
    private boolean clearing = false;

    public StorageCacheHeaderPreference(@NonNull Context context) {
        super(context);
        setKey("cinema_storage_and_cache");
        setPersistent(false);
        setSelectable(false);
        setOrder(-90);
        setLayoutResource(R.layout.pref_widget_summary);
        refreshBreakdown();
    }

    public void refreshBreakdown() {
        Context c = getContext();
        if (c == null) return;
        CinemaCacheManager.computeBreakdownAsync(c, result -> {
            this.breakdown = result != null ? result : new CinemaCacheManager.StorageBreakdown(0L, 0L, 0L);
            this.loading = false;
            notifyChanged();
        });
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context c = getContext();
        boolean ar = isArabic(c);

        if (!(holder.itemView instanceof ViewGroup rootGroup)) return;
        rootGroup.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 8));
        for (int i = 0; i < rootGroup.getChildCount(); i++) {
            View child = rootGroup.getChildAt(i);
            if (child.getId() != CARD_ID) child.setVisibility(View.GONE);
        }

        LinearLayout card = rootGroup.findViewById(CARD_ID);
        if (card != null) rootGroup.removeView(card);

        card = new LinearLayout(c);
        card.setId(CARD_ID);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1d1e21);
        bg.setStroke(dp(c, 1), 0xff4c4637);
        bg.setCornerRadius(dp(c, 16));
        card.setBackground(bg);
        card.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        rootGroup.addView(card, new ViewGroup.LayoutParams(-1, -2));

        // Header Row: Title + Total Badge
        LinearLayout headerRow = new LinearLayout(c);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        TextView heading = new TextView(c);
        heading.setText(ar ? "التخزين والكاش (Storage & Cache)" : "Storage & Cache");
        heading.setTextColor(GOLD);
        heading.setTextSize(16);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        headerRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView totalBadge = new TextView(c);
        String totalText = loading
                ? (ar ? "جاري الحساب..." : "Calculating...")
                : BrowserDownloadManager.formatBytes(breakdown.totalBytes());
        totalBadge.setText(totalText);
        totalBadge.setTextSize(12);
        totalBadge.setTypeface(Typeface.DEFAULT_BOLD);
        totalBadge.setTextColor(0xff141517);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(GOLD);
        badgeBg.setCornerRadius(dp(c, 10));
        totalBadge.setBackground(badgeBg);
        totalBadge.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        headerRow.addView(totalBadge);

        // Breakdown Container
        LinearLayout rowsBox = new LinearLayout(c);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(0xff151619);
        boxBg.setStroke(dp(c, 1), 0xff2c2d33);
        boxBg.setCornerRadius(dp(c, 12));
        rowsBox.setBackground(boxBg);
        rowsBox.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        var boxLp = new LinearLayout.LayoutParams(-1, -2);
        boxLp.topMargin = dp(c, 10);
        card.addView(rowsBox, boxLp);

        addBreakdownRow(c, rowsBox,
                ar ? "كاش التطبيق والصور" : "App & Image Cache",
                BrowserDownloadManager.formatBytes(breakdown.appImageCacheBytes()),
                false);
        addBreakdownRow(c, rowsBox,
                ar ? "كاش المتصفح" : "Browser Cache",
                BrowserDownloadManager.formatBytes(breakdown.browserCacheBytes()),
                true);
        addBreakdownRow(c, rowsBox,
                ar ? "ملفات البث المؤقتة" : "Temp Stream Files",
                BrowserDownloadManager.formatBytes(breakdown.tempStreamBytes()),
                true);

        TextView safetyNote = new TextView(c);
        safetyNote.setText(ar
                ? "يمسح الكاش وملفات البث المؤقتة فقط — تبقى حسابات Trakt والمفضلة والسجل والإضافات (uBlock) والمفاتيح محفوظة بأمان."
                : "Clears caches and temporary stream buffers only — Trakt, bookmarks, history, extensions (uBlock), and keys stay safe.");
        safetyNote.setTextSize(11.5f);
        safetyNote.setTextColor(0xffa1a1aa);
        var noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(c, 8);
        card.addView(safetyNote, noteLp);

        MaterialButton clearBtn = new MaterialButton(c, null,
                com.google.android.material.R.attr.materialButtonStyle);
        String btnText = clearing
                ? (ar ? "جاري مسح الكاش..." : "Clearing Cache...")
                : (ar ? "مسح الكاش (Clear Cache)" : "Clear Cache");
        clearBtn.setText(btnText);
        clearBtn.setEnabled(!clearing);
        clearBtn.setCornerRadius(dp(c, 12));
        clearBtn.setMinHeight(dp(c, 42));
        clearBtn.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        clearBtn.setTextColor(0xff141517);
        clearBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearBtn.setOnClickListener(v -> confirmAndClearCache(c, ar));
        var btnLp = new LinearLayout.LayoutParams(-1, -2);
        btnLp.topMargin = dp(c, 10);
        card.addView(clearBtn, btnLp);
    }

    private static void addBreakdownRow(Context c, LinearLayout parent, String label, String sizeText, boolean topMargin) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        var lp = new LinearLayout.LayoutParams(-1, -2);
        if (topMargin) lp.topMargin = dp(c, 6);
        parent.addView(row, lp);

        TextView lbl = new TextView(c);
        lbl.setText(label);
        lbl.setTextSize(13);
        lbl.setTextColor(0xffe4e4e7);
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView val = new TextView(c);
        val.setText(sizeText);
        val.setTextSize(13);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(0xfff5c518);
        row.addView(val);
    }

    public void confirmAndClearCache(Context c, boolean ar) {
        String sizeFormatted = BrowserDownloadManager.formatBytes(breakdown.totalBytes());
        new MaterialAlertDialogBuilder(c)
                .setTitle(ar ? "مسح الكاش والملفات المؤقتة؟" : "Clear Cache & Temp Stream Files?")
                .setMessage(ar
                        ? ("سيتم تحرير حوالي " + sizeFormatted + " من كاش التطبيق والمتصفح وملفات البث المؤقتة.\n\nلن يتم مسح حساب Trakt أو المفضلة أو سجل التصفح أو الإضافات أو الإعدادات.")
                        : ("This will free approximately " + sizeFormatted + " of App & Image Cache, Browser Cache, and Temp Stream Files.\n\nYour Trakt account, bookmarks, browser history, installed extensions, API keys, and settings will NOT be affected."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(ar ? "مسح الكاش" : "Clear Cache", (d, w) -> {
                    clearing = true;
                    notifyChanged();
                    CinemaCacheManager.clearAllCachesAsync(c, freedBytes -> {
                        clearing = false;
                        refreshBreakdown();
                        String msg = ar
                                ? ("تم مسح الكاش وتحرير " + BrowserDownloadManager.formatBytes(freedBytes) + " ✓")
                                : ("Cache cleared (" + BrowserDownloadManager.formatBytes(freedBytes) + " freed) ✓");
                        CinemaSnackBanner.show(c, msg);
                    });
                })
                .show();
    }

    private static boolean isArabic(Context c) {
        var locale = ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
        return locale != null && "ar".equals(locale.getLanguage());
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
