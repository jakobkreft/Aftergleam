package si.jakobkreft.aftergleam.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When arXiv last published anything, so the app can tell whether a fetch is pointless.
 *
 * arXiv announces once per weekday evening, at 20:00 US Eastern, Sunday through Thursday.
 * Between two announcements the same query returns exactly the same papers, so a second
 * request inside that window is pure waste and pure rate-limit risk. Pulling to refresh twice
 * in ten minutes used to issue two identical requests.
 *
 * Knowing the schedule beats a fixed interval in both directions: a six-hour timer refetches
 * four times a day for nothing, and can also leave the app an hour stale straight after an
 * announcement. This fetches exactly once per announcement.
 *
 * The clock is a parameter so this is testable without waiting for Thursday.
 */
object Announcements {

    private val EASTERN: ZoneId = ZoneId.of("America/New_York")

    /** Hour, US Eastern, at which the day's papers go up. */
    private const val ANNOUNCE_HOUR = 20

    /** No announcement is made on a Friday or Saturday evening. */
    private fun ZonedDateTime.announces(): Boolean =
        dayOfWeek != DayOfWeek.FRIDAY && dayOfWeek != DayOfWeek.SATURDAY

    /** The instant of the most recent announcement at or before [now]. */
    fun lastAnnouncement(now: Instant = Instant.now()): Instant {
        var t = now.atZone(EASTERN)
            .withHour(ANNOUNCE_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (t.toInstant().isAfter(now)) t = t.minusDays(1)
        // Walk back over the weekend gap.
        var guard = 0
        while (!t.announces() && guard++ < 8) t = t.minusDays(1)
        return t.toInstant()
    }

    /**
     * True when arXiv has published something since [lastFetchMillis].
     *
     * A fetch that has never happened is always stale, which is what a first run needs.
     */
    fun hasNewSince(lastFetchMillis: Long, now: Instant = Instant.now()): Boolean {
        if (lastFetchMillis <= 0L) return true
        return lastFetchMillis < lastAnnouncement(now).toEpochMilli()
    }
}
