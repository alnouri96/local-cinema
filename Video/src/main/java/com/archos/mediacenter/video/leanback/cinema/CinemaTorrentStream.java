package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import android.os.Handler;
import android.os.Looper;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;

final class CinemaTorrentStream {
    record Status(int percent, int seeds, int peers, long downloadBytesPerSec, boolean metadataReady) {}

    private static final int MIB = 1024 * 1024;
    private static final int START_MIN_BYTES = (int) (1.25 * MIB);
    private static final int START_MAX_BYTES = 3 * MIB;
    private static final int READ_AHEAD_BYTES = 24 * MIB;
    private static final int READER_REFILL_BYTES = 5 * MIB;
    private static final int INDEX_BYTES = MIB / 2;
    private static final long SPEED_SAMPLE_MS = 600;
    private static final long START_RATE_BYTES_PER_SECOND = 220 * 1024;
    private static final long METADATA_TIMEOUT_MS = 55_000;
    private static final long PIECE_IDLE_MS = 55_000;
    private static final long PIECE_LIMIT_MS = 110_000;
    private static final long PEER_REFRESH_MS = 6_000;
    private static final int PIECE_PRIORITY_STEP_MS = 60;
    private static final String[] FAST_PUBLIC_TRACKERS = {
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "http://tracker.opentrackr.org:1337/announce"
    };
    private static final String DHT_BOOTSTRAP_NODES =
            "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.aelitis.com:6881";
    static final String PREPARED_TOKEN = "prepared_torrent";
    private static final ConcurrentHashMap<String, CinemaTorrentStream> PREPARED = new ConcurrentHashMap<>();
    private static final long STORAGE_RESERVE = 64L * 1024 * 1024;
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mkv", "mp4", "webm", "mov", "avi", "m4v", "ts", "m2ts", "mpg", "mpeg");
    private final Object lifecycle = new Object();
    private final Context context;
    private final String magnet;
    private final int fileIndex;
    private volatile boolean released;
    private File directory;
    private SessionManager manager;
    private TorrentHandle handle;
    private File mediaFile;
    private long fileOffset;
    private long fileSize;
    private int pieceLength;
    private int lastPiece;
    private boolean prioritizeIndex;
    private int priorityEnd = -1;
    private int priorityStart = -1;
    private CinemaTorrentHttpServer http;

    CinemaTorrentStream(Context context, String magnet, int fileIndex) {
        this.context = context.getApplicationContext();
        this.magnet = augmentTrackers(nativeMagnet(magnet));
        this.fileIndex = fileIndex;
    }

    private static String augmentTrackers(String rawMagnet) {
        if (rawMagnet == null || !rawMagnet.startsWith("magnet:?")) return rawMagnet;
        String lower = rawMagnet.toLowerCase(java.util.Locale.ROOT);
        var sb = new StringBuilder(rawMagnet);
        for (String tracker : FAST_PUBLIC_TRACKERS) {
            String encoded = Uri.encode(tracker);
            if (!lower.contains(encoded.toLowerCase(java.util.Locale.ROOT)) && sb.length() + encoded.length() + 4 < 7600) {
                sb.append("&tr=").append(encoded);
            }
        }
        return sb.toString();
    }

    String offerToPlayer() {
        String token = UUID.randomUUID().toString();
        PREPARED.put(token, this);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (PREPARED.remove(token, this)) release();
        }, 15_000);
        return token;
    }

    static CinemaTorrentStream takePrepared(String token) {
        return token == null ? null : PREPARED.remove(token);
    }

    void prepare(IntConsumer progress) throws IOException {
        prepareWithStatus(s -> progress.accept(s.percent()));
    }

    void prepareWithStatus(java.util.function.Consumer<Status> listener) throws IOException {
        listener.accept(new Status(0, 0, 0, 0L, false));
        startIfNeeded(listener);
        preload(listener);
        synchronized (lifecycle) {
            checkActive();
            if (http == null) http = new CinemaTorrentHttpServer(this, fileSize, mediaFile.getName());
        }
        listener.accept(currentStatus(100, true));
    }

    private Status currentStatus(int percent, boolean metadataReady) {
        int seeds = 0;
        int peers = 0;
        long rate = 0L;
        if (handle != null && handle.isValid()) {
            try {
                var st = handle.status();
                seeds = Math.max(st.numSeeds(), st.listSeeds());
                peers = st.numPeers();
                rate = Math.max(0L, st.downloadPayloadRate());
            } catch (Exception ignored) {}
        }
        return new Status(percent, seeds, peers, rate, metadataReady);
    }

    private void preload(java.util.function.Consumer<Status> listener) throws IOException {
        long min = Math.min(fileSize, START_MIN_BYTES);
        long max = Math.min(fileSize, START_MAX_BYTES);
        long contiguous = 0;
        long started = SystemClock.elapsedRealtime();
        long idleUntil = started + PIECE_IDLE_MS;
        long nextAnnounce = started + PEER_REFRESH_MS;
        long sampledAt = started;
        long previousSample = 0;
        long previousDownloaded = 0;
        long lastUiEmit = 0L;
        int steadySamples = 0;
        int shown = 1;
        synchronized (lifecycle) {
            prioritizeWindow(0, max);
        }
        while (true) {
            checkActive();
            long now = SystemClock.elapsedRealtime();
            long totalDone;
            synchronized (lifecycle) {
                checkActive();
                while (contiguous < max && handle.havePiece(pieceAt(contiguous))) {
                    contiguous = Math.min(max, contiguous + bytesInPiece(contiguous));
                }
                totalDone = handle.status().totalDone();
                if (totalDone > previousDownloaded) {
                    previousDownloaded = totalDone;
                    idleUntil = now + PIECE_IDLE_MS;
                }
                nextAnnounce = refreshPeers(nextAnnounce);
            }
            long elapsed = now - sampledAt;
            if (elapsed >= SPEED_SAMPLE_MS) {
                long rate = (contiguous - previousSample) * 1000 / elapsed;
                steadySamples = rate >= START_RATE_BYTES_PER_SECOND ? steadySamples + 1 : 0;
                sampledAt = now;
                previousSample = contiguous;
            }
            if (contiguous >= max || (contiguous >= min && steadySamples >= 1)) {
                synchronized (lifecycle) {
                    checkActive();
                    prioritizeWindow(0, READ_AHEAD_BYTES);
                }
                return;
            }
            // Blend contiguous verified pieces with partial bytes in-flight so progress smoothly advances
            long effectiveBytes = Math.min(max, Math.max(contiguous, Math.min(max - 1, totalDone)));
            int percent = Math.max(shown, Math.min(99, (int) (effectiveBytes * 100 / Math.max(1L, max))));
            if (percent > shown || now - lastUiEmit >= 350L) {
                shown = percent;
                lastUiEmit = now;
                listener.accept(currentStatus(percent, true));
            }
            if (now >= Math.min(idleUntil, started + PIECE_LIMIT_MS)) {
                throw new Failure(com.archos.mediacenter.video.R.string.addon_torrent_peers);
            }
            sleep();
        }
    }

    Uri playbackUri() { return http.uri(); }

    private long bytesInPiece(long position) {
        return pieceLength - (fileOffset + position) % pieceLength;
    }

    void serve(long position, long end, java.io.OutputStream output, BooleanSupplier requestClosed) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        waitForPiece(position, requestClosed);
        try (var file = new RandomAccessFile(mediaFile, "r")) {
            file.seek(position);
            while (position <= end) {
                waitForPiece(position, requestClosed);
                int count = (int) Math.min(buffer.length, Math.min(end - position + 1, bytesInPiece(position)));
                file.readFully(buffer, 0, count);
                output.write(buffer, 0, count);
                position += count;
            }
        }
    }

    private static final java.util.regex.Pattern FILE_SELECTION = java.util.regex.Pattern.compile("&lcfile=([0-9]{1,7})$");

    static int savedFileIndex(String magnet) {
        var match = FILE_SELECTION.matcher(magnet);
        return match.find() ? Integer.parseInt(match.group(1)) : -1;
    }

    static String nativeMagnet(String magnet) {
        return FILE_SELECTION.matcher(magnet).replaceFirst("");
    }

    private void startIfNeeded(java.util.function.Consumer<Status> listener) throws IOException {
        synchronized (lifecycle) {
            checkActive();
            if (handle != null) return;
            if (manager != null) throw new IOException("Torrent unavailable");
            try {
                directory = createCacheDirectory();
                SettingsPack settings = new SettingsPack();
                settings.connectionsLimit(500);
                settings.setEnableDht(true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true);
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connection_speed.swigValue(), 120);
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_connect_timeout.swigValue(), 5);
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.request_timeout.swigValue(), 8);
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.piece_timeout.swigValue(), 10);
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_out_request_queue.swigValue(), 1500);
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.mixed_mode_algorithm.swigValue(), 0);
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP_NODES);
                settings.setMaxMetadataSize(4 * 1024 * 1024);
                settings.listenInterfaces("0.0.0.0:0,[::]:0");
                manager = new SessionManager();
                manager.start(new SessionParams(settings));
                // Keep the magnet's tracker and peer connections when metadata becomes available.
                var hash = AddTorrentParams.parseMagnetUri(magnet).getInfoHashes().getBest();
                manager.download(magnet, directory, TorrentFlags.SEQUENTIAL_DOWNLOAD.or_(TorrentFlags.UPLOAD_MODE)
                        .or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD));
                long deadline = SystemClock.elapsedRealtime() + 10_000;
                while (handle == null && SystemClock.elapsedRealtime() < deadline) {
                    checkActive();
                    TorrentHandle candidate = manager.find(hash);
                    if (candidate != null && candidate.isValid()) handle = candidate;
                    else sleep();
                }
                if (handle == null) throw new IOException("Torrent could not start");
                handle.unsetFlags(TorrentFlags.AUTO_MANAGED);
                handle.swig().set_max_connections(250);
                handle.resume();
                handle.forceReannounce();
                handle.forceDHTAnnounce();

                TorrentInfo info = handle.torrentFile();
                long nextAnnounce = SystemClock.elapsedRealtime() + PEER_REFRESH_MS;
                long lastEmit = 0L;
                deadline = SystemClock.elapsedRealtime() + METADATA_TIMEOUT_MS;
                while ((info == null || !info.isValid()) && SystemClock.elapsedRealtime() < deadline) {
                    checkActive();
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastEmit >= 400L) {
                        lastEmit = now;
                        listener.accept(currentStatus(0, false));
                    }
                    nextAnnounce = refreshPeers(nextAnnounce);
                    sleep();
                    info = handle.torrentFile();
                }
                if (info == null || !info.isValid()) throw new Failure(com.archos.mediacenter.video.R.string.addon_torrent_peers);
                checkActive();
                FileStorage files = info.files();
                int selected = selectFile(files);
                fileSize = files.fileSize(selected);
                fileOffset = files.fileOffset(selected);
                pieceLength = info.pieceLength();
                if (pieceLength <= 0 || fileSize <= 0) throw new IOException("Invalid torrent file");
                if (fileSize > Math.max(0L, directory.getUsableSpace() - STORAGE_RESERVE)) {
                    throw new Failure(com.archos.mediacenter.video.R.string.addon_torrent_storage);
                }
                lastPiece = (int) ((fileOffset + fileSize - 1) / pieceLength);
                mediaFile = new File(directory, files.filePath(selected)).getCanonicalFile();

                Priority[] priorities = new Priority[files.numFiles()];
                Arrays.fill(priorities, Priority.IGNORE);
                priorities[selected] = Priority.DEFAULT;
                handle.prioritizeFiles(priorities);
                deadline = SystemClock.elapsedRealtime() + 5000;
                while (handle.filePriority(selected) == Priority.IGNORE) {
                    checkActive();
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Torrent selection timeout");
                    sleep();
                }
                String path = mediaFile.getName().toLowerCase(java.util.Locale.ROOT);
                prioritizeIndex = path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".mp4")
                        || path.endsWith(".m4v") || path.endsWith(".mov");
                handle.unsetFlags(TorrentFlags.UPLOAD_MODE);
                handle.resume();
                handle.forceReannounce();
            } catch (RuntimeException | LinkageError error) {
                throw new IOException("TV torrent engine unavailable", error);
            }
        }
    }

    private static volatile File activeDirectory;

    static boolean isActiveDirectory(File candidate) {
        File active = activeDirectory;
        if (active == null || candidate == null) return false;
        try {
            return active.getCanonicalPath().equals(candidate.getCanonicalPath());
        } catch (IOException ignored) {
            return active.getAbsolutePath().equals(candidate.getAbsolutePath());
        }
    }

    static long purgeOrphanedCaches(Context context) {
        if (context == null) return 0L;
        long freed = 0L;
        var roots = new java.util.ArrayList<File>();
        roots.add(context.getCacheDir());
        try {
            java.util.Collections.addAll(roots, context.getExternalCacheDirs());
        } catch (Exception ignored) {}
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            File[] children = root.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory() && child.getName().startsWith("addon-torrent-") && !isActiveDirectory(child)) {
                    freed += dirSize(child);
                    deleteTree(child);
                }
            }
        }
        return freed;
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return Math.max(0L, f.length());
        long total = 0L;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) total += dirSize(c);
        }
        return total;
    }

    private File createCacheDirectory() throws IOException {
        purgeOrphanedCaches(context);
        var caches = new java.util.ArrayList<File>();
        caches.add(context.getCacheDir());
        java.util.Collections.addAll(caches, context.getExternalCacheDirs());
        caches.removeIf(file -> file == null || !file.isDirectory() || !file.canWrite());
        caches.sort(java.util.Comparator.comparingLong(File::getUsableSpace).reversed());
        for (File cache : caches) {
            if (cache.getUsableSpace() <= STORAGE_RESERVE) continue;
            try {
                File dir = Files.createTempDirectory(cache.toPath(), "addon-torrent-").toFile();
                activeDirectory = dir;
                return dir;
            } catch (IOException | SecurityException unavailable) {
                // Try the other app-owned cache if this volume is unavailable.
            }
        }
        throw new Failure(com.archos.mediacenter.video.R.string.addon_torrent_storage);
    }

    private static final class Failure extends IOException {
        final int message;

        Failure(int message) {
            this.message = message;
        }
    }

    static int errorMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof Failure failure) return failure.message;
        }
        return com.archos.mediacenter.video.R.string.addon_torrent_error;
    }

    private int selectFile(FileStorage files) throws IOException {
        int best = -1;
        long size = 0;
        String base = directory.getCanonicalPath() + File.separator;
        for (int i = 0; i < files.numFiles(); i++) {
            String path = files.filePath(i);
            if (files.fileAbsolutePath(i) || files.fileFlags(i).and_(FileStorage.FLAG_SYMLINK).eq(FileStorage.FLAG_SYMLINK)
                    || !new File(directory, path).getCanonicalPath().startsWith(base)) {
                throw new IOException("Unsafe torrent file path");
            }
            if (files.padFileAt(i) || files.fileSize(i) <= 0) continue;
            int dot = path.lastIndexOf('.');
            boolean video = dot >= 0 && VIDEO_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
            if (video && (fileIndex == i || (fileIndex < 0 && files.fileSize(i) > size))) {
                best = i;
                size = files.fileSize(i);
            }
        }
        if (best < 0) throw new Failure(com.archos.mediacenter.video.R.string.addon_torrent_video);
        return best;
    }

    private int pieceAt(long position) {
        return (int) ((fileOffset + position) / pieceLength);
    }

    private void prioritizeWindow(long position, long endExclusive) {
        priorityStart = pieceAt(position);
        priorityEnd = pieceAt(Math.min(fileSize, endExclusive) - 1);
        handle.clearPieceDeadlines();
        for (int piece = priorityStart; piece <= priorityEnd; piece++) {
            if (!handle.havePiece(piece)) {
                handle.piecePriority(piece, Priority.TOP_PRIORITY);
                handle.setPieceDeadline(piece, (piece - priorityStart) * PIECE_PRIORITY_STEP_MS);
            }
        }
        if (prioritizeIndex) {
            int firstIndexPiece = pieceAt(Math.max(0L, fileSize - INDEX_BYTES));
            for (int piece = Math.max(firstIndexPiece, priorityEnd + 1); piece <= lastPiece; piece++) {
                if (handle.havePiece(piece)) continue;
                handle.piecePriority(piece, Priority.TOP_PRIORITY);
                // Queue the index just after the startup buffer so both can share a slow peer.
                int indexDeadline = Math.min(priorityEnd - priorityStart + 1, 6) * PIECE_PRIORITY_STEP_MS;
                handle.setPieceDeadline(piece, indexDeadline);
            }
        }
    }

    private void waitForPiece(long position, BooleanSupplier requestClosed) throws IOException {
        int piece = pieceAt(position);
        long started = SystemClock.elapsedRealtime();
        long idleDeadline = started + PIECE_IDLE_MS;
        long nextAnnounce = started + PEER_REFRESH_MS;
        long lastBytes = 0;
        while (true) {
            checkActive();
            synchronized (lifecycle) {
                checkActive();
                if (requestClosed.getAsBoolean()) throw new java.io.InterruptedIOException();
                boolean pieceReady = handle.havePiece(piece);
                if (pieceReady) {
                    int refill = Math.max(1, READER_REFILL_BYTES / pieceLength);
                    if (piece >= priorityStart && piece <= priorityEnd && priorityEnd < lastPiece
                            && priorityEnd - piece < refill) {
                        prioritizeWindow(position, position + READ_AHEAD_BYTES);
                    }
                    return;
                }
                if (piece < priorityStart || piece > priorityEnd) {
                    prioritizeWindow(position, position + READ_AHEAD_BYTES);
                }
                long bytes = handle.status().totalDone();
                if (bytes > lastBytes) {
                    lastBytes = bytes;
                    idleDeadline = SystemClock.elapsedRealtime() + PIECE_IDLE_MS;
                }
                nextAnnounce = refreshPeers(nextAnnounce);
            }
            long now = SystemClock.elapsedRealtime();
            if (now >= Math.min(idleDeadline, started + PIECE_LIMIT_MS)) {
                throw new Failure(com.archos.mediacenter.video.R.string.addon_torrent_peers);
            }
            sleep();
        }
    }

    private long refreshPeers(long nextAnnounce) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextAnnounce) return nextAnnounce;
        if (handle.status().numPeers() < 4) {
            handle.forceReannounce();
            handle.forceDHTAnnounce();
        }
        return now + PEER_REFRESH_MS;
    }

    private void checkActive() throws IOException {
        if (released || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
    }

    private static void sleep() throws java.io.InterruptedIOException {
        try { Thread.sleep(100); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException();
        }
    }

    void release() {
        if (released) return;
        released = true;
        new Thread(() -> {
            synchronized (lifecycle) {
                try {
                    if (http != null) http.close();
                    if (manager != null) manager.stop();
                } catch (RuntimeException | LinkageError ignored) {
                } finally {
                    if (directory != null) {
                        if (isActiveDirectory(directory)) activeDirectory = null;
                        deleteTree(directory);
                    }
                }
            }
        }, "tv-torrent-cleanup").start();
    }

    private static void deleteTree(File root) {
        try {
            Files.walkFileTree(root.toPath(), new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir,
                        IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }
}
