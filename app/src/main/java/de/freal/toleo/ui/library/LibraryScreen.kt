package de.freal.toleo.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import de.freal.toleo.reader.ReaderActivity
import de.freal.toleo.sync.SyncStatus
import de.freal.toleo.ui.UploadDialog
import java.text.DateFormat
import java.util.Date
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Side margin of everything except the full-bleed top panel. */
private val SideMargin = 20.dp

/** Wide screens: the hero and the shelf stop growing here while the grid adds columns. */
private val ContentMaxWidth = 560.dp

private val CoverShape = RoundedCornerShape(8.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onAccount: () -> Unit, onNeedsLogin: () -> Unit) {
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val opening by vm.opening.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val authProblem by vm.authProblem.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var protected by remember { mutableStateOf<BookWithState?>(null) }
    val recentExpanded by vm.recentExpanded.collectAsState()
    // The hidden list starts collapsed every time; it survives rotation but not an app restart.
    var hiddenExpanded by rememberSaveable { mutableStateOf(false) }
    val searching = state.query.isNotBlank()
    var searchFocused by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var collectionsOpen by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    val uploadDraft by vm.uploadDraft.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::startUpload) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                LibraryEvent.OpenReader -> context.startActivity(ReaderActivity.intent(context))
                is LibraryEvent.OpenExternal -> openExternal(context, e)
                is LibraryEvent.Protected -> protected = e.book
                is LibraryEvent.Message -> snackbar.showSnackbar(e.text)
                // Launched separately so a waiting Undo doesn't hold back the next event (e.g. opening a book).
                is LibraryEvent.Dismissed -> scope.launch {
                    val result = snackbar.showSnackbar("Dismissed ${describe(e.books)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) vm.undoDismiss(e.books)
                }
                is LibraryEvent.Hidden -> scope.launch {
                    val result = snackbar.showSnackbar("Hid ${describe(e.books)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) vm.unhide(e.books)
                }
            }
        }
    }

    // Back leaves selecting before it leaves the screen.
    BackHandler(enabled = state.selecting, onBack = vm::clearSelection)

    // Grid index of the search field, so focusing it can scroll it to the top instead of hiding the hero.
    val searchIndex = 1 +
        (if (authProblem != null) 1 else 0) +
        (if (state.recent.isEmpty()) 0 else if (recentExpanded) 1 + state.recent.size else 1) +
        1
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(searchFocused, imeVisible) {
        if (searchFocused) gridState.animateScrollToItem(searchIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // The status bar is handled by the top panel, which extends under it.
        contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val pullState = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            // At rest its edge peeks out below the status bar, visible on the cover backdrop.
                            .graphicsLayer { alpha = if (refreshing || pullState.distanceFraction > 0f) 1f else 0f },
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction)),
            ) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(100.dp),
                    contentPadding = PaddingValues(start = SideMargin, end = SideMargin, bottom = padding.calculateBottomPadding() + 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    fullWidth("panel") {
                        TopPanel(
                            book = state.continueReading,
                            opening = state.continueReading?.publicationId?.let { it == opening } == true,
                            sync = sync,
                            onUpload = { picker.launch(arrayOf("application/epub+zip", "application/pdf")) },
                            onSync = vm::refresh,
                            onAccount = onAccount,
                            onOpen = vm::open,
                        )
                    }
                    authProblem?.let { problem ->
                        fullWidth("banner") { CloudBanner(problem, retrying = refreshing, onRetry = vm::refresh, onSignIn = onNeedsLogin) }
                    }
                    if (state.recent.isNotEmpty()) {
                        fullWidth("recent-heading") {
                            SectionHeading("Also reading") {
                                // ExpandLess turned upside down is ExpandMore, so the chevron flips smoothly.
                                val angle by animateFloatAsState(if (recentExpanded) 0f else 180f, label = "recent chevron")
                                IconButton(onClick = { vm.setRecentExpanded(!recentExpanded) }) {
                                    Icon(
                                        Icons.Filled.ExpandLess,
                                        if (recentExpanded) "Collapse also reading" else "Expand also reading",
                                        Modifier.rotate(angle),
                                    )
                                }
                            }
                        }
                        if (recentExpanded) {
                            // Plain grid items, so the shelf tiles match the Library tiles at any width.
                            // The key is prefixed because the same book also appears in the Library grid.
                            items(state.recent, key = { "recent-" + it.publicationId }) { book -> LibraryTile(book, TileSection.RECENT, state, opening, vm) }
                        }
                    }
                    fullWidth("library-heading") {
                        SectionHeading("Library") {
                            SortControl(sort = state.sort, filter = state.filter, onSort = vm::setSort, onFilter = vm::setFilter)
                        }
                    }
                    fullWidth("search") {
                        SearchField(state.query, vm::setQuery, onFocus = { searchFocused = it })
                    }
                    if (state.collections.isNotEmpty() || state.collection != null || state.filter != LibraryFilter.ALL) {
                        fullWidth("chips") {
                            ChipRow(state, onCollection = vm::setCollection, onClearFilter = { vm.setFilter(LibraryFilter.ALL) })
                        }
                    }
                    if (state.books.isEmpty() && !refreshing) {
                        fullWidth("empty") {
                            Text(
                                "No books here yet. Pull down to refresh, or upload an EPUB.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(48.dp),
                            )
                        }
                    }
                    items(state.books, key = { it.publicationId }) { book -> LibraryTile(book, TileSection.LIBRARY, state, opening, vm) }
                    if (state.hiddenBooks.isNotEmpty()) {
                        fullWidth("hidden-heading") {
                            SectionHeading("Hidden (${state.hiddenBooks.size})") {
                                // While searching, the matches are shown and the toggle is out of the way.
                                if (!searching) {
                                    val angle by animateFloatAsState(if (hiddenExpanded) 0f else 180f, label = "hidden chevron")
                                    IconButton(
                                        onClick = {
                                            hiddenExpanded = !hiddenExpanded
                                            if (hiddenExpanded) {
                                                // The list opens below the fold, so bring its heading to the top.
                                                val index = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hidden-heading" }?.index
                                                if (index != null) scope.launch { gridState.animateScrollToItem(index) }
                                            }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Filled.ExpandLess,
                                            if (hiddenExpanded) "Collapse hidden books" else "Expand hidden books",
                                            Modifier.rotate(angle),
                                        )
                                    }
                                }
                            }
                        }
                        if (hiddenExpanded || searching) {
                            items(state.hiddenBooks, key = { "hidden-" + it.publicationId }) { book -> LibraryTile(book, TileSection.HIDDEN, state, opening, vm) }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = state.selecting,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                SelectionBar(state, vm, onCollections = { collectionsOpen = true }, onDelete = { confirmBatchDelete = true })
            }
        }
    }

    if (collectionsOpen && state.selecting) {
        CollectionsDialog(
            selected = state.selectedBooks,
            known = state.collections,
            onApply = { add, remove -> collectionsOpen = false; vm.updateSelectedCollections(add, remove) },
            onDismiss = { collectionsOpen = false },
        )
    }

    if (confirmBatchDelete) {
        val uploads = state.selectionActions().deleteFromCloud
        if (uploads.isEmpty()) {
            confirmBatchDelete = false
        } else {
            AlertDialog(
                onDismissRequest = { confirmBatchDelete = false },
                title = { Text("Delete from cloud?") },
                text = { Text("${describe(uploads).replaceFirstChar { it.uppercase() }} will be removed from your tolino cloud on all devices.") },
                confirmButton = { TextButton(onClick = { confirmBatchDelete = false; vm.deleteSelectedFromCloud() }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmBatchDelete = false }) { Text("Cancel") } },
            )
        }
    }

    uploadDraft?.let { draft ->
        UploadDialog(
            meta = draft.meta,
            note = null,
            onConfirm = { edits -> edits?.let(vm::confirmUpload) },
            onDismiss = vm::cancelUpload,
        )
    }

    protected?.let { book ->
        AlertDialog(
            onDismissRequest = { protected = null },
            title = { Text("Copy-protected title") },
            text = {
                Text(
                    "“${book.title}” is protected with ${if (book.drm == DrmStatus.LCP) "Readium LCP" else "Adobe DRM"}. " +
                        "Only licensed reading apps can open it. Your position and highlights still sync, " +
                        "so you can read it in the tolino app and continue elsewhere.",
                )
            },
            confirmButton = { TextButton(onClick = { protected = null; openTolinoApp(context) }) { Text("Open tolino app") } },
            dismissButton = { TextButton(onClick = { protected = null }) { Text("Close") } },
        )
    }
}

/** Full-width grid item that fades and slides when items above it appear, disappear or move. */
private fun LazyGridScope.fullWidth(key: String, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.animateItem()) { content() }
    }

/** Lets a full-width grid item ignore the grid's side padding of [margin] and run edge to edge. */
private fun Modifier.fullBleed(margin: Dp) = layout { measurable, constraints ->
    val extra = (margin * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * App bar and the current book on one surface that reaches under the status bar, backed by the
 * current book's blurred cover and themed from it (see [rememberCoverTheme]).
 */
@Composable
private fun TopPanel(
    book: BookWithState?,
    opening: Boolean,
    sync: SyncStatus,
    onUpload: () -> Unit,
    onSync: () -> Unit,
    onAccount: () -> Unit,
    onOpen: (BookWithState) -> Unit,
) {
    val app = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = remember(app) { app.forHero() }) {
        TopPanelContent(book, opening, sync, onUpload, onSync, onAccount, onOpen)
    }
}

@Composable
private fun TopPanelContent(
    book: BookWithState?,
    opening: Boolean,
    sync: SyncStatus,
    onUpload: () -> Unit,
    onSync: () -> Unit,
    onAccount: () -> Unit,
    onOpen: (BookWithState) -> Unit,
) {
    val theme = rememberCoverTheme(book?.coverUrl)
    val shape = MaterialTheme.shapes.extraLarge.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier.fullBleed(SideMargin).fillMaxWidth(),
    ) {
        Box {
            CoverBackdrop(if (book != null) theme else null, Modifier.matchParentSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TopAppBar(
                    title = { Text("Toleo") },
                    actions = {
                        IconButton(onClick = onUpload) { Icon(Icons.Filled.Upload, "Upload an EPUB or PDF") }
                        SyncIndicator(sync, onClick = onSync)
                        IconButton(onClick = onAccount) { Icon(Icons.Filled.AccountCircle, "Account and devices") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
                if (book != null) {
                    CurrentBook(book, opening, theme, onOpen = { onOpen(book) })
                    Spacer(Modifier.height(32.dp))
                } else {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CurrentBook(book: BookWithState, opening: Boolean, theme: CoverTheme?, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent by animateColorAsState(theme?.accent ?: colors.primary, label = "hero accent")
    val onAccent by animateColorAsState(theme?.onAccent ?: colors.onPrimary, label = "hero on accent")
    val track by animateColorAsState(theme?.track ?: ProgressIndicatorDefaults.linearTrackColor, label = "hero track")
    Column(
        Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = SideMargin).padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(book, Modifier.width(160.dp), elevation = 24.dp, onClick = onOpen)
        Spacer(Modifier.height(28.dp))
        Text("Currently reading", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.authors.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                book.authors,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        val p = (book.progress ?: 0.0).toFloat()
        Column(Modifier.width(240.dp)) {
            LinearProgressIndicator(progress = { p }, color = accent, trackColor = track, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${(p * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                book.lastOpened?.let { time ->
                    val ago = DateUtils.getRelativeTimeSpanString(
                        time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                    )
                    Text("Last read $ago", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpen,
            enabled = !opening,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
        ) {
            if (opening) {
                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = LocalContentColor.current)
            } else {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
            }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Continue")
        }
    }
}

@Composable
private fun SectionHeading(title: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
private fun SortControl(
    sort: SortChoice,
    filter: LibraryFilter,
    onSort: (LibrarySort) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Sort: ${sort.sort.label}")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuCaption("Sort by")
            // Tapping the active option again reverses it, so the arrow shows the current direction.
            LibrarySort.entries.forEach { s ->
                val active = s == sort.sort
                CheckRow(
                    label = s.menuLabel,
                    checked = active,
                    icon = if (sort.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    iconDescription = if (sort.ascending) "Ascending, tap to reverse" else "Descending, tap to reverse",
                ) { open = false; onSort(s) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuCaption("Show only")
            // Single choice like the old chips: tapping the active row clears it.
            LibraryFilter.entries.filter { it != LibraryFilter.ALL }.forEach { f ->
                CheckRow(f.label, checked = f == filter) { open = false; onFilter(if (f == filter) LibraryFilter.ALL else f) }
            }
        }
    }
}

@Composable
private fun MenuCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    icon: ImageVector = Icons.Filled.Check,
    iconDescription: String? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (checked) FontWeight.SemiBold else null) },
        trailingIcon = if (checked) {
            { Icon(icon, iconDescription) }
        } else {
            null
        },
        onClick = onClick,
        modifier = Modifier.semantics { selected = checked },
    )
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    val focus = LocalFocusManager.current
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Title or author") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "Clear search") } }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
    )
}

/** Active "Show only" filter (from the sort menu) first, then All and the collections. */
@Composable
private fun ChipRow(state: LibraryState, onCollection: (String) -> Unit, onClearFilter: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.filter != LibraryFilter.ALL) {
            InputChip(
                selected = true,
                onClick = onClearFilter,
                label = { Text(state.filter.label) },
                trailingIcon = { Icon(Icons.Filled.Close, "Clear ${state.filter.label} filter", Modifier.size(InputChipDefaults.IconSize)) },
            )
        }
        if (state.collections.isNotEmpty() || state.collection != null) {
            // setCollection toggles, so passing the active one clears it.
            FilterChip(selected = state.collection == null, onClick = { state.collection?.let(onCollection) }, label = { Text("All") })
            state.collections.forEach { c ->
                FilterChip(selected = state.collection == c, onClick = { onCollection(c) }, label = { Text(c) })
            }
        }
    }
}

/** Shown instead of kicking the user to the login screen when the cloud can't be reached. */
@Composable
private fun CloudBanner(problem: de.freal.toleo.tolino.AuthProblem, retrying: Boolean, onRetry: () -> Unit, onSignIn: () -> Unit) {
    val blocked = problem == de.freal.toleo.tolino.AuthProblem.BLOCKED
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (blocked) "Thalia is blocking this network" else "Thalia sign-in expired",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (blocked) {
                    "Downloaded books open as usual, and your progress is saved on this phone. To sync, connect " +
                        "a VPN, or set a German proxy under Account → Access from abroad. Toleo reconnects on its own when the network changes."
                } else {
                    "Downloaded books open as usual, and your progress is saved on this phone. Sign in again to sync, " +
                        "or turn on automatic re-login under Account → Access from abroad."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (blocked) {
                    TextButton(onClick = onRetry, enabled = !retrying) { Text(if (retrying) "Retrying…" else "Retry") }
                } else {
                    TextButton(onClick = onSignIn) { Text("Sign in") }
                }
            }
        }
    }
}

@Composable
private fun SyncIndicator(status: SyncStatus, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when (status) {
            SyncStatus.Running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            is SyncStatus.Failed -> Icon(Icons.Filled.CloudOff, "Sync failed, tap to retry", tint = MaterialTheme.colorScheme.error)
            is SyncStatus.Done -> Icon(Icons.Filled.CloudDone, "Synced at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.at))}")
            SyncStatus.Idle -> Icon(Icons.Filled.Cloud, "Sync now")
        }
    }
}

/** "“Title”" for one book, "3 books" for more. */
private fun describe(books: List<BookWithState>) = if (books.size == 1) "“${books.first().title}”" else "${books.size} books"

private enum class TileSection { RECENT, LIBRARY, HIDDEN }

/**
 * A tile wired to the view model. Normally a tap opens the book and a long-press opens its menu, whose first
 * entry starts selecting. While selecting, a tap or long-press toggles the book instead.
 */
@Composable
private fun LazyGridItemScope.LibraryTile(
    book: BookWithState,
    section: TileSection,
    state: LibraryState,
    opening: String?,
    vm: LibraryViewModel,
) {
    BookTile(
        modifier = Modifier.animateItem(),
        book = book,
        downloadProgress = state.downloads[book.publicationId],
        opening = opening == book.publicationId,
        selecting = state.selecting,
        selected = book.publicationId in state.selectedIds,
        onToggleSelected = { vm.toggleSelected(book) },
        onOpen = { vm.open(book) },
        onDownload = { vm.download(book) },
        onRemoveDownload = { vm.removeDownload(book) },
        onFinished = { vm.setFinished(book, it) },
        onDelete = { vm.deleteFromCloud(book) },
        onDismiss = if (section == TileSection.RECENT) ({ vm.dismiss(book) }) else null,
        onAddToShelf = if (section == TileSection.LIBRARY && book.publicationId !in state.shelfIds) ({ vm.addToRecent(book) }) else null,
        onHide = if (section != TileSection.HIDDEN) ({ vm.hide(book) }) else null,
        onUnhide = if (section == TileSection.HIDDEN) ({ vm.unhide(book) }) else null,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    modifier: Modifier = Modifier,
    book: BookWithState,
    downloadProgress: Float?,
    opening: Boolean,
    /** Some book is selected: tiles show a mark, and a tap or long-press toggles instead of opening or showing the menu. */
    selecting: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onFinished: (Boolean) -> Unit,
    onDelete: () -> Unit,
    /** Set on the "Also reading" shelf only. */
    onDismiss: (() -> Unit)? = null,
    /** Set on Library tiles of books that aren't on the shelf yet. */
    onAddToShelf: (() -> Unit)? = null,
    /** Set on shelf and Library tiles. */
    onHide: (() -> Unit)? = null,
    /** Set on tiles in the "Hidden" list. */
    onUnhide: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier
            .semantics { if (selecting) this.selected = selected }
            .combinedClickable(
                onClick = if (selecting) onToggleSelected else onOpen,
                onLongClick = if (selecting) onToggleSelected else ({ menu = true }),
            ),
    ) {
        Box {
            Cover(book, Modifier.fillMaxWidth())
            if (selected) {
                val primary = MaterialTheme.colorScheme.primary
                Box(Modifier.matchParentSizeCompat().clip(CoverShape).background(primary.copy(alpha = 0.28f)).border(3.dp, primary, CoverShape))
            }
            // Status badges
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (book.drm == DrmStatus.ADOBE || book.drm == DrmStatus.LCP) Badge(Icons.Filled.Lock, "Copy-protected")
                if (book.format == "PDF") Badge(Icons.Filled.PictureAsPdf, "PDF")
                if (book.localPath != null) Badge(Icons.Filled.DownloadDone, "On device")
            }
            if (selecting) SelectMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (downloadProgress != null || opening) {
                Box(Modifier.matchParentSizeCompat().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    if (downloadProgress != null && downloadProgress > 0f) {
                        CircularProgressIndicator(progress = { downloadProgress }, color = Color.White)
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
            val p = book.progress ?: 0.0
            if (p > 0 || book.finished) {
                LinearProgressIndicator(
                    progress = { if (book.finished) 1f else p.toFloat() },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onToggleSelected() })
                HorizontalDivider()
                if (onDismiss != null) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Dismiss")
                                Text(
                                    "Hidden here until you open it again",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { menu = false; onDismiss() },
                    )
                    HorizontalDivider()
                }
                if (onAddToShelf != null) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Add to Also reading")
                                Text(
                                    "Keeps it on the shelf until you dismiss it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { menu = false; onAddToShelf() },
                    )
                    HorizontalDivider()
                }
                if (onUnhide != null) {
                    DropdownMenuItem(text = { Text("Show in library") }, onClick = { menu = false; onUnhide() })
                    HorizontalDivider()
                }
                if (book.localPath == null) DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload() })
                else DropdownMenuItem(text = { Text("Remove from device") }, onClick = { menu = false; onRemoveDownload() })
                DropdownMenuItem(
                    text = { Text(if (book.finished) "Mark as unread" else "Mark as finished") },
                    onClick = { menu = false; onFinished(!book.finished) },
                )
                if (onHide != null) DropdownMenuItem(text = { Text("Hide from library") }, onClick = { menu = false; onHide() })
                if (book.isUpload) DropdownMenuItem(text = { Text("Delete from cloud") }, onClick = { menu = false; confirmDelete = true })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            book.authors.ifBlank { " " },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete from cloud?") },
            text = { Text("“${book.title}” will be removed from your tolino cloud on all devices.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Round check mark in a tile's corner while selecting: filled when picked, an empty ring otherwise. */
@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surface.copy(alpha = 0.85f))
            .border(2.dp, if (selected) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = colors.onPrimary)
    }
}

/** Pinned over the top of the screen while books are selected: count, select all, collections and the other batch actions. */
@Composable
private fun SelectionBar(
    state: LibraryState,
    vm: LibraryViewModel,
    onCollections: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = state.selectionActions()
    val total = state.selectedBooks.size
    var menu by remember { mutableStateOf(false) }

    @Composable
    fun Item(label: String, books: List<BookWithState>, onClick: () -> Unit) {
        if (books.isEmpty()) return
        // With several books selected, say how many the action reaches: it skips the ones it wouldn't change.
        DropdownMenuItem(
            text = { Text(if (total > 1) "$label (${books.size})" else label) },
            onClick = { menu = false; onClick() },
        )
    }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 4.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancel selection") }
            Text("$total selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::selectAllVisible, enabled = state.books.any { it.publicationId !in state.selectedIds }) {
                Icon(Icons.Filled.SelectAll, "Select all shown")
            }
            IconButton(onClick = onCollections) { Icon(Icons.Filled.CollectionsBookmark, "Collections") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Item("Download", actions.download, vm::downloadSelected)
                    Item("Remove from device", actions.removeDownload, vm::removeSelectedDownloads)
                    Item("Mark as finished", actions.markFinished) { vm.setSelectedFinished(true) }
                    Item("Mark as unread", actions.markUnread) { vm.setSelectedFinished(false) }
                    Item("Add to Also reading", actions.addToShelf, vm::addSelectedToRecent)
                    Item("Dismiss from Also reading", actions.dismiss, vm::dismissSelected)
                    Item("Hide from library", actions.hide, vm::hideSelected)
                    Item("Show in library", actions.unhide, vm::unhideSelected)
                    if (actions.deleteFromCloud.isNotEmpty()) {
                        HorizontalDivider()
                        Item("Delete from cloud", actions.deleteFromCloud, onDelete)
                    }
                }
            }
        }
    }
}

/**
 * Edits the collections of the selected books. A box is checked when every selected book is in the
 * collection, dashed when only some are; tapping sets all or none. Typing a name creates a collection,
 * which exists as soon as a book is in it.
 */
@Composable
private fun CollectionsDialog(
    selected: List<BookWithState>,
    known: List<String>,
    onApply: (add: Set<String>, remove: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val counts = remember(selected) { selected.flatMap { it.collectionList().distinct() }.groupingBy { it }.eachCount() }
    val existing = remember(selected, known) { (known + counts.keys).distinct() }
    var created by remember { mutableStateOf(emptyList<String>()) }
    val names = (existing + created).sortedWith(String.CASE_INSENSITIVE_ORDER)
    // What the user picked for a collection; absent means "leave as is".
    val picks = remember { mutableStateMapOf<String, Boolean>() }
    var draft by remember { mutableStateOf("") }

    fun stateOf(name: String): ToggleableState {
        picks[name]?.let { return if (it) ToggleableState.On else ToggleableState.Off }
        return when (counts[name] ?: 0) {
            0 -> ToggleableState.Off
            selected.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    }

    /** Checks the collection called [raw] (any case), creating it when it's new. Returns false for a blank name. */
    fun addDraft(raw: String): Boolean {
        val name = raw.replace('\u001F', ' ').trim()
        if (name.isEmpty()) return false
        val match = names.firstOrNull { it.equals(name, ignoreCase = true) }
        if (match == null) created = created + name
        picks[match ?: name] = true
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("New collection") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (addDraft(draft)) draft = "" }),
                    trailingIcon = {
                        IconButton(onClick = { if (addDraft(draft)) draft = "" }, enabled = draft.isNotBlank()) {
                            Icon(Icons.Filled.Add, "Add collection")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (names.isEmpty()) {
                    Text(
                        "No collections yet. Name one above to create it with ${describe(selected)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        names.forEach { name ->
                            val state = stateOf(name)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { picks[name] = state != ToggleableState.On }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TriStateCheckbox(state = state, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    addDraft(draft) // a name typed but not added yet still counts
                    onApply(
                        picks.filterValues { it }.keys.toSet(),
                        picks.filterValues { !it }.keys.toSet(),
                    )
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Badge(icon: ImageVector, description: String) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(16.dp)) }
}

@Composable
private fun Cover(
    book: BookWithState,
    modifier: Modifier,
    elevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(placeholderColor(book.title)), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.MenuBook, null, tint = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(6.dp))
                Text(
                    book.title, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.coverUrl != null) {
                AsyncImage(model = book.coverUrl, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
    val sized = modifier.aspectRatio(2f / 3f)
    val shadow = CardDefaults.cardElevation(defaultElevation = elevation, pressedElevation = elevation)
    // The card draws its own shadow, so don't clip it from outside; the click goes on the card.
    if (onClick != null) {
        Card(onClick = onClick, shape = CoverShape, elevation = shadow, modifier = sized) { content() }
    } else {
        Card(shape = CoverShape, elevation = shadow, modifier = sized) { content() }
    }
}

private fun Modifier.matchParentSizeCompat() = this.then(Modifier.fillMaxWidth().aspectRatio(2f / 3f))

/** Also used for the home screen widget's placeholder covers. */
internal fun placeholderColor(seed: String): Color {
    val palette = listOf(0xFF8A4B2A, 0xFF52643F, 0xFF3F5A73, 0xFF6F4A6E, 0xFF7A6230, 0xFF405F5B)
    return Color(palette[seed.hashCode().absoluteValue % palette.size])
}

private fun openExternal(context: Context, e: LibraryEvent.OpenExternal) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", e.file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Open PDF with"))
    } catch (_: ActivityNotFoundException) {
    }
}

private fun openTolinoApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(TOLINO_APP_PACKAGE)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=tolino&c=apps"))
    runCatching { context.startActivity(intent) }
}

private const val TOLINO_APP_PACKAGE = "de.telekom.epub"
