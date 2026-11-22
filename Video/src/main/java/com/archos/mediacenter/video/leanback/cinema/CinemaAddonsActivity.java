package com.archos.mediacenter.video.leanback.cinema;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ListView;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.meedx.providers.StremioAddons;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CinemaAddonsActivity extends FragmentActivity {
    private static final int GOLD = 0xffe5a93b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = Executors.newFixedThreadPool(2);
    private LinearLayout root;
    private TextView tooltip;
    private final Runnable hideTooltip = this::hideHelp;
    private boolean browsing;
    private boolean focusOpeningPoster;
    private EditText search;
    private RecyclerView grid;
    private int genreIndex, yearIndex, ratingIndex;
    private static final String[] GENRES = {"", "", "", "Action", "Adventure", "Animation", "Comedy", "Crime",
            "Drama", "Family", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller"};
    private static final int[] MOVIE_GENRES = {0,0,0,28,12,16,35,80,18,10751,14,27,9648,10749,878,53};
    private static final int[] TV_GENRES = {0,0,0,10759,10759,16,35,80,18,10751,10765,0,9648,0,10765,0};
    private static final int[] FIRST_YEARS = {1920,2026,2025,2024,2023,2020,2010,1920};
    private static final int[] LAST_YEARS = {9999,2026,2025,2024,2023,2022,2019,2009};
    private TextView status;
    private TextView moreStatus;
    private Posters posterAdapter;
    private StremioAddons.CatalogPager pager;
    private boolean loadingPage;
    private boolean hasMore;
    private boolean pageNeedsRetry;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroSummary;
    private ImageView backdrop;
    private View focusedTile;
    private Future<?> selectionWork;
    private int selectionGeneration;
    private StremioAddons.Poster requestedPoster;
    private final Runnable backdropWork = this::loadBackdrop;
    private StremioAddons.Poster featuredPoster;
    private int generation;
    private Future<?> catalogWork;
    private final Runnable searchWork = () -> loadCatalog();

    private final BroadcastReceiver syncChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (browsing) loadCatalog();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ContextCompat.registerReceiver(this, syncChanges, new IntentFilter(CinemaLinkServer.ACTION_ADDONS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (browsing) showHub();
                else finish();
            }
        });
        showHub();
    }

    private void shell(String title) {
        var layers = new FrameLayout(this);
        layers.setClipChildren(false);
        layers.setClipToPadding(false);
        layers.setBackgroundColor(0xff171717);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layers.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        var shade = new View(this);
        shade.setBackground(new GradientDrawable(
                getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xff171717, 0xf0171717, 0x99171717}));
        layers.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setPadding(dp(36), dp(24), dp(36), dp(24));
        layers.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layers);
        var header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        var heading = CinemaUi.text(this, 30, true);
        heading.setText(title);
        heading.setTextColor(GOLD);
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void addSettingsButton(LinearLayout filters) {
        filters.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        var gear = new android.widget.ImageButton(this);
        gear.setImageResource(R.drawable.cinema_addon_settings);
        gear.setContentDescription(getString(R.string.addon_manage));
        gear.setBackground(CinemaUi.rounded(0xff25211c, dp(16), 0xff524332));
        gear.setOnFocusChangeListener((v, focused) -> v.setForeground(focused ? CinemaUi.posterFocusRing(this, 2f) : null));
        gear.setOnClickListener(v -> showAddonSettings());
        filters.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(42)));
    }

    private void showHub() {
        if (backdrop != null) Picasso.get().cancelRequest(backdrop);
        handler.removeCallbacks(backdropWork);
        cancelSelection();
        clearPosterFocus();
        if (grid != null) grid.setAdapter(null);
        grid = null;
        featuredPoster = null;
        browsing = false;
        generation++;
        handler.removeCallbacks(searchWork);
        if (catalogWork != null) catalogWork.cancel(true);
        hideHelp();
        shell(getString(R.string.addon_hub_title));

        var cards = new LinearLayout(this);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        var row = new LinearLayout.LayoutParams(-1, -2);
        row.topMargin = dp(16);
        row.setMarginStart(dp(32));
        root.addView(cards, row);
        var movies = hubTile(cards, R.string.addon_tr_movies, R.string.addon_hub_description, 0, this::showGrid);
        hubTile(cards, R.string.addon_tv_live, R.string.addon_tv_live_description, R.drawable.cinema_live_tv,
                () -> openLive(false));
        hubTile(cards, R.string.addon_iptv, R.string.addon_iptv_description, R.drawable.cinema_live_playlist,
                () -> openLive(true));
        movies.requestFocus();
    }

    private void openLive(boolean custom) {
        startActivity(new Intent(this, CinemaLiveActivity.class).putExtra(CinemaLiveActivity.CUSTOM, custom));
    }

    private View hubTile(LinearLayout row, int label, int description, int icon, Runnable open) {
        var column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        var columnSize = new LinearLayout.LayoutParams(dp(158), -2);
        columnSize.setMarginEnd(dp(20));
        row.addView(column, columnSize);

        var card = new FrameLayout(this);
        card.setFocusable(true);
        card.setBackground(CinemaUi.rounded(0xff171717, dp(CinemaTokens.POSTER_RADIUS), 0xff665233));
        card.setClipToOutline(true);
        card.setContentDescription(getString(label) + ". " + getString(description));
        var cardSize = new LinearLayout.LayoutParams(dp(144), dp(216));
        cardSize.setMargins(dp(7), dp(8), dp(7), 0);
        column.addView(card, cardSize);
        var art = new ImageView(this);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icon == 0) {
            art.setScaleType(ImageView.ScaleType.FIT_XY);
            art.setImageResource(R.drawable.cinema_tr_movies_poster);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
            addPosterLogos(card);
        } else {
            var cover = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff63471f, 0xff2b2319, 0xff171717});
            cover.setCornerRadius(dp(CinemaTokens.POSTER_RADIUS));
            art.setImageResource(icon);
            art.setColorFilter(GOLD);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setPadding(dp(34), dp(31), dp(34), dp(61));
            art.setBackground(cover);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        }

        var title = CinemaUi.text(this, 19, true);
        title.setGravity(Gravity.CENTER);
        title.setText(label);
        title.setTextColor(Color.WHITE);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xd0262018, 0xff151414}));
        var titleSize = new FrameLayout.LayoutParams(-1, dp(47), Gravity.BOTTOM);
        titleSize.setMargins(dp(7), 0, dp(7), dp(8));
        card.addView(title, titleSize);
        card.setOnClickListener(v -> open.run());

        var help = CinemaUi.text(this, 14, false);
        help.setTextColor(Color.WHITE);
        help.setBackground(CinemaUi.rounded(0xff292218, dp(10), 0xff765928));
        help.setPaddingRelative(dp(10), dp(8), dp(10), dp(8));
        help.setText(description);
        help.setVisibility(View.INVISIBLE);
        var helpSize = new LinearLayout.LayoutParams(-1, -2);
        helpSize.topMargin = dp(10);
        column.addView(help, helpSize);
        card.setOnFocusChangeListener((view, focused) -> {
            applyPosterFocus(view, focused);
            if (focused) {
                hideHelp();
                tooltip = help;
                showHelp(getString(description));
            } else if (tooltip == help) {
                hideHelp();
            }
        });
        return card;
    }

    private void showHelp(String message) {
        hideHelp();
        if (message == null || message.isBlank()) return;
        tooltip.setText(message);
        tooltip.setTranslationY(-dp(8));
        tooltip.setAlpha(0f);
        tooltip.setVisibility(View.VISIBLE);
        tooltip.animate().translationY(0f).alpha(1f).setDuration(150).start();
        handler.postDelayed(hideTooltip, 3000);
    }

    private void hideHelp() {
        handler.removeCallbacks(hideTooltip);
        if (tooltip != null) {
            tooltip.animate().cancel();
            tooltip.setVisibility(View.INVISIBLE);
        }
    }

    private void showGrid() {
        hideHelp();
        browsing = true;
        focusOpeningPoster = true;
        tooltip = null;
        shell(getString(R.string.addon_tr_movies));
        search = new EditText(this);
        search.setSingleLine();
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setHint(R.string.addon_search_hint);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xffcac2b5);
        search.setTextSize(15);
        search.setPaddingRelative(dp(16), 0, dp(16), 0);
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(9));
        search.setBackground(CinemaUi.rounded(0xe824211d, dp(12), 0xff584a36));
        search.setOnFocusChangeListener((v, focus) ->
                v.setBackground(CinemaUi.rounded(0xe824211d, dp(12), focus ? GOLD : 0xff584a36)));
        var filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        var filterSize = new LinearLayout.LayoutParams(-1, dp(48));
        filterSize.topMargin = dp(6);
        root.addView(filters, filterSize);
        filters.addView(search, new LinearLayout.LayoutParams(dp(260), dp(42)));
        addFilter(filters, R.string.addon_genres, R.array.addon_genre_options, genreIndex, i -> genreIndex = i);
        addFilter(filters, R.string.addon_years, R.array.addon_year_options, yearIndex, i -> yearIndex = i);
        addFilter(filters, R.string.addon_rating, R.array.addon_rating_options, ratingIndex, i -> ratingIndex = i);
        addSettingsButton(filters);

        heroTitle = CinemaUi.text(this, 26, true);
        heroTitle.setMaxLines(2);
        heroTitle.setGravity(Gravity.BOTTOM);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        var heroSize = new LinearLayout.LayoutParams(-1, dp(58));
        heroSize.topMargin = dp(8);
        root.addView(heroTitle, heroSize);
        heroMeta = CinemaUi.text(this, 14, true);
        heroMeta.setTextColor(GOLD);
        root.addView(heroMeta, new LinearLayout.LayoutParams(-1, dp(20)));
        heroSummary = CinemaUi.text(this, 14, false);
        heroSummary.setMaxLines(2);
        heroSummary.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(heroSummary, new LinearLayout.LayoutParams(-1, dp(40)));
        status = CinemaUi.text(this, 15, false);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        grid = new RecyclerView(this);
        int gutter = dp(12);
        grid.setPadding(gutter, dp(6), gutter, dp(12));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setItemAnimator(null);
        int available = getResources().getDisplayMetrics().widthPixels - root.getPaddingStart()
                - root.getPaddingEnd() - 2 * gutter;
        var columns = new GridLayoutManager(this, posterColumns(available));
        grid.setLayoutManager(columns);
        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int count = posterColumns(right - left - 2 * gutter);
            if (columns.getSpanCount() != count) columns.setSpanCount(count);
        });
        search.setId(View.generateViewId());
        grid.setNextFocusUpId(search.getId());
        var viewport = new FrameLayout(this);
        viewport.setClipChildren(true);
        viewport.setClipToPadding(true);
        viewport.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        moreStatus = CinemaUi.text(this, 14, true);
        moreStatus.setGravity(Gravity.CENTER);
        moreStatus.setTextColor(GOLD);
        moreStatus.setVisibility(View.GONE);
        moreStatus.setId(View.generateViewId());
        moreStatus.setOnClickListener(v -> requestPage(true));
        moreStatus.setOnFocusChangeListener((view, focused) ->
                view.setBackground(focused ? CinemaUi.posterFocusRing(this, 2f) : null));
        root.addView(moreStatus, new LinearLayout.LayoutParams(-1, dp(36)));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                maybeRequestMore(columns.findLastVisibleItemPosition());
            }
        });

        search.setOnEditorActionListener((v, id, event) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            handler.removeCallbacks(searchWork);
            searchWork.run();
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(searchWork);
                handler.postDelayed(searchWork, 400);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        loadCatalog();
    }

    private void addPosterLogos(FrameLayout card) {
        var addons = StremioAddons.list(this).stream().filter(addon -> addon.enabled).limit(6)
                .collect(java.util.stream.Collectors.toList());
        var logos = new android.widget.GridLayout(this);
        logos.setColumnCount(3);
        var area = new FrameLayout.LayoutParams(dp(102), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        area.topMargin = dp(65);
        card.addView(logos, area);
        for (var addon : addons) {
            var icon = new ImageView(this);
            int fallback = StremioAddons.mayCatalog(addon) ? R.drawable.cinema_ic_posters : R.drawable.cinema_ic_addons;
            icon.setImageResource(fallback);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(dp(3), dp(3), dp(3), dp(3));
            icon.setBackground(CinemaUi.rounded(0xff25211d, dp(7), 0xff6f5332));
            var size = new android.widget.GridLayout.LayoutParams();
            size.width = dp(30);
            size.height = dp(30);
            size.setMargins(dp(2), dp(2), dp(2), dp(2));
            logos.addView(icon, size);
            io.submit(() -> {
                String url = StremioAddons.manifestLogo(addon);
                handler.post(() -> {
                    if (!url.isBlank() && !isDestroyed() && icon.isAttachedToWindow()) Picasso.get().load(url)
                            .resize(dp(26), dp(26)).centerInside().placeholder(fallback)
                            .error(fallback).into(icon);
                });
            });
        }
    }

    private void addFilter(LinearLayout bar, int title, int choices, int initial, java.util.function.IntConsumer select) {
        var button = CinemaUi.text(this, 15, true);
        String[] labels = getResources().getStringArray(choices);
        int[] selected = {initial};
        button.setText(getString(title) + " ▾");
        button.setGravity(Gravity.CENTER);
        button.setPaddingRelative(dp(12), 0, dp(12), 0);
        button.setFocusable(true);
        Runnable paint = () -> button.setBackground(CinemaUi.rounded(0xff25211c, dp(18),
                button.hasFocus() || selected[0] != 0 ? GOLD : 0xff584a36));
        paint.run();
        button.setOnFocusChangeListener((v, focused) -> paint.run());
        button.setOnClickListener(v -> {
            var popup = new android.widget.ListPopupWindow(this);
            popup.setAnchorView(button);
            popup.setModal(true);
            popup.setWidth(dp(225));
            popup.setBackgroundDrawable(CinemaUi.rounded(0xff171717, dp(12), GOLD));
            popup.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
                @Override public View getView(int position, View recycled, ViewGroup parent) {
                    var row = CinemaUi.text(CinemaAddonsActivity.this, 15, true);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPaddingRelative(dp(14), 0, dp(12), 0);
                    row.setMinHeight(dp(40));
                    row.setText((position == selected[0] ? "✓  " : "") + getItem(position));
                    row.setTextColor(position == selected[0] ? GOLD : 0xfff1e8d8);
                    return row;
                }
            });
            popup.setOnItemClickListener((parent, view, i, id) -> {
                popup.dismiss();
                selected[0] = i;
                select.accept(i);
                paint.run();
                handler.removeCallbacks(searchWork);
                loadCatalog();
            });
            popup.show();
            popup.getListView().setSelector(CinemaUi.posterFocusRing(this, 2f));
            popup.setSelection(selected[0]);
        });
        var size = new LinearLayout.LayoutParams(-2, dp(42));
        size.setMarginStart(dp(7));
        bar.addView(button, size);
    }

    private void showAddonSettings() {
        new CinemaAddonSettings(this, io, handler, () -> {
            if (browsing) loadCatalog();
        }).show();
    }

    private void loadCatalog() {
        if (!browsing) return;
        generation++;
        if (catalogWork != null) catalogWork.cancel(true);
        cancelSelection();
        clearPosterFocus();
        updateHero(null);

        posterAdapter = new Posters();
        grid.setAdapter(posterAdapter);
        pager = null;
        loadingPage = false;
        hasMore = true;
        pageNeedsRetry = false;
        requestPage(false);
    }

    private void maybeRequestMore(int position) {
        if (!browsing || posterAdapter == null || position < 0 || pageNeedsRetry) return;
        int columns = ((GridLayoutManager) grid.getLayoutManager()).getSpanCount();
        if (position >= posterAdapter.getItemCount() - 2 * columns) requestPage(false);
    }

    private void requestPage(boolean retry) {
        if (!browsing || loadingPage || !hasMore || (pageNeedsRetry && !retry)) return;
        loadingPage = true;
        pageNeedsRetry = false;
        boolean first = posterAdapter.getItemCount() == 0;
        if (first) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.addon_loading);
            moreStatus.setVisibility(View.GONE);
        } else {
            showMoreStatus(R.string.addon_loading_more, false);
        }

        int request = generation;
        Context app = getApplicationContext();
        String query = search.getText().toString().trim();
        StremioAddons.CatalogPager previous = pager;
        var filter = new StremioAddons.CatalogFilter(genreIndex == 1 ? "movie" : genreIndex == 2 ? "series" : "",
                GENRES[genreIndex], MOVIE_GENRES[genreIndex], TV_GENRES[genreIndex], FIRST_YEARS[yearIndex],
                LAST_YEARS[yearIndex], ratingIndex >= 2 ? 11 - ratingIndex : 0, ratingIndex == 1);
        catalogWork = io.submit(() -> {
            boolean enabled = previous != null || StremioAddons.list(app).stream()
                    .anyMatch(addon -> addon.enabled && (StremioAddons.mayStream(addon) || StremioAddons.mayCatalog(addon)));
            var current = enabled && previous == null ? new StremioAddons.CatalogPager(app, query, filter) : previous;
            var page = current == null ? new StremioAddons.CatalogPage(List.of(), false, false) : current.next();
            handler.post(() -> {
                if (!browsing || isFinishing() || isDestroyed() || generation != request) return;
                loadingPage = false;
                pager = current;
                hasMore = page.hasMore();
                pageNeedsRetry = page.retry() || (page.posters().isEmpty() && hasMore);
                posterAdapter.append(page.posters());

                boolean empty = posterAdapter.getItemCount() == 0;
                status.setVisibility(empty && !pageNeedsRetry ? View.VISIBLE : View.GONE);
                if (empty && !pageNeedsRetry) status.setText(enabled ? R.string.addon_no_titles : R.string.addon_empty);
                if (pageNeedsRetry) showMoreStatus(R.string.addon_retry_titles, true);
                else moreStatus.setVisibility(View.GONE);
                if (first && !empty && featuredPoster == null) updateHero(posterAdapter.items.get(0));
            });
        });
    }

    private void showMoreStatus(int label, boolean retry) {
        moreStatus.setText(label);
        moreStatus.setClickable(retry);
        moreStatus.setFocusable(retry);
        moreStatus.setVisibility(View.VISIBLE);
        grid.setNextFocusDownId(retry ? moreStatus.getId() : View.NO_ID);
    }

    private int posterColumns(int availableWidth) {
        return Math.max(1, Math.min(6, availableWidth / dp(128)));
    }

    private void clearPosterFocus() {
        if (focusedTile != null) applyPosterFocus(focusedTile, false);
        focusedTile = null;
    }

    private void onPosterFocus(View tile, StremioAddons.Poster poster, boolean focused) {
        if (!focused || poster == null || !tile.isAttachedToWindow()) {
            applyPosterFocus(tile, false);
            if (focusedTile == tile) focusedTile = null;
            return;
        }
        if (focusedTile != tile) clearPosterFocus();
        focusedTile = tile;
        applyPosterFocus(tile, true);
        if (requestedPoster != null && poster != requestedPoster) cancelSelection();
        updateHero(poster);
        var holder = grid == null ? null : grid.findContainingViewHolder(tile);
        if (holder != null) maybeRequestMore(holder.getBindingAdapterPosition());
    }

    private void applyPosterFocus(View tile, boolean focused) {
        tile.animate().cancel();
        tile.setForeground(focused ? CinemaUi.posterFocusRing(this, 3f) : null);
        tile.setElevation(focused ? dp(3) : 0f);
        tile.setTranslationZ(0f);
        tile.setScaleX(focused ? 1.04f : 1f);
        tile.setScaleY(focused ? 1.04f : 1f);
    }

    private String posterMetadata(StremioAddons.Poster poster) {
        String rating = poster.rating() > 0 ? getString(R.string.addon_poster_rating, poster.rating()) : "";
        return poster.year().isBlank() ? rating : rating.isBlank() ? poster.year() : poster.year() + " · " + rating;
    }

    private void updateHero(StremioAddons.Poster poster) {
        heroTitle.setText(poster == null ? "" : poster.title());
        heroMeta.setText(poster == null ? "" : posterMetadata(poster));
        heroSummary.setText(poster == null ? "" : poster.description());
        if (featuredPoster == poster) return;
        featuredPoster = poster;
        handler.removeCallbacks(backdropWork);
        Picasso.get().cancelRequest(backdrop);
        handler.postDelayed(backdropWork, 160);
    }

    private void loadBackdrop() {
        if (!browsing || isFinishing() || isDestroyed()) return;
        if (featuredPoster == null) {
            backdrop.setImageDrawable(null);
            return;
        }
        String image = featuredPoster.backdrop().isBlank() ? featuredPoster.image() : featuredPoster.backdrop();
        if (isArtworkUrl(image)) Picasso.get().load(image).fit().centerCrop().noPlaceholder().into(backdrop);
        else backdrop.setImageDrawable(null);
    }

    private static boolean isArtworkUrl(String image) {
        return image.startsWith("https://") || image.startsWith("http://");
    }

    private void cancelSelection() {
        selectionGeneration++;
        requestedPoster = null;
        if (selectionWork != null) selectionWork.cancel(true);
    }

    private boolean selectionActive(int request) {
        return browsing && request == selectionGeneration && !isFinishing() && !isDestroyed();
    }

    private void showPosterActions(StremioAddons.Poster poster) {
        CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(this)
                .setTitle(poster.title())
                .setPositiveButton(R.string.addon_watch_now, (dialog, which) -> selectPoster(null, poster))
                .setNegativeButton(R.string.addon_watch_trailer, (dialog, which) -> openTrailer(poster)),
                android.content.DialogInterface.BUTTON_POSITIVE);
    }

    private void openTrailer(StremioAddons.Poster poster) {
        int year = 0;
        try { year = Integer.parseInt(poster.year()); } catch (NumberFormatException ignored) {}
        int releaseYear = year;
        cancelSelection();
        int request = selectionGeneration;
        selectionWork = CinemaAddonPicker.load(this, io, handler, R.string.addon_trailer_loading, () ->
                com.meedx.providers.OpenTrailersProvider.resolveTrailerUrl(getApplicationContext(),
                        poster.title(), releaseYear, poster.imdb(), "series".equals(poster.type())), url -> {
            if (selectionActive(request)) CinemaYouTubeLauncher.openUriOrSearch(this,
                    url == null || url.isBlank() ? null : android.net.Uri.parse(url), poster.title(), releaseYear);
        });
    }

    private void selectPoster(StremioAddons.Addon addon, StremioAddons.Poster poster) {
        cancelSelection();
        requestedPoster = poster;
        int request = selectionGeneration;
        if ("movie".equals(poster.type())) {
            selectionWork = CinemaAddonPicker.request(this, io, handler,
                    () -> new CinemaAddonPicker.Selection(poster.title(), StremioAddons.streamId(this, poster),
                            tmdbId(poster), poster.type(), 0, 0, addon, poster.image(), poster.title()), () -> selectionActive(request));
            return;
        }
        record Episodes(String id, List<StremioAddons.EpisodeChoice> rows) {}
        selectionWork = CinemaAddonPicker.load(this, io, handler, R.string.addon_loading_episodes, () -> {
            String id = StremioAddons.streamId(this, poster);
            return id.isBlank() ? null : new Episodes(id, StremioAddons.episodes(addon, id));
        }, result -> {
            if (!selectionActive(request)) return;
            if (result == null) Toast.makeText(this, R.string.addon_missing_id, Toast.LENGTH_LONG).show();
            else if (result.rows().isEmpty()) enterEpisode(addon, poster, result.id());
            else showEpisodes(addon, poster, result.id(), result.rows());
        });
    }

    private void showEpisodes(StremioAddons.Addon addon, StremioAddons.Poster poster, String id,
                              List<StremioAddons.EpisodeChoice> episodes) {
        var seasons = new TreeMap<Integer, List<StremioAddons.EpisodeChoice>>();
        for (var episode : episodes) {
            seasons.computeIfAbsent(episode.season(), season -> new ArrayList<>()).add(episode);
        }
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
        var list = new ListView(this);
        list.setId(View.generateViewId());
        list.setDivider(null);
        list.setDividerHeight(dp(5));
        list.setSelector(CinemaUi.posterFocusRing(this, 2f));
        list.setDrawSelectorOnTop(true);

        var strip = new LinearLayout(this);
        if (seasons.size() > 1) {
            var scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(strip, new FrameLayout.LayoutParams(-2, -2));
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(54)));
        }
        int height = Math.min(310, getResources().getConfiguration().screenHeightDp / 2);
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(height)));

        var dialog = CinemaUi.dialog(this).setTitle(poster.title()).setView(panel)
                .setNegativeButton(android.R.string.cancel, null).create();
        int openingSeason = seasons.firstKey();
        var tabs = new TreeMap<Integer, TextView>();
        java.util.function.IntConsumer selectSeason = season -> {
            tabs.forEach((number, button) -> button.setSelected(number == season));
            var rows = seasons.get(season);
            var labels = rows.stream().map(this::episodeLabel).toArray(String[]::new);
            list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
                @Override public View getView(int position, View recycled, ViewGroup parent) {
                    TextView label = recycled instanceof TextView old ? old : CinemaUi.text(CinemaAddonsActivity.this, 16, true);
                    label.setGravity(Gravity.CENTER_VERTICAL);
                    label.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
                    label.setMinHeight(dp(45));
                    label.setMaxLines(2);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    label.setBackground(CinemaUi.rounded(0xff22201d, dp(8), 0xff43392b));
                    label.setText(getItem(position));
                    return label;
                }
            });
            list.setSelection(0);
            list.setOnItemClickListener((parent, selected, index, rowId) -> {
                var episode = rows.get(index);
                dialog.dismiss();
                resolveEpisode(addon, poster, id, episode.season(), episode.episode(), episode.title());
            });
            if (tabs.containsKey(season)) list.setNextFocusUpId(tabs.get(season).getId());
        };

        if (seasons.size() > 1) {
            for (int number : seasons.keySet()) {
                var tab = CinemaUi.text(this, 15, true);
                tab.setText(getString(R.string.addon_season_tab, number));
                tab.setId(View.generateViewId());
                tab.setGravity(Gravity.CENTER);
                tab.setPaddingRelative(dp(13), 0, dp(13), 0);
                tab.setFocusable(true);
                tab.setNextFocusDownId(list.getId());
                var states = new android.graphics.drawable.StateListDrawable();
                states.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected},
                        CinemaUi.rounded(0xff604519, dp(8), GOLD));
                states.addState(new int[]{android.R.attr.state_focused}, CinemaUi.rounded(0xff31291b, dp(8), GOLD));
                states.addState(new int[]{android.R.attr.state_selected}, CinemaUi.rounded(0xff513a17, dp(8), 0xff9e7735));
                states.addState(new int[]{}, CinemaUi.rounded(0xff26221b, dp(8), 0xff43392b));
                tab.setBackground(states);
                tab.setOnClickListener(v -> selectSeason.accept(number));
                var size = new LinearLayout.LayoutParams(-2, dp(44));
                size.setMarginEnd(dp(8));
                strip.addView(tab, size);
                tabs.put(number, tab);
            }
        }
        selectSeason.accept(openingSeason);
        dialog.show();
        View focus = tabs.containsKey(openingSeason) ? tabs.get(openingSeason) : list;
        focus.requestFocus();
    }

    private String episodeLabel(StremioAddons.EpisodeChoice episode) {
        if (episode.title().isBlank()) {
            return getString(R.string.addon_episode_numbered, episode.season(), episode.episode());
        }
        String title = androidx.core.text.BidiFormatter.getInstance(CinemaUi.arabic(this)).unicodeWrap(episode.title());
        return getString(R.string.addon_episode_label, episode.season(), episode.episode(), title);
    }

    private void enterEpisode(StremioAddons.Addon addon, StremioAddons.Poster poster, String id) {
        var form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        var season = numberInput(R.string.addon_season);
        var episode = numberInput(R.string.addon_episode);
        form.addView(season);
        form.addView(episode);
        CinemaUi.dialog(this).setTitle(poster.title()).setView(form)
                .setPositiveButton(R.string.addon_streams, (d, which) -> {
                    try {
                        int s = Integer.parseInt(season.getText().toString());
                        int e = Integer.parseInt(episode.getText().toString());
                        if (s >= 1 && e >= 1) resolveEpisode(addon, poster, id, s, e, "");
                    } catch (NumberFormatException ignored) {}
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private EditText numberInput(int hint) {
        var field = new EditText(this);
        field.setHint(hint);
        field.setText("1");
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    private void resolveEpisode(StremioAddons.Addon addon, StremioAddons.Poster poster, String id, int season, int episode, String episodeTitle) {
        String cleanTitle = CinemaHomeFragment.stripGenericEpisodeTokens(episodeTitle);
        int request = selectionGeneration;
        selectionWork = CinemaAddonPicker.request(this, io, handler,
                () -> new CinemaAddonPicker.Selection(cleanTitle.isBlank() ? getString(R.string.addon_episode_numbered, season, episode) : cleanTitle,
                        id, tmdbId(poster), poster.type(), season, episode, addon, poster.image(), poster.title()), () -> selectionActive(request));
    }

    private static long tmdbId(StremioAddons.Poster poster) {
        return poster.id().matches("tmdb:[0-9]{1,12}") ? Long.parseLong(poster.id().substring(5)) : 0;
    }

    private int dp(int n) { return CinemaUi.dp(this, n); }

    private final class Posters extends RecyclerView.Adapter<PosterHolder> {
        private final List<StremioAddons.Poster> items = new ArrayList<>();

        void append(List<StremioAddons.Poster> added) {
            if (added.isEmpty()) return;
            int start = items.size();
            items.addAll(added);
            notifyItemRangeInserted(start, added.size());
        }

        @Override public void onViewAttachedToWindow(PosterHolder holder) {
            super.onViewAttachedToWindow(holder);
            if (holder.tile.hasFocus()) onPosterFocus(holder.tile, holder.poster, true);
            if (!focusOpeningPoster || holder.getBindingAdapterPosition() != 0) return;
            holder.itemView.post(() -> {
                if (browsing && focusOpeningPoster && holder.getBindingAdapter() == posterAdapter
                        && holder.itemView.isAttachedToWindow()) {
                    focusOpeningPoster = !holder.tile.requestFocusFromTouch();
                }
            });
        }

        @Override public int getItemCount() { return items.size(); }

        @Override public PosterHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new PosterHolder();
        }

        @Override public void onBindViewHolder(PosterHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override public void onViewDetachedFromWindow(PosterHolder holder) {
            onPosterFocus(holder.tile, null, false);
        }

        @Override public void onViewRecycled(PosterHolder holder) {
            onPosterFocus(holder.tile, null, false);
            Picasso.get().cancelRequest(holder.image);
            holder.poster = null;
        }

    }

    private final class PosterHolder extends RecyclerView.ViewHolder {
        private final LinearLayout tile;
        private final ImageView image;
        private final TextView title;
        private final TextView metadata;
        private StremioAddons.Poster poster;

        PosterHolder() {
            super(new FrameLayout(CinemaAddonsActivity.this));
            var cell = (FrameLayout) itemView;
            cell.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));
            cell.setClipToPadding(false);
            cell.setClipChildren(false);
            tile = new LinearLayout(CinemaAddonsActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setFocusable(true);
            tile.setBackground(CinemaUi.rounded(0xff23211e, dp(CinemaTokens.POSTER_RADIUS), 0xff42392a));
            tile.setClipToOutline(true);
            cell.addView(tile, new FrameLayout.LayoutParams(-1, -2));

            image = new ImageView(CinemaAddonsActivity.this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = View.MeasureSpec.getSize(widthSpec);
                    setMeasuredDimension(width, Math.round(width * 1.5f));
                }
            };
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new LinearLayout.LayoutParams(-1, -2));

            title = CinemaUi.text(CinemaAddonsActivity.this, 14, true);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPaddingRelative(dp(4), 0, dp(4), 0);
            tile.addView(title, new LinearLayout.LayoutParams(-1, dp(39)));

            metadata = CinemaUi.text(CinemaAddonsActivity.this, 12, false);
            metadata.setGravity(Gravity.CENTER);
            metadata.setSingleLine();
            metadata.setTextColor(GOLD);
            tile.addView(metadata, new LinearLayout.LayoutParams(-1, dp(21)));

            tile.setOnFocusChangeListener((v, focused) -> onPosterFocus(v, poster, focused));
            tile.setOnKeyListener((v, key, event) -> CinemaUi.moveVerticalGridFocus(grid, v, event, moreStatus));
            tile.setOnClickListener(v -> {
                if (poster != null && getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                    showPosterActions(poster);
                }
            });
        }

        void bind(StremioAddons.Poster item) {
            onPosterFocus(tile, null, false);
            poster = item;
            Picasso.get().cancelRequest(image);
            image.setImageResource(R.drawable.cinema_addons);
            if (isArtworkUrl(poster.image())) {
                Picasso.get().load(poster.image()).fit().centerCrop().placeholder(R.drawable.cinema_addons).into(image);
            }
            title.setText(poster.title());
            metadata.setText(posterMetadata(poster));
            tile.setContentDescription(poster.title() + (poster.description().isBlank() ? "" : " · " + poster.description()));
            if (tile.hasFocus()) onPosterFocus(tile, poster, true);
        }
    }

    @Override protected void onDestroy() {
        clearPosterFocus();
        if (grid != null) grid.setAdapter(null);
        cancelSelection();
        if (backdrop != null) Picasso.get().cancelRequest(backdrop);
        unregisterReceiver(syncChanges);
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
