package com.archos.mediacenter.video.leanback.cinema;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.meedx.providers.ApiKeyStore;
import com.meedx.providers.ProviderKeys;
import com.meedx.providers.StremioAddons;
import com.meedx.providers.UnifiedSubtitleConfig;
import com.meedx.providers.UnifiedSubtitleEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/**
 * Single Central Unified Subtitle Settings screen on TV:
 * - Clean collapsed dropdown for ALL built-in open-source & optional-key subtitle sources + Stremio add-ons.
 * - Live per-source testing & optional API key entry (auto-synced to Phone).
 * - Dedicated Subtitle Language Priority (Endonyms only — Audio language lives exclusively in Audio settings).
 * - Dual Subtitles & Secondary Subtitle Language controls cleanly grouped together.
 * - Flicker-free default Appearance & Timing dialog with localized RTL/LTR units & live bilingual preview.
 * - Bidirectional TV <-> Phone sync status & instant sync action.
 */
public class SubsourceSettingsActivity extends LeanbackActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private ScrollView scrollView;
    private final Map<String, String> liveTestResults = new HashMap<>();

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!isFinishing() && !isDestroyed()) render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CinemaSubtitleSyncBridge.ensureBootstrapped(this);
        ContextCompat.registerReceiver(this, syncReceiver,
                new IntentFilter(CinemaLinkServer.ACTION_API_SYNC_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(syncReceiver); } catch (Exception ignored) {}
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private String l(String ar, String en) { return label(this, ar, en); }

    private void render() {
        int focusIdx = -1;
        if (content != null) {
            View focused = getCurrentFocus();
            if (focused != null && focused.getParent() == content) {
                focusIdx = content.indexOfChild(focused);
            }
        }
        int scrollY = scrollView != null ? scrollView.getScrollY() : 0;
        boolean rtl = arabic(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(surface());
        scroll.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scrollView = scroll;

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(this, CinemaTokens.UTILITY_GUTTER), dp(this, CinemaTokens.SAFE),
                dp(this, CinemaTokens.UTILITY_GUTTER), dp(this, CinemaTokens.SAFE));
        scroll.addView(content);
        setContentView(scroll);

        TextView header = text(this, CinemaTokens.PAGE_TITLE, true);
        header.setText(l("إعدادات الترجمة الموحّدة", "Unified Subtitle Settings"));
        header.setPadding(0, 0, 0, dp(this, 6));
        content.addView(header);

        TextView desc = text(this, CinemaTokens.LABEL, false);
        desc.setTextColor(0xffb8b8b8);
        desc.setText(l(
                "إدارة مصادر وإضافات الترجمة المفتوحة والاحترافية، أولوية اللغات، الترجمة المزدوجة، المظهر، والمزامنة الثنائية مع الهاتف.",
                "Manage open-source & API subtitle providers, language priority, dual subs, appearance, and TV ↔ Phone sync."));
        desc.setPadding(0, 0, 0, dp(this, 12));
        content.addView(desc);

        // ---- SECTION 1: Collapsed Dropdown for All Subtitle Sources & Add-ons ----
        sectionHeader(l("1. مصادر وإضافات الترجمة المدمجة والمفتوحة", "1. Built-in, Open-Source & Add-on Subtitle Sources"));
        int activeCount = UnifiedSubtitleConfig.runnableSources(this, UnifiedSubtitleConfig.enabledSources(this)).size();
        int addonCount = countInstalledSubtitleAddons();
        sourcesDropdownCard(
                getString(R.string.cinema_sub_sources_dropdown_title, activeCount),
                getString(R.string.cinema_sub_sources_dropdown_hint, addonCount),
                () -> openAllSourcesDropdownDialog(0));

        // ---- SECTION 2: Preferred Subtitle Language, Auto-Select Behavior & Dual Subtitles ----
        sectionHeader(l("2. لغة الترجمة وسلوك الاختيار التلقائي والترجمة المزدوجة",
                "2. Subtitle Language, Auto-Select & Dual Subtitles"));

        settingsCard(this, content, R.drawable.cinema_globe,
                getString(R.string.cinema_pref_subtitle_lang_title),
                CinemaLanguagePreferences.localizedSubtitleLabel(this, CinemaLanguagePreferences.primarySubtitleLanguage(this)),
                null, null, () -> CinemaLanguagePreferences.choosePreferredSubtitleLanguage(this, this::render));

        int prefMode = UnifiedSubtitleConfig.preferredSubtitleMode(this);
        settingsCard(this, content, R.drawable.cinema_subtitles,
                getString(R.string.cinema_sub_default_mode_title),
                UnifiedSubtitleConfig.preferredSubtitleModeLabel(prefMode, rtl),
                null, null, () -> CinemaLanguagePreferences.choosePreferredSubtitleMode(this, this::render));

        int autoPriority = UnifiedSubtitleConfig.autoSelectPriority(this);
        settingsCard(this, content, R.drawable.cinema_subtitles,
                getString(R.string.cinema_sub_auto_priority_title),
                UnifiedSubtitleConfig.autoSelectPriorityLabel(autoPriority, rtl),
                null, null, this::openAutoPriorityDialog);

        boolean autoOn = UnifiedSubtitleConfig.autoSubtitlesEnabled(this);
        settingsCard(this, content, R.drawable.cinema_globe,
                getString(R.string.cinema_sub_auto_online_fallback_title),
                getString(autoOn ? R.string.cinema_sub_auto_online_fallback_on : R.string.cinema_sub_auto_online_fallback_off),
                autoOn, null, () -> {
                    UnifiedSubtitleConfig.setAutoSubtitlesEnabled(this, !autoOn);
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
                    render();
                });

        boolean dualOn = UnifiedSubtitleConfig.dualSubtitlesEnabled(this);
        int dualPos = UnifiedSubtitleConfig.dualSubPosition(this);
        String dualPosLabel = switch (dualPos) {
            case 0 -> l("الموضع: كلاهما بالأسفل", "Position: Both at Bottom");
            case 2 -> l("الموضع: الأساسية أعلى · الثانوية أسفل", "Position: Primary Top · Secondary Bottom");
            default -> l("الموضع: الأساسية أسفل · الثانوية أعلى", "Position: Primary Bottom · Secondary Top");
        };
        settingsCard(this, content, R.drawable.cinema_subtitles,
                l("الترجمة المزدوجة الافتراضية", "Default Dual Subtitles"),
                dualPosLabel + l(" · اضغط للتحكم بالتفعيل والموضع", " · Tap to toggle or change position"),
                dualOn, null, this::openDualSettingsDialog);

        String secLangLabel = CinemaLanguagePreferences.localizedSubtitleLabel(
                this, CinemaLanguagePreferences.secondarySubtitleLanguage(this));
        settingsCard(this, content, R.drawable.cinema_globe,
                getString(R.string.cinema_pref_secondary_sub_lang),
                secLangLabel,
                null, null, () -> CinemaLanguagePreferences.chooseSecondaryLanguage(this, chosen -> {
                    CinemaLanguagePreferences.setSecondarySubtitleLanguage(this, chosen);
                    render();
                }));

        // ---- SECTION 3: Default Appearance & Timing ----
        sectionHeader(l("3. المظهر والخط والموضع الافتراضي", "3. Default Appearance & Timing"));
        settingsCard(this, content, R.drawable.cinema_ic_appearance,
                l("مظهر وموضع وتوقيت الترجمة", "Subtitle Appearance, Position & Timing"),
                appearanceSummary(),
                null, null, this::openAppearanceDialog);

        // ---- SECTION 4: Bidirectional TV <-> Phone Sync ----
        sectionHeader(l("4. المزامنة الثنائية مع الهاتف", "4. Bidirectional TV ↔ Phone Sync"));
        boolean phoneOnline = CinemaLinkServer.hasConnectedPhone();
        settingsCard(this, content, R.drawable.cinema_ic_shield,
                l("مزامنة إعدادات ومصادر الترجمة مع الهاتف الآن", "Sync Subtitle Settings & Sources with Phone Now"),
                phoneOnline
                        ? l("الهاتف متصل ✓ · أي تعديل هنا أو في الهاتف يتزامن تلقائياً", "Phone connected ✓ · Changes auto-sync both ways")
                        : l("الهاتف غير متصل حالياً — سيتزامن تلقائياً فور فتح تطبيق الهاتف", "Phone offline — will sync automatically upon connection"),
                null, null, () -> {
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
                    boolean pulled = CinemaLinkServer.requestPhoneFullSync();
                    Toast.makeText(this, pulled
                            ? l("تمت مزامنة إعدادات ومفاتيح الترجمة مع الهاتف ✓", "Synced subtitle settings & keys with Phone ✓")
                            : l("تم الحفظ محلياً · افتح تطبيق الهاتف لإتمام المزامنة", "Saved locally · Open Phone app to complete sync"),
                            Toast.LENGTH_LONG).show();
                    if (pulled) main.postDelayed(() -> { if (!isFinishing()) render(); }, 1200);
                });

        final int restoreIdx = focusIdx;
        content.post(() -> {
            if (isFinishing() || content == null) return;
            if (scrollY > 0) scroll.scrollTo(0, scrollY);
            if (restoreIdx >= 0 && restoreIdx < content.getChildCount() && content.getChildAt(restoreIdx).isFocusable()) {
                content.getChildAt(restoreIdx).requestFocus();
            } else {
                for (int i = 0; i < content.getChildCount(); i++) {
                    if (content.getChildAt(i).isFocusable()) {
                        content.getChildAt(i).requestFocus();
                        break;
                    }
                }
            }
        });
    }

    private void sourcesDropdownCard(String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(this, 18), dp(this, 14), dp(this, 18), dp(this, 14));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.cinema_globe);
        icon.setColorFilter(CinemaTokens.GOLD);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24));
        iconLp.setMarginEnd(dp(this, 14));
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(this, CinemaTokens.BODY, true);
        titleTv.setText(title);
        titleTv.setMaxLines(2);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv);

        TextView subTv = text(this, CinemaTokens.CAPTION, false);
        subTv.setText(subtitle);
        subTv.setMaxLines(2);
        subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subTv.setPadding(0, dp(this, 3), 0, 0);
        textCol.addView(subTv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrowBadge = text(this, 16, true);
        arrowBadge.setText("▾");
        arrowBadge.setGravity(Gravity.CENTER);
        arrowBadge.setPadding(dp(this, 11), dp(this, 5), dp(this, 11), dp(this, 5));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(-2, -2);
        arrowLp.setMarginStart(dp(this, 10));
        row.addView(arrowBadge, arrowLp);

        Runnable styleRest = () -> {
            row.setBackground(rounded(0xff201e18, dp(this, 10), 0x88E5A93B));
            titleTv.setTextColor(CinemaTokens.TEXT);
            subTv.setTextColor(0xffd4b55b);
            icon.setColorFilter(CinemaTokens.GOLD);
            arrowBadge.setTextColor(CinemaTokens.GOLD);
            arrowBadge.setBackground(rounded(0xff29261d, dp(this, 8), 0x66E5A93B));
        };
        styleRest.run();

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(this, 10), CinemaTokens.FOCUS));
                titleTv.setTextColor(0xff141517);
                subTv.setTextColor(0xff2c240d);
                icon.setColorFilter(0xff141517);
                arrowBadge.setTextColor(0xff141517);
                arrowBadge.setBackground(rounded(0x33141517, dp(this, 8), 0));
            } else {
                styleRest.run();
            }
        });
        row.setOnClickListener(v -> { if (action != null) action.run(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 10);
        content.addView(row, lp);
    }

    /** Clean scrollable dropdown dialog listing all built-in, API-key, and Stremio subtitle sources. */
    private void openAllSourcesDropdownDialog(int focusProviderIdx) {
        if (isFinishing() || isDestroyed()) return;
        boolean rtl = arabic(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout listBody = new LinearLayout(this);
        listBody.setOrientation(LinearLayout.VERTICAL);
        listBody.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        listBody.setClipChildren(false);
        listBody.setClipToPadding(false);
        listBody.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 14));
        scroll.addView(listBody);

        final AlertDialog[] dlgRef = {null};
        final List<UnifiedSubtitleConfig.ProviderInfo> providers = UnifiedSubtitleConfig.allProviders();
        final View[] initialFocus = {null};

        for (int i = 0; i < providers.size(); i++) {
            final int idx = i;
            final UnifiedSubtitleConfig.ProviderInfo info = providers.get(i);
            boolean enabled = UnifiedSubtitleConfig.isSourceEnabled(this, info.id);
            boolean hasKey = UnifiedSubtitleConfig.hasKey(this, info);
            StringBuilder sub = new StringBuilder(rtl ? info.arType : info.enType);
            if (!info.keyless) {
                sub.append(hasKey
                        ? l(" · المفتاح محفوظ ✓", " · API Key Saved ✓")
                        : l(" · بدون مفتاح (اضغط للإضافة)", " · No Key (Tap to Add)"));
            } else if (UnifiedSubtitleConfig.SRC_STREMIO_ADDONS.equals(info.id)) {
                sub.append(l(" · المثبّتة حالياً: ", " · Installed: ")).append(countInstalledSubtitleAddons());
            }
            String testNote = liveTestResults.get(info.id);
            if (testNote != null && !testNote.isBlank()) {
                sub.append(" · ").append(testNote);
            }
            int iconRes = info.keyless ? R.drawable.cinema_globe : R.drawable.cinema_ic_addons;
            LinearLayout card = settingsCard(this, listBody, iconRes, info.name, sub.toString(),
                    enabled && hasKey, null, () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        openProviderMenu(info, () -> openAllSourcesDropdownDialog(idx));
                    });
            if (i == focusProviderIdx || initialFocus[0] == null) {
                initialFocus[0] = card;
            }
        }

        int activeCount = UnifiedSubtitleConfig.runnableSources(this, UnifiedSubtitleConfig.enabledSources(this)).size();
        AlertDialog dlg = dialog(this)
                .setTitle(getString(R.string.cinema_sub_sources_dropdown_title, activeCount))
                .setView(scroll)
                .setNegativeButton(l("إغلاق", "Close"), null)
                .create();
        dlgRef[0] = dlg;
        dlg.setOnShowListener(d -> {
            widenSubtitleDialog(dlg.getWindow());
            if (initialFocus[0] != null) {
                initialFocus[0].requestFocus();
                scroll.post(() -> {
                    int y = Math.max(0, initialFocus[0].getTop() - dp(this, 48));
                    scroll.scrollTo(0, y);
                });
            }
        });
        dlg.show();
    }

    private int countInstalledSubtitleAddons() {
        int c = 0;
        try {
            for (StremioAddons.Addon a : StremioAddons.list(this)) {
                if (a != null && a.enabled && StremioAddons.providesSubtitles(a)) c++;
            }
        } catch (Exception ignored) {}
        return c;
    }

    private void openProviderMenu(UnifiedSubtitleConfig.ProviderInfo info, Runnable reopenDropdown) {
        boolean enabled = UnifiedSubtitleConfig.isSourceEnabled(this, info.id);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(enabled ? l("إيقاف هذا المصدر", "Disable this source") : l("تفعيل هذا المصدر", "Enable this source"));
        actions.add(() -> {
            UnifiedSubtitleConfig.setSourceEnabled(this, info.id, !enabled);
            CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
            render();
            if (reopenDropdown != null) reopenDropdown.run();
        });

        labels.add(l("اختبار اتصال المصدر حياً الآن", "Test Live Connection Now"));
        actions.add(() -> runSingleProviderTest(info, reopenDropdown));

        if (!info.keyless && info.apiKeyName != null) {
            boolean hasKey = UnifiedSubtitleConfig.hasKey(this, info);
            labels.add(hasKey ? l("تعديل أو مسح مفتاح API", "Edit or Clear API Key")
                    : l("إضافة مفتاح API", "Add API Key"));
            actions.add(() -> promptApiKeyDialog(info, reopenDropdown));
        }

        if (UnifiedSubtitleConfig.SRC_STREMIO_ADDONS.equals(info.id)) {
            labels.add(l("فتح إدارة إضافات Stremio", "Manage Stremio Add-ons"));
            actions.add(() -> new CinemaAddonsManagerDialog(this, io, main, () -> {
                render();
                if (reopenDropdown != null) reopenDropdown.run();
            }).show());
        }

        dialog(this)
                .setTitle(info.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .setNegativeButton(R.string.addon_back, (d, w) -> {
                    if (reopenDropdown != null) reopenDropdown.run();
                })
                .show();
    }

    private void runSingleProviderTest(UnifiedSubtitleConfig.ProviderInfo info, Runnable reopenDropdown) {
        Toast.makeText(this, l("جارٍ اختبار ", "Testing ") + info.name + "…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            UnifiedSubtitleEngine.ProviderTestResult res = UnifiedSubtitleEngine.testProvider(getApplicationContext(), info.id);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                String msg = arabic(this) ? res.arMessage : res.enMessage;
                liveTestResults.put(info.id, msg);
                render();
                Toast.makeText(this, info.name + ": " + msg, Toast.LENGTH_LONG).show();
                if (reopenDropdown != null) reopenDropdown.run();
            });
        });
    }

    private void promptApiKeyDialog(UnifiedSubtitleConfig.ProviderInfo info, Runnable reopenDropdown) {
        EditText input = new EditText(this);
        input.setHint(l("ألصق مفتاح API هنا (اتركه فارغاً للمسح)", "Paste API Key here (leave blank to clear)"));
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff99a3ad);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        String existing = ProviderKeys.get(this, info.apiKeyName);
        if (!existing.isBlank()) input.setText(existing);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 18), dp(this, 10), dp(this, 18), dp(this, 6));
        box.addView(CinemaPhoneLink.pasteBadge(this));
        box.addView(input);

        dialog(this)
                .setTitle(info.name + " · " + l("مفتاح API", "API Key"))
                .setView(box)
                .setPositiveButton(l("حفظ ومزامنة", "Save & Sync"), (d, w) -> {
                    String val = input.getText().toString().trim();
                    ProviderKeys.save(this, info.apiKeyName, val);
                    if (UnifiedSubtitleConfig.SRC_SUBSOURCE.equals(info.id)) {
                        try {
                            ApiKeyStore store = new ApiKeyStore(this, "subsource");
                            store.save(val.isEmpty() ? Collections.emptyList() : List.of(val));
                        } catch (Exception ignored) {}
                    }
                    if (!val.isEmpty()) UnifiedSubtitleConfig.setSourceEnabled(this, info.id, true);
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
                    CinemaLinkServer.broadcastApiKeys(this);
                    render();
                    if (reopenDropdown != null) reopenDropdown.run();
                })
                .setNegativeButton(R.string.addon_back, (d, w) -> {
                    if (reopenDropdown != null) reopenDropdown.run();
                })
                .show();
    }

    private void openAutoPriorityDialog() {
        boolean rtl = arabic(this);
        int cur = UnifiedSubtitleConfig.autoSelectPriority(this);
        String[] choices = {
                (cur == UnifiedSubtitleConfig.AUTO_PRIORITY_SOURCE_FIRST ? "✓ " : "  ")
                        + UnifiedSubtitleConfig.autoSelectPriorityLabel(UnifiedSubtitleConfig.AUTO_PRIORITY_SOURCE_FIRST, rtl)
                        + l(" (افتراضي)", " (Default)"),
                (cur == UnifiedSubtitleConfig.AUTO_PRIORITY_EMBEDDED_FIRST ? "✓ " : "  ")
                        + UnifiedSubtitleConfig.autoSelectPriorityLabel(UnifiedSubtitleConfig.AUTO_PRIORITY_EMBEDDED_FIRST, rtl),
                (cur == UnifiedSubtitleConfig.AUTO_PRIORITY_ONLINE_DIRECT ? "✓ " : "  ")
                        + UnifiedSubtitleConfig.autoSelectPriorityLabel(UnifiedSubtitleConfig.AUTO_PRIORITY_ONLINE_DIRECT, rtl)
        };
        dialog(this)
                .setTitle(R.string.cinema_sub_auto_priority_title)
                .setItems(choices, (d, which) -> {
                    UnifiedSubtitleConfig.setAutoSelectPriority(this, which);
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
                    render();
                })
                .setNegativeButton(R.string.addon_back, null)
                .show();
    }

    private void openDualSettingsDialog() {
        boolean dualOn = UnifiedSubtitleConfig.dualSubtitlesEnabled(this);
        int pos = UnifiedSubtitleConfig.dualSubPosition(this);
        String[] choices = {
                (dualOn ? "✓ " : "○ ") + l("تبديل تفعيل الترجمة المزدوجة: ", "Toggle Dual Subtitles: ")
                        + (dualOn ? l("مفعّلة", "ON") : l("متوقفة", "OFF")),
                (pos == 1 ? "✓ " : "  ") + l("الموضع: الأساسية أسفل · الثانوية أعلى (افتراضي)", "Split: Primary Bottom · Secondary Top"),
                (pos == 2 ? "✓ " : "  ") + l("الموضع: الأساسية أعلى · الثانوية أسفل", "Split: Primary Top · Secondary Bottom"),
                (pos == 0 ? "✓ " : "  ") + l("الموضع: كلاهما بالأسفل فوق بعضهما", "Stacked: Both at Bottom")
        };
        dialog(this)
                .setTitle(l("إعدادات الترجمة المزدوجة", "Dual Subtitle Settings"))
                .setItems(choices, (d, w) -> {
                    switch (w) {
                        case 0 -> UnifiedSubtitleConfig.setDualSubtitlesEnabled(this, !dualOn);
                        case 1 -> UnifiedSubtitleConfig.setDualSubPosition(this, 1);
                        case 2 -> UnifiedSubtitleConfig.setDualSubPosition(this, 2);
                        case 3 -> UnifiedSubtitleConfig.setDualSubPosition(this, 0);
                    }
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
                    render();
                })
                .setNegativeButton(R.string.addon_back, null)
                .show();
    }

    private void sectionHeader(String title) {
        TextView header = text(this, 14, true);
        header.setText(title);
        header.setTextColor(CinemaTokens.GOLD);
        header.setFocusable(false);
        header.setClickable(false);
        header.setPaddingRelative(dp(this, 6), dp(this, 14), dp(this, 6), dp(this, 6));
        content.addView(header);
    }

    private String colorName(int color) {
        if (color == Color.YELLOW || color == 0xffffd54f) return l("أصفر", "Yellow");
        if (color == 0xff00e5ff || color == 0xff4dd0e1 || color == Color.CYAN) return l("سماوي", "Cyan");
        if (color == 0xff81c784 || color == Color.GREEN) return l("أخضر", "Green");
        if (color == 0xffe5b94c) return l("ذهبي", "Gold");
        return l("أبيض", "White");
    }

    private String appearanceSummary() {
        int size = UnifiedSubtitleConfig.textSize(this);
        int vpos = UnifiedSubtitleConfig.vpos(this);
        int color = UnifiedSubtitleConfig.textColor(this);
        boolean outline = UnifiedSubtitleConfig.opaqueBg(this);
        long delay = UnifiedSubtitleConfig.defaultDelayMs(this);
        return l("الحجم: ", "Size: ") + size
                + " · " + l("اللون: ", "Color: ") + colorName(color)
                + " · " + l("الموضع: ", "Position: ") + vpos
                + " · " + l("الخلفية: ", "BG: ") + (outline ? l("داكنة", "Dark") : l("شفافة", "Transparent"))
                + (delay != 0L ? " · " + CinemaSubtitleTimingOverlay.formatAudioDelayMs(this, delay) : "");
    }

    private void openAppearanceDialog() {
        boolean rtl = arabic(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));

        // Compact bilingual Live Preview Box (Arabic + English)
        LinearLayout previewFrame = new LinearLayout(this);
        previewFrame.setOrientation(LinearLayout.VERTICAL);
        previewFrame.setGravity(Gravity.CENTER);
        previewFrame.setBackground(rounded(0xff0d0e11, dp(this, 10), 0xff4a4332));
        previewFrame.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, -2);
        previewLp.bottomMargin = dp(this, 14);

        LinearLayout previewPill = new LinearLayout(this);
        previewPill.setOrientation(LinearLayout.VERTICAL);
        previewPill.setGravity(Gravity.CENTER);
        previewPill.setPadding(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));

        TextView previewAr = text(this, 16, true);
        previewAr.setText(getString(R.string.cinema_sub_preview_ar));
        previewAr.setGravity(Gravity.CENTER);
        previewPill.addView(previewAr);

        TextView previewEn = text(this, 14, true);
        previewEn.setText(getString(R.string.cinema_sub_preview_en));
        previewEn.setGravity(Gravity.CENTER);
        previewEn.setPadding(0, dp(this, 3), 0, 0);
        previewPill.addView(previewEn);

        previewFrame.addView(previewPill, new LinearLayout.LayoutParams(-2, -2));
        body.addView(previewFrame, previewLp);

        int[] sizeHolder = {UnifiedSubtitleConfig.textSize(this)};
        int[] vposHolder = {UnifiedSubtitleConfig.vpos(this)};
        int[] colorHolder = {UnifiedSubtitleConfig.textColor(this)};
        boolean[] outlineHolder = {UnifiedSubtitleConfig.opaqueBg(this)};
        long[] delayHolder = {UnifiedSubtitleConfig.defaultDelayMs(this)};

        Runnable syncPreview = () -> {
            UnifiedSubtitleConfig.saveAppearance(this, sizeHolder[0], vposHolder[0], colorHolder[0],
                    outlineHolder[0], delayHolder[0]);
            CinemaSubtitleSyncBridge.applyUnifiedToTvPrefs(this);
            float arSp = Math.max(13f, Math.min(24f, 13f + (sizeHolder[0] - 18) * 0.21f));
            previewAr.setTextSize(arSp);
            previewEn.setTextSize(Math.max(12f, arSp - 1.5f));
            previewAr.setTextColor(colorHolder[0]);
            previewEn.setTextColor(colorHolder[0]);
            if (outlineHolder[0]) {
                previewPill.setBackground(rounded(0xcc000000, dp(this, 8), 0x44ffffff));
                previewAr.setShadowLayer(1.5f, 0f, 1f, 0xaa000000);
                previewEn.setShadowLayer(1.5f, 0f, 1f, 0xaa000000);
            } else {
                previewPill.setBackground(null);
                previewAr.setShadowLayer(3.5f, 0f, 1.5f, 0xf0000000);
                previewEn.setShadowLayer(3.5f, 0f, 1.5f, 0xf0000000);
            }
        };
        syncPreview.run();

        Runnable[] refreshDlg = {null};
        Runnable updateDialogOnly = () -> {
            syncPreview.run();
            if (refreshDlg[0] != null) refreshDlg[0].run();
        };

        LinearLayout sizeCard = settingsCard(this, body, R.drawable.cinema_subtitles,
                l("حجم الخط (كبّر / صغّر)", "Font Size (+ / -)"),
                "", null, null, () -> {
                    sizeHolder[0] = sizeHolder[0] >= 72 ? 22 : sizeHolder[0] + 6;
                    updateDialogOnly.run();
                });
        LinearLayout posCard = settingsCard(this, body, R.drawable.cinema_ic_appearance,
                l("الموضع العمودي للترجمة", "Vertical Position"),
                "", null, null, () -> {
                    vposHolder[0] = vposHolder[0] >= 160 ? 24 : vposHolder[0] + 16;
                    updateDialogOnly.run();
                });
        int[] colors = {Color.WHITE, 0xffffd54f, 0xff4dd0e1, 0xff81c784, 0xffe5b94c};
        LinearLayout colorCard = settingsCard(this, body, R.drawable.cinema_ic_appearance,
                l("لون نص الترجمة", "Subtitle Text Color"),
                colorName(colorHolder[0]), null, null, () -> {
                    int next = 0;
                    for (int i = 0; i < colors.length; i++) {
                        if (colors[i] == colorHolder[0]) {
                            next = (i + 1) % colors.length;
                            break;
                        }
                    }
                    colorHolder[0] = colors[next];
                    updateDialogOnly.run();
                });
        LinearLayout outlineCard = settingsCard(this, body, R.drawable.cinema_subtitles,
                l("خلفية وحدود داكنة للترجمة", "Dark Background & Outline"),
                "", null, null, () -> {
                    outlineHolder[0] = !outlineHolder[0];
                    updateDialogOnly.run();
                });
        LinearLayout delayCard = settingsCard(this, body, R.drawable.cinema_ic_timer,
                l("التوقيت الافتراضي (تقديم / تأخير)", "Default Timing Offset"),
                "", null, null, () -> {
                    delayHolder[0] = delayHolder[0] >= 1000L ? -1000L : delayHolder[0] + 250L;
                    updateDialogOnly.run();
                });
        settingsCard(this, body, R.drawable.cinema_ic_updates,
                l("استعادة المظهر الافتراضي", "Reset Default Appearance"),
                l("إرجاع الحجم والموضع واللون والخلفية والتوقيت للافتراضي", "Restore default size, position, color, BG & sync"),
                null, null, () -> {
                    sizeHolder[0] = 36;
                    vposHolder[0] = 64;
                    colorHolder[0] = Color.WHITE;
                    outlineHolder[0] = false;
                    delayHolder[0] = 0L;
                    updateDialogOnly.run();
                });

        refreshDlg[0] = () -> {
            updateSettingsCardSubtitle(sizeCard, sizeHolder[0] + " " + l("نقطة (اضغط للتغيير)", "pt (Tap to cycle)"));
            updateSettingsCardSubtitle(posCard, vposHolder[0] + " " + l("نقطة (اضغط للرفع)", "dp (Tap to cycle height)"));
            updateSettingsCardSubtitle(colorCard, colorName(colorHolder[0]));
            updateSettingsCardSubtitle(outlineCard, outlineHolder[0]
                    ? l("خلفية وحدود داكنة مفعّلة ✓", "Dark box & outline ON ✓")
                    : l("حدود شفافة قياسية", "Standard transparent outline"));
            updateSettingsCardSubtitle(delayCard, CinemaSubtitleTimingOverlay.formatAudioDelayMs(this, delayHolder[0]));
        };
        refreshDlg[0].run();

        ScrollView dlgScroll = new ScrollView(this);
        dlgScroll.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        dlgScroll.setClipChildren(false);
        dlgScroll.setClipToPadding(false);
        dlgScroll.addView(body);

        AlertDialog dlg = dialog(this)
                .setTitle(l("مظهر وموضع وتوقيت الترجمة", "Subtitle Appearance & Timing"))
                .setView(dlgScroll)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> {
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
                    if (!isFinishing() && !isDestroyed()) render();
                })
                .create();
        dlg.setOnShowListener(d -> {
            widenSubtitleDialog(dlg.getWindow());
            sizeCard.requestFocus();
        });
        dlg.show();
    }
}
