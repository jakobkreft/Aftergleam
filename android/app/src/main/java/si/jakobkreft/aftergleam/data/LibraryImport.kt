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

    /** Which part of the work is running, so the screen can say something true about it. */
    enum class Stage { READING, IDENTIFIERS, TITLES, DONE }

    /**
     * Enough detail for a screen, not just a bar.
     *
     * [current] is the title being resolved at this moment. A progress bar on a job that
     * takes ten minutes looks the same whether it is working or wedged; a title that keeps
     * changing is the one piece of evidence that it is alive, and it costs nothing because
     * the entry is in hand anyway.
     */
    data class Progress(
        val done: Int,
        val total: Int,
        val matched: Int,
        val stage: Stage = Stage.TITLES,
        val current: String = "",
    )

    data class Result(
        val papers: List<Paper>,
        val total: Int,
        val unmatched: Int,
        val failed: Int,
        /** True when the reader stopped it early, so the counts describe a partial run. */
        val stopped: Boolean = false,
    )

    private const val MATCH_THRESHOLD = 0.6f

    /**
     * @param shouldStop polled between requests. Stopping keeps everything matched so far
     *   rather than throwing the work away, because ten minutes of lookups are worth more
     *   than the tidiness of an all-or-nothing result.
     */
    suspend fun run(
        text: String,
        shouldStop: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        onProgress(Progress(0, 0, 0, Stage.READING))
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
            onProgress(Progress(done, entries.size, found.size, Stage.IDENTIFIERS))
        }

        // Expensive path: title search, one request every three seconds. A hand-written
        // .bib may have no identifiers at all, in which case this is the only path there is.
        val needTitle = entries.filter { it.arxivId.isBlank() && it.title.isNotBlank() }
        var stopped = false
        for (entry in needTitle) {
            if (shouldStop()) {
                stopped = true
                break
            }
            onProgress(
                Progress(done, entries.size, found.size, Stage.TITLES, BibTeX.cleanTitle(entry.title))
            )
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
            onProgress(Progress(done, entries.size, found.size, Stage.TITLES))
        }

        onProgress(Progress(done, entries.size, found.size, Stage.DONE))
        Result(
            papers = found.values.toList(),
            // A stopped run describes what it looked at, not what the file held, or every
            // entry it never reached would be reported as an entry arXiv does not have.
            total = if (stopped) done else entries.size,
            unmatched = (if (stopped) done else entries.size) - found.size - failed,
            failed = failed,
            stopped = stopped,
        )
    }
}
