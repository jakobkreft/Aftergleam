package si.jakobkreft.aftergleam.work

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Fetcher
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.rank.DigestBuilder

/**
 * Fetches the day's papers and builds the digest before the reader wakes up. Started by the
 * morning's alarm; see [Schedule].
 *
 * arXiv announces once per weekday at 20:00 US Eastern, so one run a day is all that can be
 * useful. An empty result is not a failure, because weekends and US holidays legitimately
 * produce nothing. A failure is every server failing, and that is tried again two hours later.
 */
class DailyDigestWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val subscribed = prefs.categories
        // A retry after the reader opened the app, which fetched for itself, has nothing to do,
        // and rebuilding the digest would reorder the one they were reading.
        if (subscribed.isEmpty() || !Schedule.fetchDue(prefs)) return Result.success()

        return try {
            val papers = mutableListOf<Paper>()
            val outcome = Fetcher.fetch(subscribed) { papers += it }
            if (outcome.allFailed) return Result.retry()
            val db = Db(applicationContext)
            db.upsertPapers(papers)
            prefs.lastFetchMillis = System.currentTimeMillis()
            // Only subjects whose server answered, as in the app: a refused request is not a
            // fetch, and recording it as one stops the next run from asking again.
            prefs.fetchedCategories = prefs.fetchedCategories +
                subscribed.filter { Source.label(Source.of(it)) !in outcome.failed }

            // The newest papers mentioning the reader's keywords, from any category.
            val keywords = prefs.keywords
            if (keywords.isNotEmpty()) {
                val done = runCatching { Fetcher.fetchKeywords(keywords) { db.upsertPapers(it) } }
                    .getOrDefault(emptyList())
                prefs.keywordsFetched = done.toSet()
            }

            // What is everyone reading, joined locally. Enrichment: a failure here leaves
            // the digest exactly as it would have been.
            runCatching { Attention.fetch() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { db.saveAttention(it) }

            // Compose the digest too, not just fetch the papers for it, so that the first open
            // of the day is a database read and the daily note has a paper to name.
            //
            // Failing here is not a failed run: the papers are stored, and the app will rank
            // them itself the moment it is opened.
            runCatching { DigestBuilder.build(db, prefs, db.attention()) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "morning_fetch"

        /** The fetch, as soon as there is a network, and again [Schedule.RETRY] after a failure. */
        fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<DailyDigestWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, Schedule.RETRY)
                .apply {
                    // At once, in Doze too, rather than at the system's next convenience.
                    // Before Android 12 an expedited job is a foreground service with a
                    // notification of its own, so there it waits its turn.
                    if (Build.VERSION.SDK_INT >= 31) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
