package de.freal.toleo.ui.library

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Picks the hero's cover-themed colours so they meet WCAG AA on any cover.
 *
 * The hero's background is the cover, shrunk to a few pixels, blown up and blurred, under a scrim
 * in the cover's hue. Scaling and blurring only ever average neighbouring pixels, so every colour
 * on screen lies channel by channel between the darkest and the brightest channel values of the
 * small bitmap. Luminance rises with each channel, so it stays between the
 * luminances of those two corner colours as well. Checking both corners therefore covers every
 * pixel the blur can produce, whatever the cover looks like.
 *
 * The scrim starts light enough to let the cover show and is made more opaque until the text and
 * icons pass; the accent's lightness is then moved away from its preferred value until the button,
 * its label and the progress bar pass too. Colours are ARGB ints so this runs in plain unit tests.
 */
internal object HeroContrast {
    /** Text, at any size used in the hero (WCAG 1.4.3). */
    const val TEXT = 4.5

    /** Icons, the button's shape and the progress bar (WCAG 1.4.11). */
    const val NON_TEXT = 3.0

    /** Headroom for rounding to 8 bits when the scrim is blended on screen. */
    private const val MARGIN = 1.03

    /** Scrim opacities tried, from 30 % (the cover shows well) up to fully opaque. */
    private val SCRIM_STEPS = (12..40).map { it / 40f }

    /** How far the scrim's luminance goes from the surface's towards black (dark) or white (light). */
    private const val SCRIM_DEPTH = 0.6

    /** The scrim's saturation at most: a hint of the cover's hue, so its own colours come through. */
    private const val SCRIM_SATURATION = 0.3f

    /** How much of the accent goes into the progress bar's track, over the scrim colour. */
    private const val TRACK_MIX = 0.3f

    /** A colour shown on the hero background and the contrast it needs against it. Opaque. */
    data class Need(val color: Int, val ratio: Double)

    /** [scrim] is opaque; it is drawn at [scrimAlpha] over the backdrop. */
    data class Colors(val scrim: Int, val scrimAlpha: Float, val accent: Int, val onAccent: Int, val track: Int)

    /** Channel-wise darkest and brightest of [pixels], each first drawn over the opaque [base]. */
    fun bounds(pixels: IntArray, base: Int): Pair<Int, Int> {
        var lr = 255; var lg = 255; var lb = 255
        var hr = 0; var hg = 0; var hb = 0
        for (p in pixels) {
            val c = over(p or OPAQUE, (p ushr 24) / 255f, base)
            lr = min(lr, red(c)); lg = min(lg, green(c)); lb = min(lb, blue(c))
            hr = max(hr, red(c)); hg = max(hg, green(c)); hb = max(hb, blue(c))
        }
        return rgb(lr, lg, lb) to rgb(hr, hg, hb)
    }

    /**
     * Colours for a cover whose pixels lie between [low] and [high] (see [bounds]), seeded with the
     * cover's [seed] colour, on the theme's opaque [surface]. Null if not even a fully opaque scrim
     * lets [needs] pass, which only a theme failing on its own surface can cause.
     *
     * The scrim is preferably a lightly tinted colour further from the text than the surface is
     * (darker in dark themes, lighter in light ones): it darkens or lightens the cover as much as
     * the surface would at a lower opacity, so more of the cover shows through. Contrast only
     * depends on luminance, so it passes wherever the plain surface would.
     */
    fun solve(seed: Int, low: Int, high: Int, surface: Int, needs: List<Need>, dark: Boolean): Colors? =
        solveOn(seed, low, high, listOf(tinted(seed, surface, dark), surface), SCRIM_STEPS, needs, dark)

    /**
     * Like [solve], with the opaque [scrims] to try in order and the [alphas] to try each at, from
     * the most see-through up.
     */
    fun solveOn(seed: Int, low: Int, high: Int, scrims: List<Int>, alphas: List<Float>, needs: List<Need>, dark: Boolean): Colors? {
        for (scrim in scrims) {
            for (alpha in alphas) {
                val l = over(scrim, alpha, low)
                val h = over(scrim, alpha, high)
                if (needs.any { worstContrast(it.color, l, h) < it.ratio * MARGIN }) continue
                val accent = accentFor(seed, l, h, scrim, dark) ?: continue
                return accent.copy(scrim = scrim, scrimAlpha = alpha)
            }
        }
        return null
    }

    /**
     * [seed]'s hue, lightly saturated, at a luminance [SCRIM_DEPTH] of the way from [surface]'s
     * towards black (dark themes) or white (light themes). Always at least as far from the text as
     * the surface, so whatever passes on the surface passes on it too.
     */
    private fun tinted(seed: Int, surface: Int, dark: Boolean): Int {
        val (hue, seedSat, _) = hsl(seed)
        val sat = min(seedSat, SCRIM_SATURATION)
        val s = luminance(surface)
        val target = if (dark) s * (1 - SCRIM_DEPTH) else s + (1 - s) * SCRIM_DEPTH
        // Luminance rises with HSL lightness at a fixed hue and saturation, so a scan finds the edge.
        val steps = (0..1000).map { fromHsl(hue, sat, it / 1000f) }
        return (if (dark) steps.lastOrNull { luminance(it) <= target } else steps.firstOrNull { luminance(it) >= target }) ?: surface
    }

    /** [seed]'s hue at HSL [lightness], with at most [maxSaturation] of its saturation. */
    fun tone(seed: Int, lightness: Float, maxSaturation: Float): Int {
        val (hue, sat, _) = hsl(seed)
        return fromHsl(hue, min(sat, maxSaturation), lightness)
    }

    /**
     * The accent (with its label and progress track) for a background between [low] and [high]:
     * [seed]'s hue at the lightness nearest the theme's preferred one that passes. The track is the
     * accent mixed into the opaque [scrim]; the result's scrim is [scrim] at full opacity.
     */
    fun accentFor(seed: Int, low: Int, high: Int, scrim: Int, dark: Boolean): Colors? {
        val (hue, seedSat, _) = hsl(seed)
        // Near-grey covers keep a grey accent; anything with colour gets enough of it to read as one.
        val sat = if (seedSat < 0.12f) seedSat else seedSat.coerceIn(0.4f, 0.85f)
        val preferred = if (dark) 0.72f else 0.38f
        val lightnesses = (0..100).map { it / 100f }.sortedBy { abs(it - preferred) }
        for (l in lightnesses) {
            val accent = fromHsl(hue, sat, l)
            if (worstContrast(accent, low, high) < NON_TEXT * MARGIN) continue
            val track = over(accent, TRACK_MIX, scrim)
            if (contrast(accent, track) < NON_TEXT * MARGIN) continue
            val label = labelFor(accent, hue, sat, dark) ?: continue
            return Colors(scrim, 1f, accent, label, track)
        }
        return null
    }

    /** A near-white or near-black of the accent's hue, else plain white or black. */
    private fun labelFor(accent: Int, hue: Float, sat: Float, dark: Boolean): Int? {
        val light = fromHsl(hue, min(sat, 0.25f), 0.97f)
        val deep = fromHsl(hue, min(sat, 0.35f), 0.10f)
        val tinted = if (dark) listOf(deep, light) else listOf(light, deep)
        return (tinted + listOf(WHITE, BLACK)).firstOrNull { contrast(it, accent) >= TEXT * MARGIN }
    }

    /** The lowest contrast [fg] has against any colour between [low] and [high]. */
    fun worstContrast(fg: Int, low: Int, high: Int): Double {
        val f = luminance(fg)
        val l = luminance(low)
        val h = luminance(high)
        if (f in l..h) return 1.0
        return min(ratio(f, l), ratio(f, h))
    }

    fun contrast(a: Int, b: Int): Double = ratio(luminance(a), luminance(b))

    private fun ratio(a: Double, b: Double) = (max(a, b) + 0.05) / (min(a, b) + 0.05)

    /** WCAG relative luminance. */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))
    }

    /** Opaque [fg] at [alpha] over opaque [bg], blended per channel as the screen does. */
    fun over(fg: Int, alpha: Float, bg: Int): Int {
        fun mix(f: Int, b: Int) = (f * alpha + b * (1 - alpha)).roundToInt().coerceIn(0, 255)
        return rgb(mix(red(fg), red(bg)), mix(green(fg), green(bg)), mix(blue(fg), blue(bg)))
    }

    private fun hsl(c: Int): Triple<Float, Float, Float> {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return Triple(0f, 0f, l)
        val d = mx - mn
        val s = d / (1 - abs(2 * l - 1))
        val h = when (mx) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return Triple(h, s.coerceIn(0f, 1f), l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60).mod(2f) - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return rgb(ch(r), ch(g), ch(b))
    }

    private const val OPAQUE = -0x1000000 // 0xFF000000
    private const val WHITE = -1
    private const val BLACK = OPAQUE

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = OPAQUE or (r shl 16) or (g shl 8) or b
}
