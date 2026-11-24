package de.freal.toleo.widget

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.freal.toleo.MainActivity
import de.freal.toleo.R
import de.freal.toleo.ToleoApp
import de.freal.toleo.db.ToleoDatabase
import de.freal.toleo.ui.library.currentlyReading
import de.freal.toleo.ui.theme.Dark
import de.freal.toleo.ui.theme.Light
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** What the widget shows of the current book. [percent] is whole percent so page turns don't redraw it. */
data class WidgetBook(
    val publicationId: String,
    val title: String,
    val authors: String,
    val coverUrl: String?,
    val percent: Int,
)

private class WidgetContent(val book: WidgetBook?, val cover: Bitmap?, val theme: WidgetTheme?)

/**
 * Home screen widget for the book being read (the library's current-book panel). Small, it is
 * just the cover; with room to spare it adds title, author and progress. A tap opens the book.
 * Its background is a per-widget [WidgetStyle], chosen in [WidgetConfigActivity].
 */
class CurrentBookWidget : GlanceAppWidget() {
    // Exact, so the layout can size the cover to the space the launcher actually gives it.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = (context.applicationContext as ToleoApp).container.db
        val covers = CoverBitmaps(context)
        val content = currentBook(db).map { book ->
            val cover = book?.let { covers.load(it) }
            WidgetContent(book, cover?.bitmap, cover?.theme)
        }
        val initial = content.first()
        provideContent {
            val state by content.collectAsState(initial)
            val style = WidgetStyle.from(currentState(WidgetStyle.KEY))
            GlanceTheme(colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else FallbackColors) {
                WidgetBody(state, style.palette(state.theme))
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK = "de.freal.toleo.extra.OPEN_BOOK"

        /** The current book, emitting only when something the widget shows changes. */
        fun currentBook(db: ToleoDatabase): Flow<WidgetBook?> = db.books().observeLibrary()
            .map { books ->
                currentlyReading(books)?.let {
                    WidgetBook(
                        publicationId = it.publicationId,
                        title = it.title,
                        authors = it.authors,
                        coverUrl = it.coverUrl,
                        percent = ((it.progress ?: 0.0) * 100).roundToInt().coerceIn(0, 100),
                    )
                }
            }
            .distinctUntilChanged()
    }
}

class CurrentBookWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CurrentBookWidget()
}

/** Before Android 12 there are no dynamic colours; use the app's own. */
private val FallbackColors = ColorProviders(light = Light, dark = Dark)

/** Below this the widget is just the cover. */
private val DETAILED_MIN = DpSize(180.dp, 72.dp)

/**
 * Colours for one [WidgetStyle]. [shadowed] text is drawn by [ShadowText], since Glance text can't
 * cast shadows; its bar is tinted [shadowBar] where the launcher allows. With a [backdrop], the
 * [background] is a scrim over it.
 */
private class Palette(
    val background: ColorProvider?,
    val text: ColorProvider,
    val secondary: ColorProvider,
    val bar: ColorProvider,
    val track: ColorProvider,
    val shadowed: Boolean = false,
    val shadowBar: DayNight? = null,
    val backdrop: Bitmap? = null,
)

/** The style's colours, with the progress bar (and for the cover-themed styles, everything) taken from [theme]. */
@Composable
private fun WidgetStyle.palette(theme: WidgetTheme?): Palette {
    val base = basePalette()
    val themed = theme?.get(this) ?: return base
    return Palette(
        background = themed.background?.provider() ?: base.background,
        text = themed.text?.provider() ?: base.text,
        secondary = themed.secondary?.provider() ?: base.secondary,
        bar = themed.bar.provider(),
        track = themed.track.provider(),
        shadowed = base.shadowed,
        shadowBar = if (base.shadowed) themed.bar else null,
        backdrop = if (this == WidgetStyle.COVER) theme.backdrop else null,
    )
}

/** The style without a cover to theme from; the cover-themed ones fall back to their plain cousins. */
@Composable
private fun WidgetStyle.basePalette(): Palette = when (this) {
    WidgetStyle.SOLID, WidgetStyle.COVER -> GlanceTheme.colors.let {
        Palette(it.widgetBackground, it.onSurface, it.onSurfaceVariant, it.primary, it.surfaceVariant)
    }
    // A colour resource, so it follows wallpaper colours and dark mode exactly like SOLID.
    WidgetStyle.TRANSLUCENT, WidgetStyle.THEMED -> GlanceTheme.colors.let {
        Palette(ColorProvider(R.color.widget_background_translucent), it.onSurface, it.onSurfaceVariant, it.primary, it.surfaceVariant)
    }
    WidgetStyle.DARK -> Palette(
        ColorProvider(WidgetInk.DarkScrim), ColorProvider(WidgetInk.White), ColorProvider(WidgetInk.WhiteSecondary),
        ColorProvider(WidgetInk.White), ColorProvider(WidgetInk.WhiteTrack),
    )
    WidgetStyle.LIGHT -> Palette(
        ColorProvider(WidgetInk.LightScrim), ColorProvider(WidgetInk.Ink), ColorProvider(WidgetInk.InkSecondary),
        ColorProvider(WidgetInk.Accent), ColorProvider(WidgetInk.InkTrack),
    )
    WidgetStyle.TRANSPARENT -> Palette(
        null, ColorProvider(WidgetInk.White), ColorProvider(WidgetInk.White),
        ColorProvider(WidgetInk.White), ColorProvider(WidgetInk.WhiteTrack), shadowed = true,
    )
}

@Composable
private fun WidgetBody(state: WidgetContent, palette: Palette) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(openIntent(context, state.book))
    val book = state.book
    val cover = state.cover
    when {
        book == null || cover == null -> Empty(open, size, palette)
        size.width >= DETAILED_MIN.width && size.height >= DETAILED_MIN.height -> Detailed(book, cover, size, open, palette)
        else -> CoverOnly(book, cover, open, palette)
    }
}

/** Brings the app forward (closing an open reader) and has the library open the book. */
private fun openIntent(context: Context, book: WidgetBook?) = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    book?.let { putExtra(CurrentBookWidget.EXTRA_OPEN_BOOK, it.publicationId) }
}

/**
 * The style's background in the launcher's widget shape, filling the widget; [content] gets the
 * modifier to lay itself out with. With a backdrop, that is the blurred cover under the scrim.
 */
@Composable
private fun Card(palette: Palette, content: @Composable (GlanceModifier) -> Unit) {
    val backdrop = palette.backdrop
    val scrim = palette.background
    if (backdrop == null || scrim == null) {
        content(GlanceModifier.fillMaxSize().card(palette))
        return
    }
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().rounded().background(ImageProvider(backdrop), ContentScale.Crop)) {
        content(GlanceModifier.fillMaxSize().rounded().background(scrim))
    }
}

/** The style's colour in the launcher's widget shape. */
private fun GlanceModifier.card(palette: Palette): GlanceModifier {
    val base = appWidgetBackground()
    val color = palette.background ?: return base
    return base.rounded().background(color)
}

/** Rounded like the system's widgets, which only are from Android 12. */
private fun GlanceModifier.rounded(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cornerRadius(android.R.dimen.system_app_widget_background_radius) else this

@Composable
private fun CoverOnly(book: WidgetBook, cover: Bitmap, open: Action, palette: Palette) {
    // Transparent: the cover itself is the widget. Otherwise it sits on the card.
    val inset = if (palette.background == null) 0.dp else 8.dp
    Card(palette) { card ->
        Box(card.padding(inset).clickable(open), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = book.title,
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Detailed(book: WidgetBook, cover: Bitmap, size: DpSize, open: Action, palette: Palette) {
    val padding = 12.dp
    val coverWidth = minOf((size.height - padding * 2) * (2f / 3f), size.width * 0.4f)
    val roomy = size.height >= 96.dp
    Card(palette) { card ->
        Row(
            card.padding(padding).clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = book.title,
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.width(coverWidth).fillMaxHeight(),
            )
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Label(book.title, LabelKind.TITLE, titleLines(size.height), palette)
                if (book.authors.isNotBlank() && roomy) {
                    Spacer(GlanceModifier.height(2.dp))
                    Label(book.authors, LabelKind.BODY, 1, palette)
                }
                Spacer(GlanceModifier.height(if (roomy) 10.dp else 6.dp))
                Progress(book.percent, palette)
                Spacer(GlanceModifier.height(4.dp))
                Label("${book.percent} %", LabelKind.CAPTION, 1, palette)
            }
        }
    }
}

private fun titleLines(height: Dp) = when {
    height >= 150.dp -> 4
    height >= 96.dp -> 2
    else -> 1
}

@Composable
private fun Empty(open: Action, size: DpSize, palette: Palette) {
    Box(GlanceModifier.fillMaxSize().card(palette).padding(8.dp).clickable(open), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(ImageProvider(R.mipmap.ic_launcher), contentDescription = null, modifier = GlanceModifier.size(40.dp))
            if (size.width >= 110.dp && size.height >= 90.dp) {
                Spacer(GlanceModifier.height(6.dp))
                Label("Nothing in progress", LabelKind.CAPTION, 2, palette, centered = true)
            }
        }
    }
}

private enum class LabelKind(val sizeSp: Float, @LayoutRes val shadowLayout: Int, val medium: Boolean, val secondary: Boolean) {
    TITLE(15f, R.layout.widget_shadow_title, medium = true, secondary = false),
    BODY(13f, R.layout.widget_shadow_text, medium = false, secondary = true),
    CAPTION(12f, R.layout.widget_shadow_text, medium = false, secondary = true),
}

@Composable
private fun Label(text: String, kind: LabelKind, maxLines: Int, palette: Palette, centered: Boolean = false) {
    if (palette.shadowed) {
        ShadowText(text, kind, maxLines, centered)
        return
    }
    Text(
        text,
        style = TextStyle(
            color = if (kind.secondary) palette.secondary else palette.text,
            fontSize = kind.sizeSp.sp,
            fontWeight = if (kind.medium) FontWeight.Medium else FontWeight.Normal,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        ),
        maxLines = maxLines,
    )
}

/**
 * White text with two shadows (a wide soft halo under a tight dark edge), so it reads on any
 * wallpaper. The layout stacks two identical TextViews, one per shadow.
 */
@Composable
private fun ShadowText(text: String, kind: LabelKind, maxLines: Int, centered: Boolean) {
    val views = RemoteViews(LocalContext.current.packageName, kind.shadowLayout)
    for (id in intArrayOf(R.id.shadow_wide, R.id.shadow_tight)) {
        views.setTextViewText(id, text)
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, kind.sizeSp)
        views.setInt(id, "setMaxLines", maxLines)
        if (centered) views.setInt(id, "setGravity", Gravity.CENTER_HORIZONTAL)
    }
    AndroidRemoteViews(views, GlanceModifier.fillMaxWidth())
}

@Composable
private fun Progress(percent: Int, palette: Palette) {
    if (palette.shadowed) {
        // A dark track and an outlined white bar, visible on light and dark wallpapers alike.
        val views = RemoteViews(LocalContext.current.packageName, R.layout.widget_shadow_progress)
        views.setProgressBar(R.id.shadow_progress, 100, percent, false)
        views.setProgressBar(R.id.shadow_progress_outline, 100, percent, false)
        // The fill takes the cover's colour; the outline above it stays dark. Tinting needs Android 12.
        val tint = palette.shadowBar
        if (tint != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.shadow_progress,
                "setProgressTintList",
                ColorStateList.valueOf(tint.day.toArgb()),
                ColorStateList.valueOf(tint.night.toArgb()),
            )
        }
        AndroidRemoteViews(views, GlanceModifier.fillMaxWidth())
        return
    }
    LinearProgressIndicator(
        progress = percent / 100f,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = palette.bar,
        backgroundColor = palette.track,
    )
}
