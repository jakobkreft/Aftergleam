package si.jakobkreft.aftergleam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Venue

/**
 * Settings, as an index of pages rather than one scroll.
 *
 * It had grown to nine sections in a single column: import, subjects, ranking, appearance,
 * digest, reminder, backup, model, about, with eighty subject chips in the middle of it.
 * Finding the reminder hour meant scrolling past every field of science. The index says what
 * is in here in seven lines, and each page is short enough to take in at once.
 *
 * Navigation is a single piece of state rather than a library: there is one level, and the
 * back gesture is handled here so that it closes the page before it closes settings.
 */
private enum class Page(val title: String, val summary: String) {
    SUBJECTS("Subjects", "What you follow, and what gets fetched"),
    APPEARANCE("Appearance", "Light and dark, colours, letterforms"),
    RANKING("Ranking", "How the digest is chosen, and how many"),
    NOTIFICATIONS("Notifications", "The daily digest and the reading reminder"),
    LIBRARY("Your library", "Import from Zotero, export and restore"),
    MODEL("The model", "What it has learned, and how to clear it"),
    ABOUT("About", "Where the papers come from"),
}

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
    paperSerif: Boolean,
    interfaceSerif: Boolean,
    dynamicColour: Boolean,
    topics: Set<String>,
    ratedCount: Int,
    judgedCount: Int,
    importProgress: si.jakobkreft.aftergleam.data.LibraryImport.Progress?,
    importSummary: String?,
    onDigestSize: (Int) -> Unit,
    onQuality: (Float) -> Unit,
    onExploration: (Float) -> Unit,
    onDiversity: (Float) -> Unit,
    onDigestHour: (Int) -> Unit,
    notificationsAllowed: Boolean,
    onOpenSystemSettings: () -> Unit,
    onNotifyEnabled: (Boolean) -> Unit,
    onReminder: (Boolean, Int) -> Unit,
    onTheme: (String) -> Unit,
    onPaperSerif: (Boolean) -> Unit,
    onInterfaceSerif: (Boolean) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    onTopics: (Set<String>) -> Unit,
    versionName: String,
    onPickLibrary: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    backupSummary: String?,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    // Deeper than the overlay's own handler, so it wins: back leaves the page first.
    BackHandler(enabled = page != null) { page = null }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (page != null) page = null else onClose() }) {
                Text(if (page != null) "Settings" else "Back")
            }
        }
        Text(
            page?.title ?: "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        when (page) {
            null -> Index { page = it }
            Page.SUBJECTS -> SubjectsPage(topics, onTopics)
            Page.APPEARANCE -> AppearancePage(
                theme, paperSerif, interfaceSerif, dynamicColour,
                onTheme, onPaperSerif, onInterfaceSerif, onDynamicColour,
            )
            Page.RANKING -> RankingPage(
                digestSize, quality, exploration, diversity,
                onDigestSize, onQuality, onExploration, onDiversity, onApply,
            )
            Page.NOTIFICATIONS -> NotificationsPage(
                digestHour, notifyEnabled, reminderHour, reminderEnabled,
                notificationsAllowed, onOpenSystemSettings, onDigestHour,
                onNotifyEnabled, onReminder,
            )
            Page.LIBRARY -> LibraryPage(
                importProgress, importSummary, backupSummary,
                onPickLibrary, onExport, onRestore,
            )
            Page.MODEL -> ModelPage(ratedCount, judgedCount, onReset)
            Page.ABOUT -> AboutPage(versionName)
        }
    }
}

@Composable
private fun Index(onOpen: (Page) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
    ) {
        items(Page.entries) { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(p) }
                    .padding(vertical = 14.dp)
            ) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    p.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** One page's scrolling body, with the padding every page shares. */
@Composable
private fun PageBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        content = content,
    )
}

@Composable
private fun SubjectsPage(topics: Set<String>, onTopics: (Set<String>) -> Unit) = PageBody {
    // Onboarding promises these can be changed later, so they must be changeable.
    Text(
        "These seed the ranking and decide which categories are fetched. Your reactions " +
            "matter more as they accumulate.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    TopicFields(
        selected = topics,
        onToggle = { key -> onTopics(if (key in topics) topics - key else topics + key) },
    )
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun AppearancePage(
    theme: String,
    paperSerif: Boolean,
    interfaceSerif: Boolean,
    dynamicColour: Boolean,
    onTheme: (String) -> Unit,
    onPaperSerif: (Boolean) -> Unit,
    onInterfaceSerif: (Boolean) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
) = PageBody {
    Text("Light or dark", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark")
            .forEach { (value, label) ->
                FilterChip(
                    selected = theme == value,
                    onClick = { onTheme(value) },
                    label = { Text(label) },
                )
            }
    }

    Spacer(Modifier.height(24.dp))
    Text("Colours", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        if (dynamicColourAvailable)
            "The app's own palette is warm paper and green ink, chosen to be read on for a " +
                "long time. Android can supply colours from your wallpaper instead."
        else
            "The app's own palette: warm paper and green ink, chosen to be read on for a " +
                "long time. Wallpaper colours need Android 12.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = dynamicColour && dynamicColourAvailable,
            // Below Android 12 there is no wallpaper palette to take, so the switch is
            // shown disabled rather than hidden: its absence would look like a bug.
            enabled = dynamicColourAvailable,
            onCheckedChange = onDynamicColour,
        )
        Text("  Use my wallpaper's colours", style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(24.dp))
    Text("Text", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Journals set the article in a serif and the furniture around it in something " +
            "else. Keeping that split is a quiet way of showing where the app stops " +
            "talking and the paper starts.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = paperSerif, onCheckedChange = onPaperSerif)
        Text("  Serif for titles and abstracts", style = MaterialTheme.typography.bodyMedium)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = interfaceSerif, onCheckedChange = onInterfaceSerif)
        Text("  Serif for the app as well", style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun RankingPage(
    digestSize: Int,
    quality: Float,
    exploration: Float,
    diversity: Float,
    onDigestSize: (Int) -> Unit,
    onQuality: (Float) -> Unit,
    onExploration: (Float) -> Unit,
    onDiversity: (Float) -> Unit,
    onApply: () -> Unit,
) = PageBody {
    var size by remember { mutableFloatStateOf(digestSize.toFloat()) }
    var qual by remember { mutableFloatStateOf(quality) }
    var expl by remember { mutableFloatStateOf(exploration) }
    var divr by remember { mutableFloatStateOf(diversity) }

    Text("Papers per day: ${size.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
    Slider(size, { size = it }, valueRange = 5f..60f, steps = 10)

    Text("Weight on venue: ${(qual * 100).roundToInt()}%",
        style = MaterialTheme.typography.bodyMedium)
    Text(
        "Multiplies predicted interest, so a well-published paper on a topic you dislike " +
            "still ranks low.",
        style = MaterialTheme.typography.labelSmall,
    )
    Slider(qual, { qual = it }, valueRange = 0f..1f)

    Text("Exploration: ${(expl * 100).roundToInt()}%",
        style = MaterialTheme.typography.bodyMedium)
    Text(
        "Cards near the model's decision boundary. These are the ones it learns most from, " +
            "and they are labelled as such.",
        style = MaterialTheme.typography.labelSmall,
    )
    Slider(expl, { expl = it }, valueRange = 0f..0.4f)

    Text("Variety: ${(divr * 100).roundToInt()}%",
        style = MaterialTheme.typography.bodyMedium)
    Text(
        "Trades a little relevance for a less repetitive digest. At zero, a run returned " +
            "twenty-five cards all matching the same few words.",
        style = MaterialTheme.typography.labelSmall,
    )
    Slider(divr, { divr = it }, valueRange = 0f..0.8f)

    Spacer(Modifier.height(12.dp))
    Button(onClick = {
        onDigestSize(size.roundToInt()); onQuality(qual)
        onExploration(expl); onDiversity(divr); onApply()
    }) { Text("Apply and re-rank") }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun NotificationsPage(
    digestHour: Int,
    notifyEnabled: Boolean,
    reminderHour: Int,
    reminderEnabled: Boolean,
    notificationsAllowed: Boolean,
    onOpenSystemSettings: () -> Unit,
    onDigestHour: (Int) -> Unit,
    onNotifyEnabled: (Boolean) -> Unit,
    onReminder: (Boolean, Int) -> Unit,
) = PageBody {
    var hour by remember { mutableFloatStateOf(digestHour.toFloat()) }
    var remindHour by remember { mutableFloatStateOf(reminderHour.toFloat()) }
    // Not mirrored locally. These two are the one pair of settings the app is not free to
    // grant itself, so the switch has to show what was actually stored rather than what was
    // tapped: a switch that slides on and then silently does nothing is worse than one that
    // refuses to move.
    val notify = notifyEnabled
    val remindOn = reminderEnabled

    if (!notificationsAllowed) {
        // Said once, above both switches, because it is one fact about the app and not a
        // property of either setting. Android stops showing its own dialog after two
        // refusals, so without a way through to the system screen a reader who changed
        // their mind would have no route back.
        Text(
            "Notifications are switched off for Aftergleam in Android settings, so these " +
                "cannot be turned on here.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onOpenSystemSettings) {
            Text("Open Android notification settings")
        }
        Spacer(Modifier.height(8.dp))
    }

    Text("Daily digest", style = MaterialTheme.typography.titleSmall)
    Text(
        "Prepared at %02d:00, on wifi while charging.".format(hour.roundToInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "arXiv announces once each weekday evening and bioRxiv posts daily, so one run a " +
            "day is all that can be useful.",
        style = MaterialTheme.typography.labelSmall,
    )
    Slider(
        value = hour,
        onValueChange = { hour = it },
        onValueChangeFinished = { onDigestHour(hour.roundToInt()) },
        valueRange = 0f..23f,
        steps = 22,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = notify, onCheckedChange = { onNotifyEnabled(it) })
        Text("  Notify me once when it is ready", style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(24.dp))
    Text("Reading reminder", style = MaterialTheme.typography.titleSmall)
    Text(
        "A separate nudge at an hour that suits reading. Downloads nothing, and stays " +
            "quiet if you have already been through the digest.",
        style = MaterialTheme.typography.labelSmall,
    )
    // Saved as it changes. A switch and an hour are single, reversible choices, and asking
    // someone to confirm a toggle they can see the effect of is a step that only exists to
    // be forgotten.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = remindOn,
            onCheckedChange = { onReminder(it, remindHour.roundToInt()) },
        )
        Text("  Remind me at %02d:00".format(remindHour.roundToInt()),
            style = MaterialTheme.typography.bodyMedium)
    }
    if (remindOn) {
        Slider(
            value = remindHour,
            onValueChange = { remindHour = it },
            onValueChangeFinished = { onReminder(remindOn, remindHour.roundToInt()) },
            valueRange = 0f..23f,
            steps = 22,
        )
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun LibraryPage(
    importProgress: si.jakobkreft.aftergleam.data.LibraryImport.Progress?,
    importSummary: String?,
    backupSummary: String?,
    onPickLibrary: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
) = PageBody {
    Text("Import your library", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "A BibTeX or RIS export from Zotero. Papers you chose to read are a much stronger " +
            "signal than anything the app can guess, so this is the fastest way to a " +
            "useful feed.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    if (importProgress != null) {
        Text(
            "Resolving ${importProgress.done} of ${importProgress.total}, matched " +
                "${importProgress.matched}. arXiv allows one request every three seconds, " +
                "so this takes a while.",
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = {
                if (importProgress.total == 0) 0f
                else importProgress.done.toFloat() / importProgress.total
            },
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

    Spacer(Modifier.height(24.dp))
    Text("Backup", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Your reactions as a plain JSON file. There is no account and no server, so this " +
            "is how a model moves to another phone: put it in a synced folder and the sync " +
            "app does the rest.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onExport) { Text("Export") }
        OutlinedButton(onClick = onRestore) { Text("Restore") }
    }
    backupSummary?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun ModelPage(ratedCount: Int, judgedCount: Int, onReset: () -> Unit) = PageBody {
    var confirmReset by remember { mutableStateOf(false) }
    Text(
        "Learned from $ratedCount papers, $judgedCount of them reacted to. The rest is " +
            "what you opened, saved and read.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))
    // Trust requires an exit. Confirming in place avoids a dialog dependency.
    if (!confirmReset) {
        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset the model") }
    } else {
        Text(
            "This deletes every reaction and save. Papers stay cached.",
            style = MaterialTheme.typography.labelSmall,
        )
        Row {
            TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            TextButton(onClick = { confirmReset = false; onReset() }) {
                Text("Delete everything")
            }
        }
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun AboutPage(versionName: String) = PageBody {
    Text("Aftergleam $versionName", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        "Papers come from arXiv, bioRxiv and medRxiv, whichever subjects you chose. arXiv " +
            "asks for one request every three seconds; the app stays well inside that. " +
            "Popularity counts come from the Hugging Face daily papers list, fetched whole " +
            "so it says nothing about you. Ranking, your reactions and everything you read " +
            "stay on this device.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Thanks to arXiv for its open access interoperability, and to bioRxiv and medRxiv " +
            "for a public API that asks nothing of the reader.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(32.dp))
}
