package si.jakobkreft.aftergleam

import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Topics

/**
 * Finding your own subject among a hundred and fourteen.
 *
 * The cases that matter are the ones where the reader's word is not the app's word. Nobody
 * looking for superconductivity guesses "condensed matter", and a physiotherapist types
 * "physiotherapy" rather than the name of whatever bucket it was filed in.
 */
class TopicSearchTest {

    private fun labels(q: String) = Topics.search(q).map { it.topic.label }

    @Test
    fun `the words people actually type find the right topic`() {
        val cases = mapOf(
            "physiotherapy" to "Rehabilitation and physiotherapy",
            "law" to "Public and constitutional law",
            "chemistry" to "Organic chemistry and synthesis",
            "psychology" to "Cognitive psychology",
            "machine learning" to "Machine learning",
            "cancer" to "Cancer, immunology and disease",
        )
        for ((query, expected) in cases) {
            assertTrue("'$query' did not find '$expected', got ${labels(query)}",
                expected in labels(query))
        }
    }

    @Test
    fun `a word from the abstracts finds the topic that has no such name`() {
        // None of these is a topic label; they are the vocabulary the seeds are written in.
        assertTrue("superconductivity", labels("superconductivity").isNotEmpty())
        assertTrue("qubit -> quantum", "Quantum physics" in labels("qubit"))
        assertTrue("galaxy -> astrophysics", "Astrophysics" in labels("galaxy"))
    }

    @Test
    fun `an archive code finds its topic`() {
        assertTrue("cs.CL", "Language models" in labels("cs.CL"))
        assertTrue("math-ph", "Mathematical physics" in labels("math-ph"))
    }

    @Test
    fun `a second word narrows rather than widens`() {
        val one = labels("education")
        val two = labels("higher education")
        assertTrue("narrowing should not grow the list", two.size <= one.size)
        assertTrue("Higher and adult education" in two)
    }

    @Test
    fun `label matches come before vocabulary matches`() {
        val hits = labels("law")
        val firstNonLaw = hits.indexOfFirst { !it.lowercase().contains("law") }
        if (firstNonLaw >= 0) {
            val lastLaw = hits.indexOfLast { it.lowercase().contains("law") }
            assertTrue("a topic named law must rank above one that merely mentions it",
                lastLaw < firstNonLaw || firstNonLaw == -1)
        }
    }

    @Test
    fun `the word itself outranks a word that merely starts the same`() {
        // "physiotherapy" shares six letters with "physiology", and the prefix rule that
        // makes "superconductivity" findable also makes those two collide.
        val hits = labels("physiotherapy")
        assertTrue("expected the physiotherapy topic first, got $hits",
            hits.first() == "Rehabilitation and physiotherapy")
    }

    @Test
    fun `an empty or unknown query returns nothing rather than everything`() {
        assertTrue(Topics.search("").isEmpty())
        assertTrue(Topics.search("   ").isEmpty())
        assertTrue(Topics.search("zzzzqqq").isEmpty())
    }
}
