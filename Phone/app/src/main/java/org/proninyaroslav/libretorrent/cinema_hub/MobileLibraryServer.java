package org.proninyaroslav.libretorrent.cinema_hub;

import android.net.Uri;
import org.nanohttpd.NanoHTTPD;
import java.io.File;
import java.io.IOException;

final class MobileLibraryServer extends NanoHTTPD {
    private final java.util.Map<String, File> folders = new java.util.concurrent.ConcurrentHashMap<>();
    private final android.content.ContentResolver resolver;
    private final String secret;

    MobileLibraryServer(String host, android.content.ContentResolver resolver, String secret) {
        super(host, 8080);
        this.resolver = resolver;
        this.secret = secret;
    }

    String publish(File folder) {
        String id = java.util.UUID.nameUUIDFromBytes(folder.getAbsolutePath()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        folders.put(id, folder);
        return "/webdav/" + secret + "/" + id + "/";
    }

    void unpublish(String path) {
        folders.remove(path);
    }

    @Override public Response serve(IHTTPSession session) {
        String path = session.getUri();
        String[] pieces = path.split("/", 5);
        if (pieces.length < 4 || !"webdav".equals(pieces[1]) || !secret.equals(pieces[2]))
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
        File root = folders.get(pieces[3]);
        if (root == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
        try {
            File base = root.getCanonicalFile();
            File target = pieces.length == 4 ? base : new File(base, pieces[4]).getCanonicalFile();
            if ((!target.equals(base) && !target.getPath().startsWith(base.getPath() + File.separator))
                    || !target.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
            switch (session.getMethod()) {
                case OPTIONS:
                    var options = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
                    options.addHeader("DAV", "1");
                    options.addHeader("Allow", "OPTIONS, PROPFIND, GET, HEAD");
                    return options;
                case PROPFIND:
                    String href = Uri.encode(path, "/");
                    if (target.isDirectory() && !href.endsWith("/")) href += "/";
                    var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><D:multistatus xmlns:D=\"DAV:\">");
                    entry(xml, target, href);
                    if (target.isDirectory() && !"0".equals(session.getHeaders().get("depth"))) {
                        File[] children = target.listFiles();
                        if (children != null) for (File child : children) {
                            if (!child.isHidden() && child.getCanonicalFile().getParentFile().equals(target))
                                entry(xml, child, href + Uri.encode(child.getName()) + (child.isDirectory() ? "/" : ""));
                        }
                    }
                    var listing = newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml; charset=utf-8",
                            xml.append("</D:multistatus>").toString());
                    // The XML request body is unused. Close so it cannot be read as the next request.
                    listing.addHeader("Connection", "close");
                    return listing;
                case GET:
                case HEAD:
                    if (target.isFile()) return PhoneVideoServer.serveFile(session, resolver, Uri.fromFile(target));
                    break;
                default: break;
            }
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "");
        } catch (IOException | SecurityException error) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "");
        }
    }

    private void entry(StringBuilder xml, File file, String href) {
        xml.append("<D:response><D:href>").append(href)
                .append("</D:href><D:propstat><D:prop><D:resourcetype>");
        if (file.isDirectory()) xml.append("<D:collection/>");
        xml.append("</D:resourcetype><D:getcontentlength>").append(file.isDirectory() ? 0 : file.length())
                .append("</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }
}
