package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.archos.filecorelibrary.FileUtils;
import com.archos.filecorelibrary.MetaFile2;
import com.archos.filecorelibrary.MetaFile2Factory;
import com.archos.filecorelibrary.samba.NetworkCredentialsDatabase;
import com.archos.filecorelibrary.samba.NetworkCredentialsDatabase.Credential;
import com.archos.filecorelibrary.samba.SambaDiscovery;
import com.archos.filecorelibrary.samba.Share;
import com.archos.filecorelibrary.samba.Workgroup;
import com.archos.mediacenter.filecoreextension.UriUtils;
import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.filecoreextension.upnp2.UpnpServiceManager;
import com.archos.mediacenter.utils.ShortcutDbAdapter;
import com.archos.mediacenter.video.browser.ShortcutDb;
import com.archos.mediacenter.video.leanback.adapter.GenericNetworkShortcutMapper;
import com.archos.mediacenter.video.leanback.adapter.NetworkShortcutMapper;
import com.archos.mediacenter.video.leanback.adapter.object.GenericNetworkShortcut;
import com.archos.mediacenter.video.leanback.adapter.object.NetworkShortcut;
import com.archos.mediaprovider.ArchosMediaIntent;
import com.archos.mediaprovider.NetworkScanner;
import com.archos.mediaprovider.video.LoaderUtils;
import com.archos.mediaprovider.video.NetworkAutoRefresh;
import com.archos.mediaprovider.video.NetworkScannerServiceVideo;

import org.jupnp.model.meta.Device;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Isolated Network Discovery, Credentials & Indexed Library Sources Controller
 * for Local Cinema TV ({@code Engine v2} Network Readiness).
 *
 * <p>Decouples UI from legacy Nova {@code NetworkRootFragment}, {@code SmbSharePresenter},
 * {@code NetworkShortcutDetailsFragment}, and {@code RescanFragment} so SMB/UPnP/WebDAV/NFS
 * transports can be upgraded independently.
 */
public final class CinemaNetworkController implements NetworkScannerServiceVideo.ScannerListener {
    private static final long SMB_DISCOVERY_REPEAT_MS = 3500L;

    public interface Listener {
        void onIndexedFoldersUpdated(List<SourceEntry> indexed);
        void onSmbSharesUpdated(List<SourceEntry> smbShares);
        void onUpnpServersUpdated(List<SourceEntry> upnpServers);
        void onSavedShortcutsUpdated(List<SourceEntry> shortcuts);
        void onScannerStateChanged(boolean scanning);
    }

    public enum Kind { INDEXED_FOLDER, SMB_SHARE, UPNP_SERVER, SAVED_SERVER }

    public record SourceEntry(
            long id,
            Kind kind,
            String name,
            Uri uri,
            String protocolBadge,
            String hostOrPathSubtitle,
            boolean indexed) {}

    public record DirectoryListingResult(
            List<CinemaFileOperations.Item> items,
            boolean authRequired,
            String errorMessage) {}

    public record ServerSpec(
            int protocolIndex, // UriUtils index: 0=ftp, 1=sftp, 2=ftps, 3=sshj, 4=smb, 5=smbj, 6=webdav, 7=webdavs
            String host,
            int port,
            String path,
            String username,
            String password,
            String domain,
            boolean savePassword) {}

    private final Activity activity;
    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private SambaDiscovery sambaDiscovery;
    private boolean started;
    private final Map<String, SourceEntry> liveSmbMap = new LinkedHashMap<>();
    private List<SourceEntry> currentUpnp = List.of();
    private List<SourceEntry> currentIndexed = List.of();

    private final BroadcastReceiver libraryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            reloadShortcutsAndIndexed();
            listener.onScannerStateChanged(NetworkScannerServiceVideo.isScannerAlive());
        }
    };

    private final Runnable smbRepeatRunnable = new Runnable() {
        @Override public void run() {
            if (!started || activity.isFinishing() || activity.isDestroyed()) return;
            startSmbCycle();
        }
    };

    private final SambaDiscovery.Listener sambaListener = new SambaDiscovery.Listener() {
        @Override public void onDiscoveryStart() {}

        @Override public void onDiscoveryEnd() {
            if (!started) return;
            mainHandler.removeCallbacks(smbRepeatRunnable);
            mainHandler.postDelayed(smbRepeatRunnable, SMB_DISCOVERY_REPEAT_MS);
        }

        @Override public void onDiscoveryUpdate(List<Workgroup> workgroups) {
            if (!started || workgroups == null) return;
            for (Workgroup wg : workgroups) {
                if (wg == null || wg.getShares() == null) continue;
                for (Share share : wg.getShares()) {
                    if (share == null || share.toUri() == null) continue;
                    Uri uri = share.toUri();
                    CinemaLibraryScan.discovered(appContext, uri);
                    String key = normalizeKey(uri);
                    String dispName = (share.getDisplayName() != null && !share.getDisplayName().isBlank())
                            ? share.getDisplayName()
                            : (uri.getHost() != null ? uri.getHost() : uri.toString());
                    String wgName = wg.getName() != null && !wg.getName().isBlank() ? wg.getName() + " · " : "";
                    String hostSub = wgName + (uri.getHost() != null ? uri.getHost() : stripCredentials(uri.toString()));
                    boolean isIdx = isUriOrChildIndexed(uri);
                    SourceEntry existing = liveSmbMap.get(key);
                    if (existing == null || (existing.name().equals(uri.getHost()) && !dispName.equals(uri.getHost()))) {
                        liveSmbMap.put(key, new SourceEntry(-1L, Kind.SMB_SHARE, dispName, uri, "SMB", hostSub, isIdx));
                    }
                }
            }
            publishSmbList();
        }

        @Override public void onDiscoveryFatalError() {}
    };

    private final UpnpServiceManager.Listener upnpListener = new UpnpServiceManager.Listener() {
        @Override public void onDeviceListUpdate(List<Device> devices) {
            if (!started || devices == null) return;
            List<SourceEntry> list = new ArrayList<>();
            for (Device d : devices) {
                try {
                    String friendly = UpnpServiceManager.getDeviceFriendlyName(d);
                    Uri uri = UpnpServiceManager.getDeviceUri(d);
                    if (uri == null) continue;
                    String name = (friendly != null && !friendly.isBlank()) ? friendly : uri.toString();
                    list.add(new SourceEntry(-1L, Kind.UPNP_SERVER, name, uri, "UPnP / DLNA",
                            stripCredentials(uri.toString()), false));
                } catch (Exception ignored) {}
            }
            list.sort(Comparator.comparing(SourceEntry::name, String.CASE_INSENSITIVE_ORDER));
            currentUpnp = list;
            listener.onUpnpServersUpdated(list);
        }
    };

    public CinemaNetworkController(Activity activity, Listener listener) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter(CinemaLibraryScan.ACTION_CHANGED);
        f.addAction(CinemaLinkServer.MOBILE_LIBRARY_CHANGED);
        f.addAction(ArchosMediaIntent.ACTION_VIDEO_SCANNER_SCAN_STARTED);
        f.addAction(ArchosMediaIntent.ACTION_VIDEO_SCANNER_SCAN_FINISHED);
        ContextCompat.registerReceiver(activity, libraryReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        NetworkScannerServiceVideo.addListener(this);

        reloadShortcutsAndIndexed();
        CinemaLibraryScan.start(appContext, null);
        startSmbCycle();
        try {
            UpnpServiceManager.startServiceIfNeeded(activity).addListener(upnpListener);
        } catch (Exception ignored) {}
    }

    public void stop() {
        if (!started) return;
        started = false;
        try { activity.unregisterReceiver(libraryReceiver); } catch (Exception ignored) {}
        NetworkScannerServiceVideo.removeListener(this);
        mainHandler.removeCallbacks(smbRepeatRunnable);
        abortSmb();
        try {
            UpnpServiceManager.getSingleton(activity).removeListener(upnpListener);
        } catch (Exception ignored) {}
    }

    public void destroy() {
        stop();
        io.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onScannerStateChanged() {
        mainHandler.post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                listener.onScannerStateChanged(NetworkScannerServiceVideo.isScannerAlive());
            }
        });
    }

    public boolean isScannerActive() {
        return NetworkScannerServiceVideo.isScannerAlive();
    }

    public void reloadShortcutsAndIndexed() {
        io.execute(() -> {
            List<SourceEntry> indexed = new ArrayList<>();
            List<SourceEntry> saved = new ArrayList<>();
            try {
                ShortcutDbAdapter.VIDEO.cleanupDuplicateShortcuts(appContext);
                try (Cursor c = ShortcutDbAdapter.VIDEO.queryAllShortcuts(appContext)) {
                    if (c != null && c.moveToFirst()) {
                        NetworkShortcutMapper mapper = new NetworkShortcutMapper();
                        mapper.bind(c);
                        while (!c.isAfterLast()) {
                            NetworkShortcut ns = (NetworkShortcut) mapper.convert(c);
                            if (ns != null && ns.getUri() != null) {
                                Uri u = ns.getUri();
                                String name = (ns.getName() != null && !ns.getName().isBlank())
                                        ? ns.getName()
                                        : FileUtils.getName(u);
                                indexed.add(new SourceEntry(
                                        ns.getId(),
                                        Kind.INDEXED_FOLDER,
                                        name,
                                        u,
                                        protocolBadgeFor(u),
                                        stripCredentials(u.toString()),
                                        true));
                            }
                            c.moveToNext();
                        }
                    }
                } finally {
                    if (ShortcutDbAdapter.VIDEO.isDbOpen()) {
                        ShortcutDbAdapter.VIDEO.close();
                    }
                }
            } catch (Exception ignored) {}

            try (Cursor c = ShortcutDb.STATIC.getCursorAllShortcuts(appContext)) {
                if (c != null && c.moveToFirst()) {
                    GenericNetworkShortcutMapper mapper = new GenericNetworkShortcutMapper();
                    mapper.bindColumns(c);
                    do {
                        Object bound = mapper.bind(c);
                        if (bound instanceof GenericNetworkShortcut gs && gs.getUri() != null) {
                            Uri u = gs.getUri();
                            String name = (gs.getName() != null && !gs.getName().isBlank())
                                    ? gs.getName()
                                    : (u.getHost() != null ? u.getHost() : u.toString());
                            boolean isIdx = ShortcutDbAdapter.VIDEO.isShortcut(appContext, u.toString()) > 0;
                            saved.add(new SourceEntry(
                                    gs.getId(),
                                    Kind.SAVED_SERVER,
                                    name,
                                    u,
                                    protocolBadgeFor(u),
                                    stripCredentials(u.toString()),
                                    isIdx));
                        }
                    } while (c.moveToNext());
                }
            } catch (Exception ignored) {}

            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                currentIndexed = indexed;
                listener.onIndexedFoldersUpdated(indexed);
                listener.onSavedShortcutsUpdated(saved);
                publishSmbList();
            });
        });
    }

    public boolean isFolderIndexed(Uri uri) {
        if (uri == null) return false;
        try {
            return ShortcutDbAdapter.VIDEO.isShortcut(appContext, uri.toString()) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void indexFolder(Uri uri, String displayName, Runnable onComplete) {
        if (uri == null) return;
        io.execute(() -> {
            try {
                if (LoaderUtils.getScrapeInProgress()) {
                    LoaderUtils.setScrapeInProgress(false);
                }
                String name = (displayName != null && !displayName.isBlank())
                        ? displayName
                        : FileUtils.getName(uri);
                if (name == null || name.isBlank()) name = uri.getHost() != null ? uri.getHost() : "Share";
                ShortcutDbAdapter.VIDEO.addShortcut(appContext,
                        new ShortcutDbAdapter.Shortcut(name, uri.toString(), stripCredentials(uri.toString())));
                NetworkScanner.scanVideos(appContext, uri);
                CinemaLibraryScan.bumpStateEpoch();
                CinemaLinkServer.notifyTvSettingsMutated(appContext);
                appContext.sendBroadcast(new Intent(CinemaLibraryScan.ACTION_CHANGED).setPackage(appContext.getPackageName()));
            } catch (Exception ignored) {}
            mainHandler.post(() -> {
                reloadShortcutsAndIndexed();
                if (onComplete != null && !activity.isFinishing() && !activity.isDestroyed()) onComplete.run();
            });
        });
    }

    public void removeIndexedFolder(long shortcutId, Uri uri, Runnable onComplete) {
        if (uri == null) return;
        io.execute(() -> {
            try {
                if (LoaderUtils.getScrapeInProgress()) {
                    LoaderUtils.setScrapeInProgress(false);
                }
                boolean deleted = shortcutId > 0
                        ? ShortcutDbAdapter.VIDEO.deleteShortcut(appContext, shortcutId)
                        : ShortcutDbAdapter.VIDEO.deleteShortcut(appContext, uri.toString());
                if (deleted) {
                    NetworkScanner.removeVideos(appContext, uri);
                }
                CinemaLibraryScan.bumpStateEpoch();
                CinemaLinkServer.notifyTvSettingsMutated(appContext);
                appContext.sendBroadcast(new Intent(CinemaLibraryScan.ACTION_CHANGED).setPackage(appContext.getPackageName()));
            } catch (Exception ignored) {}
            mainHandler.post(() -> {
                reloadShortcutsAndIndexed();
                if (onComplete != null && !activity.isFinishing() && !activity.isDestroyed()) onComplete.run();
            });
        });
    }

    public void removeSavedServer(Uri uri, Runnable onComplete) {
        if (uri == null) return;
        io.execute(() -> {
            try {
                ShortcutDb.STATIC.removeShortcut(appContext, uri);
                CinemaLinkServer.notifyTvSettingsMutated(appContext);
            } catch (Exception ignored) {}
            mainHandler.post(() -> {
                reloadShortcutsAndIndexed();
                if (onComplete != null && !activity.isFinishing() && !activity.isDestroyed()) onComplete.run();
            });
        });
    }

    public void rescanFolder(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (("smb".equals(scheme) || "smbj".equals(scheme)) && uri.getPathSegments().size() <= 1) {
            CinemaLibraryScan.start(appContext, uri);
        } else {
            NetworkScanner.scanVideos(appContext, uri);
        }
    }

    public void rescanAll() {
        CinemaHomeFragment.triggerDirectScan(activity);
    }

    /** Lists a remote SMB / UPnP / FTP / SFTP / WebDAV directory asynchronously. */
    public void listDirectory(Uri folderUri, java.util.function.Consumer<DirectoryListingResult> callback) {
        io.execute(() -> {
            List<CinemaFileOperations.Item> result = new ArrayList<>();
            boolean authNeeded = false;
            String errMsg = null;
            try {
                List<MetaFile2> raw = RawListerFactoryWithUpnp.getRawListerForUrl(folderUri).getFileList();
                if (raw != null) {
                    for (MetaFile2 f : raw) {
                        if (f == null) continue;
                        String name = f.getName();
                        if (name == null || ".".equals(name) || "..".equals(name)) continue;
                        boolean dir = f.isDirectory();
                        result.add(new CinemaFileOperations.Item(
                                name,
                                f.getUri(),
                                dir,
                                f.lastModified(),
                                dir ? 0L : f.getComputedLength()));
                    }
                }
                Comparator<CinemaFileOperations.Item> byName =
                        Comparator.comparing(CinemaFileOperations.Item::name, String.CASE_INSENSITIVE_ORDER);
                result.sort(Comparator.comparing(CinemaFileOperations.Item::directory).reversed().thenComparing(byName));
            } catch (com.archos.filecorelibrary.AuthenticationException authEx) {
                authNeeded = true;
            } catch (Exception ex) {
                String m = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
                if (m.contains("logon failure") || m.contains("access is denied")
                        || m.contains("status_logon_failure") || m.contains("status_access_denied")
                        || m.contains("authentication") || m.contains("401")) {
                    authNeeded = true;
                } else {
                    errMsg = ex.getMessage();
                }
            }
            final DirectoryListingResult outcome = new DirectoryListingResult(result, authNeeded, errMsg);
            mainHandler.post(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    callback.accept(outcome);
                }
            });
        });
    }

    public Credential getSavedCredential(Uri uri) {
        if (uri == null) return null;
        try {
            Credential exact = NetworkCredentialsDatabase.getInstance().getCredential(uri.toString());
            if (exact != null) return exact;
            if (uri.getHost() != null) {
                String root = (uri.getScheme() != null ? uri.getScheme() : "smb") + "://" + uri.getHost() + "/";
                return NetworkCredentialsDatabase.getInstance().getCredential(root);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void saveCredentialsForUri(Uri uri, String username, String password, String domain, boolean persist) {
        if (uri == null) return;
        String base = uri.toString();
        Credential cred = new Credential(
                username == null ? "" : username.trim(),
                password == null ? "" : password,
                base,
                domain == null ? "" : domain.trim(),
                true);
        if (persist) NetworkCredentialsDatabase.getInstance().saveCredential(cred);
        else NetworkCredentialsDatabase.getInstance().addCredential(cred);

        if (uri.getHost() != null) {
            String hostRoot = (uri.getScheme() != null ? uri.getScheme() : "smb") + "://" + uri.getHost() + "/";
            Credential hostCred = new Credential(
                    username == null ? "" : username.trim(),
                    password == null ? "" : password,
                    hostRoot,
                    domain == null ? "" : domain.trim(),
                    true);
            if (persist) NetworkCredentialsDatabase.getInstance().saveCredential(hostCred);
            else NetworkCredentialsDatabase.getInstance().addCredential(hostCred);
        }
        if (persist) CinemaLinkServer.notifyTvSettingsMutated(appContext);
    }

    /** Builds, stores credentials + shortcut, and returns the connectable URI for a manual server spec. */
    public Uri connectAndSaveManualServer(ServerSpec spec) {
        if (spec == null || spec.host() == null || spec.host().isBlank()) return null;
        String scheme = UriUtils.getTypeUri(spec.protocolIndex());
        String host = spec.host().trim();
        int port = spec.port() > 0 ? spec.port() : MetaFile2Factory.defaultPortForProtocol(scheme);
        String path = spec.path() == null ? "/" : spec.path().trim();
        if (path.isEmpty() || !path.startsWith("/")) path = "/" + path;

        String user = spec.username() == null ? "" : spec.username().trim();
        if (user.isEmpty() && UriUtils.emptyCredentialMeansAnonymous(scheme)) {
            user = "anonymous";
        }
        String uriStr = scheme + "://" + host + (port > 0 ? ":" + port : "") + path;
        Uri uri = Uri.parse(uriStr);

        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                .putInt("NET_LATEST_TYPE", spec.protocolIndex())
                .putString("NET_LATEST_ADDRESS", host)
                .putInt("NET_LATEST_PORT", port)
                .putString("NET_LATEST_USERNAME", user)
                .putString("NET_LATEST_DOMAIN", spec.domain() == null ? "" : spec.domain().trim())
                .putString("NET_LATEST_PATH", path)
                .apply();

        saveCredentialsForUri(uri, user, spec.password(), spec.domain(), spec.savePassword());
        String shortcutName = FileUtils.getName(uri);
        if (shortcutName == null || shortcutName.isBlank()) shortcutName = host;
        final String finalName = shortcutName;
        io.execute(() -> {
            try {
                ShortcutDb.STATIC.insertShortcut(appContext, uri, finalName, stripCredentials(uriStr));
                CinemaLinkServer.notifyTvSettingsMutated(appContext);
            } catch (Exception ignored) {}
            mainHandler.post(this::reloadShortcutsAndIndexed);
        });
        return uri;
    }

    public boolean isRescanOnOpenEnabled() {
        return NetworkAutoRefresh.autoRescanAtStart(appContext);
    }

    public void setRescanOnOpenEnabled(boolean enabled) {
        NetworkAutoRefresh.setAutoRescanAtStart(appContext, enabled);
    }

    public int getAutoRescanStartingHour() {
        return NetworkAutoRefresh.getRescanPeriod(appContext);
    }

    private void startSmbCycle() {
        abortSmb();
        try {
            sambaDiscovery = new SambaDiscovery(activity);
            sambaDiscovery.setMinimumUpdatePeriodInMs(150);
            sambaDiscovery.addListener(sambaListener);
            sambaDiscovery.start();
        } catch (Exception ignored) {}
    }

    private void abortSmb() {
        if (sambaDiscovery != null) {
            try {
                sambaDiscovery.abort();
                sambaDiscovery.removeListener(sambaListener);
            } catch (Exception ignored) {}
            sambaDiscovery = null;
        }
    }

    private void publishSmbList() {
        List<SourceEntry> out = new ArrayList<>(liveSmbMap.size());
        for (SourceEntry e : liveSmbMap.values()) {
            boolean idx = isUriOrChildIndexed(e.uri());
            out.add(idx == e.indexed() ? e : new SourceEntry(
                    e.id(), e.kind(), e.name(), e.uri(), e.protocolBadge(), e.hostOrPathSubtitle(), idx));
        }
        out.sort(Comparator.comparing(SourceEntry::name, String.CASE_INSENSITIVE_ORDER));
        listener.onSmbSharesUpdated(out);
    }

    private boolean isUriOrChildIndexed(Uri shareUri) {
        if (shareUri == null) return false;
        String prefix = normalizeKey(shareUri);
        for (SourceEntry idx : currentIndexed) {
            if (idx.uri() != null && normalizeKey(idx.uri()).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(Uri u) {
        String s = u.toString().toLowerCase(Locale.ROOT);
        while (s.endsWith("/") && s.length() > 8) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static String stripCredentials(String rawUri) {
        if (rawUri == null) return "";
        return Uri.decode(rawUri).replaceFirst("://[^/@]+@", "://");
    }

    public static String protocolBadgeFor(Uri uri) {
        if (uri == null || uri.getScheme() == null) return "LOCAL";
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "smb", "smbj" -> "SMB";
            case "upnp" -> "UPnP";
            case "webdav", "webdavs" -> uri.getPort() == 8080 ? "PHONE" : "WebDAV";
            case "ftp", "ftps" -> "FTP";
            case "sftp", "sshj" -> "SFTP";
            case "file" -> {
                String p = uri.getPath() == null ? "" : uri.getPath();
                yield (p.startsWith("/storage/") && !p.startsWith("/storage/emulated")) ? "USB" : "LOCAL";
            }
            default -> scheme.toUpperCase(Locale.ROOT);
        };
    }
}
