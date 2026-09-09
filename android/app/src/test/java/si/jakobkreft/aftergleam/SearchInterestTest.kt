package si.jakobkreft.aftergleam

import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.SearchRanker

/**
 * Search results were coming back with a predicted interest of zero for everything, which
 * made the personalisation slider inert.
 */
class SearchInterestTest {

    private fun paper(id: String, text: String) = Paper(
        id = id, title = text, abstract = text, authors = listOf("A"),
        categories = listOf("cs.CV"), published = "2026-09-04", updated = "2026-09-04",
    )

    private val rated = List(12) {
        RatedDoc("r$it", "latent diffusion model panoramic image outpainting synthesis $it", 0.9f)
    }

    private val results = listOf(
        paper("hot", "diffusion model for panoramic image outpainting and synthesis"),
        paper("cold", "distributed convex optimisation convergence rates"),
    )

    @Test
    fun `a matching paper gets a non-zero interest score`() {
        val clean = List(40) { "convex optimisation convergence sparse recovery study $it" }
        val hits = SearchRanker.rank(results, "panoramic outpainting", rated, 0.5f, clean)
        val hot = hits.single { it.paper.id == "hot" }
        assertTrue("a paper squarely in the user's field must not score zero, got ${hot.interest}",
            hot.interest > 0.1f)
    }

    @Test
    fun `rated papers must not appear in the negative pool`() {
        val clean = List(40) { "convex optimisation convergence sparse recovery study $it" }
        // The pool the app actually passed included every cached paper, and the user's own
        // rated papers are cached, so the model was told its positives were negatives.
        val poisoned = clean + rated.map { it.text }

        val good = SearchRanker.rank(results, "panoramic outpainting", rated, 0.5f, clean)
            .single { it.paper.id == "hot" }.interest
        val bad = SearchRanker.rank(results, "panoramic outpainting", rated, 0.5f, poisoned)
            .single { it.paper.id == "hot" }.interest

        assertTrue(
            "labelling the user's own liked papers as negatives suppresses interest: " +
                "clean=$good poisoned=$bad",
            bad < good,
        )
    }
}
