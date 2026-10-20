package com.moonlightpages.app

/**
 * How a page can be set: the room it keeps from the screen's edges, how far apart its lines sit, and
 * the face it is printed in. The values live in one place because three things need the same ones —
 * the page's own CSS, the appearance sheet, and the settings the reader chooses — and a page that
 * says one thing and draws another would be worse than having no choice at all.
 */
internal object ReadingTypography {
    /** Room the page keeps from its left and right edges, in pixels. */
    val insets = listOf(12, 24, 40)
    val insetNames = listOf("Narrow", "Medium", "Wide")

    /** Line spacing as a percentage of the text size: 165 is the 1.65 a page is set in by default. */
    val spacings = listOf(145, 165, 195)
    val spacingNames = listOf("Tight", "Normal", "Roomy")

    /** The faces a page can be printed in. */
    val fonts = listOf("Georgia,'Noto Serif',serif", "'Noto Sans',Roboto,sans-serif")
    val fontNames = listOf("Serif", "Sans")

    val defaultInset = 1
    val defaultSpacing = 1
    val defaultFont = 0

    /** The room the page keeps top and bottom, which is a little less than it keeps at the sides. */
    fun verticalInset(inset: Int): Int = Math.round(inset * 5f / 6f)

    fun inset(index: Int): Int = insets[index.coerceIn(0, insets.lastIndex)]
    fun spacing(index: Int): Int = spacings[index.coerceIn(0, spacings.lastIndex)]
    fun font(index: Int): String = fonts[index.coerceIn(0, fonts.lastIndex)]
}

/**
 * The steps a reader can dim the screen by for night reading, as a percentage of the phone's own
 * brightness taken away: none, then progressively darker. This is what takes the light below the
 * setting the phone itself offers.
 */
internal val nightDimSteps = listOf(0, 20, 40, 60, 80)
internal val nightDimNames = listOf("Off", "20%", "40%", "60%", "80%")
internal val maxNightDim = nightDimSteps.last()

/** The dimmest a reading screen is taken to, as a share of full brightness. */
internal const val MIN_READING_BRIGHTNESS = 0.01f
