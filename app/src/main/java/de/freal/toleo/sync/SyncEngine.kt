package de.freal.toleo.sync

import android.util.Log
import de.freal.toleo.db.AnnotationEntity
import de.freal.toleo.db.AnnotationType
import de.freal.toleo.db.PositionEntity
import de.freal.toleo.db.SyncStateEntity
import de.freal.toleo.db.TagEntity
import de.freal.toleo.db.ToleoDatabase
import de.freal.toleo.tolino.NeedsLoginException
import de.freal.toleo.tolino.TokenStore
import de.freal.toleo.tolino.TolinoApi
import de.freal.toleo.tolino.TolinoException
import de.freal.toleo.tolino.double
import de.freal.toleo.tolino.long
import de.freal.toleo.tolino.obj
import de.freal.toleo.tolino.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val at: Long) : SyncStatus
    data class Failed(val message: String, val needsLogin: Boolean = false) : SyncStatus
}

/**
 * Two-way sync of reading positions, highlights/notes, page bookmarks and tags over the
 * revision-based `sync-data` patch protocol.
 *
 * Local edits are not queued as patches; rows carry `dirty`/`deleted` flags and outgoing
 * patches are built from the current row state at push time. That way an offline
 * add → edit → delete sequence collapses correctly and server IDs assigned in between are used.
 */
class SyncEngine(
    private val db: ToleoDatabase,
    private val api: TolinoApi,
    private val tokens: TokenStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    suspend fun sync(): SyncStatus = mutex.withLock {
        val session = tokens.session.value ?: return SyncStatus.Failed("Not logged in", needsLogin = true)
        val account = "${session.partnerId}:${session.resellerId}"
        _status.value = SyncStatus.Running
        val result = try {
            var revision: JsonElement? = db.syncState().get(account)?.revisionJson?.let { json.parseToJsonElement(it) }

            // 1. Pull, so remove/replace patches go out against a fresh top-level revision.
            val pulled = api.syncData(revision, emptyList())
            applyAll(pulled.patches)
            revision = pulled.revision
            saveRevision(account, revision)

            // 2. Push dirty rows.
            val outgoing = buildOutgoing()
            if (outgoing.isNotEmpty()) {
                revision = try {
                    push(revision, outgoing)
                } catch (e: TolinoException) {
                    // One malformed patch shouldn't block everything: retry individually.
                    Log.w(TAG, "Batch push failed, retrying one by one", e)
                    var rev = revision
                    for (item in outgoing) {
                        rev = runCatching { push(rev, listOf(item)) }
                            .onFailure { Log.w(TAG, "Patch rejected: ${item.patch}", it) }
                            .getOrDefault(rev)
                    }
                    rev
                }
                saveRevision(account, revision)
            }
            SyncStatus.Done(System.currentTimeMillis())
        } catch (e: NeedsLoginException) {
            SyncStatus.Failed(e.message ?: "Login required", needsLogin = true)
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            SyncStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
        _status.value = result
        result
    }

    private suspend fun push(revision: JsonElement?, items: List<Outgoing>): JsonElement? {
        val resp = api.syncData(revision, items.map { it.patch })
        items.forEach { it.onSent() }
        applyAll(resp.patches)
        return resp.revision
    }

    private suspend fun saveRevision(account: String, revision: JsonElement?) {
        db.syncState().upsert(
            SyncStateEntity(account, revision?.takeIf { it !is JsonNull }?.toString(), System.currentTimeMillis()),
        )
    }

    // ---------------------------------------------------------------- outgoing

    private class Outgoing(val patch: JsonObject, val onSent: suspend () -> Unit)

    private suspend fun buildOutgoing(): List<Outgoing> = buildList {
        for (p in db.positions().dirty().filterNot { isLocalOnly(it.publicationId) }) {
            val position = p.tolinoPosition ?: continue
            val base = "/publications/${p.publicationId}/bookmark"
            val value = buildJsonObject {
                put("modified", p.modified)
                put("position", position)
                put("currentPosition", position)
                put("progress", p.progress)
                p.itemRevision?.let { put("revision", it) }
            }
            add(Outgoing(patch(if (p.serverId == null) "add" else "replace", p.serverId?.let { "$base/$it" } ?: base, value)) {
                val now = db.positions().get(p.publicationId)
                if (now != null && now.modified == p.modified) db.positions().upsert(now.copy(dirty = false))
            })
        }

        for (a in db.annotations().dirty().filterNot { isLocalOnly(it.publicationId) }) {
            val base = "/publications/${a.publicationId}/${a.type.tolinoType}"
            if (a.deleted && a.serverId == null) {
                db.annotations().hardDelete(a.id) // never reached the server
                continue
            }
            val sent: suspend () -> Unit = {
                val now = db.annotations().get(a.id)
                when {
                    now == null -> Unit
                    now.deleted -> db.annotations().hardDelete(a.id)
                    now.modified == a.modified -> db.annotations().upsert(now.copy(dirty = false))
                }
            }
            if (a.deleted) {
                add(Outgoing(patch("remove", "$base/${a.serverId}", buildJsonObject {
                    put("modified", System.currentTimeMillis())
                    a.itemRevision?.let { put("revision", it) }
                }), sent))
                continue
            }
            if (a.startPosition == null) continue // position not mapped yet; retried later
            val value = buildJsonObject {
                put("modified", a.modified)
                if (a.serverId == null) put("transientId", a.transientId) else a.itemRevision?.let { put("revision", it) }
                when (a.type) {
                    AnnotationType.DOGEAR -> {
                        put("position", a.startPosition)
                        put("name", a.text.orEmpty())
                    }
                    AnnotationType.HIGHLIGHT -> {
                        put("startPosition", a.startPosition)
                        put("endPosition", a.endPosition ?: a.startPosition)
                        put("text", a.text.orEmpty())
                        if (!a.note.isNullOrBlank()) put("note", a.note)
                    }
                }
            }
            add(Outgoing(patch(if (a.serverId == null) "add" else "replace", a.serverId?.let { "$base/$it" } ?: base, value), sent))
        }

        for (t in db.tags().dirty().filterNot { isLocalOnly(it.publicationId) }) {
            val base = "/publications/${t.publicationId}/tags"
            if (t.deleted && t.serverId == null) {
                db.tags().hardDelete(t.id)
                continue
            }
            val sent: suspend () -> Unit = {
                if (t.deleted) db.tags().hardDelete(t.id) else db.tags().upsert(t.copy(dirty = false))
            }
            val value = buildJsonObject {
                put("modified", System.currentTimeMillis())
                put("name", t.name)
                put("category", t.category)
                if (t.serverId == null) put("transientId", t.transientId ?: UUID.randomUUID().toString())
                else t.itemRevision?.let { put("revision", it) }
            }
            when {
                t.deleted -> add(Outgoing(patch("remove", "$base/${t.serverId}", value), sent))
                t.serverId == null -> add(Outgoing(patch("add", base, value), sent))
                // Existing tags have nothing to change; clear the flag locally.
                else -> db.tags().upsert(t.copy(dirty = false))
            }
        }
    }

    /** Books opened from local files ("Open with Toleo") never go to the cloud. */
    private fun isLocalOnly(publicationId: String) =
        publicationId.startsWith(de.freal.toleo.library.LibraryRepository.LOCAL_PREFIX)

    private fun patch(op: String, path: String, value: JsonObject) = buildJsonObject {
        put("op", op)
        put("path", path)
        put("value", value)
    }

    // ---------------------------------------------------------------- incoming

    private suspend fun applyAll(patches: List<JsonObject>) {
        for (p in patches) {
            runCatching {
                val op = p["op"].str() ?: "add"
                val path = p["path"].str() ?: return@runCatching
                expand(op, path.split('/').filter { it.isNotEmpty() }, p["value"])
            }.onFailure { Log.w(TAG, "Could not apply patch $p", it) }
        }
    }

    /**
     * Patches normally address single items (`/publications/<id>/<type>/<serverId>`), but a full
     * fetch may deliver whole subtrees. Walk down until we reach items.
     */
    private suspend fun expand(op: String, segs: List<String>, value: JsonElement?) {
        if (segs.firstOrNull() != "publications") return
        when (segs.size) {
            1 -> value.obj()?.forEach { (pubId, v) -> expand(op, segs + pubId, v) }
            2 -> value.obj()?.forEach { (type, v) -> expand(op, segs + type, v) }
            3 -> {
                val obj = value.obj()
                if (op == "remove" || obj == null || looksLikeItem(obj)) {
                    applyItem(op, segs[1], segs[2], obj?.get("id").str(), obj)
                } else {
                    obj.forEach { (id, v) -> expand(op, segs + id, v) }
                }
            }
            else -> applyItem(op, segs[1], segs[2], segs[3], value.obj())
        }
    }

    private fun looksLikeItem(o: JsonObject) =
        listOf("modified", "position", "progress", "startPosition", "name", "revision").any { it in o }

    private suspend fun applyItem(op: String, pubId: String, type: String, serverId: String?, value: JsonObject?) {
        when (type) {
            "bookmark" -> applyPosition(op, pubId, serverId, value)
            "dogears" -> applyAnnotation(op, pubId, AnnotationType.DOGEAR, serverId, value)
            "comments" -> applyAnnotation(op, pubId, AnnotationType.HIGHLIGHT, serverId, value)
            "tags" -> applyTag(op, pubId, serverId, value)
        }
    }

    private suspend fun applyPosition(op: String, pubId: String, serverId: String?, v: JsonObject?) {
        if (op == "remove" || v == null) return
        val local = db.positions().get(pubId)
        val remoteModified = v["modified"].long() ?: 0L
        val position = v["position"].str() ?: v["currentPosition"].str()
        val rev = v["revision"].str()
        if (local != null && local.dirty && local.modified > remoteModified) {
            db.positions().upsert(local.copy(serverId = serverId ?: local.serverId, itemRevision = rev ?: local.itemRevision))
            return
        }
        db.positions().upsert(
            PositionEntity(
                publicationId = pubId,
                serverId = serverId ?: local?.serverId,
                itemRevision = rev ?: local?.itemRevision,
                tolinoPosition = position ?: local?.tolinoPosition,
                progress = v["progress"].double() ?: local?.progress ?: 0.0,
                // Keep our precise locator only if the remote is our own echo.
                locatorJson = local?.locatorJson?.takeIf { position == null || position == local.tolinoPosition },
                modified = maxOf(remoteModified, local?.modified ?: 0L),
                dirty = false,
            ),
        )
    }

    private suspend fun applyAnnotation(op: String, pubId: String, type: AnnotationType, serverId: String?, v: JsonObject?) {
        val dao = db.annotations()
        val transientId = v?.get("transientId").str()
        val local = serverId?.let { dao.byServerId(pubId, it) } ?: transientId?.let { dao.byTransientId(it) }
        if (op == "remove") {
            local?.let { dao.hardDelete(it.id) }
            return
        }
        v ?: return
        val remoteModified = v["modified"].long() ?: System.currentTimeMillis()
        val rev = v["revision"].str()
        if (local != null && local.dirty && local.modified > remoteModified) {
            dao.upsert(local.copy(serverId = serverId ?: local.serverId, itemRevision = rev ?: local.itemRevision))
            return
        }
        val start = if (type == AnnotationType.DOGEAR) v["position"].str() else v["startPosition"].str()
        val end = if (type == AnnotationType.DOGEAR) start else v["endPosition"].str()
        val text = if (type == AnnotationType.DOGEAR) v["name"].str() else v["text"].str()
        val positionsChanged = local == null || local.startPosition != start || local.endPosition != end
        dao.upsert(
            AnnotationEntity(
                id = local?.id ?: UUID.randomUUID().toString(),
                publicationId = pubId,
                type = type,
                serverId = serverId ?: local?.serverId,
                itemRevision = rev ?: local?.itemRevision,
                transientId = local?.transientId ?: transientId ?: UUID.randomUUID().toString(),
                startPosition = start,
                endPosition = end,
                text = text ?: local?.text,
                note = v["note"].str()?.takeIf { it.isNotBlank() },
                color = local?.color ?: AnnotationEntity.DEFAULT_HIGHLIGHT,
                locatorJson = if (positionsChanged) null else local?.locatorJson,
                progress = local?.progress,
                modified = remoteModified,
                dirty = false,
            ),
        )
    }

    private suspend fun applyTag(op: String, pubId: String, serverId: String?, v: JsonObject?) {
        val dao = db.tags()
        val name = v?.get("name").str()
        val local = serverId?.let { dao.byServerId(pubId, it) }
            ?: v?.get("transientId").str()?.let { dao.byTransientId(it) }
            ?: name?.let { dao.byName(pubId, it) }
        if (op == "remove") {
            local?.let { dao.hardDelete(it.id) }
            return
        }
        if (name == null || v == null) return
        if (local != null && local.dirty && local.deleted) {
            // Locally removed and not pushed yet: just learn the server identity so the remove can go out.
            dao.upsert(local.copy(serverId = serverId ?: local.serverId, itemRevision = v["revision"].str() ?: local.itemRevision))
            return
        }
        dao.upsert(
            TagEntity(
                id = local?.id ?: 0,
                publicationId = pubId,
                serverId = serverId ?: local?.serverId,
                itemRevision = v["revision"].str() ?: local?.itemRevision,
                transientId = local?.transientId,
                name = name,
                category = v["category"].str() ?: TagEntity.CATEGORY_COLLECTION,
                dirty = false,
            ),
        )
    }

    private companion object {
        const val TAG = "SyncEngine"
    }
}
