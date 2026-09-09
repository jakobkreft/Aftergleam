package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Evidence
import si.jakobkreft.aftergleam.data.Signal

/**
 * How several weak signals about one paper combine.
 *
 * This used to be a plain maximum, which made corroboration invisible: saving a paper and
 * then asking for more like it scored exactly what asking on its own scored. The rule now
 * lets agreement count, and these tests pin down the part that is easy to get wrong, which
 * is that it must stay bounded and must not reorder the strong signals.
 */
class EvidenceTest {

    private fun label(vararg s: Signal) = Evidence("x", s.toSet()).label()

    @Test
    fun `nothing observed is not evidence of anything`() {
        assertNull(Evidence("x", emptySet()).label())
    }

    @Test
    fun `a lone signal is worth its own weight`() {
        assertEquals(0.6f, label(Signal.SAVED)!!, 1e-4f)
        assertEquals(0.25f, label(Signal.OPENED)!!, 1e-4f)
    }

    @Test
    fun `agreeing signals are stronger than either alone`() {
        val save = label(Signal.SAVED)!!
        val like = label(Signal.LIKED)!!
        val both = label(Signal.SAVED, Signal.LIKED)!!
        assertTrue("saving and asking for more must beat either: $save, $like -> $both",
            both > like && both > save)
    }

    @Test
    fun `corroboration never reaches certainty`() {
        // Every positive signal at once is still not a 1, because a 1 is a claim that no
        // amount of behaviour can support: the reader never actually said it.
        val all = Evidence("x", Signal.entries.filter { it.positive }.toSet()).label()!!
        assertTrue("must stay under 1, got $all", all < 1f)
        assertTrue("but should be near the top, got $all", all > 0.95f)
    }

    @Test
    fun `corroboration does not outrank a stronger act`() {
        // The failure mode of a generous combiner: two cheap signals adding up to more than
        // one expensive one, so glancing at papers starts outvoting reading them.
        val glance = label(Signal.OPENED, Signal.DWELLED)!!
        val saved = label(Signal.SAVED)!!
        assertTrue("opening and lingering must not beat a deliberate save: $glance vs $saved",
            glance < saved)
    }

    @Test
    fun `the label rises monotonically as evidence accumulates`() {
        val seq = listOf(
            label(Signal.OPENED)!!,
            label(Signal.OPENED, Signal.DWELLED)!!,
            label(Signal.OPENED, Signal.DWELLED, Signal.SAVED)!!,
            label(Signal.OPENED, Signal.DWELLED, Signal.SAVED, Signal.DOWNLOADED)!!,
        )
        assertEquals(seq.sorted(), seq)
    }

    @Test
    fun `an explicit rejection overrides everything behavioural`() {
        // Read six pages and then said "not for me": the second thing is what they meant.
        assertEquals(
            0f,
            label(Signal.READ_PAGES, Signal.DOWNLOADED, Signal.DISLIKED)!!,
            1e-6f,
        )
    }

    @Test
    fun `a like still outranks any amount of inferred behaviour`() {
        // An instruction beats an inference. If this ever inverts, the heart button stops
        // meaning anything on a paper the reader also happened to open.
        val behaviour = label(
            Signal.OPENED, Signal.DWELLED, Signal.SAVED, Signal.DOWNLOADED, Signal.SHARED,
        )!!
        assertTrue("inferred $behaviour must stay under a like",
            behaviour < label(Signal.LIKED)!!)
    }

    @Test
    fun `a skip counts for a fraction of a real judgement`() {
        assertEquals(1.0f, Evidence("x", setOf(Signal.LIKED)).sampleWeight(), 1e-6f)
        assertEquals(0.05f, Evidence("x", setOf(Signal.PASSED)).sampleWeight(), 1e-6f)
    }
}
