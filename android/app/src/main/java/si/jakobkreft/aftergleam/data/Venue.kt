package si.jakobkreft.aftergleam.data

/**
 * Extracts conference or journal acceptance from arXiv's free-text comments field.
 *
 * This is the app's quality signal. OpenAlex cannot supply one for arXiv preprints: a
 * 120-paper cs.LG sample from March 2025 showed 91% with zero citations and a maximum of
 * three, because preprint records carry no citation edges and the ML venues are largely
 * absent from the underlying graph. Authors, on the other hand, edit the comments field
 * when a paper is accepted, which is both free and reliable. Coverage rises with age,
 * from about 9% in the first month to 30% at six to twelve months.
 */
object Venue {

    private val KNOWN = listOf(
        "NeurIPS", "NIPS", "ICML", "ICLR", "CVPR", "ICCV", "ECCV", "ACL", "EMNLP",
        "NAACL", "AAAI", "IJCAI", "KDD", "SIGIR", "WWW", "WACV", "BMVC", "COLING",
        "COLM", "TMLR", "JMLR", "TPAMI", "MICCAI", "INTERSPEECH", "ICASSP",
        "SIGGRAPH", "UAI", "AISTATS", "CoRL", "ICRA", "IROS", "MIDL", "ACCV", "ICME",
        "ECML", "PKDD", "RSS",
    )

    private val VENUE_RE = Regex(
        "\\b(" + KNOWN.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE
    )

    /** Distinguishes "accepted to X" from a bare mention such as "we compare against X". */
    private val ACCEPT_RE = Regex(
        "\\b(accepted|to appear|camera[- ]ready|oral|spotlight|poster|proceedings)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * A workshop acceptance is a much weaker signal than the main track, and 11% of the
     * papers carrying a venue on a sampled day were workshops. Scoring them identically
     * would let "extended abstract, non-archival" outrank a main-conference paper.
     */
    private val WORKSHOP_RE = Regex("\\bworkshop\\b", RegexOption.IGNORE_CASE)

    private val YEAR_RE = Regex("\\b(20\\d{2})\\b")

    fun isWorkshop(paper: Paper) = WORKSHOP_RE.containsMatchIn(paper.comments)

    /** A short label for the card, e.g. "ECCV 2026" or "ECCV 2026 workshop". */
    fun of(paper: Paper): String? {
        val match = VENUE_RE.find(paper.comments)
        if (match != null) {
            val year = YEAR_RE.find(paper.comments)?.value
            val base = match.value.uppercase() + if (year != null) " $year" else ""
            return if (isWorkshop(paper)) "$base workshop" else base
        }
        if (paper.journalRef.isNotBlank()) return paper.journalRef.take(40)
        return null
    }

    /**
     * Quality contribution in 0..1. A named venue with acceptance language scores highest;
     * a journal reference is nearly as good; a bare venue mention is weaker because
     * "we beat the CVPR baseline" is not an acceptance.
     */
    fun score(paper: Paper): Float {
        val named = VENUE_RE.containsMatchIn(paper.comments)
        val accepted = ACCEPT_RE.containsMatchIn(paper.comments)
        val base = when {
            named && accepted -> 1.0f
            paper.journalRef.isNotBlank() -> 0.9f
            named -> 0.6f
            accepted -> 0.4f
            else -> 0.0f
        }
        return if (isWorkshop(paper)) base * 0.5f else base
    }
}
