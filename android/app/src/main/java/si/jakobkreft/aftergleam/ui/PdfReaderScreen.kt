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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import android.content.Intent
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
    /** Where the paper came from, for the one menu item that leaves the app. */
    sourceName: String,
    onOpenSource: () -> Unit,
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
                ReaderMenu(file, title, store, sourceName, onOpenSource)
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


/**
 * The reader's overflow menu.
 *
 * A menu rather than a row of icons, because the bar is already carrying a back button, the
 * paper's title and the zoom control, and the title is the part that suffers: it is one line
 * and ellipsised before anything is added to it. Three more icons would leave it showing about
 * two words. This is also the shape the library rows already use for the same problem.
 *
 * The three that earn a place are the ones the app cannot otherwise do. Sharing here sends the
 * PDF itself, which is a different act from the Share on the abstract screen: that one sends
 * the title and a link, and neither substitutes for the other when the person receiving it is
 * standing next to you with no signal. Opening in another app is how a reader gets annotation
 * and text selection, which this renderer deliberately does not have. The last one goes to the
 * paper's own page, which is the only honest reading of "open location": the file itself lives
 * in the app's private cache, where no file manager on the device can reach it.
 */
@Composable
private fun ReaderMenu(
    file: File,
    title: String,
    store: PdfStore,
    sourceName: String,
    onOpenSource: () -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    /** The download as something another app is allowed to read. */
    fun uri(): android.net.Uri = androidx.core.content.FileProvider.getUriForFile(
        context, context.packageName + ".files", file,
    )

    // Both inside one Box, so the menu anchors to the button rather than to the row it sits
    // in. As siblings of the row's other children the popup took the row's own origin and
    // opened against the far left of the screen, a long way from the control that opened it.
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Share this PDF") },
                onClick = {
                    open = false
                    // Sent as a copy named after the paper: see PdfStore.shareableCopy.
                    val named = runCatching { store.shareableCopy(file, title) }.getOrDefault(file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = store.mimeOf(file)
                        putExtra(
                            Intent.EXTRA_STREAM,
                            androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".files", named,
                            ),
                        )
                        // The paper's name, which is what a mail or message app puts in its
                        // subject line. Without it the attachment arrives titled by its cache
                        // filename, which is a DOI with the punctuation replaced.
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, "Share the PDF")) }
                },
            )
            DropdownMenuItem(
                text = { Text("Open with another app") },
                onClick = {
                    open = false
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri(), store.mimeOf(file))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    // A chooser rather than a direct launch: on a phone with no other PDF app
                    // startActivity throws and the item looks broken.
                    runCatching { context.startActivity(Intent.createChooser(view, "Open with")) }
                },
            )
            DropdownMenuItem(
                text = { Text("On $sourceName") },
                onClick = { open = false; onOpenSource() },
            )
        }
    }
}
