package org.proninyaroslav.libretorrent.cinema_hub;

import android.webkit.MimeTypeMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.proninyaroslav.libretorrent.core.model.data.metainfo.BencodeFileItem;
import org.proninyaroslav.libretorrent.ui.addtorrent.AddTorrentMutableParams;

public final class VideoTorrentDefaults {
    private static final Set<String> EXTRA_VIDEO_EXTENSIONS = Set.of(
            "mkv", "m2ts", "ts", "avi", "wmv", "rmvb", "vob");

    private VideoTorrentDefaults() {}

    public static void apply(AddTorrentMutableParams params, List<BencodeFileItem> files) {
        for (var file : files) {
            String path = file.getPath();
            int dot = path.lastIndexOf('.');
            if (dot < 0) continue;
            String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (EXTRA_VIDEO_EXTENSIONS.contains(extension) || mime != null && mime.startsWith("video/")) {
                params.setSequentialDownload(true);
                params.setFirstLastPiecePriority(true);
                return;
            }
        }
    }
}
