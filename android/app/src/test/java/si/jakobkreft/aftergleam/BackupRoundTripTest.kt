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
import si.jakobkreft.aftergleam.data.Signal

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
    fun `judgements saves and settings survive a round trip`() {
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        db.clearFeedback()
        db.upsertPapers(listOf(paper("1"), paper("2"), paper("3")))
        db.addSignal("1", Signal.LIKED)
        db.addSignal("1", Signal.READ_PAGES)
        db.addSignal("2", Signal.DISLIKED)
        db.setReaction("2", Reaction(saved = true))
        db.setReaction("3", Reaction(saved = true))
        prefs.categories = setOf("cs.CV", "cs.LG")
        prefs.digestSize = 32
        prefs.diversity = 0.45f

        val json = Backup.export(db, prefs)

        db.clearFeedback()
        prefs.categories = emptySet()
        prefs.digestSize = 10
        assertTrue("state really was cleared", db.evidence().isEmpty())
        assertTrue("state really was cleared", db.allReactions().isEmpty())

        val restored = Backup.restore(json, db, prefs)
        assertEquals(3, restored.reactions)

        // The judgements are what the model trains on, so they are what must come back.
        val ev = db.evidence()
        assertEquals(setOf(Signal.LIKED, Signal.READ_PAGES), ev["1"]!!.signals)
        assertTrue("a dislike must survive", Signal.DISLIKED in ev["2"]!!.signals)

        val back = db.allReactions()
        assertTrue("saved flag must survive", back["2"]!!.saved)
        // A save with no judgement is a real state and must not become one.
        assertTrue(back["3"]!!.saved)
        assertEquals(null, ev["3"]?.let { if (Signal.LIKED in it.signals) true else null })

        assertEquals(setOf("cs.CV", "cs.LG"), prefs.categories)
        assertEquals(32, prefs.digestSize)
        assertEquals(0.45f, prefs.diversity, 1e-4f)
    }

    @Test
    fun `a version 1 file still restores`() {
        // Old backups carried a single interest number. Only its sign ever meant anything,
        // and refusing to read them would strand whoever exported before the ledger landed.
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        db.clearFeedback()
        val v1 = """{"version": 1, "reactions": [
            {"id": "a", "interest": 0.9},
            {"id": "b", "interest": 0.1},
            {"id": "c", "saved": true}
        ]}"""
        Backup.restore(v1, db, prefs)
        val ev = db.evidence()
        assertTrue("a high rating becomes a like", Signal.LIKED in ev["a"]!!.signals)
        assertTrue("a low one becomes a dislike", Signal.DISLIKED in ev["b"]!!.signals)
        assertTrue("a bare save stays a save", db.allReactions()["c"]!!.saved)
    }

    @Test
    fun `writing then reading does not close the database`() {
        // SQLiteOpenHelper returns one shared database. Closing it after a write left the
        // helper serving a closed connection pool, and the next read crashed with
        // "connection pool has been closed". It looked like a bug in one screen; it was in
        // every path that wrote and then read.
        val db = Db(ctx)
        db.clearFeedback()
        db.upsertPapers(listOf(paper("w1")))
        repeat(5) { i ->
            // An all-default reaction is deleted by design, so keep one flag set.
            db.setReaction("w1", Reaction(saved = true, viewed = i % 2 == 0))
            db.addSignal("w1", Signal.OPENED)
            val back = db.allReactions()
            assertTrue("read after write number $i must succeed", back.containsKey("w1"))
            assertTrue("and the ledger reads back too", db.evidence().containsKey("w1"))
        }
        db.markShown(listOf(si.jakobkreft.aftergleam.data.ShownItem("w1", "RELEVANCE", "why", 0.5f)), "2026-09-09")
        assertTrue("reads after markShown must work too", db.digestFor("2026-09-09").isNotEmpty())
        db.clearFeedback()
        assertTrue("and after clearFeedback", db.allReactions().isEmpty())
        assertTrue("which must clear the ledger too", db.evidence().isEmpty())
    }

    @Test
    fun `viewing a paper is remembered but not exported`() {
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        db.clearFeedback()
        db.upsertPapers(listOf(paper("v1"), paper("v2")))
        db.setReaction("v1", Reaction(viewed = true))
        db.setReaction("v2", Reaction(viewed = true))
        db.addSignal("v2", Signal.LIKED)

        // Opening a paper must survive a restart, so it can be marked in the digest.
        assertTrue("viewed must persist", db.allReactions()["v1"]!!.viewed)

        val json = Backup.export(db, prefs)
        assertTrue("a view-only row carries no judgement and should not travel",
            !json.contains("\"v1\""))
        assertTrue("a judged paper still travels", json.contains("\"v2\""))
    }

    @Test
    fun `restore merges rather than replacing`() {
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        db.clearFeedback()
        db.addSignal("old", Signal.LIKED)
        val json = Backup.export(db, prefs)

        db.addSignal("new", Signal.DISLIKED)
        Backup.restore(json, db, prefs)

        val back = db.evidence()
        assertTrue("restoring must not discard judgements made since the export",
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
