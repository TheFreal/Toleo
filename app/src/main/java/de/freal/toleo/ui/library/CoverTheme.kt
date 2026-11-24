package de.freal.toleo.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The current book's colours for the hero; see [HeroContrast] for how they stay legible. */
internal class CoverTheme(
    /** The cover shrunk to a few pixels; drawn blown up (and blurred where the OS can) as the backdrop. */
    val backdrop: ImageBitmap,
    val scrim: Color,
    val accent: Color,
    val onAccent: Color,
    val track: Color,
)

/**
 * The scheme the hero is drawn in: the app's, with secondary text and icons moved halfway to the
 * main text colour. The grey secondary colour is what needs the most contrast headroom, so the
 * stronger one lets the scrim be thinner and more of the cover show.
 */
internal fun ColorScheme.forHero(): ColorScheme = copy(onSurfaceVariant = lerp(onSurfaceVariant, onSurface, 0.5f))

/**
 * Loads [coverUrl], seeds the accent from its Palette swatches and solves the scrim and accent for
 * the current (hero) colour scheme. Null while loading, without a cover, or if no colours would
 * pass; the previous book's theme stays up until the next one is ready, so switching books
 * doesn't flash.
 */
@Composable
internal fun rememberCoverTheme(coverUrl: String?): CoverTheme? {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    // Plain values as keys: colour scheme objects can be recreated without changing, and a restart
    // here would replay the backdrop's crossfade.
    val surface = colors.surfaceContainerHigh.toArgb()
    // Everything the hero draws straight onto its background, with the contrast each one needs.
    val needs = listOf(
        HeroContrast.Need(colors.onSurface.toArgb(), HeroContrast.TEXT), // title, app bar title
        HeroContrast.Need(colors.onSurfaceVariant.toArgb(), HeroContrast.TEXT), // labels, app bar icons
        HeroContrast.Need(colors.error.toArgb(), HeroContrast.NON_TEXT), // sync failed icon
        HeroContrast.Need(colors.primary.toArgb(), HeroContrast.NON_TEXT), // sync spinner
    )
    val theme by produceState<CoverTheme?>(null, coverUrl, surface, needs, dark) {
        value = coverUrl?.let { load(context, it, surface, needs, dark) }
    }
    return theme
}

private suspend fun load(context: Context, url: String, surface: Int, needs: List<HeroContrast.Need>, dark: Boolean): CoverTheme? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(SAMPLE_WIDTH, SAMPLE_HEIGHT)
        .allowHardware(false) // Palette and getPixels need to read the pixels.
        .build()
    val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    return withContext(Dispatchers.Default) {
        val sample = sampleCover(bitmap) ?: return@withContext null
        val (low, high) = HeroContrast.bounds(sample.pixels, surface)
        val solved = HeroContrast.solve(sample.seed, low, high, surface, needs, dark) ?: return@withContext null
        CoverTheme(
            backdrop = sample.backdrop.asImageBitmap(),
            scrim = Color(solved.scrim).copy(alpha = solved.scrimAlpha),
            accent = Color(solved.accent),
            onAccent = Color(solved.onAccent),
            track = Color(solved.track),
        )
    }
}

/** What the hero and the widget take from a cover: its Palette seed and a few-pixel backdrop. */
internal class CoverSample(
    /** The colour the accents and scrims take their hue from. */
    val seed: Int,
    /** The cover shrunk to [BACKDROP_WIDTH] × [BACKDROP_HEIGHT]; blown up, it is the blurred background. */
    val backdrop: Bitmap,
    /** [backdrop]'s pixels, which bound every colour scaling or blurring it can produce. */
    val pixels: IntArray,
)

/** Samples a software [bitmap] (Palette and getPixels can't read hardware ones); null if it has no colours. */
internal fun sampleCover(bitmap: Bitmap): CoverSample? {
    val palette = Palette.from(bitmap).generate()
    val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
    val seed = swatch?.rgb ?: return null
    val tiny = Bitmap.createScaledBitmap(bitmap, BACKDROP_WIDTH, BACKDROP_HEIGHT, true)
    val pixels = IntArray(BACKDROP_WIDTH * BACKDROP_HEIGHT)
    tiny.getPixels(pixels, 0, BACKDROP_WIDTH, 0, 0, BACKDROP_WIDTH, BACKDROP_HEIGHT)
    return CoverSample(seed, tiny, pixels)
}

/** The blurred cover under its scrim, filling the parent; fades between books. */
@Composable
internal fun CoverBackdrop(theme: CoverTheme?, modifier: Modifier = Modifier) {
    Crossfade(theme, modifier, label = "cover backdrop") { t ->
        if (t != null) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = t.backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // Bilinear only: it averages neighbours, which the contrast bounds rely on.
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // Before Android 12 the bilinear blow-up of the tiny bitmap is the blur.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier.blur(20.dp, BlurredEdgeTreatment.Rectangle)
                            } else {
                                Modifier
                            },
                        ),
                )
                Box(Modifier.fillMaxSize().background(t.scrim))
            }
        }
    }
}

private const val SAMPLE_WIDTH = 96
private const val SAMPLE_HEIGHT = 144
private const val BACKDROP_WIDTH = 24
private const val BACKDROP_HEIGHT = 36
