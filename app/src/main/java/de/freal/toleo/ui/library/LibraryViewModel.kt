package de.freal.toleo.ui.library

import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.freal.toleo.AppContainer
import de.freal.toleo.db.BookWithState
import de.freal.toleo.db.DrmStatus
import de.freal.toleo.library.EpubMeta
import de.freal.toleo.library.UploadDraft
import de.freal.toleo.reader.ReaderSession
import de.freal.toleo.sync.SyncStatus
import de.freal.toleo.tolino.NeedsLoginException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    /** Books picked for a batch action, hidden ones included. Selecting mode is on while this isn't empty. */
    val selectedBooks: List<BookWithState> = emptyList(),
) {
    val selecting: Boolean get() = selectedBooks.isNotEmpty()
    val selectedIds: Set<String> = selectedBooks.mapTo(HashSet()) { it.publicationId }
    /** Books already shown above the Library (shelf or current-book panel); they don't offer "Add to Also reading". */
    val shelfIds: Set<String> = recent.mapTo(HashSet()) { it.publicationId } + listOfNotNull(continueReading?.publicationId)
}

/** Which batch actions make sense for a selection; each list holds the selected books the action would change. */
data class SelectionActions(
    val download: List<BookWithState>,
    val removeDownload: List<BookWithState>,
    val markFinished: List<BookWithState>,
    val markUnread: List<BookWithState>,
    val addToShelf: List<BookWithState>,
    val dismiss: List<BookWithState>,
    val hide: List<BookWithState>,
    val unhide: List<BookWithState>,
    val deleteFromCloud: List<BookWithState>,
)

fun LibraryState.selectionActions(): SelectionActions {
    val onShelf = recent.mapTo(HashSet()) { it.publicationId }
    val sel = selectedBooks
    return SelectionActions(
        download = sel.filter { it.localPath == null && it.inCloud && it.publicationId !in downloads },
        removeDownload = sel.filter { it.localPath != null },
        markFinished = sel.filter { !it.finished },
        markUnread = sel.filter { it.finished },
        addToShelf = sel.filter { !it.hidden && it.publicationId !in shelfIds },
        dismiss = sel.filter { it.publicationId in onShelf },
        hide = sel.filter { !it.hidden },
        unhide = sel.filter { it.hidden },
        deleteFromCloud = sel.filter { it.isUpload && it.inCloud },
    )
}

sealed interface LibraryEvent {
    data object OpenReader : LibraryEvent
    data class OpenExternal(val file: File, val mimeType: String) : LibraryEvent
    data class Protected(val book: BookWithState) : LibraryEvent
    data class Message(val text: String) : LibraryEvent
    /** Books left "Recently opened"; the screen offers Undo. */
    data class Dismissed(val books: List<BookWithState>) : LibraryEvent
    /** Books moved to "Hidden"; the screen offers Undo. */
    data class Hidden(val books: List<BookWithState>) : LibraryEvent
}

private data class Params(val filter: LibraryFilter, val collection: String?, val query: String, val sort: SortChoice)

class LibraryViewModel(private val container: AppContainer, appContext: android.content.Context) : ViewModel() {
    private val library = container.library
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val collection = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(SortChoice())
    private val params = combine(filter, collection, query, sort, ::Params)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
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
        library.observeLibrary(), library.downloads, params, selection,
    ) { all, downloads, (f, chosen, q, s), picked ->
        // Hidden books take no part in the Library, the shelf, the current-book panel or the collection chips.
        val books = all.filterNot { it.hidden }
        val collections = books.flatMap { it.collectionList() }.distinct().sorted()
        // Emptying a collection removes it, so a filter on it must not strand the user on an empty list.
        val c = chosen?.takeIf { it in collections }
        val current = currentlyReading(books)
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
            collections = collections,
            downloads = downloads,
            filter = f,
            collection = c,
            query = q,
            sort = s,
            selectedBooks = if (picked.isEmpty()) emptyList() else all.filter { it.publicationId in picked },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())

    init {
        refresh()
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        // A tap on the home screen widget asks for its book to be opened.
        viewModelScope.launch {
            container.openRequest.filterNotNull().collect { id ->
                container.openRequest.value = null
                library.observeLibrary().first().firstOrNull { it.publicationId == id }?.let(::open)
            }
        }
    }

    override fun onCleared() {
        uploadDraft.value?.let(library::discardStaged)
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
        _events.send(LibraryEvent.Dismissed(listOf(book)))
    }

    /** Moves [book] out of the Library into the collapsed "Hidden" list. */
    fun hide(book: BookWithState) = launchCatching("Could not hide") {
        library.setHidden(book.publicationId, true)
        _events.send(LibraryEvent.Hidden(listOf(book)))
    }

    fun unhide(book: BookWithState) = launchCatching("Could not show") { library.setHidden(book.publicationId, false) }

    /** Adds [book] to the "Also reading" shelf by hand, and opens the shelf if it was collapsed. */
    fun addToRecent(book: BookWithState) {
        setRecentExpanded(true)
        launchCatching("Could not add") { library.pinToRecent(book.publicationId) }
    }

    fun download(book: BookWithState) = launchCatching("Download failed") { library.download(book.publicationId) }
    fun removeDownload(book: BookWithState) = launchCatching("Could not remove") { library.removeDownload(book.publicationId) }
    fun setFinished(book: BookWithState, finished: Boolean) = launchCatching("Could not update") { library.setFinished(book.publicationId, finished) }
    fun deleteFromCloud(book: BookWithState) = launchCatching("Delete failed") {
        library.deleteFromCloud(book.publicationId)
        _events.send(LibraryEvent.Message("Deleted “${book.title}” from the cloud"))
    }

    /** Starts selecting with [book] ("Select" in its menu), or toggles it while selecting. Leaving the last book ends selecting. */
    fun toggleSelected(book: BookWithState) {
        selection.update { if (book.publicationId in it) it - book.publicationId else it + book.publicationId }
    }

    /** Adds every book the Library grid currently shows (after sort, filter, collection and search). */
    fun selectAllVisible() {
        val ids = state.value.books.map { it.publicationId }
        selection.update { it + ids }
    }

    fun clearSelection() { selection.value = emptySet() }

    /** Takes the current selection and ends selecting, so the tiles show what the action does. */
    private fun takeSelection(): LibraryState {
        val s = state.value
        clearSelection()
        return s
    }

    fun downloadSelected() {
        val books = takeSelection().selectionActions().download
        runBatch(books, "Download failed") { library.download(it.publicationId) }
    }

    fun removeSelectedDownloads() {
        val books = takeSelection().selectionActions().removeDownload
        runBatch(books, "Could not remove") { library.removeDownload(it.publicationId) }
    }

    fun setSelectedFinished(finished: Boolean) {
        val actions = takeSelection().selectionActions()
        runBatch(if (finished) actions.markFinished else actions.markUnread, "Could not update") {
            library.setFinished(it.publicationId, finished)
        }
    }

    /** Moves the selected books out of the Library into the collapsed "Hidden" list. */
    fun hideSelected() {
        val books = takeSelection().selectionActions().hide
        if (books.isEmpty()) return
        launchCatching("Could not hide") {
            books.forEach { library.setHidden(it.publicationId, true) }
            _events.send(LibraryEvent.Hidden(books))
        }
    }

    fun unhideSelected() {
        val books = takeSelection().selectionActions().unhide
        runBatch(books, "Could not show") { library.setHidden(it.publicationId, false) }
    }

    /** Undo for [hideSelected]; also puts hidden books back one by one. */
    fun unhide(books: List<BookWithState>) = runBatch(books, "Could not show") { library.setHidden(it.publicationId, false) }

    fun dismissSelected() {
        val books = takeSelection().selectionActions().dismiss
        if (books.isEmpty()) return
        launchCatching("Could not dismiss") {
            books.forEach { library.dismissFromRecent(it.publicationId) }
            _events.send(LibraryEvent.Dismissed(books))
        }
    }

    /** Undo for [dismissSelected]; [books] are the rows as they were before dismissing. */
    fun undoDismiss(books: List<BookWithState>) = runBatch(books, "Could not undo") {
        library.restoreShelfState(it.publicationId, it.dismissedAt, it.pinnedAt)
    }

    /** Adds the selected books to the "Also reading" shelf by hand, and opens the shelf if it was collapsed. */
    fun addSelectedToRecent() {
        val books = takeSelection().selectionActions().addToShelf
        if (books.isEmpty()) return
        setRecentExpanded(true)
        runBatch(books, "Could not add") { library.pinToRecent(it.publicationId) }
    }

    fun deleteSelectedFromCloud() {
        val books = takeSelection().selectionActions().deleteFromCloud
        runBatch(books, "Delete failed", successMessage = {
            if (it.size == 1) "Deleted “${it.first().title}” from the cloud" else "Deleted ${it.size} books from the cloud"
        }) { library.deleteFromCloud(it.publicationId) }
    }

    /**
     * Puts the selected books into the collections in [add] and out of those in [remove]. [add] may name
     * collections that don't exist yet, which creates them.
     */
    fun updateSelectedCollections(add: Set<String>, remove: Set<String>) {
        val ids = takeSelection().selectedBooks.map { it.publicationId }
        if (ids.isEmpty() || (add.isEmpty() && remove.isEmpty())) return
        launchCatching("Could not update collections") { library.updateCollections(ids, add, remove) }
    }

    /**
     * Runs [op] on each of [books] in turn. A failure doesn't stop the others; the user is told how many
     * failed, or [successMessage] if all went through. Losing the cloud connection stops the rest.
     */
    private fun runBatch(
        books: List<BookWithState>,
        errorPrefix: String,
        successMessage: ((List<BookWithState>) -> String)? = null,
        op: suspend (BookWithState) -> Unit,
    ) {
        if (books.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            var lastError: String? = null
            for (book in books) {
                try {
                    op(book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NeedsLoginException) {
                    _events.send(LibraryEvent.Message("$errorPrefix: not connected to the cloud"))
                    return@launch
                } catch (e: Exception) {
                    failed++
                    lastError = e.message
                }
            }
            when {
                failed > 0 -> _events.send(
                    LibraryEvent.Message(
                        if (books.size == 1) "$errorPrefix: $lastError" else "$errorPrefix for $failed of ${books.size} books: $lastError",
                    ),
                )
                successMessage != null -> _events.send(LibraryEvent.Message(successMessage(books)))
            }
        }
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

    /** A picked file waiting for the user to check or edit its metadata before it is uploaded. */
    val uploadDraft = MutableStateFlow<UploadDraft?>(null)

    /** Stages the picked file; EPUBs with editable metadata wait for [confirmUpload], everything else uploads at once. */
    fun startUpload(uri: Uri) = launchCatching("Upload failed") {
        val draft = library.stageUpload(uri)
        if (draft.meta == null) finishUpload(draft, null) else uploadDraft.value = draft
    }

    fun confirmUpload(edits: EpubMeta) {
        val draft = uploadDraft.value ?: return
        uploadDraft.value = null
        launchCatching("Upload failed") { finishUpload(draft, edits) }
    }

    fun cancelUpload() {
        uploadDraft.value?.let(library::discardStaged)
        uploadDraft.value = null
    }

    private suspend fun finishUpload(draft: UploadDraft, edits: EpubMeta?) {
        _events.send(LibraryEvent.Message("Uploading…"))
        library.uploadStaged(draft, edits)
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

/**
 * The book shown in the current-book panel and the home screen widget: the most recently
 * opened book that isn't finished or hidden.
 */
fun currentlyReading(books: List<BookWithState>): BookWithState? =
    books.filter { !it.hidden && it.lastOpened != null && !it.finished }.minWithOrNull(recentOrder)

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
