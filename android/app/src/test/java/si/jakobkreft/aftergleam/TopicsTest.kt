package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Taste
import si.jakobkreft.aftergleam.data.Topics
import si.jakobkreft.aftergleam.rank.LogReg
import si.jakobkreft.aftergleam.rank.Tfidf

class TopicsTest {

    @Test
    fun `the taxonomy covers the archive and not only machine learning`() {
        val cats = Topics.FIELDS.flatMap { it.topics }.flatMap { it.categories }.toSet()
        for (archive in listOf("astro-ph", "hep", "math.", "q-bio", "q-fin", "eess", "econ", "cond-mat")) {
            assertTrue("no topic reaches $archive, so that field cannot onboard",
                cats.any { it.startsWith(archive) })
        }
        assertTrue("a physicist should have several topics to choose from",
            Topics.FIELDS.first { it.label == "Physics" }.topics.size >= 5)
    }

    @Test
    fun `topic keys are unique`() {
        val keys = Topics.FIELDS.flatMap { it.topics }.map { it.key }
        assertEquals("duplicate keys would silently collide in preferences",
            keys.size, keys.toSet().size)
    }

    @Test
    fun `chosen topics yield their categories`() {
        val cats = Topics.categoriesFor(setOf("genomics", "astro"))
        assertTrue(cats.contains("q-bio.GN"))
        assertTrue(cats.any { it.startsWith("astro-ph") })
        assertTrue("unrelated fields must not appear", !cats.contains("cs.LG"))
    }

    @Test
    fun `survey probes follow the chosen topics`() {
        val probes = Taste.probesFor(setOf("neuro", "genomics"))
        assertTrue("probes must exist", probes.isNotEmpty())
        assertTrue("a biologist must not be asked about machine learning: " +
            probes.map { it.category },
            probes.all { it.category.startsWith("q-bio") })
    }

    @Test
    fun `seed text alone separates a chosen topic from an unrelated paper`() {
        // The point of the seeds: a usable ranking before any paper has been judged.
        val seeds = Topics.seedsFor(setOf("genomics", "neuro"))
        val negatives = List(30) { "portfolio volatility asset pricing risk trading returns $it" }
        val docs = seeds + negatives
        val vec = Tfidf(minDf = 1).apply { fit(docs) }
        val y = FloatArray(docs.size) { if (it < seeds.size) 0.7f else 0f }
        val clf = LogReg(vec.size).apply { fit(docs.map { vec.transform(it) }, y) }

        val onTopic = clf.predict(vec.transform(
            "single cell rna sequencing reveals gene expression across cortex neurons"))
        val offTopic = clf.predict(vec.transform(
            "hedging strategies for volatility in equity portfolios"))
        assertTrue("seeds should already favour the chosen subject: $onTopic vs $offTopic",
            onTopic > offTopic)
    }
}
