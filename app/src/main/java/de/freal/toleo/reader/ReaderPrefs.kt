package de.freal.toleo.reader

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

enum class PageLayout { AUTO, SINGLE, DOUBLE }

enum class PageTurn(val label: String) { FLIP("Flip"), SLIDE("Slide"), NONE("None") }

/** Reading colour modes. Readium's base [Theme] drives image filters; explicit colours override the palette. */
enum class ReaderTheme(
    val label: String,
    val base: Theme,
    val background: Int,
    val text: Int,
    val isDark: Boolean,
) {
    LIGHT("Light", Theme.LIGHT, 0xFFFFFFFF.toInt(), 0xFF1B1B1B.toInt(), false),
    WARM("Warm", Theme.LIGHT, 0xFFF7EEDD.toInt(), 0xFF3A2E22.toInt(), false),
    SEPIA("Sepia", Theme.SEPIA, 0xFFEBDDC2.toInt(), 0xFF4A3B2A.toInt(), false),
    DARK("Dark", Theme.DARK, 0xFF1C1B1A.toInt(), 0xFFDAD6CF.toInt(), true),
    AMOLED("Black", Theme.DARK, 0xFF000000.toInt(), 0xFFB8B4AD.toInt(), true),
}

@Serializable
data class ReaderPrefs(
    /** Text size as shown to the user; 1.0 ("100 %") is [FONT_SIZE_BASE] × Readium's default. */
    val fontSize: Double = 1.0,
    /** Line height as a multiple of the font size. */
    val lineHeight: Double = 1.65,
    /** Side margins as shown to the user; 1.0 ("100 %") is [PAGE_MARGINS_BASE] × Readium's gutter. */
    val pageMargins: Double = 1.0,
    /** Top and bottom page margin in dp, applied equally so the text block sits centred. */
    val verticalMargin: Int = 64,
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val pageLayout: PageLayout = PageLayout.AUTO,
    val pageTurn: PageTurn = PageTurn.FLIP,
    val justify: Boolean = true,
    val scroll: Boolean = false,
    /** Readium ignores text alignment and colours while publisher styles are on. */
    val publisherStyles: Boolean = false,
    val volumeKeys: Boolean = true,
    /** Shaded spine down the middle in open-book mode. */
    val crease: Boolean = true,
    /** Strength of the crease shading, 0–1. */
    val creaseOpacity: Double = 0.5,
    /** Last light and dark colour picked, restored when the system switches light/dark mode. */
    val lightTheme: ReaderTheme? = null,
    val darkTheme: ReaderTheme? = null,
    /** The system mode [theme] was last matched to; null before the first match. */
    val systemDark: Boolean? = null,
) {
    /** Picks [t] and remembers it as the colour for its mode. */
    fun withTheme(t: ReaderTheme): ReaderPrefs =
        copy(theme = t, lightTheme = if (t.isDark) lightTheme else t, darkTheme = if (t.isDark) t else darkTheme)

    /**
     * Matches the colour to the system's light or dark mode, but only when that mode has changed
     * since the last match, so a dark colour picked in light mode stays until the next switch.
     */
    fun followSystem(dark: Boolean): ReaderPrefs {
        if (systemDark == dark) return this
        val remembered = withTheme(theme)
        val target = if (dark) remembered.darkTheme ?: ReaderTheme.DARK else remembered.lightTheme ?: ReaderTheme.SEPIA
        return remembered.copy(theme = target, systemDark = dark)
    }

    /** Open-book (two-page) mode for a reading area of [widthDp]. */
    fun twoPages(widthDp: Int): Boolean = !scroll && when (pageLayout) {
        PageLayout.SINGLE -> false
        PageLayout.DOUBLE -> true
        PageLayout.AUTO -> widthDp >= TWO_PAGE_MIN_WIDTH_DP
    }

    fun toEpub(widthDp: Int): EpubPreferences {
        val two = twoPages(widthDp)
        return EpubPreferences(
            fontSize = fontSize * FONT_SIZE_BASE,
            lineHeight = lineHeight,
            pageMargins = pageMargins * PAGE_MARGINS_BASE,
            theme = theme.base,
            backgroundColor = Color(theme.background),
            textColor = Color(theme.text),
            scroll = scroll,
            publisherStyles = publisherStyles,
            textAlign = if (justify) TextAlign.JUSTIFY else TextAlign.START,
            // Justified text without hyphenation leaves rivers of white space in narrow columns.
            hyphens = justify,
            columnCount = if (two) ColumnCount.TWO else ColumnCount.ONE,
            spread = if (two) Spread.ALWAYS else Spread.NEVER,
        )
    }

    companion object {
        /** The user's preferred text size and side margins (Readium 125 % / 130 %) shown as 100 %. */
        const val FONT_SIZE_BASE = 1.25
        const val PAGE_MARGINS_BASE = 1.3

        /**
         * The unfolded Pixel Fold inner display is ≈ 850 dp wide. ReadiumCSS only honours a user
         * column count above 60em (960 px), so two-page mode is forced through [rsProperties].
         */
        const val TWO_PAGE_MIN_WIDTH_DP = 640

        /**
         * Reading-system CSS properties. They are fixed when the navigator is created, so the
         * reader recreates the navigator when switching between one and two pages.
         */
        fun rsProperties(twoPages: Boolean): RsProperties = if (twoPages) {
            RsProperties(
                colCount = ColCount.TWO,
                colWidth = Length.Em(10.0), // minimum width; column-count caps it at two
                // Readium pages by exactly one viewport width, which only matches the column stride
                // with a zero gap. The spine (where the hinge sits) comes from the gutter padding
                // inside each column instead: 2 × 36 px.
                colGap = Length.Px(0.0),
                pageGutter = Length.Px(36.0),
                maxLineLength = Length.Rem(40.0),
            )
        } else {
            RsProperties()
        }
    }
}

class ReaderPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _prefs = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<ReaderPrefs>(it) }.getOrNull() } ?: ReaderPrefs(),
    )
    val current: StateFlow<ReaderPrefs> = _prefs

    fun update(transform: (ReaderPrefs) -> ReaderPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        prefs.edit { putString(KEY, json.encodeToString(ReaderPrefs.serializer(), next)) }
    }

    /**
     * Learned reading speed in characters per second (incl. spaces), kept across books.
     * Starts at ≈ 250 words per minute.
     */
    var readingCps: Float
        get() = prefs.getFloat(KEY_CPS, DEFAULT_CPS)
        set(value) = prefs.edit { putFloat(KEY_CPS, value) }

    private companion object {
        const val KEY_CPS = "reading_cps"
        const val DEFAULT_CPS = 22f

        // Bumped when defaults or scales changed (v3: sepia, 1.65 spacing, 64 dp, rescaled 100 %).
        const val KEY = "prefs_v3"
    }
}
