package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db

/**
 * Attention counts have to outlive the process.
 *
 * They used to live only in the view model, so Popular was correct exactly once per fetch
 * and silently degraded to venue matches on every cold start where no fetch was due, which
 * is most weekends. Nothing on screen said so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttentionStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `counts survive a new database handle`() {
        Db(ctx).saveAttention(mapOf("2609.00001" to 120, "2609.00002" to 3))
        val back = Db(ctx).attention()
        assertEquals(120, back["2609.00001"])
        assertEquals(3, back["2609.00002"])
    }

    @Test
    fun `a later fetch replaces an earlier count for the same paper`() {
        val db = Db(ctx)
        db.saveAttention(mapOf("2609.00003" to 5))
        db.saveAttention(mapOf("2609.00003" to 90))
        assertEquals(90, db.attention()["2609.00003"])
    }

    @Test
    fun `stale counts are not served as todays attention`() {
        // "What is everyone reading" is a claim about now. A count from six weeks ago is not
        // a weaker version of that claim, it is a different one.
        val db = Db(ctx)
        db.saveAttention(mapOf("2609.00004" to 200))
        val sixWeeksOn = System.currentTimeMillis() + 42L * 24 * 60 * 60 * 1000
        assertTrue("stale buzz is not news",
            db.attention(now = sixWeeksOn).isEmpty())
        assertEquals(200, db.attention()["2609.00004"])
    }

    @Test
    fun `old counts are pruned rather than accumulating forever`() {
        // A hundred rows a day, and nothing reads them back after a month.
        val db = Db(ctx)
        val longAgo = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
        db.saveAttention(mapOf("2607.00001" to 90), now = longAgo)
        db.saveAttention(mapOf("2609.00006" to 12))
        val back = db.attention()
        assertEquals(setOf("2609.00006"), back.keys)
    }

    @Test
    fun `an empty fetch does not wipe what is stored`() {
        // Attention is enrichment that degrades to nothing, and a failed request returns an
        // empty map. Treating that as "nobody is reading anything" would clear the tab.
        val db = Db(ctx)
        db.saveAttention(mapOf("2609.00005" to 42))
        db.saveAttention(emptyMap())
        assertEquals(42, db.attention()["2609.00005"])
    }
}
