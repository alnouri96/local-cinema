package com.archos.mediacenter.video.leanback.cinema;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;

/**
 * First-launch & Settings-accessible Dark + Gold Permissions Wizard for Local Cinema TV.
 * Explains every permission clearly, displays live granted/missing status on return from system settings,
 * and allows 1-click activation, Skip, or Continue.
 */
public final class TvPermissionsWizardDialog {
    public static final String PREF_SHOWN = "tv_permissions_wizard_shown_v1";
    private static final String PREF_TCL_OPENED = "tv_permissions_tcl_guard_opened_v1";
    private static final int REQ_STORAGE = 7101;
    private static final int REQ_LOCATION = 7102;
    private static WeakReference<Dialog> activeDialog = new WeakReference<>(null);

    private TvPermissionsWizardDialog() {}

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

        boolean rtl = CinemaUi.arabic(activity);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 20));
        outer.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xff141517);
        rootBg.setStroke(dp(activity, 2), CinemaTokens.GOLD);
        rootBg.setCornerRadius(dp(activity, 16));
        outer.setBackground(rootBg);

        // Header
        TextView titleTv = new TextView(activity);
        titleTv.setText(label(activity,
                "إعداد الصلاحيات والخدمات (لأفضل تجربة مشاهدة واتصال)",
                "Permissions & Services Setup"));
        titleTv.setTextColor(CinemaTokens.GOLD);
        titleTv.setTextSize(22);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        outer.addView(titleTv);

        TextView subtitleTv = new TextView(activity);
        subtitleTv.setText(label(activity,
                "فعّل الصلاحيات التالية ليتمكن التطبيق من قراءة أفلامك، منع إغلاق النظام للاتصال، وتفعيل الريموت الكامل من الهاتف.",
                "Grant the permissions below so Local Cinema can read local drives, keep Phone Link awake, and enable full phone remote control."));
        subtitleTv.setTextColor(0xffc9c4bc);
        subtitleTv.setTextSize(14);
        subtitleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subtitleTv.setPadding(0, dp(activity, 6), 0, dp(activity, 14));
        outer.addView(subtitleTv);

        ScrollView scroll = new ScrollView(activity);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.bottomMargin = dp(activity, 14);
        outer.addView(scroll, scrollLp);

        // 1. Storage / All Files Access
        addPermissionCard(activity, list, refreshers,
                label(activity, "الوصول إلى الملفات والتخزين", "Storage & Media Files Access"),
                label(activity,
                        "لقراءة الأفلام والمسلسلات والترجمات المحفوظة على ذاكرة الشاشة الداخلية أو الفلاشة والهارد الخارجي.",
                        "To read movies, TV series, and subtitles stored on TV storage, USB flash drives, or external disks."),
                () -> hasStoragePermission(activity),
                () -> requestStoragePermission(activity));

        // 2. Battery Optimization Exemption
        addPermissionCard(activity, list, refreshers,
                label(activity, "استثناء توفير الطاقة ومنع السكون", "Battery Optimization Exemption"),
                label(activity,
                        "لمنع نظام التلفاز من إغلاق الاتصال في الخلفية والحفاظ على سرعة اتصال الهاتف والمزامنة التلقائية.",
                        "Prevents the TV OS from killing background processes so Phone Link and automatic sync stay fast."),
                () -> isIgnoringBatteryOptimizations(activity),
                () -> requestBatteryExemption(activity));

        // 3. Location Permission for Live Weather
        addPermissionCard(activity, list, refreshers,
                label(activity, "صلاحية الموقع للطقس الحي", "Location Permission for Live Weather"),
                label(activity,
                        "لجلب درجة الحرارة وحالة الطقس الحقيقية لموقعك الحالي بدقة في الساعة التفاعلية بالصفحة الرئيسية.",
                        "Lets the interactive Home clock display real local temperature and weather conditions for your current location."),
                () -> hasLocationPermission(activity),
                () -> requestLocationPermission(activity));

        // 4. Accessibility Service for Phone Remote
        addPermissionCard(activity, list, refreshers,
                label(activity, "خدمة إمكانية الوصول لريموت الهاتف", "Phone Remote Accessibility Service"),
                label(activity,
                        "لتمكين التحكم الكامل بالريموت والماوس الهوائي ولوحة المفاتيح من هاتفك داخل جميع تطبيقات الشاشة.",
                        "Enables full remote navigation, air mouse, and keyboard input from your phone across all TV apps."),
                TvRemoteAccessibilityService::available,
                () -> safeStartActivity(activity, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // 5. Local ADB Bridge (Hybrid Engine & Post-Update Persistence)
        addPermissionCard(activity, list, refreshers,
                label(activity, "تفعيل ADB المحلي (127.0.0.1:5555)", "Local ADB Bridge (127.0.0.1:5555)"),
                label(activity,
                        "للتحكم الكامل بالنظام والتطبيقات الخارجية مثل SmartTube واللانشر، وضمان بقاء الصلاحيات مفعّلة تلقائياً بعد تثبيت التحديثات.",
                        "Enables full control in external apps like SmartTube and custom launchers, and keeps remote permissions active across updates."),
                TvLocalAdbBridge::isReady,
                () -> TvRemotePermissionKeeper.showRemoteManagerDialog(activity, () -> {
                    for (Runnable r : refreshers) r.run();
                }));

        // 4. TCL Safety Guard (auto-shown on TCL TVs)
        if (hasPackage(activity, "com.tcl.guard")) {
            addPermissionCard(activity, list, refreshers,
                    label(activity, "حماية التشغيل لشاشات TCL (com.tcl.guard)", "TCL TV Background Guard (com.tcl.guard)"),
                    label(activity,
                            "لإيقاف الإدارة التلقائية وتفعيل البدء التلقائي والترابطي في حارس الأمان حتى لا ينقطع الاتصال عند السكون.",
                            "Disable auto-management and allow Auto Launch & Associated Startup in TCL Guard so connection never drops on sleep."),
                    () -> prefs.getBoolean(PREF_TCL_OPENED, false),
                    () -> {
                        prefs.edit().putBoolean(PREF_TCL_OPENED, true).apply();
                        openTclGuard(activity);
                        for (Runnable r : refreshers) r.run();
                    });
        }

        // Bottom actions: Skip & Continue
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        TextView skipBtn = buildDialogButton(activity, label(activity, "تخطي", "Skip"), false, () -> {
            prefs.edit().putBoolean(PREF_SHOWN, true).apply();
            dlg.dismiss();
        });
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(-2, -2);
        skLp.setMarginEnd(dp(activity, 10));
        actions.addView(skipBtn, skLp);

        TextView doneBtn = buildDialogButton(activity, label(activity, "متابعة / تم", "Continue"), true, () -> {
            prefs.edit().putBoolean(PREF_SHOWN, true).apply();
            dlg.dismiss();
        });
        actions.addView(doneBtn, new LinearLayout.LayoutParams(-2, -2));

        outer.addView(actions);

        android.widget.FrameLayout framed = new android.widget.FrameLayout(activity);
        GradientDrawable framedBg = new GradientDrawable();
        framedBg.setColor(0xff171717);
        framedBg.setStroke(dp(activity, 2), CinemaTokens.GOLD);
        framedBg.setCornerRadius(dp(activity, 16));
        framed.setBackground(framedBg);
        framed.setClipToOutline(true);

        android.widget.ImageView artBg = new android.widget.ImageView(activity);
        artBg.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        artBg.setImageResource(CinemaArtStorage.randomBuiltInResId());
        artBg.setAlpha(0.24f);
        framed.addView(artBg, new android.widget.FrameLayout.LayoutParams(-1, -1));

        View artScrim = new View(activity);
        artScrim.setBackgroundColor(0xC8171717);
        framed.addView(artScrim, new android.widget.FrameLayout.LayoutParams(-1, -1));

        outer.setBackground(null);
        framed.addView(outer, new android.widget.FrameLayout.LayoutParams(-1, -2));

        dlg.setContentView(framed);
        dlg.setOnDismissListener(d -> prefs.edit().putBoolean(PREF_SHOWN, true).apply());
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.78f);
            int maxHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.84f);
            w.setLayout(width, Math.min(ViewGroup.LayoutParams.WRAP_CONTENT, maxHeight));
        }
        for (Runnable r : refreshers) r.run();
        dlg.show();
        doneBtn.requestFocus();
    }

    private static void addPermissionCard(Activity activity, LinearLayout parent, List<Runnable> refreshers,
                                          String title, String explanation,
                                          java.util.function.BooleanSupplier isGranted,
                                          Runnable onEnable) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1d1e21);
        bg.setStroke(dp(activity, 1), 0xff3b3730);
        bg.setCornerRadius(dp(activity, 12));
        card.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(activity, 10);
        parent.addView(card, cp);

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMarginEnd(dp(activity, 14));
        card.addView(info, ip);

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(titleRow);

        TextView titleTv = new TextView(activity);
        titleTv.setText(title);
        titleTv.setTextColor(0xfff2ede7);
        titleTv.setTextSize(16);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginEnd(dp(activity, 8));
        titleRow.addView(titleTv, tp);

        TextView statusBadge = new TextView(activity);
        statusBadge.setTextSize(12);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(activity, 10), dp(activity, 3), dp(activity, 10), dp(activity, 3));
        titleRow.addView(statusBadge);

        TextView descTv = new TextView(activity);
        descTv.setText(explanation);
        descTv.setTextColor(0xffbdb7ae);
        descTv.setTextSize(13);
        descTv.setLineSpacing(dp(activity, 2), 1f);
        descTv.setPadding(0, dp(activity, 6), 0, 0);
        descTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        info.addView(descTv);

        TextView actionBtn = buildDialogButton(activity,
                label(activity, "تفعيل الآن", "Enable Now"), true, onEnable);
        card.addView(actionBtn, new LinearLayout.LayoutParams(-2, -2));

        Runnable refresh = () -> {
            boolean ok = false;
            try { ok = isGranted.getAsBoolean(); } catch (Throwable ignored) {}
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(activity, 8));
            if (ok) {
                statusBadge.setText(label(activity, "مفعّل ✓", "Enabled ✓"));
                statusBadge.setTextColor(0xff9de0b8);
                badgeBg.setColor(0xff1b3326);
                actionBtn.setText(label(activity, "فتح", "Open"));
            } else {
                statusBadge.setText(label(activity, "غير مفعّل", "Not enabled"));
                statusBadge.setTextColor(0xfff2b06b);
                badgeBg.setColor(0xff33261a);
                actionBtn.setText(label(activity, "تفعيل الآن", "Enable Now"));
            }
            statusBadge.setBackground(badgeBg);
        };
        refreshers.add(refresh);
    }

    private static TextView buildDialogButton(Activity activity, String text, boolean primary, Runnable click) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10));
        Runnable styleIdle = () -> {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(activity, 10));
            if (primary) {
                gd.setColor(0xff2b271c);
                gd.setStroke(dp(activity, 1), CinemaTokens.GOLD);
                btn.setTextColor(CinemaTokens.GOLD);
            } else {
                gd.setColor(0xff232428);
                gd.setStroke(dp(activity, 1), 0xff4c4943);
                btn.setTextColor(0xffd7d2ca);
            }
            btn.setBackground(gd);
        };
        styleIdle.run();
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                GradientDrawable gd = new GradientDrawable();
                gd.setCornerRadius(dp(activity, 10));
                gd.setColor(CinemaTokens.GOLD);
                btn.setBackground(gd);
                btn.setTextColor(0xff141517);
                btn.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start();
            } else {
                styleIdle.run();
                btn.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
        });
        btn.setOnClickListener(v -> click.run());
        return btn;
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES},
                        REQ_STORAGE);
                return;
            }
            if (!hasStoragePermission(activity)) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_STORAGE);
                return;
            }
        } catch (Exception ignored) {}
        openAppDetails(activity);
    }

    private static boolean hasLocationPermission(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestLocationPermission(Activity activity) {
        try {
            if (!hasLocationPermission(activity)) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},
                        REQ_LOCATION);
                return;
            }
        } catch (Exception ignored) {}
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
        if (!safeStartActivity(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppDetails(activity);
        }
    }

    private static void openTclGuard(Activity activity) {
        try {
            var pm = activity.getPackageManager();
            Intent intent = pm.getLeanbackLaunchIntentForPackage("com.tcl.guard");
            if (intent == null) intent = pm.getLaunchIntentForPackage("com.tcl.guard");
            if (intent != null) {
                activity.startActivity(intent);
                return;
            }
        } catch (Exception ignored) {}
        safeStartActivity(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:com.tcl.guard")));
    }

    private static void openAppDetails(Activity activity) {
        safeStartActivity(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }

    private static boolean safeStartActivity(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasPackage(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
}
