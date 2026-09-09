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
        /**
         * Which server these categories belong to.
         *
         * bioRxiv's subjects are plain words where arXiv's are archive codes, and the two
         * namespaces overlap in meaning without overlapping in content: bioRxiv "genomics"
         * is sequencing work, arXiv `q-bio.GN` is modelling. Qualifying them keeps the
         * subscription, the fetch and the topic bandit all talking about the same thing.
         */
        val source: String = Source.ARXIV,
    ) {
        val qualified: List<String> get() = categories.map { Source.qualify(source, it) }
    }

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
            Topic("software", "Software engineering", listOf("cs.SE", "cs.PL"),
                "software developers code repository testing bug program analysis compiler type system refactoring"),
            Topic("ir", "Search and recommendation", listOf("cs.IR"),
                "retrieval ranking query relevance recommendation collaborative filtering user clicks index"),
            Topic("data", "Databases and data", listOf("cs.DB", "cs.DL"),
                "query optimisation transactions storage engine index schema data integration provenance"),
            Topic("graphics", "Graphics and geometry", listOf("cs.GR", "cs.CG"),
                "rendering mesh geometry shading animation texture simulation surface reconstruction"),
            Topic("netsoc", "Networks and society", listOf("cs.SI", "cs.CY"),
                "social network graph diffusion misinformation platform users community structure policy"),
            Topic("agents", "AI, agents and planning", listOf("cs.AI", "cs.MA"),
                "agent planning reasoning knowledge representation search heuristic multi agent coordination"),
            Topic("neuroevo", "Neural and evolutionary computing", listOf("cs.NE"),
                "evolutionary algorithm genetic programming spiking neural network neuromorphic swarm optimisation"),
            Topic("hardware", "Hardware and architecture", listOf("cs.AR", "cs.PF"),
                "processor accelerator memory hierarchy fpga throughput power efficiency instruction pipeline"),
            Topic("infotheory", "Information and coding theory", listOf("cs.IT"),
                "capacity channel coding entropy rate distortion decoding compression bounds"),
            Topic("formal", "Logic and verification", listOf("cs.LO", "cs.FL"),
                "formal verification model checking theorem prover semantics automata proof assistant"),
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
            Topic("chemphys", "Chemistry and chemical physics", listOf("physics.chem-ph", "cond-mat.mtrl-sci"),
                "molecule reaction catalysis binding energy density functional spectra solvent electronic structure"),
            Topic("biophys", "Biological physics", listOf("physics.bio-ph"),
                "membrane protein folding cell mechanics active matter molecular motor stochastic dynamics"),
            Topic("plasma", "Plasma and fusion", listOf("physics.plasm-ph"),
                "plasma tokamak confinement instability magnetic reconnection turbulence fusion discharge"),
            Topic("geo", "Earth and atmosphere", listOf("physics.geo-ph", "physics.ao-ph"),
                "seismic atmosphere ocean climate precipitation crust model reanalysis circulation"),
            Topic("atomic", "Atomic and molecular physics", listOf("physics.atom-ph", "physics.atm-clus"),
                "atom trap laser cooling ionisation ultracold spectroscopy rydberg collision cross section"),
            Topic("nuclear", "Nuclear physics", listOf("nucl-th", "nucl-ex"),
                "nucleus nuclear matter cross section isotope reaction collision heavy ion neutron"),
            Topic("nonlinear", "Nonlinear and complex systems", listOf("nlin.CD", "nlin.AO", "nlin.PS"),
                "chaos bifurcation attractor synchronisation pattern formation soliton complex system"),
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
            Topic("geomtop", "Geometry and topology", listOf("math.DG", "math.SG", "math.MG"),
                "manifold curvature riemannian metric symplectic geodesic topology invariant flow"),
            Topic("logic", "Logic and foundations", listOf("math.LO", "math.CT"),
                "set theory model theory forcing category functor proof cardinal definable"),
            Topic("funcanalysis", "Functional analysis and operators", listOf("math.FA", "math.OA", "math.SP"),
                "banach hilbert space operator spectrum norm bounded algebra self adjoint"),
            Topic("dynamics", "Dynamical systems", listOf("math.DS"),
                "orbit invariant measure ergodic entropy attractor recurrence flow map"),
            Topic("groups", "Groups and representations", listOf("math.GR", "math.RT"),
                "group representation module character lie algebra irreducible symmetry action"),
            Topic("mathstat", "Mathematical statistics", listOf("math.ST"),
                "estimator asymptotic consistency minimax convergence rate hypothesis test posterior"),
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
            Topic("cellbio", "Cell and subcellular biology", listOf("q-bio.CB", "q-bio.SC"),
                "cell migration adhesion cytoskeleton signalling organelle membrane transport division motility"),
            Topic("tissues", "Tissues, organs and physiology", listOf("q-bio.TO"),
                "tissue organ growth mechanics vasculature morphogenesis wound remodelling physiology"),
            Topic("bionet", "Molecular networks", listOf("q-bio.MN"),
                "gene regulatory network signalling pathway metabolic flux feedback motif kinetics"),
            Topic("quantbio", "Quantitative methods in biology", listOf("q-bio.QM"),
                "model fitting parameter inference imaging analysis statistics measurement biological data"),
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
        Field("Biology, from bioRxiv", listOf(
            Topic("br-cell", "Cell and molecular biology",
                listOf("cell biology", "molecular biology", "biochemistry", "developmental biology"),
                "cells expression protein signalling pathway assay knockout imaging in vitro culture differentiation",
                Source.BIORXIV),
            Topic("br-neuro", "Neuroscience and behaviour",
                listOf("neuroscience", "animal behavior and cognition"),
                "neurons cortex synaptic circuit recording behaviour mice stimulus activity plasticity",
                Source.BIORXIV),
            Topic("br-cancer", "Cancer, immunology and disease",
                listOf("cancer biology", "immunology", "pathology"),
                "tumour cells immune response antigen therapy patients mutation resistance inflammation",
                Source.BIORXIV),
            Topic("br-micro", "Microbiology",
                listOf("microbiology"),
                "bacteria strains infection host microbiome antibiotic virulence culture growth species",
                Source.BIORXIV),
            Topic("br-genomics", "Genomics and bioinformatics",
                listOf("genomics", "bioinformatics", "genetics", "systems biology"),
                "sequencing reads genome variants expression annotation pipeline single cell alignment dataset",
                Source.BIORXIV),
            Topic("br-eco", "Ecology and evolution",
                listOf("ecology", "evolutionary biology", "zoology"),
                "species populations habitat diversity selection phylogeny traits field sampling climate",
                Source.BIORXIV),
            Topic("br-plant", "Plant biology",
                listOf("plant biology"),
                "plants roots leaves arabidopsis growth stress tolerance photosynthesis crop yield",
                Source.BIORXIV),
            Topic("br-biophys", "Biophysics and bioengineering",
                listOf("biophysics", "bioengineering", "synthetic biology"),
                "structure binding dynamics simulation membrane force microscopy engineered construct device",
                Source.BIORXIV),
            Topic("br-physio", "Physiology and pharmacology",
                listOf("physiology", "pharmacology and toxicology"),
                "muscle metabolism response dose exposure animals measurement organ function drug",
                Source.BIORXIV),
        )),
        Field("Medicine, from medRxiv", listOf(
            Topic("mr-epi", "Epidemiology and public health",
                listOf("epidemiology", "public and global health", "occupational and environmental health"),
                "cohort incidence risk factors population survey prevalence exposure mortality association",
                Source.MEDRXIV),
            Topic("mr-infect", "Infectious disease",
                listOf("infectious diseases", "hiv aids", "allergy and immunology"),
                "infection transmission vaccine antibody outbreak pathogen treatment surveillance",
                Source.MEDRXIV),
            Topic("mr-neuro", "Neurology and mental health",
                listOf("neurology", "psychiatry and clinical psychology", "addiction medicine", "pain medicine"),
                "patients symptoms cognitive depression brain imaging trial diagnosis therapy scale",
                Source.MEDRXIV),
            Topic("mr-cardio", "Cardiovascular and metabolic",
                listOf("cardiovascular medicine", "endocrinology", "nephrology", "gastroenterology"),
                "blood pressure cardiac diabetes kidney metabolic outcomes cholesterol risk score",
                Source.MEDRXIV),
            Topic("mr-onc", "Oncology and haematology",
                listOf("oncology", "hematology", "rheumatology"),
                "tumour survival chemotherapy staging biomarker response cohort treatment outcomes",
                Source.MEDRXIV),
            Topic("mr-informatics", "Health informatics and imaging",
                listOf("health informatics", "radiology and imaging"),
                "electronic health records model prediction imaging deep learning validation clinical decision",
                Source.MEDRXIV),
            Topic("mr-genomic", "Genetic and genomic medicine",
                listOf("genetic and genomic medicine"),
                "variants association genome wide inherited sequencing cohort penetrance risk allele",
                Source.MEDRXIV),
            Topic("mr-systems", "Health systems and policy",
                listOf("health systems and quality improvement", "health policy", "health economics",
                       "medical education", "nursing"),
                "care services delivery cost effectiveness workforce access quality guidelines implementation",
                Source.MEDRXIV),
            Topic("mr-clinical", "Clinical specialties",
                listOf("surgery", "pediatrics", "obstetrics and gynecology", "ophthalmology",
                       "dermatology", "respiratory medicine", "emergency medicine", "orthopedics",
                       "urology", "geriatric medicine", "intensive care and critical care medicine",
                       "anesthesia", "otolaryngology", "dentistry and oral medicine",
                       "rehabilitation medicine and physical therapy", "sports medicine",
                       "transplantation", "sexual and reproductive health"),
                "patients procedure postoperative outcomes clinical trial cohort complications follow up",
                Source.MEDRXIV),
        )),
        Field("Economics and finance", listOf(
            Topic("econ", "Economics", listOf("econ.EM", "econ.GN"),
                "market policy welfare equilibrium households estimation causal effect panel data"),
            Topic("finance", "Finance", listOf("q-fin.PM", "q-fin.ST", "q-fin.TR"),
                "portfolio volatility asset pricing risk trading returns market microstructure hedging"),
            Topic("econtheory", "Economic theory", listOf("econ.TH", "cs.GT"),
                "equilibrium mechanism design incentives auction game strategic agents welfare bargaining"),
            Topic("mathfin", "Mathematical finance", listOf("q-fin.MF", "q-fin.PR", "q-fin.RM"),
                "stochastic volatility option pricing martingale hedging risk measure derivative model"),
        )),
    )

    fun topic(key: String): Topic? = FIELDS.flatMap { it.topics }.firstOrNull { it.key == key }

    fun categoriesFor(keys: Set<String>): Set<String> =
        keys.mapNotNull { topic(it) }.flatMap { it.qualified }.toSet()

    /** The subscribed categories belonging to one server, unqualified and ready to fetch. */
    fun categoriesOf(source: String, categories: Set<String>): Set<String> =
        categories.filter { Source.of(it) == source }.map { Source.display(it) }.toSet()

    fun seedsFor(keys: Set<String>): List<String> =
        keys.mapNotNull { topic(it) }.map { "${it.label}. ${it.seed}" }
}
