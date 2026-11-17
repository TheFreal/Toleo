package de.freal.toleo.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.freal.toleo.db.BookEntity
import de.freal.toleo.db.DrmStatus
import de.freal.toleo.db.TagEntity
import de.freal.toleo.db.ToleoDatabase
import de.freal.toleo.sync.SyncWorker
import de.freal.toleo.tolino.RemoteBookRef
import de.freal.toleo.tolino.TolinoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryRepository(
    private val context: Context,
    private val db: ToleoDatabase,
    private val api: TolinoApi,
) {
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }

    /** publicationId → download progress 0..1 for running downloads. */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads

    fun observeLibrary() = db.books().observeLibrary()

    suspend fun refreshInventory() {
        val remote = api.inventory()
        val existing = db.books().all().associateBy { it.publicationId }
        db.books().upsert(
            remote.mapIndexed { i, r ->
                val old = existing[r.publicationId]
                BookEntity(
                    publicationId = r.publicationId,
                    deliverableId = r.deliverableId,
                    identifier = r.identifier,
                    resellerId = r.resellerId,
                    title = r.title,
                    subtitle = r.subtitle,
                    authors = r.authors.joinToString(", "),
                    publisher = r.publisher,
                    language = r.language,
                    issued = r.issued,
                    format = r.format.name,
                    purchased = r.purchased,
                    isUpload = r.isUpload,
                    coverUrl = r.coverUrl,
                    inCloud = true,
                    localPath = old?.localPath,
                    drm = old?.drm ?: DrmStatus.UNKNOWN,
                    lastOpened = old?.lastOpened,
                    sortIndex = i,
                )
            },
        )
        db.books().markMissingFromCloud(remote.map { it.publicationId })
    }

    suspend fun download(publicationId: String): File = withContext(Dispatchers.IO) {
        val book = db.books().get(publicationId) ?: error("Unknown book")
        _downloads.update { it + (publicationId to 0f) }
        try {
            val info = api.downloadInfo(RemoteBookRef(book.publicationId, book.deliverableId, book.identifier))
            val ext = when {
                book.format == "PDF" || info.format?.contains("pdf", true) == true -> "pdf"
                else -> "epub"
            }
            val dest = File(booksDir, safeName(publicationId) + "." + ext)
            api.download(info.contentUrl, dest) { p -> _downloads.update { it + (publicationId to p) } }
            db.books().setLocal(publicationId, dest.absolutePath, DrmInspector.inspect(dest))
            dest
        } finally {
            _downloads.update { it - publicationId }
        }
    }

    suspend fun removeDownload(publicationId: String) = withContext(Dispatchers.IO) {
        db.books().get(publicationId)?.localPath?.let { File(it).delete() }
        db.books().setLocal(publicationId, null, DrmStatus.UNKNOWN)
    }

    /** Uploads a local EPUB/PDF to the Tolino cloud and keeps the file so it needn't be downloaded again. */
    suspend fun upload(uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "book.epub"
        val isPdf = name.endsWith(".pdf", true) || resolver.getType(uri)?.contains("pdf") == true
        val tmp = File(context.cacheDir, "upload-" + UUID.randomUUID() + if (isPdf) ".pdf" else ".epub")
        resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: error("Cannot read file")
        try {
            val deliverableId = api.upload(tmp, name, if (isPdf) "application/pdf" else "application/epub+zip")
            refreshInventory()
            val book = db.books().all().firstOrNull { deliverableId != null && it.deliverableId == deliverableId }
            if (book != null) {
                val dest = File(booksDir, safeName(book.publicationId) + if (isPdf) ".pdf" else ".epub")
                tmp.copyTo(dest, overwrite = true)
                db.books().setLocal(book.publicationId, dest.absolutePath, DrmInspector.inspect(dest))
            }
        } finally {
            tmp.delete()
        }
    }

    suspend fun deleteFromCloud(publicationId: String) {
        val book = db.books().get(publicationId) ?: return
        api.deleteContent(book.deliverableId ?: book.publicationId)
        removeDownload(publicationId)
        db.books().delete(publicationId)
    }

    /**
     * Imports an EPUB opened from another app ("Open with Toleo"). It is read locally only: its
     * publicationId carries the [LOCAL_PREFIX], which the sync engine never sends to the cloud.
     */
    suspend fun importLocal(uri: Uri): BookEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "book.epub"
        val tmp = File(context.cacheDir, "import-" + UUID.randomUUID())
        resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: error("Cannot read file")
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(tmp.readBytes())
        val id = LOCAL_PREFIX + digest.take(10).joinToString("") { "%02x".format(it) }
        val dest = File(booksDir, safeName(id) + ".epub")
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
        val book = db.books().get(id) ?: BookEntity(
            publicationId = id, deliverableId = null, identifier = null, resellerId = null,
            title = name.substringBeforeLast('.'), subtitle = null, authors = "", publisher = null,
            language = null, issued = null, format = "EPUB", purchased = false, isUpload = false,
            coverUrl = null, inCloud = false, sortIndex = Int.MAX_VALUE,
        )
        val updated = book.copy(localPath = dest.absolutePath, drm = DrmInspector.inspect(dest))
        db.books().upsert(listOf(updated))
        updated
    }

    suspend fun setFinished(publicationId: String, finished: Boolean) {
        val tags = db.tags()
        val existing = tags.byName(publicationId, TagEntity.FINISHED_NAME)
        when {
            finished && existing == null -> tags.upsert(
                TagEntity(
                    publicationId = publicationId,
                    transientId = UUID.randomUUID().toString(),
                    name = TagEntity.FINISHED_NAME,
                    category = TagEntity.CATEGORY_SYSTEM,
                    dirty = true,
                ),
            )
            finished && existing != null && existing.deleted -> tags.upsert(existing.copy(deleted = false, dirty = existing.serverId == null))
            !finished && existing != null -> tags.upsert(existing.copy(deleted = true, dirty = true))
        }
        SyncWorker.syncSoon(context)
    }

    suspend fun markOpened(publicationId: String) = db.books().setLastOpened(publicationId, System.currentTimeMillis())

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        booksDir.listFiles()?.forEach { it.delete() }
    }

    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val LOCAL_PREFIX = "local-"
    }
}
