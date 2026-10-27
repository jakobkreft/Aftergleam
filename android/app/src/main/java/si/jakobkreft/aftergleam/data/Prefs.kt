package si.jakobkreft.aftergleam.data

import android.content.Context
import java.time.LocalDate

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

    /**
     * The daily note's hour: a note naming the day's first paper, separate from the hour the
     * digest is prepared. Stored under its old name, from when it was a reading reminder.
     * Ten in the morning unless the reader chooses another.
     *
     * These are different events with different natural times. The digest is prepared at
     * five in the morning because that is when the phone is on wifi and charging; nobody
     * wants to be told about it then.
     */
    var reminderHour: Int
        get() = sp.getInt("reminder_hour", 10)
        set(v) = sp.edit().putInt("reminder_hour", v.coerceIn(0, 23)).apply()

    /**
     * Whether a paper's own title and abstract are set in a serif.
     *
     * On by default. This is an app for reading papers, and papers are set in a serif.
     */
    var paperSerif: Boolean
        get() = sp.getBoolean("serif_font", true)
        set(v) = sp.edit().putBoolean("serif_font", v).apply()

    /** The order a library shelf is shown in, by [shelf]'s name, or null for the default. */
    fun libraryOrder(shelf: String): String? = sp.getString("library_order_$shelf", null)

    fun setLibraryOrder(shelf: String, order: String) =
        sp.edit().putString("library_order_$shelf", order).apply()

    /**
     * How large a paper's title and abstract are set, in percent of the app's own text. See
     * [si.jakobkreft.aftergleam.ui.PaperTextSize] for why only the paper's words.
     */
    var paperTextSize: Int
        get() = sp.getInt("paper_text_size", 100)
        set(v) = sp.edit().putInt("paper_text_size", v).apply()

    /**
     * Whether the app around them is too.
     *
     * Off by default, which is the ordinary editorial arrangement: the article is set in a
     * serif and the furniture around it is not. Setting buttons and tab labels in a serif
     * as well is a taste some people have, so it is here, but it makes the app's own voice
     * harder to tell apart from the paper's.
     */
    var interfaceSerif: Boolean
        get() = sp.getBoolean("serif_interface", false)
        set(v) = sp.edit().putBoolean("serif_interface", v).apply()

    /**
     * Whether to take colours from the wallpaper instead of the app's own.
     *
     * Off by default, which is the opposite of what the app used to do. Material You is a
     * good default for a launcher and a poor one for a reading surface.
     */
    var dynamicColour: Boolean
        get() = sp.getBoolean("dynamic_colour", false)
        set(v) = sp.edit().putBoolean("dynamic_colour", v).apply()

    /** Whether the daily note is on. See [reminderHour]. */
    var reminderEnabled: Boolean
        get() = sp.getBoolean("reminder_enabled", false)
        set(v) = sp.edit().putBoolean("reminder_enabled", v).apply()

    /** Whether the reader has ever switched the daily note on or off, as against the default. */
    val reminderChosen: Boolean get() = sp.contains("reminder_enabled")

    /**
     * Whether the reader has been asked to allow notifications: once, right after onboarding,
     * or at the first open of this version for somebody who had the app before.
     *
     * Kept rather than read off the permission, because Android stops showing its dialog after
     * two refusals and refuses silently from then on: "not granted" cannot tell "never asked"
     * from "said no".
     */
    var notificationsPrompted: Boolean
        get() = sp.getBoolean("notifications_prompted", false)
        set(v) = sp.edit().putBoolean("notifications_prompted", v).apply()

    /** The day the reader said no to that, for the one offer that follows it, or null. */
    var notificationsDeclinedOn: LocalDate?
        get() = sp.getString("notifications_declined_on", null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) = sp.edit().putString("notifications_declined_on", v?.toString()).apply()

    /** Whether the offer of the daily note at the end of a digest has been answered, either way. */
    var noteOfferAnswered: Boolean
        get() = sp.getBoolean("note_offer_answered", false)
        set(v) = sp.edit().putBoolean("note_offer_answered", v).apply()

    /**
     * The last day the app was opened, as yyyy-mm-dd. The daily note stays away on a day the
     * reader has already been in: telling somebody about papers they have seen is noise.
     */
    var lastOpenedDay: String?
        get() = sp.getString("last_opened_day", null)
        set(v) = sp.edit().putString("last_opened_day", v).apply()

    /**
     * The keywords with their bell on, and the day each was turned on.
     *
     * One line per keyword, the day first and a tab before the keyword: a keyword cannot hold
     * a tab or a new line, since [Keywords.normalise] collapses all space to single spaces.
     */
    var keywordAlerts: Map<String, String>
        get() = sp.getString("keyword_alerts", null).orEmpty().split('\n')
            .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 } }
            .associate { (day, keyword) -> keyword to day }
        set(v) = sp.edit().putString(
            "keyword_alerts", v.entries.joinToString("\n") { (keyword, day) -> "$day\t$keyword" },
        ).apply()

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

    /**
     * How far through the reader view a paper was read, as a fraction of its length, or null
     * when it was last read as a PDF. The paper reopens the way it was left, at the same place.
     *
     * A fraction rather than a scroll offset, because the offset of the same sentence moves
     * whenever the text size does. Bounded like the pages.
     */
    fun articlePosition(paperId: String): Float? =
        if (sp.contains("article_$paperId")) sp.getFloat("article_$paperId", 0f) else null

    fun setArticlePosition(paperId: String, position: Float?) {
        val seen = (sp.getStringSet("article_keys", emptySet()) ?: emptySet()).toMutableSet()
        val e = sp.edit()
        if (position == null) {
            seen -= paperId
            e.remove("article_$paperId")
        } else {
            seen += paperId
            e.putFloat("article_$paperId", position.coerceIn(0f, 1f))
            if (seen.size > MAX_REMEMBERED_PAGES) {
                val drop = seen.first { it != paperId }
                seen -= drop
                e.remove("article_$drop")
            }
        }
        e.putStringSet("article_keys", seen).apply()
    }

    /**
     * The reader view's own "light" or "dark", or null to follow the app. See
     * [si.jakobkreft.aftergleam.ui.ReaderTheme].
     */
    var articleTheme: String?
        get() = sp.getString("article_theme", null)
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("article_theme") else e.putString("article_theme", v)
            e.apply()
        }

    /** Text size in the reader view, in percent. One for every paper, as in a browser. */
    var articleTextZoom: Int
        get() = sp.getInt("article_text_zoom", 100)
        set(v) = sp.edit().putInt("article_text_zoom", v).apply()

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

    /**
     * The reader's keywords, in the order they were added. See [Keywords].
     *
     * One string rather than a string set: a set has no order, and the keywords are shown, and
     * take their turns in the digest, in the order the reader wrote them.
     */
    var keywords: List<String>
        get() = sp.getString("keywords", null).orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("keywords", v.joinToString("\n")).apply()

    /** Keywords fetched since the last full fetch, so a new one is fetched on its own. */
    var keywordsFetched: Set<String>
        get() = sp.getStringSet("keywords_fetched", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("keywords_fetched", v).apply()

    /** Whether the occasional support note may end the digest. See [Support]. */
    var supportReminder: Boolean
        get() = sp.getBoolean("support_reminder", true)
        set(v) = sp.edit().putBoolean("support_reminder", v).apply()

    /** The support note stays away until this day. */
    var supportQuietUntil: LocalDate?
        get() = sp.getString("support_quiet_until", null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) = sp.edit().putString("support_quiet_until", v?.toString()).apply()

    companion object {
        private const val MAX_REMEMBERED_PAGES = 100
    }
}
