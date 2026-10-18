package si.jakobkreft.aftergleam.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Fetcher
import si.jakobkreft.aftergleam.data.KeywordAlerts
import si.jakobkreft.aftergleam.data.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Asks the servers about the keywords with their bell on, and says when something new is there.
 *
 * Its own worker rather than part of the morning one, which waits for wifi and a charger and
 * can be a day late on a phone that is never left charging. This one needs any network and is
 * small: one request to arXiv and one to Crossref per word, for the belled keywords only.
 * Every six hours, since arXiv announces once a day and the other servers post through it.
 * Outside [KeywordAlerts.FIRST_HOUR] to [KeywordAlerts.LAST_HOUR] it does nothing at all, and
 * whatever appeared overnight is found by the first run of the morning.
 */
class KeywordAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val keywords = prefs.keywords.filter { it in prefs.keywordAlerts }
        if (keywords.isEmpty()) return Result.success()
        if (!KeywordAlerts.inWindow(LocalTime.now())) return Result.success()
        if (!Notifications.canPost(applicationContext)) return Result.success()

        val db = Db(applicationContext)
        val done = runCatching { Fetcher.fetchKeywords(keywords) { db.upsertPapers(it) } }
            .getOrElse { return Result.retry() }
        prefs.keywordsFetched = prefs.keywordsFetched + done
        check(applicationContext, db, prefs, keywords)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "keyword_alerts"

        /**
         * Tells the reader about what the phone holds and has not told them about yet, for
         * [keywords]. Apart from the fetch so the app can call it after a fetch of its own.
         */
        fun check(context: Context, db: Db, prefs: Prefs, keywords: List<String>, today: LocalDate = LocalDate.now()) {
            val since = today.minusDays(KeywordAlerts.RECENT_DAYS).toString()
            val matches = keywords.associateWith { db.keywordCandidates(listOf(it), since) }
            val fresh = KeywordAlerts.fresh(matches, db.alertedIds() + db.actedOnIds(), today)
            val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            for ((keyword, papers) in fresh) {
                val earlier = db.alertedSince(keyword, startOfDay)
                db.markAlerted(papers.map { it.id }, keyword)
                // Everything told about for this keyword today, newest first, so a second
                // batch joins the first note rather than replacing it.
                val ids = (papers.map { it.id } + earlier).distinct()
                val byId = db.papersById(ids).associateBy { it.id }
                Notifications.postKeywordAlert(
                    context, keyword, ids.mapNotNull { byId[it] }, quiet = earlier.isNotEmpty(),
                )
            }
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeywordAlertWorker>(Duration.ofHours(6))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
