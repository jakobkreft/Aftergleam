package si.jakobkreft.aftergleam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Bridge
import si.jakobkreft.aftergleam.data.Drift
import si.jakobkreft.aftergleam.data.Backup
import si.jakobkreft.aftergleam.data.Resurfaced
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.data.Db
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
    val attention: Map<String, Int> = emptyMap(),
    val resurfaced: Resurfaced? = null,
    val backupSummary: String? = null,
    val detail: Paper? = null,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchHits: List<SearchRanker.Hit> = emptyList(),
    val searchError: String? = null,
    val personalisation: Float = 0.5f,
    val drift: Drift.Report? = null,
    val theme: String = "system",
    val survey: SurveyState = SurveyState(),
    val topics: Set<String> = emptySet(),
)

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
    }


    private val db = Db(app)
    private val prefs = Prefs(app)

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
        if (prefs.onboarded) restore()
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
        db.setReaction(
            head.second.id,
            Reaction(interest = if (liked) Reaction.LIKED else Reaction.DISLIKED),
        )
        _state.value = _state.value.copy(
            survey = sv.copy(
                deck = sv.deck.drop(1),
                liked = if (liked) sv.liked + head else sv.liked,
                seen = sv.seen + 1,
                history = sv.history + head,
            ),
            reactions = db.allReactions(),
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
        db.setReaction(last.second.id, Reaction.NONE)
        _state.value = _state.value.copy(
            survey = sv.copy(
                deck = listOf(last) + sv.deck,
                liked = sv.liked.filterNot { it.second.id == last.second.id },
                seen = (sv.seen - 1).coerceAtLeast(0),
                history = sv.history.dropLast(1),
            ),
            reactions = db.allReactions(),
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
            ratedCount = reactions.count { it.value.rated },
            modelActive = reactions.count { it.value.rated } >= Ranker.MIN_RATINGS,
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
                    ratedCount = reactions.count { it.value.rated },
                    modelActive = reactions.count { it.value.rated } >= Ranker.MIN_RATINGS,
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
                    if (hot.isNotEmpty()) _state.value = _state.value.copy(attention = hot)

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

    private fun rebuild(cats: List<String>) {
        val today = LocalDate.now().toString()
        val candidates = db.recentPapers(limit = 400)
        // Easy negatives come from older papers, deliberately disjoint from the candidates
        // being scored so that training cannot mark a good candidate as a negative.
        val candidateIds = candidates.map { it.id }.toSet()
        val negativePool = db.recentPapers(limit = 3000)
            .filter { it.id !in candidateIds }
            .map { it.rankText }

        val rated = ratedDocs()

        // Papers shown on an earlier day stay out, but today's own digest does not count
        // as seen, otherwise re-ranking would empty the screen.
        val seen = db.shownIds() - db.digestFor(today).map { it.paperId }.toSet()

        val cards = Ranker(
            Weights(
                quality = prefs.qualityWeight,
                explorationRate = prefs.explorationRate,
                diversity = prefs.diversity,
            )
        ).digest(
            candidates = candidates,
            rated = rated,
            seen = seen,
            subscribed = cats.toSet(),
            size = prefs.digestSize,
            negativePool = negativePool,
            attention = _state.value.attention,
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
            ratedCount = rated.size,
            modelActive = rated.size >= Ranker.MIN_RATINGS,
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
        for ((paper, rated) in candidates) {
            val venue = Venue.of(paper) ?: continue
            // A workshop is not the "this turned out to matter" moment the feature promises.
            if (Venue.isWorkshop(paper)) continue
            val shown = db.firstShown(paper.id) ?: continue
            return Resurfaced(paper, venue, shown, rated)
        }
        return null
    }

    fun dismissResurfaced(stillNotInterested: Boolean) {
        val r = _state.value.resurfaced ?: return
        // "Still not interested" is itself a strong training signal, so record it.
        if (stillNotInterested) db.setReaction(r.paper.id, Reaction(interest = Reaction.DISLIKED))
        _state.value = _state.value.copy(
            resurfaced = null,
            reactions = db.allReactions(),
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
                    ratedCount = reactions.count { it.value.rated },
                    modelActive = reactions.count { it.value.rated } >= Ranker.MIN_RATINGS,
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

    /** Sets an explicit interest rating. Passing null clears it. */
    fun rate(paperId: String, interest: Float?) {
        val current = _state.value.reactions[paperId] ?: Reaction.NONE
        val next = current.copy(interest = interest)
        db.setReaction(paperId, next)
        val reactions = db.allReactions()
        _state.value = _state.value.copy(
            reactions = reactions,
            ratedCount = reactions.count { it.value.rated },
            // Keep the library list in step, so changing a rating there does not leave the
            // row showing the old value until the tab is reopened.
            ratedPapers = _state.value.ratedPapers.mapNotNull { (p, old) ->
                if (p.id == paperId) interest?.let { p to it } else p to old
            },
        )
    }

    /**
     * Imports a library and rates every resolved paper as liked.
     *
     * These are papers the user chose to read, which is a stronger positive than anything
     * the app can infer from a tap, so they seed the model at 0.9 rather than 1.0: leaving
     * headroom means a later explicit rating can still outrank an imported one.
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
                    result.papers.forEach {
                        db.setReaction(it.id, Reaction(interest = Reaction.LIKED))
                    }
                }
                val reactions = db.allReactions()
                _state.value = _state.value.copy(
                    importProgress = null,
                    reactions = reactions,
                    ratedCount = reactions.count { it.value.rated },
                    modelActive = reactions.count { it.value.rated } >= Ranker.MIN_RATINGS,
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
                val ratings = db.ratings()
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
            withContext(Dispatchers.IO) { db.clearReactions() }
            _state.value = _state.value.copy(
                reactions = emptyMap(), ratedCount = 0, modelActive = false,
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
                val results = ArxivApi.search(q, max = 100)
                val hits = withContext(Dispatchers.Default) {
                    val rated = ratedDocs()
                    // Papers the user has already judged make poor search results and good
                    // training data, so they inform the ranking without appearing in it.
                    val ratedIds = rated.mapNotNull { it.paperId }.toSet()
                    SearchRanker.rank(
                        results = results.filter { it.id !in ratedIds },
                        query = q,
                        rated = rated,
                        personalisation = _state.value.personalisation,
                        // Rated papers are cached like any other, so an unfiltered pool
                        // hands the model its own positives labelled as negatives and
                        // flattens every interest score towards zero.
                        negativePool = db.recentPapers(limit = 800)
                            .filter { it.id !in ratedIds }
                            .map { it.rankText },
                    )
                }
                withContext(Dispatchers.IO) { db.upsertPapers(results) }
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
        val ratings = db.ratings()
        val judged = db.papersById(ratings.keys).mapNotNull { p ->
            ratings[p.id]?.let { RatedDoc(p.id, p.rankText, it) }
        }
        val seeds = Topics.seedsFor(prefs.seedTopics).map { RatedDoc(null, it, SEED_WEIGHT) }
        return judged + seeds
    }

    fun openDetail(paper: Paper) {
        val current = _state.value.reactions[paper.id] ?: Reaction.NONE
        if (!current.viewed) db.setReaction(paper.id, current.copy(viewed = true))
        _state.value = _state.value.copy(
            detail = paper,
            reactions = db.allReactions(),
        )
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null)
    }

    fun toggleSave(paperId: String) {
        val current = _state.value.reactions[paperId] ?: Reaction.NONE
        val next = current.copy(saved = !current.saved)
        db.setReaction(paperId, next)
        _state.value = _state.value.copy(reactions = db.allReactions())
    }
}
