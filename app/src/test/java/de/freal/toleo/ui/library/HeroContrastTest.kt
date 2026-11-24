package de.freal.toleo.ui.library

import de.freal.toleo.ui.library.HeroContrast.Need
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroContrastTest {
    /** The neutrals of Material 3's baseline schemes, which Toleo uses below Android 12, and its primaries. */
    private class Scheme(val name: String, val surface: Int, val dark: Boolean, val onSurface: Int, val onVariant: Int, val error: Int, val primary: Int) {
        val needs = listOf(
            Need(onSurface, HeroContrast.TEXT),
            Need(onVariant, HeroContrast.TEXT),
            Need(error, HeroContrast.NON_TEXT),
            Need(primary, HeroContrast.NON_TEXT),
        )
    }

    private val light = Scheme("light", 0xFFECE6F0.toInt(), false, 0xFF1D1B20.toInt(), 0xFF49454F.toInt(), 0xFFB3261E.toInt(), 0xFF8A4B2A.toInt())
    private val dark = Scheme("dark", 0xFF2B2930.toInt(), true, 0xFFE6E0E9.toInt(), 0xFFCAC4D0.toInt(), 0xFFF2B8B5.toInt(), 0xFFFFB68F.toInt())
    private val schemes = listOf(light, dark)

    private fun solve(pixels: IntArray, seed: Int, scheme: Scheme): HeroContrast.Colors? {
        val (low, high) = HeroContrast.bounds(pixels, scheme.surface)
        return HeroContrast.solve(seed, low, high, scheme.surface, scheme.needs, scheme.dark)
    }

    @Test fun randomCoversAlwaysPassAa() {
        val random = Random(42)
        repeat(1500) { i ->
            val pixels = randomCover(random, i % 4)
            val seed = pixels[random.nextInt(pixels.size)] or OPAQUE
            for (scheme in schemes) {
                val colors = solve(pixels, seed, scheme)
                assertNotNull("cover $i, ${scheme.name}", colors)
                verify(pixels, scheme, colors!!, random, "cover $i, ${scheme.name}")
            }
        }
    }

    @Test fun extremeCoversStillGetColors() {
        val covers = mapOf(
            "white" to IntArray(N) { WHITE },
            "black" to IntArray(N) { OPAQUE },
            "checker" to IntArray(N) { if (it % 2 == 0) WHITE else OPAQUE },
            "rgb" to IntArray(N) { intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())[it % 3] },
            "transparent" to IntArray(N) { 0 },
        )
        for ((name, pixels) in covers) {
            for (scheme in schemes) {
                val colors = solve(pixels, pixels[0] or OPAQUE, scheme)
                assertNotNull("$name, ${scheme.name}", colors)
                verify(pixels, scheme, colors!!, Random(1), "$name, ${scheme.name}")
            }
        }
    }

    @Test fun midToneCoverStaysVisibleThroughTheScrim() {
        val pixels = IntArray(N) { 0xFF8FA8C8.toInt() }
        for (scheme in schemes) {
            val colors = solve(pixels, pixels[0], scheme)!!
            assertTrue("${scheme.name}: scrim ${colors.scrimAlpha}", colors.scrimAlpha < 1f)
        }
    }

    /**
     * Checks the result independently of the solver's bounds: every backdrop pixel and many random
     * blends of them, as bilinear scaling and blurring produce, under the scrim.
     */
    private fun verify(pixels: IntArray, scheme: Scheme, c: HeroContrast.Colors, random: Random, what: String) {
        val base = pixels.map { over(it or OPAQUE, (it ushr 24) / 255.0, scheme.surface) }
        val backgrounds = base.toMutableList()
        repeat(400) {
            val k = 2 + random.nextInt(3)
            val picks = List(k) { base[random.nextInt(base.size)] }
            val weights = List(k) { random.nextDouble() }.let { w -> w.map { it / w.sum() } }
            backgrounds += blend(picks, weights)
        }
        for (bg in backgrounds) {
            val shown = over(c.scrim, c.scrimAlpha.toDouble(), bg)
            for (need in scheme.needs) {
                val ratio = contrast(need.color, shown)
                assertTrue("$what: ${hex(need.color)} on ${hex(shown)} is $ratio", ratio >= need.ratio)
            }
            assertTrue("$what: accent on ${hex(shown)}", contrast(c.accent, shown) >= HeroContrast.NON_TEXT)
        }
        assertTrue("$what: label on accent", contrast(c.onAccent, c.accent) >= HeroContrast.TEXT)
        assertTrue("$what: accent on track", contrast(c.accent, c.track) >= HeroContrast.NON_TEXT)
    }

    private fun randomCover(random: Random, kind: Int) = when (kind) {
        // Noise over the whole range.
        0 -> IntArray(N) { random.nextInt() or OPAQUE }
        // A few flat areas, like most illustrated covers.
        1 -> {
            val inks = IntArray(1 + random.nextInt(4)) { random.nextInt() or OPAQUE }
            IntArray(N) { inks[(it / 37 + it % 5) % inks.size] }
        }
        // One hue in many shades.
        2 -> {
            val ink = random.nextInt()
            IntArray(N) { scale(ink, random.nextDouble()) }
        }
        // Partly transparent, as some PNG covers are.
        else -> IntArray(N) { random.nextInt() }
    }

    private fun luminance(c: Int): Double {
        fun lin(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF

    private fun rgb(r: Int, g: Int, b: Int) = OPAQUE or (r shl 16) or (g shl 8) or b

    private fun over(fg: Int, alpha: Double, bg: Int) = blend(listOf(fg, bg), listOf(alpha, 1 - alpha))

    private fun blend(colors: List<Int>, weights: List<Double>): Int {
        fun ch(shift: Int) = colors.indices.sumOf { channel(colors[it], shift) * weights[it] }.roundToInt().coerceIn(0, 255)
        return rgb(ch(16), ch(8), ch(0))
    }

    private fun scale(c: Int, f: Double) = rgb((channel(c, 16) * f).toInt(), (channel(c, 8) * f).toInt(), (channel(c, 0) * f).toInt())

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private companion object {
        const val N = 24 * 36
        const val OPAQUE = -0x1000000
        const val WHITE = -1
    }
}
