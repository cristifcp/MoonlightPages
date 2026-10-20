package com.moonlightpages.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The two themes, both cut from the app's own picture: a daylit page and a night sky.
 *
 * Daylight is warm paper with ink that is nearly navy rather than black, so text and paper stay the
 * calmest things on the screen. Night is the deep blue of the sky in that picture, with moonlight —
 * a soft blue-white — for anything the reader can act on, and a warmer tone for the second colour so
 * the two are never confused. Neither theme is green; the only green the app ever had was the old
 * lamp branding, which is gone along with the lamp.
 */
internal val DayColors = lightColorScheme(
    primary = Color(0xFF3B4E6E), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4F0), onPrimaryContainer = Color(0xFF1B2740),
    secondary = Color(0xFF5A6472), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E3D9), onSecondaryContainer = Color(0xFF2B2F36),
    background = Color(0xFFF7F5F0), onBackground = Color(0xFF1E2430),
    surface = Color(0xFFF7F5F0), onSurface = Color(0xFF1E2430),
    surfaceVariant = Color(0xFFE9E6DE), onSurfaceVariant = Color(0xFF565F6B),
    outline = Color(0xFF8A919C), outlineVariant = Color(0xFFDCD9D1),
)

internal val NightColors = darkColorScheme(
    primary = Color(0xFFA8C7F0), onPrimary = Color(0xFF152238),
    primaryContainer = Color(0xFF2A3A55), onPrimaryContainer = Color(0xFFDCE7F7),
    secondary = Color(0xFFD9C9A3), onSecondary = Color(0xFF322A18),
    secondaryContainer = Color(0xFF343B4C), onSecondaryContainer = Color(0xFFEDE5D2),
    background = Color(0xFF141A26), onBackground = Color(0xFFE4E8EF),
    surface = Color(0xFF141A26), onSurface = Color(0xFFE4E8EF),
    surfaceVariant = Color(0xFF242C3B), onSurfaceVariant = Color(0xFFBAC3D0),
    outline = Color(0xFF7C8697), outlineVariant = Color(0xFF343C4B),
)
