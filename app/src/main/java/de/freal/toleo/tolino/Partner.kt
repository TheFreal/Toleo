package de.freal.toleo.tolino

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Tolino partner shop. Loaded from `assets/partners.json` so URLs and IDs can be
 * corrected without touching code (the API is reverse-engineered and changes).
 */
@Serializable
data class Partner(
    val id: String,
    val name: String,
    /** Candidate reseller IDs, tried in order. Thalia.de is reported as both 3 and 2. */
    val resellerIds: List<Int>,
    val authUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val scope: String,
    val skinId: String,
    val mandantId: String,
) {
    /** The scope to request: the partner's own plus `offline_access` while the shop accepts it. */
    val requestScope: String get() = if (OfflineScope.enabled) "$scope ${OfflineScope.NAME}" else scope

    fun authorizeUri(): Uri = Uri.parse(authUrl).buildUpon()
        .appendQueryParameter("client_id", clientId)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("scope", requestScope)
        .appendQueryParameter("redirect_uri", TolinoConstants.REDIRECT_URI)
        .appendQueryParameter("x_buchde.skin_id", skinId)
        .appendQueryParameter("x_buchde.mandant_id", mandantId)
        .build()
}

/**
 * `offline_access` asks the shop for a long-lived refresh token (standard OIDC; unverified whether
 * Thalia's auth service supports it). If the shop answers `invalid_scope`, it is dropped for the
 * rest of the process and the request is repeated without it.
 */
object OfflineScope {
    const val NAME = "offline_access"

    @Volatile
    var enabled = true

    /** True (and disables the scope) when [text] is the shop rejecting it. */
    fun rejectedBy(text: String?): Boolean {
        if (!enabled || text == null || !text.contains("invalid_scope")) return false
        enabled = false
        Diagnostics.log("Shop rejected $NAME; continuing with the normal scope")
        return true
    }
}

object TolinoConstants {
    const val REDIRECT_URI = "https://webreader.mytolino.com/library/"
    const val REFERER = "https://webreader.mytolino.com/"
    const val BASE_URL = "https://bosh.pageplace.de/bosh/rest"
    const val API_HOST = "bosh.pageplace.de"
    const val CLIENT_TYPE = "TOLINO_WEBREADER"
    const val CLIENT_VERSION = "4.4.1"
    const val HARDWARE_TYPE = "HTML5"
    const val DEVICE_NAME = "Toleo (Android)"
}

class PartnerRegistry(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val partners: List<Partner> = context.assets.open("partners.json").use {
        json.decodeFromString(it.readBytes().decodeToString())
    }

    fun byId(id: String): Partner? = partners.firstOrNull { it.id == id }
}
