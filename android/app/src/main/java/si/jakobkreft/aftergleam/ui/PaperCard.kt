package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Attention
import si.jakobkreft.aftergleam.data.Paper
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
    Card(
        Modifier.fillMaxWidth().clickable { onOpen() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (viewed) 0.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (viewed) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
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
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (viewed) FontWeight.Normal else FontWeight.SemiBold,
                color = if (viewed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                paper.displayAbstract,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (viewed) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Opened",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp).padding(end = 2.dp),
                    )
                }
                Text(
                    listOfNotNull(
                        paper.shortAuthors.ifBlank { null },
                        paper.primaryCategory,
                        Venue.of(paper),
                        if (upvotes > 0) Attention.label(upvotes) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                CardAction(Icons.Filled.Clear, liked == false, "Less like this") {
                    onSteer(if (liked == false) null else false)
                }
                CardAction(Icons.Filled.Favorite, liked == true, "More like this") {
                    onSteer(if (liked == true) null else true)
                }
                CardAction(Icons.Filled.Star, saved, if (saved) "Saved" else "Save for later") {
                    onSave()
                }
            }
        }
    }
}

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
 * obvious next tap un-saved it by accident. An active action now sits on a filled chip.
 */
@Composable
private fun CardAction(
    icon: ImageVector,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
