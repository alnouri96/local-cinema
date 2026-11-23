package com.archos.mediacenter.video.leanback.cinema;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.meedx.providers.LiveChannelFavorites;
import com.meedx.providers.LiveTvPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CinemaLiveActivity extends FragmentActivity {
    static final String CUSTOM = "custom";
    private static final int GOLD = 0xffe5a93b;
    private static final String FAVORITE_COUNTRIES = LiveTvPreferences.FAVORITE_COUNTRIES_FIELD;

    private final java.util.concurrent.ExecutorService io = Executors.newFixedThreadPool(4);
    private final List<Future<?>> requests = new ArrayList<>();
    private final LinkedHashMap<String, CinemaLivePlaylists.Channel> channels = new LinkedHashMap<>();
    private final ChannelAdapter adapter = new ChannelAdapter();
    private boolean custom;
    private int generation;
    private int pending;
    private int failed;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean refreshingIraq;
    private CinemaLivePlaylists.IraqSnapshot iraqSnapshot;
    private Runnable updateIraqSourceControls;
    private final Runnable refreshWhileVisible = new Runnable() {
        @Override public void run() {
            refreshIraqSource(false);
            refreshHandler.postDelayed(this, CinemaLivePlaylists.IRAQ_REFRESH_INTERVAL);
        }
    };
    private String group = "";
    private String country = "";
    private boolean favoritesMode = true;
    private boolean focusOpeningChannel = true;
    private TextView countryButton;
    private TextView categoryButton;
    private ImageButton settings;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener liveSettingsChanged = (prefs, key) -> {
        if (isDestroyed() || isFinishing()) return;
        if (LiveChannelFavorites.FIELD.equals(key)) {
            filter();
            return;
        }
        if (!LiveTvPreferences.COUNTRY_FIELD.equals(key)
                && !LiveTvPreferences.FAVORITE_COUNTRIES_FIELD.equals(key)
                && !LiveTvPreferences.FAVORITES_MODE_FIELD.equals(key)) return;
        if (!custom) {
            country = LiveTvPreferences.country(this);
            favoritesMode = LiveTvPreferences.showFavoriteCountriesMode(this);
            countryButton.setText(countryButtonLabel());
        }
        focusOpeningChannel = true;
        loadChannels();
    };
    private final java.util.Set<String> scheduledLogos = new java.util.HashSet<>();
    private EditText search;
    private TextView status;
    private RecyclerView grid;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        custom = getIntent().getBooleanExtra(CUSTOM, false);
        group = CinemaLiveCategories.savedCategory(this, custom);
        if (!custom) {
            country = LiveTvPreferences.country(this);
            favoritesMode = LiveTvPreferences.showFavoriteCountriesMode(this);
            iraqSnapshot = CinemaLivePlaylists.cachedIraq(this);
        } else {
            favoritesMode = false;
        }
        var root = panel();
        root.setLayoutDirection(CinemaUi.arabic(this) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(0xff171717);
        root.setPaddingRelative(dp(36), dp(24), dp(36), dp(24));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        setContentView(root);

        var heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(heading);
        var title = CinemaUi.text(this, 30, true);
        title.setText(custom ? R.string.addon_iptv : R.string.addon_tv_live);
        title.setTextColor(GOLD);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        settings = new ImageButton(this);
        settings.setImageResource(R.drawable.cinema_addon_settings);
        settings.setContentDescription(getString(R.string.addon_live_sources));
        settings.setId(View.generateViewId());
        settings.setBackground(CinemaUi.rounded(0xff29241d, dp(14), 0xff594831));
        settings.setOnFocusChangeListener((view, focused) -> focusRing(view, focused));
        settings.setOnClickListener(v -> showSources());

        var toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setClipChildren(false);
        toolbar.setClipToPadding(false);
        var toolbarSize = new LinearLayout.LayoutParams(-1, -2);
        toolbarSize.topMargin = dp(12);
        root.addView(toolbar, toolbarSize);
        search = input(R.string.addon_channel_search, InputType.TYPE_CLASS_TEXT);
        search.setSingleLine();
        search.setId(View.generateViewId());
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        var searchSize = new LinearLayout.LayoutParams(dp(252), dp(48));
        searchSize.setMarginEnd(dp(10));
        toolbar.addView(search, searchSize);
        categoryButton = control(categoryButtonLabel(), () -> {});
        categoryButton.setOnClickListener(v -> showFilter(false, categoryButton));
        countryButton = control(countryButtonLabel(), () -> {});
        countryButton.setOnClickListener(v -> showCountryPicker(countryButton, false));
        toolbar.addView(categoryButton, toolbarItem());
        toolbar.addView(countryButton, toolbarItem());
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(search.getWindowToken(), 0);
            focusOpeningChannel = true;
            if (!focusFirstChannelIfNeeded() && !grid.requestFocus()) categoryButton.requestFocus();
            return true;
        });

        status = CinemaUi.text(this, 14, false);
        status.setTextColor(0xffddceae);
        status.setPaddingRelative(0, dp(10), 0, dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        grid = new RecyclerView(this);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setItemAnimator(null);
        grid.setPadding(dp(6), dp(10), dp(6), dp(14));
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = Math.max(2, Math.min(6, (width - 72) / 165));
        var layout = new GridLayoutManager(this, columns);
        layout.setInitialPrefetchItemCount(columns * 2);
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView recycler, int dx, int dy) {
                int last = layout.findLastVisibleItemPosition();
                if (last >= 0) prefetchLogos(last + 1, columns * 2);
            }
        });
        var viewport = new android.widget.FrameLayout(this);
        viewport.setClipChildren(true);
        viewport.setClipToPadding(true);
        viewport.addView(grid, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1f));
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LiveTvPreferences.store(this).registerOnSharedPreferenceChangeListener(liveSettingsChanged);
        loadChannels();
    }

    private List<String> activeCountryCodes() {
        if (custom) return List.of();
        if (favoritesMode) {
            var favs = new ArrayList<>(LiveTvPreferences.favoriteCountries(this));
            return favs.isEmpty() ? List.of(LiveTvPreferences.country(this)) : favs;
        }
        return List.of(country.isBlank() ? LiveTvPreferences.country(this) : country);
    }

    private boolean showCountryBadge() {
        return !custom && favoritesMode && activeCountryCodes().size() > 1;
    }

    private String countryButtonLabel() {
        if (custom) {
            return country.isEmpty() ? getString(R.string.addon_channel_countries) : displayCountry(country);
        }
        if (favoritesMode) {
            return getString(R.string.addon_live_favorite_countries);
        }
        return displayCountry(country);
    }

    private String categoryButtonLabel() {
        return group.isEmpty()
                ? getString(R.string.addon_channel_categories)
                : CinemaLiveCategories.displayName(this, group);
    }

    private void loadChannels() {
        int request = ++generation;
        for (Future<?> task : requests) task.cancel(true);
        requests.clear();
        channels.clear();
        scheduledLogos.clear();
        failed = 0;
        List<CinemaLivePlaylists.Source> sources = custom
                ? CinemaLivePlaylists.sources(this, true).stream().filter(CinemaLivePlaylists.Source::enabled).toList()
                : CinemaLivePlaylists.fetchSourcesForCountries(this, activeCountryCodes());
        pending = sources.size();
        refreshIraqSource(false);
        filter();
        for (var source : sources) {
            requests.add(io.submit(() -> {
                List<CinemaLivePlaylists.Channel> loaded;
                try {
                    loaded = CinemaLivePlaylists.load(source);
                } catch (Exception ignored) {
                    loaded = List.of();
                }
                var result = loaded;
                runOnUiThread(() -> {
                    if (request != generation || isFinishing() || isDestroyed()) return;
                    pending--;
                    if (result.isEmpty()) failed++;
                    for (var channel : result) channels.putIfAbsent(channel.url(), channel);
                    filter();
                });
            }));
        }
    }

    private boolean showIraqSource() {
        return !custom && activeCountryCodes().contains("IQ") && CinemaLivePlaylists.iraqEnabled(this);
    }

    private void refreshIraqSource(boolean force) {
        if (custom || refreshingIraq) return;
        if (!force) {
            if (!showIraqSource() || !CinemaLivePlaylists.iraqAutoRefresh(this)) return;
            long age = System.currentTimeMillis() - iraqSnapshot.attemptedAt();
            if (age >= 0 && age < CinemaLivePlaylists.IRAQ_REFRESH_INTERVAL) return;
        }
        refreshingIraq = true;
        if (updateIraqSourceControls != null) updateIraqSourceControls.run();
        io.execute(() -> {
            var snapshot = CinemaLivePlaylists.refreshIraq(this);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                iraqSnapshot = snapshot;
                refreshingIraq = false;
                if (updateIraqSourceControls != null) updateIraqSourceControls.run();
                if (showIraqSource()) filter();
            });
        });
    }

    private java.util.Collection<CinemaLivePlaylists.Channel> visibleSourceChannels() {
        if (!showIraqSource() || iraqSnapshot == null || iraqSnapshot.channels().isEmpty()) {
            return channels.values();
        }
        var combined = new LinkedHashMap<>(channels);
        for (var channel : iraqSnapshot.channels()) combined.putIfAbsent(channel.url(), channel);
        return combined.values();
    }

    private void filter() {
        String query = search.getText().toString().strip().toLowerCase(Locale.ROOT);
        var favorites = LiveChannelFavorites.active(this);
        var available = visibleSourceChannels();
        boolean loading = pending > 0 || (showIraqSource() && refreshingIraq);
        boolean unavailableSource = failed > 0 || (showIraqSource() && iraqSnapshot != null && iraqSnapshot.failed());

        // Gracefully fall back to All only after loading completes if saved category is absent in chosen country/set
        if (!group.isEmpty() && !loading && !available.isEmpty()) {
            boolean categoryPresent = available.stream().anyMatch(ch -> CinemaLiveCategories.matches(ch.group(), group));
            if (!categoryPresent) {
                group = CinemaLiveCategories.ALL;
                categoryButton.setText(categoryButtonLabel());
            }
        }

        var results = available.stream().filter(channel ->
                        CinemaLiveCategories.matches(channel.group(), group)
                                && (!custom || country.isEmpty()
                                        || List.of(channel.country().split("[,;\\s]+")).contains(country))
                                && (query.isEmpty() || channel.title().toLowerCase(Locale.ROOT).contains(query)))
                .sorted(java.util.Comparator.comparingLong(
                        (CinemaLivePlaylists.Channel channel) -> favorites.getOrDefault(channel.favoriteKey(), 0L)).reversed())
                .toList();
        adapter.favorites = favorites;
        adapter.showCountryTag = showCountryBadge();
        View currentFocus = getCurrentFocus();
        boolean toolbarFocused = currentFocus == search || currentFocus == categoryButton
                || currentFocus == countryButton || currentFocus == settings;
        boolean needFirstFocus = focusOpeningChannel || (!toolbarFocused && (currentFocus == null || currentFocus.getParent() == grid));
        adapter.items = results;
        adapter.notifyDataSetChanged();
        if (!results.isEmpty() && needFirstFocus) {
            focusOpeningChannel = true;
            focusFirstChannelIfNeeded();
        } else if (!loading && results.isEmpty() && focusOpeningChannel) {
            settings.requestFocus();
            focusOpeningChannel = false;
        }
        if (loading && results.isEmpty()) status.setText(R.string.addon_channels_loading);
        else if (unavailableSource && available.isEmpty()) status.setText(R.string.addon_channels_unavailable);
        else if (available.isEmpty()) status.setText(R.string.addon_live_no_sources);
        else if (results.isEmpty()) status.setText(R.string.addon_channels_no_match);
        else status.setText(getString(unavailableSource ? R.string.addon_channels_partial : R.string.addon_channels_count,
                results.size()));
    }

    private boolean focusFirstChannelIfNeeded() {
        if (grid == null || adapter.items.isEmpty()) return false;
        grid.scrollToPosition(0);
        Runnable attemptFocus = () -> {
            if (!focusOpeningChannel || isFinishing() || isDestroyed() || adapter.items.isEmpty()) return;
            View first = null;
            var lm = grid.getLayoutManager();
            if (lm != null) first = lm.findViewByPosition(0);
            if (first == null) {
                var holder = grid.findViewHolderForAdapterPosition(0);
                if (holder != null) first = holder.itemView;
            }
            if (first != null && first.isAttachedToWindow()) {
                if (first.requestFocus() || first.hasFocus()) {
                    focusOpeningChannel = false;
                }
            }
        };
        grid.post(attemptFocus);
        grid.postDelayed(attemptFocus, 120);
        return true;
    }

    private void showFilter(boolean byCountry, TextView trigger) {
        if (byCountry) {
            showCountryPicker(trigger, false);
            return;
        }
        var uniqueKeys = new LinkedHashSet<String>();
        for (var channel : visibleSourceChannels()) {
            uniqueKeys.addAll(CinemaLiveCategories.parseKeys(channel.group()));
        }
        var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        boolean hasUndefined = false;
        for (String key : uniqueKeys) {
            if (CinemaLiveCategories.UNDEFINED.equals(key)) {
                hasUndefined = true;
            } else {
                sorted.put(CinemaLiveCategories.displayName(this, key), key);
            }
        }
        var labels = new ArrayList<String>();
        var keys = new ArrayList<String>();
        labels.add(CinemaLiveCategories.displayName(this, CinemaLiveCategories.ALL));
        keys.add(CinemaLiveCategories.ALL);
        sorted.forEach((label, key) -> {
            labels.add(label);
            keys.add(key);
        });
        if (hasUndefined) {
            labels.add(CinemaLiveCategories.displayName(this, CinemaLiveCategories.UNDEFINED));
            keys.add(CinemaLiveCategories.UNDEFINED);
        }
        int checked = Math.max(0, keys.indexOf(group));
        CinemaUi.dialog(this).setTitle(R.string.addon_channel_categories)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, choice) -> {
                    group = keys.get(choice);
                    CinemaLiveCategories.saveCategory(this, custom, group);
                    trigger.setText(categoryButtonLabel());
                    focusOpeningChannel = true;
                    dialog.dismiss();
                    filter();
                }).setNegativeButton(R.string.addon_back, null).show();
    }

    private record CountryChoice(String code, String label, List<String> searchNames) {
        @Override public String toString() { return label; }
    }

    private static String normalizeCountrySearch(String value) {
        return java.text.Normalizer.normalize(value.strip().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("[\\p{M}ـ]", "");
    }

    private void showCountryPicker(TextView trigger, boolean editFavorites) {
        var favoriteCountries = new java.util.HashSet<>(
                LiveTvPreferences.favoriteCountries(this));
        var choices = new ArrayList<CountryChoice>();
        if (custom) {
            String all = getString(R.string.addon_live_filter_all);
            choices.add(new CountryChoice("", all, List.of(normalizeCountrySearch(all))));
        } else if (!editFavorites) {
            String favLabel = getString(R.string.addon_live_favorite_countries);
            choices.add(new CountryChoice(LiveTvPreferences.FAVORITES_MODE, favLabel, List.of(
                    normalizeCountrySearch(favLabel),
                    normalizeCountrySearch("Favorite Countries"),
                    normalizeCountrySearch("الدول المفضلة"),
                    normalizeCountrySearch("مفضلة"))));
        }
        var codes = new LinkedHashSet<String>();
        if (custom) {
            var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            for (var channel : visibleSourceChannels()) {
                for (String code : channel.country().split("[,;\\s]+")) {
                    if (!code.isBlank()) sorted.put(displayCountry(code), code);
                }
            }
            codes.addAll(sorted.values());
        } else {
            var ordered = List.of(LiveTvPreferences.countryCodes(this));
            if (!editFavorites && !favoritesMode && !country.isBlank()) codes.add(country);
            ordered.stream().filter(favoriteCountries::contains).forEach(codes::add);
            codes.addAll(ordered);
        }
        for (String code : codes) {
            var locale = new Locale("", code);
            String label = displayCountry(code);
            String arabic = normalizeCountrySearch(locale.getDisplayCountry(new Locale("ar")));
            var searchNames = new ArrayList<>(List.of(normalizeCountrySearch(label),
                    normalizeCountrySearch(locale.getDisplayCountry(Locale.ENGLISH)), arabic,
                    code.toLowerCase(Locale.ROOT)));
            if (arabic.startsWith("ال") && arabic.length() > 2) searchNames.add(arabic.substring(2));
            choices.add(new CountryChoice(code, label, searchNames));
        }

        var content = panel();
        content.setLayoutDirection(CinemaUi.arabic(this) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        content.setPaddingRelative(dp(16), dp(8), dp(16), dp(4));
        var countrySearch = input(R.string.addon_country_search, InputType.TYPE_CLASS_TEXT);
        countrySearch.setId(View.generateViewId());
        countrySearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(countrySearch, new LinearLayout.LayoutParams(-1, dp(48)));

        var list = new android.widget.ListView(this);
        list.setId(View.generateViewId());
        list.setChoiceMode(editFavorites ? android.widget.ListView.CHOICE_MODE_MULTIPLE
                : android.widget.ListView.CHOICE_MODE_SINGLE);
        list.setDivider(null);
        list.setDividerHeight(dp(4));
        list.setSelector(CinemaUi.rounded(0xff35281b, dp(12), GOLD));
        list.setNextFocusUpId(countrySearch.getId());
        countrySearch.setNextFocusDownId(list.getId());
        var filteredChoices = new ArrayList<CountryChoice>();
        var results = new android.widget.BaseAdapter() {
            @Override public int getCount() { return filteredChoices.size(); }
            @Override public CountryChoice getItem(int position) { return filteredChoices.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row;
                ImageView indicator;
                TextView labelView;
                if (recycled instanceof LinearLayout existing && existing.getChildCount() >= 2) {
                    row = existing;
                    indicator = (ImageView) row.getChildAt(0);
                    labelView = (TextView) row.getChildAt(1);
                } else {
                    row = new LinearLayout(CinemaLiveActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setMinimumHeight(dp(50));
                    row.setPaddingRelative(dp(18), dp(8), dp(18), dp(8));
                    indicator = new ImageView(CinemaLiveActivity.this);
                    indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    var indLp = new LinearLayout.LayoutParams(dp(22), dp(22));
                    indLp.setMarginEnd(dp(14));
                    row.addView(indicator, indLp);
                    labelView = CinemaUi.text(CinemaLiveActivity.this, 17, true);
                    labelView.setGravity(Gravity.CENTER_VERTICAL);
                    row.addView(labelView, new LinearLayout.LayoutParams(0, -2, 1f));
                }
                CountryChoice choice = getItem(position);
                boolean isFavEntry = LiveTvPreferences.FAVORITES_MODE.equals(choice.code());
                boolean checked = editFavorites
                        ? favoriteCountries.contains(choice.code())
                        : (custom ? choice.code().equals(country)
                                : (favoritesMode ? isFavEntry : choice.code().equals(country)));
                if (editFavorites) {
                    indicator.setImageDrawable(CinemaUi.createCinemaCheckDrawable(CinemaLiveActivity.this, checked));
                } else {
                    indicator.setImageDrawable(CinemaUi.createCinemaRadioDrawable(CinemaLiveActivity.this, checked));
                }
                labelView.setText(isFavEntry ? "★  " + choice.label() : choice.label());
                labelView.setTextColor(isFavEntry || checked ? GOLD : Color.WHITE);
                return row;
            }
        };
        list.setAdapter(results);
        var listSize = new LinearLayout.LayoutParams(-1, Math.min(dp(320), getResources().getDisplayMetrics().heightPixels / 2));
        listSize.topMargin = dp(8);
        var resultsArea = new android.widget.FrameLayout(this);
        content.addView(resultsArea, listSize);
        resultsArea.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        var empty = CinemaUi.text(this, 16, false);
        empty.setText(R.string.addon_country_no_match);
        empty.setGravity(Gravity.CENTER);
        resultsArea.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        Runnable filterChoices = () -> {
            String query = normalizeCountrySearch(countrySearch.getText().toString());
            filteredChoices.clear();
            for (CountryChoice choice : choices) {
                if (query.isEmpty() || choice.searchNames().stream()
                        .anyMatch(name -> query.length() == 1 ? name.startsWith(query) : name.contains(query))) {
                    filteredChoices.add(choice);
                }
            }
            list.clearChoices();
            results.notifyDataSetChanged();
            for (int i = 0; i < filteredChoices.size(); i++) {
                String code = filteredChoices.get(i).code();
                boolean selected = editFavorites
                        ? favoriteCountries.contains(code)
                        : (custom ? code.equals(country)
                                : (favoritesMode ? LiveTvPreferences.FAVORITES_MODE.equals(code) : code.equals(country)));
                if (selected) list.setItemChecked(i, true);
            }
            list.setSelection(0);
        };
        countrySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filterChoices.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        var keyboard = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        countrySearch.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
            if (results.getCount() > 0) {
                list.requestFocus();
                list.setSelection(0);
            }
            return true;
        });
        var builder = CinemaUi.dialog(this)
                .setTitle(editFavorites ? R.string.addon_live_favorite_countries : R.string.addon_channel_countries)
                .setView(content).setNegativeButton(R.string.addon_back, null);
        if (editFavorites) {
            builder.setPositiveButton(R.string.addon_save_apply, (dialog, button) -> {
                LiveTvPreferences.saveFavoriteCountries(this, favoriteCountries);
                CinemaLinkServer.broadcastLiveTvPreferences(this);
            });
        } else if (!custom) {
            builder.setPositiveButton(R.string.addon_edit_preferred_countries, (d, w) -> showCountryPicker(countryButton, true));
        }
        var dialog = builder.create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            CountryChoice choice = results.getItem(position);
            if (choice == null) return;
            if (editFavorites) {
                if (favoriteCountries.contains(choice.code())) favoriteCountries.remove(choice.code());
                else favoriteCountries.add(choice.code());
                results.notifyDataSetChanged();
                return;
            }
            keyboard.hideSoftInputFromWindow(countrySearch.getWindowToken(), 0);
            dialog.dismiss();
            if (custom) {
                country = choice.code();
                trigger.setText(countryButtonLabel());
                focusOpeningChannel = true;
                filter();
            } else if (LiveTvPreferences.FAVORITES_MODE.equals(choice.code())) {
                focusOpeningChannel = true;
                favoritesMode = true;
                LiveTvPreferences.setFavoriteCountriesMode(this, true);
                countryButton.setText(countryButtonLabel());
                loadChannels();
            } else {
                focusOpeningChannel = true;
                favoritesMode = false;
                country = choice.code();
                LiveTvPreferences.setFavoriteCountriesMode(this, false);
                LiveTvPreferences.saveCountry(this, choice.code());
                countryButton.setText(countryButtonLabel());
                CinemaLinkServer.broadcastLiveTvPreferences(this);
                loadChannels();
            }
        });
        filterChoices.run();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        CinemaUi.focusDialogControl(dialog, countrySearch);
    }

    private void showSources() {
        List<CinemaLivePlaylists.Source> sources = new ArrayList<>(CinemaLivePlaylists.sources(this, custom));
        var controls = panel();
        var dialog = CinemaUi.dialog(this).setTitle(R.string.addon_live_sources)
                .setView(scroll(controls)).setNegativeButton(R.string.addon_back, null).create();
        if (!custom) {
            addControl(controls, getString(R.string.addon_live_favorite_countries), () -> {
                dialog.dismiss();
                showCountryPicker(countryButton, true);
            });
            addControl(controls, getString(R.string.addon_live_iraq_source), this::showIraqSourceSettings);
        }
        if (custom) {
            addControl(controls, getString(R.string.addon_add_playlist), () -> { dialog.dismiss(); editSource(false); });
            addControl(controls, getString(R.string.addon_add_xtream), () -> { dialog.dismiss(); editSource(true); });
        }
        addControl(controls, getString(R.string.addon_reload_channels), () -> {
            dialog.dismiss();
            focusOpeningChannel = true;
            loadChannels();
        });
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i);
            int index = i;
            addControl(controls, getString(source.enabled() ? R.string.addon_live_source_on
                    : R.string.addon_live_source_off, source.name()), () -> {
                var settingsDialog = CinemaUi.dialog(this).setTitle(source.name())
                        .setPositiveButton(source.enabled() ? R.string.addon_live_disable : R.string.addon_live_enable,
                                (d, which) -> {
                                    sources.set(index, new CinemaLivePlaylists.Source(source.name(), source.url(),
                                            !source.enabled(), source.countryCode()));
                                    applySources(sources, dialog);
                                }).setNegativeButton(R.string.addon_back, null);
                if (custom && !CinemaLivePlaylists.builtIn(this, source.url())) {
                    settingsDialog.setNeutralButton(R.string.addon_live_remove, (d, which) -> {
                        sources.remove(index);
                        applySources(sources, dialog);
                    });
                }
                settingsDialog.show();
            });
        }
        dialog.show();
        CinemaUi.focusDialogControl(dialog, controls.getChildAt(0));
    }

    private void showIraqSourceSettings() {
        var controls = panel();
        var statusView = CinemaUi.text(this, 15, false);
        statusView.setPaddingRelative(dp(8), dp(6), dp(8), dp(12));
        controls.addView(statusView);
        addControl(controls, "", () -> {
            CinemaLivePlaylists.setIraqEnabled(this, !CinemaLivePlaylists.iraqEnabled(this));
            refreshIraqSource(false);
            loadChannels();
            updateIraqSourceControls.run();
        });
        addControl(controls, "", () -> {
            CinemaLivePlaylists.setIraqAutoRefresh(this, !CinemaLivePlaylists.iraqAutoRefresh(this));
            refreshIraqSource(false);
            updateIraqSourceControls.run();
        });
        addControl(controls, getString(R.string.addon_live_iraq_refresh_now), () -> refreshIraqSource(true));
        var sourceSwitch = (TextView) controls.getChildAt(1);
        var autoSwitch = (TextView) controls.getChildAt(2);
        var refresh = controls.getChildAt(3);
        updateIraqSourceControls = () -> {
            sourceSwitch.setText(CinemaLivePlaylists.iraqEnabled(this)
                    ? R.string.addon_live_iraq_source_on : R.string.addon_live_iraq_source_off);
            autoSwitch.setText(CinemaLivePlaylists.iraqAutoRefresh(this)
                    ? R.string.addon_live_iraq_auto_on : R.string.addon_live_iraq_auto_off);
            int statusText = refreshingIraq ? R.string.addon_live_iraq_refreshing
                    : iraqSnapshot.failed() ? R.string.addon_live_iraq_refresh_failed
                    : iraqSnapshot.updatedAt() > 0 ? R.string.addon_live_iraq_refresh_ready : R.string.addon_live_iraq_never;
            if (iraqSnapshot.updatedAt() > 0) {
                var date = new java.util.Date(iraqSnapshot.updatedAt());
                String stamp = android.text.format.DateFormat.getMediumDateFormat(this).format(date) + " · "
                        + android.text.format.DateFormat.getTimeFormat(this).format(date);
                statusView.setText(getString(R.string.addon_live_iraq_status, getString(statusText), stamp));
            } else {
                statusView.setText(statusText);
            }
            refresh.setAlpha(refreshingIraq ? 0.65f : 1f);
        };
        updateIraqSourceControls.run();
        var dialog = CinemaUi.dialog(this).setTitle(R.string.addon_live_iraq_source).setView(scroll(controls))
                .setNegativeButton(R.string.addon_back, null).show();
        dialog.setOnDismissListener(ignored -> updateIraqSourceControls = null);
        CinemaUi.focusDialogControl(dialog, sourceSwitch);
    }

    private void applySources(List<CinemaLivePlaylists.Source> sources, androidx.appcompat.app.AlertDialog dialog) {
        CinemaLivePlaylists.save(this, custom, sources);
        dialog.dismiss();
        focusOpeningChannel = true;
        loadChannels();
        showSources();
    }

    private void editSource(boolean xtream) {
        var fields = panel();
        fields.setPaddingRelative(dp(18), 0, dp(18), 0);
        var name = input(R.string.addon_playlist_name, InputType.TYPE_CLASS_TEXT);
        var url = input(xtream ? R.string.addon_xtream_server : R.string.addon_playlist_url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(name);
        fields.addView(url);
        var username = input(R.string.addon_xtream_user, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        var password = input(R.string.addon_xtream_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (xtream) {
            fields.addView(username);
            fields.addView(password);
        }
        var dialog = CinemaUi.dialog(this).setTitle(xtream ? R.string.addon_add_xtream : R.string.addon_add_playlist)
                .setView(scroll(fields)).setPositiveButton(R.string.addon_save_apply, null)
                .setNegativeButton(R.string.addon_back, null).show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String sourceUrl = xtream ? CinemaLivePlaylists.xtreamUrl(url.getText().toString().trim(),
                    username.getText().toString(), password.getText().toString())
                    : CinemaLivePlaylists.playlistUrl(url.getText().toString());
            String label = name.getText().toString().trim();
            if (sourceUrl.isBlank() || label.isBlank()) {
                Toast.makeText(this, R.string.addon_playlist_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            var sources = new ArrayList<>(CinemaLivePlaylists.sources(this, true));
            sources.removeIf(source -> source.url().equals(sourceUrl));
            sources.add(new CinemaLivePlaylists.Source(label, sourceUrl, true));
            CinemaLivePlaylists.save(this, true, sources);
            dialog.dismiss();
            focusOpeningChannel = true;
            loadChannels();
        });
        dialog.setOnDismissListener(d -> showSources());
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        name.requestFocus();
    }

    private boolean showFavoriteAction(CinemaLivePlaylists.Channel channel) {
        boolean favorite = adapter.favorites.containsKey(channel.favoriteKey());
        CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(this)
                .setTitle(channel.title())
                .setPositiveButton(favorite ? R.string.addon_favorite_remove : R.string.addon_favorite_add,
                        (dialog, which) -> {
                            LiveChannelFavorites.set(this, channel.favoriteKey(), !favorite);
                            CinemaLinkServer.broadcastFavoriteChannels(this);
                            focusChannel(channel.url());
                        })
                .setNegativeButton(R.string.addon_back, null), android.content.DialogInterface.BUTTON_POSITIVE);
        return true;
    }

    private void focusChannel(String url) {
        for (int i = 0; i < adapter.items.size(); i++) {
            if (!adapter.items.get(i).url().equals(url)) continue;
            int position = i;
            grid.scrollToPosition(position);
            var observer = grid.getViewTreeObserver();
            observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    var holder = grid.findViewHolderForAdapterPosition(position);
                    if (holder != null) holder.itemView.requestFocus();
                    return true;
                }
            });
            break;
        }
    }

    private void play(CinemaLivePlaylists.Channel channel) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(channel.url()), this, CinemaHlsActivity.class)
                .putExtra("format", channel.hls() ? "hls" : "adaptive_file")
                .putExtra("is_live_cast", true)
                .putExtra(CinemaHlsActivity.LIVE_CHANNEL, true)
                .putExtra(CinemaHlsActivity.CHANNEL_USER_AGENT, channel.userAgent())
                .putExtra(CinemaHlsActivity.CHANNEL_REFERER, channel.referer())
                .putExtra("title", channel.title()));
    }

    private String displayCountry(String code) {
        return LiveTvPreferences.countryName(this, code);
    }

    private com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> channelLogo(String url) {
        return Glide.with(this).load(url).override(dp(74), dp(47)).fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.ALL);
    }

    private void prefetchLogos(int start, int count) {
        for (int i = start; i < Math.min(start + count, adapter.items.size()); i++) {
            String url = adapter.items.get(i).logo();
            if (!url.isBlank() && scheduledLogos.add(url)) channelLogo(url).preload();
        }
    }

    private void focusRing(View view, boolean focused) {
        view.setForeground(focused ? CinemaUi.posterFocusRing(this, 3f) : null);
    }

    private void channelCardFocus(View view, boolean focused) {
        view.setForeground(focused ? CinemaUi.posterFocusRing(this, 3f) : null);
        view.setBackground(CinemaUi.rounded(focused ? 0xff362a19 : 0xff25211c, dp(12), focused ? GOLD : 0xff51422e));
        view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                .setDuration(CinemaUi.motion(this) ? 110 : 0).start();
    }

    private TextView control(String label, Runnable click) {
        var view = CinemaUi.text(this, 15, true);
        view.setId(View.generateViewId());
        view.setGravity(Gravity.CENTER);
        view.setBackground(CinemaUi.rounded(0xff29241d, dp(14), 0xff594831));
        view.setTextColor(Color.WHITE);
        view.setText(label);
        view.setPaddingRelative(dp(14), 0, dp(14), 0);
        view.setFocusable(true);
        view.setOnFocusChangeListener(this::focusRing);
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private void addControl(LinearLayout panel, String label, Runnable click) {
        var size = new LinearLayout.LayoutParams(-1, dp(48));
        size.bottomMargin = dp(5);
        panel.addView(control(label, click), size);
    }

    private LinearLayout.LayoutParams toolbarItem() {
        var size = new LinearLayout.LayoutParams(-2, dp(48));
        size.setMarginEnd(dp(8));
        return size;
    }

    private EditText input(int hint, int type) {
        var field = new EditText(this);
        field.setTextSize(16);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xffcbc2b2);
        field.setHint(hint);
        field.setInputType(type);
        field.setSingleLine();
        field.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
        field.setBackground(CinemaUi.rounded(0xff28231d, dp(12), 0xff665535));
        field.setOnFocusChangeListener(this::focusRing);
        return field;
    }

    private LinearLayout panel() {
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        return panel;
    }

    private View scroll(LinearLayout content) {
        var scroll = new android.widget.ScrollView(this);
        scroll.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(content);
        return scroll;
    }

    private int dp(int value) { return CinemaUi.dp(this, value); }

    @Override protected void onStart() {
        super.onStart();
        if (!custom) refreshHandler.post(refreshWhileVisible);
    }

    @Override protected void onStop() {
        refreshHandler.removeCallbacks(refreshWhileVisible);
        super.onStop();
    }

    @Override protected void onDestroy() {
        generation++;
        LiveTvPreferences.store(this).unregisterOnSharedPreferenceChangeListener(liveSettingsChanged);
        io.shutdownNow();
        super.onDestroy();
    }

    private final class ChannelAdapter extends RecyclerView.Adapter<ChannelHolder> {
        List<CinemaLivePlaylists.Channel> items = List.of();
        java.util.Map<String, Long> favorites = java.util.Map.of();
        boolean showCountryTag;

        @Override public int getItemCount() { return items.size(); }

        @Override public ChannelHolder onCreateViewHolder(ViewGroup parent, int type) {
            var tile = panel();
            tile.setBackground(CinemaUi.rounded(0xff25211c, dp(12), 0xff51422e));
            tile.setGravity(Gravity.CENTER);
            tile.setFocusable(true);
            tile.setOnFocusChangeListener(CinemaLiveActivity.this::channelCardFocus);
            tile.setOnKeyListener((v, key, event) -> CinemaUi.moveVerticalGridFocus(grid, v, event, null));
            tile.setPaddingRelative(dp(7), dp(5), dp(7), dp(5));
            var params = new RecyclerView.LayoutParams(-1, dp(130));
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            tile.setLayoutParams(params);
            var logo = new ImageView(CinemaLiveActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            var art = new android.widget.FrameLayout(CinemaLiveActivity.this);
            art.addView(logo, new android.widget.FrameLayout.LayoutParams(dp(74), dp(46), Gravity.CENTER));
            var star = CinemaUi.text(CinemaLiveActivity.this, 18, true);
            star.setText("★");
            star.setTextColor(GOLD);
            star.setGravity(Gravity.CENTER);
            star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            art.addView(star, new android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END));
            tile.addView(art, new LinearLayout.LayoutParams(-1, dp(46)));
            var title = CinemaUi.text(CinemaLiveActivity.this, 14, true);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextColor(Color.WHITE);
            tile.addView(title, new LinearLayout.LayoutParams(-1, -2));
            var countryTag = CinemaUi.text(CinemaLiveActivity.this, 11, false);
            countryTag.setGravity(Gravity.CENTER);
            countryTag.setSingleLine();
            countryTag.setEllipsize(android.text.TextUtils.TruncateAt.END);
            countryTag.setTextColor(0xffddbe7a);
            countryTag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(countryTag, new LinearLayout.LayoutParams(-1, -2));
            return new ChannelHolder(tile, logo, title, countryTag, star);
        }

        @Override public void onBindViewHolder(ChannelHolder holder, int position) {
            var channel = items.get(position);
            holder.title.setText(channel.title());
            boolean favorite = favorites.containsKey(channel.favoriteKey());
            holder.star.setVisibility(favorite ? View.VISIBLE : View.GONE);
            if (showCountryTag && channel.country() != null && !channel.country().isBlank()) {
                String code = channel.country().split("[,;\\s]+")[0].trim();
                holder.countryTag.setText(code.length() == 2 ? displayCountry(code) : code);
                holder.countryTag.setVisibility(View.VISIBLE);
            } else {
                holder.countryTag.setVisibility(View.GONE);
            }
            holder.itemView.setContentDescription(favorite ? getString(R.string.addon_favorite_channel_label, channel.title()) : null);
            if (channel.logo().isBlank()) {
                Glide.with(CinemaLiveActivity.this).clear(holder.logo);
                holder.logo.setImageResource(R.drawable.cinema_live_tv);
            } else {
                channelLogo(channel.logo()).placeholder(R.drawable.cinema_live_tv)
                        .error(R.drawable.cinema_live_tv).into(holder.logo);
            }
            holder.itemView.setOnClickListener(v -> play(channel));
            holder.itemView.setOnLongClickListener(v -> showFavoriteAction(channel));
            channelCardFocus(holder.itemView, holder.itemView.hasFocus());
        }

        @Override public void onViewAttachedToWindow(ChannelHolder holder) {
            super.onViewAttachedToWindow(holder);
            if (!focusOpeningChannel || holder.getBindingAdapterPosition() != 0) return;
            holder.itemView.post(() -> {
                if (focusOpeningChannel && holder.itemView.isAttachedToWindow()) {
                    if (holder.itemView.requestFocus()) {
                        focusOpeningChannel = false;
                    }
                }
            });
        }

        @Override public void onViewRecycled(ChannelHolder holder) {
            Glide.with(holder.logo.getContext().getApplicationContext()).clear(holder.logo);
            super.onViewRecycled(holder);
        }
    }

    private static final class ChannelHolder extends RecyclerView.ViewHolder {
        final ImageView logo;
        final TextView title;
        final TextView countryTag;
        final TextView star;
        ChannelHolder(View view, ImageView logo, TextView title, TextView countryTag, TextView star) {
            super(view);
            this.logo = logo;
            this.title = title;
            this.countryTag = countryTag;
            this.star = star;
        }
    }
}
