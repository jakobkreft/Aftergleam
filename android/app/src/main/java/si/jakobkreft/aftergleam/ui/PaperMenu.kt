package si.jakobkreft.aftergleam.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import si.jakobkreft.aftergleam.data.Paper

/**
 * What a reader can do to a paper without opening it: keep it, take it offline, pass it on.
 *
 * Built from what is true of the paper, not from which shelf it is sitting on. A saved paper
 * may also be downloaded, so a menu written per shelf would have to either omit real options
 * or offer ones that do not apply.
 *
 * Deliberately three items. Steering the model is not on this list: that is a judgement about
 * a paper, it belongs with the paper's own words, and it already has a pair of buttons on the
 * card and on the detail screen. Putting it here as well would make a management menu the
 * fourth place to do it and the only place doing it blind.
 *
 * Downloading is here because it was otherwise only possible by opening a paper and waiting
 * for the reader to render it, which is a poor way to prepare for a flight.
 */
@Composable
fun PaperMenu(
    paper: Paper,
    saved: Boolean,
    downloaded: Boolean,
    downloading: Boolean,
    onSave: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onShare: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val close = { open = false }

        Item(if (saved) "Remove from saved" else "Save for later", close, action = onSave)

        when {
            downloading -> Item("Downloading…", close, enabled = false) {}
            downloaded -> Item("Delete download", close, action = onDeleteDownload)
            else -> Item("Download for offline", close, action = onDownload)
        }

        Item("Share", close, action = onShare)
    }
}

@Composable
private fun Item(
    label: String,
    close: () -> Unit,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        onClick = { close(); action() },
    )
}
