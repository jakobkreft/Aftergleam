package si.jakobkreft.aftergleam.data

import android.util.Xml
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
    private const val UA = "Aftergleam/0.1 (+https://github.com/jakobkreft/aftergleam)"

    /** arXiv asks for one request every three seconds, single connection. */
    const val SLEEP_MS = 3_000L

    class FetchError(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Most recent submissions across [categories], newest first. */
    suspend fun recent(categories: List<String>, max: Int = 300): List<Paper> {
        require(categories.isNotEmpty()) { "no categories selected" }
        val q = categories.joinToString("+OR+") { "cat:$it" }
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
            if (chunk !== clean.takeLast(chunk.size)) Thread.sleep(SLEEP_MS)
        }
        return out
    }

    /**
     * A few recent papers matching a phrase within one category, for the onboarding survey.
     *
     * Sorted by relevance rather than date: the survey wants representative papers for the
     * topic, and the newest submissions on any given morning are a poor sample of a field.
     */
    suspend fun probe(category: String, phrase: String, max: Int = 4): List<Paper> {
        val q = java.net.URLEncoder.encode("cat:$category AND all:$phrase", "UTF-8")
        return parse(get("$ENDPOINT?search_query=$q&max_results=$max"))
    }

    /**
     * Free-text search across title, abstract and authors.
     *
     * This is arXiv's own keyword index, not semantic search. The UI says so: promising
     * semantic search over three million papers and delivering keyword matching would be
     * the kind of lie users notice on their second query.
     */
    suspend fun search(query: String, max: Int = 100): List<Paper> {
        val cleaned = query.trim()
        if (cleaned.length < 2) return emptyList()
        val encoded = java.net.URLEncoder.encode("all:$cleaned", "UTF-8")
        return parse(get("$ENDPOINT?search_query=$encoded&max_results=$max"))
    }

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

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 3) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA)
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
            if (attempt < 3) Thread.sleep(3_000L * attempt)
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
