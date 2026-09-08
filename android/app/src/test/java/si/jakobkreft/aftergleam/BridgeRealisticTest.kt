package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.rank.Weights

/**
 * The bridge slot at production settings: twenty-five cards, exploration and diversity on,
 * a candidate pool dominated by the user's own categories.
 *
 * The synthetic tests use small pools where the slot is easy to fill. This one checks it
 * survives realistic proportions, where fewer than a fifth of candidates sit outside the
 * user's fields and the diversity pass has already taken the best of the rest.
 */
class BridgeRealisticTest {

    private fun paper(id: String, cats: List<String>, text: String) = Paper(
        id = id, title = text, abstract = text, authors = listOf("A"),
        categories = cats, published = "2026-09-04", updated = "2026-09-04",
    )

    @Test
    fun `the bridge slot fills at production settings`() {
        val inside = (1..336).map {
            paper("in$it", listOf("cs.CV", "cs.LG"),
                "diffusion model image synthesis generation variant $it")
        }
        val outside = (1..64).map {
            paper("out$it", listOf("math.OC", "math.PR"),
                "stochastic optimisation convergence analysis variant $it")
        }
        val rated = (1..45).map {
            RatedDoc(null, "latent diffusion image synthesis panoramic outpainting $it", 0.9f)
        }
        val out = Ranker(Weights(explorationRate = 0.2f, diversity = 0.3f)).digest(
            candidates = inside + outside,
            rated = rated,
            seen = emptySet(),
            subscribed = setOf("cs.CV", "cs.LG"),
            size = 25,
            negativePool = (1..200).map { "unrelated numerical linear algebra study $it" },
        )
        val bridge = out.filter { it.slot == Slot.BRIDGE }
        assertEquals("expected exactly one bridge card, slots were " +
            out.groupingBy { it.slot }.eachCount(), 1, bridge.size)
        assertTrue("the bridge must come from outside the user's fields",
            bridge.single().paper.categories.none { it in setOf("cs.CV", "cs.LG") })
        assertEquals("the digest must still be full", 25, out.size)
    }
}
