package si.jakobkreft.aftergleam

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.CrossrefServers
import si.jakobkreft.aftergleam.data.EprintApi
import si.jakobkreft.aftergleam.data.CrossrefSearch
import si.jakobkreft.aftergleam.data.OsfApi
import si.jakobkreft.aftergleam.data.Source

/**
 * Talks to OSF and Crossref for real.
 *
 * Skipped when there is no network, and skipped when a server does not answer, so it never
 * fails a build for someone else's outage. OSF earns that: across one afternoon it returned
 * 200s in seven seconds, 200s in twenty-seven, and a 500. What it asserts is the part that is
 * ours, that a record which does arrive is parsed into something the ranker can use, because
 * a parser written against two sample records is a guess about a format.
 *
 * Run with: ./gradlew testDebugUnitTest --tests '*NewSourceLive*'
 *
 * Robolectric because the parsers use org.json, which on a plain JVM is the Android stub
 * whose every method throws. The first run of this test reported zero papers from both OSF
 * servers, which looked exactly like a broken parser and was the stub being caught by the
 * parser's own runCatching.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewSourceLiveTest {

    private fun online(): Boolean = runCatching {
        java.net.InetAddress.getByName("api.osf.io").isReachable(3_000) ||
            java.net.Socket("api.osf.io", 443).use { true }
    }.getOrDefault(false)

    /**
     * Runs a fetch, or skips the test when the server did not answer.
     *
     * The APIs throw when a first page fails, which is what lets the app tell an outage from
     * a quiet day. Here that same signal means there is nothing to check.
     */
    private inline fun <T> orSkip(what: String, block: () -> T): T {
        val result = runCatching(block)
        assumeTrue("$what did not answer: ${result.exceptionOrNull()}", result.isSuccess)
        return result.getOrThrow()
    }

    @Test
    fun `psyarxiv returns usable papers`() = runBlocking {
        assumeTrue("offline", online())
        val papers = orSkip("PsyArXiv") {
            OsfApi.recent(
                Source.PSYARXIV,
                setOf("cognitive psychology", "clinical psychology", "social and behavioral sciences"),
                days = 14,
                maxPages = 1,
            )
        }
        println("psyarxiv: ${papers.size} papers")
        papers.take(3).forEach { println("   ${it.title.take(70)}  [${it.categories}]") }
        assert(papers.isNotEmpty()) { "no papers came back" }
        assert(papers.all { it.abstract.isNotBlank() }) { "a paper arrived with no abstract" }
        assert(papers.all { it.source == Source.PSYARXIV }) { "wrong source stamped" }
        assert(papers.all { it.categories.all { c -> c.startsWith("psyarxiv:") } }) {
            "categories must stay qualified or they collide with arXiv's"
        }
        assert(papers.all { it.published.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }) {
            "dates are not ISO: " + papers.map { it.published }.distinct().take(3)
        }
    }

    @Test
    fun `a search reaches biology, medicine and psychology through crossref`() = runBlocking {
        assumeTrue("offline", online())
        val crispr = orSkip("Crossref") { CrossrefSearch.search("CRISPR gene editing", rows = 20) }
        println("crispr: " + crispr.take(3).map { "${it.source} ${it.title.take(50)}" })
        assert(crispr.any { it.source == Source.BIORXIV }) { "no bioRxiv paper for a biology query" }
        val vaccine = orSkip("Crossref") { CrossrefSearch.search("COVID-19 vaccine effectiveness", rows = 20) }
        assert(vaccine.any { it.source == Source.MEDRXIV }) { "no medRxiv paper for a medical query" }
        val memory = orSkip("Crossref") { CrossrefSearch.search("working memory capacity", rows = 20) }
        assert(memory.any { it.source == Source.PSYARXIV }) { "no PsyArXiv paper for a psychology query" }
        val all = crispr + vaccine + memory
        assert(all.all { it.abstract.isNotBlank() && '<' !in it.title }) { "abstract or clean title missing" }
        for (found in listOf(crispr, vaccine, memory)) {
            assert(found.map { CrossrefSearch.baseId(it.id) }.toSet().size == found.size) {
                "one preprint came back more than once"
            }
        }
    }

    @Test
    fun `law archive returns usable papers`() = runBlocking {
        assumeTrue("offline", online())
        val papers = orSkip("Law Archive") {
            OsfApi.recent(
                Source.LAWARCHIVE,
                setOf("law", "constitutional law", "criminal law"),
                days = 60,
                maxPages = 1,
            )
        }
        println("lawarchive: ${papers.size} papers")
        papers.take(3).forEach { println("   ${it.title.take(70)}") }
        assert(papers.isNotEmpty()) { "no papers came back" }
    }

    @Test
    fun `chemrxiv returns usable papers with plain text abstracts`() = runBlocking {
        assumeTrue("offline", online())
        val papers = orSkip("ChemRxiv") { CrossrefServers.recent(CrossrefServers.CHEMRXIV, days = 5) }
        println("chemrxiv: ${papers.size} papers")
        papers.take(3).forEach { println("   ${it.title.take(70)}") }
        assert(papers.isNotEmpty()) { "no papers came back" }
        assert(papers.all { it.abstract.isNotBlank() }) { "a paper arrived with no abstract" }
        // The whole reason the abstract is cleaned: markup left in becomes a model feature.
        val tag = Regex("<[a-zA-Z/][^>]*>")
        assert(papers.none { tag.containsMatchIn(it.abstract) }) {
            "markup survived into an abstract: " +
                papers.first { tag.containsMatchIn(it.abstract) }.abstract.take(120)
        }
        // Nearly all, not all: ChemRxiv's records at Crossref occasionally carry no authors at
        // all (10.26434/chemrxiv.15010182/v1, October 2026), and that is the record, not the
        // parser. Dropping them in parsing would empty far more than one in ten.
        assert(papers.count { it.authors.isNotEmpty() } >= papers.size * 9 / 10) { "authors were dropped" }
    }

    @Test
    fun `earth sciences and ecology arrive with abstracts and a PDF the reader can open`() = runBlocking {
        assumeTrue("offline", online())
        for (server in listOf(CrossrefServers.EARTHARXIV, CrossrefServers.ECOEVORXIV)) {
            val papers = orSkip(server.source) { CrossrefServers.recent(server, days = 7) }
            println("${server.source}: ${papers.size} papers")
            assert(papers.isNotEmpty()) { "no papers came back from ${server.source}" }
            assert(papers.all { it.source == server.source && it.abstract.isNotBlank() })
            // The address the reader's download asks for, followed to the file itself.
            val link = CrossrefServers.pdfLink(papers.first().id)
            assert(link != null) { "no PDF named for ${papers.first().id}" }
            val head = java.net.URL(link).openStream().use { it.readNBytes(5) }.decodeToString()
            assert(head == "%PDF-") { "$link did not serve a PDF: $head" }
        }
    }

    @Test
    fun `ePrint returns its newest papers in its own categories`() = runBlocking {
        assumeTrue("offline", online())
        val all = setOf(
            "Applications", "Cryptographic protocols", "Foundations", "Implementation",
            "Secret-key cryptography", "Public-key cryptography", "Attacks and cryptanalysis",
        )
        val papers = orSkip("IACR ePrint") { EprintApi.recent(all) }
        println("eprint: ${papers.size} papers, newest ${papers.firstOrNull()?.published}")
        assert(papers.size >= 90) { "the feed holds a hundred, ${papers.size} came back" }
        // The newest hundred, posted within the last weeks even after a quiet spell.
        val month = java.time.LocalDate.now().minusDays(30).toString()
        assert(papers.all { it.published >= month }) { "an old paper came back as new" }
        assert(papers.all { it.displayCategories.single() in all && it.authors.isNotEmpty() })
    }

    @Test
    fun `jats stripping leaves readable prose`() {
        val cleaned = CrossrefServers.stripJats(
            "<jats:p>The quintet-to-singlet   process in [Fe(tpy)<jats:sub>2</jats:sub>]" +
                "<jats:sup>2+</jats:sup> is studied &amp; compared.</jats:p>"
        )
        assert(cleaned == "The quintet-to-singlet process in [Fe(tpy) 2 ] 2+ is studied & compared.") {
            "got: $cleaned"
        }
    }
}
