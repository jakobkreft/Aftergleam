package si.jakobkreft.aftergleam.data

/**
 * A card as it appeared in a digest. The reason and the model's confidence are stored
 * alongside the paper so that reopening the app shows the same explanation and the same
 * number it showed the first time. Regenerating them later would give a different answer
 * once the model has moved on, which would make the app look like it was making things up.
 */
data class ShownItem(
    val paperId: String,
    val slot: String,
    val reason: String,
    val confidence: Float,
)
