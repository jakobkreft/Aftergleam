package si.jakobkreft.aftergleam.data

import android.util.Xml
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * arXiv's Atom API. One request per digest, well inside the published limit of one
 * request every three seconds.
 *
 * Not RSS: rss.arxiv.org returns a valid feed with zero items whenever there was no
 * announcement, which happens every weekend and on US holidays. The Atom query lets us
 * ask for a window instead of "today", so a quiet day degrades to older papers rather
 * than an empty screen.
 */
object ArxivApi {

    private const val ENDPOINT = "https://export.arxiv.org/api/query"

    /** arXiv asks for one request every three seconds, single connection. */
    const val SLEEP_MS = 3_000L

    class FetchError(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Most recent submissions across [categories], newest first. */
    suspend fun recent(categories: List<String>, max: Int = 300): List<Paper> {
        // Categories are spliced into the query as they stand, so only strings shaped like an
        // arXiv category are let through. Restoring a backup checks them against the known
        // subjects too; this is the last line, for any path that one day forgets to.
        val safe = categories.filter { CATEGORY.matches(it) }
        require(safe.isNotEmpty()) { "no categories selected" }
        val q = safe.joinToString("+OR+") { "cat:$it" }
        val url = "$ENDPOINT?search_query=$q&sortBy=submittedDate&sortOrder=descending" +
            "&start=0&max_results=$max"
        return parse(get(url))
    }

    /** Look papers up by identifier. One request per 100 ids. */
    suspend fun byIds(ids: List<String>): List<Paper> {
        val clean = ids.filter { it.isNotBlank() }
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<Paper>()
        for (chunk in clean.chunked(100)) {
            val url = "$ENDPOINT?id_list=${chunk.joinToString(",")}&max_results=${chunk.size}"
            out += parse(get(url))
        }
        return out
    }

    /**
     * Free-text search across title, abstract and authors.
     *
     * This is arXiv's own keyword index, not semantic search. The UI says so: promising
     * semantic search over three million papers and delivering keyword matching would be
     * the kind of lie users notice on their second query.
     *
     * Every word is required. The query used to go out as `all:CRISPR gene editing`, which
     * arXiv reads as CRISPR or gene or editing: 24,329 matches, the first two about the Gene
     * Ontology. Requiring each word gave fifteen, all on the subject. When requiring all of
     * them finds nothing, the looser form is the better answer than none.
     */
    suspend fun search(query: String, max: Int = 100): List<Paper> {
        val strict = searchQuery(query, requireAll = true) ?: return emptyList()
        val found = parse(get("$ENDPOINT?search_query=${enc(strict)}&max_results=$max"))
        if (found.isNotEmpty()) return found
        val loose = searchQuery(query, requireAll = false) ?: return found
        if (loose == strict) return found
        return parse(get("$ENDPOINT?search_query=${enc(loose)}&max_results=$max"))
    }

    /**
     * What is typed, as an arXiv query. Quoted phrases stay phrases, and words too common to
     * narrow anything are left out, since requiring "of" or "for" only loses results. Anybody
     * who writes arXiv's own syntax, `ti:` or `au:` or `AND`, gets exactly what they wrote.
     */
    internal fun searchQuery(text: String, requireAll: Boolean): String? {
        val t = text.trim()
        if (t.length < 2) return null
        if (ARXIV_SYNTAX.containsMatchIn(t)) return t
        val phrases = Regex("\"([^\"]+)\"").findAll(t)
            .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val words = t.replace(Regex("\"[^\"]*\"?"), " ")
            .split(Regex("[^\\p{L}\\p{N}+#-]+"))
            .map { it.trim('-') }
            .filter { it.length > 1 && it.lowercase() !in STOP_WORDS }
            .distinctBy { it.lowercase() }
        val parts = phrases.map { "all:\"$it\"" } + words.map { "all:$it" }
        if (parts.isEmpty()) return "all:$t"
        return parts.joinToString(if (requireAll) " AND " else " OR ")
    }

    private val ARXIV_SYNTAX = Regex("""\b(AND|OR|ANDNOT)\b|\b(ti|au|abs|co|jr|cat|rn|id|all):""")

    private val STOP_WORDS = setOf(
        "a", "an", "and", "as", "at", "by", "for", "from", "in", "into", "is", "of", "on",
        "or", "the", "to", "via", "with",
    )

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * Phrase search on the title field. Returns candidates for the caller to score; the
     * caller decides what counts as a match, because arXiv will cheerfully return adjacent
     * papers for a well-known title.
     */
    suspend fun searchTitle(title: String, max: Int = 5): List<Paper> {
        val q = BibTeX.cleanTitle(title)
        if (q.length < 12) return emptyList()
        val encoded = java.net.URLEncoder.encode("ti:\"$q\"", "UTF-8")
        return parse(get("$ENDPOINT?search_query=$encoded&max_results=$max"))
    }

    /** Every request in the app goes through one gate. See [RateLimiter]. */
    private val limiter = RateLimiter(SLEEP_MS)

    private suspend fun get(url: String): String = limiter.paced { fetch(url) }

    /** cs.LG, astro-ph.GA, hep-th, cond-mat.str-el, q-bio.NC and the rest; nothing else. */
    private val CATEGORY = Regex("""[a-z]+(-[a-z]+)?(\.[A-Za-z]+(-[A-Za-z]+)?)?""")

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 3) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", Http.USER_AGENT)
                    connectTimeout = 20_000
                    readTimeout = 40_000
                }
                try {
                    val code = conn.responseCode
                    if (code == 200) return@withContext conn.inputStream.bufferedReader().readText()
                    // 429 and 5xx are the documented back-off cases. Never retry tightly.
                    lastError = FetchError("arXiv returned HTTP $code")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
            }
            attempt++
            if (attempt < 3) delay(3_000L * attempt)
        }
        throw FetchError("arXiv fetch failed after 3 attempts", lastError)
    }

    /**
     * Streaming Atom parse. Reads arxiv:comment and arxiv:journal_ref as well as the
     * standard fields, because those carry the venue signal.
     */
    internal fun parse(xml: String): List<Paper> {
        val out = mutableListOf<Paper>()
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }

        var id = ""; var title = ""; var summary = ""; var published = ""; var updated = ""
        var comments = ""; var journalRef = ""
        var authors = mutableListOf<String>()
        var cats = mutableListOf<String>()
        var inEntry = false
        var inAuthor = false

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> {
                        inEntry = true
                        id = ""; title = ""; summary = ""; published = ""; updated = ""
                        comments = ""; journalRef = ""
                        authors = mutableListOf(); cats = mutableListOf()
                    }
                    "author" -> inAuthor = true
                    "name" -> if (inEntry && inAuthor) authors += p.nextText().trim()
                    "id" -> if (inEntry) id = normaliseId(p.nextText())
                    "title" -> if (inEntry) title = squash(p.nextText())
                    "summary" -> if (inEntry) summary = squash(p.nextText())
                    "published" -> if (inEntry) published = p.nextText().take(10)
                    "updated" -> if (inEntry) updated = p.nextText().take(10)
                    "comment" -> if (inEntry) comments = squash(p.nextText())
                    "journal_ref" -> if (inEntry) journalRef = squash(p.nextText())
                    "category" -> if (inEntry) {
                        p.getAttributeValue(null, "term")?.let { cats += it }
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        if (id.isNotBlank() && summary.isNotBlank()) {
                            out += Paper(id, title, summary, authors, cats,
                                published, updated, comments, journalRef)
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * "http://arxiv.org/abs/2503.00710v2" -> "2503.00710".
     * Strips the prefix rather than taking the last path segment: pre-2007 identifiers
     * contain a slash (math/0211159) and splitting would discard the archive name.
     */
    private fun normaliseId(raw: String): String =
        raw.trim()
            .removePrefix("http://arxiv.org/abs/")
            .removePrefix("https://arxiv.org/abs/")
            .replace(Regex("v\\d+$"), "")

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
