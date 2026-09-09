package si.jakobkreft.aftergleam

import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Weights

/**
 * A real digest came back with all twenty-five explanations beginning "matches reasoning",
 * because the model had learned one topic and the top of a purely ranked list is where that
 * topic lives. These check that trading a little relevance for variety actually changes what
 * gets shown, and that turning the knob off restores the old behaviour.
 */
class DiversityTest {

    private fun paper(id: String, text: String) = Paper(
        id = id,
        title = text,
        abstract = text,
        authors = listOf("A"),
        categories = listOf("cs.CV"),
        published = "2026-09-04",
        updated = "2026-09-04",
    )

    /** Twenty near-identical papers on the rated topic, five on a different one. */
    private fun candidates(): List<Paper> {
        val crowd = (1..20).map {
            paper("crowd$it",
                "vision language action model for robotic manipulation reasoning benchmark $it")
        }
        val others = (1..5).map {
            paper("other$it",
                "spherical diffusion outpainting for panoramic image synthesis variant $it")
        }
        return crowd + others
    }

    private val rated = listOf(
        RatedDoc(null, "vision language action reasoning for robot manipulation", 0.9f),
        RatedDoc(null, "benchmark for vision language models and manipulation", 0.9f),
        RatedDoc(null, "panoramic diffusion outpainting image synthesis", 0.9f),
    )

    private val negatives = (1..40).map {
        "study $it of convex optimisation convergence for sparse linear estimation"
    }

    // Temperature near zero and a fixed seed, because the digest is now sampled rather than
    // taken off the top. This isolates the diversity pass from the draw.
    private fun run(diversity: Float, seed: Int = 7, temperature: Float = 0.01f): List<String> =
        Ranker(Weights(diversity = diversity, explorationRate = 0f, temperature = temperature))
            .digest(
                candidates = candidates(),
                rated = rated,
                seen = emptySet(),
                subscribed = setOf("cs.CV"),
                size = 6,
                random = kotlin.random.Random(seed),
                negativePool = negatives,
                evidenceCount = 40,
            )
            .map { it.paper.id }

    @Test
    fun `diversity surfaces the smaller topic that pure ranking buries`() {
        val plain = run(0f).count { it.startsWith("other") }
        val diverse = run(0.6f).count { it.startsWith("other") }
        assertTrue(
            "expected variety to promote the minority topic: plain=$plain diverse=$diverse",
            diverse > plain,
        )
    }

    @Test
    fun `the same seed gives the same digest`() {
        // The digest is a sample, so it is deliberately not deterministic across runs. It
        // must still be reproducible given a seed, or nothing about it can be tested.
        assertTrue("same seed, same draw", run(0f, seed = 3) == run(0f, seed = 3))
        assertTrue("a full digest is still returned", run(0f).size == 6)
    }

    @Test
    fun `different seeds give different digests`() {
        // This is the point of sampling: re-ranking with no new data returns something new,
        // which is what makes "show me more" mean anything.
        // At a realistic temperature, not the near-zero one the other tests pin for
        // reproducibility.
        val seeds = (1..12).map { run(0.3f, seed = it, temperature = 0.4f) }
        assertTrue("sampling must not always return the same set: $seeds",
            seeds.distinct().size > 1)
    }
}
