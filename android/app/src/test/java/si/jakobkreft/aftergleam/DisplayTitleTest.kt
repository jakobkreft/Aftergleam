package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper

/**
 * arXiv titles are LaTeX source, and a real search result rendered as
 * "Cylin-Painting: Seamless {360\textdegree} Panoramic Image Generation", which reads as an
 * app bug rather than as the archive's own formatting.
 */
class DisplayTitleTest {

    private fun titled(t: String) = Paper(
        id = "1", title = t, abstract = t, authors = listOf("A"),
        categories = listOf("cs.CV"), published = "2026-09-01", updated = "2026-09-01",
    )

    @Test
    fun `latex braces and commands are removed from titles`() {
        val shown = titled("Cylin-Painting: Seamless {360\\textdegree} Panoramic Image").displayTitle
        assertTrue("braces must go: $shown", !shown.contains("{") && !shown.contains("}"))
        assertTrue("commands must go: $shown", !shown.contains("textdegree"))
        assertTrue("the actual words must survive: $shown", shown.contains("Panoramic Image"))
        assertTrue("the number must survive: $shown", shown.contains("360"))
    }

    @Test
    fun `words are not glued together where markup was removed`() {
        val shown = titled("A {DEEP} Look at {G}raphs").displayTitle
        assertEquals("A DEEP Look at Graphs", shown)
    }

    @Test
    fun `plain titles are left alone`() {
        val plain = "Denoising Diffusion Probabilistic Models"
        assertEquals(plain, titled(plain).displayTitle)
    }

    @Test
    fun `inline maths does not swallow the rest of the title`() {
        val shown = titled("On ${'$'}O(n \\log n)${'$'} Sorting").displayTitle
        assertTrue("expected the tail to survive: $shown", shown.contains("Sorting"))
        assertTrue("expected no dollar signs: $shown", !shown.contains("${'$'}"))
    }
}
