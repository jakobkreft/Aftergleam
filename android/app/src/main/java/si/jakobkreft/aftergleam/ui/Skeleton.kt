package si.jakobkreft.aftergleam.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Placeholder cards shown while the digest is being built.
 *
 * The app used to put an unranked digest on screen and then reorder it underneath the reader,
 * which was worse than waiting: the list you had started reading rearranged itself. Now that
 * ranking takes about ten seconds rather than seventy, waiting is fine, provided the wait
 * looks like something.
 *
 * Skeletons in the shape of the real cards say "papers are coming" without a spinner and
 * without pretending to show content. The pulse is a single alpha animation shared by every
 * placeholder, so it costs nothing.
 */
@Composable
fun DigestSkeleton(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        items(5) { CardBody(alpha) }
    }
}

/**
 * One placeholder card, for the case where results are already on screen and more are
 * still coming. The pulse is its own here rather than shared with the list above, which
 * costs one animation and saves threading the value through every caller.
 */
@Composable
fun SkeletonCard() {
    val transition = rememberInfiniteTransition(label = "skeleton-card")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    CardBody(alpha)
}

@Composable
private fun CardBody(alpha: Float) {
    Card(
        Modifier.fillMaxWidth(),
        elevation = flatCard(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(Modifier.padding(14.dp).alpha(alpha)) {
            Bar(0.45f, 10.dp)
            Spacer(Modifier.height(10.dp))
            Bar(0.95f, 16.dp)
            Spacer(Modifier.height(6.dp))
            Bar(0.7f, 16.dp)
            Spacer(Modifier.height(10.dp))
            Bar(0.9f, 10.dp)
            Spacer(Modifier.height(4.dp))
            Bar(0.6f, 10.dp)
        }
    }
}

@Composable
private fun Bar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Spacer(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant)
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    content: @Composable (Int) -> Unit,
) = items(count = count, key = { it }) { content(it) }
