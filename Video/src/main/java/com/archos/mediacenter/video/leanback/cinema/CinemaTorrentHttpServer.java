package com.archos.mediacenter.video.leanback.cinema;

import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Serves verified torrent bytes to the TV's own media extractor. */
final class CinemaTorrentHttpServer implements AutoCloseable {
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final CinemaTorrentStream torrent;
    private final long size;
    private final ServerSocket listener;
    private final String path;
    private final java.util.concurrent.atomic.AtomicReference<Socket> activeReader =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), work -> new Thread(work, "tv-torrent-http-client"));

    CinemaTorrentHttpServer(CinemaTorrentStream torrent, long size, String name) throws IOException {
        this.torrent = torrent;
        this.size = size;
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (!extension.matches("\\.[a-z0-9]{1,5}")) extension = "";
        path = "/" + UUID.randomUUID() + "/video" + extension;
        listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        new Thread(this::accept, "tv-torrent-http").start();
    }

    Uri uri() { return Uri.parse("http://127.0.0.1:" + listener.getLocalPort() + path); }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                clients.add(socket);
                try {
                    workers.execute(() -> respond(socket));
                } catch (RejectedExecutionException ignored) {
                    clients.remove(socket);
                    closeSocket(socket);
                }
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private void respond(Socket socket) {
        try (socket) {
            socket.setSoTimeout(3000);
            var input = new BufferedInputStream(socket.getInputStream());
            var output = socket.getOutputStream();
            String[] request = readLine(input).split(" ", 3);
            if (request.length != 3 || !path.equals(request[1])
                    || !("GET".equals(request[0]) || "HEAD".equals(request[0]))) {
                writeHeaders(output, "404 Not Found", 0, "");
                return;
            }
            String range = "";
            for (int i = 0; ; i++) {
                if (i == 40) throw new IOException("Too many headers");
                String header = readLine(input);
                if (header.isEmpty()) break;
                if (header.regionMatches(true, 0, "Range:", 0, 6)) range = header.substring(6).trim();
            }
            long start = 0;
            long end = size - 1;
            if (!range.isEmpty()) {
                try {
                    var match = RANGE.matcher(range);
                    if (!match.matches()) throw new NumberFormatException();
                    String first = match.group(1);
                    String last = match.group(2);
                    if (first.isEmpty()) {
                        long suffix = Long.parseLong(last);
                        if (suffix == 0) throw new NumberFormatException();
                        start = Math.max(0, size - suffix);
                    } else {
                        start = Long.parseLong(first);
                        if (!last.isEmpty()) end = Math.min(end, Long.parseLong(last));
                    }
                    if (start >= size || start > end) throw new NumberFormatException();
                } catch (NumberFormatException ignored) {
                    writeHeaders(output, "416 Range Not Satisfiable", 0, "Content-Range: bytes */" + size + "\r\n");
                    return;
                }
            }
            writeHeaders(output, range.isEmpty() ? "200 OK" : "206 Partial Content", end - start + 1,
                    range.isEmpty() ? "" : "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n");
            if ("GET".equals(request[0])) {
                Socket previous = activeReader.getAndSet(socket);
                if (previous != null) closeSocket(previous);
                torrent.serve(start, end, output, socket::isClosed);
            }
        } catch (IOException ignored) {
            // Seeking closes the previous HTTP request.
        } finally {
            activeReader.compareAndSet(socket, null);
            clients.remove(socket);
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        var line = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            int ch = input.read();
            if (ch == '\n') return line.toString();
            if (ch < 0) throw new IOException("Incomplete HTTP request");
            if (ch != '\r') line.append((char) ch);
        }
        throw new IOException("HTTP line too long");
    }

    private static void writeHeaders(OutputStream out, String status, long length, String otherHeaders) throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\nConnection: close\r\nAccept-Ranges: bytes\r\n"
                + "Content-Type: application/octet-stream\r\nContent-Length: " + length + "\r\n" + otherHeaders + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
        for (Socket socket : clients) closeSocket(socket);
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
