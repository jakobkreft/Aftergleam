package si.jakobkreft.aftergleam.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import si.jakobkreft.aftergleam.rank.TopicBandit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Plain SQLite rather than Room.
 *
 * The schema is four tables with no joins worth checking at compile time, and dropping
 * Room also drops an annotation processor from the build. Since the ranker is TF-IDF
 * there are no embeddings to store either, so the vector-extension dependency the original
 * design assumed is gone as well.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "aftergleam.db", null, 6) {

    // Never call `use` on the database this helper returns. It is a single shared instance,
    // and closing it leaves the helper handing a closed connection pool to the next caller.
    // The symptom is a crash on whichever read happens to follow a write, which made it look
    // like a bug in one screen rather than in every path that writes then reads.

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE papers (
              id TEXT PRIMARY KEY,
              title TEXT NOT NULL,
              abstract TEXT NOT NULL,
              authors TEXT NOT NULL,
              categories TEXT NOT NULL,
              published TEXT NOT NULL,
              updated TEXT NOT NULL,
              comments TEXT NOT NULL DEFAULT '',
              journal_ref TEXT NOT NULL DEFAULT '',
              fetched_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE reactions (
              paper_id TEXT PRIMARY KEY,
              interest REAL,
              saved INTEGER NOT NULL DEFAULT 0,
              viewed INTEGER NOT NULL DEFAULT 0,
              ts INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE shown (
              paper_id TEXT NOT NULL,
              day TEXT NOT NULL,
              slot TEXT NOT NULL DEFAULT 'RELEVANCE',
              reason TEXT NOT NULL DEFAULT '',
              confidence REAL NOT NULL DEFAULT 0,
              rank INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (paper_id, day)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_shown_day ON shown(day)")
        db.execSQL("CREATE INDEX idx_papers_published ON papers(published)")
        db.execSQL(
            """
            CREATE TABLE signals (
              paper_id TEXT NOT NULL,
              signal TEXT NOT NULL,
              ts INTEGER NOT NULL,
              PRIMARY KEY (paper_id, signal)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_signals_paper ON signals(paper_id)")
        db.execSQL(ATTENTION_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 6) db.execSQL(ATTENTION_TABLE)
        if (old < 5) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS signals (
                  paper_id TEXT NOT NULL,
                  signal TEXT NOT NULL,
                  ts INTEGER NOT NULL,
                  PRIMARY KEY (paper_id, signal)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_signals_paper ON signals(paper_id)")
            // Carry the old numeric ratings across as explicit judgements. A slider value
            // above the midpoint was the reader saying yes; the exact number was never
            // comparable between people and is not worth preserving.
            runCatching {
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO signals (paper_id, signal, ts)
                    SELECT paper_id,
                           CASE WHEN interest >= 0.5 THEN 'LIKED' ELSE 'DISLIKED' END,
                           ts
                    FROM reactions WHERE interest IS NOT NULL
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO signals (paper_id, signal, ts)
                    SELECT paper_id, 'SAVED', ts FROM reactions WHERE saved = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO signals (paper_id, signal, ts)
                    SELECT paper_id, 'OPENED', ts FROM reactions WHERE viewed = 1
                    """.trimIndent()
                )
            }
        }
        if (old in 1..3) {
            runCatching { db.execSQL("ALTER TABLE reactions ADD COLUMN viewed INTEGER NOT NULL DEFAULT 0") }
        }
        // Papers and digests are a cache and can be rebuilt, but feedback is the user's
        // own data and must never be dropped silently, so only `shown` is recreated here.
        if (old < 3) {
            // Carry binary stars and hides across as 0.9 and 0.1 so nobody loses training
            // signal to a schema change. Saves become the orthogonal flag.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS reactions (
                  paper_id TEXT PRIMARY KEY,
                  interest REAL,
                  saved INTEGER NOT NULL DEFAULT 0,
                  ts INTEGER NOT NULL
                )
                """.trimIndent()
            )
            runCatching {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO reactions (paper_id, interest, saved, ts)
                    SELECT paper_id,
                           CASE state WHEN 'STAR' THEN 0.9 WHEN 'HIDE' THEN 0.1 ELSE NULL END,
                           CASE state WHEN 'SAVE' THEN 1 ELSE 0 END,
                           ts
                    FROM feedback
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE feedback")
            }
        }
        if (old < 2) {
            db.execSQL("DROP TABLE IF EXISTS shown")
            db.execSQL(
                """
                CREATE TABLE shown (
                  paper_id TEXT NOT NULL,
                  day TEXT NOT NULL,
                  slot TEXT NOT NULL DEFAULT 'RELEVANCE',
                  reason TEXT NOT NULL DEFAULT '',
                  confidence REAL NOT NULL DEFAULT 0,
                  rank INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (paper_id, day)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_shown_day ON shown(day)")
        }
    }

    fun upsertPapers(papers: List<Paper>) = writableDatabase.let { db ->
        db.beginTransaction()
        try {
            for (p in papers) {
                db.insertWithOnConflict("papers", null, ContentValues().apply {
                    put("id", p.id); put("title", p.title); put("abstract", p.abstract)
                    put("authors", p.authors.joinToString("|"))
                    put("categories", p.categories.joinToString("|"))
                    put("published", p.published); put("updated", p.updated)
                    put("comments", p.comments); put("journal_ref", p.journalRef)
                    put("fetched_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Newest cached papers, the candidate pool for an offline re-rank. */
    fun recentPapers(limit: Int = 1200): List<Paper> =
        readableDatabase.rawQuery(
            "SELECT * FROM papers ORDER BY published DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { it.toPapers() }

    fun papersById(ids: Collection<String>): List<Paper> {
        if (ids.isEmpty()) return emptyList()
        val ph = ids.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT * FROM papers WHERE id IN ($ph)", ids.toTypedArray()
        ).use { it.toPapers() }
    }

    fun setReaction(paperId: String, r: Reaction) = writableDatabase.let { db ->
        if (r.empty) {
            db.delete("reactions", "paper_id = ?", arrayOf(paperId))
        } else {
            db.insertWithOnConflict("reactions", null, ContentValues().apply {
                put("paper_id", paperId)
                // `interest` is left untouched: the column exists only so the migration that
                // reads it once still can. Judgements go to the signal ledger.
                put("saved", if (r.saved) 1 else 0)
                put("viewed", if (r.viewed) 1 else 0)
                put("ts", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * Papers shown between [fromDay] and [toDay] that the user did not take up, and whose
     * metadata has since gained a venue.
     *
     * "Did not take up" means never rated, or rated below [threshold]. An explicit low
     * rating still counts: being shown that a paper you actively dismissed went on to be
     * accepted is the more interesting version of the feature, not the less.
     */
    fun resurfaceCandidates(
        fromDay: String,
        toDay: String,
        limit: Int = 20,
    ): List<Paper> =
        readableDatabase.rawQuery(
            """
            SELECT p.*
            FROM shown s
            JOIN papers p ON p.id = s.paper_id
            WHERE s.day BETWEEN ? AND ?
              AND (p.comments != '' OR p.journal_ref != '')
              AND NOT EXISTS (
                SELECT 1 FROM signals g
                WHERE g.paper_id = s.paper_id AND g.signal != 'PASSED'
              )
            GROUP BY p.id
            ORDER BY s.day DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(fromDay, toDay, limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(cursorToPaper(c)) } }

    /**
     * Papers shown in the given window that still carry no venue.
     *
     * These are the Resurfacer's supply. A paper accepted to a conference gets its comments
     * field edited months after it was announced, long after the app cached it, so the copy
     * on disk is stale precisely for the papers the feature cares about. Refetching is the
     * only way to notice.
     */
    fun staleMetadataIds(fromDay: String, toDay: String, limit: Int = 200): List<String> =
        readableDatabase.rawQuery(
            """
            SELECT DISTINCT s.paper_id
            FROM shown s
            JOIN papers p ON p.id = s.paper_id
            WHERE s.day BETWEEN ? AND ?
              AND p.comments = '' AND p.journal_ref = ''
            ORDER BY s.day DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(fromDay, toDay, limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** The day a paper was first shown, for the "you passed on this in March" line. */
    fun firstShown(paperId: String): String? =
        readableDatabase.rawQuery(
            "SELECT MIN(day) FROM shown WHERE paper_id = ?", arrayOf(paperId)
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    /**
     * Papers judged in a time range, with their label, for the drift report.
     *
     * Dated by the first signal each paper earned in the window, so "what moved this
     * fortnight" means what the reader engaged with this fortnight.
     */
    fun ratedBetween(fromMillis: Long, toMillis: Long): List<Pair<Paper, Float>> {
        val ids = readableDatabase.rawQuery(
            "SELECT DISTINCT paper_id FROM signals WHERE ts >= ? AND ts < ?",
            arrayOf(fromMillis.toString(), toMillis.toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        if (ids.isEmpty()) return emptyList()
        val labels = evidence()
        return papersById(ids).mapNotNull { p -> labels[p.id]?.label()?.let { p to it } }
    }

    /**
     * How exploration cards fared: how many the user actually judged, and how many of those
     * they liked.
     *
     * The denominator counts only rated cards on purpose. An exploration card the user never
     * rated is not a card that failed, it is one they did not reach, and scoring it as a miss
     * would be the same mistake as treating everything scrolled past as a negative. The first
     * version of this counted all shown cards and duly announced that ten of ten had failed
     * when in truth none had been judged at all.
     */
    fun explorationOutcome(fromDay: String, toDay: String): Pair<Int, Int> =
        readableDatabase.rawQuery(
            """
            SELECT COUNT(DISTINCT s.paper_id) AS judged,
                   COUNT(DISTINCT CASE WHEN g.signal = 'LIKED' THEN s.paper_id END) AS liked
            FROM shown s JOIN signals g ON g.paper_id = s.paper_id
            WHERE s.slot = 'EXPLORATION' AND s.day BETWEEN ? AND ?
              AND g.signal IN ('LIKED', 'DISLIKED')
            """.trimIndent(),
            arrayOf(fromDay, toDay),
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) to (if (c.isNull(1)) 0 else c.getInt(1))
            else 0 to 0
        }

    /** Records one signal. Repeats are harmless; the first timestamp is kept. */
    fun addSignal(paperId: String, signal: Signal) {
        writableDatabase.insertWithOnConflict("signals", null, ContentValues().apply {
            put("paper_id", paperId)
            put("signal", signal.name)
            put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Removes one signal, for undoing an explicit judgement. */
    fun removeSignal(paperId: String, signal: Signal) {
        writableDatabase.delete(
            "signals", "paper_id = ? AND signal = ?", arrayOf(paperId, signal.name)
        )
    }

    fun evidence(): Map<String, Evidence> =
        readableDatabase.rawQuery("SELECT paper_id, signal FROM signals", null).use { c ->
            val acc = HashMap<String, MutableSet<Signal>>()
            while (c.moveToNext()) {
                val sig = runCatching { Signal.valueOf(c.getString(1)) }.getOrNull() ?: continue
                acc.getOrPut(c.getString(0)) { mutableSetOf() } += sig
            }
            acc.mapValues { (id, set) -> Evidence(id, set) }
        }

    /**
     * Per-topic engagement history, for the slot bandit, weighted towards recent days.
     *
     * A topic here is the paper's primary arXiv category: stable, already stored, and the
     * right granularity for deciding how much of a morning to spend on an area. Engaged
     * means a shown paper later earned a positive signal; ignored means it did not.
     *
     * Each paper contributes by the day it was last shown, discounted by
     * [TopicBandit.recency]. Counting every day equally made abandoned topics permanent:
     * a field the reader has left is buried under its own ignores, and coming back to it
     * could not dig it out, because the arithmetic could not tell "no longer interested"
     * from "was not interested last spring".
     *
     * Grouped per paper rather than per row, so a paper resurfaced three times is one
     * observation dated by its most recent showing, not three.
     */
    fun topicHistory(today: LocalDate = LocalDate.now()): Map<String, Pair<Float, Float>> =
        readableDatabase.rawQuery(
            """
            SELECT substr(p.categories, 1, CASE
                     WHEN instr(p.categories, '|') = 0 THEN length(p.categories)
                     ELSE instr(p.categories, '|') - 1 END) AS topic,
                   MAX(s.day) AS last_day,
                   MAX(CASE WHEN g.signal IN
                       ('LIKED','READ_PAGES','SHARED','DOWNLOADED','SAVED','DWELLED')
                     THEN 1 ELSE 0 END) AS engaged
            FROM shown s
            JOIN papers p ON p.id = s.paper_id
            LEFT JOIN signals g ON g.paper_id = s.paper_id
            GROUP BY s.paper_id
            """.trimIndent(),
            null,
        ).use { c ->
            val engaged = HashMap<String, Float>()
            val ignored = HashMap<String, Float>()
            while (c.moveToNext()) {
                val topic = c.getString(0)
                if (topic.isNullOrBlank()) continue
                // A day that will not parse is treated as today rather than dropped: a bad
                // row should not silently remove a topic's whole history from the bandit.
                val age = runCatching {
                    ChronoUnit.DAYS.between(LocalDate.parse(c.getString(1)), today)
                }.getOrDefault(0L).coerceAtLeast(0L)
                val w = TopicBandit.recency(age.toFloat())
                val bucket = if (c.getInt(2) == 1) engaged else ignored
                bucket[topic] = (bucket[topic] ?: 0f) + w
            }
            (engaged.keys + ignored.keys).associateWith {
                (engaged[it] ?: 0f) to (ignored[it] ?: 0f)
            }
        }

    /**
     * Searches papers already on the device.
     *
     * Not every search is a search of arXiv. "Where was that paper I saved last week" is a
     * different and more common question, it should not need the network, and going out to
     * arXiv for it would usually fail to find the very paper the reader means.
     */
    fun searchLocal(query: String, savedOnly: Boolean, limit: Int = 100): List<Paper> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (terms.isEmpty()) return emptyList()
        val where = terms.joinToString(" AND ") {
            "(lower(p.title) LIKE ? OR lower(p.abstract) LIKE ? OR lower(p.authors) LIKE ?)"
        }
        val args = terms.flatMap { listOf("%$it%", "%$it%", "%$it%") }.toMutableList()
        // "My library" is what the reader has saved or reacted to. The reaction half of that
        // has to come from the ledger; keyed off `interest` it silently narrowed to saves.
        val where2 = if (savedOnly) {
            """ AND (
              EXISTS (SELECT 1 FROM reactions r WHERE r.paper_id = p.id AND r.saved = 1)
              OR EXISTS (SELECT 1 FROM signals g WHERE g.paper_id = p.id
                         AND g.signal IN ('LIKED', 'DISLIKED'))
            )"""
        } else ""
        args += limit.toString()
        return readableDatabase.rawQuery(
            "SELECT p.* FROM papers p WHERE $where$where2 ORDER BY p.published DESC LIMIT ?",
            args.toTypedArray(),
        ).use { it.toPapers() }
    }

    fun clearSignals() { writableDatabase.delete("signals", null, null) }

    /**
     * Forgets everything the reader has taught the app.
     *
     * Both tables. Clearing only `reactions` left the whole signal ledger in place, so the
     * setting that promises to reset the model kept every judgement it had ever recorded and
     * the next digest was ranked exactly as before. Trust requires the exit to actually work.
     */
    fun clearFeedback() = writableDatabase.let { db ->
        db.delete("reactions", null, null)
        db.delete("signals", null, null)
    }

    fun allReactions(): Map<String, Reaction> =
        readableDatabase.rawQuery(
            "SELECT paper_id, saved, viewed FROM reactions", null
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(
                        c.getString(0),
                        Reaction(saved = c.getInt(1) == 1, viewed = c.getInt(2) == 1),
                    )
                }
            }
        }

    fun markShown(items: List<ShownItem>, day: String) = writableDatabase.let { db ->
        db.beginTransaction()
        try {
            // Replace, not ignore: rebuilding today's digest must overwrite the previous
            // ordering rather than leave a stale one behind.
            db.delete("shown", "day = ?", arrayOf(day))
            items.forEachIndexed { i, it ->
                db.insertWithOnConflict("shown", null, ContentValues().apply {
                    put("paper_id", it.paperId); put("day", day)
                    put("slot", it.slot); put("reason", it.reason)
                    put("confidence", it.confidence); put("rank", i)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** One past day the reader was actually shown a digest. */
    data class DigestDay(val day: String, val papers: Int, val reacted: Int)

    /**
     * The days a digest was built, most recent first.
     *
     * Only days the app was opened: the daily worker fetches papers but does not compose a
     * digest, so `shown` is a record of what the reader was offered, not of what existed.
     * That is the right thing for replaying a particular morning, and the wrong thing for
     * working out what somebody missed, which is what [unseenSince] is for.
     */
    fun digestDays(limit: Int = 30): List<DigestDay> =
        readableDatabase.rawQuery(
            """
            SELECT s.day,
                   COUNT(DISTINCT s.paper_id) AS papers,
                   COUNT(DISTINCT CASE WHEN g.signal IN ('LIKED','DISLIKED')
                         THEN s.paper_id END) AS reacted
            FROM shown s
            LEFT JOIN signals g ON g.paper_id = s.paper_id
            GROUP BY s.day
            ORDER BY s.day DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DigestDay(c.getString(0), c.getInt(1), c.getInt(2)))
                }
            }
        }

    /** The last day before [today] on which the reader opened the app and got a digest. */
    fun lastDigestDayBefore(today: String): String? =
        readableDatabase.rawQuery(
            "SELECT MAX(day) FROM shown WHERE day < ?", arrayOf(today)
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    /**
     * Papers announced since [day] that were never put in front of the reader.
     *
     * This, not the `shown` table, is what "you were away" means. The worker keeps fetching
     * while the app is closed, so a week away leaves several hundred papers on the device
     * that no digest ever selected: on this device, 779 from the last seven days. Anything
     * already carrying a signal is excluded, because the reader has plainly seen it.
     */
    fun unseenSince(day: String, limit: Int = 600): List<Paper> =
        readableDatabase.rawQuery(
            """
            SELECT p.* FROM papers p
            WHERE p.published > ?
              AND NOT EXISTS (SELECT 1 FROM shown s WHERE s.paper_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM signals g WHERE g.paper_id = p.id)
            ORDER BY p.published DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(day, limit.toString()),
        ).use { it.toPapers() }

    /** How many there are, without loading their abstracts. */
    fun unseenCountSince(day: String): Int =
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM papers p
            WHERE p.published > ?
              AND NOT EXISTS (SELECT 1 FROM shown s WHERE s.paper_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM signals g WHERE g.paper_id = p.id)
            """.trimIndent(),
            arrayOf(day),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun shownIds(): Set<String> =
        readableDatabase.rawQuery("SELECT DISTINCT paper_id FROM shown", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Today's digest in the order it was shown, with the reason each card carried. */
    fun digestFor(day: String): List<ShownItem> =
        readableDatabase.rawQuery(
            "SELECT paper_id, slot, reason, confidence FROM shown WHERE day = ? ORDER BY rank",
            arrayOf(day)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ShownItem(c.getString(0), c.getString(1), c.getString(2), c.getFloat(3)))
                }
            }
        }

    private fun cursorToPaper(c: android.database.Cursor): Paper = with(c) {
        Paper(
            id = getString(getColumnIndexOrThrow("id")),
            title = getString(getColumnIndexOrThrow("title")),
            abstract = getString(getColumnIndexOrThrow("abstract")),
            authors = getString(getColumnIndexOrThrow("authors")).split("|").filter { it.isNotBlank() },
            categories = getString(getColumnIndexOrThrow("categories")).split("|").filter { it.isNotBlank() },
            published = getString(getColumnIndexOrThrow("published")),
            updated = getString(getColumnIndexOrThrow("updated")),
            comments = getString(getColumnIndexOrThrow("comments")),
            journalRef = getString(getColumnIndexOrThrow("journal_ref")),
        )
    }

    private fun android.database.Cursor.toPapers(): List<Paper> = buildList {
        while (moveToNext()) {
            add(
                Paper(
                    id = getString(getColumnIndexOrThrow("id")),
                    title = getString(getColumnIndexOrThrow("title")),
                    abstract = getString(getColumnIndexOrThrow("abstract")),
                    authors = getString(getColumnIndexOrThrow("authors")).split("|").filter { it.isNotBlank() },
                    categories = getString(getColumnIndexOrThrow("categories")).split("|").filter { it.isNotBlank() },
                    published = getString(getColumnIndexOrThrow("published")),
                    updated = getString(getColumnIndexOrThrow("updated")),
                    comments = getString(getColumnIndexOrThrow("comments")),
                    journalRef = getString(getColumnIndexOrThrow("journal_ref")),
                )
            )
        }
    }

    /**
     * Remembers what the field was reading, so Popular survives a restart.
     *
     * Upvote counts used to live only in memory. Open the app on a Sunday, or any time no
     * fetch was due, and Popular quietly fell back to venue matches alone: a tab whose whole
     * job is "what is everyone reading" showing a list assembled from something else, with
     * nothing on screen to say so.
     *
     * Cheap to keep. A hundred rows of an id and an integer, replaced wholesale each fetch,
     * against a network call that is allowed to fail and often should be skipped.
     */
    fun saveAttention(upvotes: Map<String, Int>, now: Long = System.currentTimeMillis()) {
        if (upvotes.isEmpty()) return
        writableDatabase.let { db ->
            db.beginTransaction()
            try {
                for ((id, n) in upvotes) {
                    db.insertWithOnConflict("attention", null, ContentValues().apply {
                        put("paper_id", id); put("upvotes", n); put("ts", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                // Yesterday's list is not today's news and nothing reads it back, so it is
                // dropped here rather than left to grow by a hundred rows a day forever.
                db.delete(
                    "attention", "ts < ?",
                    arrayOf((now - RETENTION_DAYS * 24 * 60 * 60 * 1000).toString()),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * The stored attention counts, forgetting anything older than [days].
     *
     * "What is everyone reading" is a claim about now. A month-old upvote count is not a
     * weaker version of that claim, it is a different one, and letting it rank today's
     * Popular tab would be the same mistake as ranking by citations.
     */
    fun attention(days: Long = RETENTION_DAYS, now: Long = System.currentTimeMillis()): Map<String, Int> {
        val cutoff = now - days * 24 * 60 * 60 * 1000
        return readableDatabase.rawQuery(
            "SELECT paper_id, upvotes FROM attention WHERE ts >= ?",
            arrayOf(cutoff.toString()),
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
    }

    private companion object {
        /** How long a day's upvote counts stay useful. */
        const val RETENTION_DAYS = 30L

        const val ATTENTION_TABLE = """
            CREATE TABLE IF NOT EXISTS attention (
              paper_id TEXT PRIMARY KEY,
              upvotes INTEGER NOT NULL,
              ts INTEGER NOT NULL
            )
        """
    }
}
