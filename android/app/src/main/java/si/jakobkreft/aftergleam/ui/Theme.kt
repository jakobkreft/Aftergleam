package si.jakobkreft.aftergleam.ui

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

/**
 * The app's own colours and letterforms.
 *
 * Two decisions live here, and both were previously made by the phone.
 *
 * **Colour.** The app used Material You throughout, which derives every surface from the
 * wallpaper. That is a fine default for a launcher and a poor one for a reader: the ground
 * that a thousand words of abstract sit on should not change because somebody swapped their
 * wallpaper for a photograph of a sunset. It also crashed on Android 8 to 11, where the
 * dynamic colour APIs do not exist and were being called anyway.
 *
 * **Type.** Papers are set in a serif, and this is an app for reading papers. The system
 * serif is used rather than a bundled face: it costs no download, no licence and no APK,
 * and on Android it is Noto Serif, which is a perfectly good text face.
 *
 * Both are defaults, not rules. Someone who wants their wallpaper's colours or the phone's
 * usual letterforms can have them.
 */

/**
 * Warm paper, deep green ink.
 *
 * The green and the paper are the icon's, so the app and its icon are recognisably the same
 * thing. The reading ground is lighter than the icon's cream: at icon size that warmth is a
 * pleasant accent, and at full-screen size it would be a tiring one. Every foreground and
 * background pair here clears WCAG AA, and most clear AAA.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF0B6B47),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6E7CD),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF55604F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E5D0),
    onSecondaryContainer = Color(0xFF131F11),
    tertiary = Color(0xFF3B5E8C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5E0F5),
    onTertiaryContainer = Color(0xFF001B3C),
    error = Color(0xFF8C2F2A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF7DAD6),
    onErrorContainer = Color(0xFF3B0906),
    background = Color(0xFFFAF4EA),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFAF4EA),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFE6DDCC),
    onSurfaceVariant = Color(0xFF4E4739),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7EFE2),
    surfaceContainer = Color(0xFFF1E8DA),
    surfaceContainerHigh = Color(0xFFEBE1D1),
    surfaceContainerHighest = Color(0xFFE5DAC8),
    outline = Color(0xFF7C7566),
    outlineVariant = Color(0xFFCFC5B3),
    inverseSurface = Color(0xFF31302A),
    inverseOnSurface = Color(0xFFF4EDE1),
    inversePrimary = Color(0xFF9BB884),
    scrim = Color(0xFF000000),
)

/**
 * Ink on warm charcoal.
 *
 * Not paper: a dark theme that tries to be paper ends up grey and lifeless. What carries
 * over is the warmth, so that switching between the two feels like the same app at a
 * different hour rather than two different ones. Not pure black either, which smears on
 * OLED as the screen scrolls and is harsher than anything printed.
 */
private val Dark = darkColorScheme(
    // Warm moss rather than the mint this used to be. That was a 54% saturated teal, which
    // on a warm charcoal ground read as the one cold thing on the screen and as much
    // brighter than anything it labelled. This sits in the same green family as the light
    // theme's ink, at half the saturation and turned towards olive, so it belongs to the
    // background it sits on. Still 8.4:1 against it.
    primary = Color(0xFF9BB884),
    onPrimary = Color(0xFF1F2A16),
    primaryContainer = Color(0xFF38452C),
    onPrimaryContainer = Color(0xFFB9D6A1),
    secondary = Color(0xFFBCCBB4),
    onSecondary = Color(0xFF273423),
    secondaryContainer = Color(0xFF3D4A39),
    onSecondaryContainer = Color(0xFFD8E5D0),
    tertiary = Color(0xFFA9C4EC),
    onTertiary = Color(0xFF0B2F52),
    tertiaryContainer = Color(0xFF24456B),
    onTertiaryContainer = Color(0xFFD5E0F5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5F1512),
    errorContainer = Color(0xFF7B2B26),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF16140F),
    onBackground = Color(0xFFE9E2D6),
    surface = Color(0xFF16140F),
    onSurface = Color(0xFFE9E2D6),
    surfaceVariant = Color(0xFF4A4437),
    onSurfaceVariant = Color(0xFFCFC6B4),
    surfaceContainerLowest = Color(0xFF100E0A),
    surfaceContainerLow = Color(0xFF1E1B15),
    surfaceContainer = Color(0xFF231F19),
    surfaceContainerHigh = Color(0xFF2E2A23),
    surfaceContainerHighest = Color(0xFF39352C),
    outline = Color(0xFF98907F),
    outlineVariant = Color(0xFF4A4437),
    inverseSurface = Color(0xFFE9E2D6),
    inverseOnSurface = Color(0xFF31302A),
    inversePrimary = Color(0xFF4A6135),
    scrim = Color(0xFF000000),
)

/** The whole type scale in one family, so nothing is left behind in the default sans. */
private fun Typography.inFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/** Dynamic colour is an Android 12 feature. Below that the switch is not offered at all. */
val dynamicColourAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The face for a paper's own words: its title and its abstract.
 *
 * Separate from the theme's typography, which dresses the app around them. A journal sets
 * its articles in a serif and its running heads and page numbers in whatever it likes, and
 * the distinction is useful here for the same reason: it marks where the app stops talking
 * and the paper starts.
 */
val LocalPaperFont = staticCompositionLocalOf<FontFamily> { FontFamily.Serif }

@Composable
fun AftergleamTheme(
    dark: Boolean,
    dynamic: Boolean,
    paperSerif: Boolean,
    interfaceSerif: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colours = when {
        // The guard is the point: calling these below Android 12 is a crash, and the app
        // was doing it unconditionally with a minimum of Android 8.
        dynamic && dynamicColourAvailable ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    // The app draws edge to edge, so the system's own status bar icons sit on the app's
    // background. Nothing told them which way to go, and on the light palette they came out
    // white on cream: a clock and a battery you had to hunt for.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }

    val base = MaterialTheme.typography
    MaterialTheme(
        colorScheme = colours,
        typography = if (interfaceSerif) base.inFamily(FontFamily.Serif) else base,
    ) {
        CompositionLocalProvider(
            LocalPaperFont provides
                if (paperSerif) FontFamily.Serif else FontFamily.SansSerif,
            content = content,
        )
    }
}
