package si.jakobkreft.aftergleam.data

import si.jakobkreft.aftergleam.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * How the app introduces itself to the servers it asks for papers.
 *
 * The public APIs it uses ask clients to identify themselves and give a way to reach whoever
 * runs them. This names the app and its source, and nothing about the reader.
 *
 * One definition rather than six. Each source used to carry its own copy with the version
 * typed in by hand, which would have gone on announcing 0.1 long after it stopped being true.
 */
object Http {
    val USER_AGENT = "Aftergleam/${BuildConfig.VERSION_NAME} " +
        "(+https://github.com/jakobkreft/aftergleam)"

    const val JSON = "application/json"

    /**
     * What [url] answers with, or null if it did not answer with a page.
     *
     * Null rather than an exception, and for a refusal as much as for no network: to every
     * caller a server that is down has nothing new today. A caller for whom "nothing" and
     * "no answer" must read differently says so where it calls. Blocks, so not for the main
     * thread.
     */
    fun get(
        url: String,
        accept: String? = null,
        readTimeout: Int = 30_000,
        agent: String = USER_AGENT,
    ): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", agent)
            if (accept != null) setRequestProperty("Accept", accept)
            connectTimeout = 20_000
            this.readTimeout = readTimeout
        }
        try {
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
