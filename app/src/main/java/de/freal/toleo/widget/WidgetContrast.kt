package de.freal.toleo.widget

import de.freal.toleo.ui.library.HeroContrast
import de.freal.toleo.ui.library.HeroContrast.Colors
import de.freal.toleo.ui.library.HeroContrast.Need

/**
 * The widget's cover-themed colours, per light or dark mode, held to the same WCAG AA bar as the
 * library's hero (see [HeroContrast]). A see-through background is checked over any wallpaper:
 * every wallpaper pixel lies between black and white, so those two bound what shows through.
 * ARGB ints, so this runs in plain unit tests.
 */
internal object WidgetContrast {
    const val BLACK = -0x1000000 // 0xFF000000
    const val WHITE = -1

    /** "Translucent" is 72 % opaque; the themed one starts there and only goes more opaque if a wallpaper could break the text. */
    const val TRANSLUCENT_ALPHA = 0.72f
    private val THEMED_ALPHAS = (0..28).map { TRANSLUCENT_ALPHA + it * 0.01f }.map { it.coerceAtMost(1f) }

    /** The themed card's tone: a light or dark shade of the cover's hue, like Material You's widget card. */
    private const val THEMED_LIGHT = 0.93f
    private const val THEMED_DARK = 0.16f
    private const val THEMED_SATURATION = 0.45f

    /** Text and secondary text on the cover-themed styles: near-black or near-white with a hint of the cover's hue. */
    fun ink(seed: Int, dark: Boolean): Pair<Int, Int> =
        HeroContrast.tone(seed, if (dark) 0.95f else 0.12f, 0.2f) to HeroContrast.tone(seed, if (dark) 0.82f else 0.30f, 0.2f)

    private fun needs(seed: Int, dark: Boolean) = ink(seed, dark).let { (text, secondary) ->
        listOf(Need(text, HeroContrast.TEXT), Need(secondary, HeroContrast.TEXT))
    }

    /** What a [color] at [alpha] can look like over any wallpaper: between it over black and over white. */
    fun overWallpaper(color: Int, alpha: Float): Pair<Int, Int> =
        HeroContrast.over(color, alpha, BLACK) to HeroContrast.over(color, alpha, WHITE)

    /** "Cover": the blurred cover (pixels between [low] and [high]) under a scrim, as in the hero, on the mode's card [surface]. */
    fun cover(seed: Int, low: Int, high: Int, surface: Int, dark: Boolean): Colors? =
        HeroContrast.solve(seed, low, high, surface, needs(seed, dark), dark)

    /** "Translucent themed": the cover's tone, see-through over the wallpaper. */
    fun themed(seed: Int, dark: Boolean): Colors? {
        val tone = HeroContrast.tone(seed, if (dark) THEMED_DARK else THEMED_LIGHT, THEMED_SATURATION)
        return HeroContrast.solveOn(seed, BLACK, WHITE, listOf(tone), THEMED_ALPHAS, needs(seed, dark), dark)
    }

    /**
     * The progress bar on one of the fixed styles, whose background lies between [low] and [high];
     * the track mixes the accent into [card]. [dark] is whether the style's own text is light.
     */
    fun bar(seed: Int, low: Int, high: Int, card: Int, dark: Boolean): Colors? =
        HeroContrast.accentFor(seed, low, high, card, dark)
}
