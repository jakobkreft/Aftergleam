package si.jakobkreft.aftergleam.data

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs

/**
 * Selecting text on a PDF page, to copy it.
 *
 * Which characters a finger means is the platform's
 * ([android.graphics.pdf.PdfRenderer.Page.selectContent]), the same pdfium that finds words for
 * [PdfFind], on the same phones. It is asked with places on the page rather than places in the
 * text: given two it selects what lies between them in reading order, whichever comes first,
 * and given the same place twice it selects the word there. It answers the same way, with the
 * places its selection starts and stops, which are what the next question is asked with.
 * What is left for the app is kept here, where it can be tested without a renderer.
 */
object PdfSelect {

    /** A place on a page, as fractions of its width and height, so it holds at any zoom. */
    data class Place(val x: Float, val y: Float) {
        /** The same place, give or take a rounding of the page's points. */
        fun near(other: Place): Boolean = abs(x - other.x) < SAME && abs(y - other.y) < SAME
    }

    /**
     * What the platform selected, in points: where it starts and stops, which it leaves out
     * when it has no place for them, and its text in pieces, each with the rectangles of the
     * lines it runs over.
     */
    class Found(val start: PointF?, val stop: PointF?, val pieces: List<Pair<String, List<RectF>>>)

    /**
     * Text selected on one page.
     *
     * [rects] are one per line, in reading order. [start] is where the first character begins
     * and [stop] where the last one ends, both on its line.
     */
    data class Selection(
        val page: Int,
        val text: String,
        val rects: List<RectF>,
        val start: Place,
        val stop: Place,
    )

    /** A selection from what the platform found, or null when it found no text. */
    fun onPage(page: Int, pageWidth: Float, pageHeight: Float, found: Found): Selection? {
        if (pageWidth <= 0f || pageHeight <= 0f) return null
        val text = found.pieces.joinToString("") { it.first }
        val rects = found.pieces.flatMap { it.second }.map { r ->
            RectF(
                (r.left / pageWidth).coerceIn(0f, 1f),
                (r.top / pageHeight).coerceIn(0f, 1f),
                (r.right / pageWidth).coerceIn(0f, 1f),
                (r.bottom / pageHeight).coerceIn(0f, 1f),
            )
        }
        if (text.isBlank() || rects.isEmpty()) return null
        fun place(p: PointF) = Place((p.x / pageWidth).coerceIn(0f, 1f), (p.y / pageHeight).coerceIn(0f, 1f))
        val first = rects.first()
        val last = rects.last()
        return Selection(
            page, text, rects,
            start = found.start?.let(::place) ?: Place(first.left, first.centerY()),
            stop = found.stop?.let(::place) ?: Place(last.right, last.centerY()),
        )
    }

    /**
     * The selection as it should be pasted: as the sentence it was before the page broke it into
     * lines. A line ending in a hyphen joins the next without a space, and keeps the hyphen,
     * since nothing tells "self-supervised" broken at its hyphen from a word broken to fit.
     */
    fun clip(text: String): String = text
        .replace(HYPHEN_AT_LINE_END, "-")
        .replace(WHITESPACE, " ")
        .trim()

    /** Half a point on a letter-size page, below anything a finger can mean. */
    private const val SAME = 0.0008f

    private val HYPHEN_AT_LINE_END = Regex("-[ \\t]*\\r?\\n\\s*")
    private val WHITESPACE = Regex("\\s+")
}
