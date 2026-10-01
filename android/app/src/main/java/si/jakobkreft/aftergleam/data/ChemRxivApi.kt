package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * ChemRxiv, reached through Crossref rather than through ChemRxiv.
 *
 * Chemistry is the largest field with no home on arXiv. physics.chem-ph carries chemical
 * physics and cond-mat.mtrl-sci carries materials, but synthesis, analytical chemistry and
 * medicinal chemistry are simply absent, and ChemRxiv posts around forty five a day.
 *
 * Its own API sits behind a Cloudflare browser challenge and answers a plain client with a
 * 403 and an interstitial. Working around that would mean pretending to be a browser, which
 * is neither honest nor stable. Crossref is the way in that is meant to be used: ChemRxiv
 * registers every preprint under DOI prefix 10.26434, and Crossref serves the records openly,
 * with abstracts, no key, and a documented courtesy of identifying yourself in the request.
 *
 * The cost of the route is that Crossref carries no subject for these records, so chemistry
 * arrives as one pool rather than pre-sorted. Every chemistry topic therefore subscribes to
 * the same single category and it is the seed vocabularies that separate organic from
 * analytical, which is the same mechanism that already decides what a digest puts first.
 */
object ChemRxivApi {


    /** ChemRxiv's DOI prefix at Crossref. */
    private const val PREFIX = "10.26434"

    /**
     * The one category every chemistry topic subscribes to.
     *
     * Crossref returns no subject for these records, so there is nothing to sort them by at
     * fetch time. Naming it once here keeps the topics honest about that.
     */
    const val CATEGORY = "chemistry"

    /**
     * Crossref asks that automated clients identify themselves, and rewards it with the
     * faster pool. This is the project's address, not the reader's: nothing about who is
     * using the app goes with it.
     */
    internal const val CONTACT = "user@aftergleam.app"

    suspend fun recent(
        subjects: Set<String>,
        days: Long = 3,
        max: Int = 300,
        today: LocalDate = LocalDate.now(),
    ): List<Paper> = withContext(Dispatchers.IO) {
        if (subjects.isEmpty()) return@withContext emptyList()
        val since = today.minusDays(days).toString()
        val url = "https://api.crossref.org/prefixes/$PREFIX/works" +
            "?filter=" + enc("from-posted-date:$since") +
            "&rows=$max" +
            "&select=" + enc("DOI,title,abstract,author,posted") +
            "&mailto=" + enc(CONTACT)

        val body = get(url)
            ?: throw java.io.IOException("ChemRxiv did not answer")
        val items = runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("message")?.optJSONArray("items")
            ?: return@withContext emptyList()

        val out = mutableListOf<Paper>()
        for (i in 0 until items.length()) {
            parse(items.optJSONObject(i) ?: continue)?.let { out += it }
        }
        // A revision gets its own DOI suffixed /v2; keep one entry per preprint.
        out.associateBy { it.id.substringBefore("/v") }.values.toList()
    }

    internal fun parse(o: JSONObject): Paper? {
        val doi = o.optString("DOI").ifBlank { return null }
        val title = title(o) ?: return null
        val abstract = stripJats(o.optString("abstract")).ifBlank { return null }
        val date = postedDate(o) ?: return null

        return Paper(
            id = doi,
            title = title,
            abstract = abstract,
            authors = authors(o),
            categories = listOf(Source.qualify(Source.CHEMRXIV, CATEGORY)),
            published = date,
            updated = date,
            source = Source.CHEMRXIV,
        )
    }

    /**
     * The title, without its markup. Crossref titles are JATS too, and a bioRxiv title read
     * "Plasmid-based CRISPR-Cas9 gene editing in multiple <i>Candida</i> species".
     */
    internal fun title(o: JSONObject): String? =
        stripJats(o.optJSONArray("title")?.optString(0).orEmpty()).ifBlank { null }

    internal fun authors(o: JSONObject): List<String> {
        val authors = mutableListOf<String>()
        val arr = o.optJSONArray("author") ?: return authors
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val name = listOf(a.optString("given"), a.optString("family"))
                .filter { it.isNotBlank() }.joinToString(" ").trim()
            if (name.isNotBlank()) authors += name
        }
        return authors
    }

    /** "posted" is the preprint date, as date-parts: [[2026, 9, 9]]. */
    internal fun postedDate(o: JSONObject): String? {
        val parts = o.optJSONObject("posted")?.optJSONArray("date-parts")?.optJSONArray(0)
            ?: return null
        if (parts.length() < 1) return null
        val y = parts.optInt(0).takeIf { it > 1900 } ?: return null
        val m = if (parts.length() > 1) parts.optInt(1) else 1
        val d = if (parts.length() > 2) parts.optInt(2) else 1
        return "%04d-%02d-%02d".format(y, m.coerceIn(1, 12), d.coerceIn(1, 31))
    }

    /**
     * Crossref abstracts are JATS XML, not text.
     *
     * Left as-is they reach the ranker as "&lt;jats:p&gt;The quintet-to-singlet ...", and the
     * vectoriser already learned once what happens when markup becomes a feature: a digest
     * explained itself with "matches textbf, reasoning, tasks".
     */
    internal fun stripJats(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#x2010;", "-")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    internal fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "${Http.USER_AGENT} mailto:$CONTACT")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
