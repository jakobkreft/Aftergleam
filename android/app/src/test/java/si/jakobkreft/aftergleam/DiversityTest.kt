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

    private fun run(diversity: Float): List<String> =
        Ranker(Weights(diversity = diversity, explorationRate = 0f))
            .digest(
                candidates = candidates(),
                rated = rated,
                seen = emptySet(),
                subscribed = setOf("cs.CV"),
                size = 6,
                negativePool = negatives,
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
    fun `turning diversity off leaves ranking untouched`() {
        val a = run(0f)
        val b = run(0f)
        assertTrue("selection must be deterministic with diversity off", a == b)
        assertTrue("a full digest is still returned", a.size == 6)
    }
}
