package si.jakobkreft.aftergleam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import si.jakobkreft.aftergleam.data.Keywords
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
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
@OptIn(ExperimentalLayoutApi::class)
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
    /** The reader's keywords, highlighted wherever the paper mentions them. */
    keywords: List<String> = emptyList(),
) {
    val context = LocalContext.current
    val store = remember { PdfStore(context) }
    // Already downloaded means already wanted: asking a second time for a file that is
    // sitting on disk is a button whose only function is to be pressed.
    //
    // rememberSaveable, not remember: a rotation recreates the activity, and plain remember
    // would close the PDF the reader had open.
    var showPdf by rememberSaveable(paper.id) { mutableStateOf(store.isCached(paper.id)) }

    // What opening this paper means, decided once. The title and the button at the foot do
    // the same thing, and a reader who taps a title expects to be reading, not to find out
    // that the tappable part was somewhere else.
    val open: () -> Unit = { if (paper.readableInApp) onRead() else onOpenExternal(paper.absUrl) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // Back on the left, the three judgements on the right.
            //
            // They already sit on every card and at the foot of this screen, but this is
            // where the reader is when they have actually read the abstract and formed the
            // opinion, and the abstract can be long enough that the controls below it are
            // several scrolls away. The same three icons in the same order as the cards, so
            // there is nothing new to learn.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Just "Back". This screen is reached from the digest, Explore, Popular, the
                // library, a search and an earlier digest, and it said "Back to digest" from
                // all six.
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.weight(1f))
                DetailAction(Icons.Filled.Clear, liked == false, "Less like this") {
                    onSteer(if (liked == false) null else false)
                }
                DetailAction(Icons.Filled.Favorite, liked == true, "More like this") {
                    onSteer(if (liked == true) null else true)
                }
                DetailAction(
                    painterResource(si.jakobkreft.aftergleam.R.drawable.ic_bookmark),
                    reaction.saved,
                    if (reaction.saved) "Saved" else "Save for later",
                    onSave,
                )
            }
            Text(
                highlighted(paper.displayTitle, keywords),
                modifier = Modifier.clickable(onClick = open),
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
                    paper.displayId,
                    // Comma separated: a paper filed under "law and politics" and "courts"
                    // ran together as "law and politics courts", which reads as one subject
                    // nobody has ever heard of.
                    // Nothing at all for a paper with no subject, which a search can bring
                    // in, rather than an empty field between two separators.
                    paper.displayCategories.joinToString(", ").ifBlank { null },
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
            // Which keyword brought it here, and where: the mention may be deep in the abstract,
            // and a label the reader cannot find on the page is a label they stop trusting.
            Keywords.mentionedBy(paper, keywords)?.let { k ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Mentions your keyword \u201c$k\u201d, highlighted below",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // The whole abstract, not a preview. This is the point of the screen.
            Text(
                highlighted(paper.displayAbstract, keywords),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = LocalPaperFont.current,
            )
            Spacer(Modifier.height(16.dp))

            InterestControl(confidence, liked, modelActive, onSteer)

            // FlowRow, not Row. Three buttons whose widths depend on the source name and on
            // the reader's font size do not fit every phone: on a narrower screen the last
            // one was squeezed until its label broke across two lines and read "Sh-are". A
            // button that will not fit now moves to the next line instead, and no label is
            // ever allowed to break inside a word.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(onClick = onSave) {
                    ButtonLabel(if (reaction.saved) "Saved" else "Save for later")
                }
                OutlinedButton(onClick = { onOpenExternal(paper.absUrl) }) {
                    ButtonLabel("On " + Source.label(paper.source))
                }
                OutlinedButton(onClick = onShare) { ButtonLabel("Share") }
            }
            Spacer(Modifier.height(12.dp))

            val downloaded = store.cachedFile(paper.id)
            // Once the file is here its type is known, so the button can say what pressing
            // it will actually do. A Word document labelled "Read" leads to a screen whose
            // only purpose is to explain that it cannot be read here.
            val needsAnotherApp = downloaded != null && !store.looksLikePdf(downloaded)
            Button(onClick = open, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(
                    when {
                        !paper.readableInApp -> "Read on " + Source.label(paper.source)
                        needsAnotherApp -> "Open with another app"
                        downloaded != null -> "Read"
                        else -> "Download and read"
                    }
                )
            }
            if (downloaded == null && paper.readableInApp) {
                Text(
                    "Downloads once and stays available offline.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** A button label that never breaks inside a word, whatever the screen or the font scale. */
@Composable
private fun ButtonLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/**
 * One of the three judgements, in the header.
 *
 * Deliberately the same icons, order and active colouring as the cards use, because they are
 * the same three actions and a second visual language for them would only be a second thing
 * to learn.
 */
@Composable
private fun DetailAction(
    icon: ImageVector,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The same, for an icon that had to be drawn rather than imported. */
@Composable
private fun DetailAction(
    icon: Painter,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** [text] with every mention of the reader's keywords marked, the way a search marks a hit. */
@Composable
private fun highlighted(text: String, keywords: List<String>): AnnotatedString {
    val ranges = remember(text, keywords) { Keywords.occurrences(text, keywords) }
    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(text, ranges, background, foreground) {
        buildAnnotatedString {
            append(text)
            for (r in ranges) addStyle(SpanStyle(background = background, color = foreground), r.first, r.last + 1)
        }
    }
}
