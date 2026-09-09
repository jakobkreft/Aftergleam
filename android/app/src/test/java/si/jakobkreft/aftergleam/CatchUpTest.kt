package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.ShownItem
import si.jakobkreft.aftergleam.data.Signal

/**
 * What "you were away" is allowed to mean.
 *
 * The obvious reading, days missing from the `shown` table, is wrong: the daily worker
 * fetches papers without composing a digest, so the days somebody was away leave no rows at
 * all. What they missed is the papers announced since the last digest they were actually
 * shown, and that is what these pin down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatchUpTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun paper(id: String, published: String) = Paper(
        id = id, title = "Title $id", abstract = "Abstract $id",
        authors = listOf("A"), categories = listOf("cs.CV"),
        published = published, updated = published,
    )

    private fun fresh(): Db = Db(ctx).also {
        it.clearFeedback()
        it.writableDatabase.delete("shown", null, null)
        it.writableDatabase.delete("papers", null, null)
    }

    @Test
    fun `papers announced since the last digest are what was missed`() {
        val db = fresh()
        db.upsertPapers(listOf(
            paper("old", "2026-09-01"),
            paper("m1", "2026-09-05"),
            paper("m2", "2026-09-06"),
        ))
        db.markShown(listOf(ShownItem("old", "RELEVANCE", "", 0.5f)), "2026-09-03")

        assertEquals("2026-09-03", db.lastDigestDayBefore("2026-09-09"))
        assertEquals(2, db.unseenCountSince("2026-09-03"))
        assertEquals(setOf("m1", "m2"), db.unseenSince("2026-09-03").map { it.id }.toSet())
    }

    @Test
    fun `a paper a digest did show is not missed`() {
        val db = fresh()
        db.upsertPapers(listOf(paper("a", "2026-09-05"), paper("b", "2026-09-05")))
        db.markShown(listOf(ShownItem("a", "RELEVANCE", "", 0.5f)), "2026-09-06")
        // "a" was announced after the 2026-09-03 line but the reader was offered it.
        assertEquals(listOf("b"), db.unseenSince("2026-09-03").map { it.id })
    }

    @Test
    fun `a paper the reader has touched is not missed either`() {
        val db = fresh()
        db.upsertPapers(listOf(paper("c", "2026-09-05"), paper("d", "2026-09-05")))
        db.addSignal("c", Signal.SAVED)
        assertEquals(listOf("d"), db.unseenSince("2026-09-03").map { it.id })
    }

    @Test
    fun `no earlier digest means nothing to catch up on`() {
        val db = fresh()
        db.upsertPapers(listOf(paper("x", "2026-09-05")))
        db.markShown(listOf(ShownItem("x", "RELEVANCE", "", 0.5f)), "2026-09-09")
        // Only today. A first-time reader has not been away, they have just arrived.
        assertNull(db.lastDigestDayBefore("2026-09-09"))
    }

    @Test
    fun `past days list what was shown and what came of it`() {
        val db = fresh()
        db.upsertPapers(listOf(
            paper("p1", "2026-09-01"), paper("p2", "2026-09-01"), paper("p3", "2026-09-02"),
        ))
        db.markShown(
            listOf(
                ShownItem("p1", "RELEVANCE", "", 0.5f),
                ShownItem("p2", "RELEVANCE", "", 0.5f),
            ),
            "2026-09-02",
        )
        db.markShown(listOf(ShownItem("p3", "RELEVANCE", "", 0.5f)), "2026-09-04")
        db.addSignal("p1", Signal.LIKED)
        // Merely opening one is not reacting to it.
        db.addSignal("p2", Signal.OPENED)

        val days = db.digestDays()
        assertEquals(listOf("2026-09-04", "2026-09-02"), days.map { it.day })
        val second = days.first { it.day == "2026-09-02" }
        assertEquals(2, second.papers)
        assertEquals(1, second.reacted)
    }

    @Test
    fun `a replayed day keeps its order and its reasons`() {
        // The record has to be the record. Re-ranking it with today's model would answer a
        // question nobody asked.
        val db = fresh()
        db.upsertPapers(listOf(paper("r1", "2026-09-01"), paper("r2", "2026-09-01")))
        db.markShown(
            listOf(
                ShownItem("r1", "RELEVANCE", "matches diffusion", 0.7f),
                ShownItem("r2", "BRIDGE", "cs.RO, outside your usual", 0.3f),
            ),
            "2026-09-02",
        )
        val back = db.digestFor("2026-09-02")
        assertEquals(listOf("r1", "r2"), back.map { it.paperId })
        assertEquals("matches diffusion", back[0].reason)
        assertEquals("BRIDGE", back[1].slot)
    }

    @Test
    fun `the catch-up pool is bounded`() {
        val db = fresh()
        db.upsertPapers((1..50).map { paper("b$it", "2026-09-05") })
        assertTrue(db.unseenSince("2026-09-03", limit = 10).size == 10)
        assertEquals(50, db.unseenCountSince("2026-09-03"))
    }
}
