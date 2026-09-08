package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Attention

class AttentionTest {

    @Test
    fun `upvote scoring is log scaled so one viral paper cannot dominate`() {
        val small = Attention.score(5)
        val big = Attention.score(300)
        assertTrue("more upvotes must score higher", big > small)
        assertTrue("scores stay within 0..1, got $big", big <= 1f)
        // Sixty times the upvotes must not be sixty times the score, or a single popular
        // paper would outrank everything the user actually likes.
        assertTrue("scaling must be sublinear: $small -> $big", big < small * 4f)
    }

    @Test
    fun `no upvotes contributes nothing`() {
        assertEquals(0f, Attention.score(0), 1e-6f)
        assertEquals(0f, Attention.score(-3), 1e-6f)
        assertEquals("", Attention.label(0))
    }
}
