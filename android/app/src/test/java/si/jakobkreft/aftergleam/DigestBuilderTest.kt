package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.rank.DigestBuilder

/**
 * The digest the nightly worker builds has to be the digest the app would have built.
 *
 * The worker used to fetch the papers and stop, leaving the ranking to be paid for on the
 * first open of the day. Both now call this, so there is one implementation rather than two
 * that drift a weight at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DigestBuilderTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun paper(i: Int) = Paper(
        id = "p$i",
        title = "Paper $i on diffusion models",
        abstract = "We study generative diffusion models for image synthesis, sample $i.",
        authors = listOf("A$i"), categories = listOf("cs.CV"),
        published = "2026-09-0${i % 9 + 1}", updated = "2026-09-01",
    )

    private fun fresh(): Pair<Db, Prefs> {
        val db = Db(ctx)
        db.clearFeedback()
        db.writableDatabase.delete("papers", null, null)
        db.writableDatabase.delete("shown", null, null)
        val prefs = Prefs(ctx)
        prefs.categories = setOf("cs.CV")
        prefs.digestSize = 5
        db.upsertPapers((1..40).map { paper(it) })
        return db to prefs
    }

    @Test
    fun `building stores the day's digest`() {
        val (db, prefs) = fresh()
        val built = DigestBuilder.build(db, prefs, emptyMap(), day = "2026-09-09")
        assertEquals(5, built.cards.size)
        val stored = db.digestFor("2026-09-09")
        assertEquals(built.cards.map { it.paper.id }, stored.map { it.paperId })
        // The reason each card carried is stored with it, so a replay is faithful.
        assertTrue(stored.all { it.reason.isNotBlank() })
    }

    @Test
    fun `rebuilding the same day replaces rather than appends`() {
        val (db, prefs) = fresh()
        DigestBuilder.build(db, prefs, emptyMap(), day = "2026-09-09")
        DigestBuilder.build(db, prefs, emptyMap(), day = "2026-09-09")
        assertEquals(5, db.digestFor("2026-09-09").size)
    }

    @Test
    fun `a later day does not re-show what an earlier one did`() {
        val (db, prefs) = fresh()
        val first = DigestBuilder.build(db, prefs, emptyMap(), day = "2026-09-09")
            .cards.map { it.paper.id }.toSet()
        val second = DigestBuilder.build(db, prefs, emptyMap(), day = "2026-09-10")
            .cards.map { it.paper.id }.toSet()
        assertTrue("yesterday's papers must not come back: $first vs $second",
            first.intersect(second).isEmpty())
    }

    @Test
    fun `a preview can be built without storing it`() {
        val (db, prefs) = fresh()
        val built = DigestBuilder.build(db, prefs, emptyMap(), day = "2026-09-09", store = false)
        assertTrue(built.cards.isNotEmpty())
        assertTrue(db.digestFor("2026-09-09").isEmpty())
    }
}
