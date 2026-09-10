package si.jakobkreft.aftergleam

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.RateLimiter

/**
 * arXiv's one-request-every-three-seconds limit, kept by one object rather than by every
 * caller separately.
 *
 * The bug this exists to prevent: two callers each pacing themselves correctly and together
 * going twice as fast. Importing a library while the onboarding fetch ran did exactly that,
 * and the import looked stalled because arXiv had started refusing its lookups.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {

    @Test
    fun `the first request is not delayed`() = runTest {
        val clock = testScheduler
        val limiter = RateLimiter(3_000L) { clock.currentTime }
        limiter.paced { }
        assertEquals(0L, clock.currentTime)
    }

    @Test
    fun `a second request waits out the interval`() = runTest {
        val clock = testScheduler
        val limiter = RateLimiter(3_000L) { clock.currentTime }
        limiter.paced { }
        limiter.paced { }
        assertEquals(3_000L, clock.currentTime)
    }

    @Test
    fun `a slow request has already served the interval`() = runTest {
        // The gap is measured from when a request started, so five seconds spent waiting
        // for arXiv counts towards the three that must pass before asking again.
        val clock = testScheduler
        val limiter = RateLimiter(3_000L) { clock.currentTime }
        limiter.paced { kotlinx.coroutines.delay(5_000L) }
        val before = clock.currentTime
        limiter.paced { }
        assertEquals("no extra wait was needed", before, clock.currentTime)
    }

    @Test
    fun `concurrent callers are spaced, not doubled up`() = runTest {
        // The actual regression: an import and a feed fetch running at the same time.
        val clock = testScheduler
        val limiter = RateLimiter(3_000L) { clock.currentTime }
        val startedAt = mutableListOf<Long>()
        val calls = List(4) {
            async { limiter.paced { startedAt += clock.currentTime } }
        }
        calls.awaitAll()

        assertEquals(4, startedAt.size)
        startedAt.sorted().zipWithNext { a, b ->
            assertTrue("two requests went out ${b - a}ms apart", b - a >= 3_000L)
        }
    }
}
