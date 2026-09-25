package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.ui.FeedViewModel
import kotlin.random.Random

/**
 * A reader whose field produces four papers a day.
 *
 * Everything the app does was built and checked against arXiv subjects that publish hundreds
 * a day, where there is always more than enough to fill a digest. Law publishes about nine a
 * week, and every assumption that a pool is larger than a screen breaks at once. A real
 * law-only profile produced a digest of twenty five cards containing no law at all: the
 * bridge fetches outside the reader's fields on purpose, those papers land in the same table
 * as everything else, and every slot was drawing from all of it.
 */
class NarrowFieldTest {

    private fun paper(id: String, cat: String, text: String) = Paper(
        id, "Paper $id about $text", "An abstract about $text and related matters.",
        listOf("A"), listOf(cat), "2026-09-01", "2026-09-01", "",
    )

    /** Four law papers, and eighty from the categories the bridge went looking in. */
    private val law = (1..4).map { paper("law$it", "lawarchive:law", "statutes and courts") }
    private val outside = (1..80).map { paper("cs$it", "cs.CY", "algorithms and society") }

    private fun digest(size: Int = 25) = Ranker().digest(
        candidates = law + outside,
        rated = law.take(3).map { RatedDoc(it.id, it.rankText, 0.9f) },
        seen = emptySet(),
        subscribed = setOf("lawarchive:law"),
        size = size,
        random = Random(1),
        negativePool = outside.map { it.rankText },
    )

    @Test
    fun `a narrow digest is short rather than padded with things nobody chose`() {
        val cards = digest()
        val offTopic = cards.filter {
            it.slot != Slot.BRIDGE && it.paper.categories.none { c -> c == "lawarchive:law" }
        }
        assertEquals(
            "only the bridge may come from outside the reader's subjects, got " +
                offTopic.map { it.paper.categories },
            emptyList<String>(), offTopic.map { it.paper.id },
        )
        assertTrue("a short day should stay short, got ${cards.size} cards", cards.size <= 5)
    }

    @Test
    fun `the survey leaves papers for the first digest`() {
        // The deck size rule, checked directly: with six papers in the whole field the
        // survey may take three, not all six.
        fun cap(available: Int) = minOf(
            FeedViewModel.SURVEY_CARDS,
            maxOf(Ranker.MIN_RATINGS, available - FeedViewModel.SURVEY_RESERVE),
        )
        assertEquals("a busy field is unaffected", 12, cap(400))
        assertEquals("six papers: ask about three, leave three", 3, cap(6))
        assertEquals("ten papers: ask about five, leave five", 5, cap(10))
        assertTrue("never below what switches ranking on", cap(1) >= Ranker.MIN_RATINGS)
    }

    @Test
    fun `the one bridge card is still offered`() {
        val bridges = digest().filter { it.slot == Slot.BRIDGE }
        assertTrue("the bridge should still fire, it is the one deliberate exception",
            bridges.size <= 1)
        bridges.forEach {
            assertTrue("a bridge card must come from outside", "cs.CY" in it.paper.categories)
        }
    }

    @Test
    fun `a broad field still fills the whole digest`() {
        // The other half of the scope rule: restricting the slots must not shrink a digest
        // for the readers it was working for all along.
        val many = (1..300).map { paper("cs$it", "cs.LG", "training neural networks $it") }
        val cards = Ranker().digest(
            candidates = many + outside,
            rated = many.take(5).map { RatedDoc(it.id, it.rankText, 0.9f) },
            seen = emptySet(),
            subscribed = setOf("cs.LG"),
            size = 25,
            random = Random(2),
            negativePool = outside.map { it.rankText },
        )
        assertEquals("a busy field should still get a full digest", 25, cards.size)
    }

    @Test
    fun `a day smaller than the digest still keeps outside papers to the bridge`() {
        // With fewer candidates than cards the ranker used to hand everything back unsorted
        // and unscoped, so the bridge's papers came through as ordinary matches. A law reader
        // with one unread law paper and five fetched for the bridge saw six "matches".
        val cards = Ranker().digest(
            candidates = law + outside.take(5),
            rated = law.take(3).map { RatedDoc(it.id, it.rankText, 0.9f) },
            seen = emptySet(),
            subscribed = setOf("lawarchive:law"),
            size = 25,
            random = Random(1),
            negativePool = outside.drop(5).map { it.rankText },
        )
        val offTopic = cards.filter {
            it.slot != Slot.BRIDGE && it.paper.categories.none { c -> c == "lawarchive:law" }
        }
        assertEquals(emptyList<String>(), offTopic.map { it.paper.id })
        assertEquals("the unread law paper and one bridge", 2, cards.size)
    }

    @Test
    fun `every card the reader sees is one they asked for`() {
        for (card in digest().filter { it.slot != Slot.BRIDGE }) {
            assertTrue(
                "${card.paper.id} is in ${card.paper.categories}, which was never subscribed",
                card.paper.categories.any { it == "lawarchive:law" },
            )
        }
    }
}
