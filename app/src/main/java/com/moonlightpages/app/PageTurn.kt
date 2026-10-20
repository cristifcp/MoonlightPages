package com.moonlightpages.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * A page turn reads as a sheet of paper being laid over the page behind it. The page arriving
 * travels in from the far side of the screen and takes it; the page being left is shoved a little
 * way aside, dims as the new sheet passes over it, and carries the shadow of the arriving edge
 * where it lands. The whole turn is quick and it eases into place.
 *
 * The arriving page is the reader's own page, moved across the screen by the reader, so the words
 * on it are drawn where they belong at every frame instead of appearing behind a sweeping line.
 */

/** How long a page takes to travel over the one it replaces. */
internal const val TURN_MILLIS = 450

/** A swipe has to carry the finger this far across before letting go turns the page. */
internal const val SWIPE_TURN_SHARE = 0.15f

/** A swipe let go at least this fast turns the page however little of it had travelled. */
internal const val SWIPE_FLING = 0.25f

/** How dark the page left behind goes by the time it has been covered completely. */
internal const val VEIL_END = 0.14f

/** How far the page left behind is shoved aside, as a share of the page width. */
internal const val NUDGE_SHARE = 0.15f

/** Share of the page width that the arriving sheet's edge shades, and how dark that shade is. */
internal const val FOLD_SHARE = 0.13f
internal const val FOLD_ALPHA = 0.07f

/** Marks the layer while it is on screen, so the turn can be checked without watching it. */
internal const val PAGE_TURN_TAG = "page-turn"

/** The page being left behind, held for as long as the page replacing it takes to cover it. */
internal data class PageTurn(val page: ImageBitmap, val direction: Int, val serial: Int)

/** Where the arriving page's leading edge has reached, in pixels across the page. */
internal fun pageTurnEdge(width: Float, direction: Int, progress: Float): Float =
    if (direction > 0) width * (1f - progress) else width * progress

/**
 * How far the arriving page still has to travel: one whole page width, from off the far side of the
 * screen to the place it belongs.
 */
internal fun pageTurnTravel(width: Float, direction: Int, progress: Float): Float =
    (if (direction > 0) 1f else -1f) * width * (1f - progress.coerceIn(0f, 1f))

/** How far the page left behind has been shoved aside by the sheet arriving over it. */
internal fun pageTurnNudge(width: Float, progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return width * NUDGE_SHARE * (1f - remaining * remaining * remaining)
}

/** How dark the page left behind is drawn, from the first frame of the turn to the last. */
internal fun pageTurnVeil(progress: Float): Float = VEIL_END * progress.coerceIn(0f, 1f)

/**
 * Whether a swipe let go turns the page. A swipe that has carried the finger past
 * [SWIPE_TURN_SHARE] of the page's width turns; so does a fast flick however short it was.
 */
internal fun swipeCompletes(progress: Float, pageWidthsPerSecond: Float): Boolean =
    progress >= SWIPE_TURN_SHARE || pageWidthsPerSecond >= SWIPE_FLING

/**
 * The page being left behind, held exactly where it was: this layer covers the page itself, so the
 * copy lands on the page it came from without anyone having to work out where that page sits. Only
 * the part the arriving page has not reached is drawn.
 */
@Composable
internal fun PageTurnCopy(turn: PageTurn?, progress: () -> Float, modifier: Modifier = Modifier) {
    if (turn == null) return
    Canvas(modifier) {
        val forward = turn.direction > 0
        val edge = pageTurnEdge(size.width, turn.direction, progress())
        // Whatever the arriving page has not reached yet is still the page being left behind.
        val left = if (forward) 0f else edge
        val right = if (forward) edge else size.width
        if (right - left <= 0f) return@Canvas
        // The page being left is shoved aside a little as the sheet arriving covers it.
        val aside = pageTurnNudge(size.width, progress())
        clipRect(left, 0f, right, size.height) {
            drawImage(
                turn.page,
                dstOffset = IntOffset((if (forward) -aside else aside).roundToInt(), 0),
                dstSize = IntSize(turn.page.width, turn.page.height),
            )
        }
    }
}

/**
 * The dimming and the shadow the arriving sheet casts, over the whole screen: the page sits inset
 * from the screen's edges, and a turn that stopped at those edges would read as a panel of the
 * screen turning rather than the page itself.
 */
@Composable
internal fun PageTurnShade(turn: PageTurn?, progress: () -> Float, modifier: Modifier = Modifier) {
    if (turn == null) return
    Canvas(modifier.testTag(PAGE_TURN_TAG)) {
        val forward = turn.direction > 0
        val edge = pageTurnEdge(size.width, turn.direction, progress())
        // Whatever the arriving page has not reached yet is still the page being left behind.
        val left = if (forward) 0f else edge
        val right = if (forward) edge else size.width
        if (right - left <= 0f) return@Canvas
        clipRect(left, 0f, right, size.height) {
            // The page being left behind dims as the sheet passes over it, edges and all.
            drawRect(Color.Black, alpha = pageTurnVeil(progress()))
            // The shadow of the sheet's leading edge, falling on the page it is covering.
            val shade = size.width * FOLD_SHARE
            val start = if (forward) edge - shade else edge
            val brush = if (forward)
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = edge - shade, endX = edge)
            else
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = edge, endX = edge + shade)
            drawRect(brush, topLeft = Offset(start, 0f), size = Size(shade, size.height), alpha = FOLD_ALPHA)
        }
    }
}
