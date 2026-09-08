package si.jakobkreft.aftergleam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import si.jakobkreft.aftergleam.ui.FeedScreen
import si.jakobkreft.aftergleam.ui.FeedViewModel
import si.jakobkreft.aftergleam.ui.OnboardingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
private fun App(vm: FeedViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val dark = isSystemInDarkTheme()

    MaterialTheme(
        colorScheme = if (dark) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    ) {
        // Scaffold supplies the insets. Under three-button navigation the bar is about
        // 48dp tall and will cover the last card otherwise.
        Scaffold { inner ->
            val mod = Modifier.padding(inner)
            if (!state.onboarded) {
                androidx.compose.foundation.layout.Box(mod) {
                    OnboardingScreen(
                        selected = state.categories,
                        onToggle = { cat ->
                            val next = state.categories.toMutableSet()
                            if (!next.remove(cat)) next.add(cat)
                            vm.setCategories(next)
                        },
                        onDone = vm::finishOnboarding,
                    )
                }
            } else {
                androidx.compose.foundation.layout.Box(mod) {
                    FeedScreen(
                        state = state,
                        onRate = vm::rate,
                        onSave = vm::toggleSave,
                        onOpen = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        onRerank = vm::rerank,
                        onRefresh = vm::refresh,
                    )
                }
            }
        }
    }
}
