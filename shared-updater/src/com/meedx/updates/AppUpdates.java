package com.meedx.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppUpdates {
    private static final String AUTOMATIC = "automatic";
    private static final int GOLD = 0xffe5a93b;
    private static final int SOFT_BLACK = 0xff171717;

    public record NoteLabels(int heading, int unavailable) {}
    private static final long APK_LIMIT = 1024L * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();

    private AppUpdates() {}

    public static String title(Context context) {
        return label(context, "App Updates", "تحديثات التطبيق");
    }

    public static String displayVersion(String version) {
        return "\u2066" + version + "\u2069";
    }

    public static void startup(Activity activity, boolean tv, NoteLabels notes) {
        if (!preferences(activity).getBoolean(AUTOMATIC, true)) return;
        View decor = activity.getWindow().getDecorView();
        MAIN.postDelayed(() -> {
            if (!available(activity)) return;
            if (activity.hasWindowFocus()) {
                check(activity, tv, false, notes);
            } else {
                // Android may be showing the first-launch permission UI.
                decor.getViewTreeObserver().addOnWindowFocusChangeListener(
                        new ViewTreeObserver.OnWindowFocusChangeListener() {
                            @Override
                            public void onWindowFocusChanged(boolean focused) {
                                if (!focused) return;
                                decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                                if (available(activity) && preferences(activity).getBoolean(AUTOMATIC, true)) {
                                    check(activity, tv, false, notes);
                                }
                            }
                        });
            }
        }, 2000);
    }

    public static void settings(Activity activity, boolean tv, NoteLabels notes) {
        SharedPreferences prefs = preferences(activity);
        new AlertDialog.Builder(activity)
                .setTitle(title(activity) + " · " + displayVersion(installed(activity).versionName))
                .setSingleChoiceItems(new String[]{
                        label(activity, "Check automatically", "الفحص التلقائي"),
                        label(activity, "Manual checks only", "الفحص اليدوي فقط")},
                        prefs.getBoolean(AUTOMATIC, true) ? 0 : 1,
                        (dialog, index) -> prefs.edit().putBoolean(AUTOMATIC, index == 0).apply())
                .setPositiveButton(label(activity, "Check for Updates", "البحث عن تحديثات"),
                        (dialog, which) -> check(activity, tv, true, notes))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void check(Activity activity, boolean tv, boolean manual, NoteLabels notes) {
        if (!CHECKING.compareAndSet(false, true)) {
            if (manual) message(activity, "Checking for updates…", "جارٍ البحث عن تحديثات…");
            return;
        }
        AlertDialog progress = manual
                ? progress(activity, "Checking for updates…", "جارٍ البحث عن تحديثات…", new ProgressBar(activity))
                : null;
        IO.execute(() -> {
            Release found = null;
            Exception failure = null;
            try {
                JSONObject feed = new JSONObject(readFeed(activity));
                JSONObject target = feed.has(tv ? "tv" : "phone")
                        ? feed.getJSONObject(tv ? "tv" : "phone") : feed;
                found = new Release(target);
            } catch (Exception e) {
                failure = e;
                android.util.Log.w("MeedxUpdates", "Update check failed", e);
            }
            Release release = found;
            boolean failed = failure != null;
            MAIN.post(() -> {
                CHECKING.set(false);
                if (progress != null) progress.dismiss();
                if (!available(activity)) return;
                if (failed) {
                    if (manual) message(activity, "Could not check for updates", "تعذّر البحث عن تحديثات");
                } else if (release.code > code(installed(activity))) {
                    offer(activity, tv, release, notes);
                } else if (manual) {
                    String version = displayVersion(installed(activity).versionName);
                    message(activity, "You are using the latest version (" + version + ")",
                            "أنت تستخدم أحدث إصدار (" + version + ")");
                }
            });
        });
    }

    private static String readFeed(Context context) throws IOException {
        String endpoint = UpdateConfig.UPDATE_JSON_URL;
        if (debuggable(context)) {
            String fixture = preferences(context).getString("debug_update_url", "");
            if (!fixture.isEmpty()) endpoint = fixture;
        }
        HttpURLConnection connection = open(context, endpoint);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 256 * 1024) throw new IOException("Feed exceeds size limit");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private static View wrapWithArtBackdrop(Activity activity, boolean tv, View content) {
        if (!tv) return content;
        String[] artNames = {"ic_art_gallery_deer", "ic_art_gallery_landscape",
                "ic_art_gallery_still_life", "ic_art_gallery_horses", "ic_art_gallery_ocean"};
        int idx = (int) (Math.abs(System.currentTimeMillis() / 10_000L) % artNames.length);
        int resId = activity.getResources().getIdentifier(artNames[idx], "drawable", activity.getPackageName());
        if (resId == 0) return content;
        var frame = new android.widget.FrameLayout(activity);
        var art = new android.widget.ImageView(activity);
        art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        art.setImageResource(resId);
        art.setAlpha(0.24f);
        frame.addView(art, new android.widget.FrameLayout.LayoutParams(-1, -1));
        var scrim = new View(activity);
        scrim.setBackgroundColor(0xC8171717);
        frame.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, -1));
        frame.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -2));
        return frame;
    }

    private static void offer(Activity activity, boolean tv, Release release, NoteLabels notes) {
        var card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPaddingRelative(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 8));

        var heading = noteText(activity, label(activity, "New Update Available", "يوجد إصدار جديد متاح")
                + " · " + displayVersion(release.name), 22, GOLD, true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        card.addView(heading);
        String size = release.size > 0 ? Formatter.formatFileSize(activity, release.size)
                : label(activity, "Size unavailable", "الحجم غير متاح");
        card.addView(noteText(activity, size, 14, Color.WHITE, false));

        if (tv) {
            var tvInfo = new LinearLayout(activity);
            tvInfo.setOrientation(LinearLayout.VERTICAL);
            tvInfo.setBackground(noteSurface(activity, 0x26E5A93B, GOLD, 10));
            tvInfo.setPaddingRelative(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
            var infoLp = new LinearLayout.LayoutParams(-1, -2);
            infoLp.topMargin = dp(activity, 12);
            tvInfo.addView(noteText(activity,
                    label(activity, "Official Website: ", "الموقع الرسمي: ")
                            + "\u202A" + UpdateConfig.DOWNLOADS_SITE_DISPLAY + "\u202C",
                    14, Color.WHITE, false));
            tvInfo.addView(noteText(activity,
                    label(activity, "Downloader Code: ", "كود Downloader: ")
                            + "\u202A" + UpdateConfig.DOWNLOADER_CODE + "\u202C",
                    15, GOLD, true));
            card.addView(tvInfo, infoLp);
        }

        var bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        var tint = android.content.res.ColorStateList.valueOf(GOLD);
        bar.setProgressTintList(tint);
        bar.setIndeterminateTintList(tint);
        bar.setVisibility(View.GONE);
        var progressSize = new LinearLayout.LayoutParams(-1, dp(activity, 14));
        progressSize.topMargin = dp(activity, 10);
        card.addView(bar, progressSize);

        var section = noteText(activity, activity.getString(notes.heading()), 16, GOLD, true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(section, true);
        var sectionSize = new LinearLayout.LayoutParams(-1, -2);
        sectionSize.topMargin = dp(activity, 16);
        sectionSize.bottomMargin = dp(activity, 6);
        card.addView(section, sectionSize);

        int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * (tv ? .40f : .36f));
        var scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                        ? maxHeight : Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }
        };
        String changelog = release.notes(activity);
        scroll.addView(noteText(activity, changelog.isBlank() ? activity.getString(notes.unavailable()) : changelog,
                16, Color.WHITE, false));
        if (tv) {
            scroll.setOnFocusChangeListener((view, focused) -> view.setForeground(
                    focused ? noteSurface(activity, Color.TRANSPARENT, GOLD, 6) : null));
        }
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setView(wrapWithArtBackdrop(activity, tv, card))
                .setNegativeButton(label(activity, "Later", "لاحقاً"), null)
                .setPositiveButton(label(activity, "Download", "تحميل"), null)
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(noteSurface(activity, SOFT_BLACK, 0xff665033, 18));
            if (tv) {
                int width = Math.min(dp(activity, 620),
                        (int) (activity.getResources().getDisplayMetrics().widthPixels * .88f));
                dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            } else {
                dialog.getWindow().setGravity(Gravity.BOTTOM);
                dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        }
        var later = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        var start = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        styleNoteButton(activity, later, false);
        styleNoteButton(activity, start, true);
        if (tv) start.post(start::requestFocus);
        start.setOnClickListener(view -> {
            if (!DOWNLOADING.compareAndSet(false, true)) return;
            heading.setText(label(activity, "Downloading update…", "جارٍ تنزيل التحديث…") + " · " + displayVersion(release.name));
            heading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            bar.setVisibility(View.VISIBLE);
            dialog.setCancelable(false);
            start.setVisibility(View.GONE);
            later.setVisibility(View.GONE);
            if (tv) scroll.requestFocus();
            download(activity, release, bar, dialog);
        });
    }

    private static TextView noteText(Activity activity, String text, int size, int color, boolean bold) {
        var view = new TextView(activity);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setText(text);
        return view;
    }

    private static void styleNoteButton(Activity activity, android.widget.Button button, boolean primary) {
        button.setAllCaps(false);
        var states = new StateListDrawable();
        if (primary) {
            button.setTextColor(SOFT_BLACK);
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            states.addState(new int[]{android.R.attr.state_focused}, noteSurface(activity, 0xfff7c968, Color.WHITE, 10));
            states.addState(new int[]{}, noteSurface(activity, GOLD, 0xfff7c968, 10));
        } else {
            button.setTextColor(GOLD);
            states.addState(new int[]{android.R.attr.state_focused}, noteSurface(activity, 0xff362816, GOLD, 10));
            states.addState(new int[]{}, noteSurface(activity, 0xff242019, 0xff5a472d, 10));
        }
        button.setBackground(states);
    }

    private static GradientDrawable noteSurface(Activity activity, int color, int border, int radius) {
        var surface = new GradientDrawable();
        surface.setColor(color);
        surface.setCornerRadius(dp(activity, radius));
        surface.setStroke(dp(activity, 1), border);
        return surface;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Removes already-installed or stale downloaded update APKs from cacheDir/updates/. */
    public static long pruneStaleUpdateApks(Context context, long installedVersionCode) {
        if (context == null) return 0L;
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory()) return 0L;
        File[] files = directory.listFiles();
        if (files == null) return 0L;
        long freed = 0L;
        long staleBefore = System.currentTimeMillis() - (24L * 60L * 60L * 1000L);
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            boolean shouldDelete = false;
            if (name.startsWith("download-") && name.endsWith(".apk")) {
                shouldDelete = f.lastModified() < staleBefore || !DOWNLOADING.get();
            } else if (name.startsWith("update-") && name.endsWith(".apk")) {
                try {
                    long code = Long.parseLong(name.substring("update-".length(), name.length() - ".apk".length()));
                    if (code <= installedVersionCode || f.lastModified() < staleBefore) {
                        shouldDelete = true;
                    }
                } catch (NumberFormatException ignored) {
                    shouldDelete = true;
                }
            }
            if (shouldDelete) {
                long len = Math.max(0L, f.length());
                if (f.delete()) freed += len;
            }
        }
        return freed;
    }

    private static void download(Activity activity, Release release, ProgressBar bar, AlertDialog progress) {
        IO.execute(() -> {
            File directory = new File(activity.getCacheDir(), "updates");
            pruneStaleUpdateApks(activity.getApplicationContext(), release.code - 1);
            File apk = new File(directory, "update-" + release.code + ".apk");
            boolean success = false;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update cache");
                if (apk.isFile()) {
                    try {
                        verify(activity, apk, release);
                        success = true;
                    } catch (Exception invalidCache) {
                        if (!apk.delete()) throw new IOException("Cannot replace cached APK", invalidCache);
                    }
                }
                if (!success) {
                    File partial = File.createTempFile("download-", ".apk", directory);
                    try {
                        transfer(activity, release, partial, bar);
                        verify(activity, partial, release);
                        if (!partial.renameTo(apk)) throw new IOException("Cannot save verified APK");
                        success = true;
                    } finally {
                        partial.delete();
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("MeedxUpdates", "Update download or verification failed", e);
            }
            boolean ready = success;
            MAIN.post(() -> {
                DOWNLOADING.set(false);
                progress.dismiss();
                if (!available(activity)) return;
                if (ready) install(activity, apk);
                else message(activity, "Could not download or verify the update", "تعذّر تنزيل التحديث أو التحقق منه");
            });
        });
    }

    private static void transfer(Context context, Release release, File partial, ProgressBar bar)
            throws IOException {
        HttpURLConnection connection = open(context, release.url);
        long contentLength = connection.getContentLength();
        long expected = release.size > 0 ? release.size : contentLength;
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(partial)) {
            if (expected > APK_LIMIT || (release.size > 0 && contentLength > 0 && release.size != contentLength)) {
                throw new IOException("Invalid update size");
            }
            byte[] buffer = new byte[65536];
            long total = 0;
            int previous = -1;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > APK_LIMIT || (expected > 0 && total > expected)) throw new IOException("APK exceeds size limit");
                output.write(buffer, 0, count);
                if (expected > 0) {
                    int percent = (int) (100 * total / expected);
                    if (percent != previous) {
                        previous = percent;
                        MAIN.post(() -> {
                            bar.setIndeterminate(false);
                            bar.setProgress(percent);
                        });
                    }
                }
            }
            if (expected > 0 && total != expected) throw new IOException("Incomplete download");
        } finally {
            connection.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    private static void verify(Context context, File apk, Release release) throws Exception {
        if (apk.length() == 0 || apk.length() > APK_LIMIT || (release.size > 0 && apk.length() != release.size)) {
            throw new IOException("Unexpected APK size");
        }
        if (!release.sha256.isEmpty()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new java.io.FileInputStream(apk)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!release.sha256.equalsIgnoreCase(hex.toString())) throw new IOException("APK checksum mismatch");
        }

        PackageManager manager = context.getPackageManager();
        int flags = PackageManager.GET_SIGNATURES;
        PackageInfo local = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !local.packageName.equals(candidate.packageName)
                || code(candidate) != release.code || code(candidate) <= code(local)
                || !release.name.equals(candidate.versionName)
                || candidate.signatures == null || local.signatures == null
                || candidate.signatures.length == 0
                || !new HashSet<>(Arrays.asList(local.signatures))
                        .equals(new HashSet<>(Arrays.asList(candidate.signatures)))) {
            throw new IOException("APK identity, version or signer mismatch");
        }
    }

    private static void install(Activity activity, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                message(activity, "Allow updates from this app, then select Update Now again",
                        "اسمح بالتثبيت من هذا التطبيق ثم اضغط تحديث الآن مجدداً");
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
            Intent installer = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installer.setClipData(ClipData.newRawUri("APK", uri));
            activity.startActivity(installer);
        } catch (ActivityNotFoundException | SecurityException e) {
            message(activity, "Android installer is unavailable", "مثبّت أندرويد غير متاح");
        }
    }

    private static HttpURLConnection open(Context context, String address) throws IOException {
        URL url = new URL(address);
        for (int redirect = 0; redirect < 6; redirect++) {
            boolean loopback = url.getHost().equals("127.0.0.1") || url.getHost().equals("localhost");
            if (url.getUserInfo() != null || (!url.getProtocol().equals("https")
                    && !(debuggable(context) && loopback && url.getProtocol().equals("http")))) {
                throw new IOException("Update requires HTTPS");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status == 200) return connection;
            String next = connection.getHeaderField("Location");
            connection.disconnect();
            if (status < 300 || status >= 400 || next == null) throw new IOException("HTTP " + status);
            url = new URL(url, next);
        }
        throw new IOException("Too many redirects");
    }

    private static boolean debuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static PackageInfo installed(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static boolean available(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static AlertDialog progress(Activity activity, String en, String ar, ProgressBar bar) {
        return new AlertDialog.Builder(activity).setTitle(label(activity, en, ar)).setView(bar)
                .setCancelable(false).show();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("meedx_updates", Context.MODE_PRIVATE);
    }

    private static String label(Context context, String en, String ar) {
        return "ar".equals(androidx.core.os.ConfigurationCompat.getLocales(context.getResources().getConfiguration()).get(0).getLanguage()) ? ar : en;
    }

    private static void message(Context context, String en, String ar) {
        Toast.makeText(context, label(context, en, ar), Toast.LENGTH_LONG).show();
    }

    private static final class Release {
        final long code;
        final String name;
        final String url;
        final JSONObject localizedNotes;
        final String changelog;
        final long size;
        final String sha256;

        String notes(Context context) {
            if (localizedNotes == null) return changelog;
            Locale locale = androidx.core.os.ConfigurationCompat.getLocales(context.getResources().getConfiguration()).get(0);
            String local = localizedNotes.optString(locale == null ? "en" : locale.getLanguage(), "").strip();
            return local.isBlank() ? localizedNotes.optString("en", "").strip() : local;
        }

        Release(JSONObject json) throws Exception {
            code = json.getLong("latestVersionCode");
            name = json.getString("latestVersionName");
            url = json.optString("apkUrl");
            localizedNotes = json.optJSONObject("changelog");
            changelog = localizedNotes == null ? json.optString("changelog", "").strip() : "";
            size = json.optLong("sizeBytes", -1);
            sha256 = json.optString("sha256");
            if (code < 1 || name.trim().isEmpty() || size > APK_LIMIT
                    || !UpdateConfig.isOfficialDownload(url)
                    || (!sha256.isEmpty() && !sha256.matches("[a-fA-F0-9]{64}"))) {
                throw new IOException("Invalid update feed");
            }
        }
    }
}
