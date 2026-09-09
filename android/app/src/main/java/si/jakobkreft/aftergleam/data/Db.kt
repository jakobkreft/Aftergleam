package si.jakobkreft.aftergleam.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite rather than Room.
 *
 * The schema is four tables with no joins worth checking at compile time, and dropping
 * Room also drops an annotation processor from the build. Since the ranker is TF-IDF
 * there are no embeddings to store either, so the vector-extension dependency the original
 * design assumed is gone as well.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "aftergleam.db", null, 4) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
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

    fun upsertPapers(papers: List<Paper>) = writableDatabase.use { db ->
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

    fun setReaction(paperId: String, r: Reaction) = writableDatabase.use { db ->
        if (r.empty) {
            db.delete("reactions", "paper_id = ?", arrayOf(paperId))
        } else {
            db.insertWithOnConflict("reactions", null, ContentValues().apply {
                put("paper_id", paperId)
                if (r.interest != null) put("interest", r.interest) else putNull("interest")
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
        threshold: Float = 0.5f,
        limit: Int = 20,
    ): List<Pair<Paper, Float?>> =
        readableDatabase.rawQuery(
            """
            SELECT p.*, r.interest AS rated
            FROM shown s
            JOIN papers p ON p.id = s.paper_id
            LEFT JOIN reactions r ON r.paper_id = s.paper_id
            WHERE s.day BETWEEN ? AND ?
              AND (r.interest IS NULL OR r.interest < ?)
              AND (p.comments != '' OR p.journal_ref != '')
            GROUP BY p.id
            ORDER BY s.day DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(fromDay, toDay, threshold.toString(), limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val rated = if (c.isNull(c.getColumnIndexOrThrow("rated"))) null
                    else c.getFloat(c.getColumnIndexOrThrow("rated"))
                    add(cursorToPaper(c) to rated)
                }
            }
        }

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

    /** Papers rated in a time range, with the rating, for the drift report. */
    fun ratedBetween(fromMillis: Long, toMillis: Long): List<Pair<Paper, Float>> =
        readableDatabase.rawQuery(
            """
            SELECT p.*, r.interest AS rated
            FROM reactions r JOIN papers p ON p.id = r.paper_id
            WHERE r.interest IS NOT NULL AND r.ts >= ? AND r.ts < ?
            """.trimIndent(),
            arrayOf(fromMillis.toString(), toMillis.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(cursorToPaper(c) to c.getFloat(c.getColumnIndexOrThrow("rated")))
                }
            }
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
            SELECT COUNT(*) AS judged,
                   SUM(CASE WHEN r.interest >= 0.5 THEN 1 ELSE 0 END) AS liked
            FROM shown s JOIN reactions r ON r.paper_id = s.paper_id
            WHERE s.slot = 'EXPLORATION' AND s.day BETWEEN ? AND ?
              AND r.interest IS NOT NULL
            """.trimIndent(),
            arrayOf(fromDay, toDay),
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) to (if (c.isNull(1)) 0 else c.getInt(1))
            else 0 to 0
        }

    fun clearReactions() = writableDatabase.use { it.delete("reactions", null, null) }

    fun allReactions(): Map<String, Reaction> =
        readableDatabase.rawQuery(
            "SELECT paper_id, interest, saved, viewed FROM reactions", null
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(
                        c.getString(0),
                        Reaction(
                            interest = if (c.isNull(1)) null else c.getFloat(1),
                            saved = c.getInt(2) == 1,
                            viewed = c.getInt(3) == 1,
                        )
                    )
                }
            }
        }

    /** Rated papers with their rating, the training set. */
    fun ratings(): Map<String, Float> =
        readableDatabase.rawQuery(
            "SELECT paper_id, interest FROM reactions WHERE interest IS NOT NULL", null
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getFloat(1)) }
        }

    fun markShown(items: List<ShownItem>, day: String) = writableDatabase.use { db ->
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
}
