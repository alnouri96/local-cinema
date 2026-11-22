package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class CinemaStorage {
    public static final List<String> SECTIONS = List.of("Anime", "Downloading", "Movies", "TV Shows");

    public record Drive(String id, String name, File directory) {
        public File library() { return new File(directory, "Local Cinema"); }
    }

    private CinemaStorage() {}

    public static File phoneLibrary() {
        return new File(Environment.getExternalStorageDirectory(), "Local Cinema");
    }

    public static String prepare() {
        File root = phoneLibrary();
        return prepare(root) ? root.getAbsolutePath() : null;
    }

    public static boolean prepare(File root) {
        if ((!root.isDirectory() && !root.mkdirs()) || !root.canWrite()) return false;
        for (String section : SECTIONS) {
            File folder = new File(root, section);
            if (!folder.isDirectory() && !folder.mkdir()) return false;
        }
        return true;
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
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*\\banime\\b.*")) return "Anime";
        if (lower.matches(".*(?:s\\d{1,2}e\\d{1,3}|\\bseason\\b).*")) return "TV Shows";
        return "Movies";
    }
}
