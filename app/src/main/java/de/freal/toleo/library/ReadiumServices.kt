package de.freal.toleo.library

import android.content.Context
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class ReadiumServices(context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null),
    )

    suspend fun open(file: File): Result<Publication> {
        val asset = assetRetriever.retrieve(file).getOrElse { return Result.failure(Exception(it.message)) }
        // Passed to open(): Readium 3.4 never calls the constructor's onCreatePublication, and calls
        // this one twice (hence the idempotent injection).
        return opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            val htmlHrefs = (manifest.readingOrder + manifest.resources)
                .filter { it.mediaType?.isHtml == true }
                .map { it.url() }
                .toSet()
            container = TransformingContainer(container) { url, resource ->
                if (url in htmlHrefs) resource.map { Try.success(ReaderStyleInjector.inject(it)) } else resource
            }
        }).fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                asset.close()
                Result.failure(Exception(it.message))
            },
        )
    }
}

/**
 * Adds the reader's own CSS to each HTML document of a book, for rules Readium has no setting for.
 *
 * ReadiumCSS sets `body { widows: 2; orphans: 2 }`. With page heights snapped to whole lines that
 * often leaves a column one line short, so the bottom margin is visibly larger than the top one.
 * The style goes at the end of the document's `<head>`, after Readium's "before" stylesheet, so it
 * wins at equal specificity. Inserted as bytes, so the document's encoding is left alone.
 */
object ReaderStyleInjector {
    const val STYLE = "<style>body{widows:1;orphans:1}</style>"
    private val style = STYLE.toByteArray(Charsets.US_ASCII)
    private val headEnd = "</head".toByteArray(Charsets.US_ASCII)

    fun inject(html: ByteArray): ByteArray {
        val at = indexOfIgnoreCase(html, headEnd)
        if (at < 0 || indexOfIgnoreCase(html, style) >= 0) return html
        return html.copyOfRange(0, at) + style + html.copyOfRange(at, html.size)
    }

    private fun indexOfIgnoreCase(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                val b = haystack[i + j].toInt()
                val lower = if (b in 'A'.code..'Z'.code) b + 32 else b
                val n = needle[j].toInt()
                val nLower = if (n in 'A'.code..'Z'.code) n + 32 else n
                if (lower != nLower) continue@outer
            }
            return i
        }
        return -1
    }
}
