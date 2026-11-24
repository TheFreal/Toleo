package de.freal.toleo.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import de.freal.toleo.ToleoApp
import de.freal.toleo.sync.SyncWorker
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Hosts Readium's [EpubNavigatorFragment] inside Compose. The navigator can only be built
 * through Readium's FragmentFactory, which must be installed before `super.onCreate`.
 */
class ReaderActivity : AppCompatActivity() {
    private val container by lazy { (application as ToleoApp).container }
    private val prefsStore by lazy { ReaderPrefsStore(this) }

    private val viewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(container, container.readerSession!!, prefsStore) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val session = container.readerSession
        if (session == null) {
            // Process was killed while reading; the library will reopen the book.
            super.onCreate(null)
            finish()
            return
        }
        // The activity is recreated when the system switches light/dark mode, so this runs on every switch.
        followSystemTheme(resources.configuration)
        val widthDp = resources.configuration.screenWidthDp
        prefsStore.current.value.let { p ->
            val two = p.twoPages(widthDp)
            installNavigatorFactory(session, two, if (two) p.spineGutter else 0, widthDp, session.initialLocator)
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keep the display on while reading; the flag only applies while this window is visible.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ReaderScreen(
                vm = viewModel,
                onClose = ::finish,
                installNavigatorFactory = { twoPages, spineGutter, width ->
                    installNavigatorFactory(session, twoPages, spineGutter, width, viewModel.locator.value ?: session.initialLocator)
                },
            )
        }

        lifecycleScope.launch {
            viewModel.chromeVisible.collect { visible ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
                else controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * Readium builds its navigator through this factory. Column CSS (one page vs. open book) can
     * only be set here, so the reader installs a new factory and recreates the navigator when the
     * mode changes, starting at [locator].
     */
    private fun installNavigatorFactory(
        session: ReaderSession,
        twoPages: Boolean,
        spineGutterDp: Int,
        widthDp: Int,
        locator: org.readium.r2.shared.publication.Locator?,
    ) {
        supportFragmentManager.fragmentFactory = session.navigatorFactory.createFragmentFactory(
            initialLocator = locator,
            initialPreferences = prefsStore.current.value.toEpub(widthDp),
            configuration = EpubNavigatorFragment.Configuration(
                readiumCssRsProperties = ReaderPrefs.rsProperties(twoPages, spineGutterDp),
                selectionActionModeCallback = selectionCallback,
                // Readium would pad the full cutout safe area on top of its own margin; the margin
                // alone (res/values/dimens.xml) is enough while the system bars are hidden.
                shouldApplyInsetsPadding = false,
            ),
        )
    }

    override fun onStart() {
        super.onStart()
        if (container.readerSession != null) viewModel.resumeReadingClock()
    }

    override fun onStop() {
        super.onStop()
        if (container.readerSession != null) {
            viewModel.pauseReadingClock()
            viewModel.flushPosition()
            // Push position and annotations as soon as there is network.
            SyncWorker.syncSoon(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            container.readerSession?.publication?.close()
            container.readerSession = null
        }
    }

    // ------------------------------------------------------------------ swipe → flip
    // Readium pages natively on horizontal swipes (scrolling the WebView). With the flip animation,
    // a swipe is caught here before the WebView sees it and turns the page through the flip instead.
    // Only quick horizontal movements are taken: taps, long-press selection and vertical drags pass.

    private val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false
    private var stealing = false

    private fun swipeFlips(): Boolean {
        val prefs = prefsStore.current.value
        return container.readerSession != null && prefs.pageTurn == PageTurn.FLIP && !prefs.scroll &&
            !viewModel.chromeVisible.value && viewModel.panel.value == null
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                stealing = false
                tracking = swipeFlips()
                downX = ev.x; downY = ev.y; downTime = ev.eventTime
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> if (!stealing) tracking = false
            android.view.MotionEvent.ACTION_MOVE -> {
                if (stealing) return true
                if (tracking) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        tracking = false
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f && ev.eventTime - downTime < SWIPE_START_MS) {
                            stealing = true
                            // Tell the WebView the gesture is over so it doesn't scroll or select.
                            val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            return true
                        }
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> if (stealing) {
                stealing = false
                val dx = ev.x - downX
                if (kotlin.math.abs(dx) > SWIPE_MIN_DP * resources.displayMetrics.density) {
                    viewModel.turn(forward = dx < 0)
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> if (stealing) {
                stealing = false
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun followSystemTheme(config: android.content.res.Configuration) {
        val dark = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        prefsStore.update { it.followSystem(dark) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Only in full screen: with the controls or a panel open, the keys change the volume as usual.
        if (container.readerSession != null && prefsStore.current.value.volumeKeys &&
            !viewModel.chromeVisible.value && viewModel.panel.value == null
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { viewModel.turn(forward = true); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { viewModel.turn(forward = false); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_HIGHLIGHT, 0, "Highlight")
            menu.add(Menu.NONE, MENU_NOTE, 1, "Note")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                MENU_HIGHLIGHT -> viewModel.highlightSelection(HighlightColors.first(), withNote = false)
                MENU_NOTE -> viewModel.highlightSelection(HighlightColors.first(), withNote = true)
                else -> return false
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    companion object {
        private const val MENU_HIGHLIGHT = 1001
        private const val MENU_NOTE = 1002
        /** A swipe must leave the touch slop this soon after touch-down (later = long press / selection). */
        private const val SWIPE_START_MS = 350L
        private const val SWIPE_MIN_DP = 32f

        fun intent(context: Context) = Intent(context, ReaderActivity::class.java)
    }
}

/** Highlight palette (ARGB). Tolino has no colour field, so colours stay on this device. */
val HighlightColors = listOf(
    0xFFFFD54F.toInt(), // yellow
    0xFF81C784.toInt(), // green
    0xFF64B5F6.toInt(), // blue
    0xFFF48FB1.toInt(), // pink
    0xFFFFB74D.toInt(), // orange
)
