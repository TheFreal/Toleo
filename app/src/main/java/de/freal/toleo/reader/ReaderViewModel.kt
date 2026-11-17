package de.freal.toleo.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.freal.toleo.AppContainer
import de.freal.toleo.db.AnnotationEntity
import de.freal.toleo.db.AnnotationType
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt
import org.readium.r2.shared.publication.services.positions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression
import kotlin.math.abs

enum class ReaderPanel { CONTENTS, HIGHLIGHTS, BOOKMARKS }

/** Shown in the bottom bar while the chrome is visible. Nulls are not known yet. */
data class PageInfo(
    /** Readium's fixed book pages (positions), independent of font size. */
    val page: Int? = null,
    val totalPages: Int? = null,
    /** Screen pages after the current one in this chapter (each side of an open book counts). */
    val chapterPagesLeft: Int? = null,
    val chapterSecondsLeft: Int? = null,
)

data class TocEntry(val link: Link, val title: String, val depth: Int)

class ReaderViewModel(
    private val container: AppContainer,
    val session: ReaderSession,
    val prefsStore: ReaderPrefsStore,
) : ViewModel() {
    private val pubId = session.book.publicationId
    private val reading = container.reading
    private var navigator: EpubNavigatorFragment? = null
    private var navigatorJobs: List<Job> = emptyList()

    val title: String = session.publication.metadata.title ?: session.book.title
    val toc: List<TocEntry> = flattenToc(session.publication.tableOfContents.ifEmpty { session.publication.readingOrder })

    val locator = MutableStateFlow(session.initialLocator)

    /**
     * Body-text line height in CSS px (= dp) of the current chapter, 0 until measured. The reader
     * sizes the page area to a whole number of lines so the leftover space is split evenly between
     * top and bottom instead of collecting under the last line.
     */
    val lineHeightDp = MutableStateFlow(0f)

    private val chapterLayout = MutableStateFlow<ChapterLayout?>(null)
    private val positionCount = MutableStateFlow<Int?>(null)

    val pageInfo: kotlinx.coroutines.flow.StateFlow<PageInfo> = kotlinx.coroutines.flow.combine(
        locator, chapterLayout, positionCount,
    ) { loc, layout, total ->
        val cps = prefsStore.readingCps
        val pagesLeft = layout?.let { (it.screens - it.screen - 1).coerceAtLeast(0) * it.columns }
        PageInfo(
            page = loc?.locations?.position,
            totalPages = total,
            chapterPagesLeft = pagesLeft,
            chapterSecondsLeft = layout?.let { l ->
                // Rest of the current screen counts as half read.
                val screensLeft = (l.screens - l.screen - 1).coerceAtLeast(0) + 0.5
                (screensLeft * l.charsPerScreen / cps).roundToInt()
            },
        )
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, PageInfo())

    /** Layout of the current chapter as measured in the WebView. */
    private data class ChapterLayout(val href: String, val screens: Int, val screen: Int, val columns: Int, val chars: Int) {
        val charsPerScreen: Double get() = chars.toDouble() / screens
    }

    /** When the current screen was first shown, for learning the reading speed. */
    private var screenShownAt = 0L
    val chromeVisible = MutableStateFlow(true)
    val panel = MutableStateFlow<ReaderPanel?>(null)
    val settingsOpen = MutableStateFlow(false)
    val editing = MutableStateFlow<AnnotationEntity?>(null)
    val message = MutableStateFlow<String?>(null)

    val annotations: StateFlow<List<AnnotationEntity>> = reading.observeAnnotations(pubId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val highlights = MutableStateFlow<List<AnnotationEntity>>(emptyList())
    val bookmarks = MutableStateFlow<List<AnnotationEntity>>(emptyList())

    /** Page bookmark at the current page, if any. */
    val currentDogear: StateFlow<AnnotationEntity?> = combine(annotations, locator) { list, loc ->
        loc ?: return@combine null
        list.firstOrNull { it.type == AnnotationType.DOGEAR && it.locator()?.let { d -> samePage(d, loc) } == true }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { positionCount.value = runCatching { session.publication.positions().size }.getOrNull() }
        viewModelScope.launch {
            annotations.collect { list ->
                highlights.value = list.filter { it.type == AnnotationType.HIGHLIGHT }
                bookmarks.value = list.filter { it.type == AnnotationType.DOGEAR }
                resolveMissingLocators(list)
            }
        }
        viewModelScope.launch { container.library.markOpened(pubId) }
    }

    @OptIn(FlowPreview::class)
    fun attach(nav: EpubNavigatorFragment) {
        if (navigator === nav) return
        navigatorJobs.forEach { it.cancel() }
        navigator = nav

        // Edge taps turn pages (through [turn], so the flip animation can snapshot first); centre toggles chrome.
        nav.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = nav.publicationView.width.toFloat()
                val edge = maxOf(width * 0.28f, 80f)
                val paged = !prefsStore.current.value.scroll
                when {
                    paged && event.point.x < edge -> turn(forward = false)
                    paged && event.point.x > width - edge -> turn(forward = true)
                    else -> chromeVisible.value = !chromeVisible.value
                }
                return true
            }
        })
        nav.addDecorationListener(GROUP, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                editing.value = annotations.value.firstOrNull { it.id == event.decoration.id }
                return editing.value != null
            }
        })

        navigatorJobs = listOf(
            viewModelScope.launch { nav.currentLocator.collect { locator.value = it.withTotalProgression() } },
            viewModelScope.launch { nav.currentLocator.debounce(1500).collect { persistPosition(it) } },
            viewModelScope.launch { nav.currentLocator.debounce(250).collect { measureLineHeight(nav) } },
            viewModelScope.launch {
                annotations.collect { list ->
                    nav.applyDecorations(
                        list.filter { it.type == AnnotationType.HIGHLIGHT }.mapNotNull { a ->
                            val loc = a.locator() ?: return@mapNotNull null
                            Decoration(id = a.id, locator = loc, style = Decoration.Style.Highlight(tint = a.color, isActive = !a.note.isNullOrBlank()))
                        },
                        GROUP,
                    )
                }
            },
        )
    }

    fun detach() {
        navigatorJobs.forEach { it.cancel() }
        navigatorJobs = emptyList()
        navigator = null
    }

    /** Saves the current position immediately (called when the reader goes to background). */
    fun flushPosition() {
        val loc = navigator?.currentLocator?.value ?: return
        container.appScope.launch { persistPosition(loc) }
    }

    /** First settled locator after opening; positions only sync once the reader moves away from it. */
    private var openedAt: Locator? = null
    private var moved = false

    private suspend fun persistPosition(loc: Locator) {
        val start = openedAt ?: loc.also { openedAt = it }
        if (!moved && (loc.href != start.href || abs((loc.locations.progression ?: 0.0) - (start.locations.progression ?: 0.0)) > 1e-4)) {
            moved = true
        }
        val tolino = runCatching { session.mapper.positionFor(loc) }.getOrNull()
        android.util.Log.d(TAG, "position ${loc.href} prog=${loc.locations.progression} total=${loc.locations.totalProgression} pos=${loc.locations.position} moved=$moved → $tolino")
        reading.savePosition(pubId, loc.withTotalProgression(), tolino, moved)
    }

    /**
     * Readium fills `totalProgression` from its positions list; when it's missing, estimate it
     * from the chapter index so the synced `progress` is never reset to 0.
     */
    private fun Locator.withTotalProgression(): Locator {
        val order = session.publication.readingOrder
        val index = order.indexOfFirst { it.url().toString() == href.toString() }.takeIf { it >= 0 } ?: return this
        val estimate = (index + (locations.progression ?: 0.0)) / order.size
        val readium = locations.totalProgression
        // Readium quantises to its positions list (~1 per 1 KB compressed); in short chapters that
        // reports 0 well past the start. Use the estimate whenever Readium's value lags behind it.
        if (readium != null && readium >= estimate * 0.9) return this
        return copy(locations = locations.copy(totalProgression = maxOf(readium ?: 0.0, estimate)))
    }

    fun highlightSelection(color: Int, withNote: Boolean) {
        val nav = navigator ?: return
        viewModelScope.launch {
            val selection = nav.currentSelection() ?: return@launch
            val pageProgress = selection.locator.locations.totalProgression
            val mapped = runCatching { session.mapper.rangeFor(selection.locator) }.getOrNull()
            val created = if (mapped != null) {
                reading.addHighlight(pubId, mapped.locator, mapped.start, mapped.end, pageProgress, color, null)
            } else {
                // Could not match the quote in the resource: keep a page-level anchor so it still syncs.
                val pos = session.mapper.positionFor(selection.locator) ?: selection.locator.href.toString()
                reading.addHighlight(pubId, selection.locator, pos, pos, pageProgress, color, null)
            }
            nav.clearSelection()
            if (withNote) editing.value = created
        }
    }

    fun toggleBookmark() {
        val loc = locator.value ?: return
        viewModelScope.launch {
            val existing = currentDogear.value
            if (existing != null) {
                reading.deleteAnnotation(existing.id)
            } else {
                reading.addDogear(pubId, loc, session.mapper.positionFor(loc), session.mapper.excerptAt(loc))
            }
        }
    }

    fun saveAnnotation(id: String, note: String?, color: Int) {
        viewModelScope.launch { reading.updateAnnotation(id, note, color) }
        editing.value = null
    }

    fun deleteAnnotation(id: String) {
        viewModelScope.launch { reading.deleteAnnotation(id) }
        editing.value = null
    }

    fun goTo(annotation: AnnotationEntity) {
        val loc = annotation.locator() ?: run { message.value = "Position not available in this edition"; return }
        navigator?.go(loc, animated = false)
        closePanelOnCompact()
    }

    fun goTo(link: Link) {
        navigator?.go(link, animated = false)
        closePanelOnCompact()
    }

    fun seek(totalProgression: Double) {
        viewModelScope.launch {
            session.publication.locateProgression(totalProgression)?.let { navigator?.go(it, animated = false) }
        }
    }

    /** Requests for the UI to run the flip animation; true = forward. */
    val flipRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    /** Turns a page using the configured animation. */
    fun turn(forward: Boolean) {
        when (prefsStore.current.value.pageTurn) {
            PageTurn.FLIP -> if (navigator != null) flipRequests.tryEmit(forward)
            PageTurn.SLIDE -> navigate(forward, animated = true)
            PageTurn.NONE -> navigate(forward, animated = false)
        }
    }

    /** Moves one page; false at the start/end of the book. */
    fun navigate(forward: Boolean, animated: Boolean): Boolean {
        val nav = navigator ?: return false
        return if (forward) nav.goForward(animated) else nav.goBackward(animated)
    }

    /** The view showing the publication, for the flip animation's snapshot. */
    fun publicationView(): android.view.View? = navigator?.publicationView

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) = prefsStore.update(transform)

    fun submitPreferences(readerWidthDp: Int) {
        val nav = navigator ?: return
        nav.submitPreferences(prefsStore.current.value.toEpub(readerWidthDp))
        // Text size / spacing changes don't always move the locator; re-measure once CSS applied.
        viewModelScope.launch { delay(400); measureLineHeight(nav) }
    }

    private suspend fun measureLineHeight(nav: EpubNavigatorFragment) {
        if (navigator !== nav) return
        val raw = runCatching { nav.evaluateJavascript(MEASURE_JS) }.getOrNull() ?: return
        val obj = runCatching {
            val text = kotlinx.serialization.json.Json.parseToJsonElement(raw).let {
                if (it is kotlinx.serialization.json.JsonPrimitive) kotlinx.serialization.json.Json.parseToJsonElement(it.content) else it
            }
            text as kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return
        fun num(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

        val lh = num("lh")?.toFloat() ?: 0f
        if (lh > 4f && kotlin.math.abs(lh - lineHeightDp.value) > 0.1f) lineHeightDp.value = lh

        val href = locator.value?.href?.toString() ?: return
        val screens = num("total")?.toInt()?.coerceAtLeast(1) ?: return
        val layout = ChapterLayout(
            href = href,
            screens = screens,
            screen = (num("cur")?.toInt() ?: 0).coerceIn(0, screens - 1),
            columns = (num("cols")?.toInt() ?: 1).coerceIn(1, 2),
            chars = num("chars")?.toInt() ?: 0,
        )
        learnSpeed(chapterLayout.value, layout)
        chapterLayout.value = layout
    }

    /**
     * Learns characters per second from forward turns of exactly one screen. Turns much faster or
     * slower than the current estimate (skimming, putting the phone down) are ignored, and each
     * accepted turn moves the estimate only a little.
     */
    private fun learnSpeed(previous: ChapterLayout?, now: ChapterLayout) {
        val t = android.os.SystemClock.elapsedRealtime()
        val shownAt = screenShownAt
        val moved = previous == null || previous.href != now.href || previous.screen != now.screen
        if (!moved) return // same screen measured again (e.g. after a settings change)
        screenShownAt = t
        if (previous == null || previous.href != now.href || now.screen != previous.screen + 1) return
        if (shownAt == 0L || previous.chars == 0) return
        val seconds = (t - shownAt) / 1000.0
        val current = prefsStore.readingCps.toDouble()
        val expected = previous.charsPerScreen / current
        if (seconds < expected / 3 || seconds > expected * 3 || seconds < 2) return
        val sample = (previous.charsPerScreen / seconds).coerceIn(4.0, 80.0)
        prefsStore.readingCps = (current * 0.85 + sample * 0.15).toFloat()
    }

    /** Time away from the book doesn't count as reading. */
    fun pauseReadingClock() { screenShownAt = 0L }
    fun resumeReadingClock() { screenShownAt = android.os.SystemClock.elapsedRealtime() }

    private fun closePanelOnCompact() { panel.value = null }

    private suspend fun resolveMissingLocators(list: List<AnnotationEntity>) {
        for (a in list.filter { it.locatorJson == null }) {
            val loc = runCatching {
                when (a.type) {
                    AnnotationType.HIGHLIGHT -> session.mapper.highlightLocator(a.startPosition, a.endPosition, a.text)
                    AnnotationType.DOGEAR -> session.mapper.locatorFor(a.startPosition, a.progress)
                }
            }.getOrNull() ?: continue
            reading.saveResolvedLocator(a.id, loc)
        }
    }

    private fun samePage(a: Locator, b: Locator): Boolean {
        val pa = a.locations.position
        val pb = b.locations.position
        if (pa != null && pb != null) return pa == pb
        return a.href == b.href && abs((a.locations.progression ?: 0.0) - (b.locations.progression ?: 0.0)) < 0.02
    }

    private fun flattenToc(links: List<Link>, depth: Int = 0): List<TocEntry> = links.flatMap { link ->
        listOf(TocEntry(link, link.title ?: link.url().toString(), depth)) + flattenToc(link.children, depth + 1)
    }

    override fun onCleared() {
        detach()
    }

    companion object {
        /** Line height of the first real paragraph (body text), in CSS px. */
        /**
         * Body line height (first real paragraph, CSS px), number of screens in the chapter, the
         * current screen, columns per screen, and the chapter's text length.
         */
        private const val MEASURE_JS = """(function(){var ps=document.querySelectorAll('p');var el=null;for(var i=0;i<ps.length&&i<300;i++){if(ps[i].textContent.length>120){el=ps[i];break;}}el=el||document.body;var lh=parseFloat(getComputedStyle(el).lineHeight);var se=document.scrollingElement||document.documentElement;var w=window.innerWidth||1;var total=Math.max(1,Math.round(se.scrollWidth/w));var cur=Math.round(se.scrollLeft/w);var cols=parseInt(getComputedStyle(document.documentElement).columnCount)||1;var chars=(document.body&&document.body.innerText||'').length;return JSON.stringify({lh:isFinite(lh)?lh:0,total:total,cur:cur,cols:cols,chars:chars});})()"""
        const val GROUP = "highlights"
        private const val TAG = "ToleoReader"
    }
}

fun AnnotationEntity.locator(): Locator? =
    locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
