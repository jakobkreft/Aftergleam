package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue
import kotlin.math.roundToInt

@Composable
fun TuneScreen(
    digestSize: Int,
    quality: Float,
    exploration: Float,
    diversity: Float,
    digestHour: Int,
    notifyEnabled: Boolean,
    reminderHour: Int,
    reminderEnabled: Boolean,
    theme: String,
    ratedCount: Int,
    importProgress: si.jakobkreft.aftergleam.data.LibraryImport.Progress?,
    importSummary: String?,
    onDigestSize: (Int) -> Unit,
    onQuality: (Float) -> Unit,
    onExploration: (Float) -> Unit,
    onDiversity: (Float) -> Unit,
    onDigestHour: (Int) -> Unit,
    onNotifyEnabled: (Boolean) -> Unit,
    onReminder: (Boolean, Int) -> Unit,
    onTheme: (String) -> Unit,
    onPickLibrary: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    backupSummary: String?,
    onReset: () -> Unit,
    onApply: () -> Unit,
) {
    var size by remember { mutableFloatStateOf(digestSize.toFloat()) }
    var qual by remember { mutableFloatStateOf(quality) }
    var expl by remember { mutableFloatStateOf(exploration) }
    var divr by remember { mutableFloatStateOf(diversity) }
    var hour by remember { mutableFloatStateOf(digestHour.toFloat()) }
    var notify by remember { mutableStateOf(notifyEnabled) }
    var remindOn by remember { mutableStateOf(reminderEnabled) }
    var remindHour by remember { mutableFloatStateOf(reminderHour.toFloat()) }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("Import your library", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "A BibTeX or RIS export from Zotero. Papers you chose to read are a much " +
                    "stronger signal than anything the app can guess, so this is the " +
                    "fastest way to a useful feed.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (importProgress != null) {
                val p = importProgress
                Text(
                    "Resolving ${p.done} of ${p.total}, matched ${p.matched}. " +
                        "arXiv allows one request every three seconds, so this takes a while.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(onClick = onPickLibrary) { Text("Choose a .bib or .ris file") }
            }
            importSummary?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
        }

        item {
            Text("Ranking", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            Text("Papers per day: ${size.roundToInt()}",
                style = MaterialTheme.typography.bodyMedium)
            Slider(size, { size = it }, valueRange = 5f..60f, steps = 10)

            Text("Weight on venue: ${(qual * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium)
            Text(
                "Multiplies predicted interest, so a well-published paper on a topic you " +
                    "dislike still ranks low.",
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(qual, { qual = it }, valueRange = 0f..1f)

            Text("Exploration: ${(expl * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium)
            Text(
                "Cards near the model's decision boundary. These are the ones it learns " +
                    "most from, and they are labelled as such.",
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(expl, { expl = it }, valueRange = 0f..0.4f)

            Text("Variety: ${(divr * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium)
            Text(
                "Trades a little relevance for a less repetitive digest. At zero, a run " +
                    "returned twenty-five cards all matching the same few words.",
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(divr, { divr = it }, valueRange = 0f..0.8f)

            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                onDigestSize(size.roundToInt()); onQuality(qual)
                onExploration(expl); onDiversity(divr); onApply()
            }) { Text("Apply and re-rank") }
        }

        item {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark")
                    .forEach { (value, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = theme == value,
                            onClick = { onTheme(value) },
                            label = { Text(label) },
                        )
                    }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Daily digest", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Prepared at %02d:00, on wifi while charging.".format(hour.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "arXiv announces once each weekday evening, so one run a day is all that " +
                    "can be useful.",
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(hour, { hour = it }, valueRange = 0f..23f, steps = 22)
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.material3.Switch(checked = notify, onCheckedChange = { notify = it })
                Spacer(Modifier.height(0.dp))
                Text("  Notify me once when it is ready",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onDigestHour(hour.roundToInt()); onNotifyEnabled(notify) }) {
                Text("Save schedule")
            }

            Spacer(Modifier.height(20.dp))
            Text("Reading reminder", style = MaterialTheme.typography.titleSmall)
            Text(
                "A separate nudge at an hour that suits reading. Downloads nothing, and " +
                    "stays quiet if you have already been through the digest.",
                style = MaterialTheme.typography.labelSmall,
            )
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.material3.Switch(
                    checked = remindOn, onCheckedChange = { remindOn = it }
                )
                Text("  Remind me at %02d:00".format(remindHour.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (remindOn) {
                Slider(remindHour, { remindHour = it }, valueRange = 0f..23f, steps = 22)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onReminder(remindOn, remindHour.roundToInt()) }) {
                Text("Save reminder")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Your ratings as a plain JSON file. There is no account and no server, so " +
                    "this is how a model moves to another phone: put it in a synced folder " +
                    "and the sync app does the rest.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onExport) { Text("Export") }
                OutlinedButton(onClick = onRestore) { Text("Restore") }
            }
            backupSummary?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("$ratedCount papers rated.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            // Trust requires an exit. Confirming in place avoids a dialog dependency.
            if (!confirmReset) {
                OutlinedButton(onClick = { confirmReset = true }) { Text("Reset the model") }
            } else {
                Text(
                    "This deletes every rating and save. Papers stay cached.",
                    style = MaterialTheme.typography.labelSmall,
                )
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmReset = false; onReset() }) {
                        Text("Delete everything")
                    }
                }
            }
        }
    }
}
