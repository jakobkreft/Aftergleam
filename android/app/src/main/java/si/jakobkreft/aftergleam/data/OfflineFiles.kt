package si.jakobkreft.aftergleam.data

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Where downloaded papers are kept: the app's own files, not its cache.
 *
 * They were in the cache until 1.1.1, on the reasoning that the system could reclaim them and a
 * paper read a month ago was no great loss. In practice the cache is also what a reader clears
 * when something looks wrong, which is exactly the moment they least expect to lose the papers
 * they kept for reading offline: a reader whose feed would not load cleared it to fix that, and
 * lost every offline paper as well. The offline shelf is a promise, so the files are kept where
 * only the reader deletes them, from that shelf, or by clearing the app's storage.
 *
 * No permission is needed. These are the app's private files, which nothing else can read,
 * and the backup rules already keep them out of cloud backups and device transfers: a PDF can
 * always be fetched again. The space they take is on the offline shelf, with a delete button.
 */
object OfflineFiles {

    /** The PDFs, and whatever else the servers sent for a paper. */
    const val PDF = "pdf"

    /** The reader view's copies of arXiv's HTML. */
    const val HTML = "html"

    fun dir(context: Context, name: String): File = File(context.filesDir, name)

    /** Where [dir] was before 1.1.1, still read until [moveFromCache] has emptied it. */
    fun legacyDir(context: Context, name: String): File = File(context.cacheDir, name)

    /**
     * Moves downloads left in the cache by an earlier version. Returns how many moved.
     *
     * Safe to call on every start: with nothing left in the cache it only finds that out. Until
     * it has run, the stores read the old place as well, so nothing is missing in between;
     * anything it cannot move stays readable where it is, and is tried again next time. One
     * paper at a time, so the extra space it needs while copying is one paper's.
     */
    fun moveFromCache(context: Context): Int = synchronized(this) {
        var moved = 0
        for (name in listOf(PDF, HTML)) {
            val from = legacyDir(context, name)
            val entries = from.listFiles() ?: continue
            val to = dir(context, name).apply { mkdirs() }
            for (entry in entries) {
                val target = File(to, entry.name)
                when {
                    // An interrupted download, never a paper.
                    entry.name.endsWith(PART) -> entry.deleteRecursively()
                    // Fetched again since, into the new place.
                    target.exists() -> entry.deleteRecursively()
                    move(entry, target) -> moved++
                }
            }
            from.delete()
        }
        moved
    }

    /**
     * Copied, and the original removed once the copy is complete.
     *
     * Not renamed, though that would be instant. Android keeps an app's cache under a group of
     * its own, which is how it counts cache space, and a renamed file keeps that group: moved
     * papers were still listed as cache in the system's storage settings, where clearing the
     * cache would then not free them. A copy is written as an ordinary file of the app. It goes
     * to a temporary name first and appears under its real one only when complete, so a
     * half-copied paper is never taken for a download.
     */
    private fun move(from: File, to: File): Boolean {
        val temp = File(to.parentFile, to.name + MOVING)
        temp.deleteRecursively()
        return try {
            from.copyRecursively(temp, overwrite = true)
            if (temp.renameTo(to)) {
                from.deleteRecursively()
                true
            } else {
                temp.deleteRecursively()
                false
            }
        } catch (e: IOException) {
            // Most likely a full disk. The paper stays readable where it was.
            temp.deleteRecursively()
            false
        }
    }

    /** A download still arriving. */
    const val PART = ".part"

    /** A file being moved out of the cache. */
    const val MOVING = ".moving"

    /** Whether [file] is a finished download rather than one arriving or being moved. */
    fun isFinished(file: File): Boolean =
        !file.name.endsWith(PART) && !file.name.endsWith(MOVING)
}
