package si.jakobkreft.aftergleam.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a keyword's bell tells the reader about, and when.
 *
 * Research alerts that work, Google Scholar's and PubMed's, send one message per search with
 * whatever is new, never one per paper as it lands, and that is the model here: a bell is a
 * standing question, "anything new on lanosterol + hypoxia?", answered at most once a day per
 * keyword. Later papers the same day join the same note quietly.
 *
 * News means recently published as well as not seen before. Without the first rule, turning a
 * bell on for a keyword whose papers had not been fetched yet would announce two months of
 * them; without the second, a paper found by two sources would be told about twice.
 */
object KeywordAlerts {

    /**
     * How recent a paper must be to be news. Four days, not one: a paper submitted to arXiv
     * on a Friday is announced on the Monday with Friday's date.
     */
    const val RECENT_DAYS = 4L

    /** Alerts are shown from this hour, and not from [LAST_HOUR] on. Found at night, they wait. */
    const val FIRST_HOUR = 8
    const val LAST_HOUR = 21

    /** Papers named in one keyword's note at most. A keyword that finds more is a broad one. */
    const val MAX_PER_KEYWORD = 5

    fun inWindow(time: LocalTime): Boolean = time.hour in FIRST_HOUR until LAST_HOUR

    /**
     * The papers to tell the reader about, by keyword in the reader's order: published in the
     * last [RECENT_DAYS], and not in [known], which holds every paper already told about, found
     * on the phone when a bell was turned on, or acted on by the reader. A paper two keywords
     * find is told about under the first.
     */
    fun fresh(matches: Map<String, List<Paper>>, known: Set<String>, today: LocalDate): Map<String, List<Paper>> {
        val since = today.minusDays(RECENT_DAYS).toString()
        val taken = known.toHashSet()
        val out = LinkedHashMap<String, List<Paper>>()
        for ((keyword, papers) in matches) {
            val news = papers
                .filter { it.published.take(10) >= since && it.id !in taken }
                .distinctBy { it.id }
                .sortedByDescending { it.published }
            if (news.isEmpty()) continue
            taken += news.map { it.id }
            out[keyword] = news
        }
        return out
    }

    /**
     * About how many papers a week a keyword finds, from the publication days of its recent
     * matches on the phone. Over the whole window when the phone has them all; over the days
     * they span when there are as many as one fetch brings, since a busy keyword's forty newest
     * may all be from this week.
     */
    fun perWeek(published: List<String>, today: LocalDate): Float {
        if (published.isEmpty()) return 0f
        val days = if (published.size < FULL_FETCH) Keywords.WINDOW_DAYS else {
            val oldest = published.minOf { it.take(10) }
            val parsed = runCatching { LocalDate.parse(oldest) }.getOrNull() ?: return 0f
            max(7L, ChronoUnit.DAYS.between(parsed, today) + 1)
        }
        return published.size * 7f / days
    }

    /** [perWeek] in words, for deciding whether a bell is worth turning on. */
    fun describe(perWeek: Float): String {
        val perMonth = perWeek * 30f / 7f
        return when {
            perWeek <= 0f -> "none in the last two months"
            perWeek >= 1.5f -> "about ${perWeek.roundToInt()} a week"
            perWeek >= 0.75f -> "about 1 a week"
            perMonth >= 1.5f -> "about ${perMonth.roundToInt()} a month"
            perMonth >= 0.75f -> "about 1 a month"
            else -> "rarely"
        }
    }

    /** Matches at which the phone holds about as many as a fetch brings, not all of them. */
    private const val FULL_FETCH = 30
}

/**
 * The daily note: one quiet line, at an hour the reader chose, naming the first paper of the
 * day's digest.
 *
 * It replaces two notifications. One came when the digest was prepared, at five in the
 * morning, with a count of everything fetched ("300 new in your categories"); the other came
 * in the evening with a count of what was left ("25 papers still waiting"). Neither number
 * told the reader anything worth stopping for, and the second was a reproach. A title is
 * something they can decide about.
 */
object DailyNote {

    /** On, a digest today, and the app not opened yet today. */
    fun due(enabled: Boolean, digest: List<ShownItem>, lastOpened: String?, today: LocalDate): Boolean =
        enabled && digest.isNotEmpty() && lastOpened != today.toString()

    /** The card to name: the first the reader has not already done something with. */
    fun pick(digest: List<ShownItem>, acted: Set<String>): ShownItem? =
        digest.firstOrNull { it.paperId !in acted }

    const val TITLE = "Today's papers"

    /** The second line, under the paper's title: why it came first. */
    fun detail(reason: String): String =
        if (reason.isBlank()) "First in today's digest"
        else "First in today's digest, " + reason.replaceFirstChar { it.lowercase() }
}
