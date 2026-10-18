package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import si.jakobkreft.aftergleam.data.KeywordAlerts
import si.jakobkreft.aftergleam.data.Keywords

/**
 * A keyword open for combining, and the reader's whole list as it will be saved.
 *
 * Combining moves keywords rather than copying them. One of the reader's keywords added to
 * the open one leaves the list, since papers on hypoxia alone are no longer what was asked
 * for, and a word taken back out returns to the list as a keyword of its own, so trying a
 * combination loses nothing. Only a word typed in while the keyword was open simply goes:
 * it never was a keyword.
 *
 * What is typed and not yet added is part of it too, as [draft], and saved with the rest
 * however the keyword is closed. Pressing Done straight after typing is what people do, and
 * a word that needed its own tick first was a word lost.
 */
internal data class Combining(
    val keywords: List<String>,
    val open: Int,
    /** Words typed in while open, in lower case. */
    val typed: Set<String> = emptySet(),
    /** The text in the field, not yet added. */
    val draft: String = "",
    /** Whether the open keyword's bell is on. It stays with the keyword as it changes. */
    val alert: Boolean = false,
    /** The open keyword as it was opened, to find its bell among the reader's. */
    val original: String = keywords[open],
) {
    val keyword: String get() = keywords[open]
    val terms: List<String> get() = Keywords.terms(keyword)
    val others: List<String> get() = keywords.filterIndexed { i, _ -> i != open }
    val full: Boolean get() = terms.size >= Keywords.MAX_TERMS

    /** [draft] as words. Commas or AND separate several. */
    val draftWords: List<String>
        get() = draft.split(Regex("[,;]+")).mapNotNull(Keywords::normalise).flatMap(Keywords::terms)

    /** Whether [other]'s words fit into the open keyword. */
    fun fits(other: String): Boolean =
        (terms + Keywords.terms(other)).distinctBy { it.lowercase() }.size <= Keywords.MAX_TERMS

    /** [other], one of the reader's keywords, added to the open one and gone from the list. */
    fun add(other: String): Combining {
        val at = keywords.indexOf(other)
        if (at < 0 || at == open || !fits(other)) return this
        val joined = Keywords.join(terms + Keywords.terms(other)) ?: return this
        val list = keywords.toMutableList().apply {
            set(open, joined)
            removeAt(at)
        }
        return copy(keywords = list, open = if (at < open) open - 1 else open)
    }

    /** Typed [words] added to the open keyword. */
    fun type(words: List<String>): Combining {
        val joined = Keywords.join(terms + words) ?: return this
        val added = Keywords.terms(joined).drop(terms.size).map { it.lowercase() }
        return copy(keywords = keywords.toMutableList().apply { set(open, joined) }, typed = typed + added)
    }

    /** The [draft] added as words, and the field empty again. */
    fun commitDraft(): Combining = type(draftWords).copy(draft = "")

    /**
     * The keywords with their bell on once this is saved: [alerts] as they were, less any
     * keyword combined in or this one as it was, and this one as it is now if its bell is on.
     */
    fun alerts(alerts: Set<String>): Set<String> {
        val saved = commitDraft()
        val kept = alerts.filter { it != original && it in saved.keywords }.toSet()
        return if (alert) kept + saved.keyword else kept
    }

    /** [term] out of the open keyword, and back in the list unless it was only just typed. */
    fun takeOut(term: String): Combining {
        val joined = Keywords.join(terms.filterNot { it == term }) ?: return this
        val list = keywords.toMutableList().apply { set(open, joined) }
        val back = term.lowercase() !in typed && list.size < Keywords.MAX &&
            list.none { it.equals(term, ignoreCase = true) }
        if (back) list.add(open + 1, term)
        return copy(keywords = list, typed = typed - term.lowercase())
    }
}

/** How many recent papers on the phone a keyword finds, and about how many a week that is. */
data class KeywordMatches(val count: Int, val perWeek: Float)

/**
 * A keyword as its chip shows it, with the " + " between words in the accent colour, so a
 * combination reads as one thing and not as a long name.
 */
@Composable
internal fun keywordLabel(keyword: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        Keywords.terms(keyword).forEachIndexed { i, term ->
            if (i > 0) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(Keywords.AND) }
            append(term)
        }
    }
}

/**
 * The open keyword, written as the sentence it is: papers that mention lanosterol and hypoxia,
 * and a field after the last "and" for the next word.
 *
 * Opened in place, not in a sheet over the page. The reader's other keywords are offered
 * inside it, under the field, as the quickest way to add one, and narrowed to those that match
 * as the reader types, the way suggestions are. The count is the teacher: worked out from the
 * papers on the phone at every change, what is being typed included, so each word is seen to
 * narrow the keyword without a sentence about logic anywhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CombineCard(
    edit: Combining,
    count: suspend (String) -> KeywordMatches,
    suggest: suspend (String) -> String?,
    onChange: (Combining) -> Unit,
    /** The bell turned on or off. On asks for permission first; the answer is what is set. */
    onBell: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    val latest by rememberUpdatedState(edit)
    val terms = edit.terms
    // What Done would save, so the count answers for the word being typed as well.
    val pending = edit.commitDraft().keyword
    val found by produceState<KeywordMatches?>(null, pending) {
        value = null
        delay(300)
        value = count(pending)
    }
    val matches = found?.count
    val scope = rememberCoroutineScope()
    // A likely misspelling in the draft, asked about when the tick is pressed, and the
    // spellings already answered for, so a kept one is not asked about twice.
    var asked by remember { mutableStateOf<String?>(null) }
    var offered by remember { mutableStateOf<String?>(null) }
    var answered by remember { mutableStateOf(emptySet<String>()) }

    fun check(from: Combining) {
        scope.launch {
            for (word in from.draftWords) {
                if (word.lowercase() in answered) continue
                val fix = suggest(word) ?: continue
                asked = word
                offered = fix
                return@launch
            }
            // Typed on while this was looked up: the newer text is the reader's.
            if (latest.draft == from.draft) onChange(from.commitDraft())
        }
    }

    fun answer(choice: String) {
        val word = asked ?: return
        answered = answered + choice.lowercase()
        asked = null
        offered = null
        val next = latest.copy(
            draft = latest.draftWords.joinToString(", ") { if (it == word) choice else it },
        )
        onChange(next)
        check(next)
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Papers that mention",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // The bell, as on a channel one follows: rung, it says so when a new paper
                // appears. Filled when on, so the state reads at a glance.
                IconToggleButton(checked = edit.alert, onCheckedChange = onBell) {
                    Icon(
                        if (edit.alert) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                        contentDescription = if (edit.alert) "Stop alerts for this keyword" else "Alert me to new papers",
                        tint = if (edit.alert) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.padding(end = 8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    terms.forEachIndexed { i, term ->
                        if (i > 0) And(Modifier.align(Alignment.CenterVertically))
                        // A word on its own cannot be taken out of itself; the keyword is removed
                        // with the button below.
                        val alone = terms.size == 1
                        InputChip(
                            selected = true,
                            onClick = { if (!alone) onChange(edit.takeOut(term)) },
                            label = { Text(term) },
                            trailingIcon = if (alone) null else {
                                { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            },
                            modifier = if (alone) Modifier else Modifier.semantics { contentDescription = "Take $term out" },
                        )
                    }
                    if (!edit.full) {
                        And(Modifier.align(Alignment.CenterVertically))
                        WordField(
                            value = edit.draft,
                            onValueChange = {
                                asked = null
                                offered = null
                                onChange(edit.copy(draft = it))
                            },
                            onSubmit = { if (edit.draft.isNotBlank()) check(edit) },
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 160.dp)
                                .align(Alignment.CenterVertically),
                        )
                    }
                }

                val fix = offered
                val word = asked
                if (fix != null && word != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Did you mean \u201c$fix\u201d?", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { answer(fix) }) { Text(fix) }
                        TextButton(onClick = { answer(word) }) { Text("Keep \u201c$word\u201d") }
                    }
                } else if (!edit.full) {
                    // The reader's other keywords, a tap away. While a word is typed, only those
                    // it could be the start of, and taking one replaces what was typed.
                    val typing = edit.draft.trim()
                    val offer = edit.others.filter { typing.isEmpty() || it.contains(typing, ignoreCase = true) }
                    if (offer.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Or one of your keywords",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            offer.forEach { other ->
                                InputChip(
                                    selected = false,
                                    enabled = edit.fits(other),
                                    onClick = { onChange(edit.copy(draft = "").add(other)) },
                                    label = { Text(keywordLabel(other)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.semantics { contentDescription = "Add $other" },
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A keyword combines up to ${Keywords.MAX_TERMS} words.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    when (val n = matches) {
                        null -> "Counting\u2026"
                        0 -> "None on this phone from the last two months. The servers are asked too once you are done."
                        1 -> "1 paper on this phone from the last two months"
                        else -> "$n papers on this phone from the last two months"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // What the bell will do, and how often, said when it is rung: the moment to find
                // out that a broad keyword would be heard from every day.
                if (edit.alert) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You will get a note when a new one appears" +
                            (found?.takeIf { it.count > 0 }?.let { ", " + KeywordAlerts.describe(it.perWeek) } ?: "") + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("Remove keyword") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

/** The word between two words of a keyword. */
@Composable
private fun And(modifier: Modifier) = Text(
    "and",
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier,
)

/**
 * The field for the next word, drawn the size and shape of the words before it so that it
 * reads as the next one in the sentence rather than a form below it. The tick adds what is
 * typed as a word, to go on to another; it is never needed to keep it.
 */
@Composable
private fun WordField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colours = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = colours.onSurface),
        cursorBrush = SolidColor(colours.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        interactionSource = interaction,
        modifier = modifier.semantics { contentDescription = "Another word it must mention" },
        decorationBox = { inner ->
            Row(
                Modifier
                    .border(
                        if (focused) 2.dp else 1.dp,
                        if (focused) colours.primary else colours.outline,
                        MaterialTheme.shapes.small,
                    )
                    .heightIn(min = 40.dp)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("another word", style = style, color = colours.onSurfaceVariant)
                    inner()
                }
                if (value.isNotBlank()) {
                    IconButton(onClick = onSubmit, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = "Add", modifier = Modifier.size(20.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        },
    )
}
