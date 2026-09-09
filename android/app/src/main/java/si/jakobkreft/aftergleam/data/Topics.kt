package si.jakobkreft.aftergleam.data

/**
 * A two-level map of the archive, used to start a model without any network calls.
 *
 * The ranker is TF-IDF over text, so a topic is simply a short pseudo-document. Choosing
 * "diffusion models" contributes the vocabulary those abstracts actually use, which is enough
 * to rank a first digest before the user has judged a single paper.
 *
 * Seed text is weaker evidence than a paper somebody actually read, and it is weighted below
 * an explicit rating for that reason. What it buys is direction: it decides which categories
 * to fetch and which papers the survey should ask about. The previous survey drew from a
 * fixed list that was almost entirely machine learning, so a biologist was shown ten
 * irrelevant papers and asked to judge them.
 *
 * Seeds are written in the vocabulary of real abstracts rather than as dictionary
 * definitions, because they are compared against abstracts.
 */
object Topics {

    data class Topic(
        val key: String,
        val label: String,
        val categories: List<String>,
        val seed: String,
    )

    data class Field(val label: String, val topics: List<Topic>)

    val FIELDS: List<Field> = listOf(
        Field("Computer science", listOf(
            Topic("ml", "Machine learning", listOf("cs.LG", "stat.ML"),
                "neural network training generalisation optimisation gradient descent representation learning benchmark"),
            Topic("llm", "Language models", listOf("cs.CL"),
                "large language model transformer pretraining instruction tuning reasoning prompt token generation"),
            Topic("vision", "Computer vision", listOf("cs.CV"),
                "image recognition object detection segmentation visual features convolutional network dataset"),
            Topic("diffusion", "Generative models", listOf("cs.CV", "cs.LG"),
                "diffusion model generative adversarial network image synthesis sampling latent denoising"),
            Topic("robotics", "Robotics", listOf("cs.RO"),
                "robot manipulation control policy motion planning grasping sim to real embodied"),
            Topic("security", "Security and privacy", listOf("cs.CR"),
                "attack adversary privacy encryption protocol vulnerability threat model defence"),
            Topic("systems", "Systems and networks", listOf("cs.DC", "cs.NI", "cs.OS"),
                "distributed system scheduling latency throughput cluster network protocol cache"),
            Topic("theory", "Theory and algorithms", listOf("cs.DS", "cs.CC"),
                "algorithm complexity bound approximation np hard polynomial time proof combinatorial"),
            Topic("hci", "Human-computer interaction", listOf("cs.HC"),
                "user study participants interface interaction design usability qualitative survey"),
        )),
        Field("Physics", listOf(
            Topic("astro", "Astrophysics", listOf("astro-ph.GA", "astro-ph.CO", "astro-ph.HE"),
                "galaxy star formation cosmology redshift telescope survey spectra luminosity halo"),
            Topic("hep", "High energy physics", listOf("hep-ph", "hep-th", "hep-ex"),
                "quantum field theory particle collider standard model gauge symmetry decay cross section"),
            Topic("condmat", "Condensed matter", listOf("cond-mat.str-el", "cond-mat.mtrl-sci"),
                "electronic structure lattice spin phase transition superconducting material crystal magnetic"),
            Topic("quantum", "Quantum physics", listOf("quant-ph"),
                "qubit entanglement quantum circuit decoherence measurement algorithm error correction"),
            Topic("gr", "Gravitation and relativity", listOf("gr-qc"),
                "black hole spacetime metric gravitational wave horizon einstein equations curvature"),
            Topic("fluids", "Fluids and soft matter", listOf("physics.flu-dyn", "cond-mat.soft"),
                "flow turbulence viscosity simulation reynolds number droplet interface stress"),
            Topic("optics", "Optics and photonics", listOf("physics.optics"),
                "laser optical waveguide photonic resonator wavelength scattering nonlinear beam"),
        )),
        Field("Mathematics", listOf(
            Topic("analysis", "Analysis and PDEs", listOf("math.AP", "math.CA"),
                "equation solution existence regularity estimate boundary operator convergence norm"),
            Topic("algebra", "Algebra and geometry", listOf("math.AG", "math.RA", "math.GT"),
                "variety scheme group ring module cohomology manifold algebraic morphism"),
            Topic("probability", "Probability", listOf("math.PR"),
                "random process brownian motion distribution martingale limit theorem stochastic measure"),
            Topic("optimisation", "Optimisation", listOf("math.OC"),
                "convex optimisation convergence rate gradient constraint dual problem algorithm minimisation"),
            Topic("numerics", "Numerical methods", listOf("math.NA"),
                "finite element discretisation error estimate solver mesh approximation stability scheme"),
            Topic("combinatorics", "Combinatorics and number theory", listOf("math.CO", "math.NT"),
                "graph vertices edges conjecture prime integer bound asymptotic counting"),
        )),
        Field("Biology and medicine", listOf(
            Topic("genomics", "Genomics", listOf("q-bio.GN"),
                "gene expression sequencing genome variant transcriptome rna cell annotation"),
            Topic("neuro", "Neuroscience", listOf("q-bio.NC"),
                "neuron cortex spiking activity brain synaptic recording behaviour stimulus"),
            Topic("biomol", "Molecular biology", listOf("q-bio.BM"),
                "protein structure binding folding molecular dynamics ligand simulation conformation"),
            Topic("popbio", "Populations and evolution", listOf("q-bio.PE"),
                "population dynamics evolution epidemic model species selection transmission fitness"),
            Topic("medimg", "Medical imaging", listOf("eess.IV", "physics.med-ph"),
                "segmentation mri ct scan clinical patient diagnosis radiology annotation lesion"),
        )),
        Field("Statistics", listOf(
            Topic("stats-method", "Methods and inference", listOf("stat.ME", "stat.TH"),
                "estimator inference bayesian posterior likelihood confidence hypothesis asymptotic model selection"),
            Topic("stats-app", "Applied statistics", listOf("stat.AP"),
                "data analysis regression covariates effect study cohort uncertainty prediction"),
            Topic("stats-comp", "Computational statistics", listOf("stat.CO"),
                "markov chain monte carlo sampling variational approximation algorithm posterior computation"),
        )),
        Field("Engineering", listOf(
            Topic("signal", "Signal processing", listOf("eess.SP"),
                "signal estimation channel noise spectrum filter antenna sampling wireless"),
            Topic("audio", "Speech and audio", listOf("eess.AS", "cs.SD"),
                "speech recognition speaker audio acoustic synthesis music waveform transcription"),
            Topic("control", "Control systems", listOf("eess.SY"),
                "control system stability feedback controller dynamics state estimation tracking"),
        )),
        Field("Economics and finance", listOf(
            Topic("econ", "Economics", listOf("econ.EM", "econ.GN"),
                "market policy welfare equilibrium households estimation causal effect panel data"),
            Topic("finance", "Finance", listOf("q-fin.PM", "q-fin.ST", "q-fin.TR"),
                "portfolio volatility asset pricing risk trading returns market microstructure hedging"),
        )),
    )

    fun topic(key: String): Topic? = FIELDS.flatMap { it.topics }.firstOrNull { it.key == key }

    fun categoriesFor(keys: Set<String>): Set<String> =
        keys.mapNotNull { topic(it) }.flatMap { it.categories }.toSet()

    fun seedsFor(keys: Set<String>): List<String> =
        keys.mapNotNull { topic(it) }.map { "${it.label}. ${it.seed}" }
}
