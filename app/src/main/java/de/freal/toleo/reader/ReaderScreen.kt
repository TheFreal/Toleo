package de.freal.toleo.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.compose.AndroidFragment
import de.freal.toleo.db.AnnotationEntity
import de.freal.toleo.ui.theme.ToleoTheme
import kotlinx.coroutines.delay
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import kotlin.math.roundToInt

private val SidePanelWidth = 360.dp
private const val EXPANDED_MIN_WIDTH_DP = 840

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onClose: () -> Unit,
    /** Installs a navigator factory for one- or two-page mode before the navigator is (re)created. */
    installNavigatorFactory: (twoPages: Boolean, widthDp: Int) -> Unit,
) {
    val prefs by vm.prefsStore.current.collectAsState()
    val chrome by vm.chromeVisible.collectAsState()
    val panel by vm.panel.collectAsState()
    val settingsOpen by vm.settingsOpen.collectAsState()
    val editing by vm.editing.collectAsState()
    val locator by vm.locator.collectAsState()
    val dogear by vm.currentDogear.collectAsState()
    val message by vm.message.collectAsState()
    val readerBg = Color(prefs.theme.background)
    val flip = remember { PageFlipState() }
    var navSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var navTop by remember { mutableIntStateOf(0) }
    val pageInfo by vm.pageInfo.collectAsState()
    val lineHeightDp by vm.lineHeightDp.collectAsState()
    val window = (LocalContext.current as android.app.Activity).window

    ToleoTheme(darkTheme = prefs.theme.isDark) {
        BoxWithConstraints(Modifier.fillMaxSize().background(readerBg)) {
            val expanded = maxWidth.value >= EXPANDED_MIN_WIDTH_DP
            // The panel floats over the book, so opening it never collapses the open-book spread.
            val readerWidthDp = maxWidth.value.roundToInt()
            val twoPages = prefs.twoPages(readerWidthDp)

            LaunchedEffect(readerWidthDp, prefs) { vm.submitPreferences(readerWidthDp) }

            // Flip: snapshot the page, turn underneath without animation, rotate the snapshot away.
            LaunchedEffect(vm, twoPages) {
                vm.flipRequests.collect { forward ->
                    if (flip.running) return@collect
                    val view = vm.publicationView()
                    val shot = view?.let { snapshotOf(it, window) }
                    if (view == null || shot == null) {
                        vm.navigate(forward, animated = true)
                        return@collect
                    }
                    flip.cover(shot, forward, twoPages)
                    // Let the overlay window appear before the page changes underneath it.
                    flip.awaitShown()
                    val before = vm.locator.value
                    if (!vm.navigate(forward, animated = false)) {
                        flip.clear() // first/last page of the book
                        return@collect
                    }
                    // Open book: the back of the turning sheet shows the new spread. One page,
                    // back: the previous page flips down over the old one. Wait until Readium has
                    // moved (it reports locations ~100 ms after settling), then capture the reader
                    // window; the overlay lives in its own window and isn't included.
                    val next = if (twoPages || !forward) {
                        withTimeoutOrNull(700) { vm.locator.first { it != before } }
                        withFrameNanos { }
                        withFrameNanos { }
                        snapshotOf(view, window)
                    } else null
                    flip.turn(next)
                }
            }

            Box(Modifier.fillMaxSize()) {
                // Equal top/bottom margin around the page area (Readium's own margin is set to 0).
                Box(
                    Modifier.fillMaxSize()
                        .wholeLinePage(prefs.verticalMargin, lineHeightDp) { top, size -> navTop = top; navSize = size },
                ) {
                    // Readium's column settings are fixed per navigator: switching between one and
                    // two pages (fold/unfold, settings) recreates it at the current position.
                    key(twoPages) {
                        remember { installNavigatorFactory(twoPages, readerWidthDp) }
                        AndroidFragment<EpubNavigatorFragment>(
                            modifier = Modifier.fillMaxSize(),
                            onUpdate = { nav ->
                                vm.attach(nav)
                                vm.submitPreferences(readerWidthDp)
                            },
                        )
                    }
                }
                // Full-screen window so the turning page's 3D bulge isn't clipped at the margins.
                if (twoPages && prefs.crease) PageCrease(prefs.creaseOpacity.toFloat(), Modifier.fillMaxSize())
                PageFlipLayer(flip, navTop, navSize.width, navSize.height)

                Box(Modifier.fillMaxSize()) {
                    ReaderChrome(
                        visible = chrome,
                        title = vm.title,
                        chapter = locator?.title,
                        bookmarked = dogear != null,
                        progression = locator?.locations?.totalProgression ?: 0.0,
                        pageInfo = pageInfo,
                        onClose = onClose,
                        onBookmark = vm::toggleBookmark,
                        onPanel = { vm.panel.value = if (panel == null) ReaderPanel.CONTENTS else null },
                        onSettings = { vm.settingsOpen.value = true },
                        onSeek = vm::seek,
                    )

                    message?.let {
                        LaunchedEffect(it) { delay(2500); vm.message.value = null }
                        Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding()) { Text(it) }
                    }
                }

                if (expanded && panel != null) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                            .clickable(interactionSource = null, indication = null) { vm.panel.value = null },
                    )
                    Surface(
                        Modifier.align(Alignment.CenterEnd).width(SidePanelWidth).fillMaxHeight(),
                        tonalElevation = 2.dp,
                        shadowElevation = 12.dp,
                    ) {
                        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Navigate", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 16.dp))
                                IconButton(onClick = { vm.panel.value = null }) { Icon(Icons.Filled.Close, "Close panel") }
                            }
                            PanelContent(vm, panel!!)
                        }
                    }
                }
            }

            if (!expanded && panel != null) {
                ModalBottomSheet(onDismissRequest = { vm.panel.value = null }) {
                    Box(Modifier.fillMaxHeight(0.8f)) { PanelContent(vm, panel!!) }
                }
            }
        }

        if (settingsOpen) {
            ModalBottomSheet(onDismissRequest = { vm.settingsOpen.value = false }) {
                SettingsSheet(prefs, vm::updatePrefs)
            }
        }

        editing?.let { a ->
            AnnotationDialog(
                annotation = a,
                onSave = { note, color -> vm.saveAnnotation(a.id, note, color) },
                onDelete = { vm.deleteAnnotation(a.id) },
                onDismiss = { vm.editing.value = null },
            )
        }
    }
}

/** Top bar and bottom progress slider, faded in and out together by a centre tap. */
@Composable
private fun ReaderChrome(
    visible: Boolean,
    title: String,
    chapter: String?,
    bookmarked: Boolean,
    progression: Double,
    pageInfo: PageInfo,
    onClose: () -> Unit,
    onBookmark: () -> Unit,
    onPanel: () -> Unit,
    onSettings: () -> Unit,
    onSeek: (Double) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        chapter?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") } },
                actions = {
                    IconButton(onClick = onBookmark) {
                        Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark page")
                    }
                    IconButton(onClick = onPanel) { Icon(Icons.AutoMirrored.Filled.List, "Contents and notes") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.FormatSize, "Display settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
            )
        }
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            ProgressBar(progression = progression, pageInfo = pageInfo, onSeek = onSeek)
        }
    }
}

@Composable
private fun ProgressBar(progression: Double, pageInfo: PageInfo, onSeek: (Double) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(progression.toFloat()) }
    if (!dragging) value = progression.toFloat()
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = value,
                onValueChange = { dragging = true; value = it },
                onValueChangeFinished = { dragging = false; onSeek(value.toDouble()) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("${(value * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge)
        }
        PageInfoRow(pageInfo, Modifier.padding(horizontal = 20.dp))
      }
    }
}

/** Pages and time left in the chapter on the left; "Page 142 / 380" on the right. */
@Composable
private fun PageInfoRow(info: PageInfo, modifier: Modifier) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val left = info.chapterPagesLeft
        val chapter = buildList {
            if (left != null) add(if (left == 0) "Last page of chapter" else if (left == 1) "1 page left in chapter" else "$left pages left in chapter")
            info.chapterSecondsLeft?.takeIf { left != 0 }?.let { add(formatDuration(it) + " left") }
        }
        Text(
            chapter.joinToString(" · "),
            style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        val page = info.page
        val total = info.totalPages
        Text(
            when {
                page != null && total != null -> "Page $page / $total"
                page != null -> "Page $page"
                else -> ""
            },
            style = style, color = color, maxLines = 1,
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return when {
        minutes < 1 -> "< 1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
    }
}

@Composable
private fun PanelContent(vm: ReaderViewModel, panel: ReaderPanel) {
    val highlights by vm.highlights.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    Column {
        PrimaryTabRow(selectedTabIndex = panel.ordinal) {
            Tab(panel == ReaderPanel.CONTENTS, { vm.panel.value = ReaderPanel.CONTENTS }, text = { Text("Contents") })
            Tab(panel == ReaderPanel.HIGHLIGHTS, { vm.panel.value = ReaderPanel.HIGHLIGHTS }, text = { Text("Highlights") })
            Tab(panel == ReaderPanel.BOOKMARKS, { vm.panel.value = ReaderPanel.BOOKMARKS }, text = { Text("Bookmarks") })
        }
        when (panel) {
            ReaderPanel.CONTENTS -> LazyColumn {
                items(vm.toc) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { vm.goTo(entry.link) }.padding(start = (entry.depth * 16).dp),
                    )
                }
            }
            ReaderPanel.HIGHLIGHTS -> if (highlights.isEmpty()) {
                EmptyHint("Select text and choose Highlight or Note.")
            } else LazyColumn {
                items(highlights, key = { it.id }) { a -> AnnotationRow(a, onClick = { vm.goTo(a) }, onEdit = { vm.editing.value = a }) }
            }
            ReaderPanel.BOOKMARKS -> if (bookmarks.isEmpty()) {
                EmptyHint("Use the bookmark button in the top bar to mark a page.")
            } else LazyColumn {
                items(bookmarks, key = { it.id }) { a ->
                    ListItem(
                        headlineContent = { Text(a.text ?: "Bookmark", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { a.progress?.let { Text("${(it * 100).roundToInt()} %") } },
                        trailingContent = { IconButton(onClick = { vm.deleteAnnotation(a.id) }) { Icon(Icons.Filled.Delete, "Delete bookmark") } },
                        modifier = Modifier.clickable { vm.goTo(a) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(a: AnnotationEntity, onClick: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Box(Modifier.width(4.dp).height(48.dp).background(Color(a.color), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.text ?: "", maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            a.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (a.locatorJson == null) {
                Text("Not located in this file yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Notes, "Edit note") }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSheet(prefs: ReaderPrefs, update: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Column(
        Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Colour", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ReaderTheme.entries.forEach { t ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { update { it.withTheme(t) } }.padding(4.dp),
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color(t.background))
                            .border(
                                width = if (prefs.theme == t) 3.dp else 1.dp,
                                color = if (prefs.theme == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", color = Color(t.text), style = MaterialTheme.typography.titleMedium) }
                    Text(t.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Text("Text size ${(prefs.fontSize * 100).roundToInt()} %", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { update { it.copy(fontSize = (it.fontSize - 0.05).coerceAtLeast(0.5)) } }) {
                Text("A", style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = prefs.fontSize.toFloat(),
                onValueChange = { v -> update { it.copy(fontSize = (v * 20).roundToInt() / 20.0) } },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { update { it.copy(fontSize = (it.fontSize + 0.05).coerceAtMost(2.0)) } }) {
                Text("A", style = MaterialTheme.typography.titleLarge)
            }
        }
        Text("Line spacing ${"%.2f".format(prefs.lineHeight)}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = prefs.lineHeight.toFloat(),
            onValueChange = { v -> update { it.copy(lineHeight = (v * 20).roundToInt() / 20.0) } },
            valueRange = 1.0f..2.2f,
        )
        Text("Top & bottom margin ${prefs.verticalMargin} dp", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = prefs.verticalMargin.toFloat(),
            onValueChange = { v -> update { it.copy(verticalMargin = (v / 4).roundToInt() * 4) } },
            valueRange = 0f..96f,
        )
        Text("Side margins ${(prefs.pageMargins * 100).roundToInt()} %", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = prefs.pageMargins.toFloat(),
            onValueChange = { v -> update { it.copy(pageMargins = (v * 20).roundToInt() / 20.0) } },
            valueRange = 0.2f..2.3f,
        )
        SwitchRow("Justify text", prefs.justify) { v -> update { it.copy(justify = v) } }
        SwitchRow("Page crease (open book)", prefs.crease) { v -> update { it.copy(crease = v) } }
        if (prefs.crease) {
            Text("Crease strength ${(prefs.creaseOpacity * 100).roundToInt()} %", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = prefs.creaseOpacity.toFloat(),
                onValueChange = { v -> update { it.copy(creaseOpacity = (v * 20).roundToInt() / 20.0) } },
                valueRange = 0.05f..1f,
            )
        }

        Text("Page turn", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PageTurn.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = prefs.pageTurn == t,
                    onClick = { update { it.copy(pageTurn = t) } },
                    shape = SegmentedButtonDefaults.itemShape(i, PageTurn.entries.size),
                ) { Text(t.label) }
            }
        }

        Text("Pages", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val labels = mapOf(PageLayout.AUTO to "Auto", PageLayout.SINGLE to "One page", PageLayout.DOUBLE to "Open book")
            PageLayout.entries.forEachIndexed { i, l ->
                SegmentedButton(
                    selected = prefs.pageLayout == l,
                    onClick = { update { it.copy(pageLayout = l) } },
                    shape = SegmentedButtonDefaults.itemShape(i, PageLayout.entries.size),
                ) { Text(labels.getValue(l)) }
            }
        }
        SwitchRow("Scroll instead of pages", prefs.scroll) { v -> update { it.copy(scroll = v) } }
        SwitchRow("Publisher styles (disables justify and colours)", prefs.publisherStyles) { v -> update { it.copy(publisherStyles = v) } }
        SwitchRow("Turn pages with volume keys", prefs.volumeKeys) { v -> update { it.copy(volumeKeys = v) } }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AnnotationDialog(
    annotation: AnnotationEntity,
    onSave: (String?, Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(annotation.id) { mutableStateOf(annotation.note.orEmpty()) }
    var color by remember(annotation.id) { mutableIntStateOf(annotation.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Highlight") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("“${annotation.text.orEmpty()}”", maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HighlightColors.forEach { c ->
                        val size = with(LocalDensity.current) { 32.dp }
                        Box(
                            Modifier.size(size).clip(CircleShape).background(Color(c))
                                .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note, color) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Places the page area between top and bottom margins of [marginDp], shrunk to a whole number of
 * [lineHeightDp] lines and centred. Readium fills each column with whole lines, so otherwise the
 * fractional rest of a line always ends up as extra space at the bottom.
 */
private fun Modifier.wholeLinePage(
    marginDp: Int,
    lineHeightDp: Float,
    onPlaced: (top: Int, size: androidx.compose.ui.unit.IntSize) -> Unit,
) = layout { measurable, constraints ->
    val height = constraints.maxHeight
    val margin = marginDp.dp.roundToPx()
    val available = (height - 2 * margin).coerceAtLeast(0)
    val line = lineHeightDp * density
    val page = if (line > 0f && available > line) {
        kotlin.math.ceil(kotlin.math.floor(available / line) * line).toInt().coerceAtMost(available)
    } else available
    val top = margin + (available - page) / 2
    val placeable = measurable.measure(constraints.copy(minHeight = page, maxHeight = page))
    onPlaced(top, androidx.compose.ui.unit.IntSize(placeable.width, placeable.height))
    layout(constraints.maxWidth, height) { placeable.place(0, top) }
}

/**
 * The spine of an open book: a soft shadow down the centre of the screen, darkest at the fold,
 * with a faint highlight on each side where the pages curve up. Draws only (no pointer input),
 * so taps go through to the pages.
 */
@Composable
private fun PageCrease(opacity: Float, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val width = 72.dp.toPx()
        val left = (size.width - width) / 2
        fun shadow(a: Float) = Color.Black.copy(alpha = (a * opacity).coerceIn(0f, 1f))
        fun light(a: Float) = Color.White.copy(alpha = (a * opacity).coerceIn(0f, 1f))
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.30f to light(0.06f),
                0.42f to shadow(0.08f),
                0.50f to shadow(0.30f),
                0.58f to shadow(0.08f),
                0.70f to light(0.06f),
                1.00f to Color.Transparent,
                startX = left,
                endX = left + width,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
            size = androidx.compose.ui.geometry.Size(width, size.height),
        )
    }
}
