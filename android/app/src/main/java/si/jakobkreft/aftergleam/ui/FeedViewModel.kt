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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import si.jakobkreft.aftergleam.data.ArxivApi
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.BioRxivApi
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Bridge
import si.jakobkreft.aftergleam.data.Drift
import si.jakobkreft.aftergleam.data.Dwell
import si.jakobkreft.aftergleam.data.Backup
import si.jakobkreft.aftergleam.data.Resurfaced
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.CrossrefSearch
import si.jakobkreft.aftergleam.data.Evidence
import si.jakobkreft.aftergleam.data.FetchPlan
import si.jakobkreft.aftergleam.data.Signal
import si.jakobkreft.aftergleam.data.Support
import si.jakobkreft.aftergleam.data.Fetcher
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.LibraryImport
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.PdfStore
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.ShownItem
import si.jakobkreft.aftergleam.data.Taste
import si.jakobkreft.aftergleam.data.Topics
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.DigestBuilder
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Scored
import si.jakobkreft.aftergleam.rank.SearchRanker
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.rank.Weights
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Where a search looks.
 *
 * Labels sit in a row of chips, so they have to be short enough to read at a glance. "Saved
 * and reacted to" described the scope accurately and was unreadable at that size.
 */
enum class SearchScope(val label: String) {
    // Every server the app reads, not only arXiv. See [CrossrefSearch].
    ONLINE("Online"),
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
    /**
     * Servers that did not answer during the last fetch.
     *
     * Empty means the fetch worked and the day really is quiet. Non-empty means the screen
     * must not call it a quiet day, because it does not know.
     */
    val fetchFailures: List<String> = emptyList(),
    /** The servers this reader's subjects actually use, for saying whose quiet day it is. */
    val activeSources: List<String> = emptyList(),
    val categories: Set<String> = emptySet(),
    val onboarded: Boolean = false,
    /** Papers carrying any signal at all. What the model actually learns from. */
    val ratedCount: Int = 0,
    /**
     * Papers the reader explicitly judged.
     *
     * Separate from [ratedCount] because they are separate claims. The digest header said
     * "87 papers you have reacted to" while the library shelf, two taps away, said 62: the
     * first was counting everything opened, downloaded or saved, the second only the button
     * presses. Both numbers were right and one of them was lying about what it counted.
     */
    val judgedCount: Int = 0,
    val modelActive: Boolean = false,
    val importProgress: LibraryImport.Progress? = null,
    val importSummary: String? = null,
    /**
     * Set from the moment a file is chosen until the reader dismisses the outcome.
     *
     * The import owns the whole screen for that whole time. It is a single flag rather than
     * "progress is not null" because there is a gap at each end, opening the file and
     * reading the result, where there is no progress to report and leaving the screen would
     * still lose the work.
     */
    val importing: Boolean = false,
    val importResult: LibraryImport.Result? = null,
    val saved: List<Paper> = emptyList(),
    val downloaded: List<Paper> = emptyList(),
    /** Bytes each downloaded paper occupies, so the reader can see what to reclaim. */
    val downloadedBytes: Map<String, Long> = emptyMap(),
    /** Papers being fetched from the library rather than by opening the reader. */
    val downloading: Set<String> = emptySet(),
    /** A short line under the shelf chips, for the things that can go wrong out of sight. */
    val libraryMessage: String? = null,
    val ratedPapers: List<Pair<Paper, Float>> = emptyList(),
    val evidence: Map<String, Evidence> = emptyMap(),
    val attention: Map<String, Int> = emptyMap(),
    val resurfaced: Resurfaced? = null,
    val backupSummary: String? = null,
    val detail: Paper? = null,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchHits: List<SearchRanker.Hit> = emptyList(),
    /**
     * Matches from papers already on the device, shown before arXiv has answered.
     *
     * Kept separate from [searchHits] rather than merged into one list, because merging
     * would mean re-sorting when the network lands and the reader would watch the thing
     * they were about to tap move somewhere else. These stay put.
     */
    val searchLocalHits: List<SearchRanker.Hit> = emptyList(),
    /** How many further device matches there are beyond the few shown. */
    val searchLocalMore: Int = 0,

    /** The last day a digest was built, when that was before today. */
    val missedSince: String? = null,
    /** Papers announced since then that no digest ever showed. */
    val missedCount: Int = 0,
    val pastOpen: Boolean = false,
    val pastDays: List<Db.DigestDay> = emptyList(),
    /** The best of what was missed, ranked on demand. */
    val catchUp: List<Scored> = emptyList(),
    val catchUpLoading: Boolean = false,
    /** A single past day being replayed exactly as it was shown. */
    val pastDay: String? = null,
    val pastCards: List<Scored> = emptyList(),
    val searchError: String? = null,
    val personalisation: Float = 0.5f,
    val searchScope: SearchScope = SearchScope.ONLINE,
    val drift: Drift.Report? = null,
    /** The reader's keywords. See [Keywords]. */
    val keywords: List<String> = emptyList(),
    /** Recent papers on the device mentioning each keyword, once its fetch is done. */
    val keywordCounts: Map<String, Int> = emptyMap(),
    /** The note that ends today's digest, if it is due. See [Support]. */
    val support: Support.Card? = null,
    /** False in a copy installed from Google Play, which asks for a rating instead of money. */
    val donationsAllowed: Boolean = false,
    val supportReminder: Boolean = true,
    val explore: List<Scored> = emptyList(),
    val exploreLoading: Boolean = false,
    val popular: List<Paper> = emptyList(),
    /** Why Popular has nothing in it, which is not always the same reason. */
    val popularStatus: PopularStatus = PopularStatus.LOADING,
    /**
     * True when Explore has nothing left to offer beyond today's digest.
     *
     * Without it the "More papers" button stayed on screen after the last card, doing
     * nothing when tapped, and every visit to the tab re-ranked eight hundred candidates to
     * arrive at the same empty list.
     */
    val exploreExhausted: Boolean = false,
    val searchOpen: Boolean = false,
    val theme: String = "system",
    val paperSerif: Boolean = true,
    val interfaceSerif: Boolean = false,
    val dynamicColour: Boolean = false,
    /**
     * Notification settings live in state rather than being read once into the settings
     * screen, so that a switch the app refuses to turn on visibly does not turn on. Android
     * can decline the permission without any dialog at all once the reader has said no
     * twice, and a switch that flips anyway would be reporting a setting that does nothing.
     */
    val notifyEnabled: Boolean = false,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 19,
    val survey: SurveyState = SurveyState(),
    val topics: Set<String> = emptySet(),
    val reading: Paper? = null,
    val readingFile: java.io.File? = null,
    val readingError: String? = null,
    /**
     * A download the in-app reader cannot render, waiting to be handed to another app.
     *
     * Preprint servers serve whatever the author uploaded, and the Law Archive really does
     * serve Word documents. That one arrived as a .docx, and because the reader only knew
     * how to show PDFs it showed its loading state and never stopped.
     */
    val readingUnsupported: java.io.File? = null,
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
    /**
     * Papers an imported library already contributed, which count towards [enough].
     *
     * Somebody who imports two hundred read papers has told the ranker far more than twelve
     * survey taps could, and being asked to keep three of them anyway, then told it was a
     * thin start, is the app failing to notice what it was just handed.
     */
    val seeded: Int = 0,
    /** Servers that did not answer while the survey was fetching, to say why it is empty. */
    val unreachable: List<String> = emptyList(),
) {
    val canGoBack: Boolean get() = history.isNotEmpty()
    val started: Boolean get() = expected > 0
    /** Out of papers and none still coming. */
    val done: Boolean get() = deck.isEmpty() && !loading && started
    /** Answered everything fetched so far, but more is on the way. */
    val waiting: Boolean get() = deck.isEmpty() && loading
    /** Three liked papers is where ranking switches on, so that is the honest target. */
    val enough: Boolean get() = liked.size + seeded >= Ranker.MIN_RATINGS

    /**
     * How many cards this survey will ask about in all, or 0 while that is not yet known.
     *
     * Usually twelve, and known from the first card, because subjects are fetched while
     * they are being chosen. A field with few papers gets a shorter deck, and that is only
     * certain once its fetch has finished; until then no number is shown rather than one
     * that later shrinks.
     */
    val planned: Int get() = when {
        !started -> 0
        !loading -> seen + deck.size
        seen + deck.size >= expected -> expected
        else -> 0
    }
}

/**
 * The three ways Popular can be empty, which need three different things said about them.
 *
 * "This fills in once a digest has been fetched" was printed for all of them. For a reader
 * who follows law that sentence is simply false: the digest had been fetched, it worked, and
 * Popular will still be empty tomorrow, because the signal it ranks by does not cover their
 * field. A screen that explains its emptiness with something the reader can disprove is
 * worse than one that says nothing.
 */
enum class PopularStatus {
    LOADING,

    /** Ranked papers are on screen. */
    READY,

    /** Nothing has been fetched yet, so the old sentence was the true one. */
    NO_PAPERS,

    /**
     * Papers are here, and not one of them carries a signal this surface can rank by.
     *
     * Popular is upvotes on the Hugging Face daily list, which covers arXiv and leans
     * heavily towards machine learning, plus conference acceptances read off arXiv comments
     * and bioRxiv's journal field. OSF and ChemRxiv publish neither, and neither does most
     * of arXiv outside the machine learning corner. For those readers this is permanent, and
     * saying so is the only useful thing the screen can do.
     */
    NO_SIGNAL,
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** How many more papers an "explore" page adds. */
        const val EXPLORE_PAGE = 30

        /**
         * How many papers the survey asks about.
         *
         * Enough to place somebody in their field, few enough to stay under a minute. The
         * reader can stop at any point and the answers so far still count.
         */
        const val SURVEY_CARDS = 12

        /**
         * Papers the survey leaves for the first digest, when there are that few to begin
         * with. Ignored by any field that posts more than a handful a day, which is all of
         * the ones the survey was originally sized against.
         */
        const val SURVEY_RESERVE = 5

        /** How long a subject selection must hold still before it is worth fetching. */
        const val SELECTION_SETTLE_MS = 1_200L

        /** How long survey answers must hold still before the first digest is prepared. */
        const val SURVEY_SETTLE_MS = 1_200L

        /** Popularity and outside papers fetched this recently are not fetched again. */
        const val EXTRAS_FRESH_MS = 30 * 60 * 1000L

        /** The catch-up is a digest, not an inbox: a morning's reading, not a backlog. */
        const val CATCH_UP_SIZE = 25

        /** Sentinel for the catch-up list, which is not a single day. */
        const val CATCH_UP = "catch-up"

        /**
         * Device matches shown above the arXiv results.
         *
         * Small on purpose. This block is context, not the answer: it says "you already have
         * these" so nobody re-reads a paper they judged last month, and anyone who wants the
         * full list of what is on the device has a chip for exactly that. A long block here
         * would push the arXiv results, which is what the reader asked for, off the screen.
         */
        const val DEVICE_HITS = 6
    }


    private val db = Db(app)
    private val prefs = Prefs(app)

    /**
     * The trained model, kept between rebuilds.
     *
     * Training took the better part of the nine seconds a rebuild costs. It depends on the
     * reader's signals and, through its vocabulary, on which papers are the newest, so the
     * signature is the ledger plus the newest paper: any new reaction or any fetch that
     * brings papers invalidates it, and a re-rank with neither reuses it.
     */
    private var cachedModel: Ranker.Model? = null
    private var cachedSignature: String? = null

    /**
     * The interest model, trained once and reused until the reader reacts to something.
     *
     * Both the digest and search want the same thing, and it depends only on the ledger.
     */
    /**
     * Fills in everything the reader has not asked for yet, while they read what they did.
     *
     * The digest is on screen at this point and the phone is otherwise idle. Popular is a
     * sort over what is already stored and costs almost nothing; Explore ranks eight hundred
     * candidates and is the slowest screen in the app to open cold. Both are computed here
     * so that the tab bar is instant, and both are cheap to throw away if the reader never
     * touches them.
     *
     * Ordered deliberately: the model first, because Explore ranks with it and would
     * otherwise train its own copy.
     */
    private fun prewarm() {
        viewModelScope.launch {
            warmModelNow()
            if (_state.value.popular.isEmpty()) loadPopular()
            if (_state.value.explore.isEmpty()) loadExplore()
        }
    }

    /**
     * Trains the interest model in the background once the digest is already on screen.
     *
     * Reopening the app restores the stored digest without training anything, which is why
     * it is fast. The bill arrives later, at whatever first needs a model: a search, or the
     * catch-up list, each paying ten seconds or more for work that had nothing to do with
     * what the reader just asked for. Doing it here spends that time while they are reading
     * the first card, and both are instant when they get there.
     */
    private fun warmModel() {
        viewModelScope.launch { warmModelNow() }
    }

    private suspend fun warmModelNow() {
        if (cachedModel != null) return
        withContext(Dispatchers.Default) {
            val rated = ratedDocs()
            if (rated.size >= Ranker.MIN_RATINGS) ensureModelShared(rated)
        }
    }

    /**
     * Serialises model fitting.
     *
     * The background warm-up and whatever the reader opens next both want a model, and
     * without this they each trained their own: tapping Explore the moment the digest landed
     * paid for two fits at nine seconds each, one of which was thrown away. Now the second
     * caller waits for the first and takes its result.
     */
    private val modelLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun ensureModelShared(rated: List<RatedDoc>): Ranker.Model? =
        modelLock.withLock { ensureModel(rated) }

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
            .hashCode().toString() + "@" + db.recentPapers(limit = 1).firstOrNull()?.id.orEmpty()

    private val _state = MutableStateFlow(
        FeedState(
            categories = prefs.categories,
            onboarded = prefs.onboarded,
            theme = prefs.theme,
            topics = prefs.seedTopics,
            donationsAllowed = Support.donationsAllowed(app),
            keywords = prefs.keywords,
            supportReminder = prefs.supportReminder,
        )
    )
    val state: StateFlow<FeedState> = _state.asStateFlow()

    /**
     * Whether to open on Popular rather than on the digest.
     *
     * True exactly when the digest is not ready and For You would therefore be a screen of
     * placeholder cards: the first launch of a day, before that day's papers have been
     * fetched and ranked. Popular is a sort over what is already stored, so it is on screen
     * immediately and is real reading rather than a promise of some.
     *
     * Deliberately decided once, here, and never revisited. Moving somebody to another tab
     * because a background job finished would be worse than the wait it saves, and the
     * digest announces itself in the tab bar without help. It also cannot fire straight
     * after onboarding, because that path does not construct a view model.
     */
    val openOnPopular: Boolean =
        prefs.onboarded && db.digestFor(LocalDate.now().toString()).isEmpty()

    init {
        _state.value = _state.value.copy(
            notifyEnabled = prefs.notifyEnabled,
            reminderEnabled = prefs.reminderEnabled,
            reminderHour = prefs.reminderHour,
            paperSerif = prefs.paperSerif,
            interfaceSerif = prefs.interfaceSerif,
            dynamicColour = prefs.dynamicColour,
        )
        if (prefs.onboarded) {
            // Before anything else, so a cold start with no fetch due still knows what the
            // field was reading. Popular has nothing else to rank by, and ranking it before
            // this line would quietly demote it to venue matches.
            _state.value = _state.value.copy(attention = db.attention())
            // Popular is the landing screen when the digest is not ready, and nothing else
            // would fill it in time: the usual precompute waits for a digest that is, by
            // definition, not there yet.
            if (openOnPopular) loadPopular()
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
    /**
     * Fills the survey from the reader's own feed, in one request per server.
     *
     * This used to ask arXiv for one paper per topic, twelve times, three seconds apart,
     * because the published rate limit is one request every three seconds. Thirty-six
     * seconds of asking to obtain twelve papers, and the deck filled more slowly than
     * anybody answers, so a reader who was quick simply ran out of cards and waited.
     *
     * The papers were already available. `recent` returns three hundred across every
     * subscribed category in a single request, which is exactly the pull the digest makes
     * a moment later, so the survey now draws from that and the whole deck exists at once.
     * One request replaces thirteen, and the same download then serves the digest, Explore
     * and Popular without being asked for twice.
     *
     * The deck is refilled as each server lands rather than at the end: arXiv answers in a
     * second or two and bioRxiv takes ten, and there is no reason to look at a spinner for
     * the second while the first is already on the device.
     */
    fun startSurvey() {
        val sv = _state.value.survey
        if (sv.started || sv.loading) return

        val probes = Taste.probesFor(prefs.seedTopics)
        if (probes.isEmpty()) return
        _state.value = _state.value.copy(
            survey = SurveyState(
                loading = true,
                expected = SURVEY_CARDS,
                // Carried across the reset: the library was imported before this point.
                seeded = _state.value.survey.seeded,
            )
        )

        viewModelScope.launch {
            val seeded = Topics.categoriesFor(prefs.seedTopics)
            if (seeded.isEmpty()) return@launch

            // Whatever the warm-up already brought in, before waiting on anything.
            fillDeck(probes)
            // It is usually still running or just finished; either way it is fetching the
            // same subjects, so join it rather than asking for them a second time.
            feedWarmUp?.join()
            fillDeck(probes)

            val missing = seeded - prefs.fetchedCategories
            if (missing.isNotEmpty()) {
                runCatching { fetchInto(missing, onStored = { fillDeck(probes) }) }
            }
            prefetched = seeded
            if (_state.value.onboarded) return@launch

            fillDeck(probes)
            val cur = _state.value.survey
            _state.value = _state.value.copy(
                survey = cur.copy(
                    loading = false,
                    failed = cur.deck.isEmpty(),
                    unreachable = _state.value.fetchFailures,
                )
            )
        }
    }

    /**
     * Draws a deck from what is stored, one paper per topic in turn.
     *
     * Round-robin rather than in order, so the questions alternate subject from the first
     * card. A reader who chose four subjects and is shown four papers from the busiest one
     * learns nothing about the other three, and neither does the model.
     */
    private suspend fun fillDeck(probes: List<Taste.Probe>) {
        val current = _state.value.survey

        val pools = withContext(Dispatchers.IO) {
            probes.map { probe ->
                // Past the deck's size by the reserve, so a busy field can be told from a
                // small one. Read at exactly the deck's size, computer vision looked like a
                // field of twelve papers, the reserve came off that, and a reader who chose it
                // was asked about seven.
                probe to db.papersInCategory(
                    Source.qualify(probe.source, probe.category),
                    limit = SURVEY_CARDS + SURVEY_RESERVE,
                )
            }
        }

        // Everything already dealt, answered ones included. Only the liked ones used to be
        // remembered, so a paper answered "Not for me" could be dealt again when more arrived.
        val seen = (current.deck + current.history + current.liked).map { it.second.id }
            .toMutableSet()
        val deck = current.deck.toMutableList()

        // How many cards this deck is allowed to grow to.
        //
        // The survey draws from the same papers the first digest will. In a field that posts
        // hundreds a day that costs nothing, and it was the only case this was ever built
        // for. A reader who follows law has about six papers a fortnight: the survey asked
        // about all six, every one became finished business, and "Show me today" opened on
        // an empty digest on the reader's first morning. The survey needs three keepers to
        // switch ranking on, and beyond that it can afford to leave the rest alone.
        val available = pools.flatMap { it.second }.distinctBy { it.id }.size
        // The total, answered cards included. Counting only the cards still to come let a deck
        // refill as it was answered: papers from a second server arriving late took a survey
        // of twelve to eighteen while the reader was on the seventh.
        val cap = (minOf(
            SURVEY_CARDS,
            maxOf(Ranker.MIN_RATINGS, available - SURVEY_RESERVE),
        ) - current.seen).coerceAtLeast(0)

        var depth = 0
        while (deck.size < cap && pools.any { depth < it.second.size }) {
            for ((probe, pool) in pools) {
                if (deck.size >= cap) break
                val paper = pool.getOrNull(depth) ?: continue
                if (!seen.add(paper.id)) continue
                deck += probe to paper
            }
            depth++
        }
        if (deck.map { it.second.id } != current.deck.map { it.second.id }) {
            _state.value = _state.value.copy(
                survey = _state.value.survey.copy(deck = deck)
            )
        }
    }

    /** Categories already pulled by the onboarding prefetch, so finishing does not repeat them. */
    private var prefetched: Set<String> = emptySet()

    /** Answers the top card. Liked papers become training data straight away. */
    fun answerSurvey(liked: Boolean) {
        val sv = _state.value.survey
        val head = sv.deck.firstOrNull() ?: return
        // Into the ledger, which is the only thing the model reads. This used to write the
        // legacy `interest` column instead, so a reader who finished onboarding taught the
        // ranker precisely nothing and the library showed their answers with neither chip lit.
        db.addSignal(head.second.id, if (liked) Signal.LIKED else Signal.DISLIKED)
        invalidateModel()
        surveyAnswers.value++
        _state.value = _state.value.copy(
            survey = sv.copy(
                deck = sv.deck.drop(1),
                liked = if (liked) sv.liked + head else sv.liked,
                seen = sv.seen + 1,
                history = sv.history + head,
            ),
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
            judgedCount = judgedCount(),
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
        surveyAnswers.value++
        _state.value = _state.value.copy(
            survey = sv.copy(
                deck = listOf(last) + sv.deck,
                liked = sv.liked.filterNot { it.second.id == last.second.id },
                seen = (sv.seen - 1).coerceAtLeast(0),
                history = sv.history.dropLast(1),
            ),
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
            judgedCount = judgedCount(),
        )
    }

    /** Records the chosen topics and their categories, before any papers are fetched. */
    fun setTopics(keys: Set<String>) {
        prefs.seedTopics = keys
        val cats = Topics.categoriesFor(keys)
        if (cats.isNotEmpty()) prefs.categories = cats
        _state.value = _state.value.copy(categories = cats, topics = keys)
        if (!prefs.onboarded) warmFeed(cats)
    }

    /** Cancelled and restarted as the selection changes, so only the final choice is fetched. */
    private var feedWarmUp: Job? = null

    /** Written from the main thread, read from the import's IO loop. */
    @Volatile
    private var stopImportRequested = false

    /**
     * Starts pulling the feed while the reader is still choosing what to follow.
     *
     * Picking subjects takes ten or twenty seconds of expanding fields and reading names,
     * and until now the app spent every one of them idle and then made the reader wait for
     * a fetch it could have finished already. The survey draws from this, so by the time
     * they ask for papers the papers are usually here.
     *
     * Debounced rather than fired on each tap: somebody ticking four subjects in quick
     * succession should produce one request for four subjects, not four requests. Fetching
     * a subject that is then unticked is no loss, since the papers are stored either way
     * and cost nothing until a digest asks for them.
     */
    private fun warmFeed(categories: Set<String>) {
        feedWarmUp?.cancel()
        if (categories.isEmpty()) return
        feedWarmUp = viewModelScope.launch {
            delay(SELECTION_SETTLE_MS)
            runCatching { fetchInto(categories) }
        }
    }

    /**
     * Categories from the topics chosen, widened by what the liked papers turned out to be
     * cross-listed under. A paper found under "generative models" is often filed somewhere
     * more useful than the probe that surfaced it.
     */
    private fun surveyCategories(sv: SurveyState): Set<String> =
        Topics.categoriesFor(prefs.seedTopics) +
            Taste.categoriesFrom(sv.liked.map { it.second }, sv.liked.map { it.first })

    /** Bumped on every survey answer, so the first digest is prepared once answers settle. */
    private val surveyAnswers = MutableStateFlow(0)

    /** Held while the first digest is being prepared, so finishing waits for it. */
    private val prepLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Gets the first digest ready while the reader is still answering.
     *
     * Finishing the survey used to start all the work: fetching the subjects the answers
     * pointed to, the popularity list, the papers for the "outside your usual" card, then
     * training, then ranking. On a phone that was most of eight seconds after the last tap,
     * and when nothing new needed fetching the extras were skipped altogether, so the first
     * digest had no "outside your usual" card. Each step here is done once the answers have
     * been still for a moment, so finishing finds the papers on the device and the model
     * trained, and only has to rank.
     *
     * Collected rather than collected-latest: work already under way is never cancelled,
     * and an answer given during it simply causes one more pass afterwards.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val firstDigestPrep = viewModelScope.launch {
        surveyAnswers.drop(1).debounce(SURVEY_SETTLE_MS).collect {
            prepLock.withLock { prepareFirstDigest() }
        }
    }

    private suspend fun prepareFirstDigest() {
        if (prefs.onboarded) return
        val cats = surveyCategories(_state.value.survey)
        val missing = cats - prefs.fetchedCategories
        if (missing.isNotEmpty()) runCatching { fetchInto(missing) }
        if (extrasStale()) runCatching { fetchExtras(cats) }
        withContext(Dispatchers.Default) { ensureModelShared(ratedDocs()) }
    }

    /** Finishes onboarding using what the survey learned. */
    fun finishSurvey() {
        val sv = _state.value.survey
        val cats = surveyCategories(sv)
        prefs.categories = cats
        prefs.onboarded = true
        val reactions = db.allReactions()
        _state.value = _state.value.copy(
            categories = cats,
            onboarded = true,
            reactions = reactions,
            evidence = db.evidence(),
            ratedCount = evidenceCount(),
            judgedCount = judgedCount(),
            modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
        )
        sync(force = true, firstDigest = true)
    }

    fun setCategories(cats: Set<String>) {
        prefs.categories = cats
        _state.value = _state.value.copy(categories = cats)
    }

    fun finishOnboarding() {
        prefs.onboarded = true
        _state.value = _state.value.copy(onboarded = true)
        sync(force = true, firstDigest = true)
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
                    judgedCount = judgedCount(),
                    modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
                    resurfaced = resurfaced,
                    drift = withContext(Dispatchers.IO) { computeDrift() },
                    support = withContext(Dispatchers.IO) { supportCard() },
                )
                withContext(Dispatchers.IO) { refreshCatchUp() }
                prewarm()
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

    /**
     * Pulls [subscribed] from every server it spans and stores the result.
     *
     * Shared by the digest and by the prefetch that runs during onboarding, so the two
     * cannot drift into fetching different things. Reports progress through [label] when the
     * caller is a screen the reader is watching, and says nothing when it is not.
     */
    private suspend fun fetchInto(
        subscribed: Set<String>,
        label: ((String) -> Unit)? = null,
        /**
         * Called after each server's papers are stored, before the next is asked.
         *
         * Lets a caller use what has arrived rather than waiting for the slowest server.
         * arXiv answers one request in a second or two; bioRxiv pages and takes ten. A
         * reader who follows both should be looking at arXiv papers during those ten.
         */
        onStored: (suspend () -> Unit)? = null,
    ): Int {
        var total = 0

        suspend fun store(papers: List<Paper>) {
            if (papers.isEmpty()) return
            total += papers.size
            withContext(Dispatchers.IO) { db.upsertPapers(papers) }
            onStored?.invoke()
        }

        val outcome = Fetcher.fetch(subscribed, label = { label?.invoke(it) }) { store(it) }
        // Recorded so an empty digest can say which of the two it is: a quiet day, or a
        // server that did not answer. They look identical from here otherwise.
        _state.value = _state.value.copy(
            fetchFailures = outcome.failed,
            activeSources = Fetcher.serversFor(subscribed).map { Source.label(it) }.sorted(),
        )
        prefs.lastFetchMillis = System.currentTimeMillis()
        // Only subjects whose server answered. Marking all of them recorded a refused request
        // as a fetch: arXiv answered "Rate exceeded" during onboarding, computer vision was
        // marked as fetched with nothing stored, the survey had nothing to ask about, and the
        // next digest did not ask again because the subject looked already fetched.
        prefs.fetchedCategories = prefs.fetchedCategories +
            subscribed.filter { Source.label(Source.of(it)) !in outcome.failed }
        return total
    }

    private fun sync(
        force: Boolean,
        networkAllowed: Boolean = true,
        /** The digest at the end of onboarding, which the survey may have half prepared. */
        firstDigest: Boolean = false,
    ) {
        val cats = _state.value.categories.toList()
        if (cats.isEmpty()) return

        // What is actually worth asking for.
        //
        // Asking again for what arXiv has not re-announced returns the same papers, and on
        // this device that cost six seconds for arXiv alone and half a minute with bioRxiv
        // and medRxiv on. Refusing the trip and saying so is faster, and more honest, than
        // spending it to arrive back where we started. A subject ticked since the last fetch
        // is the exception: it has no papers here yet, so it is pulled on its own rather
        // than dragging every other subject along with it.
        fun plan() = FetchPlan.decide(
            subscribed = cats.toSet(),
            alreadyFetched = prefs.fetchedCategories,
            announced = prefs.fetchIsStale(),
            recent = prefs.fetchedRecently(),
            forced = force,
            networkAllowed = networkAllowed,
        )
        val early = plan()
        _state.value = _state.value.copy(
            loading = true,
            loadingLabel = when {
                // "Nothing new announced" means nothing to somebody who has just arrived.
                firstDigest -> "Building your first digest"
                early.isNotEmpty() -> "Fetching from arXiv"
                force -> "Nothing new announced, re-ranking what you have"
                else -> "Re-ranking"
            },
            error = null,
            emptyDay = false,
        )

        viewModelScope.launch {
            try {
                // The survey may still be fetching what its answers pointed to, or training.
                // Waiting for it costs nothing it was not going to cost anyway, and asking
                // the servers a second time for the same subjects would.
                if (firstDigest) prepLock.withLock { }
                val toFetch = if (firstDigest) plan() else early
                val shouldFetch = toFetch.isNotEmpty()
                if (shouldFetch) {
                    val n = fetchInto(
                        subscribed = toFetch,
                        label = { msg -> _state.value = _state.value.copy(loadingLabel = msg) },
                    )
                    _state.value = _state.value.copy(
                        loadingLabel = "Got $n papers, checking what is popular"
                    )
                }
                val keywords = prefs.keywords
                val keywordsDue = if (shouldFetch) keywords else keywords - prefs.keywordsFetched
                if (networkAllowed && keywordsDue.isNotEmpty()) {
                    _state.value = _state.value.copy(loadingLabel = "Looking for your keywords")
                    runCatching { fetchKeywordsInto(keywordsDue, fresh = shouldFetch) }
                }
                if (networkAllowed && (shouldFetch || (firstDigest && extrasStale()))) {
                    fetchExtras(cats) { msg -> _state.value = _state.value.copy(loadingLabel = msg) }
                }
                // Keep the explanation when there was nothing to fetch. Overwriting it with
                // "Ranking" meant the one message that answers "why is this so quick, did it
                // even check" was replaced before anybody could read it.
                if (shouldFetch) {
                    _state.value = _state.value.copy(loadingLabel = "Ranking")
                }
                withContext(Dispatchers.Default) {
                    // Through the shared lock, so a model the survey is still training is
                    // waited for and reused rather than trained a second time alongside it.
                    ensureModelShared(ratedDocs())
                    rebuild(cats)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not reach arXiv",
                )
            }
        }
    }

    /**
     * The newest papers mentioning [keywords], stored, and the keywords recorded as fetched.
     *
     * @param fresh true when this is the day's full fetch, which starts the record over so
     *   that every keyword is asked again once something new has been announced.
     */
    private suspend fun fetchKeywordsInto(keywords: List<String>, fresh: Boolean = false) {
        val done = Fetcher.fetchKeywords(keywords) { papers ->
            if (papers.isNotEmpty()) withContext(Dispatchers.IO) { db.upsertPapers(papers) }
        }
        prefs.keywordsFetched = (if (fresh) emptySet() else prefs.keywordsFetched) + done
    }

    /** Adds a keyword as written. The reader has already seen any spelling question. */
    fun addKeyword(raw: String) {
        val keyword = Keywords.normalise(raw) ?: return
        val current = prefs.keywords
        if (current.size >= Keywords.MAX || current.any { it.equals(keyword, ignoreCase = true) }) return
        setKeywords(current + keyword)
    }

    fun removeKeyword(keyword: String) = setKeywords(prefs.keywords - keyword)

    /** Edits in progress: the next change cancels this and starts it again. */
    private var keywordChange: Job? = null

    /**
     * Records the keywords and, once the reader stops editing for a moment, fetches any new
     * keyword's papers and re-ranks today's digest, so the change shows where it matters.
     */
    private fun setKeywords(keywords: List<String>) {
        prefs.keywords = keywords
        prefs.keywordsFetched = prefs.keywordsFetched.intersect(keywords.toSet())
        _state.value = _state.value.copy(keywords = keywords)
        refreshKeywordCounts()
        keywordChange?.cancel()
        keywordChange = viewModelScope.launch {
            delay(SELECTION_SETTLE_MS)
            val due = keywords - prefs.keywordsFetched
            if (due.isNotEmpty()) runCatching { fetchKeywordsInto(due) }
            refreshKeywordCounts()
            if (prefs.onboarded) rerank()
        }
    }

    /** Words the papers on this device use, for questioning a likely misspelling. */
    private var vocabulary: Map<String, Int>? = null
    private var vocabularyKey: String? = null
    private val vocabularyLock = kotlinx.coroutines.sync.Mutex()

    /**
     * The vocabulary, rebuilt first if papers have arrived since it was made.
     *
     * Waited for rather than read as it stands: "satelite" once went in unquestioned on a
     * phone that by then held a paper about satellites, because the rebuild was still running
     * when Enter was pressed.
     */
    private suspend fun currentVocabulary(): Map<String, Int> = vocabularyLock.withLock {
        withContext(Dispatchers.Default) {
            val key = db.recentPapers(limit = 1).firstOrNull()?.id.orEmpty()
            vocabulary?.takeIf { key == vocabularyKey } ?: run {
                val subjects = Topics.FIELDS.flatMap { it.topics }.map { "${it.label} ${it.seed}" }
                Keywords.vocabulary(
                    db.recentPapers(limit = 1500).asSequence().map { it.rankText } +
                        subjects.asSequence()
                ).also { vocabulary = it; vocabularyKey = key }
            }
        }
    }

    /** Builds the vocabulary ahead of need, so the check is instant when a keyword is added. */
    fun prepareSpelling() {
        viewModelScope.launch { currentVocabulary() }
        refreshKeywordCounts()
    }

    /**
     * How many recent papers mention each keyword, for the settings page.
     *
     * Only keywords already fetched: a new one counted while its papers are on the way would
     * read "none" for a few seconds. A keyword that really finds none says so, since nothing
     * else would: "satellite photos" mentioned in no paper on a phone holding eighteen about
     * earth observation, because papers say images and imagery.
     */
    private fun refreshKeywordCounts() {
        val keywords = prefs.keywords.filter { it in prefs.keywordsFetched }
        viewModelScope.launch {
            val since = LocalDate.now().minusDays(Keywords.WINDOW_DAYS).toString()
            val counts = withContext(Dispatchers.IO) {
                keywords.associateWith { db.keywordCandidates(listOf(it), since).size }
            }
            _state.value = _state.value.copy(keywordCounts = counts)
        }
    }

    /** A respelling for the reader to accept or decline, or null. Never applied by itself. */
    suspend fun keywordSuggestion(keyword: String): String? =
        Keywords.suggest(keyword, currentVocabulary())

    /** When popularity and the papers for the "outside your usual" card last came in. */
    private var extrasAt = 0L

    private fun extrasStale(): Boolean =
        System.currentTimeMillis() - extrasAt > EXTRAS_FRESH_MS

    /**
     * What the field is reading today, and a handful of papers from outside the reader's
     * fields for the one card that comes from there.
     *
     * Enrichment only. A failure in either leaves the digest exactly as it would have been
     * without it.
     */
    private suspend fun fetchExtras(cats: Collection<String>, label: ((String) -> Unit)? = null) {
        val hot = Attention.fetch()
        if (hot.isNotEmpty()) {
            withContext(Dispatchers.IO) { db.saveAttention(hot) }
            _state.value = _state.value.copy(attention = _state.value.attention + hot)
        }

        // One extra request for the bridge slot. Without a pool from outside the user's
        // categories there is nothing for that slot to choose from, which is why it never
        // fired. Failure is silently fine: the slot just stays empty and the digest backfills.
        label?.invoke("Looking outside your fields")
        val outside = Bridge.candidatesFor(
            subscribed = cats.toSet(),
            dayOfYear = LocalDate.now().dayOfYear,
        )
        if (outside.isNotEmpty()) {
            val across = mutableListOf<Paper>()
            // A smaller ask than the daily fetch: this is one card's worth of somewhere
            // else, not a second digest.
            Fetcher.fetch(outside.toSet(), arxivMax = 80, days = 2, osfPages = 1) { across += it }
            if (across.isNotEmpty()) withContext(Dispatchers.IO) { db.upsertPapers(across) }
        }
        extrasAt = System.currentTimeMillis()
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
        // Reuse the model whenever the ledger is unchanged, which is every re-rank that is
        // not preceded by a reaction.
        val rated = ratedDocs()
        val signature = modelSignature(rated)
        val built = DigestBuilder.build(
            db = db,
            prefs = prefs,
            attention = _state.value.attention,
            day = today,
            prebuilt = if (signature == cachedSignature) cachedModel else null,
        )
        val cards = built.cards
        cachedModel = built.model
        cachedSignature = signature
        val reactions = db.allReactions()
        _state.value = _state.value.copy(
            loading = false,
            cards = cards,
            // The digest just changed, so what is left over has changed with it. Dropping
            // both lets Explore look again; without this, a tab that had run out stayed run
            // out even after a fetch brought new papers in.
            explore = emptyList(),
            exploreExhausted = false,
            reactions = reactions,
            emptyDay = cards.isEmpty(),
            // `rated` includes the seed documents built from the chosen subjects, which the
            // ranker legitimately fits on but the reader never reacted to. Counting them
            // here made the header claim judgements that did not exist and switched the
            // model on before it had been taught anything.
            ratedCount = evidenceCount(),
            judgedCount = judgedCount(),
            modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
            resurfaced = findResurfaced(),
            drift = computeDrift(),
            support = supportCard(),
        )
        refreshCatchUp()
        prewarm()
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

    /** Whether today's digest ends with the support note. The thank-you stays for the session. */
    private fun supportCard(): Support.Card? {
        if (_state.value.support == Support.Card.THANKS) return Support.Card.THANKS
        val due = Support.due(
            enabled = prefs.supportReminder,
            readingDays = db.readingDays(),
            today = LocalDate.now(),
            quietUntil = prefs.supportQuietUntil,
        )
        return if (due) Support.Card.ASK else null
    }

    /** "Not now": away for a week, then back until it is answered. */
    fun supportLater() {
        prefs.supportQuietUntil = LocalDate.now().plusDays(Support.LATER_DAYS)
        _state.value = _state.value.copy(support = null)
    }

    /** "Don't ask again": the reminder is switched off, as it would be in settings. */
    fun supportNever() = setSupportReminder(false)

    /**
     * The reader acted on the request: followed the Ko-fi link, or on Google Play went to rate
     * or share the app, from the note or from settings.
     *
     * Whether anything came of it is not something the app can know. Somebody who went to
     * look has heard the request, so the note stays away for a year either way.
     */
    fun supportActed() {
        prefs.supportQuietUntil = LocalDate.now().plusDays(Support.QUIET_AFTER_ACTING_DAYS)
        val shown = _state.value.support
        _state.value = _state.value.copy(support = if (shown != null) Support.Card.THANKS else null)
    }

    fun setSupportReminder(on: Boolean) {
        prefs.supportReminder = on
        _state.value = _state.value.copy(
            supportReminder = on,
            support = if (on) _state.value.support else null,
        )
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
            judgedCount = judgedCount(),
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
                    judgedCount = judgedCount(),
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
        if (_state.value.importing) return
        stopImportRequested = false
        viewModelScope.launch {
            _state.value = _state.value.copy(
                importSummary = null,
                importResult = null,
                importing = true,
            )
            try {
                val result = LibraryImport.run(text, shouldStop = { stopImportRequested }) { p ->
                    _state.value = _state.value.copy(importProgress = p)
                }
                withContext(Dispatchers.IO) {
                    db.upsertPapers(result.papers)
                    result.papers.forEach { db.addSignal(it.id, Signal.LIKED) }
                }
                val reactions = db.allReactions()
                _state.value = _state.value.copy(
                    importProgress = null,
                    importResult = result,
                    survey = _state.value.survey.copy(
                        seeded = _state.value.survey.seeded + result.papers.size,
                    ),
                    reactions = reactions,
                    evidence = db.evidence(),
                    ratedCount = evidenceCount(),
                    judgedCount = judgedCount(),
                    modelActive = evidenceCount() >= Ranker.MIN_RATINGS,
                    importSummary = buildString {
                        append("Matched ${result.papers.size} of ${result.total}")
                        if (result.unmatched > 0) {
                            append(", ${result.unmatched} had no arXiv record")
                        }
                        if (result.failed > 0) append(", ${result.failed} could not be checked")
                        append(".")
                    },
                )
                if (_state.value.onboarded) rerank()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    importProgress = null,
                    // A failure still ends on the import screen, with a Continue button, so
                    // it is read rather than glimpsed on the way past.
                    importResult = LibraryImport.Result(emptyList(), 0, 0, 0),
                    importSummary = "Import failed: ${e.message}",
                )
            }
        }
    }

    /**
     * Asks the import to stop at the next entry.
     *
     * Not [kotlinx.coroutines.Job.cancel]: cancelling in the middle throws away every match
     * found so far, and the reader stopping a ten minute job wants out of the waiting, not
     * out of the results. The loop reads this between requests and returns what it has.
     */
    fun stopImport() {
        stopImportRequested = true
    }

    /** Leaves the import screen once the reader has read the outcome. */
    fun dismissImport() {
        _state.value = _state.value.copy(importing = false, importResult = null)
    }

    /** The three library shelves, read together off the main thread. */
    private data class Shelves(
        val saved: List<Paper>,
        val downloaded: List<Paper>,
        val rated: List<Pair<Paper, Float>>,
        val sizes: Map<String, Long>,
    )

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
                    (reactions.keys + ratings.keys + judged.keys).filter { store.isCached(it) }
                )
                val sizes = downloaded.associate { it.id to store.sizeOf(it.id) }
                Shelves(saved, downloaded, rated, sizes)
            }
            _state.value = _state.value.copy(
                saved = loaded.saved,
                downloaded = loaded.downloaded,
                ratedPapers = loaded.rated,
                downloadedBytes = loaded.sizes,
            )
        }
    }

    /**
     * Removes a downloaded PDF, and nothing else.
     *
     * A download is a cached copy of a paper, not an opinion about it, so reclaiming the
     * space leaves saves and reactions where they are. The row goes from the shelf here
     * rather than by reloading the library, so the list does not blink.
     */
    fun deleteDownload(paperId: String) {
        viewModelScope.launch {
            PdfStore(getApplication()).delete(paperId)
            _state.value = _state.value.copy(
                downloaded = _state.value.downloaded.filterNot { it.id == paperId },
                downloadedBytes = _state.value.downloadedBytes - paperId,
            )
        }
    }

    /**
     * Fetches a paper's PDF without opening it.
     *
     * The point of the offline shelf is a flight or a train, and preparing for one meant
     * opening every paper in turn and waiting for each to render. From the library a reader
     * can now line several up and leave them to it.
     *
     * No dwell timer is armed here, unlike the reader: fetching a file is not reading it,
     * and the download signal is worth 0.7 precisely because it means somebody stayed.
     */
    fun downloadInBackground(paper: Paper) {
        if (paper.id in _state.value.downloading) return
        _state.value = _state.value.copy(
            downloading = _state.value.downloading + paper.id,
            libraryMessage = null,
        )
        viewModelScope.launch {
            val store = PdfStore(getApplication())
            try {
                store.download(paper)
                val onShelf = _state.value.downloaded.any { it.id == paper.id }
                _state.value = _state.value.copy(
                    downloaded = if (onShelf) _state.value.downloaded
                    else _state.value.downloaded + paper,
                    downloadedBytes = _state.value.downloadedBytes +
                        (paper.id to store.sizeOf(paper.id)),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    libraryMessage = humanError(e, "fetch that PDF")
                )
            } finally {
                _state.value = _state.value.copy(
                    downloading = _state.value.downloading - paper.id
                )
            }
        }
    }

    fun clearLibraryMessage() {
        _state.value = _state.value.copy(libraryMessage = null)
    }

    /** Removes every download. Deleting forty of them one at a time is not a feature. */
    fun deleteAllDownloads() {
        viewModelScope.launch {
            PdfStore(getApplication()).deleteAll()
            _state.value = _state.value.copy(
                downloaded = emptyList(),
                downloadedBytes = emptyMap(),
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
    fun setReminderHour(h: Int) {
        prefs.reminderHour = h
        _state.value = _state.value.copy(reminderHour = h)
    }

    fun setReminderEnabled(v: Boolean) {
        prefs.reminderEnabled = v
        _state.value = _state.value.copy(reminderEnabled = v)
    }

    /**
     * Turns the evening reminder on, for the first grant of notification permission only.
     *
     * Somebody who has just said yes to notifications wants one, and the evening reminder is
     * the useful one: the digest is built in the morning and read when there is time.
     *
     * Deliberately has no "have we asked" guard of its own. It had one, and it made the
     * feature do nothing, because the permission callback marks the prompt as asked before
     * invoking its continuation and the guard then saw its own flag. The caller reaches this
     * only from the first-run branch, which is already behind that check.
     */
    fun enableEveningReminder() {
        setReminderEnabled(true)
        setReminderHour(prefs.reminderHour)
    }

    /** Records that the prompt has been shown, whatever the answer. */
    fun markNotificationsAsked() { prefs.notificationsAsked = true }

    fun notificationsAsked() = prefs.notificationsAsked
    fun setPaperSerif(v: Boolean) {
        prefs.paperSerif = v
        _state.value = _state.value.copy(paperSerif = v)
    }

    fun setInterfaceSerif(v: Boolean) {
        prefs.interfaceSerif = v
        _state.value = _state.value.copy(interfaceSerif = v)
    }

    fun setDynamicColour(v: Boolean) {
        prefs.dynamicColour = v
        _state.value = _state.value.copy(dynamicColour = v)
    }

    fun setTheme(mode: String) {
        prefs.theme = mode
        _state.value = _state.value.copy(theme = mode)
    }

    fun setDigestHour(h: Int) { prefs.digestHour = h }

    fun setNotifyEnabled(v: Boolean) {
        prefs.notifyEnabled = v
        _state.value = _state.value.copy(notifyEnabled = v)
    }

    /** Clears the model but keeps papers. Trust requires an exit. */
    fun resetModel() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.clearFeedback() }
            invalidateModel()
            _state.value = _state.value.copy(
                reactions = emptyMap(), evidence = emptyMap(), ratedPapers = emptyList(),
                ratedCount = 0, judgedCount = 0, modelActive = false,
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
            searchLocalHits = SearchRanker.reorder(st.searchLocalHits, v),
        )
    }

    /**
     * Runs a search, answering from the device first and from arXiv when it replies.
     *
     * An arXiv query is a network round trip against a one-request-every-three-seconds
     * budget, and it measured twelve to fifteen seconds on the device. Almost none of that
     * is the app: it is the wait for a server. But a reader searching for something they
     * have already read does not need that server at all, and the papers are sitting in
     * SQLite. So the device answers immediately and arXiv fills in underneath.
     *
     * The two are separate lists, appended to rather than merged, so nothing already on
     * screen moves when the network lands.
     */
    fun runSearch() {
        val q = _state.value.searchQuery.trim()
        if (q.length < 2) return
        _state.value = _state.value.copy(
            searching = true, searchError = null,
            searchHits = emptyList(), searchLocalHits = emptyList(), searchLocalMore = 0,
        )
        if (_state.value.searchScope == SearchScope.ONLINE) searchOnDevice(q)
        viewModelScope.launch {
            try {
                val scope = _state.value.searchScope
                var partial: String? = null
                val results = when (scope) {
                    SearchScope.ONLINE -> {
                        val (found, missing) = searchOnline(q)
                        partial = missing
                        found
                    }
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
                    val model = ensureModelShared(rated)
                    // Papers already judged are poor results when searching online, and so
                    // are the ones already listed above as being on the device. When
                    // searching your own library they are the entire point.
                    val shown = _state.value.searchLocalHits.map { it.paper.id }.toSet()
                    val visible =
                        if (scope == SearchScope.ONLINE)
                            results.filter { it.id !in ratedIds && it.id !in shown }
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
                // Only online results are new; the local scopes already came from the table.
                if (scope == SearchScope.ONLINE) {
                    withContext(Dispatchers.IO) { db.upsertPapers(results) }
                }
                _state.value = _state.value.copy(
                    searching = false,
                    searchHits = hits,
                    searchError = partial,
                )
            } catch (e: Exception) {
                // With device results already on screen this is a footnote rather than the
                // whole answer, which is the other thing local-first buys: a search on a
                // train now returns something.
                _state.value = _state.value.copy(
                    searching = false,
                    searchError = humanError(e, "reach the preprint servers"),
                )
            }
        }
    }

    /**
     * arXiv, and through Crossref every other server the app reads, at the same time.
     *
     * Either can fail without taking the other's results with it; the second value names
     * what is missing so the screen can say so. Both failing is an error like any other.
     *
     * Crossref names some papers without the version the app stores them under, so each one
     * already on the device is replaced by the stored copy, reactions and all, rather than
     * becoming a second copy of itself.
     */
    private suspend fun searchOnline(q: String): Pair<List<Paper>, String?> = coroutineScope {
        val arxiv = async { runCatching { ArxivApi.search(q, max = 100) } }
        val others = async { runCatching { CrossrefSearch.search(q, rows = 60) } }
        val fromArxiv = arxiv.await()
        val fromOthers = others.await()
        if (fromArxiv.isFailure && fromOthers.isFailure) {
            throw fromArxiv.exceptionOrNull() ?: fromOthers.exceptionOrNull()!!
        }
        val crossref = fromOthers.getOrDefault(emptyList())
        val stored = withContext(Dispatchers.IO) {
            val byBase = db.storedIds(crossref.map { CrossrefSearch.baseId(it.id) })
            val papers = db.papersById(byBase.values).associateBy { it.id }
            crossref.map { p -> byBase[CrossrefSearch.baseId(p.id)]?.let { papers[it] } ?: p }
        }
        val missing = when {
            fromArxiv.isFailure -> "arXiv did not answer, so these are from the other servers only."
            fromOthers.isFailure ->
                "bioRxiv, medRxiv, ChemRxiv and the OSF servers could not be reached through " +
                    "Crossref, so these are from arXiv only."
            else -> null
        }
        (fromArxiv.getOrDefault(emptyList()) + stored).distinctBy { it.id } to missing
    }

    /**
     * The instant half: what the device already holds.
     *
     * Deliberately does not train. [ensureModel] can take seconds on a cold start, which is
     * the one thing this path must not do, and ordering by query match alone is arguably the
     * better answer here anyway: "where is that paper I read" is a question about the words,
     * not about predicted interest. If a model happens to be warm it is used.
     */
    private fun searchOnDevice(q: String) {
        viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) {
                val found = db.searchLocal(q, savedOnly = false, limit = 60)
                if (found.isEmpty()) return@withContext emptyList()
                SearchRanker.rank(
                    results = found,
                    query = q,
                    rated = emptyList(),
                    personalisation = _state.value.personalisation,
                    model = cachedModel,
                )
            }
            // A late arXiv reply for a previous query must not resurrect its device results.
            if (_state.value.searchQuery.trim() != q) return@launch
            _state.value = _state.value.copy(
                searchLocalHits = hits.take(DEVICE_HITS),
                searchLocalMore = (hits.size - DEVICE_HITS).coerceAtLeast(0),
            )
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
    private fun ratedDocs(): List<RatedDoc> = DigestBuilder.ratedDocs(db, prefs)

    /**
     * Papers carrying any signal at all. This is what decides how far the model's confidence
     * is pulled toward the prior, so it counts evidence rather than explicit judgements: a
     * reader who never presses a button still accumulates it.
     */
    private fun evidenceCount(): Int = db.evidence().count { it.value.label() != null }

    /** Papers the reader pressed a button on, which is a smaller and different number. */
    private fun judgedCount(): Int = db.evidence().count { it.value.explicit }

    /**
     * A sentence a reader can act on, rather than the exception's own words.
     *
     * An offline-first app that answers "why is this blank" with
     * `Unable to resolve host "arxiv.org": No address associated with hostname` is telling
     * the one person who cannot use that information. Being offline is the expected case
     * here, not a fault, and it is the only case with an obvious next step.
     */
    private fun humanError(e: Exception, action: String): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "You are offline, so the app cannot $action. Everything already downloaded " +
                "still works."
        is java.net.SocketTimeoutException ->
            "arXiv did not answer in time. Worth another try in a moment."
        else -> e.message ?: "Could not $action."
    }

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
            judgedCount = judgedCount(),
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
        val paper = _state.value.ratedPapers.firstOrNull { it.first.id == paperId }?.first
            ?: _state.value.paperById(paperId)
        val rest = _state.value.ratedPapers.filterNot { it.first.id == paperId }
        // Newest judgement at the top, matching the order the shelf is loaded in. Sorting by
        // label here instead dropped the paper into a block of identical scores and moved
        // everything the reader was looking at.
        val shelf =
            if (liked == null || paper == null) rest
            else listOf(paper to (ev[paperId]?.label() ?: 0f)) + rest
        _state.value = _state.value.copy(
            evidence = ev,
            ratedCount = judged,
            judgedCount = judgedCount(),
            modelActive = judged >= Ranker.MIN_RATINGS,
            ratedPapers = shelf,
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
        // Nothing has changed since the last look, so ranking eight hundred candidates again
        // would spend seconds to rebuild the same empty list.
        if (more && _state.value.exploreExhausted) return
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
                val model = ensureModelShared(rated)
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
                    topicHistory = db.topicHistory(subscribed = cats.toSet()),
                    prebuilt = model,
                )
            }
            _state.value = _state.value.copy(
                exploreLoading = false,
                explore = if (more) _state.value.explore + cards else cards,
                // A page that came back short is the last page. Asking again returns the
                // same nothing, and the button that asks should stop being offered.
                exploreExhausted = cards.size < EXPLORE_PAGE,
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
            val (papers, status) = withContext(Dispatchers.IO) {
                val attention = _state.value.attention
                val pool = db.recentPapers(limit = 600)
                val ranked = pool
                    .map { p ->
                        p to (Attention.score(attention[p.id] ?: 0) * 2f + Venue.score(p))
                    }
                    .filter { it.second > 0f }
                    .sortedByDescending { it.second }
                    .take(60)
                    .map { it.first }
                // Told apart rather than lumped together: whether anything has been fetched
                // at all is the difference between "wait" and "this will never fill".
                val status = when {
                    ranked.isNotEmpty() -> PopularStatus.READY
                    pool.isEmpty() -> PopularStatus.NO_PAPERS
                    else -> PopularStatus.NO_SIGNAL
                }
                ranked to status
            }
            _state.value = _state.value.copy(popular = papers, popularStatus = status)
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

    /**
     * Whether there is a gap worth telling the reader about, and how big.
     *
     * "Away" is measured from the last digest they were actually shown, which is the last day
     * they opened the app, because the worker fetches papers without composing a digest. So
     * the papers announced since then, minus anything a digest did put in front of them and
     * anything they have touched, is exactly the set they never had the chance to see.
     */
    private fun refreshCatchUp() {
        val today = LocalDate.now().toString()
        val last = db.lastDigestDayBefore(today)
        // Yesterday is not an absence. This is for the reader who was away, not the one who
        // slept, and a card that appears every single morning is a nag rather than a service.
        val gapDays = last?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now()) }
        if (last == null || gapDays == null || gapDays < 2) {
            _state.value = _state.value.copy(missedSince = null, missedCount = 0)
            return
        }
        _state.value = _state.value.copy(
            missedSince = last,
            missedCount = db.unseenCountSince(last),
        )
    }

    fun openPast() {
        _state.value = _state.value.copy(pastOpen = true)
        viewModelScope.launch {
            val days = withContext(Dispatchers.IO) { db.digestDays() }
            _state.value = _state.value.copy(pastDays = days)
        }
    }

    fun closePast() {
        _state.value = _state.value.copy(
            pastOpen = false, pastDay = null, pastCards = emptyList(), catchUp = emptyList(),
        )
    }

    /**
     * Ranks the papers the reader missed, rather than listing them.
     *
     * Several hundred papers is not a catch-up, it is a second job. The same machinery that
     * picks sixty from three hundred each morning picks the best of a week off, so coming
     * back after a fortnight costs one screen rather than fourteen.
     */
    fun openCatchUp() {
        val since = _state.value.missedSince ?: return
        if (_state.value.catchUpLoading) return
        _state.value = _state.value.copy(catchUpLoading = true, pastDay = CATCH_UP)
        viewModelScope.launch {
            val cards = withContext(Dispatchers.Default) {
                val pool = db.unseenSince(since)
                if (pool.isEmpty()) return@withContext emptyList()
                val rated = ratedDocs()
                Ranker(
                    Weights(
                        quality = prefs.qualityWeight,
                        explorationRate = 0f,
                        diversity = prefs.diversity,
                    )
                ).digest(
                    candidates = pool,
                    rated = rated,
                    seen = emptySet(),
                    subscribed = _state.value.categories,
                    size = CATCH_UP_SIZE,
                    attention = _state.value.attention,
                    evidenceCount = evidenceCount(),
                    topicHistory = db.topicHistory(subscribed = _state.value.categories),
                    prebuilt = ensureModelShared(rated),
                )
            }
            _state.value = _state.value.copy(catchUpLoading = false, catchUp = cards)
        }
    }

    /**
     * Replays one past morning in the order it was shown, with the reasons it carried.
     *
     * Not re-ranked. The reader is asking what the app told them on Tuesday, and quietly
     * answering with what it would say today would make the record useless.
     */
    fun openPastDay(day: String) {
        _state.value = _state.value.copy(pastDay = day, pastCards = emptyList())
        viewModelScope.launch {
            val cards = withContext(Dispatchers.IO) {
                val items = db.digestFor(day)
                val byId = db.papersById(items.map { it.paperId }).associateBy { it.id }
                items.mapNotNull { item ->
                    byId[item.paperId]?.let {
                        Scored(it, 0f, item.confidence, runCatching { Slot.valueOf(item.slot) }
                            .getOrDefault(Slot.RELEVANCE), storedReason = item.reason)
                    }
                }
            }
            if (_state.value.pastDay == day) {
                _state.value = _state.value.copy(pastCards = cards)
            }
        }
    }

    fun closePastDay() {
        _state.value = _state.value.copy(pastDay = null, pastCards = emptyList())
    }

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
    /**
     * Throws away the copy on the phone and fetches the paper again.
     *
     * For a file that downloaded but will not open. The download itself is atomic, so this is
     * usually a server that answered with something damaged, and asking again is the fix.
     */
    fun redownload(paper: Paper) {
        viewModelScope.launch {
            PdfStore(getApplication()).delete(paper.id)
            openReader(paper)
        }
    }

    fun openReader(paper: Paper) {
        val store = PdfStore(getApplication())
        _state.value = _state.value.copy(
            reading = paper,
            readingFile = null,
            readingError = null,
            readingUnsupported = null,
            readingPage = prefs.lastPage(paper.id),
        )
        viewModelScope.launch {
            try {
                val file = store.download(paper)
                // The download itself is not the signal. Tapping Read is one tap, and this
                // used to score it 0.7 whether the reader took in a word of it or reversed
                // straight back out. The clock starts once the file is actually on screen.
                readerDwell?.cancel()
                readerDwell = startDwell(paper.id, Signal.DOWNLOADED, Dwell.READER_MILLIS)
                // Put it on the offline shelf now, the same way deleting takes it off.
                //
                // The shelf is rebuilt when the library tab is entered, which is why this
                // looked fine coming from the digest: that route leaves the tab and comes
                // back. Reaching the reader from inside the library never changes tab, so
                // nothing rebuilt it and a paper that had plainly just downloaded was not
                // on the list of downloads.
                val onShelf = _state.value.downloaded.any { it.id == paper.id }
                // Checked by its bytes rather than its name. Rendering is the only thing
                // that cannot cope; the paper is still downloaded, still on the offline
                // shelf, and still readable in whatever app owns that format.
                val renderable = store.looksLikePdf(file)
                _state.value = _state.value.copy(
                    readingFile = if (renderable) file else null,
                    readingUnsupported = if (renderable) null else file,
                    evidence = db.evidence(),
                    downloaded = if (onShelf) _state.value.downloaded
                    else _state.value.downloaded + paper,
                    downloadedBytes = _state.value.downloadedBytes +
                        (paper.id to store.sizeOf(paper.id)),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(readingError = humanError(e, "fetch this PDF"))
            }
        }
    }

    fun closeReader() {
        readerDwell?.cancel()
        readerDwell = null
        _state.value = _state.value.copy(
            reading = null, readingFile = null, readingError = null, readingUnsupported = null,
        )
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
