package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.ContentResolver;
import android.net.Uri;
import org.nanohttpd.NanoHTTPD;
import java.io.IOException;

final class PhoneVideoServer extends NanoHTTPD {
    final String path = "/video/" + java.util.UUID.randomUUID().toString().replace("-", "");
    private final ContentResolver resolver;
    private final Uri file;
    private org.proninyaroslav.libretorrent.core.model.TorrentEngine torrentEngine;
    private org.proninyaroslav.libretorrent.core.model.stream.TorrentStream torrentStream;
    private String torrentMime;
    private boolean temporaryTorrent;
    private boolean photo;
    private final java.util.LinkedHashMap<String, Uri> audioFiles = new java.util.LinkedHashMap<>();

    record ProxyTarget(String url, String referer, String origin, String userAgent,
                       String cookie, String authorization) {}

    static final class ProxySession {
        final String id;
        final ProxyTarget root;
        final byte[] secret;
        final java.util.concurrent.ConcurrentHashMap<String, String> learnedCookies =
                new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean isLive;
        volatile long lastAccessMs;

        ProxySession(String id, ProxyTarget root, boolean isLive, byte[] secret) {
            this.id = id;
            this.root = root;
            this.isLive = isLive;
            this.secret = secret;
            this.lastAccessMs = System.currentTimeMillis();
        }

        String rootPath() {
            return "/proxy/" + id + "/root";
        }

        String signRoute(String targetUrl) {
            String u = base64UrlEncode(targetUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String sig = hmacSha256(secret, id + "|r|" + targetUrl);
            return "/proxy/" + id + "/r?u=" + u + "&sig=" + sig;
        }

        String route(java.net.URL baseUrl, String rawRef) {
            if (rawRef == null) return "";
            String ref = rawRef.trim();
            if (ref.isEmpty() || ref.startsWith("data:") || ref.startsWith("skd://") || ref.startsWith("/proxy/")) {
                return rawRef;
            }
            try {
                java.net.URL abs = new java.net.URL(baseUrl, ref);
                return signRoute(abs.toString());
            } catch (java.net.MalformedURLException ignored) {
                return rawRef;
            }
        }

        String routeDashTemplate(java.net.URL baseUrl, String rawTemplate) {
            if (rawTemplate == null) return "";
            String ref = rawTemplate.trim();
            if (ref.isEmpty() || ref.startsWith("/proxy/")) return rawTemplate;
            if (!ref.contains("$")) return route(baseUrl, ref);
            try {
                java.net.URL abs = resolveDashTemplateUrl(baseUrl, ref);
                String tplStr = abs.toString();
                String t = base64UrlEncode(tplStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String sig = hmacSha256(secret, id + "|tpl|" + tplStr);
                StringBuilder sb = new StringBuilder("/proxy/").append(id)
                        .append("/tpl?t=").append(t).append("&sig=").append(sig);
                if (tplStr.contains("$RepresentationID$")) sb.append("&rep=$RepresentationID$");
                if (tplStr.contains("$Number")) sb.append("&num=$Number$");
                if (tplStr.contains("$Time")) sb.append("&time=$Time$");
                if (tplStr.contains("$Bandwidth$")) sb.append("&bw=$Bandwidth$");
                return sb.toString();
            } catch (Exception ignored) {
                return rawTemplate;
            }
        }
    }

    private static final int MAX_PROXY_SESSIONS = 32;
    private static final long PROXY_SESSION_TTL_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_IN_MEMORY_SUBS = 64;
    private static final java.security.SecureRandom PROXY_RNG = new java.security.SecureRandom();
    private static final java.util.concurrent.ConcurrentHashMap<String, ProxySession> PROXY_SESSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, InMemorySub> IN_MEMORY_SUBS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record InMemorySub(byte[] body, String mime, long createdAtMs) {}

    static String registerInMemorySubtitle(String text, String mime) {
        if (text == null || text.isBlank()) return null;
        long now = System.currentTimeMillis();
        var it = IN_MEMORY_SUBS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().createdAtMs() > PROXY_SESSION_TTL_MS) {
                it.remove();
            }
        }
        while (IN_MEMORY_SUBS.size() >= MAX_IN_MEMORY_SUBS) {
            String oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            for (var e : IN_MEMORY_SUBS.entrySet()) {
                if (e.getValue().createdAtMs() < oldestTs) {
                    oldestTs = e.getValue().createdAtMs();
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey == null) break;
            IN_MEMORY_SUBS.remove(oldestKey);
        }
        String trimmed = text.replace("\uFEFF", "").trim();
        String detectedMime;
        if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("WEBVTT")) {
            detectedMime = "text/vtt; charset=utf-8";
        } else if (trimmed.contains("[Script Info]") || trimmed.contains("Dialogue:")) {
            detectedMime = "text/x-ssa; charset=utf-8";
        } else if (trimmed.contains("-->")) {
            detectedMime = "application/x-subrip; charset=utf-8";
        } else if (mime != null && !mime.isBlank()) {
            String baseMime = mime.split(";")[0].trim();
            detectedMime = baseMime + "; charset=utf-8";
        } else {
            detectedMime = "application/x-subrip; charset=utf-8";
        }
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        IN_MEMORY_SUBS.put(id, new InMemorySub(bytes, detectedMime, now));
        return "/proxy/sub/" + id;
    }

    static String registerProxyTarget(String url, String referer, String origin, String userAgent, String cookie) {
        return registerProxySession(url, referer, origin, userAgent, cookie, null, false);
    }

    static String registerProxySession(String url, String referer, String origin, String userAgent,
                                       String cookie, String authorization, boolean isLive) {
        if (url == null || url.isBlank()) return null;
        pruneProxySessions();
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] secret = new byte[32];
        PROXY_RNG.nextBytes(secret);
        ProxyTarget root = new ProxyTarget(
                url.trim(),
                referer == null ? "" : referer.trim(),
                origin == null ? "" : origin.trim(),
                userAgent == null ? "" : userAgent.trim(),
                cookie == null ? "" : cookie.trim(),
                authorization == null ? "" : authorization.trim());
        ProxySession session = new ProxySession(id, root, isLive, secret);
        PROXY_SESSIONS.put(id, session);
        return session.rootPath();
    }

    private static void pruneProxySessions() {
        long now = System.currentTimeMillis();
        var it = PROXY_SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue().lastAccessMs > PROXY_SESSION_TTL_MS) {
                it.remove();
            }
        }
        while (PROXY_SESSIONS.size() >= MAX_PROXY_SESSIONS) {
            String oldestId = null;
            long oldestAccess = Long.MAX_VALUE;
            for (var e : PROXY_SESSIONS.entrySet()) {
                if (e.getValue().lastAccessMs < oldestAccess) {
                    oldestAccess = e.getValue().lastAccessMs;
                    oldestId = e.getKey();
                }
            }
            if (oldestId == null) break;
            PROXY_SESSIONS.remove(oldestId);
        }
    }

    static void clearIdleBuffers() {
        PROXY_SESSIONS.clear();
        IN_MEMORY_SUBS.clear();
    }

    static long estimatedMemoryBufferBytes() {
        long bytes = 0L;
        for (InMemorySub sub : IN_MEMORY_SUBS.values()) {
            if (sub != null && sub.body() != null) {
                bytes += sub.body().length;
            }
        }
        return bytes;
    }

    boolean isAudioServer() { return resolver != null && file == null; }

    synchronized String offerAudio(Uri audio) {
        String key = "/video/" + java.util.UUID.randomUUID().toString().replace("-", "");
        audioFiles.put(key, audio);
        while (audioFiles.size() > 2) audioFiles.remove(audioFiles.keySet().iterator().next());
        return key;
    }

    private synchronized Uri audioFile(String key) { return audioFiles.get(key); }


    PhoneVideoServer(String host, ContentResolver resolver, Uri file) {
        super(host, 8766);
        this.resolver = resolver;
        this.file = file;
    }

    PhoneVideoServer(String host, ContentResolver resolver, Uri file, boolean photo) {
        this(host, resolver, file);
        this.photo = photo;
    }

    PhoneVideoServer(String host, org.proninyaroslav.libretorrent.core.model.TorrentEngine engine,
                     org.proninyaroslav.libretorrent.core.model.stream.TorrentStream stream, String name, boolean temporary) {
        this(host, null, null);
        torrentEngine = engine;
        torrentStream = stream;
        temporaryTorrent = temporary;
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        torrentMime = switch (extension) {
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "ts" -> "video/mp2t";
            default -> "video/mp4";
        };
    }

    static byte[] jpeg(ContentResolver resolver, Uri file) throws IOException {
        {
            var bitmap = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(resolver, file), (decoder, info, source) -> {
                        var size = info.getSize();
                        float ratio = Math.min(1f, 3840f / Math.max(size.getWidth(), size.getHeight()));
                        decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * ratio)),
                                Math.max(1, Math.round(size.getHeight() * ratio)));
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            try {
                var bytes = new java.io.ByteArrayOutputStream();
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bytes)) {
                    throw new IOException("Could not encode photo");
                }
                return bytes.toByteArray();
            } finally {
                bitmap.recycle();
            }
        }

    }

    @Override public Response serve(IHTTPSession session) {
        if (session.getUri() != null && session.getUri().startsWith("/proxy/")) {
            return serveProxy(session);
        }
        Uri audio = isAudioServer() ? audioFile(session.getUri()) : null;
        if (audio == null && (!path.equals(session.getUri()) || isAudioServer()))
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
        if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "");
        }
        try {
            if (torrentStream != null) return serveTorrent(session);
            var response = serveFile(session, resolver, audio == null ? file : audio);
            if (audio != null) android.util.Log.d("CinemaAudio", "HTTP " + response.getStatus().getRequestStatus()
                    + " range=" + response.getHeader("Content-Range"));
            return response;
        } catch (RuntimeException error) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot read media");
        }
    }

    boolean isTorrentStream() { return torrentStream != null; }

    @Override public synchronized void stop() {
        super.stop();
        audioFiles.clear();
        pruneProxySessions();
        if (temporaryTorrent && torrentEngine != null && torrentStream != null) {
            temporaryTorrent = false;
            torrentEngine.deleteTemporaryCast(torrentStream.torrentId);
        }
    }

    private Response serveTorrent(IHTTPSession request) {
        long size = torrentStream.fileSize;
        String range = request.getHeaders().get("range");
        long start = 0, end = size - 1;
        if (range != null) {
            try {
                var bounds = java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
                if (!bounds.matches()) throw new NumberFormatException();
                if (bounds.group(1).isEmpty()) {
                    long suffix = Long.parseLong(bounds.group(2));
                    if (suffix <= 0) throw new NumberFormatException();
                    start = Math.max(0, size - suffix);
                } else {
                    start = Long.parseLong(bounds.group(1));
                    if (!bounds.group(2).isEmpty()) end = Math.min(end, Long.parseLong(bounds.group(2)));
                }
                if (start > end) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                var response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
                response.addHeader("Content-Range", "bytes */" + size);
                return response;
            }
        }
        java.io.InputStream input = java.io.InputStream.nullInputStream();
        if (request.getMethod() != Method.HEAD) {
            try {
                input = new java.io.BufferedInputStream(
                        torrentEngine.openCastStream(torrentStream, start, end - start + 1), 512 * 1024);
            } catch (IOException error) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Torrent stream unavailable");
            }
        }
        var response = newFixedLengthResponse(range == null ? Response.Status.OK : Response.Status.PARTIAL_CONTENT,
                torrentMime, input, end - start + 1);
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Connection", "keep-alive");
        if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        return response;
    }

    static Response serveFile(IHTTPSession session, ContentResolver resolver, Uri file) {
        try {
            var descriptor = resolver.openAssetFileDescriptor(file, "r");
            if (descriptor == null) throw new IOException("Video unavailable");
            long length = descriptor.getLength();
            if (length < 0) {
                descriptor.close();
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Unknown video length");
            }
            long start = 0;
            long end = length - 1;
            String range = session.getHeaders().get("range");
            if (range != null) {
                try {
                    var bounds = java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
                    if (!bounds.matches()) throw new NumberFormatException();
                    if (bounds.group(1).isEmpty()) {
                        long suffix = Long.parseLong(bounds.group(2));
                        if (suffix == 0) throw new NumberFormatException();
                        start = Math.max(0, length - suffix);
                    } else {
                        start = Long.parseLong(bounds.group(1));
                        if (!bounds.group(2).isEmpty()) end = Math.min(end, Long.parseLong(bounds.group(2)));
                    }
                    if (start > end) throw new NumberFormatException();
                } catch (NumberFormatException error) {
                    descriptor.close();
                    var response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
                    response.addHeader("Content-Range", "bytes */" + length);
                    return response;
                }
            }
            String type = resolver.getType(file);
            if (type == null || type.isBlank() || "application/octet-stream".equalsIgnoreCase(type)) {
                String pathStr = file.toString().toLowerCase(java.util.Locale.ROOT);
                if (pathStr.endsWith(".mov")) type = "video/quicktime";
                else if (pathStr.endsWith(".mkv")) type = "video/x-matroska";
                else if (pathStr.endsWith(".webm")) type = "video/webm";
                else if (pathStr.endsWith(".ts")) type = "video/mp2t";
                else if (pathStr.endsWith(".avi")) type = "video/x-msvideo";
                else type = "video/mp4";
            }
            if (session.getMethod() == Method.HEAD) {
                descriptor.close();
                var response = newFixedLengthResponse(range == null ? Response.Status.OK : Response.Status.PARTIAL_CONTENT,
                        type, java.io.InputStream.nullInputStream(), end - start + 1);
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Cache-Control", "no-cache");
                response.addHeader("Connection", "keep-alive");
                if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
                return response;
            }
            var stream = descriptor.createInputStream();
            try {
                long remaining = start;
                while (remaining > 0) {
                    long skipped = stream.skip(remaining);
                    if (skipped == 0) throw new IOException("Cannot seek in video");
                    remaining -= skipped;
                }
                var buffered = new java.io.BufferedInputStream(stream, 512 * 1024);
                var response = newFixedLengthResponse(range == null ? Response.Status.OK : Response.Status.PARTIAL_CONTENT,
                        type, buffered, end - start + 1);
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Cache-Control", "no-cache");
                response.addHeader("Connection", "keep-alive");
                if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
                return response;
            } catch (IOException error) {
                stream.close();
                throw error;
            }
        } catch (IOException | RuntimeException error) {
            android.util.Log.w("CinemaMedia", "Could not serve selected media", error);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot read video");
        }
    }

    private Response serveProxy(IHTTPSession session) {
        if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "");
        }
        String uri = session.getUri();
        if (uri == null || !uri.startsWith("/proxy/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        if (uri.startsWith("/proxy/sub/")) {
            String subId = uri.substring("/proxy/sub/".length());
            InMemorySub sub = IN_MEMORY_SUBS.get(subId);
            if (sub == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Subtitle expired");
            }
            Response r = session.getMethod() == Method.HEAD
                    ? newFixedLengthResponse(Response.Status.OK, sub.mime(), "")
                    : newFixedLengthResponse(
                            Response.Status.OK,
                            sub.mime(),
                            new java.io.ByteArrayInputStream(sub.body()),
                            sub.body().length);
            r.addHeader("Content-Length", String.valueOf(sub.body().length));
            r.addHeader("Cache-Control", "no-cache");
            r.addHeader("Access-Control-Allow-Origin", "*");
            return r;
        }
        String rest = uri.substring("/proxy/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        String id = rest.substring(0, slash);
        String action = rest.substring(slash + 1);
        ProxySession ps = PROXY_SESSIONS.get(id);
        if (ps == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Session expired");
        }
        long now = System.currentTimeMillis();
        if (now - ps.lastAccessMs > PROXY_SESSION_TTL_MS) {
            PROXY_SESSIONS.remove(id);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Session expired");
        }
        ps.lastAccessMs = now;

        final String targetUrl;
        try {
            var params = session.getParms();
            if ("root".equals(action)) {
                targetUrl = ps.root.url();
            } else if ("r".equals(action)) {
                String u = firstParam(params, "u");
                String sig = firstParam(params, "sig");
                if (u == null || sig == null) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
                }
                String decoded = new String(base64UrlDecode(u), java.nio.charset.StandardCharsets.UTF_8);
                if (!constantTimeEquals(hmacSha256(ps.secret, id + "|r|" + decoded), sig)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid signature");
                }
                if (isForbiddenSsrfUrl(decoded, ps.root.url())) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Blocked host");
                }
                targetUrl = decoded;
            } else if ("tpl".equals(action)) {
                String t = firstParam(params, "t");
                String sig = firstParam(params, "sig");
                if (t == null || sig == null) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
                }
                String tplUrl = new String(base64UrlDecode(t), java.nio.charset.StandardCharsets.UTF_8);
                if (!constantTimeEquals(hmacSha256(ps.secret, id + "|tpl|" + tplUrl), sig)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid signature");
                }
                targetUrl = expandDashTemplate(tplUrl,
                        firstParam(params, "rep"),
                        firstParam(params, "num"),
                        firstParam(params, "time"),
                        firstParam(params, "bw"));
                if (targetUrl == null || isForbiddenSsrfUrl(targetUrl, ps.root.url())) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Blocked target");
                }
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
        } catch (IllegalArgumentException badReq) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad request");
        }

        java.net.HttpURLConnection conn = null;
        try {
            conn = openProxyConnection(ps, targetUrl, session.getMethod().name(), session.getHeaders());
            int code = conn.getResponseCode();
            java.net.URL finalUrl = conn.getURL();
            String finalUrlStr = finalUrl != null ? finalUrl.toString() : targetUrl;
            String rawCt = conn.getContentType();
            String baseMime = (rawCt == null || rawCt.isBlank())
                    ? "application/octet-stream"
                    : rawCt.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            String resolvedMime = inferProxyMime(baseMime, finalUrlStr);

            if (code >= 200 && code < 300 && session.getMethod() == Method.GET) {
                String lowerPath = pathOnlyLower(finalUrlStr);
                boolean isM3u8 = resolvedMime.contains("mpegurl") || lowerPath.endsWith(".m3u8");
                boolean isMpd = resolvedMime.contains("dash+xml") || lowerPath.endsWith(".mpd");
                if (isM3u8 || isMpd) {
                    byte[] bytes;
                    try (var rawIn = conn.getInputStream()) {
                        bytes = readProxyManifest(rawIn);
                    }
                    conn.disconnect();
                    conn = null;
                    String body = stripBom(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    String trimmed = body.trim();
                    if (isM3u8 && trimmed.startsWith("#EXTM3U")) {
                        String rewritten = rewriteM3u8(body, finalUrl, ps);
                        var res = newFixedLengthResponse(Response.Status.OK,
                                "application/vnd.apple.mpegurl", rewritten);
                        res.addHeader("Cache-Control", ps.isLive
                                ? "no-store, no-cache, must-revalidate"
                                : "no-cache");
                        return res;
                    }
                    if (isMpd && trimmed.startsWith("<") && trimmed.contains("<MPD")) {
                        String rewritten = rewriteMpd(body, finalUrl, ps);
                        var res = newFixedLengthResponse(Response.Status.OK,
                                "application/dash+xml", rewritten);
                        res.addHeader("Cache-Control", ps.isLive
                                ? "no-store, no-cache, must-revalidate"
                                : "no-cache");
                        return res;
                    }
                    return newFixedLengthResponse(Response.Status.OK, resolvedMime,
                            new java.io.ByteArrayInputStream(bytes), bytes.length);
                }
            }

            Response.IStatus status = mapHttpStatus(code);
            if (session.getMethod() == Method.HEAD || code >= 400) {
                String contentRange = conn.getHeaderField("Content-Range");
                String acceptRanges = conn.getHeaderField("Accept-Ranges");
                String retryAfter = conn.getHeaderField("Retry-After");
                conn.disconnect();
                conn = null;
                var res = newFixedLengthResponse(status, resolvedMime, "");
                if (contentRange != null) res.addHeader("Content-Range", contentRange);
                if (acceptRanges != null) res.addHeader("Accept-Ranges", acceptRanges);
                if (retryAfter != null) res.addHeader("Retry-After", retryAfter);
                return res;
            }

            final java.net.HttpURLConnection activeConn = conn;
            var bodyStream = new java.io.FilterInputStream(
                    new java.io.BufferedInputStream(activeConn.getInputStream(), 256 * 1024)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { activeConn.disconnect(); }
                }
            };
            conn = null;
            long len = activeConn.getContentLengthLong();
            Response res = len >= 0
                    ? newFixedLengthResponse(status, resolvedMime, bodyStream, len)
                    : newChunkedResponse(status, resolvedMime, bodyStream);
            String acceptRanges = activeConn.getHeaderField("Accept-Ranges");
            res.addHeader("Accept-Ranges", acceptRanges != null ? acceptRanges : "bytes");
            String contentRange = activeConn.getHeaderField("Content-Range");
            if (contentRange != null) res.addHeader("Content-Range", contentRange);
            res.addHeader("Cache-Control", ps.isLive ? "no-store, no-cache, must-revalidate" : "no-cache");
            return res;
        } catch (IOException | RuntimeException error) {
            if (conn != null) conn.disconnect();
            android.util.Log.w("CinemaProxy", "Proxy failed for host=" + safeHostOf(targetUrl), error);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy error");
        }
    }

    private static java.net.HttpURLConnection openProxyConnection(ProxySession ps, String startUrl,
                                                                  String method,
                                                                  java.util.Map<String, String> reqHeaders) throws IOException {
        String currentUrl = startUrl;
        String currentMethod = method;
        String rootHost = safeHostOf(ps.root.url());
        for (int hop = 0; hop <= 6; hop++) {
            if (isForbiddenSsrfUrl(currentUrl, ps.root.url())) {
                throw new IOException("Blocked redirect target");
            }
            java.net.URL parsed = new java.net.URL(currentUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) parsed.openConnection();
            conn.setRequestMethod(currentMethod);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            if (!ps.root.referer().isEmpty()) conn.setRequestProperty("Referer", ps.root.referer());
            if (!ps.root.origin().isEmpty()) conn.setRequestProperty("Origin", ps.root.origin());
            if (!ps.root.userAgent().isEmpty()) conn.setRequestProperty("User-Agent", ps.root.userAgent());

            String curHost = safeHostOf(currentUrl);
            boolean sameDomain = hostMatchesDomain(curHost, rootHost);
            String cookieHeader = buildCookieHeader(ps, curHost, sameDomain);
            if (!cookieHeader.isEmpty()) conn.setRequestProperty("Cookie", cookieHeader);
            if (sameDomain && !ps.root.authorization().isEmpty()) {
                conn.setRequestProperty("Authorization", ps.root.authorization());
            }
            if (reqHeaders != null) {
                String range = reqHeaders.get("range");
                if (range != null && !range.isBlank()) conn.setRequestProperty("Range", range);
                String ifRange = reqHeaders.get("if-range");
                if (ifRange != null && !ifRange.isBlank()) conn.setRequestProperty("If-Range", ifRange);
            }

            int code = conn.getResponseCode();
            absorbSetCookies(ps, curHost, conn);
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isBlank()) {
                    throw new IOException("Redirect without Location");
                }
                currentUrl = new java.net.URL(parsed, loc.trim()).toString();
                if (code == 303 && !"GET".equalsIgnoreCase(currentMethod) && !"HEAD".equalsIgnoreCase(currentMethod)) {
                    currentMethod = "GET";
                }
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static void absorbSetCookies(ProxySession ps, String host, java.net.HttpURLConnection conn) {
        if (host.isEmpty()) return;
        java.util.List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
        if (setCookies == null) setCookies = conn.getHeaderFields().get("set-cookie");
        if (setCookies == null || setCookies.isEmpty()) return;
        java.util.LinkedHashMap<String, String> jar = parseCookieMap(ps.learnedCookies.get(host));
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
                    String d = p.substring(7).trim().toLowerCase(java.util.Locale.ROOT);
                    if (d.startsWith(".")) d = d.substring(1);
                    if (!d.isEmpty() && hostMatchesDomain(host, d)) domain = d;
                }
            }
            if (domain.equals(host)) {
                jar.put(name, val);
            } else {
                java.util.LinkedHashMap<String, String> domJar = parseCookieMap(ps.learnedCookies.get(domain));
                domJar.put(name, val);
                ps.learnedCookies.put(domain, joinCookieMap(domJar));
            }
        }
        if (!jar.isEmpty()) ps.learnedCookies.put(host, joinCookieMap(jar));
    }

    private static String buildCookieHeader(ProxySession ps, String curHost, boolean sameRootDomain) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        if (sameRootDomain && !ps.root.cookie().isEmpty()) {
            merged.putAll(parseCookieMap(ps.root.cookie()));
        }
        for (var e : ps.learnedCookies.entrySet()) {
            if (hostMatchesDomain(curHost, e.getKey())) {
                merged.putAll(parseCookieMap(e.getValue()));
            }
        }
        return joinCookieMap(merged);
    }

    private static java.util.LinkedHashMap<String, String> parseCookieMap(String header) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (header == null || header.isBlank()) return map;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static String joinCookieMap(java.util.LinkedHashMap<String, String> map) {
        if (map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static byte[] readProxyManifest(java.io.InputStream in) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > 4 * 1024 * 1024) throw new IOException("Manifest too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String rewriteM3u8(String manifest, java.net.URL baseUrl, ProxySession ps) {
        boolean hasSegments = manifest.contains("#EXTINF:") || manifest.contains("#EXT-X-PART:");
        boolean hasEndlist = manifest.contains("#EXT-X-ENDLIST") || manifest.contains("#EXT-X-PLAYLIST-TYPE:VOD");
        if (hasSegments && !hasEndlist) {
            ps.isLive = true;
        } else if (hasEndlist) {
            ps.isLive = false;
        }
        var out = new StringBuilder(manifest.length() + 1024);
        var uriAttrPattern = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");
        for (String line : manifest.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("#")) {
                var m = uriAttrPattern.matcher(line);
                var sb = new StringBuilder();
                int last = 0;
                while (m.find()) {
                    sb.append(line, last, m.start());
                    String resolved = ps.route(baseUrl, m.group(1));
                    sb.append("URI=\"").append(resolved).append('"');
                    last = m.end();
                }
                sb.append(line, last, line.length());
                out.append(sb).append('\n');
            } else {
                out.append(ps.route(baseUrl, trimmed)).append('\n');
            }
        }
        return out.toString();
    }

    private static String rewriteMpd(String xmlText, java.net.URL baseUrl, ProxySession ps) throws IOException {
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Throwable ignored) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Throwable ignored) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Throwable ignored) {}
            try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Throwable ignored) {}
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlText)));
            org.w3c.dom.Element root = doc.getDocumentElement();
            if (root != null) {
                if ("dynamic".equalsIgnoreCase(root.getAttribute("type"))) {
                    ps.isLive = true;
                }
                rewriteMpdElement(root, baseUrl, ps);
            }
            javax.xml.transform.Transformer tf = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
            tf.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no");
            java.io.StringWriter sw = new java.io.StringWriter();
            tf.transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new IOException("MPD rewrite failed", e);
        }
    }

    private static void rewriteMpdElement(org.w3c.dom.Element el, java.net.URL parentBase, ProxySession ps) {
        java.net.URL elementBase = parentBase;
        var children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var n = children.item(i);
            if (n instanceof org.w3c.dom.Element child) {
                String childTag = child.getLocalName() != null ? child.getLocalName() : child.getTagName();
                if ("BaseURL".equals(childTag)) {
                    String rawBase = child.getTextContent() != null ? child.getTextContent().trim() : "";
                    if (!rawBase.isEmpty()) {
                        try { elementBase = resolveDashTemplateUrl(parentBase, rawBase); } catch (Exception ignored) {}
                    }
                    break;
                }
            }
        }
        String tag = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
        if ("BaseURL".equals(tag)) {
            String rawBase = el.getTextContent() != null ? el.getTextContent().trim() : "";
            if (!rawBase.isEmpty()) {
                el.setTextContent(ps.route(parentBase, rawBase));
            }
            return;
        }
        if ("SegmentURL".equals(tag)) {
            rewriteAttrUrl(el, "media", elementBase, ps, false);
            rewriteAttrUrl(el, "index", elementBase, ps, false);
        } else if ("Initialization".equals(tag) || "RepresentationIndex".equals(tag)) {
            rewriteAttrUrl(el, "sourceURL", elementBase, ps, false);
        } else if ("SegmentTemplate".equals(tag)) {
            rewriteAttrUrl(el, "initialization", elementBase, ps, true);
            rewriteAttrUrl(el, "media", elementBase, ps, true);
            rewriteAttrUrl(el, "index", elementBase, ps, true);
        } else if ("SegmentBase".equals(tag) || "SegmentList".equals(tag)) {
            rewriteAttrUrl(el, "index", elementBase, ps, false);
        }
        for (int i = 0; i < children.getLength(); i++) {
            var n = children.item(i);
            if (n instanceof org.w3c.dom.Element child) {
                rewriteMpdElement(child, elementBase, ps);
            }
        }
    }

    private static void rewriteAttrUrl(org.w3c.dom.Element el, String attr,
                                       java.net.URL base, ProxySession ps, boolean allowTemplate) {
        String val = el.getAttribute(attr);
        if (val == null || val.isBlank()) return;
        String trimmed = val.trim();
        String rewritten = allowTemplate ? ps.routeDashTemplate(base, trimmed) : ps.route(base, trimmed);
        el.setAttribute(attr, rewritten);
    }

    private static java.net.URL resolveDashTemplateUrl(java.net.URL base, String rawRef) throws java.net.MalformedURLException {
        String masked = rawRef.replace("$RepresentationID$", "__DASH_REP__")
                .replace("$Bandwidth$", "__DASH_BW__");
        masked = masked.replaceAll("\\$Number(?:%0?\\d*d)?\\$", "__DASH_NUM__");
        masked = masked.replaceAll("\\$Time(?:%0?\\d*d)?\\$", "__DASH_TIME__");
        java.net.URL abs = new java.net.URL(base, masked);
        String restored = abs.toString();
        String[] tokens = new String[]{"RepresentationID", "Number", "Time", "Bandwidth"};
        var placeholders = new String[]{"__DASH_REP__", "__DASH_NUM__", "__DASH_TIME__", "__DASH_BW__"};
        for (int i = 0; i < tokens.length; i++) {
            String originalTok = extractOriginalDashToken(rawRef, tokens[i]);
            if (originalTok != null) {
                restored = restored.replace(placeholders[i], originalTok);
            }
        }
        return new java.net.URL(restored);
    }

    private static String extractOriginalDashToken(String src, String name) {
        var m = java.util.regex.Pattern.compile("\\$" + name + "(?:%[^$]*)?\\$").matcher(src);
        return m.find() ? m.group(0) : null;
    }

    private static String expandDashTemplate(String tplUrl, String rep, String num, String time, String bw) {
        String out = tplUrl;
        if (rep != null) {
            if (!rep.matches("[A-Za-z0-9._:+/-]{1,96}") || rep.contains("..") || rep.contains("@")) return null;
            out = out.replace("$RepresentationID$", rep);
        }
        if (num != null) {
            if (!num.matches("[0-9]{1,20}")) return null;
            out = replaceNumericDashToken(out, "Number", Long.parseLong(num));
        }
        if (time != null) {
            if (!time.matches("[0-9]{1,20}")) return null;
            out = replaceNumericDashToken(out, "Time", Long.parseLong(time));
        }
        if (bw != null) {
            if (!bw.matches("[0-9]{1,16}")) return null;
            out = out.replace("$Bandwidth$", bw);
        }
        return out;
    }

    private static String replaceNumericDashToken(String src, String token, long value) {
        var m = java.util.regex.Pattern.compile("\\$" + token + "(?:%0?(\\d+)d)?\\$").matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String width = m.group(1);
            String formatted = (width != null && !width.isEmpty())
                    ? String.format(java.util.Locale.ROOT, "%0" + Math.min(Integer.parseInt(width), 16) + "d", value)
                    : Long.toString(value);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(formatted));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String inferProxyMime(String upstreamMime, String urlStr) {
        String lower = pathOnlyLower(urlStr);
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.endsWith(".mpd")) return "application/dash+xml";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".m4s")) return "video/iso.segment";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".vtt")) return "text/vtt; charset=utf-8";
        if (lower.endsWith(".srt")) return "application/x-subrip; charset=utf-8";
        return upstreamMime;
    }

    private static String pathOnlyLower(String urlStr) {
        if (urlStr == null) return "";
        int q = urlStr.indexOf('?');
        String p = q >= 0 ? urlStr.substring(0, q) : urlStr;
        return p.toLowerCase(java.util.Locale.ROOT);
    }

    private static String stripBom(String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
    }

    private static Response.IStatus mapHttpStatus(int code) {
        return switch (code) {
            case 200 -> Response.Status.OK;
            case 206 -> Response.Status.PARTIAL_CONTENT;
            case 400 -> Response.Status.BAD_REQUEST;
            case 401 -> Response.Status.UNAUTHORIZED;
            case 403 -> Response.Status.FORBIDDEN;
            case 404 -> Response.Status.NOT_FOUND;
            case 416 -> Response.Status.RANGE_NOT_SATISFIABLE;
            default -> new Response.IStatus() {
                @Override public String getDescription() { return code + " HTTP"; }
                @Override public int getRequestStatus() { return code; }
            };
        };
    }

    private static String firstParam(java.util.Map<String, String> params, String key) {
        return params == null ? null : params.get(key);
    }

    private static String base64UrlEncode(byte[] raw) {
        return android.util.Base64.encodeToString(raw,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    private static byte[] base64UrlDecode(String encoded) {
        return android.util.Base64.decode(encoded,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    private static String hmacSha256(byte[] secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return base64UrlEncode(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String safeHostOf(String rawUrl) {
        try {
            String h = new java.net.URL(rawUrl).getHost();
            return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean hostMatchesDomain(String host, String domain) {
        if (host.isEmpty() || domain.isEmpty()) return false;
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean isForbiddenSsrfUrl(String rawUrl, String rootUrl) {
        try {
            java.net.URL parsed = new java.net.URL(rawUrl);
            String scheme = parsed.getProtocol();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return true;
            String host = parsed.getHost();
            if (host == null || host.isBlank()) return true;
            String lh = host.toLowerCase(java.util.Locale.ROOT);
            if (lh.equals(safeHostOf(rootUrl))) return false;
            if ("localhost".equals(lh) || lh.endsWith(".localhost") || lh.endsWith(".local") || "0.0.0.0".equals(lh)) {
                return true;
            }
            java.net.InetAddress addr = java.net.InetAddress.getByName(lh);
            return addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isMulticastAddress();
        } catch (Exception ignored) {
            return true;
        }
    }
}
