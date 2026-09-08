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
class Db(context: Context) : SQLiteOpenHelper(context, "aftergleam.db", null, 3) {

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
        if (!r.rated && !r.saved) {
            db.delete("reactions", "paper_id = ?", arrayOf(paperId))
        } else {
            db.insertWithOnConflict("reactions", null, ContentValues().apply {
                put("paper_id", paperId)
                if (r.interest != null) put("interest", r.interest) else putNull("interest")
                put("saved", if (r.saved) 1 else 0)
                put("ts", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun allReactions(): Map<String, Reaction> =
        readableDatabase.rawQuery(
            "SELECT paper_id, interest, saved FROM reactions", null
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(
                        c.getString(0),
                        Reaction(
                            interest = if (c.isNull(1)) null else c.getFloat(1),
                            saved = c.getInt(2) == 1,
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
