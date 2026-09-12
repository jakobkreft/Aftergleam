package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue

/**
 * The wider feed, from everything the digest passed over.
 *
 * The digest fetches several hundred papers and shows sixty; the rest used to vanish. This is
 * where they are, drawn at a high temperature and a raised diversity weight so it leans
 * towards spread rather than the model's convictions. The digest is for what the reader
 * probably wants; this is for what they might not know they want, and unlike the digest it
 * does not claim to end.
 */
@Composable
fun ExploreScreen(
    state: FeedState,
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
    onMore: () -> Unit,
    onBrowseSubjects: () -> Unit,
) {
    // The same placeholder cards the digest uses. A bare spinner on an empty screen reads
    // as "something is wrong" rather than "something is coming", and this surface waits
    // longer than the digest does because it ranks eight hundred candidates.
    if (state.explore.isEmpty() && state.exploreLoading) {
        DigestSkeleton("Looking wider than your digest")
        return
    }

    // Explore is what did not make today's digest. In a narrow subject the digest takes
    // everything there is, and the screen used to answer that by showing its "deliberately
    // less sure of itself" header over nothing, above a More papers button that could only
    // ever return the same nothing.
    if (state.explore.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Today's digest has all of it", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "This page shows the papers your subjects produced that did not fit in the " +
                    "digest. Right now there are none left over, which means nothing is " +
                    "being kept from you.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "It fills up on its own as more papers arrive in the subjects you follow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBrowseSubjects) { Text("Choose more subjects") }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Wider than your digest and deliberately less sure of itself.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.explore, key = { it.paper.id }) { card ->
            val reaction = state.reactions[card.paper.id] ?: si.jakobkreft.aftergleam.data.Reaction.NONE
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
        item {
            Spacer(Modifier.height(8.dp))
            if (state.exploreLoading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else if (!state.exploreExhausted) {
                Button(onClick = onMore, modifier = Modifier.fillMaxWidth()) {
                    Text("More papers")
                }
            } else {
                // The end of the list, said once, rather than a button that does nothing.
                Text(
                    "That is everything outside today's digest.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
