package si.jakobkreft.aftergleam.data

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
    ) {
        val arxivCats = Topics.categoriesOf(Source.ARXIV, subscribed).toList()
        if (arxivCats.isNotEmpty()) {
            label?.invoke("Fetching from arXiv")
            store(runCatching { ArxivApi.recent(arxivCats, max = arxivMax) }.getOrDefault(emptyList()))
        }

        // Each server is optional and independent: a server being down costs that server's
        // papers rather than the whole morning.
        for (server in listOf(Source.BIORXIV, Source.MEDRXIV)) {
            val subjects = Topics.categoriesOf(server, subscribed)
            if (subjects.isEmpty()) continue
            label?.invoke("Fetching from ${Source.label(server)}")
            store(runCatching { BioRxivApi.recent(server, subjects) }.getOrDefault(emptyList()))
        }

        for (server in OSF) {
            val subjects = Topics.categoriesOf(server, subscribed)
            if (subjects.isEmpty()) continue
            label?.invoke("Fetching from ${Source.label(server)}")
            store(
                // OSF keeps its own window: see OsfApi.recent. The shared `days` is sized
                // for servers that post daily, and the Law Archive does not.
                runCatching { OsfApi.recent(server, subjects, maxPages = osfPages) }
                    .getOrDefault(emptyList())
            )
        }

        val chem = Topics.categoriesOf(Source.CHEMRXIV, subscribed)
        if (chem.isNotEmpty()) {
            label?.invoke("Fetching from ChemRxiv")
            store(runCatching { ChemRxivApi.recent(chem, days = days) }.getOrDefault(emptyList()))
        }
    }

    /** Which servers a subscription would actually reach. Used by tests and diagnostics. */
    fun serversFor(subscribed: Set<String>): Set<String> =
        (listOf(Source.ARXIV, Source.BIORXIV, Source.MEDRXIV, Source.CHEMRXIV) + OSF)
            .filter { Topics.categoriesOf(it, subscribed).isNotEmpty() }
            .toSet()
}
