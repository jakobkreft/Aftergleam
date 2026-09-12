package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.PdfStore
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.Signal

/**
 * Reclaiming space must not look like changing your mind.
 *
 * A download is a cached copy of a paper; a save is a statement about it. They live in
 * different places for that reason, and the delete has to respect it: somebody clearing
 * space before a flight should not come back to find their library emptied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun store() = PdfStore(ctx).also { runBlocking { it.deleteAll() } }

    private fun fakeDownload(store: PdfStore, id: String, bytes: Int) {
        store.fileFor(id).writeBytes(ByteArray(bytes))
    }

    @Test
    fun `sizes are reported per paper and in total`() {
        val s = store()
        fakeDownload(s, "a", 1000)
        fakeDownload(s, "b", 2500)
        assertEquals(1000L, s.sizeOf("a"))
        assertEquals(3500L, s.totalBytes())
        // A paper that was never downloaded weighs nothing rather than throwing.
        assertEquals(0L, s.sizeOf("never"))
    }

    @Test
    fun `deleting a download leaves the paper's saves and reactions alone`() {
        val db = Db(ctx)
        db.clearFeedback()
        db.setReaction("a", Reaction(saved = true))
        db.addSignal("a", Signal.SAVED)
        db.addSignal("a", Signal.LIKED)

        val s = store()
        fakeDownload(s, "a", 1000)
        assertTrue(s.isCached("a"))

        runBlocking { s.delete("a") }

        assertFalse("the file goes", s.isCached("a"))
        assertTrue("the save stays", db.allReactions()["a"]!!.saved)
        assertEquals(
            "the ledger stays",
            setOf(Signal.SAVED, Signal.LIKED),
            db.evidence()["a"]!!.signals,
        )
    }

    @Test
    fun `deleting every download leaves the library intact`() {
        val db = Db(ctx)
        db.clearFeedback()
        db.setReaction("a", Reaction(saved = true))
        db.addSignal("b", Signal.LIKED)

        val s = store()
        fakeDownload(s, "a", 1000)
        fakeDownload(s, "b", 1000)
        fakeDownload(s, "c", 1000)

        assertEquals(3, runBlocking { s.deleteAll() })
        assertEquals(0L, s.totalBytes())
        assertTrue(db.allReactions()["a"]!!.saved)
        assertTrue(Signal.LIKED in db.evidence()["b"]!!.signals)
    }

    @Test
    fun `deleting something that is not there is not an error`() {
        val s = store()
        assertFalse(runBlocking { s.delete("never-downloaded") })
        assertEquals(0, runBlocking { s.deleteAll() })
    }

    @Test
    fun `an id with a slash still round trips`() {
        // bioRxiv identifiers are DOIs, which contain a slash that cannot go in a filename.
        val s = store()
        val doi = "10.64898/2026.09.04.749381v1"
        fakeDownload(s, doi, 4096)
        assertTrue(s.isCached(doi))
        assertEquals(4096L, s.sizeOf(doi))
        runBlocking { s.delete(doi) }
        assertFalse(s.isCached(doi))
    }

    @Test
    fun `a download is recognised by its bytes, not its name`() {
        val s = store()
        val real = s.fileFor("pdf-paper")
        real.writeBytes("%PDF-1.7\nbody".toByteArray())
        assertTrue("a real PDF must be renderable", s.looksLikePdf(real))

        // The Law Archive case: a .docx that the app used to save under a .pdf name and
        // then hand to PdfRenderer, which opened nothing and reported nothing.
        val word = s.fileFor("word-paper", "pdf")
        word.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        assertTrue("a zip container is not a PDF whatever it is called", !s.looksLikePdf(word))
    }

    @Test
    fun `a cached download is found whatever extension it has`() {
        val s = store()
        s.fileFor("osf-paper", "docx").writeBytes(ByteArray(120))
        assertTrue("a downloaded paper counts as downloaded", s.isCached("osf-paper"))
        assertEquals(120L, s.sizeOf("osf-paper"))
        assertEquals("docx", s.cachedFile("osf-paper")?.extension)
    }

    @Test
    fun `deleting works for a download that is not a PDF`() {
        val s = store()
        s.fileFor("osf-paper", "docx").writeBytes(ByteArray(120))
        runBlocking { s.delete("osf-paper") }
        assertTrue("the file should be gone", !s.isCached("osf-paper"))
    }

    @Test
    fun `each format is offered to apps under its own media type`() {
        val s = store()
        assertEquals("application/pdf", s.mimeOf(s.fileFor("a", "pdf")))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            s.mimeOf(s.fileFor("b", "docx")),
        )
        // Unknown formats still open, with the chooser deciding.
        assertEquals("*/*", s.mimeOf(s.fileFor("c", "qqq")))
    }
}
