package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Drift
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.rank.Explain
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Tfidf

/**
 * The reading card: themes named in the words the cards use, compared fortnight to fortnight,
 * and silence where there is not enough to say.
 */
class DriftTest {

    private var next = 0

    private fun read(text: String, interest: Float = 0.9f, authors: List<String> = listOf("A. Author")) =
        Paper(
            id = "p${next++}", title = text, abstract = text,
            authors = authors, categories = listOf("cs.CV"),
            published = "2026-09-01", updated = "2026-09-01",
        ) to interest

    /** Each paper's reason is what its title says, comma-separated: a stand-in for the model. */
    private val byTitle: (Paper) -> List<String> = { p -> p.title.split(",").map { it.trim() } }

    private fun compute(recent: List<Pair<Paper, Float>>, earlier: List<Pair<Paper, Float>> = emptyList()) =
        Drift.compute(recent, earlier, byTitle, explorationJudged = 0, explorationLiked = 0)

    @Test
    fun `a shift in reading shows as more of the new themes and less of the old`() {
        val earlier = List(6) { read("image segmentation, medical imaging") }
        val recent = List(6) { read("world models, video generation") }
        val r = compute(recent, earlier)
        assertTrue(r.compared)
        assertEquals(setOf("world models", "video generation"), r.more.toSet())
        assertEquals(setOf("image segmentation", "medical imaging"), r.less.toSet())
    }

    @Test
    fun `reading more of the same is not a rise`() {
        val earlier = List(6) { read("world models") }
        val recent = List(12) { read("world models") }
        val r = compute(recent, earlier)
        assertEquals(emptyList<String>(), r.more)
        assertEquals(listOf("world models"), r.lately)
    }

    @Test
    fun `a theme needs two papers behind it`() {
        val recent = List(5) { read("world models") } + read("lattice gauge theory")
        assertEquals(listOf("world models"), compute(recent).lately)
    }

    @Test
    fun `plurals are one theme, and a phrase stands for its word`() {
        val recent = listOf(
            read("world model"), read("world models"), read("world"), read("world"),
            read("audio, cross modal"), read("audio"),
        )
        val lately = compute(recent).lately
        assertTrue("one subject, named by its phrase: $lately", lately.count { "world" in it } == 1)
        assertTrue(lately.any { it.startsWith("world model") })
        assertTrue("audio" in lately)
    }

    @Test
    fun `one paper's capitals name the theme for all of them`() {
        val recent = listOf(read("lora"), read("LoRA"), read("lora")) + List(3) { read("audio") }
        assertTrue(compute(recent).lately.contains("LoRA"))
    }

    @Test
    fun `papers opened count less than papers liked`() {
        val recent = List(3) { read("world models", 0.25f) } + List(3) { read("audio", 0.95f) }
        assertEquals("audio", compute(recent).lately.first())
    }

    @Test
    fun `rejected papers do not shape the picture`() {
        val recent = List(6) { read("world models") } + List(6) { read("lattice gauge theory", 0f) }
        val r = compute(recent)
        assertEquals(6, r.recentCount)
        assertTrue(r.lately.none { "lattice" in it })
    }

    @Test
    fun `too little reading, or no model yet, is said rather than invented`() {
        assertTrue("four papers is not a fortnight's reading", compute(List(4) { read("world models") }).thin)
        val noModel = Drift.compute(List(8) { read("world models") }, emptyList(), reasons = null, 0, 0)
        assertTrue(noModel.thin)
        assertFalse("nothing before to compare with", compute(List(6) { read("audio") }).compared)
    }

    @Test
    fun `repeatedly liked authors surface`() {
        val papers = listOf(
            read("panoramic diffusion", 0.9f, listOf("Jane Roe", "X Y")),
            read("panoramic outpainting", 0.9f, listOf("Jane Roe")),
            read("something else entirely", 0.9f, listOf("Other Person")),
        )
        val r = compute(papers)
        assertTrue("expected the repeated author, got ${r.recurringAuthors}",
            r.recurringAuthors.any { it.startsWith("Jane Roe") })
        assertTrue("a single appearance is not a pattern",
            r.recurringAuthors.none { it.startsWith("Other Person") })
    }

    @Test
    fun `the exploration note is honest when exploration is failing`() {
        val r = Drift.compute(emptyList(), emptyList(), byTitle, explorationJudged = 10, explorationLiked = 0)
        val note = r.explorationNote()
        assertTrue("a total miss must be stated: $note", note != null && note.contains("wrong direction"))
        assertTrue("the count must be of cards the user judged, not cards shown: $note", note!!.contains("you rated"))
    }

    @Test
    fun `no exploration note when there is not enough to judge`() {
        assertEquals(null, Drift.compute(emptyList(), emptyList(), byTitle, 3, 0).explorationNote())
    }

    @Test
    fun `themes are subjects, never the words every abstract uses`() {
        // The words the old card reported, "both, four, show", are in every abstract here.
        // The subjects are shared by some papers only, which is what a reason is made of.
        fun abstract(subject: String) =
            "We show both results in four settings. $subject. Both experiments show that the approach works in four cases."
        val kept = listOf(
            "world models for video generation", "video generation with world models",
            "audio visual contrastive learning", "contrastive audio visual pretraining",
        )
        val read = listOf(
            "learned world models predict video generation", "world models and video generation at scale",
            "audio visual contrastive learning from video", "contrastive audio visual alignment",
            "world models for robot video generation", "audio visual contrastive retrieval",
        ).map { read(abstract(it)) }
        val unrelated = List(20) { abstract("lattice gauge theory number $it") }
        val vec = Tfidf(minDf = 1).apply { fit(kept.map(::abstract) + read.map { it.first.rankText } + unrelated) }
        val rated = kept.mapIndexed { i, text -> RatedDoc("k$i", abstract(text), 0.9f) }

        val lately = Drift.compute(read, emptyList(), Explain.reasons(rated, vec), 0, 0).lately
        assertTrue("expected subjects, got $lately", lately.isNotEmpty())
        val words = lately.flatMap { it.split(' ') }
        assertTrue("no filler in $lately", words.none { it in setOf("both", "four", "show", "results", "settings", "approach") })
        assertTrue("the subjects come through: $lately",
            words.any { it in setOf("world", "models", "video", "generation", "audio", "visual", "contrastive") })
    }
}
