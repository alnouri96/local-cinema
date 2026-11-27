package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.archos.mediacenter.video.R;
import com.meedx.providers.UnifiedSubtitleConfig;

import java.util.ArrayList;
import java.util.List;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/**
 * Single Unified TV Subtitle Hub used consistently across:
 * - Native Video Player (PlayerActivity)
 * - VOD HLS / Torrent / Cast Player (CinemaHlsActivity — never Live TV)
 * - Movie Details (CinemaDetailsActivity)
 * - Series & Episode Details (CinemaSeriesActivity)
 * - Add-on Series Episodes (CinemaAddonsActivity)
 *
 * Delegates all Appearance & Timing UI to {@link CinemaSubtitleAppearancePanel} and
 * {@link CinemaSubtitleColorPickerDialog} so Settings, Details, and Players share one identical UI.
 */
public final class CinemaSubtitleHubDialog {

    public static final int MODE_CUSTOM_TRACK = -1;
    public static final int MODE_AUTO = 0;
    public static final int MODE_MOVIE_SOURCE = 1;
    public static final int MODE_ONLINE_NOW = 2;
    public static final int MODE_OFF = 3;

    public record TrackChoice(String label, boolean selected, Runnable onSelect) {}

    public record EmbeddedTrackChoice(String label, String langCanon, boolean selected, Runnable onSelect) {}

    public interface StyleCallback {
        void onAction(String action, int value);
    }

    public static final class Builder {
        final Activity activity;
        String headerTitle;
        final List<TrackChoice> tracks = new ArrayList<>();
        final List<EmbeddedTrackChoice> embeddedTracks = new ArrayList<>();
        String activeSecondaryLabel;
        boolean hasSubtitleModeDropdown;
        int activeSubtitleMode = MODE_CUSTOM_TRACK;
        boolean noMatchYet;
        Runnable onModeAuto;
        Runnable onModeMovieSource;
        Runnable onModeAutoDownloadNow;
        Runnable onDisableSubtitles;
        Runnable onSearchOnline;
        Runnable onPickMovieSource;
        Runnable onPickFromPhone;
        Runnable onPickSecondaryFile;
        Runnable onClearSecondary;
        Runnable onDualToggled;
        Runnable onDualPositionCycled;
        Runnable onOpenLiveTimingOverlay;
        boolean showDualControls = true;
        boolean hasSecondaryActive;
        boolean showLiveAppearanceControls;
        long currentDelayMs;
        int currentSize = -1;
        int currentColor = Color.WHITE;
        boolean currentOpaqueBg;
        int currentBgOpacity = 70;
        int currentOutlineLevel = 0;
        int currentOutlineColor = Color.BLACK;
        boolean currentVisible = true;
        boolean hasExplicitAppearanceState;
        StyleCallback styleCallback;

        public Builder(Activity activity) {
            this.activity = activity;
        }

        public Builder title(String title) { this.headerTitle = title; return this; }

        /** Adds a top-level external or stream subtitle track choice. */
        public Builder addTrack(String label, boolean selected, Runnable onSelect) {
            tracks.add(new TrackChoice(label, selected, onSelect));
            return this;
        }

        /** Adds an internal/embedded subtitle track to be grouped into the clean dropdown submenu. */
        public Builder addEmbeddedTrack(String cleanLabel, String langCanon, boolean selected, Runnable onSelect) {
            embeddedTracks.add(new EmbeddedTrackChoice(cleanLabel, langCanon, selected, onSelect));
            return this;
        }

        /** Configures the smart Subtitle Mode dropdown (Auto-Select, Movie Source, Auto-Download Now, Turn Off). */
        public Builder subtitleMode(int activeMode, Runnable onAuto, Runnable onMovieSource,
                                    Runnable onAutoDownloadNow, Runnable onTurnOff) {
            return subtitleMode(activeMode, false, onAuto, onMovieSource, onAutoDownloadNow, onTurnOff);
        }

        /** Configures the smart Subtitle Mode dropdown with optional no-match-yet hint for Auto-Select. */
        public Builder subtitleMode(int activeMode, boolean noMatchYet, Runnable onAuto, Runnable onMovieSource,
                                    Runnable onAutoDownloadNow, Runnable onTurnOff) {
            this.hasSubtitleModeDropdown = true;
            this.activeSubtitleMode = activeMode;
            this.noMatchYet = noMatchYet;
            this.onModeAuto = onAuto;
            this.onModeMovieSource = onMovieSource;
            this.onModeAutoDownloadNow = onAutoDownloadNow;
            this.onDisableSubtitles = onTurnOff;
            return this;
        }

        /** Shows the currently active secondary (dual) subtitle track in the tracks list with one-click turn-off. */
        public Builder activeSecondaryTrack(String label, Runnable onClear) {
            this.activeSecondaryLabel = label;
            if (label != null && !label.isBlank()) {
                this.hasSecondaryActive = true;
            }
            if (onClear != null) {
                this.onClearSecondary = onClear;
            }
            return this;
        }

        /** Wires the Live Timing Overlay Bar shortcut during playback. */
        public Builder liveTimingShortcut(long delayMs, Runnable onOpenLiveTimingOverlay) {
            this.currentDelayMs = delayMs;
            this.onOpenLiveTimingOverlay = onOpenLiveTimingOverlay;
            return this;
        }

        public Builder onSearchOnline(Runnable r) { this.onSearchOnline = r; return this; }
        public Builder onPickMovieSource(Runnable r) { this.onPickMovieSource = r; return this; }
        public Builder onPickFromPhone(Runnable r) { this.onPickFromPhone = r; return this; }

        public Builder dualControls(boolean show, boolean hasSecondary, Runnable onToggle,
                                    Runnable onCyclePosition, Runnable onPickSecondary, Runnable onClearSecondary) {
            this.showDualControls = show;
            this.hasSecondaryActive = hasSecondary || (this.activeSecondaryLabel != null && !this.activeSecondaryLabel.isBlank());
            this.onDualToggled = onToggle;
            this.onDualPositionCycled = onCyclePosition;
            this.onPickSecondaryFile = onPickSecondary;
            if (onClearSecondary != null) this.onClearSecondary = onClearSecondary;
            return this;
        }

        public Builder liveAppearance(long delayMs, int size, int color, boolean opaqueBg,
                                      boolean visible, StyleCallback callback) {
            return liveAppearance(delayMs, size, color, opaqueBg,
                    UnifiedSubtitleConfig.bgOpacity(activity),
                    UnifiedSubtitleConfig.outlineLevel(activity),
                    UnifiedSubtitleConfig.outlineColor(activity),
                    visible, callback);
        }

        public Builder liveAppearance(long delayMs, int size, int color, boolean opaqueBg,
                                      int bgOpacity, int outlineLevel,
                                      boolean visible, StyleCallback callback) {
            return liveAppearance(delayMs, size, color, opaqueBg, bgOpacity, outlineLevel,
                    UnifiedSubtitleConfig.outlineColor(activity), visible, callback);
        }

        public Builder liveAppearance(long delayMs, int size, int color, boolean opaqueBg,
                                      int bgOpacity, int outlineLevel, int outlineColor,
                                      boolean visible, StyleCallback callback) {
            this.showLiveAppearanceControls = callback != null;
            this.currentDelayMs = delayMs;
            this.currentSize = size;
            this.currentColor = color;
            this.currentOpaqueBg = opaqueBg;
            this.currentBgOpacity = UnifiedSubtitleConfig.normalizeBgOpacity(bgOpacity);
            this.currentOutlineLevel = Math.max(0, Math.min(3, outlineLevel));
            this.currentOutlineColor = UnifiedSubtitleConfig.normalizeOutlineColor(outlineColor);
            this.currentVisible = visible;
            this.hasExplicitAppearanceState = true;
            this.styleCallback = callback;
            return this;
        }

        public AlertDialog show() {
            return CinemaSubtitleHubDialog.show(this);
        }
    }

    private CinemaSubtitleHubDialog() {}

    public static Builder create(Activity activity) {
        return new Builder(activity);
    }

    /** Maps the central {@link UnifiedSubtitleConfig#preferredSubtitleMode} to a Hub dropdown mode. */
    public static int hubModeFromCentral(android.content.Context context) {
        return switch (UnifiedSubtitleConfig.preferredSubtitleMode(context)) {
            case UnifiedSubtitleConfig.SUB_MODE_MOVIE_SOURCE -> MODE_MOVIE_SOURCE;
            case UnifiedSubtitleConfig.SUB_MODE_OFF -> MODE_OFF;
            default -> MODE_AUTO;
        };
    }

    /** Saves the central preferred subtitle mode and broadcasts it across TV prefs & Phone sync. */
    public static void saveCentralMode(android.content.Context context, int subMode) {
        UnifiedSubtitleConfig.setPreferredSubtitleMode(context, subMode);
        CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(context);
    }

    /**
     * Formats the concise grouped Embedded Video Subtitles header:
     * - When active: "الترجمات الداخلية · English" / "Embedded · English"
     * - When inactive: "الترجمات الداخلية (3)" / "Embedded (3)"
     */
    public static String formatEmbeddedGroupLabel(Activity a, int embeddedCount, String activeEmbeddedLabel) {
        if (activeEmbeddedLabel != null && !activeEmbeddedLabel.isBlank()) {
            String baseLang = extractBaseLanguage(activeEmbeddedLabel);
            if (!baseLang.isEmpty()) {
                return a.getString(R.string.cinema_sub_embedded_group_active, baseLang);
            }
        }
        return a.getString(R.string.cinema_sub_embedded_group_title, embeddedCount);
    }

    private static String extractBaseLanguage(String label) {
        if (label == null) return "";
        String trimmed = label.trim();
        int sep = trimmed.indexOf('·');
        if (sep > 0) trimmed = trimmed.substring(0, sep).trim();
        int paren = trimmed.indexOf('(');
        if (paren > 0) trimmed = trimmed.substring(0, paren).trim();
        return trimmed;
    }

    /** Opens the 4-option Subtitle Mode dropdown (Auto-Select, Movie Source, Online Now, Turn Off). */
    private static void showSubtitleModeDialog(
            Activity a,
            Builder b,
            AlertDialog parentDlg,
            View returnFocusView) {
        boolean ar = arabic(a);
        ScrollView scroll = new ScrollView(a);
        scroll.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout listBody = new LinearLayout(a);
        listBody.setOrientation(LinearLayout.VERTICAL);
        listBody.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        listBody.setClipChildren(false);
        listBody.setClipToPadding(false);
        listBody.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 14));
        scroll.addView(listBody);

        final AlertDialog[] modeDlgRef = {null};
        final View[] initialFocus = {null};

        String autoHint = UnifiedSubtitleConfig.autoSelectPriorityLabel(
                UnifiedSubtitleConfig.autoSelectPriority(a), ar);

        Runnable dismissBoth = () -> {
            if (modeDlgRef[0] != null) modeDlgRef[0].dismiss();
            if (parentDlg != null) parentDlg.dismiss();
        };

        if (b.onModeAuto != null) {
            LinearLayout r = modeOptionRow(a, listBody,
                    a.getString(R.string.cinema_sub_mode_auto),
                    autoHint,
                    b.activeSubtitleMode == MODE_AUTO,
                    () -> { dismissBoth.run(); b.onModeAuto.run(); });
            if (b.activeSubtitleMode == MODE_AUTO || initialFocus[0] == null) initialFocus[0] = r;
        }
        if (b.onModeMovieSource != null) {
            LinearLayout r = modeOptionRow(a, listBody,
                    a.getString(R.string.cinema_sub_mode_source),
                    a.getString(R.string.cinema_sub_mode_source_hint),
                    b.activeSubtitleMode == MODE_MOVIE_SOURCE,
                    () -> { dismissBoth.run(); b.onModeMovieSource.run(); });
            if (b.activeSubtitleMode == MODE_MOVIE_SOURCE || initialFocus[0] == null) initialFocus[0] = r;
        }
        if (b.onModeAutoDownloadNow != null) {
            LinearLayout r = modeOptionRow(a, listBody,
                    a.getString(R.string.cinema_sub_mode_online_now),
                    a.getString(R.string.cinema_sub_mode_online_now_hint),
                    b.activeSubtitleMode == MODE_ONLINE_NOW,
                    () -> { dismissBoth.run(); b.onModeAutoDownloadNow.run(); });
            if (b.activeSubtitleMode == MODE_ONLINE_NOW || initialFocus[0] == null) initialFocus[0] = r;
        }
        if (b.onDisableSubtitles != null) {
            LinearLayout r = modeOptionRow(a, listBody,
                    a.getString(R.string.cinema_sub_mode_off),
                    a.getString(R.string.cinema_sub_mode_off_hint),
                    b.activeSubtitleMode == MODE_OFF,
                    () -> { dismissBoth.run(); b.onDisableSubtitles.run(); });
            if (b.activeSubtitleMode == MODE_OFF || initialFocus[0] == null) initialFocus[0] = r;
        }

        AlertDialog modeDlg = dialog(a)
                .setTitle(R.string.cinema_sub_mode_title)
                .setView(scroll)
                .setNegativeButton(R.string.addon_back, null)
                .create();
        modeDlgRef[0] = modeDlg;
        modeDlg.setOnShowListener(d -> {
            if (modeDlg.getWindow() != null) {
                int w = Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.68f);
                modeDlg.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (initialFocus[0] != null) initialFocus[0].requestFocus();
        });
        modeDlg.setOnDismissListener(d -> {
            if (returnFocusView != null && returnFocusView.isShown()) {
                returnFocusView.post(returnFocusView::requestFocus);
            }
        });
        modeDlg.show();
    }

    /**
     * Opens the clean, sorted Embedded Video Subtitles dropdown dialog containing ONLY
     * internal video subtitles with native Endonym language names.
     */
    public static AlertDialog showEmbeddedTracksDialog(
            Activity a,
            List<EmbeddedTrackChoice> rawTracks,
            Runnable onTrackPicked,
            View returnFocusView) {
        if (a == null || a.isFinishing() || a.isDestroyed() || rawTracks == null || rawTracks.isEmpty()) {
            return null;
        }
        boolean ar = arabic(a);

        List<EmbeddedTrackChoice> sorted = CinemaSubtitleLabels.sortEmbeddedTracks(
                a, rawTracks, EmbeddedTrackChoice::langCanon, EmbeddedTrackChoice::label);
        List<String> rawLabels = new ArrayList<>(sorted.size());
        for (EmbeddedTrackChoice tc : sorted) rawLabels.add(tc.label());
        List<String> cleanLabels = CinemaSubtitleLabels.disambiguateLabels(rawLabels);

        ScrollView scroll = new ScrollView(a);
        scroll.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout listBody = new LinearLayout(a);
        listBody.setOrientation(LinearLayout.VERTICAL);
        listBody.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        listBody.setClipChildren(false);
        listBody.setClipToPadding(false);
        listBody.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 14));
        scroll.addView(listBody);

        final AlertDialog[] subDlgRef = {null};
        final View[] initialFocus = {null};

        for (int i = 0; i < sorted.size(); i++) {
            final EmbeddedTrackChoice tc = sorted.get(i);
            final String displayLabel = cleanLabels.get(i);
            LinearLayout row = embeddedLanguageRow(a, listBody, displayLabel, tc.selected(), () -> {
                if (subDlgRef[0] != null) subDlgRef[0].dismiss();
                if (onTrackPicked != null) onTrackPicked.run();
                if (tc.onSelect() != null) tc.onSelect().run();
            });
            if (tc.selected() || initialFocus[0] == null) {
                initialFocus[0] = row;
            }
        }

        AlertDialog subDlg = dialog(a)
                .setTitle(a.getString(R.string.cinema_sub_embedded_group_title, sorted.size()))
                .setView(scroll)
                .setNegativeButton(R.string.addon_back, null)
                .create();
        subDlgRef[0] = subDlg;
        subDlg.setOnShowListener(d -> {
            if (subDlg.getWindow() != null) {
                int w = Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.68f);
                subDlg.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (initialFocus[0] != null) {
                initialFocus[0].requestFocus();
                scroll.post(() -> {
                    int y = Math.max(0, initialFocus[0].getTop() - dp(a, 48));
                    scroll.scrollTo(0, y);
                });
            }
        });
        subDlg.setOnDismissListener(d -> {
            if (returnFocusView != null && returnFocusView.isShown()) {
                returnFocusView.post(returnFocusView::requestFocus);
            }
        });
        subDlg.show();
        return subDlg;
    }

    private static AlertDialog show(Builder b) {
        Activity a = b.activity;
        CinemaSubtitleSyncBridge.ensureBootstrapped(a);
        boolean ar = arabic(a);

        ScrollView scroll = new ScrollView(a);
        scroll.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 16));
        scroll.addView(body);

        final AlertDialog[] dlgRef = {null};
        final View[] firstFocus = {null};

        // 0. UNIFIED TOP SECTION: Isolated Appearance & Timing Panel (identical in Players, Details, Series & Settings)
        CinemaSubtitleAppearancePanel.Config appCfg = new CinemaSubtitleAppearancePanel.Config();
        appCfg.livePlayback = b.showLiveAppearanceControls || b.onOpenLiveTimingOverlay != null;
        appCfg.hasExplicitState = b.hasExplicitAppearanceState;
        appCfg.initialDelayMs = b.hasExplicitAppearanceState ? b.currentDelayMs
                : (b.onOpenLiveTimingOverlay != null ? b.currentDelayMs : UnifiedSubtitleConfig.defaultDelayMs(a));
        appCfg.initialSize = b.currentSize;
        appCfg.initialVpos = UnifiedSubtitleConfig.vpos(a);
        appCfg.initialColor = b.currentColor;
        appCfg.initialOpaqueBg = b.currentOpaqueBg;
        appCfg.initialBgOpacity = b.currentBgOpacity;
        appCfg.initialOutlineLevel = b.currentOutlineLevel;
        appCfg.initialOutlineColor = b.currentOutlineColor;
        appCfg.initialVisible = b.currentVisible;
        appCfg.styleCallback = b.styleCallback != null
                ? b.styleCallback
                : CinemaSubtitleAppearancePanel.defaultConfigStyleCallback(a);
        appCfg.onOpenLiveTimingOverlay = b.onOpenLiveTimingOverlay;

        CinemaSubtitleAppearancePanel.Handle appearanceHandle =
                CinemaSubtitleAppearancePanel.attach(a, body, appCfg, () -> dlgRef[0]);
        int appearanceChildEnd = body.getChildCount();
        firstFocus[0] = appearanceHandle.firstFocusable;

        // 1. Subtitle Mode Dropdown + Embedded Dropdown + External & Secondary Tracks
        boolean hasTracksSection = b.hasSubtitleModeDropdown || !b.tracks.isEmpty()
                || !b.embeddedTracks.isEmpty() || b.hasSecondaryActive;
        if (hasTracksSection) {
            addSectionHeading(a, body, ar ? "مسارات الترجمة المتاحة" : "Available Subtitle Tracks");

            if (b.hasSubtitleModeDropdown) {
                String shortPrio = UnifiedSubtitleConfig.autoSelectPriorityShortLabel(a, ar);
                String modeTitle;
                String modeSub;
                boolean modeActive;
                switch (b.activeSubtitleMode) {
                    case MODE_AUTO -> {
                        modeTitle = a.getString(R.string.cinema_sub_mode_auto_active, shortPrio);
                        modeSub = b.noMatchYet
                                ? a.getString(R.string.cinema_sub_mode_auto_no_match_hint)
                                : a.getString(R.string.cinema_sub_mode_custom_hint);
                        modeActive = true;
                    }
                    case MODE_MOVIE_SOURCE -> {
                        modeTitle = a.getString(R.string.cinema_sub_mode_source);
                        modeSub = b.noMatchYet
                                ? a.getString(R.string.cinema_sub_mode_auto_no_match_hint)
                                : label(a, "مُفعّلة حالياً ✓ · اضغط لتغيير وضع الترجمة",
                                        "Currently active ✓ · Press to change subtitle mode");
                        modeActive = true;
                    }
                    case MODE_OFF -> {
                        modeTitle = a.getString(R.string.cinema_sub_mode_off);
                        modeSub = a.getString(R.string.cinema_sub_mode_custom_hint);
                        modeActive = false;
                    }
                    default -> {
                        modeTitle = a.getString(R.string.cinema_sub_mode_title);
                        modeSub = a.getString(R.string.cinema_sub_mode_custom_hint);
                        modeActive = false;
                    }
                }
                final LinearLayout[] modeCardRef = {null};
                LinearLayout modeCard = dropdownCard(a, body, R.drawable.cinema_subtitles,
                        modeTitle, modeSub, modeActive, () ->
                                showSubtitleModeDialog(a, b, dlgRef[0], modeCardRef[0]));
                modeCardRef[0] = modeCard;
                if (firstFocus[0] == null) firstFocus[0] = modeCard;
            }

            for (TrackChoice tc : b.tracks) {
                LinearLayout card = trackChoiceCard(a, body, tc.label(), tc.selected(), () -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    if (tc.onSelect() != null) tc.onSelect().run();
                });
                if (firstFocus[0] == null) firstFocus[0] = card;
            }

            if (!b.embeddedTracks.isEmpty()) {
                String activeEmbeddedLabel = null;
                for (EmbeddedTrackChoice etc : b.embeddedTracks) {
                    if (etc.selected()) {
                        activeEmbeddedLabel = etc.label();
                        break;
                    }
                }
                String groupTitle = formatEmbeddedGroupLabel(a, b.embeddedTracks.size(), activeEmbeddedLabel);
                String groupSub = activeEmbeddedLabel != null
                        ? a.getString(R.string.cinema_sub_embedded_group_active_hint, b.embeddedTracks.size())
                        : a.getString(R.string.cinema_sub_embedded_group_hint);
                final LinearLayout[] groupCardRef = {null};
                LinearLayout groupCard = dropdownCard(a, body, R.drawable.cinema_subtitles,
                        groupTitle, groupSub, activeEmbeddedLabel != null, () ->
                                showEmbeddedTracksDialog(a, b.embeddedTracks,
                                        () -> { if (dlgRef[0] != null) dlgRef[0].dismiss(); },
                                        groupCardRef[0]));
                groupCardRef[0] = groupCard;
                if (firstFocus[0] == null) firstFocus[0] = groupCard;
            }

            if (b.hasSecondaryActive && b.onClearSecondary != null) {
                String secName = (b.activeSecondaryLabel != null && !b.activeSecondaryLabel.isBlank())
                        ? b.activeSecondaryLabel
                        : CinemaLanguagePreferences.localizedSubtitleLabel(a,
                                CinemaLanguagePreferences.secondarySubtitleLanguage(a));
                LinearLayout secCard = secondaryActiveCard(a, body,
                        a.getString(R.string.cinema_sub_secondary_active_title, secName),
                        a.getString(R.string.cinema_sub_secondary_active_hint),
                        () -> {
                            if (dlgRef[0] != null) dlgRef[0].dismiss();
                            b.onClearSecondary.run();
                        });
                if (firstFocus[0] == null) firstFocus[0] = secCard;
            }
        }

        // 2. Smart Search & Movie Source / Phone Pickers
        addSectionHeading(a, body, ar ? "البحث وملفات الترجمة" : "Search & Subtitle Files");
        final LinearLayout[] searchCardHolder = {null};
        final LinearLayout[] sourcesQuickCardHolder = {null};
        java.util.function.Supplier<String> buildSearchSub = () -> {
            String srcSummary = UnifiedSubtitleConfig.searchSelectionSummary(a, ar);
            String langSummary = UnifiedSubtitleConfig.languageDisplay(UnifiedSubtitleConfig.primaryLanguage(a), ar);
            List<String> addonNames = UnifiedSubtitleConfig.activeAddonSourceNames(a);
            String addonSuffix = addonNames.isEmpty() ? "" : (" · " + String.join("، ", addonNames));
            return srcSummary + " · " + langSummary + addonSuffix;
        };
        if (b.onSearchOnline != null) {
            LinearLayout searchCard = settingsCard(a, body, R.drawable.cinema_globe,
                    label(a, "بحث ذكي عن الترجمة", "Smart Multi-Source Subtitle Search"),
                    buildSearchSub.get(),
                    null, null, () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        b.onSearchOnline.run();
                    });
            searchCardHolder[0] = searchCard;
            if (firstFocus[0] == null) firstFocus[0] = searchCard;
        }
        {
            List<String> addonNames = UnifiedSubtitleConfig.activeAddonSourceNames(a);
            String quickSub = addonNames.isEmpty()
                    ? UnifiedSubtitleConfig.searchSelectionSummary(a, ar)
                    : UnifiedSubtitleConfig.searchSelectionSummary(a, ar) + " · " + String.join(" · ", addonNames);
            LinearLayout srcQuickCard = dropdownCard(a, body, R.drawable.cinema_addons,
                    label(a, "مصادر البحث الذكي وإضافات الترجمة", "Smart Search Sources & Subtitle Add-ons"),
                    quickSub, !addonNames.isEmpty(), () -> showHubSourcesPickerDialog(a, () -> {
                        if (searchCardHolder[0] != null) {
                            updateSettingsCardSubtitle(searchCardHolder[0], buildSearchSub.get());
                        }
                        if (sourcesQuickCardHolder[0] != null) {
                            List<String> updatedAddons = UnifiedSubtitleConfig.activeAddonSourceNames(a);
                            String nextSub = updatedAddons.isEmpty()
                                    ? UnifiedSubtitleConfig.searchSelectionSummary(a, ar)
                                    : UnifiedSubtitleConfig.searchSelectionSummary(a, ar) + " · " + String.join(" · ", updatedAddons);
                            if (sourcesQuickCardHolder[0].getChildCount() >= 2
                                    && sourcesQuickCardHolder[0].getChildAt(1) instanceof LinearLayout col
                                    && col.getChildCount() >= 2 && col.getChildAt(1) instanceof TextView st) {
                                st.setText(nextSub);
                            }
                        }
                    }, sourcesQuickCardHolder[0]));
            sourcesQuickCardHolder[0] = srcQuickCard;
            if (firstFocus[0] == null) firstFocus[0] = srcQuickCard;
        }
        if (b.onPickMovieSource != null) {
            LinearLayout pickCard = settingsCard(a, body, R.drawable.cinema_folder,
                    a.getString(R.string.cinema_sub_pick_movie_source),
                    a.getString(R.string.cinema_sub_pick_movie_source_hint),
                    null, null, () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        b.onPickMovieSource.run();
                    });
            if (firstFocus[0] == null) firstFocus[0] = pickCard;
        }
        if (b.onPickFromPhone != null) {
            settingsCard(a, body, R.drawable.cinema_ic_addons,
                    label(a, "اختيار أو إرسال ترجمة من الهاتف", "Select Subtitle from Phone"),
                    label(a, "فتح منتقي الترجمة على هاتفك المرتبط مباشرةً", "Open subtitle picker on your connected Phone"),
                    null, null, () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        b.onPickFromPhone.run();
                    });
        }

        // 3. Dual Subtitles & Secondary Language Controls (toggles in-place WITHOUT closing the Hub!)
        if (b.showDualControls) {
            addSectionHeading(a, body, ar ? "الترجمة المزدوجة واللغة الثانوية" : "Dual Subtitles & Secondary Language");
            final boolean[] dualState = {UnifiedSubtitleConfig.dualSubtitlesEnabled(a)};
            final int[] dualPosState = {UnifiedSubtitleConfig.dualSubPosition(a)};
            java.util.function.Supplier<String> buildPosDesc = () -> switch (dualPosState[0]) {
                case 0 -> label(a, "الموضع: كلاهما بالأسفل", "Position: Both at Bottom");
                case 2 -> label(a, "الموضع: الأساسية أعلى · الثانوية أسفل", "Position: Primary Top · Secondary Bottom");
                default -> label(a, "الموضع: الأساسية أسفل · الثانوية أعلى", "Position: Primary Bottom · Secondary Top");
            };

            final LinearLayout[] posCardRef = {null};
            InPlaceToggleRef dualToggle = inPlaceToggleCard(a, body, R.drawable.cinema_subtitles,
                    label(a, "تفعيل الترجمة المزدوجة", "Dual Subtitles Mode"),
                    buildPosDesc.get(),
                    () -> dualState[0],
                    null);
            dualToggle.root.setOnClickListener(v -> {
                dualState[0] = !dualState[0];
                UnifiedSubtitleConfig.setDualSubtitlesEnabled(a, dualState[0]);
                CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(a);
                dualToggle.refresh.run();
                if (b.onDualToggled != null) b.onDualToggled.run();
            });
            if (firstFocus[0] == null) firstFocus[0] = dualToggle.root;

            final LinearLayout[] secLangCardRef = {null};
            String secLangLabel = CinemaLanguagePreferences.localizedSubtitleLabel(
                    a, CinemaLanguagePreferences.secondarySubtitleLanguage(a));
            LinearLayout secLangCard = settingsCard(a, body, R.drawable.cinema_globe,
                    a.getString(R.string.cinema_pref_secondary_sub_lang),
                    secLangLabel,
                    null, null, () -> CinemaLanguagePreferences.chooseSecondaryLanguage(a, chosen -> {
                        CinemaLanguagePreferences.setSecondarySubtitleLanguage(a, chosen);
                        if (secLangCardRef[0] != null) {
                            updateSettingsCardSubtitle(secLangCardRef[0],
                                    CinemaLanguagePreferences.localizedSubtitleLabel(a, chosen));
                        }
                        if (b.onDualToggled != null) b.onDualToggled.run();
                        if (secLangCardRef[0] != null && secLangCardRef[0].isShown()) {
                            secLangCardRef[0].post(secLangCardRef[0]::requestFocus);
                        }
                    }));
            secLangCardRef[0] = secLangCard;

            LinearLayout posCard = settingsCard(a, body, R.drawable.cinema_ic_appearance,
                    label(a, "تبديل موضع الترجمة المزدوجة", "Switch Dual Subtitle Position"),
                    buildPosDesc.get(),
                    null, null, () -> {
                        dualPosState[0] = (UnifiedSubtitleConfig.dualSubPosition(a) + 1) % 3;
                        UnifiedSubtitleConfig.setDualSubPosition(a, dualPosState[0]);
                        CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(a);
                        String nextDesc = buildPosDesc.get();
                        dualToggle.subtitleTv.setText(nextDesc);
                        if (posCardRef[0] != null) updateSettingsCardSubtitle(posCardRef[0], nextDesc);
                        if (b.onDualPositionCycled != null) b.onDualPositionCycled.run();
                    });
            posCardRef[0] = posCard;

            if (b.onPickSecondaryFile != null) {
                settingsCard(a, body, R.drawable.cinema_folder,
                        label(a, "اختيار ملف للترجمة الثانية", "Choose Secondary Subtitle File"),
                        label(a, "تحديد ملف SRT أو VTT للترجمة الثانوية", "Pick an SRT or VTT secondary track"),
                        null, null, () -> {
                            if (dlgRef[0] != null) dlgRef[0].dismiss();
                            b.onPickSecondaryFile.run();
                        });
            }
            if (b.hasSecondaryActive && b.onClearSecondary != null) {
                settingsCard(a, body, R.drawable.cinema_ic_system,
                        label(a, "إزالة الترجمة الثانية", "Clear Secondary Subtitle"),
                        null, null, null, () -> {
                            if (dlgRef[0] != null) dlgRef[0].dismiss();
                            b.onClearSecondary.run();
                        });
            }
        }

        // 4. Unified Subtitle Settings
        addSectionHeading(a, body, ar ? "الإعدادات" : "Settings");
        settingsCard(a, body, R.drawable.cinema_ic_system,
                label(a, "إعدادات الترجمة الموحّدة ومصادرها", "Unified Subtitle Settings & Sources"),
                label(a, "المصادر المفتوحة · لغة الترجمة · سلوك الاختيار التلقائي · المظهر والمزامنة",
                        "Open sources · Subtitle language · Auto-select · Appearance & Sync"),
                null, null, () -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    a.startActivity(new Intent(a, SubsourceSettingsActivity.class));
                });

        // Register all non-timing sibling sections so live timing adjustment fades them transparently over video
        List<View> siblingViews = new ArrayList<>();
        for (int i = appearanceChildEnd; i < body.getChildCount(); i++) {
            siblingViews.add(body.getChildAt(i));
        }

        String title = (b.headerTitle != null && !b.headerTitle.isBlank())
                ? b.headerTitle
                : a.getString(R.string.cinema_sub_hub_short);

        AlertDialog dlg = dialog(a)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(label(a, "إغلاق", "Close"), null)
                .create();
        dlgRef[0] = dlg;
        appearanceHandle.attachHostDialog(dlg, siblingViews);
        dlg.setOnShowListener(d -> {
            widenSubtitleDialog(dlg.getWindow());
            if (firstFocus[0] != null) firstFocus[0].requestFocus();
        });
        dlg.show();
        return dlg;
    }

    private record InPlaceToggleRef(LinearLayout root, TextView subtitleTv, Runnable refresh) {}

    /** Builds a D-pad card whose ✓ ON / ✕ OFF badge updates in-place without dismissing or losing focus. */
    private static InPlaceToggleRef inPlaceToggleCard(
            Activity a,
            LinearLayout container,
            int iconRes,
            String title,
            String initialSubtitle,
            java.util.function.BooleanSupplier stateSupplier,
            Runnable onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(a, 18), dp(a, 13), dp(a, 18), dp(a, 13));

        ImageView icon = new ImageView(a);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
        iconLp.setMarginEnd(dp(a, 14));
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(a);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(a, CinemaTokens.BODY, true);
        titleTv.setText(title);
        enableMarquee(titleTv);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv);

        TextView subTv = text(a, CinemaTokens.CAPTION, false);
        subTv.setText(initialSubtitle);
        subTv.setMaxLines(2);
        subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subTv.setPadding(0, dp(a, 3), 0, 0);
        textCol.addView(subTv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView badgeTv = text(a, CinemaTokens.CAPTION, true);
        badgeTv.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
        bLp.setMarginStart(dp(a, 10));
        row.addView(badgeTv, bLp);

        Runnable applyVisuals = () -> {
            boolean on = stateSupplier.getAsBoolean();
            boolean focused = row.hasFocus();
            badgeTv.setText(on ? label(a, "✓ مفعّل", "✓ ON") : label(a, "✕ متوقف", "✕ OFF"));
            if (focused) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
                titleTv.setTextColor(0xff141517);
                subTv.setTextColor(0xff2c240d);
                icon.setColorFilter(0xff141517);
                badgeTv.setTextColor(0xff141517);
                badgeTv.setBackground(rounded(0x33141517, dp(a, 8), 0));
            } else {
                row.setBackground(rounded(on ? 0xff222019 : 0xff1d1e21, dp(a, 10),
                        on ? 0xaaE5A93B : 0xff34322c));
                titleTv.setTextColor(on ? 0xfff8e2a6 : CinemaTokens.TEXT);
                subTv.setTextColor(on ? 0xff9de0b8 : 0xffb8b8c0);
                icon.setColorFilter(CinemaTokens.GOLD);
                badgeTv.setTextColor(on ? 0xff9de0b8 : 0xffd0a0a0);
                badgeTv.setBackground(rounded(on ? 0xff1b3326 : 0xff2b1e20, dp(a, 8), 0));
            }
        };
        applyVisuals.run();

        row.setOnFocusChangeListener((v, hasFocus) -> applyVisuals.run());
        if (onClick != null) row.setOnClickListener(v -> onClick.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 10);
        container.addView(row, lp);
        return new InPlaceToggleRef(row, subTv, applyVisuals);
    }

    /** Direct multi-source & Stremio subtitle add-on picker inside Unified Subtitle Hub. */
    private static void showHubSourcesPickerDialog(Activity a, Runnable onSaved, View returnFocusView) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        boolean rtl = arabic(a);
        List<UnifiedSubtitleConfig.ProviderInfo> providers = UnifiedSubtitleConfig.allProviders(a);
        boolean allMode = UnifiedSubtitleConfig.MODE_ALL.equals(UnifiedSubtitleConfig.searchSelectionMode(a));
        java.util.Set<String> selected = new java.util.LinkedHashSet<>(UnifiedSubtitleConfig.selectedSearchSources(a));

        boolean[] allFlag = {allMode};
        boolean[] checks = new boolean[providers.size()];
        for (int i = 0; i < providers.size(); i++) {
            checks[i] = !allMode && selected.contains(providers.get(i).id);
        }

        android.widget.ListView list = new android.widget.ListView(a);
        list.setDivider(null);
        list.setDividerHeight(dp(a, 4));
        list.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
        list.setSelector(rounded(0xff35281b, dp(a, 12), CinemaTokens.GOLD));

        var adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return providers.size() + 1; }
            @Override public Object getItem(int pos) { return pos == 0 ? "ALL" : providers.get(pos - 1); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View recycled, android.view.ViewGroup parent) {
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(a, 50));
                row.setPaddingRelative(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));

                ImageView box = new ImageView(a);
                boolean isChecked = pos == 0 ? allFlag[0] : checks[pos - 1];
                box.setImageDrawable(createCinemaCheckDrawable(a, isChecked));
                int sz = dp(a, 22);
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(sz, sz);
                bLp.setMarginEnd(dp(a, 12));
                row.addView(box, bLp);

                LinearLayout col = new LinearLayout(a);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView t1 = text(a, 15, true);
                TextView t2 = text(a, 12, false);
                if (pos == 0) {
                    t1.setText(label(a, "الكل (جميع المصادر وإضافات الترجمة المفعّلة معاً)",
                            "All Enabled Sources & Subtitle Add-ons"));
                    t2.setText(label(a, "يبحث بالتوازي في جميع المصادر المفتوحة وإضافات Stremio المفعّلة",
                            "Parallel search across all open sources & enabled Stremio add-ons"));
                    t1.setTextColor(isChecked ? CinemaTokens.GOLD : Color.WHITE);
                } else {
                    UnifiedSubtitleConfig.ProviderInfo p = providers.get(pos - 1);
                    boolean ready = p.keyless || UnifiedSubtitleConfig.hasKey(a, p);
                    t1.setText(p.name + (ready ? "" : label(a, " (يحتاج مفتاح API)", " (API key needed)")));
                    t2.setText(rtl ? p.arType : p.enType);
                    t1.setTextColor(isChecked ? CinemaTokens.GOLD : (ready ? Color.WHITE : 0xff8a8a94));
                }
                t2.setTextColor(0xffa1a1aa);
                col.addView(t1);
                col.addView(t2);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos == 0) {
                allFlag[0] = true;
                for (int i = 0; i < checks.length; i++) checks[i] = false;
            } else {
                allFlag[0] = false;
                checks[pos - 1] = !checks[pos - 1];
                boolean any = false;
                for (boolean c : checks) if (c) { any = true; break; }
                if (!any) allFlag[0] = true;
            }
            adapter.notifyDataSetChanged();
        });

        AlertDialog picker = dialog(a)
                .setTitle(label(a, "مصادر البحث الذكي وإضافات الترجمة", "Smart Search Sources & Subtitle Add-ons"))
                .setView(list)
                .setPositiveButton(label(a, "حفظ ومزامنة", "Save & Sync"), (d, w) -> {
                    if (allFlag[0]) {
                        UnifiedSubtitleConfig.setSearchSelectionAll(a);
                    } else {
                        java.util.Set<String> picked = new java.util.LinkedHashSet<>();
                        for (int i = 0; i < providers.size(); i++) {
                            if (checks[i]) {
                                picked.add(providers.get(i).id);
                                UnifiedSubtitleConfig.setSourceEnabled(a, providers.get(i).id, true);
                            }
                        }
                        UnifiedSubtitleConfig.setSearchSelectionCustom(a, picked);
                    }
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(a);
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(R.string.addon_back, null)
                .create();
        picker.setOnDismissListener(d -> {
            if (returnFocusView != null && returnFocusView.isShown()) {
                returnFocusView.post(returnFocusView::requestFocus);
            }
        });
        picker.show();
        widenSubtitleDialog(picker.getWindow());
        list.requestFocus();
    }

    private static LinearLayout modeOptionRow(
            Activity a,
            LinearLayout container,
            String title,
            String subtitle,
            boolean selected,
            Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(a, 16), dp(a, 11), dp(a, 16), dp(a, 11));

        ImageView radio = new ImageView(a);
        radio.setImageDrawable(createCinemaRadioDrawable(a, selected));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20));
        rLp.setMarginEnd(dp(a, 12));
        row.addView(radio, rLp);

        LinearLayout textCol = new LinearLayout(a);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(a, CinemaTokens.BODY, true);
        titleTv.setText(title);
        enableMarquee(titleTv);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv);

        TextView subTv = text(a, CinemaTokens.CAPTION, false);
        subTv.setText(subtitle);
        subTv.setMaxLines(2);
        subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subTv.setPadding(0, dp(a, 3), 0, 0);
        textCol.addView(subTv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView checkBadge = null;
        if (selected) {
            checkBadge = text(a, CinemaTokens.CAPTION, true);
            checkBadge.setText("✓");
            checkBadge.setPadding(dp(a, 9), dp(a, 3), dp(a, 9), dp(a, 3));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
            cLp.setMarginStart(dp(a, 8));
            row.addView(checkBadge, cLp);
        }

        final TextView finalBadge = checkBadge;
        Runnable styleRest = () -> {
            row.setBackground(rounded(selected ? 0xff222019 : 0xff1d1e21, dp(a, 10),
                    selected ? 0xaaE5A93B : 0xff2e2d29));
            titleTv.setTextColor(selected ? 0xfff8e2a6 : CinemaTokens.TEXT);
            subTv.setTextColor(selected ? 0xff9de0b8 : 0xffc4b898);
            if (finalBadge != null) {
                finalBadge.setTextColor(0xff9de0b8);
                finalBadge.setBackground(rounded(0xff1b3326, dp(a, 8), 0));
            }
        };
        styleRest.run();

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
                titleTv.setTextColor(0xff141517);
                subTv.setTextColor(0xff2c240d);
                if (finalBadge != null) {
                    finalBadge.setTextColor(0xff141517);
                    finalBadge.setBackground(rounded(0x33141517, dp(a, 8), 0));
                }
            } else {
                styleRest.run();
            }
        });
        row.setOnClickListener(v -> { if (action != null) action.run(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 8);
        container.addView(row, lp);
        return row;
    }

    private static LinearLayout trackChoiceCard(
            Activity a,
            LinearLayout container,
            String title,
            boolean selected,
            Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(a, 18), dp(a, 13), dp(a, 18), dp(a, 13));

        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.cinema_subtitles);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
        iconLp.setMarginEnd(dp(a, 14));
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(a);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(a, CinemaTokens.BODY, true);
        titleTv.setText(title);
        enableMarquee(titleTv);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv);

        TextView subTv = null;
        if (selected) {
            subTv = text(a, CinemaTokens.CAPTION, false);
            subTv.setText(label(a, "مُفعّلة حالياً ✓", "Currently active ✓"));
            subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            subTv.setPadding(0, dp(a, 3), 0, 0);
            textCol.addView(subTv);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView badgeTv = null;
        if (selected) {
            badgeTv = text(a, CinemaTokens.CAPTION, true);
            badgeTv.setText("✓ " + a.getString(R.string.cinema_sub_active_primary));
            badgeTv.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.setMarginStart(dp(a, 10));
            row.addView(badgeTv, bLp);
        }

        final TextView finalSub = subTv;
        final TextView finalBadge = badgeTv;
        Runnable styleRest = () -> {
            row.setBackground(rounded(selected ? 0xff222019 : 0xff1d1e21, dp(a, 10),
                    selected ? 0xaaE5A93B : 0xff34322c));
            titleTv.setTextColor(selected ? 0xfff8e2a6 : CinemaTokens.TEXT);
            if (finalSub != null) finalSub.setTextColor(0xff9de0b8);
            icon.setColorFilter(CinemaTokens.GOLD);
            if (finalBadge != null) {
                finalBadge.setTextColor(0xff9de0b8);
                finalBadge.setBackground(rounded(0xff1b3326, dp(a, 8), 0));
            }
        };
        styleRest.run();

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
                titleTv.setTextColor(0xff141517);
                if (finalSub != null) finalSub.setTextColor(0xff2c240d);
                icon.setColorFilter(0xff141517);
                if (finalBadge != null) {
                    finalBadge.setTextColor(0xff141517);
                    finalBadge.setBackground(rounded(0x33141517, dp(a, 8), 0));
                }
            } else {
                styleRest.run();
            }
        });
        row.setOnClickListener(v -> { if (action != null) action.run(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 10);
        container.addView(row, lp);
        return row;
    }

    private static LinearLayout secondaryActiveCard(
            Activity a,
            LinearLayout container,
            String title,
            String subtitle,
            Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(a, 18), dp(a, 13), dp(a, 18), dp(a, 13));

        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.cinema_subtitles);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
        iconLp.setMarginEnd(dp(a, 14));
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(a);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(a, CinemaTokens.BODY, true);
        titleTv.setText(title);
        enableMarquee(titleTv);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv);

        TextView subTv = text(a, CinemaTokens.CAPTION, false);
        subTv.setText(subtitle);
        subTv.setMaxLines(2);
        subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subTv.setPadding(0, dp(a, 3), 0, 0);
        textCol.addView(subTv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView badgeTv = text(a, CinemaTokens.CAPTION, true);
        badgeTv.setText("✓ " + a.getString(R.string.cinema_sub_active_secondary));
        badgeTv.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
        bLp.setMarginStart(dp(a, 10));
        row.addView(badgeTv, bLp);

        Runnable styleRest = () -> {
            row.setBackground(rounded(0xff1b2126, dp(a, 10), 0x884dd0e1));
            titleTv.setTextColor(0xffffe082);
            subTv.setTextColor(0xff8ee6f2);
            icon.setColorFilter(0xffffd54f);
            badgeTv.setTextColor(0xff8ee6f2);
            badgeTv.setBackground(rounded(0xff162e36, dp(a, 8), 0));
        };
        styleRest.run();

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
                titleTv.setTextColor(0xff141517);
                subTv.setTextColor(0xff2c240d);
                icon.setColorFilter(0xff141517);
                badgeTv.setTextColor(0xff141517);
                badgeTv.setBackground(rounded(0x33141517, dp(a, 8), 0));
            } else {
                styleRest.run();
            }
        });
        row.setOnClickListener(v -> { if (action != null) action.run(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 10);
        container.addView(row, lp);
        return row;
    }

    private static LinearLayout dropdownCard(
            Activity a,
            LinearLayout container,
            int iconRes,
            String title,
            String subtitle,
            boolean active,
            Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(a, 18), dp(a, 13), dp(a, 18), dp(a, 13));

        ImageView icon = new ImageView(a);
        if (iconRes != 0) icon.setImageResource(iconRes);
        icon.setColorFilter(CinemaTokens.GOLD);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
        iconLp.setMarginEnd(dp(a, 14));
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(a);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(a, CinemaTokens.BODY, true);
        titleTv.setText(title);
        enableMarquee(titleTv);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv);

        TextView subTv = text(a, CinemaTokens.CAPTION, false);
        subTv.setText(subtitle);
        subTv.setMaxLines(2);
        subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subTv.setPadding(0, dp(a, 3), 0, 0);
        textCol.addView(subTv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrowBadge = text(a, 16, true);
        arrowBadge.setText("▾");
        arrowBadge.setGravity(Gravity.CENTER);
        arrowBadge.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(-2, -2);
        arrowLp.setMarginStart(dp(a, 10));
        row.addView(arrowBadge, arrowLp);

        Runnable styleRest = () -> {
            row.setBackground(rounded(active ? 0xff222019 : 0xff1d1e21, dp(a, 10),
                    active ? 0xaaE5A93B : 0xff34322c));
            titleTv.setTextColor(active ? 0xfff8e2a6 : CinemaTokens.TEXT);
            subTv.setTextColor(active ? 0xff9de0b8 : 0xffd4b55b);
            icon.setColorFilter(CinemaTokens.GOLD);
            arrowBadge.setTextColor(CinemaTokens.GOLD);
            arrowBadge.setBackground(rounded(0xff29261d, dp(a, 8), 0x66E5A93B));
        };
        styleRest.run();

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
                titleTv.setTextColor(0xff141517);
                subTv.setTextColor(0xff2c240d);
                icon.setColorFilter(0xff141517);
                arrowBadge.setTextColor(0xff141517);
                arrowBadge.setBackground(rounded(0x33141517, dp(a, 8), 0));
            } else {
                styleRest.run();
            }
        });
        row.setOnClickListener(v -> { if (action != null) action.run(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 10);
        container.addView(row, lp);
        return row;
    }

    private static LinearLayout embeddedLanguageRow(
            Activity a,
            LinearLayout container,
            String languageLabel,
            boolean selected,
            Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(a, 16), dp(a, 11), dp(a, 16), dp(a, 11));

        ImageView radio = new ImageView(a);
        radio.setImageDrawable(createCinemaRadioDrawable(a, selected));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20));
        rLp.setMarginEnd(dp(a, 12));
        row.addView(radio, rLp);

        TextView labelTv = text(a, CinemaTokens.BODY, selected);
        labelTv.setText(languageLabel);
        enableMarquee(labelTv);
        labelTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.addView(labelTv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView checkBadge = null;
        if (selected) {
            checkBadge = text(a, CinemaTokens.CAPTION, true);
            checkBadge.setText("✓");
            checkBadge.setPadding(dp(a, 9), dp(a, 3), dp(a, 9), dp(a, 3));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
            cLp.setMarginStart(dp(a, 8));
            row.addView(checkBadge, cLp);
        }

        final TextView finalBadge = checkBadge;
        Runnable styleRest = () -> {
            row.setBackground(rounded(selected ? 0xff222019 : 0xff1d1e21, dp(a, 10),
                    selected ? 0xaaE5A93B : 0xff2e2d29));
            labelTv.setTextColor(selected ? 0xfff8e2a6 : CinemaTokens.TEXT);
            if (finalBadge != null) {
                finalBadge.setTextColor(0xff9de0b8);
                finalBadge.setBackground(rounded(0xff1b3326, dp(a, 8), 0));
            }
        };
        styleRest.run();

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
                labelTv.setTextColor(0xff141517);
                if (finalBadge != null) {
                    finalBadge.setTextColor(0xff141517);
                    finalBadge.setBackground(rounded(0x33141517, dp(a, 8), 0));
                }
            } else {
                styleRest.run();
            }
        });
        row.setOnClickListener(v -> { if (action != null) action.run(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 7);
        container.addView(row, lp);
        return row;
    }

    private static void addSectionHeading(Activity a, LinearLayout parent, String textStr) {
        TextView tv = text(a, 13, true);
        tv.setText(textStr);
        tv.setTextColor(CinemaTokens.GOLD);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tv.setPaddingRelative(dp(a, 4), dp(a, 10), dp(a, 4), dp(a, 6));
        parent.addView(tv);
    }
}
