package si.jakobkreft.aftergleam.rank

import kotlin.math.ln
import kotlin.random.Random

/**
 * Drawing a digest instead of taking the top of a list.
 *
 * Deterministic top-K is why the feed collapses. The model learns a narrow region, the top of
 * the list only ever comes from the middle of it, the reader engages with what they are
 * shown, and the region tightens. Sampling from the whole score distribution keeps the tail
 * reachable, and it costs nothing.
 *
 * It also makes two features possible that were not before: re-ranking with no new data
 * returns a genuinely different digest, so "show me more" means something, and refreshing
 * feels alive without touching the network.
 */
object Sampling {

    /**
     * Gumbel top-k: the standard way to draw k items without replacement, in proportion to
     * their weights, in one pass and without normalising.
     *
     * Adding Gumbel noise to each log-score and taking the largest k is exactly equivalent to
     * sampling k times without replacement from the softmax. Temperature scales the scores
     * before the noise: low temperature approaches plain top-K, high temperature approaches
     * uniform.
     */
    fun <T> topK(
        items: List<T>,
        k: Int,
        temperature: Float = 0.35f,
        random: Random = Random.Default,
        score: (T) -> Float,
    ): List<T> {
        if (k <= 0 || items.isEmpty()) return emptyList()
        if (k >= items.size) return items
        val t = temperature.coerceAtLeast(1e-3f)
        return items
            .map { item ->
                // Scores are probabilities in 0..1; the floor keeps log finite.
                val s = score(item).coerceIn(1e-6f, 1f)
                item to (ln(s) / t + gumbel(random))
            }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private fun gumbel(random: Random): Float {
        val u = random.nextFloat().coerceIn(1e-6f, 1f - 1e-6f)
        return -ln(-ln(u))
    }

    /**
     * Pulls a prediction toward the prior when there is little evidence behind it.
     *
     * A logistic regression fitted on six papers is as sharp as one fitted on six hundred,
     * which is how the feed narrowed within a day. The weight on the model rises with the
     * number of papers the reader has actually given a signal for: about a fifth at six
     * signals, four fifths at a hundred.
     */
    fun shrink(prediction: Float, evidenceCount: Int, prior: Float = 0.3f, k: Float = 25f): Float {
        val w = evidenceCount / (evidenceCount + k)
        return (w * prediction + (1f - w) * prior).coerceIn(0f, 1f)
    }
}
