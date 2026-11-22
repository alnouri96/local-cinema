/*
 * Copyright (C) 2019-2025 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.core.model;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;

import org.apache.commons.io.filefilter.FileFilterUtils;
import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.RepositoryHelper;
import org.proninyaroslav.libretorrent.core.TorrentFileObserver;
import org.proninyaroslav.libretorrent.core.exception.DecodeException;
import org.proninyaroslav.libretorrent.core.exception.FreeSpaceException;
import org.proninyaroslav.libretorrent.core.exception.TorrentAlreadyExistsException;
import org.proninyaroslav.libretorrent.core.exception.UnknownUriException;
import org.proninyaroslav.libretorrent.core.logger.LogEntry;
import org.proninyaroslav.libretorrent.core.logger.Logger;
import org.proninyaroslav.libretorrent.core.model.data.AdvancedTorrentInfo;
import org.proninyaroslav.libretorrent.core.model.data.MagnetInfo;
import org.proninyaroslav.libretorrent.core.model.data.PeerInfo;
import org.proninyaroslav.libretorrent.core.model.data.Priority;
import org.proninyaroslav.libretorrent.core.model.data.TorrentInfo;
import org.proninyaroslav.libretorrent.core.model.data.TrackerInfo;
import org.proninyaroslav.libretorrent.core.model.data.entity.TagInfo;
import org.proninyaroslav.libretorrent.core.model.data.entity.Torrent;
import org.proninyaroslav.libretorrent.core.model.data.metainfo.TorrentMetaInfo;
import org.proninyaroslav.libretorrent.core.model.session.TorrentDownload;
import org.proninyaroslav.libretorrent.core.model.session.TorrentSession;
import org.proninyaroslav.libretorrent.core.model.session.TorrentSessionImpl;
import org.proninyaroslav.libretorrent.core.model.stream.TorrentInputStream;
import org.proninyaroslav.libretorrent.core.model.stream.TorrentStream;
import org.proninyaroslav.libretorrent.core.model.stream.TorrentStreamServer;
import org.proninyaroslav.libretorrent.core.settings.SessionSettings;
import org.proninyaroslav.libretorrent.core.settings.SettingsRepository;
import org.proninyaroslav.libretorrent.core.storage.TagRepository;
import org.proninyaroslav.libretorrent.core.storage.TorrentRepository;
import org.proninyaroslav.libretorrent.core.system.FileDescriptorWrapper;
import org.proninyaroslav.libretorrent.core.system.FileSystemFacade;
import org.proninyaroslav.libretorrent.core.system.SystemFacadeHelper;
import org.proninyaroslav.libretorrent.core.utils.Utils;
import org.proninyaroslav.libretorrent.receiver.ConnectionReceiver;
import org.proninyaroslav.libretorrent.receiver.PowerReceiver;
import org.proninyaroslav.libretorrent.service.TorrentService;
import org.proninyaroslav.libretorrent.ui.TorrentNotifier;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleEmitter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class TorrentEngine {
    private static final String TAG = TorrentEngine.class.getSimpleName();

    private final Context appContext;
    private final TorrentSession session;
    private TorrentStreamServer torrentStreamServer;
    private final TorrentRepository repo;
    private final TagRepository tagRepo;
    private final SettingsRepository pref;
    private final TorrentNotifier notifier;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private TorrentFileObserver fileObserver;
    private final PowerReceiver powerReceiver = new PowerReceiver();
    private final ConnectionReceiver connectionReceiver = new ConnectionReceiver();
    private final FileSystemFacade fs;
    private final DownloadsCompletedListener downloadsCompleted;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final SessionErrorFilter errorFilter = new SessionErrorFilter();

    private static volatile TorrentEngine INSTANCE;

    public static TorrentEngine getInstance(@NonNull Context appContext) {
        if (INSTANCE == null) {
            synchronized (TorrentEngine.class) {
                if (INSTANCE == null)
                    INSTANCE = new TorrentEngine(appContext);
            }
        }

        return INSTANCE;
    }

    private TorrentEngine(@NonNull Context appContext) {
        this.appContext = appContext;
        repo = RepositoryHelper.getTorrentRepository(appContext);
        tagRepo = RepositoryHelper.getTagRepository(appContext);
        fs = SystemFacadeHelper.getFileSystemFacade(appContext);
        pref = RepositoryHelper.getSettingsRepository(appContext);
        notifier = TorrentNotifier.getInstance(appContext);
        downloadsCompleted = new DownloadsCompletedListener(this);
        session = new TorrentSessionImpl(repo,
                fs,
                SystemFacadeHelper.getSystemFacade(appContext));
        session.setSettings(pref.readSessionSettings(), false);
        session.addListener(engineListener);
    }

    private void handleAutoStop() {
        if (pref.shutdownDownloadsComplete())
            forceStop();
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean serviceRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean stopping;

    public void start() {
        if (!isRunning()) exec.execute(this::doStart);
    }

    public void startIfPending() {
        exec.execute(() -> {
            if (!org.proninyaroslav.libretorrent.cinema_hub.TvHubActivity.isTempTorrentCastActive()) {
                purgeOrphanedTemporaryCasts();
            }
            if (isRunning()) {
                updateDownloadService();
                return;
            }
            var sp = appContext.getSharedPreferences(PREF_TEMP_CASTS, Context.MODE_PRIVATE);
            var marked = new java.util.HashSet<>(sp.getStringSet(KEY_TEMP_IDS, java.util.Collections.emptySet()));
            boolean pending = repo.getAllTorrents().stream().anyMatch(torrent ->
                    torrent != null
                            && !isTemporaryCastTorrent(torrent)
                            && !marked.contains(torrent.id.toLowerCase(java.util.Locale.ROOT))
                            && !torrent.manuallyPaused
                            && torrent.error == null
                            && (torrent.isDownloadingMetadata() || torrent.visibility == Torrent.VISIBILITY_VISIBLE_NOTIFY_FINISHED));
            if (pending) doStart();
        });
    }

    public void purgeOrphanedTemporaryCastsAsync() {
        exec.execute(() -> {
            if (!org.proninyaroslav.libretorrent.cinema_hub.TvHubActivity.isTempTorrentCastActive()) {
                purgeOrphanedTemporaryCasts();
            }
        });
    }

    public boolean hasActiveDownloadWork() {
        return !stopping && isRunning() && (session.hasActiveDownloads() || session.isFetchingMetadata());
    }

    public void downloadServiceStopped() {
        serviceRequested.set(false);
        updateDownloadService();
    }

    private void updateDownloadService() {
        if (!hasActiveDownloadWork() || !serviceRequested.compareAndSet(false, true)) return;
        main.post(() -> {
            if (!hasActiveDownloadWork()) {
                serviceRequested.set(false);
                return;
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(appContext, new Intent(appContext, TorrentService.class));
            } catch (IllegalStateException unavailable) {
                serviceRequested.set(false);
            }
        });
    }

    public void restartForegroundNotification() {
        if (!serviceRequested.get()) return;
        Intent i = new Intent(appContext, TorrentService.class);
        i.setAction(TorrentService.ACTION_RESTART_FOREGROUND_NOTIFICATION);
        Utils.startServiceBackground(appContext, i);
    }

    public Flowable<Boolean> observeNeedStartEngine() {
        return Flowable.create((emitter) -> {
            if (emitter.isCancelled())
                return;

            Runnable emitLoop = () -> {
                while (!Thread.interrupted()) {
                    try {
                        Thread.sleep(1000);

                    } catch (InterruptedException e) {
                        return;
                    }

                    if (emitter.isCancelled() || isRunning())
                        return;

                    emitter.onNext(true);
                }
            };

            Disposable d = observeEngineRunning()
                    .subscribeOn(Schedulers.io())
                    .subscribe((isRunning) -> {
                        if (emitter.isCancelled())
                            return;

                        if (!isRunning) {
                            emitter.onNext(true);
                            exec.submit(emitLoop);
                        }
                    });

            if (!emitter.isCancelled()) {
                emitter.onNext(!isRunning());
                emitter.setDisposable(d);
            }

        }, BackpressureStrategy.LATEST);
    }

    public Flowable<Boolean> observeEngineRunning() {
        return Flowable.create((emitter) -> {
            if (emitter.isCancelled())
                return;

            TorrentEngineListener listener = new TorrentEngineListener() {
                @Override
                public void onSessionStarted() {
                    if (!emitter.isCancelled())
                        emitter.onNext(true);
                }

                @Override
                public void onSessionStopped() {
                    if (!emitter.isCancelled())
                        emitter.onNext(false);
                }
            };

            if (!emitter.isCancelled()) {
                emitter.onNext(isRunning());
                addListener(listener);
                emitter.setDisposable(Disposable.fromAction(() -> removeListener(listener)));
            }

        }, BackpressureStrategy.LATEST);
    }

    private synchronized void doStart() {
        if (isRunning())
            return;

        stopping = false;
        switchConnectionReceiver();
        switchPowerReceiver();
        disposables.add(pref.observeSettingsChanged()
                .subscribe(this::handleSettingsChanged));

        disposables.add(downloadsCompleted.listen()
                .subscribe(
                        this::handleAutoStop,
                        (err) -> {
                            Log.e(TAG, "Auto stop error: " +
                                    Log.getStackTraceString(err));
                            handleAutoStop();
                        }
                ));

        disposables.add(session.getLogger().observeDataSetChanged()
                .subscribe((change) -> {
                    if (change.reason() == Logger.DataSetChange.Reason.NEW_ENTRIES && change.entries() != null)
                        printSessionLog(change.entries());
                }));

        session.start();
    }

    private void printSessionLog(List<LogEntry> entries) {
        for (LogEntry entry : entries) {
            if (entry == null)
                continue;

            Log.i(TAG, entry.toString());
        }
    }

    /*
     * Leaves the right to the engine to decide whether to shutdown or not
     */

    public void requestStop() {
        if (pref.keepAlive())
            return;

        forceStop();
    }

    public void forceStop() {
        stopping = true;
        exec.execute(this::doStop);
    }

    /*
     * Only calls from TorrentService
     */

    public void doStop() {
        if (!isRunning())
            return;

        disposables.clear();
        stopWatchDir();
        stopStreamingServer();
        session.requestStop();
        cleanTemp();
    }

    public boolean isRunning() {
        return session.isRunning();
    }

    public void addListener(TorrentEngineListener listener) {
        session.addListener(listener);
    }

    public void removeListener(TorrentEngineListener listener) {
        session.removeListener(listener);
    }

    public void rescheduleTorrents() {
        disposables.add(Completable.fromRunnable(() -> {
                    if (!isRunning())
                        return;

                    if (checkPauseTorrents())
                        session.pauseAll();
                    else
                        session.resumeAll();

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public void addTorrents(@NonNull List<AddTorrentParams> paramsList,
                            boolean removeFile) {
        if (!isRunning())
            return;

        disposables.add(Observable.fromIterable(paramsList)
                .subscribeOn(Schedulers.io())
                .subscribe((params) -> {
                    try {
                        session.addTorrent(params, removeFile);

                    } catch (Exception e) {
                        handleAddTorrentError(params.name, e);
                    }
                }));
    }

    public void addTorrent(@NonNull Uri file) {
        addTorrent(file, null);
    }

    public void addTorrent(@NonNull Uri file, @Nullable Uri savePath) {
        disposables.add(addTorrentCompletable(file, savePath)
                .subscribeOn(Schedulers.io())
                .subscribe()
        );
    }

    public Completable addTorrentCompletable(@NonNull Uri file) {
        return addTorrentCompletable(file, null);
    }

    public Completable addTorrentCompletable(@NonNull Uri file, @Nullable Uri savePath) {
        return Completable.fromRunnable(() -> {
            if (!isRunning())
                return;

            TorrentMetaInfo info = null;
            try (FileDescriptorWrapper w = fs.getFD(file)) {
                FileDescriptor outFd = w.open("r");

                try (FileInputStream is = new FileInputStream(outFd)) {
                    info = new TorrentMetaInfo(is);

                } catch (Exception e) {
                    throw new DecodeException(e);
                }
                addTorrentSync(file, info, savePath);

            } catch (Exception e) {
                handleAddTorrentError((info == null ? file.getPath() : info.torrentName), e);
            }
        });
    }

    /*
     * Do not run in the UI thread
     */

    public Torrent addTorrentSync(
            @NonNull AddTorrentParams params,
            boolean removeFile
    ) throws
            IOException,
            TorrentAlreadyExistsException,
            DecodeException,
            UnknownUriException {
        if (!isRunning())
            return null;

        return session.addTorrent(params, removeFile);
    }

    public Pair<MagnetInfo, Single<TorrentMetaInfo>> fetchMagnet(@NonNull String uri) throws Exception {
        if (!isRunning())
            return null;

        MagnetInfo info = session.fetchMagnet(uri);
        updateDownloadService();
        if (info == null)
            return null;
        Single<TorrentMetaInfo> res = createFetchMagnetSingle(info.getSha1hash());

        return Pair.create(info, res);
    }

    public record AddonTorrent(String id, int fileIndex, boolean temporary) {}

    /** Resolves the requested file and starts only that file in the private cast directory. */
    public AddonTorrent openAddonTorrent(String magnet, int requestedFile) throws Exception {
        var parsed = parseMagnet(magnet);
        if (parsed == null || !parsed.getSha1hash().matches("(?i)[a-f0-9]{40,64}")) {
            throw new IOException("Invalid magnet");
        }
        String id = parsed.getSha1hash();
        TorrentMetaInfo metadata = getTorrentMetaInfo(id);
        boolean existing = metadata != null && metadata.fileCount > 0;
        if (!existing) {
            var download = fetchMagnet(magnet);
            if (download == null) throw new IOException("Metadata unavailable");
            try {
                metadata = download.second.timeout(40, java.util.concurrent.TimeUnit.SECONDS).blockingGet();
            } finally {
                cancelFetchMagnet(id);
            }
        }
        int index = requestedFile < 0
                ? metadata.fileList.stream().filter(file -> {
                    String path = file.getPath();
                    String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                    return java.util.Set.of("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg", "wmv").contains(extension);
                }).max(java.util.Comparator.comparingLong(file -> file.getSize()))
                        .map(file -> file.getIndex()).orElse(-1)
                : requestedFile;
        var selected = metadata.fileList.stream().filter(file -> file.getIndex() == index)
                .findFirst().orElseThrow(() -> new IOException("Video file unavailable"));
        if (index < 0 || index >= metadata.fileCount || selected.getSize() <= 0) throw new IOException("Invalid file");
        if (existing) return new AddonTorrent(id, index, false);

        var root = appContext.getExternalFilesDir("torrent-casts");
        if (root == null) throw new IOException("Temporary storage unavailable");
        var directory = new java.io.File(root, id);
        if ((!directory.isDirectory() && !directory.mkdirs()) || directory.getUsableSpace() < selected.getSize()) {
            throw new IOException("Temporary storage unavailable");
        }
        var priorities = new org.proninyaroslav.libretorrent.core.model.data.Priority[metadata.fileCount];
        java.util.Arrays.fill(priorities, org.proninyaroslav.libretorrent.core.model.data.Priority.IGNORE);
        priorities[index] = org.proninyaroslav.libretorrent.core.model.data.Priority.DEFAULT;
        try {
            var torrent = addTorrentSync(new AddTorrentParams(magnet, true, id, metadata.torrentName,
                    priorities, Uri.fromFile(directory), true, false, java.util.List.of(), true), false);
            if (torrent == null) throw new IOException("Torrent engine unavailable");
        } catch (TorrentAlreadyExistsException alreadyAdded) {
            return new AddonTorrent(id, index, false);
        }
        markTemporaryCast(appContext, id);
        return new AddonTorrent(id, index, true);
    }

    public MagnetInfo parseMagnet(@NonNull String uri) {
        return session.parseMagnet(uri);
    }

    private Single<TorrentMetaInfo> createFetchMagnetSingle(String targetHash) {
        return Single.create((emitter) -> {
            TorrentEngineListener listener = new TorrentEngineListener() {
                @Override
                public void onMagnetLoaded(@NonNull String hash, byte[] bencode) {
                    if (!targetHash.equals(hash))
                        return;

                    if (!emitter.isDisposed()) {
                        if (bencode == null)
                            emitter.onError(new IOException(new NullPointerException("bencode is null")));
                        else
                            sendInfoToEmitter(emitter, bencode);
                    }
                }
            };
            if (!emitter.isDisposed()) {
                /* Check if metadata is already loaded */
                byte[] bencode = session.getLoadedMagnet(targetHash);
                if (bencode == null) {
                    session.addListener(listener);
                    emitter.setDisposable(Disposable.fromAction(() ->
                            session.removeListener(listener)));
                } else {
                    sendInfoToEmitter(emitter, bencode);
                }
            }
        });
    }

    private void sendInfoToEmitter(SingleEmitter<TorrentMetaInfo> emitter, byte[] bencode) {
        TorrentMetaInfo info;
        try {
            info = new TorrentMetaInfo(bencode);

        } catch (DecodeException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            if (!emitter.isDisposed())
                emitter.onError(e);
            return;
        }

        if (!emitter.isDisposed())
            emitter.onSuccess(info);
    }

    /*
     * Used only for magnets from the magnetList (non added magnets)
     */

    public void cancelFetchMagnet(@NonNull String infoHash) {
        if (!isRunning())
            return;

        session.cancelFetchMagnet(infoHash);
    }

    public void pauseResumeTorrent(@NonNull String id) {
        disposables.add(Completable.fromRunnable(() -> {
                    TorrentDownload task = session.getTask(id);
                    if (task == null) {
                        Torrent torrent = repo.getTorrentById(id);
                        if (torrent != null) {
                            torrent.manuallyPaused = !torrent.manuallyPaused;
                            repo.updateTorrent(torrent);
                            if (!torrent.manuallyPaused) start();
                        }
                        return;
                    }
                    try {
                        if (task.isPaused())
                            task.resumeManually();
                        else
                            task.pauseManually();

                    } catch (Exception e) {
                        /* Ignore */
                    }

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public void forceRecheckTorrents(@NonNull List<String> ids) {
        disposables.add(Observable.fromIterable(ids)
                .filter(Objects::nonNull)
                .subscribe((id) -> {
                    if (!isRunning())
                        return;

                    TorrentDownload task = session.getTask(id);
                    if (task != null)
                        task.forceRecheck();
                }));
    }

    public void forceAnnounceTorrents(@NonNull List<String> ids) {
        disposables.add(Observable.fromIterable(ids)
                .filter(Objects::nonNull)
                .subscribe((id) -> {
                    if (!isRunning())
                        return;

                    TorrentDownload task = session.getTask(id);
                    if (task != null)
                        task.requestTrackerAnnounce();
                }));
    }

    public void deleteTorrents(@NonNull List<String> ids, boolean withFiles) {
        disposables.add(Observable.fromIterable(ids)
                .observeOn(Schedulers.io())
                .subscribe((id) -> {
                    if (!isRunning()) doStart();
                    session.deleteTorrent(id, withFiles);
                }));
    }

    public void deleteTrackers(@NonNull String id, @NonNull List<String> urls) {
        if (!isRunning())
            return;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return;

        Set<String> trackers = task.getTrackersUrl();
        urls.forEach(trackers::remove);

        task.replaceTrackers(trackers);
    }

    public void replaceTrackers(@NonNull String id, @NonNull List<String> urls) {
        if (!isRunning())
            return;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return;

        task.replaceTrackers(new HashSet<>(urls));
    }

    public void addTrackers(@NonNull String id, @NonNull List<String> urls) {
        if (!isRunning())
            return;

        TorrentDownload task = session.getTask(id);
        if (task != null)
            task.addTrackers(new HashSet<>(urls));
    }

    public String makeMagnet(@NonNull String id, boolean includePriorities) {
        if (!isRunning())
            return null;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return null;

        return task.makeMagnet(includePriorities);
    }

    public Flowable<TorrentMetaInfo> observeTorrentMetaInfo(@NonNull String id) {
        return Flowable.create((emitter) -> {
            TorrentEngineListener listener = new TorrentEngineListener() {
                @Override
                public void onTorrentMetadataLoaded(@NonNull String torrentId, Exception err) {
                    if (!id.equals(torrentId) || emitter.isCancelled())
                        return;

                    if (err == null) {
                        TorrentMetaInfo info = getTorrentMetaInfo(id);
                        if (info == null)
                            emitter.onError(new NullPointerException());
                        else
                            emitter.onNext(info);
                    } else {
                        emitter.onError(err);
                    }
                }
            };
            if (!emitter.isCancelled()) {
                TorrentMetaInfo info = getTorrentMetaInfo(id);
                if (info == null)
                    emitter.onError(new NullPointerException());
                else
                    emitter.onNext(info);

                session.addListener(listener);
                emitter.setDisposable(Disposable.fromAction(() ->
                        session.removeListener(listener)));
            }
        }, BackpressureStrategy.LATEST);
    }

    public TorrentMetaInfo getTorrentMetaInfo(@NonNull String id) {
        if (!isRunning())
            return null;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return null;

        TorrentMetaInfo info = null;
        try {
            info = task.getTorrentMetaInfo();

        } catch (DecodeException e) {
            Log.e(TAG, "Can't decode torrent info: ");
            Log.e(TAG, Log.getStackTraceString(e));
        }

        return info;
    }

    public boolean[] getPieces(@NonNull String id) {
        if (!isRunning())
            return new boolean[0];

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return new boolean[0];

        return task.pieces();
    }

    public void pauseAll() {
        disposables.add(Completable.fromRunnable(() -> {
                    if (isRunning())
                        session.pauseAllManually();

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public void resumeAll() {
        disposables.add(Completable.fromRunnable(() -> {
                    if (isRunning())
                        session.resumeAllManually();
                    else {
                        for (Torrent torrent : repo.getAllTorrents()) {
                            if (torrent.manuallyPaused) {
                                torrent.manuallyPaused = false;
                                repo.updateTorrent(torrent);
                            }
                        }
                        startIfPending();
                    }

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public void setTorrentName(@NonNull String id, @NonNull String name) {
        disposables.add(Completable.fromRunnable(() -> {
                    if (!isRunning())
                        return;

                    TorrentDownload task = session.getTask(id);
                    if (task != null)
                        task.setTorrentName(name);

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public void setDownloadPath(@NonNull String id, @NonNull Uri path) {
        disposables.add(Completable.fromRunnable(() -> {
                    if (!isRunning())
                        return;

                    TorrentDownload task = session.getTask(id);
                    if (task != null)
                        task.setDownloadPath(path);

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public void setSequentialDownload(@NonNull String id, boolean sequential) {
        disposables.add(Completable.fromRunnable(() -> {
                    if (!isRunning())
                        return;

                    TorrentDownload task = session.getTask(id);
                    if (task != null)
                        task.setSequentialDownload(sequential);

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public void setFirstLastPiecePriority(@NonNull String id, boolean enabled) {
        disposables.add(Completable.fromRunnable(() -> {
            if (!isRunning()) {
                return;
            }
            var task = session.getTask(id);
            if (task != null) {
                task.setFirstLastPiecePriority(enabled);
            }
        }).subscribeOn(Schedulers.io()).subscribe());
    }

    public boolean isFirstLastPiecePriority(@NonNull String id) {
        if (!isRunning()) {
            return false;
        }

        var task = session.getTask(id);
        if (task == null) {
            return false;
        }

        return task.isFirstLastPiecePriority();
    }

    public void prioritizeFiles(@NonNull String id, @NonNull Priority[] priorities) {
        disposables.add(Completable.fromRunnable(() -> {
                    if (!isRunning())
                        return;

                    TorrentDownload task = session.getTask(id);
                    if (task != null)
                        task.prioritizeFiles(priorities);

                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    public TorrentStream getStream(@NonNull String id, int fileIndex) {
        if (!isRunning())
            return null;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return null;

        return task.getStream(fileIndex);
    }

    public TorrentInputStream getTorrentInputStream(@NonNull TorrentStream stream) {
        return new TorrentInputStream(session, stream);
    }

    public int prepareCastStream(TorrentStream stream) {
        TorrentDownload task = session.getTask(stream.torrentId);
        if (task == null || !task.isValid()) return 0;
        if (task.isPaused() && !task.isFinished()) task.resumeManually();
        int total = stream.lastFilePiece - stream.firstFilePiece + 1;
        long bytes = Math.min(stream.fileSize, 3L * 1024 * 1024);
        int initial = stream.bytesToPieceIndex(stream.fileOffset % stream.pieceLength + bytes)
                - stream.firstFilePiece + 1;
        int header = Math.min(total, Math.max(initial, (int) Math.ceil(total * .025)));
        int tail = Math.max(1, (int) Math.ceil(total * .01));
        task.setInterestedPieces(stream, stream.firstFilePiece, header);
        int ready = 0;
        for (int i = 0; i < initial; i++)
            if (task.havePiece(stream.firstFilePiece + i)) ready++;
        if (ready == initial) task.setInterestedPieces(stream, stream.lastFilePiece - tail + 1, tail);
        return ready * 100 / initial;
    }

    public java.io.InputStream openCastStream(TorrentStream stream, long start, long length) throws IOException {
        TorrentDownload task = session.getTask(stream.torrentId);
        if (task == null || !task.isValid()) throw new IOException("Torrent is unavailable");
        return new org.proninyaroslav.libretorrent.core.model.stream.TorrentByteRangeInputStream(
                task, stream, start, length);
    }

    private static final String PREF_TEMP_CASTS = "cinema_temp_torrents";
    private static final String KEY_TEMP_IDS = "temp_ids";

    public static void markTemporaryCast(Context context, String id) {
        if (context == null || id == null || !id.matches("(?i)[0-9a-f]{40,64}")) return;
        var sp = context.getApplicationContext().getSharedPreferences(PREF_TEMP_CASTS, Context.MODE_PRIVATE);
        var next = new java.util.HashSet<>(sp.getStringSet(KEY_TEMP_IDS, java.util.Collections.emptySet()));
        next.add(id.toLowerCase(java.util.Locale.ROOT));
        sp.edit().putStringSet(KEY_TEMP_IDS, next).apply();
    }

    private static void unmarkTemporaryCast(Context context, String id) {
        if (context == null || id == null) return;
        var sp = context.getApplicationContext().getSharedPreferences(PREF_TEMP_CASTS, Context.MODE_PRIVATE);
        var next = new java.util.HashSet<>(sp.getStringSet(KEY_TEMP_IDS, java.util.Collections.emptySet()));
        if (next.remove(id.toLowerCase(java.util.Locale.ROOT))) {
            sp.edit().putStringSet(KEY_TEMP_IDS, next).apply();
        }
    }

    private boolean isTemporaryCastTorrent(Torrent torrent) {
        if (torrent == null) return false;
        try {
            var root = appContext.getExternalFilesDir("torrent-casts");
            if (root == null) return false;
            var expected = new java.io.File(root, torrent.id).getCanonicalFile();
            var actual = new java.io.File(fs.makeFileSystemPath(torrent.downloadPath)).getCanonicalFile();
            return expected.equals(actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    public void deleteTemporaryCast(String id) {
        if (id == null || !id.matches("(?i)[0-9a-f]{40,64}")) return;
        unmarkTemporaryCast(appContext, id);
        exec.execute(() -> {
            var torrent = repo.getTorrentById(id);
            var root = appContext.getExternalFilesDir("torrent-casts");
            if (torrent != null && root != null) {
                try {
                    var expected = new java.io.File(root, id).getCanonicalFile();
                    var actual = new java.io.File(fs.makeFileSystemPath(torrent.downloadPath)).getCanonicalFile();
                    if (expected.equals(actual)) deleteTorrents(java.util.List.of(id), true);
                } catch (IOException | UnknownUriException ignored) {}
            }
            if (root != null) {
                deleteDirRecursive(new java.io.File(root, id));
            }
        });
    }

    private void purgeOrphanedTemporaryCasts() {
        try {
            var root = appContext.getExternalFilesDir("torrent-casts");
            var sp = appContext.getSharedPreferences(PREF_TEMP_CASTS, Context.MODE_PRIVATE);
            var marked = new java.util.HashSet<>(sp.getStringSet(KEY_TEMP_IDS, java.util.Collections.emptySet()));
            java.util.List<String> toDelete = new java.util.ArrayList<>();
            for (Torrent t : repo.getAllTorrents()) {
                if (t == null) continue;
                if (isTemporaryCastTorrent(t) || marked.contains(t.id.toLowerCase(java.util.Locale.ROOT))) {
                    toDelete.add(t.id);
                }
            }
            if (!toDelete.isEmpty()) {
                deleteTorrents(toDelete, true);
            }
            if (root != null && root.isDirectory()) {
                var children = root.listFiles();
                if (children != null) {
                    for (var c : children) deleteDirRecursive(c);
                }
            }
            sp.edit().remove(KEY_TEMP_IDS).apply();
        } catch (Exception ignored) {}
    }

    private static void deleteDirRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            var list = f.listFiles();
            if (list != null) {
                for (var child : list) deleteDirRecursive(child);
            }
        }
        try { f.delete(); } catch (Exception ignored) {}
    }

    /*
     * Do not run in the UI thread
     */

    public TorrentInfo makeInfoSync(@NonNull String id) {
        Torrent torrent = repo.getTorrentById(id);
        if (torrent == null) {
            return null;
        }
        List<TagInfo> tags = tagRepo.getByTorrentId(id);

        return makeInfo(torrent, tags);
    }

    private TorrentInfo makeInfo(Torrent torrent, List<TagInfo> tags) {
        TorrentDownload task = session.getTask(torrent.id);
        if (task == null || !task.isValid() || task.isStopped()) {
            var info = new TorrentInfo(torrent.id, torrent.name, torrent.dateAdded, torrent.error, tags);
            if (torrent.visibility == Torrent.VISIBILITY_HIDDEN) {
                info.stateCode = org.proninyaroslav.libretorrent.core.model.data.TorrentStateCode.FINISHED;
                info.progress = 100;
            } else if (torrent.manuallyPaused) {
                info.stateCode = org.proninyaroslav.libretorrent.core.model.data.TorrentStateCode.PAUSED;
            }
            return info;
        } else {
            return new TorrentInfo(
                    torrent.id,
                    torrent.name,
                    task.getStateCode(),
                    task.getProgress(),
                    task.getReceivedBytes(),
                    task.getTotalSentBytes(),
                    task.getTotalWanted(),
                    task.getDownloadSpeed(),
                    task.getUploadSpeed(),
                    task.getETA(),
                    torrent.dateAdded,
                    task.getTotalPeers(),
                    task.getConnectedPeers(),
                    torrent.error,
                    task.isSequentialDownload(),
                    task.getFilePriorities(),
                    tags,
                    task.isFirstLastPiecePriority()
            );
        }
    }

    /*
     * Do not run in the UI thread
     */

    public List<TorrentInfo> makeInfoListSync() {
        ArrayList<TorrentInfo> stateList = new ArrayList<>();

        for (Torrent torrent : repo.getAllTorrents()) {
            if (torrent == null) {
                continue;
            }
            List<TagInfo> tags = tagRepo.getByTorrentId(torrent.id);
            stateList.add(makeInfo(torrent, tags));
        }

        return stateList;
    }

    /*
     * Do not run in the UI thread
     */

    public AdvancedTorrentInfo makeAdvancedInfoSync(@NonNull String id) {
        if (!isRunning())
            return null;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return null;

        Torrent torrent = repo.getTorrentById(id);
        if (torrent == null)
            return null;

        int[] piecesAvail = task.getPiecesAvailability();

        return new AdvancedTorrentInfo(
                torrent.id,
                task.getFilesReceivedBytes(),
                task.getTotalSeeds(),
                task.getConnectedSeeds(),
                task.getNumDownloadedPieces(),
                task.getShareRatio(),
                task.getActiveTime(),
                task.getSeedingTime(),
                task.getAvailability(piecesAvail),
                task.getFilesAvailability(piecesAvail),
                task.getConnectedLeechers(),
                task.getTotalLeechers());
    }

    public List<TrackerInfo> makeTrackerInfoList(@NonNull String id) {
        if (!isRunning())
            return new ArrayList<>();

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return new ArrayList<>();

        return task.getTrackerInfoList();
    }

    public List<PeerInfo> makePeerInfoList(@NonNull String id) {
        if (!isRunning())
            return new ArrayList<>();

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return new ArrayList<>();

        return task.getPeerInfoList();
    }

    public int getUploadSpeedLimit(@NonNull String id) {
        if (!isRunning())
            return -1;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return -1;

        return task.getUploadSpeedLimit();
    }

    public int getDownloadSpeedLimit(@NonNull String id) {
        if (!isRunning())
            return -1;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return -1;

        return task.getDownloadSpeedLimit();
    }

    public void setDownloadSpeedLimit(@NonNull String id, int limit) {
        if (!isRunning())
            return;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return;

        task.setDownloadSpeedLimit(limit);
    }

    public void setUploadSpeedLimit(@NonNull String id, int limit) {
        if (!isRunning())
            return;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return;

        task.setUploadSpeedLimit(limit);
    }

    public byte[] getBencode(@NonNull String id) {
        if (!isRunning())
            return null;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return null;

        return task.getBencode();
    }

    public boolean isSequentialDownload(@NonNull String id) {
        if (!isRunning())
            return false;

        TorrentDownload task = session.getTask(id);
        if (task == null)
            return false;

        return task.isSequentialDownload();
    }

    public int[] getPieceSizeList() {
        return session.getPieceSizeList();
    }

    public int[] getTorrentVersionList() {
        return session.getTorrentVersionList();
    }

    public Logger getSessionLogger() {
        return session.getLogger();
    }

    private void saveTorrentFileIn(@NonNull Torrent torrent,
                                   @NonNull Uri saveDir) {
        String torrentFileName = torrent.name + ".torrent";
        try {
            if (!saveTorrentFile(torrent.id, saveDir, torrentFileName))
                Log.w(TAG, "Could not save torrent file + " + torrentFileName);

        } catch (Exception e) {
            Log.w(TAG, "Could not save torrent file + " + torrentFileName + ": ", e);
        }
    }

    private boolean saveTorrentFile(String id, Uri destDir, String fileName) throws IOException, UnknownUriException {
        byte[] bencode = getBencode(id);
        if (bencode == null)
            return false;

        String name = (fileName != null ? fileName : id);

        Uri path = fs.createFile(destDir, name, true);
        if (path == null)
            return false;

        fs.write(bencode, path);

        return true;
    }

    private void switchPowerReceiver() {
        boolean batteryControl = pref.batteryControl();
        boolean customBatteryControl = pref.customBatteryControl();
        boolean onlyCharging = pref.onlyCharging();

        try {
            appContext.unregisterReceiver(powerReceiver);

        } catch (IllegalArgumentException e) {
            /* Ignore non-registered receiver */
        }
        if (customBatteryControl) {
            ContextCompat.registerReceiver(appContext, powerReceiver, PowerReceiver.getCustomFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
            /* Custom receiver doesn't send sticky intent, reschedule manually */
            rescheduleTorrents();
        } else if (batteryControl || onlyCharging) {
            ContextCompat.registerReceiver(appContext, powerReceiver, PowerReceiver.getFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    private void switchConnectionReceiver() {
        boolean unmeteredOnly = pref.unmeteredConnectionsOnly();
        boolean roaming = pref.enableRoaming();

        try {
            appContext.unregisterReceiver(connectionReceiver);

        } catch (IllegalArgumentException e) {
            /* Ignore non-registered receiver */
        }
        if (unmeteredOnly || roaming) {
            ContextCompat.registerReceiver(appContext, connectionReceiver, ConnectionReceiver.getFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    private boolean checkPauseTorrents() {
        boolean batteryControl = pref.batteryControl();
        boolean customBatteryControl = pref.customBatteryControl();
        int customBatteryControlValue = pref.customBatteryControlValue();
        boolean onlyCharging = pref.onlyCharging();
        boolean unmeteredOnly = pref.unmeteredConnectionsOnly();
        boolean roaming = pref.enableRoaming();

        boolean stop = false;
        if (roaming)
            stop = Utils.isRoaming(appContext);
        if (unmeteredOnly)
            stop = Utils.isMetered(appContext);
        if (onlyCharging)
            stop |= !Utils.isBatteryCharging(appContext);
        if (customBatteryControl)
            stop |= Utils.isBatteryBelowThreshold(appContext, customBatteryControlValue);
        else if (batteryControl)
            stop |= Utils.isBatteryLow(appContext);

        return stop;
    }

    private void handleOnSessionStarted() {
        if (pref.enableIpFiltering()) {
            String path = pref.ipFilteringFile();
            if (path != null)
                session.enableIpFilter(Uri.parse(path));
        }

        if (pref.watchDir())
            startWatchDir();

        boolean enableStreaming = pref.enableStreaming();
        if (enableStreaming)
            startStreamingServer();

        loadTorrents();
    }

    private void startStreamingServer() {
        stopStreamingServer();

        String hostname = pref.streamingHostname();
        int port = pref.streamingPort();

        torrentStreamServer = new TorrentStreamServer(hostname, port);
        try {
            torrentStreamServer.start(appContext);

        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            notifier.makeErrorNotify(appContext.getString(R.string.pref_streaming_error));
        }
    }

    private void stopStreamingServer() {
        if (torrentStreamServer != null)
            torrentStreamServer.stop();
        torrentStreamServer = null;
    }

    private void loadTorrents() {
        disposables.add(Completable.fromRunnable(() -> {
                    if (isRunning()) {
                        session.restoreTorrents();
                        purgeOrphanedTemporaryCasts();
                    }
                }).subscribeOn(Schedulers.io())
                .subscribe());
    }

    private void setProxy() {
        SessionSettings s = session.getSettings();

        s.proxyType = SessionSettings.ProxyType.fromValue(pref.proxyType());
        s.proxyAddress = pref.proxyAddress();
        s.proxyPort = pref.proxyPort();
        s.proxyPeersToo = pref.proxyPeersToo();
        s.proxyRequireAllConnections = pref.proxyRequireAllConnections();
        s.proxyRequiresAuth = pref.proxyRequiresAuth();
        s.proxyLogin = pref.proxyLogin();
        s.proxyPassword = pref.proxyPassword();

        session.setSettings(s);
    }

    private SessionSettings.EncryptMode getEncryptInConnectionsMode() {
        return SessionSettings.EncryptMode.fromValue(pref.encryptInConnectionsMode());
    }

    private SessionSettings.EncryptMode getEncryptOutConnectionsMode() {
        return SessionSettings.EncryptMode.fromValue(pref.encryptOutConnectionsMode());
    }

    private void startWatchDir() {
        String dir = pref.dirToWatch();
        Uri uri = Uri.parse(dir);
        if (!Utils.isFileSystemPath(uri))
            throw new IllegalArgumentException("SAF is not supported:" + uri);
        dir = uri.getPath();

        scanTorrentsInDir(dir);
        fileObserver = makeTorrentFileObserver(dir);
        fileObserver.startWatching();
    }

    private void stopWatchDir() {
        if (fileObserver == null)
            return;

        fileObserver.stopWatching();
        fileObserver = null;
    }

    private TorrentFileObserver makeTorrentFileObserver(String pathToDir) {
        return new TorrentFileObserver(pathToDir) {
            @Override
            public void onEvent(int event, @Nullable String name) {
                if (name == null)
                    return;

                File f = new File(pathToDir, name);
                if (!f.exists())
                    return;
                if (f.isDirectory() || !f.getName().endsWith(".torrent"))
                    return;
                Uri uri = Uri.fromFile(f);
                disposables.add(addTorrentCompletable(uri)
                        .subscribeOn(Schedulers.io())
                        .subscribe(() -> {
                            if (pref.watchDirDeleteFile()) {
                                try {
                                    fs.deleteFile(uri);
                                } catch (IOException | UnknownUriException e) {
                                    Log.w(TAG, "[Watch] Unable to delete file: "
                                            + Log.getStackTraceString(e));
                                }
                            }
                        })
                );
            }
        };
    }

    private void scanTorrentsInDir(String pathToDir) {
        File dir = new File(pathToDir);
        if (!dir.exists())
            return;
        for (File file : org.apache.commons.io.FileUtils.listFiles(dir, FileFilterUtils.suffixFileFilter(".torrent"), null)) {
            if (!file.exists())
                continue;
            addTorrent(Uri.fromFile(file));
        }
    }

    private Torrent addTorrentSync(Uri file, TorrentMetaInfo info, Uri savePath)
            throws IOException,
            FreeSpaceException,
            TorrentAlreadyExistsException,
            DecodeException,
            UnknownUriException {
        Priority[] priorities = new Priority[info.fileCount];
        Arrays.fill(priorities, Priority.DEFAULT);
        Uri downloadPath = (savePath == null ? Uri.parse(pref.saveTorrentsIn()) : savePath);

        AddTorrentParams params = new AddTorrentParams(
                file.toString(),
                false,
                info.sha1Hash,
                info.torrentName,
                priorities,
                downloadPath,
                false,
                false,
                new ArrayList<>(),
                false
        );

        if (fs.getDirAvailableBytes(downloadPath) < info.torrentSize) {
            throw new FreeSpaceException();
        }

        return addTorrentSync(params, false);
    }

    private void handleAddTorrentError(String name, Throwable e) {
        if (e instanceof TorrentAlreadyExistsException) {
            notifier.makeTorrentInfoNotify(name, appContext.getString(R.string.torrent_exist));
            return;
        }
        Log.e(TAG, Log.getStackTraceString(e));
        String message;
        if (e instanceof FileNotFoundException)
            message = appContext.getString(R.string.error_file_not_found_add_torrent);
        else if (e instanceof IOException)
            message = appContext.getString(R.string.error_io_add_torrent);
        else
            message = appContext.getString(R.string.error_add_torrent);
        notifier.makeTorrentErrorNotify(name, message);
    }

    private void cleanTemp() {
        try {
            fs.cleanTempDir();

        } catch (Exception e) {
            Log.e(TAG, "Error during setup of temp directory: ", e);
        }
    }

    private void setRandomPortRange(boolean useRandomPort) {
        SessionSettings settings = session.getSettings();
        settings.useRandomPort = useRandomPort;
        if (!useRandomPort) {
            int first = pref.portRangeFirst();
            int second = pref.portRangeSecond();
            if (first != -1 && second != -1) {
                settings.portRangeFirst = first;
                settings.portRangeSecond = second;
            }
        }
        session.setSettings(settings, false);
    }

    private void setPortRange(int first, int second) {
        if (first == -1 || second == -1)
            return;

        SessionSettings settings = session.getSettings();
        settings.portRangeFirst = first;
        settings.portRangeSecond = second;
        session.setSettings(settings, false);
    }

    /*
     * Disable notifications for torrent
     */

    private void markAsHiddenSync(Torrent torrent) {
        torrent.visibility = Torrent.VISIBILITY_HIDDEN;
        repo.updateTorrent(torrent);
    }

    private final TorrentEngineListener engineListener = new TorrentEngineListener() {
        @Override
        public void onSessionStats(@NonNull org.proninyaroslav.libretorrent.core.model.data.SessionStats stats) {
            if (stopping || !isRunning()) return;
            session.pauseSeeding();
            updateDownloadService();
        }

        @Override
        public void onSessionStarted() {
            handleOnSessionStarted();
        }

        @Override
        public void onTorrentResumed(@NonNull String id) {
            updateDownloadService();
        }

        @Override
        public void onTorrentAdded(@NonNull String id) {
            updateDownloadService();
            if (pref.saveTorrentFiles())
                saveTorrentFileIn(repo.getTorrentById(id),
                        Uri.parse(pref.saveTorrentFilesIn()));

            if (checkPauseTorrents()) {
                disposables.add(Completable.fromRunnable(() -> {
                            if (!isRunning())
                                return;
                            TorrentDownload task = session.getTask(id);
                            if (task != null)
                                task.pause();

                        }).subscribeOn(Schedulers.io())
                        .subscribe());
            }
        }

        @Override
        public void onTorrentLoaded(@NonNull String id) {
            if (checkPauseTorrents()) {
                disposables.add(Completable.fromRunnable(() -> {
                            if (!isRunning())
                                return;
                            TorrentDownload task = session.getTask(id);
                            if (task != null)
                                task.pause();

                        }).subscribeOn(Schedulers.io())
                        .subscribe());
            }
        }

        @Override
        public void onTorrentFinished(@NonNull String id) {
            TorrentDownload task = session.getTask(id);
            if (task != null) task.pause();
            disposables.add(repo.getTorrentByIdSingle(id)
                    .subscribeOn(Schedulers.io())
                    .filter(Objects::nonNull)
                    .subscribe((torrent) -> {
                                if (isTemporaryCastTorrent(torrent)) return;
                                notifier.makeTorrentFinishedNotify(torrent);
                                if (torrent.visibility != Torrent.VISIBILITY_HIDDEN)
                                    markAsHiddenSync(torrent);

                                if (pref.moveAfterDownload()) {
                                    String curPath = torrent.downloadPath.toString();
                                    String newPath = pref.moveAfterDownloadIn();

                                    if (!curPath.equals(newPath))
                                        setDownloadPath(id, Uri.parse(newPath));
                                }
                            },
                            (Throwable t) -> Log.e(TAG, "Getting torrent " + id + " error: " +
                                    Log.getStackTraceString(t)))
            );
        }

        @Override
        public void onTorrentMoving(@NonNull String id) {
            disposables.add(repo.getTorrentByIdSingle(id)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe((torrent) -> {
                                String name;
                                if (torrent == null)
                                    name = id;
                                else
                                    name = torrent.name;

                                notifier.makeMovingTorrentNotify(name);
                            },
                            (Throwable t) -> Log.e(TAG, "Getting torrent " + id + " error: " +
                                    Log.getStackTraceString(t)))
            );
        }

        @Override
        public void onTorrentMoved(@NonNull String id, boolean success) {
            disposables.add(repo.getTorrentByIdSingle(id)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe((torrent) -> {
                                String name;
                                if (torrent == null)
                                    name = id;
                                else
                                    name = torrent.name;

                                if (success)
                                    notifier.makeTorrentInfoNotify(name,
                                            appContext.getString(R.string.torrent_move_success));
                                else
                                    notifier.makeTorrentErrorNotify(name,
                                            appContext.getString(R.string.torrent_move_fail));
                            },
                            (Throwable t) -> Log.e(TAG, "Getting torrent " + id + " error: " +
                                    Log.getStackTraceString(t)))
            );
        }

        @Override
        public void onIpFilterParsed(int ruleCount) {
            disposables.add(Completable.fromRunnable(() -> Toast.makeText(appContext,
                                    (ruleCount > 0 ?
                                            appContext.getString(R.string.ip_filter_add_success) :
                                            appContext.getString(R.string.ip_filter_add_error, ruleCount)),
                                    Toast.LENGTH_LONG)
                            .show())
                    .subscribeOn(AndroidSchedulers.mainThread())
                    .subscribe()
            );
        }

        @Override
        public void onSessionError(@NonNull String errorMsg) {
            if (errorFilter.skip(errorMsg)) {
                return;
            }
            notifier.makeSessionErrorNotify(errorMsg);
        }

        @Override
        public void onNatError(@NonNull String errorMsg) {
            Log.e(TAG, "NAT error: " + errorMsg);
            if (pref.showNatErrors())
                notifier.makeNatErrorNotify(errorMsg);
        }

        @Override
        public void onRestoreSessionError(@NonNull String id) {
            disposables.add(repo.getTorrentByIdSingle(id)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe((torrent) -> {
                                String name;
                                if (torrent == null)
                                    name = id;
                                else
                                    name = torrent.name;

                                notifier.makeTorrentErrorNotify(name,
                                        appContext.getString(R.string.restore_torrent_error));
                            },
                            (Throwable t) -> Log.e(TAG, "Getting torrent " + id + " error: " +
                                    Log.getStackTraceString(t)))
            );
        }

        @Override
        public void onTorrentMetadataLoaded(@NonNull String id, Exception err) {
            if (err != null) {
                Log.e(TAG, "Load metadata error: ");
                Log.e(TAG, Log.getStackTraceString(err));
            }

            disposables.add(repo.getTorrentByIdSingle(id)
                    .subscribeOn(Schedulers.io())
                    .filter(Objects::nonNull)
                    .subscribe((torrent) -> {
                                if (err == null) {
                                    if (pref.saveTorrentFiles())
                                        saveTorrentFileIn(torrent, Uri.parse(pref.saveTorrentFilesIn()));

                                } else if (err instanceof FreeSpaceException) {
                                    notifier.makeTorrentErrorNotify(torrent.name, appContext.getString(R.string.error_free_space));
                                }
                            },
                            (Throwable t) -> Log.e(TAG, "Getting torrent " + id + " error: " +
                                    Log.getStackTraceString(t)))
            );

            if (checkPauseTorrents()) {
                disposables.add(Completable.fromRunnable(() -> {
                            if (!isRunning())
                                return;
                            TorrentDownload task = session.getTask(id);
                            if (task != null)
                                task.pause();

                        }).subscribeOn(Schedulers.io())
                        .subscribe());
            }
        }
    };

    private void handleSettingsChanged(String key) {
        boolean reschedule = false;

        if (key.equals(appContext.getString(R.string.pref_key_unmetered_connections_only)) ||
                key.equals(appContext.getString(R.string.pref_key_enable_roaming))) {
            reschedule = true;
            switchConnectionReceiver();

        } else if (key.equals(appContext.getString(R.string.pref_key_download_and_upload_only_when_charging)) ||
                key.equals(appContext.getString(R.string.pref_key_battery_control))) {
            reschedule = true;
            switchPowerReceiver();

        } else if (key.equals(appContext.getString(R.string.pref_key_custom_battery_control)) ||
                key.equals(appContext.getString(R.string.pref_key_custom_battery_control_value))) {
            switchPowerReceiver();

        } else if (key.equals(appContext.getString(R.string.pref_key_max_download_speed))) {
            SessionSettings s = session.getSettings();
            s.downloadRateLimit = pref.maxDownloadSpeedLimit();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_max_upload_speed))) {
            SessionSettings s = session.getSettings();
            s.uploadRateLimit = pref.maxUploadSpeedLimit();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_max_connections))) {
            SessionSettings s = session.getSettings();
            s.connectionsLimit = pref.maxConnections();
            s.maxPeerListSize = s.connectionsLimit;
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_max_connections_per_torrent))) {
            session.setMaxConnectionsPerTorrent(pref.maxConnectionsPerTorrent());

        } else if (key.equals(appContext.getString(R.string.pref_key_max_uploads_per_torrent))) {
            session.setMaxUploadsPerTorrent(pref.maxUploadsPerTorrent());

        } else if (key.equals(appContext.getString(R.string.pref_key_max_active_downloads))) {
            SessionSettings s = session.getSettings();
            s.activeDownloads = pref.maxActiveDownloads();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_max_active_uploads))) {
            SessionSettings s = session.getSettings();
            s.activeSeeds = pref.maxActiveUploads();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_max_active_torrents))) {
            SessionSettings s = session.getSettings();
            s.activeLimit = pref.maxActiveTorrents();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enable_dht))) {
            SessionSettings s = session.getSettings();
            s.dhtEnabled = pref.enableDht();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enable_lsd))) {
            SessionSettings s = session.getSettings();
            s.lsdEnabled = pref.enableLsd();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enable_utp))) {
            SessionSettings s = session.getSettings();
            s.utpEnabled = pref.enableUtp();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enable_upnp))) {
            SessionSettings s = session.getSettings();
            s.upnpEnabled = pref.enableUpnp();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enable_natpmp))) {
            SessionSettings s = session.getSettings();
            s.natPmpEnabled = pref.enableNatPmp();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enc_in_connections_mode))) {
            SessionSettings s = session.getSettings();
            s.encryptModeIncoming = getEncryptInConnectionsMode();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enc_out_connections_mode))) {
            SessionSettings s = session.getSettings();
            s.encryptModeOutcoming = getEncryptOutConnectionsMode();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_use_random_port))) {
            setRandomPortRange(pref.useRandomPort());

        } else if (key.equals(appContext.getString(R.string.pref_key_port_range_first)) ||
                key.equals(appContext.getString(R.string.pref_key_port_range_second))) {
            int portFirst = pref.portRangeFirst();
            int portSecond = pref.portRangeSecond();
            setPortRange(portFirst, portSecond);

        } else if (key.equals(appContext.getString(R.string.pref_key_enable_ip_filtering))) {
            if (pref.enableIpFiltering()) {
                String path = pref.ipFilteringFile();
                if (path != null)
                    session.enableIpFilter(Uri.parse(path));
            } else {
                session.disableIpFilter();
            }

        } else if (key.equals(appContext.getString(R.string.pref_key_ip_filtering_file))) {
            String path = pref.ipFilteringFile();
            if (path != null)
                session.enableIpFilter(Uri.parse(path));

        } else if (key.equals(appContext.getString(R.string.pref_key_apply_proxy))) {
            if (pref.applyProxy()) {
                pref.applyProxy(false);
                setProxy();
                Toast.makeText(appContext,
                                R.string.proxy_settings_applied,
                                Toast.LENGTH_SHORT)
                        .show();
            }

        } else if (key.equals(appContext.getString(R.string.pref_key_auto_manage))) {
            session.setAutoManaged(pref.autoManage());

        } else if (key.equals(appContext.getString(R.string.pref_key_watch_dir))) {
            if (pref.watchDir())
                startWatchDir();
            else
                stopWatchDir();

        } else if (key.equals(appContext.getString(R.string.pref_key_dir_to_watch))) {
            if (pref.watchDir()) {
                stopWatchDir();
                startWatchDir();
            }
        } else if (key.equals(appContext.getString(R.string.pref_key_streaming_enable))) {
            if (pref.enableStreaming())
                startStreamingServer();
            else
                stopStreamingServer();

        } else if (key.equals(appContext.getString(R.string.pref_key_streaming_port)) ||
                key.equals(appContext.getString(R.string.pref_key_streaming_hostname))) {
            startStreamingServer();

        } else if (key.equals(appContext.getString(R.string.pref_key_anonymous_mode))) {
            SessionSettings s = session.getSettings();
            s.anonymousMode = pref.anonymousMode();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_seeding_outgoing_connections))) {
            SessionSettings s = session.getSettings();
            s.seedingOutgoingConnections = pref.seedingOutgoingConnections();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_enable_logging))) {
            SessionSettings s = session.getSettings();
            s.logging = pref.logging();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_log_session_filter))) {
            SessionSettings s = session.getSettings();
            s.logSessionFilter = pref.logSessionFilter();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_log_dht_filter))) {
            SessionSettings s = session.getSettings();
            s.logDhtFilter = pref.logDhtFilter();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_log_peer_filter))) {
            SessionSettings s = session.getSettings();
            s.logPeerFilter = pref.logPeerFilter();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_log_portmap_filter))) {
            SessionSettings s = session.getSettings();
            s.logPortmapFilter = pref.logPortmapFilter();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_log_torrent_filter))) {
            SessionSettings s = session.getSettings();
            s.logTorrentFilter = pref.logTorrentFilter();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_max_log_size))) {
            SessionSettings s = session.getSettings();
            s.maxLogSize = pref.maxLogSize();
            session.setSettings(s);

        } else if (key.equals(appContext.getString(R.string.pref_key_default_trackers_list))) {
            session.setDefaultTrackersList(pref.defaultTrackersList().split("\n"));

        } else if (key.equals(appContext.getString(R.string.pref_key_validate_https_trackers))) {
            SessionSettings s = session.getSettings();
            s.validateHttpsTrackers = pref.validateHttpsTrackers();
            session.setSettings(s);
        }

        if (reschedule)
            rescheduleTorrents();
    }
}
