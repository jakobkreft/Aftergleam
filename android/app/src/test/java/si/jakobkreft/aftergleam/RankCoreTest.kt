
package si.jakobkreft.aftergleam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.rank.Explain
import si.jakobkreft.aftergleam.rank.LogReg
import si.jakobkreft.aftergleam.rank.RatedDoc
import si.jakobkreft.aftergleam.rank.Ranker
import si.jakobkreft.aftergleam.rank.Scored
import si.jakobkreft.aftergleam.rank.Slot
import si.jakobkreft.aftergleam.rank.Weights
import si.jakobkreft.aftergleam.rank.Tfidf

class RankCoreTest {
    @Test
    fun `an explanation names the subject, not the scaffolding`() {
        // A real digest produced "matches layers, arbitrarily, terms". Two of those three
        // words appear in every abstract ever written and tell the reader nothing.
        val paper = Paper(
            id = "w1", title = "Sinkhorn layers", abstract = "x",
            authors = listOf("A"), categories = listOf("cs.LG"),
            published = "2026-09-01", updated = "2026-09-01",
        )
        val scored = Scored(
            paper = paper, score = 0.8f, relevance = 0.8f, slot = Slot.RELEVANCE,
            reasonTerms = listOf("layers", "arbitrarily", "terms", "transport"),
        )
        val why = scored.why()
        assertTrue("filler must not be named: $why", "arbitrarily" !in why)
        assertTrue("nor generic nouns: $why", "terms" !in why)
        assertTrue("the topical words stay: $why", "layers" in why && "transport" in why)
    }

    @Test
    fun `an all filler explanation still says something`() {
        // Better an unhelpful chip than an empty one that looks like a rendering bug.
        val paper = Paper(
            id = "w2", title = "T", abstract = "x", authors = listOf("A"),
            categories = listOf("cs.LG"), published = "2026-09-01", updated = "2026-09-01",
        )
        val why = Scored(
            paper = paper, score = 0.8f, relevance = 0.8f, slot = Slot.RELEVANCE,
            reasonTerms = listOf("results", "approach"),
        ).why()
        assertTrue("must not be blank: '$why'", why.isNotBlank())
    }


    private fun paper(id: String, title: String, abs: String, comments: String = "") =
        Paper(id, title, abs, listOf("A"), listOf("cs.CV"), "2026-09-01", "2026-09-01", comments)

    @Test
    fun `the model can use a word only the day's papers share with a liked one`() {
        // The vocabulary keeps words seen in at least two documents. Fitted on the training
        // papers alone, a subject the reader has liked once is a word seen once, so it was
        // dropped, and the day's papers on that subject looked like any other. On a real phone
        // 57% of a new paper's words were missing from the vocabulary this way.
        val liked = listOf(
            "tokamak plasma disruption forecasting",
            "coral reef bleaching field survey",
            "medieval manuscript dating from parchment",
        )
        val subjects = listOf(
            "tokamak plasma disruption", "coral reef bleaching", "medieval manuscript dating",
        )
        val onSubject = subjects.flatMap { s ->
            (1..4).map { paper("s${s.hashCode()}-$it", "New results on $s", "A study of $s, part $it.") }
        }
        val elsewhere = (1..20).map {
            paper("e$it", "Sparse regression $it", "A study of convex optimisation for sparse regression, part $it.")
        }
        val fields = listOf(
            "stochastic gradient methods", "graph neural networks", "protein structure",
            "image segmentation", "speech recognition",
        )
        val negatives = (1..40).map { "${fields[it % fields.size]} for large data, report $it" }

        val model = Ranker().train(
            candidates = onSubject + elsewhere,
            rated = liked.map { RatedDoc(null, it, 0.95f) },
            negativePool = negatives,
        )
        assertNotNull("three liked papers should train a model", model)
        fun score(p: Paper) = model!!.clf.predict(model.vec.transform(p.rankText))
        val weakestOnSubject = onSubject.minOf { score(it) }
        val strongestElsewhere = elsewhere.maxOf { score(it) }
        assertTrue(
            "every paper on a liked subject should outrank the rest: " +
                "$weakestOnSubject vs $strongestElsewhere",
            weakestOnSubject > strongestElsewhere,
        )
    }

    @Test
    fun `tokeniser produces unigrams and bigrams and drops stopwords`() {
        val t = Tfidf.terms("The diffusion model")
        assertTrue("diffusion" in t)
        assertTrue("model" in t)
        assertTrue("diffusion_model" in t)
        assertTrue("the" !in t)
    }

    @Test
    fun `tokeniser strips latex markup and urls`() {
        val t = Tfidf.terms(
            "We show \\textbf{strong} results, see https://github.com/foo/bar and \$x_i\$ scaling"
        )
        assertTrue("latex command leaked: $t", "textbf" !in t)
        assertTrue("url host leaked: $t", t.none { it.contains("github") })
        assertTrue("url scheme leaked: $t", t.none { it.contains("https") })
        assertTrue("real words should survive: $t", "results" in t)
        assertTrue("real words should survive: $t", "scaling" in t)
    }

    @Test
    fun `transform yields unit-length vectors`() {
        val v = Tfidf()
        v.fit(listOf(
            "diffusion models for panoramic image generation",
            "diffusion models for super resolution",
            "graph neural networks for molecules",
            "graph neural networks for proteins",
        ))
        val vec = v.transform("diffusion models for panoramic image generation")
        assertTrue("vocabulary should be non-empty", v.size > 0)
        val norm = kotlin.math.sqrt(vec.values.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1.0f, norm, 1e-3f)
    }

    @Test
    fun `unknown document yields empty vector rather than crashing`() {
        val v = Tfidf()
        v.fit(listOf("alpha beta gamma", "alpha beta delta"))
        assertTrue(v.transform("zzzz qqqq").isEmpty())
    }

    @Test
    fun `graded ratings order results between liked and disliked`() {
        val docs = listOf(
            "diffusion model panorama outpainting 360 degree image",
            "panoramic image generation with latent diffusion",
            "outpainting wide field of view images using diffusion",
            "federated learning convergence bounds for convex objectives",
            "differential privacy in distributed gradient descent",
        )
        // Graded, not binary: two emphatic likes, one lukewarm, two dislikes.
        val y = floatArrayOf(0.95f, 0.9f, 0.6f, 0.1f, 0.05f)
        val vec = Tfidf(minDf = 1).apply { fit(docs) }
        val clf = LogReg(vec.size).apply { fit(docs.map { vec.transform(it) }, y) }

        val liked = clf.predict(vec.transform("360 panorama diffusion outpainting"))
        val disliked = clf.predict(vec.transform("federated privacy gradient bounds"))
        assertTrue("liked $liked should exceed disliked $disliked", liked > disliked)
    }

    @Test
    fun `logistic regression separates two topics and explains itself`() {
        val pos = listOf(
            "diffusion model panorama outpainting 360 degree image",
            "panoramic image generation with latent diffusion",
            "outpainting wide field of view images using diffusion",
        )
        val neg = listOf(
            "federated learning convergence bounds for convex objectives",
            "differential privacy in distributed gradient descent",
            "byzantine robust aggregation for federated optimisation",
        )
        val vec = Tfidf(minDf = 1).apply { fit(pos + neg) }
        val x = (pos + neg).map { vec.transform(it) }
        val y = FloatArray(6) { if (it < 3) 1f else 0f }
        val clf = LogReg(vec.size).apply { fit(x, y) }

        val onTopic = clf.predict(vec.transform("360 degree panorama diffusion outpainting"))
        val offTopic = clf.predict(vec.transform("federated gradient descent privacy bounds"))
        assertTrue("on-topic $onTopic should beat off-topic $offTopic", onTopic > offTopic)
        assertTrue("on-topic should exceed 0.5, was $onTopic", onTopic > 0.5f)

        // The reason is read off the reader's own papers, not off the classifier's weights.
        val references = pos.map { vec.transform(it) }
        val match = Explain.match(
            vec.transform("360 degree panorama diffusion outpainting"), references, vec,
        )
        assertTrue("expected a readable reason, got $match", match!!.terms.isNotEmpty())
    }

    @Test
    fun `a strong venue cannot rescue an uninteresting paper`() {
        val liked = listOf(
            "diffusion model panorama outpainting 360 degree image synthesis",
            "panoramic image generation with latent diffusion models",
            "outpainting wide field of view images using diffusion priors",
        )
        val candidates = listOf(
            // On topic, no venue at all.
            paper("a", "Spherical diffusion for panoramic outpainting",
                "we present a diffusion model for 360 degree panoramic image outpainting",
                comments = "12 pages"),
            // Off topic, best possible venue.
            paper("b", "Convergence of federated optimisation",
                "we prove convergence bounds for distributed convex optimisation",
                comments = "Accepted at NeurIPS 2026"),
        )
        val rated = liked.map { RatedDoc(null, it, 0.9f) }
        val negatives = (1..40).map {
            "study $it of numerical methods for sparse linear systems and preconditioning"
        }

        // Once the reader has a real history the model is trusted, and no venue should be
        // able to promote a paper on a topic they dislike. Temperature is off and the seed
        // fixed so this measures the ranking rather than the draw.
        val settled = Ranker(Weights(temperature = 0.01f, diversity = 0f)).digest(
            candidates = candidates,
            rated = rated,
            seen = emptySet(),
            subscribed = setOf("cs.CV"),
            size = 2,
            random = kotlin.random.Random(1),
            negativePool = negatives,
            evidenceCount = 60,
        )
        assertEquals(
            "with a settled model the on-topic paper must win",
            "a", settled.first().paper.id,
        )
    }

    @Test
    fun `with almost no history the model already decides the order`() {
        // This used to let the venue win. Shrinkage pulls a three-rating model toward the
        // prior, and that was read as "the model barely knows, so lean on the acceptance".
        // But three liked papers already rank a reader's library far above chance (nDCG@25
        // of 0.55 against about 0.05 for a random order), and what shrinkage actually did
        // was hand the order to a fixed venue bonus. Ordering now uses the model's standing
        // among the day's papers, whatever its confidence; the uncertainty of an early model
        // is what the sampling, the exploration cards and the topic bandit are for.
        val liked = listOf(
            "diffusion model panorama outpainting 360 degree image synthesis",
            "panoramic image generation with latent diffusion models",
            "outpainting wide field of view images using diffusion priors",
        )
        val candidates = listOf(
            paper("a", "Spherical diffusion for panoramic outpainting",
                "we present a diffusion model for 360 degree panoramic image outpainting",
                comments = "12 pages"),
            paper("b", "Convergence of federated optimisation",
                "we prove convergence bounds for distributed convex optimisation",
                comments = "Accepted at NeurIPS 2026"),
        )
        val out = Ranker(Weights(temperature = 0.01f, diversity = 0f)).digest(
            candidates = candidates,
            rated = liked.map { RatedDoc(null, it, 0.9f) },
            seen = emptySet(),
            subscribed = setOf("cs.CV"),
            size = 2,
            random = kotlin.random.Random(1),
            negativePool = (1..40).map { "numerical methods sparse linear systems $it" },
            evidenceCount = 3,
        )
        assertEquals("both papers are still offered", 2, out.size)
        assertEquals("the paper on the reader's subject leads", "a", out.first().paper.id)
    }

    @Test
    fun `workshop acceptance scores below main track and is labelled as such`() {
        val main = paper("1", "T", "A", "Accepted at ECCV 2026")
        val workshop = paper("2", "T", "A", "Accepted at the MARS2 Workshop @ ECCV 2026")
        assertTrue(
            "workshop ${Venue.score(workshop)} should score below main ${Venue.score(main)}",
            Venue.score(workshop) < Venue.score(main)
        )
        assertEquals("ECCV 2026", Venue.of(main))
        assertEquals("ECCV 2026 workshop", Venue.of(workshop))
    }

    @Test
    fun `venue signal ranks acceptance above bare mention above nothing`() {
        val accepted = paper("1", "T", "A", "Accepted to NeurIPS 2025")
        val mention = paper("2", "T", "A", "We compare against the CVPR baseline")
        val nothing = paper("3", "T", "A", "12 pages, 4 figures")
        assertTrue(Venue.score(accepted) > Venue.score(mention))
        assertTrue(Venue.score(mention) > Venue.score(nothing))
        assertEquals(0.0f, Venue.score(nothing), 1e-6f)
        assertEquals("NEURIPS 2025", Venue.of(accepted))
    }
}
