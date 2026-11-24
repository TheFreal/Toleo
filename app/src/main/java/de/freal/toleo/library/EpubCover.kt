package de.freal.toleo.library

import de.freal.toleo.library.EpubMetadata.read
import org.w3c.dom.Element
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * Finds the cover image inside an EPUB. The tolino cloud makes its own cover for an upload and
 * sometimes misses one the file declares (it then shows "No Image Available"), so uploads take
 * their cover from the file instead.
 */
object EpubCover {
    private const val OPF = "http://www.idpf.org/2007/opf"

    /** The cover's bytes, or null if the file declares none or isn't a readable EPUB. */
    fun extract(file: File): ByteArray? = runCatching {
        ZipFile(file).use { zip -> coverEntry(zip)?.let { zip.read(it) } }
    }.getOrNull()

    /**
     * The zip entry of the cover, looked up the way reading systems do, first match wins:
     * 1. the EPUB 3 manifest item with `properties="cover-image"`;
     * 2. the EPUB 2 `<meta name="cover" content="…">`, naming an item's id (or, in sloppy files, its href);
     *    if that item is a page rather than an image, the image on that page;
     * 3. the guide's `type="cover"` page, and the image on it;
     * 4. any manifest image with "cover" in its id or file name.
     */
    internal fun coverEntry(zip: ZipFile): String? {
        val opfPath = EpubMetadata.opfPath(zip)
        val opfDir = opfPath.substringBeforeLast('/', "")
        val doc = EpubMetadata.parse(zip.read(opfPath))
        val items = doc.getElementsByTagNameNS(OPF, "item").elements().map {
            Item(it.getAttribute("id"), it.getAttribute("href"), it.getAttribute("media-type"), it.getAttribute("properties"))
        }

        fun entryOf(item: Item): String? = resolve(opfDir, item.href)?.takeIf { zip.getEntry(it) != null }
        fun imageOrPageImage(item: Item): String? = when {
            item.isImage -> entryOf(item)
            item.isPage -> entryOf(item)?.let { imageOnPage(zip, it) }
            else -> null
        }

        items.firstOrNull { item -> item.isImage && "cover-image" in item.properties.split(' ') }
            ?.let(::entryOf)?.let { return it }

        val metaCover = doc.getElementsByTagNameNS(OPF, "meta").elements()
            .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
            ?.getAttribute("content")?.takeIf { it.isNotBlank() }
        if (metaCover != null) {
            val item = items.firstOrNull { it.id == metaCover } ?: items.firstOrNull { it.href == metaCover }
            item?.let(::imageOrPageImage)?.let { return it }
        }

        doc.getElementsByTagNameNS(OPF, "reference").elements()
            .firstOrNull { it.getAttribute("type").equals("cover", ignoreCase = true) }
            ?.let { resolve(opfDir, it.getAttribute("href")) }
            ?.takeIf { zip.getEntry(it) != null }
            ?.let { imageOnPage(zip, it) }
            ?.let { return it }

        return items.firstOrNull { it.isImage && (it.id.contains("cover", true) || it.href.substringAfterLast('/').contains("cover", true)) }
            ?.let(::entryOf)
    }

    private data class Item(val id: String, val href: String, val mediaType: String, val properties: String) {
        val isImage get() = mediaType.startsWith("image/") && mediaType != "image/svg+xml"
        val isPage get() = mediaType == "application/xhtml+xml" || mediaType == "text/html"
    }

    private val imgSrc = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val svgImage = Regex("""<image\b[^>]*?\b(?:xlink:)?href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * The first raster image on an (X)HTML cover page. A regex rather than a parser: cover pages often
     * use HTML entities such as `&nbsp;` that a plain XML parser rejects without the DTD.
     */
    private fun imageOnPage(zip: ZipFile, page: String): String? {
        val html = zip.read(page).toString(Charsets.UTF_8)
        val href = (svgImage.find(html) ?: imgSrc.find(html))?.groupValues?.get(1) ?: return null
        return resolve(page.substringBeforeLast('/', ""), href)
            ?.takeIf { zip.getEntry(it) != null && !it.endsWith(".svg", ignoreCase = true) }
    }

    /** Joins a relative [href] onto [baseDir] inside the zip, decoding `%20` and resolving `..`. */
    internal fun resolve(baseDir: String, href: String): String? {
        val path = href.substringBefore('#').substringBefore('?').trim()
        if (path.isEmpty() || "://" in path) return null
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        val parts = ArrayDeque<String>()
        val start = if (decoded.startsWith("/")) decoded.trimStart('/') else if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        for (segment in start.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull() ?: return null
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }
}
