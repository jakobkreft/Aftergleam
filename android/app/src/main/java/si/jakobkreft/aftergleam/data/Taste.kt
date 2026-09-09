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
    data class Probe(val label: String, val category: String, val phrase: String)

    val PROBES: List<Probe> = listOf(
        Probe("Language models", "cs.CL", "large language model reasoning"),
        Probe("Image generation", "cs.CV", "diffusion model image generation"),
        Probe("Vision and perception", "cs.CV", "object detection segmentation"),
        Probe("Learning theory", "cs.LG", "generalisation bounds optimisation"),
        Probe("Robotics and control", "cs.RO", "manipulation policy learning"),
        Probe("Speech and audio", "eess.AS", "speech recognition synthesis"),
        Probe("Security and privacy", "cs.CR", "privacy attack defence"),
        Probe("Systems and efficiency", "cs.DC", "efficient inference serving"),
        Probe("Graphs and networks", "cs.SI", "graph neural network"),
        Probe("Neuroscience", "q-bio.NC", "neural coding brain"),
        Probe("Statistics", "stat.ME", "bayesian inference estimation"),
        Probe("Astronomy and physics", "astro-ph.IM", "survey data analysis pipeline"),
    )

    /**
     * Categories to subscribe to, inferred from what the user actually liked.
     *
     * Their real categories come from the papers themselves rather than from the probe
     * label, because a paper found under "image generation" is frequently cross-listed
     * somewhere more useful. Falls back to the probe categories when nothing was liked.
     */
    fun categoriesFrom(liked: List<Paper>, likedProbes: List<Probe>): Set<String> {
        val counted = liked
            .flatMap { it.categories }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            .keys
        val fromProbes = likedProbes.map { it.category }.toSet()
        return (counted + fromProbes).ifEmpty { setOf("cs.LG") }
    }
}
