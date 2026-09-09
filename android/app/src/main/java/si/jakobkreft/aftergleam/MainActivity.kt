package si.jakobkreft.aftergleam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import si.jakobkreft.aftergleam.ui.DetailScreen
import si.jakobkreft.aftergleam.ui.PastScreen
import si.jakobkreft.aftergleam.ui.FeedScreen
import si.jakobkreft.aftergleam.ui.FeedViewModel
import si.jakobkreft.aftergleam.ui.OnboardingScreen
import si.jakobkreft.aftergleam.ui.PdfReaderScreen
import si.jakobkreft.aftergleam.ui.LibraryScreen
import si.jakobkreft.aftergleam.ui.ExploreScreen
import si.jakobkreft.aftergleam.ui.PopularScreen
import si.jakobkreft.aftergleam.ui.SearchScreen
import si.jakobkreft.aftergleam.ui.TuneScreen
import si.jakobkreft.aftergleam.work.DailyDigestWorker
import si.jakobkreft.aftergleam.work.MetadataRefreshWorker
import si.jakobkreft.aftergleam.work.ReminderWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

/**
 * Four surfaces, each answering a different question.
 *
 * Search used to be one of these, which was wrong: it is a verb, not a place. Nobody opens an
 * app to be in search. It is now an action in the bar, leaving the tabs for the four things a
 * reader actually comes back for.
 */
private enum class Tab(val label: String) {
    /** What you probably want, finishable. */
    TODAY("For you"),
    /** What you might not know you want, wider and less sure of itself. */
    EXPLORE("Explore"),
    /** What everyone is reading, with the model switched off. */
    POPULAR("Popular"),
    /** What you kept. */
    LIBRARY("Library"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: FeedViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val dark = when (state.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    // rememberSaveable: a rotation recreates the activity, and plain remember would drop the
    // reader back on Today from whichever tab they were using.
    var tab by rememberSaveable {
        // Landing on the one screen that is ready, rather than on placeholder cards.
        mutableStateOf(if (vm.openOnPopular) Tab.POPULAR else Tab.TODAY)
    }

    /**
     * Keeps each screen's scroll position while it is off the composition.
     *
     * Opening a paper replaces the whole screen rather than pushing onto a back stack, so
     * the list underneath is disposed and its `rememberLazyListState` goes with it: scroll
     * halfway down the digest, open the fortieth card, come back, and you are at the top
     * with no way to find where you were. This is what a navigation library would install
     * for the same reason. Every screen wrapped in it keeps its position, and switching
     * tabs and back now keeps it too.
     */
    val screenState = rememberSaveableStateHolder()

    // Any text MIME type: exports are variously text/plain, text/x-bibtex or octet-stream,
    // and a narrow filter would hide the user's own file from them in the picker.
    // Below 33 there is no runtime permission and notifications are simply allowed.
    fun notificationsAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // What to do once the reader has answered. Set before launching, because the result
    // arrives on a later frame and the switch that asked is long gone by then.
    var onPermissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Declining is fine: the digest still builds, it just does not announce itself.
        vm.markNotificationsAsked()
        onPermissionResult?.invoke(granted)
        onPermissionResult = null
    }

    /**
     * Turns a notification setting on only if it can actually work.
     *
     * Switching a notification on used to write the preference whatever Android thought,
     * so a reader who had declined the permission got a switch that stayed on and a
     * notification that never came. Asking again here is the right moment: they have just
     * said what they want, which is exactly when the system dialog makes sense. If the
     * permission is refused, or was refused twice before and Android no longer asks, the
     * setting is not written and the switch does not move.
     */
    fun withNotificationPermission(enable: Boolean, apply: (Boolean) -> Unit) {
        if (!enable || notificationsAllowed()) {
            apply(enable)
            return
        }
        onPermissionResult = { granted -> apply(granted) }
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // CreateDocument needs the MIME type up front; the suggested name is passed at launch.
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(si.jakobkreft.aftergleam.data.Backup.MIME)
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(vm.exportBackup().toByteArray())
            }
            vm.noteExported(uri.lastPathSegment?.substringAfterLast('/') ?: "file")
        }
    }

    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.let { vm.restoreBackup(it) }
    }

    val pickLibrary = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.let { vm.importLibrary(it) }
    }

    MaterialTheme(
        colorScheme = if (dark) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    ) {
        if (!state.onboarded) {
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    OnboardingScreen(
                        survey = state.survey,
                        topics = state.topics,
                        importProgress = state.importProgress,
                        importSummary = state.importSummary,
                        onTopics = vm::setTopics,
                        onStart = vm::startSurvey,
                        onAnswer = vm::answerSurvey,
                        onBack = vm::undoSurveyAnswer,
                        onFinish = vm::finishSurvey,
                        onImport = { pickLibrary.launch(arrayOf("*/*")) },
                        onSkip = vm::finishOnboarding,
                    )
                }
            }
            return@MaterialTheme
        }

        // Keyed on what the shelves are actually made of, not on every reaction. Opening a
        // paper sets its `viewed` flag, which no shelf shows, and that was enough to rebuild
        // all three lists and nudge the reader's scroll position on the way back out.
        LaunchedEffect(
            tab,
            state.reactions.count { it.value.saved },
            state.judgedCount,
        ) { if (tab == Tab.LIBRARY) vm.loadLibrary() }

        // Scheduling is idempotent (UPDATE on a unique name), so doing it on every launch
        // also repairs the schedule if the user cleared app data or rebooted.
        LaunchedEffect(Unit) {
            DailyDigestWorker.schedule(context, vm.currentDigestHour())
            MetadataRefreshWorker.schedule(context)
            if (vm.currentReminderEnabled()) {
                ReminderWorker.schedule(context, vm.currentReminderHour())
            }
            // Ask once, on the first run. Android shows this at most twice before denying
            // silently, so firing it on every launch afterwards is a no-op that only makes
            // the code look like it is trying.
            if (!vm.notificationsAsked()) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    onPermissionResult = { granted ->
                        if (granted) {
                            vm.enableEveningReminder()
                            ReminderWorker.schedule(context, vm.currentReminderHour())
                        }
                    }
                    notificationPermission.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                } else {
                    vm.enableEveningReminder()
                    ReminderWorker.schedule(context, vm.currentReminderHour())
                }
            }
        }

        val reading = state.reading
        if (reading != null) {
            BackHandler { vm.closeReader() }
            val file = state.readingFile
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    when {
                        state.readingError != null -> Column(Modifier.padding(24.dp)) {
                            Text(state.readingError!!)
                            TextButton(onClick = vm::closeReader) { Text("Back") }
                        }
                        file == null -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                androidx.compose.material3.CircularProgressIndicator()
                                Text("Fetching the PDF", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        else -> PdfReaderScreen(
                            file = file,
                            title = reading.displayTitle,
                            store = remember { si.jakobkreft.aftergleam.data.PdfStore(context) },
                            initialPage = state.readingPage,
                            onPageChanged = { vm.rememberPage(reading.id, it) },
                            onBack = vm::closeReader,
                        )
                    }
                }
            }
            return@MaterialTheme
        }

        val detail = state.detail
        if (detail != null) {
            BackHandler { vm.closeDetail() }
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    DetailScreen(
                        paper = detail,
                        reaction = state.reactions[detail.id] ?: si.jakobkreft.aftergleam.data.Reaction.NONE,
                        liked = state.evidence[detail.id]?.let {
                            when {
                                si.jakobkreft.aftergleam.data.Signal.LIKED in it.signals -> true
                                si.jakobkreft.aftergleam.data.Signal.DISLIKED in it.signals -> false
                                else -> null
                            }
                        },
                        // A paper opened from search is not in today's digest, so looking
                        // only at `cards` reported every search result as 0% interest.
                        confidence = state.cards.firstOrNull { it.paper.id == detail.id }?.relevance
                            ?: state.searchHits.firstOrNull { it.paper.id == detail.id }?.interest
                            ?: 0f,
                        modelActive = state.modelActive,
                        upvotes = state.attention[detail.id] ?: 0,
                        onSteer = { vm.steer(detail.id, it) },
                        onSave = { vm.toggleSave(detail.id) },
                        onOpenExternal = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        onRead = { vm.openReader(detail) },
                        onShare = {
                            vm.share(detail.id)
                            // Title plus link: what a colleague actually needs, and it
                            // pastes usefully into any chat or mail client.
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, detail.displayTitle)
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    // The paper first, because that is what was asked for.
                                    // Then one line saying what sent it and where to get
                                    // it, which is the whole of the advertising: a colleague
                                    // who wants the app can act on it, and one who does not
                                    // has lost a line. It said "an offline arXiv reader",
                                    // which stopped being true when bioRxiv and medRxiv
                                    // arrived.
                                    "${detail.displayTitle}\n${detail.absUrl}" +
                                        "\n\nFound with Aftergleam, a privacy reader for " +
                                        "arXiv scientific papers.",
                                )
                            }
                            context.startActivity(Intent.createChooser(share, null))
                        },
                        onBack = vm::closeDetail,
                    )
                }
            }
            return@MaterialTheme
        }

        // Below the detail screen in the overlay stack and above the tabs, so opening a
        // paper from an earlier digest covers this and closing it comes back here rather
        // than dumping the reader on today.
        if (state.pastOpen) {
            BackHandler { if (state.pastDay != null) vm.closePastDay() else vm.closePast() }
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    screenState.SaveableStateProvider("past-" + (state.pastDay ?: "index")) {
                    PastScreen(
                        state = state,
                        onOpenDay = vm::openPastDay,
                        onOpenCatchUp = vm::openCatchUp,
                        onSteer = vm::steer,
                        onSave = vm::toggleSave,
                        onOpen = vm::openDetail,
                        onBackToIndex = vm::closePastDay,
                        onBack = vm::closePast,
                    )
                    }
                }
            }
            return@MaterialTheme
        }

        val searchOpen = state.searchOpen
        if (searchOpen) {
            BackHandler { vm.closeSearch() }
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    Column {
                        TextButton(onClick = vm::closeSearch) { Text("Back") }
                        screenState.SaveableStateProvider("search") {
                        SearchScreen(
                            state = state,
                            onQuery = vm::setSearchQuery,
                            onSubmit = vm::runSearch,
                            onPersonalisation = vm::setPersonalisation,
                            onScope = vm::setSearchScope,
                            onSteer = vm::steer,
                            onSave = vm::toggleSave,
                            onOpen = vm::openDetail,
                        )
                        }
                    }
                }
            }
            return@MaterialTheme
        }

        var showTune by rememberSaveable { mutableStateOf(false) }
        if (showTune) {
            BackHandler { showTune = false }
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    Column {
                        TextButton(onClick = { showTune = false }) { Text("Back") }
                        TuneScreen(
                            digestSize = vm.currentDigestSize(),
                            quality = vm.currentQualityWeight(),
                            exploration = vm.currentExplorationRate(),
                            diversity = vm.currentDiversity(),
                            digestHour = vm.currentDigestHour(),
                            notifyEnabled = state.notifyEnabled,
                            reminderHour = vm.currentReminderHour(),
                            reminderEnabled = state.reminderEnabled,
                            theme = state.theme,
                            topics = state.topics,
                            ratedCount = state.ratedCount,
                            judgedCount = state.judgedCount,
                            importProgress = state.importProgress,
                            importSummary = state.importSummary,
                            onDigestSize = vm::setDigestSize,
                            onQuality = vm::setQualityWeight,
                            onExploration = vm::setExplorationRate,
                            onDiversity = vm::setDiversity,
                            onTheme = vm::setTheme,
                            onTopics = vm::setTopics,
                            versionName = si.jakobkreft.aftergleam.BuildConfig.VERSION_NAME,
                            onDigestHour = { h ->
                                vm.setDigestHour(h)
                                DailyDigestWorker.schedule(context, h)
                            },
                            notificationsAllowed = notificationsAllowed(),
                            onOpenSystemSettings = {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings
                                            .ACTION_APP_NOTIFICATION_SETTINGS
                                    ).putExtra(
                                        android.provider.Settings.EXTRA_APP_PACKAGE,
                                        context.packageName,
                                    )
                                )
                            },
                            onNotifyEnabled = { on ->
                                withNotificationPermission(on) { vm.setNotifyEnabled(it) }
                            },
                            onReminder = { on, hour ->
                                withNotificationPermission(on) { granted ->
                                    vm.setReminderEnabled(granted)
                                    vm.setReminderHour(hour)
                                    if (granted) ReminderWorker.schedule(context, hour)
                                    else ReminderWorker.cancel(context)
                                }
                            },
                            onPickLibrary = { pickLibrary.launch(arrayOf("*/*")) },
                            onExport = {
                                exportBackup.launch(si.jakobkreft.aftergleam.data.Backup.suggestedFileName())
                            },
                            onRestore = { restoreBackup.launch(arrayOf("*/*")) },
                            backupSummary = state.backupSummary,
                            onReset = vm::resetModel,
                            onApply = { vm.rerank(); showTune = false },
                        )
                    }
                }
            }
            return@MaterialTheme
        }

        LaunchedEffect(tab) {
            when (tab) {
                Tab.EXPLORE -> if (state.explore.isEmpty()) vm.loadExplore()
                // Both are precomputed once the digest lands, so this is only the fallback
                // for a tab reached before that finished.
                Tab.POPULAR -> if (state.popular.isEmpty()) vm.loadPopular()
                else -> Unit
            }
        }

        Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = { Text(tab.label) },
                    actions = {
                        IconButton(onClick = {
                            vm.openSearch(
                                if (tab == Tab.LIBRARY) si.jakobkreft.aftergleam.ui.SearchScope.KEPT
                                else null
                            )
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search arXiv")
                        }
                        IconButton(onClick = { showTune = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                if (t == Tab.EXPLORE) {
                                    Icon(
                                        androidx.compose.ui.res.painterResource(
                                            si.jakobkreft.aftergleam.R.drawable.ic_explore
                                        ),
                                        contentDescription = t.label,
                                    )
                                } else {
                                    Icon(
                                        when (t) {
                                            Tab.TODAY -> Icons.Filled.List
                                            Tab.POPULAR -> Icons.Filled.ThumbUp
                                            else -> Icons.Filled.Star
                                        },
                                        contentDescription = t.label,
                                    )
                                }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        ) { inner ->
            val openExternal: (String) -> Unit = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            Box(Modifier.padding(inner)) {
                screenState.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.TODAY -> FeedScreen(
                        state = state,
                        onSteer = vm::steer,
                        onSave = vm::toggleSave,
                        onOpen = vm::openDetail,
                        onRerank = vm::rerank,
                        onRefresh = vm::refresh,
                        onPast = vm::openPast,
                        onDismissResurfaced = vm::dismissResurfaced,
                    )
                    Tab.EXPLORE -> ExploreScreen(
                        state = state,
                        onSteer = vm::steer,
                        onSave = vm::toggleSave,
                        onOpen = vm::openDetail,
                        onMore = { vm.loadExplore(more = true) },
                    )
                    Tab.POPULAR -> PopularScreen(
                        state = state,
                        onSteer = vm::steer,
                        onSave = vm::toggleSave,
                        onOpen = vm::openDetail,
                    )
                    Tab.LIBRARY -> LibraryScreen(
                        saved = state.saved,
                        downloaded = state.downloaded,
                        rated = state.ratedPapers,
                        likedFlag = state::likedFlag,
                        onOpen = vm::openDetail,
                        onUnsave = vm::toggleSave,
                        onSteer = vm::steer,
                    )
                }
                }
            }
        }
    }
}
