package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.ui.SurveyState

/**
 * When the survey is allowed to say it has heard enough.
 *
 * The bar is the ranker's own cold start threshold, so this is not a matter of taste: below
 * it the digest falls back to recency and venue, and the screen should not claim otherwise.
 * The case that used to be wrong is an import, where the reader hands over a hundred papers
 * they have actually read and is then told a survey of twelve was a thin start.
 */
class SurveyStateTest {

    @Test
    fun `an empty survey is not enough`() {
        assertFalse(SurveyState().enough)
    }

    @Test
    fun `an imported library alone can clear the bar`() {
        assertTrue(SurveyState(seeded = 8).enough)
    }

    @Test
    fun `a library short of the bar still needs the survey`() {
        assertFalse(SurveyState(seeded = 2).enough)
    }

    private fun deck(n: Int) = List(n) {
        si.jakobkreft.aftergleam.data.Taste.Probe("p", "cs.CV") to
            si.jakobkreft.aftergleam.data.Paper("id$it", "t", "a", emptyList(), listOf("cs.CV"), "2026-01-01", "2026-01-01")
    }

    @Test
    fun `the total is shown once it is certain`() {
        // A busy field fills the deck from the first card: twelve, and it will stay twelve.
        assertEquals(12, SurveyState(expected = 12, deck = deck(12), loading = true).planned)
        // Answering moves cards from the deck to seen; the total does not change.
        assertEquals(12, SurveyState(expected = 12, deck = deck(9), seen = 3, loading = true).planned)
        // Loading finished with fewer papers than planned: a small field, and now certain.
        assertEquals(5, SurveyState(expected = 12, deck = deck(4), seen = 1, loading = false).planned)
    }

    @Test
    fun `no total while it could still change`() {
        // Five cards so far and more loading: the deck may grow to twelve or stop at five.
        assertEquals(0, SurveyState(expected = 12, deck = deck(5), loading = true).planned)
        assertEquals("not started", 0, SurveyState().planned)
    }

    @Test
    fun `liking nothing adds no subjects of its own`() {
        // It used to add cs.LG, so a law reader who skipped the survey got a digest of
        // machine learning papers and no law.
        assertTrue(si.jakobkreft.aftergleam.data.Taste.categoriesFrom(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a category two liked papers share is added`() {
        val p = { id: String -> si.jakobkreft.aftergleam.data.Paper(id, "t", "a", emptyList(),
            listOf("lawarchive:criminal law", "lawarchive:evidence"), "2026-01-01", "2026-01-01") }
        assertEquals(setOf("lawarchive:criminal law", "lawarchive:evidence"),
            si.jakobkreft.aftergleam.data.Taste.categoriesFrom(listOf(p("a"), p("b")), emptyList()))
    }
}
