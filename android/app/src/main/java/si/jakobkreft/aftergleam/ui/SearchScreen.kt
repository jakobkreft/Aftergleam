package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onScope: (SearchScope) -> Unit,
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
    onAddKeyword: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // After a reorder the best result is at the top, and leaving the reader parked halfway
    // down the old ranking hides the very change they asked for.
    LaunchedEffect(state.searchHits.firstOrNull()?.paper?.id) {
        if (state.searchHits.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismiss = {
        keyboard?.hide()
        focus.clearFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            // Tapping anywhere off the field puts the keyboard away, which is what every
            // other app on the phone does.
            .pointerInput(Unit) { detectTapGestures(onTap = { dismiss() }) }
            .padding(16.dp)
    ) {
        // A visible button as well as the keyboard action. Relying on the IME alone leaves
        // the feature unreachable whenever the keyboard does not offer a search key.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                // Not "Search arXiv": the scope chips below say where, and two of the three
                // do not touch arXiv at all.
                label = { Text("Search anything") },
                singleLine = true,
                // The last query is still there when search reopens, which is usually what
                // you want and occasionally the opposite. Without this the only way out is
                // holding backspace.
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear the query")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { dismiss(); onSubmit() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { dismiss(); onSubmit() },
                enabled = state.searchQuery.trim().length >= 2,
            ) {
                Text("Search")
            }
        }
        Spacer(Modifier.height(8.dp))
        // Where to look. "Which paper did I save last week" is a different and more common
        // question than "what exists on arXiv", it needs no network, and sending it to arXiv
        // would usually fail to find the very paper the reader meant.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchScope.entries.forEach { scope ->
                androidx.compose.material3.FilterChip(
                    selected = state.searchScope == scope,
                    onClick = { onScope(scope) },
                    label = { Text(scope.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (state.searchScope) {
                SearchScope.ONLINE ->
                    "Keyword search on arXiv and seven other preprint servers, reordered " +
                        "here by what you read."
                SearchScope.CACHED -> "Everything this device has downloaded. No network."
                SearchScope.KEPT -> "Only what you saved or reacted to. No network."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.searchHits.isNotEmpty() || state.searchLocalHits.isNotEmpty()) {
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
            // A search that found what the reader wanted can go on finding it every morning.
            // Offered only for an online search, which is the one that looks at new papers.
            val query = si.jakobkreft.aftergleam.data.Keywords.normalise(state.searchQuery)
            if (state.searchScope == SearchScope.ONLINE && query != null && !state.searching) {
                val added = state.keywords.any { it.equals(query, ignoreCase = true) }
                val full = state.keywords.size >= si.jakobkreft.aftergleam.data.Keywords.MAX
                TextButton(onClick = { onAddKeyword(query) }, enabled = !added && !full) {
                    Text(
                        when {
                            added -> "Watching for \u201c$query\u201d"
                            full -> "Your keyword list is full"
                            else -> "Watch for \u201c$query\u201d in new papers"
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val nothingAtAll = state.searchLocalHits.isEmpty() && state.searchHits.isEmpty()

        when {
            // Only a bare wait when there is genuinely nothing to show yet. Once the device
            // results are up, the wait becomes a line at the bottom of them instead: a
            // skeleton over the top of real results would hide the thing that just arrived.
            state.searching && nothingAtAll -> DigestSkeleton(
                when (state.searchScope) {
                    SearchScope.ONLINE -> "Asking the preprint servers, then ranking for you"
                    else -> "Searching this device"
                }
            )

            state.searchError != null && nothingAtAll ->
                Text(state.searchError, style = MaterialTheme.typography.bodySmall)

            nothingAtAll && state.searchQuery.isNotBlank() && !state.searching ->
                Text("Nothing found. Try fewer or more common words.",
                    style = MaterialTheme.typography.bodySmall)

            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.searchLocalHits.isNotEmpty()) {
                    item(key = "device-header") {
                        SectionLabel("Already on your device")
                    }
                    items(state.searchLocalHits, key = { "d-" + it.paper.id }) { hit ->
                        Result(state, hit, onSteer, onSave, onOpen, fromDevice = true)
                    }
                    if (state.searchLocalMore > 0) {
                        item(key = "device-more") {
                            Text(
                                "${state.searchLocalMore} more on this device. " +
                                    "The On device chip shows them all.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "arxiv-header") {
                        Spacer(Modifier.height(4.dp))
                        SectionLabel(
                            when {
                                state.searching -> "Asking the preprint servers for the rest"
                                state.searchError != null -> "From the preprint servers"
                                else -> "New to you, from the preprint servers"
                            }
                        )
                    }
                }

                // The network's own report, under the results rather than instead of them.
                state.searchError?.let { err ->
                    item(key = "error") {
                        Text(err, style = MaterialTheme.typography.bodySmall)
                    }
                }

                items(state.searchHits, key = { it.paper.id }) { hit ->
                    Result(state, hit, onSteer, onSave, onOpen)
                }

                if (state.searching && state.searchLocalHits.isNotEmpty()) {
                    item(key = "pending") { PendingRow() }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One skeleton card, so the arXiv half looks like it is coming rather than missing. */
@Composable
private fun PendingRow() {
    Column {
        Spacer(Modifier.height(4.dp))
        SkeletonCard()
    }
}

/**
 * What to say about a paper the reader already has.
 *
 * Not [SearchRanker.Hit.why], which describes a predicted interest. These results are ranked
 * by the query alone so that they can appear instantly, and reporting "outside your usual
 * reading" for a paper the reader saved last week would be inventing a judgement out of a
 * score nobody computed. What is worth saying here is what they already did with it.
 */
private fun deviceReason(state: FeedState, id: String): String? =
    when {
        state.likedFlag(id) == true -> "you asked for more like this"
        state.likedFlag(id) == false -> "you said less like this"
        state.reactions[id]?.saved == true -> "saved for later"
        state.reactions[id]?.viewed == true -> "you opened this before"
        else -> null
    }

@Composable
private fun Result(
    state: FeedState,
    hit: si.jakobkreft.aftergleam.rank.SearchRanker.Hit,
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (si.jakobkreft.aftergleam.data.Paper) -> Unit,
    fromDevice: Boolean = false,
) {
    val reaction = state.reactions[hit.paper.id]
        ?: si.jakobkreft.aftergleam.data.Reaction.NONE
    PaperCard(
        paper = hit.paper,
        reason = if (fromDevice) deviceReason(state, hit.paper.id) else hit.why(),
        slot = null,
        liked = state.likedFlag(hit.paper.id),
        saved = reaction.saved,
        viewed = reaction.viewed,
        upvotes = state.attention[hit.paper.id] ?: 0,
        onSteer = { onSteer(hit.paper.id, it) },
        onSave = { onSave(hit.paper.id) },
        onOpen = { onOpen(hit.paper) },
    )
}
