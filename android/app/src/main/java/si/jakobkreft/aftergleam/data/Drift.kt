package si.jakobkreft.aftergleam.data

import si.jakobkreft.aftergleam.rank.Caption

/**
 * What the reader has been reading about lately, how that differs from the fortnight before,
 * and where the model got it wrong.
 *
 * A theme is told in the words the digest's own cards use. Each paper read is given its
 * reason, the words it shares with the kept paper it is most like (see
 * [si.jakobkreft.aftergleam.rank.Explain.reasons]), and a theme is a reason that comes back
 * across papers. Those words are what two particular papers have in common, so they name
 * subjects: "audio", "LoRA fine tuning", "cross modal".
 *
 * This used to count the words of the papers liked in each fortnight and report the ones whose
 * share moved most. Nothing weighed a word by what it says, so the words every abstract uses
 * moved most by chance, and the card told a reader they were reading more of "both, four,
 * show". Only saved and liked papers counted, too: nine of the thirty five a reader had opened
 * that fortnight.
 *
 * Cheap: a few dozen papers against a hundred or so kept ones, over vectors the digest has
 * already built. No network and no model of its own.
 */
object Drift {

    data class Report(
        /** The strongest themes of the last fortnight, whether or not anything came before. */
        val lately: List<String>,
        /** Themes with a larger share of the last fortnight than of the one before. */
        val more: List<String>,
        /** Themes with a larger share of the fortnight before than of the last. */
        val less: List<String>,
        val recurringAuthors: List<String>,
        /** How many exploration cards the user actually rated, either way. */
        val explorationJudged: Int,
        val explorationLiked: Int,
        /** Papers read in each fortnight, the rejected ones not counted. */
        val recentCount: Int,
        val earlierCount: Int,
        /**
         * Too little to say anything: under [MIN_PAPERS] papers read lately, or no model yet
         * to tell what they are about.
         */
        val thin: Boolean,
    ) {
        /** Whether the fortnight before had enough reading to compare with. */
        val compared: Boolean get() = earlierCount >= MIN_PAPERS

        /**
         * The honest line about exploration. Reported even when it is unflattering, which
         * is the whole point of including it.
         */
        fun explorationNote(): String? = when {
            explorationJudged < 5 -> null
            explorationLiked == 0 ->
                "None of the $explorationJudged exploration cards you rated landed. The " +
                    "model is probing in the wrong direction."
            explorationLiked * 4 < explorationJudged ->
                "$explorationLiked of $explorationJudged exploration cards you rated " +
                    "landed. Still mostly missing."
            else ->
                "$explorationLiked of $explorationJudged exploration cards you rated landed."
        }
    }

    /** Papers read in a fortnight before it says anything about that fortnight. */
    const val MIN_PAPERS = 5

    private const val TOP_N = 3

    /** Words of a paper's reason that count, as many as its card shows. */
    private const val REASON_WORDS = 3

    /** Papers a reason must come back in to be a theme rather than one paper's subject. */
    private const val MIN_SUPPORT = 2

    /** How much larger a theme's share must be to have grown, or shrunk the other way. */
    private const val CHANGE = 1.5f

    /**
     * @param recent papers read in the last fortnight, with how interested the reader was
     * @param earlier the same for the fortnight before
     * @param reasons what a paper is about, in the reader's terms, or null when there is no
     *   model yet to say
     */
    fun compute(
        recent: List<Pair<Paper, Float>>,
        earlier: List<Pair<Paper, Float>>,
        reasons: ((Paper) -> List<String>)?,
        explorationJudged: Int,
        explorationLiked: Int,
    ): Report {
        val read = recent.filter { it.second > 0f }
        val readBefore = earlier.filter { it.second > 0f }
        val now = if (reasons != null) shares(read, reasons) else emptyMap()
        val before = if (reasons != null) shares(readBefore, reasons) else emptyMap()

        // Shares rather than counts, so a fortnight with simply more reading in it does not
        // register as every theme growing at once.
        fun grew(a: Map<String, Float>, b: Map<String, Float>): List<String> =
            a.entries.filter { (t, s) -> s > CHANGE * shareOf(b, t) }
                .sortedByDescending { (t, s) -> s - shareOf(b, t) }
                .map { it.key }
                .let(::distinct)

        return Report(
            lately = distinct(now.entries.sortedByDescending { it.value }.map { it.key }),
            more = grew(now, before),
            less = grew(before, now),
            recurringAuthors = recurringAuthors(recent + earlier),
            explorationJudged = explorationJudged,
            explorationLiked = explorationLiked,
            recentCount = read.size,
            earlierCount = readBefore.size,
            thin = read.size < MIN_PAPERS || reasons == null,
        )
    }

    /**
     * Each theme's share of a fortnight's reading: the interest of the papers whose reason
     * names it, over the interest of everything read. A paper opened counts for a quarter of
     * one liked, the same weights the model learns from.
     */
    private fun shares(read: List<Pair<Paper, Float>>, reasons: (Paper) -> List<String>): Map<String, Float> {
        val total = read.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) return emptyMap()
        // Grouped plurals aside: one paper's reason can say "world model" and another's
        // "world models", and each alone would fall short of two papers.
        val themes = mutableListOf<Theme>()
        for ((paper, interest) in read) {
            for (term in reasons(paper).take(REASON_WORDS)) {
                val theme = themes.firstOrNull { Caption.samePhrase(it.name, term) }
                    ?: Theme(term).also { themes += it }
                // As a paper wrote it, when one did: "LoRA" over another paper's "lora".
                if (term != term.lowercase() && theme.name == theme.name.lowercase()) theme.name = term
                theme.weight += interest
                theme.papers++
            }
        }
        return themes.filter { it.papers >= MIN_SUPPORT }.associate { it.name to it.weight / total }
    }

    private class Theme(var name: String) {
        var weight = 0f
        var papers = 0
    }

    /** [theme]'s share of [shares], found plurals aside. */
    private fun shareOf(shares: Map<String, Float>, theme: String): Float =
        shares.entries.firstOrNull { Caption.samePhrase(it.key, theme) }?.value ?: 0f

    /**
     * The first [TOP_N] themes, each a different subject. Two papers can name one subject at
     * different lengths, "world" and "world models"; the phrase says more, so it takes the
     * word's place.
     */
    private fun distinct(ranked: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (theme in ranked) {
            val i = out.indexOfFirst { Caption.within(it, theme) || Caption.within(theme, it) }
            when {
                i < 0 -> if (out.size < TOP_N) out += theme
                theme.split(' ').size > out[i].split(' ').size -> {
                    out[i] = theme
                    out.removeAll { it !== theme && Caption.within(it, theme) }
                }
            }
        }
        return out
    }

    private fun recurringAuthors(rated: List<Pair<Paper, Float>>): List<String> {
        val counts = HashMap<String, Int>()
        for ((paper, interest) in rated) {
            if (interest < 0.5f) continue
            for (a in paper.authors.map { it.trim() }.filter { it.isNotBlank() }.toSet()) {
                counts[a] = (counts[a] ?: 0) + 1
            }
        }
        return counts.entries.filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(TOP_N)
            .map { "${it.key} (${it.value})" }
    }
}
