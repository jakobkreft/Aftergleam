package si.jakobkreft.aftergleam.data

import si.jakobkreft.aftergleam.rank.Tfidf

/**
 * What the user's reading has been drifting towards, and where the model got it wrong.
 *
 * Cheap by construction: term counts and category shares over data already on disk, no
 * network and no new model. The value is not the arithmetic, it is that the app says
 * something true about the reader back to them, including the part where it was wrong. An
 * app that only ever reports success is one you stop believing.
 */
object Drift {

    data class Report(
        val rising: List<String>,
        val falling: List<String>,
        val recurringAuthors: List<String>,
        /** How many exploration cards the user actually rated, either way. */
        val explorationJudged: Int,
        val explorationLiked: Int,
        val recentCount: Int,
        val earlierCount: Int,
    ) {
        /** True when there is too little history for any of this to mean anything. */
        val thin: Boolean get() = recentCount < MIN_FOR_DRIFT || earlierCount < MIN_FOR_DRIFT

        /**
         * The honest line about exploration. Reported even when it is unflattering, which
         * is the whole point of including it.
         */
        fun explorationNote(): String? = when {
            explorationJudged < 5 -> null
            explorationLiked == 0 ->
                "None of the $explorationJudged exploration cards you rated landed. The " +
                    "model is probing in the wrong direction."
            explorationLiked * 4 < explorationJudged ->
                "$explorationLiked of $explorationJudged exploration cards you rated " +
                    "landed. Still mostly missing."
            else ->
                "$explorationLiked of $explorationJudged exploration cards you rated landed."
        }
    }

    private const val MIN_FOR_DRIFT = 5
    private const val TOP_N = 3

    /**
     * @param recent papers rated in the most recent window
     * @param earlier papers rated in the window before it
     */
    fun compute(
        recent: List<Pair<Paper, Float>>,
        earlier: List<Pair<Paper, Float>>,
        explorationJudged: Int,
        explorationLiked: Int,
    ): Report {
        val recentTerms = weightedTerms(recent)
        val earlierTerms = weightedTerms(earlier)

        // Compare shares rather than counts, so a fortnight when the user simply read more
        // does not register as every topic rising at once.
        val recentTotal = recentTerms.values.sum().coerceAtLeast(1f)
        val earlierTotal = earlierTerms.values.sum().coerceAtLeast(1f)

        val deltas = (recentTerms.keys + earlierTerms.keys).associateWith { term ->
            (recentTerms[term] ?: 0f) / recentTotal - (earlierTerms[term] ?: 0f) / earlierTotal
        }

        return Report(
            rising = deltas.entries.sortedByDescending { it.value }
                .take(TOP_N).filter { it.value > 0f }.map { pretty(it.key) },
            falling = deltas.entries.sortedBy { it.value }
                .take(TOP_N).filter { it.value < 0f }.map { pretty(it.key) },
            recurringAuthors = recurringAuthors(recent + earlier),
            explorationJudged = explorationJudged,
            explorationLiked = explorationLiked,
            recentCount = recent.size,
            earlierCount = earlier.size,
        )
    }

    /**
     * Terms weighted by how much the user liked the paper they came from, so a lukewarm 0.6
     * moves the picture less than an emphatic 0.95. Only unigrams: bigrams are better for
     * ranking but read badly in a sentence about someone's interests.
     */
    private fun weightedTerms(rated: List<Pair<Paper, Float>>): Map<String, Float> {
        val out = HashMap<String, Float>()
        for ((paper, interest) in rated) {
            if (interest < 0.5f) continue
            val terms = Tfidf.terms(paper.rankText).filter { !it.contains('_') }.toSet()
            for (t in terms) out[t] = (out[t] ?: 0f) + interest
        }
        // A term seen once is noise, not a trend.
        return out.filterValues { it >= 1.5f }
    }

    private fun recurringAuthors(rated: List<Pair<Paper, Float>>): List<String> {
        val counts = HashMap<String, Int>()
        for ((paper, interest) in rated) {
            if (interest < 0.5f) continue
            for (a in paper.authors.map { it.trim() }.filter { it.isNotBlank() }.toSet()) {
                counts[a] = (counts[a] ?: 0) + 1
            }
        }
        return counts.entries.filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(TOP_N)
            .map { "${it.key} (${it.value})" }
    }

    private fun pretty(term: String) = term.replace('_', ' ')
}
