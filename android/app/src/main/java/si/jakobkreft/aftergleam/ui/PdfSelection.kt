package si.jakobkreft.aftergleam.ui

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import si.jakobkreft.aftergleam.data.PdfSelect
import si.jakobkreft.aftergleam.data.PdfStore
import java.io.File

/**
 * Text selected on the PDF's pages, the way text is selected anywhere else on the phone.
 *
 * A long press selects the word under the finger, and moving the finger before lifting it
 * carries the selection on from that word, either way. The handles at its ends move them, and
 * a tap anywhere on the pages lets it go, as Back does. Copy, and Select all for the page, are
 * in the platform's own floating bar, which keeps out of the way while a finger or the pages
 * are moving.
 *
 * One page at a time: the platform selects within a page, and a quotation that runs over a
 * page break is two copies.
 *
 * The platform is asked as the finger moves, on a worker, and only ever about where the
 * finger is now. A question still being answered when the finger moves on is dropped, so a
 * fast drag does not leave a queue of answers about where it used to be.
 */
@Stable
class PdfTextSelection(private val store: PdfStore, private val file: File) {

    var selection by mutableStateOf<PdfSelect.Selection?>(null)
        private set

    /** Whether a finger is drawing the selection or moving one of its ends. */
    var adjusting by mutableStateOf(false)
        internal set

    /** The pages laid out now, for placing the bar over the selection. */
    internal val pages = mutableMapOf<Int, LayoutCoordinates>()

    private sealed interface Ask

    /** The word at [at]: a long press. */
    private data class Word(val page: Int, val at: PdfSelect.Place) : Ask

    /** From the word pressed at [press] to [to], whichever side of it that is. */
    private data class FromWord(val page: Int, val press: PdfSelect.Place, val to: PdfSelect.Place) : Ask

    /** From [fixed] to [to]: an end moved, the other one staying where it is. */
    private data class Span(val page: Int, val fixed: PdfSelect.Place, val to: PdfSelect.Place) : Ask

    private val asks = MutableStateFlow<Ask?>(null)

    /** The word a finger is drawing the selection from. */
    private var word: PdfSelect.Selection? = null

    fun press(page: Int, at: PdfSelect.Place) {
        asks.value = Word(page, at)
    }

    fun drawTo(page: Int, press: PdfSelect.Place, to: PdfSelect.Place) {
        asks.value = FromWord(page, press, to)
    }

    fun moveEnd(page: Int, fixed: PdfSelect.Place, to: PdfSelect.Place) {
        asks.value = Span(page, fixed, to)
    }

    fun selectPage(page: Int) {
        asks.value = Span(page, PdfSelect.Place(0f, 0f), PdfSelect.Place(1f, 1f))
    }

    fun clear() {
        // Null rather than left as it was, so that pressing the same word again is asked again.
        asks.value = null
        word = null
        selection = null
    }

    /** Answers what the gestures ask, for as long as the pages are showing. */
    suspend fun answer() {
        asks.collectLatest { ask ->
            when (ask) {
                null -> Unit
                is Word -> {
                    word = store.select(file, ask.page, ask.at, ask.at)
                    selection = word
                }
                is FromWord -> {
                    val w = word?.takeIf { it.page == ask.page }
                        ?: store.select(file, ask.page, ask.press, ask.press)?.also { word = it }
                        ?: return@collectLatest
                    // Asked from the word's start, a finger before the word gets the text up to
                    // it and not the word itself; that is the sign to ask from its end instead.
                    val on = store.select(file, ask.page, w.start, ask.to)
                    selection = on?.takeIf { it.start.near(w.start) }
                        ?: store.select(file, ask.page, ask.to, w.stop)
                        ?: selection
                }
                // Over empty space the platform has no answer, and the selection stays as it was.
                is Span -> selection = store.select(file, ask.page, ask.fixed, ask.to) ?: selection
            }
        }
    }

    /** Where [s] is on the screen, cut to what of it is in sight; null when none of it is. */
    internal fun onScreen(s: PdfSelect.Selection): Rect? {
        val page = pages[s.page]?.takeIf { it.isAttached } ?: return null
        val w = page.size.width.toFloat()
        val h = page.size.height.toFloat()
        val left = s.rects.minOf { it.left } * w
        val top = s.rects.minOf { it.top } * h
        val right = s.rects.maxOf { it.right } * w
        val bottom = s.rects.maxOf { it.bottom } * h
        val rect = Rect(page.localToRoot(Offset(left, top)), page.localToRoot(Offset(right, bottom)))
        val shown = page.boundsInRoot()
        return if (rect.overlaps(shown)) rect.intersect(shown) else null
    }
}

/** The gestures that select text on page [page]; see [PdfTextSelection]. */
internal fun Modifier.selectsText(page: Int, state: PdfTextSelection, haptics: HapticFeedback): Modifier =
    pointerInput(page, state) {
        val reach = HANDLE_REACH.toPx()
        val handle = HANDLE.toPx()
        fun place(p: Offset) = PdfSelect.Place(
            (p.x / size.width).coerceIn(0f, 1f),
            (p.y / size.height).coerceIn(0f, 1f),
        )
        fun pixels(p: PdfSelect.Place) = Offset(p.x * size.width, p.y * size.height)

        awaitEachGesture {
            val down = awaitFirstDown()
            val selected = state.selection?.takeIf { it.page == page }

            // On a handle: that end follows the finger, and the other stays. The end is kept
            // where it was relative to the finger, which is a little above the handle, so that
            // the text being selected is not under the finger hiding it.
            val ends = selected?.let { handleCentres(it, size, handle) }
            val moving = when {
                selected == null || ends == null -> null
                (down.position - ends.first).getDistance() <= reach -> selected.start to selected.stop
                (down.position - ends.second).getDistance() <= reach -> selected.stop to selected.start
                else -> null
            }
            if (moving != null) {
                val (end, fixed) = moving
                val grip = pixels(end) - down.position
                down.consume()
                state.adjusting = true
                drag(down.id) {
                    it.consume()
                    state.moveEnd(page, fixed, place(it.position + grip))
                }
                state.adjusting = false
                return@awaitEachGesture
            }

            // Held in place long enough, a long press; lifted first, a tap. Moved, it was the
            // start of a scroll, and a pinch takes it from the other side: either way it is
            // not this gesture's.
            var taken = false
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation().also { taken = it == null }
            }
            when {
                up != null -> state.clear()
                taken -> Unit
                else -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val press = place(down.position)
                    state.press(page, press)
                    state.adjusting = true
                    drag(down.id) {
                        it.consume()
                        state.drawTo(page, press, place(it.position))
                    }
                    state.adjusting = false
                }
            }
        }
    }

/**
 * The platform's floating bar over the selection, as a text field has: Copy, and Select all
 * for the page. Shown when the selection is settled and [still], and hidden otherwise.
 */
@Composable
internal fun SelectionBar(state: PdfTextSelection, still: Boolean) {
    val toolbar = LocalTextToolbar.current
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection = state.selection
    val settled = still && !state.adjusting
    // Whether this bar is the one showing: the toolbar is the window's, and a text field's
    // may be using it.
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(selection, settled) {
        val rect = selection?.takeIf { settled }?.let(state::onScreen)
        if (selection == null || rect == null) {
            if (shown) toolbar.hide()
            shown = false
            return@LaunchedEffect
        }
        toolbar.showMenu(
            rect,
            onCopyRequested = {
                val text = PdfSelect.clip(selection.text)
                val clip = ClipData.newPlainText("Text from the paper", text)
                scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
                // From Android 13 the phone shows what was copied itself.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                state.clear()
            },
            onSelectAllRequested = { state.selectPage(selection.page) },
        )
        shown = true
    }
    DisposableEffect(toolbar) {
        onDispose { if (shown) toolbar.hide() }
    }
}

/** The selection on the page being drawn: its highlight, and a handle at either end. */
internal fun DrawScope.drawSelection(s: PdfSelect.Selection) {
    for (r in s.rects) {
        drawRect(
            SELECTED,
            topLeft = Offset(r.left * size.width, r.top * size.height),
            size = Size(r.width() * size.width, r.height() * size.height),
            blendMode = BlendMode.Multiply,
        )
    }
    val handle = HANDLE.toPx()
    val radius = handle / 2
    val (start, stop) = handleTips(s, size.width, size.height)
    // A drop hanging from each end, its square corner pointing up at the text: left of the
    // first letter, right of the last.
    drawCircle(HANDLE_COLOUR, radius, Offset(start.x - radius, start.y + radius))
    drawRect(HANDLE_COLOUR, Offset(start.x - radius, start.y), Size(radius, radius))
    drawCircle(HANDLE_COLOUR, radius, Offset(stop.x + radius, stop.y + radius))
    drawRect(HANDLE_COLOUR, Offset(stop.x, stop.y), Size(radius, radius))
}

/** Where the handles hang from: the bottom of the first line's start and the last line's end. */
private fun handleTips(s: PdfSelect.Selection, width: Float, height: Float): Pair<Offset, Offset> {
    val first = s.rects.first()
    val last = s.rects.last()
    return Offset(first.left * width, first.bottom * height) to
        Offset(last.right * width, last.bottom * height)
}

private fun handleCentres(s: PdfSelect.Selection, size: IntSize, handle: Float): Pair<Offset, Offset> {
    val (start, stop) = handleTips(s, size.width.toFloat(), size.height.toFloat())
    val r = handle / 2
    return Offset(start.x - r, start.y + r) to Offset(stop.x + r, stop.y + r)
}

/**
 * The light theme's green, in either theme: the pages are white in both, as find's marks
 * assume. Multiplied into the page, so the ink under the highlight stays black.
 */
private val HANDLE_COLOUR = Color(0xFF0B6B47)
private val SELECTED = HANDLE_COLOUR.copy(alpha = 0.4f)

/** The size of a handle, as the platform's text fields draw theirs. */
private val HANDLE = 22.dp

/** How far from a handle's middle a finger still takes it: a fingertip, not the drawn drop. */
private val HANDLE_REACH = 28.dp
