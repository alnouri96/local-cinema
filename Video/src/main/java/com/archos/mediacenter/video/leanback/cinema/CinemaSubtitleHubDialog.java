package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
 *
 * Features:
 * - Compact Live Timing Overlay shortcut at the top during playback
 * - Smart Subtitle Mode dropdown (Auto-Select, Movie Source, Auto-Download Online Now, Turn Off)
 * - Clear active primary track indicator (gold highlight + ✓ badge) & active secondary track display
 * - Grouped Embedded Video Subtitles dropdown with concise header and native Endonym language names
 * - Choose File from Movie Source (opens movie's own directory directly)
 * - Dual Subtitles controls with dedicated Secondary Subtitle Language picker
 * - Live Arabic + English Subtitle Preview Box with active state indicators inside Live Appearance
 * - Smooth Marquee scrolling for long labels
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
        boolean showDualControls;
        boolean hasSecondaryActive;
        boolean showLiveAppearanceControls;
        long currentDelayMs;
        int currentSize = -1;
        int currentColor = Color.WHITE;
        boolean currentOpaqueBg;
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

        /** Places the Live Timing Overlay Bar shortcut at the very top of the Hub during playback. */
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
            this.showLiveAppearanceControls = callback != null;
            this.currentDelayMs = delayMs;
            this.currentSize = size;
            this.currentColor = color;
            this.currentOpaqueBg = opaqueBg;
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
     * internal video subtitles with native Endonym language names (preferred language first,
     * followed by alphabetical order).
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
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 16));
        scroll.addView(body);

        final AlertDialog[] dlgRef = {null};
        final View[] firstFocus = {null};

        // 0. Top Playback Shortcut: Adjust Subtitle Timing -> Live Timing Overlay Bar
        if (b.onOpenLiveTimingOverlay != null) {
            LinearLayout timingCard = settingsCard(a, body, R.drawable.cinema_ic_timer,
                    CinemaSubtitleTimingOverlay.formatShortcutTitle(a, b.currentDelayMs),
                    a.getString(R.string.cinema_sub_timing_shortcut_hint),
                    null, null, () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        b.onOpenLiveTimingOverlay.run();
                    });
            firstFocus[0] = timingCard;
        }

        // 1. Subtitle Mode Dropdown + Embedded Dropdown + External & Secondary Tracks
        boolean hasTracksSection = b.hasSubtitleModeDropdown || !b.tracks.isEmpty()
                || !b.embeddedTracks.isEmpty() || b.hasSecondaryActive;
        if (hasTracksSection) {
            addSectionHeading(a, body, ar ? "مسارات الترجمة المتاحة" : "Available Subtitle Tracks");

            // 1a. Smart Subtitle Mode Dropdown (Auto-Select / Movie Source / Online Now / Turn Off)
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

            // 1b. External / Stream tracks remain directly visible at level 1 with clear active highlight
            for (TrackChoice tc : b.tracks) {
                LinearLayout card = trackChoiceCard(a, body, tc.label(), tc.selected(), () -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    if (tc.onSelect() != null) tc.onSelect().run();
                });
                if (firstFocus[0] == null) firstFocus[0] = card;
            }

            // 1c. All embedded/internal video subtitles collapsed into ONE concise dropdown right below
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

            // 1d. Active Secondary (Dual) Subtitle status card with one-click turn-off
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
        if (b.onSearchOnline != null) {
            String srcSummary = UnifiedSubtitleConfig.searchSelectionSummary(a, ar);
            String langSummary = UnifiedSubtitleConfig.languageDisplay(UnifiedSubtitleConfig.primaryLanguage(a), ar);
            LinearLayout searchCard = settingsCard(a, body, R.drawable.cinema_globe,
                    label(a, "بحث ذكي عن الترجمة", "Smart Multi-Source Subtitle Search"),
                    srcSummary + " · " + langSummary,
                    null, null, () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        b.onSearchOnline.run();
                    });
            if (firstFocus[0] == null) firstFocus[0] = searchCard;
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

        // 3. Dual Subtitles & Secondary Language Controls
        if (b.showDualControls) {
            addSectionHeading(a, body, ar ? "الترجمة المزدوجة واللغة الثانوية" : "Dual Subtitles & Secondary Language");
            boolean dualOn = UnifiedSubtitleConfig.dualSubtitlesEnabled(a);
            int dualPos = UnifiedSubtitleConfig.dualSubPosition(a);
            String posDesc = switch (dualPos) {
                case 0 -> label(a, "الموضع: كلاهما بالأسفل", "Position: Both at Bottom");
                case 2 -> label(a, "الموضع: الأساسية أعلى · الثانوية أسفل", "Position: Primary Top · Secondary Bottom");
                default -> label(a, "الموضع: الأساسية أسفل · الثانوية أعلى", "Position: Primary Bottom · Secondary Top");
            };
            LinearLayout dualCard = settingsCard(a, body, R.drawable.cinema_subtitles,
                    label(a, "تفعيل الترجمة المزدوجة", "Dual Subtitles Mode"),
                    posDesc,
                    dualOn, null, () -> {
                        boolean next = !UnifiedSubtitleConfig.dualSubtitlesEnabled(a);
                        UnifiedSubtitleConfig.setDualSubtitlesEnabled(a, next);
                        CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(a);
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        if (b.onDualToggled != null) b.onDualToggled.run();
                    });
            if (firstFocus[0] == null) firstFocus[0] = dualCard;

            // Dedicated Secondary Subtitle Language picker lives cleanly inside Dual Subtitles
            String secLangLabel = CinemaLanguagePreferences.localizedSubtitleLabel(
                    a, CinemaLanguagePreferences.secondarySubtitleLanguage(a));
            settingsCard(a, body, R.drawable.cinema_globe,
                    a.getString(R.string.cinema_pref_secondary_sub_lang),
                    secLangLabel,
                    null, null, () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        CinemaLanguagePreferences.chooseSecondaryLanguage(a, chosen -> {
                            CinemaLanguagePreferences.setSecondarySubtitleLanguage(a, chosen);
                            if (b.onDualToggled != null) b.onDualToggled.run();
                        });
                    });

            if (b.onDualPositionCycled != null) {
                settingsCard(a, body, R.drawable.cinema_ic_appearance,
                        label(a, "تبديل موضع الترجمة المزدوجة", "Switch Dual Subtitle Position"),
                        posDesc,
                        null, null, () -> {
                            int nextPos = (UnifiedSubtitleConfig.dualSubPosition(a) + 1) % 3;
                            UnifiedSubtitleConfig.setDualSubPosition(a, nextPos);
                            CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(a);
                            if (dlgRef[0] != null) dlgRef[0].dismiss();
                            b.onDualPositionCycled.run();
                        });
            }
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

        // 4. Live Appearance Controls with Compact Arabic + English Live Preview Box & Active Indicators
        if (b.showLiveAppearanceControls && b.styleCallback != null) {
            addSectionHeading(a, body, ar ? "المظهر الفوري" : "Live Appearance");

            final int[] sizeState = {b.hasExplicitAppearanceState && b.currentSize > 0
                    ? b.currentSize : UnifiedSubtitleConfig.textSize(a)};
            final int[] colorState = {b.hasExplicitAppearanceState
                    ? b.currentColor : UnifiedSubtitleConfig.textColor(a)};
            final boolean[] bgState = {b.hasExplicitAppearanceState
                    ? b.currentOpaqueBg : UnifiedSubtitleConfig.opaqueBg(a)};
            final boolean[] visibleState = {b.hasExplicitAppearanceState ? b.currentVisible : true};

            LinearLayout previewFrame = new LinearLayout(a);
            previewFrame.setOrientation(LinearLayout.VERTICAL);
            previewFrame.setGravity(Gravity.CENTER);
            previewFrame.setBackground(rounded(0xff0d0e11, dp(a, 10), 0xff3b3629));
            previewFrame.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
            LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(-1, -2);
            frameLp.bottomMargin = dp(a, 10);

            LinearLayout previewPill = new LinearLayout(a);
            previewPill.setOrientation(LinearLayout.VERTICAL);
            previewPill.setGravity(Gravity.CENTER);
            previewPill.setPadding(dp(a, 14), dp(a, 7), dp(a, 14), dp(a, 7));

            TextView previewAr = text(a, 16, true);
            previewAr.setText(a.getString(R.string.cinema_sub_preview_ar));
            previewAr.setGravity(Gravity.CENTER);
            previewAr.setMaxLines(2);
            previewPill.addView(previewAr);

            TextView previewEn = text(a, 14, true);
            previewEn.setText(a.getString(R.string.cinema_sub_preview_en));
            previewEn.setGravity(Gravity.CENTER);
            previewEn.setMaxLines(2);
            previewEn.setPadding(0, dp(a, 3), 0, 0);
            previewPill.addView(previewEn);

            TextView previewHiddenTv = text(a, 13, true);
            previewHiddenTv.setText(a.getString(R.string.cinema_sub_preview_hidden));
            previewHiddenTv.setTextColor(0xff9aa0aa);
            previewHiddenTv.setGravity(Gravity.CENTER);
            previewHiddenTv.setVisibility(View.GONE);

            previewFrame.addView(previewPill, new LinearLayout.LayoutParams(-2, -2));
            previewFrame.addView(previewHiddenTv, new LinearLayout.LayoutParams(-2, -2));
            body.addView(previewFrame, frameLp);

            final Runnable[] refreshAppearance = {null};

            LinearLayout row1 = chipRow(a, body);
            TextView visibleChip = addStatefulControlChip(a, row1, "",
                    () -> visibleState[0],
                    () -> {
                        visibleState[0] = !visibleState[0];
                        b.styleCallback.onAction("visible", 0);
                        if (refreshAppearance[0] != null) refreshAppearance[0].run();
                    });
            addControlChip(a, row1, "A−", () -> {
                sizeState[0] = Math.max(16, sizeState[0] - 2);
                b.styleCallback.onAction("size", -2);
                if (refreshAppearance[0] != null) refreshAppearance[0].run();
            });
            addControlChip(a, row1, "A+", () -> {
                sizeState[0] = Math.min(72, sizeState[0] + 2);
                b.styleCallback.onAction("size", 2);
                if (refreshAppearance[0] != null) refreshAppearance[0].run();
            });
            addControlChip(a, row1, "↑", () -> b.styleCallback.onAction("position", 5));
            addControlChip(a, row1, "↓", () -> b.styleCallback.onAction("position", -5));

            LinearLayout row2 = chipRow(a, body);
            final int[] palette = {Color.WHITE, 0xffffd54f, 0xff4dd0e1, 0xff81c784};
            final String[] colorNames = {
                    ar ? "أبيض" : "White",
                    ar ? "أصفر" : "Yellow",
                    ar ? "سماوي" : "Cyan",
                    ar ? "أخضر" : "Green"
            };
            final ColorChipRef[] colorChips = new ColorChipRef[palette.length];
            for (int i = 0; i < palette.length; i++) {
                final int chipColor = palette[i];
                colorChips[i] = addStatefulColorChip(a, row2, colorNames[i], chipColor,
                        () -> isSameColor(colorState[0], chipColor),
                        () -> {
                            colorState[0] = chipColor;
                            b.styleCallback.onAction("color", chipColor);
                            if (refreshAppearance[0] != null) refreshAppearance[0].run();
                        });
            }

            LinearLayout row3 = chipRow(a, body);
            TextView bgChip = addStatefulControlChip(a, row3, "",
                    () -> true,
                    () -> {
                        bgState[0] = !bgState[0];
                        b.styleCallback.onAction("background", 0);
                        if (refreshAppearance[0] != null) refreshAppearance[0].run();
                    });
            addControlChip(a, row3, ar ? "تصفير المظهر والتوقيت" : "Reset All", () -> {
                visibleState[0] = true;
                sizeState[0] = 36;
                colorState[0] = Color.WHITE;
                bgState[0] = false;
                b.styleCallback.onAction("reset", 0);
                if (refreshAppearance[0] != null) refreshAppearance[0].run();
            });

            refreshAppearance[0] = () -> {
                float arSp = Math.max(12.5f, Math.min(23.5f, 12.5f + (sizeState[0] - 18) * 0.21f));
                float enSp = Math.max(11.5f, Math.min(21.5f, arSp - 1.5f));
                previewAr.setTextSize(arSp);
                previewEn.setTextSize(enSp);
                previewAr.setTextColor(colorState[0]);
                previewEn.setTextColor(colorState[0]);
                if (bgState[0]) {
                    previewPill.setBackground(rounded(0xcc000000, dp(a, 8), 0x44ffffff));
                    previewAr.setShadowLayer(1.5f, 0f, 1f, 0xaa000000);
                    previewEn.setShadowLayer(1.5f, 0f, 1f, 0xaa000000);
                } else {
                    previewPill.setBackground(null);
                    previewAr.setShadowLayer(3.5f, 0f, 1.5f, 0xf0000000);
                    previewEn.setShadowLayer(3.5f, 0f, 1.5f, 0xf0000000);
                }
                if (visibleState[0]) {
                    previewPill.setVisibility(View.VISIBLE);
                    previewHiddenTv.setVisibility(View.GONE);
                } else {
                    previewPill.setVisibility(View.GONE);
                    previewHiddenTv.setVisibility(View.VISIBLE);
                }

                visibleChip.setText(visibleState[0]
                        ? a.getString(R.string.cinema_sub_visible_on)
                        : a.getString(R.string.cinema_sub_visible_off));
                styleStatefulControlChip(a, visibleChip, visibleState[0], visibleChip.hasFocus());

                for (int i = 0; i < colorChips.length; i++) {
                    boolean active = isSameColor(colorState[0], palette[i]);
                    colorChips[i].label.setText(active ? (colorNames[i] + " ✓") : colorNames[i]);
                    styleStatefulColorChip(a, colorChips[i], active, colorChips[i].root.hasFocus());
                }

                bgChip.setText(bgState[0]
                        ? a.getString(R.string.cinema_sub_bg_dark_on)
                        : a.getString(R.string.cinema_sub_bg_trans_on));
                styleStatefulControlChip(a, bgChip, true, bgChip.hasFocus());
            };
            refreshAppearance[0].run();
        }

        // 5. Unified Subtitle Settings (clean single entry; Preferred Subtitle Language lives inside Settings)
        addSectionHeading(a, body, ar ? "الإعدادات" : "Settings");
        settingsCard(a, body, R.drawable.cinema_ic_system,
                label(a, "إعدادات الترجمة الموحّدة ومصادرها", "Unified Subtitle Settings & Sources"),
                label(a, "المصادر المفتوحة · لغة الترجمة · سلوك الاختيار التلقائي · المظهر والمزامنة",
                        "Open sources · Subtitle language · Auto-select · Appearance & Sync"),
                null, null, () -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    a.startActivity(new Intent(a, SubsourceSettingsActivity.class));
                });

        String title = (b.headerTitle != null && !b.headerTitle.isBlank())
                ? b.headerTitle
                : a.getString(R.string.cinema_sub_hub_short);

        AlertDialog dlg = dialog(a)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(label(a, "إغلاق", "Close"), null)
                .create();
        dlgRef[0] = dlg;
        dlg.setOnShowListener(d -> {
            widenSubtitleDialog(dlg.getWindow());
            if (firstFocus[0] != null) firstFocus[0].requestFocus();
        });
        dlg.show();
        return dlg;
    }

    private static boolean isSameColor(int actual, int swatch) {
        if (actual == swatch) return true;
        if (swatch == 0xffffd54f && (actual == Color.YELLOW || actual == 0xffe5b94c)) return true;
        if (swatch == 0xff4dd0e1 && (actual == Color.CYAN || actual == 0xff00e5ff)) return true;
        if (swatch == 0xff81c784 && actual == Color.GREEN) return true;
        return false;
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

    private static LinearLayout chipRow(Activity a, LinearLayout parent) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 8);
        parent.addView(row, lp);
        return row;
    }

    private static void addControlChip(Activity a, LinearLayout row, String labelText, Runnable click) {
        TextView btn = text(a, 13, true);
        btn.setText(labelText);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setPadding(dp(a, 8), dp(a, 10), dp(a, 8), dp(a, 10));
        btn.setBackground(rounded(0xff202127, dp(a, 10), 0xff3b372c));
        btn.setTextColor(CinemaTokens.TEXT);
        btn.setOnFocusChangeListener((v, has) -> {
            btn.setBackground(rounded(has ? CinemaTokens.FOCUS : 0xff202127, dp(a, 10),
                    has ? CinemaTokens.FOCUS : 0xff3b372c));
            btn.setTextColor(has ? 0xff141517 : CinemaTokens.TEXT);
        });
        btn.setOnClickListener(v -> { if (click != null) click.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginEnd(dp(a, 6));
        row.addView(btn, lp);
    }

    private static TextView addStatefulControlChip(
            Activity a,
            LinearLayout row,
            String initialText,
            java.util.function.BooleanSupplier isActive,
            Runnable click) {
        TextView btn = text(a, 13, true);
        btn.setText(initialText);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setPadding(dp(a, 8), dp(a, 10), dp(a, 8), dp(a, 10));
        styleStatefulControlChip(a, btn, isActive.getAsBoolean(), false);
        btn.setOnFocusChangeListener((v, has) -> styleStatefulControlChip(a, btn, isActive.getAsBoolean(), has));
        btn.setOnClickListener(v -> { if (click != null) click.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginEnd(dp(a, 6));
        row.addView(btn, lp);
        return btn;
    }

    private static void styleStatefulControlChip(Activity a, TextView btn, boolean active, boolean focused) {
        if (focused) {
            btn.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
            btn.setTextColor(0xff141517);
        } else if (active) {
            btn.setBackground(rounded(0xff272318, dp(a, 10), 0xbbE5A93B));
            btn.setTextColor(0xfff8e2a6);
        } else {
            btn.setBackground(rounded(0xff291d1d, dp(a, 10), 0x88d46a6a));
            btn.setTextColor(0xfff2a29d);
        }
    }

    private record ColorChipRef(LinearLayout root, TextView label) {}

    private static ColorChipRef addStatefulColorChip(
            Activity a,
            LinearLayout row,
            String labelText,
            int color,
            java.util.function.BooleanSupplier isActive,
            Runnable click) {
        LinearLayout chip = new LinearLayout(a);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setPadding(dp(a, 8), dp(a, 9), dp(a, 8), dp(a, 9));

        View dot = new View(a);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(a, 1), 0x88000000);
        dot.setBackground(d);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(a, 12), dp(a, 12));
        dotLp.setMarginEnd(dp(a, 6));
        chip.addView(dot, dotLp);

        TextView tv = text(a, 12, true);
        tv.setText(labelText);
        tv.setSingleLine(true);
        chip.addView(tv);

        ColorChipRef ref = new ColorChipRef(chip, tv);
        styleStatefulColorChip(a, ref, isActive.getAsBoolean(), false);

        chip.setOnFocusChangeListener((v, has) -> styleStatefulColorChip(a, ref, isActive.getAsBoolean(), has));
        chip.setOnClickListener(v -> { if (click != null) click.run(); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginEnd(dp(a, 6));
        row.addView(chip, lp);
        return ref;
    }

    private static void styleStatefulColorChip(Activity a, ColorChipRef ref, boolean active, boolean focused) {
        if (focused) {
            ref.root.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
            ref.label.setTextColor(0xff141517);
        } else if (active) {
            ref.root.setBackground(rounded(0xff292418, dp(a, 10), 0xddE5A93B));
            ref.label.setTextColor(0xfff8e2a6);
        } else {
            ref.root.setBackground(rounded(0xff202127, dp(a, 10), 0xff3b372c));
            ref.label.setTextColor(CinemaTokens.TEXT);
        }
    }
}
