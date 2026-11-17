package de.freal.toleo.reader

import de.freal.toleo.AppContainer
import de.freal.toleo.db.BookEntity
import de.freal.toleo.position.PositionMapper
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File

/** An opened publication waiting to be shown by [ReaderActivity]. */
class ReaderSession(
    val book: BookEntity,
    val publication: Publication,
    val mapper: PositionMapper,
    val initialLocator: Locator?,
) {
    val navigatorFactory = EpubNavigatorFactory(publication)

    companion object {
        suspend fun open(container: AppContainer, book: BookEntity): Result<ReaderSession> {
            val path = book.localPath ?: return Result.failure(IllegalStateException("Not downloaded"))
            val publication = container.readium.open(File(path)).getOrElse { return Result.failure(it) }
            val mapper = PositionMapper(publication)
            val pos = container.reading.position(book.publicationId)
            // Our own precise locator if the server didn't move us since; otherwise map the Tolino position.
            val initial = pos?.locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                ?: runCatching { mapper.locatorFor(pos?.tolinoPosition, pos?.progress) }.getOrNull()
            return Result.success(ReaderSession(book, publication, mapper, initial))
        }
    }
}
