package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Resurfaced

class ResurfaceTest {

    private val paper = Paper(
        id = "2503.00710", title = "Proteina", abstract = "a",
        authors = listOf("A"), categories = listOf("cs.LG"),
        published = "2025-03-02", updated = "2025-03-04",
        comments = "Accepted to ICLR 2026",
    )

    @Test
    fun `headline names the month the paper was passed over`() {
        val r = Resurfaced(paper, "ICLR 2026", "2025-03-14")
        assertEquals("You passed on this in March", r.headline())
        assertEquals("It was accepted to ICLR 2026.", r.detail())
    }

    @Test
    fun `wording is discovery rather than blame`() {
        val r = Resurfaced(paper, "ICLR 2026", "2025-03-14")
        val text = r.headline() + " " + r.detail()
        // The design note is explicit that this feature dies on tone.
        for (word in listOf("missed", "should", "wrong", "mistake", "failed")) {
            assertTrue("copy must not scold, found '$word' in: $text",
                !text.lowercase().contains(word))
        }
    }

    @Test
    fun `an unparseable date degrades instead of crashing`() {
        val r = Resurfaced(paper, "ICLR 2026", "not-a-date")
        assertTrue(r.headline().isNotBlank())
    }
}
