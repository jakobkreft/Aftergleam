package si.jakobkreft.aftergleam

import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.ArticleStore
import si.jakobkreft.aftergleam.data.Html
import si.jakobkreft.aftergleam.data.PdfFind
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.ui.ArticleDocument
import si.jakobkreft.aftergleam.ui.PdfZoom
import si.jakobkreft.aftergleam.ui.ReaderTheme
import java.io.File

/**
 * Find in paper and the reader view, in the parts that do not need a PDF renderer or a web
 * view: what is searched for, the order matches are shown in, what is kept of arXiv's page,
 * and what the reader view is allowed to load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `an apostrophe is searched for both ways, since papers print a curly one`() {
        assertEquals(listOf("camera's", "camera’s"), PdfFind.variants("camera’s"))
        assertEquals(listOf("camera's", "camera’s"), PdfFind.variants(" camera's "))
        assertEquals(listOf("neural crest"), PdfFind.variants("neural   crest"))
        assertTrue(PdfFind.variants("   ").isEmpty())
    }

    private fun bounds(vararg r: Float) = listOf(RectF(r[0], r[1], r[2], r[3]))

    @Test
    fun `matches come in reading order, once each, as fractions of the page`() {
        val found = listOf(
            40 to bounds(300f, 100f, 360f, 110f),
            5 to bounds(60f, 50f, 120f, 60f),
            40 to bounds(300f, 100f, 360f, 110f), // the same match, from the other spelling
            12 to emptyList(),
        )
        val m = PdfFind.onPage(3, pageWidth = 600f, pageHeight = 800f, found = found)
        assertEquals(listOf(5, 40), m.map { it.start })
        assertTrue(m.all { it.page == 3 && it.aspect == 800f / 600f })
        val r = m.first().rects.single()
        assertEquals(0.1f, r.left, 1e-6f)
        assertEquals(0.2f, r.right, 1e-6f)
        assertTrue("a little taller than the glyphs", r.top < 50f / 800f && r.bottom > 60f / 800f)
        assertTrue(PdfFind.onPage(0, 0f, 800f, found).isEmpty())
    }

    private fun match(page: Int) = PdfFind.Match(page, 0, emptyList(), 1.4f)

    @Test
    fun `the first match shown is the first from the page being read, wrapping round`() {
        val matches = listOf(match(1), match(4), match(4), match(9))
        assertEquals(1, PdfFind.firstFrom(matches, page = 3))
        assertEquals(0, PdfFind.firstFrom(matches, page = 0))
        assertEquals("nothing after page 10, so back to the start", 0, PdfFind.firstFrom(matches, 10))
        assertEquals(-1, PdfFind.firstFrom(emptyList(), 2))
    }

    @Test
    fun `the count says when it is still growing or capped`() {
        assertEquals("3 of 27", PdfFind.count(2, 27, capped = false, searching = false))
        assertEquals("3 of 27…", PdfFind.count(2, 27, capped = false, searching = true))
        assertEquals("1 of 1000+", PdfFind.count(0, 1000, capped = true, searching = false))
        assertEquals("No matches", PdfFind.count(-1, 0, capped = false, searching = false))
        assertEquals("…", PdfFind.count(-1, 0, capped = false, searching = true))
    }

    /** The shape of one of arXiv's HTML pages, with its site around the paper. */
    private val page = """
        <!DOCTYPE html><html><head><title>A paper</title>
        <link rel="stylesheet" href="/static/browse/0.3.4/css/arxiv-html-papers.css">
        <script src="/static/browse/0.3.4/js/arxiv-html-papers.js"> </script>
        <script>initializeReadingPreferences();</script></head>
        <body><header class="arxiv-html-header"><a href="/">arXiv</a></header>
        <div class="ltx_page_main"><div class="ltx_page_content">
        <article class="ltx_document ltx_authors_1line">
        <h1 class="ltx_title ltx_title_document">A paper</h1>
        <script>alert(1)</script>
        <p class="ltx_p">Text with <math alttext="x"><mi>x</mi></math> and
        <img src="2610.02210v1/figs/x1.png" class="ltx_graphics" width="200" height="100"></p>
        <span class="ltx_ERROR undefined">\foo</span>
        </article></div></div>
        <footer class="arxiv-html-footer">Funders</footer></body></html>
    """.trimIndent()

    @Test
    fun `only the paper is kept of arXiv's page, without scripts or stylesheets`() {
        val body = ArticleStore.extract(page)
        assertNotNull(body)
        body!!
        assertTrue(body.startsWith("<article class=\"ltx_document"))
        assertTrue(body.endsWith("</article>"))
        assertTrue("<math" in body && "x1.png" in body)
        assertFalse("<script" in body)
        assertFalse("arxiv-html-header" in body || "Funders" in body)
        assertNull("arXiv's 'No HTML' page has no paper in it",
            ArticleStore.extract("<html><body><h1>No HTML for '2610.00023'</h1></body></html>"))
    }

    @Test
    fun `the reader view loads only the paper's own files from arXiv`() {
        val id = "2610.02210"
        assertTrue(ArticleStore.allowed(id, "https://arxiv.org/html/2610.02210v1/figs/x1.png"))
        assertTrue(ArticleStore.allowed(id, "https://arxiv.org/html/2610.02210/x2.svg"))
        assertFalse("another paper", ArticleStore.allowed(id, "https://arxiv.org/html/2610.022101/x1.png"))
        assertFalse(ArticleStore.allowed(id, "https://arxiv.org/static/browse/0.3.4/css/x.css"))
        assertFalse(ArticleStore.allowed(id, "https://use.typekit.net/utz6mli.css"))
        assertFalse(ArticleStore.allowed(id, "https://arxiv.org/html/2610.02210v1/../2610.09999v1/x.png"))
        assertFalse(ArticleStore.allowed(id, "http://arxiv.org/html/2610.02210v1/x1.png"))
    }

    @Test
    fun `a fetched figure is kept under a name it cannot escape the folder with`() {
        val a = ArticleStore.localName("https://arxiv.org/html/2610.02210v1/figs/x1.png")
        val b = ArticleStore.localName("https://arxiv.org/html/2610.02210v1/figs/x2.png")
        assertTrue(a.endsWith(".png") && '/' !in a)
        assertTrue(a != b)
        assertEquals(a, ArticleStore.localName("https://arxiv.org/html/2610.02210v1/figs/x1.png"))
        assertEquals("image/png", ArticleStore.mimeOf(a))
        assertFalse('.' in ArticleStore.localName("https://arxiv.org/html/2610.02210v1/figs/x1.p\$ng"))
    }

    @Test
    fun `a kept article is read back, counted and deleted with the paper`() {
        val store = ArticleStore(ctx)
        val dir = File(ctx.cacheDir, "html/2610.02210").apply { mkdirs() }
        File(dir, "article.html").writeText(ArticleStore.extract(page)!!)
        File(dir, "base.txt").writeText("https://arxiv.org/html/2610.02210")
        assertTrue(store.isCached("2610.02210"))
        val article = store.cached("2610.02210")!!
        assertEquals("https://arxiv.org/html/2610.02210", article.baseUrl)
        assertTrue(article.hasErrors)
        assertTrue(store.sizeOf("2610.02210") > 0)
        store.delete("2610.02210")
        assertFalse(store.isCached("2610.02210"))
        assertNull(store.cached("2610.02210"))
    }

    @Test
    fun `current conversions mark what failed with a bare class, and that counts too`() {
        val body = "<article class=\"ltx_document\"><math><mtext class=\"undefined\">\\widecheck</mtext></math></article>"
        assertTrue(ArticleStore.Article(body, "https://arxiv.org/html/x").hasErrors)
        assertFalse(ArticleStore.Article("<article class=\"ltx_document\"></article>", "").hasErrors)
    }

    @Test
    fun `the page shown runs no script, takes the app's theme and says what did not convert`() {
        val article = ArticleStore.Article(ArticleStore.extract(page)!!, "https://arxiv.org/html/2610.02210")
        val style = ArticleDocument.Style(
            dark = true, background = Color(0xFF121212), text = Color(0xFFEEEEEE),
            muted = Color(0xFF999999), link = Color(0xFF88CC88), serif = true,
        )
        val html = ArticleDocument.build(article, stylesheet = ".ltx_p{}", style = style)
        assertTrue("data-theme=\"dark\"" in html)
        assertTrue("#121212" in html && "serif" in html)
        assertTrue("did not convert" in html)
        assertFalse("<script" in html)
        val clean = ArticleStore.Article("<article class=\"ltx_document\"><p>ok</p></article>", article.baseUrl)
        assertFalse("did not convert" in ArticleDocument.build(clean, "", style))
    }

    @Test
    fun `tags are found by whole name, and removed with what they hold`() {
        val html = "<p>a<SCRIPT type=x>bad()</script>b<mathvariant/><math alttext=\"x\">x</math>" +
            "<link rel=\"stylesheet\" href=\"x.css\">c<script>unclosed"
        assertEquals("not <mathvariant", 48, Html.findTag(html, "math", 0))
        assertEquals(4, Html.findTag(Html.lowered(html), "script", 0))
        assertEquals("<p>ab<mathvariant/><math alttext=\"x\">x</math><link rel=\"stylesheet\" href=\"x.css\">c",
            Html.removeElements(html, "script"))
        assertFalse("<link" in Html.removeTags(html, "link"))
        assertEquals("no tag, same string", "plain", Html.removeElements("plain", "script"))
    }

    @Test
    fun `long formulas in running text get a box that scrolls, short and display ones do not`() {
        val long = "<math alttext=\"${"a+".repeat(20)}b\" display=\"inline\"><mi>a</mi></math>"
        val short = "<math alttext=\"x_i\" display=\"inline\"><mi>x</mi></math>"
        val block = "<math alttext=\"${"a+".repeat(20)}b\" display=\"block\"><mi>a</mi></math>"
        val out = ArticleDocument.widen("<p>$long and $short</p>$block")
        assertEquals("<p><span class=\"aftergleam-wide\">$long</span> and $short</p>$block", out)
    }

    @Test
    fun `a paper of several megabytes is prepared in well under a second`() {
        // The size of the longest paper seen, with as many formulas. A regular expression
        // took 34 seconds over it on a phone; the scan has to stay linear.
        val formula = "<math alttext=\"${"x".repeat(35)}\" display=\"inline\"><mi>x</mi></math>"
        val body = "<article class=\"ltx_document\">" +
            "<p class=\"ltx_p\">${"Text around a formula, ".repeat(10)}$formula</p>".repeat(9000) + "</article>"
        assertTrue("${body.length}", body.length > 3_000_000)
        val t = System.nanoTime()
        val page = "<html><body><script>x()</script>$body<footer>f</footer></body></html>"
        val widened = ArticleDocument.widen(ArticleStore.extract(page)!!)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(9000, Regex("aftergleam-wide").findAll(widened).count())
        assertTrue("took $ms ms", ms < 1000)
    }

    @Test
    fun `the reader's page follows the app until the reader picks the other, and back`() {
        assertTrue("follows a dark app", ReaderTheme.dark(null, appDark = true))
        assertFalse(ReaderTheme.dark(null, appDark = false))
        val light = ReaderTheme.toggled(null, appDark = true)
        assertEquals("a light page in a dark app is remembered", "light", light)
        assertFalse(ReaderTheme.dark(light, appDark = true))
        assertFalse("still light when the system turns light", ReaderTheme.dark(light, appDark = false))
        assertNull("choosing the app's own again follows it", ReaderTheme.toggled(light, appDark = true))
        assertEquals("dark", ReaderTheme.toggled(null, appDark = false))
        val prefs = Prefs(ctx)
        prefs.articleTheme = "light"
        assertEquals("light", prefs.articleTheme)
        prefs.articleTheme = null
        assertNull(prefs.articleTheme)
    }

    @Test
    fun `the zoom button steps from wherever a pinch left it, and the point between the fingers stays put`() {
        assertEquals(1.5f, PdfZoom.next(1f))
        assertEquals("from a pinched 137%", 1.5f, PdfZoom.next(1.37f))
        assertEquals(3f, PdfZoom.next(2.6f))
        assertEquals("fit again after the last", 1f, PdfZoom.next(3f))
        assertTrue(PdfZoom.atMost(3f))
        assertFalse(PdfZoom.atMost(2.6f))
        assertEquals(1f, PdfZoom.clamp(0.4f))
        assertEquals(3f, PdfZoom.clamp(5f))
        // Content 300 px into a page scrolled by 100, under a finger 200 px from the edge,
        // doubled: it is now 600 px in, so the page scrolls to 400 to keep it under the finger.
        assertEquals(400f, PdfZoom.scrollAfter(scroll = 100f, focus = 200f, ratio = 2f), 1e-4f)
        assertEquals(-50f, PdfZoom.scrollAfter(scroll = 0f, focus = 100f, ratio = 0.5f), 1e-4f)
    }

    @Test
    fun `a paper reopens in the view it was left in`() {
        val prefs = Prefs(ctx)
        assertNull("a PDF unless the reader view was chosen", prefs.articlePosition("p1"))
        prefs.setArticlePosition("p1", 0.4f)
        assertEquals(0.4f, prefs.articlePosition("p1")!!, 1e-6f)
        prefs.setArticlePosition("p1", null)
        assertNull(prefs.articlePosition("p1"))
        repeat(150) { prefs.setArticlePosition("q$it", 0.5f) }
        assertNotNull(prefs.articlePosition("q149"))
        assertTrue("bounded like reading positions",
            (0 until 150).count { prefs.articlePosition("q$it") != null } <= 100)
    }
}
