package de.freal.toleo.position

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.mediatype.MediaType

/** A highlight range expressed both ways. */
data class MappedRange(val start: String, val end: String, val locator: Locator, val progression: Double)

/**
 * Translates between Tolino position strings and Readium [Locator]s for one open publication.
 * Every conversion goes through [TextIndex] (flat character offsets into a resource), and
 * falls back to chapter + progress when a point path can't be resolved, so a wrong path
 * hypothesis degrades to chapter-level accuracy instead of failing.
 */
class PositionMapper(private val publication: Publication) {
    private val indexes = mutableMapOf<String, TextIndex?>()
    private val lock = Mutex()

    /** Reading position from Tolino → Locator. */
    suspend fun locatorFor(position: String?, progress: Double?): Locator? {
        if (position != null) {
            val link = linkFor(TolinoPoint.hrefOf(position))
            if (link != null) {
                val point = TolinoPoint.parse(position)
                val idx = index(link)
                val flat = if (point != null && idx != null) idx.resolve(point.steps, point.offset) else null
                return baseLocator(link).copyWithLocations(
                    progression = flat?.let { idx!!.progressionOf(it) } ?: 0.0,
                    totalProgression = progress,
                )
            }
        }
        return progress?.let { publication.locateProgression(it) }
    }

    /** Highlight from Tolino → Locator carrying a text quote, which is what Readium decorations need. */
    suspend fun highlightLocator(start: String?, end: String?, text: String?): Locator? {
        start ?: return null
        val link = linkFor(TolinoPoint.hrefOf(start)) ?: return null
        val idx = index(link) ?: return null
        val s = TolinoPoint.parse(start)?.let { idx.resolve(it.steps, it.offset) }
        val e = end?.let(TolinoPoint::parse)?.let { idx.resolve(it.steps, it.offset) }
        val range = when {
            s != null && e != null && e > s -> s until e
            // Paths didn't resolve: find the quoted text instead.
            !text.isNullOrBlank() -> idx.findQuote(text, null, null, s)?.let { it.first until it.last }
            else -> null
        } ?: return null
        return quoteLocator(link, idx, range.first, range.last + 1)
    }

    /** Current Locator → Tolino position string. */
    suspend fun positionFor(locator: Locator): String? {
        val link = linkFor(locator.href.toString()) ?: return null
        val href = tolinoHref(link)
        val idx = index(link) ?: return href
        val (steps, offset) = idx.pathFor(idx.flatOf(locator.locations.progression ?: 0.0)) ?: return href
        return TolinoPoint(href, steps, offset).toString()
    }

    /** A user selection (page Locator + text quote) → Tolino start/end positions and a precise Locator. */
    suspend fun rangeFor(selection: Locator): MappedRange? {
        val quote = selection.text.highlight?.takeIf { it.isNotBlank() } ?: return null
        val link = linkFor(selection.href.toString()) ?: return null
        val idx = index(link) ?: return null
        val near = selection.locations.progression?.let(idx::flatOf)
        val range = idx.findQuote(quote, selection.text.before, selection.text.after, near) ?: return null
        val href = tolinoHref(link)
        val (sSteps, sOff) = idx.pathFor(range.first) ?: return null
        val (eSteps, eOff) = idx.pathFor(range.last) ?: return null
        return MappedRange(
            start = TolinoPoint(href, sSteps, sOff).toString(),
            end = TolinoPoint(href, eSteps, eOff).toString(),
            // Quote taken from the resource itself, so it matches the DOM text Readium searches.
            locator = quoteLocator(link, idx, range.first, range.last),
            progression = idx.progressionOf(range.first),
        )
    }

    /** Short text excerpt at a locator, used as the `name` of page bookmarks. */
    suspend fun excerptAt(locator: Locator, length: Int = 80): String? {
        val link = linkFor(locator.href.toString()) ?: return null
        val idx = index(link) ?: return null
        val at = idx.flatOf(locator.locations.progression ?: 0.0)
        return idx.slice(at, at + length).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    private fun quoteLocator(link: Link, idx: TextIndex, from: Int, to: Int) =
        baseLocator(link).copy(
            locations = Locator.Locations(progression = idx.progressionOf(from)),
            text = Locator.Text(
                before = idx.slice(from - 48, from),
                highlight = idx.slice(from, to),
                after = idx.slice(to, to + 48),
            ),
        )

    private fun baseLocator(link: Link) = Locator(
        href = link.url(),
        mediaType = link.mediaType ?: MediaType.XHTML,
        title = link.title,
    )

    private fun tolinoHref(link: Link): String = Uri.decode(link.url().toString())

    private fun linkFor(href: String): Link? {
        val wanted = normalize(href)
        if (wanted.isEmpty()) return null
        val order = publication.readingOrder
        return order.firstOrNull { normalize(it.url().toString()) == wanted }
            // Tolino hrefs may be relative to the OPF directory rather than the container root.
            ?: order.firstOrNull { normalize(it.url().toString()).endsWith("/$wanted") }
            ?: order.firstOrNull { wanted.endsWith("/" + normalize(it.url().toString())) }
    }

    private fun normalize(href: String) = Uri.decode(href.substringBefore('#')).trimStart('/')

    private suspend fun index(link: Link): TextIndex? = lock.withLock {
        val key = link.url().toString()
        if (indexes.containsKey(key)) return indexes[key]
        val idx = withContext(Dispatchers.Default) {
            val resource = publication.get(link) ?: return@withContext null
            try {
                resource.read().getOrNull()?.decodeToString()?.let { runCatching { TextIndex(it) }.getOrNull() }
            } finally {
                resource.close()
            }
        }
        indexes[key] = idx
        idx
    }
}
