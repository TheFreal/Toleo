package de.freal.toleo.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import de.freal.toleo.reader.ReaderActivity
import de.freal.toleo.sync.SyncStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onAccount: () -> Unit, onNeedsLogin: () -> Unit) {
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val opening by vm.opening.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val authProblem by vm.authProblem.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var protected by remember { mutableStateOf<BookWithState?>(null) }
    var searching by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::upload) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                LibraryEvent.OpenReader -> context.startActivity(ReaderActivity.intent(context))
                is LibraryEvent.OpenExternal -> openExternal(context, e)
                is LibraryEvent.Protected -> protected = e.book
                is LibraryEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = state.query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text("Title or author") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text("Toleo")
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                    SyncIndicator(sync, onClick = vm::refresh)
                    IconButton(onClick = onAccount) { Icon(Icons.Filled.AccountCircle, "Account and devices") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/epub+zip", "application/pdf")) },
                icon = { Icon(Icons.Filled.Upload, null) },
                text = { Text("Upload") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      Column(Modifier.padding(padding).fillMaxSize()) {
        authProblem?.let { CloudBanner(it, retrying = refreshing, onRetry = vm::refresh, onSignIn = onNeedsLogin) }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(136.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.continueReading?.takeIf { state.query.isBlank() && state.filter == LibraryFilter.ALL && state.collection == null }?.let { book ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        ContinueCard(book, opening == book.publicationId) { vm.open(book) }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryFilter.entries.forEach { f ->
                            FilterChip(selected = state.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
                        }
                        state.collections.forEach { c ->
                            FilterChip(selected = state.collection == c, onClick = { vm.setCollection(c) }, label = { Text(c) })
                        }
                    }
                }
                if (state.books.isEmpty() && !refreshing) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text(
                            "No books here yet. Pull down to refresh, or upload an EPUB.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                        )
                    }
                }
                items(state.books, key = { it.publicationId }) { book ->
                    BookTile(
                        book = book,
                        downloadProgress = state.downloads[book.publicationId],
                        opening = opening == book.publicationId,
                        onOpen = { vm.open(book) },
                        onDownload = { vm.download(book) },
                        onRemoveDownload = { vm.removeDownload(book) },
                        onFinished = { vm.setFinished(book, it) },
                        onDelete = { vm.deleteFromCloud(book) },
                    )
                }
            }
        }
      }
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

/** Shown instead of kicking the user to the login screen when the cloud can't be reached. */
@Composable
private fun CloudBanner(problem: de.freal.toleo.tolino.AuthProblem, retrying: Boolean, onRetry: () -> Unit, onSignIn: () -> Unit) {
    val blocked = problem == de.freal.toleo.tolino.AuthProblem.BLOCKED
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                    androidx.compose.material3.TextButton(onClick = onRetry, enabled = !retrying) { Text(if (retrying) "Retrying…" else "Retry") }
                } else {
                    androidx.compose.material3.TextButton(onClick = onSignIn) { Text("Sign in") }
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

@Composable
private fun ContinueCard(book: BookWithState, opening: Boolean, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, Modifier.width(72.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue reading", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.authors, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                val p = (book.progress ?: 0.0).toFloat()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text("${(p * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (opening) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: BookWithState,
    downloadProgress: Float?,
    opening: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onFinished: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box {
            Cover(book, Modifier.fillMaxWidth())
            // Status badges
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (book.drm == DrmStatus.ADOBE || book.drm == DrmStatus.LCP) Badge(Icons.Filled.Lock, "Copy-protected")
                if (book.format == "PDF") Badge(Icons.Filled.PictureAsPdf, "PDF")
                if (book.localPath != null) Badge(Icons.Filled.DownloadDone, "On device")
            }
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
                if (book.localPath == null) DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload() })
                else DropdownMenuItem(text = { Text("Remove from device") }, onClick = { menu = false; onRemoveDownload() })
                DropdownMenuItem(
                    text = { Text(if (book.finished) "Mark as unread" else "Mark as finished") },
                    onClick = { menu = false; onFinished(!book.finished) },
                )
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

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(16.dp)) }
}

@Composable
private fun Cover(book: BookWithState, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Card(shape = shape, modifier = modifier.aspectRatio(2f / 3f)) {
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
}

private fun Modifier.matchParentSizeCompat() = this.then(Modifier.fillMaxWidth().aspectRatio(2f / 3f))

private fun placeholderColor(seed: String): Color {
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
