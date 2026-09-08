package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a BibTeX or RIS library into rated papers, seeding the model on day one.
 *
 * D10 calls this the highest-leverage onboarding path and the measurement agrees: it turns
 * "accurate in three weeks" into "accurate immediately". Measured on a real library, 38 of
 * 52 research entries resolved with no false matches.
 *
 * Entries that resolve to nothing are counted and reported, never silently dropped. The
 * prototype's first run reported 22 papers as unmatched that in fact resolved perfectly; the
 * requests had failed and `catch { null }` had turned a network error into a product claim.
 * That is why [Result.failed] is distinct from [Result.unmatched] here.
 */
object LibraryImport {

    data class Progress(val done: Int, val total: Int, val matched: Int)

    data class Result(
        val papers: List<Paper>,
        val total: Int,
        val unmatched: Int,
        val failed: Int,
    )

    private const val MATCH_THRESHOLD = 0.6f

    suspend fun run(
        text: String,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val entries = BibTeX.parse(text)
        if (entries.isEmpty()) return@withContext Result(emptyList(), 0, 0, 0)

        val found = LinkedHashMap<String, Paper>()
        var failed = 0
        var done = 0

        // Cheap path first: anything with an explicit identifier resolves in one request
        // per hundred, no rate limiting worth speaking of.
        val withIds = entries.filter { it.arxivId.isNotBlank() }
        if (withIds.isNotEmpty()) {
            runCatching { ArxivApi.byIds(withIds.map { it.arxivId }) }
                .onSuccess { papers -> papers.forEach { found[it.id] = it } }
                .onFailure { failed += withIds.size }
            done += withIds.size
            onProgress(Progress(done, entries.size, found.size))
        }

        // Expensive path: title search, one request every three seconds. A hand-written
        // .bib may have no identifiers at all, in which case this is the only path there is.
        val needTitle = entries.filter { it.arxivId.isBlank() && it.title.isNotBlank() }
        for (entry in needTitle) {
            val outcome = runCatching { ArxivApi.searchTitle(entry.title) }
            if (outcome.isFailure) {
                failed++
            } else {
                outcome.getOrNull()
                    ?.maxByOrNull { BibTeX.similarity(BibTeX.cleanTitle(entry.title), it.title) }
                    ?.takeIf {
                        BibTeX.similarity(BibTeX.cleanTitle(entry.title), it.title) >= MATCH_THRESHOLD
                    }
                    ?.let { found[it.id] = it }
            }
            done++
            onProgress(Progress(done, entries.size, found.size))
            Thread.sleep(ArxivApi.SLEEP_MS)
        }

        Result(
            papers = found.values.toList(),
            total = entries.size,
            unmatched = entries.size - found.size - failed,
            failed = failed,
        )
    }
}
