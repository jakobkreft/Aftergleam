package si.jakobkreft.aftergleam.data

import kotlinx.coroutines.async

/**
 * Fetches a subscription from whichever servers it actually mentions.
 *
 * The app now reads seven servers. Nobody reads all seven: a computer scientist's categories
 * name arXiv and nothing else, and asking OSF and Crossref on their behalf would be minutes
 * of waiting for papers they did not ask for and will never see. So each server is consulted
 * only when the reader's own categories include one of its subjects, which falls out of
 * [Topics.categoriesOf] returning an empty set for the rest.
 *
 * Gathered here because three callers need it and they used to each keep their own list:
 * the digest rebuild, the nightly worker, and the across-the-archive fetch behind Explore.
 * A server added to one and forgotten in the others is a source that works in the morning
 * and not in the evening.
 */
object Fetcher {

    /** Servers in the order they are asked, arXiv first because most readers want it. */
    private val OSF = Source.OSF_SERVERS

    /**
     * @param label called with a human sentence before each server is asked
     * @param store called with each server's papers as they land, so the feed can fill in
     *   rather than waiting for the slowest server
     */
    /** What one run of [fetch] managed, so the caller can tell silence from failure. */
    data class Outcome(val stored: Int, val failed: List<String>) {
        val allFailed: Boolean get() = failed.isNotEmpty() && stored == 0
    }

    suspend fun fetch(
        subscribed: Set<String>,
        arxivMax: Int = 300,
        days: Long = 3,
        /**
         * How deep to page OSF, which is the slow one: about thirteen seconds a page.
         *
         * The daily fetch can afford four. The bridge, which is buying a single card from
         * outside the reader's fields, cannot, and passes one.
         */
        osfPages: Int = 4,
        label: (suspend (String) -> Unit)? = null,
        store: suspend (List<Paper>) -> Unit,
    ): Outcome {
        var stored = 0
        val failed = mutableListOf<String>()

        /**
         * Runs one server's fetch and records whether it answered.
         *
         * A server that times out returns an empty list, exactly like a server with nothing
         * new. Told apart here, because the screen that reports it says two very different
         * things: "nothing was announced today" is a fact about the archive, and a reader who
         * follows law was shown it after the Law Archive simply failed to answer.
         */
        suspend fun ask(server: String, block: suspend () -> List<Paper>) {
            label?.invoke("Fetching from ${Source.label(server)}")
            val result = runCatching { block() }
            val papers = result.getOrDefault(emptyList())
            if (result.isFailure) failed += Source.label(server)
            stored += papers.size
            store(papers)
        }

        val arxivCats = Topics.categoriesOf(Source.ARXIV, subscribed).toList()
        if (arxivCats.isNotEmpty()) {
            ask(Source.ARXIV) { ArxivApi.recent(arxivCats, max = arxivMax) }
        }

        // Each server is optional and independent: a server being down costs that server's
        // papers rather than the whole morning.
        for (server in listOf(Source.BIORXIV, Source.MEDRXIV)) {
            val subjects = Topics.categoriesOf(server, subscribed)
            if (subjects.isEmpty()) continue
            ask(server) { BioRxivApi.recent(server, subjects) }
        }

        for (server in OSF) {
            val subjects = Topics.categoriesOf(server, subscribed)
            if (subjects.isEmpty()) continue
            // OSF keeps its own window: see OsfApi.recent. The shared `days` is sized for
            // servers that post daily, and the Law Archive does not.
            ask(server) { OsfApi.recent(server, subjects, maxPages = osfPages) }
        }

        val chem = Topics.categoriesOf(Source.CHEMRXIV, subscribed)
        if (chem.isNotEmpty()) {
            ask(Source.CHEMRXIV) { ChemRxivApi.recent(chem, days = days) }
        }

        return Outcome(stored, failed)
    }

    /** Which servers a subscription would actually reach. Used by tests and diagnostics. */
    /**
     * The newest papers mentioning each keyword, from arXiv and, through Crossref, every other
     * server, whatever their category.
     *
     * Kept to papers whose title or abstract actually mentions the keyword, within the keyword
     * window. Both servers match more loosely than that: arXiv's search splits "H-Net" into
     * "h" and "net", and Crossref's relevance returns papers for any one of the words.
     *
     * @return the keywords that were fetched, so one that failed is asked again next time.
     */
    suspend fun fetchKeywords(
        keywords: List<String>,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        store: suspend (List<Paper>) -> Unit,
    ): List<String> {
        val since = today.minusDays(Keywords.WINDOW_DAYS)
        val done = mutableListOf<String>()
        // Crossref is asked for every keyword at once, alongside arXiv, which has to go one
        // request at a time. In turn, three took about a minute on a phone.
        val crossref = kotlinx.coroutines.coroutineScope {
            keywords.associateWith { k ->
                async { runCatching { CrossrefSearch.search(k, rows = 30, since = since) } }
            }.mapValues { it.value.await() }
        }
        for (keyword in keywords) {
            if (Keywords.parts(keyword).isEmpty()) continue
            val arxiv = runCatching { ArxivApi.recentMentioning(keyword) }
            val others = crossref.getValue(keyword)
            val found = (arxiv.getOrDefault(emptyList()) + others.getOrDefault(emptyList()))
                .filter { it.published >= since.toString() }
                .filter { Keywords.mentions(it.title + "\n" + it.abstract, keyword) }
            store(found)
            if (arxiv.isSuccess || others.isSuccess) done += keyword
        }
        return done
    }

    fun serversFor(subscribed: Set<String>): Set<String> =
        (listOf(Source.ARXIV, Source.BIORXIV, Source.MEDRXIV, Source.CHEMRXIV) + OSF)
            .filter { Topics.categoriesOf(it, subscribed).isNotEmpty() }
            .toSet()
}
