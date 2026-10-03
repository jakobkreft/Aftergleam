package si.jakobkreft.aftergleam.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports and restores everything the user has taught the app.
 *
 * This is the whole multi-device story. There is no account and no server, so the way to
 * carry a model to a new phone is a file the user owns: put it in a synced folder and
 * Syncthing does the rest, at the cost of no backend at all.
 *
 * Judgements and saves travel; cached papers do not. Papers are refetchable from arXiv in one
 * request, whereas a year of judgements is irreplaceable, and keeping the file small enough
 * to read and diff by hand is worth more than saving a fetch.
 *
 * Plain JSON, deliberately. An encrypted blob would be opaque to the person who owns it, and
 * the threat model here is losing a phone, not an adversary reading a file the user chose
 * where to put.
 */
object Backup {

    const val VERSION = 2

    /** Longer than any id a preprint server issues; DOIs and OSF guids run to about sixty. */
    private const val MAX_ID = 200
    const val MIME = "application/json"

    fun suggestedFileName(): String {
        val stamp = java.time.LocalDate.now().toString()
        return "aftergleam-$stamp.json"
    }

    fun export(db: Db, prefs: Prefs): String {
        // The ledger, not the legacy rating column. Version 1 exported `interest`, which
        // nothing has written since the ledger landed, so a backup taken from a phone in
        // daily use carried the reader's saves and none of their judgements.
        val evidence = db.evidence()
        val saved = db.allReactions().filterValues { it.saved }.keys
        val reactions = JSONArray()
        for (id in evidence.keys + saved) {
            val signals = evidence[id]?.signals.orEmpty()
            // A paper that was merely shown carries no judgement worth moving to another
            // device, and exporting it would write a row that restore then deletes.
            if (signals.isEmpty() && id !in saved) continue
            reactions.put(
                JSONObject().apply {
                    put("id", id)
                    if (signals.isNotEmpty()) {
                        put("signals", JSONArray(signals.map { it.name }))
                    }
                    if (id in saved) put("saved", true)
                }
            )
        }
        return JSONObject().apply {
            put("version", VERSION)
            put("exported", System.currentTimeMillis())
            put("categories", JSONArray(prefs.categories.toList()))
            put("digestSize", prefs.digestSize)
            put("qualityWeight", prefs.qualityWeight.toDouble())
            put("explorationRate", prefs.explorationRate.toDouble())
            put("diversity", prefs.diversity.toDouble())
            put("keywords", JSONArray(prefs.keywords))
            put("reactions", reactions)
        }.toString(2)
    }

    data class Restored(val reactions: Int, val categories: Set<String>)

    /**
     * Merges rather than replaces. Restoring on a phone that has already been used should
     * not silently discard whatever was judged there since the export.
     *
     * Version 1 files are still read: their single interest number becomes a like or a
     * dislike, which is all it ever meant.
     */
    fun restore(json: String, db: Db, prefs: Prefs): Restored {
        val root = JSONObject(json)
        require(root.optInt("version", 0) <= VERSION) {
            "This backup was written by a newer version of the app."
        }

        // A backup is a file from somewhere else: another phone, an older version, or a text
        // editor. Everything in it is checked against what the app itself could have written.
        //
        // Categories are kept only if a subject in this version of the app offers them. They
        // go into the arXiv request as they stand, so an unchecked one could carry extra query
        // parameters, and one this version no longer knows would be fetched for no subject.
        val known = Topics.FIELDS.flatMap { it.topics }.flatMap { it.qualified }.toSet()
        val cats = root.optJSONArray("categories")
        val categories = buildSet {
            if (cats != null) for (i in 0 until cats.length()) {
                cats.optString(i).takeIf { it in known }?.let { add(it) }
            }
        }
        if (categories.isNotEmpty()) prefs.categories = categories

        // Settings are held to the ranges the settings screen allows. A negative digest size
        // made every rebuild throw; NaN weights made every score NaN and the order random.
        fun number(key: String, range: ClosedFloatingPointRange<Double>): Double? =
            root.optDouble(key, Double.NaN).takeIf { it.isFinite() }?.coerceIn(range)
        number("digestSize", 5.0..60.0)?.let { prefs.digestSize = it.toInt() }
        number("qualityWeight", 0.0..1.0)?.let { prefs.qualityWeight = it.toFloat() }
        number("explorationRate", 0.0..0.4)?.let { prefs.explorationRate = it.toFloat() }
        number("diversity", 0.0..0.8)?.let { prefs.diversity = it.toFloat() }
        // Keywords pass through the same rule as typing them, and no more than the screen allows.
        root.optJSONArray("keywords")?.let { arr ->
            prefs.keywords = (0 until arr.length())
                .mapNotNull { Keywords.normalise(arr.optString(it)) }
                .distinctBy { it.lowercase() }
                .take(Keywords.MAX)
        }

        // Read once. It used to be re-read for every saved paper, which is a full table scan
        // per entry and turns a large backup into a quadratic restore.
        val reactions = db.allReactions().toMutableMap()

        val arr = root.optJSONArray("reactions") ?: JSONArray()
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // An id names a paper and, through the download cache, a file. The cache makes
            // it safe as a name, and this keeps an absurd one from being stored at all.
            val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: continue

            val signals = o.optJSONArray("signals")
            if (signals != null) {
                for (j in 0 until signals.length()) {
                    val s = runCatching { Signal.valueOf(signals.getString(j)) }.getOrNull()
                    if (s != null) db.addSignal(id, s)
                }
            } else if (o.has("interest")) {
                // A version 1 file. The number was never comparable between readers and only
                // its sign was ever meaningful, which is exactly what the ledger stores.
                val v = o.getDouble("interest").toFloat()
                db.addSignal(id, if (v >= 0.5f) Signal.LIKED else Signal.DISLIKED)
            }

            if (o.optBoolean("saved", false)) {
                val saved = (reactions[id] ?: Reaction.NONE).copy(saved = true)
                db.setReaction(id, saved)
                reactions[id] = saved
                db.addSignal(id, Signal.SAVED)
            }
            count++
        }
        return Restored(count, categories)
    }
}
