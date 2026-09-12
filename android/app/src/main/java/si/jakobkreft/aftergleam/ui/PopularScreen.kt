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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue

/**
 * What the field is reading, with the model switched off entirely.
 *
 * Ordered by attention and venue alone, so it says the same thing to every reader. Mixing
 * this into a personalised feed would make it noise; kept apart it is a straight answer to a
 * question people genuinely ask, and it is honest about where the numbers come from.
 */
@Composable
fun PopularScreen(
    state: FeedState,
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
    onBrowseSubjects: () -> Unit,
) {
    if (state.popular.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            when (state.popularStatus) {
                PopularStatus.NO_SIGNAL -> {
                    Text(
                        "No ranking for your subjects",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This page ranks by upvotes on the Hugging Face daily list and by " +
                            "conference acceptances. Both cover arXiv, and the daily list " +
                            "leans heavily towards machine learning. Nothing in your " +
                            "subjects is measured by either, so this will stay empty rather " +
                            "than fill in later.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Deliberately not a fallback to the list itself. Showing a lawyer the
                    // day's most upvoted machine learning papers would fill the screen with
                    // the one thing this app exists to stop doing.
                    Text(
                        "Your digest is unaffected: it ranks on what you read, not on what " +
                            "is popular.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onBrowseSubjects) { Text("Choose more subjects") }
                }

                PopularStatus.LOADING, PopularStatus.NO_PAPERS -> {
                    Text("Nothing to show yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This fills in once a digest has been fetched. It ranks by upvotes " +
                            "on the Hugging Face daily list and by conference acceptances, " +
                            "with no personalisation at all.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                PopularStatus.READY -> Unit
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "What others are reading. Not personalised, and the same for everyone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.popular, key = { it.id }) { p ->
            val reaction = state.reactions[p.id] ?: si.jakobkreft.aftergleam.data.Reaction.NONE
            PaperCard(
                paper = p,
                // No reason line: this surface is explicitly not personalised, and inventing
                // one would undercut the only thing it promises.
                reason = null,
                slot = null,
                liked = state.likedFlag(p.id),
                saved = reaction.saved,
                viewed = reaction.viewed,
                upvotes = state.attention[p.id] ?: 0,
                onSteer = { onSteer(p.id, it) },
                onSave = { onSave(p.id) },
                onOpen = { onOpen(p) },
            )
        }
    }
}
