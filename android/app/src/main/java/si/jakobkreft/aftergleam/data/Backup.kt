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

        val cats = root.optJSONArray("categories")
        val categories = buildSet {
            if (cats != null) for (i in 0 until cats.length()) add(cats.getString(i))
        }
        if (categories.isNotEmpty()) prefs.categories = categories
        if (root.has("digestSize")) prefs.digestSize = root.getInt("digestSize")
        if (root.has("qualityWeight")) prefs.qualityWeight = root.getDouble("qualityWeight").toFloat()
        if (root.has("explorationRate")) {
            prefs.explorationRate = root.getDouble("explorationRate").toFloat()
        }
        if (root.has("diversity")) prefs.diversity = root.getDouble("diversity").toFloat()

        val arr = root.optJSONArray("reactions") ?: JSONArray()
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id").ifBlank { continue }

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
                db.setReaction(id, (db.allReactions()[id] ?: Reaction.NONE).copy(saved = true))
                db.addSignal(id, Signal.SAVED)
            }
            count++
        }
        return Restored(count, categories)
    }
}
