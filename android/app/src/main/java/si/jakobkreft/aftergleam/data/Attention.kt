package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the field is paying attention to right now, from Hugging Face's daily papers list.
 *
 * This exists because the obvious version of the feature is impossible. Citations would be
 * the principled signal, but OpenAlex carries no citation edges for arXiv preprints: a
 * 120-paper cs.LG sample from March 2025 showed 91% with zero citations eighteen months on,
 * and NeurIPS appears in the graph with 169 works in total. Venue acceptance answers "what
 * mattered" months later. Nothing in that set answers "what is everyone reading today".
 *
 * **Why this does not break the privacy promise.** The objection to citation lookups was
 * never the network call, it was that asking a server "what happened to these forty papers
 * I skipped" transmits the user's reading history. This downloads the same public list
 * everybody else downloads and joins it locally, so it discloses nothing about the user.
 *
 * Treated as enrichment that degrades to nothing. The endpoint is undocumented, the list is
 * small and skewed towards language models, and it may disappear without notice, so a
 * failure here must never affect the digest.
 */
object Attention {

    private const val ENDPOINT = "https://huggingface.co/api/daily_papers"
    private const val UA = "Aftergleam/0.1 (+https://github.com/jakobkreft/aftergleam)"

    /** arXiv id to upvote count. Empty on any failure, by design. */
    suspend fun fetch(limit: Int = 100): Map<String, Int> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$ENDPOINT?limit=$limit").openConnection() as HttpURLConnection)
                .apply {
                    setRequestProperty("User-Agent", UA)
                    connectTimeout = 15_000
                    readTimeout = 20_000
                }
            val body = try {
                if (conn.responseCode != 200) return@withContext emptyMap()
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }

            val arr = JSONArray(body)
            buildMap {
                for (i in 0 until arr.length()) {
                    val paper = arr.optJSONObject(i)?.optJSONObject("paper") ?: continue
                    val id = paper.optString("id").ifBlank { continue }
                    put(id, paper.optInt("upvotes", 0))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * A 0..1 contribution from upvote count.
     *
     * Log-scaled and capped: the list spans roughly 3 to 300 upvotes and a linear scale
     * would let one viral paper dominate a whole digest. Being on the list at all is most
     * of the signal, and the exact number is worth much less than that.
     */
    fun score(upvotes: Int): Float {
        if (upvotes <= 0) return 0f
        val scaled = kotlin.math.ln(1.0 + upvotes) / kotlin.math.ln(101.0)
        return scaled.coerceIn(0.0, 1.0).toFloat()
    }

    fun label(upvotes: Int): String = when {
        upvotes >= 50 -> "widely read today, $upvotes upvotes"
        upvotes > 0 -> "on Hugging Face daily papers"
        else -> ""
    }
}
