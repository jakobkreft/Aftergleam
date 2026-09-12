package si.jakobkreft.aftergleam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.PdfStore
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.Venue
import kotlin.math.roundToInt

/**
 * One paper, in full.
 *
 * Cards are necessarily lossy: three lines of abstract is enough to triage but not to
 * decide. This screen is where the decision happens, so the abstract is complete, the rating
 * control is the same one as on the card, and the PDF opens below rather than throwing the
 * user into a browser and losing their place in the digest.
 */
@Composable
fun DetailScreen(
    paper: Paper,
    reaction: Reaction,
    liked: Boolean?,
    confidence: Float,
    modelActive: Boolean,
    upvotes: Int,
    onSteer: (Boolean?) -> Unit,
    onSave: () -> Unit,
    onOpenExternal: (String) -> Unit,
    onRead: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { PdfStore(context) }
    // Already downloaded means already wanted: asking a second time for a file that is
    // sitting on disk is a button whose only function is to be pressed.
    //
    // rememberSaveable, not remember: a rotation recreates the activity, and plain remember
    // would close the PDF the reader had open.
    var showPdf by rememberSaveable(paper.id) { mutableStateOf(store.isCached(paper.id)) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // Just "Back". This screen is reached from the digest, Explore, Popular, the
            // library, a search and an earlier digest, and it said "Back to digest" from
            // all six.
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                paper.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = LocalPaperFont.current,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(paper.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    paper.sourceLabel,
                    paper.id,
                    paper.displayCategories.joinToString(" "),
                    "submitted ${paper.published}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
            )
            Venue.of(paper)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (upvotes > 0) {
                Text(
                    si.jakobkreft.aftergleam.data.Attention.label(upvotes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (paper.comments.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(paper.comments, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // The whole abstract, not a preview. This is the point of the screen.
            Text(
                paper.displayAbstract,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = LocalPaperFont.current,
            )
            Spacer(Modifier.height(16.dp))

            InterestControl(confidence, liked, modelActive, onSteer)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onSave) {
                    Text(if (reaction.saved) "Saved" else "Save for later")
                }
                OutlinedButton(onClick = { onOpenExternal(paper.absUrl) }) {
                    Text("On " + Source.label(paper.source))
                }
                OutlinedButton(onClick = onShare) { Text("Share") }
            }
            Spacer(Modifier.height(12.dp))

            val cached = store.isCached(paper.id)
            Button(
                onClick = { if (paper.readableInApp) onRead() else onOpenExternal(paper.absUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        !paper.readableInApp -> "Read on " + Source.label(paper.source)
                        cached -> "Read"
                        else -> "Download and read"
                    }
                )
            }
            if (!cached && paper.readableInApp) {
                Text(
                    "Downloads once and stays available offline.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
