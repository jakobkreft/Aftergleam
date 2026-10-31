package si.jakobkreft.aftergleam.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import si.jakobkreft.aftergleam.data.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the app does on its own, and when: the morning fetch at [Prefs.digestHour] and the
 * daily note at [Prefs.reminderHour].
 *
 * Both are times of day, so both are alarms. They used to be daily periodic jobs, the wrong
 * tool for a time: Android runs a job when it suits the battery, hours late on a phone left
 * alone, and these were scheduled again on every open, which pushed the note to the next day
 * each time. An alarm set with [AlarmManager.setAndAllowWhileIdle] goes off close to its time,
 * in Doze too, and needs no permission. The fetch itself is still a job, because it needs the
 * network, and it is the job that tries again two hours after a failure.
 *
 * Nothing waits for wifi, a charger or a full battery. A day's fetch is a handful of requests,
 * and these notes are what the reader installed the app for.
 *
 * Alarms are forgotten on a reboot, an update and a force stop, so they are set again after
 * each, by [Receiver], and whenever the app starts. The note's time is kept, so that a note
 * missed while the phone was off is posted late rather than not at all; a fetch missed that
 * way is made up at boot.
 */
object Schedule {

    private const val ACTION_MORNING = "si.jakobkreft.aftergleam.MORNING"
    private const val ACTION_NOTE = "si.jakobkreft.aftergleam.NOTE"

    /** How long until the next try, after a failed fetch or a note with nothing to name. */
    val RETRY: Duration = Duration.ofHours(2)

    /** Work that earlier versions scheduled, replaced by these alarms. */
    private val LEGACY_WORK = listOf("daily_digest", "reading_reminder")

    /** Everything the app keeps scheduled, set up again. Safe to call as often as wanted. */
    fun start(context: Context) {
        Notifications.ensureChannels(context)
        val work = WorkManager.getInstance(context)
        LEGACY_WORK.forEach { work.cancelUniqueWork(it) }
        MetadataRefreshWorker.schedule(context)
        if (Prefs(context).keywordAlerts.isEmpty()) KeywordAlertWorker.cancel(context)
        else KeywordAlertWorker.schedule(context)
        armMorning(context)
        armNote(context)
    }

    /**
     * Sets the fetch's alarm for the next [Prefs.digestHour]. Each alarm is set on its own, so
     * that setting one never moves the other while it is still to go off.
     */
    fun armMorning(context: Context, now: LocalDateTime = LocalDateTime.now()) =
        set(context, ACTION_MORNING, millis(next(Prefs(context).digestHour, now)))

    /** Sets the note's alarm for when it is due. A note due in the past, missed, goes off at once. */
    fun armNote(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val prefs = Prefs(context)
        if (!prefs.reminderEnabled) {
            prefs.noteDueMillis = 0L
            alarms(context).cancel(intent(context, ACTION_NOTE))
            return
        }
        if (prefs.noteDueMillis == 0L) {
            // From tomorrow if today's has been posted. And never at once: switched on after
            // its hour, the first note is tomorrow's.
            val today = now.toLocalDate()
            val from = if (prefs.noteDay == today) today.plusDays(1) else today
            prefs.noteDueMillis = millis(next(prefs.reminderHour, now, from))
        }
        set(context, ACTION_NOTE, prefs.noteDueMillis)
    }

    /** The note was switched on or off, or moved to another hour. */
    fun planNote(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        Prefs(context).noteDueMillis = 0L
        armNote(context, now)
    }

    /**
     * The note's alarm went off: posts today's note if its hour has come and it has not been
     * posted yet, and plans the next.
     */
    internal fun onNote(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val prefs = Prefs(context)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val hour = prefs.reminderHour
        val next = when {
            !prefs.reminderEnabled -> null
            prefs.noteDay == today -> next(hour, now, tomorrow)
            // Due on a day the phone was off. That day is over, and today's is still to come.
            now.isBefore(today.atTime(hour, 0)) -> next(hour, now)
            // Notifications are off in Android's settings. Tomorrow, in case they are back.
            !Notifications.canPost(context) -> next(hour, now, tomorrow)
            Notifications.postDailyNote(context, today) -> {
                prefs.noteDay = today
                next(hour, now, tomorrow)
            }
            // Nothing to name: no digest yet, and nothing kept. Again later today.
            else -> now.plus(RETRY).takeIf { it.toLocalDate() == today } ?: next(hour, now, tomorrow)
        }
        prefs.noteDueMillis = next?.let(::millis) ?: 0L
        armNote(context, now)
    }

    /** The first [hour]:00 on [from] or a later day that is still ahead of [now]. */
    internal fun next(hour: Int, now: LocalDateTime, from: LocalDate = now.toLocalDate()): LocalDateTime {
        var at = from.atTime(hour, 0)
        while (!at.isAfter(now)) at = at.plusDays(1)
        return at
    }

    /**
     * Whether the morning's fetch is still to happen: nothing fetched since the last
     * [Prefs.digestHour], by the app or in the background.
     */
    internal fun fetchDue(prefs: Prefs, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val morning = now.toLocalDate().atTime(prefs.digestHour, 0)
            .let { if (it.isAfter(now)) it.minusDays(1) else it }
        return prefs.lastFetchMillis < millis(morning)
    }

    private fun millis(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun set(context: Context, action: String, at: Long) =
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, action))

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, Receiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The two alarms, and the system's news that alarms were lost or moved: a reboot, an
     * update of the app, a new time or time zone. Not exported; the system reaches it anyway.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            val pending = goAsync()
            Thread {
                try {
                    when (intent.action) {
                        // A new day starts afresh, rather than behind yesterday's retries.
                        ACTION_MORNING -> {
                            DailyDigestWorker.enqueue(app, ExistingWorkPolicy.REPLACE)
                            armMorning(app)
                        }
                        ACTION_NOTE -> onNote(app)
                        else -> {
                            start(app)
                            // The phone may have been off at the morning's hour.
                            if (fetchDue(Prefs(app))) DailyDigestWorker.enqueue(app, ExistingWorkPolicy.KEEP)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
