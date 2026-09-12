package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * bioRxiv and medRxiv, which is where the biology arXiv does not have actually goes.
 *
 * arXiv's q-bio is quantitative biology: models, networks, population dynamics. Cell
 * biology, immunology, cancer biology, cardiology, psychiatry and epidemiology are not
 * quietly filed somewhere else on arXiv, they are simply not there, and a reader in any of
 * those fields opening an arXiv reader finds nothing addressed to them. bioRxiv posts around
 * 190 papers a working day and medRxiv around 70, across 23 and 44 subjects respectively.
 *
 * One JSON endpoint serves both, with no key and no bot check, and it returns the two things
 * the ranker actually needs: a title and an abstract. It also returns `published`, which is
 * the journal a preprint eventually appeared in, so the venue signal that arXiv gets from
 * its comments field arrives here for free and by the same route: months later, on a paper
 * that has since turned out to matter.
 *
 * Unlike arXiv there is no category filter in the request. Everything for a date range comes
 * back and is filtered here, which is fine at these volumes and is the reason the fetch is
 * bounded by [maxPages] rather than by subject.
 */
object BioRxivApi {

    private const val UA = "Aftergleam/0.1 (+https://github.com/jakobkreft/aftergleam)"

    /** The API pages in fixed blocks of thirty; the size is not a parameter. */
    private const val PAGE = 30

    /**
     * Recent preprints from [server], keeping only those in [subjects].
     *
     * @param days how far back to look. bioRxiv posts every day including weekends, so a
     *   short window is usually enough; a returning reader is served by the catch-up list
     *   rather than by widening this.
     */
    suspend fun recent(
        server: String,
        subjects: Set<String>,
        days: Long = 2,
        maxPages: Int = 12,
        today: LocalDate = LocalDate.now(),
    ): List<Paper> = withContext(Dispatchers.IO) {
        if (subjects.isEmpty()) return@withContext emptyList()
        val from = today.minusDays(days).toString()
        val to = today.toString()

        val out = mutableListOf<Paper>()
        var cursor = 0
        var total = Int.MAX_VALUE
        var page = 0
        while (cursor < total && page < maxPages) {
            val body = get("https://api.biorxiv.org/details/$server/$from/$to/$cursor")
                // The first page failing is the server being unreachable, which is a
                // different thing to say than "nothing was posted".
                ?: if (page == 0) throw java.io.IOException("${Source.label(server)} did not answer")
                else break
            val root = runCatching { JSONObject(body) }.getOrNull() ?: break
            total = root.optJSONArray("messages")
                ?.optJSONObject(0)?.optString("total")?.toIntOrNull() ?: 0
            val items = root.optJSONArray("collection") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                // Filtered on the server's own name for the subject, before qualifying.
                // Comparing the qualified form against the caller's plain subjects matched
                // nothing at all, and a fetch that silently returns zero papers looks
                // exactly like a server with nothing to say.
                if (o.optString("category") !in subjects) continue
                parse(o, server)?.let { out += it }
            }
            cursor += PAGE
            page++
            // Well inside anything the endpoint asks for, and the same courtesy the arXiv
            // path extends: this is a free service run by a non-profit.
            if (cursor < total && page < maxPages) delay(400)
        }
        // A revision and its original share a DOI. The API returns them oldest first, so the
        // later version wins and the reader is not shown the same paper twice.
        out.associateBy { it.id.substringBefore("v") }.values.toList()
    }

    private fun parse(o: JSONObject, server: String): Paper? {
        val doi = o.optString("doi").ifBlank { return null }
        val version = o.optString("version").ifBlank { "1" }
        val title = o.optString("title").ifBlank { return null }
        val abstract = o.optString("abstract")
        // A record with no abstract is invisible to a ranker that reads abstracts, and
        // showing it would be showing a title and a shrug.
        if (abstract.isBlank()) return null
        val date = o.optString("date").ifBlank { return null }
        val category = o.optString("category").ifBlank { "unclassified" }
        // "Mes, W.; Haanen, R." rather than arXiv's separate author elements.
        val authors = o.optString("authors").split(";")
            .map { it.trim() }.filter { it.isNotBlank() }
            .map { name ->
                // "Surname, I. J." reads badly in a byline built for "First Last".
                val parts = name.split(",")
                if (parts.size == 2) "${parts[1].trim()} ${parts[0].trim()}" else name
            }
        val published = o.optString("published").takeIf { it.isNotBlank() && it != "NA" }
        return Paper(
            id = "${doi}v$version",
            title = title,
            abstract = abstract,
            authors = authors,
            categories = listOf(Source.qualify(server, category)),
            published = date,
            updated = date,
            comments = "",
            // The same field the venue signal already reads for arXiv papers.
            journalRef = published.orEmpty(),
            source = server,
        )
    }

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
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
