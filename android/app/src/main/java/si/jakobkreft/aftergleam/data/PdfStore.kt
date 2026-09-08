package si.jakobkreft.aftergleam.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
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

    fun cachedFile(arxivId: String): File = File(dir, arxivId.replace('/', '_') + ".pdf")

    fun isCached(arxivId: String) = cachedFile(arxivId).let { it.exists() && it.length() > 0 }

    class DownloadError(message: String) : Exception(message)

    /** Fetches the PDF if it is not already cached. Returns the local file. */
    suspend fun download(arxivId: String): File = withContext(Dispatchers.IO) {
        val target = cachedFile(arxivId)
        if (target.exists() && target.length() > 0) return@withContext target

        val url = "https://arxiv.org/pdf/$arxivId"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Aftergleam/0.1 (+https://github.com/jakobkreft/aftergleam)")
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) {
                throw DownloadError("arXiv returned HTTP ${conn.responseCode}")
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

    fun pageCount(file: File): Int = openRenderer(file)?.use { it.pageCount } ?: 0

    /**
     * Renders one page to a bitmap [width] pixels across.
     *
     * Opened and closed per page on purpose. PdfRenderer allows only one open page at a
     * time, and holding the renderer across recompositions is the standard way to end up
     * with "Page already open" crashes on a fast scroll.
     */
    suspend fun renderPage(file: File, index: Int, width: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            openRenderer(file)?.use { renderer ->
                if (index !in 0 until renderer.pageCount) return@use null
                renderer.openPage(index).use { page ->
                    val height = (width.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer draws only ink, so an unpainted bitmap shows whatever was
                    // in the buffer. Papers are black on white regardless of app theme.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    private fun openRenderer(file: File): PdfRenderer? = try {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    } catch (e: Exception) {
        null
    }
}
