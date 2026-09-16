package si.jakobkreft.aftergleam.data

import si.jakobkreft.aftergleam.BuildConfig

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
}
