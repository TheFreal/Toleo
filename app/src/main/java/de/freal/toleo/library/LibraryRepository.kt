package de.freal.toleo.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import de.freal.toleo.db.BookEntity
import de.freal.toleo.db.DrmStatus
import de.freal.toleo.db.TagEntity
import de.freal.toleo.db.ToleoDatabase
import de.freal.toleo.sync.SyncWorker
import de.freal.toleo.tolino.RemoteBookRef
import de.freal.toleo.tolino.TolinoApi
import de.freal.toleo.tolino.TolinoException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A picked file waiting in the cache for its metadata to be confirmed. [meta] is null when it can't be edited. */
class UploadDraft(val file: File, val name: String, val isPdf: Boolean, val meta: EpubMeta?)

class LibraryRepository(
    private val context: Context,
    private val db: ToleoDatabase,
    private val api: TolinoApi,
) {
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    /** publicationId → download progress 0..1 for running downloads. */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads

    fun observeLibrary() = db.books().observeLibrary()

    /**
     * Books deleted from the cloud in this session, with the time. The inventory can still list a book
     * for a while after deleting it, and a refresh in that window mustn't bring it back.
     */
    private val recentlyDeleted = ConcurrentHashMap<String, Long>()

    suspend fun refreshInventory() {
        val now = System.currentTimeMillis()
        recentlyDeleted.entries.removeIf { now - it.value > DELETE_GRACE_MS }
        val remote = api.inventory().filterNot { recentlyDeleted.containsKey(it.publicationId) }
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
                    // The upsert rebuilds every row, so carry this over or a refresh clears all dismissals.
                    dismissedAt = old?.dismissedAt,
                    pinnedAt = old?.pinnedAt,
                    hidden = old?.hidden ?: false,
                    localCover = old?.localCover,
                    sortIndex = i,
                )
            },
        )
        val remoteIds = remote.map { it.publicationId }
        db.books().markMissingFromCloud(remoteIds)
        removeGoneFromCloud(remoteIds.toHashSet())
        // Uploads already on this device that haven't had their own cover taken yet (e.g. from before it existed).
        db.books().all()
            .filter { it.localCover == null && it.localPath != null && usesOwnCover(it) }
            .forEach { takeOwnCover(it.publicationId) }
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
            if (book.localCover == null) takeOwnCover(publicationId)
            dest
        } finally {
            _downloads.update { it - publicationId }
        }
    }

    suspend fun removeDownload(publicationId: String) = withContext(Dispatchers.IO) {
        db.books().get(publicationId)?.localPath?.let { File(it).delete() }
        db.books().setLocal(publicationId, null, DrmStatus.UNKNOWN)
    }

    /**
     * Copies a picked EPUB/PDF into the cache so its metadata can be edited before the upload.
     * Always follow with [uploadStaged] or [discardStaged].
     */
    suspend fun stageUpload(uri: Uri): UploadDraft = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "book.epub"
        val isPdf = name.endsWith(".pdf", true) || resolver.getType(uri)?.contains("pdf") == true
        val tmp = File(context.cacheDir, "upload-" + UUID.randomUUID() + if (isPdf) ".pdf" else ".epub")
        resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: error("Cannot read file")
        UploadDraft(tmp, name, isPdf, meta = if (isPdf) null else editableMeta(tmp))
    }

    /**
     * Uploads a staged file to the Tolino cloud and keeps it so it needn't be downloaded again. [edits]
     * rewrites the EPUB's metadata first; null, or edits equal to the original, uploads it unchanged.
     */
    suspend fun uploadStaged(draft: UploadDraft, edits: EpubMeta?) = withContext(Dispatchers.IO) {
        val original = draft.meta
        if (original == null || edits == null || edits == original) {
            uploadTemp(draft.file, draft.name, draft.isPdf)
        } else {
            val edited = File(context.cacheDir, "upload-" + UUID.randomUUID() + ".epub")
            try {
                EpubMetadata.write(draft.file, edited, original, edits)
            } catch (e: Exception) {
                edited.delete()
                draft.file.delete()
                throw e
            }
            draft.file.delete()
            uploadTemp(edited, fileNameFor(edits.title), isPdf = false)
        }
        Unit
    }

    fun discardStaged(draft: UploadDraft) {
        draft.file.delete()
    }

    /**
     * The metadata that can be edited for [file], or null when it can't be: PDFs, copy-protected books
     * (rewriting one could invalidate its license) and files that aren't readable EPUBs.
     */
    private fun editableMeta(file: File): EpubMeta? =
        if (DrmInspector.inspect(file) == DrmStatus.NONE) EpubMetadata.read(file) else null

    /** The editable metadata of a local book, or null if it has none to edit. */
    suspend fun localMeta(publicationId: String): EpubMeta? = withContext(Dispatchers.IO) {
        db.books().get(publicationId)?.localPath?.let(::File)?.takeIf { it.exists() }?.let(::editableMeta)
    }

    private fun fileNameFor(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "book" } + ".epub"

    /** Uploads [tmp] (deleted afterwards) and returns the new cloud book, or null if it isn't in the inventory yet. */
    private suspend fun uploadTemp(tmp: File, name: String, isPdf: Boolean): BookEntity? {
        try {
            val deliverableId = api.upload(tmp, name, if (isPdf) "application/pdf" else "application/epub+zip")
            refreshInventory()
            val book = db.books().all().firstOrNull { deliverableId != null && it.deliverableId == deliverableId }
            if (book != null) {
                val dest = File(booksDir, safeName(book.publicationId) + if (isPdf) ".pdf" else ".epub")
                tmp.copyTo(dest, overwrite = true)
                db.books().setLocal(book.publicationId, dest.absolutePath, DrmInspector.inspect(dest))
                takeOwnCover(book.publicationId)
            }
            return book
        } finally {
            tmp.delete()
        }
    }

    /**
     * Uploads a local-only book (see [importLocal]) to the tolino cloud. The cloud copy is a different
     * book, so the position, highlights, bookmarks, finished flag and shelf state move over to it and the
     * local copy is removed. [edits] rewrites the metadata of the uploaded copy (null: unchanged). The
     * caller must stop saving reading state under [publicationId] afterwards. Returns the new cloud book's id.
     */
    suspend fun uploadLocal(publicationId: String, edits: EpubMeta? = null): String = withContext(Dispatchers.IO) {
        require(publicationId.startsWith(LOCAL_PREFIX)) { "Not a local book" }
        val local = db.books().get(publicationId) ?: error("Unknown book")
        val file = local.localPath?.let(::File)?.takeIf { it.exists() } ?: error("The book's file is missing")
        val tmp = File(context.cacheDir, "upload-" + UUID.randomUUID() + ".epub")
        val original = edits?.let { editableMeta(file) }
        val name = if (edits != null && original != null && edits != original) {
            EpubMetadata.write(file, tmp, original, edits)
            fileNameFor(edits.title)
        } else {
            file.copyTo(tmp, overwrite = true)
            local.title + ".epub"
        }
        val uploaded = uploadTemp(tmp, name, isPdf = false)
            ?: error("Uploaded, but the book isn't in your cloud library yet. Pull to refresh, then open it from there.")
        val newId = uploaded.publicationId
        db.withTransaction {
            db.positions().move(publicationId, newId)
            db.annotations().move(publicationId, newId)
            db.tags().move(publicationId, newId)
            local.lastOpened?.let { db.books().setLastOpened(newId, it) }
            db.books().setShelfState(newId, local.dismissedAt, local.pinnedAt)
            db.books().setHidden(newId, local.hidden)
            db.books().delete(publicationId)
        }
        file.delete()
        deleteOwnCover(publicationId)
        newId
    }

    /**
     * Drops cloud books that are no longer in the cloud (deleted here or elsewhere, a returned loan) and
     * aren't on this device either, so they can't be opened any more. Downloaded ones stay readable,
     * marked not in the cloud. An empty inventory removes nothing, in case the cloud answered oddly.
     */
    private suspend fun removeGoneFromCloud(remoteIds: Set<String>) {
        if (remoteIds.isEmpty()) return
        val gone = db.books().all().filter {
            it.publicationId !in remoteIds && it.localPath == null && !it.publicationId.startsWith(LOCAL_PREFIX)
        }
        if (gone.isEmpty()) return
        db.withTransaction { gone.forEach { db.books().delete(it.publicationId) } }
        gone.forEach { deleteOwnCover(it.publicationId) }
    }

    suspend fun deleteFromCloud(publicationId: String) {
        val book = db.books().get(publicationId) ?: return
        try {
            api.deleteContent(book.deliverableId ?: book.publicationId)
        } catch (e: TolinoException) {
            // Already gone from the cloud (an earlier delete went through, or it was deleted elsewhere):
            // nothing left to do there, so finish removing it here.
            if (e.status != STATUS_NOT_IN_BOOKSHELF) throw e
        }
        recentlyDeleted[publicationId] = System.currentTimeMillis()
        removeDownload(publicationId)
        deleteOwnCover(publicationId)
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
        if (updated.localCover == null) takeOwnCover(id)
        updated
    }

    /** Uploads and local books show the cover from their own file: the cloud generates covers for uploads (and sometimes misses), local books have none. */
    private fun usesOwnCover(book: BookEntity) = book.isUpload || book.publicationId.startsWith(LOCAL_PREFIX)

    /**
     * Takes the cover out of the book's downloaded EPUB, scales it down and records it, so the library
     * shows it instead of the cloud's. Records "" when the file has no usable cover, so it isn't checked
     * again. Never throws: a book without its own cover simply keeps the cloud's.
     */
    private suspend fun takeOwnCover(publicationId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val book = db.books().get(publicationId) ?: return@runCatching
            if (!usesOwnCover(book) || book.format == "PDF" || book.drm == DrmStatus.ADOBE || book.drm == DrmStatus.LCP) return@runCatching
            val file = book.localPath?.let(::File)?.takeIf { it.exists() } ?: return@runCatching
            val dest = File(coversDir, safeName(publicationId) + ".jpg")
            val saved = EpubCover.extract(file)?.let { saveScaled(it, dest) } == true
            db.books().setLocalCover(publicationId, if (saved) Uri.fromFile(dest).toString() else "")
        }
    }

    private fun deleteOwnCover(publicationId: String) {
        File(coversDir, safeName(publicationId) + ".jpg").delete()
    }

    /** Decodes [bytes] at about [COVER_WIDTH] wide and writes them to [dest] as JPEG. False if they aren't a decodable image. */
    private fun saveScaled(bytes: ByteArray, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        // JPEG has no transparency; put transparent PNG covers on white rather than black.
        val opaque = if (!decoded.hasAlpha()) decoded else {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            }
        }
        val tmp = File(dest.path + ".tmp")
        val written = tmp.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (opaque !== decoded) opaque.recycle()
        decoded.recycle()
        return written && tmp.renameTo(dest)
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

    /**
     * Puts every book in [publicationIds] into the collections in [add] and takes them out of those in
     * [remove]. A collection exists as long as a book carries its tag, so adding to a new name creates it
     * and removing the last book deletes it. Books that are already in (or out of) a collection are left alone.
     */
    suspend fun updateCollections(publicationIds: Collection<String>, add: Set<String>, remove: Set<String>) {
        db.withTransaction {
            val tags = db.tags()
            for (id in publicationIds) {
                for (name in add) {
                    val existing = tags.byName(id, name)
                    when {
                        existing == null -> tags.upsert(
                            TagEntity(
                                publicationId = id,
                                transientId = UUID.randomUUID().toString(),
                                name = name,
                                category = TagEntity.CATEGORY_COLLECTION,
                                dirty = true,
                            ),
                        )
                        // Restoring a tag whose removal wasn't pushed yet needs no push; one never pushed does.
                        existing.deleted -> tags.upsert(existing.copy(deleted = false, dirty = existing.serverId == null))
                    }
                }
                for (name in remove) {
                    val existing = tags.byName(id, name)
                    if (existing != null && !existing.deleted) tags.upsert(existing.copy(deleted = true, dirty = true))
                }
            }
        }
        SyncWorker.syncSoon(context)
    }

    suspend fun markOpened(publicationId: String) = db.books().setLastOpened(publicationId, System.currentTimeMillis())

    /** Hides the book from "Also reading" until it is opened again, and unpins it if it was added by hand. */
    suspend fun dismissFromRecent(publicationId: String) = db.books().setShelfState(publicationId, System.currentTimeMillis(), null)

    /** Undo for [dismissFromRecent]: puts back the values the book had before. */
    suspend fun restoreShelfState(publicationId: String, dismissedAt: Long?, pinnedAt: Long?) =
        db.books().setShelfState(publicationId, dismissedAt, pinnedAt)

    /** Moves the book out of the Library into the collapsed "Hidden" list, or back. Local only. */
    suspend fun setHidden(publicationId: String, hidden: Boolean) = db.books().setHidden(publicationId, hidden)

    /** Adds the book to "Also reading" by hand. */
    suspend fun pinToRecent(publicationId: String) = db.books().setPinnedAt(publicationId, System.currentTimeMillis())

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        booksDir.listFiles()?.forEach { it.delete() }
        coversDir.listFiles()?.forEach { it.delete() }
    }

    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val LOCAL_PREFIX = "local-"

        /** Width covers taken from book files are scaled to; enough for the hero cover on a dense screen. */
        private const val COVER_WIDTH = 600

        /** `deletecontent` answer for a book that isn't (any longer) in the user's cloud. */
        private const val STATUS_NOT_IN_BOOKSHELF = -72

        /** How long a refresh ignores a book after deleting it, while the cloud inventory catches up. */
        private const val DELETE_GRACE_MS = 10 * 60 * 1000L
    }
}
