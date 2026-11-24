package de.freal.toleo

import android.content.Context
import android.webkit.WebSettings
import de.freal.toleo.db.ToleoDatabase
import de.freal.toleo.library.LibraryRepository
import de.freal.toleo.library.ReadingRepository
import de.freal.toleo.library.ReadiumServices
import de.freal.toleo.reader.ReaderSession
import de.freal.toleo.sync.SyncEngine
import de.freal.toleo.tolino.PartnerRegistry
import de.freal.toleo.tolino.TokenStore
import de.freal.toleo.tolino.TolinoApi
import de.freal.toleo.tolino.TolinoAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph; one instance per process, owned by [ToleoApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** WebView's UA minus the "wv" marker, so API calls look like the browser that logged in. */
    val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android)")
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .addInterceptor(de.freal.toleo.tolino.Diagnostics.interceptor)
        .build()

    val partners = PartnerRegistry(context)
    val tokens = TokenStore(context)
    val shopAccess = de.freal.toleo.tolino.ShopAccess(context, partners)
    val browser = de.freal.toleo.tolino.BrowserTokenClient(context, shopAccess)
    val auth = TolinoAuth(http, tokens, partners, browser, shopAccess)
    val api = TolinoApi(http, auth).also { auth.api = it }
    val db = ToleoDatabase.create(context)
    val sync = SyncEngine(db, api, tokens)
    val readium = ReadiumServices(context)
    val library = LibraryRepository(context, db, api)
    val reading = ReadingRepository(db)

    /** The publication handed from the library to [de.freal.toleo.reader.ReaderActivity]. */
    @Volatile var readerSession: ReaderSession? = null

    /** A book the home screen widget asked to open; the library opens it and clears this. */
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
