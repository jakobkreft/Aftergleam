package si.jakobkreft.aftergleam

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.ArticleStore
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.OfflineFiles
import si.jakobkreft.aftergleam.data.PdfStore
import si.jakobkreft.aftergleam.ui.Problems
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What 1.1.1 fixes, from a report of a feed that would not load, saved papers that seemed to
 * be gone, and offline papers lost to clearing the cache: the feed names what failed, and
 * downloads are kept where clearing the cache does not reach them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobustnessTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `only a network failure is called one, and it names the reader's servers`() {
        val offline = Problems.digest(UnknownHostException("arxiv.org"), listOf("arXiv"))
        assertEquals("Could not reach arXiv", offline.headline)
        assertTrue("says the app may not be allowed the network, as on GrapheneOS",
            "not allowed to use the network" in offline.detail)
        assertEquals("Could not reach arXiv, bioRxiv or medRxiv",
            Problems.digest(SocketException("Permission denied"), listOf("arXiv", "bioRxiv", "medRxiv")).headline)
        val wrapped = ArxivApi.FetchError("arXiv fetch failed after 3 attempts", SocketTimeoutException("timeout"))
        assertTrue("did not answer in time" in Problems.digest(wrapped, listOf("arXiv")).detail)

        val bug = Problems.digest(IllegalStateException("pool is empty"), listOf("arXiv"))
        assertEquals("Could not build today's digest", bug.headline)
        assertFalse("never blamed on arXiv", "arXiv" in bug.headline + bug.detail)
        assertTrue("the real reason, for a report", "IllegalStateException: pool is empty" in bug.detail)
    }

    @Test
    fun `database failures say what happened in the reader's terms`() {
        assertEquals("Your phone is out of space",
            Problems.digest(SQLiteFullException("database or disk is full"), emptyList()).headline)
        assertTrue("busy" in Problems.digest(SQLiteDatabaseLockedException("locked"), emptyList()).detail)
        val other = Problems.digest(RuntimeException("wrapped", SQLiteException("no such table: shown")), emptyList())
        assertTrue("no such table: shown" in other.detail)
        assertFalse("..." in other.detail || ".." in other.detail)
    }

    private val pdfs get() = OfflineFiles.dir(ctx, OfflineFiles.PDF)
    private val oldPdfs get() = OfflineFiles.legacyDir(ctx, OfflineFiles.PDF)
    private val oldHtml get() = OfflineFiles.legacyDir(ctx, OfflineFiles.HTML)

    @Before
    fun clean() {
        listOf(OfflineFiles.PDF, OfflineFiles.HTML).forEach {
            OfflineFiles.dir(ctx, it).deleteRecursively()
            OfflineFiles.legacyDir(ctx, it).deleteRecursively()
        }
    }

    private fun write(dir: File, name: String, bytes: Int = 2000): File =
        File(dir.apply { mkdirs() }, name).apply { writeBytes(ByteArray(bytes) { 1 }) }

    @Test
    fun `downloads are kept outside the cache, and earlier ones are found before they move`() {
        val store = PdfStore(ctx)
        assertFalse(pdfs.path.startsWith(ctx.cacheDir.path))
        write(oldPdfs, "2610.01742.pdf")
        assertEquals("a 1.1.0 download is still on the shelf before the move",
            oldPdfs, store.cachedFile("2610.01742")!!.parentFile)

        assertEquals(1, OfflineFiles.moveFromCache(ctx))
        assertEquals(pdfs, store.cachedFile("2610.01742")!!.parentFile)
        assertFalse(oldPdfs.exists())
        assertEquals("nothing left to move", 0, OfflineFiles.moveFromCache(ctx))

        // What clearing the cache does.
        ctx.cacheDir.deleteRecursively()
        assertNotNull("survives clearing the cache", store.cachedFile("2610.01742"))
    }

    @Test
    fun `an interrupted download is never offered as the paper, and is not moved`() {
        val store = PdfStore(ctx)
        write(pdfs, "2610.00001" + OfflineFiles.PART)
        write(oldPdfs, "2610.00002" + OfflineFiles.PART)
        write(pdfs, "2610.00003.pdf" + OfflineFiles.MOVING)
        assertNull(store.cachedFile("2610.00001"))
        assertNull(store.cachedFile("2610.00002"))
        assertNull("half moved", store.cachedFile("2610.00003"))
        OfflineFiles.moveFromCache(ctx)
        assertFalse(File(pdfs, "2610.00002" + OfflineFiles.PART).exists())
    }

    @Test
    fun `a paper already fetched again keeps the new copy, and deleting takes every copy`() {
        val store = PdfStore(ctx)
        write(pdfs, "2610.00004.pdf", bytes = 3000)
        write(oldPdfs, "2610.00004.pdf", bytes = 1000)
        write(oldPdfs, "2610.00005.pdf")
        assertEquals(3000L, store.cachedFile("2610.00004")!!.length())
        runBlocking { assertTrue(store.delete("2610.00005")) }
        assertNull(store.cachedFile("2610.00005"))
        OfflineFiles.moveFromCache(ctx)
        assertEquals(3000L, store.cachedFile("2610.00004")!!.length())
        assertEquals(3000L, store.totalBytes())
    }

    @Test
    fun `the reader view's copies move with their figures`() {
        val articles = ArticleStore(ctx)
        val paper = File(oldHtml, "2610.01742")
        File(paper, "article.html").apply { parentFile!!.mkdirs(); writeText("<article class=\"ltx_document\"></article>") }
        File(paper, "base.txt").writeText("https://arxiv.org/html/2610.01742")
        write(File(paper, "res"), "abc.png")
        assertTrue("readable before the move", articles.isCached("2610.01742"))
        val before = articles.sizeOf("2610.01742")

        OfflineFiles.moveFromCache(ctx)
        assertFalse(paper.exists())
        assertTrue(articles.isCached("2610.01742"))
        assertEquals(before, articles.sizeOf("2610.01742"))
        assertEquals("https://arxiv.org/html/2610.01742", articles.cached("2610.01742")!!.baseUrl)
        assertTrue(articles.delete("2610.01742"))
        assertFalse(articles.isCached("2610.01742"))
    }
}
