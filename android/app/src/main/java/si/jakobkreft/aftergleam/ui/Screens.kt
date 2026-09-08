package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.rank.Scored
import kotlin.math.roundToInt

val COMMON_CATEGORIES = listOf(
    "cs.LG", "cs.CV", "cs.CL", "cs.AI", "cs.RO", "cs.CR", "cs.SE", "cs.IR",
    "stat.ML", "eess.IV", "eess.AS", "q-bio.NC", "math.OC", "astro-ph.GA",
)

@Composable
fun OnboardingScreen(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onImport: () -> Unit,
    importProgress: si.jakobkreft.aftergleam.data.LibraryImport.Progress?,
    importSummary: String?,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Aftergleam", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick the categories you follow. A ranked digest each day, tuned by how you " +
                "rate papers. Nothing you read leaves this device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COMMON_CATEGORIES.forEach { cat ->
                FilterChip(cat in selected, { onToggle(cat) }, { Text(cat) })
            }
        }
        Spacer(Modifier.height(24.dp))
        // Optional but prominent: importing a reading list is the difference between a feed
        // that is useful today and one that takes three weeks to become useful.
        Text("Already have a library?", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "A BibTeX or RIS export seeds the model with papers you actually chose to read.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (importProgress != null) {
            Text(
                "Resolving ${importProgress.done} of ${importProgress.total}, " +
                    "matched ${importProgress.matched}",
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            androidx.compose.material3.OutlinedButton(onClick = onImport) {
                Text("Import a .bib or .ris file")
            }
        }
        importSummary?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, enabled = selected.isNotEmpty()) {
            Text(if (selected.isEmpty()) "Pick at least one" else "Show me today")
        }
    }
}

@Composable
fun FeedScreen(
    state: FeedState,
    onRate: (String, Float?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRerank: () -> Unit,
    onRefresh: () -> Unit,
    onDismissResurfaced: (Boolean) -> Unit = {},
) {
    when {
        state.loading -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(state.loadingLabel, style = MaterialTheme.typography.bodySmall)
        }

        state.error != null ->
            Message("Could not reach arXiv", state.error, "Try again", onRefresh)

        // An empty feed is normal, not a failure: arXiv does not announce at weekends or
        // on US holidays, so say that rather than showing an error.
        state.emptyDay -> Message(
            "Nothing new today",
            "arXiv does not announce at weekends or on US holidays. Your next digest " +
                "will pick up where this leaves off.",
            "Check again",
            onRefresh,
        )

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        "Today, ${state.cards.size} papers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.modelActive)
                            "Ranking from ${state.ratedCount} rated papers"
                        else
                            "Rate ${3 - state.ratedCount} more to switch ranking on",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            state.resurfaced?.let { r ->
                item {
                    ResurfacedCard(
                        resurfaced = r,
                        onOpen = onOpen,
                        onInterested = { onRate(r.paper.id, Reaction.LIKED); onDismissResurfaced(false) },
                        onDismiss = { onDismissResurfaced(true) },
                    )
                }
            }
            items(state.cards, key = { it.paper.id }) { card ->
                PaperCard(
                    card = card,
                    reaction = state.reactions[card.paper.id] ?: Reaction.NONE,
                    modelActive = state.modelActive,
                    upvotes = state.attention[card.paper.id] ?: 0,
                    onRate = onRate,
                    onSave = onSave,
                    onOpen = onOpen,
                )
            }
            item { EndCard(state, onRerank, onRefresh) }
        }
    }
}

/**
 * The interest control.
 *
 * The slider starts where the model predicted, so the gesture is "correct the machine"
 * rather than "fill in a form". Reading the bar and then dragging it is the whole
 * legibility argument made concrete: the user can see what the model believes and
 * overrule it in one motion. The two buttons are shortcuts to 0.9 and 0.1, because most
 * reactions really are binary and dragging every time would be tiresome.
 */
@Composable
private fun InterestControl(
    confidence: Float,
    reaction: Reaction,
    modelActive: Boolean,
    onRate: (Float?) -> Unit,
) {
    val shown = reaction.interest ?: confidence
    val userSet = reaction.rated

    Column {
        Text(
            if (userSet) "your interest ${(shown * 100).roundToInt()}%"
            else if (modelActive) "model predicts ${(confidence * 100).roundToInt()}%"
            else "not ranked yet, rate to teach it",
            style = MaterialTheme.typography.labelSmall,
            color = if (userSet) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The track must stay visible when the value is only a prediction, otherwise the
        // slider renders as a bare thumb floating on the left and reads as a glitch.
        Slider(
            value = shown.coerceIn(0f, 1f),
            onValueChange = { onRate(it) },
            modifier = Modifier.height(24.dp),
            colors = if (userSet) {
                SliderDefaults.colors()
            } else {
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.outline,
                    activeTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            },
        )
    }
}

/**
 * The Resurfacer. At most one per digest, framed as discovery rather than failure, and
 * always dismissible. "Still not interested" is itself a strong training signal, so
 * dismissing it teaches the model rather than just hiding the card.
 */
@Composable
private fun ResurfacedCard(
    resurfaced: si.jakobkreft.aftergleam.data.Resurfaced,
    onOpen: (String) -> Unit,
    onInterested: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                resurfaced.headline(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                resurfaced.paper.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onOpen(resurfaced.paper.absUrl) },
            )
            Spacer(Modifier.height(4.dp))
            Text(resurfaced.detail(), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                CompactAction("Interested after all") { onInterested() }
                CompactAction("Still not for me") { onDismiss() }
            }
        }
    }
}

@Composable
private fun CompactAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun PaperCard(
    card: Scored,
    reaction: Reaction,
    modelActive: Boolean,
    upvotes: Int,
    onRate: (String, Float?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val p = card.paper
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                p.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onOpen(p.absUrl) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                p.authors.take(3).joinToString(", ") +
                    if (p.authors.size > 3) " +${p.authors.size - 3}" else "",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                p.abstract.take(200).let { if (p.abstract.length > 200) "$it..." else it },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            Text(
                card.why(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Venue.of(p)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (upvotes > 0) {
                Text(
                    si.jakobkreft.aftergleam.data.Attention.label(upvotes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(4.dp))

            InterestControl(card.relevance, reaction, modelActive) { onRate(p.id, it) }

            // A single compact row: with a longer digest, two rows of tall buttons per
            // card turned the list into mostly chrome.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactAction("Not for me") { onRate(p.id, Reaction.DISLIKED) }
                CompactAction("Interested") { onRate(p.id, Reaction.LIKED) }
                CompactAction(if (reaction.saved) "Saved" else "Save") { onSave(p.id) }
                CompactAction(if (reaction.rated) "Clear" else "Open") {
                    if (reaction.rated) onRate(p.id, null) else onOpen(p.absUrl)
                }
            }
        }
    }
}

@Composable
private fun EndCard(state: FeedState, onRerank: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text("That is today", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                state.ratedCount == 0 -> "Rate a few papers and the next digest is chosen for you."
                !state.modelActive -> "${state.ratedCount} rated. Three is where ranking switches on."
                else -> "${state.ratedCount} rated. Ranking is using them."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        // Re-ranking is local and instant. Fetching is separate and rate limited, because
        // arXiv announces once a weekday and a second fetch returns the same papers.
        TextButton(onClick = onRerank) { Text("Re-rank with my ratings") }
        TextButton(onClick = onRefresh) { Text("Check arXiv for new papers") }
    }
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}
