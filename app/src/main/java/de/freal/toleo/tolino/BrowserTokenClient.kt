package de.freal.toleo.tolino

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Sends OAuth token requests from inside a headless WebView, the way the official web reader does.
 *
 * Partner shops (verified for thalia.de) answer non-browser clients with "403 Zugriff geblockt"
 * based on the TLS/client fingerprint, so OkHttp can't reach the token endpoint. A WebView uses
 * Chrome's network stack and shares the cookies from the login. The request runs from a blank
 * document whose origin is https://webreader.mytolino.com, so Origin/Referer match the official
 * client and the shop's CORS policy for it applies.
 */
class BrowserTokenClient(private val context: Context, private val shopAccess: ShopAccess) {

    data class Response(val status: Int, val body: String)

    suspend fun postForm(url: String, form: List<Pair<String, String>>): Response = withContext(Dispatchers.Main) {
        withTimeout(45_000) {
            suspendCancellableCoroutine { cont ->
                val webView = createWebView()
                val finished = AtomicBoolean(false)
                val main = Handler(Looper.getMainLooper())

                fun finish(response: Response) {
                    if (!finished.compareAndSet(false, true)) return
                    main.post {
                        webView.destroy()
                        if (cont.isActive) cont.resume(response)
                    }
                }

                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun done(status: Int, body: String) = finish(Response(status, body))
                    },
                    BRIDGE,
                )
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        view.evaluateJavascript(script(url, form), null)
                    }

                    override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String?, realm: String?) =
                        answerProxyAuth(handler, host)
                }
                webView.loadDataWithBaseURL(TolinoConstants.REDIRECT_URI, "<!doctype html><title>Toleo</title>", "text/html", "utf-8", null)
                cont.invokeOnCancellation { main.post { if (finished.compareAndSet(false, true)) webView.destroy() } }
            }
        }
    }

    sealed interface AuthorizeResult {
        data class Code(val code: String) : AuthorizeResult
        /** The shop's firewall answered 403 (geo-block). */
        data object Blocked : AuthorizeResult
        /** Offline, DNS failure, timeout. Not a reason to sign in again. */
        data object Unreachable : AuthorizeResult
        /** The shop showed its login form: its own session cookie is gone. */
        data object NeedsCredentials : AuthorizeResult
        /** The shop redirected back with an OAuth error instead of a code. */
        data class Error(val error: String) : AuthorizeResult
        /** The stored login was submitted but didn't get through (wrong password, captcha, …). */
        data object LoginRejected : AuthorizeResult
    }

    /**
     * Loads the partner's authorize URL invisibly. While the shop's own session cookie (set when
     * the user logged in through the WebView) is valid, the shop redirects straight to the web
     * reader URL with a fresh `code`; otherwise its login page appears and we give up.
     * Needed because refresh tokens are short-lived (observed: `refresh_expires_in` 3600 s).
     */
    suspend fun silentAuthorize(authorizeUrl: String): AuthorizeResult = withContext(Dispatchers.Main) {
        // Automatic login at most every few minutes, so a misbehaving form can't hammer the shop.
        val now = android.os.SystemClock.elapsedRealtime()
        val autoLogin = shopAccess.fillScript()?.takeIf { lastAutoLogin == 0L || now - lastAutoLogin > AUTO_LOGIN_INTERVAL_MS }
        kotlinx.coroutines.withTimeoutOrNull(if (autoLogin != null) 60_000 else 20_000) {
            suspendCancellableCoroutine<AuthorizeResult> { cont ->
                val webView = createWebView()
                val finished = AtomicBoolean(false)
                val main = Handler(Looper.getMainLooper())
                var fillRuns = 0
                var passwordSent = false
                fun finish(result: AuthorizeResult) {
                    if (!finished.compareAndSet(false, true)) return
                    webView.stopLoading()
                    webView.destroy()
                    if (cont.isActive) cont.resume(result)
                }

                /** Fills the shop's login form with the stored login (two-step forms take two runs). */
                fun fillLogin() {
                    if (finished.get() || autoLogin == null) return
                    if (fillRuns >= 4) { finish(if (passwordSent) AuthorizeResult.LoginRejected else AuthorizeResult.NeedsCredentials); return }
                    fillRuns++
                    lastAutoLogin = android.os.SystemClock.elapsedRealtime()
                    webView.evaluateJavascript(autoLogin) { raw ->
                        val step = raw?.trim('"').orEmpty()
                        Diagnostics.log("Automatic login: $step")
                        when (step) {
                            "password" -> {
                                passwordSent = true
                                // A good login redirects to the web reader; still here means it was refused.
                                main.postDelayed({ finish(AuthorizeResult.LoginRejected) }, 25_000)
                            }
                            "email" -> main.postDelayed({ fillLogin() }, 2_500) // the password step may not reload
                            else -> main.postDelayed({ fillLogin() }, 1_500) // form not rendered yet
                        }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    fun check(url: String): Boolean {
                        if (!url.startsWith(TolinoConstants.REDIRECT_URI)) return false
                        val uri = android.net.Uri.parse(url)
                        val code = uri.getQueryParameter("code")
                        val error = uri.getQueryParameter("error")
                        finish(
                            when {
                                code != null -> AuthorizeResult.Code(code)
                                error != null -> AuthorizeResult.Error(error)
                                else -> AuthorizeResult.NeedsCredentials
                            },
                        )
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                        check(request.url.toString())

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        check(url)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        response: android.webkit.WebResourceResponse,
                    ) {
                        if (request.isForMainFrame && response.statusCode == 403) finish(AuthorizeResult.Blocked)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) finish(AuthorizeResult.Unreachable)
                    }

                    override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String?, realm: String?) =
                        answerProxyAuth(handler, host)

                    override fun onPageFinished(view: WebView, url: String) {
                        // A page that finished loading on the shop means it wants credentials.
                        if (url.startsWith(TolinoConstants.REDIRECT_URI)) return
                        when {
                            autoLogin == null -> main.postDelayed({ finish(AuthorizeResult.NeedsCredentials) }, 2_000)
                            passwordSent -> main.postDelayed({ finish(AuthorizeResult.LoginRejected) }, 4_000)
                            else -> main.postDelayed({ fillLogin() }, 800)
                        }
                    }
                }
                webView.loadUrl(authorizeUrl)
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { finish(AuthorizeResult.Unreachable) } }
            }
        } ?: AuthorizeResult.Unreachable
    }

    /**
     * Loads [url] in a hidden WebView and reports the main page's HTTP status (200 when it loaded
     * without an error status, -1 for network errors). Used to test the shop proxy.
     */
    suspend fun probe(url: String): Int = withContext(Dispatchers.Main) {
        kotlinx.coroutines.withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Int> { cont ->
                val webView = createWebView()
                val finished = AtomicBoolean(false)
                fun finish(status: Int) {
                    if (!finished.compareAndSet(false, true)) return
                    webView.stopLoading()
                    webView.destroy()
                    if (cont.isActive) cont.resume(status)
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) {
                        if (request.isForMainFrame) finish(response.statusCode)
                    }
                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) finish(-1)
                    }
                    override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String?, realm: String?) =
                        answerProxyAuth(handler, host)
                    override fun onPageFinished(view: WebView, url: String) = finish(200)
                }
                webView.loadUrl(url)
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { finish(-1) } }
            }
        } ?: -1
    }

    private fun answerProxyAuth(handler: android.webkit.HttpAuthHandler, host: String?) {
        val creds = shopAccess.proxyCredentials(host)
        if (creds != null) handler.proceed(creds.first, creds.second) else handler.cancel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(context).apply {
        // Not attached to a window: give it a phone-sized viewport so pages lay out normally.
        layout(0, 0, 1080, 2000)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    }

    private fun script(url: String, form: List<Pair<String, String>>): String {
        val formJson = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject { form.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        )
        val urlJson = JsonPrimitive(url).toString()
        // Try without cookies first (what a cross-origin web app sends), then with them in case
        // the shop's bot protection wants its cookies and allows credentialed CORS.
        return """
            (async () => {
              const body = new URLSearchParams($formJson).toString();
              let last = 'no attempt';
              for (const credentials of ['omit', 'include']) {
                try {
                  const r = await fetch($urlJson, {
                    method: 'POST',
                    body,
                    credentials,
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' },
                  });
                  $BRIDGE.done(r.status, await r.text());
                  return;
                } catch (e) {
                  last = String(e);
                }
              }
              $BRIDGE.done(-1, last);
            })();
        """.trimIndent()
    }

    private var lastAutoLogin = 0L

    private companion object {
        const val BRIDGE = "ToleoBridge"
        const val AUTO_LOGIN_INTERVAL_MS = 5 * 60_000L
    }
}
