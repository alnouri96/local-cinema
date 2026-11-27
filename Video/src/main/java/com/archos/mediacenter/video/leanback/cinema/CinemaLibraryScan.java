package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.archos.filecorelibrary.MetaFile2;
import com.archos.filecorelibrary.RawListerFactory;
import com.archos.filecorelibrary.samba.SambaDiscovery;
import com.archos.filecorelibrary.samba.Workgroup;
import com.archos.mediacenter.utils.ShortcutDbAdapter;
import com.archos.mediaprovider.NetworkScanner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Executors;

public final class CinemaLibraryScan {
    public static final String ACTION_CHANGED = "com.meedx.localcinema.tv.INDEXED_LIBRARIES_CHANGED";
    private static final String TAG = "CinemaLibraryScan";
    private static final java.util.concurrent.atomic.AtomicLong STATE_EPOCH =
            new java.util.concurrent.atomic.AtomicLong(1L);

    public static long stateEpoch() {
        return STATE_EPOCH.get();
    }

    public static void bumpStateEpoch() {
        STATE_EPOCH.incrementAndGet();
    }
    private static final Set<String> SYSTEM = Set.of("appdata", "$recycle.bin", "system volume information");
    private static final Set<String> LIBRARIES = Set.of("movies", "films", "shows", "tv shows", "series",
            "cartoon", "cartoons", "animation", "anime", "أفلام", "مسلسلات", "رسوم متحركة");
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ScheduledExecutorService PROBES = Executors.newSingleThreadScheduledExecutor();
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SMB_HOSTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.util.concurrent.ScheduledFuture<?> watcher;
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> RECHECK = new HashSet<>();
    private static final Map<String, Long> LAST_RUN = new LinkedHashMap<>();
    private static SambaDiscovery discovery;

    private static final Handler INDEX_EVENTS = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService TIMINGS = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean TIMING_SWEEP = new java.util.concurrent.atomic.AtomicBoolean();

    public static void observeIndexedMedia(Context context) {
        Context app = context.getApplicationContext();
        Runnable warm = () -> {
            if (!TIMING_SWEEP.compareAndSet(false, true)) return;
            TIMINGS.execute(() -> {
                var source = com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI;
                var filters = new CinemaPlaybackFilters(app, () -> 0, () -> 0, () -> false, ignored -> {});
                try (var cursor = app.getContentResolver().query(source, new String[]{"_id", "_data"},
                        null, null, "_id DESC")) {
                    while (cursor != null && cursor.moveToNext()) {
                        String path = cursor.getString(1);
                        if (path == null || path.isBlank()) continue;
                        Uri uri = path.startsWith("/") ? Uri.fromFile(new java.io.File(path)) : Uri.parse(path);
                        if (!"file".equals(uri.getScheme()) && !"smb".equals(uri.getScheme())) continue;
                        filters.warmLibraryEntry(cursor.getLong(0), uri);
                    }
                } catch (RuntimeException failure) {
                    Log.w(TAG, "Could not prepare indexed playback timings");
                } finally {
                    TIMING_SWEEP.set(false);
                }
            });
        };
        app.getContentResolver().registerContentObserver(
                com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI, true,
                new android.database.ContentObserver(INDEX_EVENTS) {
                    @Override public void onChange(boolean selfChange) {
                        INDEX_EVENTS.removeCallbacks(warm);
                        INDEX_EVENTS.postDelayed(warm, 4000);
                    }
                });
        var scans = new android.content.IntentFilter(com.archos.mediaprovider.ArchosMediaIntent.ACTION_VIDEO_SCANNER_SCAN_FINISHED);
        scans.addDataScheme("smb");
        scans.addDataScheme("file");
        scans.addDataScheme("content");
        androidx.core.content.ContextCompat.registerReceiver(app, new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context receiver, android.content.Intent intent) {
                INDEX_EVENTS.removeCallbacks(warm);
                INDEX_EVENTS.postDelayed(warm, 4000);
            }
        }, scans, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        INDEX_EVENTS.postDelayed(warm, 8000);
    }

    private CinemaLibraryScan() {}

    public static void discover(Context context) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (discovery != null) return;
            discovery = new SambaDiscovery(app);
            discovery.addListener(new SambaDiscovery.Listener() {
                @Override public void onDiscoveryStart() {}
                @Override public void onDiscoveryEnd() { discovery = null; }
                @Override public void onDiscoveryFatalError() { discovery = null; }
                @Override public void onDiscoveryUpdate(List<Workgroup> groups) {
                    for (Workgroup group : groups)
                        for (var share : group.getShares()) discovered(app, share.toUri());
                }
            });
            discovery.start();
        });
    }

    public static void discovered(Context context, Uri uri) {
        enqueue(context, host(uri), false);
    }

    static synchronized void startWatching(Context context) {
        if (watcher != null) return;
        Context app = context.getApplicationContext();
        watcher = PROBES.scheduleWithFixedDelay(() -> checkSavedRoots(app, false),
                0, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    static synchronized void stopWatching() {
        if (watcher != null) watcher.cancel(false);
        watcher = null;
    }

    static boolean isAvailable(Uri uri) {
        return uri == null || !isSmb(uri) || Boolean.TRUE.equals(SMB_HOSTS.get(hostKey(uri)));
    }

    private static String hostKey(Uri uri) {
        return java.util.Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT)
                + ":" + (uri.getPort() > 0 ? uri.getPort() : 445);
    }

    private static boolean probe(Uri uri) {
        String address = uri.getHost();
        if (address == null || address.isBlank()) return false;
        try (var socket = new java.net.Socket()) {
            java.net.InetAddress resolved;
            try {
                resolved = java.net.InetAddress.getByName(address);
            } catch (java.net.UnknownHostException unresolved) {
                resolved = com.archos.filecorelibrary.jcifs.JcifsUtils.getBaseContext(true)
                        .getNameServiceClient().getByName(address).toInetAddress();
            }
            socket.connect(new java.net.InetSocketAddress(resolved, uri.getPort() > 0 ? uri.getPort() : 445), 900);
            return true;
        } catch (java.io.IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static void checkSavedRoots(Context context, boolean manual) {
        try {
            Map<String, Boolean> checked = new LinkedHashMap<>();
            Set<String> recovered = new HashSet<>();
            List<ShortcutDbAdapter.Shortcut> roots = shortcuts(context);
            boolean changed = false;
            for (var shortcut : roots) {
                Uri uri = Uri.parse(shortcut.getUri());
                if (!isSmb(uri) || checked.containsKey(hostKey(uri))) continue;
                String key = hostKey(uri);
                boolean online = probe(uri);
                checked.put(key, online);
                Boolean previous = SMB_HOSTS.put(key, online);
                if (online && !Boolean.TRUE.equals(previous)) recovered.add(key);
                changed |= previous == null || previous != online;
            }
            if (changed) signalChange(context);
            for (var shortcut : roots) {
                Uri uri = Uri.parse(shortcut.getUri());
                if (isSmb(uri)) {
                    if (isAvailable(uri) && (manual || recovered.contains(hostKey(uri)))) scanSavedRoot(context, uri);
                } else if (manual) {
                    NetworkScanner.scanVideos(context, uri);
                }
            }
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not check saved libraries", unavailable);
        }
    }

    private static void scanSavedRoot(Context context, Uri uri) {
        if (!uri.getPathSegments().isEmpty()) {
            NetworkScanner.scanVideos(context, uri);
        } else {
            enqueue(context, host(uri), true);
        }
    }

    private static void signalChange(Context context) {
        STATE_EPOCH.incrementAndGet();
        context.sendBroadcast(new android.content.Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
    }

    static void start(Context context) {
        start(context, null);
    }

    public static void start(Context context, Uri root) {
        Context app = context.getApplicationContext();
        if (root != null) {
            if (!isSmb(root)) {
                NetworkScanner.scanVideos(app, root);
                return;
            }
            PROBES.execute(() -> {
                boolean online = probe(root);
                Boolean previous = SMB_HOSTS.put(hostKey(root), online);
                if (previous == null || previous != online) signalChange(app);
                if (!online) return;
                scanSavedRoot(app, root);
                if (!root.getPathSegments().isEmpty() && permitted(root.getPathSegments().get(0))) {
                    enqueue(app, normalize(root), true);
                }
                if (Boolean.FALSE.equals(previous)) {
                    for (var shortcut : shortcuts(app)) {
                        Uri sibling = Uri.parse(shortcut.getUri());
                        if (isSmb(sibling) && hostKey(sibling).equals(hostKey(root))
                                && !sameDirectory(root, sibling)) scanSavedRoot(app, sibling);
                    }
                }
            });
            return;
        }
        discover(app);
        PROBES.execute(() -> checkSavedRoots(app, true));
    }

    private static void enqueue(Context context, Uri root, boolean force) {
        if (!"smb".equals(root.getScheme()) || root.getHost() == null || root.getUserInfo() != null) return;
        String key = root.toString().toLowerCase(Locale.ROOT);
        synchronized (PENDING) {
            long now = android.os.SystemClock.elapsedRealtime();
            Long previous = LAST_RUN.get(key);
            if (!force && previous != null && now - previous < 120_000) return;
            if (!PENDING.add(key)) {
                if (force) RECHECK.add(key);
                return;
            }
            LAST_RUN.put(key, now);
            if (LAST_RUN.size() > 256) LAST_RUN.remove(LAST_RUN.keySet().iterator().next());
        }
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                scan(app, root);
            } catch (RuntimeException error) {
                Log.w(TAG, "SMB indexing failed: " + error.getClass().getSimpleName());
            } finally {
                boolean recheck;
                synchronized (PENDING) {
                    PENDING.remove(key);
                    recheck = RECHECK.remove(key);
                }
                if (recheck) enqueue(app, root, true);
            }
        });
    }

    private static void scan(Context context, Uri root) {
        if (!probe(root)) return;
        Map<Uri, Set<String>> listed = new LinkedHashMap<>();
        Map<Uri, String> libraries = new LinkedHashMap<>();
        boolean hostRoot = root.getPathSegments().isEmpty();
        collect(root, hostRoot ? 4 : 3, hostRoot, new int[]{512}, listed, libraries);
        if (listed.isEmpty()) return;
        Map<Uri, String> sections = new LinkedHashMap<>();
        for (var library : libraries.entrySet()) {
            Uri section = sectionOf(library.getKey(), root);
            String last = section.getLastPathSegment();
            sections.putIfAbsent(section, last == null || last.isBlank() ? library.getValue() : last);
        }
        libraries = sections;

        var db = ShortcutDbAdapter.VIDEO;
        db.cleanupDuplicateShortcuts(context);
        var prefs = context.getSharedPreferences("cinema_library_scan", Context.MODE_PRIVATE);
        Set<String> managed = new HashSet<>(prefs.getStringSet("auto_indexed", Set.of()));
        var saved = shortcuts(context);
        boolean changed = false;
        for (var entry : libraries.entrySet()) {
            Uri uri = entry.getKey();
            String uriStr = ShortcutDbAdapter.normalizeStoredUri(uri.toString());
            var existing = saved.stream().filter(s -> ShortcutDbAdapter.isSameOrAncestorUri(s.getUri(), uriStr)
                    || coveredByHost(s.getUri(), uriStr)).findFirst();
            if (existing.isPresent()) {
                // Ancestor or exact shortcut already indexed; do not duplicate or register subfolder.
                continue;
            }
            if (!db.isHimselfOrAncestorShortcut(context, uriStr)
                    && db.addShortcut(context, new ShortcutDbAdapter.Shortcut(entry.getValue(), uriStr))) {
                managed.add(uriStr);
                changed = true;
                NetworkScanner.scanVideos(context, uriStr);
                Log.i(TAG, "Indexed SMB folder: " + entry.getValue());
            }
        }
        var current = shortcuts(context);
        for (var item : saved) {
            if (!managed.contains(item.getUri())) continue;
            for (Uri section : libraries.keySet()) {
                String sectionUri = ShortcutDbAdapter.normalizeStoredUri(section.toString());
                boolean sectionExists = current.stream().anyMatch(c -> ShortcutDbAdapter.isSameCanonicalUri(c.getUri(), sectionUri));
                if (sectionExists && !ShortcutDbAdapter.isSameCanonicalUri(sectionUri, item.getUri())
                        && (ShortcutDbAdapter.isSameOrAncestorUri(sectionUri, item.getUri()) || coveredByHost(sectionUri, item.getUri()))
                        && db.deleteShortcut(context, item.getID())) {
                    managed.remove(item.getUri());   // stale auto-indexed child; videos stay (covered by section scan)
                    changed = true;
                    break;
                }
            }
        }
        for (var item : saved) {
            if (!managed.contains(item.getUri())) continue;
            Uri uri = normalize(Uri.parse(item.getUri()));
            while (!uri.getPathSegments().isEmpty()) {
                Uri parent = normalize(com.archos.filecorelibrary.FileUtils.getParentUrl(uri));
                if (parent.equals(uri)) break;
                Set<String> children = listed.get(parent);
                if (children != null && !children.contains(uri.getLastPathSegment().toLowerCase(Locale.ROOT))) {
                    if (confirmedMissing(uri) && db.deleteShortcut(context, item.getID())) {
                        managed.remove(item.getUri());
                        changed = true;
                        NetworkScanner.removeVideos(context, item.getUri());
                    }
                    break;
                }
                uri = parent;
            }
        }
        prefs.edit().putStringSet("auto_indexed", managed).apply();
        if (changed) signalChange(context);
        CinemaMetadataPrefetcher.triggerAfterScan(context);
    }

    private static boolean confirmedMissing(Uri uri) {
        try {
            RawListerFactory.getRawListerForUrl(uri).getFileList();
        } catch (com.archos.filecorelibrary.smbj.SmbjRawLister.MissingDirectoryException missing) {
            return true;
        } catch (jcifs.smb.SmbException error) {
            int status = error.getNtStatus();
            return status == 0xC0000034 || status == 0xC000003A || status == 0xC00000CC;
        } catch (Exception unavailable) {
            return false;
        }
        return false;
    }

    private static void collect(Uri directory, int depth, boolean host, int[] budget,
                                Map<Uri, Set<String>> listed, Map<Uri, String> libraries) {
        if (budget[0]-- <= 0) return;
        List<MetaFile2> entries;
        try {
            entries = (host ? new com.archos.filecorelibrary.jcifs.JcifsRawLister(directory)
                    : RawListerFactory.getRawListerForUrl(directory)).getFileList();
        } catch (Exception error) {
            Log.d(TAG, "SMB directory unavailable: " + error.getClass().getSimpleName());
            return;
        }
        if (entries == null) return;
        Set<String> children = new HashSet<>();
        boolean containsVideo = false;
        for (MetaFile2 entry : entries) {
            String name = entry.getName();
            if (!permitted(name)) continue;
            if (entry.isDirectory()) children.add(name.toLowerCase(Locale.ROOT));
            else if (entry.isFile() && name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|avi|mov|wmv|ts|m2ts|webm)$"))
                containsVideo = true;
        }
        listed.put(directory, children);
        if (!host && containsVideo) {
            libraries.put(directory, directory.getLastPathSegment());
        }
        int previousCount = libraries.size();
        if (depth > 0) {
            for (MetaFile2 entry : entries) {
                if (budget[0] <= 0) break;
                if (entry.isDirectory() && permitted(entry.getName()))
                    collect(normalize(directory.buildUpon().appendPath(entry.getName()).build()),
                            depth - 1, false, budget, listed, libraries);
            }
        }
        String name = directory.getLastPathSegment();
        if (!host && name != null && libraries.size() > previousCount
                && LIBRARIES.contains(name.toLowerCase(Locale.ROOT))
                && libraries.entrySet().stream().noneMatch(entry -> !entry.getKey().equals(directory)
                    && entry.getKey().toString().startsWith(directory.toString())
                    && LIBRARIES.contains(entry.getValue().toLowerCase(Locale.ROOT)))) {
            libraries.keySet().removeIf(uri -> !uri.equals(directory)
                    && uri.toString().startsWith(directory.toString()));
            libraries.put(directory, name);
        }
    }

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Direct child of the share (host/share roots) or the scan root itself (deeper roots). */
    private static Uri sectionOf(Uri library, Uri root) {
        List<String> segments = library.getPathSegments();
        int keep = root.getPathSegments().size() <= 1 ? 2 : root.getPathSegments().size();
        if (segments.size() <= keep) return normalize(library);
        Uri.Builder builder = library.buildUpon().encodedPath("/").clearQuery().fragment(null);
        for (int i = 0; i < keep; i++) builder.appendPath(segments.get(i));
        return normalize(builder.build());
    }

    private static String pathKey(Uri uri) {
        String p = Uri.decode(uri.getPath() == null ? "" : uri.getPath()).toLowerCase(Locale.ROOT);
        return p.replaceAll("/+$", "");
    }

    /** SMB ancestor-or-same check that treats an IPv4 literal and a hostname as the same server. */
    private static boolean coveredByHost(String ancestor, String candidate) {
        if (ancestor == null || candidate == null) return false;
        Uri a = Uri.parse(ancestor), c = Uri.parse(candidate);
        if (a.getHost() == null || c.getHost() == null || !isSmb(a) || !isSmb(c)) return false;
        String ha = a.getHost().toLowerCase(Locale.ROOT), hc = c.getHost().toLowerCase(Locale.ROOT);
        boolean sameServer = ha.equals(hc) || IPV4.matcher(ha).matches() != IPV4.matcher(hc).matches();
        if (!sameServer) return false;
        String pa = pathKey(a), pc = pathKey(c);
        return pc.equals(pa) || pc.startsWith(pa + "/");
    }

    private static boolean permitted(String name) {
        return name != null && !name.isBlank() && !name.startsWith(".") && !name.endsWith("$")
                && !SYSTEM.contains(name.toLowerCase(Locale.ROOT));
    }

    private static List<ShortcutDbAdapter.Shortcut> shortcuts(Context context) {
        var db = ShortcutDbAdapter.VIDEO;
        try (Cursor cursor = db.queryAllShortcuts(context)) {
            return cursor == null ? List.of() : db.cursorToShortcutList(cursor);
        }
    }

    private static boolean sameDirectory(Uri first, Uri second) {
        if (first == null || second == null) return false;
        return ShortcutDbAdapter.isSameCanonicalUri(first.toString(), second.toString());
    }

    private static boolean isSmb(Uri uri) {
        return "smb".equals(uri.getScheme()) || "smbj".equals(uri.getScheme());
    }

    private static Uri host(Uri uri) {
        return uri.buildUpon().path("/").clearQuery().fragment(null).build();
    }

    private static Uri normalize(Uri uri) {
        return uri.buildUpon().encodedPath((uri.getEncodedPath() == null ? "" : uri.getEncodedPath())
                .replaceFirst("/*$", "") + "/").clearQuery().fragment(null).build();
    }
}
