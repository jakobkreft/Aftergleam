package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.rank.TopicBandit
import si.jakobkreft.aftergleam.rank.TopicBandit.Arm
import kotlin.random.Random

class TopicBanditTest {

    private fun tally(arms: List<Arm>, draws: Int = 4000, seed: Int = 5): Map<String, Int> {
        val r = Random(seed)
        val out = HashMap<String, Int>()
        repeat(draws) {
            TopicBandit.draw(arms, r)?.let { out[it] = (out[it] ?: 0) + 1 }
        }
        return out
    }

    @Test
    fun `a topic the reader engages with wins most slots`() {
        val counts = tally(listOf(
            Arm("diffusion", engaged = 18, ignored = 2),
            Arm("finance", engaged = 1, ignored = 19),
        ))
        assertTrue("the engaged topic should dominate: $counts",
            counts.getValue("diffusion") > counts.getOrDefault("finance", 0) * 3)
    }

    @Test
    fun `an untried topic still gets a real share`() {
        // The whole point. A topic nothing is known about has a flat posterior, so it wins
        // slots often enough to actually be tried, which is what per-item noise cannot do.
        val counts = tally(listOf(
            Arm("diffusion", engaged = 15, ignored = 5),
            Arm("neuroscience", engaged = 0, ignored = 0),
        ))
        val share = counts.getValue("neuroscience") / 4000.0
        assertTrue("an unknown topic must be explored, got ${"%.2f".format(share)}",
            share > 0.10)
        assertTrue("but it must not dominate a well-liked one, got ${"%.2f".format(share)}",
            share < 0.50)
    }

    @Test
    fun `evidence narrows the posterior and exploration falls away`() {
        // Same success rate, more evidence: the allocation should sharpen rather than drift.
        val thin = tally(listOf(
            Arm("a", engaged = 3, ignored = 1),
            Arm("b", engaged = 1, ignored = 3),
        ))
        val thick = tally(listOf(
            Arm("a", engaged = 300, ignored = 100),
            Arm("b", engaged = 100, ignored = 300),
        ))
        val thinShare = thin.getValue("a") / 4000.0
        val thickShare = thick.getValue("a") / 4000.0
        assertTrue("more evidence must sharpen the choice: $thinShare -> $thickShare",
            thickShare > thinShare)
        assertTrue("and with strong evidence it should be near total, got $thickShare",
            thickShare > 0.95)
    }

    @Test
    fun `a consistently ignored topic does stop appearing`() {
        // This is correct, not a bug. Forty ignores out of forty is strong evidence, and a
        // recommender that kept serving it anyway would not be listening.
        val counts = tally(listOf(
            Arm("liked", engaged = 40, ignored = 2),
            Arm("ignored", engaged = 0, ignored = 40),
        ))
        assertTrue("a well-evidenced dislike should be respected: $counts",
            counts.getOrDefault("ignored", 0) < 40)
    }

    @Test
    fun `renewed interest in an old topic moves the allocation`() {
        // Interests are not stationary: people change project. What the evidence window
        // guarantees is that certainty stays bounded, so a genuine change in the engagement
        // *rate* is believed within a handful of interactions instead of being outvoted by
        // years of accumulated history.
        val other = Arm("current", engaged = 60, ignored = 20)
        val stale = Arm("old", engaged = 5, ignored = 200)
        val renewed = Arm("old", engaged = 90, ignored = 200)

        val before = tally(listOf(stale, other)).getOrDefault("old", 0)
        val after = tally(listOf(renewed, other)).getOrDefault("old", 0)
        assertTrue("a changed rate must be believed, $before -> $after", after > before)
    }

    @Test
    fun `evidence loses half its weight over the half life`() {
        assertEquals(1f, TopicBandit.recency(0f), 1e-4f)
        assertEquals(0.5f, TopicBandit.recency(TopicBandit.HALF_LIFE_DAYS), 1e-4f)
        assertEquals(0.25f, TopicBandit.recency(2 * TopicBandit.HALF_LIFE_DAYS), 1e-4f)
        // Never negative, whatever a clock change hands it.
        assertEquals(1f, TopicBandit.recency(-5f), 1e-4f)
    }

    @Test
    fun `a topic abandoned and taken up again comes back`() {
        // The case the evidence window alone could not fix. Two hundred ignores from six
        // months ago against a dozen engagements this month is a six percent rate, and on
        // the raw counts the bandit correctly calls that poor and stops offering the topic,
        // so the reader has no way to tell it they have changed project.
        //
        // Decayed, those old ignores are worth about a fiftieth each and the recent
        // engagements almost their full value, which is the same evidence read as a
        // description of the reader now rather than of the reader last spring.
        val other = Arm("current", engaged = 40, ignored = 10)

        val raw = Arm("returned", engaged = 12, ignored = 200)
        val decayed = Arm(
            "returned",
            engaged = 12 * TopicBandit.recency(5f),
            ignored = 200 * TopicBandit.recency(180f),
        )

        val without = tally(listOf(raw, other)).getOrDefault("returned", 0) / 4000.0
        val with = tally(listOf(decayed, other)).getOrDefault("returned", 0) / 4000.0
        assertTrue("stale counts should bury it, got $without", without < 0.05)
        assertTrue("decayed counts should revive it, got $with", with > 0.25)
    }

    @Test
    fun `decay does not resurrect a topic ignored recently`() {
        // The other half of the bargain. If decay let *everything* back in, the bandit would
        // just be periodic amnesia, and the reader would keep being shown the thing they
        // have spent this month declining.
        val counts = tally(listOf(
            Arm("liked", engaged = 40 * TopicBandit.recency(10f),
                ignored = 2 * TopicBandit.recency(10f)),
            Arm("declined", engaged = 0f, ignored = 40 * TopicBandit.recency(3f)),
        ))
        assertTrue("a fresh, well-evidenced dislike must still be respected: $counts",
            counts.getOrDefault("declined", 0) < 40)
    }

    @Test
    fun `certainty stays bounded however long the history`() {
        // Two arms with the same rate but wildly different history must allocate similarly.
        // Unbounded, the longer history would sharpen to a near-certainty that nothing could
        // later move.
        val short = tally(listOf(Arm("a", 15, 5), Arm("b", 5, 15)))
        val long = tally(listOf(Arm("a", 1500, 500), Arm("b", 500, 1500)))
        val diff = kotlin.math.abs(
            short.getOrDefault("a", 0) - long.getOrDefault("a", 0)
        )
        assertTrue("a thousandfold longer history should not change much, differed by $diff",
            diff < 600)
    }

    @Test
    fun `no arms means no draw`() {
        assertNull(TopicBandit.draw(emptyList(), Random(1)))
    }

    @Test
    fun `a single arm is always chosen`() {
        assertEquals("only", TopicBandit.draw(listOf(Arm("only", 0, 0)), Random(1)))
    }
}
