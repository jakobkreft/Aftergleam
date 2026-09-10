package si.jakobkreft.aftergleam

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
}
