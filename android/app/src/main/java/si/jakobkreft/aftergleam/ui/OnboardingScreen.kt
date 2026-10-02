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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
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
        !survey.started && step == 0 -> Welcome(onDone = { step = 1 })

        !survey.started -> TopicPicker(
            selected = topics,
            onToggle = { key ->
                onTopics(if (key in topics) topics - key else topics + key)
            },
            onBack = { step = 0 },
            onSurvey = onStart,
            onSkip = onSkip,
            onImport = onImport,
            importSummary = importSummary,
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
    onImport: () -> Unit,
    importSummary: String?,
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

        // Importing a library is the same question as the survey, asked a faster way: both
        // are ways of telling the app what you read. It belongs beside them rather than on
        // a welcome screen, where it was a technical aside in the middle of a promise.
        // Choosing a file hands the whole screen to ImportScreen until it is finished.
        TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("Or import a BibTeX library")
        }
        importSummary?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
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
        // Without this the cards read as the app itself, a stack of papers to swipe through
        // for ever. One line says what the answers are for and that something comes after.
        Text(
            "Each answer teaches your ranking. Your first digest comes next.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val total = survey.planned
        Text(
            buildString {
                append(if (total > 0) "${survey.seen + 1} of $total" else "Paper ${survey.seen + 1}")
                append(" · ${survey.liked.size} kept")
                if (total == 0 && survey.loading) append(" · more loading")
            },
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = {
                val of = if (total > 0) total else survey.expected
                if (of == 0) 0f else (survey.seen.toFloat() / of).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        // One scroll position per paper. Held for the whole screen, it carried over: a reader
        // who scrolled down an abstract and answered saw the next paper from the same depth,
        // with its title out of sight.
        val scroll = key(paper.id) { rememberScrollState() }

        // The card takes the space that is left, so the buttons stay put.
        Card(
            Modifier.weight(1f).fillMaxWidth(),
            elevation = flatCard(),
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(scroll)) {
                Text(
                    paper.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = LocalPaperFont.current,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(paper.displayCategories.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                // Between the small size it began at, which was hard to read on the one screen
                // where reading the abstract is the whole task, and the page size, which left
                // little of a long abstract on screen at once.
                Text(
                    paper.displayAbstract,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
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
        // Always there, so nobody is held in the survey. The chosen subjects already rank a
        // first digest on their own; from the third keeper on, the answers do too, and the
        // wording says which of the two the reader is choosing.
        TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (survey.enough) "That is enough, build my digest"
                else "Skip the rest, build my digest"
            )
        }
    }
}

@Composable
private fun Finished(survey: SurveyState, onFinish: () -> Unit) {
    // Nothing was ever asked, so there is no score to report. A law reader saw "You kept 0
    // of 0. That is a thin start", which reads as a judgement on answers they never gave:
    // the survey draws its cards from papers already on the device, and a field the app had
    // not fetched yet has none to draw.
    if (survey.seen == 0) {
        Text("Nothing to ask about yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            // A server that refused is not a quiet field. Computer vision posts hundreds of
            // papers a day, and telling its reader that smaller fields post a few a week, when
            // arXiv had simply answered "rate exceeded", blamed their subject for an outage.
            if (survey.unreachable.isNotEmpty())
                "${survey.unreachable.joinToString(" and ")} did not answer, so there were no " +
                    "papers to ask you about. This is usually temporary, and your first " +
                    "digest will ask again."
            else if (survey.seeded > 0)
                "Your subjects had no papers on the device to ask you about, so the survey " +
                    "was skipped. The ${survey.seeded} from your library are enough to start."
            else
                "Your subjects had no papers on the device to ask you about, so the survey " +
                    "was skipped. Smaller fields post a few papers a week, and the first " +
                    "digest will fetch them. Rate a few there and the ranking starts from " +
                    "the same place.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Show me today") }
        return
    }

    Text("Ready", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        buildString {
            append("You kept ${survey.liked.size} of ${survey.seen}")
            if (survey.seeded > 0) append(", and your library added ${survey.seeded}")
            append(". ")
            append(
                if (survey.enough)
                    "That is enough to rank your first digest, and it sharpens every time " +
                        "you rate something."
                else
                    "That is a thin start, so the first digest leans on recency and venue " +
                        "until you have rated a few more."
            )
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Show me today") }
}
