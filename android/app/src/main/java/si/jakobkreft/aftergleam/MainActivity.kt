package si.jakobkreft.aftergleam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import si.jakobkreft.aftergleam.ui.FeedScreen
import si.jakobkreft.aftergleam.ui.FeedViewModel
import si.jakobkreft.aftergleam.ui.OnboardingScreen
import si.jakobkreft.aftergleam.ui.SavedScreen
import si.jakobkreft.aftergleam.ui.TuneScreen
import si.jakobkreft.aftergleam.work.DailyDigestWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

private enum class Tab(val label: String) { TODAY("Today"), SAVED("Saved"), TUNE("Tune") }

@Composable
private fun App(vm: FeedViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val dark = isSystemInDarkTheme()
    var tab by remember { mutableStateOf(Tab.TODAY) }

    // Any text MIME type: exports are variously text/plain, text/x-bibtex or octet-stream,
    // and a narrow filter would hide the user's own file from them in the picker.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* declining is fine: the digest still builds, it just does not announce itself */ }

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
                        selected = state.categories,
                        onToggle = { cat ->
                            val next = state.categories.toMutableSet()
                            if (!next.remove(cat)) next.add(cat)
                            vm.setCategories(next)
                        },
                        onImport = { pickLibrary.launch(arrayOf("*/*")) },
                        importProgress = state.importProgress,
                        importSummary = state.importSummary,
                        onDone = vm::finishOnboarding,
                    )
                }
            }
            return@MaterialTheme
        }

        LaunchedEffect(tab, state.reactions) { if (tab == Tab.SAVED) vm.loadSaved() }

        // Scheduling is idempotent (UPDATE on a unique name), so doing it on every launch
        // also repairs the schedule if the user cleared app data or rebooted.
        LaunchedEffect(Unit) {
            DailyDigestWorker.schedule(context)
            if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
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
                                        Tab.SAVED -> Icons.Filled.Star
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
            val open: (String) -> Unit = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            Box(Modifier.padding(inner)) {
                when (tab) {
                    Tab.TODAY -> FeedScreen(
                        state = state,
                        onRate = vm::rate,
                        onSave = vm::toggleSave,
                        onOpen = open,
                        onRerank = vm::rerank,
                        onRefresh = vm::refresh,
                    )
                    Tab.SAVED -> SavedScreen(
                        papers = state.saved,
                        onOpen = open,
                        onUnsave = vm::toggleSave,
                    )
                    Tab.TUNE -> TuneScreen(
                        digestSize = vm.currentDigestSize(),
                        quality = vm.currentQualityWeight(),
                        exploration = vm.currentExplorationRate(),
                        diversity = vm.currentDiversity(),
                        ratedCount = state.ratedCount,
                        importProgress = state.importProgress,
                        importSummary = state.importSummary,
                        onDigestSize = vm::setDigestSize,
                        onQuality = vm::setQualityWeight,
                        onExploration = vm::setExplorationRate,
                        onDiversity = vm::setDiversity,
                        onPickLibrary = { pickLibrary.launch(arrayOf("*/*")) },
                        onReset = vm::resetModel,
                        onApply = { vm.rerank(); tab = Tab.TODAY },
                    )
                }
            }
        }
    }
}
