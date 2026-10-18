package si.jakobkreft.aftergleam.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import si.jakobkreft.aftergleam.data.DailyNote
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The daily note, at an hour of the reader's choosing. See [DailyNote].
 *
 * Deliberately separate from [DailyDigestWorker] and deliberately offline. The digest is
 * prepared before dawn because that is when the phone is charging on wifi; being told about
 * it then is useless. This one downloads nothing, needs no network and no charger, and says
 * nothing on a day the app has already been opened. Named for what it used to be, a reading
 * reminder, because the name is what the work already scheduled on phones refers to.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val db = Db(applicationContext)
        val today = LocalDate.now()
        val digest = db.digestFor(today.toString())
        if (!DailyNote.due(prefs.reminderEnabled, digest, prefs.lastOpenedDay, today)) return Result.success()
        val card = DailyNote.pick(digest, db.actedOnIds()) ?: return Result.success()
        val paper = db.papersById(listOf(card.paperId)).firstOrNull() ?: return Result.success()
        Notifications.postDailyNote(applicationContext, paper, card.reason)
        return Result.success()
    }

    companion object {
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
