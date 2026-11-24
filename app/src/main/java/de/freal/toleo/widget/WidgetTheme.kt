package de.freal.toleo.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import de.freal.toleo.R
import de.freal.toleo.ui.library.HeroContrast
import de.freal.toleo.ui.library.sampleCover
import de.freal.toleo.ui.theme.Dark
import de.freal.toleo.ui.theme.Light

/** A colour for light mode and one for dark; from Android 12 the launcher switches between them itself. */
internal data class DayNight(val day: Color, val night: Color) {
    fun provider(): ColorProvider = ColorProvider(day = day, night = night)

    fun of(dark: Boolean) = if (dark) night else day
}

/** The cover-themed colours of one [WidgetStyle]; text and background stay the style's own where null. */
internal class StyleColors(
    val bar: DayNight,
    val track: DayNight,
    val background: DayNight? = null,
    val text: DayNight? = null,
    val secondary: DayNight? = null,
)

/** The current book's colours for the widget: every style's progress bar, and the cover-themed styles. */
internal class WidgetTheme(
    /** The cover, blurred: the "Cover" style's background. Opaque, whatever the cover. */
    val backdrop: Bitmap,
    private val styles: Map<WidgetStyle, StyleColors>,
) {
    operator fun get(style: WidgetStyle): StyleColors? = styles[style]
}

/**
 * Themes the widget from a software [cover] bitmap; null if it has no colours. A style whose
 * colours can't pass (in practice none) is left out and keeps its usual look.
 */
internal fun widgetTheme(context: Context, cover: Bitmap): WidgetTheme? {
    val sample = sampleCover(cover) ?: return null
    val seed = sample.seed
    // Transparent pixels show white, so the backdrop is opaque on any wallpaper.
    val pixels = IntArray(sample.pixels.size) { HeroContrast.over(sample.pixels[it] or WidgetContrast.BLACK, (sample.pixels[it] ushr 24) / 255f, WidgetContrast.WHITE) }
    val (low, high) = HeroContrast.bounds(pixels, WidgetContrast.WHITE)
    val cards = Cards(context)

    fun bars(day: HeroContrast.Colors, night: HeroContrast.Colors) =
        StyleColors(bar = DayNight(Color(day.accent), Color(night.accent)), track = DayNight(Color(day.track), Color(night.track)))

    fun bothModes(style: WidgetStyle, solve: (dark: Boolean) -> HeroContrast.Colors?, colors: (HeroContrast.Colors, HeroContrast.Colors) -> StyleColors) =
        solve(false)?.let { day -> solve(true)?.let { night -> style to colors(day, night) } }

    val darkGlass = WidgetContrast.overWallpaper(WidgetContrast.BLACK, WidgetInk.DarkScrim.alpha).let { (l, h) ->
        WidgetContrast.bar(seed, l, h, WidgetContrast.BLACK, dark = true)
    }
    val lightGlass = WidgetContrast.overWallpaper(WidgetContrast.WHITE, WidgetInk.LightScrim.alpha).let { (l, h) ->
        WidgetContrast.bar(seed, l, h, WidgetContrast.WHITE, dark = false)
    }
    val styles = listOfNotNull(
        bothModes(WidgetStyle.SOLID, { dark -> cards.solid(dark).let { WidgetContrast.bar(seed, it, it, it, dark) } }, ::bars),
        bothModes(WidgetStyle.TRANSLUCENT, { dark ->
            val (card, alpha) = cards.translucent(dark)
            val (l, h) = WidgetContrast.overWallpaper(card, alpha)
            WidgetContrast.bar(seed, l, h, card, dark)
        }, ::bars),
        // The glass styles look the same in either mode.
        darkGlass?.let { WidgetStyle.DARK to bars(it, it) },
        lightGlass?.let { WidgetStyle.LIGHT to bars(it, it) },
        // White text on the wallpaper: the bright accent of the dark glass, kept legible by the bar's dark outline.
        darkGlass?.let { WidgetStyle.TRANSPARENT to bars(it, it) },
        bothModes(WidgetStyle.COVER, { dark -> WidgetContrast.cover(seed, low, high, cards.solid(dark), dark) }) { day, night ->
            themed(seed, day, night)
        },
        bothModes(WidgetStyle.THEMED, { dark -> WidgetContrast.themed(seed, dark) }) { day, night -> themed(seed, day, night) },
    ).toMap()
    return WidgetTheme(blur(pixels), styles)
}

/** A cover-themed style: its scrim or tone as the background, with the cover-tinted ink. */
private fun themed(seed: Int, day: HeroContrast.Colors, night: HeroContrast.Colors): StyleColors {
    val (dayText, daySecondary) = WidgetContrast.ink(seed, dark = false)
    val (nightText, nightSecondary) = WidgetContrast.ink(seed, dark = true)
    return StyleColors(
        bar = DayNight(Color(day.accent), Color(night.accent)),
        track = DayNight(Color(day.track), Color(night.track)),
        background = DayNight(Color(day.scrim).copy(alpha = day.scrimAlpha), Color(night.scrim).copy(alpha = night.scrimAlpha)),
        text = DayNight(Color(dayText), Color(nightText)),
        secondary = DayNight(Color(daySecondary), Color(nightSecondary)),
    )
}

/** The cards of the "Solid" and "Translucent" styles, as the launcher draws them in each mode. */
private class Cards(private val context: Context) {
    /** Glance's widgetBackground: Material You's from Android 12, the app's secondary container before. */
    fun solid(dark: Boolean): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        translucent(dark).first
    } else {
        (if (dark) Dark else Light).secondaryContainer.toArgb()
    }

    /** The widget_background_translucent colour resource in that mode: the opaque colour and its alpha. */
    fun translucent(dark: Boolean): Pair<Int, Float> {
        val argb = context.inMode(dark).getColor(R.color.widget_background_translucent)
        return (argb or WidgetContrast.BLACK) to (argb ushr 24) / 255f
    }

    private fun Context.inMode(dark: Boolean): Context {
        val config = Configuration(resources.configuration)
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return createConfigurationContext(config)
    }
}

/**
 * The backdrop [pixels] blown up and box-blurred. Both only average neighbouring pixels, as does
 * the launcher scaling it to the widget, so the contrast bounds from [pixels] hold for the result.
 */
private fun blur(pixels: IntArray): Bitmap {
    val tiny = Bitmap.createBitmap(pixels, BACKDROP_W, BACKDROP_H, Bitmap.Config.ARGB_8888)
    val big = Bitmap.createScaledBitmap(tiny, BACKDROP_W * SCALE, BACKDROP_H * SCALE, true)
    val w = big.width
    val h = big.height
    var px = IntArray(w * h).also { big.getPixels(it, 0, w, 0, 0, w, h) }
    repeat(2) {
        px = boxPass(px, w, h, horizontal = true)
        px = boxPass(px, w, h, horizontal = false)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** One box-blur pass of radius [BLUR_RADIUS] along rows or columns, clamping at the edges. */
private fun boxPass(src: IntArray, w: Int, h: Int, horizontal: Boolean): IntArray {
    val out = IntArray(src.size)
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val n = 2 * BLUR_RADIUS + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        for (i in 0 until length) {
            var r = 0; var g = 0; var b = 0
            for (k in -BLUR_RADIUS..BLUR_RADIUS) {
                val p = at(i + k)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val v = WidgetContrast.BLACK or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            if (horizontal) out[line * w + i] = v else out[i * w + line] = v
        }
    }
    return out
}

// The library's backdrop size (see sampleCover), blown up four times and blurred.
private const val BACKDROP_W = 24
private const val BACKDROP_H = 36
private const val SCALE = 4
private const val BLUR_RADIUS = 3
