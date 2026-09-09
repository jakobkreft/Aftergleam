package si.jakobkreft.aftergleam.data

/**
 * One arXiv paper as the app needs it.
 *
 * [comments] and [journalRef] are not decoration: they carry the venue-acceptance signal
 * ("ICLR 2025 Oral") that replaces citation counts as the quality signal. Coverage runs
 * from ~9% at one month to ~30% at 6-12 months, and it costs no extra network call.
 */
/**
 * Where a paper came from.
 *
 * arXiv is not the whole of preprinting and never was. It has no chemistry archive, no
 * medicine, and its biology is quantitative biology rather than the wet-lab work that goes
 * to bioRxiv, so a cell biologist opening an arXiv reader finds almost nothing addressed to
 * them. Each server is its own identifier scheme and its own URL shape, and that is the only
 * thing the rest of the app needs to know about them: the ranker sees title and abstract,
 * which every one of them provides.
 */
object Source {
    const val ARXIV = "arxiv"
    const val BIORXIV = "biorxiv"
    const val MEDRXIV = "medrxiv"

    fun label(source: String): String = when (source) {
        BIORXIV -> "bioRxiv"
        MEDRXIV -> "medRxiv"
        else -> "arXiv"
    }

    /**
     * Categories are qualified by server, as in `biorxiv:cell biology`.
     *
     * bioRxiv has a "genomics" and arXiv has a `q-bio.GN`, and they are not the same feed:
     * one is wet-lab sequencing work and the other is quantitative modelling. Subscribing to
     * one must not silently subscribe to the other, and the topic bandit has to be able to
     * learn that a reader engages with one and ignores the other. arXiv categories are left
     * bare because they already carry an archive prefix of their own.
     */
    fun qualify(source: String, category: String): String =
        if (source == ARXIV) category else "$source:$category"

    fun display(category: String): String = category.substringAfter(':')

    fun of(category: String): String =
        if (':' in category) category.substringBefore(':') else ARXIV
}

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
    val source: String = Source.ARXIV,
) {
    val primaryCategory: String get() = categories.firstOrNull() ?: ""

    /** Without the server prefix, which is plumbing rather than something to read. */
    val displayCategories: List<String> get() = categories.map { Source.display(it) }

    /**
     * Title with LaTeX stripped, for display.
     *
     * arXiv titles are LaTeX source. Shown raw they read as
     * "Cylin-Painting: Seamless {360\textdegree} Panoramic Image", which looks like a bug
     * in the app rather than what it is. The ranker already strips this for its own
     * purposes; the reader deserves the same courtesy.
     */
    val displayTitle: String get() = cleanLatex(title)

    val displayAbstract: String get() = cleanLatex(abstract)

    /** "Park et al." rather than a bare surname, which reads as a fragment. */
    val shortAuthors: String
        get() {
            val first = authors.firstOrNull()?.trim().orEmpty()
            if (first.isBlank()) return ""
            val surname = first.substringAfterLast(' ').ifBlank { first }
            return if (authors.size > 1) "$surname et al." else surname
        }

    /**
     * The bioRxiv identifier carries its version, as in `10.64898/2026.09.01.747412v2`,
     * because the content URL needs it and a separate column for one integer that only
     * matters inside a URL would be a column to keep in step for nothing.
     */
    val absUrl: String get() = when (source) {
        Source.BIORXIV -> "https://www.biorxiv.org/content/$id"
        Source.MEDRXIV -> "https://www.medrxiv.org/content/$id"
        else -> "https://arxiv.org/abs/$id"
    }

    val pdfUrl: String get() = when (source) {
        Source.BIORXIV, Source.MEDRXIV -> "$absUrl.full.pdf"
        else -> "https://arxiv.org/pdf/$id"
    }

    /** Named only when it is not arXiv, so the common case carries no extra noise. */
    val sourceLabel: String? get() =
        if (source == Source.ARXIV) null else Source.label(source)

    /** Text the ranker sees. Title first so its terms carry into the tf weighting twice. */
    val rankText: String get() = "$title. $abstract"
}

// Raw strings take backslashes literally, so one `\\` here is the regex escape for a single
// backslash. Writing `\\\\` would match two of them and silently pass everything through.
private val LATEX_CMD = Regex("""\\(?:text|math)?[a-zA-Z]+\s*""")
private val LATEX_BRACES = Regex("""[{}$]""")

private fun cleanLatex(s: String): String =
    LATEX_BRACES.replace(LATEX_CMD.replace(s) { m ->
        // Keep a space so "360\textdegree Panoramic" does not become "360Panoramic".
        if (m.value.trimEnd().endsWith("degree")) "\u00b0" else " "
    }, "").replace(Regex("""\s+"""), " ").trim()

/**
 * The two flags that are not judgements: whether a paper is on the reader's shelf, and
 * whether they have opened it.
 *
 * Everything the model learns from lives in the signal ledger instead. This class used to
 * carry an `interest` rating as well, and keeping it after the ledger arrived was the whole
 * problem: onboarding, library import and backup restore all wrote judgements here, where
 * nothing read them, so a reader could answer twenty survey questions and teach the ranker
 * nothing. The field is gone so that it cannot happen again; the column survives in the
 * database only for the migration that reads it once.
 *
 * [saved] is deliberately orthogonal and does not train anything, so "save" means only
 * "come back to this", which is a different intent from "this is my kind of paper".
 */
data class Reaction(
    val saved: Boolean = false,
    /**
     * The paper's own screen has been opened.
     *
     * Recorded so the digest can mark what has already been looked at, and for nothing else.
     * It is explicitly not a weak positive: opening a paper and deciding against it is a
     * perfectly ordinary outcome, and treating it as approval is how implicit signals
     * quietly poison a model.
     */
    val viewed: Boolean = false,
) {
    val empty: Boolean get() = !saved && !viewed

    companion object {
        val NONE = Reaction()
    }
}
