package si.jakobkreft.aftergleam.rank

import si.jakobkreft.aftergleam.data.Paper

/**
 * Re-ranks arXiv's keyword results against the user's model.
 *
 * D11's two-tier design. A local vector index over three million papers needs a server, so
 * tier one is arXiv's keyword search and tier two is scoring the hundred results it returns
 * against what the user actually reads. That recovers most of the value of personalised
 * search for the cost of one request.
 *
 * [personalisation] runs 0 to 1: at 0 the results are arXiv's own order by relevance to the
 * query, at 1 they are ordered purely by predicted interest. Exposed as a single toggle,
 * because two independent weights would be a worse version of the same control.
 */
object SearchRanker {

    data class Hit(val paper: Paper, val queryMatch: Float, val interest: Float) {
        fun why(): String = when {
            interest > 0.6f -> "matches your query and your interests"
            interest > 0.35f -> "matches your query"
            else -> "matches your query, outside your usual reading"
        }
    }

    fun rank(
        results: List<Paper>,
        query: String,
        rated: List<RatedDoc>,
        personalisation: Float = 0.5f,
        negativePool: List<String> = emptyList(),
    ): List<Hit> {
        if (results.isEmpty()) return emptyList()

        // Query similarity uses a vocabulary fitted on the results themselves, so a rare
        // query term is correctly treated as informative within this result set.
        val queryVec = Tfidf(minDf = 1).apply { fit(results.map { it.rankText } + listOf(query)) }
        val qv = queryVec.transform(query)

        val model = trainInterest(rated, negativePool.ifEmpty { results.map { it.rankText } })

        return results.map { paper ->
            val match = cosine(qv, queryVec.transform(paper.rankText))
            val interest = model?.let { (vec, clf) -> clf.predict(vec.transform(paper.rankText)) } ?: 0f
            Hit(paper, match, interest)
        }.sortedByDescending {
            (1f - personalisation) * it.queryMatch + personalisation * it.interest
        }
    }

    private fun trainInterest(
        rated: List<RatedDoc>,
        negatives: List<String>,
    ): Pair<Tfidf, LogReg>? {
        if (rated.size < Ranker.MIN_RATINGS) return null
        val docs = rated.map { it.text } + negatives.shuffled().take(rated.size * 10)
        val vec = Tfidf().apply { fit(docs) }
        if (vec.size == 0) return null
        val y = FloatArray(docs.size) { i -> if (i < rated.size) rated[i].interest else 0f }
        val clf = LogReg(vec.size).apply { fit(docs.map { vec.transform(it) }, y) }
        return vec to clf
    }

    private fun cosine(a: Map<Int, Float>, b: Map<Int, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val (small, large) = if (a.size < b.size) a to b else b to a
        var dot = 0f
        for ((i, v) in small) large[i]?.let { dot += v * it }
        return dot
    }
}
