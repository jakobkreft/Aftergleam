package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.R
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Reaction
import si.jakobkreft.aftergleam.data.Source
import si.jakobkreft.aftergleam.data.Venue
import si.jakobkreft.aftergleam.rank.Slot

/**
 * One card, used by every surface.
 *
 * The digest, explore, popular and search each grew their own version, which meant a paper
 * you could rate in one place was inert in another for no reason a reader could see. There is
 * one card now, and anywhere a paper appears it can be steered, saved and opened.
 *
 * No predicted percentage. A number like "38%" looks precise and is not: a paper at 38 can be
 * exactly right and another at 38 can be useless, and putting it on the card invites the
 * reader to trust a figure the model has not earned. The same value is genuinely useful for
 * *ordering*, which is where it stays. What the reader gets instead is the reason, in words
 * they can check.
 */
/** The card for [paper], with what the reader has done with it read from [state]. */
@Composable
fun PaperCard(
    state: FeedState,
    paper: Paper,
    reason: String?,
    slot: Slot?,
    onSteer: (String, Boolean?) -> Unit,
    onSave: (String) -> Unit,
    onOpen: (Paper) -> Unit,
) {
    val reaction = state.reactions[paper.id] ?: Reaction.NONE
    PaperCard(
        paper = paper,
        reason = reason,
        slot = slot,
        liked = state.likedFlag(paper.id),
        saved = reaction.saved,
        viewed = reaction.viewed,
        upvotes = state.attention[paper.id] ?: 0,
        onSteer = { onSteer(paper.id, it) },
        onSave = { onSave(paper.id) },
        onOpen = { onOpen(paper) },
    )
}

@Composable
fun PaperCard(
    paper: Paper,
    reason: String?,
    slot: Slot?,
    liked: Boolean?,
    saved: Boolean,
    viewed: Boolean,
    upvotes: Int,
    onSteer: (Boolean?) -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
) {
    val byline = remember(paper, upvotes) {
        listOfNotNull(
            paper.shortAuthors.ifBlank { null },
            // Named only when it is not arXiv. Where a paper came from changes what it is: a
            // medRxiv preprint is not peer reviewed and may never be, and the reader is
            // entitled to know that at a glance.
            paper.sourceLabel,
            // Blank for a paper with no subject, which a search can bring in.
            Source.display(paper.primaryCategory).ifBlank { null },
            Venue.of(paper),
            if (upvotes > 0) Attention.label(upvotes) else null,
        )
    }
    // To a screen reader the card is one thing with three switches after it, and says what is
    // on it: its title, where it is from and why it is here. Each line used to be a thing of
    // its own, the abstract read out whole though two lines of it show, and each switch four
    // things, eighteen in all for one card. With an accessibility service on, every one of
    // them is measured again at each frame of a scroll, and a list of cards could not keep up.
    val spoken =
        listOf(paper.displayTitle, byline.joinToString(", "), reason.orEmpty(), if (viewed) "Opened" else "")
            .filter { it.isNotBlank() }
            .joinToString(". ")
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        // Read and unread are told apart by the surface and the title, not by a
        // shadow that only one of them had.
        elevation = flatCard(),
        colors = CardDefaults.cardColors(
            containerColor = if (viewed) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        // The words in one group and the switches in another, laid over its last line where
        // room is left for them: the group speaks for every line in it, and a line that only
        // had its own words taken away was still there to be measured.
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
                if (!reason.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        slot?.let { SlotDot(it); Spacer(Modifier.width(6.dp)) }
                        Text(
                            reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    paper.displayTitle,
                    style = paperStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = if (viewed) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (viewed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = paperLines(3),
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    paper.displayAbstract,
                    style = paperStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.height(ACTION), verticalAlignment = Alignment.CenterVertically) {
                    if (viewed) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        )
                    }
                    Text(
                        byline.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(ACTION * 3))
                }
            }
            Row(Modifier.align(Alignment.BottomEnd)) {
                CardAction(rememberVectorPainter(Icons.Filled.Clear), liked == false, "Less like this") {
                    onSteer(if (liked == false) null else false)
                }
                CardAction(rememberVectorPainter(Icons.Filled.Favorite), liked == true, "More like this") {
                    onSteer(if (liked == true) null else true)
                }
                CardAction(painterResource(R.drawable.ic_bookmark), saved, "Save for later", onSave)
            }
        }
    }
}

/** The size of one of a card's three switches, and so the height of the line they sit on. */
private val ACTION = 36.dp

/**
 * What a list calls its paper cards, so that a card scrolling in is built from one that scrolled
 * out, and not from the header or the note that shared the list with it.
 */
const val PAPER = "paper"

/** A small colour cue for which slot a card came from, paired with the text reason. */
@Composable
private fun SlotDot(slot: Slot) {
    val colour = when (slot) {
        Slot.RELEVANCE -> MaterialTheme.colorScheme.primary
        Slot.EXPLORATION -> MaterialTheme.colorScheme.secondary
        Slot.BRIDGE -> MaterialTheme.colorScheme.tertiary
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
}

/**
 * An action that plainly reads as on or off.
 *
 * A tinted outline was too subtle: a saved paper looked much like an unsaved one, so the
 * obvious next tap un-saved it by accident. An active action now sits on a filled chip, and a
 * screen reader hears it as a switch that is on or off, which a button never said.
 */
@Composable
private fun CardAction(icon: Painter, active: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(ACTION)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .toggleable(value = active, role = Role.Switch, onValueChange = { onClick() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}
