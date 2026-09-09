package si.jakobkreft.aftergleam.rank

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Allocates digest slots across topics by Thompson sampling.
 *
 * Sampling individual papers, which the digest already does, still draws from inside the
 * region the model is confident about. A reader who liked four diffusion papers gets a model
 * that is sure about diffusion and silent about everything else, and per-item noise only
 * shuffles the diffusion papers. Collapse has to be fought one level up, by deciding *how
 * many slots each topic gets* before deciding which paper fills them.
 *
 * Each topic carries a Beta posterior over "does this reader engage with this". The useful
 * property is that the posterior's *width* does the exploring: a topic tried four times with
 * three engagements sits narrowly around 0.75, while a topic never tried at all is flat
 * across the whole range and so wins a slot fairly often purely because nothing is known
 * about it. Exploration stops being a number in settings and becomes a consequence of
 * ignorance, which is what it should have been all along.
 *
 * As evidence accumulates the posteriors sharpen and the allocation quietly converges on
 * what the reader actually reads, with no schedule to tune.
 */
object TopicBandit {

    /**
     * Engagements and disengagements observed for one topic.
     *
     * Fractional, because evidence is time-weighted before it gets here: half an engagement
     * is what an engagement from a month ago is worth.
     */
    data class Arm(val topic: String, val engaged: Float, val ignored: Float) {
        constructor(topic: String, engaged: Int, ignored: Int) :
            this(topic, engaged.toFloat(), ignored.toFloat())
    }

    /**
     * How much evidence any one topic is allowed to accumulate.
     *
     * Without a bound the posteriors sharpen without limit, and a topic ignored forty times
     * in a row becomes unrecoverable: no amount of later interest moves a Beta(1, 41) enough
     * to win a slot again. Interests are not stationary. People change project, and the app
     * has to be able to notice.
     *
     * Capping the counts while preserving their ratio keeps the *conclusion* about a topic
     * and discards only the excess certainty, so a reader who returns to an abandoned field
     * is believed within a handful of interactions rather than never.
     */
    private const val EVIDENCE_WINDOW = 30

    /** Scales an arm down to the evidence window, keeping the ratio it observed. */
    private fun Arm.bounded(): Arm {
        val total = engaged + ignored
        if (total <= EVIDENCE_WINDOW) return this
        val f = EVIDENCE_WINDOW / total
        return copy(engaged = engaged * f, ignored = ignored * f)
    }

    /**
     * How long a day's evidence keeps half its weight.
     *
     * The window above bounds *certainty*, which is not the same as forgetting, and the
     * difference is why an abandoned topic used to be unrecoverable. Ignoring a topic two
     * hundred times last year and engaging with it twelve times this month is a six percent
     * rate, and the bandit was right to call that poor, but it is the wrong question: the
     * reader has changed project and only the recent part of that history describes them.
     *
     * A month is chosen against the app's own rhythm rather than a tuning sweep. The digest
     * arrives daily, so thirty days is roughly one working cycle of a project: long enough
     * that a fortnight away from a field does not erase it, short enough that a genuine
     * change of direction is reflected in the allocation inside a few weeks rather than
     * being outvoted by a year of history that is no longer about this reader.
     */
    const val HALF_LIFE_DAYS = 30f

    /**
     * What a piece of evidence from [ageDays] ago is still worth, in 0..1.
     *
     * Exponential rather than a cutoff window: a cliff edge would mean a topic's standing
     * lurched on the day an old observation fell off the end, and the reader would see the
     * digest change for no reason they did anything to cause.
     */
    fun recency(ageDays: Float): Float =
        0.5f.pow((ageDays / HALF_LIFE_DAYS).coerceAtLeast(0f))

    /**
     * Draws a topic, in proportion to the posterior probability that it is the best one.
     *
     * The priors are deliberately optimistic (alpha = 1, beta = 1 is uniform), so an
     * untried topic is treated as "could be anything" rather than "probably bad". Pessimistic
     * priors would reproduce the collapse this exists to prevent.
     */
    fun draw(arms: List<Arm>, random: Random = Random.Default): String? {
        if (arms.isEmpty()) return null
        var bestTopic: String? = null
        var best = Float.NEGATIVE_INFINITY
        for (raw in arms) {
            val arm = raw.bounded()
            val theta = betaSample(1f + arm.engaged, 1f + arm.ignored, random)
            if (theta > best) {
                best = theta
                bestTopic = arm.topic
            }
        }
        return bestTopic
    }

    /**
     * Beta(a, b) as the ratio of two Gamma draws, which is the standard construction and
     * needs no special functions.
     */
    private fun betaSample(a: Float, b: Float, random: Random): Float {
        val x = gammaSample(a, random)
        val y = gammaSample(b, random)
        return if (x + y <= 0f) 0.5f else x / (x + y)
    }

    /**
     * Marsaglia and Tsang's method for Gamma(shape, 1).
     *
     * Valid for shape >= 1; smaller shapes are handled by the standard boost, raising a
     * uniform to the power 1/shape. Both priors start at 1, so the boost only matters for
     * fractional shapes that this code does not currently produce, but leaving it out would
     * be a trap for whoever changes the priors.
     */
    private fun gammaSample(shape: Float, random: Random): Float {
        if (shape < 1f) {
            val u = random.nextDouble().coerceAtLeast(1e-12)
            return gammaSample(shape + 1f, random) * u.pow(1.0 / shape).toFloat()
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian(random)
                v = 1.0 + c * x
            } while (v <= 0.0)
            v = v * v * v
            val u = random.nextDouble().coerceIn(1e-12, 1.0 - 1e-12)
            if (u < 1.0 - 0.0331 * x * x * x * x) return (d * v).toFloat()
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return (d * v).toFloat()
        }
    }

    private fun gaussian(random: Random): Double {
        // Box-Muller. Only one of the pair is used; the second is not worth caching here.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
