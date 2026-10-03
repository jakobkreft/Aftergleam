package si.jakobkreft.aftergleam.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import si.jakobkreft.aftergleam.data.ArticleStore
import si.jakobkreft.aftergleam.data.Html
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Text sizes the reader view steps through, in percent. */
val ARTICLE_TEXT_STEPS = listOf(80, 90, 100, 115, 130, 150, 175, 200)

/**
 * The text size a pinch of [factor] leads to from [current].
 *
 * The nearest step to the pinched size, and at least one step in the pinch's direction, so a
 * small deliberate pinch is never ignored.
 */
fun pinchedTextZoom(current: Int, factor: Float): Int {
    if (abs(factor - 1f) < 0.08f) return current
    val nearest = ARTICLE_TEXT_STEPS.minBy { abs(it - current * factor) }
    return when {
        factor > 1f && nearest <= current -> ARTICLE_TEXT_STEPS.firstOrNull { it > current } ?: current
        factor < 1f && nearest >= current -> ARTICLE_TEXT_STEPS.lastOrNull { it < current } ?: current
        else -> nearest
    }
}

/**
 * The page the reader view shows: the paper's article, the stylesheet arXiv itself uses for
 * these papers, and the app's colours and font on top.
 *
 * The stylesheet is ar5iv-css 0.9.1, by Deyan Ginev (MIT licence, in the assets beside it),
 * the same file arXiv serves with these pages, shipped in the app's assets rather
 * than fetched. It knows every class LaTeXML writes, the coloured text, the tables' rules, the
 * pictures drawn in TeX, and a stylesheet written from scratch would get those wrong one paper
 * at a time. Shipped, it looks the same offline, and the app decides when it changes.
 */
internal object ArticleDocument {

    class Style(
        val dark: Boolean,
        val background: Color,
        val text: Color,
        val muted: Color,
        val link: Color,
        val serif: Boolean,
    )

    fun build(article: ArticleStore.Article, stylesheet: String, style: Style): String {
        val notice = if (article.hasErrors) {
            "<p class=\"aftergleam-notice\">Parts of this paper did not convert from LaTeX and " +
                "are shown in red. The PDF has them as written.</p>"
        } else ""
        return "<!DOCTYPE html><html lang=\"en\" data-theme=\"${if (style.dark) "dark" else "light"}\">" +
            "<head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>$stylesheet</style><style>${theme(style)}</style></head>" +
            "<body><div class=\"ltx_page_main\"><div class=\"ltx_page_content\">" +
            notice + widen(article.body) +
            "</div></div></body></html>"
    }

    private fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

    /**
     * Long formulas in running text, each in a box of its own that scrolls sideways.
     *
     * A formula cannot break across lines, so one longer than the screen is wide ran off the
     * edge of its paragraph and was cut off. The box is an inline flex box because that keeps
     * the formula on the line's baseline exactly, where an inline block would lift it.
     * Only long ones, by the length of their TeX: a short formula never needs it, and a page
     * of maths has thousands of them.
     *
     * Scanned by hand rather than with a regular expression. The obvious pattern took 34
     * seconds on a phone for a paper of 3.4 MB, against a few milliseconds on a desktop: the
     * phone's regex engine backtracks through every character of every formula.
     */
    internal fun widen(body: String): String {
        val out = StringBuilder(body.length + body.length / 50)
        var at = 0
        while (true) {
            val start = Html.findTag(body, "math", at)
            if (start < 0) break
            val tagEnd = body.indexOf('>', start)
            val end = if (tagEnd < 0) -1 else body.indexOf("</math>", tagEnd)
            if (end < 0) break
            val close = end + "</math>".length
            out.append(body, at, start)
            if (long(body.substring(start, tagEnd + 1))) {
                out.append("<span class=\"aftergleam-wide\">").append(body, start, close).append("</span>")
            } else {
                out.append(body, start, close)
            }
            at = close
        }
        return out.append(body, at, body.length).toString()
    }

    /** An inline formula whose TeX is long enough that it might not fit across a phone. */
    private fun long(tag: String): Boolean {
        if ("display=\"block\"" in tag) return false
        val a = tag.indexOf("alttext=\"")
        if (a < 0) return false
        val from = a + "alttext=\"".length
        val to = tag.indexOf('"', from)
        return to - from >= WIDE_TEX
    }

    private const val WIDE_TEX = 30

    /**
     * The app's look over ar5iv's. Plain rules outside any layer, which win over ar5iv's own,
     * all of which sit in layers.
     *
     * Beyond colours and font: the page never scrolls sideways. A wide table, equation, long
     * formula or row of sub-figures scrolls inside itself or wraps, so the reader only ever
     * scrolls down. Checked by laying out papers at phone width in Chromium and listing
     * anything past the edge that is not inside a box that scrolls (prototype/reader_audit.py).
     *
     * A footnote opens in the text under its line when its number is tapped, and closes on a
     * tap elsewhere; ar5iv's pop-up, made for a desktop's margin, opened mostly off the side of
     * a phone. And text is set ragged rather than justified, as the app sets its abstracts: on
     * a column as narrow as a phone, and narrower still at a larger size, justified lines open
     * wide gaps between words.
     */
    private fun theme(s: Style) = """
        :root {
          --background-color: ${hex(s.background)};
          --text-color: ${hex(s.text)};
          --border-color: ${hex(s.text)};
          --link-text-color: ${hex(s.link)};
          --text-font-family: ${if (s.serif) "\"Noto Serif\", serif" else "Roboto, \"Noto Sans\", sans-serif"};
          --main-width: 100%;
          -webkit-text-size-adjust: none;
          text-size-adjust: none;
        }
        html, body { overflow-x: hidden; }
        .ltx_para, .ltx_abstract .ltx_p, .ltx_acknowledgements, .ltx_theorem, .ltx_proof,
        .ltx_caption, .ltx_note { text-align: start; }
        body { background: var(--background-color); }
        .ltx_page_content { margin: 12px 16px 64px; }
        .ltx_document { max-width: none; }
        nav.ltx_TOC, .ltx_page_navbar, .ltx_page_header, .ltx_page_footer, .ltx_page_logo { display: none; }
        .ltx_eqn_table { display: block; max-width: 100%; overflow-x: auto; overflow-y: hidden; }
        .ltx_para > .ltx_tabular {
          display: block; width: fit-content; max-width: 100%; overflow-x: auto; margin-inline: auto;
        }
        .aftergleam-wide { display: inline-flex; max-width: 100%; overflow-x: auto; overflow-y: hidden; }
        .ltx_flex_figure { flex-wrap: wrap; }
        .ltx_flex_figure > .ltx_flex_cell { min-width: min(100%, 12rem); overflow-x: auto; }
        .ltx_flex_figure img.ltx_graphics { min-width: 0; }
        .ltx_minipage, .ltx_inline-block:not(.ltx_transformed_outer) { max-width: 100%; }
        .ltx_transformed_outer { max-width: 100%; overflow-x: auto; }
        .ltx_figure > *, .ltx_float > * { max-width: 100%; }
        .ltx_float_algorithm { overflow-x: auto; }
        .ltx_para > .ltx_p { overflow-wrap: anywhere; }
        .ltx_cite > .ltx_ref { white-space: normal; display: inline; }
        ul.ltx_itemize, ol.ltx_enumerate, dl.ltx_description { padding-inline-start: 2.5rem; }
        .ltx_note.ltx_note_frontmatter { position: static; width: auto; max-width: 100%; }
        .ltx_float { max-width: 100%; box-sizing: border-box; }
        .ltx_note_outer { max-width: 100%; box-sizing: border-box; }
        .ltx_note:not(.ltx_note_frontmatter):hover > .ltx_note_outer,
        .ltx_note:not(.ltx_note_frontmatter):active > .ltx_note_outer,
        .ltx_note:not(.ltx_note_frontmatter):focus-within > .ltx_note_outer {
          position: static; display: block; float: none; width: auto; max-width: none;
          margin: 0.4rem 0; padding: 0.2rem 0; inset: auto; opacity: 1;
          font-size: 0.9em; color: ${hex(s.muted)}; background: transparent;
        }
        .ltx_note_content > .ltx_note_mark { position: static; display: inline; margin: 0 0.3em 0 0; }
        .ltx_pubnotes:not(.ltx_pubnotes_meta):hover .ltx_pubnotes_content,
        .ltx_pubnotes:not(.ltx_pubnotes_meta):active .ltx_pubnotes_content,
        .ltx_pubnotes:not(.ltx_pubnotes_meta):focus-within .ltx_pubnotes_content {
          position: static; display: block; width: auto; max-width: 100%; box-sizing: border-box;
          margin-top: 0.4rem; box-shadow: none;
        }
        .ltx_authors { display: block; }
        .ltx_authors span.ltx_personname { max-width: 100%; }
        .ltx_authors .ltx_tabular { display: block; max-width: 100%; overflow-x: auto; }
        .ltx_role_email, .ltx_role_email *, .ltx_authors .ltx_font_typewriter, .ltx_authors a {
          overflow-wrap: anywhere;
        }
        .ltx_authors .ltx_author_notes { max-width: 100%; box-sizing: border-box; }
        .ltx_authors .ltx_role_affiliation, .ltx_authors .ltx_role_address { position: static; width: auto; }
        img, svg.ltx_picture { max-width: 100%; height: auto; }
        .undefined { color: var(--error-text-color); }
        .aftergleam-notice {
          font-family: sans-serif; font-size: 0.85rem; color: ${hex(s.muted)};
          border-left: 3px solid ${hex(s.muted)}; padding: 4px 10px; margin: 0 0 20px;
        }
    """.trimIndent()
}

/**
 * The reader view of one paper.
 *
 * A web view, because the article is HTML with MathML in it and the web view is the one
 * renderer on the phone that reads both. It runs no script, may not read files, and loads
 * nothing it is not given: the page is asked for by its address on arXiv and answered by the
 * app with the article it keeps, and each figure is asked of
 * [ArticleStore.resource], which reads it from the phone or fetches it once from the paper's
 * own folder on arXiv. Any other request is refused, so opening a paper this way contacts
 * nobody but arXiv. Links out of the paper open in the browser.
 *
 * Pinching changes the text size rather than magnifying the page, which is what keeps the
 * reading to one direction: the text wraps again at the new size.
 */
@Composable
fun ArticleView(
    paperId: String,
    article: ArticleStore.Article,
    textZoom: Int,
    position: Float,
    find: FindState,
    onPosition: (Float) -> Unit,
    onPinch: (Float) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ArticleStore(context) }
    val stylesheet by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("reader/ar5iv.min.css").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }
    val colors = MaterialTheme.colorScheme
    val style = ArticleDocument.Style(
        dark = colors.surface.luminance() < 0.5f,
        background = colors.surface,
        text = colors.onSurface,
        muted = colors.onSurfaceVariant,
        link = colors.primary,
        serif = LocalPaperFont.current == FontFamily.Serif,
    )
    // Built away from the main thread: a long paper is several megabytes of HTML.
    val document by produceState<ByteArray?>(null, article, stylesheet, style.dark, style.serif) {
        val css = stylesheet ?: return@produceState
        value = withContext(Dispatchers.Default) {
            ArticleDocument.build(article, css, style).toByteArray()
        }
    }
    val page = document
    if (page == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var web by remember { mutableStateOf<ArticleWebView?>(null) }
    var fraction by remember { mutableFloatStateOf(position) }
    var canGoBack by remember { mutableStateOf(false) }
    // The web view lays the page out in its own time, about two seconds on a phone for the
    // longest paper seen; until it has drawn, the spinner stays rather than an empty page.
    var drawn by remember { mutableStateOf(false) }
    val latestPinch by rememberUpdatedState(onPinch)

    // A tapped citation or footnote jumps within the paper; Back returns to where it was,
    // as in a browser, before it closes the reader.
    BackHandler(enabled = canGoBack && !find.open) {
        web?.goBack()
        canGoBack = web?.canGoBack() == true
    }

    LaunchedEffect(Unit) {
        snapshotFlow { fraction }.drop(1).debounce(600).collect { onPosition(it) }
    }

    LaunchedEffect(web, find.open, find.query) {
        val w = web ?: return@LaunchedEffect
        find.clearResults()
        w.clearMatches()
        if (!find.open || find.query.isBlank()) return@LaunchedEffect
        delay(250)
        find.searching = true
        w.findAllAsync(find.query.trim())
    }
    find.step = { forward -> web?.findNext(forward); find.jumps++ }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ArticleWebView(ctx).apply {
                    configure(
                        this, paperId, store, article.baseUrl, page,
                        onVisible = { drawn = true },
                        onLoaded = { restore(this, position) },
                        onHistory = { canGoBack = it },
                    )
                    setBackgroundColor(style.background.toArgb())
                    settings.textZoom = textZoom
                    onScrolled = { fraction = it }
                    setFindListener { active, total, done ->
                        find.current = if (total > 0) active else -1
                        find.total = total
                        find.searching = !done
                    }
                    // Asked for by its address on arXiv and answered from the phone, see
                    // configure. Not loadDataWithBaseURL, which hands the page over as a data
                    // address and drew nothing at all for a paper of 3.4 MB: those are capped at
                    // 2 MB, and papers long on equations pass that.
                    loadUrl(article.baseUrl)
                    web = this
                }
            },
            update = { view ->
                view.onPinch = { latestPinch(it) }
                if (view.settings.textZoom != textZoom) view.settings.textZoom = textZoom
            },
            onRelease = { it.destroy() },
        )
        if (!drawn) {
            Box(
                Modifier.fillMaxSize().background(style.background),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }

    DisposableEffect(Unit) { onDispose { web = null } }
}

/**
 * Settings and loading rules for the reader view. Kept apart from the composable so that what
 * the view may and may not do can be read in one place.
 */
private fun configure(
    view: WebView,
    paperId: String,
    store: ArticleStore,
    baseUrl: String,
    page: ByteArray,
    onVisible: () -> Unit,
    onLoaded: () -> Unit,
    onHistory: (Boolean) -> Unit,
) {
    view.settings.apply {
        javaScriptEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        // The text is resized instead, by textZoom, so it wraps rather than running off the
        // side of the screen.
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        domStorageEnabled = false
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
    }
    view.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Inline data, an image written into the page itself, needs nothing from anyone.
            if (request.url.scheme == "data") return null
            val url = request.url.toString()
            // The paper itself, built by the app.
            if (url.substringBefore('#') == baseUrl.substringBefore('#')) {
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(page))
            }
            val file = store.resource(paperId, url)
            return if (file != null) {
                WebResourceResponse(ArticleStore.mimeOf(file.name), null, FileInputStream(file))
            } else {
                WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not available", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // A jump within the paper: a section, an equation, a reference.
            if (url.substringBefore('#') == baseUrl.substringBefore('#')) return false
            if (request.url.scheme in setOf("http", "https", "mailto")) {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            }
            return true
        }

        private var loaded = false

        // Once: a jump to a footnote and back again is not a new page to restore.
        override fun onPageCommitVisible(view: WebView, url: String?) = onVisible()

        override fun onPageFinished(view: WebView, url: String?) {
            if (!loaded) onLoaded()
            loaded = true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            onHistory(view.canGoBack())
        }
    }
}

/**
 * Scrolls to where the paper was left. The page's height settles a moment after it reports
 * that it has loaded, so this tries a few times rather than scrolling against a page that
 * has no height yet.
 */
private fun restore(view: ArticleWebView, position: Float, attempt: Int = 0) {
    if (position <= 0f) return
    view.postDelayed({
        if (!view.scrollToFraction(position) && attempt < 20) restore(view, position, attempt + 1)
    }, 60L)
}

/**
 * A web view that reports pinches and how far down it is.
 *
 * The pinch is read alongside the view's own touch handling rather than instead of it, so a
 * one-finger scroll is the web view's as usual.
 */
@SuppressLint("ViewConstructor")
private class ArticleWebView(context: Context) : WebView(context) {
    var onPinch: (Float) -> Unit = {}
    var onScrolled: (Float) -> Unit = {}
    private var pinch = 1f

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinch = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinch *= detector.scaleFactor
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = onPinch(pinch)
        },
    )

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private val range: Int get() = computeVerticalScrollRange() - height

    /** How far down, from 0 at the title to 1 at the end. */
    val fraction: Float get() = range.let { if (it > 0) (scrollY.toFloat() / it).coerceIn(0f, 1f) else 0f }

    /** False while the page has no height to scroll through yet. */
    fun scrollToFraction(f: Float): Boolean {
        val r = range
        if (r <= 0) return false
        scrollTo(0, (f * r).roundToInt())
        return true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled(fraction)
    }
}
