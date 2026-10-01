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
 * [personalisation] runs 0 to 1: at 0 the results are in order of how well they match the
 * query, at 1 purely by predicted interest. Exposed as a single toggle, because two
 * independent weights would be a worse version of the same control.
 */
object SearchRanker {

    /**
     * @param matchRank and [interestRank] are this hit's standing among the results it came
     *   with, 0 for the last and 1 for the first.
     * @param personal whether there was a model to predict interest with at all.
     */
    data class Hit(
        val paper: Paper,
        val queryMatch: Float,
        val interest: Float,
        val matchRank: Float = 0f,
        val interestRank: Float = 0f,
        val personal: Boolean = true,
    ) {
        /**
         * Relative to the other results, because that is all a model trained on a few dozen
         * papers can honestly say. It used to compare the raw prediction with fixed cut-offs
         * of 0.6 and 0.35, which a model of this size almost never reaches, so nearly every
         * hit was "outside your usual reading"; and a reader with no history at all got that
         * on every hit, about reading they had not done yet.
         */
        fun why(): String = when {
            !personal -> "matches your query"
            interestRank >= 2f / 3f -> "matches your query and your interests"
            interestRank <= 1f / 3f -> "matches your query, further from your usual reading"
            else -> "matches your query"
        }
    }

    /**
     * Re-orders hits that have already been scored.
     *
     * Both components are computed once, at search time, so changing the balance between
     * them is a comparison and nothing more. Re-running [rank] here would refit the
     * vectoriser and retrain the classifier on every pixel of a slider drag, which is a
     * few hundred milliseconds of work repeated dozens of times a second.
     *
     * The two are blended as ranks, not raw scores. Query match is a cosine spread over most
     * of 0..0.5 while predicted interest sits within a few hundredths of 0.3, so blending the
     * raw numbers let the query decide almost every position whatever the slider said.
     */
    fun reorder(hits: List<Hit>, personalisation: Float): List<Hit> =
        hits.sortedByDescending {
            (1f - personalisation) * it.matchRank + personalisation * it.interestRank
        }

    /**
     * @param model the interest model, normally the one the digest already trained.
     *
     * Search used to fit its own vectoriser and train its own classifier on every query,
     * which is the same expensive work the digest does and the reason searching a hundred
     * cached papers took as long as searching the whole of arXiv. The network was never the
     * bottleneck. The model depends only on what the reader has reacted to, so there is no
     * reason for search to have a different one.
     */
    fun rank(
        results: List<Paper>,
        query: String,
        rated: List<RatedDoc>,
        personalisation: Float = 0.5f,
        negativePool: List<String> = emptyList(),
        model: Ranker.Model? = null,
    ): List<Hit> {
        if (results.isEmpty()) return emptyList()

        // Fitted with no upper document-frequency cutoff, which matters here.
        //
        // The default vectoriser drops terms appearing in more than half the documents,
        // which is right for a corpus and exactly wrong for a result set: these documents
        // were returned *because* they match the query, so the query's own words are in most
        // of them and were being filtered out. The query vector came out empty and every
        // result scored a query match of zero, which made the personalisation slider useless
        // since one side of it was always nothing.
        val queryVec = Tfidf(minDf = 1, maxDfRatio = 1f)
            .apply { fit(results.map { it.rankText } + listOf(query)) }
        val qv = queryVec.transform(query)

        val interestModel = model
            ?: trainInterest(rated, negativePool.ifEmpty { results.map { it.rankText } })

        val raw = results.map { paper ->
            val match = cosine(qv, queryVec.transform(paper.rankText))
            val interest = interestModel
                ?.let { it.clf.predict(it.vec.transform(paper.rankText)) } ?: 0f
            Hit(paper, match, interest, personal = interestModel != null)
        }
        val matchRanks = ranks(raw.map { it.queryMatch })
        val interestRanks = ranks(raw.map { it.interest })
        return raw.mapIndexed { i, h ->
            h.copy(matchRank = matchRanks[i], interestRank = interestRanks[i])
        }.let { reorder(it, personalisation) }
    }

    /** Each value's standing among the others, 0 for the lowest and 1 for the highest. Ties share. */
    internal fun ranks(values: List<Float>): FloatArray {
        val n = values.size
        val out = FloatArray(n)
        if (n < 2) return out.also { it.fill(0.5f) }
        val order = values.indices.sortedBy { values[it] }
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) j++
            val r = ((i + j) / 2f) / (n - 1)
            for (k in i..j) out[order[k]] = r
            i = j + 1
        }
        return out
    }

    /** Fallback for the rare case where no model has been trained yet this session. */
    private fun trainInterest(
        rated: List<RatedDoc>,
        negatives: List<String>,
    ): Ranker.Model? {
        if (rated.size < Ranker.MIN_RATINGS) return null
        val docs = rated.map { it.text } + negatives.shuffled().take(rated.size * 10)
        val vec = Tfidf().apply { fit(docs) }
        if (vec.size == 0) return null
        val y = FloatArray(docs.size) { i -> if (i < rated.size) rated[i].interest else 0f }
        val clf = LogReg(vec.size).apply { fit(docs.map { vec.transform(it) }, y) }
        return Ranker.Model(vec, clf)
    }

    // Both vectors are L2 normalised by Tfidf.transform, so the dot product is the cosine.
    private fun cosine(a: SparseVec, b: SparseVec): Float =
        if (a.isEmpty() || b.isEmpty()) 0f else a.dot(b)
}
