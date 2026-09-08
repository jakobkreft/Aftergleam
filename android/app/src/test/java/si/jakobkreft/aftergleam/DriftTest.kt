package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Drift
import si.jakobkreft.aftergleam.data.Paper

class DriftTest {

    private fun rated(text: String, interest: Float, authors: List<String> = listOf("A. Author")) =
        Paper(
            id = text.hashCode().toString(), title = text, abstract = text,
            authors = authors, categories = listOf("cs.CV"),
            published = "2026-09-01", updated = "2026-09-01",
        ) to interest

    @Test
    fun `a shift in reading shows up as rising and falling topics`() {
        val earlier = List(6) { rated("generative adversarial network image synthesis $it", 0.9f) }
        val recent = List(6) { rated("diffusion transformer video generation model $it", 0.9f) }
        val r = Drift.compute(recent, earlier, explorationJudged = 0, explorationLiked = 0)

        // Every term in a window carries the same weight here, so which three surface is a
        // tie-break and not worth asserting. What matters is that risers come from the new
        // vocabulary and fallers from the old one.
        val newWords = setOf("diffusion", "transformer", "video", "generation", "model")
        val oldWords = setOf("generative", "adversarial", "network", "image", "synthesis")
        assertTrue("risers must come from the recent vocabulary, got ${r.rising}",
            r.rising.isNotEmpty() && r.rising.all { it in newWords })
        assertTrue("fallers must come from the earlier vocabulary, got ${r.falling}",
            r.falling.isNotEmpty() && r.falling.all { it in oldWords })
    }

    @Test
    fun `reading more of everything is not reported as a rise in everything`() {
        val earlier = List(6) { rated("diffusion model image synthesis $it", 0.9f) }
        // Same topic, simply twice as much of it.
        val recent = List(12) { rated("diffusion model image synthesis $it", 0.9f) }
        val r = Drift.compute(recent, earlier, 0, 0)
        assertTrue("comparing shares, not counts, should keep this flat: ${r.rising}",
            r.rising.none { it == "diffusion" })
    }

    @Test
    fun `too little history is reported as such rather than invented`() {
        val r = Drift.compute(
            recent = List(2) { rated("diffusion $it", 0.9f) },
            earlier = List(1) { rated("gan $it", 0.9f) },
            explorationJudged = 0, explorationLiked = 0,
        )
        assertTrue("two papers is not a trend", r.thin)
    }

    @Test
    fun `disliked papers do not shape the picture`() {
        val earlier = List(6) { rated("diffusion image synthesis $it", 0.9f) }
        val recent = List(6) { rated("quantum chromodynamics lattice $it", 0.05f) }
        val r = Drift.compute(recent, earlier, 0, 0)
        assertTrue("a rejected topic must not count as rising: ${r.rising}",
            r.rising.none { it.contains("quantum") || it.contains("lattice") })
    }

    @Test
    fun `repeatedly liked authors surface`() {
        val papers = listOf(
            rated("panoramic diffusion outpainting one", 0.9f, listOf("Jane Roe", "X Y")),
            rated("panoramic diffusion outpainting two", 0.9f, listOf("Jane Roe")),
            rated("something else entirely here", 0.9f, listOf("Other Person")),
        )
        val r = Drift.compute(papers, emptyList(), 0, 0)
        assertTrue("expected the repeated author, got ${r.recurringAuthors}",
            r.recurringAuthors.any { it.startsWith("Jane Roe") })
        assertTrue("a single appearance is not a pattern",
            r.recurringAuthors.none { it.startsWith("Other Person") })
    }

    @Test
    fun `the exploration note is honest when exploration is failing`() {
        val r = Drift.compute(emptyList(), emptyList(), explorationJudged = 10, explorationLiked = 0)
        val note = r.explorationNote()
        assertTrue("a total miss must be stated: $note",
            note != null && note.contains("wrong direction"))
        assertTrue("the count must be of cards the user judged, not cards shown: $note",
            note!!.contains("you rated"))
    }

    @Test
    fun `no exploration note when there is not enough to judge`() {
        assertEquals(null, Drift.compute(emptyList(), emptyList(), 3, 0).explorationNote())
    }
}
