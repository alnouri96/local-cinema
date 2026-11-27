package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent universal video & HLS downloader for CinemaBrowser saving directly into
 * {@code Local Cinema/Download} with clean {@code <Title> - <Quality>.mp4} filenames,
 * full session header & 302 redirect forwarding, HLS (.m3u8) -> .mp4 muxing,
 * live system progress notifications, and instant Library folder refresh.
 */
public final class BrowserDownloadManager {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String PREF_NAME = "cinema_browser_downloads_v1";
    private static final String KEY_ITEMS = "items_json";
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    public static final class DownloadItem {
        public final String id;
        public final String url;
        public String audioUrl;
        public String kind;
        public int targetHeight;
        public String filename;
        public String filePath;
        public String stagingPath;
        public String referer;
        public String origin;
        public String userAgent;
        public String cookie;
        public String authorization;
        public long downloadedBytes;
        public long totalBytes;
        public long speedBytesPerSec;
        public String status;
        public String error;
        public long createdAt;

        public DownloadItem(String id, String url, String audioUrl, String kind, int targetHeight,
                            String filename, String filePath, String stagingPath,
                            String referer, String origin, String userAgent, String cookie, String authorization,
                            long downloadedBytes, long totalBytes, long speedBytesPerSec,
                            String status, String error, long createdAt) {
            this.id = id;
            this.url = url;
            this.audioUrl = audioUrl == null ? "" : audioUrl;
            this.kind = kind == null ? "progressive" : kind;
            this.targetHeight = targetHeight;
            this.filename = filename;
            this.filePath = filePath;
            this.stagingPath = stagingPath;
            this.referer = referer == null ? "" : referer;
            this.origin = origin == null ? "" : origin;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.cookie = cookie == null ? "" : cookie;
            this.authorization = authorization == null ? "" : authorization;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.speedBytesPerSec = speedBytesPerSec;
            this.status = status;
            this.error = error;
            this.createdAt = createdAt;
        }

        public int progressPercent() {
            if (STATUS_COMPLETED.equals(status)) return 100;
            if (totalBytes <= 0) return 0;
            return (int) Math.max(0, Math.min(100, (downloadedBytes * 100L) / totalBytes));
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("url", url);
                obj.put("audioUrl", audioUrl);
                obj.put("kind", kind);
                obj.put("targetHeight", targetHeight);
                obj.put("filename", filename);
                obj.put("filePath", filePath);
                obj.put("stagingPath", stagingPath == null ? "" : stagingPath);
                obj.put("referer", referer);
                obj.put("origin", origin);
                obj.put("userAgent", userAgent);
                obj.put("cookie", cookie);
                obj.put("authorization", authorization);
                obj.put("downloadedBytes", downloadedBytes);
                obj.put("totalBytes", totalBytes);
                obj.put("status", status);
                obj.put("error", error == null ? "" : error);
                obj.put("createdAt", createdAt);
            } catch (Exception ignored) {}
            return obj;
        }

        static DownloadItem fromJson(JSONObject obj) {
            if (obj == null) return null;
            String id = obj.optString("id", "");
            String url = obj.optString("url", "");
            if (id.isEmpty() || url.isEmpty()) return null;
            String status = obj.optString("status", STATUS_PAUSED);
            if (STATUS_RUNNING.equals(status)) status = STATUS_PAUSED;
            String fp = migratePathString(obj.optString("filePath", ""));
            String sp = migratePathString(obj.optString("stagingPath", fp));
            return new DownloadItem(
                    id,
                    url,
                    obj.optString("audioUrl", ""),
                    obj.optString("kind", "progressive"),
                    obj.optInt("targetHeight", 0),
                    obj.optString("filename", "video.mp4"),
                    fp,
                    sp,
                    obj.optString("referer", ""),
                    obj.optString("origin", ""),
                    obj.optString("userAgent", ""),
                    obj.optString("cookie", ""),
                    obj.optString("authorization", ""),
                    obj.optLong("downloadedBytes", 0L),
                    obj.optLong("totalBytes", -1L),
                    0L,
                    status,
                    obj.optString("error", ""),
                    obj.optLong("createdAt", System.currentTimeMillis()));
        }

        private static String migratePathString(String p) {
            if (p == null || p.isEmpty()) return "";
            return p.replace("/Local Cinema/Downloading/", "/Local Cinema/Download/")
                    .replace("\\Local Cinema\\Downloading\\", "\\Local Cinema\\Download\\")
                    .replace("/Downloads/Downloading/", "/Downloads/Download/");
        }
    }

    private static volatile BrowserDownloadManager instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final List<DownloadItem> items = new ArrayList<>();
    private final Map<String, AtomicBoolean> pauseFlags = new ConcurrentHashMap<>();
    private final Map<String, HttpURLConnection> activeConns = new ConcurrentHashMap<>();
    private final java.util.Set<String> deletedIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();

    private BrowserDownloadManager(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        CinemaStorage.migrateLegacyDownloadingFolder(this.app);
        loadFromPrefs();
    }

    public static BrowserDownloadManager get(Context context) {
        if (instance == null) {
            synchronized (BrowserDownloadManager.class) {
                if (instance == null) {
                    instance = new BrowserDownloadManager(context);
                }
            }
        }
        return instance;
    }

    public static boolean isDirectVideoUrl(String url, String contentType) {
        if (url == null) return false;
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerType.startsWith("video/") && !lowerType.contains("mpegurl")) return true;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String path = uri.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(mp4|mkv|webm|avi|mov|m4v|flv|wmv)(\\?.*)?$");
    }

    public static String buildStreamFilename(String cleanTitle, int height, boolean adaptive, String kind, String url) {
        String title = (cleanTitle == null ? "" : cleanTitle)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (title.isEmpty() || "صائد الفيديوهات".equals(title) || "Browser video".equalsIgnoreCase(title)) {
            title = "Video";
        }
        if (title.length() > 110) title = title.substring(0, 110).trim();

        String qualityTag = "";
        if ("audio".equalsIgnoreCase(kind)) {
            qualityTag = "Audio";
        } else if (height > 0) {
            qualityTag = height + "p";
        } else if (adaptive || "hls".equalsIgnoreCase(kind)) {
            qualityTag = "AUTO";
        }

        String ext = ".mp4";
        if (!"hls".equalsIgnoreCase(kind) && !"split".equalsIgnoreCase(kind)) {
            String path = "";
            try {
                String p = Uri.parse(url == null ? "" : url).getPath();
                if (p != null) path = p.toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {}
            if (path.endsWith(".mkv")) ext = ".mkv";
            else if (path.endsWith(".webm")) ext = ".webm";
            else if (path.endsWith(".mov")) ext = ".mov";
            else if ("audio".equalsIgnoreCase(kind)) ext = ".m4a";
        }

        boolean alreadyHasQuality = !qualityTag.isEmpty()
                && title.toLowerCase(Locale.ROOT).contains(qualityTag.toLowerCase(Locale.ROOT));
        String stem = (!qualityTag.isEmpty() && !alreadyHasQuality)
                ? (title + " - " + qualityTag)
                : title;
        return stem + ext;
    }

    public static String inferFilename(String url, String contentDisposition, String fallbackTitle) {
        String cleanHint = fallbackTitle != null ? CinemaBrowserMediaBridge.cleanVideoTitle(fallbackTitle) : "";
        if (!cleanHint.isBlank()) {
            return buildStreamFilename(cleanHint, 0, false, "progressive", url);
        }
        if (contentDisposition != null) {
            var m = java.util.regex.Pattern.compile("filename\\*?=(?:UTF-8''|\"?)([^\";\r\n]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentDisposition);
            if (m.find()) {
                String candidate = Uri.decode(m.group(1)).replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
                if (!candidate.isEmpty() && !candidate.toLowerCase(Locale.ROOT).endsWith(".m3u8")) return candidate;
            }
        }
        try {
            String last = Uri.parse(url).getLastPathSegment();
            if (last != null && last.contains(".") && last.length() <= 120
                    && !last.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                String clean = last.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
                if (!clean.isEmpty()) return clean;
            }
        } catch (Exception ignored) {}
        return buildStreamFilename(fallbackTitle, 0, false, "progressive", url);
    }

    public synchronized void addListener(Runnable r) {
        if (r != null && !listeners.contains(r)) listeners.add(r);
    }

    public synchronized void removeListener(Runnable r) {
        listeners.remove(r);
    }

    public synchronized List<DownloadItem> listAll() {
        return new ArrayList<>(items);
    }

    public synchronized int activeCount() {
        int c = 0;
        for (DownloadItem it : items) {
            if (STATUS_RUNNING.equals(it.status)) c++;
        }
        return c;
    }

    public void enqueue(String url, String filename, long knownSize) {
        enqueue(url, filename, knownSize, "", DEFAULT_UA, "");
    }

    public void enqueue(String url, String filename, long knownSize,
                        String referer, String userAgent, String cookie) {
        enqueueStream(url, "", "progressive", 0, filename, knownSize, referer, "", userAgent, cookie, "");
    }

    public DownloadItem enqueueStream(String url,
                                      String audioUrl,
                                      String kind,
                                      int targetHeight,
                                      String filename,
                                      long knownSize,
                                      String referer,
                                      String origin,
                                      String userAgent,
                                      String cookie,
                                      String authorization) {
        if (url == null || url.isBlank()) return null;
        File dir = writableOutputDir();
        String safeName = sanitizeFilename(filename, dir);
        File finalFile = new File(dir, safeName);
        File partFile = new File(dir, "." + safeName + ".part");
        DownloadItem item = new DownloadItem(
                UUID.randomUUID().toString(),
                url,
                audioUrl,
                kind,
                targetHeight,
                safeName,
                finalFile.getAbsolutePath(),
                partFile.getAbsolutePath(),
                referer,
                origin,
                userAgent == null || userAgent.isBlank() ? DEFAULT_UA : userAgent,
                cookie,
                authorization,
                0L,
                knownSize,
                0L,
                STATUS_RUNNING,
                "",
                System.currentTimeMillis());
        synchronized (this) {
            items.add(0, item);
            saveToPrefs();
        }
        notifyListeners();
        CinemaMediaDownloader.updateProgressNotification(app, item);
        startWorker(item);
        return item;
    }

    public synchronized void pause(String id) {
        AtomicBoolean flag = pauseFlags.get(id);
        if (flag != null) flag.set(true);
        HttpURLConnection active = activeConns.remove(id);
        if (active != null) {
            pool.execute(() -> {
                try { active.disconnect(); } catch (Exception ignored) {}
            });
        }
        CinemaMediaDownloader.cancelNotification(app, id);
        for (DownloadItem it : items) {
            if (it.id.equals(id) && STATUS_RUNNING.equals(it.status)) {
                it.status = STATUS_PAUSED;
                it.speedBytesPerSec = 0L;
                saveToPrefs();
                notifyListeners();
                break;
            }
        }
    }

    public void resume(String id) {
        DownloadItem found = null;
        synchronized (this) {
            for (DownloadItem it : items) {
                if (it.id.equals(id)) {
                    found = it;
                    it.status = STATUS_RUNNING;
                    it.error = "";
                    saveToPrefs();
                    break;
                }
            }
        }
        if (found != null) {
            notifyListeners();
            CinemaMediaDownloader.updateProgressNotification(app, found);
            startWorker(found);
        }
    }

    /** Removes entry from Downloads history only, keeping file intact on device. */
    public synchronized void removeRecordOnly(String id) {
        AtomicBoolean flag = pauseFlags.remove(id);
        if (flag != null) flag.set(true);
        CinemaMediaDownloader.cancelNotification(app, id);
        items.removeIf(it -> it.id.equals(id));
        saveToPrefs();
        notifyListeners();
    }

    /** Removes entry AND permanently deletes the file (and any .part/.tmp remnants) from storage. */
    public synchronized boolean deleteRecordAndFile(String id) {
        deletedIds.add(id);
        AtomicBoolean flag = pauseFlags.remove(id);
        if (flag != null) flag.set(true);
        CinemaMediaDownloader.cancelNotification(app, id);
        HttpURLConnection active = activeConns.remove(id);
        if (active != null) {
            pool.execute(() -> {
                try { active.disconnect(); } catch (Exception ignored) {}
            });
        }
        DownloadItem target = null;
        for (DownloadItem it : items) {
            if (it.id.equals(id)) {
                target = it;
                break;
            }
        }
        boolean deleted = false;
        if (target != null) {
            items.remove(target);
            saveToPrefs();
            deleted = purgeFileAndRemnants(target);
            notifyListeners();
        }
        return deleted;
    }

    private boolean purgeFileAndRemnants(DownloadItem target) {
        if (target == null) return false;
        boolean deleted = false;
        try {
            if (target.filePath != null && target.filePath.startsWith("content://")) {
                int count = app.getContentResolver().delete(Uri.parse(target.filePath), null, null);
                deleted = count > 0;
            }
            for (String candidatePath : new String[]{target.filePath, target.stagingPath}) {
                if (candidatePath == null || candidatePath.isBlank() || candidatePath.startsWith("content://")) continue;
                File f = new File(candidatePath);
                if (f.exists()) {
                    deleted = f.delete() || deleted;
                }
                File parent = f.getParentFile();
                if (parent != null && parent.isDirectory()) {
                    String baseName = f.getName();
                    for (String ext : new String[]{".part", ".tmp", ".download", ".crdownload"}) {
                        File rem = new File(parent, baseName + ext);
                        if (rem.exists()) //noinspection ResultOfMethodCallIgnored
                            rem.delete();
                        File hiddenRem = new File(parent, "." + baseName + ext);
                        if (hiddenRem.exists()) //noinspection ResultOfMethodCallIgnored
                            hiddenRem.delete();
                    }
                }
                MediaScannerConnection.scanFile(app, new String[]{candidatePath}, null, null);
            }
        } catch (Exception ignored) {}
        return deleted;
    }

    private File ensurePartFile(DownloadItem item) {
        String path = (item.stagingPath != null && !item.stagingPath.isBlank() && !item.stagingPath.startsWith("content://"))
                ? item.stagingPath
                : (item.filePath != null && !item.filePath.startsWith("content://")
                        ? new File(new File(item.filePath).getParentFile(), "." + item.filename + ".part").getAbsolutePath()
                        : null);
        File candidate = (path != null && !path.isBlank()) ? new File(path) : null;
        if (candidate != null) {
            File parent = candidate.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            if (canWriteFile(candidate)) {
                item.stagingPath = candidate.getAbsolutePath();
                return candidate;
            }
        }
        File fallbackDir = appScopedDownloadDir();
        String safeName = sanitizeFilename(item.filename, fallbackDir);
        File finalFallback = new File(fallbackDir, safeName);
        File partFallback = new File(fallbackDir, "." + safeName + ".part");
        item.stagingPath = partFallback.getAbsolutePath();
        item.filePath = finalFallback.getAbsolutePath();
        synchronized (this) { saveToPrefs(); }
        return partFallback;
    }

    private static boolean canWriteFile(File file) {
        try {
            File parent = file.getParentFile();
            if (parent == null || !parent.isDirectory() || !parent.canWrite()) return false;
            try (RandomAccessFile test = new RandomAccessFile(file, "rw")) {
                return test.getFD().valid();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void startWorker(DownloadItem item) {
        deletedIds.remove(item.id);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        pauseFlags.put(item.id, cancelFlag);
        pool.execute(() -> {
            try {
                File partFile = ensurePartFile(item);
                CinemaMediaDownloader.StreamRequest req = new CinemaMediaDownloader.StreamRequest(
                        item.id,
                        item.url,
                        item.audioUrl,
                        item.kind,
                        item.targetHeight,
                        item.referer,
                        item.origin,
                        item.userAgent,
                        item.cookie,
                        item.authorization);

                final long[] lastNotifyMs = {0L};
                CinemaMediaDownloader.downloadToTarget(
                        app,
                        req,
                        partFile,
                        cancelFlag,
                        activeConns,
                        (dl, tot, spd) -> {
                            if (deletedIds.contains(item.id) || cancelFlag.get()) return;
                            item.downloadedBytes = dl;
                            if (tot > 0) item.totalBytes = tot;
                            item.speedBytesPerSec = spd;
                            long now = System.currentTimeMillis();
                            if (now - lastNotifyMs[0] >= 550) {
                                lastNotifyMs[0] = now;
                                synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                                notifyListeners();
                                CinemaMediaDownloader.updateProgressNotification(app, item);
                            }
                        });

                if (deletedIds.contains(item.id) || cancelFlag.get()) {
                    if (!deletedIds.contains(item.id)) {
                        item.status = STATUS_PAUSED;
                        item.speedBytesPerSec = 0L;
                        synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                        notifyListeners();
                        CinemaMediaDownloader.cancelNotification(app, item.id);
                    }
                    return;
                }

                finalizeCompletedFile(item, partFile);
                item.status = STATUS_COMPLETED;
                item.speedBytesPerSec = 0L;
                if (item.totalBytes <= 0 || item.downloadedBytes > item.totalBytes) {
                    item.totalBytes = item.downloadedBytes;
                }
                synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                notifyListeners();
                CinemaMediaDownloader.showCompletedNotification(app, item);
                PhoneModuleBridges.tvHub().notifyLibraryDownloadUpdated(app, item.filePath);
            } catch (Exception e) {
                if (!cancelFlag.get() && !deletedIds.contains(item.id)) {
                    item.status = STATUS_FAILED;
                    item.error = e.getMessage() == null ? "Download failed" : e.getMessage();
                    item.speedBytesPerSec = 0L;
                    synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                    notifyListeners();
                    CinemaMediaDownloader.showFailedNotification(app, item);
                }
            } finally {
                activeConns.remove(item.id);
                if (deletedIds.remove(item.id)) {
                    purgeFileAndRemnants(item);
                }
            }
        });
    }

    private void finalizeCompletedFile(DownloadItem item, File partFile) throws Exception {
        File directLibraryDownload = CinemaStorage.phoneDownloadFolder();
        boolean canWriteLibraryDirectly = (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
                && ((directLibraryDownload.isDirectory() || directLibraryDownload.mkdirs()) && canDirCreate(directLibraryDownload));

        if (canWriteLibraryDirectly) {
            File target = new File(directLibraryDownload, sanitizeFilename(item.filename, directLibraryDownload));
            if (!partFile.equals(target)) {
                try {
                    Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable moveErr) {
                    try (FileInputStream in = new FileInputStream(partFile);
                         OutputStream out = new java.io.FileOutputStream(target)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        out.flush();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                }
            }
            item.filename = target.getName();
            item.filePath = target.getAbsolutePath();
            item.stagingPath = target.getAbsolutePath();
            item.downloadedBytes = target.length();
            item.totalBytes = target.length();
            MediaScannerConnection.scanFile(app, new String[]{target.getAbsolutePath()}, null, null);
            return;
        }

        // Scoped storage fallback when MANAGE_EXTERNAL_STORAGE has not yet been granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentResolver cr = app.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, item.filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, guessMimeType(item.filename));
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Local Cinema/Download");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(partFile);
                         OutputStream out = cr.openOutputStream(uri)) {
                        if (out == null) throw new java.io.IOException("Cannot open MediaStore output");
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        out.flush();
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    item.downloadedBytes = partFile.length();
                    item.totalBytes = partFile.length();
                    item.filePath = uri.toString();
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                    return;
                }
            } catch (Exception ignored) {}
        }

        File localFinal = new File(partFile.getParentFile(), sanitizeFilename(item.filename, partFile.getParentFile()));
        if (!partFile.equals(localFinal)) {
            //noinspection ResultOfMethodCallIgnored
            partFile.renameTo(localFinal);
        }
        item.filePath = localFinal.getAbsolutePath();
        item.stagingPath = localFinal.getAbsolutePath();
        item.downloadedBytes = localFinal.length();
        item.totalBytes = localFinal.length();
        MediaScannerConnection.scanFile(app, new String[]{localFinal.getAbsolutePath()}, null, null);
    }

    private static String guessMimeType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    private File writableOutputDir() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            File libDl = CinemaStorage.phoneDownloadFolder();
            if ((libDl.isDirectory() || libDl.mkdirs()) && canDirCreate(libDl)) return libDl;
        }
        return appScopedDownloadDir();
    }

    private File appScopedDownloadDir() {
        File base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        CinemaStorage.migrateLegacyDownloadingFolderInRoot(base, null);
        File appDir = new File(base, CinemaStorage.DOWNLOAD_FOLDER_NAME);
        if (!appDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            appDir.mkdirs();
        return appDir;
    }

    private static boolean canDirCreate(File dir) {
        try {
            File probe = new File(dir, ".write_test");
            if (probe.createNewFile()) {
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String sanitizeFilename(String raw, File dir) {
        String base = (raw == null || raw.isBlank()) ? "video.mp4"
                : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) base = "video.mp4";
        File candidate = new File(dir, base);
        if (!candidate.exists()) return base;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int n = 1; n < 999; n++) {
            File alt = new File(dir, stem + " (" + n + ")" + ext);
            if (!alt.exists()) return alt.getName();
        }
        return stem + "_" + System.currentTimeMillis() + ext;
    }

    private void loadFromPrefs() {
        try {
            String raw = prefs.getString(KEY_ITEMS, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                DownloadItem item = DownloadItem.fromJson(arr.optJSONObject(i));
                if (item != null) items.add(item);
            }
        } catch (Exception ignored) {}
    }

    private void saveToPrefs() {
        try {
            JSONArray arr = new JSONArray();
            for (DownloadItem it : items) arr.put(it.toJson());
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void notifyListeners() {
        main.post(() -> {
            List<Runnable> snapshot;
            synchronized (BrowserDownloadManager.this) {
                snapshot = new ArrayList<>(listeners);
            }
            for (Runnable r : snapshot) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        });
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private java.util.Set<String> trackedLocalFilePaths() {
        java.util.Set<String> tracked = new java.util.HashSet<>();
        synchronized (this) {
            for (DownloadItem it : items) {
                if (it.filePath != null && !it.filePath.isBlank() && !it.filePath.startsWith("content://")) {
                    tracked.add(new File(it.filePath).getAbsolutePath());
                }
                if (it.stagingPath != null && !it.stagingPath.isBlank() && !it.stagingPath.startsWith("content://")) {
                    tracked.add(new File(it.stagingPath).getAbsolutePath());
                }
            }
        }
        return tracked;
    }

    public long computeOrphanedStagingBytes() {
        long bytes = 0L;
        try {
            File appStage = appScopedDownloadDir();
            if (appStage.isDirectory()) {
                File[] children = appStage.listFiles();
                if (children != null) {
                    java.util.Set<String> tracked = trackedLocalFilePaths();
                    for (File f : children) {
                        if (!f.isFile()) continue;
                        String name = f.getName().toLowerCase(Locale.ROOT);
                        boolean tempExt = name.endsWith(".part") || name.endsWith(".tmp")
                                || name.endsWith(".download") || name.endsWith(".crdownload");
                        if (tempExt || !tracked.contains(f.getAbsolutePath())) {
                            bytes += Math.max(0L, f.length());
                        }
                    }
                }
            }
            for (String tmpName : new String[]{"hls_mux_stage", "stream_mux_stage"}) {
                File d = new File(app.getCacheDir(), tmpName);
                if (d.isDirectory()) {
                    File[] c = d.listFiles();
                    if (c != null) for (File f : c) if (f.isFile()) bytes += f.length();
                }
            }
        } catch (Exception ignored) {}
        return bytes;
    }

    public void purgeOrphanedStagingFiles() {
        try {
            boolean anyRunning = activeCount() > 0;
            File appStage = appScopedDownloadDir();
            if (appStage.isDirectory()) {
                File[] children = appStage.listFiles();
                if (children != null) {
                    java.util.Set<String> tracked = trackedLocalFilePaths();
                    for (File f : children) {
                        if (!f.isFile()) continue;
                        String name = f.getName().toLowerCase(Locale.ROOT);
                        boolean tempExt = name.endsWith(".part") || name.endsWith(".tmp")
                                || name.endsWith(".download") || name.endsWith(".crdownload");
                        if (!anyRunning && (tempExt || !tracked.contains(f.getAbsolutePath()))) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
            }
            if (!anyRunning) {
                for (String tmpName : new String[]{"hls_mux_stage", "stream_mux_stage"}) {
                    File d = new File(app.getCacheDir(), tmpName);
                    if (d.isDirectory()) {
                        File[] c = d.listFiles();
                        if (c != null) for (File f : c) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
