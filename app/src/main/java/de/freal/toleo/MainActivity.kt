package de.freal.toleo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.freal.toleo.sync.SyncWorker
import de.freal.toleo.ui.account.AccountScreen
import de.freal.toleo.ui.account.AccountViewModel
import de.freal.toleo.ui.library.LibraryScreen
import de.freal.toleo.ui.library.LibraryViewModel
import de.freal.toleo.ui.login.LoginScreen
import de.freal.toleo.ui.theme.ToleoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ToleoApp).container
        setContent {
            ToleoTheme { ToleoNav(container) }
        }
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with Toleo" for local EPUB files: read locally, never synced. */
    private fun handleViewIntent(intent: android.content.Intent?) {
        val uri = intent?.takeIf { it.action == android.content.Intent.ACTION_VIEW }?.data ?: return
        val container = (application as ToleoApp).container
        lifecycleScope.launch {
            val result = runCatching {
                val book = container.library.importLocal(uri)
                container.readerSession = de.freal.toleo.reader.ReaderSession.open(container, book).getOrThrow()
            }
            result.onSuccess { startActivity(de.freal.toleo.reader.ReaderActivity.intent(this@MainActivity)) }
                .onFailure { android.widget.Toast.makeText(this@MainActivity, "Cannot open file: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}

private object Routes {
    const val LOGIN = "login"
    const val LIBRARY = "library"
    const val ACCOUNT = "account?problem={problem}"
    fun account(problem: String? = null) = "account?problem=" + android.net.Uri.encode(problem.orEmpty())
}

@Composable
private fun ToleoNav(container: AppContainer) {
    val nav = rememberNavController()
    val session = container.tokens.session.value
    val start = when {
        session == null -> Routes.LOGIN
        !session.deviceRegistered -> Routes.account("Device not registered")
        else -> Routes.LIBRARY
    }
    NavHost(nav, startDestination = if (start.startsWith("account")) Routes.ACCOUNT else start) {
        composable(Routes.LOGIN) {
            val context = androidx.compose.ui.platform.LocalContext.current
            LoginScreen(
                container = container,
                onLoggedIn = {
                    SyncWorker.schedulePeriodic(context.applicationContext)
                    nav.navigateClearing(Routes.LIBRARY)
                },
                onDeviceProblem = { reason -> nav.navigateClearing(Routes.account(reason)) },
            )
        }
        composable(Routes.LIBRARY) {
            val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val vm = viewModel { LibraryViewModel(container, appContext) }
            LibraryScreen(
                vm = vm,
                onAccount = { nav.navigate(Routes.account()) },
                onNeedsLogin = { nav.navigateClearing(Routes.LOGIN) },
            )
        }
        composable(Routes.ACCOUNT) { entry ->
            val problem = entry.arguments?.getString("problem")?.takeIf { it.isNotBlank() }
                ?: if (start.startsWith("account")) "Device not registered" else null
            val vm = viewModel { AccountViewModel(container) }
            val context = androidx.compose.ui.platform.LocalContext.current
            AccountScreen(
                vm = vm,
                deviceProblem = problem,
                onBack = { if (!nav.popBackStack()) nav.navigateClearing(Routes.LIBRARY) },
                onRegistered = { nav.navigateClearing(Routes.LIBRARY) },
                onLoggedOut = {
                    SyncWorker.cancelAll(context.applicationContext)
                    nav.navigateClearing(Routes.LOGIN)
                },
            )
        }
    }
}

private fun NavHostController.navigateClearing(route: String) = navigate(route) {
    popUpTo(0) { inclusive = true }
}
