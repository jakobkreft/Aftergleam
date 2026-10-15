package si.jakobkreft.aftergleam.ui

import si.jakobkreft.aftergleam.data.Paper
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The library's shelves, each with the thing the reader did to put a paper on it. */
enum class LibraryShelf(val label: String, val action: String) {
    SAVED("Saved", "saved"),
    DOWNLOADED("Offline", "downloaded"),
    RATED("Reacted to", "reacted"),
}

/**
 * How a shelf is ordered: by when the reader put each paper there (the default), by when the
 * paper itself appeared, or by title.
 *
 * Offline had a largest-first order of its own, for freeing space. It went: the differences
 * between papers are a few megabytes, nobody chooses what to read by them, and the shelf still
 * shows the total and deletes in one tap.
 */
enum class LibraryOrder {
    RECENT, NEWEST, TITLE;

    fun label(shelf: LibraryShelf): String = when (this) {
        RECENT -> "Recently " + shelf.action
        NEWEST -> "Newest papers first"
        TITLE -> "Title A to Z"
    }
}

object LibrarySort {

    /**
     * [papers] in [order]. [actedAt] is when each was saved, downloaded or reacted to; a paper
     * with no time goes after those with one. Ties fall back to the newest paper, then the id,
     * so the shelf never reshuffles between two loads of the same data.
     */
    fun sort(
        papers: List<Paper>,
        order: LibraryOrder,
        actedAt: Map<String, Long>,
        collator: Collator = Collator.getInstance(),
    ): List<Paper> {
        val newest = compareByDescending<Paper> { it.published }.thenBy { it.id }
        return when (order) {
            LibraryOrder.RECENT -> papers.sortedWith(
                compareByDescending<Paper> { actedAt[it.id] ?: Long.MIN_VALUE }.then(newest)
            )
            LibraryOrder.NEWEST -> papers.sortedWith(newest)
            // In the reader's language, so accented titles sort where they would in a library.
            LibraryOrder.TITLE -> papers.sortedWith(
                Comparator<Paper> { a, b -> collator.compare(a.displayTitle, b.displayTitle) }.then(newest)
            )
        }
    }

    /**
     * The date a row shows when the shelf is in the order the reader acted: "saved today",
     * "downloaded 2 Oct". Showing the publication date there instead made the list look
     * unordered, a paper from 2024 above one from last week for no visible reason.
     */
    fun actedLabel(
        shelf: LibraryShelf,
        millis: Long,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val date = when {
            day == today -> "today"
            day == today.minusDays(1) -> "yesterday"
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> day.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        }
        return "${shelf.action} $date"
    }
}
