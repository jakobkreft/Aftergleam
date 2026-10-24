package si.jakobkreft.aftergleam.rank

/**
 * How a reason is put into words: what a card says it matches, and what the reading card says
 * the reader has been reading about. One set of rules for both, so they say things the same
 * way, and a fix to one is a fix to the other.
 */
internal object Caption {

    /**
     * [features], vectoriser terms with "_" between words and strongest first, as a reader is
     * shown them: words with spaces, fragments trimmed, repeats and filler gone.
     *
     * Drops a term that merely repeats one already listed, so a chip reads
     * "diffusion, generated" rather than "diffusion, diffusion models, generated".
     * Bigrams and their component words are both real features, but printing both wastes
     * the one line the card gives the explanation.
     */
    fun terms(features: List<String>): List<String> {
        // A two-word term with a verb at either end is a fragment of a sentence, cut out by the
        // tokeniser: "transformer-based world models" gives "based world". Without the verb
        // what is left is the subject, which the pass below can then show as the phrase it
        // belongs to, "world models", where that phrase is in the list too.
        val terms = features.mapNotNull(::trim)
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
    fun samePlural(first: String, second: String): Boolean {
        val a = first.lowercase()
        val b = second.lowercase()
        if (a == b) return true
        val (short, long) = if (a.length < b.length) a to b else b to a
        if (short.length < 3) return false
        return long == short + "s" ||
            long == short + "es" ||
            (short.endsWith("y") && long == short.dropLast(1) + "ies")
    }

    /**
     * [term] as [text] writes it, when the paper writes it with a capital inside a word: "LoRA",
     * "NeRF" and "LLM" for the lower case the vectoriser keeps. A capital that only begins a
     * sentence or a title's word changes nothing, so an "Audio" that opened a sentence stays
     * "audio".
     */
    fun cased(term: String, text: String): String {
        val words = term.split(' ').joinToString("[\\s\\-]+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value }
            .filter { v -> v.indices.any { i -> i > 0 && v[i].isUpperCase() && v[i - 1].isLetterOrDigit() } }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.key?.replace(Regex("[\\s\\-]+"), " ")
            ?: term
    }

    /** Whether two shown terms are the same, word for word, plurals aside: "world model" and "world models". */
    fun samePhrase(a: String, b: String): Boolean {
        val x = a.split(' ')
        val y = b.split(' ')
        return x.size == y.size && x.indices.all { samePlural(x[it], y[it]) }
    }

    /** Whether every word of [part] is in [whole], plurals aside: "world" in "world models". */
    fun within(part: String, whole: String): Boolean {
        val words = whole.split(' ')
        return part.split(' ').all { w -> words.any { samePlural(w, it) } }
    }

    /** [term] without a [FRAGMENT_EDGES] verb at either end, or null if nothing else is left. */
    private fun trim(term: String): String? =
        term.split('_')
            .dropWhile { it in FRAGMENT_EDGES }
            .dropLastWhile { it in FRAGMENT_EDGES }
            .joinToString("_")
            .ifEmpty { null }

    /**
     * Verbs that end or begin a two-word term only when it is a piece of a sentence, never
     * when it names something. Deliberately not all of [FILLER]: its qualifiers and plain
     * nouns begin real subjects, "optimal transport", "state space", "fixed point", and
     * trimming them would cut a subject down to half of itself.
     */
    private val FRAGMENT_EDGES = setOf(
        "based", "using", "used", "via", "show", "shows", "shown", "propose", "proposed",
        "proposes", "demonstrate", "demonstrates", "achieve", "achieves", "achieved",
        "provide", "provides", "introduce", "introduces", "allows", "allowing", "enables",
        "enabling", "requires", "requiring", "yields", "yielding", "improves", "improving",
    )

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
