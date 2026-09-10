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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
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

/** The system's own wording for file sizes, so the app agrees with Android's storage screen. */
private fun formatSize(context: android.content.Context, bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(context, bytes)

/** What a row says about its download: nothing, a size, or that one is on its way. */
private fun downloadNote(
    context: android.content.Context,
    paper: Paper,
    sizes: Map<String, Long>,
    downloading: Set<String>,
): String? = when {
    paper.id in downloading -> "downloading…"
    else -> sizes[paper.id]?.takeIf { it > 0 }?.let { formatSize(context, it) }
}

private enum class Shelf(val label: String) {
    SAVED("Saved"), DOWNLOADED("Offline"), RATED("Reacted to")
}

/**
 * Everything the reader has accumulated, in one place.
 *
 * The reacted shelf exists because a model trained on judgements the reader cannot see or
 * change is not tunable, only obeyed. Every reaction here can be changed or cleared, and the
 * model picks that up on the next re-rank.
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
    sizes: Map<String, Long>,
    downloading: Set<String>,
    message: String?,
    onDeleteDownload: (String) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    onDownload: (Paper) -> Unit,
    onShare: (Paper) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var shelf by rememberSaveable { mutableStateOf(Shelf.SAVED) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

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
        // Downloads started from a row happen out of sight, so this is where anything
        // that went wrong with one gets said.
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismissMessage) { Text("Dismiss") }
        }
        Spacer(Modifier.height(12.dp))

        /** One menu for every row, told what is true of the paper rather than which shelf. */
        val menu: @Composable (Paper) -> Unit = { p ->
            PaperMenu(
                paper = p,
                saved = saved.any { it.id == p.id },
                downloaded = downloaded.any { it.id == p.id },
                downloading = p.id in downloading,
                onSave = { onUnsave(p.id) },
                onDownload = { onDownload(p) },
                onDeleteDownload = { onDeleteDownload(p.id) },
                onShare = { onShare(p) },
            )
        }

        when (shelf) {
            Shelf.SAVED -> Shelf(
                papers = saved,
                empty = "Nothing saved yet. Saving is separate from reacting: react to teach " +
                    "the model, save to come back to it.",
                onOpen = onOpen,
                meta = { p -> downloadNote(context, p, sizes, downloading) },
                trailing = menu,
            )

            // The only shelf that costs anything, so it is the only one that says what it
            // costs. A reader wondering why the app is taking up space is asking about
            // these files, and the answer belongs where the files are listed rather than
            // buried in settings.
            Shelf.DOWNLOADED -> Shelf(
                // Heaviest first. The order the papers arrive in is whatever SQLite
                // returned, and on a screen whose job is reclaiming space the useful
                // order is the one that puts the twenty-four megabyte paper at the top.
                papers = downloaded.sortedByDescending { sizes[it.id] ?: 0L },
                empty = "No PDFs downloaded. Open a paper and read it once, and it stays " +
                    "here for trains and planes.",
                onOpen = onOpen,
                meta = { p -> downloadNote(context, p, sizes, downloading) },
                header = {
                    Text(
                        "${downloaded.size} " +
                            (if (downloaded.size == 1) "paper" else "papers") +
                            " · " + formatSize(context, sizes.values.sum()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                footer = {
                    Spacer(Modifier.height(8.dp))
                    // Confirmed in place, because on a train these are unreplaceable until
                    // there is signal again, which is the situation they were kept for.
                    if (!confirmDeleteAll) {
                        TextButton(onClick = { confirmDeleteAll = true }) {
                            Text("Delete all downloads")
                        }
                    } else {
                        Text(
                            "This removes the files only. Saves and reactions stay, and " +
                                "any paper downloads again in a tap.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row {
                            TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
                            TextButton(onClick = {
                                confirmDeleteAll = false; onDeleteAllDownloads()
                            }) { Text("Delete ${downloaded.size}") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                },
                trailing = menu,
            )

            // The two chips, not the menu. This shelf exists to change your mind, so the
            // control for changing it is the row's own affordance rather than something
            // behind a tap: it shows which way you went and moves in one press. The other
            // three actions are a menu away on the shelves where they are the point.
            Shelf.RATED -> Shelf(
                papers = rated.map { it.first },
                empty = "Nothing yet. React to papers in the digest, or import a library, " +
                    "and they all show up here where you can change your mind.",
                onOpen = onOpen,
                meta = { p -> downloadNote(context, p, sizes, downloading) },
            ) { p ->
                Row {
                    FilterChip(
                        selected = likedFlag(p.id) == false,
                        onClick = { onSteer(p.id, if (likedFlag(p.id) == false) null else false) },
                        label = { Text("Less") },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
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
    /** An extra fact for the row's second line, such as what the download weighs. */
    meta: ((Paper) -> String?)? = null,
    header: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
    trailing: @Composable ((Paper) -> Unit)? = null,
) {
    if (papers.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header?.let { item { it() } }
        items(papers, key = { it.id }) { p ->
            // The same surface and lift as a digest card. These were left on Material's
            // default, which is a step lighter in light and a step darker in dark, so two
            // lists of the same thing sat on visibly different paper.
            Card(
                Modifier.fillMaxWidth().clickable { onOpen(p) },
                elevation = flatCard(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = LocalPaperFont.current,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(p.published, Venue.of(p), meta?.invoke(p))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    trailing?.invoke(p)
                }
            }
        }
        footer?.let { item { it() } }
    }
}
