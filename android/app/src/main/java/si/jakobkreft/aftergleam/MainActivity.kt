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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import si.jakobkreft.aftergleam.ui.DetailScreen
import si.jakobkreft.aftergleam.ui.FeedScreen
import si.jakobkreft.aftergleam.ui.FeedViewModel
import si.jakobkreft.aftergleam.ui.OnboardingScreen
import si.jakobkreft.aftergleam.ui.LibraryScreen
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

private enum class Tab(val label: String) {
    TODAY("Today"), SEARCH("Search"), LIBRARY("Library"), TUNE("Tune")
}

@Composable
private fun App(vm: FeedViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val dark = when (state.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    var tab by remember { mutableStateOf(Tab.TODAY) }

    // Any text MIME type: exports are variously text/plain, text/x-bibtex or octet-stream,
    // and a narrow filter would hide the user's own file from them in the picker.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* declining is fine: the digest still builds, it just does not announce itself */ }

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
                        importProgress = state.importProgress,
                        importSummary = state.importSummary,
                        onStart = vm::startSurvey,
                        onAnswer = vm::answerSurvey,
                        onFinish = vm::finishSurvey,
                        onImport = { pickLibrary.launch(arrayOf("*/*")) },
                        onSkip = {
                            vm.setCategories(setOf("cs.LG"))
                            vm.finishOnboarding()
                        },
                    )
                }
            }
            return@MaterialTheme
        }

        LaunchedEffect(tab, state.reactions) { if (tab == Tab.LIBRARY) vm.loadLibrary() }

        // Scheduling is idempotent (UPDATE on a unique name), so doing it on every launch
        // also repairs the schedule if the user cleared app data or rebooted.
        LaunchedEffect(Unit) {
            DailyDigestWorker.schedule(context, vm.currentDigestHour())
            MetadataRefreshWorker.schedule(context)
            if (vm.currentReminderEnabled()) {
                ReminderWorker.schedule(context, vm.currentReminderHour())
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val detail = state.detail
        if (detail != null) {
            BackHandler { vm.closeDetail() }
            Scaffold { inner ->
                Box(Modifier.padding(inner)) {
                    DetailScreen(
                        paper = detail,
                        reaction = state.reactions[detail.id] ?: si.jakobkreft.aftergleam.data.Reaction.NONE,
                        // A paper opened from search is not in today's digest, so looking
                        // only at `cards` reported every search result as 0% interest.
                        confidence = state.cards.firstOrNull { it.paper.id == detail.id }?.relevance
                            ?: state.searchHits.firstOrNull { it.paper.id == detail.id }?.interest
                            ?: 0f,
                        modelActive = state.modelActive,
                        upvotes = state.attention[detail.id] ?: 0,
                        onRate = { vm.rate(detail.id, it) },
                        onSave = { vm.toggleSave(detail.id) },
                        onOpenExternal = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        onBack = vm::closeDetail,
                    )
                }
            }
            return@MaterialTheme
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.TODAY -> Icons.Filled.List
                                        Tab.SEARCH -> Icons.Filled.Search
                                        Tab.LIBRARY -> Icons.Filled.Star
                                        Tab.TUNE -> Icons.Filled.Settings
                                    },
                                    contentDescription = t.label,
                                )
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
                when (tab) {
                    Tab.TODAY -> FeedScreen(
                        state = state,
                        onRate = vm::rate,
                        onSave = vm::toggleSave,
                        onOpen = vm::openDetail,
                        onRerank = vm::rerank,
                        onRefresh = vm::refresh,
                        onDismissResurfaced = vm::dismissResurfaced,
                    )
                    Tab.SEARCH -> SearchScreen(
                        state = state,
                        onQuery = vm::setSearchQuery,
                        onSubmit = vm::runSearch,
                        onPersonalisation = vm::setPersonalisation,
                        onOpen = vm::openDetail,
                    )
                    Tab.LIBRARY -> LibraryScreen(
                        saved = state.saved,
                        downloaded = state.downloaded,
                        rated = state.ratedPapers,
                        onOpen = vm::openDetail,
                        onUnsave = vm::toggleSave,
                        onRate = vm::rate,
                    )
                    Tab.TUNE -> TuneScreen(
                        digestSize = vm.currentDigestSize(),
                        quality = vm.currentQualityWeight(),
                        exploration = vm.currentExplorationRate(),
                        diversity = vm.currentDiversity(),
                        digestHour = vm.currentDigestHour(),
                        notifyEnabled = vm.currentNotifyEnabled(),
                        reminderHour = vm.currentReminderHour(),
                        reminderEnabled = vm.currentReminderEnabled(),
                        theme = state.theme,
                        ratedCount = state.ratedCount,
                        importProgress = state.importProgress,
                        importSummary = state.importSummary,
                        onDigestSize = vm::setDigestSize,
                        onQuality = vm::setQualityWeight,
                        onExploration = vm::setExplorationRate,
                        onDiversity = vm::setDiversity,
                        onDigestHour = { h ->
                            vm.setDigestHour(h)
                            // Rescheduling is idempotent on a unique work name, so saving
                            // simply moves the next run rather than stacking jobs.
                            DailyDigestWorker.schedule(context, h)
                        },
                        onNotifyEnabled = vm::setNotifyEnabled,
                        onTheme = vm::setTheme,
                        onReminder = { on, hour ->
                            vm.setReminderEnabled(on)
                            vm.setReminderHour(hour)
                            if (on) ReminderWorker.schedule(context, hour)
                            else ReminderWorker.cancel(context)
                        },
                        onPickLibrary = { pickLibrary.launch(arrayOf("*/*")) },
                        onExport = {
                            exportBackup.launch(si.jakobkreft.aftergleam.data.Backup.suggestedFileName())
                        },
                        onRestore = { restoreBackup.launch(arrayOf("*/*")) },
                        backupSummary = state.backupSummary,
                        onReset = vm::resetModel,
                        onApply = { vm.rerank(); tab = Tab.TODAY },
                    )
                }
            }
        }
    }
}
