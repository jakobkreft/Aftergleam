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
fun PopularScreen(state: FeedState, onOpen: (Paper) -> Unit) {
    if (state.popular.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Nothing to show yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "This fills in once a digest has been fetched. It ranks by upvotes on the " +
                    "Hugging Face daily list and by conference acceptances, with no " +
                    "personalisation at all.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                // The bar already names the surface; repeating it here just costs a line.
                Text(
                    "What others are reading. Not personalised, and the same for everyone.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        items(state.popular, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(p) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.displayTitle, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val upvotes = state.attention[p.id] ?: 0
                    Text(
                        listOfNotNull(
                            if (upvotes > 0) Attention.label(upvotes) else null,
                            Venue.of(p),
                            p.primaryCategory,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
