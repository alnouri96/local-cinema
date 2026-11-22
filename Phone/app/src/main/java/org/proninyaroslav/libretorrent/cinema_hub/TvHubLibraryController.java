package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

final class TvHubLibraryController {
    final TvHubActivity host;

    LinearLayout libraryPage;
    java.io.File libraryFolder;
    int librarySectionTab; // 0 = Files, 1 = Continue Watching, 2 = Watched
    String libraryPlaybackKey;
    String libraryPlaybackPath;
    MobileLibraryServer libraryServer;
    String libraryHost;
    com.google.android.material.bottomsheet.BottomSheetDialog usbSheet;
    LinearLayout usbOptions;
    private android.os.FileObserver folderObserver;
    private java.io.File observedFolder;
    private final Runnable refreshChangedFolder = this::refreshObservedFolder;

    private void refreshObservedFolder() {
        if (host.hubForeground && libraryFolder != null) refreshFiles(libraryFolder);
    }

    final android.content.BroadcastReceiver storageChanges = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (libraryPage != null) showLibrary();
            if (usbSheet != null && usbSheet.isShowing()) populateUsbStorage();
        }
    };

    TvHubLibraryController(TvHubActivity host) {
        this.host = host;
    }

    boolean isReady() {
        return libraryPage != null;
    }

    void refreshIfReady() {
        if (libraryPage == null) return;
        var root = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Local Cinema");
        host.watchSyncIo.execute(() -> {
            PhoneWatchStore.refreshLibraryFiles(host, root, null, null);
            for (var drive : CinemaStorage.drives(host)) {
                PhoneWatchStore.refreshLibraryFiles(host, drive.library(), null, null);
            }
            host.main.post(() -> { if (host.hubForeground && !host.isDestroyed()) showLibrary(); });
        });
        showLibrary();
    }

    void stopServer() {
        if (libraryServer != null) {
            libraryServer.stop();
            libraryServer = null;
            libraryHost = null;
        }
    }

    void stopObserving() {
        if (folderObserver != null) folderObserver.stopWatching();
        folderObserver = null;
        observedFolder = null;
        host.main.removeCallbacks(refreshChangedFolder);
    }

    @SuppressWarnings("deprecation")
    private void observeFolder(java.io.File folder) {
        if (!host.hubForeground || folder.equals(observedFolder)) return;
        stopObserving();
        observedFolder = folder;
        folderObserver = new android.os.FileObserver(folder.getAbsolutePath(),
                android.os.FileObserver.CREATE | android.os.FileObserver.DELETE
                        | android.os.FileObserver.DELETE_SELF | android.os.FileObserver.MOVE_SELF
                        | android.os.FileObserver.MOVED_FROM | android.os.FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) {
                host.main.removeCallbacks(refreshChangedFolder);
                host.main.postDelayed(refreshChangedFolder, 350);
            }
        };
        folderObserver.startWatching();
    }

    void showLibrary() {
        libraryPage.removeAllViews();
        var sectionScroll = new android.widget.HorizontalScrollView(host);
        sectionScroll.setHorizontalScrollBarEnabled(false);
        sectionScroll.setFillViewport(false);
        var sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = host.dp(10);
        libraryPage.addView(sectionScroll, sectionLp);
        var sectionTabs = new LinearLayout(host);
        sectionTabs.setOrientation(LinearLayout.HORIZONTAL);
        sectionScroll.addView(sectionTabs);
        sectionTabs.setGravity(Gravity.CENTER_VERTICAL);
        String[] tabTitles = new String[]{
                host.getString(org.proninyaroslav.libretorrent.R.string.cinema_files_tab),
                host.getString(org.proninyaroslav.libretorrent.R.string.cinema_continue_watching_tab),
                host.getString(org.proninyaroslav.libretorrent.R.string.cinema_watched_tab),
                host.l("قائمة المشاهدة", "Watchlist")
        };
        for (int i = 0; i < tabTitles.length; i++) {
            final int tabIdx = i;
            boolean activeTab = librarySectionTab == tabIdx;
            var btn = host.button((activeTab ? "● " : "") + tabTitles[tabIdx], () -> {
                librarySectionTab = tabIdx;
                showLibrary();
            }, activeTab);
            btn.setSingleLine(true);
            btn.setMaxLines(1);
            btn.setPadding(host.dp(14), host.dp(6), host.dp(14), host.dp(6));
            btn.setMinimumWidth(0);
            btn.setMinWidth(0);
            var tabBtnLp = new LinearLayout.LayoutParams(-2, -2);
            if (tabIdx > 0) tabBtnLp.setMarginStart(host.dp(8));
            sectionTabs.addView(btn, tabBtnLp);
        }
        if (librarySectionTab != 0) stopObserving();
        if (librarySectionTab == 1 || librarySectionTab == 2) {
            showWatchSection(librarySectionTab == 2);
            return;
        }
        if (librarySectionTab == 3) {
            showWatchlistSection();
            return;
        }
        var root = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Local Cinema");
        var phone = root;
        var activeDrive = CinemaStorage.active(host);
        if (libraryFolder == null) libraryFolder = phone;
        if (!withinLibrary(libraryFolder, phone)) {
            if (activeDrive != null && withinLibrary(libraryFolder, activeDrive.library())) {
                root = activeDrive.library();
            } else {
                libraryFolder = phone;
            }
        }
        while (!libraryFolder.equals(root) && !libraryFolder.isDirectory()) libraryFolder = libraryFolder.getParentFile();
        final var navigationRoot = root;
        observeFolder(libraryFolder);
        if (!libraryFolder.equals(root)) {
            var trail = new java.util.ArrayList<java.io.File>();
            for (var folder = libraryFolder; folder != null && !folder.equals(root); folder = folder.getParentFile()) {
                trail.add(0, folder);
            }
            var breadcrumbs = host.row(libraryPage);
            host.add(breadcrumbs, "‹ Back", () -> openLibraryFolder(libraryFolder.getParentFile()));
            host.add(breadcrumbs, "Local Cinema", () -> openLibraryFolder(navigationRoot));
            for (var folder : trail) {
                if (folder.equals(libraryFolder)) libraryPage.addView(host.label(folder.getName(), 18));
                else libraryPage.addView(host.button(folder.getName(), () -> openLibraryFolder(folder), false));
            }
        } else {
            libraryPage.addView(host.label("Local Cinema", 20));
        }
        var files = libraryFolder.listFiles();
        if (files == null) {
            libraryPage.addView(host.label("Allow storage access to browse this folder.", 15));
        } else {
            java.util.Arrays.sort(files, java.util.Comparator
                    .comparing((java.io.File file) -> !file.isDirectory())
                    .thenComparing(java.io.File::getName, String.CASE_INSENSITIVE_ORDER));
            int visible = 0;
            for (var file : files) {
                if (file.isHidden() || (!file.isDirectory() && !isLibraryVideo(file))) continue;
                if (!file.isDirectory() && libraryProgress().getBoolean("hidden:" + libraryKey(file), false)) continue;
                visible++;
                if (file.isDirectory()) {
                    var entries = file.listFiles(child -> !child.isHidden() && (child.isDirectory() || isLibraryVideo(child)));
                    var folder = folderCard(file, libraryFolder.equals(navigationRoot));
                    folder.addView(host.label(entries == null ? "Folder" : entries.length + " items", 14));
                    folder.setOnClickListener(view -> openLibraryFolder(file));
                    if (canManage(file, navigationRoot)) folder.setOnLongClickListener(view -> {
                        showFileActions(file, navigationRoot, new java.util.ArrayList<>(), new java.util.ArrayList<>());
                        return true;
                    });
                    folder.setFocusable(true);
                } else {
                    var media = host.card(libraryPage, file.getName());
                    var details = new java.util.ArrayList<String>();
                    String name = file.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("2160p")) details.add("4K");
                    if (name.contains("dovi")) details.add("DoVi");
                    if (name.contains("h 265") || name.contains("hevc") || name.contains("h265")) details.add("HEVC");
                    if (name.contains("web-dl")) details.add("WEB-DL");
                    details.add(android.text.format.Formatter.formatFileSize(host, file.length()));
                    media.addView(host.label(String.join(" · ", details), 14));
                    var uri = android.net.Uri.fromFile(file);
                    String libKey = libraryKey(file);
                    String watchKey = "lib:" + libKey;
                    var entry = PhoneWatchStore.getEntry(host, watchKey);
                    boolean isWatched = entry != null && entry.watched;
                    long durMs = entry != null ? entry.durationMs : 0L;
                    int saved = entry != null && !entry.watched && entry.positionMs > 0
                            ? (int) Math.min(Integer.MAX_VALUE, entry.positionMs)
                            : libraryProgress().getInt(libKey, 0);
                    if (isWatched) {
                        var badge = host.label("✓ " + host.getString(org.proninyaroslav.libretorrent.R.string.cinema_watched_badge), 13);
                        badge.setTextColor(TvHubActivity.GOLD);
                        media.addView(badge);
                    } else if (saved > 0 && durMs > 0) {
                        var bar = new android.widget.ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
                        bar.setMax(100);
                        bar.setProgress((int) Math.min(100, saved * 100L / durMs));
                        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(TvHubActivity.GOLD));
                        media.addView(bar, new LinearLayout.LayoutParams(-1, host.dp(4)));
                    }
                    Runnable play = () -> host.castVideo(uri, -1, false);
                    var cast = host.button(saved > 0 && !isWatched ? "Resume · " + TvHubActivity.formatTime(saved) : "Cast", play, true);
                    var longPress = (android.view.View.OnLongClickListener) view -> {
                        showLibraryItemMenu(file, watchKey, file.getName(), uri.toString(), isWatched, saved > 0);
                        return true;
                    };
                    cast.setOnLongClickListener(longPress);
                    media.addView(cast);
                    media.setOnClickListener(view -> play.run());
                    media.setOnLongClickListener(longPress);
                }
            }
            if (visible == 0) libraryPage.addView(host.label("No videos or folders here.", 15));
        }
        if (libraryFolder.equals(phone)) {
            libraryPage.addView(host.button(host.l("تصفح USB / وحدة تخزين", "Browse USB / other storage"),
                    this::showUsbStorage, false));
            if (activeDrive != null) {
                libraryPage.addView(host.label("USB · " + activeDrive.name() + " (Local Cinema)", 20));
                for (String section : CinemaStorage.SECTIONS) {
                    var file = new java.io.File(activeDrive.library(), section);
                    var folder = folderCard(file, true);
                    var entries = file.listFiles(child -> !child.isHidden() && (child.isDirectory() || isLibraryVideo(child)));
                    folder.addView(host.label(entries == null ? "Unavailable" : entries.length + " items", 14));
                    folder.setOnClickListener(v -> openLibraryFolder(file));
                    folder.setFocusable(true);
                }
            }
            var progress = libraryProgress();
            if (progress.getAll().keySet().stream().anyMatch(key -> key.startsWith("hidden:"))) {
                libraryPage.addView(host.button("Show hidden videos", () -> {
                    var edit = progress.edit();
                    for (String key : progress.getAll().keySet()) {
                        if (key.startsWith("hidden:")) edit.remove(key);
                    }
                    edit.apply();
                    showLibrary();
                }, false));
            }
        }
    }

    boolean withinLibrary(java.io.File folder, java.io.File root) {
        return folder.equals(root) || folder.getAbsolutePath().startsWith(root.getAbsolutePath() + java.io.File.separator);
    }

    java.util.Set<String> publishedFolders() {
        return new java.util.HashSet<>(host.getSharedPreferences("cinema_library", 0)
                .getStringSet("published", java.util.Collections.emptySet()));
    }

    static String folderId(java.io.File folder) {
        return java.util.UUID.nameUUIDFromBytes(folder.getAbsolutePath()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    void togglePublication(java.io.File folder) {
        var paths = publishedFolders();
        boolean hide = paths.remove(folder.getAbsolutePath());
        if (!hide) paths.add(folder.getAbsolutePath());
        host.getSharedPreferences("cinema_library", 0).edit().putStringSet("published", paths).commit();
        if (hide && libraryServer != null) libraryServer.unpublish(folderId(folder));
        if (hide) host.command("UNPUBLISH_LIBRARY_FOLDER", "folder_id", folderId(folder));
        else publishFolder(folder);
        String dispName = "Movies".equalsIgnoreCase(folder.getName()) ? "TV Movies" : folder.getName();
        if (!host.connected) {
            host.notifyStatus(host.l("تم حفظ تفضيل المجلد. اتصل بالشاشة للمزامنة.", "Folder preference saved. Connect to the TV to sync."));
        } else if (hide) {
            host.notifyStatus(host.l("تمت إزالة المجلد من فئات الشاشة: " + folder.getName(), "Removed from TV: " + folder.getName()));
        } else {
            host.notifyStatus(host.l("تم عرض المجلد على الشاشة: Mobile (" + dispName + ")", "Exposed on TV: Mobile (" + dispName + ")"));
        }
        showLibrary();
    }

    void syncPublishedFolders() {
        var snapshot = new org.json.JSONArray();
        var published = publishedFolders();
        for (String path : published) {
            var folder = new java.io.File(path);
            if (folder.isDirectory()) snapshot.put(folderId(folder));
        }
        host.command("SYNC_LIBRARY_FOLDERS", "folder_ids", snapshot);
        if (snapshot.length() == 0) {
            stopServer();
            return;
        }
        for (String path : published) publishFolder(new java.io.File(path));
    }

    void publishFolder(java.io.File folder) {
        publishFolder(folder, true);
    }

    void resumePublishedFolders() {
        for (String path : publishedFolders()) publishFolder(new java.io.File(path), false);
    }

    private void publishFolder(java.io.File folder, boolean notifyTv) {
        if (!host.connected || host.socket == null || !host.socket.isOpen() || !folder.isDirectory()) return;
        try {
            String serverHost = host.socket.getLocalSocketAddress().getAddress().getHostAddress();
            if (libraryServer == null || !serverHost.equals(libraryHost)) {
                if (libraryServer != null) libraryServer.stop();
                libraryServer = null;
                var prefs = host.getSharedPreferences("cinema_library", 0);
                String secret = prefs.getString("secret", null);
                if (secret == null) {
                    secret = java.util.UUID.randomUUID().toString();
                    prefs.edit().putString("secret", secret).apply();
                }
                var server = new MobileLibraryServer(serverHost, host.getContentResolver(), secret);
                server.start();
                libraryServer = server;
                libraryHost = serverHost;
            }
            String path = libraryServer.publish(folder);
            if (!notifyTv) return;
            boolean usb = CinemaStorage.drives(host).stream()
                    .anyMatch(drive -> withinLibrary(folder, drive.library()));
            host.socket.send(new org.json.JSONObject().put("type", "PUBLISH_LIBRARY_FOLDER")
                    .put("folder_id", folderId(folder)).put("folder_name", folder.getName())
                    .put("usb", usb)
                    .put("webdav_url", "http://" + serverHost + ":8080" + path).toString());
        } catch (java.io.IOException | org.json.JSONException | RuntimeException error) {
            host.notifyStatus("Could not publish this folder");
        }
    }

    LinearLayout folderCard(java.io.File file, boolean allowPublish) {
        boolean visible = allowPublish && publishedFolders().contains(file.getAbsolutePath());
        var folder = host.card(libraryPage, file.getName(), null,
                visible ? CinemaGlassUi.Accent.LIBRARY_ACTIVE : CinemaGlassUi.Accent.SUBTLE);
        var heading = (TextView) folder.getChildAt(0);
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(
                org.proninyaroslav.libretorrent.R.drawable.ic_folder_gold, 0, 0, 0);
        heading.setCompoundDrawablePadding(host.dp(8));
        folder.removeView(heading);
        var top = new LinearLayout(host);
        top.setGravity(Gravity.CENTER_VERTICAL);
        folder.addView(top, 0);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (!allowPublish) return folder;
        var eye = host.button("", () -> togglePublication(file), false);
        eye.setPadding(0, 0, 0, 0);
        eye.setMinWidth(0);
        eye.setIconResource(visible ? org.proninyaroslav.libretorrent.R.drawable.cinema_eye
                : org.proninyaroslav.libretorrent.R.drawable.cinema_eye_off);
        eye.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        eye.setIconPadding(0);
        eye.setIconTint(android.content.res.ColorStateList.valueOf(visible ? TvHubActivity.GOLD : 0xff8e8e93));
        eye.setContentDescription((visible ? "Hide " : "Publish ") + file.getName());
        top.addView(eye, new LinearLayout.LayoutParams(host.dp(48), host.dp(48)));
        return folder;
    }

    void showUsbStorage() {
        usbSheet = new com.google.android.material.bottomsheet.BottomSheetDialog(host);
        var scroll = new ScrollView(host);
        usbOptions = host.column();
        usbOptions.setPadding(host.dp(20), host.dp(16), host.dp(20), host.dp(24));
        scroll.addView(usbOptions);
        usbSheet.setContentView(scroll);
        populateUsbStorage();
        usbSheet.show();
    }

    void populateUsbStorage() {
        usbOptions.removeAllViews();
        usbOptions.addView(host.label("USB Storage", 24));
        var drives = CinemaStorage.drives(host);
        if (drives.isEmpty()) usbOptions.addView(host.label("No USB drive connected. Connect a drive and refresh.", 15));
        for (var drive : drives) {
            var panel = host.card(usbOptions, drive.name());
            panel.addView(host.label(android.text.format.Formatter.formatFileSize(host, drive.directory().getUsableSpace())
                    + " available / " + android.text.format.Formatter.formatFileSize(host, drive.directory().getTotalSpace()), 14));
            panel.addView(host.label("FAT32 has a 4 GB limit per file. Android does not report the filesystem here.", 13));
            panel.addView(host.button(drive.library().isDirectory() ? "Use this library" : "Create Local Cinema library", () -> {
                if (!CinemaStorage.drives(host).contains(drive) || !CinemaStorage.prepare(drive.library())) {
                    host.notifyStatus("Cannot write to this USB drive. Check storage access and reconnect it.");
                    return;
                }
                CinemaStorage.select(host, drive);
                libraryFolder = CinemaStorage.phoneLibrary();
                showLibrary();
                usbSheet.dismiss();
            }, true));
        }
        usbOptions.addView(host.button("Refresh", this::populateUsbStorage, false));
    }

    void openLibraryFolder(java.io.File folder) {
        libraryFolder = folder;
        showLibrary();
        if (libraryPage.getParent() instanceof android.view.ViewGroup parent
                && parent.getParent() instanceof ScrollView scroll) scroll.scrollTo(0, 0);
    }

    static boolean isLibraryVideo(java.io.File file) {
        return file.getName().toLowerCase(java.util.Locale.ROOT)
                .matches(".*[.](mkv|mp4|m4v|webm|avi|mov|ts|m2ts|mpg|mpeg)$");
    }

    android.content.SharedPreferences libraryProgress() {
        return host.getSharedPreferences("cinema_library_progress", Context.MODE_PRIVATE);
    }

    static String libraryKey(java.io.File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }

    void attachWatchPoster(LinearLayout row, String imdbId) {
        var img = new android.widget.ImageView(host);
        img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        var bg = new GradientDrawable();
        bg.setColor(0xff141517);
        bg.setCornerRadius(host.dp(8));
        bg.setStroke(host.dp(1), 0xff2c2d31);
        img.setBackground(bg);
        img.setClipToOutline(true);
        var lp = new LinearLayout.LayoutParams(host.dp(54), host.dp(80));
        lp.setMarginEnd(host.dp(12));
        row.addView(img, lp);
        if (imdbId != null && imdbId.matches("tt[0-9]+")) {
            com.bumptech.glide.Glide.with(host)
                    .load("https://images.metahub.space/poster/small/" + imdbId + "/img")
                    .placeholder(org.proninyaroslav.libretorrent.R.drawable.cinema_tv)
                    .error(org.proninyaroslav.libretorrent.R.drawable.cinema_tv)
                    .centerCrop()
                    .into(img);
        } else {
            img.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            img.setImageResource(org.proninyaroslav.libretorrent.R.drawable.cinema_tv);
        }
    }

    void showWatchSection(boolean watchedSection) {
        var entries = watchedSection
                ? PhoneWatchStore.listWatched(host)
                : PhoneWatchStore.listContinueWatching(host);
        var headerRow = host.row(libraryPage);
        var titleTv = host.label(host.getString(watchedSection
                ? org.proninyaroslav.libretorrent.R.string.cinema_watched_tab
                : org.proninyaroslav.libretorrent.R.string.cinema_continue_watching_tab), 20);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1));
        headerRow.addView(host.button(host.l("⟳ تحديث · Trakt", "⟳ Refresh · Trakt Sync"),
                () -> host.connection.syncTraktNowAsync(false), false),
                new LinearLayout.LayoutParams(-2, -2));
        if (entries.isEmpty()) {
            libraryPage.addView(host.label(host.getString(watchedSection
                    ? org.proninyaroslav.libretorrent.R.string.cinema_no_watched_items
                    : org.proninyaroslav.libretorrent.R.string.cinema_no_continue_items), 15));
            return;
        }
        for (var e : entries) {
            if ("library".equals(e.kind) && e.sourceUri != null && e.sourceUri.startsWith("file:")) {
                String path = android.net.Uri.parse(e.sourceUri).getPath();
                if (path != null && java.nio.file.Files.notExists(java.nio.file.Paths.get(path))) continue;
            }
            String titleStr = (e.title == null || e.title.isBlank()) ? "Video" : e.title;
            String header = (e.watched ? "✓ " : "") + titleStr;
            var itemCard = host.card(libraryPage, header);
            var bodyRow = host.row(itemCard);
            bodyRow.setGravity(Gravity.CENTER_VERTICAL);
            attachWatchPoster(bodyRow, e.imdbId);
            var infoCol = host.column();
            bodyRow.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1));
            var meta = new java.util.ArrayList<String>();
            if ("torrent".equals(e.kind)) meta.add(host.l("تورنت", "Torrent"));
            else if ("trakt".equals(e.kind)) meta.add("Trakt");
            else if ("web".equals(e.kind)) meta.add(host.l("إضافة / ويب", "Web / Addon"));
            else meta.add(host.l("المكتبة", "Library"));
            if (e.season > 0 || e.episode > 0) meta.add(String.format(java.util.Locale.US, "S%02dE%02d", e.season, e.episode));
            if (!e.watched && e.durationMs > 0 && e.positionMs > 0) {
                int pct = (int) Math.min(99, Math.max(1, e.positionMs * 100L / e.durationMs));
                meta.add(pct + "% · " + TvHubActivity.formatTime((int) e.positionMs) + " / " + TvHubActivity.formatTime((int) e.durationMs));
            }
            infoCol.addView(host.label(String.join(" · ", meta), 13));
            if (!e.watched && e.durationMs > 0 && e.positionMs > 0) {
                var bar = new android.widget.ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(100);
                bar.setProgress((int) Math.min(100, e.positionMs * 100L / e.durationMs));
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(TvHubActivity.GOLD));
                var bLp = new LinearLayout.LayoutParams(-1, host.dp(4));
                bLp.topMargin = host.dp(6);
                infoCol.addView(bar, bLp);
            }
            var actions = host.row(itemCard);
            boolean hasDirectSource = (e.sourceUri != null && !e.sourceUri.isBlank())
                    || ("torrent".equals(e.kind) && e.torrentId != null && !e.torrentId.isBlank());
            if (hasDirectSource) {
                host.add(actions, e.watched ? host.l("تشغيل", "Play") : host.l("استئناف", "Resume"), () -> replayWatchEntry(e));
            } else if (e.imdbId != null && e.imdbId.matches("tt[0-9]+")) {
                if (com.meedx.providers.StremioAddons.hasEnabled(host)) {
                    host.add(actions, host.l("بث عبر الإضافات", "Stream via Addons"), () ->
                            host.connection.resolveAndPickAddonStream(e.imdbId, Math.max(0, e.season), Math.max(0, e.episode)));
                } else {
                    host.add(actions, host.l("أضف إضافة للبث", "Addons Setup"), () -> {
                        if (host.tabLayout != null && host.tabLayout.getTabAt(TvHubActivity.CONNECTION_TAB) != null) {
                            host.tabLayout.selectTab(host.tabLayout.getTabAt(TvHubActivity.CONNECTION_TAB));
                        }
                    });
                }
            }
            host.add(actions, e.watched ? host.getString(org.proninyaroslav.libretorrent.R.string.cinema_mark_unwatched)
                    : host.getString(org.proninyaroslav.libretorrent.R.string.cinema_mark_watched), () -> {
                boolean newWatched = !e.watched;
                var updated = PhoneWatchStore.markRemoteWatched(host, e, newWatched);
                if (updated != null) {
                    host.connection.sendWatchSyncRecord(updated);
                    if (com.meedx.providers.TraktSyncClient.isConnected(host)
                            && ((e.imdbId != null && e.imdbId.matches("tt[0-9]+")) || e.tmdbId > 0)) {
                        boolean isEp = e.season > 0 || e.episode > 0 || "episode".equalsIgnoreCase(e.mediaType);
                        host.watchSyncIo.execute(() -> {
                            com.meedx.providers.TraktSyncClient.syncHistoryItem(
                                    host, isEp, e.imdbId, e.tmdbId, e.season, e.episode, newWatched);
                            PhoneWatchStore.clearPendingTraktSync(host, java.util.Collections.singletonList(updated.itemKey));
                            host.connection.sendFullSyncStateToTv(false);
                        });
                    }
                }
                showLibrary();
            });
            if (!e.watched) {
                host.add(actions, host.getString(org.proninyaroslav.libretorrent.R.string.cinema_remove_continue), () -> {
                    var updated = PhoneWatchStore.removeFromContinue(host, e.itemKey);
                    if ("library".equals(e.kind) && e.itemKey.startsWith("lib:")) {
                        libraryProgress().edit().remove(e.itemKey.substring(4)).apply();
                    }
                    host.connection.sendWatchSyncRecord(updated);
                    if (com.meedx.providers.TraktSyncClient.isConnected(host)
                            && ((e.imdbId != null && e.imdbId.matches("tt[0-9]+")) || e.tmdbId > 0)) {
                        host.watchSyncIo.execute(() -> {
                            com.meedx.providers.TraktSyncClient.removePlaybackProgress(
                                    host, e.imdbId, e.tmdbId, e.season, e.episode);
                            host.connection.sendFullSyncStateToTv(false);
                        });
                    }
                    showLibrary();
                });
            }
        }
    }

    void showWatchlistSection() {
        var headerRow = host.row(libraryPage);
        headerRow.addView(host.label(host.l("قائمة المشاهدة (Watchlist)", "Watchlist"), 20),
                new LinearLayout.LayoutParams(0, -2, 1));
        headerRow.addView(host.button(host.l("⟳ تحديث · Trakt", "⟳ Refresh · Trakt Sync"),
                () -> host.connection.syncTraktNowAsync(false), false),
                new LinearLayout.LayoutParams(-2, -2));
        var list = PhoneWatchStore.listWatchlist(host);
        if (list.isEmpty()) {
            libraryPage.addView(host.label(host.l(
                    "لا توجد عناصر في قائمة المشاهدة بعد. اربط Trakt واضغط تحديث أو أضف عناصر من الشاشة.",
                    "No Watchlist items yet. Connect Trakt and tap Refresh, or add titles on TV."), 15));
            return;
        }
        for (var w : list) {
            String titleStr = (w.title == null || w.title.isBlank()) ? w.imdbId : w.title;
            var wlCard = host.card(libraryPage, titleStr + (w.year > 0 ? " (" + w.year + ")" : ""));
            var bodyRow = host.row(wlCard);
            bodyRow.setGravity(Gravity.CENTER_VERTICAL);
            attachWatchPoster(bodyRow, w.imdbId);
            var infoCol = host.column();
            bodyRow.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1));
            infoCol.addView(host.label(("show".equals(w.mediaType)
                    ? host.l("مسلسل · Trakt Watchlist", "TV Series · Trakt Watchlist")
                    : host.l("فيلم · Trakt Watchlist", "Movie · Trakt Watchlist"))
                    + (w.year > 0 ? " · " + w.year : ""), 13));
            var wlActions = host.row(wlCard);
            if (w.imdbId != null && w.imdbId.matches("tt[0-9]+")) {
                if (com.meedx.providers.StremioAddons.hasEnabled(host)) {
                    host.add(wlActions, host.l("بث عبر الإضافات", "Stream via Addons"), () ->
                            host.connection.resolveAndPickAddonStream(w.imdbId, "show".equals(w.mediaType) ? 1 : 0,
                                    "show".equals(w.mediaType) ? 1 : 0));
                } else {
                    host.add(wlActions, host.l("أضف إضافة للبث", "Addons Setup"), () -> {
                        if (host.tabLayout != null && host.tabLayout.getTabAt(TvHubActivity.CONNECTION_TAB) != null) {
                            host.tabLayout.selectTab(host.tabLayout.getTabAt(TvHubActivity.CONNECTION_TAB));
                        }
                    });
                }
            }
            host.add(wlActions, host.l("إزالة", "Remove"), () -> {
                PhoneWatchStore.removeFromWatchlist(host, w.itemKey);
                if (com.meedx.providers.TraktSyncClient.isConnected(host)) {
                    host.watchSyncIo.execute(() -> {
                        com.meedx.providers.TraktSyncClient.syncWatchlistItem(
                                host, "show".equalsIgnoreCase(w.mediaType), w.imdbId, w.tmdbId, false);
                        host.connection.sendFullSyncStateToTv(false);
                    });
                } else {
                    host.connection.sendFullSyncStateToTv(false);
                }
                showLibrary();
            });
        }
    }

    void replayWatchEntry(PhoneWatchStore.Entry e) {
        if ("library".equals(e.kind) && e.sourceUri != null && !e.sourceUri.isBlank()) {
            host.castVideo(android.net.Uri.parse(e.sourceUri), e.watched ? 0 : (int) Math.max(0, e.positionMs), false);
        } else if ("torrent".equals(e.kind) && e.torrentId != null && !e.torrentId.isBlank()) {
            TvHubActivity.castTorrent(host, e.torrentId, e.fileIndex, e.title);
        } else if ("web".equals(e.kind) && e.sourceUri != null && !e.sourceUri.isBlank()) {
            if (!host.connected || host.socket == null || !host.socket.isOpen()) {
                host.notifyStatus("Connect to the TV first");
                return;
            }
            host.music.stopMusicForVideo(true);
            host.cleanupPreviousTemporaryTorrent("");
            host.activeWatchKey = e.itemKey;
            host.activeWatchKind = "web";
            host.activeWatchTitle = e.title;
            host.activeWatchUri = e.sourceUri;
            host.activeWatchFormat = e.format;
            host.activeWatchImdbId = e.imdbId == null ? "" : e.imdbId;
            host.activeWatchTmdbId = e.tmdbId;
            host.activeWatchSeason = e.season;
            host.activeWatchEpisode = e.episode;
            host.browserCastTitle = e.title;
            try {
                host.socket.send(new org.json.JSONObject().put("type", "CAST_WEB")
                        .put("url", e.sourceUri).put("audio_url", "")
                        .put("format", e.format == null ? "" : e.format)
                        .put("title", e.title == null ? "" : e.title)
                        .put("stream_key", e.itemKey)
                        .put("media_type", e.mediaType == null ? "movie" : e.mediaType)
                        .put("imdb_id", host.activeWatchImdbId)
                        .put("tmdb_id", e.tmdbId)
                        .put("season", e.season)
                        .put("episode", e.episode)
                        .put("resume_ms", e.watched ? 0L : e.positionMs).toString());
                host.showPlayback(true);
            } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException ignored) {
                host.notifyStatus("Could not cast saved web video");
            }
        }
    }

    void showLibraryItemMenu(java.io.File file, String watchKey, String title, String uri, boolean watched, boolean hasResume) {
        var labels = new java.util.ArrayList<String>();
        var actions = new java.util.ArrayList<Runnable>();
        labels.add(host.getString(watched ? org.proninyaroslav.libretorrent.R.string.cinema_mark_unwatched
                : org.proninyaroslav.libretorrent.R.string.cinema_mark_watched));
        actions.add(() -> {
            var updated = PhoneWatchStore.markWatched(host, watchKey, "library", title, uri, !watched);
            libraryProgress().edit().remove(libraryKey(file)).apply();
            host.connection.sendWatchSyncRecord(updated);
            host.connection.pushPendingTraktOnlyAsync();
            showLibrary();
        });
        if (hasResume && !watched) {
            labels.add(host.getString(org.proninyaroslav.libretorrent.R.string.cinema_remove_continue));
            actions.add(() -> {
                var updated = PhoneWatchStore.removeFromContinue(host, watchKey);
                libraryProgress().edit().remove(libraryKey(file)).apply();
                host.connection.sendWatchSyncRecord(updated);
                showLibrary();
            });
        }
        labels.add(host.l("إخفاء من المكتبة", "Hide video from Library"));
        actions.add(() -> confirmLibraryHide(file));
        showFileActions(file, libraryRoot(file), labels, actions);
    }

    private java.io.File libraryRoot(java.io.File file) {
        var internal = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Local Cinema");
        if (withinLibrary(file, internal)) return internal;
        for (var drive : CinemaStorage.drives(host)) {
            if (withinLibrary(file, drive.library())) return drive.library();
        }
        return null;
    }

    private boolean canManage(java.io.File file, java.io.File root) {
        if (root == null || file == null || !file.exists() || file.equals(root)) return false;
        if (file.getParentFile().equals(root) && CinemaStorage.SECTIONS.stream()
                .anyMatch(section -> section.equalsIgnoreCase(file.getName()))) return false;
        try {
            return file.getCanonicalFile().equals(file.getAbsoluteFile())
                    && withinLibrary(file.getCanonicalFile(), root.getCanonicalFile());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private void showFileActions(java.io.File file, java.io.File root, java.util.List<String> labels,
                                 java.util.List<Runnable> actions) {
        if (canManage(file, root)) {
            labels.add(host.l("إعادة التسمية", "Rename"));
            actions.add(() -> renameFile(file, root));
            labels.add(host.l("حذف من التخزين", "Delete from storage"));
            actions.add(() -> confirmDeleteFile(file, root));
        }
        labels.add(host.l("المعلومات", "Info"));
        actions.add(() -> showFileInfo(file));
        new androidx.appcompat.app.AlertDialog.Builder(host).setTitle(file.getName())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showFileInfo(java.io.File file) {
        host.watchSyncIo.execute(() -> {
            var details = new java.util.ArrayList<String>();
            details.add(file.getAbsolutePath());
            if (file.isDirectory()) {
                String[] children = file.list();
                if (children != null) details.add(host.l("العناصر ", "Items ") + children.length);
            } else {
                details.add(android.text.format.Formatter.formatFileSize(host, file.length()));
                try (var metadata = new android.media.MediaMetadataRetriever()) {
                    metadata.setDataSource(file.getAbsolutePath());
                    String duration = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (duration != null) details.add(TvHubActivity.formatTime((int) Math.min(Integer.MAX_VALUE, Long.parseLong(duration))));
                    String width = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String height = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (width != null && height != null) details.add(width + " × " + height);
                } catch (Exception unreadable) {
                    // Size and path remain available if the video metadata cannot be read.
                }
            }
            host.main.post(() -> {
                if (!host.isFinishing() && !host.isDestroyed()) {
                    new androidx.appcompat.app.AlertDialog.Builder(host).setTitle(file.getName())
                            .setMessage(String.join("\n", details)).setPositiveButton(android.R.string.ok, null).show();
                }
            });
        });
    }

    private void renameFile(java.io.File file, java.io.File root) {
        var name = new android.widget.EditText(host);
        name.setSingleLine(true);
        name.setText(file.getName());
        int stem = file.isDirectory() ? -1 : file.getName().lastIndexOf('.');
        name.setSelection(0, stem > 0 ? stem : name.length());
        var rename = new androidx.appcompat.app.AlertDialog.Builder(host).setTitle(host.l("إعادة التسمية", "Rename"))
                .setView(name).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).show();
        rename.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty() || label.startsWith(".") || label.indexOf('/') >= 0 || label.indexOf('\\') >= 0
                    || label.indexOf(0) >= 0) {
                name.setError(host.l("اسم غير صالح", "Invalid name"));
                return;
            }
            if (stem > 0 && label.lastIndexOf('.') <= 0) label += file.getName().substring(stem);
            var destination = new java.io.File(file.getParentFile(), label);
            if (destination.equals(file)) { rename.dismiss(); return; }
            if (destination.exists()) {
                name.setError(host.l("الاسم مستخدم بالفعل", "This name already exists"));
                return;
            }
            rename.dismiss();
            host.watchSyncIo.execute(() -> {
                try {
                    if (!canManage(file, root)) throw new java.io.IOException();
                    java.nio.file.Files.move(file.toPath(), destination.toPath());
                    PhoneWatchStore.refreshLibraryFiles(host, root, file, destination);
                } catch (java.io.IOException | RuntimeException failure) {
                    host.main.post(() -> host.notifyStatus(host.l("تعذّرت إعادة التسمية", "Could not rename this item")));
                } finally {
                    host.main.post(() -> refreshFiles(file.getParentFile()));
                }
            });
        });
    }

    private void confirmDeleteFile(java.io.File file, java.io.File root) {
        new androidx.appcompat.app.AlertDialog.Builder(host).setTitle(host.l("حذف من التخزين؟", "Delete from storage?"))
                .setMessage(file.getName() + (file.isDirectory()
                        ? host.l("\nسيُحذف المجلد وكل محتوياته.", "\nThe folder and all its contents will be deleted.") : ""))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(host.l("حذف", "Delete"), (dialog, which) -> host.watchSyncIo.execute(() -> {
                    try {
                        if (!canManage(file, root)) throw new java.io.IOException();
                        try (var files = java.nio.file.Files.walk(file.toPath())) {
                            for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                                java.nio.file.Files.delete(path);
                            }
                        }
                    } catch (java.io.IOException | RuntimeException failure) {
                        host.main.post(() -> host.notifyStatus(host.l("تعذّر إكمال الحذف", "Could not complete deletion")));
                    } finally {
                        host.main.post(() -> refreshFiles(file.getParentFile()));
                    }
                })).show();
    }

    private void refreshFiles(java.io.File changedFolder) {
        if (host.isFinishing() || host.isDestroyed()) return;
        var root = libraryRoot(changedFolder);
        if (root != null) host.watchSyncIo.execute(() -> PhoneWatchStore.refreshLibraryFiles(host, root, null, null));
        for (String path : publishedFolders()) {
            var shared = new java.io.File(path);
            if (shared.isDirectory() && withinLibrary(changedFolder, shared)) publishFolder(shared);
        }
        showLibrary();
    }


    void confirmLibraryHide(java.io.File file) {
        new androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(org.proninyaroslav.libretorrent.R.string.cinema_hide_library_video_confirmation)
                .setMessage(file.getName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    libraryProgress().edit().putBoolean("hidden:" + libraryKey(file), true).apply();
                    showLibrary();
                }).show();
    }
}
