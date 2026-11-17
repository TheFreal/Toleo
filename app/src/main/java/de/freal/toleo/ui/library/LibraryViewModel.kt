package de.freal.toleo.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.freal.toleo.AppContainer
import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import de.freal.toleo.reader.ReaderSession
import de.freal.toleo.sync.SyncStatus
import de.freal.toleo.tolino.NeedsLoginException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class LibraryFilter(val label: String) {
    ALL("All"), READING("Reading"), DOWNLOADED("On device"), FINISHED("Finished"), UPLOADS("My uploads")
}

data class LibraryState(
    val books: List<BookWithState> = emptyList(),
    val continueReading: BookWithState? = null,
    val collections: List<String> = emptyList(),
    val downloads: Map<String, Float> = emptyMap(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val collection: String? = null,
    val query: String = "",
)

sealed interface LibraryEvent {
    data object OpenReader : LibraryEvent
    data class OpenExternal(val file: File, val mimeType: String) : LibraryEvent
    data class Protected(val book: BookWithState) : LibraryEvent
    data class Message(val text: String) : LibraryEvent
}

class LibraryViewModel(private val container: AppContainer, appContext: android.content.Context) : ViewModel() {
    private val library = container.library
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val collection = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val refreshing = MutableStateFlow(false)
    val opening = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<SyncStatus> = container.sync.status

    /** Set while the cloud is out of reach; the library stays usable and shows a banner. */
    val authProblem: StateFlow<de.freal.toleo.tolino.AuthProblem?> = container.auth.problem

    private val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java)

    /** Retries as soon as the network changes (e.g. a VPN comes up) while the shop was blocking us. */
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (authProblem.value == de.freal.toleo.tolino.AuthProblem.BLOCKED) {
                viewModelScope.launch { refresh() }
            }
        }
    }

    val state: StateFlow<LibraryState> = combine(
        library.observeLibrary(), library.downloads, filter, collection, query,
    ) { books, downloads, f, c, q ->
        val sorted = books.sortedWith(compareByDescending<BookWithState> { it.lastOpened ?: 0L }.thenBy { it.sortIndex })
        val visible = sorted.filter { b ->
            when (f) {
                LibraryFilter.ALL -> true
                LibraryFilter.READING -> (b.progress ?: 0.0) > 0.0 && !b.finished
                LibraryFilter.DOWNLOADED -> b.localPath != null
                LibraryFilter.FINISHED -> b.finished
                LibraryFilter.UPLOADS -> b.isUpload
            } && (c == null || b.collectionList().contains(c)) &&
                (q.isBlank() || b.title.contains(q, true) || b.authors.contains(q, true))
        }
        LibraryState(
            books = visible,
            continueReading = sorted.firstOrNull { it.lastOpened != null && !it.finished },
            collections = books.flatMap { it.collectionList() }.distinct().sorted(),
            downloads = downloads,
            filter = f,
            collection = c,
            query = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())

    init {
        refresh()
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    fun setFilter(f: LibraryFilter) { filter.value = f }
    fun setCollection(c: String?) { collection.value = if (collection.value == c) null else c }
    fun setQuery(q: String) { query.value = q }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                library.refreshInventory()
            } catch (e: NeedsLoginException) {
                return@launch // shown as a banner
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message("Library refresh failed: ${e.message}"))
            } finally {
                refreshing.value = false
            }
            val status = container.sync.sync()
            if (status is SyncStatus.Failed && !status.needsLogin) {
                _events.send(LibraryEvent.Message("Sync failed: ${status.message}"))
            }
        }
    }

    fun open(book: BookWithState) {
        if (opening.value != null) return
        viewModelScope.launch {
            opening.value = book.publicationId
            try {
                var entity = container.db.books().get(book.publicationId) ?: return@launch
                if (entity.localPath == null || !File(entity.localPath!!).exists()) {
                    library.download(book.publicationId)
                    entity = container.db.books().get(book.publicationId) ?: return@launch
                }
                when {
                    entity.drm == DrmStatus.ADOBE || entity.drm == DrmStatus.LCP -> _events.send(LibraryEvent.Protected(book))
                    entity.format == "PDF" -> {
                        library.markOpened(entity.publicationId)
                        _events.send(LibraryEvent.OpenExternal(File(entity.localPath!!), "application/pdf"))
                    }
                    else -> {
                        // Pull remote positions/annotations first so we open where the user left off elsewhere.
                        runCatching { container.sync.sync() }
                        val session = ReaderSession.open(container, entity).getOrThrow()
                        container.readerSession = session
                        _events.send(LibraryEvent.OpenReader)
                    }
                }
            } catch (e: NeedsLoginException) {
                _events.send(LibraryEvent.Message("“${book.title}” isn't on this device yet. Reconnect to the cloud to download it."))
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message("Could not open “${book.title}”: ${e.message}"))
            } finally {
                opening.value = null
            }
        }
    }

    fun download(book: BookWithState) = launchCatching("Download failed") { library.download(book.publicationId) }
    fun removeDownload(book: BookWithState) = launchCatching("Could not remove") { library.removeDownload(book.publicationId) }
    fun setFinished(book: BookWithState, finished: Boolean) = launchCatching("Could not update") { library.setFinished(book.publicationId, finished) }
    fun deleteFromCloud(book: BookWithState) = launchCatching("Delete failed") {
        library.deleteFromCloud(book.publicationId)
        _events.send(LibraryEvent.Message("Deleted “${book.title}” from the cloud"))
    }

    fun upload(uri: Uri) = launchCatching("Upload failed") {
        _events.send(LibraryEvent.Message("Uploading…"))
        library.upload(uri)
        _events.send(LibraryEvent.Message("Upload finished"))
    }

    private fun launchCatching(errorPrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: NeedsLoginException) {
                _events.send(LibraryEvent.Message("$errorPrefix: not connected to the cloud"))
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message("$errorPrefix: ${e.message}"))
            }
        }
    }
}

fun BookWithState.collectionList(): List<String> = collections?.split('\u001F')?.filter { it.isNotBlank() }.orEmpty()
