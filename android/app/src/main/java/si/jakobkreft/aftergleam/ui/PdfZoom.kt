package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The PDF's zoom levels, and the arithmetic of keeping a point still while zooming. */
object PdfZoom {
    const val MIN = 1f
    const val MAX = 3f

    /** What the Zoom button steps through. A pinch can stop anywhere between them. */
    val STEPS = listOf(1f, 1.5f, 2f, 3f)

    /** The Zoom button's next level: the next step up, or back to fitting the width from the last. */
    fun next(level: Float): Float = STEPS.firstOrNull { it > level + 0.01f } ?: STEPS.first()

    /** Whether the Zoom button would go back to fitting the width, and so says "Fit". */
    fun atMost(level: Float): Boolean = level >= STEPS.last() - 0.01f

    fun clamp(level: Float): Float = level.coerceIn(MIN, MAX)

    /**
     * Where to scroll to after zooming by [ratio], for the content under [focus], a distance
     * from the edge of the view, to stay under it. Content at [scroll] + [focus] moves to
     * ([scroll] + [focus]) × [ratio].
     */
    fun scrollAfter(scroll: Float, focus: Float, ratio: Float): Float = (scroll + focus) * ratio - focus
}

/**
 * The PDF reader's zoom: any level from fitting the width to three times it.
 *
 * The Zoom button steps through fixed levels, and a pinch could only snap between the same
 * ones, so it jumped from 100% to 150% to 200% under the fingers. Now a pinch scales what is on
 * screen as the fingers move, which costs nothing, and the pages are rendered again at exactly
 * the new size when they lift, which keeps the text sharp: rendering on every frame of a pinch
 * is what made the first reader stutter.
 *
 * The point between the fingers stays where it is, through the pinch and after it. Moving it
 * after the new size is laid out took a frame in which the page sat in the wrong place, so the
 * vertical position is asked of the list before that layout, and the horizontal one, which a
 * scroll cannot take until the page is wider, is drawn shifted for that one frame instead.
 */
@Stable
class PdfZoomState(private val list: LazyListState, private val across: ScrollState) {
    var level by mutableFloatStateOf(1f)
        private set

    /** A pinch in progress, as a scale on what is already drawn. */
    var pinch by mutableFloatStateOf(1f)
        private set

    /** Where the pinch started, in the page view. The scale is drawn about it. */
    var pinchAt by mutableStateOf(Offset.Zero)
        private set

    /** The one-frame horizontal shift described above. */
    var shiftX by mutableFloatStateOf(0f)
        private set

    /** The page view's size, set as it is laid out. */
    var view by mutableStateOf(IntSize.Zero)

    /** A page's width at 100%, in pixels: the width of the screen. */
    var baseWidth = 0f

    /** A page's width at this zoom, which is what the pages are rendered at. */
    val pageWidth: Int get() = (baseWidth * level).roundToInt()

    fun pinchBy(factor: Float, centroid: Offset) {
        if (pinch == 1f) pinchAt = centroid
        pinch = (pinch * factor).coerceIn(PdfZoom.MIN / level, PdfZoom.MAX / level)
    }

    fun endPinch(scope: CoroutineScope) = zoomTo(level * pinch, pinchAt, scope)

    /**
     * Zooms to [target], keeping what is at [focus] in the page view where it is. With
     * [toLeftEdge] the view goes to the pages' left edge instead, as the Zoom button does:
     * a paper is read from the start of its lines.
     */
    fun zoomTo(target: Float, focus: Offset, scope: CoroutineScope, toLeftEdge: Boolean = false) {
        val next = PdfZoom.clamp(target)
        val ratio = next / level
        if (abs(ratio - 1f) < 0.002f) {
            pinch = 1f
            return
        }
        val item = list.layoutInfo.visibleItemsInfo
            .firstOrNull { focus.y >= it.offset && focus.y < it.offset + it.size }
            ?: list.layoutInfo.visibleItemsInfo.firstOrNull()
        // Pages are as wide as the zoom, so the widest scroll after it is known in advance.
        val newWidth = (baseWidth * next).roundToInt()
        val newMax = (newWidth - view.width).coerceAtLeast(0)
        val targetX = if (toLeftEdge) 0
        else PdfZoom.scrollAfter(across.value.toFloat(), focus.x, ratio).roundToInt().coerceIn(0, newMax)
        // Where the layout will leave the old scroll: kept if it still fits, else at the end.
        val landed = across.value.coerceAtMost(newMax)

        level = next
        pinch = 1f
        if (item != null) {
            val within = (focus.y - item.offset) * ratio
            // Negative when the page now starts below the top of the view; the list then lays
            // out the pages before it to fill the space.
            list.requestScrollToItem(item.index, (within - focus.y).roundToInt())
        }
        shiftX = (landed - targetX).toFloat()
        scope.launch {
            withFrameNanos { }  // the frame that lays the pages out at the new size
            withFrameNanos { }  // and the one after it has been drawn
            across.scrollTo(targetX)
            shiftX = 0f
        }
    }
}
