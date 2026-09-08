package si.jakobkreft.aftergleam.rank

import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.random.Random

/** Why a card is in the digest. Shown to the user verbatim, so the labels are honest. */
enum class Slot { RELEVANCE, EXPLORATION, BRIDGE }

data class Scored(
    val paper: Paper,
    val score: Float,
    val relevance: Float,
    val slot: Slot,
    val reasonTerms: List<String> = emptyList(),
    /** Set when this card was rebuilt from storage; shown verbatim if present. */
    val storedReason: String? = null,
    /** True when venue or freshness, not predicted interest, put this card here. */
    val placedByQuality: Boolean = false,
) {
    /**
     * The "why" chip. With TF-IDF the explanation is the matching words themselves.
     *
     * It also has to account for placement. The list is ordered by a blend of predicted
     * interest, venue and freshness, so a card showing 38% can legitimately sit above one
     * showing 47%. Without saying so, the visible number appears to contradict the order.
     */
    fun why(): String = storedReason ?: when (slot) {
        Slot.EXPLORATION -> "testing whether this is for you"
        Slot.BRIDGE -> "${paper.primaryCategory}, outside your usual"
        Slot.RELEVANCE -> when {
            placedByQuality && reasonTerms.isNotEmpty() ->
                "ranked up for its venue, matches " +
                    reasonTerms.take(2).joinToString(", ") { it.replace('_', ' ') }
            placedByQuality -> "ranked up for its venue"
            reasonTerms.isEmpty() -> "recent in your categories"
            else -> "matches " + reasonTerms.joinToString(", ") { it.replace('_', ' ') }
        }
    }
}

data class Weights(
    val relevance: Float = 1.0f,
    val quality: Float = 0.35f,
    val recency: Float = 0.25f,
    val explorationRate: Float = 0.2f,
    /**
     * How much to trade relevance for variety, 0 = pure ranking, 1 = pure novelty.
     *
     * Pure ranking produces a digest of near-duplicates: a real run returned twenty-five
     * cards whose explanations all began "matches reasoning", because the model had learned
     * one topic and the top of the list is where that topic lives. Nine slightly different
     * papers about vision-language agents is a worse morning than six of those plus three
     * other things.
     */
    val diversity: Float = 0.3f,
)

/**
 * Turns a day's candidates into a fixed set of cards.
 *
 * The digest has an end. That is the retention mechanism, not a limitation: a finite,
 * finishable set is what distinguishes this from the infinite feed it is reacting to.
 */
class Ranker(private val weights: Weights = Weights()) {

    /**
     * @param candidates today's papers
     * @param rated every paper the user has rated, as text paired with a 0..1 interest
     * @param seen ids already shown on a previous day
     * @param subscribed the user's categories, used to identify bridge candidates
     */
    fun digest(
        candidates: List<Paper>,
        rated: List<RatedDoc>,
        seen: Set<String>,
        subscribed: Set<String>,
        size: Int = 25,
        random: Random = Random.Default,
        negativePool: List<String> = emptyList(),
    ): List<Scored> {
        // A paper the user has already judged is finished business. Leaving rated papers
        // in the pool made them dominate the top of the list, because the model scores its
        // own training positives most confidently of all.
        val ratedIds = rated.mapNotNull { it.paperId }.toSet()
        val fresh = candidates.filter { it.id !in seen && it.id !in ratedIds }
        if (fresh.isEmpty()) return emptyList()

        val model = train(fresh, rated, negativePool)
        val today = LocalDate.now()

        val hasModel = model != null
        val scored = fresh.map { paper ->
            val vec = model?.vec?.transform(paper.rankText)
            val rel = if (model != null && vec != null) model.clf.predict(vec) else 0f
            val terms = if (model != null && vec != null) {
                model.clf.topContributors(vec).mapNotNull { model.vec.termAt(it) }
            } else emptyList()

            val venue = Venue.score(paper)
            val fresh = recency(paper, today)

            // Quality multiplies interest rather than being added to it.
            //
            // Additively, venue dominated: early on the model's confidence sits near 0.2
            // while an accepted paper contributes 0.35 outright, so nine of the top ten
            // cards were there for their venue and the digest was really "recently
            // accepted papers" rather than "papers you will like". As a multiplier a
            // strong venue promotes a paper the user would want anyway, and cannot rescue
            // one they would not.
            val score = if (hasModel) {
                rel * (1f + weights.quality * venue) + weights.recency * fresh
            } else {
                // Cold start: with no model every relevance is zero, so a multiplier would
                // flatten everything. Venue and freshness are all there is to go on.
                weights.quality * venue + weights.recency * fresh
            }

            Scored(
                paper = paper,
                score = score,
                relevance = rel,
                slot = Slot.RELEVANCE,
                reasonTerms = terms,
                placedByQuality = hasModel && venue > 0f && weights.quality * venue * rel > weights.recency * fresh,
            )
        }.sortedByDescending { it.score }

        return compose(scored, subscribed, size, random, hasModel, model)
    }

    /**
     * Maximal marginal relevance: repeatedly take the best remaining card after penalising
     * it for how much it looks like what has already been chosen.
     *
     * Similarity is cosine over the same TF-IDF vectors the ranker already computed, so
     * this costs one dot product per candidate per slot and needs no extra model. With no
     * vectoriser available (cold start) it degrades to plain ranking.
     */
    private fun selectDiverse(scored: List<Scored>, n: Int, model: Model?): List<Scored> {
        if (n <= 0) return emptyList()
        if (model == null || weights.diversity <= 0f) return scored.take(n)

        val vectors = HashMap<String, Map<Int, Float>>()
        fun vec(s: Scored) = vectors.getOrPut(s.paper.id) {
            model.vec.transform(s.paper.rankText)
        }

        val pool = scored.take((n * 6).coerceAtMost(scored.size)).toMutableList()
        val chosen = mutableListOf<Scored>()
        val lambda = 1f - weights.diversity

        while (chosen.size < n && pool.isNotEmpty()) {
            var bestIdx = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (i in pool.indices) {
                val cand = pool[i]
                val maxSim = chosen.maxOfOrNull { cosine(vec(cand), vec(it)) } ?: 0f
                val value = lambda * cand.score - weights.diversity * maxSim
                if (value > bestValue) {
                    bestValue = value
                    bestIdx = i
                }
            }
            chosen += pool.removeAt(bestIdx)
        }
        return chosen
    }

    private fun cosine(a: Map<Int, Float>, b: Map<Int, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        // Both vectors are already L2 normalised by Tfidf.transform, so the dot product is
        // the cosine directly.
        val (small, large) = if (a.size < b.size) a to b else b to a
        var dot = 0f
        for ((i, v) in small) large[i]?.let { dot += v * it }
        return dot
    }

    private fun compose(
        scored: List<Scored>,
        subscribed: Set<String>,
        size: Int,
        random: Random,
        hasModel: Boolean,
        model: Model?,
    ): List<Scored> {
        if (scored.size <= size) return scored

        val picked = LinkedHashMap<String, Scored>()
        // With no trained model every relevance is zero, so an "exploration" card would
        // be a random paper wearing a label that claims the model is learning from it.
        val nExplore =
            if (!hasModel) 0 else (size * weights.explorationRate).toInt().coerceIn(0, size - 1)
        val nBridge = if (subscribed.isEmpty()) 0 else 1
        val nRelevance = size - nExplore - nBridge

        selectDiverse(scored, nRelevance, model).forEach { picked[it.paper.id] = it }

        // Uncertainty sampling: relevance nearest 0.5 is where a label teaches the most.
        scored.asSequence()
            .filter { it.paper.id !in picked }
            .sortedBy { kotlin.math.abs(it.relevance - 0.5f) }
            .take(nExplore * 3)
            .shuffled(random)
            .take(nExplore)
            .forEach { picked[it.paper.id] = it.copy(slot = Slot.EXPLORATION) }

        if (nBridge > 0) {
            scored.firstOrNull {
                it.paper.id !in picked && it.paper.categories.none { c -> c in subscribed }
            }?.let { picked[it.paper.id] = it.copy(slot = Slot.BRIDGE) }
        }

        // Backfill if a slot found no candidate, so the digest is always `size` long.
        for (s in scored) {
            if (picked.size >= size) break
            picked.putIfAbsent(s.paper.id, s)
        }
        return picked.values.take(size).sortedByDescending { it.score }
    }

    private class Model(val vec: Tfidf, val clf: LogReg)

    /**
     * Trains on stars as positives and hides plus sampled random papers as negatives.
     *
     * Random papers are used as "easy negatives" deliberately. Treating everything the
     * user scrolled past as a negative would outnumber the positives roughly thirty to one
     * and collapse the feed within a fortnight, so only explicit signals count.
     */
    /**
     * @param negativePool text to draw "easy negatives" from. It must not be the same set
     *   we are ranking: sampling negatives out of the candidates means a paper can be
     *   labelled a negative in the very training run that scores it, which suppresses
     *   exactly the good matches we are looking for. In practice this is older cached
     *   papers, disjoint from today's arrivals; the fallback below only matters on day one.
     */
    private fun train(
        candidates: List<Paper>,
        rated: List<RatedDoc>,
        negativePool: List<String>,
    ): Model? {
        if (rated.size < MIN_RATINGS) return null   // cold start: venue and recency only

        val pool = negativePool.ifEmpty { candidates.map { it.rankText } }
        val easyNegatives = pool.shuffled().take(rated.size * 10)
        val docs = rated.map { it.text } + easyNegatives
        val vec = Tfidf().apply { fit(docs) }
        if (vec.size == 0) return null

        val x = docs.map { vec.transform(it) }
        val y = FloatArray(docs.size) { i ->
            if (i < rated.size) rated[i].interest else 0f
        }
        val clf = LogReg(vec.size).apply { fit(x, y) }
        return Model(vec, clf)
    }

    companion object {
        /** Below this many ratings the model is noise, so we do not pretend to have one. */
        const val MIN_RATINGS = 3
    }

    /** Exponential decay with a one-week half-life. */
    private fun recency(paper: Paper, today: LocalDate): Float {
        val days = try {
            ChronoUnit.DAYS.between(LocalDate.parse(paper.published), today).toFloat()
        } catch (e: Exception) {
            return 0f
        }
        if (days < 0f) return 1f
        return exp(-days / 7f)
    }
}
