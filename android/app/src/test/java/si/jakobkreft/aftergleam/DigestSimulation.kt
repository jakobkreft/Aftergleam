package si.jakobkreft.aftergleam

import org.junit.Assume.assumeTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Evidence
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Signal
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.rank.TopicBandit
import si.jakobkreft.aftergleam.rank.Weights
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * Forty mornings for a handful of simulated readers, through the real ranker.
 *
 * The unit tests check that each part does what it says. This checks what the reader gets:
 * how many of the papers they would have wanted reach the digest, whether the top of the list
 * holds the best of them, and whether a reader with three interests keeps seeing all three.
 * The readers, their hidden interests and the papers come from prototype/sim_prep.py; the
 * ranker only ever learns those interests from the signals the reader gives what it shows.
 *
 * Skipped unless AFTERGLEAM_SIM names the folder that script wrote, as an absolute path:
 *
 *     AFTERGLEAM_SIM=$PWD/../prototype/out/sim \
 *         ./gradlew :app:testDebugUnitTest --tests '*DigestSimulation*'
 *
 * AFTERGLEAM_SIM_LABEL names the run in results.tsv, so runs before and after a change can be
 * compared. AFTERGLEAM_SIM_T and AFTERGLEAM_SIM_DIV override the temperature and diversity,
 * AFTERGLEAM_SIM_SEEDS the number of runs per reader, and AFTERGLEAM_SIM_ONLY picks readers
 * by name. Gradle does not know about these, so add --rerun to run it again.
 */
class DigestSimulation {

    private class Persona(val name: String, val subscribed: Set<String>, val interests: Int)

    private class Taste(val utility: Float, val interest: Int)

    private data class Morning(
        val day: Int,
        /** Cards the reader would like: utility in the top 3% of their fields. */
        val hits: Int,
        /** The most such cards any digest could have held that morning. */
        val possible: Int,
        /** Share of cards in the top 10% of the reader's fields. */
        val relevant: Float,
        /** Mean utility percentile of the ordinary cards, among the morning's candidates. */
        val matches: Float,
        /** The same for the first three cards, which is where a digest is judged. */
        val head: Float,
        /** Share of the model's own top 25 that the reader actually sees. */
        val survival: Float?,
        /** Interests with a good card that morning, over interests that had one available. */
        val coverage: Float?,
        /** Pairs of cards that are near copies of each other. */
        val duplicates: Int?,
        /** Share of cards that a re-rank with no new data keeps. */
        val overlap: Float?,
        /** The same two measures for the model's own top 25: the most composition could keep. */
        val modelHits: Int,
        val modelMatches: Float?,
    )

    @Test
    fun `forty mornings`() {
        val dir = System.getenv("AFTERGLEAM_SIM")?.let(::File)
        assumeTrue("AFTERGLEAM_SIM is not set", dir?.isDirectory == true)
        dir!!
        val label = System.getenv("AFTERGLEAM_SIM_LABEL") ?: "unnamed"
        val seeds = System.getenv("AFTERGLEAM_SIM_SEEDS")?.toInt() ?: 3
        val base = Weights()
        val weights = base.copy(
            temperature = System.getenv("AFTERGLEAM_SIM_T")?.toFloat() ?: base.temperature,
            diversity = System.getenv("AFTERGLEAM_SIM_DIV")?.toFloat() ?: base.diversity,
        )

        val dayOf = HashMap<String, Int>()
        val papers = File(dir, "papers.tsv").readLines().map { line ->
            val f = line.split('\t')
            dayOf[f[0]] = f[1].toInt()
            Paper(
                id = f[0], title = f[3], abstract = f[4], authors = emptyList(),
                categories = f[2].split(' '), published = "", updated = "",
                comments = f[5], journalRef = f[6],
            )
        }
        val only = System.getenv("AFTERGLEAM_SIM_ONLY")?.takeIf { it.isNotBlank() }?.split(',')?.toSet()
        val personas = File(dir, "personas.tsv").readLines().map { line ->
            val f = line.split('\t')
            Persona(f[0], f[1].split(' ').toSet(), f[2].toInt())
        }.filter { only == null || it.name in only }
        val tastes = HashMap<String, HashMap<String, Taste>>()
        File(dir, "utility.tsv").forEachLine { line ->
            val f = line.split('\t')
            tastes.getOrPut(f[0]) { HashMap() }[f[1]] = Taste(f[2].toFloat(), f[3].toInt())
        }

        val out = File(dir, "results.tsv")
        println("\n== $label  T=${weights.temperature} diversity=${weights.diversity}")
        println("persona      week  hits/possible  relevant  matches  head  survival  " +
            "coverage  dups  overlap  model-hits  model-matches")
        for (persona in personas) {
            val taste = tastes.getValue(persona.name)
            val world = papers.filter { it.id in taste }
            val runs = (1..seeds).map { simulate(persona, world, dayOf, taste, it, weights) }
            for ((span, days) in listOf("1" to (0..6), "2-6" to (7 until DAYS))) {
                val m = runs.flatten().filter { it.day in days }
                val possible = m.sumOf { it.possible }.coerceAtLeast(1)
                val row = listOf(
                    "%.2f".format(m.sumOf { it.hits }.toFloat() / possible),
                    "%.2f".format(m.map { it.relevant }.average()),
                    "%.2f".format(m.map { it.matches }.average()),
                    "%.2f".format(m.map { it.head }.average()),
                    m.mapNotNull { it.survival }.averageOrNaN(),
                    m.mapNotNull { it.coverage }.averageOrNaN(),
                    m.mapNotNull { it.duplicates?.toFloat() }.averageOrNaN(),
                    m.mapNotNull { it.overlap }.averageOrNaN(),
                    "%.2f".format(m.sumOf { it.modelHits }.toFloat() / possible),
                    m.mapNotNull { it.modelMatches }.averageOrNaN(),
                )
                println("%-12s %-5s %s".format(persona.name, span, row.joinToString("  ")))
                out.appendText((listOf(label, persona.name, span) + row).joinToString("\t") + "\n")
            }
        }
    }

    private fun List<Float>.averageOrNaN(): String =
        if (isEmpty()) "  -  " else "%.2f".format(average())

    private fun simulate(
        persona: Persona,
        world: List<Paper>,
        dayOf: Map<String, Int>,
        taste: Map<String, Taste>,
        seed: Int,
        weights: Weights,
    ): List<Morning> {
        val inField = world.filter { p -> p.categories.any { it in persona.subscribed } }
        val sorted = inField.map { taste.getValue(it.id).utility }.sorted()
        fun quantile(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
        val like = quantile(0.97)
        val relevant = quantile(0.90)
        val poor = quantile(0.50)

        val byId = world.associateBy { it.id }
        val newestFirst = world.sortedWith(
            compareByDescending<Paper> { dayOf.getValue(it.id) }.thenByDescending { it.id }
        )
        val signals = HashMap<String, MutableSet<Signal>>()
        val lastShown = HashMap<String, Int>()
        val reader = Random(seed * 31 + 7)
        val today = LocalDate.now()
        val mornings = mutableListOf<Morning>()

        for (day in 0 until DAYS) {
            // What Db.recentPapers returns: the newest first, with ages as of this morning.
            val recent = newestFirst.filter { dayOf.getValue(it.id) <= day }
            fun dated(p: Paper) =
                p.copy(published = today.minusDays((day - dayOf.getValue(p.id)).toLong()).toString())
            val candidates = recent.take(400).map(::dated)
            val candidateIds = candidates.map { it.id }.toSet()
            val negatives = recent.take(900).filter { it.id !in candidateIds }.map { it.rankText }
            val rated = signals.mapNotNull { (id, s) ->
                Evidence(id, s).label()?.let { RatedDoc(id, byId.getValue(id).rankText, it) }
            }
            val seen = lastShown.keys.toSet()
            val history = topicHistory(lastShown, signals, day, byId, persona.subscribed)

            val ranker = Ranker(weights)
            val model = ranker.train(candidates, rated, negatives)
            fun draw(salt: Int) = ranker.digest(
                candidates = candidates, rated = rated, seen = seen,
                subscribed = persona.subscribed, size = SIZE,
                random = Random(seed * 7919 + day + salt), negativePool = negatives,
                evidenceCount = rated.size, topicHistory = history, prebuilt = model,
            )
            val cards = draw(0)

            val ratedIds = rated.mapNotNull { it.paperId }.toSet()
            val fresh = candidates.filter { p ->
                p.id !in seen && p.id !in ratedIds && p.categories.any { it in persona.subscribed }
            }
            val freshU = fresh.map { taste.getValue(it.id).utility }.sorted()
            fun pct(u: Float) = freshU.count { it < u }.toFloat() / freshU.size.coerceAtLeast(1)
            val utility = cards.associate { it.paper.id to taste.getValue(it.paper.id).utility }
            val ordinary = cards.filter { it.slot == Slot.RELEVANCE }

            val modelTop = model?.let { m ->
                fresh.map { it.id to m.clf.predict(m.vec.transform(it.rankText)) }
                    .sortedByDescending { it.second }.take(SIZE).map { it.first }
            }
            val survival = modelTop?.let { top ->
                cards.count { it.paper.id in top }.toFloat() / top.size.coerceAtLeast(1)
            }
            val available = fresh.filter { taste.getValue(it.id).utility >= relevant }
                .map { taste.getValue(it.id).interest }.toSet()
            val covered = cards.filter { utility.getValue(it.paper.id) >= relevant }
                .map { taste.getValue(it.paper.id).interest }.toSet()
            val duplicates = model?.let { m ->
                val v = cards.map { m.vec.transform(it.paper.rankText) }
                var n = 0
                for (i in v.indices) for (j in i + 1 until v.size) {
                    if (!v[i].isEmpty() && !v[j].isEmpty() && v[i].dot(v[j]) > 0.5f) n++
                }
                n
            }
            val overlap = if (day % 5 == 4) {
                val again = draw(100_000).map { it.paper.id }.toSet()
                cards.count { it.paper.id in again }.toFloat() / cards.size.coerceAtLeast(1)
            } else null

            mornings += Morning(
                day = day,
                hits = utility.values.count { it >= like },
                possible = freshU.takeLast(SIZE).count { it >= like },
                relevant = utility.values.count { it >= relevant }.toFloat() / cards.size.coerceAtLeast(1),
                matches = ordinary.map { pct(utility.getValue(it.paper.id)) }.average().toFloat(),
                head = cards.take(3).map { pct(utility.getValue(it.paper.id)) }.average().toFloat(),
                survival = survival,
                coverage = if (available.isEmpty()) null
                    else covered.intersect(available).size.toFloat() / available.size,
                duplicates = duplicates,
                overlap = overlap,
                modelHits = modelTop?.count { taste.getValue(it).utility >= like } ?: 0,
                modelMatches = modelTop?.map { pct(taste.getValue(it).utility) }?.average()?.toFloat(),
            )

            // The reader goes down the list, a little less attentive towards the end.
            cards.forEachIndexed { pos, card ->
                val id = card.paper.id
                lastShown[id] = day
                val u = utility.getValue(id)
                val attention = 1f - 0.3f * pos / SIZE
                val r = reader.nextFloat()
                val signal = when {
                    u >= like -> when {
                        r < 0.5f * attention -> Signal.LIKED
                        r < 0.8f * attention -> Signal.DWELLED
                        else -> null
                    }
                    u >= relevant -> when {
                        r < 0.25f * attention -> Signal.OPENED
                        r < 0.35f * attention -> Signal.DWELLED
                        else -> null
                    }
                    u < poor -> if (r < 0.04f) Signal.DISLIKED else null
                    else -> if (r < 0.03f * attention) Signal.OPENED else null
                }
                if (signal != null) signals.getOrPut(id) { mutableSetOf() } += signal
            }
        }
        return mornings
    }

    /** What Db.topicHistory computes, from the simulated ledger. */
    private fun topicHistory(
        lastShown: Map<String, Int>,
        signals: Map<String, Set<Signal>>,
        day: Int,
        byId: Map<String, Paper>,
        subscribed: Set<String>,
    ): Map<String, Pair<Float, Float>> {
        val engaged = HashMap<String, Float>()
        val ignored = HashMap<String, Float>()
        for ((id, shownOn) in lastShown) {
            val topic = TopicBandit.topicOf(byId.getValue(id).categories, subscribed) ?: continue
            val w = TopicBandit.recency((day - shownOn).toFloat())
            val bucket = if (signals[id].orEmpty().any { it in ENGAGED }) engaged else ignored
            bucket[topic] = (bucket[topic] ?: 0f) + w
        }
        return (engaged.keys + ignored.keys).associateWith {
            (engaged[it] ?: 0f) to (ignored[it] ?: 0f)
        }
    }

    private companion object {
        const val DAYS = 40
        const val SIZE = 25
        val ENGAGED = setOf(
            Signal.LIKED, Signal.READ_PAGES, Signal.SHARED, Signal.DOWNLOADED, Signal.SAVED,
            Signal.DWELLED,
        )
    }
}
