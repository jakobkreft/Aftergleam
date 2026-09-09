package si.jakobkreft.aftergleam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import si.jakobkreft.aftergleam.data.PdfStore
import java.io.File
import kotlin.math.roundToInt

/**
 * A pinch handler that claims only multi-touch.
 *
 * This is the crux of making a zoomable document scroll. `detectTransformGestures` consumes
 * single-finger drags as pan, so a list underneath it can only be scrolled in the gaps
 * between pages, which is exactly how the previous reader behaved. Here nothing is consumed
 * until a second finger is down, so one finger scrolls the list as usual and two fingers zoom.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPinchOnly(
    onPinch: (zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onPinch(zoom)
                    event.changes.forEach { it.consume() }
                }
                // Pan is consumed too while pinching, or the list lurches mid-gesture.
                if (event.calculatePan() != androidx.compose.ui.geometry.Offset.Zero) {
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Full-screen PDF reader.
 *
 * Zoom re-renders the page at the wider size rather than magnifying a bitmap, so text stays
 * sharp at any level; that is the whole reason to zoom a paper. Zoom levels are discrete and
 * the render is debounced, because re-rendering on every frame of a pinch is what made the
 * previous version stutter.
 *
 * Horizontal panning when zoomed is an ordinary horizontal scroll of content that is simply
 * wider than the screen, so it needs no gesture handling of its own.
 */
@Composable
fun PdfReaderScreen(
    file: File,
    title: String,
    store: PdfStore,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val hScroll = rememberScrollState()
    var zoom by remember { mutableFloatStateOf(1f) }

    val pages by produceState(0, file) { value = store.pageCount(file) }

    // Remember where the reader stopped, so reopening a long paper does not start again.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(500)
            .collect { onPageChanged(it) }
    }

    DisposableEffect(file) {
        onDispose { scope.launch { store.release() } }
    }

    val baseWidthPx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    // Discrete steps: a continuous zoom would ask for a fresh render of every visible page
    // on every frame of the pinch.
    val step = ZOOM_STEPS.minByOrNull { kotlin.math.abs(it - zoom) } ?: 1f
    val renderWidth = (baseWidthPx * step).roundToInt()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to the paper")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(step * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = {
                    zoom = ZOOM_STEPS.firstOrNull { it > step } ?: ZOOM_STEPS.first()
                    scope.launch { hScroll.scrollTo(0) }
                }) { Text(if (step >= ZOOM_STEPS.last()) "Fit" else "Zoom") }
            }

            Box(
                Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectPinchOnly { factor ->
                            zoom = (zoom * factor)
                                .coerceIn(ZOOM_STEPS.first(), ZOOM_STEPS.last())
                        }
                    }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().horizontalScroll(hScroll),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(pages) { index ->
                        PdfPage(store, file, index, renderWidth, baseWidthPx.roundToInt())
                    }
                }

                if (pages == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Text(
                        "${listState.firstVisibleItemIndex + 1} / $pages",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    content: @Composable (Int) -> Unit,
) = items(count = count, key = { it }) { content(it) }

@Composable
private fun PdfPage(
    store: PdfStore,
    file: File,
    index: Int,
    renderWidth: Int,
    baseWidth: Int,
) {
    // The aspect ratio is cheap to read and lets the placeholder take the page's real height,
    // so the list does not jump as pages arrive.
    val aspect by produceState(1.414f, file, index) { value = store.pageAspect(file, index) }
    val bitmap by produceState<Bitmap?>(null, file, index, renderWidth) {
        value = store.renderPage(file, index, renderWidth)
    }

    val widthDp = with(LocalDensity.current) { renderWidth.toDp() }
    val bmp = bitmap
    Box(
        Modifier
            .width(widthDp)
            .aspectRatio(1f / aspect)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp == null) CircularProgressIndicator()
        else Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val ZOOM_STEPS = listOf(1f, 1.5f, 2f, 3f)
