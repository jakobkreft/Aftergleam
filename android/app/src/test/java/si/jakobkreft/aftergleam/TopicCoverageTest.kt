package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.Topics

/**
 * Every subject a server publishes should be reachable from the picker.
 *
 * A category no topic lists is a category the app never fetches, so those papers do not
 * exist as far as the reader is concerned, and nothing says so. That is how nutrition,
 * pathology, primary care research, palliative medicine and pharmacology went missing from
 * medRxiv, and how a physiotherapist ended up able to follow their own field only by
 * subscribing to seventeen other specialties along with it.
 *
 * The lists below are what the two servers actually returned across June to August 2026.
 * They change rarely, and when one does change this test is the thing that notices.
 */
class TopicCoverageTest {

    private val bioRxivSubjects = listOf(
        "animal behavior and cognition", "biochemistry", "bioengineering", "bioinformatics",
        "biophysics", "cancer biology", "cell biology", "developmental biology", "ecology",
        "evolutionary biology", "genetics", "genomics", "immunology", "microbiology",
        "molecular biology", "neuroscience", "paleontology", "pathology",
        "pharmacology and toxicology", "physiology", "plant biology",
        "scientific communication and education", "synthetic biology", "systems biology",
        "zoology",
    )

    private val medRxivSubjects = listOf(
        "addiction medicine", "allergy and immunology", "anesthesia",
        "cardiovascular medicine", "dentistry and oral medicine", "dermatology",
        "emergency medicine", "endocrinology", "epidemiology", "forensic medicine",
        "gastroenterology", "genetic and genomic medicine", "geriatric medicine",
        "health economics", "health informatics", "health policy",
        "health systems and quality improvement", "hematology", "hiv aids",
        "infectious diseases", "intensive care and critical care medicine",
        "medical education", "medical ethics", "nephrology", "neurology", "nursing",
        "nutrition", "obstetrics and gynecology", "occupational and environmental health",
        "oncology", "ophthalmology", "orthopedics", "otolaryngology", "pain medicine",
        "palliative medicine", "pathology", "pediatrics", "pharmacology and therapeutics",
        "primary care research", "psychiatry and clinical psychology",
        "public and global health", "radiology and imaging",
        "rehabilitation medicine and physical therapy", "respiratory medicine",
        "rheumatology", "sexual and reproductive health", "sports medicine", "surgery",
        "toxicology", "transplantation", "urology",
    )

    /**
     * Subjects deliberately left out, with the reason.
     *
     * Not an escape hatch for forgetting one: anything here is a decision, and a category
     * that turns out to matter should get a topic rather than a line in this list.
     */
    private val deliberatelyUnmapped = setOf(
        // Meta-research about how biology is written and taught, three papers a quarter.
        // A topic of its own would sit empty in the picker, and it belongs to no other.
        "scientific communication and education",
        // medRxiv lists these but published none in the sample; they have no natural home
        // and would be near-empty topics.
        "forensic medicine", "medical ethics", "toxicology",
    )

    private fun mapped(source: String): Set<String> =
        Topics.FIELDS.asSequence()
            .flatMap { it.topics.asSequence() }
            .filter { it.source == source }
            .flatMap { it.categories.asSequence() }
            .map { it.lowercase() }
            .toSet()

    @Test
    fun `every bioRxiv subject is reachable`() {
        val missing = bioRxivSubjects.toSet() - mapped(Source.BIORXIV) - deliberatelyUnmapped
        assertEquals("bioRxiv subjects no topic fetches: $missing", emptySet<String>(), missing)
    }

    @Test
    fun `every medRxiv subject is reachable`() {
        val missing = medRxivSubjects.toSet() - mapped(Source.MEDRXIV) - deliberatelyUnmapped
        assertEquals("medRxiv subjects no topic fetches: $missing", emptySet<String>(), missing)
    }

    @Test
    fun `physiotherapy can be followed on its own`() {
        val topic = Topics.FIELDS.flatMap { it.topics }
            .firstOrNull { "rehabilitation medicine and physical therapy" in it.categories }
        assertTrue("physiotherapy has no topic at all", topic != null)
        assertTrue(
            "it is buried with ${topic!!.categories.size} other subjects, which is the state " +
                "it was in when a physiotherapist could not follow it",
            topic.categories.size <= 4,
        )
    }

    @Test
    fun `the arXiv archives with real volume all have a topic`() {
        val cats = Topics.FIELDS.flatMap { it.topics }
            .filter { it.source == Source.ARXIV }
            .flatMap { it.categories }
            .toSet()
        // Each of these ran to more than a hundred submissions in August 2026 and none of
        // them could be subscribed to.
        for (c in listOf(
            "math-ph", "cond-mat.mes-hall", "cond-mat.stat-mech", "cond-mat.supr-con",
            "astro-ph.SR", "astro-ph.EP", "astro-ph.IM", "physics.comp-ph", "physics.app-ph",
            "physics.soc-ph", "physics.ins-det", "hep-lat", "cs.CE", "cs.DM", "cs.ET",
            "cs.MM", "math.CV", "math.AT", "math.AC",
        )) {
            assertTrue("$c has real volume and no topic reaches it", c in cats)
        }
    }

    @Test
    fun `no category is claimed by two topics of the same source`() {
        // Overlap is legitimate where it is meant, "cs.CV" feeding both vision and
        // generative models, so this only checks the new medical splits stayed disjoint.
        val medical = Topics.FIELDS.flatMap { it.topics }.filter { it.source == Source.MEDRXIV }
        val seen = mutableMapOf<String, String>()
        for (t in medical) for (c in t.categories) {
            val other = seen.put(c, t.key)
            assertEquals("$c is in both ${other} and ${t.key}", null, other)
        }
    }
}
