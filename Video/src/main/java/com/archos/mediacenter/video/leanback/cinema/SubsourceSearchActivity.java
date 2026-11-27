package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediascraper.BaseTags;
import com.meedx.providers.SubtitleArabicTranslator;
import com.meedx.providers.SubtitleMatch;
import com.meedx.providers.UnifiedSubtitleConfig;
import com.meedx.providers.UnifiedSubtitleEngine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/**
 * Unified Smart Multi-Source Subtitle Search on TV:
 * - Resolves Arabic/Persian/non-English titles into verified English/IMDb candidates without false guessing.
 * - Provides a one-click Title Candidates Switcher (English suggestions, Original source title, Manual/Phone paste).
 * - Lets users search All Enabled sources, a single source, or any multi-select subset.
 * - Supports one-click Arabic translation for English/Persian/foreign subtitles via live-verified SubtitleArabicTranslator.
 */
public class SubsourceSearchActivity extends LeanbackActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Video video;
    private String imdb = "";
    private long tmdbId = 0L;
    private int season = -1;
    private int episode = -1;
    private int year = -1;
    private boolean isSeries = false;
    private boolean hasIndexedMetadata = false;
    private String rawDisplayTitle = "";
    private String alternateDbTitle = "";
    private String cleanedFilenameFallback = "";
    private String intentEnglishHint = "";
    private String resolvedEnglishTitle = "";
    private String videoFilenameHint = "";
    private long videoSizeHint = 0L;
    private String videoHashHint = "";
    private String addonItemIdHint = "";
    private String language;
    private List<UnifiedSubtitleEngine.TitleCandidate> titleCandidates = List.of();
    private String activeChipKey = "";

    private EditText query;
    private TextView resolvedBadge;
    private HorizontalScrollView candidatesScroll;
    private LinearLayout candidatesRow;
    private TextView status;
    private LinearLayout sourceCard;
    private LinearLayout languageCard;
    private LinearLayout results;
    private boolean busy;
    private int requestGeneration;

    private String l(String ar, String en) { return label(this, ar, en); }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        CinemaSubtitleSyncBridge.ensureBootstrapped(this);
        boolean rtl = arabic(this);
        language = UnifiedSubtitleConfig.primaryLanguage(this);
        video = (Video) getIntent().getSerializableExtra(VideoDetailsFragment.EXTRA_VIDEO);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(CinemaUi.surface());
        scroll.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(this, CinemaTokens.UTILITY_GUTTER), dp(this, CinemaTokens.SAFE),
                dp(this, CinemaTokens.UTILITY_GUTTER), dp(this, CinemaTokens.SAFE));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = text(this, CinemaTokens.PAGE_TITLE, true);
        title.setText(l("البحث الذكي عن الترجمة", "Smart Multi-Source Subtitle Search"));
        content.addView(title);

        resolvedBadge = text(this, 13, true);
        resolvedBadge.setTextColor(CinemaTokens.GOLD);
        resolvedBadge.setBackground(rounded(0xff232018, dp(this, 10), 0xff5a4a24));
        resolvedBadge.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        resolvedBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-1, -2);
        badgeLp.topMargin = dp(this, 8);
        content.addView(resolvedBadge, badgeLp);

        status = text(this, CinemaTokens.LABEL, false);
        status.setTextColor(0xffb8b8c0);
        status.setPadding(0, dp(this, 10), 0, dp(this, 8));
        content.addView(status);

        content.addView(CinemaPhoneLink.pasteBadge(this));
        query = new EditText(this);
        query.setTextColor(Color.WHITE);
        query.setSingleLine(true);
        query.setHintTextColor(0xff8899aa);
        query.setHint(l("اسم الفيلم أو المسلسل (بالعربية أو الإنجليزية)", "Movie or show title (Arabic or English)"));
        query.setBackground(rounded(0xff1d1e24, dp(this, 10), 0xff3b3932));
        query.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(-1, -2);
        qLp.bottomMargin = dp(this, 8);
        content.addView(query, qLp);

        // Quick Title Candidates Switcher (English Suggestions / Original Source Title / Manual Edit & Paste)
        candidatesScroll = new HorizontalScrollView(this);
        candidatesScroll.setHorizontalScrollBarEnabled(false);
        candidatesScroll.setClipChildren(false);
        candidatesScroll.setClipToPadding(false);
        candidatesScroll.setVisibility(View.GONE);
        candidatesRow = new LinearLayout(this);
        candidatesRow.setOrientation(LinearLayout.HORIZONTAL);
        candidatesRow.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        candidatesRow.setClipChildren(false);
        candidatesRow.setClipToPadding(false);
        candidatesRow.setPadding(0, dp(this, 2), 0, dp(this, 6));
        candidatesScroll.addView(candidatesRow);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(-1, -2);
        csLp.bottomMargin = dp(this, 8);
        content.addView(candidatesScroll, csLp);

        // Source Selector (All Enabled / Single Source / Multi-Select)
        sourceCard = settingsCard(this, content, R.drawable.cinema_ic_addons,
                l("مصادر البحث عن الترجمة", "Subtitle Search Sources"),
                UnifiedSubtitleConfig.searchSelectionSummary(this, rtl),
                null, null, this::openSourcePickerDialog);

        // Language Selector
        languageCard = settingsCard(this, content, R.drawable.cinema_globe,
                l("لغة البحث", "Search Language"),
                UnifiedSubtitleConfig.languageDisplay(language, rtl),
                null, null, this::chooseLanguage);

        // Action Buttons Row (Search + Unified Settings)
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setClipChildren(false);
        btnRow.setClipToPadding(false);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.bottomMargin = dp(this, 14);
        content.addView(btnRow, brLp);

        TextView searchBtn = actionPill(l("بحث الآن", "Search Now"), true, this::runSearch);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, -2, 1f);
        sLp.setMarginEnd(dp(this, 10));
        btnRow.addView(searchBtn, sLp);

        TextView settingsBtn = actionPill(l("إعدادات ومصادر الترجمة", "Subtitle Settings & Sources"), false,
                () -> startActivity(new Intent(this, SubsourceSettingsActivity.class)));
        btnRow.addView(settingsBtn, new LinearLayout.LayoutParams(0, -2, 1f));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        results.setClipChildren(false);
        results.setClipToPadding(false);
        content.addView(results);

        status.setText(l("جارٍ تحليل معلومات الفيديو وجلب الاسم الإنجليزي الأصلي…",
                "Reading video info & resolving original English title…"));
        loadVideoMetadataAndSearch();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sourceCard != null) {
            updateSettingsCardSubtitle(sourceCard, UnifiedSubtitleConfig.searchSelectionSummary(this, arabic(this)));
        }
    }

    private TextView actionPill(String labelText, boolean primary, Runnable click) {
        TextView tv = text(this, 15, true);
        tv.setText(labelText);
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        Runnable rest = () -> {
            tv.setBackground(rounded(primary ? 0xff2b2517 : 0xff1d1e21, dp(this, 10),
                    primary ? CinemaTokens.GOLD : 0xff34322c));
            tv.setTextColor(primary ? CinemaTokens.GOLD : CinemaTokens.TEXT);
        };
        rest.run();
        tv.setOnFocusChangeListener((v, has) -> {
            if (has) {
                tv.setBackground(rounded(CinemaTokens.FOCUS, dp(this, 10), CinemaTokens.FOCUS));
                tv.setTextColor(0xff141517);
            } else {
                rest.run();
            }
        });
        tv.setOnClickListener(v -> click.run());
        return tv;
    }

    private void renderTitleCandidateChips() {
        candidatesRow.removeAllViews();
        boolean hasAny = false;
        Set<String> shownNormTitles = new LinkedHashSet<>();

        // 1. Priority 1 & 2: Clean MediaDB/Poster English/Original Title + Alternate DB Title
        for (int i = 0; i < Math.min(3, titleCandidates.size()); i++) {
            final UnifiedSubtitleEngine.TitleCandidate cand = titleCandidates.get(i);
            String chipLabel = cand.title() + (cand.year() > 0 ? " (" + cand.year() + ")" : "");
            String chipKey = "cand:" + cand.title() + ":" + cand.imdbId();
            boolean active = chipKey.equals(activeChipKey);
            shownNormTitles.add(cand.title().trim().toLowerCase(Locale.ROOT));
            addCandidateChip(chipLabel, active, () -> {
                if (busy) return;
                activeChipKey = chipKey;
                resolvedEnglishTitle = cand.title();
                imdb = cand.imdbId();
                if (cand.year() > 0) year = cand.year();
                query.setText(cand.title());
                query.setSelection(query.getText().length());
                renderTitleCandidateChips();
                runSearch();
            });
            hasAny = true;
        }

        // Ensure Alternate DB Title (Priority 2) is clickable even if not in top 3
        if (alternateDbTitle != null && !alternateDbTitle.isBlank()
                && shownNormTitles.add(alternateDbTitle.trim().toLowerCase(Locale.ROOT))) {
            final String altTitle = alternateDbTitle;
            String altKey = "alt:" + altTitle;
            boolean active = altKey.equals(activeChipKey);
            addCandidateChip(altTitle, active, () -> {
                if (busy) return;
                activeChipKey = altKey;
                resolvedEnglishTitle = altTitle;
                query.setText(altTitle);
                query.setSelection(query.getText().length());
                renderTitleCandidateChips();
                runSearch();
            });
            hasAny = true;
        }

        // 2. Priority 3 (Secondary clickable chip when indexed; primary only when unindexed): Cleaned Filename
        String secondaryFileChip = !cleanedFilenameFallback.isBlank() ? cleanedFilenameFallback : rawDisplayTitle;
        if (secondaryFileChip != null && !secondaryFileChip.isBlank()
                && shownNormTitles.add(secondaryFileChip.trim().toLowerCase(Locale.ROOT))) {
            final String fileChipTitle = secondaryFileChip;
            String origKey = "orig:" + fileChipTitle;
            boolean active = origKey.equals(activeChipKey);
            addCandidateChip(getString(R.string.cinema_sub_chip_source_title, fileChipTitle), active, () -> {
                if (busy) return;
                activeChipKey = origKey;
                resolvedEnglishTitle = fileChipTitle;
                imdb = "";
                query.setText(fileChipTitle);
                query.setSelection(query.getText().length());
                renderTitleCandidateChips();
                runSearch();
            });
            hasAny = true;
        }

        candidatesScroll.setVisibility(hasAny ? View.VISIBLE : View.GONE);
    }

    private void addCandidateChip(String labelText, boolean active, Runnable click) {
        TextView chip = text(this, 13, true);
        chip.setText(active ? (labelText + " ✓") : labelText);
        chip.setSingleLine(true);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        Runnable rest = () -> {
            chip.setBackground(rounded(active ? 0xff2b2517 : 0xff1d1e24, dp(this, 16),
                    active ? CinemaTokens.GOLD : 0xff3b3932));
            chip.setTextColor(active ? 0xfff8e2a6 : CinemaTokens.TEXT);
        };
        rest.run();
        chip.setOnFocusChangeListener((v, has) -> {
            if (has) {
                chip.setBackground(rounded(CinemaTokens.FOCUS, dp(this, 16), CinemaTokens.FOCUS));
                chip.setTextColor(0xff141517);
            } else {
                rest.run();
            }
        });
        chip.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(this, 8));
        candidatesRow.addView(chip, lp);
    }

    private void loadVideoMetadataAndSearch() {
        io.execute(() -> {
            try {
                if (video == null) {
                    long id = getIntent().getLongExtra("video_id", -1);
                    if (id >= 0) {
                        try (Cursor c = getContentResolver().query(
                                com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                                com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,
                                "_id=?", new String[]{Long.toString(id)}, null)) {
                            if (c != null && c.moveToFirst()) {
                                VideoCursorMapper m = new VideoCursorMapper();
                                m.bindColumns(c);
                                video = (Video) m.bind(c);
                            }
                        }
                    }
                    if (video == null && getIntent().getData() != null) {
                        video = new NonIndexedVideo(getIntent().getData(), getIntent().getData(),
                                getIntent().getStringExtra("title"), null);
                    }
                }
                String intentTitle = cleanTitle(getIntent().getStringExtra("title"));
                String intentPosterTitle = cleanTitle(getIntent().getStringExtra(SubtitleSources.EXTRA_POSTER_TITLE));
                intentEnglishHint = cleanTitle(getIntent().getStringExtra("english_title"));
                String intentHeading = cleanTitle(getIntent().getStringExtra("addon_heading"));
                String intentImdb = cleanTitle(getIntent().getStringExtra("imdb_id"));
                long intentTmdb = getIntent().getLongExtra(SubtitleSources.EXTRA_TMDB_ID, 0L);
                int intentYear = getIntent().getIntExtra("release_year", -1);
                int intentSeason = getIntent().getIntExtra("season", -1);
                int intentEpisode = getIntent().getIntExtra("episode", -1);
                videoFilenameHint = cleanTitle(getIntent().getStringExtra("video_filename"));
                videoSizeHint = Math.max(0L, getIntent().getLongExtra("video_size", 0L));
                videoHashHint = cleanTitle(getIntent().getStringExtra("video_hash"));
                addonItemIdHint = cleanTitle(getIntent().getStringExtra("addon_item_id"));
                if (intentImdb.matches("tt[0-9]+")) imdb = intentImdb;
                if (intentTmdb > 0L) tmdbId = intentTmdb;
                if (intentYear > 0) year = intentYear;
                if (intentSeason > 0) season = intentSeason;
                if (intentEpisode > 0) episode = intentEpisode;

                if (video instanceof Movie movie && year <= 0) year = movie.getYear();
                if (video instanceof Episode ep) {
                    season = ep.getSeasonNumber();
                    episode = ep.getEpisodeNumber();
                    isSeries = true;
                }
                if (video != null) {
                    if (videoFilenameHint.isEmpty() && video.getFileNameWithExtension() != null) {
                        videoFilenameHint = video.getFileNameWithExtension();
                    }
                    if (videoSizeHint <= 0L && video.getSize() > 0L) {
                        videoSizeHint = video.getSize();
                    }
                    Uri localCheckUri = video.getFileUri();
                    if (videoHashHint.isEmpty() && localCheckUri != null
                            && (localCheckUri.getScheme() == null || "file".equalsIgnoreCase(localCheckUri.getScheme()))
                            && localCheckUri.getPath() != null) {
                        java.io.File f = new java.io.File(localCheckUri.getPath());
                        if (f.isFile() && f.length() > 0L) {
                            if (videoSizeHint <= 0L) videoSizeHint = f.length();
                            String computed = com.archos.mediacenter.video.utils.OpenSubtitlesHasher.computeHash(f);
                            if (computed != null) videoHashHint = computed;
                        }
                    }

                    // Strict 3-tier resolution:
                    // 1) Clean Poster/MediaDB Title (preferring TMDb English/Original)
                    // 2) Alternate/Localized DB Title (cleaned of '|', 720p/1080p, extensions)
                    // 3) Cleaned Filename (auto-used ONLY when unindexed; secondary chip when indexed)
                    String bestPosterHint = !intentPosterTitle.isEmpty() ? intentPosterTitle : intentHeading;
                    SubtitleSources.MediaSearchSeed seed = SubtitleSources.resolveSeed(
                            getApplicationContext(), video, bestPosterHint, intentEnglishHint, imdb, tmdbId, year);
                    hasIndexedMetadata = seed.hasIndexedRecord();
                    cleanedFilenameFallback = seed.cleanedFilename();
                    alternateDbTitle = seed.alternateDbTitle();
                    if (!seed.imdbId().isEmpty()) imdb = seed.imdbId();
                    if (seed.tmdbId() > 0L) tmdbId = seed.tmdbId();
                    if (seed.year() > 0) year = seed.year();
                    if (seed.isSeries()) isSeries = true;
                    if (season <= 0 && seed.season() > 0) season = seed.season();
                    if (episode <= 0 && seed.episode() > 0) episode = seed.episode();

                    if (hasIndexedMetadata) {
                        rawDisplayTitle = seed.primaryPosterTitle();
                        if (!seed.englishTitleHint().isEmpty()) intentEnglishHint = seed.englishTitleHint();
                    } else {
                        rawDisplayTitle = seed.cleanedFilename();
                        Uri uri = video.getFileUri();
                        boolean isHttp = uri != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
                        if (!isHttp && uri != null) {
                            try {
                                com.archos.mediascraper.preprocess.SearchInfo parsed =
                                        com.archos.mediascraper.preprocess.SearchPreprocessor.instance().parseFileBased(uri, uri);
                                if (parsed instanceof com.archos.mediascraper.preprocess.MovieSearchInfo m) {
                                    String fn = UnifiedSubtitleEngine.cleanRawTitle(m.getName());
                                    if (!fn.isEmpty() && (!UnifiedSubtitleEngine.containsNonLatin(fn) || rawDisplayTitle.isEmpty())) {
                                        if (intentEnglishHint.isEmpty() && !UnifiedSubtitleEngine.containsNonLatin(fn)) {
                                            intentEnglishHint = fn;
                                        }
                                        if (rawDisplayTitle.isEmpty()) rawDisplayTitle = fn;
                                    }
                                    if (year <= 0) {
                                        try { year = Integer.parseInt(m.getYear()); } catch (Exception ignored) {}
                                    }
                                } else if (parsed instanceof com.archos.mediascraper.preprocess.TvShowSearchInfo s) {
                                    isSeries = true;
                                    String fn = UnifiedSubtitleEngine.cleanRawTitle(s.getShowName());
                                    if (!fn.isEmpty() && (!UnifiedSubtitleEngine.containsNonLatin(fn) || rawDisplayTitle.isEmpty())) {
                                        if (intentEnglishHint.isEmpty() && !UnifiedSubtitleEngine.containsNonLatin(fn)) {
                                            intentEnglishHint = fn;
                                        }
                                        if (rawDisplayTitle.isEmpty()) rawDisplayTitle = fn;
                                    }
                                    if (season <= 0) season = s.getSeason();
                                    if (episode <= 0) episode = s.getEpisode();
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (!intentHeading.isEmpty() && (rawDisplayTitle.isEmpty() || "video".equalsIgnoreCase(rawDisplayTitle))) {
                    rawDisplayTitle = UnifiedSubtitleEngine.cleanRawTitle(intentHeading);
                } else if (!intentTitle.isEmpty() && (rawDisplayTitle.isEmpty() || "video".equalsIgnoreCase(rawDisplayTitle))) {
                    rawDisplayTitle = UnifiedSubtitleEngine.cleanRawTitle(stripEpisodeSuffix(intentTitle));
                }
                if (season > 0 || episode > 0) isSeries = true;

                UnifiedSubtitleEngine.ResolvedQuery rq = UnifiedSubtitleEngine.resolveQuery(
                        getApplicationContext(), rawDisplayTitle, intentEnglishHint, alternateDbTitle,
                        imdb, tmdbId, year, isSeries, season, episode, hasIndexedMetadata);
                resolvedEnglishTitle = rq.englishTitle;
                titleCandidates = rq.candidates;
                if (!rq.imdbId.isEmpty()) imdb = rq.imdbId;
                if (rq.year > 0) year = rq.year;
                if (rq.season > 0) season = rq.season;
                if (rq.episode > 0) episode = rq.episode;
                isSeries = rq.isSeries;

                if (!titleCandidates.isEmpty()) {
                    var top = titleCandidates.get(0);
                    activeChipKey = "cand:" + top.title() + ":" + top.imdbId();
                } else {
                    String fallbackChip = !cleanedFilenameFallback.isBlank() ? cleanedFilenameFallback : rawDisplayTitle;
                    activeChipKey = "orig:" + fallbackChip;
                }

                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    query.setText(resolvedEnglishTitle);
                    query.setSelection(query.getText().length());
                    renderTitleCandidateChips();
                    updateResolvedBanner(rq);
                    if (!resolvedEnglishTitle.isEmpty() || !imdb.isEmpty()) {
                        runSearch();
                    } else {
                        status.setText(l("اكتب اسم الفيلم أو المسلسل ثم اضغط بحث", "Type a movie or show title and press Search"));
                        query.requestFocus();
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (!isDestroyed()) status.setText(l("تعذر قراءة معلومات الفيديو", "Could not read video information"));
                });
            }
        });
    }

    private void updateResolvedBanner(UnifiedSubtitleEngine.ResolvedQuery rq) {
        if (rq == null) {
            resolvedBadge.setVisibility(View.GONE);
            return;
        }
        StringBuilder info = new StringBuilder();
        if (rq.resolvedFromNonEnglish && !rq.englishTitle.equalsIgnoreCase(rq.displayTitle)) {
            info.append(l("تم جلب الاسم الإنجليزي تلقائياً: ", "Resolved English title: "))
                    .append(rq.englishTitle);
        } else if (!rq.imdbId.isEmpty() || (rq.isSeries && rq.season > 0 && rq.episode > 0)) {
            info.append(l("بيانات البحث: ", "Matched metadata: ")).append(rq.englishTitle);
        }
        if (info.length() > 0) {
            if (rq.year > 0) info.append(" (").append(rq.year).append(")");
            if (rq.isSeries && rq.season > 0 && rq.episode > 0) {
                info.append(String.format(Locale.US, " · S%02dE%02d", rq.season, rq.episode));
            }
            if (!rq.imdbId.isEmpty()) info.append(" · ").append(rq.imdbId);
            resolvedBadge.setText(info.toString());
            resolvedBadge.setVisibility(View.VISIBLE);
        } else {
            resolvedBadge.setVisibility(View.GONE);
        }
    }

    private void openSourcePickerDialog() {
        boolean rtl = arabic(this);
        List<UnifiedSubtitleConfig.ProviderInfo> providers = UnifiedSubtitleConfig.allProviders(this);
        boolean allMode = UnifiedSubtitleConfig.MODE_ALL.equals(UnifiedSubtitleConfig.searchSelectionMode(this));
        Set<String> selected = new LinkedHashSet<>(UnifiedSubtitleConfig.selectedSearchSources(this));

        boolean[] allFlag = {allMode};
        boolean[] checks = new boolean[providers.size()];
        for (int i = 0; i < providers.size(); i++) {
            checks[i] = !allMode && selected.contains(providers.get(i).id);
        }

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(this, 4));
        list.setPadding(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));
        list.setSelector(rounded(0xff35281b, dp(this, 12), CinemaTokens.GOLD));

        var adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return providers.size() + 1; }
            @Override public Object getItem(int pos) { return pos == 0 ? "ALL" : providers.get(pos - 1); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View recycled, android.view.ViewGroup parent) {
                LinearLayout row = new LinearLayout(SubsourceSearchActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(SubsourceSearchActivity.this, 50));
                row.setPaddingRelative(dp(SubsourceSearchActivity.this, 14), dp(SubsourceSearchActivity.this, 8),
                        dp(SubsourceSearchActivity.this, 14), dp(SubsourceSearchActivity.this, 8));

                ImageView box = new ImageView(SubsourceSearchActivity.this);
                boolean isChecked = pos == 0 ? allFlag[0] : checks[pos - 1];
                box.setImageDrawable(createCinemaCheckDrawable(SubsourceSearchActivity.this, isChecked));
                int sz = dp(SubsourceSearchActivity.this, 22);
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(sz, sz);
                bLp.setMarginEnd(dp(SubsourceSearchActivity.this, 12));
                row.addView(box, bLp);

                LinearLayout col = new LinearLayout(SubsourceSearchActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView t1 = text(SubsourceSearchActivity.this, 15, true);
                TextView t2 = text(SubsourceSearchActivity.this, 12, false);
                if (pos == 0) {
                    t1.setText(l("الكل (جميع المصادر المفعّلة معاً)", "All Enabled Sources Together"));
                    t2.setText(l("يبحث بالتوازي في جميع المصادر المفتوحة والمحفوظة", "Parallel search across all enabled sources"));
                    t1.setTextColor(isChecked ? CinemaTokens.GOLD : Color.WHITE);
                } else {
                    UnifiedSubtitleConfig.ProviderInfo p = providers.get(pos - 1);
                    boolean ready = p.keyless || UnifiedSubtitleConfig.hasKey(SubsourceSearchActivity.this, p);
                    t1.setText(p.name + (ready ? "" : l(" (يحتاج مفتاح API)", " (API key needed)")));
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

        dialog(this)
                .setTitle(l("اختر مصدر واحد أو عدة مصادر للبحث", "Choose One or Multiple Search Sources"))
                .setView(list)
                .setPositiveButton(l("تطبيق والبحث", "Apply & Search"), (d, w) -> {
                    if (allFlag[0]) {
                        UnifiedSubtitleConfig.setSearchSelectionAll(this);
                    } else {
                        Set<String> picked = new LinkedHashSet<>();
                        for (int i = 0; i < providers.size(); i++) {
                            if (checks[i]) picked.add(providers.get(i).id);
                        }
                        UnifiedSubtitleConfig.setSearchSelectionCustom(this, picked);
                    }
                    CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
                    updateSettingsCardSubtitle(sourceCard, UnifiedSubtitleConfig.searchSelectionSummary(this, rtl));
                    runSearch();
                })
                .setNegativeButton(R.string.addon_back, null)
                .show();
        list.requestFocus();
    }

    private void chooseLanguage() {
        if (busy) return;
        CinemaAutoSubtitles.chooseLanguage(this, language, true, selected -> {
            language = selected == null ? "" : UnifiedSubtitleConfig.canonicalLangName(selected);
            updateSettingsCardSubtitle(languageCard, language.isEmpty()
                    ? l("كل اللغات", "All languages")
                    : UnifiedSubtitleConfig.languageDisplay(language, arabic(this)));
            runSearch();
        });
    }

    private void runSearch() {
        if (busy) return;
        String inputTitle = query.getText().toString().trim();
        if (inputTitle.isEmpty() && imdb.isEmpty()) return;

        busy = true;
        results.removeAllViews();
        int token = ++requestGeneration;
        boolean rtl = arabic(this);
        Set<String> targetSources = UnifiedSubtitleConfig.selectedSearchSources(this);
        Set<String> runnable = UnifiedSubtitleConfig.runnableSources(this, targetSources);
        status.setText(l("جارٍ البحث في ", "Searching ") + runnable.size()
                + l(" مصدر… (", " source(s)… (") + UnifiedSubtitleConfig.searchSelectionSummary(this, rtl) + ")");

        String videoFileName = !videoFilenameHint.isBlank() ? videoFilenameHint
                : (video != null && video.getFileNameWithExtension() != null ? video.getFileNameWithExtension() : inputTitle);
        UnifiedSubtitleEngine.VideoParams videoParams = new UnifiedSubtitleEngine.VideoParams(
                videoFileName, videoSizeHint, videoHashHint, addonItemIdHint);
        boolean manualEdited = !inputTitle.equalsIgnoreCase(resolvedEnglishTitle);
        boolean explicitlyPickedOriginal = activeChipKey.startsWith("orig:");
        String chosenLang = language;

        io.execute(() -> {
            try {
                UnifiedSubtitleEngine.ResolvedQuery rq;
                if (!explicitlyPickedOriginal && (manualEdited || UnifiedSubtitleEngine.containsNonLatin(inputTitle))) {
                    rq = UnifiedSubtitleEngine.resolveQuery(getApplicationContext(), inputTitle,
                            manualEdited ? null : intentEnglishHint,
                            manualEdited ? "" : imdb, manualEdited ? -1 : year, isSeries, season, episode);
                    if (!rq.englishTitle.isBlank()) resolvedEnglishTitle = rq.englishTitle;
                    if (!rq.imdbId.isBlank()) imdb = rq.imdbId;
                    if (!rq.candidates.isEmpty()) titleCandidates = rq.candidates;
                } else {
                    rq = new UnifiedSubtitleEngine.ResolvedQuery(rawDisplayTitle, inputTitle, imdb, year,
                            isSeries, season, episode, false, titleCandidates);
                }

                List<String> langs = new ArrayList<>();
                if (chosenLang != null && !chosenLang.isBlank()) langs.add(chosenLang);
                else langs.addAll(UnifiedSubtitleConfig.languagePriorities(getApplicationContext()));

                List<UnifiedSubtitleEngine.Candidate> found = UnifiedSubtitleEngine.search(
                        getApplicationContext(), rq, langs, targetSources, videoParams);

                boolean usedOtherLangFallback = false;
                if (found.isEmpty() && "arabic".equals(chosenLang)) {
                    // Automatically look for English / Persian / other available subtitles that can be translated to Arabic!
                    List<UnifiedSubtitleEngine.Candidate> alt = UnifiedSubtitleEngine.search(
                            getApplicationContext(), rq, List.of("english", "persian", ""), targetSources, videoParams);
                    if (!alt.isEmpty()) {
                        found = alt;
                        usedOtherLangFallback = true;
                    }
                }

                final List<UnifiedSubtitleEngine.Candidate> finalFound = found;
                final boolean fallbackShown = usedOtherLangFallback;

                handler.post(() -> {
                    if (isDestroyed() || isFinishing() || token != requestGeneration) return;
                    busy = false;
                    if (!explicitlyPickedOriginal && rq.resolvedFromNonEnglish
                            && !rq.englishTitle.equals(query.getText().toString().trim())) {
                        query.setText(rq.englishTitle);
                        query.setSelection(query.getText().length());
                    }
                    renderTitleCandidateChips();
                    updateResolvedBanner(rq);
                    results.removeAllViews();
                    if (finalFound.isEmpty()) {
                        status.setText(l("لم يتم العثور على ترجمات مطابقة في المصادر المحددة. جرّب تبديل الاسم المقترح أعلاه أو توسيع المصادر.",
                                "No matching subtitles found. Try switching title candidates above or enabling more sources."));
                        return;
                    }
                    status.setText(fallbackShown
                            ? getString(R.string.cinema_sub_fallback_other_langs)
                            : l("عُثر على " + finalFound.size() + " ترجمة مرتّبة حسب التوافق:",
                                    "Found " + finalFound.size() + " subtitles ranked by match:"));
                    for (UnifiedSubtitleEngine.Candidate cand : finalFound) {
                        String langLabel = UnifiedSubtitleConfig.languageDisplay(cand.languageCanon, rtl);
                        String titleLine = "[" + cand.sourceBadge + "] · " + langLabel
                                + " (" + cand.format + ") · " + confidence(cand.confidence);
                        String subLine = cand.releaseName;
                        settingsCard(this, results, R.drawable.cinema_subtitles,
                                titleLine, subLine, null, null, () -> confirmDownload(cand));
                    }
                    if (results.getChildCount() > 0) results.getChildAt(0).requestFocus();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (isDestroyed() || token != requestGeneration) return;
                    busy = false;
                    status.setText(l("تعذّر البحث. تحقق من الاتصال بالإنترنت.", "Search failed. Check your network connection."));
                });
            }
        });
    }

    private String confidence(SubtitleMatch.Confidence c) {
        if (c == null) return l("توافق جيد", "Good match");
        return switch (c) {
            case EXCELLENT -> l("توافق ممتاز ★", "Excellent ★");
            case HIGH -> l("توافق مرتفع", "High match");
            case MEDIUM -> l("توافق متوسط", "Medium");
            case UNCERTAIN -> l("تطابق عام", "General");
        };
    }

    private void confirmDownload(UnifiedSubtitleEngine.Candidate cand) {
        String langLabel = UnifiedSubtitleConfig.languageDisplay(cand.languageCanon, arabic(this));
        boolean canTranslateToArabic = !"arabic".equalsIgnoreCase(cand.languageCanon);
        var builder = dialog(this)
                .setTitle("[" + cand.sourceBadge + "] · " + langLabel)
                .setMessage(cand.releaseName + "\n" + confidence(cand.confidence) + " · " + cand.format)
                .setNegativeButton(R.string.addon_back, null);
        if (canTranslateToArabic) {
            builder.setPositiveButton(R.string.cinema_sub_translate_to_ar, (d, w) -> downloadCandidate(cand, true))
                    .setNeutralButton(R.string.cinema_sub_apply_original_lang, (d, w) -> downloadCandidate(cand, false));
        } else {
            builder.setPositiveButton(l("تحميل وتفعيل", "Download & Apply"), (d, w) -> downloadCandidate(cand, false));
        }
        builder.show();
    }

    private void downloadCandidate(UnifiedSubtitleEngine.Candidate cand, boolean translateToArabic) {
        if (busy) return;
        busy = true;
        status.setText(l("جارٍ تنزيل الترجمة من ", "Downloading subtitle from ") + cand.sourceBadge + "…");
        io.execute(() -> {
            try {
                List<UnifiedSubtitleEngine.SubtitleFile> files = UnifiedSubtitleEngine.download(
                        getApplicationContext(), cand, season, episode);
                handler.post(() -> {
                    busy = false;
                    if (isDestroyed() || isFinishing()) return;
                    if (files.size() == 1) {
                        saveDownloadedFile(files.get(0), cand, translateToArabic);
                    } else {
                        String[] names = new String[files.size()];
                        for (int i = 0; i < files.size(); i++) names[i] = files.get(i).name;
                        dialog(this)
                                .setTitle(l("اختر ملف الحلقة أو الفيلم", "Choose the subtitle file"))
                                .setItems(names, (d, which) -> saveDownloadedFile(files.get(which), cand, translateToArabic))
                                .setNegativeButton(R.string.addon_back, null)
                                .show();
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    busy = false;
                    if (!isDestroyed()) {
                        status.setText(l("تعذّر تنزيل هذه الترجمة. جرّب نتيجة أخرى.",
                                "Could not download this subtitle. Try another result."));
                        Toast.makeText(this, l("فشل تنزيل الترجمة", "Subtitle download failed"), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void saveDownloadedFile(UnifiedSubtitleEngine.SubtitleFile file,
                                    UnifiedSubtitleEngine.Candidate cand,
                                    boolean translateToArabic) {
        Uri targetVideo = video != null ? video.getFileUri() : getIntent().getData();
        if (targetVideo == null) {
            Toast.makeText(this, l("لا يوجد مسار فيديو لحفظ الترجمة", "No video URI to bind subtitle"), Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        status.setText(translateToArabic
                ? getString(R.string.cinema_sub_translating_ar, 0)
                : l("جارٍ حفظ الترجمة وتطبيقها…", "Saving and applying subtitle…"));
        io.execute(() -> {
            try {
                byte[] payload = file.utf8Bytes;
                String saveName = file.name;
                String saveLang = cand.languageCanon;
                String saveSource = cand.sourceBadge;
                if (translateToArabic) {
                    byte[] translated = SubtitleArabicTranslator.translateToArabicSrt(
                            payload, file.extension,
                            pct -> handler.post(() -> {
                                if (!isDestroyed() && !isFinishing()) {
                                    status.setText(getString(R.string.cinema_sub_translating_ar, pct));
                                }
                            }),
                            () -> !isDestroyed() && !isFinishing());
                    if (translated == null || translated.length == 0) {
                        handler.post(() -> {
                            busy = false;
                            if (!isDestroyed()) {
                                status.setText(getString(R.string.cinema_sub_translated_ar_fail));
                                Toast.makeText(this, R.string.cinema_sub_translated_ar_fail, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    payload = translated;
                    saveName = file.name.replaceFirst("(?i)\\.(srt|vtt|ass|ssa)$", "") + ".ar.srt";
                    saveLang = "arabic";
                    saveSource = cand.sourceBadge + " → العربية";
                }
                try (SubtitleStorage storage = new SubtitleStorage(this)) {
                    long subId = 0L;
                    try { subId = Long.parseLong(cand.externalId); } catch (Exception ignored) {}
                    SubtitleStorage.Entry entry = new SubtitleStorage.Entry(saveName, payload);
                    SubtitleStorage.Saved saved = storage.save(targetVideo, entry, subId,
                            saveLang, saveSource, () -> !isDestroyed());
                    if (subId > 0L) CinemaAutoSubtitles.remember(this, targetVideo, subId);
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putString(SubtitleStorage.preferenceKey(targetVideo) + "_subsource_release", cand.releaseName)
                            .apply();
                    handler.post(() -> {
                        busy = false;
                        if (isDestroyed() || isFinishing()) return;
                        Toast.makeText(this, translateToArabic
                                ? getString(R.string.cinema_sub_translated_ar_ok)
                                : l("تم تحميل وتفعيل الترجمة من ", "Subtitle applied from ") + cand.sourceBadge + " ✓",
                                Toast.LENGTH_SHORT).show();
                        setResult(Activity.RESULT_OK, new Intent().setData(saved.uri()));
                        finish();
                    });
                }
            } catch (Exception e) {
                handler.post(() -> {
                    busy = false;
                    if (!isDestroyed()) status.setText(l("فشل حفظ ملف الترجمة.", "Failed to save subtitle file."));
                });
            }
        });
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        return (t.isEmpty() || "null".equalsIgnoreCase(t)) ? "" : t;
    }

    private static String stripEpisodeSuffix(String title) {
        return title.replaceAll("(?i)[\\s·-]+S\\d{1,2}E\\d{1,3}.*$", "").trim();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
