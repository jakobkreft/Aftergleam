package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.EprintApi
import si.jakobkreft.aftergleam.data.Source

/**
 * IACR ePrint's feed of its newest papers, read the way the archive sends it: one category,
 * an author per dc:creator, and the day it was posted.
 *
 * Robolectric for android.util.Xml, the parser the arXiv feed is read with too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EprintTest {

    private fun item(id: String, category: String? = "Cryptographic protocols") = """
        <item>
          <title>SoK: Multiple Concurrent Proposer Blockchains</title>
          <link>https://eprint.iacr.org/$id</link>
          <description>Multiple concurrent proposer protocols let several parties
            contribute transactions to the same ledger update.</description>
          <guid isPermaLink="true">https://eprint.iacr.org/$id</guid>
          ${category?.let { "<category>$it</category>" } ?: ""}
          <enclosure url="https://eprint.iacr.org/$id.pdf" length="705988" type="application/pdf" />
          <pubDate>Thu, 29 Oct 2026 12:55:01 +0000</pubDate>
          <dc:creator>Benjamin Marsh</dc:creator>
          <dc:creator>Max Resnick</dc:creator>
          <dc:rights>https://creativecommons.org/licenses/by/4.0/</dc:rights>
        </item>"""

    private fun feed(vararg items: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel>
            <title>Cryptology ePrint Archive</title>
            <link>https://eprint.iacr.org/</link>
            <description>Recent papers</description>
            ${items.joinToString("\n")}
          </channel>
        </rss>"""

    @Test
    fun `an item becomes a paper in its ePrint category, dated the day it was posted`() {
        val p = EprintApi.parse(feed(item("2026/2684"))).single()
        assertEquals("2026/2684", p.id)
        assertEquals(Source.EPRINT, p.source)
        assertEquals("SoK: Multiple Concurrent Proposer Blockchains", p.title)
        assertEquals(listOf("eprint:Cryptographic protocols"), p.categories)
        assertEquals(listOf("Benjamin Marsh", "Max Resnick"), p.authors)
        assertEquals("2026-10-29", p.published)
    }

    @Test
    fun `an item without a category is left out, and the feed's own title is no paper`() {
        val papers = EprintApi.parse(feed(item("2026/2", category = null), item("2026/3")))
        assertEquals(listOf("2026/3"), papers.map { it.id })
    }
}
