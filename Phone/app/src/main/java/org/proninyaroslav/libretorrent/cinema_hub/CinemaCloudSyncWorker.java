package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Lightweight WorkManager background sync worker inside {@code cinema_hub/} (zero LibreTorrent or
 * CinemaBrowser dependencies). Runs every 6 hours (and as a 25s out-of-process fallback after local
 * or TV mutations) to reconcile any paired TV updates and silently upload pending changes to Google Drive.
 */
public final class CinemaCloudSyncWorker extends Worker {
    public static final String UNIQUE_PERIODIC_WORK = "local_cinema_periodic_cloud_sync_6h";
    public static final String UNIQUE_DEBOUNCED_WORK = "local_cinema_debounced_cloud_sync";

    public CinemaCloudSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            LocalCinemaBackupManager.runBackgroundSyncPass(getApplicationContext());
            return Result.success();
        } catch (Throwable ignored) {
            return Result.retry();
        }
    }

    public static void ensurePeriodicScheduled(Context context) {
        if (context == null) return;
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                    CinemaCloudSyncWorker.class, 6L, TimeUnit.HOURS, 30L, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniquePeriodicWork(
                            UNIQUE_PERIODIC_WORK,
                            ExistingPeriodicWorkPolicy.KEEP,
                            periodic);
        } catch (Throwable ignored) {}
    }

    public static void scheduleDebouncedFallback(Context context, long delaySeconds) {
        if (context == null) return;
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CinemaCloudSyncWorker.class)
                    .setInitialDelay(Math.max(20L, delaySeconds), TimeUnit.SECONDS)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(UNIQUE_DEBOUNCED_WORK, ExistingWorkPolicy.REPLACE, req);
        } catch (Throwable ignored) {}
    }
}
