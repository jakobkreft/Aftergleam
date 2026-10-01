package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.CrossrefSearch
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.SearchRanker

/**
 * Search used to ask arXiv alone. A biology reader searching "CRISPR gene editing" got cs.LG
 * papers about CRISPR and none of the seventeen thousand bioRxiv preprints on it, every hit
 * labelled "outside your usual reading" though they had read nothing yet, and arXiv was asked
 * for papers matching any one of the three words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchAcrossServersTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A Crossref record, shaped the way the works route returns it with our selection. */
    private fun record(
        prefix: String,
        doi: String,
        link: String,
        group: String? = null,
        posted: String = "[2025, 7, 20]",
        title: String = "A title",
        type: String = "posted-content",
    ) = JSONObject(
        """{"DOI": "$doi", "prefix": "$prefix", "type": "$type",
            "title": ["$title"],
            "abstract": "<jats:p>An abstract that says something.</jats:p>",
            "author": [{"given": "Ada", "family": "Lovelace"}],
            "posted": {"date-parts": [$posted]},
            ${if (group != null) "\"group-title\": \"$group\"," else ""}
            "resource": {"primary": {"URL": "$link"}}}"""
    )

    private fun parse(o: JSONObject): Paper? = CrossrefSearch.parse(o)

    @Test
    fun `a bioRxiv record becomes a bioRxiv paper with its subject and a working id`() {
        val p = parse(record("10.64898", "10.1101/2025.07.18.665633",
            "http://biorxiv.org/lookup/doi/10.1101/2025.07.18.665633", group = "Genetics",
            title = "CRISPR in <i>Candida</i>"))!!
        assertEquals(Source.BIORXIV, p.source)
        assertEquals("10.1101/2025.07.18.665633", p.id)
        assertEquals(listOf("biorxiv:genetics"), p.categories)
        assertEquals("markup is not part of a title", "CRISPR in Candida", p.title)
        assertEquals("2025-07-20", p.published)
        // bioRxiv resolves a versionless DOI to the latest version, page and PDF alike.
        assertEquals("https://www.biorxiv.org/content/10.1101/2025.07.18.665633.full.pdf", p.pdfUrl)
    }

    @Test
    fun `medRxiv shares bioRxiv's prefix and is told apart by its link`() {
        val p = parse(record("10.64898", "10.1101/2024.03.14.24304284",
            "http://medrxiv.org/lookup/doi/10.1101/2024.03.14.24304284", group = "Epidemiology"))!!
        assertEquals(Source.MEDRXIV, p.source)
        assertEquals(listOf("medrxiv:epidemiology"), p.categories)
    }

    @Test
    fun `an OSF record takes OSF's own id from its link, version and all`() {
        val psy = parse(record("10.31234", "10.31234/osf.io/8a2jw", "https://osf.io/8a2jw_v1",
            group = "PsyArXiv"))!!
        assertEquals("psyarxiv:8a2jw_v1", psy.id)
        assertEquals("https://osf.io/download/8a2jw_v1/", psy.pdfUrl)
        assertTrue("Crossref has no subject for these, so none is invented", psy.categories.isEmpty())

        val ed = parse(record("10.35542", "10.35542/osf.io/g3xcd", "https://osf.io/g3xcd",
            group = "EdArXiv"))!!
        assertEquals("edarxiv:g3xcd", ed.id)
        val law = parse(record("10.31228", "10.31228/osf.io/hwm7k", "https://osf.io/hwm7k_v1"))!!
        assertEquals(Source.LAWARCHIVE, law.source)
    }

    @Test
    fun `ChemRxiv records are read the way the digest reads them`() {
        val p = parse(record("10.26434", "10.26434/chemrxiv.15002326/v2",
            "https://chemrxiv.org/doi/full/10.26434/chemrxiv.15002326/v2"))!!
        assertEquals(Source.CHEMRXIV, p.source)
        assertEquals("10.26434/chemrxiv.15002326/v2", p.id)
    }

    @Test
    fun `other prefixes and other kinds of record are left out`() {
        assertNull(parse(record("10.1101", "10.1101/pdb.prot099176", "https://cshlp.org/x")))
        assertNull(parse(record("10.31234", "10.31234/osf.io/8a2jw", "https://osf.io/8a2jw_v1",
            type = "peer-review")))
    }

    @Test
    fun `several versions of one preprint come back as one, the latest`() {
        val v1 = parse(record("10.31234", "10.31234/osf.io/jtw5p", "https://osf.io/jtw5p_v1",
            posted = "[2022, 3, 28]"))!!
        val v2 = parse(record("10.31234", "10.31234/osf.io/jtw5p_v2", "https://osf.io/jtw5p_v2",
            posted = "[2022, 9, 1]"))!!
        val other = parse(record("10.31234", "10.31234/osf.io/8a2jw", "https://osf.io/8a2jw_v1"))!!
        val kept = CrossrefSearch.latestVersions(listOf(v1, other, v2))
        assertEquals(listOf("psyarxiv:jtw5p_v2", "psyarxiv:8a2jw_v1"), kept.map { it.id })
    }

    @Test
    fun `a version-less id finds the copy already stored under its version`() {
        assertEquals("10.1101/2025.07.18.665633", CrossrefSearch.baseId("10.1101/2025.07.18.665633v2"))
        assertEquals("psyarxiv:8a2jw", CrossrefSearch.baseId("psyarxiv:8a2jw_v1"))
        assertEquals("10.26434/chemrxiv.15002326", CrossrefSearch.baseId("10.26434/chemrxiv.15002326/v2"))

        val db = Db(ctx)
        fun stored(id: String, source: String) = Paper(id, "T $id", "A $id", listOf("A"),
            listOf("$source:x"), "2025-07-20", "2025-07-20", source = source)
        db.upsertPapers(listOf(
            stored("10.1101/2025.07.18.665633v2", Source.BIORXIV),
            stored("psyarxiv:8a2jw_v1", Source.PSYARXIV),
            stored("10.1101/4814160v1", Source.BIORXIV),
        ))
        val found = db.storedIds(listOf("10.1101/2025.07.18.665633", "psyarxiv:8a2jw", "10.1101/481416"))
        assertEquals("10.1101/2025.07.18.665633v2", found["10.1101/2025.07.18.665633"])
        assertEquals("psyarxiv:8a2jw_v1", found["psyarxiv:8a2jw"])
        assertNull("a longer DOI that starts the same is another paper", found["10.1101/481416"])
    }

    private fun arxiv(text: String, all: Boolean = true): String? =
        ArxivApi.searchQuery(text, requireAll = all)

    @Test
    fun `arXiv is asked for papers with every word, not any of them`() {
        assertEquals("all:CRISPR AND all:gene AND all:editing", arxiv("CRISPR gene editing"))
        assertEquals("all:CRISPR OR all:gene OR all:editing", arxiv("CRISPR gene editing", all = false))
        assertEquals("words that narrow nothing are not required",
            "all:graph AND all:neural AND all:networks AND all:molecules",
            arxiv("graph neural networks for the molecules"))
        assertEquals("all:\"gene editing\" AND all:CRISPR", arxiv("\"gene editing\" CRISPR"))
        assertEquals("arXiv's own syntax is passed through",
            "ti:transformer AND au:vaswani", arxiv("ti:transformer AND au:vaswani"))
        assertNull(arxiv(" a "))
    }

    private fun paper(id: String, text: String) =
        Paper(id, text, text, listOf("A"), listOf("q-bio.GN"), "2025-01-01", "2025-01-01")

    @Test
    fun `with nothing read yet no result claims to be outside your reading`() {
        val results = listOf(
            paper("a", "CRISPR gene editing in yeast"),
            paper("b", "gene regulatory networks"),
            paper("c", "editing large language models"),
        )
        val hits = SearchRanker.rank(results, "CRISPR gene editing", rated = emptyList())
        assertTrue(hits.all { it.why() == "matches your query" })
        assertEquals("the closest match leads", "a", hits.first().paper.id)
    }

    @Test
    fun `the slider moves the order whatever scale the two scores are on`() {
        val results = listOf(
            paper("crispr", "CRISPR gene editing CRISPR gene editing in plants"),
            paper("gnn", "gene editing prediction with graph neural networks deep learning"),
            paper("other", "gene expression atlas"),
        )
        val rated = listOf(
            RatedDoc(null, "graph neural networks deep learning", 0.95f),
            RatedDoc(null, "deep learning for molecules with graph neural networks", 0.95f),
            RatedDoc(null, "neural networks and deep learning", 0.95f),
        )
        val byQuery = SearchRanker.rank(results, "CRISPR gene editing", rated, personalisation = 0f)
        val byMe = SearchRanker.reorder(byQuery, 1f)
        assertEquals("crispr", byQuery.first().paper.id)
        assertEquals("gnn", byMe.first().paper.id)
        assertEquals("matches your query and your interests", byMe.first().why())
    }

    @Test
    fun `standing among results shares ties`() {
        val r = SearchRanker.ranks(listOf(0.3f, 0.1f, 0.3f, 0.2f))
        assertEquals(0f, r[1], 1e-6f)
        assertEquals(1f / 3f, r[3], 1e-6f)
        assertEquals("tied values share their standing", r[0], r[2], 1e-6f)
    }
}
