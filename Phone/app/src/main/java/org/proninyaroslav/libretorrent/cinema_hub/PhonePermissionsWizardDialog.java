package org.proninyaroslav.libretorrent.cinema_hub;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.ConfigurationCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * First-launch & Settings-accessible Dark + Gold Permissions Wizard for Local Cinema Manager (Phone).
 */
public final class PhonePermissionsWizardDialog {
    public static final String PREF_SHOWN = "phone_permissions_wizard_shown_v1";
    private static final int GOLD = 0xfff5c518;
    private static final int REQ_CODE_BASE = 8200;
    private static WeakReference<Dialog> activeDialog = new WeakReference<>(null);

    private PhonePermissionsWizardDialog() {}

    public static void maybeShowFirstLaunch(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (prefs.getBoolean(PREF_SHOWN, false)) return;
        show(activity);
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Dialog existing = activeDialog.get();
        if (existing != null && existing.isShowing()) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        List<Runnable> refreshers = new ArrayList<>();

        Dialog dlg = new Dialog(activity) {
            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                if (hasFocus) {
                    for (Runnable r : refreshers) r.run();
                }
            }
        };
        activeDialog = new WeakReference<>(dlg);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(true);

        boolean ar = isArabic(activity);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 14));
        outer.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xff141517);
        rootBg.setStroke(dp(activity, 1), GOLD);
        rootBg.setCornerRadius(dp(activity, 18));
        outer.setBackground(rootBg);

        TextView titleTv = new TextView(activity);
        titleTv.setText(ar ? "إعداد صلاحيات التطبيق الأساسية" : "App Permissions Setup");
        titleTv.setTextColor(GOLD);
        titleTv.setTextSize(20);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        outer.addView(titleTv);

        TextView subtitleTv = new TextView(activity);
        subtitleTv.setText(ar
                ? "فعّل هذه الصلاحيات لضمان استمرار البث للشاشة في الخلفية، اكتشاف التلفاز على الشبكة، وحفظ النسخ الاحتياطية."
                : "Enable these permissions for smooth TV discovery, background casting, notifications, and backup storage.");
        subtitleTv.setTextColor(0xffc9c4bc);
        subtitleTv.setTextSize(13);
        subtitleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subtitleTv.setPadding(0, dp(activity, 6), 0, dp(activity, 12));
        outer.addView(subtitleTv);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.bottomMargin = dp(activity, 12);
        outer.addView(scroll, scrollLp);

        // 1. Notifications
        addPermissionCard(activity, list, refreshers, ar,
                ar ? "الإشعارات" : "Notifications",
                ar ? "لعرض حالة البث المباشر، تحميلات التورنت، وحالة الاتصال بالتلفاز في شريط الإشعارات."
                   : "Shows live TV casting status, torrent downloads, and connection controls in notifications.",
                () -> hasNotificationsPermission(activity),
                () -> requestNotificationsPermission(activity));

        // 2. Media & Storage Access
        addPermissionCard(activity, list, refreshers, ar,
                ar ? "الوصول إلى الوسائط والملفات" : "Media & Storage Access",
                ar ? "لبث الأفلام والموسيقى والصور من هاتفك إلى الشاشة، وحفظ ملفات التورنت والنسخ الاحتياطية."
                   : "Allows casting movies, music, and photos to TV, and saving torrents and unified backups.",
                () -> hasStoragePermission(activity),
                () -> requestStoragePermission(activity));

        // 3. Nearby Wi-Fi Devices / Location
        addPermissionCard(activity, list, refreshers, ar,
                ar ? "الأجهزة القريبة والشبكة" : "Nearby Wi-Fi Devices / Network",
                ar ? "لاكتشاف شاشة التلفاز تلقائياً على شبكة الواي فاي المحلية والاتصال السريع بها."
                   : "Discovers your Android TV on local Wi-Fi automatically for fast pairing and casting.",
                () -> hasNearbyWifiPermission(activity),
                () -> requestNearbyWifiPermission(activity));

        // 4. Battery Optimization Exemption
        addPermissionCard(activity, list, refreshers, ar,
                ar ? "استثناء توفير الطاقة" : "Battery Optimization Exemption",
                ar ? "لاستمرار بث الفيديو والتورنت للشاشة في الخلفية دون توقف عند إطفاء شاشة الهاتف."
                   : "Keeps video casting and torrent streaming active in the background when the phone screen turns off.",
                () -> isIgnoringBatteryOptimizations(activity),
                () -> requestBatteryExemption(activity));

        // Footer buttons
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        MaterialButton skipBtn = makeButton(activity, ar ? "تخطي" : "Skip", false, () -> {
            prefs.edit().putBoolean(PREF_SHOWN, true).apply();
            dlg.dismiss();
        });
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(-2, -2);
        skLp.setMarginEnd(dp(activity, 8));
        actions.addView(skipBtn, skLp);

        MaterialButton doneBtn = makeButton(activity, ar ? "متابعة / تم" : "Continue", true, () -> {
            prefs.edit().putBoolean(PREF_SHOWN, true).apply();
            dlg.dismiss();
        });
        actions.addView(doneBtn, new LinearLayout.LayoutParams(-2, -2));

        outer.addView(actions);

        dlg.setContentView(outer);
        dlg.setOnDismissListener(d -> prefs.edit().putBoolean(PREF_SHOWN, true).apply());
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.94f);
            int maxHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.85f);
            w.setLayout(width, Math.min(ViewGroup.LayoutParams.WRAP_CONTENT, maxHeight));
        }
        for (Runnable r : refreshers) r.run();
        dlg.show();
    }

    private static void addPermissionCard(Activity activity, LinearLayout parent, List<Runnable> refreshers,
                                          boolean ar, String title, String desc,
                                          BooleanSupplier isGranted, Runnable onEnable) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1d1e21);
        bg.setStroke(dp(activity, 1), 0xff45403a);
        bg.setCornerRadius(dp(activity, 14));
        card.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(activity, 10);
        parent.addView(card, cp);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);

        TextView titleTv = new TextView(activity);
        titleTv.setText(title);
        titleTv.setTextColor(0xfff2ede7);
        titleTv.setTextSize(15);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginEnd(dp(activity, 8));
        header.addView(titleTv, tp);

        TextView statusTv = new TextView(activity);
        statusTv.setTextSize(12);
        statusTv.setTypeface(Typeface.DEFAULT_BOLD);
        statusTv.setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3));
        header.addView(statusTv);

        TextView descTv = new TextView(activity);
        descTv.setText(desc);
        descTv.setTextColor(0xffbdb7ae);
        descTv.setTextSize(12);
        descTv.setLineSpacing(dp(activity, 2), 1f);
        descTv.setPadding(0, dp(activity, 6), 0, dp(activity, 8));
        descTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        card.addView(descTv);

        MaterialButton enableBtn = makeButton(activity, ar ? "تفعيل الآن" : "Enable Now", false, onEnable);
        card.addView(enableBtn, new LinearLayout.LayoutParams(-1, -2));

        refreshers.add(() -> {
            boolean ok = false;
            try { ok = isGranted.getAsBoolean(); } catch (Throwable ignored) {}
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(activity, 8));
            if (ok) {
                statusTv.setText(ar ? "مفعّل ✓" : "Enabled ✓");
                statusTv.setTextColor(0xff9de0b8);
                badgeBg.setColor(0xff1b3326);
                enableBtn.setText(ar ? "مفعّل ✓ (تعديل الإعدادات)" : "Enabled ✓ (Settings)");
            } else {
                statusTv.setText(ar ? "غير مفعّل" : "Not enabled");
                statusTv.setTextColor(0xfff2b06b);
                badgeBg.setColor(0xff33261a);
                enableBtn.setText(ar ? "تفعيل الآن" : "Enable Now");
            }
            statusTv.setBackground(badgeBg);
        });
    }

    private static MaterialButton makeButton(Activity activity, String text, boolean primary, Runnable click) {
        MaterialButton btn = new MaterialButton(activity, null, primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setSingleLine(false);
        btn.setMaxLines(2);
        btn.setCornerRadius(dp(activity, 12));
        btn.setMinHeight(dp(activity, 42));
        if (primary) {
            btn.setBackgroundTintList(ColorStateList.valueOf(GOLD));
            btn.setTextColor(0xff141517);
        } else {
            btn.setTextColor(GOLD);
        }
        btn.setOnClickListener(v -> click.run());
        return btn;
    }

    private static boolean hasNotificationsPermission(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean granted = ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            return granted && NotificationManagerCompat.from(c).areNotificationsEnabled();
        }
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    private static void requestNotificationsPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_CODE_BASE + 1);
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    private static boolean hasStoragePermission(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.READ_MEDIA_IMAGES
                }, REQ_CODE_BASE + 2);
                return;
            }
        } else if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_CODE_BASE + 2);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            } catch (Exception ignored) {}
        }
        openAppDetails(activity);
    }

    private static boolean hasNearbyWifiPermission(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestNearbyWifiPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_CODE_BASE + 3);
                return;
            }
        } else if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_CODE_BASE + 3);
            return;
        }
        openAppDetails(activity);
    }

    private static boolean isIgnoringBatteryOptimizations(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    private static void requestBatteryExemption(Activity activity) {
        try {
            Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName()));
            if (direct.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(direct);
                return;
            }
        } catch (Exception ignored) {}
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    private static void openAppDetails(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {}
    }

    private static boolean isArabic(Context c) {
        var locale = ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
        return locale != null && "ar".equals(locale.getLanguage());
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
