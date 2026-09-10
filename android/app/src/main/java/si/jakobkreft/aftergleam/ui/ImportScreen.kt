package si.jakobkreft.aftergleam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import si.jakobkreft.aftergleam.data.LibraryImport

/**
 * The import, given the whole screen for as long as it runs.
 *
 * It used to be a line of text and a thin bar at the bottom of the subject picker, and the
 * next button was right above it. Tapping that button moved on to the survey, the bar went
 * with it, and the import was still running with nothing on screen to say so. Whether it
 * finished was invisible, and its arXiv lookups were competing with the survey's for the
 * same one-request-every-three-seconds budget.
 *
 * So it takes the screen. There is exactly one way out while it runs and it asks first,
 * which is the difference between leaving and losing ten minutes of work by accident.
 */
@Composable
fun ImportScreen(
    progress: LibraryImport.Progress?,
    result: LibraryImport.Result?,
    onStop: () -> Unit,
    onDone: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val running = result == null

    // The system back gesture is the accident this screen exists to prevent, so while the
    // import runs it asks the same question the Stop button does. Once it has finished the
    // handler stays registered and simply leaves: unregistering it instead sent back
    // straight past the app to the launcher, which is a strange way to be told the import
    // worked.
    BackHandler { if (running) confirming = true else onDone() }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Stop reading your library?") },
            text = {
                // Not "it will pick up where it left off": running it again starts from the
                // first entry. What is true is that nothing already matched is lost.
                Text("Everything matched so far is kept. You can run the import again later.")
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onStop() }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep going") }
            },
        )
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (result != null) Finished(result, onDone) else Running(progress, onStop = { confirming = true })
    }
}

@Composable
private fun Running(progress: LibraryImport.Progress?, onStop: () -> Unit) {
    val total = progress?.total ?: 0
    val done = progress?.done ?: 0
    val fraction by animateFloatAsState(
        targetValue = if (total == 0) 0f else done.toFloat() / total,
        label = "import",
    )

    Mark(Modifier.size(52.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        "Reading your library",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (total == 0) "Opening the file"
        else "$done of $total, ${progress?.matched ?: 0} matched on arXiv",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    if (total == 0) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(28.dp))

    // The title currently being looked up. Two lines are reserved for it whether or not
    // there is one, so the rest of the screen does not jump every three seconds.
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
        AnimatedContent(
            targetState = progress?.current.orEmpty(),
            transitionSpec = {
                fadeIn(tween(FADE_MILLIS, delayMillis = FADE_MILLIS)) togetherWith
                    fadeOut(tween(FADE_MILLIS))
            },
            label = "title",
        ) { title ->
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = LocalPaperFont.current,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Chatter(progress?.stage ?: LibraryImport.Stage.READING)

    Spacer(Modifier.height(32.dp))
    TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
}

/**
 * A line about what is happening, changed every few seconds.
 *
 * Windows says "optimising your experience" here, and the point of that is only to prove
 * the thing has not wedged. This app can prove it with the truth: the lookups really are
 * paced, the matches really do become likes, and the vocabulary really is being built out
 * of these abstracts. Inventing a reassuring lie would be the one dishonest sentence in an
 * app whose whole pitch is that it does not do anything behind your back.
 */
@Composable
private fun Chatter(stage: LibraryImport.Stage) {
    val lines = when (stage) {
        LibraryImport.Stage.READING -> listOf("Parsing the entries")
        LibraryImport.Stage.IDENTIFIERS -> listOf("Fetching the ones with an arXiv id")
        LibraryImport.Stage.DONE -> listOf("Finishing up")
        LibraryImport.Stage.TITLES -> TITLE_LINES
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(stage, lines.size) {
        index = 0
        while (lines.size > 1) {
            delay(CHATTER_MILLIS)
            index = (index + 1) % lines.size
        }
    }
    // Reserved height and a full width line, because these differ in length and a line that
    // resizes its own box drags the Stop button up and down the screen. The fade in waits
    // for the fade out to finish; overlapping them printed both sentences on top of each
    // other, which is the one thing a screen that exists to look alive must not do.
    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
        AnimatedContent(
            targetState = lines[index.coerceAtMost(lines.lastIndex)],
            transitionSpec = {
                fadeIn(tween(FADE_MILLIS, delayMillis = FADE_MILLIS)) togetherWith
                    fadeOut(tween(FADE_MILLIS))
            },
            label = "chatter",
        ) { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val CHATTER_MILLIS = 4_000L
private const val FADE_MILLIS = 300

/** All true of the title-matching pass, which is the long one. */
private val TITLE_LINES = listOf(
    "Searching arXiv by title",
    "arXiv allows one request every three seconds",
    "Matching each entry against the record",
    "Every match becomes a paper you liked",
    "Learning the words your field actually uses",
)

@Composable
private fun Finished(result: LibraryImport.Result, onDone: () -> Unit) {
    Mark(Modifier.size(52.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        when {
            result.stopped -> "Stopped"
            result.papers.isEmpty() -> "Nothing to import"
            else -> "Your library is in"
        },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        when {
            // A stopped run has no opinion about the entries it never reached, so it counts
            // what it looked at. Saying "no entry in that file matched" after examining nine
            // of thirty is a verdict on twenty one entries nobody checked.
            result.stopped -> "Looked at ${result.total} " +
                "${if (result.total == 1) "entry" else "entries"} and matched " +
                "${result.papers.size}."

            result.papers.isEmpty() ->
                "No entry in that file could be matched to an arXiv record. A BibTeX or " +
                    "RIS export from Zotero works best."

            else -> "${result.papers.size} of ${result.total} entries matched, and the " +
                "ranking has already learned from them."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    // The unmatched count only earns a line when something did match, because otherwise it
    // is the sentence above with a different number in it. A failure is always worth saying:
    // "could not be reached" is a different fact from "arXiv does not have it", and it is
    // the one the reader can do something about.
    if (result.failed > 0 || (result.unmatched > 0 && result.papers.isNotEmpty())) {
        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                if (result.unmatched > 0) append("${result.unmatched} had no arXiv record")
                if (result.unmatched > 0 && result.failed > 0) append(", and ")
                if (result.failed > 0) append("${result.failed} could not be reached")
                append(".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (result.stopped) {
        Spacer(Modifier.height(10.dp))
        Text(
            "What matched is saved. Running the import again starts from the top.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(28.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
}
