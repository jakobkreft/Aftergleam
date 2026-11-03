package si.jakobkreft.aftergleam.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The IACR's Cryptology ePrint Archive, where cryptography is posted.
 *
 * Cryptography has no home of its own on arXiv: cs.CR is security at large, and the field's
 * papers go to ePrint, around twelve a day. Its metadata is offered to harvesters under CC0,
 * on the one condition that IACR and the authors are credited, which the card's source and
 * byline do. Each paper carries one of seven categories, so its topics filter for real,
 * unlike the pooled servers read through Crossref.
 *
 * What it does not offer an app is the PDF, which comes behind a Cloudflare challenge, so its
 * papers are read on ePrint's own pages; see [Paper.readableInApp]. Nor has it DOIs, so a
 * search through Crossref cannot reach it, and its papers are found once they are on the
 * device.
 *
 * Read from its feed of the hundred newest papers, about a week, rather than a window of days.
 * The archive posts in bursts: one November it was quiet for five days, and a three day
 * window would have been a subject with nothing in it. IACR asks feed readers to refresh no
 * more than once a day, and one answer holds every category, so it is kept for most of a day:
 * the morning's fetch, a pull to refresh and the bridge are all answered from it.
 */
object EprintApi {

    private const val FEED = "https://eprint.iacr.org/rss/rss.xml?order=recent"
    private const val KEEP_MS = 20 * 60 * 60 * 1000L

    private val lock = Mutex()
    private var held: Pair<Long, List<Paper>>? = null

    /** The newest papers under any of [subjects], ePrint's own category names. */
    suspend fun recent(subjects: Set<String>): List<Paper> {
        if (subjects.isEmpty()) return emptyList()
        val all = lock.withLock {
            held?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second
                ?: withContext(Dispatchers.IO) {
                    parse(get(FEED) ?: throw IOException("IACR ePrint did not answer"))
                }.also { held = System.currentTimeMillis() to it }
        }
        return all.filter { Source.display(it.primaryCategory) in subjects }
    }

    /** The feed's papers, newest first. */
    internal fun parse(xml: String): List<Paper> {
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml))
        }
        val papers = mutableListOf<Paper>()
        // An item's elements by name: dc:creator once per author, everything else once.
        val item = mutableMapOf<String, MutableList<String>>()
        var inItem = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when {
                p.eventType == XmlPullParser.START_TAG && p.name == "item" -> {
                    inItem = true
                    item.clear()
                }
                p.eventType == XmlPullParser.START_TAG && inItem ->
                    item.getOrPut(p.name) { mutableListOf() } += p.nextText().trim()
                p.eventType == XmlPullParser.END_TAG && p.name == "item" -> {
                    inItem = false
                    paper(item)?.let { papers += it }
                }
            }
        }
        return papers
    }

    /** A paper from one item; none without the parts the app needs, its category included. */
    private fun paper(item: Map<String, List<String>>): Paper? {
        fun first(name: String) = item[name]?.firstOrNull()?.ifBlank { null }
        val posted = runCatching {
            ZonedDateTime.parse(first("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate()
        }.getOrNull()?.toString() ?: return null
        return Paper(
            id = first("guid")?.substringAfter("eprint.iacr.org/", "")?.ifBlank { null } ?: return null,
            title = first("title") ?: return null,
            abstract = first("description") ?: return null,
            authors = item["creator"].orEmpty(),
            categories = listOf(Source.qualify(Source.EPRINT, first("category") ?: return null)),
            published = posted,
            updated = posted,
            source = Source.EPRINT,
        )
    }

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", Http.USER_AGENT)
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
