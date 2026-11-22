package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Permanent bookmark & recent-site favicon cache stored in `files/bookmark_favicons/<host_hash>.png`
 * with synchronous memory LruCache lookup so cached favicons never flicker or vanish on navigation.
 */
final class SiteFaviconManager {
    private static final int MAX_BYTES = 512 * 1024;
    private static final String DIR_NAME = "bookmark_favicons";
    private static final String LEGACY_DIR_NAME = "favicons";
    private static final Executor WORKER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final LruCache<String, Bitmap> MEMORY_CACHE = new LruCache<>(128);

    private SiteFaviconManager() {}

    private static String hostKey(String address) {
        if (address == null || address.isBlank()) return "";
        try {
            URI site = URI.create(address.trim());
            String host = site.getHost();
            if (host == null || host.isBlank()) return "";
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String fileHash(String host) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(host.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(host.hashCode());
        }
    }

    private static File cacheDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
    }

    private static File cacheFile(Context context, String host) {
        return new File(cacheDir(context), fileHash(host) + ".png");
    }

    /** Synchronous in-memory cache hit for zero-flicker bookmark tile rendering. */
    static Bitmap getImmediate(String address) {
        String host = hostKey(address);
        if (host.isEmpty()) return null;
        return MEMORY_CACHE.get(host);
    }

    static void preload(Context context, List<String> urls) {
        if (context == null || urls == null || urls.isEmpty()) return;
        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            for (String url : urls) {
                String host = hostKey(url);
                if (host.isEmpty() || MEMORY_CACHE.get(host) != null) continue;
                Bitmap disk = readFromDiskOrMigrate(app, url, host);
                if (disk != null) MEMORY_CACHE.put(host, disk);
            }
        });
    }

    static void load(Context context, String address, Consumer<Bitmap> display) {
        load(context, address, true, display);
    }

    static void loadCached(Context context, String address, Consumer<Bitmap> display) {
        load(context, address, false, display);
    }

    private static void load(Context context, String address, boolean fetchMissing, Consumer<Bitmap> display) {
        if (context == null || display == null) return;
        final String host = hostKey(address);
        if (host.isEmpty()) return;

        Bitmap mem = MEMORY_CACHE.get(host);
        if (mem != null) {
            display.accept(mem);
            return;
        }

        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Bitmap existing = readFromDiskOrMigrate(app, address, host);
                if (existing != null) {
                    MEMORY_CACHE.put(host, existing);
                    MAIN.post(() -> display.accept(existing));
                    return;
                }
                if (!fetchMissing) return;

                synchronized (IN_FLIGHT) {
                    if (!IN_FLIGHT.add(host)) return;
                }
                try {
                    Bitmap fetched = fetchBestFavicon(address, host);
                    if (fetched != null) {
                        MEMORY_CACHE.put(host, fetched);
                        savePng(cacheDir(app), cacheFile(app, host), fetched);
                        MAIN.post(() -> display.accept(fetched));
                    }
                } finally {
                    synchronized (IN_FLIGHT) {
                        IN_FLIGHT.remove(host);
                    }
                }
            } catch (Exception ignored) {
                // Keep any previously displayed/cached icon untouched on failure.
            }
        });
    }

    /** Removes a cached favicon ONLY when the user manually deletes a bookmark and no other entry shares its host. */
    static void deleteIfUnused(Context context, String removedUrl, Collection<String> remainingUrls) {
        if (context == null || removedUrl == null) return;
        String host = hostKey(removedUrl);
        if (host.isEmpty()) return;
        if (remainingUrls != null) {
            for (String other : remainingUrls) {
                if (host.equals(hostKey(other))) return;
            }
        }
        MEMORY_CACHE.remove(host);
        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                File f = cacheFile(app, host);
                if (f.isFile()) f.delete();
                File legacyDir = new File(app.getFilesDir(), LEGACY_DIR_NAME);
                URI site = URI.create(removedUrl.trim());
                File legacy = new File(legacyDir, (site.getScheme() + "://" + host).hashCode() + ".ico");
                if (legacy.isFile()) legacy.delete();
            } catch (Exception ignored) {}
        });
    }

    private static Bitmap readFromDiskOrMigrate(Context app, String address, String host) {
        File png = cacheFile(app, host);
        if (png.isFile()) {
            Bitmap bmp = BitmapFactory.decodeFile(png.getAbsolutePath());
            if (bmp != null) return bmp;
        }
        try {
            URI site = URI.create(address.trim());
            File legacy = new File(new File(app.getFilesDir(), LEGACY_DIR_NAME),
                    (site.getScheme() + "://" + host).hashCode() + ".ico");
            if (legacy.isFile()) {
                Bitmap bmp = BitmapFactory.decodeFile(legacy.getAbsolutePath());
                if (bmp != null) {
                    savePng(cacheDir(app), png, bmp);
                    return bmp;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void savePng(File dir, File target, Bitmap bitmap) {
        if (bitmap == null) return;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        AtomicFile file = new AtomicFile(target);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)) {
                file.finishWrite(out);
            } else {
                file.failWrite(out);
            }
        } catch (Exception ignored) {
            if (out != null) file.failWrite(out);
        }
    }

    private static Bitmap fetchBestFavicon(String address, String host) {
        String scheme = "https";
        try {
            String s = URI.create(address.trim()).getScheme();
            if ("http".equalsIgnoreCase(s)) scheme = "http";
        } catch (Exception ignored) {}

        String[] candidates = {
                "https://" + host + "/apple-touch-icon.png",
                scheme + "://" + host + "/favicon.ico",
                "https://www.google.com/s2/favicons?domain=" + URLEncoder.encode(host, StandardCharsets.UTF_8) + "&sz=128"
        };
        for (String candidate : candidates) {
            Bitmap bmp = fetchFollowRedirects(candidate, 4);
            if (bmp != null) return bmp;
        }
        return null;
    }

    private static Bitmap fetchFollowRedirects(String rawUrl, int redirectsLeft) {
        String current = rawUrl;
        for (int hop = 0; hop <= redirectsLeft; hop++) {
            HttpURLConnection conn = null;
            try {
                URI url = URI.create(current);
                String scheme = url.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
                conn = (HttpURLConnection) url.toURL().openConnection();
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(4500);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isBlank()) return null;
                    current = url.resolve(loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) return null;
                try (var input = conn.getInputStream();
                     var buffer = new java.io.ByteArrayOutputStream()) {
                    byte[] chunk = new byte[4096];
                    for (int count; (count = input.read(chunk)) != -1; ) {
                        if (buffer.size() + count > MAX_BYTES) return null;
                        buffer.write(chunk, 0, count);
                    }
                    byte[] bytes = buffer.toByteArray();
                    if (bytes.length < 32) return null;
                    var opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                    if (opts.outWidth <= 0 || opts.outHeight <= 0
                            || opts.outWidth > 2048 || opts.outHeight > 2048) return null;
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = Math.max(1, Integer.highestOneBit(
                            Math.max(opts.outWidth, opts.outHeight) / 128));
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                }
            } catch (Exception ignored) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }
}
