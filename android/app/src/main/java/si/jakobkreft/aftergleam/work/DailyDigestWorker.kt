package si.jakobkreft.aftergleam.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import si.jakobkreft.aftergleam.MainActivity
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.BioRxivApi
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.Topics
import si.jakobkreft.aftergleam.rank.DigestBuilder
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Builds tomorrow's digest before the user wakes up.
 *
 * arXiv announces once per weekday at 20:00 US Eastern, so one run a day is all that can
 * possibly be useful; this is periodic-daily rather than aggressive. An empty result is not
 * a failure, because weekends and US holidays legitimately produce nothing, and retrying
 * into that would only burn the rate limit.
 */
class DailyDigestWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val subscribed = prefs.categories
        if (subscribed.isEmpty()) return Result.success()

        return try {
            val papers = mutableListOf<si.jakobkreft.aftergleam.data.Paper>()
            val arxivCats = Topics.categoriesOf(Source.ARXIV, subscribed).toList()
            if (arxivCats.isNotEmpty()) papers += ArxivApi.recent(arxivCats, max = 300)
            for (server in listOf(Source.BIORXIV, Source.MEDRXIV)) {
                val subjects = Topics.categoriesOf(server, subscribed)
                if (subjects.isNotEmpty()) {
                    papers += runCatching { BioRxivApi.recent(server, subjects) }
                        .getOrDefault(emptyList())
                }
            }
            val db = Db(applicationContext)
            db.upsertPapers(papers)
            prefs.lastFetchMillis = System.currentTimeMillis()
            prefs.fetchedCategories = prefs.fetchedCategories + subscribed

            // What is everyone reading, joined locally. Enrichment: a failure here leaves
            // the digest exactly as it would have been.
            runCatching { Attention.fetch() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { db.saveAttention(it) }

            // Compose the digest too, not just fetch the papers for it.
            //
            // This worker used to stop at the download, so opening the app in the morning
            // meant waiting through a fetch that had already happened and a ranking that
            // had not. Both now happen at five in the morning, on wifi, on a charger, and
            // the first open of the day is a database read.
            //
            // Failing here is not a failed run: the papers are stored, and the app will
            // rank them itself the moment it is opened.
            runCatching {
                DigestBuilder.build(db, prefs, db.attention())
            }

            if (papers.isNotEmpty() && prefs.notifyEnabled) notify(papers.size)
            Result.success()
        } catch (e: Exception) {
            // Back off rather than hammer. 429s have been reported even inside the
            // documented one-request-per-three-seconds limit.
            Result.retry()
        }
    }

    /** Exactly one notification a day, and only when there is something new. */
    private fun notify(count: Int) {
        val ctx = applicationContext
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Daily digest", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "One notification a day when new papers are ready." }
        )
        val intent = android.app.PendingIntent.getActivity(
            ctx, 0,
            android.content.Intent(ctx, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(si.jakobkreft.aftergleam.R.drawable.ic_notification)
            .setContentTitle("Today's papers are ready")
            .setContentText("$count new in your categories")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // From Android 13 posting is a permission, and posting without it throws. The
        // throw was being swallowed, which worked but read as if somebody saying no were an
        // unexpected failure. Asking first says which it is.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "daily_digest"
        private const val NOTIFICATION_ID = 1
        const val WORK_NAME = "daily_digest"

        /** Schedules the next run for [hour] local time, then daily. */
        fun schedule(context: Context, hour: Int = 5) {
            val now = LocalDateTime.now()
            var next = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)

            val request = PeriodicWorkRequestBuilder<DailyDigestWorker>(Duration.ofDays(1))
                .setInitialDelay(Duration.between(now, next))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }
    }
}
