package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.squareup.picasso.Picasso;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.archos.mediacenter.video.leanback.cinema.CinemaFileOperations.Item;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/**
 * Unified File Browser supporting internal storage, USB drives, Art Gallery, and network shares.
 * Serves both full browsing (videos, images, folders) from Home and images-only picking from Art Mode,
 * with fast thumbnails and long-press file management (Select, Copy, Move, Paste, Delete).
 */
public final class CinemaFileBrowserActivity extends LeanbackActivity {
    public static final String EXTRA_FILTER_MODE = "cinema_filter_mode";
    public static final String EXTRA_INITIAL_VIDEO_URI = "cinema_initial_video_uri";
    public static final String FILTER_ALL = "all";
    public static final String FILTER_IMAGES_ONLY = "images";
    public static final String FILTER_SUBTITLES_ONLY = "subtitles";
    static final String IMAGE_MODE = "cinema_image_mode";
    static final String SELECTED_IMAGES = "cinema_selected_images";

    /** Shared DRY factory opening the subtitle picker directly inside the movie's own source folder. */
    public static Intent subtitlePickerIntent(android.content.Context context, Uri videoUri, boolean secondary) {
        Intent intent = new Intent(context, CinemaFileBrowserActivity.class);
        intent.putExtra(EXTRA_FILTER_MODE, FILTER_SUBTITLES_ONLY);
        if (videoUri != null) {
            intent.putExtra(EXTRA_INITIAL_VIDEO_URI, videoUri.toString());
        }
        if (secondary) {
            intent.putExtra("secondary_subtitle", true);
        }
        return intent;
    }

    private Uri movieSourceFolderUri;

    private final ExecutorService folders = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final LinkedHashMap<Uri, Item> marked = new LinkedHashMap<>();
    private final android.util.LruCache<Uri, Bitmap> thumbnails = new android.util.LruCache<>(6 * 1024 * 1024) {
        @Override protected int sizeOf(Uri uri, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };

    private List<Item> clipboard = List.of();
    private boolean clipboardMove;
    private List<Item> pendingSafDeleteTargets = List.of();
    private List<Item> pendingMediaStoreDeleteTargets = List.of();
    private int pendingMediaStorePriorDeleted;
    private int pendingMediaStoreTotalTargets;
    private Uri pendingMediaStoreNeighbor;
    private boolean imagesOnly;
    private boolean subtitlesOnly;
    private boolean selectMode;
    private boolean transferring;

    private final ActivityResultLauncher<Intent> safFolderPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    Uri tree = result.getData().getData();
                    try {
                        getContentResolver().takePersistableUriPermission(tree,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    if (!pendingSafDeleteTargets.isEmpty()) {
                        List<Item> retry = List.copyOf(pendingSafDeleteTargets);
                        pendingSafDeleteTargets = List.of();
                        executeDelete(retry);
                    } else if (!clipboard.isEmpty()) {
                        executeCopyOrMove(List.copyOf(clipboard), tree, clipboardMove);
                    }
                } else {
                    pendingSafDeleteTargets = List.of();
                }
            });

    private final ActivityResultLauncher<IntentSenderRequest> mediaStoreDeleteLauncher = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                List<Item> requested = List.copyOf(pendingMediaStoreDeleteTargets);
                int priorDeleted = pendingMediaStorePriorDeleted;
                int total = pendingMediaStoreTotalTargets;
                Uri neighbor = pendingMediaStoreNeighbor;
                pendingMediaStoreDeleteTargets = List.of();
                pendingMediaStorePriorDeleted = 0;
                pendingMediaStoreTotalTargets = 0;
                pendingMediaStoreNeighbor = null;
                if (result.getResultCode() == RESULT_OK && !requested.isEmpty()) {
                    for (Item item : requested) {
                        clearDeletedCaches(item);
                        marked.remove(item.uri());
                    }
                    selected = neighbor;
                    if (marked.isEmpty() && !imagesOnly) selectMode = false;
                    refreshActionStrip();
                    load();
                    Toast.makeText(this, getString(R.string.cinema_file_delete_result,
                            priorDeleted + requested.size(), Math.max(total, requested.size())),
                            Toast.LENGTH_LONG).show();
                } else if (!requested.isEmpty()) {
                    showManageAllFilesPrompt();
                }
            });

    private final ArrayDeque<Item> path = new ArrayDeque<>();
    private final ArrayList<Item> entries = new ArrayList<>();
    private FrameLayout root, viewer;
    private ImageView picture;
    private TextView backButton, location, status, photoCaption, layoutButton;
    private LinearLayout actionStrip;
    private TextView actionStripLabel, useSelectedBtn, pasteBtn, copyBtn, moveBtn, deleteBtn, cancelBarBtn;
    private RecyclerView files;
    private Files adapter;
    private int generation, imageGeneration, sort, photoIndex;
    private boolean grid;
    private Uri selected;
    private List<Item> photos = List.of();
    private float zoom = 1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String filterMode = getIntent().getStringExtra(EXTRA_FILTER_MODE);
        imagesOnly = getIntent().getBooleanExtra(IMAGE_MODE, false)
                || FILTER_IMAGES_ONLY.equalsIgnoreCase(filterMode);
        subtitlesOnly = FILTER_SUBTITLES_ONLY.equalsIgnoreCase(filterMode);
        selectMode = imagesOnly;
        requestStoragePermissions();

        var prefs = PreferenceManager.getDefaultSharedPreferences(this);
        grid = prefs.getBoolean("cinema_files_grid", true);
        sort = prefs.getInt("cinema_files_sort", 0);

        boolean rtl = CinemaUi.arabic(this);
        root = new FrameLayout(this);
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CinemaTokens.CANVAS);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        int gutter = dp(this, CinemaTokens.UTILITY_GUTTER);
        page.setPaddingRelative(gutter, dp(this, 18), gutter, dp(this, 16));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // Top Header & Controls
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setClipChildren(false);
        tools.setClipToPadding(false);
        tools.setPadding(0, dp(this, 4), 0, dp(this, 6));
        page.addView(tools, new LinearLayout.LayoutParams(-1, -2));

        backButton = control(tools, getString(R.string.cinema_back), this::onBackPressed);
        location = text(this, 24, true);
        location.setTextColor(CinemaTokens.GOLD);
        location.setSingleLine();
        location.setEllipsize(TextUtils.TruncateAt.END);
        location.setPaddingRelative(dp(this, 14), 0, dp(this, 14), 0);
        tools.addView(location, new LinearLayout.LayoutParams(0, -2, 1));

        control(tools, getString(R.string.cinema_sort), this::sortMenu);
        layoutButton = control(tools, layoutLabel(), () -> {
            grid = !grid;
            prefs.edit().putBoolean("cinema_files_grid", grid).apply();
            layoutButton.setText(layoutLabel());
            updateLayout();
        });
        if (subtitlesOnly) {
            control(tools, getString(R.string.cinema_sub_browse_other_storage), () -> {
                path.clear();
                selected = null;
                load();
            });
        } else if (!imagesOnly) {
            control(tools, getString(R.string.cinema_network_files), () -> startActivity(new Intent(this,
                    com.archos.mediacenter.video.leanback.network.NetworkRootActivity.class)));
        }

        // Unified Selection / Clipboard Action Strip (shown when selecting, image-picking, or clipboard active)
        actionStrip = new LinearLayout(this);
        actionStrip.setOrientation(LinearLayout.HORIZONTAL);
        actionStrip.setGravity(Gravity.CENTER_VERTICAL);
        actionStrip.setClipChildren(false);
        actionStrip.setClipToPadding(false);
        actionStrip.setPaddingRelative(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));
        GradientDrawable stripBg = rounded(0xFF1F1D19, dp(this, 12), 0x66E5A93B);
        actionStrip.setBackground(stripBg);
        var stripLp = new LinearLayout.LayoutParams(-1, -2);
        stripLp.topMargin = dp(this, 6);
        stripLp.bottomMargin = dp(this, 4);
        page.addView(actionStrip, stripLp);

        actionStripLabel = text(this, 14, true);
        actionStripLabel.setTextColor(CinemaTokens.GOLD);
        actionStripLabel.setSingleLine();
        actionStripLabel.setEllipsize(TextUtils.TruncateAt.END);
        actionStrip.addView(actionStripLabel, new LinearLayout.LayoutParams(0, -2, 1));

        useSelectedBtn = pillControl(actionStrip, getString(R.string.cinema_photo_use_selection), this::returnSelectedImages);
        copyBtn = pillControl(actionStrip, getString(R.string.cinema_file_copy), () -> stageMarkedToClipboard(false));
        moveBtn = pillControl(actionStrip, getString(R.string.cinema_file_move), () -> stageMarkedToClipboard(true));
        deleteBtn = pillControl(actionStrip, getString(R.string.cinema_file_delete), () -> confirmDeleteItems(List.copyOf(marked.values())));
        pasteBtn = pillControl(actionStrip, getString(R.string.cinema_file_paste), this::triggerPaste);
        cancelBarBtn = pillControl(actionStrip, getString(android.R.string.cancel), this::clearSelectionAndClipboard);

        status = text(this, CinemaTokens.LABEL, false);
        status.setTextColor(CinemaTokens.SECONDARY);
        status.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        page.addView(status);

        files = new RecyclerView(this);
        files.setId(View.generateViewId());
        files.setClipChildren(false);
        files.setClipToPadding(false);
        files.setPadding(dp(this, 8), dp(this, 12), dp(this, 8), dp(this, 16));
        files.setItemAnimator(null);
        adapter = new Files();
        files.setAdapter(adapter);
        FrameLayout filesViewport = new FrameLayout(this);
        filesViewport.setClipChildren(true);
        filesViewport.setClipToPadding(true);
        filesViewport.addView(files, new FrameLayout.LayoutParams(-1, -1));
        page.addView(filesViewport, new LinearLayout.LayoutParams(-1, 0, 1));

        refreshActionStrip();
        updateLayout();
        if (subtitlesOnly) {
            movieSourceFolderUri = resolveMovieSourceParent(getIntent().getStringExtra(EXTRA_INITIAL_VIDEO_URI));
            if (movieSourceFolderUri != null) {
                path.push(new Item(getString(R.string.cinema_sub_pick_movie_source), movieSourceFolderUri, true, 0L, 0L));
            }
        }
        load();
    }

    private static Uri resolveMovieSourceParent(String rawVideoUri) {
        if (rawVideoUri == null || rawVideoUri.isBlank()) return null;
        try {
            Uri uri = Uri.parse(rawVideoUri);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme) || "content".equals(scheme)) {
                return null;
            }
            if (scheme.isEmpty() && rawVideoUri.startsWith("/")) {
                java.io.File f = new java.io.File(rawVideoUri);
                java.io.File parent = f.isDirectory() ? f : f.getParentFile();
                return parent != null ? Uri.fromFile(parent) : null;
            }
            String s = uri.toString();
            int q = s.indexOf('?');
            if (q > 0) s = s.substring(0, q);
            while (s.endsWith("/") && s.length() > scheme.length() + 4) {
                s = s.substring(0, s.length() - 1);
            }
            int slash = s.lastIndexOf('/');
            if (slash > scheme.length() + 3) {
                return Uri.parse(s.substring(0, slash + 1));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void requestStoragePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                needed.add(android.Manifest.permission.READ_MEDIA_IMAGES);
            if (!imagesOnly && checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                needed.add(android.Manifest.permission.READ_MEDIA_VIDEO);
        } else if (Build.VERSION.SDK_INT < 29) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                needed.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 17);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 17 && files != null) load();
    }

    private TextView control(LinearLayout tools, String name, Runnable click) {
        TextView button = text(this, CinemaTokens.LABEL, true);
        button.setText(name);
        button.setPaddingRelative(dp(this, 14), 0, dp(this, 14), 0);
        button.setGravity(Gravity.CENTER);
        focusable(button);
        var params = new LinearLayout.LayoutParams(-2, dp(this, 44));
        params.setMarginEnd(dp(this, 8));
        tools.addView(button, params);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private TextView pillControl(LinearLayout bar, String name, Runnable click) {
        TextView btn = text(this, 13, true);
        btn.setText(name);
        btn.setGravity(Gravity.CENTER);
        btn.setPaddingRelative(dp(this, 12), 0, dp(this, 12), 0);
        focusable(btn);
        btn.setMinimumHeight(dp(this, 38));
        var lp = new LinearLayout.LayoutParams(-2, dp(this, 38));
        lp.setMarginStart(dp(this, 8));
        bar.addView(btn, lp);
        btn.setOnClickListener(v -> click.run());
        return btn;
    }

    private void refreshActionStrip() {
        boolean hasMarked = !marked.isEmpty();
        boolean hasClip = !clipboard.isEmpty();
        boolean insideFolder = !path.isEmpty();
        boolean showBar = imagesOnly || selectMode || hasMarked || hasClip;
        actionStrip.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (!showBar) return;

        if (hasClip && !hasMarked) {
            actionStripLabel.setText(getString(clipboardMove
                    ? R.string.cinema_file_clipboard_move_status : R.string.cinema_file_clipboard_copy_status,
                    clipboard.size()));
        } else if (hasMarked) {
            actionStripLabel.setText(getString(R.string.cinema_file_selected_status, marked.size()));
        } else if (imagesOnly) {
            actionStripLabel.setText(getString(R.string.cinema_art_pick_hint));
        } else {
            actionStripLabel.setText(getString(R.string.cinema_file_select_mode_hint));
        }

        useSelectedBtn.setVisibility(imagesOnly && hasMarked ? View.VISIBLE : View.GONE);
        copyBtn.setVisibility(hasMarked ? View.VISIBLE : View.GONE);
        moveBtn.setVisibility(hasMarked ? View.VISIBLE : View.GONE);
        deleteBtn.setVisibility(hasMarked ? View.VISIBLE : View.GONE);
        pasteBtn.setVisibility(hasClip ? View.VISIBLE : View.GONE);
        cancelBarBtn.setVisibility((hasMarked || hasClip || (selectMode && !imagesOnly)) ? View.VISIBLE : View.GONE);
    }

    private void clearSelectionAndClipboard() {
        marked.clear();
        clipboard = List.of();
        clipboardMove = false;
        selectMode = imagesOnly;
        adapter.notifyDataSetChanged();
        refreshActionStrip();
    }

    private void stageMarkedToClipboard(boolean move) {
        if (transferring || marked.isEmpty()) return;
        clipboard = List.copyOf(marked.values());
        clipboardMove = move;
        marked.clear();
        selectMode = imagesOnly;
        adapter.notifyDataSetChanged();
        refreshActionStrip();
        Toast.makeText(this, getString(move ? R.string.cinema_file_ready_to_move : R.string.cinema_file_ready_to_copy,
                clipboard.size()), Toast.LENGTH_SHORT).show();
    }

    private void stageItemsToClipboard(List<Item> items, boolean move) {
        if (transferring || items.isEmpty()) return;
        clipboard = List.copyOf(items);
        clipboardMove = move;
        marked.clear();
        selectMode = imagesOnly;
        adapter.notifyDataSetChanged();
        refreshActionStrip();
        Toast.makeText(this, getString(move ? R.string.cinema_file_ready_to_move : R.string.cinema_file_ready_to_copy,
                clipboard.size()), Toast.LENGTH_SHORT).show();
    }

    private void returnSelectedImages() {
        if (marked.isEmpty() || transferring) return;
        ArrayList<String> paths = new ArrayList<>();
        for (Uri uri : marked.keySet()) paths.add(uri.toString());
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(SELECTED_IMAGES, paths));
        finish();
    }

    private void triggerPaste() {
        if (transferring || clipboard.isEmpty()) return;
        Item currentFolder = path.peek();
        if (currentFolder != null) {
            executeCopyOrMove(List.copyOf(clipboard), currentFolder.uri(), clipboardMove);
            return;
        }
        // At root level, let user pick destination (Gallery or SAF Folder / USB)
        var destinations = new String[]{
                getString(R.string.cinema_photo_gallery),
                getString(R.string.cinema_photo_choose_folder)
        };
        dialog(this).setTitle(R.string.cinema_file_paste).setItems(destinations, (d, which) -> {
            if (which == 0) executeCopyOrMove(List.copyOf(clipboard), CinemaArtStorage.GALLERY, clipboardMove);
            else launchSafFolderPicker();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void launchSafFolderPicker() {
        try {
            safFolderPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
        } catch (android.content.ActivityNotFoundException missing) {
            Toast.makeText(this, R.string.cinema_photo_folder_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteItems(List<Item> targets) {
        if (transferring || targets.isEmpty()) return;
        String message = targets.size() == 1
                ? getString(R.string.cinema_file_delete_single_confirm, targets.get(0).name())
                : getString(R.string.cinema_file_delete_multi_confirm, targets.size());
        showDialogWithButtonFocus(dialog(this)
                .setTitle(R.string.cinema_file_delete)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cinema_file_delete, (d, w) -> executeDelete(targets)),
                android.content.DialogInterface.BUTTON_POSITIVE);
    }

    private void clearDeletedCaches(Item item) {
        if (item == null || item.uri() == null) return;
        thumbnails.remove(item.uri());
        try { Picasso.get().invalidate(item.uri()); } catch (Throwable ignored) {}
    }

    private Uri computeNeighborAfterDelete(List<Item> targets) {
        if (entries.isEmpty() || targets.isEmpty()) return null;
        java.util.HashSet<Uri> deleting = new java.util.HashSet<>();
        for (Item item : targets) deleting.add(item.uri());
        int anchor = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).uri().equals(selected)) {
                anchor = i;
                break;
            }
        }
        if (anchor < 0) {
            for (int i = 0; i < entries.size(); i++) {
                if (deleting.contains(entries.get(i).uri())) {
                    anchor = i;
                    break;
                }
            }
        }
        if (anchor < 0) anchor = 0;
        for (int i = anchor; i < entries.size(); i++) {
            if (!deleting.contains(entries.get(i).uri())) return entries.get(i).uri();
        }
        for (int i = anchor - 1; i >= 0; i--) {
            if (!deleting.contains(entries.get(i).uri())) return entries.get(i).uri();
        }
        return null;
    }

    private void showManageAllFilesPrompt() {
        showDialogWithButtonFocus(dialog(this)
                .setTitle(R.string.cinema_file_manage_permission_title)
                .setMessage(R.string.cinema_file_manage_permission_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cinema_file_enable_permission_now,
                        (d, w) -> TvPermissionsWizardDialog.requestManageAllFilesPermission(this, this::load)),
                android.content.DialogInterface.BUTTON_POSITIVE);
    }

    private void executeDelete(List<Item> targets) {
        transferring = true;
        Uri neighborAfterDelete = computeNeighborAfterDelete(targets);
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(this, 20);
        panel.setPadding(pad, dp(this, 10), pad, pad);
        TextView detail = text(this, 14, false);
        detail.setSingleLine();
        detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        panel.addView(detail);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(targets.size());
        progress.setProgressTintList(ColorStateList.valueOf(CinemaTokens.GOLD));
        var barLp = new LinearLayout.LayoutParams(-1, dp(this, 16));
        barLp.topMargin = dp(this, 10);
        panel.addView(progress, barLp);

        var modal = dialog(this).setTitle(R.string.cinema_file_deleting).setView(panel).setCancelable(false).show();
        folders.execute(() -> {
            int deleted = 0;
            boolean needsAllFilesPermission = false;
            List<Item> deletedItems = new ArrayList<>();
            List<Item> usbSafNeeded = new ArrayList<>();
            List<Uri> mediaStoreUris = new ArrayList<>();
            List<Item> mediaStoreItems = new ArrayList<>();
            for (Item item : targets) {
                if (Thread.currentThread().isInterrupted()) break;
                runOnUiThread(() -> detail.setText(item.name()));
                try {
                    var outcome = CinemaFileOperations.deleteItemDetailed(this, item);
                    switch (outcome.status()) {
                        case DELETED -> {
                            deleted++;
                            deletedItems.add(item);
                        }
                        case NEEDS_USB_SAF -> usbSafNeeded.add(item);
                        case NEEDS_MEDIASTORE_DELETE -> {
                            if (outcome.mediaStoreUri() != null) {
                                mediaStoreUris.add(outcome.mediaStoreUri());
                                mediaStoreItems.add(item);
                            } else {
                                needsAllFilesPermission = true;
                            }
                        }
                        case NEEDS_ALL_FILES_PERMISSION -> needsAllFilesPermission = true;
                        default -> {}
                    }
                } catch (Exception ignored) {}
                runOnUiThread(() -> progress.incrementProgressBy(1));
            }
            int done = deleted;
            boolean askAllFiles = needsAllFilesPermission;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                transferring = false;
                modal.dismiss();
                for (Item item : deletedItems) {
                    clearDeletedCaches(item);
                    marked.remove(item.uri());
                }
                if (done > 0) {
                    selected = neighborAfterDelete;
                    if (marked.isEmpty() && !imagesOnly) selectMode = false;
                    refreshActionStrip();
                    load();
                }
                if (!usbSafNeeded.isEmpty()) {
                    pendingSafDeleteTargets = List.copyOf(usbSafNeeded);
                    showDialogWithButtonFocus(dialog(this)
                            .setTitle(R.string.cinema_file_usb_permission_title)
                            .setMessage(R.string.cinema_file_usb_delete_permission_message)
                            .setNegativeButton(android.R.string.cancel, (d, w) -> pendingSafDeleteTargets = List.of())
                            .setPositiveButton(R.string.cinema_photo_choose_folder, (d, w) -> launchSafFolderPicker()),
                            android.content.DialogInterface.BUTTON_POSITIVE);
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !mediaStoreUris.isEmpty()) {
                    try {
                        PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), mediaStoreUris);
                        pendingMediaStoreDeleteTargets = List.copyOf(mediaStoreItems);
                        pendingMediaStorePriorDeleted = done;
                        pendingMediaStoreTotalTargets = targets.size();
                        pendingMediaStoreNeighbor = neighborAfterDelete;
                        mediaStoreDeleteLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
                        return;
                    } catch (Throwable unsupportedOnTv) {
                        pendingMediaStoreDeleteTargets = List.of();
                        showManageAllFilesPrompt();
                        return;
                    }
                }
                if (askAllFiles && done < targets.size()) {
                    showManageAllFilesPrompt();
                    return;
                }
                if (done == 0) {
                    if (marked.isEmpty() && !imagesOnly) selectMode = false;
                    refreshActionStrip();
                    load();
                }
                Toast.makeText(this, getString(R.string.cinema_file_delete_result, done, targets.size()),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void executeCopyOrMove(List<Item> sources, Uri destFolder, boolean move) {
        transferring = true;
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(this, 20);
        panel.setPadding(pad, dp(this, 10), pad, pad);
        TextView detail = text(this, 14, false);
        detail.setSingleLine();
        detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        panel.addView(detail);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(ColorStateList.valueOf(CinemaTokens.GOLD));
        progress.setIndeterminateTintList(ColorStateList.valueOf(CinemaTokens.GOLD));
        var barLp = new LinearLayout.LayoutParams(-1, dp(this, 16));
        barLp.topMargin = dp(this, 10);
        panel.addView(progress, barLp);

        int titleRes = move ? R.string.cinema_file_moving : R.string.cinema_file_copying;
        var modal = dialog(this).setTitle(titleRes).setView(panel).setCancelable(false).show();
        folders.execute(() -> {
            int total = CinemaFileOperations.countUnits(this, sources);
            int[] counter = {0};
            int completedSources = 0;
            boolean needsSafPermission = false;
            runOnUiThread(() -> {
                progress.setIndeterminate(false);
                progress.setMax(total);
            });
            for (Item source : sources) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    CinemaFileOperations.copyOrMoveItem(this, source, destFolder, move, counter, total,
                            (name, done, max) -> runOnUiThread(() -> {
                                detail.setText(name + " (" + done + " / " + max + ")");
                                progress.setProgress(done);
                            }));
                    completedSources++;
                } catch (SecurityException usbDenied) {
                    needsSafPermission = true;
                    break;
                } catch (Exception ignored) {}
            }
            int done = completedSources;
            boolean askSaf = needsSafPermission;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                transferring = false;
                modal.dismiss();
                if (askSaf) {
                    boolean isUsbDest = "file".equalsIgnoreCase(destFolder.getScheme())
                            && destFolder.getPath() != null
                            && CinemaFileOperations.isExternalRemovableFile(new java.io.File(destFolder.getPath()));
                    if (!isUsbDest && !TvPermissionsWizardDialog.hasManageAllFilesPermission(this)) {
                        showManageAllFilesPrompt();
                    } else {
                        showDialogWithButtonFocus(dialog(this)
                                .setTitle(R.string.cinema_file_usb_permission_title)
                                .setMessage(R.string.cinema_file_usb_permission_message)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.cinema_photo_choose_folder, (d, w) -> launchSafFolderPicker()),
                                android.content.DialogInterface.BUTTON_POSITIVE);
                    }
                    return;
                }
                if (done == sources.size()) {
                    clipboard = List.of();
                    clipboardMove = false;
                }
                refreshActionStrip();
                load();
                Toast.makeText(this, getString(move ? R.string.cinema_file_move_result : R.string.cinema_file_copy_result,
                        done, sources.size()), Toast.LENGTH_LONG).show();
            });
        });
    }

    private void showLongPressMenu(Item entry) {
        if (transferring || path.isEmpty()) return;
        boolean isMarked = marked.containsKey(entry.uri());
        List<Item> activeTargets = !marked.isEmpty()
                ? (isMarked ? List.copyOf(marked.values()) : mergeWithItem(marked, entry))
                : List.of(entry);

        CinemaFileOperations.showItemActionSheet(this, entry, isMarked, marked.size(),
                !clipboard.isEmpty(), !path.isEmpty(),
                new CinemaFileOperations.SheetCallbacks() {
                    @Override public void onToggleSelect() {
                        selectMode = true;
                        toggleMarked(entry);
                    }
                    @Override public void onSelectAll() {
                        selectMode = true;
                        for (Item item : entries) {
                            if (imagesOnly && !item.isImage()) continue;
                            marked.put(item.uri(), item);
                        }
                        adapter.notifyDataSetChanged();
                        refreshActionStrip();
                    }
                    @Override public void onClearSelection() {
                        marked.clear();
                        if (!imagesOnly) selectMode = false;
                        adapter.notifyDataSetChanged();
                        refreshActionStrip();
                    }
                    @Override public void onCopy() {
                        stageItemsToClipboard(activeTargets, false);
                    }
                    @Override public void onMove() {
                        stageItemsToClipboard(activeTargets, true);
                    }
                    @Override public void onPaste() {
                        triggerPaste();
                    }
                    @Override public void onDelete() {
                        confirmDeleteItems(activeTargets);
                    }
                });
    }

    private static List<Item> mergeWithItem(LinkedHashMap<Uri, Item> existing, Item extra) {
        var list = new ArrayList<>(existing.values());
        if (!existing.containsKey(extra.uri())) list.add(extra);
        return list;
    }

    private void toggleMarked(Item entry) {
        if (marked.remove(entry.uri()) == null) marked.put(entry.uri(), entry);
        if (marked.isEmpty() && !imagesOnly) selectMode = false;
        int idx = entries.indexOf(entry);
        if (idx >= 0) adapter.notifyItemChanged(idx);
        else adapter.notifyDataSetChanged();
        refreshActionStrip();
    }

    private String layoutLabel() {
        return grid ? getString(R.string.cinema_list_view) : getString(R.string.cinema_grid_view);
    }

    private void updateLayout() {
        int columns = Math.max(3, Math.min(6, (getResources().getDisplayMetrics().widthPixels - dp(this, 104)) / dp(this, 140)));
        files.setLayoutManager(new GridLayoutManager(this, grid ? columns : 1));
        adapter.notifyDataSetChanged();
        focusEntry(generation);
    }

    private void sortMenu() {
        dialog(this).setTitle(getString(R.string.cinema_sort_files)).setItems(new String[]{
                getString(R.string.cinema_name_a_z), getString(R.string.cinema_name_z_a),
                getString(R.string.cinema_newest_first), getString(R.string.cinema_oldest_first),
                getString(R.string.cinema_largest_first)
        }, (d, which) -> {
            sort = which;
            PreferenceManager.getDefaultSharedPreferences(this).edit().putInt("cinema_files_sort", sort).apply();
            orderEntries();
            adapter.notifyDataSetChanged();
        }).show();
    }

    private void load() {
        int token = ++generation;
        Item folder = path.peek();
        location.setText(folder == null ? getString(R.string.cinema_file_browser) : buildBreadcrumb());
        entries.clear();
        adapter.notifyDataSetChanged();
        status.setText(getString(R.string.cinema_loading_files));
        folders.execute(() -> {
            List<Item> result = new ArrayList<>();
            String error = null;
            try {
                if (folder == null) {
                    result.addAll(CinemaFileOperations.listRootSources(this, imagesOnly));
                    if (subtitlesOnly) result.removeIf(item -> CinemaArtStorage.GALLERY.equals(item.uri()));
                } else if (CinemaArtStorage.GALLERY.equals(folder.uri())) {
                    for (var photo : CinemaArtStorage.gallery(this)) {
                        result.add(new Item(photo.name(), photo.uri(), false, photo.changed(), photo.size()));
                    }
                } else {
                    boolean scanCompanionSubs = subtitlesOnly && movieSourceFolderUri != null
                            && movieSourceFolderUri.equals(folder.uri());
                    List<com.archos.filecorelibrary.MetaFile2> subsDirs = new ArrayList<>();
                    for (var file : RawListerFactoryWithUpnp.getRawListerForUrl(folder.uri()).getFileList()) {
                        String name = file.getName();
                        if (name == null || ".".equals(name) || "..".equals(name)) continue;
                        boolean directory = file.isDirectory();
                        if (imagesOnly && !directory && !CinemaFileOperations.isImageName(name)) continue;
                        if (subtitlesOnly && !directory && !CinemaFileOperations.isSubtitleName(name)) continue;
                        result.add(new Item(name, file.getUri(), directory, file.lastModified(),
                                directory ? 0 : file.getComputedLength()));
                        if (scanCompanionSubs && directory
                                && (name.equalsIgnoreCase("subs") || name.equalsIgnoreCase("subtitles")
                                || name.equalsIgnoreCase("sub"))) {
                            subsDirs.add(file);
                        }
                    }
                    // Surface subtitle files inside companion Subs/ / Subtitles/ folders immediately
                    for (var subDir : subsDirs) {
                        try {
                            for (var subFile : RawListerFactoryWithUpnp.getRawListerForUrl(subDir.getUri()).getFileList()) {
                                String sn = subFile.getName();
                                if (sn == null || subFile.isDirectory() || !CinemaFileOperations.isSubtitleName(sn)) continue;
                                result.add(new Item(subDir.getName() + "/" + sn, subFile.getUri(), false,
                                        subFile.lastModified(), subFile.getComputedLength()));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception failure) {
                error = getString(R.string.cinema_could_not_read_this_source_check_permissions_and_connection);
            }
            String message = error;
            runOnUiThread(() -> {
                if (isDestroyed() || token != generation) return;
                entries.addAll(result);
                if (folder != null) orderEntries();
                adapter.notifyDataSetChanged();
                status.setText(message != null ? message
                        : entries.isEmpty() ? getString(R.string.cinema_empty_folder)
                        : (!path.isEmpty() ? getString(R.string.cinema_file_long_press_hint) : ""));
                refreshActionStrip();
                focusEntry(token);
            });
        });
    }

    private String buildBreadcrumb() {
        var names = new ArrayList<String>();
        for (Item item : path) names.add(0, item.name());
        return TextUtils.join("  ›  ", names);
    }

    private void orderEntries() {
        Comparator<Item> name = Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Item> order = switch (sort) {
            case 1 -> name.reversed();
            case 2 -> Comparator.comparingLong(Item::modified).reversed();
            case 3 -> Comparator.comparingLong(e -> e.modified() > 0 ? e.modified() : Long.MAX_VALUE);
            case 4 -> Comparator.comparingLong(Item::size).reversed();
            default -> name;
        };
        entries.sort(Comparator.comparing(Item::directory).reversed().thenComparing(order).thenComparing(name));
    }

    private void focusEntry(int token) {
        if (entries.isEmpty()) {
            if (backButton != null && viewer == null) backButton.requestFocus();
            return;
        }
        int position = 0;
        for (int n = 0; n < entries.size(); n++) {
            if (entries.get(n).uri().equals(selected)) { position = n; break; }
        }
        int target = position;
        files.scrollToPosition(target);
        androidx.core.view.OneShotPreDrawListener.add(files, () -> {
            if (token != generation || viewer != null) return;
            var holder = files.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocusFromTouch();
        });
    }

    private void open(Item entry) {
        if (selectMode && !path.isEmpty() && (!entry.directory() || !imagesOnly)) {
            if (entry.directory() && imagesOnly) {
                enterDirectory(entry);
            } else {
                toggleMarked(entry);
            }
            return;
        }
        if (entry.directory()) {
            enterDirectory(entry);
        } else if (subtitlesOnly && entry.isSubtitle()) {
            setResultAndFinish(entry.uri());
        } else if (imagesOnly) {
            toggleMarked(entry);
        } else if (entry.isImage()) {
            photos = entries.stream().filter(Item::isImage).collect(java.util.stream.Collectors.toList());
            photoIndex = Math.max(0, photos.indexOf(entry));
            showViewer();
        } else if (entry.isVideo()) {
            setResultAndFinish(entry.uri());
        } else {
            Toast.makeText(this, getString(R.string.cinema_this_file_type_cannot_be_opened), Toast.LENGTH_SHORT).show();
        }
    }

    private void enterDirectory(Item entry) {
        path.push(entry);
        selected = null;
        if (!imagesOnly) {
            marked.clear();
            selectMode = false;
        }
        load();
    }

    private void setResultAndFinish(Uri uri) {
        Intent result = new Intent().setData(uri);
        if (uri == null) result.putExtra("system_picker", true);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void showViewer() {
        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(0xff000000);
        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        photoCaption = text(this, CinemaTokens.LABEL, true);
        photoCaption.setBackgroundColor(0xaa000000);
        photoCaption.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 8));
        viewer.addView(photoCaption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        viewer.setFocusableInTouchMode(true);
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        viewer.requestFocus();
        loadPhoto();
    }

    private void loadPhoto() {
        int token = ++imageGeneration;
        Item entry = photos.get(photoIndex);
        selected = entry.uri();
        zoom = 1;
        picture.setImageDrawable(null);
        transformPhoto(0, 0);
        photoCaption.setText(entry.name() + "   " + (photoIndex + 1) + " / " + photos.size()
                + "   ·   " + getString(R.string.cinema_ch_ch_to_zoom));
        thumbs.execute(() -> {
            Bitmap bitmap = null;
            try { bitmap = CinemaArtStorage.decode(this, entry.uri(), 3840); } catch (Exception ignored) {}
            Bitmap decoded = bitmap;
            runOnUiThread(() -> {
                if (token != imageGeneration || viewer == null) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                if (decoded != null) picture.setImageBitmap(decoded);
                else photoCaption.setText(getString(R.string.cinema_could_not_open_image) + " · " + entry.name());
            });
        });
    }

    private void transformPhoto(float x, float y) {
        picture.setScaleX(zoom);
        picture.setScaleY(zoom);
        var drawable = picture.getDrawable();
        float maxX = 0, maxY = 0;
        if (drawable != null) {
            float fit = Math.min((float) picture.getWidth() / drawable.getIntrinsicWidth(),
                    (float) picture.getHeight() / drawable.getIntrinsicHeight());
            maxX = Math.max(0, (drawable.getIntrinsicWidth() * fit * zoom - picture.getWidth()) / 2);
            maxY = Math.max(0, (drawable.getIntrinsicHeight() * fit * zoom - picture.getHeight()) / 2);
        }
        picture.setTranslationX(Math.max(-maxX, Math.min(maxX, x)));
        picture.setTranslationY(Math.max(-maxY, Math.min(maxY, y)));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (viewer == null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_MENU
                    && !path.isEmpty()) {
                View focus = getCurrentFocus();
                var holder = focus == null ? null : files.findContainingViewHolder(focus);
                if (holder != null && holder.getBindingAdapterPosition() >= 0
                        && holder.getBindingAdapterPosition() < entries.size()) {
                    showLongPressMenu(entries.get(holder.getBindingAdapterPosition()));
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (key) {
            case KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zoom = Math.max(1, Math.min(4, zoom + (key == KeyEvent.KEYCODE_CHANNEL_UP ? .25f : -.25f)));
                transformPhoto(picture.getTranslationX(), picture.getTranslationY());
            }
            case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (zoom > 1) {
                    int step = dp(this, 32);
                    transformPhoto(picture.getTranslationX() + (key == KeyEvent.KEYCODE_DPAD_LEFT ? step : key == KeyEvent.KEYCODE_DPAD_RIGHT ? -step : 0),
                            picture.getTranslationY() + (key == KeyEvent.KEYCODE_DPAD_UP ? step : key == KeyEvent.KEYCODE_DPAD_DOWN ? -step : 0));
                } else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    if (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) direction = -direction;
                    int next = photoIndex + direction;
                    if (next >= 0 && next < photos.size()) { photoIndex = next; loadPhoto(); }
                }
            }
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { zoom = 1; transformPhoto(0, 0); }
            default -> { return super.dispatchKeyEvent(event); }
        }
        return true;
    }

    @Override public void onBackPressed() {
        if (viewer != null) {
            imageGeneration++;
            root.removeView(viewer);
            viewer = null;
            picture = null;
            focusEntry(generation);
        } else if (!imagesOnly && selectMode) {
            marked.clear();
            selectMode = false;
            adapter.notifyDataSetChanged();
            refreshActionStrip();
        } else if (!path.isEmpty()) {
            selected = path.pop().uri();
            load();
        } else {
            super.onBackPressed();
        }
    }

    @Override public void onDestroy() {
        generation++;
        imageGeneration++;
        folders.shutdownNow();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    private final class Files extends RecyclerView.Adapter<FileCard> {
        @Override public int getItemCount() { return entries.size(); }
        @Override public int getItemViewType(int position) { return grid ? 1 : 0; }

        @Override public FileCard onCreateViewHolder(ViewGroup parent, int mode) {
            boolean tile = mode == 1;
            FrameLayout cell = new FrameLayout(CinemaFileBrowserActivity.this);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            cell.setPadding(dp(CinemaFileBrowserActivity.this, 6), dp(CinemaFileBrowserActivity.this, 6),
                    dp(CinemaFileBrowserActivity.this, 6), dp(CinemaFileBrowserActivity.this, 6));
            cell.setLayoutParams(new RecyclerView.LayoutParams(-1,
                    dp(CinemaFileBrowserActivity.this, tile ? 142 : 60)));

            FrameLayout card = new FrameLayout(CinemaFileBrowserActivity.this);
            card.setFocusable(true);
            card.setClickable(true);
            card.setLongClickable(true);
            card.setClipToOutline(true);
            card.setBackground(rounded(0xFF1F1E1C, dp(CinemaFileBrowserActivity.this, 12), 0xFF3A342B));
            cell.addView(card, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout content = new LinearLayout(CinemaFileBrowserActivity.this);
            content.setOrientation(tile ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            content.setGravity(tile ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            content.setPadding(dp(CinemaFileBrowserActivity.this, 8), dp(CinemaFileBrowserActivity.this, 8),
                    dp(CinemaFileBrowserActivity.this, 8), dp(CinemaFileBrowserActivity.this, 8));
            card.addView(content, new FrameLayout.LayoutParams(-1, -1));

            ImageView icon = new ImageView(CinemaFileBrowserActivity.this);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setClipToOutline(true);
            int iconW = dp(CinemaFileBrowserActivity.this, tile ? 88 : 40);
            int iconH = dp(CinemaFileBrowserActivity.this, tile ? 68 : 36);
            content.addView(icon, new LinearLayout.LayoutParams(iconW, iconH));

            TextView name = text(CinemaFileBrowserActivity.this, tile ? 13 : 15, true);
            name.setMaxLines(tile ? 2 : 1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(tile ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
            name.setPaddingRelative(dp(CinemaFileBrowserActivity.this, tile ? 4 : 12),
                    dp(CinemaFileBrowserActivity.this, tile ? 6 : 0),
                    dp(CinemaFileBrowserActivity.this, 4), 0);
            content.addView(name, new LinearLayout.LayoutParams(tile ? -1 : 0, -2, tile ? 0 : 1));

            TextView checkBadge = text(CinemaFileBrowserActivity.this, 12, true);
            checkBadge.setText("✓");
            checkBadge.setTextColor(0xFF141517);
            checkBadge.setGravity(Gravity.CENTER);
            checkBadge.setBackground(rounded(CinemaTokens.GOLD, dp(CinemaFileBrowserActivity.this, 11), 0));
            checkBadge.setVisibility(View.GONE);
            var badgeLp = new FrameLayout.LayoutParams(dp(CinemaFileBrowserActivity.this, 22),
                    dp(CinemaFileBrowserActivity.this, 22), Gravity.TOP | Gravity.END);
            badgeLp.setMargins(dp(CinemaFileBrowserActivity.this, 6), dp(CinemaFileBrowserActivity.this, 6),
                    dp(CinemaFileBrowserActivity.this, 6), dp(CinemaFileBrowserActivity.this, 6));
            card.addView(checkBadge, badgeLp);

            return new FileCard(cell, card, icon, name, checkBadge);
        }

        @Override public void onBindViewHolder(FileCard holder, int position) {
            Item entry = entries.get(position);
            boolean checked = marked.containsKey(entry.uri());
            holder.name.setText(entry.name());
            holder.checkBadge.setVisibility(checked ? View.VISIBLE : View.GONE);
            holder.card.setBackground(rounded(checked ? 0xFF2D2518 : 0xFF1F1E1C,
                    dp(CinemaFileBrowserActivity.this, 12), checked ? CinemaTokens.GOLD : 0xFF3A342B));
            holder.card.setContentDescription(checked
                    ? getString(R.string.cinema_photo_marked, entry.name()) : entry.name());

            if (holder.work != null) holder.work.cancel(false);
            holder.uri = entry.uri();
            Picasso.get().cancelRequest(holder.icon);
            holder.icon.setColorFilter(null);

            int fallbackIcon = entry.directory() ? R.drawable.cinema_folder
                    : entry.isSubtitle() ? R.drawable.cinema_subtitles
                    : entry.isImage() ? android.R.drawable.ic_menu_gallery : R.drawable.cinema_play_movie;
            if (entry.directory()) {
                holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.icon.setImageResource(fallbackIcon);
                holder.icon.setColorFilter(CinemaTokens.GOLD);
            } else if (entry.isSubtitle()) {
                holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.icon.setImageResource(R.drawable.cinema_subtitles);
                holder.icon.setColorFilter(CinemaTokens.GOLD);
            } else if (entry.isImage()) {
                holder.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Bitmap cached = thumbnails.get(entry.uri());
                if (cached != null) {
                    holder.icon.setImageBitmap(cached);
                } else if ("file".equalsIgnoreCase(entry.uri().getScheme()) || "content".equalsIgnoreCase(entry.uri().getScheme())) {
                    int w = dp(CinemaFileBrowserActivity.this, grid ? 96 : 48);
                    int h = dp(CinemaFileBrowserActivity.this, grid ? 72 : 40);
                    Picasso.get().load(entry.uri()).resize(w, h).centerCrop().placeholder(fallbackIcon).error(fallbackIcon).into(holder.icon);
                } else {
                    holder.icon.setImageResource(fallbackIcon);
                    holder.work = thumbs.submit(() -> {
                        try {
                            Bitmap bmp = CinemaArtStorage.decode(CinemaFileBrowserActivity.this, entry.uri(), 192);
                            if (bmp == null) return;
                            thumbnails.put(entry.uri(), bmp);
                            runOnUiThread(() -> {
                                if (entry.uri().equals(holder.uri) && !isDestroyed()) holder.icon.setImageBitmap(bmp);
                            });
                        } catch (Exception ignored) {}
                    });
                }
            } else if (entry.isVideo()) {
                holder.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Bitmap cached = thumbnails.get(entry.uri());
                if (cached != null) {
                    holder.icon.setImageBitmap(cached);
                } else {
                    holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    holder.icon.setImageResource(fallbackIcon);
                    holder.icon.setColorFilter(CinemaTokens.GOLD);
                    if ("file".equalsIgnoreCase(entry.uri().getScheme())) {
                        holder.work = thumbs.submit(() -> {
                            Bitmap bmp = CinemaFileOperations.loadVideoThumbnail(CinemaFileBrowserActivity.this, entry.uri(), 220);
                            if (bmp == null) return;
                            thumbnails.put(entry.uri(), bmp);
                            runOnUiThread(() -> {
                                if (entry.uri().equals(holder.uri) && !isDestroyed()) {
                                    holder.icon.setColorFilter(null);
                                    holder.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                    holder.icon.setImageBitmap(bmp);
                                }
                            });
                        });
                    }
                }
            } else {
                holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.icon.setImageResource(fallbackIcon);
                holder.icon.setColorFilter(0xFFB5ADA0);
            }

            holder.card.setOnFocusChangeListener((view, focused) -> {
                GradientDrawable ring = rounded(Color.TRANSPARENT, dp(CinemaFileBrowserActivity.this, 12), 0);
                ring.setStroke(dp(CinemaFileBrowserActivity.this, 3), CinemaTokens.GOLD);
                view.setForeground(focused ? ring : null);
                view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(motion(CinemaFileBrowserActivity.this) ? CinemaTokens.FOCUS_MS : 0).start();
                if (focused) selected = entry.uri();
            });
            holder.card.setOnClickListener(v -> open(entry));
            holder.card.setOnLongClickListener(v -> {
                if (path.isEmpty()) return false;
                showLongPressMenu(entry);
                return true;
            });
        }

        @Override public void onViewRecycled(FileCard holder) {
            Picasso.get().cancelRequest(holder.icon);
            if (holder.work != null) holder.work.cancel(false);
            holder.uri = null;
        }
    }

    private static final class FileCard extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final ImageView icon;
        final TextView name;
        final TextView checkBadge;
        Uri uri;
        Future<?> work;

        FileCard(View cell, FrameLayout card, ImageView icon, TextView name, TextView checkBadge) {
            super(cell);
            this.card = card;
            this.icon = icon;
            this.name = name;
            this.checkBadge = checkBadge;
        }
    }
}
