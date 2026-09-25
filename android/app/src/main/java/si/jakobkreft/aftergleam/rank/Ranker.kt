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
        val vecs = fresh.map { model?.vec?.transform(it.rankText) }
        val predictions = fresh.indices.map { i ->
            val v = vecs[i]
            if (model != null && v != null) model.clf.predict(v) else 0f
        }
        val standing = standing(fresh, predictions, subscribed)

        val scored = fresh.mapIndexed { i, paper ->
            val vec = vecs[i]
            // The confidence a card shows. Shrunk toward the prior: a model fitted on a
            // handful of papers is as sharp as one fitted on hundreds, and quoting its raw
            // probability would claim more than it knows. Ordering uses standing instead.
            val rel = if (model != null && vec != null) {
                Sampling.shrink(predictions[i], evidenceCount)
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
                standing[i] * (1f + weights.quality * venue + weights.attention * buzz) +
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
                placedByQuality = hasModel && venue > 0f &&
                    weights.quality * venue * standing[i] > weights.recency * fresh,
            )
        }.sortedByDescending { it.score }

        val chosen = compose(scored, subscribed, size, random, hasModel, model, topicHistory)
        return explain(chosen, rated, model)
    }

    /**
     * Each paper's interest as its standing among the day's papers in the reader's fields,
     * in 0..1.
     *
     * The model's probabilities are too close together to be combined with anything. They
     * are pulled toward a prior until evidence builds up, and the classifier takes small
     * steps, so on a real phone every one of the day's 400 candidates sat between 0.26 and
     * 0.40 before shrinking and within 0.07 of each other after it. Venue multiplies the
     * score by up to 1.35 and freshness adds up to 0.25, both fixed amounts, and against a
     * spread that narrow they decided the order: an acceptance was worth more than the whole
     * difference between the day's best match and its worst.
     *
     * Standing is the model's score in standard deviations from the day's average, mapped
     * to 0..1 by a logistic curve that closely follows the normal distribution. It spans the
     * range whatever the model's confidence, so venue and freshness again adjust the order
     * rather than set it. Papers from outside the reader's fields are placed on the same
     * scale but do not set it: the bridge fetches dozens of them, and letting them into the
     * average made every paper in a small field look excellent.
     */
    private fun standing(
        papers: List<Paper>,
        predictions: List<Float>,
        subscribed: Set<String>,
    ): FloatArray {
        val logits = predictions.map { logit(it) }
        val own = papers.indices.filter { i ->
            subscribed.isEmpty() || papers[i].categories.any { it in subscribed }
        }
        val scale = Sampling.Scale(own.map { logits[it] }.ifEmpty { logits })
        return FloatArray(papers.size) { i -> 1f / (1f + exp(-1.7f * scale.of(logits[i]))) }
    }

    private fun logit(p: Float): Float {
        val q = p.coerceIn(1e-6f, 1f - 1e-6f)
        return kotlin.math.ln(q / (1f - q))
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
     * vectoriser available (cold start) it degrades to plain sampling.
     *
     * [earlier] is what was chosen before this call. The topic bandit asks for one paper at a
     * time, and each call used to start from an empty list, so the penalty had nothing to
     * compare against: for every reader with more than one topic, the variety setting did
     * nothing at all.
     */
    private fun selectDiverse(
        scored: List<Scored>,
        n: Int,
        model: Model?,
        random: Random,
        scale: Sampling.Scale,
        earlier: List<Scored> = emptyList(),
    ): List<Scored> {
        if (n <= 0) return emptyList()
        if (model == null) {
            return Sampling.topK(scored, n, weights.temperature, random) { it.score }
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
        val t = weights.temperature.coerceAtLeast(1e-3f)
        fun vec(s: Scored) = s.vec ?: model.vec.transform(s.paper.rankText)

        // Score and similarity are on different scales, so similarity is priced in the
        // score's own unit, the standard deviation. At the default setting a paper that
        // repeats one already chosen loses about two and a half, a close relative at a
        // cosine of 0.3 loses under one, and papers that merely share a field lose almost
        // nothing.
        //
        // It used to subtract the raw cosine from the raw score. With the day's scores
        // within 0.07 of each other and similarities spread up to 0.8, similarity decided
        // nearly every pick and relevance hardly mattered.
        val cost = SIMILARITY_COST * weights.diversity /
            (1f - weights.diversity).coerceAtLeast(0.1f)

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
            val recent = (earlier + chosen).takeLast(MMR_LOOKBACK)
            var bestIdx = 0
            var bestKey = Float.NEGATIVE_INFINITY
            for (i in pool.indices) {
                val cand = pool[i]
                val maxSim = recent.maxOfOrNull { cosine(vec(cand), vec(it)) } ?: 0f
                val key = (scale.of(cand.score) - cost * maxSim) / t + Sampling.gumbel(random)
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
        scale: Sampling.Scale,
        subscribed: Set<String>,
    ): List<Scored> {
        if (n <= 0 || candidates.isEmpty()) return emptyList()
        val byTopic = candidates.groupBy {
            TopicBandit.topicOf(it.paper.categories, subscribed) ?: it.paper.primaryCategory
        }
        // With one topic there is nothing to allocate, and with no history the bandit would
        // just be a uniform draw over topics, which the sampler already handles better.
        if (byTopic.size < 2 || topicHistory.isEmpty()) {
            return selectDiverse(candidates, n, model, random, scale)
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
            val pick = selectDiverse(pool, 1, model, random, scale, earlier = chosen)
                .firstOrNull() ?: break
            pool.remove(pick)
            chosen += pick
        }
        // A short pool or an unlucky run of draws must not leave the digest short.
        if (chosen.size < n) {
            val taken = chosen.map { it.paper.id }.toSet()
            chosen += selectDiverse(
                candidates.filter { it.paper.id !in taken },
                n - chosen.size, model, random, scale, earlier = chosen,
            )
        }
        return chosen
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
        // There is deliberately no shortcut for a pool smaller than the digest. There was
        // one, returning everything as it stood, and it skipped the scope rule below: a law
        // reader with one unread law paper and five fetched for the bridge got six "matches".

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
        // One scale for every draw, so a paper's chances do not depend on which topic's
        // pool it happens to be drawn from.
        val scale = Sampling.Scale(inScope.map { it.score })
        val allocated = allocateByTopic(
            remaining, nRelevance, model, random, topicHistory, scale, subscribed,
        )
        allocated.forEach { picked[it.paper.id] = it }

        // Exploration is the near misses: papers ranked just below the ones chosen to show.
        //
        // It used to take the papers nearest a relevance of 0.5, on the reasoning that 0.5 is
        // where a classifier is least sure. That holds for a calibrated classifier and not for
        // this one. Relevance is shrunk towards a prior of 0.3 until evidence accumulates, so
        // on a real digest every candidate sat between 0.28 and 0.35, and "nearest 0.5" meant
        // "highest scored". Exploration was taking the best papers of the day and labelling
        // them as a test, while the relevance slots got what was left. A band of ranks is
        // where the digest actually decides between showing a paper and not, whatever the
        // scale of the scores, and it cannot reach the top of the list.
        inScope.sortedByDescending { it.relevance }.asSequence()
            .drop(nRelevance)
            .filter { it.paper.id !in picked }
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
        return arrange(picked.values.take(size))
    }

    /**
     * The order the reader sees: the best matches first, the deliberate detours woven in.
     *
     * Everything used to be sorted by score together, and a bridge or exploration card is not
     * worse by construction, so it could land anywhere including the top. On a real digest the
     * bridge card was first on three days in seven and second on most of the rest. Its score
     * was not higher because it was a better match; a conference acceptance multiplies the
     * score, and when relevance is nearly flat that multiplier decides the order.
     *
     * The top of the list is the one place a reader judges the whole digest by, so it belongs
     * to the papers most likely to be right. The detours follow, one after every few matches,
     * with the bridge first among them so that the one thing from outside the reader's fields
     * is seen rather than buried at the bottom.
     */
    private fun arrange(cards: List<Scored>): List<Scored> {
        val matches = cards.filter { it.slot == Slot.RELEVANCE }.sortedByDescending { it.score }
        val detours = ArrayDeque(
            cards.filter { it.slot == Slot.BRIDGE } +
                cards.filter { it.slot == Slot.EXPLORATION }.sortedByDescending { it.score }
        )
        if (detours.isEmpty()) return matches
        val out = ArrayList<Scored>(cards.size)
        matches.forEachIndexed { i, card ->
            out += card
            val shown = i + 1
            if (shown >= HEAD && (shown - HEAD) % SPACING == 0) detours.removeFirstOrNull()?.let { out += it }
        }
        out += detours
        return out
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
        // The vocabulary comes from the day's candidates as well as the training set.
        //
        // Fitted on the training set alone, a real phone's held 4,580 terms from 242 papers,
        // and only 43% of a new paper's words were in it: most of what a candidate said was
        // invisible to the model. Candidates carry no label, so this widens what the model
        // can see without changing what it is taught. On a real reader's library it ranked
        // held-out papers better at every size tried, most when it matters most: from three
        // liked papers, nDCG@25 rose from 0.43 to 0.55.
        val vec = Tfidf().apply { fit(docs + candidates.map { it.rankText }) }
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

        /** Cards at the top that are always the best matches. */
        const val HEAD = 3

        /** Matches between one detour and the next. */
        const val SPACING = 3

        /** Candidates the diversity pass considers. Flat cost regardless of digest size. */
        private const val MMR_POOL = 150

        /** How many recent picks a candidate is compared against for similarity. */
        private const val MMR_LOOKBACK = 12

        /**
         * Standard deviations of score that a cosine similarity of one costs, when variety
         * and relevance are weighted equally. Simulated readers did as well at half and at
         * double this; it is the middle of that range.
         */
        private const val SIMILARITY_COST = 6f
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
