package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Fetcher
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.Topics

/**
 * Which servers a subscription is allowed to wake up.
 *
 * The app reads seven servers and nobody reads all seven. A computer scientist who waits on
 * OSF and Crossref every morning is waiting on law and psychology they did not ask for, and
 * every extra server is another way for the morning to be slow or to fail.
 */
class FetcherTest {

    private fun cats(vararg topics: String) = Topics.categoriesFor(topics.toSet())

    @Test
    fun `a computer scientist only wakes arXiv`() {
        assertEquals(
            setOf(Source.ARXIV),
            Fetcher.serversFor(cats("ml", "vision", "theory")),
        )
    }

    @Test
    fun `a psychologist only wakes PsyArXiv`() {
        assertEquals(
            setOf(Source.PSYARXIV),
            Fetcher.serversFor(cats("psy-cog", "psy-clin")),
        )
    }

    @Test
    fun `a lawyer only wakes the Law Archive`() {
        assertEquals(setOf(Source.LAWARCHIVE), Fetcher.serversFor(cats("law-public")))
    }

    @Test
    fun `a chemist only wakes ChemRxiv`() {
        assertEquals(setOf(Source.CHEMRXIV), Fetcher.serversFor(cats("chem-organic")))
    }

    @Test
    fun `mixed interests wake exactly the servers they name`() {
        assertEquals(
            setOf(Source.ARXIV, Source.MEDRXIV, Source.EDARXIV),
            Fetcher.serversFor(cats("ml", "mr-rehab", "edu-higher")),
        )
    }

    @Test
    fun `no subscription wakes nothing`() {
        assertTrue(Fetcher.serversFor(emptySet()).isEmpty())
    }

    @Test
    fun `every server the app knows is reachable from some topic`() {
        // A server with no topic pointing at it is code that can never run.
        val all = Topics.FIELDS.flatMap { it.topics }.map { it.source }.toSet()
        for (s in listOf(
            Source.ARXIV, Source.BIORXIV, Source.MEDRXIV, Source.PSYARXIV,
            Source.SOCARXIV, Source.EDARXIV, Source.LAWARCHIVE, Source.CHEMRXIV,
        )) {
            assertTrue("no topic fetches from $s", s in all)
        }
    }
}
