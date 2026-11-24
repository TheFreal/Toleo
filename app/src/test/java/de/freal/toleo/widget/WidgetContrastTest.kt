package de.freal.toleo.widget

import de.freal.toleo.ui.library.HeroContrast
import java.util.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContrastTest {
    private val black = WidgetContrast.BLACK
    private val white = WidgetContrast.WHITE

    /** Every opacity of a translucent card over black or white, the extremes of any wallpaper. */
    private fun extremes(c: HeroContrast.Colors) =
        listOf(HeroContrast.over(c.scrim, c.scrimAlpha, black), HeroContrast.over(c.scrim, c.scrimAlpha, white))

    private fun seeds(n: Int): List<Int> {
        val random = Random(7)
        return listOf(black, white, 0xFF808080.toInt(), 0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF0000FF.toInt()) +
            List(n) { random.nextInt() or black }
    }

    @Test fun themedPassesOnAnyWallpaper() {
        for (seed in seeds(500)) {
            for (dark in listOf(false, true)) {
                val c = WidgetContrast.themed(seed, dark)
                assertNotNull("seed ${hex(seed)} dark=$dark", c)
                c!!
                assertTrue("as see-through as Translucent at most", c.scrimAlpha >= WidgetContrast.TRANSLUCENT_ALPHA - 1e-6f)
                val (text, secondary) = WidgetContrast.ink(seed, dark)
                for (bg in extremes(c)) {
                    assertTrue("text on ${hex(bg)}", HeroContrast.contrast(text, bg) >= HeroContrast.TEXT)
                    assertTrue("secondary on ${hex(bg)}", HeroContrast.contrast(secondary, bg) >= HeroContrast.TEXT)
                    assertTrue("bar on ${hex(bg)}", HeroContrast.contrast(c.accent, bg) >= HeroContrast.NON_TEXT)
                }
                assertTrue("bar on track", HeroContrast.contrast(c.accent, c.track) >= HeroContrast.NON_TEXT)
            }
        }
    }

    @Test fun themedStaysAsTranslucentAsTranslucentForMidTones() {
        for (dark in listOf(false, true)) {
            val c = WidgetContrast.themed(0xFF3F6FA0.toInt(), dark)!!
            assertTrue("dark=$dark: ${c.scrimAlpha}", c.scrimAlpha <= 0.85f)
        }
    }

    @Test fun barsPassOnTheGlassStyles() {
        val glasses = listOf(
            Triple(black, WidgetInk.DarkScrim.alpha, true),
            Triple(white, WidgetInk.LightScrim.alpha, false),
        )
        for (seed in seeds(500)) {
            for ((card, alpha, dark) in glasses) {
                val (low, high) = WidgetContrast.overWallpaper(card, alpha)
                val c = WidgetContrast.bar(seed, low, high, card, dark)
                assertNotNull("seed ${hex(seed)} on ${hex(card)}", c)
                for (bg in listOf(low, high)) {
                    assertTrue("bar on ${hex(bg)}", HeroContrast.contrast(c!!.accent, bg) >= HeroContrast.NON_TEXT)
                }
                assertTrue("bar on track", HeroContrast.contrast(c!!.accent, c.track) >= HeroContrast.NON_TEXT)
            }
        }
    }

    @Test fun coverPassesOnAnyCover() {
        val random = Random(3)
        val surfaces = mapOf(false to 0xFFF0E0D6.toInt(), true to 0xFF4A3A30.toInt())
        repeat(300) {
            val pixels = IntArray(24 * 36) { random.nextInt() or black }
            val seed = pixels[0]
            for ((dark, surface) in surfaces) {
                val (low, high) = HeroContrast.bounds(pixels, white)
                val c = WidgetContrast.cover(seed, low, high, surface, dark)
                assertNotNull(c)
                val (text, secondary) = WidgetContrast.ink(seed, dark)
                val shown = pixels.map { HeroContrast.over(c!!.scrim, c.scrimAlpha, it) }
                for (bg in shown) {
                    assertTrue(HeroContrast.contrast(text, bg) >= HeroContrast.TEXT)
                    assertTrue(HeroContrast.contrast(secondary, bg) >= HeroContrast.TEXT)
                    assertTrue(HeroContrast.contrast(c!!.accent, bg) >= HeroContrast.NON_TEXT)
                }
            }
        }
    }

    private fun hex(c: Int) = "#%08X".format(c)
}
