package si.jakobkreft.aftergleam.rank

/**
 * A sparse document vector as two primitive arrays.
 *
 * This replaces `Map<Int, Float>`, which was costing the app most of its ranking time.
 * Kotlin boxes both the key and the value in such a map, so training, which walks every
 * feature of every document once per epoch, was doing tens of millions of boxed lookups and
 * allocating a fresh gradient map for each of two hundred epochs. Indices are kept sorted so
 * two vectors can be intersected by a merge rather than by hashing.
 */
class SparseVec(val indices: IntArray, val values: FloatArray) {

    val size: Int get() = indices.size
    fun isEmpty() = indices.isEmpty()

    /** Dot product by merging two sorted index lists. */
    fun dot(other: SparseVec): Float {
        var i = 0
        var j = 0
        var sum = 0f
        while (i < indices.size && j < other.indices.size) {
            val a = indices[i]
            val b = other.indices[j]
            when {
                a == b -> { sum += values[i] * other.values[j]; i++; j++ }
                a < b -> i++
                else -> j++
            }
        }
        return sum
    }

    /** Dot with a dense weight vector, the inner loop of both training and scoring. */
    fun dot(dense: FloatArray): Float {
        var sum = 0f
        for (k in indices.indices) sum += dense[indices[k]] * values[k]
        return sum
    }

    companion object {
        val EMPTY = SparseVec(IntArray(0), FloatArray(0))

        /** Builds a vector from an index-to-weight map, sorting the indices. */
        fun of(entries: Map<Int, Float>): SparseVec {
            if (entries.isEmpty()) return EMPTY
            val idx = entries.keys.toIntArray()
            idx.sort()
            val vals = FloatArray(idx.size) { entries[idx[it]] ?: 0f }
            return SparseVec(idx, vals)
        }
    }
}
