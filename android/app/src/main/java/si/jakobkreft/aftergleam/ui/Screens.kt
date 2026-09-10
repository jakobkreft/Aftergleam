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
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
    onRerank: () -> Unit,
    onRefresh: () -> Unit,
    onPast: () -> Unit,
    onDismissResurfaced: (Boolean) -> Unit = {},
) {
    when {
        state.loading -> DigestSkeleton(state.loadingLabel.ifBlank { "Working" })

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
                            "Learned from ${state.ratedCount} papers you have read or reacted to"
                        else
                            "React to ${3 - state.ratedCount} more to switch ranking on",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Above the papers, because the reader who was away needs to know before they
            // start reading today that there is a "before today". Only when there is a real
            // gap: someone who opens the app every morning never sees it.
            if (state.missedCount > 0 && state.missedSince != null) {
                item { AwayCard(state.missedCount, onPast) }
            }
            state.resurfaced?.let { r ->
                item {
                    ResurfacedCard(
                        resurfaced = r,
                        onOpen = { onOpen(r.paper) },
                        onInterested = { onSteer(r.paper.id, true); onDismissResurfaced(false) },
                        onDismiss = { onDismissResurfaced(true) },
                    )
                }
            }
            items(state.cards, key = { it.paper.id }) { card ->
                val reaction = state.reactions[card.paper.id] ?: Reaction.NONE
                PaperCard(
                    paper = card.paper,
                    reason = card.why(),
                    slot = card.slot,
                    liked = state.likedFlag(card.paper.id),
                    saved = reaction.saved,
                    viewed = reaction.viewed,
                    upvotes = state.attention[card.paper.id] ?: 0,
                    onSteer = { onSteer(card.paper.id, it) },
                    onSave = { onSave(card.paper.id) },
                    onOpen = { onOpen(card.paper) },
                )
            }
            item { EndCard(state, onRerank, onRefresh, onPast) }
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
/**
 * Two buttons instead of a slider.
 *
 * The slider asked for a calibrated number in exchange for a vague feeling. One reader's
 * "quite interested" was 95 and another's 55, the model's own prediction sat beside it and
 * anchored the answer, and reading a paper properly gives you more reasons to fault it, so
 * the more attention a paper got the worse it scored. A steering instruction has none of
 * those problems: it means the same thing coming from anyone.
 *
 * Everything between the two buttons is inferred from what the reader does, which costs them
 * nothing and is harder to misreport.
 */
@Composable
fun InterestControl(
    confidence: Float,
    liked: Boolean?,
    modelActive: Boolean,
    onSteer: (Boolean?) -> Unit,
) {
    Column {
        if (modelActive) {
            Text(
                when (liked) {
                    true -> "You asked for more like this"
                    false -> "You asked for less like this"
                    // No percentage. It was taken off the cards for looking precise when it
                    // is not, and it was no more honest here.
                    null -> "Ranked for you. Tell it if that is wrong."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (liked != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(
                selected = liked == false,
                onClick = { onSteer(if (liked == false) null else false) },
                label = { Text("Less like this") },
            )
            androidx.compose.material3.FilterChip(
                selected = liked == true,
                onClick = { onSteer(if (liked == true) null else true) },
                label = { Text("More like this") },
            )
        }
    }
}

@Composable
private fun ResurfacedCard(
    resurfaced: si.jakobkreft.aftergleam.data.Resurfaced,
    onOpen: () -> Unit,
    onInterested: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        elevation = flatCard(),
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
                fontFamily = LocalPaperFont.current,
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
 * The one line a returning reader needs before anything else.
 *
 * Phrased as papers rather than days: "142 papers" is a quantity somebody can decide about,
 * where "you were away 3 days" is a fact about them that they already know.
 */
@Composable
private fun AwayCard(count: Int, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        elevation = flatCard(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("While you were away", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "$count papers you have not seen. Tap for the best of them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EndCard(
    state: FeedState,
    onRerank: () -> Unit,
    onRefresh: () -> Unit,
    onPast: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text("That is today", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                state.ratedCount == 0 ->
                    "React to a few papers and the next digest is chosen for you."
                !state.modelActive ->
                    "${state.ratedCount} so far. Three is where ranking switches on."
                else -> "Learned from ${state.ratedCount} papers, " +
                    "${state.judgedCount} of them reacted to."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        // Re-ranking is local and instant. Fetching is separate and rate limited, because
        // arXiv announces once a weekday and a second fetch returns the same papers.
        TextButton(onClick = onRerank) { Text("Shuffle with my reactions") }
        TextButton(onClick = onRefresh) { Text("Check arXiv for new papers") }
        // The end of today is where "and before today?" is a natural question, and it costs
        // no tab and no chrome to answer it here.
        TextButton(onClick = onPast) { Text("Earlier digests") }
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
        elevation = flatCard(),
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
                    "Not enough history yet. After a couple of weeks of reading, this is " +
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
