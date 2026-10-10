package si.jakobkreft.aftergleam.ui

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import si.jakobkreft.aftergleam.data.ArxivApi
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What went wrong, in two parts: a headline, and a sentence on what it means. */
data class Problem(val headline: String, val detail: String)

/**
 * Turning an exception into something a reader can act on.
 *
 * The feed used to say "Could not reach arXiv" whatever had failed. Network failures do not
 * even reach that message: each server's fetch catches its own, and the digest is then built
 * from the papers already on the phone. So the message only ever appeared for something else,
 * and named the network for it. A reader whose feed would not load turned the network off and
 * on, cleared the cache, and reported the app as corrupted, while whatever had actually failed
 * was not named anywhere.
 */
object Problems {

    /** Whether [e], or anything that caused it, is the network failing. */
    fun isNetwork(e: Throwable): Boolean = causes(e).any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
            it is SocketException || it is SocketTimeoutException || it is SSLException ||
            it is ArxivApi.FetchError
    }

    /** Whether the phone could not get out at all, as opposed to a server being slow or refusing. */
    private fun isOffline(e: Throwable): Boolean = causes(e).any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
            (it is SocketException && it !is SocketTimeoutException)
    }

    private fun isTimeout(e: Throwable): Boolean = causes(e).any { it is SocketTimeoutException }

    /**
     * Why today's digest could not be built.
     *
     * @param servers the servers the reader's subjects are fetched from, for naming them.
     */
    fun digest(e: Throwable, servers: List<String>): Problem {
        val named = when (servers.size) {
            0 -> "the preprint servers"
            1 -> servers[0]
            else -> servers.dropLast(1).joinToString(", ") + " or " + servers.last()
        }
        val sqlite = causes(e).firstOrNull { it is SQLiteException }
        return when {
            isNetwork(e) -> Problem("Could not reach $named", connection(e, named))
            sqlite is SQLiteFullException -> Problem(
                "Your phone is out of space",
                "Today's digest could not be saved. Free some space and try again.",
            )
            sqlite is SQLiteDatabaseLockedException -> Problem(
                BUILD_FAILED,
                "The app's database was busy, usually with the overnight fetch. " +
                    "Try again in a moment.",
            )
            sqlite != null -> Problem(
                BUILD_FAILED,
                "The app could not use its database: ${technical(sqlite).trimEnd('.')}. " + REPORT,
            )
            else -> Problem(BUILD_FAILED, "Something went wrong while ranking: ${technical(e).trimEnd('.')}. $REPORT")
        }
    }

    /**
     * What a network failure means, for any action: the digest, a PDF, a search.
     *
     * Offline is the expected case for an app read on trains, and the only one with an obvious
     * next step. The second half of it is for phones that let the reader take network access
     * away from one app, as GrapheneOS does, where "you are offline" alone would be untrue.
     */
    fun connection(e: Throwable, server: String = "the server"): String = when {
        isOffline(e) -> "You are offline, or Aftergleam is not allowed to use the network. " +
            "Everything already on your phone still works."
        isTimeout(e) -> "${server.replaceFirstChar { it.uppercase() }} did not answer in time. " +
            "Worth another try in a moment."
        else -> (e.message?.let { "${it.trimEnd('.')}. " } ?: "") + "This is usually temporary."
    }

    /** The exception's own words, with its type, for a report: "IllegalStateException: ...". */
    fun technical(e: Throwable): String =
        e.javaClass.simpleName.ifBlank { "Error" } + (e.message?.let { ": $it" } ?: "")

    private fun causes(e: Throwable): Sequence<Throwable> =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8)

    private const val BUILD_FAILED = "Could not build today's digest"
    private const val REPORT = "If it keeps happening, please report it with this message."
}
