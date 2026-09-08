package si.jakobkreft.aftergleam.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Fetches tomorrow's papers before the user wakes up.
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
        val cats = prefs.categories.toList()
        if (cats.isEmpty()) return Result.success()

        return try {
            val papers = ArxivApi.recent(cats, max = 300)
            Db(applicationContext).upsertPapers(papers)
            prefs.lastFetchMillis = System.currentTimeMillis()
            if (papers.isNotEmpty()) notify(papers.size)
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
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Today's papers are ready")
            .setContentText("$count new in your categories")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) }
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
