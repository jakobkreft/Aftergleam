package si.jakobkreft.aftergleam.data

/**
 * What the reader did with a paper, and what it is worth as training evidence.
 *
 * This replaces the interest slider. A slider asks for a calibrated number in exchange for a
 * vague feeling: one reader's "quite interested" is 95 and another's is 55, the model's own
 * prediction sitting beside it anchors the answer, and reading a paper properly gives you
 * more reasons to find fault, so the signal ends up anti-correlated with attention.
 *
 * Behaviour does not have those problems. Reading a PDF for a minute is unambiguous in any
 * reader's private scale, and it costs nobody any effort to report.
 *
 * **Weights are earned by cost, and cost includes time.** An earlier version scored
 * [DOWNLOADED] the moment the reader tapped Read, which is one tap: bouncing straight back
 * out of a PDF was worth 0.7, the same as reading it properly. Every weight above [SAVED] is
 * now gated on the reader actually staying, which is what made those numbers defensible in
 * the first place.
 *
 * On negatives: the original design banned implicit signals outright. That was right about
 * negatives and wrong about positives. Everything not opened outnumbers everything opened by
 * roughly thirty to one, so [PASSED] is admitted at a twentieth of a like and its total mass
 * is capped during training.
 *
 * [PASSED] is deliberately dormant: nothing emits it. Not-engaged-with is already used where
 * it is safe, as the `ignored` count in the topic bandit, where thirty-to-one is a ratio
 * rather than thirty times the training mass. Feeding it to the classifier as well is the
 * part that would poison the model, so it waits until there is a measurement saying otherwise.
 */
enum class Signal(val weight: Float) {
    /**
     * The explicit steer. Just below the ceiling rather than at it, so that saying "more like
     * this" *and* saving *and* reading it can still add up to something stronger than the tap
     * alone. It stays above every inferred signal: an instruction beats an inference.
     */
    LIKED(0.95f),
    READ_PAGES(0.9f),
    SHARED(0.85f),

    /** Opened the PDF and stayed with it. See [Dwell.READER_MILLIS]. */
    DOWNLOADED(0.7f),
    SAVED(0.6f),

    /** Stayed on the detail screen rather than glancing at it. See [Dwell.DETAIL_MILLIS]. */
    DWELLED(0.4f),
    OPENED(0.25f),
    PASSED(-0.05f),
    DISLIKED(-1.0f);

    val positive: Boolean get() = weight > 0f
}

/** How long counts as staying, per surface. */
object Dwell {
    /** Long enough to have read the abstract and thought about it, rather than bounced. */
    const val DETAIL_MILLIS = 15_000L

    /** Long enough to have read a page of a PDF rather than seen it render. */
    const val READER_MILLIS = 20_000L
}

/**
 * The evidence for one paper.
 *
 * The label is not a sum. Opening a paper, downloading it and reading it is one endorsement
 * expressed three ways, and adding them would let a single enthusiastic afternoon outweigh a
 * month of considered judgements.
 *
 * But it is not a plain maximum either, which is what this used to be. Under a maximum,
 * saving a paper *and* asking for more like it says exactly what asking for more like it says
 * on its own, and a paper someone downloaded, read four pages of and saved is indistinguishable
 * from one they merely read. Corroborating evidence is real evidence and the label should show it.
 *
 * So: the strongest signal sets the floor, and everything else closes part of the remaining
 * gap to 1. Bounded above by 1 whatever happens, which is the property the no-sums rule was
 * actually protecting.
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
        // An explicit rejection overrides everything inferred from behaviour: someone who
        // read six pages and then said "not for me" means the second thing.
        if (Signal.DISLIKED in signals) return 0f

        val positive = signals.filter { it.positive }.sortedByDescending { it.weight }
        val best = positive.firstOrNull()
            ?: return if (Signal.PASSED in signals) 0f else null

        // Noisy-OR over the corroborating signals, damped: two of them cannot conjure a
        // certainty that neither one alone justified, and the total can never pass 1.
        var doubt = 1f
        for (s in positive.drop(1)) doubt *= (1f - s.weight)
        return best.weight + (1f - best.weight) * CORROBORATION * (1f - doubt)
    }

    /** How much this example counts, so a skip cannot shout as loudly as a download. */
    fun sampleWeight(): Float = when {
        explicit -> 1.0f
        signals.any { it.positive } -> 1.0f
        else -> -Signal.PASSED.weight   // 0.05
    }

    private companion object {
        /**
         * How much of the gap to 1 the supporting signals may close.
         *
         * At 1.0 a save plus an open would reach 0.7, outranking a paper someone actually
         * downloaded, and corroboration would be doing more work than the strongest act.
         * Half leaves the ordering of the strong signals intact and lets agreement break ties.
         */
        const val CORROBORATION = 0.5f
    }
}
