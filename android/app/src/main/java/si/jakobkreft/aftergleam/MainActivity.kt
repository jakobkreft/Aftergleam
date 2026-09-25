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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.ui.AftergleamTheme
import si.jakobkreft.aftergleam.ui.DetailScreen
import si.jakobkreft.aftergleam.ui.PastScreen
import si.jakobkreft.aftergleam.ui.FeedScreen
import si.jakobkreft.aftergleam.ui.FeedViewModel
import si.jakobkreft.aftergleam.ui.ImportScreen
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
    /**
     * Which of the four screens is showing, as a pager rather than a plain selection.
     *
     * The bottom bar was the only way across, which on a phone is the one navigation people
     * do not use: every other feed on the device is swiped. The pager is the state and the
     * bar reads from it, rather than the two keeping separate ideas of where the reader is.
     *
     * Its state is saved across rotation the way the plain selection was, so a rotation no
     * longer drops the reader back on Today from whichever tab they were using.
     */
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        // Landing on the one screen that is ready, rather than on placeholder cards.
        initialPage = if (vm.openOnPopular) Tab.POPULAR.ordinal else Tab.TODAY.ordinal,
        pageCount = { Tab.entries.size },
    )
    // currentPage rather than settledPage: it flips once a swipe is more than half way, so
    // the title, the bar and any loading a screen needs all start while the finger is still
    // moving, and the page has something on it by the time it arrives.
    val tab = Tab.entries[pager.currentPage]
    val pagerScope = rememberCoroutineScope()

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

    AftergleamTheme(
        dark = dark,
        dynamic = state.dynamicColour,
        paperSerif = state.paperSerif,
        interfaceSerif = state.interfaceSerif,
    ) {
        // Declared above the import's early return: anything declared after it is unmounted
        // while the import owns the screen, and coming back from a ten minute import to a
        // closed settings screen is its own small loss of place.
        var showTune by rememberSaveable { mutableStateOf(false) }
        // Which settings page to land on. Popular and Explore can both run out of papers
        // through no fault of the reader, and the fix for both is in Subjects.
        var tuneStart by rememberSaveable { mutableStateOf<String?>(null) }
        val browseSubjects = { tuneStart = "SUBJECTS"; showTune = true }

        // Above everything, onboarding included. An import is minutes long and every other
        // screen in the app has a way off it, so the only way to guarantee it is not walked
        // away from by accident is for there to be nothing else on screen to touch.
        if (state.importing) {
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    ImportScreen(
                        progress = state.importProgress,
                        result = state.importResult,
                        onStop = vm::stopImport,
                        onDone = vm::dismissImport,
                    )
                }
            }
            return@AftergleamTheme
        }

        if (!state.onboarded) {
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    screenState.SaveableStateProvider("onboarding") {
                        OnboardingScreen(
                            survey = state.survey,
                            topics = state.topics,
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
            }
            return@AftergleamTheme
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

                        // Downloaded, but not something this reader can draw. Saying so and
                        // offering it to an app that can is the whole of the fix: the file
                        // is on the device either way.
                        state.readingUnsupported != null -> UnsupportedFile(
                            file = state.readingUnsupported!!,
                            onBack = vm::closeReader,
                        )
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
                            sourceName = si.jakobkreft.aftergleam.data.Source.label(reading.source),
                            onOpenSource = { openUrl(context, reading.absUrl) },
                            liked = state.likedFlag(reading.id),
                            saved = state.reactions[reading.id]?.saved == true,
                            onSteer = { vm.steer(reading.id, it) },
                            onSave = { vm.toggleSave(reading.id) },
                            // The same share, and the same signal, as the abstract screen.
                            onShareLink = { sharePaper(context, reading); vm.share(reading.id) },
                            onShared = { vm.share(reading.id) },
                            onRedownload = { vm.redownload(reading) },
                        )
                    }
                }
            }
            return@AftergleamTheme
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
                        onOpenExternal = { url -> openUrl(context, url) },
                        onRead = { vm.openReader(detail) },
                        onShare = { sharePaper(context, detail); vm.share(detail.id) },
                        onBack = vm::closeDetail,
                    )
                }
            }
            return@AftergleamTheme
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
            return@AftergleamTheme
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
            return@AftergleamTheme
        }

        if (showTune) {
            BackHandler { showTune = false }
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    // The header lives inside the screen now, so that back can leave a
                    // settings page before it leaves settings.
                    Column {
                        TuneScreen(
                            startPage = tuneStart,
                            onClose = { showTune = false; tuneStart = null },
                            digestSize = vm.currentDigestSize(),
                            quality = vm.currentQualityWeight(),
                            exploration = vm.currentExplorationRate(),
                            diversity = vm.currentDiversity(),
                            digestHour = vm.currentDigestHour(),
                            notifyEnabled = state.notifyEnabled,
                            reminderHour = vm.currentReminderHour(),
                            reminderEnabled = state.reminderEnabled,
                            theme = state.theme,
                            paperSerif = state.paperSerif,
                            interfaceSerif = state.interfaceSerif,
                            dynamicColour = state.dynamicColour,
                            topics = state.topics,
                            ratedCount = state.ratedCount,
                            judgedCount = state.judgedCount,
                            importSummary = state.importSummary,
                            onDigestSize = vm::setDigestSize,
                            onQuality = vm::setQualityWeight,
                            onExploration = vm::setExplorationRate,
                            onDiversity = vm::setDiversity,
                            onTheme = vm::setTheme,
                            onPaperSerif = vm::setPaperSerif,
                            onInterfaceSerif = vm::setInterfaceSerif,
                            onDynamicColour = vm::setDynamicColour,
                            onTopics = vm::setTopics,
                            versionName = si.jakobkreft.aftergleam.BuildConfig.VERSION_NAME,
                            onDigestHour = { h ->
                                vm.setDigestHour(h)
                                DailyDigestWorker.schedule(context, h)
                            },
                            notificationsAllowed = notificationsAllowed(),
                            onOpenSystemSettings = {
                                // Some vendor builds strip this screen; a missing one should
                                // not take the app down with it.
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            android.provider.Settings
                                                .ACTION_APP_NOTIFICATION_SETTINGS
                                        ).putExtra(
                                            android.provider.Settings.EXTRA_APP_PACKAGE,
                                            context.packageName,
                                        )
                                    )
                                }
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
                            onOpenUrl = { url -> openUrl(context, url) },
                            onApply = { vm.rerank(); showTune = false; tuneStart = null },
                        )
                    }
                }
            }
            return@AftergleamTheme
        }

        LaunchedEffect(tab) {
            when (tab) {
                // Not retried while it is known to be empty: ranking eight hundred
                // candidates on every visit to rebuild the same empty list is seconds spent
                // to learn nothing. A rebuilt digest clears the flag.
                Tab.EXPLORE -> if (state.explore.isEmpty() && !state.exploreExhausted) {
                    vm.loadExplore()
                }
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
                            // Animated rather than jumped to, so tapping a tab and swiping
                            // to it arrive the same way and the bar reads as the same
                            // control as the gesture.
                            onClick = {
                                pagerScope.launch { pager.animateScrollToPage(t.ordinal) }
                            },
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
            val openExternal: (String) -> Unit = { url -> openUrl(context, url) }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                modifier = Modifier.padding(inner),
                // Keyed by the screen rather than by position, so a page keeps its identity
                // and its saved scroll however the reader arrived at it.
                key = { Tab.entries[it].name },
            ) { page ->
                // The page being drawn, which during a swipe is not the one selected. Reading
                // `tab` here would draw the same screen on every page and the swipe would
                // look like the content sliding onto itself.
                val pageTab = Tab.entries[page]
                screenState.SaveableStateProvider(pageTab.name) {
                when (pageTab) {
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
                        onBrowseSubjects = browseSubjects,
                    )
                    Tab.POPULAR -> PopularScreen(
                        state = state,
                        onSteer = vm::steer,
                        onSave = vm::toggleSave,
                        onOpen = vm::openDetail,
                        onBrowseSubjects = browseSubjects,
                    )
                    Tab.LIBRARY -> LibraryScreen(
                        saved = state.saved,
                        downloaded = state.downloaded,
                        rated = state.ratedPapers,
                        likedFlag = state::likedFlag,
                        onOpen = vm::openDetail,
                        onUnsave = vm::toggleSave,
                        onSteer = vm::steer,
                        sizes = state.downloadedBytes,
                        downloading = state.downloading,
                        message = state.libraryMessage,
                        onDeleteDownload = vm::deleteDownload,
                        onDeleteAllDownloads = vm::deleteAllDownloads,
                        onDownload = vm::downloadInBackground,
                        onShare = { p -> sharePaper(context, p); vm.share(p.id) },
                        onDismissMessage = vm::clearLibraryMessage,
                    )
                }
                }
            }
        }
    }
}

/**
 * Hands a paper to whatever the reader wants to send it with.
 *
 * One implementation, because it is now reachable from the detail screen and from the
 * library's row menu, and a share sheet that says something different depending on which
 * one you used would be a small mystery nobody needs.
 *
 * Title plus link is what a colleague actually needs, and it pastes usefully into any chat
 * or mail client. Then one line saying what sent it and where to get it, which is the whole
 * of the advertising: a colleague who wants the app can act on it, and one who does not has
 * lost a line.
 */
/**
 * A download the in-app reader cannot render.
 *
 * Preprint servers hand over whatever the author uploaded, and the Law Archive serves Word
 * documents often enough that this is ordinary rather than exceptional. So it reads as a
 * fact about the file rather than as a failure, and offers the one useful action. Before
 * this the reader sat on "Fetching the PDF" forever, because a file that PdfRenderer cannot
 * open reports no pages and nothing told the difference between that and a slow download.
 */
@Composable
private fun UnsupportedFile(file: java.io.File, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { si.jakobkreft.aftergleam.data.PdfStore(context) }
    val kind = when (file.extension.lowercase()) {
        "docx", "doc", "odt", "rtf" -> "a Word document"
        "pptx", "ppt" -> "a slide deck"
        "xlsx", "xls" -> "a spreadsheet"
        "zip" -> "an archive"
        else -> "a ${file.extension.uppercase()} file"
    }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Not a PDF", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "The author uploaded $kind, so it cannot be shown here. It is downloaded, and " +
                "any app that reads that format can open it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".files", file,
                )
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, store.mimeOf(file))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // A chooser rather than a direct launch: on a phone with nothing installed
                // for the format, startActivity throws and the button looks broken.
                runCatching {
                    context.startActivity(Intent.createChooser(view, "Open with"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open with another app") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/**
 * Opens a web address in whatever the phone uses to read them.
 *
 * Launching ACTION_VIEW directly throws when nothing can handle it, which on a phone without
 * a browser (a locked-down work profile, some Android Go builds) took the whole app down from
 * a button that only meant "show me the paper's page". Failing quietly is the right outcome:
 * there is nothing the app can open it with.
 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun sharePaper(context: android.content.Context, paper: Paper) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, paper.displayTitle)
        putExtra(
            Intent.EXTRA_TEXT,
            "${paper.displayTitle}\n${paper.absUrl}" +
                "\n\nFound with Aftergleam, a privacy reader for arXiv scientific papers.",
        )
    }
    context.startActivity(Intent.createChooser(share, null))
}
