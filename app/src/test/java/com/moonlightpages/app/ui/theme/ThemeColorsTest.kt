package com.moonlightpages.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The two themes: one for daylight, one for night, and neither of them green. */
class ThemeColorsTest {
    /** The strongest channel of a colour: which of red, green and blue it mostly is. */
    private fun strongest(colour: Color) = maxOf(colour.red, colour.green, colour.blue)

    private fun isGreenest(colour: Color) = colour.green == strongest(colour)

    @Test fun oneThemeIsForDaylightAndTheOtherForNight() {
        assertTrue("The day theme is paper, not a dark room",
            DayColors.background.luminance() > 0.7f)
        assertTrue("The night theme is the night sky, not a bright page",
            NightColors.background.luminance() < 0.1f)
        assertTrue("Day starts lighter than night and stays that way",
            DayColors.background.luminance() > NightColors.background.luminance())
        assertNotEquals("Light and dark cannot be the same theme",
            DayColors.background, NightColors.background)
    }

    @Test fun aPageIsReadableInBothThemes() {
        // Body text against its own background, in each theme: nothing near invisible in either.
        val dayInk = (DayColors.onBackground.luminance() - DayColors.background.luminance())
        val nightInk = (NightColors.onBackground.luminance() - NightColors.background.luminance())
        assertTrue("Day ink stands off the page", abs(dayInk) > 0.5f)
        assertTrue("Night ink stands off the page", abs(nightInk) > 0.5f)
    }

    @Test fun neitherThemeIsGreen() {
        fun check(name: String, scheme: ColorScheme) {
            for ((what, colour) in listOf(
                "primary" to scheme.primary,
                "background" to scheme.background,
                "surface" to scheme.surface,
                "secondaryContainer" to scheme.secondaryContainer,
            )) {
                assertTrue("$name's $what is green again: $colour", !isGreenest(colour))
            }
        }
        check("the day theme", DayColors)
        check("the night theme", NightColors)
    }
}
