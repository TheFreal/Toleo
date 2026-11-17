package de.freal.toleo.tolino

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File

/**
 * Client for the Tolino web reader REST API (`bosh.pageplace.de`), following the endpoints
 * documented by cweiske/tolino-api-docs, tolino-calibre-sync and pytolino.
 */
class TolinoApi(
    private val http: OkHttpClient,
    private val auth: TolinoAuth,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    suspend fun registerHardware() {
        call("/v2/registerhw") { post(buildJsonObject { put("hardware_name", TolinoConstants.DEVICE_NAME) }.body()) }
    }

    suspend fun inventory(): List<RemoteBook> {
        val root = call("/inventory/delta?strip=true") { get() }.obj()?.get("PublicationInventory").obj()
            ?: return emptyList()
        val uploads = root["edata"].arr().orEmpty().mapNotNull { it.obj()?.let { o -> parseInventoryItem(o, isUpload = true) } }
        val purchases = root["ebook"].arr().orEmpty().mapNotNull { it.obj()?.let { o -> parseInventoryItem(o, isUpload = false) } }
        return (uploads + purchases).distinctBy { it.publicationId }
    }

    /**
     * pytolino passes `epubMetaData.identifier` for both path segments; an older variant used
     * publicationId + deliverableId. We try both.
     */
    suspend fun downloadInfo(book: RemoteBookRef): DownloadInfo {
        val candidates = buildList {
            book.identifier?.let { add(it to it) }
            if (book.deliverableId != null) add(book.publicationId to book.deliverableId)
            add(book.publicationId to book.publicationId)
        }.distinct()
        var last: Exception? = null
        for ((a, b) in candidates) {
            try {
                val info = call("/cloud/downloadinfo/${b64(a)}/${b64(b)}/type/external-download") { get() }
                    .obj()?.get("DownloadInfo").obj() ?: continue
                val url = info["contentUrl"].str() ?: continue
                return DownloadInfo(url, info["format"].str(), info["fileSize"].long())
            } catch (e: TolinoException) {
                last = e
            }
        }
        throw last ?: TolinoException(404, null, "No download URL")
    }

    /**
     * Streams [url] to [dest]. Content URLs on the tolino host (observed:
     * `bosh.pageplace.de/bosh/rest/content/<uuid>/<name>.epub`) answer 400 without the session
     * headers; URLs on other hosts are assumed pre-signed and get none, so tokens never leak.
     */
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val session = auth.freshSession()
        val request = Request.Builder().url(url).apply {
            if (url.toHttpUrlOrNull()?.host == TolinoConstants.API_HOST) {
                header("t_auth_token", session.accessToken)
                header("hardware_id", session.hardwareId)
                header("reseller_id", session.resellerId.toString())
                header("client_type", TolinoConstants.CLIENT_TYPE)
                header("client_version", TolinoConstants.CLIENT_VERSION)
                header("hardware_type", TolinoConstants.HARDWARE_TYPE)
            }
        }.build()
        withContext(Dispatchers.IO) { streamTo(request, dest, onProgress) }
    }

    private fun streamTo(request: Request, dest: File, onProgress: (Float) -> Unit) {
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw TolinoException(resp.code, null, "Download failed")
            val total = resp.body.contentLength()
            val tmp = File(dest.parentFile, dest.name + ".part")
            resp.body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("Could not move download into place")
        }
    }

    suspend fun upload(file: File, fileName: String, mimeType: String): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, file.asRequestBody(mimeType.toMediaType()))
            .build()
        return call("/upload") { post(body) }.obj()?.get("metadata").obj()?.get("deliverableId").str()
    }

    suspend fun deleteContent(deliverableId: String) {
        call("/deletecontent?deliverableId=$deliverableId") { get() }
    }

    /** One `sync-data` round trip: send [patches] relative to [revision], get everything newer back. */
    suspend fun syncData(revision: JsonElement?, patches: List<JsonObject>): SyncResponse {
        val body = buildJsonObject {
            put("revision", revision ?: JsonNull)
            put("patches", JsonArray(patches))
        }
        val resp = call("/sync-data?paths=publications,audiobooks") {
            method("PATCH", body.body())
        }.obj() ?: JsonObject(emptyMap())
        return SyncResponse(
            revision = resp["revision"] ?: revision ?: JsonNull,
            patches = resp["patches"].arr().orEmpty().mapNotNull { it.obj() },
        )
    }

    suspend fun listDevices(): List<RemoteDevice> {
        val s = auth.freshSession()
        val body = buildJsonObject {
            put("deviceListRequest", buildJsonObject {
                putJsonArray("accounts") { addJsonObject { put("auth_token", s.accessToken); put("reseller_id", s.resellerId) } }
            })
        }
        val devices = call("/handshake/devices/list") { post(body.body()) }
            .obj()?.get("deviceListResponse").obj()?.get("devices").arr().orEmpty()
        return devices.mapNotNull { it.obj() }.mapNotNull { d ->
            RemoteDevice(
                id = d["deviceId"].str() ?: return@mapNotNull null,
                name = d["deviceName"].str() ?: d["deviceId"].str()!!,
                type = d["deviceType"].str(),
                lastUsage = d["lastUsage"].long(),
                resellerId = d["resellerId"].int(),
            )
        }
    }

    suspend fun deleteDevice(deviceId: String) {
        val s = auth.freshSession()
        val body = buildJsonObject {
            put("deleteDevicesRequest", buildJsonObject {
                putJsonArray("accounts") { addJsonObject { put("auth_token", s.accessToken); put("reseller_id", s.resellerId) } }
                put("devices", buildJsonArray { addJsonObject { put("device_id", deviceId); put("reseller_id", s.resellerId) } })
            })
        }
        call("/handshake/devices/delete") { post(body.body()) }
    }

    // --- plumbing ---

    private fun JsonObject.body(): RequestBody = toString().toRequestBody(jsonType)

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    private suspend fun call(path: String, build: Request.Builder.() -> Request.Builder): JsonElement? {
        suspend fun attempt(session: Session): Pair<Int, String> = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(TolinoConstants.BASE_URL + path)
                .header("t_auth_token", session.accessToken)
                .header("hardware_id", session.hardwareId)
                .header("reseller_id", session.resellerId.toString())
                .header("client_type", TolinoConstants.CLIENT_TYPE)
                .header("client_version", TolinoConstants.CLIENT_VERSION)
                .header("hardware_type", TolinoConstants.HARDWARE_TYPE)
                .build(build)
            http.newCall(request).execute().use { it.code to it.body.string() }
        }
        var (code, text) = attempt(auth.freshSession())
        if (code == 401) {
            val retry = attempt(auth.forceRefresh())
            code = retry.first; text = retry.second
        }
        val parsed = text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val info = parsed.obj()?.get("ResponseInfo").obj()
        val status = info?.get("responseStatus").int()
        if (code !in 200..299 || (status != null && status !in 200..299 && status != 0)) {
            throw TolinoException(code, status, info?.get("message").str() ?: text.take(300))
        }
        return parsed
    }

    private inline fun Request.Builder.build(block: Request.Builder.() -> Request.Builder): Request = block().build()
}

/** Identifiers needed to fetch a book's file. */
data class RemoteBookRef(val publicationId: String, val deliverableId: String?, val identifier: String?)
