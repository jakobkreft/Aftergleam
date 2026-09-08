package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.SearchRanker

class SearchRankerTest {

    private fun paper(id: String, text: String) = Paper(
        id = id, title = text, abstract = text,
        authors = listOf("A"), categories = listOf("cs.CV"),
        published = "2026-09-01", updated = "2026-09-01",
    )

    private val results = listOf(
        paper("onTopicMatch", "panoramic diffusion outpainting for wide field of view images"),
        paper("queryOnly", "panoramic camera calibration using classical geometry"),
        paper("interestOnly", "latent diffusion models for image synthesis and generation"),
        paper("neither", "convergence bounds for distributed convex optimisation"),
    )

    private val rated = listOf(
        RatedDoc(null, "latent diffusion image synthesis generation", 0.95f),
        RatedDoc(null, "denoising diffusion probabilistic models for images", 0.95f),
        RatedDoc(null, "diffusion outpainting panoramic image generation", 0.9f),
    )

    private val negatives = (1..30).map {
        "study $it of sparse linear algebra preconditioning and numerical stability"
    }

    @Test
    fun `at zero personalisation the query decides the order`() {
        val hits = SearchRanker.rank(results, "panoramic camera calibration geometry",
            rated, personalisation = 0f, negativePool = negatives)
        assertEquals("the closest query match must lead", "queryOnly", hits.first().paper.id)
    }

    @Test
    fun `at full personalisation the model decides the order`() {
        val hits = SearchRanker.rank(results, "panoramic camera calibration geometry",
            rated, personalisation = 1f, negativePool = negatives)
        assertTrue(
            "expected a paper matching the user's reading to lead, got ${hits.first().paper.id}",
            hits.first().paper.id in setOf("interestOnly", "onTopicMatch"),
        )
    }

    @Test
    fun `the slider actually changes the ordering`() {
        val q = "panoramic camera calibration geometry"
        val neutral = SearchRanker.rank(results, q, rated, 0f, negatives).map { it.paper.id }
        val personal = SearchRanker.rank(results, q, rated, 1f, negatives).map { it.paper.id }
        assertTrue("a control that changes nothing is worse than no control",
            neutral != personal)
    }

    @Test
    fun `with too few ratings it degrades to plain query relevance`() {
        val hits = SearchRanker.rank(results, "panoramic diffusion outpainting",
            rated.take(1), personalisation = 1f, negativePool = negatives)
        // No model, so every interest score is zero and nothing crashes or reorders wildly.
        assertEquals(results.size, hits.size)
        assertTrue(hits.all { it.interest == 0f })
    }

    @Test
    fun `empty results are handled`() {
        assertTrue(SearchRanker.rank(emptyList(), "anything", rated).isEmpty())
    }
}
