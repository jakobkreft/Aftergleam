package si.jakobkreft.aftergleam.data

import kotlin.math.abs

/**
 * Words the reader watches for: "H-Net", "Sentinel-2", "C. elegans", "long COVID".
 *
 * Literal, on purpose. A paper matches when its title or abstract contains the keyword as
 * written, ignoring only capitals, how its parts are joined (a hyphen, a space, a dot, LaTeX's
 * "~" or nothing: H-Net, H Net and HNet are one name) and a plural. It never matches inside a
 * longer word, so H-Net does not find U-Net and "net" finds nothing in "subnetwork".
 *
 * The first version treated a tag as a topic and matched it with the ranker's tokens, which
 * drop words of one or two letters and split at hyphens. "H-net" became "net", and 21 of the 35
 * papers it was said to be on never mentioned H-Net; "RL" became nothing and matched no paper
 * at all. A reader opened a labelled paper, searched it for their word and did not find it,
 * which is the one check a label has to pass. Twenty keywords across fields measured against
 * real papers (prototype/keyword_audit.py): for ordinary words the two rules agreed exactly,
 * and for names only the literal one was right.
 *
 * Keywords do not train the model. The model learns from how the reader reacts to the papers
 * that mention them, as it does from everything else they read.
 */
object Keywords {

    const val MAX = 8
    const val MAX_LENGTH = 60

    /**
     * How far back a keyword's papers are looked for, when fetching and when ranking.
     *
     * Two months, not two weeks. A rare name is mentioned a few times a season: arXiv has
     * fifteen papers that ever mention H-Net, the newest six weeks old, and a two-week window
     * told the reader there were none while that paper sat unseen. A digest never shows a
     * paper twice, so a longer window costs a busy keyword nothing; its newest still come first.
     */
    const val WINDOW_DAYS = 60L

    /** Papers asked of arXiv per keyword per fetch: the newest ones. */
    const val FETCH_PER_KEYWORD = 40

    /** One typed keyword: trimmed, spaces collapsed, length bounded. */
    fun normalise(raw: String): String? =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().ifBlank { null }

    /** Typed text as keywords. Commas, semicolons and new lines separate them. */
    fun split(raw: String): List<String> = raw.split(Regex("[,;\\n]+")).mapNotNull(::normalise)

    /** The keyword's letters and digits, in runs: "C. elegans" is C and elegans. */
    fun parts(keyword: String): List<String> = PART.findAll(keyword).map { it.value }.toList()

    private val patterns = HashMap<String, Regex?>()

    /** The pattern a keyword is found by, or null when it has no letters or digits at all. */
    fun pattern(keyword: String): Regex? = synchronized(patterns) {
        patterns.getOrPut(keyword) {
            val parts = parts(keyword)
            if (parts.isEmpty()) return@getOrPut null
            val head = parts.dropLast(1).map { Regex.escape(it) }
            val body = (head + forms(parts.last())).joinToString(SEPARATOR)
            Regex("(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }
    }

    /**
     * The last part, singular or plural: "satellite images" also finds a satellite image, and
     * "perovskite" finds perovskites.
     */
    private fun forms(last: String): String {
        val f = mutableListOf(last, last + "s", last + "es")
        if (last.length > 3 && last.endsWith("s", ignoreCase = true)) f += last.dropLast(1)
        if (last.length > 4 && last.endsWith("es", ignoreCase = true)) f += last.dropLast(2)
        return f.distinctBy { it.lowercase() }.sortedByDescending { it.length }
            .joinToString("|", "(?:", ")") { Regex.escape(it) }
    }

    /** Whether [text] mentions [keyword]. */
    fun mentions(text: String, keyword: String): Boolean = pattern(keyword)?.containsMatchIn(text) == true

    /** The first of [keywords], in the reader's order, that the paper's title or abstract mentions. */
    fun mentionedBy(paper: Paper, keywords: List<String>): String? {
        if (keywords.isEmpty()) return null
        val text = paper.title + "\n" + paper.abstract
        return keywords.firstOrNull { mentions(text, it) }
    }

    /** Where any of [keywords] occurs in [text], for highlighting, sorted and not overlapping. */
    fun occurrences(text: String, keywords: List<String>): List<IntRange> {
        val found = keywords.flatMap { k -> pattern(k)?.findAll(text)?.map { it.range }?.toList().orEmpty() }
            .sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (r in found) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last) out[out.lastIndex] = last.first..maxOf(last.last, r.last)
            else out += r
        }
        return out
    }

    /**
     * How often each word occurs in [texts], by document, for [suggest].
     *
     * Built from the papers on the device, so the words it knows are the words the reader's
     * own fields use.
     */
    fun vocabulary(texts: Sequence<String>): Map<String, Int> {
        val df = HashMap<String, Int>()
        for (t in texts) {
            for (w in WORD.findAll(t.lowercase()).map { it.value }.filter { it.length >= 3 }.toHashSet()) {
                df[w] = (df[w] ?: 0) + 1
            }
        }
        return df
    }

    /**
     * A respelling of [keyword] for the reader to accept or decline, or null when there is none.
     *
     * Never applied on its own: a literal keyword spelt wrongly matches nothing, so the question
     * is worth asking, but a silent change would turn a name into an ordinary word. A word is
     * only questioned when no paper on the device uses it and one within an edit or two does.
     * Words that look deliberate are left alone altogether: anything with a capital after its
     * first letter (LoRA, GNN, MRI), digits or a hyphen.
     */
    fun suggest(keyword: String, vocabulary: Map<String, Int>): String? {
        if (vocabulary.isEmpty()) return null
        var changed = false
        val out = TOKEN.replace(keyword) { m ->
            val w = m.value
            if (!questionable(w)) return@replace w
            val lw = w.lowercase()
            if ((vocabulary[lw] ?: 0) >= 1) return@replace w
            val limit = if (lw.length <= 5) 1 else 2
            val best = vocabulary.entries.asSequence()
                .filter { abs(it.key.length - lw.length) <= limit }
                .map { it to distance(lw, it.key, limit) }
                .filter { it.second <= limit }
                .minWithOrNull(compareBy<Pair<Map.Entry<String, Int>, Int>> { it.second }
                    .thenByDescending { it.first.value })
                ?.first?.key ?: return@replace w
            changed = true
            if (w[0].isUpperCase()) best.replaceFirstChar { it.uppercase() } else best
        }
        return if (changed && out != keyword) out else null
    }

    private fun questionable(w: String): Boolean =
        w.length >= 4 && w.all { it.isLetter() } && w.drop(1).none { it.isUpperCase() }

    /** Edit distance, giving up early once it is past [cap]. */
    internal fun distance(a: String, b: String, cap: Int): Int {
        if (abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > cap) return cap + 1
            prev = cur
        }
        return prev[b.length]
    }

    private val PART = Regex("[\\p{L}\\p{N}]+")

    /** Between two parts: up to two of hyphen, dashes, space, dot, slash or LaTeX's tilde. */
    private const val SEPARATOR = "[\\s\\-\\u2010\\u2011\\u2013\\u2014./~]{0,2}"

    private val WORD = Regex("[a-z]+")
    private val TOKEN = Regex("[\\p{L}\\p{N}-]+")
}
