package si.jakobkreft.aftergleam.data

/**
 * The onboarding survey: learn taste from papers, not from a taxonomy.
 *
 * Asking a new user to commit to arXiv categories up front is the wrong question twice over.
 * It demands a decision before they have seen anything, and the answer is a filter rather
 * than a taste: "cs.LG" describes six thousand papers a month with nothing in common. It
 * also asks people to name their own interests, which they are famously bad at.
 *
 * Instead the survey shows real, recent, well-cited-looking papers and asks the only question
 * anyone can answer reliably: would you read this? A handful of yes-or-no answers on concrete
 * papers gives both a trained model and, as a by-product, the categories to subscribe to.
 * Nothing is committed to in advance and every answer is visible and reversible afterwards.
 *
 * The seeds are chosen for spread rather than quality. A survey that only shows machine
 * learning papers can only ever conclude that the user likes machine learning.
 */
object Taste {

    /**
     * Broad probes across the archive. Each is a category plus a search phrase, so the
     * survey draws real current papers rather than shipping a frozen list that ages badly.
     */
    /**
     * One survey question's subject: a topic's name, and where to find papers for it.
     *
     * It used to carry a search phrase as well, because each question was its own keyword
     * request to arXiv. The deck is drawn from the feed the app fetches anyway now, so what
     * is left is the label to show and the category to draw from.
     */
    data class Probe(
        val label: String,
        val category: String,
        val source: String = Source.ARXIV,
    )

    /**
     * Probes for the topics the user actually chose.
     *
     * Each topic contributes one probe per category it covers, capped so a reader who picks
     * many topics is not asked forty questions.
     */
    fun probesFor(topicKeys: Set<String>, cap: Int = 12): List<Probe> {
        val chosen = topicKeys.mapNotNull { Topics.topic(it) }
        if (chosen.isEmpty()) return PROBES.take(cap)
        // Round-robin over topics so the questions alternate subject from the start.
        val perTopic = chosen.map { t ->
            t.categories.map { cat -> Probe(t.label, cat, t.source) }
        }
        val out = mutableListOf<Probe>()
        var i = 0
        while (out.size < cap && perTopic.any { i < it.size }) {
            for (group in perTopic) {
                group.getOrNull(i)?.let { if (out.size < cap) out += it }
            }
            i++
        }
        return out
    }

    /** Fallback set, used only if somebody reaches the survey having chosen nothing. */
    val PROBES: List<Probe> = listOf(
        Probe("Language models", "cs.CL"),
        Probe("Image generation", "cs.CV"),
        Probe("Vision and perception", "cs.CV"),
        Probe("Learning theory", "cs.LG"),
        Probe("Robotics and control", "cs.RO"),
        Probe("Speech and audio", "eess.AS"),
        Probe("Security and privacy", "cs.CR"),
        Probe("Systems and efficiency", "cs.DC"),
        Probe("Graphs and networks", "cs.SI"),
        Probe("Neuroscience", "q-bio.NC"),
        Probe("Statistics", "stat.ME"),
        Probe("Astronomy and physics", "astro-ph.IM"),
    )

    /**
     * Categories to subscribe to, inferred from what the user actually liked.
     *
     * Their real categories come from the papers themselves rather than from the probe
     * label, because a paper found under "image generation" is frequently cross-listed
     * somewhere more useful.
     *
     * Nothing liked means nothing to add. This used to fall back to cs.LG, from when every
     * reader was assumed to be in machine learning, and a reader who followed criminal law
     * and skipped the survey was subscribed to cs.LG: their first digest was twenty four
     * machine learning papers and no law. The chosen subjects are subscribed regardless, and
     * they are what a reader who liked nothing has said.
     */
    fun categoriesFrom(liked: List<Paper>, likedProbes: List<Probe>): Set<String> {
        val counted = liked
            .flatMap { it.categories }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            .keys
        val fromProbes = likedProbes.map { Source.qualify(it.source, it.category) }.toSet()
        return counted + fromProbes
    }
}
