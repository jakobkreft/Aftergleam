package si.jakobkreft.aftergleam.data

/**
 * What is worth asking the servers for, given what is already here.
 *
 * Pulled out of the sync coroutine because it is the part with the interesting cases and
 * none of the machinery: four conditions, and getting any of them wrong either wastes half a
 * minute on three servers or leaves a subject the reader just ticked permanently empty.
 */
object FetchPlan {

    /**
     * @param subscribed everything the reader follows.
     * @param alreadyFetched the subset whose papers are on the device.
     * @param announced whether the source has published since the last fetch.
     * @param recent whether the last fetch was inside the minimum interval.
     * @param forced whether the reader asked for this, rather than the app deciding.
     */
    fun decide(
        subscribed: Set<String>,
        alreadyFetched: Set<String>,
        announced: Boolean,
        recent: Boolean,
        forced: Boolean,
        networkAllowed: Boolean = true,
    ): Set<String> {
        if (!networkAllowed || subscribed.isEmpty()) return emptySet()
        // Something has been published: everything is worth re-asking.
        if (announced) return subscribed
        // The reader asked and enough time has passed to make it plausible that a server
        // without an announcement schedule, which is both of the preprint ones, has moved.
        if (forced && !recent) return subscribed
        // Nothing has been announced and nothing has aged, so the only thing worth a
        // request is a subject with no papers here yet.
        return subscribed - alreadyFetched
    }
}
