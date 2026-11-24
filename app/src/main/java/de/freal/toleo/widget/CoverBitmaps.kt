package de.freal.toleo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.freal.toleo.ui.library.placeholderColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** The widget's cover bitmap and, for a real cover, the colours themed from it. */
internal class WidgetCover(val bitmap: Bitmap, val theme: WidgetTheme?)

/**
 * Cover bitmaps for the widget, already cropped to 2:3 with rounded corners. Rounding the bitmap
 * itself (rather than the view) works on every API level and with any scale type.
 */
internal class CoverBitmaps(private val context: Context) {
    private var key: String? = null
    private var cover: WidgetCover? = null

    /** The cover for [book]; the last one is kept so progress updates don't refetch it. */
    suspend fun load(book: WidgetBook): WidgetCover {
        val k = book.coverUrl ?: "title:${book.title}"
        if (k == key) cover?.let { return it }
        val fetched = book.coverUrl?.let { fetch(it) }
        // Placeholder covers have no theme: the styles keep their usual colours.
        val result = fetched?.let { WidgetCover(rounded(it), withContext(Dispatchers.Default) { widgetTheme(context, it) }) }
            ?: WidgetCover(placeholder(book.title), null)
        // A failed fetch (offline, say) isn't remembered, so the next update tries again.
        if (fetched != null || book.coverUrl == null) {
            key = k
            cover = result
        }
        return result
    }

    /** Goes through Coil, so covers the library has shown come from its cache. */
    private suspend fun fetch(url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(WIDTH, HEIGHT)
            .allowHardware(false) // RemoteViews must be able to parcel the pixels.
            .build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    private fun rounded(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val scale = max(WIDTH / src.width.toFloat(), HEIGHT / src.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((WIDTH - src.width * scale) / 2f, (HEIGHT - src.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        Canvas(out).drawRoundRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), RADIUS, RADIUS, paint)
        return out
    }

    /** Same look as the library's placeholder: the title on a colour picked from it. */
    private fun placeholder(title: String): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = placeholderColor(title).toArgb() }
        canvas.drawRoundRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), RADIUS, RADIUS, fill)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = WIDTH * 0.1f
            isFakeBoldText = true
        }
        val padding = (WIDTH * 0.1f).toInt()
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, text, WIDTH - 2 * padding)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(5)
            .build()
        canvas.translate(padding.toFloat(), (HEIGHT - layout.height) / 2f)
        layout.draw(canvas)
        return out
    }

    private companion object {
        // 2:3 like the library's covers; small enough to stay well under the RemoteViews bitmap limit.
        const val WIDTH = 300
        const val HEIGHT = 450
        const val RADIUS = WIDTH * 0.06f
    }
}
