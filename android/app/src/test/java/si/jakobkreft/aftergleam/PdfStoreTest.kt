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
        store.cachedFile(id).writeBytes(ByteArray(bytes))
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
}
