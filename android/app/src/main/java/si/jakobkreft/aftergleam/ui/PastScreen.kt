package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Reaction
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * What came before today.
 *
 * Two different needs, and it took looking at the data to see they are different. Replaying
 * a past digest answers "where was that paper on Tuesday", and is a faithful record: the
 * cards come back in the order they were shown, carrying the reasons they carried, because
 * quietly re-ranking them with today's model would make the record worthless.
 *
 * Catching up is the other one, and it is the reason this screen exists. The daily worker
 * keeps fetching while the app is closed but never composes a digest, so a week away leaves
 * several hundred papers on the device that no digest ever selected. Listing them would be
 * handing someone a second job; they are ranked instead, and the best twenty-five shown,
 * which is a morning's reading rather than a backlog.
 */
@Composable
fun PastScreen(
    state: FeedState,
    onOpenDay: (String) -> Unit,
    onOpenCatchUp: () -> Unit,
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
    onBackToIndex: () -> Unit,
    onBack: () -> Unit,
) {
    when (state.pastDay) {
        null -> Index(state, onOpenDay, onOpenCatchUp, onBack)
        else -> DayList(state, onSteer, onSave, onOpen, onBackToIndex)
    }
}

@Composable
private fun Index(
    state: FeedState,
    onOpenDay: (String) -> Unit,
    onOpenCatchUp: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to today") }
            Text("Earlier", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
        }

        if (state.missedCount > 0 && state.missedSince != null) {
            item {
                Spacer(Modifier.height(4.dp))
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenCatchUp),
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
                            "${state.missedCount} papers announced since " +
                                "${dayName(state.missedSince)} that no digest has shown you. " +
                                "Tap for the best of them.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.pastDays.size <= 1) "No earlier digests yet. They collect here as " +
                    "you use the app."
                else "Digests you were shown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(state.pastDays.filter { it.day != LocalDate.now().toString() },
            key = { it.day }) { d ->
            Card(
                Modifier.fillMaxWidth().clickable { onOpenDay(d.day) },
                elevation = flatCard(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(dayName(d.day), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append("${d.papers} " + if (d.papers == 1) "paper" else "papers")
                            if (d.reacted > 0) append(" · you reacted to ${d.reacted}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DayList(
    state: FeedState,
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
    onBackToIndex: () -> Unit,
) {
    val catchUp = state.pastDay == FeedViewModel.CATCH_UP
    val cards = if (catchUp) state.catchUp else state.pastCards

    if (catchUp && state.catchUpLoading && cards.isEmpty()) {
        DigestSkeleton("Ranking what you missed")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBackToIndex) { Text("Back to earlier") }
            Text(
                if (catchUp) "While you were away" else dayName(state.pastDay ?: ""),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (catchUp)
                    "The best ${cards.size} of the ${state.missedCount} you have not seen."
                else "Exactly as it was shown that morning.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(cards, key = { it.paper.id }) { card ->
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
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * "Monday 8 September" rather than "2026-09-08".
 *
 * A date the reader has to decode is a date they will not use, and the weekday is the part
 * they actually remember about which morning they missed.
 */
private fun dayName(day: String): String = runCatching {
    val d = LocalDate.parse(day)
    val weekday = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val month = d.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    when (d) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> "$weekday ${d.dayOfMonth} $month"
    }
}.getOrDefault(day)
