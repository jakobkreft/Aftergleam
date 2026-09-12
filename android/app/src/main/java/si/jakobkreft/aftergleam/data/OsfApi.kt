package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * OSF Preprints, which is where the fields with no arXiv live.
 *
 * Psychology, sociology, education and law are not filed somewhere quiet on arXiv; they are
 * not there at all, and neither bioRxiv nor medRxiv carries them. A reader in any of those
 * fields opening this app previously found a subject picker with nothing addressed to them.
 *
 * One API serves thirty-two preprint servers, which is the whole reason this is worth doing:
 * four fields arrive for one integration. Several of those servers are dormant, having moved
 * to platforms of their own, so only the live ones are wired up. Measured by publications
 * since 2026-08-01: PsyArXiv 1702, SocArXiv 848, EdArXiv 233, Law Archive 55, MetaArXiv 41.
 * SportRxiv last published in 2021, engrXiv in 2022, EarthArXiv and AgriXiv in 2020, and
 * pointing the app at those would be offering a subject that never updates.
 *
 * Records carry a hierarchical subject taxonomy, so the leaf ("Cognitive Psychology",
 * "Criminal Law") is what a topic subscribes to. Like bioRxiv there is no usable server-side
 * subject filter, so a date window is fetched and filtered here.
 */
object OsfApi {

    private const val UA = "Aftergleam/0.1 (+https://github.com/jakobkreft/aftergleam)"

    /** The servers that still publish. See the note above on the ones that do not. */
    const val PSYARXIV = "psyarxiv"
    const val SOCARXIV = "socarxiv"
    const val EDARXIV = "edarxiv"
    const val LAWARCHIVE = "lawarchive"

    val SERVERS = listOf(PSYARXIV, SOCARXIV, EDARXIV, LAWARCHIVE)

    /**
     * Fifty, because OSF's cost is the payload rather than the number of requests.
     *
     * Measured against PsyArXiv: fifty records take 13s with the fields below and 25s
     * without them, and a hundred take 55s. Asking for less, more often, is the cheaper
     * shape here, which is the opposite of the arXiv path.
     */
    private const val PAGE = 50

    /**
     * Only the fields the app reads.
     *
     * OSF returns thirty attributes per preprint including licence records and preregistration
     * links, and none of it is shown or ranked on. Asking for six halves both the payload and
     * the wait.
     */
    private const val FIELDS = "title,description,date_published,subjects,is_published,date_withdrawn"

    /**
     * Recent preprints from one [provider], keeping only those under [subjects].
     *
     * @param days how far back to look. Wider than the other servers, because these differ
     *   from each other by two orders of magnitude: PsyArXiv posts around forty a working
     *   day and the Law Archive around nine a week. A three day window served psychology and
     *   returned nothing at all for law, which reads as a server with no papers rather than
     *   as a window too narrow to catch any. Results come back newest first and [maxPages]
     *   caps the volume, so widening the window costs the busy servers nothing: it only adds
     *   older papers to a tail that is already being cut off.
     */
    suspend fun recent(
        provider: String,
        subjects: Set<String>,
        days: Long = 14,
        // Two pages is a hundred records, newest first, which is several days of the busiest
        // server. Four pages bought little and cost up to a hundred seconds at the slow end
        // of the latency above.
        maxPages: Int = 2,
        today: LocalDate = LocalDate.now(),
    ): List<Paper> = withContext(Dispatchers.IO) {
        if (subjects.isEmpty()) return@withContext emptyList()
        val since = today.minusDays(days).toString()
        val wanted = subjects.map { it.lowercase() }.toSet()

        val out = mutableListOf<Paper>()
        var page = 1
        while (page <= maxPages) {
            val url = "https://api.osf.io/v2/preprints/" +
                "?filter%5Bprovider%5D=" + enc(provider) +
                "&filter%5Bdate_published%5D%5Bgte%5D=" + enc(since) +
                "&page%5Bsize%5D=$PAGE&page=$page" +
                "&fields%5Bpreprints%5D=" + enc(FIELDS)
            val body = get(url)
                // Distinguished from "this server has nothing": returning an empty list for
                // both is what let a timed out Law Archive be reported to the reader as a
                // quiet day. Later pages may stop quietly, since by then there is real data.
                ?: if (page == 1) throw java.io.IOException("${Source.label(provider)} did not answer")
                else break
            val root = runCatching { JSONObject(body) }.getOrNull() ?: break
            val items = root.optJSONArray("data") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val paper = parse(o, provider, wanted) ?: continue
                out += paper
            }
            // The API reports how many pages remain; stop rather than asking for an empty one.
            val next = root.optJSONObject("links")?.optString("next").orEmpty()
            if (next.isBlank() || next == "null") break
            page++
            delay(400)
        }
        // A preprint and its revisions share a guid stem, newest last.
        out.associateBy { it.id.substringBefore("_v") }.values.toList()
    }

    /**
     * The leaf subjects a provider has actually used, for building the topic list.
     *
     * Not called by the app. It exists so the taxonomy can be checked against the server
     * rather than guessed, which is how five medRxiv subjects came to be unreachable.
     */
    suspend fun subjectsSeen(provider: String, since: String): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val counts = HashMap<String, Int>()
            val url = "https://api.osf.io/v2/preprints/?filter%5Bprovider%5D=" + enc(provider) +
                "&filter%5Bdate_published%5D%5Bgte%5D=" + enc(since) + "&page%5Bsize%5D=$PAGE" +
                "&fields%5Bpreprints%5D=" + enc(FIELDS)
            val root = get(url)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@withContext counts
            val items = root.optJSONArray("data") ?: return@withContext counts
            for (i in 0 until items.length()) {
                for (leaf in leaves(items.optJSONObject(i))) {
                    counts[leaf] = (counts[leaf] ?: 0) + 1
                }
            }
            counts
        }

    /** The last element of each subject path, which is the specific one. */
    private fun leaves(o: JSONObject?): List<String> {
        val paths = o?.optJSONObject("attributes")?.optJSONArray("subjects") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until paths.length()) {
            val path = paths.optJSONArray(i) ?: continue
            val last = path.optJSONObject(path.length() - 1) ?: continue
            last.optString("text").takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    private fun parse(o: JSONObject, provider: String, wanted: Set<String>): Paper? {
        val a = o.optJSONObject("attributes") ?: return null
        if (!a.optBoolean("is_published", true)) return null
        if (a.optString("date_withdrawn").let { it.isNotBlank() && it != "null" }) return null

        val subjects = leaves(o)
        val matched = subjects.filter { it.lowercase() in wanted }
        if (matched.isEmpty()) return null

        val title = a.optString("title").ifBlank { return null }
        // A record with no abstract is invisible to a ranker that reads abstracts.
        val abstract = a.optString("description").ifBlank { return null }
        val date = a.optString("date_published").ifBlank { return null }.take(10)
        val id = o.optString("id").ifBlank { return null }

        return Paper(
            id = "$provider:$id",
            title = title,
            abstract = abstract,
            // Empty on purpose. Authors live behind a separate relationship, and embedding
            // them costs more than everything else combined: fifty records with contributors
            // timed out past sixty seconds where fifty without took thirteen. The byline
            // drops a missing name and shows the server and subject instead, and nothing in
            // the ranking reads an author.
            authors = emptyList(),
            categories = matched.map { Source.qualify(provider, it.lowercase()) },
            published = date,
            updated = date,
            source = provider,
        )
    }


    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 20_000
            // Generous, because OSF is genuinely slow and erratic with it: the same request
            // for fifty records measured 12.8s once and 26.5s an hour later. At a thirty
            // second read timeout the slow half of that range returns null, and null here
            // is indistinguishable from a server with no papers, so psychology and law would
            // quietly empty out for reasons nobody could see.
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
