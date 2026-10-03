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
import si.jakobkreft.aftergleam.data.Backup
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Slot
import kotlin.random.Random

/**
 * Keywords are found as written. Each case where the first, topic-like rule went wrong on real
 * papers (prototype/keyword_audit.py) is checked here, with what the settings page promises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeywordsTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun mentions(text: String, keyword: String) = Keywords.mentions(text, keyword)

    @Test
    fun `a name is found however its parts are joined, and nothing else is`() {
        assertTrue(mentions("We propose H-Net, a dynamic chunking model", "H-net"))
        assertTrue(mentions("HNet learns segmentation end to end", "H-Net"))
        assertTrue(mentions("an H Net architecture", "H-Net"))
        assertFalse("the first rule matched this on 'net'", mentions("a U-Net for segmentation", "H-Net"))
        assertFalse(mentions("net conversion of catabolic pathways", "H-Net"))
        assertFalse("never inside a longer word", mentions("the subnetwork of a GH-Net", "H-Net"))
    }

    @Test
    fun `the examples the settings page gives hold`() {
        assertTrue(mentions("a UNet backbone for segmentation", "U-Net"))
        assertTrue(mentions("small RNAs in plants", "RNA"))
        assertFalse(mentions("an mRNA vaccine", "RNA"))
    }

    @Test
    fun `short and hyphenated names that the first rule dropped`() {
        assertTrue("RL was reduced to nothing", mentions("offline RL from logged data", "RL"))
        assertFalse(mentions("URL parsing", "RL"))
        assertTrue(mentions("cloud-free Sentinel-2 mosaics", "Sentinel-2"))
        assertTrue(mentions("Sentinel 2 imagery", "Sentinel-2"))
        assertFalse("'sentinel sites' is not the satellite",
            mentions("wastewater plants as sentinel sites", "Sentinel-2"))
        assertTrue(mentions("responses of \\textit{C.~elegans} to odours", "C. elegans"))
        assertTrue(mentions("in C.elegans neurons", "C. elegans"))
        assertFalse("a different worm", mentions("Pristionchus elegans", "C. elegans"))
    }

    @Test
    fun `capitals and plurals are ignored, both ways`() {
        assertTrue(mentions("we fine-tune with lora adapters", "LoRA"))
        assertTrue(mentions("perovskites for solar cells", "perovskite"))
        assertTrue(mentions("a satellite image of a city", "satellite images"))
        assertTrue(mentions("Graph Neural Networks for molecules", "graph neural network"))
        assertFalse("both words, apart, are not the phrase",
            mentions("dark energy and ordinary matter", "dark matter"))
    }

    @Test
    fun `occurrences are found for highlighting, merged where they touch`() {
        val text = "H-Net beats HNet-small; see H-Net."
        assertEquals(listOf(0..4, 12..15, 28..32), Keywords.occurrences(text, listOf("H-Net")))
        assertTrue(Keywords.occurrences(text, emptyList()).isEmpty())
    }

    @Test
    fun `typed text splits into keywords at commas`() {
        assertEquals(listOf("H-Net", "Sentinel-2", "C. elegans"),
            Keywords.split(" H-Net,Sentinel-2 ;  C.  elegans "))
        assertNull(Keywords.normalise("   "))
    }

    private val vocab = Keywords.vocabulary(sequenceOf(
        "satellite imagery of floods", "anomaly detection", "photos of cats", "detection rates",
    ))

    @Test
    fun `a likely misspelling is offered as a question, names never`() {
        assertEquals("satellite photos", Keywords.suggest("satelite photos", vocab))
        assertEquals("Satellite", Keywords.suggest("Satelite", vocab))
        assertNull("LoRA is somebody's method", Keywords.suggest("LoRA", vocab))
        assertNull(Keywords.suggest("H-Net", vocab))
        assertNull(Keywords.suggest("Sentinel-2", vocab))
        assertNull(Keywords.suggest("anomaly detection", vocab))
        assertNull(Keywords.suggest("satelite", emptyMap()))
    }

    private fun paper(id: String, cat: String, text: String, date: String = "2026-09-24") =
        Paper(id, "Paper $id: $text", "We study $text in detail and report results.",
            listOf("A"), listOf(cat), date, date)

    private val subscribed = setOf("cs.CV")
    private val generic = (1..60).map { paper("v$it", "cs.CV", "image classification benchmark $it") }
    private val elsewhere = (1..20).map { paper("r$it", "cs.RO", "robot grasping $it") }

    private fun digest(candidates: List<Paper>, keywords: List<String>, seed: Int = 1) =
        Ranker().digest(
            candidates = candidates, rated = emptyList(), seen = emptySet(),
            subscribed = subscribed, size = 25, random = Random(seed),
            negativePool = elsewhere.map { it.rankText }, keywords = keywords,
        )

    @Test
    fun `papers mentioning a keyword come first, labelled, from any field`() {
        val hnet = (1..4).map { paper("h$it", "cs.LG", "H-Net dynamic chunking $it") }
        val unet = (1..8).map { paper("u$it", "cs.CV", "U-Net segmentation $it") }
        repeat(5) { seed ->
            val cards = digest(generic + hnet + unet + elsewhere, listOf("H-Net"), seed)
            val labelled = cards.filter { it.keyword != null }
            assertEquals("every H-Net paper, and only those", hnet.map { it.id }.toSet(),
                labelled.map { it.paper.id }.toSet())
            assertEquals("mentions “H-Net”", labelled.first().why())
            assertTrue("they lead the digest", cards.take(3).all { it.keyword != null })
            assertTrue(cards.filter { it.paper.categories.first() == "cs.RO" }.all { it.slot == Slot.BRIDGE })
        }
    }

    @Test
    fun `keywords take at most half the matches, in turn`() {
        val busy = (1..30).map { paper("l$it", "cs.CV", "LoRA adapters for vision $it") }
        val rare = (1..3).map { paper("o$it", "cs.LG", "earth observation time series $it") }
        repeat(5) { seed ->
            val cards = digest(generic + busy + rare + elsewhere, listOf("LoRA", "earth observation"), seed)
            val matches = cards.filter { it.slot == Slot.RELEVANCE }
            val labelled = matches.filter { it.keyword != null }
            assertTrue("half of ${matches.size} at most, got ${labelled.size}",
                labelled.size <= (matches.size + 1) / 2)
            assertEquals(3, labelled.count { it.keyword == "earth observation" })
        }
    }

    @Test
    fun `a keyword is a preference, not a filter`() {
        val cards = digest(generic + elsewhere, listOf("H-Net"))
        assertEquals("a keyword nothing mentions changes nothing", digest(generic + elsewhere, emptyList()).size, cards.size)
        assertTrue(cards.none { it.keyword != null })
    }

    @Test
    fun `recent papers mentioning a keyword are found on the device, exactly`() {
        val db = Db(ctx)
        db.upsertPapers(listOf(
            paper("a", "cs.LG", "H-Net for byte-level language models", "2026-09-28"),
            paper("b", "cs.CV", "U-Net for cell segmentation", "2026-09-28"),
            paper("c", "cs.LG", "HNet revisited", "2026-09-27"),
            paper("d", "cs.LG", "H-Net from last year", "2025-09-27"),
        ))
        assertEquals(listOf("a", "c"),
            db.keywordCandidates(listOf("H-Net"), since = "2026-09-14").map { it.id })
    }

    @Test
    fun `a backup carries the keywords, held to what the screen allows`() {
        val db = Db(ctx); val prefs = Prefs(ctx)
        prefs.keywords = listOf("H-Net", "Sentinel-2")
        val json = Backup.export(db, prefs)
        prefs.keywords = emptyList()
        Backup.restore(json, db, prefs)
        assertEquals(listOf("H-Net", "Sentinel-2"), prefs.keywords)
        Backup.restore(
            """{"version":2,"reactions":[],"keywords":["a","A"," b ","${"x".repeat(200)}","c","d","e","f","g","h","i"]}""",
            db, prefs,
        )
        assertEquals(Keywords.MAX, prefs.keywords.size)
        assertEquals(listOf("a", "b"), prefs.keywords.take(2))
        assertTrue(prefs.keywords.all { it.length <= Keywords.MAX_LENGTH })
    }
}
