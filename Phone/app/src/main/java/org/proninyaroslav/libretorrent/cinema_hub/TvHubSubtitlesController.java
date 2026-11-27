package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.meedx.providers.ApiKeyStore;
import com.meedx.providers.SubtitleMatch;

final class TvHubSubtitlesController {
    final TvHubActivity host;

    String subtitleTitle = "";
    long liveSubSyncDelayMs = 0L;
    final java.util.List<StreamSubtitleItem> activeStreamSubtitles = new java.util.ArrayList<>();
    final java.util.List<TvSubtitleTrackItem> activeTvSubtitleTracks = new java.util.ArrayList<>();
    String selectedStreamPrimaryUrl = "";
    String selectedStreamSecondaryUrl = "";
    int selectedEmbeddedSecondaryIndex = -1;
    boolean subtitlesTurnedOff = false;
    LinearLayout activeStreamSubCard;
    String lastTvSubTracksSignature = "";

    record StreamSubtitleItem(
            String label, String url, String memPath, String mime, String language, boolean isDual,
            String referer, String origin, String userAgent, String cookie, String authorization) {}

    record TvSubtitleTrackItem(int index, String label, String language, boolean selected) {}

    interface SubtitleTask { void run() throws Exception; }

    record ParsedSubtitleMeta(String headline, java.util.List<String> tags, String rawLeaf) {}

    static final java.util.regex.Pattern EPISODE_TAG =
            java.util.regex.Pattern.compile("(?i)S(\\d{1,2})[ ._-]*(E\\d{1,3}(?:[ ._-]*E\\d{1,3})*)");
    static final java.util.regex.Pattern EPISODE_NUMBER = java.util.regex.Pattern.compile("(?i)E(\\d{1,3})");
    static final java.util.regex.Pattern RELEASE_TAG = java.util.regex.Pattern.compile(
            "(?i)(?<![a-z0-9])(S\\d{1,2}(?:E\\d{1,3})+|(?:2160|1080|720|480)p|WEB[- .]?DL|WEBRip|Blu[- .]?Ray|HDTV|WEB|HEVC|AVC|[HX][. ]?26[45]|AV1)(?![a-z0-9])");

    TvHubSubtitlesController(TvHubActivity host) {
        this.host = host;
    }

    void onSubtitlePicked(android.net.Uri uri) {
        if (uri == null) return;
        subtitleTask(() -> {
            try (var input = host.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException();
                byte[] bytes = readLimited(input, 524288);
                sendSubtitle(bytes, host.videoTitle(uri));
            }
        });
    }

    static boolean dualEnabled(Context context) {
        return context.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE)
                .getBoolean("dual_subs_enabled", false);
    }

    static void setDualEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE)
                .edit().putBoolean("dual_subs_enabled", enabled).apply();
    }

    private void toggleDual() {
        boolean enable = !dualEnabled(host);
        setDualEnabled(host, enable);
        com.meedx.providers.UnifiedSubtitleConfig.setDualSubtitlesEnabled(host, enable);
        if (host.connection != null) host.connection.sendSubtitleConfigToTv();
        if (enable) {
            if (deduplicateStreamSubtitles(activeStreamSubtitles).size() >= 2) {
                enableAutoDualStreamSubtitles();
            } else {
                for (var track : activeTvSubtitleTracks) {
                    if (!track.selected() && !selectedStreamPrimaryUrl.isBlank()) {
                        selectTvEmbeddedSubtitle(track.index(), true);
                        break;
                    }
                }
            }
        } else {
            selectedStreamSecondaryUrl = "";
            selectedEmbeddedSecondaryIndex = -1;
            sendSubtitleStyle("sub_clear_secondary", 0);
        }
        refreshActiveStreamSubCard();
    }

    void resetForWebCast(String webSubtitle, String webSubtitleMemPath,
                         String webSecondarySubtitle, String webSecondarySubtitleMemPath,
                         String catRaw) {
        liveSubSyncDelayMs = 0L;
        subtitlesTurnedOff = false;
        selectedStreamPrimaryUrl = canonicalSubSelectionKey(TvHubActivity.firstNonBlank(webSubtitle, webSubtitleMemPath));
        selectedStreamSecondaryUrl = canonicalSubSelectionKey(TvHubActivity.firstNonBlank(webSecondarySubtitle, webSecondarySubtitleMemPath));
        selectedEmbeddedSecondaryIndex = -1;
        activeStreamSubtitles.clear();
        if (catRaw != null && !catRaw.isBlank()) {
            try {
                var parsedList = new java.util.ArrayList<StreamSubtitleItem>();
                var arr = new org.json.JSONArray(catRaw);
                for (int i = 0; i < arr.length(); i++) {
                    var o = arr.optJSONObject(i);
                    if (o == null) continue;
                    parsedList.add(new StreamSubtitleItem(
                            o.optString("label", "Subtitle " + (i + 1)),
                            o.optString("url", ""),
                            o.optString("memPath", ""),
                            o.optString("mime", ""),
                            o.optString("language", ""),
                            o.optBoolean("isDual", false),
                            o.optString("referer", ""),
                            o.optString("origin", ""),
                            o.optString("userAgent", ""),
                            o.optString("cookie", ""),
                            o.optString("authorization", "")));
                }
                activeStreamSubtitles.addAll(deduplicateStreamSubtitles(parsedList));
            } catch (Exception ignored) {}
        }
    }

    void updateTvSubtitleTracks(org.json.JSONArray subArr) {
        activeTvSubtitleTracks.clear();
        var sigSb = new StringBuilder();
        if (subArr != null) {
            for (int si = 0; si < subArr.length(); si++) {
                var to = subArr.optJSONObject(si);
                if (to == null) continue;
                int idx = to.optInt("index", si);
                String lbl = to.optString("label", "Track " + (si + 1));
                String lng = to.optString("language", "");
                boolean sel = to.optBoolean("selected", false);
                if (sel) subtitlesTurnedOff = false;
                activeTvSubtitleTracks.add(new TvSubtitleTrackItem(idx, lbl, lng, sel));
                sigSb.append(idx).append(':').append(lbl).append(':').append(lng).append(':').append(sel).append('|');
            }
        }
        String sigNow = sigSb.toString();
        if (!sigNow.equals(lastTvSubTracksSignature)) {
            lastTvSubTracksSignature = sigNow;
            refreshActiveStreamSubCard();
        }
    }

    void showSubtitleSheet() {
        var panel = host.column();
        panel.setPadding(host.dp(16), 0, host.dp(16), host.dp(16));
        buildSubtitles(panel);
        showPanel(host.getString(org.proninyaroslav.libretorrent.R.string.cinema_subtitles), panel);
    }

    void showPanel(String title, LinearLayout panel) {
        if (panel.getParent() != null) ((android.view.ViewGroup) panel.getParent()).removeView(panel);
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(host);
        var scroll = subtitleSheetScroll(dialog);
        var body = host.column();
        body.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        body.addView(host.label(title, 21));
        body.addView(panel);
        scroll.addView(body);
        dialog.setOnDismissListener(d -> {
            if (activeStreamSubCard != null && activeStreamSubCard.getWindowToken() == null) {
                activeStreamSubCard = null;
            }
        });
        dialog.setContentView(scroll);
        expandSubtitleSheet(dialog);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -2);
    }

    private androidx.core.widget.NestedScrollView subtitleSheetScroll(
            com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        return new androidx.core.widget.NestedScrollView(host) {
            private boolean startedAtTop;

            {
                setNestedScrollingEnabled(true);
                setFillViewport(true);
            }

            @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
                super.onScrollChanged(x, y, oldX, oldY);
                if (y > 0) dialog.getBehavior().setDraggable(false);
            }

            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    startedAtTop = getScrollY() == 0;
                }
                dialog.getBehavior().setDraggable(startedAtTop && getScrollY() == 0);
                boolean handled = super.dispatchTouchEvent(event);
                if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                    dialog.getBehavior().setDraggable(getScrollY() == 0);
                }
                return handled;
            }
        };
    }

    private static void expandSubtitleSheet(com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        var behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    static String cleanTitleForSubtitleSearch(String raw) {
        if (raw == null) return "";
        String t = raw.trim()
                .replaceFirst("(?i)\\.(mp4|mkv|avi|webm|mov|ts)$", "")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("\\([^)]*\\)", " ")
                .replaceFirst("(?i)\\s*[-–—|]+\\s*(?:YouTube|Official\\s+Trailer.*|Teaser.*|Trailer.*|Full\\s+Movie.*|Clip.*)$", "")
                .replaceAll("(?i)\\b(?:2160p|1440p|1080p|720p|480p|360p|4K|8K|WEB[- .]?DL|WEBRip|Blu[- .]?Ray|BDRip|HDRip|HDTV|DVDRip|HEVC|AVC|H\\.?26[45]|X\\.?26[45]|AV1|10bit|AAC|AC3|DTS|DDP?5\\.1|HDR|SDR|Extended|Unrated|Remastered)\\b.*", "")
                .replace('.', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return t;
    }

    void buildSubtitles(LinearLayout page) {
        // 1. PROTECTED TOP CARD: Active & Stream Subtitles (Cinemana/Web Video Sniffer, Magnet, HLS, YouTube, TV embedded tracks + Cast Subtitle Settings)
        var streamCard = host.card(page, host.l("ترجمات البث المتاحة", "Active & Stream Subtitles"));
        activeStreamSubCard = streamCard;
        renderActiveStreamSubtitlesCard(streamCard);

        // 2. UNIFIED SMART MULTI-SOURCE SUBTITLE SEARCH (directly beneath Active & Stream Subtitles)
        var source = host.card(page, host.l("البحث الذكي عن الترجمة", "Smart Multi-Source Subtitle Search"));
        var preferences = host.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE);
        boolean ar = isArabicUi();

        String rawCandidate = TvHubActivity.cleanCastTitle(!subtitleTitle.isBlank() ? subtitleTitle
                : (host.activeWatchTitle != null && !host.activeWatchTitle.isBlank() ? host.activeWatchTitle
                : (host.browserCastTitle != null ? host.browserCastTitle : "")));
        var identity = java.util.regex.Pattern.compile("(?i)S(\\d{1,2})E(\\d{1,3})").matcher(rawCandidate);
        boolean episode = identity.find();
        String baseTitle = episode ? rawCandidate.substring(0, identity.start()) : rawCandidate;
        String cleanTitle = cleanTitleForSubtitleSearch(baseTitle);
        String videoRelease = rawCandidate;
        int season = episode ? Integer.parseInt(identity.group(1)) : -1;
        int episodeNumber = episode ? Integer.parseInt(identity.group(2)) : -1;

        var query = new EditText(host);
        query.setHint(host.l("اسم الفيلم أو المسلسل (بالعربية أو الإنجليزية)", "Movie or series title (Arabic or English)"));
        query.setSingleLine(true);
        query.setTextColor(0xfffafafa);
        query.setHintTextColor(0xff8e8e93);
        if (!cleanTitle.isEmpty()) query.setText(cleanTitle);
        source.addView(query);

        var resolvedHint = host.label("", 11);
        resolvedHint.setTextColor(TvHubActivity.GOLD);
        resolvedHint.setVisibility(View.GONE);
        source.addView(resolvedHint);

        // If title contains Arabic/non-Latin characters, resolve English title proactively in background!
        if (!cleanTitle.isEmpty() && com.meedx.providers.UnifiedSubtitleEngine.containsNonLatin(cleanTitle)) {
            resolvedHint.setText(host.l("جارٍ جلب الاسم الإنجليزي الأصلي تلقائياً…", "Resolving original English title…"));
            resolvedHint.setVisibility(View.VISIBLE);
            final String origInput = cleanTitle;
            subtitleTask(() -> {
                var rq = com.meedx.providers.UnifiedSubtitleEngine.resolveQuery(
                        host.getApplicationContext(), origInput, "", -1, episode, season, episodeNumber);
                host.runOnUiThread(() -> {
                    if (host.isDestroyed()) return;
                    if (rq.resolvedFromNonEnglish && !rq.englishTitle.isBlank()) {
                        if (query.getText().toString().trim().equals(origInput)) {
                            query.setText(rq.englishTitle);
                        }
                        String metaLine = host.l("تم جلب الاسم الإنجليزي: ", "Resolved English: ") + rq.englishTitle
                                + (rq.year > 0 ? " (" + rq.year + ")" : "")
                                + (!rq.imdbId.isEmpty() ? " · " + rq.imdbId : "");
                        resolvedHint.setText(metaLine);
                        resolvedHint.setVisibility(View.VISIBLE);
                    } else {
                        resolvedHint.setVisibility(View.GONE);
                    }
                });
            });
        }

        // Source Selection button (All Enabled / Single Source / Multi-Select — synced with TV!)
        source.addView(host.label(host.l("مصادر البحث (مصدر واحد أو عدة مصادر)", "Search sources (single or multi-select)"), 13));
        final MaterialButton[] srcPickerBtn = new MaterialButton[1];
        srcPickerBtn[0] = host.button(
                com.meedx.providers.UnifiedSubtitleConfig.searchSelectionSummary(host, ar),
                () -> showPhoneSourceMultiSelectDialog(() -> {
                    if (srcPickerBtn[0] != null) {
                        srcPickerBtn[0].setText(com.meedx.providers.UnifiedSubtitleConfig.searchSelectionSummary(host, ar));
                    }
                }), false);
        source.addView(srcPickerBtn[0], new LinearLayout.LayoutParams(-1, -2));

        var language = new android.widget.Spinner(host);
        language.setContentDescription(language.getContext().getString(org.proninyaroslav.libretorrent.R.string.cinema_subtitle_language_accessibility));
        var languages = new android.widget.ArrayAdapter<>(host, android.R.layout.simple_spinner_item,
                new String[]{"العربية", "English", "العربية + English", host.l("حسب أولوية اللغات", "By Language Priority")});
        languages.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(languages);
        language.setSelection(Math.max(0, Math.min(3, preferences.getInt("language", 0))));
        source.addView(host.label(host.l("لغة الترجمة", "Subtitle language"), 13));
        source.addView(language, new LinearLayout.LayoutParams(-1, host.dp(46)));

        var actions = host.row(source);
        host.add(actions, host.l("بحث ذكي", "Smart Search"), () -> {
            int choice = language.getSelectedItemPosition();
            preferences.edit().putInt("language", choice).apply();
            String q = query.getText().toString().trim();
            runUnifiedPhoneSearch(q, season, episodeNumber, videoRelease, choice, query, resolvedHint);
        });
        host.add(actions, host.l("إعدادات ومصادر", "Sources & Settings"), this::showUnifiedSubtitleSettingsSheet);
        source.addView(host.button(host.l("اختيار ملف ترجمة من الجهاز", "Choose subtitle file"), host::launchSubtitlePicker, false));

        // 3. Unified Appearance, Outline, Opacity & Sync Card (100% synced bidirectionally with TV & Drive)
        attachPhoneSubtitleAppearanceCard(page, true);
    }

    /** Renders the organized Subtitle Appearance & Sync card on Phone matching TV's UnifiedSubtitleConfig. */
    private void attachPhoneSubtitleAppearanceCard(LinearLayout parent, boolean includeLiveTimingAndVisibility) {
        boolean ar = isArabicUi();
        var style = host.card(parent, includeLiveTimingAndVisibility
                ? host.l("مظهر وتزامن الترجمة", "Subtitle Appearance & Sync")
                : host.l("مظهر وموضع وإطار الترجمة", "Subtitle Appearance, Position & Outline"));

        var summaryLbl = host.label("", 12);
        summaryLbl.setTextColor(TvHubActivity.GOLD);
        style.addView(summaryLbl);

        if (includeLiveTimingAndVisibility) {
            styleControl(host.row(style), host.l("إظهار / إخفاء على الشاشة", "Show / Hide on TV"), "visible", 0);
        }

        final Runnable[] refreshAppearanceUi = {null};

        // 1. Compact Color Picker Button (9 curated colors) + Font Size + Vertical Position
        var colorBtn = host.button("", () -> {
            int curCol = com.meedx.providers.UnifiedSubtitleConfig.textColor(host);
            int[] cols = com.meedx.providers.UnifiedSubtitleConfig.TEXT_COLORS;
            String[] names = new String[cols.length];
            int checkedIdx = 0;
            for (int i = 0; i < cols.length; i++) {
                names[i] = com.meedx.providers.UnifiedSubtitleConfig.textColorLabel(cols[i], ar);
                if (com.meedx.providers.UnifiedSubtitleConfig.isSameSubtitleColor(curCol, cols[i])) checkedIdx = i;
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                    .setTitle(host.l("اختر لون نص الترجمة", "Choose Subtitle Text Color"))
                    .setSingleChoiceItems(names, checkedIdx, (d, which) -> {
                        d.dismiss();
                        sendSubtitleStyle("color", cols[which]);
                        if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }, false);
        var colorLp = new LinearLayout.LayoutParams(-1, -2);
        colorLp.topMargin = host.dp(6);
        style.addView(colorBtn, colorLp);

        var sizePosRow = host.row(style);
        addRefreshingStyleBtn(sizePosRow, "A−", "size", -2, refreshAppearanceUi);
        addRefreshingStyleBtn(sizePosRow, "A+", "size", 2, refreshAppearanceUi);
        addRefreshingStyleBtn(sizePosRow, "↑", "position", 5, refreshAppearanceUi);
        addRefreshingStyleBtn(sizePosRow, "↓", "position", -5, refreshAppearanceUi);

        // 2. Dark Background Toggle + 0%..100% Opacity (10% steps)
        var bgRow = host.row(style);
        var bgToggleBtn = host.button("", () -> {
            boolean curBg = com.meedx.providers.UnifiedSubtitleConfig.opaqueBg(host);
            int curOp = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(host);
            if (!curBg && curOp == 0) {
                sendSubtitleStyle("bg_opacity", 70);
            } else {
                sendSubtitleStyle("background", 0);
            }
            if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
        }, false);
        bgToggleBtn.setSingleLine(true);
        bgToggleBtn.setTextSize(12);
        bgToggleBtn.setPadding(0, 0, 0, 0);
        bgRow.addView(bgToggleBtn, new LinearLayout.LayoutParams(0, host.dp(46), 1.35f));

        var opMinusBtn = host.button("−10%", () -> {
            int curOp = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(host);
            int nextOp = com.meedx.providers.UnifiedSubtitleConfig.stepBgOpacity(curOp, -10);
            sendSubtitleStyle("bg_opacity", nextOp);
            if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
        }, false);
        opMinusBtn.setSingleLine(true);
        opMinusBtn.setTextSize(12);
        opMinusBtn.setPadding(0, 0, 0, 0);
        bgRow.addView(opMinusBtn, new LinearLayout.LayoutParams(0, host.dp(46), 0.7f));

        var opValBtn = host.button("", () -> {
            int curOp = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(host);
            String[] steps = new String[11];
            int sel = curOp / 10;
            for (int i = 0; i <= 10; i++) steps[i] = (i * 10) + "%";
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                    .setTitle(host.l("نسبة شفافية الخلفية الداكنة", "Dark Background Opacity"))
                    .setSingleChoiceItems(steps, Math.max(0, Math.min(10, sel)), (d, which) -> {
                        d.dismiss();
                        sendSubtitleStyle("bg_opacity", which * 10);
                        if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }, false);
        opValBtn.setSingleLine(true);
        opValBtn.setTextSize(12);
        opValBtn.setPadding(0, 0, 0, 0);
        bgRow.addView(opValBtn, new LinearLayout.LayoutParams(0, host.dp(46), 0.95f));

        var opPlusBtn = host.button("+10%", () -> {
            int curOp = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(host);
            int nextOp = com.meedx.providers.UnifiedSubtitleConfig.stepBgOpacity(curOp, 10);
            if (nextOp == 0) nextOp = 10;
            sendSubtitleStyle("bg_opacity", nextOp);
            if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
        }, false);
        opPlusBtn.setSingleLine(true);
        opPlusBtn.setTextSize(12);
        opPlusBtn.setPadding(0, 0, 0, 0);
        bgRow.addView(opPlusBtn, new LinearLayout.LayoutParams(0, host.dp(46), 0.7f));

        // 3. Unified Outline / Stroke Row (Size + Color together)
        var outlineRow = host.row(style);
        var outlineSizeBtn = host.button("", () -> {
            int curLvl = com.meedx.providers.UnifiedSubtitleConfig.outlineLevel(host);
            String[] lvlNames = new String[4];
            for (int i = 0; i < 4; i++) {
                lvlNames[i] = com.meedx.providers.UnifiedSubtitleConfig.outlineLevelLabel(i, ar);
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                    .setTitle(host.l("حجم إطار الترجمة", "Subtitle Outline Thickness"))
                    .setSingleChoiceItems(lvlNames, curLvl, (d, which) -> {
                        d.dismiss();
                        sendSubtitleStyle("outline", which);
                        if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }, false);
        outlineSizeBtn.setSingleLine(true);
        outlineSizeBtn.setTextSize(12);
        outlineSizeBtn.setPadding(0, 0, 0, 0);
        outlineRow.addView(outlineSizeBtn, new LinearLayout.LayoutParams(0, host.dp(48), 1f));

        var outlineColorBtn = host.button("", () -> {
            int curOutCol = com.meedx.providers.UnifiedSubtitleConfig.outlineColor(host);
            int[] outCols = com.meedx.providers.UnifiedSubtitleConfig.OUTLINE_COLORS;
            String[] outNames = new String[outCols.length];
            int checked = 0;
            for (int i = 0; i < outCols.length; i++) {
                outNames[i] = com.meedx.providers.UnifiedSubtitleConfig.outlineColorLabel(outCols[i], ar);
                if (com.meedx.providers.UnifiedSubtitleConfig.isSameOutlineColor(curOutCol, outCols[i])) checked = i;
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                    .setTitle(host.l("لون إطار الترجمة", "Subtitle Outline Color"))
                    .setSingleChoiceItems(outNames, checked, (d, which) -> {
                        d.dismiss();
                        sendSubtitleStyle("outline_color", outCols[which]);
                        if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }, false);
        outlineColorBtn.setSingleLine(true);
        outlineColorBtn.setTextSize(12);
        outlineColorBtn.setPadding(0, 0, 0, 0);
        outlineRow.addView(outlineColorBtn, new LinearLayout.LayoutParams(0, host.dp(48), 1f));

        // 4. Dual Subtitles Toggle + Dual Position
        var dualRow = host.row(style);
        var dualToggleBtn = host.button("", () -> {
            boolean next = !com.meedx.providers.UnifiedSubtitleConfig.dualSubtitlesEnabled(host);
            setDualEnabled(host, next);
            com.meedx.providers.UnifiedSubtitleConfig.setDualSubtitlesEnabled(host, next);
            if (host.connection != null) host.connection.sendSubtitleConfigToTv();
            if (!next) sendSubtitleStyle("sub_clear_secondary", 0);
            refreshActiveStreamSubCard();
            if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
        }, false);
        dualToggleBtn.setSingleLine(true);
        dualToggleBtn.setTextSize(12);
        dualToggleBtn.setPadding(0, 0, 0, 0);
        dualRow.addView(dualToggleBtn, new LinearLayout.LayoutParams(0, host.dp(46), 1f));

        var dualPosBtn = host.button("", () -> {
            int nextPos = (com.meedx.providers.UnifiedSubtitleConfig.dualSubPosition(host) + 1) % 3;
            com.meedx.providers.UnifiedSubtitleConfig.setDualSubPosition(host, nextPos);
            host.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE)
                    .edit().putInt("dual_sub_position", nextPos).apply();
            if (host.connection != null) host.connection.sendSubtitleConfigToTv();
            sendSubtitleStyle("dual_position", nextPos);
            if (refreshAppearanceUi[0] != null) refreshAppearanceUi[0].run();
        }, false);
        dualPosBtn.setSingleLine(true);
        dualPosBtn.setTextSize(12);
        dualPosBtn.setPadding(0, 0, 0, 0);
        dualRow.addView(dualPosBtn, new LinearLayout.LayoutParams(0, host.dp(46), 1f));

        // 5. Timing & Reset
        if (includeLiveTimingAndVisibility) {
            var sync = host.row(style);
            styleControl(sync, "−0.5s", "delay", -500);
            styleControl(sync, "−0.1s", "delay", -100);
            styleControl(sync, "+0.1s", "delay", 100);
            styleControl(sync, "+0.5s", "delay", 500);
        }
        var resetRow = host.row(style);
        if (includeLiveTimingAndVisibility) {
            addRefreshingStyleBtn(resetRow, host.l("تصفير التوقيت", "Reset Sync"), "delay_reset", 0, refreshAppearanceUi);
        }
        addRefreshingStyleBtn(resetRow, host.l("تصفير المظهر والتوقيت", "Reset All"), "reset", 0, refreshAppearanceUi);

        refreshAppearanceUi[0] = () -> {
            int col = com.meedx.providers.UnifiedSubtitleConfig.textColor(host);
            boolean bg = com.meedx.providers.UnifiedSubtitleConfig.opaqueBg(host);
            int bgOp = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(host);
            int outLvl = com.meedx.providers.UnifiedSubtitleConfig.outlineLevel(host);
            int outCol = com.meedx.providers.UnifiedSubtitleConfig.outlineColor(host);
            boolean dualOn = com.meedx.providers.UnifiedSubtitleConfig.dualSubtitlesEnabled(host);
            int dualPos = com.meedx.providers.UnifiedSubtitleConfig.dualSubPosition(host);

            summaryLbl.setText(com.meedx.providers.UnifiedSubtitleConfig.appearanceSummary(host, ar));

            var textDot = new GradientDrawable();
            textDot.setShape(GradientDrawable.OVAL);
            textDot.setColor(col);
            textDot.setStroke(host.dp(1), 0x88000000);
            textDot.setSize(host.dp(14), host.dp(14));
            colorBtn.setIcon(textDot);
            colorBtn.setIconTint(null);
            colorBtn.setText(host.l("لون النص: ", "Text Color: ")
                    + com.meedx.providers.UnifiedSubtitleConfig.textColorLabel(col, ar) + " ▾");

            bgToggleBtn.setText(bg ? host.l("خلفية داكنة ✓", "Dark BG ✓") : host.l("خلفية شفافة", "Trans BG"));
            styleSubtitleActionButton(bgToggleBtn, bg, false);

            opValBtn.setText((bg ? bgOp : 0) + "%");
            styleSubtitleActionButton(opValBtn, bg && bgOp > 0, false);

            outlineSizeBtn.setText(host.l("الإطار: ", "Outline: ")
                    + com.meedx.providers.UnifiedSubtitleConfig.outlineLevelLabel(outLvl, ar));
            styleSubtitleActionButton(outlineSizeBtn, outLvl > 0, false);

            var outDot = new GradientDrawable();
            outDot.setShape(GradientDrawable.OVAL);
            outDot.setColor(outCol);
            outDot.setStroke(host.dp(1), (outCol | 0xff000000) == android.graphics.Color.BLACK ? 0xbbffffff : 0x88000000);
            outDot.setSize(host.dp(13), host.dp(13));
            outlineColorBtn.setIcon(outDot);
            outlineColorBtn.setIconTint(null);
            outlineColorBtn.setText(host.l("لون الإطار: ", "Stroke: ")
                    + com.meedx.providers.UnifiedSubtitleConfig.outlineColorLabel(outCol, ar));

            dualToggleBtn.setText(dualOn ? host.l("المزدوجة: ✓ مفعّلة", "Dual: ✓ ON")
                    : host.l("المزدوجة: ✕ متوقفة", "Dual: ✕ OFF"));
            styleSubtitleActionButton(dualToggleBtn, dualOn, false);

            String posShort = switch (dualPos) {
                case 0 -> host.l("الموضع: أسفل معاً", "Pos: Both Bottom");
                case 2 -> host.l("الموضع: أساسية أعلى", "Pos: Primary Top");
                default -> host.l("الموضع: أساسية أسفل", "Pos: Split Default");
            };
            dualPosBtn.setText(posShort);
        };
        refreshAppearanceUi[0].run();
    }

    private void addRefreshingStyleBtn(LinearLayout row, String label, String action, int value, Runnable[] refreshRef) {
        var control = host.button(label, () -> {
            sendSubtitleStyle(action, value);
            if (refreshRef != null && refreshRef[0] != null) refreshRef[0].run();
        }, false);
        control.setSingleLine(true);
        control.setTextSize(13);
        control.setPadding(0, 0, 0, 0);
        row.addView(control, new LinearLayout.LayoutParams(0, host.dp(46), 1));
    }

    private boolean isArabicUi() {
        var loc = androidx.core.os.ConfigurationCompat.getLocales(host.getResources().getConfiguration()).get(0);
        return loc != null && "ar".equals(loc.getLanguage());
    }

    void showPhoneSourceMultiSelectDialog(Runnable onUpdated) {
        boolean ar = isArabicUi();
        var providers = com.meedx.providers.UnifiedSubtitleConfig.allProviders(host);
        boolean allMode = com.meedx.providers.UnifiedSubtitleConfig.MODE_ALL
                .equals(com.meedx.providers.UnifiedSubtitleConfig.searchSelectionMode(host));
        var selected = new java.util.LinkedHashSet<>(
                com.meedx.providers.UnifiedSubtitleConfig.selectedSearchSources(host));

        String[] labels = new String[providers.size() + 1];
        boolean[] checked = new boolean[providers.size() + 1];
        labels[0] = host.l("الكل (جميع المصادر المفعّلة معاً)", "All Enabled Sources Together");
        checked[0] = allMode;
        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            boolean ready = p.keyless || com.meedx.providers.UnifiedSubtitleConfig.hasKey(host, p);
            labels[i + 1] = p.name + (ready ? "" : host.l(" (يحتاج مفتاح)", " (API key needed)"));
            checked[i + 1] = !allMode && selected.contains(p.id);
        }

        var dlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                .setTitle(host.l("اختر مصدر واحد أو عدة مصادر للبحث", "Choose One or Multiple Sources"))
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    var lv = ((androidx.appcompat.app.AlertDialog) dialog).getListView();
                    if (which == 0 && isChecked) {
                        for (int i = 1; i < checked.length; i++) {
                            checked[i] = false;
                            if (lv != null) lv.setItemChecked(i, false);
                        }
                    } else if (which > 0 && isChecked) {
                        checked[0] = false;
                        if (lv != null) lv.setItemChecked(0, false);
                    }
                })
                .setPositiveButton(host.l("تطبيق ومزامنة", "Apply & Sync"), (d, w) -> {
                    if (checked[0]) {
                        com.meedx.providers.UnifiedSubtitleConfig.setSearchSelectionAll(host);
                    } else {
                        var picked = new java.util.LinkedHashSet<String>();
                        for (int i = 0; i < providers.size(); i++) {
                            if (checked[i + 1]) picked.add(providers.get(i).id);
                        }
                        if (picked.isEmpty()) com.meedx.providers.UnifiedSubtitleConfig.setSearchSelectionAll(host);
                        else com.meedx.providers.UnifiedSubtitleConfig.setSearchSelectionCustom(host, picked);
                    }
                    if (host.connection != null) host.connection.sendSubtitleConfigToTv();
                    if (onUpdated != null) onUpdated.run();
                })
                .setNegativeButton(android.R.string.cancel, null);
        dlg.show();
    }

    void runUnifiedPhoneSearch(String rawTitle, int season, int episode, String videoRelease,
                               int languageChoice, EditText queryView, TextView resolvedView) {
        if (rawTitle == null || rawTitle.isBlank()) {
            host.notifyStatus(host.l("أدخل اسم الفيلم أو المسلسل", "Enter a movie or series title"));
            return;
        }
        boolean ar = isArabicUi();
        host.notifyStatus(host.l("جارٍ البحث في المصادر المحددة…", "Searching selected subtitle sources…"));
        subtitleTask(() -> {
            var rq = com.meedx.providers.UnifiedSubtitleEngine.resolveQuery(
                    host.getApplicationContext(), rawTitle, "", -1, season > 0 || episode > 0, season, episode);
            var langs = switch (languageChoice) {
                case 1 -> java.util.List.of("english");
                case 2 -> java.util.List.of("arabic", "english");
                case 3 -> com.meedx.providers.UnifiedSubtitleConfig.languagePriorities(host);
                default -> java.util.List.of("arabic");
            };
            var selectedSources = com.meedx.providers.UnifiedSubtitleConfig.selectedSearchSources(host);
            var found = com.meedx.providers.UnifiedSubtitleEngine.search(
                    host.getApplicationContext(), rq, langs, selectedSources, videoRelease);

            host.runOnUiThread(() -> {
                if (host.isDestroyed()) return;
                if (rq.resolvedFromNonEnglish && !rq.englishTitle.isBlank()) {
                    if (queryView != null) queryView.setText(rq.englishTitle);
                    if (resolvedView != null) {
                        resolvedView.setText(host.l("تم جلب الاسم الإنجليزي: ", "Resolved English: ") + rq.englishTitle
                                + (rq.year > 0 ? " (" + rq.year + ")" : "")
                                + (!rq.imdbId.isEmpty() ? " · " + rq.imdbId : ""));
                        resolvedView.setVisibility(View.VISIBLE);
                    }
                }
                if (found.isEmpty()) {
                    host.notifyStatus(host.l("لم تُعثر ترجمات مطابقة في المصادر المختارة", "No matching subtitles in selected sources"));
                    return;
                }
                showUnifiedPhoneSearchResults(found, rq.season, rq.episode, ar);
            });
        });
    }

    private void showUnifiedPhoneSearchResults(
            java.util.List<com.meedx.providers.UnifiedSubtitleEngine.Candidate> items,
            int season, int episode, boolean ar) {
        var resultsPanel = host.column();
        for (var cand : items) {
            String langLbl = com.meedx.providers.UnifiedSubtitleConfig.languageDisplay(cand.languageCanon, ar);
            String heading = "[" + cand.sourceBadge + "] · " + langLbl + " (" + cand.format + ")";
            var card = host.card(resultsPanel, heading);
            var relTv = host.label(cand.releaseName, 13);
            relTv.setTextColor(0xffd4d4d8);
            relTv.setSingleLine(false);
            relTv.setMaxLines(3);
            card.addView(relTv);

            card.setFocusable(true);
            card.setClickable(true);
            card.setOnClickListener(v -> {
                host.notifyStatus(host.l("جارٍ تنزيل وإرسال الترجمة من ", "Downloading & sending from ") + cand.sourceBadge + "…");
                subtitleTask(() -> {
                    var files = com.meedx.providers.UnifiedSubtitleEngine.download(
                            host.getApplicationContext(), cand, season, episode);
                    host.runOnUiThread(() -> {
                        if (host.isDestroyed() || files.isEmpty()) return;
                        if (files.size() == 1) {
                            sendSubtitle(files.get(0).utf8Bytes, files.get(0).name);
                        } else {
                            var names = new java.util.ArrayList<String>();
                            var bytesList = new java.util.ArrayList<byte[]>();
                            for (var f : files) {
                                names.add(f.name);
                                bytesList.add(f.utf8Bytes);
                            }
                            showChooseSubtitleFileSheet(names, bytesList);
                        }
                    });
                });
            });
        }
        showPanel(host.l("نتائج الترجمة (", "Subtitle Results (") + items.size() + ")", resultsPanel);
    }

    /** Phone Unified Subtitle Settings sheet (100% synced bidirectionally with TV). */
    void showUnifiedSubtitleSettingsSheet() {
        boolean ar = isArabicUi();
        var panel = host.column();
        panel.setPadding(host.dp(12), 0, host.dp(12), host.dp(18));

        // 1. Transparent list of ALL built-in open-source, Stremio subtitle add-ons & optional-key subtitle providers
        var srcCard = host.card(panel, host.l("1. مصادر وإضافات الترجمة (مفتوحة ومدمجة)", "1. Built-in & Add-on Subtitle Sources"));
        for (var info : com.meedx.providers.UnifiedSubtitleConfig.allProviders(host)) {
            boolean enabled = com.meedx.providers.UnifiedSubtitleConfig.isSourceEnabled(host, info.id);
            boolean hasKey = com.meedx.providers.UnifiedSubtitleConfig.hasKey(host, info);
            String statusTxt = (enabled && hasKey) ? host.l("✓ مفعّل", "✓ ON")
                    : (!hasKey ? host.l("مفتاح API", "Need Key") : host.l("✕ متوقف", "✕ OFF"));
            var rowBtn = host.button(info.name + "  ·  " + statusTxt, () -> {
                var opts = new java.util.ArrayList<String>();
                var acts = new java.util.ArrayList<Runnable>();
                opts.add(enabled ? host.l("إيقاف المصدر", "Disable Source") : host.l("تفعيل المصدر", "Enable Source"));
                acts.add(() -> {
                    com.meedx.providers.UnifiedSubtitleConfig.setSourceEnabled(host, info.id, !enabled);
                    if (host.connection != null) host.connection.sendSubtitleConfigToTv();
                    host.notifyStatus(info.name + ": " + (!enabled ? host.l("مفعّل ✓", "Enabled ✓") : host.l("متوقف", "Disabled")));
                });
                opts.add(host.l("اختبار حي للمصدر الآن", "Test Live Connection Now"));
                acts.add(() -> {
                    host.notifyStatus(host.l("جارٍ اختبار ", "Testing ") + info.name + "…");
                    subtitleTask(() -> {
                        var res = com.meedx.providers.UnifiedSubtitleEngine.testProvider(host.getApplicationContext(), info.id);
                        host.runOnUiThread(() -> {
                            if (!host.isDestroyed()) {
                                host.notifyStatus(info.name + ": " + (ar ? res.arMessage : res.enMessage));
                            }
                        });
                    });
                });
                if (!info.keyless && info.apiKeyName != null && host.connection != null) {
                    opts.add(host.l("إضافة / تعديل مفتاح API", "Set / Edit API Key"));
                    acts.add(() -> host.connection.promptSingleApiKey(
                            info.name + " API Key",
                            host.l("ألصق مفتاح API هنا", "Paste API Key"),
                            info.apiKeyName,
                            info.keyUrl));
                }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                        .setTitle(info.name + "\n" + (ar ? info.arType : info.enType))
                        .setItems(opts.toArray(new String[0]), (d, which) -> {
                            if (which >= 0 && which < acts.size()) acts.get(which).run();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }, enabled && hasKey);
            var lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = host.dp(6);
            srcCard.addView(rowBtn, lp);
        }

        // 2. Language Priority & Dual Subtitles
        var langCard = host.card(panel, host.l("2. أولوية اللغات والترجمة المزدوجة", "2. Language Priority & Dual Subtitles"));
        var prioList = com.meedx.providers.UnifiedSubtitleConfig.languagePriorities(host);
        var prioSb = new StringBuilder();
        for (int i = 0; i < prioList.size(); i++) {
            if (i > 0) prioSb.append(" ← ");
            prioSb.append((i + 1)).append(". ")
                    .append(com.meedx.providers.UnifiedSubtitleConfig.languageDisplay(prioList.get(i), ar));
        }
        var prioLbl = host.label(prioSb.toString(), 13);
        prioLbl.setTextColor(TvHubActivity.GOLD);
        langCard.addView(prioLbl);
        langCard.addView(host.button(host.l("تغيير اللغة الأساسية", "Change Primary Language"), () -> {
            var langs = com.meedx.providers.UnifiedSubtitleConfig.SUPPORTED_LANGUAGES;
            String[] names = new String[langs.size()];
            for (int i = 0; i < langs.size(); i++) {
                names[i] = com.meedx.providers.UnifiedSubtitleConfig.languageDisplay(langs.get(i), ar);
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                    .setTitle(host.l("اللغة الأساسية للترجمة", "Primary Subtitle Language"))
                    .setItems(names, (d, w) -> {
                        com.meedx.providers.UnifiedSubtitleConfig.setPrimaryLanguage(host, langs.get(w));
                        if (host.connection != null) host.connection.sendSubtitleConfigToTv();
                        host.notifyStatus(host.l("تم تحديث أولوية اللغة ومزامنتها للشاشة ✓", "Language priority synced to TV ✓"));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }, false));

        // 3. Unified Subtitle Appearance, Outline & Opacity (synced with TV & Google Drive Backup)
        attachPhoneSubtitleAppearanceCard(panel, false);

        // 4. Sync Now
        var syncCard = host.card(panel, host.l("4. المزامنة الثنائية (الهاتف ↔ الشاشة)", "4. Bidirectional Sync (Phone ↔ TV)"));
        syncCard.addView(host.button(host.l("مزامنة إعدادات ومصادر الترجمة مع الشاشة الآن",
                "Sync Subtitle Settings & Sources with TV Now"), () -> {
            if (host.connection != null) {
                host.connection.sendSubtitleConfigToTv();
                host.connection.sendFullSyncStateToTv(true);
            }
        }, true));

        showPanel(host.l("إعدادات الترجمة الموحّدة", "Unified Subtitle Settings"), panel);
    }

    static String normalizeSubLanguage(String lang, String label) {
        String combined = ((lang == null ? "" : lang) + " " + (label == null ? "" : label))
                .toLowerCase(java.util.Locale.ROOT);
        if (combined.contains("عرب") || combined.matches(".*\\b(ar|ara|arabic)\\b.*")) return "ar";
        if (combined.matches(".*\\b(en|eng|english)\\b.*")) return "en";
        if (combined.matches(".*\\b(fr|fra|fre|french)\\b.*")) return "fr";
        if (combined.matches(".*\\b(es|spa|spanish)\\b.*")) return "es";
        if (combined.matches(".*\\b(tr|tur|turkish)\\b.*")) return "tr";
        return lang == null ? "" : lang.trim().toLowerCase(java.util.Locale.ROOT);
    }

    static String canonicalSubSelectionKey(String rawUrlOrMem) {
        if (rawUrlOrMem == null || rawUrlOrMem.isBlank()) return "";
        String t = rawUrlOrMem.trim();
        if (t.startsWith("/mem/") || t.startsWith("custom:")) return t;
        try {
            android.net.Uri u = android.net.Uri.parse(t);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            String subHost = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = u.getPath() == null ? "" : u.getPath();
            var builder = new android.net.Uri.Builder()
                    .scheme(scheme)
                    .encodedAuthority(u.getPort() > 0 ? subHost + ":" + u.getPort() : subHost)
                    .path(path);
            for (String q : u.getQueryParameterNames()) {
                String ql = q.toLowerCase(java.util.Locale.ROOT);
                if (ql.equals("token") || ql.equals("expires") || ql.equals("exp") || ql.equals("sig")
                        || ql.equals("signature") || ql.equals("t") || ql.equals("_") || ql.equals("rand")
                        || ql.equals("cb") || ql.equals("ts") || ql.equals("nonce") || ql.equals("v")
                        || ql.equals("wmsauthsign") || ql.equals("hdnts")) {
                    continue;
                }
                String val = u.getQueryParameter(q);
                if (val != null) builder.appendQueryParameter(q, val);
            }
            return builder.build().toString();
        } catch (Exception ignored) {
            return t;
        }
    }

    static String streamSubKey(StreamSubtitleItem item) {
        if (item == null) return "";
        if (item.url() != null && !item.url().isBlank()) return canonicalSubSelectionKey(item.url());
        if (item.memPath() != null && !item.memPath().isBlank()) return item.memPath().trim();
        return normalizeSubLanguage(item.language(), item.label()) + "|"
                + (item.label() == null ? "" : item.label().trim().toLowerCase(java.util.Locale.ROOT));
    }

    static boolean matchesSelectedSub(StreamSubtitleItem item, String selectedKey) {
        if (item == null || selectedKey == null || selectedKey.isBlank()) return false;
        String selCanon = canonicalSubSelectionKey(selectedKey);
        if (item.url() != null && !item.url().isBlank()) {
            if (selectedKey.equals(item.url()) || selCanon.equals(canonicalSubSelectionKey(item.url()))) return true;
        }
        if (item.memPath() != null && !item.memPath().isBlank()) {
            if (selectedKey.equals(item.memPath()) || selCanon.equals(item.memPath())) return true;
        }
        return false;
    }

    static java.util.List<StreamSubtitleItem> deduplicateStreamSubtitles(java.util.List<StreamSubtitleItem> source) {
        var byKey = new java.util.LinkedHashMap<String, StreamSubtitleItem>();
        for (var item : source) {
            if (item == null) continue;
            String urlKey = streamSubKey(item);
            String normLang = normalizeSubLanguage(item.language(), item.label());
            String normLabel = item.label() == null ? "" : item.label().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
            String semanticKey = !normLang.isEmpty() && !normLabel.isEmpty() ? (normLang + "|" + normLabel) : urlKey;

            String matchKey = byKey.containsKey(urlKey) ? urlKey : (byKey.containsKey(semanticKey) ? semanticKey : null);
            if (matchKey == null && !normLabel.isEmpty()) {
                for (var e : byKey.entrySet()) {
                    var existing = e.getValue();
                    String eLabel = existing.label() == null ? "" : existing.label().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
                    String eLang = normalizeSubLanguage(existing.language(), existing.label());
                    if (normLabel.equals(eLabel) && (normLang.isEmpty() || eLang.isEmpty() || normLang.equals(eLang))) {
                        matchKey = e.getKey();
                        break;
                    }
                }
            }
            if (matchKey != null) {
                var existing = byKey.get(matchKey);
                boolean keepNew = (existing.memPath().isBlank() && !item.memPath().isBlank())
                        || (existing.url().isBlank() && !item.url().isBlank());
                if (keepNew) byKey.put(matchKey, item);
            } else {
                byKey.put(!urlKey.isBlank() ? urlKey : semanticKey, item);
            }
        }
        return new java.util.ArrayList<>(byKey.values());
    }

    static java.util.List<TvSubtitleTrackItem> deduplicateTvTracks(
            java.util.List<TvSubtitleTrackItem> tvTracks,
            java.util.List<StreamSubtitleItem> streamSubs) {
        var uniqueTv = new java.util.LinkedHashMap<String, TvSubtitleTrackItem>();
        for (var track : tvTracks) {
            if (track == null) continue;
            String tLang = normalizeSubLanguage(track.language(), track.label());
            String tLabel = track.label() == null ? "" : track.label().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
            String k = (!tLang.isEmpty() ? tLang : ("idx" + track.index())) + "|" + tLabel;
            var prev = uniqueTv.get(k);
            if (prev == null || (!prev.selected() && track.selected())) {
                uniqueTv.put(k, track);
            }
        }
        var out = new java.util.ArrayList<TvSubtitleTrackItem>();
        for (var track : uniqueTv.values()) {
            String tLang = normalizeSubLanguage(track.language(), track.label());
            String tLabel = track.label() == null ? "" : track.label().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
            boolean genericLabel = tLabel.isEmpty()
                    || tLabel.matches("(?i)(?:track\\s*\\d+|unknown|und|subtitle(?:\\s*\\d+)?|ar|ara|arabic|en|eng|english|fr|es|tr|العربية)");
            boolean duplicateOfStream = false;
            if (!streamSubs.isEmpty()) {
                for (var s : streamSubs) {
                    String sLang = normalizeSubLanguage(s.language(), s.label());
                    String sLabel = s.label() == null ? "" : s.label().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
                    boolean sameLang = !tLang.isEmpty() && tLang.equals(sLang);
                    if ((sameLang && (genericLabel || tLabel.equals(sLabel)))
                            || (!tLabel.isEmpty() && tLabel.equals(sLabel))) {
                        duplicateOfStream = true;
                        break;
                    }
                }
            }
            if (!duplicateOfStream) out.add(track);
        }
        return out;
    }

    void refreshActiveStreamSubCard() {
        if (activeStreamSubCard != null && activeStreamSubCard.isAttachedToWindow()) {
            renderActiveStreamSubtitlesCard(activeStreamSubCard);
        }
    }

    private void styleSubtitleActionButton(MaterialButton btn, boolean activeGold, boolean activeCrimson) {
        if (activeGold) {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(TvHubActivity.GOLD));
            btn.setStrokeColor(android.content.res.ColorStateList.valueOf(TvHubActivity.GOLD));
            btn.setStrokeWidth(host.dp(1));
            btn.setTextColor(0xff141517);
            btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else if (activeCrimson) {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff5c1922));
            btn.setStrokeColor(android.content.res.ColorStateList.valueOf(0xfff87171));
            btn.setStrokeWidth(host.dp(1));
            btn.setTextColor(0xffffe4e6);
            btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff1f2027));
            btn.setStrokeColor(android.content.res.ColorStateList.valueOf(0xff3f414d));
            btn.setStrokeWidth(host.dp(1));
            btn.setTextColor(0xffe4e4e7);
            btn.setTypeface(android.graphics.Typeface.DEFAULT);
        }
    }

    void renderActiveStreamSubtitlesCard(LinearLayout streamCard) {
        if (streamCard == null) return;
        while (streamCard.getChildCount() > 1) {
            streamCard.removeViewAt(streamCard.getChildCount() - 1);
        }
        var dedupStream = deduplicateStreamSubtitles(activeStreamSubtitles);
        var dedupTv = deduplicateTvTracks(activeTvSubtitleTracks, dedupStream);

        boolean hasPrimary = selectedStreamPrimaryUrl != null && !selectedStreamPrimaryUrl.isBlank();
        boolean hasSecondary = selectedStreamSecondaryUrl != null && !selectedStreamSecondaryUrl.isBlank();
        boolean hasEmbeddedSelected = false;
        String activeEmbeddedLang = "";
        for (var t : dedupTv) {
            if (t.selected()) {
                hasEmbeddedSelected = true;
                activeEmbeddedLang = (t.language() != null && !t.language().isBlank()) ? t.language().trim() : t.label();
                break;
            }
        }
        boolean hasEmbeddedSecondary = selectedEmbeddedSecondaryIndex >= 0;
        boolean dualActive = !subtitlesTurnedOff && ((hasPrimary && hasSecondary)
                || ((hasPrimary || hasEmbeddedSelected) && (hasSecondary || hasEmbeddedSecondary)));
        boolean subsOff = subtitlesTurnedOff || (!hasPrimary && !hasSecondary && !hasEmbeddedSelected && !hasEmbeddedSecondary);

        if (dedupStream.isEmpty() && dedupTv.isEmpty()) {
            var emptyNote = host.label(host.l("لا توجد ترجمات مرفقة مع البث بعد. يمكنك البحث أدناه أو اختيار ملف ترجمة من جهازك.",
                    "No stream subtitles attached yet. Search below or choose a subtitle file."), 12);
            emptyNote.setTextColor(0xffa1a1aa);
            emptyNote.setPadding(0, host.dp(2), 0, host.dp(8));
            streamCard.addView(emptyNote);
        } else {
            for (var item : dedupStream) {
                boolean isPrim = !subtitlesTurnedOff && matchesSelectedSub(item, selectedStreamPrimaryUrl);
                boolean isSec = !subtitlesTurnedOff && matchesSelectedSub(item, selectedStreamSecondaryUrl);
                boolean rowActive = isPrim || isSec;

                var r = new LinearLayout(host);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                var rowBg = new GradientDrawable();
                rowBg.setColor(rowActive ? 0xff1e1c14 : 0xff141519);
                rowBg.setCornerRadius(host.dp(10));
                rowBg.setStroke(host.dp(1), rowActive ? TvHubActivity.GOLD : 0xff2c2d35);
                r.setBackground(rowBg);
                r.setPadding(host.dp(10), host.dp(8), host.dp(8), host.dp(8));

                var nameTv = host.label(item.label(), 13);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                nameTv.setTextColor(rowActive ? TvHubActivity.GOLD : 0xfffafafa);
                nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                r.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));

                var pBtn = host.button(isPrim ? host.l("أساسي ✓", "Primary ✓") : host.l("أساسي", "Primary"),
                        () -> sendStreamSubtitleToTv(item, false), isPrim);
                styleSubtitleActionButton(pBtn, isPrim, false);
                pBtn.setTextSize(11);
                pBtn.setMinHeight(host.dp(36));
                pBtn.setPadding(host.dp(10), 0, host.dp(10), 0);
                var pLp = new LinearLayout.LayoutParams(-2, host.dp(36));
                pLp.setMarginEnd(host.dp(4));
                r.addView(pBtn, pLp);

                var rLp = new LinearLayout.LayoutParams(-1, -2);
                rLp.bottomMargin = host.dp(6);
                streamCard.addView(r, rLp);
            }
            if (!dedupTv.isEmpty()) {
                boolean embeddedActive = !subtitlesTurnedOff && (hasEmbeddedSelected || hasEmbeddedSecondary);
                StringBuilder embText = new StringBuilder();
                embText.append(host.l("الترجمات المدمجة", "Embedded Subtitles"))
                       .append(" (").append(dedupTv.size()).append(")");
                if (!subtitlesTurnedOff && !activeEmbeddedLang.isBlank()) {
                    embText.append(" · ").append(activeEmbeddedLang);
                }
                if (!subtitlesTurnedOff && hasEmbeddedSecondary) {
                    for (var t : dedupTv) {
                        if (t.index() == selectedEmbeddedSecondaryIndex) {
                            String secLang = (t.language() != null && !t.language().isBlank()) ? t.language().trim() : t.label();
                            if (!secLang.isBlank()) embText.append(" + ").append(secLang);
                            break;
                        }
                    }
                }
                var embBtn = host.button(embText.toString(), this::showEmbeddedSubtitlesDialog, embeddedActive);
                styleSubtitleActionButton(embBtn, embeddedActive, false);
                embBtn.setTextSize(13);
                var embLp = new LinearLayout.LayoutParams(-1, -2);
                embLp.bottomMargin = host.dp(8);
                streamCard.addView(embBtn, embLp);
            }
        }

        var quickRow = host.row(streamCard);
        boolean dualEnabled = dualEnabled(host);
        var dualBtn = host.button(dualEnabled ? host.l("مزدوجة: مفعّلة ✓", "Dual Subs: ON ✓")
                        : host.l("تفعيل الترجمة المزدوجة", "Enable Dual Subs"), this::toggleDual, dualEnabled);
        var dLp = new LinearLayout.LayoutParams(0, -2, 1f);
        dLp.setMarginEnd(host.dp(6));
        quickRow.addView(dualBtn, dLp);

        var offBtn = host.button(
                subsOff ? host.l("الترجمة: متوقفة", "Subtitles: OFF") : host.l("إيقاف الترجمة", "Turn Off Subs"),
                () -> {
                    subtitlesTurnedOff = true;
                    selectedStreamPrimaryUrl = "";
                    selectedStreamSecondaryUrl = "";
                    selectedEmbeddedSecondaryIndex = -1;
                    for (int i = 0; i < activeTvSubtitleTracks.size(); i++) {
                        var t = activeTvSubtitleTracks.get(i);
                        if (t.selected()) {
                            activeTvSubtitleTracks.set(i, new TvSubtitleTrackItem(t.index(), t.label(), t.language(), false));
                        }
                    }
                    host.command("SUBTITLE_CLEAR", "cast_id", host.activeCastId);
                    host.notifyStatus(host.l("تم إيقاف الترجمة على الشاشة", "Subtitles turned off"));
                    refreshActiveStreamSubCard();
                },
                false);
        styleSubtitleActionButton(offBtn, false, subsOff);
        quickRow.addView(offBtn, new LinearLayout.LayoutParams(0, -2, 1f));

        var settingsShortcut = host.button(host.l("⚙ إعدادات ترجمة البث المتقدمة", "⚙ Cast Subtitle Settings"),
                this::showCastSubtitleSettingsDialog, true);
        var ssLp = new LinearLayout.LayoutParams(-1, -2);
        ssLp.topMargin = host.dp(8);
        streamCard.addView(settingsShortcut, ssLp);
    }

    void sendStreamSubtitleToTv(StreamSubtitleItem item, boolean isSecondary) {
        if (item == null) return;
        subtitlesTurnedOff = false;
        String key = streamSubKey(item);
        if (isSecondary) selectedStreamSecondaryUrl = key;
        else selectedStreamPrimaryUrl = key;
        refreshActiveStreamSubCard();
        host.watchSyncIo.execute(() -> {
            var targetSocket = (host.socket != null && host.socket.isOpen()) ? host.socket : CinemaRemoteBridge.getConnectedSocket();
            if (targetSocket == null || !targetSocket.isOpen()) {
                host.main.post(() -> host.notifyStatus(host.l("الشاشة غير متصلة حالياً", "TV is not connected")));
                return;
            }
            String resolved = host.resolveSubtitleUrlForCast(item.url(), item.memPath(), item.referer(),
                    item.origin(), item.userAgent(), item.cookie(), item.authorization());
            if (resolved.isBlank()) return;
            try {
                var payload = new org.json.JSONObject()
                        .put("type", "SUBTITLE")
                        .put("subtitle_url", resolved)
                        .put("secondary", isSecondary)
                        .put("cast_id", host.activeCastId == null ? "" : host.activeCastId);
                host.safeSend(targetSocket, payload.toString());
                host.main.post(() -> {
                    host.notifyStatus(isSecondary
                            ? host.l("تم إرسال الترجمة الثانوية للشاشة ✓", "Secondary subtitle sent to TV ✓")
                            : host.l("تم تفعيل الترجمة على الشاشة ✓", "Subtitle sent to TV ✓"));
                    refreshActiveStreamSubCard();
                });
            } catch (Exception ignored) {}
        });
    }

    void selectTvEmbeddedSubtitle(int trackIndex) {
        selectTvEmbeddedSubtitle(trackIndex, false);
    }

    void selectTvEmbeddedSubtitle(int trackIndex, boolean isSecondary) {
        var targetSocket = (host.socket != null && host.socket.isOpen()) ? host.socket : CinemaRemoteBridge.getConnectedSocket();
        if (targetSocket == null || !targetSocket.isOpen()) {
            host.notifyStatus(host.l("الشاشة غير متصلة حالياً", "TV is not connected"));
            return;
        }
        subtitlesTurnedOff = false;
        if (isSecondary) {
            selectedStreamSecondaryUrl = "";
            selectedEmbeddedSecondaryIndex = trackIndex;
        } else {
            selectedStreamPrimaryUrl = "";
            for (int i = 0; i < activeTvSubtitleTracks.size(); i++) {
                var t = activeTvSubtitleTracks.get(i);
                activeTvSubtitleTracks.set(i, new TvSubtitleTrackItem(t.index(), t.label(), t.language(), t.index() == trackIndex));
            }
        }
        refreshActiveStreamSubCard();
        try {
            host.safeSend(targetSocket, new org.json.JSONObject()
                    .put("type", "SUBTITLE_TRACK_SELECT")
                    .put("track_index", trackIndex)
                    .put("secondary", isSecondary).toString());
            host.notifyStatus(isSecondary
                    ? host.l("تم إرسال المسار المدمج كترجمة ثانوية ✓", "Embedded track set as dual subtitle ✓")
                    : host.l("تم تبديل مسار الترجمة المدمجة ✓", "Embedded subtitle switched ✓"));
        } catch (Exception ignored) {}
    }

    void showEmbeddedSubtitlesDialog() {
        var tracks = deduplicateTvTracks(activeTvSubtitleTracks, deduplicateStreamSubtitles(activeStreamSubtitles));
        if (tracks.isEmpty()) {
            host.notifyStatus(host.l("لا توجد ترجمات مدمجة متاحة حالياً", "No embedded subtitles available"));
            return;
        }
        var sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(host);
        var panel = host.column();
        panel.setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(18));
        panel.setBackgroundColor(0xff141518);

        var topRow = new LinearLayout(host);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        var header = host.label(host.l("الترجمات المدمجة", "Embedded Subtitles") + " (" + tracks.size() + ")", 19);
        header.setTextColor(TvHubActivity.GOLD);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        topRow.addView(header, new LinearLayout.LayoutParams(0, -2, 1f));

        var closeBtn = host.button(host.l("إغلاق", "Close"), sheet::dismiss, false);
        styleSubtitleActionButton(closeBtn, false, false);
        closeBtn.setTextSize(12);
        closeBtn.setMinHeight(host.dp(36));
        topRow.addView(closeBtn, new LinearLayout.LayoutParams(-2, host.dp(36)));
        panel.addView(topRow);

        var recycler = new androidx.recyclerview.widget.RecyclerView(host);
        recycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(host));
        int maxH = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.62f);
        var listLp = new LinearLayout.LayoutParams(-1, maxH);
        listLp.topMargin = host.dp(10);
        panel.addView(recycler, listLp);

        final androidx.recyclerview.widget.RecyclerView.Adapter<?>[] adapterRef = new androidx.recyclerview.widget.RecyclerView.Adapter<?>[1];
        adapterRef[0] = new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @Override
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                var row = new LinearLayout(parent.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(host.dp(10), host.dp(8), host.dp(8), host.dp(8));
                var rLp = new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2);
                rLp.bottomMargin = host.dp(6);
                row.setLayoutParams(rLp);

                var label = host.label("", 13);
                label.setTag("label");
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

                var pBtn = host.button("", () -> {}, false);
                pBtn.setTag("primary");
                pBtn.setTextSize(11);
                pBtn.setMinHeight(host.dp(36));
                pBtn.setPadding(host.dp(10), 0, host.dp(10), 0);
                var pLp = new LinearLayout.LayoutParams(-2, host.dp(36));
                pLp.setMarginEnd(host.dp(4));
                row.addView(pBtn, pLp);

                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(row) {};
            }

            @Override
            public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {
                var currentTracks = deduplicateTvTracks(activeTvSubtitleTracks, deduplicateStreamSubtitles(activeStreamSubtitles));
                if (position < 0 || position >= currentTracks.size()) return;
                var track = currentTracks.get(position);
                var row = (LinearLayout) holder.itemView;
                var label = (TextView) row.findViewWithTag("label");
                var pBtn = (MaterialButton) row.findViewWithTag("primary");

                boolean isPrim = !subtitlesTurnedOff && track.selected();
                boolean isSec = !subtitlesTurnedOff && selectedEmbeddedSecondaryIndex == track.index();
                boolean rowActive = isPrim || isSec;

                var rowBg = new GradientDrawable();
                rowBg.setColor(rowActive ? 0xff1e1c14 : 0xff1c1d22);
                rowBg.setCornerRadius(host.dp(10));
                rowBg.setStroke(host.dp(1), rowActive ? TvHubActivity.GOLD : 0xff2c2d35);
                row.setBackground(rowBg);

                String lang = track.language() != null && !track.language().isBlank()
                        ? " · " + track.language().trim() : "";
                label.setText(track.label() + lang);
                label.setTextColor(rowActive ? TvHubActivity.GOLD : 0xfffafafa);
                label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

                pBtn.setText(isPrim ? host.l("أساسي ✓", "Primary ✓") : host.l("أساسي", "Primary"));
                styleSubtitleActionButton(pBtn, isPrim, false);
                pBtn.setOnClickListener(v -> {
                    selectTvEmbeddedSubtitle(track.index(), false);
                    if (adapterRef[0] != null) adapterRef[0].notifyDataSetChanged();
                });

            }

            @Override
            public int getItemCount() {
                return deduplicateTvTracks(activeTvSubtitleTracks, deduplicateStreamSubtitles(activeStreamSubtitles)).size();
            }
        };
        recycler.setAdapter(adapterRef[0]);
        var dualButton = host.button(dualEnabled(host) ? host.l("مزدوجة: مفعّلة ✓", "Dual Subs: ON ✓")
                        : host.l("تفعيل الترجمة المزدوجة", "Enable Dual Subs"), () -> {
            toggleDual();
            sheet.dismiss();
        }, dualEnabled(host));
        panel.addView(dualButton, new LinearLayout.LayoutParams(-1, -2));
        sheet.setContentView(panel);
        sheet.show();
    }

    void enableAutoDualStreamSubtitles() {
        var list = deduplicateStreamSubtitles(activeStreamSubtitles);
        if (list.isEmpty()) return;
        StreamSubtitleItem ar = null;
        StreamSubtitleItem en = null;
        for (var s : list) {
            String lang = normalizeSubLanguage(s.language(), s.label());
            if (ar == null && "ar".equals(lang)) ar = s;
            else if (en == null && "en".equals(lang)) en = s;
        }
        if (ar == null) ar = list.get(0);
        if (en == null && list.size() >= 2) {
            en = list.get(ar == list.get(0) ? 1 : 0);
        }
        subtitlesTurnedOff = false;
        if (ar != null) selectedStreamPrimaryUrl = streamSubKey(ar);
        if (en != null) selectedStreamSecondaryUrl = streamSubKey(en);
        refreshActiveStreamSubCard();
        if (ar != null) sendStreamSubtitleToTv(ar, false);
        if (en != null) {
            final StreamSubtitleItem fEn = en;
            host.main.postDelayed(() -> sendStreamSubtitleToTv(fEn, true), 250);
        }
    }

    void showCastSubtitleSettingsDialog() {
        var sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(host);
        var scroll = subtitleSheetScroll(sheet);
        var panel = host.column();
        panel.setPadding(host.dp(18), host.dp(14), host.dp(18), host.dp(22));
        panel.setBackgroundColor(0xff141518);

        var header = host.label(host.l("⚙ إعدادات ترجمة البث", "⚙ Cast Subtitle Settings"), 20);
        header.setTextColor(TvHubActivity.GOLD);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, host.dp(10));
        panel.addView(header);

        // Dual mode & layout controls
        var modeCard = host.card(panel, host.l("وضع العرض والترجمة المزدوجة", "Display & Dual Mode"));
        var modeRow1 = host.row(modeCard);
        styleControl(modeRow1, host.l("مزدوج (Dual)", "Dual"), "sub_mode", 0);
        styleControl(modeRow1, host.l("الأساسية فقط", "Primary"), "sub_mode", 1);
        var modeRow2 = host.row(modeCard);
        styleControl(modeRow2, host.l("الثانوية فقط", "Secondary"), "sub_mode", 2);
        styleControl(modeRow2, host.l("إظهار / إخفاء", "Show / Hide"), "visible", 0);
        var clearRow = host.row(modeCard);
        var clearSecBtn = host.button(host.l("إزالة الترجمة الثانوية", "Clear Secondary"), () -> {
            selectedStreamSecondaryUrl = "";
            sendSubtitleStyle("sub_clear_secondary", 0);
            refreshActiveStreamSubCard();
            host.notifyStatus(host.l("تمت إزالة الترجمة الثانوية", "Secondary subtitle cleared"));
        }, false);
        clearRow.addView(clearSecBtn, new LinearLayout.LayoutParams(0, host.dp(48), 1));

        // Dual Subtitle Position Card (synced live & saved for future TV sessions)
        var subPrefs = host.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE);
        var posCard = host.card(panel, host.l("موضع الترجمة المزدوجة", "Dual Subtitle Position"));
        Runnable[] rebuildPos = new Runnable[1];
        rebuildPos[0] = () -> {
            while (posCard.getChildCount() > 1) posCard.removeViewAt(posCard.getChildCount() - 1);
            int pos = Math.max(0, Math.min(2, subPrefs.getInt("dual_sub_position", 1)));
            String posDesc = switch (pos) {
                case 0 -> host.l("الحالي: كلاهما بالأسفل (فوق بعضهما)", "Active: Both at Bottom (Stacked)");
                case 2 -> host.l("الحالي: متباعد (الأساسية أعلى · الثانوية أسفل)", "Active: Split (Primary Top · Secondary Bottom)");
                default -> host.l("الحالي: متباعد (الأساسية أسفل · الثانوية أعلى)", "Active: Split (Primary Bottom · Secondary Top)");
            };
            var descTv = host.label(posDesc, 12);
            descTv.setTextColor(0xffe5b84b);
            descTv.setPadding(0, host.dp(2), 0, host.dp(8));
            posCard.addView(descTv);

            java.util.function.IntConsumer applyPos = nextMode -> {
                int clamped = Math.max(0, Math.min(2, nextMode));
                subPrefs.edit().putInt("dual_sub_position", clamped).apply();
                com.meedx.providers.UnifiedSubtitleConfig.setDualSubPosition(host, clamped);
                if (host.connection != null) host.connection.sendSubtitleConfigToTv();
                if (host.connected && host.socket != null && host.socket.isOpen()) {
                    sendSubtitleStyle("dual_position", clamped);
                } else {
                    host.notifyStatus(host.l("تم الحفظ · سيتزامن مع الشاشة عند الاتصال", "Saved · Will sync when TV connects"));
                }
                rebuildPos[0].run();
            };

            var topModeRow = host.row(posCard);
            var stackedBtn = host.button(host.l("كلاهما بالأسفل", "Both at Bottom"), () -> applyPos.accept(0), pos == 0);
            stackedBtn.setTextSize(12);
            var sLp = new LinearLayout.LayoutParams(0, host.dp(48), 1);
            sLp.setMarginEnd(host.dp(6));
            topModeRow.addView(stackedBtn, sLp);

            var splitBtn = host.button(host.l("أعلى وأسفل", "Top & Bottom"), () -> applyPos.accept(pos == 2 ? 2 : 1), pos != 0);
            splitBtn.setTextSize(12);
            topModeRow.addView(splitBtn, new LinearLayout.LayoutParams(0, host.dp(48), 1));

            if (pos != 0) {
                var subOrderLbl = host.label(host.l("ترتيب الأعلى والأسفل:", "Split Order (Swap):"), 11);
                subOrderLbl.setTextColor(0xffa1a1aa);
                subOrderLbl.setPadding(0, host.dp(6), 0, host.dp(4));
                posCard.addView(subOrderLbl);

                var splitRow = host.row(posCard);
                var primBotBtn = host.button(host.l("الأساسية أسفل · الثانوية أعلى", "Primary Bottom / Dual Top"),
                        () -> applyPos.accept(1), pos == 1);
                primBotBtn.setTextSize(11);
                var pbLp = new LinearLayout.LayoutParams(0, host.dp(48), 1);
                pbLp.setMarginEnd(host.dp(6));
                splitRow.addView(primBotBtn, pbLp);

                var primTopBtn = host.button(host.l("الأساسية أعلى · الثانوية أسفل", "Primary Top / Dual Bottom"),
                        () -> applyPos.accept(2), pos == 2);
                primTopBtn.setTextSize(11);
                splitRow.addView(primTopBtn, new LinearLayout.LayoutParams(0, host.dp(48), 1));
            }
        };
        rebuildPos[0].run();

        // Precise millisecond timing sync card
        var syncCard = host.card(panel, host.l("ضبط التزامن بالملي ثانية", "Sync Offset (ms)"));
        var offsetBadge = host.label((liveSubSyncDelayMs >= 0 ? "+" : "") + liveSubSyncDelayMs + " ms", 15);
        offsetBadge.setTextColor(TvHubActivity.GOLD);
        offsetBadge.setGravity(Gravity.CENTER);
        offsetBadge.setPadding(0, host.dp(2), 0, host.dp(8));
        syncCard.addView(offsetBadge);
        var syncRow = host.row(syncCard);
        for (int delta : new int[]{-500, -100, 100, 500}) {
            String txt = (delta > 0 ? "+" : "") + delta + "ms";
            var b = host.button(txt, () -> {
                liveSubSyncDelayMs += delta;
                offsetBadge.setText((liveSubSyncDelayMs >= 0 ? "+" : "") + liveSubSyncDelayMs + " ms");
                sendSubtitleStyle("delay", delta);
            }, false);
            b.setSingleLine(true);
            b.setTextSize(12);
            b.setPadding(0, 0, 0, 0);
            syncRow.addView(b, new LinearLayout.LayoutParams(0, host.dp(48), 1));
        }
        var resetSyncBtn = host.button(host.l("تصفير التزامن (0 ms)", "Reset Sync (0 ms)"), () -> {
            liveSubSyncDelayMs = 0L;
            offsetBadge.setText("+0 ms");
            sendSubtitleStyle("reset", 0);
        }, false);
        var rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.topMargin = host.dp(6);
        syncCard.addView(resetSyncBtn, rLp);

        // Unified Appearance card (Colors, Size, Position, Dark BG Opacity 0-100%, Outline Size + Color)
        attachPhoneSubtitleAppearanceCard(panel, false);

        scroll.addView(panel);
        sheet.setContentView(scroll);
        expandSubtitleSheet(sheet);
        sheet.show();
    }

    void styleControl(LinearLayout row, String text, String action, int value) {
        var control = host.button(text, () -> sendSubtitleStyle(action, value), false);
        control.setSingleLine(true);
        control.setTextSize(13);
        control.setPadding(0, 0, 0, 0);
        if ("position".equals(action)) control.setContentDescription(value > 0 ? "Raise subtitles" : "Lower subtitles");
        row.addView(control, new LinearLayout.LayoutParams(0, host.dp(48), 1));
    }

    void swatch(LinearLayout row, String name, int color) {
        var control = host.button(name, () -> sendSubtitleStyle("color", color), false);
        control.setSingleLine(true);
        control.setTextSize(11);
        control.setPadding(0, 0, 0, 0);
        var dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(host.dp(16), host.dp(16));
        control.setIcon(dot);
        control.setIconTint(null);
        control.setIconSize(host.dp(16));
        control.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        row.addView(control, new LinearLayout.LayoutParams(0, host.dp(56), 1));
    }

    void sendSubtitleStyle(String action, int value) {
        boolean isPersistedAppearance = "color".equals(action) || "size".equals(action)
                || "position".equals(action) || "background".equals(action)
                || "bg_opacity".equals(action) || "outline".equals(action)
                || "outline_color".equals(action) || "delay_reset".equals(action)
                || "reset".equals(action);
        if (isPersistedAppearance) {
            int sz = com.meedx.providers.UnifiedSubtitleConfig.textSize(host);
            int vp = com.meedx.providers.UnifiedSubtitleConfig.vpos(host);
            int col = com.meedx.providers.UnifiedSubtitleConfig.textColor(host);
            boolean bg = com.meedx.providers.UnifiedSubtitleConfig.opaqueBg(host);
            int bgOp = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(host);
            int outline = com.meedx.providers.UnifiedSubtitleConfig.outlineLevel(host);
            int outlineCol = com.meedx.providers.UnifiedSubtitleConfig.outlineColor(host);
            long del = com.meedx.providers.UnifiedSubtitleConfig.defaultDelayMs(host);
            switch (action) {
                case "color" -> col = value;
                case "size" -> sz = Math.max(18, Math.min(80, sz + value));
                case "position" -> vp = Math.max(16, Math.min(200, vp + value));
                case "background" -> bg = !bg;
                case "bg_opacity" -> {
                    bgOp = value >= 0
                            ? com.meedx.providers.UnifiedSubtitleConfig.normalizeBgOpacity(value)
                            : com.meedx.providers.UnifiedSubtitleConfig.nextBgOpacity(bgOp);
                    bg = bgOp > 0;
                }
                case "outline" -> outline = value >= 0
                        ? Math.max(0, Math.min(3, value))
                        : com.meedx.providers.UnifiedSubtitleConfig.nextOutlineLevel(outline);
                case "outline_color" -> {
                    outlineCol = com.meedx.providers.UnifiedSubtitleConfig.normalizeOutlineColor(value);
                    if (outline == 0) outline = 2;
                }
                case "delay_reset" -> del = 0L;
                case "reset" -> {
                    sz = 36; vp = 64; col = android.graphics.Color.WHITE;
                    bg = false; bgOp = 70; outline = 0; outlineCol = android.graphics.Color.BLACK; del = 0L;
                }
            }
            com.meedx.providers.UnifiedSubtitleConfig.saveAppearance(host, sz, vp, col, bg, bgOp, outline, outlineCol, del);
            if (host.connection != null) host.connection.sendSubtitleConfigToTv();
        }
        if (!host.connected || host.socket == null || !host.socket.isOpen()) {
            if (!isPersistedAppearance && !"dual_position".equals(action) && !"sub_clear_secondary".equals(action)) {
                host.notifyStatus("Connect to the TV first");
            }
            return;
        }
        try {
            host.socket.send(new org.json.JSONObject().put("type", "SUBTITLE_STYLE")
                    .put("action", action).put("value", value).toString());
        } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException error) {
            host.notifyStatus("Could not send subtitle settings");
        }
    }

    void editSubtitleKey(Runnable saved) {
        var input = new EditText(host);
        input.setHint(org.proninyaroslav.libretorrent.R.string.cinema_subsource_key_label);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false);
        var dialog = new androidx.appcompat.app.AlertDialog.Builder(host).setTitle(org.proninyaroslav.libretorrent.R.string.cinema_subsource_key_label)
                .setView(input).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_action_save, null).show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            var values = ApiKeyStore.parse(input.getText().toString());
            if (values.size() != 1) { input.setError("Enter one valid API key"); return; }
            var save = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            save.setEnabled(false);
            new Thread(() -> {
                try {
                    var store = new ApiKeyStore(host);
                    store.save(values);
                    if (!store.read().equals(values)) throw new java.io.IOException("Key verification failed");
                    host.runOnUiThread(() -> {
                        if (host.isDestroyed()) return;
                        saved.run();
                        dialog.dismiss();
                        host.notifyStatus("SubSource API key saved on this phone");
                    });
                } catch (Exception error) {
                    host.runOnUiThread(() -> {
                        if (host.isDestroyed()) return;
                        input.setError("Could not save the key. Try again.");
                        save.setEnabled(true);
                    });
                }
            }, "cinema-subtitle-key").start();
        });
    }

    void subtitleTask(SubtitleTask task) {
        new Thread(() -> {
            try { task.run(); }
            catch (Exception error) {
                host.runOnUiThread(() -> { if (!host.isDestroyed()) host.notifyStatus(error instanceof java.io.IOException
                        && error.getMessage() != null && error.getMessage().startsWith("SubSource HTTP ")
                        ? error.getMessage() : "Subtitle operation failed. Check network and file."); });
            }
        }, "cinema-subtitles").start();
    }

    void searchSubtitles(String title, int season, int episode, String videoRelease, int language) {
        if (title.isBlank()) { host.notifyStatus(host.l("أدخل اسم الفيلم أو المسلسل", "Enter a movie or series title")); return; }
        subtitleTask(() -> {
            String key = com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.SUBSOURCE).trim();
            if (key.isEmpty()) {
                var legacyKeys = new ApiKeyStore(host).read();
                if (!legacyKeys.isEmpty()) key = legacyKeys.get(0);
            }
            if (key.isEmpty()) {
                host.runOnUiThread(() -> {
                    if (!host.isDestroyed()) host.notifyStatus(host.l("أضف مفتاح Subsource في تبويب الاتصال أو اختر OpenSubtitles المجاني",
                            "Save a Subsource key in the Connection tab or switch to OpenSubtitles"));
                });
                return;
            }
            final String finalKey = key;
            String path = "/movies/search?searchType=text&q=" + android.net.Uri.encode(title)
                    + (season > 0 ? "&season=" + season : "");
            var results = new org.json.JSONObject(new String(subtitleRequest(path, finalKey, 2000000),
                    java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("data");
            var candidates = new java.util.LinkedHashMap<Long, org.json.JSONObject>();
            long match = -1;
            int exactCount = 0;
            for (int i = 0; i < results.length(); i++) {
                var result = results.getJSONObject(i);
                long id = result.optLong("movieId", -1);
                if (id <= 0 || candidates.putIfAbsent(id, result) != null) continue;
                if (normalizedSubtitleTitle(title).equals(normalizedSubtitleTitle(result.optString("title")))
                        && (season <= 0 || result.optInt("season", -1) == season)) {
                    exactCount++;
                    match = id;
                }
            }
            if (exactCount == 1) {
                loadSubtitleResults(finalKey, match, language, season, episode, videoRelease);
            } else {
                var choices = new org.json.JSONArray(candidates.values());
                showSubtitleResults(host.l("اختر الفيلم أو المسلسل", "Choose movie or series"), choices, "title", result ->
                        loadSubtitleResults(finalKey, result.optLong("movieId"), language, season, episode, videoRelease));
            }
        });
    }

    void searchOpenSubtitles(String title, int season, int episode, int languageChoice) {
        if (title.isBlank()) { host.notifyStatus(host.l("أدخل اسم الفيلم أو المسلسل", "Enter a movie or series title")); return; }
        subtitleTask(() -> {
            String apiKey = com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES).trim();
            String langParam = switch (languageChoice) {
                case 1 -> "eng";
                case 2 -> "ara,eng";
                default -> "ara";
            };
            String urlStr = "https://rest.opensubtitles.org/search/query-" + android.net.Uri.encode(title)
                    + (season > 0 ? "/season-" + season : "")
                    + (episode > 0 ? "/episode-" + episode : "")
                    + "/sublanguageid-" + langParam;
            var conn = (javax.net.ssl.HttpsURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "TemporaryUserAgent");
            if (!apiKey.isEmpty()) conn.setRequestProperty("X-User-Agent", "LocalCinema v1.0.0-beta.9");
            byte[] raw;
            try (var in = conn.getInputStream()) {
                raw = readLimited(in, 2000000);
            } finally {
                conn.disconnect();
            }
            var arr = new org.json.JSONArray(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            if (arr.length() == 0) {
                host.runOnUiThread(() -> { if (!host.isDestroyed()) host.notifyStatus(host.l("لا توجد نتائج مطابقة في OpenSubtitles", "No matching OpenSubtitles results")); });
                return;
            }
            int limit = Math.min(20, arr.length());
            var labels = new String[limit];
            var urls = new String[limit];
            var names = new String[limit];
            for (int i = 0; i < limit; i++) {
                var item = arr.getJSONObject(i);
                String name = item.optString("SubFileName", "subtitle.srt");
                String lang = item.optString("LanguageName", "");
                labels[i] = (lang.isEmpty() ? "" : lang + " · ") + name;
                urls[i] = item.optString("SubDownloadLink", "");
                names[i] = name;
            }
            host.runOnUiThread(() -> {
                if (host.isDestroyed()) return;
                new androidx.appcompat.app.AlertDialog.Builder(host)
                        .setTitle(host.l("نتائج OpenSubtitles", "OpenSubtitles Results"))
                        .setItems(labels, (d, which) -> downloadOpenSubtitleFile(urls[which], names[which]))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    void downloadOpenSubtitleFile(String downloadUrl, String fileName) {
        if (downloadUrl == null || downloadUrl.isBlank()) return;
        subtitleTask(() -> {
            var conn = (javax.net.ssl.HttpsURLConnection) new java.net.URL(downloadUrl).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "TemporaryUserAgent");
            byte[] data;
            try (var rawIn = conn.getInputStream()) {
                if (downloadUrl.endsWith(".gz")) {
                    try (var gz = new java.util.zip.GZIPInputStream(rawIn)) {
                        data = readLimited(gz, 524288);
                    }
                } else {
                    data = readLimited(rawIn, 524288);
                }
            } finally {
                conn.disconnect();
            }
            String safeName = (fileName != null && fileName.matches("(?i).*\\.(srt|vtt|ass)$")) ? fileName : "subtitle.srt";
            sendSubtitle(data, safeName);
        });
    }

    static String normalizedSubtitleTitle(String title) {
        return title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    void loadSubtitleResults(String key, long id, int choice, int season, int episode, String videoRelease) {
        subtitleTask(() -> {
            var matches = new org.json.JSONArray();
            for (String language : switch (choice) {
                case 1 -> new String[]{"english"};
                case 2 -> new String[]{"arabic", "english"};
                default -> new String[]{"arabic"};
            }) {
                var items = new org.json.JSONObject(new String(subtitleRequest("/subtitles?movieId=" + id
                        + "&language=" + language + "&limit=50&sort=rating", key, 2000000),
                        java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("data");
                var ranked = new java.util.ArrayList<org.json.JSONObject>();
                for (int i = 0; i < items.length(); i++) {
                    var item = items.getJSONObject(i);
                    var releases = item.optJSONArray("releaseInfo");
                    if (releases == null) releases = new org.json.JSONArray().put(item.optString("releaseInfo"));
                    String best = "";
                    int score = Integer.MIN_VALUE;
                    for (int j = 0; j < releases.length(); j++) {
                        String release = releases.optString(j);
                        if (episode >= 0 && !matchesEpisode(release, season, episode)) continue;
                        var rank = SubtitleMatch.rank(videoRelease, release, true, season, episode,
                                null, null, null, null, 0, 0);
                        if (!rank.episodeConflict() && rank.score() >= 0 && rank.score() > score) {
                            best = release;
                            score = rank.score();
                        }
                    }
                    if (score != Integer.MIN_VALUE && item.optLong("subtitleId", -1) > 0) {
                        ranked.add(item.put("releaseInfo", best).put("matchScore", score));
                    }
                }
                ranked.sort(java.util.Comparator.comparingInt(
                        (org.json.JSONObject item) -> item.optInt("matchScore")).reversed());
                for (var item : ranked) matches.put(item);
            }
            showSubtitleReleases(matches, result ->
                    downloadSubtitle(key, result.optLong("subtitleId"), season, episode));
        });
    }

    static boolean matchesEpisode(String release, int season, int episode) {
        var seasons = EPISODE_TAG.matcher(release);
        while (seasons.find()) {
            if (Integer.parseInt(seasons.group(1)) != season) continue;
            var numbers = EPISODE_NUMBER.matcher(seasons.group(2));
            while (numbers.find()) {
                if (Integer.parseInt(numbers.group(1)) == episode) return true;
            }
        }
        return false;
    }

    void showSubtitleReleases(org.json.JSONArray items,
            java.util.function.Consumer<org.json.JSONObject> selected) {
        host.runOnUiThread(() -> {
            if (host.isDestroyed()) return;
            if (items.length() == 0) {
                host.notifyStatus("No matching results");
                return;
            }
            var results = host.column();
            for (int i = 0; i < items.length(); i++) {
                var item = items.optJSONObject(i);
                if (item == null || item.optLong("subtitleId", -1) <= 0) continue;
                addSubtitleRelease(results, item, selected);
            }
            if (results.getChildCount() == 0) {
                host.notifyStatus("No downloadable subtitles found");
                return;
            }
            showPanel("Choose subtitles", results);
        });
    }

    void addSubtitleRelease(LinearLayout results, org.json.JSONObject item,
            java.util.function.Consumer<org.json.JSONObject> selected) {
        var aliases = item.optJSONArray("releaseInfo");
        String release = aliases == null ? item.optString("releaseInfo") : aliases.optString(0);
        String language = switch (item.optString("language").toLowerCase(java.util.Locale.ROOT)) {
            case "arabic", "ar", "ara" -> "العربية";
            case "english", "en", "eng" -> "English";
            case "" -> "Subtitle";
            default -> item.optString("language");
        };
        String episode = "";
        var tags = new java.util.LinkedHashSet<String>();
        var matcher = RELEASE_TAG.matcher(release);
        while (matcher.find()) {
            String tag = matcher.group();
            if (tag.matches("(?i)S\\d{1,2}(?:E\\d{1,3})+")) {
                if (episode.isEmpty()) episode = tag.toUpperCase(java.util.Locale.ROOT);
            } else {
                tags.add(tag);
            }
        }
        int group = release.lastIndexOf('-');
        if (group > 0) {
            String suffix = release.substring(group + 1);
            if (suffix.matches("[A-Za-z][A-Za-z0-9]{1,19}")
                    && !matcher.reset().region(group + 1, release.length()).matches()
                    && !release.substring(group - 1, group).isBlank()) {
                tags.add(suffix);
            }
        }

        var result = host.card(results, episode.isEmpty() ? language : episode + "  ·  " + language);
        if (!tags.isEmpty() || !release.isBlank()) {
            var detail = host.label(tags.isEmpty() ? release.replace('.', ' ') : String.join("  ·  ", tags), 14);
            detail.setTextColor(0xffc9c7c4);
            detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            result.addView(detail);
        }
        result.setFocusable(true);
        var highlight = new android.util.TypedValue();
        if (host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, highlight, true)) {
            result.setForeground(host.getDrawable(highlight.resourceId));
        }
        String caption = episode.isEmpty() ? language : episode + " · " + language;
        result.setOnClickListener(view -> {
            var confirm = new androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle(caption)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_action_download, (dialog, which) -> selected.accept(item));
            if (!release.isBlank()) confirm.setMessage(release);
            confirm.show();
        });
    }

    void showSubtitleResults(String title, org.json.JSONArray items, String field,
            java.util.function.Consumer<org.json.JSONObject> selected) throws org.json.JSONException {
        var labels = new String[items.length()];
        for (int i = 0; i < labels.length; i++) {
            var item = items.getJSONObject(i);
            var releases = item.optJSONArray(field);
            labels[i] = item.optString("language", "") + " " + (releases == null
                    ? item.optString(field) : releases.optString(0));
            if (item.optInt("season") > 0) labels[i] += " · S" + item.optInt("season");
            if (item.optInt("releaseYear") > 0) labels[i] += " · " + item.optInt("releaseYear");
        }
        host.runOnUiThread(() -> {
            if (host.isDestroyed()) return;
            if (labels.length == 0) { host.notifyStatus("No matching results"); return; }
            new androidx.appcompat.app.AlertDialog.Builder(host).setTitle(title).setItems(labels,
                    (dialog, index) -> selected.accept(items.optJSONObject(index)))
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    void downloadSubtitle(String key, long id, int season, int episode) {
        subtitleTask(() -> {
            byte[] archive = subtitleRequest("/subtitles/" + id + "/download", key, 20000000);
            var names = new java.util.ArrayList<String>();
            var files = new java.util.ArrayList<byte[]>();
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                while (files.size() < 30 && (entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().toLowerCase(java.util.Locale.ROOT)
                            .matches(".*[.](srt|ass|vtt)$")) continue;
                    if (episode >= 0 && EPISODE_TAG.matcher(entry.getName()).find()
                            && !matchesEpisode(entry.getName(), season, episode)) continue;
                    names.add(entry.getName());
                    files.add(readLimited(zip, 524288));
                }
            }
            host.runOnUiThread(() -> {
                if (host.isDestroyed()) return;
                if (files.isEmpty()) { host.notifyStatus("No subtitle files found"); return; }
                if (episode >= 0 && files.size() > 1) {
                    for (int i = names.size() - 1; i >= 0; i--) {
                        if (!matchesEpisode(names.get(i), season, episode)) {
                            names.remove(i);
                            files.remove(i);
                        }
                    }
                    if (files.isEmpty()) { host.notifyStatus("No file matches this episode"); return; }
                }
                if (files.size() == 1) sendSubtitle(files.get(0), names.get(0));
                else showChooseSubtitleFileSheet(names, files);
            });
        });
    }

    ParsedSubtitleMeta parseSubtitleCardMeta(String rawName) {
        String normalized = rawName == null ? "" : rawName.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 && slash < normalized.length() - 1 ? normalized.substring(slash + 1) : normalized;
        String ext = "";
        int dot = leaf.lastIndexOf('.');
        String stem = dot > 0 ? leaf.substring(0, dot) : leaf;
        if (dot > 0 && dot < leaf.length() - 1) {
            ext = leaf.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
        }
        String spaced = stem.replace('.', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();

        java.util.List<String> tags = new java.util.ArrayList<>();
        for (var pat : new java.util.regex.Pattern[]{
                java.util.regex.Pattern.compile("(?i)\\b(2160p|1440p|1080p|720p|480p|4K)\\b"),
                java.util.regex.Pattern.compile("(?i)\\b(BluRay|Blu-Ray|BDRip|WEB-?DL|WEBRip|HDRip|HDTV|DVDRip|BRRip)\\b"),
                java.util.regex.Pattern.compile("(?i)\\b(x265|HEVC|H\\.?265|x264|H\\.?264|AVC|AV1|10bit)\\b")
        }) {
            var m = pat.matcher(spaced);
            if (m.find()) {
                String val = m.group(1);
                if (!tags.contains(val)) tags.add(val);
            }
        }
        if (!ext.isEmpty()) tags.add(ext);

        String headline;
        var se = java.util.regex.Pattern.compile("(?i)(.*?)\\b(?:Season\\s*(\\d{1,2})[ ._-]*E(?:pisode)?[ ._-]*(\\d{1,3})|S(\\d{1,2})[ ._-]*E(\\d{1,3}))\\b").matcher(spaced);
        if (se.find()) {
            String show = cleanTitleForSubtitleSearch(se.group(1));
            int sNum = Integer.parseInt(se.group(2) != null ? se.group(2) : se.group(4));
            int eNum = Integer.parseInt(se.group(3) != null ? se.group(3) : se.group(5));
            String epTag = String.format(java.util.Locale.US, "S%02dE%02d", sNum, eNum);
            headline = show.isBlank() ? epTag : (show + " · " + epTag);
        } else {
            var epOnly = java.util.regex.Pattern.compile("(?i)(.*?)\\bE(?:pisode)?[ ._-]*(\\d{1,3})\\b").matcher(spaced);
            if (epOnly.find()) {
                String show = cleanTitleForSubtitleSearch(epOnly.group(1));
                int eNum = Integer.parseInt(epOnly.group(2));
                String epTag = String.format(java.util.Locale.US, "E%02d", eNum);
                headline = show.isBlank() ? epTag : (show + " · " + epTag);
            } else {
                String clean = cleanTitleForSubtitleSearch(spaced);
                headline = clean.isBlank() ? leaf : clean;
            }
        }
        return new ParsedSubtitleMeta(headline, tags, leaf);
    }

    void showChooseSubtitleFileSheet(java.util.List<String> names, java.util.List<byte[]> files) {
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(host);
        var root = host.column();
        root.setBackgroundColor(0xff111215);
        root.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(18));

        var grip = new View(host);
        var gripBg = new GradientDrawable();
        gripBg.setColor(0xff52525b);
        gripBg.setCornerRadius(host.dp(3));
        grip.setBackground(gripBg);
        var gripLp = new LinearLayout.LayoutParams(host.dp(38), host.dp(4));
        gripLp.gravity = Gravity.CENTER_HORIZONTAL;
        gripLp.bottomMargin = host.dp(10);
        root.addView(grip, gripLp);

        var titleTv = host.label(host.l("اختر ملف الترجمة", "Choose subtitle file") + " (" + names.size() + ")", 19);
        titleTv.setTextColor(0xfffafafa);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleTv.setPadding(0, 0, 0, host.dp(10));
        root.addView(titleTv);

        var recycler = new androidx.recyclerview.widget.RecyclerView(host);
        recycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(host));
        recycler.setNestedScrollingEnabled(true);
        recycler.setClipToPadding(false);
        recycler.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @androidx.annotation.NonNull @Override
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int viewType) {
                var cardView = new LinearLayout(host);
                cardView.setOrientation(LinearLayout.VERTICAL);
                cardView.setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12));
                cardView.setFocusable(true);
                cardView.setClickable(true);
                var bg = new GradientDrawable();
                bg.setColor(0xff18181b);
                bg.setCornerRadius(host.dp(14));
                bg.setStroke(host.dp(1), 0x88e5b84b);
                cardView.setBackground(bg);
                var lp = new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2);
                lp.bottomMargin = host.dp(8);
                cardView.setLayoutParams(lp);
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(cardView) {};
            }

            @Override
            public void onBindViewHolder(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {
                var cardView = (LinearLayout) holder.itemView;
                cardView.removeAllViews();
                ParsedSubtitleMeta meta = parseSubtitleCardMeta(names.get(position));

                var headTv = host.label(meta.headline(), 16);
                headTv.setTextColor(0xfffafafa);
                headTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                cardView.addView(headTv);

                if (!meta.tags().isEmpty()) {
                    var badgeRow = new LinearLayout(host);
                    badgeRow.setOrientation(LinearLayout.HORIZONTAL);
                    var brLp = new LinearLayout.LayoutParams(-1, -2);
                    brLp.topMargin = host.dp(6);
                    brLp.bottomMargin = host.dp(6);
                    for (int tIdx = 0; tIdx < meta.tags().size(); tIdx++) {
                        String tag = meta.tags().get(tIdx);
                        var pill = host.label(tag, 11);
                        boolean isFmt = tag.equals("SRT") || tag.equals("VTT") || tag.equals("ASS") || tag.equals("SSA");
                        pill.setTextColor(isFmt ? 0xff18181b : TvHubActivity.GOLD);
                        pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        var pBg = new GradientDrawable();
                        pBg.setColor(isFmt ? TvHubActivity.GOLD : 0xff26241d);
                        pBg.setCornerRadius(host.dp(8));
                        pill.setBackground(pBg);
                        pill.setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3));
                        var pLp = new LinearLayout.LayoutParams(-2, -2);
                        pLp.setMarginEnd(host.dp(6));
                        badgeRow.addView(pill, pLp);
                    }
                    cardView.addView(badgeRow, brLp);
                }

                var rawTv = host.label(meta.rawLeaf(), 12);
                rawTv.setTextColor(0xffa1a1aa);
                rawTv.setSingleLine(false);
                cardView.addView(rawTv);

                cardView.setOnClickListener(v -> {
                    dialog.dismiss();
                    sendSubtitle(files.get(position), names.get(position));
                });
            }

            @Override public int getItemCount() { return names.size(); }
        });

        int maxH = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.65f);
        root.addView(recycler, new LinearLayout.LayoutParams(-1, Math.min(host.dp(480), maxH)));
        dialog.setContentView(root);
        dialog.getBehavior().setDraggable(false);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    byte[] subtitleRequest(String path, String key, int limit) throws Exception {
        var conn = (javax.net.ssl.HttpsURLConnection) new java.net.URL(
                "https://api.subsource.net/api/v1" + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("X-API-Key", key);
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new java.io.IOException("SubSource HTTP " + status);
            try (var input = conn.getInputStream()) { return readLimited(input, limit); }
        } finally { conn.disconnect(); }
    }

    static byte[] readLimited(java.io.InputStream input, int limit) throws java.io.IOException {
        var output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new java.io.IOException("File too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    void sendSubtitle(byte[] bytes, String name) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            host.runOnUiThread(() -> sendSubtitle(bytes, name));
            return;
        }
        var targetSocket = (host.socket != null && host.socket.isOpen()) ? host.socket : CinemaRemoteBridge.getConnectedSocket();
        if (host.isDestroyed() || targetSocket == null || !targetSocket.isOpen()) {
            host.notifyStatus(host.l("الشاشة غير متصلة حالياً", "TV is not connected"));
            return;
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        if (!extension.matches("srt|ass|vtt")) { host.notifyStatus("Choose an SRT, ASS or VTT file"); return; }
        try {
            String text;
            try {
                text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException invalid) {
                text = new String(bytes, java.nio.charset.Charset.forName("windows-1256"));
            }
            byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (utf8.length > 1572864) { host.notifyStatus("Subtitle file is too large"); return; }
            var subPayload = new org.json.JSONObject().put("type", "SUBTITLE").put("extension", extension)
                    .put("data", android.util.Base64.encodeToString(utf8, android.util.Base64.NO_WRAP));
            if (host.activeCastId != null && !host.activeCastId.isEmpty()) subPayload.put("cast_id", host.activeCastId);
            host.safeSend(targetSocket, subPayload.toString());
            subtitlesTurnedOff = false;
            String memPath = PhoneVideoServer.registerInMemorySubtitle(text, "text/" + extension);
            String customUrl = memPath != null && !memPath.isBlank() ? "" : ("custom:" + name);
            String detectedLang = normalizeSubLanguage("", name);
            var customItem = new StreamSubtitleItem(
                    name, customUrl, memPath != null ? memPath : "", "text/" + extension,
                    detectedLang, false, "", "", "", "", "");
            var merged = new java.util.ArrayList<StreamSubtitleItem>();
            merged.add(customItem);
            merged.addAll(activeStreamSubtitles);
            activeStreamSubtitles.clear();
            activeStreamSubtitles.addAll(deduplicateStreamSubtitles(merged));
            selectedStreamPrimaryUrl = streamSubKey(customItem);
            refreshActiveStreamSubCard();
            host.notifyStatus(host.l("تم إرسال الترجمة إلى الشاشة ✓", "Subtitle sent to TV ✓"));
        } catch (org.json.JSONException error) {
            host.notifyStatus("Could not send subtitle to TV");
        }
    }
}
