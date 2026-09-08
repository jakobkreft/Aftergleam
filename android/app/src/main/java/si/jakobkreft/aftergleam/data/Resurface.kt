package si.jakobkreft.aftergleam.data

/**
 * Papers the user passed over that turned out to matter.
 *
 * The structural asymmetry this exploits: the user had two seconds to judge a paper, and
 * the world takes months to answer. The app can hold the question open, which is a thing no
 * amount of better ranking can do.
 *
 * The original design proposed citation counts here. That is not available: OpenAlex
 * carries no citation edges for arXiv preprints, so the honest evidence is venue acceptance,
 * which authors add to the comments field when a paper is accepted. Measured, it reaches
 * roughly 30% of papers at six to twelve months, which is the window this looks at.
 *
 *   "You passed on this in March. It was just accepted to NeurIPS 2026."
 *
 * That claim is arguably stronger than a citation count, because it is a discrete human
 * judgement rather than a number needing age normalisation.
 */
data class Resurfaced(
    val paper: Paper,
    val venue: String,
    val shownOn: String,
    val wasRated: Float?,
) {
    /**
     * Framed as discovery, never as failure. The design note is explicit that this reads as
     * nagging if it is written as "you were wrong", and the feature lives or dies on tone.
     */
    fun headline(): String = "You passed on this in ${monthName(shownOn)}"

    fun detail(): String = "It was accepted to $venue."

    private fun monthName(iso: String): String {
        val month = iso.substringAfter('-').substringBefore('-').toIntOrNull() ?: return "an earlier digest"
        val names = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
        return names.getOrElse(month - 1) { "an earlier digest" }
    }
}
