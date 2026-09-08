package si.jakobkreft.aftergleam

import org.junit.Test
import si.jakobkreft.aftergleam.rank.LogReg
import si.jakobkreft.aftergleam.rank.Tfidf

/**
 * Not an assertion of correctness, a measurement. On the device the model's confidence
 * never exceeded 0.47 with three ratings, and a bar that never passes halfway reads as
 * "the model likes nothing". This works out whether that is undertraining or honest
 * uncertainty before anything is changed to fix it.
 */
class CalibrationDiagnostic {

    private val liked = listOf(
        "vision language action models for robotic manipulation and long horizon tasks",
        "teaching llm based agents to prioritise requirements before preferences",
        "causal information flow from vision to language in multimodal models",
    )

    // Stand-ins for the sampled easy negatives: unrelated arXiv-ish abstracts.
    private val negatives = (1..30).map { i ->
        "study number $i of stochastic convergence bounds in convex optimisation " +
            "with applications to distributed estimation and signal recovery"
    }

    private val onTopic =
        "a benchmark for vision language agents performing long horizon manipulation"

    @Test
    fun `report confidence against epochs and learning rate`() {
        val docs = liked + negatives
        val y = FloatArray(docs.size) { if (it < liked.size) 0.9f else 0f }
        println("%-8s %-6s %-10s %-10s".format("epochs", "lr", "onTopic", "negative"))
        for (epochs in listOf(200, 800, 3000)) {
            for (lr in listOf(0.5f, 2.0f)) {
                val vec = Tfidf(minDf = 1).apply { fit(docs) }
                val clf = LogReg(vec.size, lr = lr, epochs = epochs)
                    .apply { fit(docs.map { vec.transform(it) }, y) }
                val a = clf.predict(vec.transform(onTopic))
                val b = clf.predict(vec.transform(negatives.first()))
                println("%-8d %-6.1f %-10.3f %-10.3f".format(epochs, lr, a, b))
            }
        }
    }
}
