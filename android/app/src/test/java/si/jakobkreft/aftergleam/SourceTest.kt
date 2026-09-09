package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.Topics

/**
 * More than one preprint server, and the seams that creates.
 *
 * arXiv has no chemistry archive, no medicine, and its biology is quantitative biology, so
 * a cell biologist found nothing here. The cost of fixing that is that identifiers, URLs and
 * category names now vary by server, and every one of those is somewhere a bioRxiv DOI can
 * be handed to arXiv and fail.
 */
class SourceTest {

    private fun paper(id: String, source: String, cat: String) = Paper(
        id = id, title = "T", abstract = "A", authors = listOf("X"),
        categories = listOf(cat), published = "2026-09-08", updated = "2026-09-08",
        source = source,
    )

    @Test
    fun `each server addresses its own papers`() {
        val a = paper("2609.08391", Source.ARXIV, "cs.CV")
        assertEquals("https://arxiv.org/abs/2609.08391", a.absUrl)
        assertEquals("https://arxiv.org/pdf/2609.08391", a.pdfUrl)

        // The version is part of the identifier because the content URL needs it.
        val b = paper("10.64898/2026.09.01.747412v2", Source.BIORXIV, "biorxiv:cell biology")
        assertEquals("https://www.biorxiv.org/content/10.64898/2026.09.01.747412v2", b.absUrl)
        assertEquals(
            "https://www.biorxiv.org/content/10.64898/2026.09.01.747412v2.full.pdf",
            b.pdfUrl,
        )
        val m = paper("10.1101/2026.09.01.1234v1", Source.MEDRXIV, "medrxiv:epidemiology")
        assertTrue(m.absUrl.startsWith("https://www.medrxiv.org/content/"))
    }

    @Test
    fun `only non-arXiv papers announce where they came from`() {
        assertNull(paper("1", Source.ARXIV, "cs.CV").sourceLabel)
        assertEquals("bioRxiv", paper("2", Source.BIORXIV, "x").sourceLabel)
        assertEquals("medRxiv", paper("3", Source.MEDRXIV, "x").sourceLabel)
    }

    @Test
    fun `categories are qualified so two servers can both have genomics`() {
        // bioRxiv genomics is sequencing work; arXiv q-bio.GN is modelling. Subscribing to
        // one must not subscribe to the other, and the bandit must be able to tell them
        // apart when it learns which one the reader actually opens.
        assertEquals("biorxiv:genomics", Source.qualify(Source.BIORXIV, "genomics"))
        assertEquals("q-bio.GN", Source.qualify(Source.ARXIV, "q-bio.GN"))
        assertEquals("genomics", Source.display("biorxiv:genomics"))
        assertEquals("q-bio.GN", Source.display("q-bio.GN"))
        assertEquals(Source.BIORXIV, Source.of("biorxiv:genomics"))
        assertEquals(Source.ARXIV, Source.of("q-bio.GN"))
    }

    @Test
    fun `a fetch for one server never sees another's categories`() {
        // The failure this prevents: handing arXiv the string "cell biology" as a category,
        // or handing bioRxiv "cs.CV".
        val subscribed = setOf("cs.CV", "q-bio.NC", "biorxiv:cell biology", "medrxiv:oncology")
        assertEquals(setOf("cs.CV", "q-bio.NC"), Topics.categoriesOf(Source.ARXIV, subscribed))
        assertEquals(setOf("cell biology"), Topics.categoriesOf(Source.BIORXIV, subscribed))
        assertEquals(setOf("oncology"), Topics.categoriesOf(Source.MEDRXIV, subscribed))
    }

    @Test
    fun `every topic's categories belong to the server it names`() {
        for (field in Topics.FIELDS) {
            for (t in field.topics) {
                for (q in t.qualified) {
                    assertEquals(
                        "topic ${t.key} claims ${t.source} but produced $q",
                        t.source, Source.of(q),
                    )
                }
            }
        }
    }

    @Test
    fun `arXiv coverage includes the fields it actually has`() {
        // The gap that started this: arXiv does carry cell biology, software engineering and
        // chemistry, and the app simply was not offering them.
        val all = Topics.FIELDS.flatMap { it.topics }.flatMap { it.qualified }.toSet()
        for (c in listOf("q-bio.CB", "q-bio.SC", "q-bio.TO", "cs.SE", "cs.IR", "cs.AI",
                         "physics.chem-ph", "math.ST", "econ.TH", "nucl-th")) {
            assertTrue("$c should be reachable from some topic", c in all)
        }
    }

    @Test
    fun `a subject subscribed to is a subject the server can be asked for`() {
        // The join between the two halves, which is where this broke: subscriptions are
        // stored qualified, the API speaks in bare subject names, and a mismatch there is
        // not an error, it is a fetch that quietly returns nothing.
        val subscribed = Topics.categoriesFor(setOf("br-cell", "mr-onc"))
        val bio = Topics.categoriesOf(Source.BIORXIV, subscribed)
        val med = Topics.categoriesOf(Source.MEDRXIV, subscribed)
        assertTrue("cell biology" in bio)
        assertTrue("oncology" in med)
        // What the API returns for the category field, verbatim, must be in that set.
        for (s in bio + med) assertTrue("'$s' must be bare", ':' !in s)
    }

    @Test
    fun `topic keys are unique`() {
        // Two topics sharing a key would make one of them unselectable, silently.
        val keys = Topics.FIELDS.flatMap { it.topics }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
