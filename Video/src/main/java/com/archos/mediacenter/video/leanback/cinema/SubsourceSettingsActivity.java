package com.archos.mediacenter.video.leanback.cinema;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.meedx.providers.ProviderKeys;
import com.meedx.providers.ZeroConfigSubtitles;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Luxury dark-gold TV Subtitle Settings matching CinemaSettingsActivity card architecture. */
public class SubsourceSettingsActivity extends LeanbackActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout content;
    private ScrollView scrollView;
    private String lastTestStatus = "";

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!isFinishing() && !isDestroyed()) render();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        ContextCompat.registerReceiver(this, syncReceiver,
                new IntentFilter(CinemaLinkServer.ACTION_API_SYNC_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        render();
    }

    @Override public void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(syncReceiver); } catch (Exception ignored) {}
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private String l(String ar, String en) {
        return label(this, ar, en);
    }

    private void render() {
        int focusIdx = 2;
        if (content != null) {
            View focused = getCurrentFocus();
            if (focused != null && focused.getParent() == content) {
                focusIdx = Math.max(2, content.indexOfChild(focused));
            }
        }
        int scrollY = scrollView != null ? scrollView.getScrollY() : 0;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(surface());
        if (arabic(this)) scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scrollView = scroll;

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(this, CinemaTokens.UTILITY_GUTTER), dp(this, CinemaTokens.SAFE),
                dp(this, CinemaTokens.UTILITY_GUTTER), dp(this, CinemaTokens.SAFE));
        scroll.addView(content);
        setContentView(scroll);

        TextView header = text(this, CinemaTokens.PAGE_TITLE, true);
        header.setText(l("إعدادات الترجمة", "Subtitle Settings"));
        header.setPadding(0, 0, 0, dp(this, 6));
        content.addView(header);

        TextView desc = text(this, CinemaTokens.LABEL, false);
        desc.setTextColor(0xffb8b8b8);
        desc.setText(l(
                "ضبط مزامنة مفاتيح الهاتف، اللغة التلقائية، المظهر وفحص اتصال الترجمة بضغطة واحدة.",
                "Manage Phone-synced subtitle keys, automatic language, appearance & test connection."));
        desc.setPadding(0, 0, 0, dp(this, 20));
        content.addView(desc);

        // 1. Phone Subtitle Sync
        boolean autoEnabled = prefs.getBoolean("subsource_enabled", true);
        boolean hasSubsource = !ProviderKeys.get(this, ProviderKeys.SUBSOURCE).isBlank();
        boolean hasOpenSub = !ProviderKeys.get(this, ProviderKeys.OPEN_SUBTITLES).isBlank();
        String phoneKeysSummary = (hasSubsource && hasOpenSub)
                ? l("Subsource + OpenSubtitles محفوظان ✓", "Subsource + OpenSubtitles synced ✓")
                : hasSubsource ? l("Subsource API محفوظ ✓ · مصادر مجانية مفعّلة", "Subsource synced ✓ · Free sources active")
                : hasOpenSub ? l("OpenSubtitles API محفوظ ✓ · مصادر مجانية مفعّلة", "OpenSubtitles synced ✓ · Free sources active")
                : l("ترجمة مجانية تلقائية نشطة (أضف مفاتيح إضافية من الهاتف)", "Free auto subtitles active (optional keys managed in Phone)");
        settingsCard(this, content, R.drawable.cinema_ic_addons,
                l("مزامنة ترجمة الهاتف", "Phone Subtitle Sync"),
                SubtitleSources.label(this) + " · " + phoneKeysSummary,
                autoEnabled, null, this::openPhoneSyncMenu);

        // 2. Automatic Search Language
        String lang = CinemaAutoSubtitles.preferredLanguage(this);
        settingsCard(this, content, R.drawable.cinema_globe,
                l("لغة البحث التلقائي", "Automatic Search Language"),
                l("اللغة الحالية للبحث والتحميل التلقائي: ", "Current search & auto-download language: ")
                        + CinemaAutoSubtitles.languageLabel(lang),
                null, null, () -> CinemaAutoSubtitles.chooseLanguage(this, lang, false, selected -> {
                    prefs.edit().putString("subsource_language", selected).apply();
                    render();
                }));

        // 3. Subtitle Appearance & Position
        settingsCard(this, content, R.drawable.cinema_ic_appearance,
                l("مظهر وموضع الترجمة", "Subtitle Appearance & Position"),
                appearanceSummary(),
                null, null, this::openAppearanceDialog);

        // 4. Test Subtitle Connection
        String testSub = !lastTestStatus.isBlank() ? lastTestStatus
                : l("اختبار جاهزية مصادر الترجمة المجانية والمزامنة", "Verify free & synced subtitle sources live");
        settingsCard(this, content, R.drawable.cinema_subtitles,
                l("اختبار اتصال الترجمة", "Test Subtitle Connection"),
                testSub, null, null, this::runConnectionTest);

        final int restoreIdx = focusIdx;
        content.post(() -> {
            if (isFinishing() || content == null) return;
            if (scrollY > 0) scroll.scrollTo(0, scrollY);
            if (restoreIdx < content.getChildCount() && content.getChildAt(restoreIdx).isFocusable()) {
                content.getChildAt(restoreIdx).requestFocus();
            } else if (content.getChildCount() > 2) {
                content.getChildAt(2).requestFocus();
            }
        });
    }

    private void openPhoneSyncMenu() {
        boolean autoEnabled = prefs.getBoolean("subsource_enabled", true);
        int mode = SubtitleSources.selected(this);
        String[] choices = {
                l("تبديل التحميل التلقائي للترجمة: ", "Toggle Auto Subtitles: ") + (autoEnabled ? l("مفعّل", "Enabled") : l("متوقف", "Off")),
                l("محرك البحث: Subsource + مجاني تلقائي", "Engine: Subsource + Free Auto") + (mode == 2 || mode == 0 ? " ✓" : ""),
                l("محرك البحث: OpenSubtitles", "Engine: OpenSubtitles") + (mode == 1 ? " ✓" : ""),
                l("طلب مزامنة فورية من الهاتف الآن", "Sync Keys from Phone Now")
        };
        dialog(this).setTitle(l("مزامنة ترجمة الهاتف", "Phone Subtitle Sync"))
                .setItems(choices, (d, which) -> {
                    switch (which) {
                        case 0 -> {
                            prefs.edit().putBoolean("subsource_enabled", !autoEnabled).apply();
                            render();
                        }
                        case 1 -> {
                            prefs.edit().putInt(SubtitleSources.MODE, 2).apply();
                            render();
                        }
                        case 2 -> {
                            prefs.edit().putInt(SubtitleSources.MODE, 1).apply();
                            render();
                        }
                        case 3 -> {
                            boolean ok = CinemaLinkServer.requestPhoneFullSync();
                            Toast.makeText(this, ok
                                    ? l("تم إرسال طلب مزامنة المفاتيح إلى الهاتف ✓", "Requested subtitle sync from Phone ✓")
                                    : l("الهاتف غير متصل الآن — افتح TV Hub على الهاتف", "Phone offline — open Phone app while connected"),
                                    Toast.LENGTH_LONG).show();
                            if (ok) main.postDelayed(() -> { if (!isFinishing()) render(); }, 1200);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int defaultSize() {
        try { return getResources().getInteger(R.integer.player_pref_subtitle_size_default); }
        catch (Exception ignored) { return 36; }
    }

    private int defaultVpos() {
        try { return getResources().getInteger(R.integer.player_pref_subtitle_vpos_default); }
        catch (Exception ignored) { return 64; }
    }

    private int defaultColor() {
        try { return Color.parseColor(getString(R.string.subtitle_color_default)); }
        catch (Exception ignored) { return Color.WHITE; }
    }

    private String colorName(int color) {
        if (color == Color.YELLOW) return l("أصفر", "Yellow");
        if (color == 0xff00e5ff || color == Color.CYAN) return l("سماوي", "Cyan");
        if (color == 0xffe5b94c) return l("ذهبي", "Gold");
        return l("أبيض", "White");
    }

    private String appearanceSummary() {
        int size = prefs.getInt(PlayerActivity.KEY_SUBTITLE_SIZE, defaultSize());
        int vpos = prefs.getInt(PlayerActivity.KEY_SUBTITLE_VPOS, defaultVpos());
        int color = prefs.getInt(PlayerActivity.KEY_SUBTITLE_COLOR, defaultColor());
        boolean outline = prefs.getBoolean(PlayerActivity.KEY_SUBTITLE_OUTLINE, false);
        return l("الحجم: ", "Size: ") + size
                + " · " + l("اللون: ", "Color: ") + colorName(color)
                + " · " + l("الموضع: ", "Position: ") + vpos
                + " · " + l("خلفية/حدود: ", "Outline: ") + (outline ? l("نعم", "On") : l("تلقائي", "Auto"));
    }

    private void openAppearanceDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 8));

        TextView preview = text(this, 16, true);
        preview.setText(l("معاينة سريعة للترجمة العربية · Sample Subtitle", "Sample Subtitle · معاينة سريعة"));
        preview.setGravity(android.view.Gravity.CENTER);
        preview.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        body.addView(preview);

        int[] sizeHolder = {prefs.getInt(PlayerActivity.KEY_SUBTITLE_SIZE, defaultSize())};
        int[] vposHolder = {prefs.getInt(PlayerActivity.KEY_SUBTITLE_VPOS, defaultVpos())};
        int[] colorHolder = {prefs.getInt(PlayerActivity.KEY_SUBTITLE_COLOR, defaultColor())};
        boolean[] outlineHolder = {prefs.getBoolean(PlayerActivity.KEY_SUBTITLE_OUTLINE, false)};

        Runnable syncPreview = () -> {
            prefs.edit()
                    .putInt(PlayerActivity.KEY_SUBTITLE_SIZE, sizeHolder[0])
                    .putInt(PlayerActivity.KEY_SUBTITLE_VPOS, vposHolder[0])
                    .putInt(PlayerActivity.KEY_SUBTITLE_COLOR, colorHolder[0])
                    .putBoolean(PlayerActivity.KEY_SUBTITLE_OUTLINE, outlineHolder[0])
                    .apply();
            preview.setTextColor(colorHolder[0]);
            preview.setBackground(rounded(outlineHolder[0] ? 0xbb000000 : 0xff16171b, dp(this, 8), 0xff34322c));
            preview.setTextSize(Math.max(14f, Math.min(26f, 14f + (sizeHolder[0] * 0.14f))));
        };
        syncPreview.run();

        Runnable[] refreshDlg = {null};
        Runnable rebuild = () -> {
            if (refreshDlg[0] != null) refreshDlg[0].run();
            render();
        };

        LinearLayout sizeCard = settingsCard(this, body, R.drawable.cinema_subtitles,
                l("حجم الخط (كبّر / صغّر)", "Font Size (+ / -)"), "", null, null, () -> {
                    sizeHolder[0] = sizeHolder[0] >= 72 ? 22 : sizeHolder[0] + 6;
                    syncPreview.run();
                    rebuild.run();
                });
        LinearLayout posCard = settingsCard(this, body, R.drawable.cinema_ic_appearance,
                l("الموضع العمودي للترجمة", "Vertical Position"), "", null, null, () -> {
                    vposHolder[0] = vposHolder[0] >= 160 ? 24 : vposHolder[0] + 16;
                    syncPreview.run();
                    rebuild.run();
                });
        int[] colors = {Color.WHITE, Color.YELLOW, 0xff00e5ff, 0xffe5b94c};
        LinearLayout colorCard = settingsCard(this, body, R.drawable.cinema_ic_appearance,
                l("لون نص الترجمة", "Subtitle Text Color"), "", null, null, () -> {
                    int next = 0;
                    for (int i = 0; i < colors.length; i++) {
                        if (colors[i] == colorHolder[0]) {
                            next = (i + 1) % colors.length;
                            break;
                        }
                    }
                    colorHolder[0] = colors[next];
                    syncPreview.run();
                    rebuild.run();
                });
        LinearLayout outlineCard = settingsCard(this, body, R.drawable.cinema_subtitles,
                l("حدود وإطار داكن للترجمة", "Outline & Dark Box"), "", null, null, () -> {
                    outlineHolder[0] = !outlineHolder[0];
                    syncPreview.run();
                    rebuild.run();
                });

        refreshDlg[0] = () -> {
            updateSecondLine(sizeCard, sizeHolder[0] + " pt (" + l("اضغط للتغيير", "Tap to cycle") + ")");
            updateSecondLine(posCard, vposHolder[0] + " dp (" + l("اضغط للرفع", "Tap to cycle height") + ")");
            updateSecondLine(colorCard, colorName(colorHolder[0]));
            updateSecondLine(outlineCard, outlineHolder[0]
                    ? l("خلفية وحدود داكنة مفعّلة ✓", "Dark box & outline ON ✓")
                    : l("حدود شفافة قياسية", "Standard transparent outline"));
        };
        refreshDlg[0].run();

        ScrollView dlgScroll = new ScrollView(this);
        dlgScroll.addView(body);
        dialog(this)
                .setTitle(l("مظهر وموضع الترجمة", "Subtitle Appearance & Position"))
                .setView(dlgScroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> render())
                .show();
    }

    private void updateSecondLine(LinearLayout card, String text) {
        if (card == null || card.getChildCount() < 2) return;
        if (card.getChildAt(1) instanceof LinearLayout col) {
            if (col.getChildCount() >= 2 && col.getChildAt(1) instanceof TextView sub) {
                sub.setText(text);
            } else {
                TextView sub = text(this, CinemaTokens.CAPTION, false);
                sub.setText(text);
                sub.setTextColor(0xffd4b55b);
                col.addView(sub);
            }
        }
    }

    private void runConnectionTest() {
        Toast.makeText(this,
                l("جاري اختبار اتصال مصادر الترجمة...", "Testing subtitle providers..."),
                Toast.LENGTH_SHORT).show();
        final long startMs = System.currentTimeMillis();
        final String lang = CinemaAutoSubtitles.preferredLanguage(this);
        io.execute(() -> {
            try {
                var results = ZeroConfigSubtitles.search("tt0133093", false, 0, 0, lang);
                long took = System.currentTimeMillis() - startMs;
                boolean ok = results != null && !results.isEmpty();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lastTestStatus = ok
                            ? l("متصل ✓ · عُثر على " + results.size() + " ترجمة تجريبية خلال " + took + " ms",
                                "Connected ✓ · Found " + results.size() + " sample subtitles in " + took + " ms")
                            : l("متصل ولكن لا توجد نتائج للغة المختارة (" + took + " ms)",
                                "Reachable · No results returned for sample query (" + took + " ms)");
                    render();
                    Toast.makeText(this, lastTestStatus, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lastTestStatus = l("فشل الفحص: ", "Connection error: ")
                            + (e.getMessage() != null ? e.getMessage() : "Network error");
                    render();
                    Toast.makeText(this, lastTestStatus, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
