package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Slot
import kotlin.random.Random

/**
 * What the top of the digest is allowed to be.
 *
 * On a real reader's phone the "outside your usual" card was first on three days in seven and
 * second on most others, and five of the top seven cards on one day were labelled "testing
 * whether this is for you". Neither was a better match. Relevance was nearly flat, a conference
 * acceptance multiplies the score, and exploration was choosing the highest-scored papers.
 */
class DigestOrderTest {

    private fun paper(id: String, cat: String, text: String, comments: String = "") = Paper(
        id, "Paper $id on $text", "An abstract about $text, in some detail.",
        listOf("A"), listOf(cat), "2026-09-24", "2026-09-24", comments,
    )

    private val mine = (1..80).map { paper("m$it", "cs.CV", "image diffusion model number $it") }

    /** Outside the reader's fields, and accepted at a conference, which is what won before. */
    private val outside = (1..20).map {
        paper("o$it", "cs.RO", "robot grasping $it", comments = "Accepted at IROS 2026")
    }

    private fun digest(seed: Int) = Ranker().digest(
        candidates = mine + outside,
        rated = mine.take(8).map { RatedDoc(it.id, it.rankText, 0.95f) },
        seen = emptySet(),
        subscribed = setOf("cs.CV"),
        size = 25,
        random = Random(seed),
        negativePool = outside.map { it.rankText },
        topicHistory = mapOf("cs.CV" to (3f to 1f)),
    )

    @Test
    fun `the top of the digest is always the best matches`() {
        repeat(20) { seed ->
            val top = digest(seed).take(Ranker.HEAD)
            assertTrue(
                "seed $seed: top was ${top.map { it.slot }}",
                top.all { it.slot == Slot.RELEVANCE },
            )
        }
    }

    @Test
    fun `the bridge comes straight after the head rather than at the top or the bottom`() {
        repeat(20) { seed ->
            val cards = digest(seed)
            val at = cards.indexOfFirst { it.slot == Slot.BRIDGE }
            if (at >= 0) assertEquals("seed $seed", Ranker.HEAD, at)
        }
    }

    @Test
    fun `exploration never takes the papers the model rates highest`() {
        repeat(20) { seed ->
            val cards = digest(seed)
            val explore = cards.filter { it.slot == Slot.EXPLORATION }
            val relevance = cards.filter { it.slot == Slot.RELEVANCE }
            if (explore.isEmpty() || relevance.isEmpty()) return@repeat
            // Near misses sit below the matches chosen to show, never above all of them.
            assertTrue(
                "seed $seed: exploration ${explore.maxOf { it.relevance }} beat every match",
                explore.maxOf { it.relevance } <= relevance.maxOf { it.relevance },
            )
        }
    }

    @Test
    fun `detours are spread through the digest, not stacked`() {
        val slots = digest(1).map { it.slot }
        for (i in 1 until slots.size) {
            val stacked = slots[i] != Slot.RELEVANCE && slots[i - 1] != Slot.RELEVANCE
            // Two in a row only once the matches have run out, at the very end.
            if (stacked) assertTrue(slots.drop(i).none { it == Slot.RELEVANCE })
        }
    }
}
