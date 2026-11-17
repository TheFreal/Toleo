package de.freal.toleo.ui.library

import android.net.Uri
import androidx.core.content.edit
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.Collator
import java.time.ZonedDateTime

enum class LibraryFilter(val label: String) {
    ALL("All"), READING("Reading"), DOWNLOADED("On device"), FINISHED("Finished"), UPLOADS("My uploads")
}

/**
 * Order of the Library grid. [label] goes on the sort button, [menuLabel] into the menu.
 * [ascendingByDefault] says which way the menu arrow points before the option is tapped again to reverse it:
 * newest first and highest progress first count as descending.
 */
enum class LibrarySort(val label: String, val menuLabel: String, val ascendingByDefault: Boolean) {
    RECENT("Recent", "Recently opened", false),
    ADDED("Added", "Recently added", false),
    TITLE("Title", "Title", true),
    AUTHOR("Author", "Author", true),
    PROGRESS("Progress", "Progress", false),
}

/** A sort option plus whether its default direction is flipped. */
data class SortChoice(val sort: LibrarySort = LibrarySort.RECENT, val reversed: Boolean = false) {
    val ascending: Boolean get() = sort.ascendingByDefault != reversed
}

data class LibraryState(
    val books: List<BookWithState> = emptyList(),
    val continueReading: BookWithState? = null,
    /** "Recently opened" shelf; independent of sort, filter, collection and query. */
    val recent: List<BookWithState> = emptyList(),
    /** Hidden books by title. Only the search applies, so hidden books can still be found; sort, filter and collection don't. */
    val hiddenBooks: List<BookWithState> = emptyList(),
    val collections: List<String> = emptyList(),
    val downloads: Map<String, Float> = emptyMap(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val collection: String? = null,
    val query: String = "",
    val sort: SortChoice = SortChoice(),
)

sealed interface LibraryEvent {
    data object OpenReader : LibraryEvent
    data class OpenExternal(val file: File, val mimeType: String) : LibraryEvent
    data class Protected(val book: BookWithState) : LibraryEvent
    data class Message(val text: String) : LibraryEvent
    /** A book left "Recently opened"; the screen offers Undo. */
    data class Dismissed(val book: BookWithState) : LibraryEvent
    /** A book moved to "Hidden"; the screen offers Undo. */
    data class Hidden(val book: BookWithState) : LibraryEvent
}

private data class Params(val filter: LibraryFilter, val collection: String?, val query: String, val sort: SortChoice)

class LibraryViewModel(private val container: AppContainer, appContext: android.content.Context) : ViewModel() {
    private val library = container.library
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val collection = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(SortChoice())
    private val params = combine(filter, collection, query, sort, ::Params)
    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val prefs = appContext.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)

    /** Whether the "Also reading" shelf is open; remembered across app sessions. */
    private val _recentExpanded = MutableStateFlow(prefs.getBoolean(PREF_RECENT_EXPANDED, true))
    val recentExpanded: StateFlow<Boolean> = _recentExpanded

    fun setRecentExpanded(expanded: Boolean) {
        _recentExpanded.value = expanded
        prefs.edit { putBoolean(PREF_RECENT_EXPANDED, expanded) }
    }

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
        library.observeLibrary(), library.downloads, params,
    ) { all, downloads, (f, c, q, s) ->
        // Hidden books take no part in the Library, the shelf, the current-book panel or the collection chips.
        val books = all.filterNot { it.hidden }
        val byRecent = books.sortedWith(recentOrder)
        val current = byRecent.firstOrNull { it.lastOpened != null && !it.finished }
        val visible = books.sortedWith(s.sort.comparator(s.reversed)).filter { b ->
            when (f) {
                LibraryFilter.ALL -> true
                LibraryFilter.READING -> (b.progress ?: 0.0) > 0.0 && !b.finished
                LibraryFilter.DOWNLOADED -> b.localPath != null
                LibraryFilter.FINISHED -> b.finished
                LibraryFilter.UPLOADS -> b.isUpload
            } && (c == null || b.collectionList().contains(c)) && b.matchesQuery(q)
        }
        LibraryState(
            books = visible,
            continueReading = current,
            recent = recentlyOpened(books, current, ZonedDateTime.now()),
            hiddenBooks = all.filter { it.hidden && it.matchesQuery(q) }.sortedWith(titleOrder),
            collections = books.flatMap { it.collectionList() }.distinct().sorted(),
            downloads = downloads,
            filter = f,
            collection = c,
            query = q,
            sort = s,
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
    /** Picks [s]; choosing the active option again reverses its direction. */
    fun setSort(s: LibrarySort) {
        sort.update { if (it.sort == s) it.copy(reversed = !it.reversed) else SortChoice(s) }
    }

    fun dismiss(book: BookWithState) = launchCatching("Could not dismiss") {
        library.dismissFromRecent(book.publicationId)
        _events.send(LibraryEvent.Dismissed(book))
    }

    /** Undo for [dismiss]; [book] is the row as it was before dismissing. */
    fun undoDismiss(book: BookWithState) = launchCatching("Could not undo") {
        library.restoreShelfState(book.publicationId, book.dismissedAt, book.pinnedAt)
    }

    /** Moves [book] out of the Library into the collapsed "Hidden" list. */
    fun hide(book: BookWithState) = launchCatching("Could not hide") {
        library.setHidden(book.publicationId, true)
        _events.send(LibraryEvent.Hidden(book))
    }

    fun unhide(book: BookWithState) = launchCatching("Could not show") { library.setHidden(book.publicationId, false) }

    /** Adds [book] to the "Also reading" shelf by hand, and opens the shelf if it was collapsed. */
    fun addToRecent(book: BookWithState) {
        setRecentExpanded(true)
        launchCatching("Could not add") { library.pinToRecent(book.publicationId) }
    }

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

/** True for a blank [query], otherwise when the title or authors contain it, ignoring case. */
fun BookWithState.matchesQuery(query: String): Boolean =
    query.isBlank() || title.contains(query, true) || authors.contains(query, true)

fun BookWithState.collectionList(): List<String> = collections?.split('\u001F')?.filter { it.isNotBlank() }.orEmpty()

private const val PREF_RECENT_EXPANDED = "recent_expanded"

/** How far back the "Also reading" shelf reaches. */
const val RECENT_MONTHS = 6L
const val RECENT_MAX = 3

/**
 * Books for the "Also reading" shelf, never including [current] or hidden books:
 * 1. Books the user added by hand ([BookWithState.pinnedAt]), most recently added first. They stay
 *    regardless of age or earlier dismissals, and are not limited by [RECENT_MAX].
 * 2. Then books opened within the last [RECENT_MONTHS] months and not dismissed since, newest first,
 *    filling whatever is left of [RECENT_MAX] slots.
 */
fun recentlyOpened(books: List<BookWithState>, current: BookWithState?, now: ZonedDateTime): List<BookWithState> {
    val cutoff = now.minusMonths(RECENT_MONTHS).toInstant().toEpochMilli()
    val candidates = books.filter { !it.hidden && it.publicationId != current?.publicationId }
    val pinned = candidates.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val automatic = candidates
        .filter { b ->
            val opened = b.lastOpened ?: return@filter false
            b.pinnedAt == null && opened >= cutoff && (b.dismissedAt == null || opened > b.dismissedAt)
        }
        .sortedByDescending { it.lastOpened }
        .take((RECENT_MAX - pinned.size).coerceAtLeast(0))
    return pinned + automatic
}

/** Most recently opened first, never-opened last, then inventory order. */
private val recentOrder = compareByDescending<BookWithState> { it.lastOpened ?: 0L }.thenBy { it.sortIndex }

/** Locale-aware and case-insensitive, so "über" sorts with "u" and not after "z". */
private val collator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

private val titleOrder = Comparator<BookWithState> { a, b -> collator.compare(a.title, b.title) }

/** Matches what the tile shows: a finished book counts as complete. */
private fun BookWithState.shownProgress(): Double? = if (finished) 1.0 else progress?.takeIf { it > 0.0 }

private fun <T> Comparator<T>.flipIf(flip: Boolean): Comparator<T> = if (flip) reversed() else this

/**
 * Order for each sort option. [reversed] flips only the main key: books without a value
 * (never opened, no author, no progress) stay at the end in both directions.
 */
fun LibrarySort.comparator(reversed: Boolean = false): Comparator<BookWithState> = when (this) {
    LibrarySort.RECENT -> compareBy<BookWithState> { it.lastOpened == null }
        .then(compareByDescending<BookWithState> { it.lastOpened ?: 0L }.flipIf(reversed))
        .thenBy { it.sortIndex }
    LibrarySort.ADDED -> compareBy<BookWithState> { it.sortIndex }.flipIf(reversed).then(titleOrder)
    LibrarySort.TITLE -> titleOrder.flipIf(reversed).thenBy { it.sortIndex }
    LibrarySort.AUTHOR -> compareBy<BookWithState> { it.authors.isBlank() }
        .then(Comparator<BookWithState> { a, b -> collator.compare(a.authors, b.authors) }.flipIf(reversed))
        .then(titleOrder)
    LibrarySort.PROGRESS -> compareBy<BookWithState> { it.shownProgress() == null }
        .then(compareByDescending<BookWithState> { it.shownProgress() ?: 0.0 }.flipIf(reversed))
        .then(titleOrder)
}
