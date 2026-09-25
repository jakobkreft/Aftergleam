package si.jakobkreft.aftergleam.rank

import kotlin.math.ln
import kotlin.math.sqrt
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
     * Where a score sits among the scores it competes with, in standard deviations.
     *
     * The sampler used to read scores as probabilities and draw in proportion to them, which
     * only works when they spread over most of 0..1. They do not. On a real phone with twenty
     * two signals the model's predictions for the day's papers ran only from 0.26 to 0.40, and
     * shrinking them toward the prior left them within 0.07 of each other. Drawing in
     * proportion to numbers that close is close to drawing blind: of the twenty five papers
     * the model rated highest, two reached the digest, about what chance would manage.
     * Measured against their own spread, scores mean the same on the first day as on the
     * hundredth, and so does the temperature.
     */
    class Scale(values: Collection<Float>) {
        private val mean: Float
        private val sd: Float

        init {
            val m = if (values.isEmpty()) 0.0 else values.average()
            val v = if (values.isEmpty()) 0.0 else values.sumOf { (it - m) * (it - m) } / values.size
            mean = m.toFloat()
            sd = sqrt(v).toFloat()
        }

        /** Zero for every score when they are all equal, since then there is nothing to prefer. */
        fun of(score: Float): Float = if (sd < 1e-6f) 0f else (score - mean) / sd
    }

    /**
     * Gumbel top-k: the standard way to draw k items without replacement, favouring high
     * scores, in one pass.
     *
     * Adding Gumbel noise to each score divided by the temperature and taking the largest k
     * is exactly sampling k times without replacement from the softmax of those scores. The
     * scores are put on a common scale first, so a temperature means the same whatever they
     * are measured in: at the default, a paper one standard deviation above another is about
     * seventeen times as likely to be drawn. Low temperature approaches plain top-K, high
     * temperature approaches uniform.
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
        val scores = items.map(score)
        val scale = Scale(scores)
        return items.indices
            .map { items[it] to (scale.of(scores[it]) / t + gumbel(random)) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    /** A standard Gumbel draw: the noise that turns taking a maximum into sampling. */
    fun gumbel(random: Random): Float {
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
