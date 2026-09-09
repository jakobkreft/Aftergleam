package si.jakobkreft.aftergleam.data

/**
 * Picks categories to look outside the user's own for.
 *
 * The bridge card could never fire before this: candidates came only from subscribed
 * categories, so "the best paper outside your fields" was always chosen from an empty set.
 * It needs its own small query.
 *
 * Which outside categories to ask for is the real question. Sampling the whole arXiv
 * taxonomy uniformly mostly returns things with no plausible connection at all, and the
 * genuine risk the design named for this feature is surfacing papers that merely share
 * vocabulary rather than ideas. So the pool is a curated set of fields that regularly
 * exchange methods with the ones people follow here, and it rotates by day so the same
 * neighbour is not offered every morning.
 */
object Bridge {

    /**
     * Neighbourhoods of fields that borrow methods from each other. A cs.CV reader is far
     * more likely to find something in graphics or medical imaging than in econometrics.
     */
    private val NEIGHBOURS: Map<String, List<String>> = mapOf(
        // The bridge crosses servers as well as archives. A cell biologist's most useful
        // "outside your usual" is often a methods paper on arXiv, and an arXiv reader's is
        // often the biology their methods are aimed at; neither would ever meet the other
        // without this, because the two archives share no vocabulary of categories.
        "biorxiv:cell biology" to listOf("q-bio.SC", "q-bio.CB", "biorxiv:biophysics"),
        "biorxiv:neuroscience" to listOf("q-bio.NC", "cs.NE", "medrxiv:neurology"),
        "biorxiv:genomics" to listOf("q-bio.GN", "biorxiv:bioinformatics", "cs.LG"),
        "biorxiv:bioinformatics" to listOf("q-bio.QM", "cs.LG", "stat.ME"),
        "biorxiv:cancer biology" to listOf("medrxiv:oncology", "q-bio.TO", "biorxiv:immunology"),
        "biorxiv:immunology" to listOf("medrxiv:infectious diseases", "q-bio.MN"),
        "biorxiv:ecology" to listOf("q-bio.PE", "biorxiv:evolutionary biology"),
        "biorxiv:evolutionary biology" to listOf("q-bio.PE", "biorxiv:ecology"),
        "biorxiv:biophysics" to listOf("physics.bio-ph", "cond-mat.soft", "q-bio.BM"),
        "biorxiv:systems biology" to listOf("q-bio.MN", "q-bio.QM"),
        "medrxiv:epidemiology" to listOf("q-bio.PE", "stat.AP", "medrxiv:public and global health"),
        "medrxiv:health informatics" to listOf("cs.LG", "cs.CY", "medrxiv:radiology and imaging"),
        "medrxiv:radiology and imaging" to listOf("eess.IV", "cs.CV", "physics.med-ph"),
        "medrxiv:neurology" to listOf("q-bio.NC", "biorxiv:neuroscience"),
        "medrxiv:genetic and genomic medicine" to listOf("q-bio.GN", "biorxiv:genomics"),
        "medrxiv:psychiatry and clinical psychology" to listOf("q-bio.NC", "medrxiv:neurology"),

        "cs.LG" to listOf("stat.ML", "math.OC", "q-bio.NC", "cs.NE", "physics.data-an"),
        "cs.CV" to listOf("eess.IV", "cs.GR", "q-bio.NC", "astro-ph.IM", "cs.RO"),
        "cs.CL" to listOf("cs.IR", "q-bio.NC", "cs.CY", "cs.SD"),
        "cs.AI" to listOf("cs.LO", "cs.MA", "q-bio.NC", "math.OC"),
        "cs.RO" to listOf("eess.SY", "cs.CV", "math.OC"),
        "cs.CR" to listOf("cs.IT", "math.NT", "cs.DC"),
        "cs.SE" to listOf("cs.PL", "cs.LO", "cs.DC"),
        "cs.IR" to listOf("cs.CL", "cs.DL", "stat.ML"),
        "stat.ML" to listOf("math.ST", "cs.LG", "math.OC"),
        "eess.IV" to listOf("cs.CV", "physics.med-ph", "astro-ph.IM"),
        "eess.AS" to listOf("cs.SD", "cs.CL", "physics.med-ph"),
        "q-bio.NC" to listOf("cs.NE", "cs.LG", "physics.bio-ph"),
        "math.OC" to listOf("cs.LG", "math.NA", "eess.SY"),
        "astro-ph.GA" to listOf("astro-ph.IM", "physics.data-an", "cs.LG"),
    )

    /** A short, non-empty list of categories to fetch a bridge pool from. */
    fun candidatesFor(subscribed: Set<String>, dayOfYear: Int, count: Int = 3): List<String> {
        val pool = subscribed
            .flatMap { NEIGHBOURS[it].orEmpty() }
            .filter { it !in subscribed }
            .distinct()
            .ifEmpty {
                // Nothing known about these categories, so fall back to broad neighbours
                // rather than giving up on the feature entirely.
                listOf("cs.LG", "cs.CV", "cs.CL", "stat.ML", "q-bio.NC", "math.OC")
                    .filter { it !in subscribed }
            }
        if (pool.isEmpty()) return emptyList()

        // Rotate by day so the same neighbour is not offered every morning.
        val start = dayOfYear % pool.size
        return List(minOf(count, pool.size)) { pool[(start + it) % pool.size] }
    }
}
