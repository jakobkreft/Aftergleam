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
 * Ratings and saves travel; cached papers do not. Papers are refetchable from arXiv in one
 * request, whereas a year of judgements is irreplaceable, and keeping the file small enough
 * to read and diff by hand is worth more than saving a fetch.
 *
 * Plain JSON, deliberately. An encrypted blob would be opaque to the person who owns it, and
 * the threat model here is losing a phone, not an adversary reading a file the user chose
 * where to put.
 */
object Backup {

    const val VERSION = 1
    const val MIME = "application/json"

    fun suggestedFileName(): String {
        val stamp = java.time.LocalDate.now().toString()
        return "aftergleam-$stamp.json"
    }

    fun export(db: Db, prefs: Prefs): String {
        val reactions = JSONArray()
        for ((id, r) in db.allReactions()) {
            reactions.put(
                JSONObject().apply {
                    put("id", id)
                    if (r.interest != null) put("interest", r.interest.toDouble())
                    if (r.saved) put("saved", true)
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
     * not silently discard whatever was rated there since the export.
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
            db.setReaction(
                id,
                Reaction(
                    interest = if (o.has("interest")) o.getDouble("interest").toFloat() else null,
                    saved = o.optBoolean("saved", false),
                ),
            )
            count++
        }
        return Restored(count, categories)
    }
}
