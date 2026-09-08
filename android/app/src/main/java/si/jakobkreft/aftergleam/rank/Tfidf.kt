package si.jakobkreft.aftergleam.rank

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Sparse TF-IDF vectoriser, unigrams and bigrams, sublinear term frequency.
 *
 * There is deliberately no neural embedder here. Measured on a real 38-paper library with
 * leave-one-out, TF-IDF placed 21 of 24 held-out cs.CV papers in the top ten of 301
 * candidates (hit@10 0.875) against 19 of 24 for a quantised MiniLM. The difference is not
 * statistically resolved at that sample size, so the cheaper model wins: no model download,
 * no native libraries, and a vocabulary that can be shown to the user as plain words.
 */
class Tfidf(
    private val minDf: Int = 2,
    private val maxDfRatio: Float = 0.5f,
    private val maxFeatures: Int = 40_000,
) {
    /** term -> feature index */
    private val vocab = HashMap<String, Int>()
    private var idf = FloatArray(0)

    val size: Int get() = vocab.size

    fun fit(docs: List<String>) {
        val df = HashMap<String, Int>()
        for (d in docs) {
            for (t in terms(d).toHashSet()) df[t] = (df[t] ?: 0) + 1
        }
        val maxDf = (docs.size * maxDfRatio).toInt().coerceAtLeast(minDf)
        val kept = df.entries
            .filter { it.value >= minDf && it.value <= maxDf }
            .sortedByDescending { it.value }
            .take(maxFeatures)

        vocab.clear()
        idf = FloatArray(kept.size)
        kept.forEachIndexed { i, e ->
            vocab[e.key] = i
            // Smoothed idf, matching scikit-learn's default so prototype and app agree.
            idf[i] = (ln((1f + docs.size) / (1f + e.value)) + 1f)
        }
    }

    /** L2-normalised sparse vector: feature index -> weight. */
    fun transform(doc: String): Map<Int, Float> {
        val counts = HashMap<Int, Float>()
        for (t in terms(doc)) {
            val i = vocab[t] ?: continue
            counts[i] = (counts[i] ?: 0f) + 1f
        }
        if (counts.isEmpty()) return emptyMap()

        var norm = 0f
        val out = HashMap<Int, Float>(counts.size)
        for ((i, c) in counts) {
            val v = (1f + ln(c)) * idf[i]   // sublinear tf
            out[i] = v
            norm += v * v
        }
        norm = sqrt(norm)
        if (norm > 0f) for (k in out.keys.toList()) out[k] = out[k]!! / norm
        return out
    }

    /** Maps a feature index back to its term, for the "why" chip. */
    fun termAt(index: Int): String? = vocab.entries.firstOrNull { it.value == index }?.key

    companion object {
        // Small, deliberately conservative list. Aggressive stopword removal hurts here
        // because words like "not" and "without" carry method meaning in abstracts.
        private val STOP = setOf(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "is",
            "are", "was", "were", "be", "been", "by", "that", "this", "these", "those",
            "we", "our", "it", "its", "as", "at", "from", "which", "can", "such", "also",
            "have", "has", "had", "but", "they", "their", "than", "then", "thus", "here",
        )

        // arXiv abstracts are LaTeX source and routinely contain markup and project
        // URLs. Left in, they become high-weight features: a real digest produced the
        // explanation "matches textbf, reasoning, tasks", which is both embarrassing and
        // a sign the model is keying on formatting rather than content.
        private val URL_RE = Regex("https?://\\S+|\\bwww\\.\\S+|\\b[a-z0-9-]+\\.(com|org|io|net|github)\\b")
        private val LATEX_CMD_RE = Regex("\\\\[a-zA-Z]+\\*?")
        private val MATH_RE = Regex("\\$[^$]*\\$")

        fun clean(doc: String): String =
            doc.lowercase()
                .replace(URL_RE, " ")
                .replace(MATH_RE, " ")
                .replace(LATEX_CMD_RE, " ")

        fun terms(doc: String): List<String> {
            val words = Regex("[^a-z0-9]+").split(clean(doc))
                .filter { it.length > 2 && it !in STOP && !it.all { c -> c.isDigit() } }
            if (words.size < 2) return words
            val out = ArrayList<String>(words.size * 2)
            out.addAll(words)
            for (i in 0 until words.size - 1) out.add(words[i] + "_" + words[i + 1])
            return out
        }
    }
}
