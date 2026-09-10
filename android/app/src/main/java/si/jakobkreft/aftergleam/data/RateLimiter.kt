package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets one caller through at a time, no faster than [minIntervalMs].
 *
 * arXiv asks for one request every three seconds, and every caller used to keep its own
 * clock: the importer slept between title lookups, the batch fetch slept between chunks,
 * and the digest slept not at all because on its own it never needed to. Separately each was
 * polite. Together they were not, so importing a library while the onboarding fetch ran
 * doubled the rate, arXiv began refusing the title lookups, and the import appeared to stall
 * with its matched count frozen.
 *
 * A limit that belongs to a service belongs in one object that every request goes through,
 * where no later caller can forget it.
 *
 * @param now injectable so the pacing can be tested against a virtual clock rather than by
 *   waiting three real seconds a few times.
 */
class RateLimiter(
    private val minIntervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()

    /**
     * When the last request began, or null if none has.
     *
     * Nullable rather than zero: a clock that legitimately reads zero, which a test clock
     * does on its first tick, would otherwise be mistaken for "nothing has run yet" and the
     * very first interval would be skipped.
     */
    private var lastStartedAt: Long? = null

    suspend fun <T> paced(block: suspend () -> T): T = gate.withLock {
        // Measured from when the last request started, which is what "one request every
        // three seconds" says. Measuring from when it finished would add the response time
        // to every gap, and over a two hundred entry import that is minutes of nothing.
        lastStartedAt?.let { last ->
            val since = now() - last
            if (since < minIntervalMs) delay(minIntervalMs - since)
        }
        lastStartedAt = now()
        // The lock is held for the whole request, so only one is ever in flight.
        block()
    }
}
