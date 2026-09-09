package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Announcements
import java.time.ZoneId
import java.time.ZonedDateTime

class AnnouncementsTest {

    private val eastern = ZoneId.of("America/New_York")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, eastern).toInstant()

    @Test
    fun `an announcement is recognised on the evening it happens`() {
        // Wednesday 9 September 2026, just after eight in the evening Eastern.
        val justAfter = at(2026, 9, 9, 20, 5)
        val last = Announcements.lastAnnouncement(justAfter).atZone(eastern)
        assertEquals(9, last.dayOfMonth)
        assertEquals(20, last.hour)
    }

    @Test
    fun `before the evening the previous day's announcement is the latest`() {
        val morning = at(2026, 9, 9, 9)
        val last = Announcements.lastAnnouncement(morning).atZone(eastern)
        assertEquals("Tuesday evening is the most recent", 8, last.dayOfMonth)
    }

    @Test
    fun `the weekend gap is skipped`() {
        // Saturday: nothing was announced Friday or Saturday evening, so the latest is
        // Thursday. Getting this wrong would make the app refetch all weekend for nothing.
        val saturday = at(2026, 9, 12, 12)
        val last = Announcements.lastAnnouncement(saturday).atZone(eastern)
        assertEquals("Thursday 10 September", 10, last.dayOfMonth)

        val sundayMorning = at(2026, 9, 13, 10)
        assertEquals(10, Announcements.lastAnnouncement(sundayMorning).atZone(eastern).dayOfMonth)

        // Sunday evening does announce.
        val sundayNight = at(2026, 9, 13, 21)
        assertEquals(13, Announcements.lastAnnouncement(sundayNight).atZone(eastern).dayOfMonth)
    }

    @Test
    fun `a second fetch between announcements is refused`() {
        val morning = at(2026, 9, 9, 9)
        val fetchedAtEight = at(2026, 9, 9, 8).toEpochMilli()
        assertFalse(
            "nothing has been published since, so this must not hit the network",
            Announcements.hasNewSince(fetchedAtEight, morning),
        )
        // Ten minutes later, still nothing new: this is the pull-to-refresh case.
        assertFalse(Announcements.hasNewSince(fetchedAtEight, at(2026, 9, 9, 9, 10)))
    }

    @Test
    fun `a fetch from before the last announcement is stale`() {
        val fetchedTuesdayMorning = at(2026, 9, 8, 9).toEpochMilli()
        assertTrue(
            "Tuesday evening published since, so this should fetch",
            Announcements.hasNewSince(fetchedTuesdayMorning, at(2026, 9, 9, 9)),
        )
    }

    @Test
    fun `a first run always fetches`() {
        assertTrue(Announcements.hasNewSince(0L, at(2026, 9, 9, 9)))
    }

    @Test
    fun `a whole weekend of refreshing makes at most no requests`() {
        // Fetched Friday morning, after Thursday evening's announcement. Nothing more is
        // published until Sunday evening, so every refresh in between must be refused.
        val fridayMorning = at(2026, 9, 11, 9).toEpochMilli()
        for (instant in listOf(
            at(2026, 9, 11, 18), at(2026, 9, 12, 9), at(2026, 9, 12, 22), at(2026, 9, 13, 12),
        )) {
            assertFalse("no announcement before Sunday evening: $instant",
                Announcements.hasNewSince(fridayMorning, instant))
        }
        assertTrue("but Sunday evening does publish",
            Announcements.hasNewSince(fridayMorning, at(2026, 9, 13, 21)))
    }
}
