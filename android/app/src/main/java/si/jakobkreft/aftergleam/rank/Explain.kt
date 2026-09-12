package si.jakobkreft.aftergleam.rank

/**
 * Why a card is in the digest, worked out from the papers the reader actually kept.
 *
 * The chip used to print the classifier's top-weighted features for the document. That is
 * the wrong quantity. The model is fitted on a few dozen positives against sampled negatives
 * in a vocabulary of thousands, so its weights are badly underdetermined: a word that
 * happens to appear in two liked abstracts and in none of the sampled negatives earns a
 * large positive weight whatever it means. When a paper genuinely matches, the real topic
 * words outweigh the noise and the chip reads well. When it does not, there is nothing real
 * to say and the chip prints the noise instead, which is how a digest came to explain itself
 * with "matches optimal, thereby, known".
 *
 * Measured on 32,584 arXiv abstracts, the obvious repairs do not work. Filtering by how
 * common a word is fails because the offenders are rare: "status" occurs in 0.3% of
 * abstracts and "principal" in 0.7%, against 6.1% for "diffusion" and 4.9% for
 * "transformer". Preferring two-word terms fails because abstract boilerplate is mostly
 * two-word: it yields "state art", "results demonstrate", "end end". Requiring a term to
 * recur across the reader's liked papers fails worst of all, because a reader with varied
 * taste has only generic language in common, so it actively selects for "role", "many",
 * "finally".
 *
 * What works is asking a different question. Instead of which features the classifier
 * weights, take the paper the reader kept that this one is most like, and name the words
 * that make the two alike. Those words are the overlap of two specific documents, so they
 * describe a subject rather than a quirk of the fit, and the same test that finds them also
 * says how strong the resemblance is. On the cases above it turns "transition, adding,
 * mutation" into "species, mutations, populations", and "i2p, autoencoder, one one" into
 * "sparse, autoencoder, diffusion".
 */
object Explain {

    /**
     * How much resemblance is needed before a card may claim to match anything.
     *
     * Calibrated against the distribution of best-overlap scores over 600 random candidates
     * for a real library: the median is 0.075 and the lower quartile 0.062, while cards that
     * actually reach the digest score 0.12 to 0.20. So this sits below anything the reader
     * will see and only fires on a card that arrived on venue or freshness alone, where the
     * honest thing to say is which category it is from rather than to invent a match.
     */
    const val MIN_OVERLAP = 0.06f

    /**
     * How interested the reader must be before a paper can be cited as a reason.
     *
     * At 0.6 this is saved, liked, downloaded, shared or actually read. A paper merely
     * opened scores 0.25 and one dwelled on 0.4, and "matches" pointing at something the
     * reader only glanced at would be the caption overstating the evidence again, in a
     * quieter way.
     */
    const val REFERENCE_INTEREST = 0.6f

    /** The words two papers share, strongest first, with how much they add up to. */
    data class Match(val terms: List<String>, val strength: Float)

    /** The vectors of papers the reader kept, which are what a match is measured against. */
    fun references(rated: List<RatedDoc>, vec: Tfidf): List<SparseVec> =
        rated.asSequence()
            .filter { it.interest >= REFERENCE_INTEREST }
            .map { vec.transform(it.text) }
            .filter { !it.isEmpty() }
            .toList()

    /**
     * The best resemblance between [candidate] and anything in [references].
     *
     * Returns null when nothing resembles it well enough to be worth claiming. Each pair is
     * one merge over two sorted index arrays, the same walk [SparseVec.dot] does, and it
     * runs only for the cards that reached the digest rather than for every candidate.
     */
    fun match(
        candidate: SparseVec,
        references: List<SparseVec>,
        vec: Tfidf,
        /**
         * How many shared terms to hand back, before the caption filters them.
         *
         * Well over the three that get shown. The filter drops the ones naming nothing, and
         * the caption also swaps a bare word for the phrase it came from where the list holds
         * one, which it can only do if the phrase is still in the list: a card read "law,
         * power" because "power law" fell outside a shorter pool.
         */
        candidates: Int = 20,
    ): Match? {
        if (candidate.isEmpty() || references.isEmpty()) return null

        var bestStrength = 0f
        var bestRef: SparseVec? = null
        for (ref in references) {
            val s = candidate.dot(ref)
            if (s > bestStrength) {
                bestStrength = s
                bestRef = ref
            }
        }
        val ref = bestRef ?: return null
        if (bestStrength < MIN_OVERLAP) return null

        // Every shared feature, ordered by how much it contributed to the resemblance.
        // The product is the term's share of the cosine, so this is a decomposition of the
        // similarity rather than a separate heuristic laid on top of it.
        val shared = ArrayList<Pair<Int, Float>>()
        var i = 0
        var j = 0
        while (i < candidate.size && j < ref.size) {
            val a = candidate.indices[i]
            val b = ref.indices[j]
            when {
                a == b -> {
                    shared += a to candidate.values[i] * ref.values[j]
                    i++
                    j++
                }
                a < b -> i++
                else -> j++
            }
        }
        shared.sortByDescending { it.second }
        return Match(
            shared.take(candidates).mapNotNull { vec.termAt(it.first) },
            bestStrength,
        )
    }
}
