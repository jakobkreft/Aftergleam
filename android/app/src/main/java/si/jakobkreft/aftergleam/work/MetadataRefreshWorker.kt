package si.jakobkreft.aftergleam.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.Db
import java.time.Duration
import java.time.LocalDate

/**
 * Refetches metadata for papers the user passed over months ago.
 *
 * This is what keeps the Resurfacer supplied. The venue signal arrives late by nature: an
 * author edits the comments field to say "Accepted at NeurIPS 2026" long after the paper was
 * announced and cached, so the copy on disk is stale for exactly the papers the feature is
 * about. Nothing else in the app would ever notice.
 *
 * Cheap by construction. Only papers in the three-to-twelve month window that still carry no
 * venue are candidates, and the arXiv API takes a hundred identifiers per request, so a
 * typical run is two or three requests once a week.
 */
class MetadataRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = Db(applicationContext)
        val today = LocalDate.now()
        val stale = db.staleMetadataIds(
            fromDay = today.minusMonths(12).toString(),
            toDay = today.minusMonths(3).toString(),
        )
        if (stale.isEmpty()) return Result.success()

        return try {
            val refreshed = ArxivApi.byIds(stale)
            if (refreshed.isNotEmpty()) db.upsertPapers(refreshed)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "metadata_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MetadataRefreshWorker>(Duration.ofDays(7))
                .setInitialDelay(Duration.ofHours(12))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
