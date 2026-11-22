package com.archos.mediacenter.video.leanback.cinema;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;

/**
 * Self-healing Accessibility & Hybrid Remote manager.
 * - Auto-restores TvRemoteAccessibilityService on boot / app upgrade via WRITE_SECURE_SETTINGS.
 * - Evaluates the 3-color Hybrid Remote LED status (Green / Blue / Red).
 * - Presents the interactive TV Remote Manager card with color legend and Enable/Test controls.
 */
public final class TvRemotePermissionKeeper {
    private static final String TAG = "CinemaRemoteKeeper";

    public enum RemoteLed {
        GREEN,
        BLUE,
        RED
    }

    private TvRemotePermissionKeeper() {}

    public static boolean hasWriteSecureSettings(Context context) {
        if (context == null) return false;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Ensures TvRemoteAccessibilityService is enabled whenever WRITE_SECURE_SETTINGS is available,
     * so OS upgrades or APK reinstalls never silently switch off remote Accessibility control.
     */
    public static boolean ensureAccessibilityEnabled(Context context) {
        if (context == null) return false;
        if (TvRemoteAccessibilityService.available()) return true;
        if (!hasWriteSecureSettings(context)) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            ComponentName cn = new ComponentName(context, TvRemoteAccessibilityService.class);
            String full = cn.flattenToString();
            String shortName = cn.flattenToShortString();
            String current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String updated = current == null ? "" : current.trim();
            boolean present = false;
            for (String entry : updated.split(":")) {
                String t = entry.trim();
                if (t.equals(full) || t.equals(shortName)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                updated = updated.isEmpty() ? full : (updated + ":" + full);
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Auto-enable accessibility failed", t);
            return false;
        }
    }

    public static RemoteLed evaluateRemoteLed(Context context) {
        boolean serverOk = CinemaLinkServer.isServerRunning();
        boolean adbOk = TvLocalAdbBridge.isReady();
        boolean accOk = TvRemoteAccessibilityService.available();
        if (!serverOk) return RemoteLed.RED;
        if (adbOk && accOk) return RemoteLed.GREEN;
        return RemoteLed.BLUE;
    }

    public static int remoteLedColor(RemoteLed led) {
        return switch (led) {
            case GREEN -> 0xff22c55e;
            case BLUE -> 0xff3b82f6;
            case RED -> 0xffef4444;
        };
    }

    public static String tierName(Context context) {
        return switch (evaluateRemoteLed(context)) {
            case GREEN -> "hybrid_full";
            case BLUE -> "web_only";
            case RED -> "inactive";
        };
    }

    public static void showRemoteManagerDialog(Activity activity, Runnable onStatusChanged) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Dialog dlg = new Dialog(activity);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(true);

        boolean rtl = CinemaUi.arabic(activity);
        Handler main = new Handler(Looper.getMainLooper());

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 20));
        outer.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xff141517);
        rootBg.setStroke(dp(activity, 2), CinemaTokens.GOLD);
        rootBg.setCornerRadius(dp(activity, 16));
        outer.setBackground(rootBg);

        TextView titleTv = new TextView(activity);
        titleTv.setText(label(activity, "إدارة الريموت الهجين", "Hybrid Remote Manager"));
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(20);
        titleTv.setTypeface(null, Typeface.BOLD);
        outer.addView(titleTv);

        // Current Live Status Banner
        LinearLayout statusBanner = new LinearLayout(activity);
        statusBanner.setOrientation(LinearLayout.HORIZONTAL);
        statusBanner.setGravity(Gravity.CENTER_VERTICAL);
        statusBanner.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(0xff1d1f24);
        bannerBg.setCornerRadius(dp(activity, 12));
        bannerBg.setStroke(dp(activity, 1), 0xff2c3038);
        statusBanner.setBackground(bannerBg);

        View ledView = new View(activity);
        GradientDrawable ledBg = new GradientDrawable();
        ledBg.setShape(GradientDrawable.OVAL);
        ledView.setBackground(ledBg);
        LinearLayout.LayoutParams ledLp = new LinearLayout.LayoutParams(dp(activity, 14), dp(activity, 14));
        ledLp.setMarginEnd(dp(activity, 12));
        statusBanner.addView(ledView, ledLp);

        TextView statusHeadline = new TextView(activity);
        statusHeadline.setTextSize(14);
        statusHeadline.setTypeface(null, Typeface.BOLD);
        statusHeadline.setTextColor(Color.WHITE);
        statusBanner.addView(statusHeadline, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, -2);
        bannerLp.topMargin = dp(activity, 12);
        outer.addView(statusBanner, bannerLp);

        TextView detailText = new TextView(activity);
        detailText.setTextColor(0xffcbd5e1);
        detailText.setTextSize(13);
        detailText.setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 4));
        outer.addView(detailText);

        TextView testResultTv = new TextView(activity);
        testResultTv.setTextSize(13);
        testResultTv.setTextColor(CinemaTokens.GOLD);
        testResultTv.setVisibility(View.GONE);
        testResultTv.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        GradientDrawable trBg = new GradientDrawable();
        trBg.setColor(0xff232018);
        trBg.setCornerRadius(dp(activity, 10));
        trBg.setStroke(dp(activity, 1), 0xff5a4923);
        testResultTv.setBackground(trBg);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
        trLp.topMargin = dp(activity, 6);
        outer.addView(testResultTv, trLp);

        // Scrollable 3-Color Legend Card
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout legendCard = new LinearLayout(activity);
        legendCard.setOrientation(LinearLayout.VERTICAL);
        legendCard.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xff1b1d22);
        cardBg.setCornerRadius(dp(activity, 12));
        legendCard.setBackground(cardBg);

        TextView legendTitle = new TextView(activity);
        legendTitle.setText(label(activity, "دلالة ألوان نقطة الحالة (LED):", "Status Dot Color Guide:"));
        legendTitle.setTextColor(CinemaTokens.GOLD);
        legendTitle.setTextSize(14);
        legendTitle.setTypeface(null, Typeface.BOLD);
        legendCard.addView(legendTitle);

        legendCard.addView(legendRow(activity, 0xff22c55e,
                label(activity, "نقطة خضراء — تحكم هجين كامل", "Green — Full Hybrid Control"),
                label(activity,
                        "كلا المسارين مفعّلان (ADB المحلي + الويب/سهولة الوصول): يعمل داخل التطبيق وخارجه في SmartTube واللانشر، مع بقاء الصلاحيات مفعّلة بعد التحديثات.",
                        "Both ADB Bridge & Accessibility/Web active: full external app & launcher control plus auto-restore after updates.")));

        legendCard.addView(legendRow(activity, 0xff3b82f6,
                label(activity, "نقطة زرقاء — وضع الويب فقط", "Blue — Web Only Mode"),
                label(activity,
                        "وضع الويب فقط — الصلاحيات محدودة خارج التطبيق (الريموت يعمل داخل التطبيق؛ فعّل ADB المحلي أو سهولة الوصول للتحكم الكامل).",
                        "Web Only Mode — permissions limited outside app (in-app control works; enable Local ADB or Accessibility for external apps).")));

        legendCard.addView(legendRow(activity, 0xffef4444,
                label(activity, "نقطة حمراء — غير مفعّل", "Red — Inactive"),
                label(activity,
                        "خادم الريموت غير نشط أو الشبكة مفصولة ولا يوجد مسار اتصال متاح.",
                        "Remote server is stopped or disconnected with no active control path.")));

        body.addView(legendCard, new LinearLayout.LayoutParams(-1, -2));

        // Action Buttons Row(s)
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(activity, 12), 0, 0);

        Runnable refreshUi = () -> {
            ensureAccessibilityEnabled(activity);
            RemoteLed led = evaluateRemoteLed(activity);
            ledBg.setColor(remoteLedColor(led));
            ledView.invalidate();
            boolean serverOk = CinemaLinkServer.isServerRunning();
            boolean adbOk = TvLocalAdbBridge.isReady();
            boolean accOk = TvRemoteAccessibilityService.available();
            boolean secureOk = hasWriteSecureSettings(activity);

            if (led == RemoteLed.GREEN) {
                statusHeadline.setText(label(activity,
                        "مفعّل بالكامل (ADB المحلي + الويب/سهولة الوصول)",
                        "Fully Active (Local ADB + Accessibility/Web)"));
                statusHeadline.setTextColor(0xff4ade80);
            } else if (led == RemoteLed.BLUE) {
                statusHeadline.setText(label(activity,
                        "وضع الويب فقط — الصلاحيات محدودة خارج التطبيق",
                        "Web Only Mode — Permissions limited outside app"));
                statusHeadline.setTextColor(0xff60a5fa);
            } else {
                statusHeadline.setText(label(activity,
                        "الريموت غير مفعّل (الخادم متوقف)",
                        "Remote Inactive (Server Stopped)"));
                statusHeadline.setTextColor(0xfff87171);
            }
            String ok = label(activity, "متصل ✓", "Active ✓");
            String off = label(activity, "غير مفعّل ✗", "Inactive ✗");
            detailText.setText(
                    label(activity, "١. خادم الويب (داخل التطبيق): ", "1. Web Server (In-App): ") + (serverOk ? ok : off) + "\n"
                    + label(activity, "٢. جسر ADB المحلي (127.0.0.1:5555): ", "2. Local ADB Bridge (127.0.0.1:5555): ") + (adbOk ? ok : off) + "\n"
                    + label(activity, "٣. خدمة سهولة الوصول (Accessibility): ", "3. Accessibility Service: ") + (accOk ? ok : off) + "\n"
                    + label(activity, "• الإبقاء التلقائي بعد التحديثات (WRITE_SECURE_SETTINGS): ",
                            "• Auto-restore after updates (WRITE_SECURE_SETTINGS): ") + (secureOk ? ok : off));
            if (onStatusChanged != null) onStatusChanged.run();
        };

        TextView enableAdbBtn = tvActionBtn(activity,
                label(activity, "تفعيل / ربط جسر ADB المحلي (127.0.0.1:5555)", "Enable / Connect Local ADB (127.0.0.1:5555)"),
                true);
        enableAdbBtn.setOnClickListener(v -> {
            testResultTv.setVisibility(View.VISIBLE);
            testResultTv.setText(label(activity,
                    "جارٍ الاتصال بـ 127.0.0.1:5555... إذا ظهرت نافذة السماح على الشاشة اختر السماح دائماً ✓",
                    "Connecting to 127.0.0.1:5555... Approve USB Debugging prompt if shown."));
            TvLocalAdbBridge.connectInteractiveAsync(activity, res -> main.post(() -> {
                if (res.success()) {
                    testResultTv.setTextColor(0xff4ade80);
                    testResultTv.setText(label(activity,
                            "تم تفعيل ADB المحلي ومنح صلاحية الاستعادة التلقائية بنجاح ✓",
                            "Local ADB connected & auto-restore enabled ✓"));
                } else {
                    testResultTv.setTextColor(0xfff87171);
                    testResultTv.setText(label(activity,
                            "تعذر اتصال ADB المحلي (" + res.message() + "). تأكد من تفعيل USB/Wireless Debugging في خيارات المطور.",
                            "Local ADB failed (" + res.message() + "). Check Developer Options -> USB/Wireless Debugging."));
                }
                refreshUi.run();
            }));
        });
        actions.addView(enableAdbBtn, btnLp(activity));

        TextView testBtn = tvActionBtn(activity,
                label(activity, "اختبار مسارات الريموت الآن", "Test Remote Paths Now"),
                false);
        testBtn.setOnClickListener(v -> {
            testResultTv.setVisibility(View.VISIBLE);
            testResultTv.setTextColor(CinemaTokens.GOLD);
            testResultTv.setText(label(activity, "جارٍ فحص المسارات الثلاثة...", "Testing 3 remote tiers..."));
            new Thread(() -> {
                TvLocalAdbBridge.ConnectResult adbRes = TvLocalAdbBridge.isEnabled(activity)
                        ? TvLocalAdbBridge.testAndGrantBlocking(activity)
                        : new TvLocalAdbBridge.ConnectResult(false, "disabled");
                boolean restored = ensureAccessibilityEnabled(activity);
                main.post(() -> {
                    boolean acc = TvRemoteAccessibilityService.available();
                    boolean srv = CinemaLinkServer.isServerRunning();
                    String summary = (srv ? "✓ Web " : "✗ Web ")
                            + (adbRes.success() ? "✓ ADB " : "✗ ADB ")
                            + (acc || restored ? "✓ Accessibility" : "✗ Accessibility");
                    testResultTv.setTextColor(adbRes.success() && acc ? 0xff4ade80 : 0xff60a5fa);
                    testResultTv.setText(label(activity, "نتيجة الاختبار: ", "Test result: ") + summary);
                    refreshUi.run();
                });
            }).start();
        });
        actions.addView(testBtn, btnLp(activity));

        TextView accBtn = tvActionBtn(activity,
                label(activity, "فتح إعدادات سهولة الوصول (Accessibility)", "Open Accessibility Settings"),
                false);
        accBtn.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });
        actions.addView(accBtn, btnLp(activity));

        body.addView(actions);
        scroll.addView(body);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(activity, 10);
        outer.addView(scroll, scrollLp);

        TextView closeBtn = tvActionBtn(activity, label(activity, "إغلاق", "Close"), false);
        closeBtn.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
        cLp.topMargin = dp(activity, 10);
        outer.addView(closeBtn, cLp);

        refreshUi.run();
        dlg.setContentView(outer);
        if (dlg.getWindow() != null) {
            int maxW = Math.min((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.72f), dp(activity, 680));
            int maxH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.86f);
            dlg.getWindow().setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            if (outer.getLayoutParams() != null) outer.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        dlg.setOnDismissListener(d -> {
            if (onStatusChanged != null) onStatusChanged.run();
        });
        dlg.show();
        enableAdbBtn.requestFocus();
    }

    private static View legendRow(Activity a, int dotColor, String title, String desc) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.topMargin = dp(a, 10);
        row.setLayoutParams(rLp);

        View dot = new View(a);
        GradientDrawable dBg = new GradientDrawable();
        dBg.setShape(GradientDrawable.OVAL);
        dBg.setColor(dotColor);
        dot.setBackground(dBg);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(a, 11), dp(a, 11));
        dLp.topMargin = dp(a, 5);
        dLp.setMarginEnd(dp(a, 10));
        row.addView(dot, dLp);

        LinearLayout txtCol = new LinearLayout(a);
        txtCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        txtCol.addView(t);

        TextView d = new TextView(a);
        d.setText(desc);
        d.setTextColor(0xff94a3b8);
        d.setTextSize(12);
        txtCol.addView(d);
        row.addView(txtCol, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private static TextView tvActionBtn(Activity a, String text, boolean primary) {
        TextView btn = new TextView(a);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(14);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(primary ? 0xff141517 : Color.WHITE);
        btn.setPadding(dp(a, 16), dp(a, 11), dp(a, 16), dp(a, 11));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(a, 10));
        normal.setColor(primary ? CinemaTokens.GOLD : 0xff262930);
        normal.setStroke(dp(a, 1), primary ? CinemaTokens.GOLD : 0xff3b404c);

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(a, 10));
        focused.setColor(primary ? 0xffffd86b : 0xff343842);
        focused.setStroke(dp(a, 2), Color.WHITE);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{}, normal);
        btn.setBackground(sld);
        return btn;
    }

    private static LinearLayout.LayoutParams btnLp(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 8);
        return lp;
    }
}
