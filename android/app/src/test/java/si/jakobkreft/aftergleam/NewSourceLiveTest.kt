package si.jakobkreft.aftergleam

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.ChemRxivApi
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
        val papers = orSkip("ChemRxiv") { ChemRxivApi.recent(setOf(ChemRxivApi.CATEGORY), days = 5) }
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
        assert(papers.all { it.authors.isNotEmpty() }) { "authors were dropped" }
    }

    @Test
    fun `jats stripping leaves readable prose`() {
        val cleaned = ChemRxivApi.stripJats(
            "<jats:p>The quintet-to-singlet   process in [Fe(tpy)<jats:sub>2</jats:sub>]" +
                "<jats:sup>2+</jats:sup> is studied &amp; compared.</jats:p>"
        )
        assert(cleaned == "The quintet-to-singlet process in [Fe(tpy) 2 ] 2+ is studied & compared.") {
            "got: $cleaned"
        }
    }
}
