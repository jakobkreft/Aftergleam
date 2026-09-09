package si.jakobkreft.aftergleam.data

/**
 * What the reader did with a paper, and what it is worth as training evidence.
 *
 * This replaces the interest slider. A slider asks for a calibrated number in exchange for a
 * vague feeling: one reader's "quite interested" is 95 and another's is 55, the model's own
 * prediction sitting beside it anchors the answer, and reading a paper properly gives you
 * more reasons to find fault, so the signal ends up anti-correlated with attention.
 *
 * Behaviour does not have those problems. Downloading a PDF is unambiguous in any reader's
 * private scale, and it costs nobody any effort to report.
 *
 * On negatives: the original design banned implicit signals outright. That was right about
 * negatives and wrong about positives. Everything not opened outnumbers everything opened by
 * roughly thirty to one, so [PASSED] is admitted at a twentieth of a like and its total mass
 * is capped during training. A download is not ambiguous and needs no such caution.
 */
enum class Signal(val weight: Float) {
    LIKED(1.0f),
    READ_PAGES(0.9f),
    SHARED(0.85f),
    DOWNLOADED(0.7f),
    SAVED(0.6f),
    DWELLED(0.4f),
    OPENED(0.25f),
    PASSED(-0.05f),
    DISLIKED(-1.0f);

    val positive: Boolean get() = weight > 0f
}

/**
 * The evidence for one paper.
 *
 * The label is the *strongest* event, not a sum: opening a paper, downloading it and reading
 * it is one endorsement expressed three ways, and adding them would let a single enthusiastic
 * afternoon outweigh a month of considered judgements.
 */
data class Evidence(
    val paperId: String,
    val signals: Set<Signal>,
) {
    val explicit: Boolean
        get() = Signal.LIKED in signals || Signal.DISLIKED in signals

    /** Training label in 0..1, or null when there is nothing to learn from. */
    fun label(): Float? {
        if (signals.isEmpty()) return null
        // An explicit judgement overrides everything inferred from behaviour: someone who
        // read six pages and then said "not for me" means the second thing.
        if (Signal.DISLIKED in signals) return 0f
        if (Signal.LIKED in signals) return 1f

        val best = signals.filter { it.positive }.maxByOrNull { it.weight }
        if (best != null) return best.weight
        return if (Signal.PASSED in signals) 0f else null
    }

    /** How much this example counts, so a skip cannot shout as loudly as a download. */
    fun sampleWeight(): Float = when {
        explicit -> 1.0f
        signals.any { it.positive } -> 1.0f
        else -> -Signal.PASSED.weight   // 0.05
    }
}
