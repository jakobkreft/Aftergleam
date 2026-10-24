package si.jakobkreft.aftergleam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.Topics

/**
 * The reader's keywords: a field to add one, and the keywords as chips.
 *
 * A likely misspelling is asked about, never fixed. A literal keyword spelt wrongly finds
 * nothing, so "satelite" stops at a question, "Did you mean satellite?", with both spellings
 * as buttons and the reader's own on the right where the eye ends. Correcting silently would
 * turn LoRA or a protein's name into an ordinary word nobody asked for; the check already
 * leaves acronyms alone, and the question covers whatever it gets wrong anyway.
 *
 * Tapping a keyword opens it, in place: see [CombineCard]. That is where it is combined, where
 * its bell is rung and where it is removed. Each chip shows its bell, on or off, and a pencil
 * that says there is more behind it. There is no cross: a small one beside the label was hit
 * by readers reaching for the keyword, and a keyword gone that way took its combination and
 * its bell with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordEditor(
    keywords: List<String>,
    onAdd: (String) -> Unit,
    suggest: suspend (String) -> String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    /** Recent papers per keyword, shown under the keywords when given. */
    counts: Map<String, Int>? = null,
    /** The whole list once a keyword has been combined, and the keywords with their bell on. */
    onReplace: (List<String>, Set<String>) -> Unit = { _, _ -> },
    /** Recent papers on the phone a keyword matches, for the count while it is combined. */
    count: suspend (String) -> KeywordMatches = { KeywordMatches(0, 0f) },
    /** The keywords with their bell on. */
    alerts: Set<String> = emptySet(),
    /** Asks for permission to notify, if it is needed, and answers whether it was given. */
    requestAlerts: ((Boolean) -> Unit) -> Unit = { it(true) },
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

    // The keyword open for combining. Saved when it is closed, however that happens: by Done,
    // by Back, or by leaving the page, with whatever is still typed in its field, so nothing
    // done to it is lost.
    var combining by remember { mutableStateOf<Combining?>(null) }
    val save by rememberUpdatedState(onReplace)
    val bells by rememberUpdatedState(alerts)
    DisposableEffect(Unit) {
        onDispose { combining?.let { save(it.commitDraft().keywords, it.alerts(bells)) } }
    }
    fun finish(edit: Combining, removed: Boolean = false) {
        combining = null
        if (removed) save(edit.others, bells - edit.original)
        else save(edit.commitDraft().keywords, edit.alerts(bells))
    }
    BackHandler(enabled = combining != null) { combining?.let { finish(it) } }

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

    Column(modifier.animateContentSize()) {
        val edit = combining
        if (edit != null) {
            CombineCard(
                edit = edit,
                count = count,
                suggest = suggest,
                onChange = { combining = it },
                onBell = { on ->
                    if (!on) combining = combining?.copy(alert = false)
                    else requestAlerts { granted -> combining = combining?.copy(alert = granted) }
                },
                onRemove = { finish(edit, removed = true) },
                onDone = { finish(edit) },
            )
            Spacer(Modifier.height(4.dp))
        } else {
            if (keywords.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keywords.forEachIndexed { i, keyword ->
                        InputChip(
                            selected = false,
                            onClick = { combining = Combining(keywords, i, alert = keyword in alerts) },
                            label = { Text(keywordLabel(keyword)) },
                            // The bell's state, on or off, as a channel shows it. Changed in the
                            // keyword itself, not here: a target this small is hit by accident.
                            leadingIcon = {
                                val on = keyword in alerts
                                Icon(
                                    if (on) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                                    contentDescription = if (on) "Alerts on" else "Alerts off",
                                    tint = if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            // Not a button of its own: the whole chip opens the keyword.
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "Edit",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
                Text(
                    "Tap a keyword to combine it with another word, turn its alerts on or off, or remove it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
