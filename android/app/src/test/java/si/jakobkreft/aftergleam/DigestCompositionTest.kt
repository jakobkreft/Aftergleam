package si.jakobkreft.aftergleam

import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Weights
import kotlin.random.Random

/**
 * How a scored list becomes a digest, checked on the three ways it threw the model away.
 *
 * Simulated readers with known interests got a digest that was, for the broadest of them, less
 * interesting on average than a random pick of the same day's papers, while the model's own
 * top twenty five was well above it. Each case here is one of the reasons.
 */
class DigestCompositionTest {

    private fun paper(id: String, cats: List<String>, title: String, comments: String = "") =
        Paper(
            id, title, "We study $title and report experiments on it.", listOf("A"), cats,
            "2026-09-24", "2026-09-24", comments,
        )

    private val subjects = listOf(
        "graph neural networks for molecule property prediction",
        "message passing on graphs with attention",
        "graph transformers for node classification",
        "oversmoothing in deep graph neural networks",
        "equivariant graph networks for molecular dynamics",
        "link prediction with graph autoencoders",
        "scalable graph learning with sampling",
        "graph neural networks for combinatorial optimisation",
    )

    private val negatives = (1..60).map {
        "a survey $it of convex optimisation for sparse linear regression in statistics"
    }

    @Test
    fun `categories a paper is cross-listed from do not take the reader's slots`() {
        // Topics used to be primary categories, so every category a paper was filed under
        // became a topic of its own, and an untried topic starts at a flat prior that beats
        // anything a reader reads at a realistic rate. These twenty are filed elsewhere and
        // cross-listed to the one category the reader follows, on a subject they never read.
        val theirs = (1..40).map {
            paper("m$it", listOf("cs.LG"), subjects[it % subjects.size] + " variant $it")
        }
        val filedElsewhere = listOf(
            "stat.ML", "math.OC", "eess.SP", "physics.comp-ph", "q-bio.QM", "econ.EM", "cs.IT",
            "math.ST", "cs.NE", "eess.SY", "cs.SI", "cs.DC", "quant-ph", "cs.CE", "math.NA",
            "cs.MA", "cs.GT", "cs.DS", "cs.CR", "cs.SE",
        ).mapIndexed { i, primary ->
            paper("x$i", listOf(primary, "cs.LG"),
                "stochastic volatility estimation for commodity futures markets $i")
        }
        val rated = subjects.take(5).map { RatedDoc(null, it, 0.95f) }

        val cards = (1..10).flatMap { seed ->
            Ranker(Weights(explorationRate = 0f, diversity = 0f)).digest(
                candidates = theirs + filedElsewhere,
                rated = rated,
                seen = emptySet(),
                subscribed = setOf("cs.LG"),
                size = 10,
                random = Random(seed),
                negativePool = negatives,
                // Read about one card in eight, which is what readers actually do.
                topicHistory = mapOf("cs.LG" to (4f to 26f)),
            )
        }
        val stray = cards.count { it.paper.id.startsWith("x") }.toFloat() / cards.size
        assertTrue("the reader's own subject should fill the digest, stray share was $stray",
            stray < 0.2f)
    }

    @Test
    fun `accepted papers on a subject the reader skips do not crowd out ones they read`() {
        // Twenty two signals, the evidence behind the real digest where this was found. The
        // model's confidence is shrunk toward a prior at that point, and every candidate sat
        // within 0.07 of every other, so an acceptance, worth a fixed 35%, decided the order.
        val matches = (1..20).map {
            paper("m$it", listOf("cs.LG"), subjects[it % subjects.size] + " variant $it")
        }
        val accepted = (1..20).map {
            paper("a$it", listOf("cs.LG"), "federated learning with differential privacy $it",
                comments = "Accepted at NeurIPS 2026")
        }
        val rated = subjects.take(5).map { RatedDoc(null, it, 0.95f) } +
            RatedDoc(null, "federated learning privacy for clients", 0f)

        val cards = (1..10).flatMap { seed ->
            Ranker(Weights(explorationRate = 0f, diversity = 0f)).digest(
                candidates = matches + accepted,
                rated = rated,
                seen = emptySet(),
                subscribed = setOf("cs.LG"),
                size = 10,
                random = Random(seed),
                negativePool = negatives,
                evidenceCount = 22,
            )
        }
        val skipped = cards.count { it.paper.id.startsWith("a") }.toFloat() / cards.size
        assertTrue("a venue should adjust the order, not decide it; skipped share was $skipped",
            skipped < 0.2f)
    }

    @Test
    fun `variety applies across topics, not only within one draw`() {
        // With a topic history the digest is filled one paper at a time, a topic drawn for
        // each slot. Each of those draws started with nothing chosen, so there was nothing to
        // be different from, and near copies filled the digest whatever the setting said.
        val copies = (1..12).map {
            paper("c$it", listOf("cs.LG"),
                "graph neural networks for molecule property prediction benchmark $it")
        }
        val varied = (1..12).map {
            paper("v$it", listOf("cs.LG"), subjects[it % subjects.size] + " study $it")
        }
        val language = (1..12).map {
            paper("l$it", listOf("cs.CL"), "retrieval augmented generation for question answering $it")
        }
        val rated = listOf(
            RatedDoc(null, "graph neural networks for molecule property prediction", 0.95f),
            RatedDoc(null, "molecule property prediction with graph networks", 0.95f),
            RatedDoc(null, "retrieval augmented generation for question answering", 0.6f),
        )

        val copiesPerDigest = (1..10).map { seed ->
            Ranker(Weights(explorationRate = 0f, diversity = 0.6f)).digest(
                candidates = copies + varied + language,
                rated = rated,
                seen = emptySet(),
                subscribed = setOf("cs.LG", "cs.CL"),
                size = 12,
                random = Random(seed),
                negativePool = negatives,
                topicHistory = mapOf("cs.LG" to (5f to 20f), "cs.CL" to (3f to 20f)),
            ).count { it.paper.id.startsWith("c") }
        }
        assertTrue("near copies should not stack up, got $copiesPerDigest per digest",
            copiesPerDigest.average() <= 2.0)
    }
}
