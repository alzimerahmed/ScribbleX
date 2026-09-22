package com.philkes.notallyx.utils.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Sync worker — on-demand ("Sync now") and optional periodic scheduling. Follows the existing
 * CoroutineWorker pattern (see AutoBackupWorker / AutoRemoveDeletedNotesWorker). All sync code is
 * inert unless the user enabled sync in settings; [scheduleSyncNow] is only called from the
 * settings UI and [schedulePeriodicSync] only when sync is enabled.
 */
class SyncWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = context.applicationContext as ContextWrapper
        val result = createSyncEngine(app).syncNow()
        val output =
            workDataOf(
                OUTPUT_STATUS to result.status.name,
                OUTPUT_UPLOADED to result.uploaded,
                OUTPUT_DOWNLOADED to result.downloaded,
            )
        return when (result.status) {
            SyncResult.Status.SUCCESS,
            SyncResult.Status.DISABLED,
            SyncResult.Status.NOT_CONFIGURED -> Result.success(output)

            // m2: report terminal failures (with the message) instead of retrying forever, so
            // the settings UI can show sync_failed. Retry would loop on non-transient errors.
            SyncResult.Status.BACKUP_FAILED,
            SyncResult.Status.ERROR ->
                Result.failure(
                    Data.Builder()
                        .putAll(output)
                        .putString(OUTPUT_EXCEPTION, result.message ?: result.status.name)
                        .build()
                )
        }
    }

    companion object {
        const val WORK_NAME_ON_DEMAND = "com.philkes.notallyx.SyncNow"
        const val WORK_NAME_PERIODIC = "com.philkes.notallyx.PeriodicSync"
        const val PERIODIC_SYNC_INTERVAL_DAYS = 1L
        const val OUTPUT_STATUS = "status"
        const val OUTPUT_UPLOADED = "uploaded"
        const val OUTPUT_DOWNLOADED = "downloaded"
        const val OUTPUT_EXCEPTION = "exception"
    }
}

/** Enqueues a one-off sync ("Sync now" button). No-op effect if sync is disabled. */
fun ContextWrapper.scheduleSyncNow() {
    WorkManager.getInstance(this)
        .enqueueUniqueWork(
            SyncWorker.WORK_NAME_ON_DEMAND,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
}

/** Schedules (or cancels) the daily periodic sync depending on [enabled]. */
fun ContextWrapper.schedulePeriodicSync(enabled: Boolean) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
        return
    }
    workManager.enqueueUniquePeriodicWork(
        SyncWorker.WORK_NAME_PERIODIC,
        ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<SyncWorker>(
                SyncWorker.PERIODIC_SYNC_INTERVAL_DAYS,
                TimeUnit.DAYS,
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build(),
    )
}
