package si.jakobkreft.aftergleam

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.OfflineFiles
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Signal
import si.jakobkreft.aftergleam.ui.LibraryOrder
import si.jakobkreft.aftergleam.ui.LibraryShelf
import si.jakobkreft.aftergleam.ui.LibrarySort
import java.io.File
import java.text.Collator
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The library's orders, and the times they are built from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySortTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun paper(id: String, title: String, published: String) =
        Paper(id, title, "An abstract.", listOf("A"), listOf("cs.CV"), published, published)

    private val a = paper("a", "Zebrafish fins", "2026-09-01")
    private val b = paper("b", "Élan in attention", "2026-10-10")
    private val c = paper("c", "attention is all you need", "2017-06-12")
    private val all = listOf(a, b, c)

    private fun ids(papers: List<Paper>) = papers.map { it.id }

    @Test
    fun `recent puts the last thing the reader did first, and papers with no time last`() {
        val times = mapOf("a" to 3_000L, "c" to 9_000L)
        assertEquals(listOf("c", "a", "b"), ids(LibrarySort.sort(all, LibraryOrder.RECENT, times)))
    }

    @Test
    fun `newest papers first goes by the paper's own date`() {
        assertEquals(listOf("b", "a", "c"), ids(LibrarySort.sort(all, LibraryOrder.NEWEST, emptyMap())))
    }

    @Test
    fun `titles sort as a reader expects, whatever the case or accents`() {
        val english = Collator.getInstance(Locale.ENGLISH)
        assertEquals("not by character code, which puts lower case and accents last",
            listOf("c", "b", "a"), ids(LibrarySort.sort(all, LibraryOrder.TITLE, emptyMap(), english)))
    }

    @Test
    fun `each shelf names its own recent order, and offers three`() {
        assertEquals(listOf("Recently saved", "Newest papers first", "Title A to Z"),
            LibraryOrder.entries.map { it.label(LibraryShelf.SAVED) })
        assertEquals("Recently downloaded", LibraryOrder.RECENT.label(LibraryShelf.DOWNLOADED))
        assertEquals("Recently reacted", LibraryOrder.RECENT.label(LibraryShelf.RATED))
    }

    @Test
    fun `a row says when the reader acted, in plain words`() {
        val today = LocalDate.of(2026, 10, 15)
        fun at(day: LocalDate) = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        fun label(day: LocalDate, shelf: LibraryShelf = LibraryShelf.SAVED) =
            LibrarySort.actedLabel(shelf, at(day), today, ZoneOffset.UTC, Locale.ENGLISH)
        assertEquals("saved today", label(today))
        assertEquals("downloaded yesterday", label(today.minusDays(1), LibraryShelf.DOWNLOADED))
        assertEquals("reacted 2 Oct", label(LocalDate.of(2026, 10, 2), LibraryShelf.RATED))
        assertEquals("saved 3 Mar 2025", label(LocalDate.of(2025, 3, 3)))
    }

    @Test
    fun `saving again moves a paper to the top, and reactions take the latest of more and less`() {
        val db = Db(ctx)
        db.addSignal("p1", Signal.SAVED)
        Thread.sleep(5)
        db.addSignal("p2", Signal.SAVED)
        Thread.sleep(5)
        db.removeSignal("p1", Signal.SAVED)
        db.addSignal("p1", Signal.SAVED)
        val saved = db.signalTimes(Signal.SAVED)
        assertTrue("saved again, so latest", saved.getValue("p1") > saved.getValue("p2"))

        db.addSignal("p3", Signal.LIKED)
        val reacted = db.signalTimes(Signal.LIKED, Signal.DISLIKED)
        assertEquals(setOf("p3"), reacted.keys)
    }

    @Test
    fun `moving downloads out of the cache keeps when they were downloaded`() {
        val old = File(OfflineFiles.legacyDir(ctx, OfflineFiles.PDF).apply { mkdirs() }, "2610.00001.pdf")
        old.writeBytes(ByteArray(100) { 1 })
        val lastWeek = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        old.setLastModified(lastWeek)
        OfflineFiles.moveFromCache(ctx)
        val moved = File(OfflineFiles.dir(ctx, OfflineFiles.PDF), "2610.00001.pdf")
        assertTrue(moved.exists())
        assertEquals(lastWeek / 1000, moved.lastModified() / 1000)
    }
}
