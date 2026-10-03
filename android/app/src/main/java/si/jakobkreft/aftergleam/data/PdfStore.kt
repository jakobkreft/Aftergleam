package si.jakobkreft.aftergleam.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and renders arXiv PDFs in the app.
 *
 * Uses the framework's own [PdfRenderer] rather than a PDF library. It has been in Android
 * since API 21, adds no dependency, ships no native blob, and keeps the build F-Droid clean.
 * A bundled renderer would be several megabytes and another reproducibility risk for nothing.
 *
 * Files land in the cache directory, so the system can reclaim them under pressure and the
 * user is never asked for storage permission. A paper read on a train is still there on the
 * way home; one read a month ago may not be, which is the right trade.
 */
class PdfStore(private val context: Context) {

    private val dir = File(context.cacheDir, "pdf").apply { mkdirs() }

    /**
     * The cache name for a paper, without an extension.
     *
     * The extension is not decided here because it is not known until the bytes arrive. A
     * preprint server hands over whatever the author uploaded, and the Law Archive really
     * does serve Word documents: one paper downloaded as a .docx, was saved as a .pdf
     * because that is what the app called every download, and the reader then sat on a
     * spinner forever because PdfRenderer could not open it and nothing said so.
     */
    private fun base(paperId: String) = paperId.replace('/', '_')

    /**
     * Where a download for this paper would live, whether or not it is there yet.
     *
     * Separate from [cachedFile], which answers the different question of what is actually
     * on disk. Callers that want to read a download want that one.
     */
    fun fileFor(paperId: String, extension: String = "pdf"): File =
        File(dir, base(paperId) + "." + extension)

    /** The downloaded file for a paper, whatever type it turned out to be, or null. */
    fun cachedFile(paperId: String): File? {
        val stem = base(paperId)
        return dir.listFiles()
            ?.firstOrNull { it.name.substringBeforeLast('.') == stem && it.length() > 0 }
    }

    fun isCached(paperId: String) = cachedFile(paperId) != null

    /**
     * Bytes on disk for one paper, or zero if it is not downloaded. The reader view's copy
     * counts too: to the reader both are the paper, kept for reading offline.
     */
    fun sizeOf(paperId: String): Long =
        (cachedFile(paperId)?.length() ?: 0L) + articles.sizeOf(paperId)

    /** Everything the store is holding, which is the number a reader wants to see. */
    fun totalBytes(): Long = (dir.listFiles()?.sumOf { it.length() } ?: 0L) + articles.totalBytes()

    /** The reader view's copies, which are deleted with the PDF they were opened from. */
    private val articles = ArticleStore(context)

    /**
     * Removes one downloaded paper.
     *
     * The files only, the PDF and the reader view's copy: a download is a cached copy, and a
     * reader reclaiming space has not changed their mind about the paper. Saves and
     * reactions are untouched.
     *
     * Releasing the renderer first is tidiness rather than necessity. Unlinking a file that
     * is still open is safe, and the instance doing the deleting is usually not the one
     * holding the document anyway, but leaving a renderer pointing at a file nobody can
     * find again is the sort of thing that is fine until it is not.
     */
    suspend fun delete(paperId: String): Boolean {
        withContext(Dispatchers.IO) { articles.delete(paperId) }
        val file = cachedFile(paperId) ?: return false
        if (openFile == file) release()
        return withContext(Dispatchers.IO) { file.delete() }
    }

    /** Removes every download. Returns how many files went. */
    suspend fun deleteAll(): Int {
        release()
        return withContext(Dispatchers.IO) {
            articles.deleteAll()
            dir.listFiles()?.count { it.delete() } ?: 0
        }
    }

    class DownloadError(message: String) : Exception(message)

    /**
     * Fetches the PDF if it is not already cached. Returns the local file.
     *
     * Takes the paper rather than the id: each server keeps its PDFs somewhere different,
     * and the paper is the only thing that knows which server it came from.
     */
    suspend fun download(paper: Paper): File = withContext(Dispatchers.IO) {
        cachedFile(paper.id)?.let { return@withContext it }

        val url = paper.pdfUrl
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", Http.USER_AGENT)
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) {
                throw DownloadError(
                    "${Source.label(paper.source)} returned HTTP ${conn.responseCode}"
                )
            }
            // Write to a temporary name first, so an interrupted download cannot leave a
            // truncated file that later looks cached and renders as a corrupt document.
            val partial = File(dir, base(paper.id) + ".part")
            // Refused outright when the server says it is too large, and cut off if it lies.
            // Without a ceiling one broken or hostile response could fill the phone's storage,
            // and the cache is only reclaimed by the system after the damage is done.
            if (conn.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                throw DownloadError("The file is larger than ${MAX_DOWNLOAD_BYTES / 1_000_000} MB")
            }
            conn.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) {
                            out.close(); partial.delete()
                            throw DownloadError(
                                "The file is larger than ${MAX_DOWNLOAD_BYTES / 1_000_000} MB"
                            )
                        }
                        out.write(buf, 0, n)
                    }
                }
            }

            // What arrived, rather than what was asked for. The server's own name for the
            // file is the best evidence, and the first bytes are the tiebreak: a PDF starts
            // "%PDF" and the Office formats are zip archives starting "PK".
            val named = conn.getHeaderField("Content-Disposition")
                ?.let { Regex("""filename="?([^";]+)""").find(it)?.groupValues?.get(1) }
            val ext = extensionFor(named, partial)
            val target = fileFor(paper.id, ext)
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw DownloadError("Could not save the download")
            }
            target
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The extension a downloaded file should carry.
     *
     * Trusts the sniffed bytes over the server's filename, because the extension decides
     * whether the app tries to render the file and which app it is offered to if it cannot.
     */
    internal fun extensionFor(filename: String?, file: File): String {
        if (looksLikePdf(file)) return "pdf"
        // Letters and digits only. The name comes from the server, and everything after its
        // last dot could otherwise carry a path separator into the cache: "x.pdf/../y" leaves
        // "/y" after the last dot. It cannot climb out, having no dots left, but a file name
        // is not the place to find out what a remote server chose to put in one.
        val fromName = filename?.substringAfterLast('.', "")?.lowercase()
        return if (fromName != null && EXTENSION.matches(fromName)) fromName else "bin"
    }

    /** True when the file really is a PDF, whatever it is called. */
    fun looksLikePdf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(5)
            if (input.read(head) < 5) return@use false
            String(head, Charsets.US_ASCII) == "%PDF-"
        }
    }.getOrDefault(false)

    /**
     * A copy of a download named after the paper, for sending to somebody else.
     *
     * The cache names files by paper id, which is right for the cache and wrong for a
     * recipient: a PDF shared from here arrived as "lawarchive:4vpd7_v1.pdf". That is the
     * app's bookkeeping, and the colon in it is not a legal filename character on Windows or
     * on a FAT card, so saving the attachment fails rather than merely looking odd.
     *
     * A copy rather than a rename, because the cached file has to keep the name the cache
     * can find it by. The share directory is emptied first, so it holds one file at a time
     * instead of growing a copy per share.
     */
    fun shareableCopy(file: File, title: String): File {
        val out = File(context.cacheDir, "share").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val stem = title.take(80)
            // Apostrophes are legal in a filename everywhere and dropping them turned
            // "Hong Kong's" into "Hong Kong s".
            .replace(Regex("""[^A-Za-z0-9 '.,()-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "paper" }
        val copy = File(out, "$stem.${file.extension}")
        file.copyTo(copy, overwrite = true)
        return copy
    }

    /** A guess at the media type, for handing the file to an app that can read it. */
    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "txt" -> "text/plain"
        "rtf" -> "application/rtf"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        else -> "*/*"
    }

    // One open renderer per file, reused across pages.
    //
    // Opening and closing the document for every page made scrolling visibly slow: each
    // render paid a file open, a parse and a close. PdfRenderer permits only one open *page*
    // at a time, not one open document, so the document is cached and access serialised.
    private var openFile: File? = null
    private var renderer: PdfRenderer? = null
    private val lock = Mutex()

    private fun rendererFor(file: File): PdfRenderer? {
        if (openFile == file && renderer != null) return renderer
        runCatching { renderer?.close() }
        renderer = try {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        } catch (e: Exception) {
            null
        }
        openFile = if (renderer != null) file else null
        return renderer
    }

    /** Releases the cached document. Call when the reader closes. */
    suspend fun release() = lock.withLock {
        runCatching { renderer?.close() }
        renderer = null
        openFile = null
        runCatching { searcher?.close() }
        searcher = null
        searcherFile = null
    }

    // On Android 12 to 14 search lives in a second class, PdfRendererPreV, which the system's
    // PDF module adds; the renderer that draws the pages cannot search there. It is opened on
    // the same file when first needed and held under the same lock: pdfium is one library
    // underneath both, and it is not safe to call from two threads at once. Held as
    // AutoCloseable so that nothing names the class on a phone that does not have it.
    private var searcher: AutoCloseable? = null
    private var searcherFile: File? = null

    /**
     * Where any of [variants] occurs on one page, in reading order. Empty when it does not,
     * when the page cannot be read, or when this phone cannot search ([PdfFind.supported]).
     *
     * One page per call, so that pages being drawn while a long paper is searched take their
     * turn between pages rather than waiting for the whole search.
     */
    suspend fun find(file: File, index: Int, variants: List<String>): List<PdfFind.Match> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val r = rendererFor(file) ?: return@withLock emptyList()
                if (index !in 0 until r.pageCount || variants.isEmpty()) return@withLock emptyList()
                runCatching {
                    withTextPage(file, r, index) { page ->
                        PdfFind.onPage(index, page.width, page.height, variants.flatMap(page.search))
                    }
                }.getOrNull() ?: emptyList()
            }
        }

    /**
     * Whether the document has any text to search, for telling "no matches" from a scan.
     *
     * Asked only after a search found nothing, and of the first pages only: a paper with text
     * has it on its first page, and a scanned one has none on any.
     */
    suspend fun hasText(file: File): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val r = rendererFor(file) ?: return@withLock false
            (0 until minOf(r.pageCount, 5)).any { index ->
                runCatching { withTextPage(file, r, index) { it.hasText() } }.getOrNull() == true
            }
        }
    }

    /** A page opened for its text, whichever class this phone reads text with. */
    private class TextPage(
        /** In points, as the match rectangles are. */
        val width: Float,
        val height: Float,
        /** Each match's start in the page's text, with its rectangles in points. */
        val search: (String) -> List<Pair<Int, List<RectF>>>,
        val hasText: () -> Boolean,
    )

    /** Runs [block] on page [index], or returns null on a phone that cannot read PDF text. */
    private fun <T> withTextPage(file: File, r: PdfRenderer, index: Int, block: (TextPage) -> T): T? {
        if (Build.VERSION.SDK_INT >= 35) {
            r.openPage(index).use { page ->
                return block(TextPage(
                    page.width.toFloat(), page.height.toFloat(),
                    search = { q -> page.searchText(q).map { it.textStartIndex to it.bounds } },
                    hasText = { page.textContents.any { it.text.isNotBlank() } },
                ))
            }
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        ) {
            if (searcherFile != file || searcher == null) {
                runCatching { searcher?.close() }
                searcher = PdfRendererPreV(
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                )
                searcherFile = file
            }
            (searcher as PdfRendererPreV).openPage(index).use { page ->
                return block(TextPage(
                    page.width.toFloat(), page.height.toFloat(),
                    search = { q -> page.searchText(q).map { it.textStartIndex to it.bounds } },
                    hasText = { page.textContents.any { it.text.isNotBlank() } },
                ))
            }
        }
        return null
    }

    suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) {
        lock.withLock { rendererFor(file)?.pageCount ?: 0 }
    }

    /** Aspect ratio (height / width) of a page, for sizing a placeholder before it renders. */
    suspend fun pageAspect(file: File, index: Int): Float = withContext(Dispatchers.IO) {
        lock.withLock {
            val r = rendererFor(file) ?: return@withLock 1.414f
            if (index !in 0 until r.pageCount) return@withLock 1.414f
            runCatching {
                r.openPage(index).use { it.height.toFloat() / it.width.toFloat() }
            }.getOrDefault(1.414f)
        }
    }

    /**
     * Renders one page to a bitmap [width] pixels across.
     *
     * Opened and closed per page on purpose. PdfRenderer allows only one open page at a
     * time, and holding the renderer across recompositions is the standard way to end up
     * with "Page already open" crashes on a fast scroll.
     */
    suspend fun renderPage(file: File, index: Int, width: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val r = rendererFor(file) ?: return@withLock null
                if (index !in 0 until r.pageCount) return@withLock null
                runCatching {
                    r.openPage(index).use { page ->
                        val w = width.coerceIn(200, MAX_RENDER_WIDTH)
                        val h = (w.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PdfRenderer draws ink only, so an unpainted bitmap shows whatever
                        // was in the buffer. Papers are black on white whatever the theme.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }.getOrNull()
            }
        }

    companion object {
        /** Beyond this a full page bitmap costs more memory than the detail is worth. */
        const val MAX_RENDER_WIDTH = 2600

        /**
         * The largest download accepted. Papers run to a few megabytes and the largest seen
         * here was 7.3 MB; a hundred leaves room for scanned books and figure-heavy theses.
         */
        const val MAX_DOWNLOAD_BYTES = 100_000_000L

        private val EXTENSION = Regex("[a-z0-9]{1,5}")
    }
}
