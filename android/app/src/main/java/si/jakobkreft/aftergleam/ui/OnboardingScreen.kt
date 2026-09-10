package si.jakobkreft.aftergleam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import si.jakobkreft.aftergleam.data.LibraryImport
import si.jakobkreft.aftergleam.data.Topics

/**
 * Onboarding as a taste survey rather than a category form.
 *
 * The previous screen asked people to commit to arXiv categories before they had seen
 * anything. That is the wrong question: a category is a filter over thousands of unrelated
 * papers, not a taste, and people are poor at naming their own interests in the abstract.
 * Here the app shows real recent papers and asks the one question anyone answers reliably,
 * would you read this, then infers the categories from the answers.
 *
 * Everything is skippable, nothing is permanent, and the model that results is visible and
 * editable afterwards in the library. This audience is technical and sceptical; the way to
 * earn a first session is to ask for little and show the working.
 */
@Composable
fun OnboardingScreen(
    survey: SurveyState,
    topics: Set<String>,
    importProgress: LibraryImport.Progress?,
    importSummary: String?,
    onTopics: (Set<String>) -> Unit,
    onStart: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    when {
        !survey.started && step == 0 -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) { Intro({ step = 1 }, onImport, importProgress, importSummary) }

        !survey.started -> TopicPicker(
            selected = topics,
            onToggle = { key ->
                onTopics(if (key in topics) topics - key else topics + key)
            },
            onBack = { step = 0 },
            onSurvey = onStart,
            onSkip = onSkip,
        )

        survey.deck.isNotEmpty() -> Question(survey, onAnswer, onBack, onFinish)

        // Only reachable if the reader outpaces the loader, or on the very first fetch.
        survey.waiting -> Column(
            Modifier.fillMaxSize().padding(20.dp)
        ) { Preparing(survey) }

        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) { Finished(survey, onFinish) }
    }
}

@Composable
private fun Intro(
    onStart: () -> Unit,
    onImport: () -> Unit,
    importProgress: LibraryImport.Progress?,
    importSummary: String?,
) {
    Text("Aftergleam", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        "A short digest of new arXiv papers each day, ranked by what you actually read.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "First pick the subjects you work in. Then, if you like, judge a few real papers " +
            "from those subjects, which sharpens the ranking considerably. About a minute " +
            "in total.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Nothing you read leaves the device. There is no account and no server. Every " +
            "answer here is visible and changeable later.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text("Choose my subjects")
    }
    Spacer(Modifier.height(12.dp))

    if (importProgress != null) {
        Text(
            "Reading your library: ${importProgress.done} of ${importProgress.total}, " +
                "matched ${importProgress.matched}",
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
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("I have a BibTeX library")
        }
        Text(
            "Faster and more accurate than the survey, if you have a Zotero export handy.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
    importSummary?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Subjects first, papers second.
 *
 * This is the whole archive, not one corner of it. Each topic carries vocabulary drawn from
 * how its abstracts are actually written, so choosing a few is already enough to rank a
 * first digest, and it decides which papers the survey will ask about.
 */
@Composable
private fun TopicPicker(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onSurvey: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Text("${selected.size} chosen", style = MaterialTheme.typography.labelMedium)
        }
        Text("What do you work on?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick as many as apply. You can change all of this later.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TopicFields(selected = selected, onToggle = onToggle)
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSurvey,
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (selected.isEmpty()) "Pick at least one subject"
                else "Now show me some papers"
            )
        }
        if (selected.isNotEmpty()) {
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Skip the papers, these subjects are enough")
            }
        }
    }
}

/** Shown only while the deck is momentarily empty, which is the first fetch or a fast reader. */
@Composable
private fun Preparing(survey: SurveyState) {
    Text(
        if (survey.seen == 0) "Finding a first paper" else "Fetching the next one",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "arXiv allows one request every three seconds. The rest load while you read, so " +
            "this is the only wait.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

/**
 * One question, with the answers pinned to the bottom.
 *
 * Abstracts vary in length, so laying the buttons out after the card made them jump between
 * questions. In a flow whose whole point is a fast rhythm of yes and no, a target that moves
 * every time is the difference between a minute and a chore.
 */
@Composable
private fun Question(
    survey: SurveyState,
    onAnswer: (Boolean) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val (_, paper) = survey.deck.first()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Would you read this?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // A one-tap survey with no way back turns a slip into a training example the
            // reader cannot find again.
            if (survey.canGoBack) {
                TextButton(onClick = onBack) { Text("Undo") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Paper ${survey.seen + 1} · ${survey.liked.size} kept" +
                if (survey.loading) " · more loading" else "",
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = {
                if (survey.expected == 0) 0f
                else (survey.seen.toFloat() / survey.expected).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        // The card takes the space that is left, so the buttons stay put.
        Card(
            Modifier.weight(1f).fillMaxWidth(),
            elevation = flatCard(),
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(
                    paper.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = LocalPaperFont.current,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(paper.displayCategories.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    paper.displayAbstract,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = LocalPaperFont.current,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f)) {
                Text("Not for me")
            }
            Button(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f)) {
                Text("Yes")
            }
        }
        // Offered from the third keeper on: that is when ranking switches on, and there is
        // no reason to make someone answer questions they have already answered enough of.
        if (survey.enough) {
            TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text("That is enough, build my digest")
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Finished(survey: SurveyState, onFinish: () -> Unit) {
    Text("Ready", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        if (survey.enough)
            "You kept ${survey.liked.size} of ${survey.seen}. That is enough to rank your " +
                "first digest, and it sharpens every time you rate something."
        else
            "You kept ${survey.liked.size} of ${survey.seen}. That is a thin start, so the " +
                "first digest leans on recency and venue until you have rated a few more.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Show me today") }
}
