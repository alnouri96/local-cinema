package com.archos.mediacenter.video.leanback.cinema;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.squareup.picasso.Picasso;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Modern dark glassmorphic YouTube 4K Ambient Screensaver browser & loop player.
 * Combines preset categories in a single dropdown, compact top-bar search, favorites,
 * and unclipped gold D-pad focus rings.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class CinemaAmbientYoutubeActivity extends LeanbackActivity {
    private static final String[] SMART_TUBE = {"org.smarttube.stable", "org.smarttube.beta",
            "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv2"};
    private static final int[] CATEGORIES = {R.string.cinema_ambient_fire, R.string.cinema_ambient_rain,
            R.string.cinema_ambient_beach, R.string.cinema_ambient_forest, R.string.cinema_ambient_aquarium,
            R.string.cinema_ambient_space};
    private static final String[] QUERIES = {"4K fireplace logs ambience", "4K night rain ambience",
            "4K relaxing ocean beach ambience", "4K forest nature ambience", "4K aquarium ambience", "4K space ambience"};

    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(2);
    private final LinkedHashMap<String, AmbientYoutubeFetcher.Video> favorites = new LinkedHashMap<>();
    private List<AmbientYoutubeFetcher.Video> items = List.of();
    private List<AmbientYoutubeFetcher.Source> sources = List.of();
    private SharedPreferences prefs;
    private FrameLayout root, playback;
    private LinearLayout browser;
    private EditText query;
    private TextView categoryBtn, favoritesBtn, status, playerStatus;
    private RecyclerView grid;
    private PlayerView playerView;
    private ExoPlayer player;
    private View previousFocus;
    private Future<?> pending;
    private int generation, sourceIndex, preferredHeight;
    private int selectedCategoryIndex = 0;
    private boolean showingFavorites;
    private String recentQuery;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("cinema_ambient_youtube", MODE_PRIVATE);
        loadFavorites();

        boolean rtl = CinemaUi.arabic(this);
        root = new FrameLayout(this);
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CinemaTokens.CANVAS);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // Subtle dark ambient vignette background
        View vignette = new View(this);
        vignette.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1F1C17, CinemaTokens.CANVAS, 0xFF121212}));
        root.addView(vignette, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        browser = page;
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        int gutter = dp(34);
        page.setPaddingRelative(gutter, dp(20), gutter, dp(16));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // Header Row: Title + Compact Controls on One Sleek Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setClipChildren(false);
        topBar.setClipToPadding(false);
        topBar.setPadding(0, dp(4), 0, dp(10));
        page.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = CinemaUi.text(this, 26, true);
        title.setTextColor(CinemaTokens.GOLD);
        title.setText(R.string.cinema_ambient_youtube);
        title.setAccessibilityHeading(true);
        titleCol.addView(title);
        TextView subtitle = CinemaUi.text(this, 12, false);
        subtitle.setTextColor(0xFFB5ADA0);
        subtitle.setText(R.string.cinema_ambient_subtitle_hint);
        titleCol.addView(subtitle);
        topBar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        // 1) Single Categories Dropdown Pill
        categoryBtn = createToolbarPill("", this::showCategoryDropdown);
        updateCategoryButtonLabel();
        var catLp = new LinearLayout.LayoutParams(-2, dp(42));
        catLp.setMarginStart(dp(10));
        topBar.addView(categoryBtn, catLp);

        // 2) Compact Modern Search Pill + Icon Button
        LinearLayout searchWrap = new LinearLayout(this);
        searchWrap.setOrientation(LinearLayout.HORIZONTAL);
        searchWrap.setGravity(Gravity.CENTER_VERTICAL);
        searchWrap.setClipChildren(false);
        searchWrap.setClipToPadding(false);
        var searchWrapLp = new LinearLayout.LayoutParams(-2, dp(42));
        searchWrapLp.setMarginStart(dp(8));
        topBar.addView(searchWrap, searchWrapLp);

        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextSize(14);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(0xFFB5ADA0);
        query.setHint(R.string.cinema_ambient_search);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setSelectAllOnFocus(true);
        query.setPaddingRelative(dp(14), 0, dp(14), 0);
        query.setBackground(CinemaUi.rounded(0xFF211F1C, dp(14), 0xFF4F4435));
        query.setOnFocusChangeListener((v, focused) ->
                v.setBackground(CinemaUi.rounded(0xFF28241E, dp(14), focused ? CinemaTokens.GOLD : 0xFF4F4435)));
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submitQuery();
            return true;
        });
        searchWrap.addView(query, new LinearLayout.LayoutParams(dp(200), dp(42)));

        ImageButton searchBtn = createToolbarIcon(android.R.drawable.ic_menu_search,
                getString(R.string.cinema_ambient_search_action), this::submitQuery);
        var sbLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        sbLp.setMarginStart(dp(6));
        searchWrap.addView(searchBtn, sbLp);

        // 3) Favorites Pill
        favoritesBtn = createToolbarPill("", this::showFavorites);
        updateFavoritesButtonLabel();
        var favLp = new LinearLayout.LayoutParams(-2, dp(42));
        favLp.setMarginStart(dp(8));
        topBar.addView(favoritesBtn, favLp);

        // 4) Settings Gear Icon
        ImageButton settingsBtn = createToolbarIcon(R.drawable.cinema_addon_settings,
                getString(R.string.cinema_ambient_playback_settings),
                () -> AmbientYoutubeSettingsDialog.show(this, prefs));
        var setLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        setLp.setMarginStart(dp(8));
        topBar.addView(settingsBtn, setLp);

        status = CinemaUi.text(this, 15, false);
        status.setTextColor(0xFFD6CBB8);
        status.setPaddingRelative(dp(4), dp(4), dp(4), dp(6));
        page.addView(status);

        // Modern 4-column Video Grid with Zero Focus-Ring Clipping
        grid = new RecyclerView(this);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), dp(12), dp(8), dp(18));
        int cols = Math.max(3, Math.min(4, (getResources().getDisplayMetrics().widthPixels - gutter * 2) / dp(215)));
        grid.setLayoutManager(new GridLayoutManager(this, cols));
        grid.setItemAnimator(null);
        grid.setAdapter(new Videos());
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (playback != null) closePlayback();
                else finish();
            }
        });

        categoryBtn.requestFocus();
        search(QUERIES[0]);
    }

    private TextView createToolbarPill(String label, Runnable click) {
        TextView btn = CinemaUi.text(this, 14, true);
        btn.setText(label);
        btn.setGravity(Gravity.CENTER);
        btn.setPaddingRelative(dp(14), 0, dp(14), 0);
        btn.setFocusable(true);
        btn.setClickable(true);
        stylePill(btn, false, false);
        btn.setOnFocusChangeListener((v, focused) -> stylePill(btn, focused, btn == favoritesBtn && showingFavorites));
        btn.setOnClickListener(v -> click.run());
        return btn;
    }

    private void stylePill(TextView btn, boolean focused, boolean active) {
        int bg = focused ? CinemaTokens.GOLD : active ? 0xFF3A2C16 : 0xFF23201C;
        int border = focused || active ? CinemaTokens.GOLD : 0xFF4F4435;
        btn.setBackground(CinemaUi.rounded(bg, dp(14), border));
        btn.setTextColor(focused ? 0xFF141517 : active ? CinemaTokens.GOLD : Color.WHITE);
        btn.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                .setDuration(CinemaUi.motion(this) ? CinemaTokens.FOCUS_MS : 0).start();
    }

    private ImageButton createToolbarIcon(int iconRes, String desc, Runnable click) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(iconRes);
        btn.setColorFilter(CinemaTokens.GOLD);
        btn.setContentDescription(desc);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setBackground(CinemaUi.rounded(0xFF23201C, dp(14), 0xFF4F4435));
        btn.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable ring = CinemaUi.rounded(Color.TRANSPARENT, dp(14), 0);
            ring.setStroke(dp(3), CinemaTokens.GOLD);
            v.setForeground(focused ? ring : null);
            btn.setBackground(CinemaUi.rounded(focused ? 0xFF362B1A : 0xFF23201C, dp(14),
                    focused ? CinemaTokens.GOLD : 0xFF4F4435));
            v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                    .setDuration(CinemaUi.motion(this) ? CinemaTokens.FOCUS_MS : 0).start();
        });
        btn.setOnClickListener(v -> click.run());
        return btn;
    }

    private void updateCategoryButtonLabel() {
        String catName = selectedCategoryIndex >= 0 && selectedCategoryIndex < CATEGORIES.length
                ? getString(CATEGORIES[selectedCategoryIndex])
                : getString(R.string.cinema_ambient_categories);
        categoryBtn.setText(getString(R.string.cinema_ambient_categories) + ": " + catName + " ▾");
    }

    private void updateFavoritesButtonLabel() {
        String base = "★ " + getString(R.string.cinema_ambient_favorites);
        favoritesBtn.setText(favorites.isEmpty() ? base : base + " (" + favorites.size() + ")");
        stylePill(favoritesBtn, favoritesBtn.hasFocus(), showingFavorites);
    }

    private void showCategoryDropdown() {
        String[] labels = new String[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) labels[i] = getString(CATEGORIES[i]);
        ListPopupWindow popup = new ListPopupWindow(this);
        popup.setAnchorView(categoryBtn);
        popup.setModal(true);
        popup.setWidth(dp(250));
        GradientDrawable bg = CinemaUi.rounded(0xFF1A1917, dp(14), 0);
        bg.setStroke(dp(2), CinemaTokens.GOLD);
        popup.setBackgroundDrawable(bg);
        popup.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = CinemaUi.text(CinemaAmbientYoutubeActivity.this, 15, true);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                row.setPaddingRelative(dp(16), dp(10), dp(14), dp(10));
                row.setMinHeight(dp(44));
                boolean current = !showingFavorites && position == selectedCategoryIndex;
                row.setText((current ? "✓  " : "") + getItem(position));
                row.setTextColor(current ? CinemaTokens.GOLD : 0xFFF3ECE0);
                return row;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            selectedCategoryIndex = position;
            showingFavorites = false;
            updateCategoryButtonLabel();
            updateFavoritesButtonLabel();
            search(QUERIES[position]);
        });
        popup.show();
        if (popup.getListView() != null) {
            GradientDrawable sel = CinemaUi.rounded(0x33E5A93B, dp(10), 0);
            sel.setStroke(dp(2), CinemaTokens.GOLD);
            popup.getListView().setSelector(sel);
            popup.setSelection(Math.max(0, selectedCategoryIndex));
        }
    }

    private int dp(int size) { return CinemaUi.dp(this, size); }

    private void submitQuery() {
        String term = query.getText().toString().strip();
        if (term.isEmpty()) return;
        var ime = getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(query.getWindowToken(), 0);
        showingFavorites = false;
        updateFavoritesButtonLabel();
        search(term);
    }

    private void showFavorites() {
        cancelRequest();
        showingFavorites = true;
        updateFavoritesButtonLabel();
        display(List.copyOf(favorites.values()), R.string.cinema_ambient_empty_favorites);
    }

    private void search(String term) {
        cancelRequest();
        showingFavorites = false;
        recentQuery = term;
        updateFavoritesButtonLabel();
        int token = generation;
        display(List.of(), R.string.addon_loading);
        pending = workers.submit(() -> {
            try {
                var results = AmbientYoutubeFetcher.search(term);
                runOnUiThread(() -> {
                    if (current(token)) display(results, R.string.cinema_ambient_empty_results);
                });
            } catch (Exception | LinkageError failure) {
                android.util.Log.w("CinemaAmbientYoutube", "YouTube request failed (" + failure.getClass().getSimpleName() + ")");
                runOnUiThread(() -> {
                    if (current(token)) {
                        display(List.of(), R.string.cinema_ambient_search_retry);
                        status.setOnClickListener(v -> search(recentQuery));
                        status.setFocusable(true);
                    }
                });
            }
        });
    }

    private void display(List<AmbientYoutubeFetcher.Video> results, int emptyLabel) {
        items = results;
        grid.getAdapter().notifyDataSetChanged();
        status.setText(emptyLabel);
        status.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        status.setOnClickListener(null);
        status.setClickable(false);
        status.setFocusable(false);
    }

    private boolean current(int token) {
        return token == generation && !isFinishing() && !isDestroyed();
    }

    private void cancelRequest() {
        generation++;
        if (pending != null) pending.cancel(true);
    }

    private void play(AmbientYoutubeFetcher.Video video) {
        Uri uri = Uri.parse(AmbientYoutubeFetcher.watchUrl(video.id()));
        if (prefs.getInt(AmbientYoutubeSettingsDialog.PLAYER, 0) == 0) {
            for (String pkg : SMART_TUBE) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg));
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {}
            }
        }
        openPlayback(video);
    }

    private void openPlayback(AmbientYoutubeFetcher.Video video) {
        cancelRequest();
        int token = generation;
        previousFocus = getCurrentFocus();
        preferredHeight = AmbientYoutubeSettingsDialog.HEIGHTS[prefs.getInt(AmbientYoutubeSettingsDialog.QUALITY, 0)];
        playback = new FrameLayout(this);
        playback.setBackgroundColor(0xff000000);
        playback.setClickable(true);
        playback.setFocusableInTouchMode(true);
        browser.setVisibility(View.INVISIBLE);
        root.addView(playback, new FrameLayout.LayoutParams(-1, -1));
        playback.requestFocus();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playback.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER);
        loadingBox.setPadding(dp(24), dp(16), dp(24), dp(16));
        loadingBox.setBackground(CinemaUi.rounded(0xCC171717, dp(16), 0x66E5A93B));
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(CinemaTokens.GOLD));
        loadingBox.addView(spin, new LinearLayout.LayoutParams(dp(34), dp(34)));
        playerStatus = CinemaUi.text(this, 16, true);
        playerStatus.setGravity(Gravity.CENTER);
        playerStatus.setPadding(0, dp(10), 0, 0);
        playerStatus.setText(R.string.addon_loading);
        loadingBox.addView(playerStatus);
        playback.addView(loadingBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        playerStatus.setTag(loadingBox);

        pending = workers.submit(() -> {
            try {
                var found = AmbientYoutubeFetcher.sources(video.id(), preferredHeight);
                runOnUiThread(() -> {
                    if (!current(token) || playback == null) return;
                    sources = found;
                    sourceIndex = 0;
                    trySource();
                });
            } catch (Exception | LinkageError failure) {
                android.util.Log.w("CinemaAmbientYoutube", "YouTube request failed (" + failure.getClass().getSimpleName() + ")");
                runOnUiThread(() -> {
                    if (current(token) && playback != null) playerStatus.setText(R.string.cinema_ambient_playback_error);
                });
            }
        });
    }

    private void hidePlaybackLoading() {
        if (playerStatus != null && playerStatus.getTag() instanceof View box) {
            box.setVisibility(View.GONE);
        }
    }

    private void showPlaybackLoading(int messageRes) {
        if (playerStatus != null) {
            playerStatus.setText(messageRes);
            if (playerStatus.getTag() instanceof View box) box.setVisibility(View.VISIBLE);
        }
    }

    private void trySource() {
        if (sourceIndex >= sources.size()) {
            showPlaybackLoading(R.string.cinema_ambient_playback_error);
            return;
        }
        var source = sources.get(sourceIndex++);
        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            if (preferredHeight > 0) player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon().setMaxVideoSize(Integer.MAX_VALUE, preferredHeight).build());
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onRenderedFirstFrame() {
                    if (playback != null) hidePlaybackLoading();
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (playback != null && playing) hidePlaybackLoading();
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if (playback != null) trySource();
                }
            });
        }
        var factory = new DefaultMediaSourceFactory(new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000).setReadTimeoutMs(12000));
        var item = new MediaItem.Builder().setUri(source.video()).setMimeType(source.mime()).build();
        var media = factory.createMediaSource(item);
        if (source.audio().isEmpty()) player.setMediaSource(media);
        else player.setMediaSource(new MergingMediaSource(true, true, media,
                factory.createMediaSource(MediaItem.fromUri(source.audio()))));
        player.prepare();
        player.play();
    }

    private void closePlayback() {
        cancelRequest();
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        root.removeView(playback);
        playback = null;
        browser.setVisibility(View.VISIBLE);
        sources = List.of();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (previousFocus != null) previousFocus.requestFocus();
    }

    private void loadFavorites() {
        try {
            var saved = new JSONArray(prefs.getString("favorites", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject row = saved.getJSONObject(i);
                String id = row.getString("id");
                if (id.matches("[A-Za-z0-9_-]{11}")) favorites.put(id, new AmbientYoutubeFetcher.Video(
                        id, row.getString("title"), row.optString("thumbnail"), row.optLong("duration")));
            }
        } catch (org.json.JSONException ignored) {}
    }

    private void favorite(AmbientYoutubeFetcher.Video video) {
        boolean added = favorites.remove(video.id()) == null;
        if (added) favorites.put(video.id(), video);
        var saved = new JSONArray();
        try {
            for (var item : favorites.values()) {
                saved.put(new JSONObject().put("id", item.id()).put("title", item.title())
                        .put("thumbnail", item.thumbnail()).put("duration", item.seconds()));
            }
        } catch (org.json.JSONException ignored) {}
        prefs.edit().putString("favorites", saved.toString()).apply();
        updateFavoritesButtonLabel();
        Toast.makeText(this, added ? R.string.cinema_ambient_favorite : R.string.cinema_ambient_unfavorite,
                Toast.LENGTH_SHORT).show();
        if (showingFavorites) showFavorites();
        else grid.getAdapter().notifyDataSetChanged();
    }

    @Override protected void onStop() {
        if (playback != null) closePlayback();
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelRequest();
        workers.shutdownNow();
        super.onDestroy();
    }

    private final class Videos extends RecyclerView.Adapter<VideoCard> {
        @Override public int getItemCount() { return items.size(); }

        @Override public VideoCard onCreateViewHolder(ViewGroup parent, int type) { return new VideoCard(); }

        @Override public void onBindViewHolder(VideoCard card, int position) {
            var video = items.get(position);
            boolean isFav = favorites.containsKey(video.id());
            card.title.setText(video.title());
            long seconds = video.seconds();
            card.duration.setText(seconds >= 3600
                    ? String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                    : String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60));
            card.favStar.setVisibility(isFav ? View.VISIBLE : View.GONE);
            Picasso.get().cancelRequest(card.image);
            card.image.setImageResource(R.drawable.cinema_live_tv);
            if (video.thumbnail().startsWith("https://")) {
                Picasso.get().load(video.thumbnail()).resize(dp(360), dp(202)).centerCrop()
                        .placeholder(R.drawable.cinema_live_tv).into(card.image);
            }
            card.tile.setContentDescription(video.title() + " · " + card.duration.getText());
            card.tile.setOnClickListener(v -> play(video));
            card.tile.setOnLongClickListener(v -> {
                favorite(video);
                return true;
            });
            card.tile.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    favorite(video);
                    return true;
                }
                return CinemaUi.moveVerticalGridFocus(grid, v, event, null);
            });
        }

        @Override public void onViewRecycled(VideoCard card) { Picasso.get().cancelRequest(card.image); }
    }

    private final class VideoCard extends RecyclerView.ViewHolder {
        final LinearLayout tile;
        final ImageView image;
        final TextView title, duration, favStar;

        VideoCard() {
            super(new FrameLayout(CinemaAmbientYoutubeActivity.this));
            FrameLayout cell = (FrameLayout) itemView;
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            int pad = dp(8);
            cell.setPadding(pad, pad, pad, pad);
            cell.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            tile = new LinearLayout(cell.getContext());
            tile.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF23211E, 0xFF171717});
            surface.setCornerRadius(dp(14));
            surface.setStroke(dp(1), 0xFF3E362A);
            tile.setBackground(surface);
            tile.setClipToOutline(true);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setLongClickable(true);
            tile.setOnFocusChangeListener((v, focused) -> {
                GradientDrawable ring = CinemaUi.rounded(Color.TRANSPARENT, dp(14), 0);
                ring.setStroke(dp(3), CinemaTokens.GOLD);
                v.setForeground(focused ? ring : null);
                float scale = focused ? 1.04f : 1f;
                v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(CinemaUi.motion(CinemaAmbientYoutubeActivity.this) ? CinemaTokens.FOCUS_MS : 0).start();
            });
            cell.addView(tile, new FrameLayout.LayoutParams(-1, -2));

            // 16:9 Thumbnail Frame with Overlaid Duration & Favorite Star Badges
            FrameLayout thumbFrame = new FrameLayout(cell.getContext()) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int w = View.MeasureSpec.getSize(widthSpec);
                    int h = Math.round(w * 9f / 16f);
                    super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                }
            };
            tile.addView(thumbFrame, new LinearLayout.LayoutParams(-1, -2));

            image = new ImageView(cell.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            thumbFrame.addView(image, new FrameLayout.LayoutParams(-1, -1));

            View scrim = new View(cell.getContext());
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00171717, 0x88171717}));
            thumbFrame.addView(scrim, new FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM));

            duration = CinemaUi.text(cell.getContext(), 11, true);
            duration.setTextColor(Color.WHITE);
            duration.setPadding(dp(7), dp(2), dp(7), dp(2));
            duration.setBackground(CinemaUi.rounded(0xCC121212, dp(6), 0x44E5A93B));
            var durLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            durLp.setMargins(dp(8), dp(8), dp(8), dp(8));
            thumbFrame.addView(duration, durLp);

            favStar = CinemaUi.text(cell.getContext(), 13, true);
            favStar.setText("★");
            favStar.setTextColor(0xFF141517);
            favStar.setGravity(Gravity.CENTER);
            favStar.setBackground(CinemaUi.rounded(CinemaTokens.GOLD, dp(10), 0));
            var starLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START);
            starLp.setMargins(dp(8), dp(8), dp(8), dp(8));
            thumbFrame.addView(favStar, starLp);

            title = CinemaUi.text(cell.getContext(), 13, true);
            title.setTextColor(Color.WHITE);
            title.setPaddingRelative(dp(10), dp(8), dp(10), dp(10));
            title.setLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(title, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
