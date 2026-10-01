package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Keyword search over every preprint server the app reads except arXiv, in one request.
 *
 * Search used to ask arXiv and nothing else. A reader who follows biology searched for
 * "CRISPR gene editing" and got machine learning papers about CRISPR from cs.LG, while
 * seventeen thousand bioRxiv preprints on the subject were out of reach unless one of them
 * happened to be on the phone already.
 *
 * None of the other servers offers a usable keyword search to an app: bioRxiv's API lists by
 * date and DOI only, ChemRxiv sits behind a browser challenge, and OSF's search is being
 * replaced. Crossref holds all of their records, with abstracts, and the app already asks it
 * for ChemRxiv, so searching through it adds no new party. Each server registers its
 * preprints under its own DOI prefix, which is how one request covers all of them and
 * nothing else.
 */
object CrossrefSearch {

    /** openRxiv, which holds bioRxiv and medRxiv, old `10.1101` DOIs included. */
    private const val OPENRXIV = "10.64898"

    /** The owner prefix at Crossref of each server, other than openRxiv's two. */
    private val OSF_PREFIXES = mapOf(
        "10.31234" to Source.PSYARXIV,
        "10.31235" to Source.SOCARXIV,
        "10.35542" to Source.EDARXIV,
        "10.31228" to Source.LAWARCHIVE,
    )
    private const val CHEMRXIV = "10.26434"

    private val PREFIXES = listOf(OPENRXIV, CHEMRXIV) + OSF_PREFIXES.keys

    // `institution`, which would name bioRxiv or medRxiv outright, cannot be selected on this
    // route; the record's link says the same thing.
    private const val FIELDS = "DOI,prefix,title,abstract,author,posted,group-title,resource,type"

    /** An OSF id as the app stores it: five characters, and the version when there is one. */
    private val OSF_ID = Regex("[a-z0-9]{5}(_v\\d+)?")

    suspend fun search(query: String, rows: Int = 60): List<Paper> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val filter = PREFIXES.joinToString(",") { "prefix:$it" } + ",type:posted-content"
        val url = "https://api.crossref.org/works" +
            "?query.bibliographic=" + enc(q) +
            "&filter=" + enc(filter) +
            "&rows=$rows" +
            "&select=" + enc(FIELDS) +
            "&mailto=" + enc(ChemRxivApi.CONTACT)
        val body = ChemRxivApi.get(url) ?: throw java.io.IOException("Crossref did not answer")
        val items = runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("message")?.optJSONArray("items")
            ?: return@withContext emptyList()
        val papers = (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { parse(it) }
        }
        latestVersions(papers)
    }

    /**
     * One entry per preprint. Crossref registers each version of an OSF or ChemRxiv preprint
     * as its own record, and a search for a well-studied subject returned the same paper
     * three times in a row. The order Crossref ranked them in is kept.
     */
    internal fun latestVersions(papers: List<Paper>): List<Paper> {
        val best = LinkedHashMap<String, Paper>()
        for (p in papers) {
            val key = baseId(p.id)
            val held = best[key]
            if (held == null || p.published > held.published) best[key] = p
        }
        return best.values.toList()
    }

    /** The id with any version taken off, so two versions of a paper compare equal. */
    fun baseId(id: String): String =
        id.replace(Regex("(_v\\d+|/v\\d+|(?<=\\d)v\\d+)$"), "")

    internal fun parse(o: JSONObject): Paper? {
        if (o.optString("type") != "posted-content") return null
        return when (val prefix = o.optString("prefix")) {
            CHEMRXIV -> ChemRxivApi.parse(o)
            OPENRXIV -> openRxiv(o)
            in OSF_PREFIXES -> osf(o, OSF_PREFIXES.getValue(prefix))
            else -> null
        }
    }

    private fun link(o: JSONObject): String =
        o.optJSONObject("resource")?.optJSONObject("primary")?.optString("URL").orEmpty()

    /**
     * bioRxiv and medRxiv. The DOI comes without a version, which the servers resolve to the
     * latest one for both the page and the PDF, so it is used as the id as it stands.
     */
    private fun openRxiv(o: JSONObject): Paper? {
        val doi = o.optString("DOI").ifBlank { return null }
        val server = when {
            "medrxiv.org" in link(o) -> Source.MEDRXIV
            "biorxiv.org" in link(o) -> Source.BIORXIV
            else -> return null
        }
        val title = ChemRxivApi.title(o) ?: return null
        val abstract = ChemRxivApi.stripJats(o.optString("abstract")).ifBlank { return null }
        val date = ChemRxivApi.postedDate(o) ?: return null
        // The subject, in the words bioRxiv's own API uses once lowercased.
        val subject = o.optString("group-title").trim().lowercase().ifBlank { "unclassified" }
        return Paper(
            id = doi,
            title = title,
            abstract = abstract,
            authors = ChemRxivApi.authors(o),
            categories = listOf(Source.qualify(server, subject)),
            published = date,
            updated = date,
            source = server,
        )
    }

    /**
     * PsyArXiv, SocArXiv, EdArXiv and Law Archive. The id is OSF's own, taken from the
     * record's link, which is the form the reader's download address is built from.
     *
     * Crossref carries no subject for these, so the paper has none rather than a made-up
     * one; the card shows the server instead.
     */
    private fun osf(o: JSONObject, server: String): Paper? {
        val fromLink = link(o).trimEnd('/').substringAfterLast('/')
        val fromDoi = o.optString("DOI").substringAfterLast('/')
        val guid = listOf(fromLink, fromDoi).map { it.lowercase() }
            .firstOrNull { OSF_ID.matches(it) } ?: return null
        val title = ChemRxivApi.title(o) ?: return null
        val abstract = ChemRxivApi.stripJats(o.optString("abstract")).ifBlank { return null }
        val date = ChemRxivApi.postedDate(o) ?: return null
        return Paper(
            id = "$server:$guid",
            title = title,
            abstract = abstract,
            authors = ChemRxivApi.authors(o),
            categories = emptyList(),
            published = date,
            updated = date,
            source = server,
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
