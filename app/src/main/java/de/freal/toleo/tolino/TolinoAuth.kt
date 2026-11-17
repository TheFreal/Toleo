package de.freal.toleo.tolino

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class NeedsLoginException(message: String = "Tolino login required", val blocked: Boolean = false) : Exception(message)

/** The shop's firewall rejected us (Thalia blocks whole countries; observed from Australia). */
class ShopBlockedException : Exception("The shop blocks this network (VPN in DE/AT/CH needed)")

/** Why the cloud is currently out of reach, shown as a banner instead of throwing the user out. */
enum class AuthProblem {
    /** Token renewal hit the shop's geo-block. The session is still fine; retry from another network. */
    BLOCKED,
    /** The shop wants credentials again. */
    EXPIRED,
}

sealed interface LoginResult {
    data object Success : LoginResult
    /** Logged in, but the cloud refused to register this device (usually: 5 device limit reached). */
    data class DeviceNotRegistered(val reason: String) : LoginResult
    data class Failed(val reason: String) : LoginResult
}

/**
 * OAuth against the partner shop (authorization code flow, code captured from the WebView),
 * token refresh, and device registration.
 */
class TolinoAuth(
    private val http: OkHttpClient,
    private val store: TokenStore,
    private val partners: PartnerRegistry,
    private val browser: BrowserTokenClient,
    private val shopAccess: ShopAccess,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()

    /** Set by the container once the API exists (the API needs auth, registration needs the API). */
    lateinit var api: TolinoApi

    val isLoggedIn: Boolean get() = store.session.value != null

    private val _problem = kotlinx.coroutines.flow.MutableStateFlow<AuthProblem?>(null)
    val problem: kotlinx.coroutines.flow.StateFlow<AuthProblem?> = _problem

    suspend fun completeLogin(partner: Partner, code: String): LoginResult = withContext(Dispatchers.IO) {
        val tokens = try {
            tokenRequest(
                partner,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to TolinoConstants.REDIRECT_URI,
            )
        } catch (e: Exception) {
            return@withContext LoginResult.Failed("Token exchange failed: ${e.message}")
        }
        val now = System.currentTimeMillis()
        val base = Session(
            partnerId = partner.id,
            resellerId = partner.resellerIds.first(),
            hardwareId = store.hardwareId,
            accessToken = tokens.str("access_token") ?: return@withContext LoginResult.Failed("No access token in response"),
            refreshToken = tokens.str("refresh_token"),
            accessExpiresAt = now + (tokens.num("expires_in") ?: 3600) * 1000,
            refreshExpiresAt = refreshExpiry(tokens, now),
        )
        logTokenLifetimes(tokens)
        store.save(base)
        _problem.value = null
        registerDevice()
    }

    /** Registers our hardware ID, probing the partner's candidate reseller IDs. */
    suspend fun registerDevice(): LoginResult {
        val session = store.session.value ?: return LoginResult.Failed("Not logged in")
        val partner = partners.byId(session.partnerId) ?: return LoginResult.Failed("Unknown partner")
        var lastError: String? = null
        for (resellerId in listOf(session.resellerId) + (partner.resellerIds - session.resellerId)) {
            store.save(session.copy(resellerId = resellerId))
            try {
                api.registerHardware()
                store.save(store.session.value!!.copy(deviceRegistered = true))
                return LoginResult.Success
            } catch (e: TolinoException) {
                // The cloud answers an already-registered ID with an error; inventory access proves we're fine.
                if (runCatching { api.inventory() }.isSuccess) {
                    store.save(store.session.value!!.copy(deviceRegistered = true))
                    return LoginResult.Success
                }
                lastError = e.message
            }
        }
        store.save(store.session.value!!.copy(resellerId = session.resellerId))
        return LoginResult.DeviceNotRegistered(lastError ?: "Device registration failed")
    }

    /** Returns a session with a valid access token, refreshing if needed. */
    suspend fun freshSession(): Session = refreshLock.withLock {
        val s = store.session.value ?: throw NeedsLoginException()
        if (s.accessExpiresAt - System.currentTimeMillis() > 60_000) return s
        refreshLocked(s)
    }

    /** Forces a refresh, used after the API answers 401. */
    suspend fun forceRefresh(): Session = refreshLock.withLock {
        refreshLocked(store.session.value ?: throw NeedsLoginException())
    }

    private suspend fun refreshLocked(s: Session): Session = try {
        refreshTokens(s).also { _problem.value = null }
    } catch (e: ShopBlockedException) {
        Diagnostics.log("Token renewal blocked by the shop's firewall; keeping the session")
        _problem.value = AuthProblem.BLOCKED
        throw NeedsLoginException("Can't reach the shop from this network", blocked = true)
    } catch (e: NeedsLoginException) {
        _problem.value = AuthProblem.EXPIRED
        throw e
    }

    private suspend fun refreshTokens(s: Session): Session = withContext(Dispatchers.IO) {
        val partner = partners.byId(s.partnerId) ?: throw NeedsLoginException()
        val refresh = s.refreshToken
        val refreshUsable = refresh != null && (s.refreshExpiresAt == null || s.refreshExpiresAt > System.currentTimeMillis())
        val tokens = if (refreshUsable) {
            try {
                tokenRequest(partner, "grant_type" to "refresh_token", "refresh_token" to refresh!!)
            } catch (e: TolinoException) {
                Diagnostics.log("Token refresh rejected (${e.message}); trying silent re-login")
                silentReauth(partner)
            }
        } else {
            // Refresh tokens live about an hour; the shop's own login cookie lasts much longer.
            silentReauth(partner)
        }
        val now = System.currentTimeMillis()
        val updated = s.copy(
            accessToken = tokens.str("access_token") ?: throw NeedsLoginException(),
            refreshToken = tokens.str("refresh_token") ?: refresh, // server rotates; keep the newest
            accessExpiresAt = now + (tokens.num("expires_in") ?: 3600) * 1000,
            refreshExpiresAt = if (tokens.containsKey("refresh_expires_in")) refreshExpiry(tokens, now) else s.refreshExpiresAt,
        )
        logTokenLifetimes(tokens)
        store.save(updated)
        updated
    }

    /** Gets a new authorization code from the shop's session cookie, without user interaction. */
    private suspend fun silentReauth(partner: Partner): JsonObject {
        var result = browser.silentAuthorize(partner.authorizeUri().toString())
        if (result is BrowserTokenClient.AuthorizeResult.Error && OfflineScope.rejectedBy(result.error)) {
            result = browser.silentAuthorize(partner.authorizeUri().toString())
        }
        val code = when (val r = result) {
            is BrowserTokenClient.AuthorizeResult.Code -> r.code
            is BrowserTokenClient.AuthorizeResult.Error -> throw NeedsLoginException("Re-login refused: ${r.error}")
            BrowserTokenClient.AuthorizeResult.LoginRejected -> {
                // Don't retry a login that didn't work; the user fixes it in the account settings.
                shopAccess.markLoginFailed()
                throw NeedsLoginException("Automatic login didn't get through")
            }
            BrowserTokenClient.AuthorizeResult.Blocked -> throw ShopBlockedException()
            BrowserTokenClient.AuthorizeResult.Unreachable -> throw java.io.IOException("Can't reach ${partner.name} right now")
            BrowserTokenClient.AuthorizeResult.NeedsCredentials -> throw NeedsLoginException("Tolino session expired")
        }
        Diagnostics.log("Silent re-login obtained a new authorization code")
        return try {
            tokenRequest(
                partner,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to TolinoConstants.REDIRECT_URI,
            )
        } catch (e: TolinoException) {
            throw NeedsLoginException("Re-login failed: ${e.message}")
        }
        // ShopBlockedException propagates as is.
    }

    fun logout() {
        store.clear()
        _problem.value = null
    }

    /**
     * Token calls go through a WebView first: the shops block non-browser TLS clients on these
     * endpoints (observed: thalia.de answers curl with 403 "Zugriff geblockt"). OkHttp is only a
     * fallback for when no WebView is available.
     */
    private suspend fun tokenRequest(partner: Partner, vararg params: Pair<String, String>): JsonObject {
        val form = listOf("client_id" to partner.clientId, "scope" to partner.requestScope) + params
        val (status, body) = try {
            browser.postForm(partner.tokenUrl, form).also {
                Diagnostics.log("WebView POST ${partner.tokenUrl} → ${it.status}\n  resp: ${Diagnostics.redact(it.body.take(1500))}")
            }.let { it.status to it.body }
        } catch (e: Exception) {
            Diagnostics.log("WebView token request unavailable (${e.javaClass.simpleName}: ${e.message}); falling back to OkHttp")
            okHttpTokenRequest(partner, form)
        }
        if (status == 403 && body.contains("Zugriff geblockt", ignoreCase = true)) throw ShopBlockedException()
        if (status == 400 && OfflineScope.rejectedBy(body)) return tokenRequest(partner, *params)
        if (status !in 200..299) {
            val reason = when {
                status == -1 -> "network/CORS error: $body"
                body.contains("Zugriff geblockt", ignoreCase = true) ->
                    "blocked by the shop's firewall (it rejects some countries/networks; try a VPN in DE/AT/CH)"
                else -> body.take(300)
            }
            throw TolinoException(status, null, reason)
        }
        return runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw TolinoException(status, null, "Token response is not JSON: ${body.take(200)}") }
    }

    private suspend fun okHttpTokenRequest(partner: Partner, form: List<Pair<String, String>>): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder()
                .url(partner.tokenUrl)
                .header("Referer", TolinoConstants.REFERER)
                .post(body)
                .build()
            http.newCall(request).execute().use { it.code to it.body.string() }
        }

    /** `refresh_expires_in` of 0 (or absent) means the refresh token doesn't expire (offline tokens). */
    private fun refreshExpiry(tokens: JsonObject, now: Long): Long? =
        tokens.num("refresh_expires_in")?.takeIf { it > 0 }?.let { now + it * 1000 }

    private fun logTokenLifetimes(tokens: JsonObject) = Diagnostics.log(
        "Tokens: expires_in=${tokens.num("expires_in")} refresh_expires_in=${tokens.num("refresh_expires_in")} " +
            "scope=${tokens.str("scope")} token_type=${tokens.str("token_type")} (requested ${OfflineScope.NAME}: ${OfflineScope.enabled})",
    )

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.longOrNull
}
