package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.net.URL;
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
 * Persistent direct-video HTTP downloader for CinemaBrowser supporting pause/resume via Range,
 * Android 11+ MediaStore.Downloads output (zero EPERM on Samsung One UI), session header forwarding
 * (User-Agent, Referer, Cookie), X record removal, and permanent disk/MediaStore deletion.
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
        public String filename;
        public String filePath;
        public String stagingPath;
        public String referer;
        public String userAgent;
        public String cookie;
        public long downloadedBytes;
        public long totalBytes;
        public long speedBytesPerSec;
        public String status;
        public String error;
        public long createdAt;

        public DownloadItem(String id, String url, String filename, String filePath,
                            String stagingPath, String referer, String userAgent, String cookie,
                            long downloadedBytes, long totalBytes, long speedBytesPerSec,
                            String status, String error, long createdAt) {
            this.id = id;
            this.url = url;
            this.filename = filename;
            this.filePath = filePath;
            this.stagingPath = stagingPath;
            this.referer = referer == null ? "" : referer;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.cookie = cookie == null ? "" : cookie;
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
                obj.put("filename", filename);
                obj.put("filePath", filePath);
                obj.put("stagingPath", stagingPath == null ? "" : stagingPath);
                obj.put("referer", referer);
                obj.put("userAgent", userAgent);
                obj.put("cookie", cookie);
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
            String fp = obj.optString("filePath", "");
            String sp = obj.optString("stagingPath", fp);
            return new DownloadItem(
                    id,
                    url,
                    obj.optString("filename", "video.mp4"),
                    fp,
                    sp,
                    obj.optString("referer", ""),
                    obj.optString("userAgent", ""),
                    obj.optString("cookie", ""),
                    obj.optLong("downloadedBytes", 0L),
                    obj.optLong("totalBytes", -1L),
                    0L,
                    status,
                    obj.optString("error", ""),
                    obj.optLong("createdAt", System.currentTimeMillis()));
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

    public static String inferFilename(String url, String contentDisposition, String fallbackTitle) {
        if (contentDisposition != null) {
            var m = java.util.regex.Pattern.compile("filename\\*?=(?:UTF-8''|\"?)([^\";\r\n]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentDisposition);
            if (m.find()) {
                String candidate = Uri.decode(m.group(1)).replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
                if (!candidate.isEmpty()) return candidate;
            }
        }
        try {
            String last = Uri.parse(url).getLastPathSegment();
            if (last != null && last.contains(".") && last.length() <= 120) {
                String clean = last.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
                if (!clean.isEmpty()) return clean;
            }
        } catch (Exception ignored) {}
        String base = (fallbackTitle != null && !fallbackTitle.isBlank())
                ? fallbackTitle.replaceAll("[\\\\/:*?\"<>|]+", "_").trim()
                : "LocalCinema-Video";
        if (base.length() > 64) base = base.substring(0, 64);
        return base + ".mp4";
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
        if (url == null || url.isBlank()) return;
        File dir = writableStageDir();
        String safeName = sanitizeFilename(filename, dir);
        File target = new File(dir, safeName);
        DownloadItem item = new DownloadItem(
                UUID.randomUUID().toString(),
                url,
                safeName,
                target.getAbsolutePath(),
                target.getAbsolutePath(),
                referer,
                userAgent == null || userAgent.isBlank() ? DEFAULT_UA : userAgent,
                cookie,
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
        startWorker(item);
    }

    public synchronized void pause(String id) {
        AtomicBoolean flag = pauseFlags.get(id);
        if (flag != null) flag.set(true);
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
            startWorker(found);
        }
    }

    /** Removes entry from Downloads history only, keeping file intact on device. */
    public synchronized void removeRecordOnly(String id) {
        AtomicBoolean flag = pauseFlags.remove(id);
        if (flag != null) flag.set(true);
        items.removeIf(it -> it.id.equals(id));
        saveToPrefs();
        notifyListeners();
    }

    /** Removes entry AND permanently deletes the file (and any .part/.tmp remnants) from storage. */
    public synchronized boolean deleteRecordAndFile(String id) {
        deletedIds.add(id);
        AtomicBoolean flag = pauseFlags.remove(id);
        if (flag != null) flag.set(true);
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
                        if (rem.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            rem.delete();
                        }
                    }
                }
                android.media.MediaScannerConnection.scanFile(
                        app, new String[]{candidatePath}, null, null);
            }
        } catch (Exception ignored) {}
        return deleted;
    }

    private File ensureWritableFile(DownloadItem item) {
        String path = (item.stagingPath != null && !item.stagingPath.isBlank() && !item.stagingPath.startsWith("content://"))
                ? item.stagingPath : item.filePath;
        File candidate = (path != null && !path.isBlank() && !path.startsWith("content://")) ? new File(path) : null;
        if (candidate != null) {
            File parent = candidate.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            if (canWriteFile(candidate)) {
                item.stagingPath = candidate.getAbsolutePath();
                if (item.filePath == null || item.filePath.isBlank()) item.filePath = item.stagingPath;
                return candidate;
            }
        }
        File fallbackDir = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Downloading");
        if (!fallbackDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            fallbackDir.mkdirs();
        File fallback = new File(fallbackDir, sanitizeFilename(item.filename, fallbackDir));
        item.stagingPath = fallback.getAbsolutePath();
        item.filePath = item.stagingPath;
        synchronized (this) { saveToPrefs(); }
        return fallback;
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
            HttpURLConnection conn = null;
            try {
                File file = ensureWritableFile(item);
                long existing = file.isFile() ? file.length() : 0L;
                conn = (HttpURLConnection) new URL(item.url).openConnection();
                activeConns.put(item.id, conn);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", item.userAgent == null || item.userAgent.isBlank() ? DEFAULT_UA : item.userAgent);
                conn.setRequestProperty("Accept", "*/*");
                if (item.referer != null && !item.referer.isBlank()) {
                    conn.setRequestProperty("Referer", item.referer);
                }
                String cookie = item.cookie;
                if ((cookie == null || cookie.isBlank())) {
                    try { cookie = android.webkit.CookieManager.getInstance().getCookie(item.url); } catch (Throwable ignored) {}
                }
                if (cookie != null && !cookie.isBlank()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existing + "-");
                }
                int code = conn.getResponseCode();
                boolean append = (code == 206 && existing > 0);
                if (!append) existing = 0L;
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }
                long contentLen = conn.getContentLengthLong();
                if (contentLen > 0) {
                    item.totalBytes = append ? (existing + contentLen) : contentLen;
                }
                item.downloadedBytes = existing;
                notifyListeners();

                try (InputStream in = conn.getInputStream();
                     RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    if (append) {
                        raf.seek(existing);
                    } else {
                        raf.setLength(0L);
                    }
                    byte[] buf = new byte[32768];
                    int read;
                    long lastUiTs = System.currentTimeMillis();
                    long bytesSinceTick = 0L;
                    while ((read = in.read(buf)) != -1) {
                        if (cancelFlag.get() || deletedIds.contains(item.id)) {
                            if (!deletedIds.contains(item.id)) {
                                item.status = STATUS_PAUSED;
                                item.speedBytesPerSec = 0L;
                                synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                                notifyListeners();
                            }
                            return;
                        }
                        raf.write(buf, 0, read);
                        item.downloadedBytes += read;
                        bytesSinceTick += read;
                        long now = System.currentTimeMillis();
                        if (now - lastUiTs >= 450) {
                            long elapsed = Math.max(1L, now - lastUiTs);
                            item.speedBytesPerSec = (bytesSinceTick * 1000L) / elapsed;
                            lastUiTs = now;
                            bytesSinceTick = 0L;
                            if (!deletedIds.contains(item.id)) {
                                synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                                notifyListeners();
                            }
                        }
                    }
                }
                if (deletedIds.contains(item.id) || cancelFlag.get()) {
                    return;
                }
                publishToDownloadsIfScoped(item, file);
                item.status = STATUS_COMPLETED;
                item.speedBytesPerSec = 0L;
                if (item.totalBytes <= 0) item.totalBytes = item.downloadedBytes;
                synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                notifyListeners();
            } catch (Exception e) {
                if (!cancelFlag.get() && !deletedIds.contains(item.id)) {
                    item.status = STATUS_FAILED;
                    item.error = e.getMessage() == null ? "Download failed" : e.getMessage();
                    item.speedBytesPerSec = 0L;
                    synchronized (BrowserDownloadManager.this) { saveToPrefs(); }
                    notifyListeners();
                }
            } finally {
                activeConns.remove(item.id);
                if (conn != null) conn.disconnect();
                if (deletedIds.remove(item.id)) {
                    purgeFileAndRemnants(item);
                }
            }
        });
    }

    private void publishToDownloadsIfScoped(DownloadItem item, File sourceFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            android.media.MediaScannerConnection.scanFile(app, new String[]{sourceFile.getAbsolutePath()}, null, null);
            return;
        }
        try {
            ContentResolver cr = app.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, item.filename);
            cv.put(MediaStore.Downloads.MIME_TYPE, guessMimeType(item.filename));
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Local Cinema");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return;
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("Cannot open MediaStore output");
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            item.filePath = uri.toString();
            // Remove app-private temp staging file after publishing to public Downloads/Local Cinema
            //noinspection ResultOfMethodCallIgnored
            sourceFile.delete();
        } catch (Exception ignored) {
            item.filePath = sourceFile.getAbsolutePath();
        }
    }

    private static String guessMimeType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    private File writableStageDir() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            File pub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Cinema");
            if ((pub.isDirectory() || pub.mkdirs()) && canDirCreate(pub)) return pub;
        }
        File appDir = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Downloading");
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
                : raw.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        File candidate = new File(dir, base);
        if (!candidate.exists()) return base;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int n = 1; n < 99; n++) {
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
            File appStage = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Downloading");
            if (!appStage.isDirectory()) return 0L;
            File[] children = appStage.listFiles();
            if (children == null) return 0L;
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
        } catch (Exception ignored) {}
        return bytes;
    }

    public void purgeOrphanedStagingFiles() {
        try {
            File appStage = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Downloading");
            if (!appStage.isDirectory()) return;
            File[] children = appStage.listFiles();
            if (children == null) return;
            java.util.Set<String> tracked = trackedLocalFilePaths();
            for (File f : children) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                boolean tempExt = name.endsWith(".part") || name.endsWith(".tmp")
                        || name.endsWith(".download") || name.endsWith(".crdownload");
                if (tempExt || !tracked.contains(f.getAbsolutePath())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {}
    }
}
