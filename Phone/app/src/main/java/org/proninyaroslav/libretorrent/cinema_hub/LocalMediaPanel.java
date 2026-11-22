package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.ContentUris;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import org.proninyaroslav.libretorrent.R;

final class LocalMediaPanel {
    static final int PERMISSION_REQUEST = 463;
    private static final String[] TYPES = {"video", "audio", "image"};
    private static final String[] PERMISSIONS = {"android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES"};
    private final TvHubActivity activity;
    private final FrameLayout results;
    private final TextView disabledMessage;
    private final List<CollectionState> collections;
    private final ExecutorService mediaQueries = Executors.newFixedThreadPool(2);
    private final BiConsumer<Uri, String> cast;
    private final androidx.activity.result.ActivityResultLauncher<String[]> picker;
    private static final int GOLD = 0xffe5a93b;
    private static final int FILES = 3;
    private final MaterialButton[] categories = new MaterialButton[4];
    private int category;
    private boolean browsingFiles;
    private boolean enabled;
    private static final java.util.regex.Pattern EXCLUDED_AUDIO_FOLDERS = java.util.regex.Pattern.compile(
            "(?:^|/)(?:whatsapp voice notes|whatsapp audio|com\\.whatsapp(?:\\.w4b)?|telegram audio|org\\.telegram\\.messenger|recordings|voice recorder|voicerecorder|soundrecorder|call|callrecordings|miui/sound_recorder|ringtones|notifications|alarms)(?:/|$)");
    private static final java.util.regex.Pattern EXCLUDED_AUDIO_NAMES = java.util.regex.Pattern.compile(
            "^(?:voice[_ ]|ptt-|aud-|rec_|recording_|call_)|\\.(?:opus|amr|3gp)$");
    private final java.util.WeakHashMap<LinearLayout, Uri> audioRows = new java.util.WeakHashMap<>();
    private final java.util.Set<String> favorites = new java.util.HashSet<>();
    private int audioTab;

    private enum Access { NONE, PARTIAL, FULL }

    private static final class CollectionState {
        final LinearLayout panel;
        Access access;
        List<Item> items;
        String folder = "";
        String query = "";
        int request;
        boolean querying;

        CollectionState(TvHubActivity activity) {
            panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
        }
    }

    record Item(Uri uri, String title, String folder, long duration, long size, String artist, String filename, long dateAdded) {}

    LocalMediaPanel(TvHubActivity activity, LinearLayout root, MaterialSwitch toggle, BiConsumer<Uri, String> cast) {
        this.activity = activity;
        this.cast = cast;
        var settings = activity.getSharedPreferences("cinema_local_media", 0);
        enabled = settings.getBoolean("enabled", true);
        favorites.addAll(activity.getSharedPreferences("cinema_music", 0).getStringSet("favorites", java.util.Set.of()));
        picker = activity.getActivityResultRegistry().register("local-media-files",
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
                    browsingFiles = false;
                    updateCategories();
                    if (uri == null || !enabled) return;
                    String mime = activity.getContentResolver().getType(uri);
                    if (mime != null && mime.contains("/")) {
                        String type = mime.substring(0, mime.indexOf('/'));
                        if (List.of(TYPES).contains(type)) cast.accept(uri, type);
                    }
                });
        var controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls);
        LinearLayout tabs = new LinearLayout(activity);
        controls.addView(tabs);
        String[] names = {activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_videos), activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_audio), activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_photos)};
        for (int i = 0; i < names.length; i++) {
            int selected = i;
            MaterialButton button = button(names[i], () -> {
                browsingFiles = false;
                category = selected;
                showCategory();
                load(selected, false, false);
            });
            button.setMinWidth(0);
            button.setPadding(0, 0, 0, 0);
            categories[i] = button;
            var params = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) params.setMarginStart(dp(6));
            tabs.addView(button, params);
        }
        categories[FILES] = button(activity.getString(R.string.cinema_file_browser), () -> {
            browsingFiles = true;
            updateCategories();
            picker.launch(new String[]{"video/*", "audio/*", "image/*"});
        });
        categories[FILES].setIconResource(R.drawable.ic_folder_open_24px);
        categories[FILES].setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        controls.addView(categories[FILES], new LinearLayout.LayoutParams(-1, dp(48)));
        updateCategories();
        results = new FrameLayout(activity);
        root.addView(results, new LinearLayout.LayoutParams(-1, -2));
        collections = List.of(new CollectionState(activity), new CollectionState(activity),
                new CollectionState(activity));
        for (var entry : collections) {
            results.addView(entry.panel, new FrameLayout.LayoutParams(-1, -2));
        }
        disabledMessage = new TextView(activity);
        disabledMessage.setText(R.string.cinema_media_disabled);
        results.addView(disabledMessage);
        showCategory();
        controls.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
        toggle.setChecked(enabled);
        toggle.setOnCheckedChangeListener((view, checked) -> {
            enabled = checked;
            settings.edit().putBoolean("enabled", checked).apply();
            controls.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE);
            refresh();
        });
        activity.getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                picker.unregister();
                mediaQueries.shutdownNow();
            }
        });
    }

    private void updateCategories() {
        for (int i = 0; i < categories.length; i++) {
            boolean selected = i == (browsingFiles ? FILES : category);
            var button = categories[i];
            button.setSelected(selected);
            button.setBackgroundTintList(ColorStateList.valueOf(selected ? GOLD : 0xff171717));
            button.setStrokeColor(ColorStateList.valueOf(selected ? GOLD : 0xff50483c));
            button.setTextColor(selected ? 0xff171717 : 0xfff2ede7);
            button.setIconTint(ColorStateList.valueOf(selected ? 0xff171717 : GOLD));
        }
    }

    private void showCategory() {
        updateCategories();
        results.setMinimumHeight(enabled ? dp(280) : 0);
        disabledMessage.setVisibility(enabled ? View.GONE : View.VISIBLE);
        for (int index = 0; index < collections.size(); index++) {
            collections.get(index).panel.setVisibility(enabled && index == category ? View.VISIBLE : View.GONE);
        }
    }

    void refresh() {
        refresh(false);
    }

    void refreshOnResume() {
        refresh(true);
    }

    private void refresh(boolean recheckPartialAccess) {
        showCategory();
        if (!enabled) return;
        for (int offset = 0; offset < collections.size(); offset++) {
            int index = (category + offset) % collections.size();
            load(index, true, recheckPartialAccess);
        }
    }

    private void load(int selected, boolean force, boolean recheckPartialAccess) {
        if (!enabled) return;
        var state = collections.get(selected);
        String permission = Build.VERSION.SDK_INT >= 33 ? PERMISSIONS[selected]
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        boolean visualSelection = Build.VERSION.SDK_INT >= 34 && selected != 1;
        Access access = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ? Access.FULL
                : visualSelection && activity.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        == PackageManager.PERMISSION_GRANTED ? Access.PARTIAL : Access.NONE;
        if (access != state.access || (access == Access.PARTIAL && recheckPartialAccess)) {
            state.access = access;
            state.items = null;
            state.request++;
            state.querying = false;
            state.panel.removeAllViews();
            if (access == Access.NONE) {
                message(state.panel, R.string.cinema_media_permission);
                state.panel.addView(button(activity.getString(R.string.cinema_grant_access),
                        () -> activity.requestPermissions(visualSelection
                                ? new String[]{permission, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
                                : new String[]{permission}, PERMISSION_REQUEST)));
            }
        }
        if (access == Access.NONE || state.querying || (!force && state.items != null)) return;
        state.querying = true;
        int request = ++state.request;
        mediaQueries.execute(() -> {
            var items = new ArrayList<Item>();
            Uri collection = switch (selected) {
                case 1 -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                case 2 -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                default -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            };
            boolean timed = selected != 2;
            String[] fields = timed ? new String[]{"_id", "_display_name", "relative_path", "_size", "duration"}
                    : new String[]{"_id", "_display_name", "relative_path", "_size"};
            if (selected == 1) fields = new String[]{"_id", "_display_name", "relative_path", "_size", "duration", "title", "artist", "date_added"};
            try (var cursor = activity.getContentResolver().query(collection, fields,
                    selected == 1 ? "_size >= ? AND duration >= ?" : null,
                    selected == 1 ? new String[]{"1048576", "30000"} : null,
                    "date_added DESC, _id DESC")) {
                while (!Thread.currentThread().isInterrupted() && cursor != null && cursor.moveToNext()) {
                    String filename = cursor.getString(1);
                    String folder = cursor.getString(2);
                    long duration = timed ? cursor.getLong(4) : 0;
                    String artist = selected == 1 ? cursor.getString(6) : "";
                    String title = selected == 1 ? cursor.getString(5) : filename;
                    if (selected == 1) {
                        if (EXCLUDED_AUDIO_FOLDERS.matcher((folder == null ? "" : folder)
                                .toLowerCase(java.util.Locale.ROOT)).find()
                                || filename == null || EXCLUDED_AUDIO_NAMES.matcher(
                                        filename.toLowerCase(java.util.Locale.ROOT)).find()) continue;
                        if (title == null || title.isBlank()) title = filename;
                        title = title.replaceAll("\\[[A-Za-z0-9_-]{11}\\]", "").replaceFirst("\\.[A-Za-z0-9]{2,5}$", "").trim();
                        if (artist == null || artist.equals("<unknown>")) artist = folder == null ? "" : folder;
                    }
                    items.add(new Item(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                            title, folder, duration, cursor.getLong(3), artist, filename, selected == 1 ? cursor.getLong(7) : 0));
                }
                activity.runOnUiThread(() -> {
                    if (request != state.request || activity.isDestroyed()) return;
                    state.querying = false;
                    if (!items.equals(state.items)) {
                        state.items = List.copyOf(items);
                        render(state, selected);
                    }
                });
            } catch (RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (request != state.request || activity.isDestroyed()) return;
                    state.querying = false;
                    if (state.items == null) {
                        state.panel.removeAllViews();
                        message(state.panel, R.string.cinema_media_read_error);
                    }
                });
            }
        });
    }

    private void render(CollectionState state, int selected) {
        List<Item> items = state.items;
        LinearLayout panel = state.panel;
        panel.removeAllViews();
        if (items.isEmpty()) {
            message(panel, switch (selected) {
                case 1 -> R.string.cinema_media_no_audio;
                case 2 -> R.string.cinema_media_no_photos;
                default -> R.string.cinema_media_no_videos;
            });
            return;
        }
        var search = new android.widget.EditText(activity);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setPaddingRelative(dp(12), 0, dp(12), 0);
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search_24px, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(GOLD));
        search.setCompoundDrawablePadding(dp(8));
        var field = new GradientDrawable();
        field.setCornerRadius(dp(12));
        field.setColor(0xff171717);
        field.setStroke(dp(1), 0xff50483c);
        search.setBackground(field);
        search.setHint(selected == 1 ? activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_search_tracks_artists_folders) : activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_search_names_or_folders));
        var searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.topMargin = dp(6);
        searchParams.bottomMargin = dp(8);
        search.setText(state.query);
        panel.addView(search, searchParams);
        var list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        panel.addView(list);
        if (!state.folder.isEmpty() && items.stream().noneMatch(item -> state.folder.equals(item.folder()))) {
            state.folder = "";
        }
        var groups = new LinearLayout(activity);
        groups.setOrientation(LinearLayout.VERTICAL);
        if (selected == 1) panel.addView(groups, 0);
        Runnable redraw = () -> {
            list.removeAllViews();
            String query = search.getText().toString().toLowerCase(java.util.Locale.ROOT);
            var matches = items.stream().filter(item -> (state.folder.isEmpty() || state.folder.equals(item.folder()))
                    && (item.title() + " " + item.filename() + " " + item.artist() + " " + item.folder()).toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
            if (selected == 1) {
                var prefs = activity.getSharedPreferences("cinema_music", 0);
                matches = AudioSort.apply(matches, prefs.getInt("audio_list_sort", 1), prefs);
            }
            if (selected == 1 && audioTab == 3) {
                matches = matches.stream().filter(item -> favorites.contains(item.uri().toString())).toList();
            }
            if (selected == 1 && (audioTab == 1 || audioTab == 2) && state.folder.isEmpty() && query.isEmpty()) {
                var byGroup = new java.util.TreeMap<String, List<Item>>(String.CASE_INSENSITIVE_ORDER);
                for (Item item : matches) {
                    String key = audioTab == 1 ? item.folder() : item.artist();
                    if (key == null || key.isBlank()) key = activity.getString(R.string.cinema_music_unknown_group);
                    byGroup.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
                }
                for (var entry : byGroup.entrySet()) {
                    list.addView(button(entry.getKey() + " · " + entry.getValue().size(), () -> {
                        list.removeAllViews();
                        append(list, entry.getValue(), selected, 0);
                    }));
                }
            } else append(list, matches, selected, 0);
        };
        if (selected != 1) {
            String allFolders = activity.getString(R.string.cinema_all_folders);
            var folderControl = button(state.folder.isEmpty() ? allFolders : state.folder, () -> {});
            folderControl.setIconResource(R.drawable.ic_folder_24px);
            folderControl.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            folderControl.setSingleLine(true);
            folderControl.setEllipsize(android.text.TextUtils.TruncateAt.END);
            folderControl.setOnClickListener(v -> {
                var paths = new ArrayList<String>();
                paths.add("");
                items.stream().map(Item::folder).filter(path -> path != null && !path.isBlank())
                        .distinct().sorted(String.CASE_INSENSITIVE_ORDER).forEach(paths::add);
                var choices = paths.stream().map(path -> new CinemaGlassUi.Choice(
                        path.isEmpty() ? allFolders : path,
                        path.isEmpty() ? R.drawable.ic_folder_open_24px : R.drawable.ic_folder_24px)).toList();
                CinemaGlassUi.showChoices(activity, R.string.cinema_folders, choices, paths.indexOf(state.folder), index -> {
                    state.folder = paths.get(index);
                    folderControl.setText(choices.get(index).label());
                    redraw.run();
                });
            });
            panel.addView(folderControl, 0);
        }
        else {
            var tabs = new com.google.android.material.tabs.TabLayout(activity);
            tabs.setBackgroundColor(0xff171717);
            tabs.setSelectedTabIndicatorColor(GOLD);
            groups.addView(tabs, new LinearLayout.LayoutParams(-1, dp(48)));
            tabs.setTabMode(com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE);
            for (String name : new String[]{activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_tracks), activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_folders), activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_artists), activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_favorites)}) {
                var tab = tabs.newTab().setText(name);
                var label = new TextView(activity);
                label.setText(name);
                label.setTextSize(13);
                label.setSingleLine(true);
                label.setMaxLines(1);
                label.setGravity(android.view.Gravity.CENTER);
                label.setPadding(dp(8), 0, dp(8), 0);
                label.setTextColor(new android.content.res.ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_selected}, new int[0]},
                        new int[]{0xfff0bf62, 0xffccc3b8}));
                tab.setCustomView(label);
                tab.view.setPadding(0, 0, 0, 0);
                tabs.addTab(tab);
            }
            tabs.selectTab(tabs.getTabAt(audioTab));
            tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                    audioTab = tab.getPosition();
                    redraw.run();
                }
                public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
                public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) { redraw.run(); }
            });
            var actions = activity.row(groups);
            var sort = button(activity.getString(R.string.cinema_media_sort),
                    () -> AudioSort.choose(activity, "audio_list_sort", order -> redraw.run()));
            sort.setIconResource(R.drawable.ic_sort_24px);
            sort.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            actions.addView(sort, new LinearLayout.LayoutParams(0, dp(48), 1));
            var clear = button(activity.getString(R.string.cinema_clear_search), () -> search.setText(""));
            var clearParams = new LinearLayout.LayoutParams(0, dp(48), 1);
            clearParams.setMarginStart(dp(6));
            actions.addView(clear, clearParams);
        }
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                state.query = s.toString();
                redraw.run();
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
        redraw.run();
    }

    private void append(LinearLayout list, List<Item> items, int selected, int offset) {
        int end = Math.min(offset + 30, items.size());
        for (int i = offset; i < end; i++) {
            Item item = items.get(i);
            if (selected == 1) {
                appendAudioRow(list, items, i);
                continue;
            }
            var row = new LinearLayout(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            var thumb = new android.widget.ImageView(activity);
            thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            ThumbnailLoader.EXECUTOR.execute(() -> {
                try {
                    var bitmap = activity.getContentResolver().loadThumbnail(item.uri(), new android.util.Size(160, 160), null);
                    activity.runOnUiThread(() -> thumb.setImageBitmap(bitmap));
                } catch (java.io.IOException | RuntimeException ignored) {}
            });
            int size = Math.round(activity.getResources().getDisplayMetrics().density * 56);
            row.addView(thumb, new LinearLayout.LayoutParams(size, size));
            String details = android.text.format.Formatter.formatShortFileSize(activity, item.size());
            if (item.duration() > 0) details += "  ·  " + android.text.format.DateUtils.formatElapsedTime(item.duration() / 1000);
            row.addView(button(item.title() + "\n" + details, () -> open(items, items.indexOf(item), selected)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            list.addView(row);
        }
        if (end < items.size()) {
            var more = button(activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_show_more), () -> append(list, items, selected, end));
            more.setOnClickListener(v -> {
                list.removeView(v);
                append(list, items, selected, end);
            });
            list.addView(more);
        }
    }

    private void appendAudioRow(LinearLayout list, List<Item> items, int index) {
        Item item = items.get(index);
        var row = new LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        var play = new android.widget.ImageButton(activity);
        play.setBackgroundResource(android.R.drawable.list_selector_background);
        play.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        play.setColorFilter(GOLD);
        Runnable start = () -> activity.openAudio(items, index);
        play.setOnClickListener(v -> activity.toggleOrOpenAudio(items, index));
        row.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));

        var body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        var title = new TextView(activity);
        title.setText(item.title());
        title.setTextSize(14);
        title.setTextColor(0xfff2ede7);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(title);
        var detail = new TextView(activity);
        detail.setTextSize(12);
        detail.setTextColor(0xffccc3b8);
        detail.setText(android.text.format.Formatter.formatShortFileSize(activity, item.size())
                + " · " + android.text.format.DateUtils.formatElapsedTime(item.duration() / 1000));
        body.addView(detail);
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> start.run());
        row.setFocusable(true);

        String key = item.uri().toString();
        var heart = new ImageButton(activity);
        heart.setBackgroundResource(android.R.drawable.list_selector_background);
        heart.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        heart.setColorFilter(GOLD);
        updateFavoriteIcon(heart, favorites.contains(key));
        heart.setOnClickListener(v -> {
            toggleFavorite(item.uri());
            updateFavoriteIcon(heart, favorites.contains(key));
            if (audioTab == 3 && !favorites.contains(key)) list.removeView(row);
        });
        row.addView(heart, new LinearLayout.LayoutParams(dp(48), dp(48)));
        var layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(6);
        list.addView(row, layout);
        audioRows.put(row, item.uri());
        updateAudioRow(row, item.uri(), activity.activeAudioUri(), activity.isAudioPlaying());
    }

    private void updateFavoriteIcon(ImageButton button, boolean favorite) {
        button.setImageResource(favorite ? R.drawable.cinema_favorite_filled : R.drawable.cinema_favorite_outline);
        button.setContentDescription(activity.getString(favorite
                ? R.string.cinema_music_remove_favorite : R.string.cinema_music_add_favorite));
        button.setSelected(favorite);
    }

    void refreshFavorites() {
        var audio = collections.get(1);
        if (audio.items != null && audioTab == 3) {
            render(audio, 1);
        } else {
            audioRows.forEach((row, uri) -> updateFavoriteIcon((ImageButton) row.getChildAt(2), isFavorite(uri)));
        }
    }

    boolean isFavorite(Uri uri) {
        return uri != null && favorites.contains(uri.toString());
    }

    void toggleFavorite(Uri uri) {
        if (uri == null) return;
        String key = uri.toString();
        if (!favorites.remove(key)) favorites.add(key);
        activity.getSharedPreferences("cinema_music", 0).edit().putStringSet("favorites",
                new java.util.HashSet<>(favorites)).apply();
    }

    void updateAudioRows(Uri active, boolean playing) {
        audioRows.forEach((row, uri) -> updateAudioRow(row, uri, active, playing));
    }

    private void updateAudioRow(LinearLayout row, Uri uri, Uri active, boolean playing) {
        boolean selected = uri.equals(active);
        boolean showPause = selected && playing;
        var play = (android.widget.ImageButton) row.getChildAt(0);
        play.setImageResource(showPause ? R.drawable.cinema_control_pause : R.drawable.cinema_control_play);
        play.setContentDescription(activity.getString(showPause ? R.string.cinema_music_pause_track : R.string.cinema_music_play_track));
        var surface = new android.graphics.drawable.GradientDrawable();
        surface.setCornerRadius(dp(16));
        surface.setColor(selected ? 0xff252016 : 0xff171717);
        surface.setStroke(dp(1), selected ? GOLD : 0xff403a32);
        row.setBackground(surface);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void open(List<Item> items, int index, int selected) {
        if (selected == 1) activity.openAudio(items, index);
        else if (selected == 2) activity.openPhotos(items.stream().map(Item::uri).toList(), index);
        else cast.accept(items.get(index).uri(), TYPES[selected]);
    }
    private static final class ThumbnailLoader {
        private static final java.util.concurrent.Executor EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(2);
    }

    private void message(LinearLayout panel, int message) {
        TextView text = new TextView(activity);
        text.setText(message);
        panel.addView(text);
    }

    private MaterialButton button(String label, Runnable action) {
        var button = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setStrokeColor(ColorStateList.valueOf(0xff50483c));
        button.setOnClickListener(v -> action.run());
        return button;
    }
}
