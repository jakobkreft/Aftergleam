package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Bridge
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.rank.Weights

class BridgeTest {

    @Test
    fun `suggested categories sit outside what the user already follows`() {
        val subs = setOf("cs.CV", "cs.LG")
        val out = Bridge.candidatesFor(subs, dayOfYear = 100)
        assertTrue("must suggest something", out.isNotEmpty())
        assertTrue("must not suggest a category already followed: $out",
            out.none { it in subs })
    }

    @Test
    fun `suggestions rotate across days`() {
        val subs = setOf("cs.CV", "cs.LG")
        val seen = (1..30).map { Bridge.candidatesFor(subs, it).first() }.toSet()
        assertTrue("the same neighbour every morning is not a bridge, got $seen",
            seen.size > 1)
    }

    @Test
    fun `an unknown category set still yields suggestions`() {
        val out = Bridge.candidatesFor(setOf("econ.EM"), dayOfYear = 5)
        assertTrue("must fall back rather than give up: $out", out.isNotEmpty())
    }

    @Test
    fun `subscribing to everything known cannot produce a self-referential bridge`() {
        val everything = setOf("cs.LG", "cs.CV", "stat.ML", "math.OC", "q-bio.NC", "cs.CL")
        val out = Bridge.candidatesFor(everything, dayOfYear = 7)
        assertTrue("a bridge into your own field is not a bridge: $out",
            out.none { it in everything })
    }

    private fun paper(id: String, cat: String, text: String) = Paper(
        id = id, title = text, abstract = text, authors = listOf("A"),
        categories = listOf(cat), published = "2026-09-04", updated = "2026-09-04",
    )

    @Test
    fun `the bridge slot fills once outside papers are in the pool`() {
        val subscribed = setOf("cs.CV")
        val candidates = (1..12).map {
            paper("in$it", "cs.CV", "diffusion model for image synthesis variant $it")
        } + listOf(
            paper("out1", "q-bio.NC", "predictive coding in visual cortex and generative models"),
            paper("out2", "astro-ph.IM", "image reconstruction for radio interferometry"),
        )
        val rated = listOf(
            RatedDoc(null, "latent diffusion image synthesis", 0.9f),
            RatedDoc(null, "denoising diffusion probabilistic models", 0.9f),
            RatedDoc(null, "panoramic outpainting with diffusion", 0.9f),
        )
        val out = Ranker(Weights(explorationRate = 0f, diversity = 0f)).digest(
            candidates = candidates,
            rated = rated,
            seen = emptySet(),
            subscribed = subscribed,
            size = 6,
            negativePool = (1..40).map { "convex optimisation convergence study $it" },
        )
        val bridge = out.filter { it.slot == Slot.BRIDGE }
        assertEquals("exactly one bridge card", 1, bridge.size)
        assertTrue("the bridge must come from outside the user's categories",
            bridge.first().paper.categories.none { it in subscribed })
        assertTrue("and it must say so: ${bridge.first().why()}",
            bridge.first().why().contains("outside"))
    }
}
