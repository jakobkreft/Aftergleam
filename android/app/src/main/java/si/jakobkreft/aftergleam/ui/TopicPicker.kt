package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Topics

/**
 * The subject picker, closed by default.
 *
 * Nine fields and eighty topics in one flat list buries biology and medicine under twenty
 * computer science chips, so the reader who most needed to know they were there had to
 * scroll past everything they did not want to find out. Closed, the first screen is the map.
 *
 * Shared by onboarding and settings, which had two copies of this and only one of them was
 * ever fixed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicFields(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    Column(modifier) {
        Topics.FIELDS.forEach { field ->
            val expanded = field.label in open
            val chosen = field.topics.count { it.key in selected }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        open = if (expanded) open - field.label else open + field.label
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                // The count is what makes a closed section safe: a reader can see at a
                // glance that they have chosen something in there without opening it.
                Text(
                    if (chosen > 0) "$chosen chosen" else "${field.topics.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chosen > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field.topics.forEach { t ->
                        FilterChip(
                            selected = t.key in selected,
                            onClick = { onToggle(t.key) },
                            label = { Text(t.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
