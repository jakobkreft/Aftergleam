package si.jakobkreft.aftergleam.rank

/** A paper the user has rated: its text, and how interesting they said it was. */
data class RatedDoc(val paperId: String?, val text: String, val interest: Float)
