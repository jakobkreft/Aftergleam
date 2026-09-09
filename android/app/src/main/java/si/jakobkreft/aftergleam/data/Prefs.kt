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

    var reminderEnabled: Boolean
        get() = sp.getBoolean("reminder_enabled", false)
        set(v) = sp.edit().putBoolean("reminder_enabled", v).apply()

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    var lastFetchMillis: Long
        get() = sp.getLong("last_fetch", 0L)
        set(v) = sp.edit().putLong("last_fetch", v).apply()

    /**
     * arXiv announces once per weekday at 20:00 US Eastern, so a second fetch on the same
     * day returns exactly the same papers. Measured: the newest cs.CV submission stayed at
     * 2026-09-04 across a whole day of polling. Re-ranking needs no network at all, which
     * is why the two operations are separate.
     */
    fun fetchIsStale(now: Long = System.currentTimeMillis()): Boolean =
        now - lastFetchMillis > FETCH_INTERVAL_MS

    companion object {
        const val FETCH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
