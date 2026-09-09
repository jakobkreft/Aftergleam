package si.jakobkreft.aftergleam.data

import android.content.Context

/**
 * Settings. SharedPreferences rather than DataStore: this is a handful of scalars read
 * once at startup, and the extra dependency bought nothing.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("aftergleam", Context.MODE_PRIVATE)

    var categories: Set<String>
        get() = sp.getStringSet("categories", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("categories", v).apply()

    /** Topic keys chosen during onboarding, seeding the model before any paper is judged. */
    var seedTopics: Set<String>
        get() = sp.getStringSet("seed_topics", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("seed_topics", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var explorationRate: Float
        get() = sp.getFloat("exploration", 0.2f)
        set(v) = sp.edit().putFloat("exploration", v).apply()

    var qualityWeight: Float
        get() = sp.getFloat("quality", 0.35f)
        set(v) = sp.edit().putFloat("quality", v).apply()

    /** Trade-off between pure ranking and variety. See Weights.diversity. */
    var diversity: Float
        get() = sp.getFloat("diversity", 0.3f)
        set(v) = sp.edit().putFloat("diversity", v.coerceIn(0f, 1f)).apply()

    /** How many cards a digest holds. Ten proved too short to be worth opening. */
    var digestSize: Int
        get() = sp.getInt("digest_size", 25)
        set(v) = sp.edit().putInt("digest_size", v.coerceIn(5, 100)).apply()

    /** Local hour the daily digest is prepared. */
    var digestHour: Int
        get() = sp.getInt("digest_hour", 5)
        set(v) = sp.edit().putInt("digest_hour", v.coerceIn(0, 23)).apply()

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    /**
     * A nudge to read, separate from the hour the digest is prepared.
     *
     * These are different events with different natural times. The digest is prepared at
     * five in the morning because that is when the phone is on wifi and charging; nobody
     * wants to be told about it then.
     */
    var reminderHour: Int
        get() = sp.getInt("reminder_hour", 19)
        set(v) = sp.edit().putInt("reminder_hour", v.coerceIn(0, 23)).apply()

    /**
     * Whether the reader has been through the notification permission prompt once.
     *
     * Android shows the system dialog at most twice and then denies silently, so "have we
     * asked" cannot be recovered from the permission state. It is also what stops the first
     * grant from re-enabling a reminder the reader has since switched off.
     */
    var notificationsAsked: Boolean
        get() = sp.getBoolean("notifications_asked", false)
        set(v) = sp.edit().putBoolean("notifications_asked", v).apply()

    var reminderEnabled: Boolean
        get() = sp.getBoolean("reminder_enabled", false)
        set(v) = sp.edit().putBoolean("reminder_enabled", v).apply()

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /**
     * The page each paper was last read to.
     *
     * Kept for a bounded number of papers: a reading position is worth almost nothing once
     * it is old, and an unbounded map would grow forever in a preferences file.
     */
    fun lastPage(paperId: String): Int = sp.getInt("page_$paperId", 0)

    fun setLastPage(paperId: String, page: Int) {
        val seen = (sp.getStringSet("page_keys", emptySet()) ?: emptySet()).toMutableSet()
        seen += paperId
        val e = sp.edit().putInt("page_$paperId", page)
        if (seen.size > MAX_REMEMBERED_PAGES) {
            val drop = seen.first { it != paperId }
            seen -= drop
            e.remove("page_$drop")
        }
        e.putStringSet("page_keys", seen).apply()
    }

    var lastFetchMillis: Long
        get() = sp.getLong("last_fetch", 0L)
        set(v) = sp.edit().putLong("last_fetch", v).apply()

    /**
     * Whether arXiv has published anything since the last fetch.
     *
     * This asks the schedule rather than a timer. arXiv announces once per weekday evening,
     * so between announcements the same query returns the same papers; a fixed interval both
     * refetches for nothing several times a day and can sit stale right after an
     * announcement. Re-ranking needs no network at all, which is why the two are separate.
     */
    fun fetchIsStale(): Boolean = Announcements.hasNewSince(lastFetchMillis)

    /**
     * How long after a fetch another one is pointless.
     *
     * arXiv announces once per weekday, so between announcements a second fetch returns the
     * same papers it returned the first time. bioRxiv has no announcement to key off, which
     * is what this is for: a plain interval, short enough that somebody who comes back in an
     * hour still gets a real check, long enough that pulling twice in a row does not cost
     * another trip through three servers.
     */
    private val MIN_FETCH_INTERVAL_MS = 10 * 60 * 1000L

    fun fetchedRecently(now: Long = System.currentTimeMillis()): Boolean =
        lastFetchMillis > 0L && now - lastFetchMillis < MIN_FETCH_INTERVAL_MS

    /**
     * Categories whose papers are already on the device.
     *
     * Subscribing to something new has to fetch it even inside the interval above, or the
     * reader ticks a subject and is told there is nothing new to get.
     */
    var fetchedCategories: Set<String>
        get() = sp.getStringSet("fetched_categories", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("fetched_categories", v).apply()

    companion object {
        private const val MAX_REMEMBERED_PAGES = 100
    }
}
