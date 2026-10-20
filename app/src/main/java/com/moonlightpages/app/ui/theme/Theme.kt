package com.moonlightpages.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** How long the app takes to move from one theme's colours to the other's. */
private const val THEME_FADE_MILLIS = 250

@Composable
fun MoonlightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), paper: Color? = null,
    content: @Composable () -> Unit,
) {
    // The colours are taken from one theme to the other over a moment rather than changed in a single
    // frame. Swapping them at once reads as a flicker, because the app's surfaces are redrawn a little
    // apart from one another and the eye catches the seam — the bar above still dark while the page
    // below has gone light. Halfway through a change the two themes are simply blended.
    val progress by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(THEME_FADE_MILLIS), label = "theme",
    )
    val colors = blend(DayColors, NightColors, progress)
    val scheme = if (paper == null) colors else colors.copy(background = paper, surface = paper)
    val view = LocalView.current
    DisposableEffect(view, darkTheme) {
        val activity = view.context.activity()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !darkTheme
        controller?.isAppearanceLightNavigationBars = !darkTheme
        onDispose {
            controller?.let {
                if (oldStatus != null) it.isAppearanceLightStatusBars = oldStatus
                if (oldNavigation != null) it.isAppearanceLightNavigationBars = oldNavigation
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}

private fun Context.activity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}

/**
 * Day and night colours blended by [progress]: 0 is fully day, 1 fully night. Only the colours the
 * app itself paints are blended — anything left out is a colour nothing here draws, so it can change
 * with the theme without being seen to.
 */
private fun blend(day: ColorScheme, night: ColorScheme, progress: Float): ColorScheme {
    if (progress <= 0f) return day
    if (progress >= 1f) return night
    fun mix(start: Color, stop: Color): Color = lerp(start, stop, progress)
    return night.copy(
        primary = mix(day.primary, night.primary),
        onPrimary = mix(day.onPrimary, night.onPrimary),
        primaryContainer = mix(day.primaryContainer, night.primaryContainer),
        onPrimaryContainer = mix(day.onPrimaryContainer, night.onPrimaryContainer),
        secondary = mix(day.secondary, night.secondary),
        onSecondary = mix(day.onSecondary, night.onSecondary),
        secondaryContainer = mix(day.secondaryContainer, night.secondaryContainer),
        onSecondaryContainer = mix(day.onSecondaryContainer, night.onSecondaryContainer),
        background = mix(day.background, night.background),
        onBackground = mix(day.onBackground, night.onBackground),
        surface = mix(day.surface, night.surface),
        onSurface = mix(day.onSurface, night.onSurface),
        surfaceVariant = mix(day.surfaceVariant, night.surfaceVariant),
        onSurfaceVariant = mix(day.onSurfaceVariant, night.onSurfaceVariant),
        error = mix(day.error, night.error),
        onError = mix(day.onError, night.onError),
        errorContainer = mix(day.errorContainer, night.errorContainer),
        onErrorContainer = mix(day.onErrorContainer, night.onErrorContainer),
        outline = mix(day.outline, night.outline),
        outlineVariant = mix(day.outlineVariant, night.outlineVariant),
        scrim = mix(day.scrim, night.scrim),
        surfaceTint = mix(day.surfaceTint, night.surfaceTint),
    )
}
