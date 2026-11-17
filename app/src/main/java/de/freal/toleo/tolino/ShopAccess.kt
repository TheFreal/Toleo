package de.freal.toleo.tolino

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An HTTP(S) proxy for reaching the shop from outside the DACH region. */
@Serializable
data class ShopProxy(
    val enabled: Boolean = false,
    /** "http" or "https" (TLS to the proxy itself). */
    val scheme: String = "http",
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
}

/** The shop login, used only to answer the shop's login form when its session cookie expired. */
@Serializable
data class ShopLogin(
    val enabled: Boolean = false,
    val email: String = "",
    val password: String = "",
    /** Set after an automatic login that didn't get through; cleared when the login is edited. */
    val failedAt: Long? = null,
) {
    val usable: Boolean get() = enabled && email.isNotBlank() && password.isNotBlank() && failedAt == null
}

@Serializable
data class ShopAccessSettings(val proxy: ShopProxy = ShopProxy(), val login: ShopLogin = ShopLogin())

/**
 * Settings for reaching the partner shop, stored encrypted with a key that never leaves Android
 * Keystore.
 *
 * The proxy is applied to the app's WebViews only, and (where the WebView supports it) only for
 * the shop domains: book downloads and the tolino API keep going direct.
 */
class ShopAccess(private val context: Context, private val partners: PartnerRegistry) {
    private val prefs = context.getSharedPreferences("toleo_shop_access", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ShopAccessSettings> = _settings

    val proxy: ShopProxy get() = _settings.value.proxy
    val login: ShopLogin get() = _settings.value.login

    fun update(transform: (ShopAccessSettings) -> ShopAccessSettings) {
        val before = _settings.value
        val next = transform(before)
        save(next)
        if (next.proxy != before.proxy) applyProxy()
    }

    fun markLoginFailed() = update { it.copy(login = it.login.copy(failedAt = System.currentTimeMillis())) }

    /** Hosts that should go through the proxy: every partner's auth and token host, plus subdomains. */
    private fun shopHosts(): List<String> = partners.partners
        .flatMap { listOf(it.authUrl, it.tokenUrl) }
        .mapNotNull { android.net.Uri.parse(it).host }
        .map { it.removePrefix("www.") }
        .distinct()

    /** Installs or clears the WebView proxy override. Call on start and after changes. */
    fun applyProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Diagnostics.log("WebView doesn't support proxy override; shop proxy unavailable")
            return
        }
        val controller = ProxyController.getInstance()
        val p = proxy
        val done = Runnable { }
        if (!p.usable) {
            controller.clearProxyOverride({ it.run() }, done)
            return
        }
        val builder = ProxyConfig.Builder().addProxyRule("${p.scheme}://${p.host}:${p.port}")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
            // Reverse bypass: the listed hosts are the only ones that use the proxy.
            shopHosts().forEach { host -> builder.addBypassRule(host); builder.addBypassRule("*.$host") }
            builder.setReverseBypassEnabled(true)
            Diagnostics.log("Shop proxy ${p.host}:${p.port} for ${shopHosts().joinToString()}")
        } else {
            // Older WebView: everything goes through the proxy except the tolino hosts we know.
            listOf("*.pageplace.de", "pageplace.de", "*.mytolino.com", "mytolino.com", "<local>", "readium").forEach(builder::addBypassRule)
            Diagnostics.log("Shop proxy ${p.host}:${p.port} for all WebView traffic except tolino hosts")
        }
        controller.setProxyOverride(builder.build(), { it.run() }, done)
    }

    /** Answers the proxy's authentication challenge, if [host] is our proxy. */
    fun proxyCredentials(host: String?): Pair<String, String>? {
        val p = proxy
        if (!p.usable || p.username.isBlank() || host == null || !host.equals(p.host, ignoreCase = true)) return null
        return p.username to p.password
    }

    // ------------------------------------------------------------------ storage

    private fun save(settings: ShopAccessSettings) {
        val plain = json.encodeToString(ShopAccessSettings.serializer(), settings).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(plain)
        prefs.edit { putString(KEY, Base64.encodeToString(blob, Base64.NO_WRAP)) }
        _settings.value = settings
    }

    private fun load(): ShopAccessSettings = runCatching {
        val blob = Base64.decode(prefs.getString(KEY, null) ?: return ShopAccessSettings(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
        json.decodeFromString(ShopAccessSettings.serializer(), cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE).decodeToString())
    }.getOrDefault(ShopAccessSettings())

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "toleo_shop_access_key"
        const val KEY = "settings"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12

        /**
         * Fills and submits the shop's login form. Generic on purpose (the Thalia form couldn't be
         * inspected from outside the DACH region): it handles one-step forms and two-step forms
         * that ask for the email first. Returns what it did.
         */
        const val FILL_JS = """(function(email,password){
  function vis(el){ if(!el||el.disabled) return false; var r=el.getBoundingClientRect(); var s=getComputedStyle(el); return s.display!=='none'&&s.visibility!=='hidden'&&(r.width>0||r.height>0||el.offsetParent!==null); }
  function find(sel){ return Array.prototype.slice.call(document.querySelectorAll(sel)).filter(vis)[0]; }
  function set(el,v){ var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value'); d.set.call(el,v); el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }
  var pw=find('input[type=password]');
  var em=find('input[type=email],input[autocomplete=username],input[autocomplete=email],input[name*=mail i],input[id*=mail i],input[name*=user i],input[name*=login i]');
  if(!pw&&!em) return 'no-form';
  if(em&&em.value!==email) set(em,email);
  if(pw) set(pw,password);
  var form=(pw||em).form;
  var btn=form?Array.prototype.slice.call(form.querySelectorAll('button[type=submit],input[type=submit],button:not([type])')).filter(vis)[0]:null;
  if(btn) btn.click(); else if(form){ if(form.requestSubmit) form.requestSubmit(); else form.submit(); }
  return pw?'password':'email';
})"""
    }

    /** The script call for the stored login, with values JSON-escaped. */
    fun fillScript(): String? {
        val l = login
        if (!l.usable) return null
        val e = kotlinx.serialization.json.JsonPrimitive(l.email).toString()
        val p = kotlinx.serialization.json.JsonPrimitive(l.password).toString()
        return "$FILL_JS($e,$p)"
    }
}
