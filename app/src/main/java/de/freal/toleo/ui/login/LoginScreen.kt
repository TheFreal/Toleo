package de.freal.toleo.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.material3.Card
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.freal.toleo.AppContainer
import de.freal.toleo.tolino.LoginResult
import de.freal.toleo.tolino.Partner
import de.freal.toleo.tolino.TolinoConstants
import kotlinx.coroutines.launch

/**
 * Partner picker, then the partner's own login page in a WebView. We only watch for the
 * redirect to the web reader URL and take the `code` from it; credentials never touch the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit, onDeviceProblem: (String) -> Unit) {
    var partner by remember { mutableStateOf<Partner?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = partner != null && !working) { partner = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(partner?.let { "Sign in · ${it.name}" } ?: "Connect your tolino account") },
                navigationIcon = {
                    if (partner != null && !working) IconButton(onClick = { partner = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val p = partner
            when {
                working -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Connecting to the tolino cloud…", Modifier.padding(16.dp))
                }
                p == null -> PartnerList(container, error) { error = null; partner = it }
                else -> PartnerLogin(p, container.shopAccess) { code ->
                    working = true
                    scope.launch {
                        when (val r = container.auth.completeLogin(p, code)) {
                            LoginResult.Success -> onLoggedIn()
                            is LoginResult.DeviceNotRegistered -> onDeviceProblem(r.reason)
                            is LoginResult.Failed -> { error = r.reason; partner = null }
                        }
                        working = false
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerList(container: AppContainer, error: String?, onPick: (Partner) -> Unit) {
    val partners = container.partners.partners
    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(Modifier.widthIn(max = 560.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Where did you create your tolino account?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "You sign in on your bookseller's own page. Toleo keeps only an access token, encrypted on this device, unless you turn on automatic re-login below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        items(partners) { partner ->
            Column(Modifier.widthIn(max = 560.dp)) {
                ListItem(
                    headlineContent = { Text(partner.name) },
                    leadingContent = { Icon(Icons.Filled.Storefront, null) },
                    modifier = Modifier.fillMaxWidth().clickable { onPick(partner) },
                )
                HorizontalDivider()
            }
        }
        item {
            de.freal.toleo.ui.account.ShopAccessSection(container, Modifier.padding(24.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PartnerLogin(partner: Partner, shopAccess: de.freal.toleo.tolino.ShopAccess, onCode: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var handled by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Some bot protection rejects the WebView marker; present as plain Chrome.
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv", "")
                        .replace(Regex("Version/\\d+\\.\\d+ "), "")
                    webViewClient = object : WebViewClient() {
                        fun intercept(url: String): Boolean {
                            if (!url.startsWith(TolinoConstants.REDIRECT_URI)) return false
                            val code = android.net.Uri.parse(url).getQueryParameter("code")
                            if (code == null && de.freal.toleo.tolino.OfflineScope.rejectedBy(android.net.Uri.parse(url).getQueryParameter("error"))) {
                                webView?.post { webView?.loadUrl(partner.authorizeUri().toString()) }
                                return true
                            }
                            if (code != null && !handled) {
                                handled = true
                                // Persist the shop's session cookie now; silent re-login depends on it.
                                CookieManager.getInstance().flush()
                                onCode(code)
                            }
                            return true
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                            intercept(request.url.toString())

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            loading = true
                            if (intercept(url)) view.stopLoading()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                        }

                        // The Thalia-platform shops sit behind a Cloudflare WAF that answers 403
                        // ("Zugriff geblockt") for whole regions, e.g. any IP outside the DACH area.
                        override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String?, realm: String?) {
                            val creds = shopAccess.proxyCredentials(host)
                            if (creds != null) handler.proceed(creds.first, creds.second) else handler.cancel()
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame && response.statusCode == 403) blocked = true
                        }
                    }
                    webView = this
                    loadUrl(partner.authorizeUri().toString())
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        if (blocked) {
            Card(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${partner.name} is blocking this connection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The shop's firewall rejects requests from some countries and networks. If you are outside " +
                            "Germany, Austria or Switzerland, connect through a VPN located there, or set a proxy under \"Access from abroad\" on the previous screen. " +
                            "It's needed for signing in and when the session is renewed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        blocked = false
                        webView?.loadUrl(partner.authorizeUri().toString())
                    }) { Text("Try again") }
                }
            }
        }
    }
}
