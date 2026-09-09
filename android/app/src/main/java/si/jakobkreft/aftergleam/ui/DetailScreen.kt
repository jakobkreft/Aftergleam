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
import androidx.compose.runtime.remember
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
    confidence: Float,
    modelActive: Boolean,
    upvotes: Int,
    onRate: (Float?) -> Unit,
    onSave: () -> Unit,
    onOpenExternal: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { PdfStore(context) }
    // rememberSaveable, not remember: a rotation recreates the activity and plain remember
    // would close the PDF the reader had open. The file itself is already cached on disk,
    // so only this flag was standing between them and their place in the paper.
    var showPdf by rememberSaveable(paper.id) { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to digest") }
            Text(
                paper.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(paper.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${paper.id} · ${paper.categories.joinToString(" ")} · submitted ${paper.published}",
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
            Text(paper.displayAbstract, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            InterestControl(confidence, reaction, modelActive, onRate)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onSave) {
                    Text(if (reaction.saved) "Saved" else "Save for later")
                }
                OutlinedButton(onClick = { onOpenExternal(paper.absUrl) }) { Text("On arXiv") }
            }
            Spacer(Modifier.height(12.dp))

            if (!showPdf) {
                Button(onClick = { showPdf = true }) {
                    Text(if (store.isCached(paper.id)) "Read the PDF" else "Download the PDF")
                }
                Text(
                    "Downloads once and stays available offline.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (showPdf) {
            item { PdfSection(store, paper, onOpenExternal) }
        }
    }
}

@Composable
private fun PdfSection(store: PdfStore, paper: Paper, onOpenExternal: (String) -> Unit) {
    val widthPx = with(LocalDensity.current) {
        (LocalConfiguration_widthDp() - 32).dp.toPx().roundToInt().coerceAtLeast(320)
    }

    val state by produceState<PdfState>(PdfState.Loading, paper.id) {
        value = try {
            val file = store.download(paper.id)
            val pages = store.pageCount(file)
            if (pages == 0) PdfState.Failed("That file could not be opened as a PDF.")
            else PdfState.Ready(file, pages)
        } catch (e: Exception) {
            PdfState.Failed(e.message ?: "Download failed")
        }
    }

    when (val s = state) {
        is PdfState.Loading -> Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("  Fetching the PDF", style = MaterialTheme.typography.bodySmall)
        }

        is PdfState.Failed -> Column(Modifier.padding(vertical = 16.dp)) {
            Text(s.message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onOpenExternal(paper.pdfUrl) }) {
                Text("Open it on arXiv instead")
            }
        }

        is PdfState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${s.pages} pages", style = MaterialTheme.typography.labelSmall)
            for (i in 0 until s.pages) {
                PdfPage(store, s.file, i, widthPx)
            }
        }
    }
}

/** Pages render one at a time and are held only while on screen. */
@Composable
private fun PdfPage(store: PdfStore, file: java.io.File, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(null, file, index, widthPx) {
        value = store.renderPage(file, index, widthPx)
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.707f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxWidth().background(Color.White),
        )
    }
}

private sealed interface PdfState {
    data object Loading : PdfState
    data class Ready(val file: java.io.File, val pages: Int) : PdfState
    data class Failed(val message: String) : PdfState
}

@Composable
private fun LocalConfiguration_widthDp(): Int =
    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
