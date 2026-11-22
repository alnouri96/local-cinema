package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.archos.filecorelibrary.FileEditor;
import com.archos.filecorelibrary.FileEditorFactory;
import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.utils.ShortcutDbAdapter;
import com.archos.mediacenter.video.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Modular file operations, root volume discovery, thumbnail decoding, and glassmorphic
 * D-pad context menu for the unified {@link CinemaFileBrowserActivity}.
 */
final class CinemaFileOperations {
    record Item(String name, Uri uri, boolean directory, long modified, long size) {
        boolean isImage() {
            return !directory && isImageName(name);
        }

        boolean isVideo() {
            return !directory && isVideoName(name);
        }
    }

    interface ProgressListener {
        void onFileProgress(String fileName, int completedFiles, int totalFiles);
    }

    interface SheetCallbacks {
        void onToggleSelect();
        void onSelectAll();
        void onClearSelection();
        void onCopy();
        void onMove();
        void onPaste();
        void onDelete();
    }

    private CinemaFileOperations() {}

    static boolean isImageName(String name) {
        if (name == null) return false;
        return !CinemaArtStorage.mime(name).isEmpty();
    }

    static boolean isVideoName(String name) {
        if (name == null) return false;
        return name.matches("(?i).*\\.(mkv|avi|mp4|m4v|mov|ts|m2ts|webm|mpg|mpeg|wmv|vob|3gp|flv)$");
    }

    static String mimeTypeFor(String name) {
        String img = CinemaArtStorage.mime(name);
        if (!img.isEmpty()) return img;
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mapped = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mapped != null && !mapped.isEmpty()) return mapped;
        }
        return "application/octet-stream";
    }

    static List<Item> listRootSources(Context context, boolean imagesOnly) {
        List<Item> result = new ArrayList<>();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();

        result.add(new Item(context.getString(R.string.cinema_photo_gallery),
                CinemaArtStorage.GALLERY, true, 0, 0));

        File internal = Environment.getExternalStorageDirectory();
        if (internal != null) {
            seenPaths.add(canonicalOrPath(internal));
            result.add(new Item(context.getString(R.string.cinema_internal_storage),
                    Uri.fromFile(internal), true, 0, 0));
        }

        if (Build.VERSION.SDK_INT >= 30) {
            var manager = context.getSystemService(android.os.storage.StorageManager.class);
            if (manager != null) {
                for (var volume : manager.getStorageVolumes()) {
                    File dir = volume.getDirectory();
                    if (!volume.isPrimary() && dir != null && seenPaths.add(canonicalOrPath(dir))) {
                        String desc = volume.getDescription(context);
                        result.add(new Item(desc == null || desc.isBlank() ? dir.getName() : desc,
                                Uri.fromFile(dir), true, 0, 0));
                    }
                }
            }
        }

        // Fallback scan for mounted USB/SD volumes under /storage on Android TV devices
        File storageRoot = new File("/storage");
        File[] mounts = storageRoot.listFiles();
        if (mounts != null) {
            for (File mount : mounts) {
                if (mount == null || !mount.isDirectory()) continue;
                String name = mount.getName();
                if ("emulated".equalsIgnoreCase(name) || "self".equalsIgnoreCase(name)
                        || "enc_emulated".equalsIgnoreCase(name)) continue;
                if (mount.canRead() && seenPaths.add(canonicalOrPath(mount))) {
                    result.add(new Item(context.getString(R.string.cinema_usb_storage_label, name),
                            Uri.fromFile(mount), true, 0, 0));
                }
            }
        }

        try (var cursor = ShortcutDbAdapter.VIDEO.queryAllShortcuts(context)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String source = cursor.getString(cursor.getColumnIndexOrThrow(ShortcutDbAdapter.KEY_PATH));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(ShortcutDbAdapter.KEY_NAME));
                    if (source == null || source.isBlank()) continue;
                    Uri uri = Uri.parse(source);
                    result.add(new Item(name == null || name.isBlank() ? uri.getLastPathSegment() : name,
                            uri, true, 0, 0));
                }
            }
        } catch (Exception ignored) {}

        return result;
    }

    private static String canonicalOrPath(File file) {
        try { return file.getCanonicalPath(); } catch (Exception e) { return file.getAbsolutePath(); }
    }

    static Bitmap loadVideoThumbnail(Context context, Uri uri, int targetSize) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) return null;
        String path = uri.getPath();
        if (path == null || path.isBlank()) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            Bitmap frame = null;
            if (Build.VERSION.SDK_INT >= 27) {
                frame = retriever.getScaledFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetSize, targetSize);
            }
            if (frame == null) {
                Bitmap raw = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (raw != null) {
                    float scale = Math.min(1f, (float) targetSize / Math.max(raw.getWidth(), raw.getHeight()));
                    int w = Math.max(1, Math.round(raw.getWidth() * scale));
                    int h = Math.max(1, Math.round(raw.getHeight() * scale));
                    frame = Bitmap.createScaledBitmap(raw, w, h, true);
                    if (frame != raw) raw.recycle();
                }
            }
            return frame;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) {}
        }
    }

    static int countUnits(Context context, List<Item> items) {
        int count = 0;
        for (Item item : items) {
            count += countItemUnits(context, item, 0);
        }
        return Math.max(1, count);
    }

    private static int countItemUnits(Context context, Item item, int depth) {
        if (!item.directory() || depth > 8) return 1;
        if ("file".equalsIgnoreCase(item.uri().getScheme()) && item.uri().getPath() != null) {
            File dir = new File(item.uri().getPath());
            File[] children = dir.listFiles();
            if (children == null || children.length == 0) return 1;
            int total = 0;
            for (File child : children) {
                total += countItemUnits(context, new Item(child.getName(), Uri.fromFile(child),
                        child.isDirectory(), child.lastModified(), child.isDirectory() ? 0 : child.length()), depth + 1);
            }
            return Math.max(1, total);
        }
        return 1;
    }

    static boolean deleteItem(Context context, Item item) throws Exception {
        if (item == null || item.uri() == null) return false;
        Uri uri = item.uri();
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return deleteFileRecursively(new File(uri.getPath()));
        }
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try {
                if (DocumentsContract.deleteDocument(context.getContentResolver(), uri)) return true;
            } catch (Exception ignored) {}
            return context.getContentResolver().delete(uri, null, null) > 0;
        }
        if (item.directory()) {
            try {
                for (var child : RawListerFactoryWithUpnp.getRawListerForUrl(uri).getFileList()) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    String name = child.getName();
                    if (name == null || ".".equals(name) || "..".equals(name)) continue;
                    deleteItem(context, new Item(name, child.getUri(), child.isDirectory(), child.lastModified(), 0));
                }
            } catch (Exception ignored) {}
        }
        FileEditor editor = FileEditorFactory.getFileEditorForUrl(uri, context);
        return editor != null && Boolean.TRUE.equals(editor.delete());
    }

    private static boolean deleteFileRecursively(File file) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        if (!file.exists()) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteFileRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    static void copyOrMoveItem(Context context, Item source, Uri destFolder, boolean move,
                               int[] counter, int total, ProgressListener listener) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        if (source == null || source.uri() == null || destFolder == null) {
            throw new IOException("Invalid source or destination");
        }
        if (CinemaArtStorage.GALLERY.equals(destFolder)) {
            if (source.directory()) throw new IOException("Cannot paste folder into Art Gallery");
            CinemaArtStorage.copy(context, new CinemaArtStorage.Photo(source.name(), source.uri(),
                    source.size(), source.modified()), destFolder);
            if (move) deleteItem(context, source);
            counter[0]++;
            if (listener != null) listener.onFileProgress(source.name(), counter[0], total);
            return;
        }

        if ("content".equalsIgnoreCase(destFolder.getScheme())) {
            String parentDocId = DocumentsContract.getTreeDocumentId(destFolder);
            Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(destFolder, parentDocId);
            copyIntoDocumentTree(context, source, destFolder, parentDoc, move, counter, total, listener);
            return;
        }

        if ("file".equalsIgnoreCase(destFolder.getScheme()) && destFolder.getPath() != null) {
            File targetDir = new File(destFolder.getPath());
            if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
                throw new IOException("Destination folder is not writable");
            }
            if (!targetDir.canWrite()) {
                throw new SecurityException("USB or folder requires write permission");
            }
            copyOrMoveToLocalDir(context, source, targetDir, move, counter, total, listener);
            return;
        }

        // Network / SMB / WebDAV destination via FileEditorFactory
        copyOrMoveToRemoteDir(context, source, destFolder, move, counter, total, listener);
    }

    private static void copyOrMoveToLocalDir(Context context, Item source, File destDir, boolean move,
                                              int[] counter, int total, ProgressListener listener) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        File srcFile = "file".equalsIgnoreCase(source.uri().getScheme()) && source.uri().getPath() != null
                ? new File(source.uri().getPath()) : null;

        if (srcFile != null && srcFile.isDirectory()) {
            String srcCanon = canonicalOrPath(srcFile);
            String dstCanon = canonicalOrPath(destDir);
            if (dstCanon.equals(srcCanon) || dstCanon.startsWith(srcCanon + File.separator)) {
                throw new IOException("Cannot copy folder into itself");
            }
        }

        File target = uniqueLocalTarget(destDir, source.name(), move && srcFile != null
                && destDir.equals(srcFile.getParentFile()));
        if (move && srcFile != null && srcFile.equals(target)) {
            counter[0]++;
            if (listener != null) listener.onFileProgress(source.name(), counter[0], total);
            return;
        }

        // Fast atomic move when on same mount
        if (move && srcFile != null && srcFile.exists() && srcFile.renameTo(target)) {
            counter[0]++;
            if (listener != null) listener.onFileProgress(source.name(), counter[0], total);
            return;
        }

        if (source.directory()) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("Cannot create folder " + target.getName());
            }
            if (srcFile != null) {
                File[] children = srcFile.listFiles();
                if (children != null) {
                    for (File child : children) {
                        Item childItem = new Item(child.getName(), Uri.fromFile(child),
                                child.isDirectory(), child.lastModified(), child.isDirectory() ? 0 : child.length());
                        copyOrMoveToLocalDir(context, childItem, target, move, counter, total, listener);
                    }
                }
                if (move) srcFile.delete();
            } else {
                for (var child : RawListerFactoryWithUpnp.getRawListerForUrl(source.uri()).getFileList()) {
                    String name = child.getName();
                    if (name == null || ".".equals(name) || "..".equals(name)) continue;
                    Item childItem = new Item(name, child.getUri(), child.isDirectory(),
                            child.lastModified(), child.isDirectory() ? 0 : child.getComputedLength());
                    copyOrMoveToLocalDir(context, childItem, target, move, counter, total, listener);
                }
                if (move) deleteItem(context, source);
            }
            return;
        }

        try (InputStream in = openInput(context, source.uri());
             OutputStream out = new FileOutputStream(target)) {
            transfer(in, out);
        } catch (Exception failure) {
            target.delete();
            throw failure;
        }
        if (move) deleteItem(context, source);
        counter[0]++;
        if (listener != null) listener.onFileProgress(source.name(), counter[0], total);
    }

    private static void copyIntoDocumentTree(Context context, Item source, Uri treeUri, Uri parentDoc,
                                             boolean move, int[] counter, int total, ProgressListener listener) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        var resolver = context.getContentResolver();
        if (source.directory()) {
            Uri subDir = DocumentsContract.createDocument(resolver, parentDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, source.name());
            if (subDir == null) throw new IOException("Cannot create folder on USB");
            if ("file".equalsIgnoreCase(source.uri().getScheme()) && source.uri().getPath() != null) {
                File[] children = new File(source.uri().getPath()).listFiles();
                if (children != null) {
                    for (File child : children) {
                        copyIntoDocumentTree(context, new Item(child.getName(), Uri.fromFile(child),
                                child.isDirectory(), child.lastModified(), child.isDirectory() ? 0 : child.length()),
                                treeUri, subDir, move, counter, total, listener);
                    }
                }
                if (move) deleteFileRecursively(new File(source.uri().getPath()));
            } else {
                for (var child : RawListerFactoryWithUpnp.getRawListerForUrl(source.uri()).getFileList()) {
                    String name = child.getName();
                    if (name == null || ".".equals(name) || "..".equals(name)) continue;
                    copyIntoDocumentTree(context, new Item(name, child.getUri(), child.isDirectory(),
                            child.lastModified(), child.isDirectory() ? 0 : child.getComputedLength()),
                            treeUri, subDir, move, counter, total, listener);
                }
                if (move) deleteItem(context, source);
            }
            return;
        }
        Uri doc = DocumentsContract.createDocument(resolver, parentDoc, mimeTypeFor(source.name()), source.name());
        if (doc == null) throw new IOException("Cannot create file on USB");
        try (InputStream in = openInput(context, source.uri());
             OutputStream out = resolver.openOutputStream(doc)) {
            transfer(in, out);
        } catch (Exception failure) {
            try { DocumentsContract.deleteDocument(resolver, doc); } catch (Exception ignored) {}
            throw failure;
        }
        if (move) deleteItem(context, source);
        counter[0]++;
        if (listener != null) listener.onFileProgress(source.name(), counter[0], total);
    }

    private static void copyOrMoveToRemoteDir(Context context, Item source, Uri destFolder, boolean move,
                                              int[] counter, int total, ProgressListener listener) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        Uri targetUri = destFolder.buildUpon().appendPath(source.name()).build();
        if (source.directory()) {
            FileEditor dirEditor = FileEditorFactory.getFileEditorForUrl(targetUri, context);
            if (dirEditor == null) throw new IOException("Destination is read only");
            dirEditor.mkdir();
            if ("file".equalsIgnoreCase(source.uri().getScheme()) && source.uri().getPath() != null) {
                File[] children = new File(source.uri().getPath()).listFiles();
                if (children != null) {
                    for (File child : children) {
                        copyOrMoveToRemoteDir(context, new Item(child.getName(), Uri.fromFile(child),
                                child.isDirectory(), child.lastModified(), child.isDirectory() ? 0 : child.length()),
                                targetUri, move, counter, total, listener);
                    }
                }
                if (move) deleteFileRecursively(new File(source.uri().getPath()));
            } else {
                for (var child : RawListerFactoryWithUpnp.getRawListerForUrl(source.uri()).getFileList()) {
                    String name = child.getName();
                    if (name == null || ".".equals(name) || "..".equals(name)) continue;
                    copyOrMoveToRemoteDir(context, new Item(name, child.getUri(), child.isDirectory(),
                            child.lastModified(), child.isDirectory() ? 0 : child.getComputedLength()),
                            targetUri, move, counter, total, listener);
                }
                if (move) deleteItem(context, source);
            }
            return;
        }
        FileEditor editor = FileEditorFactory.getFileEditorForUrl(targetUri, context);
        if (editor == null) throw new IOException("Destination is read only");
        try (InputStream in = openInput(context, source.uri());
             OutputStream out = editor.getOutputStreamExclusive()) {
            transfer(in, out);
        } catch (Exception failure) {
            try { editor.delete(); } catch (Exception ignored) {}
            throw failure;
        }
        if (move) deleteItem(context, source);
        counter[0]++;
        if (listener != null) listener.onFileProgress(source.name(), counter[0], total);
    }

    private static InputStream openInput(Context context, Uri uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Source unavailable");
            return in;
        }
        FileEditor editor = FileEditorFactory.getFileEditorForUrl(uri, context);
        if (editor == null) throw new IOException("Source unavailable");
        return editor.getInputStream();
    }

    private static File uniqueLocalTarget(File destDir, String name, boolean allowSame) {
        File candidate = new File(destDir, name);
        if (allowSame || !candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File alt = new File(destDir, stem + " (" + i + ")" + ext);
            if (!alt.exists()) return alt;
        }
        return candidate;
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        if (input == null || output == null) throw new IOException("Stream unavailable");
        byte[] buf = new byte[64 * 1024];
        int read;
        while ((read = input.read(buf)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            output.write(buf, 0, read);
        }
        output.flush();
    }

    static void showItemActionSheet(Activity host, Item item, boolean isMarked, int markedCount,
                                    boolean hasClipboard, boolean canPasteHere, SheetCallbacks cb) {
        int dp = CinemaUi.dp(host, 1);
        boolean rtl = CinemaUi.arabic(host);
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(dp * 18, dp * 14, dp * 18, dp * 16);

        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF21201D, 0xFF171717});
        glass.setCornerRadius(dp * 18);
        glass.setStroke(dp, 0x66E5A93B);
        panel.setBackground(glass);

        TextView header = CinemaUi.text(host, 18, true);
        header.setTextColor(CinemaTokens.GOLD);
        header.setMaxLines(2);
        header.setText(markedCount > 1
                ? host.getString(R.string.cinema_file_selected_count_title, markedCount)
                : item.name());
        header.setPadding(dp * 4, 0, dp * 4, dp * 10);
        panel.addView(header);

        AlertDialog dialog = CinemaUi.dialog(host).setView(panel).create();

        View firstBtn = addSheetAction(host, panel,
                isMarked ? host.getString(R.string.cinema_file_deselect) : host.getString(R.string.cinema_file_select),
                () -> { dialog.dismiss(); cb.onToggleSelect(); });

        if (markedCount > 0) {
            addSheetAction(host, panel, host.getString(R.string.cinema_file_clear_selection),
                    () -> { dialog.dismiss(); cb.onClearSelection(); });
        } else {
            addSheetAction(host, panel, host.getString(R.string.cinema_file_select_all),
                    () -> { dialog.dismiss(); cb.onSelectAll(); });
        }

        addSheetAction(host, panel, host.getString(R.string.cinema_file_copy),
                () -> { dialog.dismiss(); cb.onCopy(); });

        addSheetAction(host, panel, host.getString(R.string.cinema_file_move),
                () -> { dialog.dismiss(); cb.onMove(); });

        if (hasClipboard && canPasteHere) {
            addSheetAction(host, panel, host.getString(R.string.cinema_file_paste_here),
                    () -> { dialog.dismiss(); cb.onPaste(); });
        }

        addSheetAction(host, panel, host.getString(R.string.cinema_file_delete),
                () -> { dialog.dismiss(); cb.onDelete(); }, true);

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.min(CinemaUi.dp(host, 420), host.getResources().getDisplayMetrics().widthPixels * 82 / 100);
            dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        CinemaUi.focusDialogControl(dialog, firstBtn);
    }

    private static View addSheetAction(Activity host, LinearLayout parent, String label, Runnable action) {
        return addSheetAction(host, parent, label, action, false);
    }

    private static View addSheetAction(Activity host, LinearLayout parent, String label, Runnable action, boolean destructive) {
        int rad = CinemaUi.dp(host, 12);
        TextView btn = CinemaUi.text(host, 16, true);
        btn.setText(label);
        btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        btn.setPaddingRelative(CinemaUi.dp(host, 16), CinemaUi.dp(host, 12),
                CinemaUi.dp(host, 16), CinemaUi.dp(host, 12));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setMinimumHeight(CinemaUi.dp(host, 48));

        int restText = destructive ? 0xFFFCA5A5 : Color.WHITE;
        int restBg = destructive ? 0xFF2A1B1B : 0xFF23221F;
        int restBorder = destructive ? 0xFF5A2B2B : 0xFF3E362B;
        int focusBg = destructive ? 0xFFDC2626 : CinemaTokens.GOLD;
        int focusText = destructive ? Color.WHITE : 0xFF141517;

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = CinemaUi.rounded(focusBg, rad, 0);
        focused.setStroke(CinemaUi.dp(host, 2), CinemaTokens.GOLD);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, CinemaUi.rounded(restBg, rad, restBorder));
        btn.setBackground(bg);
        btn.setTextColor(restText);
        btn.setOnFocusChangeListener((v, hasFocus) -> btn.setTextColor(hasFocus ? focusText : restText));
        btn.setOnClickListener(v -> action.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CinemaUi.dp(host, 8);
        parent.addView(btn, lp);
        return btn;
    }
}
