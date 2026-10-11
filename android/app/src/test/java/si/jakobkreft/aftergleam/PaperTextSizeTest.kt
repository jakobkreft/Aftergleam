package si.jakobkreft.aftergleam

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.ui.PaperTextSize
import si.jakobkreft.aftergleam.ui.forPaper
import si.jakobkreft.aftergleam.ui.paperLinesFor

/** The paper text size setting: what it scales, how far, and what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaperTextSizeTest {

    @Test
    fun `letters and line spacing grow together, in the paper's face`() {
        val title = TextStyle(fontSize = 16.sp, lineHeight = 24.sp).forPaper(FontFamily.Serif, 1.5f)
        assertEquals(24f, title.fontSize.value, 1e-4f)
        assertEquals("lines would touch on the old spacing", 36f, title.lineHeight.value, 1e-4f)
        assertEquals(FontFamily.Serif, title.fontFamily)
        val bare = TextStyle().forPaper(FontFamily.SansSerif, 1.3f)
        assertEquals("an unset size stays unset rather than failing", TextUnit.Unspecified, bare.fontSize)
    }

    @Test
    fun `titles keep room for the same words at a larger size`() {
        assertEquals(listOf(3, 3, 3, 4, 4, 4, 5),
            listOf(0.9f, 1f, 1.05f, 1.1f, 1.2f, 1.3f, 1.5f).map { paperLinesFor(3, it) })
        assertEquals("never fewer lines than at the app's size", 2, paperLinesFor(2, 0.9f))
    }

    @Test
    fun `only the offered sizes are used, whatever is stored`() {
        assertEquals("thirteen stops 5% apart, from 90% to half as large again",
            listOf(90, 95, 100, 105, 110, 115, 120, 125, 130, 135, 140, 145, 150), PaperTextSize.SIZES)
        assertEquals(150, PaperTextSize.nearest(400))
        assertEquals(90, PaperTextSize.nearest(10))
        assertEquals(120, PaperTextSize.nearest(118))
        assertEquals("a size stored by the ten-step slider is still offered", 130, PaperTextSize.nearest(130))
        assertEquals(105, PaperTextSize.nearest(104))
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        assertEquals("the app's own size until the reader picks another", 100, prefs.paperTextSize)
    }
}
