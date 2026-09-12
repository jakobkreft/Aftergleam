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
            Topic("vision", "Computer vision", listOf("cs.CV", "cs.MM"),
                "image recognition object detection segmentation visual features convolutional network dataset"),
            Topic("diffusion", "Generative models", listOf("cs.CV", "cs.LG"),
                "diffusion model generative adversarial network image synthesis sampling latent denoising"),
            Topic("robotics", "Robotics", listOf("cs.RO"),
                "robot manipulation control policy motion planning grasping sim to real embodied"),
            Topic("security", "Security and privacy", listOf("cs.CR"),
                "attack adversary privacy encryption protocol vulnerability threat model defence"),
            Topic("systems", "Systems and networks", listOf("cs.DC", "cs.NI", "cs.OS"),
                "distributed system scheduling latency throughput cluster network protocol cache"),
            Topic("theory", "Theory and algorithms", listOf("cs.DS", "cs.CC", "cs.DM", "cs.SC"),
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
            Topic("hardware", "Hardware and architecture", listOf("cs.AR", "cs.PF", "cs.ET"),
                "processor accelerator memory hierarchy fpga throughput power efficiency instruction pipeline"),
            Topic("infotheory", "Information and coding theory", listOf("cs.IT"),
                "capacity channel coding entropy rate distortion decoding compression bounds"),
            Topic("formal", "Logic and verification", listOf("cs.LO", "cs.FL"),
                "formal verification model checking theorem prover semantics automata proof assistant"),
        )),
        Field("Physics", listOf(
            Topic("astro", "Astrophysics", listOf("astro-ph.GA", "astro-ph.CO", "astro-ph.HE",
                "astro-ph.SR", "astro-ph.EP", "astro-ph.IM"),
                "galaxy star formation cosmology redshift telescope survey spectra luminosity halo "
                    + "stellar exoplanet planetary instrument pipeline photometry"),
            Topic("hep", "High energy physics", listOf("hep-ph", "hep-th", "hep-ex", "hep-lat"),
                "quantum field theory particle collider standard model gauge symmetry decay cross section"),
            Topic("condmat", "Condensed matter", listOf("cond-mat.str-el", "cond-mat.mtrl-sci",
                "cond-mat.mes-hall", "cond-mat.supr-con", "cond-mat.dis-nn", "cond-mat.other"),
                "electronic structure lattice spin phase transition superconducting material crystal "
                    + "magnetic nanoscale mesoscopic transport disorder film device"),
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
            Topic("geo", "Earth and atmosphere",
                listOf("physics.geo-ph", "physics.ao-ph", "physics.space-ph"),
                "seismic atmosphere ocean climate precipitation crust model reanalysis circulation"),
            Topic("atomic", "Atomic and molecular physics",
                listOf("physics.atom-ph", "physics.atm-clus", "cond-mat.quant-gas"),
                "atom trap laser cooling ionisation ultracold spectroscopy rydberg collision cross section"),
            Topic("nuclear", "Nuclear physics", listOf("nucl-th", "nucl-ex"),
                "nucleus nuclear matter cross section isotope reaction collision heavy ion neutron"),
            Topic("mathphys", "Mathematical physics", listOf("math-ph", "math.MP"),
                "operator hamiltonian spectrum integrable symmetry algebra rigorous asymptotic "
                    + "quantum field lattice model boundary condition"),
            Topic("appliedphys", "Applied and computational physics",
                listOf("physics.app-ph", "physics.comp-ph", "physics.data-an", "physics.class-ph"),
                "device fabrication measurement simulation numerical scheme sensor performance "
                    + "efficiency material thin film data analysis"),
            Topic("instrum", "Instrumentation and detectors",
                listOf("physics.ins-det", "physics.acc-ph"),
                "detector calibration readout resolution beam trigger noise commissioning "
                    + "accelerator cavity prototype test bench"),
            Topic("physsoc", "Physics, society and history",
                listOf("physics.soc-ph", "physics.ed-ph", "physics.hist-ph"),
                "network collective behaviour statistical distribution model social dynamics "
                    + "students teaching curriculum historical account"),
            Topic("nonlinear", "Nonlinear and complex systems",
                listOf("nlin.CD", "nlin.AO", "nlin.PS", "nlin.SI", "nlin.CG", "cond-mat.stat-mech"),
                "chaos bifurcation attractor synchronisation pattern formation soliton complex system "
                    + "statistical mechanics entropy equilibrium integrable lattice"),
        )),
        Field("Mathematics", listOf(
            Topic("analysis", "Analysis and PDEs", listOf("math.AP", "math.CA", "math.CV"),
                "equation solution existence regularity estimate boundary operator convergence norm"),
            Topic("algebra", "Algebra and geometry",
                listOf("math.AG", "math.RA", "math.GT", "math.AC", "math.QA"),
                "variety scheme group ring module cohomology manifold algebraic morphism"),
            Topic("probability", "Probability", listOf("math.PR"),
                "random process brownian motion distribution martingale limit theorem stochastic measure"),
            Topic("optimisation", "Optimisation", listOf("math.OC"),
                "convex optimisation convergence rate gradient constraint dual problem algorithm minimisation"),
            Topic("numerics", "Numerical methods", listOf("math.NA", "cs.MS"),
                "finite element discretisation error estimate solver mesh approximation stability scheme"),
            Topic("combinatorics", "Combinatorics and number theory", listOf("math.CO", "math.NT"),
                "graph vertices edges conjecture prime integer bound asymptotic counting"),
            Topic("geomtop", "Geometry and topology",
                listOf("math.DG", "math.SG", "math.MG", "math.AT", "math.GN", "math.KT"),
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
            Topic("quantbio", "Quantitative methods in biology", listOf("q-bio.QM", "q-bio.OT"),
                "model fitting parameter inference imaging analysis statistics measurement biological data"),
            Topic("medimg", "Medical imaging", listOf("eess.IV", "physics.med-ph"),
                "segmentation mri ct scan clinical patient diagnosis radiology annotation lesion"),
        )),
        Field("Statistics", listOf(
            Topic("stats-method", "Methods and inference", listOf("stat.ME", "stat.TH"),
                "estimator inference bayesian posterior likelihood confidence hypothesis asymptotic model selection"),
            Topic("stats-app", "Applied statistics", listOf("stat.AP", "stat.OT"),
                "data analysis regression covariates effect study cohort uncertainty prediction"),
            Topic("stats-comp", "Computational statistics", listOf("stat.CO"),
                "markov chain monte carlo sampling variational approximation algorithm posterior computation"),
        )),
        Field("Engineering", listOf(
            Topic("signal", "Signal processing", listOf("eess.SP"),
                "signal estimation channel noise spectrum filter antenna sampling wireless"),
            Topic("audio", "Speech and audio", listOf("eess.AS", "cs.SD"),
                "speech recognition speaker audio acoustic synthesis music waveform transcription"),
            Topic("comp-eng", "Computational engineering", listOf("cs.CE"),
                "simulation finite element mesh solver design optimisation structural thermal "
                    + "computational model validation engineering"),
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
                listOf("ecology", "evolutionary biology", "zoology", "paleontology"),
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
                listOf("epidemiology", "public and global health",
                       "occupational and environmental health", "nutrition"),
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
                listOf("oncology", "hematology", "rheumatology", "pathology"),
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
                       "medical education", "nursing", "primary care research"),
                "care services delivery cost effectiveness workforce access quality guidelines implementation",
                Source.MEDRXIV),
            Topic("mr-rehab", "Rehabilitation and physiotherapy",
                listOf("rehabilitation medicine and physical therapy", "sports medicine"),
                "rehabilitation exercise physiotherapy mobility strength gait function recovery "
                    + "training programme range of motion pain disability outcome measure",
                Source.MEDRXIV),
            Topic("mr-surgery", "Surgery and perioperative care",
                listOf("surgery", "anesthesia", "orthopedics", "transplantation", "urology",
                       "otolaryngology", "ophthalmology"),
                "operative procedure postoperative complications resection graft anaesthesia "
                    + "length of stay surgical outcomes",
                Source.MEDRXIV),
            Topic("mr-womenchild", "Women's and children's health",
                listOf("obstetrics and gynecology", "pediatrics",
                       "sexual and reproductive health"),
                "pregnancy maternal neonatal infants children birth gestational contraception "
                    + "growth development screening",
                Source.MEDRXIV),
            Topic("mr-acute", "Emergency, intensive and palliative care",
                listOf("emergency medicine", "intensive care and critical care medicine",
                       "geriatric medicine", "palliative medicine"),
                "admission triage ventilation mortality critically ill older adults frailty "
                    + "end of life symptom burden",
                Source.MEDRXIV),
            Topic("mr-clinical", "Other clinical specialties",
                listOf("dermatology", "respiratory medicine", "dentistry and oral medicine",
                       "pharmacology and therapeutics"),
                "patients diagnosis treatment clinical trial cohort outcomes follow up dose",
                Source.MEDRXIV),
        )),
        Field("Economics and finance", listOf(
            Topic("econ", "Economics", listOf("econ.EM", "econ.GN"),
                "market policy welfare equilibrium households estimation causal effect panel data"),
            Topic("finance", "Finance", listOf("q-fin.PM", "q-fin.ST", "q-fin.TR", "q-fin.GN"),
                "portfolio volatility asset pricing risk trading returns market microstructure hedging"),
            Topic("econtheory", "Economic theory", listOf("econ.TH", "cs.GT"),
                "equilibrium mechanism design incentives auction game strategic agents welfare bargaining"),
            Topic("mathfin", "Mathematical finance",
                listOf("q-fin.MF", "q-fin.PR", "q-fin.RM", "q-fin.CP"),
                "stochastic volatility option pricing martingale hedging risk measure derivative model"),
        )),
        Field("Psychology, from PsyArXiv", listOf(
            Topic("psy-cog", "Cognitive psychology",
                listOf("cognitive psychology", "cognitive neuroscience",
                       "judgment and decision making", "social cognition", "attention",
                       "memory", "perception", "learning"),
                "participants task response accuracy memory attention reaction time condition "
                    + "experiment stimuli cognitive processing bias",
                Source.PSYARXIV),
            Topic("psy-clin", "Clinical and mental health",
                listOf("clinical psychology", "psychiatry", "clinical neuroscience",
                       "health psychology", "counseling psychology"),
                "patients symptoms depression anxiety treatment therapy intervention outcome "
                    + "diagnosis severity scale wellbeing clinical sample",
                Source.PSYARXIV),
            Topic("psy-social", "Social and personality",
                listOf("social and personality psychology", "self and social identity",
                       "individual differences", "emotion", "social and behavioral sciences",
                       "personality"),
                "people attitudes behaviour group identity emotion traits survey sample "
                    + "association self report motivation",
                Source.PSYARXIV),
            Topic("psy-dev", "Developmental psychology",
                listOf("developmental psychology", "child psychology"),
                "children infants age development parents longitudinal language acquisition "
                    + "school years caregivers growth",
                Source.PSYARXIV),
            Topic("psy-methods", "Methods and meta-science",
                listOf("quantitative methods", "quantitative psychology", "meta-science",
                       "psychology, other"),
                "replication preregistration effect size power sample bayesian estimate "
                    + "measurement reliability model fit open data",
                Source.PSYARXIV),
        )),
        Field("Social science, from SocArXiv", listOf(
            Topic("soc-socio", "Sociology",
                listOf("sociology", "social and behavioral sciences", "economic sociology",
                       "medical sociology", "organizations, occupations, and work",
                       "inequality and stratification", "family, life course, and society"),
                "social inequality class households labour survey respondents institutions "
                    + "stratification mobility work organisations",
                Source.SOCARXIV),
            Topic("soc-policy", "Policy and public affairs",
                listOf("public affairs, public policy and public administration",
                       "science and technology policy", "international relations",
                       "urban studies and planning", "political science", "law and politics"),
                "policy government states governance reform programme implementation public "
                    + "administration cities planning",
                Source.SOCARXIV),
            Topic("soc-sts", "Science and technology studies",
                listOf("science and technology studies",
                       "communication, information technologies, and media sociology",
                       "environmental studies"),
                "science knowledge expertise technology infrastructure media platforms "
                    + "communication practices controversy environment",
                Source.SOCARXIV),
            Topic("soc-methods", "Social methods and statistics",
                listOf("social statistics", "models and methods", "demography, population, and ecology"),
                "model estimates regression survey sample measurement data population "
                    + "longitudinal cohort demographic method",
                Source.SOCARXIV),
        )),
        Field("Education, from EdArXiv", listOf(
            Topic("edu-teaching", "Teaching and curriculum",
                listOf("curriculum and instruction", "educational methods",
                       "teacher education and professional development",
                       "instructional media design", "elementary education",
                       "secondary education"),
                "students teachers classroom lesson curriculum instruction learning outcomes "
                    + "school practice intervention pedagogy",
                Source.EDARXIV),
            Topic("edu-assessment", "Assessment and education research",
                listOf("educational assessment, evaluation, and research", "education",
                       "educational psychology"),
                "achievement assessment test scores measurement validity motivation learning "
                    + "effects sample schools evaluation",
                Source.EDARXIV),
            Topic("edu-higher", "Higher and adult education",
                listOf("higher education", "higher education and teaching",
                       "adult and continuing education", "online and distance education",
                       "vocational education"),
                "university students courses degree faculty enrolment online learning adult "
                    + "learners training programme retention",
                Source.EDARXIV),
            Topic("edu-subject", "Subject teaching",
                listOf("science and mathematics education", "language and literacy education",
                       "bilingual, multilingual, and multicultural education"),
                "mathematics science reading literacy language learners conceptual "
                    + "understanding misconceptions tasks instruction",
                Source.EDARXIV),
        )),
        Field("Law, from Law Archive", listOf(
            Topic("law-public", "Public and constitutional law",
                listOf("law", "constitutional law", "administrative law",
                       "public law and legal theory", "law and politics",
                       "courts", "judges"),
                "court constitutional statute rights doctrine legislature judicial review "
                    + "state authority regulation jurisdiction",
                Source.LAWARCHIVE),
            Topic("law-criminal", "Criminal and civil law",
                listOf("criminal law", "criminal procedure", "civil law", "evidence",
                       "civil procedure", "family law"),
                "defendant sentencing offence prosecution evidence procedure liability "
                    + "damages claim trial punishment",
                Source.LAWARCHIVE),
            Topic("law-business", "Business and economic law",
                listOf("business organizations law", "law and economics",
                       "consumer protection law", "gaming law", "tax law",
                       "banking and finance law", "antitrust and trade regulation"),
                "firms contracts shareholders market regulation competition tax consumers "
                    + "liability corporate governance costs",
                Source.LAWARCHIVE),
            Topic("law-international", "International and comparative law",
                listOf("international law", "comparative and foreign law",
                       "human rights law", "immigration law"),
                "treaty states international jurisdiction human rights comparative "
                    + "convention tribunal sovereignty cross border",
                Source.LAWARCHIVE),
            Topic("law-tech", "Technology, health and society",
                listOf("science and technology law", "health law and policy",
                       "privacy law", "intellectual property law", "environmental law"),
                "data privacy platforms algorithms patents copyright health policy consent "
                    + "regulation technology liability",
                Source.LAWARCHIVE),
        )),
        Field("Chemistry, from ChemRxiv", listOf(
            Topic("chem-organic", "Organic chemistry and synthesis",
                listOf(ChemRxivApi.CATEGORY),
                "synthesis reaction catalyst yield substrate selectivity ligand asymmetric "
                    + "total synthesis mechanism functional group",
                Source.CHEMRXIV),
            Topic("chem-physical", "Physical and computational chemistry",
                listOf(ChemRxivApi.CATEGORY),
                "density functional calculations energies excited states dynamics simulation "
                    + "spectroscopy potential surface kinetics quantum chemical",
                Source.CHEMRXIV),
            Topic("chem-materials", "Materials and nanochemistry",
                listOf(ChemRxivApi.CATEGORY),
                "nanoparticles framework porous electrode battery film polymer surface "
                    + "crystal structure conductivity composite",
                Source.CHEMRXIV),
            Topic("chem-analytical", "Analytical chemistry",
                listOf(ChemRxivApi.CATEGORY),
                "detection sensor chromatography mass spectrometry quantification samples "
                    + "calibration limit of detection assay separation",
                Source.CHEMRXIV),
            Topic("chem-bio", "Biological and medicinal chemistry",
                listOf(ChemRxivApi.CATEGORY),
                "inhibitor binding affinity protein target compounds docking drug activity "
                    + "peptide enzyme selectivity",
                Source.CHEMRXIV),
        )),
    )

    fun topic(key: String): Topic? = FIELDS.flatMap { it.topics }.firstOrNull { it.key == key }

    /** A topic with the field it came from, so a search result keeps its context. */
    data class Match(val field: String, val topic: Topic)

    /**
     * Finds topics by name, by field, by the archive codes behind them, or by the words the
     * abstracts use.
     *
     * Fourteen fields and a hundred and fourteen topics is past the point where scrolling is
     * a reasonable way to find your own subject, and the reader who most needs the list is
     * the one who does not already know which field the app filed them under. Searching the
     * seed vocabulary as well as the label is what makes that work: "superconductivity"
     * finds condensed matter, "physiotherapy" finds rehabilitation, "qubit" finds quantum,
     * and none of those words is a topic name.
     *
     * Every word in the query has to match something, so a second word narrows rather than
     * widens. Label matches rank above field matches above vocabulary matches, because
     * somebody typing "law" wants the law topics before they want a paper that mentions it.
     */
    fun search(query: String): List<Match> {
        val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val out = mutableListOf<Pair<Int, Match>>()
        for (field in FIELDS) {
            for (t in field.topics) {
                val label = t.label.lowercase()
                val fieldLabel = field.label.lowercase()
                val cats = t.categories.joinToString(" ").lowercase()
                val seed = t.seed.lowercase()
                val haystack = "$label $fieldLabel $cats $seed"
                if (!words.all { matches(it, haystack) }) continue
                // The word itself beats a word that merely begins the same way. Typing
                // "physiotherapy" matched "physiology" on a six letter prefix and, ranked
                // together, put it above the topic actually called physiotherapy.
                val rank = when {
                    words.all { it in label } -> 0
                    words.all { matches(it, label) } -> 1
                    words.all { it in "$label $fieldLabel" } -> 2
                    words.all { matches(it, "$label $fieldLabel") } -> 3
                    words.all { matches(it, "$label $fieldLabel $cats") } -> 4
                    else -> 5
                }
                out += rank to Match(field.label, t)
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /**
     * Whether a typed word is in this text, allowing for the ending being different.
     *
     * Plain substring matching is too literal for a search box. Nobody looking for
     * superconductivity types "superconducting", which is the word the seed happens to use,
     * and a search that answers nothing reads as a subject the app does not carry. Sharing a
     * six letter prefix is enough to connect the two, and a wrong guess here costs one extra
     * row in a list rather than a wrong caption on a paper.
     */
    private fun matches(word: String, text: String): Boolean {
        if (word in text) return true
        if (word.length < PREFIX) return false
        val stem = word.take(PREFIX)
        return text.split(' ', '-', '.', ',', ':').any { it.length >= PREFIX && it.startsWith(stem) }
    }

    private const val PREFIX = 6

    fun categoriesFor(keys: Set<String>): Set<String> =
        keys.mapNotNull { topic(it) }.flatMap { it.qualified }.toSet()

    /** The subscribed categories belonging to one server, unqualified and ready to fetch. */
    fun categoriesOf(source: String, categories: Set<String>): Set<String> =
        categories.filter { Source.of(it) == source }.map { Source.display(it) }.toSet()

    fun seedsFor(keys: Set<String>): List<String> =
        keys.mapNotNull { topic(it) }.map { "${it.label}. ${it.seed}" }
}
