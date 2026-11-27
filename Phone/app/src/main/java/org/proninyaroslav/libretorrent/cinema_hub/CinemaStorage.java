package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CinemaStorage {
    public static final String DOWNLOAD_FOLDER_NAME = "Download";
    public static final String LEGACY_DOWNLOADING_FOLDER_NAME = "Downloading";
    public static final List<String> SECTIONS = List.of("Anime", DOWNLOAD_FOLDER_NAME, "Movies", "TV Shows");

    public record Drive(String id, String name, File directory) {
        public File library() { return new File(directory, "Local Cinema"); }
    }

    private CinemaStorage() {}

    public static File phoneLibrary() {
        return new File(Environment.getExternalStorageDirectory(), "Local Cinema");
    }

    public static File phoneDownloadFolder() {
        File root = phoneLibrary();
        migrateLegacyDownloadingFolderInRoot(root, null);
        File dl = new File(root, DOWNLOAD_FOLDER_NAME);
        if (!dl.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dl.mkdirs();
        }
        return dl;
    }

    public static String prepare() {
        File root = phoneLibrary();
        return prepare(root) ? root.getAbsolutePath() : null;
    }

    public static boolean prepare(File root) {
        if ((!root.isDirectory() && !root.mkdirs()) || !root.canWrite()) return false;
        migrateLegacyDownloadingFolderInRoot(root, null);
        for (String section : SECTIONS) {
            File folder = new File(root, section);
            if (!folder.isDirectory() && !folder.mkdir()) return false;
        }
        return true;
    }

    /**
     * Safely migrates any files/folders from {@code Local Cinema/Downloading} into
     * {@code Local Cinema/Download} across internal storage, USB drives, and app-scoped staging,
     * updates published library folder paths if present, and deletes the empty legacy folder.
     */
    public static void migrateLegacyDownloadingFolder(Context context) {
        List<String> movedPaths = new ArrayList<>();
        try {
            migrateLegacyDownloadingFolderInRoot(phoneLibrary(), movedPaths);
            if (context != null) {
                for (Drive d : drives(context)) {
                    migrateLegacyDownloadingFolderInRoot(d.library(), movedPaths);
                }
                File appDlRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (appDlRoot != null) {
                    migrateLegacyDownloadingFolderInRoot(appDlRoot, movedPaths);
                }
                updatePublishedFolderPrefs(context);
                if (!movedPaths.isEmpty()) {
                    MediaScannerConnection.scanFile(
                            context.getApplicationContext(),
                            movedPaths.toArray(new String[0]),
                            null,
                            null);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void migrateLegacyDownloadingFolderInRoot(File root, List<String> movedOut) {
        if (root == null || !root.isDirectory()) return;
        File legacy = new File(root, LEGACY_DOWNLOADING_FOLDER_NAME);
        if (!legacy.isDirectory()) return;
        File target = new File(root, DOWNLOAD_FOLDER_NAME);
        if (!target.isDirectory() && !target.mkdirs()) return;

        moveDirectoryContentsSafely(legacy, target, movedOut);
        File[] remaining = legacy.listFiles();
        if (remaining == null || remaining.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
    }

    private static void moveDirectoryContentsSafely(File srcDir, File dstDir, List<String> movedOut) {
        File[] children = srcDir.listFiles();
        if (children == null) return;
        if (!dstDir.isDirectory() && !dstDir.mkdirs()) return;
        for (File child : children) {
            if (child == null || !child.exists()) continue;
            if (child.isDirectory()) {
                File subDst = new File(dstDir, child.getName());
                moveDirectoryContentsSafely(child, subDst, movedOut);
                File[] subRem = child.listFiles();
                if (subRem == null || subRem.length == 0) {
                    //noinspection ResultOfMethodCallIgnored
                    child.delete();
                }
                continue;
            }
            File dest = uniqueDestination(dstDir, child.getName());
            boolean moved = false;
            try {
                Files.move(child.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (Throwable moveErr) {
                if (copyFileFallback(child, dest)) {
                    //noinspection ResultOfMethodCallIgnored
                    child.delete();
                    moved = true;
                }
            }
            if (moved && movedOut != null) {
                movedOut.add(dest.getAbsolutePath());
            }
        }
    }

    private static File uniqueDestination(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 999; i++) {
            File alt = new File(dir, stem + " (" + i + ")" + ext);
            if (!alt.exists()) return alt;
        }
        return new File(dir, stem + "_" + System.currentTimeMillis() + ext);
    }

    private static boolean copyFileFallback(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
            return dst.isFile() && dst.length() == src.length();
        } catch (Throwable e) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return false;
        }
    }

    private static void updatePublishedFolderPrefs(Context context) {
        var sp = context.getSharedPreferences("cinema_library", 0);
        Set<String> existing = sp.getStringSet("published", null);
        if (existing == null || existing.isEmpty()) return;
        Set<String> updated = new HashSet<>();
        boolean changed = false;
        String suffix = File.separator + LEGACY_DOWNLOADING_FOLDER_NAME;
        for (String p : existing) {
            if (p != null && p.endsWith(suffix)) {
                updated.add(p.substring(0, p.length() - LEGACY_DOWNLOADING_FOLDER_NAME.length()) + DOWNLOAD_FOLDER_NAME);
                changed = true;
            } else if (p != null) {
                updated.add(p);
            }
        }
        if (changed) {
            sp.edit().putStringSet("published", updated).apply();
        }
    }

    public static List<Drive> drives(Context context) {
        var drives = new ArrayList<Drive>();
        if (Build.VERSION.SDK_INT < 30) return drives;
        var storage = context.getSystemService(StorageManager.class);
        if (storage == null) return drives;
        for (var volume : storage.getStorageVolumes()) {
            if (!volume.isRemovable() || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
            File directory = volume.getDirectory();
            if (directory == null || volume.getUuid() == null) continue;
            drives.add(new Drive(volume.getUuid(), volume.getDescription(context), directory));
        }
        return drives;
    }

    public static Drive active(Context context) {
        String id = context.getSharedPreferences("cinema_storage", 0).getString("usb", "");
        return drives(context).stream().filter(drive -> id.equals(drive.id()) && drive.library().isDirectory())
                .findFirst().orElse(null);
    }

    public static void select(Context context, Drive drive) {
        context.getSharedPreferences("cinema_storage", 0).edit().putString("usb", drive.id()).apply();
    }

    public static String suggestedSection(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\banime\\b.*")) return "Anime";
        if (lower.matches(".*(?:s\\d{1,2}e\\d{1,3}|\\bseason\\b).*")) return "TV Shows";
        return "Movies";
    }
}
