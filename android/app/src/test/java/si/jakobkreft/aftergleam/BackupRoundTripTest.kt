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
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.Reaction

/**
 * Backup touches org.json and SQLite, both of which are stubbed in plain android.jar and
 * would return nulls rather than fail loudly. Robolectric gives real implementations.
 *
 * A round trip is the test that matters: an export that silently drops a field looks
 * perfectly healthy until someone restores it onto a new phone and finds a year of
 * judgements missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun paper(id: String) = Paper(
        id = id, title = "Title $id", abstract = "Abstract $id",
        authors = listOf("A"), categories = listOf("cs.CV"),
        published = "2026-09-01", updated = "2026-09-01",
    )

    @Test
    fun `ratings saves and settings survive a round trip`() {
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        db.upsertPapers(listOf(paper("1"), paper("2"), paper("3")))
        db.setReaction("1", Reaction(interest = 0.9f))
        db.setReaction("2", Reaction(interest = 0.15f, saved = true))
        db.setReaction("3", Reaction(saved = true))
        prefs.categories = setOf("cs.CV", "cs.LG")
        prefs.digestSize = 32
        prefs.diversity = 0.45f

        val json = Backup.export(db, prefs)

        db.clearReactions()
        prefs.categories = emptySet()
        prefs.digestSize = 10
        assertTrue("state really was cleared", db.allReactions().isEmpty())

        val restored = Backup.restore(json, db, prefs)
        assertEquals(3, restored.reactions)

        val back = db.allReactions()
        assertEquals(0.9f, back["1"]!!.interest!!, 1e-4f)
        assertEquals(0.15f, back["2"]!!.interest!!, 1e-4f)
        assertTrue("saved flag must survive", back["2"]!!.saved)
        // A save with no rating is a real state and must not be turned into a rating.
        assertEquals(null, back["3"]!!.interest)
        assertTrue(back["3"]!!.saved)

        assertEquals(setOf("cs.CV", "cs.LG"), prefs.categories)
        assertEquals(32, prefs.digestSize)
        assertEquals(0.45f, prefs.diversity, 1e-4f)
    }

    @Test
    fun `viewing a paper is remembered but not exported`() {
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        db.clearReactions()
        db.upsertPapers(listOf(paper("v1"), paper("v2")))
        db.setReaction("v1", Reaction(viewed = true))
        db.setReaction("v2", Reaction(interest = 0.9f, viewed = true))

        // Opening a paper must survive a restart, so it can be marked in the digest.
        assertTrue("viewed must persist", db.allReactions()["v1"]!!.viewed)

        val json = Backup.export(db, prefs)
        assertTrue("a view-only row carries no judgement and should not travel",
            !json.contains("\"v1\""))
        assertTrue("a rated paper still travels", json.contains("\"v2\""))
    }

    @Test
    fun `restore merges rather than replacing`() {
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        db.clearReactions()
        db.setReaction("old", Reaction(interest = 0.8f))
        val json = Backup.export(db, prefs)

        db.setReaction("new", Reaction(interest = 0.2f))
        Backup.restore(json, db, prefs)

        val back = db.allReactions()
        assertTrue("restoring must not discard ratings made since the export",
            back.containsKey("new"))
        assertTrue(back.containsKey("old"))
    }

    @Test
    fun `a backup from a newer version is refused rather than half applied`() {
        val db = Db(ctx)
        val future = """{"version": 99, "reactions": []}"""
        var threw = false
        try {
            Backup.restore(future, db, Prefs(ctx))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a future backup format must be refused", threw)
    }
}
