package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.Topics

/**
 * The reader's keywords: a field to add one, and the keywords as chips that remove themselves.
 *
 * A likely misspelling is asked about, never fixed. A literal keyword spelt wrongly finds
 * nothing, so "satelite" stops at a question, "Did you mean satellite?", with both spellings
 * as buttons and the reader's own on the right where the eye ends. Correcting silently would
 * turn LoRA or a protein's name into an ordinary word nobody asked for; the check already
 * leaves acronyms alone, and the question covers whatever it gets wrong anyway.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordEditor(
    keywords: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    suggest: suspend (String) -> String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    /** Recent papers per keyword, shown under the keywords when given. */
    counts: Map<String, Int>? = null,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The keyword waiting on an answer, and the spelling offered for it.
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    var offered by rememberSaveable { mutableStateOf<String?>(null) }
    // Asked again whenever a new keyword is begun, so a vocabulary built before the latest
    // papers arrived is brought up to date. Rebuilt only when newer papers are on the device.
    LaunchedEffect(text.isEmpty()) { onReady() }

    val full = keywords.size >= Keywords.MAX

    fun submit(raw: String) {
        if (checking) return
        val parts = Keywords.split(raw)
        checking = true
        scope.launch {
            try {
                for ((i, part) in parts.withIndex()) {
                    val fix = suggest(part)
                    if (fix != null) {
                        asked = part
                        offered = fix
                        // Anything typed after it waits in the field for the reader.
                        text = parts.drop(i + 1).joinToString(", ")
                        return@launch
                    }
                    onAdd(part)
                }
                text = ""
            } finally {
                checking = false
            }
        }
    }

    fun answer(keyword: String) {
        onAdd(keyword)
        asked = null
        offered = null
    }

    Column(modifier) {
        if (keywords.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keywords.forEach { keyword ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(keyword) },
                        label = { Text(keyword) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $keyword",
                                modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
            // How many recent papers each keyword finds, so one that papers write differently
            // shows itself. A keyword still being fetched shows an ellipsis rather than a count.
            if (counts != null && counts.isNotEmpty()) {
                Text(
                    "Papers in the last two months: " + keywords.joinToString(" · ") { keyword ->
                        "$keyword " + when (val n = counts[keyword]) {
                            null -> "\u2026"
                            0 -> "none"
                            else -> "$n"
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (keywords.any { counts[it] == 0 }) {
                    Text(
                        "None can mean the name is rare, or that papers write it another way.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (asked != null && offered != null) {
            Text("Did you mean “$offered”?", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { answer(offered!!) }) { Text(offered!!) }
                TextButton(onClick = { answer(asked!!) }) { Text("Keep “$asked”") }
            }
            Spacer(Modifier.height(4.dp))
        }

        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                // A comma ends a keyword, the way it does in an email's address field.
                val cut = v.lastIndexOfAny(charArrayOf(',', ';'))
                if (cut >= 0 && asked == null) {
                    submit(v.substring(0, cut))
                    if (asked == null) text = v.substring(cut + 1).trimStart()
                } else {
                    text = v
                }
            },
            enabled = !full && asked == null && !checking,
            singleLine = true,
            label = { Text(if (full) "That is the most it takes" else "Add a keyword") },
            placeholder = {
                Text(placeholder, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            },
            trailingIcon = {
                if (text.isNotBlank()) {
                    IconButton(onClick = { submit(text) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add keyword")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) submit(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Example keywords in the reader's own field, for the empty field to show.
 *
 * Names rather than topics, because a keyword is found as written: "Sentinel-2" says what is
 * meant to a computer vision reader, and nothing to a lawyer.
 */
fun keywordExample(topics: Set<String>): String {
    for (key in topics) EXAMPLES[key]?.let { return "e.g. $it" }
    val field = Topics.FIELDS.firstOrNull { f -> f.topics.any { it.key in topics } }?.label
    return "e.g. " + (FIELD_EXAMPLES.entries.firstOrNull { field?.startsWith(it.key) == true }?.value
        ?: "a method, a dataset or a gene")
}

private val EXAMPLES = mapOf(
    "vision" to "Sentinel-2, NeRF",
    "ml" to "LoRA, PyTorch",
    "llm" to "RAG, tokenizer",
    "robotics" to "MuJoCo, quadruped",
    "security" to "GDPR, side-channel",
    "numerics" to "multigrid, GMRES",
    "neuro" to "Neuropixels, place cells",
)

private val FIELD_EXAMPLES = mapOf(
    "Computer science" to "LoRA, PyTorch",
    "Physics" to "JWST, dark matter",
    "Mathematics" to "Riemann hypothesis, knot invariants",
    "Biology" to "CRISPR, C. elegans",
    "Medicine" to "long COVID, GLP-1",
    "Statistics" to "bootstrap, Bayesian optimisation",
    "Engineering" to "lithium-ion, digital twin",
    "Economics" to "CBDC, inflation expectations",
    "Psychology" to "working memory, ADHD",
    "Social science" to "migration, Gini",
    "Education" to "phonics, MOOC",
    "Law" to "GDPR, Article 6",
    "Chemistry" to "perovskite, MOF",
)
