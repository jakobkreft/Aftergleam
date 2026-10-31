package si.jakobkreft.aftergleam

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.ShownItem
import si.jakobkreft.aftergleam.work.Schedule
import java.time.LocalDateTime
import java.time.ZoneId

/** When the morning fetch and the daily note happen, and what a missed one does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleTest {

    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = Prefs(ctx)
    private val day = LocalDateTime.of(2026, 10, 31, 0, 0)

    private fun at(hour: Int, minute: Int = 0, days: Long = 0) = day.plusDays(days).withHour(hour).withMinute(minute)
    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Each alarm set, by what it is for. */
    private fun alarms(): Map<String, LocalDateTime> =
        shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.associate {
            shadowOf(it.operation).savedIntent.action!!.substringAfterLast('.') to
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.triggerAtTime), ZoneId.systemDefault())
        }

    private fun posted() = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    /** A digest for the day, so that the note has something to name. */
    private fun digest() {
        val date = day.toLocalDate().toString()
        Db(ctx).upsertPapers(listOf(Paper("p", "A paper", "An abstract.", listOf("A. Chen"), listOf("cs.LG"), date, date)))
        Db(ctx).markShown(listOf(ShownItem("p", "RELEVANCE", "", 0.5f)), date)
    }

    @Before
    fun on() {
        shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.reminderEnabled = true
        prefs.reminderHour = 10
        prefs.digestHour = 5
    }

    @Test
    fun `the next hour is today's while it is ahead, otherwise tomorrow's`() {
        assertEquals(at(10), Schedule.next(10, at(9, 59)))
        assertEquals(at(10, days = 1), Schedule.next(10, at(10)))
        assertEquals(at(10, days = 1), Schedule.next(10, at(9), from = at(0, days = 1).toLocalDate()))
    }

    @Test
    fun `both alarms are set, whatever the phone is doing, and the note's goes when it is off`() {
        Schedule.armMorning(ctx, at(7))
        Schedule.armNote(ctx, at(7))
        assertEquals(mapOf("MORNING" to at(5, days = 1), "NOTE" to at(10)), alarms())
        assertTrue(shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.all { it.allowWhileIdle })

        prefs.reminderEnabled = false
        Schedule.planNote(ctx, at(7))
        assertEquals(setOf("MORNING"), alarms().keys)
        assertEquals(0L, prefs.noteDueMillis)
    }

    @Test
    fun `the note going off leaves the morning's alarm alone, though it is due at the same hour`() {
        digest()
        prefs.digestHour = 10
        Schedule.armMorning(ctx, at(9))
        Schedule.armNote(ctx, at(9))
        Schedule.onNote(ctx, at(10, 1))
        assertEquals(at(10), alarms()["MORNING"])
        assertEquals(at(10, days = 1), alarms()["NOTE"])
    }

    @Test
    fun `switched on after its hour, the first note is tomorrow's, not one at once`() {
        Schedule.armNote(ctx, at(14))
        assertEquals(at(10, days = 1), alarms()["NOTE"])
    }

    @Test
    fun `the note comes every day at its hour, and the next is tomorrow`() {
        digest()
        Schedule.armNote(ctx, at(7))
        Schedule.onNote(ctx, at(10))
        assertEquals(1, posted().size)
        assertEquals(day.toLocalDate(), prefs.noteDay)
        assertEquals(at(10, days = 1), alarms()["NOTE"])
    }

    @Test
    fun `a note missed while the phone was off is posted late the same day`() {
        digest()
        Schedule.armNote(ctx, at(7))
        // The alarm was lost; at 15:00 the phone is back and the alarm is set again, in the past.
        Schedule.armNote(ctx, at(15))
        assertEquals(at(10), alarms()["NOTE"])
        Schedule.onNote(ctx, at(15))
        assertEquals(1, posted().size)
        assertEquals(at(10, days = 1), alarms()["NOTE"])
    }

    @Test
    fun `yesterday's missed note is not posted the next morning, today's still comes`() {
        prefs.noteDueMillis = millis(at(10, days = -1))
        Schedule.onNote(ctx, at(6))
        assertTrue(posted().isEmpty())
        assertEquals(at(10), alarms()["NOTE"])
    }

    @Test
    fun `with nothing to name it tries again two hours later, but not past midnight`() {
        Schedule.armNote(ctx, at(7))
        Schedule.onNote(ctx, at(10))
        assertTrue(posted().isEmpty())
        assertNull(prefs.noteDay)
        assertEquals(at(12), alarms()["NOTE"])

        Schedule.onNote(ctx, at(22, 30))
        assertEquals(at(10, days = 1), alarms()["NOTE"])
    }

    @Test
    fun `moving the hour after today's note does not bring a second one`() {
        digest()
        Schedule.armNote(ctx, at(7))
        Schedule.onNote(ctx, at(10))
        prefs.reminderHour = 15
        Schedule.planNote(ctx, at(11))
        assertEquals(at(15, days = 1), alarms()["NOTE"])
    }

    @Test
    fun `moving the hour before today's note moves today's`() {
        Schedule.armNote(ctx, at(7))
        prefs.reminderHour = 8
        Schedule.planNote(ctx, at(7))
        assertEquals(at(8), alarms()["NOTE"])
    }

    @Test
    fun `the fetch is due from the morning's hour until something is fetched`() {
        prefs.lastFetchMillis = millis(at(23, days = -1))
        assertFalse("before five it is yesterday's, and that was done", Schedule.fetchDue(prefs, at(4)))
        assertTrue(Schedule.fetchDue(prefs, at(5)))
        prefs.lastFetchMillis = millis(at(6, 30))
        assertFalse("the app fetched since", Schedule.fetchDue(prefs, at(7)))
    }
}
