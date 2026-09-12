package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.Explain
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Scored
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.rank.Tfidf

/**
 * What the "why" chip is allowed to claim.
 *
 * The chip is the only place the model speaks for itself, so a caption that names words the
 * paper has nothing to do with is worse than no caption. These pin the two halves of the
 * fix: a reason has to come out of a paper the reader kept, and when no kept paper resembles
 * the card the chip has to say nothing rather than reach for the model's noisiest features.
 */
class ExplainTest {

    private val kept = listOf(
        "diffusion model panorama outpainting 360 degree image synthesis",
        "panoramic image generation with latent diffusion models",
        "outpainting wide field of view images using diffusion priors",
    )
    private val unrelated = listOf(
        "byzantine robust aggregation for federated optimisation bounds",
        "differential privacy in distributed gradient descent convergence",
        "convex relaxations for combinatorial assignment under budget limits",
    )

    private fun fitted() = Tfidf(minDf = 1).apply { fit(kept + unrelated) }

    private fun paper() = Paper(
        "1", "A paper", "An abstract", listOf("A"), listOf("cs.CV"), "2026-09-01",
        "2026-09-01", "",
    )

    @Test
    fun `the reason names what the two papers share`() {
        val vec = fitted()
        val match = Explain.match(
            vec.transform("latent diffusion for wide panorama image outpainting"),
            kept.map { vec.transform(it) },
            vec,
        )
        assertNotNull(match)
        val words = match!!.terms.joinToString(" ")
        assertTrue("expected the shared subject, got $words", "diffusion" in words)
    }

    @Test
    fun `a paper resembling nothing kept gets no reason at all`() {
        val vec = fitted()
        // This is the case that produced "matches optimal, thereby, known": a card that
        // arrived on venue or freshness, with no overlap with anything the reader kept.
        val match = Explain.match(
            vec.transform("byzantine robust aggregation for federated optimisation bounds"),
            kept.map { vec.transform(it) },
            vec,
        )
        assertNull("nothing kept resembles this, so it must not claim a match", match)
    }

    @Test
    fun `papers the reader only glanced at are not evidence`() {
        val vec = fitted()
        val rated = listOf(
            RatedDoc(null, kept[0], 0.25f),   // opened
            RatedDoc(null, kept[1], 0.4f),    // dwelled
            RatedDoc(null, kept[2], 0.95f),   // liked
        )
        assertEquals(1, Explain.references(rated, vec).size)
    }

    @Test
    fun `a phrase survives even when one of its words is filler`() {
        // The old rule dropped any term containing a filler word, which cost the subjects
        // worth naming. These are the phrases that regressed, kept as a guard.
        val kept = Scored(
            paper = paper(),
            score = 0f,
            relevance = 0.9f,
            slot = Slot.RELEVANCE,
            reasonTerms = listOf("optimal_transport", "state_space", "image_quality"),
        ).why()
        assertTrue("expected the phrases, got $kept", "optimal transport" in kept)
        assertTrue("expected the phrases, got $kept", "state space" in kept)
    }

    @Test
    fun `boilerplate that is filler end to end is dropped`() {
        val why = Scored(
            paper = paper(),
            score = 0f,
            relevance = 0.9f,
            slot = Slot.RELEVANCE,
            reasonTerms = listOf("state_art", "novel_framework", "diffusion_models"),
        ).why()
        assertTrue("expected only the subject, got $why", why == "matches diffusion models")
    }

    @Test
    fun `a word is shown as the phrase it came from`() {
        // "datasets, language, shot" was a real chip. The vectoriser scores the word and the
        // phrase separately, the bare word often wins, and the phrase was then dropped as a
        // repeat of a word already shown.
        val why = Scored(
            paper = paper(),
            score = 0f,
            relevance = 0.9f,
            slot = Slot.RELEVANCE,
            reasonTerms = listOf("shot", "datasets", "few_shot"),
        ).why()
        assertTrue("expected the phrase, got $why", "few shot" in why)
        assertTrue("the bare word must not also appear, got $why", !why.contains(", shot"))
    }

    @Test
    fun `a word and its plural are one word`() {
        // "matches layers, layer, update" and "trajectory, trajectories, call" were both
        // real chips. The vectoriser scores the two forms separately; a reader sees one word
        // printed twice in the only line the card gives the explanation.
        val why = Scored(
            paper = paper(),
            score = 0f,
            relevance = 0.9f,
            slot = Slot.RELEVANCE,
            reasonTerms = listOf("trajectory", "trajectories", "layers", "layer", "solver"),
        ).why()
        assertEquals("matches trajectory, layers, solver", why)
    }

    @Test
    fun `a singular noun ending in s is not mistaken for a plural`() {
        val why = Scored(
            paper = paper(),
            score = 0f,
            relevance = 0.9f,
            slot = Slot.RELEVANCE,
            reasonTerms = listOf("loss", "bias", "solver"),
        ).why()
        assertEquals("matches loss, bias, solver", why)
    }

    @Test
    fun `a singular ending in s still absorbs its own plural`() {
        // The case a stem gets wrong: "bias" reduces to "bia" while "biases" reduces to
        // "bias", so stemming would print both.
        val why = Scored(
            paper = paper(),
            score = 0f,
            relevance = 0.9f,
            slot = Slot.RELEVANCE,
            reasonTerms = listOf("bias", "biases", "solver"),
        ).why()
        assertEquals("matches bias, solver", why)
    }

    @Test
    fun `no kept papers means no reason rather than a guess`() {
        val vec = fitted()
        assertNull(Explain.match(vec.transform(kept[0]), emptyList(), vec))
    }
}
