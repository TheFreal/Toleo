package de.freal.toleo.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import de.freal.toleo.R
import de.freal.toleo.ToleoApp
import de.freal.toleo.ui.library.placeholderColor
import de.freal.toleo.ui.theme.ToleoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Picks a [WidgetStyle] for one widget. The launcher opens it when the widget is placed, and on
 * Android 12+ again from the widget's "Customize" entry. Backing out keeps the widget as it was
 * (and, on placement, cancels adding it).
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_CANCELED, result)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        val db = (application as ToleoApp).container.db

        setContent {
            ToleoTheme {
                var selected by remember { mutableStateOf(WidgetStyle.SOLID) }
                LaunchedEffect(Unit) {
                    val prefs = getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                    selected = WidgetStyle.from(prefs[WidgetStyle.KEY])
                }
                val book by produceState<WidgetBook?>(null) { value = CurrentBookWidget.currentBook(db).first() }
                // The same colours the widget will get, so the cover-themed previews are exact.
                val theme by produceState<WidgetTheme?>(null, book) {
                    value = book?.let { CoverBitmaps(this@WidgetConfigActivity).load(it).theme }
                }
                StylePicker(
                    selected = selected,
                    book = book,
                    theme = theme,
                    onSelect = { selected = it },
                    onDone = {
                        lifecycleScope.launch {
                            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { it[WidgetStyle.KEY] = selected.name }
                            CurrentBookWidget().update(this@WidgetConfigActivity, glanceId)
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StylePicker(
    selected: WidgetStyle,
    book: WidgetBook?,
    theme: WidgetTheme?,
    onSelect: (WidgetStyle) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Widget background") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text("Done") }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(WidgetStyle.entries) { style ->
                StyleOption(style, style == selected, book, theme) { onSelect(style) }
            }
        }
    }
}

@Composable
private fun StyleOption(style: WidgetStyle, selected: Boolean, book: WidgetBook?, theme: WidgetTheme?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(style.label, style = MaterialTheme.typography.titleMedium)
                Text(style.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        StylePreview(style, book, theme)
    }
}

/** Colours of one style, mirroring the widget's palette. With a [backdrop], [background] is the scrim over it. */
private class PreviewColors(
    val background: Color?,
    val text: Color,
    val secondary: Color,
    val bar: Color,
    val track: Color,
    val shadow: Shadow? = null,
    val backdrop: ImageBitmap? = null,
)

/** The style's preview colours in the current mode, themed from the cover like the widget's. */
@Composable
private fun WidgetStyle.previewColors(theme: WidgetTheme?): PreviewColors {
    val base = basePreviewColors()
    val themed = theme?.get(this) ?: return base
    val dark = isSystemInDarkTheme()
    val backdrop = remember(theme) { theme.backdrop.asImageBitmap() }
    return PreviewColors(
        background = themed.background?.of(dark) ?: base.background,
        text = themed.text?.of(dark) ?: base.text,
        secondary = themed.secondary?.of(dark) ?: base.secondary,
        // The transparent style's track is the drawable's dark one, not a themed colour.
        bar = themed.bar.of(dark),
        track = if (this == WidgetStyle.TRANSPARENT) base.track else themed.track.of(dark),
        shadow = base.shadow,
        backdrop = if (this == WidgetStyle.COVER) backdrop else null,
    )
}

@Composable
private fun WidgetStyle.basePreviewColors(): PreviewColors {
    val c = MaterialTheme.colorScheme
    // The widget's actual card colour (see res/color*/widget_background_translucent.xml).
    val card = colorResource(R.color.widget_background_translucent).copy(alpha = 1f)
    return when (this) {
        WidgetStyle.SOLID, WidgetStyle.COVER -> PreviewColors(card, c.onSurface, c.onSurfaceVariant, c.primary, c.surfaceVariant)
        WidgetStyle.TRANSLUCENT, WidgetStyle.THEMED -> PreviewColors(colorResource(R.color.widget_background_translucent), c.onSurface, c.onSurfaceVariant, c.primary, c.surfaceVariant)
        WidgetStyle.DARK -> PreviewColors(WidgetInk.DarkScrim, WidgetInk.White, WidgetInk.WhiteSecondary, WidgetInk.White, WidgetInk.WhiteTrack)
        WidgetStyle.LIGHT -> PreviewColors(WidgetInk.LightScrim, WidgetInk.Ink, WidgetInk.InkSecondary, WidgetInk.Accent, WidgetInk.InkTrack)
        WidgetStyle.TRANSPARENT -> PreviewColors(
            null, WidgetInk.White, WidgetInk.White, WidgetInk.White, Color(0x73000000),
            shadow = Shadow(Color(0xE6000000), Offset(0f, 1.5f), blurRadius = 8f),
        )
    }
}

/** A light-to-dark stand-in wallpaper, so the preview shows how each style holds up on both. */
private val Wallpaper = Brush.linearGradient(
    listOf(Color(0xFFF6EFE4), Color(0xFFC9D6D3), Color(0xFF7E95A3), Color(0xFF2C3440)),
)

/** A mock of the wide widget layout on [Wallpaper]. */
@Composable
private fun StylePreview(style: WidgetStyle, book: WidgetBook?, theme: WidgetTheme?) {
    val p = style.previewColors(theme)
    val title = book?.title ?: "Book title"
    val authors = book?.authors?.takeIf { it.isNotBlank() } ?: "Author"
    val percent = book?.percent ?: 42
    val cardShape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(Wallpaper).padding(14.dp),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .then(if (p.backdrop != null) Modifier.clip(cardShape).paint(BitmapPainter(p.backdrop), contentScale = ContentScale.Crop) else Modifier)
                .then(if (p.background != null) Modifier.clip(cardShape).background(p.background) else Modifier)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(placeholderColor(title)),
            ) {
                book?.coverUrl?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, shadow = p.shadow),
                )
                Text(
                    authors, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = p.secondary, fontSize = 13.sp, shadow = p.shadow),
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.track)) {
                    Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(p.bar))
                }
                Spacer(Modifier.height(4.dp))
                Text("$percent %", style = TextStyle(color = p.secondary, fontSize = 12.sp, shadow = p.shadow))
            }
        }
    }
}
