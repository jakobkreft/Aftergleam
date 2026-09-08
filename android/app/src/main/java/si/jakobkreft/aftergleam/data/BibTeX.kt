package si.jakobkreft.aftergleam.data

/**
 * BibTeX and RIS parsing, ported from the Python prototype that was measured against a real
 * library: 38 of 52 research entries resolved (73%), with no false matches.
 *
 * Two things that measurement taught, both of which shape this code:
 *
 *  * A hand-written .bib may carry no `eprint` field and no DOI at all. In the library
 *    tested, zero of 104 entries had either, so title search is not a fallback path, it is
 *    the main one.
 *  * Most unmatched entries *should* stay unmatched. Knuth's books, the stock `xampl.bib`
 *    demo entries that ship with LaTeX, and pre-arXiv classics have no arXiv record. An
 *    importer that tried harder on those would only manufacture false matches.
 */
object BibTeX {

    data class Entry(
        val key: String,
        val title: String,
        val year: String,
        val arxivId: String,
        val doi: String,
    )

    // 1706.03762 or 1706.03762v5, and the pre-2007 form math/0211159.
    private val ARXIV_ID = Regex(
        """(?:arxiv[.:/ ]+)?(\d{4}\.\d{4,5}|[a-z-]+(?:\.[A-Z]{2})?/\d{7})(?:v\d+)?""",
        RegexOption.IGNORE_CASE,
    )
    private val DOI_ARXIV = Regex("""10\.48550/arxiv\.(\S+)""", RegexOption.IGNORE_CASE)
    private val VERSION_SUFFIX = Regex("""v\d+$""")

    fun parse(text: String): List<Entry> =
        if (text.trimStart().startsWith("TY  -")) parseRis(text) else parseBibtex(text)

    /** Brace-counting splitter. A regex per field cannot handle nested braces in titles. */
    fun parseBibtex(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        Regex("""@(\w+)\s*\{""").findAll(text).forEach { m ->
            if (m.groupValues[1].lowercase() in setOf("comment", "preamble", "string")) {
                return@forEach
            }
            var i = m.range.last + 1
            val start = i
            var depth = 1
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            val body = text.substring(start, (i - 1).coerceAtLeast(start))
            val key = body.substringBefore(',').trim()
            val fields = readFields(body)

            val blob = fields.values.joinToString(" ")
            var aid = ""
            val prefix = (fields["archiveprefix"] ?: "") + (fields["eprinttype"] ?: "")
            if (!fields["eprint"].isNullOrBlank() && prefix.contains("arxiv", true)) {
                aid = fields["eprint"]!!
            }
            if (aid.isBlank()) DOI_ARXIV.find(blob)?.let { aid = it.groupValues[1] }
            if (aid.isBlank()) {
                for (f in listOf("url", "note", "howpublished", "eprint")) {
                    val found = ARXIV_ID.find(fields[f] ?: "")
                    if (found != null) {
                        aid = found.groupValues[1]
                        break
                    }
                }
            }
            out += Entry(
                key = key,
                title = fields["title"] ?: "",
                year = fields["year"] ?: "",
                arxivId = VERSION_SUFFIX.replace(aid, ""),
                doi = fields["doi"] ?: "",
            )
        }
        return out
    }

    private fun readFields(body: String): Map<String, String> {
        val fields = HashMap<String, String>()
        Regex("""(\w+)\s*=\s*""").findAll(body).forEach { fm ->
            var j = fm.range.last + 1
            if (j >= body.length) return@forEach
            val value: String = when (body[j]) {
                '{' -> {
                    var d = 1
                    val s2 = j + 1
                    j++
                    while (j < body.length && d > 0) {
                        when (body[j]) {
                            '{' -> d++
                            '}' -> d--
                        }
                        j++
                    }
                    body.substring(s2, (j - 1).coerceAtLeast(s2))
                }
                '"' -> {
                    val s2 = j + 1
                    val end = body.indexOf('"', s2)
                    body.substring(s2, if (end > 0) end else body.length)
                }
                else -> Regex("""[^,\n]*""").find(body.substring(j))?.value ?: ""
            }
            fields[fm.groupValues[1].lowercase()] = stripBraces(value)
        }
        return fields
    }

    fun parseRis(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var cur = HashMap<String, String>()
        for (line in text.lines()) {
            val m = Regex("""^([A-Z][A-Z0-9])\s+-\s*(.*)$""").find(line) ?: continue
            val (tag, value) = m.destructured
            when (tag) {
                "TY" -> cur = HashMap()
                "ER" -> {
                    val blob = cur.values.joinToString(" ")
                    val aid = if (blob.contains("arxiv", true)) {
                        ARXIV_ID.find(blob)?.groupValues?.get(1) ?: ""
                    } else ""
                    out += Entry(
                        key = cur["ID"] ?: "",
                        title = cur["TI"] ?: cur["T1"] ?: "",
                        year = (cur["PY"] ?: cur["Y1"] ?: "").take(4),
                        arxivId = VERSION_SUFFIX.replace(aid, ""),
                        doi = cur["DO"] ?: "",
                    )
                }
                else -> cur[tag] = ((cur[tag] ?: "") + " " + value).trim()
            }
        }
        return out
    }

    private fun stripBraces(s: String) = s.replace(Regex("""[{}]"""), "").trim()
        .replace(Regex("""\s+"""), " ")

    // --- title matching ---

    private val LATEX = Regex("""\\[a-zA-Z]+\s*|[${'$'}{}\\]""")

    /** Strips LaTeX so "Any-size-diffusion: ${'$'}\infty${'$'}-Diff" can be searched. */
    fun cleanTitle(t: String): String =
        LATEX.replace(t, " ").replace(Regex("""\s+"""), " ").trim()

    fun normalise(t: String): String =
        t.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /**
     * Jaccard overlap of title words. The 0.6 threshold matters: arXiv's search happily
     * returns adjacent papers, so a query for "Denoising diffusion probabilistic models"
     * also matches a dozen works that merely cite it. Every accepted match in the measured
     * run scored about 0.99.
     */
    fun similarity(a: String, b: String): Float {
        val ta = normalise(a).split(" ").filter { it.isNotBlank() }.toSet()
        val tb = normalise(b).split(" ").filter { it.isNotBlank() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        return ta.intersect(tb).size.toFloat() / ta.union(tb).size
    }
}
