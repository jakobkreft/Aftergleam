package si.jakobkreft.aftergleam.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import si.jakobkreft.aftergleam.R

/**
 * The three things worth knowing before anybody is asked for anything.
 *
 * An onboarding screen is a promise, and this app's promises are unusual enough to be worth
 * making out loud: the ranking is a model that lives on the phone, nothing about the reading
 * goes anywhere, and the papers can be read and kept in the app rather than thrown at a
 * browser. Each page says one of them in a sentence.
 *
 * Swipeable, skippable, and over in about fifteen seconds. It is deliberately not a feature
 * tour: a reader who wants to know what the diversity slider does will find it in settings,
 * and one who is still deciding whether to spend a minute on the survey needs three reasons,
 * not thirty.
 */
private data class Slide(
    val headline: String,
    val body: String,
)

private val SLIDES = listOf(
    Slide(
        "The day's research, without the rest of it.",
        "A few hundred new papers appear every morning. Aftergleam reads them and puts the " +
            "handful worth your time at the top.",
    ),
    Slide(
        "It learns from you, on your phone.",
        "React to a paper and the ranking shifts. The model is trained here, from what you " +
            "read. There is no account, no server, and nothing about you to leak.",
    ),
    Slide(
        "Read it here, and keep it.",
        "Open the PDF without leaving the app. Save what matters, take it offline, and it " +
            "is still there on a train with no signal.",
    ),
)

@Composable
fun Welcome(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { SLIDES.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            // Always available. Somebody who wants the app rather than the explanation
            // should not have to swipe through three screens to say so.
            TextButton(onClick = onDone) { Text("Skip") }
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            val slide = SLIDES[index]
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
            ) {
                if (index == 0) {
                    Mark(Modifier.size(96.dp))
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Aftergleam",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Serif,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // The serif is the app's own voice, and this is the app introducing itself.
                Text(
                    slide.headline,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    slide.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SLIDES.indices.forEach { i ->
                val active = i == pager.currentPage
                val alpha by animateFloatAsState(if (active) 1f else 0.28f, label = "dot")
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                )
            }
        }

        Button(
            onClick = {
                if (last) onDone()
                else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (last) "Choose your subjects" else "Next")
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** The app's own mark, which is already on the device as the launcher icon. */
@Composable
internal fun Mark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.mipmap.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier,
    )
}
