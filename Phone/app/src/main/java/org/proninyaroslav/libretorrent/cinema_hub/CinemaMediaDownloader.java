package org.proninyaroslav.libretorrent.cinema_hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.ConfigurationCompat;

import org.proninyaroslav.libretorrent.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Self-contained universal media stream downloader inside {@code cinema_hub/}.
 * Downloads direct progressive streams (MP4/MKV/WEBM/MOV with 302 redirects),
 * split video+audio streams, and adaptive HLS (.m3u8 Master/Media playlists,
 * fMP4 #EXT-X-MAP + .m4s, MPEG-TS .ts & disguised-extension segments, AES-128 encryption),
 * forwarding full session headers (Referer, Origin, User-Agent, Cookie + Set-Cookie jar, Authorization)
 * and muxing into standard playable .mp4 files with live Android notification progress.
 */
final class CinemaMediaDownloader {
    private static final String CHANNEL_ID = "cinema_media_downloads";
    private static final int BASE_NOTIFY_ID = 44000;
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private static final Pattern ATTR_PATTERN = Pattern.compile("([A-Z0-9-]+)=(\"([^\"]*)\"|[^,\\s]+)");

    interface ProgressCallback {
        void onProgress(long downloadedBytes, long totalBytes, long speedBytesPerSec);
    }

    record StreamRequest(
            String id,
            String url,
            String audioUrl,
            String kind,
            int targetHeight,
            String referer,
            String origin,
            String userAgent,
            String cookie,
            String authorization) {}

    static final class SessionHeaders {
        final String rootUrl;
        final String rootHost;
        final String referer;
        final String origin;
        final String userAgent;
        final String initialCookie;
        final String authorization;
        final ConcurrentHashMap<String, String> learnedCookies = new ConcurrentHashMap<>();

        SessionHeaders(StreamRequest req) {
            this.rootUrl = req.url() == null ? "" : req.url().trim();
            this.rootHost = safeHostOf(this.rootUrl);
            this.referer = req.referer() == null ? "" : req.referer().trim();
            String orig = req.origin() == null ? "" : req.origin().trim();
            if (orig.isEmpty() && !this.referer.isEmpty()) {
                orig = deriveOrigin(this.referer);
            }
            this.origin = orig;
            this.userAgent = (req.userAgent() == null || req.userAgent().isBlank()) ? DEFAULT_UA : req.userAgent().trim();
            String ck = req.cookie() == null ? "" : req.cookie().trim();
            if (ck.isEmpty() && !this.rootUrl.isEmpty()) {
                try {
                    String wk = android.webkit.CookieManager.getInstance().getCookie(this.rootUrl);
                    if (wk != null) ck = wk.trim();
                } catch (Throwable ignored) {}
            }
            this.initialCookie = ck;
            this.authorization = req.authorization() == null ? "" : req.authorization().trim();
        }
    }

    private record FetchedManifest(URL finalUrl, String body, HttpURLConnection nonManifestConn) {}

    private record VariantCandidate(String uri, int height, long bandwidth, String audioGroup) {}

    private record EncryptionKey(String method, String uri, byte[] explicitIv) {}

    private record SegmentSpec(
            int index,
            long mediaSeq,
            String uri,
            long byteRangeLength,
            long byteRangeOffset,
            EncryptionKey key) {}

    private record ParsedMediaPlaylist(
            URL baseUrl,
            SegmentSpec initSegment,
            List<SegmentSpec> segments,
            boolean hasEndList) {}

    private CinemaMediaDownloader() {}

    static boolean isHlsRequest(StreamRequest req) {
        if (req == null) return false;
        if ("hls".equalsIgnoreCase(req.kind())) return true;
        String u = req.url() == null ? "" : req.url();
        int q = u.indexOf('?');
        String path = (q >= 0 ? u.substring(0, q) : u).toLowerCase(Locale.ROOT);
        return path.endsWith(".m3u8");
    }

    static boolean isSplitRequest(StreamRequest req) {
        return req != null && req.audioUrl() != null && !req.audioUrl().isBlank()
                && ("split".equalsIgnoreCase(req.kind()) || !isHlsRequest(req));
    }

    static void downloadToTarget(Context app,
                                 StreamRequest req,
                                 File partFile,
                                 AtomicBoolean cancelFlag,
                                 Map<String, HttpURLConnection> activeConns,
                                 ProgressCallback progressCb) throws Exception {
        SessionHeaders headers = new SessionHeaders(req);
        if (isHlsRequest(req)) {
            downloadHlsStream(app, req, headers, partFile, cancelFlag, activeConns, progressCb);
        } else if (isSplitRequest(req)) {
            downloadAndMuxSplitStream(app, req, headers, partFile, cancelFlag, activeConns, progressCb);
        } else {
            try {
                downloadProgressiveWithRedirects(req.id(), req.url(), headers, partFile, true, cancelFlag, activeConns, progressCb);
            } catch (RedirectedToHlsException hlsRedirect) {
                StreamRequest redirectedReq = new StreamRequest(
                        req.id(),
                        hlsRedirect.finalUrl,
                        req.audioUrl(),
                        "hls",
                        req.targetHeight(),
                        req.referer(),
                        req.origin(),
                        req.userAgent(),
                        req.cookie(),
                        req.authorization());
                downloadHlsStream(app, redirectedReq, headers, partFile, cancelFlag, activeConns, progressCb);
            }
        }
    }

    // ---- Progressive HTTP Downloader with 8-hop Redirects & Header/Cookie Forwarding ----

    static void downloadProgressiveWithRedirects(String id,
                                                 String startUrl,
                                                 SessionHeaders headers,
                                                 File targetFile,
                                                 boolean allowResume,
                                                 AtomicBoolean cancelFlag,
                                                 Map<String, HttpURLConnection> activeConns,
                                                 ProgressCallback progressCb) throws Exception {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        long existing = (allowResume && targetFile.isFile()) ? targetFile.length() : 0L;
        String rangeHeader = existing > 0 ? ("bytes=" + existing + "-") : null;
        HttpURLConnection conn = openConnectionWithRedirects(startUrl, "GET", rangeHeader, headers);
        if (activeConns != null && id != null) activeConns.put(id, conn);
        try {
            int code = conn.getResponseCode();
            if (code == 416 && existing > 0) {
                conn.disconnect();
                existing = 0L;
                conn = openConnectionWithRedirects(startUrl, "GET", null, headers);
                if (activeConns != null && id != null) activeConns.put(id, conn);
                code = conn.getResponseCode();
            }
            String ct = conn.getContentType();
            String finalUrlStr = conn.getURL() != null ? conn.getURL().toString() : startUrl;
            if (code >= 200 && code < 300 && existing == 0L
                    && ((ct != null && ct.toLowerCase(Locale.ROOT).contains("mpegurl"))
                        || pathOnlyLower(finalUrlStr).endsWith(".m3u8"))) {
                byte[] manifestBytes = readBoundedBytes(conn.getInputStream(), 4 * 1024 * 1024);
                conn.disconnect();
                String body = stripBom(new String(manifestBytes, StandardCharsets.UTF_8));
                if (body.trim().startsWith("#EXTM3U")) {
                    throw new RedirectedToHlsException(finalUrlStr, body);
                }
            }
            boolean append = (code == 206 && existing > 0);
            if (!append) existing = 0L;
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long contentLen = conn.getContentLengthLong();
            long totalBytes = contentLen > 0 ? (append ? existing + contentLen : contentLen) : -1L;
            long downloaded = existing;
            if (progressCb != null) progressCb.onProgress(downloaded, totalBytes, 0L);

            try (InputStream in = conn.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
                if (append) raf.seek(existing);
                else raf.setLength(0L);
                byte[] buf = new byte[65536];
                int read;
                long lastTick = System.currentTimeMillis();
                long tickBytes = 0L;
                while ((read = in.read(buf)) != -1) {
                    if (cancelFlag.get()) return;
                    raf.write(buf, 0, read);
                    downloaded += read;
                    tickBytes += read;
                    long now = System.currentTimeMillis();
                    if (now - lastTick >= 450) {
                        long speed = (tickBytes * 1000L) / Math.max(1L, now - lastTick);
                        lastTick = now;
                        tickBytes = 0L;
                        if (progressCb != null) progressCb.onProgress(downloaded, totalBytes, speed);
                    }
                }
            }
            if (!cancelFlag.get() && progressCb != null) {
                progressCb.onProgress(downloaded, downloaded > 0 ? downloaded : totalBytes, 0L);
            }
        } finally {
            if (activeConns != null && id != null) activeConns.remove(id);
            conn.disconnect();
        }
    }

    private static final class RedirectedToHlsException extends Exception {
        final String finalUrl;
        final String manifestBody;

        RedirectedToHlsException(String finalUrl, String manifestBody) {
            this.finalUrl = finalUrl;
            this.manifestBody = manifestBody;
        }
    }

    // ---- Split Video + Audio Downloader & Lossless MP4 Muxer ----

    private static void downloadAndMuxSplitStream(Context app,
                                                  StreamRequest req,
                                                  SessionHeaders headers,
                                                  File outMp4File,
                                                  AtomicBoolean cancelFlag,
                                                  Map<String, HttpURLConnection> activeConns,
                                                  ProgressCallback progressCb) throws Exception {
        File stageDir = new File(app.getCacheDir(), "stream_mux_stage");
        if (!stageDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            stageDir.mkdirs();
        File tmpVid = new File(stageDir, "split_v_" + req.id() + ".tmp");
        File tmpAud = new File(stageDir, "split_a_" + req.id() + ".tmp");
        try {
            final long[] vTotal = {-1L};
            final long[] vDone = {0L};
            downloadProgressiveWithRedirects(req.id(), req.url(), headers, tmpVid, true, cancelFlag, activeConns,
                    (dl, tot, spd) -> {
                        vDone[0] = dl;
                        vTotal[0] = tot;
                        long estTot = tot > 0 ? (long) (tot * 1.15) : -1L;
                        if (progressCb != null) progressCb.onProgress(dl, estTot, spd);
                    });
            if (cancelFlag.get()) return;

            downloadProgressiveWithRedirects(req.id(), req.audioUrl(), headers, tmpAud, true, cancelFlag, activeConns,
                    (dl, tot, spd) -> {
                        long combinedDl = vDone[0] + dl;
                        long combinedTot = (vTotal[0] > 0 && tot > 0) ? (vTotal[0] + tot) : -1L;
                        if (progressCb != null) progressCb.onProgress(combinedDl, combinedTot, spd);
                    });
            if (cancelFlag.get()) return;

            muxSeparateVideoAndAudioToMp4(tmpVid, tmpAud, outMp4File, cancelFlag);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmpVid.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpAud.delete();
        }
    }

    // ---- Adaptive HLS (.m3u8) Downloader + AES-128 Decryptor + Standard MP4 Remuxer ----

    private static void downloadHlsStream(Context app,
                                          StreamRequest req,
                                          SessionHeaders headers,
                                          File outMp4File,
                                          AtomicBoolean cancelFlag,
                                          Map<String, HttpURLConnection> activeConns,
                                          ProgressCallback progressCb) throws Exception {
        FetchedManifest rootFetch = fetchManifestOrDirect(req.url(), headers);
        if (rootFetch.nonManifestConn() != null) {
            try {
                rootFetch.nonManifestConn().disconnect();
            } catch (Exception ignored) {}
            downloadProgressiveWithRedirects(req.id(), req.url(), headers, outMp4File, true, cancelFlag, activeConns, progressCb);
            return;
        }

        URL playlistUrl = rootFetch.finalUrl();
        String playlistBody = rootFetch.body();
        String externalAudioPlaylistUrl = null;

        if (playlistBody.contains("#EXT-X-STREAM-INF:")) {
            Map<String, String> audioGroupUris = parseHlsAudioGroups(playlistBody, playlistUrl);
            VariantCandidate chosen = selectBestVariant(playlistBody, playlistUrl, req.targetHeight());
            if (chosen == null || chosen.uri().isBlank()) {
                throw new IOException("No playable HLS variant found in master playlist");
            }
            if (chosen.audioGroup() != null && audioGroupUris.containsKey(chosen.audioGroup())) {
                externalAudioPlaylistUrl = audioGroupUris.get(chosen.audioGroup());
            }
            FetchedManifest mediaFetch = fetchManifestOrDirect(chosen.uri(), headers);
            if (mediaFetch.nonManifestConn() != null) {
                mediaFetch.nonManifestConn().disconnect();
                downloadProgressiveWithRedirects(req.id(), chosen.uri(), headers, outMp4File, true, cancelFlag, activeConns, progressCb);
                return;
            }
            playlistUrl = mediaFetch.finalUrl();
            playlistBody = mediaFetch.body();
        }

        ParsedMediaPlaylist videoMedia = parseMediaPlaylist(playlistBody, playlistUrl);
        if (videoMedia.segments().isEmpty()) {
            throw new IOException("HLS playlist contains no media segments");
        }
        if (!videoMedia.hasEndList() && videoMedia.segments().size() <= 10) {
            throw new IOException(isArabic(app)
                    ? "البث المباشر المستمر لا يدعم التحميل كملف فيديو"
                    : "Continuous live stream cannot be downloaded as a VOD file");
        }

        ParsedMediaPlaylist audioMedia = null;
        if (externalAudioPlaylistUrl != null && !externalAudioPlaylistUrl.isBlank()) {
            try {
                FetchedManifest audFetch = fetchManifestOrDirect(externalAudioPlaylistUrl, headers);
                if (audFetch.nonManifestConn() != null) {
                    audFetch.nonManifestConn().disconnect();
                } else {
                    ParsedMediaPlaylist parsedAud = parseMediaPlaylist(audFetch.body(), audFetch.finalUrl());
                    if (!parsedAud.segments().isEmpty()) {
                        audioMedia = parsedAud;
                    }
                }
            } catch (Exception ignored) {}
        }

        File stageDir = new File(app.getCacheDir(), "hls_mux_stage");
        if (!stageDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            stageDir.mkdirs();

        boolean isFmp4 = videoMedia.initSegment() != null;
        File rawVideoFile = new File(stageDir, "hls_v_" + req.id() + (isFmp4 ? ".fmp4" : ".ts"));
        File rawAudioFile = audioMedia != null
                ? new File(stageDir, "hls_a_" + req.id() + (audioMedia.initSegment() != null ? ".fmp4" : ".ts"))
                : null;

        int totalSegCount = videoMedia.segments().size() + (audioMedia != null ? audioMedia.segments().size() : 0);
        final long[] cumulativeBytes = {0L};
        final int[] completedSegs = {0};

        try {
            downloadPlaylistSegmentsToFile(
                    videoMedia, headers, rawVideoFile, isFmp4, cancelFlag,
                    totalSegCount, cumulativeBytes, completedSegs, progressCb);
            if (cancelFlag.get()) return;

            if (audioMedia != null && rawAudioFile != null) {
                downloadPlaylistSegmentsToFile(
                        audioMedia, headers, rawAudioFile, audioMedia.initSegment() != null, cancelFlag,
                        totalSegCount, cumulativeBytes, completedSegs, progressCb);
                if (cancelFlag.get()) return;
            }

            if (outMp4File.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outMp4File.delete();
            }

            boolean muxed = false;
            if (rawAudioFile != null && rawAudioFile.isFile() && rawAudioFile.length() > 0) {
                muxed = tryMuxWithMediaExtractor(rawVideoFile, rawAudioFile, outMp4File, cancelFlag);
            } else {
                muxed = tryMuxWithMediaExtractor(rawVideoFile, null, outMp4File, cancelFlag);
                if (!muxed && !isFmp4) {
                    muxed = tryRemuxMpegTsPureJava(rawVideoFile, outMp4File, cancelFlag);
                }
            }

            if (cancelFlag.get()) return;

            if (!muxed || !outMp4File.isFile() || outMp4File.length() < 1024L) {
                if (isFmp4 && rawVideoFile.isFile() && rawVideoFile.length() > 1024L) {
                    copyFile(rawVideoFile, outMp4File);
                } else if (rawVideoFile.isFile() && rawVideoFile.length() > 1024L) {
                    // Last-resort direct stream copy so user never loses a downloaded stream
                    copyFile(rawVideoFile, outMp4File);
                } else {
                    throw new IOException("Failed to assemble MP4 output");
                }
            }

            long finalLen = outMp4File.length();
            if (progressCb != null) {
                progressCb.onProgress(finalLen, finalLen, 0L);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            rawVideoFile.delete();
            if (rawAudioFile != null) {
                //noinspection ResultOfMethodCallIgnored
                rawAudioFile.delete();
            }
        }
    }

    private static void downloadPlaylistSegmentsToFile(ParsedMediaPlaylist playlist,
                                                       SessionHeaders headers,
                                                       File outFile,
                                                       boolean isFmp4,
                                                       AtomicBoolean cancelFlag,
                                                       int globalTotalSegments,
                                                       long[] globalDownloadedBytes,
                                                       int[] globalCompletedSegments,
                                                       ProgressCallback progressCb) throws Exception {
        Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
        ExecutorService segPool = Executors.newFixedThreadPool(4);
        try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
            if (playlist.initSegment() != null) {
                byte[] initBytes = fetchSegmentWithRetry(playlist.initSegment(), headers, keyCache, cancelFlag);
                if (cancelFlag.get()) return;
                fos.write(initBytes);
                globalDownloadedBytes[0] += initBytes.length;
            }

            List<SegmentSpec> segs = playlist.segments();
            int n = segs.size();
            int window = 4;
            long windowStartMs = System.currentTimeMillis();
            long bytesInWindow = 0L;

            for (int base = 0; base < n; base += window) {
                if (cancelFlag.get()) return;
                int end = Math.min(n, base + window);
                List<Future<byte[]>> futures = new ArrayList<>(end - base);
                for (int i = base; i < end; i++) {
                    final SegmentSpec spec = segs.get(i);
                    Callable<byte[]> task = () -> fetchSegmentWithRetry(spec, headers, keyCache, cancelFlag);
                    futures.add(segPool.submit(task));
                }
                for (Future<byte[]> f : futures) {
                    if (cancelFlag.get()) return;
                    byte[] rawSeg = f.get();
                    if (rawSeg == null || rawSeg.length == 0) continue;
                    int offset = (!isFmp4) ? findMpegTsSyncOffset(rawSeg) : 0;
                    int writeLen = rawSeg.length - offset;
                    if (writeLen > 0) {
                        fos.write(rawSeg, offset, writeLen);
                        globalDownloadedBytes[0] += writeLen;
                        bytesInWindow += writeLen;
                    }
                    globalCompletedSegments[0]++;

                    long now = System.currentTimeMillis();
                    long elapsed = Math.max(1L, now - windowStartMs);
                    long speed = elapsed >= 300 ? (bytesInWindow * 1000L) / elapsed : 0L;
                    if (elapsed >= 800) {
                        windowStartMs = now;
                        bytesInWindow = 0L;
                    }
                    int done = Math.max(1, globalCompletedSegments[0]);
                    // Reserve final 3% for fast MP4 remux pass
                    long estimatedTotal = Math.max(
                            globalDownloadedBytes[0] + 1024L,
                            (globalDownloadedBytes[0] * Math.max(done, globalTotalSegments) * 100L) / (done * 97L));
                    if (progressCb != null) {
                        progressCb.onProgress(globalDownloadedBytes[0], estimatedTotal, speed);
                    }
                }
            }
            fos.flush();
        } finally {
            segPool.shutdownNow();
        }
    }

    private static byte[] fetchSegmentWithRetry(SegmentSpec spec,
                                                SessionHeaders headers,
                                                Map<String, byte[]> keyCache,
                                                AtomicBoolean cancelFlag) throws Exception {
        Exception lastErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (cancelFlag.get()) return new byte[0];
            HttpURLConnection conn = null;
            try {
                String range = spec.byteRangeLength() > 0
                        ? ("bytes=" + spec.byteRangeOffset() + "-" + (spec.byteRangeOffset() + spec.byteRangeLength() - 1))
                        : null;
                conn = openConnectionWithRedirects(spec.uri(), "GET", range, headers);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("Segment HTTP " + code);
                }
                byte[] data = readBoundedBytes(conn.getInputStream(), 64 * 1024 * 1024);
                conn.disconnect();
                conn = null;
                if (spec.key() != null && "AES-128".equalsIgnoreCase(spec.key().method())) {
                    data = decryptAes128Segment(data, spec, headers, keyCache);
                }
                return data;
            } catch (Exception e) {
                lastErr = e;
                if (conn != null) conn.disconnect();
                if (attempt < 2 && !cancelFlag.get()) {
                    try { Thread.sleep(350L * (attempt + 1)); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastErr != null ? lastErr : new IOException("Segment download failed");
    }

    private static byte[] decryptAes128Segment(byte[] encrypted,
                                               SegmentSpec spec,
                                               SessionHeaders headers,
                                               Map<String, byte[]> keyCache) throws Exception {
        EncryptionKey ek = spec.key();
        if (ek == null || ek.uri() == null || ek.uri().isEmpty()) return encrypted;
        byte[] keyBytes = keyCache.get(ek.uri());
        if (keyBytes == null) {
            HttpURLConnection kc = openConnectionWithRedirects(ek.uri(), "GET", null, headers);
            try {
                int code = kc.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("HLS key HTTP " + code);
                keyBytes = readBoundedBytes(kc.getInputStream(), 4096);
            } finally {
                kc.disconnect();
            }
            if (keyBytes.length != 16) {
                throw new IOException("Invalid AES-128 key length: " + keyBytes.length);
            }
            keyCache.put(ek.uri(), keyBytes);
        }
        byte[] iv = new byte[16];
        if (ek.explicitIv() != null && ek.explicitIv().length == 16) {
            System.arraycopy(ek.explicitIv(), 0, iv, 0, 16);
        } else {
            long seq = spec.mediaSeq();
            for (int i = 15; i >= 8; i--) {
                iv[i] = (byte) (seq & 0xFF);
                seq >>>= 8;
            }
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
    }

    /**
     * Strips any fake image/ID3/CDN disguise prefix before the first aligned 188-byte MPEG-TS packet (0x47).
     */
    private static int findMpegTsSyncOffset(byte[] data) {
        if (data == null || data.length < 188) return 0;
        if (data[0] == 0x47 && (data.length < 376 || data[188] == 0x47)) return 0;
        // Check if segment is actually fMP4 ('ftyp', 'moof', 'styp', 'sidx') despite missing EXT-X-MAP
        if (data.length >= 8) {
            String box = new String(data, 4, 4, StandardCharsets.US_ASCII);
            if ("ftyp".equals(box) || "moof".equals(box) || "styp".equals(box) || "sidx".equals(box)) {
                return 0;
            }
        }
        int maxScan = Math.min(data.length - 188, 8192);
        for (int i = 0; i < maxScan; i++) {
            if (data[i] == 0x47 && data[i + 188] == 0x47) {
                if (i + 376 >= data.length || data[i + 376] == 0x47) {
                    return i;
                }
            }
        }
        return 0;
    }

    // ---- Lossless Remuxer Stage 1: Android MediaExtractor -> MediaMuxer ----

    private static void muxSeparateVideoAndAudioToMp4(File videoFile,
                                                      File audioFile,
                                                      File outMp4File,
                                                      AtomicBoolean cancelFlag) throws Exception {
        if (!tryMuxWithMediaExtractor(videoFile, audioFile, outMp4File, cancelFlag)) {
            throw new IOException("Could not mux video and audio tracks into MP4");
        }
    }

    private static boolean tryMuxWithMediaExtractor(File primaryFile,
                                                    File externalAudioFile,
                                                    File outMp4File,
                                                    AtomicBoolean cancelFlag) {
        MediaExtractor vEx = new MediaExtractor();
        MediaExtractor aEx = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            vEx.setDataSource(primaryFile.getAbsolutePath());
            int vTrackIn = -1;
            int internalATrackIn = -1;
            MediaFormat vFmt = null;
            MediaFormat aFmt = null;

            for (int i = 0; i < vEx.getTrackCount(); i++) {
                MediaFormat fmt = vEx.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (vTrackIn < 0 && mime.startsWith("video/") && isMp4CompatibleMime(mime)) {
                    vTrackIn = i;
                    vFmt = fmt;
                } else if (internalATrackIn < 0 && externalAudioFile == null
                        && mime.startsWith("audio/") && isMp4CompatibleMime(mime)) {
                    internalATrackIn = i;
                    aFmt = fmt;
                }
            }
            if (vTrackIn < 0 && internalATrackIn < 0) {
                return false;
            }

            int extATrackIn = -1;
            if (externalAudioFile != null && externalAudioFile.isFile()) {
                aEx.setDataSource(externalAudioFile.getAbsolutePath());
                for (int i = 0; i < aEx.getTrackCount(); i++) {
                    MediaFormat fmt = aEx.getTrackFormat(i);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/") && isMp4CompatibleMime(mime)) {
                        extATrackIn = i;
                        aFmt = fmt;
                        break;
                    }
                }
            }

            muxer = new MediaMuxer(outMp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vTrackOut = vFmt != null ? muxer.addTrack(vFmt) : -1;
            int aTrackOut = aFmt != null ? muxer.addTrack(aFmt) : -1;
            muxer.start();
            muxerStarted = true;

            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long writtenSamples = 0L;

            if (vTrackIn >= 0) vEx.selectTrack(vTrackIn);
            if (internalATrackIn >= 0) vEx.selectTrack(internalATrackIn);

            long basePtsUs = -1L;
            long lastVideoPtsUs = -1L;
            long lastAudioPtsUs = -1L;

            while (!cancelFlag.get()) {
                int trackIdx = vEx.getSampleTrackIndex();
                if (trackIdx < 0) break;
                buf.clear();
                int sampleSize = vEx.readSampleData(buf, 0);
                if (sampleSize < 0) break;
                long rawPts = Math.max(0L, vEx.getSampleTime());
                if (basePtsUs < 0L) basePtsUs = rawPts;
                long normPts = Math.max(0L, rawPts - basePtsUs);

                int outTrack = (trackIdx == vTrackIn) ? vTrackOut : (trackIdx == internalATrackIn ? aTrackOut : -1);
                if (outTrack >= 0) {
                    if (outTrack == vTrackOut) {
                        if (normPts <= lastVideoPtsUs) normPts = lastVideoPtsUs + 1000L;
                        lastVideoPtsUs = normPts;
                    } else {
                        if (normPts <= lastAudioPtsUs) normPts = lastAudioPtsUs + 1000L;
                        lastAudioPtsUs = normPts;
                    }
                    info.offset = 0;
                    info.size = sampleSize;
                    info.presentationTimeUs = normPts;
                    info.flags = mapExtractorFlags(vEx.getSampleFlags());
                    muxer.writeSampleData(outTrack, buf, info);
                    writtenSamples++;
                }
                vEx.advance();
            }

            if (extATrackIn >= 0 && aTrackOut >= 0 && !cancelFlag.get()) {
                aEx.selectTrack(extATrackIn);
                long aBasePts = -1L;
                long lastExtAPts = -1L;
                while (!cancelFlag.get()) {
                    buf.clear();
                    int sampleSize = aEx.readSampleData(buf, 0);
                    if (sampleSize < 0) break;
                    long rawPts = Math.max(0L, aEx.getSampleTime());
                    if (aBasePts < 0L) aBasePts = rawPts;
                    long normPts = Math.max(0L, rawPts - aBasePts);
                    if (normPts <= lastExtAPts) normPts = lastExtAPts + 1000L;
                    lastExtAPts = normPts;
                    info.offset = 0;
                    info.size = sampleSize;
                    info.presentationTimeUs = normPts;
                    info.flags = mapExtractorFlags(aEx.getSampleFlags());
                    muxer.writeSampleData(aTrackOut, buf, info);
                    writtenSamples++;
                    aEx.advance();
                }
            }

            muxer.stop();
            muxerStarted = false;
            return !cancelFlag.get() && writtenSamples > 5 && outMp4File.isFile() && outMp4File.length() > 1024L;
        } catch (Throwable t) {
            return false;
        } finally {
            try { vEx.release(); } catch (Throwable ignored) {}
            try { aEx.release(); } catch (Throwable ignored) {}
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Throwable ignored) {}
                }
                try { muxer.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isMp4CompatibleMime(String mime) {
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "video/avc", "video/hevc", "video/mp4v-es", "video/av01",
                 "audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/opus", "audio/mpeg" -> true;
            default -> false;
        };
    }

    private static int mapExtractorFlags(int sampleFlags) {
        int out = 0;
        if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            out |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        return out;
    }

    // ---- Lossless Remuxer Stage 2: Pure-Java MPEG-TS Demuxer (H.264/HEVC + ADTS AAC) -> MediaMuxer ----

    private static final int[] ADTS_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private record TsSample(boolean isVideo, byte[] data, long ptsUs, int flags) {}

    private static boolean tryRemuxMpegTsPureJava(File tsFile, File outMp4File, AtomicBoolean cancelFlag) {
        MediaMuxer muxer = null;
        boolean started = false;
        try (FileInputStream fis = new FileInputStream(tsFile)) {
            int pmtPid = -1;
            int videoPid = -1;
            int videoStreamType = 0; // 0x1B = AVC, 0x24 = HEVC
            int audioPid = -1;

            ByteArrayOutputStream vPes = new ByteArrayOutputStream(262144);
            long vPesPts = -1L;
            ByteArrayOutputStream aPes = new ByteArrayOutputStream(32768);
            long aPesPts = -1L;

            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            int vTrackOut = -1;
            int aTrackOut = -1;
            List<TsSample> preStartQueue = new ArrayList<>();
            long basePtsUs = -1L;
            long lastVPts = -1L;
            long lastAPts = -1L;
            long written = 0L;

            byte[] pkt = new byte[188];
            while (!cancelFlag.get() && readFullyOrEof(fis, pkt) == 188) {
                if (pkt[0] != 0x47) continue;
                boolean payloadStart = (pkt[1] & 0x40) != 0;
                int pid = ((pkt[1] & 0x1F) << 8) | (pkt[2] & 0xFF);
                int afc = (pkt[3] >> 4) & 0x03;
                if ((afc & 0x01) == 0) continue; // no payload
                int pos = 4;
                if ((afc & 0x02) != 0) {
                    int afLen = pkt[4] & 0xFF;
                    pos += 1 + afLen;
                }
                if (pos >= 188) continue;

                if (pid == 0) {
                    int ptr = payloadStart ? (pkt[pos] & 0xFF) : 0;
                    int secPos = payloadStart ? pos + 1 + ptr : pos;
                    if (secPos + 12 <= 188 && (pkt[secPos] & 0xFF) == 0x00) {
                        int secLen = (((pkt[secPos + 1] & 0x0F) << 8) | (pkt[secPos + 2] & 0xFF));
                        int progStart = secPos + 8;
                        int progEnd = Math.min(188, secPos + 3 + secLen - 4);
                        for (int p = progStart; p + 4 <= progEnd; p += 4) {
                            int progNum = ((pkt[p] & 0xFF) << 8) | (pkt[p + 1] & 0xFF);
                            if (progNum != 0) {
                                pmtPid = ((pkt[p + 2] & 0x1F) << 8) | (pkt[p + 3] & 0xFF);
                                break;
                            }
                        }
                    }
                } else if (pid == pmtPid && pmtPid > 0) {
                    int ptr = payloadStart ? (pkt[pos] & 0xFF) : 0;
                    int secPos = payloadStart ? pos + 1 + ptr : pos;
                    if (secPos + 12 <= 188 && (pkt[secPos] & 0xFF) == 0x02) {
                        int secLen = (((pkt[secPos + 1] & 0x0F) << 8) | (pkt[secPos + 2] & 0xFF));
                        int progInfoLen = (((pkt[secPos + 10] & 0x0F) << 8) | (pkt[secPos + 11] & 0xFF));
                        int esPos = secPos + 12 + progInfoLen;
                        int esEnd = Math.min(188, secPos + 3 + secLen - 4);
                        while (esPos + 5 <= esEnd) {
                            int st = pkt[esPos] & 0xFF;
                            int elemPid = ((pkt[esPos + 1] & 0x1F) << 8) | (pkt[esPos + 2] & 0xFF);
                            int esInfoLen = ((pkt[esPos + 3] & 0x0F) << 8) | (pkt[esPos + 4] & 0xFF);
                            if ((st == 0x1B || st == 0x24) && videoPid < 0) {
                                videoPid = elemPid;
                                videoStreamType = st;
                            } else if (st == 0x0F && audioPid < 0) {
                                audioPid = elemPid;
                            }
                            esPos += 5 + esInfoLen;
                        }
                    }
                } else if (pid == videoPid && videoPid > 0) {
                    if (payloadStart) {
                        if (vPes.size() > 0 && vPesPts >= 0L) {
                            byte[] es = vPes.toByteArray();
                            if (videoFormat == null) {
                                videoFormat = buildVideoFormatFromAnnexB(es, videoStreamType);
                            }
                            int flags = isKeyFrameAnnexB(es, videoStreamType) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            long ptsUs = vPesPts * 1000L / 90L;
                            if (basePtsUs < 0L) basePtsUs = ptsUs;
                            ptsUs = Math.max(0L, ptsUs - basePtsUs);
                            if (ptsUs <= lastVPts) ptsUs = lastVPts + 1000L;
                            lastVPts = ptsUs;
                            TsSample sample = new TsSample(true, es, ptsUs, flags);
                            if (started) {
                                writeTsSample(muxer, vTrackOut, sample);
                                written++;
                            } else {
                                preStartQueue.add(sample);
                            }
                        }
                        vPes.reset();
                        int hdrSkip = parsePesHeaderSkip(pkt, pos);
                        if (hdrSkip > 0 && pos + hdrSkip <= 188) {
                            long parsedPts = parsePesPts90k(pkt, pos);
                            if (parsedPts >= 0L) vPesPts = parsedPts;
                            vPes.write(pkt, pos + hdrSkip, 188 - (pos + hdrSkip));
                        }
                    } else if (vPesPts >= 0L) {
                        vPes.write(pkt, pos, 188 - pos);
                    }
                } else if (pid == audioPid && audioPid > 0) {
                    if (payloadStart) {
                        if (aPes.size() > 0 && aPesPts >= 0L) {
                            byte[] es = aPes.toByteArray();
                            List<TsSample> aacSamples = extractAdtsFrames(es, aPesPts, basePtsUs, lastAPts);
                            if (audioFormat == null && !aacSamples.isEmpty()) {
                                audioFormat = buildAdtsAudioFormat(es);
                            }
                            for (TsSample s : aacSamples) {
                                if (basePtsUs < 0L) basePtsUs = s.ptsUs();
                                lastAPts = Math.max(lastAPts, s.ptsUs());
                                if (started && aTrackOut >= 0) {
                                    writeTsSample(muxer, aTrackOut, s);
                                    written++;
                                } else {
                                    preStartQueue.add(s);
                                }
                            }
                        }
                        aPes.reset();
                        int hdrSkip = parsePesHeaderSkip(pkt, pos);
                        if (hdrSkip > 0 && pos + hdrSkip <= 188) {
                            long parsedPts = parsePesPts90k(pkt, pos);
                            if (parsedPts >= 0L) aPesPts = parsedPts;
                            aPes.write(pkt, pos + hdrSkip, 188 - (pos + hdrSkip));
                        }
                    } else if (aPesPts >= 0L) {
                        aPes.write(pkt, pos, 188 - pos);
                    }
                }

                if (!started && videoFormat != null && (audioPid < 0 || audioFormat != null || preStartQueue.size() > 90)) {
                    muxer = new MediaMuxer(outMp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    vTrackOut = muxer.addTrack(videoFormat);
                    if (audioFormat != null) aTrackOut = muxer.addTrack(audioFormat);
                    muxer.start();
                    started = true;
                    for (TsSample queued : preStartQueue) {
                        if (queued.isVideo() && vTrackOut >= 0) {
                            writeTsSample(muxer, vTrackOut, queued);
                            written++;
                        } else if (!queued.isVideo() && aTrackOut >= 0) {
                            writeTsSample(muxer, aTrackOut, queued);
                            written++;
                        }
                    }
                    preStartQueue.clear();
                }
            }

            if (!started && videoFormat != null && !preStartQueue.isEmpty()) {
                muxer = new MediaMuxer(outMp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                vTrackOut = muxer.addTrack(videoFormat);
                if (audioFormat != null) aTrackOut = muxer.addTrack(audioFormat);
                muxer.start();
                started = true;
                for (TsSample queued : preStartQueue) {
                    if (queued.isVideo() && vTrackOut >= 0) {
                        writeTsSample(muxer, vTrackOut, queued);
                        written++;
                    } else if (!queued.isVideo() && aTrackOut >= 0) {
                        writeTsSample(muxer, aTrackOut, queued);
                        written++;
                    }
                }
            }
            if (started) {
                muxer.stop();
                started = false;
            }
            return !cancelFlag.get() && written > 5 && outMp4File.isFile() && outMp4File.length() > 1024L;
        } catch (Throwable t) {
            return false;
        } finally {
            if (muxer != null) {
                if (started) {
                    try { muxer.stop(); } catch (Throwable ignored) {}
                }
                try { muxer.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void writeTsSample(MediaMuxer muxer, int trackIdx, TsSample s) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.offset = 0;
        info.size = s.data().length;
        info.presentationTimeUs = s.ptsUs();
        info.flags = s.flags();
        muxer.writeSampleData(trackIdx, ByteBuffer.wrap(s.data()), info);
    }

    private static int readFullyOrEof(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) return off;
            off += r;
        }
        return off;
    }

    private static int parsePesHeaderSkip(byte[] pkt, int pos) {
        if (pos + 9 > 188) return -1;
        if (pkt[pos] != 0x00 || pkt[pos + 1] != 0x00 || pkt[pos + 2] != 0x01) return -1;
        int hdrDataLen = pkt[pos + 8] & 0xFF;
        return 9 + hdrDataLen;
    }

    private static long parsePesPts90k(byte[] pkt, int pos) {
        if (pos + 14 > 188) return -1L;
        int ptsDtsFlags = (pkt[pos + 7] >> 6) & 0x03;
        if ((ptsDtsFlags & 0x02) == 0) return -1L;
        long b0 = (pkt[pos + 9] >> 1) & 0x07L;
        long b1 = pkt[pos + 10] & 0xFFL;
        long b2 = (pkt[pos + 11] >> 1) & 0x7FL;
        long b3 = pkt[pos + 12] & 0xFFL;
        long b4 = (pkt[pos + 13] >> 1) & 0x7FL;
        return (b0 << 30) | (b1 << 22) | (b2 << 15) | (b3 << 7) | b4;
    }

    private static List<byte[]> splitAnnexBNals(byte[] es) {
        List<byte[]> nals = new ArrayList<>();
        int i = 0;
        int len = es.length;
        int nalStart = -1;
        while (i + 3 < len) {
            int scLen = 0;
            if (es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 1) scLen = 3;
            else if (i + 4 < len && es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 0 && es[i + 3] == 1) scLen = 4;
            if (scLen > 0) {
                if (nalStart >= 0 && i > nalStart) {
                    byte[] nal = new byte[4 + (i - nalStart)];
                    nal[0] = 0; nal[1] = 0; nal[2] = 0; nal[3] = 1;
                    System.arraycopy(es, nalStart, nal, 4, i - nalStart);
                    nals.add(nal);
                }
                nalStart = i + scLen;
                i += scLen;
            } else {
                i++;
            }
        }
        if (nalStart >= 0 && len > nalStart) {
            byte[] nal = new byte[4 + (len - nalStart)];
            nal[0] = 0; nal[1] = 0; nal[2] = 0; nal[3] = 1;
            System.arraycopy(es, nalStart, nal, 4, len - nalStart);
            nals.add(nal);
        }
        return nals;
    }

    private static MediaFormat buildVideoFormatFromAnnexB(byte[] es, int streamType) {
        List<byte[]> nals = splitAnnexBNals(es);
        if (streamType == 0x1B) { // H.264 AVC
            byte[] sps = null;
            byte[] pps = null;
            for (byte[] nal : nals) {
                if (nal.length <= 4) continue;
                int type = nal[4] & 0x1F;
                if (type == 7 && sps == null) sps = nal;
                else if (type == 8 && pps == null) pps = nal;
            }
            if (sps != null && pps != null) {
                MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
                return fmt;
            }
        } else if (streamType == 0x24) { // H.265 HEVC
            ByteArrayOutputStream csd = new ByteArrayOutputStream();
            boolean hasVps = false, hasSps = false, hasPps = false;
            for (byte[] nal : nals) {
                if (nal.length <= 5) continue;
                int type = (nal[4] >> 1) & 0x3F;
                if (type == 32 || type == 33 || type == 34) {
                    csd.write(nal, 0, nal.length);
                    if (type == 32) hasVps = true;
                    if (type == 33) hasSps = true;
                    if (type == 34) hasPps = true;
                }
            }
            if (hasVps && hasSps && hasPps) {
                MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
                return fmt;
            }
        }
        return null;
    }

    private static boolean isKeyFrameAnnexB(byte[] es, int streamType) {
        for (byte[] nal : splitAnnexBNals(es)) {
            if (nal.length <= 4) continue;
            if (streamType == 0x1B) {
                int type = nal[4] & 0x1F;
                if (type == 5) return true;
            } else if (streamType == 0x24) {
                int type = (nal[4] >> 1) & 0x3F;
                if (type >= 16 && type <= 21) return true;
            }
        }
        return false;
    }

    private static MediaFormat buildAdtsAudioFormat(byte[] es) {
        for (int i = 0; i + 7 <= es.length; i++) {
            if ((es[i] & 0xFF) == 0xFF && (es[i + 1] & 0xF6) == 0xF0) {
                int profile = ((es[i + 2] >> 6) & 0x03) + 1;
                int freqIdx = (es[i + 2] >> 2) & 0x0F;
                int chanCfg = ((es[i + 2] & 0x01) << 2) | ((es[i + 3] >> 6) & 0x03);
                if (freqIdx >= ADTS_SAMPLE_RATES.length) continue;
                int sampleRate = ADTS_SAMPLE_RATES[freqIdx];
                int channels = chanCfg > 0 ? chanCfg : 2;
                MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                byte[] asc = new byte[]{
                        (byte) ((profile << 3) | ((freqIdx >> 1) & 0x07)),
                        (byte) (((freqIdx & 0x01) << 7) | (chanCfg << 3))
                };
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
                return fmt;
            }
        }
        return null;
    }

    private static List<TsSample> extractAdtsFrames(byte[] es, long pesPts90k, long basePtsUs, long lastAPts) {
        List<TsSample> list = new ArrayList<>();
        int pos = 0;
        int frameIndex = 0;
        long baseFramePtsUs = pesPts90k * 1000L / 90L;
        if (basePtsUs >= 0L) baseFramePtsUs = Math.max(0L, baseFramePtsUs - basePtsUs);
        long curPts = lastAPts;

        while (pos + 7 <= es.length) {
            if ((es[pos] & 0xFF) != 0xFF || (es[pos + 1] & 0xF6) != 0xF0) {
                pos++;
                continue;
            }
            boolean protectionAbsent = (es[pos + 1] & 0x01) != 0;
            int hdrLen = protectionAbsent ? 7 : 9;
            int freqIdx = (es[pos + 2] >> 2) & 0x0F;
            int sr = freqIdx < ADTS_SAMPLE_RATES.length ? ADTS_SAMPLE_RATES[freqIdx] : 48000;
            int frameLen = ((es[pos + 3] & 0x03) << 11)
                    | ((es[pos + 4] & 0xFF) << 3)
                    | ((es[pos + 5] >> 5) & 0x07);
            if (frameLen <= hdrLen || pos + frameLen > es.length) break;
            byte[] rawAac = new byte[frameLen - hdrLen];
            System.arraycopy(es, pos + hdrLen, rawAac, 0, rawAac.length);
            long pts = baseFramePtsUs + (frameIndex * 1024L * 1_000_000L) / Math.max(8000, sr);
            if (pts <= curPts) pts = curPts + 1000L;
            curPts = pts;
            list.add(new TsSample(false, rawAac, pts, 0));
            frameIndex++;
            pos += frameLen;
        }
        return list;
    }

    // ---- HLS Playlist Parsers ----

    private static FetchedManifest fetchManifestOrDirect(String startUrl, SessionHeaders headers) throws IOException {
        HttpURLConnection conn = openConnectionWithRedirects(startUrl, "GET", null, headers);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        URL finalUrl = conn.getURL() != null ? conn.getURL() : new URL(startUrl);
        String ct = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase(Locale.ROOT);
        if (ct.startsWith("video/") && !ct.contains("mpegurl")) {
            return new FetchedManifest(finalUrl, "", conn);
        }
        byte[] bytes;
        try (InputStream in = conn.getInputStream()) {
            bytes = readBoundedBytes(in, 4 * 1024 * 1024);
        } finally {
            conn.disconnect();
        }
        String text = stripBom(new String(bytes, StandardCharsets.UTF_8));
        if (!text.trim().startsWith("#EXTM3U")) {
            HttpURLConnection reopen = openConnectionWithRedirects(startUrl, "GET", null, headers);
            return new FetchedManifest(finalUrl, "", reopen);
        }
        return new FetchedManifest(finalUrl, text, null);
    }

    private static Map<String, String> parseHlsAudioGroups(String masterBody, URL baseUrl) {
        Map<String, String> groups = new HashMap<>();
        for (String rawLine : masterBody.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("#EXT-X-MEDIA:")) continue;
            Map<String, String> attrs = parseHlsAttributes(line.substring("#EXT-X-MEDIA:".length()));
            if (!"AUDIO".equalsIgnoreCase(attrs.get("TYPE"))) continue;
            String groupId = attrs.get("GROUP-ID");
            String uri = attrs.get("URI");
            if (groupId == null || uri == null || uri.isBlank()) continue;
            try {
                String resolved = new URL(baseUrl, uri.trim()).toString();
                boolean isDefault = "YES".equalsIgnoreCase(attrs.get("DEFAULT"));
                String lang = attrs.getOrDefault("LANGUAGE", "").toLowerCase(Locale.ROOT);
                if (!groups.containsKey(groupId) || isDefault || lang.startsWith("ar")) {
                    groups.put(groupId, resolved);
                }
            } catch (Exception ignored) {}
        }
        return groups;
    }

    private static VariantCandidate selectBestVariant(String masterBody, URL baseUrl, int targetHeight) {
        List<VariantCandidate> list = new ArrayList<>();
        String[] lines = masterBody.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            Map<String, String> attrs = parseHlsAttributes(line.substring("#EXT-X-STREAM-INF:".length()));
            long bw = parseLongSafe(attrs.getOrDefault("AVERAGE-BANDWIDTH", attrs.getOrDefault("BANDWIDTH", "0")));
            int h = 0;
            String res = attrs.get("RESOLUTION");
            if (res != null && res.contains("x")) {
                String[] wh = res.toLowerCase(Locale.ROOT).split("x");
                if (wh.length == 2) {
                    int w = (int) parseLongSafe(wh[0]);
                    int rh = (int) parseLongSafe(wh[1]);
                    h = normalizeHeight(w, rh);
                }
            }
            String audioGroup = attrs.get("AUDIO");
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty()) continue;
                if (next.startsWith("#")) break;
                try {
                    String resolved = new URL(baseUrl, next).toString();
                    list.add(new VariantCandidate(resolved, h, bw, audioGroup));
                } catch (Exception ignored) {}
                i = j;
                break;
            }
        }
        if (list.isEmpty()) return null;
        if (targetHeight > 0) {
            list.sort((a, b) -> {
                int diffA = a.height() > 0 ? Math.abs(a.height() - targetHeight) : 9999;
                int diffB = b.height() > 0 ? Math.abs(b.height() - targetHeight) : 9999;
                if (diffA != diffB) return Integer.compare(diffA, diffB);
                return Long.compare(b.bandwidth(), a.bandwidth());
            });
        } else {
            list.sort((a, b) -> {
                if (b.height() != a.height()) return Integer.compare(b.height(), a.height());
                return Long.compare(b.bandwidth(), a.bandwidth());
            });
        }
        return list.get(0);
    }

    private static ParsedMediaPlaylist parseMediaPlaylist(String body, URL baseUrl) {
        List<SegmentSpec> segments = new ArrayList<>();
        SegmentSpec initSegment = null;
        long mediaSeq = 0L;
        EncryptionKey currentKey = null;
        long pendingRangeLen = -1L;
        long pendingRangeOff = 0L;
        long lastRangeEnd = 0L;
        boolean hasEndList = false;

        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-ENDLIST") || line.equalsIgnoreCase("#EXT-X-PLAYLIST-TYPE:VOD")) {
                hasEndList = true;
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSeq = parseLongSafe(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attrs = parseHlsAttributes(line.substring("#EXT-X-KEY:".length()));
                String method = attrs.getOrDefault("METHOD", "NONE");
                if ("NONE".equalsIgnoreCase(method)) {
                    currentKey = null;
                } else {
                    String keyUri = attrs.get("URI");
                    String resolvedKeyUri = "";
                    if (keyUri != null && !keyUri.isBlank()) {
                        try { resolvedKeyUri = new URL(baseUrl, keyUri.trim()).toString(); } catch (Exception ignored) {}
                    }
                    byte[] iv = parseHexIv(attrs.get("IV"));
                    currentKey = new EncryptionKey(method, resolvedKeyUri, iv);
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> attrs = parseHlsAttributes(line.substring("#EXT-X-MAP:".length()));
                String mapUri = attrs.get("URI");
                if (mapUri != null && !mapUri.isBlank()) {
                    try {
                        String resolvedMap = new URL(baseUrl, mapUri.trim()).toString();
                        long rLen = -1L;
                        long rOff = 0L;
                        String br = attrs.get("BYTERANGE");
                        if (br != null && !br.isBlank()) {
                            String[] parts = br.split("@");
                            rLen = parseLongSafe(parts[0]);
                            if (parts.length > 1) rOff = parseLongSafe(parts[1]);
                        }
                        initSegment = new SegmentSpec(-1, mediaSeq, resolvedMap, rLen, rOff, currentKey);
                    } catch (Exception ignored) {}
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                String br = line.substring("#EXT-X-BYTERANGE:".length()).trim();
                String[] parts = br.split("@");
                pendingRangeLen = parseLongSafe(parts[0]);
                pendingRangeOff = parts.length > 1 ? parseLongSafe(parts[1]) : lastRangeEnd;
            } else if (!line.startsWith("#")) {
                try {
                    String segUrl = new URL(baseUrl, line).toString();
                    int idx = segments.size();
                    long segSeq = mediaSeq + idx;
                    segments.add(new SegmentSpec(idx, segSeq, segUrl, pendingRangeLen, pendingRangeOff, currentKey));
                    if (pendingRangeLen > 0) {
                        lastRangeEnd = pendingRangeOff + pendingRangeLen;
                    }
                    pendingRangeLen = -1L;
                } catch (Exception ignored) {}
            }
        }
        return new ParsedMediaPlaylist(baseUrl, initSegment, segments, hasEndList);
    }

    private static Map<String, String> parseHlsAttributes(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ATTR_PATTERN.matcher(raw);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(3) != null ? m.group(3) : m.group(2);
            if (key != null && val != null) {
                out.put(key.toUpperCase(Locale.ROOT), val.trim());
            }
        }
        return out;
    }

    private static byte[] parseHexIv(String rawIv) {
        if (rawIv == null) return null;
        String hex = rawIv.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.isEmpty() || hex.length() > 32) return null;
        while (hex.length() < 32) hex = "0" + hex;
        byte[] out = new byte[16];
        try {
            for (int i = 0; i < 16; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ---- Shared HTTP Redirect & Cookie Jar Engine (mirrors PhoneVideoServer) ----

    static HttpURLConnection openConnectionWithRedirects(String startUrl,
                                                         String method,
                                                         String rangeHeader,
                                                         SessionHeaders headers) throws IOException {
        String currentUrl = startUrl;
        String currentMethod = method;
        for (int hop = 0; hop <= 8; hop++) {
            URL parsed = new URL(currentUrl);
            String proto = parsed.getProtocol();
            if (!"http".equalsIgnoreCase(proto) && !"https".equalsIgnoreCase(proto)) {
                throw new IOException("Unsupported protocol: " + proto);
            }
            HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
            conn.setRequestMethod(currentMethod);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("User-Agent", headers.userAgent);
            if (!headers.referer.isEmpty()) conn.setRequestProperty("Referer", headers.referer);
            if (!headers.origin.isEmpty()) conn.setRequestProperty("Origin", headers.origin);

            String curHost = safeHostOf(currentUrl);
            boolean sameDomain = hostMatchesDomain(curHost, headers.rootHost);
            String cookieHeader = buildCookieHeader(headers, curHost, sameDomain);
            if (!cookieHeader.isEmpty()) conn.setRequestProperty("Cookie", cookieHeader);
            if (sameDomain && !headers.authorization.isEmpty()) {
                conn.setRequestProperty("Authorization", headers.authorization);
            }
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                conn.setRequestProperty("Range", rangeHeader);
            }

            int code = conn.getResponseCode();
            absorbSetCookies(headers, curHost, conn);
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isBlank()) {
                    throw new IOException("Redirect without Location");
                }
                currentUrl = new URL(parsed, loc.trim()).toString();
                if (code == 303 && !"GET".equalsIgnoreCase(currentMethod) && !"HEAD".equalsIgnoreCase(currentMethod)) {
                    currentMethod = "GET";
                }
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static void absorbSetCookies(SessionHeaders headers, String host, HttpURLConnection conn) {
        if (host.isEmpty()) return;
        List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
        if (setCookies == null) setCookies = conn.getHeaderFields().get("set-cookie");
        if (setCookies == null || setCookies.isEmpty()) return;
        LinkedHashMap<String, String> jar = parseCookieMap(headers.learnedCookies.get(host));
        for (String header : setCookies) {
            if (header == null) continue;
            String[] parts = header.split(";");
            if (parts.length == 0) continue;
            int eq = parts[0].indexOf('=');
            if (eq <= 0) continue;
            String name = parts[0].substring(0, eq).trim();
            String val = parts[0].substring(eq + 1).trim();
            String domain = host;
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.regionMatches(true, 0, "domain=", 0, 7)) {
                    String d = p.substring(7).trim().toLowerCase(Locale.ROOT);
                    if (d.startsWith(".")) d = d.substring(1);
                    if (!d.isEmpty() && hostMatchesDomain(host, d)) domain = d;
                }
            }
            if (domain.equals(host)) {
                jar.put(name, val);
            } else {
                LinkedHashMap<String, String> domJar = parseCookieMap(headers.learnedCookies.get(domain));
                domJar.put(name, val);
                headers.learnedCookies.put(domain, joinCookieMap(domJar));
            }
        }
        if (!jar.isEmpty()) headers.learnedCookies.put(host, joinCookieMap(jar));
    }

    private static String buildCookieHeader(SessionHeaders headers, String curHost, boolean sameRootDomain) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if ((sameRootDomain || headers.learnedCookies.isEmpty()) && !headers.initialCookie.isEmpty()) {
            merged.putAll(parseCookieMap(headers.initialCookie));
        }
        for (var e : headers.learnedCookies.entrySet()) {
            if (hostMatchesDomain(curHost, e.getKey())) {
                merged.putAll(parseCookieMap(e.getValue()));
            }
        }
        return joinCookieMap(merged);
    }

    private static LinkedHashMap<String, String> parseCookieMap(String header) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (header == null || header.isBlank()) return map;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static String joinCookieMap(LinkedHashMap<String, String> map) {
        if (map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ---- System Progress & Completion Notifications ----

    private static int notifyIdFor(String itemId) {
        return BASE_NOTIFY_ID + (Math.abs(itemId.hashCode()) % 4000);
    }

    private static void ensureNotificationChannel(Context app, NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            boolean ar = isArabic(app);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    ar ? "تحميلات الفيديو (Local Cinema)" : "Local Cinema Video Downloads",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    private static boolean canPostNotifications(Context app) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(app, "android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent launchAppIntent(Context app, int reqCode) {
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launch == null) launch = new Intent();
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(app, reqCode, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void updateProgressNotification(Context app, BrowserDownloadManager.DownloadItem item) {
        if (app == null || item == null || !canPostNotifications(app)) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureNotificationChannel(app, nm);

        int pct = item.progressPercent();
        boolean indeterminate = item.totalBytes <= 0;
        String sizePart = item.totalBytes > 0
                ? (pct + "% · " + BrowserDownloadManager.formatBytes(item.downloadedBytes)
                        + " / " + BrowserDownloadManager.formatBytes(item.totalBytes))
                : BrowserDownloadManager.formatBytes(item.downloadedBytes);
        String speedPart = item.speedBytesPerSec > 0
                ? (" · " + BrowserDownloadManager.formatBytes(item.speedBytesPerSec) + "/s")
                : "";

        Notification n = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_notification)
                .setContentTitle(item.filename)
                .setContentText(sizePart + speedPart)
                .setProgress(100, pct, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launchAppIntent(app, notifyIdFor(item.id)))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build();
        nm.notify(notifyIdFor(item.id), n);
    }

    static void showCompletedNotification(Context app, BrowserDownloadManager.DownloadItem item) {
        if (app == null || item == null || !canPostNotifications(app)) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureNotificationChannel(app, nm);
        boolean ar = isArabic(app);
        String msg = (ar ? "اكتمل التحميل ✓ · Local Cinema/Download · " : "Download complete ✓ · Local Cinema/Download · ")
                + BrowserDownloadManager.formatBytes(Math.max(item.totalBytes, item.downloadedBytes));
        Notification n = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_notification)
                .setContentTitle(item.filename)
                .setContentText(msg)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(launchAppIntent(app, notifyIdFor(item.id)))
                .build();
        nm.notify(notifyIdFor(item.id), n);
    }

    static void showFailedNotification(Context app, BrowserDownloadManager.DownloadItem item) {
        if (app == null || item == null || !canPostNotifications(app)) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureNotificationChannel(app, nm);
        boolean ar = isArabic(app);
        String msg = (ar ? "تعذّر إكمال التحميل: " : "Download failed: ") + (item.error == null ? "" : item.error);
        Notification n = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_notification)
                .setContentTitle(item.filename)
                .setContentText(msg)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(launchAppIntent(app, notifyIdFor(item.id)))
                .build();
        nm.notify(notifyIdFor(item.id), n);
    }

    static void cancelNotification(Context app, String itemId) {
        if (app == null || itemId == null) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifyIdFor(itemId));
    }

    // ---- Utilities ----

    private static boolean isArabic(Context ctx) {
        var locales = ConfigurationCompat.getLocales(ctx.getResources().getConfiguration());
        var loc = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        return loc != null && "ar".equalsIgnoreCase(loc.getLanguage());
    }

    private static String deriveOrigin(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        try {
            Uri u = Uri.parse(rawUrl.trim());
            String scheme = u.getScheme();
            String auth = u.getEncodedAuthority();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && auth != null && !auth.isBlank() && u.getUserInfo() == null) {
                return scheme.toLowerCase(Locale.ROOT) + "://" + auth;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String safeHostOf(String rawUrl) {
        try {
            String h = new URL(rawUrl).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean hostMatchesDomain(String host, String domain) {
        if (host.isEmpty() || domain.isEmpty()) return false;
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String pathOnlyLower(String urlStr) {
        if (urlStr == null) return "";
        int q = urlStr.indexOf('?');
        String p = q >= 0 ? urlStr.substring(0, q) : urlStr;
        return p.toLowerCase(Locale.ROOT);
    }

    private static String stripBom(String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
    }

    private static byte[] readBoundedBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > maxBytes) throw new IOException("Response exceeds maximum allowed size");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void copyFile(File src, File dst) throws IOException {
        File p = dst.getParentFile();
        if (p != null && !p.isDirectory()) //noinspection ResultOfMethodCallIgnored
            p.mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.getFD().sync();
        }
    }

    private static long parseLongSafe(String raw) {
        if (raw == null) return 0L;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return 0L; }
    }

    private static int normalizeHeight(int w, int h) {
        if (w <= 0 && h <= 0) return 0;
        if (w > 0 && h > 0) {
            int maxDim = Math.max(w, h);
            int minDim = Math.min(w, h);
            if (h > w) return minDim;
            if (maxDim >= 3700 || minDim >= 2000) return 2160;
            if (maxDim >= 2450 || minDim >= 1380) return 1440;
            if (maxDim >= 1840 || minDim >= 1000) return 1080;
            if (maxDim >= 1220 || minDim >= 680) return 720;
            if (maxDim >= 800 || minDim >= 440) return 480;
            if (maxDim >= 580 || minDim >= 320) return 360;
            if (maxDim >= 380 || minDim >= 210) return 240;
            return minDim;
        }
        return h;
    }
}
