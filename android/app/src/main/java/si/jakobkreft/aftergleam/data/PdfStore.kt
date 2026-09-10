package si.jakobkreft.aftergleam.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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

    fun cachedFile(paperId: String): File = File(dir, paperId.replace('/', '_') + ".pdf")

    fun isCached(paperId: String) = cachedFile(paperId).let { it.exists() && it.length() > 0 }

    /** Bytes on disk for one paper, or zero if it is not downloaded. */
    fun sizeOf(paperId: String): Long = cachedFile(paperId).let { if (it.exists()) it.length() else 0L }

    /** Everything the store is holding, which is the number a reader wants to see. */
    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Removes one downloaded paper.
     *
     * The file only: a download is a cached copy, and a reader reclaiming space has not
     * changed their mind about the paper. Saves and reactions are untouched.
     *
     * Releasing the renderer first is tidiness rather than necessity. Unlinking a file that
     * is still open is safe, and the instance doing the deleting is usually not the one
     * holding the document anyway, but leaving a renderer pointing at a file nobody can
     * find again is the sort of thing that is fine until it is not.
     */
    suspend fun delete(paperId: String): Boolean {
        val file = cachedFile(paperId)
        if (openFile == file) release()
        return withContext(Dispatchers.IO) { file.delete() }
    }

    /** Removes every download. Returns how many files went. */
    suspend fun deleteAll(): Int {
        release()
        return withContext(Dispatchers.IO) {
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
        val target = cachedFile(paper.id)
        if (target.exists() && target.length() > 0) return@withContext target

        val url = paper.pdfUrl
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Aftergleam/0.1 (+https://github.com/jakobkreft/aftergleam)")
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
            val partial = File(dir, target.name + ".part")
            conn.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(target)) {
                partial.delete()
                throw DownloadError("Could not save the download")
            }
            target
        } finally {
            conn.disconnect()
        }
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
    }
}
