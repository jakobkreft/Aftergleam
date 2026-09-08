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
import si.jakobkreft.aftergleam.data.Backup
import si.jakobkreft.aftergleam.data.Resurfaced
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.LibraryImport
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.ShownItem
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
    val attention: Map<String, Int> = emptyMap(),
    val resurfaced: Resurfaced? = null,
    val backupSummary: String? = null,
    val detail: Paper? = null,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchHits: List<SearchRanker.Hit> = emptyList(),
    val searchError: String? = null,
    val personalisation: Float = 0.5f,
)

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Db(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(
        FeedState(categories = prefs.categories, onboarded = prefs.onboarded)
    )
    val state: StateFlow<FeedState> = _state.asStateFlow()

    init {
        if (prefs.onboarded) restore()
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
                    val papers = ArxivApi.recent(cats, max = 300)
                    withContext(Dispatchers.IO) { db.upsertPapers(papers) }
                    prefs.lastFetchMillis = System.currentTimeMillis()

                    // Enrichment only. A failure here returns an empty map and the digest
                    // is built exactly as it would have been.
                    val hot = Attention.fetch()
                    if (hot.isNotEmpty()) _state.value = _state.value.copy(attention = hot)
                }
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

    fun loadSaved() {
        viewModelScope.launch {
            val ids = _state.value.reactions.filterValues { it.saved }.keys
            val papers = withContext(Dispatchers.IO) { db.papersById(ids) }
            _state.value = _state.value.copy(saved = papers)
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

    fun setPersonalisation(v: Float) {
        _state.value = _state.value.copy(personalisation = v)
        if (_state.value.searchHits.isNotEmpty()) reorderSearch()
    }

    /** Re-orders the results already fetched. Moving the slider must not re-query arXiv. */
    private fun reorderSearch() {
        val st = _state.value
        viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) {
                SearchRanker.rank(
                    results = st.searchHits.map { it.paper },
                    query = st.searchQuery,
                    rated = ratedDocs(),
                    personalisation = st.personalisation,
                )
            }
            _state.value = _state.value.copy(searchHits = hits)
        }
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
                        negativePool = db.recentPapers(limit = 800).map { it.rankText },
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

    private fun ratedDocs(): List<RatedDoc> {
        val ratings = db.ratings()
        return db.papersById(ratings.keys).mapNotNull { p ->
            ratings[p.id]?.let { RatedDoc(p.id, p.rankText, it) }
        }
    }

    fun openDetail(paper: Paper) {
        _state.value = _state.value.copy(detail = paper)
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
