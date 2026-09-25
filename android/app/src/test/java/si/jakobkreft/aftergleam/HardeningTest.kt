package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Backup
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.PdfStore
import si.jakobkreft.aftergleam.data.Prefs

/**
 * Inputs the app does not control: long histories, backups from elsewhere, server filenames.
 *
 * None of these can be exploited from outside the phone, and none of them was being checked.
 * Each case here was a crash, a wrong number, or a string from somewhere else ending up in a
 * request or a path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardeningTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun paper(id: String) = Paper(
        id, "Title $id", "Abstract $id", listOf("A"), listOf("cs.LG"), "2026-09-01", "2026-09-01",
    )

    @Test
    fun `looking up more papers than one statement may bind returns every one of them`() {
        // Android 8 to 11 cap a statement at 999 bound parameters. Robolectric's SQLite does
        // not, so this cannot show the crash; it shows the batching loses and repeats nothing.
        val db = Db(ctx)
        val ids = (1..2_500).map { "p$it" }
        db.upsertPapers(ids.map { paper(it) })
        val found = db.papersById(ids + ids.take(50))
        assertEquals(2_500, found.size)
        assertEquals(ids.toSet(), found.map { it.id }.toSet())
    }

    @Test
    fun `a restored backup cannot carry categories the app does not offer`() {
        val db = Db(ctx); val prefs = Prefs(ctx)
        prefs.categories = setOf("cs.CV")
        Backup.restore(
            """{"version":2,"categories":["cs.LG","cs.LG&max_results=100000","nonsense"],
               "reactions":[]}""",
            db, prefs,
        )
        assertEquals(setOf("cs.LG"), prefs.categories)
    }

    @Test
    fun `restored settings are held to the ranges the settings screen allows`() {
        val db = Db(ctx); val prefs = Prefs(ctx)
        Backup.restore(
            """{"version":2,"digestSize":-4,"qualityWeight":7.5,
               "explorationRate":-1,"diversity":0.5,"reactions":[]}""",
            db, prefs,
        )
        assertEquals(5, prefs.digestSize)
        assertEquals(1f, prefs.qualityWeight)
        assertEquals(0f, prefs.explorationRate)
        assertEquals(0.5f, prefs.diversity)
    }

    @Test
    fun `a value that is not a number leaves the setting alone`() {
        val db = Db(ctx); val prefs = Prefs(ctx)
        prefs.diversity = 0.3f
        Backup.restore("""{"version":2,"diversity":"lots","reactions":[]}""", db, prefs)
        assertEquals(0.3f, prefs.diversity)
    }

    @Test
    fun `an absurd paper id in a backup is skipped rather than stored`() {
        val db = Db(ctx); val prefs = Prefs(ctx)
        db.clearFeedback()
        val long = "x".repeat(5_000)
        val r = Backup.restore(
            """{"version":2,"reactions":[{"id":"$long","signals":["LIKED"]},
               {"id":"2609.00001","signals":["LIKED"]}]}""",
            db, prefs,
        )
        assertEquals(1, r.reactions)
        assertTrue(db.evidence().keys.none { it.length > 200 })
    }

    @Test
    fun `a server cannot put a path into a download's name`() {
        val store = PdfStore(ctx)
        val f = store.fileFor("probe", "part").apply { writeBytes("PK\u0003\u0004".toByteArray()) }
        assertEquals("docx", store.extensionFor("thesis.docx", f))
        assertEquals("bin", store.extensionFor("x.pdf/../y", f))
        assertEquals("bin", store.extensionFor("x.p df", f))
        assertEquals("bin", store.extensionFor("noextension", f))
        assertEquals("bin", store.extensionFor(null, f))
        f.delete()
    }
}
