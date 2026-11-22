package com.archos.mediacenter.video.leanback.cinema;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.archos.filecorelibrary.FileEditorFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CinemaArtStorage {
    static final String RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/LocalCinemaArt/";
    static final Uri GALLERY = Uri.parse("localcinema://art-gallery");

    record Photo(String name, Uri uri, long size, long changed) {}

    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "";
        };
    }

    static InputStream open(Context context, Uri uri) throws Exception {
        if ("content".equals(uri.getScheme())) return context.getContentResolver().openInputStream(uri);
        return FileEditorFactory.getFileEditorForUrl(uri, context).getInputStream();
    }

    static Bitmap decode(Context context, Uri uri, int maxEdge) throws Exception {
        var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (var input = open(context, uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        try (var input = open(context, uri)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private static File legacyGallery() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LocalCinemaArt");
    }

    static List<Photo> gallery(Context context) {
        var photos = new ArrayList<Photo>();
        if (Build.VERSION.SDK_INT < 29) {
            File[] files = legacyGallery().listFiles();
            if (files != null) for (File file : files) {
                if (file.isFile() && !mime(file.getName()).isEmpty())
                    photos.add(new Photo(file.getName(), Uri.fromFile(file), file.length(), file.lastModified()));
            }
            return photos;
        }
        var collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (var rows = context.getContentResolver().query(collection,
                new String[]{"_id", "_display_name", "_size", "date_modified"},
                "relative_path=? AND is_pending=0", new String[]{RELATIVE_PATH}, null)) {
            if (rows != null) while (rows.moveToNext()) {
                String name = rows.getString(1);
                if (name != null && !mime(name).isEmpty())
                    photos.add(new Photo(name, ContentUris.withAppendedId(collection, rows.getLong(0)),
                            rows.getLong(2), rows.getLong(3) * 1000));
            }
        }
        return photos;
    }

    static void copy(Context context, Photo source, Uri folder) throws Exception {
        String name = source.name();
        if (mime(name).isEmpty() || name.contains("/") || name.contains("\\"))
            throw new IOException("Invalid photo name");
        try (var input = open(context, source.uri())) {
            if (input == null) throw new IOException("Image unavailable");
            if (GALLERY.equals(folder) && Build.VERSION.SDK_INT >= 29) {
                copyToMediaStore(context, name, input);
            } else if ("content".equals(folder.getScheme())) {
                copyToDocumentTree(context, folder, name, input);
            } else {
                if (GALLERY.equals(folder)) {
                    File gallery = legacyGallery();
                    if (!gallery.isDirectory() && !gallery.mkdirs()) throw new IOException("Gallery unavailable");
                    folder = Uri.fromFile(gallery);
                }
                Uri target = folder.buildUpon().appendPath(name).build();
                var editor = FileEditorFactory.getFileEditorForUrl(target, context);
                if (editor == null) throw new IOException("Folder is read only");
                OutputStream output = editor.getOutputStreamExclusive();
                try (output) {
                    transfer(input, output);
                } catch (Exception failure) {
                    editor.delete();
                    throw failure;
                }
            }
        }
    }

    private static void copyToDocumentTree(Context context, Uri folder, String name, InputStream input) throws Exception {
        var resolver = context.getContentResolver();
        var parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(folder,
                android.provider.DocumentsContract.getTreeDocumentId(folder));
        var target = android.provider.DocumentsContract.createDocument(resolver, parent, mime(name), name);
        if (target == null) throw new IOException("Cannot create image");
        try (var output = resolver.openOutputStream(target)) {
            transfer(input, output);
        } catch (Exception failure) {
            android.provider.DocumentsContract.deleteDocument(resolver, target);
            throw failure;
        }
    }

    private static void copyToMediaStore(Context context, String name, InputStream input) throws Exception {
        var resolver = context.getContentResolver();
        var values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime(name));
        values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new IOException("Cannot create image");
        try {
            try (var output = resolver.openOutputStream(target)) {
                transfer(input, output);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(target, values, null, null) != 1) throw new IOException("Cannot publish image");
        } catch (Exception failure) {
            resolver.delete(target, null, null);
            throw failure;
        }
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        if (output == null) throw new IOException("Cannot open destination");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            output.write(buffer, 0, read);
        }
    }

    static boolean delete(Context context, Uri uri) throws Exception {
        if ("content".equals(uri.getScheme())) return context.getContentResolver().delete(uri, null, null) > 0;
        return Boolean.TRUE.equals(FileEditorFactory.getFileEditorForUrl(uri, context).delete());
    }

    private CinemaArtStorage() {}
}
