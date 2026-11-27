package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Shared TV subtitle downloader, ZIP/GZIP extractor, charset normalizer,
 * content format sniffer, and local cache writer for cast and remote subtitles.
 */
public final class CinemaSubtitleCodec {
    public record DecodedSubtitle(String ext, byte[] utf8Bytes, File cachedFile) {}

    private static final int MAX_ARCHIVE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;

    private CinemaSubtitleCodec() {}

    public static DecodedSubtitle downloadUnpackAndCache(Context context, String subUrl) throws Exception {
        if (context == null || subUrl == null || subUrl.isBlank()) {
            throw new IOException("Empty subtitle URL");
        }
        if (com.archos.mediacenter.video.player.CinemaSubtitleText.isStoryboardTrackHint(subUrl)) {
            throw new IOException("Storyboard thumbnail track ignored");
        }
        String current = subUrl.trim();
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "LocalCinema/1.0");
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isBlank()) throw new IOException("Missing redirect location");
                    current = new URL(new URL(current), loc.trim()).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("Subtitle HTTP " + code);
                }
                String contentType = conn.getContentType();
                byte[] raw;
                try (InputStream in = conn.getInputStream()) {
                    raw = readBounded(in, MAX_ARCHIVE_BYTES);
                }
                String hint = current + " " + (contentType == null ? "" : contentType);
                return decodeAndCache(context, raw, hint);
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many subtitle redirects");
    }

    public static DecodedSubtitle decodeAndCache(Context context, byte[] rawInput, String hint) throws Exception {
        if (context == null || rawInput == null || rawInput.length == 0) {
            throw new IOException("Empty subtitle data");
        }
        byte[] payload = rawInput;
        String effectiveHint = hint == null ? "" : hint;

        if (payload.length >= 2 && payload[0] == (byte) 0x1f && payload[1] == (byte) 0x8b) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                payload = readBounded(gz, MAX_SUBTITLE_BYTES);
            }
        }
        if (payload.length >= 4 && payload[0] == 'P' && payload[1] == 'K') {
            var entries = SubtitleStorage.unpack(payload);
            if (entries.isEmpty()) throw new IOException("Empty subtitle archive");
            SubtitleStorage.Entry best = entries.get(0);
            payload = best.bytes();
            effectiveHint = best.name() + " " + effectiveHint;
        }

        byte[] cleanUtf8 = decodeTextUtf8(payload);
        String ext = sniffExtension(cleanUtf8, effectiveHint);
        File cachedFile = writeTempSubtitleFile(context, ext, cleanUtf8);
        return new DecodedSubtitle(ext, cleanUtf8, cachedFile);
    }

    public static String sniffExtension(byte[] utf8Bytes, String hint) {
        String sample = new String(utf8Bytes, 0, Math.min(utf8Bytes.length, 4096), StandardCharsets.UTF_8);
        String trimmed = sample.replace("\uFEFF", "").trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("WEBVTT")) return "vtt";
        if (trimmed.contains("[Script Info]") || trimmed.contains("Dialogue:")) return "ass";
        if (trimmed.contains("-->")) return "srt";
        String lowHint = hint == null ? "" : hint.toLowerCase(Locale.ROOT);
        if (lowHint.contains(".ass") || lowHint.contains(".ssa") || lowHint.contains("text/x-ssa")) return "ass";
        if (lowHint.contains(".vtt") || lowHint.contains("text/vtt")) return "vtt";
        return "srt";
    }

    private static byte[] decodeTextUtf8(byte[] bytes) throws Exception {
        String text;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            text = new String(bytes, StandardCharsets.UTF_16LE);
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            text = new String(bytes, StandardCharsets.UTF_16BE);
        } else {
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, Charset.forName("windows-1256"));
            }
        }
        if (text.indexOf('\0') >= 0) throw new IOException("Binary subtitle unsupported");
        text = text.replace("\uFEFF", "");
        if (!text.contains("-->") && !text.contains("Dialogue:") && !text.contains("[Script Info]")
                && !text.trim().toUpperCase(Locale.ROOT).startsWith("WEBVTT")) {
            throw new IOException("Unrecognized subtitle content");
        }
        if (com.archos.mediacenter.video.player.CinemaSubtitleText.isEntirelyStoryboardVtt(text)) {
            throw new IOException("Storyboard thumbnail VTT ignored");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static File writeTempSubtitleFile(Context context, String ext, byte[] utf8Bytes) throws IOException {
        File dir = new File(context.getCacheDir(), "cast_subs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create subtitle cache folder");
        }
        File[] existing = dir.listFiles();
        if (existing != null && existing.length >= 20) {
            Arrays.sort(existing, java.util.Comparator.comparingLong(File::lastModified));
            for (int i = 0; i <= existing.length - 20; i++) {
                //noinspection ResultOfMethodCallIgnored
                existing[i].delete();
            }
        }
        File out = new File(dir, UUID.randomUUID().toString().replace("-", "") + "." + ext);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(utf8Bytes);
            fos.getFD().sync();
        }
        return out;
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > maxBytes) throw new IOException("Subtitle exceeds max size");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
