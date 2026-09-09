package si.jakobkreft.aftergleam.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import si.jakobkreft.aftergleam.MainActivity
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A nudge to read today's digest, at an hour of the user's choosing.
 *
 * Deliberately separate from [DailyDigestWorker] and deliberately offline. The digest is
 * prepared before dawn because that is when the phone is charging on wifi; being told about
 * it then is useless. This one downloads nothing, needs no network and no charger, and says
 * nothing if the digest has already been read.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.reminderEnabled) return Result.success()

        val db = Db(applicationContext)
        val today = LocalDate.now().toString()
        val digest = db.digestFor(today)
        if (digest.isEmpty()) return Result.success()

        // Nothing to nudge about if the digest has already been worked through.
        val reactions = db.allReactions()
        val untouched = digest.count { reactions[it.paperId] == null }
        if (untouched == 0) return Result.success()

        notify(untouched)
        return Result.success()
    }

    private fun notify(untouched: Int) {
        val ctx = applicationContext
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading reminder", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "An optional nudge to read the day's papers." }
        )
        val intent = android.app.PendingIntent.getActivity(
            ctx, 1,
            android.content.Intent(ctx, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(si.jakobkreft.aftergleam.R.drawable.ic_notification)
            .setContentTitle("$untouched papers still waiting")
            .setContentText("Five minutes and you are done for the day.")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val CHANNEL = "reading_reminder"
        private const val NOTIFICATION_ID = 2
        const val WORK_NAME = "reading_reminder"

        fun schedule(context: Context, hour: Int) {
            val now = LocalDateTime.now()
            var next = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(Duration.ofDays(1))
                .setInitialDelay(Duration.between(now, next))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
