package de.freal.toleo.tolino

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

class TolinoException(val httpCode: Int, val status: Int?, message: String) :
    Exception("HTTP $httpCode${status?.let { " / $it" } ?: ""}: $message")

enum class BookFormat { EPUB, PDF, OTHER }

/** A title from `inventory/delta`, flattened from the loosely structured JSON. */
data class RemoteBook(
    val publicationId: String,
    val deliverableId: String?,
    val identifier: String?,
    val resellerId: Int?,
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val publisher: String?,
    val isbn: String?,
    val language: String?,
    val issued: String?,
    val format: BookFormat,
    val purchased: Boolean,
    val isUpload: Boolean,
    val coverUrl: String?,
)

data class DownloadInfo(val contentUrl: String, val format: String?, val fileSize: Long?)

data class SyncResponse(val revision: JsonElement, val patches: List<JsonObject>)

data class RemoteDevice(
    val id: String,
    val name: String,
    val type: String?,
    val lastUsage: Long?,
    val resellerId: Int?,
)

// --- lenient JSON helpers: the API mixes strings/numbers and omits fields freely ---

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = when (this) {
    is JsonArray -> this
    is JsonObject -> JsonArray(listOf(this)) // single item serialized without array
    else -> null
}
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
internal fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal fun parseInventoryItem(item: JsonObject, isUpload: Boolean): RemoteBook? {
    val meta = item["epubMetaData"].obj() ?: JsonObject(emptyMap())
    val publicationId = item["publicationId"].str() ?: meta["identifier"].str() ?: return null
    val deliverable = meta["deliverable"].arr()?.firstOrNull().obj()
    val formatRaw = (deliverable?.get("contentFormat").str() ?: meta["type"].str() ?: "").lowercase()
    val format = when {
        "pdf" in formatRaw -> BookFormat.PDF
        "epub" in formatRaw || formatRaw.isEmpty() -> BookFormat.EPUB
        else -> BookFormat.OTHER
    }
    // Observed: {"name": null, "firstName": "Rebekka", "lastName": "Endler"}.
    val authors = meta["author"].arr()?.mapNotNull { a ->
        val o = a.obj() ?: return@mapNotNull a.str()
        o["name"].str()?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(o["firstName"].str(), o["lastName"].str()).joinToString(" ").takeIf { it.isNotBlank() }
    }.orEmpty()
    // fileResource mixes cover, spine, full image and a preview EPUB; pick a cover by type.
    val resources = meta["fileResource"].arr().orEmpty().mapNotNull { it.obj() }
    val cover = listOf("SCALEDCOVER", "FRONTCOVERIMAGE").firstNotNullOfOrNull { t ->
        resources.firstOrNull { it["type"].str() == t }?.get("resource").str()
    } ?: resources.firstOrNull { it["format"].str()?.startsWith("image/") == true }?.get("resource").str()
    return RemoteBook(
        publicationId = publicationId,
        deliverableId = item["deliverableId"].str() ?: deliverable?.get("deliverableId").str(),
        identifier = meta["identifier"].str(),
        resellerId = item["resellerId"].int(),
        title = meta["title"].str() ?: "Untitled",
        subtitle = meta["subtitle"].str(),
        authors = authors,
        publisher = meta["publisher"].str(),
        isbn = meta["isbn"].str(),
        language = meta["language"].str(),
        issued = meta["issued"].str(),
        format = format,
        purchased = deliverable?.get("purchased").bool() ?: !isUpload,
        isUpload = isUpload,
        coverUrl = cover,
    )
}
