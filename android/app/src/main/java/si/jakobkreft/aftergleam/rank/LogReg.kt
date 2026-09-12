package si.jakobkreft.aftergleam.rank

import kotlin.math.exp

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
    fun fit(x: List<SparseVec>, labels: FloatArray) {
        require(x.size == labels.size) { "x and labels differ in length" }
        if (x.isEmpty()) return

        val posMass = labels.sum().coerceAtLeast(1e-3f)
        val negMass = (labels.size - posMass).coerceAtLeast(1e-3f)
        val wPos = labels.size / (2f * posMass)
        val wNeg = labels.size / (2f * negMass)

        weights = FloatArray(dim)
        bias = 0f

        // One dense gradient buffer, reused across epochs.
        //
        // The previous version allocated a HashMap<Int, Float> per epoch and boxed every
        // index and value it touched. Training walks every feature of every document once
        // per epoch, so at two hundred epochs over eight hundred documents that was tens of
        // millions of boxed operations, and it was where the ranking pass spent most of its
        // time. The stamp array avoids clearing the whole vocabulary each epoch when only a
        // fraction of it appears in the batch.
        val grad = FloatArray(dim)
        val touched = IntArray(dim)
        var stamp = 0

        repeat(epochs) {
            stamp++
            var gBias = 0f
            for (i in x.indices) {
                val v = x[i]
                val p = predict(v)
                // Blend the two class weights by the label itself, so a 0.5 counts halfway
                // rather than being forced into one class.
                val w = labels[i] * wPos + (1f - labels[i]) * wNeg
                val err = (p - labels[i]) * w
                for (k in v.indices.indices) {
                    val idx = v.indices[k]
                    if (touched[idx] != stamp) {
                        touched[idx] = stamp
                        grad[idx] = 0f
                    }
                    grad[idx] += err * v.values[k]
                }
                gBias += err
            }
            val scale = lr / x.size
            for (i in 0 until dim) {
                if (touched[i] == stamp) weights[i] -= scale * grad[i] + lr * l2 * weights[i]
            }
            bias -= scale * gBias
        }
    }

    fun predict(v: SparseVec): Float = 1f / (1f + exp(-(bias + v.dot(weights))))

}
