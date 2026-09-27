package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.Support
import java.time.LocalDate
import java.time.ZoneId

/**
 * When the support note may end the digest.
 *
 * The rules are the whole feature: a note that comes too early, too often, or cannot be
 * turned off is worse than none, so each promise the settings page makes is checked here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val today = LocalDate.of(2026, 10, 1)

    private fun due(
        readingDays: Int = 10,
        quietUntil: LocalDate? = null,
        enabled: Boolean = true,
        on: LocalDate = today,
    ) = Support.due(enabled, readingDays, on, quietUntil)

    @Test
    fun `nothing is asked before five separate reading days`() {
        assertFalse(due(readingDays = 4))
        assertTrue(due(readingDays = 5))
    }

    @Test
    fun `turned off means never`() {
        assertFalse(due(enabled = false, readingDays = 500))
    }

    @Test
    fun `until it is answered it is there every day`() {
        repeat(30) { assertTrue(due(on = today.plusDays(it.toLong()))) }
    }

    @Test
    fun `not now puts it away for a week by the calendar, however little the app is used`() {
        val back = today.plusDays(Support.LATER_DAYS)
        assertFalse(due(quietUntil = back, on = today.plusDays(6)))
        // Same reading days as before: coming back does not depend on having read since.
        assertTrue(due(quietUntil = back, on = back))
    }

    @Test
    fun `acting on it keeps it away for a year`() {
        val back = today.plusDays(Support.QUIET_AFTER_ACTING_DAYS)
        assertFalse(due(quietUntil = back, on = back.minusDays(1)))
        assertTrue(due(quietUntil = back, on = back))
    }

    @Test
    fun `a reading day is a local day with any signal, however many`() {
        val db = Db(ctx)
        val zone = ZoneId.systemDefault()
        fun at(day: LocalDate, hour: Int) =
            day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        fun signal(id: String, kind: String, ts: Long) {
            db.writableDatabase.execSQL(
                "INSERT INTO signals (paper_id, signal, ts) VALUES (?, ?, ?)",
                arrayOf<Any>(id, kind, ts),
            )
        }
        signal("a", "OPENED", at(today, 8))
        signal("b", "LIKED", at(today, 23))           // same evening, same day
        signal("c", "OPENED", at(today.minusDays(3), 9))
        signal("d", "PASSED", at(today.minusDays(5), 9))  // skipping is not reading
        assertEquals(2, db.readingDays())
    }

    @Test
    fun `the reminder's state survives in settings`() {
        val prefs = Prefs(ctx)
        assertTrue("on by default", prefs.supportReminder)
        assertNull(prefs.supportQuietUntil)
        prefs.supportQuietUntil = today.plusDays(7)
        prefs.supportReminder = false
        val again = Prefs(ctx)
        assertEquals(today.plusDays(7), again.supportQuietUntil)
        assertFalse(again.supportReminder)
    }
}
