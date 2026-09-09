package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Source

/** A paper that forgets which server it came from gets the wrong URL and the wrong label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceRoundTripTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `source survives a write and every read path`() {
        val db = Db(ctx)
        db.writableDatabase.delete("papers", null, null)
        val p = Paper(
            id = "10.64898/2026.09.04.749381v1",
            title = "CAMOR", abstract = "lncRNA tumour biology",
            authors = listOf("A Grzes"), categories = listOf("biorxiv:cancer biology"),
            published = "2026-09-04", updated = "2026-09-04",
            source = Source.BIORXIV,
        )
        db.upsertPapers(listOf(p))

        assertEquals(Source.BIORXIV, db.papersById(setOf(p.id)).single().source)
        assertEquals(Source.BIORXIV, db.recentPapers(limit = 10).single().source)
        assertEquals(Source.BIORXIV, db.searchLocal("lncRNA", savedOnly = false).single().source)
    }
}
