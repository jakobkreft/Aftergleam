package si.jakobkreft.aftergleam.rank

import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.ShownItem
import si.jakobkreft.aftergleam.data.Topics
import java.time.LocalDate

/**
 * Composes and stores a day's digest, given nothing but the database and the settings.
 *
 * Extracted so the nightly worker can do it as well as the screen. The worker already
 * fetched the papers before the reader woke up and then stopped, leaving the ranking to be
 * paid for on the first open of the day: several seconds of placeholder cards for work that
 * could have happened at five in the morning on a charger.
 *
 * One implementation, not two. A digest built by the worker and a digest built by the app
 * have to be the same digest, and the way that goes wrong is a second copy of this that
 * drifts a weight at a time.
 */
object DigestBuilder {

    /**
     * Seed documents weigh less than a paper somebody actually read.
     *
     * Below the 0.9 an explicit "interested" carries, so real judgements dominate.
     */
    const val SEED_WEIGHT = 0.7f

    /** The training set: everything judged, plus the chosen subjects as pseudo-documents. */
    fun ratedDocs(db: Db, prefs: Prefs): List<RatedDoc> {
        val evidence = db.evidence()
        val judged = db.papersById(evidence.keys).mapNotNull { p ->
            evidence[p.id]?.label()?.let { RatedDoc(p.id, p.rankText, it) }
        }
        val seeds = Topics.seedsFor(prefs.seedTopics).map { RatedDoc(null, it, SEED_WEIGHT) }
        // Keywords are not among them. They are names to find, not topics to learn, and
        // through the ranker's tokens "H-Net" would have taught the model the word "net".
        return judged + seeds
    }

    /**
     * The day's candidates: the newest papers, and recent papers mentioning a keyword.
     *
     * Four hundred newest is a day or less of a busy field, so a paper mentioning a keyword
     * from earlier in the week, or from a category the reader does not follow, would never be
     * considered without the second half.
     */
    fun candidates(db: Db, prefs: Prefs, today: LocalDate = LocalDate.now()): List<Paper> {
        val recent = db.recentPapers(limit = 400)
        val keywords = prefs.keywords
        if (keywords.isEmpty()) return recent
        val since = today.minusDays(Keywords.WINDOW_DAYS).toString()
        return (recent + db.keywordCandidates(keywords, since)).distinctBy { it.id }
    }

    /** What a rebuild produced, so the caller can keep the model it fitted. */
    data class Built(val cards: List<Scored>, val model: Ranker.Model?)

    /**
     * @param prebuilt a model to reuse when the ledger has not changed since it was fitted.
     * @param store whether to record the result as the day's digest. The worker stores;
     *   a preview would not.
     */
    fun build(
        db: Db,
        prefs: Prefs,
        attention: Map<String, Int>,
        day: String = LocalDate.now().toString(),
        prebuilt: Ranker.Model? = null,
        store: Boolean = true,
    ): Built {
        val candidates = candidates(db, prefs)
        // Easy negatives come from older papers, deliberately disjoint from the candidates
        // being scored so that training cannot mark a good candidate as a negative. Only as
        // many as the trainer will sample, rather than loading three thousand abstracts out
        // of SQLite on every rebuild to discard most of them; and not at all when the model
        // is already trained, which is every rebuild after the first and the first digest
        // too, since onboarding trains it while the reader is still answering.
        val candidateIds = candidates.map { it.id }.toSet()
        val negativePool = if (prebuilt != null) emptyList() else db.recentPapers(limit = 900)
            .filter { it.id !in candidateIds }
            .map { it.rankText }

        val rated = ratedDocs(db, prefs)

        // Papers shown on an earlier day stay out, but today's own digest does not count as
        // seen, otherwise re-ranking would empty the screen.
        val seen = db.shownIds() - db.digestFor(day).map { it.paperId }.toSet()

        val weights = Weights(
            quality = prefs.qualityWeight,
            explorationRate = prefs.explorationRate,
            diversity = prefs.diversity,
        )

        val model = prebuilt ?: Ranker(weights).train(candidates, rated, negativePool)
        val cards = Ranker(weights).digest(
            candidates = candidates,
            rated = rated,
            seen = seen,
            subscribed = prefs.categories,
            size = prefs.digestSize,
            negativePool = negativePool,
            attention = attention,
            evidenceCount = db.evidence().count { it.value.label() != null },
            topicHistory = db.topicHistory(subscribed = prefs.categories),
            prebuilt = model,
            keywords = prefs.keywords,
        )
        if (store) {
            db.markShown(
                cards.map { ShownItem(it.paper.id, it.slot.name, it.why(), it.relevance) },
                day,
            )
        }
        return Built(cards, model)
    }
}
