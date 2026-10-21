package si.jakobkreft.aftergleam.data

/**
 * What a search asks for: its quoted phrases and its other words, every one of them required,
 * in any order and anywhere in the paper. The one reading of a query, shared by arXiv, the
 * other servers and the phone, so that a search means the same thing wherever it is answered.
 *
 * Every word, not the query as a phrase, because that is what a search is for: finding papers
 * that are about all of it. "CRISPR gene editing" as one phrase is in 1 of the 15 arXiv papers
 * that are about it. Quotes are how to ask for a phrase, as everywhere else; and papers that
 * do say the words together are ranked first (see [si.jakobkreft.aftergleam.rank.SearchRanker]).
 *
 * Words too common to narrow anything, "of" or "the", are not required, since requiring them
 * only loses results. Inside quotes they are kept: "theory of mind" is a phrase with an "of".
 */
data class SearchTerms(val phrases: List<String>, val words: List<String>) {

    /** Everything that must be found, the phrases first. */
    val all: List<String> get() = phrases + words

    /** Whether [text] contains every phrase and word, ignoring capitals. */
    fun foundIn(text: String): Boolean {
        val t = text.lowercase()
        return all.all { it.lowercase() in t }
    }

    companion object {
        /** Words dropped from a query: requiring them narrows nothing. */
        val STOP_WORDS = setOf(
            "a", "an", "and", "as", "at", "by", "for", "from", "in", "into", "is", "of", "on",
            "or", "the", "to", "via", "with",
        )

        fun of(query: String): SearchTerms {
            val phrases = QUOTED.findAll(query).map { it.groupValues[1].trim().replace(SPACES, " ") }
                .filter { it.isNotEmpty() }.toList()
            val words = query.replace(OPEN_QUOTES, " ")
                .split(BETWEEN_WORDS)
                .map { it.trim('-', '.') }
                .filter { it.length > 1 && it.lowercase() !in STOP_WORDS }
            return SearchTerms(
                phrases.distinctBy { it.lowercase() },
                words.distinctBy { it.lowercase() }.filter { w -> phrases.none { it.equals(w, ignoreCase = true) } },
            )
        }

        // Curly quotes as well as straight ones: a phrase copied from a paper or a web page
        // arrives in “these”, and read as two words it would be searched for apart.
        private const val QUOTE = "\"\u201c\u201d\u201e\u00ab\u00bb"
        private val QUOTED = Regex("[$QUOTE]([^$QUOTE]+)[$QUOTE]")
        private val OPEN_QUOTES = Regex("[$QUOTE][^$QUOTE]*[$QUOTE]?")
        private val BETWEEN_WORDS = Regex("[^\\p{L}\\p{N}+#.\\-]+")
        private val SPACES = Regex("\\s+")
    }
}
