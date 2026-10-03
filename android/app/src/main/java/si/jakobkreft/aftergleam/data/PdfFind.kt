package si.jakobkreft.aftergleam.data

import android.graphics.RectF
import android.os.Build
import android.os.ext.SdkExtensions

/**
 * Finding words in a PDF, and where on the page they are.
 *
 * The search itself is the platform's ([android.graphics.pdf.PdfRenderer.Page.searchText]),
 * which is pdfium's: it ignores capitals, finds phrases, and finds a word that the paper broke
 * across two lines with a hyphen. Measured on arXiv papers it takes about a millisecond a page.
 * What is left for the app is what the platform does not do, and is kept here where it can be
 * tested without a renderer.
 */
object PdfFind {

    /**
     * Whether this phone can search a PDF at all.
     *
     * Android 15 and later, and Android 12 to 14 once their system updates bring the PDF module
     * to version 13. Older phones keep a reader that shows pages and cannot search them, rather
     * than a search that silently finds nothing.
     */
    val supported: Boolean by lazy {
        when {
            Build.VERSION.SDK_INT >= 35 -> true
            Build.VERSION.SDK_INT >= 31 ->
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
            else -> false
        }
    }

    /** More than this and the count reads "1000+": nobody steps through them one by one. */
    const val MAX_MATCHES = 1000

    /**
     * One place the query was found.
     *
     * [rects] are fractions of the page's width and height, one per line the match runs over,
     * so a highlight lands in the same place at every zoom level without being recomputed.
     * [start] is where the match begins in the page's text, which orders matches the way the
     * page is read: down the first column, then the second. [aspect] is the page's height over
     * its width, for working out where on screen a match is before its page has been drawn.
     */
    data class Match(val page: Int, val start: Int, val rects: List<RectF>, val aspect: Float)

    /**
     * The strings to ask the platform for, for what the reader typed.
     *
     * Papers are typeset with curly apostrophes and phones type straight ones, and pdfium
     * treats them as different letters: "camera's" found nothing in a paper that prints
     * "camera’s". Both spellings are searched for. Spaces are collapsed, because a double
     * space typed by accident should not make a phrase vanish.
     */
    fun variants(query: String): List<String> {
        val q = query.trim().replace(WHITESPACE, " ")
        if (q.isEmpty()) return emptyList()
        if (q.none { it in APOSTROPHES }) return listOf(q)
        val straight = q.map { if (it in APOSTROPHES) '\'' else it }.joinToString("")
        val curly = q.map { if (it in APOSTROPHES) '’' else it }.joinToString("")
        return listOf(straight, curly).distinct()
    }

    /**
     * One page's matches, from the results for each variant: in reading order, each once.
     *
     * Rectangles arrive in PDF points and leave as fractions of the page. A little height is
     * added above and below, because pdfium's boxes hug the glyphs and a highlight that tight
     * reads as an underline rather than a mark.
     */
    fun onPage(
        page: Int,
        pageWidth: Float,
        pageHeight: Float,
        found: List<Pair<Int, List<RectF>>>,
    ): List<Match> {
        if (pageWidth <= 0f || pageHeight <= 0f) return emptyList()
        return found
            .filter { it.second.isNotEmpty() }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { (start, bounds) ->
                Match(page, start, aspect = pageHeight / pageWidth, rects = bounds.map { r ->
                    val pad = (r.bottom - r.top) * 0.15f
                    RectF(
                        (r.left / pageWidth).coerceIn(0f, 1f),
                        ((r.top - pad) / pageHeight).coerceIn(0f, 1f),
                        (r.right / pageWidth).coerceIn(0f, 1f),
                        ((r.bottom + pad) / pageHeight).coerceIn(0f, 1f),
                    )
                })
            }
    }

    /**
     * Which match to show first: the first one at or after the page being read.
     *
     * Searching from page 9 for a word that is also on page 1 should not throw the reader
     * back to the start; a browser's find-in-page behaves the same way. Wraps to the first
     * match when there is nothing further on.
     */
    fun firstFrom(matches: List<Match>, page: Int): Int =
        if (matches.isEmpty()) -1 else matches.indexOfFirst { it.page >= page }.takeIf { it >= 0 } ?: 0

    /**
     * "3 of 27", or what to say instead. An ellipsis while later pages are still being
     * searched, so a count that is about to grow does not pass for the final one.
     */
    fun count(current: Int, total: Int, capped: Boolean, searching: Boolean): String {
        if (total == 0) return if (searching) "…" else "No matches"
        val at = if (current >= 0) "${current + 1} of " else ""
        return at + total + when {
            capped -> "+"
            searching -> "…"
            else -> ""
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val APOSTROPHES = "'’‘ʼ"
}
