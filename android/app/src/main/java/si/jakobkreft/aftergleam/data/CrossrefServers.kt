package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * The preprint servers read through Crossref rather than through an API of their own.
 *
 * Each registers every preprint under its own DOI prefix, and Crossref serves the records
 * openly, with abstracts, no key, and a documented courtesy of identifying yourself in the
 * request. Search reaches the other servers the same way, so these add no new party.
 *
 * ChemRxiv because chemistry is the largest field with no home on arXiv, around forty five a
 * day, and its own API sits behind a Cloudflare browser challenge that answers a plain client
 * with a 403. Working around that would mean pretending to be a browser, which is neither
 * honest nor stable. EarthArXiv and EcoEvoRxiv because earth sciences and ecology have little
 * on arXiv either; they left OSF, in 2020 and 2022, for Janeway at the California Digital
 * Library, whose records here are complete, and they post around seven and five a day.
 *
 * The cost of the route is that Crossref carries no subject for these records, so each server
 * arrives as one pool rather than pre-sorted. Every topic of a server therefore subscribes to
 * the same single category, and it is the seed vocabularies that separate organic chemistry
 * from analytical, or hydrology from seismology, which is the same mechanism that already
 * decides what a digest puts first.
 */
object CrossrefServers {

    /**
     * A server, by the DOI prefix it registers its preprints under, and the one category
     * every topic of it subscribes to: Crossref has no subject to sort them by at fetch time.
     */
    class Server(val source: String, val prefix: String, val category: String)

    val CHEMRXIV = Server(Source.CHEMRXIV, "10.26434", "chemistry")
    val EARTHARXIV = Server(Source.EARTHARXIV, "10.31223", "earth sciences")
    val ECOEVORXIV = Server(Source.ECOEVORXIV, "10.32942", "ecology")
    val ALL = listOf(CHEMRXIV, EARTHARXIV, ECOEVORXIV)

    /**
     * Crossref asks that automated clients identify themselves, and rewards it with the
     * faster pool. This is the project's address, not the reader's: nothing about who is
     * using the app goes with it.
     */
    internal const val CONTACT = "user@aftergleam.app"

    suspend fun recent(
        server: Server,
        days: Long = 3,
        max: Int = 300,
        today: LocalDate = LocalDate.now(),
    ): List<Paper> = withContext(Dispatchers.IO) {
        val since = today.minusDays(days).toString()
        val url = "https://api.crossref.org/prefixes/${server.prefix}/works" +
            "?filter=" + enc("from-posted-date:$since") +
            "&rows=$max" +
            "&select=" + enc("DOI,title,abstract,author,posted") +
            "&mailto=" + enc(CONTACT)

        val body = get(url)
            ?: throw java.io.IOException("${Source.label(server.source)} did not answer")
        val items = runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("message")?.optJSONArray("items")
            ?: return@withContext emptyList()

        val out = (0 until items.length())
            .mapNotNull { items.optJSONObject(it)?.let { o -> parse(o, server) } }
        // A revision of a ChemRxiv preprint gets its own DOI suffixed /v2.
        CrossrefSearch.latestVersions(out)
    }

    internal fun parse(o: JSONObject, server: Server): Paper? {
        val doi = o.optString("DOI").ifBlank { return null }
        val title = title(o) ?: return null
        val abstract = stripJats(o.optString("abstract")).ifBlank { return null }
        val date = postedDate(o) ?: return null

        return Paper(
            id = doi,
            title = title,
            abstract = abstract,
            authors = authors(o),
            categories = listOf(Source.qualify(server.source, server.category)),
            published = date,
            updated = date,
            source = server.source,
        )
    }

    /**
     * Where the PDF of the preprint [doi] is, from its record's link. Needed where the address
     * cannot be built from the DOI: Janeway's names the file, which changes with each version.
     */
    suspend fun pdfLink(doi: String): String? = withContext(Dispatchers.IO) {
        get("https://api.crossref.org/works/$doi?mailto=" + enc(CONTACT))
            ?.let { runCatching { JSONObject(it).getJSONObject("message") }.getOrNull() }
            ?.let(::pdfLinkIn)
    }

    internal fun pdfLinkIn(o: JSONObject): String? {
        val links = o.optJSONArray("link") ?: return null
        return (0 until links.length()).mapNotNull { links.optJSONObject(it) }
            .firstOrNull { it.optString("content-type") == "application/pdf" }
            ?.optString("URL")?.ifBlank { null }
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
            // The abstract's own heading. bioRxiv and medRxiv open every abstract with one, and
            // left in, every card from them began "Abstract The first..." or "ABSTRACT Background".
            .replace(LEADING_TITLE, "")
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

    private val LEADING_TITLE = Regex("^\\s*<jats:title>\\s*(?:abstract|summary)\\s*</jats:title>", RegexOption.IGNORE_CASE)

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
