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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
 * is in here in a line per page, and each page is short enough to take in at once.
 *
 * Navigation is a single piece of state rather than a library: there is one level, and the
 * back gesture is handled here so that it closes the page before it closes settings.
 */
private enum class Page(val title: String, val summary: String) {
    SUBJECTS("Subjects and keywords", "What you follow, and words to watch for"),
    APPEARANCE("Appearance", "Light and dark, colours, letterforms"),
    RANKING("Ranking", "How the digest is chosen, and how many"),
    NOTIFICATIONS("Notifications", "The daily digest and the reading reminder"),
    LIBRARY("Your library", "Import from Zotero, export and restore"),
    MODEL("The model", "What it has learned, and how to clear it"),
    ABOUT("About", "Where the papers come from"),
    SUPPORT("Support Aftergleam", "Free, no ads, open source. Help keep it that way"),
    // The same place in a copy from Google Play, which may not ask for money.
    HELP("Help Aftergleam", "Rate it, share it, or say what is missing"),
}

@Composable
fun TuneScreen(
    /** Opens straight at this page, for the screens that send the reader here to fix something. */
    startPage: String? = null,
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
    /** Opens a web address in the browser, for the About page's links. */
    onOpenUrl: (String) -> Unit = {},
    /** False in a copy installed from Google Play, which asks for a rating instead. */
    donationsAllowed: Boolean = false,
    supportReminder: Boolean = true,
    onSupportReminder: (Boolean) -> Unit = {},
    onDonate: () -> Unit = {},
    onRate: () -> Unit = {},
    onShareApp: () -> Unit = {},
    /** Writes to the developer, from About and the support page. */
    onFeedback: () -> Unit = {},
    keywords: List<String> = emptyList(),
    onAddKeyword: (String) -> Unit = {},
    onRemoveKeyword: (String) -> Unit = {},
    suggestKeyword: suspend (String) -> String? = { null },
    onPrepareSpelling: () -> Unit = {},
    keywordCounts: Map<String, Int> = emptyMap(),
    onClose: () -> Unit,
) {
    var page by rememberSaveable {
        mutableStateOf(Page.entries.firstOrNull { it.name == startPage })
    }
    // Deeper than the overlay's own handler, so it wins: back leaves the page first.
    //
    // Unless this page was opened directly from somewhere else, in which case back returns
    // there. A reader sent here from Explore to widen their subjects never saw the settings
    // index, and being dropped on it by the back gesture is being moved somewhere they have
    // not been.
    BackHandler(enabled = page != null) {
        if (startPage != null && page?.name == startPage) onClose() else page = null
    }

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
            null -> Index(
                pages = Page.entries.filter {
                    it != (if (donationsAllowed) Page.HELP else Page.SUPPORT)
                },
            ) { page = it }
            Page.SUBJECTS -> SubjectsPage(
                topics, onTopics, keywords, onAddKeyword, onRemoveKeyword, suggestKeyword,
                onPrepareSpelling, keywordCounts,
            )
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
                importSummary, backupSummary,
                onPickLibrary, onExport, onRestore,
            )
            Page.MODEL -> ModelPage(ratedCount, judgedCount, onReset)
            Page.ABOUT -> AboutPage(versionName, onOpenUrl, onFeedback)
            Page.SUPPORT -> SupportPage(supportReminder, onSupportReminder, onDonate, onFeedback)
            Page.HELP -> HelpPage(
                supportReminder, onSupportReminder, onRate, onShareApp, onFeedback,
            )
        }
    }
}

@Composable
private fun Index(pages: List<Page>, onOpen: (Page) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
    ) {
        items(pages) { p ->
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
private fun SubjectsPage(
    topics: Set<String>,
    onTopics: (Set<String>) -> Unit,
    keywords: List<String>,
    onAddKeyword: (String) -> Unit,
    onRemoveKeyword: (String) -> Unit,
    suggestKeyword: suspend (String) -> String?,
    onPrepareSpelling: () -> Unit,
    keywordCounts: Map<String, Int>,
) = PageBody {
    // Keywords first: the list is short. The rule is behind an info button, said in full when
    // asked for and otherwise out of the way, since most readers glance at this page and leave.
    var explained by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Keywords", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { explained = !explained }) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.Info,
                contentDescription = if (explained) "Hide how keywords work" else "How keywords work",
                tint = if (explained) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    androidx.compose.animation.AnimatedVisibility(explained) {
        Column {
            Text(
                "Papers that mention one of these come first, from any field, marked with the " +
                    "keyword. Matching is exact apart from capitals, hyphens and plurals: U-Net " +
                    "also finds UNet, and RNA finds RNAs but never mRNA. Up to half of each digest.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Add each name a paper might use, such as C. elegans and Caenorhabditis elegans.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    KeywordEditor(
        keywords = keywords,
        onAdd = onAddKeyword,
        onRemove = onRemoveKeyword,
        suggest = suggestKeyword,
        placeholder = keywordExample(topics),
        onReady = onPrepareSpelling,
        counts = keywordCounts,
    )
    Spacer(Modifier.height(24.dp))
    Text("Subjects", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
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
        "Papers that only just missed the cut, labelled as such. Your reaction to them " +
            "shows the model where your line is.",
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
    Button(onClick = onPickLibrary) { Text("Choose a .bib or .ris file") }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutPage(
    versionName: String,
    onOpenUrl: (String) -> Unit,
    onFeedback: () -> Unit,
) = PageBody {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallMark(36.dp, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Aftergleam",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            )
            Text("Version $versionName", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(4.dp))
    Spacer(Modifier.height(10.dp))
    Text(
        "Papers come from arXiv, bioRxiv, medRxiv, PsyArXiv, SocArXiv, EdArXiv, Law Archive " +
            "and ChemRxiv, and only from the ones your subjects need; a search online asks " +
            "arXiv and Crossref, which covers the rest. Popularity counts come " +
            "from the Hugging Face daily papers list, fetched whole so it says nothing about " +
            "you. Ranking, your reactions and everything you read stay on this device.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(10.dp))
    // arXiv's API terms ask for this acknowledgement in these words.
    Text(
        "Thank you to arXiv for use of its open access interoperability. Thanks also to " +
            "bioRxiv and medRxiv, to the Center for Open Science for OSF, and to Crossref, " +
            "for public APIs that ask nothing of the reader.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "Aftergleam is free software under the GNU General Public License, version 3 or " +
            "later. It comes with no warranty.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onOpenUrl(REPO) }) { Text("Source code") }
        OutlinedButton(onClick = { onOpenUrl("$REPO/blob/main/PRIVACY.md") }) {
            Text("Privacy policy")
        }
        OutlinedButton(onClick = { onOpenUrl("$REPO/blob/main/LICENSE") }) { Text("License") }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "Questions, bugs, or a source you would like added: " +
            si.jakobkreft.aftergleam.data.Support.CONTACT,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onFeedback) { Text("Write to the developer") }
    Spacer(Modifier.height(32.dp))
}

/**
 * The permanent, quiet place to support the app: always here, never in the way.
 *
 * It says plainly that nothing is locked, because a request that might be a paywall in
 * disguise is one people ignore. The reminder switch lives here too, so turning the note off
 * and finding it again are the same place.
 */
@Composable
private fun SupportPage(
    reminder: Boolean,
    onReminder: (Boolean) -> Unit,
    onDonate: () -> Unit,
    onFeedback: () -> Unit,
) = PageBody {
    Text(
        "Aftergleam is free and open source. It has no ads, no tracking and no account, and " +
            "it never will. Supporting it unlocks nothing, because nothing is locked.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "If it has earned a place in your mornings, you can help keep it going.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = onDonate) { Text("Support on Ko-fi") }
    Spacer(Modifier.height(16.dp))
    Text(
        "Hearing what is missing or broken helps too.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onFeedback) { Text("Write to the developer") }
    ReminderSetting(reminder, onReminder)
}

/**
 * The same page in a copy installed from Google Play.
 *
 * Play counts any link, button or message that leads to another way of paying, including a
 * website that has a donate button on it, so this page asks only for what Play allows and
 * what genuinely helps a free app there: ratings decide who finds it.
 */
@Composable
private fun HelpPage(
    reminder: Boolean,
    onReminder: (Boolean) -> Unit,
    onRate: () -> Unit,
    onShare: () -> Unit,
    onFeedback: () -> Unit,
) = PageBody {
    Text(
        "Aftergleam is free and open source, with no ads, no tracking and no account. The " +
            "most useful help is a rating on Google Play, a word to a colleague, and hearing " +
            "what is missing or broken.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRate) { Text("Rate on Google Play") }
        OutlinedButton(onClick = onShare) { Text("Share the app") }
        OutlinedButton(onClick = onFeedback) { Text("Write to the developer") }
    }
    ReminderSetting(reminder, onReminder)
}

@Composable
private fun ColumnScope.ReminderSetting(reminder: Boolean, onReminder: (Boolean) -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text("The occasional reminder", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Once you have read on five different days, a short note ends the digest until you " +
            "answer it. Not now hides it for a week. Once you have acted on it, it stays away " +
            "for a year.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = reminder, onCheckedChange = onReminder)
        Text("  Show the reminder", style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(32.dp))
}

private const val REPO = "https://github.com/jakobkreft/aftergleam"
