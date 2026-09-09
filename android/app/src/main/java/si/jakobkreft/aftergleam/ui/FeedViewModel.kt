package si.jakobkreft.aftergleam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Bridge
import si.jakobkreft.aftergleam.data.Drift
import si.jakobkreft.aftergleam.data.Dwell
import si.jakobkreft.aftergleam.data.Backup
import si.jakobkreft.aftergleam.data.Resurfaced
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Evidence
import si.jakobkreft.aftergleam.data.Signal
import si.jakobkreft.aftergleam.data.LibraryImport
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.PdfStore
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.ShownItem
import si.jakobkreft.aftergleam.data.Taste
import si.jakobkreft.aftergleam.data.Topics
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Scored
import si.jakobkreft.aftergleam.rank.SearchRanker
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.rank.Weights
import java.time.LocalDate

/**
 * Where a search looks.
 *
 * Labels sit in a row of chips, so they have to be short enough to read at a glance. "Saved
 * and reacted to" described the scope accurately and was unreadable at that size.
 */
enum class SearchScope(val label: String) {
    ARXIV("arXiv"),
    CACHED("On device"),
    KEPT("My library"),
}

data class FeedState(
    val loading: Boolean = false,
    val loadingLabel: String = "",
    val cards: List<Scored> = emptyList(),
    val reactions: Map<String, Reaction> = emptyMap(),
    val error: String? = null,
    val emptyDay: Boolean = false,
    val categories: Set<String> = emptySet(),
    val onboarded: Boolean = false,
    val ratedCount: Int = 0,
    val modelActive: Boolean = false,
    val importProgress: LibraryImport.Progress? = null,
    val importSummary: String? = null,
    val saved: List<Paper> = emptyList(),
    val downloaded: List<Paper> = emptyList(),
    val ratedPapers: List<Pair<Paper, Float>> = emptyList(),
    val evidence: Map<String, Evidence> = emptyMap(),
    val attention: Map<String, Int> = emptyMap(),
    val resurfaced: Resurfaced? = null,
    val backupSummary: String? = null,
    val detail: Paper? = null,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchHits: List<SearchRanker.Hit> = emptyList(),
    val searchError: String? = null,
    val personalisation: Float = 0.5f,
    val searchScope: SearchScope = SearchScope.ARXIV,
    val drift: Drift.Report? = null,
    val explore: List<Scored> = emptyList(),
    val exploreLoading: Boolean = false,
    val popular: List<Paper> = emptyList(),
    val searchOpen: Boolean = false,
    val theme: String = "system",
    val survey: SurveyState = SurveyState(),
    val topics: Set<String> = emptySet(),
    val reading: Paper? = null,
    val readingFile: java.io.File? = null,
    val readingError: String? = null,
    val readingPage: Int = 0,
) {
    /**
     * What the reader has explicitly said about a paper: true, false, or nothing yet.
     *
     * Every surface needs this and each was unpacking the ledger itself, which is how they
     * drifted apart in the first place.
     */
    fun likedFlag(paperId: String): Boolean? = evidence[paperId]?.let {
        when {
            Signal.LIKED in it.signals -> true
            Signal.DISLIKED in it.signals -> false
            else -> null
        }
    }

    /** The paper behind an id, from whichever surface is currently holding it. */
    fun paperById(id: String): Paper? =
        cards.firstOrNull { it.paper.id == id }?.paper
            ?: explore.firstOrNull { it.paper.id == id }?.paper
            ?: searchHits.firstOrNull { it.paper.id == id }?.paper
            ?: popular.firstOrNull { it.id == id }
            ?: saved.firstOrNull { it.id == id }
            ?: downloaded.firstOrNull { it.id == id }
            ?: detail?.takeIf { it.id == id }
            ?: resurfaced?.paper?.takeIf { it.id == id }
}

/**
 * The onboarding survey.
 *
 * The deck fills while the user is already answering. arXiv allows one request every three
 * seconds and the survey draws from a dozen fields, so waiting for the whole set first meant
 * staring at a progress bar for half a minute before being asked anything. Reading one
 * abstract takes about as long as fetching the next, so after the first card the loading is
 * invisible.
 */
data class SurveyState(
    val loading: Boolean = false,
    val deck: List<Pair<Taste.Probe, Paper>> = emptyList(),
    val liked: List<Pair<Taste.Probe, Paper>> = emptyList(),
    val seen: Int = 0,
    val expected: Int = 0,
    val failed: Boolean = false,
    /** Answered cards, newest last, so a mis-tap can be taken back. */
    val history: List<Pair<Taste.Probe, Paper>> = emptyList(),
) {
    val canGoBack: Boolean get() = history.isNotEmpty()
    val started: Boolean get() = expected > 0
    /** Out of papers and none still coming. */
    val done: Boolean get() = deck.isEmpty() && !loading && started
    /** Answered everything fetched so far, but more is on the way. */
    val waiting: Boolean get() = deck.isEmpty() && loading
    /** Three liked papers is where ranking switches on, so that is the honest target. */
    val enough: Boolean get() = liked.size >= 3
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** Below the 0.9 an explicit "interested" carries, so real judgements dominate. */
        const val SEED_WEIGHT = 0.7f

        /** How many more papers an "explore" page adds. */
        const val EXPLORE_PAGE = 30
    }


    private val db = Db(app)
    private val prefs = Prefs(app)

    /**
     * The trained model, kept between rebuilds.
     *
     * Training took the better part of the nine seconds a rebuild costs, and it depends only
     * on the reader's signals: re-fitting it because today's papers arrived is work for
     * nothing. The signature is the ledger, so any new reaction invalidates it at once.
     */
    private var cachedModel: Ranker.Model? = null
    private var cachedSignature: String? = null

    /**
     * The interest model, trained once and reused until the reader reacts to something.
     *
     * Both the digest and search want the same thing, and it depends only on the ledger.
     */
    private fun ensureModel(rated: List<RatedDoc>): Ranker.Model? {
        val signature = modelSignature(rated)
        if (signature == cachedSignature && cachedModel != null) return cachedModel
        val candidates = db.recentPapers(limit = 400)
        val ids = candidates.map { it.id }.toSet()
        val negatives = db.recentPapers(limit = 900)
            .filter { it.id !in ids }
            .map { it.rankText }
        val model = Ranker().train(candidates, rated, negatives)
        cachedModel = model
        cachedSignature = signature
        return model
    }

    private fun modelSignature(rated: List<RatedDoc>): String =
        rated.joinToString("|") { "${it.paperId ?: it.text.hashCode()}:${it.interest}" }
            .hashCode().toString()

    private val _state = MutableStateFlow(
        FeedState(
            categories = prefs.categories,
            onboarded = prefs.onboarded,
            theme = prefs.theme,
            topics = prefs.seedTopics,
        )
    )
    val state: StateFlow<FeedState> = _state.asStateFlow()

    init {
        if (prefs.onboarded) {
            // Before anything else, so a cold start with no fetch due still knows what the
            // field was reading. Popular has nothing else to rank by.
            _state.value = _state.value.copy(attention = db.attention())
            restore()
        }
    }

    /**
     * Fills the survey deck in the background while the user answers.
     *
     * Two passes of one paper per probe rather than two papers from each probe in turn. The
     * first pass alone gives a card from every field, so the questions alternate subject
     * from the very start, and the second pass only matters for someone who keeps going.
     */
    /**
     * Starts the paper survey using the topics the user chose, not a fixed list.
     *
     * The previous version drew from a hardcoded set that was almost all machine learning,
     * so anyone outside that field was asked to judge a dozen papers they had no reason to
     * care about.
     */
    fun startSurvey() {
        val sv = _state.value.survey
        if (sv.started || sv.loading) return

        val probes = Taste.probesFor(prefs.seedTopics)
        if (probes.isEmpty()) return
        _state.value = _state.value.copy(
            survey = SurveyState(loading = true, expected = probes.size * 2)
        )

        viewModelScope.launch {
            var any = false
            for (pass in 0 until 2) {
                for (probe in probes) {
                    val fetched = runCatching {
                        ArxivApi.probe(probe.category, probe.phrase, max = pass + 1)
                    }.getOrDefault(emptyList())

                    // On the second pass ask for two and keep the one not already seen.
                    val alreadyHave = _state.value.survey.let { st ->
                        (st.deck.map { it.second.id } + st.liked.map { it.second.id }).toSet()
                    }
                    val fresh = fetched.filter { it.id !in alreadyHave }
                        .take(1)
                        .map { probe to it }

                    if (fresh.isNotEmpty()) {
                        any = true
                        withContext(Dispatchers.IO) { db.upsertPapers(fresh.map { it.second }) }
                        val cur = _state.value.survey
                        _state.value = _state.value.copy(
                            survey = cur.copy(deck = cur.deck + fresh)
                        )
                    }
                    // The published rate limit is one request every three seconds.
                    kotlinx.coroutines.delay(3_000)

                    // Stop early once onboarding is over, so the loader does not keep
                    // fetching into a screen nobody is looking at.
                    if (_state.value.onboarded) return@launch
                }
            }
            val cur = _state.value.survey
            _state.value = _state.value.copy(
                survey = cur.copy(loading = false, failed = !any)
            )
        }
    }

    /** Answers the top card. Liked papers become training data straight away. */
    fun answerSurvey(liked: Boolean) {
        val sv = _state.value.survey
        val head = sv.deck.firstOrNull() ?: return
        // Into the ledger, which is the only thing the model reads. This used to write the
        // legacy `interest` column instead, so a reader who finished onboarding taught the
        // ranker precisely nothing and the library showed their answers with neither chip lit.
        db.addSignal(head.second.id, if (liked) Signal.LIKED else Signal.DISLIKED)
        invalidateModel()
        _state.value = _state.value.copy(
            survey = sv.copy(
                deck = sv.deck.drop(1),
                liked = if (liked) sv.liked + head else sv.liked,
                seen = sv.seen + 1,
                history = sv.history + head,
            ),
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
        )
    }

    /**
     * Takes back the last answer.
     *
     * A survey of one-tap judgements with no way back punishes a slip by baking it into the
     * model, and the reader has no way of knowing which card it was by the time they notice.
     * The rating is deleted rather than inverted, so undo leaves no trace.
     */
    fun undoSurveyAnswer() {
        val sv = _state.value.survey
        val last = sv.history.lastOrNull() ?: return
        db.removeSignal(last.second.id, Signal.LIKED)
        db.removeSignal(last.second.id, Signal.DISLIKED)
        invalidateModel()
        _state.value = _state.value.copy(
            survey = sv.copy(
                deck = listOf(last) + sv.deck,
                liked = sv.liked.filterNot { it.second.id == last.second.id },
                seen = (sv.seen - 1).coerceAtLeast(0),
                history = sv.history.dropLast(1),
            ),
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
        )
    }

    /** Records the chosen topics and their categories, before any papers are fetched. */
    fun setTopics(keys: Set<String>) {
        prefs.seedTopics = keys
        val cats = Topics.categoriesFor(keys)
        if (cats.isNotEmpty()) prefs.categories = cats
        _state.value = _state.value.copy(categories = cats, topics = keys)
    }

    /** Finishes onboarding using what the survey learned. */
    fun finishSurvey() {
        val sv = _state.value.survey
        // Categories from the topics chosen, widened by what the liked papers turned out to
        // be cross-listed under. A paper found under "generative models" is often filed
        // somewhere more useful than the probe that surfaced it.
        val cats = Topics.categoriesFor(prefs.seedTopics) +
            Taste.categoriesFrom(sv.liked.map { it.second }, sv.liked.map { it.first })
        prefs.categories = cats
        prefs.onboarded = true
        val reactions = db.allReactions()
        _state.value = _state.value.copy(
            categories = cats,
            onboarded = true,
            reactions = reactions,
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
            modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
        )
        sync(force = true)
    }

    fun setCategories(cats: Set<String>) {
        prefs.categories = cats
        _state.value = _state.value.copy(categories = cats)
    }

    fun finishOnboarding() {
        prefs.onboarded = true
        _state.value = _state.value.copy(onboarded = true)
        sync(force = true)
    }

    /** Reopen today's digest from disk. No network, works on a train. */
    private fun restore() {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val restored = withContext(Dispatchers.IO) {
                val items = db.digestFor(today)
                val byId = db.papersById(items.map { it.paperId }).associateBy { it.id }
                items.mapNotNull { item ->
                    byId[item.paperId]?.let {
                        Scored(it, 0f, item.confidence, runCatching { Slot.valueOf(item.slot) }
                            .getOrDefault(Slot.RELEVANCE), storedReason = item.reason)
                    }
                } to db.allReactions()
            }
            val (cards, reactions) = restored
            if (cards.isNotEmpty()) {
                // The resurfaced card has to be computed here too, not only when the digest
                // is rebuilt. Reopening the app is the common path, and a feature that only
                // appeared after a manual refresh would look broken.
                val resurfaced = withContext(Dispatchers.IO) { findResurfaced() }
                _state.value = _state.value.copy(
                    cards = cards,
                    reactions = reactions,
                    evidence = db.evidence(),
                    ratedCount = evidenceCount(),
                    modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
                    resurfaced = resurfaced,
                    drift = withContext(Dispatchers.IO) { computeDrift() },
                )
            } else {
                sync(force = false)
            }
        }
    }

    /**
     * Rebuild the digest from papers already on disk. This is what the user wants after
     * rating a few cards: it retrains and reorders instantly, and touches no network.
     */
    fun rerank() = sync(force = false, networkAllowed = false)

    /** Explicit pull for new papers, subject to the fetch interval. */
    fun refresh() = sync(force = true)

    private fun sync(force: Boolean, networkAllowed: Boolean = true) {
        val cats = _state.value.categories.toList()
        if (cats.isEmpty()) return

        val shouldFetch = networkAllowed && (force || prefs.fetchIsStale())
        _state.value = _state.value.copy(
            loading = true,
            loadingLabel = if (shouldFetch) "Fetching from arXiv" else "Re-ranking",
            error = null,
            emptyDay = false,
        )

        viewModelScope.launch {
            try {
                if (shouldFetch) {
                    _state.value = _state.value.copy(loadingLabel = "Fetching from arXiv")
                    val papers = ArxivApi.recent(cats, max = 300)
                    _state.value = _state.value.copy(
                        loadingLabel = "Got ${papers.size} papers, checking what is popular"
                    )
                    withContext(Dispatchers.IO) { db.upsertPapers(papers) }
                    prefs.lastFetchMillis = System.currentTimeMillis()

                    // Enrichment only. A failure here returns an empty map and the digest
                    // is built exactly as it would have been.
                    val hot = Attention.fetch()
                    if (hot.isNotEmpty()) {
                        withContext(Dispatchers.IO) { db.saveAttention(hot) }
                        _state.value = _state.value.copy(
                            attention = _state.value.attention + hot
                        )
                    }

                    // One extra request for the bridge slot. Without a pool from outside the
                    // user's categories there is nothing for that slot to choose from, which
                    // is why it never fired. Failure is silently fine: the slot just stays
                    // empty and the digest backfills.
                    _state.value = _state.value.copy(loadingLabel = "Looking outside your fields")
                    val outside = Bridge.candidatesFor(
                        subscribed = cats.toSet(),
                        dayOfYear = LocalDate.now().dayOfYear,
                    )
                    if (outside.isNotEmpty()) {
                        runCatching { ArxivApi.recent(outside, max = 80) }
                            .onSuccess { withContext(Dispatchers.IO) { db.upsertPapers(it) } }
                    }
                }
                _state.value = _state.value.copy(loadingLabel = "Ranking")
                withContext(Dispatchers.Default) { rebuild(cats) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not reach arXiv",
                )
            }
        }
    }

    /**
     * Builds the digest.
     *
     * This used to publish an unranked digest first and reorder it underneath the reader a
     * few seconds later. That was worse than waiting: a list you have started reading
     * rearranging itself is disorienting in a way a short wait is not. Now that ranking takes
     * about ten seconds rather than seventy, the wait is shown as skeleton cards instead.
     */
    private fun rebuild(cats: List<String>) {
        val today = LocalDate.now().toString()
        val candidates = db.recentPapers(limit = 400)
        // Easy negatives come from older papers, deliberately disjoint from the candidates
        // being scored so that training cannot mark a good candidate as a negative.
        val candidateIds = candidates.map { it.id }.toSet()
        // Only as many negatives as the trainer will actually sample, rather than loading
        // three thousand abstracts out of SQLite on every rebuild to discard most of them.
        val negativePool = db.recentPapers(limit = 900)
            .filter { it.id !in candidateIds }
            .map { it.rankText }

        val rated = ratedDocs()

        // Papers shown on an earlier day stay out, but today's own digest does not count
        // as seen, otherwise re-ranking would empty the screen.
        val seen = db.shownIds() - db.digestFor(today).map { it.paperId }.toSet()

        val weights = Weights(
            quality = prefs.qualityWeight,
            explorationRate = prefs.explorationRate,
            diversity = prefs.diversity,
        )

        // Reuse the model whenever the ledger is unchanged, which is every re-rank that is
        // not preceded by a reaction.
        val signature = modelSignature(rated)
        val reusable = if (signature == cachedSignature) cachedModel else null
        val model = reusable ?: Ranker(weights).train(candidates, rated, negativePool)
        cachedModel = model
        cachedSignature = signature

        val cards = Ranker(weights).digest(
            candidates = candidates,
            rated = rated,
            seen = seen,
            subscribed = cats.toSet(),
            size = prefs.digestSize,
            negativePool = negativePool,
            attention = _state.value.attention,
            evidenceCount = evidenceCount(),
            topicHistory = db.topicHistory(),
            prebuilt = model,
        )
        db.markShown(
            cards.map { ShownItem(it.paper.id, it.slot.name, it.why(), it.relevance) },
            today,
        )
        val reactions = db.allReactions()
        _state.value = _state.value.copy(
            loading = false,
            cards = cards,
            reactions = reactions,
            emptyDay = cards.isEmpty(),
            // `rated` includes the seed documents built from the chosen subjects, which the
            // ranker legitimately fits on but the reader never reacted to. Counting them
            // here made the header claim judgements that did not exist and switched the
            // model on before it had been taught anything.
            ratedCount = evidenceCount(),
            modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
            resurfaced = findResurfaced(),
            drift = computeDrift(),
        )
    }

    /**
     * Compares the last fortnight of ratings against the fortnight before it.
     *
     * Two weeks is short enough that a change of project shows up and long enough that a
     * single evening of reading does not look like a trend.
     */
    private fun computeDrift(): Drift.Report {
        val now = System.currentTimeMillis()
        val fortnight = 14L * 24 * 60 * 60 * 1000
        val today = LocalDate.now()
        val (judged, liked) = db.explorationOutcome(
            fromDay = today.minusDays(14).toString(),
            toDay = today.toString(),
        )
        return Drift.compute(
            recent = db.ratedBetween(now - fortnight, now),
            earlier = db.ratedBetween(now - 2 * fortnight, now - fortnight),
            explorationJudged = judged,
            explorationLiked = liked,
        )
    }

    /**
     * At most one per digest. The design note is explicit that this reads as nagging
     * otherwise, and the feature lives or dies on tone.
     */
    private fun findResurfaced(): Resurfaced? {
        val today = LocalDate.now()
        val candidates = db.resurfaceCandidates(
            fromDay = today.minusMonths(12).toString(),
            toDay = today.minusMonths(3).toString(),
        )
        for (paper in candidates) {
            val venue = Venue.of(paper) ?: continue
            // A workshop is not the "this turned out to matter" moment the feature promises.
            if (Venue.isWorkshop(paper)) continue
            val shown = db.firstShown(paper.id) ?: continue
            return Resurfaced(paper, venue, shown)
        }
        return null
    }

    fun dismissResurfaced(stillNotInterested: Boolean) {
        val r = _state.value.resurfaced ?: return
        // "Still not interested" is itself a strong training signal, so record it.
        if (stillNotInterested) {
            db.addSignal(r.paper.id, Signal.DISLIKED)
            invalidateModel()
        }
        _state.value = _state.value.copy(
            resurfaced = null,
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
        )
    }

    fun exportBackup(): String = Backup.export(db, prefs)

    fun restoreBackup(json: String) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Backup.restore(json, db, prefs) }
                val reactions = db.allReactions()
                _state.value = _state.value.copy(
                    reactions = reactions,
                    evidence = db.evidence(),
                    ratedCount = evidenceCount(),
                    modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
                    categories = prefs.categories,
                    backupSummary = "Restored ${r.reactions} ratings.",
                )
                rerank()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    backupSummary = "Could not read that file: ${e.message}",
                )
            }
        }
    }

    fun noteExported(name: String) {
        _state.value = _state.value.copy(backupSummary = "Exported to $name.")
    }

    /**
     * Imports a library, recording every resolved paper as an explicit like.
     *
     * These are papers the reader chose to keep, which is the same claim the "more like
     * this" button makes, so it is written as the same signal. It used to be written to the
     * legacy rating column, where the model never saw it: importing a hundred papers taught
     * the ranker nothing at all.
     */
    fun importLibrary(text: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(importSummary = null)
            try {
                val result = LibraryImport.run(text) { p ->
                    _state.value = _state.value.copy(importProgress = p)
                }
                withContext(Dispatchers.IO) {
                    db.upsertPapers(result.papers)
                    result.papers.forEach { db.addSignal(it.id, Signal.LIKED) }
                }
                val reactions = db.allReactions()
                _state.value = _state.value.copy(
                    importProgress = null,
                    reactions = reactions,
                    evidence = db.evidence(),
                    ratedCount = evidenceCount(),
                    modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
                    importSummary = buildString {
                        append("Matched ${result.papers.size} of ${result.total}.")
                        if (result.unmatched > 0) {
                            append(" ${result.unmatched} had no arXiv record")
                        }
                        if (result.failed > 0) append(", ${result.failed} could not be checked")
                        append(".")
                    },
                )
                if (_state.value.onboarded) rerank()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    importProgress = null,
                    importSummary = "Import failed: ${e.message}",
                )
            }
        }
    }

    /** Everything the user has accumulated: saved, downloaded, and rated. */
    fun loadLibrary() {
        viewModelScope.launch {
            val reactions = _state.value.reactions
            val store = PdfStore(getApplication())
            val loaded = withContext(Dispatchers.IO) {
                val saved = db.papersById(reactions.filterValues { it.saved }.keys)
                // Explicit judgements only. The shelf's promise is "everything you told it,
                // where you can change your mind", and a paper you merely opened is not
                // something you said. It used to read the legacy rating column, so it showed
                // whatever onboarding had written and never grew as you reacted.
                val judged = db.evidence().filterValues { it.explicit }
                val ratings = judged.mapValues { (_, e) -> e.label() ?: 0f }
                val rated = db.papersById(ratings.keys)
                    .mapNotNull { p -> ratings[p.id]?.let { p to it } }
                    .sortedByDescending { it.second }
                // Downloaded is a property of the cache, not of any table, so it is asked
                // of the store directly rather than tracked in a column that could drift.
                val downloaded = db.papersById(
                    (reactions.keys + ratings.keys).filter { store.isCached(it) }
                )
                Triple(saved, downloaded, rated)
            }
            _state.value = _state.value.copy(
                saved = loaded.first,
                downloaded = loaded.second,
                ratedPapers = loaded.third,
            )
        }
    }

    fun setDigestSize(n: Int) { prefs.digestSize = n }
    fun setQualityWeight(v: Float) { prefs.qualityWeight = v }
    fun setExplorationRate(v: Float) { prefs.explorationRate = v }
    fun setDiversity(v: Float) { prefs.diversity = v }
    fun currentDigestSize() = prefs.digestSize
    fun currentQualityWeight() = prefs.qualityWeight
    fun currentExplorationRate() = prefs.explorationRate
    fun currentDiversity() = prefs.diversity
    fun currentDigestHour() = prefs.digestHour
    fun currentNotifyEnabled() = prefs.notifyEnabled
    fun currentReminderHour() = prefs.reminderHour
    fun currentReminderEnabled() = prefs.reminderEnabled
    fun setReminderHour(h: Int) { prefs.reminderHour = h }
    fun setReminderEnabled(v: Boolean) { prefs.reminderEnabled = v }
    fun setTheme(mode: String) {
        prefs.theme = mode
        _state.value = _state.value.copy(theme = mode)
    }

    fun setDigestHour(h: Int) { prefs.digestHour = h }
    fun setNotifyEnabled(v: Boolean) { prefs.notifyEnabled = v }

    /** Clears the model but keeps papers. Trust requires an exit. */
    fun resetModel() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.clearFeedback() }
            invalidateModel()
            _state.value = _state.value.copy(
                reactions = emptyMap(), evidence = emptyMap(), ratedPapers = emptyList(),
                ratedCount = 0, modelActive = false,
            )
            rerank()
        }
    }

    fun setSearchQuery(q: String) {
        _state.value = _state.value.copy(searchQuery = q)
    }

    /**
     * Moving the slider re-sorts what is already on screen, synchronously.
     *
     * Nothing is fetched and nothing is retrained: each hit already carries its query match
     * and its predicted interest, so this is a comparison over a hundred items. The earlier
     * version called the full ranker here, which refit the vectoriser and retrained the
     * classifier for every pixel of the drag.
     */
    fun setSearchScope(scope: SearchScope) {
        _state.value = _state.value.copy(searchScope = scope)
        if (_state.value.searchQuery.trim().length >= 2) runSearch()
    }

    fun setPersonalisation(v: Float) {
        val st = _state.value
        _state.value = st.copy(
            personalisation = v,
            searchHits = SearchRanker.reorder(st.searchHits, v),
        )
    }

    fun runSearch() {
        val q = _state.value.searchQuery.trim()
        if (q.length < 2) return
        _state.value = _state.value.copy(searching = true, searchError = null)
        viewModelScope.launch {
            try {
                val scope = _state.value.searchScope
                val results = when (scope) {
                    SearchScope.ARXIV -> ArxivApi.search(q, max = 100)
                    SearchScope.CACHED -> withContext(Dispatchers.IO) {
                        db.searchLocal(q, savedOnly = false)
                    }
                    SearchScope.KEPT -> withContext(Dispatchers.IO) {
                        db.searchLocal(q, savedOnly = true)
                    }
                }
                val hits = withContext(Dispatchers.Default) {
                    val rated = ratedDocs()
                    val ratedIds = rated.mapNotNull { it.paperId }.toSet()
                    // The digest's model, not a fresh one. Training here was the whole cost
                    // of a search, and it is the same model either way.
                    val model = ensureModel(rated)
                    // Papers already judged are poor results when searching arXiv, but they
                    // are the entire point when searching your own library.
                    val visible =
                        if (scope == SearchScope.ARXIV) results.filter { it.id !in ratedIds }
                        else results
                    SearchRanker.rank(
                        results = visible,
                        query = q,
                        rated = rated,
                        personalisation = _state.value.personalisation,
                        // No negative pool is needed when a model already exists; loading
                        // eight hundred abstracts to train a duplicate of it was most of the
                        // time a search took.
                        negativePool = emptyList(),
                        model = model,
                    )
                }
                // Only arXiv results are new; the local scopes already came from the table.
                if (scope == SearchScope.ARXIV) {
                    withContext(Dispatchers.IO) { db.upsertPapers(results) }
                }
                _state.value = _state.value.copy(searching = false, searchHits = hits)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    searching = false,
                    searchError = e.message ?: "Search failed",
                )
            }
        }
    }

    /**
     * Training documents: papers the user rated, plus the topics they chose at onboarding.
     *
     * Topic seeds are weighted below an explicit rating. They are a statement of direction
     * rather than a judgement of a specific paper, and once real ratings exist those should
     * win. They keep contributing rather than being discarded at the first rating, because a
     * handful of ratings is a thinner picture of someone than the fields they told us about.
     */
    private fun ratedDocs(): List<RatedDoc> {
        val evidence = db.evidence()
        val judged = db.papersById(evidence.keys).mapNotNull { p ->
            evidence[p.id]?.label()?.let { RatedDoc(p.id, p.rankText, it) }
        }
        val seeds = Topics.seedsFor(prefs.seedTopics).map { RatedDoc(null, it, SEED_WEIGHT) }
        return judged + seeds
    }

    /**
     * Papers carrying any signal at all. This is what decides how far the model's confidence
     * is pulled toward the prior, so it counts evidence rather than explicit judgements: a
     * reader who never presses a button still accumulates it.
     */
    private fun evidenceCount(): Int = db.evidence().count { it.value.label() != null }

    /** Pending dwell timers, cancelled the moment their screen closes. */
    private var dwell: Job? = null
    private var readerDwell: Job? = null

    /** Records a behavioural signal. Free for the reader, and far more honest than a rating. */
    fun signal(paperId: String, signal: Signal) {
        invalidateModel()
        db.addSignal(paperId, signal)
        _state.value = _state.value.copy(
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
        )
    }

    /**
     * An explicit steering instruction, or clearing one.
     *
     * "More like this" and "less like this" rather than a number: a steering instruction has
     * no scale to calibrate, so it means the same thing coming from any two readers.
     */
    /** Any new signal makes the cached model wrong, so it is dropped rather than aged. */
    private fun invalidateModel() {
        cachedModel = null
        cachedSignature = null
    }

    fun steer(paperId: String, liked: Boolean?) {
        invalidateModel()
        db.removeSignal(paperId, Signal.LIKED)
        db.removeSignal(paperId, Signal.DISLIKED)
        when (liked) {
            true -> db.addSignal(paperId, Signal.LIKED)
            false -> db.addSignal(paperId, Signal.DISLIKED)
            null -> Unit
        }
        val ev = db.evidence()
        val judged = ev.count { it.value.label() != null }
        // Keep the library shelf in step. Reacting to a paper in the digest has to make it
        // appear there, and clearing a reaction has to remove it, without reopening the tab.
        val shelf = _state.value.ratedPapers.filterNot { it.first.id == paperId } +
            listOfNotNull(
                _state.value.ratedPapers.firstOrNull { it.first.id == paperId }?.first
                    ?: _state.value.paperById(paperId),
            ).filter { liked != null }.map { it to (ev[paperId]?.label() ?: 0f) }
        _state.value = _state.value.copy(
            evidence = ev,
            ratedCount = judged,
            modelActive = judged >= Ranker.MIN_RATINGS,
            ratedPapers = shelf.sortedByDescending { it.second },
        )
    }

    /**
     * A wider, deliberately less confident feed from everything the digest passed over.
     *
     * The digest is a fixed set of sixty from a pool of several hundred, and the rest simply
     * vanished. This is where they go. Temperature is high and the diversity weight is
     * raised, so it leans towards spread rather than towards the model's convictions, which
     * is the point of an explore surface: the digest is for what the reader probably wants,
     * this is for what they might not know they want.
     */
    fun loadExplore(more: Boolean = false) {
        if (_state.value.exploreLoading) return
        val cats = _state.value.categories.toList()
        if (cats.isEmpty()) return
        _state.value = _state.value.copy(exploreLoading = true)

        viewModelScope.launch {
            val cards = withContext(Dispatchers.Default) {
                val today = LocalDate.now().toString()
                val inDigest = db.digestFor(today).map { it.paperId }.toSet()
                val alreadyShown = if (more) _state.value.explore.map { it.paper.id }.toSet()
                    else emptySet()
                val candidates = db.recentPapers(limit = 800)
                    .filter { it.id !in inDigest && it.id !in alreadyShown }
                val rated = ratedDocs()
                Ranker(
                    Weights(
                        quality = prefs.qualityWeight,
                        explorationRate = 0f,
                        diversity = 0.6f,
                        temperature = 1.2f,
                    )
                ).digest(
                    candidates = candidates,
                    rated = rated,
                    seen = emptySet(),
                    subscribed = cats.toSet(),
                    size = EXPLORE_PAGE,
                    attention = _state.value.attention,
                    evidenceCount = evidenceCount(),
                    topicHistory = db.topicHistory(),
                    prebuilt = cachedModel,
                )
            }
            _state.value = _state.value.copy(
                exploreLoading = false,
                explore = if (more) _state.value.explore + cards else cards,
            )
        }
    }

    /**
     * What the field is reading, with the model switched off entirely.
     *
     * Personalisation is the wrong lens some days, and mixing this into a personalised feed
     * would just make it noise. Ordered by attention and venue only, so it says the same
     * thing to everyone.
     */
    fun loadPopular() {
        viewModelScope.launch {
            val papers = withContext(Dispatchers.IO) {
                val attention = _state.value.attention
                db.recentPapers(limit = 600)
                    .map { p ->
                        p to (Attention.score(attention[p.id] ?: 0) * 2f + Venue.score(p))
                    }
                    .filter { it.second > 0f }
                    .sortedByDescending { it.second }
                    .take(60)
                    .map { it.first }
            }
            _state.value = _state.value.copy(popular = papers)
        }
    }

    /**
     * Opens search, defaulting the scope to the one the reader is standing in.
     *
     * Reaching for search from the library is almost always "where did I put that paper",
     * not "what else exists on arXiv", and making them change the scope every time to ask
     * the obvious question is friction for nothing. Still a default, not a rule: the chips
     * are right there.
     */
    fun openSearch(scope: SearchScope? = null) {
        _state.value = _state.value.copy(
            searchOpen = true,
            searchScope = scope ?: _state.value.searchScope,
        )
    }
    fun closeSearch() { _state.value = _state.value.copy(searchOpen = false) }

    fun openDetail(paper: Paper) {
        val current = _state.value.reactions[paper.id] ?: Reaction.NONE
        if (!current.viewed) db.setReaction(paper.id, current.copy(viewed = true))
        db.addSignal(paper.id, Signal.OPENED)
        dwell?.cancel()
        dwell = startDwell(paper.id, Signal.DWELLED, Dwell.DETAIL_MILLIS)
        _state.value = _state.value.copy(
            detail = paper,
            reactions = db.allReactions(),
            evidence = db.evidence(),
        )
    }

    fun closeDetail() {
        dwell?.cancel()
        dwell = null
        _state.value = _state.value.copy(detail = null)
    }

    /** Records a paper being passed on to somebody, which is a strong thing to do quietly. */
    fun share(paperId: String) {
        db.addSignal(paperId, Signal.SHARED)
        _state.value = _state.value.copy(evidence = db.evidence())
    }

    /**
     * Arms a signal that only fires if the reader is still on the paper when the clock runs out.
     *
     * A timer rather than a stopwatch read on the way out: the way people leave a screen is by
     * swiping home or killing the app, and a stopwatch would record nothing in exactly the
     * cases where the reader was most absorbed. Cancelled when the screen closes, so a glance
     * costs nothing.
     */
    private fun startDwell(paperId: String, signal: Signal, millis: Long): Job =
        viewModelScope.launch {
            delay(millis)
            db.addSignal(paperId, signal)
            _state.value = _state.value.copy(evidence = db.evidence())
        }

    /** Opens the reader, downloading first if the file is not already cached. */
    fun openReader(paper: Paper) {
        val store = PdfStore(getApplication())
        _state.value = _state.value.copy(
            reading = paper,
            readingFile = null,
            readingError = null,
            readingPage = prefs.lastPage(paper.id),
        )
        viewModelScope.launch {
            try {
                val file = store.download(paper.id)
                // The download itself is not the signal. Tapping Read is one tap, and this
                // used to score it 0.7 whether the reader took in a word of it or reversed
                // straight back out. The clock starts once the file is actually on screen.
                readerDwell?.cancel()
                readerDwell = startDwell(paper.id, Signal.DOWNLOADED, Dwell.READER_MILLIS)
                _state.value = _state.value.copy(
                    readingFile = file,
                    evidence = db.evidence(),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    readingError = e.message ?: "Could not fetch the PDF",
                )
            }
        }
    }

    fun closeReader() {
        readerDwell?.cancel()
        readerDwell = null
        _state.value = _state.value.copy(reading = null, readingFile = null, readingError = null)
    }

    /** Where the reader stopped, so a long paper reopens where it was left. */
    fun rememberPage(paperId: String, page: Int) {
        prefs.setLastPage(paperId, page)
        // Getting past the third page is about the strongest thing a reader does silently.
        if (page >= 2) db.addSignal(paperId, Signal.READ_PAGES)
    }

    fun toggleSave(paperId: String) {
        val current = _state.value.reactions[paperId] ?: Reaction.NONE
        val next = current.copy(saved = !current.saved)
        db.setReaction(paperId, next)
        if (next.saved) db.addSignal(paperId, Signal.SAVED)
        else db.removeSignal(paperId, Signal.SAVED)
        _state.value = _state.value.copy(
            reactions = db.allReactions(),
            evidence = db.evidence(),
        )
    }
}
