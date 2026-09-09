package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import si.jakobkreft.aftergleam.rank.Slot
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.rank.Scored

val COMMON_CATEGORIES = listOf(
    "cs.LG", "cs.CV", "cs.CL", "cs.AI", "cs.RO", "cs.CR", "cs.SE", "cs.IR",
    "stat.ML", "eess.IV", "eess.AS", "q-bio.NC", "math.OC", "astro-ph.GA",
)

@Composable
fun FeedScreen(
    state: FeedState,
    onRate: (String, Float?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
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

        // Pull to refresh, because every other feed on the phone has it and its absence
        // reads as the screen being stuck.
        else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
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
                        onOpen = { onOpen(r.paper) },
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
            state.drift?.let { item { DriftCard(it) } }
        }
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
fun InterestControl(
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
    onOpen: () -> Unit,
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
                resurfaced.paper.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onOpen() },
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

/**
 * A digest card.
 *
 * Deliberately light. The earlier version put the whole rating control, four text actions
 * and two metadata lines on every card, so a screen held one and a half papers and the list
 * read as a wall of controls. Triage needs a title, a hint of the content, why it is here
 * and one gesture; everything else belongs on the paper's own screen, one tap away.
 */
@Composable
private fun PaperCard(
    card: Scored,
    reaction: Reaction,
    modelActive: Boolean,
    upvotes: Int,
    onRate: (String, Float?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
) {
    val p = card.paper
    val rated = reaction.rated
    // Viewed cards recede rather than disappear. The digest is a fixed set and removing
    // rows from under the reader would lose their place; dimming says "you have been here"
    // without moving anything.
    val seen = reaction.viewed
    Card(
        Modifier.fillMaxWidth().clickable { onOpen(p) },
        elevation = CardDefaults.cardElevation(defaultElevation = if (seen) 0.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (seen) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // Why the card is here comes first: it is the thing that makes the list
            // legible, and it is cheap to skim.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SlotDot(card.slot)
                Spacer(Modifier.width(6.dp))
                Text(
                    card.why(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))

            Text(
                p.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (seen) FontWeight.Normal else FontWeight.SemiBold,
                color = if (seen) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                p.displayAbstract,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (seen) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Opened",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp).padding(end = 2.dp),
                    )
                }
                MetaChip(p.shortAuthors)
                Venue.of(p)?.let { MetaChip(it, MaterialTheme.colorScheme.tertiary) }
                if (upvotes > 0) MetaChip("$upvotes read", MaterialTheme.colorScheme.secondary)
                if (modelActive) MetaChip("${(card.relevance * 100).roundToInt()}%")

                Spacer(Modifier.weight(1f))

                // Icons, because these three are universal and a word each would crowd out
                // the paper. The full slider lives on the detail screen.
                IconToggle(
                    icon = Icons.Filled.Clear,
                    active = rated && reaction.interest!! < 0.5f,
                    description = "Not for me",
                ) { onRate(p.id, if (rated && reaction.interest!! < 0.5f) null else Reaction.DISLIKED) }
                IconToggle(
                    icon = Icons.Filled.Favorite,
                    active = rated && reaction.interest!! >= 0.5f,
                    description = "Interested",
                ) { onRate(p.id, if (rated && reaction.interest!! >= 0.5f) null else Reaction.LIKED) }
                IconToggle(
                    icon = Icons.Filled.Star,
                    active = reaction.saved,
                    description = "Save for later",
                ) { onSave(p.id) }
            }
        }
    }
}

/** A small colour cue for which slot a card came from, paired with the text reason. */
@Composable
private fun SlotDot(slot: Slot) {
    val colour = when (slot) {
        Slot.RELEVANCE -> MaterialTheme.colorScheme.primary
        Slot.EXPLORATION -> MaterialTheme.colorScheme.secondary
        Slot.BRIDGE -> MaterialTheme.colorScheme.tertiary
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
}

@Composable
private fun MetaChip(text: String, colour: Color = Color.Unspecified) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (colour == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = 10.dp),
    )
}

@Composable
private fun IconToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
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

/**
 * The weekly read on where the user's attention has moved, and where the model missed.
 *
 * Deliberately placed after the end card. It is a reflection on the week, not another thing
 * to get through, and it should not compete with the papers for attention.
 */
@Composable
private fun DriftCard(report: si.jakobkreft.aftergleam.data.Drift.Report) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Your reading, lately", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (report.thin) {
                // Saying "not yet" is better than inventing a trend from four papers.
                Text(
                    "Not enough history yet. After a couple of weeks of rating, this is " +
                        "where the shift in what you read shows up.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                if (report.rising.isNotEmpty()) {
                    Text("More of: " + report.rising.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (report.falling.isNotEmpty()) {
                    Text("Less of: " + report.falling.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (report.recurringAuthors.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Authors you keep coming back to: " +
                        report.recurringAuthors.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            report.explorationNote()?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
