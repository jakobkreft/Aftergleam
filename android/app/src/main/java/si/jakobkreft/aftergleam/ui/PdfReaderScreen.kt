package si.jakobkreft.aftergleam.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import si.jakobkreft.aftergleam.data.ArticleStore
import si.jakobkreft.aftergleam.data.PdfFind
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
    onPinch: (zoom: Float, centroid: Offset) -> Unit,
    onPinchEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    pinching = true
                    onPinch(zoom, event.calculateCentroid())
                    event.changes.forEach { it.consume() }
                }
                // Pan is consumed too while pinching, or the list lurches mid-gesture.
                if (event.calculatePan() != Offset.Zero) {
                    event.changes.forEach { it.consume() }
                }
            } else if (pinching) {
                // One finger has lifted: the pinch is over, and the other one may go on to
                // scroll as usual.
                pinching = false
                onPinchEnd()
            }
        } while (event.changes.any { it.pressed })
        if (pinching) onPinchEnd()
    }
}

/**
 * Full-screen reader: the PDF, or arXiv's HTML version of it in the reader view.
 *
 * Zoom re-renders the page at the wider size rather than magnifying a bitmap, so text stays
 * sharp at any level; that is the whole reason to zoom a paper. See [PdfZoomState] for how a
 * pinch stays smooth while doing so.
 *
 * Horizontal panning when zoomed is an ordinary horizontal scroll of content that is simply
 * wider than the screen, so it needs no gesture handling of its own.
 *
 * The reader view is the other way to read the same paper: text that wraps to the screen at
 * whatever size the reader picks, so a phone is read by scrolling down and nothing else. It
 * exists only where arXiv has made an HTML version, and the menu says so where it has not,
 * rather than offering a view built by guessing at the PDF's text. See [ArticleStore].
 *
 * Find in paper works in both, with the same bar.
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
    /** The reader's current judgement of this paper, so the menu can show it. */
    liked: Boolean?,
    saved: Boolean,
    onSteer: (Boolean?) -> Unit,
    onSave: () -> Unit,
    onShareLink: () -> Unit,
    /** Records that the PDF itself was shared, which is as strong a signal as the link. */
    onShared: () -> Unit,
    /** Deletes the copy on the phone and fetches it again, for a file that will not open. */
    onRedownload: () -> Unit,
    paperId: String,
    /** The reader view: whether there is one, and whether it is showing. */
    article: ArticleUi,
    articleTextZoom: Int,
    onShowArticle: () -> Unit,
    onShowPdf: () -> Unit,
    onArticlePosition: (Float) -> Unit,
    onArticleTextZoom: (Int) -> Unit,
    /** Whether the reader view's page is dark, which can differ from the app. */
    articleDark: Boolean,
    onToggleArticleTheme: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Held here rather than with the pages, so that a trip to the reader view and back
    // returns to the same page at the same zoom.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val hScroll = rememberScrollState()
    val zoom = remember { PdfZoomState(listState, hScroll) }
    val find = remember { FindState() }

    // Null while the document is opening, zero if it could not be. Starting at zero conflated
    // the two: a damaged PDF reported no pages and the reader showed its loading spinner for
    // good, with nothing to say the wait would never end.
    val pages by produceState<Int?>(null, file) { value = store.pageCount(file) }

    // Remember where the reader stopped, so reopening a long paper does not start again.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(500)
            .collect { onPageChanged(it) }
    }

    DisposableEffect(file) {
        onDispose { scope.launch { store.release() } }
    }

    // Back closes the find bar first, as it closes a keyboard first.
    BackHandler(enabled = find.open) { find.close() }
    // Matches belong to the view they were found in, so switching views ends the search.
    LaunchedEffect(article.showing) { find.close() }

    val baseWidthPx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    zoom.baseWidth = baseWidthPx
    val renderWidth = zoom.pageWidth
    val showingArticle = article.showing
    val canFind = if (showingArticle) article.article != null
        else PdfFind.supported && (pages ?: 0) > 0

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            if (find.open) {
                FindBar(find)
            } else Row(
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
                if (showingArticle) {
                    TextSizeButtons(articleTextZoom, onArticleTextZoom)
                    IconButton(onClick = onToggleArticleTheme) {
                        Icon(
                            painterResource(
                                if (articleDark) si.jakobkreft.aftergleam.R.drawable.ic_light_page
                                else si.jakobkreft.aftergleam.R.drawable.ic_dark_page
                            ),
                            contentDescription = if (articleDark) "Light page" else "Dark page",
                        )
                    }
                } else {
                    Text(
                        "${(zoom.level * zoom.pinch * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = {
                        zoom.zoomTo(PdfZoom.next(zoom.level), Offset.Zero, scope, toLeftEdge = true)
                    }) { Text(if (PdfZoom.atMost(zoom.level)) "Fit" else "Zoom") }
                }
                ReaderMenu(
                    file, title, store, sourceName, onOpenSource,
                    liked, saved, onSteer, onSave, onShareLink, onShared,
                    canFind = canFind,
                    onFind = { find.open = true },
                    article = article,
                    onShowArticle = onShowArticle,
                    onShowPdf = onShowPdf,
                )
            }

            Box(Modifier.weight(1f)) {
                if (showingArticle) {
                    ArticleContent(
                        paperId, article, articleTextZoom, find,
                        onShowArticle, onShowPdf, onArticlePosition,
                    )
                } else {
                    PdfPages(
                        file, store, pages, listState, hScroll, find,
                        renderWidth = renderWidth,
                        baseWidthPx = baseWidthPx,
                        zoom = zoom,
                        onRedownload = onRedownload,
                    )
                }
            }
        }
    }
}

/**
 * Smaller and larger text, for the reader view.
 *
 * Two letters A in two sizes, the convention of every reading app, rather than a percentage:
 * the text itself changes as they are pressed, and that is the feedback.
 */
@Composable
private fun TextSizeButtons(zoom: Int, onZoom: (Int) -> Unit) {
    val smaller = ARTICLE_TEXT_STEPS.lastOrNull { it < zoom }
    val larger = ARTICLE_TEXT_STEPS.firstOrNull { it > zoom }
    IconButton(
        onClick = { smaller?.let(onZoom) },
        enabled = smaller != null,
        modifier = Modifier.semantics { contentDescription = "Smaller text" },
    ) { Text("A", fontSize = 14.sp) }
    IconButton(
        onClick = { larger?.let(onZoom) },
        enabled = larger != null,
        modifier = Modifier.semantics { contentDescription = "Larger text" },
    ) { Text("A", fontSize = 21.sp) }
}

/** The reader view, or why it is not there yet. */
@Composable
private fun ArticleContent(
    paperId: String,
    article: ArticleUi,
    textZoom: Int,
    find: FindState,
    onRetry: () -> Unit,
    onShowPdf: () -> Unit,
    onPosition: (Float) -> Unit,
) {
    val loaded = article.article
    when {
        article.error != null -> Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(article.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            if (article.availability != ArticleStore.Availability.NONE) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onShowPdf, modifier = Modifier.fillMaxWidth()) {
                Text("Back to the PDF")
            }
        }

        loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Fetching the HTML version", style = MaterialTheme.typography.labelSmall)
            }
        }

        // Keyed on the theme too: the page is built with the app's colours, so a switch to
        // dark mode while reading builds it again rather than leaving it light.
        else -> key(loaded, MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            ArticleView(
                paperId = paperId,
                article = loaded,
                textZoom = textZoom,
                position = article.position,
                find = find,
                onPosition = onPosition,
            )
        }
    }
}

/** The PDF's pages, with find in paper's highlights over them. */
@Composable
private fun PdfPages(
    file: File,
    store: PdfStore,
    pages: Int?,
    listState: LazyListState,
    hScroll: ScrollState,
    find: FindState,
    renderWidth: Int,
    baseWidthPx: Float,
    zoom: PdfZoomState,
    onRedownload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var matches by remember { mutableStateOf<List<PdfFind.Match>>(emptyList()) }
    val byPage = remember(matches) { matches.groupBy { it.page } }

    // Searched a page at a time, in order, with the count shown as it grows, and the first
    // match at or after the page being read shown as soon as it is found. A new letter typed
    // restarts the effect, which cancels the search that was under way.
    LaunchedEffect(find.open, find.query, pages) {
        matches = emptyList()
        find.clearResults()
        val n = pages ?: return@LaunchedEffect
        val variants = PdfFind.variants(find.query)
        if (!find.open || variants.isEmpty() || n == 0) return@LaunchedEffect
        delay(250)
        find.searching = true
        val from = listState.firstVisibleItemIndex
        val found = ArrayList<PdfFind.Match>()
        var capped = false
        for (i in 0 until n) {
            found += store.find(file, i, variants)
            if (found.size >= PdfFind.MAX_MATCHES) {
                capped = true
                break
            }
            if (i % 4 == 3) {
                matches = found.toList()
                find.total = found.size
                if (find.current < 0) {
                    val k = found.indexOfFirst { it.page >= from }
                    if (k >= 0) { find.current = k; find.jumps++ }
                }
            }
        }
        val all = found.take(PdfFind.MAX_MATCHES)
        matches = all
        find.total = all.size
        find.capped = capped
        if (find.current < 0) {
            find.current = PdfFind.firstFrom(all, from)
            if (find.current >= 0) find.jumps++
        }
        find.searching = false
        if (all.isEmpty() && !store.hasText(file)) {
            find.note = "This PDF has no text to search, so it is probably a scan."
        }
    }
    find.step = { forward ->
        if (find.total > 0) {
            find.current = Math.floorMod(find.current + if (forward) 1 else -1, find.total)
            find.jumps++
        }
    }

    // Brings the current match on screen, a third of the way down, unless it is already
    // comfortably in view; and across, when the page is zoomed wider than the screen.
    LaunchedEffect(find.jumps) {
        val m = matches.getOrNull(find.current) ?: return@LaunchedEffect
        val r = m.rects.firstOrNull() ?: return@LaunchedEffect
        val itemHeight = renderWidth * m.aspect
        val viewport = listState.layoutInfo.viewportSize.height
        val top = r.top * itemHeight
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == m.page }
        val inView = item != null &&
            item.offset + top >= viewport * 0.08f &&
            item.offset + r.bottom * itemHeight <= viewport * 0.85f
        if (!inView) {
            val offset = (top - viewport * 0.3f).roundToInt()
            if (offset >= 0) {
                listState.scrollToItem(m.page, offset)
            } else {
                listState.scrollToItem(m.page)
                listState.scrollBy(offset.toFloat())
            }
        }
        val x = r.centerX() * renderWidth
        if (x < hScroll.value + baseWidthPx * 0.1f || x > hScroll.value + baseWidthPx * 0.9f) {
            hScroll.scrollTo((x - baseWidthPx / 2).roundToInt().coerceIn(0, hScroll.maxValue))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { zoom.view = it }
            .pointerInput(Unit) {
                detectPinchOnly(
                    onPinch = { factor, centroid -> zoom.pinchBy(factor, centroid) },
                    onPinchEnd = { zoom.endPinch(scope) },
                )
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Read in the drawing phase, so a pinch redraws the pages without composing
                // or laying them out again on every frame.
                .graphicsLayer {
                    scaleX = zoom.pinch
                    scaleY = zoom.pinch
                    val v = zoom.view
                    transformOrigin = if (v.width > 0 && v.height > 0) {
                        TransformOrigin(zoom.pinchAt.x / v.width, zoom.pinchAt.y / v.height)
                    } else TransformOrigin.Center
                }
                .horizontalScroll(hScroll)
                // Inside the scroll, so the shift moves the pages within the view rather than
                // moving the view and showing nothing beside it.
                .graphicsLayer { translationX = zoom.shiftX },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(pages ?: 0) { index ->
                PdfPage(
                    store, file, index, renderWidth,
                    marks = byPage[index].orEmpty(),
                    current = matches.getOrNull(find.current)?.takeIf { it.page == index },
                )
            }
        }

        if (pages == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (pages == 0) {
            Unreadable(file, store, onRedownload)
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

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    content: @Composable (Int) -> Unit,
) = items(count = count, key = { it }) { content(it) }

/**
 * Find in paper's marks: a highlighter's yellow on every match and orange on the current one,
 * as browsers do. Multiplied into the page rather than painted over it, so the ink under a
 * mark stays black and the mark reads as a highlighter's, not a sticker's. Pages are white in
 * either theme, so the colours are fixed.
 */
private val MARK = Color(0xFFFFE45C)
private val MARK_CURRENT = Color(0xFFFF9B3D)

@Composable
private fun PdfPage(
    store: PdfStore,
    file: File,
    index: Int,
    renderWidth: Int,
    marks: List<PdfFind.Match>,
    current: PdfFind.Match?,
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
            .background(Color.White)
            .drawWithContent {
                drawContent()
                for (m in marks) {
                    val colour = if (m == current) MARK_CURRENT else MARK
                    for (r in m.rects) {
                        drawRect(
                            colour,
                            topLeft = Offset(r.left * size.width, r.top * size.height),
                            size = Size(r.width() * size.width, r.height() * size.height),
                            blendMode = BlendMode.Multiply,
                        )
                    }
                }
            },
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



/**
 * The reader's overflow menu.
 *
 * A menu rather than a row of icons, because the bar is already carrying a back button, the
 * paper's title and the zoom control, and the title is the part that suffers: it is one line
 * and ellipsised before anything is added to it.
 *
 * Four groups, in the order they are wanted while reading.
 *
 * How to read the paper comes first: finding a word in it, and the reader view. These are
 * used over and over while reading, so they sit where the thumb lands.
 *
 * Then the judgements. The reader is where an opinion about a paper is actually formed,
 * and leaving it to find the heart on the card means losing the page. They are the card's own
 * three actions with the card's own icons, and each shows whether it is already on, because a
 * menu has no other way to say so. Choosing one that is on turns it off, as on the card.
 *
 * Then sharing, in both forms. The link is what most people send; the file is for somebody
 * with no signal or behind a paywall-free mirror nobody can find. They are different acts and
 * neither substitutes for the other. Both record that the paper was shared, as the abstract
 * screen's Share always has: passing a paper on is one of the strongest signals there is.
 *
 * Last, the two ways out: another app, for annotation and text selection this renderer
 * deliberately does not have, and the paper's own page. That is the only honest reading of
 * "open location", because the file itself lives in the app's private cache where no file
 * manager can reach it.
 */
@Composable
private fun ReaderMenu(
    file: File,
    title: String,
    store: PdfStore,
    sourceName: String,
    onOpenSource: () -> Unit,
    liked: Boolean?,
    saved: Boolean,
    onSteer: (Boolean?) -> Unit,
    onSave: () -> Unit,
    onShareLink: () -> Unit,
    onShared: () -> Unit,
    canFind: Boolean,
    onFind: () -> Unit,
    article: ArticleUi,
    onShowArticle: () -> Unit,
    onShowPdf: () -> Unit,
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
            if (canFind) {
                DropdownMenuItem(
                    text = { Text("Find in paper") },
                    onClick = { open = false; onFind() },
                )
            }
            if (article.showing) {
                DropdownMenuItem(
                    text = { Text("PDF view") },
                    onClick = { open = false; onShowPdf() },
                )
            } else {
                ReaderViewItem(article.availability, onClick = { open = false; onShowArticle() })
            }

            HorizontalDivider()

            Judgement(
                icon = { tint -> Icon(Icons.Filled.Favorite, null, tint = tint) },
                label = "More like this",
                on = liked == true,
                onClick = { open = false; onSteer(if (liked == true) null else true) },
            )
            Judgement(
                icon = { tint -> Icon(Icons.Filled.Clear, null, tint = tint) },
                label = "Less like this",
                on = liked == false,
                onClick = { open = false; onSteer(if (liked == false) null else false) },
            )
            Judgement(
                icon = { tint ->
                    Icon(
                        painterResource(si.jakobkreft.aftergleam.R.drawable.ic_bookmark),
                        null,
                        tint = tint,
                    )
                },
                label = if (saved) "Saved" else "Save for later",
                on = saved,
                onClick = { open = false; onSave() },
            )

            HorizontalDivider()

            DropdownMenuItem(
                text = { Text("Share link") },
                onClick = { open = false; onShareLink() },
            )
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
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share the PDF"))
                        onShared()
                    }
                },
            )

            HorizontalDivider()

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

/**
 * The reader view, as a menu row that says what it is, or why it is not there.
 *
 * Shown greyed out rather than hidden when there is no HTML version, so that a reader who
 * found it on one paper is not left wondering where it went on the next. The second line is
 * the reason, in the reader's terms.
 */
@Composable
private fun ReaderViewItem(availability: ArticleStore.Availability, onClick: () -> Unit) {
    val (enabled, note) = when (availability) {
        ArticleStore.Availability.READY,
        ArticleStore.Availability.AVAILABLE -> true to "Text that fits the screen, from arXiv\u2019s HTML"
        ArticleStore.Availability.CHECKING -> false to "Checking arXiv for an HTML version"
        ArticleStore.Availability.NONE -> false to "arXiv has no HTML version of this paper"
        ArticleStore.Availability.NOT_ARXIV -> false to "Only for arXiv papers"
        ArticleStore.Availability.UNREACHABLE -> true to "Could not reach arXiv to check"
    }
    DropdownMenuItem(
        enabled = enabled,
        text = {
            Column {
                Text("Reader view")
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * One of the three judgements, as a menu row.
 *
 * The icon takes the primary colour and a check appears at the end when it is on. The tint
 * alone is what the cards use, but a card shows all three side by side where the difference
 * is obvious; a menu row stands on its own, and a check is the convention for "this is set".
 */
@Composable
private fun Judgement(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { icon(tint) },
        trailingIcon = if (on) {
            { Icon(Icons.Filled.Check, contentDescription = "On", tint = tint) }
        } else null,
    )
}

/**
 * A downloaded file that looks like a PDF and will not open as one.
 *
 * Usually a damaged download or a server that sent an error page with a PDF's first bytes.
 * Downloading again fixes the first; another app, with a more forgiving renderer, sometimes
 * reads what this one refuses.
 */
@Composable
private fun Unreadable(file: File, store: PdfStore, onRedownload: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("This PDF will not open", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "The file on your phone looks damaged. Downloading it again usually fixes this.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRedownload, modifier = Modifier.fillMaxWidth()) {
            Text("Download again")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        androidx.core.content.FileProvider.getUriForFile(
                            context, context.packageName + ".files", file,
                        ),
                        store.mimeOf(file),
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(view, "Open with")) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open with another app") }
    }
}
