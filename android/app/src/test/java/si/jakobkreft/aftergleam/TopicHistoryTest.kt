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
import si.jakobkreft.aftergleam.data.Signal
import si.jakobkreft.aftergleam.data.ShownItem
import java.time.LocalDate

/**
 * The bandit's view of history, read out of real rows rather than constructed by hand.
 *
 * The arithmetic is tested in TopicBanditTest; what is tested here is the join that feeds
 * it, which is where the mistakes actually live: double counting a resurfaced paper, or
 * dating a topic by when it was rated instead of when it was offered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopicHistoryTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val today = LocalDate.of(2026, 9, 9)

    private fun paper(id: String, cat: String) = Paper(
        id = id, title = "Title $id", abstract = "Abstract $id",
        authors = listOf("A"), categories = listOf(cat, "stat.ML"),
        published = "2026-01-01", updated = "2026-01-01",
    )

    /** Shows [n] papers of [cat] on the given day, engaging with the first [engaged] of them. */
    private fun show(db: Db, cat: String, daysAgo: Long, n: Int, engaged: Int, tag: String) {
        val papers = (0 until n).map { paper("$tag-$it", cat) }
        db.upsertPapers(papers)
        val day = today.minusDays(daysAgo).toString()
        db.markShown(papers.map { ShownItem(it.id, "RELEVANCE", "", 0.5f) }, day)
        papers.take(engaged).forEach { db.addSignal(it.id, Signal.SAVED) }
    }

    @Test
    fun `recent evidence outweighs old evidence of the same size`() {
        val db = Db(ctx)
        show(db, "cs.CV", daysAgo = 1, n = 10, engaged = 0, tag = "new")
        show(db, "cs.LG", daysAgo = 180, n = 10, engaged = 0, tag = "old")

        val h = db.topicHistory(today)
        val fresh = h.getValue("cs.CV").second
        val stale = h.getValue("cs.LG").second
        assertTrue("a day-old ignore should count for far more than a six-month-old one: " +
            "$fresh vs $stale", fresh > stale * 10)
    }

    @Test
    fun `an abandoned topic taken up again reads as engaged`() {
        val db = Db(ctx)
        // Ignored heavily half a year ago, then read again this week.
        show(db, "q-bio.NC", daysAgo = 200, n = 60, engaged = 0, tag = "then")
        show(db, "q-bio.NC", daysAgo = 3, n = 8, engaged = 6, tag = "now")

        val (engaged, ignored) = db.topicHistory(today).getValue("q-bio.NC")
        assertTrue("recent reading must dominate: $engaged engaged vs $ignored ignored",
            engaged > ignored)
    }

    @Test
    fun `a paper resurfaced several times is still one observation`() {
        val db = Db(ctx)
        val p = paper("r-1", "cs.CL")
        db.upsertPapers(listOf(p))
        val item = ShownItem(p.id, "RESURFACED", "", 0.5f)
        db.markShown(listOf(item), today.minusDays(40).toString())
        db.markShown(listOf(item), today.minusDays(20).toString())
        db.markShown(listOf(item), today.toString())

        val (engaged, ignored) = db.topicHistory(today).getValue("cs.CL")
        assertEquals(0f, engaged, 1e-4f)
        // One observation, dated by the most recent showing, so worth its full weight.
        assertEquals(1f, ignored, 1e-3f)
    }

    @Test
    fun `the topic is the primary category, not every category`() {
        val db = Db(ctx)
        show(db, "math.OC", daysAgo = 0, n = 4, engaged = 4, tag = "m")
        val h = db.topicHistory(today)
        assertTrue("primary category only, got ${h.keys}", h.keys == setOf("math.OC"))
    }

    @Test
    fun `no history at all is an empty map rather than zeroes`() {
        // The ranker uses emptiness to mean "the bandit has nothing to say", and falls back
        // to plain sampling. Rows of zeroes would send it down the bandit path with nothing
        // to allocate on.
        assertTrue(Db(ctx).topicHistory(today).isEmpty())
    }
}
