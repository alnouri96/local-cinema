package org.proninyaroslav.libretorrent.core.model.stream;

import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import org.proninyaroslav.libretorrent.core.model.session.TorrentDownload;

public final class TorrentByteRangeInputStream extends InputStream {
    private final TorrentDownload task;
    private final TorrentStream stream;
    private final RandomAccessFile file;
    private final long limit;
    private long position;
    private int prioritizedPiece = -1;
    private volatile boolean closed;

    public TorrentByteRangeInputStream(TorrentDownload task, TorrentStream stream, long start, long length)
            throws IOException {
        this.task = task;
        this.stream = stream;
        position = start;
        limit = start + length;
        file = new RandomAccessFile(task.getStreamFile(stream.selectedFileIndex), "r");
        try {
            file.seek(start);
        } catch (IOException error) {
            file.close();
            throw error;
        }
    }

    @Override public int read() throws IOException {
        var one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (position >= limit) return -1;
        long absolute = stream.fileOffset + position;
        int piece = (int) (absolute / stream.pieceLength);
        if (piece != prioritizedPiece) {
            task.setInterestedPieces(stream, piece, 1);
            prioritizedPiece = piece;
        }
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!closed && task.isValid() && !task.havePiece(piece)) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Torrent piece timed out");
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Torrent stream interrupted");
            }
        }
        if (closed || !task.isValid()) throw new IOException("Torrent stream closed");
        int wanted = (int) Math.min(Math.min(limit - position, length),
                stream.pieceLength - absolute % stream.pieceLength);
        int count = file.read(bytes, offset, wanted);
        if (count < 0) throw new IOException("Verified piece could not be read");
        position += count;
        return count;
    }

    @Override public void close() throws IOException {
        closed = true;
        file.close();
    }
}
