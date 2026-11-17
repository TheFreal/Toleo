package de.freal.toleo.tolino

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log of Tolino/partner HTTP traffic for debugging the reverse-engineered API on a real
 * device. Tokens, codes and cookies are redacted; bodies are truncated. Nothing leaves the phone
 * unless the user copies it.
 */
object Diagnostics {
    private const val MAX_ENTRIES = 80
    private const val MAX_BODY = 1500
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries
    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private val secretHeaders = setOf("t_auth_token", "authorization", "cookie", "set-cookie")
    private val secretFields = Regex(
        """("?(?:access_token|refresh_token|auth_token|code|id_token|t_auth_token)"?\s*[:=]\s*"?)([^"&,\s}]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(s: String) = secretFields.replace(s) { it.groupValues[1] + "…redacted…" }

    fun log(line: String) = _entries.update { (it + "${time.format(Date())} $line").takeLast(MAX_ENTRIES) }

    fun clear() { _entries.value = emptyList() }

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        // Downloads of book files are large and uninteresting.
        val isFile = req.url.encodedPath.endsWith(".epub") || req.url.encodedPath.endsWith(".pdf")
        val reqBody = req.body?.takeIf { !isFile && (it.contentLength() in 0..20_000) }?.let { body ->
            runCatching { Buffer().also { body.writeTo(it) }.readUtf8() }.getOrNull()
        }
        val headers = req.headers.joinToString(", ") { (k, v) ->
            if (k.lowercase() in secretHeaders) "$k=…" else "$k=$v"
        }
        val url = redact(req.url.toString())
        val started = System.currentTimeMillis()
        val resp: Response = try {
            chain.proceed(req)
        } catch (e: Exception) {
            log("✗ ${req.method} $url → ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val peek = if (isFile) "(file)" else runCatching { resp.peekBody(MAX_BODY.toLong()).string() }.getOrDefault("")
        log(
            buildString {
                append("${req.method} $url → ${resp.code} (${System.currentTimeMillis() - started} ms)\n")
                append("  req headers: $headers\n")
                reqBody?.let { append("  req body: ${redact(it).take(MAX_BODY)}\n") }
                if (resp.isRedirect) append("  location: ${redact(resp.header("Location").orEmpty())}\n")
                append("  resp: ${redact(peek)}")
            },
        )
        resp
    }
}
