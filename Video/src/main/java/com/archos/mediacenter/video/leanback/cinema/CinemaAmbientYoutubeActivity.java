package com.archos.mediacenter.video.leanback.cinema;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private TextView status, playerStatus;
    private RecyclerView grid;
    private PlayerView playerView;
    private ExoPlayer player;
    private View previousFocus;
    private Future<?> pending;
    private int generation, sourceIndex, preferredHeight;
    private boolean showingFavorites;
    private String recentQuery;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("cinema_ambient_youtube", MODE_PRIVATE);
        loadFavorites();
        root = new FrameLayout(this);
        root.setLayoutDirection(CinemaUi.arabic(this) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CinemaTokens.CANVAS);
        var page = new LinearLayout(this);
        browser = page;
        page.setOrientation(LinearLayout.VERTICAL);
        int gutter = dp(34);
        page.setPaddingRelative(gutter, dp(22), gutter, dp(14));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        var title = CinemaUi.text(this, 28, true);
        title.setTextColor(0xffe5a93b);
        title.setText(R.string.cinema_ambient_youtube);
        title.setAccessibilityHeading(true);
        page.addView(title);

        var tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(tools, new LinearLayout.LayoutParams(-1, dp(58)));
        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint(R.string.cinema_ambient_search);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setSelectAllOnFocus(true);
        query.setPaddingRelative(dp(12), 0, dp(12), 0);
        query.setBackground(CinemaUi.rounded(0xff272727, dp(12), 0xff6e6558));
        query.setOnFocusChangeListener((v, focused) -> v.setBackground(CinemaUi.rounded(0xff272727,
                dp(12), focused ? 0xffe5a93b : 0xff6e6558)));
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submitQuery();
            return true;
        });
        tools.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        action(tools, R.string.cinema_ambient_search_action, this::submitQuery);
        action(tools, R.string.cinema_ambient_favorites, this::showFavorites);
        action(tools, R.string.cinema_ambient_playback_settings, () -> AmbientYoutubeSettingsDialog.show(this, prefs));

        var rail = new HorizontalScrollView(this);
        rail.setHorizontalScrollBarEnabled(false);
        var categories = new LinearLayout(this);
        rail.addView(categories);
        page.addView(rail, new LinearLayout.LayoutParams(-1, dp(60)));
        for (int i = 0; i < CATEGORIES.length; i++) {
            String term = QUERIES[i];
            action(categories, CATEGORIES[i], () -> search(term));
        }

        status = CinemaUi.text(this, 16, false);
        page.addView(status);
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, Math.max(2, Math.min(5,
                (getResources().getDisplayMetrics().widthPixels - gutter * 2) / dp(180)))));
        grid.setItemAnimator(null);
        grid.setAdapter(new Videos());
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (playback != null) closePlayback();
                else finish();
            }
        });
        categories.getChildAt(0).requestFocus();
        search(QUERIES[0]);
    }

    private int dp(int size) { return CinemaUi.dp(this, size); }

    private void action(LinearLayout row, int label, Runnable click) {
        var button = CinemaUi.text(this, 14, true);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setPaddingRelative(dp(12), 0, dp(12), 0);
        CinemaUi.focusable(button);
        button.setOnFocusChangeListener((view, focused) -> button.setTextColor(
                focused ? CinemaTokens.ON_FOCUS : CinemaTokens.TEXT));
        var size = new LinearLayout.LayoutParams(-2, dp(48));
        size.setMarginStart(dp(8));
        row.addView(button, size);
        button.setOnClickListener(v -> click.run());
    }

    private void submitQuery() {
        String term = query.getText().toString().strip();
        if (term.isEmpty()) return;
        var ime = getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(query.getWindowToken(), 0);
        search(term);
    }

    private void showFavorites() {
        cancelRequest();
        showingFavorites = true;
        display(List.copyOf(favorites.values()), R.string.cinema_ambient_empty_favorites);
    }

    private void search(String term) {
        cancelRequest();
        showingFavorites = false;
        recentQuery = term;
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
        playerStatus = CinemaUi.text(this, 17, true);
        playerStatus.setGravity(Gravity.CENTER);
        playerStatus.setText(R.string.addon_loading);
        playback.addView(playerStatus, new FrameLayout.LayoutParams(-1, -1));
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

    private void trySource() {
        if (sourceIndex >= sources.size()) {
            playerStatus.setVisibility(View.VISIBLE);
            playerStatus.setText(R.string.cinema_ambient_playback_error);
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
                    if (playback != null) playerStatus.setVisibility(View.GONE);
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (playback != null && playing) playerStatus.setVisibility(View.GONE);
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if (playback != null) trySource();
                }
            });
        }
        var factory = new DefaultMediaSourceFactory(new DefaultHttpDataSource.Factory()
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
        if (favorites.remove(video.id()) == null) favorites.put(video.id(), video);
        var saved = new JSONArray();
        try {
            for (var item : favorites.values()) {
                saved.put(new JSONObject().put("id", item.id()).put("title", item.title())
                        .put("thumbnail", item.thumbnail()).put("duration", item.seconds()));
            }
        } catch (org.json.JSONException ignored) {}
        prefs.edit().putString("favorites", saved.toString()).apply();
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
            card.title.setText(video.title());
            long seconds = video.seconds();
            card.duration.setText(seconds >= 3600
                    ? String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                    : String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60));
            card.favorite.setText(favorites.containsKey(video.id()) ? R.string.cinema_ambient_unfavorite : R.string.cinema_ambient_favorite);
            Picasso.get().cancelRequest(card.image);
            card.image.setImageResource(R.drawable.cinema_live_tv);
            if (video.thumbnail().startsWith("https://"))
                Picasso.get().load(video.thumbnail()).resize(dp(320), dp(180)).centerCrop()
                        .placeholder(R.drawable.cinema_live_tv).into(card.image);
            card.play.setContentDescription(video.title() + " · " + card.duration.getText());
            card.play.setOnClickListener(v -> play(video));
            card.favorite.setOnClickListener(v -> favorite(video));
        }

        @Override public void onViewRecycled(VideoCard card) { Picasso.get().cancelRequest(card.image); }
    }

    private final class VideoCard extends RecyclerView.ViewHolder {
        final LinearLayout play;
        final ImageView image;
        final TextView title, duration;
        final android.widget.Button favorite;

        VideoCard() {
            super(new LinearLayout(CinemaAmbientYoutubeActivity.this));
            var column = (LinearLayout) itemView;
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPaddingRelative(dp(7), dp(6), dp(7), dp(6));
            column.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            play = new LinearLayout(column.getContext());
            play.setOrientation(LinearLayout.VERTICAL);
            play.setBackground(CinemaUi.rounded(0xff252525, dp(12), 0xff4b4b4b));
            play.setClipToOutline(true);
            play.setFocusable(true);
            play.setOnFocusChangeListener((v, focused) -> v.setForeground(focused
                    ? CinemaUi.rounded(0, dp(12), 0xffe5a93b) : null));
            column.addView(play);
            image = new ImageView(column.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            play.addView(image, new LinearLayout.LayoutParams(-1, dp(104)));
            title = CinemaUi.text(column.getContext(), 15, true);
            title.setPaddingRelative(dp(10), dp(6), dp(10), 0);
            title.setLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            play.addView(title);
            duration = CinemaUi.text(column.getContext(), 13, false);
            duration.setPaddingRelative(dp(10), 0, dp(10), dp(7));
            play.addView(duration);
            favorite = CinemaArtSettingsSheet.addButton(column, "");
        }
    }
}
