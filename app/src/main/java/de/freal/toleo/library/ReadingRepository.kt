package de.freal.toleo.library

import de.freal.toleo.db.AnnotationEntity
import de.freal.toleo.db.AnnotationType
import de.freal.toleo.db.PositionEntity
import de.freal.toleo.db.ToleoDatabase
import org.readium.r2.shared.publication.Locator
import java.util.UUID

/** Local writes for positions and annotations. Rows are flagged dirty; [de.freal.toleo.sync.SyncEngine] pushes them. */
class ReadingRepository(private val db: ToleoDatabase) {

    fun observeAnnotations(publicationId: String) = db.annotations().observe(publicationId)

    suspend fun position(publicationId: String) = db.positions().get(publicationId)

    /**
     * Saves the reading position. With [moved] false (the reader hasn't left the page the book
     * opened on) only the local locator is refreshed: the cloud position and `modified` stay
     * untouched, so merely opening a book can never move progress on other devices.
     */
    suspend fun savePosition(publicationId: String, locator: Locator, tolinoPosition: String?, moved: Boolean) {
        val old = db.positions().get(publicationId)
        if (!moved) {
            db.positions().upsert((old ?: PositionEntity(publicationId)).copy(locatorJson = locator.toJSON().toString()))
            return
        }
        val progress = locator.locations.totalProgression ?: old?.progress ?: 0.0
        if (old != null && old.tolinoPosition == tolinoPosition && old.locatorJson == locator.toJSON().toString()) return
        db.positions().upsert(
            (old ?: PositionEntity(publicationId)).copy(
                tolinoPosition = tolinoPosition ?: old?.tolinoPosition,
                progress = progress,
                locatorJson = locator.toJSON().toString(),
                modified = System.currentTimeMillis(),
                dirty = tolinoPosition != null,
            ),
        )
    }

    suspend fun addHighlight(
        publicationId: String,
        locator: Locator,
        start: String,
        end: String,
        progression: Double?,
        color: Int,
        note: String?,
    ): AnnotationEntity {
        val a = AnnotationEntity(
            id = UUID.randomUUID().toString(),
            publicationId = publicationId,
            type = AnnotationType.HIGHLIGHT,
            transientId = UUID.randomUUID().toString(),
            startPosition = start,
            endPosition = end,
            text = locator.text.highlight,
            note = note?.takeIf { it.isNotBlank() },
            color = color,
            locatorJson = locator.toJSON().toString(),
            progress = locator.locations.totalProgression ?: progression,
            modified = System.currentTimeMillis(),
            dirty = true,
        )
        db.annotations().upsert(a)
        return a
    }

    suspend fun addDogear(publicationId: String, locator: Locator, position: String?, excerpt: String?) {
        db.annotations().upsert(
            AnnotationEntity(
                id = UUID.randomUUID().toString(),
                publicationId = publicationId,
                type = AnnotationType.DOGEAR,
                transientId = UUID.randomUUID().toString(),
                startPosition = position,
                endPosition = position,
                text = excerpt,
                locatorJson = locator.toJSON().toString(),
                progress = locator.locations.totalProgression,
                modified = System.currentTimeMillis(),
                dirty = true,
            ),
        )
    }

    suspend fun updateAnnotation(id: String, note: String?, color: Int) {
        val a = db.annotations().get(id) ?: return
        val noteChanged = (a.note ?: "") != (note ?: "")
        db.annotations().upsert(
            a.copy(
                note = note?.takeIf { it.isNotBlank() },
                color = color,
                // Colour is local-only (Tolino has no field for it); only note edits need a push.
                modified = if (noteChanged) System.currentTimeMillis() else a.modified,
                dirty = a.dirty || noteChanged,
            ),
        )
    }

    suspend fun deleteAnnotation(id: String) {
        val a = db.annotations().get(id) ?: return
        db.annotations().upsert(a.copy(deleted = true, dirty = true, modified = System.currentTimeMillis()))
    }

    /** Stores a locator resolved from Tolino positions, without marking the row dirty. */
    suspend fun saveResolvedLocator(id: String, locator: Locator) {
        val a = db.annotations().get(id) ?: return
        db.annotations().upsert(
            a.copy(locatorJson = locator.toJSON().toString(), progress = locator.locations.totalProgression ?: locator.locations.progression),
        )
    }

    /** Backfills Tolino positions for annotations created before the mapper could resolve them. */
    suspend fun savePositions(id: String, start: String, end: String) {
        val a = db.annotations().get(id) ?: return
        db.annotations().upsert(a.copy(startPosition = start, endPosition = end, dirty = true))
    }
}
