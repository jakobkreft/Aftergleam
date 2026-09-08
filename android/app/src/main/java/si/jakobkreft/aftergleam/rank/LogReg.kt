package si.jakobkreft.aftergleam.rank

import kotlin.math.exp
import kotlin.math.abs

/**
 * Logistic regression over sparse vectors, trained by gradient descent with L2.
 *
 * Small enough to retrain from scratch every time the user stars something, which is the
 * point: the model is never stale and there is no incremental-update bug surface. On a
 * few hundred documents this is milliseconds.
 */
class LogReg(
    private val dim: Int,
    private val lr: Float = 0.5f,
    private val l2: Float = 1e-4f,
    private val epochs: Int = 200,
) {
    var weights = FloatArray(dim); private set
    private var bias = 0f

    /**
     * [labels] are continuous in 0..1, not just 0 or 1: the user rates how interesting a
     * paper is rather than only starring it, and cross-entropy handles soft targets
     * directly.
     *
     * Weighting uses the total positive *mass* rather than a count, so a handful of
     * lukewarm 0.6 ratings does not get treated as the same evidence as the same number of
     * emphatic 0.95s. Without any weighting the sampled negatives swamp the ratings and
     * the model simply learns to predict "no".
     */
    fun fit(x: List<Map<Int, Float>>, labels: FloatArray) {
        require(x.size == labels.size) { "x and labels differ in length" }
        if (x.isEmpty()) return

        val posMass = labels.sum().coerceAtLeast(1e-3f)
        val negMass = (labels.size - posMass).coerceAtLeast(1e-3f)
        val wPos = labels.size / (2f * posMass)
        val wNeg = labels.size / (2f * negMass)

        weights = FloatArray(dim)
        bias = 0f

        repeat(epochs) {
            val grad = HashMap<Int, Float>()
            var gBias = 0f
            for (i in x.indices) {
                val p = predict(x[i])
                // Blend the two weights by the label itself, so a 0.5 rating is
                // weighted halfway rather than being forced into one class.
                val w = labels[i] * wPos + (1f - labels[i]) * wNeg
                val err = (p - labels[i]) * w
                for ((idx, v) in x[i]) grad[idx] = (grad[idx] ?: 0f) + err * v
                gBias += err
            }
            val scale = lr / x.size
            for ((idx, g) in grad) {
                weights[idx] -= scale * g + lr * l2 * weights[idx]
            }
            bias -= scale * gBias
        }
    }

    fun predict(v: Map<Int, Float>): Float {
        var z = bias
        for ((i, x) in v) z += weights[i] * x
        return 1f / (1f + exp(-z))
    }

    /**
     * The features that pushed this document's score up the most, for the "why" chip.
     * P3 says the model must be legible, and with TF-IDF that is literally free: the
     * explanation is a list of words the user can read.
     */
    fun topContributors(v: Map<Int, Float>, n: Int = 3): List<Int> =
        v.entries
            .map { it.key to weights[it.key] * it.value }
            .filter { it.second > 0f }
            .sortedByDescending { abs(it.second) }
            .take(n)
            .map { it.first }
}
