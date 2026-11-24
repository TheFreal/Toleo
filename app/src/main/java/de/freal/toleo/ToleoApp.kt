package de.freal.toleo

import android.app.Application
import androidx.glance.appwidget.updateAll
import de.freal.toleo.sync.SyncWorker
import de.freal.toleo.widget.CurrentBookWidget
import kotlinx.coroutines.launch

class ToleoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.shopAccess.applyProxy()
        if (container.auth.isLoggedIn) SyncWorker.schedulePeriodic(this)
        // Keep the home screen widget current while the process runs: reading in the app, a
        // background sync and changes made on other devices all end up in the database.
        container.appScope.launch {
            CurrentBookWidget.currentBook(container.db).collect {
                runCatching { CurrentBookWidget().updateAll(this@ToleoApp) }
            }
        }
    }
}
