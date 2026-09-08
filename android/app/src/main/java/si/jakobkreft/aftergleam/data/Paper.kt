package si.jakobkreft.aftergleam.data

/**
 * One arXiv paper as the app needs it.
 *
 * [comments] and [journalRef] are not decoration: they carry the venue-acceptance signal
 * ("ICLR 2025 Oral") that replaces citation counts as the quality signal. Coverage runs
 * from ~9% at one month to ~30% at 6-12 months, and it costs no extra network call.
 */
data class Paper(
    val id: String,
    val title: String,
    val abstract: String,
    val authors: List<String>,
    val categories: List<String>,
    val published: String,
    val updated: String,
    val comments: String = "",
    val journalRef: String = "",
) {
    val primaryCategory: String get() = categories.firstOrNull() ?: ""

    val absUrl: String get() = "https://arxiv.org/abs/$id"

    /** Text the ranker sees. Title first so its terms carry into the tf weighting twice. */
    val rankText: String get() = "$title. $abstract"
}

/**
 * How the user reacted to a paper.
 *
 * [interest] is a continuous 0..1 rating rather than a star, so the user can say "somewhat"
 * instead of only yes or no. The buttons remain, as shortcuts to 0.9 and 0.1, because most
 * reactions really are binary and dragging a slider for each one would be tedious.
 *
 * [saved] is deliberately orthogonal and does not train anything. Starring and saving were
 * originally separate actions that did nearly the same job; collapsing the judgement into
 * [interest] leaves "save" to mean only "come back to this", which is a different intent
 * from "this is my kind of paper".
 */
data class Reaction(
    val interest: Float? = null,
    val saved: Boolean = false,
) {
    val rated: Boolean get() = interest != null

    companion object {
        const val LIKED = 0.9f
        const val DISLIKED = 0.1f
        val NONE = Reaction()
    }
}
