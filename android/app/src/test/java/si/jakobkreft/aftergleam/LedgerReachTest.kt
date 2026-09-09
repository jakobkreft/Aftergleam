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
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.ShownItem
import si.jakobkreft.aftergleam.data.Signal

/**
 * Everything that consumes a judgement must consume the same one.
 *
 * The app grew a signal ledger but kept the rating column it replaced, and for a while half
 * the code wrote to one and half read from the other. Onboarding, library import, backup and
 * the resurfacer all wrote judgements the model could not see, so a reader could answer
 * twenty survey questions, watch the library count them, and get a ranker that had learned
 * nothing. Nothing failed; the two halves simply never met.
 *
 * These pin the consumers to the ledger. The producers are pinned by the compiler: the
 * rating field is gone from `Reaction`, so there is no second place left to write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerReachTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun paper(id: String) = Paper(
        id = id, title = "Title $id", abstract = "Abstract $id about diffusion models",
        authors = listOf("A"), categories = listOf("cs.CV"),
        published = "2026-09-01", updated = "2026-09-01",
    )

    @Test
    fun `a judgement reaches the training set, the shelf and the drift report`() {
        val db = Db(ctx)
        db.clearFeedback()
        db.upsertPapers(listOf(paper("j1"), paper("j2")))
        db.addSignal("j1", Signal.LIKED)
        db.addSignal("j2", Signal.DISLIKED)

        // The training set.
        val ev = db.evidence()
        assertEquals(2, ev.count { it.value.label() != null })

        // The library's reacted shelf, which reads explicit judgements only.
        assertEquals(setOf("j1", "j2"), ev.filterValues { it.explicit }.keys)

        // The drift report, which used to read the rating column and so was empty for
        // anyone who had only ever used the buttons.
        val now = System.currentTimeMillis()
        val seen = db.ratedBetween(now - 60_000, now + 60_000).map { it.first.id }.toSet()
        assertEquals(setOf("j1", "j2"), seen)
    }

    @Test
    fun `the exploration report counts judged cards from the ledger`() {
        val db = Db(ctx)
        db.clearFeedback()
        db.upsertPapers(listOf(paper("e1"), paper("e2"), paper("e3")))
        db.markShown(
            listOf("e1", "e2", "e3").map { ShownItem(it, "EXPLORATION", "", 0.5f) },
            "2026-09-09",
        )
        db.addSignal("e1", Signal.LIKED)
        db.addSignal("e2", Signal.DISLIKED)
        // e3 was never judged, so it is not a miss, it is a card nobody reached.
        db.addSignal("e3", Signal.OPENED)

        val (judged, liked) = db.explorationOutcome("2026-09-01", "2026-09-30")
        assertEquals(2, judged)
        assertEquals(1, liked)
    }

    @Test
    fun `the resurfacer offers papers the ledger never heard about`() {
        val db = Db(ctx)
        db.clearFeedback()
        val accepted = paper("r1").copy(comments = "Accepted to ICLR 2026")
        val alsoAccepted = paper("r2").copy(comments = "Accepted to ICLR 2026")
        db.upsertPapers(listOf(accepted, alsoAccepted))
        db.markShown(
            listOf(ShownItem("r1", "RELEVANCE", "", 0.5f), ShownItem("r2", "RELEVANCE", "", 0.5f)),
            "2026-05-01",
        )
        // The reader already engaged with r2, so offering it back as "you passed on this"
        // would be plainly wrong. Keyed off the rating column it was invisible.
        db.addSignal("r2", Signal.SAVED)

        val ids = db.resurfaceCandidates("2026-04-01", "2026-06-01").map { it.id }
        assertEquals(listOf("r1"), ids)
    }

    @Test
    fun `my library search finds papers reacted to, not only saved`() {
        val db = Db(ctx)
        db.clearFeedback()
        db.upsertPapers(listOf(paper("s1"), paper("s2"), paper("s3")))
        db.setReaction("s1", Reaction(saved = true))
        db.addSignal("s2", Signal.LIKED)

        val hits = db.searchLocal("diffusion", savedOnly = true).map { it.id }.toSet()
        assertTrue("a saved paper is in my library, got $hits", "s1" in hits)
        assertTrue("so is one reacted to, got $hits", "s2" in hits)
        assertTrue("an untouched paper is not, got $hits", "s3" !in hits)
    }

    @Test
    fun `resetting forgets the ledger, not just the reactions table`() {
        // The setting promises the model is reset. Clearing only the reactions table left
        // every judgement in place and the next digest ranked exactly as before.
        val db = Db(ctx)
        db.upsertPapers(listOf(paper("x1")))
        db.addSignal("x1", Signal.LIKED)
        db.setReaction("x1", Reaction(saved = true))

        db.clearFeedback()
        assertTrue(db.evidence().isEmpty())
        assertTrue(db.allReactions().isEmpty())
    }
}
