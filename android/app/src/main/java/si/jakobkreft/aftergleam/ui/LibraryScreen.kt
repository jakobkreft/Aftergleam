package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue
import kotlin.math.roundToInt

private enum class Shelf(val label: String) {
    SAVED("Saved"), DOWNLOADED("Offline"), RATED("Reacted to")
}

/**
 * Everything the reader has accumulated, in one place.
 *
 * The Rated shelf exists because a model trained on ratings the user cannot see or change is
 * not tunable, only obeyed. Every rating here can be moved or removed, and the model picks
 * that up on the next re-rank.
 */
@Composable
fun LibraryScreen(
    saved: List<Paper>,
    downloaded: List<Paper>,
    rated: List<Pair<Paper, Float>>,
    likedFlag: (String) -> Boolean?,
    onOpen: (Paper) -> Unit,
    onUnsave: (String) -> Unit,
    onSteer: (String, Boolean?) -> Unit,
) {
    var shelf by rememberSaveable { mutableStateOf(Shelf.SAVED) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Shelf.entries.forEach { s ->
                val count = when (s) {
                    Shelf.SAVED -> saved.size
                    Shelf.DOWNLOADED -> downloaded.size
                    Shelf.RATED -> rated.size
                }
                FilterChip(
                    selected = shelf == s,
                    onClick = { shelf = s },
                    label = { Text("${s.label} $count") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (shelf) {
            Shelf.SAVED -> Shelf(
                papers = saved,
                empty = "Nothing saved yet. Saving is separate from rating: rate a paper to " +
                    "teach the model, save it to come back to it.",
                onOpen = onOpen,
            ) { p ->
                IconButton(onClick = { onUnsave(p.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove from saved")
                }
            }

            Shelf.DOWNLOADED -> Shelf(
                papers = downloaded,
                empty = "No PDFs downloaded. Open a paper and read it once, and it stays " +
                    "here for trains and planes.",
                onOpen = onOpen,
            )

            // The same two chips as everywhere else. This shelf used to carry a slider per
            // paper, left over from the rating concept that was removed; a control that
            // exists nowhere else is worse than no control.
            Shelf.RATED -> Shelf(
                papers = rated.map { it.first },
                empty = "Nothing yet. React to papers in the digest, or import a library, " +
                    "and they all show up here where you can change your mind.",
                onOpen = onOpen,
            ) { p ->
                Row {
                    androidx.compose.material3.FilterChip(
                        selected = likedFlag(p.id) == false,
                        onClick = { onSteer(p.id, if (likedFlag(p.id) == false) null else false) },
                        label = { Text("Less") },
                    )
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.FilterChip(
                        selected = likedFlag(p.id) == true,
                        onClick = { onSteer(p.id, if (likedFlag(p.id) == true) null else true) },
                        label = { Text("More") },
                    )
                }
            }
        }
    }
}

@Composable
private fun Shelf(
    papers: List<Paper>,
    empty: String,
    onOpen: (Paper) -> Unit,
    trailing: @Composable ((Paper) -> Unit)? = null,
) {
    if (papers.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(papers, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(p) }) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(p.published, Venue.of(p)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    trailing?.invoke(p)
                }
            }
        }
    }
}
