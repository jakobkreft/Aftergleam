package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.PdfFind

/**
 * Find in paper, shared by the PDF and the reader view.
 *
 * The bar only holds what was typed and what was found. Whichever view is showing does the
 * finding and sets [step], so the two read the same and behave the same: the count, the
 * arrows, the keyboard's search key going to the next match.
 */
@Stable
class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    /** Index of the match being shown, or -1 before there is one. */
    var current by mutableIntStateOf(-1)
    var total by mutableIntStateOf(0)
    /** More matches than are counted, see [PdfFind.MAX_MATCHES]. */
    var capped by mutableStateOf(false)
    var searching by mutableStateOf(false)
    /** Something to say under the bar, such as a PDF that has no text. */
    var note by mutableStateOf<String?>(null)
    /**
     * Bumped on every step, so that stepping to the match already current, when there is only
     * one, still brings it back on screen after the reader has scrolled away.
     */
    var jumps by mutableIntStateOf(0)

    /** Moves to the next match, or the previous one. Set by the view that is showing. */
    var step: (forward: Boolean) -> Unit = {}

    fun clearResults() {
        current = -1; total = 0; capped = false; searching = false; note = null
    }

    fun close() {
        open = false
        query = ""
        clearResults()
    }
}

@Composable
fun FindBar(state: FindState, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.close() }) {
                Icon(Icons.Filled.Close, contentDescription = "Close find")
            }
            TextField(
                value = state.query,
                onValueChange = { state.query = it },
                placeholder = { Text("Find in paper") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // Out of the way, so the match it moves to is not behind the keyboard.
                    keyboard?.hide()
                    state.step(true)
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (state.query.isNotBlank()) {
                Text(
                    PdfFind.count(state.current, state.total, state.capped, state.searching),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { keyboard?.hide(); state.step(false) },
                enabled = state.total > 0,
            ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match") }
            IconButton(
                onClick = { keyboard?.hide(); state.step(true) },
                enabled = state.total > 0,
            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match") }
        }
        state.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}
