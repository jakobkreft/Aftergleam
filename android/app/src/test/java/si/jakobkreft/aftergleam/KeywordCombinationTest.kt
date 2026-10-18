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
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.ui.Combining
import kotlin.random.Random

/**
 * Keywords that combine words, every one of which a paper must mention. The example is the
 * reader's own: sterols, lanosterol, hypoxia and sleep apnea, some of which only mean
 * something together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeywordCombinationTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `typed AND joins words, and OR makes separate keywords`() {
        assertEquals(listOf("lanosterol + hypoxia"), Keywords.split("lanosterol AND hypoxia"))
        assertEquals("written the way the chip shows it", "lanosterol + hypoxia",
            Keywords.normalise("  lanosterol   +  hypoxia "))
        assertEquals(listOf("hypoxia", "hypoxic"), Keywords.split("hypoxia OR hypoxic"))
        assertEquals("AND before OR, as in PubMed", listOf("a", "b + c"), Keywords.split("a OR b AND c"))
        assertEquals("only capitals are operators", listOf("salt and pepper"), Keywords.split("salt and pepper"))
    }

    @Test
    fun `a bracketed search becomes the keywords it describes`() {
        assertEquals(
            listOf("sterols + hypoxia", "sterols + sleep apnea", "lanosterol + hypoxia", "lanosterol + sleep apnea"),
            Keywords.split("(sterols OR lanosterol) AND (hypoxia OR sleep apnea)"),
        )
        assertEquals("never more than a reader may keep", Keywords.MAX,
            Keywords.split("(a1 OR b1 OR c1) AND (d1 OR e1 OR f1)").size)
        assertEquals("without AND or OR a bracket is part of the name", listOf("IL-6 (interleukin 6)"),
            Keywords.split("IL-6 (interleukin 6)"))
    }

    @Test
    fun `plus signs and slashes inside a word stay part of it`() {
        assertEquals(listOf("Ca2+"), Keywords.terms(Keywords.normalise("Ca2+")!!))
        assertEquals(listOf("Ca2+", "hypoxia"), Keywords.terms("Ca2+ + hypoxia"))
        assertFalse(Keywords.isCombined("PD-1/PD-L1"))
        assertTrue(Keywords.isCombined("lanosterol + hypoxia"))
    }

    @Test
    fun `repeats go, and a keyword cannot grow into a query language`() {
        assertEquals("hypoxia", Keywords.normalise("hypoxia + Hypoxia"))
        val many = (1..10).joinToString(" AND ") { "w$it" }
        assertEquals(Keywords.MAX_TERMS, Keywords.terms(Keywords.normalise(many)!!).size)
        assertNull(Keywords.normalise(" + "))
    }

    private val both = "Lanosterol accumulates under hypoxia in liver cells"
    private val sterolOnly = "Lanosterol synthase structure"

    @Test
    fun `every word must be mentioned`() {
        assertTrue(Keywords.mentions(both, "lanosterol + hypoxia"))
        assertFalse("one of two is not both", Keywords.mentions(sterolOnly, "lanosterol + hypoxia"))
        assertTrue("a single word is as before", Keywords.mentions(both, "hypoxia"))
    }

    @Test
    fun `a label names each word, so they can be found on the page`() {
        assertEquals("“lanosterol” and “hypoxia”", Keywords.describe("lanosterol + hypoxia"))
        assertEquals("“a”, “b” and “c”", Keywords.describe("a + b + c"))
        assertEquals("“H-Net”", Keywords.describe("H-Net"))
        assertEquals(listOf(0..9, 25..31),
            Keywords.occurrences("Lanosterol, and later in hypoxia", listOf("lanosterol + hypoxia")))
    }

    @Test
    fun `arXiv is asked for every word`() {
        assertEquals("all:\"H-Net\"", ArxivApi.keywordQuery("H-Net"))
        assertEquals("all:\"lanosterol\" AND all:\"hypoxia\"", ArxivApi.keywordQuery("lanosterol + hypoxia"))
    }

    @Test
    fun `the phone finds combinations among the papers it holds`() {
        val db = Db(ctx)
        fun p(id: String, title: String) = Paper(id, title, "An abstract.", listOf("A"), listOf("q-bio.CB"),
            "2026-10-01", "2026-10-01")
        db.upsertPapers(listOf(
            p("a", "Lanosterol and hypoxia in the liver"),
            p("b", "Lanosterol synthase"),
            p("c", "Cholesterol under hypoxia"),
        ))
        assertEquals(listOf("a"), db.keywordCandidates(listOf("lanosterol + hypoxia"), "2026-09-01").map { it.id })
        assertEquals(listOf("c"), db.keywordCandidates(listOf("hypoxia + cholesterol"), "2026-09-01").map { it.id })
    }

    @Test
    fun `watching a search requires every word, as the search did`() {
        val stop = ArxivApi.STOP_WORDS
        assertEquals("lanosterol + hypoxia", Keywords.fromSearch("lanosterol hypoxia", stop))
        assertEquals("a quoted phrase stays one word", "sleep apnea + hypoxia",
            Keywords.fromSearch("\"sleep apnea\" and hypoxia", stop))
        assertEquals("one word is one word", "lanosterol", Keywords.fromSearch("lanosterol", stop))
        assertEquals("more words than a keyword holds are watched as the phrase",
            "protein folding kinetics membrane channel dynamics",
            Keywords.fromSearch("protein folding kinetics membrane channel dynamics", stop))
    }

    @Test
    fun `a combined keyword brings its papers to the top of the digest, labelled`() {
        fun paper(id: String, text: String) = Paper(id, "Paper $id: $text", "We study $text.",
            listOf("A"), listOf("q-bio.CB"), "2026-10-01", "2026-10-01")
        val ordinary = (1..40).map { paper("o$it", "cell biology result $it") }
        val match = paper("m", "lanosterol accumulation under hypoxia")
        val half = paper("h", "lanosterol synthase alone")
        val cards = Ranker().digest(
            candidates = ordinary + match + half, rated = emptyList(), seen = emptySet(),
            subscribed = setOf("q-bio.CB"), size = 20, random = Random(1),
            negativePool = emptyList(), keywords = listOf("lanosterol + hypoxia"),
        )
        val labelled = cards.filter { it.keyword != null }
        assertEquals(listOf("m"), labelled.map { it.paper.id })
        assertEquals("mentions “lanosterol” and “hypoxia”", labelled.single().why())
    }

    private val mine = listOf("sterols", "lanosterol", "hypoxia", "sleep apnea")

    @Test
    fun `combining moves a keyword in, and taking it out puts it back`() {
        val open = Combining(mine, open = 1).add("hypoxia")
        assertEquals(listOf("sterols", "lanosterol + hypoxia", "sleep apnea"), open.keywords)
        assertEquals("lanosterol + hypoxia", open.keyword)
        assertEquals("back where it was, nothing lost", mine, open.takeOut("hypoxia").keywords)

        val earlier = Combining(mine, open = 3).add("sterols")
        assertEquals("the open keyword is followed when one before it leaves",
            "sleep apnea + sterols", earlier.keyword)
    }

    @Test
    fun `a word typed in only goes, and the last word stays`() {
        val typed = Combining(mine, open = 3).type(listOf("hypoxic"))
        assertEquals("sleep apnea + hypoxic", typed.keyword)
        assertEquals(mine, typed.takeOut("hypoxic").keywords)

        val saved = Combining(listOf("sleep apnea + hypoxic"), open = 0)
        assertEquals("a word of a saved combination comes back as a keyword",
            listOf("sleep apnea", "hypoxic"), saved.takeOut("hypoxic").keywords)
        assertEquals(listOf("hypoxia"), Combining(listOf("hypoxia"), 0).takeOut("hypoxia").keywords)
    }

    @Test
    fun `what is typed and not yet added is kept`() {
        val saved = Combining(mine, open = 1).copy(draft = "hypoxic").commitDraft()
        assertEquals("lanosterol + hypoxic", saved.keyword)
        assertEquals("", saved.draft)
        assertEquals("typed, so taking it out only removes it", mine, saved.takeOut("hypoxic").keywords)
        assertEquals("commas and AND separate words", listOf("hypoxia", "hypoxic", "sleep apnea"),
            Combining(mine, 1).copy(draft = "hypoxia, hypoxic AND sleep apnea").draftWords)
        assertEquals("nothing typed changes nothing", mine, Combining(mine, 1).copy(draft = "  ").commitDraft().keywords)
    }

    @Test
    fun `a combination holds at most four words`() {
        val three = Combining(listOf("a1 + b1 + c1", "d1 + e1", "f1"), open = 0)
        assertFalse(three.fits("d1 + e1"))
        assertTrue(three.fits("f1"))
        assertEquals(three, three.add("d1 + e1"))
        assertTrue(three.add("f1").full)
    }
}
