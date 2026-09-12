package si.jakobkreft.aftergleam.rank

import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.Attention
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
    /**
     * This paper's TF-IDF vector, kept so the reason can be worked out after selection.
     *
     * Not persisted and not part of what a card means; it is the scoring pass handing the
     * explanation pass something it already computed rather than transforming the text twice.
     */
    val vec: SparseVec? = null,
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
                    distinctTerms(reasonTerms).take(2).joinToString(", ")
            placedByQuality -> "ranked up for its venue"
            // Naming the category rather than claiming it is one of the reader's. Papers
            // fetched for the bridge slot stay in the pool and can be picked into an
            // ordinary slot, and this line told a cell biologist that cond-mat.soft was one
            // of their categories. Saying which category it is happens to be more useful too.
            reasonTerms.isEmpty() ->
                "recent in " + Source.display(paper.primaryCategory).ifBlank { "your feed" }
            else -> "matches " + distinctTerms(reasonTerms).take(3).joinToString(", ")
        }
    }

    /**
     * Drops a term that merely repeats one already listed, so a chip reads
     * "diffusion, generated" rather than "diffusion, diffusion models, generated".
     * Bigrams and their component words are both real features, but printing both wastes
     * the one line the card gives the explanation.
     */
    private fun distinctTerms(terms: List<String>): List<String> {
        // A word standing in for the phrase it came from says less than the phrase does. The
        // vectoriser scores "shot" and "few_shot" separately and the bare word often wins,
        // so the pass below would print "shot" and then discard "few shot" as a repeat. A
        // digest really did explain a card with "datasets, language, shot". Where the list
        // already holds a phrase containing the word, the phrase is shown in its place.
        val phraseFor = HashMap<String, String>()
        for (raw in terms) {
            if ('_' !in raw) continue
            for (w in raw.split('_')) phraseFor.putIfAbsent(w, raw)
        }

        val out = mutableListOf<String>()
        val shown = mutableListOf<String>()
        for (term in terms) {
            val raw = if ('_' in term) term else phraseFor[term] ?: term
            val words = raw.split('_')
            // Singular and plural are separate features to the vectoriser and both can score,
            // so a chip read "matches layers, layer, update" and another "trajectory,
            // trajectories, call". They are one word to a reader, and the card gives the
            // explanation one line.
            if (words.any { w -> shown.any { samePlural(w, it) } }) continue
            out += words.joinToString(" ")
            shown += words
        }
        // Prefer terms that say what the paper is about. A real digest produced "matches
        // layers, arbitrarily, terms", where two words in three describe no subject at all.
        // Filtered here rather than in the vectoriser on purpose: the model may well be
        // right to weight them, and dropping them from the vocabulary would change the
        // ranking to fix a caption. If filtering leaves nothing, the unfiltered list is
        // still better than an empty chip.
        //
        // A term is empty only when every word in it is. Dropping any term that merely
        // contains a filler word costs the phrases worth keeping, "optimal transport",
        // "state space", "image quality", while this rule still discards the boilerplate
        // that is filler end to end: "state art", "novel framework", "high quality".
        val topical = out.filter { term -> term.split(' ').any { it !in FILLER } }
        return topical.ifEmpty { out }
    }

    /**
     * Whether two words are the same word, one of them pluralised.
     *
     * A pairwise test rather than a stem, because stemming a word on its own has to guess:
     * "bias" would reduce to "bia" while "biases" reduces to "bias", and the two would still
     * both be printed, which is the whole complaint. Comparing the pair needs no guess.
     * Deliberately not a full stemmer either, which merges words that differ: "generate" and
     * "general" agree for six letters.
     */
    private fun samePlural(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length < b.length) a to b else b to a
        if (short.length < 3) return false
        return long == short + "s" ||
            long == short + "es" ||
            (short.endsWith("y") && long == short.dropLast(1) + "ies")
    }

    /**
     * Words every abstract contains regardless of subject, excluded from explanations only.
     *
     * Not a stopword list for the model. These are the words that survive TF-IDF because
     * they are genuinely uneven across documents, and still tell a reader nothing about
     * what a paper is on.
     */
    private val FILLER = setOf(
        "terms", "term", "results", "result", "approach", "approaches", "method", "methods",
        "propose", "proposed", "proposes", "paper", "papers", "work", "works", "study",
        "studies", "based", "using", "used", "use", "show", "shows", "shown", "however",
        "arbitrarily", "arbitrary", "various", "several", "different", "existing", "novel",
        "new", "recent", "recently", "particular", "particularly", "significantly",
        "extensive", "extensively", "empirical", "empirically", "demonstrate", "demonstrates",
        "achieve", "achieves", "achieved", "provide", "provides", "present", "presents",
        "introduce", "introduces", "consider", "considered", "given", "well", "may", "often",
        "furthermore", "moreover", "additionally", "respectively", "via", "towards", "toward",

        // Qualifiers. They grade a thing without naming one, and because they are graded
        // rather than common they survive TF-IDF: "principal" occurs in 0.7% of abstracts
        // and "status" in 0.3%, against 6.1% for "diffusion", so no frequency rule reaches
        // them. Only as whole terms; "optimal transport" and "state space" are subjects.
        "best", "better", "known", "unknown", "specific", "general", "generic", "simple",
        "complex", "efficient", "effective", "robust", "standard", "common", "typical",
        "similar", "related", "important", "main", "key", "single", "multiple", "further",
        "overall", "global", "globally", "local", "locally", "direct", "directly", "strong",
        "strongly", "weak", "weakly", "full", "partial", "optimal", "principal", "special",
        "high", "low", "state", "art", "quality", "able", "large", "small", "long", "short",

        // Kept in the model's vocabulary on purpose: "not" and "without" carry method
        // meaning, and the vectoriser's list stays short for that reason. They still name
        // no subject, which is the whole point of this list being a separate one.
        "not", "nor", "plus", "without",

        // Things that happen in every paper.
        "experienced", "maintaining", "adding", "obtained", "observed", "applied",
        "applying", "allows", "allowing", "enables", "enabling", "requires", "requiring",
        "leads", "leading", "yields", "yielding", "remains", "remaining", "distinguishing",
        "reducing", "improving", "improved", "improves", "achieve", "challenge",

        // Nouns that name no subject.
        "status", "role", "idea", "ideas", "number", "numbers", "way", "ways", "case",
        "cases", "part", "parts", "level", "levels", "order", "form", "forms", "notion",
        "aspect", "aspects", "factor", "factors", "framework", "frameworks", "setting",
        "settings", "context", "condition", "conditions", "property", "properties", "value",
        "values", "type", "types", "set", "sets", "point", "points", "data", "shared",

        // Seen in a real digest after the change above, all in the third slot where the
        // weakest shared term lands. As whole terms only: "fixed point", "closed form",
        // "maximum likelihood" and "constant factor" are subjects and survive.
        "defined", "argue", "argues", "perspective", "partially", "fixed", "current",
        "call", "called", "reaching", "generate", "generates", "closed", "constant",
        "maximum", "minimum", "satisfy", "satisfies", "perform", "performs", "dominant",
        "rapid", "concerns", "resulting", "unseen", "varied", "need", "needs", "issues",
        "issue", "contrast", "relative", "generalize", "generalise", "followed", "matters",
        "traditional", "making", "made",

        // Connectives and counters that only ever arrived by accident.
        "thereby", "where", "when", "while", "whereas", "hence", "therefore", "whether",
        "though", "although", "since", "because", "finally", "first", "second", "third",
        "next", "last", "many", "much", "most", "more", "less", "least", "both", "either",
        "neither", "each", "every", "some", "any", "other", "others", "another", "one",
        "two", "three",
    )
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
    /**
     * How often to show something the model is unsure about.
     *
     * This is the temperature of the sampler, and it is the one exploration control worth
     * exposing. Near zero the digest is the deterministic top of the list, which is what
     * made the feed collapse into one topic.
     */
    val temperature: Float = 0.35f,
    /** Weight on "lots of people are reading this today". Enrichment, so modest. */
    val attention: Float = 0.25f,
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
        attention: Map<String, Int> = emptyMap(),
        /** Papers the reader has given any signal for; drives how much the model is trusted. */
        evidenceCount: Int = rated.size,
        /** Per-topic engaged/ignored counts, driving how slots are shared out. */
        topicHistory: Map<String, Pair<Float, Float>> = emptyMap(),
        /** A model trained earlier, when the reader's signals have not changed since. */
        prebuilt: Model? = null,
    ): List<Scored> {
        // A paper the user has already judged is finished business. Leaving rated papers
        // in the pool made them dominate the top of the list, because the model scores its
        // own training positives most confidently of all.
        val ratedIds = rated.mapNotNull { it.paperId }.toSet()
        val fresh = candidates.filter { it.id !in seen && it.id !in ratedIds }
        if (fresh.isEmpty()) return emptyList()

        val model = prebuilt ?: train(fresh, rated, negativePool)
        val today = LocalDate.now()

        val hasModel = model != null
        val scored = fresh.map { paper ->
            val vec = model?.vec?.transform(paper.rankText)
            // Shrunk toward the prior: a model fitted on a handful of papers is as sharp as
            // one fitted on hundreds, and acting on that is how the feed narrowed in a day.
            val rel = if (model != null && vec != null) {
                Sampling.shrink(model.clf.predict(vec), evidenceCount)
            } else 0f
            val venue = Venue.score(paper)
            val fresh = recency(paper, today)
            // Attention is what the field is reading today, on the timescale where venue
            // and citations both say nothing yet. It boosts like venue does, and for the
            // same reason cannot rescue a paper the user would not want.
            val buzz = attention[paper.id]?.let { Attention.score(it) } ?: 0f

            // Quality multiplies interest rather than being added to it.
            //
            // Additively, venue dominated: early on the model's confidence sits near 0.2
            // while an accepted paper contributes 0.35 outright, so nine of the top ten
            // cards were there for their venue and the digest was really "recently
            // accepted papers" rather than "papers you will like". As a multiplier a
            // strong venue promotes a paper the user would want anyway, and cannot rescue
            // one they would not.
            val score = if (hasModel) {
                rel * (1f + weights.quality * venue + weights.attention * buzz) +
                    weights.recency * fresh
            } else {
                // Cold start: with no model every relevance is zero, so a multiplier would
                // flatten everything. Venue, buzz and freshness are all there is to go on.
                weights.quality * venue + weights.attention * buzz + weights.recency * fresh
            }

            Scored(
                paper = paper,
                score = score,
                relevance = rel,
                slot = Slot.RELEVANCE,
                vec = vec,
                placedByQuality = hasModel && venue > 0f && weights.quality * venue * rel > weights.recency * fresh,
            )
        }.sortedByDescending { it.score }

        val chosen = compose(scored, subscribed, size, random, hasModel, model, topicHistory)
        return explain(chosen, rated, model)
    }

    /**
     * Attaches each card's reason once the digest is settled.
     *
     * Done here rather than during scoring because it costs one merge per card per kept
     * paper, and only the twenty five cards that survive selection are ever explained. On
     * the old path the equivalent work ran for every candidate, several hundred of which
     * are thrown away.
     */
    private fun explain(cards: List<Scored>, rated: List<RatedDoc>, model: Model?): List<Scored> {
        if (model == null) return cards
        val references = Explain.references(rated, model.vec)
        if (references.isEmpty()) return cards
        return cards.map { card ->
            val v = card.vec ?: return@map card
            val match = Explain.match(v, references, model.vec) ?: return@map card
            card.copy(reasonTerms = match.terms)
        }
    }

    /**
     * Maximal marginal relevance: repeatedly take the best remaining card after penalising
     * it for how much it looks like what has already been chosen.
     *
     * Similarity is cosine over the same TF-IDF vectors the ranker already computed, so
     * this costs one dot product per candidate per slot and needs no extra model. With no
     * vectoriser available (cold start) it degrades to plain ranking.
     */
    private fun selectDiverse(
        scored: List<Scored>,
        n: Int,
        model: Model?,
        random: Random,
    ): List<Scored> {
        if (n <= 0) return emptyList()
        if (model == null) {
            return Sampling.topK(scored, n, weights.temperature, random) {
                it.score.coerceIn(0f, 1f)
            }
        }

        val vectors = HashMap<String, SparseVec>()
        fun vec(s: Scored) = vectors.getOrPut(s.paper.id) {
            model.vec.transform(s.paper.rankText)
        }

        // Two caps, both needed. The loop is slots x pool x already-chosen, so at a digest
        // of sixty it was doing roughly three hundred thousand sparse cosines and burning
        // minutes of CPU before showing anything; at twenty-five the same code was fine,
        // which is why it went unnoticed.
        //
        // A fixed pool keeps the cost flat as the digest grows, and comparing only against
        // the most recent picks is enough: near-duplicates cluster, so a paper that echoes
        // something chosen forty slots ago is not what this pass is for.
        val pool = scored.take(MMR_POOL.coerceAtMost(scored.size)).toMutableList()
        val chosen = mutableListOf<Scored>()
        val lambda = 1f - weights.diversity

        // Stochastic maximal marginal relevance: each pick is *drawn* from the
        // diversity-adjusted scores rather than taken as the maximum.
        //
        // Doing these separately did not work. Sampling a pool and then running a greedy
        // MMR over it means the greedy pass decides everything whenever the pool covers the
        // candidates, so the digest was identical on every re-rank; and shrinking the pool
        // far enough to matter starved the diversity pass of the minority topics it exists
        // to rescue. One loop does both jobs: variety between runs, and no near-duplicates
        // within a run.
        while (chosen.size < n && pool.isNotEmpty()) {
            var bestIdx = 0
            var bestKey = Float.NEGATIVE_INFINITY
            for (i in pool.indices) {
                val cand = pool[i]
                val maxSim = chosen.takeLast(MMR_LOOKBACK)
                    .maxOfOrNull { cosine(vec(cand), vec(it)) } ?: 0f
                val value = (lambda * cand.score - weights.diversity * maxSim)
                    .coerceIn(1e-6f, 1f)
                val key = kotlin.math.ln(value) / weights.temperature.coerceAtLeast(1e-3f) +
                    gumbel(random)
                if (key > bestKey) {
                    bestKey = key
                    bestIdx = i
                }
            }
            chosen += pool.removeAt(bestIdx)
        }
        return chosen
    }

    /**
     * Fills [n] slots by repeatedly drawing a topic and taking the best paper from it.
     *
     * Within a topic the existing stochastic diversity pass still chooses, so the two work at
     * different levels: the bandit decides how much attention an area deserves, and the
     * sampler decides which paper represents it.
     */
    private fun allocateByTopic(
        candidates: List<Scored>,
        n: Int,
        model: Model?,
        random: Random,
        topicHistory: Map<String, Pair<Float, Float>>,
    ): List<Scored> {
        if (n <= 0 || candidates.isEmpty()) return emptyList()
        val byTopic = candidates.groupBy { it.paper.primaryCategory }
        // With one topic there is nothing to allocate, and with no history the bandit would
        // just be a uniform draw over topics, which the sampler already handles better.
        if (byTopic.size < 2 || topicHistory.isEmpty()) {
            return selectDiverse(candidates, n, model, random)
        }

        val pools = byTopic.mapValues { (_, v) -> v.toMutableList() }
        val chosen = mutableListOf<Scored>()
        var guard = 0
        while (chosen.size < n && guard++ < n * 8) {
            val arms = pools.entries
                .filter { it.value.isNotEmpty() }
                .map { (topic, _) ->
                    val (engaged, ignored) = topicHistory[topic] ?: (0f to 0f)
                    TopicBandit.Arm(topic, engaged, ignored)
                }
            val topic = TopicBandit.draw(arms, random) ?: break
            val pool = pools[topic] ?: break
            // One paper at a time, so the next slot is decided with the previous pick known.
            val pick = selectDiverse(pool, 1, model, random).firstOrNull() ?: break
            pool.remove(pick)
            chosen += pick
        }
        // A short pool or an unlucky run of draws must not leave the digest short.
        if (chosen.size < n) {
            chosen += selectDiverse(
                candidates.filter { c -> chosen.none { it.paper.id == c.paper.id } },
                n - chosen.size, model, random,
            )
        }
        return chosen
    }

    private fun gumbel(random: Random): Float {
        val u = random.nextFloat().coerceIn(1e-6f, 1f - 1e-6f)
        return -kotlin.math.ln(-kotlin.math.ln(u))
    }

    // Both vectors are L2 normalised by Tfidf.transform, so the dot product is the cosine.
    private fun cosine(a: SparseVec, b: SparseVec): Float =
        if (a.isEmpty() || b.isEmpty()) 0f else a.dot(b)

    private fun compose(
        scored: List<Scored>,
        subscribed: Set<String>,
        size: Int,
        random: Random,
        hasModel: Boolean,
        model: Model?,
        topicHistory: Map<String, Pair<Float, Float>>,
    ): List<Scored> {
        if (scored.size <= size) return scored

        // Papers from categories the reader never chose are candidates for exactly one slot,
        // the bridge, and for nothing else.
        //
        // The candidate pool is everything recently fetched, and the bridge fetches outside
        // the reader's fields on purpose, so those papers sit in the same table as the rest.
        // Every slot used to draw from all of it. For a reader with broad subjects that was
        // invisible, because eighty outside papers among a thousand subscribed ones rank low
        // and rarely surface. For a reader who follows law it was the whole digest: four law
        // papers existed, and the morning was twenty five cards of cs.CY, cs.AI and q-fin,
        // none of which they had asked for and none of which was law.
        val inScope =
            if (subscribed.isEmpty()) scored
            else scored.filter { s -> s.paper.categories.any { it in subscribed } }
        val outside = scored.filter { s -> s.paper.categories.none { it in subscribed } }

        val picked = LinkedHashMap<String, Scored>()
        // With no trained model every relevance is zero, so an "exploration" card would
        // be a random paper wearing a label that claims the model is learning from it.
        val nExplore =
            if (!hasModel) 0 else (size * weights.explorationRate).toInt().coerceIn(0, size - 1)
        val nBridge = if (subscribed.isEmpty()) 0 else 1
        val nRelevance = size - nExplore - nBridge

        // Sample a pool several times the size needed, then pick a diverse subset of it.
        // Sampling supplies exploration and makes a re-rank return something new; the
        // diversity pass then stops that pool being ten papers on one topic.
        // The bridge is chosen first, and deliberately.
        //
        // Relevance selection is now a sample, so it can happily draw the one out-of-field
        // paper into an ordinary slot and leave the bridge step with nothing to offer. A
        // reserved slot is the only way to guarantee the feature actually appears.
        if (nBridge > 0) {
            outside.firstOrNull()?.let { picked[it.paper.id] = it.copy(slot = Slot.BRIDGE) }
        }

        // Slots are shared out across topics before any paper is chosen.
        //
        // Sampling papers alone still draws from the region the model is confident about: a
        // reader who liked four diffusion papers gets a model sure about diffusion and silent
        // about everything else, and per-item noise only shuffles the diffusion papers. The
        // bandit decides how much of the morning each area gets, and the width of its
        // posterior does the exploring, so an area nothing is known about is tried because it
        // is unknown rather than because a slider said to.
        val remaining = inScope.filter { it.paper.id !in picked }
        val allocated = allocateByTopic(remaining, nRelevance, model, random, topicHistory)
        allocated.forEach { picked[it.paper.id] = it }

        // Uncertainty sampling: relevance nearest 0.5 is where a label teaches the most.
        inScope.asSequence()
            .filter { it.paper.id !in picked }
            .sortedBy { kotlin.math.abs(it.relevance - 0.5f) }
            .take(nExplore * 3)
            .shuffled(random)
            .take(nExplore)
            .forEach { picked[it.paper.id] = it.copy(slot = Slot.EXPLORATION) }

        // Backfill if a slot found no candidate. Only from the reader's own subjects, so a
        // short day stays short: four papers they chose beat twenty five they did not.
        for (s in inScope) {
            if (picked.size >= size) break
            picked.putIfAbsent(s.paper.id, s)
        }
        return picked.values.take(size).sortedByDescending { it.score }
    }

    /**
     * A fitted vectoriser and classifier.
     *
     * Public so it can be held between digests. Training is the expensive part of a rebuild,
     * and it depends only on what the reader has reacted to; re-fitting it because the day's
     * papers changed is work for nothing.
     */
    class Model(val vec: Tfidf, val clf: LogReg)

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
    /**
     * Fits a model, or returns null when there is too little to learn from.
     *
     * Public so the caller can hold on to the result. The negatives are drawn with a seed
     * derived from the ratings, so the same ledger always trains the same model and caching
     * it cannot silently change what the reader sees.
     */
    fun train(
        candidates: List<Paper>,
        rated: List<RatedDoc>,
        negativePool: List<String>,
    ): Model? {
        if (rated.size < MIN_RATINGS) return null   // cold start: venue and recency only

        val pool = negativePool.ifEmpty { candidates.map { it.rankText } }
        val easyNegatives = pool
            .shuffled(Random(rated.sumOf { it.text.hashCode().toLong() }))
            .take(rated.size * 10)
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

        /** Candidates the diversity pass considers. Flat cost regardless of digest size. */
        private const val MMR_POOL = 150

        /** How many recent picks a candidate is compared against for similarity. */
        private const val MMR_LOOKBACK = 12
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
