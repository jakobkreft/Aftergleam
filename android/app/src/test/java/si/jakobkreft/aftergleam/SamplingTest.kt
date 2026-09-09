package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.rank.Sampling
import kotlin.random.Random

class SamplingTest {

    private val items = (1..100).map { it }
    private fun score(i: Int) = (101 - i) / 101f   // item 1 best, item 100 worst

    @Test
    fun `sampling favours high scores without ever fixing the order`() {
        val runs = (1..40).map { seed ->
            Sampling.topK(items, k = 10, temperature = 0.35f, random = Random(seed)) { score(it) }
        }
        val meanRank = runs.flatten().average()
        assertTrue("draws should sit near the top of the list, mean rank was $meanRank",
            meanRank < 25)
        assertTrue("two draws must not be identical, or nothing has changed",
            runs.distinct().size > 30)
    }

    @Test
    fun `low temperature approaches plain top-K`() {
        val cold = Sampling.topK(items, 10, temperature = 0.01f, random = Random(1)) { score(it) }
        assertEquals("at near-zero temperature this is just the top ten",
            (1..10).toList(), cold.sorted())
    }

    @Test
    fun `high temperature reaches the tail`() {
        val hot = (1..30).flatMap {
            Sampling.topK(items, 10, temperature = 5f, random = Random(it)) { score(it) }
        }
        assertTrue("a hot draw must sometimes reach papers the model rates poorly",
            hot.any { it > 70 })
    }

    @Test
    fun `asking for everything returns everything`() {
        assertEquals(items.size, Sampling.topK(items, 500, random = Random(0)) { score(it) }.size)
        assertTrue(Sampling.topK(items, 0, random = Random(0)) { score(it) }.isEmpty())
    }

    @Test
    fun `confidence is pulled toward the prior when evidence is thin`() {
        val confident = 0.95f
        val withSix = Sampling.shrink(confident, evidenceCount = 6)
        val withHundred = Sampling.shrink(confident, evidenceCount = 100)
        assertTrue("six signals should not licence a 0.95, got $withSix", withSix < 0.5f)
        assertTrue("a hundred signals should mostly trust the model, got $withHundred",
            withHundred > 0.7f)
        assertTrue("more evidence must never mean less trust", withHundred > withSix)
    }

    @Test
    fun `shrinkage keeps the ordering it was given`() {
        // It must temper confidence without reshuffling which paper is better.
        val a = Sampling.shrink(0.9f, 10)
        val b = Sampling.shrink(0.4f, 10)
        assertTrue("shrinkage must be monotonic", a > b)
    }
}
