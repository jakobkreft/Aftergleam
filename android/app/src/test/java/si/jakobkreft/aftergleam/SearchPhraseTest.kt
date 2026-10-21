package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.ChemRxivApi
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.SearchTerms
import si.jakobkreft.aftergleam.rank.SearchRanker

/**
 * Several words in one search: every one required, quotes for a phrase, papers that say the
 * words together first, and a keyword to watch that knows a name from a combination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchPhraseTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun paper(id: String, title: String, abstract: String = "An abstract.") =
        Paper(id, title, abstract, listOf("A. Chen"), listOf("cs.LG"), "2026-10-01", "2026-10-01")

    @Test
    fun `a query is its quoted phrases and its other words, small words aside`() {
        val t = SearchTerms.of("\"theory of mind\" in large models")
        assertEquals(listOf("theory of mind"), t.phrases)
        assertEquals(listOf("large", "models"), t.words)
        assertTrue(t.foundIn("Theory of Mind in Large Language Models"))
        assertFalse("the phrase, not its words apart", t.foundIn("A theory of the mind in large models"))
        assertEquals(emptyList<String>(), SearchTerms.of(" of the ").all)
    }

    @Test
    fun `the phone finds a quoted phrase, which it used to search for with the quotes on`() {
        val db = Db(ctx)
        db.upsertPapers(listOf(
            paper("together", "Gene editing in wheat"),
            paper("apart", "Editing the gene regulatory network"),
        ))
        assertEquals(listOf("together"), db.searchLocal("\"gene editing\"", savedOnly = false).map { it.id })
        assertEquals("unquoted, every word anywhere", setOf("together", "apart"),
            db.searchLocal("gene editing", savedOnly = false).map { it.id }.toSet())
    }

    @Test
    fun `papers that say the words together come first`() {
        val hits = SearchRanker.rank(
            listOf(
                paper("apart", "Mechanism design for attention markets", "We study the mechanism of attention allocation."),
                paper("together", "Sparse attention mechanism for long documents"),
            ),
            query = "attention mechanism",
            rated = emptyList(),
            personalisation = 0f,
        )
        assertEquals("together", hits.first().paper.id)
    }

    private fun results(n: Int, text: (Int) -> String) = (1..n).map { paper("p$it", text(it)) }

    @Test
    fun `a name is watched as a phrase, with its words apart offered beside it`() {
        val found = results(10) { if (it <= 9) "An attention mechanism for vision $it" else "Attention as a mechanism $it" }
        assertEquals(Keywords.Watch("attention mechanism", "attention + mechanism"),
            Keywords.watchFor("attention mechanism", found))
        val mind = results(5) { "Theory of mind in language models $it" }
        assertEquals("small words stay inside a phrase", Keywords.Watch("theory of mind", "theory + mind"),
            Keywords.watchFor("theory of mind", mind))
    }

    @Test
    fun `separate ideas are watched together, and the phrase is offered beside them`() {
        val found = results(6) { "Cholesterol synthesis falls under hypoxia $it" }
        assertEquals(Keywords.Watch("cholesterol + hypoxia", "cholesterol hypoxia"),
            Keywords.watchFor("cholesterol hypoxia", found))
    }

    @Test
    fun `only the words that belong together are joined`() {
        val found = results(8) { "Intermittent hypoxia in obstructive sleep apnea patients $it" }
        assertEquals(Keywords.Watch("hypoxia + sleep apnea", "hypoxia sleep apnea"),
            Keywords.watchFor("hypoxia sleep apnea", found))
        val crispr = results(8) { "CRISPR screens and gene editing outcomes $it" }
        assertEquals("CRISPR + gene editing", Keywords.watchFor("CRISPR gene editing", crispr)?.keyword)
    }

    @Test
    fun `quotes, single words and no evidence are taken as they come`() {
        assertEquals("quotes are the reader saying it", Keywords.Watch("sleep apnea + hypoxia", null),
            Keywords.watchFor("\"sleep apnea\" and hypoxia", emptyList()))
        assertEquals(Keywords.Watch("H-Net", null), Keywords.watchFor("H-Net", emptyList()))
        assertEquals("a single letter is the start of a name, written as the reader wrote it",
            Keywords.Watch("C. elegans", null), Keywords.watchFor("C. elegans", emptyList()))
        assertEquals("T cell + exhaustion", Keywords.watchFor("T cell exhaustion", emptyList())?.keyword)
        assertEquals("nothing found to go by: every word its own",
            Keywords.Watch("lanosterol + hypoxia", "lanosterol hypoxia"),
            Keywords.watchFor("lanosterol hypoxia", emptyList()))
        assertNull(Keywords.watchFor("of the", emptyList()))
    }

    @Test
    fun `a possessive is one name however it is written`() {
        val k = "Alzheimer's disease"
        for (text in listOf("in Alzheimer's disease", "in Alzheimer\u2019s disease", "in Alzheimers disease", "in Alzheimer disease")) {
            assertTrue(text, Keywords.mentions(text, k))
        }
        assertFalse("still a whole word", Keywords.mentions("in Alzheimerish disease", k))
        assertTrue("and an apostrophe inside a name", Keywords.mentions("as O'Brien showed", "O'Brien"))
        val found = results(6) { "Early signs of Alzheimer's disease in speech $it" }
        assertEquals(Keywords.Watch("Alzheimer's disease", "Alzheimer's + disease"),
            Keywords.watchFor("Alzheimer's disease", found))
    }

    @Test
    fun `a search that found an author is not offered as a keyword`() {
        val byHim = (1..5).map {
            Paper("a$it", "World models $it", "We learn representations.", listOf("Yann LeCun", "A. Chen"),
                listOf("cs.LG"), "2026-10-01", "2026-10-01")
        }
        assertNull(Keywords.watchFor("Yann LeCun", byHim))
    }

    @Test
    fun `a phrase in curly quotes is a phrase`() {
        val t = SearchTerms.of("\u201csleep apnea\u201d hypoxia")
        assertEquals(listOf("sleep apnea"), t.phrases)
        assertEquals(listOf("hypoxia"), t.words)
    }

    @Test
    fun `an abstract loses its own heading, and keeps its sections`() {
        assertEquals("The first publication.",
            ChemRxivApi.stripJats("<jats:title>Abstract</jats:title>\n <jats:p>The first publication.</jats:p>"))
        assertEquals("Background Difficulty in diagnosis.",
            ChemRxivApi.stripJats("<jats:title>ABSTRACT</jats:title><jats:sec><jats:title>Background</jats:title><jats:p>Difficulty in diagnosis.</jats:p></jats:sec>"))
    }
}
