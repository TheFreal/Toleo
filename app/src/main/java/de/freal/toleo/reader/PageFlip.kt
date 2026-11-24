package de.freal.toleo.reader

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Page-turn animation drawn over the navigator.
 *
 * The reader snapshots the current page ([old]), covers the navigator with it, turns the page
 * underneath without animation, and animates the snapshot away.
 *
 * - One page: hinged on the left edge of the screen. Forward, the old page flips away to the
 *   left; back, the previous page flips down from the left over it.
 * - Open book: a real sheet turn around the spine. Going forward, the right page lifts and
 *   rotates to edge-on (first half); then its back — the new left page ([next]) — falls onto the
 *   left side (second half). Going back mirrors this. The untouched half of the old spread stays
 *   in place until the sheet lands on it.
 */
@Stable
class PageFlipState {
    var old by mutableStateOf<ImageBitmap?>(null)
        private set
    var next by mutableStateOf<ImageBitmap?>(null)
        private set
    var forward by mutableStateOf(true)
        private set
    var twoPages by mutableStateOf(false)
        private set
    val progress = Animatable(0f)

    val running: Boolean get() = old != null

    private var shown = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Shows [snapshot] statically over the navigator so the page can change underneath unseen. */
    suspend fun cover(snapshot: ImageBitmap, forward: Boolean, twoPages: Boolean) {
        this.forward = forward
        this.twoPages = twoPages
        progress.snapTo(0f)
        next = null
        shown = kotlinx.coroutines.CompletableDeferred()
        old = snapshot
    }

    /** Suspends until the overlay window is on screen (bounded, so a missing window can't hang turns). */
    suspend fun awaitShown() {
        kotlinx.coroutines.withTimeoutOrNull(500) { shown.await() }
    }

    internal fun onShown() {
        shown.complete(Unit)
    }

    /** Runs the turn. [nextPage] is the new spread, needed for the back of the sheet in open-book mode. */
    suspend fun turn(nextPage: ImageBitmap?) {
        next = nextPage
        try {
            progress.animateTo(1f, tween(durationMillis = if (twoPages) 620 else 380, easing = FastOutSlowInEasing))
        } finally {
            clear()
        }
    }

    fun clear() {
        old = null
        next = null
    }
}

@Composable
fun PageFlipOverlay(state: PageFlipState, modifier: Modifier = Modifier) {
    val old = state.old ?: return
    val p = state.progress.value
    if (!state.twoPages) {
        // Always hinged on the left edge, like a book. Forward: the open page flips away to the
        // left, revealing the next one. Back: the previous page flips down from the left over it.
        val next = state.next
        if (!state.forward) {
            Box(modifier.fillMaxSize()) {
                Half(old, HalfPart.WHOLE, Modifier.fillMaxSize(), shade = 0f, shadeTowardsEnd = true)
                if (next != null) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            rotationY = -90f * (1f - p)
                            cameraDistance = 14f * density
                        },
                    ) { Half(next, HalfPart.WHOLE, Modifier.fillMaxSize(), shade = 1f - p, shadeTowardsEnd = true) }
                }
            }
        } else {
            Box(
                modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = -90f * p
                    cameraDistance = 14f * density
                },
            ) { Half(old, HalfPart.WHOLE, Modifier.fillMaxSize(), shade = p, shadeTowardsEnd = true) }
        }
        return
    }

    val next = state.next
    val firstHalf = p < 0.5f
    // 0 → 1 over each half of the turn.
    val lift = (p / 0.5f).coerceIn(0f, 1f)
    val land = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)

    Row(modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- left half
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (state.forward) {
                // Old left page waits until the sheet lands on it (or fades if there's no sheet back).
                Half(
                    old, HalfPart.LEFT,
                    Modifier.fillMaxSize().graphicsLayer { alpha = if (next == null && !firstHalf) 1f - land else 1f },
                    shade = if (firstHalf) 0f else 0.35f * (1f - land), shadeTowardsEnd = true,
                )
                if (!firstHalf) {
                    if (next != null) {
                        // Back of the turning sheet = new left page, falling from edge-on to flat.
                        Half(
                            next, HalfPart.LEFT,
                            Modifier.fillMaxSize().graphicsLayer {
                                transformOrigin = TransformOrigin(1f, 0.5f)
                                rotationY = 90f * (1f - land)
                                cameraDistance = 14f * density
                            },
                            shade = 0.5f * (1f - land), shadeTowardsEnd = true,
                        )
                    }
                }
            } else if (firstHalf) {
                // Going back: the left page lifts and rotates towards the spine.
                Half(
                    old, HalfPart.LEFT,
                    Modifier.fillMaxSize().graphicsLayer {
                        transformOrigin = TransformOrigin(1f, 0.5f)
                        rotationY = 90f * lift
                        cameraDistance = 14f * density
                    },
                    shade = 0.5f * lift, shadeTowardsEnd = true,
                )
            }
        }
        // ---------------------------------------------------------------- right half
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (state.forward) {
                if (firstHalf) {
                    Half(
                        old, HalfPart.RIGHT,
                        Modifier.fillMaxSize().graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            rotationY = -90f * lift
                            cameraDistance = 14f * density
                        },
                        shade = 0.5f * lift, shadeTowardsEnd = false,
                    )
                }
            } else {
                Half(
                    old, HalfPart.RIGHT,
                    Modifier.fillMaxSize().graphicsLayer { alpha = if (next == null && !firstHalf) 1f - land else 1f },
                    shade = if (firstHalf) 0f else 0.35f * (1f - land), shadeTowardsEnd = false,
                )
                if (!firstHalf && next != null) {
                    Half(
                        next, HalfPart.RIGHT,
                        Modifier.fillMaxSize().graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            rotationY = -90f * (1f - land)
                            cameraDistance = 14f * density
                        },
                        shade = 0.5f * (1f - land), shadeTowardsEnd = false,
                    )
                }
            }
        }
    }
}

private enum class HalfPart { LEFT, RIGHT, WHOLE }

/**
 * Draws [part] of [image] with a shadow that darkens towards the spine (or the outer edge for a
 * single page), which sells the curvature of a turning page.
 */
@Composable
private fun Half(image: ImageBitmap, part: HalfPart, modifier: Modifier, shade: Float, shadeTowardsEnd: Boolean) {
    Canvas(modifier) {
        val srcWidth = if (part == HalfPart.WHOLE) image.width else image.width / 2
        val srcX = if (part == HalfPart.RIGHT) image.width - srcWidth else 0
        drawImage(
            image = image,
            srcOffset = IntOffset(srcX, 0),
            srcSize = IntSize(srcWidth, image.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
        if (shade > 0f) {
            val light = Color.Black.copy(alpha = 0.04f * shade)
            val dark = Color.Black.copy(alpha = 0.55f * shade)
            drawRect(
                brush = Brush.horizontalGradient(
                    if (shadeTowardsEnd) listOf(light, dark) else listOf(dark, light),
                    startX = 0f,
                    endX = size.width,
                ),
                size = size,
            )
        }
    }
}

/** Copies what [view] currently shows on screen. PixelCopy also captures hardware-rendered WebView content. */
suspend fun snapshotOf(view: View, window: Window): ImageBitmap? {
    if (view.width == 0 || view.height == 0) return null
    val location = IntArray(2).also(view::getLocationInWindow)
    val rect = android.graphics.Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    // With a spine gutter the WebView reaches past the screen edges; capture only what's visible,
    // which matches the page area the flip overlay is drawn over.
    val decor = window.decorView
    if (!rect.intersect(0, 0, decor.width, decor.height)) return null
    val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { cont ->
        PixelCopy.request(window, rect, bitmap, { result ->
            cont.resume(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null)
        }, Handler(Looper.getMainLooper()))
    }
}

/**
 * Hosts [PageFlipOverlay] in its own non-touchable window above the reader.
 *
 * PixelCopy of the activity window doesn't include other windows, so the new spread can be
 * captured while this overlay still hides it. Rendering the WebView offscreen instead is not an
 * option: Chromium's software draw re-syncs the WebView's scroll offset and undoes Readium's
 * page turn (observed on device).
 */
@Composable
fun PageFlipLayer(state: PageFlipState, topPx: Int, widthPx: Int, heightPx: Int) {
    // The popup window only exists during a flip: Compose's Popup doesn't honour
    // FLAG_NOT_TOUCHABLE, so a permanent one would swallow every tap (observed).
    val snapshot = state.old
    if (widthPx == 0 || heightPx == 0 || snapshot == null) return
    androidx.compose.ui.window.Popup(
        properties = androidx.compose.ui.window.PopupProperties(
            flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The window spans the whole reader (margins included) so the rotating page can extend
        // past the text area; the overlay itself sits exactly over the page area.
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset { IntOffset(0, topPx) }
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() }),
            ) { PageFlipOverlay(state) }
        }
        // Signal once the overlay window has drawn a frame, so the page may change underneath.
        androidx.compose.runtime.LaunchedEffect(snapshot) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            state.onShown()
        }
    }
}
