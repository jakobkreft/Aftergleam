package si.jakobkreft.aftergleam

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.LibraryImport

/**
 * What the import screen is allowed to claim.
 *
 * The counts here are shown to the reader as a sentence about their own library, so a
 * miscount is a lie about their files rather than an off-by-one. The stopped case is the
 * one that used to be impossible to get right: the reader leaves after forty of two hundred
 * entries and the screen must not report a hundred and sixty papers as missing from arXiv.
 */
class LibraryImportTest {

    private val empty = ""

    @Test
    fun `an empty file resolves to nothing without reaching the network`() = runTest {
        val r = LibraryImport.run(empty)
        assertEquals(0, r.total)
        assertEquals(0, r.papers.size)
        assertTrue(r.papers.isEmpty())
    }

    @Test
    fun `stopping before the first lookup reports no unmatched entries`() = runTest {
        val bib = (1..5).joinToString("\n") {
            "@article{k$it, title={A paper about nothing number $it}, year={2024}}"
        }
        val r = LibraryImport.run(bib, shouldStop = { true })
        assertTrue("the run reports itself as stopped", r.stopped)
        // Nothing was looked at, so nothing can be said to be missing from arXiv. Reporting
        // total from the file would blame arXiv for five entries never sent to it.
        assertEquals(0, r.total)
        assertEquals(0, r.unmatched)
        assertEquals(0, r.failed)
    }

    @Test
    fun `progress opens by saying the file is being read`() = runTest {
        val seen = mutableListOf<LibraryImport.Progress>()
        LibraryImport.run(empty) { seen += it }
        assertEquals(LibraryImport.Stage.READING, seen.first().stage)
    }

    @Test
    fun `a stopped run still ends on the done stage`() = runTest {
        val seen = mutableListOf<LibraryImport.Progress>()
        val bib = "@article{k, title={Something with a long enough title}, year={2024}}"
        LibraryImport.run(bib, shouldStop = { true }) { seen += it }
        assertEquals(LibraryImport.Stage.DONE, seen.last().stage)
    }
}
