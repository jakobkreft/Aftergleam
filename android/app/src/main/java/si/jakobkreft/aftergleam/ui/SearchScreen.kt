package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue

/**
 * Keyword search with personalised re-ranking.
 *
 * The wording is deliberately literal. This is arXiv's keyword index plus local reordering,
 * not semantic search over the whole archive, and saying otherwise would be found out on the
 * second query.
 */
@Composable
fun SearchScreen(
    state: FeedState,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onPersonalisation: (Float) -> Unit,
    onOpen: (Paper) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // A visible button as well as the keyboard action. Relying on the IME alone leaves
        // the feature unreachable whenever the keyboard does not offer a search key.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Search arXiv") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSubmit, enabled = state.searchQuery.trim().length >= 2) {
                Text("Search")
            }
        }
        Text(
            "Keyword search on arXiv, reordered on this device by what you read.",
            style = MaterialTheme.typography.labelSmall,
        )

        if (state.searchHits.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Ordering: ${(state.personalisation * 100).toInt()}% my interests",
                style = MaterialTheme.typography.labelSmall,
            )
            // Reordering is local, so this never re-queries arXiv.
            Slider(state.personalisation, onPersonalisation, valueRange = 0f..1f)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("closest to query", style = MaterialTheme.typography.labelSmall)
                Text("closest to me", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            state.searching -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            state.searchError != null ->
                Text(state.searchError, style = MaterialTheme.typography.bodySmall)

            state.searchHits.isEmpty() && state.searchQuery.isNotBlank() ->
                Text("Nothing found. Try fewer or more common words.",
                    style = MaterialTheme.typography.bodySmall)

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.searchHits, key = { it.paper.id }) { hit ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(hit.paper) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                hit.paper.displayTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                hit.paper.authors.take(3).joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(hit.why(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Venue.of(hit.paper)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}
