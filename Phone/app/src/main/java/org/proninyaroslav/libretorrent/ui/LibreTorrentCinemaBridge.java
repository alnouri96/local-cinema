package org.proninyaroslav.libretorrent.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.MimeTypeMap;

import org.proninyaroslav.libretorrent.MainActivity;
import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.cinema_hub.CinemaBrowser;
import org.proninyaroslav.libretorrent.cinema_hub.PhoneModuleBridges;
import org.proninyaroslav.libretorrent.cinema_hub.TvHubActivity;
import org.proninyaroslav.libretorrent.core.model.TorrentEngine;
import org.proninyaroslav.libretorrent.core.model.data.metainfo.BencodeFileItem;
import org.proninyaroslav.libretorrent.core.model.data.metainfo.TorrentMetaInfo;
import org.proninyaroslav.libretorrent.core.model.stream.TorrentStream;
import org.proninyaroslav.libretorrent.ui.addtorrent.AddTorrentMutableParams;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single explicit v1 adapter wiring the LibreTorrent module (MainActivity + TorrentEngine)
 * into {@link PhoneModuleBridges} so Browser and TV Hub stay 100% decoupled from LibreTorrent.
 */
public final class LibreTorrentCinemaBridge {
    private static final Set<String> EXTRA_VIDEO_EXTENSIONS = Set.of(
            "mkv", "m2ts", "ts", "avi", "wmv", "rmvb", "vob");

    private LibreTorrentCinemaBridge() {}

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        CinemaBrowser.registerModulePort();
        TvHubActivity.registerModulePort();
        org.proninyaroslav.libretorrent.cinema_hub.LocalCinemaBackupManager.initAutoSync(app);

        PhoneModuleBridges.registerHostShell(new PhoneModuleBridges.HostShellPort() {
            @Override
            public void openHostTab(Context ctx, PhoneModuleBridges.HostTab tab, String optionalUrl) {
                if (ctx == null) return;
                if (tab == PhoneModuleBridges.HostTab.TV_HUB) {
                    TvHubActivity.open(ctx);
                    return;
                }
                int tabId = switch (tab) {
                    case DOWNLOADS -> R.id.home_nav;
                    case BROWSER -> R.id.cinema_browser_nav;
                    case SETTINGS -> R.id.settings_nav;
                    case TV_HUB -> R.id.cinema_hub_nav;
                };
                Intent intent = new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(MainActivity.EXTRA_SELECT_TAB, tabId);
                if (optionalUrl != null && !optionalUrl.isBlank()) {
                    intent.putExtra("open_url", optionalUrl);
                }
                if (!(ctx instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(intent);
                if (ctx instanceof Activity act) {
                    act.overridePendingTransition(0, 0);
                }
            }

            @Override
            public void openHostTabById(Context ctx, int navTabId) {
                if (ctx == null || navTabId == 0) return;
                Intent intent = new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(MainActivity.EXTRA_SELECT_TAB, navTabId);
                if (!(ctx instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(intent);
                if (ctx instanceof Activity act) {
                    act.overridePendingTransition(0, 0);
                }
            }

            @Override
            public boolean isHostShellActivity(Activity activity) {
                return activity instanceof MainActivity;
            }

            @Override
            public int resolveReturnTabForContext(Context ctx) {
                return ctx instanceof MainActivity ? R.id.home_nav : 0;
            }

            @Override
            public int browserReturnTabId() {
                return R.id.cinema_browser_nav;
            }

            @Override
            public void applyWindowTransitions(Activity activity) {
                if (activity != null) {
                    MainActivity.applyTabWindowTransitions(activity);
                }
            }
        });

        PhoneModuleBridges.registerTorrentCast(new PhoneModuleBridges.TorrentCastPort() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void ensureStarted() {
                TorrentEngine engine = TorrentEngine.getInstance(app);
                if (!engine.isRunning()) {
                    engine.start();
                }
            }

            @Override
            public PhoneModuleBridges.PreparedAddonTorrent openAddonTorrent(String magnetUri, int fileIndex) throws Exception {
                TorrentEngine engine = TorrentEngine.getInstance(app);
                if (!engine.isRunning()) engine.start();
                long until = SystemClock.elapsedRealtime() + 6000L;
                while (!engine.isRunning() && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(100L);
                }
                TorrentEngine.AddonTorrent prepared = engine.openAddonTorrent(magnetUri, fileIndex);
                if (prepared == null) return null;
                return new PhoneModuleBridges.PreparedAddonTorrent(
                        prepared.id(), prepared.fileIndex(), prepared.temporary());
            }

            @Override
            public int prepareBufferPercent(String torrentId, int fileIndex) {
                TorrentEngine engine = TorrentEngine.getInstance(app);
                TorrentStream stream = engine.getStream(torrentId, fileIndex);
                if (stream == null || stream.fileSize <= 0) return -1;
                return engine.prepareCastStream(stream);
            }

            @Override
            public PhoneModuleBridges.ByteRangeStreamSource resolveReadyStream(String torrentId, int fileIndex, boolean temporary) {
                TorrentEngine engine = TorrentEngine.getInstance(app);
                TorrentStream stream = engine.getStream(torrentId, fileIndex);
                if (stream == null || stream.fileSize <= 0) return null;
                return new PhoneModuleBridges.ByteRangeStreamSource() {
                    private boolean tempActive = temporary;

                    @Override
                    public String streamId() {
                        return stream.torrentId;
                    }

                    @Override
                    public int fileIndex() {
                        return stream.selectedFileIndex;
                    }

                    @Override
                    public long fileSize() {
                        return stream.fileSize;
                    }

                    @Override
                    public InputStream openRange(long start, long length) throws IOException {
                        return engine.openCastStream(stream, start, length);
                    }

                    @Override
                    public synchronized void releaseIfTemporary() {
                        if (tempActive) {
                            tempActive = false;
                            engine.deleteTemporaryCast(stream.torrentId);
                        }
                    }
                };
            }

            @Override
            public void deleteTemporaryCast(String torrentId) {
                if (torrentId == null || torrentId.isEmpty()) return;
                TorrentEngine.getInstance(app).deleteTemporaryCast(torrentId);
            }

            @Override
            public void purgeOrphanedTemporaryCastsAsync() {
                TorrentEngine.getInstance(app).purgeOrphanedTemporaryCastsAsync();
            }

            @Override
            public String parseTorrentFileToMagnet(File torrentFile) {
                if (torrentFile == null || !torrentFile.isFile() || torrentFile.length() <= 32) return "";
                try (FileInputStream fis = new FileInputStream(torrentFile)) {
                    TorrentMetaInfo meta = new TorrentMetaInfo(fis);
                    if (!meta.sha1Hash.isBlank()) {
                        StringBuilder sb = new StringBuilder("magnet:?xt=urn:btih:").append(meta.sha1Hash);
                        if (!meta.torrentName.isBlank()) {
                            sb.append("&dn=").append(Uri.encode(meta.torrentName));
                        }
                        sb.append("&tr=").append(Uri.encode("udp://tracker.opentrackr.org:1337/announce"))
                                .append("&tr=").append(Uri.encode("udp://open.stealth.si:80/announce"))
                                .append("&tr=").append(Uri.encode("udp://tracker.torrent.eu.org:451/announce"))
                                .append("&tr=").append(Uri.encode("udp://exodus.desync.com:6969/announce"))
                                .append("&tr=").append(Uri.encode("udp://open.demonii.com:1337/announce"));
                        return sb.toString();
                    }
                } catch (Throwable ignored) {}
                return "";
            }
        });
    }

    public static void applyVideoSequentialDefaults(AddTorrentMutableParams params, List<BencodeFileItem> files) {
        if (params == null || files == null) return;
        for (BencodeFileItem file : files) {
            String path = file.getPath();
            int dot = path.lastIndexOf('.');
            if (dot < 0) continue;
            String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (EXTRA_VIDEO_EXTENSIONS.contains(extension) || (mime != null && mime.startsWith("video/"))) {
                params.setSequentialDownload(true);
                params.setFirstLastPiecePriority(true);
                return;
            }
        }
    }
}
