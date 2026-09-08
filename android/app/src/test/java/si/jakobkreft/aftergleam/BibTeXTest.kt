package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.BibTeX

/**
 * Parses the same file the Python prototype was measured against, so the Kotlin port can be
 * compared like for like rather than merely "looking right".
 */
class BibTeXTest {

    private fun realLibrary(): String =
        javaClass.classLoader!!.getResourceAsStream("literatura.bib")!!
            .bufferedReader().readText()

    @Test
    fun `parses the real library to the same entry count as the prototype`() {
        val entries = BibTeX.parse(realLibrary())
        println("parsed ${entries.size} entries")
        assertEquals("prototype measured 104 entries", 104, entries.size)
        assertTrue("titles must survive", entries.count { it.title.isNotBlank() } > 90)
    }

    @Test
    fun `nested braces and latex in titles survive`() {
        val e = BibTeX.parseBibtex(
            "@article{k, title = {A {DEEP} Look at {G}raphs}, year = {2024}}"
        ).single()
        assertEquals("A DEEP Look at Graphs", e.title)
    }

    @Test
    fun `recovers arxiv ids from eprint, doi and url forms`() {
        val src = """
            @misc{a, title={T}, eprint={2503.00710}, archivePrefix={arXiv}}
            @article{b, title={T}, doi={10.48550/arXiv.math/0211159}}
            @article{c, title={T}, url={https://arxiv.org/abs/1706.03762v5}}
            @article{d, title={No arXiv here}, doi={10.1113/jphysiol.1952.sp004764}}
        """.trimIndent()
        val e = BibTeX.parseBibtex(src)
        assertEquals("2503.00710", e[0].arxivId)
        // Pre-2007 identifiers contain a slash; dropping the archive breaks the lookup.
        assertEquals("math/0211159", e[1].arxivId)
        assertEquals("1706.03762", e[2].arxivId)
        assertEquals("a real journal paper has no arXiv id", "", e[3].arxivId)
    }

    @Test
    fun `similarity accepts a true match and rejects an adjacent one`() {
        val q = "Denoising diffusion probabilistic models"
        assertTrue(BibTeX.similarity(q, "Denoising Diffusion Probabilistic Models") > 0.9f)
        assertTrue(
            "an adjacent paper must fall below the 0.6 threshold",
            BibTeX.similarity(q, "On the Importance of Noise Schedules in Diffusion") < 0.6f,
        )
    }

    @Test
    fun `latex is stripped before searching`() {
        val cleaned = BibTeX.cleanTitle("Any-size-diffusion: ${'$'}\\infty${'$'}-Diff scaling")
        assertTrue("no backslash commands should remain: $cleaned", !cleaned.contains("\\"))
        assertTrue(cleaned.contains("Diff"))
    }

    @Test
    fun `parses RIS as well as bibtex`() {
        val ris = "TY  - JOUR\nTI  - Some Paper\nPY  - 2024\n" +
            "UR  - https://arxiv.org/abs/2404.01234\nER  - \n"
        val e = BibTeX.parse(ris).single()
        assertEquals("Some Paper", e.title)
        assertEquals("2404.01234", e.arxivId)
    }
}
