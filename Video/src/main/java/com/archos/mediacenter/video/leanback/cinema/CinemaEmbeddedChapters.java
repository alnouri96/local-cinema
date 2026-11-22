package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.net.Uri;

import com.archos.filecorelibrary.FileEditorFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Reads embedded chapter tables with bounded reads separate from the video decoder. */
final class CinemaEmbeddedChapters {
    record Chapter(long start, long end, String title) {}
    private record Element(long id, int data, int end) {}
    private record Box(long data, long end) {}

    private static final long SEGMENT = 0x18538067;
    private static final long SEEK_HEAD = 0x114D9B74;
    private static final long CHAPTERS = 0x1043A770;
    private static final int MAX_READ = 256 * 1024;

    static List<Chapter> read(Context context, Uri uri) {
        String path = uri.getPath();
        if (path == null) return List.of();
        path = path.toLowerCase(Locale.ROOT);
        try {
            if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")) {
                return readMp4(context, uri);
            }
            if (!path.endsWith(".mkv") && !path.endsWith(".webm")) return List.of();
            ByteBuffer data = readAt(context, uri, 0, MAX_READ);
            Element header = element(data, data.limit());
            if (header == null || header.id != 0x1A45DFA3) return List.of();
            data.position(header.end);
            Element segment = element(data, data.limit());
            if (segment == null || segment.id != SEGMENT) return List.of();
            long seek = -1;
            for (Element child; (child = element(data, segment.end)) != null; data.position(child.end)) {
                if (child.id == CHAPTERS) return readEditions(data, child);
                if (child.id == SEEK_HEAD) seek = findChapterPosition(data, child);
            }
            if (seek < 0 || seek > Long.MAX_VALUE - segment.data) return List.of();
            data = readAt(context, uri, segment.data + seek, MAX_READ);
            Element chapters = element(data, data.limit());
            if (chapters != null && chapters.id == CHAPTERS) return readEditions(data, chapters);
        } catch (IOException | RuntimeException ignored) {
            // A malformed or unavailable chapter table must not interrupt playback.
        }
        return List.of();
    }

    private static List<Chapter> readMp4(Context context, Uri uri) throws IOException {
        Box movie = findBox(context, uri, new Box(0, Long.MAX_VALUE), 0x6D6F6F76); // moov
        if (movie == null) return List.of();
        Box userData = findBox(context, uri, movie, 0x75647461); // udta
        if (userData == null) return List.of();
        Box chapters = findBox(context, uri, userData, 0x6368706C); // chpl
        if (chapters == null || chapters.end - chapters.data > 70_000) return List.of();
        ByteBuffer payload = readAt(context, uri, chapters.data, (int) (chapters.end - chapters.data));
        if (payload.remaining() < 5) return List.of();
        int version = payload.getInt() >>> 24;
        if (version == 1 && payload.remaining() >= 5) payload.getInt();
        else if (version != 0) return List.of();
        int count = payload.get() & 255;
        var result = new ArrayList<Chapter>();
        for (int i = 0; i < count; i++) {
            if (payload.remaining() < 9) return List.of();
            long time = payload.getLong();
            int size = payload.get() & 255;
            if (time < 0 || payload.remaining() < size) return List.of();
            byte[] title = new byte[size];
            payload.get(title);
            result.add(new Chapter(time / 10_000, -1, new String(title, StandardCharsets.UTF_8)));
        }
        result.sort(Comparator.comparingLong(Chapter::start));
        return result;
    }

    private static Box findBox(Context context, Uri uri, Box parent, int wanted) throws IOException {
        long offset = parent.data;
        long blockStart = 0;
        ByteBuffer block = ByteBuffer.allocate(0);
        int reads = 0;
        for (int i = 0; i < 96 && parent.end - offset >= 8; i++) {
            if (offset - blockStart > block.limit() - 16L) {
                if (++reads > 8) break;
                block = readAt(context, uri, offset, (int) Math.min(8192, parent.end - offset));
                blockStart = offset;
            }
            int header = (int) (offset - blockStart);
            if (block.limit() - header < 8) break;
            block.position(header);
            long size = Integer.toUnsignedLong(block.getInt());
            int type = block.getInt();
            if (size == 1) {
                if (block.remaining() < 8) break;
                size = block.getLong();
            } else if (size == 0) {
                size = parent.end - offset;
            }
            int headerSize = block.position() - header;
            if (size < headerSize || size > parent.end - offset) break;
            if (type == wanted) return new Box(offset + headerSize, offset + size);
            offset += size;
        }
        return null;
    }

    private static long findChapterPosition(ByteBuffer data, Element head) {
        for (Element entry; (entry = element(data, head.end)) != null; data.position(entry.end)) {
            if (entry.id != 0x4DBB) continue;
            long id = -1;
            long position = -1;
            for (Element field; (field = element(data, entry.end)) != null; data.position(field.end)) {
                if (field.id == 0x53AB) id = uint(data, field);
                if (field.id == 0x53AC) position = uint(data, field);
            }
            if (id == CHAPTERS && position >= 0) return position;
        }
        return -1;
    }

    private static List<Chapter> readEditions(ByteBuffer data, Element chapters) {
        List<Chapter> first = List.of();
        for (Element edition; (edition = element(data, chapters.end)) != null; data.position(edition.end)) {
            if (edition.id != 0x45B9) continue;
            boolean isDefault = false;
            boolean ordered = false;
            boolean hidden = false;
            List<Chapter> result = new ArrayList<>();
            for (Element child; (child = element(data, edition.end)) != null; data.position(child.end)) {
                if (child.id == 0x45DB) isDefault = uint(data, child) == 1;
                if (child.id == 0x45DD) ordered = uint(data, child) == 1;
                if (child.id == 0x45BD) hidden = uint(data, child) == 1;
                if (child.id == 0xB6 && result.size() < 512) readChapter(data, child, result);
            }
            if (ordered || hidden || result.isEmpty()) continue;
            result.sort(Comparator.comparingLong(Chapter::start));
            if (isDefault) return result;
            if (first.isEmpty()) first = result;
        }
        return first;
    }

    private static void readChapter(ByteBuffer data, Element atom, List<Chapter> chapters) {
        String title = "";
        long start = -1;
        long end = -1;
        boolean enabled = true;
        for (Element field; (field = element(data, atom.end)) != null; data.position(field.end)) {
            if (field.id == 0x91) start = uint(data, field);
            if (field.id == 0x92) end = uint(data, field);
            if (field.id == 0x4598 && uint(data, field) == 0) enabled = false;
            if (field.id == 0x80 && title.isEmpty()) {
                for (Element display; (display = element(data, field.end)) != null; data.position(display.end)) {
                    int size = display.end - display.data;
                    if (display.id == 0x85 && size > 0 && size <= 512) {
                        title = new String(data.array(), display.data, size, StandardCharsets.UTF_8);
                        break;
                    }
                }
            }
        }
        if (enabled && start >= 0) chapters.add(new Chapter(start / 1_000_000,
                end < 0 ? -1 : end / 1_000_000, title));
    }

    private static long uint(ByteBuffer data, Element field) {
        int size = field.end - field.data;
        if (size < 1 || size > 8) return -1;
        long value = 0;
        for (int i = field.data; i < field.end; i++) value = (value << 8) | (data.get(i) & 255);
        return value;
    }

    private static Element element(ByteBuffer data, int limit) {
        long id = vint(data, limit, true);
        long size = vint(data, limit, false);
        if (id < 0 || size == -1) return null;
        int start = data.position();
        int remaining = limit - start;
        // Only the top-level Segment can span the entire input file.
        if (id != SEGMENT && (size < 0 || size > remaining)) return null;
        int end = size < 0 || size > remaining ? limit : start + (int) size;
        return new Element(id, start, end);
    }

    private static long vint(ByteBuffer data, int limit, boolean keepMarker) {
        if (data.position() >= limit) return -1;
        int first = data.get() & 255;
        if (first == 0) return -1;
        int length = Integer.numberOfLeadingZeros(first) - 23;
        if (length - 1 > limit - data.position() || (keepMarker && length > 4)) return -1;
        long value = keepMarker ? first : first & (0xFF >>> length);
        for (int i = 1; i < length; i++) value = (value << 8) | (data.get() & 255);
        return !keepMarker && value == (1L << (7 * length)) - 1 ? -2 : value;
    }

    private static ByteBuffer readAt(Context context, Uri uri, long offset, int limit) throws IOException {
        if (limit <= 0) return ByteBuffer.allocate(0);
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            try (var input = new FileInputStream(uri.getPath())) {
                input.getChannel().position(offset);
                return readBlock(input, limit);
            }
        }
        if ("smb".equals(scheme)) {
            try (var input = FileEditorFactory.getFileEditorForUrl(uri, context).getInputStream(offset)) {
                return readBlock(input, limit);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) return ByteBuffer.allocate(0);
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(1800);
        connection.setReadTimeout(1800);
        long end = offset + Math.min(limit - 1L, Long.MAX_VALUE - offset);
        connection.setRequestProperty("Range", "bytes=" + offset + "-" + end);
        try {
            int status = connection.getResponseCode();
            if (status != 206 && (status != 200 || offset != 0)) return ByteBuffer.allocate(0);
            if (status == 206) {
                String range = connection.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + offset + "-")) return ByteBuffer.allocate(0);
            }
            try (InputStream input = connection.getInputStream()) {
                return readBlock(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static ByteBuffer readBlock(InputStream input, int limit) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count <= 0) break;
            output.write(buffer, 0, count);
        }
        return ByteBuffer.wrap(output.toByteArray());
    }
}
