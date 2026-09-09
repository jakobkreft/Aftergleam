package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Test
import si.jakobkreft.aftergleam.data.FetchPlan

/**
 * When a trip to the servers is worth making.
 *
 * Pulling to refresh always went to the network, even seconds after a fetch, and arXiv
 * announces once a weekday: the second request returned the same papers for six seconds of
 * waiting on arXiv alone and half a minute with bioRxiv and medRxiv on.
 */
class FetchPlanTest {

    private val subs = setOf("cs.LG", "cs.CV")

    private fun decide(
        fetched: Set<String> = subs,
        announced: Boolean = false,
        recent: Boolean = true,
        forced: Boolean = false,
        network: Boolean = true,
    ) = FetchPlan.decide(subs, fetched, announced, recent, forced, network)

    @Test
    fun `a new announcement is always worth fetching`() {
        assertEquals(subs, decide(announced = true))
        // Even moments after the last one, because the papers really are new.
        assertEquals(subs, decide(announced = true, recent = true))
    }

    @Test
    fun `refreshing just after a fetch asks for nothing`() {
        // The case that wasted the time: nothing announced, nothing aged, everything here.
        assertEquals(emptySet<String>(), decide(forced = true, recent = true))
    }

    @Test
    fun `refreshing later is honoured`() {
        // bioRxiv has no announcement schedule, so once the interval has passed a reader
        // asking for a check should get one.
        assertEquals(subs, decide(forced = true, recent = false))
    }

    @Test
    fun `the app does not fetch on its own without an announcement`() {
        assertEquals(emptySet<String>(), decide(forced = false, recent = false))
    }

    @Test
    fun `a newly ticked subject is fetched on its own`() {
        // Ticking a subject and being told there is nothing new would be the worst of both:
        // it has no papers here at all. Only that subject is asked for, not the rest.
        assertEquals(
            setOf("cs.CV"),
            decide(fetched = setOf("cs.LG"), forced = false, recent = true),
        )
    }

    @Test
    fun `no network means no plan`() {
        assertEquals(emptySet<String>(), decide(announced = true, forced = true, network = false))
    }

    @Test
    fun `no subjects means no plan`() {
        assertEquals(
            emptySet<String>(),
            FetchPlan.decide(emptySet(), emptySet(), announced = true, recent = false, forced = true),
        )
    }
}
