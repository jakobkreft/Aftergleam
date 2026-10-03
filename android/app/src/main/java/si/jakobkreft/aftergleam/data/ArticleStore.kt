package si.jakobkreft.aftergleam.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * arXiv's HTML version of a paper, for the reader view.
 *
 * Why HTML rather than the PDF's own text. The reader view is text that wraps to the screen at
 * any size, and a PDF does not hold text in that form: the platform hands back each page as one
 * string with the lines already broken, no paragraphs or headings, the labels inside figures
 * read out as words, captions in the wrong place and no figures at all. Measured on arXiv
 * papers that was unreadable often enough that a reader could not tell when to trust it.
 * arXiv converts the LaTeX source of nearly every paper it receives into HTML, with real
 * paragraphs, headings, figures, tables and MathML equations. 30 of 30 new mathematics papers
 * had one; papers submitted as a PDF alone have none, and arXiv answers 404 for them.
 *
 * So the reader view is offered only for arXiv papers, and only once arXiv has said it has
 * the HTML. Everything else keeps the PDF, which is always the paper as its authors made it.
 *
 * Only the paper is kept: the `<article>` that LaTeXML generates, without arXiv's site around
 * it, its scripts or its stylesheets. The page is shown with JavaScript off, a stylesheet
 * shipped with the app, and the figures fetched from arXiv once and then read from the phone,
 * so a paper opened in the reader view once can be read on a plane.
 */
class ArticleStore(context: Context) {

    private val root = File(context.cacheDir, "html")

    /** What the reader view can do for a paper. */
    enum class Availability {
        /** Asking arXiv. */
        CHECKING,
        /** On the phone already. */
        READY,
        /** arXiv has one, not fetched yet. */
        AVAILABLE,
        /** arXiv has none: the paper was submitted as a PDF, or its conversion failed. */
        NONE,
        /** Not an arXiv paper. bioRxiv and the rest publish no HTML the app can rely on. */
        NOT_ARXIV,
        /** arXiv did not answer. Worth trying again. */
        UNREACHABLE,
    }

    /** The paper's body, and the address its relative links and figures resolve against. */
    class Article(val body: String, val baseUrl: String) {
        /**
         * Whether LaTeXML marked something it could not convert: usually a macro, now and
         * then a whole passage swallowed into an equation. Older conversions mark it
         * `ltx_ERROR`, current ones a bare `undefined` class on the leftover source.
         */
        val hasErrors: Boolean get() = "ltx_ERROR" in body || "class=\"undefined\"" in body
    }

    /** arXiv answered that it has no HTML version of this paper. */
    class NoHtml : Exception("arXiv has no HTML version of this paper")

    private fun dir(paperId: String) = File(root, paperId.replace('/', '_'))

    fun isCached(paperId: String): Boolean = File(dir(paperId), ARTICLE).length() > 0

    /**
     * Whether the reader view can be offered, without fetching the paper.
     *
     * A HEAD request: arXiv answers 200 or 404 for it without sending the page, so asking
     * costs nothing worth counting for a paper the reader never views this way.
     */
    suspend fun availability(paper: Paper): Availability = withContext(Dispatchers.IO) {
        if (paper.source != Source.ARXIV) return@withContext Availability.NOT_ARXIV
        if (isCached(paper.id)) return@withContext Availability.READY
        try {
            val conn = open(pageUrl(paper.id)).apply { requestMethod = "HEAD" }
            try {
                when (conn.responseCode) {
                    200 -> Availability.AVAILABLE
                    404, 410 -> Availability.NONE
                    else -> Availability.UNREACHABLE
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Availability.UNREACHABLE
        }
    }

    /** The article as kept on the phone, or null if it is not. */
    fun cached(paperId: String): Article? {
        val d = dir(paperId)
        val body = runCatching { File(d, ARTICLE).readText() }.getOrNull()?.ifBlank { null }
            ?: return null
        val base = runCatching { File(d, BASE).readText().trim() }.getOrNull()
            ?.takeIf { it.startsWith("https://arxiv.org/html/") }
            ?: pageUrl(paperId)
        return Article(body, base)
    }

    /**
     * The article, from the phone or else from arXiv.
     *
     * Throws [NoHtml] when arXiv has none, and an ordinary exception when it could not be
     * reached or sent something that is not a paper.
     */
    suspend fun load(paper: Paper): Article = withContext(Dispatchers.IO) {
        cached(paper.id)?.let { return@withContext it }
        if (paper.source != Source.ARXIV) throw NoHtml()
        val conn = open(pageUrl(paper.id))
        val (page, finalUrl) = try {
            when (conn.responseCode) {
                200 -> {}
                404, 410 -> throw NoHtml()
                else -> throw PdfStore.DownloadError("arXiv returned HTTP ${conn.responseCode}")
            }
            val bytes = conn.inputStream.use { readCapped(it, MAX_PAGE_BYTES) }
                ?: throw PdfStore.DownloadError("arXiv's HTML version is too large to keep")
            String(bytes, Charsets.UTF_8) to conn.url.toString()
        } finally {
            conn.disconnect()
        }
        val body = extract(page) ?: throw PdfStore.DownloadError(
            "arXiv's HTML version of this paper could not be read"
        )
        val d = dir(paper.id).apply { mkdirs() }
        writeAtomically(File(d, ARTICLE), body.toByteArray())
        val base = finalUrl.takeIf { it.startsWith("https://arxiv.org/html/") } ?: pageUrl(paper.id)
        writeAtomically(File(d, BASE), base.toByteArray())
        Article(body, base)
    }

    /**
     * A file the article asks for: a figure, usually. Read from the phone, and fetched from
     * arXiv the first time and kept. Null for anything outside the paper's own folder on
     * arXiv, which is never fetched: the reader view talks to nobody else.
     *
     * Called by the web view on its own background threads, so it blocks.
     */
    fun resource(paperId: String, url: String): File? {
        if (!allowed(paperId, url)) return null
        val res = File(dir(paperId), RESOURCES).apply { mkdirs() }
        val file = File(res, localName(url))
        if (file.length() > 0) return file
        return runCatching {
            val conn = open(url)
            try {
                if (conn.responseCode != 200) return@runCatching null
                val bytes = conn.inputStream.use { readCapped(it, MAX_RESOURCE_BYTES) }
                    ?: return@runCatching null
                writeAtomically(file, bytes)
                file
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Removes one paper's article and figures. */
    fun delete(paperId: String): Boolean = dir(paperId).deleteRecursively()

    /** Removes every article. */
    fun deleteAll(): Int = root.listFiles()?.count { it.deleteRecursively() } ?: 0

    /** Bytes kept for one paper. */
    fun sizeOf(paperId: String): Long = dir(paperId).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun totalBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", Http.USER_AGENT)
        instanceFollowRedirects = true
        connectTimeout = 20_000
        readTimeout = 40_000
    }

    /** The bytes, or null past [cap]: one broken response should not fill the phone. */
    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Written under another name and renamed, so a half-written file is never read. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val part = File(target.parentFile, target.name + "." + System.nanoTime() + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) {
            part.delete()
            throw PdfStore.DownloadError("Could not save the HTML version")
        }
    }

    companion object {
        private const val ARTICLE = "article.html"
        private const val BASE = "base.txt"
        private const val RESOURCES = "res"

        /** The largest seen was 3.5 MB, a paper long on equations. */
        private const val MAX_PAGE_BYTES = 30_000_000L
        private const val MAX_RESOURCE_BYTES = 25_000_000L

        fun pageUrl(paperId: String) = "https://arxiv.org/html/$paperId"

        /**
         * The paper inside one of arXiv's HTML pages, or null when there is none.
         *
         * LaTeXML puts the whole paper, title to bibliography, in one `<article>`, and arXiv
         * wraps it in its own site: a header, a menu, a report-an-issue form, a footer with
         * its funders, and scripts for all of them. Only the article is kept. Its scripts and
         * stylesheet links go too: the view runs no script, and is styled by the app.
         *
         * Scanned with plain string searches, see [Html], not regular expressions: a page of
         * equations runs to megabytes, and on a phone a lazy pattern over that much text takes
         * not milliseconds but tens of seconds.
         */
        fun extract(page: String): String? {
            var start = Html.findTag(page, "article", 0)
            while (start >= 0) {
                val tagEnd = page.indexOf('>', start)
                if (tagEnd < 0) return null
                if ("ltx_document" in page.substring(start, tagEnd)) break
                start = Html.findTag(page, "article", tagEnd)
            }
            // Its scripts and stylesheet links are removed below in any case; this tag is the
            // one LaTeXML writes, always in lower case.
            if (start < 0) return null
            val end = page.lastIndexOf("</article>")
            if (end < start) return null
            var body = page.substring(start, end + "</article>".length)
            body = Html.removeElements(body, "script")
            body = Html.removeElements(body, "iframe")
            for (tag in listOf("link", "meta", "base")) body = Html.removeTags(body, tag)
            return body
        }

        /**
         * Whether the article may load [url]: only files under the paper's own folder on
         * arXiv, `https://arxiv.org/html/2610.02210v1/...`, which is where its figures are.
         */
        fun allowed(paperId: String, url: String): Boolean {
            val prefix = "https://arxiv.org/html/$paperId"
            if (!url.startsWith(prefix)) return false
            val rest = url.removePrefix(prefix)
            return RESOURCE_PATH.matches(rest) && "/../" !in rest && !rest.endsWith("/..")
        }

        /**
         * The name a fetched file is kept under: a hash of its address, with its extension.
         *
         * Not the path itself, which comes from the paper's HTML and so from whatever the
         * conversion produced: a hash cannot name anything outside the folder.
         */
        fun localName(url: String): String {
            val path = url.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
                .takeIf { EXTENSION.matches(it) }
            val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
            return if (ext != null) "$hash.$ext" else hash
        }

        /** The media type a kept file is served as. */
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "css" -> "text/css"
            else -> "application/octet-stream"
        }

        /** A version, then a path below it: "v1/figs/x1.png". */
        private val RESOURCE_PATH = Regex("""(v\d+)?/[A-Za-z0-9._~%/+-]+""")
        private val EXTENSION = Regex("[a-z0-9]{1,5}")
    }
}
